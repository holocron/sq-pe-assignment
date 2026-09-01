package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.fails;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The <em>failure</em> paths of the rule-coverage guarantee under the orchestrator.
 *
 * <p>{@link RuleCoverageGateTest} pins the outward shape of the guarantee. This class covers what
 * the guarantee is for when things break: the model server dying during the closing conversation,
 * the rule whose query never runs at all (not in its subagent, not in the retry), and the
 * prompt-too-large refusal that must cost a compaction, never a run. Each test is written so that
 * removing the mechanism it names makes it fail:
 *
 * <ul>
 *   <li>settle nothing on failure and
 *       {@link #aModelFailureInTheSummaryPhaseKeepsEveryVerdictTheSubagentsSubmitted()} loses the
 *       subagents' work along with the run;</li>
 *   <li>record a rule whose query was refused as "not triggered" and
 *       {@link #aRuleWhoseQueryNeverRunsIsLeftUnjudgedAndFailsTheRun()} turns a failed review into a
 *       clean one - the single most dangerous shortcut available here;</li>
 *   <li>drop the overflow retry and
 *       {@link #aContextOverflowInsideASubagentIsRetriedRatherThanFailingIt()} kills a run for a
 *       recoverable refusal.</li>
 * </ul>
 *
 * <p>No Spring context, no database and no language model: the model is {@link RoutedChatModel},
 * PostgreSQL is {@link StubRuleSqlEvaluator} and the evidence is {@link AgentTestFixtures}, whose
 * planted activity makes the queries answer SANCTIONED_WIRE 30 + STRUCTURING 20 +
 * UNATTRIBUTED_CRYPTO 15 = 65 (HIGH), with DECLINE_BURST not triggered.
 */
class RuleCoverageGuaranteeTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    private static final ScriptedChatModel.Turn SUBMIT_HIGH = calls(
            RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
            "{\"risk_level\":\"HIGH\",\"summary\":\"Sanctioned wire and structuring.\","
                    + "\"recommendations\":\"Escalate.\"}");

    // ==================================================================
    // 1. The model server dies during the closing conversation
    // ==================================================================

    @Test
    @DisplayName("a model failure after the fan-out keeps every verdict the subagents submitted, "
            + "and the run is failed with the real cause")
    void aModelFailureInTheSummaryPhaseKeepsEveryVerdictTheSubagentsSubmitted() {
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = AgentTestFixtures.coveringModel(rules,
                fails(new IllegalStateException("the model server closed the connection")));

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context,
                        AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2)));

        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("closed the connection"));

        AgentRunResult result = failure.result();
        assertNotNull(result, "a failed run must still carry the work the subagents did");
        assertEquals(4, result.ruleOutcomes().size(), "every verdict survives");
        assertTrue(result.coverageComplete(), "coverage was complete; it is the summary that died");
        assertTrue(result.steps() >= 5, "the failed turn still counts as a step the run got to");

        Map<String, RuleOutcome> byName = outcomes(result);
        assertEquals("The activity this rule's condition names.",
                byName.get(SANCTIONED_WIRE).rationale());
        assertTrue(byName.values().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.SQL_DERIVED));

        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel(), "the mechanical band stands on its own");
        assertEquals(1, countSteps(trace, TraceStep.Type.FINAL));
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_FAILED),
                "a fully covered run that broke later is not a coverage failure");
    }

    // ==================================================================
    // 2. A rule whose query never runs
    // ==================================================================

    @Test
    @DisplayName("a rule whose query is refused every time - in its subagent and in the retry - is "
            + "left UNJUDGED and fails the run; it is never quietly recorded as not triggered")
    void aRuleWhoseQueryNeverRunsIsLeftUnjudgedAndFailsTheRun() {
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);
        // Three rules answer normally; every query written for the fourth is refused.
        StubRuleSqlEvaluator sql = AgentTestFixtures.evaluator(context)
                .rejecting("card", "unknown column card.declined_at");

        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, SUBMIT_HIGH);
        // Both subagent attempts for the decline rule spend their whole budget on refused queries:
        // three reach the database (the run-wide per-rule attempt budget), the rest hit the
        // exhausted budget. The route's queue simply continues into the retry.
        model.route(declines.getRuleId().toString(), List.of(
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Five declined authorisations inside a rolling day.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Declined authorisations, counted over a day.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Declines, once more.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Declines, one last try.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Declines, retry attempt.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Declines, final."))));

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> AgentTestFixtures.run(model, context, sql,
                        AgentTestFixtures.properties(6, 1)));
        AgentRunResult result = failure.result();

        // The whole point: no verdict at all, rather than a comfortable one.
        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());
        assertFalse(result.coverageComplete());
        assertEquals(List.of(DECLINE_BURST),
                result.unjudgedRules().stream().map(UnjudgedRule::ruleName).toList());
        assertEquals(3, result.rulesJudged());
        assertFalse(outcomes(result).containsKey(DECLINE_BURST),
                "a rule whose query never ran must not appear as an outcome - 'not triggered, 0.00' "
                        + "would be indistinguishable from a rule that was checked and cleared");
        assertTrue(RiskAssessmentRows.build(result.assessmentId(), result.ruleOutcomes(),
                        AgentTestFixtures.NOW).stream()
                        .noneMatch(row -> row.getRuleId().equals(declines.getRuleId())),
                "and it must not reach risk_assessments either");

        // The retry does not refill the query budget: three queries reached the database across
        // both subagents, and spending them is visible.
        assertEquals(3, sql.executed().stream().filter(query -> query.contains("card")).count(),
                "the per-rule budget is the run's, not one subagent conversation's");
        List<TraceStep> attempts = trace.steps().stream()
                .filter(step -> TraceStep.Type.TOOL_CALL.equals(step.type()))
                .filter(step -> RiskAgentTools.EVALUATE_RULE.equals(step.tool()))
                .filter(step -> DECLINE_BURST.equals(step.ruleName()))
                .toList();
        assertTrue(attempts.size() >= 3, "every refused attempt is on the transcript");
        assertTrue(attempts.stream().anyMatch(step -> step.outcome() != null
                        && step.outcome().startsWith("query rejected (attempt 3)")),
                attempts.stream().map(TraceStep::outcome).collect(Collectors.joining(" | ")));
        assertTrue(attempts.getFirst().detail().get("sql").stringValue().contains("card"),
                "a refused attempt keeps the query that was refused");

        // What did run is still scored, and the run still says it is not a finished review.
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));
        assertTrue(result.summary().contains(DECLINE_BURST));
    }

    // ==================================================================
    // 3. The model server refuses a subagent's prompt as too large
    // ==================================================================

    @Test
    @DisplayName("a prompt the model server refuses as too large is compacted and replayed inside "
            + "the subagent, and the run finishes normally")
    void aContextOverflowInsideASubagentIsRetriedRatherThanFailingIt() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        UUID runId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(runId);
        AgentRunContext context = AgentTestFixtures.context(runId, trace, rules);

        RoutedChatModel model = AgentTestFixtures.coveringModel(rules, SUBMIT_HIGH);
        model.route(sanctioned.getRuleId().toString(), List.of(
                fails(new IllegalStateException(
                        "400 Bad Request: the prompt exceeds the model's context length")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                        "Payments over 10,000 to a sanctioned jurisdiction."))));

        AgentRunResult result = AgentTestFixtures.run(model, context,
                AgentTestFixtures.evaluator(context), AgentTestFixtures.properties(12, 2));

        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("too large"),
                "the trace must show that the transcript was compacted and the turn replayed");
        assertTrue(result.coverageComplete(), "a recovered overflow must not cost the run its coverage");
        assertEquals(4, result.rulesJudged());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
        assertEquals(2, model.calls(sanctioned.getRuleId().toString()),
                "the refused turn and its replay are one subagent, not a retry");
    }

    // ==================================================================
    // 4. Settling an empty run
    // ==================================================================

    @Test
    @DisplayName("settling a run in which nothing was judged invents nothing")
    void settlingWithoutAnyVerdictProducesNoOutcomeAtAll() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        AgentProperties properties = AgentTestFixtures.properties(12, 3);

        // settle() is what RiskAgentLoop calls when a run dies before it could finish. With no
        // verdicts to settle it must produce an empty, honest result - not four zero-score rows.
        AgentRunResult result = new RiskAgentLoop(new ScriptedChatModel(List.of()),
                ToolCallingManager.builder().build(), jsonMapper, properties).settle(context, 0, 0L);

        assertTrue(result.ruleOutcomes().isEmpty());
        assertEquals(4, result.rulesTotal());
        assertEquals(4, result.unjudgedRules().size());
        assertFalse(result.coverageComplete());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertNotNull(result.summary());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));
        assertTrue(RiskAssessmentRows.build(result.assessmentId(), result.ruleOutcomes(),
                AgentTestFixtures.NOW).isEmpty(), "nothing judged means nothing written");
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));
    }

    // ------------------------------------------------------------------

    private static Map<String, RuleOutcome> outcomes(AgentRunResult result) {
        return result.ruleOutcomes().stream()
                .collect(Collectors.toMap(RuleOutcome::ruleName, outcome -> outcome));
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
