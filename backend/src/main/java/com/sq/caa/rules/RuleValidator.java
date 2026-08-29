package com.sq.caa.rules;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Write-time semantic validation of a parsed rule.
 *
 * <p>The evaluator is deliberately forgiving - a condition it cannot resolve is false and degraded,
 * never an exception. That forgiveness is exactly why writes must be strict: a rule saved with a
 * typo would otherwise sit in the table forever, quietly never matching. Everything checkable up
 * front is therefore checked here and rejected with the pointer of the offending node.
 */
public final class RuleValidator {

    private RuleValidator() {
    }

    /** Validates a whole rule, throwing {@link RuleValidationException} on the first problem. */
    public static void validate(RuleNode node) {
        validateNode(node, "$");
    }

    private static void validateNode(RuleNode node, String path) {
        switch (node) {
            case RuleGroup group -> {
                if (group.conditions().isEmpty()) {
                    throw fail(path, node, "group '" + group.op() + "' needs at least one child condition");
                }
                int index = 0;
                for (RuleNode child : group.conditions()) {
                    validateNode(child, path + ".conditions[" + index + "]");
                    index++;
                }
            }
            case RuleCondition condition -> validateCondition(condition, path);
        }
    }

    private static void validateCondition(RuleCondition condition, String path) {
        FieldDefinition definition = FieldCatalog.find(condition.field())
                .orElseThrow(() -> fail(path, condition,
                        "unknown field '" + condition.field() + "'" + suggestion(condition.field())));

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

        if (operator == RuleOperator.MATCHES) {
            validateRegex(value, condition, path);
        }
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

    private static void validateRegex(Object value, RuleCondition condition, String path) {
        if (!(value instanceof String regex)) {
            throw fail(path, condition, "operator MATCHES requires a regular expression string");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw fail(path, condition, "value is not a valid regular expression: " + e.getDescription());
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
