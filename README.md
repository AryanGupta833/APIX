# APiX

APiX is a Spring Boot API execution and observability application. The repository already includes outbound HTTP execution, request history, saved collections, load-test utilities, rule-based diagnostics, AI failure analysis, OpenAPI import, OAuth login, and OpenTelemetry spans.

## Current execution evidence

Each execution now receives an `executionId` UUID. Its API response includes that ID and the OpenTelemetry `traceId`, and the request history row stores both identifiers. Existing history and execution endpoints remain in use. Hibernate's current `spring.jpa.hibernate.ddl-auto=update` setting adds the two nullable history columns on startup; deployments using managed migrations should create nullable `execution_id` (36 characters) and `trace_id` (32 characters) columns on `request_history` before rollout.

Sensitive request headers, common credential fields in JSON text, and credential query parameters are redacted before request history persistence. AI prompts use the same redaction for response text, URL, headers, and trace/error text. Ordinary response display remains unchanged so developers can inspect the response they just received. The current redactor is best-effort for common JSON fields; configure upstream test fixtures to avoid placing secrets in arbitrary non-JSON text.

Phase timing fields are nullable when the corresponding event was not observed. A missing value is not reported as a fabricated measurement. Existing `timingTrace.totalTime` remains the measured end-to-end duration.

## Configuration and running

Configure the existing environment variables `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `OPENROUTER_API_KEY`. The backend defaults to port 8080. MySQL is required by the current application configuration.

### Local Google OAuth

The frontend's Vite server uses `http://localhost:5174`. Start the backend with the Spring `local` profile so it uses port 8081 and redirects a successful login back to the local frontend:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:GOOGLE_CLIENT_ID = "<Google OAuth client ID>"
$env:GOOGLE_CLIENT_SECRET = "<Google OAuth client secret>"
$env:OPENROUTER_API_KEY = "<OpenRouter API key>"
mvn spring-boot:run
```

Set the Google OAuth web client's authorized redirect URI to `http://localhost:8081/login/oauth2/code/google`. Keep the Google client credentials in environment variables; do not commit them. Start the frontend with `npm run dev`. Development builds use `http://localhost:8081` for API requests by default. Override that with `VITE_API_BASE_URL` if the backend uses another address. Deployed builds retain the existing Render API and Vercel OAuth-success destinations unless their environment variables are configured otherwise. To use a different local frontend origin, update the backend CORS allowlist and `APIX_OAUTH_FRONTEND_SUCCESS_URL` together.

For IntelliJ, open **Run → Edit Configurations → your Spring Boot configuration**, set **Active profiles** to `local`, and add the required environment variables. The frontend Vite server is fixed to port 5174 (`strictPort`), separate from the backend. If it reports that port is occupied, resolve that conflict rather than allowing Vite to move onto the backend's port.

Run backend tests with `mvn test` (or `mvnw.cmd test` on Windows). The frontend application is in `APIX_Frontend/friendly-frontend-helper`; use its npm `build`, `lint`, and `test` scripts for frontend checks.

## Baseline performance regression detection

The performance view uses completed, authenticated request-history entries as measured samples. Create a baseline from at least five samples with the same HTTP method, exact stored URL, redacted request body, and request-header names. A comparison must use a later group matching that scenario and must contain at least five complete samples. The sample picker is paginated (up to 100 entries per page); the current frontend displays the first page of 50 matching samples. Credential values are deliberately redacted from history, so this version checks authentication header presence/names but cannot distinguish different credential identities; compare runs that used the same authentication setup.

Latency values are milliseconds. Average latency is the arithmetic mean. p50, p95, and p99 use the nearest-rank percentile method (`ceil(p × n)`, one-based); p50 is included from the minimum five-sample run, while p95 and p99 are unavailable below 20 samples. Error rate is the proportion of samples with HTTP status 400 or above; timeout/network failures recorded by the existing execution service count as errors when they have a status code. Throughput is sample count divided by the time between the first and last recorded completion timestamps; it is unavailable if that interval is zero. These throughput values describe the selected observation window, not server capacity. Request/response sizes are omitted because response bodies and reliable byte counts are not stored in history.

