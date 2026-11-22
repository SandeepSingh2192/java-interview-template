package com.company.promoengine.engine;

import com.company.promoengine.domain.DiscountConfig;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code BUY_X_GET_CHEAPEST_FREE}: pools every non-bundled unit whose SKU is listed
 * (case-sensitive, across lines and SKUs). Each complete group of {@code buy + free}
 * units earns {@code free} free units, and the cheapest pooled units are freed at their
 * full current price.
 *
 * <p>Cheapest means: current unit price ascending, then line order, then unit index.
 */
final class BuyXGetCheapestFreeHandler implements RuleHandler {

    /** One eligible unit: where it is and what it currently costs. */
    private record PoolUnit(int lineIndex, int unitIndex, BigDecimal price) {
    }

    private static final Comparator<PoolUnit> CHEAPEST_FIRST =
            Comparator.comparing(PoolUnit::price)
                    .thenComparingInt(PoolUnit::lineIndex)
                    .thenComparingInt(PoolUnit::unitIndex);

    @Override
    public RuleType type() {
        return RuleType.BUY_X_GET_CHEAPEST_FREE;
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
        List<String> skus = rule.conditions().skus();
        if (skus == null || skus.isEmpty()) {
            return Optional.of("conditions.skus must not be empty");
        }
        if (skus.stream().anyMatch(RuleHandler::isBlank)) {
            return Optional.of("conditions.skus must not contain blank entries");
        }
        DiscountConfig config = rule.discountConfig();
        if (!isAtLeastOne(config.buyQuantity())) {
            return Optional.of("discountConfig.buyQuantity must be an integer >= 1");
        }
        if (!isAtLeastOne(config.freeQuantity())) {
            return Optional.of("discountConfig.freeQuantity must be an integer >= 1");
        }
        return Optional.empty();
    }

    @Override
    public RuleOutcome evaluate(PromotionRule rule, CartState cart) {
        List<String> skuList = rule.conditions().skus();
        Set<String> skus = new HashSet<>(skuList);
        // long: buy + free must not overflow for large inputs.
        long buy = rule.discountConfig().buyQuantity();
        long free = rule.discountConfig().freeQuantity();

        // 1. Pool every non-bundled unit of every line whose SKU is listed.
        boolean skuInCart = false;
        List<PoolUnit> pool = new ArrayList<>();
        for (int i = 0; i < cart.lines().size(); i++) {
            LineState line = cart.line(i);
            if (!skus.contains(line.item().sku())) {
                continue;
            }
            skuInCart = true;
            for (int unit : line.nonBundledUnits()) {
                pool.add(new PoolUnit(i, unit, line.currentUnitPrice(unit)));
            }
        }
        if (!skuInCart) {
            return new RuleOutcome.Skip("no items with SKUs " + skuList);
        }
        if (pool.isEmpty()) {
            return new RuleOutcome.Skip("all units with SKUs " + skuList + " are already bundled");
        }

        // 2. Only complete groups earn free units.
        long groupSize = buy + free;
        long groups = pool.size() / groupSize;
        if (groups == 0) {
            return new RuleOutcome.Skip(pool.size() + " eligible unit(s) with SKUs " + skuList
                    + ", but a complete group needs " + groupSize);
        }
        // groups * free < groups * groupSize <= pool.size(), so this fits in an int.
        int freeUnits = (int) (groups * free);

        // 3. Free the cheapest units, grouped by line in request order.
        pool.sort(CHEAPEST_FIRST);
        Map<Integer, List<PoolUnit>> freedByLine = new TreeMap<>();
        for (PoolUnit unit : pool.subList(0, freeUnits)) {
            freedByLine.computeIfAbsent(unit.lineIndex(), line -> new ArrayList<>()).add(unit);
        }

        // 4. Each line's discount is the full current price of its freed units.
        List<LineChange> changes = new ArrayList<>();
        for (Map.Entry<Integer, List<PoolUnit>> entry : freedByLine.entrySet()) {
            List<PoolUnit> freed = entry.getValue();
            int[] unitIndexes = freed.stream().mapToInt(PoolUnit::unitIndex).sorted().toArray();
            BigDecimal amount = freed.stream().map(PoolUnit::price).reduce(Money.ZERO, BigDecimal::add);
            if (amount.signum() > 0) {
                changes.add(new LineChange(entry.getKey(), unitIndexes, amount, false));
            }
        }

        String detail = "buy " + buy + " get " + free + " cheapest free ("
                + freeUnits + (freeUnits == 1 ? " unit" : " units") + " free)";
        return new RuleOutcome.Apply(detail, changes);
    }

    private static boolean isAtLeastOne(Integer quantity) {
        return quantity != null && quantity >= 1;
    }
}
