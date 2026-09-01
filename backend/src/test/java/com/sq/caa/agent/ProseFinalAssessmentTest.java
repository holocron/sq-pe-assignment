package com.sq.caa.agent;

import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conclusion the model writes out instead of calling {@code submit_final_assessment}.
 *
 * <p>Observed on a real 8m36s run: with all twelve rules covered, the model ended its turn with a
 * paragraph containing exactly the JSON the tool wanted. The loop could only see "no tool call, no
 * conclusion", re-prompted twice ("Every rule has a verdict but no assessment was submitted") and
 * spent two more round trips getting an answer it already had.
 *
 * <p>Under the orchestrator this lives in the closing summary conversation, which is the only
 * conversation that may conclude at all: the written assessment is accepted there when it really
 * parses, the band rule applies to it exactly as to a tool call, and it is always visible in the
 * trace as having arrived as prose. The coverage half of the boundary is now structural - a run
 * with an unjudged rule never reaches the summary conversation (see {@link RuleCoverageGateTest}).
 */
class ProseFinalAssessmentTest {

    private final tools.jackson.databind.json.JsonMapper jsonMapper =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("a parseable assessment written as prose is accepted by the closing conversation "
            + "without another round trip")
    void aWrittenAssessmentIsAcceptedWhenCoverageIsComplete() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        RoutedChatModel model = AgentTestFixtures.coveringModel(rules,
                says("""
                        All checks are done. Here is my final assessment:

                        ```json
                        {
                          "risk_level": "CRITICAL",
                          "summary": "A 25,000 wire to a bank in RU, three payments just under the \
                        10,000 reporting threshold and an unattributed XMR transfer.",
                          "recommendations": ["Escalate to the MLRO within 24 hours.", \
                        "Request source-of-funds evidence."]
                        }
                        ```
                        """));

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2));

        assertEquals(1, model.calls(RoutedChatModel.SUMMARY_MARKER),
                "the written assessment must end the conversation, not cost more turns");
        assertEquals(0, countSteps(trace, TraceStep.Type.REPROMPT),
                "the loop must not ask for an assessment it has already been given");
        assertEquals(1, countSteps(trace, TraceStep.Type.PROSE_FINAL),
                "the trace must record that the assessment arrived as prose rather than via the tool");
        assertTrue(stepTexts(trace, TraceStep.Type.PROSE_FINAL)
                        .contains("as prose instead of calling submit_final_assessment"));

        assertEquals(RiskLevel.CRITICAL, result.agentRiskLevel());
        assertTrue(result.summary().contains("just under the 10,000 reporting threshold"));
        assertNotNull(result.recommendations());
        assertTrue(result.recommendations().contains("Escalate to the MLRO"));
        assertTrue(result.recommendations().contains("source-of-funds"),
                "a list of recommendations must survive as one line each");

        // The band is still derived from the rule scores, exactly as when the tool is used - and a
        // band written into a paragraph is subject to the same rule as one passed to the tool. This
        // message asked for CRITICAL without saying why, so the escalation is not admissible and the
        // mechanical band stands. The prose path has no tool to refuse it; the loop refuses it.
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(RiskLevel.HIGH, result.mechanicalRiskLevel());
        assertFalse(result.escalated(), "an escalation with no justification is not an escalation");
        assertNull(result.escalationJustification());
        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesJudged());
    }

    @Test
    @DisplayName("prose that is not an assessment still costs a reprompt, exactly as before")
    void proseWithoutAnAssessmentIsStillReprompted() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        RoutedChatModel model = new RoutedChatModel();
        for (RiskRule rule : rules) {
            model.route(rule.getRuleId().toString(), List.of(
                    calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(rule,
                            "The activity this rule's condition names."))));
        }
        model.summary(List.of(
                says("I have now finished reviewing every rule for this customer. The risk is high."),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                        "{\"risk_level\":\"HIGH\",\"summary\":\"Sanctioned wire and structuring.\","
                                + "\"recommendations\":\"Escalate.\"}")));

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2));

        assertEquals(0, countSteps(trace, TraceStep.Type.PROSE_FINAL),
                "a sentence mentioning risk is not a submitted assessment");
        assertEquals(1, countSteps(trace, TraceStep.Type.REPROMPT));
        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("No assessment was submitted"));
        assertEquals(2, model.calls(RoutedChatModel.SUMMARY_MARKER));
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
    }

    @Test
    @DisplayName("the parser accepts the shapes a model really produces and refuses the rest")
    void theParserAcceptsRealShapesAndRefusesTheRest() {
        assertNull(FinalAssessmentParser.parse(null, jsonMapper));
        assertNull(FinalAssessmentParser.parse("   ", jsonMapper));
        assertNull(FinalAssessmentParser.parse("The customer is HIGH risk. I recommend escalation.",
                jsonMapper), "prose alone is not parseable and must still be re-prompted");
        assertNull(FinalAssessmentParser.parse("{\"summary\":\"No level here.\"}", jsonMapper),
                "without a risk level there is no assessment");
        assertNull(FinalAssessmentParser.parse("{\"risk_level\":\"HIGH\"}", jsonMapper),
                "without a summary there is no assessment");
        assertNull(FinalAssessmentParser.parse("{\"risk_level\":\"SEVERE\",\"summary\":\"x\"}", jsonMapper),
                "an invented band is not a band");
        assertNull(FinalAssessmentParser.parse("{\"risk_level\":\"HIGH\",\"summary\":", jsonMapper),
                "a truncated object must not be half-accepted");

        FinalAssessment camel = FinalAssessmentParser.parse(
                "Done: {\"riskLevel\":\"medium\",\"summary\":\"Some concern.\"}", jsonMapper);
        assertNotNull(camel);
        assertEquals(RiskLevel.MEDIUM, camel.riskLevel());
        assertEquals("Some concern.", camel.summary());
        assertNull(camel.recommendations());

        FinalAssessment nested = FinalAssessmentParser.parse("""
                I will call the tool now:
                {"name":"submit_final_assessment","arguments":{"risk_level":"CRITICAL",
                 "summary":"Structuring across {three} payments.","recommendations":"File a SAR."}}""",
                jsonMapper);
        assertNotNull(nested, "the model often wraps the arguments in a tool-call envelope");
        assertEquals(RiskLevel.CRITICAL, nested.riskLevel());
        assertEquals("Structuring across {three} payments.", nested.summary(),
                "a brace inside the summary must not truncate it");
        assertEquals("File a SAR.", nested.recommendations());
    }

    // ------------------------------------------------------------------

    private static long countSteps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(step -> type.equals(step.type())).count();
    }

    private static String stepTexts(AnalysisTrace trace, String type) {
        return trace.steps().stream()
                .filter(step -> type.equals(step.type()))
                .map(TraceStep::text)
                .collect(Collectors.joining("\n"));
    }
}
