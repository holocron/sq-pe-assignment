package com.sq.caa.agent;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts the SQL Hibernate actually sends, so a test can assert on statement counts rather than on
 * timings.
 *
 * <p>Wired in with {@code spring.jpa.properties.hibernate.session_factory.statement_inspector}, and
 * therefore instantiated reflectively by Hibernate: it has to be public with a no-argument
 * constructor, and the counters have to be static because Hibernate owns the instance.
 */
public class CountingStatementInspector implements StatementInspector {

    private static final LongAdder SELECTS = new LongAdder();
    private static final LongAdder INSERTS = new LongAdder();
    private static final LongAdder OTHER = new LongAdder();

    @Override
    public String inspect(String sql) {
        String statement = sql == null ? "" : sql.trim().toLowerCase(Locale.ROOT);
        if (statement.startsWith("select")) {
            SELECTS.increment();
        } else if (statement.startsWith("insert")) {
            INSERTS.increment();
        } else {
            OTHER.increment();
        }
        return sql;
    }

    static void reset() {
        SELECTS.reset();
        INSERTS.reset();
        OTHER.reset();
    }

    static long selects() {
        return SELECTS.sum();
    }

    static long inserts() {
        return INSERTS.sum();
    }

    static long statements() {
        return SELECTS.sum() + INSERTS.sum() + OTHER.sum();
    }
}
