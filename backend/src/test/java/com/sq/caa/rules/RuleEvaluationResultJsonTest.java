package com.sq.caa.rules;

import static com.sq.caa.rules.RuleTestFixtures.batch;
import static com.sq.caa.rules.RuleTestFixtures.payment;
import static com.sq.caa.rules.RuleTestFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

import com.sq.caa.domain.RuleScope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The serialised shape of a rule verdict is a contract: the agent returns it from
 * {@code evaluate_rule_deterministically} and the analysis page renders it. Pin it.
 */
class RuleEvaluationResultJsonTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void serialisesWithTheDocumentedFields() {
        RuleEvaluationResult result = new RuleEvaluator().evaluate(
                rule("Sanctioned wire", RuleScope.PAYMENT, """
                        {"op":"AND","conditions":[
                          {"field":"amount","operator":"GT","value":10000},
                          {"field":"payment.receiver_bank_country","operator":"IN","value":["IR"]}]}
                        """, "30.00"),
                batch(payment("15000.00", "Completed", Instant.parse("2026-08-20T12:00:00Z"), "SWIFT", "IR")));

        JsonNode json = MAPPER.valueToTree(result);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "ruleId", "ruleName", "appliesTo", "weight", "triggered", "score",
                "matchedTransactionIds", "matchedCount", "evaluatedTransactionCount",
                "degraded", "degradationNotes", "explanation", "sampleMatches");

        assertThat(json.get("triggered").booleanValue()).isTrue();
        assertThat(json.get("score").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(json.get("appliesTo").stringValue()).isEqualTo("PAYMENT");
        assertThat(json.get("matchedTransactionIds").size()).isEqualTo(1);
        assertThat(json.get("degraded").booleanValue()).isFalse();

        JsonNode sample = json.get("sampleMatches").get(0);
        assertThat(sample.propertyNames()).containsExactlyInAnyOrder(
                "transactionId", "customerId", "customerName", "activityType", "amount", "currency",
                "status", "createdAt", "explanation");
        assertThat(sample.get("activityType").stringValue()).isEqualTo("PAYMENT");
    }
}
