package az.testup.controller;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.Exam;
import az.testup.entity.PaymentOrder;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.User;
import az.testup.entity.UserSubscription;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.exception.UnauthorizedException;
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

        // Wrap the whole flow in a try/catch so any backend hiccup (KapitalBank
        // unreachable, audit log constraint, …) surfaces a meaningful 4xx
        // message instead of bubbling up to the generic 500 "Daxili server
        // xətası baş verdi" toast. The detailed error still lands in the
        // server log via the global handler if we re-throw a specific type.
        try {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        if (body == null || body.get("planId") == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Plan seçimi tələb olunur"));
        }
        Long planId;
        try {
            planId = Long.valueOf(body.get("planId").toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Plan id-si yanlışdır"));
        }
        int months = body.containsKey("months") && body.get("months") != null
                ? Integer.parseInt(body.get("months").toString())
                : 1;
        if (months <= 0 || months > 24) months = 1;

        User user = userRepository.findByEmail(principal.getUsername())
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Hesab tapılmadı, yenidən daxil olun"));
        }

        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElse(null);
        if (plan == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Plan tapılmadı"));
        }

        // Defensive null guards: SubscriptionPlan.price is a boxed Double in
        // the entity and older rows can be null. Without these guards any
        // arithmetic below NPEs and the controller returns a 500
        // ("Daxili server xətası") to the user.
        double newPrice = plan.getPrice() != null ? plan.getPrice() : 0.0;
        if (newPrice <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu plan pulsuz olduğu üçün birbaşa aktivdir"));
        }

        double totalAmount = newPrice * months;

        // ── Prorate: monetary value of remaining time on current plan ──
        // We compute the credit in AZN of the unused portion of the active
        // subscription (only for plan SWITCH, not same-plan renewal).
        //
        // Daily rate falls back to the current plan's listed price/30 when
        // amountPaid is 0 (e.g. manually-assigned subs). Remaining time uses
        // fractional days (hours/24.0) so the user never loses a partial day.
        double creditAzn = 0.0;
        LocalDateTime now = LocalDateTime.now();
        Optional<UserSubscription> currentOpt = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(user.getId(), now);
        if (currentOpt.isPresent()) {
            UserSubscription current = currentOpt.get();
            // Same defensive pattern — any of these references can be null on
            // legacy/manually-assigned subscription rows; bail out of the
            // credit calculation rather than 500-ing.
            SubscriptionPlan currentPlan = current.getPlan();
            double currentPlanPrice = (currentPlan != null && currentPlan.getPrice() != null)
                    ? currentPlan.getPrice() : 0.0;
            boolean isSamePlan = currentPlan != null && currentPlan.getId() != null
                    && currentPlan.getId().equals(plan.getId());
            if (!isSamePlan
                    && current.getStartDate() != null
                    && current.getEndDate() != null) {
                long totalSeconds = ChronoUnit.SECONDS.between(current.getStartDate(), current.getEndDate());
                long remainingSeconds = ChronoUnit.SECONDS.between(now, current.getEndDate());
                if (totalSeconds > 0 && remainingSeconds > 0) {
                    double totalDaysExact = totalSeconds / 86400.0;
                    double remainingDaysExact = remainingSeconds / 86400.0;
                    double oldDailyRate = current.getAmountPaid() > 0
                            ? current.getAmountPaid() / totalDaysExact
                            : currentPlanPrice / 30.0;
                    creditAzn = oldDailyRate * remainingDaysExact;
                    // Cap credit at the listed value of the remaining time on the
                    // current plan — defends against over-credit if amountPaid was
                    // inflated by previous credits (compounding).
                    double remainingListedValue = (currentPlanPrice / 30.0) * remainingDaysExact;
                    if (creditAzn > remainingListedValue) {
                        creditAzn = remainingListedValue;
                    }
                }
            }
        }
        // Round credit to 2 decimal places (AZN cents) — avoids long fractions
        // propagating into the duration calculation.
        creditAzn = Math.round(creditAzn * 100.0) / 100.0;

        // Value wallet: total economic value = credit + new charge
        double chargeAmount = Math.max(0.0, totalAmount - creditAzn);
        chargeAmount = Math.round(chargeAmount * 100.0) / 100.0;
        double totalValue = creditAzn + chargeAmount;
        // Duration in days derived from total value at new plan's daily rate.
        // Use Math.round (not cast) so users don't lose a day from fractional truncation.
        double newDailyRate = plan.getPrice() / 30.0;
        long durationDays = Math.max(1L, Math.round(totalValue / newDailyRate));

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
        } catch (BadRequestException | ResourceNotFoundException | UnauthorizedException e) {
            // Re-throw domain exceptions so the global handler turns them
            // into a proper 4xx with the original message.
            throw e;
        } catch (Exception e) {
            // KapitalBank, audit log, DB hiccup — surface the cause to the
            // user as a 400 rather than letting it become "Daxili server
            // xətası baş verdi" with no actionable info.
            log.error("Payment initiate failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Ödəniş başladıla bilmədi, yenidən cəhd edin";
            return ResponseEntity.badRequest().body(Map.of("message", msg));
        }
    }

    @PostMapping("/verify")
    @Transactional
    public ResponseEntity<?> verifyPayment(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, Object> body) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        // The frontend occasionally retries /verify after the popup closes
        // without rebuilding the payload, leaving `orderId` absent. Surface
        // that as a 400 instead of letting it NPE into a generic 500.
        Object rawOrderId = body == null ? null : body.get("orderId");
        if (rawOrderId == null || rawOrderId.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "orderId tələb olunur"));
        }
        String orderId = rawOrderId.toString();

        PaymentOrder order = paymentOrderRepository.findByOrderId(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(Map.of("status", "NOT_FOUND", "message", "Order not found"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!order.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "Bu əməliyyat üçün icazəniz yoxdur"));
        }

        // Fast paths for terminal states — don't burn a KB API call.
        if ("PAID".equals(order.getStatus())) {
            if (order.getExam() != null) {
                return ResponseEntity.ok(Map.of("status", "PAID", "alreadyProcessed", true, "examShareLink", order.getExam().getShareLink()));
            }
            return ResponseEntity.ok(Map.of("status", "PAID", "alreadyProcessed", true));
        }
        if ("FAILED".equals(order.getStatus())) {
            return ResponseEntity.ok(Map.of("status", "FAILED"));
        }

        // PENDING or PROCESSING: ALWAYS ask Kapital Bank for the truth.
        //
        // Previously we tried to claim PENDING → PROCESSING and returned the
        // local status when the claim failed (already PROCESSING). That broke
        // the common case where the user pays in the KB tab, the browser
        // redirect callback transitions the order to PROCESSING, and then a
        // hiccup (network, transaction rollback, KB getOrderStatus timeout
        // mid-flight) leaves it stuck in PROCESSING forever. The frontend
        // polls /verify, sees PROCESSING again and again until MAX_WAIT
        // expires, and the user gives up.
        //
        // Now we always re-query KB and use markAsPaid as the activation
        // race guard, so verify can recover from a stuck PROCESSING by
        // itself.
        String paymentStatus = kapitalBankService.getOrderStatus(orderId);
        boolean isPaid = isPaidStatus(paymentStatus);

        if (isPaid) {
            int marked = paymentOrderRepository.markAsPaid(orderId);
            // Only run activation if THIS request flipped the status.
            // Reload the order to pick up the fresh PAID row before the
            // exam-share-link branch fires (the in-memory `order` still
            // has whatever status JPA loaded at the start).
            order = paymentOrderRepository.findByOrderId(orderId).orElse(order);
            if (marked == 1) {
                activateOrder(order, orderId, user);
            }
            if (order.getExam() != null) {
                return ResponseEntity.ok(Map.of("status", "PAID", "examShareLink", order.getExam().getShareLink()));
            }
            return ResponseEntity.ok(Map.of("status", "PAID", "message", "Abunəlik aktivləşdirildi"));
        }

        if (isFailedStatus(paymentStatus)) {
            order.setStatus("FAILED");
            paymentOrderRepository.save(order);
            return ResponseEntity.ok(Map.of("status", "FAILED"));
        }

        // Still in-flight at KB. Keep the order in PENDING so the next /verify
        // call won't get gated by a stale PROCESSING marker. The frontend
        // polling loop will keep asking until KB resolves.
        if (!"PENDING".equals(order.getStatus())) {
            order.setStatus("PENDING");
            paymentOrderRepository.save(order);
        }
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
                // Guard against double activation if /verify already won the
                // race while the KB retry loop was in flight.
                int marked = paymentOrderRepository.markAsPaid(orderId);
                order = paymentOrderRepository.findByOrderId(orderId).orElse(order);
                if (marked == 1) {
                    activateOrder(order, orderId, null);
                } else {
                    log.info("KB callback: order={} already activated by another path", orderId);
                }
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
