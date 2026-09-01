package com.sq.caa.service;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.CustomerRepository;
import com.sq.caa.repository.RiskAssessmentRepository;
import com.sq.caa.repository.RiskRuleRepository;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.repository.projection.RuleActivityStats;
import com.sq.caa.rules.ConditionEnhancer;
import com.sq.caa.rules.DuplicateRuleNameException;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.rules.FieldCatalog;
import com.sq.caa.rules.FieldDefinition;
import com.sq.caa.rules.RuleDraft;
import com.sq.caa.rules.RuleInUseException;
import com.sq.caa.rules.RuleJudge;
import com.sq.caa.rules.RuleJudgement;
import com.sq.caa.rules.RuleJudgementException;
import com.sq.caa.rules.RuleNotFoundException;
import com.sq.caa.rules.RuleValidator;
import com.sq.caa.rules.RuleValidationException;
import com.sq.caa.rules.UnknownCustomerException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Risk rule administration and the customer snapshots every rule is judged against.
 *
 * <p>Three responsibilities:
 * <ul>
 *   <li>CRUD over {@code risk_rules}. {@code threshold_logic} is natural language - the sentence the
 *       agent reads and judges - so a write validates the <em>text</em> ({@link RuleValidator}) and
 *       stores it verbatim. There is nothing to parse and nothing to canonicalise: what an admin
 *       types is what the model is shown.
 *   <li>The coverage set of a customer, which is the list of rules an analysis must return a verdict
 *       for before it may be called complete.
 *   <li>The {@link EvaluationBatch} - the customer's activity loaded once, with every catalog field
 *       and every {@code agg.*} window already resolved. The agent's tools read the run's evidence
 *       through it, and the admin "Test rule" action judges against the same snapshot.
 * </ul>
 *
 * <p><b>Batch reuse.</b> The {@code agg.*} values are defined relative to each transaction, so
 * computing them per tool call would mean window queries per transaction per rule. Instead the
 * activity is loaded once per customer, swept once, and cached briefly so the agent's many tool calls
 * reuse it. Transaction data is immutable in this application, so the cache cannot serve a stale
 * verdict.
 */
