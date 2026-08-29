package com.sq.caa.agent;

import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskRule;
import java.util.List;
import java.util.StringJoiner;

/**
 * The wording that steers the agent.
 *
 * <p>The posture is deliberate and comes from the domain: in transaction monitoring the cost of a
 * false negative (a real risk cleared) dwarfs the cost of a false positive (an extra manual review).
 * The prompts therefore tell the model to escalate under ambiguity, to ground every number in a tool
 * result, and to cite policy through the knowledge base rather than from memory.
 *
 * <p>The prompts also state the coverage rule in the same terms the loop enforces it, so the model's
 * incentives and the gate agree instead of fighting each other.
 */
public final class AgentPrompts {

    private AgentPrompts() {
    }

    /** Standing instructions for the whole run. */
    public static String system() {
        return """
                You are the transaction-monitoring analyst of a Swiss bank's financial-crime \
                compliance team. You review one customer's activity and decide how risky it is, \
                using only the tools provided to you.

                HOW YOU MUST WORK
                1. Investigate before you judge. Start with get_customer_profile and \
                get_customer_activity_summary, then list_risk_rules to get the checklist of rules \
                that apply to this customer.
                2. For EVERY rule on that checklist, call evaluate_rule_deterministically first and \
                then submit_rule_evaluation. The rule engine, not your intuition, decides whether a \
                numeric threshold is breached; your job is to interpret the result, cite the \
                evidence and explain what it means.
                3. Look at the underlying transactions with list_transactions and \
                get_transaction_details before you describe a pattern. Never state an amount, a \
                count, a country or a date that did not come out of a tool.
                4. Ground policy claims with search_policy_knowledge and cite the document and \
                section you relied on. If the knowledge base returns nothing relevant, say so \
                instead of inventing a policy.
                5. Conclude with submit_final_assessment - but only once every rule on the checklist \
                has a verdict. The call is rejected while any rule is outstanding.

                HOW YOU MUST JUDGE
                - This work is asymmetric. A missed real risk costs the bank far more than an \
                unnecessary review costs an analyst. When the evidence is ambiguous, escalate; do \
                not clear.
                - Judge the pattern, not only the single transaction. Many payments just under a \
                reporting threshold, a burst of declines followed by a large card-not-present \
                success, transfers to a privacy chain with no exchange attribution, or wires into a \
                sanctioned jurisdiction are each more serious than any one transaction in them.
                - A rule that did not trigger is still a finding worth one sentence: it tells the \
                reviewer what was checked and ruled out.
                - Be concrete and short. The summary and the recommendations are read by a busy \
                compliance officer who must act on them.

                Do not ask the user questions; you have every tool you need. Work autonomously until \
                the assessment is submitted.""";
    }

    /** The task message that opens the conversation. */
    public static String task(Customer customer, List<RiskRule> rules) {
        StringJoiner checklist = new StringJoiner("\n");
        for (RiskRule rule : rules) {
            checklist.add("  - " + rule.getRuleName() + " [" + rule.getAppliesTo() + ", weight "
                    + rule.getWeight() + "] id=" + rule.getRuleId());
        }
        String list = rules.isEmpty() ? "  (no rules are configured for this customer's activity)"
                : checklist.toString();
        return """
                Assess the financial-crime risk of customer %s (%s), resident in %s.

                %d rule(s) apply to this customer and each one needs its own submit_rule_evaluation \
                call before you may conclude:
                %s

                Work through them systematically, then submit the final assessment."""
                .formatted(customer.getFullName(), customer.getCustomerId(),
                        customer.getCountry() == null ? "an unknown country" : customer.getCountry(),
                        rules.size(), list);
    }

    /**
     * The message the coverage gate appends when the model tries to stop early. It names the exact
     * rules that are outstanding, because "you missed some rules" is not actionable.
     */
    public static String coverageReprompt(List<RiskRule> missing) {
        StringJoiner names = new StringJoiner("\n");
        for (RiskRule rule : missing) {
            names.add("  - " + rule.getRuleName() + " [" + rule.getAppliesTo() + ", weight "
                    + rule.getWeight() + "] rule_id=" + rule.getRuleId());
        }
        return """
                STOP - the analysis is not finished. %d rule(s) still have no verdict:
                %s

                For each of them: call evaluate_rule_deterministically with the rule_id, then call \
                submit_rule_evaluation with your verdict and a rationale. Do not conclude, do not \
                summarise and do not repeat what you have already found until every rule above has \
                been submitted. Then call submit_final_assessment."""
                .formatted(missing.size(), names.toString());
    }

    /** Appended when every rule has a verdict but the model has not actually concluded. */
    public static String conclusionReprompt() {
        return """
                Every rule now has a verdict, but the assessment has not been submitted. Call \
                submit_final_assessment now with the overall risk level, a summary of what you found \
                and the recommended next steps.""";
    }

    /** Appended when the model answers with neither a tool call nor any text. */
    public static String emptyTurnReprompt() {
        return """
                Your last turn produced no answer and no tool call. Continue the analysis by calling \
                a tool.""";
    }
}
