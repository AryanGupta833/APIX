# APiX implementation progress

This note tracks the requested “Ultimate Master Implementation Prompt.” It is not a release-readiness certification.

## WebSocket suite implementation plan (2026-10-14)

Current audit: both repositories already contain broad unstaged/untracked feature work and no staged changes; it is preserved. HTTP suites, asynchronous persistence/cancellation, owner-scoped backend WebSocket sessions, outbound destination enforcement, and frontend suite editor are already present. The WebSocket workbench currently depends on the request user's thread context and exposes only manual send plus retained-frame polling. Suite definitions and results are JSON in existing LONGTEXT fields, so adding a protocol discriminator and message evidence should not require a database migration.

Implementation steps: (1) extend the backward-compatible suite case/result DTOs with an optional protocol and bounded WebSocket scenario steps; (2) add owner-explicit internal workbench operations for a background worker to await handshake, send frames, await ordered inbound frames, and close; (3) execute each scenario within the existing suite worker, case timeout, cancellation flag/thread interruption, failure policy, per-case persistence, and run progress; (4) validate the step/action/assertion model and redacted evidence; (5) update the existing editor/results view and focused tests/docs. WebSocket session limits and the existing destination policy remain authoritative. No session or interaction will be replayed after restart.

## WebSocket suite implementation result (2026-10-14)

- Implemented backward-compatible HTTP/WebSocket suite DTOs. HTTP cases missing the protocol field continue to run as HTTP. WebSocket cases validate `ws`/`wss` URL syntax, bounded headers/timeouts/step counts/frame sizes, ordered `SEND_TEXT`, `SEND_JSON`, and `RECEIVE` operations, and allowed text/JSON receive assertions.
- Integrated background suite runs with explicit owner-scoped operations in `WebSocketWorkbenchService`. The existing backend transport and its connection-time destination policy remain in use. The suite path waits on bounded handshakes/frames, uses the existing cancellation interruption, evaluates assertions, persists per-case evidence/progress, and closes sessions in `finally`. It returns transport/timeouts as `ERROR`, assertion mismatch as `FAILED`, and applies `STOP_ON_FAILURE` or `CONTINUE` consistently.
- Added exact runtime secret redaction to WebSocket target/handshake/message/assertion evidence. Existing TLS verification, SSRF policy, workbench frame/resource limits, request tracing for existing HTTP work, and HTTP suite execution were not bypassed. An uncertain interaction during process failure is not retried; existing startup recovery retains completed results and marks interrupted active runs `ERROR`.
- Extended the existing `Suites` frontend editor and result/history component. HTTP request/assertion controls are preserved; WebSocket fields and receive evidence are shown in the same suite/run UI. A component test covers saving ordered steps and displaying returned message assertions.
- No migration is needed: the case definitions and results use existing JSON text columns. No production database was accessed.
- Added deterministic suite tests for scenario validation, ordered send/receive/assertion behavior, redaction, receive timeout, cancellation while waiting for a frame, cleanup, owner isolation, and preservation of existing HTTP tests. Added workbench tests for ordered frames, handshake-header redaction, owner checks, interruptible waiting, and close state. The real local echo integration test continues to fail before bind in this Windows environment because Netty WEPoll cannot establish its loopback selector pipe; this is an environment limitation, not a passing transport test.

Verification for this implementation is recorded after completion below. The preceding “follow-up audit” and “work completed” sections describe earlier historical turns and should not be read as the current feature status.

## Reusable API suite task audit (2026-10-14)

The requested reusable HTTP suite vertical slice is already present in this worktree; this audit did not recreate it or modify the pre-existing implementation. The current code persists owner-scoped suite definitions and runs, validates and redacts definitions, evaluates deterministic HTTP/JSON assertions, executes through `RequestExecutionService`, persists each completed case, handles ordered dependencies and failure policies, exposes paginated owner-scoped history/cancellation/comparison routes, and provides a frontend suite editor and result/history view. Performance comparisons delegate to `PerformanceRegressionService`. AI-generated suite authoring is omitted as optional in the task. The local agent and WebSocket work present elsewhere in the worktree were not modified as part of this suite task.

Audit verification:

- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=ApiAssertionServiceTest,ApiTestSuiteServiceTest,PerformanceComparisonServiceTest,SensitiveDataRedactorTest' test` — passed (30 tests, 0 failures/errors/skips).
- `mvn '-Dmaven.repo.local=target/m2' -DskipTests compile` — passed; Maven reported sources up to date.
- `npx tsc --noEmit` — passed (exit 0).
- `npm test -- --run src/pages/Suites.test.tsx` — blocked before Vitest loaded: esbuild could not read the OneDrive parent directory (`Cannot read directory "../../../../..": Access is denied`).
- Backend `git diff --check` and frontend `git diff --check` (with `core.whitespace=cr-at-eol`) — passed.
- `APIX_TEST_DATASOURCE_URL` is not configured in this environment; persistence integration tests were not run in this audit. The README records earlier disposable MySQL 8.4.11 migration and persistence verification; that is historical evidence, not a rerun here.

No source changes were needed after review. Remaining limits include in-JVM-only active-run admission/worker coordination, no AI-generated test authoring, and blocked frontend Vitest/build execution in the OneDrive checkout.

## Local-agent implementation result (2026-10-14)

Priority A is implemented to the verified extent below. Priority B (reusable WebSocket suite cases) remains outstanding. This is an explicit incomplete result, not a production-readiness claim.

- Added persisted owner-scoped agent and pairing entities, repositories, management/pairing/channel endpoints, and the one-time migration `20261014_02_local_test_agents.sql`. Pairing codes and agent credentials are random and stored server-side only as SHA-256 hashes; pairing consumption is a conditional database update. User management/HTTP execution checks ownership, revocation rejects credentials, and pair/channel routes use the agent credential separately from user authentication.
- Added an agent-only structured HTTP job path. It does not fall through to the backend public outbound client. The standalone Node 20+ CLI has exact loopback host/port grants, checks DNS answers at connection time, rejects redirects, bounds request/response bytes and duration, and has no shell, proxy, or arbitrary filesystem API. Pair credentials are prompted without echo; the resulting bearer credential is persisted in the agent's dedicated config file with restrictive POSIX permissions (Windows relies on the user's profile ACL).
- Added frontend pairing/revocation and agent selection to the HTTP workbench and suite HTTP case editor. Existing public outbound destination policy was left intact.
- The queue/channel and online tracking are process-local; persisted agent identities do not imply cross-instance job delivery. Jobs are not persisted/replayed. A request may have taken effect if the connection is lost before its result arrives. Local WebSocket execution is not supported.
- The new agent migration is intentionally one-time and has not been exercised against MySQL in this continuation. Validate it against a disposable schema matching the target MySQL version before deployment. Never run it directly against production without the documented backup/review procedure.
- The existing WebSocket workbench remains separate from reusable suite execution. Integrating it into background suite workers requires an owner-scoped non-request-thread API, cancellation propagation, bounded receive semantics and deterministic connection cleanup; that integration and its regression tests were not completed here.

Verification for the agent implementation:

- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=LocalTestAgentServiceTest,ApiTestSuiteServiceTest,OutboundDestinationPolicyTest,WebSocketWorkbenchServiceTest#invalidOrUnapprovedTargetsAreRejectedBeforeAClientConnectionIsStarted+invalidProtocolHeadersAreRejectedWithoutOpeningASession+sessionsAreNotAccessibleAcrossOwners+sessionCreationReturnsBeforeHandshakeAndCancellationDisposesPendingConnection' test` — passed (36 tests, 0 failures/errors/skips).
- `mvn '-Dmaven.repo.local=target/m2' -DskipTests compile` — passed earlier in this pass; 100 Java source files compiled.
- `npm test` in `local-agent/` — passed (4 tests).
- `node --check apix-agent.mjs` and `node --check test/agent.test.mjs` — passed.
- `npx tsc --noEmit` in the frontend repository — passed (exit 0).
- Frontend `npm test -- --run` and `npm run build` — blocked at esbuild startup because it cannot read the OneDrive parent directory (`Cannot read directory "../../../../..": Access is denied`). No Vitest or production-build pass is claimed.
- Backend and frontend full persistence/context integration have not been rerun for the agent migration. The database migration is therefore unverified; MySQL compatibility and FK/schema matching remain deployment validation requirements.

