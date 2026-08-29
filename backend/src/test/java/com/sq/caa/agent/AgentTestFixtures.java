package com.sq.caa.agent;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.CryptoActivity;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import com.sq.caa.rules.EvaluationBatch;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A small, fully in-memory customer with planted risk, and the four rules that judge it.
 *
 * <p>No Spring context, no database and no model: {@link EvaluationBatch} is a pure function of the
 * domain objects, which is what lets the coverage gate be tested as the piece of control flow it is.
 *
 * <p>Each rule's {@code threshold_logic} is what it is in production now - the condition in prose,
 * written by an administrator. Nothing evaluates it here: the scripted model plays the part of the
 * agent that reads it and judges. The verdicts the tests script are therefore the fixture's
 * expectations, and they are consistent throughout: SANCTIONED_WIRE 30, STRUCTURING 20,
 * UNATTRIBUTED_CRYPTO 15 and DECLINE_BURST not triggered. Total 65, which bands as HIGH.
 */
final class AgentTestFixtures {

    static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    static final String SANCTIONED_WIRE = "High-value wire to a sanctioned jurisdiction";
    static final String STRUCTURING = "Structuring: repeated payments just under the reporting threshold";
    static final String UNATTRIBUTED_CRYPTO = "Crypto transfer with no exchange attribution";
    static final String DECLINE_BURST = "Card decline burst";

    private AgentTestFixtures() {
    }

    static Customer customer() {
        return Customer.builder()
                .customerId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
                .firstName("Dana")
                .lastName("Kovac")
                .dob(LocalDate.of(1984, 3, 11))
                .country("CH")
                .build();
    }

    /** Activity with three planted patterns and one clean card payment. */
    static List<Transaction> transactions() {
        return List.of(
                payment("25000.00", "Completed", NOW.minusSeconds(3600), "SWIFT", "RU"),
                payment("9500.00", "Completed", NOW.minusSeconds(7200), "Wire", "DE"),
                payment("9600.00", "Completed", NOW.minusSeconds(10800), "Wire", "DE"),
                payment("9700.00", "Completed", NOW.minusSeconds(14400), "Wire", "DE"),
                crypto("4000.00", "Completed", NOW.minusSeconds(18000), "XMR", null),
                card("120.00", "Completed", NOW.minusSeconds(21600), "Coop Supermarket", "5411", true,
                        null));
    }

    /** The four rules, each with its condition written the way an administrator would write it. */
    static List<RiskRule> rules() {
        return List.of(
                rule(SANCTIONED_WIRE, RuleScope.PAYMENT, """
                        The customer sends a payment of more than 10,000 in any currency to a bank \
                        in a sanctioned or high-risk jurisdiction (Iran, North Korea, Syria, Russia \
                        or Afghanistan). Weigh the amount and the beneficiary bank country.""",
                        "30.00"),
                rule(STRUCTURING, RuleScope.PAYMENT, """
                        Three or more payments within any rolling 24 hours, each between 9,000 and \
                        9,999 - that is, deliberately just below the 10,000 reporting threshold. A \
                        single payment near the threshold is not enough; the pattern is what \
                        matters.""", "20.00"),
                rule(UNATTRIBUTED_CRYPTO, RuleScope.CRYPTO, """
                        A crypto transfer of more than 1,000 that cannot be attributed to a \
                        registered exchange, or that moves value over a privacy chain.""", "15.00"),
                rule(DECLINE_BURST, RuleScope.CARD, """
                        Five or more declined card authorisations within any rolling 24 hours, \
                        especially when followed by a successful card-not-present payment.""",
                        "10.00"));
    }