Comparisons are threshold-based alerts, not statistical-significance claims. Latency regression/improvement thresholds are relative to the baseline; throughput uses a relative threshold; error-rate changes use an absolute proportion threshold. A zero latency or throughput baseline makes relative comparison unavailable; zero error rate remains comparable through its absolute threshold. Missing percentiles remain visible as unavailable. Reports preserve baseline/current metric snapshots, thresholds, history IDs, execution IDs, and nonempty trace IDs. Baseline deletion is soft deletion so saved reports remain readable.

Defaults can be changed with `APIX_PERFORMANCE_MINIMUM_SAMPLES` (5), `APIX_PERFORMANCE_LATENCY_THRESHOLD` (0.10, a 10% relative change), `APIX_PERFORMANCE_THROUGHPUT_THRESHOLD` (0.10), and `APIX_PERFORMANCE_ERROR_RATE_THRESHOLD` (0.01, one percentage point). Values are fractions: for example, `0.10` is 10% and `0.01` error rate is 1%.

### API

All routes below require the existing APiX authentication. List endpoints return `{ content, page, size, totalElements, totalPages }`.

```http
GET    /apix/performance/samples?method=GET&url=https%3A%2F%2Fexample.test%2Fitems&page=0&size=50
POST   /apix/performance/baselines
GET    /apix/performance/baselines?page=0&size=20
GET    /apix/performance/baselines/{id}
DELETE /apix/performance/baselines/{id}
POST   /apix/performance/baselines/{id}/comparisons
GET    /apix/performance/reports?page=0&size=20
GET    /apix/performance/reports/{id}
```

Create a baseline or comparison by supplying selected history IDs:

```json
{"name":"June API baseline","sampleIds":[101,102,103,104,105]}
```

```json
{"sampleIds":[201,202,203,204,205]}
```

Hibernate schema updates are enabled in this repository (`spring.jpa.hibernate.ddl-auto=update`), so it creates `performance_baselines` and `performance_regression_reports` on startup. Environments using managed migrations should create equivalent tables, user foreign keys/indexes, and nullable time-window columns before deploying this version; no Flyway/Liquibase migration system is configured here.

## Reusable API test suites

Suite cases reuse the existing `ApiRequest` model and `RequestExecutionService`; there is no second HTTP client. Runs are sequential (concurrency one), persist a `RUNNING` record before execution and save case results as each step completes. Case timeouts default to 15 seconds and are limited to 100–30,000 ms. Suites accept up to 25 cases and 50 assertions per case. A 10-minute suite wall limit is checked between cases. `CONTINUE` runs independent cases after failures; `STOP_ON_FAILURE` skips all remaining cases after the first failed or errored case. A case depending on an unsuccessful earlier case is skipped.

Each case can include an existing request definition (`method`, absolute HTTP(S) `url`, `headers`, `queryParams`, `body`), timeout, ordered dependencies, extraction definitions, and multiple assertions. Supported assertion types are `REQUEST_COMPLETED`, `STATUS_EQUALS`, `STATUS_IN`, `HEADER_PRESENT`, `HEADER_EQUALS`, `RESPONSE_TIME_LTE`, `BODY_PRESENT`, `BODY_ABSENT`, `JSON_SCHEMA`, `JSON_EXISTS`, `JSON_NOT_EXISTS`, `JSON_EQUALS`, `JSON_NOT_EQUALS`, `JSON_GT`, `JSON_GTE`, `JSON_LT`, and `JSON_LTE`. JSON path evaluation is deterministic and limited to `$`, dotted property paths, quoted property brackets, and numeric array indexes (for example `$.user.id` and `$.items[0].id`). JSON Schema validation uses the existing Swagger parser dependency's validator, rejects remote `$ref` and `$schema` URLs, and reports malformed schema/response JSON as assertion failures. Invalid paths, malformed JSON, unavailable bodies, and unsupported assertion types return individual failures.

Example schema assertion:

```json
{"type":"JSON_SCHEMA","expected":{"type":"object","required":["id"],"properties":{"id":{"type":"integer"}}}}
```

To chain test cases, configure an extraction such as `"resourceId":{"jsonPath":"$.id","secret":false}` and reference it later as `{{resourceId}}` in a URL, header, query parameter, body, or expected string. Variable replacement is one pass; recursive templates are rejected. Runtime variables are submitted for each run and are not stored with suite or run records. All supplied runtime variable values are treated as sensitive in suite result and request-history redaction, whether or not the caller marks them in `secretVariables`; extracted values marked `secret:true` are also added to the redaction set. Suite requests suppress the AI failure-analysis call. Common credential headers/query values and sensitive JSON fields are redacted before suite definitions are persisted. Use runtime placeholders for credentials; a literal sensitive header value is stored as `[REDACTED]` and cannot be replayed. Raw outbound exception messages are suppressed in suite case results and exception trace events.

