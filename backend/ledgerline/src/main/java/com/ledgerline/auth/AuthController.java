package com.ledgerline.auth;

import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** What the frontend receives about the logged-in person. Never includes the password hash. */
    public record MeResponse(Long id, String username, String fullName, String role) {
    }

    /** Open endpoint. Calling it makes the server send the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    /**
     * "Who am I?" The answer comes from the server-side session,
     * never from anything the browser sends. Not logged in gives 401.
     */
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new MeResponse(user.getId(), user.getUsername(), user.getFullName(), user.getRole().name());
    }
}