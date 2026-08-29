package com.sq.caa.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A {@link ChunkStore} that keeps everything in a list.
 *
 * <p>Embedding is a network call to a local model and has nothing to say about whether ingestion
 * bookkeeping, visibility rules or the seeding gate are correct, so the tests that care about those
 * run against this instead. Retrieval is a keyword overlap score rather than a vector distance,
 * which is enough to assert that provenance and the document filter survive the round trip.
 *
 * <p>Shared by {@link RagServiceTest} and {@link KnowledgeBootstrapTest} so both exercise the same
 * fake, and so a change to {@link ChunkStore} has one place to be reflected.
 */
class InMemoryChunkStore implements ChunkStore {

    private final List<Map<String, Object>> chunks = new ArrayList<>();
    private final List<Integer> searches = new ArrayList<>();
    private final List<Set<UUID>> searchedDocuments = new ArrayList<>();

    private int failAfterChunks = Integer.MAX_VALUE;
    private Supplier<RuntimeException> indexFailure;

    void reset() {
        chunks.clear();
        searches.clear();
        searchedDocuments.clear();
        failAfterChunks = Integer.MAX_VALUE;
        indexFailure = null;
    }

    /** Fails once this many chunks have been written, as a partially embedded document does. */
    void failAfter(int writtenChunks) {
        this.failAfterChunks = writtenChunks;
    }

    /** Fails every write with the given error, as an unreachable embedding model does. */
    void failEveryIndexWith(Supplier<RuntimeException> failure) {
        this.indexFailure = failure;
    }

    int size() {
        return chunks.size();
    }

    List<Integer> searches() {
        return searches;
    }

    List<Set<UUID>> searchedDocuments() {
        return searchedDocuments;
    }

    List<Map<String, Object>> chunksOf(UUID documentId) {
        return chunks.stream()
                .filter(chunk -> documentId.toString().equals(chunk.get("document_id")))
                .toList();
    }

    @Override
    public int index(UUID documentId, String filename, String title, List<TextChunk> textChunks) {
        if (indexFailure != null) {
            throw indexFailure.get();
        }
        for (TextChunk textChunk : textChunks) {
            if (chunks.size() >= failAfterChunks) {
                throw new KnowledgeIndexException("the embedding model went away");
            }
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("document_id", documentId.toString());
            chunk.put("filename", filename);
            chunk.put("title", title);
            chunk.put("section_title", textChunk.sectionTitle().isBlank()
                    ? title : textChunk.sectionTitle());
            chunk.put("chunk_index", textChunk.chunkIndex());
            chunk.put("content", textChunk.content());
            chunks.add(chunk);
        }
        return textChunks.size();
    }

    @Override
    public void deleteByDocument(UUID documentId) {
        chunks.removeIf(chunk -> documentId.toString().equals(chunk.get("document_id")));
    }

    @Override
    public List<RetrievedChunk> search(String query, int topK, Collection<UUID> documentIds) {
        searches.add(topK);
        searchedDocuments.add(Set.copyOf(documentIds));
        Set<String> visible = new HashSet<>();
        documentIds.forEach(id -> visible.add(id.toString()));
        return chunks.stream()
                .filter(chunk -> visible.contains((String) chunk.get("document_id")))
                .map(chunk -> new RetrievedChunk(
                        UUID.randomUUID().toString(),
                        UUID.fromString((String) chunk.get("document_id")),
                        (String) chunk.get("filename"),
                        (String) chunk.get("title"),
                        (String) chunk.get("section_title"),
                        (Integer) chunk.get("chunk_index"),
                        (String) chunk.get("content"),
                        overlap(query, (String) chunk.get("content"))))
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    private static double overlap(String query, String content) {
        String haystack = content.toLowerCase();
        long matched = query.toLowerCase().lines()
                .flatMap(line -> List.of(line.split("\\s+")).stream())
                .filter(word -> word.length() > 3 && haystack.contains(word))
                .count();
        return Math.min(1d, matched / 4d);
    }
}
