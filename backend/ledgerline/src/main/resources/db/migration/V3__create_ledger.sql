-- V3: ledger tables, plus an account "kind" so the bank can own a system account.
-- Money is always a whole number of PAISE. Never decimals.

-- 1) Accounts get a kind: CUSTOMER (belongs to a user) or SYSTEM (belongs to the bank itself).
ALTER TABLE accounts ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE accounts ALTER COLUMN owner_id DROP NOT NULL;

-- Rebuild the balance rule: customers may never be negative, system accounts may.
ALTER TABLE accounts DROP CONSTRAINT ck_accounts_balance_non_negative;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_kind
    CHECK (kind IN ('CUSTOMER', 'SYSTEM'));
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_owner_required
    CHECK (kind = 'SYSTEM' OR owner_id IS NOT NULL);
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_balance_non_negative
    CHECK (kind = 'SYSTEM' OR balance >= 0);

-- There can be only one system account with a given name.
CREATE UNIQUE INDEX uq_accounts_system_name ON accounts (name) WHERE kind = 'SYSTEM';

-- 2) One row per money event.
CREATE TABLE transactions (
    id           BIGSERIAL    PRIMARY KEY,
    type         VARCHAR(20)  NOT NULL,
    amount       BIGINT       NOT NULL,
    initiated_by BIGINT       NOT NULL,
    description  VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_transactions_initiated_by FOREIGN KEY (initiated_by) REFERENCES users (id),
    CONSTRAINT ck_transactions_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0)
);

-- 3) The double-entry lines: each is a DEBIT (subtracts) or a CREDIT (adds) on one account.
CREATE TABLE ledger_entries (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id BIGINT      NOT NULL,
    account_id     BIGINT      NOT NULL,
    direction      VARCHAR(6)  NOT NULL,
    amount         BIGINT      NOT NULL,
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_entries_account_id ON ledger_entries (account_id);