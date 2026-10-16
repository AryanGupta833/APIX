# APIX

**APIX is an API development workbench for executing and debugging HTTP requests, running reusable API test suites, inspecting distributed traces, and analyzing performance regressions.** It combines request execution, history, collections, OpenAPI import, diagnostics, optional AI-assisted failure analysis, WebSocket testing, and a local agent for explicitly approved loopback targets.

> **Status:** APIX is an actively developed project, not a production-readiness certification. Review the deployment notes and limitations below before relying on it for production traffic or horizontally scaling it.

## Capabilities

### HTTP request workbench

- Execute `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, and `OPTIONS` requests with headers, query parameters, and request bodies.
- Inspect status, response headers/body, elapsed time, available network timing phases, execution ID, and OpenTelemetry trace ID.
- Persist request history and replay saved requests; organize requests in owner-scoped collections.
- Import API definitions from supported OpenAPI documents and use the resulting endpoint information while building requests.
- Analyze failures with rule-based diagnostics and optional Spring AI/OpenRouter assistance. AI access requires a configured provider key; the application remains useful without it.

### Test suites

- Create and manage reusable suites with ordered HTTP or WebSocket cases, dependencies, extraction variables, and configurable failure policy.
- Assert status, headers, response time, body presence/absence, JSON values, and JSON Schema constraints using bounded JSON-path lookups.
- Chain extracted values into later requests with `{{variableName}}` templates. Runtime values and marked extracted values are redacted in persisted evidence.
- Run suites asynchronously; inspect paginated run history and per-case progress/evidence, cancel an active run, and compare a completed run against a performance baseline.
- Choose `CONTINUE` or `STOP_ON_FAILURE`; dependent cases are skipped when prerequisites do not pass.
- Use WebSocket cases to connect, send ordered text/JSON frames, receive frames, and assert bounded message evidence. Sessions close after the case and are not replayed after restart.

### WebSocket workbench

The backend-mediated workbench supports authenticated, owner-scoped `ws://` and `wss://` sessions, handshake headers, text messages, receive polling, and explicit close. Sessions and frames are ephemeral in-memory state, not durable history. A browser-direct mode is also available but has a weaker security boundary: it does not use backend DNS/connection-time destination enforcement.

### Performance and observability

- Review request timing and latency information where available, including DNS, connect, TLS, response, and total phases. Missing measurements remain absent instead of being fabricated.
- Build performance baselines from comparable request-history samples and create threshold-based regression reports with average and nearest-rank percentile latency, error rate, and throughput when sample data supports each metric.
- Adjust minimum sample and alert thresholds through configuration. These comparisons are threshold alerts, not claims of statistical significance or server capacity.
- Follow execution and distributed traces using OpenTelemetry trace IDs and the configured Jaeger/OpenTelemetry UI.

### Local Testing Agent

The optional standalone Node.js agent runs bounded HTTP requests against specifically granted loopback host/port pairs, such as a local development server that the hosted API cannot reach. Pairing is single-use; server-side pairing and agent credentials are stored as hashes, and an agent can be revoked. The agent has no shell, general proxy, or arbitrary filesystem interface. It is not a tunnel to arbitrary network destinations.

Agent pairing records persist, but online state and job delivery are process-local and jobs are not persisted or replayed. If connectivity is lost after a request is sent, the remote operation may have happened even if APIX never receives the result. Local-agent WebSocket execution is not supported.

## Security model and boundaries

- `/apix/**` routes require the existing Google OAuth/JWT sign-in flow. The one-time agent pairing route and credential-authenticated agent channel are separate exceptions.
- HTTP and backend-mediated WebSocket execution use a shared outbound destination policy. It rejects ambiguous/credential-bearing URLs, cloud metadata addresses, and non-public IP ranges by default; actual DNS answers are checked at connection time. Local destinations require an explicit exact-host allowlist. Redirects/retries are not used to bypass the destination policy.
- Request history and AI/suite evidence apply best-effort secret redaction. Do not send production credentials or sensitive data to external APIs or an AI provider unless that use is approved for your environment.
- The Local Testing Agent separately enforces exact loopback host/port grants and validates DNS answers. Protect its per-user configuration file and revoke agents that are no longer needed.
- Browser-direct WebSocket connections do not receive the backend's DNS-answer or connection-time SSRF enforcement. Use the backend-mediated mode when that policy matters.
- OAuth client secrets, database credentials, OpenRouter keys, and other secrets belong in environment variables or a deployment secret manager, never in Git.

