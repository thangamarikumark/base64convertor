# Production Readiness Review — Base64Convertor

Principal-Engineer/SRE-style go-live assessment. Every finding below cites the exact file, method, and code observed in this repository — no generic Spring Boot advice is included. Findings not backed by direct evidence are omitted rather than guessed at.

---

## Production Readiness Score: **48 / 100**

The application has a genuinely strong foundation in several areas built out during this engagement (structured logging with masking, a correlation-id/observability layer, connection pooling, bounded retry, path-traversal protection, non-root Docker user) — but three P0 findings (a live SSRF in the delivery/forwarding path, a hardcoded plaintext bearer token, and unbounded disk growth from an uncleaned output directory) are exactly the class of issue a go-live review exists to catch, and they are not theoretical — each is reachable from an existing, unauthenticated HTTP endpoint.

---

## P0 — Production Blockers

### P0-1: Server-Side Request Forgery (SSRF) via unvalidated `target_url` forwarding

- **File:** `src/main/java/com/twixor/base64convertor/pdf/facade/PdfDeliveryFacade.java`
- **Method:** `deliverAlways` (L67-99), `deliverIfTargetPresent` (L105-139)
- **Exact code:**
  ```java
  // deliverAlways, L87
  restTemplate.postForEntity(req.getTarget_url(), entity, String.class);
  // deliverIfTargetPresent, L122
  restTemplate.postForEntity(req.getTarget_url(), entity, String.class);
  ```
