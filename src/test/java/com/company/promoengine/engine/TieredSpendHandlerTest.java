package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.amountTier;
import static com.company.promoengine.engine.EngineTestSupport.bundleRule;
import static com.company.promoengine.engine.EngineTestSupport.cart;
import static com.company.promoengine.engine.EngineTestSupport.categoryRule;
import static com.company.promoengine.engine.EngineTestSupport.component;
import static com.company.promoengine.engine.EngineTestSupport.evaluate;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.percentTier;
import static com.company.promoengine.engine.EngineTestSupport.tier;
import static com.company.promoengine.engine.EngineTestSupport.tieredRule;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.SpendTier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code TIERED_SPEND} and, through it, the shared {@link CartLevelRuleHandler} flow.
 * Engine-level tests also check the accounting invariants via {@link EngineTestSupport#evaluate}.
 */
class TieredSpendHandlerTest {

    private final TieredSpendHandler handler = new TieredSpendHandler();

    private static Cart singleLine(String price) {
        return cart(item("SKU-ORDER", "GENERAL", price, 1));
    }

    /** Lines A (BOOKS 100.00) and B (TOYS 100.00). */
    private static Cart twoLines() {
        return cart(item("SKU-A", "BOOKS", "100.00", 1), item("SKU-B", "TOYS", "100.00", 1));
    }

    @Nested
    class TierSelection {

        @Test
        void singleQualifyingTier() {
            // Same data as public fixture 04.
            var result = evaluate(singleLine("2500.00"),
                    tieredRule("PROMO_TIER", amountTier("2000.00", "150.00")).name("Spend 2000 save 150").build());

            assertThat(result.totalDiscount()).isEqualTo(money("150.00"));
            assertThat(result.finalTotal()).isEqualTo(money("2350.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: spend tier >= 2000.00 grants 150.00 off cart (running subtotal 2500.00)");
        }

        @Test
        void greatestQualifyingThresholdWinsRegardlessOfListOrder() {
            var result = evaluate(singleLine("2500.00"), tieredRule("R",
                    amountTier("1000.00", "50.00"),
                    amountTier("3000.00", "300.00"),
                    amountTier("2000.00", "150.00")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("150.00"));
        }

        @Test
        void equalThresholdsKeepTheEarlierTier() {
            // 2000.0 and 2000.00 are equal by compareTo.
            var result = evaluate(singleLine("2500.00"), tieredRule("R",
                    amountTier("500.00", "10.00"),
                    amountTier("2000.0", "100.00"),
                    amountTier("2000.00", "200.00")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("100.00"));
        }

        @Test
        void baseEqualToThresholdQualifies() {
            var result = evaluate(singleLine("2000.00"), tieredRule("R", amountTier("2000.00", "150.00")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("150.00"));
        }

        @Test
        void baseJustBelowEveryThresholdIsSkipped() {
            var result = evaluate(singleLine("1999.99"), tieredRule("R",
                    amountTier("2000.00", "150.00"),
                    amountTier("3000.00", "300.00")).build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: no spend tier qualifies for running subtotal 1999.99");
        }

        @Test
        void zeroThresholdQualifiesForAnyPositiveBase() {
            var result = evaluate(singleLine("0.01"), tieredRule("R", amountTier("0.00", "5.00")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("0.01"));
        }
    }

    @Nested
    class GrantCalculation {

        @Test
        void percentageTierIsRoundedOnceOnTheBase() {
            // 99.99 * 12.5% = 12.49875 -> 12.50
            var result = evaluate(singleLine("99.99"), tieredRule("R", percentTier("0.00", "12.5")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("12.50"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: spend tier >= 0.00 grants 12.5% (12.50) off cart (running subtotal 99.99)");
        }

        @Test
        void percentageIsComputedOnTheCartNotPerLine() {
            // Base 0.30 * 15% = 0.045 -> 0.05 (per-line rounding would give 3 x 0.02 = 0.06),
            // then 0.05 is split 0.02 / 0.02 / 0.01.
            var result = evaluate(cart(
                            item("SKU-A", "X", "0.10", 1),
                            item("SKU-B", "X", "0.10", 1),
                            item("SKU-C", "X", "0.10", 1)),
                    tieredRule("R", percentTier("0.00", "15")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("0.05"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.02"), money("0.02"), money("0.01"));
        }

        @Test
        void fixedAmountIsCappedAtTheBase() {
            var result = evaluate(singleLine("300.00"), tieredRule("R", amountTier("0.00", "500.00")).build());

            assertThat(result.totalDiscount()).isEqualTo(money("300.00"));
            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: spend tier >= 0.00 grants 300.00 off cart (running subtotal 300.00)");
        }

        @Test
        void hundredPercentTierGrantsTheWholeBase() {
            var result = evaluate(singleLine("80.00"), tieredRule("R", percentTier("0.00", "100")).build());

            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
        }

        @Test
        void maxDiscountAmountIsNotAnInputAndIsIgnored() {
            var result = evaluate(singleLine("2500.00"),
                    tieredRule("R", amountTier("2000.00", "150.00")).maxDiscount("10.00").build());

            assertThat(result.totalDiscount()).isEqualTo(money("150.00"));
        }

        @Test
        void zeroRoundedGrantIsSkipped() {
            // 0.04 * 10% = 0.004 -> 0.00
            var result = evaluate(singleLine("0.04"), tieredRule("R", percentTier("0.00", "10")).build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: computed discount is 0.00");
        }
    }

    @Nested
    class Allocation {

        @Test
        void pennyRemainderGoesToTheEarlierLine() {
            var result = evaluate(cart(
                            item("SKU-A", "X", "10.00", 1),
                            item("SKU-B", "X", "10.00", 1),
                            item("SKU-C", "X", "10.00", 1)),
                    tieredRule("R", amountTier("0.00", "10.00")).build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("3.34"), money("3.33"), money("3.33"));
        }

        @Test
        void grantIsWeightedByCurrentLinePrice() {
            var result = evaluate(cart(item("SKU-A", "X", "100.00", 1), item("SKU-B", "X", "50.00", 1)),
                    tieredRule("R", amountTier("0.00", "30.00")).build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("20.00"), money("10.00"));
        }

        @Test
        void bundledUnitsShareTheCartDiscount() {
            // The bundle bundles every unit (640 / 120 / 40); the tier still discounts them 64 / 12 / 4.
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                            item("SKU-MOUSE", "ACCESSORIES", "150.00", 1),
                            item("SKU-BAG", "ACCESSORIES", "50.00", 1)),
                    bundleRule("BUNDLE", "800.00",
                            component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1), component("SKU-BAG", 1))
                            .priority(1).build(),
                    tieredRule("TIER", amountTier("0.00", "80.00")).priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("200.00"), money("80.00"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("576.00"), money("108.00"), money("36.00"));
        }
    }

    @Nested
    class RunningSubtotalAndStacking {

        private final SpendTier[] downgradeTiers = {amountTier("2000.00", "200.00"), amountTier("1000.00", "50.00")};

        @Test
        void withoutItemDiscountTheHigherTierApplies() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "1100.00", 1), item("SKU-B", "TOYS", "1000.00", 1)),
                    tieredRule("TIER", downgradeTiers).build());

            assertThat(result.totalDiscount()).isEqualTo(money("200.00"));
        }

        @Test
        void earlierItemDiscountDowngradesTheTier() {
            // Category takes 110.00 off BOOKS, so the running subtotal is 1990.00 -> the 1000 tier.
            var result = evaluate(cart(item("SKU-A", "BOOKS", "1100.00", 1), item("SKU-B", "TOYS", "1000.00", 1)),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    tieredRule("TIER", downgradeTiers).priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("110.00"), money("50.00"));
            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: spend tier >= 1000.00 grants 50.00 off cart (running subtotal 1990.00)");
        }

        @Test
        void nonStackableRuleUsesOnlyLinesWithoutItemDiscounts() {
            // Line A is item-discounted (90.00). Eligible base = line B 100.00 -> the 0.00 tier (10.00), all on B.
            var result = evaluate(twoLines(),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    tieredRule("TIER", amountTier("150.00", "40.00"), amountTier("0.00", "10.00"))
                            .stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).discountAmount()).isEqualTo(money("10.00"));
            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: spend tier >= 0.00 grants 10.00 off cart (eligible subtotal 100.00)");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("10.00"), money("10.00"));
        }

        @Test
        void stackableTrueOrNullUsesAllLines() {
            // Base 190.00 -> the 150 tier (40.00), split 90:100 -> 18.95 / 21.05.
            for (Boolean stackable : new Boolean[] {Boolean.TRUE, null}) {
                var result = evaluate(twoLines(),
                        categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                        tieredRule("TIER", amountTier("150.00", "40.00"), amountTier("0.00", "10.00"))
                                .stackable(stackable).priority(2).build());

                assertThat(result.auditTrail().get(1).discountAmount()).isEqualTo(money("40.00"));
                assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                        .containsExactly(money("28.95"), money("21.05"));
            }
        }

        @Test
        void nonStackableRuleWithEveryLineItemDiscountedIsSkipped() {
            var result = evaluate(cart(item("SKU-A", "BOOKS", "100.00", 1)),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    tieredRule("TIER", amountTier("0.00", "10.00")).stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Skipped: no eligible subtotal (item-discounted lines excluded)");
        }

        @Test
        void cartRulesDoNotMakeLinesItemDiscounted() {
            // The first tier lowers both lines but does not mark them, so the non-stackable second
            // tier still sees both (eligible subtotal 180.00).
            var result = evaluate(twoLines(),
                    tieredRule("T1", amountTier("0.00", "20.00")).priority(1).build(),
                    tieredRule("T2", amountTier("0.00", "18.00")).stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: spend tier >= 0.00 grants 18.00 off cart (eligible subtotal 180.00)");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("19.00"), money("19.00"));
        }

        @Test
        void commitLeavesItemDiscountedFlagUntouched() {
            var state = new CartState(twoLines());

            var apply = (RuleOutcome.Apply) handler.evaluate(tieredRule("R", amountTier("0.00", "20.00")).build(), state);
            state.commit(apply.changes(), handler.itemLevel());

            assertThat(handler.itemLevel()).isFalse();
            assertThat(state.line(0).isItemDiscounted()).isFalse();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
            assertThat(state.currentSubtotal()).isEqualTo(money("180.00"));
        }

        @Test
        void bundledLinesWithPositiveSharesAreExcludedWhenNotStackable() {
            var result = evaluate(
                    cart(item("SKU-LAPTOP", "LAPTOP", "800.00", 1), item("SKU-MOUSE", "ACCESSORIES", "150.00", 1)),
                    bundleRule("BUNDLE", "900.00", component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1))
                            .priority(1).build(),
                    tieredRule("TIER", amountTier("0.00", "10.00")).stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Skipped: no eligible subtotal (item-discounted lines excluded)");
        }
    }

    @Nested
    class SkipsAndGroups {

        @Test
        void emptyCartIsSkipped() {
            var result = evaluate(cart(), tieredRule("R", amountTier("0.00", "10.00")).build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no eligible subtotal");
        }

        @Test
        void tierThatDoesNotQualifyLeavesTheGroupUnclaimed() {
            var result = evaluate(singleLine("2500.00"),
                    tieredRule("T1", amountTier("5000.00", "100.00")).group("CART").priority(1).build(),
                    tieredRule("T2", amountTier("0.00", "10.00")).group("CART").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.SKIPPED, RuleStatus.APPLIED);
        }

        @Test
        void appliedTierClaimsTheGroup() {
            var result = evaluate(singleLine("2500.00"),
                    tieredRule("T1", amountTier("0.00", "10.00")).group("CART").priority(1).build(),
                    tieredRule("T2", amountTier("0.00", "20.00")).group("CART").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.APPLIED, RuleStatus.SKIPPED);
            assertThat(result.totalDiscount()).isEqualTo(money("10.00"));
        }

        @Test
        void evaluateDoesNotChangeTheCart() {
            var state = new CartState(twoLines());

            handler.evaluate(tieredRule("R", amountTier("0.00", "50.00")).build(), state);

            assertThat(unitPrices(state.line(0))).containsExactly(money("100.00"));
            assertThat(unitPrices(state.line(1))).containsExactly(money("100.00"));
        }
    }

    @Nested
    class Validation {

        static Stream<Arguments> invalidRules() {
            return Stream.of(
                    Arguments.of("discountConfig missing",
                            tieredRule("R", amountTier("0.00", "10.00")).withoutDiscountConfig().build(),
                            "discountConfig is missing"),
                    Arguments.of("tiers missing", tieredRule("R").tiers((SpendTier[]) null).build(),
                            "discountConfig.tiers must not be empty"),
                    Arguments.of("tiers empty", tieredRule("R").build(),
                            "discountConfig.tiers must not be empty"),
                    Arguments.of("minSubtotal missing", tieredRule("R", tier(null, null, "10.00")).build(),
                            "tier 1: minSubtotal must be >= 0"),
                    Arguments.of("minSubtotal negative", tieredRule("R", tier("-0.01", null, "10.00")).build(),
                            "tier 1: minSubtotal must be >= 0"),
                    Arguments.of("both percentage and amount", tieredRule("R", tier("0.00", "10", "10.00")).build(),
                            "tier 1: exactly one of percentage or amount is required"),
                    Arguments.of("neither percentage nor amount", tieredRule("R", tier("0.00", null, null)).build(),
                            "tier 1: exactly one of percentage or amount is required"),
                    Arguments.of("invalid percentage beside valid amount (strict)",
                            tieredRule("R", tier("0.00", "0", "50.00")).build(),
                            "tier 1: exactly one of percentage or amount is required"),
                    Arguments.of("percentage zero", tieredRule("R", percentTier("0.00", "0")).build(),
                            "tier 1: percentage must be > 0 and <= 100"),
                    Arguments.of("percentage above 100", tieredRule("R", percentTier("0.00", "101")).build(),
                            "tier 1: percentage must be > 0 and <= 100"),
                    Arguments.of("amount zero", tieredRule("R", amountTier("0.00", "0.00")).build(),
                            "tier 1: amount must be > 0"),
                    Arguments.of("amount negative", tieredRule("R", amountTier("0.00", "-5.00")).build(),
                            "tier 1: amount must be > 0"),
                    Arguments.of("one bad tier among good ones (never selected)",
                            tieredRule("R", amountTier("0.00", "10.00"), percentTier("5000.00", "0")).build(),
                            "tier 2: percentage must be > 0 and <= 100"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRules")
        void invalidConfigurationIsSkipped(String label, PromotionRule rule, String problem) {
            assertThat(handler.validate(rule)).contains(problem);

            var result = evaluate(singleLine("2500.00"), rule);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: invalid configuration: " + problem);
            assertThat(result.finalTotal()).isEqualTo(money("2500.00"));
        }

        @Test
        void boundaryValuesAreValid() {
            assertThat(handler.validate(tieredRule("R", amountTier("0.00", "0.01"), percentTier("0.00", "100")).build()))
                    .isEmpty();
        }

        @Test
        void conditionsAreNotRequired() {
            var rule = tieredRule("R", amountTier("0.00", "10.00")).withoutConditions().build();

            assertThat(handler.validate(rule)).isEmpty();
            assertThat(evaluate(singleLine("100.00"), rule).totalDiscount()).isEqualTo(money("10.00"));
        }
    }
}
