package com.sq.caa.sql;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning of the SQL rule evaluator, bound from {@code caa.sql.*}.
 *
 * @param datasource        connection settings of the least-privilege pool
 * @param statementTimeout  how long one rule query may run. Set on the transaction as a bound
 *                          parameter and mirrored as a JDBC query timeout, so a fragment that
 *                          accidentally - or deliberately - asks for a cartesian product is
 *                          cancelled by the server rather than holding the analysis open. Short on
 *                          purpose: every legitimate rule query reads at most a few hundred rows.
 * @param maxMatchedIds     how many transaction ids one result may carry back. The reported
 *                          {@code matchedCount} is never capped - only the id list is - so the
 *                          verdict and the score are unaffected by this number.
 * @param maxFragmentChars  longest fragment the validator will look at.
 */
@ConfigurationProperties(prefix = "caa.sql")
public record RuleSqlProperties(
        @DefaultValue Datasource datasource,
        @DefaultValue("5s") Duration statementTimeout,
        @DefaultValue("500") int maxMatchedIds,
        @DefaultValue("8000") int maxFragmentChars) {

    public RuleSqlProperties {
        datasource = datasource == null ? new Datasource(null, null, null, 3, null) : datasource;
        statementTimeout = clamp(statementTimeout, Duration.ofSeconds(5),
                Duration.ofMillis(250), Duration.ofSeconds(120));
        maxMatchedIds = Math.clamp(maxMatchedIds, 1, 10_000);
        maxFragmentChars = Math.clamp(maxFragmentChars, 64, 100_000);
    }

    /** Statement timeout in the {@code '1234ms'} form PostgreSQL takes for a GUC. */
    public String statementTimeoutSetting() {
        return statementTimeout.toMillis() + "ms";
    }

    /** JDBC-side timeout, a whole second longer so the server's own cancellation wins the race. */
    public int queryTimeoutSeconds() {
        return (int) Math.min(Integer.MAX_VALUE, statementTimeout.toSeconds() + 1);
    }

    private static Duration clamp(Duration value, Duration fallback, Duration min, Duration max) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }

    /**
     * Connection settings of the read-only pool.
     *
     * <p>This is a second, deliberately separate datasource. It is not the application's
     * {@code spring.datasource}, it is not managed by the transaction manager, and it does not
     * borrow the owner role's credentials - the whole point is that statements written by a
     * language model reach PostgreSQL as a principal that cannot do anything but read five
     * customer-scoped views.
     *
     * @param url               JDBC url, normally the same database as the primary datasource
     * @param username          the least-privilege login role created by {@code V5__readonly_role}
     * @param password          its password; overridden per environment, see the migration header
     * @param poolSize          maximum connections. Small: rule queries are short and an analysis
     *                          evaluates rules one at a time, so a large pool would only make a
     *                          runaway query more expensive.
     * @param connectionTimeout how long a caller waits for a connection before giving up
     */
    public record Datasource(
            @DefaultValue("jdbc:postgresql://localhost:5432/caa") String url,
            @DefaultValue("caa_readonly") String username,
            @DefaultValue("caa_readonly") String password,
            @DefaultValue("3") int poolSize,
            @DefaultValue("10s") Duration connectionTimeout) {

        public Datasource {
            url = url == null || url.isBlank() ? "jdbc:postgresql://localhost:5432/caa" : url.trim();
            username = username == null || username.isBlank() ? "caa_readonly" : username.trim();
            password = password == null ? "" : password;
            poolSize = Math.clamp(poolSize, 1, 32);
            connectionTimeout = connectionTimeout == null || connectionTimeout.isNegative()
                    || connectionTimeout.isZero()
                    ? Duration.ofSeconds(10)
                    : connectionTimeout;
        }
    }
}
