package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.call;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.callsAll;
import static com.sq.caa.agent.ScriptedChatModel.fails;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The <em>failure</em> paths of the rule-coverage guarantee.
 *
 * <p>{@link RuleCoverageGateTest} proves the gate for a model that misbehaves politely. This class
 * covers what the guarantee is actually for - the model that concludes before it has finished, the
 * model that runs out of steps, the model server that dies mid-run, and the model that awards itself
 * more points than a rule is worth. Each test is written so that removing the mechanism it names
 * makes it fail:
 *
 * <ul>
 *   <li>drop the gate and {@link #anEarlyConclusionIsRefusedAndTheAgentStillFinishesTheChecklist()}
 *       ends with one verdict instead of four;</li>
 *   <li>let {@link RiskAgentLoop#execute} return an incomplete run and
 *       {@link #aRunThatRunsOutOfStepsWithRulesUnjudgedFails()} reports a two-rule review as a
 *       finished four-rule one;</li>
 *   <li>let {@code execute} rethrow instead of settling from the partial run and
 *       {@link #aModelFailureMidRunKeepsEveryVerdictTheAgentAlreadySubmitted()} loses the agent's
 *       work along with the run;</li>
 *   <li>drop the clamp in {@code submit_rule_evaluation} and
 *       {@link #anInflatedScoreIsCappedAtTheRulesWeightAndTheAttemptIsRecorded()} reports 1019
 *       instead of 50.</li>
 * </ul>
 *
 * <p>No Spring context, no database and no language model: the model is {@link ScriptedChatModel}
 * and the evidence is {@link AgentTestFixtures}, whose planted activity supports the scripted
 * verdicts SANCTIONED_WIRE 30 + STRUCTURING 20 + UNATTRIBUTED_CRYPTO 15 = 65 (HIGH), with
 * DECLINE_BURST not triggered.
 */
class RuleCoverageGuaranteeTest {

    private static final int MAX_COVERAGE_REPROMPTS = 3;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();

    // ==================================================================
    // 1. The model concludes early, with rules outstanding
    // ==================================================================

    @Test
    @DisplayName("a model that concludes with rules outstanding is refused, finishes the checklist and "
            + "the run completes with coverage_complete true")
    void anEarlyConclusionIsRefusedAndTheAgentStillFinishesTheChecklist() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                // Concluding here must be refused: three rules have no verdict.
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                        "Sanctioned-jurisdiction wire found.", "File a report.")),
                // ... and the refusal must be actionable enough that the model can recover.
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        structuring, true, 20, "Three payments just under 10,000.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        crypto, true, 15, "XMR transfer with no exchange attribution.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        declines, false, 0, "No declined card transactions on file.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                        "Sanctioned wire, structuring and an unattributed transfer.",
                        "Escalate to the MLRO."))));

        AgentRunResult result = run(model, context, 40);

        // The early conclusion cost the model a turn and did not end the run.
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT),
                "the gate must have refused the early submit_final_assessment exactly once");
        assertEquals(6, model.turns(), "the loop must have kept going after the refusal");

        // The agent covered everything itself, which is the only way a run may complete.
        assertEquals(4, result.ruleOutcomes().size());
        assertEquals(4, result.rulesJudged());
        assertTrue(result.unjudgedRules().isEmpty());
        assertTrue(result.coverageComplete(),
                "the agent finished the checklist itself, so coverage_complete must be true");
        assertEquals(0, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.AGENT_JUDGED));

        // The refusal named every outstanding rule, which is what makes it actionable.
        String reprompt = repromptNaming(model, STRUCTURING);
        assertNotNull(reprompt, "the gate must name the rules that are still missing a verdict");
        assertTrue(reprompt.contains("3 rule(s) still have no verdict"));
        assertTrue(reprompt.contains(structuring.getRuleId().toString()));
        assertTrue(reprompt.contains(crypto.getRuleId().toString()));
        assertTrue(reprompt.contains(declines.getRuleId().toString()));
        assertFalse(reprompt.contains(sanctioned.getRuleId().toString()),
                "the rule that already had a verdict must not be asked for again");

        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
    }

    // ==================================================================
    // 2. The model runs out of steps with rules still open
    // ==================================================================

    @Test
    @DisplayName("a run that reaches max-steps with rules unjudged is FAILED, keeps the verdicts it "
            + "has and names the rules it never judged")
    void aRunThatRunsOutOfStepsWithRulesUnjudgedFails() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        // Two verdicts, then the model busies itself re-reading the checklist until the step budget
        // is gone. It never concludes, so the gate never even fires - only the failure policy stands
        // between this and a run that reports two rules as if it had judged four.
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        structuring, true, 20, "Three payments just under 10,000.")),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}")));

        AgentRunFailedException failure =
                assertThrows(AgentRunFailedException.class, () -> run(model, context, 6));
        AgentRunResult result = failure.result();

        assertEquals(6, model.turns(), "the loop must stop at max-steps, not run forever");
        assertEquals(6, result.steps());

        assertInstanceOf(IncompleteRuleCoverageException.class, failure.getCause());
        assertEquals(4, result.rulesTotal());
        assertEquals(2, result.rulesJudged());
        assertFalse(result.coverageComplete());
        assertEquals(List.of(UNATTRIBUTED_CRYPTO, DECLINE_BURST),
                result.unjudgedRules().stream().map(UnjudgedRule::ruleName).toList());
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_FAILED),
                "the unjudged rules must be visible in the trace, not only in the exception");

        Map<String, RuleOutcome> byName = outcomes(result);
        assertEquals(RuleVerdictSource.AGENT_JUDGED, byName.get(SANCTIONED_WIRE).source());
        assertEquals(RuleVerdictSource.AGENT_JUDGED, byName.get(STRUCTURING).source());
        assertFalse(byName.containsKey(UNATTRIBUTED_CRYPTO));
        assertFalse(byName.containsKey(DECLINE_BURST));

        // What was judged is kept and scored; what was not is absent, not zeroed.
        assertEquals(0, new BigDecimal("50.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertNull(result.agentRiskLevel());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));
        assertTrue(RiskAssessmentRows.build(result.assessmentId(), result.ruleOutcomes(),
                        AgentTestFixtures.NOW).stream()
                        .noneMatch(row -> row.getRuleId()
                                .equals(AgentTestFixtures.ruleNamed(rules, DECLINE_BURST).getRuleId())),
                "an unjudged rule must not reach risk_assessments at all, not even at 0.00");
    }

    // ==================================================================
    // 3. The model server dies mid-run
    // ==================================================================

    @Test
    @DisplayName("a model failure mid-run keeps every verdict the agent had already submitted, and "
            + "still reports the run as incomplete")
    void aModelFailureMidRunKeepsEveryVerdictTheAgentAlreadySubmitted() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        structuring, true, 20, "Three payments just under 10,000.")),
                fails(new IllegalStateException("the model server closed the connection"))));

        AgentRunFailedException failure =
                assertThrows(AgentRunFailedException.class, () -> run(model, context, 40));

        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("closed the connection"));

        AgentRunResult result = failure.result();
        assertNotNull(result, "a failed run must still carry the work the agent had done");
        assertEquals(2, result.ruleOutcomes().size(), "the verdicts already submitted survive");
        assertEquals(3, result.steps(), "the failed turn still counts as a step the run got to");

        Map<String, RuleOutcome> byName = outcomes(result);
        assertEquals("Wire of 25,000 to a bank in RU.", byName.get(SANCTIONED_WIRE).rationale());
        assertEquals("Three payments just under 10,000.", byName.get(STRUCTURING).rationale());
        assertTrue(byName.values().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.AGENT_JUDGED));

        // ... and the two rules it never reached are named rather than quietly closed.
        assertFalse(result.coverageComplete());
        assertEquals(2, result.unjudgedRules().size());
        assertTrue(result.unjudgedRuleNames().contains(UNATTRIBUTED_CRYPTO));
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));

        assertEquals(0, new BigDecimal("50.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(1, countSteps(trace, TraceStep.Type.FINAL));
    }

    // ==================================================================
    // 4. The agent awards itself more than a rule is worth
    // ==================================================================

    @Test
    @DisplayName("a score above the rule's weight is capped end to end, and the attempt is recorded")
    void anInflatedScoreIsCappedAtTheRulesWeightAndTheAttemptIsRecorded() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                // 999 for a rule worth 30.
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        sanctioned, true, 999, "This one is extremely serious.")),
                // A partial breach, scored below the weight - which the agent is allowed to do.
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        structuring, true, 5, "Only borderline; two payments, not three.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        crypto, true, 15, "XMR transfer with no exchange attribution.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        declines, false, 0, "No declines on file.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("CRITICAL",
                        "Serious findings.", "Escalate."))));

        AgentRunResult result = run(model, context, 40);
        Map<String, RuleOutcome> byName = outcomes(result);

        RuleOutcome capped = byName.get(SANCTIONED_WIRE);
        assertTrue(capped.scoreClamped(), "the model exceeded the weight and that must be on record");
        assertEquals(0, new BigDecimal("999.00").compareTo(capped.claimedScore()),
                "what the model asked for is kept for the reviewer");
        assertEquals(0, new BigDecimal("30.00").compareTo(capped.score()),
                "what is scored is the rule's weight, never more");

        RuleOutcome partial = byName.get(STRUCTURING);
        assertFalse(partial.scoreClamped(), "a score inside the weight is the agent's to choose");
        assertEquals(0, new BigDecimal("5.00").compareTo(partial.score()));

        assertFalse(byName.get(DECLINE_BURST).triggered());
        assertEquals(0, BigDecimal.ZERO.compareTo(byName.get(DECLINE_BURST).score()));

        // 30 + 5 + 15 + 0 - the total is arithmetic over capped scores, so no single rule can run
        // away with the run.
        assertEquals(0, new BigDecimal("50.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(RiskLevel.CRITICAL, result.agentRiskLevel(),
                "the agent's own band is kept, but only as a comparison");
        assertTrue(result.coverageComplete());
    }

    // ==================================================================
    // 4b. The model server refuses the prompt as too large
    // ==================================================================

    @Test
    @DisplayName("a prompt the model server refuses as too large is compacted and replayed, and the "
            + "run finishes normally")
    void aContextOverflowIsRetriedRatherThanLosingTheRun() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        AgentTestFixtures.verdict(context, sanctioned, true, 30, "RU wire.")),
                fails(new IllegalStateException(
                        "400 Bad Request: the prompt exceeds the model's context length")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        AgentTestFixtures.verdict(context, structuring, true, 20, "Near 10k.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        AgentTestFixtures.verdict(context, crypto, true, 15, "XMR.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        AgentTestFixtures.verdict(context, declines, false, 0, "None.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH", "Wire and structuring.",
                        "Escalate."))));

        AgentRunResult result = run(model, context, 40);

        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("too large"),
                "the trace must show that the transcript was compacted and the turn replayed");
        assertTrue(result.coverageComplete(), "a recovered overflow must not cost the run its coverage");
        assertEquals(4, result.rulesJudged());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
        assertEquals(6, model.turns());
    }

    @Test
    @DisplayName("settling a run in which nothing was judged invents nothing")
    void settlingWithoutAnyVerdictProducesNoOutcomeAtAll() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        AgentProperties properties = new AgentProperties(40, MAX_COVERAGE_REPROMPTS, 4096, 0.1, 32768,
                1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);

        // settle() is what RiskAgentLoop calls when a run dies before it could finish. With no
        // verdicts to settle it must produce an empty, honest result - not four zero-score rows.
        AgentRunResult result = new RiskAgentLoop(new ScriptedChatModel(List.of()),
                ToolCallingManager.builder().build(), jsonMapper, properties).settle(context, 0, 0L);

        assertTrue(result.ruleOutcomes().isEmpty());
        assertEquals(4, result.rulesTotal());
        assertEquals(4, result.unjudgedRules().size());
        assertFalse(result.coverageComplete());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalScore()));
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertNotNull(result.summary());
        assertTrue(result.summary().contains("INCOMPLETE ANALYSIS"));
        assertTrue(RiskAssessmentRows.build(result.assessmentId(), result.ruleOutcomes(),
                AgentTestFixtures.NOW).isEmpty(), "nothing judged means nothing written");
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_FAILED));
    }

    // ==================================================================
    // 5. The gate does not waste turns it does not need
    // ==================================================================

    @Test
    @DisplayName("a final assessment submitted before the last verdict of the same turn does not "
            + "produce a reprompt naming zero missing rules")
    void aConclusionOvertakenByItsOwnLastVerdictDoesNotBurnACoverageReprompt() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                callsAll(call(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                                AgentTestFixtures.verdict(context, sanctioned, true, 30, "RU wire.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                                structuring, true, 20, "Near-threshold payments.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                                crypto, true, 15, "XMR, no exchange."))),
                // The model puts the conclusion first and the last verdict second in ONE turn. The
                // conclusion is rejected (correctly - a rule was open when it arrived) but by the end
                // of the batch nothing is missing.
                callsAll(call(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                                "Sanctioned wire and structuring.", "Escalate.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                                declines, false, 0, "No declines on file."))),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                        "Sanctioned wire and structuring.", "Escalate."))));

        AgentRunResult result = run(model, context, 40);

        assertFalse(String.join("\n", model.userMessages()).contains("0 rule(s) still have no verdict"),
                "the loop must never tell the model that zero rules are missing");
        assertTrue(String.join("\n", model.userMessages()).contains("Every rule now has a verdict"),
                "with coverage closed, the model must be asked to conclude rather than to keep working");
        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("closed the coverage set"),
                "the trace must explain why the rejected conclusion was not a coverage failure");

        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesJudged());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
        assertEquals(3, model.turns());
    }

    // ==================================================================
    // 6. The guarantee is not negotiable, however the model phrases things
    // ==================================================================

    @Test
    @DisplayName("a final assessment written as prose is refused while any rule is still outstanding")
    void proseThatLooksLikeAnAssessmentCannotShortCircuitTheCoverageGate() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                        AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE), true, 30, "RU wire.")),
                says("Here is my final assessment: "
                        + "{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}"),
                says("As I said: "
                        + "{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}"),
                says("{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}"),
                says("{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}")));

        AgentRunFailedException failure =
                assertThrows(AgentRunFailedException.class, () -> run(model, context, 40));
        AgentRunResult result = failure.result();

        assertEquals(0, countSteps(trace, TraceStep.Type.PROSE_FINAL),
                "a written assessment must never be accepted while a rule has no verdict");
        assertNull(result.agentRiskLevel(), "the model's LOW must not have been recorded");
        assertEquals(MAX_COVERAGE_REPROMPTS + 1, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT));
        assertEquals(1, result.ruleOutcomes().size());
        assertEquals(3, result.unjudgedRules().size());
        assertFalse(result.coverageComplete());
        assertEquals(0, new BigDecimal("30.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.MEDIUM, result.riskLevel());
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context, int maxSteps) {
        AgentProperties properties = new AgentProperties(maxSteps, MAX_COVERAGE_REPROMPTS, 4096, 0.1,
                32768, 1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

    private static Map<String, RuleOutcome> outcomes(AgentRunResult result) {
        return result.ruleOutcomes().stream()
                .collect(Collectors.toMap(RuleOutcome::ruleName, outcome -> outcome));
    }

    private static long countSteps(AnalysisTrace trace, String type) {
        return trace.steps().stream().filter(step -> type.equals(step.type())).count();
    }

    private static String stepTexts(AnalysisTrace trace, String type) {
        return trace.steps().stream()
                .filter(step -> type.equals(step.type()))
                .map(TraceStep::text)
                .collect(Collectors.joining("\n"));
    }

    /** The reprompt that named {@code ruleName}, or null when the gate never sent one. */
    private static String repromptNaming(ScriptedChatModel model, String ruleName) {
        return model.userMessages().stream()
                .filter(message -> message.contains("still have no verdict") && message.contains(ruleName))
                .findFirst()
                .orElse(null);
    }

    private static String assessment(String level, String summary, String recommendations) {
        return """
                {"risk_level":"%s","summary":"%s","recommendations":"%s"}"""
                .formatted(level, summary, recommendations);
    }
}
