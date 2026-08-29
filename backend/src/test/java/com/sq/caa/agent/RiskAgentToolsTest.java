package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.agent.ToolPayloads.CustomerProfile;
import com.sq.caa.agent.ToolPayloads.FinalAck;
import com.sq.caa.agent.ToolPayloads.RuleEngineVerdict;
import com.sq.caa.agent.ToolPayloads.RuleList;
import com.sq.caa.agent.ToolPayloads.ToolError;
import com.sq.caa.agent.ToolPayloads.TransactionDetail;
import com.sq.caa.agent.ToolPayloads.TransactionPage;
import com.sq.caa.agent.ToolPayloads.VerdictAck;
import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools the agent reasons through, exercised directly.
 *
 * <p>The coverage gate lives partly here - {@code submit_final_assessment} is what refuses to end an
 * incomplete analysis - so these are not incidental unit tests: they pin the contract the loop
 * depends on.
 */
class RiskAgentToolsTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();
    private final AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
    private final AgentRunContext context =
            AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
    private final RiskAgentTools tools =
            new RiskAgentTools(context, null, null, JsonMapper.builder().build(), 25);

    @Test
    @DisplayName("every tool is exposed with a description and a typed schema")
    void toolsAreDiscoverable() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        assertEquals(9, callbacks.length);
        List<String> names = List.of(callbacks).stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
        assertTrue(names.containsAll(List.of(
                RiskAgentTools.GET_CUSTOMER_PROFILE,
                RiskAgentTools.GET_CUSTOMER_ACTIVITY_SUMMARY,
                RiskAgentTools.LIST_TRANSACTIONS,
                RiskAgentTools.GET_TRANSACTION_DETAILS,
                RiskAgentTools.LIST_RISK_RULES,
                RiskAgentTools.SEARCH_POLICY_KNOWLEDGE,
                RiskAgentTools.EVALUATE_RULE_DETERMINISTICALLY,
                RiskAgentTools.SUBMIT_RULE_EVALUATION,
                RiskAgentTools.SUBMIT_FINAL_ASSESSMENT)), names.toString());
        for (ToolCallback callback : callbacks) {
            assertTrue(callback.getToolDefinition().description().length() > 80,
                    callback.getToolDefinition().name() + " needs an operator-readable description");
            assertNotNull(callback.getToolDefinition().inputSchema());
        }
    }

    @Test
    @DisplayName("list_risk_rules is the checklist, and it tracks what is still outstanding")
    void listRiskRulesIsTheChecklist() {
        RuleList before = assertInstanceOf(RuleList.class, tools.listRiskRules());
        assertEquals(4, before.rulesTotal());
        assertEquals(0, before.verdictsSubmitted());
        assertEquals(4, before.verdictsStillRequired());
        assertTrue(before.rules().stream().noneMatch(ToolPayloads.RuleListing::verdictAlreadySubmitted));
        assertTrue(before.rules().stream()
                .allMatch(rule -> rule.logicInPlainEnglish() != null && rule.thresholdLogic() != null));

        tools.submitRuleEvaluation(id(SANCTIONED_WIRE), true, 30.0, List.of(), "RU wire.");

        RuleList after = assertInstanceOf(RuleList.class, tools.listRiskRules());
        assertEquals(1, after.verdictsSubmitted());
        assertEquals(3, after.verdictsStillRequired());
    }

    @Test
    @DisplayName("evaluate_rule_deterministically returns the engine's verdict and its evidence")
    void deterministicEvaluationReturnsEvidence() {
        RuleEngineVerdict verdict = assertInstanceOf(RuleEngineVerdict.class,
                tools.evaluateRuleDeterministically(id(SANCTIONED_WIRE)));
        assertTrue(verdict.triggered());
        assertEquals(1, verdict.matchedCount());
        assertEquals(1, verdict.matchedTransactionIds().size());
        assertEquals(0, new BigDecimal("30.00").compareTo(verdict.score()));
        assertFalse(verdict.degraded());
        assertFalse(verdict.sampleMatches().isEmpty());
        assertNotNull(verdict.sampleMatches().getFirst().whyItMatched());
    }

    @Test
    @DisplayName("an id that is not on the checklist is refused with a usable hint")
    void unknownRuleIsRefused() {
        ToolError error = assertInstanceOf(ToolError.class,
                tools.evaluateRuleDeterministically(UUID.randomUUID().toString()));
        assertTrue(error.hint().contains(SANCTIONED_WIRE));
        assertInstanceOf(ToolError.class, tools.evaluateRuleDeterministically("not-a-uuid"));
    }

    @Test
    @DisplayName("a verdict that contradicts the engine is accepted, flagged and corrected")
    void verdictIsCrossCheckedAgainstTheEngine() {
        VerdictAck ack = assertInstanceOf(VerdictAck.class,
                tools.submitRuleEvaluation(id(STRUCTURING), false, 0.0, List.of(),
                        "These payments look ordinary."));

        assertTrue(ack.accepted());
        assertFalse(ack.agreesWithRuleEngine());
        assertTrue(ack.crossCheck().contains("DISAGREEMENT"));
        assertTrue(ack.recordedAsTriggered(), "the engine's verdict is what will be recorded");
        assertEquals(0, new BigDecimal("20.00").compareTo(ack.recordedScore()));
        assertEquals(3, ack.verdictsStillRequired());
        assertEquals(3, ack.rulesStillMissingAVerdict().size());
        assertTrue(ack.nextAction().contains("Still missing"));
    }

    @Test
    @DisplayName("submit_final_assessment refuses to end an incomplete analysis")
    void finalAssessmentIsRejectedWhileRulesAreOpen() {
        FinalAck rejected = assertInstanceOf(FinalAck.class,
                tools.submitFinalAssessment("HIGH", "All done.", "File a report."));

        assertFalse(rejected.accepted());
        assertEquals(4, rejected.verdictsStillRequired());
        assertEquals(4, rejected.rulesStillMissingAVerdict().size());
        assertTrue(rejected.message().startsWith("REJECTED"));
        assertFalse(context.isConcluded(), "a rejected conclusion must not end the run");
        assertTrue(context.consumeConclusionRejected(), "the loop must be told to reprompt");
        assertTrue(trace.steps().stream()
                .anyMatch(step -> TraceStep.Type.COVERAGE_REPROMPT.equals(step.type())));
    }

    @Test
    @DisplayName("submit_final_assessment is accepted once every rule has a verdict")
    void finalAssessmentIsAcceptedWhenCoverageIsComplete() {
        for (RiskRule rule : rules) {
            tools.submitRuleEvaluation(rule.getRuleId().toString(), true, 1.0, List.of(), "checked");
        }
        FinalAck accepted = assertInstanceOf(FinalAck.class,
                tools.submitFinalAssessment("critical", "Serious findings.", "Escalate."));

        assertTrue(accepted.accepted());
        assertEquals(0, accepted.verdictsStillRequired());
        assertTrue(context.isConcluded());
        assertEquals(com.sq.caa.domain.RiskLevel.CRITICAL, context.finalAssessment().riskLevel());
    }

    @Test
    @DisplayName("the customer and the activity are read from the run's own snapshot")
    void readsAreScopedToTheCustomerUnderAnalysis() {
        CustomerProfile profile = assertInstanceOf(CustomerProfile.class, tools.getCustomerProfile());
        assertEquals("Dana Kovac", profile.fullName());
        assertEquals("CH", profile.countryOfResidence());
        assertEquals(6, profile.transactionCount());
        assertEquals(List.of("CARD", "CRYPTO", "PAYMENT"), profile.activityTypesPresent());

        TransactionPage payments = assertInstanceOf(TransactionPage.class,
                tools.listTransactions("PAYMENT", null, 9000.0, 10, 0));
        assertEquals(4, payments.matchingTransactions());
        assertEquals(4, payments.returned());
        assertFalse(payments.moreAvailable());
        assertTrue(payments.transactions().getFirst().counterparty().contains("RU"));

        TransactionPage paged = assertInstanceOf(TransactionPage.class,
                tools.listTransactions(null, "Completed", null, 2, 0));
        assertEquals(6, paged.matchingTransactions());
        assertEquals(2, paged.returned());
        assertTrue(paged.moreAvailable());

        assertInstanceOf(ToolError.class, tools.listTransactions("WIRE", null, null, null, null));
        assertInstanceOf(ToolError.class, tools.getTransactionDetails(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("get_transaction_details answers from the run's snapshot, type-specific detail included")
    void transactionDetailsAreServedFromTheSnapshot() {
        // These tools hold no transaction service and no repository: the only place any of the
        // fields below can come from is the run's EvaluationBatch.
        Transaction card = snapshot(ActivityType.CARD);
        TransactionDetail detail = assertInstanceOf(TransactionDetail.class,
                tools.getTransactionDetails(card.getTransactionId().toString()));

        assertEquals(card.getTransactionId().toString(), detail.transactionId());
        assertEquals("11111111-1111-4111-8111-111111111111", detail.customerId());
        assertEquals("Dana Kovac", detail.customerName());
        assertEquals("CARD", detail.activityType());
        assertEquals("Completed", detail.status());
        assertEquals(0, new BigDecimal("120.00").compareTo(detail.amount()));
        assertNull(detail.payment());
        assertNull(detail.crypto());
        assertNotNull(detail.card());
        assertEquals("Coop Supermarket", detail.card().merchantName());
        assertEquals("5411", detail.card().mccCode());
        assertTrue(detail.card().cardPresent());
        // Neither the PAN nor the authorisation code is a rule-DSL field, so they can only have
        // been carried by the batch's own record of the transaction.
        assertEquals("****4242", detail.card().cardPan());
        assertEquals("Debit", detail.card().cardType());
        assertEquals("AUTH-1", detail.card().authorizationCode());
        assertNull(detail.card().declineReason());

        // The aggregates alongside it are the engine's own snapshots for this transaction, not a
        // recomputation: this is the oldest transaction on file, so its backward-looking windows
        // contain only itself.
        assertEquals(1, detail.customerAggregatesAtThisTransaction().transactionsInPrior24h());
        assertEquals(0, new BigDecimal("120.00")
                .compareTo(detail.customerAggregatesAtThisTransaction().largestAmountInPrior30d()));

        Transaction crypto = snapshot(ActivityType.CRYPTO);
        TransactionDetail cryptoDetail = assertInstanceOf(TransactionDetail.class,
                tools.getTransactionDetails(crypto.getTransactionId().toString()));
        assertNotNull(cryptoDetail.crypto());
        assertEquals("XMR", cryptoDetail.crypto().blockchain());
        assertEquals("wallet-from", cryptoDetail.crypto().walletAddressFrom());
        assertEquals("wallet-to", cryptoDetail.crypto().walletAddressTo());
        assertEquals("0xfeed", cryptoDetail.crypto().txHash());
        assertNull(cryptoDetail.crypto().exchangeName(), "an unattributed transfer has no exchange");
    }

    private Transaction snapshot(ActivityType type) {
        return context.batch().transactions().stream()
                .filter(transaction -> transaction.getActivityType() == type)
                .findFirst()
                .orElseThrow();
    }

    private String id(String ruleName) {
        return AgentTestFixtures.ruleNamed(rules, ruleName).getRuleId().toString();
    }
}
