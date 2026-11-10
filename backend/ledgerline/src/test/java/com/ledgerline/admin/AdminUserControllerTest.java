package com.ledgerline.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Tests for POST /api/admin/users (user onboarding). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserControllerTest {

    private static final String PASSWORD = "Test-Password-123";
    private static final String NEW_PASSWORD = "A-Strong-Pass-123";

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

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String json(String username, String password, String fullName, String role) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"fullName\":\"" + fullName + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void adminCanCreateUserAndNewUserCanLogIn() throws Exception {
        MockHttpSession admin = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newuser1", NEW_PASSWORD, "New User", "ACCOUNT_HOLDER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser1"))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));

        // The new person can log in, and the server reports their role from the database.
        MockHttpSession newUser = login("newuser1", NEW_PASSWORD);
        mockMvc.perform(get("/api/auth/me").session(newUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
    }

    @Test
    void createdUserPasswordIsStoredAsHash() throws Exception {
        MockHttpSession admin = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newuser2", NEW_PASSWORD, "New User Two", "AUDITOR")))
                .andExpect(status().isCreated());

        User saved = userRepository.findByUsername("newuser2").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(saved.getPasswordHash()).startsWith("$2");
    }

    @Test
    void responseNeverContainsPassword() throws Exception {
        MockHttpSession admin = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newuser3", NEW_PASSWORD, "New User Three", "ACCOUNT_HOLDER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void accountHolderCannotCreateUsers() throws Exception {
        MockHttpSession holder = login("holder1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(holder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("sneaky1", NEW_PASSWORD, "Sneaky", "ADMIN")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.existsByUsername("sneaky1")).isFalse();
    }

    @Test
    void auditorCannotCreateUsers() throws Exception {
        MockHttpSession auditor = login("auditor1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(auditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("sneaky2", NEW_PASSWORD, "Sneaky", "ADMIN")))
                .andExpect(status().isForbidden());

        assertThat(userRepository.existsByUsername("sneaky2")).isFalse();
    }

    @Test
    void anonymousCannotCreateUsers() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("sneaky3", NEW_PASSWORD, "Sneaky", "ADMIN")))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.existsByUsername("sneaky3")).isFalse();
    }

    @Test
    void duplicateUsernameIsRejectedWithConflict() throws Exception {
        MockHttpSession admin = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("holder1", NEW_PASSWORD, "Copycat", "ACCOUNT_HOLDER")))
                .andExpect(status().isConflict());
    }

    @Test
    void tooShortPasswordIsRejected() throws Exception {
        MockHttpSession admin = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("weakling1", "short", "Weak Password", "ACCOUNT_HOLDER")))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.existsByUsername("weakling1")).isFalse();
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        MockHttpSession admin = login("admin1", PASSWORD);

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("badrole1", NEW_PASSWORD, "Bad Role", "SUPERUSER")))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.existsByUsername("badrole1")).isFalse();
    }
}