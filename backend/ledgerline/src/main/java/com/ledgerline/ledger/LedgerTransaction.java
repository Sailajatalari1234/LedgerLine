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

/**
 * One money event. @Immutable tells Hibernate that a transaction row is never changed
 * after it has been saved. History must not be rewritten.
 */
@Entity
@Immutable
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    // Paise.
    @Column(nullable = false)
    private long amount;

    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(TransactionType type, long amount, Long initiatedBy, String description) {
        this.type = type;
        this.amount = amount;
        this.initiatedBy = initiatedBy;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}