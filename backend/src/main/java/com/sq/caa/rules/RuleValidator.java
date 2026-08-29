package com.sq.caa.rules;

import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Write-time semantic validation of a parsed rule.
 *
 * <p>The evaluator is deliberately forgiving - a condition it cannot resolve is false and degraded,
 * never an exception. That forgiveness is exactly why writes must be strict: a rule saved with a
 * typo would otherwise sit in the table forever, quietly never matching. Everything checkable up
 * front is therefore checked here and rejected with the pointer of the offending node.
 *
 * <p>"Quietly never matching" is the whole point, so the checks go beyond well-formedness. A rule is
 * also refused when it is <em>structurally incapable of firing</em>:
 * <ul>
 *   <li>a leaf reading a field of another activity type than the rule's own scope, which resolves to
 *       "not applicable" on every transaction the rule will ever see;
 *   <li>an {@code AND} whose branches need two different activity types at once, which no single
 *       transaction can satisfy;
 *   <li>a degenerate text operand - a blank {@code CONTAINS} needle matches every value, a blank
 *       {@code MATCHES} pattern matches every value - which fires the rule on all activity or none
 *       regardless of the data.
 * </ul>
 */
public final class RuleValidator {

    private RuleValidator() {
    }

    /**
     * Validates a whole rule against the scope it will be evaluated with, throwing
     * {@link RuleValidationException} on the first problem.
     *
     * @param scope the rule's {@code applies_to}; {@code null} is read as {@link RuleScope#ALL}
     */
    public static void validate(RuleNode node, RuleScope scope) {
        validateNode(node, "$", scope == null ? RuleScope.ALL : scope);
    }

    /** Validates a rule with no scope restriction, i.e. as if it were {@code ALL}-scoped. */
    public static void validate(RuleNode node) {
        validate(node, RuleScope.ALL);
    }

    private static void validateNode(RuleNode node, String path, RuleScope scope) {
        switch (node) {
            case RuleGroup group -> {
                if (group.conditions().isEmpty()) {
                    throw fail(path, node, "group '" + group.op() + "' needs at least one child condition");
                }
                int index = 0;
                for (RuleNode child : group.conditions()) {
                    validateNode(child, path + ".conditions[" + index + "]", scope);
                    index++;
                }
                validateSatisfiable(group, path);
            }
            case RuleCondition condition -> validateCondition(condition, path, scope);
        }
    }

    private static void validateCondition(RuleCondition condition, String path, RuleScope scope) {
        FieldDefinition definition = FieldCatalog.find(condition.field())
                .orElseThrow(() -> fail(path, condition,
                        "unknown field '" + condition.field() + "'" + suggestion(condition.field())));

        if (!definition.availableIn(scope)) {
            throw fail(path, condition, "field '" + definition.field() + "' exists only on "
                    + definition.appliesTo() + " activity, but this rule is scoped to " + scope
                    + ", so the condition could never match; use a field available on " + scope
                    + " activity, or set the rule scope to " + definition.appliesTo() + " or ALL");
        }

        RuleOperator operator = condition.operator();
        if (!definition.type().supports(operator)) {
            throw fail(path, condition, "operator " + operator + " is not valid for "
                    + definition.type().name().toLowerCase(Locale.ROOT) + " field '" + definition.field()
                    + "'; allowed operators are " + names(definition.allowedOperators()));
        }

        Object value = condition.value();
        switch (operator.arity()) {
            case NONE -> {
                if (value != null) {
                    throw fail(path, condition, "operator " + operator + " takes no value");
                }
            }
            case SINGLE -> {
                if (value == null) {
                    throw fail(path, condition, "operator " + operator + " requires a value");
                }
                if (Values.isList(value)) {
                    throw fail(path, condition, "operator " + operator + " requires a single value, not an array");
                }
                validateOperand(value, definition, condition, path);
            }
            case PAIR -> {
                if (!Values.isList(value) || Values.asList(value).size() != 2) {
                    throw fail(path, condition,
                            "operator BETWEEN requires an array of exactly two values");
                }
                List<Object> bounds = Values.asList(value);
                for (Object bound : bounds) {
                    if (bound == null) {
                        throw fail(path, condition, "operator BETWEEN does not accept null bounds");
                    }
                    validateOperand(bound, definition, condition, path);
                }
                validateBoundOrder(bounds, definition, condition, path);
            }
            case LIST -> {
                if (!Values.isList(value)) {
                    throw fail(path, condition, "operator " + operator + " requires an array of values");
                }
                List<Object> elements = Values.asList(value);
                if (elements.isEmpty()) {
                    throw fail(path, condition, "operator " + operator + " requires a non-empty array");
                }
                for (Object element : elements) {
                    if (element == null) {
                        throw fail(path, condition, "operator " + operator + " does not accept null elements");
                    }
                    validateOperand(element, definition, condition, path);
                }
            }
        }

        switch (operator) {
            case CONTAINS, NOT_CONTAINS -> validateNeedle(value, operator, condition, path);
            case MATCHES -> validateRegex(value, condition, path);
            default -> {
                // No operand-shape rule beyond the type checks above.
            }
        }
    }

