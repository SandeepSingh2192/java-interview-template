package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The shared README flow for every cart-level rule:
 * <ol>
 *   <li>Eligible lines: all lines when {@code stackableWithItemPromos} is {@code true} or
 *       {@code null}; only lines no item-level rule has discounted when it is {@code false}.</li>
 *   <li>{@code base} = sum of the eligible lines' current prices; skip when it is 0.00.</li>
 *   <li>The rule-specific predicate and grant (the two hooks below).</li>
 *   <li>Cap the grant at {@code base} and split it across the eligible lines in proportion to
 *       their current prices (penny allocation, ties to the earlier line).</li>
 * </ol>
 *
 * <p>Cart-level rules reduce all units of an eligible line, bundled ones included, and never
 * mark lines item-discounted ({@link #itemLevel()} is {@code false}).
 */
abstract class CartLevelRuleHandler implements RuleHandler {

    /**
     * What a rule wants to grant before the base cap, and how to describe the amount that is
     * actually granted (after the cap) in the audit reason.
     */
    record Grant(BigDecimal amount, Function<BigDecimal, String> describe) {
        Grant {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(describe, "describe");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("grant must not be negative: " + amount.toPlainString());
            }
        }
    }

    @Override
    public final boolean itemLevel() {
        return false;
    }

    @Override
    public final RuleOutcome evaluate(PromotionRule rule, CartState cart) {
        boolean stacks = stacksWithItemPromos(rule);

        // 1. Eligible lines, in request order, weighted by current price.
        List<Integer> eligibleLines = new ArrayList<>();
        List<BigDecimal> weights = new ArrayList<>();
        for (int i = 0; i < cart.lines().size(); i++) {
            LineState line = cart.line(i);
            if (stacks || !line.isItemDiscounted()) {
                eligibleLines.add(i);
                weights.add(line.currentTotal());
            }
        }

        // 2. Base; an empty base means there is nothing to discount.
        BigDecimal base = weights.stream().reduce(Money.ZERO, BigDecimal::add);
        if (base.signum() == 0) {
            return new RuleOutcome.Skip(stacks
                    ? "no eligible subtotal"
                    : "no eligible subtotal (item-discounted lines excluded)");
        }

        // 3. Rule-specific predicate and grant.
        Optional<String> failure = predicateFailure(rule, cart, base);
        if (failure.isPresent()) {
            return new RuleOutcome.Skip(failure.get());
        }
        Grant grant = grant(rule, base);

        // 4. Never more than the base; split by current price. Zero shares need no change.
        BigDecimal granted = grant.amount().min(base);
        List<BigDecimal> shares = MoneyMath.allocate(granted, weights);
        List<LineChange> changes = new ArrayList<>();
        for (int k = 0; k < eligibleLines.size(); k++) {
            BigDecimal share = shares.get(k);
            if (share.signum() > 0) {
                int lineIndex = eligibleLines.get(k);
                changes.add(new LineChange(lineIndex, cart.line(lineIndex).allUnits(), share, false));
            }
        }
        return new RuleOutcome.Apply(grant.describe().apply(granted), changes);
    }

    /**
     * Gate 5 for this rule (for example "no qualifying tier"), checked after the base is known
     * to be positive. Default: no extra condition.
     *
     * @return a description of why the rule does not apply, or empty if it does
     */
    Optional<String> predicateFailure(PromotionRule rule, CartState cart, BigDecimal base) {
        return Optional.empty();
    }

    /** How much this rule grants on {@code base} (the caller caps it at {@code base}). */
    abstract Grant grant(PromotionRule rule, BigDecimal base);

    /** {@code null} and {@code true} both mean the rule stacks with item-level discounts. */
    static boolean stacksWithItemPromos(PromotionRule rule) {
        return !Boolean.FALSE.equals(rule.stackableWithItemPromos());
    }

    /** "running subtotal 2500.00", or "eligible subtotal ..." when item-discounted lines are excluded. */
    static String subtotalText(PromotionRule rule, BigDecimal base) {
        return (stacksWithItemPromos(rule) ? "running subtotal " : "eligible subtotal ") + Money.format(base);
    }
}
