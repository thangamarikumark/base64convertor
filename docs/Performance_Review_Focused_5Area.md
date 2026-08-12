# Focused Performance Review — Base64 / Tika / RestTemplate / Logging / Batch Processing

Deep-dive follow-up to `docs/Performance_Review_Quick_Wins.md`, scoped to the 5 areas requested. Every Base64/Tika/RestTemplate call site in the codebase was enumerated via grep and each one read in full — the tables below are exhaustive, not sampled.

---

## Area 1 — Base64 Processing Analysis

**All `Base64.getEncoder()`/`Base64.getDecoder()` call sites in the codebase (9 total, no `getMimeEncoder`/`getMimeDecoder` usage anywhere):**

| Class | Method | Current Flow | Optimization Opportunity | Expected Gain | Risk |
|---|---|---|---|---|---|
| `Base64DecodingService` | `decodeAndSaveFile` | L68 decodes `request.getBase64Content()` to `decodedBytes`; L71 calls `fileTypeDetectionService.detectFileType(request.getBase64Content())`, which **decodes the same string a second time internally** (`FileTypeDetectionService.java:73`) just to hand Tika a `byte[]`. | Add a `detectFileType(byte[])` overload; pass the `decodedBytes` already computed at L68. | Removes 1 of 2 decodes on this path — ~50% CPU/memory reduction for this operation | Very Low |
| `FileTypeDetectionService` | `detectFileType` | Decodes `base64Content` independently (see above) — this is the *second* decode of the same content when called from `Base64DecodingService`. | Same fix as above (add byte[]-accepting overload). | Same finding as above, other side of the pair | Very Low |
| `Base64FileValidator` | `isValidBase64ForMime` | Pads and decodes its own `base64` argument via `Base64.getDecoder().decode(padded)` purely to read the first 4 magic bytes. Called from `PdfProtectionServiceImpl.decodeAndValidate` **after** that method already decoded the identical content at L103. | Expose the existing private `verifyFileSignature(byte[], String)` method publicly; call sites that already hold decoded bytes pass them directly instead of the base64 string. | Removes 1 of 2 decodes on `/pdf/protect` — for the max-permitted 20MB payload (`pdf.protection.max-file-size-mb`), this is a full second 20MB decode avoided per request | Very Low |
| `PdfProtectionServiceImpl` | `decodeAndValidate` | L103 decodes `base64DocContent` to `pdfBytes`; L114 re-decodes the same string via `Base64FileValidator.isValidBase64ForMime(base64DocContent, ...)` (see above — this is the *first* half of that same pair). | Same fix as above. | Same finding | Very Low |
| `FileConversionService` | `handleFileProcessing` | Encodes `fileBytes` (already read from disk once via `Files.readAllBytes`) exactly once. No duplication. | None needed. | — | — |
| `PdfService` | `fetchAndConvertToBase64` | Encodes the HTTP response body exactly once (`Base64.getEncoder().encodeToString(fileBytes)`). No duplication. | None needed. | — | — |
| `PdfService` | `fetchAndConvertToBase64Dynamic` | Encodes with `.withoutPadding()` then immediately runs `.replaceAll("\\r|\\n", "")` over the full output string — `java.util.Base64.Encoder` (unlike the legacy `sun.misc.BASE64Encoder`) never emits `\r`/`\n`, so this is a full-string regex scan (with a hidden `Pattern.compile` per call) that always matches nothing. | Delete the `.replaceAll(...)` call — output is provably unchanged. | Removes an O(n) regex scan + implicit pattern compile on every dynamic-fetch response, proportional to Base64 output size | Very Low |
| `PdfProtectionController` | `protectPdf` | Encodes the final `pdfBytes`/protected result exactly once to build the response `data.base64ProtectedPdf`. No duplication. | None needed. | — | — |

**Explicit verification requested by the task:** `byte[] decodedBytes = Base64.getDecoder().decode(base64Content);`-style calls **are** executed twice within the same request flow in exactly two places — `Base64DecodingService`+`FileTypeDetectionService` (the `/save-decoded` flow) and `PdfProtectionServiceImpl`+`Base64FileValidator` (the `/pdf/protect` flow). No other endpoint exhibits this pattern; the remaining 5 call sites each decode/encode exactly once per request.

