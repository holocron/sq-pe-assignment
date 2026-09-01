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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule-coverage gate, re-armed for the orchestrator architecture.
 *
 * <p>Coverage is no longer a conversation the model can have with the gate; it is the fan-out's
 * shape: one subagent per applicable rule, each existing solely to submit that rule's verdict. What
 * these tests pin is the guarantee's outward form, which is unchanged: a run either has a verdict
 * for every applicable rule - {@code coverage_complete} true - or it is persisted FAILED, naming the
 * rules never judged and keeping the verdicts it did obtain.
 *
 * <p>No Spring context, no database and no language model: the model is {@link RoutedChatModel},
 * PostgreSQL is {@link StubRuleSqlEvaluator} and the evidence is {@link AgentTestFixtures}, whose
 * planted activity answers SANCTIONED_WIRE 30 + STRUCTURING 20 + UNATTRIBUTED_CRYPTO 15 = 65 (HIGH),
 * with DECLINE_BURST not triggered.
 */
class RuleCoverageGateTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();

    private static final ScriptedChatModel.Turn SUBMIT_HIGH = calls(
            RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
            "{\"risk_level\":\"HIGH\",\"summary\":\"Sanctioned wire and structuring.\","
                    + "\"recommendations\":\"Escalate.\"}");

    @Test
    @DisplayName("rules whose subagents never produce a verdict fail the run, are named in the "
            + "exception and the trace, and the verdicts that did land are kept")
    void unjudgedRulesFailTheRunAndAreNamed() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = new RoutedChatModel()
                .route(sanctioned.getRuleId().toString(), List.of(
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                                "Payments over 10,000 to a sanctioned jurisdiction."))))
                .route(structuring.getRuleId().toString(), List.of(
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(structuring,
                                "Three payments of 9,000-9,999 inside a rolling day."))))
                // The other two subagents just talk, on both their attempt and their retry.
                .route(crypto.getRuleId().toString(), List.of(
                        says("Looking."), says("Looking more."), says("Done looking."),
                        says("Retry: looking."), says("Retry: more."), says("Retry: done.")))
                .route(declines.getRuleId().toString(), List.of(
                        says("Pondering."), says("Pondering more."), says("Done pondering."),
                        says("Retry: pondering."), says("Retry: more."), says("Retry: done.")))
                .summary(List.of(SUBMIT_HIGH));

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(3, 2)));

        // --- the run is a failure, and says why ----------------------------
        IncompleteRuleCoverageException cause = assertInstanceOf(IncompleteRuleCoverageException.class,
                failure.getCause(), "an unfinished checklist must fail the run, not round it up");
        assertEquals(2, cause.unjudgedRules().size());
        assertTrue(cause.getMessage().contains(UNATTRIBUTED_CRYPTO),
                "the failure must name the rules that were never judged: " + cause.getMessage());
        assertTrue(cause.getMessage().contains(DECLINE_BURST));

        // --- and it keeps everything the subagents did establish -----------
        AgentRunResult result = failure.result();
        assertEquals(4, result.rulesTotal());
        assertEquals(2, result.rulesJudged());
        assertEquals(2, result.ruleOutcomes().size(), "the verdicts obtained must survive the failure");
        assertFalse(result.coverageComplete());
        assertEquals(List.of(UNATTRIBUTED_CRYPTO, DECLINE_BURST),
                result.unjudgedRules().stream().map(UnjudgedRule::ruleName).toList());

        Map<String, RuleOutcome> byName = result.ruleOutcomes().stream()
                .collect(Collectors.toMap(RuleOutcome::ruleName, outcome -> outcome));
        assertEquals(RuleVerdictSource.SQL_DERIVED, byName.get(SANCTIONED_WIRE).source());
        assertEquals("Payments over 10,000 to a sanctioned jurisdiction.",
                byName.get(SANCTIONED_WIRE).rationale());
        assertTrue(byName.get(SANCTIONED_WIRE).sql().contains("receiver_bank_country"),
                "a kept verdict keeps the query that produced it");
        assertFalse(byName.containsKey(UNATTRIBUTED_CRYPTO),
                "a rule nobody judged must not appear as an outcome - that would write a 0.00 row "
                        + "indistinguishable from a rule that was checked and cleared");

        // --- the failure is visible in the trace ---------------------------
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));
        String coverageFailure = stepTexts(trace, TraceStep.Type.COVERAGE_FAILED);
        assertTrue(coverageFailure.contains(UNATTRIBUTED_CRYPTO));
        assertTrue(coverageFailure.contains(DECLINE_BURST));
        assertTrue(coverageFailure.contains("recorded as FAILED"));
        assertNull(result.agentRiskLevel(), "a partial run gets no closing conversation");
        assertEquals(0, model.calls(RoutedChatModel.SUMMARY_MARKER));

        // --- what was judged is still scored -------------------------------
        assertEquals(0, new BigDecimal("50.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(1, countSteps(trace, TraceStep.Type.FINAL));
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"),
                "the generated summary must not read like a finished review");

        // Each failed rule shows both subagent attempts on the transcript.
        List<TraceStep.SubagentSpan> failedEnds = trace.steps().stream()
                .map(TraceStep::subagent)
                .filter(span -> span != null && "end".equals(span.phase()))
                .filter(span -> "failed".equals(span.verdict()))
                .toList();
        assertEquals(4, failedEnds.size(), "two rules, two attempts each");
        assertEquals(List.of(DECLINE_BURST, DECLINE_BURST, UNATTRIBUTED_CRYPTO, UNATTRIBUTED_CRYPTO),
                failedEnds.stream().map(TraceStep.SubagentSpan::ruleName).sorted().toList());
    }

    @Test
    @DisplayName("subagents that judge nothing at all produce a failed run, not a clean review")
    void subagentsThatJudgeNothingFailInsteadOfReportingAnEmptyReview() {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = new RoutedChatModel();
        for (RiskRule rule : rules) {
            model.route(rule.getRuleId().toString(), List.of(
                    says("This customer looks fine to me."), says("I said it looks fine."),
                    says("Retry: still fine.")));
        }

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(2, 2)));
        AgentRunResult result = failure.result();

        assertTrue(result.ruleOutcomes().isEmpty(), "nothing was judged, so nothing may be recorded");
        assertEquals(4, result.unjudgedRules().size());
        assertFalse(result.coverageComplete());
        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());

        // Every rule burned both its subagent attempts (two steps each) and was then given up.
        for (RiskRule rule : rules) {
            assertEquals(4, model.calls(rule.getRuleId().toString()),
                    "two attempts of two steps for " + rule.getRuleName());
        }

        // "Looks fine to me" scores nothing, because nothing was judged.
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertNull(result.agentRiskLevel());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));
        assertTrue(result.summary().contains("No rule was judged at all"));
        assertTrue(result.recommendations().contains("Re-run this analysis"));
    }

    @Test
    @DisplayName("the trace renders in the published shape, subagent spans and failed runs included")
    void traceMatchesThePublishedShape() {
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, List.of(declines));
        RoutedChatModel model = new RoutedChatModel()
                .route(declines.getRuleId().toString(), List.of(says("Nothing to say.")));

        assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context,
                        new StubRuleSqlEvaluator(), AgentTestFixtures.properties(2, 1)));

        var document = trace.toJson();
        assertTrue(document.has("steps"));
        var steps = document.get("steps");
        assertTrue(steps.isArray() && !steps.isEmpty());
        assertEquals(1, steps.get(0).get("n").asInt());
        assertEquals(TraceStep.Type.STARTED, steps.get(0).get("type").stringValue());

        // The new subagent step, in exactly the shape the frontend codes against.
        var subagentStart = firstStepOfType(document, TraceStep.Type.SUBAGENT);
        assertEquals("start", subagentStart.get("phase").stringValue());
        assertEquals(declines.getRuleId().toString(), subagentStart.get("ruleId").stringValue());
        assertEquals(DECLINE_BURST, subagentStart.get("ruleName").stringValue());
        assertEquals(1, subagentStart.get("worker").asInt());
        assertEquals(1, subagentStart.get("attempt").asInt());

        var subagentEnd = lastStepOfType(document, TraceStep.Type.SUBAGENT);
        assertEquals("end", subagentEnd.get("phase").stringValue());
        assertEquals("failed", subagentEnd.get("verdict").stringValue());
        assertEquals(2, subagentEnd.get("attempt").asInt());
        assertTrue(subagentEnd.get("stepsUsed").asInt() >= 1);
        assertTrue(subagentEnd.has("durationMs"));

        var failedStep = firstStepOfType(document, TraceStep.Type.COVERAGE_FAILED);
        assertEquals(1, failedStep.get("missing").size(), "the unjudged rule ids are machine-readable");
        assertEquals(1, failedStep.get("detail").get("rules_unjudged").asInt());
        assertEquals(DECLINE_BURST,
                failedStep.get("detail").get("unjudged_rule_names").get(0).stringValue());

        var finalStep = firstStepOfType(document, TraceStep.Type.FINAL);
        assertEquals(RiskLevel.LOW.name(), finalStep.get("risk_level").stringValue());
        assertFalse(finalStep.get("detail").get("coverage_complete").asBoolean());
    }

    // ------------------------------------------------------------------

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

    private static tools.jackson.databind.JsonNode lastStepOfType(
            tools.jackson.databind.node.ObjectNode document, String type) {
        tools.jackson.databind.JsonNode found = null;
        for (tools.jackson.databind.JsonNode step : document.get("steps")) {
            if (type.equals(step.get("type").stringValue())) {
                found = step;
            }
        }
        if (found == null) {
            throw new AssertionError("no step of type " + type + " in " + document);
        }
        return found;
    }
}
