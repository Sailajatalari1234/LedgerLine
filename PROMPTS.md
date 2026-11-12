# AI usage log

AI tool used: Claude (a chat conversation, guided step by step). I created and pasted the files myself, ran every command, and sent the output back. The prompts below are summaries, not word-for-word copies.

## Main prompts (summarised)

1. A long build prompt: build LedgerLine from scratch in Java 21, Spring Boot, PostgreSQL, Flyway, React and Tailwind, with sessions, CSRF, roles, double-entry, row locking, idempotency, an audit log, tests and docs. Do not invent results or claim untested features.
2. Work step by step: one step at a time, complete files, exact commands, expected output, wait for my output, fix errors before continuing.
3. During development: asking to explain errors, asking for complete corrected files, asking for deployment steps, and asking for honest documents.

## Mistakes the AI made, and how they were found

- **A test expected the wrong status.** The AI said a login without a CSRF token would return 403, and the real server returned 401. Its first test then expected 401 but the test framework returned 403. I sent the failing output, and the test was rewritten to check that the login is rejected and no session is logged in.
- **Decimal amounts were accepted.** The first version of the money request used a Java `long`, and the JSON library silently turned `10.5` into `10` paise. The automated test for bad amounts failed, which exposed the bug. The fix reads the amount as an exact decimal and rejects fractions.
- **Wrong expected test counts.** The AI predicted 52, 64 and 72 tests. The real counts were 51, 63 and 71. The real output was used each time.
- **Unclear paste instructions broke files.** Telling me to "add a line" or "add these lines" led to a broken `SecurityConfig.java` (compile error) and to an `application.properties` that lost its database settings (the app failed with "'url' attribute is not specified"). Both were fixed by replacing the whole file. Instructions became "replace the whole file".
- **Empty files compiled and ran.** Twice, files I had created but not filled caused confusing results (a Flyway migration that created nothing, and an empty `UserRepository.java` causing a compile error). I now check file sizes before building.
- **Wrong Java version advice.** The AI first said Java 21 had to be installed. The project builds with the JDK 26 already on my machine while targeting Java 21 (`java.version` in pom.xml).
- **Requirements mismatch.** An organizer email later asked for Node, Prisma and Redis items. This project uses Java and JPA, and Prisma and Redis were not built. This is stated in the README.
- **A database password was shown in screenshots** while setting up Neon. The password was reset.

## What I verified myself

I ran every build and test, 71 tests pass locally, and on the live deployment I verified admin login and the identity endpoint. I did not run the tests against the Neon database.