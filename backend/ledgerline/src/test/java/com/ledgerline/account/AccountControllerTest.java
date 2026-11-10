package com.ledgerline.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Tests for account creation, reading and ownership checks. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountControllerTest {

    private static final String PASSWORD = "Test-Password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private Long adminId;
    private Long holderAId;
    private Long holderBId;

    @BeforeEach
    void setUp() {
        // SAFETY: refuse to delete anything unless we are on the test database.
        assertThat(datasourceUrl).endsWith("ledgerline_test");

        // Accounts first, because accounts point at users.
        accountRepository.deleteAll();
        userRepository.deleteAll();

        adminId = userRepository.save(new User("admin1", passwordEncoder.encode(PASSWORD), "Admin One", Role.ADMIN)).getId();
        holderAId = userRepository.save(new User("holdera", passwordEncoder.encode(PASSWORD), "Holder A", Role.ACCOUNT_HOLDER)).getId();
        holderBId = userRepository.save(new User("holderb", passwordEncoder.encode(PASSWORD), "Holder B", Role.ACCOUNT_HOLDER)).getId();
        userRepository.save(new User("auditor1", passwordEncoder.encode(PASSWORD), "Auditor One", Role.AUDITOR));
    }

    @AfterEach
    void cleanUp() {
        // Leave no accounts behind, so other test classes can wipe the users table.
        accountRepository.deleteAll();
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void holderCreatesOwnAccountWithZeroBalance() throws Exception {
        MockHttpSession session = login("holdera");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Savings\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(holderAId))
                .andExpect(jsonPath("$.name").value("Savings"))
                .andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void holderCannotChooseAnotherOwner() throws Exception {
        MockHttpSession session = login("holdera");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sneaky\",\"ownerId\":" + holderBId + "}"))
                .andExpect(status().isBadRequest());

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void auditorCannotCreateAccount() throws Exception {
        MockHttpSession session = login("auditor1");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void anonymousCannotCreateAccount() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCreatesAccountForHolder() throws Exception {
        MockHttpSession session = login("admin1");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Current\",\"ownerId\":" + holderAId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(holderAId));
    }

    @Test
    void adminMustProvideOwner() throws Exception {
        MockHttpSession session = login("admin1");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No Owner\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotAssignAccountToNonHolder() throws Exception {
        MockHttpSession session = login("admin1");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Wrong Owner\",\"ownerId\":" + adminId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void holderSeesOnlyOwnAccounts() throws Exception {
        accountRepository.save(new Account(holderAId, "A Savings"));
        accountRepository.save(new Account(holderBId, "B Savings"));
        MockHttpSession session = login("holdera");

        mockMvc.perform(get("/api/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("A Savings"));
    }

    @Test
    void holderCannotReadAnotherHoldersAccount() throws Exception {
        Account others = accountRepository.save(new Account(holderBId, "B Savings"));
        MockHttpSession session = login("holdera");

        // 404, exactly like an account that does not exist.
        mockMvc.perform(get("/api/accounts/" + others.getId()).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void holderCanReadOwnAccount() throws Exception {
        Account own = accountRepository.save(new Account(holderAId, "A Savings"));
        MockHttpSession session = login("holdera");

        mockMvc.perform(get("/api/accounts/" + own.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A Savings"));
    }

    @Test
    void auditorCanReadAnyAccount() throws Exception {
        Account a = accountRepository.save(new Account(holderAId, "A Savings"));
        accountRepository.save(new Account(holderBId, "B Savings"));
        MockHttpSession session = login("auditor1");

        mockMvc.perform(get("/api/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/accounts/" + a.getId()).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void emptyNameIsRejected() throws Exception {
        MockHttpSession session = login("holdera");

        mockMvc.perform(post("/api/accounts")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void databaseRejectsNegativeBalance() {
        Account saved = accountRepository.save(new Account(holderAId, "A Savings"));

        // Talk to the database directly, bypassing our Java rules: the CHECK constraint must still refuse.
        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE accounts SET balance = -1 WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}