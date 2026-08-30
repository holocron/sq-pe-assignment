package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.says;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.agent.ToolPayloads.FinalAck;
import com.sq.caa.domain.RiskLevel;
import com.sq.caa.domain.RiskRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one discretionary move the agent still has: raising the band, never lowering it.
 *
 * <p>Every rule verdict is a query result and every rule score is the rule's weight, so the total
 * and the band it produces are arithmetic the model had no hand in. That band is a floor. The agent
 * may record a <em>higher</em> one when the pattern is worse than the individual rules capture -
 * three separately unremarkable rules that together look like layering, say - and when it does, the
 * reason goes on the record beside it, because an override nobody can read is indistinguishable from
 * a mistake.
 *
 * <p>What must never work is the other direction. A narrative that talks a scored breach down into a
 * clean review is precisely the failure this design was built to remove, so a lower band is refused
 * by the tool, and refused again by {@link RiskAgentLoop#settle} for the path where the model writes
 * its conclusion as prose and there is no tool call to refuse.
 *
 * <p>The fixture's four rules answer 30 + 20 + 15 + 0 = 65, which bands as HIGH; CRITICAL is
 * therefore the escalation and MEDIUM the attempted downgrade.
 */
class EscalationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();
    private final AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
    private final AgentRunContext context =
            AgentTestFixtures.context(UUID.randomUUID(), trace, rules);
    private final RiskAgentTools tools = new RiskAgentTools(context, null, null,
            AgentTestFixtures.evaluator(context), jsonMapper, 25, 3);

    @Test
    @DisplayName("a band above the mechanical one is accepted when the agent says why, and the "
            + "justification is what makes it admissible")
    void anEscalationIsAcceptedOnlyWithAJustification() {
        judgeEveryRule();
        assertEquals(RiskLevel.HIGH, context.mechanicalRiskLevel());

        // Without a reason it is refused outright, and the run is not concluded.
        FinalAck bare = assertInstanceOf(FinalAck.class, tools.submitFinalAssessment("CRITICAL",
                "Sanctioned wire, structuring and an unattributed transfer.", "Escalate.", null));
        assertFalse(bare.accepted());
        assertTrue(bare.message().startsWith("REFUSED"), bare.message());
        assertTrue(bare.message().contains("escalation_justification"), bare.message());
        assertEquals(RiskLevel.HIGH.name(), bare.mechanicalRiskLevel());
        assertFalse(context.isConcluded(), "a refused band must not end the run");

        // With one, it stands - and both bands come back so the reviewer sees the move.
        FinalAck escalated = assertInstanceOf(FinalAck.class, tools.submitFinalAssessment("CRITICAL",
                "Sanctioned wire, structuring and an unattributed transfer.", "Escalate.",
                "The three rules that fired are the same layering pattern seen end to end, which no "
                        + "single rule weight captures."));
        assertTrue(escalated.accepted());
        assertTrue(escalated.escalated());
        assertEquals(RiskLevel.CRITICAL.name(), escalated.recordedRiskLevel());
        assertEquals(RiskLevel.HIGH.name(), escalated.mechanicalRiskLevel());
        assertEquals(0, new BigDecimal("65.00").compareTo(escalated.totalScore()));
        assertTrue(escalated.message().contains("escalated from HIGH to CRITICAL"),
                escalated.message());

        FinalAssessment conclusion = context.finalAssessment();
        assertEquals(RiskLevel.CRITICAL, conclusion.riskLevel());
        assertTrue(conclusion.escalates(RiskLevel.HIGH));
        assertTrue(conclusion.escalationJustification().contains("layering"));
    }

    @Test
    @DisplayName("a band below the mechanical one is refused, whatever the narrative says")
    void aLowerBandIsRefused() {
        judgeEveryRule();

        FinalAck refused = assertInstanceOf(FinalAck.class, tools.submitFinalAssessment("MEDIUM",
                "On balance the customer's explanation is plausible.", "Periodic review.",
                "I am confident this is benign."));

        assertFalse(refused.accepted());
        assertTrue(refused.message().contains("cannot be set lower than the rules themselves"),
                refused.message());
        assertTrue(refused.message().contains("65.00"), "the total it argues against is quoted back");
        assertFalse(refused.escalated());
        assertFalse(context.isConcluded(),
                "no narrative may conclude a run below the band the queries produced");
    }

    @Test
    @DisplayName("an escalated run records both bands and the reason, and is still 100% covered")
    void anEscalatedRunPersistsBothBandsAndKeepsFullCoverage() {
        ScriptedChatModel model = new ScriptedChatModel(script(calls(
                RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                """
                {"risk_level":"CRITICAL","summary":"Sanctioned wire, structuring and an \
                unattributed transfer.","recommendations":"Escalate to the MLRO.",\
                "escalation_justification":"Three rules fired inside one week on one account, \
                which is the layering pattern rather than three coincidences."}""")));

        AgentRunResult result = run(model);

        assertEquals(RiskLevel.CRITICAL, result.riskLevel(), "the escalated band is what is recorded");
        assertEquals(RiskLevel.HIGH, result.mechanicalRiskLevel());
        assertEquals(RiskLevel.CRITICAL, result.agentRiskLevel());
        assertTrue(result.escalated());
        assertTrue(result.escalationJustification().contains("layering pattern"));
        assertEquals(0, new BigDecimal("65.00").compareTo(result.totalScore()));

        // Coverage is untouched by the escalation: every rule still has a SQL-derived verdict.
        assertTrue(result.coverageComplete());
        assertEquals(4, result.rulesJudged());
        assertEquals(4, result.ruleOutcomes().size());
        assertTrue(result.ruleOutcomes().stream()
                .allMatch(outcome -> outcome.source() == RuleVerdictSource.SQL_DERIVED));
        assertEquals(3, result.ruleOutcomes().stream().filter(RuleOutcome::triggered).count(),
                "escalating the overall band must not change a single rule's verdict");

        // The trace carries the move, both bands and the reason, so the UI can render "escalated
        // from HIGH to CRITICAL because ..." from the stored document alone.
        JsonNode last = finalStep();
        assertEquals(RiskLevel.CRITICAL.name(), last.get("risk_level").stringValue());
        assertEquals(RiskLevel.HIGH.name(), last.get("detail").get("mechanical_risk_level").stringValue());
        assertTrue(last.get("detail").get("escalated").asBoolean());
        assertTrue(last.get("detail").get("escalation_justification").stringValue()
                .contains("layering pattern"));
        assertEquals("escalated HIGH to CRITICAL", last.get("outcome").stringValue());
    }

    @Test
    @DisplayName("a run that is not escalated records the mechanical band with no justification")
    void anUnescalatedRunRecordsTheMechanicalBand() {
        ScriptedChatModel model = new ScriptedChatModel(script(calls(
                RiskAgentTools.SUBMIT_FINAL_ASSESSMENT,
                """
                {"risk_level":"HIGH","summary":"Sanctioned wire and structuring.",\
                "recommendations":"Open an investigation."}""")));

        AgentRunResult result = run(model);

        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(RiskLevel.HIGH, result.mechanicalRiskLevel());
        assertFalse(result.escalated());
        assertNull(result.escalationJustification());
        assertEquals("HIGH (HIGH from the rule scores)", finalStep().get("outcome").stringValue());
        assertFalse(finalStep().get("detail").get("escalated").asBoolean());
    }

    @Test
    @DisplayName("a conclusion written as prose may escalate on the same terms - with a reason, and "
            + "never downwards")
    void theProsePathObeysTheSameBandRule() {
        ScriptedChatModel model = new ScriptedChatModel(script(says("""
                Every rule has a verdict. My conclusion:
                {"risk_level":"CRITICAL","summary":"Sanctioned wire, structuring and an \
                unattributed transfer, all within a week.","recommendations":"Escalate to the MLRO.",\
                "escalation_justification":"Taken together these are one layering pattern."}""")));

        AgentRunResult escalated = run(model);
        assertEquals(RiskLevel.CRITICAL, escalated.riskLevel());
        assertEquals(RiskLevel.HIGH, escalated.mechanicalRiskLevel());
        assertTrue(escalated.escalationJustification().contains("layering pattern"));

        // The same path, arguing downwards: the band the queries produced is what is recorded, and
        // the model's own proposal is kept only so a reviewer can see where it differed.
        AnalysisTrace ownTrace = AgentTestFixtures.trace(UUID.randomUUID());
        AgentRunContext ownContext = AgentTestFixtures.context(UUID.randomUUID(), ownTrace, rules);
        ScriptedChatModel lowering = new ScriptedChatModel(script(says("""
                {"risk_level":"LOW","summary":"Nothing here concerns me.",\
                "recommendations":"None.","escalation_justification":"I am confident."}""")));

        AgentRunResult result = run(lowering, ownContext, ownTrace);
        assertEquals(RiskLevel.HIGH, result.riskLevel(), "a written LOW cannot clear a scored HIGH");
        assertEquals(RiskLevel.LOW, result.agentRiskLevel(), "what it asked for is still on record");
        assertFalse(result.escalated());
        assertNull(result.escalationJustification());
        assertEquals(3, result.ruleOutcomes().stream().filter(RuleOutcome::triggered).count(),
                "and not one of the rules it argued against was cleared");
    }

    // ------------------------------------------------------------------

    /** The four rules judged one per turn, then whatever conclusion the test scripts. */
    private List<ScriptedChatModel.Turn> script(ScriptedChatModel.Turn conclusion) {
        List<ScriptedChatModel.Turn> turns = new ArrayList<>();
        for (String name : List.of(SANCTIONED_WIRE, STRUCTURING, UNATTRIBUTED_CRYPTO, DECLINE_BURST)) {
            RiskRule rule = AgentTestFixtures.ruleNamed(rules, name);
            turns.add(calls(RiskAgentTools.EVALUATE_RULE,
                    AgentTestFixtures.evaluateRule(rule, "The activity this rule's condition names.")));
        }
        turns.add(conclusion);
        return turns;
    }

    private void judgeEveryRule() {
        for (RiskRule rule : rules) {
            tools.evaluateRule(rule.getRuleId().toString(), AgentTestFixtures.sqlFor(rule),
                    "The activity this rule's condition names.");
        }
        assertEquals(4, context.evaluatedCount());
    }

    private AgentRunResult run(ScriptedChatModel model) {
        return run(model, context, trace);
    }

    private AgentRunResult run(ScriptedChatModel model, AgentRunContext runContext,
            AnalysisTrace runTrace) {
        AgentProperties properties = new AgentProperties(40, 3, 3, 4096, 0.1, 32768, 1536, 10,
                "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools runTools = new RiskAgentTools(runContext, null, null,
                AgentTestFixtures.evaluator(runContext), jsonMapper, 25, 3);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        AgentRunResult result = loop.execute(runContext, runTools);
        assertTrue(runTrace.steps().stream().anyMatch(step -> TraceStep.Type.FINAL.equals(step.type())));
        return result;
    }

    private JsonNode finalStep() {
        for (JsonNode step : trace.toJson().get("steps")) {
            if (TraceStep.Type.FINAL.equals(step.get("type").stringValue())) {
                return step;
            }
        }
        throw new AssertionError("the run recorded no final step");
    }
}
