package com.sq.caa.service;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.CustomerRepository;
import com.sq.caa.repository.RiskRuleRepository;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.rules.DuplicateRuleNameException;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.rules.FieldDefinition;
import com.sq.caa.rules.FieldCatalog;
import com.sq.caa.rules.RuleEvaluationResult;
import com.sq.caa.rules.RuleEvaluator;
import com.sq.caa.rules.RuleMatch;
import com.sq.caa.rules.RuleNode;
import com.sq.caa.rules.RuleNotFoundException;
import com.sq.caa.rules.RuleParser;
import com.sq.caa.rules.RuleTestOutcome;
import com.sq.caa.rules.RuleValidationException;
import com.sq.caa.rules.ScopedEvaluation;
import com.sq.caa.rules.UnknownCustomerException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Risk rule administration and deterministic evaluation.
 *
 * <p>Two responsibilities that belong together because they share the same validation rules:
 * <ul>
 *   <li>CRUD over {@code risk_rules}, where every write is parsed and semantically validated so a
 *       broken rule can never reach the table;
 *   <li>the deterministic entry points used by the ReAct agent
 *       ({@code evaluate_rule_deterministically}), by the coverage backfill and by the admin
 *       "test rule" action.
 * </ul>
 *
 * <p><b>Batch reuse.</b> The {@code agg.*} fields are defined relative to each transaction, so the
 * naive implementation issues window queries per transaction per rule. Instead a customer's activity
 * is loaded once into an {@link EvaluationBatch}, which resolves every field and every aggregate up
 * front, and that batch is cached briefly so the agent's rule-by-rule tool calls reuse it instead of
 * hammering the database. Transaction data is immutable in this application, so the cache cannot
 * serve a stale verdict; rule edits are unaffected because rules are never cached.
 */
