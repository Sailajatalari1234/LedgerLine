package com.ledgerline.bootstrap;

import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Runs once every time the app starts. If the users table is completely empty,
 * it creates the first ADMIN so that someone can log in.
 * The password comes from the ADMIN_PASSWORD environment variable, never from code or Git.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          @Value("${ledgerline.bootstrap.admin-username:admin}") String adminUsername,
                          @Value("${ledgerline.bootstrap.admin-password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return; // users already exist, so do nothing
        }
        if (adminPassword == null || adminPassword.length() < MIN_PASSWORD_LENGTH) {
            log.warn("No users exist and ADMIN_PASSWORD is missing or shorter than {} characters. "
                    + "No admin was created.", MIN_PASSWORD_LENGTH);
            return;
        }
        // encode() turns the real password into a BCrypt hash. Only the hash is stored.
        User admin = new User(adminUsername, passwordEncoder.encode(adminPassword),
                "System Administrator", Role.ADMIN);
        userRepository.save(admin);
        log.info("Created first admin user '{}'.", adminUsername);
    }
}