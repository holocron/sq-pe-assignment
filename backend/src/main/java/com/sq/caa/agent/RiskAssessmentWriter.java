package com.sq.caa.agent;

import com.sq.caa.domain.RiskAssessment;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.Session;

/**
 * Writes the {@code risk_assessments} rows of one analysis run.
 *
 * <p>Exists because {@code JpaRepository.saveAll} is the wrong tool for these rows.
 * {@link RiskAssessment} carries an assigned {@code @EmbeddedId} and no {@code @Version}, so Spring
 * Data's {@code isNew} test - "is the id null?" - is false for every row and {@code save} takes the
 * {@code merge} branch. Hibernate then treats each row as detached and issues a SELECT by primary
 * key before it can schedule the insert. Those SELECTs cannot find anything: the previous rows of
 * the run were just removed by a bulk delete in the same transaction. One run writes one row per
 * (transaction, rule) pair - a few hundred on the seeded data, thousands for a busy customer - so
 * that is a few hundred wasted round trips, unbatched, every run.
 *
 * <p>{@link EntityManager#persist} states what the caller already knows - these rows are new - so no
 * SELECT is issued, and raising the session's JDBC batch size lets the driver send the inserts in
 * batches instead of one statement at a time.
 *
 * <p>The persistence context is cleared as it goes, so a large run does not accumulate hundreds of
 * managed entities. Callers must therefore write these rows <em>before</em> loading anything else
 * they intend to keep working with in the same transaction.
 */
public final class RiskAssessmentWriter {

    /**
     * Rows per JDBC batch and per persistence-context flush. 100 keeps the batches large enough to
     * amortise the round trip and the context small enough to stay cheap to flush.
     */
    public static final int BATCH_SIZE = 100;

    private RiskAssessmentWriter() {
    }

    /**
     * Inserts every row, batched, without a read-before-write.
     *
     * @return how many rows were written
     */
    public static int write(EntityManager entityManager, List<RiskAssessment> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Session session = entityManager.unwrap(Session.class);
        Integer previousBatchSize = session.getJdbcBatchSize();
        session.setJdbcBatchSize(BATCH_SIZE);
        try {
            int written = 0;
            for (RiskAssessment row : rows) {
                entityManager.persist(row);
                if (++written % BATCH_SIZE == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }
            entityManager.flush();
            entityManager.clear();
            return written;
        } finally {
            session.setJdbcBatchSize(previousBatchSize);
        }
    }
}
