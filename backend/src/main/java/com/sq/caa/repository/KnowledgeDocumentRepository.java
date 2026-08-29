package com.sq.caa.repository;

import com.sq.caa.domain.DocumentStatus;
import com.sq.caa.domain.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Metadata of the documents backing the RAG knowledge base. */
@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    List<KnowledgeDocument> findAllByOrderByUploadedAtDesc();

    List<KnowledgeDocument> findByStatusOrderByUploadedAtDesc(DocumentStatus status);

    Optional<KnowledgeDocument> findByFilenameIgnoreCase(String filename);

    boolean existsByFilenameIgnoreCase(String filename);
}
