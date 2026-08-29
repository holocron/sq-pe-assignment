package com.sq.caa.agent;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.rag.RagService;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.rules.RuleEvaluator;
import com.sq.caa.service.ActivitySummaryService;
import com.sq.caa.service.CustomerService;
import com.sq.caa.service.RiskRuleService;
import com.sq.caa.service.TransactionService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the ReAct risk-assessment agent: loads everything one run needs, wires the tools to
 * it and hands the conversation to {@link RiskAgentLoop}.
 *
 * <p>The customer's activity is loaded exactly once, into a single
 * {@link EvaluationBatch}. Every tool read and every deterministic rule evaluation of the run is
 * served from that one snapshot, so the evidence the agent reasons about and the evidence the engine
 * scores can never be two different things - which is what makes the post-loop cross-check
 * meaningful rather than a race.
 */
@Component
public class ReActRiskAgent {

    private final RiskAgentLoop loop;
    private final CustomerService customerService;
    private final RiskRuleService riskRuleService;
    private final RuleEvaluator ruleEvaluator;
    private final ActivitySummaryService activitySummaryService;
    private final TransactionService transactionService;
    private final ObjectProvider<RagService> ragServiceProvider;
    private final JsonMapper jsonMapper;
    private final AgentProperties properties;

    public ReActRiskAgent(RiskAgentLoop loop,
            CustomerService customerService,
            RiskRuleService riskRuleService,
            RuleEvaluator ruleEvaluator,
            ActivitySummaryService activitySummaryService,
            TransactionService transactionService,
            ObjectProvider<RagService> ragServiceProvider,
            JsonMapper jsonMapper,
            AgentProperties properties) {
        this.loop = loop;
        this.customerService = customerService;
        this.riskRuleService = riskRuleService;
        this.ruleEvaluator = ruleEvaluator;
        this.activitySummaryService = activitySummaryService;
        this.transactionService = transactionService;
        this.ragServiceProvider = ragServiceProvider;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /** Model id the next run will use; recorded on the run row before it starts. */
    public String modelId() {
        return loop.modelId();
    }

    /** Runs one full analysis. Blocking and slow - the caller must already be off the request thread. */
    public AgentRunResult run(UUID assessmentId, UUID customerId, AnalysisTrace trace) {
        return run(assessmentId, customerId, trace, AnalysisProgressListener.NONE);
    }

    /**
     * Runs one full analysis, reporting turn and coverage counters to {@code progress} as it goes so
     * a RUNNING analysis is not a black box for the minutes it takes.
     */
    public AgentRunResult run(UUID assessmentId, UUID customerId, AnalysisTrace trace,
            AnalysisProgressListener progress) {
        AgentRunContext context = context(assessmentId, customerId, trace, progress);
        RiskAgentTools tools = new RiskAgentTools(context, activitySummaryService, transactionService,
                ragServiceProvider.getIfAvailable(), jsonMapper, properties.transactionPageSize());
        return loop.execute(context, tools);
    }

    /**
     * Evaluates the whole coverage set with the rule engine alone, with no model in the loop.
     *
     * <p>Used when an agent run failed: the narrative is lost, but every applicable rule is still
     * evaluated and scored, so rule coverage stays complete even on a failed run. Every verdict it
     * produces is marked {@link RuleVerdictSource#DETERMINISTIC_FALLBACK}.
     */
    public AgentRunResult deterministicOnly(UUID assessmentId, UUID customerId, AnalysisTrace trace) {
        long startedAt = System.currentTimeMillis();
        AgentRunContext context = context(assessmentId, customerId, trace,
                AnalysisProgressListener.NONE);
        return loop.settle(context, 0, System.currentTimeMillis() - startedAt);
    }

    private AgentRunContext context(UUID assessmentId, UUID customerId, AnalysisTrace trace,
            AnalysisProgressListener progress) {
        Customer customer = customerService.requireCustomer(customerId);
        EvaluationBatch batch = riskRuleService.batchFor(customer);
        List<RiskRule> rules = riskRuleService.coverageSetFor(customerId);
        return new AgentRunContext(assessmentId, customer, batch, rules,
                rule -> ruleEvaluator.evaluate(rule, batch), trace, progress);
    }
}
