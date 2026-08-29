package com.sq.caa.service;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.Transaction;
import com.sq.caa.repository.AnalysisRunRepository;
import com.sq.caa.repository.TransactionRepository;
import com.sq.caa.repository.projection.ActivityTypeAggregate;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import com.sq.caa.repository.projection.CountryCount;
import com.sq.caa.repository.projection.CurrencyCount;
import com.sq.caa.repository.projection.StatusCount;
import com.sq.caa.rules.AggregateSnapshot;
import com.sq.caa.rules.EvaluationBatch;
import com.sq.caa.web.dto.CustomerDtos.ActivityTypeBreakdown;
import com.sq.caa.web.dto.CustomerDtos.CountryBreakdown;
import com.sq.caa.web.dto.CustomerDtos.CurrencyBreakdown;
import com.sq.caa.web.dto.CustomerDtos.CustomerActivitySummary;
import com.sq.caa.web.dto.CustomerDtos.CustomerSummary;
import com.sq.caa.web.dto.CustomerDtos.DailyAmount;
import com.sq.caa.web.dto.CustomerDtos.DominantCurrency;
import com.sq.caa.web.dto.CustomerDtos.LatestAnalysis;
import com.sq.caa.web.dto.CustomerDtos.StatusBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the aggregate payload behind {@code GET /api/customers/{id}/summary}.
 *
 * <p>The rollups come from the repository's grouped queries - one statement each - rather than from
 * loading the customer's transactions.
 *
 * <p>The timeline and the six velocity figures share one read: the customer's
 * {@link EvaluationBatch}, the same object the rule engine and the ReAct agent evaluate against.
 * That read is a single fetch-joined statement and it is briefly cached per customer, so the whole
 * endpoint costs a fixed number of queries regardless of how much activity the customer has. It
 * also removes any chance of the operator seeing a velocity figure that differs from the one a rule
 * fired on, because both read the identical snapshots.
 */
@Service
public class ActivitySummaryService {

    /** Length of the charting window, in days, ending today (UTC) inclusive. */
    private static final int TIMELINE_DAYS = 30;

    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /** Statuses that are always reported, even at zero, so the dashboard tiles never disappear. */
    private static final List<String> CANONICAL_STATUSES = List.of(
            Transaction.STATUS_COMPLETED,
            Transaction.STATUS_PENDING,
            Transaction.STATUS_FAILED,
            Transaction.STATUS_REVERSED);

    private final CustomerService customerService;
    private final RiskRuleService riskRuleService;
    private final TransactionRepository transactions;
    private final AnalysisRunRepository analysisRuns;

    public ActivitySummaryService(CustomerService customerService,
            RiskRuleService riskRuleService,
            TransactionRepository transactions,
            AnalysisRunRepository analysisRuns) {
        this.customerService = customerService;
        this.riskRuleService = riskRuleService;
        this.transactions = transactions;
        this.analysisRuns = analysisRuns;
    }

    /** Aggregates one customer's whole activity history for the dashboard. */
    @Transactional(readOnly = true)
    public CustomerActivitySummary summarise(UUID customerId) {
        Customer customer = customerService.requireCustomer(customerId);

        List<ActivityTypeAggregate> perType = transactions.aggregateByActivityType(customerId);
        List<ActivityTypeBreakdown> byActivityType = breakdownByActivityType(perType);
        List<StatusBreakdown> byStatus = breakdownByStatus(transactions.aggregateByStatus(customerId));
        List<CurrencyBreakdown> byCurrency = breakdownByCurrency(transactions.aggregateByCurrency(customerId));
        List<CountryBreakdown> countries =
                breakdownByCountry(transactions.aggregateByReceiverBankCountry(customerId));

        long totalTransactions = perType.stream().mapToLong(ActivityTypeAggregate::getTxCount).sum();
        BigDecimal totalAmount = perType.stream()
                .map(aggregate -> money(aggregate.getTotalAmount()))
                .reduce(ZERO_AMOUNT, BigDecimal::add);
        Instant firstActivityAt = perType.stream().map(ActivityTypeAggregate::getFirstAt)
                .filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant lastActivityAt = perType.stream().map(ActivityTypeAggregate::getLastAt)
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(null);

        long failedCount = countOf(byStatus, Transaction.STATUS_FAILED);
        long reversedCount = countOf(byStatus, Transaction.STATUS_REVERSED);

        List<AnalysisRunSummary> runs = analysisRuns.findSummaries(customerId);
        EvaluationBatch batch = riskRuleService.batchFor(customer);
        PeakVelocity velocity = peakVelocity(batch);

        return new CustomerActivitySummary(
                customerId,
                customerRow(customer, totalTransactions, byCurrency, lastActivityAt, runs),
                totalTransactions,
                totalAmount,
                firstActivityAt,
                lastActivityAt,
                countOf(byStatus, Transaction.STATUS_COMPLETED),
                countOf(byStatus, Transaction.STATUS_PENDING),
                failedCount,
                reversedCount,
                amountOf(byStatus, Transaction.STATUS_FAILED),
                amountOf(byStatus, Transaction.STATUS_REVERSED),
                ratio(failedCount, totalTransactions),
                byCurrency.size(),
                countries.size(),
                velocity.txCount24h(),
                velocity.amountSum24h(),
                velocity.failedCount24h(),
                velocity.distinctCountries30d(),
                velocity.cryptoRatio30d(),
                velocity.maxAmount30d(),
                byActivityType,
                byStatus,
                byCurrency,
                countries,
                dailyTimeline(batch),
                latestAnalysis(runs));
    }

