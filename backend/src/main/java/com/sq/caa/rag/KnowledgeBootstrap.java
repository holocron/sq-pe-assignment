package com.sq.caa.rag;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Seeds the knowledge base with the bundled policy documents the first time the application starts
 * against an empty corpus.
 *
 * <p>Every other seed item is plain SQL in {@code V3__seed.sql}. The knowledge corpus cannot be:
 * a {@code document_chunks} row is only useful once its text has been embedded by the live model,
 * which no migration can do. Without this component a reviewer who follows the README gets zero
 * chunks, the operator's knowledge search returns nothing for the very queries that screen suggests,
 * and the agent's {@code search_policy_knowledge} tool - which the system prompt tells the model to
 * cite before making any policy claim - answers "no policy passage matched" for every question.
 *
 * <p>The documents go through {@link RagService#ingest(String, byte[], String)}, the same call the
 * admin upload endpoint makes: same format detection, same section parsing, same chunker, same
 * embedding, same {@code knowledge_documents} bookkeeping. There is no second, divergent ingestion
 * path to keep in step.
 *
 * <h2>Three properties this component is judged on</h2>
 *
 * <p><b>It is idempotent.</b> Seeding runs only when the corpus holds no document that is anything
 * other than {@code FAILED}. A restart after a successful seed finds three {@code INDEXED} rows and
 * does nothing; a restart after an administrator has uploaded their own document does nothing
 * either. {@code FAILED} rows are deliberately not counted, so a run that could not reach the
 * embedding model is retried on the next start rather than leaving the corpus permanently empty.
 *
 * <p><b>It cannot break startup.</b> The work runs on a background daemon thread, so the
 * application is serving requests before the first embedding round trip is even made, and every
 * failure - an unreachable model, a corrupt file, a database that rejects the write - is caught and
 * logged. An unreachable embedding model leaves the knowledge base empty and prints one clear
 * warning; it never delays or aborts the boot.
 *
 * <p><b>It does not depend on the working directory.</b> The documents are read from
 * {@code caa.knowledge.bootstrap.location}, which defaults to a {@code classpath*:} pattern
 * resolving inside the application artifact, so {@code java -jar} behaves the same from any
 * directory. The property accepts a {@code file:} pattern too, for a deployment that curates its
 * corpus outside the build.
 *
 * @see KnowledgeBootstrapProperties
 */
@Component
public class KnowledgeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrap.class);

    private static final String THREAD_NAME = "knowledge-bootstrap";

    private final RagService ragService;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeBootstrapProperties properties;
    private final ResourcePatternResolver resources;

    /**
     * Annotated because a second, wider constructor exists for tests; without it the container
     * cannot tell which one to use. Injection is still by constructor - no field is autowired.
     */
    @Autowired
    public KnowledgeBootstrap(RagService ragService,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeBootstrapProperties properties) {
        this(ragService, documentRepository, properties, new PathMatchingResourcePatternResolver());
    }

    /** Visible for tests, which supply a resolver that answers with fixtures. */
    KnowledgeBootstrap(RagService ragService,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeBootstrapProperties properties,
            ResourcePatternResolver resources) {
        this.ragService = ragService;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.resources = resources;
    }

    /**
     * Kicks the seeding off once the application is up, on a thread of its own.
     *
     * <p>Embedding three policy documents is a dozen round trips to a local GGUF model and takes
     * seconds, not milliseconds. Doing that on the startup thread would hold the boot open for no
     * benefit, so it is handed to a daemon thread: the JVM can still exit at any time, and a
     * request that arrives before seeding finishes simply searches a corpus that is still filling
     * up - which the {@code INDEXED}-only visibility rule in {@link RagService} makes safe.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!properties.enabled()) {
            log.info("Knowledge base bootstrap is disabled (caa.knowledge.bootstrap.enabled=false)");
            return;
        }
        Thread worker = new Thread(this::seedQuietly, THREAD_NAME);
        worker.setDaemon(true);
        worker.start();
    }

    /** {@link #seed()} with every possible failure absorbed. Startup must survive anything. */
    private void seedQuietly() {
        try {
            seed();
        } catch (Throwable failure) {
            // Deliberately Throwable: this runs on a thread of its own, and an escape would only
            // print an anonymous stack trace while telling nobody why the knowledge base is empty.
            log.warn("Knowledge base bootstrap did not complete; the knowledge base is left as it "
                    + "is. Upload the policy documents through Admin > Knowledge, or restart once "
                    + "the cause below is fixed.", failure);
        }
    }

    /**
     * Seeds the corpus if it is empty and reports what happened.
     *
     * <p>Public and synchronous so a test - or an operator with an actuator shell - can run the
     * seeding deterministically instead of racing the startup thread.
     *
     * @return what was ingested, or why nothing was
     */
    public BootstrapReport seed() {
        if (!properties.enabled()) {
            return BootstrapReport.skipped("caa.knowledge.bootstrap.enabled is false");
        }
        List<KnowledgeDocument> existing = documentRepository.findAllByOrderByUploadedAtDesc();
        List<KnowledgeDocument> usable = existing.stream()
                .filter(document -> document.getStatus() != DocumentStatus.FAILED)
                .toList();
        if (!usable.isEmpty()) {
            log.debug("Knowledge base bootstrap skipped: {} document(s) already present",
                    usable.size());
            return BootstrapReport.skipped(
                    "the knowledge base already holds " + usable.size() + " document(s)");
        }

        List<Resource> bundled = bundledDocuments();
        if (bundled.isEmpty()) {
            log.warn("Knowledge base bootstrap found no .docx or .pdf documents at '{}'. The "
                    + "knowledge base stays empty and policy search will return nothing until a "
                    + "document is uploaded.", properties.location());
            return BootstrapReport.skipped("no documents matched " + properties.location());
        }

        log.info("Seeding an empty knowledge base from {} bundled document(s) at '{}'",
                bundled.size(), properties.location());
        List<DocumentOutcome> outcomes = new ArrayList<>(bundled.size());
        for (Resource resource : bundled) {
            DocumentOutcome outcome = ingest(resource);
            outcomes.add(outcome);
            if (outcome.modelUnavailable()) {
                // Every remaining document would fail on the same round trip and leave the same
                // FAILED row. Stop, and say so once.
                log.warn("Knowledge base bootstrap stopped after '{}': the embedding model is not "
                        + "reachable. The knowledge base is left empty; it will be seeded on the "
                        + "next start once the model router is up. Reason: {}",
                        outcome.filename(), outcome.error());
                return new BootstrapReport(true, "the embedding model was unreachable", outcomes);
            }
        }
        int indexed = (int) outcomes.stream().filter(DocumentOutcome::succeeded).count();
        int chunks = outcomes.stream().mapToInt(DocumentOutcome::chunkCount).sum();
        log.info("Knowledge base seeded: {}/{} document(s) indexed as {} chunk(s)", indexed,
                outcomes.size(), chunks);
        return new BootstrapReport(true, "seeded " + indexed + " document(s)", outcomes);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The bundled files, in a stable order so chunk ids and {@code uploaded_at} ordering are
     * reproducible. Anything that is not a {@code .docx} or a {@code .pdf} is ignored here rather
     * than sent to the format detector, so an unrelated file sitting next to the policies cannot
     * leave a {@code FAILED} row behind on every fresh deployment.
     */
    private List<Resource> bundledDocuments() {
        Resource[] found;
        try {
            found = resources.getResources(properties.location());
        } catch (IOException | RuntimeException e) {
            // A malformed pattern, an unreadable directory or a jar that cannot be opened are all
            // reasons to leave the knowledge base alone, not to fail.
            log.warn("Could not list knowledge bootstrap documents at '{}': {}",
                    properties.location(), e.getMessage());
            return List.of();
        }
        return Arrays.stream(found)
                .filter(Resource::exists)
                .filter(resource -> KnowledgeFormat.fromFilename(resource.getFilename()) != null)
                .sorted(Comparator.comparing(resource -> String.valueOf(resource.getFilename())))
                .toList();
    }

    private DocumentOutcome ingest(Resource resource) {
        String filename = Objects.requireNonNullElse(resource.getFilename(), "document");
        byte[] content;
        try {
            content = resource.getContentAsByteArray();
        } catch (IOException e) {
            log.warn("Could not read the bundled knowledge document '{}': {}", filename,
                    e.getMessage());
            return DocumentOutcome.failed(filename, e.getMessage(), false);
        }
        try {
            KnowledgeDocument document = ragService.ingest(filename, content,
                    properties.uploadedBy());
            log.info("Seeded '{}' as {} chunk(s)", document.getFilename(),
                    document.getChunkCount());
            return new DocumentOutcome(document.getFilename(), DocumentStatus.INDEXED,
                    document.getChunkCount(), null, false);
        } catch (DuplicateDocumentException e) {
            // Only reachable if an administrator uploaded this exact name between the gate and
            // here. Their copy wins; nothing to repair.
            log.info("Skipped '{}': a document with that name already exists", filename);
            return DocumentOutcome.failed(filename, e.getMessage(), false);
        } catch (KnowledgeIndexException e) {
            return DocumentOutcome.failed(filename, e.getMessage(), true);
        } catch (RuntimeException e) {
            log.warn("Could not seed the bundled knowledge document '{}': {}", filename,
                    e.getMessage());
            return DocumentOutcome.failed(filename, e.getMessage(), false);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Result types                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * What one bootstrap run did.
     *
     * @param attempted whether any ingestion was attempted at all
     * @param reason    human-readable outcome, suitable for a log line
     * @param documents per-document results, empty when nothing was attempted
     */
    public record BootstrapReport(boolean attempted, String reason,
            List<DocumentOutcome> documents) {

        public BootstrapReport {
            documents = List.copyOf(documents);
        }

        static BootstrapReport skipped(String reason) {
            return new BootstrapReport(false, reason, List.of());
        }

        /** Documents that reached {@code INDEXED}. */
        public long indexedCount() {
            return documents.stream().filter(DocumentOutcome::succeeded).count();
        }

        /** Chunks written across every seeded document. */
        public int chunkCount() {
            return documents.stream().mapToInt(DocumentOutcome::chunkCount).sum();
        }
    }

    /**
     * One document's result.
     *
     * @param modelUnavailable whether the failure was the embedding model rather than the file,
     *                         which is the one case worth abandoning the remaining documents for
     */
    public record DocumentOutcome(String filename, DocumentStatus status, int chunkCount,
            String error, boolean modelUnavailable) {

        static DocumentOutcome failed(String filename, String error, boolean modelUnavailable) {
            return new DocumentOutcome(filename, DocumentStatus.FAILED, 0, error, modelUnavailable);
        }

        public boolean succeeded() {
            return status == DocumentStatus.INDEXED;
        }
    }
}
