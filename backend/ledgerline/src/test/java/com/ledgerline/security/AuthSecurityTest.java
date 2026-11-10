package com.ledgerline.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests for login, logout, sessions, CSRF and role-based access.
 * Every test starts with the same three users in an otherwise empty users table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityTest {

    private static final String PASSWORD = "Test-Password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @BeforeEach
    void setUp() {
        // SAFETY: refuse to delete anything unless we are on the test database.
        assertThat(datasourceUrl).endsWith("ledgerline_test");

        userRepository.deleteAll();
        userRepository.save(new User("admin1", passwordEncoder.encode(PASSWORD), "Admin One", Role.ADMIN));
        userRepository.save(new User("holder1", passwordEncoder.encode(PASSWORD), "Holder One", Role.ACCOUNT_HOLDER));
        userRepository.save(new User("auditor1", passwordEncoder.encode(PASSWORD), "Auditor One", Role.AUDITOR));
    }

    /** Logs in through the real login endpoint and returns the server-side session. */
    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void meWithoutLoginIs401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenMeReturnsRoleFromServer() throws Exception {
        MockHttpSession session = login("holder1", PASSWORD);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("holder1"))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
    }

    @Test
    void wrongPasswordIs401WithJsonError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "admin1")
                        .param("password", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Invalid username or password\"}"));
    }

    @Test
    void unknownUserGetsSameErrorAsWrongPassword() throws Exception {
        // Same answer for "no such user" and "wrong password", so attackers cannot find real usernames.
        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "nobody")
                        .param("password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Invalid username or password\"}"));
    }

    @Test
    void loginWithoutCsrfTokenIsRejectedAndNotLoggedIn() throws Exception {
        // Correct username and password, but no CSRF token: must be rejected (a 4xx answer)...
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", "admin1")
                        .param("password", PASSWORD))
                .andExpect(status().is4xxClientError())
                .andReturn();

        // ...and whatever session came back must NOT be logged in.
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        if (session != null) {
            mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isUnauthorized());
        } else {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void adminCanReachAdminArea() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        // No controller exists at this path yet, so "allowed" shows up as 404, not 401 or 403.
        mockMvc.perform(get("/api/admin/anything").session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountHolderCannotReachAdminOrAuditArea() throws Exception {
        MockHttpSession session = login("holder1", PASSWORD);

        mockMvc.perform(get("/api/admin/anything").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/anything").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorCanReachAuditAreaButNotAdminArea() throws Exception {
        MockHttpSession session = login("auditor1", PASSWORD);

        mockMvc.perform(get("/api/audit/anything").session(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/anything").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotReachAdminArea() throws Exception {
        mockMvc.perform(get("/api/admin/anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        // The old session must no longer work.
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        User admin = userRepository.findByUsername("admin1").orElseThrow();

        assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(admin.getPasswordHash()).startsWith("$2");
    }

    @Test
    void meResponseNeverContainsPasswordHash() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}