Suite run states are `QUEUED`, `RUNNING`, `CANCEL_REQUESTED`, `PASSED`, `FAILED`, `PARTIAL`, `ERROR`, and `CANCELLED`. The terminal states are `PASSED`, `FAILED`, `PARTIAL`, `ERROR`, and `CANCELLED`; individual case results are terminal `PASSED`, `FAILED`, `SKIPPED`, or `ERROR` records with start/completion timestamps. HTTP error statuses are still actual HTTP responses and can satisfy expected-status assertions. Transport and timeout errors are distinct from HTTP status assertion failures. Every case result retains available execution/trace identifiers, observed status/latency, assertion expected/actual values, and redacted response evidence. Results are saved after each case so completed evidence remains if a later case fails.

### WebSocket suite cases

The same suite editor and run-history view also supports `protocol: "WEBSOCKET"` cases. Configure an absolute `ws://` or `wss://` URL, optional handshake headers, a connection timeout (100–10,000 ms), and 1–100 ordered steps. Steps are `SEND_TEXT`, `SEND_JSON`, or `RECEIVE`; receive steps may set a timeout (100–30,000 ms, default 5 seconds) and assertions. `MESSAGE_EQUALS` and `MESSAGE_CONTAINS` compare text frames. `JSON_EXISTS`, `JSON_NOT_EXISTS`, `JSON_EQUALS`, `JSON_NOT_EQUALS`, `JSON_GT`, `JSON_GTE`, `JSON_LT`, and `JSON_LTE` apply the same bounded JSONPath evaluator as HTTP cases. `SEND_JSON` must resolve to valid JSON at execution time. The case timeout (100–30,000 ms, default 15 seconds) bounds the complete handshake and step sequence.

Suite WebSockets use APiX's backend-mediated workbench transport. The same connection-time destination policy, TLS verification, frame/message/session bounds, ownership checks, and secret redaction apply. A suite case closes its session on success, assertion failure, timeout, transport error, or cancellation. A failed assertion marks the case `FAILED`; handshake/receive timeout and transport/policy errors mark it `ERROR`, and the suite's existing failure policy applies. Received text and assertion evidence are redacted before persistence. If the process stops during an in-flight WebSocket interaction, startup recovery marks the uncertain run `ERROR`; it does not replay the handshake or messages. A server may already have acted on a sent frame whose acknowledgement was not observed, so this is not exactly-once execution.

The scenario and message evidence are stored inside the existing suite/run JSON columns; this addition does not require a schema migration. Existing HTTP case JSON without a `protocol` field remains interpreted as HTTP. WebSocket case execution remains subject to the existing in-process worker/queue admission model and is not coordinated across multiple application instances.

All suite routes require the current authenticated user and filter suite/run reads and writes by owner:

```http
GET    /apix/suites?page=0&size=20
POST   /apix/suites
GET    /apix/suites/{suiteId}
PUT    /apix/suites/{suiteId}
DELETE /apix/suites/{suiteId}
POST   /apix/suites/{suiteId}/clone
POST   /apix/suites/{suiteId}/cases
PUT    /apix/suites/{suiteId}/cases/{caseId}
DELETE /apix/suites/{suiteId}/cases/{caseId}
PUT    /apix/suites/{suiteId}/cases/order
POST   /apix/suites/{suiteId}/runs
GET    /apix/suites/{suiteId}/runs?page=0&size=20
GET    /apix/suites/runs/{runId}
POST   /apix/suites/runs/{runId}/cancel
POST   /apix/suites/runs/{runId}/performance-comparisons?baselineId={baselineId}
```

Example suite creation (case IDs are generated when omitted):

```json
{
  "name": "Users API contract",
  "description": "Create then retrieve a user",
  "failurePolicy": "CONTINUE",
  "cases": [{
    "name": "Create user",
    "request": {"method":"POST","url":"https://api.example.test/users","headers":{"Content-Type":"application/json"},"body":"{\"name\":\"Ada\"}"},
    "assertions": [{"type":"STATUS_EQUALS","expected":201},{"type":"JSON_EXISTS","path":"$.id"}],
    "extract": {"userId":{"jsonPath":"$.id","secret":false}},
    "dependsOn": [],
    "timeoutMs": 10000
  }]
}
```

