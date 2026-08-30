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
 * It matters more than a cosmetic complaint now that a rule condition is prose answered by a query:
 * the transcript is where a reviewer sees which rule a step decided, how it came out, and - since
 * the verdict is a row count - <em>what was actually run</em> to decide it.
 *
 * <p>So three things are asserted here. The two fields a reader reads while the step is collapsed,
 * {@code subject} and {@code outcome}; the property the original defect violated, that no two
 * verdict steps of one run may look alike; and the SQL on the expanded step, for the attempt that
 * produced the verdict <b>and</b> for the attempts that were refused on the way - a retry loop that
 * left no trace would hide exactly the behaviour this design exists to make visible.
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
                // Two rules in one turn: the labels must follow their own calls, not the turn.
                callsAll(
                        call(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(sanctioned,
                                "Payments over 10,000 to a sanctioned jurisdiction.")),
                        call(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(structuring,
                                "Three payments of 9,000-9,999 inside a rolling day."))),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(crypto,
                        "Crypto over 1,000 with no exchange attribution.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(declines,
                        "Five declined authorisations inside a rolling day.")),
                calls(RiskAgentTools.SUBMIT_FINAL_ASSESSMENT, """
                        {"risk_level":"HIGH","summary":"Sanctioned wire and structuring.",\
                        "recommendations":"File a report."}""")));

        AgentRunResult result = run(model);
        assertTrue(result.coverageComplete());

        List<TraceStep> verdicts = toolSteps(RiskAgentTools.EVALUATE_RULE);
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

        // The query is on the step too, so the verdict can be checked rather than believed - and
        // it is the statement that ran, not the fragment the model typed.
        assertEquals(4, verdicts.stream().filter(step -> step.detail() != null).count());
        String executed = verdicts.getFirst().detail().get("sql").stringValue();
        assertTrue(executed.startsWith(StubRuleSqlEvaluator.WRAPPER_PREFIX),
                "the trace must keep the statement that was executed: " + executed);
        assertTrue(executed.contains("receiver_bank_country"),
                "and the agent's own condition inside it: " + executed);

        // And all of it survives into analysis_runs.trace, not just into the live objects.
        JsonNode persisted = firstPersistedStep(RiskAgentTools.EVALUATE_RULE);
        assertEquals(SANCTIONED_WIRE, persisted.get("subject").stringValue());
        assertEquals("triggered +30.00 (rule 1 of 4)", persisted.get("outcome").stringValue());
        assertTrue(persisted.get("detail").get("sql").stringValue().contains("receiver_bank_country"));
    }

    @Test
    @DisplayName("a refused query and the retry that fixed it are both on the transcript, each with "
            + "the query it ran and why it was refused")
    void arefusedAttemptAndItsRetryAreBothVisible() {
        RiskRule crypto = AgentTestFixtures.ruleNamed(rules, UNATTRIBUTED_CRYPTO);
        AgentRunContext oneRule = AgentTestFixtures.context(UUID.randomUUID(), trace, List.of(crypto));
        StubRuleSqlEvaluator sql = new StubRuleSqlEvaluator()
                .rejecting("DROP", "the query must be a single SELECT; DROP is not allowed")
                .matching("exchange_name IS NULL", AgentTestFixtures.cryptoEvidence(oneRule));

        ScriptedChatModel model = new ScriptedChatModel(List.of(
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(crypto,
                        "SELECT t.transaction_id FROM tx t WHERE t.amount > 1000; "
                                + "DROP TABLE transactions",
                        "Crypto transfers with no exchange attribution.")),
                calls(RiskAgentTools.EVALUATE_RULE, AgentTestFixtures.evaluateRule(crypto,
                        "Crypto over 1,000 with no exchange attribution."))));

        run(model, sql, oneRule);

        List<TraceStep> steps = toolSteps(RiskAgentTools.EVALUATE_RULE);
        assertEquals(2, steps.size(), "the refused attempt must not vanish from the transcript");

        TraceStep refused = steps.getFirst();
        assertEquals(UNATTRIBUTED_CRYPTO, refused.subject());
        assertTrue(refused.outcome().startsWith("query rejected (attempt 1)"), refused.outcome());
        assertTrue(refused.detail().get("sql").stringValue().contains("DROP"),
                "the query that was refused is kept, or the retry loop is unreviewable");

        TraceStep accepted = steps.getLast();
        assertEquals(UNATTRIBUTED_CRYPTO, accepted.subject());
        assertEquals("triggered +15.00 (rule 1 of 1)", accepted.outcome());
        assertTrue(accepted.detail().get("sql").stringValue().contains("exchange_name IS NULL"));
    }

    @Test
    @DisplayName("an evidence step names what it opened: the transaction, or the policy query")
    void evidenceStepsNameWhatTheyOpened() {
        RiskAgentTools tools = tools();
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
        RiskAgentTools tools = tools();
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);

        tools.listRiskRules();
        tools.evaluateRule(sanctioned.getRuleId().toString(), AgentTestFixtures.sqlFor(sanctioned),
                "Payments over 10,000 to a sanctioned jurisdiction.");

        // Asked for the wrong tool's label first: the queue resynchronises and this call goes
        // unlabelled. A verdict row that named someone else's rule would be worse than a bare one.
        assertNull(tools.takeNote(RiskAgentTools.EVALUATE_RULE));
        assertEquals(SANCTIONED_WIRE, tools.takeNote(RiskAgentTools.EVALUATE_RULE).subject());
        assertNull(tools.takeNote(RiskAgentTools.EVALUATE_RULE), "the queue is empty now");
    }

    @Test
    @DisplayName("a call refused before any query ran says so on its own row")
    void aRefusedCallIsLabelledAsRefused() {
        RiskAgentTools tools = tools();
        RiskRule sanctioned = AgentTestFixtures.ruleNamed(rules, SANCTIONED_WIRE);

        // No explanation: the call is refused before the query is even run, nothing is recorded, and
        // the trace must show that instead of a row that looks like a verdict.
        tools.evaluateRule(sanctioned.getRuleId().toString(), AgentTestFixtures.sqlFor(sanctioned),
                "   ");

        TraceStep.Note note = tools.takeNote(RiskAgentTools.EVALUATE_RULE);
        assertNotNull(note);
        assertNull(note.subject());
        assertEquals("call rejected", note.outcome());
        assertNull(note.sql(), "no query ran, so there is no query to show");
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

    private RiskAgentTools tools() {
        return new RiskAgentTools(context, null, null, AgentTestFixtures.evaluator(context),
                jsonMapper, 25, 3);
    }

    private AgentRunResult run(ScriptedChatModel model) {
        return run(model, AgentTestFixtures.evaluator(context), context);
    }

    /** A run over a caller-built context, so a one-rule transcript can be read on its own. */
    private AgentRunResult run(ScriptedChatModel model, StubRuleSqlEvaluator sql,
            AgentRunContext runContext) {
        AgentProperties properties = new AgentProperties(40, 3, 3, 4096, 0.1, 32768, 1536, 10,
                "test-model", 2, 16, Duration.ofMinutes(5), Duration.ofMinutes(10), 25);
        RiskAgentTools tools = new RiskAgentTools(runContext, null, null, sql, jsonMapper, 25, 3);
        RiskAgentLoop loop = new RiskAgentLoop(model, ToolCallingManager.builder().build(), jsonMapper,
                properties);
        return loop.execute(runContext, tools);
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
