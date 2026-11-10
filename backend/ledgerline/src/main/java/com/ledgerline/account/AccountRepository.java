package com.ledgerline.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOwnerIdOrderById(Long ownerId);

    // Looks up an account only if it belongs to this owner. This is the ownership check.
    Optional<Account> findByIdAndOwnerId(Long id, Long ownerId);
}