Run-time variable payload:

```json
{"variables":{"token":"value supplied for this run"},"secretVariables":["token"]}
```

The frontend Test Suites page provides suite CRUD/clone, case authoring and ordering, assertion configuration, variable submission, run progress/history, cancellation, and a run-to-baseline comparison action. `POST /apix/suites/{suiteId}/runs` returns a persisted `QUEUED` snapshot promptly; poll `GET /apix/suites/runs/{runId}` for `QUEUED`, `RUNNING`, `CANCEL_REQUESTED`, and terminal state updates. Progress includes `totalCases`, `completedCases` (terminal case results persisted so far), and the current case ID/name. `POST /apix/suites/runs/{runId}/cancel` is authenticated and owner-scoped; queued work is cancelled before claim, and running work is interrupted best-effort and reaches `CANCELLED` after its worker stops. The managed pool has two workers and a queue of 20. Per-user admission is serialized within this JVM and allows at most three active runs per user; the 64-stripe lock is process-local. This repository contains a single-container Dockerfile and no multi-replica deployment configuration, so the current guarantee is one backend JVM per database. Do not scale multiple backend instances against the same database without adding distributed admission, worker ownership/leases, and instance-scoped recovery; the current startup recovery would otherwise treat another instance's active runs as interrupted. Saturated requests receive HTTP 503; per-user active-run limits receive HTTP 429. Runtime variables and secrets stay in the submitting process memory and are never stored in run records. On restart, queued, running, and cancellation-requested records are marked `ERROR` with completed results preserved; queued runs are never replayed because their runtime variables were not persisted, and no uncertain outbound request is replayed. A request interrupted after reaching its target may have side effects even though its run is marked cancelled. The executor waits up to 35 seconds for graceful task completion at context shutdown; set the container/orchestrator termination grace period accordingly. The Docker image launches Java directly so it receives the termination signal. Forced termination is reconciled by startup recovery. Run comparison is rejected while a run is active and is available for terminal evidence subject to existing sample rules. Hibernate `ddl-auto=update` is enabled, but deployments with an existing `api_suite_runs` table should inspect its columns and apply the checked-in [manual migration](src/main/resources/db/manual-migrations/20261013_01_api_suite_run_progress.sql) once before rollout if all five columns are absent. It adds `total_cases`, `completed_cases`, `current_case_id`, `current_case_name`, and optimistic-lock `version`; it backfills old counters from known result totals. Apply with a backup and a maintenance window appropriate for the production MySQL size/version. Do not run it on a fresh schema or when Hibernate has already added these columns. No Flyway/Liquibase migration runner is configured. AI-generated test creation is not included.

### Outbound request security

The API request endpoint, suite cases, sequential load-test service, shared application `WebClient`, and configured AI provider use a common APiX Reactor Netty client and destination policy. Only absolute HTTP and HTTPS URLs without embedded credentials are accepted. Invalid ports, ambiguous numeric IPv4 forms, URL authority escapes, loopback, private, link-local, unspecified, multicast, reserved, IPv4-mapped private, and non-global IPv6 destinations are blocked. Known metadata hosts and addresses, including `169.254.169.254` and Azure's `168.63.129.16`, remain blocked even when a local host is allowlisted.

For DNS names, APiX collects all A/AAAA answers, rejects the destination if any answer is blocked, and passes only those checked socket addresses to Reactor Netty. This avoids validating one DNS result and then independently resolving the host for the connection. DNS queries have a timeout; DNS failures and empty answers fail closed. URL parsing currently requires an ASCII hostname (use its IDNA/Punycode form for internationalized names). Redirect following is explicitly disabled, so a `Location` header is returned as an ordinary 3xx response and is never followed. TLS certificate verification remains enabled. No retry loop is configured.

Local/private API testing is disabled by default. To allow a specific local target in a trusted development environment, set `APIX_OUTBOUND_ALLOWED_LOCAL_HOSTS` to a comma-separated list of exact hostnames or IP literals, such as `localhost,127.0.0.1`. Wildcards and suffix matching are not supported. Every answer for an allowlisted host must still be a local/private address; metadata, multicast, and other reserved destinations remain blocked. This setting grants all authenticated APiX users access to those exact local targets, so do not enable it on a service exposed to untrusted users.

