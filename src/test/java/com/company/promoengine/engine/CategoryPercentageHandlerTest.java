package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.cart;
import static com.company.promoengine.engine.EngineTestSupport.categoryRule;
import static com.company.promoengine.engine.EngineTestSupport.evaluate;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code PERCENTAGE_OFF_CATEGORY}. Most tests run through the engine (which also checks the
 * accounting invariants); a few call the handler directly to see unit-level detail or to
 * set up bundled units, which no rule can create yet.
 */
class CategoryPercentageHandlerTest {

    private final CategoryPercentageHandler handler = new CategoryPercentageHandler();

    @Nested
    class Matching {

        @Test
        void discountsOnlyTheMatchingCategory() {
            // Same data as public fixture 01.
            var result = evaluate(
                    cart(item("SKU-SHOES", "FOOTWEAR", "2000.00", 1), item("SKU-SOCKS", "ACCESSORIES", "500.00", 1)),
                    categoryRule("PROMO_FOOT", "FOOTWEAR", "10").name("Footwear 10% off").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("200.00"), money("0.00"));
            assertThat(result.originalSubtotal()).isEqualTo(money("2500.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("200.00"));
            assertThat(result.finalTotal()).isEqualTo(money("2300.00"));
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Applied: 10% off category FOOTWEAR");
        }

        @Test
        void categoryMatchIsCaseSensitive() {
            var result = evaluate(cart(item("SKU-SHOES", "FOOTWEAR", "2000.00", 1)),
                    categoryRule("PROMO_FOOT", "footwear", "10").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no items in category footwear");
        }

        @Test
        void discountsEveryMatchingLineInRequestOrder() {
            var state = new CartState(cart(
                    item("SKU-A", "BOOKS", "100.00", 1),
                    item("SKU-T", "TOYS", "70.00", 1),
                    item("SKU-B", "BOOKS", "50.00", 1)));

            var apply = (RuleOutcome.Apply) handler.evaluate(categoryRule("R", "BOOKS", "10").build(), state);

            assertThat(apply.changes()).extracting(LineChange::lineIndex).containsExactly(0, 2);
            assertThat(apply.changes()).extracting(LineChange::amount).containsExactly(money("10.00"), money("5.00"));
            assertThat(apply.totalDiscount()).isEqualTo(money("15.00"));
        }
    }

    @Nested
    class Calculation {

        @Test
        void repeatedPercentagesUseTheRunningPrice() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "100.00", 1)),
                    categoryRule("R1", "BOOKS", "10").priority(1).build(),
                    categoryRule("R2", "BOOKS", "10").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("10.00"), money("9.00"));
            assertThat(result.finalTotal()).isEqualTo(money("81.00"));
        }