    /**
     * The identity block of the payload, carrying the same aggregates the customer list shows so the
     * two screens can never disagree about a customer.
     */
    private CustomerSummary customerRow(Customer customer,
            long totalTransactions,
            List<CurrencyBreakdown> byCurrency,
            Instant lastActivityAt,
            List<AnalysisRunSummary> runs) {
        AnalysisRunSummary completed = lastCompleted(runs);
        return CustomerSummary.from(customer)
                .withActivity(totalTransactions, DominantCurrency.of(byCurrency), lastActivityAt)
                .withLatestRisk(completed == null ? null : completed.getRiskLevel(),
                        completed == null ? null : completed.getCreatedAt());
    }

    /** Newest COMPLETED run that reached a verdict; the list is already ordered newest first. */
    private static AnalysisRunSummary lastCompleted(List<AnalysisRunSummary> runs) {
        for (AnalysisRunSummary run : runs) {
            if (run.getStatus() == AnalysisStatus.COMPLETED && run.getRiskLevel() != null) {
                return run;
            }
        }
        return null;
    }

    /**
     * The six {@code agg.*} figures, peaked over the customer's whole history.
     *
     * <p>Every window is defined relative to a transaction, so there is one snapshot per transaction
     * and no single "current" value. The peak is the figure that matters: a threshold rule triggers
     * exactly when the peak crosses its threshold, so an operator looking at a triggered rule sees
     * the number that made it fire. The snapshots are read straight off the rule engine's own batch
     * and folded with the same maximum the agent's {@code get_customer_activity_summary} velocity
     * block uses, so the screen, the agent's narrative and the rule verdict cannot disagree.
     */
    private static PeakVelocity peakVelocity(EvaluationBatch batch) {
        long txCount24h = 0L;
        BigDecimal amountSum24h = BigDecimal.ZERO;
        long failedCount24h = 0L;
        long distinctCountries30d = 0L;
        BigDecimal cryptoRatio30d = BigDecimal.ZERO;
        BigDecimal maxAmount30d = BigDecimal.ZERO;
        for (Transaction transaction : batch.transactions()) {
            AggregateSnapshot snapshot = batch.aggregatesFor(transaction.getTransactionId());
            txCount24h = Math.max(txCount24h, snapshot.txCount24h());
            amountSum24h = amountSum24h.max(snapshot.amountSum24h());
            failedCount24h = Math.max(failedCount24h, snapshot.failedCount24h());
            distinctCountries30d = Math.max(distinctCountries30d, snapshot.distinctCountries30d());
            cryptoRatio30d = cryptoRatio30d.max(snapshot.cryptoRatio30d());
            maxAmount30d = maxAmount30d.max(snapshot.maxAmount30d());
        }
        return new PeakVelocity(txCount24h, amountSum24h, failedCount24h, distinctCountries30d,
                cryptoRatio30d, maxAmount30d);
    }

    /** The peak of each rolling window over the customer's history. All zero without activity. */
    private record PeakVelocity(long txCount24h,
            BigDecimal amountSum24h,
            long failedCount24h,
            long distinctCountries30d,
            BigDecimal cryptoRatio30d,
            BigDecimal maxAmount30d) {
    }

    /** All three activity types in enum order, zero-filled for the ones the customer never used. */
    private List<ActivityTypeBreakdown> breakdownByActivityType(List<ActivityTypeAggregate> aggregates) {
        Map<ActivityType, ActivityTypeBreakdown> byType = new EnumMap<>(ActivityType.class);
        for (ActivityType type : ActivityType.values()) {
            byType.put(type, new ActivityTypeBreakdown(type, 0L, ZERO_AMOUNT, ZERO_AMOUNT, ZERO_AMOUNT,
                    ZERO_AMOUNT, null, null));
        }
        for (ActivityTypeAggregate aggregate : aggregates) {
            byType.put(aggregate.getActivityType(), new ActivityTypeBreakdown(
                    aggregate.getActivityType(),
                    aggregate.getTxCount(),
                    money(aggregate.getTotalAmount()),
                    money(aggregate.getMinAmount()),
                    money(aggregate.getMaxAmount()),
                    money(aggregate.getAvgAmount()),
                    aggregate.getFirstAt(),
                    aggregate.getLastAt()));
        }
        return List.copyOf(byType.values());
    }

