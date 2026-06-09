package az.testup.service;

import az.testup.entity.SubscriptionPlanPrice;
import az.testup.repository.SubscriptionPlanPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Single source of truth for turning a (tier, duration) into money. Centralises
 * the price lookup + daily-rate maths so the payment controller, the admin
 * revenue path, and the recovery scheduler can't drift apart on how a plan's
 * economic value is computed.
 *
 * Price semantics: {@link SubscriptionPlanPrice#getPrice()} is the TOTAL for the
 * whole period (not a per-month rate). A "month" is normalised to 30 days.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final int DAYS_PER_MONTH = 30;

    private final SubscriptionPlanPriceRepository priceRepository;

    /** Visible-or-not price row for an exact (tier, duration), if it exists. */
    public Optional<SubscriptionPlanPrice> findPrice(Long planId, int months) {
        return priceRepository.findByPlanIdAndDurationMonths(planId, months);
    }

    /** The tier's 1-month list price, used as a proration fallback. 0 if none. */
    public double monthlyListPrice(Long planId) {
        return priceRepository.findByPlanIdAndDurationMonths(planId, 1)
                .map(SubscriptionPlanPrice::getPrice)
                .orElse(0.0);
    }

    /**
     * Total price for a (tier, duration). Falls back to the 1-month price ×
     * months when the exact duration row is missing (e.g. an order placed for a
     * duration that was later hidden). Returns 0 when the tier has no price.
     */
    public double periodPrice(Long planId, int months) {
        int m = Math.max(1, months);
        return findPrice(planId, m)
                .map(SubscriptionPlanPrice::getPrice)
                .orElseGet(() -> monthlyListPrice(planId) * m);
    }

    /**
     * Daily rate for a (tier, duration): total period price / (months × 30).
     * Returns 0 when the tier is free / unpriced.
     */
    public double dailyRate(Long planId, int months) {
        int m = Math.max(1, months);
        double price = periodPrice(planId, m);
        return price <= 0 ? 0.0 : price / (m * (double) DAYS_PER_MONTH);
    }

    /** Economic (AZN) value of {@code durationDays} on a (tier, duration) plan. */
    public double economicValue(Long planId, int months, long durationDays) {
        return durationDays * dailyRate(planId, months);
    }
}
