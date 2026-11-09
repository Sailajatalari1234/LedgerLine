package com.ledgerline.user;

/**
 * The three roles in LedgerLine.
 * The names must match the CHECK constraint in the users table exactly.
 */
public enum Role {
    ADMIN,
    ACCOUNT_HOLDER,
    AUDITOR
}