Connection pooling defaults to 50 active connections and at most 100 pending acquisitions. DNS timeout defaults to 3 seconds, connect timeout to 5 seconds, and response timeout to 30 seconds. Set `APIX_OUTBOUND_MAX_CONNECTIONS`, `APIX_OUTBOUND_MAX_PENDING_ACQUIRES`, `APIX_OUTBOUND_DNS_TIMEOUT_MS`, `APIX_OUTBOUND_CONNECT_TIMEOUT_MS`, and `APIX_OUTBOUND_RESPONSE_TIMEOUT_MS` to tune these bounded controls. `APIX_OUTBOUND_MAX_BODY_BYTES` sets the request-body and WebClient response-buffer limit (default 1 MiB, configurable up to 10 MiB); `APIX_OUTBOUND_MAX_LOAD_TEST_REQUESTS` sets the sequential load-run cap (default 50, maximum 200). Headers are limited to 100 entries and 8 KiB per UTF-8 value. Suite runs retain their existing case and total-duration bounds. Policy-denied requests return a generic safe diagnostic; low-level DNS and connection details are not exposed. AI provider URL and response data are no longer printed to standard output.

The secure DNS resolver is applied to direct outbound connections made by the shared Reactor Netty client. The current application does not configure an HTTP proxy; if a proxy is introduced, destination enforcement must also validate the proxy's actual egress behavior. The local allowlist is an operator trust control, not per-user authorization. Framework-managed OAuth requests use fixed Google endpoints and are not user-targetable through the API request interface.

Suite runtime variables are limited to 50 values of up to 4 KB each. Extraction JSONPath expressions are limited to 256 characters. Suite case IDs are limited to 64 alphanumeric, underscore, or hyphen characters. Exception detail in case results and recorded exception events is generic; this sacrifices low-level diagnostics to avoid carrying raw transport exception data into persisted evidence or traces.

## Implementation plan and status

Implemented increments establish stable execution/trace correlation and redact persisted and AI-bound evidence, add baseline creation, threshold-based comparisons, persisted reports and a performance view, and add reusable user-owned suites with a safe assertion engine, ordered execution, variable extraction, run persistence/history, and baseline comparison delegation. The authenticated `/websocket` page supports backend-mediated sessions and a browser-direct compatibility mode. The Local Testing Agent supports owner-scoped pairing, exact loopback host/port grants, and explicit local HTTP request execution. WebSocket test-suite cases, natural-language test plans, mixed-protocol scenarios, and a CI runner remain unimplemented.

### Backend WebSocket workbench

The authenticated UI uses these owner-scoped, ephemeral session endpoints:

```http
POST   /apix/websocket/sessions
GET    /apix/websocket/sessions
GET    /apix/websocket/sessions/{id}
POST   /apix/websocket/sessions/{id}/messages
DELETE /apix/websocket/sessions/{id}
```

Create with `{"url":"wss://public.example.test/socket","headers":{"Authorization":"Bearer …"}}`; send a text frame with `{"message":"hello"}`. These routes require existing APiX authentication and every session read/send/close checks owner ID. The backend uses the existing instrumented Reactor Netty `HttpClient` and its DNS resolver, not a separate permissive transport. `ws://` and `wss://` handshakes are checked through the same destination policy; the resolver validates all DNS answers and connects only to checked addresses. Redirects and retries remain disabled and `wss://` uses the client's normal certificate verification. Private/loopback targets are blocked unless the exact-host `APIX_OUTBOUND_ALLOWED_LOCAL_HOSTS` setting is configured; that existing setting is global to all authenticated users and should not be enabled on an untrusted service.

Session creation returns a `CONNECTING` session immediately; poll its owner-scoped detail endpoint for handshake completion, or close it to cancel a pending handshake. The server enforces a 10-second handshake timeout. Sessions are in-memory and process-local, limited to 3 active sessions per user and 20 total, with a 10-minute lifetime, 64 KiB frame limit, 500 total messages, and 200 retained incoming text frames. Binary frames are closed as unsupported. Frame contents and credentials are not persisted; common credential content and sensitive handshake-header values are redacted from received text and response headers. Sessions end on application shutdown and are not restart-recovered. They are not shared across application instances.

### Browser-direct WebSocket mode (limited)

