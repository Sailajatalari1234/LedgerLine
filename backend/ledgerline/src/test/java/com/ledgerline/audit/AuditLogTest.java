package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.ledger.ClearingAccount;
import com.ledgerline.ledger.MoneyService;
import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Tests for the append-only audit log, the audit filter and the health endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogTest {

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

    @Autowired
    private MoneyService moneyService;

    @Autowired
    private ClearingAccount clearingAccount;

    @Autowired
    private AuditService auditService;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private Long holderAId;
    private Long holderBId;
    private Long accountAId;
    private Long accountBId;

    @BeforeEach
    void setUp() {
        cleanAll();
        userRepository.save(new User("admin1", passwordEncoder.encode(PASSWORD), "Admin One", Role.ADMIN));
        holderAId = userRepository.save(new User("holdera", passwordEncoder.encode(PASSWORD), "Holder A", Role.ACCOUNT_HOLDER)).getId();
        holderBId = userRepository.save(new User("holderb", passwordEncoder.encode(PASSWORD), "Holder B", Role.ACCOUNT_HOLDER)).getId();
        userRepository.save(new User("auditor1", passwordEncoder.encode(PASSWORD), "Auditor One", Role.AUDITOR));

        clearingAccount.id();
        accountAId = accountRepository.save(new Account(holderAId, "A Savings")).getId();
        accountBId = accountRepository.save(new Account(holderBId, "B Savings")).getId();
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    /** Everything EXCEPT the audit log, which cannot be deleted. The safety check runs first. */
    private void cleanAll() {
        assertThat(datasourceUrl).endsWith("ledgerline_test");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
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

    private long maxAuditId() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
    }

    private List<Map<String, Object>> rowsAfter(long afterId, String action) {
        return jdbcTemplate.queryForList(
                "SELECT actor, status, http_status, idempotency_key FROM audit_log WHERE id > ? AND action = ?",
                afterId, action);
    }

    // ---------- append-only ----------

    @Test
    void auditRowsCannotBeUpdated() {
        auditService.record("test", "TEST ACTION", "/test", "SUCCESS", 200, null, null);
        long id = maxAuditId();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET actor = 'hacker' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);

        String actor = jdbcTemplate.queryForObject("SELECT actor FROM audit_log WHERE id = ?", String.class, id);
        assertThat(actor).isEqualTo("test");
    }

    @Test
    void auditRowsCannotBeDeleted() {
        auditService.record("test", "TEST ACTION", "/test", "SUCCESS", 200, null, null);
        long id = maxAuditId();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void auditLogCannotBeTruncated() {
        auditService.record("test", "TEST ACTION", "/test", "SUCCESS", 200, null, null);

        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE audit_log"))
                .isInstanceOf(DataAccessException.class);

        assertThat(maxAuditId()).isPositive();
    }

    // ---------- what gets recorded ----------

    @Test
    void transferIsAuditedWithActorAndKey() throws Exception {
        moneyService.deposit(holderAId, accountAId, 10000, "seed", "seed-key-0001");
        MockHttpSession session = login("holdera");
        long before = maxAuditId();

        String body = "{\"fromAccountId\":" + accountAId + ",\"toAccountId\":" + accountBId
                + ",\"amountPaise\":1000,\"description\":\"audit test\"}";
        mockMvc.perform(post("/api/transfers")
                        .with(csrf())
                        .session(session)
                        .header("Idempotency-Key", "audit-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        List<Map<String, Object>> rows = rowsAfter(before, "POST /api/transfers");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor")).isEqualTo("holdera");
        assertThat(rows.get(0).get("status")).isEqualTo("SUCCESS");
        assertThat(rows.get(0).get("http_status")).isEqualTo(201);
        assertThat(rows.get(0).get("idempotency_key")).isEqualTo("audit-key-0001");
    }

    @Test
    void failedLoginIsAuditedWithoutThePassword() throws Exception {
        long before = maxAuditId();

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "admin1")
                        .param("password", "wrong-password-value"))
                .andExpect(status().isUnauthorized());

        List<Map<String, Object>> rows = rowsAfter(before, "POST /api/auth/login");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor")).isEqualTo("admin1");
        assertThat(rows.get(0).get("status")).isEqualTo("DENIED");

        // The password must not appear anywhere in the new audit rows.
        Integer leaked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE id > ? AND (actor || action || resource) LIKE '%wrong-password-value%'",
                Integer.class, before);
        assertThat(leaked).isZero();
    }

    @Test
    void forbiddenActionIsAuditedAsDenied() throws Exception {
        MockHttpSession session = login("holdera");
        long before = maxAuditId();

        mockMvc.perform(post("/api/admin/users")
                        .with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sneaky9\",\"password\":\"A-Strong-Pass-123\",\"fullName\":\"S\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        List<Map<String, Object>> rows = rowsAfter(before, "POST /api/admin/users");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actor")).isEqualTo("holdera");
        assertThat(rows.get(0).get("status")).isEqualTo("DENIED");
        assertThat(rows.get(0).get("http_status")).isEqualTo(403);
    }

    // ---------- who may read it ----------

    @Test
    void auditorAndAdminCanReadTheLogButHoldersAndAnonymousCannot() throws Exception {
        auditService.record("test", "TEST ACTION", "/test", "SUCCESS", 200, null, null);

        mockMvc.perform(get("/api/audit/logs").session(login("auditor1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").exists());
        mockMvc.perform(get("/api/audit/logs").session(login("admin1")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/audit/logs").session(login("holdera")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/logs"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- health ----------

    @Test
    void healthEndpointIsOpenAndReportsDatabaseUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }
}