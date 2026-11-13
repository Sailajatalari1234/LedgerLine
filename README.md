# LedgerLine (BalanceVault)

A secure money-movement backend: accounts, deposits, withdrawals and transfers with double-entry bookkeeping, row locking, idempotency keys, role-based access and an append-only audit log.

Built for the Snovasys RISE 2026 hiring challenge. The organizers later said the challenge was renamed BalanceVault; this repository is the same project.

- **Live API:** https://ledgerline-ctg6.onrender.com (health check: /api/health)
- **Code:** https://github.com/Sailajatalari1234/LedgerLine

## Honest status

| Area | Status |
|---|---|
| Session login, logout, BCrypt, CSRF, roles (ADMIN, ACCOUNT_HOLDER, AUDITOR) | Done, automated tests pass |
| User onboarding (admin creates users), first-admin bootstrap from an environment variable | Done, tested |
| Accounts with server-side ownership checks | Done, tested |
| Deposit, withdrawal, transfer with SELECT ... FOR UPDATE, ordered locking, double-entry | Done, tested (including concurrent tests) |
| Idempotency-Key (unique constraint, request hash, replay, different-payload rejection) | Done, tested |
| Append-only audit log (database trigger) and audit filter | Done, tested |
| Transaction history and statements | Done, tested |
| Deployment (Render and Neon) | Deployed. Verified live on 12 Nov 2026: admin login and GET /api/auth/me work |
| Web page | A single-file page (backend/ledgerline/src/main/resources/static/index.html) served by the backend, with forms for login, accounts, deposit, withdraw, transfer, history, admin user creation and the audit log. It loads and shows the login form; the forms were only lightly tested. This is NOT a React dashboard. The React and Tailwind project in /frontend is only a starter page and is not used |
| Redis rate limiting | NOT done |
| Separate database roles (migration role vs limited runtime role) | NOT done. The app connects as the database owner |
| Prisma | NOT used. This project uses Java, Spring Data JPA and Flyway instead |
| Demo video | NOT recorded |

71 automated tests pass locally (Maven, against a local PostgreSQL 17 test database). The tests have not been run against the Neon database. Money operations were not tried through the live deployment, only login and identity.

## Stack

Java 21 target (built with JDK 26), Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway, PostgreSQL, Maven, JUnit 5 and MockMvc. Deployed on Render (Docker) with Neon PostgreSQL.

## Architecture

- `user`: users table, roles, repository.
- `security`: Spring Security configuration (session login, CSRF cookie, URL rules).
- `admin`: admin-only user creation.
- `account`: accounts, ownership checks, row-lock query.
- `ledger`: transactions, ledger entries, money service (deposit, withdrawal, transfer), statements.
- `audit`: audit filter, audit service, read-only audit endpoint.
- `bootstrap`: creates the first admin from environment variables when the users table is empty.
- Database changes are Flyway migrations V1 to V5 in `backend/ledgerline/src/main/resources/db/migration`.

Money is stored as BIGINT paise (1 rupee = 100 paise). No floating point is used for money.

## Run locally

Requirements: JDK 21 or newer, Maven, PostgreSQL.

1. Create a database and user, for example `ledgerline` and `ledgerline_app`. For tests, also create `ledgerline_test`.
2. Set environment variables: `DB_PASSWORD` (database password) and `ADMIN_PASSWORD` (at least 12 characters, for the first admin).
3. From `backend/ledgerline`: `mvn spring-boot:run`
4. Tests: `mvn test` (needs the `ledgerline_test` database)

Optional variables: `DB_URL`, `DB_USER`, `DB_POOL_SIZE`, `COOKIE_SECURE`, `PORT`, `ADMIN_USERNAME`.

## API

All write requests need the CSRF token: read the `XSRF-TOKEN` cookie from `GET /api/auth/csrf` and send it back in the `X-XSRF-TOKEN` header. Money amounts are `amountPaise`, a whole number.

| Method and path | Who | Notes |
|---|---|---|
| GET /api/health | anyone | database check |
| GET /api/auth/csrf | anyone | sets the CSRF cookie |
| POST /api/auth/login | anyone | form fields `username`, `password` |
| POST /api/auth/logout | logged in | ends the session |
| GET /api/auth/me | logged in | identity and role from the server session |
| POST /api/admin/users | ADMIN | create a user (password 12 to 72 characters) |
| POST /api/accounts | ADMIN, ACCOUNT_HOLDER | holders create for themselves; admins must give `ownerId` |
| GET /api/accounts, GET /api/accounts/{id} | logged in | holders see only their own; admins and auditors see all |
| POST /api/accounts/{id}/deposits | ACCOUNT_HOLDER (owner) | needs `Idempotency-Key` header |
| POST /api/accounts/{id}/withdrawals | ACCOUNT_HOLDER (owner) | needs `Idempotency-Key` header |
| POST /api/transfers | ACCOUNT_HOLDER (owner of source) | body: fromAccountId, toAccountId, amountPaise; needs `Idempotency-Key` |
| GET /api/accounts/{id}/transactions | owner, ADMIN, AUDITOR | newest first |
| GET /api/accounts/{id}/statement | owner, ADMIN, AUDITOR | optional `from` and `to` dates |
| GET /api/audit/logs | ADMIN, AUDITOR | newest first |

Status codes: 401 not logged in, 403 role not allowed, 404 account not found or not yours, 422 insufficient funds or idempotency key reused with a different request, 400 invalid input.

## Security decisions

- Identity and role always come from the server-side session, never from request data.
- Someone else's account returns 404, the same as a missing account.
- Passwords are stored as BCrypt hashes (work factor 12). Login errors do not reveal whether a username exists.
- CSRF protection with a cookie token. Session cookie is HttpOnly, SameSite=Lax and Secure in production.
- Database constraints back up the code: CHECK balance >= 0 for customer accounts, positive amounts, foreign keys, unique idempotency keys.
- The audit log rejects UPDATE, DELETE and TRUNCATE through database triggers. Request bodies and passwords are never logged.
- See THREATS.md for the full list and the known gaps.

## Deployment

Render builds `backend/ledgerline/Dockerfile` and runs it against a Neon PostgreSQL database. Environment variables on Render: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_PASSWORD`, `COOKIE_SECURE=true`, `DB_POOL_SIZE`, `JAVA_TOOL_OPTIONS`. Flyway applies the migrations on startup. The free tier sleeps when idle, so the first request after a pause can take up to two minutes.

## Known limitations

See the status table above. In addition: the audit-log protection can be removed by the database owner (who can drop the trigger), which is why a separate limited runtime database role is the next planned step.