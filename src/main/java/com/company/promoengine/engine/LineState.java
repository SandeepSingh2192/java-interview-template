package com.company.promoengine.engine;

import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Mutable pricing state of one cart line during a single evaluation.
 *
 * <p>The line is tracked unit by unit: unit {@code i} has its own current price and
 * bundled flag. A line's current price is the sum of its units' current prices.
 * All money is scale-2 {@link BigDecimal}.
 *
 * <p>This class knows nothing about promotions. It only offers reads and three
 * changes ({@link #reduce}, {@link #markBundled}, {@link #markItemDiscounted}).
 */
final class LineState {

    private final CartItem item;
    private final BigDecimal originalUnitPrice;
    private final BigDecimal originalTotal;
    private final BigDecimal[] unitPrices;
    private final boolean[] bundled;
    private boolean itemDiscounted;

    LineState(CartItem item) {
        this.item = Objects.requireNonNull(item, "item");
        if (item.quantity() < 0) {
            throw new IllegalArgumentException("quantity must not be negative: " + item.quantity());
        }
        this.originalUnitPrice = Money.of(Objects.requireNonNull(item.unitPrice(), "unitPrice"));
        if (originalUnitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + originalUnitPrice);
        }
        this.originalTotal = Money.extended(originalUnitPrice, item.quantity());
        this.unitPrices = new BigDecimal[item.quantity()];
        // BigDecimal is immutable, so all untouched units can share one instance.
        Arrays.fill(unitPrices, originalUnitPrice);
        this.bundled = new boolean[item.quantity()];
    }

    // ---- reads ---------------------------------------------------------------

    CartItem item() {
        return item;
    }

    int quantity() {
        return unitPrices.length;
    }

    /** The input unit price at scale 2 (never changes). */
    BigDecimal originalUnitPrice() {
        return originalUnitPrice;
    }

    /** {@code unitPrice * quantity} at scale 2 (never changes). */
    BigDecimal originalTotal() {
        return originalTotal;
    }

    BigDecimal currentUnitPrice(int unit) {
        return unitPrices[Objects.checkIndex(unit, unitPrices.length)];
    }

    boolean isBundled(int unit) {
        return bundled[Objects.checkIndex(unit, bundled.length)];
    }

    boolean isItemDiscounted() {
        return itemDiscounted;
    }

    /** Indexes of every unit, ascending. */
    int[] allUnits() {
        return IntStream.range(0, unitPrices.length).toArray();
    }

    /** Indexes of units not yet consumed by a bundle, ascending. */
    int[] nonBundledUnits() {
        return IntStream.range(0, unitPrices.length).filter(i -> !bundled[i]).toArray();
    }

    /** Sum of all units' current prices. */
    BigDecimal currentTotal() {
        return currentTotalOf(allUnits());
    }

    /** Sum of the current prices of non-bundled units: the base for item-level rules. */
    BigDecimal nonBundledTotal() {
        return currentTotalOf(nonBundledUnits());
    }

    /** Sum of the current prices of the given units. */
    BigDecimal currentTotalOf(int[] units) {
        checkUnits(units);
        BigDecimal total = Money.ZERO;
        for (int unit : units) {
            total = total.add(unitPrices[unit]);
        }
        return total;
    }

    /** Everything taken off this line so far: {@code originalTotal - currentTotal}. */
    BigDecimal discount() {
        return originalTotal.subtract(currentTotal());
    }

    // ---- changes -------------------------------------------------------------

    /**
     * Takes {@code amount} off the given units, split in proportion to their current
     * prices with {@link MoneyMath#allocate} (ties go to the earlier position in
     * {@code units}).
     *
     * <p>Zero floor: {@code allocate} rejects an amount larger than the units' combined
     * current price and never gives a unit a share above its own price, so no unit can
     * go below 0.00. If the call throws, nothing has been changed.
     *
     * @throws IndexOutOfBoundsException if an index is outside the line
     * @throws IllegalArgumentException if an index repeats, or the amount is negative,
     *         has more than two decimals, or exceeds the units' current total
     */
    void reduce(int[] units, BigDecimal amount) {
        checkUnits(units);
        List<BigDecimal> weights = new ArrayList<>(units.length);
        for (int unit : units) {
            weights.add(unitPrices[unit]);
        }
        List<BigDecimal> shares = MoneyMath.allocate(amount, weights);
        for (int k = 0; k < units.length; k++) {
            int unit = units[k];
            unitPrices[unit] = unitPrices[unit].subtract(shares.get(k));
        }
    }

    /**
     * Marks the given units as consumed by a bundle. A unit can be bundled only once.
     *
     * @throws IndexOutOfBoundsException if an index is outside the line
     * @throws IllegalArgumentException if an index repeats or a unit is already bundled
     */
    void markBundled(int[] units) {
        checkUnits(units);
        for (int unit : units) {
            if (bundled[unit]) {
                throw new IllegalArgumentException("unit " + unit + " is already bundled");
            }
        }
        for (int unit : units) {
            bundled[unit] = true;
        }
    }

    /** Records that an item-level rule lowered this line's price. */
    void markItemDiscounted() {
        itemDiscounted = true;
    }

    /** Snapshot for the response: input quantity and unit price, derived discount. */
    ItemizedLine toItemizedLine() {
        BigDecimal finalTotal = currentTotal();
        return new ItemizedLine(
                item.sku(),
                item.category(),
                item.quantity(),
                originalUnitPrice,
                originalTotal,
                originalTotal.subtract(finalTotal),
                finalTotal);
    }

    /** Rejects null, out-of-range, and repeated indexes before any state is read or changed. */
    private void checkUnits(int[] units) {
        Objects.requireNonNull(units, "units");
        boolean[] seen = new boolean[unitPrices.length];
        for (int unit : units) {
            Objects.checkIndex(unit, unitPrices.length);
            if (seen[unit]) {
                throw new IllegalArgumentException("unit index " + unit + " appears more than once");
            }
            seen[unit] = true;
        }
    }
}