    /**
     * Status rollup with the four canonical statuses always present. Statuses are keyed
     * case-insensitively so a stray casing in the data cannot produce two tiles for one status.
     */
    private List<StatusBreakdown> breakdownByStatus(List<StatusCount> rows) {
        Map<String, StatusBreakdown> byStatus = new LinkedHashMap<>();
        for (String status : CANONICAL_STATUSES) {
            byStatus.put(key(status), new StatusBreakdown(status, 0L, ZERO_AMOUNT));
        }
        for (StatusCount row : rows) {
            StatusBreakdown incoming =
                    new StatusBreakdown(row.getStatus(), row.getTxCount(), money(row.getTotalAmount()));
            byStatus.merge(key(row.getStatus()), incoming, ActivitySummaryService::mergeStatus);
        }
        return List.copyOf(byStatus.values());
    }

    private static StatusBreakdown mergeStatus(StatusBreakdown existing, StatusBreakdown incoming) {
        return new StatusBreakdown(existing.status(),
                existing.transactionCount() + incoming.transactionCount(),
                existing.totalAmount().add(incoming.totalAmount()));
    }

    private List<CurrencyBreakdown> breakdownByCurrency(List<CurrencyCount> rows) {
        List<CurrencyBreakdown> result = new ArrayList<>(rows.size());
        for (CurrencyCount row : rows) {
            result.add(new CurrencyBreakdown(row.getCurrency(), row.getTxCount(),
                    money(row.getTotalAmount())));
        }
        return List.copyOf(result);
    }

    /** Counterparty countries are the beneficiary bank countries of the customer's payments. */
    private List<CountryBreakdown> breakdownByCountry(List<CountryCount> rows) {
        List<CountryBreakdown> result = new ArrayList<>(rows.size());
        for (CountryCount row : rows) {
            String country = row.getCountry() == null ? null : row.getCountry().trim().toUpperCase(Locale.ROOT);
            result.add(new CountryBreakdown(country, row.getTxCount(), money(row.getTotalAmount())));
        }
        return List.copyOf(result);
    }

    /**
     * Daily totals for the last {@value #TIMELINE_DAYS} days (UTC), oldest first and gap-filled, so
     * the chart can be drawn without any client-side bucketing.
     *
     * <p>Bucketed from the evaluation batch that is loaded anyway. The previous implementation paged
     * the window out of the database, which cost three extra selects per row: the detail
     * associations are nullable inverse to-ones, so Hibernate cannot proxy them and probes all three
     * detail tables for every transaction it loads - even though this loop only reads the timestamp
     * and the amount.
     */
    private List<DailyAmount> dailyTimeline(EvaluationBatch batch) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(TIMELINE_DAYS - 1L);

        Map<LocalDate, DailyAmount> buckets = new LinkedHashMap<>();
        for (int day = 0; day < TIMELINE_DAYS; day++) {
            LocalDate date = start.plusDays(day);
            buckets.put(date, new DailyAmount(date, 0L, ZERO_AMOUNT));
        }

        for (Transaction transaction : batch.transactions()) {
            if (transaction.getCreatedAt() == null) {
                continue;
            }
            LocalDate date = transaction.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            BigDecimal amount = money(transaction.getAmount());
            buckets.computeIfPresent(date, (bucketDate, bucket) -> new DailyAmount(bucketDate,
                    bucket.transactionCount() + 1, bucket.totalAmount().add(amount)));
        }
        return List.copyOf(buckets.values());
    }

    /** Head of the newest analysis run, or null when the customer has never been analysed. */
    private LatestAnalysis latestAnalysis(List<AnalysisRunSummary> runs) {
        return runs.isEmpty() ? null : LatestAnalysis.from(runs.getFirst());
    }

    private static long countOf(List<StatusBreakdown> rows, String status) {
        return rows.stream().filter(row -> status.equalsIgnoreCase(row.status()))
                .mapToLong(StatusBreakdown::transactionCount).sum();
    }

    private static BigDecimal amountOf(List<StatusBreakdown> rows, String status) {
        return rows.stream().filter(row -> status.equalsIgnoreCase(row.status()))
                .map(StatusBreakdown::totalAmount).reduce(ZERO_AMOUNT, BigDecimal::add);
    }

    private static double ratio(long part, long total) {
        if (total <= 0L) {
            return 0d;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String key(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO_AMOUNT : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Double value) {
        return value == null ? ZERO_AMOUNT : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
