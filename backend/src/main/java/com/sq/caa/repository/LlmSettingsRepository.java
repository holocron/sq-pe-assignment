package com.sq.caa.repository;

import com.sq.caa.domain.LlmSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code llm_settings} - a single-row table, so {@code findById(1L)} is the whole API surface. */
public interface LlmSettingsRepository extends JpaRepository<LlmSettings, Long> {
}
