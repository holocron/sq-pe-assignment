package com.sq.caa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The administrator's runtime override of the LLM endpoint configuration. Maps
 * {@code llm_settings}, a single-row table ({@code id} is pinned to 1).
 *
 * <p>An absent row means "use the environment configuration"; this entity only exists once an
 * admin has saved settings through {@code PUT /api/admin/llm-settings}.
 *
 * <p>{@code chatApiKey} and {@code embedApiKey} are plaintext by deliberate prototype decision
 * (see the V7/V8 migrations and the README); they must never be serialised - the API exposes only
 * whether each key is set. An empty string is an explicit "no key" (local model servers); a null
 * column (only possible on rows migrated by V8) reads the same.
 */
@Entity
@Table(name = "llm_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmSettings {

    /** Single-row sentinel; the table's CHECK constraint pins it to this value. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "base_url", nullable = false, columnDefinition = "text")
    private String baseUrl;

    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    @Column(name = "embed_model", nullable = false)
    private String embedModel;

    /** What {@code document_chunks.embedding} was altered to when this row was saved. */
    @Column(name = "embed_dimension", nullable = false)
    private int embedDimension;

    /** Chat credential; empty string is an explicit "no key". */
    @Column(name = "chat_api_key", columnDefinition = "text")
    private String chatApiKey;

    /** Embedding credential; empty string is an explicit "no key". */
    @Column(name = "embed_api_key", columnDefinition = "text")
    private String embedApiKey;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;
}
