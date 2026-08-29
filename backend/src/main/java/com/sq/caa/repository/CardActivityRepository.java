package com.sq.caa.repository;

import com.sq.caa.domain.CardActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Card detail rows; the identifier is the owning transaction id. */
@Repository
public interface CardActivityRepository extends JpaRepository<CardActivity, UUID> {

    List<CardActivity> findByTransaction_Customer_CustomerId(UUID customerId);

    List<CardActivity> findByTransaction_TransactionIdIn(List<UUID> transactionIds);

    /** Declined card authorisations of a customer, newest first. */
    @Query("""
            select ca
            from CardActivity ca
            join fetch ca.transaction t
            where t.customer.customerId = :customerId
              and ca.declineReason is not null
            order by t.createdAt desc
            """)
    List<CardActivity> findDeclinedForCustomer(@Param("customerId") UUID customerId);
}
