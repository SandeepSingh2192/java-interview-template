package com.company.promoengine.engine;

import static com.company.promoengine.engine.EngineTestSupport.amountTier;
import static com.company.promoengine.engine.EngineTestSupport.bundleRule;
import static com.company.promoengine.engine.EngineTestSupport.buyXGetCheapestFreeRule;
import static com.company.promoengine.engine.EngineTestSupport.cartPercentageRule;
import static com.company.promoengine.engine.EngineTestSupport.cartWith;
import static com.company.promoengine.engine.EngineTestSupport.categoryRule;
import static com.company.promoengine.engine.EngineTestSupport.component;
import static com.company.promoengine.engine.EngineTestSupport.couponRule;
import static com.company.promoengine.engine.EngineTestSupport.evaluate;
import static com.company.promoengine.engine.EngineTestSupport.item;
import static com.company.promoengine.engine.EngineTestSupport.money;
import static com.company.promoengine.engine.EngineTestSupport.tieredRule;
import static com.company.promoengine.engine.EngineTestSupport.unitPrices;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.RuleType;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code COUPON_PAYMENT_OFF}. Engine-level tests also check the accounting invariants via
 * {@link EngineTestSupport#evaluate}; a few handler-level tests inspect flags and unit detail.
 */
class CouponPaymentHandlerTest {

    private static final CartItem ORDER_1000 = item("SKU-ORDER", "GENERAL", "1000.00", 1);

    private final CouponPaymentHandler handler = new CouponPaymentHandler();

    /** Coupon SAVE10, 10%, no payment condition. */
    private static EngineTestSupport.RuleBuilder save10() {
        return couponRule("PROMO_COUPON", "SAVE10").percentage("10");
    }

    /** Line A (BOOKS 100.00) and line B (TOYS 100.00), with coupon SAVE10. */
    private static Cart twoLinesWithCoupon() {
        return cartWith("SAVE10", null,
                item("SKU-A", "BOOKS", "100.00", 1), item("SKU-B", "TOYS", "100.00", 1));
    }

    @Nested
    class Matching {

        @Test
        void couponAndPaymentMethodMatch() {
            // Same data as public fixture 05.
            var result = evaluate(cartWith("SAVE10", "UPI", ORDER_1000),
                    couponRule("PROMO_UPI", "SAVE10").paymentMethod("UPI").percentage("10")
                            .name("UPI coupon 10%").build());

            assertThat(result.originalSubtotal()).isEqualTo(money("1000.00"));
            assertThat(result.totalDiscount()).isEqualTo(money("100.00"));
            assertThat(result.finalTotal()).isEqualTo(money("900.00"));
            assertThat(result.itemizedBreakdown().get(0).discount()).isEqualTo(money("100.00"));
            assertThat(result.auditTrail()).containsExactly(new AuditEntry("PROMO_UPI", "UPI coupon 10%",
                    RuleType.COUPON_PAYMENT_OFF, RuleStatus.APPLIED, money("100.00"),
                    "Applied: 10% coupon SAVE10 via UPI off cart (running subtotal 1000.00)"));
        }

        @Test
        void couponMatchIsCaseInsensitiveAndTheReasonShowsTheRuleCode() {
            var result = evaluate(cartWith("save10", null, ORDER_1000), save10().build());

            assertThat(result.totalDiscount()).isEqualTo(money("100.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 10% coupon SAVE10 off cart (running subtotal 1000.00)");
        }

        @Test
        void paymentMatchIsCaseInsensitive() {
            var result = evaluate(cartWith("SAVE10", "upi", ORDER_1000), save10().paymentMethod("UPI").build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 10% coupon SAVE10 via UPI off cart (running subtotal 1000.00)");
        }

        @Test
        void withoutAPaymentConditionTheCartPaymentMethodIsIgnored() {
            for (String cartPayment : new String[] {"CARD", null}) {
                var result = evaluate(cartWith("SAVE10", cartPayment, ORDER_1000), save10().build());

                assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.APPLIED);
                assertThat(result.auditTrail().get(0).reason()).doesNotContain("via");
            }
        }

        @Test
        void blankRulePaymentMethodCountsAsNotSet() {
            var result = evaluate(cartWith("SAVE10", "CARD", ORDER_1000), save10().paymentMethod("   ").build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 10% coupon SAVE10 off cart (running subtotal 1000.00)");
        }

        @Test
        void cartWithoutCouponIsSkipped() {
            var result = evaluate(cartWith(null, "UPI", ORDER_1000), save10().build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: cart has no coupon code (rule requires SAVE10)");
        }

        @Test
        void differentCouponIsSkipped() {
            var result = evaluate(cartWith("SAVE20", null, ORDER_1000), save10().build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: coupon SAVE20 does not match SAVE10");
        }

        @Test
        void couponValuesAreNotTrimmed() {
            var result = evaluate(cartWith("SAVE10 ", null, ORDER_1000), save10().build());

            assertThat(result.auditTrail().get(0).status()).isEqualTo(RuleStatus.SKIPPED);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: coupon SAVE10  does not match SAVE10");
        }

        @Test
        void cartWithoutPaymentMethodIsSkippedWhenTheRuleRequiresOne() {
            var result = evaluate(cartWith("SAVE10", null, ORDER_1000), save10().paymentMethod("UPI").build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: cart has no payment method (rule requires UPI)");
        }

        @Test
        void differentPaymentMethodIsSkipped() {
            var result = evaluate(cartWith("SAVE10", "CARD", ORDER_1000), save10().paymentMethod("UPI").build());

            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Skipped: payment method CARD does not match UPI");
        }
    }

    @Nested
    class GrantCalculation {

        @Test
        void percentageIsRoundedOnceOnTheCart() {
            // Base 0.30 * 15% = 0.045 -> 0.05 (not 3 x 0.02), split 0.02 / 0.02 / 0.01.
            var result = evaluate(cartWith("SAVE10", null,
                            item("SKU-A", "X", "0.10", 1), item("SKU-B", "X", "0.10", 1), item("SKU-C", "X", "0.10", 1)),
                    couponRule("R", "SAVE10").percentage("15").build());

            assertThat(result.totalDiscount()).isEqualTo(money("0.05"));
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.02"), money("0.02"), money("0.01"));
        }

        @Test
        void halfCentRoundsUp() {
            // 0.25 * 50% = 0.125 -> 0.13
            var result = evaluate(cartWith("SAVE10", null, item("SKU-A", "X", "0.25", 1)),
                    couponRule("R", "SAVE10").percentage("50").build());

            assertThat(result.totalDiscount()).isEqualTo(money("0.13"));
        }

        @Test
        void fixedAmount() {
            var result = evaluate(cartWith("SAVE10", null, ORDER_1000), couponRule("R", "SAVE10").amount("50.00").build());

            assertThat(result.totalDiscount()).isEqualTo(money("50.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 50.00 coupon SAVE10 off cart (running subtotal 1000.00)");
        }

        @Test
        void fixedAmountIsCappedAtTheBase() {
            var result = evaluate(cartWith("SAVE10", "UPI", item("SKU-A", "X", "300.00", 1)),
                    couponRule("R", "SAVE10").paymentMethod("UPI").amount("500.00").build());

            assertThat(result.totalDiscount()).isEqualTo(money("300.00"));
            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
            assertThat(result.auditTrail().get(0).reason())
                    .isEqualTo("Applied: 300.00 coupon SAVE10 via UPI off cart (running subtotal 300.00)");
        }

        @Test
        void hundredPercentClearsTheCart() {
            var result = evaluate(cartWith("SAVE10", null, ORDER_1000), couponRule("R", "SAVE10").percentage("100").build());

            assertThat(result.finalTotal()).isEqualTo(money("0.00"));
        }

        @Test
        void grantRoundingToZeroIsSkipped() {
            var result = evaluate(cartWith("SAVE10", null, item("SKU-A", "X", "0.04", 1)), save10().build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: computed discount is 0.00");
        }
    }

    @Nested
    class Allocation {

        @Test
        void pennyRemainderGoesToTheEarlierLine() {
            var result = evaluate(cartWith("SAVE10", null,
                            item("SKU-A", "X", "3.33", 1), item("SKU-B", "X", "3.33", 1), item("SKU-C", "X", "3.33", 1)),
                    couponRule("R", "SAVE10").amount("1.00").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("0.34"), money("0.33"), money("0.33"));
        }

        @Test
        void grantIsWeightedByCurrentLinePrice() {
            var result = evaluate(cartWith("SAVE10", null, item("SKU-A", "X", "100.00", 1), item("SKU-B", "X", "50.00", 1)),
                    couponRule("R", "SAVE10").amount("30.00").build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("20.00"), money("10.00"));
        }

        @Test
        void bundledUnitsShareTheCouponDiscount() {
            // Bundle leaves 640 / 120 / 40 (all bundled); 80.00 off splits 64 / 12 / 4.
            var result = evaluate(cartWith("SAVE10", null,
                            item("SKU-LAPTOP", "LAPTOP", "800.00", 1),
                            item("SKU-MOUSE", "ACCESSORIES", "150.00", 1),
                            item("SKU-BAG", "ACCESSORIES", "50.00", 1)),
                    bundleRule("BUNDLE", "800.00",
                            component("SKU-LAPTOP", 1), component("SKU-MOUSE", 1), component("SKU-BAG", 1))
                            .priority(1).build(),
                    couponRule("COUPON", "SAVE10").amount("80.00").priority(2).build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("576.00"), money("108.00"), money("36.00"));
        }
    }

    @Nested
    class StackingAndRunningSubtotal {

        @Test
        void stackableTrueOrNullIncludesItemDiscountedLines() {
            // Category takes 10.00 off A; base 190.00 -> 19.00 split 9.00 / 10.00.
            for (Boolean stackable : new Boolean[] {Boolean.TRUE, null}) {
                var result = evaluate(twoLinesWithCoupon(),
                        categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                        save10().stackable(stackable).priority(2).build());

                assertThat(result.auditTrail().get(1).reason())
                        .isEqualTo("Applied: 10% coupon SAVE10 off cart (running subtotal 190.00)");
                assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                        .containsExactly(money("19.00"), money("10.00"));
            }
        }

        @Test
        void nonStackableExcludesItemDiscountedLines() {
            var result = evaluate(twoLinesWithCoupon(),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    save10().stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% coupon SAVE10 off cart (eligible subtotal 100.00)");
            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::discount)
                    .containsExactly(money("10.00"), money("10.00"));
        }

        @Test
        void emptyBaseIsReportedBeforeACouponMismatch() {
            // The shared flow checks the base first, so the mismatched coupon is not what is reported.
            var result = evaluate(cartWith("WRONG", null, item("SKU-A", "BOOKS", "100.00", 1)),
                    categoryRule("CAT", "BOOKS", "10").priority(1).build(),
                    save10().stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Skipped: no eligible subtotal (item-discounted lines excluded)");
        }

        @Test
        void emptyCartIsSkipped() {
            var result = evaluate(cartWith("SAVE10", null), save10().build());

            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: no eligible subtotal");
        }

        @Test
        void couponAfterACartPercentageUsesTheRunningSubtotal() {
            var result = evaluate(cartWith("SAVE10", null, ORDER_1000),
                    cartPercentageRule("CART", "10").priority(1).build(),
                    couponRule("COUPON", "SAVE10").amount("50.00").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("100.00"), money("50.00"));
            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 50.00 coupon SAVE10 off cart (running subtotal 900.00)");
        }

        @Test
        void earlierCouponCanDowngradeATier() {
            // 10% off 2100.00 leaves 1890.00 -> the 1000 tier (50.00) instead of the 2000 tier.
            var result = evaluate(cartWith("SAVE10", null, item("SKU-ORDER", "GENERAL", "2100.00", 1)),
                    save10().priority(1).build(),
                    tieredRule("TIER", amountTier("2000.00", "200.00"), amountTier("1000.00", "50.00"))
                            .priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("210.00"), money("50.00"));
        }

        @Test
        void couponAfterATierUsesTheReducedSubtotal() {
            var result = evaluate(cartWith("SAVE10", null, item("SKU-ORDER", "GENERAL", "2100.00", 1)),
                    tieredRule("TIER", amountTier("2000.00", "200.00")).priority(1).build(),
                    save10().priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::discountAmount)
                    .containsExactly(money("200.00"), money("190.00"));
        }

        @Test
        void couponDoesNotMakeLinesItemDiscounted() {
            // The coupon lowers both lines to 90.00 without marking them; the non-stackable cart rule sees both.
            var result = evaluate(twoLinesWithCoupon(),
                    save10().priority(1).build(),
                    cartPercentageRule("CART", "10").stackable(false).priority(2).build());

            assertThat(result.auditTrail().get(1).reason())
                    .isEqualTo("Applied: 10% (18.00) off cart (eligible subtotal 180.00)");
        }

        @Test
        void nonStackableCouponExcludesTheBuyXGetYLine() {
            var result = evaluate(cartWith("SAVE10", null,
                            item("SKU-MUG", "KITCHEN", "10.00", 3), item("SKU-BOOK", "BOOKS", "100.00", 1)),
                    buyXGetCheapestFreeRule("BXGY", 2, 1, "SKU-MUG").priority(1).build(),
                    save10().stackable(false).priority(2).build());

            assertThat(result.itemizedBreakdown()).extracting(ItemizedLine::finalExtendedPrice)
                    .containsExactly(money("20.00"), money("90.00"));
        }

        @Test
        void commitLeavesItemDiscountedFlagsUntouched() {
            var state = new CartState(twoLinesWithCoupon());

            var apply = (RuleOutcome.Apply) handler.evaluate(save10().build(), state);
            state.commit(apply.changes(), handler.itemLevel());

            assertThat(handler.itemLevel()).isFalse();
            assertThat(state.line(0).isItemDiscounted()).isFalse();
            assertThat(state.line(1).isItemDiscounted()).isFalse();
            assertThat(unitPrices(state.line(0))).containsExactly(money("90.00"));
        }
    }

    @Nested
    class GroupsAndProperties {

        @Test
        void appliedCouponClaimsTheGroup() {
            var result = evaluate(cartWith("SAVE10", null, ORDER_1000),
                    save10().group("CART_DEALS").priority(1).build(),
                    tieredRule("TIER", amountTier("0.00", "50.00")).group("CART_DEALS").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.APPLIED, RuleStatus.SKIPPED);
        }

        @Test
        void mismatchedCouponLeavesTheGroupUnclaimed() {
            var result = evaluate(cartWith("OTHER", null, ORDER_1000),
                    save10().group("CART_DEALS").priority(1).build(),
                    tieredRule("TIER", amountTier("0.00", "50.00")).group("CART_DEALS").priority(2).build());

            assertThat(result.auditTrail()).extracting(AuditEntry::status)
                    .containsExactly(RuleStatus.SKIPPED, RuleStatus.APPLIED);
        }

        @Test
        void fieldsThatAreNotInputsAreIgnored() {
            var result = evaluate(cartWith("SAVE10", null, ORDER_1000), save10()
                    .maxDiscount("1.00").tiers(amountTier("0.00", "500.00"))
                    .category("ELECTRONICS").skus("SKU-NONE").build());

            assertThat(result.totalDiscount()).isEqualTo(money("100.00"));
        }

        @Test
        void evaluateDoesNotChangeTheCart() {
            var state = new CartState(twoLinesWithCoupon());

            handler.evaluate(save10().build(), state);

            assertThat(unitPrices(state.line(0))).containsExactly(money("100.00"));
            assertThat(unitPrices(state.line(1))).containsExactly(money("100.00"));
        }
    }

    @Nested
    class Validation {

        static Stream<Arguments> invalidRules() {
            return Stream.of(
                    Arguments.of("conditions missing", save10().withoutConditions().build(), "conditions are missing"),
                    Arguments.of("coupon code missing", couponRule("R", null).percentage("10").build(),
                            "conditions.couponCode is required"),
                    Arguments.of("coupon code blank", couponRule("R", "  ").percentage("10").build(),
                            "conditions.couponCode is required"),
                    Arguments.of("discountConfig missing", save10().withoutDiscountConfig().build(),
                            "discountConfig is missing"),
                    Arguments.of("both percentage and amount", save10().amount("50.00").build(),
                            "discountConfig needs exactly one of percentage or amount"),
                    Arguments.of("neither percentage nor amount", couponRule("R", "SAVE10").build(),
                            "discountConfig needs exactly one of percentage or amount"),
                    Arguments.of("percentage zero", couponRule("R", "SAVE10").percentage("0").build(),
                            "discountConfig.percentage must be > 0 and <= 100"),
                    Arguments.of("percentage above 100", couponRule("R", "SAVE10").percentage("101").build(),
                            "discountConfig.percentage must be > 0 and <= 100"),
                    Arguments.of("amount zero", couponRule("R", "SAVE10").amount("0.00").build(),
                            "discountConfig.amount must be > 0"),
                    Arguments.of("amount negative", couponRule("R", "SAVE10").amount("-1.00").build(),
                            "discountConfig.amount must be > 0"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRules")
        void invalidConfigurationIsSkipped(String label, PromotionRule rule, String problem) {
            assertThat(handler.validate(rule)).contains(problem);

            var result = evaluate(cartWith("SAVE10", "UPI", ORDER_1000), rule);
            assertThat(result.auditTrail().get(0).reason()).isEqualTo("Skipped: invalid configuration: " + problem);
            assertThat(result.finalTotal()).isEqualTo(money("1000.00"));
        }

        @Test
        void boundaryValuesAreValid() {
            assertThat(handler.validate(couponRule("R", "SAVE10").percentage("100").build())).isEmpty();
            assertThat(handler.validate(couponRule("R", "SAVE10").amount("0.01").build())).isEmpty();
            assertThat(handler.validate(couponRule("R", "SAVE10").percentage("0.01").paymentMethod("UPI").build()))
                    .isEmpty();
        }
    }
}