    static RiskRule ruleNamed(List<RiskRule> rules, String name) {
        return rules.stream()
                .filter(rule -> rule.getRuleName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no fixture rule named " + name));
    }

    static AnalysisTrace trace(UUID assessmentId) {
        return new AnalysisTrace(assessmentId, JsonNodeFactory.instance);
    }

    static AgentRunContext context(UUID assessmentId, AnalysisTrace trace, List<RiskRule> rules) {
        return context(assessmentId, trace, rules, AnalysisProgressListener.NONE);
    }

    static AgentRunContext context(UUID assessmentId, AnalysisTrace trace, List<RiskRule> rules,
            AnalysisProgressListener progress) {
        Customer customer = customer();
        EvaluationBatch batch = EvaluationBatch.forCustomer(customer, transactions());
        return new AgentRunContext(assessmentId, customer, batch, rules, trace, progress);
    }

    /** A context over a bespoke set of transactions, for tests that plant their own evidence. */
    static AgentRunContext contextOver(UUID assessmentId, AnalysisTrace trace, List<RiskRule> rules,
            List<Transaction> transactions) {
        Customer customer = customer();
        EvaluationBatch batch = EvaluationBatch.forCustomer(customer, transactions);
        return new AgentRunContext(assessmentId, customer, batch, rules, trace,
                AnalysisProgressListener.NONE);
    }

    /**
     * The arguments of one {@code submit_rule_evaluation} call.
     *
     * <p>A triggered verdict has to cite transactions that are really in the rule's scope - the tool
     * refuses anything else - so the evidence is taken from the run's own batch rather than invented
     * by the test. That is deliberate: a fixture that could not produce valid evidence would be
     * proving something about the fixture rather than about the loop.
     */
    static String verdict(AgentRunContext context, RiskRule rule, boolean triggered, int score,
            String rationale) {
        List<UUID> inScope = context.inScopeTransactionIds(rule.getRuleId());
        List<UUID> cited = triggered && !inScope.isEmpty() ? List.of(inScope.getFirst()) : List.of();
        return verdict(rule, triggered, score, cited, rationale);
    }

    /** The same, with the evidence chosen by the caller. */
    static String verdict(RiskRule rule, boolean triggered, int score, List<UUID> transactionIds,
            String rationale) {
        StringJoiner ids = new StringJoiner(",", "[", "]");
        transactionIds.forEach(id -> ids.add("\"" + id + "\""));
        return """
                {"rule_id":"%s","triggered":%s,"score":%d,"transaction_ids":%s,"rationale":"%s"}"""
                .formatted(rule.getRuleId(), triggered, score, ids, rationale);
    }

    /** One card transaction with caller-chosen free text, for the prompt-injection tests. */
    static Transaction cardTransaction(String merchant, String declineReason) {
        return card("120.00", "Completed", NOW.minusSeconds(600), merchant, "5411", false,
                declineReason);
    }

    /** A rule whose administrator-authored name tries to give the model orders. */
    static RiskRule ruleNamedByAnAttacker(String name) {
        return rule(name, RuleScope.ALL, "Any single transaction above 1,000,000.", "5.00");
    }

    /** A rule whose administrator-authored condition tries to give the model orders. */
    static RiskRule ruleConditionedByAnAttacker(String condition) {
        return rule("Large payment threshold", RuleScope.ALL, condition, "5.00");
    }

    // ------------------------------------------------------------------

    private static RiskRule rule(String name, RuleScope scope, String condition, String weight) {
        return RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName(name)
                .appliesTo(scope)
                .thresholdLogic(condition)
                .weight(new BigDecimal(weight))
                .build();
    }

    private static Transaction transaction(ActivityType type, String amount, String status,
            Instant createdAt) {
        return Transaction.builder()
                .transactionId(UUID.randomUUID())
                .activityType(type)
                .amount(new BigDecimal(amount))
                .currency("CHF")
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    private static Transaction payment(String amount, String status, Instant createdAt, String method,
            String receiverBankCountry) {
        Transaction transaction = transaction(ActivityType.PAYMENT, amount, status, createdAt);
        transaction.setPaymentActivity(PaymentActivity.builder()
                .paymentMethod(method)
                .senderAccount("CH93-SENDER")
                .receiverAccount("XX00-RECEIVER")
                .receiverBankCountry(receiverBankCountry)
                .build());
        return transaction;
    }

    private static Transaction crypto(String amount, String status, Instant createdAt, String blockchain,
            String exchangeName) {
        Transaction transaction = transaction(ActivityType.CRYPTO, amount, status, createdAt);
        transaction.setCryptoActivity(CryptoActivity.builder()
                .blockchain(blockchain)
                .walletAddressFrom("wallet-from")
                .walletAddressTo("wallet-to")
                .txHash("0xfeed")
                .exchangeName(exchangeName)
                .build());
        return transaction;
    }

    private static Transaction card(String amount, String status, Instant createdAt, String merchant,
            String mcc, boolean cardPresent, String declineReason) {
        Transaction transaction = transaction(ActivityType.CARD, amount, status, createdAt);
        transaction.setCardActivity(CardActivity.builder()
                .cardPan("****4242")
                .cardType("Debit")
                .merchantName(merchant)
                .mccCode(mcc)
                .cardPresent(cardPresent)
                .authorizationCode("AUTH-1")
                .declineReason(declineReason)
                .build());
        return transaction;
    }
}