## Local-agent continuation audit and plan (2026-10-14)

### Audit findings

- Both repository working trees already contain broad unstaged/untracked feature work. No staged changes were present. Those changes are preserved.
- Backend-mediated WebSocket sessions, the shared secured Reactor Netty client, exact-host public/local destination policy, owner-scoped HTTP suites, asynchronous run persistence/cancellation, and migrations are present. The recent asynchronous WebSocket handshake/cancel behavior and its test are also present.
- The Local Testing Agent, pairing persistence, credential authentication/revocation, local-agent request channel, and WebSocket suite case model/execution are absent.
- `RequestExecutionService` owns ordinary HTTP validation, outbound execution, history, tracing, and analysis. Its destination policy must remain unchanged for public execution; local requests require an explicit agent-only route and cannot fall through to this client.
- Authentication uses the existing current-user service and `/apix/**` JWT/OAuth security chain. Agent credentials require a separate hashed-token authenticator; raw credentials must never enter logs or URL parameters.
- Persistence uses JPA with `ddl-auto=update` plus documented one-time manual MySQL migrations for managed databases. New lifecycle records therefore need explicit migration SQL and schema instructions.
- The frontend has existing suite and WebSocket pages, API helpers, and Vitest scripts (`test: vitest run`). Frontend check history still includes the observed OneDrive/esbuild access denial and stalled elevated runs.

### Planned implementation

1. Add persisted owner-scoped agents and short-lived single-use pairing credentials; store only hashes, consume pairings atomically, and add immediate revocation.
2. Add a bounded, process-local polling/job channel with explicit status, timeouts, safe restart/disconnect behavior, and a separate agent-only HTTP route. The agent enforces an exact loopback-host/port grant at URL parse and connection resolution; it has no proxy, shell, or filesystem API. Ordinary HTTP remains on the existing secured client.
3. Add a standalone dependency-free Node agent CLI that reads pairing input without command-line secrets, stores its bearer credential only in its dedicated agent config file, and executes only bounded structured requests under its local allowlist.
4. Integrate agent pairing/list/revoke and opt-in agent execution in the frontend and ordinary HTTP workbench, without persisting the pairing secret in browser storage.
5. Add WebSocket case definitions to the existing suite schema/executor only if they can reuse the current run lifecycle and close connections on every outcome; keep their results in existing per-case evidence.
6. Add deterministic lifecycle/policy/protocol tests first, then run the available Maven, TypeScript, Vitest, build, persistence, and diff checks. Record platform blockers without weakening policy.

The agent channel and work admission are intentionally planned as single-JVM/process-local for this deployment; no horizontal scaling guarantee will be claimed. Pairing and agent records require the checked-in managed-database migration before deploying to a schema managed outside Hibernate.

## Follow-up audit (2026-10-14)

The continuation request was inspected against the current source tree and frontend. The backend-mediated WebSocket workbench described below is implemented; the older historical statements below that say it is absent refer to the earlier browser-direct-only pass. The following remain incomplete and must not be treated as delivered: Local Testing Agent pairing/revocation and authenticated agent transport, agent-side target grants and safe local HTTP execution, agent integration with the HTTP workbench/suites, agent-mediated WebSockets, and WebSocket cases in the reusable suite engine.

The WebSocket session implementation is authenticated by the existing `/apix/**` security chain and checks session ownership on list/read/send/close. It reuses the instrumented Reactor Netty `HttpClient`, including its connection-time destination resolver, redirect/retry policy, and TLS defaults. Sessions are process-local and ephemeral. It is not an agent tunnel and cannot reach developer loopback destinations under the default policy. The UI retains browser-direct mode, which is not covered by backend DNS/SSRF policy. During this audit, session creation was changed to return `CONNECTING` immediately; the UI polls while connecting and can cancel once it receives the session ID. A server-side handshake timeout disposes a still-pending transport.

Verification rerun:

- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=OutboundDestinationPolicyTest,WebSocketWorkbenchServiceTest#invalidOrUnapprovedTargetsAreRejectedBeforeAClientConnectionIsStarted+invalidProtocolHeadersAreRejectedWithoutOpeningASession+sessionsAreNotAccessibleAcrossOwners+sessionCreationReturnsBeforeHandshakeAndCancellationDisposesPendingConnection,ApiTestSuiteServiceTest,AIAnalysisServiceTest,SensitiveDataRedactorTest' test` — passed after the async-handshake change, 35 tests, 0 failures/errors/skips. Main and test classes were up to date on this invocation; the focused pending-session test had already compiled and passed separately.
- `npx tsc --noEmit` — passed after the latest frontend cancel-control and test changes.
- Backend and frontend `git diff --check` — passed after the latest changes (frontend check uses `core.whitespace=cr-at-eol`).
- `npm test -- --run src/pages/WebSocketWorkbench.test.tsx` — sandbox run failed because esbuild was denied access to `../../../../..`; an elevated retry started Vitest but produced no test output and was stopped. No frontend test result is claimed.
- `npm run build` — sandbox run failed with the same parent-directory access denial; elevated retry reached Vite `transforming...` but stalled without a result and was stopped. No production build result is claimed.
- The controlled local WebSocket echo test remains unverified: rerun failed before server bind because Netty could not create its Windows WEPoll selector loopback pipe. Do not interpret the passing unit/policy tests as end-to-end validation.

The local agent and WebSocket suite execution were not implemented in this pass. Implementing them as a superficial extension of process-local WebSocket sessions would bypass the required pairing credential lifecycle, agent-side destination enforcement, and persisted suite/cancellation semantics. This continuation therefore remains incomplete; these requirements need implementation and independent end-to-end verification before deployment.

## Continuation implementation pass (2026-10-14)

### Implemented

- Added backend-mediated ephemeral WebSocket sessions at `POST/GET /apix/websocket/sessions`, `GET /{id}`, `POST /{id}/messages`, and `DELETE /{id}`. All endpoints are under the existing authenticated `/apix/**` chain, and service reads/sends/closes enforce the owning user's ID.
- Added `ws`/`wss` URL validation by applying the existing HTTP(S) URL preflight and connection-time `PolicyAddressResolverGroup`; the session client is built from the same instrumented Reactor Netty `HttpClient` used for HTTP. Redirects/retries stay disabled and TLS verification remains the client's default.
- Added process-local limits (3 active per user, 20 global), 10-second handshake wait, 10-minute lifetime, 64 KiB frames, 500 total messages, bounded 100-message outbound queue, 200 retained incoming text frames, shutdown cleanup, and generic connection errors.
- Session handshake headers and received messages are redacted; secret-looking request-header values are also redacted from echoed text. Sessions and frames are memory-only and are not replayed after restart.
- Extended the frontend to default to backend mode, configure handshake headers, poll owner-scoped session state, send/close through authenticated APIs, and restore an active backend session after page refresh. Browser-direct mode remains available with its weaker security boundary clearly described.
- Added unit/policy tests and a controlled local WebSocket echo integration test. Fixed a `ws`/`wss` scheme replacement defect found by the first test run.
- Updated README API, limits, policy, session persistence, browser-direct, and verification notes.

### Not implemented

- No Local Testing Agent, pairing/revocation APIs, agent credential protocol, agent-side target grants, or agent HTTP execution exists yet.
- WebSocket suite cases are not integrated with the existing asynchronous suite lifecycle.
- The controlled echo integration test is present but cannot run in this Windows environment because Netty cannot create its WEPoll selector loopback pipe. The echo connection itself has therefore not been verified end to end here.
- No schema migration was needed for ephemeral sessions. No production database was used or modified.

### Verification for this pass

- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=OutboundDestinationPolicyTest,WebSocketWorkbenchServiceTest#invalidOrUnapprovedTargetsAreRejectedBeforeAClientConnectionIsStarted+invalidProtocolHeadersAreRejectedWithoutOpeningASession+sessionsAreNotAccessibleAcrossOwners,ApiTestSuiteServiceTest,AIAnalysisServiceTest,SensitiveDataRedactorTest' test` — passed (34 tests, 0 failures/errors/skips). This invocation compiled 93 backend source files and the test sources.
- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=WebSocketWorkbenchServiceTest#controlledLocalWebSocketEchoUsesPolicyConfiguredTransportAndRedactsReceivedSecrets' test` — blocked/failed before the server bound: `failed to create a child event loop` / `Unable to establish loopback connection` / `WEPollSelectorImpl` `Invalid argument: connect`. This is the known environment socket issue, not a passing integration test.
- `npx tsc --noEmit` in the frontend repository — passed after backend-session UI integration.
- `npm test -- --run src/pages/WebSocketWorkbench.test.tsx` — remains blocked because esbuild cannot traverse the OneDrive checkout; no frontend test result is claimed.
- `npm run build` was attempted in the previous pass; the elevated invocation stalled at Vite `transforming...` and was stopped. No production build result is claimed.
- Backend `git diff --check` passed. Frontend `git diff --check` passed with `core.whitespace=cr-at-eol` for the repository's CRLF working files.
- Maven still reports duplicate MySQL connector, WebFlux starter, and OpenTelemetry API dependency declarations in `pom.xml`; untouched.

## Continuation plan recorded 2026-10-14

1. Implement backend-mediated WebSocket sessions using Spring's `ReactorNettyWebSocketClient` constructed with the existing instrumented Reactor Netty `HttpClient`; this reuses its checked DNS resolver, disabled redirects/retries, TLS defaults, connection/timeouts, and destination policy.
2. Validate `ws://` and `wss://` targets via a dedicated WebSocket URL validator and enforce the same address policy on the actual connection resolver. Keep local/private targets disallowed unless the existing exact-host local allowlist is explicitly configured.
3. Expose authenticated owner-scoped ephemeral session APIs with bounded duration, frames/messages, concurrent sessions, and safe close; do not persist frame contents or credentials.
4. Extend the current page to select backend-mediated mode and poll its own session only after backend confirmation. Keep browser-direct mode clearly labeled as distinct and weaker.
5. Add deterministic unit and controlled-server integration tests where the Windows socket environment permits.
6. In a separate phase, design agent pairing/credentials/local loopback execution, integrate agent-selected HTTP suite cases, then decide how agent-local WebSocket execution and reusable WebSocket suite cases fit without bypassing the agent-side target policy.

No Local Testing Agent, pairing endpoint, or WebSocket suite case should be claimed complete until its independent credential, target authorization, cancellation, and controlled-server tests exist.

## Work completed in this pass

- Inspected the continuation prompt, this progress note, README, worktree status, backend authentication/JWT and outbound policy, suite lifecycle, and frontend routes/API client.
- Added an authenticated-route browser WebSocket workbench at `/websocket`: explicit connect/disconnect, text/JSON send, bounded in-page records, size checks, connection timeout, session-duration cap, cleanup on unmount, and conservative blocking of obvious local/private URL hosts.
- Added a deterministic frontend component test using a controlled fake WebSocket. The source type-checks, but Vitest could not complete in this OneDrive environment.
- Documented the browser-direct trust boundary and limitations: no APiX backend proxy/SSRF enforcement, DNS-answer validation, handshake status/header access, arbitrary handshake headers, binary send, persisted sessions, or suite execution.
- No Local Testing Agent, pairing APIs, agent execution path, server-side WebSocket transport, or WebSocket suite case was implemented in this pass. The separate agent still needs a reviewed authenticated protocol and agent-side destination enforcement.
- Also retained the prior pass's AI redaction improvement: suite-provided `ApiRequest.sensitiveValues` are redacted before AI-provider handoff, with a passing backend regression test.

## Verification in this pass

