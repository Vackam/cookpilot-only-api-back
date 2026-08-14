# AGENTS.md

Guidance for AI coding agents working in this repository. Canonical file — `CLAUDE.md` imports it.

## What this is

CookPilot backend — the server for a voice-driven cooking assistant (Flutter client). MVP scope is one loop: run a recipe, get contextual AI feedback while cooking, write a post-cook review, and derive a personal version of the recipe from that review. Spring Boot 4.1.0, Java 21, Gradle, PostgreSQL.

## Commands

```bash
./gradlew build                                          # compile + test
./gradlew test                                           # all tests
./gradlew test --tests '*PersonalRecipeDeriveTest'       # single test class
./gradlew bootRun                                        # run, no DB (default profile)
./gradlew bootRun --args='--spring.profiles.active=db'   # run against postgres
docker compose up --build                                # app + postgres together
```

Health check: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}` (port 8080).

Tests that hit the DB (`*ApiTest`, `CoreSchemaPersistenceTest`) use Testcontainers and **need a running Docker daemon**. `./gradlew test` runs each test class exactly once. Do not run `MainApplicationTests` via Gradle — it is a `@Suite` for one-click "run everything" in the IDE only, and `build.gradle` excludes it from the Gradle `test` task to avoid double execution.

## Two-profile design (important)

The default (no-profile) context **runs without a database** — Flyway off, JPA `ddl-auto: none`, tests use an embedded H2 just to boot the web context for mock/web tests. Real schema work happens only under the `db` profile: Flyway runs the migrations and JPA is `validate` (mapping vs. real schema, never mutating). Server/prod deploy always sets `SPRING_PROFILES_ACTIVE=db`.

**Flyway is the source of truth for the schema; JPA entities only validate against it.** When you change an entity's mapping you must add a matching migration under `src/main/resources/db/migration` — a mismatch fails startup under `db` with `SchemaManagementException` (that's why `build.gradle` sets `testLogging.exceptionFormat = 'full'`, so the failing column is visible).

## Architecture

Package-by-feature under `com.cookpilot.backend`, each feature a thin `Controller → Service → Repository` slice:

- **recipe** — original recipes/ingredients/steps. Read-only; originals are immutable seed data.
- **review** — post-cook reviews (JPA). Saved first; a personal version back-references it via `source_review_id`.
- **personalrecipe** — the heart of the app (see below).
- **ai** — `AiFeedbackService` returns **fixed mock data**. STT/TTS/LLM is unconfirmed; no real LLM call, no server-side cook session (the client owns session/timer state and posts only the result). Look for `TODO(AI 확정 후)`.
- **user** — `UserService.getCurrentUser()` returns **one hardcoded mock user** (`00000000-0000-0000-0000-000000000001`). No auth yet.
- **common** — `GlobalExceptionHandler` maps exceptions to RFC-7807 `ProblemDetail`: `NotFoundException`→404, `IllegalArgumentException`→400, `IllegalStateException`→409. Throw these; don't build error responses by hand.

### Personal recipe versioning — the diff model

A personal version does **not** snapshot the whole recipe. It stores a **relational diff** against the original recipe in `personal_ingredient_adjustments` / `personal_step_adjustments`, each row typed `ADD` / `MODIFY` / `REMOVE`:

- **Diffs are always cumulative against the ORIGINAL recipe** — never a parent chain. Rendering a version = original + that version's diff only. `DiffComposer` is the pure function that composes them (no DB, no parent replay). `MODIFY` overrides specified fields; omitted fields retain the original. `amount` additionally treats an explicit JSON `null` as removing the amount. `ADD` carries its own full data with no original reference; `REMOVE`/`MODIFY` must FK-reference an original row (enforced by DB `CHECK` and re-validated in the service).
- **Deriving** a new version copies the parent's diffs, applies edits, saves with `parent_version_id` for lineage. Versions stack `v1, v2, v3…` per `(user, recipe)`; the newest becomes `is_default` (previous default is demoted).
- The tree is **append-only and immutable**: there are no edit/delete APIs for a version by design — a change is a new derived leaf. The diff contract is **explicit**: the client/AI declares `ADD`/`MODIFY`/`REMOVE`; the server validates but never name-matches. Duplicate-name `ADD` is allowed ("one more egg").

`PersonalRecipeService.derive()` exists at the service level; its public HTTP route is deliberately not finalized yet.

## API conventions

All routes under `/api/v1`. Plain DTO JSON in/out (Java `record` DTOs, distinct from JPA `*Entity` classes). Errors are `ProblemDetail`. Existing endpoints: `GET /users/me`, `GET /recipes?page=0&size=10` (paged: `{items, page, size, totalElements, hasNext}`), `GET /recipes/{id}`, `POST /reviews`, `GET /reviews/{id}`, `GET /recipes/{id}/reviews`, `GET /recipes/{id}/personal-versions`, `GET /personal-versions/{id}`, `POST /ai-feedback`.

## Gotchas (Spring Boot 4 / Jackson 3 / Testcontainers 2)

Boot 4 splits autoconfig into per-module starters — e.g. `flyway-core` alone won't run migrations without `spring-boot-flyway`. MockMvc autoconfig moved to `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. Jackson 3 lives under `tools.jackson.databind.*`. Testcontainers 2.0 renamed coordinates to `org.testcontainers:testcontainers-postgresql` (version pinned explicitly in `build.gradle` since Boot 4 only manages the core). `PostgresApiTestBase` shares one singleton postgres container and cached Spring context across DB test classes, so **data persists between test classes** — assert counts loosely (`>=`) and give each class its own recipe fixture.

