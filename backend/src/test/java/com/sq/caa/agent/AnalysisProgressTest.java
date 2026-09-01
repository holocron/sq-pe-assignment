package com.sq.caa.agent;

import static com.sq.caa.agent.ScriptedChatModel.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskRule;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Progress is reported <em>while</em> the run is going, not only when it ends.
 *
 * <p>Watched on a real run: {@code GET /api/analyses/{id}} reported "0/12 rules, 0 steps" for the
 * whole 8m36s, because the counters were only written by the final persist. The UI's polling
 * fallback - what every client falls back to when the SSE stream drops - therefore looked frozen for
 * the entire analysis.
 *
 * <p>This test pins the source of the fix under the orchestrator: each subagent reports its steps as
 * they happen, and every verdict a subagent lands reports the coverage counter. Remove the
 * {@link AnalysisProgressListener} callbacks from {@link AgentRunContext} and the recorded
 * progression collapses to nothing. Run at parallelism 1 so the sequence is fully deterministic.
 */
class AnalysisProgressTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("steps and rule coverage are reported as they happen, not once at the end")
    void progressIsReportedThroughoutTheRun() {
        List<int[]> reported = new ArrayList<>();

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules,
                (steps, rulesEvaluated, rulesTotal) ->
                        reported.add(new int[] {steps, rulesEvaluated, rulesTotal}));

        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, calls(
                RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                "{\"risk_level\":\"HIGH\",\"summary\":\"Sanctioned wire and structuring.\","
                        + "\"recommendations\":\"Escalate.\"}"));

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 1));

        assertEquals(9, reported.size(), "every step and every verdict must report progress: "
                + "one per subagent turn, one per verdict, one for the closing turn");
        // The very first report happens before any rule has been ruled on - that is exactly the
        // window in which the old code showed nothing at all.
        assertEquals(1, reported.getFirst()[0]);
        assertEquals(0, reported.getFirst()[1]);
        assertEquals(4, reported.getFirst()[2]);

        // One verdict lands per subagent, and each is visible the moment it lands.
        assertTrue(contains(reported, 1, 1), "coverage 1/4 must be visible while the run is going");
        assertTrue(contains(reported, 2, 2));
        assertTrue(contains(reported, 3, 3));
        assertTrue(contains(reported, 4, 4));

        // Monotonic, and the last report matches what the run actually did.
        int[] previous = null;
        for (int[] snapshot : reported) {
            if (previous != null) {
                assertTrue(snapshot[0] >= previous[0], "step count must never go backwards");
                assertTrue(snapshot[1] >= previous[1], "coverage must never go backwards");
            }
            previous = snapshot;
        }
        assertEquals(result.steps(), reported.getLast()[0]);
        assertEquals(result.rulesJudged(), reported.getLast()[1]);
        assertEquals(5, result.steps(), "four subagent turns plus the closing one");
    }

    private static boolean contains(List<int[]> reported, int steps, int rulesEvaluated) {
        return reported.stream().anyMatch(row -> row[0] == steps && row[1] == rulesEvaluated);
    }
}
