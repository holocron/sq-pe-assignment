package com.sq.caa.web.dto;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.AnalysisStatus;
import com.sq.caa.domain.Customer;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.repository.projection.AnalysisRunSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read models for customers and for the activity aggregates the operator dashboard is built on. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /** Row of the customer search results. Deliberately aggregate-free so search stays one query. */
    public record CustomerSummary(UUID customerId,
            String firstName,
            String lastName,
            String fullName,
            LocalDate dob,
            Integer age,
            String country) {

        public static CustomerSummary from(Customer customer) {
            return new CustomerSummary(customer.getCustomerId(), customer.getFirstName(),
                    customer.getLastName(), customer.getFullName(), customer.getDob(), customer.getAge(),
                    customer.getCountry());
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
     * per-status, per-currency and per-country breakdowns, a 30-day daily timeline for the chart and
     * the head of the latest analysis run.
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
            List<ActivityTypeBreakdown> byActivityType,
            List<StatusBreakdown> byStatus,
            List<CurrencyBreakdown> byCurrency,
            List<CountryBreakdown> counterpartyCountries,
            List<DailyAmount> dailyTimeline,
            LatestAnalysis latestAnalysis) {
    }
}
