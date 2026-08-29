package com.sq.caa.agent;

import static com.sq.caa.agent.AgentTestFixtures.DECLINE_BURST;
import static com.sq.caa.agent.AgentTestFixtures.SANCTIONED_WIRE;
import static com.sq.caa.agent.AgentTestFixtures.STRUCTURING;
import static com.sq.caa.agent.AgentTestFixtures.UNATTRIBUTED_CRYPTO;
import static com.sq.caa.agent.ScriptedChatModel.call;
import static com.sq.caa.agent.ScriptedChatModel.calls;
import static com.sq.caa.agent.ScriptedChatModel.callsAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sq.caa.domain.RiskRule;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The trace has to say which rule each step was about.
 *
 * <p>This is a regression test for a defect a reviewer hit on a real run: with twelve rules the
 * transcript rendered two dozen consecutive steps whose visible label was identical - "Submit rule
 * verdict", over and over - because the rule's identity existed only inside the collapsed arguments.
 * It matters more than a cosmetic complaint now that a rule condition is prose: the agent's
 * judgement <em>is</em> the verdict, with no deterministic engine behind it to check the result, so
 * the transcript is the only evidence of how each rule was decided.
 *
 * <p>The old tests passed on a trace where every row was the same, which is exactly why this
 * shipped. These assert on the two fields a reader actually reads - {@code subject} and
 * {@code outcome} - and on the property the defect violated: no two verdict steps of one run may
 * look alike.
 */
class TraceStepTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * One step of a trace persisted before {@code subject} and {@code outcome} existed, verbatim in
     * the shape of BUILD_SPEC section 4.
     */
    private static final String PERSISTED_BEFORE_THE_CHANGE = """
            {"n":1,"type":"tool_call","tool":"list_risk_rules","args":{},\
            "result_preview":"{\\"rulesTotal\\":12}","ms":812}""";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<RiskRule> rules = AgentTestFixtures.rules();
    private final AnalysisTrace trace = AgentTestFixtures.trace(UUID.randomUUID());
    private final AgentRunContext context =
            AgentTestFixtures.context(UUID.randomUUID(), trace, rules);

    @Test
    @DisplayName("every verdict step names its own rule and its own verdict, so four rules read as "
            + "four distinguishable rows rather than four identical ones")
    void verdictStepsNameTheRuleAndTheVerdict() {
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        RiskRule structuring = AgentTestFixtures.ruleNamed(rules, STRUCTURING);
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        RiskRule declines = AgentTestFixtures.ruleNamed(rules, DECLINE_BURST);

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.LIST_RISK_RULES, "{}"),
                // Two verdicts in one turn: the labels must follow their own calls, not the turn.
                callsAll(
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                                sanctioned, true, 30, "A 25,000 wire to a bank in RU.")),
                        call(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context,
                                structuring, true, 20, "Three payments just under 10,000 in a day."))),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context, crypto,
                        true, 15, "A 4,000 XMR transfer with no exchange attribution.")),
                calls(RiskAgentTools.SUBMIT_RULE_EVALUATION, AgentTestFixtures.verdict(context, declines,
                        false, 0, "No declined authorisations at all.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"HIGH","summary":"Sanctioned wire and structuring.",\
                        "recommendations":"File a report."}""")));

        AgentRunResult result = run(model);
        assertTrue(result.coverageComplete());

        List<TraceStep> verdicts = toolSteps(RiskAgentTools.SUBMIT_RULE_EVALUATION);
        assertEquals(4, verdicts.size());

        // The subject is the rule, in the words the compliance officer sees everywhere else.
        assertEquals(List.of(SANCTIONED_WIRE, STRUCTURING, UNATTRIBUTED_CRYPTO, DECLINE_BURST),
                verdicts.stream().map(TraceStep::subject).toList());
        // The outcome is the verdict, the score it contributed and how far coverage has got.
        assertEquals(List.of(
                        "triggered +30.00 (rule 1 of 4)",
                        "triggered +20.00 (rule 2 of 4)",
                        "triggered +15.00 (rule 3 of 4)",
                        "not triggered (rule 4 of 4)"),
                verdicts.stream().map(TraceStep::outcome).toList());

        // The defect itself: consecutive verdict rows that no reader could tell apart.
        assertEquals(4, verdicts.stream()
                .map(step -> step.subject() + " - " + step.outcome())
                .distinct()
                .count(), "no two verdict steps of one run may read alike");

        // The checklist step says how big the coverage set is without being expanded.
        TraceStep checklist = toolSteps(RiskAgentTools.LIST_RISK_RULES).getFirst();
        assertNull(checklist.subject(), "the checklist is not scoped to one rule");
        assertEquals("4 rules in scope", checklist.outcome());

        // And all of it survives into analysis_runs.trace, not just into the live objects.
        JsonNode persisted = firstPersistedStep(RiskAgentTools.SUBMIT_RULE_EVALUATION);
        assertEquals(SANCTIONED_WIRE, persisted.get("subject").stringValue());
        assertEquals("triggered +30.00 (rule 1 of 4)", persisted.get("outcome").stringValue());
    }

    @Test
    @DisplayName("an evidence step names what it opened: the transaction, or the policy query")
    void evidenceStepsNameWhatTheyOpened() {
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        UUID paymentId = context.inScopeTransactionIds(sanctioned.getRuleId()).getFirst();

        tools.getTransactionDetails(paymentId.toString());
        TraceStep.Note opened = tools.takeNote(RiskAgentTools.GET_TRANSACTION_DETAILS);
        assertNotNull(opened);
        // Type, amount, currency and day - what a reviewer needs to recognise the row. Never the id:
        // a UUID identifies the transaction without describing it.
        assertTrue(opened.subject().matches("PAYMENT [0-9,]+\\.\\d{2} CHF on \\d{4}-\\d{2}-\\d{2}"),
                "unrecognisable transaction descriptor: " + opened.subject());
        assertFalse(opened.subject().contains(paymentId.toString()));
        assertEquals("Completed", opened.outcome());

        tools.searchPolicyKnowledge("reporting threshold for structured payments", 3);
        TraceStep.Note searched = tools.takeNote(RiskAgentTools.SEARCH_POLICY_KNOWLEDGE);
        assertNotNull(searched);
        assertEquals("reporting threshold for structured payments", searched.subject());
        // No knowledge base is wired into this fixture, and the row says so rather than staying blank.
        assertEquals("0 passages", searched.outcome());

        tools.listTransactions("PAYMENT", null, null, 2, 0);
        assertEquals("2 of 4 transactions", tools.takeNote(RiskAgentTools.LIST_TRANSACTIONS).outcome());
    }

    @Test
    @DisplayName("a label is dropped rather than attached to the wrong call")
    void aNoteIsNeverAttachedToTheWrongCall() {
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);
        List<UUID> evidence = context.inScopeTransactionIds(sanctioned.getRuleId());

        tools.listRiskRules();
        tools.submitRuleEvaluation(sanctioned.getRuleId().toString(), true, 30.0,
                List.of(evidence.getFirst().toString()), "A 25,000 wire to a bank in RU.");

        // Asked for the wrong tool's label first: the queue resynchronises and this call goes
        // unlabelled. A verdict row that named someone else's rule would be worse than a bare one.
        assertNull(tools.takeNote(RiskAgentTools.SUBMIT_RULE_EVALUATION));
        assertEquals(SANCTIONED_WIRE, tools.takeNote(RiskAgentTools.SUBMIT_RULE_EVALUATION).subject());
        assertNull(tools.takeNote(RiskAgentTools.SUBMIT_RULE_EVALUATION), "the queue is empty now");
    }

    @Test
    @DisplayName("a refused call says so on its own row")
    void aRefusedCallIsLabelledAsRefused() {
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);

        // Triggered with no evidence: the tool refuses it and the verdict is NOT recorded, which the
        // trace must show instead of a row that looks like a submitted verdict.
        tools.submitRuleEvaluation(sanctioned.getRuleId().toString(), true, 30.0, List.of(),
                "Trust me.");

        TraceStep.Note note = tools.takeNote(RiskAgentTools.SUBMIT_RULE_EVALUATION);
        assertNotNull(note);
        assertNull(note.subject());
        assertEquals("call rejected", note.outcome());
    }

    @Test
    @DisplayName("both fields are optional: a step with nothing nameable renders exactly as it did "
            + "before, so a trace persisted without them still reads")
    void subjectAndOutcomeAreOptional() {
        TraceStep unlabelled = new TraceStep(1, TraceStep.Type.TOOL_CALL, null, "list_risk_rules",
                NODES.objectNode(), "{\"rulesTotal\":12}", 812L, null, null, null, null, null, null);
        ObjectNode rendered = unlabelled.toJson(NODES);
        JsonNode old = jsonMapper.readTree(PERSISTED_BEFORE_THE_CHANGE);

        // Exactly the shape that is already in analysis_runs.trace: the two keys are simply absent,
        // so every consumer of a document written before they existed keeps reading it unchanged.
        assertEquals(names(old), names(rendered));
        assertEquals("list_risk_rules", rendered.get("tool").stringValue());
        assertEquals(812, rendered.get("ms").asInt());
        assertNull(old.get("subject"));
        assertNull(old.get("outcome"));

        // The same through the recorder, which is how a real note-less step is produced.
        AnalysisTrace fresh = AgentTestFixtures.trace(UUID.randomUUID());
        fresh.toolCall("get_customer_profile", NODES.objectNode(), "{}", 12L, null);
        assertFalse(fresh.steps().getFirst().toJson(NODES).has("subject"));
        assertFalse(fresh.steps().getFirst().toJson(NODES).has("outcome"));
    }

    @Test
    @DisplayName("a label is one line, capped, and blank rather than empty")
    void labelsAreOneCappedLine() {
        assertNull(TraceStep.Note.of(null, null));
        assertNull(TraceStep.Note.of("   ", ""));

        TraceStep.Note wrapped = TraceStep.Note.of("Structuring:\n  repeated payments", "not triggered");
        assertEquals("Structuring: repeated payments", wrapped.subject());

        String long_ = "x".repeat(TraceStep.SUBJECT_LIMIT + 40);
        TraceStep.Note clipped = TraceStep.Note.of(long_, "y".repeat(TraceStep.OUTCOME_LIMIT + 40));
        assertEquals(TraceStep.SUBJECT_LIMIT, clipped.subject().length());
        assertEquals(TraceStep.OUTCOME_LIMIT, clipped.outcome().length());
        assertTrue(clipped.subject().endsWith("…"), "an over-long label is cut, not annotated");
    }

    // ------------------------------------------------------------------

    private AgentRunResult run(ScriptedChatModel model) {
        AgentProperties properties = new AgentProperties(40, 3, 4096, 0.1, 32768, 1536, 10,
                "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(context, null, null, jsonMapper, 25);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(context, tools);
    }

    private List<TraceStep> toolSteps(String tool) {
        return trace.steps().stream()
                .filter(step -> TraceStep.Type.TOOL_CALL.equals(step.type()))
                .filter(step -> tool.equals(step.tool()))
                .toList();
    }

    /** The keys a step rendered, sorted, so two documents can be compared by shape. */
    private static List<String> names(JsonNode node) {
        return node.propertyNames().stream().sorted().toList();
    }

    private JsonNode firstPersistedStep(String tool) {
        for (JsonNode step : trace.toJson().get("steps")) {
            if (step.has("tool") && tool.equals(step.get("tool").stringValue())) {
                return step;
            }
        }
        throw new AssertionError("no persisted step for tool " + tool);
    }
}
