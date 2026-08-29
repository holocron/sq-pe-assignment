package com.sq.caa.repository.projection;

import java.math.BigDecimal;

/** Number and value of a customer's transactions grouped by {@code status}. */
public interface StatusCount {

    String getStatus();

    long getTxCount();

    BigDecimal getTotalAmount();
}
