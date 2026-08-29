package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Progress is reported <em>while</em> the run is going, not only when it ends.
 *
 * <p>Watched on a real run: {@code GET /api/analyses/{id}} reported "0/12 rules, 0 steps" for the
 * whole 8m36s, because the counters were only written by the final persist. The UI's polling
 * fallback - what every client falls back to when the SSE stream drops - therefore looked frozen for
 * the entire analysis.
 *
 * <p>This test pins the source of the fix: the loop reports its counters as it goes. Remove the
 * {@link AnalysisProgressListener} callbacks from {@link AgentRunContext} and the recorded
 * progression collapses to nothing.
 */
class AnalysisProgressTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("the loop reports steps and rule coverage as they happen, not once at the end")
    void progressIsReportedThroughoutTheRun() {
        List<int[]> reported = new ArrayList<>();

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE), true, 30, "RU wire.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, STRUCTURING), true, 20, "Structuring.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO), true, 15, "XMR.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, DECLINE_BURST), false, 0, "None.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"HIGH","summary":"Sanctioned wire and structuring.",\
                        "recommendations":"Escalate."}""")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules,
                (steps, rulesEvaluated, rulesTotal) ->
                        reported.add(new int[] {steps, rulesEvaluated, rulesTotal}));

        AgentRunResult result = run(model, context);

        assertTrue(reported.size() >= 10, "every turn and every verdict must report progress");
        // The very first report happens before any rule has been ruled on - that is exactly the
        // window in which the old code showed nothing at all.
        assertEquals(1, reported.getFirst()[0]);
        assertEquals(0, reported.getFirst()[1]);
        assertEquals(4, reported.getFirst()[2]);

        assertTrue(contains(reported, 2, 1), "coverage 1/4 must be visible while the run is still going");
        assertTrue(contains(reported, 3, 2));
        assertTrue(contains(reported, 4, 3));
        assertTrue(contains(reported, 5, 4));

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
        assertEquals(result.rulesEvaluatedByAgent(), reported.getLast()[1]);
    }

    private static boolean contains(List<int[]> reported, int steps, int rulesEvaluated) {
        return reported.stream().anyMatch(row -> row[0] == steps && row[1] == rulesEvaluated);
    }

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context) {
        AgentProperties properties = new AgentProperties(40, 3, 4096, 0.1, 32768, 1536, 10, "test-model",
                2, 16, Duration.ofMinutes(5), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

    private static String verdict(RiskRule rule, boolean triggered, int score, String rationale) {
        return """
                {"rule_id":"%s","triggered":%s,"score":%d,"transaction_ids":[],"rationale":"%s"}"""
                .formatted(rule.getRuleId(), triggered, score, rationale);
    }
}
