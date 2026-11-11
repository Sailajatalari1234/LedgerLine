package com.ledgerline.ledger;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deposits and withdrawals. Each public method is ONE database transaction:
 * everything inside succeeds together or is undone together.
 */
@Service
public class MoneyService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final ClearingAccount clearingAccount;
    private final JdbcTemplate jdbcTemplate;

    public MoneyService(AccountRepository accountRepository,
                        LedgerTransactionRepository transactionRepository,
                        LedgerEntryRepository entryRepository,
                        ClearingAccount clearingAccount,
                        JdbcTemplate jdbcTemplate) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.clearingAccount = clearingAccount;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public MoneyOutcome deposit(Long callerId, long accountId, long amount,
                                String description, String idempotencyKey) {
        return execute(TransactionType.DEPOSIT, callerId, accountId, amount, description, idempotencyKey);
    }

    @Transactional
    public MoneyOutcome withdraw(Long callerId, long accountId, long amount,
                                 String description, String idempotencyKey) {
        return execute(TransactionType.WITHDRAWAL, callerId, accountId, amount, description, idempotencyKey);
    }

    private MoneyOutcome execute(TransactionType type, Long callerId, long accountId, long amount,
                                 String description, String idempotencyKey) {

        // 1. Ownership: only the owner may touch this account.
        //    Someone else's account answers 404, the same as an account that does not exist.
        if (!accountRepository.existsByIdAndOwnerId(accountId, callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 2. Idempotency: try to reserve the key. The unique constraint decides who wins,
        //    even if two identical requests arrive at the same moment.
        String requestHash = hashOf(type, accountId, amount, description);
        int reserved = jdbcTemplate.update(
                "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, idempotency_key) DO NOTHING",
                callerId, idempotencyKey, requestHash);
        if (reserved == 0) {
            return replay(callerId, idempotencyKey, requestHash, accountId);
        }

        // 3. Lock both accounts (SELECT ... FOR UPDATE), always in ascending id order.
        long clearingId = clearingAccount.id();
        Map<Long, Account> locked = lockInOrder(accountId, clearingId);
        Account customer = locked.get(accountId);
        Account clearing = locked.get(clearingId);

        boolean isDeposit = type == TransactionType.DEPOSIT;

        // 4. Overdraft check (the database CHECK is a second safety net).
        if (!isDeposit && customer.getBalance() < amount) {
            throw new InsufficientFundsException();
        }

        // 5. Record the event, move the money, and write both sides of the double entry.
        LedgerTransaction tx = transactionRepository.save(
                new LedgerTransaction(type, amount, callerId, description));

        if (isDeposit) {
            customer.credit(amount);
            clearing.debit(amount);
        } else {
            customer.debit(amount);
            clearing.credit(amount);
        }

        Direction customerSide = isDeposit ? Direction.CREDIT : Direction.DEBIT;
        Direction clearingSide = isDeposit ? Direction.DEBIT : Direction.CREDIT;
        entryRepository.save(new LedgerEntry(tx.getId(), customer.getId(), customerSide, amount, customer.getBalance()));
        entryRepository.save(new LedgerEntry(tx.getId(), clearing.getId(), clearingSide, amount, clearing.getBalance()));

        // 6. Remember which transaction this key produced, so a retry can return the original result.
        jdbcTemplate.update(
                "UPDATE idempotency_keys SET transaction_id = ? WHERE user_id = ? AND idempotency_key = ?",
                tx.getId(), callerId, idempotencyKey);

        return new MoneyOutcome(
                new MoneyResponse(tx.getId(), type.name(), accountId, amount, customer.getBalance()),
                false);
    }

    /** Locks accounts one by one in ascending id order. Same order everywhere means no deadlocks. */
    private Map<Long, Account> lockInOrder(long... ids) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        for (long id : Arrays.stream(ids).distinct().sorted().toArray()) {
            Account account = accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            locked.put(id, account);
        }
        return locked;
    }

    private record StoredKey(String requestHash, Long transactionId) {
    }

    /** The key was used before. Same request: return the original result. Different request: refuse. */
    private MoneyOutcome replay(Long callerId, String idempotencyKey, String requestHash, long accountId) {
        List<StoredKey> rows = jdbcTemplate.query(
                "SELECT request_hash, transaction_id FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                (rs, i) -> new StoredKey(rs.getString("request_hash"), rs.getObject("transaction_id", Long.class)),
                callerId, idempotencyKey);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Please retry the request");
        }
        StoredKey stored = rows.get(0);

        if (!stored.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "Idempotency-Key was already used with a different request");
        }
        if (stored.transactionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A request with this Idempotency-Key is still being processed");
        }

        MoneyResponse original = jdbcTemplate.queryForObject(
                "SELECT t.id, t.type, t.amount, e.balance_after FROM transactions t "
                        + "JOIN ledger_entries e ON e.transaction_id = t.id AND e.account_id = ? "
                        + "WHERE t.id = ?",
                (rs, i) -> new MoneyResponse(rs.getLong("id"), rs.getString("type"), accountId,
                        rs.getLong("amount"), rs.getLong("balance_after")),
                accountId, stored.transactionId());
        return new MoneyOutcome(original, true);
    }

    /** A fingerprint (SHA-256) of everything that matters in the request. */
    private static String hashOf(TransactionType type, long accountId, long amount, String description) {
        String text = type.name() + "|" + accountId + "|" + amount + "|" + (description == null ? "" : description);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}