**Multiple in-memory Base64 copies / large byte[] allocations:** for the two duplicate-decode cases above, each request momentarily holds two independent `byte[]` copies of the same content (e.g. two ~20MB arrays for a max-size PDF protect request) until the first goes out of scope — this is the direct memory cost of the duplicate decode, not a separate issue.

---

## Area 2 — Apache Tika Detection Review

**Only one Tika call site exists in the entire codebase**, confirmed by grep across all of `src/main/java`:

```
FileTypeDetectionService.java:17:  private static final Tika tika = new Tika();
FileTypeDetectionService.java:92:  String mimeType = tika.detect(decodedBytes);
```

- **`Tika` instantiation:** already a single `private static final Tika tika` field, shared across all requests — correct (Tika instances are documented as thread-safe and non-trivial to construct, so a static singleton is the right pattern; this was verified, not assumed).
- **`detectFileType(...)` callers, codebase-wide:** exactly **one** call site — `Base64DecodingService.decodeAndSaveFile` (`/api/files/save-decoded`). `PdfService`, `PdfProtectionServiceImpl`, and the file-retrieval controllers do **not** call Tika at all; they either trust the caller-declared MIME type, use `URLConnection.guessContentTypeFromName/Stream` (`PdfService.detectMimeType`, a separate, non-Tika mechanism), or check magic bytes directly (`Base64FileValidator`).
- **No detection-inside-a-loop pattern found** — there is no per-item Tika call inside `/api/files/convert`'s batch loop or anywhere else; Tika is only reachable via the single `/save-decoded` endpoint, which processes one file per request.
- **No "detection after type already known" pattern found** — `/save-decoded`'s `Base64SaveRequest` DTO has no caller-supplied MIME/type field to make detection redundant; Tika detection is the *only* source of `detectedMimeType` on this path, so the call is necessary, not wasteful, on its own. The only true inefficiency is the duplicate *decode* feeding into it (Area 1 finding), not a duplicate *detection* call.

