package com.ledgerline.ledger;

import com.ledgerline.account.AccountRepository;
import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only views of the ledger: transaction history and account statements. */
@RestController
@RequestMapping("/api/accounts/{accountId}")
public class StatementController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public record HistoryItem(long entryId, long transactionId, String type, String direction,
                              long amountPaise, long balanceAfterPaise, String description, Instant createdAt) {
    }

    public record StatementResponse(long accountId, LocalDate from, LocalDate to,
                                    long openingBalancePaise, long closingBalancePaise,
                                    List<HistoryItem> entries) {
    }

    private static final String SELECT_ENTRIES =
            "SELECT e.id, e.transaction_id, t.type, e.direction, e.amount, e.balance_after, "
                    + "t.description, e.created_at "
                    + "FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id ";

    private static final RowMapper<HistoryItem> MAPPER = (rs, i) -> new HistoryItem(
            rs.getLong("id"), rs.getLong("transaction_id"), rs.getString("type"),
            rs.getString("direction"), rs.getLong("amount"), rs.getLong("balance_after"),
            rs.getString("description"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public StatementController(JdbcTemplate jdbcTemplate, AccountRepository accountRepository,
                               UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    /** Newest entries first. */
    @GetMapping("/transactions")
    public List<HistoryItem> history(@PathVariable long accountId,
                                     @RequestParam(defaultValue = "50") int limit,
                                     Authentication authentication) {
        requireAccess(authentication, accountId);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.query(
                SELECT_ENTRIES + "WHERE e.account_id = ? ORDER BY e.id DESC LIMIT ?",
                MAPPER, accountId, safeLimit);
    }

    /** Opening balance, the entries in the date range (oldest first), and the closing balance. */
    @GetMapping("/statement")
    public StatementResponse statement(
            @PathVariable long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        requireAccess(authentication, accountId);

        LocalDate fromDate = from != null ? from : LocalDate.of(1970, 1, 1);
        LocalDate toDate = to != null ? to : LocalDate.now(ZONE);
        if (fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        Timestamp start = Timestamp.from(fromDate.atStartOfDay(ZONE).toInstant());
        Timestamp end = Timestamp.from(toDate.plusDays(1).atStartOfDay(ZONE).toInstant());

        List<Long> before = jdbcTemplate.query(
                "SELECT balance_after FROM ledger_entries WHERE account_id = ? AND created_at < ? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), accountId, start);
        long opening = before.isEmpty() ? 0L : before.get(0);

        List<HistoryItem> entries = jdbcTemplate.query(
                SELECT_ENTRIES + "WHERE e.account_id = ? AND e.created_at >= ? AND e.created_at < ? ORDER BY e.id ASC",
                MAPPER, accountId, start, end);
        long closing = entries.isEmpty() ? opening : entries.get(entries.size() - 1).balanceAfterPaise();

        return new StatementResponse(accountId, fromDate, toDate, opening, closing, entries);
    }

    /** Admins and auditors may read any account. Holders only their own. Others get 404. */
    private void requireAccess(Authentication authentication, long accountId) {
        User caller = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        boolean staff = caller.getRole() == Role.ADMIN || caller.getRole() == Role.AUDITOR;
        boolean allowed = staff
                ? accountRepository.existsById(accountId)
                : accountRepository.existsByIdAndOwnerId(accountId, caller.getId());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}