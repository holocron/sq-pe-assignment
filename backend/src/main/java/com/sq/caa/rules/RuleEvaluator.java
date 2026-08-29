package com.sq.caa.rules;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic evaluator for the risk-rule DSL.
 *
 * <p>Contract, in order of importance:
 * <ol>
 *   <li><b>It never throws while scoring.</b> An unknown field, a missing value, a type mismatch or
 *       an unparseable rule all produce a false leaf and set {@code degraded}. A rule engine that
 *       can throw is a rule engine that can silently skip a rule, which is exactly what this
 *       application must be able to prove it never does.
 *   <li><b>It evaluates every branch.</b> Groups do not short-circuit, so a degradation buried in
 *       the right-hand side of an {@code AND} that already failed is still reported.
 *   <li><b>It explains itself.</b> Every node renders its own outcome, so a match carries a trace an
 *       operator can check by eye.
 * </ol>
 *
 * <p>Text comparisons ({@code EQ}, {@code NEQ}, {@code IN}, {@code NOT_IN}, {@code CONTAINS},
 * {@code NOT_CONTAINS}, {@code MATCHES}) are case-insensitive, and {@code MATCHES} is a search, not
 * a full-string match. Both choices favour the false positive over the false negative, which is the
 * asymmetric cost this domain has.
 *
 * <p>Degenerate text operands are the exception: a blank {@code CONTAINS} / {@code NOT_CONTAINS}
 * needle or {@code MATCHES} pattern would match every value or none regardless of the data, so it is
 * reported as a degraded false instead of being silently honoured. {@code MATCHES} additionally runs
 * under the length, backtracking and cache bounds of {@link Regexes}.
 *
 * <p>Stateless and thread-safe.
 */
@Component
public class RuleEvaluator {

    /** Longest per-transaction trace kept on a sample match. */
    public static final int MAX_TRACE_LENGTH = 900;

    /** Longest degradation note list carried on a result. */
    private static final int MAX_NOTES = 10;

    // ------------------------------------------------------------------
    // Rule level
    // ------------------------------------------------------------------