The UI retains a browser-native mode. The UI blocks obvious local/private IP literals and local-only hostnames, but cannot inspect DNS answers; this client-side check is not an SSRF boundary. Browser APIs cannot set arbitrary handshake headers or expose complete handshake status/headers. Browser-direct connections do not use APiX backend destination policy. Use the Local Testing Agent below when local HTTP access is required.

WebSocket test-suite execution is not implemented. A controlled echo-server test exists, but it is blocked in the current Windows environment by Netty's selector loopback-pipe failure; the end-to-end path is not verified here.

### Local Testing Agent (HTTP only)

The agent allows explicitly selected APiX HTTP requests to reach a local development service without relaxing the backend's public outbound SSRF policy. Pairing and management routes are authenticated and owner-scoped: `POST /apix/agents/pairings`, `GET /apix/agents`, and `DELETE /apix/agents/{id}`. An agent redeems the one-time code at `POST /apix/agents/pair`; subsequent bounded polling/result calls use the `X-APiX-Agent-Credential` header at `/apix/agents/channel/**`. Pairing codes are single-use, expire after five minutes, and only their hashes are stored. The random agent credential is shown once and persisted by the CLI as a bearer secret in its dedicated config file; protect that user profile and revoke the agent if the file is exposed.

From `local-agent/`, run `npm test` and start with Node.js 20 or later, passing the APiX server origin and one or more exact `--allow=HOST:PORT` grants (for example `node apix-agent.mjs https://apix.example --allow=127.0.0.1:3000`). The CLI prompts for the pairing code without echoing it. Only `localhost`, `127.0.0.1`, and `::1` are accepted, with explicit ports. The agent validates every job itself, resolves at connection time and rejects non-loopback DNS answers, does not follow redirects, and has no proxy, shell, or filesystem browsing feature. Requests and responses are bounded to 1 MiB and 30 seconds. Current transport is one sequential poller per agent; agent jobs/channels are process-local, not persisted or coordinated across replicas, and are never replayed after restart or uncertain result delivery. An in-flight local request may nevertheless have taken effect before a disconnect. WebSocket agent transport is not implemented.

The one-time agent schema is in [the local-agent migration](src/main/resources/db/manual-migrations/20261014_02_local_test_agents.sql). Before deployment, stop APiX, back up the database, verify the MySQL schema uses InnoDB and contains the referenced `users(id)` key, then apply this migration once. It is intentionally non-repeatable: the two tables must not already exist. Rollback requires stopping APiX and dropping the pairing table and agent table; this discards all enrollments. Never roll back by deleting either table while the new application is running. The migration has not been validated against a live MySQL server in this continuation, so verify it against the target MySQL version and a disposable copy before deployment. Existing public HTTP SSRF policy remains unchanged.

### Local trace viewer

The local Spring profile points trace links to Jaeger at `http://localhost:16686/trace/`. From the sibling `apix-otel/` directory, run `docker compose up -d` to start local Jaeger and the OTLP HTTP collector; stop them with `docker compose down`. Keep the backend Java agent exporter endpoint at `http://localhost:4318`. Set `APIX_OTEL_UI_BASE_URL` to the deployed Jaeger trace URL prefix in non-local deployments; the production default remains the existing hosted viewer. Restart the backend after changing its active profile or trace URL setting.

The performance tests cover nearest-rank metrics, missing percentiles, throughput windows, error rates, threshold regressions, improvements, unchanged values, and zero-baseline behavior. Additional database-backed authorization and frontend interaction tests remain outstanding.

### Async run persistence and schema upgrade

The progress migration is a **manual, one-time, non-repeatable MySQL migration**. It was exercised against MySQL Community Server 8.4.11 using a disposable schema seeded with representative pre-progress `api_suite_runs` rows. That schema had the existing `id`, `user_id`, `suite_id`, `suite_name`, `status`, `passed`, `failed`, `skipped`, `errored`, `results_json`, `started_at`, `completed_at`, `duration_ms`, and `failure_summary` columns, the `idx_api_suite_run_user_started (user_id, started_at)` index, and a user foreign key. MySQL versions other than 8.4.11 have not been verified here; the repository does not declare a supported server-version range. The run table and referenced `users` table should use InnoDB.

