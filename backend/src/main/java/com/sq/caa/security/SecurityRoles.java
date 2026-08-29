package com.sq.caa.security;

/**
 * Role names and ready-made {@code @PreAuthorize} expressions.
 *
 * <p>Authorities are stored as {@code ROLE_ADMIN} / {@code ROLE_OPERATOR}, so
 * {@code hasRole(...)} is the right test everywhere.
 */
public final class SecurityRoles {

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";

    /** Admins only: rule writes, knowledge upload/delete, user administration. */
    public static final String IS_ADMIN = "hasRole('ADMIN')";

    /** Any signed-in operator or admin. */
    public static final String IS_OPERATOR_OR_ADMIN = "hasAnyRole('ADMIN','OPERATOR')";

    private SecurityRoles() {
    }
}
