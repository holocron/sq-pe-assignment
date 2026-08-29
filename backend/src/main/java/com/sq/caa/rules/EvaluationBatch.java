package com.sq.caa.rules;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One customer's transactions with every DSL field already resolved.
 *
 * <p>This is the unit of work that keeps rule evaluation cheap: the customer's activity is read from
 * the database once, the {@code agg.*} windows are swept once, and the per-transaction facts are
 * built once. Evaluating another rule against the same batch costs no I/O at all, which is what
 * stops the agent's per-rule tool calls from turning into O(rules x transactions) queries.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class EvaluationBatch {

    private final Customer customer;
    private final UUID customerId;
    private final List<Transaction> transactions;
    private final Map<UUID, TransactionFacts> facts;
    private final Map<UUID, AggregateSnapshot> aggregates;

    private EvaluationBatch(Customer customer, UUID customerId, List<Transaction> transactions,
            Map<UUID, TransactionFacts> facts, Map<UUID, AggregateSnapshot> aggregates) {
        this.customer = customer;
        this.customerId = customerId;
        this.transactions = transactions;
        this.facts = facts;
        this.aggregates = aggregates;
    }

    /**
     * Builds a batch. The transactions must all belong to {@code customer} and should have their
     * CARD/PAYMENT/CRYPTO detail already fetched.
     */
    public static EvaluationBatch forCustomer(Customer customer, Collection<Transaction> transactions) {
        List<Transaction> ordered = new ArrayList<>();
        if (transactions != null) {
            for (Transaction transaction : transactions) {
                if (transaction != null && transaction.getTransactionId() != null) {
                    ordered.add(transaction);
                }
            }
        }
        ordered.sort(Comparator.comparing(Transaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Transaction::getTransactionId));

        Map<UUID, AggregateSnapshot> aggregates = AggregateCalculator.compute(ordered);
        Map<UUID, TransactionFacts> facts = new LinkedHashMap<>(Math.max(16, ordered.size() * 2));
        for (Transaction transaction : ordered) {
            AggregateSnapshot snapshot = aggregates.getOrDefault(transaction.getTransactionId(),
                    AggregateSnapshot.EMPTY);
            facts.put(transaction.getTransactionId(),
                    TransactionFacts.of(transaction, customer, snapshot));
        }
        UUID id = customer != null ? customer.getCustomerId() : null;
        return new EvaluationBatch(customer, id, List.copyOf(ordered),
                Map.copyOf(facts), Map.copyOf(new HashMap<>(aggregates)));
    }

    /** A batch for a customer with no activity. */
    public static EvaluationBatch empty(Customer customer) {
        return forCustomer(customer, List.of());
    }

    public Customer customer() {
        return customer;
    }

    public UUID customerId() {
        return customerId;
    }

    /** All transactions, newest first. */
    public List<Transaction> transactions() {
        return transactions;
    }

    /** Transactions a rule with this scope must be evaluated against, newest first. */
    public List<Transaction> transactionsFor(RuleScope scope) {
        if (scope == null || scope == RuleScope.ALL) {
            return transactions;
        }
        List<Transaction> selected = new ArrayList<>();
        for (Transaction transaction : transactions) {
            if (transaction.getActivityType() != null && scope.matches(transaction.getActivityType())) {
                selected.add(transaction);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Ids of the transactions in scope. The agent uses this to write one {@code risk_assessments}
     * row per (transaction, rule) pair, including the rows that did not trigger.
     */
    public List<UUID> transactionIdsFor(RuleScope scope) {
        return transactionsFor(scope).stream().map(Transaction::getTransactionId).toList();
    }

    public TransactionFacts factsFor(UUID transactionId) {
        return facts.get(transactionId);
    }

    public TransactionFacts factsFor(Transaction transaction) {
        return transaction == null ? null : facts.get(transaction.getTransactionId());
    }

    public AggregateSnapshot aggregatesFor(UUID transactionId) {
        return aggregates.getOrDefault(transactionId, AggregateSnapshot.EMPTY);
    }

    public int size() {
        return transactions.size();
    }

    public boolean isEmpty() {
        return transactions.isEmpty();
    }
}
