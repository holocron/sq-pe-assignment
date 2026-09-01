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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A small, fully in-memory customer with planted risk, and the four rules that judge it.
 *
 * <p>No Spring context, no database and no model: {@link EvaluationBatch} is a pure function of the
 * domain objects, which is what lets the coverage gate be tested as the piece of control flow it is.
 *
 * <p>Each rule's {@code threshold_logic} is what it is in production now - the condition in prose,
 * written by an administrator. Nothing here evaluates that prose. The scripted model plays the part
 * of the agent that reads it and writes a query for it ({@link #sqlFor}), and
 * {@link StubRuleSqlEvaluator} plays the part of PostgreSQL answering that query
 * ({@link #evaluator}). The verdicts are therefore the stub's, exactly as they are the database's in
 * production, and they are consistent throughout: SANCTIONED_WIRE 30, STRUCTURING 20,
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

    /**
     * Agent tuning for the orchestrator-era tests: explicit only where a test exercises it
     * ({@code subagentMaxSteps}, {@code subagentParallelism}), everything else at its production
     * default. {@code maxSteps} now bounds only the closing summary conversation.
     */
    static AgentProperties properties(int subagentMaxSteps, int subagentParallelism) {
        return new AgentProperties(40, subagentMaxSteps, subagentParallelism, 3, 3, 4096, 0.1, 32768,
                1536, 10, "test-model", 2, 16, java.time.Duration.ofMinutes(5),
                java.time.Duration.ofMinutes(10), 25);
    }

    /**
     * Drives one full orchestrated run: the given model (usually {@link RoutedChatModel}) serves
     * both roles (reasoning and tooling), with the given SQL answers, and no Spring context.
     */
    static AgentRunResult run(org.springframework.ai.chat.model.ChatModel model,
            AgentRunContext context, com.sq.caa.sql.RuleSqlEvaluator sql, AgentProperties properties) {
        return run(model, model, context, sql, properties);
    }

    /**
     * The same, with a distinct tooling model - the rule subagents' mini-loops must call
     * {@code toolingModel} while the closing summary conversation stays on {@code reasoningModel}.
     */
    static AgentRunResult run(org.springframework.ai.chat.model.ChatModel reasoningModel,
            org.springframework.ai.chat.model.ChatModel toolingModel,
            AgentRunContext context, com.sq.caa.sql.RuleSqlEvaluator sql, AgentProperties properties) {
        tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        RiskAgentTools tools = new RiskAgentTools(context, null, null, sql, mapper, 25,
                properties.maxRuleSqlAttempts());
        RiskAgentLoop loop = new RiskAgentLoop(reasoningModel, toolingModel,
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(), mapper,
                properties);
        return loop.execute(context, tools);
    }

    /**
     * A routed model in which every rule's subagent immediately submits its verdict and the closing
     * conversation ends with {@code summary} - the "everything just works" skeleton a test then
     * perturbs.
     */
    static RoutedChatModel coveringModel(List<RiskRule> rules, ScriptedChatModel.Turn summary) {
        RoutedChatModel model = new RoutedChatModel();
        for (RiskRule rule : rules) {
            model.route(rule.getRuleId().toString(), List.of(ScriptedChatModel.calls(
                    RiskAgentTools.EVALUATE_RULE,
                    evaluateRule(rule, "The activity this rule's condition names."))));
        }
        model.summary(List.of(summary));
        return model;
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
     * The arguments of one {@code evaluate_rule} call: the rule, the SQL the model wrote for it and
     * what the model says that SQL looks for.
     *
     * <p>Notice what is <em>not</em> here. There is no verdict and no score to script, because the
     * model no longer supplies either - the query result does. A test that wants a rule triggered
     * says so to {@link StubRuleSqlEvaluator}, which is the database's seat in these tests.
     */
    static String evaluateRule(RiskRule rule, String explanation) {
        return evaluateRule(rule, sqlFor(rule), explanation);
    }

    /** The same, with the query written by the caller - a broken one, or a deliberately lazy one. */
    static String evaluateRule(RiskRule rule, String sql, String explanation) {
        return """
                {"rule_id":"%s","sql":"%s","explanation":"%s"}"""
                .formatted(rule.getRuleId(), json(sql), json(explanation));
    }

    /**
     * The query an agent would plausibly write for one of these rules.
     *
     * <p>Each is recognisable by a fragment of its own text - {@code receiver_bank_country},
     * {@code BETWEEN 9000 AND 9999} - which is how {@link StubRuleSqlEvaluator} tells them apart,
     * because the real evaluator is handed a SELECT and a customer id and never learns which rule it
     * belongs to.
     */
    static String sqlFor(RiskRule rule) {
        return switch (rule.getRuleName()) {
            case SANCTIONED_WIRE -> "SELECT t.transaction_id FROM tx t JOIN payment p ON "
                    + "p.transaction_id = t.transaction_id WHERE t.activity_type = 'PAYMENT' AND "
                    + "t.amount > 10000 AND p.receiver_bank_country IN ('IR','KP','SY','RU','AF')";
            // The "< 10000" is redundant against the BETWEEN and is written anyway, because the
            // condition names the 10,000 reporting threshold and ThresholdFidelity expects a first
            // query to use the numbers its condition states.
            case STRUCTURING -> "SELECT t.transaction_id FROM tx t WHERE t.activity_type = 'PAYMENT' "
                    + "AND t.amount BETWEEN 9000 AND 9999 AND t.amount < 10000 AND (SELECT count(*) "
                    + "FROM tx w WHERE w.activity_type = 'PAYMENT' AND w.amount BETWEEN 9000 AND "
                    + "9999 AND w.created_at > t.created_at - INTERVAL '24 hours' AND w.created_at "
                    + "<= t.created_at) >= 3";
            case UNATTRIBUTED_CRYPTO -> "SELECT t.transaction_id FROM tx t JOIN crypto c ON "
                    + "c.transaction_id = t.transaction_id WHERE t.activity_type = 'CRYPTO' AND "
                    + "t.amount > 1000 AND c.exchange_name IS NULL";
            case DECLINE_BURST -> "SELECT t.transaction_id FROM tx t JOIN card c ON "
                    + "c.transaction_id = t.transaction_id WHERE t.activity_type = 'CARD' AND "
                    + "c.decline_reason IS NOT NULL AND (SELECT count(*) FROM tx w JOIN card k ON "
                    + "k.transaction_id = w.transaction_id WHERE k.decline_reason IS NOT NULL AND "
                    + "w.created_at > t.created_at - INTERVAL '24 hours' AND w.created_at <= "
                    + "t.created_at) >= 5";
            default -> "SELECT t.transaction_id FROM tx t WHERE t.amount > 1000000";
        };
    }

    /**
     * A query that names every threshold its rule's condition states and still matches nothing.
     *
     * <p>Both halves are needed. {@link ThresholdFidelity} refuses a first query that ignores the
     * condition's numbers, so a test that wants "the query ran and found nothing" cannot just write
     * {@code WHERE amount > 1000000} any more; and {@link StubRuleSqlEvaluator} answers an
     * unscripted query with no rows, so naming the numbers in a predicate nothing satisfies is
     * exactly the shape needed.
     */
    static String faithfulSqlThatMatchesNothing(RiskRule rule) {
        StringBuilder sql = new StringBuilder("SELECT t.transaction_id FROM tx t WHERE false");
        Matcher numbers = Pattern.compile("(?<![A-Za-z0-9_.])\\d+(?:,\\d{3})*(?:\\.\\d+)?(?![A-Za-z0-9_])")
                .matcher(rule.getThresholdLogic());
        while (numbers.find()) {
            sql.append(" AND t.amount = ").append(numbers.group().replace(",", ""));
        }
        return sql.toString();
    }

    /**
     * PostgreSQL's part, scripted: the answers that make this fixture's planted risk real.
     *
     * <p>The RU wire, the three payments just under the threshold and the unattributed XMR transfer
     * come back as rows; the decline rule's query finds nothing, because the customer's one card
     * transaction was authorised. Every id is taken from the run's own snapshot, so the ids a
     * verdict records are transactions that exist and are in the rule's scope - which is what the
     * tools check before accepting a result.
     */
    static StubRuleSqlEvaluator evaluator(AgentRunContext context) {
        return new StubRuleSqlEvaluator()
                .matching("receiver_bank_country", sanctionedWireEvidence(context))
                .matching("BETWEEN 9000 AND 9999", structuringEvidence(context))
                .matching("exchange_name IS NULL", cryptoEvidence(context));
    }

    /** The single payment above 10,000 to a sanctioned jurisdiction. */
    static List<UUID> sanctionedWireEvidence(AgentRunContext context) {
        return matching(context, transaction -> transaction.getActivityType() == ActivityType.PAYMENT
                && transaction.getAmount().compareTo(new BigDecimal("10000")) > 0);
    }

    /** The three payments between 9,000 and 9,999 inside one day. */
    static List<UUID> structuringEvidence(AgentRunContext context) {
        return matching(context, transaction -> transaction.getActivityType() == ActivityType.PAYMENT
                && transaction.getAmount().compareTo(new BigDecimal("9000")) >= 0
                && transaction.getAmount().compareTo(new BigDecimal("9999")) <= 0);
    }

    /** The crypto transfer with no exchange attribution. */
    static List<UUID> cryptoEvidence(AgentRunContext context) {
        return matching(context, transaction -> transaction.getActivityType() == ActivityType.CRYPTO);
    }

    private static List<UUID> matching(AgentRunContext context, Predicate<Transaction> predicate) {
        return context.batch().transactions().stream()
                .filter(predicate)
                .map(Transaction::getTransactionId)
                .toList();
    }

    /** A string as it has to appear inside the tool-call arguments the scripted model emits. */
    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
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
