package com.company.promoengine.engine;

import com.company.promoengine.domain.BundleComponent;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code BUNDLE_FIXED_PRICE}: forms as many complete component sets as the non-bundled
 * units allow, sells each set at {@code bundlePrice}, and marks the consumed units as
 * bundled so later item-level rules skip them.
 *
 * <p>Savings and allocation weights use the consumed units' original unit prices. Each
 * line's share is then clamped to what its consumed units currently cost (zero floor);
 * clamped amounts are not redistributed.
 */
final class BundleFixedPriceHandler implements RuleHandler {

    /** The units one line contributes to the bundles, and their value at original price. */
    private record Consumption(int lineIndex, int[] unitIndexes, BigDecimal originalValue) {
    }

    @Override
    public RuleType type() {
        return RuleType.BUNDLE_FIXED_PRICE;
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
        List<BundleComponent> components = rule.conditions().components();
        if (components == null || components.isEmpty()) {
            return Optional.of("conditions.components must not be empty");
        }
        Set<String> seenSkus = new HashSet<>();
        for (BundleComponent component : components) {
            if (RuleHandler.isBlank(component.sku())) {
                return Optional.of("conditions.components entries must have a non-blank sku");
            }
            if (component.quantity() < 1) {
                return Optional.of("conditions.components quantity must be >= 1 (sku " + component.sku() + ")");
            }
            // Case-sensitive, like SKU matching.
            if (!seenSkus.add(component.sku())) {
                return Optional.of("conditions.components lists sku " + component.sku() + " more than once");
            }
        }
        BigDecimal bundlePrice = rule.discountConfig().bundlePrice();
        if (bundlePrice == null || bundlePrice.signum() < 0) {
            return Optional.of("discountConfig.bundlePrice must be >= 0");
        }
        return Optional.empty();
    }

    @Override
    public RuleOutcome evaluate(PromotionRule rule, CartState cart) {
        List<BundleComponent> components = rule.conditions().components();
        BigDecimal bundlePrice = rule.discountConfig().bundlePrice();

        // 1. Complete sets: the scarcest component (non-bundled units only) decides. long: no overflow.
        long bundleCount = Long.MAX_VALUE;
        for (BundleComponent component : components) {
            long available = availableUnits(cart, component.sku());
            long sets = available / component.quantity();
            if (sets == 0) {
                return new RuleOutcome.Skip("incomplete bundle: sku " + component.sku() + " needs "
                        + component.quantity() + ", " + available + " available");
            }
            bundleCount = Math.min(bundleCount, sets);
        }

        // 2. Consume units: earlier lines first, lowest unit indexes first within a line.
        List<Consumption> consumed = new ArrayList<>();
        for (BundleComponent component : components) {
            long needed = (long) component.quantity() * bundleCount;
            for (int i = 0; i < cart.lines().size() && needed > 0; i++) {
                LineState line = cart.line(i);
                if (!component.sku().equals(line.item().sku())) {
                    continue;
                }
                int[] nonBundled = line.nonBundledUnits();
                int take = (int) Math.min(needed, nonBundled.length);
                if (take == 0) {
                    continue;
                }
                int[] units = Arrays.copyOf(nonBundled, take);
                BigDecimal originalValue = line.originalUnitPrice().multiply(BigDecimal.valueOf(take));
                consumed.add(new Consumption(i, units, originalValue));
                needed -= take;
            }
        }
        // Penny-allocation ties go to the earlier line in request order, not component order.
        consumed.sort(Comparator.comparingInt(Consumption::lineIndex));

        // 3. Savings at original prices, never negative.
        List<BigDecimal> weights = consumed.stream().map(Consumption::originalValue).toList();
        BigDecimal componentValue = weights.stream().reduce(Money.ZERO, BigDecimal::add);
        BigDecimal savings = componentValue.subtract(bundlePrice.multiply(BigDecimal.valueOf(bundleCount)));
        if (savings.signum() < 0) {
            savings = Money.ZERO;
        }
        // savings <= componentValue (bundlePrice >= 0), so allocate's precondition holds.
        List<BigDecimal> shares = MoneyMath.allocate(savings, weights);

        // 4. Clamp each share to the consumed units' current value; bundle every consumed unit,
        //    including lines whose share is 0.00. If all shares are 0.00, gate 6 skips the rule.
        List<LineChange> changes = new ArrayList<>();
        BigDecimal totalSaving = Money.ZERO;
        for (int k = 0; k < consumed.size(); k++) {
            Consumption consumption = consumed.get(k);
            BigDecimal currentValue = cart.line(consumption.lineIndex()).currentTotalOf(consumption.unitIndexes());
            BigDecimal share = shares.get(k).min(currentValue);
            changes.add(new LineChange(consumption.lineIndex(), consumption.unitIndexes(), share, true));
            totalSaving = totalSaving.add(share);
        }

        String detail = bundleCount + " x bundle at " + Money.format(bundlePrice)
                + " saving " + Money.format(totalSaving)
                + " on component value " + Money.format(componentValue);
        return new RuleOutcome.Apply(detail, changes);
    }

    private static long availableUnits(CartState cart, String sku) {
        long available = 0;
        for (LineState line : cart.lines()) {
            if (sku.equals(line.item().sku())) {
                available += line.nonBundledUnits().length;
            }
        }
        return available;
    }
}