| | Count |
|---|---|
| Current Tika `detect()` calls per `/save-decoded` request | 1 |
| Required Tika `detect()` calls per `/save-decoded` request | 1 (irreducible — it's the only MIME source for this endpoint) |
| Current Base64 decodes feeding that single detection | 2 (see Area 1) |
| Required Base64 decodes feeding that single detection | 1 |

**Recommendation:** no change to Tika usage itself (already optimal — single instance, single call, no redundant detection). The only actionable item is the Area 1 fix (pass already-decoded bytes into `detectFileType`), which is a Base64 finding, not a Tika finding. MIME type cannot be "derived from response headers" here since `/save-decoded` is not an HTTP-fetch endpoint — the content arrives as a base64 request body with no headers to consult, and cannot be "reused from a previous operation" since this is the first and only detection in the request.

---

## Area 3 — RestTemplate Review

**All `RestTemplate` references (5 files):**

- `UnsafeRestTemplate.java` — the **bean definition**. `@Bean @Primary RestTemplate restTemplate()` — a genuine Spring-managed singleton, confirmed no `new RestTemplate()` exists anywhere else in the codebase (grep-verified).
- `PdfService.java`, `DynamicHttpService.java`, `PdfDeliveryFacade.java`, `LoggingInterceptor.java` — all **consume** the injected singleton bean via constructor injection; none construct their own instance.

**Bad pattern search (`new RestTemplate()` inside a method): none found.** Every consumer receives the bean through Spring DI.

**Connection management, read from `UnsafeRestTemplate.createRequestFactory()`:**

| Setting | Value | Source |
|---|---|---|
| Connection pool | `PoolingHttpClientConnectionManagerBuilder` — genuinely pooled, not a new connection per call | `UnsafeRestTemplate.java:61-67` / `:83-86` |
| Max total connections | 200 | `app.http.max-connections` |
| Max connections per route | 20 | `app.http.max-connections-per-route` |
| Connect timeout | 30s | `app.http.connect-timeout-seconds` |
| Response/read timeout | 60s | `app.http.response-timeout-seconds` |
| Idle connection eviction | every 2 minutes (`evictIdleConnections(TimeValue.ofMinutes(2))`) | hardcoded in `createRequestFactory` |
| Expired connection eviction | `evictExpiredConnections()` enabled | hardcoded in `createRequestFactory` |
| Retry interaction | `PdfService.executeWithRetry` and `FileConversionService.downloadWithRetry` each implement their **own** manual retry loop (`Thread.sleep` + exponential backoff from `app.retry.*`) *around* calls through this same pooled `RestTemplate`/`URLConnection` — retries reuse the pool correctly, they don't bypass it. | `PdfService.java:239-264`, `FileConversionService.java:202-235` |

**Assessment: this is already a textbook-correct `PoolingHttpClientConnectionManager` setup.** No `new RestTemplate()` anti-pattern, real connection pooling (not `SimpleClientHttpRequestFactory`'s default one-socket-per-request), configurable timeouts, and idle-eviction hygiene. **No RestTemplate-level recommendation is warranted** — the only related finding is the Area 1 no-op `.replaceAll` that happens to sit in the same method (`fetchAndConvertToBase64Dynamic`) as one of the RestTemplate calls, but it's a Base64 issue, not an HTTP-client issue.

One **observation, not a defect:** `PdfService.executeWithRetry`'s manual loop duplicates `FileConversionService.downloadWithRetry`'s manual loop almost line-for-line (same backoff formula, same `Thread.sleep` pattern) — both independently reimplement what `PdfService.fetchAndConvertToBase64` gets "for free" via Spring Retry's `@Retryable` annotation just a few lines above in the same class. Not flagged as P1/P2 (no performance cost, purely a maintainability duplication), but worth noting since Spring Retry is already a dependency and already proven to work in this exact class.

---

## Area 4 — Logging Performance Review

**Every `log.*`/`logger.*` call site referencing base64/payload/content/body-shaped variables was enumerated (80+ call sites across all controllers/services) and individually inspected.**

**Critical-issue search result: none found.** Specifically:
- No call site logs a `base64Data`/`base64ProtectedPdf`/`attachmentData`/decoded-bytes variable directly — every logging call touching these operations logs `.length` (a count) or the filename, never the content itself. Examples confirmed by direct read: `FileTypeDetectionService.java:93` (`"Detected MIME type: {} for {} bytes"` — count, not content), `Base64DecodingService.java:105` (`"Saved decoded file: {} (size: {} bytes)"`), `PdfProtectionServiceImpl.java:141` (`"PDF protected ({} bytes -> {} bytes)"`).
- No call site does `objectMapper.writeValueAsString(request)`/`(response)` for logging purposes — the only `objectMapper.writeValueAsString` calls in the codebase are in `PdfDeliveryFacade` (building the actual outbound HTTP request body — functional, not logging) and `Base64OutputWriter`/`Base64DecodingService` (writing `.meta.json` sidecar files to disk — also functional, not logging).
- `UnsafeRestTemplate.configureErrorHandler`'s `handleError` reads the full error response body (`new String(response.getBody().readAllBytes())`) but only ever logs the **status code** at ERROR level; a **truncated-to-200-chars** preview is logged at DEBUG only (`LogSanitizer.truncate(body, 200)`), which is a no-op in any environment where DEBUG isn't enabled (production/staging per `logging.level.*` conventions elsewhere in this codebase).
- `LoggingInterceptor.logResponse` already gates its own body-size-measuring read behind `httpLogger.isDebugEnabled()`, so in non-DEBUG environments it performs zero extra I/O — this was a finding from a prior remediation pass in this codebase (`docs/logging-review-remediation-report.md`, referenced in the code comment) and is confirmed still in place, not re-flagged as new.

**Conclusion: this area was already fully remediated in a prior session** (visible from the `LogSanitizer`/masking/truncation infrastructure and the code comments citing "logging remediation, CRITICAL #1/#2" throughout `PdfService`, `UnsafeRestTemplate`, and `LoggingInterceptor`). This review independently re-verified every call site rather than trusting those comments, and found no regression and no new instance of the length-only/hash/metadata-logging pattern being violated anywhere.

**No P1/P2/P3 finding in this area** — recommend no change; flagging this explicitly so a future review doesn't re-spend effort re-checking a closed item.

---

## Area 5 — Batch Processing Review

**`POST /api/files/convert`** (`FileConvertController.convertFiles`, `FileConversionService.java`):

- **Current flow:** `requests.stream().map(fileConversionService::processFile).collect(Collectors.toList())` — the Java Stream API here provides no parallelism (`.stream()`, not `.parallelStream()`); each `processFile` call is fully synchronous and blocking (URL download via `downloadWithRetry`, then encode, then write). Batch size is capped at `app.convert.max-batch-size=50`.
- **Existing concurrency capability, already built and working elsewhere in this class:** `processFileAsync` (annotated `@Async("fileExecutor")`) already exists and is exercised by a *different* code path (`FileConvertController.getStatus`/an async submission flow implied by `getAsyncResult`), running on the `fileExecutor` thread pool (`async.file-executor.core-pool-size=4`, `max-pool-size=8`, `queue-capacity=50`, configured in `AsyncConfig`/`application.properties`). The batch `/convert` endpoint simply doesn't route through it.
- **Independence of batch items:** each `FileConvertRequest` in the batch list carries its own `url`/`mimeType`/`type` and produces an independent `FileConvertResponse` — there is no shared mutable state or ordering dependency between items in `handleFileProcessing` (each writes to its own uniquely-named temp file via `UUID.randomUUID()`), so parallel execution is **safe from a data-race perspective**.

| | Value |
|---|---|
| Current throughput (worst case) | Sum of all N item latencies, fully serial — e.g. 50 items × 2s average download = ~100s wall-clock for one batch request |
| Potential throughput (bounded by existing `fileExecutor` pool) | ~N / min(N, 8) × per-item latency — e.g. same 50-item batch at 8-way concurrency ≈ ~13s wall-clock (≈7-8x improvement), bounded by `async.file-executor.max-pool-size=8` |
| Concurrency risks | (1) The `fileExecutor` pool is shared with the existing async single-file endpoint — a large batch request would temporarily starve concurrent async single-file requests hitting the same pool; (2) partial-failure semantics change: today, a `.map()` over a synchronous stream naturally preserves item order and one `FileConvertResponse` per input even on individual failures (each `processFile` internally catches its own exceptions and returns an `errorResponse`, never throwing) — a `CompletableFuture.allOf(...)`-based parallel version must preserve this same per-item error-isolation guarantee (it can, but requires deliberate care, not a mechanical stream-to-async swap); (3) `Thread.sleep`-based retry backoff inside each item (`downloadWithRetry`) would now block a pool thread instead of a request thread — still fine since the pool is bounded, but worth noting the retry backoff no longer "costs" a servlet container thread, it costs a `fileExecutor` thread instead. |
| Recommended approach | Dispatch each batch item through the existing `fileExecutor` (`CompletableFuture.supplyAsync(() -> fileConversionService.processFile(item), fileExecutor)` for each item, then `CompletableFuture.allOf(...).join()` and collect in original order) — reuses infrastructure that already exists and is already proven in this codebase, no new thread pool, no new dependency. |

**Are download operations independent?** Yes — confirmed no shared state between `handleFileProcessing` invocations (each downloads to its own `UUID`-named temp file, writes its own `.b64` output, appends its own audit-log line via a shared but append-safe `Files.write(..., APPEND)` call).

**Is the thread pool currently underutilized?** Yes, specifically *for this endpoint* — `fileExecutor` (capacity 8) is fully idle during every synchronous `/convert` batch call today, since that endpoint never routes through `@Async`.

---

## Memory Analysis

| Source | Allocation Pattern | Current Footprint (worst case, ~20MB file — the largest size this codebase permits via `pdf.protection.max-file-size-mb`) | Potential Reduction |
|---|---|---|---|
| `Base64DecodingService`/`FileTypeDetectionService` duplicate decode | Two independent `byte[]` decodes of the same content | ~2× decoded-size in concurrent heap allocation (≈2×15MB for a 20MB base64 string's ~15MB decoded form) momentarily live | ~50% (one decode instead of two) |
| `PdfProtectionServiceImpl`/`Base64FileValidator` duplicate decode | Two independent `byte[]` decodes of the same content | Same pattern, ~2×15MB momentarily live per `/pdf/protect` call | ~50% |
| `FileStorageFacade.readDecodedFile` → `DecodedFileController.downloadDecodedFile` | `Files.readAllBytes(filePath)` loads the entire file into a `byte[]` before `ResponseEntity.ok().body(...)` | Full file size in heap per concurrent download (up to whatever was saved — bounded by upstream validation, e.g. 20MB for protected PDFs) | Converting to a streamed `Resource` response reduces this to near-constant (buffer-sized) memory regardless of file size — the single largest potential memory-footprint reduction identified in this review |
| `PdfProtectionServiceImpl.protect` | `ByteArrayOutputStream` buffers the entire PDFBox-encrypted output before returning `byte[]` | One buffer, sized to the (comparable) output PDF — this is PDFBox's own API contract (`document.save(OutputStream)`), not something this codebase can avoid without a PDFBox-level streaming API that doesn't exist for encryption | None available without a library-level change (out of scope — "no framework replacement") |
| `FileConversionService.handleFileProcessing` | `Files.readAllBytes(tempFile)` after already streaming the download to disk via `Files.copy` | One `byte[]` copy for encoding — this one is necessary (can't `Base64`-stream-encode without a different encoder API) but is not *duplicated*, so it's already minimal for this design | None without changing the encoding approach (out of scope) |

**Overall:** the two duplicate-decode findings (Area 1) are the only genuinely *avoidable* double-allocation in the codebase; the streamed-download finding is the largest single win but was already identified and scoped in the prior general review (`docs/Performance_Review_Quick_Wins.md`, finding #5) — repeated here because it's directly relevant to "large payload" memory analysis in this focused pass.

## CPU Impact Analysis

- **Duplicate Base64 decode (2 sites):** decode is a linear-time, cache-friendly operation but still O(n) work performed twice instead of once — for a 20MB payload this roughly doubles the CPU-cycles spent in `Base64.getDecoder().decode(...)` per affected request. Under concurrent load (multiple simultaneous large `/pdf/protect` or `/save-decoded` calls), this is direct, avoidable CPU contention.
- **`.replaceAll("\\r|\\n", "")` no-op regex:** a full-string scan plus a one-time hidden `Pattern.compile(...)` per call — for Base64 output sized proportionally to the fetched file, this is a second O(n) pass over data that provably never matches, purely wasted CPU.
- **Uncompiled-per-call `Pattern.compile` in `PdfService.detectFileName`** (carried over from the general review, restated here since it's in the same class/request path as the Area-1 RestTemplate-adjacent finding): regex *compilation* (not matching) is the expensive part; recompiling on every request with a `Content-Disposition` header is avoidable CPU work with a one-line static-field fix.
- **Batch `/convert` (Area 5):** today's sequential batch processing isn't a CPU problem — it's mostly I/O-wait (network download latency), which is exactly why moving it onto the existing thread pool improves *wall-clock latency* dramatically without meaningfully increasing CPU usage (the same total download work happens, just concurrently instead of serially).

## Estimated Latency Improvements

| Fix | Latency impact |
|---|---|
| Remove duplicate decode (`/save-decoded`, `/pdf/protect`) | Modest per-request latency improvement (single-digit percent to low-double-digit percent depending on payload size) — the win is primarily CPU/memory pressure under concurrency, not single-request wall-clock time, since decode itself is fast relative to I/O in these flows |
| Remove no-op `.replaceAll` | Small, consistent per-request latency improvement proportional to Base64 output size on the dynamic-fetch path |
| Stream large-file downloads instead of `readAllBytes` | Reduces time-to-first-byte for large downloads (streaming starts sending before the full file is buffered) — a genuine latency win for large files, in addition to the memory win |
| Async batch `/convert` | By far the largest latency win available in this codebase — a 50-item batch bound by network latency could improve wall-clock time by roughly the pool concurrency factor (up to ~8x with the existing `fileExecutor` sizing), as shown in the Area 5 table |

---

## Final Output Table

| Priority | Class | Method | Finding | Recommendation | Estimated Gain | Risk |
|---|---|---|---|---|---|---|
| P1 | `Base64DecodingService` | `decodeAndSaveFile` | Base64 content decoded twice (own decode + `FileTypeDetectionService`'s internal decode) | Add `detectFileType(byte[])` overload; pass already-decoded bytes | ~50% CPU/memory reduction for this operation; modest latency improvement | Very Low |
| P1 | `PdfProtectionServiceImpl` | `decodeAndValidate` | Base64 content decoded twice (own decode + `Base64FileValidator`'s internal decode) | Expose `verifyFileSignature(byte[], String)`; pass already-decoded bytes | ~50% CPU/memory reduction for up-to-20MB payloads; modest latency improvement | Very Low |
| P1 | `PdfService` | `fetchAndConvertToBase64Dynamic` | No-op `.replaceAll("\\r|\\n", "")` scans full Base64 output; `Base64.Encoder` never emits those characters | Delete the call | Removes O(n) regex scan + hidden compile per request | Very Low |
| P1 | `PdfService` | `detectFileName` | `Pattern.compile(...)` recompiled on every call | Hoist to `private static final Pattern` | Avoids repeated regex compilation on every fetch response with a `Content-Disposition` header | Very Low |
| P2 | `FileStorageFacade` / `DecodedFileController` | `readDecodedFile` / `downloadDecodedFile` | `Files.readAllBytes` fully buffers large files (up to 20MB) before serving | Switch to streamed `ResponseEntity<Resource>` | Largest memory-footprint reduction identified; improves time-to-first-byte on large downloads | Low (needs a download smoke-test for `Content-Length` behavior) |
| P2 | `FileConvertController` / `FileConversionService` | `convertFiles` | Batch `/convert` processes up to 50 items fully sequentially despite an existing, working `fileExecutor` async path in the same class | Dispatch each batch item via `CompletableFuture.supplyAsync(..., fileExecutor)` + `allOf(...)`, preserving per-item error isolation and original ordering | Up to ~8x wall-clock latency improvement for large/slow batches, bounded by existing pool size | Medium — changes concurrent-execution and partial-failure semantics; needs explicit review of pool-sharing with the existing async single-file endpoint before merging |
| P3 | `PdfService` / `FileConversionService` | `executeWithRetry` / `downloadWithRetry` | Two near-identical hand-rolled retry loops, duplicating what `@Retryable` (already used elsewhere in `PdfService`) provides | Consider replacing both with `@Retryable`, consistent with `fetchAndConvertToBase64`'s existing pattern in the same class | Maintainability only — no measurable performance change | Low, but out of this review's "quick win" scope — a behavior-preserving refactor, not urgent |

**Confirmatory findings (no action needed, listed to close out the review's specific check-list items):**
- Tika: single static instance, single call site, no redundant detection — already optimal.
- RestTemplate: singleton bean, real connection pooling (200 total / 20 per-route), configured timeouts, idle eviction — already optimal, no `new RestTemplate()` anywhere.
- Logging: zero instances of payload/Base64/content logging found across 80+ inspected call sites — already remediated in a prior session, independently re-verified here.

---

## Final Verdict

# IMPLEMENT SELECTIVELY

**Justification and ROI per recommendation:**

- **The four P1 findings (duplicate decodes ×2, no-op regex, uncompiled pattern): APPROVE FOR IMPLEMENTATION.** All are mechanical, behavior-preserving, single-file changes with existing test coverage (`PdfProtectionServiceImplTest`) to catch regressions. ROI is highest here relative to effort — near-zero implementation risk, measurable CPU/memory reduction on the two largest-payload endpoints in the system (`/pdf/protect`, `/save-decoded`).
- **Streamed downloads (P2): APPROVE FOR IMPLEMENTATION**, ROI is high (largest memory-footprint reduction found) but gate the merge on a manual download verification pass, since `Content-Length`/streaming header behavior needs a live check, not just a code read.
- **Async batch processing (P2): IMPLEMENT SELECTIVELY, not this sprint as a "quick win," but the single highest-ROI item in this entire report if scheduled deliberately.** The throughput gain (up to ~8x) is real and the underlying infrastructure (`fileExecutor`) already exists and is proven — but it changes concurrency and partial-failure behavior for a widely-used endpoint, which this review's own scope explicitly excludes from "same-sprint quick win." Recommend a dedicated, reviewed change (with its own test plan for partial-failure ordering and pool-contention with the existing async single-file path), not a bundled fix alongside the P1 items.
- **Retry-loop consolidation (P3): DEFER.** Real duplication, zero performance impact — track as tech debt, not a performance sprint item.
- **Tika / RestTemplate / Logging: NO ACTION.** All three areas were explicitly re-verified in this pass at the level of detail requested and found already correctly implemented — stated explicitly so future reviews don't re-spend effort here.
