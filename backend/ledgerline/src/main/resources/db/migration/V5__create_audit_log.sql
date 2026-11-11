-- V5: append-only audit log.
-- actor is stored as TEXT (not a foreign key) so the log survives even if a user is removed.

CREATE TABLE audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor           VARCHAR(100) NOT NULL,
    action          VARCHAR(150) NOT NULL,
    resource        VARCHAR(255) NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    http_status     INTEGER      NOT NULL,
    ip_address      VARCHAR(64),
    idempotency_key VARCHAR(100),

    CONSTRAINT ck_audit_status CHECK (status IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE INDEX idx_audit_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_actor ON audit_log (actor);

-- Append-only: any UPDATE, DELETE or TRUNCATE is refused by the database itself.
CREATE FUNCTION prevent_audit_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_change();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_change();