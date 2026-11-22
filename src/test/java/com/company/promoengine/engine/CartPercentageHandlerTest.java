package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.amountTier;
import static com.company.promoengine.engine.EngineTestSupport.bundleRule;
import static com.company.promoengine.engine.EngineTestSupport.buyXGetCheapestFreeRule;
import static com.company.promoengine.engine.EngineTestSupport.cart;
import static com.company.promoengine.engine.EngineTestSupport.cartPercentageRule;
import static com.company.promoengine.engine.EngineTestSupport.categoryRule;
import static com.company.promoengine.engine.EngineTestSupport.component;
import static com.company.promoengine.engine.EngineTestSupport.evaluate;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.tieredRule;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code CART_PERCENTAGE_OFF}. Engine-level tests also check the accounting invariants via
 * {@link EngineTestSupport#evaluate}; a few handler-level tests inspect unit detail and flags.
 */
class CartPercentageHandlerTest {

    private final CartPercentageHandler handler = new CartPercentageHandler();

    private static Cart singleLine(String price) {
        return cart(item("SKU-ORDER", "GENERAL", price, 1));
    }

    /** Line A (BOOKS 100.00) and line B (TOYS 100.00). */
    private static Cart twoLines() {
        return cart(item("SKU-A", "BOOKS", "100.00", 1), item("SKU-B", "TOYS", "100.00", 1));
    }

    private static Cart threeLines(String price) {
        return cart(item("SKU-A", "X", price, 1), item("SKU-B", "X", price, 1), item("SKU-C", "X", price, 1));
    }

    @Nested
    class GrantAndRounding {

        @Test
        void basicPercentageOfTheCart() {
            var result = evaluate(singleLine("1000.00"), cartPercentageRule("PROMO_CART", "10").build());

            assertThat(result.totalDiscount()).isEqualTo(money("100.00"));
            assertThat(result.finalTotal()).isEqualTo(money("900.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 10% (100.00) off cart (running subtotal 1000.00)");
        }

        @Test
        void percentageIsRoundedOnceOnTheCartNotPerLine() {
            // Base 0.30 * 15% = 0.045 -> 0.05, split 0.02 / 0.02 / 0.01.
            var cartRule = evaluate(threeLines("0.10"), cartPercentageRule("R", "15").build());
            assertThat(cartRule.totalDiscount()).isEqualTo(money("0.05"));
            assertThat(cartRule.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.02"), money("0.02"), money("0.01"));

            // Contrast: the category rule rounds per line, 3 x 0.015 -> 3 x 0.02 = 0.06.
            var perLine = evaluate(threeLines("0.10"), categoryRule("R", "X", "15").build());
            assertThat(perLine.totalDiscount()).isEqualTo(money("0.06"));
        }

        @Test
        void halfCentRoundsUp() {
            // 0.25 * 50% = 0.125 -> 0.13
            var result = evaluate(singleLine("0.25"), cartPercentageRule("R", "50").build());

            assertThat(result.totalDiscount()).isEqualTo(money("0.13"));
        }

        @Test
        void fractionalPercentage() {
            // 99.99 * 12.5% = 12.49875 -> 12.50
            var result = evaluate(singleLine("99.99"), cartPercentageRule("R", "12.5").build());

            assertThat(result.totalDiscount()).isEqualTo(money("12.50"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 12.5% (12.50) off cart (running subtotal 99.99)");
        }

        @Test
        void hundredPercentClearsTheCart() {
            var result = evaluate(cart(item("SKU-A", "X", "30.00", 1), item("SKU-B", "X", "20.00", 1)),
                    cartPercentageRule("R", "100").build());

            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("50.00"));
        }

        @Test
        void grantRoundingToZeroIsSkipped() {
            // 0.04 * 10% = 0.004 -> 0.00
            var result = evaluate(singleLine("0.04"), cartPercentageRule("R", "10").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: computed discount is 0.00");
        }
    }

    @Nested
    class Allocation {

        @Test
        void pennyRemainderGoesToTheEarlierLine() {
            // Base 9.99 * 10% = 0.999 -> 1.00, split over three equal lines.
            var result = evaluate(threeLines("3.33"), cartPercentageRule("R", "10").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.34"), money("0.33"), money("0.33"));
        }

        @Test
        void grantIsWeightedByCurrentLinePrice() {
            var result = evaluate(cart(item("SKU-A", "X", "100.00", 1), item("SKU-B", "X", "50.00", 1)),
                    cartPercentageRule("R", "20").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("20.00"), money("10.00"));
        }

        @Test
        void bundledUnitsShareTheCartDiscount() {
            // Bundle leaves 640 / 120 / 40 (all units bundled); 10% of 800 = 80 -> 64 / 12 / 4.
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                            item("SKU-MOUSE", "ACCESSORIES", "150.00", 1),
                            item("SKU-BAG", "ACCESSORIES", "50.00", 1)),
                    bundleRule("BUNDLE", "800.00",
                            component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1), component("SKU-BAG", 1))
                            .priority(1).build(),
                    cartPercentageRule("CART", "10").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("200.00"), money("80.00"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("576.00"), money("108.00"), money("36.00"));
        }
    }

    @Nested
    class RunningSubtotalAndStacking {

        @Test
        void sequentialCartPercentagesUseTheRunningSubtotal() {
            var result = evaluate(singleLine("100.00"),
                    cartPercentageRule("R1", "10").priority(1).build(),
                    cartPercentageRule("R2", "10").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("10.00"), money("9.00"));
            assertThat(result.finalTotal()).isEqualTo(money("81.00"));
        }

        @Test
        void stackableTrueOrNullIncludesItemDiscountedLines() {
            // Category takes 10.00 off A; base 190.00 -> 19.00, split 90:100 -> 9.00 / 10.00.
            for (Boolean stackable : new Boolean[] {Boolean.TRUE, null}) {
                var result = evaluate(twoLines(),
                        categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                        cartPercentageRule("CART", "10").stackable(stackable).priority(2).build());

                assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                        .containsExactly(money("10.00"), money("19.00"));
                assertThat(result.auditTrail().get(1).reason())
                        .isEqualTo("Applied: 10% (19.00) off cart (running subtotal 190.00)");
                assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                        .containsExactly(money("19.00"), money("10.00"));
            }
        }

        @Test
        void nonStackableExcludesItemDiscountedLines() {
            // Only line B (100.00) is eligible -> 10.00, all on B.
            var result = evaluate(twoLines(),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    cartPercentageRule("CART", "10").stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% (10.00) off cart (eligible subtotal 100.00)");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("10.00"), money("10.00"));
        }

        @Test
        void nonStackableWithEveryLineItemDiscountedIsSkipped() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "100.00", 1)),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    cartPercentageRule("CART", "10").stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Skipped: no eligible subtotal (item-discounted lines excluded)");
        }

        @Test
        void cartRulesDoNotMakeLinesItemDiscounted() {
            // R1 lowers both lines to 90.00 without marking them, so the non-stackable R2 still sees both.
            var result = evaluate(twoLines(),
                    cartPercentageRule("R1", "10").priority(1).build(),
                    cartPercentageRule("R2", "10").stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% (18.00) off cart (eligible subtotal 180.00)");
        }

        @Test
        void commitLeavesItemDiscountedFlagsUntouched() {
            var state = new CartState(twoLines());

            var apply = (RuleOutcome.Apply) handler.evaluate(cartPercentageRule("R", "10").build(), state);
            state.commit(apply.changes(), handler.itemLevel());

            assertThat(handler.itemLevel()).isFalse();
            assertThat(state.line(0).isItemDiscounted()).isFalse();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
            assertThat(state.currentSubtotal()).isEqualTo(money("180.00"));
        }

        @Test
        void earlierCartPercentageCanDowngradeATier() {
            // 10% off 2100.00 leaves 1890.00, below the 2000 tier -> the 1000 tier (50.00).
            var result = evaluate(singleLine("2100.00"),
                    cartPercentageRule("CART", "10").priority(1).build(),
                    tieredRule("TIER", amountTier("2000.00", "200.00"), amountTier("1000.00", "50.00"))
                            .priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("210.00"), money("50.00"));
        }

        @Test
        void cartPercentageAfterATierUsesTheReducedSubtotal() {
            // Tier takes 200.00 -> 1900.00, then 10% -> 190.00.
            var result = evaluate(singleLine("2100.00"),
                    tieredRule("TIER", amountTier("2000.00", "200.00"), amountTier("1000.00", "50.00"))
                            .priority(1).build(),
                    cartPercentageRule("CART", "10").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("200.00"), money("190.00"));
            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% (190.00) off cart (running subtotal 1900.00)");
        }
    }

    @Nested
    class InteractionsWithItemRules {

        private final Cart mugsAndBook =
                cart(item("SKU-MUG", "KITCHEN", "10.00", 3), item("SKU-BOOK", "BOOKS", "100.00", 1));

        @Test
        void afterBuyXGetYTheFreedLineSharesByItsCurrentTotal() {
            // BXGY frees one mug: mugs 20.00, book 100.00; 10% of 120.00 = 12.00 -> 2.00 / 10.00.
            var result = evaluate(mugsAndBook,
                    buyXGetCheapestFreeRule("BXGY", 2, 1, "SKU-MUG").priority(1).build(),
                    cartPercentageRule("CART", "10").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("10.00"), money("12.00"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("18.00"), money("90.00"));
        }

        @Test
        void afterBuyXGetYTheFreedUnitTakesNoShare() {
            var state = new CartState(mugsAndBook);
            var bxgy = new BuyXGetCheapestFreeHandler();
            var bxgyApply = (RuleOutcome.Apply) bxgy.evaluate(
                    buyXGetCheapestFreeRule("BXGY", 2, 1, "SKU-MUG").build(), state);
            state.commit(bxgyApply.changes(), bxgy.itemLevel());

            var apply = (RuleOutcome.Apply) handler.evaluate(cartPercentageRule("CART", "10").build(), state);
            state.commit(apply.changes(), handler.itemLevel());

            assertThat(unitPrices(state.line(0))).containsExactly(money("0.00"), money("9.00"), money("9.00"));
        }

        @Test
        void nonStackableExcludesTheBuyXGetYLine() {
            var result = evaluate(mugsAndBook,
                    buyXGetCheapestFreeRule("BXGY", 2, 1, "SKU-MUG").priority(1).build(),
                    cartPercentageRule("CART", "10").stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% (10.00) off cart (eligible subtotal 100.00)");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("20.00"), money("90.00"));
        }

        @Test
        void bundledLinesAndStackability() {
            // Bundle laptop + gift at 700: laptop share 100.00 (item-discounted), gift share 0.00
            // (bundled, not item-discounted). The book is not in the bundle.
            Cart cart = cart(
                    item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                    item("SKU-GIFT", "GIFTS", "0.00", 1),
                    item("SKU-BOOK", "BOOKS", "100.00", 1));
            PromotionRule bundle = bundleRule("BUNDLE", "700.00",
                    component("SKU-LAPTOP", 1), component("SKU-GIFT", 1)).priority(1).build();

            // Stackable: bundled laptop included; 10% of 800.00 = 80.00 -> 70.00 / 0.00 / 10.00.
            var stacking = evaluate(cart, bundle, cartPercentageRule("CART", "10").priority(2).build());
            assertThat(stacking.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("630.00"), money("0.00"), money("90.00"));

            // Not stackable: laptop excluded; gift (0.00) and book remain -> 10.00 all on the book.
            var nonStacking = evaluate(cart, bundle,
                    cartPercentageRule("CART", "10").stackable(false).priority(2).build());
            assertThat(nonStacking.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% (10.00) off cart (eligible subtotal 100.00)");
            assertThat(nonStacking.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("700.00"), money("0.00"), money("90.00"));
        }
    }

    @Nested
    class SkipsAndGroups {

        @Test
        void emptyCartIsSkipped() {
            var result = evaluate(cart(), cartPercentageRule("R", "10").build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no eligible subtotal");
        }

        @Test
        void allZeroPricedLinesAreSkipped() {
            var result = evaluate(cart(item("SKU-GIFT", "GIFTS", "0.00", 2)), cartPercentageRule("R", "10").build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no eligible subtotal");
        }

        @Test
        void appliedCartPercentageClaimsTheGroup() {
            var result = evaluate(singleLine("2500.00"),
                    cartPercentageRule("CART", "10").group("CART_DEALS").priority(1).build(),
                    tieredRule("TIER", amountTier("0.00", "50.00")).group("CART_DEALS").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.APPLIED, RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Skipped: exclusive group CART_DEALS already claimed by an earlier rule");
        }

        @Test
        void skippedCartPercentageLeavesTheGroupUnclaimed() {
            // The non-stackable cart rule has an empty base, so the tier in the same group still applies.
            var result = evaluate(cart(item("SKU-A", "BOOKS", "100.00", 1)),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    cartPercentageRule("CART", "10").stackable(false).group("CART_DEALS").priority(2).build(),
                    tieredRule("TIER", amountTier("0.00", "5.00")).group("CART_DEALS").priority(3).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.APPLIED, RuleStatus.SKIPPED, RuleStatus.APPLIED);
        }

        @Test
        void fieldsThatAreNotInputsAreIgnored() {
            var result = evaluate(singleLine("1000.00"), cartPercentageRule("R", "10")
                    .amount("999.00").maxDiscount("1.00").tiers(amountTier("0.00", "500.00")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("100.00"));
        }

        @Test
        void evaluateDoesNotChangeTheCart() {
            var state = new CartState(twoLines());

            handler.evaluate(cartPercentageRule("R", "50").build(), state);

            assertThat(unitPrices(state.line(0))).containsExactly(money("100.00"));
            assertThat(unitPrices(state.line(1))).containsExactly(money("100.00"));
        }
    }

    @Nested
    class Validation {

        static Stream<Arguments> invalidRules() {
            return Stream.of(
                    Arguments.of("discountConfig missing",
                            cartPercentageRule("R", "10").withoutDiscountConfig().build(), "discountConfig is missing"),
                    Arguments.of("percentage missing", cartPercentageRule("R", null).build(),
                            "discountConfig.percentage must be > 0 and <= 100"),
                    Arguments.of("percentage zero", cartPercentageRule("R", "0").build(),
                            "discountConfig.percentage must be > 0 and <= 100"),
                    Arguments.of("percentage negative", cartPercentageRule("R", "-5").build(),
                            "discountConfig.percentage must be > 0 and <= 100"),
                    Arguments.of("percentage above 100", cartPercentageRule("R", "100.01").build(),
                            "discountConfig.percentage must be > 0 and <= 100"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRules")
        void invalidConfigurationIsSkipped(String label, PromotionRule rule, String problem) {
            assertThat(handler.validate(rule)).contains(problem);

            var result = evaluate(singleLine("1000.00"), rule);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: invalid configuration: " + problem);
            assertThat(result.finalTotal()).isEqualTo(money("1000.00"));
        }

        @Test
        void boundaryPercentagesAreValid() {
            assertThat(handler.validate(cartPercentageRule("R", "0.01").build())).isEmpty();
            assertThat(handler.validate(cartPercentageRule("R", "100").build())).isEmpty();
        }

        @Test
        void conditionsAreNotRequired() {
            var rule = cartPercentageRule("R", "10").withoutConditions().build();

            assertThat(handler.validate(rule)).isEmpty();
            assertThat(evaluate(singleLine("100.00"), rule).totalDiscount()).isEqualTo(money("10.00"));
        }
    }
}
