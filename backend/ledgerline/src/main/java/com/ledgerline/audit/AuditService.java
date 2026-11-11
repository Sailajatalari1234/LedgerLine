package com.ledgerline.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Writes and reads the append-only audit log. There is deliberately no update or delete method. */
@Service
public class AuditService {

    public record AuditEntry(long id, Instant createdAt, String actor, String action, String resource,
                             String status, int httpStatus, String ipAddress, String idempotencyKey) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String actor, String action, String resource, String status,
                       int httpStatus, String ipAddress, String idempotencyKey) {
        jdbcTemplate.update(
                "INSERT INTO audit_log (actor, action, resource, status, http_status, ip_address, idempotency_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                actor, action, resource, status, httpStatus, ipAddress, idempotencyKey);
    }

    /** Newest first. */
    public List<AuditEntry> latest(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.query(
                "SELECT id, created_at, actor, action, resource, status, http_status, ip_address, idempotency_key "
                        + "FROM audit_log ORDER BY id DESC LIMIT ?",
                (rs, i) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("resource"),
                        rs.getString("status"),
                        rs.getInt("http_status"),
                        rs.getString("ip_address"),
                        rs.getString("idempotency_key")),
                safeLimit);
    }
}