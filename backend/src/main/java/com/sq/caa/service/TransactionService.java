package com.sq.caa.service;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.web.dto.PageResponse;
import com.sq.caa.web.dto.TransactionDtos.TransactionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transaction reads for the activity tabs and the transaction drill-down.
 *
 * <p>Both entry points use the repository's fetch-joining queries, so a page of activity costs one
 * statement for the rows plus one for the count - the per-type detail never triggers a follow-up
 * select.
 */
@Service
public class TransactionService {

    /** Newest first, with the id as tie-breaker so paging is stable for same-instant rows. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("transactionId"));

    private final TransactionRepository transactions;
    private final CustomerService customerService;

    public TransactionService(TransactionRepository transactions, CustomerService customerService) {
        this.transactions = transactions;
        this.customerService = customerService;
    }

    /** One transaction with its CARD/PAYMENT/CRYPTO detail inlined. */
    @Transactional(readOnly = true)
    public TransactionView getTransaction(UUID transactionId) {
        if (transactionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A transaction id is required.");
        }
        return transactions.findByIdWithDetails(transactionId)
                .map(TransactionView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Transaction " + transactionId + " was not found."));
    }

    /**
     * Page of a customer's activity. Every filter is optional; {@code from}/{@code to} are inclusive
     * bounds on {@code createdAt}, {@code minAmount}/{@code maxAmount} inclusive bounds on
     * {@code amount} and {@code status} is matched case-insensitively. {@code sort} is a
     * whitelist-checked order from the controller; {@code null} keeps the default, newest first.
     *
     * @throws ResponseStatusException 404 when the customer does not exist, 400 when the time window
     *                                 is inverted
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionView> findCustomerActivity(UUID customerId,
            ActivityType activityType,
            String status,
            Instant from,
            Instant to,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Sort sort,
            int page,
            int size) {
        customerService.requireCustomer(customerId);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'from' must not be later than 'to'.");
        }
        PageRequest pageable = CustomerService.pageRequest(page, size,
                sort == null || sort.isUnsorted() ? NEWEST_FIRST : sort);
        Page<Transaction> found = transactions.findForCustomerWithDetails(customerId, activityType,
                CustomerService.blankToNull(status), from, to, minAmount, maxAmount, pageable);
        return PageResponse.of(found, TransactionView::from);
    }
}
