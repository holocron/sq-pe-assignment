package com.sq.caa.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sq.caa.agent.AgentTracer;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The web contract of {@code /api/admin/agent-trace}: the state shape the frontend codes against,
 * enable/restart/disable transitions, the incremental content read and the download.
 *
 * <p>A real {@link AgentTracer} on a temp directory backs the controller - the API and the file
 * behaviour are tested together, as in {@link LlmSettingsApiTest} only the advice is mocked away.
 */
class AgentTraceApiTest {

    @TempDir
    Path dir;

    private AgentTracer tracer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tracer = new AgentTracer(dir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentTraceController(tracer)).build();
    }

    @Test
    @DisplayName("GET before any enable: disabled, no file")
    void initialState() throws Exception {
        mockMvc.perform(get("/api/admin/agent-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.fileName").doesNotExist())
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(jsonPath("$.sizeBytes").value(0));
    }

    @Test
    @DisplayName("POST enable starts a session; re-POST restarts it on a new file")
    void enableThenRestart() throws Exception {
        mockMvc.perform(post("/api/admin/agent-trace")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.fileName").exists())
                .andExpect(jsonPath("$.startedAt").exists());
        String first = tracer.state().fileName();

        mockMvc.perform(post("/api/admin/agent-trace")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        org.assertj.core.api.Assertions.assertThat(tracer.state().fileName()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("POST disable keeps the file coordinates in the state")
    void disableKeepsFile() throws Exception {
        tracer.enable();
        mockMvc.perform(post("/api/admin/agent-trace")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.fileName").exists())
                .andExpect(jsonPath("$.startedAt").exists());
    }

    @Test
    @DisplayName("GET content honours the offset and reports where it read from")
    void contentEndpoint() throws Exception {
        tracer.enable();
        tracer.assistant("id-1", "some reasoning");
        long size = tracer.state().sizeBytes();

        mockMvc.perform(get("/api/admin/agent-trace/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromOffset").value(0))
                .andExpect(jsonPath("$.sizeBytes").value(size))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString(
                        "some reasoning")));

        mockMvc.perform(get("/api/admin/agent-trace/content").param("offset", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromOffset").value(size))
                .andExpect(jsonPath("$.content").value(""));

        mockMvc.perform(get("/api/admin/agent-trace/content")
                        .param("offset", String.valueOf(size + 9999)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromOffset").value(0));
    }

    @Test
    @DisplayName("GET download serves the file as a text/plain attachment")
    void download() throws Exception {
        tracer.enable();
        tracer.assistant("id-1", "download me");
        String fileName = tracer.state().fileName();

        mockMvc.perform(get("/api/admin/agent-trace/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + fileName + "\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("download me")));
    }

    @Test
    @DisplayName("GET download with no trace file: 404 problem+json")
    void downloadMissing() throws Exception {
        mockMvc.perform(get("/api/admin/agent-trace/download"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("No agent trace file"));
    }
}
