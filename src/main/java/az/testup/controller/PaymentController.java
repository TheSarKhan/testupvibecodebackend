package az.testup.controller;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.Exam;
import az.testup.entity.PayriffOrder;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import az.testup.repository.ExamPurchaseRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.PayriffOrderRepository;
import az.testup.repository.SubscriptionPlanRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import az.testup.enums.AuditAction;
import az.testup.service.AuditLogService;
import az.testup.service.ExamService;
import az.testup.service.PayriffService;
import az.testup.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PayriffService payriffService;
    private final PayriffOrderRepository payriffOrderRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final UserSubscriptionService userSubscriptionService;
    private final AuditLogService auditLogService;
    private final ExamRepository examRepository;
    private final ExamPurchaseRepository examPurchaseRepository;
    private final ExamService examService;

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePayment(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, Object> body) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        Long planId = Long.valueOf(body.get("planId").toString());
        int months = body.containsKey("months") ? Integer.parseInt(body.get("months").toString()) : 1;

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        if (plan.getPrice() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu plan pulsuz olduğu üçün birbaşa aktivdir"));
        }

        double totalAmount = plan.getPrice() * months;

        // Credit: monetary value of remaining days on current plan
        double creditAzn = 0.0;
        Optional<UserSubscription> currentOpt = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(user.getId(), LocalDateTime.now());
        if (currentOpt.isPresent()) {
            UserSubscription current = currentOpt.get();
            boolean isSamePlan = current.getPlan().getId().equals(plan.getId());
            if (!isSamePlan && current.getAmountPaid() > 0) {
                long totalDays = ChronoUnit.DAYS.between(current.getStartDate(), current.getEndDate());
                long remainingDays = ChronoUnit.DAYS.between(LocalDateTime.now(), current.getEndDate());
                if (totalDays > 0 && remainingDays > 0) {
                    double oldDailyRate = current.getAmountPaid() / totalDays;
                    creditAzn = oldDailyRate * remainingDays;
                }
            }
        }

        // Value wallet: total economic value = credit + new charge
        double chargeAmount = Math.max(0.0, totalAmount - creditAzn);
        double totalValue = creditAzn + chargeAmount;
        // Duration in days derived from total value at new plan's daily rate
        long durationDays = (long) (totalValue / (plan.getPrice() / 30.0));

        // Free switch: credit covers entire cost
        if (chargeAmount == 0.0) {
            AssignSubscriptionRequest req = new AssignSubscriptionRequest();
            req.setUserId(user.getId());
            req.setPlanId(plan.getId());
            req.setDurationMonths(0);
            req.setDurationDays(durationDays);
            req.setPaymentProvider("CREDIT");
            req.setAmountPaid(totalValue);
            userSubscriptionService.assignSubscription(req);
            auditLogService.log(AuditAction.SUBSCRIPTION_SWITCHED, user.getEmail(), user.getFullName(),
                    "SUBSCRIPTION", plan.getName(),
                    "Kredit ilə ödənişsiz keçid. Müddət: " + durationDays + " gün. Kredit: " + String.format("%.2f", creditAzn) + " AZN");
            return ResponseEntity.ok(Map.of(
                    "directActivated", true,
                    "durationDays", durationDays,
                    "months", months
            ));
        }

        // Paid: charge only the difference, duration from total value
        String description = plan.getName() + " — " + months + " ay";
        PayriffService.CreateInvoiceResult result = payriffService.createOrder(chargeAmount, description);

        PayriffOrder order = PayriffOrder.builder()
                .orderId(result.orderId())
                .user(user)
                .plan(plan)
                .months(months)
                .durationDays(durationDays)
                .amount(chargeAmount)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        payriffOrderRepository.save(order);

        return ResponseEntity.ok(Map.of(
                "orderId", result.orderId(),
                "paymentUrl", result.paymentUrl(),
                "amount", chargeAmount,
                "durationDays", durationDays,
                "months", months
        ));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyPayment(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, Object> body) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        String orderId = body.get("orderId").toString();

        PayriffOrder order = payriffOrderRepository.findByOrderId(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(Map.of("status", "NOT_FOUND", "message", "Order not found"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!order.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "Bu əməliyyat üçün icazəniz yoxdur"));
        }

        if ("PAID".equals(order.getStatus())) {
            if (order.getExam() != null) {
                return ResponseEntity.ok(Map.of("status", "PAID", "alreadyProcessed", true, "examShareLink", order.getExam().getShareLink()));
            }
            return ResponseEntity.ok(Map.of("status", "PAID", "alreadyProcessed", true));
        }

        String payriffStatus = payriffService.getOrderStatus(orderId);

        // V3 statuses: PAID / APPROVED / SUCCESS → activate; DECLINED / FAILED / CANCELLED → fail
        boolean isPaid = "PAID".equals(payriffStatus)
                || "APPROVED".equals(payriffStatus)
                || "SUCCESS".equals(payriffStatus);

        if (isPaid) {
            order.setStatus("PAID");
            payriffOrderRepository.save(order);

            // Exam purchase
            if (order.getExam() != null) {
                examService.purchaseExam(order.getExam().getShareLink(), user);
                return ResponseEntity.ok(Map.of("status", "PAID", "examShareLink", order.getExam().getShareLink()));
            }

            // Subscription purchase
            AssignSubscriptionRequest request = new AssignSubscriptionRequest();
            request.setUserId(user.getId());
            request.setPlanId(order.getPlan().getId());
            request.setDurationMonths(order.getMonths());
            request.setDurationDays(order.getDurationDays());
            request.setPaymentProvider("PAYRIFF");
            request.setTransactionId(orderId);
            double economicValue = order.getDurationDays() * (order.getPlan().getPrice() / 30.0);
            request.setAmountPaid(economicValue);
            boolean isSwitching = userSubscriptionRepository
                    .findActiveSubscriptionByUserIdAndDate(user.getId(), java.time.LocalDateTime.now())
                    .map(s -> !s.getPlan().getId().equals(order.getPlan().getId()))
                    .orElse(false);
            userSubscriptionService.assignSubscription(request);
            AuditAction action = isSwitching ? AuditAction.SUBSCRIPTION_SWITCHED : AuditAction.SUBSCRIPTION_PURCHASED;
            auditLogService.log(action, user.getEmail(), user.getFullName(),
                    "SUBSCRIPTION", order.getPlan().getName(),
                    "Ödəniş: " + String.format("%.2f", order.getAmount()) + " AZN. Müddət: " + order.getDurationDays() + " gün. OrderId: " + orderId);

            return ResponseEntity.ok(Map.of("status", "PAID", "message", "Abunəlik aktivləşdirildi"));
        }

        if ("DECLINED".equals(payriffStatus) || "FAILED".equals(payriffStatus) || "CANCELLED".equals(payriffStatus)) {
            order.setStatus("FAILED");
            payriffOrderRepository.save(order);
        }

        return ResponseEntity.ok(Map.of("status", payriffStatus));
    }

    @PostMapping("/initiate-exam")
    public ResponseEntity<?> initiateExamPayment(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, Object> body) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        String shareLink = body.get("shareLink").toString();

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new RuntimeException("Exam not found"));

        if (exam.getPrice() == null || exam.getPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu imtahan pulsuzdur"));
        }

        // Already purchased
        if (examPurchaseRepository.existsByUserIdAndExamId(user.getId(), exam.getId())) {
            return ResponseEntity.ok(Map.of("alreadyPurchased", true, "shareLink", shareLink));
        }

        double amount = exam.getPrice().doubleValue();
        String description = exam.getTitle() + " — imtahan";
        PayriffService.CreateInvoiceResult result = payriffService.createOrder(amount, description);

        PayriffOrder order = PayriffOrder.builder()
                .orderId(result.orderId())
                .user(user)
                .exam(exam)
                .months(0)
                .durationDays(0)
                .amount(amount)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        payriffOrderRepository.save(order);

        return ResponseEntity.ok(Map.of(
                "orderId", result.orderId(),
                "paymentUrl", result.paymentUrl(),
                "amount", amount
        ));
    }
}
