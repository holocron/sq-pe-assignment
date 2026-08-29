package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing, canonical serialisation, and the error reporting the API contract depends on. */
class RuleParserTest {

    /** The exact document from the build spec. */
    private static final String SPEC_EXAMPLE = """
            {
              "op": "AND",
              "conditions": [
                { "field": "amount", "operator": "GT", "value": 10000 },
                { "op": "OR", "conditions": [
                    { "field": "payment.receiver_bank_country", "operator": "IN", "value": ["IR","KP","SY","RU","AF"] },
                    { "field": "customer.country", "operator": "NEQ", "value": "US" }
                ]}
              ]
            }
            """;

    @Test
    void parsesTheSpecExampleIntoTheSealedHierarchy() {
        RuleNode node = RuleParser.parse(SPEC_EXAMPLE);

        assertThat(node).isInstanceOf(RuleGroup.class);
        RuleGroup root = (RuleGroup) node;
        assertThat(root.op()).isEqualTo(LogicalOp.AND);
        assertThat(root.conditions()).hasSize(2);

        RuleCondition amount = (RuleCondition) root.conditions().get(0);
        assertThat(amount.field()).isEqualTo("amount");
        assertThat(amount.operator()).isEqualTo(RuleOperator.GT);
        assertThat(amount.value()).isEqualTo(new BigDecimal("10000"));

        RuleGroup nested = (RuleGroup) root.conditions().get(1);
        assertThat(nested.op()).isEqualTo(LogicalOp.OR);
        RuleCondition countries = (RuleCondition) nested.conditions().get(0);
        assertThat(countries.operator()).isEqualTo(RuleOperator.IN);
        assertThat(countries.value()).isEqualTo(List.of("IR", "KP", "SY", "RU", "AF"));

        assertThat(root.depth()).isEqualTo(3);
        assertThat(root.nodeCount()).isEqualTo(5);
        assertThat(root.referencedFields())
                .containsExactly("amount", "payment.receiver_bank_country", "customer.country");
    }

    @Test
    void parsesABareLeafAsTheWholeRule() {
        RuleNode node = RuleParser.parse("{\"field\":\"amount\",\"operator\":\"GTE\",\"value\":500}");
        assertThat(node).isInstanceOf(RuleCondition.class);
        assertThat(((RuleCondition) node).operator()).isEqualTo(RuleOperator.GTE);
    }

    @Test
    void canonicalisationRoundTrips() {
        RuleNode parsed = RuleParser.parse(SPEC_EXAMPLE);
        String canonical = RuleParser.toJson(parsed);

        assertThat(canonical).isEqualTo("{\"op\":\"AND\",\"conditions\":["
                + "{\"field\":\"amount\",\"operator\":\"GT\",\"value\":10000},"
                + "{\"op\":\"OR\",\"conditions\":["
                + "{\"field\":\"payment.receiver_bank_country\",\"operator\":\"IN\","
                + "\"value\":[\"IR\",\"KP\",\"SY\",\"RU\",\"AF\"]},"
                + "{\"field\":\"customer.country\",\"operator\":\"NEQ\",\"value\":\"US\"}]}]}");
        assertThat(RuleParser.parse(canonical)).isEqualTo(parsed);
    }

    @Test
    void nullChecksSerialiseWithoutAValue() {
        RuleNode node = RuleParser.parse(
                "{\"field\":\"crypto.exchange_name\",\"operator\":\"IS_NULL\",\"value\":null}");
        assertThat(RuleParser.toJson(node))
                .isEqualTo("{\"field\":\"crypto.exchange_name\",\"operator\":\"IS_NULL\"}");
    }

    @Test
    void operatorNamesAreAcceptedCaseInsensitively() {
        RuleNode node = RuleParser.parse("{\"field\":\"amount\",\"operator\":\"gte\",\"value\":1}");
        assertThat(((RuleCondition) node).operator()).isEqualTo(RuleOperator.GTE);
    }

    @Test
    void aDoubleEncodedDocumentIsStillReadable() {
        String embedded = "\"{\\\"field\\\":\\\"amount\\\",\\\"operator\\\":\\\"GT\\\",\\\"value\\\":1}\"";
        assertThatCode(() -> RuleParser.parse(RuleParser.readTree(embedded))).doesNotThrowAnyException();
    }

