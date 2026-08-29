package com.sq.caa.repository;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** The configurable rule set. */
@Repository
public interface RiskRuleRepository extends JpaRepository<RiskRule, UUID> {

    List<RiskRule> findAllByOrderByRuleNameAsc();

    Optional<RiskRule> findByRuleNameIgnoreCase(String ruleName);

    boolean existsByRuleNameIgnoreCase(String ruleName);

    @Query("select r from RiskRule r where r.appliesTo in :scopes order by r.ruleName asc")
    List<RiskRule> findByAppliesToIn(@Param("scopes") Collection<RuleScope> scopes);

    /**
     * The coverage set for a customer: every rule scoped to {@link RuleScope#ALL} plus every rule
     * scoped to an activity type the customer actually has. Passing no activity types yields the
     * {@code ALL} rules only.
     */
    default List<RiskRule> findCoverageSet(Collection<ActivityType> activityTypes) {
        return findByAppliesToIn(RuleScope.coverageSetFor(activityTypes));
    }
}