## 문서화 규칙

### 브랜치별 이해 자료 (필수)
작업 브랜치마다 `docs/<브랜치명>.md` 를 만들어 해당 PR/브랜치를 이해하기 위한 자료를 남긴다.

- 파일명: 브랜치명의 `/` 를 `-` 로 치환. 예: `feat/personal-table` → `docs/feat-personal-table.md`
- 목적: 리뷰어·미래의 나·AI 가 코드 안 읽고 무엇을 왜 어떻게 바꿨는지 파악.
- 최소 포함: (1) 무엇을 왜, (2) 핵심 설계 결정과 근거, (3) 스키마/API 변경, (4) 알려진 약점·후속.

## Deploy

Push to `main` → CI builds an ARM64 image to `ghcr.io/cook-pilot/backend:latest` → Watchtower on the VPS polls and swaps the `app` container automatically. Postgres has no Watchtower label (won't auto-update). DB connection is all env vars (`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`), swappable without rebuild. See `README.md` for one-time VPS setup.

## Working rules

### 1. Think Before Coding
Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

State your assumptions explicitly. If uncertain, ask.
If multiple interpretations exist, present them - don't pick silently.
If a simpler approach exists, say so. Push back when warranted.
If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First
Minimum code that solves the problem. Nothing speculative.

No features beyond what was asked.
No abstractions for single-use code.
No "flexibility" or "configurability" that wasn't requested.
No error handling for impossible scenarios.
If you write 200 lines and it could be 50, rewrite it.
Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.


### 3. Surgical Changes
Touch only what you must. Clean up only your own mess.

When editing existing code:

Don't "improve" adjacent code, comments, or formatting.
Don't refactor things that aren't broken.
Match existing style, even if you'd do it differently.
If you notice unrelated dead code, mention it - don't delete it.
When your changes create orphans:

Remove imports/variables/functions that YOUR changes made unused.
Don't remove pre-existing dead code unless asked.
The test: Every changed line should trace directly to the user's request.


### 4. Goal-Driven Execution
Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

"Add validation" → "Write tests for invalid inputs, then make them pass"
"Fix the bug" → "Write a test that reproduces it, then make it pass"
"Refactor X" → "Ensure tests pass before and after"
For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

