-- V4: idempotency keys. One row per (user, key). The UNIQUE constraint is what makes
-- it impossible for two requests to both "win" the same key.

CREATE TABLE idempotency_keys (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    transaction_id  BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_idem_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_idem_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT uq_idem_user_key UNIQUE (user_id, idempotency_key)
);