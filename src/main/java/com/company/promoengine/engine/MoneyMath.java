package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Engine-internal arithmetic on scale-2 {@link BigDecimal} money.
 *
 * <p>Everything is arbitrary precision, so line totals, cart totals, and allocation
 * weights cannot overflow regardless of unit price or quantity.
 */
final class MoneyMath {

    private MoneyMath() {
    }

    /**
     * Returns {@code base * percentage / 100}, rounded once to scale 2 ({@code HALF_UP}).
     * {@code percentage} is a percent ({@code 10} means 10%), not a fraction.
     */
    static BigDecimal percentOf(BigDecimal base, BigDecimal percentage) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(percentage, "percentage");
        return base.multiply(percentage).divide(Money.ONE_HUNDRED, Money.SCALE, Money.ROUNDING);
    }

    /**
     * Splits {@code total} across {@code weights} pro rata in whole cents using the
     * largest-remainder (Hamilton) method: every entry gets its floor share, then the
     * leftover cents go one at a time to the largest remainders, ties to the earlier
     * index, never pushing a share above its weight.
     *
     * @return one share per weight, in input order, at scale 2, summing exactly to {@code total}
     * @throws IllegalArgumentException if {@code total} or any weight is negative, if
     *         {@code total} exceeds the sum of weights, or if an amount has more than two decimals
     */
    static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(weights, "weights");

        BigInteger totalCents = toCents(total, "total");
        if (totalCents.signum() < 0) {
            throw new IllegalArgumentException("total must not be negative: " + total.toPlainString());
        }

        int n = weights.size();
        BigInteger[] weightCents = new BigInteger[n];
        BigInteger weightSum = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal weight = Objects.requireNonNull(weights.get(i), "weight");
            BigInteger cents = toCents(weight, "weight");
            if (cents.signum() < 0) {
                throw new IllegalArgumentException("weight must not be negative: " + weight.toPlainString());
            }
            weightCents[i] = cents;
            weightSum = weightSum.add(cents);
        }
        if (totalCents.compareTo(weightSum) > 0) {
            throw new IllegalArgumentException("total " + total.toPlainString()
                    + " exceeds sum of weights " + new BigDecimal(weightSum, Money.SCALE).toPlainString());
        }

        BigInteger[] shares = new BigInteger[n];
        if (totalCents.signum() == 0) {
            Arrays.fill(shares, BigInteger.ZERO);
            return toMoney(shares);
        }

        // Floor shares and remainders: total * weight = share * weightSum + remainder.
        BigInteger[] remainders = new BigInteger[n];
        BigInteger leftover = totalCents;
        for (int i = 0; i < n; i++) {
            BigInteger[] quotientAndRemainder = totalCents.multiply(weightCents[i]).divideAndRemainder(weightSum);
            shares[i] = quotientAndRemainder[0];
            remainders[i] = quotientAndRemainder[1];
            leftover = leftover.subtract(shares[i]);
        }

        // Leftover is smaller than the number of entries with a positive remainder, and each
        // of those has share < weight (because total <= weightSum), so one pass is enough.
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        Comparator<Integer> byRemainderDescending = (a, b) -> remainders[b].compareTo(remainders[a]);
        order.sort(byRemainderDescending.thenComparingInt(i -> i));

        for (int i : order) {
            if (leftover.signum() == 0) {
                break;
            }
            if (shares[i].compareTo(weightCents[i]) < 0) {
                shares[i] = shares[i].add(BigInteger.ONE);
                leftover = leftover.subtract(BigInteger.ONE);
            }
        }
        if (leftover.signum() != 0) {
            throw new IllegalStateException("unallocated cents remain: " + leftover);
        }
        return toMoney(shares);
    }

    private static BigInteger toCents(BigDecimal amount, String name) {
        try {
            return amount.movePointRight(Money.SCALE).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    name + " must have at most " + Money.SCALE + " decimal places: " + amount.toPlainString(), e);
        }
    }

    private static List<BigDecimal> toMoney(BigInteger[] cents) {
        return Arrays.stream(cents).map(c -> new BigDecimal(c, Money.SCALE)).toList();
    }
}