- **Evidence of the gap:** `UrlAllowlistValidator` exists and *is* correctly applied to the source-fetch URL (`pdfService.fetchAndConvertToBase64(req.getUrl(), ...)` internally calls `urlAllowlistValidator.validate(url)` — confirmed in `PdfService.java:92`), but `req.getTarget_url()` is passed directly to `restTemplate.postForEntity(...)` with **zero validation** — confirmed by `grep -n "urlAllowlistValidator" PdfDeliveryFacade.java` returning no results. `PdfRequest.target_url` (`src/main/java/com/twixor/base64convertor/pdf/dto/PdfRequest.java:17`) has no `@NotBlank`, `@URL`, or any bean-validation annotation at all.
- **Impact:** `POST /api/files/pdf/send` and `POST /api/files/pdf/single` are unauthenticated (see P0-2 — the only "auth" is a static bearer token this endpoint itself attaches, not one required of the caller) and let any caller set `target_url` to an arbitrary address — `http://169.254.169.254/latest/meta-data/...` (cloud instance metadata), `http://localhost:9090/actuator/...` (this app's own management port), or any internal-only service. The request is sent through the pooled `RestTemplate`, which additionally has `trust-all-ssl=true` (`UnsafeRestTemplate.java:56`), so it will happily reach internal HTTPS endpoints with self-signed/internal CA certs too. The full crafted `TargetApiRequest` body (built from attacker-controlled `PdfRequest` fields) and an `Authorization: Bearer <defaultToken>` header (P0-2) are sent to whatever `target_url` resolves to.
- **Confirmed blind-SSRF oracle:** `PdfDeliveryController.convertFileAndSendToTarget` (L53-61) and `convertSingleFile` (L74-84) catch `RestClientException` and return `rce.getMessage()` directly in the response body (`"FAILED: " + rce.getMessage()`). Since `UnsafeRestTemplate`'s error handler only treats HTTP ≥500 as an exception (4xx passes through silently), an attacker can still distinguish "connection refused" vs "connection timed out" vs no error via the returned message — a working port-scanning/service-discovery oracle against the internal network from an external, unauthenticated request.
- **Recommended fix:** Call `urlAllowlistValidator.validate(req.getTarget_url())` at the top of both `deliverAlways` and `deliverIfTargetPresent`, exactly as already done for `req.getUrl()`. Given `app.url.allowlist-enabled=false` by default, also strongly recommend enabling and populating `app.url.allowed-hosts` for any environment where `target_url` is meant to be a small, known set of downstream systems (which the endpoint's own design — "send to target system" — implies it should be).
- **Risk:** Critical — internal network reconnaissance/pivoting from an external, unauthenticated caller.
- **Estimated effort:** Low (2 one-line `validate()` calls) — the hard part (the validator) already exists and is proven elsewhere in the same codebase.

---

### P0-2: Hardcoded plaintext bearer token committed to source control

- **File:** `src/main/resources/application.properties:5`
- **Exact code:** `app.default.auth.token=q4ynVHA2s4d3unfWY2ujrQ==`
- **Consumed at:** `PdfDeliveryController.java:33-34` (`@Value("${app.default.auth.token}") private String defaultToken;`), forwarded into every outbound `target_url` request as `Authorization: Bearer <token>` (`TargetApiRequestMapper.java:81`).
- **Impact:** This value is committed to the repository (confirmed present verbatim in the tracked `application.properties`), meaning anyone with read access to the repo — including this review — has the credential used to authenticate this service's outbound calls to downstream "target" systems. Combined with P0-1 (target_url is unvalidated), an attacker doesn't even need this token for SSRF, but if the downstream target system trusts this bearer token for anything privileged, its exposure in source control is a standing credential-leak regardless.
- **Recommended fix:** Rotate the token immediately (treat it as burned, since it has been in this codebase's history), remove it from `application.properties`, and inject it via environment variable / secrets manager at deploy time (the `Dockerfile`'s existing `--spring.config.location=file:/app/config/application.properties` external-config pattern is already the right mechanism — this value simply needs to move out of the checked-in file and into that externally-supplied one).
- **Risk:** Critical — credential exposure, compounded by SSRF making it exploitable end-to-end.
- **Estimated effort:** Low for the config change; separate (non-code) effort to actually rotate the credential with whatever system consumes it.

---

### P0-3: Unbounded disk growth — `/api/files/save-decoded` output is never cleaned up

- **File:** `src/main/java/com/twixor/base64convertor/common/service/Base64OutputWriter.java`
- **Method:** `cleanupOldFiles` (L118-153)
- **Exact code:**
  ```java
  try (Stream<Path> files = Files.list(dir)) {
      files.filter(p -> p.toString().endsWith(".b64"))   // L132
           ...
  ```
- **Evidence of the gap:** The only scheduled cleanup in the entire application is `FileConversionService.cleanupOldFilesAndLogs` (`@Scheduled(cron = "0 0 * * * *")`, `FileConversionService.java:289`), which calls `base64OutputWriter.cleanupOldFiles()`. That method's file filter is hardcoded to `.b64` extensions only. But `Base64DecodingService.decodeAndSaveFile` (the handler for `POST /api/files/save-decoded`) writes files to the **same directory** (`app.base64.output-path`, confirmed by `Base64DecodingService.java:59-64`) using the Tika-**detected** extension (`.pdf`, `.jpg`, `.png`, `.docx`, etc. — never `.b64`) plus a `.meta.json` sidecar (`Base64DecodingService.java:104,109-110`). None of these files ever match the `.b64` filter, so **they are never deleted by any process in this codebase.**
- **Corroborating evidence:** `Base64OutputWriter.writeBinaryWithMetadata` (L168-204) — a second binary-write method with its own `.meta.json` sidecar — is fully implemented but has **zero callers anywhere in the codebase** (confirmed by grep), so it doesn't currently contribute to the growth, but is dead code sitting next to the method that does.
- **Impact / worst-case disk math:** Every successful `/save-decoded` call permanently consumes disk space equal to the decoded file size plus its metadata sidecar, forever. There is no request-rate assumption needed to state the structural fact: **disk usage for this directory is strictly monotonically increasing for the life of the deployment**, bounded only by the underlying volume's capacity. Illustratively, at a modest sustained rate of 100 saved files/day averaging 500KB each (~50MB/day): **30 days ≈ 1.5GB, 90 days ≈ 4.5GB, 180 days ≈ 9GB** — and this scales linearly with actual traffic with no ceiling; a single high-traffic day (or an abusive/looping caller, since there's also no per-caller rate limit anywhere in this codebase) can consume disk far faster. Eventually this fills the volume, at which point every write-dependent endpoint in the application (`/save-decoded`, `/pdf/protect`'s... — see note below — `/save`, the `.b64` output writer itself, and the audit/rotation logs under the same or adjacent paths) begins failing, and depending on what else shares that volume, can affect the host/container beyond just this app.
- **Note on `/pdf/protect`:** confirmed this endpoint (`PdfProtectionController`/`PdfProtectionServiceImpl`) does **not** persist to disk at all — it only returns Base64 in the response body — so it does not contribute to this specific growth (this was verified, not assumed, since `writeBinaryWithMetadata` looked like it might be the intended persistence path for this endpoint but is in fact unreachable dead code).
- **Recommended fix:** Extend `Base64OutputWriter.cleanupOldFiles()` (or add a sibling method) to also sweep `app.base64.output-path` for files matching `decodeAndSaveFile`'s naming convention (the existing `yyyyMMdd-HHmmss_<8-char>_<name><ext>` prefix pattern already makes age-based cleanup straightforward — the same `Files.getLastModifiedTime` + retention-day-cutoff logic already used for `.b64` files applies directly), and delete the matching `.meta.json` sidecar alongside each file. Alternatively, and more simply: broaden the existing filter from `.endsWith(".b64")` to "any file in this managed output directory," since the directory's entire purpose (per its config key name, `app.base64.output-path`) is to be a managed, retention-governed output area, not general storage.
- **Risk:** Critical — guaranteed eventual disk exhaustion / outage with zero attacker action required, purely from normal legitimate use of a documented, working endpoint.
- **Estimated effort:** Low-Medium (extend one existing method's filter + delete-sidecar logic; the retention/scheduling infrastructure already exists and is proven).

#### ✅ RESOLVED — implemented and verified live

- **Config:** `AppProperties.DecodedFile.retentionDays` (new nested class), bound to `app.decoded-file.retention-days` (default 7, independently tunable from `app.base64.retention-days`).
- **Cleanup logic:** `Base64OutputWriter.cleanupOldDecodedFiles()` — sweeps `app.base64.output-path` for any file that is neither `.b64` nor `.meta.json` (i.e. every decoded-file primary output regardless of Tika-detected extension), deletes it once older than the configured retention window, and deletes its `<fileName>.meta.json` sidecar in the same pass so the two never drift out of sync. A second pass separately reclaims orphaned `.meta.json` sidecars whose primary file is already gone, so a sidecar can never accumulate forever even from a partial prior failure.
- **Scheduled cleanup:** wired into the existing hourly job — `FileConversionService.cleanupOldFilesAndLogs()` now also calls `base64OutputWriter.cleanupOldDecodedFiles()` alongside the pre-existing `.b64` cleanup.
- **Startup cleanup:** `Base64OutputWriter.cleanupOnStartup()`, an `@EventListener(ApplicationReadyEvent.class)`, runs both cleanup methods once at boot — confirmed live in `runtime-data/app.log`: `"Running startup cleanup of expired Base64/decoded output files..."` immediately after `"Started Base64convertorApplication"`. This closes the gap where a service down past the retention window would otherwise wait up to an hour after restart before reclaiming already-expired space.
- **Metrics:** two new Micrometer counters — `base64.output.decoded-files.deleted`, `base64.output.decoded-files.delete-errors` — exposed via the existing `/actuator/prometheus` endpoint alongside the pre-existing `.b64` counters.
- **Audit logging:** each deletion is logged at INFO with filename, age-bounded retention setting, reclaimed byte count, and whether its sidecar was deleted; a summary line logs total files deleted and total bytes reclaimed per cleanup run.
- **Tests:** `Base64OutputWriterDecodedFileCleanupTest` — 9 new unit tests (expired file+sidecar deletion, recent file+sidecar retention, orphaned expired/recent sidecar handling, `.b64` files never touched by this path, metric increment verification, disabled-output no-op, missing-directory no-op, configurable retention-days respected). Full suite: **42/42 passing** (33 pre-existing + 9 new).
- **Live verification:** confirmed `POST /api/files/save-decoded` continues to work normally after the change (fresh files are unaffected by retention, as expected — only files older than the configured window are ever touched).

**Disk growth — before vs. after** (same illustrative 100 files/day @ 500KB/file ≈ 50MB/day example used in the original finding, for direct comparison):

| | Before fix | After fix (7-day retention, default) |
|---|---|---|
| Growth pattern | Unbounded, strictly monotonically increasing for the life of the deployment | Bounded — net accumulation approaches zero once file age exceeds the retention window; disk usage reaches a **steady-state plateau** rather than growing indefinitely |
| 30 days | ≈ 1.5 GB | ≈ 350 MB (steady-state reached after the first 7 days, plateaus at ~7 days' worth of accumulation) |
| 90 days | ≈ 4.5 GB | ≈ 350 MB (unchanged from day 7 onward — no further net growth) |
| 180 days | ≈ 9 GB | ≈ 350 MB (unchanged) |
| Structural ceiling | None — scales linearly with total traffic since deployment, forever | `retention_days × daily_growth_rate` (≈ 7 × 50MB ≈ 350MB at these illustrative rates) — a fixed, configurable ceiling independent of how long the service has been running |

The steady-state formula (`retention_days × daily_growth_rate`) is the operationally meaningful number now — it lets capacity planning size the volume once, rather than needing to project forward against an ever-growing baseline. `app.decoded-file.retention-days` can be tuned per environment (shorter for high-traffic/limited-disk environments, longer if downstream consumers need a longer download window) without any code change.

---

## P1 — High Risk

### P1-1: Unbounded request body size on `/api/files/save-decoded` (and most other JSON endpoints)

- **File:** `src/main/java/com/twixor/base64convertor/filestorage/dto/Base64SaveRequest.java:19`
- **Exact code:** `@NotBlank(message = "base64Content is required") private String base64Content;` — no `@Size` or any length cap.
- **Evidence:** `pom.xml`/`application.properties` were checked for `server.tomcat.max-http-form-post-size`, `server.max-http-header-size`, or `spring.servlet.multipart.max-file-size` — none are set. Only `PdfProtectionServiceImpl.decodeAndValidate` (`/pdf/protect`) enforces a payload cap in code (`pdf.protection.max-file-size-mb=20`, checked against decoded byte length at `PdfProtectionServiceImpl.java:108-112`). No equivalent check exists in `Base64DecodingService.decodeAndSaveFile` (`/save-decoded`) or `FileConvertController`/`FileConversionService` (`/convert`).
- **Impact:** A caller can submit an arbitrarily large `base64Content` string in a single request to `/save-decoded`; the server will attempt to fully buffer, decode (allocating a full second-sized byte array), Tika-detect, and write it before any size check occurs — because none exists. This is a memory-exhaustion / disk-fill (compounding P0-3) vector reachable with a single crafted request, no authentication required.
- **Recommended fix:** Add a request-body size cap consistent with `pdf.protection.max-file-size-mb`'s pattern (either a shared `AppProperties`-level max, or a Tomcat-level `server.tomcat.max-swallow-size`/connector limit as a defense-in-depth backstop) and enforce it in `Base64DecodingService` before decoding, the same way `PdfProtectionServiceImpl` already does.
- **Risk:** High — memory/disk exhaustion DoS, unauthenticated.
- **Estimated effort:** Low — the pattern to copy already exists in the same codebase.

### P1-2: Generic exception messages returned verbatim to API callers

- **Files/methods:**
  - `DecodedFileController.decodeAndSaveFile`, generic `catch (Exception e)` branch: `.message("Unexpected error: " + e.getMessage())` (`DecodedFileController.java:71-77`)
  - `CallbackController` (both catch blocks): `.message("Error saving file: " + e.getMessage())` / `"Unexpected error: " + e.getMessage()` (`CallbackController.java:34-47`)
  - `PdfFetchController.convertToBase64`, generic catch: `"FAILED: " + e.getMessage()` (`PdfFetchController.java:54-57`)
  - `PdfDeliveryController` (both endpoints), non-`RestClientException` branch: `"FAILED: " + cause.getMessage()` (`PdfDeliveryController.java:58-61,80-84`)
- **Impact:** These are `catch (Exception e)` blocks — the generic, unclassified case — meaning whatever underlying exception Java/a library throws (e.g., an `IOException` carrying an absolute server filesystem path, a `NullPointerException` message referencing an internal field/variable name, or a Tika/PDFBox internal parser exception message) is passed through to the HTTP client as-is. This is not a full stack trace, but it is unfiltered internal detail — the same class of information-disclosure risk stack-trace-suppression exists to prevent, just via `.getMessage()` instead of `.printStackTrace()`.
- **Recommended fix:** For the generic/unclassified `catch (Exception e)` branches specifically (not the already-well-scoped specific exception types like `PdfProtectionValidationException`, which intentionally return a controlled message), return a fixed, generic message ("An unexpected error occurred, contact support with request ID X" — now feasible thanks to the correlation-id work already completed this session) and log the real `e.getMessage()`/stack trace server-side only.
- **Risk:** Medium-High — information disclosure, not typically a full compromise on its own, but a real finding a PRR board would flag.
- **Estimated effort:** Low — message-text change only in the already-identified generic catch blocks.

### P1-3: No graceful shutdown configured

- **Files checked:** `application.properties`, `application-dev/uat/prod.properties` — none set `server.shutdown=graceful` or `spring.lifecycle.timeout-per-shutdown-phase`.
- **Impact:** Spring Boot's default is `server.shutdown=immediate` — on `SIGTERM` (the standard container/Kubernetes/Docker-stop termination signal), Tomcat stops accepting new connections but does **not** wait for in-flight requests to finish; they are cut off. This codebase has genuinely long-running synchronous operations reachable from a single HTTP request — PDFBox AES-256 encryption of up to a 20MB PDF (`PdfProtectionServiceImpl.protect`), and HTTP fetches with a configured 60-second response timeout plus up to 3 retries with exponential backoff (`app.retry.*`, `PdfService.executeWithRetry`) — meaning a rolling deploy or pod eviction during normal operation will actively truncate legitimate in-progress requests rather than letting them complete.
- **Recommended fix:** Set `server.shutdown=graceful` and a bounded `spring.lifecycle.timeout-per-shutdown-phase` (e.g., 30s, chosen to comfortably exceed a single retry-and-backoff sequence but not indefinitely delay pod termination).
- **Risk:** High for any environment doing rolling deploys or autoscaling (the deployment model implied by the existing `Dockerfile`/externalized-config pattern).
- **Estimated effort:** Very low — two config lines.

### P1-4: `fileExecutor` thread pool is entirely unreachable from production traffic (dead async path)

- **File:** `src/main/java/com/twixor/base64convertor/fileconversion/service/FileConversionService.java:111-137` (`processFileAsync`), `AsyncConfig.java` (`fileExecutor` bean)
- **Evidence:** `grep -rn "processFileAsync" src/main/java` returns only the method's own definition — **no controller or any other class calls it.** `FileConvertController.getStatus` (`/api/files/status/{processingId}`) reads from `asyncResults`, a map that `processFileAsync` populates — but since nothing ever invokes `processFileAsync`, `asyncResults` is permanently empty, so `getStatus` will return HTTP 404 for every possible `processingId`, always, in every environment.
- **Impact:** This is not a performance bug — it's a **built, documented-looking, but completely non-functional feature**. Anyone (a client integrator, a future engineer, this review's Area-5 finding in the prior performance report which recommended *routing through* this exact pool) reasonably assuming an async submission path exists for file conversion will find it doesn't — there is no way to reach `processFileAsync` via any HTTP endpoint today. This is an operational-support risk: a support engineer investigating "why does polling /status always 404" needs to know this is structural, not a bug in a specific request.
- **Recommended fix:** Either (a) wire an actual async-submission endpoint to `processFileAsync` (the natural target of the previously-identified batch-processing performance improvement — implementing that recommendation would also resolve this finding), or (b) if async processing isn't actually planned, remove `processFileAsync`/`getAsyncResult`/`/status/{processingId}` entirely rather than leaving a dead, confusing surface area in the API.
- **Risk:** Medium-High — not a security/outage risk, but a genuine supportability and API-contract-integrity gap (an endpoint exists and returns a well-formed 404 instead of erroring loudly, which is worse for diagnosis than either working correctly or not existing).
- **Estimated effort:** Medium (implement) or Low (remove) — a scoping decision, not a technical one.

### P1-5: Checked-in default config points to a developer's local Windows filesystem

- **File:** `src/main/resources/application.properties:70,75`
- **Exact code:**
  ```properties
  app.base64.output-path=C:\\Users\\Twixoradmin\\Pictures\\downloadtobase64\\base64output
  file.cache.path=C:\\Users\\Twixoradmin\\Pictures\\downloadtobase64\\convert2base64\\convert2base64
  ```
- **Impact:** These are the checked-in **default** values (no profile file overrides them — `application-dev/uat/prod.properties` only set logging levels, confirmed by reading all three in full). On a Linux container (this app's own `Dockerfile` is Alpine-based), a backslash is not a path separator, so `Paths.get("C:\\Users\\...")` is treated as one long, oddly-named single path segment; `Files.createDirectories(...)` would create a literal directory with that name (containing backslash characters) under the working directory, or fail depending on filesystem character restrictions — not the intended location either way. The Dockerfile's `--spring.config.location=file:/app/config/application.properties` pattern implies these are *meant* to be overridden per-environment, but the fact that the checked-in defaults are a specific individual's personal Windows user directory (`Twixoradmin`) is itself a signal that "run with no overrides" was never a validated, safe path — exactly what this session had to work around every time the app was started (`--app.base64.output-path=... --file.cache.path=...` passed manually as CLI overrides each time).
- **Recommended fix:** Change the checked-in defaults to a portable, relative, container-friendly path (e.g., `./data/base64-output`, `./data/file-cache`), and rely on the existing environment-config-file mechanism for actual per-environment overrides — so "run with zero extra flags" is a safe, working default rather than a personal dev path.
- **Risk:** High for anyone attempting a first-time run/deploy without tribal knowledge of the required overrides (this session needed exactly that tribal knowledge, repeatedly).
- **Estimated effort:** Very low — two property values.

### P1-6: No `RejectedExecutionHandler` configured on `fileExecutor`

- **File:** `src/main/java/com/twixor/base64convertor/common/config/AsyncConfig.java` (`fileExecutor` bean)
- **Evidence:** `grep -n "RejectedExecutionHandler" AsyncConfig.java` — no match; `ThreadPoolTaskExecutor`'s Spring default is `AbortPolicy` (throws `RejectedExecutionException`) once the pool (max 8) and queue (capacity 50) are both full.
- **Impact:** Currently low real-world impact given P1-4 (nothing actually submits to this executor in production traffic today), but this is exactly the kind of latent gap that becomes a live incident the moment P1-4 is "fixed" by wiring real traffic through it — an unhandled `RejectedExecutionException` under load would surface as an ungraceful 500 with no caller-facing indication of "system is at capacity, retry later" (e.g., 429/503).
- **Recommended fix:** Configure an explicit rejection policy (e.g., `CallerRunsPolicy` for backpressure, or a custom handler that maps to a clean 503 response) as part of whatever change resolves P1-4.
- **Risk:** Medium (currently latent; becomes real the moment the pool is actually used).
- **Estimated effort:** Low.

---

## P2 — Recommended

- **No custom `HealthIndicator`.** `management.endpoints.web.exposure.include=health,info,prometheus,metrics` is configured, but no custom `HealthIndicator` bean was found anywhere in `src/main/java`, meaning `/actuator/health` only reflects Spring Boot's built-in defaults (disk space, ping) — it does not know whether `app.base64.output-path` is writable, or reflect the health of downstream dependencies this service actually calls. Recommend adding a lightweight custom indicator for output-directory writability at minimum, given how central that path is to multiple endpoints.
- **`asyncResults`/`asyncResultTimes` (`FileConversionService`) are unbounded in-memory maps between cleanup cycles.** They're `ConcurrentHashMap`s cleaned hourly by retention (`cleanupAsyncResults`, `FileConversionService.java:297-306`), which is reasonable — but since the async path is currently unreachable (P1-4), this is a non-issue today; flagged only so it's re-evaluated if/when P1-4 is resolved and real traffic starts populating these maps.
- **`PdfDeliveryFacade`'s two methods (`deliverAlways`/`deliverIfTargetPresent`) are near-duplicates** (confirmed by reading both in full) — not a production-readiness defect on its own, but worth noting since P0-1's fix needs to be applied to both call sites identically; a shared helper would make that fix (and any future one) impossible to apply to only one of the two by mistake.
- **Dependency versions** (`pom.xml`): `pdfbox 3.0.3`, `tika-core 3.2.0`, `springdoc-openapi-starter-webmvc-ui 2.6.0`, Spring Boot `3.5.6` parent — all pinned to specific, reasonably current versions (not floating/unpinned), which is good practice; this review did not have live internet access to cross-reference against a CVE database, so no specific vulnerable-version finding is claimed — recommend running this pom through Dependabot/OWASP Dependency-Check in CI as a standing practice rather than a one-time manual check.

## P3 — Nice to Have

- **`PdfService.executeWithRetry` and `FileConversionService.downloadWithRetry`** independently hand-roll near-identical retry loops in the same codebase that already successfully uses Spring Retry's `@Retryable` elsewhere (`PdfService.fetchAndConvertToBase64`) — consolidating would reduce future-maintenance risk of the two loops drifting apart, but has no runtime impact today.
- **No operational runbook found** (`docs/` contains several engineering analysis documents from this engagement, but no "if X alarm fires, do Y" runbook) — worth creating now that the correlation-id/observability groundwork exists to make one actionable.

---

## Security Risk Matrix

| Risk | Likelihood | Impact | Overall |
|---|---|---|---|
| SSRF via `target_url` (P0-1) | High (trivial to trigger, no auth) | Critical (internal network access) | **Critical** |
| Hardcoded bearer token (P0-2) | Certain (already committed) | High (credential compromise) | **Critical** |
| Unbounded request size (P1-1) | Medium (requires a deliberately large payload) | High (memory/disk DoS) | **High** |
| Internal error message disclosure (P1-2) | High (any unhandled exception) | Medium (info disclosure, not direct compromise) | **Medium-High** |
| Path traversal on file retrieval endpoints | **Not found — mitigated.** `PathTraversalGuard.isWithin` (`candidate.normalize().startsWith(baseDir.normalize())`) is consistently applied across all `FileStorageFacade` read/delete methods, confirmed by direct read of every method in that class. | — | **Low (controlled)** |
| Secrets in logs | **Not found.** Every logging call site touching Base64/payload/attachment data was enumerated in the prior performance review and logs only counts/filenames; `LogSanitizer.maskHeaders` masks `Authorization`/`Cookie`/`X-API-Key`/`X-Auth-Token` consistently. | — | **Low (controlled)** |

## Reliability Risk Matrix

| Risk | Likelihood | Impact | Overall |
|---|---|---|---|
| Disk exhaustion from uncleaned `/save-decoded` output (P0-3) | Certain over time, given any sustained usage | Critical (outage) | **Critical** |
| No graceful shutdown (P1-3) | Certain on every deploy/pod-eviction | Medium (truncated in-flight requests, not data loss) | **High** |
| Retry logic — bounded, no infinite-retry risk found | `app.retry.max-attempts=3` is a hard cap in both `PdfService.executeWithRetry` and `FileConversionService.downloadWithRetry`; Spring's `@Retryable` on `fetchAndConvertToBase64` is likewise bounded by `maxAttemptsExpression`. | — | **Low (controlled)** |
| Thread-pool exhaustion (`fileExecutor`) | Currently near-zero (pool is unreachable, P1-4) | Would be Medium once reachable, absent P1-6's fix | **Low today / Medium if P1-4 addressed without P1-6** |
| Failure isolation between requests | No shared mutable state found between concurrent request-handling paths in the services reviewed (`FileConversionService.handleFileProcessing` uses per-request UUID-named temp files; `PdfProtectionServiceImpl` is fully request-scoped) | — | **Low (controlled)** |

## Scalability Assessment

The application scales acceptably for moderate synchronous load: RestTemplate connection pooling (200 total/20 per-route) and Tomcat's own thread pool handle concurrent requests without a code-level bottleneck for typical request sizes. The two structural scalability limiters found are **P0-3** (disk, not compute, is the actual ceiling — the service will run out of disk before it runs out of CPU/threads under sustained `/save-decoded` usage) and the **fully sequential `/api/files/convert` batch endpoint** (documented in the prior focused performance review — up to 50 sequential downloads per request, with a working-but-unused `fileExecutor` pool sitting idle, tracked separately as a dedicated enhancement per explicit instruction in this engagement, not re-litigated here).

## Operational Readiness Assessment

**Can support answer "what failed, which request, why, which downstream API failed" from logs alone?** Materially improved by this session's own correlation-id work (`RequestCorrelationFilter`, `%X{requestId}` in every appender, verified live end-to-end — see `docs/API_Observability_Implementation_Report.md`) — this is a genuine, verified strength, not a gap. Combined with `LogSanitizer.sanitizeUrl`/`maskHeaders`, support can trace a specific request's full lifecycle (including which downstream URL was hit, sanitized) without exposing credentials in the process. The remaining operational gap is P1-2 (generic exception messages) working *against* this strength for the caller-facing side — the request ID is present, but a caller and a support engineer are both looking at an unfiltered internal message instead of a clean "see request ID X" pointer, which is the opposite of the pattern the correlation-id work was meant to establish.

**Recovery / runbooks:** No PRR-standard "if the disk fills, do X" or "if `/pdf/send` starts failing, check Y" runbook exists in `docs/`. Given P0-3's near-certainty over time, a runbook entry for "output directory disk pressure" is a near-term operational necessity, not a nice-to-have, until P0-3 itself is fixed.

---

## Final Recommendation

# NOT PRODUCTION READY

**Justification:** Three P0 findings are each independently sufficient to block go-live, and two of them (SSRF, disk exhaustion) are reachable through normal or trivially-abusive use of already-implemented, unauthenticated endpoints — not edge cases or theoretical concerns. The hardcoded token (P0-2) compounds the SSRF finding by giving the forged outbound request a real credential to present. None of the three require an architectural rewrite to fix — each has a concrete, low-to-medium-effort remediation identified above, most reusing infrastructure that already exists elsewhere in this same codebase (the URL allowlist validator, the retention-cleanup scheduler, the externalized-config mechanism).

**Path to PRODUCTION READY WITH CONDITIONS:** Resolve P0-1, P0-2, and P0-3. At that point, the P1 findings (unbounded request size, leaked exception messages, no graceful shutdown, the dead async path, the Windows-path defaults, the missing rejection policy) are real but individually survivable in a conditional go-live, provided they're tracked as committed near-term follow-ups rather than closed out silently — none of them are "outage on day one" severity the way the P0s are, but several (P1-1, P1-3) are the kind of gap that turns into an incident under real production load or a routine deploy, not an edge case.