    /** Evaluates a stored rule, parsing its {@code threshold_logic} defensively. */
    public RuleEvaluationResult evaluate(RiskRule rule, EvaluationBatch batch) {
        RuleNode node;
        try {
            node = RuleParser.parse(rule.getThresholdLogic());
        } catch (RuleValidationException e) {
            return RuleEvaluationResult.unparseable(rule, e.describe());
        } catch (RuntimeException e) {
            return RuleEvaluationResult.unparseable(rule,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return evaluate(rule, node, batch);
    }

    /** Evaluates an already parsed rule. */
    public RuleEvaluationResult evaluate(RiskRule rule, RuleNode node, EvaluationBatch batch) {
        RuleScope scope = rule.getAppliesTo() == null ? RuleScope.ALL : rule.getAppliesTo();
        ScopedEvaluation evaluation = evaluate(node, scope, batch);

        BigDecimal weight = RuleEvaluationResult.scale(rule.getWeight());
        boolean triggered = evaluation.triggered();
        BigDecimal score = triggered ? weight : RuleEvaluationResult.zeroScore();

        List<UUID> matchedIds = evaluation.matches().stream().map(RuleMatch::transactionId).toList();
        List<RuleMatch> samples = evaluation.matches().stream()
                .sorted(Comparator.comparing(RuleMatch::amount,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RuleMatch::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RuleEvaluationResult.SAMPLE_LIMIT)
                .toList();

        String explanation = explain(rule.getRuleName(), scope, node, evaluation, weight, score, samples);

        return new RuleEvaluationResult(
                rule.getRuleId(),
                rule.getRuleName(),
                scope,
                weight,
                triggered,
                score,
                matchedIds,
                matchedIds.size(),
                evaluation.evaluatedCount(),
                evaluation.degraded(),
                evaluation.notes(),
                explanation,
                samples);
    }

    // ------------------------------------------------------------------
    // Batch level
    // ------------------------------------------------------------------

    /** Runs one node over every in-scope transaction of a batch. */
    public ScopedEvaluation evaluate(RuleNode node, RuleScope scope, EvaluationBatch batch) {
        if (node == null || batch == null) {
            return ScopedEvaluation.empty();
        }
        RuleScope effectiveScope = scope == null ? RuleScope.ALL : scope;
        List<Transaction> inScope = batch.transactionsFor(effectiveScope);
        List<RuleMatch> matches = new ArrayList<>();
        Set<String> notes = new LinkedHashSet<>(unreachableFieldNotes(node, effectiveScope));
        boolean degraded = !notes.isEmpty();

        for (Transaction transaction : inScope) {
            TransactionFacts facts = batch.factsFor(transaction);
            NodeOutcome outcome;
            if (facts == null) {
                outcome = NodeOutcome.degraded("<no resolved facts> [false]",
                        "no resolved values for transaction " + transaction.getTransactionId());
            } else {
                outcome = evaluateSafely(node, facts);
            }
            degraded = degraded || outcome.degraded();
            notes.addAll(outcome.notes());
            if (outcome.matched()) {
                matches.add(toMatch(transaction, batch.customer(), outcome.explanation()));
            }
        }
        return new ScopedEvaluation(inScope.size(), matches, degraded, limit(notes));
    }

    /**
     * Fields the rule reads that cannot exist on the activity type it is scoped to.
     *
     * <p>Writes are refused by {@link RuleValidator}, but a rule stored before that check - or
     * written straight into {@code risk_rules} - would otherwise report "did not trigger" for ever,
     * which is indistinguishable from a customer with nothing to find. Reported once per rule, not
     * once per transaction, and only for a narrowed scope: an {@code ALL}-scoped rule reaching into
     * one activity type is ordinary and stays clean.
     */
    private static Set<String> unreachableFieldNotes(RuleNode node, RuleScope scope) {
        if (scope == RuleScope.ALL) {
            return Set.of();
        }
        Set<String> notes = new LinkedHashSet<>();
        for (String field : node.referencedFields()) {
            FieldCatalog.find(field)
                    .filter(definition -> !definition.availableIn(scope))
                    .ifPresent(definition -> notes.add("'" + field + "' exists only on "
                            + definition.appliesTo() + " activity, so it can never resolve on a "
                            + scope + " rule"));
        }
        return notes;
    }

    private NodeOutcome evaluateSafely(RuleNode node, TransactionFacts facts) {
        try {
            return evaluateNode(node, facts);
        } catch (RuntimeException e) {
            // Unreachable by design; kept so that a future field resolver bug degrades a single
            // transaction instead of aborting a whole analysis.
            return NodeOutcome.degraded("<evaluation error> [false]",
                    "evaluation error: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Node level
    // ------------------------------------------------------------------

    /** Evaluates one node against one transaction's resolved facts. */
    public NodeOutcome evaluateNode(RuleNode node, TransactionFacts facts) {
        return switch (node) {
            case RuleGroup group -> evaluateGroup(group, facts);
            case RuleCondition condition -> evaluateCondition(condition, facts);
        };
    }

    private NodeOutcome evaluateGroup(RuleGroup group, TransactionFacts facts) {
        List<NodeOutcome> outcomes = new ArrayList<>(group.conditions().size());
        for (RuleNode child : group.conditions()) {
            // Deliberately not short-circuiting: a degraded branch must be reported even when the
            // group's verdict is already decided.
            outcomes.add(evaluateNode(child, facts));
        }
        boolean allMatched = outcomes.stream().allMatch(NodeOutcome::matched);
        boolean anyMatched = outcomes.stream().anyMatch(NodeOutcome::matched);
        boolean matched = switch (group.op()) {
            case AND -> allMatched;
            case OR -> anyMatched;
            case NOT -> !allMatched;
        };
        boolean degraded = outcomes.stream().anyMatch(NodeOutcome::degraded);

        String joiner = group.op() == LogicalOp.OR ? " OR " : " AND ";
        StringJoiner body = new StringJoiner(joiner, "(", ")");
        for (NodeOutcome outcome : outcomes) {
            body.add(outcome.explanation());
        }
        String explanation = (group.op() == LogicalOp.NOT ? "NOT " + body : body.toString())
                + " [" + matched + "]";
        return new NodeOutcome(matched, degraded, explanation, NodeOutcome.merge(outcomes));
    }

    private NodeOutcome evaluateCondition(RuleCondition condition, TransactionFacts facts) {
        FieldLookup lookup = facts.lookup(condition.field());
        RuleOperator operator = condition.operator();

        return switch (lookup.status()) {
            case UNKNOWN_FIELD -> NodeOutcome.degraded(
                    trace(condition, "<unknown field>", false, "unknown field"),
                    "unknown field '" + condition.field() + "'");
            case NOT_APPLICABLE -> {
                String actual = "<not on " + activityName(facts) + ">";
                if (operator == RuleOperator.IS_NULL) {
                    yield NodeOutcome.of(true, trace(condition, actual, true, "field absent on this activity type"));
                }
                if (operator == RuleOperator.NOT_NULL) {
                    yield NodeOutcome.of(false, trace(condition, actual, false, "field absent on this activity type"));
                }
                // Expected, structural non-match: an ALL-scoped rule reaching a field of another
                // activity type is normal and must not pollute the degraded flag.
                yield NodeOutcome.of(false, trace(condition, actual, false, "field absent on this activity type"));
            }
            case NULL_VALUE -> {
                if (operator == RuleOperator.IS_NULL) {
                    yield NodeOutcome.of(true, trace(condition, "null", true, null));
                }
                if (operator == RuleOperator.NOT_NULL) {
                    yield NodeOutcome.of(false, trace(condition, "null", false, null));
                }
                yield NodeOutcome.degraded(trace(condition, "null", false, "value is null"),
                        "'" + condition.field() + "' has no value on at least one transaction");
            }
            case RESOLVED -> {
                Object actual = lookup.value();
                if (operator == RuleOperator.IS_NULL) {
                    yield NodeOutcome.of(false, trace(condition, RuleFormatter.value(actual), false, null));
                }
                if (operator == RuleOperator.NOT_NULL) {
                    yield NodeOutcome.of(true, trace(condition, RuleFormatter.value(actual), true, null));
                }
                yield compare(condition, actual);
            }
        };
    }

    // ------------------------------------------------------------------
    // Comparisons
    // ------------------------------------------------------------------

    private NodeOutcome compare(RuleCondition condition, Object actual) {
        if (actual instanceof BigDecimal number) {
            return compareNumber(condition, number);
        }
        if (actual instanceof Number number) {
            return compareNumber(condition, new BigDecimal(number.toString()));
        }
        if (actual instanceof Instant instant) {
            return compareInstant(condition, instant);
        }
        if (actual instanceof Boolean bool) {
            return compareBoolean(condition, bool);
        }
        return compareText(condition, String.valueOf(actual));
    }

    private NodeOutcome compareNumber(RuleCondition condition, BigDecimal actual) {
        RuleOperator operator = condition.operator();
        Object value = condition.value();
        String rendered = RuleFormatter.value(actual);

        return switch (operator) {
            case GT, GTE, LT, LTE, EQ, NEQ -> {
                Optional<BigDecimal> operand = Values.toDecimal(value);
                if (operand.isEmpty()) {
                    yield mismatch(condition, rendered, "expected a number, got " + RuleFormatter.value(value));
                }
                int comparison = actual.compareTo(operand.get());
                boolean matched = switch (operator) {
                    case GT -> comparison > 0;
                    case GTE -> comparison >= 0;
                    case LT -> comparison < 0;
                    case LTE -> comparison <= 0;
                    case EQ -> comparison == 0;
                    default -> comparison != 0;
                };
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            case BETWEEN -> {
                List<Object> bounds = Values.asList(value);
                if (bounds.size() != 2) {
                    yield mismatch(condition, rendered, "BETWEEN needs exactly two bounds");
                }
                Optional<BigDecimal> low = Values.toDecimal(bounds.get(0));
                Optional<BigDecimal> high = Values.toDecimal(bounds.get(1));
                if (low.isEmpty() || high.isEmpty()) {
                    yield mismatch(condition, rendered, "BETWEEN bounds are not numbers");
                }
                boolean matched = actual.compareTo(low.get()) >= 0 && actual.compareTo(high.get()) <= 0;
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            case IN, NOT_IN -> {
                List<Object> elements = Values.asList(value);
                if (elements.isEmpty()) {
                    yield mismatch(condition, rendered, operator + " needs a non-empty list");
                }
                boolean found = false;
                boolean anyComparable = false;
                for (Object element : elements) {
                    Optional<BigDecimal> operand = Values.toDecimal(element);
                    if (operand.isEmpty()) {
                        continue;
                    }
                    anyComparable = true;
                    if (actual.compareTo(operand.get()) == 0) {
                        found = true;
                    }
                }
                if (!anyComparable) {
                    yield mismatch(condition, rendered, "list holds no numbers to compare against");
                }
                boolean matched = operator == RuleOperator.IN ? found : !found;
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            default -> mismatch(condition, rendered,
                    operator + " is not defined for the numeric field '" + condition.field() + "'");
        };
    }

    private NodeOutcome compareInstant(RuleCondition condition, Instant actual) {
        RuleOperator operator = condition.operator();
        Object value = condition.value();
        String rendered = RuleFormatter.value(actual);

        return switch (operator) {
            case GT, GTE, LT, LTE, EQ, NEQ -> {
                Optional<Instant> operand = Values.toInstant(value);
                if (operand.isEmpty()) {
                    yield mismatch(condition, rendered,
                            "expected an ISO-8601 timestamp, got " + RuleFormatter.value(value));
                }
                int comparison = actual.compareTo(operand.get());
                boolean matched = switch (operator) {
                    case GT -> comparison > 0;
                    case GTE -> comparison >= 0;
                    case LT -> comparison < 0;
                    case LTE -> comparison <= 0;
                    case EQ -> comparison == 0;
                    default -> comparison != 0;
                };
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            case BETWEEN -> {
                List<Object> bounds = Values.asList(value);
                if (bounds.size() != 2) {
                    yield mismatch(condition, rendered, "BETWEEN needs exactly two bounds");
                }
                Optional<Instant> low = Values.toInstant(bounds.get(0));
                Optional<Instant> high = Values.toInstant(bounds.get(1));
                if (low.isEmpty() || high.isEmpty()) {
                    yield mismatch(condition, rendered, "BETWEEN bounds are not timestamps");
                }
                boolean matched = !actual.isBefore(low.get()) && !actual.isAfter(high.get());
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            default -> mismatch(condition, rendered,
                    operator + " is not defined for the timestamp field '" + condition.field() + "'");
        };
    }

    private NodeOutcome compareBoolean(RuleCondition condition, boolean actual) {
        RuleOperator operator = condition.operator();
        String rendered = String.valueOf(actual);
        if (operator != RuleOperator.EQ && operator != RuleOperator.NEQ) {
            return mismatch(condition, rendered,
                    operator + " is not defined for the boolean field '" + condition.field() + "'");
        }
        Optional<Boolean> operand = Values.toBoolean(condition.value());
        if (operand.isEmpty()) {
            return mismatch(condition, rendered,
                    "expected a boolean, got " + RuleFormatter.value(condition.value()));
        }
        boolean equal = actual == operand.get();
        boolean matched = operator == RuleOperator.EQ ? equal : !equal;
        return NodeOutcome.of(matched, trace(condition, rendered, matched, null));
    }

    private NodeOutcome compareText(RuleCondition condition, String actual) {
        RuleOperator operator = condition.operator();
        Object value = condition.value();
        String rendered = RuleFormatter.value(actual);
        String lower = actual.toLowerCase(Locale.ROOT);

        return switch (operator) {
            case EQ, NEQ -> {
                String operand = Values.toText(value);
                if (operand == null) {
                    yield mismatch(condition, rendered, "expected a text value");
                }
                boolean equal = actual.equalsIgnoreCase(operand.trim());
                boolean matched = operator == RuleOperator.EQ ? equal : !equal;
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            case IN, NOT_IN -> {
                List<Object> elements = Values.asList(value);
                if (elements.isEmpty()) {
                    yield mismatch(condition, rendered, operator + " needs a non-empty list");
                }
                boolean found = false;
                for (Object element : elements) {
                    String operand = Values.toText(element);
                    if (operand != null && actual.equalsIgnoreCase(operand.trim())) {
                        found = true;
                        break;
                    }
                }
                boolean matched = operator == RuleOperator.IN ? found : !found;
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            case CONTAINS, NOT_CONTAINS -> {
                // A blank needle is degenerate, not a filter: every string contains "" and none is
                // missing it, so accepting one would silently fire the rule on every transaction
                // (CONTAINS) or on none (NOT_CONTAINS). Writes reject it; stored rules that predate
                // that check degrade here rather than lying. Blank elements of a list are skipped,
                // mirroring how the numeric IN branch skips values it cannot compare.
                boolean found = false;
                boolean anyUsable = false;
                for (Object needle : Values.asList(value)) {
                    String operand = Values.toText(needle);
                    if (operand == null || operand.isBlank()) {
                        continue;
                    }
                    anyUsable = true;
                    if (lower.contains(operand.trim().toLowerCase(Locale.ROOT))) {
                        found = true;
                        break;
                    }
                }
                if (!anyUsable) {
                    yield mismatch(condition, rendered, operator + " needs a non-blank text value");
                }
                boolean matched = operator == RuleOperator.CONTAINS ? found : !found;
                yield NodeOutcome.of(matched, trace(condition, rendered, matched, null));
            }
            case MATCHES -> {
                String regex = Values.toText(value);
                yield switch (Regexes.search(regex, actual)) {
                    case MATCH -> NodeOutcome.of(true, trace(condition, rendered, true, null));
                    case NO_MATCH -> NodeOutcome.of(false, trace(condition, rendered, false, null));
                    case BLANK -> mismatch(condition, rendered,
                            "MATCHES needs a non-blank regular expression");
                    case TOO_LONG -> mismatch(condition, rendered, "regular expression is longer than "
                            + Regexes.MAX_PATTERN_LENGTH + " characters");
                    case INVALID -> mismatch(condition, rendered,
                            "'" + regex + "' is not a valid regular expression");
                    case BUDGET_EXCEEDED -> mismatch(condition, rendered,
                            "regular expression was abandoned after " + Regexes.MAX_MATCH_STEPS
                                    + " steps without deciding");
                };
            }
            default -> mismatch(condition, rendered,
                    operator + " is not defined for the text field '" + condition.field() + "'");
        };
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private NodeOutcome mismatch(RuleCondition condition, String actual, String reason) {
        return NodeOutcome.degraded(trace(condition, actual, false, reason),
                "'" + condition.field() + "': " + reason);
    }

    private static String trace(RuleCondition condition, String actual, boolean matched, String reason) {
        StringBuilder text = new StringBuilder()
                .append(condition.field()).append('=').append(actual)
                .append(' ').append(condition.operator().name());
        if (!condition.operator().isNullCheck()) {
            text.append(' ').append(RuleFormatter.value(condition.value()));
        }
        text.append(" [").append(matched);
        if (reason != null) {
            text.append(": ").append(reason);
        }
        return text.append(']').toString();
    }

    private static String activityName(TransactionFacts facts) {
        return facts.activityType() == null ? "this transaction" : facts.activityType().name();
    }

    private static RuleMatch toMatch(Transaction transaction, Customer customer, String explanation) {
        return new RuleMatch(
                transaction.getTransactionId(),
                customer == null ? null : customer.getCustomerId(),
                customer == null ? null : customer.getFullName(),
                transaction.getActivityType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                RuleFormatter.abbreviate(explanation, MAX_TRACE_LENGTH));
    }

    private static List<String> limit(Set<String> notes) {
        return notes.stream().limit(MAX_NOTES).toList();
    }

    private static String explain(String ruleName, RuleScope scope, RuleNode node,
            ScopedEvaluation evaluation, BigDecimal weight, BigDecimal score, List<RuleMatch> samples) {
        String name = ruleName == null ? "(unnamed rule)" : ruleName;
        String scopeWord = scope == null || scope == RuleScope.ALL ? "" : scope.name() + " ";
        StringBuilder text = new StringBuilder();

        if (evaluation.evaluatedCount() == 0) {
            text.append("Rule '").append(name).append("' did not trigger: the customer has no ")
                    .append(scopeWord.isEmpty() ? "transactions" : scopeWord + "transactions")
                    .append(" to evaluate.");
        } else if (evaluation.triggered()) {
            text.append("Rule '").append(name).append("' triggered on ")
                    .append(evaluation.matchedCount()).append(" of ").append(evaluation.evaluatedCount())
                    .append(' ').append(scopeWord).append("transaction(s), scoring ")
                    .append(score.toPlainString()).append(" of the rule weight ")
                    .append(weight.toPlainString()).append('.');
            if (!samples.isEmpty()) {
                RuleMatch top = samples.get(0);
                text.append(" Largest match: transaction ").append(top.transactionId())
                        .append(top.createdAt() == null ? "" : " at " + top.createdAt())
                        .append(top.amount() == null ? "" : " for " + top.amount().toPlainString()
                                + (top.currency() == null ? "" : " " + top.currency()))
                        .append(" - ").append(top.explanation());
            }
        } else {
            text.append("Rule '").append(name).append("' did not trigger: none of ")
                    .append(evaluation.evaluatedCount()).append(' ').append(scopeWord)
                    .append("transaction(s) satisfied ").append(RuleFormatter.describe(node)).append('.');
        }

        if (evaluation.degraded()) {
            text.append(" Degraded: ").append(String.join("; ", evaluation.notes())).append('.');
        }
        return text.toString();
    }
}
