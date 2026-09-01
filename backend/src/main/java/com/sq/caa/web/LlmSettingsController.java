package com.sq.caa.web;

import com.sq.caa.llm.LlmEndpointException;
import com.sq.caa.llm.LlmSettingsException;
import com.sq.caa.llm.MutableLlmSettingsService;
import com.sq.caa.llm.ReembedConfirmationRequiredException;
import com.sq.caa.llm.ReembedService;
import com.sq.caa.security.SecurityRoles;
import com.sq.caa.security.SecurityUtils;
import com.sq.caa.web.dto.LlmSettingsDtos.LlmSettingsDto;
import com.sq.caa.web.dto.LlmSettingsDtos.LlmSettingsSavedDto;
import com.sq.caa.web.dto.LlmSettingsDtos.LlmSettingsUpdateRequest;
import com.sq.caa.web.dto.LlmSettingsDtos.LlmTestRequest;
import com.sq.caa.web.dto.LlmSettingsDtos.LlmTestResponse;
import com.sq.caa.web.dto.LlmSettingsDtos.ModelsResponse;
import com.sq.caa.web.dto.LlmSettingsDtos.ReembedStatusDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime administration of the LLM endpoint configuration.
 *
 * <p>Everything here is admin-only - these endpoints spend model calls and can re-point every
 * analysis - enforced both here and in the URL security rules. The settings are read and written
 * through {@link MutableLlmSettingsService}; saving an embedding-model change also starts the
 * background re-embed job owned by {@link ReembedService}.
 *
 * <p>The API keys are write-only: request bodies may carry them, no response ever does.
 */
@RestController
@RequestMapping("/api/admin/llm-settings")
@PreAuthorize(SecurityRoles.IS_ADMIN)
public class LlmSettingsController {

    private static final Logger log = LoggerFactory.getLogger(LlmSettingsController.class);

    private final MutableLlmSettingsService settingsService;
    private final ReembedService reembedService;

    public LlmSettingsController(MutableLlmSettingsService settingsService,
            ReembedService reembedService) {
        this.settingsService = settingsService;
        this.reembedService = reembedService;
    }

    /** The settings currently in effect - the database row, or the environment defaults. */
    @GetMapping
    public LlmSettingsDto get() {
        return LlmSettingsDto.from(settingsService.effective());
    }

    /**
     * Saves new settings; subsequent chat and embedding calls use them without a restart.
     *
     * <p>An embedding-model change needs {@code confirmReembed: true}; saved with it, this also
     * kicks off the background re-embed of the knowledge base and reports whether it started.
     */
    @PutMapping
    public LlmSettingsSavedDto update(@Valid @RequestBody LlmSettingsUpdateRequest request) {
        MutableLlmSettingsService.UpdateOutcome outcome = settingsService.update(
                request.toCommand(), SecurityUtils.currentUsernameOrSystem());
        boolean reembedStarted = false;
        if (outcome.embeddingModelChanged()) {
            reembedStarted = reembedService.start();
            if (!reembedStarted) {
                log.warn("Embedding model changed but a re-embed job is already running");
            }
        }
        return LlmSettingsSavedDto.from(outcome.settings(), reembedStarted);
    }

    /** The model ids a candidate endpoint advertises, via its OpenAI-standard {@code GET /models}. */
    @GetMapping("/models")
    public ModelsResponse models(@RequestParam String baseUrl,
            @RequestParam(required = false) String apiKey) {
        List<String> models = settingsService.listModels(baseUrl, apiKey);
        return new ModelsResponse(models);
    }

    /** Live-probes a candidate configuration - the two chat roles and the embedding. Saves nothing. */
    @PostMapping("/test")
    public LlmTestResponse test(@Valid @RequestBody LlmTestRequest request) {
        return LlmTestResponse.from(settingsService.testConnection(
                request.baseUrl(), request.chatModel(), request.toolModel(), request.embedModel(),
                request.chatApiKey(), request.embedApiKey()));
    }

    /** The progress of the background re-embed job. */
    @GetMapping("/reembed-status")
    public ReembedStatusDto reembedStatus() {
        return ReembedStatusDto.from(reembedService.status());
    }

    // ------------------------------------------------------------------
    // Problem responses
    // ------------------------------------------------------------------

    /** An embedding-model change the caller has not confirmed. */
    @ExceptionHandler(ReembedConfirmationRequiredException.class)
    public ResponseEntity<ProblemDetail> onReembedNotConfirmed(ReembedConfirmationRequiredException e,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "Embedding model change requires re-embedding", e.getMessage(), request);
        return respond(problem);
    }

    /** The configured or candidate endpoint is down or answered badly. */
    @ExceptionHandler(LlmEndpointException.class)
    public ResponseEntity<ProblemDetail> onEndpointFailure(LlmEndpointException e,
            HttpServletRequest request) {
        log.warn("LLM endpoint failure on {}: {}", request.getRequestURI(), e.getMessage());
        return respond(problem(HttpStatus.BAD_GATEWAY, "LLM endpoint unavailable",
                e.getMessage(), request));
    }

    /** The settings could not be applied (e.g. the embedding-dimension DDL was refused). */
    @ExceptionHandler(LlmSettingsException.class)
    public ResponseEntity<ProblemDetail> onSettingsFailure(LlmSettingsException e,
            HttpServletRequest request) {
        log.error("Applying LLM settings failed on {}", request.getRequestURI(), e);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "LLM settings could not be applied",
                e.getMessage(), request));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        if (request != null && request.getRequestURI() != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
