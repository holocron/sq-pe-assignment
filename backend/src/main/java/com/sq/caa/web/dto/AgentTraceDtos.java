package com.sq.caa.web.dto;

import com.sq.caa.agent.AgentTracer;
import java.time.Instant;

/**
 * The request and response shapes of {@code /api/admin/agent-trace}.
 */
public final class AgentTraceDtos {

    private AgentTraceDtos() {
    }

    /**
     * The on/off switch and the current (or last) trace file. {@code fileName} and
     * {@code startedAt} are null until tracing has been enabled at least once; after a disable they
     * keep pointing at the file that stopped being written, so it stays viewable and downloadable.
     */
    public record AgentTraceStateDto(boolean enabled, String fileName, Instant startedAt,
            long sizeBytes) {

        public static AgentTraceStateDto from(AgentTracer.State state) {
            return new AgentTraceStateDto(state.enabled(), state.fileName(), state.startedAt(),
                    state.sizeBytes());
        }
    }

    /** The POST body: {@code {"enabled": true}} starts a fresh trace file, false stops writing. */
    public record AgentTraceUpdateRequest(boolean enabled) {
    }

    /**
     * One incremental read of the trace file. {@code fromOffset} echoes where the content actually
     * starts: the requested offset, 0 when the file was restarted and shrank below it, or the tail
     * boundary when the remainder exceeded the response cap.
     */
    public record AgentTraceContentDto(String content, long sizeBytes, long fromOffset) {

        public static AgentTraceContentDto from(AgentTracer.Content content) {
            return new AgentTraceContentDto(content.content(), content.sizeBytes(),
                    content.fromOffset());
        }
    }
}
