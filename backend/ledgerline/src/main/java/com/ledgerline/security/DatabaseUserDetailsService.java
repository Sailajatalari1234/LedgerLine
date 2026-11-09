package com.ledgerline.security;

import com.ledgerline.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Tells Spring Security how to find a user when someone tries to log in.
 * It reads the user from our PostgreSQL "users" table.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        com.ledgerline.user.User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        // We write the full name "com.ledgerline.user.User" because Spring Security
        // also has a class called User, and Java needs to know which one we mean.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())   // ADMIN becomes the authority ROLE_ADMIN
                .disabled(!user.isEnabled())
                .build();
    }
}