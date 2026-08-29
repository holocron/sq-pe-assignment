package com.sq.caa.rules;

import java.util.UUID;

/** Thrown when rule evaluation is asked for a customer that does not exist. Mapped to 404. */
public class UnknownCustomerException extends RuntimeException {

    private final UUID customerId;

    public UnknownCustomerException(UUID customerId) {
        super("No customer with id " + customerId);
        this.customerId = customerId;
    }

    public UUID customerId() {
        return customerId;
    }
}
