package com.company.promoengine.engine;

import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.DiscountConfig;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * {@code COUPON_PAYMENT_OFF}: a percentage or fixed amount off the eligible cart subtotal when
 * the cart's coupon code matches (case-insensitive) and, if the rule names a payment method,
 * the cart's payment method matches too (case-insensitive). Values are compared as given,
 * without trimming. The shared cart-level flow in {@link CartLevelRuleHandler} selects the
 * eligible lines, skips an empty base, caps the grant, and allocates it.
 */
final class CouponPaymentHandler extends CartLevelRuleHandler {

    @Override
    public RuleType type() {
        return RuleType.COUPON_PAYMENT_OFF;
    }

    @Override
    public Optional<String> validate(PromotionRule rule) {
        if (rule.conditions() == null) {
            return Optional.of("conditions are missing");
        }
        if (RuleHandler.isBlank(rule.conditions().couponCode())) {
            return Optional.of("conditions.couponCode is required");
        }
        DiscountConfig config = rule.discountConfig();
        if (config == null) {
            return Optional.of("discountConfig is missing");
        }
        boolean hasPercentage = config.percentage() != null;
        boolean hasAmount = config.amount() != null;
        if (hasPercentage == hasAmount) {
            return Optional.of("discountConfig needs exactly one of percentage or amount");
        }
        if (hasPercentage && !RuleHandler.isValidPercentage(config.percentage())) {
            return Optional.of("discountConfig.percentage must be > 0 and <= 100");
        }
        if (hasAmount && !RuleHandler.isPositive(config.amount())) {
            return Optional.of("discountConfig.amount must be > 0");
        }
        return Optional.empty();
    }

    @Override
    Optional<String> predicateFailure(PromotionRule rule, CartState cart, BigDecimal base) {
        Cart input = cart.cart();
        String requiredCoupon = rule.conditions().couponCode();
        if (input.couponCode() == null) {
            return Optional.of("cart has no coupon code (rule requires " + requiredCoupon + ")");
        }
        if (!input.couponCode().equalsIgnoreCase(requiredCoupon)) {
            return Optional.of("coupon " + input.couponCode() + " does not match " + requiredCoupon);
        }
        String requiredPayment = requiredPaymentMethod(rule);
        if (requiredPayment == null) {
            return Optional.empty();
        }
        if (input.paymentMethod() == null) {
            return Optional.of("cart has no payment method (rule requires " + requiredPayment + ")");
        }
        if (!input.paymentMethod().equalsIgnoreCase(requiredPayment)) {
            return Optional.of("payment method " + input.paymentMethod() + " does not match " + requiredPayment);
        }
        return Optional.empty();
    }

    @Override
    Grant grant(PromotionRule rule, BigDecimal base) {
        String requiredPayment = requiredPaymentMethod(rule);
        String offer = " coupon " + rule.conditions().couponCode()
                + (requiredPayment == null ? "" : " via " + requiredPayment)
                + " off cart (" + subtotalText(rule, base) + ")";

        BigDecimal percentage = rule.discountConfig().percentage();
        if (percentage != null) {
            // Calculated once on the base and rounded once.
            String percent = RuleHandler.formatPercentage(percentage);
            return new Grant(MoneyMath.percentOf(base, percentage), granted -> percent + "%" + offer);
        }
        return new Grant(rule.discountConfig().amount(), granted -> Money.format(granted) + offer);
    }

    /** The rule's payment method, or {@code null} when it has none (null or blank). */
    private static String requiredPaymentMethod(PromotionRule rule) {
        String paymentMethod = rule.conditions().paymentMethod();
        return RuleHandler.isBlank(paymentMethod) ? null : paymentMethod;
    }
}
