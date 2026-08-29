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
import com.sq.caa.rules.RuleEvaluator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A small, fully in-memory customer with planted risk, and the four rules that judge it.
 *
 * <p>No Spring context, no database and no model: {@link EvaluationBatch} and {@link RuleEvaluator}
 * are pure functions of the domain objects, which is what lets the coverage gate be tested as the
 * piece of control flow it is.
 *
 * <p>The deterministic verdicts these fixtures produce are fixed and are what the tests assert
 * against: SANCTIONED_WIRE (30) triggers, STRUCTURING (20) triggers, UNATTRIBUTED_CRYPTO (15)
 * triggers, DECLINE_BURST (10) does not. Total 65, which bands as HIGH.
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

    static List<RiskRule> rules() {
        return List.of(
                rule(SANCTIONED_WIRE, RuleScope.PAYMENT, """
                        {"op":"AND","conditions":[
                          {"field":"amount","operator":"GT","value":10000},
                          {"field":"payment.receiver_bank_country","operator":"IN",
                           "value":["IR","KP","SY","RU","AF"]}]}""", "30.00"),
                rule(STRUCTURING, RuleScope.PAYMENT, """
                        {"op":"AND","conditions":[
                          {"field":"amount","operator":"BETWEEN","value":[9000,9999]},
                          {"field":"agg.tx_count_24h","operator":"GTE","value":3}]}""", "20.00"),
                rule(UNATTRIBUTED_CRYPTO, RuleScope.CRYPTO, """
                        {"op":"AND","conditions":[
                          {"field":"crypto.exchange_name","operator":"IS_NULL"},
                          {"field":"amount","operator":"GT","value":1000}]}""", "15.00"),
                rule(DECLINE_BURST, RuleScope.CARD, """
                        {"op":"AND","conditions":[
                          {"field":"card.decline_reason","operator":"NOT_NULL"},
                          {"field":"agg.failed_count_24h","operator":"GTE","value":5}]}""", "10.00"));
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
        RuleEvaluator evaluator = new RuleEvaluator();
        return new AgentRunContext(assessmentId, customer, batch, rules,
                rule -> evaluator.evaluate(rule, batch), trace, progress);
    }

    /** A context over a bespoke set of transactions, for tests that plant their own evidence. */
    static AgentRunContext contextOver(UUID assessmentId, AnalysisTrace trace, List<RiskRule> rules,
            List<Transaction> transactions) {
        Customer customer = customer();
        EvaluationBatch batch = EvaluationBatch.forCustomer(customer, transactions);
        RuleEvaluator evaluator = new RuleEvaluator();
        return new AgentRunContext(assessmentId, customer, batch, rules,
                rule -> evaluator.evaluate(rule, batch), trace, AnalysisProgressListener.NONE);
    }

    /** One card transaction with caller-chosen free text, for the prompt-injection tests. */
    static Transaction cardTransaction(String merchant, String declineReason) {
        return card("120.00", "Completed", NOW.minusSeconds(600), merchant, "5411", false,
                declineReason);
    }

    /** A rule whose administrator-authored name tries to give the model orders. */
    static RiskRule ruleNamedByAnAttacker(String name) {
        return rule(name, RuleScope.ALL, """
                {"op":"AND","conditions":[{"field":"amount","operator":"GT","value":1000000}]}""",
                "5.00");
    }

    // ------------------------------------------------------------------

    private static RiskRule rule(String name, RuleScope scope, String logic, String weight) {
        return RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName(name)
                .appliesTo(scope)
                .thresholdLogic(logic)
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
