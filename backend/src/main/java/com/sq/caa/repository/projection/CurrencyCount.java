package com.sq.caa.repository.projection;

import java.math.BigDecimal;

/** Number and value of a customer's transactions grouped by {@code currency}. */
public interface CurrencyCount {

    String getCurrency();

    long getTxCount();

    BigDecimal getTotalAmount();
}