Before a managed-database rollout, stop all APiX instances and take a tested backup. Confirm the legacy columns and existing user/start index, then inspect `information_schema.columns` for `api_suite_runs`. Run the first script only when **none** of the five progress/version columns exists and the legacy counters exist. Do not run it against an empty/fresh schema or a table with any of the five columns already present. The `ALTER TABLE` adds `total_cases INT NOT NULL DEFAULT 0`, `completed_cases INT NOT NULL DEFAULT 0`, nullable `current_case_id VARCHAR(100)` and `current_case_name VARCHAR(200)`, and `version BIGINT NOT NULL DEFAULT 0`; the following update sets both counters to `passed + failed + skipped + errored`. Result JSON, statuses, history IDs, and existing indexes are not rewritten. Hibernate `@Version Long` is mapped as non-null signed `BIGINT`; conditional JPQL transitions increment it explicitly.

If an earlier Hibernate startup already added all five columns, do not run the first migration. Inspect the version column's `IS_NULLABLE`; when it is `YES`, apply [the version correction migration](src/main/resources/db/manual-migrations/20261014_01_api_suite_run_version_not_null.sql) once while APiX is stopped. It backfills only null versions to zero, then enforces `BIGINT NOT NULL DEFAULT 0`. If the five-column set is incomplete, stop and prepare a reviewed repair for the observed schema rather than guessing which migration to run.

