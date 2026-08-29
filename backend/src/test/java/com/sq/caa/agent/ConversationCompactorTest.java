package com.sq.caa.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The compactor is what stops a long analysis from dying on
 * {@code 500: Context size has been exceeded}. Two properties matter and both are asserted here:
 * the transcript ends up inside the budget, and it stays a <em>valid</em> transcript - same number
 * of messages, same tool-call/tool-response pairing, system prompt and task untouched.
 */
class ConversationCompactorTest {

    /**
     * Comfortably above the structural floor of the 82-message fixture (every message still costs
     * its role framing, its elision placeholder and its tool-call arguments) and far below its
     * ~10,000-token natural size, so the assertions test compaction rather than arithmetic.
     */
    private static final int BUDGET = 6000;

    @Test
    @DisplayName("a transcript that already fits is returned untouched")
    void shortTranscriptIsNotCompacted() {
        List<Message> history = transcript(2);
        List<Message> compacted = new ConversationCompactor(BUDGET, 10).compact(history, 100);
        assertSame(history, compacted);
    }

    @Test
    @DisplayName("an oversized transcript is brought inside the budget")
    void oversizedTranscriptIsCompacted() {
        List<Message> history = transcript(40);
        int before = ConversationCompactor.estimateTokens(history);
        assertTrue(before > BUDGET, "fixture must actually overflow the budget, was " + before);

        List<Message> compacted = new ConversationCompactor(BUDGET, 10).compact(history, 200);
        int after = ConversationCompactor.estimateTokens(compacted) + 200;
        assertTrue(after <= BUDGET, "compacted transcript is still " + after + " tokens");
    }

    @Test
    @DisplayName("compaction preserves message count and tool-call pairing")
    void compactionKeepsTheTranscriptValid() {
        List<Message> history = transcript(40);
        List<Message> compacted = new ConversationCompactor(BUDGET, 10).compact(history, 200);

        assertEquals(history.size(), compacted.size(), "no message may be dropped");
        for (int i = 0; i < history.size(); i++) {
            assertEquals(history.get(i).getMessageType(), compacted.get(i).getMessageType());
        }
        for (int i = 0; i < compacted.size(); i++) {
            if (compacted.get(i) instanceof ToolResponseMessage responses) {
                AssistantMessage caller = (AssistantMessage) compacted.get(i - 1);
                assertEquals(caller.getToolCalls().size(), responses.getResponses().size());
                for (int r = 0; r < responses.getResponses().size(); r++) {
                    assertEquals(caller.getToolCalls().get(r).id(),
                            responses.getResponses().get(r).id(),
                            "tool_call_id pairing must survive compaction");
                }
            }
        }
    }

    @Test
    @DisplayName("the system prompt and the rule checklist are never compacted away")
    void systemAndTaskSurvive() {
        List<Message> history = transcript(40);
        List<Message> compacted = new ConversationCompactor(BUDGET, 10).compact(history, 200);

        assertSame(history.get(0), compacted.get(0));
        assertSame(history.get(1), compacted.get(1));
        assertEquals(MessageType.SYSTEM, compacted.get(0).getMessageType());
        assertEquals(MessageType.USER, compacted.get(1).getMessageType());
    }

    @Test
    @DisplayName("the newest exchange is kept verbatim while older results are elided")
    void oldestResultsGoFirst() {
        List<Message> history = transcript(40);
        List<Message> compacted = new ConversationCompactor(BUDGET, 10).compact(history, 200);

        assertEquals(ConversationCompactor.ELIDED_RESULT,
                ((ToolResponseMessage) compacted.get(3)).getResponses().getFirst().responseData());
        Message newest = compacted.getLast();
        assertSame(history.getLast(), newest, "the last message must stay whole");
        assertFalse(((ToolResponseMessage) newest).getResponses().getFirst().responseData()
                .equals(ConversationCompactor.ELIDED_RESULT));
    }

    @Test
    @DisplayName("the estimator is corrected downwards by the server's real token count")
    void calibrationOnlyEverTightens() {
        ConversationCompactor compactor = new ConversationCompactor(BUDGET, 10);
        double initial = compactor.charsPerToken();

        // The server counted a fifth more tokens than were estimated: the ratio must drop.
        compactor.calibrate(1000, 1200);
        double tightened = compactor.charsPerToken();
        assertTrue(tightened < initial, "ratio should have tightened from " + initial);

        // A turn the estimator over-counted must not license a looser estimate again.
        compactor.calibrate(1000, 500);
        assertEquals(tightened, compactor.charsPerToken());

        // Absent or nonsensical accounting is ignored.
        compactor.calibrate(1000, null);
        compactor.calibrate(1000, 0);
        assertEquals(tightened, compactor.charsPerToken());
    }

    @Test
    @DisplayName("a rejected prompt tightens the estimator hard, and compaction follows")
    void tighteningShrinksTheNextEstimate() {
        ConversationCompactor compactor = new ConversationCompactor(BUDGET, 10);
        List<Message> history = transcript(6);
        int before = compactor.estimate(history);

        assertTrue(compactor.tighten());
        assertTrue(compactor.estimate(history) > before,
                "a tighter ratio must make the same transcript look more expensive");
    }

    /** system + task, then {@code turns} of (assistant with one tool call, tool response). */
    private static List<Message> transcript(int turns) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("You are the transaction-monitoring analyst.".repeat(4)));
        history.add(new UserMessage("Assess the financial-crime risk of customer X.".repeat(4)));
        for (int i = 0; i < turns; i++) {
            String id = "call_" + i;
            history.add(AssistantMessage.builder()
                    .content("Checking rule " + i + ". ".repeat(20))
                    .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function",
                            "get_transaction_details", "{\"transaction_id\":\"tx-" + i + "\"}")))
                    .build());
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(id,
                            "get_transaction_details",
                            "{\"amount\":9500,\"matched\":[" + "\"tx\",".repeat(60) + "\"tx\"]}")))
                    .build());
        }
        return history;
    }
}
