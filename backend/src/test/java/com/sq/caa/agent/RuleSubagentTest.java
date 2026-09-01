package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The rule subagent's own mini-loop, driven through the orchestrator with a coverage set small
 * enough to see it.
 *
 * <p>What is pinned here: a subagent ends only through a recorded verdict; exhausting its step
 * budget without one is a failure (which the orchestrator retries once, then fails the run naming
 * the rule); a threshold-fidelity refusal costs no database attempt and is recoverable inside the
 * same subagent; and a subagent's verdict tool refuses a rule that is not its own.
 *
 * <p>No Spring context, no database and no language model: the model is {@link RoutedChatModel},
 * PostgreSQL is {@link StubRuleSqlEvaluator}.
 */
class RuleSubagentTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();
    private final RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
    private final RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);

    private static final ScriptedChatModel.Turn SUBMIT_HIGH = calls(
            RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
            "{\"risk_level\":\"HIGH\",\"summary\":\"Findings.\",\"recommendations\":\"Escalate.\"}");

    @Test
    @DisplayName("the happy path: investigate, one query, verdict recorded, subagent span traced")
    void verdictHappyPath() {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, List.of(sanctioned));
        StubRuleSqlEvaluator sql = new StubRuleSqlEvaluator()
                .matching("receiver_bank_country", AgentTestFixtures.sanctionedWireEvidence(context));

        RoutedChatModel model = new RoutedChatModel()
                .route(sanctioned.getRuleId().toString(), List.of(
                        calls(RiskAgentTools.GET_CUSTOMER_PROFILE, "{}"),
                        calls(RiskAgentTools.LIST_TRANSACTIONS, "{}"),
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                                "Payments over 10,000 to a sanctioned jurisdiction."))))
                .summary(List.of(SUBMIT_HIGH));

        AgentRunResult result = AgentTestFixtures.run(model, context, sql,
                AgentTestFixtures.properties(12, 1));

        assertTrue(result.coverageComplete());
        assertEquals(1, result.rulesJudged());
        assertEquals(RiskLevel.MEDIUM, result.riskLevel(), "weight 30 bands MEDIUM");
        RuleOutcome outcome = result.ruleOutcomes().getFirst();
        assertTrue(outcome.triggered());
        assertEquals(RuleVerdictSource.SQL_DERIVED, outcome.source());
        assertEquals("Payments over 10,000 to a sanctioned jurisdiction.", outcome.rationale());

        // The subagent span: start and end, one worker, first attempt, the verdict and its cost.
        List<TraceStep> spans = steps(trace, TraceStep.Type.SUBAGENT);
        assertEquals(2, spans.size());
        TraceStep.SubagentSpan start = spans.getFirst().subagent();
        assertEquals("start", start.phase());
        assertEquals(sanctioned.getRuleId().toString(), start.ruleId());
        assertEquals(SANCTIONED_WIRE, start.ruleName());
        assertEquals(1, start.worker());
        assertEquals(1, start.attempt());
        TraceStep.SubagentSpan end = spans.getLast().subagent();
        assertEquals("end", end.phase());
        assertEquals("triggered", end.verdict());
        assertEquals(0, new BigDecimal("30.00").compareTo(end.score()));
        assertEquals(3, end.stepsUsed());
        assertNotNull(end.durationMs());

        // The subagent's own prompt carries the rule - id, condition, weight, scope - and the
        // verdict protocol.
        String prompt = model.prompts().stream()
                .map(p -> p.getInstructions().stream().map(m -> m.getText())
                        .collect(Collectors.joining("\n")))
                .filter(text -> text.contains(sanctioned.getRuleId().toString()))
                .findFirst().orElseThrow();
        assertTrue(prompt.contains("[BEGIN UNTRUSTED rule_condition"));
        assertTrue(prompt.contains("10,000"), "the condition reaches the model verbatim");
        assertTrue(prompt.contains("evaluate_rule"));
    }

    @Test
    @DisplayName("a subagent that never submits exhausts its budget, is retried once, and the rule "
            + "fails the run named, with the other rule's verdict kept")
    void budgetExhaustionFailsTheRuleAfterOneRetry() {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules.subList(0, 2));

        RoutedChatModel model = new RoutedChatModel()
                // The sanctioned-wire subagent never calls a tool: four prose turns, twice.
                .route(sanctioned.getRuleId().toString(), List.of(
                        says("Still thinking."), says("Thinking."), says("Hmm."), says("..."),
                        says("Retry, still thinking."), says("Retry."), says("Hmm."), says("...")))
                .route(structuring.getRuleId().toString(), List.of(
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(structuring,
                                "Three payments of 9,000-9,999 inside a rolling day."))))
                .summary(List.of(SUBMIT_HIGH));

        StubRuleSqlEvaluator sql = new StubRuleSqlEvaluator()
                .matching("BETWEEN 9000 AND 9999", AgentTestFixtures.structuringEvidence(context));

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context, sql,
                        AgentTestFixtures.properties(4, 1)));
        AgentRunResult result = failure.result();

        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(SANCTIONED_WIRE),
                "the failure names the rule that never got a verdict");
        assertEquals(List.of(SANCTIONED_WIRE),
                result.unjudgedRules().stream().map(UnjudgedRule::ruleName).toList());
        assertEquals(1, result.rulesJudged(), "the other subagent's verdict is kept");
        assertFalse(result.coverageComplete());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));

        // Two subagent spans for the failed rule: attempt 1 and the retry, both ending "failed".
        List<TraceStep.SubagentSpan> ends = steps(trace, TraceStep.Type.SUBAGENT).stream()
                .map(TraceStep::subagent)
                .filter(span -> "end".equals(span.phase()))
                .filter(span -> SANCTIONED_WIRE.equals(span.ruleName()))
                .toList();
        assertEquals(2, ends.size(), "the first subagent and its retry are both on the transcript");
        assertEquals(List.of(1, 2), ends.stream().map(TraceStep.SubagentSpan::attempt).toList());
        assertTrue(ends.stream().allMatch(span -> "failed".equals(span.verdict())));
        assertNull(ends.getFirst().score());

        // The retry is announced on the reused reprompt step type.
        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("retrying it once"),
                stepTexts(trace, TraceStep.Type.REPROMPT));

        // And no summary conversation ever happened: a partial run does not get one.
        assertEquals(0, model.calls(RoutedChatModel.SUMMARY_MARKER));
    }

    @Test
    @DisplayName("a threshold-fidelity refusal costs no database attempt and the subagent recovers "
            + "inside its own loop")
    void fidelityRefusalIsHandledInsideTheSubagent() {
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, AgentTestFixtures.DECLINE_BURST);
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, List.of(declines));
        StubRuleSqlEvaluator sql = new StubRuleSqlEvaluator();

        RoutedChatModel model = new RoutedChatModel()
                .route(declines.getRuleId().toString(), List.of(
                        // The lazy query: none of the condition's own numbers (5, 24) in it.
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                                "SELECT t.transaction_id FROM tx t WHERE t.status = 'Failed'",
                                "Failed card authorisations.")),
                        // The faithful one, accepted and run.
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                                "Five declined authorisations inside a rolling day."))))
                .summary(List.of(calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                        "{\"risk_level\":\"LOW\",\"summary\":\"Nothing fired.\","
                                + "\"recommendations\":\"Standard review.\"}")));

        AgentRunResult result = AgentTestFixtures.run(model, context, sql,
                AgentTestFixtures.properties(12, 1));

        assertTrue(result.coverageComplete());
        assertFalse(result.ruleOutcomes().getFirst().triggered());
        assertEquals(1, sql.executed().size(),
                "only the faithful query reached PostgreSQL; the refused one never ran");

        List<TraceStep> attempts = steps(trace, TraceStep.Type.TOOL_CALL).stream()
                .filter(step -> RiskAgentTools.EVALUATE_RULE.equals(step.tool()))
                .toList();
        assertEquals(2, attempts.size());
        assertTrue(attempts.getFirst().outcome().startsWith("query not run"),
                attempts.getFirst().outcome());
        assertEquals("not triggered (rule 1 of 1)", attempts.getLast().outcome());
        // Both calls are attributed to the subagent's rule.
        assertTrue(attempts.stream().allMatch(step -> AgentTestFixtures.DECLINE_BURST
                .equals(step.ruleName())), "tool calls inside a subagent carry ruleName");
    }

    @Test
    @DisplayName("a subagent's verdict tool refuses a rule that is not its own, and the run still "
            + "completes when both subagents behave")
    void aSubagentCannotJudgeAnotherSubagentsRule() {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules.subList(0, 2));
        StubRuleSqlEvaluator sql = new StubRuleSqlEvaluator()
                .matching("receiver_bank_country", AgentTestFixtures.sanctionedWireEvidence(context))
                .matching("BETWEEN 9000 AND 9999", AgentTestFixtures.structuringEvidence(context));

        RoutedChatModel model = new RoutedChatModel()
                .route(sanctioned.getRuleId().toString(), List.of(
                        // The confused subagent first aims at the other rule...
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(structuring,
                                "Payments under the threshold.")),
                        // ... is refused, and corrects itself.
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                                "Payments over 10,000 to a sanctioned jurisdiction."))))
                .route(structuring.getRuleId().toString(), List.of(
                        calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(structuring,
                                "Three payments of 9,000-9,999 inside a rolling day."))))
                .summary(List.of(SUBMIT_HIGH));

        AgentRunResult result = AgentTestFixtures.run(model, context, sql,
                AgentTestFixtures.properties(12, 1));

        assertTrue(result.coverageComplete());
        assertEquals(2, result.rulesJudged());
        assertEquals(0, new BigDecimal("50.00").compareTo(result.totalScore()));
        // The mistaken call recorded nothing: exactly one verdict per rule, from the right query.
        assertTrue(result.ruleOutcomes().stream()
                .filter(outcome -> outcome.ruleName().equals(STRUCTURING))
                .findFirst().orElseThrow().sql().contains("BETWEEN 9000 AND 9999"));
        String refused = trace.steps().stream()
                .filter(step -> TraceStep.Type.TOOL_CALL.equals(step.type()))
                .map(TraceStep::resultPreview)
                .filter(preview -> preview != null && preview.contains("belongs to another subagent"))
                .findFirst().orElse(null);
        assertNotNull(refused, "the cross-rule call must be refused by the scoped verdict tool");
    }

    // ------------------------------------------------------------------

    private static List<TraceStep> steps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(step -> type.equals(step.type())).toList();
    }

    private static String stepTexts(AnalysisTrace trace, String type) {
        return trace.steps().stream()
                .filter(step -> type.equals(step.type()))
                .map(step -> step.text() == null
                        ? (step.resultPreview() == null ? "" : step.resultPreview())
                        : step.text())
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unused")
    private static JsonNode asJson(TraceStep step) {
        return step.toJson(tools.jackson.databind.node.JsonNodeFactory.instance);
    }
}
