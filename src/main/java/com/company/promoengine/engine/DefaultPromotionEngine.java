package com.company.promoengine.engine;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.RuleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates a cart in one deterministic pass over the promotions.
 *
 * <p>Rules are sorted once (priority, then id, then request order). Each rule goes through
 * the README gates in order and produces exactly one audit entry. Handlers only propose
 * changes; this class commits them, so a skipped rule never changes the cart.
 */
@Component
public class DefaultPromotionEngine implements PromotionEngine {

    /**
     * Priority ascending, then id ascending ({@link String#compareTo}). {@link List#sort}
     * is stable, so rules equal on both keep their request order (the final tie-breaker).
     */
    private static final Comparator<PromotionRule> EVALUATION_ORDER =
            Comparator.comparingInt(PromotionRule::priority).thenComparing(PromotionRule::id);

    private final Map<RuleType, RuleHandler> handlers = new EnumMap<>(RuleType.class);

    /** The Spring bean constructor: one handler per rule type. */
    public DefaultPromotionEngine() {
        this(List.of(
                new CategoryPercentageHandler(),
                new BuyXGetCheapestFreeHandler(),
                new BundleFixedPriceHandler(),
                new TieredSpendHandler(),
                new CartPercentageHandler(),
                new CouponPaymentHandler()));
    }

    /**
     * Registers the given handlers and fails fast unless every {@link RuleType} has exactly
     * one. Package-private so tests can check the registry rules.
     *
     * @throws IllegalStateException if a rule type has no handler or more than one
     */
    DefaultPromotionEngine(List<RuleHandler> ruleHandlers) {
        ruleHandlers.forEach(this::register);
        Set<RuleType> missing = EnumSet.allOf(RuleType.class);
        missing.removeAll(handlers.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No handler registered for " + missing);
        }
    }

    private void register(RuleHandler handler) {
        if (handlers.putIfAbsent(handler.type(), handler) != null) {
            throw new IllegalStateException("Duplicate handler for " + handler.type());
        }
    }

    @Override
    public EvaluationResult evaluate(Cart cart, List<PromotionRule> promotions) {
        Objects.requireNonNull(cart, "cart");
        Objects.requireNonNull(promotions, "promotions");
        // Checked up front so a rule without a type fails clearly, whichever gate it would reach.
        for (PromotionRule rule : promotions) {
            Objects.requireNonNull(rule, "promotion rule must not be null");
            Objects.requireNonNull(rule.type(), () -> "promotion rule " + rule.id() + " has no type");
        }

        CartState state = new CartState(cart);
        Set<String> claimedGroups = new HashSet<>();
        List<AuditEntry> auditTrail = new ArrayList<>();
        for (PromotionRule rule : inEvaluationOrder(promotions)) {
            auditTrail.add(evaluateRule(rule, state, claimedGroups));
        }
        return state.toResult(auditTrail);
    }

    private static List<PromotionRule> inEvaluationOrder(List<PromotionRule> promotions) {
        List<PromotionRule> sorted = new ArrayList<>(promotions);
        sorted.sort(EVALUATION_ORDER);
        return sorted;
    }

    /** Runs the gates in README order; the first one that fails decides the outcome. */
    private AuditEntry evaluateRule(PromotionRule rule, CartState state, Set<String> claimedGroups) {
        Instant now = state.cart().evaluationTimestamp();

        // Gate 1: inverted window.
        if (rule.validFrom().isAfter(rule.validTo())) {
            return skipped(rule, "validity window is inverted (validFrom " + rule.validFrom()
                    + " is after validTo " + rule.validTo() + ")");
        }
        // Gate 2: validity window, inclusive at both ends.
        if (now.isBefore(rule.validFrom()) || now.isAfter(rule.validTo())) {
            return skipped(rule, "evaluationTimestamp " + now + " outside validity window ["
                    + rule.validFrom() + ", " + rule.validTo() + "]");
        }
        // Gate 3: exclusive group already claimed by an applied rule.
        String group = exclusiveGroup(rule);
        if (group != null && claimedGroups.contains(group)) {
            return skipped(rule, "exclusive group " + group + " already claimed by an earlier rule");
        }

        // The constructor guarantees a handler for every rule type.
        RuleHandler handler = handlers.get(rule.type());

        // Gate 4: type-specific configuration.
        Optional<String> invalid = handler.validate(rule);
        if (invalid.isPresent()) {
            return skipped(rule, "invalid configuration: " + invalid.get());
        }

        // Gate 5 (predicate) and the proposal; the handler does not change the cart.
        RuleOutcome outcome = handler.evaluate(rule, state);
        return switch (outcome) {
            case RuleOutcome.Skip skip -> skipped(rule, skip.detail());
            case RuleOutcome.Apply apply -> {
                BigDecimal discount = apply.totalDiscount();
                // Gate 6: zero discount.
                if (discount.signum() == 0) {
                    yield skipped(rule, "computed discount is 0.00");
                }
                // Gate 7: commit, then claim the group.
                state.commit(apply.changes(), handler.itemLevel());
                if (group != null) {
                    claimedGroups.add(group);
                }
                yield applied(rule, discount, apply.detail());
            }
        };
    }

    /** The rule's exclusive group, or {@code null} when it has none (null or blank). */
    private static String exclusiveGroup(PromotionRule rule) {
        String group = rule.exclusiveGroup();
        return group == null || group.isBlank() ? null : group;
    }

    private static AuditEntry skipped(PromotionRule rule, String detail) {
        return new AuditEntry(rule.id(), rule.name(), rule.type(), RuleStatus.SKIPPED, Money.ZERO,
                "Skipped: " + detail);
    }

    private static AuditEntry applied(PromotionRule rule, BigDecimal discount, String detail) {
        return new AuditEntry(rule.id(), rule.name(), rule.type(), RuleStatus.APPLIED, discount,
                "Applied: " + detail);
    }
}