    /**
     * Rejects an {@code AND} that cannot be true for any transaction because its branches need two
     * different activity types at once. Only reachable for {@code ALL}-scoped rules: on a narrower
     * scope the offending leaf is already refused by {@link FieldDefinition#availableIn(RuleScope)}.
     */
    private static void validateSatisfiable(RuleGroup group, String path) {
        if (group.op() != LogicalOp.AND) {
            return;
        }
        Set<RuleScope> required = requiredScopes(group);
        if (required.size() > 1) {
            throw fail(path, group, "these conditions require "
                    + String.join(" and ", required.stream().map(Enum::name).sorted().toList())
                    + " activity on the same transaction, which cannot happen - a transaction has "
                    + "exactly one activity type, so this group could never match");
        }
    }

    /**
     * Activity types a node needs in order to be able to hold at all.
     *
     * <p>A leaf on an activity-specific field reads as "not applicable" - and therefore false - on
     * every other activity type, so it requires its own. {@code IS_NULL} is the exception: absence is
     * exactly what it accepts. {@code AND} needs all of its children's requirements, {@code OR} only
     * what all of its branches require in common, and {@code NOT} requires nothing because it holds
     * precisely where its body does not.
     */
    private static Set<RuleScope> requiredScopes(RuleNode node) {
        return switch (node) {
            case RuleCondition condition -> {
                FieldDefinition definition = FieldCatalog.find(condition.field()).orElse(null);
                if (condition.operator() == RuleOperator.IS_NULL || definition == null
                        || definition.appliesTo() == RuleScope.ALL) {
                    yield Set.of();
                }
                yield EnumSet.of(definition.appliesTo());
            }
            case RuleGroup group -> switch (group.op()) {
                case AND -> {
                    Set<RuleScope> union = EnumSet.noneOf(RuleScope.class);
                    for (RuleNode child : group.conditions()) {
                        union.addAll(requiredScopes(child));
                    }
                    yield union;
                }
                case OR -> {
                    Set<RuleScope> common = null;
                    for (RuleNode child : group.conditions()) {
                        Set<RuleScope> childScopes = requiredScopes(child);
                        if (common == null) {
                            common = EnumSet.noneOf(RuleScope.class);
                            common.addAll(childScopes);
                        } else {
                            common.retainAll(childScopes);
                        }
                        if (common.isEmpty()) {
                            break;
                        }
                    }
                    yield common == null ? Set.of() : common;
                }
                case NOT -> Set.of();
            };
        };
    }

    private static void validateOperand(Object operand, FieldDefinition definition,
            RuleCondition condition, String path) {
        switch (definition.type()) {
            case NUMBER -> {
                if (Values.toDecimal(operand).isEmpty()) {
                    throw fail(path, condition, "value " + RuleFormatter.value(operand)
                            + " is not a number, but '" + definition.field() + "' is numeric");
                }
            }
            case DATETIME -> {
                if (Values.toInstant(operand).isEmpty()) {
                    throw fail(path, condition, "value " + RuleFormatter.value(operand)
                            + " is not an ISO-8601 date or date-time, but '" + definition.field()
                            + "' is a timestamp");
                }
            }
            case BOOLEAN -> {
                if (Values.toBoolean(operand).isEmpty()) {
                    throw fail(path, condition, "value " + RuleFormatter.value(operand)
                            + " is not a boolean, but '" + definition.field() + "' is a boolean");
                }
            }
            case ENUM -> {
                if (definition.optionsClosed() && !matchesOption(definition, operand)) {
                    throw fail(path, condition, "value " + RuleFormatter.value(operand)
                            + " is not one of the allowed values of '" + definition.field() + "': "
                            + String.join(", ", definition.options()));
                }
            }
            case STRING -> {
                if (Values.isList(operand)) {
                    throw fail(path, condition, "value of '" + definition.field()
                            + "' must be a single text value");
                }
            }
        }
    }

