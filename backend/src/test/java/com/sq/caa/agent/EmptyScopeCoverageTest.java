package com.sq.caa.agent;

import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.agent.ToolPayloads.CustomerProfile;
import com.sq.caa.agent.ToolPayloads.QueryRejected;
import com.sq.caa.agent.ToolPayloads.RuleList;
import com.sq.caa.agent.ToolPayloads.TransactionPage;
import com.sq.caa.agent.ToolPayloads.VerdictAck;
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
 * is always in the coverage set, and for a customer with zero transactions there is nothing for the
 * agent to write a query about.
 *
 * <p>Three things have to hold there, and all three are asserted below.
 *
 * <ul>
 *   <li><b>The analysis must not break.</b> A customer with no activity is a perfectly ordinary
 *       customer - a new account, an account whose activity was purged - and the run has to finish
 *       with a coherent band, a total score of zero and complete coverage counters.</li>
 *   <li><b>The rule still has to be judged.</b> "Nothing in scope" is not the same as "not checked":
 *       the agent must still write the query, and a run that skips the rule fails
 *       like any other.</li>
 *   <li><b>The audit claim must be exact.</b> {@code risk_assessments} is keyed by transaction, so a
 *       rule with nothing in scope writes no rows: for that rule, and only that rule, the evidence
 *       that it was checked lives in {@code analysis_runs.rules_evaluated} / {@code rules_total} /
 *       {@code coverage_complete} and in the run trace instead. The class javadoc of
 *       {@link RiskAssessmentRows} and the {@code V1__baseline.sql} comment say exactly this; the
 *       assertions here are what keep them honest.</li>
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
            .thresholdLogic("A single transaction of more than 100,000 in any currency, of any "
                    + "activity type.")
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
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(dormantAccount,
                        "SELECT t.transaction_id FROM tx t WHERE t.amount > 100000",
                        "Any transaction of this customer above 100,000.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"LOW","summary":"No activity on file.",\
                        "recommendations":"Schedule a periodic review."}""")));

        AgentRunResult result = run(model, context, 40);

        // The run finished normally.
        assertEquals(1, result.rulesTotal());
        assertEquals(1, result.ruleOutcomes().size(), "the rule was judged, not skipped");
        assertEquals(1, result.rulesJudged());
        assertTrue(result.unjudgedRules().isEmpty());
        assertTrue(result.coverageComplete());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertEquals(RiskLevel.LOW, result.agentRiskLevel());
        assertNotNull(result.summary());

        RuleOutcome outcome = result.ruleOutcomes().getFirst();
        assertFalse(outcome.triggered());
        assertEquals(RuleVerdictSource.SQL_DERIVED, outcome.source());
        assertEquals(0, outcome.evaluatedTransactionCount());
        assertEquals(0, outcome.matchedCount());
        assertTrue(outcome.inScopeTransactionIds().isEmpty());
        assertNotNull(outcome.rationale());
        assertNotNull(outcome.sql(), "the query that answered the rule is kept, even a query that "
                + "could only ever return nothing");

        // The edge itself: nothing to key a row on, so the table records nothing for this rule...
        List<RiskAssessment> rows =
                RiskAssessmentRows.build(assessmentId, result.ruleOutcomes(), Instant.now());
        assertTrue(rows.isEmpty(),
                "risk_assessments is keyed by transaction; a rule with none in scope writes no rows");

        // ... which is why the run's own counters and its trace are the authoritative record that
        // the rule was checked. Both carry it.
        assertEquals(result.rulesTotal(), result.rulesJudged(),
                "rules_evaluated == rules_total is what proves coverage for an empty-scope rule");
        assertTrue(trace.steps().stream()
                        .anyMatch(step -> TraceStep.Type.TOOL_CALL.equals(step.type())
                                && RiskAgentTools.EVALUATE_RULE.equals(step.tool())),
                "the trace must show the rule being evaluated");
        assertTrue(trace.steps().stream().anyMatch(step -> TraceStep.Type.FINAL.equals(step.type())));
    }

    @Test
    @DisplayName("an empty-scope rule still has to be judged: skipping it fails the run like any other")
    void anEmptyScopeRuleThatIsNeverJudgedStillFailsTheRun() {
        UUID assessmentId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(assessmentId);
        AgentRunContext context = AgentTestFixtures.contextOver(assessmentId, trace, rules, List.of());

        // "There is nothing to look at, so there is nothing to say" is exactly the reasoning the
        // coverage guarantee exists to refuse.
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_TRANSACTIONS, "{}"),
                says("This customer has no activity, so there is nothing to assess."),
                says("As I said, nothing to assess."),
                says("Nothing to assess."),
                says("Nothing to assess.")));

        AgentRunFailedException failure =
                assertThrows(AgentRunFailedException.class, () -> run(model, context, 40));
        AgentRunResult result = failure.result();

        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());
        assertEquals(1, result.unjudgedRules().size());
        assertEquals(dormantAccount.getRuleName(), result.unjudgedRules().getFirst().ruleName());
        assertFalse(result.coverageComplete());
        assertTrue(result.ruleOutcomes().isEmpty());
        assertTrue(RiskAssessmentRows.build(assessmentId, result.ruleOutcomes(), Instant.now()).isEmpty());
        assertTrue(trace.steps().stream()
                        .anyMatch(step -> TraceStep.Type.COVERAGE_FAILED.equals(step.type())
                                && step.text().contains(dormantAccount.getRuleName())),
                "the trace names the rule that was never judged");
    }

    @Test
    @DisplayName("the tools describe an empty customer without failing, and no query can trigger a "
            + "rule with nothing in scope")
    void theToolsAnswerForACustomerWithNoActivity() {
        UUID assessmentId = UUID.randomUUID();
        AnalysisTrace trace = AgentTestFixtures.trace(assessmentId);
        AgentRunContext context = AgentTestFixtures.contextOver(assessmentId, trace, rules, List.of());
        // A query that returns an id for a customer with no activity cannot happen against the real
        // evaluator - its CTEs are scoped to the customer - so this stub is deliberately impossible.
        // The point is that the tools do not take a query result on trust either.
        StubRuleSqlEvaluator sql = new StubRuleSqlEvaluator()
                .matching("OR t.amount > 0", List.of(UUID.randomUUID()));
        RiskAgentTools tools = new RiskAgentTools(context, null, null, sql, jsonMapper, 25, 3);

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
        assertTrue(checklist.rules().getFirst().condition().contains("more than 100,000"));

        QueryRejected refused = assertInstanceOf(QueryRejected.class, tools.evaluateRule(
                dormantAccount.getRuleId().toString(),
                "SELECT t.transaction_id FROM tx t WHERE t.amount > 100000 OR t.amount > 0",
                "Any transaction at all."));
        assertTrue(refused.reason().contains("not this customer's"), refused.reason());
        assertFalse(context.isEvaluated(dormantAccount.getRuleId()),
                "a result that names a transaction the rule does not cover records nothing");

        VerdictAck accepted = assertInstanceOf(VerdictAck.class, tools.evaluateRule(
                dormantAccount.getRuleId().toString(),
                "SELECT t.transaction_id FROM tx t WHERE t.amount > 100000",
                "Any transaction of this customer above 100,000."));
        assertTrue(accepted.accepted());
        assertFalse(accepted.triggered(), "there is nothing for the query to return");
        assertEquals(0, BigDecimal.ZERO.compareTo(accepted.score()));
        assertEquals(0, accepted.verdictsStillRequired());
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context, int maxSteps) {
        AgentProperties properties = new AgentProperties(maxSteps, MAX_COVERAGE_REPROMPTS, 3, 4096,
                0.1, 32768, 1536, 10, "test-model", 2, 16, Duration.ofMinutes(5),
                Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, new StubRuleSqlEvaluator(),
                jsonMapper, 25, 3);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }
}
