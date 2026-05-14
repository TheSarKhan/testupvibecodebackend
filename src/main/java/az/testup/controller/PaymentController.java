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
import az.testup.util.InMemoryRateLimiter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    // 10 initiate calls per user per minute — generous for legitimate clicks,
    // tight enough to stop automated abuse (KB API spam, audit-log flooding).
    private final InMemoryRateLimiter initiateLimiter = new InMemoryRateLimiter(10, 60_000L);

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
    @Transactional
    public ResponseEntity<?> initiatePayment(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, Object> body) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        // Rate-limit by username to stop /initiate flooding (KB API abuse, audit spam).
        if (!initiateLimiter.tryAcquire(principal.getUsername())) {
            auditLogService.log(AuditAction.PAYMENT_RATE_LIMITED, principal.getUsername(), principal.getUsername(),
                    "PAYMENT", "initiate", "10/dəq limiti keçildi");
            return ResponseEntity.status(429).body(Map.of("message", "Çox tez-tez sorğu göndərilir. Bir az gözləyin."));
        }

        Long planId;
        int months;
        try {
            planId = Long.valueOf(body.get("planId").toString());
            months = body.containsKey("months") ? Integer.parseInt(body.get("months").toString()) : 1;
        } catch (NullPointerException | NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", "Yanlış planId və ya months"));
        }

        // CRITICAL: validate months bounds. Negative/zero would flip the sign of totalAmount,
        // dropping chargeAmount to 0 and triggering the free-switch branch — letting the
        // user obtain a subscription for free. Cap upper bound to defend against overflow
        // and absurd durations.
        if (months < 1 || months > 24) {
            return ResponseEntity.badRequest().body(Map.of("message", "Müddət 1-24 ay arası olmalıdır"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("İstifadəçi tapılmadı"));

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("Plan tapılmadı"));

        if (plan.getPrice() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu plan pulsuz olduğu üçün birbaşa aktivdir"));
        }

        // Reuse a fresh PENDING order for the same user+plan instead of creating a new
        // KB order on every click. Without this guard, a misbehaving frontend (or user
        // double-clicking "Pay") spawns many parallel KB orders for one intent —
        // cluttering payment_orders, stressing the KB API, and inflating the scheduler.
        java.util.List<PaymentOrder> recentPending = paymentOrderRepository.findRecentPendingForUserAndPlan(
                user.getId(), plan.getId(), LocalDateTime.now().minusMinutes(5));
        if (!recentPending.isEmpty()) {
            PaymentOrder existing = recentPending.get(0);
            return ResponseEntity.ok(Map.of(
                    "orderId", existing.getOrderId(),
                    "amount", existing.getAmount(),
                    "durationDays", existing.getDurationDays(),
                    "months", existing.getMonths(),
                    "reused", true,
                    "message", "5 dəq əvvəl başladılmış ödəniş hələ də gözləyir. Onu tamamlayın və ya bir az gözləyin."
            ));
        }

        // All money math uses BigDecimal (scale=2, HALF_UP) to avoid double-precision
        // drift that, accumulated across many transactions, can cost or overpay users
        // by fractions of a qəpik.
        BigDecimal planPrice = BigDecimal.valueOf(plan.getPrice()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmountBd = planPrice.multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_UP);

        // Credit: monetary value of remaining days on current plan.
        // PESSIMISTIC WRITE LOCK on the active subscription serializes concurrent
        // initiate calls for the same user (e.g. two tabs upgrading at once),
        // preventing the same prorated credit from being applied twice.
        BigDecimal creditAznBd = BigDecimal.ZERO;
        Optional<UserSubscription> currentOpt = userSubscriptionRepository
                .findActiveSubscriptionForUpdate(user.getId(), LocalDateTime.now());
        if (currentOpt.isPresent()) {
            UserSubscription current = currentOpt.get();
            boolean isSamePlan = current.getPlan().getId().equals(plan.getId());
            if (!isSamePlan && current.getAmountPaid() > 0) {
                long totalDays = ChronoUnit.DAYS.between(current.getStartDate(), current.getEndDate());
                long remainingDays = ChronoUnit.DAYS.between(LocalDateTime.now(), current.getEndDate());
                if (totalDays > 0 && remainingDays > 0) {
                    BigDecimal totalDaysBd = BigDecimal.valueOf(totalDays);
                    BigDecimal remainingDaysBd = BigDecimal.valueOf(remainingDays);
                    BigDecimal amountPaidBd = BigDecimal.valueOf(current.getAmountPaid());
                    BigDecimal oldDailyRate = amountPaidBd.divide(totalDaysBd, 6, RoundingMode.HALF_UP);
                    creditAznBd = oldDailyRate.multiply(remainingDaysBd).setScale(2, RoundingMode.HALF_UP);
                }
            }
        }

        // Value wallet: total economic value = credit + new charge
        BigDecimal chargeAmountBd = totalAmountBd.subtract(creditAznBd).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalValueBd = creditAznBd.add(chargeAmountBd).setScale(2, RoundingMode.HALF_UP);
        double chargeAmount = chargeAmountBd.doubleValue();
        double creditAzn = creditAznBd.doubleValue();
        double totalValue = totalValueBd.doubleValue();
        // Duration in days derived from total value at new plan's daily rate.
        // Floor of 1 day prevents the user from being charged but receiving 0 days
        // (e.g. when chargeAmount is tiny vs plan price, integer cast yields 0).
        BigDecimal dailyRate = planPrice.divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP);
        long durationDays = Math.max(1L,
                totalValueBd.divide(dailyRate, 0, RoundingMode.DOWN).longValueExact());

        // Free switch: credit covers entire cost
        if (chargeAmount == 0.0) {
            // Create a PaymentOrder row even though no money changes hands.
            // Without this, the credit-only switch leaves NO transactional record
            // in payment_orders, making receipts / dispute trails impossible.
            // Provider is CREDIT so revenue queries (which filter status='PAID' AND amount>0)
            // still correctly exclude these from revenue totals.
            String creditOrderId = "CREDIT-" + java.util.UUID.randomUUID();
            PaymentOrder creditOrder = PaymentOrder.builder()
                    .orderId(creditOrderId)
                    .user(user)
                    .plan(plan)
                    .months(months)
                    .durationDays(durationDays)
                    .amount(0.0)
                    .status("PAID")
                    .createdAt(LocalDateTime.now())
                    .build();
            paymentOrderRepository.save(creditOrder);

            AssignSubscriptionRequest req = new AssignSubscriptionRequest();
            req.setUserId(user.getId());
            req.setPlanId(plan.getId());
            req.setDurationMonths(0);
            req.setDurationDays(durationDays);
            req.setPaymentProvider("CREDIT");
            req.setTransactionId(creditOrderId);
            req.setAmountPaid(totalValue);
            userSubscriptionService.assignSubscription(req);
            auditLogService.log(AuditAction.SUBSCRIPTION_SWITCHED, user.getEmail(), user.getFullName(),
                    "SUBSCRIPTION", plan.getName(),
                    "Kredit ilə ödənişsiz keçid. Müddət: " + durationDays + " gün. Kredit: " + String.format("%.2f", creditAzn) + " AZN, OrderId: " + creditOrderId);
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
            long storedId;
            try {
                storedId = Long.parseLong(body.get("storedId").toString());
            } catch (NumberFormatException nfe) {
                storedId = -1;
            }
            // TODO: when a saved_cards table exists, replace this with
            //   savedCardRepository.existsByUserIdAndKbStoredId(user.getId(), storedId)
            // For now reject obviously invalid values and rely on KB's 3DS/auth on the
            // card itself to prevent using someone else's storedId.
            if (storedId <= 0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Yanlış kart identifikatoru"));
            }
            try {
                kapitalBankService.setSrcToken(result.orderId(), result.password(), storedId);
                auditLogService.log(AuditAction.PAYMENT_STORED_CARD_USED, user.getEmail(), user.getFullName(),
                        "PAYMENT", plan.getName(),
                        "Saved card ID: " + storedId + ", OrderId: " + result.orderId()
                                + ", Məbləğ: " + String.format("%.2f", chargeAmount) + " AZN");
            } catch (Exception e) {
                // Saved card attach failed — proceed with regular HPP flow.
                log.warn("setSrcToken failed for orderId={}, falling back to HPP: {}", result.orderId(), e.getMessage());
            }
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
        try {
            paymentOrderRepository.save(order);
        } catch (Exception saveEx) {
            // CRITICAL: KB has created an order on their side but we couldn't persist
            // the matching PaymentOrder row. Without this log, a future paid callback
            // would arrive with no DB context, the money would be taken, and we'd have
            // no audit trail. ERROR-level slf4j is independent of the DB so a file/log
            // aggregator can recover this. Manual reconciliation is required.
            log.error("ORPHAN KB ORDER: KB orderId={} created but PaymentOrder save failed user={} amount={} err={}",
                    result.orderId(), user.getEmail(), chargeAmount, saveEx.getMessage(), saveEx);
            try {
                auditLogService.log(AuditAction.ORDER_ORPHANED, user.getEmail(), user.getFullName(),
                        "PAYMENT", plan.getName(),
                        "KB orderId: " + result.orderId() + ", Məbləğ: " + String.format("%.2f", chargeAmount)
                                + " AZN, DB xətası: " + saveEx.getMessage());
            } catch (Exception ignored) { /* audit also unreachable — slf4j is our only trail */ }
            throw saveEx;
        }

        auditLogService.log(AuditAction.PAYMENT_INITIATED, user.getEmail(), user.getFullName(),
                "PAYMENT", plan.getName(),
                "Məbləğ: " + String.format("%.2f", chargeAmount) + " AZN, Müddət: " + durationDays + " gün, OrderId: " + result.orderId());

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

        String orderId;
        try {
            orderId = body.get("orderId").toString();
        } catch (NullPointerException npe) {
            return ResponseEntity.badRequest().body(Map.of("message", "orderId tələb olunur"));
        }

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(Map.of("status", "NOT_FOUND", "message", "Order not found"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("İstifadəçi tapılmadı"));

        if (!order.getUser().getId().equals(user.getId())) {
            auditLogService.log(AuditAction.PAYMENT_UNAUTHORIZED_ACCESS, user.getEmail(), user.getFullName(),
                    "PAYMENT", "OrderId: " + orderId,
                    "Order sahibi: " + order.getUser().getEmail() + " (başqasının order-ını yoxlamağa cəhd)");
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
            // Another thread won the claim — re-read the DB to return a fresh status
            // rather than the stale value we loaded earlier.
            String freshStatus = paymentOrderRepository.findByOrderId(orderId)
                    .map(PaymentOrder::getStatus)
                    .orElse("UNKNOWN");
            return ResponseEntity.ok(Map.of("status", freshStatus));
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
            String target = order.getExam() != null ? "İmtahan: " + order.getExam().getTitle()
                    : (order.getPlan() != null ? order.getPlan().getName() : "?");
            auditLogService.log(AuditAction.PAYMENT_FAILED, order.getUser().getEmail(), order.getUser().getFullName(),
                    "PAYMENT", target,
                    "Status: " + paymentStatus + ", OrderId: " + orderId
                            + ", Məbləğ: " + String.format("%.2f", order.getAmount()) + " AZN");
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

            boolean callbackSaysPaid = isPaidStatus(callbackStatus);
            boolean callbackSaysFailed = isFailedStatus(callbackStatus);
            log.info("KB callback: callbackStatus={} callbackSaysPaid={} callbackSaysFailed={}",
                    callbackStatus, callbackSaysPaid, callbackSaysFailed);

            // SECURITY: NEVER trust callback's STATUS parameter as authoritative.
            // ALWAYS verify with Kapital Bank API server-to-server before activating an order,
            // because the callback endpoint is unauthenticated and anyone can craft
            //   GET /api/payment/callback?ID=<order>&STATUS=FullyPaid
            // We use the callback only as a trigger; the API call is the source of truth.
            boolean isPaid = false;
            boolean isFailed = false;

            int maxRetries = callbackSaysPaid ? 3 : (callbackSaysFailed ? 1 : 5);
            int retryDelayMs = 1500;
            for (int i = 1; i <= maxRetries; i++) {
                if (i > 1) {
                    try { Thread.sleep(retryDelayMs); } catch (InterruptedException ignored) {}
                }
                String apiStatus = kapitalBankService.getOrderStatus(orderId);
                log.info("KB callback: API verify {}/{} apiStatus={}", i, maxRetries, apiStatus);
                if (isPaidStatus(apiStatus)) { isPaid = true; break; }
                if (isFailedStatus(apiStatus)) { isFailed = true; break; }
            }

            if (callbackSaysPaid && !isPaid) {
                // Callback claimed paid but API disagrees — possible forgery attempt OR KB delay
                log.warn("KB callback: callback claimed paid but API did not confirm for orderId={}", orderId);
                auditLogService.log(AuditAction.PAYMENT_UNAUTHORIZED_ACCESS,
                        "system@callback", "Callback",
                        "PAYMENT", "OrderId: " + orderId,
                        "Callback STATUS=Paid lakin KB API təsdiqləmədi (saxta cəhd ola bilər)");
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
                String target = order.getExam() != null ? "İmtahan: " + order.getExam().getTitle()
                        : (order.getPlan() != null ? order.getPlan().getName() : "?");
                auditLogService.log(AuditAction.PAYMENT_FAILED, order.getUser().getEmail(), order.getUser().getFullName(),
                        "PAYMENT", target,
                        "Callback status: " + callbackStatus + ", OrderId: " + orderId);
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

        if (!initiateLimiter.tryAcquire(principal.getUsername())) {
            auditLogService.log(AuditAction.PAYMENT_RATE_LIMITED, principal.getUsername(), principal.getUsername(),
                    "PAYMENT", "initiate-exam", "10/dəq limiti keçildi");
            return ResponseEntity.status(429).body(Map.of("message", "Çox tez-tez sorğu göndərilir. Bir az gözləyin."));
        }

        String shareLink;
        try {
            shareLink = body.get("shareLink").toString();
        } catch (NullPointerException npe) {
            return ResponseEntity.badRequest().body(Map.of("message", "shareLink tələb olunur"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("İstifadəçi tapılmadı"));

        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("İmtahan tapılmadı"));

        if (exam.getPrice() == null || exam.getPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu imtahan pulsuzdur"));
        }

        // Has unused purchase — no need to pay again
        if (examService.hasUnusedPurchase(exam, user)) {
            return ResponseEntity.ok(Map.of("alreadyPurchased", true, "shareLink", shareLink));
        }

        // Reuse a fresh PENDING order for the same user+exam, same reasoning as initiate().
        java.util.List<PaymentOrder> recentPendingExam = paymentOrderRepository.findRecentPendingForUserAndExam(
                user.getId(), exam.getId(), LocalDateTime.now().minusMinutes(5));
        if (!recentPendingExam.isEmpty()) {
            PaymentOrder existing = recentPendingExam.get(0);
            return ResponseEntity.ok(Map.of(
                    "orderId", existing.getOrderId(),
                    "amount", existing.getAmount(),
                    "reused", true,
                    "message", "5 dəq əvvəl başladılmış ödəniş hələ də gözləyir."
            ));
        }

        double amount = exam.getPrice().doubleValue();
        String description = exam.getTitle() + " — imtahan";
        KapitalBankService.CreateInvoiceResult result = kapitalBankService.createOrder(amount, description);

        if (body.containsKey("storedId") && body.get("storedId") != null) {
            long storedId;
            try {
                storedId = Long.parseLong(body.get("storedId").toString());
            } catch (NumberFormatException nfe) {
                storedId = -1;
            }
            if (storedId <= 0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Yanlış kart identifikatoru"));
            }
            try {
                kapitalBankService.setSrcToken(result.orderId(), result.password(), storedId);
                auditLogService.log(AuditAction.PAYMENT_STORED_CARD_USED, user.getEmail(), user.getFullName(),
                        "PAYMENT", "İmtahan: " + exam.getTitle(),
                        "Saved card ID: " + storedId + ", OrderId: " + result.orderId()
                                + ", Məbləğ: " + String.format("%.2f", amount) + " AZN");
            } catch (Exception e) {
                log.warn("setSrcToken failed for orderId={}, falling back to HPP: {}", result.orderId(), e.getMessage());
            }
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
        try {
            paymentOrderRepository.save(order);
        } catch (Exception saveEx) {
            log.error("ORPHAN KB ORDER (exam): KB orderId={} created but PaymentOrder save failed user={} amount={} err={}",
                    result.orderId(), user.getEmail(), amount, saveEx.getMessage(), saveEx);
            try {
                auditLogService.log(AuditAction.ORDER_ORPHANED, user.getEmail(), user.getFullName(),
                        "PAYMENT", "İmtahan: " + exam.getTitle(),
                        "KB orderId: " + result.orderId() + ", Məbləğ: " + String.format("%.2f", amount)
                                + " AZN, DB xətası: " + saveEx.getMessage());
            } catch (Exception ignored) { /* audit also unreachable — slf4j is our only trail */ }
            throw saveEx;
        }

        auditLogService.log(AuditAction.PAYMENT_INITIATED, user.getEmail(), user.getFullName(),
                "PAYMENT", "İmtahan: " + exam.getTitle(),
                "Məbləğ: " + String.format("%.2f", amount) + " AZN, OrderId: " + result.orderId());

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
        // PARTIALLYPAID intentionally excluded: in most payment systems it means the
        // user paid less than the requested amount. Granting full access for a partial
        // capture is a revenue leak. If KB's PartiallyPaid actually means "first
        // installment received" for this merchant configuration, re-add it once
        // confirmed in writing with KB.
        return s.equals("FULLYPAID")
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

    /**
     * Returns true if the KB status indicates the payment was reversed AFTER
     * successful capture (refund, chargeback, etc.). Treated as a distinct
     * lifecycle event from "failed" because the user once had access and that
     * access typically must be revoked.
     */
    private boolean isReversedStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.toUpperCase().replace(" ", "").replace("_", "");
        return s.equals("REVERSED") || s.equals("REFUNDED")
                || s.equals("CHARGEBACK") || s.equals("CHARGEDBACK")
                || s.equals("RETURNED");
    }

    private void activateOrder(PaymentOrder order, String orderId, User verifyingUser) {
        // IDEMPOTENCY GUARD: Atomically transition from PENDING/PROCESSING to PAID.
        // If the row was already PAID, the update returns 0 and we skip activation.
        // This prevents double-activation when callback + scheduler + admin race,
        // which would otherwise extend the subscription twice for one payment.
        int activated = paymentOrderRepository.markAsPaidIfNotAlready(orderId);
        if (activated == 0) {
            log.warn("activateOrder: order {} already PAID — skipping duplicate activation", orderId);
            return;
        }
        // Refresh in-memory entity to reflect the DB transition
        order.setStatus("PAID");

        User orderUser = order.getUser();

        if (order.getExam() != null) {
            // Use the amount actually charged on this order, not the current exam price —
            // admin may have changed the price between initiation and activation.
            examService.purchaseExam(order.getExam().getShareLink(), orderUser,
                    java.math.BigDecimal.valueOf(order.getAmount()));
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
            // BigDecimal: economicValue = durationDays * (planPrice / 30)
            BigDecimal economicValueBd = BigDecimal.valueOf(order.getPlan().getPrice())
                    .divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(order.getDurationDays()))
                    .setScale(2, RoundingMode.HALF_UP);
            request.setAmountPaid(economicValueBd.doubleValue());

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
