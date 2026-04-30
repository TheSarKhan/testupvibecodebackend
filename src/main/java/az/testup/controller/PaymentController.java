package az.testup.controller;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.Exam;
import az.testup.entity.PaymentOrder;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import az.testup.repository.ExamPurchaseRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.SubscriptionPlanRepository;
import az.testup.repository.UserRepository;
import az.testup.repository.UserSubscriptionRepository;
import az.testup.enums.AuditAction;
import az.testup.service.AuditLogService;
import az.testup.service.ExamService;
import az.testup.service.KapitalBankService;
import az.testup.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Value("${app.base-url}")
    private String appBaseUrl;

    private final KapitalBankService kapitalBankService;
    private final PaymentOrderRepository paymentOrderRepository;
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
        KapitalBankService.CreateInvoiceResult result = kapitalBankService.createOrder(chargeAmount, description);

        if (body.containsKey("storedId") && body.get("storedId") != null) {
            long storedId = Long.parseLong(body.get("storedId").toString());
            kapitalBankService.setSrcToken(result.orderId(), result.password(), storedId);
        }

        PaymentOrder order = PaymentOrder.builder()
                .orderId(result.orderId())
                .user(user)
                .plan(plan)
                .months(months)
                .durationDays(durationDays)
                .amount(chargeAmount)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        paymentOrderRepository.save(order);

        return ResponseEntity.ok(Map.of(
                "orderId", result.orderId(),
                "paymentUrl", result.paymentUrl(),
                "amount", chargeAmount,
                "durationDays", durationDays,
                "months", months
        ));
    }

    @PostMapping("/verify")
    @Transactional
    public ResponseEntity<?> verifyPayment(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, Object> body) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        String orderId = body.get("orderId").toString();

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(Map.of("status", "NOT_FOUND", "message", "Order not found"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!order.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "Bu əməliyyat üçün icazəniz yoxdur"));
        }

        // Already fully processed — return early without hitting Kapital Bank API
        if ("PAID".equals(order.getStatus())) {
            if (order.getExam() != null) {
                return ResponseEntity.ok(Map.of("status", "PAID", "alreadyProcessed", true, "examShareLink", order.getExam().getShareLink()));
            }
            return ResponseEntity.ok(Map.of("status", "PAID", "alreadyProcessed", true));
        }

        // Atomically claim the order for processing (PENDING → PROCESSING).
        int claimed = paymentOrderRepository.claimForProcessing(orderId);
        if (claimed == 0) {
            // Either already PROCESSING (concurrent request) or FAILED
            return ResponseEntity.ok(Map.of("status", order.getStatus()));
        }

        String paymentStatus = kapitalBankService.getOrderStatus(orderId);
        boolean isPaid = isPaidStatus(paymentStatus);

        if (isPaid) {
            activateOrder(order, orderId, user);
            if (order.getExam() != null) {
                return ResponseEntity.ok(Map.of("status", "PAID", "examShareLink", order.getExam().getShareLink()));
            }
            return ResponseEntity.ok(Map.of("status", "PAID", "message", "Abunəlik aktivləşdirildi"));
        }

        if (isFailedStatus(paymentStatus)) {
            order.setStatus("FAILED");
        } else {
            order.setStatus("PENDING");
        }
        paymentOrderRepository.save(order);

        return ResponseEntity.ok(Map.of("status", paymentStatus));
    }

    /**
     * Kapital Bank browser redirect callback.
     * KB redirects user's browser here after payment with ?ID={orderId}&STATUS={status}.
     */
    @GetMapping("/callback")
    @Transactional
    public ResponseEntity<?> kapitalBankCallback(
            @RequestParam(name = "ID", required = false) String orderId,
            @RequestParam(name = "STATUS", required = false) String callbackStatus) {
        try {
            log.info("KB callback received: ID={}, STATUS={}", orderId, callbackStatus);

            if (orderId == null || orderId.isBlank()) {
                log.warn("KB callback: orderId is blank");
                return ResponseEntity.status(302).header("Location", appBaseUrl + "/odenis/red").build();
            }

            PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElse(null);
            if (order == null) {
                log.warn("KB callback: order not found in DB for orderId={}", orderId);
                return ResponseEntity.status(302).header("Location", appBaseUrl + "/odenis/red").build();
            }

            log.info("KB callback: order found, current DB status={}", order.getStatus());

            if ("PAID".equals(order.getStatus())) {
                log.info("KB callback: order already PAID, redirecting to ugurlu");
                String successUrl = order.getExam() != null
                        ? appBaseUrl + "/odenis/ugurlu?shareLink=" + order.getExam().getShareLink()
                        : appBaseUrl + "/odenis/ugurlu";
                return ResponseEntity.status(302).header("Location", successUrl).build();
            }

            int claimed = paymentOrderRepository.claimForProcessing(orderId);
            log.info("KB callback: claimForProcessing result={}", claimed);
            if (claimed == 0) {
                log.info("KB callback: order already being processed, redirecting to ugurlu");
                return ResponseEntity.status(302).header("Location", appBaseUrl + "/odenis/ugurlu").build();
            }

            boolean isPaid = isPaidStatus(callbackStatus);
            boolean isFailed = isFailedStatus(callbackStatus);
            log.info("KB callback: callbackStatus={} isPaid={} isFailed={}", callbackStatus, isPaid, isFailed);

            // Callback status qeyri-müəyyəndirsə (Preparing/Processing) — retry ilə API-ni sorğula.
            // KB brauzer redirect-i transaction tamamlanmadan edə bilər.
            if (!isPaid && !isFailed) {
                int maxRetries = 5;
                int retryDelayMs = 1500;
                for (int i = 1; i <= maxRetries; i++) {
                    try { Thread.sleep(retryDelayMs); } catch (InterruptedException ignored) {}
                    String apiStatus = kapitalBankService.getOrderStatus(orderId);
                    log.info("KB callback: retry {}/{} apiStatus={}", i, maxRetries, apiStatus);
                    if (isPaidStatus(apiStatus)) { isPaid = true; break; }
                    if (isFailedStatus(apiStatus)) { isFailed = true; break; }
                }
            } else if (!isPaid) {
                // Callback-da failed gəlsə bir dəfə API-ni yoxla (status müvəqqəti ola bilər)
                String apiStatus = kapitalBankService.getOrderStatus(orderId);
                log.info("KB callback: API confirm for failed callbackStatus, apiStatus={}", apiStatus);
                if (isPaidStatus(apiStatus)) isPaid = true;
                else if (isFailedStatus(apiStatus)) isFailed = true;
            }

            if (isPaid) {
                log.info("KB callback: payment confirmed, activating order={}", orderId);
                activateOrder(order, orderId, null);
                String successUrl = order.getExam() != null
                        ? appBaseUrl + "/odenis/ugurlu?shareLink=" + order.getExam().getShareLink()
                        : appBaseUrl + "/odenis/ugurlu";
                return ResponseEntity.status(302).header("Location", successUrl).build();
            } else if (isFailed) {
                log.warn("KB callback: payment FAILED for order={}", orderId);
                order.setStatus("FAILED");
                paymentOrderRepository.save(order);
                return ResponseEntity.status(302).header("Location", appBaseUrl + "/odenis/red").build();
            } else {
                log.warn("KB callback: status still unconfirmed after retries, keeping PENDING order={}", orderId);
                order.setStatus("PENDING");
                paymentOrderRepository.save(order);
                return ResponseEntity.status(302).header("Location", appBaseUrl + "/odenis/ugurlu").build();
            }
        } catch (Exception e) {
            log.error("KB callback: exception for orderId={}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(302).header("Location", appBaseUrl + "/odenis/red").build();
        }
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

        // Has unused purchase — no need to pay again
        if (examService.hasUnusedPurchase(exam, user)) {
            return ResponseEntity.ok(Map.of("alreadyPurchased", true, "shareLink", shareLink));
        }

        double amount = exam.getPrice().doubleValue();
        String description = exam.getTitle() + " — imtahan";
        KapitalBankService.CreateInvoiceResult result = kapitalBankService.createOrder(amount, description);

        if (body.containsKey("storedId") && body.get("storedId") != null) {
            long storedId = Long.parseLong(body.get("storedId").toString());
            kapitalBankService.setSrcToken(result.orderId(), result.password(), storedId);
        }

        PaymentOrder order = PaymentOrder.builder()
                .orderId(result.orderId())
                .user(user)
                .exam(exam)
                .months(0)
                .durationDays(0)
                .amount(amount)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        paymentOrderRepository.save(order);

        return ResponseEntity.ok(Map.of(
                "orderId", result.orderId(),
                "paymentUrl", result.paymentUrl(),
                "amount", amount
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isPaidStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("FULLYPAID") || s.equals("PARTIALLYPAID")
                || s.equals("AUTHORIZED") || s.equals("FUNDED")
                || s.equals("PAID") || s.equals("APPROVED") || s.equals("SUCCESS")
                || s.equals("CONFIRMED") || s.equals("COMPLETE") || s.equals("COMPLETED");
    }

    private boolean isFailedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("DECLINED") || s.equals("FAILED") || s.equals("CANCELLED")
                || s.equals("REJECTED") || s.equals("REFUSED") || s.equals("EXPIRED")
                || s.equals("VOIDED") || s.equals("CLOSED");
    }

    private void activateOrder(PaymentOrder order, String orderId, User verifyingUser) {
        order.setStatus("PAID");
        paymentOrderRepository.save(order);

        User orderUser = order.getUser();

        if (order.getExam() != null) {
            examService.purchaseExam(order.getExam().getShareLink(), orderUser);
            return;
        }

        if (order.getPlan() != null) {
            AssignSubscriptionRequest request = new AssignSubscriptionRequest();
            request.setUserId(orderUser.getId());
            request.setPlanId(order.getPlan().getId());
            request.setDurationMonths(order.getMonths());
            request.setDurationDays(order.getDurationDays());
            request.setPaymentProvider("KAPITALBANK");
            request.setTransactionId(orderId);
            double economicValue = order.getDurationDays() * (order.getPlan().getPrice() / 30.0);
            request.setAmountPaid(economicValue);

            boolean isSwitching = userSubscriptionRepository
                    .findActiveSubscriptionByUserIdAndDate(orderUser.getId(), LocalDateTime.now())
                    .map(s -> !s.getPlan().getId().equals(order.getPlan().getId()))
                    .orElse(false);
            userSubscriptionService.assignSubscription(request);

            AuditAction action = isSwitching ? AuditAction.SUBSCRIPTION_SWITCHED : AuditAction.SUBSCRIPTION_PURCHASED;
            auditLogService.log(action, orderUser.getEmail(), orderUser.getFullName(),
                    "SUBSCRIPTION", order.getPlan().getName(),
                    "Ödəniş: " + String.format("%.2f", order.getAmount()) + " AZN. Müddət: "
                            + order.getDurationDays() + " gün. OrderId: " + orderId);
        }
    }
}
