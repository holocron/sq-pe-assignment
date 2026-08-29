package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rule-coverage gate: the loop must not be able to finish while a rule is unjudged, and a run
 * that cannot judge them all must end as a failure rather than as a tidy report.
 *
 * <p>This is the guarantee re-armed for an agent that is the sole judge. There is no engine to close
 * a rule the model skipped, so "coverage is always 100%" would now be a lie; what holds instead is
 * stricter and is what these tests pin: a run is either complete - every applicable rule judged by
 * the agent, {@code coverage_complete} true - or it is failed, with the rules it never judged named
 * in the exception and in the trace, and with every verdict it did produce kept.
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
    @DisplayName("a model that tries to finish early is re-prompted, and a run it never finishes ends "
            + "FAILED with the unjudged rules named and its own verdicts kept")
    void gateBlocksAnEarlyFinishAndAnUnfinishedRunFails() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                // 1. look at the checklist
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                // 2. judge one rule out of four
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                // 3. try to conclude with three rules still open - the gate must reject this
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                        """
                        {"risk_level":"HIGH","summary":"Sanctioned-jurisdiction wire found.",\
                        "recommendations":"File a report."}"""),
                // 4. give up and answer in prose instead - the gate must reject this too
                says("My analysis is complete; the customer is high risk."),
                // 5. grudgingly judge one more
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        structuring, true, 20, "Three payments just under 10,000 within a day.")),
                // 6. and 7. stop answering, burning the reprompt budget
                says("Nothing else to add."),
                says("Truly done.")));

        AgentRunFailedException failure =
                assertThrows(AgentRunFailedException.class, () -> run(model, context));

        // --- the run is a failure, and says why ----------------------------
        IncompleteRuleCoverageException cause = assertInstanceOf(IncompleteRuleCoverageException.class,
                failure.getCause(), "an unfinished checklist must fail the run, not round it up");
        assertEquals(2, cause.unjudgedRules().size());
        assertTrue(cause.getMessage().contains(UNATTRIBUTED_CRYPTO),
                "the failure must name the rules that were never judged: " + cause.getMessage());
        assertTrue(cause.getMessage().contains(DECLINE_BURST));

        // --- and it keeps everything the agent did establish ----------------
        AgentRunResult result = failure.result();
        assertEquals(4, result.rulesTotal());
        assertEquals(2, result.rulesJudged());
        assertEquals(2, result.ruleOutcomes().size(), "the verdicts obtained must survive the failure");
        assertFalse(result.coverageComplete());
        assertEquals(List.of(UNATTRIBUTED_CRYPTO, DECLINE_BURST),
                result.unjudgedRules().stream().map(UnjudgedRule::ruleName).toList());
        assertTrue(result.unjudgedRuleNames().contains(crypto.getRuleId().toString()));
        assertTrue(result.unjudgedRuleNames().contains(declines.getRuleId().toString()));

        Map<String, RuleOutcome> byName = outcomes(result);
        assertEquals(RuleVerdictSource.AGENT_JUDGED, byName.get(SANCTIONED_WIRE).source());
        assertEquals("Wire of 25,000 to a bank in RU.", byName.get(SANCTIONED_WIRE).rationale());
        assertEquals(RuleVerdictSource.AGENT_JUDGED, byName.get(STRUCTURING).source());
        assertFalse(byName.containsKey(UNATTRIBUTED_CRYPTO),
                "a rule nobody judged must not appear as an outcome - that would write a 0.00 row "
                        + "indistinguishable from a rule that was checked and cleared");

        // --- the gate actually fired ---------------------------------------
        assertEquals(4, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT),
                "the gate must record a coverage_reprompt every time the model tried to stop early");
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));
        String coverageFailure = stepTexts(trace, TraceStep.Type.COVERAGE_FAILED);
        assertTrue(coverageFailure.contains(UNATTRIBUTED_CRYPTO));
        assertTrue(coverageFailure.contains(DECLINE_BURST));
        assertTrue(coverageFailure.contains("recorded as FAILED"));
        assertNull(result.agentRiskLevel(),
                "submit_final_assessment was rejected, so the agent never concluded");

        // --- and the reprompt named the rules that were actually missing ----
        String reprompts = String.join("\n", model.userMessages());
        assertTrue(reprompts.contains(UNATTRIBUTED_CRYPTO), "the reprompt must name the missing rule");
        assertTrue(reprompts.contains(crypto.getRuleId().toString()),
                "the reprompt must give the missing rule's id so the model can act on it");
        assertTrue(reprompts.contains(DECLINE_BURST));

        // --- what was judged is still scored, from the agent's own estimates -
        assertEquals(0, new BigDecimal("50.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(1, countSteps(trace, TraceStep.Type.FINAL));
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"),
                "the generated summary must not read like a finished review");

        // sanity: the loop really did keep going instead of exiting at turn 3
        assertTrue(model.turns() >= 7, "the loop must not have exited when the model first tried to");
    }

    @Test
    @DisplayName("a model that judges every rule and concludes is let through untouched, with "
            + "coverage_complete true")
    void agentThatCoversEverythingIsNotReprompted() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE), true, 30, "RU wire.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        AgentTestFixtures.ruleNamed(rules, STRUCTURING), true, 20,
                        "Three near-threshold payments.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO), true, 15,
                        "XMR, no exchange.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        AgentTestFixtures.ruleNamed(rules, DECLINE_BURST), false, 0,
                        "No declines on file.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"CRITICAL","summary":"Sanctioned wire, structuring and an \
                        unattributed privacy-coin transfer.","recommendations":"Escalate to the MLRO."}""")));

        AgentRunResult result = run(model, context);

        assertTrue(result.coverageComplete(), "the agent judged every rule, so the run may complete");
        assertEquals(4, result.rulesJudged());
        assertTrue(result.unjudgedRules().isEmpty());
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT));
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));
        assertEquals(6, model.turns(), "the loop must stop as soon as the assessment is accepted");

        assertEquals(RiskLevel.CRITICAL, result.agentRiskLevel());
        // The band is re-derived by banding the agent's own rule scores, so it can differ from the
        // band the agent proposed.
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertTrue(result.summary().contains("Sanctioned wire"));
        assertTrue(result.recommendations().contains("MLRO"));
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.AGENT_JUDGED));
    }

    @Test
    @DisplayName("a model that never uses a tool produces a failed run, not a clean one")
    void modelThatJudgesNothingFailsInsteadOfReportingAnEmptyReview() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                says("This customer looks fine to me."),
                says("I said it looks fine."),
                says("Still fine."),
                says("Fine.")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        AgentRunFailedException failure =
                assertThrows(AgentRunFailedException.class, () -> run(model, context));
        AgentRunResult result = failure.result();

        assertTrue(result.ruleOutcomes().isEmpty(), "nothing was judged, so nothing may be recorded");
        assertEquals(4, result.unjudgedRules().size());
        assertFalse(result.coverageComplete());
        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());

        // The gate fires on every turn and the budget bounds how long it keeps trying.
        assertEquals(MAX_COVERAGE_REPROMPTS + 1, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT));
        assertEquals(MAX_COVERAGE_REPROMPTS + 1, model.turns());

        // "Looks fine to me" scores nothing, because nothing was judged.
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertNull(result.agentRiskLevel());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));
        assertTrue(result.summary().contains("No rule was judged at all"));
        assertTrue(result.recommendations().contains("Re-run this analysis"));
    }

    @Test
    @DisplayName("the trace renders in the published shape, failed runs included")
    void traceMatchesThePublishedShape() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                says("Done.")));
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        assertThrows(AgentRunFailedException.class, () -> run(model, context));

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

        var failedStep = firstStepOfType(document, TraceStep.Type.COVERAGE_FAILED);
        assertEquals(4, failedStep.get("missing").size(), "the unjudged rule ids are machine-readable");
        assertEquals(4, failedStep.get("detail").get("rules_unjudged").asInt());
        assertEquals(4, failedStep.get("detail").get("unjudged_rule_names").size());

        var finalStep = firstStepOfType(document, TraceStep.Type.FINAL);
        assertEquals(RiskLevel.LOW.name(), finalStep.get("risk_level").stringValue());
        assertFalse(finalStep.get("detail").get("coverage_complete").asBoolean());
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context) {
        AgentProperties properties = new AgentProperties(40, MAX_COVERAGE_REPROMPTS, 4096, 0.1,
                32768, 1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

    private static Map<String, RuleOutcome> outcomes(AgentRunResult result) {
        return result.ruleOutcomes().stream()
                .collect(Collectors.toMap(RuleOutcome::ruleName, outcome -> outcome));
    }

    private static long countSteps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(byType(type)).count();
    }

    private static String stepTexts(AnalysisTrace trace, String type) {
        return trace.steps().stream()
                .filter(byType(type))
                .map(TraceStep::text)
                .collect(Collectors.joining("\n"));
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
}
