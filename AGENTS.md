# ChipTrip Backend Agent Guide

Verified against code on 2026-09-20. This is a navigation and invariant summary, not proof that current code is bug-free. Reply in Vietnamese, including when working directly in this repository.

## Read only what the task needs

1. Inspect `git status --short` and the diff of files you will touch; preserve unrelated edits.
2. Use the task map below, then read the relevant methods, callers, DTOs, repositories and tests. Expand scope only when dependencies require it.
3. If `.codegraph/` exists, use CodeGraph first; otherwise use scoped `rg`. Do not create an index automatically.
4. Consult relevant sections of `CONTEXT.md`, not its full contents by default: section 5 domain model, 6 API, 7.1 generation flow, 7.3 AI schema, 8 conventions. Find headings with `rg -n '^#{1,3} ' CONTEXT.md`. Read applicable ADRs if present.
5. Code/config/tests override stale documentation. Older `CLAUDE.md` notes contain outdated database, retry and test-count claims. Do not repeatedly search for the absent `BACKEND_AI_CONTEXT.md` unless the task supplies it.

## Architecture and task map

Java 21, Spring Boot 4, Maven wrapper, Spring MVC + JPA/Hibernate + SQL Server. WebClient is used for provider HTTP; this is not a reactive end-to-end app. Modular monolith: `common/` infrastructure and `module/<domain>/` features. Keep controllers thin and changes local; no microservices rewrite.

Paths below are relative to `src/main/java/com/tranbac/chiptripbe/`. Unless stated otherwise, service implementations are in `module/<domain>/service/impl/`; repositories, entities and DTOs are siblings in that domain. Tests mirror packages under `src/test/java/`.

| Task | Start here; follow only relevant dependencies |
|---|---|
| Login, OTP, reset, refresh | `module/auth/service/impl/AuthServiceImpl.java`; auth token/OTP repositories; `common/security/JwtAuthFilter.java`, `UserDetailsServiceImpl.java`; `common/config/SecurityConfig.java` |
| Profile, credit, premium | user `UserServiceImpl`, `CreditServiceImpl`, `EntitlementServiceImpl`; `module/user/entity/User.java`, `repository/UserRepository.java` |
| Generate sync/async/status | `module/trip/controller/TripController.java`; trip `TripGenerationLock`, `AsyncTripGenerator`, `TripGenerationStatusTracker`, `TripServiceImpl`, `TripGenerationPersistenceServiceImpl` |
| AI prompt/output/retries | ai `AiGenerateServiceImpl`, `AiSuggestServiceImpl`; `module/ai/service/AiItineraryValidator.java`, `dto/AiItineraryResult.java`; trip `dto/request/GenerateTripRequest.java` |
| Places, maps, photos | place `PlaceEnrichmentServiceImpl`, `PlaceCacheServiceImpl`, `PlacePhotoServiceImpl`, `PlacePhotoRefreshWorker`; `module/geocoding/client/`; `common/resilience/ProviderResilience.java` |
| Enrichment recovery | trip `TripServiceImpl.sweepPendingEnrichments`; `module/trip/scheduler/TripEnrichmentScheduler.java`; Trip/Activity repositories; `common/config/AsyncConfig.java` |
| Alternative suggestions/apply | trip `ActivityAlternativeServiceImpl`; `ActivityAlternativeSessionRepository`, session DTO/entity; credit and place services |
| CRUD, public/shared trips, delete | trip `TripServiceImpl`, `AdminTripServiceImpl`, `ActivityServiceImpl`, `ChecklistServiceImpl`; response mappers and dependent entities |
| Members, expenses, social/reports | trip `TripMemberServiceImpl`, `TripExpenseServiceImpl`, `TripSocialServiceImpl`; moderation `ModerationServiceImpl` |
| Payment/SePay | `module/payment/controller/SepayWebhookController.java`; payment `PaymentServiceImpl`, `OrderServiceImpl`; order/transaction repositories, `config/SepayProperties.java` |
| Chat, notifications, STOMP | chat `ChatServiceImpl`, `R2StorageServiceImpl`; `module/notification/config/` and `service/NotificationEventListener.java`; notification schedulers |
| Flights/weather/stats | `module/flight/`, `module/external/`, `module/stats/`; respective providers and aggregate queries |
| Errors, rate limits, deployment | `common/exception/`, `common/filter/`; `pom.xml`, `src/main/resources/application.yml`, `.github/workflows/ci-cd.yml`, `docs/migrations/` |

## Business invariants: preserve and verify