@Service
@Transactional(readOnly = true)
public class RiskRuleService {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleService.class);

    /** Customers a draft rule is tried against when the tester does not name one. */
    private static final int MAX_TEST_CUSTOMERS = 25;

    /** Batches kept in memory; exceeding this simply clears the cache. */
    private static final int MAX_CACHED_BATCHES = 16;

    private static final BigDecimal MIN_WEIGHT = new BigDecimal("0.01");
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("999.99");
    private static final int MAX_RULE_NAME_LENGTH = 160;

    private final RiskRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final RuleEvaluator evaluator;
    private final Duration batchCacheTtl;
    private final Map<UUID, CachedBatch> batchCache = new ConcurrentHashMap<>();

    public RiskRuleService(RiskRuleRepository ruleRepository,
            TransactionRepository transactionRepository,
            CustomerRepository customerRepository,
            RuleEvaluator evaluator,
            @Value("${caa.rules.batch-cache-ttl-seconds:120}") long batchCacheTtlSeconds) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.evaluator = evaluator;
        this.batchCacheTtl = Duration.ofSeconds(Math.max(0, batchCacheTtlSeconds));
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    public List<RiskRule> findAll() {
        return ruleRepository.findAllByOrderByRuleNameAsc();
    }

    public RiskRule findById(UUID ruleId) {
        return ruleRepository.findById(ruleId).orElseThrow(() -> new RuleNotFoundException(ruleId));
    }

    /**
     * Creates a rule. The logic is parsed strictly and stored canonicalised, so what comes back out
     * of the API is exactly what the evaluator will run.
     */
    @Transactional
    public RiskRule create(String ruleName, RuleScope appliesTo, JsonNode thresholdLogic, BigDecimal weight) {
        String name = normaliseName(ruleName);
        if (ruleRepository.existsByRuleNameIgnoreCase(name)) {
            throw new DuplicateRuleNameException(name);
        }
        RiskRule rule = RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName(name)
                .appliesTo(requireScope(appliesTo))
                .thresholdLogic(canonicalLogic(thresholdLogic))
                .weight(normaliseWeight(weight))
                .build();
        RiskRule saved = ruleRepository.save(rule);
        log.info("Created risk rule {} '{}' scope={} weight={}", saved.getRuleId(), saved.getRuleName(),
                saved.getAppliesTo(), saved.getWeight());
        return saved;
    }

    /** Replaces every editable attribute of a rule. */
    @Transactional
    public RiskRule update(UUID ruleId, String ruleName, RuleScope appliesTo, JsonNode thresholdLogic,
            BigDecimal weight) {
        RiskRule rule = findById(ruleId);
        String name = normaliseName(ruleName);
        ruleRepository.findByRuleNameIgnoreCase(name)
                .filter(existing -> !existing.getRuleId().equals(ruleId))
                .ifPresent(existing -> {
                    throw new DuplicateRuleNameException(name);
                });
        rule.setRuleName(name);
        rule.setAppliesTo(requireScope(appliesTo));
        rule.setThresholdLogic(canonicalLogic(thresholdLogic));
        rule.setWeight(normaliseWeight(weight));
        RiskRule saved = ruleRepository.save(rule);
        log.info("Updated risk rule {} '{}'", saved.getRuleId(), saved.getRuleName());
        return saved;
    }

    /** Deletes a rule. Its {@code risk_assessments} rows cascade away with it. */
    @Transactional
    public void delete(UUID ruleId) {
        RiskRule rule = findById(ruleId);
        ruleRepository.delete(rule);
        log.info("Deleted risk rule {} '{}'", ruleId, rule.getRuleName());
    }

    /** The field catalog that drives the visual editor and bounds what a rule may reference. */
    public List<FieldDefinition> fieldCatalog() {
        return FieldCatalog.entries();
    }

    // ------------------------------------------------------------------
    // Deterministic evaluation
    // ------------------------------------------------------------------

    /**
     * Rules that must be evaluated for a customer: everything scoped {@code ALL} plus everything
     * scoped to an activity type the customer actually has. This is the coverage set the agent loop
     * is graded against.
     */
    public List<RiskRule> coverageSetFor(UUID customerId) {
        List<ActivityType> activityTypes = transactionRepository.findDistinctActivityTypes(customerId);
        return ruleRepository.findCoverageSet(activityTypes);
    }

    /** Runs one rule over one customer's activity. */
    public RuleEvaluationResult evaluateRule(UUID customerId, UUID ruleId) {
        RiskRule rule = findById(ruleId);
        return evaluator.evaluate(rule, batchFor(customerId));
    }

    /** Runs the customer's whole coverage set, loading the activity exactly once. */
    public List<RuleEvaluationResult> evaluateCoverageSet(UUID customerId) {
        return evaluateRules(customerId, coverageSetFor(customerId));
    }

    /** Runs a chosen set of rules, loading the activity exactly once. */
    public List<RuleEvaluationResult> evaluateRules(UUID customerId, Collection<RiskRule> rules) {
        EvaluationBatch batch = batchFor(customerId);
        List<RuleEvaluationResult> results = new ArrayList<>(rules.size());
        for (RiskRule rule : rules) {
            results.add(evaluator.evaluate(rule, batch));
        }
        return List.copyOf(results);
    }

    /**
     * Evaluates a draft rule without saving it. Malformed logic is rejected here exactly as it would
     * be on a write, so the editor's "Test rule" button gives the same verdict as "Save".
     */
    public RuleTestOutcome testRule(JsonNode thresholdLogic, RuleScope appliesTo, UUID customerId) {
        RuleNode node = RuleParser.parseStrict(thresholdLogic);
        RuleScope scope = appliesTo == null ? RuleScope.ALL : appliesTo;

        List<Customer> customers = customerId != null
                ? List.of(customerRepository.findById(customerId)
                        .orElseThrow(() -> new UnknownCustomerException(customerId)))
                : customerRepository.findAll(PageRequest.of(0, MAX_TEST_CUSTOMERS,
                        Sort.by("lastName").and(Sort.by("firstName")))).getContent();

        int matched = 0;
        int evaluated = 0;
        boolean degraded = false;
        Set<String> notes = new LinkedHashSet<>();
        List<RuleMatch> matches = new ArrayList<>();

        for (Customer customer : customers) {
            ScopedEvaluation evaluation = evaluator.evaluate(node, scope, batchFor(customer));
            matched += evaluation.matchedCount();
            evaluated += evaluation.evaluatedCount();
            degraded = degraded || evaluation.degraded();
            notes.addAll(evaluation.notes());
            matches.addAll(evaluation.matches());
        }

        List<RuleMatch> samples = matches.stream()
                .sorted(Comparator.comparing(RuleMatch::amount, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RuleMatch::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RuleTestOutcome.SAMPLE_LIMIT)
                .toList();

        return new RuleTestOutcome(matched, evaluated, customers.size(), degraded,
                notes.stream().limit(10).toList(), samples);
    }

    // ------------------------------------------------------------------
    // Batches
    // ------------------------------------------------------------------

    /** Loads (or reuses) the evaluation batch of one customer. */
    public EvaluationBatch batchFor(UUID customerId) {
        CachedBatch cached = batchCache.get(customerId);
        if (cached != null && cached.isFresh(batchCacheTtl)) {
            return cached.batch();
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new UnknownCustomerException(customerId));
        return batchFor(customer);
    }

    /** Loads (or reuses) the evaluation batch of an already-loaded customer. */
    public EvaluationBatch batchFor(Customer customer) {
        UUID customerId = customer.getCustomerId();
        CachedBatch cached = batchCache.get(customerId);
        if (cached != null && cached.isFresh(batchCacheTtl)) {
            return cached.batch();
        }
        List<Transaction> transactions = transactionRepository.findAllForCustomerWithDetails(customerId);
        EvaluationBatch batch = EvaluationBatch.forCustomer(customer, transactions);
        if (batchCache.size() >= MAX_CACHED_BATCHES) {
            batchCache.clear();
        }
        batchCache.put(customerId, new CachedBatch(batch, Instant.now()));
        log.debug("Built evaluation batch for customer {} with {} transactions", customerId, batch.size());
        return batch;
    }

    /** Drops a customer's cached batch; call after activity for that customer changes. */
    public void invalidateBatch(UUID customerId) {
        batchCache.remove(customerId);
    }

    /** Drops every cached batch. */
    public void invalidateAllBatches() {
        batchCache.clear();
    }

    // ------------------------------------------------------------------
    // Input normalisation
    // ------------------------------------------------------------------

    private String normaliseName(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) {
            throw new IllegalArgumentException("ruleName must not be blank");
        }
        String trimmed = ruleName.trim();
        if (trimmed.length() > MAX_RULE_NAME_LENGTH) {
            throw new IllegalArgumentException("ruleName must be at most " + MAX_RULE_NAME_LENGTH
                    + " characters");
        }
        return trimmed;
    }

    private RuleScope requireScope(RuleScope appliesTo) {
        if (appliesTo == null) {
            throw new IllegalArgumentException("appliesTo must be one of CARD, PAYMENT, CRYPTO, ALL");
        }
        return appliesTo;
    }

    private BigDecimal normaliseWeight(BigDecimal weight) {
        if (weight == null) {
            throw new IllegalArgumentException("weight is required");
        }
        BigDecimal scaled = weight.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_WEIGHT) < 0 || scaled.compareTo(MAX_WEIGHT) > 0) {
            throw new IllegalArgumentException("weight must be between " + MIN_WEIGHT + " and " + MAX_WEIGHT);
        }
        return scaled;
    }

    private String canonicalLogic(JsonNode thresholdLogic) {
        if (thresholdLogic == null || thresholdLogic.isNull() || thresholdLogic.isMissingNode()) {
            throw new RuleValidationException("$", null, "thresholdLogic is required");
        }
        return RuleParser.toJson(RuleParser.parseStrict(thresholdLogic));
    }

    private record CachedBatch(EvaluationBatch batch, Instant loadedAt) {

        boolean isFresh(Duration ttl) {
            return !ttl.isZero() && loadedAt.plus(ttl).isAfter(Instant.now());
        }
    }
}
