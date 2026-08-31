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
 * <p>{@code apiKey} is plaintext by deliberate prototype decision (see the V7 migration and the
 * README); it must never be serialised - the API exposes only whether a key is set.
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

    /** Null means "fall back to the environment key". */
    @Column(name = "api_key", columnDefinition = "text")
    private String apiKey;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;
}