- Premium is derived from paid credit balance, not `ROLE_PREMIUM`; roles are for RBAC. Backend enforces entitlements, not the frontend.
- `aiCreditUnits` is the precise paid balance: 100 units = 1 credit; generate costs 100, paid alternative swap costs 25 after free quota. Preserve fractional units; `aiCredits` is legacy whole-credit compatibility, not a safe mutation source.
- Generate consumes paid first; current trial query permits trial only when paid balance is zero. Trial uses the Vietnam business date. Do not silently allow trial for a 25/50/75-unit balance or change eligibility policy.
- Consume generation credit atomically in the same transaction as trip persistence. Precheck is not final authorization; persistence must recheck. Failure must roll back both graph and credit.
- Keep LLM/maps HTTP outside DB transactions. Preserve the separate transactional persistence bean; do not wrap orchestration in a broad class-level transaction or lose proxy interception through self-invocation.
- Sync and async generate share a per-user lock; always release on failure. The lock and status tracker are JVM-local, not distributed/durable guarantees.
- Webhook settlement must remain idempotent: conditional PENDING -> PAID, atomic grant and unique provider transaction in one transaction. An exists-check alone is insufficient.
- Trip enrichment persists intent/state, claims work and recovers through a sweeper. Executors are not durable queues; preserve conditional updates that avoid overwriting user edits. Photo refresh is a separate workflow to inspect, not assumed equally durable.
- Provider outcomes FOUND / NOT_FOUND / TEMPORARY_UNAVAILABLE are distinct. Transient failures must not become permanent negative cache. Foreground generate uses `allowScraper=false`; preserve latency budgets and provider cooldowns.
- Enforce ownership/membership and the complete trip/day/activity parent chain. Public/shared views need explicit privacy policy; owner DTOs are not automatically public-safe.
- Public/shared readers use `TripReadOnlyResponse`, never owner detail: no email, share token, members or checklist. Public also omits dates/budget/booking URLs; feed returns `durationDays`. Non-owner cloning must not restore those secrets. `TripShareTokens` rejects legacy links; current tokens start with `v2_`.
- OTP verification runs in an explicit write transaction with pessimistic row locking. Wrong-code rejection is thrown AFTER commit; success consumes OTP and updates account/revokes tokens atomically. Do not add a surrounding transaction that rolls attempts back.
- JWT checks current account status. STOMP denies client SEND, allowlists subscriptions, and `WebSocketAccountGuard` checks active status before outbound MESSAGE delivery to existing sessions. New destinations need an explicit policy and tests.
- Admin grants use `UserRepository.addCredits`, not whole-balance read/modify/save; `@DynamicUpdate` prevents unrelated managed User updates from overwriting credit columns. It does not make arbitrary detached credit writes safe.
- Keep AI prompt/schema, validator, persisted fields and REST DTOs aligned. Missing searchQuery currently has a fallback; do not introduce stricter validation without checking intended behavior.
- Alternatives apply server-stored options under locks with quota/credit and mutation in one transaction. Preserve `generatedAsPremium` snapshot OR current-premium checks where used; do not replace every gate with a role check.

## Critical flows

```text
Generate: controller/lock -> precheck -> AI/validate -> place resolution [no DB tx]
          -> persistence + atomic credit + enrichment intent [one DB tx]
          -> background signal/sweeper -> providers -> conditional DB updates
Payment:  authenticated webhook -> match/validate -> conditional settle
          + atomic credit grant + transaction record [one DB tx]
```

## Verification and operational safety

- From this repo: `.\mvnw.cmd test`; targeted: `.\mvnw.cmd "-Dtest=CreditServiceImplTest" test`; package: `.\mvnw.cmd -B verify`. Offline `-o` works only with cached dependencies.
- Use JDK 21. On this workstation, if Java is unavailable: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'` after checking the directory exists. Do not change global Java settings.
- The September 2026 audit ran 112 existing tests successfully; this is historical, not the current test count or evidence of DB concurrency correctness. Mockito tests do not verify Spring transaction proxies, SQL Server locks/FKs or the security filter chain.
- For transaction/concurrency/schema/security changes, add boundary/integration tests using isolated SQL Server and real Spring proxies as appropriate. Never use production DB, real payments or paid providers merely to run tests.
- `AuthOtpTransactionTest` and `CreditPersistenceTest` use isolated JPA contexts (H2 by default). Run on disposable SQL Server 2022 with `.\mvnw.cmd -B "-Dchiptrip.test.database=sqlserver" verify`; requires Docker and image download. No production config/startup fixers loaded. See `docs/security/p0-implementation-verification.md` for current evidence and rollout compatibility.
- Current config has `ddl-auto:update`, `open-in-view:false`, startup schema fixers and manual SQL under `docs/migrations/`; no versioned migration pipeline was present at verification. Starting the app can mutate its configured DB. Do not boot against an unknown database or run repair SQL casually.
- Do not read/log secrets or capability tokens. Inspect `.env.example` for variable names, not `.env` values. Do not open OSIV to hide lazy-loading/N+1 problems.

## Audit watchpoints: recheck, do not assume fixed

Remaining pointers after the five P0 fixes on 2026-09-20, not standing permission to fix unrelated code:
- Privacy: user search DTO and moderation target visibility still need a separate audit; read-only projection is field-level, not a scrubber for personal information entered into itinerary text.
- Data: inspect all credit writers before adding optimistic locking; admin absolute balance replacement and detached entity merges need their own concurrency policy.
- Jobs/data: delete cleanup for expenses/AI usage; alternative lock ordering; photo REFRESHING recovery; enrichment NOT_FOUND/disabled completion; hotel quotes stored in shared place cache.
- Ops: startup DELETE/DROP-constraint repair, CI accepting health HTTP401, and Boot/springdoc compatibility need validation before release.

## Working agreements

- State important assumptions and tradeoffs; ask when ambiguity changes correctness or scope. Prefer the simpler solution.
- No speculative features/abstractions or unrelated cleanup. Match local style, preserve others' edits, remove only orphans introduced by your change.
- Define success criteria; reproduce bugs with a regression test, implement narrowly, verify and report actual results/limitations.
- Keep this guide compact: update the affected map/invariant when behavior changes. Put long explanations in relevant `CONTEXT.md` sections or ADRs, not a growing session log; do not duplicate full audits here.

## Agent skills

### Issue tracker

Issues live as markdown files under `.scratch/` in this repo. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
