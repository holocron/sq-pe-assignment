package com.sq.caa.repository.projection;

import java.math.BigDecimal;

/** Number and value of a customer's payments grouped by beneficiary bank country. */
public interface CountryCount {

    String getCountry();

    long getTxCount();

    BigDecimal getTotalAmount();
}
