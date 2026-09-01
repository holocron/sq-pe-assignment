package com.sq.caa.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The admin-controlled verbose agent trace, written to a plain file next to the application logs.
 *
 * <p>The per-run transcript ({@link AnalysisTrace}, persisted in {@code analysis_runs.trace}) is
 * built for the UI: previews are truncated, arguments are condensed, and it is scoped to one run.
 * When an administrator needs the unabridged reasoning - every assistant message, every tool call
 * with its full arguments and its <em>full, untruncated</em> result, and the final assessment -
 * this tracer writes it to {@code agent-trace-<yyyyMMdd-HHmmss>.log} under the configured
 * directory ({@code caa.agent.trace.dir}, default {@code ../logs}, where the run scripts already
 * put the backend log).
 *
 * <p>Enabling a trace is always a <b>new session</b>: {@link #enable()} creates a fresh timestamped
 * file, even when tracing was already on, so a previous session's file is never appended to and
 * survives as history. {@link #disable()} only stops writing; the file stays for viewing and
 * download.
 *
 * <p>The state is deliberately in-memory: it does not survive an application restart (a prototype
 * decision - tracing is a debugging session, not configuration). Enabling is admin-only via the
 * controller, and the write path can never break an analysis: every failure is logged and
 * swallowed.
 */
@Component
public class AgentTracer {

    private static final Logger log = LoggerFactory.getLogger(AgentTracer.class);

    /** Upper bound of one {@code content} response; the tail is returned when the file is larger. */
    public static final int MAX_CONTENT_BYTES = 256 * 1024;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Path directory;
    private final Object lock = new Object();

    /** Non-null while tracing is on. */
    private volatile Session session;

    /** Last session started, kept after disable so the file stays viewable/downloadable. */
    private volatile Session lastSession;

    public AgentTracer(@Value("${caa.agent.trace.dir:../logs}") String directory) {
        this.directory = Path.of(directory == null || directory.isBlank() ? "../logs" : directory);
    }

    /** A tracer that never writes unless enabled; for tests that drive the loop without a file. */
    public static AgentTracer noop() {
        return new AgentTracer(System.getProperty("java.io.tmpdir", "."));
    }

    public boolean isEnabled() {
        return session != null;
    }

    /** A snapshot of the on/off switch and the current (or last) trace file. */
    public State state() {
        Session active = session;
        Session shown = active != null ? active : lastSession;
        if (shown == null) {
            return new State(false, null, null, 0);
        }
        return new State(active != null, shown.file().getFileName().toString(), shown.startedAt(),
                sizeOf(shown.file()));
    }

    /**
     * Starts a fresh trace session: a new timestamped file, never an append to the previous one.
     *
     * @return the state after enabling
     */
    public State enable() {
        synchronized (lock) {
            try {
                Files.createDirectories(directory);
                Path file = freshFile();
                Files.write(file, sessionHeader().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW);
                Session started = new Session(file, Instant.now());
                session = started;
                lastSession = started;
                log.info("Agent trace enabled; writing to {}", file.toAbsolutePath());
            } catch (IOException e) {
                // A trace that cannot start is reported but must not take the admin endpoint down
                // with an obscure I/O error; the state simply stays off.
                session = null;
                log.error("Could not start the agent trace in {}", directory.toAbsolutePath(), e);
            }
            return state();
        }
    }

    /** Stops writing; the file is kept for viewing and download. */
    public State disable() {
        Session stopped = session;
        session = null;
        if (stopped != null) {
            append(stopped, "=== AGENT TRACE DISABLED at " + Instant.now() + " ===\n");
            log.info("Agent trace disabled; {} is kept for download", stopped.file().getFileName());
        }
        return state();
    }

    // ------------------------------------------------------------------
    // Recording (called from the agent loop; all no-ops while disabled)
    // ------------------------------------------------------------------

    /** Header of one analysis run. */
    public void runStarted(Object assessmentId, String customerName, String model, int ruleCount) {
        section(assessmentId, "ANALYSIS RUN STARTED", "assessment: " + assessmentId + "\n"
                + "customer:   " + customerName + "\n"
                + "model:      " + model + "\n"
                + "rules:      " + ruleCount + " applicable rule(s) to judge");
    }

    /** One assistant message - the model's reasoning, in full. */
    public void assistant(Object assessmentId, String text) {
        section(assessmentId, "ASSISTANT", text);
    }

    /**
     * One executed tool call with its full arguments and its full, untruncated result - including
     * the SQL the agent submitted through {@code evaluate_rule} and the verdict it got back.
     */
    public void toolCall(Object assessmentId, String tool, String prettyArguments,
            String fullResult, long ms) {
        toolCall(assessmentId, tool, null, prettyArguments, fullResult, ms);
    }

    /**
     * The same, attributed to the rule whose subagent made the call. The section title names the
     * rule so a run fanned out over concurrent subagents still reads as one thread per rule.
     */
    public void toolCall(Object assessmentId, String tool, String ruleName, String prettyArguments,
            String fullResult, long ms) {
        section(assessmentId, "TOOL CALL: " + tool + (ruleName == null ? "" : "  [rule: " + ruleName + "]"),
                "arguments:\n" + orEmpty(prettyArguments) + "\n\nduration: " + ms + " ms\n"
                        + "result:\n" + orEmpty(fullResult));
    }

    /** A rule subagent started its mini-loop. */
    public void subagentStarted(Object assessmentId, String ruleName, int worker, int attempt) {
        section(assessmentId, "SUBAGENT STARTED  [worker " + worker + "]",
                "rule:    " + ruleName + "\n"
                        + "worker:  " + worker + "\n"
                        + "attempt: " + attempt + (attempt > 1 ? " (retry after a failed first "
                        + "subagent; fresh conversation)" : ""));
    }

    /** A rule subagent finished - with its verdict, or failed having never submitted one. */
    public void subagentEnded(Object assessmentId, String ruleName, int worker, int attempt,
            String verdict, String score, int stepsUsed, long durationMs) {
        section(assessmentId, "SUBAGENT " + ("failed".equals(verdict) ? "FAILED" : "DONE")
                        + "  [worker " + worker + "]",
                "rule:     " + ruleName + "\n"
                        + "worker:   " + worker + "\n"
                        + "attempt:  " + attempt + "\n"
                        + "verdict:  " + verdict + "\n"
                        + (score == null ? "" : "score:    " + score + "\n")
                        + "steps:    " + stepsUsed + "\n"
                        + "duration: " + durationMs + " ms");
    }

    /** A loop event worth seeing in the trace: coverage reprompt, compaction note, cancellation. */
    public void note(Object assessmentId, String kind, String text) {
        section(assessmentId, kind, text);
    }

    /** The settled final assessment of a run. */
    public void finalAssessment(Object assessmentId, String riskLevel, String mechanicalRiskLevel,
            String escalationJustification, String totalScore, String summary,
            String recommendations, boolean coverageComplete) {
        StringBuilder body = new StringBuilder(1024);
        body.append("risk level:            ").append(riskLevel).append('\n');
        body.append("mechanical (from SQL): ").append(mechanicalRiskLevel).append('\n');
        if (escalationJustification != null) {
            body.append("escalation:            ").append(escalationJustification).append('\n');
        }
        body.append("total score:           ").append(totalScore).append('\n');
        body.append("coverage complete:     ").append(coverageComplete).append("\n\n");
        body.append("SUMMARY\n").append(orEmpty(summary)).append("\n\n");
        body.append("RECOMMENDATIONS\n").append(orEmpty(recommendations));
        section(assessmentId, "FINAL ASSESSMENT", body.toString());
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * The trace file from byte {@code offset} on, for incremental polling.
     *
     * <p>If the offset is past the end - the file was restarted and shrank - the read restarts
     * from 0. One response carries at most {@link #MAX_CONTENT_BYTES}; a larger remainder returns
     * the tail with {@code fromOffset} adjusted accordingly.
     */
    public Content content(long offset) {
        Session shown = session != null ? session : lastSession;
        if (shown == null) {
            return new Content("", 0, 0);
        }
        Path file = shown.file();
        try {
            long size = sizeOf(file);
            long from = Math.max(0, offset);
            if (from > size) {
                // Restarted file is shorter than the client's cursor: replay from the beginning.
                from = 0;
            }
            if (size - from > MAX_CONTENT_BYTES) {
                from = size - MAX_CONTENT_BYTES;
            }
            try (var channel = Files.newByteChannel(file)) {
                channel.position(from);
                var buffer = java.nio.ByteBuffer.allocate((int) (size - from));
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // read fully
                }
                return new Content(new String(buffer.array(), StandardCharsets.UTF_8), size, from);
            }
        } catch (IOException e) {
            log.warn("Could not read the agent trace file {}", file.toAbsolutePath(), e);
            return new Content("", sizeOf(file), 0);
        }
    }

    /** The current (or last) trace file, or {@code null} when tracing was never enabled. */
    public Path currentFile() {
        Session shown = session != null ? session : lastSession;
        return shown == null ? null : shown.file();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void section(Object assessmentId, String title, String body) {
        Session active = session;
        if (active == null) {
            return;
        }
        append(active, "\n---- " + title + "  [run " + assessmentId + ", " + Instant.now()
                + "] ----\n" + orEmpty(body) + "\n");
    }

    /** Appends to the active file. A failure is logged, never propagated: tracing must not break an analysis. */
    private void append(Session target, String text) {
        synchronized (lock) {
            try {
                Files.write(target.file(), text.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
            } catch (IOException | UncheckedIOException e) {
                log.warn("Could not write to the agent trace file {}; tracing continues silently",
                        target.file().toAbsolutePath(), e);
            }
        }
    }

    /** A fresh timestamped file name; a same-second re-enable gets a numeric suffix. */
    private Path freshFile() {
        String stamp = FILE_STAMP.format(Instant.now());
        Path file = directory.resolve("agent-trace-" + stamp + ".log");
        for (int suffix = 2; Files.exists(file); suffix++) {
            file = directory.resolve("agent-trace-" + stamp + "-" + suffix + ".log");
        }
        return file;
    }

    private static String sessionHeader() {
        return "=== AGENT TRACE SESSION STARTED at " + Instant.now() + " (UTC) ===\n"
                + "Verbose trace of the ReAct risk agent: assistant reasoning, full tool calls\n"
                + "and results (no truncation), coverage events, and the final assessment.\n";
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** One tracing session: the file being written and when it started. */
    private record Session(Path file, Instant startedAt) {
    }

    /** The public state shape of {@code GET/POST /api/admin/agent-trace}. */
    public record State(boolean enabled, String fileName, Instant startedAt, long sizeBytes) {
    }

    /** The public shape of {@code GET /api/admin/agent-trace/content}. */
    public record Content(String content, long sizeBytes, long fromOffset) {
    }
}
