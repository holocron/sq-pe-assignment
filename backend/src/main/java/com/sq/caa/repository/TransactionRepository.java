package com.sq.caa.repository;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.projection.ActivityTypeAggregate;
import com.sq.caa.repository.projection.CountryCount;
import com.sq.caa.repository.projection.CurrencyCount;
import com.sq.caa.repository.projection.StatusCount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Customer activity queries.
 *
 * <p>The {@code ...WithDetails} variants left-join-fetch the three shared-primary-key detail rows so
 * a page of transactions costs a single statement instead of three per row.
 *
 * <p>Optional filters are written as {@code column = coalesce(:param, column)} rather than
 * {@code (:param is null or column = :param)}. The latter makes each parameter appear once in a
 * bare {@code ? is null}, which PostgreSQL cannot type - it rejects the statement with
 * "could not determine data type of parameter" for the enum filter and resolves the untyped null to
 * {@code bytea} for the status filter. The {@code coalesce} form gives every parameter exactly one
 * typed occurrence and behaves identically because none of the filtered columns are nullable.
 */
@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    /** One transaction with its CARD/PAYMENT/CRYPTO detail eagerly loaded. */
    @Query("""
            select t
            from Transaction t
            join fetch t.customer
            left join fetch t.cardActivity
            left join fetch t.paymentActivity
            left join fetch t.cryptoActivity
            where t.transactionId = :transactionId
            """)
    Optional<Transaction> findByIdWithDetails(@Param("transactionId") UUID transactionId);

    /**
     * Page of a customer's transactions, newest first unless the {@link Pageable} says otherwise.
     * Every filter is optional: pass {@code null} to leave it out.
     */
    @Query("""
            select t
            from Transaction t
            where t.customer.customerId = :customerId
              and t.activityType = coalesce(:activityType, t.activityType)
              and lower(t.status) = lower(coalesce(cast(:status as string), t.status))
              and t.createdAt >= coalesce(:from, t.createdAt)
              and t.createdAt <= coalesce(:to, t.createdAt)
            """)
    Page<Transaction> findForCustomer(@Param("customerId") UUID customerId,
            @Param("activityType") ActivityType activityType,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** As {@link #findForCustomer}, with the per-type detail rows fetched in the same statement. */
    @Query(value = """
            select t
            from Transaction t
            join fetch t.customer c
            left join fetch t.cardActivity
            left join fetch t.paymentActivity
            left join fetch t.cryptoActivity
            where c.customerId = :customerId
              and t.activityType = coalesce(:activityType, t.activityType)
              and lower(t.status) = lower(coalesce(cast(:status as string), t.status))
              and t.createdAt >= coalesce(:from, t.createdAt)
              and t.createdAt <= coalesce(:to, t.createdAt)
            """,
            countQuery = """
            select count(t)
            from Transaction t
            where t.customer.customerId = :customerId
              and t.activityType = coalesce(:activityType, t.activityType)
              and lower(t.status) = lower(coalesce(cast(:status as string), t.status))
              and t.createdAt >= coalesce(:from, t.createdAt)
              and t.createdAt <= coalesce(:to, t.createdAt)
            """)
    Page<Transaction> findForCustomerWithDetails(@Param("customerId") UUID customerId,
            @Param("activityType") ActivityType activityType,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** Every transaction of a customer with its detail row - the input of the rule engine. */
    @Query("""
            select distinct t
            from Transaction t
            join fetch t.customer
            left join fetch t.cardActivity
            left join fetch t.paymentActivity
            left join fetch t.cryptoActivity
            where t.customer.customerId = :customerId
            order by t.createdAt desc
            """)
    List<Transaction> findAllForCustomerWithDetails(@Param("customerId") UUID customerId);

    List<Transaction> findByCustomer_CustomerIdOrderByCreatedAtDesc(UUID customerId);

    long countByCustomer_CustomerId(UUID customerId);

    // ---------------------------------------------------------------------
    // Dashboard aggregates
    // ---------------------------------------------------------------------

    /** Counts and sums per activity type. Returns one row per activity type the customer actually has. */
    @Query("""
            select t.activityType as activityType,
                   count(t) as txCount,
                   sum(t.amount) as totalAmount,
                   min(t.amount) as minAmount,
                   max(t.amount) as maxAmount,
                   avg(t.amount) as avgAmount,
                   min(t.createdAt) as firstAt,
                   max(t.createdAt) as lastAt
            from Transaction t
            where t.customer.customerId = :customerId
            group by t.activityType
            order by t.activityType
            """)
    List<ActivityTypeAggregate> aggregateByActivityType(@Param("customerId") UUID customerId);

    /** Distinct activity types the customer has, which drives the rule coverage set. */
    @Query("""
            select distinct t.activityType
            from Transaction t
            where t.customer.customerId = :customerId
            """)
    List<ActivityType> findDistinctActivityTypes(@Param("customerId") UUID customerId);

    @Query("""
            select t.status as status, count(t) as txCount, sum(t.amount) as totalAmount
            from Transaction t
            where t.customer.customerId = :customerId
            group by t.status
            order by count(t) desc
            """)
    List<StatusCount> aggregateByStatus(@Param("customerId") UUID customerId);

    @Query("""
            select t.currency as currency, count(t) as txCount, sum(t.amount) as totalAmount
            from Transaction t
            where t.customer.customerId = :customerId
            group by t.currency
            order by count(t) desc
            """)
    List<CurrencyCount> aggregateByCurrency(@Param("customerId") UUID customerId);

    /** Beneficiary-bank-country breakdown of the customer's payments. */
    @Query("""
            select pa.receiverBankCountry as country, count(t) as txCount, sum(t.amount) as totalAmount
            from Transaction t
            join t.paymentActivity pa
            where t.customer.customerId = :customerId
            group by pa.receiverBankCountry
            order by count(t) desc
            """)
    List<CountryCount> aggregateByReceiverBankCountry(@Param("customerId") UUID customerId);

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.customer.customerId = :customerId
            """)
    BigDecimal sumAmountForCustomer(@Param("customerId") UUID customerId);

    // ---------------------------------------------------------------------
    // Window aggregates backing the agg.* fields of the rule DSL.
    // Windows are half-open: (from, to].
    // ---------------------------------------------------------------------

    @Query("""
            select count(t)
            from Transaction t
            where t.customer.customerId = :customerId
              and t.createdAt > :from and t.createdAt <= :to
            """)
    long countInWindow(@Param("customerId") UUID customerId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select coalesce(sum(t.amount), 0)
            from Transaction t
            where t.customer.customerId = :customerId
              and t.createdAt > :from and t.createdAt <= :to
            """)
    BigDecimal sumAmountInWindow(@Param("customerId") UUID customerId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select coalesce(max(t.amount), 0)
            from Transaction t
            where t.customer.customerId = :customerId
              and t.createdAt > :from and t.createdAt <= :to
            """)
    BigDecimal maxAmountInWindow(@Param("customerId") UUID customerId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select count(t)
            from Transaction t
            where t.customer.customerId = :customerId
              and t.createdAt > :from and t.createdAt <= :to
              and lower(t.status) = lower(cast(:status as string))
            """)
    long countByStatusInWindow(@Param("customerId") UUID customerId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select count(t)
            from Transaction t
            where t.customer.customerId = :customerId
              and t.createdAt > :from and t.createdAt <= :to
              and t.activityType = :activityType
            """)
    long countByActivityTypeInWindow(@Param("customerId") UUID customerId,
            @Param("activityType") ActivityType activityType,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Distinct beneficiary bank countries the customer paid into within the window. */
    @Query("""
            select count(distinct pa.receiverBankCountry)
            from Transaction t
            join t.paymentActivity pa
            where t.customer.customerId = :customerId
              and t.createdAt > :from and t.createdAt <= :to
            """)
    long countDistinctReceiverCountriesInWindow(@Param("customerId") UUID customerId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
