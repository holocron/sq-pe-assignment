package com.sq.caa.domain;

/** Application role. Admins additionally manage rules, users and the knowledge base. */
public enum UserRole {
    OPERATOR,
    ADMIN;

    /** Spring Security authority name for this role. */
    public String authority() {
        return "ROLE_" + name();
    }
}
