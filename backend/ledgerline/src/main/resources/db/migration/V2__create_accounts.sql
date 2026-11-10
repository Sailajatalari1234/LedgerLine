-- V2: accounts table
-- balance is stored in PAISE (1 rupee = 100 paise) as a whole number, never as a decimal.
-- A running balance is a cached total. The ledger entries added later are the full record.

CREATE TABLE accounts (
    id         BIGSERIAL    PRIMARY KEY,
    owner_id   BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    balance    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    -- The database itself refuses a negative balance, even if Java code has a bug.
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);