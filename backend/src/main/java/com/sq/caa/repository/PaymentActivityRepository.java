package com.sq.caa.repository;

import com.sq.caa.domain.PaymentActivity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Payment detail rows; the identifier is the owning transaction id. */
@Repository
public interface PaymentActivityRepository extends JpaRepository<PaymentActivity, UUID> {

    List<PaymentActivity> findByTransaction_Customer_CustomerId(UUID customerId);

    List<PaymentActivity> findByTransaction_TransactionIdIn(List<UUID> transactionIds);

    /** Payments of a customer into any of the given beneficiary bank countries, newest first. */
    @Query("""
            select pa
            from PaymentActivity pa
            join fetch pa.transaction t
            where t.customer.customerId = :customerId
              and upper(pa.receiverBankCountry) in :countries
            order by t.createdAt desc
            """)
    List<PaymentActivity> findForCustomerByReceiverBankCountry(@Param("customerId") UUID customerId,
            @Param("countries") Collection<String> countries);
}
