package com.ledgerline.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tests for deposits, withdrawals, double-entry, idempotency and concurrency. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MoneyOperationsTest {

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

        clearingAccount.id(); // create the bank's clearing account up front
        accountAId = accountRepository.save(new Account(holderAId, "A Savings")).getId();
        accountBId = accountRepository.save(new Account(holderBId, "B Savings")).getId();
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    /** Children first. The safety check lives here so it always runs before any delete. */
    private void cleanAll() {
        assertThat(datasourceUrl).endsWith("ledgerline_test");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }

    // ---------- helpers ----------

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private ResultActions send(String operation, Long accountId, MockHttpSession session,
                               String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/accounts/" + accountId + "/" + operation)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (session != null) {
            request = request.session(session);
        }
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private static String amountJson(long paise) {
        return "{\"amountPaise\":" + paise + ",\"description\":\"test\"}";
    }

    private long balanceOf(Long accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private int countOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** The three double-entry rules that must ALWAYS hold. */
    private void assertLedgerConsistent() {
        // 1. All balances in the whole system add up to exactly zero.
        Long total = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM accounts", Long.class);
        assertThat(total).isZero();

        // 2. Every transaction has equal credits and debits.
        assertThat(countOf("SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries "
                + "GROUP BY transaction_id HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) <> 0) x"))
                .isZero();

        // 3. Every account balance equals the sum of its own ledger entries.
        assertThat(countOf("SELECT count(*) FROM accounts a WHERE a.balance <> COALESCE("
                + "(SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) "
                + "FROM ledger_entries e WHERE e.account_id = a.id), 0)"))
                .isZero();
    }

    // ---------- deposits and withdrawals ----------

    @Test
    void depositIncreasesBalanceAndWritesBalancedEntries() throws Exception {
        MockHttpSession session = login("holdera");

        send("deposits", accountAId, session, "dep-key-0001", amountJson(10000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.balancePaise").value(10000));

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
        assertThat(countOf("SELECT count(*) FROM ledger_entries")).isEqualTo(2);
        assertLedgerConsistent();
    }

    @Test
    void withdrawalReducesBalance() throws Exception {
        moneyService.deposit(holderAId, accountAId, 10000, "seed", "seed-key-0001");
        MockHttpSession session = login("holdera");

        send("withdrawals", accountAId, session, "wd-key-0001", amountJson(4000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balancePaise").value(6000));

        assertThat(balanceOf(accountAId)).isEqualTo(6000L);
        assertLedgerConsistent();
    }

    @Test
    void overdraftIsRejectedAndBalanceUnchanged() throws Exception {
        moneyService.deposit(holderAId, accountAId, 5000, "seed", "seed-key-0001");
        MockHttpSession session = login("holdera");

        send("withdrawals", accountAId, session, "wd-key-0001", amountJson(6000))
                .andExpect(status().is(422));

        assertThat(balanceOf(accountAId)).isEqualTo(5000L);
        assertThat(countOf("SELECT count(*) FROM transactions WHERE type = 'WITHDRAWAL'")).isZero();
        assertLedgerConsistent();
    }

    // ---------- idempotency ----------

    @Test
    void sameKeySameRequestReturnsOriginalResponseAndMovesMoneyOnce() throws Exception {
        MockHttpSession session = login("holdera");

        MvcResult first = send("deposits", accountAId, session, "dep-key-0001", amountJson(10000))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = send("deposits", accountAId, session, "dep-key-0001", amountJson(10000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
        assertThat(countOf("SELECT count(*) FROM transactions")).isEqualTo(1);
        assertLedgerConsistent();
    }

    @Test
    void sameKeyDifferentAmountIsRejected() throws Exception {
        MockHttpSession session = login("holdera");

        send("deposits", accountAId, session, "dep-key-0001", amountJson(10000))
                .andExpect(status().isCreated());
        send("deposits", accountAId, session, "dep-key-0001", amountJson(20000))
                .andExpect(status().is(422));

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
        assertThat(countOf("SELECT count(*) FROM transactions")).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        MockHttpSession session = login("holdera");

        send("deposits", accountAId, session, null, amountJson(10000))
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(accountAId)).isZero();
    }

    // ---------- bad input ----------

    @Test
    void zeroNegativeAndDecimalAmountsAreRejected() throws Exception {
        MockHttpSession session = login("holdera");

        send("deposits", accountAId, session, "bad-key-0001", amountJson(0))
                .andExpect(status().isBadRequest());
        send("deposits", accountAId, session, "bad-key-0002", amountJson(-5))
                .andExpect(status().isBadRequest());
        send("deposits", accountAId, session, "bad-key-0003", "{\"amountPaise\":10.5}")
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(accountAId)).isZero();
        assertThat(countOf("SELECT count(*) FROM transactions")).isZero();
    }

    // ---------- authorization ----------

    @Test
    void holderCannotUseAnotherHoldersAccount() throws Exception {
        MockHttpSession holderB = login("holderb");

        // 404, exactly like an account that does not exist.
        send("deposits", accountAId, holderB, "dep-key-0001", amountJson(10000))
                .andExpect(status().isNotFound());
        send("withdrawals", accountAId, holderB, "wd-key-0001", amountJson(100))
                .andExpect(status().isNotFound());

        assertThat(balanceOf(accountAId)).isZero();
        assertThat(countOf("SELECT count(*) FROM transactions")).isZero();
    }

    @Test
    void adminAndAuditorCannotMoveMoneyAndAnonymousIsRejected() throws Exception {
        send("deposits", accountAId, login("admin1"), "dep-key-0001", amountJson(100))
                .andExpect(status().isForbidden());
        send("deposits", accountAId, login("auditor1"), "dep-key-0002", amountJson(100))
                .andExpect(status().isForbidden());
        send("deposits", accountAId, null, "dep-key-0003", amountJson(100))
                .andExpect(status().isUnauthorized());

        assertThat(countOf("SELECT count(*) FROM transactions")).isZero();
    }

    // ---------- concurrency ----------

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        moneyService.deposit(holderAId, accountAId, 10000, "seed", "seed-key-0001");

        // 8 withdrawals of 3000 start at the same instant against a balance of 10000.
        // Exactly 3 can succeed (9000). The other 5 must be refused.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String key = String.format("race-key-%04d", i);
            futures.add(pool.submit(() -> {
                startSignal.await();
                try {
                    moneyService.withdraw(holderAId, accountAId, 3000, "race", key);
                    return "OK";
                } catch (InsufficientFundsException e) {
                    return "REFUSED";
                }
            }));
        }
        startSignal.countDown();

        int succeeded = 0;
        int refused = 0;
        for (Future<String> future : futures) {
            String result = future.get(60, TimeUnit.SECONDS);
            if (result.equals("OK")) {
                succeeded++;
            } else {
                refused++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3);
        assertThat(refused).isEqualTo(5);
        assertThat(balanceOf(accountAId)).isEqualTo(1000L);
        assertThat(countOf("SELECT count(*) FROM transactions WHERE type = 'WITHDRAWAL'")).isEqualTo(3);
        assertLedgerConsistent();
    }

    @Test
    void concurrentIdenticalRequestsMoveMoneyOnce() throws Exception {
        // The same request (same key) is sent 5 times at the same instant.
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MoneyOutcome>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startSignal.await();
                return moneyService.deposit(holderAId, accountAId, 5000, "dup", "same-key-0001");
            }));
        }
        startSignal.countDown();

        Set<Long> transactionIds = new HashSet<>();
        int replays = 0;
        for (Future<MoneyOutcome> future : futures) {
            MoneyOutcome outcome = future.get(60, TimeUnit.SECONDS);
            transactionIds.add(outcome.response().transactionId());
            if (outcome.replayed()) {
                replays++;
            }
        }
        pool.shutdown();

        assertThat(transactionIds).hasSize(1);
        assertThat(replays).isEqualTo(4);
        assertThat(balanceOf(accountAId)).isEqualTo(5000L);
        assertThat(countOf("SELECT count(*) FROM transactions")).isEqualTo(1);
        assertLedgerConsistent();
    }

    // ---------- accounting ----------

    @Test
    void ledgerStaysBalancedAfterMixedOperations() throws Exception {
        moneyService.deposit(holderAId, accountAId, 50000, "a1", "mix-key-0001");
        moneyService.deposit(holderBId, accountBId, 20000, "b1", "mix-key-0002");
        moneyService.withdraw(holderAId, accountAId, 12345, "a2", "mix-key-0003");
        moneyService.withdraw(holderBId, accountBId, 20000, "b2", "mix-key-0004");

        assertThat(balanceOf(accountAId)).isEqualTo(37655L);
        assertThat(balanceOf(accountBId)).isZero();
        assertLedgerConsistent();
    }
}