Example deployment sequence after the backup and review (replace the database/schema name and credentials with the deployment's secret-managed values):

```powershell
mysql --host <host> --user <migration-user> --password <database-name> < src/main/resources/db/manual-migrations/20261013_01_api_suite_run_progress.sql
```

The DDL is not transactionally rolled back with the backfill. If the `UPDATE` fails after the `ALTER TABLE` succeeds, stop rollout, inspect the column and row state, and restore from the backup or perform a reviewed forward repair. Do not rerun the file: its `ADD COLUMN` statements will fail on existing columns. A rollback must not simply drop these columns after new code has written progress; that would discard run evidence. Restore a pre-upgrade backup only under the deployment's data-recovery procedure, or roll code forward after repairing schema/data. After migration, verify all five column definitions, `version=0` for legacy rows, null current-case values, the index, and representative counter backfills before starting the application. Keep `ddl-auto=update` behavior coordinated with the migration window; never start old/new replicas against the schema concurrently because this service does not support multi-instance run coordination.

`ApiSuiteRunPersistenceIntegrationTest` is an opt-in test slice for repository queries, progress writes, conditional claim/cancel transitions, and optimistic-lock conflicts. It runs only when `APIX_TEST_DATASOURCE_URL` begins with `jdbc:mysql:` and uses the `persistence-test` profile, which reads only `APIX_TEST_DATASOURCE_*` settings. Point it exclusively at a disposable schema; the regular production datasource properties are not changed. Migration regression tests execute the checked-in SQL against connection-local legacy tables and verify counter backfill, defaults, version repair, and preserved result JSON.

### Verification performed

- Reusable-suite audit on 2026-10-14: `mvn '-Dmaven.repo.local=target/m2' '-Dtest=ApiAssertionServiceTest,ApiTestSuiteServiceTest,PerformanceComparisonServiceTest,SensitiveDataRedactorTest' test` — passed (30 tests, 0 failures/errors/skips); `mvn '-Dmaven.repo.local=target/m2' -DskipTests compile` — passed with sources up to date; frontend `npx tsc --noEmit` — passed.
- `npm test -- --run src/pages/Suites.test.tsx` — blocked before test discovery because esbuild could not read the OneDrive parent directory (`Cannot read directory "../../../../..": Access is denied`). `APIX_TEST_DATASOURCE_URL` was not configured, so the persistence integration tests were not rerun in this audit. Earlier MySQL 8.4.11 checks documented below are historical verification.
- `mvn '-Dmaven.repo.local=target/m2' -DskipTests compile` — passed for 86 backend source files.
- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=OutboundDestinationPolicyTest,PolicyAddressResolverGroupTest,LoadTestServiceTest,ApiAssertionServiceTest,ApiTestSuiteServiceTest,PerformanceComparisonServiceTest,PerformanceMetricServiceTest,PerformanceRegressionServiceTest,SensitiveDataRedactorTest' test` — passed (35 tests, 0 failures/errors), including address-class blocks, mixed DNS results, DNS failure/timeout, executor preflight before HTTP client use, exact local allowlisting, metadata blocks, load-count bounds, suite redaction, and local JSON Schema references.
- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=SensitiveDataRedactorTest,PerformanceComparisonServiceTest,PerformanceMetricServiceTest,PerformanceRegressionServiceTest' test` — passed (10 tests, 0 failures/errors).
- `mvn '-Dmaven.repo.local=target/m2' '-Dtest=LocalTestAgentServiceTest,ApiTestSuiteServiceTest,OutboundDestinationPolicyTest,WebSocketWorkbenchServiceTest#invalidOrUnapprovedTargetsAreRejectedBeforeAClientConnectionIsStarted+invalidProtocolHeadersAreRejectedWithoutOpeningASession+sessionsAreNotAccessibleAcrossOwners+sessionCreationReturnsBeforeHandshakeAndCancellationDisposesPendingConnection' test` — passed (36 tests, 0 failures/errors/skips), including agent pairing/revocation policy, structured agent response redaction, existing HTTP suites, outbound policy, and selected WebSocket workbench tests.
- `npm test` in `local-agent/` — passed (4 tests); `node --check apix-agent.mjs` and `node --check test/agent.test.mjs` — passed.
- Frontend `npx tsc --noEmit` — passed. Frontend `npm test -- --run` and `npm run build` — could not load their Vite/esbuild configs because esbuild received `Access is denied` reading the OneDrive parent directory; neither is a passing verification.
- The local-agent migration `20261014_02_local_test_agents.sql` has not been tested against MySQL in this pass. Do not deploy it until it is exercised against a disposable schema on the target MySQL version and table/foreign-key conventions are confirmed.
- Disposable MySQL 8.4.11 migration validation — passed for representative existing run rows; exact schema and backfill values were inspected.
- `APIX_TEST_DATASOURCE_URL=jdbc:mysql://127.0.0.1:<port>/...` plus `mvn -Dmaven.repo.local=<local-cache> -Dtest=ApiSuiteRunPersistenceIntegrationTest test` — passed (8 tests, 0 failures/errors) against disposable MySQL 8.4.11. Covered both migration scripts, backfill/defaults, entity/schema metadata, progress persistence, latch-coordinated claim races with one winner, cancellation transitions, stale-version conflicts, cancellation versus stale completion, and a database-triggered failure proving a rejected terminal write does not persist `PASSED`.
- The final focused test selection including persistence, suite runner, assertion, outbound policy, DNS policy, and redaction tests — passed (44 tests, 0 failures/errors) with a disposable MySQL test datasource.
- `mvn -Dmaven.repo.local=<local-cache> -Dtest=ApixApplicationTests test` with test-only JDBC/OAuth environment variables — database connected and Hibernate generated current tables, but full application startup was blocked when the outbound Netty resolver could not establish its Windows selector loopback pipe. The focused JPA Spring context and repository tests passed independently.
- `mvn '-Dmaven.repo.local=target/m2' test` — the performance and redaction tests passed, but the full suite failed in the existing `ApixApplicationTests.contextLoads` because this environment has no valid `jdbc:` datasource URL. Supply the documented MySQL datasource environment variables to run that context test.
- `npm run build` in `APIX_Frontend/friendly-frontend-helper` — could not complete in this environment; Vite/esbuild reported OneDrive traversal access denied in the sandbox. An elevated retry began transforming modules but stalled without output and was stopped.
- `npx tsc --noEmit` in `APIX_Frontend/friendly-frontend-helper` — passed.
- `npm test` in `APIX_Frontend/friendly-frontend-helper` — could not complete: Vitest/esbuild reported the same sandbox directory-access error. An elevated retry started Vitest but stalled before test results were emitted and was stopped.

An earlier full-suite attempt failed before context creation because no JDBC datasource URL was configured. A later `ApixApplicationTests` attempt with a valid disposable MySQL URL connected and generated the mapped tables, then failed when the outbound Netty DNS resolver could not establish its Windows selector loopback pipe. The focused Spring Data JPA context and persistence tests pass. The frontend build and Vitest checks should be rerun from a normal local checkout outside a OneDrive-synchronized directory.

The connection-time DNS resolver is covered with deterministic fake DNS answers, including mixed public/private results, resolver failure, and timeout. A real loopback HTTP-server integration test could not run in this sandbox: Netty was unable to create its Windows selector loopback pipe. The tests therefore verify rejection at the resolver stage with controlled addresses; rerun integration probes from a normal local checkout if environment-level socket testing is needed.
