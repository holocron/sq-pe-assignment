package com.sq.caa.web.dto;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.rules.FieldDefinition;
import com.sq.caa.rules.FieldType;
import com.sq.caa.rules.RuleMatch;
import com.sq.caa.rules.RuleOperator;
import com.sq.caa.rules.RuleParser;
import com.sq.caa.rules.RuleTestOutcome;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/** Request and response payloads of the rule administration API. */
public final class RuleDtos {

    private RuleDtos() {
    }

    /**
     * A stored rule.
     *
     * @param thresholdLogic     the logic as a JSON object, which is what the visual editor binds to
     * @param thresholdLogicText the exact text stored in the column, so an admin can still see a rule
     *                           that somehow became unparseable
     */
    public record RiskRuleDto(
            UUID ruleId,
            String ruleName,
            RuleScope appliesTo,
            JsonNode thresholdLogic,
            String thresholdLogicText,
            BigDecimal weight) {

        public static RiskRuleDto from(RiskRule rule) {
            return new RiskRuleDto(rule.getRuleId(), rule.getRuleName(), rule.getAppliesTo(),
                    safeTree(rule.getThresholdLogic()), rule.getThresholdLogic(), rule.getWeight());
        }

        private static JsonNode safeTree(String json) {
            try {
                return RuleParser.readTree(json);
            } catch (RuntimeException e) {
                return NullNode.getInstance();
            }
        }
    }

    /** Create or replace a rule. */
    public record RuleUpsertRequest(
            @NotBlank @Size(max = 160) String ruleName,
            @NotNull RuleScope appliesTo,
            @NotNull JsonNode thresholdLogic,
            @NotNull @DecimalMin("0.01") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2)
            BigDecimal weight) {
    }

    /** Try a draft rule against live data without saving it. */
    public record RuleTestRequest(
            @NotNull JsonNode thresholdLogic,
            RuleScope appliesTo,
            UUID customerId) {
    }

    /** Outcome of a draft rule test. */
    public record RuleTestResponse(
            int matchedCount,
            int evaluatedCount,
            int customerCount,
            boolean degraded,
            List<String> notes,
            List<RuleMatchDto> sampleMatches) {

        public static RuleTestResponse from(RuleTestOutcome outcome) {
            return new RuleTestResponse(outcome.matchedCount(), outcome.evaluatedCount(),
                    outcome.customerCount(), outcome.degraded(), outcome.notes(),
                    outcome.sampleMatches().stream().map(RuleMatchDto::from).toList());
        }
    }

    /** One matching transaction, with the trace explaining the match. */
    public record RuleMatchDto(
            UUID transactionId,
            UUID customerId,
            String customerName,
            ActivityType activityType,
            BigDecimal amount,
            String currency,
            String status,
            Instant createdAt,
            String explanation) {

        public static RuleMatchDto from(RuleMatch match) {
            return new RuleMatchDto(match.transactionId(), match.customerId(), match.customerName(),
                    match.activityType(), match.amount(), match.currency(), match.status(),
                    match.createdAt(), match.explanation());
        }
    }

    /**
     * One field the editor may offer.
     *
     * @param operators     operators valid for this field, in display order
     * @param options       enum options where applicable
     * @param optionsClosed whether a value outside {@code options} is rejected on write
     */
    public record FieldCatalogEntry(
            String field,
            String label,
            FieldType type,
            RuleScope appliesTo,
            List<RuleOperator> operators,
            List<String> options,
            boolean optionsClosed,
            boolean nullable,
            String description) {

        public static FieldCatalogEntry from(FieldDefinition definition) {
            return new FieldCatalogEntry(definition.field(), definition.label(), definition.type(),
                    definition.appliesTo(), definition.allowedOperators(), definition.options(),
                    definition.optionsClosed(), definition.nullable(), definition.description());
        }
    }
}
