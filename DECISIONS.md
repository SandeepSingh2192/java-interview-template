# Decisions

## 1. Rule engine pattern and extensibility

How would you add a new rule type without modifying existing rule classes?

Each `RuleType` has exactly one package-private `RuleHandler` with two responsibilities: `validate` (gate 4, configuration) and `evaluate` (gate 5, predicate and discount). `DefaultPromotionEngine` owns everything shared: it sorts the rules once (priority, then id, then request order via a stable sort), runs the README gates in order, looks handlers up in an `EnumMap`, and refuses to start unless every `RuleType` has exactly one handler. Handlers are read-only: they return a `RuleOutcome` (`Skip`, or `Apply` with `LineChange`s) and the engine commits it through `CartState`, so a skipped rule never changes the cart and an exclusive group is claimed only after a successful commit. The three cart-level rules extend `CartLevelRuleHandler`, which owns eligibility (`stackableWithItemPromos`), the eligible base, the cap at the base, and pro-rata allocation; each subclass supplies only its predicate and grant. To add a rule type, add the enum constant, write one new handler class (extending `CartLevelRuleHandler` for a cart rule), and add it to the engine's handler list; no existing handler changes, and a forgotten registration fails at startup.

## 2. Precision and rounding choices

Where did you round, and why does that keep every line and the cart total at or above `0.00`?

All money is scale-2 `BigDecimal`, including line and cart totals, so nothing can overflow regardless of unit price or quantity. Percentages are rounded exactly once with `HALF_UP`: per line for `PERCENTAGE_OFF_CATEGORY`, and once on the eligible cart base for cart-level rules (`MoneyMath.percentOf`). Whenever an amount is split across lines or units (cart grants, bundle savings, capped category discounts, and spreading a line's discount over its units), `MoneyMath.allocate` uses the largest-remainder method in integer cents with ties to the earlier line or unit; it rejects a total larger than the sum of its weights and never gives a share above its weight. Every grant is capped at its base first: the line's non-bundled price for category rules, the eligible cart base for cart rules, and the consumed units' current value for bundles. `CartState.commit` then re-checks every change against the units' current value before mutating anything. Because line and cart totals are always derived from unit prices, `totalDiscount = originalSubtotal - finalTotal = sum(line discounts) = sum(APPLIED audit amounts)` holds by construction, and no unit, line, or total can drop below `0.00`.

## 3. Edge cases handled vs deferred

Which edge cases did you handle inside the 2-hour cap, and which did you deliberately leave out?

Handled and tested:
- inverted windows, and inclusive window boundaries;
- exclusive groups claimed only by APPLIED rules, with blank groups ignored;
- invalid configuration returned as SKIPPED rather than HTTP 400, and zero discounts skipped;
- bundle leftovers that stay eligible, lowest-index unit consumption, and the same SKU pooled across lines;
- BXGY price ties broken by line and then unit index, and zero-priced units;
- spend-tier downgrades on the running subtotal, and equal tier thresholds;
- non-stackable cart rules, including bundled and BXGY lines;
- one-cent remainders, and fixed amounts larger than the base;
- overflow-safe buy/free and bundle counts;
- fail-fast handler registration and rules without a type.

Where the README is silent, I chose explicit, tested interpretations:
- bundle savings use the original `unitPrice`, and each line's share is clamped to its consumed units' current value without redistribution;
- a bundle counts only non-bundled units;
- tiers and coupons require exactly one of `percentage` or `amount`;
- coupon and payment values are compared without trimming;
- an empty eligible base is reported before a coupon mismatch.

Deliberately deferred:
- unit state keeps one entry per unit, so a quantity near `Integer.MAX_VALUE` would exhaust memory;
- `null` entries inside JSON `components` or `tiers` lists reach the starter `EvaluationMapper` and produce a 500 instead of a 400;
- Rule money fields (`amount`, `bundlePrice`, and `maxDiscountAmount`) with more than two decimal places passed directly to the engine may throw; unit prices are rounded. The HTTP mapper normalizes money values before they reach the engine.
- Jackson's default float-to-int coercion for quantities is not hardened;
- duplicate rule IDs and cross-rule optimization are out of scope per the README.

## 4. AI usage note

Name one place an AI assistant accelerated you, and one place you corrected or overrode its suggestion.

AI tools were used throughout development to accelerate repository analysis, implementation, test generation, and review. I used AI to break down the requirements, explore implementation approaches, generate code and test cases, and identify edge cases. I reviewed the proposed designs, challenged decisions where necessary, and iteratively reviewed the implementation and test results before accepting changes. The final implementation was validated against the README requirements, unit tests, integration tests, and all six public fixtures. One specific correction was the initial consideration of representing money entirely as `long` cents; after reviewing overflow risks for line and cart totals, I retained `BigDecimal` for monetary values and used integer cents with `BigInteger` inside the allocation logic.
