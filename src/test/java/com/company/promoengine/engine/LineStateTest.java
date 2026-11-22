package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LineState}. Cart-level behaviour lives in {@link CartStateTest}.
 * Assertions use {@link BigDecimal#equals}, which also checks scale 2.
 */
class LineStateTest {

    @Nested
    class Initialization {

        @Test
        void everyUnitStartsAtTheInputUnitPrice() {
            var item = item("SKU-A", "BOOKS", "33.33", 3);
            var line = new LineState(item);

            assertThat(line.item()).isSameAs(item);
            assertThat(line.quantity()).isEqualTo(3);
            for (int unit = 0; unit < 3; unit++) {
                assertThat(line.currentUnitPrice(unit)).isEqualTo(money("33.33"));
                assertThat(line.isBundled(unit)).isFalse();
            }
            assertThat(line.isItemDiscounted()).isFalse();
            assertThat(line.originalUnitPrice()).isEqualTo(money("33.33"));
            assertThat(line.originalTotal()).isEqualTo(money("99.99"));
            assertThat(line.currentTotal()).isEqualTo(money("99.99"));
            assertThat(line.discount()).isEqualTo(money("0.00"));
        }

        @Test
        void unscaledUnitPriceIsNormalizedToScaleTwo() {
            var line = new LineState(item("SKU-MUG", "KITCHEN", "120.0", 3));

            assertThat(line.originalUnitPrice()).isEqualTo(money("120.00"));
            assertThat(line.currentUnitPrice(0)).isEqualTo(money("120.00"));
            assertThat(line.originalTotal()).isEqualTo(money("360.00"));
        }

        @Test
        void rejectsNegativeUnitPrice() {
            assertThatThrownBy(() -> new LineState(item("SKU-A", "BOOKS", "-0.01", 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TotalsAndEligibleBase {

        @Test
        void withNothingBundledEveryUnitIsEligible() {
            var line = new LineState(item("SKU-A", "BOOKS", "10.00", 3));

            assertThat(line.allUnits()).containsExactly(0, 1, 2);
            assertThat(line.nonBundledUnits()).containsExactly(0, 1, 2);
            assertThat(line.nonBundledTotal()).isEqualTo(money("30.00"));
            assertThat(line.currentTotalOf(new int[] {0, 2})).isEqualTo(money("20.00"));
        }

        @Test
        void unitIndexesAreBoundsChecked() {
            var line = new LineState(item("SKU-A", "BOOKS", "10.00", 2));

            assertThatThrownBy(() -> line.currentUnitPrice(2)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> line.isBundled(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> line.currentTotalOf(new int[] {0, 5}))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    class Reduce {

        @Test
        void splitsAmountAcrossUnitsWithPennyAllocation() {
            var line = new LineState(item("SKU-A", "BOOKS", "10.00", 3));

            line.reduce(line.allUnits(), money("10.00")); // shares 3.34, 3.33, 3.33

            assertThat(unitPrices(line)).containsExactly(money("6.66"), money("6.67"), money("6.67"));
            assertThat(line.currentTotal()).isEqualTo(money("20.00"));
            assertThat(line.discount()).isEqualTo(money("10.00"));
        }

        @Test
        void touchesOnlyTheSelectedUnits() {
            var line = new LineState(item("SKU-LAPTOP", "LAPTOP", "800.00", 3));

            line.reduce(new int[] {2}, money("80.00"));

            assertThat(unitPrices(line)).containsExactly(money("800.00"), money("800.00"), money("720.00"));
        }

        @Test
        void splitIsWeightedByCurrentUnitPrice() {
            var line = new LineState(item("SKU-A", "BOOKS", "10.00", 3));
            line.reduce(new int[] {0}, money("5.00")); // units: 5.00, 10.00, 10.00

            line.reduce(line.allUnits(), money("5.00")); // weights 5:10:10 -> 1.00, 2.00, 2.00

            assertThat(unitPrices(line)).containsExactly(money("4.00"), money("8.00"), money("8.00"));
        }

        @Test
        void reducingByTheFullPriceLandsExactlyOnZero() {
            var line = new LineState(item("SKU-MUG", "KITCHEN", "120.00", 3));

            line.reduce(new int[] {0}, money("120.00"));

            assertThat(line.currentUnitPrice(0)).isEqualTo(money("0.00"));
            assertThat(line.currentTotal()).isEqualTo(money("240.00"));
        }

        @Test
        void zeroAmountChangesNothing() {
            var line = new LineState(item("SKU-A", "BOOKS", "10.00", 2));

            line.reduce(line.allUnits(), money("0.00"));

            assertThat(unitPrices(line)).containsExactly(money("10.00"), money("10.00"));
        }

        @Test
        void doesNotMarkTheLineItemDiscountedByItself() {
            var line = new LineState(item("SKU-A", "BOOKS", "10.00", 1));

            line.reduce(line.allUnits(), money("1.00"));

            assertThat(line.isItemDiscounted()).isFalse();
            line.markItemDiscounted();
            assertThat(line.isItemDiscounted()).isTrue();
        }

        @Test
        void zeroFloorRejectsAmountAboveSelectedUnitsAndLeavesStateUnchanged() {
            var line = new LineState(item("SKU-A", "BOOKS", "5.00", 2));

            assertThatThrownBy(() -> line.reduce(new int[] {0}, money("5.01")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(unitPrices(line)).containsExactly(money("5.00"), money("5.00"));

            line.reduce(line.allUnits(), money("10.00"));
            assertThat(unitPrices(line)).containsExactly(money("0.00"), money("0.00"));
            assertThat(line.currentTotal()).isEqualTo(money("0.00"));

            assertThatThrownBy(() -> line.reduce(line.allUnits(), money("0.01")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(line.currentTotal()).isEqualTo(money("0.00"));
        }

        @Test
        void rejectsNegativeAmount() {
            var line = new LineState(item("SKU-A", "BOOKS", "5.00", 1));

            assertThatThrownBy(() -> line.reduce(line.allUnits(), money("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsBadIndexesWithoutChangingState() {
            var line = new LineState(item("SKU-A", "BOOKS", "1.00", 2));

            assertThatThrownBy(() -> line.reduce(new int[] {2}, money("0.50")))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> line.reduce(new int[] {-1}, money("0.50")))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            // A repeated index would double-count unit 0's price and could push it below zero.
            assertThatThrownBy(() -> line.reduce(new int[] {0, 0}, money("2.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> line.reduce(null, money("0.50")))
                    .isInstanceOf(NullPointerException.class);

            assertThat(unitPrices(line)).containsExactly(money("1.00"), money("1.00"));
        }
    }

    @Nested
    class Bundling {

        @Test
        void bundledUnitsLeaveTheEligibleSetButLeftoversRemain() {
            var line = new LineState(item("SKU-LAPTOP", "LAPTOP", "800.00", 3));

            line.markBundled(new int[] {0, 1});

            assertThat(line.isBundled(0)).isTrue();
            assertThat(line.isBundled(1)).isTrue();
            assertThat(line.isBundled(2)).isFalse();
            assertThat(line.nonBundledUnits()).containsExactly(2);
            assertThat(line.nonBundledTotal()).isEqualTo(money("800.00"));
            // Bundling alone does not change any price.
            assertThat(line.currentTotal()).isEqualTo(money("2400.00"));
            assertThat(line.allUnits()).containsExactly(0, 1, 2);
        }

        @Test
        void bundledUnitsCanStillBeReduced() {
            var line = new LineState(item("SKU-LAPTOP", "LAPTOP", "800.00", 3));
            line.markBundled(new int[] {0, 1});

            line.reduce(new int[] {0, 1}, money("320.00"));

            assertThat(unitPrices(line)).containsExactly(money("640.00"), money("640.00"), money("800.00"));
            assertThat(line.nonBundledTotal()).isEqualTo(money("800.00"));
        }

        @Test
        void fullyBundledLineHasZeroEligibleBase() {
            var line = new LineState(item("SKU-BAG", "ACCESSORIES", "50.00", 2));

            line.markBundled(line.allUnits());

            assertThat(line.nonBundledUnits()).isEmpty();
            assertThat(line.nonBundledTotal()).isEqualTo(money("0.00"));
        }

        @Test
        void aUnitCannotBeBundledTwice() {
            var line = new LineState(item("SKU-BAG", "ACCESSORIES", "50.00", 2));
            line.markBundled(new int[] {0});

            assertThatThrownBy(() -> line.markBundled(new int[] {1, 0}))
                    .isInstanceOf(IllegalArgumentException.class);
            // Validation happens before any change, so unit 1 was not marked.
            assertThat(line.isBundled(1)).isFalse();
            assertThatThrownBy(() -> line.markBundled(new int[] {2}))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }
}
