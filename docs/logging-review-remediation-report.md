# Logging Review — Remediation Report

Implements the two approved HIGH findings and the one additional review item from `docs/Logging_Remediation_Independent_Review.md`. **Narrowly scoped, no redesign** — no endpoint, request/response payload, or business logic was touched.

---

## Finding 1 — Embedded URL Credentials Not Sanitized

**Root Cause:** `LogSanitizer.sanitizeUrl()` reconstructed the sanitized URL using `uri.getAuthority()`, which includes any `user:password@` user-info prefix verbatim — only `query` and `fragment` were explicitly stripped.

**Fix Applied:** Rebuild the authority from `uri.getHost()` + (if present) `uri.getPort()` only, never from the raw authority string — this structurally excludes user-info regardless of its content. The malformed-URL fallback path was also hardened to strip both `?query` and `#fragment` (it previously only stripped the query string).

**Files Changed:**
- `common/util/LogSanitizer.java` — `sanitizeUrl()` rewritten; new private `stripQueryAndFragment()` helper.
- `src/test/java/com/twixor/base64convertor/common/util/LogSanitizerTest.java` — **new**, 11 tests.
- `pom.xml` — added `org.junit.jupiter:junit-jupiter` (test scope only; does not affect the production/runtime classpath or the shipped jar).

**Before / After:**
```java
// Before
URI stripped = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
// https://user:password@host/file.pdf -> https://user:password@host/file.pdf  (credentials leak)

// After
String host = uri.getHost();
String authority = (uri.getPort() != -1) ? host + ":" + uri.getPort() : host;
URI stripped = new URI(uri.getScheme(), authority, uri.getPath(), null, null);
// https://user:password@host/file.pdf -> https://host/file.pdf
```

**Risk:** Low. Pure string-transformation change confined to one method; the method's callers (15 log call sites across the codebase) are unaffected in signature or calling convention — only the returned string's content changes (now safer). `URI.getHost()`/`getPort()` are well-defined, standard JDK behavior.

**Verification:**
- **Unit tests** (`LogSanitizerTest`, 11 cases, all passing) cover exactly the 6 cases from the finding plus 5 defensive cases (plain URL unchanged, null input, blank input, combined credentials+query+fragment, malformed input):

  | Input | Output | Pass? |
  |---|---|---|
  | `https://user:password@host/file.pdf` | `https://host/file.pdf` | ✅ |
  | `https://user@host/file.pdf` | `https://host/file.pdf` | ✅ |
  | `https://user:password@host:8443/file.pdf` | `https://host:8443/file.pdf` | ✅ |
  | `https://host/file.pdf?token=abc` | `https://host/file.pdf` | ✅ |
  | `https://host/file.pdf#fragment` | `https://host/file.pdf` | ✅ |
  | `https://host/file.pdf?token=abc#fragment` | `https://host/file.pdf` | ✅ |

  ```
  mvn test  (temporarily run with skipTests overridden to false, then reverted — see Verification Notes)
  Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
  ```
- **Live verification:** a real request with `url":"https://user:mysecretpass@localhost:8199/sample.pdf"` was sent to `/api/files/pdf/convert/base64`. Confirmed the direct `LogSanitizer.sanitizeUrl(url)` output embedded in the ERROR log line was clean (`"Error fetching file from URL: https://localhost:8199/sample.pdf"` — no credentials). **A separate, previously-unknown leak channel was discovered during this same test — see "New Finding Discovered During Verification" below.**