## Architecture

```text
Browser / frontend
       │ Google OAuth + JWT
       ▼
Spring Boot APIX backend ── MySQL (JPA persistence)
       │
       ├── Shared HTTP client ── destination/DNS policy ── target APIs
       ├── OpenTelemetry spans ── OTLP ── Jaeger / trace UI
       ├── optional Spring AI ── configured model provider
       ├── process-local asynchronous suite workers
       └── optional agent job channel ── Node local agent ── granted loopback API
```

The reusable-suite worker pool/admission state, WebSocket sessions, and local-agent job channel are process-local. APIX currently does not provide distributed worker coordination, cross-instance job delivery, or high-availability guarantees. Run a single backend instance unless you have independently addressed these constraints.

## Repository layout

- Backend: this repository (Spring Boot / Maven)
- Frontend: the separate [APIX frontend repository](https://github.com/AryanGupta833/friendly-frontend-helper)
- Optional Node agent: [`local-agent`](local-agent)
- Progress and implementation notes: [`IMPLEMENTATION_PROGRESS.md`](IMPLEMENTATION_PROGRESS.md)

## Technology

- Java 21, Spring Boot 4.0.x, Maven
- Spring MVC and WebFlux/Reactor Netty HTTP clients
- MySQL with JPA/Hibernate
- Spring Security, Google OAuth login, JWT
- OpenTelemetry API/SDK and OTLP export; Jaeger-compatible tracing UI
- Spring AI with configurable OpenRouter-compatible provider
- React, TypeScript, Vite, and Vitest in the separate frontend repository
- Node.js 20+ for the optional local agent

## Run locally

### Prerequisites

- Java 21 and Maven (or the included `mvnw.cmd`)
- MySQL reachable by the backend
- The APIX frontend repository and Node.js for the UI
- Google OAuth client credentials for sign-in
- Optional: OpenRouter-compatible model API key for AI analysis, Jaeger/OTLP endpoint for trace viewing, and Node.js 20+ if using the local agent

### Configure the backend

The application reads connection and provider settings from environment variables. At minimum, configure:

```text
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/apix
SPRING_DATASOURCE_USERNAME=<mysql-user>
SPRING_DATASOURCE_PASSWORD=<mysql-password>
GOOGLE_CLIENT_ID=<google-oauth-client-id>
GOOGLE_CLIENT_SECRET=<google-oauth-client-secret>
```

For optional AI and tracing features:

```text
OPENROUTER_API_KEY=<provider-key>
APIX_OTEL_UI_BASE_URL=http://localhost:16686/trace/
```

The backend's default port is `8080`. The local Spring profile uses `8081` and redirects OAuth success to the frontend's default Vite origin at `http://localhost:5174`:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/apix"
$env:SPRING_DATASOURCE_USERNAME = "<mysql-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<mysql-password>"
$env:GOOGLE_CLIENT_ID = "<google-oauth-client-id>"
$env:GOOGLE_CLIENT_SECRET = "<google-oauth-client-secret>"
# Optional:
$env:OPENROUTER_API_KEY = "<provider-key>"
.\mvnw.cmd spring-boot:run
```

Set the Google OAuth authorized redirect URI to `http://localhost:8081/login/oauth2/code/google`. Keep real credentials in your terminal environment or secret manager; do not paste them into tracked configuration files. For IntelliJ, select the `local` profile and add these variables to the run configuration.

### Start the frontend

Clone the separate [APIX frontend repository](https://github.com/AryanGupta833/friendly-frontend-helper), then run:

```powershell
git clone https://github.com/AryanGupta833/friendly-frontend-helper.git
cd friendly-frontend-helper
npm install
npm run dev
```

The Vite development server uses port `5174` with strict-port behavior. Configure `VITE_API_BASE_URL` if the backend runs at a different address, and keep the backend CORS allowlist and `APIX_OAUTH_FRONTEND_SUCCESS_URL` aligned with the frontend origin.

### Run tests

Backend:

```powershell
.\mvnw.cmd test
```

Frontend:

```powershell
npm test -- --run
npm run build
```

Some persistence/integration tests require a disposable MySQL test database. Inspect test properties and environment configuration before running them. Never point destructive test setup at a production database. The repository's implementation notes record platform-specific test limitations; a checked-in test is not evidence that its external integration has passed on every machine.

## Local agent setup

The agent requires Node.js 20 or later. Pair it through the authenticated APIX UI, then run the standalone CLI with the APIX server URL and only the exact loopback host/port grants you intend it to reach. The CLI prompts for the pairing credential without echoing it and writes its credential to a dedicated user-profile config file. Do not pass the pairing secret as a command-line argument or commit the generated agent config. See the [`local-agent`](local-agent) directory for implementation and tests.

## API route overview

API routes are under `/apix` and require APIX authentication unless noted. The current controllers expose:

| Capability | Routes |
|---|---|
| Request execution and history | `POST /apix/request`, `GET /apix/history` |
| Collections | `GET/POST /apix/collections`, `GET /apix/collections/{id}`, `POST /apix/collections/{collectionId}/requests` |
| OpenAPI import | `POST /apix/openapi/import` |
| Reusable suites | `/apix/suites` (suite and case CRUD, clone, asynchronous runs, run history, cancellation, and performance comparison) |
| Performance | `/apix/performance` (samples, baselines, comparisons, reports) |
| Backend WebSocket sessions | `/apix/websocket/sessions` (create/list/read/send/close; owner-scoped) |
| Local agents | `/apix/agents` (pairing management, list/revoke, approved local requests); pairing and agent channel use their dedicated credential flows |

Consult the controllers and frontend API client for request/response schemas. Avoid relying on the stale endpoint examples in earlier README revisions.

## Database and deployment notes

JPA/Hibernate schema update is enabled by the current application configuration. The repository also contains manual SQL migrations for selected deployment changes rather than a complete Flyway/Liquibase migration history. Review `src/main/resources/db/manual-migrations/` and `IMPLEMENTATION_PROGRESS.md` before deploying or applying any migration. Some recent one-time migration paths have not been verified against a disposable database in the latest implementation pass; do not apply them blindly to production.

The Dockerfile builds and runs the backend application, but it does not by itself configure MySQL, OAuth, the frontend, the model provider, Jaeger, or multi-instance worker coordination. Configure deployment secrets and allowed origins explicitly. Use TLS at the deployment boundary and review OAuth redirect and CORS settings before exposing the service.

## Current limitations

- Suite execution coordination, worker admission, WebSocket sessions, and agent job delivery are single-process/in-memory. Horizontal scaling and HA are not guaranteed.
- A running suite is marked `ERROR` during recovery if the process stops; completed per-case evidence remains, and outbound work is not automatically replayed.
- Backend WebSocket sessions and messages are ephemeral, bounded, and not restored after restart. Browser-direct mode has a weaker destination-security boundary.
- Local agent job delivery is process-local and not durable. A lost result may leave uncertainty about whether the target API performed the request. The agent does not provide local WebSocket access.
- Performance reports are threshold-based and need adequate comparable history samples; they are not statistical-significance tests.
- AI assistance depends on an external configured provider and may transmit redacted request/response evidence to that provider. Keep sensitive test data out of prompts.
- The full persistence migration path, Windows WebSocket loopback integration, frontend production build, and all deployment configurations are not certified by this README. See [`IMPLEMENTATION_PROGRESS.md`](IMPLEMENTATION_PROGRESS.md) for the latest verified checks and known environment blockers.
- GraphQL/gRPC testing, team workspaces, CI runners, a mock server, and AI-generated test authoring are not documented as implemented features.

## Author

**Aryan Gupta** · [GitHub](https://github.com/AryanGupta833) · [LinkedIn](https://linkedin.com/in/aryan-gupta-316a35366)
