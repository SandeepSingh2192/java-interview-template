package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.bundleRule;
import static com.company.promoengine.engine.EngineTestSupport.buyXGetCheapestFreeRule;
import static com.company.promoengine.engine.EngineTestSupport.cart;
import static com.company.promoengine.engine.EngineTestSupport.categoryRule;
import static com.company.promoengine.engine.EngineTestSupport.component;
import static com.company.promoengine.engine.EngineTestSupport.evaluate;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.BundleComponent;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code BUNDLE_FIXED_PRICE}. Engine-level tests also check the accounting invariants via
 * {@link EngineTestSupport#evaluate}; handler-level tests inspect the proposed units.
 */
class BundleFixedPriceHandlerTest {

    private static final BundleComponent LAPTOP = component("SKU-LAPTOP", 1);
    private static final BundleComponent MOUSE = component("SKU-MOUSE", 1);
    private static final BundleComponent BAG = component("SKU-BAG", 1);

    private final BundleFixedPriceHandler handler = new BundleFixedPriceHandler();

    /** Public fixture 03: laptop 800, mouse 150, bag 50 (component value 1000). */
    private static Cart deskCart() {
        return cart(
                item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                item("SKU-MOUSE", "ACCESSORIES", "150.00", 1),
                item("SKU-BAG", "ACCESSORIES", "50.00", 1));
    }

    /** README example: 3 laptops, 2 mice, 2 bags. */
    private static Cart readmeCart() {
        return cart(
                item("SKU-LAPTOP", "LAPTOP", "800.00", 3),
                item("SKU-MOUSE", "ACCESSORIES", "150.00", 2),
                item("SKU-BAG", "ACCESSORIES", "50.00", 2));
    }

    private static EngineTestSupport.RuleBuilder deskBundle(String bundlePrice) {
        return bundleRule("PROMO_DESK", bundlePrice, LAPTOP, MOUSE, BAG).name("Desk kit");
    }

    @Nested
    class Specification {

        @Test
        void singleCompleteBundle() {
            // Same data as public fixture 03.
            var result = evaluate(deskCart(), deskBundle("800.00").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("160.00"), money("30.00"), money("10.00"));
            assertThat(result.finalTotal()).isEqualTo(money("800.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 1 x bundle at 800.00 saving 200.00 on component value 1000.00");
        }

        @Test
        void readmeExampleBundlesTwoSetsAndLeavesOneLaptopForTheCategoryRule() {
            var result = evaluate(readmeCart(),
                    bundleRule("PROMO_DESK", "800.00", LAPTOP, MOUSE, BAG).priority(1).build(),
                    categoryRule("PROMO_LAPTOP", "LAPTOP", "10").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("400.00"), money("80.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 2 x bundle at 800.00 saving 400.00 on component value 2000.00");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("400.00"), money("60.00"), money("20.00"));
            assertThat(result.originalSubtotal()).isEqualTo(money("2800.00"));
            assertThat(result.finalTotal()).isEqualTo(money("2320.00"));
        }

        @Test
        void consumesLowestIndexUnitsAndMarksThemBundled() {
            var state = new CartState(readmeCart());

            var apply = (RuleOutcome.Apply) handler.evaluate(
                    bundleRule("R", "800.00", LAPTOP, MOUSE, BAG).build(), state);

            assertThat(apply.changes()).extracting(LineChange::lineIndex).containsExactly(0, 1, 2);
            assertThat(apply.changes()).allSatisfy(change -> {
                assertThat(change.unitIndexes()).containsExactly(0, 1);
                assertThat(change.markBundled()).isTrue();
            });
            assertThat(apply.changes()).extracting(LineChange::amount)
                    .containsExactly(money("320.00"), money("60.00"), money("20.00"));

            state.commit(apply.changes(), handler.itemLevel());
            assertThat(state.line(0).nonBundledUnits()).containsExactly(2);
            assertThat(unitPrices(state.line(0))).containsExactly(money("640.00"), money("640.00"), money("800.00"));
            assertThat(state.line(1).nonBundledUnits()).isEmpty();
        }

        @Test
        void componentQuantityAboveOne() {
            // laptop x1 + mouse x2; cart 2 laptops, 3 mice -> 1 set (mice limit it).
            // Value 800 + 300 = 1100, price 1000 -> saving 100 split 800:300 -> 72.73 / 27.27.
            var state = new CartState(cart(
                    item("SKU-LAPTOP", "LAPTOP", "800.00", 2),
                    item("SKU-MOUSE", "ACCESSORIES", "150.00", 3)));

            var apply = (RuleOutcome.Apply) handler.evaluate(
                    bundleRule("R", "1000.00", LAPTOP, component("SKU-MOUSE", 2)).build(), state);

            assertThat(apply.changes().get(0).unitIndexes()).containsExactly(0);
            assertThat(apply.changes().get(1).unitIndexes()).containsExactly(0, 1);
            assertThat(apply.changes()).extracting(LineChange::amount)
                    .containsExactly(money("72.73"), money("27.27"));
            assertThat(apply.detail()).isEqualTo("1 x bundle at 1000.00 saving 100.00 on component value 1100.00");

            state.commit(apply.changes(), handler.itemLevel());
            assertThat(state.line(0).nonBundledUnits()).as("leftover laptop").containsExactly(1);
            assertThat(state.line(1).nonBundledUnits()).as("leftover mouse").containsExactly(2);
        }
    }

    @Nested
    class SelectionAcrossLines {

        @Test
        void sameSkuOnSeveralLinesIsPooled() {
            // Two single-mouse lines together satisfy mouse x2.
            var result = evaluate(
                    cart(item("SKU-MOUSE", "ACCESSORIES", "150.00", 1),
                            item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                            item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)),
                    bundleRule("R", "900.00", LAPTOP, component("SKU-MOUSE", 2)).build());

            // Weights in line order 150 : 800 : 150 for a saving of 200.00
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("27.27"), money("145.46"), money("27.27"));
        }

        @Test
        void earlierLinesAreConsumedFirst() {
            var state = new CartState(cart(
                    item("SKU-MOUSE", "ACCESSORIES", "150.00", 2),
                    item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                    item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)));

            var apply = (RuleOutcome.Apply) handler.evaluate(
                    bundleRule("R", "1000.00", LAPTOP, component("SKU-MOUSE", 2)).build(), state);

            assertThat(apply.changes()).extracting(LineChange::lineIndex).containsExactly(0, 1);
            assertThat(apply.changes().get(0).unitIndexes()).containsExactly(0, 1);
            state.commit(apply.changes(), handler.itemLevel());
            assertThat(state.line(2).isBundled(0)).as("third line untouched").isFalse();
        }

        @Test
        void componentValueUsesTheOriginalPriceOfTheUnitsActuallyConsumed() {
            // Mice at 100 (line 0) and 200 (line 1); only the earlier 100 mouse is consumed.
            var result = evaluate(
                    cart(item("SKU-MOUSE", "ACCESSORIES", "100.00", 1),
                            item("SKU-MOUSE", "ACCESSORIES", "200.00", 1),
                            item("SKU-LAPTOP", "LAPTOP", "800.00", 1)),
                    bundleRule("R", "850.00", MOUSE, LAPTOP).build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 1 x bundle at 850.00 saving 50.00 on component value 900.00");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("5.56"), money("0.00"), money("44.44"));
        }
    }

    @Nested
    class SavingsAndAllocation {

        private final Cart threeEqualLines = cart(
                item("SKU-A", "X", "1.00", 1),
                item("SKU-B", "X", "1.00", 1),
                item("SKU-C", "X", "1.00", 1));

        @Test
        void pennyRemainderGoesToTheEarlierLine() {
            var result = evaluate(threeEqualLines,
                    bundleRule("R", "2.00", component("SKU-A", 1), component("SKU-B", 1), component("SKU-C", 1)).build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.34"), money("0.33"), money("0.33"));
        }

        @Test
        void tieBreakFollowsCartLineOrderNotComponentOrder() {
            var result = evaluate(threeEqualLines,
                    bundleRule("R", "2.00", component("SKU-C", 1), component("SKU-B", 1), component("SKU-A", 1)).build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.34"), money("0.33"), money("0.33"));
        }

        @Test
        void zeroSavingsIsSkippedAndBundlesNothing() {
            var result = evaluate(deskCart(),
                    bundleRule("PROMO_DESK", "1000.00", LAPTOP, MOUSE, BAG).priority(1).build(),
                    categoryRule("PROMO_ACC", "ACCESSORIES", "10").priority(2).build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: computed discount is 0.00");
            // The units were not bundled, so the category rule still discounts them.
            assertThat(result.auditTrail().get(1).status()).isEqualTo(RuleStatus.APPLIED);
            assertThat(result.auditTrail().get(1).discountAmount()).isEqualTo(money("20.00"));
        }

        @Test
        void bundlePriceAboveComponentValueNeverRaisesPrices() {
            var result = evaluate(deskCart(), deskBundle("1200.00").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.finalTotal()).isEqualTo(money("1000.00"));
        }

        @Test
        void zeroBundlePriceMakesTheConsumedUnitsFree() {
            var result = evaluate(deskCart(), deskBundle("0.00").build());

            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 1 x bundle at 0.00 saving 1000.00 on component value 1000.00");
        }

        @Test
        void zeroPricedComponentIsBundledButNotItemDiscounted() {
            var state = new CartState(cart(
                    item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                    item("SKU-GIFT", "GIFTS", "0.00", 1)));

            var apply = (RuleOutcome.Apply) handler.evaluate(
                    bundleRule("R", "700.00", LAPTOP, component("SKU-GIFT", 1)).build(), state);
            assertThat(apply.changes()).extracting(LineChange::amount)
                    .containsExactly(money("100.00"), money("0.00"));

            state.commit(apply.changes(), handler.itemLevel());
            assertThat(state.line(1).isBundled(0)).isTrue();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
            assertThat(state.line(0).isItemDiscounted()).isTrue();
        }
    }

    @Nested
    class EarlierPromotions {

        @Test
        void shareIsClampedToTheCurrentValueOfDeeplyDiscountedUnits() {
            // Laptop is at 80.00 after 90% off; its literal share 160.00 is clamped to 80.00 and
            // the removed 80.00 is not redistributed: 80 + 30 + 10 = 120.00.
            var result = evaluate(deskCart(),
                    categoryRule("CAT", "LAPTOP", "90").priority(1).build(),
                    deskBundle("800.00").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("720.00"), money("120.00"));
            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 1 x bundle at 800.00 saving 120.00 on component value 1000.00");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("0.00"), money("120.00"), money("40.00"));
        }

        @Test
        void mildEarlierDiscountKeepsTheLiteralOriginalPriceSavings() {
            // Laptop at 720.00 after 10% off; the literal share 160.00 fits, so no clamp.
            var result = evaluate(deskCart(),
                    categoryRule("CAT", "LAPTOP", "10").priority(1).build(),
                    deskBundle("800.00").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("80.00"), money("200.00"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("560.00"), money("120.00"), money("40.00"));
        }

        @Test
        void unitFreedByBuyXGetYIsConsumedAndClampedToZero() {
            // BXGY frees laptop unit 0. The bundle consumes that 0.00 unit (lowest index), so the
            // laptop share (84.21 at original price) is clamped to 0.00; the mouse keeps 15.79.
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 3), item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)),
                    buyXGetCheapestFreeRule("BXGY", 2, 1, "SKU-LAPTOP").priority(1).build(),
                    bundleRule("BUNDLE", "850.00", LAPTOP, MOUSE).priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("800.00"), money("15.79"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("1600.00"), money("134.21"));
        }

        @Test
        void secondIdenticalBundleFindsNoCompleteSetInTheLeftovers() {
            var result = evaluate(readmeCart(),
                    bundleRule("R1", "800.00", LAPTOP, MOUSE, BAG).priority(1).build(),
                    bundleRule("R2", "800.00", LAPTOP, MOUSE, BAG).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Skipped: incomplete bundle: sku SKU-MOUSE needs 1, 0 available");
        }

        @Test
        void secondBundleCanUseLeftoverUnits() {
            // R1 laptop+mouse uses 2 laptops and both mice; R2 laptop+bag uses the leftover laptop.
            var result = evaluate(readmeCart(),
                    bundleRule("R1", "900.00", LAPTOP, MOUSE).priority(1).build(),
                    bundleRule("R2", "800.00", LAPTOP, BAG).priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::reason).containsExactly(
                    "Applied: 2 x bundle at 900.00 saving 100.00 on component value 1900.00",
                    "Applied: 1 x bundle at 800.00 saving 50.00 on component value 850.00");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("131.27"), money("15.79"), money("2.94"));
        }

        @Test
        void leftoverUnitsStayEligibleButBundledUnitsDoNot() {
            // Bundle consumes laptop unit 0 (now 757.89). BXGY then pools only units 1-3 at 800.00;
            // had the bundled unit been eligible, BXGY would have freed it as the cheapest.
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 4), item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)),
                    bundleRule("BUNDLE", "900.00", LAPTOP, MOUSE).priority(1).build(),
                    buyXGetCheapestFreeRule("BXGY", 2, 1, "SKU-LAPTOP").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("50.00"), money("800.00"));
            assertThat(result.itemizedBreakdown().get(0).finalExtendedPrice()).isEqualTo(money("2357.89"));
        }
    }

    @Nested
    class IncompleteBundles {

        @Test
        void missingSkuSkipsAndLeavesUnitsUsable() {
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1), item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)),
                    bundleRule("BUNDLE", "800.00", LAPTOP, MOUSE, BAG).priority(1).build(),
                    categoryRule("CAT", "LAPTOP", "10").priority(2).build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: incomplete bundle: sku SKU-BAG needs 1, 0 available");
            assertThat(result.auditTrail().get(1).discountAmount()).isEqualTo(money("80.00"));
        }

        @Test
        void notEnoughUnitsSkips() {
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1), item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)),
                    bundleRule("BUNDLE", "800.00", LAPTOP, component("SKU-MOUSE", 2)).build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: incomplete bundle: sku SKU-MOUSE needs 2, 1 available");
        }

        @Test
        void skuMatchIsCaseSensitive() {
            var result = evaluate(deskCart(),
                    bundleRule("BUNDLE", "800.00", component("sku-laptop", 1), MOUSE, BAG).build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: incomplete bundle: sku sku-laptop needs 1, 0 available");
        }

        @Test
        void hugeComponentQuantityDoesNotOverflow() {
            var result = evaluate(deskCart(),
                    bundleRule("BUNDLE", "800.00", component("SKU-LAPTOP", Integer.MAX_VALUE)).build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: incomplete bundle: sku SKU-LAPTOP needs 2147483647, 1 available");
        }

        @Test
        void evaluateDoesNotChangeTheCart() {
            var state = new CartState(readmeCart());

            handler.evaluate(bundleRule("R", "800.00", LAPTOP, MOUSE, BAG).build(), state);

            for (LineState line : state.lines()) {
                assertThat(line.nonBundledUnits()).hasSize(line.quantity());
                assertThat(line.currentTotal()).isEqualTo(line.originalTotal());
                assertThat(line.isItemDiscounted()).isFalse();
            }
        }
    }

    @Nested
    class Validation {

        static Stream<Arguments> invalidRules() {
            return Stream.of(
                    Arguments.of("components missing",
                            bundleRule("R", "800.00").components((BundleComponent[]) null).build(),
                            "conditions.components must not be empty"),
                    Arguments.of("components empty", bundleRule("R", "800.00").build(),
                            "conditions.components must not be empty"),
                    Arguments.of("blank sku", bundleRule("R", "800.00", LAPTOP, component(" ", 1)).build(),
                            "conditions.components entries must have a non-blank sku"),
                    Arguments.of("quantity zero", bundleRule("R", "800.00", component("SKU-LAPTOP", 0)).build(),
                            "conditions.components quantity must be >= 1 (sku SKU-LAPTOP)"),
                    Arguments.of("quantity negative", bundleRule("R", "800.00", component("SKU-LAPTOP", -1)).build(),
                            "conditions.components quantity must be >= 1 (sku SKU-LAPTOP)"),
                    Arguments.of("duplicate sku",
                            bundleRule("R", "800.00", LAPTOP, MOUSE, component("SKU-LAPTOP", 2)).build(),
                            "conditions.components lists sku SKU-LAPTOP more than once"),
                    Arguments.of("bundlePrice missing", bundleRule("R", null, LAPTOP, MOUSE).build(),
                            "discountConfig.bundlePrice must be >= 0"),
                    Arguments.of("bundlePrice negative", bundleRule("R", "-0.01", LAPTOP, MOUSE).build(),
                            "discountConfig.bundlePrice must be >= 0"),
                    Arguments.of("conditions missing",
                            bundleRule("R", "800.00", LAPTOP).withoutConditions().build(), "conditions are missing"),
                    Arguments.of("discountConfig missing",
                            bundleRule("R", "800.00", LAPTOP).withoutDiscountConfig().build(), "discountConfig is missing"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRules")
        void invalidConfigurationIsSkipped(String label, PromotionRule rule, String problem) {
            assertThat(handler.validate(rule)).contains(problem);

            var result = evaluate(deskCart(), rule);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: invalid configuration: " + problem);
            assertThat(result.finalTotal()).isEqualTo(money("1000.00"));
        }

        @Test
        void duplicateDetectionIsCaseSensitive() {
            assertThat(handler.validate(bundleRule("R", "800.00", component("SKU-A", 1), component("sku-a", 1)).build()))
                    .isEmpty();
        }

        @Test
        void zeroBundlePriceIsValid() {
            assertThat(handler.validate(bundleRule("R", "0.00", LAPTOP).build())).isEmpty();
        }
    }
}
