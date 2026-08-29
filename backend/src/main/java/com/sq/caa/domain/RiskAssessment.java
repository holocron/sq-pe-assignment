package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One evaluated (transaction, rule) pair of an analysis run. Maps the assignment table
 * {@code risk_assessments}.
 *
 * <p>A row is written for <em>every</em> rule in the coverage set, not only the triggered ones:
 * rules that did not fire are persisted with {@code scoreContribution = 0.00}. That makes "no rule
 * was skipped" provable from this table alone for every rule that had at least one transaction in
 * scope. A rule with an empty scope - an {@code ALL}-scoped rule for a customer with no activity -
 * has no transaction to key a row on, and {@code analysis_runs.rules_evaluated} /
 * {@code rules_total} / {@code coverage_complete} are the record that it was evaluated.
 *
 * <p>{@link #transaction} and {@link #rule} are read-only navigations over the key columns; write
 * the identifiers through {@link #getId()}.
 */
@Entity
@Table(name = "risk_assessments")
@Getter
@Setter
@NoArgsConstructor
public class RiskAssessment {

    @EmbeddedId
    private RiskAssessmentId id;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    /** Points added to the risk score; {@code 0.00} when the rule was evaluated but did not fire. */
    @Column(name = "score_contribution", nullable = false, precision = 5, scale = 2)
    private BigDecimal scoreContribution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", insertable = false, updatable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", insertable = false, updatable = false)
    private RiskRule rule;

    public RiskAssessment(UUID assessmentId, UUID transactionId, UUID ruleId, Instant triggeredAt,
            BigDecimal scoreContribution) {
        this.id = new RiskAssessmentId(assessmentId, transactionId, ruleId);
        this.triggeredAt = triggeredAt;
        this.scoreContribution = scoreContribution;
    }

    public UUID getAssessmentId() {
        return id == null ? null : id.getAssessmentId();
    }

    public UUID getTransactionId() {
        return id == null ? null : id.getTransactionId();
    }

    public UUID getRuleId() {
        return id == null ? null : id.getRuleId();
    }

    /** Whether the rule actually fired for this transaction. */
    public boolean isTriggered() {
        return scoreContribution != null && scoreContribution.signum() > 0;
    }
}
