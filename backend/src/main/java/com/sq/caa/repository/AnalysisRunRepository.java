package com.sq.caa.repository;

import com.sq.caa.domain.AnalysisRun;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persisted AI analysis runs. */
@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    /** One run with its customer loaded. */
    @Query("""
            select a
            from AnalysisRun a
            join fetch a.customer
            where a.assessmentId = :assessmentId
            """)
    Optional<AnalysisRun> findByIdWithCustomer(@Param("assessmentId") UUID assessmentId);

    /** Analysis history of one customer, newest first. */
    @Query("""
            select a
            from AnalysisRun a
            join fetch a.customer
            where a.customer.customerId = :customerId
            order by a.createdAt desc
            """)
    List<AnalysisRun> findByCustomerOrderByCreatedAtDesc(@Param("customerId") UUID customerId);

    /** Analysis history of one customer without the trace payload, newest first. */
    @Query("""
            select a.assessmentId as assessmentId,
                   c.customerId as customerId,
                   c.firstName as customerFirstName,
                   c.lastName as customerLastName,
                   a.status as status,
                   a.riskLevel as riskLevel,
                   a.totalScore as totalScore,
                   a.rulesTotal as rulesTotal,
                   a.rulesEvaluated as rulesEvaluated,
                   a.coverageComplete as coverageComplete,
                   a.requestedBy as requestedBy,
                   a.createdAt as createdAt,
                   a.completedAt as completedAt
            from AnalysisRun a
            join a.customer c
            where :customerId is null or c.customerId = :customerId
            order by a.createdAt desc
            """)
    List<AnalysisRunSummary> findSummaries(@Param("customerId") UUID customerId);

    @Query("""
            select a
            from AnalysisRun a
            join fetch a.customer
            order by a.createdAt desc
            """)
    Page<AnalysisRun> findAllOrderByCreatedAtDesc(Pageable pageable);

    List<AnalysisRun> findByStatusOrderByCreatedAtAsc(AnalysisStatus status);

    long countByCustomer_CustomerId(UUID customerId);

    Optional<AnalysisRun> findFirstByCustomer_CustomerIdOrderByCreatedAtDesc(UUID customerId);
}
