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
 * <p>{@link RuleCoverageGateTest} proves the gate for a model that misbehaves politely: it stops
 * early, it waffles, it never calls a tool. This class covers what the guarantee is actually for -
 * the model that concludes before it has finished, the model that runs out of steps, the model
 * server that dies mid-run, and the model that contradicts the rule engine. Each test is written so
 * that removing the mechanism it names makes it fail:
 *
 * <ul>
 *   <li>drop the gate and {@link #anEarlyConclusionIsRefusedAndTheAgentStillFinishesTheChecklist()}
 *       ends with one agent verdict instead of four;</li>
 *   <li>drop the deterministic backfill in {@link RiskAgentLoop#settle} and
 *       {@link #rulesTheAgentNeverReachesAreBackfilledAndCoverageIsMarkedIncomplete()} ends with two
 *       rules unevaluated;</li>
 *   <li>let {@link RiskAgentLoop#execute} rethrow instead of settling from the partial run and
 *       {@link #aModelFailureMidRunKeepsEveryVerdictTheAgentAlreadySubmitted()} loses both the agent's
 *       work and the coverage;</li>
 *   <li>score from the agent's own verdict instead of the engine's and
 *       {@link #theDeterministicEngineWinsEveryDisagreementAndTheDisagreementIsRecorded()} reports 55
 *       instead of 65.</li>
 * </ul>
 *
 * <p>No Spring context, no database and no language model: the model is
 * {@link ScriptedChatModel} and the evidence is {@link AgentTestFixtures}, whose deterministic
 * verdicts are fixed at SANCTIONED_WIRE 30 + STRUCTURING 20 + UNATTRIBUTED_CRYPTO 15 = 65 (HIGH),
 * with DECLINE_BURST not triggered.
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
            + "still reaches 100% coverage by itself")
    void anEarlyConclusionIsRefusedAndTheAgentStillFinishesTheChecklist() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                // Concluding here must be refused: three rules have no verdict.
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                        "Sanctioned-jurisdiction wire found.", "File a report.")),
                // ... and the refusal must be actionable enough that the model can recover.
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(structuring, true, 20, "Three payments just under 10,000.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(crypto, true, 15, "XMR transfer with no exchange attribution.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(declines, false, 0, "No declined card transactions on file.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                        "Sanctioned wire, structuring and an unattributed transfer.",
                        "Escalate to the MLRO."))));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules), 40);

        // The early conclusion cost the model a turn and did not end the run.
        assertEquals(1, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT),
                "the gate must have refused the early submit_final_assessment exactly once");
        assertEquals(6, model.turns(), "the loop must have kept going after the refusal");

        // The agent, not the backfill, ended up covering everything.
        assertEquals(4, result.ruleOutcomes().size());
        assertEquals(4, result.rulesEvaluatedByAgent());
        assertEquals(0, result.rulesBackfilled());
        assertTrue(result.coverageComplete(),
                "the agent finished the checklist itself, so coverage_complete must be true");
        assertEquals(0, countSteps(trace, TraceStep.Type.BACKFILL));
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.AGENT));

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
    @DisplayName("rules the model never reaches within max-steps are completed by the deterministic "
            + "backfill and coverage_complete records that")
    void rulesTheAgentNeverReachesAreBackfilledAndCoverageIsMarkedIncomplete() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);

        // Two verdicts, then the model busies itself re-reading the checklist until the step budget
        // is gone. It never concludes, so the gate never even fires - only the backfill can save the
        // coverage set here.
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(structuring, true, 20, "Three payments just under 10,000.")),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                calls(RiskAgentTools.LIST_RISK_RULES, "{}")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules), 6);

        assertEquals(6, model.turns(), "the loop must stop at max-steps, not run forever");
        assertEquals(6, result.steps());

        // Coverage is still 100% - that is the guarantee.
        assertEquals(4, result.rulesTotal());
        assertEquals(4, result.ruleOutcomes().size(), "every applicable rule must end with a verdict");
        assertEquals(2, result.rulesEvaluatedByAgent());
        assertEquals(2, result.rulesBackfilled());
        assertFalse(result.coverageComplete(),
                "the backfill was needed, so coverage_complete must be false");

        Map<String, RuleOutcome> byName = outcomes(result);
        assertEquals(RuleVerdictSource.AGENT, byName.get(SANCTIONED_WIRE).source());
        assertEquals(RuleVerdictSource.AGENT, byName.get(STRUCTURING).source());
        assertEquals(RuleVerdictSource.DETERMINISTIC_FALLBACK, byName.get(UNATTRIBUTED_CRYPTO).source());
        assertEquals(RuleVerdictSource.DETERMINISTIC_FALLBACK, byName.get(DECLINE_BURST).source());
        assertEquals(2, countSteps(trace, TraceStep.Type.BACKFILL),
                "each backfilled rule must be visible in the trace");

        // The backfilled rules are really evaluated, not merely recorded as covered.
        assertTrue(byName.get(UNATTRIBUTED_CRYPTO).triggered());
        assertEquals(0, new BigDecimal("15.00").compareTo(byName.get(UNATTRIBUTED_CRYPTO).score()));
        assertFalse(byName.get(DECLINE_BURST).triggered());
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertNull(result.agentRiskLevel());
        assertTrue(result.summary().contains("deterministic rule engine"));
    }

    // ==================================================================
    // 3. The model server dies mid-run
    // ==================================================================

    @Test
    @DisplayName("a model failure mid-run keeps every verdict the agent had already submitted and "
            + "backfills only the rules it never reached")
    void aModelFailureMidRunKeepsEveryVerdictTheAgentAlreadySubmitted() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(structuring, false, 0, "The payments look ordinary to me.")),
                fails(new IllegalStateException("the model server closed the connection"))));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

        AgentRunFailedException failure = assertThrows(AgentRunFailedException.class,
                () -> run(model, context, 40));

        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("closed the connection"));

        AgentRunResult result = failure.result();
        assertNotNull(result, "a failed run must still carry its settled coverage set");
        assertEquals(4, result.ruleOutcomes().size(), "coverage must be complete even on a failed run");
        assertEquals(3, result.steps(), "the failed turn still counts as a step the run got to");

        Map<String, RuleOutcome> byName = outcomes(result);
        // What the agent established survives...
        assertEquals(RuleVerdictSource.AGENT, byName.get(SANCTIONED_WIRE).source());
        assertEquals("Wire of 25,000 to a bank in RU.", byName.get(SANCTIONED_WIRE).rationale());
        assertEquals(RuleVerdictSource.AGENT, byName.get(STRUCTURING).source());
        assertEquals("The payments look ordinary to me.", byName.get(STRUCTURING).rationale());
        assertEquals(2, result.rulesEvaluatedByAgent());
        // ... and only the rules it never reached are backfilled.
        assertEquals(RuleVerdictSource.DETERMINISTIC_FALLBACK, byName.get(UNATTRIBUTED_CRYPTO).source());
        assertEquals(RuleVerdictSource.DETERMINISTIC_FALLBACK, byName.get(DECLINE_BURST).source());
        assertEquals(2, result.rulesBackfilled());
        assertEquals(2, countSteps(trace, TraceStep.Type.BACKFILL));
        assertFalse(result.coverageComplete());

        // The cross-check still ran on the verdicts that survived, so the score is unaffected by the
        // failure.
        assertTrue(byName.get(STRUCTURING).disagreement());
        assertTrue(byName.get(STRUCTURING).triggered());
        assertEquals(1, result.disagreementCount());
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(1, countSteps(trace, TraceStep.Type.FINAL));
    }

    // ==================================================================
    // 4. The agent contradicts the rule engine
    // ==================================================================

    @Test
    @DisplayName("the deterministic engine wins every disagreement, in both directions, and each one "
            + "is recorded")
    void theDeterministicEngineWinsEveryDisagreementAndTheDisagreementIsRecorded() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(sanctioned, true, 30, "Wire of 25,000 to a bank in RU.")),
                // False negative: the agent clears a rule the engine triggers.
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(structuring, false, 0, "Three payments under 10,000 look routine.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(crypto, true, 15, "XMR transfer with no exchange attribution.")),
                // False positive: the agent invents a breach the engine does not see.
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(declines, true, 10, "I think I saw some declines.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("LOW",
                        "Nothing much to report.", "No action required."))));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules), 40);

        Map<String, RuleOutcome> byName = outcomes(result);

        // The rule the agent cleared is scored as triggered anyway - the false-negative safety net.
        RuleOutcome cleared = byName.get(STRUCTURING);
        assertTrue(cleared.disagreement());
        assertEquals(Boolean.FALSE, cleared.agentTriggered());
        assertTrue(cleared.triggered(), "the deterministic verdict is what stands");
        assertEquals(0, new BigDecimal("20.00").compareTo(cleared.score()));

        // The rule the agent invented is scored as not triggered - the engine wins both ways, so the
        // model cannot inflate a score any more than it can suppress one.
        RuleOutcome invented = byName.get(DECLINE_BURST);
        assertTrue(invented.disagreement());
        assertEquals(Boolean.TRUE, invented.agentTriggered());
        assertEquals(0, new BigDecimal("10.00").compareTo(invented.agentScore()),
                "what the agent claimed is kept for the reviewer");
        assertFalse(invented.triggered());
        assertEquals(0, BigDecimal.ZERO.compareTo(invented.score()));

        assertEquals(2, result.disagreementCount());
        assertEquals(2, countSteps(trace, TraceStep.Type.DISAGREEMENT));
        String disagreements = stepTexts(trace, TraceStep.Type.DISAGREEMENT);
        assertTrue(disagreements.contains(STRUCTURING));
        assertTrue(disagreements.contains(DECLINE_BURST));
        assertTrue(disagreements.contains("The deterministic result wins for scoring."));

        // 30 + 20 + 15 + 0: the agent's arithmetic (30 + 0 + 15 + 10 = 55) is never used.
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(RiskLevel.LOW, result.agentRiskLevel(),
                "the agent's own band is kept, but only as a comparison");
        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesEvaluatedByAgent());
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

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, verdict(sanctioned, true, 30, "RU wire.")),
                fails(new IllegalStateException(
                        "400 Bad Request: the prompt exceeds the model's context length")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, verdict(structuring, true, 20, "Near 10k.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, verdict(crypto, true, 15, "XMR.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, verdict(declines, false, 0, "None.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH", "Wire and structuring.",
                        "Escalate."))));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules), 40);

        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("too large"),
                "the trace must show that the transcript was compacted and the turn replayed");
        assertTrue(result.coverageComplete(), "a recovered overflow must not cost the run its coverage");
        assertEquals(4, result.rulesEvaluatedByAgent());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
        assertEquals(6, model.turns());
    }

    @Test
    @DisplayName("settling with no model in the loop at all still evaluates and scores every rule")
    void theDeterministicOnlyPathCoversTheWholeChecklist() {
        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext context = AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
        AgentProperties properties = new AgentProperties(40, MAX_COVERAGE_REPROMPTS, 4096, 0.1, 32768,
                1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), 25);

        // This is what RiskAnalysisService falls back to when a run failed before the loop could even
        // start - ReActRiskAgent.deterministicOnly - so it has to close the coverage set on its own.
        AgentRunResult result = new RiskAgentLoop(new ScriptedChatModel(List.of()),
                ToolCallingManager.builder().build(), jsonMapper, properties).settle(context, 0, 0L);

        assertEquals(4, result.ruleOutcomes().size());
        assertEquals(0, result.rulesEvaluatedByAgent());
        assertEquals(4, result.rulesBackfilled());
        assertFalse(result.coverageComplete());
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.DETERMINISTIC_FALLBACK));
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertNotNull(result.summary());
        assertEquals(4, countSteps(trace, TraceStep.Type.BACKFILL));
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

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                callsAll(call(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                                verdict(sanctioned, true, 30, "RU wire.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                                verdict(structuring, true, 20, "Near-threshold payments.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                                verdict(crypto, true, 15, "XMR, no exchange."))),
                // The model puts the conclusion first and the last verdict second in ONE turn. The
                // conclusion is rejected (correctly - a rule was open when it arrived) but by the end
                // of the batch nothing is missing.
                callsAll(call(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                                "Sanctioned wire and structuring.", "Escalate.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                                verdict(declines, false, 0, "No declines on file."))),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, assessment("HIGH",
                        "Sanctioned wire and structuring.", "Escalate."))));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules), 40);

        assertFalse(String.join("\n", model.userMessages()).contains("0 rule(s) still have no verdict"),
                "the loop must never tell the model that zero rules are missing");
        assertTrue(String.join("\n", model.userMessages()).contains("Every rule now has a verdict"),
                "with coverage closed, the model must be asked to conclude rather than to keep working");
        assertTrue(stepTexts(trace, TraceStep.Type.REPROMPT).contains("closed the coverage set"),
                "the trace must explain why the rejected conclusion was not a coverage failure");

        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesEvaluatedByAgent());
        assertEquals(RiskLevel.HIGH, result.agentRiskLevel());
        assertEquals(3, model.turns());
    }

    // ==================================================================
    // 6. The guarantee is not negotiable, however the model phrases things
    // ==================================================================

    @Test
    @DisplayName("a final assessment written as prose is refused while any rule is still outstanding")
    void proseThatLooksLikeAnAssessmentCannotShortCircuitTheCoverageGate() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION,
                        verdict(AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE), true, 30, "RU wire.")),
                says("Here is my final assessment: "
                        + "{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}"),
                says("As I said: "
                        + "{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}"),
                says("{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}"),
                says("{\"risk_level\":\"LOW\",\"summary\":\"All clear.\",\"recommendations\":\"None.\"}")));

        AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunResult result = run(model, AgentTestFixtures.context(UUID.randomUUID(), trace, rules), 40);

        assertEquals(0, countSteps(trace, TraceStep.Type.PROSE_FINAL),
                "a written assessment must never be accepted while a rule has no verdict");
        assertNull(result.agentRiskLevel(), "the model's LOW must not have been recorded");
        assertEquals(MAX_COVERAGE_REPROMPTS + 1, countSteps(trace, TraceStep.Type.COVERAGE_REPROMPT));
        assertEquals(4, result.ruleOutcomes().size());
        assertEquals(3, result.rulesBackfilled());
        assertFalse(result.coverageComplete());
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));
        assertEquals(RiskLevel.HIGH, result.riskLevel());
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext context, int maxSteps) {
        AgentProperties properties = new AgentProperties(maxSteps, MAX_COVERAGE_REPROMPTS, 4096, 0.1,
                32768, 1536, 10, "test-model", 2, 16, Duration.ofMinutes(5), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, null, jsonMapper, 25);
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

    private static String verdict(RiskRule rule, boolean triggered, int score, String rationale) {
        return """
                {"rule_id":"%s","triggered":%s,"score":%d,"transaction_ids":[],"rationale":"%s"}"""
                .formatted(rule.getRuleId(), triggered, score, rationale);
    }

    private static String assessment(String level, String summary, String recommendations) {
        return """
                {"risk_level":"%s","summary":"%s","recommendations":"%s"}"""
                .formatted(level, summary, recommendations);
    }
}
