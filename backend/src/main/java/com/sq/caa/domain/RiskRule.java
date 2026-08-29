package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configurable risk rule. Maps the assignment table {@code risk_rules}.
 *
 * <p>{@link #thresholdLogic} holds the rule condition in natural language. It is a prompt, not a
 * program: the ReAct agent is shown the sentence verbatim, fetches the customer's data with its
 * tools and judges whether the rule is triggered and what it should score. Nothing parses this
 * column, so it must be written for a reader - a concrete threshold, a time window, and why the
 * pattern matters.
 */
@Entity
@Table(name = "risk_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskRule {

    @Id
    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "rule_name", nullable = false, length = 160)
    private String ruleName;

    /** Native PostgreSQL enum {@code rule_scope}. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "applies_to", nullable = false, columnDefinition = "rule_scope")
    private RuleScope appliesTo;

    /** Rule condition in natural language, shown to the agent exactly as stored. */
    @Column(name = "threshold_logic", nullable = false, columnDefinition = "text")
    private String thresholdLogic;

    /** Score added when the rule matches; the per-rule contribution is capped at this weight. */
    @Column(name = "weight", nullable = false, precision = 5, scale = 2)
    private BigDecimal weight;

    /** Whether this rule has to be evaluated for the given activity type. */
    public boolean appliesTo(ActivityType activityType) {
        return appliesTo != null && appliesTo.matches(activityType);
    }
}
