package com.sq.caa.repository.projection;

import com.sq.caa.domain.ActivityType;
import java.math.BigDecimal;
import java.time.Instant;

/** Per-activity-type rollup of a customer's transactions, one row per activity type present. */
public interface ActivityTypeAggregate {

    ActivityType getActivityType();

    long getTxCount();

    BigDecimal getTotalAmount();

    BigDecimal getMinAmount();

    BigDecimal getMaxAmount();

    Double getAvgAmount();

    Instant getFirstAt();

    Instant getLastAt();
}
