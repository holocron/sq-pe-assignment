package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskRule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cancelling a run: the loop polls the flag between turns, aborts promptly and settles from the
 * verdicts already obtained, so a cancelled run keeps its work and is reported as
 * {@link AgentRunCancelledException} - never as a completed or failed analysis.
 *
 * <p>No Spring context, no database and no language model, same rig as
 * {@link RuleCoverageGuaranteeTest}.
 */
class AnalysisCancellationTest {

    private static final int MAX_COVERAGE_REPROMPTS = 3;
    private static final int MAX_SQL_ATTEMPTS = 3;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    @Test
    @DisplayName("a run cancelled before the first turn aborts without touching the model")
    void cancelBeforeTheFirstTurnAbortsImmediately() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        ScriptedChatModel model = new ScriptedChatModel(List.of());

        trace.requestCancellation();

        AgentRunCancelledException cancelled =
                assertThrows(AgentRunCancelledException.class, () -> run(model, context, 10));

        assertEquals(0, model.turns(), "a cancelled run must not spend another model turn");
        assertTrue(cancelled.result().ruleOutcomes().isEmpty());
        assertEquals(1, countSteps(trace, TraceStep.Type.CANCELLED),
                "the transcript must say why the run stopped");
    }

    @Test
    @DisplayName("a cancel mid-run aborts at the next step boundary and keeps the verdicts obtained")
    void cancelMidRunKeepsTheVerdictsAlreadyObtained() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                        "Payments over 10,000 to a sanctioned jurisdiction.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Five declined authorisations inside a rolling day.")),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}")));
        // The cancel lands while the second turn is being answered; the loop must notice before
        // it spends a third.
        ChatModel cancelling = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                ChatResponse response = model.call(prompt);
                if (model.turns() == 2) {
                    trace.requestCancellation();
                }
                return response;
            }
        };

        AgentRunCancelledException cancelled =
                assertThrows(AgentRunCancelledException.class, () -> run(cancelling, context, 10));
        AgentRunResult result = cancelled.result();

        assertEquals(2, model.turns(), "the loop must stop at the step boundary after the cancel");
        assertEquals(2, result.ruleOutcomes().size(),
                "the two verdicts obtained before the cancel are settled, not discarded");
        assertEquals(2, result.unjudgedRules().size());
        assertEquals(1, countSteps(trace, TraceStep.Type.CANCELLED));
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_FAILED),
                "an unfinished checklist on a cancelled run is not a coverage failure");
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ChatModel model, AgentRunContext context, int maxSteps) {
        AgentProperties properties = new AgentProperties(maxSteps, MAX_COVERAGE_REPROMPTS,
                MAX_SQL_ATTEMPTS, 4096, 0.1, 32768, 1536, 10, "test-model", 2, 16,
                Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null,
                AgentTestFixtures.evaluator(context), jsonMapper, 25, MAX_SQL_ATTEMPTS);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

    private static long countSteps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(step -> type.equals(step.type())).count();
    }
}
