package com.company.promoengine.engine;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.Money;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The running cart during a single evaluation: the input {@link Cart} plus one
 * {@link LineState} per cart line, in original request order.
 *
 * <p>Every total is derived from the lines' current unit prices, never tracked
 * separately, so the accounting invariants hold by construction. This class does not
 * order rules, run gates, or write audit entries.
 */
final class CartState {

    private final Cart cart;
    private final List<LineState> lines;

    CartState(Cart cart) {
        this.cart = Objects.requireNonNull(cart, "cart");
        this.lines = cart.items().stream().map(LineState::new).toList();
    }

    Cart cart() {
        return cart;
    }

    /** All lines in request order (unmodifiable list; index = request position). */
    List<LineState> lines() {
        return lines;
    }

    LineState line(int index) {
        return lines.get(index);
    }

    BigDecimal originalSubtotal() {
        return sum(lines, LineState::originalTotal);
    }

    BigDecimal currentSubtotal() {
        return sum(lines, LineState::currentTotal);
    }

    BigDecimal totalDiscount() {
        return originalSubtotal().subtract(currentSubtotal());
    }

    /**
     * Applies an accepted proposal. Every change is checked against the current state
     * first, so an invalid proposal throws without changing anything. Then, for each
     * change in order: reduce its units, mark them bundled if requested, and mark the line
     * item-discounted if this is an item-level rule and the amount is positive.
     *
     * <p>Each line may appear at most once per commit, which keeps the up-front check exact.
     */
    void commit(List<LineChange> changes, boolean itemLevel) {
        Objects.requireNonNull(changes, "changes");
        checkCanCommit(changes);
        for (LineChange change : changes) {
            LineState line = lines.get(change.lineIndex());
            int[] units = change.unitIndexes();
            line.reduce(units, change.amount());
            if (change.markBundled()) {
                line.markBundled(units);
            }
            if (itemLevel && change.amount().signum() > 0) {
                line.markItemDiscounted();
            }
        }
    }

    /** Throws if any change would fail or break the zero floor; changes nothing. */
    private void checkCanCommit(List<LineChange> changes) {
        Set<Integer> seenLines = new HashSet<>();
        for (LineChange change : changes) {
            Objects.checkIndex(change.lineIndex(), lines.size());
            if (!seenLines.add(change.lineIndex())) {
                throw new IllegalArgumentException("line " + change.lineIndex() + " appears in more than one change");
            }
            LineState line = lines.get(change.lineIndex());
            int[] units = change.unitIndexes();
            // Also rejects out-of-range and repeated unit indexes.
            BigDecimal available = line.currentTotalOf(units);
            if (change.amount().compareTo(available) > 0) {
                throw new IllegalArgumentException("amount " + change.amount().toPlainString()
                        + " exceeds current value " + available.toPlainString() + " of line " + change.lineIndex());
            }
            if (change.markBundled()) {
                for (int unit : units) {
                    if (line.isBundled(unit)) {
                        throw new IllegalArgumentException(
                                "unit " + unit + " of line " + change.lineIndex() + " is already bundled");
                    }
                }
            }
        }
    }

    /** One {@link ItemizedLine} per line, in request order. */
    List<ItemizedLine> itemizedLines() {
        return lines.stream().map(LineState::toItemizedLine).toList();
    }

    /**
     * Builds the final result. Totals are summed from the itemized lines themselves, so
     * {@code originalSubtotal = sum(originalExtendedPrice)},
     * {@code finalTotal = sum(finalExtendedPrice)}, and
     * {@code totalDiscount = originalSubtotal - finalTotal = sum(line.discount)}.
     * The audit trail is passed through unchanged.
     */
    EvaluationResult toResult(List<AuditEntry> auditTrail) {
        Objects.requireNonNull(auditTrail, "auditTrail");
        List<ItemizedLine> items = itemizedLines();
        BigDecimal original = sum(items, ItemizedLine::originalExtendedPrice);
        BigDecimal finalTotal = sum(items, ItemizedLine::finalExtendedPrice);
        return new EvaluationResult(
                cart.cartId(),
                cart.currency(),
                original,
                original.subtract(finalTotal),
                finalTotal,
                items,
                auditTrail);
    }

    private static <T> BigDecimal sum(List<T> values, Function<T, BigDecimal> amount) {
        return values.stream().map(amount).reduce(Money.ZERO, BigDecimal::add);
    }
}
