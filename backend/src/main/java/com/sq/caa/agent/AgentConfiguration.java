package com.sq.caa.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the agent's configuration properties ({@code caa.agent.*}).
 *
 * <p>Everything else the agent needs is a component: {@link RiskAgentLoop} drives the conversation,
 * {@link ReActRiskAgent} loads what a run needs, {@link AnalysisExecutor} owns the bounded pool the
 * runs execute on and {@link AnalysisStreamRegistry} owns the live transcripts.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
}
