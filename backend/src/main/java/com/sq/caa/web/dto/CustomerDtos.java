package com.sq.caa.web.dto;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read models for customers and for the activity aggregates the operator dashboard is built on. */
public final class CustomerDtos {

    /** Every money field is emitted with two decimals, including the zero-activity case. */
    static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private CustomerDtos() {
    }

    /**
     * Row of the customer search results.
     *
     * <p>Carries the aggregates the dashboard table renders next to the name: {@code
     * transactionCount}, {@code totalAmount}, {@code lastActivityAt} and {@code lastRiskLevel} (the
     * verdict of the customer's most recent COMPLETED analysis, {@code null} when there is none).
     * They are <b>not</b> computed row by row - {@code CustomerService} resolves them for the whole
     * page in two grouped queries, so a page costs a constant number of statements no matter how
     * many customers it holds.
     *
     * <p><b>Currency.</b> A customer may transact in several currencies and this system has no FX
     * rates, so a single cross-currency total would be a fabricated number. {@code totalAmount} is
     * therefore the sum of one currency only - the customer's dominant currency, the one carrying
     * the most transactions - and {@code totalAmountCurrency} names it. {@code mixedCurrency} is
     * {@code true} when other currencies exist on file, so the UI can label the figure honestly
     * instead of implying it covers everything.
     */
    public record CustomerSummary(UUID customerId,
            String firstName,
            String lastName,
            String fullName,
            LocalDate dob,
            Integer age,
            String country,
            long transactionCount,
            BigDecimal totalAmount,
            String totalAmountCurrency,
            boolean mixedCurrency,
            Instant lastActivityAt,
            RiskLevel lastRiskLevel,
            Instant lastAnalysisAt) {

        /** Bare row: identity only, with the aggregates at their zero-activity values. */
        public static CustomerSummary from(Customer customer) {
            return new CustomerSummary(customer.getCustomerId(), customer.getFirstName(),
                    customer.getLastName(), customer.getFullName(), customer.getDob(), customer.getAge(),
                    customer.getCountry(), 0L, ZERO_AMOUNT, null, false, null, null, null);
        }

        /** The same row with this customer's activity rollup applied. */
        public CustomerSummary withActivity(long transactionCount, DominantCurrency amount,
                Instant lastActivityAt) {
            DominantCurrency total = amount == null ? DominantCurrency.NONE : amount;
            return new CustomerSummary(customerId, firstName, lastName, fullName, dob, age, country,
                    transactionCount, total.totalAmount(), total.currency(), total.mixed(),
                    lastActivityAt, lastRiskLevel, lastAnalysisAt);
        }

        /** The same row with the verdict of the most recent COMPLETED analysis applied. */
        public CustomerSummary withLatestRisk(RiskLevel lastRiskLevel, Instant lastAnalysisAt) {
            return new CustomerSummary(customerId, firstName, lastName, fullName, dob, age, country,
                    transactionCount, totalAmount, totalAmountCurrency, mixedCurrency, lastActivityAt,
                    lastRiskLevel, lastAnalysisAt);
        }
    }

    /** Single-customer profile, with the two counters the profile header shows. */
    public record CustomerDetail(UUID customerId,
            String firstName,
            String lastName,
            String fullName,
            LocalDate dob,
            Integer age,
            String country,
            long transactionCount,
            long analysisCount) {

        public static CustomerDetail from(Customer customer, long transactionCount, long analysisCount) {
            return new CustomerDetail(customer.getCustomerId(), customer.getFirstName(),
                    customer.getLastName(), customer.getFullName(), customer.getDob(), customer.getAge(),
                    customer.getCountry(), transactionCount, analysisCount);
        }
    }

    /**
     * Rollup for one activity type. All three types are always present, zero-filled when the customer
     * has no activity of that kind, so the dashboard can render three stable tabs.
     */
    public record ActivityTypeBreakdown(ActivityType activityType,
            long transactionCount,
            BigDecimal totalAmount,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal avgAmount,
            Instant firstAt,
            Instant lastAt) {
    }

    /** Rollup per transaction status (Completed, Pending, Failed, Reversed). */
    public record StatusBreakdown(String status, long transactionCount, BigDecimal totalAmount) {
    }

    /** Rollup per currency. */
    public record CurrencyBreakdown(String currency, long transactionCount, BigDecimal totalAmount) {

        public CurrencyBreakdown {
            totalAmount = totalAmount == null ? ZERO_AMOUNT : totalAmount;
        }
    }

