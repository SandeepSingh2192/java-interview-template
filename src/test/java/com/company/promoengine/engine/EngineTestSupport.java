package com.company.promoengine.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.promoengine.domain.AuditEntry;
import com.company.promoengine.domain.BundleComponent;
import com.company.promoengine.domain.Cart;
import com.company.promoengine.domain.CartItem;
import com.company.promoengine.domain.DiscountConfig;
import com.company.promoengine.domain.EvaluationResult;
import com.company.promoengine.domain.ItemizedLine;
import com.company.promoengine.domain.Money;
import com.company.promoengine.domain.PromotionRule;
import com.company.promoengine.domain.RuleConditions;
import com.company.promoengine.domain.RuleStatus;
import com.company.promoengine.domain.RuleType;
import com.company.promoengine.domain.SpendTier;
import com.company.promoengine.domain.UserTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** Builders and shared accounting-invariant checks for engine-package tests. */
final class EngineTestSupport {

    static final Instant NOW = Instant.parse("2026-03-15T10:00:00Z");
    static final Instant WINDOW_START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant WINDOW_END = Instant.parse("2026-12-31T23:59:59Z");

    private EngineTestSupport() {
    }

    // ---- values ----------------------------------------------------------------

    static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    static CartItem item(String sku, String category, String unitPrice, int quantity) {
        return new CartItem(sku, category, money(unitPrice), quantity);
    }

    static Cart cart(CartItem... items) {
        return cart(List.of(items));
    }

    static Cart cart(List<CartItem> items) {
        return cartWith(null, null, items.toArray(CartItem[]::new));
    }

    /** A cart carrying a coupon code and payment method ({@code null} for none). */
    static Cart cartWith(String couponCode, String paymentMethod, CartItem... items) {
        return new Cart("cart-1", "INR", NOW, UserTier.STANDARD, List.of(items), couponCode, paymentMethod);
    }

    static List<BigDecimal> unitPrices(LineState line) {
        return IntStream.range(0, line.quantity()).mapToObj(line::currentUnitPrice).toList();
    }

    // ---- rules -----------------------------------------------------------------

    static RuleBuilder rule(String id, RuleType type) {
        return new RuleBuilder(id, type);
    }

    static RuleBuilder categoryRule(String id, String category, String percentage) {
        return rule(id, RuleType.PERCENTAGE_OFF_CATEGORY).category(category).percentage(percentage);
    }

    static RuleBuilder buyXGetCheapestFreeRule(String id, Integer buy, Integer free, String... skus) {
        return rule(id, RuleType.BUY_X_GET_CHEAPEST_FREE).buyQuantity(buy).freeQuantity(free).skus(skus);
    }

    static RuleBuilder bundleRule(String id, String bundlePrice, BundleComponent... components) {
        return rule(id, RuleType.BUNDLE_FIXED_PRICE).bundlePrice(bundlePrice).components(components);
    }

    static BundleComponent component(String sku, int quantity) {
        return new BundleComponent(sku, quantity);
    }

    static RuleBuilder couponRule(String id, String couponCode) {
        return rule(id, RuleType.COUPON_PAYMENT_OFF).couponCode(couponCode);
    }

    static RuleBuilder cartPercentageRule(String id, String percentage) {
        return rule(id, RuleType.CART_PERCENTAGE_OFF).percentage(percentage);
    }

    static RuleBuilder tieredRule(String id, SpendTier... tiers) {
        return rule(id, RuleType.TIERED_SPEND).tiers(tiers);
    }

    static SpendTier amountTier(String minSubtotal, String amount) {
        return tier(minSubtotal, null, amount);
    }

    static SpendTier percentTier(String minSubtotal, String percentage) {
        return tier(minSubtotal, percentage, null);
    }

    /** Any combination, including invalid ones; {@code null} leaves a field unset. */
    static SpendTier tier(String minSubtotal, String percentage, String amount) {
        return new SpendTier(
                minSubtotal == null ? null : new BigDecimal(minSubtotal),
                percentage == null ? null : new BigDecimal(percentage),
                amount == null ? null : new BigDecimal(amount));
    }

    /** Defaults: name = id, priority 1, no group, window covering {@link #NOW}. */
    static final class RuleBuilder {
        private final String id;
        private final RuleType type;
        private String name;
        private int priority = 1;
        private String exclusiveGroup;
        private Boolean stackableWithItemPromos;
        private Instant validFrom = WINDOW_START;
        private Instant validTo = WINDOW_END;
        private String category;
        private String couponCode;
        private String paymentMethod;
        private List<String> skus;
        private BigDecimal percentage;
        private BigDecimal amount;
        private BigDecimal maxDiscountAmount;
        private Integer buyQuantity;
        private Integer freeQuantity;
        private List<BundleComponent> components;
        private BigDecimal bundlePrice;
        private List<SpendTier> tiers;
        private boolean withoutConditions;
        private boolean withoutDiscountConfig;

        private RuleBuilder(String id, RuleType type) {
            this.id = id;
            this.type = type;
        }

        RuleBuilder name(String value) {
            this.name = value;
            return this;
        }

        RuleBuilder priority(int value) {
            this.priority = value;
            return this;
        }

        RuleBuilder group(String value) {
            this.exclusiveGroup = value;
            return this;
        }

        RuleBuilder window(Instant from, Instant to) {
            this.validFrom = from;
            this.validTo = to;
            return this;
        }

        RuleBuilder category(String value) {
            this.category = value;
            return this;
        }

        /** {@code null} means "no skus"; note RuleConditions rejects null entries. */
        RuleBuilder skus(String... values) {
            this.skus = values == null ? null : List.of(values);
            return this;
        }

