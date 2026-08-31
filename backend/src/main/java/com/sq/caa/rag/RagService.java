package com.sq.caa.rag;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import com.sq.caa.repository.KnowledgeDocumentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The knowledge base: ingestion of policy documents and retrieval over them.
 *
 * <p>This is the whole RAG surface. Uploads are parsed into sections, cut into overlapping windows,
 * embedded and stored; searches return the nearest windows with their provenance. The operator
 * search screen and the ReAct agent's {@code search_policy_knowledge} tool go through one method,
 * {@link #searchPolicy(String, Integer)}, so the screen that claims to show what the model reads
 * really does.
 *
 * <p><b>Ingestion is synchronous.</b> A policy document is a handful of pages and embeds in
 * seconds, so the upload call returns the finished {@link KnowledgeDocument} with its real
 * {@code chunkCount} - far more useful to an administrator than a {@code PENDING} row they have to
 * poll. It is deliberately <em>not</em> wrapped in a transaction: the embedding round trips must
 * not hold a database connection open, and the status transitions
 * ({@code PROCESSING -> INDEXED | FAILED}) are only meaningful if each one commits on its own.
 *
 * <p><b>Failure leaves evidence.</b> A file that is not a {@code .docx} or {@code .pdf} is refused
 * before any row exists. A file that is one but cannot be read, or that the embedding model refuses,
 * leaves a {@code FAILED} row carrying the reason, so the administrator can see what happened
 * instead of an upload that silently vanished. Any chunks written before the failure are removed -
 * and because that clean-up is best effort, search is additionally restricted to {@code INDEXED}
 * documents, so a failed or half-written document cannot contribute to a result even if its rows
 * survive.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** {@code knowledge_documents.filename} and {@code .title} are VARCHAR(255). */
    private static final int MAX_NAME_LENGTH = 255;

    /** Cap on the stored failure message, so one stack trace cannot bloat the row. */
    private static final int MAX_ERROR_LENGTH = 2000;

    /**
     * Longest passage any caller is given, including {@link #TRUNCATION_MARKER}.
     *
     * <p>The cap belongs here rather than in a caller because the operator search screen exists to
     * show a reviewer <em>what the model read</em>. A screen that renders the full chunk while the
     * agent saw only its first 1,200 characters invites the reviewer to accept a citation the model
     * could not have grounded, so both are cut to the same length by the same code.
     */
    public static final int MAX_PASSAGE_CHARS = 1200;

    /** Appended to a passage that had to be cut, so a reader can see it is not the whole section. */
    public static final String TRUNCATION_MARKER = " [...passage truncated]";

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentFormatDetector formatDetector;
    private final Map<KnowledgeFormat, DocumentTextExtractor> extractors;
    private final SectionChunker chunker;
    private final ChunkStore chunkStore;
    private final RagProperties properties;

    public RagService(KnowledgeDocumentRepository documentRepository,
            DocumentFormatDetector formatDetector,
            List<DocumentTextExtractor> extractors,
            SectionChunker chunker,
            ChunkStore chunkStore,
            RagProperties properties) {
        this.documentRepository = documentRepository;
        this.formatDetector = formatDetector;
        this.extractors = indexByFormat(extractors);
        this.chunker = chunker;
        this.chunkStore = chunkStore;
        this.properties = properties;
    }

    private static Map<KnowledgeFormat, DocumentTextExtractor> indexByFormat(
            List<DocumentTextExtractor> extractors) {
        Map<KnowledgeFormat, DocumentTextExtractor> byFormat = new EnumMap<>(KnowledgeFormat.class);
        for (DocumentTextExtractor extractor : extractors) {
            byFormat.put(extractor.format(), extractor);
        }
        for (KnowledgeFormat format : KnowledgeFormat.values()) {
            if (!byFormat.containsKey(format)) {
                throw new IllegalStateException(
                        "No DocumentTextExtractor bean is registered for " + format);
            }
        }
        return Map.copyOf(byFormat);
    }

    /* ------------------------------------------------------------------ */
    /* Retrieval                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The one retrieval in the system: vector similarity search over the knowledge base.
     *
     * <p>BUILD_SPEC section 5 gives the operator screen and the ReAct agent the same knowledge
     * base, and the operator screen tells the reviewer that what it shows is what the model reads.
     * That is only true if both go through <em>this</em> method - not merely through the same
     * ranking, but through the same clamping, the same visibility rule and the same passage
     * length. {@code POST /api/knowledge/search} and the agent's {@code search_policy_knowledge}
     * tool therefore both call it, and neither applies a cap of its own.
     *
     * <p>It is deliberately forgiving about its arguments, because one caller is a language model:
     * a blank or null query returns nothing rather than throwing, and {@code topK} is clamped into
     * {@code [1, caa.rag.max-top-k]} - null, zero or negative means "use
     * {@code caa.rag.default-top-k}".
     *
     * <p>Only chunks of {@code INDEXED} documents can be returned. A document is {@code PROCESSING}
     * while its batches are being embedded and committed, and a failed ingest cleans its chunks up
     * on a best-effort basis, so ranking over the raw chunk table would leak passages from
     * half-written and failed documents into both the UI and the agent's citations.
     *
     * @param query free-text question or topic
     * @param topK  desired number of hits; null or non-positive means the configured default
     * @return the nearest chunks, best first; never null, possibly empty
     * @throws KnowledgeIndexException when the embedding model or the vector store is unavailable
     */
    public List<RetrievedChunk> searchPolicy(String query, Integer topK) {
        String question = query == null ? "" : query.strip();
        if (question.isEmpty()) {
            return List.of();
        }
        int wanted = topK == null || topK <= 0
                ? properties.defaultTopK()
                : Math.min(topK, properties.maxTopK());

        // An empty corpus is the common case before the first upload. Answering it from the
        // document table saves an embedding round trip that could only ever return nothing.
        List<UUID> searchable = indexedDocumentIds();
        if (searchable.isEmpty()) {
            log.debug("Knowledge search for '{}' skipped: no indexed documents", question);
            return List.of();
        }

        List<RetrievedChunk> hits = chunkStore.search(question, wanted, searchable);
        List<RetrievedChunk> capped = new ArrayList<>(hits.size());
        for (RetrievedChunk hit : hits) {
            capped.add(cap(hit));
        }
        log.debug("Knowledge search for '{}' returned {} chunk(s) from {} indexed document(s)",
                question, capped.size(), searchable.size());
        return List.copyOf(capped);
    }

    /**
     * {@link #searchPolicy(String, Integer)} under the name the existing callers use.
     *
     * @param topK desired number of hits; {@code 0} or negative means the configured default
     */
    public List<RetrievedChunk> search(String query, int topK) {
        return searchPolicy(query, topK);
    }

    /** Search with the configured default {@code topK}. */
    public List<RetrievedChunk> search(String query) {
        return searchPolicy(query, null);
    }

    /**
     * Cuts a passage to {@link #MAX_PASSAGE_CHARS} <em>including</em> the marker, so a caller that
     * applies the same cap again finds nothing left to do and cannot stack a second marker on.
     */
    private static RetrievedChunk cap(RetrievedChunk hit) {
        String content = hit.content() == null ? "" : hit.content();
        if (content.length() <= MAX_PASSAGE_CHARS) {
            return hit;
        }
        String cut = content.substring(0, MAX_PASSAGE_CHARS - TRUNCATION_MARKER.length())
                .stripTrailing() + TRUNCATION_MARKER;
        return new RetrievedChunk(hit.chunkId(), hit.documentId(), hit.filename(), hit.title(),
                hit.sectionTitle(), hit.chunkIndex(), cut, hit.score());
    }

    /* ------------------------------------------------------------------ */
    /* Document administration                                             */
    /* ------------------------------------------------------------------ */

    /** Every uploaded document, newest first. */
    @Transactional(readOnly = true)
    public List<KnowledgeDocument> listDocuments() {
        return documentRepository.findAllByOrderByUploadedAtDesc();
    }

    /** One document by id. */
    @Transactional(readOnly = true)
    public KnowledgeDocument getDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId));
    }

    /**
     * Deletes a document and everything it contributed to the knowledge base.
     *
     * <p>The chunk delete and the row delete run in one transaction on the same connection - the
     * vector store shares the application's {@code DataSource} - so a document can never be left
     * with orphaned chunks that would keep being cited after it was removed.
     */
    @Transactional
    public void delete(UUID documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId));
        chunkStore.deleteByDocument(documentId);
        documentRepository.delete(document);
        log.info("Deleted knowledge document '{}' ({}) and its {} chunk(s)", document.getFilename(),
                documentId, document.getChunkCount());
    }

    /**
     * Parses, chunks, embeds and stores an uploaded document.
     *
     * @param rawFilename the browser-supplied file name
     * @param content     the complete file bytes
     * @param uploadedBy  username recorded on the row
     * @return the stored document, {@code INDEXED}
     * @throws IllegalArgumentException      the file is empty or larger than {@code caa.rag.max-upload-bytes}
     * @throws UnsupportedDocumentException  the bytes are not a {@code .docx} or a {@code .pdf}
     * @throws DuplicateDocumentException    a document with the same name is already indexed
     * @throws DocumentExtractionException   the file is corrupt or carries no extractable text
     * @throws KnowledgeIndexException       the embedding model or the vector store is unavailable
     */
    public KnowledgeDocument ingest(String rawFilename, byte[] content, String uploadedBy) {
        String filename = normaliseFilename(rawFilename);
        if (content == null || content.length == 0) {
            throw new UnsupportedDocumentException(filename, "empty file",
                    "The uploaded file is empty.");
        }
        if (content.length > properties.maxUploadBytes()) {
            throw new IllegalArgumentException("The file is " + megabytes(content.length)
                    + " MB, which exceeds the " + megabytes(properties.maxUploadBytes())
                    + " MB limit for knowledge documents.");
        }

        KnowledgeFormat format = formatDetector.detect(filename, content);
        replaceOrRejectExisting(filename);

        KnowledgeDocument document = newDocument(filename, format, content.length, uploadedBy,
                content);
        insert(document);

        try {
            ParsedDocument parsed = extractors.get(format).extract(content, filename);
            List<TextChunk> chunks = chunker.chunk(parsed);
            if (chunks.isEmpty()) {
                throw new DocumentExtractionException(filename,
                        "No text could be chunked out of the document.");
            }
            // A document whose own metadata and headings gave nothing keeps the file-name title
            // set when the row was created, so the column is never blank.
            String title = trim(parsed.title(), MAX_NAME_LENGTH);
            if (title == null || title.isEmpty()) {
                title = document.getTitle();
            }
            document.setTitle(title);

            int stored = chunkStore.index(document.getDocumentId(), filename, title, chunks);
            document.setChunkCount(stored);
            document.setStatus(DocumentStatus.INDEXED);
            document.setError(null);
            KnowledgeDocument indexed = documentRepository.saveAndFlush(document);
            log.info("Indexed '{}' as {} chunk(s) across {} section(s)", filename, stored,
                    parsed.sectionCount());
            return indexed;
        } catch (RuntimeException e) {
            markFailed(document, e);
            throw e;
        }
    }

    /**
     * Re-extracts, re-chunks and re-embeds a document from its stored original bytes, in place -
     * the row and its {@code documentId} survive, so the chunk metadata links stay valid. Used by
     * the re-embed job after an embedding-model change; like {@link #ingest} it is deliberately not
     * transactional, so the embedding round trips hold no connection open.
     *
     * @throws KnowledgeDocumentNotFoundException no document with that id
     * @throws KnowledgeIndexException            the document predates source-byte retention (V7),
     *                                            so there is nothing to re-extract from
     */
    public KnowledgeDocument reindex(UUID documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId));
        String filename = document.getFilename();
        byte[] content = document.getSourceBytes();
        if (content == null || content.length == 0) {
            throw new KnowledgeIndexException("The original file bytes of '" + filename
                    + "' were not stored (it predates V7), so it cannot be re-embedded.");
        }

        deleteChunksQuietly(documentId);
        document.setChunkCount(0);
        document.setStatus(DocumentStatus.PROCESSING);
        document.setError(null);
        documentRepository.saveAndFlush(document);

        try {
            KnowledgeFormat format = formatDetector.detect(filename, content);
            ParsedDocument parsed = extractors.get(format).extract(content, filename);
            List<TextChunk> chunks = chunker.chunk(parsed);
            if (chunks.isEmpty()) {
                throw new DocumentExtractionException(filename,
                        "No text could be chunked out of the document.");
            }
            String title = trim(parsed.title(), MAX_NAME_LENGTH);
            if (title != null && !title.isEmpty()) {
                document.setTitle(title);
            }
            int stored = chunkStore.index(document.getDocumentId(), filename, document.getTitle(),
                    chunks);
            document.setChunkCount(stored);
            document.setStatus(DocumentStatus.INDEXED);
            document.setError(null);
            KnowledgeDocument indexed = documentRepository.saveAndFlush(document);
            log.info("Re-indexed '{}' as {} chunk(s)", filename, stored);
            return indexed;
        } catch (RuntimeException e) {
            markFailed(document, e);
            throw e;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The documents a search may draw from: those that finished ingesting.
     *
     * <p>The corpus is a curated handful of policy documents, so listing their ids costs one small
     * indexed read and lets the restriction be pushed into the vector query itself.
     */
    private List<UUID> indexedDocumentIds() {
        return documentRepository.findByStatusOrderByUploadedAtDesc(DocumentStatus.INDEXED).stream()
                .map(KnowledgeDocument::getDocumentId)
                .toList();
    }

    /**
     * A previous upload of the same file name is either an operator mistake (409) or the remains of
     * an ingestion that failed, in which case it is cleared out so the retry can proceed.
     *
     * <p>This check alone is a read-then-write and two concurrent uploads of the same name would
     * both pass it. The real guarantee is the unique index on {@code lower(filename)} added in
     * {@code V4__rag_fixes.sql}; {@link #insert(KnowledgeDocument)} turns the resulting constraint
     * violation into the same {@code 409} this check produces, so the loser of the race gets the
     * documented answer instead of a 500, and no second row can ever exist to make
     * {@code findByFilenameIgnoreCase} throw.
     */
    private void replaceOrRejectExisting(String filename) {
        Optional<KnowledgeDocument> existing = documentRepository.findByFilenameIgnoreCase(filename);
        if (existing.isEmpty()) {
            return;
        }
        KnowledgeDocument previous = existing.get();
        if (previous.getStatus() != DocumentStatus.FAILED) {
            throw new DuplicateDocumentException(filename, previous.getDocumentId());
        }
        log.info("Replacing the failed upload of '{}' ({})", filename, previous.getDocumentId());
        deleteChunksQuietly(previous.getDocumentId());
        documentRepository.delete(previous);
        documentRepository.flush();
    }

    /**
     * Writes the {@code PROCESSING} row, translating the unique-filename violation that a
     * concurrent upload of the same name produces into the documented {@code 409}.
     */
    private void insert(KnowledgeDocument document) {
        try {
            documentRepository.saveAndFlush(document);
        } catch (DataIntegrityViolationException e) {
            UUID winner = documentRepository.findByFilenameIgnoreCase(document.getFilename())
                    .map(KnowledgeDocument::getDocumentId)
                    .orElse(null);
            log.info("Concurrent upload of '{}' lost the race to {}", document.getFilename(),
                    winner);
            throw new DuplicateDocumentException(document.getFilename(), winner);
        }
    }

    private KnowledgeDocument newDocument(String filename, KnowledgeFormat format, long sizeBytes,
            String uploadedBy, byte[] content) {
        return KnowledgeDocument.builder()
                .documentId(UUID.randomUUID())
                .filename(filename)
                .title(trim(HeadingHeuristics.titleFromFilename(filename), MAX_NAME_LENGTH))
                .mimeType(format.mimeType())
                .sizeBytes(sizeBytes)
                .chunkCount(0)
                .status(DocumentStatus.PROCESSING)
                .uploadedBy(uploadedBy)
                .uploadedAt(Instant.now())
                .sourceBytes(content)
                .build();
    }

    private void markFailed(KnowledgeDocument document, RuntimeException failure) {
        log.warn("Ingestion of '{}' failed: {}", document.getFilename(), failure.getMessage());
        deleteChunksQuietly(document.getDocumentId());
        document.setChunkCount(0);
        document.setStatus(DocumentStatus.FAILED);
        document.setError(trim(describe(failure), MAX_ERROR_LENGTH));
        try {
            documentRepository.saveAndFlush(document);
        } catch (RuntimeException e) {
            // The original failure is what the caller needs to see; losing the audit row is not
            // worth masking it with a second exception.
            log.error("Could not record the failed ingestion of '{}'", document.getFilename(), e);
        }
    }

    /** Best effort clean-up: the caller is already handling a failure and must not be derailed. */
    private void deleteChunksQuietly(UUID documentId) {
        try {
            chunkStore.deleteByDocument(documentId);
        } catch (RuntimeException e) {
            log.error("Could not remove the chunks of document {}", documentId, e);
        }
    }

    /** Strips any path the browser sent and caps the length to what the column holds. */
    private static String normaliseFilename(String rawFilename) {
        String name = rawFilename == null ? "" : rawFilename.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1).strip();
        }
        if (name.isEmpty()) {
            throw new UnsupportedDocumentException(rawFilename, "unnamed file",
                    "The upload has no file name. Send the file as the multipart part 'file'.");
        }
        return trim(name, MAX_NAME_LENGTH);
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static long megabytes(long bytes) {
        return Math.max(1, Math.round(bytes / 1_048_576d));
    }
}
