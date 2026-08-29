package com.sq.caa.agent;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.rag.RagService;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.service.ActivitySummaryService;
import com.sq.caa.service.CustomerService;
import com.sq.caa.service.RiskRuleService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the ReAct risk-assessment agent: loads everything one run needs, wires the tools to
 * it and hands the conversation to {@link RiskAgentLoop}.
 *
 * <p>The customer's activity is loaded exactly once, into a single {@link EvaluationBatch}. Every
 * transaction the tools read and every rule scope the run resolves is served from that one snapshot
 * - the tool surface is handed no repository and no transaction service, so it has nothing else to
 * read from. The agent therefore judges one fixed body of evidence: the transaction it quotes in a
 * rationale and the transaction whose id is written to {@code risk_assessments} are by construction
 * the same row, even if the database moves underneath the run.
 */
@Component
public class ReActRiskAgent {

    private final RiskAgentLoop loop;
    private final CustomerService customerService;
    private final RiskRuleService riskRuleService;
    private final ActivitySummaryService activitySummaryService;
    private final ObjectProvider<RagService> ragServiceProvider;
    private final JsonMapper jsonMapper;
    private final AgentProperties properties;

    public ReActRiskAgent(RiskAgentLoop loop,
            CustomerService customerService,
            RiskRuleService riskRuleService,
            ActivitySummaryService activitySummaryService,
            ObjectProvider<RagService> ragServiceProvider,
            JsonMapper jsonMapper,
            AgentProperties properties) {
        this.loop = loop;
        this.customerService = customerService;
        this.riskRuleService = riskRuleService;
        this.activitySummaryService = activitySummaryService;
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
     *
     * @throws AgentRunFailedException when the run cannot be reported as complete - the conversation
     *                                 broke, or it ended with applicable rules unjudged. The
     *                                 exception carries the verdicts that were obtained.
     */
    public AgentRunResult run(UUID assessmentId, UUID customerId, AnalysisTrace trace,
            AnalysisProgressListener progress) {
        AgentRunContext context = context(assessmentId, customerId, trace, progress);
        RiskAgentTools tools = new RiskAgentTools(context, activitySummaryService,
                ragServiceProvider.getIfAvailable(), jsonMapper, properties.transactionPageSize());
        return loop.execute(context, tools);
    }

    private AgentRunContext context(UUID assessmentId, UUID customerId, AnalysisTrace trace,
            AnalysisProgressListener progress) {
        Customer customer = customerService.requireCustomer(customerId);
        EvaluationBatch batch = riskRuleService.batchFor(customer);
        List<RiskRule> rules = riskRuleService.coverageSetFor(customerId);
        return new AgentRunContext(assessmentId, customer, batch, rules, trace, progress);
    }
}
