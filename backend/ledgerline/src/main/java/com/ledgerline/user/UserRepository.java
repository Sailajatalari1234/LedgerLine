package com.ledgerline.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    // Spring writes: SELECT ... FROM users WHERE username = ?
    // Optional means "a user, or nothing if the username doesn't exist".
    Optional<User> findByUsername(String username);
}