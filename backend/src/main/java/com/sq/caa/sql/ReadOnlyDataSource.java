package com.sq.caa.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The connection pool the agent's SQL runs on, and the only place the read-only credentials are
 * used.
 *
 * <p>It deliberately does <b>not</b> implement {@code javax.sql.DataSource}. Spring Boot's
 * {@code DataSourceAutoConfiguration} backs off as soon as any bean of that type exists, so
 * publishing a second {@code DataSource} bean would silently take the application's primary
 * datasource - and with it JPA, Flyway and every repository - away from Boot's own configuration.
 * Wrapping the pool keeps the two apart at the type level: nothing can inject this by accident
 * where the owner-role datasource was meant, and no transaction manager can enlist it.
 *
 * <p>Every connection it hands out is already marked read-only and non-transactional at the pool
 * level. The evaluator sets the same properties again per transaction, because a pool default is a
 * configuration, not a guarantee.
 */
public final class ReadOnlyDataSource implements AutoCloseable {

    private final HikariDataSource pool;

    public ReadOnlyDataSource(RuleSqlProperties.Datasource settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("caa-rule-sql");
        config.setJdbcUrl(settings.url());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());
        config.setAutoCommit(false);
        config.setReadOnly(true);
        // Never fail application start-up over this pool. A database that refuses the read-only
        // role has to surface as "this rule could not be judged" - which fails the run and names
        // the rule - not as an application that will not boot and cannot even show a customer.
        config.setInitializationFailTimeout(-1);
        this.pool = new HikariDataSource(config);
    }

    /** A read-only, auto-commit-off connection. The caller owns closing it. */
    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    /** Where the pool points, for the start-up log and for diagnostics. Never the credentials. */
    public String describe() {
        return pool.getUsername() + "@" + pool.getJdbcUrl();
    }

    @Override
    public void close() {
        pool.close();
    }
}
