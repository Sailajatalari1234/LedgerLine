package com.ledgerline.admin;

import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * User onboarding. SecurityConfig already restricts /api/admin/** to the ADMIN role.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        // Only the BCrypt hash is stored. The real password is never saved or logged.
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                request.role());

        try {
            // saveAndFlush sends the INSERT now, so a database uniqueness error appears here.
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two admins creating the same username at the same moment: the database still protects us.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        return UserResponse.from(user);
    }
}