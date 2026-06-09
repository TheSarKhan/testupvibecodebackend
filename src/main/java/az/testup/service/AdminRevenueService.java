package az.testup.service;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.dto.response.PendingOrderResponse;
import az.testup.dto.response.RevenueStatsResponse;
import az.testup.entity.PaymentOrder;
import az.testup.entity.User;
import az.testup.enums.AuditAction;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminRevenueService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final KapitalBankService kapitalBankService;
    private final UserSubscriptionService userSubscriptionService;
    private final ExamService examService;
    private final PricingService pricingService;
    private final AuditLogService auditLogService;

    public RevenueStatsResponse getRevenueStats() {
        double total = nullToZero(paymentOrderRepository.totalRevenue());
        double thisMonth = nullToZero(paymentOrderRepository.thisMonthRevenue());
        double lastMonth = nullToZero(paymentOrderRepository.lastMonthRevenue());
        double growth = lastMonth > 0
                ? ((thisMonth - lastMonth) / lastMonth) * 100.0
                : (thisMonth > 0 ? 100.0 : 0.0);
        long totalPaidOrders = paymentOrderRepository.countByStatus("PAID");
        long activeSubscriptions = userSubscriptionRepository.countActiveSubscriptions();

        List<RevenueStatsResponse.MonthlyItem> monthly = new ArrayList<>();
        for (Object[] row : paymentOrderRepository.monthlyRevenue()) {
            monthly.add(new RevenueStatsResponse.MonthlyItem(
                    row[0].toString(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()
            ));
        }
        Collections.reverse(monthly);

        List<RevenueStatsResponse.PlanItem> byPlan = new ArrayList<>();
        for (Object[] row : paymentOrderRepository.revenueByPlan()) {
            byPlan.add(new RevenueStatsResponse.PlanItem(
                    row[0].toString(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()
            ));
        }

        List<RevenueStatsResponse.RecentPayment> recent = new ArrayList<>();
        for (PaymentOrder o : paymentOrderRepository.findTop10ByStatusOrderByCreatedAtDesc("PAID")) {
            // PaymentOrder is either a subscription (plan set) or an exam purchase (exam
            // set, plan null). Display the relevant title in the "plan" slot so both kinds
            // surface in the recent-payments feed without an NPE.
            String label = o.getPlan() != null
                    ? o.getPlan().getName()
                    : (o.getExam() != null ? o.getExam().getTitle() : "—");
            recent.add(new RevenueStatsResponse.RecentPayment(
                    o.getOrderId(),
                    o.getUser() != null ? o.getUser().getEmail() : "",
                    o.getUser() != null ? o.getUser().getFullName() : "",
                    label,
                    o.getAmount(),
                    o.getDurationDays(),
                    o.getCreatedAt() != null ? o.getCreatedAt().toString() : ""
            ));
        }

        List<RevenueStatsResponse.StatusItem> statusBreakdown = new ArrayList<>();
        for (Object[] row : paymentOrderRepository.statusBreakdown()) {
            statusBreakdown.add(new RevenueStatsResponse.StatusItem(
                    (String) row[0],
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).doubleValue()
            ));
        }

        return new RevenueStatsResponse(
                total, thisMonth, lastMonth,
                Math.round(growth * 10.0) / 10.0,
                totalPaidOrders, activeSubscriptions,
                monthly, byPlan, recent, statusBreakdown
        );
    }

    public String exportCsv(String status, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        String effectiveStatus = (status == null || status.isBlank()) ? "PAID" : status.toUpperCase();
        List<PaymentOrder> orders = paymentOrderRepository.findForExport(effectiveStatus, from, to);

        StringBuilder sb = new StringBuilder();
        sb.append("Order ID,User Email,User Name,Type,Plan/Exam,Amount (AZN),Duration Days,Months,Status,Created At\n");
        for (PaymentOrder o : orders) {
            boolean isExam = o.getExam() != null;
            String planOrExam = isExam
                    ? o.getExam().getTitle()
                    : (o.getPlan() != null ? o.getPlan().getName() : "");
            sb.append(csvEscape(o.getOrderId())).append(',')
              .append(csvEscape(o.getUser() != null ? o.getUser().getEmail() : "")).append(',')
              .append(csvEscape(o.getUser() != null ? o.getUser().getFullName() : "")).append(',')
              .append(isExam ? "EXAM" : "SUBSCRIPTION").append(',')
              .append(csvEscape(planOrExam)).append(',')
              .append(o.getAmount()).append(',')
              .append(o.getDurationDays()).append(',')
              .append(o.getMonths()).append(',')
              .append(o.getStatus()).append(',')
              .append(o.getCreatedAt() != null ? o.getCreatedAt().toString() : "")
              .append('\n');
        }
        return sb.toString();
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public org.springframework.data.domain.Page<PendingOrderResponse> getPendingOrders(
            org.springframework.data.domain.Pageable pageable) {
        return paymentOrderRepository.findByStatusOrderByCreatedAtDesc("PENDING", pageable)
                .map(this::toPendingResponse);
    }

    private PendingOrderResponse toPendingResponse(PaymentOrder o) {
        boolean isExam = o.getExam() != null;
        String planName = isExam
                ? ("İmtahan: " + o.getExam().getTitle())
                : (o.getPlan() != null ? o.getPlan().getName() : "(silinmiş plan)");
        return new PendingOrderResponse(
                o.getId(),
                o.getOrderId(),
                o.getUser() != null ? o.getUser().getEmail() : "",
                o.getUser() != null ? o.getUser().getFullName() : "",
                planName,
                o.getAmount(),
                o.getDurationDays(),
                o.getMonths(),
                o.getCreatedAt() != null ? o.getCreatedAt().toString() : "",
                isExam
        );
    }

    @Transactional
    public Map<String, Object> verifyOrder(String orderId) {
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order tapılmadı"));
        if ("PAID".equals(order.getStatus())) {
            return Map.of("success", true, "message", "Artıq işlənib", "alreadyPaid", true);
        }

        String paymentStatus = kapitalBankService.getOrderStatus(orderId);
        if (isKbPaidStatus(paymentStatus)) {
            activateOrder(order, orderId, "Admin manual verify");
            return Map.of("success", true, "paymentStatus", paymentStatus);
        }

        if (isKbFailedStatus(paymentStatus)) {
            order.setStatus("FAILED");
            paymentOrderRepository.save(order);
        }
        return Map.of("success", false, "paymentStatus", paymentStatus != null ? paymentStatus : "UNKNOWN");
    }

    @Transactional
    public Map<String, Object> forceActivate(String orderId, String adminEmail) {
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order tapılmadı"));
        activateOrder(order, orderId, "Admin force-activate. Admin: " + adminEmail);
        return Map.of("success", true, "message", "Abunəlik aktivləşdirildi");
    }

    @Transactional
    public Map<String, Object> cancelOrder(String orderId, String adminEmail) {
        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order tapılmadı"));
        if ("PAID".equals(order.getStatus())) {
            throw new BadRequestException("PAID order ləğv edilə bilməz");
        }
        order.setStatus("FAILED");
        paymentOrderRepository.save(order);
        String targetName = order.getExam() != null
                ? order.getExam().getTitle()
                : (order.getPlan() != null ? order.getPlan().getName() : "?");
        auditLogService.log(AuditAction.SUBSCRIPTION_CANCELLED, adminEmail, adminEmail,
                "ORDER", targetName,
                "Admin pending order ləğv etdi. İstifadəçi: " + order.getUser().getEmail());
        return Map.of("success", true);
    }

    private void activateOrder(PaymentOrder order, String orderId, String logNote) {
        order.setStatus("PAID");
        paymentOrderRepository.save(order);

        if (order.getExam() != null) {
            User student = order.getUser();
            examService.purchaseExam(order.getExam().getShareLink(), student);
            auditLogService.log(AuditAction.SUBSCRIPTION_PURCHASED,
                    "admin@system", "Admin", "EXAM", order.getExam().getTitle(),
                    logNote + ". İstifadəçi: " + student.getEmail()
                            + ". Məbləğ: " + order.getAmount() + " AZN");
        } else {
            double economicValue = pricingService.economicValue(
                    order.getPlan().getId(), order.getMonths(), order.getDurationDays());
            AssignSubscriptionRequest req = new AssignSubscriptionRequest();
            req.setUserId(order.getUser().getId());
            req.setPlanId(order.getPlan().getId());
            req.setDurationMonths(order.getMonths());
            req.setDurationDays(order.getDurationDays());
            req.setPaymentProvider("KAPITALBANK");
            req.setTransactionId(orderId);
            req.setAmountPaid(economicValue);
            userSubscriptionService.assignSubscription(req);
            auditLogService.log(AuditAction.SUBSCRIPTION_PURCHASED,
                    "admin@system", "Admin", "SUBSCRIPTION", order.getPlan().getName(),
                    logNote + ". İstifadəçi: " + order.getUser().getEmail()
                            + ". Məbləğ: " + order.getAmount() + " AZN. Müddət: "
                            + order.getDurationDays() + " gün");
        }
    }

    private boolean isKbPaidStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("FULLYPAID") || s.equals("PARTIALLYPAID")
                || s.equals("AUTHORIZED") || s.equals("FUNDED")
                || s.equals("PAID") || s.equals("APPROVED") || s.equals("SUCCESS")
                || s.equals("CONFIRMED") || s.equals("COMPLETE") || s.equals("COMPLETED");
    }

    private boolean isKbFailedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("DECLINED") || s.equals("FAILED") || s.equals("CANCELLED")
                || s.equals("REJECTED") || s.equals("REFUSED") || s.equals("EXPIRED")
                || s.equals("VOIDED") || s.equals("CLOSED");
    }

    private double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }
}