        @Test
        void eachLineIsRoundedOnItsOwn() {
            // README example: two 1.25 lines at 15% -> 0.1875 each -> 0.19 + 0.19
            var readme = evaluate(cart(item("SKU-A", "SNACKS", "1.25", 1), item("SKU-B", "SNACKS", "1.25", 1)),
                    categoryRule("R", "SNACKS", "15").build());
            assertThat(readme.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.19"), money("0.19"));

            // Three 0.10 lines at 15%: 0.015 each -> 0.02 x 3 = 0.06 (the combined 0.30 would give 0.05)
            var perLine = evaluate(cart(
                            item("SKU-A", "SNACKS", "0.10", 1),
                            item("SKU-B", "SNACKS", "0.10", 1),
                            item("SKU-C", "SNACKS", "0.10", 1)),
                    categoryRule("R", "SNACKS", "15").build());
            assertThat(perLine.totalDiscount()).isEqualTo(money("0.06"));
        }

        @Test
        void fractionalPercentage() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "80.00", 1)),
                    categoryRule("R", "BOOKS", "12.5").build());

            assertThat(result.totalDiscount()).isEqualTo(money("10.00"));
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Applied: 12.5% off category BOOKS");
        }

        @Test
        void lineDiscountIsSpreadOverItsUnits() {
            var state = new CartState(cart(item("SKU-A", "BOOKS", "33.33", 3)));

            // 10% of 99.99 = 9.999 -> 10.00, split 3.34 / 3.33 / 3.33 over the equal units
            commit(state, handler.evaluate(categoryRule("R", "BOOKS", "10").build(), state));

            assertThat(unitPrices(state.line(0))).containsExactly(money("29.99"), money("30.00"), money("30.00"));
            assertThat(state.line(0).isItemDiscounted()).isTrue();
        }

        @Test
        void hundredPercentStopsAtZero() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "49.99", 2)),
                    categoryRule("R", "BOOKS", "100").build());

            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("99.98"));
        }

        @Test
        void zeroDiscountIsSkipped() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "0.04", 1)),
                    categoryRule("R", "BOOKS", "10").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: computed discount is 0.00");
        }

        @Test
        void evaluateDoesNotChangeTheCart() {
            var state = new CartState(cart(item("SKU-A", "BOOKS", "10.00", 2)));

            handler.evaluate(categoryRule("R", "BOOKS", "50").build(), state);

            assertThat(unitPrices(state.line(0))).containsExactly(money("10.00"), money("10.00"));
            assertThat(state.line(0).isItemDiscounted()).isFalse();
        }
    }

    @Nested
    class Cap {

        private final List<CartItem> electronics = List.of(
                item("SKU-TV", "ELECTRONICS", "100.00", 1),
                item("SKU-CABLE", "ELECTRONICS", "50.00", 1));

        @Test
        void capIsSplitByUncappedLineDiscounts() {
            // Uncapped 10.00 + 5.00 = 15.00, capped to 10.00 and split 2:1
            var result = evaluate(cart(electronics),
                    categoryRule("R", "ELECTRONICS", "10").maxDiscount("10.00").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("6.67"), money("3.33"));
            assertThat(result.auditTrail().get(0).discountAmount()).isEqualTo(money("10.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 10% off category ELECTRONICS (capped at 10.00)");
        }

        @Test
        void capAboveOrEqualToTotalChangesNothing() {
            for (String cap : List.of("20.00", "15.00")) {
                var result = evaluate(cart(electronics),
                        categoryRule("R", "ELECTRONICS", "10").maxDiscount(cap).build());

                assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                        .containsExactly(money("10.00"), money("5.00"));
                assertThat(result.auditTrail().get(0).reason()).doesNotContain("capped");
            }
        }

        @Test
        void lineWhoseCappedShareIsZeroIsNotItemDiscounted() {
            var state = new CartState(cart(electronics));

            // Cap 0.01 over weights 10.00 : 5.00 -> 0.01 and 0.00
            var outcome = handler.evaluate(categoryRule("R", "ELECTRONICS", "10").maxDiscount("0.01").build(), state);
            var apply = (RuleOutcome.Apply) outcome;
            assertThat(apply.changes()).extracting(LineChange::lineIndex).containsExactly(0);

            commit(state, outcome);
            assertThat(state.line(0).isItemDiscounted()).isTrue();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
            assertThat(state.line(1).currentTotal()).isEqualTo(money("50.00"));
        }
    }

    @Nested
    class BundledUnits {

        @Test
        void onlyNonBundledUnitsAreDiscounted() {
            var state = new CartState(cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 3)));
            // Simulate an earlier bundle that consumed units 0 and 1.
            state.commit(List.of(new LineChange(0, new int[] {0, 1}, money("320.00"), true)), true);

            var outcome = handler.evaluate(categoryRule("R", "LAPTOP", "10").build(), state);
            var change = ((RuleOutcome.Apply) outcome).changes().get(0);
            assertThat(change.unitIndexes()).containsExactly(2);
            assertThat(change.amount()).isEqualTo(money("80.00"));

            commit(state, outcome);
            assertThat(unitPrices(state.line(0))).containsExactly(money("640.00"), money("640.00"), money("720.00"));
        }

        @Test
        void fullyBundledCategoryIsSkipped() {
            var state = new CartState(cart(item("SKU-BAG", "ACCESSORIES", "50.00", 2)));
            state.commit(List.of(new LineChange(0, new int[] {0, 1}, money("20.00"), true)), true);

            var outcome = handler.evaluate(categoryRule("R", "ACCESSORIES", "10").build(), state);

            assertThat(outcome).isEqualTo(new RuleOutcome.Skip("all units in category ACCESSORIES are already bundled"));
        }
    }

    @Nested
    class Validation {

        @Test
        void acceptsBoundaryValues() {
            assertThat(handler.validate(categoryRule("R", "BOOKS", "100").build())).isEmpty();
            assertThat(handler.validate(categoryRule("R", "BOOKS", "0.01").maxDiscount("0.01").build())).isEmpty();
        }

        @Test
        void reportsTheProblem() {
            assertThat(handler.validate(categoryRule("R", "BOOKS", "101").build()))
                    .contains("discountConfig.percentage must be > 0 and <= 100");
            assertThat(handler.validate(categoryRule("R", " ", "10").build()))
                    .contains("conditions.category is required");
            assertThat(handler.validate(categoryRule("R", "BOOKS", "10").maxDiscount("0").build()))
                    .contains("discountConfig.maxDiscountAmount must be > 0");
        }
    }

    /** Commits an Apply outcome the way the engine does for this item-level rule. */
    private void commit(CartState state, RuleOutcome outcome) {
        state.commit(((RuleOutcome.Apply) outcome).changes(), handler.itemLevel());
    }
}
