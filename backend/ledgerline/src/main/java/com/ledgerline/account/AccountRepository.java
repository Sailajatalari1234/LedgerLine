package com.ledgerline.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOwnerIdOrderById(Long ownerId);

    // Looks up an account only if it belongs to this owner. This is the ownership check.
    Optional<Account> findByIdAndOwnerId(Long id, Long ownerId);

    List<Account> findByKindOrderById(AccountKind kind);

    // Only asks "does it exist?" and does NOT load the account, so it cannot leave a stale copy behind.
    boolean existsByIdAndOwnerId(Long id, Long ownerId);

    // SELECT ... FOR UPDATE: other transactions must wait until this one finishes.
    // We use it before changing any balance.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}