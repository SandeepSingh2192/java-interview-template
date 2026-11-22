package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What a {@link RuleHandler} proposes after reading the cart: either skip the rule, or
 * apply a list of line changes. A proposal changes nothing by itself; the engine decides
 * whether to commit it.
 *
 * <p>{@code detail} is the human-readable reason without the {@code "Applied: "} or
 * {@code "Skipped: "} prefix, which the engine adds.
 *
 * <p>Records nested in an interface are implicitly public, so their constructors must be
 * declared public; they are still invisible outside this package because the interface is not.
 */
sealed interface RuleOutcome {

    /** The rule's condition is not met (gate 5). */
    record Skip(String detail) implements RuleOutcome {
        public Skip {
            requireDetail(detail);
        }
    }

    /** Apply these changes. An empty list or all-zero amounts mean a 0.00 discount. */
    record Apply(String detail, List<LineChange> changes) implements RuleOutcome {
        public Apply {
            requireDetail(detail);
            changes = List.copyOf(changes);
        }

        /** Sum of all change amounts: what the rule grants if committed. */
        BigDecimal totalDiscount() {
            return changes.stream().map(LineChange::amount).reduce(Money.ZERO, BigDecimal::add);
        }
    }

    /**
     * Take {@code amount} off the given units of one line, split by their current prices,
     * and optionally mark those units as bundled.
     *
     * <p>Note: record equality compares {@code unitIndexes} by reference, so tests should
     * compare its contents.
     */
    record LineChange(int lineIndex, int[] unitIndexes, BigDecimal amount, boolean markBundled) {
        public LineChange {
            if (lineIndex < 0) {
                throw new IllegalArgumentException("lineIndex must not be negative: " + lineIndex);
            }
            Objects.requireNonNull(unitIndexes, "unitIndexes");
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("amount must not be negative: " + amount.toPlainString());
            }
            if (amount.stripTrailingZeros().scale() > Money.SCALE) {
                throw new IllegalArgumentException("amount must have at most 2 decimal places: " + amount.toPlainString());
            }
            unitIndexes = unitIndexes.clone();
            amount = amount.setScale(Money.SCALE);
        }

        /** A copy, so callers cannot change the proposal. */
        @Override
        public int[] unitIndexes() {
            return unitIndexes.clone();
        }
    }

    private static void requireDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
