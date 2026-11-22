package com.company.promoengine.engine;

import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * The logic for one {@link RuleType}. To add a rule type, implement this interface and
 * register the handler in {@link DefaultPromotionEngine}.
 *
 * <p>The engine runs the shared gates (validity window, exclusive group) first, then calls
 * {@link #validate}, and only if that passes, {@link #evaluate}. Handlers never change
 * the cart: they return a proposal and the engine commits it.
 */
interface RuleHandler {

    RuleType type();

    /**
     * {@code true} for item-level rules: lines they actually discount become
     * "item-discounted", which non-stackable cart rules later exclude.
     */
    boolean itemLevel();

    /**
     * Gate 4: checks the type-specific configuration.
     *
     * @return a description of the first problem found, or empty if the rule is valid
     */
    Optional<String> validate(PromotionRule rule);

    /**
     * Gate 5 and the discount calculation. Called only for valid rules.
     * Must only read {@code cart}; never call its mutating methods.
     */
    RuleOutcome evaluate(PromotionRule rule, CartState cart);

    // ---- shared configuration checks (README "Configuration Validation Rules") ----

    /** {@code > 0} and {@code <= 100}. */
    static boolean isValidPercentage(BigDecimal percentage) {
        return percentage != null
                && percentage.signum() > 0
                && percentage.compareTo(Money.ONE_HUNDRED) <= 0;
    }

    /** {@code > 0}. */
    static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Percent for reasons: {@code 10} not {@code 10.00} or {@code 1E+1}; {@code 12.5} stays {@code 12.5}. */
    static String formatPercentage(BigDecimal percentage) {
        return percentage.stripTrailingZeros().toPlainString();
    }
}
