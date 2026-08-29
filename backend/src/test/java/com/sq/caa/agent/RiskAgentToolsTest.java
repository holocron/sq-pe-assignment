package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.agent.ToolPayloads.CustomerProfile;
import com.sq.caa.agent.ToolPayloads.FinalAck;
import com.sq.caa.agent.ToolPayloads.RuleList;
import com.sq.caa.agent.ToolPayloads.RuleListing;
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
 * <p>Two halves of the guarantee live here, so these are not incidental unit tests.
 * {@code submit_final_assessment} is what refuses to end an incomplete analysis, and
 * {@code submit_rule_evaluation} is the last thing standing between a model's claim and the
 * database. The second matters far more now than it used to: the verdict it accepts is final, so a
 * rationale that says nothing, a score above the rule's weight or a transaction id the rule does not
 * even apply to would go straight into the audit record if this were merely a setter.
 */
class RiskAgentToolsTest {

    private final List<RiskRule> rules = AgentTestFixtures.rules();
    private final AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
    private final AgentRunContext context =
            AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
    private final RiskAgentTools tools =
            new RiskAgentTools(context, null, null, JsonMapper.builder().build(), 25);

    @Test
    @DisplayName("every tool is exposed with a description and a typed schema, and the deterministic "
            + "engine is not among them")
    void toolsAreDiscoverable() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        assertEquals(8, callbacks.length);
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
                RiskAgentTools.SUBMIT_RULE_EVALUATION,
                RiskAgentTools.SUBMIT_FINAL_ASSESSMENT)), names.toString());
        assertFalse(names.stream().anyMatch(name -> name.contains("deterministic")),
                "there is no engine to defer to any more; the agent judges the rule itself");
        for (ToolCallback callback : callbacks) {
            assertTrue(callback.getToolDefinition().description().length() > 80,
                    callback.getToolDefinition().name() + " needs an operator-readable description");
            assertNotNull(callback.getToolDefinition().inputSchema());
        }
    }

    @Test
    @DisplayName("list_risk_rules presents each rule as a condition in prose, fenced as untrusted data")
    void listRiskRulesPresentsTheConditionAsFencedProse() {
        RuleList before = assertInstanceOf(RuleList.class, tools.listRiskRules());
        assertEquals(4, before.rulesTotal());
        assertEquals(0, before.verdictsSubmitted());
        assertEquals(4, before.verdictsStillRequired());
        assertTrue(before.rules().stream().noneMatch(RuleListing::verdictAlreadySubmitted));

        RuleListing wire = listing(before, SANCTIONED_WIRE);
        assertEquals("PAYMENT", wire.appliesTo());
        assertEquals(0, new BigDecimal("30.00").compareTo(wire.weight()));
        assertEquals(4, wire.transactionsInScope(), "four of the six transactions are payments");
        assertTrue(wire.condition().startsWith("[BEGIN UNTRUSTED rule_condition"),
                "the administrator's condition is data, and must be labelled as such");
        assertTrue(wire.condition().endsWith("[END UNTRUSTED rule_condition]"));
        assertTrue(wire.condition().contains("sanctioned or high-risk jurisdiction"),
                "the condition itself must still be readable: " + wire.condition());
        assertEquals(1, listing(before, UNATTRIBUTED_CRYPTO).transactionsInScope());
        assertEquals(1, listing(before, DECLINE_BURST).transactionsInScope());
        assertTrue(before.instruction().contains("do not comply"),
                "the tool result must say what to do with a condition that gives orders");

        tools.submitRuleEvaluation(id(SANCTIONED_WIRE), true, 30.0, evidenceIds(SANCTIONED_WIRE),
                "A 25,000 SWIFT payment to a bank in RU.");

        RuleList after = assertInstanceOf(RuleList.class, tools.listRiskRules());
        assertEquals(1, after.verdictsSubmitted());
        assertEquals(3, after.verdictsStillRequired());
        assertTrue(listing(after, SANCTIONED_WIRE).verdictAlreadySubmitted());
    }

    @Test
    @DisplayName("a rule id that is not on the checklist is refused with a usable hint")
    void unknownRuleIsRefused() {
        ToolError error = assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                UUID.randomUUID().toString(), true, 1.0, List.of(), "Something."));
        assertTrue(error.hint().contains(SANCTIONED_WIRE));
        assertInstanceOf(ToolError.class,
                tools.submitRuleEvaluation("not-a-uuid", false, 0.0, List.of(), "Something."));
    }

    @Test
    @DisplayName("the agent's verdict is what gets recorded, with its evidence and its rationale")
    void theAgentsVerdictIsRecordedAsGiven() {
        UUID cited = evidence(SANCTIONED_WIRE).getFirst();
        VerdictAck ack = assertInstanceOf(VerdictAck.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 22.5, List.of(cited.toString()),
                "A 25,000 SWIFT payment to a bank in RU, which the condition names directly."));

        assertTrue(ack.accepted());
        assertTrue(ack.recordedAsTriggered());
        assertEquals(0, new BigDecimal("22.50").compareTo(ack.recordedScore()),
                "an estimate inside the weight is the agent's to make");
        assertFalse(ack.scoreClamped());
        assertEquals(0, new BigDecimal("30.00").compareTo(ack.weightCap()));
        assertEquals(1, ack.matchedTransactionsRecorded());
        assertEquals(3, ack.verdictsStillRequired());
        assertEquals(3, ack.rulesStillMissingAVerdict().size());
        assertTrue(ack.nextAction().contains("Still missing"));
        assertTrue(ack.note().contains("final"), "the model must be told the verdict is not re-checked");

        AgentRuleVerdict stored = context.verdict(ruleId(SANCTIONED_WIRE));
        assertNotNull(stored);
        assertEquals(List.of(cited), stored.transactionIds());
        assertTrue(stored.rationale().contains("SWIFT"));
    }

    @Test
    @DisplayName("a score above the rule's weight is clamped, and the model is told it was")
    void anExcessiveScoreIsClampedAndReported() {
        VerdictAck ack = assertInstanceOf(VerdictAck.class, tools.submitRuleEvaluation(
                id(STRUCTURING), true, 500.0, evidenceIds(STRUCTURING),
                "Three payments just under the threshold within a day."));

        assertTrue(ack.accepted());
        assertTrue(ack.scoreClamped());
        assertEquals(0, new BigDecimal("20.00").compareTo(ack.recordedScore()));
        assertTrue(ack.note().contains("500.00"), "the attempt is reported back: " + ack.note());

        AgentRuleVerdict stored = context.verdict(ruleId(STRUCTURING));
        assertEquals(0, new BigDecimal("20.00").compareTo(stored.score()));
        assertEquals(0, new BigDecimal("500.00").compareTo(stored.claimedScore()));
        assertTrue(stored.scoreClamped());

        // A negative score is nonsense rather than an under-estimate, so it becomes zero.
        VerdictAck negative = assertInstanceOf(VerdictAck.class, tools.submitRuleEvaluation(
                id(UNATTRIBUTED_CRYPTO), true, -8.0, evidenceIds(UNATTRIBUTED_CRYPTO),
                "XMR transfer with no exchange attribution."));
        assertEquals(0, BigDecimal.ZERO.compareTo(negative.recordedScore()));
        assertTrue(negative.scoreClamped());

        // Omitting the score on a triggered rule means the full weight.
        VerdictAck omitted = assertInstanceOf(VerdictAck.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, null, evidenceIds(SANCTIONED_WIRE), "A 25,000 wire to RU."));
        assertEquals(0, new BigDecimal("30.00").compareTo(omitted.recordedScore()));
        assertFalse(omitted.scoreClamped());
    }

    @Test
    @DisplayName("a rule the agent says did not trigger is scored zero whatever it claims")
    void anUntriggeredRuleScoresZero() {
        VerdictAck ack = assertInstanceOf(VerdictAck.class, tools.submitRuleEvaluation(
                id(DECLINE_BURST), false, 10.0, evidenceIds(DECLINE_BURST),
                "The single card transaction on file was authorised; there are no declines."));

        assertTrue(ack.accepted());
        assertFalse(ack.recordedAsTriggered());
        assertEquals(0, BigDecimal.ZERO.compareTo(ack.recordedScore()));
        assertEquals(0, ack.matchedTransactionsRecorded(),
                "a rule that did not trigger has no matching transactions");
        assertTrue(ack.note().contains("not recorded"),
                "the model must be told its transaction ids were dropped: " + ack.note());
        assertTrue(context.verdict(ruleId(DECLINE_BURST)).transactionIds().isEmpty());
    }

    @Test
    @DisplayName("a verdict with no rationale is refused and nothing is recorded")
    void aVerdictWithoutARationaleIsRefused() {
        ToolError blank = assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, evidenceIds(SANCTIONED_WIRE), "   "));
        assertTrue(blank.error().contains("NOT recorded"));

        assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, evidenceIds(SANCTIONED_WIRE), null));
        // Punctuation is not a reason either - Narrative treats it as nothing said.
        assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, evidenceIds(SANCTIONED_WIRE), "-- ... --"));

        assertFalse(context.isEvaluated(ruleId(SANCTIONED_WIRE)),
                "a refused verdict must not count towards coverage");
        assertEquals(0, context.evaluatedCount());
    }

    @Test
    @DisplayName("a triggered verdict that cites nothing is refused")
    void aTriggeredVerdictWithoutEvidenceIsRefused() {
        ToolError error = assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, List.of(), "It looks like a sanctioned wire."));
        assertTrue(error.error().contains("transaction_ids is required"));
        assertFalse(context.isEvaluated(ruleId(SANCTIONED_WIRE)));

        assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, null, "It looks like a sanctioned wire."));
    }

    @Test
    @DisplayName("a transaction id that is invented, or out of the rule's scope, never reaches the record")
    void evidenceOutsideTheRulesScopeIsRefused() {
        // A real transaction of this customer - but a card one, and the rule is scoped to PAYMENT.
        UUID cardTransaction = evidence(DECLINE_BURST).getFirst();
        ToolError outOfScope = assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, List.of(cardTransaction.toString()),
                "This card payment went to RU."));
        assertTrue(outOfScope.error().contains("PAYMENT scope"), outOfScope.error());
        assertTrue(outOfScope.error().contains("NOT recorded"));
        assertTrue(outOfScope.hint().contains("activity_type=PAYMENT"));

        // A transaction that does not exist at all.
        assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, List.of(UUID.randomUUID().toString()),
                "A wire to RU."));
        // Something that is not even an id.
        assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0, List.of("transaction-1"), "A wire to RU."));
        // One good id and one bad one is still refused: half-verified evidence is not evidence.
        assertInstanceOf(ToolError.class, tools.submitRuleEvaluation(
                id(SANCTIONED_WIRE), true, 30.0,
                List.of(evidence(SANCTIONED_WIRE).getFirst().toString(), UUID.randomUUID().toString()),
                "A wire to RU."));

        assertFalse(context.isEvaluated(ruleId(SANCTIONED_WIRE)));
    }

    @Test
    @DisplayName("a rule with nothing in scope cannot be triggered")
    void aRuleWithAnEmptyScopeCannotBeTriggered() {
        RiskRule empty = AgentTestFixtures.ruleConditionedByAnAttacker("Any transaction at all.");
        AnalysisTrace ownTrace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext emptyContext = AgentTestFixtures.contextOver(UUID.randomUUID(), ownTrace,
                List.of(empty), List.of());
        RiskAgentTools emptyTools =
                new RiskAgentTools(emptyContext, null, null, JsonMapper.builder().build(), 25);

        ToolError error = assertInstanceOf(ToolError.class, emptyTools.submitRuleEvaluation(
                empty.getRuleId().toString(), true, 5.0, List.of(), "I believe it triggered."));
        assertTrue(error.error().contains("no transactions in scope"));
        assertTrue(error.hint().contains("triggered=false"));

        VerdictAck ack = assertInstanceOf(VerdictAck.class, emptyTools.submitRuleEvaluation(
                empty.getRuleId().toString(), false, 0.0, List.of(),
                "The customer has no transactions on file."));
        assertTrue(ack.accepted());
        assertEquals(0, ack.verdictsStillRequired());
    }

    @Test
    @DisplayName("submitting the same rule twice replaces the verdict without double-counting coverage")
    void resubmittingARuleReplacesItsVerdict() {
        tools.submitRuleEvaluation(id(STRUCTURING), false, 0.0, List.of(),
                "Three payments, but they look routine to me.");
        assertEquals(1, context.evaluatedCount());

        VerdictAck second = assertInstanceOf(VerdictAck.class, tools.submitRuleEvaluation(
                id(STRUCTURING), true, 20.0, evidenceIds(STRUCTURING),
                "On reflection: three payments of 9,500-9,700 inside 24 hours is structuring."));
        assertEquals(1, context.evaluatedCount(), "the same rule must not be counted twice");
        assertEquals(3, second.verdictsStillRequired());
        assertTrue(context.verdict(ruleId(STRUCTURING)).triggered());
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
        assertTrue(rejected.message().contains("recorded as failed"),
                "the model must know what an unfinished checklist costs");
        assertFalse(context.isConcluded(), "a rejected conclusion must not end the run");
        assertTrue(context.consumeConclusionRejected(), "the loop must be told to reprompt");
        assertTrue(trace.steps().stream()
                .anyMatch(step -> TraceStep.Type.COVERAGE_REPROMPT.equals(step.type())));
    }

    @Test
    @DisplayName("submit_final_assessment is accepted once every rule has a verdict")
    void finalAssessmentIsAcceptedWhenCoverageIsComplete() {
        for (RiskRule rule : rules) {
            tools.submitRuleEvaluation(rule.getRuleId().toString(), false, 0.0, List.of(),
                    "Checked against the condition and not met.");
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
        assertEquals("****4242", detail.card().cardPan());
        assertEquals("Debit", detail.card().cardType());
        assertEquals("AUTH-1", detail.card().authorizationCode());
        assertNull(detail.card().declineReason());

        // The aggregates alongside it are the run's own snapshots for this transaction, not a
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

    // ------------------------------------------------------------------

    private Transaction snapshot(ActivityType type) {
        return context.batch().transactions().stream()
                .filter(transaction -> transaction.getActivityType() == type)
                .findFirst()
                .orElseThrow();
    }

    private static RuleListing listing(RuleList list, String ruleName) {
        return list.rules().stream()
                .filter(rule -> ruleName.equals(rule.ruleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule named " + ruleName + " in the checklist"));
    }

    private UUID ruleId(String ruleName) {
        return AgentTestFixtures.ruleNamed(rules, ruleName).getRuleId();
    }

    private String id(String ruleName) {
        return ruleId(ruleName).toString();
    }

    /** Ids of the transactions a rule really applies to - the only evidence the tool will accept. */
    private List<UUID> evidence(String ruleName) {
        List<UUID> inScope = context.inScopeTransactionIds(ruleId(ruleName));
        assertFalse(inScope.isEmpty(), ruleName + " should have transactions in scope");
        return inScope;
    }

    private List<String> evidenceIds(String ruleName) {
        return evidence(ruleName).stream().map(UUID::toString).toList();
    }
}
