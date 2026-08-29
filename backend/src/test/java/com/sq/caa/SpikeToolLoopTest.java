package com.sq.caa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(properties = {
        "spring.ai.openai.base-url=http://localhost:13305/api/v1",
        "spring.ai.openai.api-key=none",
        "spring.ai.openai.chat.options.model=gpt-oss-120b-GGUF",
        "spring.ai.openai.chat.options.temperature=0.1",
        "spring.ai.openai.embedding.options.model=Qwen3-Embedding-4B-GGUF",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
@Tag("live")
class SpikeToolLoopTest {

    static class Tools {
        int calls = 0;
        @Tool(name = "get_transactions", description = "Fetch the transactions of a customer by customer id")
        String getTransactions(@ToolParam(description = "customer id") String customerId) {
            calls++;
            return "[{\"amount\": 91000, \"currency\": \"USD\", \"type\": \"CRYPTO\"}]";
        }
    }

    @Autowired ChatModel chatModel;
    @Autowired ToolCallingManager toolCallingManager;

    @Test
    void manualReActLoopWorks() {
        Tools tools = new Tools();
        var options = OpenAiChatOptions.builder()
                .toolCallbacks(List.of(ToolCallbacks.from(tools)))
                .model("gpt-oss-120b-GGUF")
                .maxTokens(2048)
                .build();

        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("What transactions does customer C-42 have? Use tools, then state the amount."));

        int steps = 0;
        String finalText = null;
        while (steps++ < 5) {
            Prompt prompt = new Prompt(history, options);
            ChatResponse resp = chatModel.call(prompt);
            System.out.println(">>> STEP " + steps + " hasToolCalls=" + resp.hasToolCalls()
                    + " toolInvocationsSoFar=" + tools.calls
                    + " text=" + resp.getResult().getOutput().getText());
            if (resp.hasToolCalls()) {
                ToolExecutionResult ter = toolCallingManager.executeToolCalls(prompt, resp);
                history = new ArrayList<>(ter.conversationHistory());
            } else {
                finalText = resp.getResult().getOutput().getText();
                break;
            }
        }
        System.out.println(">>> SPIKE RESULT toolCalls=" + tools.calls + " final=" + finalText);
        if (tools.calls == 0) throw new AssertionError("tool was never invoked");
        if (finalText == null || !finalText.contains("91")) throw new AssertionError("model did not use tool output: " + finalText);
        System.out.println(">>> SPIKE PASSED: manual ReAct loop with ToolCallingManager works");
    }
}
