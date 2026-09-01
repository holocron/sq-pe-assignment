package com.sq.caa.agent;

import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cancelling a run under the orchestrator: the flag is polled between steps of every subagent and
 * by the orchestrator while it waits on the fan-out, so a cancel aborts promptly; the run settles
 * from the verdicts already obtained and is reported as {@link AgentRunCancelledException} - never
 * as a completed or failed analysis.
 *
 * <p>No Spring context, no database and no language model, same rig as
 * {@link RuleCoverageGuaranteeTest}.
 */
class AnalysisCancellationTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("a run cancelled before the first turn aborts without touching the model")
    void cancelBeforeTheFirstTurnAbortsImmediately() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, says("unreachable"));

        trace.requestCancellation();

        AgentRunCancelledException cancelled = assertThrows(AgentRunCancelledException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2)));

        assertTrue(model.prompts().isEmpty(), "a cancelled run must not spend another model turn");
        assertTrue(cancelled.result().ruleOutcomes().isEmpty());
        assertEquals(1, countSteps(trace, TraceStep.Type.CANCELLED),
                "the transcript must say why the run stopped");
    }

    @Test
    @DisplayName("a cancel during the closing conversation aborts it and keeps every verdict")
    void cancelDuringTheSummaryKeepsAllVerdicts() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        CountDownLatch cancelled = new CountDownLatch(1);
        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, says("unreachable"));
        // The summary conversation's first turn returns prose once the cancel lands; the loop must
        // notice the flag before spending a second turn on it.
        model.summary(List.of(() -> {
            try {
                cancelled.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return says("The verdict table shows...").respond();
        }));

        Thread canceller = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 30_000;
            while (context.evaluatedCount() < rules.size()
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            trace.requestCancellation();
            cancelled.countDown();
        });
        canceller.setDaemon(true);
        canceller.start();

        AgentRunCancelledException exception = assertThrows(AgentRunCancelledException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 3)));
        AgentRunResult result = exception.result();

        assertEquals(4, result.ruleOutcomes().size(),
                "the whole fan-out had already landed; every verdict is settled, not discarded");
        assertTrue(result.unjudgedRules().isEmpty());
        assertEquals(1, countSteps(trace, TraceStep.Type.CANCELLED));
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_FAILED),
                "a fully covered run cancelled during its summary is not a coverage failure");
        assertEquals(RiskLevel.HIGH, result.riskLevel(), "the mechanical band is still settled");
    }

    // ------------------------------------------------------------------

    private static long countSteps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(step -> type.equals(step.type())).count();
    }
}
