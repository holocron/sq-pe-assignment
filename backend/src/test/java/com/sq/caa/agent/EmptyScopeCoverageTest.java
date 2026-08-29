package com.sq.caa.agent;

import static com.sq.caa.agent.ScriptedChatModel.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.agent.ToolPayloads.CustomerProfile;
import com.sq.caa.agent.ToolPayloads.RuleList;
import com.sq.caa.agent.ToolPayloads.TransactionPage;
import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A rule whose scope contains no transactions at all - and the one auditing edge it creates.
 *
 * <p>This is reachable in exactly one way: a customer with <b>no activity on file</b>. The coverage
 * set is built from the activity types the customer actually has, so an activity-scoped rule can
 * never be listed for a customer who has none of that activity; an {@code ALL}-scoped rule, however,
 * is always in the coverage set, and for a customer with zero transactions it is evaluated against
 * zero of them.
 *
 * <p>Two things have to hold there, and both are asserted below.
 *
 * <ul>
 *   <li><b>The analysis must not break.</b> A customer with no activity is a perfectly ordinary
 *       customer - a new account, an account whose activity was purged - and the run has to finish
 *       with a coherent band, a total score of zero and complete coverage counters, whether the
 *       model drives it or the deterministic path does.</li>
 *   <li><b>The audit claim must be exact.</b> {@code risk_assessments} is keyed by transaction, so a
 *       rule with nothing in scope writes no rows: for that rule, and only that rule, the evidence
 *       that it was checked lives in {@code analysis_runs.rules_evaluated} /
 *       {@code rules_total} / {@code coverage_complete} and in the run trace instead. The class
 *       javadoc of {@link RiskAssessmentRows} and the {@code V1__baseline.sql} comment say exactly
 *       this; the assertions here are what keep them honest.</li>
 * </ul>
 */
class EmptyScopeCoverageTest {

    private static final int MAX_COVERAGE_REPROMPTS = 3;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** An ALL-scoped rule: in the coverage set of every customer, including one with no activity. */
    private final RiskRule dormantAccount = RiskRule.builder()
            .ruleId(UUID.randomUUID())
            .ruleName("Any single transaction above 100,000")
            .appliesTo(RuleScope.ALL)
            .thresholdLogic("""
                    {"op":"AND","conditions":[{"field":"amount","operator":"GT","value":100000}]}""")
            .weight(new BigDecimal("40.00"))
            .build();

    private final List<RiskRule> rules = List.of(dormantAccount);

