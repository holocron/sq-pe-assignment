package com.sq.caa.service;

import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.repository.AnalysisRunRepository;
import com.sq.caa.repository.CustomerRepository;
import com.sq.caa.repository.CustomerRepository.LatestAnalysisVerdict;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.repository.TransactionRepository.CustomerCurrencyTotal;
import com.sq.caa.web.dto.CustomerDtos.CurrencyBreakdown;
import com.sq.caa.web.dto.CustomerDtos.CustomerDetail;
import com.sq.caa.web.dto.CustomerDtos.CustomerSummary;
import com.sq.caa.web.dto.CustomerDtos.DominantCurrency;
import com.sq.caa.web.dto.PageResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
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
     *
     * <p>Each row carries the aggregates the dashboard table renders (transaction count, total
     * amount, last activity, last risk level). They cost two extra statements <b>per page</b>, not
     * per row: the page's ids are collected first and both aggregate queries are grouped over that
     * id list. A page of 200 customers therefore costs the same four statements as a page of two.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> search(String query, int page, int size) {
        PageRequest pageable = pageRequest(page, size, BY_NAME);
        Page<Customer> found = customers.search(blankToNull(query), pageable);
        List<UUID> ids = found.getContent().stream().map(Customer::getCustomerId).toList();
        Map<UUID, ActivityRollup> activity = activityRollups(ids);
        Map<UUID, LatestAnalysisVerdict> verdicts = latestVerdicts(ids);
        return PageResponse.of(found, customer -> decorate(customer, activity, verdicts));
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

    // ------------------------------------------------------------------
    // Page-wide aggregates
    // ------------------------------------------------------------------

    private CustomerSummary decorate(Customer customer,
            Map<UUID, ActivityRollup> activity,
            Map<UUID, LatestAnalysisVerdict> verdicts) {
        ActivityRollup rollup = activity.getOrDefault(customer.getCustomerId(), ActivityRollup.NONE);
        LatestAnalysisVerdict verdict = verdicts.get(customer.getCustomerId());
        return CustomerSummary.from(customer)
                .withActivity(rollup.transactionCount(), rollup.amount(), rollup.lastActivityAt())
                .withLatestRisk(verdict == null ? null : verdict.getRiskLevel(),
                        verdict == null ? null : verdict.getCreatedAt());
    }

    /** One grouped query for the whole page; customers with no activity are simply absent from it. */
    private Map<UUID, ActivityRollup> activityRollups(List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CurrencyBreakdown>> perCurrency = new HashMap<>();
        Map<UUID, Instant> lastActivity = new HashMap<>();
        for (CustomerCurrencyTotal row : transactions.aggregateByCustomerAndCurrency(customerIds)) {
            perCurrency.computeIfAbsent(row.getCustomerId(), id -> new ArrayList<>())
                    .add(new CurrencyBreakdown(row.getCurrency(), row.getTxCount(), row.getTotalAmount()));
            Instant seen = row.getLastActivityAt();
            Instant known = lastActivity.get(row.getCustomerId());
            if (seen != null && (known == null || seen.isAfter(known))) {
                lastActivity.put(row.getCustomerId(), seen);
            }
        }
        Map<UUID, ActivityRollup> rollups = new HashMap<>(perCurrency.size() * 2);
        perCurrency.forEach((customerId, currencies) -> {
            long transactionCount = currencies.stream()
                    .mapToLong(CurrencyBreakdown::transactionCount).sum();
            rollups.put(customerId, new ActivityRollup(transactionCount,
                    DominantCurrency.of(currencies), lastActivity.get(customerId)));
        });
        return rollups;
    }

    /**
     * One grouped query for the whole page. Two runs of the same customer can share a timestamp to
     * the microsecond, so the first row of the query's deterministic ordering wins.
     */
    private Map<UUID, LatestAnalysisVerdict> latestVerdicts(List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LatestAnalysisVerdict> verdicts = new HashMap<>();
        for (LatestAnalysisVerdict verdict :
                customers.findLatestVerdicts(customerIds, AnalysisStatus.COMPLETED)) {
            verdicts.putIfAbsent(verdict.getCustomerId(), verdict);
        }
        return verdicts;
    }

    /** The aggregates of one customer row, assembled from the page-wide grouped query. */
    private record ActivityRollup(long transactionCount, DominantCurrency amount, Instant lastActivityAt) {

        /** A customer with no transactions on file. */
        static final ActivityRollup NONE = new ActivityRollup(0L, DominantCurrency.NONE, null);
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
