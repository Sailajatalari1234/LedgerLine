package com.ledgerline.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** One DEBIT or CREDIT line on one account. Never changed after it is saved. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    // Paise.
    @Column(nullable = false)
    private long amount;

    // The account's balance right after this entry was applied.
    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long transactionId, Long accountId, Direction direction, long amount, long balanceAfter) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public Long getId() {
        return id;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}