**Residual (documented, not fixed — out of this task's scope):** if a URL's host cannot be parsed by `java.net.URI` (an "opaque"/malformed URI with no resolvable host), the fallback strips query/fragment only and cannot structurally guarantee user-info removal in that narrow case. None of the six required test cases hit this path; flagged for awareness only.

---

## Finding 2 — Production Logging Policy Not Enforced for the `pdf` Package

**Root Cause:** `log4j2-spring.xml` (pre-existing, not modified) defines `<Logger name="com.twixor.base64convertor.pdf" level="INFO" additivity="false">`. Log4j2 resolves a class's effective level via the *most specific* matching named `LoggerConfig` — since this is more specific than the bare `com.twixor.base64convertor` that the Spring Boot property targets, every class under the `pdf` package (`PdfService`, `PdfProtectionService`, all `Pdf*Controller`s, both `Pdf*Facade`s, `TargetApiRequestMapper`, `DynamicHttpService`) resolved against the XML's hardcoded `INFO`, regardless of what `logging.level.com.twixor.base64convertor` was set to.

**Options considered:**
- **Option A (chosen):** Add an explicit `logging.level.com.twixor.base64convertor.pdf=<level>` property to each profile, exactly matching the pattern already proven for the `com.twixor.base64convertor.http` logger in the prior remediation.
- **Option B (rejected):** Modify `log4j2-spring.xml` to remove the hardcoded level or make it externally parameterized (e.g. via a system property placeholder).

**Reasoning for choosing Option A:** Option A is strictly additive — three new property lines, zero risk to the existing `PdfFile`/`HttpFile`/`ErrorFile` appenders, rotation policies, or file routing, all of which remain byte-for-byte unchanged. Option B requires editing the XML structure itself (introducing a Log4j2 property/lookup mechanism, e.g. `${sys:pdfLogLevel}`), which is a strictly larger change surface for the same outcome, touches a file this task's constraints did not name for modification, and reintroduces exactly the same "explicit-name-must-match" fragility this fix is meant to close — just relocated into XML instead of properties. Option A is also consistent with (and completes) the precedent already set for `.http` in the prior remediation, keeping the fix pattern uniform and easy to audit.

**Files Changed:**
- `src/main/resources/application-dev.properties` — added `logging.level.com.twixor.base64convertor.pdf=DEBUG`
- `src/main/resources/application-uat.properties` — added `logging.level.com.twixor.base64convertor.pdf=INFO`
- `src/main/resources/application-prod.properties` — added `logging.level.com.twixor.base64convertor.pdf=ERROR`
- `log4j2-spring.xml` — **not modified**

**Before / After (prod profile, live test):**
```
# Before this fix — pdf.log under spring.profiles.active=prod:
2026-07-02 14:09:33.691 [main] INFO  com.twixor.base64convertor.pdf.controller.PdfDeliveryController - PdfDeliveryController initialized.
2026-07-02 14:09:43.960 [http-nio-8099-exec-2] INFO  com.twixor.base64convertor.pdf.service.PdfProtectionService - PDF password protection applied (2356 bytes -> 2777 bytes)
2026-07-02 14:09:44.157 [http-nio-8099-exec-4] INFO  com.twixor.base64convertor.pdf.service.PdfService - File [sample.pdf] [2356 bytes, ...] converted. ...
(6 INFO lines total from a 2-request test)

# After this fix — identical test, same profile:
(0 INFO lines — pdf.log contains ERROR-level entries only, when present)
```

**Risk:** None. Purely additive property lines; no XML/appender/rotation changes; no code changes.

**Verification (live, all three profiles, this session):**

| Profile | Expected | Observed | Pass? |
|---|---|---|---|
| `prod` | `pdf.log`: 0 INFO lines | `grep -c " INFO " logs/pdf.log` → **0** (after 2 successful requests that previously produced 6 INFO lines) | ✅ |
| `uat` | `pdf.log`: INFO lines present, no DEBUG | 2 INFO lines observed (`"Dynamic Base64 conversion requested..."`, `"File [...] converted..."`); `grep -c " DEBUG "` → **0** | ✅ |
| `dev` | `http.log`: DEBUG lines present | 9 DEBUG lines observed (request/response metadata blocks); confirmed still metadata-only (`PayloadPresent`/`PayloadSize`, no body content) | ✅ |

---

## Additional Review Item — `LoggingInterceptor` Response Body Buffering

**Analysis:**
- `logResponse()` called `StreamUtils.copyToByteArray(response.getBody())` **unconditionally**, on every single outbound call through the shared `RestTemplate`, regardless of whether `httpLogger` was at DEBUG or not — purely to compute `.length` for a debug-only log line.
- **Is buffering itself still required?** Yes, but not by this method. `BufferingClientHttpRequestFactory` (configured in `UnsafeRestTemplate`) already buffers every response so it can be read more than once — this exists to serve the *actual business logic* (e.g. `PdfService.fetchAndConvertToBase64`'s `ResponseEntity<byte[]>` conversion, which necessarily reads the full body into memory as part of its normal, required operation). That buffering is unrelated to logging and out of scope here.
- **Is this specific read/copy still required?** No — it was a *second*, redundant full-body copy, made solely to log a byte count, on top of the buffering that already happens for business reasons.
- **Can payload size be calculated without reading the entire content?** Not reliably in all cases (the `Content-Length` header is absent for chunked-encoded responses), but this doesn't matter once the read is correctly gated — see recommendation.
- **Memory pressure risk:** Real and measurable for large files. For a response near the app's own `app.pdf.stream-threshold-bytes` (default 5,000,000 bytes), this was an additional ~5MB short-lived allocation+copy on every outbound fetch, **in every environment**, even production, even though the resulting `httpLogger.debug(...)` call would never fire outside of DEBUG-enabled environments.

**Risk (proven, not assumed):** Confirmed by code inspection that the read executed unconditionally before any level check — this is not a hypothetical; it is the literal control flow that existed prior to this fix.

**Recommendation implemented:** Guard the entire body-read-and-log block behind `httpLogger.isDebugEnabled()`. This is the minimal, narrowly-scoped fix that eliminates the proven redundant allocation in every environment where DEBUG is not active (UAT/staging/production — i.e. essentially all real traffic), while leaving DEV's behavior (and the resulting log content) completely unchanged.

**Files Changed:** `common/util/LoggingInterceptor.java` — `logResponse()` wrapped in `if (!httpLogger.isDebugEnabled()) { return; }`. `logRequest()` was deliberately **not** changed — it does not perform any extra buffering (the `body` byte array is already supplied as a method parameter by the calling infrastructure regardless of this interceptor's existence), so there was no proven memory-pressure risk to justify touching it, consistent with "do not change implementation unless risk is proven."

**Risk of this change:** Low. `logResponse`'s return value is `void`; skipping its body has no effect on the `ClientHttpResponse` object returned to the caller, which downstream business logic continues to read exactly as before (via the pre-existing buffering factory, unaffected by this change).

**Verification (live):** Confirmed via the `dev`-profile test above — with DEBUG active, `http.log` still shows all 9 expected DEBUG lines including accurate `PayloadPresent`/`PayloadSize` values (e.g. `PayloadSize: 2356 bytes` matching the actual fetched file size) — proving the guarded code path still executes correctly when needed, and (by the `uat`/`prod` tests showing 0 DEBUG lines with successful requests) is skipped when not.

---

## New Finding Discovered During Verification (documented, not fixed — outside this task's approved scope)

While live-testing Finding 1 with a credentialed URL (`https://user:mysecretpass@localhost:8199/sample.pdf`), the request failed at the network layer — modern Java (`java.net.http` / Apache HttpClient 5, used underneath `RestTemplate` here) **rejects URLs with a user-info component outright** (`"Request URI authority contains deprecated userinfo component"`), which is itself a positive, independent defense-in-depth control not part of this application's own code.

However, the resulting `ResourceAccessException`'s own `.getMessage()` — constructed internally by Spring's `RestTemplate`, not by this application — **embeds the raw, unsanitized URL including the credential**, and this application's existing error-logging call:
```java
logger.error("Error fetching file from URL: {} - {}", LogSanitizer.sanitizeUrl(url), e.getMessage(), e);
```
passes `e.getMessage()` (leaking the raw URL) and `e` itself (whose stack-trace dump repeats the same message) straight through, even though the **first** argument (`LogSanitizer.sanitizeUrl(url)`, the one this task's Finding 1 was scoped to) is correctly sanitized. Confirmed live: the credential `mysecretpass` appeared 3 times in `pdf.log` (once per retry attempt) via this channel, entirely independent of `LogSanitizer`.

This is a **different** leak path than Finding 1 described (Finding 1 was about `sanitizeUrl()`'s own string-transformation logic, which is now correct and verified) — it is about a third-party exception's message text being logged verbatim. Fixing it (e.g. sanitizing `e.getMessage()` itself, or catching this specific exception type to rebuild a safe message) was **not** part of the two approved findings for this task and was not implemented, per the "narrowly scoped, only address approved findings" instruction. **Recommend opening this as a new, separate finding for explicit approval before remediation**, since a fix here would need care to avoid discarding genuinely useful diagnostic detail from other exception types that don't have this problem.

---

## Verification Summary

| Check | Result |
|---|---|
| ✓ Embedded credentials removed | **PASS** — for all `LogSanitizer.sanitizeUrl()` call sites; residual risk via third-party exception messages documented separately above (new finding, not in scope) |
| ✓ Query strings removed | **PASS** — unit-tested and live-verified |
| ✓ Fragments removed | **PASS** — unit-tested and live-verified |
| ✓ Production profile truly suppresses PDF INFO logs | **PASS** — live-verified, 0 INFO lines in `pdf.log` under `prod` after successful requests that previously produced 6 |
| ✓ UAT still logs INFO | **PASS** — live-verified, 2 INFO lines, 0 DEBUG lines |
| ✓ DEV still logs DEBUG | **PASS** — live-verified, 9 DEBUG lines, still metadata-only (no body content) |
| ✓ Build successful | **PASS** — `mvn clean package` → BUILD SUCCESS (50 main source files); `mvn test` (temporarily unskipped) → 11/11 tests pass |

### Verification Notes on `skipTests`
`pom.xml` has a pre-existing, hardcoded `<skipTests>true</skipTests>` (not property-templated, so it cannot be overridden via `-DskipTests=false` on the command line). To actually execute the new `LogSanitizerTest` and prove it passes, this value was **temporarily** flipped to `false`, `mvn test` was run (11/11 passing), and the value was then **reverted to exactly its original `true`**, restoring the project's pre-existing default build behavior unchanged. The final `mvn clean package` run in this report reflects that restored, original default state (tests compiled, then skipped, same as before this task).