@Service
@Transactional(readOnly = true)
public class RiskRuleService {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleService.class);

    /** Batches kept in memory; exceeding this simply clears the cache. */
    private static final int MAX_CACHED_BATCHES = 16;

    private final RiskRuleRepository ruleRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final TransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final ObjectProvider<RuleJudge> ruleJudgeProvider;
    private final ObjectProvider<ConditionEnhancer> conditionEnhancerProvider;
    private final Duration batchCacheTtl;
    private final Map<UUID, CachedBatch> batchCache = new ConcurrentHashMap<>();

    public RiskRuleService(RiskRuleRepository ruleRepository,
            RiskAssessmentRepository riskAssessmentRepository,
            TransactionRepository transactionRepository,
            CustomerRepository customerRepository,
            ObjectProvider<RuleJudge> ruleJudgeProvider,
            ObjectProvider<ConditionEnhancer> conditionEnhancerProvider,
            @Value("${caa.rules.batch-cache-ttl-seconds:120}") long batchCacheTtlSeconds) {
        this.ruleRepository = ruleRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.ruleJudgeProvider = ruleJudgeProvider;
        this.conditionEnhancerProvider = conditionEnhancerProvider;
        this.batchCacheTtl = Duration.ofSeconds(Math.max(0, batchCacheTtlSeconds));
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    public List<RiskRule> findAll() {
        return ruleRepository.findAllByOrderByRuleNameAsc();
    }

    /**
     * Latest judgement and latest firing per rule, keyed by rule id. One aggregate query for every
     * rule at once - the rule list never pays a per-rule lookup. A rule absent from the map has no
     * assessment rows at all.
     */
    public Map<UUID, RuleActivityStats> activityStatsByRule() {
        return riskAssessmentRepository.activityStatsByRule().stream()
                .collect(java.util.stream.Collectors.toMap(RuleActivityStats::ruleId, stats -> stats));
    }

    public RiskRule findById(UUID ruleId) {
        return ruleRepository.findById(ruleId).orElseThrow(() -> new RuleNotFoundException(ruleId));
    }

    /** Creates a rule. The condition is stored exactly as the agent will read it. */
    @Transactional
    public RiskRule create(String ruleName, RuleScope appliesTo, String thresholdLogic, BigDecimal weight) {
        String name = RuleValidator.normaliseName(ruleName);
        if (ruleRepository.existsByRuleNameIgnoreCase(name)) {
            throw new DuplicateRuleNameException(name);
        }
        RiskRule rule = RiskRule.builder()
                .ruleId(UUID.randomUUID())
                .ruleName(name)
                .appliesTo(requireScope(appliesTo))
                .thresholdLogic(RuleValidator.normaliseCondition(thresholdLogic))
                .weight(RuleValidator.normaliseWeight(weight))
                .build();
        RiskRule saved = ruleRepository.save(rule);
        log.info("Created risk rule {} '{}' scope={} weight={}", saved.getRuleId(), saved.getRuleName(),
                saved.getAppliesTo(), saved.getWeight());
        return saved;
    }

    /** Replaces every editable attribute of a rule. */
    @Transactional
    public RiskRule update(UUID ruleId, String ruleName, RuleScope appliesTo, String thresholdLogic,
            BigDecimal weight) {
        RiskRule rule = findById(ruleId);
        String name = RuleValidator.normaliseName(ruleName);
        ruleRepository.findByRuleNameIgnoreCase(name)
                .filter(existing -> !existing.getRuleId().equals(ruleId))
                .ifPresent(existing -> {
                    throw new DuplicateRuleNameException(name);
                });
        rule.setRuleName(name);
        rule.setAppliesTo(requireScope(appliesTo));
        rule.setThresholdLogic(RuleValidator.normaliseCondition(thresholdLogic));
        rule.setWeight(RuleValidator.normaliseWeight(weight));
        RiskRule saved = ruleRepository.save(rule);
        log.info("Updated risk rule {} '{}'", saved.getRuleId(), saved.getRuleName());
        return saved;
    }

    /**
     * Deletes a rule - but only while nothing historical points at it. A rule referenced by any
     * {@code risk_assessments} row is evidence of past analyses, and the {@code ON DELETE CASCADE}
     * on that table would erase it with the rule, so such a delete is refused instead.
     *
     * @throws RuleInUseException when a recorded assessment still references the rule
     */
    @Transactional
    public void delete(UUID ruleId) {
        RiskRule rule = findById(ruleId);
        if (riskAssessmentRepository.existsById_RuleId(ruleId)) {
            throw new RuleInUseException(ruleId);
        }
        ruleRepository.delete(rule);
        log.info("Deleted risk rule {} '{}'", ruleId, rule.getRuleName());
    }

    /**
     * The data a rule may talk about: what the agent can see, and therefore what it can judge a
     * condition against.
     */
    public List<FieldDefinition> fieldCatalog() {
        return FieldCatalog.entries();
    }

    // ------------------------------------------------------------------
    // Coverage and judgement
    // ------------------------------------------------------------------

    /**
     * Rules that must be evaluated for a customer: everything scoped {@code ALL} plus everything
     * scoped to an activity type the customer actually has. This is the coverage set an analysis is
     * graded against - a run may only complete once every rule in it has a verdict.
     */
    public List<RiskRule> coverageSetFor(UUID customerId) {
        List<ActivityType> activityTypes = transactionRepository.findDistinctActivityTypes(customerId);
        return ruleRepository.findCoverageSet(activityTypes);
    }

    /**
     * Judges one rule - saved or draft - against one customer, with a single model call.
     *
     * <p>Deliberately outside any transaction: the call takes tens of seconds and must not hold a
     * database connection for the duration. The snapshot is loaded first (each repository call opens
     * its own short transaction), and the model sees only that snapshot.
     *
     * @throws UnknownCustomerException when the customer does not exist
     * @throws RuleJudgementException   when no judge is registered, or the model did not answer
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RuleJudgement judgeRule(RuleDraft draft, UUID customerId) {
        RuleJudge judge = ruleJudgeProvider.getIfAvailable();
        if (judge == null) {
            throw new RuleJudgementException(RuleJudgementException.Reason.UNAVAILABLE,
                    "No rule judge is configured, so a rule condition cannot be judged. Rule "
                            + "conditions are natural language and there is nothing to evaluate "
                            + "mechanically.");
        }
        EvaluationBatch batch = batchFor(customerId);
        return judge.judge(draft, batch);
    }

    // ------------------------------------------------------------------
    // Condition enhancement
    // ------------------------------------------------------------------

    /**
     * Rewrites a draft condition into prose the agent can translate into one SQL query - one model
     * call, nothing stored. Deliberately outside any transaction, like judgement: the call takes
     * tens of seconds and must not hold a database connection for the duration.
     *
     * @throws RuleValidationException when the draft is blank - there is nothing to rewrite
     * @throws RuleJudgementException  when the model did not answer or answered unusably
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConditionEnhancer.Enhancement enhanceCondition(String condition, RuleScope appliesTo) {
        if (condition == null || condition.isBlank()) {
            throw new RuleValidationException("thresholdLogic",
                    "is empty; there is no draft condition to enhance");
        }
        ConditionEnhancer enhancer = conditionEnhancerProvider.getIfAvailable();
        if (enhancer == null) {
            throw new RuleJudgementException(RuleJudgementException.Reason.UNAVAILABLE,
                    "No condition enhancer is configured, so a rule condition cannot be "
                            + "rewritten. Rule conditions are natural language and there is "
                            + "nothing to rephrase them mechanically.");
        }
        return enhancer.enhance(condition, requireScope(appliesTo));
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

    private RuleScope requireScope(RuleScope appliesTo) {
        if (appliesTo == null) {
            throw new IllegalArgumentException("appliesTo must be one of CARD, PAYMENT, CRYPTO, ALL");
        }
        return appliesTo;
    }

    private record CachedBatch(EvaluationBatch batch, Instant loadedAt) {

        boolean isFresh(Duration ttl) {
            return !ttl.isZero() && loadedAt.plus(ttl).isAfter(Instant.now());
        }
    }
}
