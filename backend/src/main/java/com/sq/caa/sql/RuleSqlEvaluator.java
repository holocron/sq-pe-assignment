package com.sq.caa.sql;

import java.util.UUID;

/**
 * Runs the SELECT fragment the agent wrote for one rule against one customer's activity.
 *
 * <p>This is the boundary that took rule arithmetic away from the model. The agent decides what to
 * ask; PostgreSQL decides the answer. The implementation binds the customer as a JDBC parameter,
 * nests the fragment inside pre-filtered CTEs, runs it as a least-privilege role in a read-only
 * transaction with a short statement timeout, and hands back only ids that belong to that customer.
 */
public interface RuleSqlEvaluator {

    /**
     * Executes the agent's SELECT fragment scoped to one customer. Never throws for bad input - a
     * rejection or a SQL error comes back as ok=false with a reason the model can act on.
     */
    SqlRuleResult evaluate(UUID customerId, String agentSql);
}
