package com.sq.caa.web.dto;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.rules.FieldDefinition;
import com.sq.caa.rules.FieldType;
import com.sq.caa.rules.JudgedTransaction;
import com.sq.caa.rules.RuleJudgement;
import com.sq.caa.rules.RuleValidator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response payloads of the rule administration API. */
public final class RuleDtos {

    private RuleDtos() {
    }

    /**
     * A stored rule.
     *
     * @param thresholdLogic the rule condition in plain English, exactly the text the agent is shown
     */
    public record RiskRuleDto(
            UUID ruleId,
            String ruleName,
            RuleScope appliesTo,
            String thresholdLogic,
            BigDecimal weight) {

        public static RiskRuleDto from(RiskRule rule) {
            return new RiskRuleDto(rule.getRuleId(), rule.getRuleName(), rule.getAppliesTo(),
                    rule.getThresholdLogic(), rule.getWeight());
        }
    }

    /**
     * Create or replace a rule.
     *
     * <p>The bounds mirror {@link RuleValidator}; bean validation gives the editor a per-field error
     * without a round trip through the service, and the service checks the same limits again on the
     * normalised text so nothing depends on the annotation alone.
     */
    public record RuleUpsertRequest(
            @NotBlank @Size(max = 160) String ruleName,
            @NotNull RuleScope appliesTo,
            @NotBlank @Size(min = 20, max = 2000) String thresholdLogic,
            @NotNull @DecimalMin("0.01") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
            BigDecimal weight) {
    }

    /**
     * Judge one draft rule against one customer.
     *
     * <p>The customer is mandatory: with a prose condition there is nothing to evaluate mechanically,
     * so the only answer is the one the model gives after reading that customer's activity.
     */
    public record RuleTestRequest(
            @Size(max = 160) String ruleName,
            @NotBlank @Size(min = 20, max = 2000) String thresholdLogic,
            @NotNull RuleScope appliesTo,
            @NotNull @DecimalMin("0.01") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
            BigDecimal weight,
            @NotNull UUID customerId) {
    }

    /**
     * The model's verdict on a draft rule.
     *
     * @param score      the model's estimated contribution, never above {@code weight}
     * @param model      model id that produced the verdict, {@code null} when no call was needed
     * @param durationMs wall time of the judgement, so the cost of the call is visible
     * @param notes      corrections and caveats: evidence truncated, ids dropped, score capped
     */
    public record RuleTestResponse(
            String ruleName,
            RuleScope appliesTo,
            BigDecimal weight,
            UUID customerId,
            String customerName,
            boolean triggered,
            BigDecimal score,
            List<RuleMatchDto> matchedTransactions,
            int matchedCount,
            int evaluatedTransactionCount,
            String rationale,
            String model,
            long durationMs,
            List<String> notes) {

        public static RuleTestResponse from(RuleJudgement judgement) {
            return new RuleTestResponse(judgement.ruleName(), judgement.appliesTo(),
                    judgement.weight(), judgement.customerId(), judgement.customerName(),
                    judgement.triggered(), judgement.score(),
                    judgement.matchedTransactions().stream().map(RuleMatchDto::from).toList(),
                    judgement.matchedCount(), judgement.evaluatedTransactionCount(),
                    judgement.rationale(), judgement.model(), judgement.durationMs(),
                    judgement.notes());
        }
    }

    /**
     * One transaction the model cited.
     *
     * @param reason the model's note on why it counted, or {@code null} when it gave none
     */
    public record RuleMatchDto(
            UUID transactionId,
            ActivityType activityType,
            BigDecimal amount,
            String currency,
            String status,
            Instant createdAt,
            String reason) {

        public static RuleMatchDto from(JudgedTransaction match) {
            return new RuleMatchDto(match.transactionId(), match.activityType(), match.amount(),
                    match.currency(), match.status(), match.createdAt(), match.reason());
        }
    }

    /**
     * One field the agent can see, as the rule editor's reference panel renders it.
     *
     * @param category grouping of the panel, lower case: {@code transaction}, {@code customer},
     *                 {@code card}, {@code payment}, {@code crypto}, {@code aggregate}
     * @param options  known values of an enumerated field, empty when it is free-form
     * @param example  a short sample value
     */
    public record FieldCatalogEntry(
            String field,
            String label,
            FieldType type,
            String category,
            RuleScope appliesTo,
            List<String> options,
            boolean nullable,
            String example,
            String description) {

        public static FieldCatalogEntry from(FieldDefinition definition) {
            return new FieldCatalogEntry(definition.field(), definition.label(), definition.type(),
                    definition.category().wireName(), definition.appliesTo(), definition.options(),
                    definition.nullable(), definition.example(), definition.description());
        }
    }
}
