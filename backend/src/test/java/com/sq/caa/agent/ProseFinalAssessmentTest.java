package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The conclusion the model writes out instead of calling {@code submit_final_assessment}.
 *
 * <p>Observed on a real 8m36s run: with all twelve rules covered, the model ended its turn with a
 * paragraph containing exactly the JSON the tool wanted. The loop could only see "no tool call, no
 * conclusion", re-prompted twice ("Every rule has a verdict but no assessment was submitted") and
 * spent two more round trips getting an answer it already had.
 *
 * <p>These tests fix the boundary of the fix: the written assessment is accepted only once coverage
 * is complete, only when it really parses, and it is always visible in the trace as having arrived
 * as prose. The "coverage is still open" half of the boundary is asserted in
 * {@link RuleCoverageGuaranteeTest}.
 */
class ProseFinalAssessmentTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("a parseable assessment written as prose is accepted once every rule has a verdict, "
            + "without another round trip")
    void aWrittenAssessmentIsAcceptedWhenCoverageIsComplete() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        ScriptedChatModel model = new ScriptedChatModel(coverEverything(context,
                says("""
                        All twelve checks are done. Here is my final assessment:

                        ```json
                        {
                          "risk_level": "CRITICAL",
                          "summary": "A 25,000 wire to a bank in RU, three payments just under the \
                        10,000 reporting threshold and an unattributed XMR transfer.",
                          "recommendations": ["Escalate to the MLRO within 24 hours.", \
                        "Request source-of-funds evidence."]
                        }
                        ```
                        """)));

        AgentRunResult result = run(model, context);

        assertEquals(5, model.turns(), "the written assessment must end the run, not cost more turns");
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
        ScriptedChatModel model = new ScriptedChatModel(coverEverything(context,
                says("I have now finished reviewing every rule for this customer. The risk is high."),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"HIGH","summary":"Sanctioned wire and structuring.",\
                        "recommendations":"Escalate."}""")));

        AgentRunResult result = run(model, context);

        assertEquals(0, countSteps(trace, TraceStep.Type.PROSE_FINAL),
                "a sentence mentioning risk is not a submitted assessment");
        assertEquals(1, countSteps(trace, TraceStep.Type.REPROMPT));
        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("no assessment was submitted"));
        assertEquals(6, model.turns());
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

    /** The four fixture rules judged one per turn, followed by whatever the test appends. */
    private List<ScriptedChatModel.Turn> coverEverything(AgentRunContext context,
            ScriptedChatModel.Turn... then) {
        List<ScriptedChatModel.Turn> script = new java.util.ArrayList<>(List.of(
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(
                        AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE),
                        "Payments over 10,000 to a sanctioned jurisdiction.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(
                        AgentTestFixtures.ruleNamed(rules, STRUCTURING),
                        "Three payments of 9,000-9,999 inside a rolling day.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(
                        AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO),
                        "Crypto over 1,000 with no exchange attribution.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(
                        AgentTestFixtures.ruleNamed(rules, DECLINE_BURST),
                        "Five declined authorisations inside a rolling day."))));
        script.addAll(List.of(then));
        return script;
    }

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context) {
        AgentProperties properties = new AgentProperties(40, 3, 3, 4096, 0.1, 32768, 1536, 10,
                "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null,
                AgentTestFixtures.evaluator(context), jsonMapper, 25, 3);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

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