        RuleBuilder buyQuantity(Integer value) {
            this.buyQuantity = value;
            return this;
        }

        RuleBuilder freeQuantity(Integer value) {
            this.freeQuantity = value;
            return this;
        }

        /** {@code null} means "no components". */
        RuleBuilder components(BundleComponent... values) {
            this.components = values == null ? null : List.of(values);
            return this;
        }

        /** {@code null} means "no tiers". */
        RuleBuilder tiers(SpendTier... values) {
            this.tiers = values == null ? null : List.of(values);
            return this;
        }

        RuleBuilder stackable(Boolean value) {
            this.stackableWithItemPromos = value;
            return this;
        }

        RuleBuilder bundlePrice(String value) {
            this.bundlePrice = value == null ? null : new BigDecimal(value);
            return this;
        }

        RuleBuilder couponCode(String value) {
            this.couponCode = value;
            return this;
        }

        RuleBuilder paymentMethod(String value) {
            this.paymentMethod = value;
            return this;
        }

        RuleBuilder amount(String value) {
            this.amount = value == null ? null : new BigDecimal(value);
            return this;
        }

        RuleBuilder percentage(String value) {
            this.percentage = value == null ? null : new BigDecimal(value);
            return this;
        }

        RuleBuilder maxDiscount(String value) {
            this.maxDiscountAmount = value == null ? null : new BigDecimal(value);
            return this;
        }

        RuleBuilder withoutConditions() {
            this.withoutConditions = true;
            return this;
        }

        RuleBuilder withoutDiscountConfig() {
            this.withoutDiscountConfig = true;
            return this;
        }

        PromotionRule build() {
            RuleConditions conditions = withoutConditions
                    ? null
                    : new RuleConditions(category, skus, couponCode, paymentMethod, components);
            DiscountConfig discountConfig = withoutDiscountConfig
                    ? null
                    : new DiscountConfig(percentage, amount, buyQuantity, freeQuantity, maxDiscountAmount,
                            bundlePrice, tiers);
            return new PromotionRule(id, name == null ? id : name, type, priority, exclusiveGroup,
                    stackableWithItemPromos, validFrom, validTo, conditions, discountConfig);
        }
    }

    // ---- running the engine ----------------------------------------------------

    /** Evaluates with a fresh engine and checks every accounting invariant on the result. */
    static EvaluationResult evaluate(Cart cart, PromotionRule... rules) {
        EvaluationResult result = new DefaultPromotionEngine().evaluate(cart, List.of(rules));
        assertInvariants(result, rules.length);
        return result;
    }

    // ---- invariants ------------------------------------------------------------

    /** Line and total invariants (everything except the audit trail). */
    static void assertTotalsConsistent(EvaluationResult result) {
        BigDecimal sumOriginal = Money.ZERO;
        BigDecimal sumFinal = Money.ZERO;
        BigDecimal sumDiscount = Money.ZERO;
        for (ItemizedLine line : result.itemizedBreakdown()) {
            assertScaleTwo(line.unitPrice());
            assertScaleTwo(line.originalExtendedPrice());
            assertScaleTwo(line.discount());
            assertScaleTwo(line.finalExtendedPrice());
            assertThat(line.finalExtendedPrice().signum()).as("final line price >= 0").isGreaterThanOrEqualTo(0);
            assertThat(line.discount()).isEqualTo(line.originalExtendedPrice().subtract(line.finalExtendedPrice()));
            sumOriginal = sumOriginal.add(line.originalExtendedPrice());
            sumFinal = sumFinal.add(line.finalExtendedPrice());
            sumDiscount = sumDiscount.add(line.discount());
        }
        assertScaleTwo(result.originalSubtotal());
        assertScaleTwo(result.totalDiscount());
        assertScaleTwo(result.finalTotal());
        assertThat(result.originalSubtotal()).isEqualTo(sumOriginal);
        assertThat(result.finalTotal()).isEqualTo(sumFinal);
        assertThat(result.finalTotal().signum()).as("final total >= 0").isGreaterThanOrEqualTo(0);
        assertThat(result.totalDiscount()).isEqualTo(result.originalSubtotal().subtract(result.finalTotal()));
        assertThat(result.totalDiscount()).isEqualTo(sumDiscount);
    }

    /** All README accounting invariants, including the audit trail. */
    static void assertInvariants(EvaluationResult result, int ruleCount) {
        assertTotalsConsistent(result);
        assertThat(result.auditTrail()).as("one audit entry per rule").hasSize(ruleCount);
        BigDecimal sumApplied = Money.ZERO;
        for (AuditEntry entry : result.auditTrail()) {
            assertThat(entry.reason()).as("reason of " + entry.ruleId()).isNotBlank();
            assertScaleTwo(entry.discountAmount());
            if (entry.status() == RuleStatus.SKIPPED) {
                assertThat(entry.discountAmount()).isEqualTo(Money.ZERO);
                assertThat(entry.reason()).startsWith("Skipped: ");
            } else {
                assertThat(entry.discountAmount().signum()).isPositive();
                assertThat(entry.reason()).startsWith("Applied: ");
                sumApplied = sumApplied.add(entry.discountAmount());
            }
        }
        assertThat(result.totalDiscount()).as("totalDiscount == sum of APPLIED audit amounts").isEqualTo(sumApplied);
    }

    private static void assertScaleTwo(BigDecimal value) {
        assertThat(value.scale()).as("scale of " + value.toPlainString()).isEqualTo(Money.SCALE);
    }
}
