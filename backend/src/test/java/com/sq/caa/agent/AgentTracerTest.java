package com.sq.caa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file behaviour of {@link AgentTracer}: enable starts a fresh file every time, disable stops
 * writing but keeps the file, the incremental content read handles restart and the size cap, and a
 * write failure is swallowed rather than breaking an analysis.
 */
class AgentTracerTest {

    @TempDir
    Path dir;

    private AgentTracer tracer;

    @BeforeEach
    void setUp() {
        tracer = new AgentTracer(dir.toString());
    }

    @Test
    @DisplayName("Never enabled: state is off with no file and content is empty")
    void neverEnabled() {
        AgentTracer.State state = tracer.state();
        assertThat(state.enabled()).isFalse();
        assertThat(state.fileName()).isNull();
        assertThat(state.startedAt()).isNull();
        assertThat(state.sizeBytes()).isZero();
        assertThat(tracer.currentFile()).isNull();
        assertThat(tracer.content(0).content()).isEmpty();
    }

    @Test
    @DisplayName("Writes while disabled are no-ops and create nothing")
    void disabledMeansNoWrites() {
        tracer.runStarted("id-1", "Ada", "model-x", 3);
        tracer.assistant("id-1", "thinking");
        tracer.toolCall("id-1", "evaluate_rule", "{}", "result", 12);
        tracer.finalAssessment("id-1", "LOW", "LOW", null, "0.00", "s", "r", true);
        assertThat(dir.toFile().listFiles()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Enable creates a timestamped file and records every section kind")
    void enableAndWrite() throws Exception {
        AgentTracer.State state = tracer.enable();
        assertThat(state.enabled()).isTrue();
        assertThat(state.fileName()).startsWith("agent-trace-").endsWith(".log");
        assertThat(state.startedAt()).isNotNull();

        tracer.runStarted("id-1", "Ada", "model-x", 2);
        tracer.assistant("id-1", "I will judge the structuring rule first.");
        tracer.toolCall("id-1", "evaluate_rule", "{\n  \"sql\" : \"SELECT ...\"\n}",
                "FULL RESULT, NOT TRUNCATED", 42);
        tracer.note("id-1", "COVERAGE REPROMPT", "1 rule(s) still have no verdict");
        tracer.finalAssessment("id-1", "HIGH", "MEDIUM", "justified", "7.50", "the summary",
                "the recommendations", true);

        Path file = dir.resolve(state.fileName());
        String text = Files.readString(file);
        assertThat(text).contains("AGENT TRACE SESSION STARTED")
                .contains("ANALYSIS RUN STARTED", "customer:   Ada", "model:      model-x")
                .contains("ASSISTANT", "I will judge the structuring rule first.")
                .contains("TOOL CALL: evaluate_rule", "SELECT ...", "FULL RESULT, NOT TRUNCATED",
                        "duration: 42 ms")
                .contains("COVERAGE REPROMPT")
                .contains("FINAL ASSESSMENT", "risk level:            HIGH",
                        "mechanical (from SQL): MEDIUM", "escalation:", "the summary",
                        "the recommendations");
        assertThat(tracer.state().sizeBytes()).isEqualTo(Files.size(file));
    }

    @Test
    @DisplayName("Re-enabling restarts a fresh file; the previous one is kept untouched")
    void reEnableRestarts() throws Exception {
        AgentTracer.State first = tracer.enable();
        tracer.assistant("id-1", "session one content");
        Path firstFile = dir.resolve(first.fileName());
        long firstSize = Files.size(firstFile);

        AgentTracer.State second = tracer.enable();
        assertThat(second.fileName()).isNotEqualTo(first.fileName());
        Path secondFile = dir.resolve(second.fileName());

        String fresh = Files.readString(secondFile);
        assertThat(fresh).contains("AGENT TRACE SESSION STARTED")
                .doesNotContain("session one content");
        assertThat(Files.size(firstFile)).isEqualTo(firstSize);
        assertThat(Files.readString(firstFile)).contains("session one content");
    }

    @Test
    @DisplayName("Disable stops writing but keeps the file visible and downloadable")
    void disableStopsWriting() throws Exception {
        AgentTracer.State enabled = tracer.enable();
        tracer.assistant("id-1", "before disable");

        AgentTracer.State disabled = tracer.disable();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.fileName()).isEqualTo(enabled.fileName());
        assertThat(disabled.startedAt()).isEqualTo(enabled.startedAt());

        long size = Files.size(dir.resolve(enabled.fileName()));
        tracer.assistant("id-1", "after disable - must not land");
        assertThat(Files.size(dir.resolve(enabled.fileName()))).isEqualTo(size);
        assertThat(tracer.currentFile()).isNotNull();
        assertThat(tracer.content(0).content()).contains("before disable")
                .doesNotContain("after disable");
    }

    @Test
    @DisplayName("Content reads from the byte offset for incremental polling")
    void contentFromOffset() {
        tracer.enable();
        tracer.assistant("id-1", "hello agent trace");
        AgentTracer.Content full = tracer.content(0);
        assertThat(full.fromOffset()).isZero();
        long mid = full.sizeBytes() / 2;
        AgentTracer.Content tail = tracer.content(mid);
        assertThat(tail.fromOffset()).isEqualTo(mid);
        assertThat(tail.content()).isEqualTo(full.content().substring((int) mid));
        assertThat(tail.sizeBytes()).isEqualTo(full.sizeBytes());
    }

    @Test
    @DisplayName("An offset past the end (restarted, shorter file) resets to 0")
    void contentResetsWhenFileShrank() {
        tracer.enable();
        tracer.assistant("id-1", "short");
        long beyond = tracer.state().sizeBytes() + 10_000;
        AgentTracer.Content content = tracer.content(beyond);
        assertThat(content.fromOffset()).isZero();
        assertThat(content.content()).contains("short");
    }

    @Test
    @DisplayName("A single response is capped at 256 KB and returns the tail")
    void contentCapsAt256Kb() {
        tracer.enable();
        String marker = "HEAD-MARKER";
        tracer.assistant("id-1", marker);
        tracer.toolCall("id-1", "evaluate_rule", "{}", "x".repeat(600_000), 1);
        long size = tracer.state().sizeBytes();
        assertThat(size).isGreaterThan(AgentTracer.MAX_CONTENT_BYTES);

        AgentTracer.Content content = tracer.content(0);
        assertThat(content.sizeBytes()).isEqualTo(size);
        assertThat(content.fromOffset()).isEqualTo(size - AgentTracer.MAX_CONTENT_BYTES);
        assertThat(content.content().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSize(AgentTracer.MAX_CONTENT_BYTES);
        assertThat(content.content()).doesNotContain(marker);
    }

    @Test
    @DisplayName("A vanished file or unwritable directory never throws out of the write path")
    void writeFailuresAreSwallowed() throws Exception {
        tracer.enable();
        Path file = tracer.currentFile();
        Files.delete(file);
        assertThatCode(() -> tracer.assistant("id-1", "into the void")).doesNotThrowAnyException();

        // A directory that cannot be created: enable reports the failure as state, not an exception.
        Path blocked = Files.createFile(dir.resolve("blocked"));
        AgentTracer broken = new AgentTracer(blocked.resolve("sub").toString());
        assertThatCode(broken::enable).doesNotThrowAnyException();
        assertThat(broken.state().enabled()).isFalse();
    }

    @Test
    @DisplayName("Files accumulate as history; the newest is the active one")
    void historyAccumulates() throws Exception {
        tracer.enable();
        tracer.enable();
        tracer.enable();
        try (var stream = Files.list(dir)) {
            assertThat(stream.map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder()).toList())
                    .hasSize(3)
                    .allMatch(name -> name.startsWith("agent-trace-"));
        }
    }
}
