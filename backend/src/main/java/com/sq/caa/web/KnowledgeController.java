package com.sq.caa.web;

import com.sq.caa.rag.DocumentExtractionException;
import com.sq.caa.rag.DuplicateDocumentException;
import com.sq.caa.rag.KnowledgeDocumentNotFoundException;
import com.sq.caa.rag.KnowledgeFormat;
import com.sq.caa.rag.KnowledgeIndexException;
import com.sq.caa.rag.RagService;
import com.sq.caa.rag.RetrievedChunk;
import com.sq.caa.rag.UnsupportedDocumentException;
import com.sq.caa.security.SecurityRoles;
import com.sq.caa.security.SecurityUtils;
import com.sq.caa.web.dto.KnowledgeDtos.KnowledgeChunkDto;
import com.sq.caa.web.dto.KnowledgeDtos.KnowledgeDocumentDto;
import com.sq.caa.web.dto.KnowledgeDtos.KnowledgeSearchRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The knowledge base behind the RAG tooling.
 *
 * <p>Administrators curate the corpus - only {@code .docx} and {@code .pdf} policy documents are
 * accepted, and the check is on the file's actual bytes, not its extension. Operators cannot change
 * it but can search it, which is the same retrieval the risk agent uses when it cites policy.
 *
 * <p>Ingestion failures are translated here into RFC-7807 responses that say what was wrong with
 * the file, because "unsupported media type" tells an administrator nothing about the spreadsheet
 * they renamed to {@code .pdf}.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final RagService ragService;

    public KnowledgeController(RagService ragService) {
        this.ragService = ragService;
    }

    /** Every uploaded document, newest first. */
    @GetMapping("/documents")
    @PreAuthorize(SecurityRoles.IS_OPERATOR_OR_ADMIN)
    public List<KnowledgeDocumentDto> listDocuments() {
        return ragService.listDocuments().stream().map(KnowledgeDocumentDto::from).toList();
    }

    /** One document by id. */
    @GetMapping("/documents/{documentId}")
    @PreAuthorize(SecurityRoles.IS_OPERATOR_OR_ADMIN)
    public KnowledgeDocumentDto getDocument(@PathVariable UUID documentId) {
        return KnowledgeDocumentDto.from(ragService.getDocument(documentId));
    }

    /**
     * Uploads a policy document and indexes it.
     *
     * <p>Synchronous on purpose: the response carries the finished document with the number of
     * chunks it produced, so the admin screen can show the result rather than a spinner.
     */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocumentDto upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedDocumentException(file == null ? null : file.getOriginalFilename(),
                    "empty file", "The uploaded file is empty.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new KnowledgeIndexException(
                    "The upload could not be read from the request: " + e.getMessage(), e);
        }
        String uploadedBy = SecurityUtils.currentUsernameOrSystem();
        log.info("Uploading knowledge document '{}' ({} bytes) as {}", file.getOriginalFilename(),
                content.length, uploadedBy);
        return KnowledgeDocumentDto.from(
                ragService.ingest(file.getOriginalFilename(), content, uploadedBy));
    }

    /** Deletes a document and its chunks from the vector store. */
    @DeleteMapping("/documents/{documentId}")
    @PreAuthorize(SecurityRoles.IS_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID documentId) {
        ragService.delete(documentId);
    }

    /** Vector similarity search over the corpus. */
    @PostMapping("/search")
    @PreAuthorize(SecurityRoles.IS_OPERATOR_OR_ADMIN)
    public List<KnowledgeChunkDto> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        List<RetrievedChunk> hits = ragService.search(request.query(), request.topKOrDefault());
        return hits.stream().map(KnowledgeChunkDto::from).toList();
    }

    // ------------------------------------------------------------------
    // Problem responses
    // ------------------------------------------------------------------

    /**
     * The bytes are not a Word or PDF document. {@code detected} names what they actually were, so
     * the administrator can see that their {@code .pdf} is really a spreadsheet.
     */
    @ExceptionHandler(UnsupportedDocumentException.class)
    public ResponseEntity<ProblemDetail> onUnsupported(UnsupportedDocumentException e,
            HttpServletRequest request) {
        log.info("Rejected upload '{}': {}", e.filename(), e.getMessage());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Unsupported document",
                e.getMessage(), request);
        problem.setProperty("filename", e.filename());
        problem.setProperty("detected", e.detected());
        problem.setProperty("accepted", List.of(KnowledgeFormat.DOCX.extension(),
                KnowledgeFormat.PDF.extension()));
        return respond(problem);
    }

    /** A real .docx or .pdf that could not be read - corrupt, protected or a scan. */
    @ExceptionHandler(DocumentExtractionException.class)
    public ResponseEntity<ProblemDetail> onUnreadable(DocumentExtractionException e,
            HttpServletRequest request) {
        log.info("Could not extract text from '{}': {}", e.filename(), e.getMessage());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Unreadable document",
                e.getMessage(), request);
        problem.setProperty("filename", e.filename());
        return respond(problem);
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<ProblemDetail> onDuplicate(DuplicateDocumentException e,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Document already indexed",
                e.getMessage(), request);
        problem.setProperty("filename", e.filename());
        problem.setProperty("existingDocumentId", String.valueOf(e.existingDocumentId()));
        return respond(problem);
    }

    @ExceptionHandler(KnowledgeDocumentNotFoundException.class)
    public ResponseEntity<ProblemDetail> onMissing(KnowledgeDocumentNotFoundException e,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Document not found", e.getMessage(),
                request);
        problem.setProperty("documentId", String.valueOf(e.documentId()));
        return respond(problem);
    }

    /** The embedding model or the vector store is down: the caller may retry. */
    @ExceptionHandler(KnowledgeIndexException.class)
    public ResponseEntity<ProblemDetail> onIndexFailure(KnowledgeIndexException e,
            HttpServletRequest request) {
        log.error("Knowledge base unavailable on {}", request.getRequestURI(), e);
        return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, "Knowledge base unavailable",
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
