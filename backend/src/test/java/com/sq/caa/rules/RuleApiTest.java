package com.sq.caa.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sq.caa.domain.ActivityType;
import com.sq.caa.domain.RiskRule;
import com.sq.caa.domain.RuleScope;
import com.sq.caa.service.RiskRuleService;
import com.sq.caa.web.RuleController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The rule administration API, now that a condition is text.
 *
 * <p>Three contracts are pinned here, because three different consumers depend on them: a rule is
 * carried as prose under {@code thresholdLogic} and nothing parses it; a rejected write answers with
 * {@code application/problem+json} naming the input that has to change; and a judgement that could
 * not be obtained is reported with the status that tells the caller whether waiting would help.
 *
 * <p>The service is mocked - what is under test is the web contract, not the model.
 */
class RuleApiTest {

    private static final UUID RULE_ID = UUID.fromString("7115e643-31c1-5552-82cb-fe870c7a3a6a");
    private static final UUID CUSTOMER_ID = UUID.fromString("50f3ac6f-0f62-5b00-8314-cf99a4f3ac35");
    private static final UUID TRANSACTION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final String CONDITION = """
            Three or more payments each between 8,000 and 9,999.99 inside any rolling 24-hour \
            window, together totalling at least 20,000. Why it matters: this is structuring.""";

