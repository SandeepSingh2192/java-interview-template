package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Assertions use {@link BigDecimal#equals}, which also compares scale, so every
 * expectation also verifies that results are returned at scale 2.
 */
class MoneyMathTest {

    @Nested
    class PercentOf {

        @Test
        void tenPercentOfWholeAmount() {
            assertThat(MoneyMath.percentOf(money("2000.00"), pct("10"))).isEqualTo(money("200.00"));
        }

        @Test
        void roundsOnceHalfUp() {
            // 1.25 * 15% = 0.1875
            assertThat(MoneyMath.percentOf(money("1.25"), pct("15"))).isEqualTo(money("0.19"));
        }

        @Test
        void halfCentRoundsUp() {
            // 0.25 * 50% = 0.125
            assertThat(MoneyMath.percentOf(money("0.25"), pct("50"))).isEqualTo(money("0.13"));
        }

        @Test
        void fractionalPercentage() {
            // 99.99 * 12.5% = 12.49875
            assertThat(MoneyMath.percentOf(money("99.99"), pct("12.5"))).isEqualTo(money("12.50"));
            assertThat(MoneyMath.percentOf(money("80.00"), pct("12.5"))).isEqualTo(money("10.00"));
        }

        @Test
        void alwaysReturnsScaleTwo() {
            assertThat(MoneyMath.percentOf(new BigDecimal("100"), pct("10"))).isEqualTo(money("10.00"));
            assertThat(MoneyMath.percentOf(money("0.00"), pct("10"))).isEqualTo(money("0.00"));
        }
    }

    @Nested
    class Allocate {

        @Test
        void splitsTenAcrossThreeEqualLines() {
            // Weights are the lines' current prices, so each must cover its share (total <= sum).
            assertThat(MoneyMath.allocate(money("10.00"), moneys("10.00", "10.00", "10.00")))
                    .containsExactly(money("3.34"), money("3.33"), money("3.33"));
            // Unscaled weight input is accepted; only proportions matter.
            assertThat(MoneyMath.allocate(money("10.00"), moneys("20", "20", "20")))
                    .containsExactly(money("3.34"), money("3.33"), money("3.33"));
        }

        @Test
        void equalRemaindersFavourEarlierIndex() {
            assertThat(MoneyMath.allocate(money("0.02"), moneys("1.00", "1.00", "1.00")))
                    .containsExactly(money("0.01"), money("0.01"), money("0.00"));
        }

        @Test
        void largestRemainderBeatsEarlierIndex() {
            // 100 cents over weights 100:200 -> floors 33 (rem 100) and 66 (rem 200); index 1 gets the cent
            assertThat(MoneyMath.allocate(money("1.00"), moneys("1.00", "2.00")))
                    .containsExactly(money("0.33"), money("0.67"));
        }

        @Test
        void zeroTotalAllocatesNothing() {
            assertThat(MoneyMath.allocate(money("0.00"), moneys("5.00", "10.00")))
                    .containsExactly(money("0.00"), money("0.00"));
            assertThat(MoneyMath.allocate(money("0.00"), moneys("0.00", "0.00")))
                    .containsExactly(money("0.00"), money("0.00"));
            assertThat(MoneyMath.allocate(money("0.00"), List.of())).isEmpty();
        }

        @Test
        void totalEqualToSumOfWeightsReturnsWeightsExactly() {
            assertThat(MoneyMath.allocate(money("100.00"), moneys("33.33", "33.33", "33.34")))
                    .containsExactly(money("33.33"), money("33.33"), money("33.34"));
            assertThat(MoneyMath.allocate(money("15.37"), moneys("10.00", "5.37")))
                    .containsExactly(money("10.00"), money("5.37"));
        }

        @Test
        void zeroWeightEntriesReceiveNothing() {
            assertThat(MoneyMath.allocate(money("10.00"), moneys("0.00", "5.00", "5.00")))
                    .containsExactly(money("0.00"), money("5.00"), money("5.00"));
            // 100 cents over 0:100:200 -> floors 0, 33 (rem 100), 66 (rem 200); zero weight never gets a cent
            assertThat(MoneyMath.allocate(money("1.00"), moneys("0.00", "1.00", "2.00")))
                    .containsExactly(money("0.00"), money("0.33"), money("0.67"));
        }

        @Test
        void bundleFixtureSplit() {
            assertThat(MoneyMath.allocate(money("200.00"), moneys("800.00", "150.00", "50.00")))
                    .containsExactly(money("160.00"), money("30.00"), money("10.00"));
        }

        @Test
        void sharesNeverExceedWeights() {
            var weights = moneys("0.01", "0.01", "0.01", "9.97");
            var shares = MoneyMath.allocate(money("10.00"), weights);
            for (int i = 0; i < weights.size(); i++) {
                assertThat(shares.get(i)).isLessThanOrEqualTo(weights.get(i));
            }
            assertThat(sum(shares)).isEqualTo(money("10.00"));
        }

        @Test
        void handlesAmountsBeyondLongRange() {
            var maxLine = Money.extended(new BigDecimal("999999999999.99"), Integer.MAX_VALUE);
            // Sanity check: the cents value of one line alone does not fit in a long.
            assertThat(maxLine.movePointRight(2).toBigIntegerExact().bitLength()).isGreaterThan(63);

            var weights = List.of(maxLine, maxLine, maxLine);
            var cartTotal = sum(weights);

            // Exact split when total equals the sum of weights.
            assertThat(MoneyMath.allocate(cartTotal, weights)).containsExactly(maxLine, maxLine, maxLine);

            // Uneven split: shares sum exactly, differ by at most one cent, earlier lines take extras.
            var total = maxLine.add(money("0.02"));
            var shares = MoneyMath.allocate(total, weights);
            assertThat(sum(shares)).isEqualTo(total);
            assertThat(shares).allSatisfy(share -> assertThat(share.scale()).isEqualTo(2));
            assertThat(shares.get(0)).isGreaterThanOrEqualTo(shares.get(1));
            assertThat(shares.get(1)).isGreaterThanOrEqualTo(shares.get(2));
            assertThat(shares.get(0).subtract(shares.get(2))).isLessThanOrEqualTo(money("0.01"));
        }

        @Test
        void returnsSharesInInputOrder() {
            assertThat(MoneyMath.allocate(money("10.00"), moneys("50.00", "150.00", "800.00")))
                    .containsExactly(money("0.50"), money("1.50"), money("8.00"));
        }

        @Test
        void rejectsNegativeTotal() {
            assertThatThrownBy(() -> MoneyMath.allocate(money("-0.01"), moneys("1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeWeight() {
            assertThatThrownBy(() -> MoneyMath.allocate(money("1.00"), moneys("5.00", "-1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsTotalAboveSumOfWeights() {
            assertThatThrownBy(() -> MoneyMath.allocate(money("10.01"), moneys("5.00", "5.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MoneyMath.allocate(money("0.01"), moneys("0.00", "0.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MoneyMath.allocate(money("0.01"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsMoreThanTwoDecimalPlaces() {
            assertThatThrownBy(() -> MoneyMath.allocate(new BigDecimal("1.005"), moneys("5.00")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MoneyMath.allocate(money("1.00"), List.of(new BigDecimal("5.001"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal pct(String value) {
        return new BigDecimal(value);
    }

    private static List<BigDecimal> moneys(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(Money.ZERO, BigDecimal::add);
    }
}