    /**
     * The one currency a single "total amount" figure may honestly be quoted in.
     *
     * <p>Amounts in different currencies are never added together anywhere in this API - there is no
     * FX rate to add them with. Where the UI has room for exactly one number, it gets the total of
     * the currency the customer uses most, the code of that currency, and a flag telling it whether
     * other currencies are also on file.
     */
    public record DominantCurrency(String currency, BigDecimal totalAmount, boolean mixed) {

        /** The value for a customer with no activity at all. */
        public static final DominantCurrency NONE = new DominantCurrency(null, ZERO_AMOUNT, false);

        /** Most transactions wins; ties break on the larger sum, then on the currency code. */
        private static final Comparator<CurrencyBreakdown> DOMINANCE =
                Comparator.comparingLong(CurrencyBreakdown::transactionCount).reversed()
                        .thenComparing(CurrencyBreakdown::totalAmount, Comparator.reverseOrder())
                        .thenComparing(CurrencyBreakdown::currency,
                                Comparator.nullsLast(Comparator.naturalOrder()));

        public static DominantCurrency of(Collection<CurrencyBreakdown> byCurrency) {
            if (byCurrency == null || byCurrency.isEmpty()) {
                return NONE;
            }
            CurrencyBreakdown top = byCurrency.stream().min(DOMINANCE).orElseThrow();
            return new DominantCurrency(top.currency(), money(top.totalAmount()), byCurrency.size() > 1);
        }
    }

    /** Rollup per counterparty country, derived from the beneficiary bank country of payments. */
    public record CountryBreakdown(String country, long transactionCount, BigDecimal totalAmount) {
    }

    /** One bucket of the 30-day charting timeline. Days with no activity are present with zeroes. */
    public record DailyAmount(LocalDate date, long transactionCount, BigDecimal totalAmount) {
    }

    /** Head of the customer's most recent AI analysis run, or null when never analysed. */
    public record LatestAnalysis(UUID assessmentId,
            AnalysisStatus status,
            RiskLevel riskLevel,
            BigDecimal totalScore,
            boolean coverageComplete,
            Instant createdAt,
            Instant completedAt) {

        public static LatestAnalysis from(AnalysisRunSummary run) {
            return run == null ? null : new LatestAnalysis(run.getAssessmentId(), run.getStatus(),
                    run.getRiskLevel(), run.getTotalScore(), run.getCoverageComplete(), run.getCreatedAt(),
                    run.getCompletedAt());
        }
    }

    /**
     * Everything the customer dashboard needs in one response: headline totals, the per-type,
     * per-status, per-currency and per-country breakdowns, the six velocity aggregates, a 30-day
     * daily timeline for the chart and the head of the latest analysis run.
     *
     * <p><b>Velocity.</b> {@code txCount24h}, {@code amountSum24h}, {@code failedCount24h},
     * {@code distinctCountries30d}, {@code cryptoRatio30d} and {@code maxAmount30d} are the
     * {@code agg.*} values of the rule DSL, read off the customer's {@code EvaluationBatch} - the
     * very snapshots the engine scores rules against. Each window is defined relative to a
     * transaction, so what is reported is the <b>peak</b> over the customer's history: the busiest
     * rolling 24 hours, the largest 24-hour sum, and so on. That is the figure a threshold rule
     * fires on, and it is the same fold the agent's {@code get_customer_activity_summary} velocity
     * block reports, so the screen, the AI narrative and the rule verdict cannot disagree. Label
     * them as peaks in the UI, not as "the last 24 hours".
     *
     * <p>{@code totalAmount} here is the sum across every currency of the customer's history and is
     * only meaningful together with {@code byCurrency} / {@code distinctCurrencies}; the
     * single-currency figure lives on the nested {@link CustomerSummary}.
     */
    public record CustomerActivitySummary(UUID customerId,
            CustomerSummary customer,
            long totalTransactions,
            BigDecimal totalAmount,
            Instant firstActivityAt,
            Instant lastActivityAt,
            long completedCount,
            long pendingCount,
            long failedCount,
            long reversedCount,
            BigDecimal failedAmount,
            BigDecimal reversedAmount,
            double failedRatio,
            int distinctCurrencies,
            int distinctCounterpartyCountries,
            long txCount24h,
            BigDecimal amountSum24h,
            long failedCount24h,
            long distinctCountries30d,
            BigDecimal cryptoRatio30d,
            BigDecimal maxAmount30d,
            List<ActivityTypeBreakdown> byActivityType,
            List<StatusBreakdown> byStatus,
            List<CurrencyBreakdown> byCurrency,
            List<CountryBreakdown> counterpartyCountries,
            List<DailyAmount> dailyTimeline,
            LatestAnalysis latestAnalysis) {
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO_AMOUNT : value.setScale(2, RoundingMode.HALF_UP);
    }
}