    private static void validateBoundOrder(List<Object> bounds, FieldDefinition definition,
            RuleCondition condition, String path) {
        if (definition.type() == FieldType.NUMBER) {
            Optional<BigDecimal> low = Values.toDecimal(bounds.get(0));
            Optional<BigDecimal> high = Values.toDecimal(bounds.get(1));
            if (low.isPresent() && high.isPresent() && low.get().compareTo(high.get()) > 0) {
                throw fail(path, condition, "BETWEEN bounds are inverted: "
                        + RuleFormatter.value(bounds.get(0)) + " is greater than "
                        + RuleFormatter.value(bounds.get(1)));
            }
        } else if (definition.type() == FieldType.DATETIME) {
            Optional<Instant> low = Values.toInstant(bounds.get(0));
            Optional<Instant> high = Values.toInstant(bounds.get(1));
            if (low.isPresent() && high.isPresent() && low.get().isAfter(high.get())) {
                throw fail(path, condition, "BETWEEN bounds are inverted: "
                        + RuleFormatter.value(bounds.get(0)) + " is after "
                        + RuleFormatter.value(bounds.get(1)));
            }
        }
    }

    /**
     * A {@code CONTAINS} / {@code NOT_CONTAINS} needle must carry information. A blank one is
     * contained in every string, so it does not filter anything: it fires the rule on all activity or
     * on none, whichever way round the operator points, and it is invisible in the rendered rule.
     */
    private static void validateNeedle(Object value, RuleOperator operator, RuleCondition condition,
            String path) {
        String needle = Values.toText(value);
        if (needle == null || needle.isBlank()) {
            throw fail(path, condition, "operator " + operator + " requires a non-blank text value; "
                    + "every value contains a blank needle, so this condition would "
                    + (operator == RuleOperator.CONTAINS ? "match every" : "never match a")
                    + " transaction of '" + condition.field() + "' regardless of the data");
        }
    }

    /** Bounded by {@link Regexes}: non-blank, at most {@code MAX_PATTERN_LENGTH}, and compilable. */
    private static void validateRegex(Object value, RuleCondition condition, String path) {
        if (!(value instanceof String regex)) {
            throw fail(path, condition, "operator MATCHES requires a regular expression string");
        }
        Optional<Regexes.Outcome> rejection = Regexes.reject(regex);
        if (rejection.isEmpty()) {
            return;
        }
        throw switch (rejection.get()) {
            case BLANK -> fail(path, condition, "operator MATCHES requires a non-blank regular "
                    + "expression; an empty pattern matches every value, and a whitespace-only one "
                    + "is almost always a paste accident - write it explicitly, e.g. '\\s{2,}'");
            case TOO_LONG -> fail(path, condition, "regular expression is " + regex.length()
                    + " characters long, the maximum is " + Regexes.MAX_PATTERN_LENGTH);
            default -> fail(path, condition,
                    "value is not a valid regular expression: " + syntaxProblem(regex));
        };
    }

    private static String syntaxProblem(String regex) {
        try {
            Pattern.compile(regex);
            return "the pattern was refused";
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    private static boolean matchesOption(FieldDefinition definition, Object operand) {
        String text = Values.toText(operand);
        if (text == null) {
            return false;
        }
        for (String option : definition.options()) {
            if (option.equalsIgnoreCase(text.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String suggestion(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String needle = field.trim().toLowerCase(Locale.ROOT);
        String bare = needle.contains(".") ? needle.substring(needle.lastIndexOf('.') + 1) : needle;
        for (String candidate : FieldCatalog.fieldNames()) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.equals(needle) || lower.endsWith("." + bare) || lower.equals(bare)) {
                return "; did you mean '" + candidate + "'?";
            }
        }
        return "; see GET /api/rules/field-catalog for the available fields";
    }

    private static String names(List<RuleOperator> operators) {
        return operators.stream().map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static RuleValidationException fail(String path, RuleNode node, String message) {
        return new RuleValidationException(path, RuleParser.compact(node), message);
    }
}