    private RiskRuleService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RiskRuleService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RuleController(service)).build();
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rule is carried as prose, not as a parsed tree")
    void listServesTheConditionAsText() throws Exception {
        when(service.findAll()).thenReturn(List.of(rule()));

        mockMvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleId").value(RULE_ID.toString()))
                .andExpect(jsonPath("$[0].appliesTo").value("PAYMENT"))
                .andExpect(jsonPath("$[0].weight").value(30.00))
                .andExpect(jsonPath("$[0].thresholdLogic").value(CONDITION))
                .andExpect(jsonPath("$[0].thresholdLogicText").doesNotExist());
    }

    @Test
    void createPassesTheConditionThroughUntouched() throws Exception {
        when(service.create(anyString(), any(), anyString(), any())).thenReturn(rule());

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(CONDITION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.thresholdLogic").value(CONDITION));

        ArgumentCaptor<String> condition = ArgumentCaptor.forClass(String.class);
        verify(service).create(eq("Structuring"), eq(RuleScope.PAYMENT), condition.capture(),
                eq(new BigDecimal("30.00")));
        assertThat(condition.getValue()).isEqualTo(CONDITION);
    }

    @Test
    void updateReplacesTheCondition() throws Exception {
        when(service.update(any(), anyString(), any(), anyString(), any())).thenReturn(rule());

        mockMvc.perform(put("/api/rules/" + RULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(CONDITION)))
                .andExpect(status().isOk());

        verify(service).update(eq(RULE_ID), eq("Structuring"), eq(RuleScope.PAYMENT), eq(CONDITION),
                eq(new BigDecimal("30.00")));
    }

    @Test
    void deleteAnswersNoContent() throws Exception {
        mockMvc.perform(delete("/api/rules/" + RULE_ID)).andExpect(status().isNoContent());

        verify(service).delete(RULE_ID);
    }

    @Test
    @DisplayName("a condition too short to judge is refused before it reaches the service")
    void rejectsAConditionThatIsTooShort() throws Exception {
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody("too short")))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a rejected write names the input the author has to fix")
    void reportsValidationFailuresAsProblemJson() throws Exception {
        when(service.create(anyString(), any(), anyString(), any())).thenThrow(
                new RuleValidationException("thresholdLogic", "looks like the old JSON rule DSL"));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(CONDITION)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid rule definition"))
                .andExpect(jsonPath("$.field").value("thresholdLogic"))
                .andExpect(jsonPath("$.detail").value(
                        "Invalid rule: thresholdLogic looks like the old JSON rule DSL"));
    }

    @Test
    void reportsADuplicateNameAsConflict() throws Exception {
        when(service.create(anyString(), any(), anyString(), any()))
                .thenThrow(new DuplicateRuleNameException("Structuring"));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(CONDITION)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ruleName").value("Structuring"));
    }

    @Test
    void reportsAMissingRuleAsNotFound() throws Exception {
        when(service.update(any(), anyString(), any(), anyString(), any()))
                .thenThrow(new RuleNotFoundException(RULE_ID));

        mockMvc.perform(put("/api/rules/" + RULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(CONDITION)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ruleId").value(RULE_ID.toString()));
    }

    // ------------------------------------------------------------------
    // Field catalog
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the field catalog is served as the reference list the editor renders")
    void servesTheFieldCatalog() throws Exception {
        when(service.fieldCatalog()).thenReturn(FieldCatalog.entries());

        mockMvc.perform(get("/api/rules/field-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(FieldCatalog.entries().size()))
                .andExpect(jsonPath("$[0].field").value("amount"))
                .andExpect(jsonPath("$[0].label").value("Amount"))
                .andExpect(jsonPath("$[0].type").value("NUMBER"))
                .andExpect(jsonPath("$[0].category").value("transaction"))
                .andExpect(jsonPath("$[0].appliesTo").value("ALL"))
                .andExpect(jsonPath("$[0].nullable").value(false))
                .andExpect(jsonPath("$[0].example").value("9975.00"))
                .andExpect(jsonPath("$[0].description").isNotEmpty())
                .andExpect(jsonPath("$[0].options").isArray());
    }

    @Test
    void catalogEntriesCarryTheirKnownValuesAndCategory() throws Exception {
        when(service.fieldCatalog()).thenReturn(FieldCatalog.entries());

        mockMvc.perform(get("/api/rules/field-catalog"))
                .andExpect(jsonPath("$[?(@.field == 'payment.payment_method')].options[0]")
                        .value("ACH"))
                .andExpect(jsonPath("$[?(@.field == 'payment.payment_method')].category")
                        .value("payment"))
                .andExpect(jsonPath("$[?(@.field == 'agg.crypto_ratio_30d')].category")
                        .value("aggregate"))
                .andExpect(jsonPath("$[?(@.field == 'card.decline_reason')].nullable").value(true));
    }

    // ------------------------------------------------------------------
    // Judging a draft rule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the test endpoint answers with the model's verdict, its evidence and its reasoning")
    void servesTheJudgement() throws Exception {
        when(service.judgeRule(any(RuleDraft.class), eq(CUSTOMER_ID))).thenReturn(judgement());

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(CONDITION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggered").value(true))
                .andExpect(jsonPath("$.score").value(30.00))
                .andExpect(jsonPath("$.weight").value(30.00))
                .andExpect(jsonPath("$.customerName").value("Marcus Holloway"))
                .andExpect(jsonPath("$.evaluatedTransactionCount").value(22))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.matchedTransactions[0].transactionId")
                        .value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.matchedTransactions[0].amount").value(9400.00))
                .andExpect(jsonPath("$.matchedTransactions[0].reason").value("Just under 10,000"))
                .andExpect(jsonPath("$.rationale").value("Five payments in one afternoon."))
                .andExpect(jsonPath("$.model").value("gpt-oss-120b-GGUF"))
                .andExpect(jsonPath("$.durationMs").value(4200))
                .andExpect(jsonPath("$.notes[0]").value("The score was capped at the weight."));
    }

    @Test
    @DisplayName("the draft is handed to the service exactly as the editor sent it")
    void passesTheDraftThrough() throws Exception {
        when(service.judgeRule(any(RuleDraft.class), eq(CUSTOMER_ID))).thenReturn(judgement());

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(CONDITION)))
                .andExpect(status().isOk());

        ArgumentCaptor<RuleDraft> draft = ArgumentCaptor.forClass(RuleDraft.class);
        verify(service).judgeRule(draft.capture(), eq(CUSTOMER_ID));
        assertThat(draft.getValue().condition()).isEqualTo(CONDITION);
        assertThat(draft.getValue().appliesTo()).isEqualTo(RuleScope.PAYMENT);
        assertThat(draft.getValue().ruleName()).isEqualTo("Structuring");
        assertThat(draft.getValue().weight()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("a customer is mandatory: with prose there is nothing to judge without one")
    void refusesAJudgementWithoutACustomer() throws Exception {
        String body = """
                {"ruleName": "Structuring", "thresholdLogic": "%s", "appliesTo": "PAYMENT", \
                "weight": 30.00}""".formatted(json(CONDITION));

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a stalled model is a gateway timeout, not a hung request")
    void reportsAJudgementTimeout() throws Exception {
        when(service.judgeRule(any(RuleDraft.class), any())).thenThrow(new RuleJudgementException(
                RuleJudgementException.Reason.TIMEOUT, "The model did not answer within 120 seconds."));

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(CONDITION)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.detail").value("The model did not answer within 120 seconds."));
    }

    @Test
    void reportsAnUnreadableAnswerAsABadGateway() throws Exception {
        when(service.judgeRule(any(RuleDraft.class), any())).thenThrow(new RuleJudgementException(
                RuleJudgementException.Reason.UNREADABLE_ANSWER, "Not a verdict."));

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(CONDITION)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("UNREADABLE_ANSWER"));
    }

    @Test
    void reportsABusyOrUnavailableJudgeAsServiceUnavailable() throws Exception {
        when(service.judgeRule(any(RuleDraft.class), any())).thenThrow(new RuleJudgementException(
                RuleJudgementException.Reason.BUSY, "Every rule-judgement slot is in use."));

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(CONDITION)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("BUSY"));
    }

    @Test
    void reportsAnUnknownCustomerAsNotFound() throws Exception {
        when(service.judgeRule(any(RuleDraft.class), any()))
                .thenThrow(new UnknownCustomerException(CUSTOMER_ID));

        mockMvc.perform(post("/api/rules/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testBody(CONDITION)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static RiskRule rule() {
        return RiskRule.builder()
                .ruleId(RULE_ID)
                .ruleName("Structuring")
                .appliesTo(RuleScope.PAYMENT)
                .thresholdLogic(CONDITION)
                .weight(new BigDecimal("30.00"))
                .build();
    }

    private static RuleJudgement judgement() {
        return new RuleJudgement("Structuring", RuleScope.PAYMENT, new BigDecimal("30.00"),
                CUSTOMER_ID, "Marcus Holloway", true, new BigDecimal("30.00"),
                List.of(new JudgedTransaction(TRANSACTION_ID, ActivityType.PAYMENT,
                        new BigDecimal("9400.00"), "USD", "Completed",
                        Instant.parse("2026-08-17T09:12:00Z"), "Just under 10,000")),
                1, 22, "Five payments in one afternoon.", "gpt-oss-120b-GGUF", 4200L,
                List.of("The score was capped at the weight."));
    }

    private static String upsertBody(String condition) {
        return """
                {"ruleName": "Structuring", "appliesTo": "PAYMENT", "thresholdLogic": "%s", \
                "weight": 30.00}""".formatted(json(condition));
    }

    private static String testBody(String condition) {
        return """
                {"ruleName": "Structuring", "thresholdLogic": "%s", "appliesTo": "PAYMENT", \
                "weight": 30.00, "customerId": "%s"}"""
                .formatted(json(condition), CUSTOMER_ID);
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
