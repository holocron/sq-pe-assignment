package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sq.caa.domain.RuleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Write-time validation. Every case here is a rule that would otherwise sit in the table for ever,
 * silently never matching.
 */
class RuleValidatorTest {

    private static void strict(String json) {
        RuleParser.parseStrict(json);
    }

    private static void strict(String json, RuleScope scope) {
        RuleParser.parseStrict(json, scope);
    }

    private static RuleValidationException failureOf(String json) {
        return failureOf(json, RuleScope.ALL);
    }

    private static RuleValidationException failureOf(String json, RuleScope scope) {
        try {
            strict(json, scope);
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
    @DisplayName("an operator the field type cannot support is refused and the bad node is named")
    void namesTheNodeWhoseOperatorDoesNotFitTheFieldType() {
        // The editor can silently rewrite a condition's operator when its field changes; whatever
        // reaches the API, an operator the type does not define must never be stored.
        RuleValidationException failure = failureOf("""
                {"op":"AND","conditions":[
                  {"field":"amount","operator":"GT","value":10000},
                  {"field":"card.card_present","operator":"CONTAINS","value":"true"}]}
                """, RuleScope.CARD);

        assertThat(failure.path()).isEqualTo("$.conditions[1]");
        assertThat(failure.node()).contains("\"field\":\"card.card_present\"").contains("CONTAINS");
        assertThat(failure.describe())
                .contains("Invalid rule logic at $.conditions[1]")
                .contains("operator CONTAINS is not valid for boolean field 'card.card_present'")
                .contains("allowed operators are EQ, NEQ, IS_NULL, NOT_NULL");
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

    @Nested
    @DisplayName("a rule that could never fire is not a rule")
    class Satisfiability {

        @Test
        @DisplayName("a field of another activity type is refused, not silently dead")
        void rejectsAFieldOutsideTheRuleScope() {
            RuleValidationException failure = failureOf(
                    "{\"field\":\"payment.payment_method\",\"operator\":\"EQ\",\"value\":\"SWIFT\"}",
                    RuleScope.CARD);

            assertThat(failure).hasMessageContaining("field 'payment.payment_method' exists only on "
                            + "PAYMENT activity, but this rule is scoped to CARD")
                    .hasMessageContaining("could never match")
                    .hasMessageContaining("set the rule scope to PAYMENT or ALL");
            assertThat(failure.path()).isEqualTo("$");
        }

        @Test
        void namesTheOffendingLeafInsideAGroup() {
            RuleValidationException failure = failureOf("""
                    {"op":"AND","conditions":[
                      {"field":"amount","operator":"GT","value":1000},
                      {"field":"crypto.blockchain","operator":"EQ","value":"XMR"}]}
                    """, RuleScope.CARD);

            assertThat(failure.path()).isEqualTo("$.conditions[1]");
            assertThat(failure.node()).contains("crypto.blockchain");
        }

        @Test
        void acceptsAFieldOfTheRulesOwnScopeAndTheSharedFields() {
            assertThatCode(() -> strict("""
                    {"op":"AND","conditions":[
                      {"field":"card.mcc_code","operator":"IN","value":["7995"]},
                      {"field":"amount","operator":"GT","value":1000},
                      {"field":"agg.tx_count_24h","operator":"GTE","value":3}]}
                    """, RuleScope.CARD)).doesNotThrowAnyException();
        }

        @Test
        void anAllScopedRuleMayStillReachIntoOneActivityType() {
            assertThatCode(() -> strict(
                    "{\"field\":\"payment.payment_method\",\"operator\":\"EQ\",\"value\":\"SWIFT\"}",
                    RuleScope.ALL)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an AND that needs two activity types at once cannot hold for any transaction")
        void rejectsAnAndAcrossTwoActivityTypes() {
            RuleValidationException failure = failureOf("""
                    {"op":"AND","conditions":[
                      {"field":"card.mcc_code","operator":"EQ","value":"7995"},
                      {"field":"payment.payment_method","operator":"EQ","value":"SWIFT"}]}
                    """, RuleScope.ALL);

            assertThat(failure).hasMessageContaining("require CARD and PAYMENT activity on the same "
                            + "transaction")
                    .hasMessageContaining("could never match");
            assertThat(failure.path()).isEqualTo("$");
        }

        @Test
        void rejectsTheConflictEvenWhenItIsNested() {
            RuleValidationException failure = failureOf("""
                    {"op":"OR","conditions":[
                      {"field":"amount","operator":"GT","value":100000},
                      {"op":"AND","conditions":[
                        {"field":"crypto.blockchain","operator":"EQ","value":"XMR"},
                        {"field":"card.card_present","operator":"EQ","value":false}]}]}
                    """, RuleScope.ALL);

            assertThat(failure.path()).isEqualTo("$.conditions[1]");
            assertThat(failure).hasMessageContaining("CARD and CRYPTO activity")
                    .hasMessageContaining("could never match");
        }

        @Test
        @DisplayName("OR across activity types is legitimate and stays accepted")
        void acceptsAnOrAcrossActivityTypes() {
            assertThatCode(() -> strict("""
                    {"op":"AND","conditions":[
                      {"field":"amount","operator":"GT","value":10000},
                      {"op":"OR","conditions":[
                        {"field":"card.mcc_code","operator":"EQ","value":"7995"},
                        {"field":"payment.payment_method","operator":"EQ","value":"SWIFT"}]}]}
                    """, RuleScope.ALL)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("IS_NULL is satisfied by absence, so it does not bind the group to a type")
        void isNullDoesNotConflict() {
            assertThatCode(() -> strict("""
                    {"op":"AND","conditions":[
                      {"field":"card.decline_reason","operator":"IS_NULL"},
                      {"field":"payment.payment_method","operator":"EQ","value":"SWIFT"}]}
                    """, RuleScope.ALL)).doesNotThrowAnyException();
        }

        @Test
        void aNegatedBranchImposesNoActivityRequirement() {
            assertThatCode(() -> strict("""
                    {"op":"AND","conditions":[
                      {"op":"NOT","conditions":[
                        {"field":"card.card_present","operator":"EQ","value":true}]},
                      {"field":"payment.payment_method","operator":"EQ","value":"SWIFT"}]}
                    """, RuleScope.ALL)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("degenerate text operands")
    class DegenerateOperands {

        @Test
        @DisplayName("a whitespace-only CONTAINS needle would match every transaction")
        void rejectsAWhitespaceOnlyContainsNeedle() {
            assertThat(failureOf(
                    "{\"field\":\"card.merchant_name\",\"operator\":\"CONTAINS\",\"value\":\"   \"}"))
                    .hasMessageContaining("operator CONTAINS requires a non-blank text value")
                    .hasMessageContaining("match every");
        }

        @Test
        void rejectsAnEmptyContainsNeedle() {
            assertThat(failureOf(
                    "{\"field\":\"card.merchant_name\",\"operator\":\"CONTAINS\",\"value\":\"\"}"))
                    .hasMessageContaining("requires a non-blank text value");
        }

        @Test
        @DisplayName("the mirror case, NOT_CONTAINS, would never match")
        void rejectsABlankNotContainsNeedle() {
            assertThat(failureOf(
                    "{\"field\":\"card.merchant_name\",\"operator\":\"NOT_CONTAINS\",\"value\":\" \"}"))
                    .hasMessageContaining("operator NOT_CONTAINS requires a non-blank text value")
                    .hasMessageContaining("never match");
        }

        @Test
        void acceptsANeedleThatIsBlankOnlyAtTheEdges() {
            assertThatCode(() -> strict(
                    "{\"field\":\"card.merchant_name\",\"operator\":\"CONTAINS\",\"value\":\" casino \"}"))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsABlankRegularExpression() {
            assertThat(failureOf(
                    "{\"field\":\"card.merchant_name\",\"operator\":\"MATCHES\",\"value\":\"\"}"))
                    .hasMessageContaining("operator MATCHES requires a non-blank regular expression")
                    .hasMessageContaining("matches every value");
        }

        @Test
        @DisplayName("a regular expression longer than the cap is refused on write")
        void rejectsAnOverlongRegularExpression() {
            String regex = "a".repeat(Regexes.MAX_PATTERN_LENGTH + 1);
            assertThat(failureOf("{\"field\":\"card.merchant_name\",\"operator\":\"MATCHES\",\"value\":\""
                    + regex + "\"}"))
                    .hasMessageContaining("regular expression is " + regex.length() + " characters long")
                    .hasMessageContaining("the maximum is " + Regexes.MAX_PATTERN_LENGTH);
        }

        @Test
        void acceptsARegularExpressionAtTheCap() {
            String regex = "a".repeat(Regexes.MAX_PATTERN_LENGTH);
            assertThatCode(() -> strict("{\"field\":\"card.merchant_name\",\"operator\":\"MATCHES\","
                    + "\"value\":\"" + regex + "\"}")).doesNotThrowAnyException();
        }
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
