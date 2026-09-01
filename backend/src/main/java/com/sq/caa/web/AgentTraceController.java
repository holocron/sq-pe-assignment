package com.sq.caa.web;

import com.sq.caa.agent.AgentTracer;
import com.sq.caa.security.SecurityRoles;
import com.sq.caa.web.dto.AgentTraceDtos.AgentTraceContentDto;
import com.sq.caa.web.dto.AgentTraceDtos.AgentTraceStateDto;
import com.sq.caa.web.dto.AgentTraceDtos.AgentTraceUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration of the verbose agent trace file ({@link AgentTracer}).
 *
 * <p>Admin-only, as everything under {@code /api/admin/**}. Enabling always starts a fresh,
 * timestamped file; disabling stops writing but keeps the file so it can still be polled and
 * downloaded. The content endpoint takes a byte offset for incremental polling and caps one
 * response at 256 KB.
 */
@RestController
@RequestMapping("/api/admin/agent-trace")
@PreAuthorize(SecurityRoles.IS_ADMIN)
public class AgentTraceController {

    private final AgentTracer tracer;

    public AgentTraceController(AgentTracer tracer) {
        this.tracer = tracer;
    }

    /** The on/off switch and the current (or last) trace file. */
    @GetMapping
    public AgentTraceStateDto state() {
        return AgentTraceStateDto.from(tracer.state());
    }

    /** Flips the switch. Enabling - even when already enabled - starts a new, empty trace file. */
    @PostMapping
    public AgentTraceStateDto update(@RequestBody AgentTraceUpdateRequest request) {
        return AgentTraceStateDto.from(
                request.enabled() ? tracer.enable() : tracer.disable());
    }

    /** The trace file from byte {@code offset} on; restarts from 0 when the file shrank. */
    @GetMapping("/content")
    public AgentTraceContentDto content(@RequestParam(defaultValue = "0") long offset) {
        return AgentTraceContentDto.from(tracer.content(offset));
    }

    /** The whole trace file as a {@code text/plain} attachment. */
    @GetMapping("/download")
    public ResponseEntity<?> download(HttpServletRequest request) {
        Path file = tracer.currentFile();
        if (file == null || !Files.isRegularFile(file)) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
            problem.setTitle("No agent trace file");
            problem.setDetail("Tracing has never been enabled on this instance, or the file is gone. "
                    + "Enable the trace first with POST /api/admin/agent-trace.");
            if (request != null && request.getRequestURI() != null) {
                problem.setInstance(URI.create(request.getRequestURI()));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .body(resource);
    }
}
