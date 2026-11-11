package com.ledgerline.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.user.Role;
import com.ledgerline.user.User;
import com.ledgerline.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Checks the database rules of the ledger tables, going around our Java code on purpose. */
@SpringBootTest
@ActiveProfiles("test")
class LedgerSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ClearingAccount clearingAccount;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @BeforeEach
    void setUp() {
        cleanAll();
    }

    @AfterEach
    void tearDown() {
        cleanAll();
    }

    /** Deletes in the right order (children first). The safety check lives HERE, so it always runs. */
    private void cleanAll() {
        assertThat(datasourceUrl).endsWith("ledgerline_test");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }

    private User newHolder() {
        return userRepository.save(new User("owner1", "not-a-real-hash", "Owner One", Role.ACCOUNT_HOLDER));
    }

    @Test
    void clearingAccountIsCreatedOnceAndReused() {
        long first = clearingAccount.id();
        long second = clearingAccount.id();

        assertThat(second).isEqualTo(first);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM accounts WHERE kind = 'SYSTEM'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void systemAccountMayGoNegativeButCustomerAccountMayNot() {
        long clearingId = clearingAccount.id();
        Account customer = accountRepository.save(new Account(newHolder().getId(), "Savings"));

        // Allowed: the bank's clearing account mirrors customer money, so it goes negative.
        int updated = jdbcTemplate.update("UPDATE accounts SET balance = -500 WHERE id = ?", clearingId);
        assertThat(updated).isEqualTo(1);

        // Refused by the database itself.
        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE accounts SET balance = -1 WHERE id = ?", customer.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void customerAccountMustHaveAnOwner() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (owner_id, name, kind, balance) VALUES (NULL, 'Orphan', 'CUSTOMER', 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerEntryAmountMustBePositive() {
        User holder = newHolder();
        Account account = accountRepository.save(new Account(holder.getId(), "Savings"));
        Long transactionId = jdbcTemplate.queryForObject(
                "INSERT INTO transactions (type, amount, initiated_by) VALUES ('DEPOSIT', 100, ?) RETURNING id",
                Long.class, holder.getId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, balance_after) "
                        + "VALUES (?, ?, 'CREDIT', 0, 0)",
                transactionId, account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}