- `mvn '-Dmaven.repo.local=target/m2' -DskipTests compile` — first invocation failed in javac while opening a dependency JAR under the OneDrive checkout; a retry reported `BUILD SUCCESS` but `Nothing to compile`, so this does not conclusively verify a clean rebuild of the latest edits.
- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=AIAnalysisServiceTest,ApiTestSuiteServiceTest,SensitiveDataRedactorTest,OutboundDestinationPolicyTest,PolicyAddressResolverGroupTest' test` — passed after an elevated retry (32 tests, 0 failures/errors/skips), including the new AI redaction regression. The initial sandboxed attempt failed before tests because javac could not open the Byte Buddy JAR under `target/m2`.
- `git diff --check` — passed.
- `npx tsc --noEmit` in `APIX_Frontend/friendly-frontend-helper` — passed.
- `npm test -- --run src/pages/WebSocketWorkbench.test.tsx` — blocked in the sandbox because esbuild could not access a parent directory. Elevated retry initialized Vitest but emitted no test results and was stopped after a bounded wait; no frontend tests are claimed as passed.
- `npm run build` — blocked in the sandbox by esbuild parent-directory access. Elevated retry reached Vite's `transforming...` stage but emitted no completion within the bounded wait and was stopped; no production build is claimed as passed.
- `git diff --check` — passed in both backend and frontend repositories.
- The repository already documents previous passing focused backend runs, disposable MySQL 8.4.11 migration/persistence verification, and prior TypeScript checking. Those are historical evidence from earlier task turns, not rerun in this pass.

`mvn '-Dmaven.repo.local=target/m2' -DskipTests compile` — the first invocation failed while javac opened a Spring Data JPA JAR; a retry exited successfully but reported `Nothing to compile`. The successful focused Maven test recompiled test sources and exercised the changed AI service class, but a clean standalone compilation was not independently established. Maven also reports duplicate MySQL connector, WebFlux starter, and OpenTelemetry API dependency declarations in `pom.xml`; these were not changed here.

## Repository state and verified boundaries

- The existing HTTP suite runner persists queued/progress/terminal states, claims runs with a conditional database update, and records incomplete active runs as `ERROR` during startup recovery without replaying outbound requests. Its worker pool, queue, user admission lock, and startup recovery are process-local. The README documents single-container deployment and the need for distributed admission/ownership/recovery before horizontal scaling.
- The checked-in manual schema migrations and MySQL validation are documented in `README.md`; do not rerun the one-time migration against production or a schema where its columns already exist.
- HTTP execution, suites, and performance requests use the existing shared HTTP execution path. No client-side WebSocket workbench or Local Testing Agent implementation was found in the inspected backend source. The README explicitly lists the localhost agent, WebSocket scenarios, natural-language test plans, mixed-protocol scenarios, and CI runner as unimplemented.
- Frontend source/build status, the entire AI provider lifecycle, and deployment artifacts have not been fully re-audited in this pass. Existing reported OneDrive/esbuild access-denied limitations remain unverified here.

## Outstanding work and next actions

1. Design the Local Testing Agent as a separate process using authenticated outbound communication, single-use pairing, hashed/revocable agent credentials, per-user target grants, and agent-side loopback/port enforcement. Do not implement it as a proxy or unrestricted tunnel.
2. Decide how the Spring MVC/JWT security chain authenticates persistent agent WebSocket connections without placing credentials in query strings or logs; add protocol, persistence, and integration tests before exposing it.
3. Integrate explicitly selected agent execution into request and suite execution, including cancellation, disconnect uncertainty, progress/results, and redaction.
4. Add backend-mediated WebSocket suite execution using the same explicit agent path for loopback destinations, then add controlled-server end-to-end tests. Do not route remote WebSocket targets around destination validation.
5. Add effective DNS policy to browser-direct sessions or reconsider their architecture; the UI's obvious-host blocking cannot enforce DNS-answer or connection-time policy.
6. Re-run backend and frontend checks and MySQL persistence tests against a disposable database only. Keep prior DB evidence as historical until rerun.
7. Complete security, end-to-end, and deployment verification before describing the system as a release candidate.

## Current assessment

The backend has substantive HTTP execution, reusable-suite, asynchronous-run, performance-regression, persistence-migration, and outbound HTTP security work. A browser-direct WebSocket UI slice now exists, but this broad implementation request is incomplete: the Local Testing Agent, backend-secured WebSocket connections, WebSocket suite execution, DNS enforcement for browser-direct connections, and controlled-server end-to-end verification remain outstanding. Frontend type checking passed, while Vitest and production build remain blocked/stalled in the OneDrive checkout. Do not claim production readiness.
