package com.sq.caa.rules;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.CardActivity;
import com.sq.caa.domain.CryptoActivity;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.PaymentActivity;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Hand-built domain objects for the rules tests.
 *
 * <p>Everything here is plain Java: no Spring context, no database, no live model. Building a
 * customer's snapshot is a pure function of (customer, transactions), and the tests keep it that
 * way.
 */
final class RuleTestFixtures {

    static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private RuleTestFixtures() {
    }

    static Customer customer(String country, int age) {
        return Customer.builder()
                .customerId(CUSTOMER_ID)
                .firstName("Dana")
                .lastName("Kovac")
                .dob(LocalDate.now(ZoneOffset.UTC).minusYears(age).minusDays(1))
                .country(country)
                .build();
    }

    static Customer customer() {
        return customer("US", 41);
    }

    static Transaction transaction(ActivityType type, String amount, String status, Instant createdAt) {
        return Transaction.builder()
                .transactionId(UUID.randomUUID())
                .activityType(type)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .status(status)
                .createdAt(createdAt)
                .build();
    }

    static Transaction card(String amount, String status, Instant createdAt, String merchant, String mcc,
            String cardType, boolean cardPresent, String declineReason) {
        Transaction transaction = transaction(ActivityType.CARD, amount, status, createdAt);
        transaction.setCardActivity(CardActivity.builder()
                .cardPan("****4242")
                .cardType(cardType)
                .merchantName(merchant)
                .mccCode(mcc)
                .cardPresent(cardPresent)
                .authorizationCode("AUTH123")
                .declineReason(declineReason)
                .build());
        return transaction;
    }

    static Transaction payment(String amount, String status, Instant createdAt, String method,
            String receiverBankCountry) {
        Transaction transaction = transaction(ActivityType.PAYMENT, amount, status, createdAt);
        transaction.setPaymentActivity(PaymentActivity.builder()
                .paymentMethod(method)
                .senderAccount("ACC-SENDER-1")
                .receiverAccount("ACC-RECEIVER-9")
                .receiverBankCountry(receiverBankCountry)
                .build());
        return transaction;
    }

    static Transaction crypto(String amount, String status, Instant createdAt, String blockchain,
            String exchangeName, String walletTo) {
        Transaction transaction = transaction(ActivityType.CRYPTO, amount, status, createdAt);
        transaction.setCryptoActivity(CryptoActivity.builder()
                .blockchain(blockchain)
                .walletAddressFrom("wallet-from-1")
                .walletAddressTo(walletTo)
                .txHash("0xabc123")
                .exchangeName(exchangeName)
                .build());
        return transaction;
    }

    static EvaluationBatch batch(Customer customer, Transaction... transactions) {
        return EvaluationBatch.forCustomer(customer, Arrays.asList(transactions));
    }

    static EvaluationBatch batch(Transaction... transactions) {
        return batch(customer(), transactions);
    }

    static TransactionFacts facts(Transaction transaction) {
        return factsOf(customer(), transaction);
    }

    static TransactionFacts factsOf(Customer customer, Transaction transaction) {
        return batch(customer, transaction).factsFor(transaction.getTransactionId());
    }

    static RiskRule rule(String name, RuleScope scope, String thresholdLogic, String weight) {
        return RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName(name)
                .appliesTo(scope)
                .thresholdLogic(thresholdLogic)
                .weight(new BigDecimal(weight))
                .build();
    }

    static List<Transaction> list(Transaction... transactions) {
        return Arrays.asList(transactions);
    }
}