    @Test
    @DisplayName("a customer with no activity at all still completes an analysis, with full coverage "
            + "and a coherent risk band")
    void anAnalysisOfACustomerWithNoActivityStillCompletes() {
        UUID assessmentId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(assessmentId);
        AgentRunContext context = AgentTestFixtures.contextOver(assessmentId, trace, rules, List.of());

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_TRANSACTIONS, "{}"),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, """
                        {"rule_id":"%s","triggered":false,"score":0,"transaction_ids":[],\
                        "rationale":"The customer has no transactions on file, so nothing can breach \
                        this rule."}""".formatted(dormantAccount.getRuleId())),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"LOW","summary":"No activity on file.",\
                        "recommendations":"Schedule a periodic review."}""")));

        AgentRunResult result = run(model, context, 40);

        // The run finished normally.
        assertEquals(1, result.rulesTotal());
        assertEquals(1, result.ruleOutcomes().size(), "the rule was evaluated, not skipped");
        assertEquals(1, result.rulesEvaluatedByAgent());
        assertEquals(0, result.rulesBackfilled());
        assertTrue(result.coverageComplete());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertEquals(RiskLevel.LOW, result.agentRiskLevel());
        assertNotNull(result.summary());
        assertEquals(0, result.disagreementCount(), "the engine and the agent both say not triggered");

        RuleOutcome outcome = result.ruleOutcomes().getFirst();
        assertFalse(outcome.triggered());
        assertEquals(0, outcome.evaluatedTransactionCount());
        assertEquals(0, outcome.matchedCount());
        assertTrue(outcome.inScopeTransactionIds().isEmpty());

        // The edge itself: nothing to key a row on, so the table records nothing for this rule...
        List<RiskAssessment> rows =
                RiskAssessmentRows.build(assessmentId, result.ruleOutcomes(), Instant.now());
        assertTrue(rows.isEmpty(),
                "risk_assessments is keyed by transaction; a rule with none in scope writes no rows");

        // ... which is why the run's own counters and its trace are the authoritative record that
        // the rule was checked. Both carry it.
        assertEquals(result.rulesTotal(), result.ruleOutcomes().size(),
                "rules_evaluated == rules_total is what proves coverage for an empty-scope rule");
        assertTrue(trace.steps().stream()
                        .anyMatch(step -> TraceStep.Type.TOOL_CALL.equals(step.type())
                                && RiskAgentTools.SUBMIT_RULE_EVALUATION.equals(step.tool())),
                "the trace must show the verdict being submitted");
        assertTrue(trace.steps().stream().anyMatch(step -> TraceStep.Type.FINAL.equals(step.type())));
    }

    @Test
    @DisplayName("the deterministic path closes the coverage set for an empty customer too")
    void theDeterministicPathAlsoCoversAnEmptyCustomer() {
        UUID assessmentId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(assessmentId);
        AgentRunContext context = AgentTestFixtures.contextOver(assessmentId, trace, rules, List.of());
        AgentProperties properties = new AgentProperties(40, MAX_COVERAGE_REPROMPTS, 4096, 0.1, 32768,
                1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), 25);

        // The path RiskAnalysisService falls back to when a run fails before the loop starts.
        AgentRunResult result = new RiskAgentLoop(new ScriptedChatModel(List.of()),
                ToolCallingManager.builder().build(), jsonMapper, properties).settle(context, 0, 0L);

        assertEquals(1, result.ruleOutcomes().size());
        assertEquals(1, result.rulesBackfilled());
        assertFalse(result.coverageComplete(), "the backfill was needed, so this is not a clean run");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertNotNull(result.summary());
        assertTrue(RiskAssessmentRows.build(assessmentId, result.ruleOutcomes(), Instant.now()).isEmpty());
        assertTrue(trace.steps().stream()
                        .anyMatch(step -> TraceStep.Type.BACKFILL.equals(step.type())
                                && step.text().contains(dormantAccount.getRuleName())),
                "the trace names the rule that was closed deterministically");
    }

    @Test
    @DisplayName("the tools describe an empty customer without failing")
    void theToolsAnswerForACustomerWithNoActivity() {
        UUID assessmentId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(assessmentId);
        AgentRunContext context = AgentTestFixtures.contextOver(assessmentId, trace, rules, List.of());
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);

        CustomerProfile profile = assertInstanceOf(CustomerProfile.class, tools.getCustomerProfile());
        assertEquals(0, profile.transactionCount());
        assertTrue(profile.activityTypesPresent().isEmpty());
        assertEquals("Dana Kovac", profile.fullName());

        TransactionPage page = assertInstanceOf(TransactionPage.class,
                tools.listTransactions(null, null, null, null, null));
        assertEquals(0, page.matchingTransactions());
        assertEquals(0, page.returned());
        assertFalse(page.moreAvailable());

        RuleList checklist = assertInstanceOf(RuleList.class, tools.listRiskRules());
        assertEquals(1, checklist.rulesTotal());
        assertEquals(0, checklist.rules().getFirst().transactionsInScope(),
                "the checklist tells the model up front that this rule has nothing in scope");

        ToolPayloads.RuleEngineVerdict verdict = assertInstanceOf(ToolPayloads.RuleEngineVerdict.class,
                tools.evaluateRuleDeterministically(dormantAccount.getRuleId().toString()));
        assertFalse(verdict.triggered());
        assertEquals(0, verdict.transactionsEvaluated());
        assertEquals(0, verdict.matchedCount());
        assertFalse(verdict.degraded(), "an empty scope is not a degraded evaluation");
        assertNotNull(verdict.explanation());
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context, int maxSteps) {
        AgentProperties properties = new AgentProperties(maxSteps, MAX_COVERAGE_REPROMPTS, 4096, 0.1,
                32768, 1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }
}
