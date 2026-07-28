# M9 Security Review

**Review date:** 2026-07-28

**Scope:** the routes and deployment boundary described in [architecture §6](architecture.md#6-security), source at `feat/m9-hardening`, and its tracked Git history.
**Method:** source review plus the executable evidence listed below. OWASP categories use the [OWASP Top 10:2025](https://owasp.org/Top10/2025/0x00_2025-Introduction/) taxonomy. No unaddressed high or critical finding remains in this scope.

## Route inventory and trust boundaries

| Boundary | Routes reviewed | Required control | Evidence |
|---|---|---|---|
| Public API | `POST /api/v1/auth/register`, `/login`, `/refresh`; `GET /actuator/health` | Auth endpoints are deliberately anonymous; actuator exposure is health only and omits details. | `apps/api/src/main/resources/application.yml`; `apps/api/src/test/java/com/ledgerly/api/auth/AuthEndpointsIT.java` |
| Authenticated API | All other `/api/v1/**`: documents (upload, read, content, events), expenses (create, read, list, detail, approve, correct), categories CRUD, budgets CRUD, policies upload/read, dashboard summary, and alerts | Stateless JWT authentication, service-layer organization scope, UUID resource lookup returns 404 when not visible. | `apps/api/src/main/java/com/ledgerly/api/auth/SecurityConfig.java`; `apps/api/src/test/java/com/ledgerly/api/tenant/TenantIsolationIT.java`; `apps/api/src/test/java/com/ledgerly/api/expense/ExpenseDetailIT.java`; `apps/api/src/test/java/com/ledgerly/api/document/DocumentContentIT.java` |
| Browser BFF | `GET|POST|PUT|PATCH|DELETE /api/[...path]` | Same-origin proxy only; bearer token stays in an httpOnly cookie; rejects traversal, unsafe segments, oversized bodies, and cross-origin mutations before forwarding. | `apps/web/src/app/api/[...path]/route.ts`; `apps/web/src/app/api/[...path]/__tests__/route.test.ts` |
| Internal AI | `GET /health`; `POST /extract`, `/embed-policy`, `/embed-query`, `/categorize`, `/anomaly` | Health is public; every cost-bearing operation requires the API service bearer token before body processing and has a rate limit. | `apps/ai/app/service_auth.py`; `apps/ai/tests/test_service_auth.py`; `apps/ai/tests/test_rate_limit.py` |

The tenant-crossing probes in `ExpenseDetailIT` and `DocumentContentIT` assert `404`, rather than an authorization response that confirms the resource exists. `TenantIsolationIT` covers organization A attempting organization B's data through the API surface.

## OWASP 2025 assessment

| ID | Result | Controls and executable evidence |
|---|---|---|
| A01 Broken Access Control | Addressed | `SecurityConfig` protects all non-auth API routes; organization is enforced in services/repositories, not merely controllers. `TenantIsolationIT`, `ExpenseDetailIT`, and `DocumentContentIT` prove tenant probes are absent/404. The BFF rejects path traversal with 404 in `route.test.ts`. |
| A02 Security Misconfiguration | Addressed | `application.yml` exposes only detail-free health; JWT and AI token values are environment-only; no static storage route exists. API responses carry `X-Content-Type-Options: nosniff` (`SecurityHeadersIT`). The BFF has no API CORS bypass and enforces same-origin mutation requests (`route.test.ts`). |
| A03 Software Supply Chain Failures | Addressed | `.github/workflows/ci.yml` pins every GitHub Action to a full commit SHA and runs Trivy `v0.72.0` for HIGH/CRITICAL library vulnerabilities with `exit-code: 1`. The 2026-07-28 local Trivy scans of Maven, Python, and npm dependency sets returned zero HIGH/CRITICAL findings after remediation. |
| A04 Cryptographic Failures | Addressed | Passwords use BCrypt; JWT signing material and AI service token are required runtime secrets, never source literals. `AuthEndpointsIT`, `apps/ai/tests/test_config.py`, and `apps/ai/tests/test_service_auth.py` exercise authentication and fail-fast secret configuration. |
| A05 Injection | Addressed | API request DTOs and AI Pydantic schemas validate boundaries; repositories use parameterized/JPA queries; BFF path allow-list prevents path injection. Document MIME and magic bytes, size, opaque storage keys, and response path non-disclosure are covered by `DocumentUploadIT`; API↔AI payload schemas by `DocumentSchemaIT`, `ExtractionContractTest`, `ProposalContractConformanceTest`, and `apps/ai/tests/test_contract.py`. |
| A06 Insecure Design | Addressed | Financial mutations use idempotency, bounded asynchronous extraction, explicit retry/reaper states, review thresholds, and double-entry invariants. Evidence: `IdempotencyFilterIT`, `DocumentStatusPipelineIT`, `DocumentReaperIT`, `ExpensePostingPipelineIT`, and `LedgerTransactionPropertyTest`. |
| A07 Authentication Failures | Addressed | API is stateless and returns 401 for missing credentials; browser sessions retain tokens in httpOnly cookies and the BFF adds bearer credentials server-side only. AI rejects missing/wrong service tokens before reading a body. Evidence: `AuthEndpointsIT`, BFF `route.test.ts`, and `apps/ai/tests/test_service_auth.py`. |
| A08 Software or Data Integrity Failures | Addressed | API independently validates AI output before state transition and both sides test their shared contracts. Atomic status transitions and idempotency prevent duplicate posting. Evidence: `DocumentSchemaIT`, `ExtractionContractTest`, `ProposalContractConformanceTest`, `apps/ai/tests/test_contract.py`, `ExpensePostingPipelineIT`, and `ExpenseReviewIT`. |
| A09 Security Logging and Alerting Failures | Addressed | API and AI emit structured JSON records with a validated correlation ID; redaction excludes document contents, filenames, email addresses, authorization values, and common provider keys. Evidence: `CorrelationIdFilterIT`, `CorrelationIdsTest`, and `apps/ai/tests/test_observability.py`. Audit changes are covered by `AuditTrailIT`. |
| A10 Mishandling of Exceptional Conditions | Addressed | Invalid uploads, malformed AI replies, missing blobs, Redis failure, retries, and crashed workers have bounded, fail-closed paths. Evidence: `DocumentUploadIT`, `DocumentUploadRollbackIT`, `DocumentContentIT`, `DocumentStatusPipelineIT`, `DocumentReaperIT`, `RedisOutageIT`, and `UploadRateLimitIT`. |

## Architecture §6 traceability

| §6 control | Executable check |
|---|---|
| Every query is organization-scoped | `TenantIsolationIT`, `ExpenseDetailIT`, `DocumentContentIT` |
| Uploads are validated, bounded, opaque, and not web-root served | `DocumentUploadIT`, `DocumentContentIT`, `PolicyUploadIT` |
| Secrets are environment-only | `apps/ai/tests/test_config.py`; Gitleaks command below |
| Structured logs exclude raw content and PII | `CorrelationIdFilterIT`; `apps/ai/tests/test_observability.py` |
| Authorization is not bypassable by internal callers | `apps/ai/tests/test_service_auth.py`; `SecurityConfig` and tenant integration tests |
| Upload and agent calls are rate-limited | `UploadRateLimitIT`, `RedisOutageIT`, `apps/ai/tests/test_rate_limit.py`, `scripts/loadtest.sh` |

## Scan and demo record

Run from repository root:

```powershell
docker run --rm -v "${PWD}:/repo:ro" zricethezav/gitleaks:latest detect --source /repo --redact --no-banner
Set-Location apps/api; .\mvnw.cmd -q verify
Set-Location ../ai; python -m pytest -q
Set-Location ../..; bash scripts/loadtest.sh
bash scripts/degradation-smoke.sh
```

The Gitleaks review on 2026-07-28 scanned 102 commits and reported no leaks. The CI Trivy gate is the release enforcement point; it fails on any HIGH or CRITICAL dependency finding. After the review remediations, the API suite passed, the AI suite passed (164 tests), and the combined load/degradation demo passed: 200/200 uploads, 0% errors, 575.6 ms p95, then a document remained durable while `ai` was stopped and completed after recovery.

## Residual risks and owners

| Severity | Finding | Disposition |
|---|---|---|
| Medium | TLS termination, ingress allow-lists, and production secret-manager rotation are deployment controls, not exercised by local Compose. | **Accepted temporarily. Owner:** Emir. **Due:** before M10 production deployment, no later than 2026-08-31. Deployment checklist must verify HTTPS-only ingress, private API↔AI networking, and managed secret rotation. |
| Low | `/actuator/health` is intentionally anonymous for platform liveness checks. It currently exposes no details. | **Accepted. Owner:** Emir. **Review by:** 2026-08-31 or before adding any further management endpoint; keep exposure limited to `health` and `show-details: never`. |
| Low | The BFF's 12 MB request buffer is intentionally bounded but still allocates per concurrent upload. | **Accepted. Owner:** Emir. **Review by:** M10 capacity test / 2026-08-31. Put an ingress body-size and concurrency limit in front of the web service before production traffic. |

No medium or low item is left without an owner and time-bounded decision.
