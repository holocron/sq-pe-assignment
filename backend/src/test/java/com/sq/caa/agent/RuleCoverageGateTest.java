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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rule-coverage gate: the loop must not be able to finish while a rule is unevaluated, and the
 * run must end at 100% coverage whatever the model does.
 *
 * <p>Everything here runs against a scripted {@link ScriptedChatModel} that deliberately misbehaves,
 * so the gate is exercised as control flow rather than hoped for. No Spring context, no database and
 * no language model are involved.
 */
class RuleCoverageGateTest {

    private static final int MAX_COVERAGE_REPROMPTS = 3;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("a model that tries to finish early is re-prompted and the run still ends at 100% coverage")
    void gateBlocksAnEarlyFinishAndTheBackfillCompletesCoverage() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                // 1. look at the checklist
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                // 2. rule one out of four
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, verdict(sanctioned, true, 30,
                        "Wire of 25,000 to a bank in RU.")),
                // 3. try to conclude with three rules still open - the gate must reject this
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                        """
                        {"risk_level":"HIGH","summary":"Sanctioned-jurisdiction wire found.",\
                        "recommendations":"File a report."}"""),
                // 4. give up and answer in prose instead - the gate must reject this too
                says("My analysis is complete; the customer is high risk."),
                // 5. grudgingly rule on one more, and get it wrong
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, verdict(structuring, false, 0,
                        "The payments look ordinary to me.")),
                // 6. and 7. stop answering, burning the reprompt budget
                says("Nothing else to add."),
                says("Truly done.")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        AgentRunResult result = run(model, context);

        // --- coverage is complete no matter what the model did -------------
        assertEquals(4, result.rulesTotal());
        assertEquals(4, result.ruleOutcomes().size(), "every applicable rule must end with a verdict");
        assertEquals(4, outcomes(result).size());
        assertFalse(result.coverageComplete(),
                "the agent skipped rules, so coverage_complete must record that the backfill was needed");
        assertEquals(2, result.rulesEvaluatedByAgent());
        assertEquals(2, result.rulesBackfilled());

        // --- the gate actually fired ---------------------------------------
        assertEquals(4, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT),
                "the gate must record a coverage_reprompt every time the model tried to stop early");
        assertNull(result.agentRiskLevel(),
                "submit_final_assessment was rejected, so the agent never concluded");

        // --- and the reprompt named the rules that were actually missing ----
        String reprompts = String.join("\n", model.userMessages());
        assertTrue(reprompts.contains(UNATTRIBUTED_CRYPTO), "the reprompt must name the missing rule");
        assertTrue(reprompts.contains(crypto.getRuleId().toString()),
                "the reprompt must give the missing rule's id so the model can act on it");
        assertTrue(reprompts.contains(DECLINE_BURST));

        // --- sources ---------------------------------------------------------
        Map<String, RuleOutcome> byName = outcomes(result);
        assertEquals(RuleVerdictSource.AGENT, byName.get(SANCTIONED_WIRE).source());
        assertEquals(RuleVerdictSource.AGENT, byName.get(STRUCTURING).source());
        assertEquals(RuleVerdictSource.DETERMINISTIC_FALLBACK, byName.get(UNATTRIBUTED_CRYPTO).source());
        assertEquals(RuleVerdictSource.DETERMINISTIC_FALLBACK, byName.get(DECLINE_BURST).source());
        assertEquals(2, countSteps(trace, TraceStep.Type.BACKFILL));

        // --- the deterministic engine wins the disagreement -------------------
        RuleOutcome structuringOutcome = byName.get(STRUCTURING);
        assertTrue(structuringOutcome.disagreement(), "the agent said not triggered, the engine said triggered");
        assertEquals(Boolean.FALSE, structuringOutcome.agentTriggered());
        assertTrue(structuringOutcome.triggered(), "the deterministic verdict is what stands");
        assertEquals(0, new BigDecimal("20.00").compareTo(structuringOutcome.score()));
        assertEquals(1, result.disagreementCount());
        assertEquals(1, countSteps(trace, TraceStep.Type.DISAGREEMENT));

        // --- scoring is purely deterministic ---------------------------------
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertFalse(byName.get(DECLINE_BURST).triggered());
        assertEquals(0, BigDecimal.ZERO.compareTo(byName.get(DECLINE_BURST).score()));
        assertEquals(1, countSteps(trace, TraceStep.Type.FINAL));

        // sanity: the loop really did keep going instead of exiting at turn 3
        assertTrue(model.turns() >= 7, "the loop must not have exited when the model first tried to");
    }

    @Test
    @DisplayName("a model that evaluates every rule and concludes is let through untouched")
    void agentThatCoversEverythingIsNotReprompted() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE), true, 30, "RU wire.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, STRUCTURING), true, 20, "Three near-threshold payments.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO), true, 15, "XMR, no exchange.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, DECLINE_BURST), false, 0, "No declines on file.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"CRITICAL","summary":"Sanctioned wire, structuring and an \
                        unattributed privacy-coin transfer.","recommendations":"Escalate to the MLRO."}""")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules));

        assertTrue(result.coverageComplete(), "the agent covered every rule by itself");
        assertEquals(4, result.rulesEvaluatedByAgent());
        assertEquals(0, result.rulesBackfilled());
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT));
        assertEquals(0, countSteps(trace, TraceStep.Type.BACKFILL));
        assertEquals(0, result.disagreementCount());
        assertEquals(6, model.turns(), "the loop must stop as soon as the assessment is accepted");

        assertEquals(RiskLevel.CRITICAL, result.agentRiskLevel());
        // The band is re-derived from the deterministic score, so it can differ from the agent's.
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertTrue(result.summary().contains("Sanctioned wire"));
        assertTrue(result.recommendations().contains("MLRO"));
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.AGENT));
    }

    @Test
    @DisplayName("a model that never uses a tool still produces a fully covered, fully scored run")
    void modelThatNeverEvaluatesAnythingIsBackfilledEntirely() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                says("This customer looks fine to me."),
                says("I said it looks fine."),
                says("Still fine."),
                says("Fine.")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules));

        assertEquals(4, result.ruleOutcomes().size(), "coverage must still be 100%");
        assertEquals(0, result.rulesEvaluatedByAgent());
        assertEquals(4, result.rulesBackfilled());
        assertFalse(result.coverageComplete());
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.DETERMINISTIC_FALLBACK));

        // The gate fires on every turn and the budget bounds how long it keeps trying.
        assertEquals(MAX_COVERAGE_REPROMPTS + 1, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT));
        assertEquals(MAX_COVERAGE_REPROMPTS + 1, model.turns());

        // A missing narrative does not cost the run its score.
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertNull(result.agentRiskLevel());
        assertTrue(result.summary().contains("deterministic rule engine"));
        assertNotNull(result.recommendations());
    }

    @Test
    @DisplayName("the trace renders in the published shape")
    void traceMatchesThePublishedShape() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                says("Done.")));
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules));

        var document = trace.toJson();
        assertTrue(document.has("steps"));
        var steps = document.get("steps");
        assertTrue(steps.isArray() && !steps.isEmpty());
        assertEquals(1, steps.get(0).get("n").asInt());
        assertEquals(TraceStep.Type.STARTED, steps.get(0).get("type").stringValue());

        var toolStep = firstStepOfType(document, TraceStep.Type.TOOL_CALL);
        assertEquals(RiskAgentTools.LIST_RISK_RULES, toolStep.get("tool").stringValue());
        assertTrue(toolStep.has("args"));
        assertTrue(toolStep.has("result_preview"));
        assertTrue(toolStep.has("ms"));

        var coverageStep = firstStepOfType(document, TraceStep.Type.COVERAGE_REPROMPT);
        assertTrue(coverageStep.get("missing").isArray());
        assertEquals(4, coverageStep.get("missing").size());

        var finalStep = firstStepOfType(document, TraceStep.Type.FINAL);
        assertEquals(RiskLevel.HIGH.name(), finalStep.get("risk_level").stringValue());
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context) {
        AgentProperties properties = new AgentProperties(40, MAX_COVERAGE_REPROMPTS, 4096, 0.1,
                32768, 1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

    private static Map<String, RuleOutcome> outcomes(AgentRunResult result) {
        return result.ruleOutcomes().stream()
                .collect(java.util.stream.Collectors.toMap(RuleOutcome::ruleName, outcome -> outcome));
    }

    private static long countSteps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(byType(type)).count();
    }

    private static Predicate<TraceStep> byType(String type) {
        return step -> type.equals(step.type());
    }

    private static tools.jackson.databind.JsonNode firstStepOfType(
            tools.jackson.databind.node.ObjectNode document, String type) {
        for (tools.jackson.databind.JsonNode step : document.get("steps")) {
            if (type.equals(step.get("type").stringValue())) {
                return step;
            }
        }
        throw new AssertionError("no step of type " + type + " in " + document);
    }

    private static String verdict(RiskRule rule, boolean triggered, int score, String rationale) {
        return """
                {"rule_id":"%s","triggered":%s,"score":%d,"transaction_ids":[],"rationale":"%s"}"""
                .formatted(rule.getRuleId(), triggered, score, rationale);
    }
}
