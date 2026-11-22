package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.buyXGetCheapestFreeRule;
import static com.company.promoengine.engine.EngineTestSupport.cart;
import static com.company.promoengine.engine.EngineTestSupport.categoryRule;
import static com.company.promoengine.engine.EngineTestSupport.evaluate;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.engine.RuleOutcome.LineChange;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code BUY_X_GET_CHEAPEST_FREE}. Engine-level tests also check the accounting invariants
 * via {@link EngineTestSupport#evaluate}; handler-level tests inspect unit detail or use
 * hand-bundled units (no bundle rule exists yet).
 */
class BuyXGetCheapestFreeHandlerTest {

    private final BuyXGetCheapestFreeHandler handler = new BuyXGetCheapestFreeHandler();

    /** Section 2 example: NOTE 50x2, PEN 10x2, MUG 5x1 (not pooled), PEN 10x1 (second PEN line). */
    private static Cart stationeryCart() {
        return cart(
                item("SKU-NOTE", "STATIONERY", "50.00", 2),
                item("SKU-PEN", "STATIONERY", "10.00", 2),
                item("SKU-MUG", "KITCHEN", "5.00", 1),
                item("SKU-PEN", "SALE", "10.00", 1));
    }

    @Nested
    class GroupsAndSelection {

        @Test
        void singleSkuExactMultiple() {
            // Same data as public fixture 02.
            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "120.0", 3)),
                    buyXGetCheapestFreeRule("PROMO_MUG", 2, 1, "SKU-MUG").name("Mug buy 2 get 1").build());

            assertThat(result.originalSubtotal()).isEqualTo(money("360.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("120.00"));
            assertThat(result.finalTotal()).isEqualTo(money("240.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: buy 2 get 1 cheapest free (1 unit free)");
        }

        @ParameterizedTest(name = "{0} units -> {1} free")
        @CsvSource({"1,0", "2,0", "3,1", "4,1", "5,1", "6,2", "7,2", "8,2"})
        void onlyCompleteGroupsEarnFreeUnits(int quantity, int expectedFree) {
            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "10.00", quantity)),
                    buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG").build());

            AuditEntry entry = result.auditTrail().get(0);
            if (expectedFree == 0) {
                assertThat(entry.status()).isEqualTo(RuleStatus.SKIPPED);
                assertThat(entry.reason()).contains("a complete group needs 3");
            } else {
                assertThat(entry.status()).isEqualTo(RuleStatus.APPLIED);
                assertThat(entry.discountAmount()).isEqualTo(money("10.00").multiply(BigDecimal.valueOf(expectedFree)));
            }
        }

        @Test
        void multiSkuPoolFreesTheCheapestUnits() {
            // Pool: NOTE 50,50 + PEN 10,10 + PEN 10 = 5 units; buy 1 get 1 -> 2 groups -> 2 free.
            // Sorted: L1u0, L1u1, L3u0 (all 10.00, earlier line first), then L0u0, L0u1.
            var result = evaluate(stationeryCart(),
                    buyXGetCheapestFreeRule("R", 1, 1, "SKU-PEN", "SKU-NOTE").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.00"), money("20.00"), money("0.00"), money("0.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: buy 1 get 1 cheapest free (2 units free)");
        }

        @Test
        void equalPriceTieGoesToEarlierLine() {
            // Pool: line 0 (2 units) + line 1 (1 unit) = 3 -> 1 free; neither line alone forms a group.
            var result = evaluate(
                    cart(item("SKU-MUG", "KITCHEN", "10.00", 2), item("SKU-MUG", "KITCHEN", "10.00", 1)),
                    buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("10.00"), money("0.00"));
        }

        @Test
        void equalPriceTieWithinLineGoesToLowerUnitIndex() {
            var state = new CartState(cart(item("SKU-MUG", "KITCHEN", "10.00", 3)));

            var outcome = handler.evaluate(buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG").build(), state);

            LineChange change = ((RuleOutcome.Apply) outcome).changes().get(0);
            assertThat(change.lineIndex()).isZero();
            assertThat(change.unitIndexes()).containsExactly(0);
            assertThat(change.amount()).isEqualTo(money("10.00"));
        }

        @Test
        void unitsReducedByAnEarlierRuleCompeteAtTheirCurrentPrice() {
            // Category 10% on SALE makes line 3's PEN 9.00, so it is freed first, then line 1's first PEN.
            var result = evaluate(stationeryCart(),
                    categoryRule("CAT", "SALE", "10").priority(1).build(),
                    buyXGetCheapestFreeRule("BXGY", 1, 1, "SKU-PEN", "SKU-NOTE").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("1.00"), money("19.00"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("100.00"), money("10.00"), money("5.00"), money("0.00"));
        }

        @Test
        void buyOneGetOne() {
            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "10.00", 5)),
                    buyXGetCheapestFreeRule("R", 1, 1, "SKU-MUG").build());

            assertThat(result.totalDiscount()).isEqualTo(money("20.00"));
        }

        @Test
        void freeQuantityCanExceedBuyQuantity() {
            // buy 1 get 2: groups of 3; 7 units -> 2 groups -> 4 free
            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "10.00", 7)),
                    buyXGetCheapestFreeRule("R", 1, 2, "SKU-MUG").build());

            assertThat(result.totalDiscount()).isEqualTo(money("40.00"));
            assertThat(result.auditTrail().get(0).reason()).contains("(4 units free)");
        }
    }

    @Nested
    class Eligibility {

        @Test
        void unlistedSkuIsIgnoredEvenWhenCheapest() {
            var result = evaluate(stationeryCart(), buyXGetCheapestFreeRule("R", 1, 1, "SKU-PEN", "SKU-NOTE").build());

            assertThat(result.itemizedBreakdown().get(2).discount()).isEqualTo(money("0.00"));
        }

        @Test
        void skuMatchIsCaseSensitive() {
            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "10.00", 3)),
                    buyXGetCheapestFreeRule("R", 2, 1, "sku-mug").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no items with SKUs [sku-mug]");
        }

        @Test
        void emptyCartIsSkipped() {
            var result = evaluate(cart(), buyXGetCheapestFreeRule("R", 1, 1, "SKU-MUG").build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no items with SKUs [SKU-MUG]");
        }

        @Test
        void bundledUnitsAreNeitherCountedNorFreed() {
            var state = new CartState(cart(
                    item("SKU-PEN", "STATIONERY", "10.00", 3),
                    item("SKU-PEN", "STATIONERY", "8.00", 2)));
            // Simulate an earlier bundle: line 0 units 0 and 1 are bundled and now cost 1.00 each.
            state.commit(List.of(new LineChange(0, new int[] {0, 1}, money("18.00"), true)), true);

            // Pool = line 0 unit 2 (10.00) + line 1 units 0,1 (8.00) = 3 units -> 1 free: line 1 unit 0.
            var outcome = handler.evaluate(buyXGetCheapestFreeRule("R", 2, 1, "SKU-PEN").build(), state);

            List<LineChange> changes = ((RuleOutcome.Apply) outcome).changes();
            assertThat(changes).hasSize(1);
            assertThat(changes.get(0).lineIndex()).isEqualTo(1);
            assertThat(changes.get(0).unitIndexes()).containsExactly(0);
            assertThat(changes.get(0).amount()).isEqualTo(money("8.00"));
        }

        @Test
        void fullyBundledPoolIsSkipped() {
            var state = new CartState(cart(item("SKU-PEN", "STATIONERY", "10.00", 3)));
            state.commit(List.of(new LineChange(0, new int[] {0, 1, 2}, money("0.00"), true)), true);

            var outcome = handler.evaluate(buyXGetCheapestFreeRule("R", 2, 1, "SKU-PEN").build(), state);

            assertThat(outcome).isEqualTo(new RuleOutcome.Skip("all units with SKUs [SKU-PEN] are already bundled"));
        }
    }

    @Nested
    class StateEffects {

        @Test
        void freedUnitsBecomeZeroAndOthersAreUntouched() {
            var state = new CartState(cart(
                    item("SKU-PEN", "STATIONERY", "10.00", 3),
                    item("SKU-NOTE", "STATIONERY", "20.00", 1)));

            // buy 1 get 1 over 4 units -> 2 free: both are line 0 (10.00 < 20.00)
            var outcome = handler.evaluate(buyXGetCheapestFreeRule("R", 1, 1, "SKU-PEN", "SKU-NOTE").build(), state);
            state.commit(((RuleOutcome.Apply) outcome).changes(), handler.itemLevel());

            assertThat(unitPrices(state.line(0))).containsExactly(money("0.00"), money("0.00"), money("10.00"));
            assertThat(unitPrices(state.line(1))).containsExactly(money("20.00"));
            assertThat(state.line(0).isItemDiscounted()).isTrue();
            assertThat(state.line(1).isItemDiscounted()).as("pooled but nothing freed").isFalse();
        }

        @Test
        void evaluateDoesNotChangeTheCart() {
            var state = new CartState(cart(item("SKU-MUG", "KITCHEN", "10.00", 3)));

            handler.evaluate(buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG").build(), state);

            assertThat(unitPrices(state.line(0))).containsExactly(money("10.00"), money("10.00"), money("10.00"));
            assertThat(state.line(0).isItemDiscounted()).isFalse();
        }
    }

    @Nested
    class ZeroPricedUnits {

        @Test
        void allZeroPricedFreeUnitsGiveZeroDiscount() {
            var result = evaluate(cart(item("SKU-GIFT", "GIFTS", "0.00", 3)),
                    buyXGetCheapestFreeRule("R", 2, 1, "SKU-GIFT").build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: computed discount is 0.00");
        }

        @Test
        void zeroPricedUnitsCountAndTakeFreeSlotsFirst() {
            // Pool: GIFT 0.00 + PEN 10.00 x5 = 6 units; buy 2 get 1 -> 2 free: the gift and one pen.
            var result = evaluate(
                    cart(item("SKU-GIFT", "GIFTS", "0.00", 1), item("SKU-PEN", "STATIONERY", "10.00", 5)),
                    buyXGetCheapestFreeRule("R", 2, 1, "SKU-GIFT", "SKU-PEN").build());

            assertThat(result.totalDiscount()).isEqualTo(money("10.00"));
            assertThat(result.auditTrail().get(0).reason()).contains("(2 units free)");
        }

        @Test
        void secondRuleThatOnlyReachesAlreadyFreedUnitsGivesZero() {
            // R1 frees unit 0 -> [0.00, 10.00, 10.00]. R2 again earns 1 free unit, the cheapest is the
            // already-free unit 0, so R2's discount is 0.00 and it is skipped.
            var result = evaluate(cart(item("SKU-PEN", "STATIONERY", "10.00", 3)),
                    buyXGetCheapestFreeRule("R1", 2, 1, "SKU-PEN").priority(1).build(),
                    buyXGetCheapestFreeRule("R2", 2, 1, "SKU-PEN").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.APPLIED, RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(1).reason()).isEqualTo("Skipped: computed discount is 0.00");
            assertThat(result.totalDiscount()).isEqualTo(money("10.00"));
        }

        @Test
        void secondRuleWithMoreFreeSlotsStillDiscountsPositiveUnits() {
            // R1 (buy 2 get 1) over 6 units frees 2 -> [0, 0, 10, 10, 10, 10].
            // R2 (buy 1 get 1) earns 3 free: the two zero units plus one 10.00 unit -> 10.00.
            var result = evaluate(cart(item("SKU-PEN", "STATIONERY", "10.00", 6)),
                    buyXGetCheapestFreeRule("R1", 2, 1, "SKU-PEN").priority(1).build(),
                    buyXGetCheapestFreeRule("R2", 1, 1, "SKU-PEN").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("20.00"), money("10.00"));
            assertThat(result.finalTotal()).isEqualTo(money("30.00"));
        }
    }

    @Nested
    class Validation {

        static Stream<Arguments> invalidRules() {
            return Stream.of(
                    Arguments.of("skus missing", buyXGetCheapestFreeRule("R", 2, 1).skus((String[]) null).build(),
                            "conditions.skus must not be empty"),
                    Arguments.of("skus empty", buyXGetCheapestFreeRule("R", 2, 1).build(),
                            "conditions.skus must not be empty"),
                    Arguments.of("blank sku entry", buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG", " ").build(),
                            "conditions.skus must not contain blank entries"),
                    Arguments.of("buy missing", buyXGetCheapestFreeRule("R", null, 1, "SKU-MUG").build(),
                            "discountConfig.buyQuantity must be an integer >= 1"),
                    Arguments.of("buy zero", buyXGetCheapestFreeRule("R", 0, 1, "SKU-MUG").build(),
                            "discountConfig.buyQuantity must be an integer >= 1"),
                    Arguments.of("buy negative", buyXGetCheapestFreeRule("R", -1, 1, "SKU-MUG").build(),
                            "discountConfig.buyQuantity must be an integer >= 1"),
                    Arguments.of("free missing", buyXGetCheapestFreeRule("R", 2, null, "SKU-MUG").build(),
                            "discountConfig.freeQuantity must be an integer >= 1"),
                    Arguments.of("free zero", buyXGetCheapestFreeRule("R", 2, 0, "SKU-MUG").build(),
                            "discountConfig.freeQuantity must be an integer >= 1"),
                    Arguments.of("conditions missing",
                            buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG").withoutConditions().build(),
                            "conditions are missing"),
                    Arguments.of("discountConfig missing",
                            buyXGetCheapestFreeRule("R", 2, 1, "SKU-MUG").withoutDiscountConfig().build(),
                            "discountConfig is missing"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRules")
        void invalidConfigurationIsSkipped(String label, PromotionRule rule, String problem) {
            assertThat(handler.validate(rule)).contains(problem);

            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "10.00", 3)), rule);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: invalid configuration: " + problem);
            assertThat(result.finalTotal()).isEqualTo(money("30.00"));
        }

        @Test
        void minimumQuantitiesAreValid() {
            assertThat(handler.validate(buyXGetCheapestFreeRule("R", 1, 1, "SKU-MUG").build())).isEmpty();
        }

        @Test
        void hugeQuantitiesDoNotOverflow() {
            var result = evaluate(cart(item("SKU-MUG", "KITCHEN", "10.00", 3)),
                    buyXGetCheapestFreeRule("R1", Integer.MAX_VALUE, 1, "SKU-MUG").build(),
                    buyXGetCheapestFreeRule("R2", Integer.MAX_VALUE, Integer.MAX_VALUE, "SKU-MUG").build());

            assertThat(result.auditTrail()).extracting(AuditEntry::reason).containsExactly(
                    "Skipped: 3 eligible unit(s) with SKUs [SKU-MUG], but a complete group needs 2147483648",
                    "Skipped: 3 eligible unit(s) with SKUs [SKU-MUG], but a complete group needs 4294967294");
        }
    }
}
