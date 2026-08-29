package com.sq.caa.rules;

/**
 * Judges one rule against one customer's activity with a single model call.
 *
 * <p>This is the seam between rule administration and the model. Rule conditions are prose, so
 * "test this rule" cannot be answered by evaluating anything - the only honest answer is the one the
 * model gives when it reads the condition and looks at the evidence, which is exactly what the ReAct
 * agent does during an analysis. The admin path needs that judgement without the rest of the run: no
 * multi-turn loop, no coverage gate, no persistence, and bounded in time because a human is waiting.
 *
 * <p>Implemented here by {@link ChatModelRuleJudge}, which drives the same {@code ChatModel} bean and
 * the same model id as the agent. It is an interface rather than a class so the agent package can
 * take the judgement over - reusing its own tool surface, for instance - without the rule API having
 * to know: {@link com.sq.caa.service.RiskRuleService} resolves it lazily and works with whichever
 * implementation is registered.
 */
public interface RuleJudge {

    /**
     * Judges {@code draft} against the customer's frozen activity snapshot.
     *
     * @param batch the customer's transactions with every catalog field already resolved; only the
     *              transactions in {@code draft.appliesTo()} scope are shown to the model
     * @return the verdict, never {@code null}
     * @throws RuleJudgementException when the model could not be reached, did not answer in time or
     *                                answered with something that is not a verdict
     */
    RuleJudgement judge(RuleDraft draft, EvaluationBatch batch);

    /** Model id this judge will use, for the response and for the logs. */
    String modelId();
}
