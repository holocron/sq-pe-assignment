package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Composite primary key of {@link RiskAssessment}: {@code (assessment_id, transaction_id, rule_id)}.
 *
 * <p>{@code assessment_id} is the shared identifier of one analysis run, so it cannot be unique on
 * its own; see the deviation note in {@code V1__baseline.sql}.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessmentId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RiskAssessmentId that)) {
            return false;
        }
        return Objects.equals(assessmentId, that.assessmentId)
                && Objects.equals(transactionId, that.transactionId)
                && Objects.equals(ruleId, that.ruleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assessmentId, transactionId, ruleId);
    }

    @Override
    public String toString() {
        return assessmentId + "/" + transactionId + "/" + ruleId;
    }
}