    @Test
    void emptyLogicIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("  "))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void invalidJsonIsRejectedWithTheRootPath() {
        assertThatThrownBy(() -> RuleParser.parse("{ not json"))
                .isInstanceOf(RuleValidationException.class)
                .satisfies(error -> assertThat(((RuleValidationException) error).path()).isEqualTo("$"))
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void aNonObjectNodeIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("[1,2,3]"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("expected a rule node object");
    }

    @Test
    void aNodeThatIsNeitherGroupNorConditionIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("{\"foo\":1}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("neither a group");
    }

    @Test
    void anUnknownGroupOperatorIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("{\"op\":\"XOR\",\"conditions\":[]}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("unknown group operator 'XOR'");
    }

    @Test
    void anEmptyGroupIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("{\"op\":\"AND\",\"conditions\":[]}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("empty 'conditions'");
    }

    @Test
    void aGroupWithoutConditionsIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("{\"op\":\"AND\"}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("requires a 'conditions' array");
    }

    @Test
    void anUnknownOperatorIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse(
                "{\"field\":\"amount\",\"operator\":\"APPROXIMATELY\",\"value\":1}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("unknown operator 'APPROXIMATELY'");
    }

    @Test
    void aConditionWithoutAFieldIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse("{\"operator\":\"GT\",\"value\":1}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("requires a 'field' string");
    }

    @Test
    void anObjectValueIsRejected() {
        assertThatThrownBy(() -> RuleParser.parse(
                "{\"field\":\"amount\",\"operator\":\"GT\",\"value\":{\"nested\":1}}"))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("'value' must be a scalar or an array");
    }

    @Test
    void theErrorNamesTheOffendingNodeByPathAndContent() {
        String json = """
                {"op":"AND","conditions":[
                  {"field":"amount","operator":"GT","value":10000},
                  {"op":"OR","conditions":[
                    {"field":"amount","operator":"NOPE","value":1}
                  ]}
                ]}
                """;
        assertThatThrownBy(() -> RuleParser.parse(json))
                .isInstanceOf(RuleValidationException.class)
                .satisfies(error -> {
                    RuleValidationException failure = (RuleValidationException) error;
                    assertThat(failure.path()).isEqualTo("$.conditions[1].conditions[0]");
                    assertThat(failure.node()).contains("\"operator\":\"NOPE\"");
                    assertThat(failure.describe())
                            .isEqualTo("Invalid rule logic at $.conditions[1].conditions[0]: unknown operator 'NOPE'");
                });
    }

    @Test
    void unknownPropertiesAreToleratedWhenReadingButRejectedWhenWriting() {
        String json = "{\"field\":\"amount\",\"operator\":\"GT\",\"value\":1,\"note\":\"why\"}";
        assertThatCode(() -> RuleParser.parse(json)).doesNotThrowAnyException();
        assertThatThrownBy(() -> RuleParser.parseStrict(json))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("unexpected property 'note'");
    }

    @Test
    void excessiveNestingIsRejected() {
        StringBuilder json = new StringBuilder();
        int depth = RuleParser.MAX_DEPTH + 1;
        for (int i = 0; i < depth; i++) {
            json.append("{\"op\":\"AND\",\"conditions\":[");
        }
        json.append("{\"field\":\"amount\",\"operator\":\"GT\",\"value\":1}");
        json.append("]}".repeat(depth));

        assertThatThrownBy(() -> RuleParser.parse(json.toString()))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("levels deep");
    }

    @Test
    void anExcessivelyLargeRuleIsRejected() {
        StringBuilder json = new StringBuilder("{\"op\":\"OR\",\"conditions\":[");
        for (int i = 0; i < RuleParser.MAX_NODES; i++) {
            json.append(i == 0 ? "" : ",").append("{\"field\":\"amount\",\"operator\":\"GT\",\"value\":")
                    .append(i).append("}");
        }
        json.append("]}");

        assertThatThrownBy(() -> RuleParser.parse(json.toString()))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("the maximum is " + RuleParser.MAX_NODES);
    }
}
