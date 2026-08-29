package com.sq.caa.service;

import com.sq.caa.domain.Customer;
import com.sq.caa.repository.AnalysisRunRepository;
import com.sq.caa.repository.CustomerRepository;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.web.dto.CustomerDtos.CustomerDetail;
import com.sq.caa.web.dto.CustomerDtos.CustomerSummary;
import com.sq.caa.web.dto.PageResponse;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Customer lookup for the operator dashboard: search, profile and existence checks. */
@Service
public class CustomerService {

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Upper bound on the page size, so no caller can pull the whole table in one request. */
    public static final int MAX_PAGE_SIZE = 200;

    private static final Sort BY_NAME = Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName"));

    private final CustomerRepository customers;
    private final TransactionRepository transactions;
    private final AnalysisRunRepository analysisRuns;

    public CustomerService(CustomerRepository customers,
            TransactionRepository transactions,
            AnalysisRunRepository analysisRuns) {
        this.customers = customers;
        this.transactions = transactions;
        this.analysisRuns = analysisRuns;
    }

    /**
     * Searches customers by full or partial UUID or by name, case-insensitively. A blank query
     * returns every customer, ordered by last name then first name.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> search(String query, int page, int size) {
        PageRequest pageable = pageRequest(page, size, BY_NAME);
        return PageResponse.of(customers.search(blankToNull(query), pageable), CustomerSummary::from);
    }

    /** One customer profile. */
    @Transactional(readOnly = true)
    public CustomerDetail getCustomer(UUID customerId) {
        Customer customer = requireCustomer(customerId);
        return CustomerDetail.from(customer,
                transactions.countByCustomer_CustomerId(customerId),
                analysisRuns.countByCustomer_CustomerId(customerId));
    }

    /**
     * Loads a customer or fails the request with 404. Shared by the other read services so that an
     * unknown customer produces the same error whichever endpoint was called.
     */
    @Transactional(readOnly = true)
    public Customer requireCustomer(UUID customerId) {
        if (customerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A customer id is required.");
        }
        return customers.findById(customerId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Customer " + customerId + " was not found."));
    }

    /** Clamps caller-supplied paging parameters into the range this API is willing to serve. */
    static PageRequest pageRequest(int page, int size, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }

    /** Treats an omitted, empty or whitespace-only parameter as "no filter". */
    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
