package com.sq.caa.repository;

import com.sq.caa.domain.CryptoActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Crypto detail rows; the identifier is the owning transaction id. */
@Repository
public interface CryptoActivityRepository extends JpaRepository<CryptoActivity, UUID> {

    List<CryptoActivity> findByTransaction_Customer_CustomerId(UUID customerId);

    List<CryptoActivity> findByTransaction_TransactionIdIn(List<UUID> transactionIds);

    /** Crypto transfers of a customer with no attributed exchange, newest first. */
    @Query("""
            select ca
            from CryptoActivity ca
            join fetch ca.transaction t
            where t.customer.customerId = :customerId
              and (ca.exchangeName is null or ca.exchangeName = '')
            order by t.createdAt desc
            """)
    List<CryptoActivity> findUnattributedForCustomer(@Param("customerId") UUID customerId);
}
