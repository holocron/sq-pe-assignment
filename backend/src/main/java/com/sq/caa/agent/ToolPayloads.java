package com.sq.caa.agent;

import com.sq.caa.web.dto.TransactionDtos.CardDetail;
import com.sq.caa.web.dto.TransactionDtos.CryptoDetail;
import com.sq.caa.web.dto.TransactionDtos.PaymentDetail;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The JSON documents the agent's tools hand back to the model.
 *
 * <p>Two deliberate choices, both about making the model's job unambiguous:
 * <ul>
 *   <li><b>Every instant is an ISO-8601 string.</b> Tool results are serialised by Spring AI's own
 *       mapper, not by the application's, so relying on its date settings would be guesswork; a
 *       pre-formatted string cannot be misread as an epoch number by the model either.
 *   <li><b>Failures are data, not exceptions.</b> A tool that throws costs a turn and teaches the
 *       model nothing, so every foreseeable failure returns {@link ToolError} with a hint about what
 *       to do instead.
 * </ul>
 */
public final class ToolPayloads {

    private ToolPayloads() {
    }

    /** Returned instead of a payload when a tool cannot answer. */
    public record ToolError(String error, String hint) {
    }

    // ------------------------------------------------------------------
    // get_customer_profile
    // ------------------------------------------------------------------

    public record CustomerProfile(
            String customerId,
            String fullName,
            String firstName,
            String lastName,
            String dateOfBirth,
            Integer age,
            String countryOfResidence,
            long transactionCount,
            String firstActivityAt,
            String lastActivityAt,
            List<String> activityTypesPresent) {
    }

    // ------------------------------------------------------------------
    // get_customer_activity_summary
    // ------------------------------------------------------------------

    public record TypeBreakdown(
            String activityType,
            long transactionCount,
            BigDecimal totalAmount,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal avgAmount,
            String firstAt,
            String lastAt) {
    }

    /** A grouped count, used for the status, currency and beneficiary-country breakdowns. */
    public record NamedCount(String value, long transactionCount, BigDecimal totalAmount) {
    }

    /** Peaks of the rolling windows the rule engine also uses, over the customer's whole history. */
    public record Velocity(
            long peakTransactionsInAny24h,
            BigDecimal peakAmountInAny24h,
            long peakFailedInAny24h,
            long maxDistinctBeneficiaryCountriesIn30d,
            BigDecimal maxCryptoShareOfActivityIn30d,
            BigDecimal largestSingleAmountIn30d) {
    }

    public record ActivitySummary(
            String customerId,
            long totalTransactions,
            BigDecimal totalAmount,
            String firstActivityAt,
            String lastActivityAt,
            long completedCount,
            long pendingCount,
            long failedCount,
            long reversedCount,
            double failedRatio,
            int distinctCurrencies,
            int distinctBeneficiaryCountries,
            List<TypeBreakdown> byActivityType,
            List<NamedCount> byStatus,
            List<NamedCount> byCurrency,
            List<NamedCount> byBeneficiaryBankCountry,
            Velocity velocity) {
    }

    // ------------------------------------------------------------------
    // list_transactions / get_transaction_details
    // ------------------------------------------------------------------

    public record TransactionRow(
            String transactionId,
            String activityType,
            BigDecimal amount,
            String currency,
            String status,
            String createdAt,
            String counterparty) {
    }

    public record TransactionPage(
            int matchingTransactions,
            int returned,
            int offset,
            boolean moreAvailable,
            List<TransactionRow> transactions) {
    }

    /** The {@code agg.*} values of the rule DSL as seen from one transaction. */
    public record TransactionAggregates(
            long transactionsInPrior24h,
            BigDecimal amountSumInPrior24h,
            long failedInPrior24h,
            long distinctBeneficiaryCountriesInPrior30d,
            BigDecimal cryptoShareOfActivityInPrior30d,
            BigDecimal largestAmountInPrior30d) {
    }

    public record TransactionDetail(
            String transactionId,
            String customerId,
            String customerName,
            String activityType,
            BigDecimal amount,
            String currency,
            String status,
            String createdAt,
            CardDetail card,
            PaymentDetail payment,
            CryptoDetail crypto,
            TransactionAggregates customerAggregatesAtThisTransaction) {
    }

    // ------------------------------------------------------------------
    // list_risk_rules
    // ------------------------------------------------------------------

    public record RuleListing(
            String ruleId,
            String ruleName,
            String appliesTo,
            BigDecimal weight,
            JsonNode thresholdLogic,
            String logicInPlainEnglish,
            int transactionsInScope,
            boolean verdictAlreadySubmitted) {
    }

    public record RuleList(
            int rulesTotal,
            int verdictsSubmitted,
            int verdictsStillRequired,
            List<RuleListing> rules,
            String instruction) {
    }

    // ------------------------------------------------------------------
    // evaluate_rule_deterministically
    // ------------------------------------------------------------------

    public record RuleEngineMatch(
            String transactionId,
            String activityType,
            BigDecimal amount,
            String currency,
            String status,
            String createdAt,
            String whyItMatched) {
    }

    public record RuleEngineVerdict(
            String ruleId,
            String ruleName,
            String appliesTo,
            BigDecimal weight,
            boolean triggered,
            BigDecimal score,
            int transactionsEvaluated,
            int matchedCount,
            List<String> matchedTransactionIds,
            List<RuleEngineMatch> sampleMatches,
            boolean degraded,
            List<String> degradationNotes,
            String explanation) {
    }

    // ------------------------------------------------------------------
    // submit_rule_evaluation / submit_final_assessment
    // ------------------------------------------------------------------

    public record MissingRule(String ruleId, String ruleName, String appliesTo) {
    }

    public record VerdictAck(
            boolean accepted,
            String ruleId,
            String ruleName,
            boolean recordedAsTriggered,
            BigDecimal recordedScore,
            boolean agreesWithRuleEngine,
            String crossCheck,
            int rulesTotal,
            int verdictsSubmitted,
            int verdictsStillRequired,
            List<MissingRule> rulesStillMissingAVerdict,
            String nextAction) {
    }

    public record FinalAck(
            boolean accepted,
            int rulesTotal,
            int verdictsSubmitted,
            int verdictsStillRequired,
            List<MissingRule> rulesStillMissingAVerdict,
            String message) {
    }

    // ------------------------------------------------------------------
    // search_policy_knowledge
    // ------------------------------------------------------------------

    public record KnowledgeSearchResult(
            String query,
            int returned,
            JsonNode passages,
            String note) {
    }

    /**
     * One policy passage as the agent sees it.
     *
     * <p>Deliberately narrower than {@link com.sq.caa.rag.RetrievedChunk}: the chunk and document
     * UUIDs are dropped because the model cites {@code citation} - "file > section" - and never the
     * ids, while a pair of UUIDs per passage is pure context spend on the worst-tokenising text
     * there is.
     */
    public record KnowledgePassage(
            String citation,
            String filename,
            String sectionTitle,
            double score,
            String content) {
    }
}
