package az.testup.service;

import az.testup.entity.PaymentOrder;
import az.testup.entity.User;
import az.testup.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends a one-per-order purchase receipt email. Called from every payment
 * activation path (verify/callback, recovery scheduler, admin force-activate);
 * the {@code receiptSent} flag on the order keeps it idempotent so retries and
 * double-activation never duplicate the email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseReceiptService {

    private final EmailService emailService;
    private final PaymentOrderRepository paymentOrderRepository;

    @Transactional
    public void sendForOrder(PaymentOrder order, User fallbackUser) {
        if (order == null || order.isReceiptSent()) return;

        // Legacy rows can carry a null buyer; fall back to whoever activated it.
        User user = order.getUser() != null ? order.getUser() : fallbackUser;
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("Receipt skipped for order {} — no recipient email", order.getOrderId());
            return;
        }

        try {
            String product = order.getExam() != null ? order.getExam().getTitle()
                    : (order.getPlan() != null ? order.getPlan().getName() : "Sifariş");
            String detail = order.getExam() != null
                    ? "İmtahan girişi"
                    : (order.getDurationDays() > 0 ? order.getDurationDays() + " günlük abunəlik" : "Abunəlik");

            // @Async — returns immediately; send errors are logged inside EmailService.
            emailService.sendReceipt(user.getEmail(), user.getFullName(), order.getOrderId(),
                    product, order.getAmount(), detail, order.getCreatedAt());

            // Mark as sent (queued) so no other activation path re-sends it.
            order.setReceiptSent(true);
            paymentOrderRepository.save(order);
        } catch (Exception e) {
            // Never let receipt handling break activation.
            log.error("Receipt email failed for order {}: {}", order.getOrderId(), e.getMessage());
        }
    }
}
