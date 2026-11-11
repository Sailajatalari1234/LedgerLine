package com.ledgerline.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.List;
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

/** Tests for transfers, concurrency between transfers, history and statements. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferTest {

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

        clearingAccount.id();
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

    private ResultActions transfer(MockHttpSession session, String key, Long from, Long to, long paise) throws Exception {
        String body = "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to
                + ",\"amountPaise\":" + paise + ",\"description\":\"test\"}";
        return mockMvc.perform(post("/api/transfers")
                .with(csrf())
                .session(session)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long balanceOf(Long accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private int countOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private void assertLedgerConsistent() {
        Long total = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM accounts", Long.class);
        assertThat(total).isZero();

        assertThat(countOf("SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries "
                + "GROUP BY transaction_id HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) <> 0) x"))
                .isZero();

        assertThat(countOf("SELECT count(*) FROM accounts a WHERE a.balance <> COALESCE("
                + "(SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) "
                + "FROM ledger_entries e WHERE e.account_id = a.id), 0)"))
                .isZero();
    }

    private void seed(Long holderId, Long accountId, long paise, String key) {
        moneyService.deposit(holderId, accountId, paise, "seed", key);
    }

    // ---------- transfers ----------

    @Test
    void transferMovesMoneyAndKeepsLedgerBalanced() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        MockHttpSession session = login("holdera");

        transfer(session, "tr-key-0001", accountAId, accountBId, 3000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.balancePaise").value(7000));

        assertThat(balanceOf(accountAId)).isEqualTo(7000L);
        assertThat(balanceOf(accountBId)).isEqualTo(3000L);
        assertLedgerConsistent();
    }

    @Test
    void transferMoreThanBalanceIsRejectedAndNothingChanges() throws Exception {
        seed(holderAId, accountAId, 5000, "seed-key-0001");
        MockHttpSession session = login("holdera");

        transfer(session, "tr-key-0001", accountAId, accountBId, 6000)
                .andExpect(status().is(422));

        assertThat(balanceOf(accountAId)).isEqualTo(5000L);
        assertThat(balanceOf(accountBId)).isZero();
        assertThat(countOf("SELECT count(*) FROM transactions WHERE type = 'TRANSFER'")).isZero();
        assertLedgerConsistent();
    }

    @Test
    void cannotSpendFromAnotherHoldersAccount() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        MockHttpSession holderB = login("holderb");

        // Holder B tries to move money OUT of holder A's account.
        transfer(holderB, "tr-key-0001", accountAId, accountBId, 1000)
                .andExpect(status().isNotFound());

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
        assertThat(balanceOf(accountBId)).isZero();
    }

    @Test
    void transferToSameAccountIsRejected() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        MockHttpSession session = login("holdera");

        transfer(session, "tr-key-0001", accountAId, accountAId, 1000)
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
    }

    @Test
    void transferToUnknownOrSystemAccountIsRejected() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        MockHttpSession session = login("holdera");

        transfer(session, "tr-key-0001", accountAId, 999999999L, 1000)
                .andExpect(status().isNotFound());
        transfer(session, "tr-key-0002", accountAId, clearingAccount.id(), 1000)
                .andExpect(status().isNotFound());

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
        assertLedgerConsistent();
    }

    @Test
    void adminCannotTransfer() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");

        transfer(login("admin1"), "tr-key-0001", accountAId, accountBId, 1000)
                .andExpect(status().isForbidden());

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
    }

    @Test
    void sameKeyTransferMovesMoneyOnlyOnce() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        MockHttpSession session = login("holdera");

        MvcResult first = transfer(session, "tr-key-0001", accountAId, accountBId, 2500)
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = transfer(session, "tr-key-0001", accountAId, accountBId, 2500)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(balanceOf(accountAId)).isEqualTo(7500L);
        assertThat(balanceOf(accountBId)).isEqualTo(2500L);
        assertThat(countOf("SELECT count(*) FROM transactions WHERE type = 'TRANSFER'")).isEqualTo(1);
        assertLedgerConsistent();
    }

    // ---------- concurrency ----------

    @Test
    void oppositeTransfersAtTheSameTimeDoNotDeadlock() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        seed(holderBId, accountBId, 10000, "seed-key-0002");

        // 20 transfers at once: 10 from A to B and 10 from B to A. Each is Rs 1.00.
        // Locking in ascending id order means they can never wait for each other forever.
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                startSignal.await();
                String key = String.format("opp-key-%04d", n);
                if (n % 2 == 0) {
                    moneyService.transfer(holderAId, accountAId, accountBId, 100, "a to b", key);
                } else {
                    moneyService.transfer(holderBId, accountBId, accountAId, 100, "b to a", key);
                }
                return "OK";
            }));
        }
        startSignal.countDown();

        for (Future<String> future : futures) {
            assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        pool.shutdown();

        assertThat(balanceOf(accountAId)).isEqualTo(10000L);
        assertThat(balanceOf(accountBId)).isEqualTo(10000L);
        assertThat(countOf("SELECT count(*) FROM transactions WHERE type = 'TRANSFER'")).isEqualTo(20);
        assertLedgerConsistent();
    }

    @Test
    void concurrentTransfersCannotOverdrawTheSource() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");

        // 8 transfers of 3000 at once from an account holding 10000: exactly 3 may succeed.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String key = String.format("race-key-%04d", i);
            futures.add(pool.submit(() -> {
                startSignal.await();
                try {
                    moneyService.transfer(holderAId, accountAId, accountBId, 3000, "race", key);
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
            if (future.get(60, TimeUnit.SECONDS).equals("OK")) {
                succeeded++;
            } else {
                refused++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3);
        assertThat(refused).isEqualTo(5);
        assertThat(balanceOf(accountAId)).isEqualTo(1000L);
        assertThat(balanceOf(accountBId)).isEqualTo(9000L);
        assertLedgerConsistent();
    }

    // ---------- history and statements ----------

    @Test
    void historyListsEntriesNewestFirst() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        moneyService.withdraw(holderAId, accountAId, 4000, "cash", "wd-key-0001");
        MockHttpSession session = login("holdera");

        mockMvc.perform(get("/api/accounts/" + accountAId + "/transactions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$[0].balanceAfterPaise").value(6000))
                .andExpect(jsonPath("$[1].type").value("DEPOSIT"));
    }

    @Test
    void holderCannotSeeAnotherHoldersHistoryButAuditorCan() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");

        mockMvc.perform(get("/api/accounts/" + accountAId + "/transactions").session(login("holderb")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/" + accountAId + "/transactions").session(login("auditor1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void statementShowsOpeningAndClosingBalance() throws Exception {
        seed(holderAId, accountAId, 10000, "seed-key-0001");
        moneyService.withdraw(holderAId, accountAId, 4000, "cash", "wd-key-0001");
        MockHttpSession session = login("holdera");

        mockMvc.perform(get("/api/accounts/" + accountAId + "/statement").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalancePaise").value(0))
                .andExpect(jsonPath("$.closingBalancePaise").value(6000))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].type").value("DEPOSIT"));
    }
}