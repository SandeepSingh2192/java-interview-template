package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.domain.SpendTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * {@code TIERED_SPEND}: picks the single tier with the greatest {@code minSubtotal <= base}
 * (list order does not matter; equal thresholds keep the earlier tier) and grants its
 * percentage of {@code base} or its fixed amount. The shared cart-level flow in
 * {@link CartLevelRuleHandler} computes the base, caps the grant, and allocates it.
 */
final class TieredSpendHandler extends CartLevelRuleHandler {

    @Override
    public RuleType type() {
        return RuleType.TIERED_SPEND;
    }

    @Override
    public Optional<String> validate(PromotionRule rule) {
        if (rule.discountConfig() == null) {
            return Optional.of("discountConfig is missing");
        }
        List<SpendTier> tiers = rule.discountConfig().tiers();
        if (tiers == null || tiers.isEmpty()) {
            return Optional.of("discountConfig.tiers must not be empty");
        }
        // Every tier must be valid, including tiers that would never be selected.
        for (int i = 0; i < tiers.size(); i++) {
            SpendTier tier = tiers.get(i);
            String label = "tier " + (i + 1) + ": ";
            if (tier.minSubtotal() == null || tier.minSubtotal().signum() < 0) {
                return Optional.of(label + "minSubtotal must be >= 0");
            }
            boolean hasPercentage = tier.percentage() != null;
            boolean hasAmount = tier.amount() != null;
            if (hasPercentage == hasAmount) {
                return Optional.of(label + "exactly one of percentage or amount is required");
            }
            if (hasPercentage && !RuleHandler.isValidPercentage(tier.percentage())) {
                return Optional.of(label + "percentage must be > 0 and <= 100");
            }
            if (hasAmount && !RuleHandler.isPositive(tier.amount())) {
                return Optional.of(label + "amount must be > 0");
            }
        }
        return Optional.empty();
    }

    @Override
    Optional<String> predicateFailure(PromotionRule rule, CartState cart, BigDecimal base) {
        if (selectTier(rule.discountConfig().tiers(), base).isEmpty()) {
            return Optional.of("no spend tier qualifies for " + subtotalText(rule, base));
        }
        return Optional.empty();
    }

    @Override
    Grant grant(PromotionRule rule, BigDecimal base) {
        SpendTier tier = selectTier(rule.discountConfig().tiers(), base).orElseThrow();
        String threshold = "spend tier >= " + Money.format(tier.minSubtotal()) + " grants ";
        String suffix = " off cart (" + subtotalText(rule, base) + ")";

        if (tier.percentage() != null) {
            // Calculated once on the base and rounded once.
            BigDecimal amount = MoneyMath.percentOf(base, tier.percentage());
            String percent = RuleHandler.formatPercentage(tier.percentage());
            return new Grant(amount, granted -> threshold + percent + "% (" + Money.format(granted) + ")" + suffix);
        }
        return new Grant(tier.amount(), granted -> threshold + Money.format(granted) + suffix);
    }

    /**
     * The tier with the greatest {@code minSubtotal <= base}. A later tier replaces the current
     * choice only if its threshold is strictly greater, so equal thresholds keep the earlier tier.
     */
    static Optional<SpendTier> selectTier(List<SpendTier> tiers, BigDecimal base) {
        SpendTier best = null;
        for (SpendTier tier : tiers) {
            boolean qualifies = tier.minSubtotal().compareTo(base) <= 0;
            if (qualifies && (best == null || tier.minSubtotal().compareTo(best.minSubtotal()) > 0)) {
                best = tier;
            }
        }
        return Optional.ofNullable(best);
    }
}
