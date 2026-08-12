# Logging Assessment Report

**Status: ANALYSIS ONLY. No code has been modified. No logging levels have been changed. No logs have been removed.** This report is the required pre-implementation deliverable; code changes should only be proposed after this assessment is reviewed and approved.

**Scope:** Full scan of `src/main/java/com/twixor/base64convertor/**/*.java` (49 files, post Phase-A refactor). Logging frameworks in use: Log4j2 (`org.apache.logging.log4j.Logger` — most classes), SLF4J (`org.slf4j.Logger` via Lombok `@Slf4j` — `Base64OutputWriter`, `FileTypeDetectionService`, `Base64DecodingService`, `PdfProtectionService`, `LoggingInterceptor`, `UnsafeRestTemplate`), plus one custom file-based audit logger (`FileConversionService.logAudit`) that writes directly to disk, bypassing both frameworks entirely. No new logging framework is proposed or needed — this is a usage/policy problem, not a tooling problem.

---

# Phase 1: Logging Inventory

Total statements found: **115** (33 `.info`, 1 `.debug`, 27 `.warn`, 33 `.error`, 18 `httpLogger.*`, 3 `System.out.println`, 0 `printStackTrace()`). No custom logging wrapper classes exist besides the file-based audit trail noted above.

| File | Line | Level | Current Message (paraphrased) | Risk |
|---|---|---|---|---|
| `Base64convertorApplication.java` | 11 | `System.out` | Startup banner | LOW |
| `common/config/UnsafeRestTemplate.java` | 78, 97 | INFO | HttpClient init params (pool sizes, timeouts, trust-all flag) | LOW |
| `common/config/UnsafeRestTemplate.java` | 127 | ERROR | **Full raw response body of any 5xx from any outbound call** | **CRITICAL** |
| `common/service/Base64OutputWriter.java` | 102, 144, 185 | INFO | File path written/deleted (contains generated filename only, not content) | LOW |
| `common/service/Base64OutputWriter.java` | 107, 147, 200 | WARN | I/O failure message | LOW |
| `common/service/Base64OutputWriter.java` | 151 | ERROR | Cleanup failure + stack trace | LOW |
| `common/service/FileTypeDetectionService.java` | 63, 77, 88, 93 | WARN/INFO | Detection status, byte counts, MIME type | LOW |
| `common/service/FileTypeDetectionService.java` | 105, 113 | ERROR | Exception message from Base64 decode failure | LOW-MEDIUM (exception message could echo a fragment of malformed input in rare cases) |
| `common/util/LoggingInterceptor.java` | 36–40 | INFO | **Full outbound HTTP request: URI, method, headers, and entire request BODY as UTF-8 string** | **CRITICAL** |
| `common/util/LoggingInterceptor.java` | 45–48 | INFO | **Full outbound HTTP response: status, headers, and entire response BODY as UTF-8 string** | **CRITICAL** |
| `fileconversion/service/FileConversionService.java` | 163, 170 | INFO | Source URL + local temp/output file path | MEDIUM (source URL may itself contain query-string tokens — see Phase 2) |
| `fileconversion/service/FileConversionService.java` | 188 | ERROR | Source URL + exception | MEDIUM (same URL concern) |
| `fileconversion/service/FileConversionService.java` | 221, 282, 317, 321, 340, 349, 354, 83, 85 | WARN/INFO/ERROR | Retry attempts, cache/log cleanup housekeeping | LOW |
| `fileconversion/service/FileConversionService.java` | 261–279 (`logAudit`) | **custom file audit logger** | Timestamp, status, fileName, mimeType, **source URL**, message — written unconditionally to `<cache>/logs/conversion_audit_*.log`, **not governed by any `logging.level.*` property** | MEDIUM (URL may carry tokens; bypasses all log-level policy by design) |
| `filestorage/controller/Base64FileController.java` | 37, 55, 69, 84 | ERROR | Filename + exception message + stack trace | LOW |
| `filestorage/controller/CallbackController.java` | 37, 44 | ERROR | Exception message + stack trace | LOW |
| `filestorage/controller/DecodedFileController.java` | 40 | INFO | Saved filename | LOW |
| `filestorage/controller/DecodedFileController.java` | 58, 65, 72, 95, 110 | ERROR | Exception message (one branch logs the raw `IllegalArgumentException` message from a failed `Base64.getDecoder().decode()` call) | **MEDIUM** — see Phase 2 |
| `filestorage/facade/FileStorageFacade.java` | 120 | INFO | Saved filename, size, **and `request.getExtraParams()` — an arbitrary caller-supplied map with no schema** | **HIGH** |
| `filestorage/facade/FileStorageFacade.java` | 67, 71, 75, 90, 94, 151, 168, 172, 176, 191, 195, 203 | WARN | Filename + traversal/not-found/wrong-extension warnings | LOW |
| `filestorage/facade/FileStorageFacade.java` | 80, 99, 158, 181, 201 | INFO | Filenames, counts | LOW |
| `filestorage/service/Base64DecodingService.java` | 75, 84, 105, 111 | INFO/WARN | Detected MIME/extension, saved filename, sanitization fallback | LOW |
| `health/controller/TestController.java` | 12, 18 | `System.out` | `"Ping endpoint hit!"`; **`"Echo endpoint received: " + body` — logs the entire raw request body verbatim, unconditionally, uncontrolled by any logging framework or level** | **HIGH** |
| `pdf/controller/PdfDeliveryController.java` | 40 | INFO | Startup banner | LOW |
| `pdf/controller/PdfDeliveryController.java` | 56, 59, 77, 81 | ERROR | fileName + exception message + stack trace | LOW |
| `pdf/controller/PdfFetchController.java` | 37, 62 | INFO | Startup banner; **source URL** | LOW-MEDIUM |
| `pdf/controller/PdfFetchController.java` | 45 | INFO | Source URL | LOW-MEDIUM |
| `pdf/controller/PdfFetchController.java` | 50, 54 | ERROR | Source URL + exception + stack trace | LOW-MEDIUM |
| `pdf/controller/PdfProtectionController.java` | 34 | INFO | Startup banner | LOW |
| `pdf/controller/PdfProtectionController.java` | 56, 62, 68, 74 | ERROR | Exception message only — **confirmed: never logs `name`, `dob`, the derived password, or the Base64 PDF content** | LOW *(positive control — see Phase 2)* |
| `pdf/facade/PdfDeliveryFacade.java` | 85, 87, 120 | INFO | fileName + **target_url** (destination endpoint URL, potentially internal-network-revealing) | LOW-MEDIUM |
| `pdf/facade/PdfProtectionFacade.java` | 78 | WARN | Output filename only | LOW |
| `pdf/service/PdfProtectionService.java` | 146 | INFO | Byte counts only — **confirmed: password never logged** | LOW *(positive control)* |
| `pdf/service/PdfService.java` | 92 | DEBUG | Source URL | LOW |
| `pdf/service/PdfService.java` | 107, 113, 119, 131, 132 | ERROR/WARN/INFO | Source URL, HTTP status, exception message | LOW-MEDIUM |
| `pdf/service/PdfService.java` | 162–166 | `httpLogger.info` | Request URI/method + **masked** headers + `"[Payload present]"`/`"(none)"` flag (does **not** log actual body — good practice) | LOW *(positive control, but see Phase 2 note on why it's undermined)* |
| `pdf/service/PdfService.java` | 171–173 | `httpLogger.info` | Response status + **unmasked** response headers (`resp.getHeaders()` — not passed through `maskSensitiveHeaders`) | **MEDIUM** |
| `pdf/service/PdfService.java` | 193, 202, 214, 223, 228, 250, 304 | WARN/INFO/ERROR | File size/type diagnostics, retry attempts, validation errors — URL and byte counts only | LOW |

---

# Phase 2: Sensitive Data Exposure Report

## CRITICAL

### 1. `LoggingInterceptor` logs the full raw body of every outbound HTTP request and response
**File:** `common/util/LoggingInterceptor.java:40` (request body) and `:48` (response body).
**Mechanism:** This interceptor is registered on the **single, shared, `@Primary` `RestTemplate` bean** in `common/config/UnsafeRestTemplate.java:42` — meaning it fires for *every* outbound call the application makes, from every module, with no opt-out.
**Concrete exposure paths:**
- `PdfDeliveryFacade` builds a `TargetApiRequest` whose `message.content.attachment.attachmentData` field holds the **full Base64-encoded PDF** and POSTs it via this shared `RestTemplate` (`PdfDeliveryFacade.java:86`, `:121`). The entire multi-kilobyte-to-megabyte Base64 payload is written to the log file at **INFO** level on every `/pdf/send` and `/pdf/single` call.
- `PdfService.fetchAndConvertToBase64` and `PdfService.executeWithRetry` both route through this same `RestTemplate` to fetch source files; the **raw binary response body** (the source PDF/image/document bytes) is coerced to a UTF-8 string and logged in full on every `/pdf/convert/base64`, `/pdf/convert/base64dynamic`, `/pdf/send`, `/pdf/single`, and (transitively, once wired) `/convert` call.
- Header masking exists (`SENSITIVE_HEADERS`: `authorization`, `cookie`, `set-cookie`, `x-api-key`, `x-auth-token`) but **there is no body masking at all** — this is a body-content leak, not a header leak.
**Why this matters given the target policy:** the stated INFO-level policy (UAT/staging default) explicitly says "No Base64 content logging" and "No PDF content logging." This interceptor violates that at INFO — meaning it is a violation in every non-production environment today, and would violate it in production too if anyone ever raised the root logger above ERROR for troubleshooting (a realistic support scenario).

### 2. `UnsafeRestTemplate`'s custom error handler logs the full raw response body on any 5xx
**File:** `common/config/UnsafeRestTemplate.java:126–127`.
**Exposure:** Any downstream 5xx response — which could itself be an error page echoing back partial request data, or (for `/pdf/*` fetches) a corrupted/partial binary body — is logged **at ERROR level, in production's default level**. This is the one finding here that is live in the Level-1/production default, not just UAT.

## HIGH

### 3. `TestController.echo` logs the entire raw request body via `System.out.println`
**File:** `health/controller/TestController.java:18`.
**Exposure:** `POST /api/test/echo` accepts an arbitrary raw string body and prints it verbatim to stdout, completely bypassing Log4j2/SLF4J and therefore **every `logging.level.*` control in `application.properties`** — this line logs at every level, in every environment, always. If this endpoint is ever used for connectivity testing with a real payload (or probed by an external party), whatever they send is captured in container/system logs regardless of configured log level.

### 4. `FileStorageFacade.saveRawBase64` logs the caller-supplied `extraParams` map verbatim
**File:** `filestorage/facade/FileStorageFacade.java:120`.
**Exposure:** `Base64SaveRequest.extraParams` is a `@JsonAnySetter`-captured, schema-less map — the `/callback` endpoint's caller can put **anything** in it (arbitrary key/value pairs), and it is logged at INFO on every save. Since this field has no defined shape, there is no way to know in advance whether a caller might put a password, token, or PII field name in there — this is an open-ended exposure surface by design of the DTO, not a specific known leak, but it is the kind of finding that becomes a real incident the first time an integrating team decides to pass something sensitive through this field.

## MEDIUM

### 5. Source/target URLs logged in full, potentially including query-string tokens
**Files:** `fileconversion/service/FileConversionService.java:163,170,188` (+ its `logAudit` method), `pdf/service/PdfService.java:92,107,113,119,131,223,228`, `pdf/controller/PdfFetchController.java:45,62,50,54`, `pdf/facade/PdfDeliveryFacade.java:85,87,120` (`target_url`).
**Exposure:** Every one of these endpoints accepts a caller-supplied `url` (and `target_url`) with no restriction on format — a caller could legitimately pass a pre-signed URL or a URL with an embedded API key/token as a query parameter (e.g., `?token=...` or `?sig=...`), which would then be logged in full, including at **INFO in the UAT/staging target policy**, and via the custom audit file logger regardless of any configured log level.

### 6. `DecodedFileController` logs raw exception messages from `Base64.getDecoder().decode()`
**File:** `filestorage/controller/DecodedFileController.java:58` (also mirrored conceptually in `PdfProtectionController.java:62` and `FileTypeDetectionService.java:105`).
**Exposure:** `IllegalArgumentException` messages from Java's Base64 decoder are generally generic (e.g., "Illegal base64 character"), so this is lower severity than the CRITICAL/HIGH items — but it is technically logging exception detail derived from caller-supplied content, and should be reviewed rather than assumed safe, particularly if the JDK's exception message format ever changes to include offending character context.

### 7. `PdfService`'s manual `httpLogger` logs unmasked response headers
**File:** `pdf/service/PdfService.java:173` (`httpLogger.info("Headers  : {}", resp.getHeaders())`) — contrast with line 165, which correctly applies `maskSensitiveHeaders()` to the *request* headers. The *response* headers are logged raw. If a downstream server ever returns a `Set-Cookie` or similar sensitive header on the response, it is logged unmasked. (Note the `LoggingInterceptor`, which also fires on this same call, *does* mask `set-cookie` — so today's actual output likely goes through both loggers with inconsistent treatment of the same data, which is itself a maintainability/consistency problem worth fixing alongside the content issue.)

## Confirmed NOT exposed (positive controls — do not regress these)

- **Passwords:** `PdfProtectionService.buildPassword()` and `.protect()` never log the derived password or the request's `name`/`dob` fields, anywhere. Confirmed by grep across the whole codebase — zero log statements reference `PdfProtectRequest.getName()`, `.getDob()`, or the local `password` variable.
- **The PDF-protection owner password** (randomly generated per document in `PdfProtectionService.protect`) is never logged or returned — confirmed.
- **Authorization/Cookie header values**: consistently masked wherever headers are logged (both `LoggingInterceptor.maskHeaders` and `PdfService.maskSensitiveHeaders`), except for the one gap noted in Finding 7 above (response headers on one specific call path).
- **No DOB, email, phone, or other classic PII fields exist elsewhere in the DTOs** besides `PdfProtectRequest.dob`, which is confirmed never logged.
- **`printStackTrace()`**: zero occurrences in the codebase — all exception logging goes through the configured logging frameworks (a genuinely good baseline to build on).

---

# Phase 3: Logging Level Recommendations

This section proposes *target* levels only — **no code will be changed until this report is approved**, per the constraints.

| Current | File:Line | Recommended | Rationale |
|---|---|---|---|
| `httpLogger.info("Body: {}", new String(body, UTF_8))` | `LoggingInterceptor.java:40` | **REMOVE COMPLETELY**, or at most `DEBUG` with size-based truncation and a body-type check (skip entirely for `multipart`/binary/large bodies) | Full request-body logging can never be safe at INFO given this app's payload shapes (Base64/PDF content) |
| `httpLogger.info("Body: {}", body)` | `LoggingInterceptor.java:48` | **REMOVE COMPLETELY**, or at most `DEBUG` with the same truncation/size-check treatment | Same reasoning — response bodies are the fetched file content |
| `log.error("Remote API responded with HTTP {}: {}", statusCode, body)` | `UnsafeRestTemplate.java:127` | Keep at `ERROR`, but change to `log.error("Remote API responded with HTTP {}", statusCode)` — drop `body` from the log line, or cap it to a fixed prefix length (e.g., first 200 chars) with an explicit "(truncated)" marker | This is the one finding active in production's default (ERROR) level — highest priority fix |
| `System.out.println("Echo endpoint received: " + body)` | `TestController.java:18` | **REMOVE COMPLETELY**, or replace with `logger.debug("Echo endpoint invoked, body length={}", body.length())` under the standard framework so it obeys `logging.level.*` | Currently bypasses all level control; also a HIGH-risk raw-body leak |
| `System.out.println("Ping endpoint hit!")` | `TestController.java:12` | Replace with `logger.debug(...)` under the standard framework, or remove | No sensitive content, but should obey level control like everything else, and a liveness probe firing on every health-checker hit doesn't belong in `INFO` |
| `logger.info("Saved Base64 file: ... params: {}", ..., request.getExtraParams())` | `FileStorageFacade.java:120` | `logger.info("Saved Base64 file: {} (size: {} bytes)", outFileName, fileSize)` — **drop `extraParams` from the log line entirely** | Schema-less caller-supplied map; no way to guarantee it never carries something sensitive |
| `logger.info("Converted file from URL: {}. ...", url, ...)` and all other `url`-logging INFO statements | `FileConversionService.java`, `PdfService.java`, `PdfFetchController.java`, `PdfDeliveryFacade.java` (target_url) | Keep at `INFO`, but log a **sanitized** form of the URL — strip query string, log only scheme+host+path (e.g., `UriComponentsBuilder.fromUriString(url).replaceQuery(null).build()`) | Query strings can carry tokens; host+path is enough for operational troubleshooting |
| `logAudit(...)` custom file logger | `FileConversionService.java:261` | Keep as a dedicated audit trail (see Phase 4 — Audit Logs), but sanitize the URL the same way as above before writing | Currently bypasses `logging.level.*` by design (this is expected/acceptable for an audit trail, but the content written to it must still be sanitized) |
| `httpLogger.info("Headers : {}", resp.getHeaders())` | `PdfService.java:173` | `httpLogger.info("Headers : {}", maskSensitiveHeaders(resp.getHeaders()))` | Apply the same masking already used for request headers three lines above |
| `logger.debug("Fetching file from URL: {}", url)` | `PdfService.java:92` | Keep at `DEBUG` (already correctly leveled) — apply the same URL-sanitization treatment | Already appropriately scoped to dev-only visibility; just needs the query-string strip |
| All `Startup/initialized` `logger.info(...)` (`PdfFetchController:37`, `PdfDeliveryController:40`, `PdfProtectionController:34`) | pdf controllers | Keep at `INFO`, no change needed | Pure operational/lifecycle events, zero sensitive content |
| All filename/count/status `logger.info(...)` across `FileStorageFacade`, `Base64DecodingService`, `Base64OutputWriter`, `FileTypeDetectionService` | multiple | Keep at `INFO`, no change needed | Matches the target INFO policy exactly (file names, counts, status) |
| All `WARN` traversal/not-found/validation warnings | `FileStorageFacade`, `FileTypeDetectionService`, `PdfService` | Keep at `WARN`, no change needed | Appropriate — operationally actionable, contain filenames/counts only, no payload content |
| All `ERROR` exception-with-message-only logs in controllers | all `*Controller.java` | Keep at `ERROR`, but see Phase 9 for the exception-object-attachment recommendation | Level is correct; the *pattern* (message-only vs. exception-object) needs review per-site |

**Example transformations matching the task's own format:**

Before:
```java
logger.info("Base64: {}", content);   // (representative of the LoggingInterceptor pattern)
```
After:
```java
logger.debug("File received. Size={} bytes", content.length());   // or remove entirely
```

Before:
```java
logger.info("Saved Base64 file: {} (size: {} bytes) from request with params: {}",
        outFileName, fileSize, request.getExtraParams());
```
After:
```java
logger.info("Saved Base64 file: {} (size: {} bytes)", outFileName, fileSize);
```

---

# Phase 4: Production Logging Policy

## ERROR (Level 1 — production default)

**Allowed:**
- Exception type, message, and stack trace for genuine failures
- HTTP status codes of failed calls
- Sanitized identifiers: fileName (generated, not user-controlled path), sanitized URL (host+path, no query string), request correlation/processing IDs
- Byte counts, sizes, durations

**Not allowed:**
- Base64 content, PDF/file binary content (raw or string-coerced)
- Passwords, derived passwords, secret keys, tokens
- Authorization/Cookie header values (even partially)
- DOB or any PII field value
- Raw request/response bodies of any kind
- Caller-supplied schema-less maps (`extraParams`) or their contents
- Full URLs with query strings

## INFO (Level 2 — UAT/SIT/Staging)

**Allowed:** everything ERROR allows, plus:
- "API received" / "API completed" lifecycle markers per endpoint
- Processing duration
- Sanitized file names, MIME types, byte counts
- Record/item counts (e.g., "Listed 12 Base64 files")
- Status transitions (SUCCESS/FAILURE/FILE_TOO_LARGE, etc.)
- Component/bean initialization messages

**Not allowed:** same list as ERROR's "Not allowed," with zero exceptions — INFO is not a looser tier for payload content, it is a wider tier for *operational* events only.

## DEBUG (Level 3 — Development only)

**Allowed:** everything INFO allows, plus:
- Internal execution-flow breadcrumbs (which branch/method was taken)
- Outbound HTTP request/response **metadata only**: URI (sanitized), method, masked headers, and a body **presence flag + size**, matching the existing good pattern at `PdfService.java:166` (`"[Payload present]"` / `"(none)"`) — generalize this pattern, do not regress to logging actual body content even in DEBUG
- Retry attempt counters and backoff delays
- Detailed validation-failure reasoning (still no raw sensitive values)

**Not allowed, even in DEBUG:**
- Secrets, tokens, passwords, cookie/authorization values — **never**, at any level, per the task's own constraint
- Full Base64/file content — DEBUG may confirm size/type but must not print content, since local developer logs are still a leakage surface (shared dev environments, screen-shares, pasted-into-tickets logs)

---

# Phase 5: Configuration Recommendations

Current `application.properties` has no `logging.level.*` entries at all today (only `logging.level.org.springframework.web.client.RestTemplate=INFO`) — meaning the application currently runs at Log4j2's default level (INFO) in every environment, with no environment-specific override. This is itself a gap: there is no mechanism today to enforce "ERROR in production" — it must be added, not just decided.

| Environment | `logging.level.root` | `logging.level.com.twixor.base64convertor` | `logging.level.com.twixor.base64convertor.http` (the `httpLogger` name) | Notes |
|---|---|---|---|---|
| Development | `WARN` | `DEBUG` | `DEBUG` | Keep third-party framework noise down; full app-level detail for local troubleshooting. Requires the DEBUG-tier content fixes in Phase 3/4 to be safe even here. |
| UAT / SIT / Staging | `WARN` | `INFO` | `INFO` | Matches the task's Level 2 policy. **Must not be enabled until the CRITICAL/HIGH findings in Phase 2 are fixed** — today, enabling INFO in these environments actively leaks Base64/PDF content via `LoggingInterceptor`. |
| Production | `ERROR` | `ERROR` | `ERROR` | Matches the task's Level 1 policy. The `UnsafeRestTemplate` CRITICAL finding (body-in-error-log) is live at this exact level today and is the single highest-priority fix regardless of any other rollout timing. |

Recommend introducing environment-specific property files (e.g., `application-dev.properties`, `application-uat.properties`, `application-prod.properties`) activated via `spring.profiles.active`, each carrying only the three `logging.level.*` lines above — rather than hand-editing one shared file per deployment. (This is a configuration-structure recommendation for the eventual implementation phase, not a change made now.)

---

# Phase 6: Refactoring Plan (proposed — not yet implemented)

## IMMEDIATE FIX (production-impacting or trivially safe to fix)

1. `UnsafeRestTemplate.java:127` — stop logging the full response body on 5xx; log status code only (or a fixed-length truncated prefix). **This is the only finding currently reachable at ERROR level in production.**
2. `common/util/LoggingInterceptor.java:40,48` — remove full body logging from both request and response; replace with size/presence metadata only, generalizing the safe pattern already used in `PdfService.java:166`.
3. `TestController.java:18` — remove or replace the raw-body `System.out.println` in `/echo`; it bypasses all logging-level control entirely.
4. `FileStorageFacade.java:120` — drop `request.getExtraParams()` from the INFO log line.
5. Add `logging.level.*` entries to `application.properties` (or environment-specific profiles) — today, nothing enforces "ERROR in production"; the policy exists only as intent until this is added.

## RECOMMENDED (real risk reduction, not yet actively exploited but should be closed before wider UAT/staging rollout)

6. Sanitize all logged URLs (source `url` and `target_url`) to strip query strings before logging, across `FileConversionService`, `PdfService`, `PdfFetchController`, `PdfDeliveryFacade`, and the `logAudit` file writer.
7. `PdfService.java:173` — apply `maskSensitiveHeaders()` to response headers, matching the treatment already given to request headers three lines above.
8. `TestController.java:12` — move the ping log line under the standard framework (still low priority; no sensitive content, but for consistency and to obey level control).
9. Review `DecodedFileController.java:58` and the two other `IllegalArgumentException`-message log sites for Base64-decode failures — confirm (or constrain) that JDK decoder exception messages never echo offending input content.

## OPTIONAL (hygiene / consistency, no active exposure)

10. Consolidate the two independent HTTP-logging mechanisms (`LoggingInterceptor` on the shared `RestTemplate` bean, and the manual `httpLogger` calls inside `PdfService.fetchAndConvertToBase64Dynamic`) into one — today they overlap on the same calls with inconsistent masking behavior, which is a maintainability risk as much as a security one.
11. Standardize exception logging across controllers to consistently pass the exception object (not just `e.getMessage()`) so stack traces are available for every ERROR-level failure — see Phase 9 below for specifics.
12. Consider introducing a lightweight request-correlation ID (e.g., a `requestId` MDC value set per request) so ERROR-level logs in production can be correlated without needing payload content — directly enables the "Good" exception-logging example in Phase 9 without inventing new sensitive fields.

---

# Phase 7 (task item 4): Log Categorization

| Category | Representative log sites |
|---|---|
| **Business Logs** | "Saved decoded file", "Detected MIME type", "Listed N Base64 files", "File converted successfully", status transitions (SUCCESS/FAILED/FILE_TOO_LARGE) |
| **Security Logs** | Path-traversal attempt warnings (`FileStorageFacade`'s 6 "Attempt to access/delete ... outside output directory" lines), non-`.b64`/wrong-extension access attempts, `.meta.json` direct-access-blocked warning |
| **Audit Logs** | `FileConversionService.logAudit` (dedicated file-based trail, not level-gated by design) |
| **Error Logs** | All `ERROR`-level statements across controllers/services/facades |
| **Debug Logs** | `PdfService.java:92` (the sole existing `.debug()` call); the httpLogger request/response metadata blocks (once fixed per Phase 3, these belong at DEBUG rather than their current INFO) |
| **Performance Logs** | Micrometer `Timer`/`Counter` instrumentation (`pdf.conversions`, `pdf.protect.duration`, `base64.conversion.duration`, etc.) — not text logs, but the closest existing equivalent to "performance logging" in this codebase; no text log statements currently report duration directly (a gap — see Phase 6 item 12 for how a correlation ID could also carry duration context) |

---

# Phase 8 (task item 9): Exception Logging Review

**Overall verdict: no `printStackTrace()` anywhere, and the large majority of `ERROR`-level calls already pass the exception object as the final varargs parameter (Log4j2/SLF4J convention), which correctly attaches a stack trace.** This is a good baseline. A minority of sites log only `e.getMessage()` with no exception object attached, which is the pattern to fix:

**Bad (message-only, no stack trace attached) — found at:**
- `PdfProtectionController.java:56,62,68` (three of its four catch blocks — the fourth, line 74, correctly attaches `e`)
- `FileTypeDetectionService.java:105` (attaches `e` at line 113 in the sibling catch block, but not at 105)
- `PdfService.java:107` (`logger.error(msg)` — no exception object at all, since this is a manually-constructed `RuntimeException` about to be thrown, not yet caught)
- `PdfService.java:223` (`Validation error` branch — message only)

**Good (exception object attached, matches the task's own example) — the dominant pattern, e.g.:**
```java
logger.error("Error protecting PDF: {}", e.getMessage(), e);          // PdfProtectionController.java:74
logger.error("Error saving decoded file: {}", e.getMessage(), e);     // DecodedFileController.java:65
logger.error("Error converting file from {}: {}", req.getUrl(), e.getMessage(), e);  // PdfService.java:228
```

**Recommended target pattern** (per the task's own example, and enabled cleanly by the correlation-ID idea in Phase 6 item 12):
```java
logger.error("PDF protection failed. RequestId={}", requestId, ex);
```
No `requestId`/correlation-ID field exists anywhere in this codebase today — introducing one is an OPTIONAL improvement (Phase 6 item 12), not a prerequisite for fixing the message-only sites above, which can be brought to the "Good" pattern immediately by simply attaching the already-available exception object.

---

## Summary for approval

- **2 CRITICAL findings**, both centered on one root cause: the shared `RestTemplate`'s `LoggingInterceptor` and its sibling error handler log full request/response bodies with no content-awareness. Fixing these two sites closes the majority of real exposure risk in this codebase.
- **2 HIGH findings**: an unguarded raw-body `println` in a test endpoint, and an open-ended caller-supplied map being logged verbatim.
- **3 MEDIUM findings**: unsanitized URLs (query-string tokens), one inconsistent header-masking gap, and decoder-exception-message content.
- **Strong positive baseline**: zero `printStackTrace()`, no password/DOB/derived-password logging anywhere (verified by direct grep against every sensitive getter), consistent Authorization/Cookie header masking in the two places that do it, and a pre-existing "log presence not content" pattern (`PdfService.java:166`) that the fix plan generalizes rather than invents from scratch.
- No new logging framework is needed. No `logging.level.*` configuration exists today — adding it is itself part of the fix, not a pre-existing control that merely needs tuning.

**Awaiting approval before any implementation.**
