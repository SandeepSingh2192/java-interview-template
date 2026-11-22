package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code PERCENTAGE_OFF_CATEGORY}: a percentage off the non-bundled units of every line in
 * one category (case-sensitive match), with an optional cap on the rule's total.
 *
 * <p>Each line's discount is rounded on its own (never on the combined sum). If the sum
 * exceeds {@code maxDiscountAmount}, the cap is split across the lines in proportion to
 * their uncapped discounts.
 */
final class CategoryPercentageHandler implements RuleHandler {

    @Override
    public RuleType type() {
        return RuleType.PERCENTAGE_OFF_CATEGORY;
    }

    @Override
    public boolean itemLevel() {
        return true;
    }

    @Override
    public Optional<String> validate(PromotionRule rule) {
        if (rule.conditions() == null) {
            return Optional.of("conditions are missing");
        }
        if (rule.discountConfig() == null) {
            return Optional.of("discountConfig is missing");
        }
        if (RuleHandler.isBlank(rule.conditions().category())) {
            return Optional.of("conditions.category is required");
        }
        if (!RuleHandler.isValidPercentage(rule.discountConfig().percentage())) {
            return Optional.of("discountConfig.percentage must be > 0 and <= 100");
        }
        BigDecimal cap = rule.discountConfig().maxDiscountAmount();
        if (cap != null && !RuleHandler.isPositive(cap)) {
            return Optional.of("discountConfig.maxDiscountAmount must be > 0");
        }
        return Optional.empty();
    }

    @Override
    public RuleOutcome evaluate(PromotionRule rule, CartState cart) {
        String category = rule.conditions().category();
        BigDecimal percentage = rule.discountConfig().percentage();
        BigDecimal cap = rule.discountConfig().maxDiscountAmount();

        // 1. Matching lines that still have non-bundled units, each discounted on its own.
        boolean categoryInCart = false;
        List<Integer> matchingLines = new ArrayList<>();
        List<BigDecimal> discounts = new ArrayList<>();
        for (int i = 0; i < cart.lines().size(); i++) {
            LineState line = cart.line(i);
            if (!category.equals(line.item().category())) {
                continue;
            }
            categoryInCart = true;
            if (line.nonBundledUnits().length == 0) {
                continue;
            }
            BigDecimal base = line.nonBundledTotal();
            matchingLines.add(i);
            discounts.add(MoneyMath.percentOf(base, percentage).min(base));
        }
        if (!categoryInCart) {
            return new RuleOutcome.Skip("no items in category " + category);
        }
        if (matchingLines.isEmpty()) {
            return new RuleOutcome.Skip("all units in category " + category + " are already bundled");
        }

        // 2. Optional cap on the rule total, split by each line's uncapped discount.
        BigDecimal uncappedTotal = discounts.stream().reduce(Money.ZERO, BigDecimal::add);
        boolean capped = cap != null && uncappedTotal.compareTo(cap) > 0;
        if (capped) {
            discounts = MoneyMath.allocate(cap, discounts);
        }

        // 3. One change per line that actually gets money off, spread over its non-bundled units.
        List<LineChange> changes = new ArrayList<>();
        for (int k = 0; k < matchingLines.size(); k++) {
            BigDecimal discount = discounts.get(k);
            if (discount.signum() > 0) {
                int lineIndex = matchingLines.get(k);
                changes.add(new LineChange(lineIndex, cart.line(lineIndex).nonBundledUnits(), discount, false));
            }
        }

        String detail = RuleHandler.formatPercentage(percentage) + "% off category " + category
                + (capped ? " (capped at " + Money.format(cap) + ")" : "");
        return new RuleOutcome.Apply(detail, changes);
    }
}
