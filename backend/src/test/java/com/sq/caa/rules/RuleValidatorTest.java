package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Write-time validation. Every case here is a rule that would otherwise sit in the table for ever,
 * silently never matching.
 */
class RuleValidatorTest {

    private static void strict(String json) {
        RuleParser.parseStrict(json);
    }

    private static RuleValidationException failureOf(String json) {
        try {
            strict(json);
        } catch (RuleValidationException e) {
            return e;
        }
        throw new AssertionError("expected " + json + " to be rejected");
    }

    @Test
    void acceptsAWellFormedRule() {
        assertThatCode(() -> strict("""
                {"op":"AND","conditions":[
                  {"field":"amount","operator":"GT","value":10000},
                  {"field":"payment.receiver_bank_country","operator":"IN","value":["IR","KP"]}]}
                """)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnknownFieldAndSuggestsTheRealOne() {
        RuleValidationException failure =
                failureOf("{\"field\":\"receiver_bank_country\",\"operator\":\"EQ\",\"value\":\"IR\"}");
        assertThat(failure).hasMessageContaining("unknown field 'receiver_bank_country'")
                .hasMessageContaining("did you mean 'payment.receiver_bank_country'?");
    }

    @Test
    void pointsAtTheOffendingNodeInsideNestedGroups() {
        RuleValidationException failure = failureOf("""
                {"op":"OR","conditions":[
                  {"field":"amount","operator":"GT","value":1},
                  {"op":"AND","conditions":[
                     {"field":"amount","operator":"GT","value":2},
                     {"field":"nope","operator":"EQ","value":"x"}]}]}
                """);
        assertThat(failure.path()).isEqualTo("$.conditions[1].conditions[1]");
        assertThat(failure.node()).contains("\"field\":\"nope\"");
    }

    @Test
    void rejectsAnOperatorTheFieldTypeDoesNotSupport() {
        assertThat(failureOf("{\"field\":\"currency\",\"operator\":\"GT\",\"value\":\"USD\"}"))
                .hasMessageContaining("operator GT is not valid for string field 'currency'")
                .hasMessageContaining("allowed operators are EQ, NEQ, IN, NOT_IN, CONTAINS");
    }

    @Test
    void rejectsRegexOnANumericField() {
        assertThat(failureOf("{\"field\":\"amount\",\"operator\":\"MATCHES\",\"value\":\"1.*\"}"))
                .hasMessageContaining("not valid for number field 'amount'");
    }

    @Test
    void rejectsAValueOnANullCheck() {
        assertThat(failureOf(
                "{\"field\":\"card.decline_reason\",\"operator\":\"IS_NULL\",\"value\":\"x\"}"))
                .hasMessageContaining("operator IS_NULL takes no value");
    }

    @Test
    void rejectsAMissingValue() {
        assertThat(failureOf("{\"field\":\"amount\",\"operator\":\"GT\"}"))
                .hasMessageContaining("operator GT requires a value");
    }

    @Test
    void rejectsAnArrayWhereAScalarIsExpected() {
        assertThat(failureOf("{\"field\":\"amount\",\"operator\":\"GT\",\"value\":[1,2]}"))
                .hasMessageContaining("requires a single value, not an array");
    }

    @Test
    void rejectsAScalarWhereAListIsExpected() {
        assertThat(failureOf("{\"field\":\"currency\",\"operator\":\"IN\",\"value\":\"USD\"}"))
                .hasMessageContaining("operator IN requires an array of values");
    }

    @Test
    void rejectsAnEmptyList() {
        assertThat(failureOf("{\"field\":\"currency\",\"operator\":\"IN\",\"value\":[]}"))
                .hasMessageContaining("requires a non-empty array");
    }

    @Test
    void rejectsBetweenWithTheWrongNumberOfBounds() {
        assertThat(failureOf("{\"field\":\"amount\",\"operator\":\"BETWEEN\",\"value\":[1]}"))
                .hasMessageContaining("BETWEEN requires an array of exactly two values");
    }

    @Test
    void rejectsInvertedBetweenBounds() {
        assertThat(failureOf("{\"field\":\"amount\",\"operator\":\"BETWEEN\",\"value\":[9999,8000]}"))
                .hasMessageContaining("BETWEEN bounds are inverted");
    }

    @Test
    void rejectsANonNumericValueOnANumericField() {
        assertThat(failureOf("{\"field\":\"agg.tx_count_24h\",\"operator\":\"GTE\",\"value\":\"many\"}"))
                .hasMessageContaining("is not a number");
    }

    @Test
    void acceptsANumericStringOnANumericField() {
        assertThatCode(() -> strict("{\"field\":\"amount\",\"operator\":\"GT\",\"value\":\"10000\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnparseableTimestamp() {
        assertThat(failureOf("{\"field\":\"created_at\",\"operator\":\"GT\",\"value\":\"yesterday\"}"))
                .hasMessageContaining("is not an ISO-8601 date");
    }

    @Test
    void acceptsIsoDatesAndDateTimes() {
        assertThatCode(() -> {
            strict("{\"field\":\"created_at\",\"operator\":\"GT\",\"value\":\"2026-01-01\"}");
            strict("{\"field\":\"created_at\",\"operator\":\"LTE\",\"value\":\"2026-01-01T10:15:30Z\"}");
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsANonBooleanOnABooleanField() {
        assertThat(failureOf("{\"field\":\"card.card_present\",\"operator\":\"EQ\",\"value\":\"maybe\"}"))
                .hasMessageContaining("is not a boolean");
    }

    @Test
    void rejectsAValueOutsideAClosedEnum() {
        assertThat(failureOf("{\"field\":\"status\",\"operator\":\"EQ\",\"value\":\"Complete\"}"))
                .hasMessageContaining("is not one of the allowed values of 'status'")
                .hasMessageContaining("Completed, Pending, Failed, Reversed");
    }

    @Test
    void acceptsAClosedEnumValueRegardlessOfCase() {
        assertThatCode(() -> strict("{\"field\":\"status\",\"operator\":\"EQ\",\"value\":\"completed\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAValueOutsideAnOpenEndedEnum() {
        // The blockchain list is a suggestion, not an exhaustive set.
        assertThatCode(() -> strict(
                "{\"field\":\"crypto.blockchain\",\"operator\":\"EQ\",\"value\":\"ZEC\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnInvalidRegularExpression() {
        assertThat(failureOf(
                "{\"field\":\"card.merchant_name\",\"operator\":\"MATCHES\",\"value\":\"([unclosed\"}"))
                .hasMessageContaining("not a valid regular expression");
    }

    @Test
    void rejectsNullElementsInAList() {
        assertThat(failureOf("{\"field\":\"currency\",\"operator\":\"IN\",\"value\":[\"USD\",null]}"))
                .hasMessageContaining("does not accept null elements");
    }

    @Test
    void everyCatalogFieldCanBeUsedWithEveryOperatorItAdvertises() {
        for (FieldDefinition definition : FieldCatalog.entries()) {
            for (RuleOperator operator : definition.allowedOperators()) {
                RuleCondition condition = RuleCondition.of(definition.field(), operator,
                        sampleValue(definition, operator));
                assertThatCode(() -> RuleValidator.validate(condition))
                        .as("%s %s", definition.field(), operator)
                        .doesNotThrowAnyException();
            }
        }
    }

    private static Object sampleValue(FieldDefinition definition, RuleOperator operator) {
        Object scalar = switch (definition.type()) {
            case NUMBER -> new java.math.BigDecimal("1");
            case DATETIME -> "2026-01-01T00:00:00Z";
            case BOOLEAN -> Boolean.TRUE;
            case ENUM -> definition.options().isEmpty() ? "X" : definition.options().get(0);
            case STRING -> "X";
        };
        Object other = definition.type() == FieldType.NUMBER ? new java.math.BigDecimal("2")
                : definition.type() == FieldType.DATETIME ? "2026-02-01T00:00:00Z" : scalar;
        return switch (operator.arity()) {
            case NONE -> null;
            case SINGLE -> scalar;
            case PAIR -> java.util.List.of(scalar, other);
            case LIST -> java.util.List.of(scalar);
        };
    }
}
