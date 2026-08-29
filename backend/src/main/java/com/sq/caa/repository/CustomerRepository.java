package com.sq.caa.repository;

import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskLevel;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Customer lookup for the operator dashboard. */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * Free-text customer search. A blank query returns every customer; otherwise the term is matched
     * case-insensitively against the customer id, the first name, the last name and the full name.
     */
    @Query("""
            select c
            from Customer c
            where :query is null
               or :query = ''
               or lower(c.firstName) like lower(concat('%', :query, '%'))
               or lower(c.lastName) like lower(concat('%', :query, '%'))
               or lower(concat(c.firstName, ' ', c.lastName)) like lower(concat('%', :query, '%'))
               or lower(cast(c.customerId as string)) like lower(concat('%', :query, '%'))
            """)
    Page<Customer> search(@Param("query") String query, Pageable pageable);

    /**
     * Newest analysis run in the given status, for every customer of a page, in one statement.
     *
     * <p>The customer list shows the last known risk level per row. Reading it run by run would be
     * an N+1, so the whole page is resolved at once: the correlated {@code max(createdAt)} keeps the
     * result at one row per customer (bar exact timestamp ties, which the caller resolves by taking
     * the first row of the deterministic ordering).
     *
     * <p>It lives on this repository rather than on {@code AnalysisRunRepository} because its only
     * purpose is to decorate customer search results, and it is keyed and ordered by customer.
     */
    @Query("""
            select r.customer.customerId as customerId,
                   r.riskLevel as riskLevel,
                   r.createdAt as createdAt
            from AnalysisRun r
            where r.customer.customerId in :customerIds
              and r.status = :status
              and r.riskLevel is not null
              and r.createdAt = (select max(peer.createdAt)
                                 from AnalysisRun peer
                                 where peer.customer.customerId = r.customer.customerId
                                   and peer.status = :status
                                   and peer.riskLevel is not null)
            order by r.customer.customerId asc, r.createdAt desc, r.assessmentId asc
            """)
    List<LatestAnalysisVerdict> findLatestVerdicts(@Param("customerIds") Collection<UUID> customerIds,
            @Param("status") AnalysisStatus status);

    /** The risk verdict {@link #findLatestVerdicts} carries for one customer. */
    interface LatestAnalysisVerdict {

        UUID getCustomerId();

        RiskLevel getRiskLevel();

        Instant getCreatedAt();
    }
}
