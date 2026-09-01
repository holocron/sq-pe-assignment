package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.fails;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator: bounded fan-out of rule subagents, one retry per failed subagent, then the
 * single closing conversation over the verdict table.
 *
 * <p>Each rule's conversation is scripted independently by prompt content ({@link RoutedChatModel}),
 * which is what makes the concurrent design testable: a rule's subagent can stall, fail or misbehave
 * without any coupling to what another rule's subagent is doing at that moment.
 */
class OrchestratorTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();

    private static final ScriptedChatModel.Turn SUBMIT_HIGH = calls(
            RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
            "{\"risk_level\":\"HIGH\",\"summary\":\"Sanctioned wire, structuring and an unattributed "
                    + "transfer.\",\"recommendations\":\"Escalate to the MLRO.\"}");

    @Test
    @DisplayName("all subagents complete, then one closing conversation writes the assessment over "
            + "the verdict table")
    void allSubagentsCompleteThenTheSummaryIsWritten() {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);
        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, SUBMIT_HIGH);

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 3));

        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesJudged());
        assertEquals(4, result.ruleOutcomes().size());
        assertTrue(result.unjudgedRules().isEmpty());
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.mechanicalRiskLevel());
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel(), "the closing conversation concluded");
        assertTrue(result.summary().contains("Sanctioned wire"));
        assertTrue(result.recommendations().contains("MLRO"));
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.SQL_DERIVED));

        // Exactly one summary conversation, handed the verdict table.
        assertEquals(1, model.calls(RoutedChatModel.SUMMARY_MARKER));
        String summaryPrompt = model.prompts().stream()
                .map(prompt -> prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .collect(Collectors.joining("\n")))
                .filter(text -> text.contains(RoutedChatModel.SUMMARY_MARKER))
                .findFirst().orElseThrow();
        for (String name : List.of(SANCTIONED_WIRE, STRUCTURING, UNATTRIBUTED_CRYPTO, DECLINE_BURST)) {
            assertTrue(summaryPrompt.contains(name), "the verdict table must carry " + name);
        }
        assertTrue(summaryPrompt.contains("TRIGGERED") && summaryPrompt.contains("not triggered"));
        assertTrue(summaryPrompt.contains("65.00"), "the mechanical total is stated");

        // Every rule got its own subagent span; every evaluate_rule step is attributed to a rule.
        List<TraceStep> spans = trace.steps().stream()
                .filter(step -> TraceStep.Type.SUBAGENT.equals(step.type())).toList();
        assertEquals(8, spans.size(), "start + end for each of the four rules");
        assertTrue(spans.stream().map(TraceStep::subagent).map(TraceStep.SubagentSpan::worker)
                .allMatch(worker -> worker >= 1 && worker <= 3));
        List<TraceStep> verdictSteps = trace.steps().stream()
                .filter(step -> TraceStep.Type.TOOL_CALL.equals(step.type()))
                .filter(step -> RiskAgentTools.EVALUATE_RULE.equals(step.tool()))
                .toList();
        assertEquals(4, verdictSteps.size());
        assertTrue(verdictSteps.stream().allMatch(step -> step.ruleName() != null
                && step.ruleName().equals(step.subject())),
                "a tool call inside a subagent carries its rule name");
        assertEquals(1, trace.steps().stream()
                .filter(step -> TraceStep.Type.TOOL_CALL.equals(step.type()))
                .filter(step -> RiskAgentTools.SUBMIT_FINAL_ASSESSMENT.equals(step.tool()))
                .filter(step -> step.ruleName() == null)
                .count(), "the closing call is the orchestrator's, not a subagent's");
    }

    @Test
    @DisplayName("a failed subagent is retried once on a fresh conversation and the retry's verdict "
            + "completes the run")
    void aFailedSubagentIsRetriedOnce() {
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, SUBMIT_HIGH);
        // The decline-burst subagent's first attempt dies on its second turn (the model server
        // drops the connection); the retry judges the rule. One route, both attempts in order.
        model.route(declines.getRuleId().toString(), List.of(
                calls(RiskAgentTools.LIST_TRANSACTIONS, "{}"),
                fails(new IllegalStateException("the model server closed the connection")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Five declined authorisations inside a rolling day."))));

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2));

        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesJudged());
        assertEquals(RiskLevel.HIGH, result.riskLevel());

        List<TraceStep.SubagentSpan> ends = trace.steps().stream()
                .map(TraceStep::subagent)
                .filter(span -> span != null && "end".equals(span.phase()))
                .filter(span -> DECLINE_BURST.equals(span.ruleName()))
                .toList();
        assertEquals(2, ends.size());
        assertEquals("failed", ends.getFirst().verdict());
        assertEquals(1, ends.getFirst().attempt());
        assertEquals("not_triggered", ends.getLast().verdict());
        assertEquals(2, ends.getLast().attempt());
        assertTrue(trace.steps().stream()
                .filter(step -> TraceStep.Type.REPROMPT.equals(step.type()))
                .anyMatch(step -> step.text().contains(DECLINE_BURST)
                        && step.text().contains("retrying")));
    }

    @Test
    @DisplayName("a rule unjudged after its retry fails the run named, keeping the other verdicts; "
            + "no summary conversation happens")
    void aRuleUnjudgedAfterTheRetryFailsTheRun() {
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, SUBMIT_HIGH);
        // Both attempts of the decline-burst subagent answer prose until their budgets run out.
        model.route(declines.getRuleId().toString(), List.of(
                says("Nothing yet."), says("Still nothing."), says("Done."),
                says("Retry: nothing."), says("Retry: still nothing."), says("Retry done.")));

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(3, 2)));
        AgentRunResult result = failure.result();

        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(DECLINE_BURST));
        assertEquals(List.of(DECLINE_BURST),
                result.unjudgedRules().stream().map(UnjudgedRule::ruleName).toList());
        assertEquals(3, result.rulesJudged(), "the verdicts that did land are kept");
        assertFalse(result.coverageComplete());
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(1, trace.steps().stream()
                .filter(step -> TraceStep.Type.COVERAGE_FAILED.equals(step.type())).count());
        assertEquals(0, model.calls(RoutedChatModel.SUMMARY_MARKER),
                "a partial run gets no closing conversation");
        assertEquals(6, model.calls(declines.getRuleId().toString()),
                "two subagent attempts of three steps each, then the rule is given up");
    }

    @Test
    @DisplayName("parallel execution respects the bound, and concurrent rules' verdicts never bleed "
            + "into each other")
    void parallelismIsBoundedAndVerdictsAreIsolated() throws Exception {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        // Every subagent first makes a slow evidence call (so the pool genuinely overlaps) and then
        // submits its own rule's verdict.
        CountDownLatch release = new CountDownLatch(1);
        RoutedChatModel model = new RoutedChatModel();
        for (RiskRule rule : rules) {
            model.route(rule.getRuleId().toString(), List.of(
                    () -> {
                        try {
                            assertTrue(release.await(30, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return new org.springframework.ai.chat.model.ChatResponse(List.of(
                                new org.springframework.ai.chat.model.Generation(
                                        new org.springframework.ai.chat.messages.AssistantMessage(
                                                "looked around"))));
                    },
                    calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(rule,
                            "The activity this rule's condition names."))));
        }
        model.summary(List.of(SUBMIT_HIGH));

        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        opener.setDaemon(true);
        opener.start();

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2));

        assertTrue(result.coverageComplete());
        assertEquals(2, model.peakConcurrency(),
                "exactly the configured bound must be reached and never exceeded");

        // Isolation: each rule's verdict is its own query's answer, scored at its own weight.
        Map<String, RuleOutcome> byName = result.ruleOutcomes().stream()
                .collect(Collectors.toMap(RuleOutcome::ruleName, Function.identity()));
        assertTrue(byName.get(SANCTIONED_WIRE).triggered());
        assertEquals(0, new BigDecimal("30.00").compareTo(byName.get(SANCTIONED_WIRE).score()));
        assertTrue(byName.get(STRUCTURING).triggered());
        assertEquals(0, new BigDecimal("20.00").compareTo(byName.get(STRUCTURING).score()));
        assertTrue(byName.get(UNATTRIBUTED_CRYPTO).triggered());
        assertFalse(byName.get(DECLINE_BURST).triggered());
        assertEquals(0, BigDecimal.ZERO.compareTo(byName.get(DECLINE_BURST).score()));
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
    }

    @Test
    @DisplayName("cancellation mid-fan-out aborts in-flight subagents promptly and keeps the "
            + "verdicts already obtained")
    void cancellationMidFanOut() throws Exception {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = new RoutedChatModel();
        model.route(sanctioned.getRuleId().toString(), List.of(
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                        "Payments over 10,000 to a sanctioned jurisdiction."))));
        // The other three subagents block inside their first model call until interrupted.
        ScriptedChatModel.Turn blocked = () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the model call was in flight");
            }
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                    new org.springframework.ai.chat.model.Generation(
                            new org.springframework.ai.chat.messages.AssistantMessage("late"))));
        };
        for (RiskRule rule : List.of(structuring, declines,
                AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO))) {
            model.route(rule.getRuleId().toString(), List.of(blocked));
        }

        // Cancel as soon as the fast subagent's verdict is recorded.
        Thread canceller = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 30_000;
            while (!context.isEvaluated(sanctioned.getRuleId())
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            trace.requestCancellation();
        });
        canceller.setDaemon(true);
        canceller.start();

        AgentRunCancelledException cancelled = assertThrows(AgentRunCancelledException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 3)));

        AgentRunResult result = cancelled.result();
        assertEquals(1, result.ruleOutcomes().size(), "the verdict obtained before the cancel is kept");
        assertEquals(SANCTIONED_WIRE, result.ruleOutcomes().getFirst().ruleName());
        assertEquals(3, result.unjudgedRules().size());
        assertEquals(1, trace.steps().stream()
                .filter(step -> TraceStep.Type.CANCELLED.equals(step.type())).count());
        assertEquals(0, trace.steps().stream()
                .filter(step -> TraceStep.Type.COVERAGE_FAILED.equals(step.type())).count(),
                "an unfinished fan-out on a cancelled run is not a coverage failure");
        assertEquals(0, model.calls(RoutedChatModel.SUMMARY_MARKER));
    }
}
