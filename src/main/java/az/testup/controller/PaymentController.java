package az.testup.controller;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.entity.Exam;
import az.testup.entity.PaymentOrder;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.SubscriptionPlanPrice;
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
import az.testup.service.PricingService;
import az.testup.service.PurchaseReceiptService;
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
import java.util.HashMap;
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
    private final PricingService pricingService;
    private final PurchaseReceiptService purchaseReceiptService;
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

        LocalDateTime now = LocalDateTime.now();
        Optional<UserSubscription> currentOpt = userSubscriptionRepository
                .findActiveSubscriptionByUserIdAndDate(user.getId(), now);

        // ── Plan-change rule ──────────────────────────────────────────────
        // Upgrading (lower tier → higher) and same-tier renewals are allowed
        // any time. Downgrading (higher tier → lower) is blocked while a paid
        // subscription is still active; the user can buy a lower tier only
        // after the current one expires. findActiveSubscriptionByUserIdAndDate
        // already filters out expired subs, so a lapsed/Free user can buy
        // anything.
        Map<String, Object> downgradeBlock = checkPlanChangeAllowed(currentOpt.orElse(null), plan);
        if (downgradeBlock != null) {
            return ResponseEntity.badRequest().body(downgradeBlock);
        }

        // Resolve the price for the chosen (tier, duration). Price rows carry
        // the TOTAL for the whole period — do NOT multiply by months again.
        SubscriptionPlanPrice priceRow = pricingService.findPrice(plan.getId(), months).orElse(null);
        if (priceRow == null || priceRow.getPrice() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu müddət üçün qiymət tapılmadı"));
        }
        double totalAmount = priceRow.getPrice();
        if (totalAmount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Bu plan pulsuz olduğu üçün birbaşa aktivdir"));
        }
        // Monthly-equivalent of the target price, for the gift-credit comparison.
        double newMonthlyPrice = totalAmount / months;

        // ── Prorate: monetary value of remaining time on current plan ──
        // We compute the credit in AZN of the unused portion of the active
        // subscription (only for plan SWITCH, not same-plan renewal).
        //
        // Daily rate falls back to the current tier's 1-month list price/30 when
        // amountPaid is 0 (e.g. manually-assigned subs). Remaining time uses
        // fractional days (hours/24.0) so the user never loses a partial day.
        double creditAzn = 0.0;
        if (currentOpt.isPresent()) {
            UserSubscription current = currentOpt.get();
            // Same defensive pattern — any of these references can be null on
            // legacy/manually-assigned subscription rows; bail out of the
            // credit calculation rather than 500-ing.
            SubscriptionPlan currentPlan = current.getPlan();
            // Monthly list price of the current tier (0 if free/unpriced).
            double currentMonthlyPrice = currentPlan != null && currentPlan.getId() != null
                    ? pricingService.monthlyListPrice(currentPlan.getId()) : 0.0;
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
                    double remainingListedValue = (currentMonthlyPrice / 30.0) * remainingDaysExact;
                    if (current.getAmountPaid() > 0) {
                        double oldDailyRate = current.getAmountPaid() / totalDaysExact;
                        creditAzn = oldDailyRate * remainingDaysExact;
                        // Cap credit at the listed value of the remaining time on
                        // the current plan — defends against over-credit if
                        // amountPaid was inflated by previous credits.
                        if (creditAzn > remainingListedValue) {
                            creditAzn = remainingListedValue;
                        }
                    } else if (newMonthlyPrice <= currentMonthlyPrice) {
                        // Gift / admin-assigned plan being switched to an
                        // equal-or-cheaper tier — apply list-price credit. The
                        // historic guard (amountPaid > 0) exists to block a
                        // gift→higher-tier rollover (e.g. 60-day Standart gift
                        // becoming ~20 free Pro days). Downgrades and lateral
                        // moves don't have that risk: the user is reducing the
                        // tier they already hold, not extracting more value.
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
        // Price rows hold the period total, so the daily rate is total/(months*30).
        // Use Math.round (not cast) so users don't lose a day from fractional truncation.
        double newDailyRate = totalAmount / (months * 30.0);
        long durationDays = Math.max(1L, Math.round(totalValue / newDailyRate));

        // Free switch: credit covers entire cost
        if (chargeAmount == 0.0) {
            AssignSubscriptionRequest req = new AssignSubscriptionRequest();
            req.setUserId(user.getId());
            req.setPlanId(plan.getId());
            req.setDurationMonths(months);
            req.setDurationDays(durationDays);
            req.setPaymentProvider("CREDIT");
            req.setAmountPaid(totalValue);
            userSubscriptionService.assignSubscription(req);
            auditLogService.log(AuditAction.SUBSCRIPTION_SWITCHED, user.getEmail(), user.getFullName(),
                    "SUBSCRIPTION", plan.getName(),
                    "Kredit ilə ödənişsiz keçid. Müddət: " + durationDays + " gün. Kredit: " + String.format("%.2f", creditAzn) + " AZN");
            return ResponseEntity.ok(Map.of(
                    "directActivated", true,
                    "orderType", "SUBSCRIPTION",
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
                "orderType", "SUBSCRIPTION",
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
                .orElseThrow(() -> new UnauthorizedException("Hesab tapılmadı, yenidən daxil olun"));

        // order.getUser() can be null on legacy rows — guard before dereferencing
        // so a missing buyer yields a clean 403, not a 500 NPE (#254).
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "Bu əməliyyat üçün icazəniz yoxdur"));
        }

        // Fast paths for terminal states — don't burn a KB API call.
        if ("PAID".equals(order.getStatus())) {
            Map<String, Object> r = verifyResponse(order, "PAID");
            r.put("alreadyProcessed", true);
            return ResponseEntity.ok(r);
        }
        if ("FAILED".equals(order.getStatus())) {
            return ResponseEntity.ok(verifyResponse(order, "FAILED"));
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
            Map<String, Object> r = verifyResponse(order, "PAID");
            if (order.getExam() == null) {
                r.put("message", "Abunəlik aktivləşdirildi");
            }
            return ResponseEntity.ok(r);
        }

        if (isFailedStatus(paymentStatus)) {
            order.setStatus("FAILED");
            paymentOrderRepository.save(order);
            return ResponseEntity.ok(verifyResponse(order, "FAILED"));
        }

        // Still in-flight at KB. Keep the order in PENDING so the next /verify
        // call won't get gated by a stale PROCESSING marker. The frontend
        // polling loop will keep asking until KB resolves.
        if (!"PENDING".equals(order.getStatus())) {
            order.setStatus("PENDING");
            paymentOrderRepository.save(order);
        }
        return ResponseEntity.ok(verifyResponse(order, paymentStatus));
    }

    /**
     * Build a /verify response that always carries the order type — and the
     * exam info for exam orders — regardless of the bank status. Without this
     * the client could only tell an exam purchase apart on a terminal PAID
     * with examShareLink, so a slow/pending bank confirmation fell back to
     * subscription-flavoured text on an exam purchase (#XXX).
     */
    private Map<String, Object> verifyResponse(PaymentOrder order, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        boolean isExam = order != null && order.getExam() != null;
        m.put("orderType", isExam ? "EXAM" : "SUBSCRIPTION");
        if (isExam) {
            m.put("examShareLink", order.getExam().getShareLink());
            if (order.getExam().getTitle() != null) {
                m.put("examTitle", order.getExam().getTitle());
            }
        }
        return m;
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

        // Mirror /initiate: wrap the whole flow so a missing field or a
        // KapitalBank/DB hiccup surfaces as a clean 4xx with a real message
        // instead of bubbling up as "Daxili server xətası" 500 (#254).
        try {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Giriş tələb olunur"));
        }

        // The shareLink was read unguarded (body.get(...).toString()), so a
        // request without it NPE'd into a 500. Validate it up front.
        Object rawShareLink = body == null ? null : body.get("shareLink");
        if (rawShareLink == null || rawShareLink.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "İmtahan kodu tələb olunur"));
        }
        String shareLink = rawShareLink.toString();

        User user = userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new UnauthorizedException("Hesab tapılmadı, yenidən daxil olun"));

        // Soft-deleted / missing exam → clean 404 instead of a bare
        // RuntimeException 500.
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı və ya artıq mövcud deyil"));

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
                "orderType", "EXAM",
                "examShareLink", exam.getShareLink(),
                "examTitle", exam.getTitle() != null ? exam.getTitle() : "",
                "paymentUrl", result.paymentUrl(),
                "amount", amount
        ));
        } catch (BadRequestException | ResourceNotFoundException | UnauthorizedException e) {
            throw e; // global handler → proper 4xx with the original message
        } catch (Exception e) {
            log.error("Exam payment initiate failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = "Ödəniş başladıla bilmədi, yenidən cəhd edin";
            return ResponseEntity.badRequest().body(Map.of("message", msg));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Plan-change rule for the PURCHASE path. Returns null when the change is
     * allowed; otherwise a 400 body explaining why a downgrade is blocked.
     *
     * Upgrade (target tier level > current) and same-tier renewal (==) are
     * always allowed. Downgrade (target level < current) is blocked while a paid
     * subscription is active — the user must wait until the current plan expires.
     * A user with no active sub, or only a Free (level 0) one, can buy anything.
     *
     * NOTE: admin assignment (UserSubscriptionService.assignSubscription) does
     * NOT call this — admins may assign any tier in any direction.
     */
    private Map<String, Object> checkPlanChangeAllowed(UserSubscription current, SubscriptionPlan target) {
        if (current == null || current.getPlan() == null) return null;
        Integer curLevelObj = current.getPlan().getLevel();
        int curLevel = curLevelObj != null ? curLevelObj : 0;
        if (curLevel <= 0) return null; // Free / no real tier → anything purchasable
        int newLevel = target.getLevel() != null ? target.getLevel() : 0;
        if (newLevel >= curLevel) return null; // upgrade or same-tier renewal
        String end = current.getEndDate() != null
                ? current.getEndDate().toLocalDate().toString() : "";
        return Map.of("message", "Hazırkı \"" + current.getPlan().getName() + "\" planınız "
                + end + " tarixinə qədər aktivdir. Daha aşağı plana yalnız bu müddət "
                + "bitdikdən sonra keçə bilərsiniz.");
    }

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

        // Legacy/partial order rows can carry a null user; fall back to the
        // authenticated user verifying the payment so activation never NPEs (#254).
        User orderUser = order.getUser() != null ? order.getUser() : verifyingUser;

        if (order.getExam() != null) {
            String shareLink = order.getExam().getShareLink();
            // Guard against a null buyer or an exam with no share link rather
            // than letting purchaseExam dereference null and 500 the callback.
            if (orderUser != null && shareLink != null && !shareLink.isBlank()) {
                examService.purchaseExam(shareLink, orderUser);
            } else {
                log.warn("activateOrder: skipping exam grant for order {} (user={}, shareLink={})",
                        orderId, orderUser != null ? orderUser.getId() : null, shareLink);
            }
        } else if (order.getPlan() != null) {
            AssignSubscriptionRequest request = new AssignSubscriptionRequest();
            request.setUserId(orderUser.getId());
            request.setPlanId(order.getPlan().getId());
            request.setDurationMonths(order.getMonths());
            request.setDurationDays(order.getDurationDays());
            request.setPaymentProvider("KAPITALBANK");
            request.setTransactionId(orderId);
            double economicValue = pricingService.economicValue(
                    order.getPlan().getId(), order.getMonths(), order.getDurationDays());
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

        // Email the buyer a receipt — once per order, non-blocking; email
        // failure is logged inside the receipt service and never breaks activation.
        purchaseReceiptService.sendForOrder(order, verifyingUser);
    }
}
