package com.sq.caa.repository;

import com.sq.caa.domain.Customer;
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
}
