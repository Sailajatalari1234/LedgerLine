# Threat model

For each threat: what we do about it, how it is tested, and what is still weak. Tests are in `backend/ledgerline/src/test/java`.

| Threat | Mitigation | Tested by | Gap |
|---|---|---|---|
| Duplicate or retried request moves money twice | Idempotency-Key required on every money write. UNIQUE (user_id, key) in the database. The key is reserved in the same transaction as the money movement. Replays return the original result | MoneyOperationsTest, TransferTest (sequential and concurrent same-key tests) | Keys never expire |
| Same key reused with different data | Request hash stored. A different hash gives 422 | MoneyOperationsTest.sameKeyDifferentAmountIsRejected | None known |
| Two withdrawals race and overdraw an account | SELECT ... FOR UPDATE row locks inside one database transaction. Overdraft check after locking. CHECK (balance >= 0) as a last defence | concurrentWithdrawalsNeverOverdraw (8 threads, exactly 3 succeed), concurrentTransfersCannotOverdrawTheSource | Tested against local PostgreSQL only |
| Deadlock between opposite transfers | Accounts always locked in ascending id order | oppositeTransfersAtTheSameTimeDoNotDeadlock (20 simultaneous transfers) | None known |
| Money created or lost | Double-entry: every transaction has equal debits and credits. The tests check that all balances sum to 0 and that each balance equals the sum of its entries | assertLedgerConsistent in the money and transfer tests | The clearing account represents money outside the system, so it is negative by design |
| Floating point or rounding errors | Money is BIGINT paise. Math.addExact and subtractExact. Decimal input is rejected | zeroNegativeAndDecimalAmountsAreRejected | None known |
| Reading or spending from another person's account (IDOR) | Ownership checked on the server for every account request. Other people's accounts return 404 | AccountControllerTest, MoneyOperationsTest, TransferTest | None known |
| Privilege escalation | Role comes from the database through the session. URL rules plus checks in the controllers. Holders cannot name another owner. Only ADMIN creates users | AuthSecurityTest, AdminUserControllerTest | None known |
| CSRF | Cookie token required on every write request | AuthSecurityTest (login without a token is rejected) | None known |
| Stolen password database | BCrypt hashes only | passwordsAreStoredAsBcryptHashes | No password reset flow |
| Username guessing | Same error for unknown user and wrong password | unknownUserGetsSameErrorAsWrongPassword | Timing differences were not measured |
| Brute-force password guessing | Not mitigated | None | NO rate limiting. Redis rate limiting was required later and is not built |
| Audit log tampering | Database triggers reject UPDATE, DELETE and TRUNCATE. Only ADMIN and AUDITOR can read it. Bodies and passwords are never stored | AuditLogTest | The database owner can drop the trigger. A limited runtime role is not set up. The app currently connects as the owner |
| Failed or refused actions going unnoticed | The audit filter records 401 and 403 attempts and failed logins | AuditLogTest | Reads (GET) are not audited |
| SQL injection | Spring Data and parameterised JDBC queries only | Code review. No dedicated test | None known |
| Secrets in the repository | Passwords come from environment variables. A search of the repository found none | git grep of the test password | A database password was shown in screenshots during development and was reset afterwards |
| Oversized or malicious input | Validation: amount range, username pattern, password length, description length | AdminUserControllerTest, MoneyOperationsTest | No request size limit beyond defaults |
| Session theft | HttpOnly and SameSite=Lax cookie, Secure when deployed, 30 minute timeout | The live response headers show Secure, HttpOnly and SameSite=Lax | No automated test of the Secure flag |
| Service unavailable | Health endpoint. Free-tier hosting sleeps | Health test | No monitoring or alerting |