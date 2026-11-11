package com.ledgerline.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row of the "accounts" table. Money is a whole number of paise (a Java long). */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Empty only for SYSTEM accounts.
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountKind kind;

    // Paise. It changes only through credit() and debit(), never through a setter.
    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    /** A customer account. Balances always start at zero. */
    public Account(Long ownerId, String name) {
        this.ownerId = ownerId;
        this.name = name;
        this.kind = AccountKind.CUSTOMER;
        this.balance = 0;
    }

    /** CREDIT: adds money. Math.addExact throws an error instead of silently overflowing. */
    public void credit(long amount) {
        requirePositive(amount);
        this.balance = Math.addExact(this.balance, amount);
    }

    /** DEBIT: removes money. A customer account is never allowed to go below zero. */
    public void debit(long amount) {
        requirePositive(amount);
        if (kind == AccountKind.CUSTOMER && balance < amount) {
            throw new IllegalStateException("Insufficient funds");
        }
        this.balance = Math.subtractExact(this.balance, amount);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public AccountKind getKind() {
        return kind;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}