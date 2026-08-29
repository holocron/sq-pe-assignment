package com.sq.caa.repository;

import com.sq.caa.domain.RiskAssessment;
import com.sq.caa.domain.RiskAssessmentId;
import com.sq.caa.repository.projection.RuleEvaluationRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-(transaction, rule) results of the analysis runs.
 *
 * <p>{@code assessment_id} is shared by every row of one run, so all lookups here are by
 * {@code assessmentId} rather than by the full composite key.
 */
@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, RiskAssessmentId> {

    List<RiskAssessment> findById_AssessmentId(UUID assessmentId);

    List<RiskAssessment> findById_TransactionId(UUID transactionId);

    long countById_AssessmentId(UUID assessmentId);

    /** Every row of one run with its rule and transaction loaded, triggered rows first. */
    @Query("""
            select ra
            from RiskAssessment ra
            join fetch ra.rule r
            join fetch ra.transaction t
            where ra.id.assessmentId = :assessmentId
            order by ra.scoreContribution desc, r.ruleName asc, t.createdAt desc
            """)
    List<RiskAssessment> findDetailedByAssessmentId(@Param("assessmentId") UUID assessmentId);

    /** Only the rows where the rule actually fired. */
    @Query("""
            select ra
            from RiskAssessment ra
            join fetch ra.rule r
            join fetch ra.transaction t
            where ra.id.assessmentId = :assessmentId
              and ra.scoreContribution > 0
            order by ra.scoreContribution desc, t.createdAt desc
            """)
    List<RiskAssessment> findTriggeredByAssessmentId(@Param("assessmentId") UUID assessmentId);

    /**
     * Rule-level rollup of one run: how many transactions each rule was evaluated against, how many
     * it fired on and the score it contributed. This is what proves rule coverage in the UI.
     */
    @Query("""
            select r.ruleId as ruleId,
                   r.ruleName as ruleName,
                   r.appliesTo as appliesTo,
                   r.weight as weight,
                   count(ra) as evaluatedCount,
                   sum(case when ra.scoreContribution > 0 then 1L else 0L end) as triggeredCount,
                   coalesce(sum(ra.scoreContribution), 0) as score
            from RiskAssessment ra
            join ra.rule r
            where ra.id.assessmentId = :assessmentId
            group by r.ruleId, r.ruleName, r.appliesTo, r.weight
            order by coalesce(sum(ra.scoreContribution), 0) desc, r.ruleName asc
            """)
    List<RuleEvaluationRow> summariseRulesForAssessment(@Param("assessmentId") UUID assessmentId);

    /** Total score of a run, i.e. the sum of every score contribution it recorded. */
    @Query("""
            select coalesce(sum(ra.scoreContribution), 0)
            from RiskAssessment ra
            where ra.id.assessmentId = :assessmentId
            """)
    BigDecimal totalScore(@Param("assessmentId") UUID assessmentId);

    /** Number of distinct rules the run recorded a verdict for. */
    @Query("""
            select count(distinct ra.id.ruleId)
            from RiskAssessment ra
            where ra.id.assessmentId = :assessmentId
            """)
    long countDistinctRules(@Param("assessmentId") UUID assessmentId);

    /** Ids of the rules the run already recorded a verdict for - the coverage tracker. */
    @Query("""
            select distinct ra.id.ruleId
            from RiskAssessment ra
            where ra.id.assessmentId = :assessmentId
            """)
    List<UUID> findEvaluatedRuleIds(@Param("assessmentId") UUID assessmentId);

    @Modifying
    @Transactional
    @Query("delete from RiskAssessment ra where ra.id.assessmentId = :assessmentId")
    int deleteByAssessmentId(@Param("assessmentId") UUID assessmentId);

    @Modifying
    @Transactional
    @Query("""
            delete from RiskAssessment ra
            where ra.id.assessmentId = :assessmentId and ra.id.ruleId = :ruleId
            """)
    int deleteByAssessmentIdAndRuleId(@Param("assessmentId") UUID assessmentId,
            @Param("ruleId") UUID ruleId);
}
