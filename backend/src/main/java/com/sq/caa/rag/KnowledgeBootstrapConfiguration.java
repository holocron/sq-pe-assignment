package com.sq.caa.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link KnowledgeBootstrapProperties}.
 *
 * <p>The project binds each {@code @ConfigurationProperties} record explicitly rather than with a
 * blanket {@code @ConfigurationPropertiesScan}, so the seeding properties get their own one-line
 * registration next to the component that reads them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeBootstrapProperties.class)
public class KnowledgeBootstrapConfiguration {
}
