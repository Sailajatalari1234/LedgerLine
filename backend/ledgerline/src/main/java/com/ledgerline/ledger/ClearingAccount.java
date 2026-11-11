package com.ledgerline.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds the bank's CASH_CLEARING system account, creating it the first time it is needed.
 * Deposits and withdrawals use it as the other side of the double entry.
 *
 * The insert is safe even if two requests arrive at the same time: the unique index decides,
 * and ON CONFLICT DO NOTHING quietly skips the loser. The "WHERE NOT EXISTS" part means
 * no database id number is wasted when the account already exists.
 */
@Component
public class ClearingAccount {

    static final String NAME = "CASH_CLEARING";

    private final JdbcTemplate jdbcTemplate;

    public ClearingAccount(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long id() {
        jdbcTemplate.update(
                "INSERT INTO accounts (owner_id, name, kind, balance) "
                        + "SELECT CAST(NULL AS BIGINT), CAST(? AS VARCHAR), 'SYSTEM', 0 "
                        + "WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE kind = 'SYSTEM' AND name = ?) "
                        + "ON CONFLICT DO NOTHING",
                NAME, NAME);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE kind = 'SYSTEM' AND name = ?", Long.class, NAME);
        return id;
    }
}