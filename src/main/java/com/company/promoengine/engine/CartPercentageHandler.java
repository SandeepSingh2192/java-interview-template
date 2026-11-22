package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * {@code CART_PERCENTAGE_OFF}: {@code percentage%} of the eligible cart subtotal, calculated
 * once on the whole base and rounded once. The shared cart-level flow in
 * {@link CartLevelRuleHandler} selects the eligible lines, skips an empty base, caps the
 * grant, and allocates it across the lines.
 */
final class CartPercentageHandler extends CartLevelRuleHandler {

    @Override
    public RuleType type() {
        return RuleType.CART_PERCENTAGE_OFF;
    }

    @Override
    public Optional<String> validate(PromotionRule rule) {
        if (rule.discountConfig() == null) {
            return Optional.of("discountConfig is missing");
        }
        if (!RuleHandler.isValidPercentage(rule.discountConfig().percentage())) {
            return Optional.of("discountConfig.percentage must be > 0 and <= 100");
        }
        return Optional.empty();
    }

    @Override
    Grant grant(PromotionRule rule, BigDecimal base) {
        BigDecimal percentage = rule.discountConfig().percentage();
        String percent = RuleHandler.formatPercentage(percentage);
        return new Grant(MoneyMath.percentOf(base, percentage),
                granted -> percent + "% (" + Money.format(granted) + ") off cart (" + subtotalText(rule, base) + ")");
    }
}
