package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.assertTotalsConsistent;
import static com.company.promoengine.engine.EngineTestSupport.cart;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CartStateTest {

    @Nested
    class TotalsAndResult {

        @Test
        void keepsLinesInRequestOrder() {
            var items = List.of(
                    item("SKU-C", "Z", "1.00", 1),
                    item("SKU-A", "A", "2.00", 1),
                    item("SKU-B", "M", "3.00", 1));
            var state = new CartState(cart(items));

            assertThat(state.lines()).extracting(LineState::item).containsExactlyElementsOf(items);
            assertThat(state.line(1).item()).isSameAs(items.get(1));
            assertThat(state.itemizedLines()).extracting(ItemizedLine::sku)
                    .containsExactly("SKU-C", "SKU-A", "SKU-B");
        }

        @Test
        void subtotalsAndTotalDiscountFollowLineChanges() {
            var state = new CartState(cart(
                    item("SKU-SHOES", "FOOTWEAR", "2000.00", 1),
                    item("SKU-SOCKS", "ACCESSORIES", "500.00", 1)));

            assertThat(state.originalSubtotal()).isEqualTo(money("2500.00"));
            assertThat(state.currentSubtotal()).isEqualTo(money("2500.00"));
            assertThat(state.totalDiscount()).isEqualTo(money("0.00"));

            state.commit(List.of(new LineChange(0, new int[] {0}, money("200.00"), false)), true);

            assertThat(state.originalSubtotal()).isEqualTo(money("2500.00"));
            assertThat(state.currentSubtotal()).isEqualTo(money("2300.00"));
            assertThat(state.totalDiscount()).isEqualTo(money("200.00"));
        }

        @Test
        void resultSatisfiesAccountingInvariants() {
            // README example: 3 laptops, 2 mice, 2 bags -> 2 bundles, then 10% off the leftover laptop.
            var state = new CartState(cart(
                    item("SKU-LAPTOP", "LAPTOP", "800.00", 3),
                    item("SKU-MOUSE", "ACCESSORIES", "150.00", 2),
                    item("SKU-BAG", "ACCESSORIES", "50.00", 2)));
            state.commit(List.of(
                    new LineChange(0, new int[] {0, 1}, money("320.00"), true),
                    new LineChange(1, new int[] {0, 1}, money("60.00"), true),
                    new LineChange(2, new int[] {0, 1}, money("20.00"), true)), true);
            state.commit(List.of(new LineChange(0, state.line(0).nonBundledUnits(), money("80.00"), false)), true);
            var audit = List.of(
                    audit("PROMO_DESK", RuleType.BUNDLE_FIXED_PRICE, "400.00"),
                    audit("PROMO_LAPTOP", RuleType.PERCENTAGE_OFF_CATEGORY, "80.00"));

            EvaluationResult result = state.toResult(audit);

            assertThat(result.cartId()).isEqualTo("cart-1");
            assertThat(result.currency()).isEqualTo("INR");
            assertThat(result.originalSubtotal()).isEqualTo(money("2800.00"));
            assertThat(result.finalTotal()).isEqualTo(money("2320.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("480.00"));
            assertThat(result.itemizedBreakdown()).containsExactly(
                    new ItemizedLine("SKU-LAPTOP", "LAPTOP", 3, money("800.00"),
                            money("2400.00"), money("400.00"), money("2000.00")),
                    new ItemizedLine("SKU-MOUSE", "ACCESSORIES", 2, money("150.00"),
                            money("300.00"), money("60.00"), money("240.00")),
                    new ItemizedLine("SKU-BAG", "ACCESSORIES", 2, money("50.00"),
                            money("100.00"), money("20.00"), money("80.00")));
            assertThat(result.auditTrail()).containsExactlyElementsOf(audit);
            assertTotalsConsistent(result);
        }

        @Test
        void lineCanBeReducedToZeroButNotBelow() {
            var state = new CartState(cart(item("SKU-A", "BOOKS", "9.99", 1)));

            state.commit(List.of(new LineChange(0, new int[] {0}, money("9.99"), false)), false);

            EvaluationResult result = state.toResult(List.of());
            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("9.99"));
            assertTotalsConsistent(result);
        }

        @Test
        void emptyCartProducesScaleTwoZeros() {
            EvaluationResult result = new CartState(cart()).toResult(List.of());

            assertThat(result.originalSubtotal()).isEqualTo(money("0.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("0.00"));
            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.itemizedBreakdown()).isEmpty();
            assertThat(result.auditTrail()).isEmpty();
        }
    }

    @Nested
    class Commit {

        @Test
        void itemLevelChangeWithPositiveAmountMarksLineItemDiscounted() {
            var state = twoLineCart();

            state.commit(List.of(new LineChange(0, new int[] {0, 1}, money("3.00"), false)), true);

            assertThat(unitPrices(state.line(0))).containsExactly(money("8.50"), money("8.50"));
            assertThat(state.line(0).isItemDiscounted()).isTrue();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
        }

        @Test
        void cartLevelChangeDoesNotMarkItemDiscounted() {
            var state = twoLineCart();

            state.commit(List.of(
                    new LineChange(0, new int[] {0, 1}, money("2.00"), false),
                    new LineChange(1, new int[] {0}, money("1.00"), false)), false);

            assertThat(state.currentSubtotal()).isEqualTo(money("22.00"));
            assertThat(state.line(0).isItemDiscounted()).isFalse();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
        }

        @Test
        void zeroAmountDoesNotMarkItemDiscounted() {
            var state = twoLineCart();

            state.commit(List.of(new LineChange(0, new int[] {0, 1}, money("0.00"), false)), true);

            assertThat(state.line(0).isItemDiscounted()).isFalse();
            assertThat(state.currentSubtotal()).isEqualTo(money("25.00"));
        }

        @Test
        void marksUnitsBundledWhenRequested() {
            var state = twoLineCart();

            state.commit(List.of(new LineChange(0, new int[] {1}, money("4.00"), true)), true);

            assertThat(state.line(0).isBundled(0)).isFalse();
            assertThat(state.line(0).isBundled(1)).isTrue();
            assertThat(unitPrices(state.line(0))).containsExactly(money("10.00"), money("6.00"));
            assertThat(state.line(0).nonBundledUnits()).containsExactly(0);
        }

        @Test
        void bundlingWithZeroSavingsStillBundlesButDoesNotMarkItemDiscounted() {
            var state = twoLineCart();

            state.commit(List.of(new LineChange(0, new int[] {0}, money("0.00"), true)), true);

            assertThat(state.line(0).isBundled(0)).isTrue();
            assertThat(state.line(0).isItemDiscounted()).isFalse();
        }

        @Test
        void emptyChangeListChangesNothing() {
            var state = twoLineCart();

            state.commit(List.of(), true);

            assertThat(state.currentSubtotal()).isEqualTo(money("25.00"));
        }

        @Test
        void invalidLaterChangeLeavesEarlierLinesUntouched() {
            var state = twoLineCart();
            var valid = new LineChange(0, new int[] {0, 1}, money("5.00"), true);
            var exceedsLine = new LineChange(1, new int[] {0}, money("5.01"), false);

            assertThatThrownBy(() -> state.commit(List.of(valid, exceedsLine), true))
                    .isInstanceOf(IllegalArgumentException.class);

            assertUnchanged(state);
        }

        @Test
        void rejectsInvalidChangesWithoutMutating() {
            var state = twoLineCart();
            var ok = new LineChange(0, new int[] {0}, money("1.00"), false);

            assertThatThrownBy(() -> state.commit(List.of(ok, new LineChange(2, new int[] {0}, money("1.00"), false)), true))
                    .as("line index out of range").isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> state.commit(List.of(ok, new LineChange(0, new int[] {1}, money("1.00"), false)), true))
                    .as("same line twice").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> state.commit(List.of(ok, new LineChange(1, new int[] {1}, money("1.00"), false)), true))
                    .as("unit index out of range").isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> state.commit(List.of(ok, new LineChange(1, new int[] {0, 0}, money("1.00"), false)), true))
                    .as("repeated unit index").isInstanceOf(IllegalArgumentException.class);

            assertUnchanged(state);
        }

        @Test
        void rejectsBundlingAlreadyBundledUnitWithoutMutating() {
            var state = twoLineCart();
            state.commit(List.of(new LineChange(1, new int[] {0}, money("0.00"), true)), true);

            var ok = new LineChange(0, new int[] {0}, money("1.00"), false);
            var rebundle = new LineChange(1, new int[] {0}, money("1.00"), true);
            assertThatThrownBy(() -> state.commit(List.of(ok, rebundle), true))
                    .isInstanceOf(IllegalArgumentException.class);

            assertUnchanged(state);
        }

        private CartState twoLineCart() {
            return new CartState(cart(
                    item("SKU-A", "BOOKS", "10.00", 2),
                    item("SKU-B", "TOYS", "5.00", 1)));
        }

        private void assertUnchanged(CartState state) {
            assertThat(unitPrices(state.line(0))).containsExactly(money("10.00"), money("10.00"));
            assertThat(unitPrices(state.line(1))).containsExactly(money("5.00"));
            assertThat(state.line(0).isItemDiscounted()).isFalse();
            assertThat(state.line(0).isBundled(0)).isFalse();
            assertThat(state.line(0).isBundled(1)).isFalse();
        }
    }

    @Nested
    class LineChangeRecord {

        @Test
        void normalizesAmountToScaleTwo() {
            assertThat(new LineChange(0, new int[] {0}, money("5"), false).amount()).isEqualTo(money("5.00"));
            assertThat(new LineChange(0, new int[] {0}, money("5.000"), false).amount()).isEqualTo(money("5.00"));
        }

        @Test
        void rejectsInvalidValues() {
            assertThatThrownBy(() -> new LineChange(-1, new int[] {0}, money("1.00"), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LineChange(0, new int[] {0}, money("-0.01"), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LineChange(0, new int[] {0}, money("0.005"), false))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LineChange(0, null, money("1.00"), false))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void unitIndexesAreDefensivelyCopied() {
            int[] units = {0, 1};
            var change = new LineChange(0, units, money("1.00"), false);

            units[0] = 9;
            change.unitIndexes()[1] = 9;

            assertThat(change.unitIndexes()).containsExactly(0, 1);
        }
    }

    private static AuditEntry audit(String ruleId, RuleType type, String amount) {
        return new AuditEntry(ruleId, ruleId, type, RuleStatus.APPLIED, money(amount), "Applied: test");
    }
}
