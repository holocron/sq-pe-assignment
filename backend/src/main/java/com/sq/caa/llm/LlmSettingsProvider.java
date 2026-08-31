package com.sq.caa.llm;

/**
 * Read access to the effective LLM configuration.
 *
 * <p>The full {@link MutableLlmSettingsService} implements this; consumers that only need to know
 * what the current configuration is (the vector-store dimension guard, the startup schema
 * verifier) depend on this narrow interface so a unit test can stub it with a lambda instead of
 * mocking the whole service.
 */
@FunctionalInterface
public interface LlmSettingsProvider {

    /** The settings in effect at call time; reads the database row when one exists. */
    EffectiveLlmSettings effective();
}
