package com.sq.caa.config;

import com.sq.caa.sql.ReadOnlyDataSource;
import com.sq.caa.sql.RuleSqlProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the second, least-privilege datasource that agent-authored SQL runs on.
 *
 * <p>It is separate from the application's datasource in every way that matters: its own pool, its
 * own login role, read-only connections, no transaction manager and no shared state. Nothing the
 * agent writes can reach the owner role's connection, and nothing the application does enlists this
 * one in a transaction.
 *
 * <p>The bean type is {@link ReadOnlyDataSource}, not {@code DataSource}, and that is load-bearing
 * rather than stylistic: Boot's {@code DataSourceAutoConfiguration} is conditional on no
 * {@code DataSource} bean existing, so declaring one here would quietly disable the primary
 * datasource that JPA, Flyway and every repository depend on.
 */
@Configuration
@EnableConfigurationProperties(RuleSqlProperties.class)
public class ReadOnlyDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyDataSourceConfig.class);

    /**
     * The pool, plus one probe.
     *
     * <p>The probe never fails start-up. A misconfigured read-only role must not stop an operator
     * logging in and reading yesterday's analyses - but it does stop every rule from being judged,
     * so it is worth a warning that names the cause rather than leaving a run to fail later with a
     * connection error per rule.
     */
    @Bean(destroyMethod = "close")
    public ReadOnlyDataSource ruleSqlDataSource(RuleSqlProperties properties) {
        ReadOnlyDataSource dataSource = new ReadOnlyDataSource(properties.datasource());
        probe(dataSource);
        return dataSource;
    }

    private void probe(ReadOnlyDataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1 FROM caa_ro.transactions WHERE false");
            connection.rollback();
            log.info("rule SQL datasource ready: {}", dataSource.describe());
        } catch (SQLException e) {
            log.warn("rule SQL datasource is not usable ({}): {}. Rules cannot be evaluated until "
                            + "this is fixed - check that V5__readonly_role.sql has run and that "
                            + "caa.sql.datasource.username/password match the role it creates.",
                    dataSource.describe(), e.getMessage());
        }
    }
}
