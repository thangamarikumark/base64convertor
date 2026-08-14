# Base64 Convertor — Modular Architecture Refactoring Plan

**Scope:** Full package restructuring of `com.twixor.base64convertor` into the target modular structure, with a **hard constraint of zero behavioral change** — no endpoint URL, request payload, response payload, business rule, file storage location, or database change of any kind (note: this app has no database — confirmed in Phase 1).

**How to read this document:** It is a *plan*, not an executed change. Given the blast radius of a full package rename/reorganization across 32 files touching every controller, service, DTO, and config bean in the app, executing it should happen in the verified, incremental phases described in Phase 4/5 — not as one large uncontrolled edit. See "Next Steps" at the end.

---

# Phase 1: Architecture Assessment

## 1. Current Architecture Diagram

```
com.twixor.base64convertor
│
├── Base64convertorApplication.java        (main, @SpringBootApplication)
│
├── config/
│   ├── AppProperties.java                 (god-config: http, pdf(+protect), convert, url, retry, base64 output)
│   ├── AsyncConfig.java                   (@EnableAsync, @EnableRetry, fileExecutor bean)
│   └── FileCacheProperties.java           (file.cache.* — used only by FileConversionService)
│
├── controller/
│   ├── ChecksumController.java            (2 endpoints: checksum, checksumgenerator — has inline nested DTO)
│   ├── FileConvertController.java         (3 endpoints: convert, convert/single, status/{id})
│   ├── FileRetrievalController.java       (8 endpoints: save-decoded, download-decoded, metadata,
│   │                                        callback, list, download, content, delete — 5 DOING
│   │                                        DIFFERENT THINGS crammed into one class)
│   ├── PdfController.java                 (5 endpoints: send, convert/base64, convert/base64dynamic,
│   │                                        single, protect — mixes "fetch+forward", "fetch+return",
│   │                                        and "protect" concerns)
│   └── TestController.java                (2 endpoints: ping, echo)
│
├── service/
│   ├── Base64DecodingService.java         (decode + Tika-detect + save + metadata)
│   ├── DynamicHttpService.java            (DEAD CODE — no controller references it)
│   ├── FileConversionService.java         (fetch-by-URL + encode + retry + audit log +
│   │                                        4 UNRELATED scheduled cleanup jobs)
│   ├── FileTypeDetectionService.java      (Tika MIME/extension detection — generic, cross-module)
│   ├── PdfProtectionService.java          (password derivation + PDFBox protection)
│   └── PdfService.java                    (fetch-and-encode via RestTemplate, used by 3 different endpoints)
│
├── dto/                                    (flat — 11 DTOs, no module grouping)
│   ├── Base64SaveRequest / Base64SaveResponse
│   ├── DecodedFileSaveResponse
│   ├── FileConvertRequest / FileConvertResponse
│   ├── FileInfo
│   ├── PdfBase64Request / PdfBase64RequestDynamic
│   ├── PdfProtectRequest / PdfProtectResponse
│   ├── PdfRequest / PdfResponse
│   └── TargetApiRequest
│
└── util/
    ├── Base64FileValidator.java           (pure validator — magic-byte MIME check)
    ├── Base64OutputWriter.java            (cross-cutting: used by fileconversion, pdf, filestorage)
    ├── LoggingInterceptor.java            (RestTemplate interceptor)
    ├── UnsafeRestTemplate.java            (@Configuration — RestTemplate bean, belongs in config/)
    └── UrlAllowlistValidator.java         (pure validator — SSRF guard, used by 2 modules)
```

**Key structural facts confirmed by re-reading the current tree (Phase 1 verification):**
- No database, no JPA, no entities/repositories exist — all persistence is filesystem-based. The "Database unchanged" verification item in Phase 5 will be satisfied trivially (there is none to change).
- 32 Java source files total.
- `DynamicHttpService` has zero callers anywhere in the codebase — confirmed dead code.
- `FileRetrievalController` alone spans 5 of the 10 "functional areas" you listed (File Storage, Metadata, Download, Callback, and half of PDF-adjacent retrieval) — the single clearest SRP violation in the codebase.

## 2. Proposed Architecture Diagram

```
com.company.application
│
├── Base64convertorApplication.java
│
├── common/
│   ├── config/          UnsafeRestTemplate, AsyncConfig, cross-cutting AppProperties (http/retry/url)
│   ├── constants/        NEW — extracted magic strings (see Phase 2)
│   ├── exception/        NEW — typed exceptions, kept THIN (no behavior-changing global handler — see risk note)
│   ├── util/              LoggingInterceptor, FileNameSanitizer, FileSizeFormatter (NEW, extracted)
│   ├── validation/        Base64FileValidator, UrlAllowlistValidator, PathTraversalGuard (NEW, extracted)
│   ├── service/  *(extension — see note below)*   Base64OutputWriter, FileTypeDetectionService
│   └── model/    *(extension — see note below)*   DetectionResult, BinaryWriteResult
│
├── fileconversion/
│   ├── controller/       FileConvertController (unchanged endpoints: /convert, /convert/single, /status/{id})
│   ├── service/           FileConversionService (audit-log + retry-download logic only)
│   ├── scheduler/  *(extension)*  FileConversionCleanupScheduler (extracted @Scheduled job)
│   ├── config/     *(extension)*  FileCacheProperties (module-local, not cross-cutting)
│   ├── dto/               FileConvertRequest, FileConvertResponse
│   └── mapper/            (reserved — see Phase 2, currently no non-trivial mapping to extract here)
│
├── filestorage/
│   ├── controller/       split from FileRetrievalController (see Phase 3 for exact split, same URLs)
│   ├── service/           Base64DecodingService
│   ├── dto/               Base64SaveRequest, Base64SaveResponse, DecodedFileSaveResponse, FileInfo
│   └── model/             DecodedFileResult (promoted from inner class)
│
├── pdf/
│   ├── controller/       split from PdfController (see Phase 3, same URLs)
│   ├── service/           PdfService (fetch/relay), PdfProtectionService (protect)
│   ├── dto/               PdfRequest, PdfResponse, PdfBase64Request, PdfBase64RequestDynamic,
│   │                       PdfProtectRequest, PdfProtectResponse, TargetApiRequest
│   └── mapper/     *(extension)*  TargetApiRequestMapper (extracted from PdfController private methods)
│
├── checksum/
│   ├── controller/       ChecksumController
│   ├── service/           ChecksumService (NEW — extracted CRC32 logic, currently inline in controller)
│   └── dto/               ChecksumRequest (promoted from inline nested class), ChecksumResponse
│
├── jobstatus/
│   ├── controller/       (split from FileConvertController: GET /status/{id} only)
│   ├── service/           (thin facade over FileConversionService's async result map — see Phase 3 risk note)
│   └── dto/               (reuses fileconversion's FileConvertResponse — see note)
│
└── health/
    ├── controller/       TestController
    └── dto/               (none needed — plain string responses, unchanged)
```

**Two intentional extensions beyond your literal tree, both flagged explicitly:**
1. `common/service` + `common/model` — `Base64OutputWriter` and `FileTypeDetectionService` are consumed by **more than one** business module (fileconversion, filestorage, and pdf). Forcing a cross-cutting service into one "owning" module would create a hidden reverse-dependency (e.g., `pdf` importing from `filestorage`), which defeats the purpose of modularization. Common is the correct home for anything 2+ modules depend on.
2. Module-local `config/` and `scheduler/` subfolders (`fileconversion/config`, `fileconversion/scheduler`) — your tree only shows `common/config`, but `FileCacheProperties` and the cleanup scheduler are fileconversion-specific, not cross-cutting. Putting module-specific config in `common` would make `common` a dumping ground and defeat SRP at the package level.

## 3. Package Structure (flat listing)

```
common/config/UnsafeRestTemplate.java
common/config/AsyncConfig.java
common/config/CommonAppProperties.java          (renamed slice of AppProperties: http, retry, url)
common/constants/FileConstants.java              (NEW)
common/exception/InvalidBase64Exception.java     (NEW)
common/exception/PathTraversalException.java     (NEW)
common/exception/UnsupportedUrlException.java    (NEW)
common/util/LoggingInterceptor.java
common/util/FileNameSanitizer.java               (NEW, extracted)
common/util/FileSizeFormatter.java               (NEW, extracted)
common/validation/Base64FileValidator.java
common/validation/UrlAllowlistValidator.java
common/validation/PathTraversalGuard.java        (NEW, extracted)
common/service/Base64OutputWriter.java
common/service/FileTypeDetectionService.java
common/model/DetectionResult.java                (promoted from FileTypeDetectionService inner class)
common/model/BinaryWriteResult.java              (promoted from Base64OutputWriter inner class)

fileconversion/controller/FileConvertController.java
fileconversion/service/FileConversionService.java
fileconversion/scheduler/FileConversionCleanupScheduler.java  (NEW, extracted)
fileconversion/config/FileConversionProperties.java            (renamed FileCacheProperties)
fileconversion/dto/FileConvertRequest.java
fileconversion/dto/FileConvertResponse.java

filestorage/controller/DecodedFileController.java     (save-decoded, download-decoded, metadata)
filestorage/controller/Base64FileController.java      (list, download/{fileName}, content/{fileName}, DELETE)
filestorage/controller/CallbackController.java        (callback)
filestorage/service/Base64DecodingService.java
filestorage/dto/Base64SaveRequest.java
filestorage/dto/Base64SaveResponse.java
filestorage/dto/DecodedFileSaveResponse.java
filestorage/dto/FileInfo.java
filestorage/model/DecodedFileResult.java               (promoted from Base64DecodingService inner class)

pdf/controller/PdfFetchController.java     (convert/base64, convert/base64dynamic)
pdf/controller/PdfDeliveryController.java  (send, single)
pdf/controller/PdfProtectionController.java (protect)
pdf/service/PdfService.java
pdf/service/PdfProtectionService.java
pdf/dto/PdfRequest.java  PdfResponse.java  PdfBase64Request.java  PdfBase64RequestDynamic.java
pdf/dto/PdfProtectRequest.java  PdfProtectResponse.java  TargetApiRequest.java
pdf/mapper/TargetApiRequestMapper.java      (NEW, extracted from PdfController)

checksum/controller/ChecksumController.java
checksum/service/ChecksumService.java        (NEW, extracted)
checksum/dto/ChecksumRequest.java            (promoted from inline nested class)
checksum/dto/ChecksumResponse.java           (NEW — currently an anonymous Map<String,String>)

jobstatus/controller/JobStatusController.java  (GET /api/files/status/{id} only)

health/controller/TestController.java
```

---

# Phase 2: Code Smells Identified

| # | Smell | Location | Category |
|---|---|---|---|
| 1 | **God controller** — 8 unrelated endpoints (decode-save, raw-save, list, download×2, metadata, delete) in one class | `FileRetrievalController` | SRP violation |
| 2 | **God service with unrelated scheduled jobs** — one `@Scheduled` method fans out to 4 unrelated cleanup routines (temp cache, audit-log rotation, async-result GC, `.b64` retention) | `FileConversionService.cleanupOldFilesAndLogs()` | SRP violation |
| 3 | **God config class** — `AppProperties` holds config for 5 unrelated concerns (HTTP client, PDF streaming+protection, batch conversion, URL allowlist, retry, Base64 output) in one `@ConfigurationProperties` bean | `AppProperties` | SRP violation |
| 4 | **Mixed controller concerns** — fetch-and-return, fetch-and-relay-to-target, and password-protect all live in one `PdfController` | `PdfController` | SRP violation |
| 5 | **Business logic embedded in controller** — CRC32 checksum computation is a private method inside the controller, not a service | `ChecksumController.computeChecksum()` | Missing service layer |
| 6 | **Business logic (mapping) embedded in controller** — `PdfRequest` → `TargetApiRequest` transformation is 50+ lines of private controller methods | `PdfController.buildTargetApiRequest/buildTargetHeaders` | Missing mapper layer |
| 7 | **DTO defined inline inside a controller** instead of its own file | `ChecksumController.ChecksumRequest` (static nested class) | Poor DTO placement |
| 8 | **Duplicated path-traversal guard** — the exact same `filePath.normalize().startsWith(...)` check copy-pasted 5 times | `FileRetrievalController` (5 methods) | Duplicate code |
| 9 | **Duplicated `formatFileSize()`** — identical private method in two classes | `FileRetrievalController`, `Base64DecodingService` | Duplicate code |
| 10 | **Duplicated filename-sanitization regex** — same `[^a-zA-Z0-9._-]` replace logic in three places | `FileRetrievalController`, `Base64DecodingService`, `Base64OutputWriter` | Duplicate code |
| 11 | **Result/value classes buried as public static inner classes** with hand-rolled builders instead of top-level model classes | `Base64DecodingService.DecodedFileResult`, `FileTypeDetectionService.DetectionResult`, `Base64OutputWriter.BinaryWriteResult` | Poor model placement |
| 12 | **Flat, unscoped `dto` package** — 11 DTOs from 4 different business domains sit in one folder with no grouping | `dto/*` | Poor package cohesion |
| 13 | **Dead code** — zero callers anywhere in the codebase | `DynamicHttpService` | Unused code |
| 14 | **Configuration-holding class misfiled as a util** — `UnsafeRestTemplate` is a `@Configuration` class defining a bean, not a utility | `util/UnsafeRestTemplate.java` | Naming/placement inconsistency |
| 15 | **No custom exception types** — every failure path is caught via generic `Exception`/`IllegalArgumentException`/`IOException` and hand-formatted into a message string, per endpoint, with no shared vocabulary of typed failures | All controllers | Missing exception layer |
| 16 | **Inconsistent constructor injection style** — most classes use constructor injection (good), but `PdfController` mixes constructor injection with field-level `@Value` | `PdfController` | Naming/style inconsistency |
| 17 | **Anonymous/ad-hoc response shape** — checksum endpoint returns a raw `Map<String,String>` instead of a typed response DTO | `ChecksumController` | Missing DTO |

---

# Phase 3: Modular Design

For each numbered smell above, here is the concrete refactor with the required **A–G** breakdown.

### 3.1 Split `FileRetrievalController` into three module-scoped controllers

**A. Current Problem:** One class owns 8 endpoints spanning three distinct concerns: (1) decode Base64 → save binary + metadata, (2) save raw Base64 text + retrieve it, (3) list/download/delete `.b64` files. Changes to one concern risk regressions in the others; the class is hard to navigate and hard to unit test in isolation.

**B. Proposed Module:** `filestorage.controller`, split into:
- `DecodedFileController` — `POST /api/files/save-decoded`, `GET /api/files/download-decoded/{fileName}`, `GET /api/files/metadata/{fileName}`
- `Base64FileController` — `GET /api/files/list`, `GET /api/files/download/{fileName}`, `GET /api/files/content/{fileName}`, `DELETE /api/files/{fileName}`
- `CallbackController` — `POST /api/files/callback`

**C. Classes To Create:** `DecodedFileController.java`, `Base64FileController.java`, `CallbackController.java` (all in `filestorage.controller`).

**D. Classes To Modify:** None functionally — this is a pure move-and-split; method bodies are copied verbatim. `FileRetrievalController.java` is deleted once the split is verified.

**E. Dependency Impact:** All three new controllers inject `AppProperties` (or its successor) and `Base64DecodingService` exactly as before. No new dependencies introduced. Spring's `@RequestMapping("/api/files")` is declared identically on all three classes — Spring has no problem with multiple controllers sharing a base path as long as no two methods share the same full path + HTTP method, which is already guaranteed here (each endpoint's sub-path is unique).

**F. Risk Level:** **Low.** Each method body is moved unchanged; no logic is altered. The only way to break something is a copy-paste error, which is caught immediately by the regression test suite (Phase 5) since every endpoint's exact request/response is re-verified.

**G. Why Behavior Remains Unchanged:** URLs are literal `@GetMapping`/`@PostMapping`/`@DeleteMapping` path strings copied verbatim. Request/response DTOs are untouched. The path-traversal guard, extension checks, and error-status codes are copied byte-for-byte (or extracted into `PathTraversalGuard`, see 3.8, which is proven equivalent before use). No Spring bean name or `@RequestMapping` prefix collision is introduced.

---

### 3.2 Extract scheduled cleanup into a dedicated scheduler

**A. Current Problem:** `FileConversionService.cleanupOldFilesAndLogs()` is a single `@Scheduled(cron = "0 0 * * * *")` method that fans out to temp-cache cleanup, audit-log rotation/archival, async-result-map GC, and delegates `.b64` cleanup — none of which are "file conversion" business logic; they're housekeeping. This makes `FileConversionService` responsible for both *doing conversions* and *scheduling cluster-wide janitorial work*, and makes the conversion logic harder to unit-test because the class also carries `@Scheduled` wiring.

**B. Proposed Module:** `fileconversion.scheduler.FileConversionCleanupScheduler`.

**C. Classes To Create:** `FileConversionCleanupScheduler.java` — holds the exact same `@Scheduled(cron = "0 0 * * * *")` method, `cleanupTempFiles()`, `cleanupOldLogs()`, `cleanupAsyncResults()`, `zipFile()`, `extractDateFromLogFileName()`, injecting `FileConversionService` (for `asyncResults`/`asyncResultTimes` — see risk note below) and `Base64OutputWriter`.

**D. Classes To Modify:** `FileConversionService` — remove the `@Scheduled` method and the five private cleanup helpers; keep `processFile`, `processFileAsync`, `getAsyncResult`, `handleFileProcessing`, `downloadWithRetry`, `logAudit`.

**E. Dependency Impact:** The async-result maps (`asyncResults`, `asyncResultTimes`) are currently `private` fields of `FileConversionService`. Moving their cleanup to a separate class requires either (a) package-private/exposed accessors on `FileConversionService` for the scheduler to call, or (b) moving the maps themselves into a small `AsyncJobRegistry` component that both `FileConversionService` and the scheduler depend on. **Recommend (b)** — it's cleaner and directly sets up the `jobstatus` module (3.10) to depend on the same registry instead of reaching into `FileConversionService`.

**F. Risk Level:** **Low-Medium.** The cron expression, retention math, and file-system paths are unchanged; the only risk is the async-map extraction touching three methods (`processFileAsync`, `getAsyncResult`, `cleanupAsyncResults`) that must keep referencing the *same* map instance (not a copy) so in-flight async jobs are still visible to `GET /status/{id}` during the transition.

**G. Why Behavior Remains Unchanged:** Cron schedule, cleanup thresholds (`file.cache.retention-hours`, `app.base64.retention-days`, `file.cache.audit.archive-retention-days`), and file-system side effects are copied verbatim, just re-homed into a class dedicated to that single responsibility. The `AsyncJobRegistry` is a pure extraction of existing `ConcurrentHashMap` fields with the same identity, so no observable timing/consistency change occurs.

---

### 3.3 Split `AppProperties` along module boundaries

**A. Current Problem:** One `@ConfigurationProperties(prefix = "app")` class centralizes settings for 5 unrelated concerns. Any module wanting its own config must reach into a shared god-object, and it's unclear from the class alone which settings belong to which feature.

**B. Proposed Module:** `common.config.CommonAppProperties` (http, retry, url — genuinely cross-cutting, used by 2+ modules) + `fileconversion.config.FileConversionProperties` (rename of existing `FileCacheProperties`, already module-scoped) + `pdf.config.PdfProtectionProperties` (the `pdf.protect.*` block) + `filestorage.config.Base64OutputProperties` (the `base64.*` output block) + keep `convert.max-batch-size` in `fileconversion.config` alongside the rest of that module's settings.

**C. Classes To Create:** `CommonAppProperties.java`, `PdfProtectionProperties.java`, `Base64OutputProperties.java` (each a `@ConfigurationProperties` class bound to the **same property prefixes** as today, e.g. `app.pdf.protect`, `app.base64`).

**D. Classes To Modify:** Every class that currently calls `appProperties.getHttp()`, `.getPdf().getStreamThresholdBytes()`, `.getPdf().getProtect()...`, `.getConvert()`, `.getUrl()`, `.getRetry()`, `.getBase64()` must be updated to inject the new, narrower properties bean instead (e.g. `PdfService` now injects `CommonAppProperties` for retry/http and keeps its own `streamThresholdBytes` — see note below).

**E. Dependency Impact:** This is the **highest-touch** item in the whole plan — nearly every service and one utility (`Base64OutputWriter`) reads from `AppProperties` today. Each read site must be re-pointed to the correct narrower properties bean. **This is why it is scheduled last and marked optional/Phase-2** in the step-by-step plan (Phase 4) — it delivers real SRP improvement but has the largest surface area for a silent mistake (e.g., accidentally binding two `@ConfigurationProperties` classes to overlapping prefixes, or missing one call site).

**F. Risk Level:** **Medium-High.** Property *values and prefixes* do not change (so `application.properties` itself needs zero edits), but the **Java-side wiring** touches ~10 files. Recommend doing this only after Phase 4 Steps 1–8 are complete and verified, as its own isolated, separately-verified step — or skipping it entirely and keeping `AppProperties` as one class in `common.config` (still a legitimate, lower-risk choice — see Phase 4 "Alternative: keep AppProperties as-is").

**G. Why Behavior Remains Unchanged:** `@ConfigurationProperties` binding is purely structural — Spring binds `app.pdf.protect.password-pattern` to whatever `@ConfigurationProperties(prefix = "app.pdf.protect")` class exists, regardless of its Java class name or package. As long as each new properties class targets the exact same prefix as the corresponding nested class did before, and every call site is correctly re-pointed, the resolved values at runtime are byte-for-byte identical to today.

---

### 3.4 Split `PdfController` into three intent-scoped controllers

**A. Current Problem:** One class mixes three distinct responsibilities: fetching a file and returning it (`/convert/base64`, `/convert/base64dynamic`), fetching and relaying it to a third-party target system (`/send`, `/single`), and password-protecting an existing PDF (`/protect`). These have different collaborators (`PdfService` vs. `PdfProtectionService` + `RestTemplate`/`ObjectMapper`) and different failure modes.

**B. Proposed Module:** `pdf.controller`, split into `PdfFetchController` (`/convert/base64`, `/convert/base64dynamic`), `PdfDeliveryController` (`/send`, `/single`, plus the private `buildTargetApiRequest`/`buildTargetHeaders` helpers — moved to a mapper, see 3.6), `PdfProtectionController` (`/protect`).

**C. Classes To Create:** `PdfFetchController.java`, `PdfDeliveryController.java`, `PdfProtectionController.java` — all under `@RequestMapping("/api/files/pdf")`.

**D. Classes To Modify:** None functionally; `PdfController.java` is deleted once the split is verified, exactly as in 3.1.

**E. Dependency Impact:** `PdfFetchController` needs only `PdfService`. `PdfDeliveryController` needs `PdfService`, `RestTemplate`, `ObjectMapper`, and the new `TargetApiRequestMapper` (3.6). `PdfProtectionController` needs `PdfProtectionService`, `Base64OutputWriter` (from `common.service`), and `Base64FileValidator` (from `common.validation`) — a strictly smaller dependency graph per class than today's single `PdfController`.

**F. Risk Level:** **Low**, same reasoning as 3.1 — pure move-and-split, no logic change.

**G. Why Behavior Remains Unchanged:** All five endpoint paths, HTTP methods, `@RequestBody` types, and response types are copied verbatim into the new classes.

---

### 3.5 Extract checksum logic into `ChecksumService` + typed DTOs

**A. Current Problem:** CRC32 computation and nonce generation are private controller methods; the request shape is a static nested class with no top-level identity; the response is an untyped `Map<String,String>` built ad hoc in two places.

**B. Proposed Module:** `checksum.service.ChecksumService` + `checksum.dto.ChecksumRequest`/`ChecksumResponse`.

**C. Classes To Create:** `QrDecoderService.java` (method: `ChecksumResponse compute(String message, String secretKey)`, containing the exact same `CRC32`/UTF-8/`message + "|" + secretKey` logic), `checksum.dto.ChecksumRequest.java` (promoted from the inline nested class, identical fields/getters/setters), `checksum.dto.ChecksumResponse.java` (fields: `checksum`, `nonce` — must serialize to the exact same two-key JSON object).

**D. Classes To Modify:** `ChecksumController` — both endpoint methods now call `checksumService.compute(...)` and return `ResponseEntity.ok(response)` instead of building the map inline; the 400 error path (`Map.of("error", ...)`) is kept as-is in the controller (or promoted to a matching `ChecksumErrorResponse` — optional, only if you want full DTO typing on the error path too).

**E. Dependency Impact:** `ChecksumController` now depends on `ChecksumService` (new, trivial, no external dependencies of its own). No change to any other class.

**F. Risk Level:** **Low.** The CRC32 algorithm, input concatenation (`message + "|" + secretKey"`), UTF-8 encoding, and nonce generation (`UUID.randomUUID()`) are copied verbatim; `ChecksumResponse`'s two fields (`checksum`, `nonce`) serialize identically to the current `Map.of("checksum", ..., "nonce", ...)`.

**G. Why Behavior Remains Unchanged:** Jackson serializes a `@Data` POJO with fields `checksum`/`nonce` to the exact same `{"checksum":"...","nonce":"..."}` shape as a `Map.of("checksum", ..., "nonce", ...)` — field order aside (JSON objects are unordered, and no consumer should depend on key order), the payload is identical.

---

### 3.6 Extract `PdfRequest → TargetApiRequest` mapping into `TargetApiRequestMapper`

**A. Current Problem:** `buildTargetApiRequest()` and `buildTargetHeaders()` are 50+ lines of private controller logic duplicated in intent between `/send` and `/single` handlers — classic mapper-layer logic hiding in a controller.

**B. Proposed Module:** `pdf.mapper.TargetApiRequestMapper`.

**C. Classes To Create:** `TargetApiRequestMapper.java` with `TargetApiRequest toTargetApiRequest(PdfRequest req)` and `HttpHeaders buildTargetHeaders(PdfRequest req, String defaultToken)` — bodies copied verbatim from the current private methods.

**D. Classes To Modify:** `PdfDeliveryController` (from 3.4) — replace `buildTargetApiRequest(req)`/`buildTargetHeaders(req)` calls with `targetApiRequestMapper.toTargetApiRequest(req)`/`.buildTargetHeaders(req, defaultToken)`.

**E. Dependency Impact:** `PdfDeliveryController` gains one new constructor-injected dependency (`TargetApiRequestMapper`, itself dependency-free). No change to `TargetApiRequest`/`PdfRequest` DTOs.

**F. Risk Level:** **Low.** Pure extract-method refactor; every field copy (`channel`, `content`/`attachment`, `recipient`/`reference`, `sender`, `preferences`, `metaData`/`version`) is preserved exactly, including the existing quirk where `TargetApiRequest.Reference` is always constructed empty (no fields copied from source) — that quirk is *business logic as currently implemented* and must NOT be "fixed" as part of this refactor per constraint #4.

**G. Why Behavior Remains Unchanged:** Field-by-field copy logic is textually identical, just relocated. The outbound JSON built by `objectMapper.writeValueAsString(targetApiRequest)` is byte-for-byte identical because the object graph constructed is identical.

---

### 3.7 Promote inner result classes to top-level `model` classes

**A. Current Problem:** `Base64DecodingService.DecodedFileResult`, `FileTypeDetectionService.DetectionResult`, and `Base64OutputWriter.BinaryWriteResult` are public static nested classes with hand-rolled builder boilerplate (in two of the three cases) — not real domain models, just structurally hidden inside their producing service.

**B. Proposed Module:** `filestorage.model.DecodedFileResult`, `common.model.DetectionResult`, `common.model.BinaryWriteResult`.

**C. Classes To Create:** Three top-level classes with identical fields; the two hand-rolled builders (`DecodedFileResult`, `DetectionResult`) can be simplified to Lombok `@Builder` (behaviorally identical output, less code) — `BinaryWriteResult` already uses Lombok `@Builder`.

**D. Classes To Modify:** `Base64DecodingService` (change `DecodedFileResult.builder()...` to import the new top-level class — call sites unchanged since the builder API is preserved), `FileTypeDetectionService` (same), `Base64OutputWriter` (same), plus every caller that references `Base64DecodingService.DecodedFileResult` etc. by qualified inner-class name updates its import.

**E. Dependency Impact:** Purely a class relocation; no new dependencies. `FileRetrievalController`'s (or its successors') references to `Base64DecodingService.DecodedFileResult` become plain `DecodedFileResult` imports.

**F. Risk Level:** **Low.** Field names, types, and builder method names are preserved exactly; only the class's package/enclosing-class changes.

**G. Why Behavior Remains Unchanged:** These are pure data carriers with no serialization annotations reaching outside the service layer (they are never directly returned by a controller — every controller builds its own DTO from these internal models), so moving them cannot affect any JSON contract.

---

### 3.8 Extract the duplicated path-traversal guard into `PathTraversalGuard`

**A. Current Problem:** `filePath.normalize().startsWith(Paths.get(outputPath).normalize())` (or an equivalent check) is copy-pasted 5 times across `FileRetrievalController`'s download/metadata/delete endpoints, each with subtly different surrounding log messages — a change to the security check itself would require 5 synchronized edits today.

**B. Proposed Module:** `common.validation.PathTraversalGuard`.

**C. Classes To Create:** `PathTraversalGuard.java` with a single method `boolean isWithin(Path candidate, Path baseDir)` (or `Path resolveSafe(Path baseDir, String fileName)` throwing `PathTraversalException` on violation — see 3.15 for the exception type).

**D. Classes To Modify:** `DecodedFileController` and `Base64FileController` (from 3.1) — each of their 5 guarded methods calls `pathTraversalGuard.isWithin(...)` instead of the inline check, preserving the exact same `403 Forbidden` response and log message text at each call site (only the *computation* of the boolean is shared, not the response-building, which stays local to preserve each endpoint's exact existing log wording).

**E. Dependency Impact:** Both filestorage controllers gain one new constructor-injected dependency (`PathTraversalGuard`, itself dependency-free, pure function).

**F. Risk Level:** **Low.** `Path.normalize().startsWith(...)` semantics are copied exactly; unit tests (Phase 5) can trivially cover both the "inside" and "outside" cases to prove equivalence before switching call sites over.

**G. Why Behavior Remains Unchanged:** The extracted method contains the identical `normalize()`/`startsWith()` logic; every call site's response code (`403`) and behavior for traversal attempts is unchanged — only *where* the boolean check is computed changes, not what it evaluates to for any given input.

---

### 3.9 Extract duplicated `formatFileSize` and filename sanitization

**A. Current Problem:** `formatFileSize(long bytes)` exists verbatim in `FileRetrievalController` and `Base64DecodingService`; the `[^a-zA-Z0-9._-]` filename-sanitizing regex exists in three places (`FileRetrievalController`, `Base64DecodingService`, `Base64OutputWriter`).

**B. Proposed Module:** `common.util.FileSizeFormatter` (static method `format(long bytes)`), `common.util.FileNameSanitizer` (static method `sanitize(String fileName, String fallback)`).

**C. Classes To Create:** `FileSizeFormatter.java`, `FileNameSanitizer.java` — both stateless static utility classes, method bodies copied verbatim from any one of the existing duplicates (they are textually identical across all copies today, confirmed by re-reading each).

**D. Classes To Modify:** `DecodedFileController`, `Base64FileController`, `CallbackController` (filestorage controllers from 3.1), `Base64DecodingService`, `Base64OutputWriter` — each private method body is deleted and replaced with a call to the shared utility.

**E. Dependency Impact:** No new Spring-managed dependencies (these are static utility methods, not injected beans), so no constructor changes — just a static import.

**F. Risk Level:** **Low.** Verified textually identical across all current duplicates before extraction, so the shared version is provably equivalent to every call site it replaces.

**G. Why Behavior Remains Unchanged:** Byte-for-byte identical algorithm, now called from one place instead of three/two.

---

### 3.10 Extract `jobstatus` as a thin module over the existing async registry

**A. Current Problem:** `GET /api/files/status/{processingId}` lives in `FileConvertController` alongside the unrelated `/convert` and `/convert/single` endpoints, and reads directly from `FileConversionService`'s private in-memory map — mixing "submit work" and "poll work status" concerns in one controller/service pair.

**B. Proposed Module:** `jobstatus.controller.JobStatusController`, backed by the `AsyncJobRegistry` introduced in 3.2.

**C. Classes To Create:** `JobStatusController.java` with the single `GET /api/files/status/{processingId}` method, calling `asyncJobRegistry.get(processingId)` instead of `fileConversionService.getAsyncResult(processingId)`.

**D. Classes To Modify:** `FileConvertController` — remove the `/status/{processingId}` method (moved out); `FileConversionService` — `processFileAsync` now writes into the shared `AsyncJobRegistry` instead of its own private maps (same change already required by 3.2).

**E. Dependency Impact:** `JobStatusController` depends only on `AsyncJobRegistry` (from `fileconversion` or `common`, since it's used by both `fileconversion` and `jobstatus` — another legitimate case for a shared `common.service` or a small dedicated `fileconversion.registry` package consumed by `jobstatus`). **Note:** since `processFileAsync` (which populates the registry) is never actually called by any controller today (confirmed dead trigger path in the earlier architecture review), this module has no observable behavior change risk beyond the relocation itself — `GET /status/{id}` will continue to return `404` for any ID in practice, exactly as it does today.

**F. Risk Level:** **Low.** No live caller populates the map today, so there is no realistic regression scenario beyond "the endpoint moves to a new controller class with the same path" — the lowest-risk item in this entire plan.

**G. Why Behavior Remains Unchanged:** Path, HTTP method, response DTO (`FileConvertResponse`), and `404`-on-miss semantics are copied verbatim; the underlying data source (the async result map/registry) is the same object identity, just referenced by a differently-named holder class.

---

### 3.11 Move `UnsafeRestTemplate` from `util` to `common.config`

**A. Current Problem:** It is a `@Configuration` class defining the primary `RestTemplate` bean — structurally a config class, but currently sits in the `util` package, which is misleading for anyone navigating the codebase by package name.

**B. Proposed Module:** `common.config.UnsafeRestTemplate` (or renamed `RestTemplateConfig` — **note:** renaming the class is optional and carries zero runtime risk since Spring resolves `@Configuration`/`@Bean` by type and bean name, not by the enclosing file's historical name; recommend renaming to `RestTemplateConfig` for clarity while you're moving it anyway, since the "Unsafe" prefix describes a *property* of the bean, not a good class identity).

**C. Classes To Create:** None — this is a pure move (+ optional rename).

**D. Classes To Modify:** None functionally.

**E. Dependency Impact:** None — `@Primary @Bean public RestTemplate restTemplate()` is resolved by type everywhere it's injected (`PdfController`/successors, `DynamicHttpService` if kept), regardless of the defining class's package or name.

**F. Risk Level:** **Low.**

**G. Why Behavior Remains Unchanged:** Spring's bean resolution is unaffected by which package/class declares a `@Bean` method; the bean's name, type, and `@Primary` status are unchanged.

---

### 3.12 Remove (or explicitly quarantine) dead code: `DynamicHttpService`

**A. Current Problem:** Zero callers anywhere in the codebase (re-confirmed in this session's Phase 1 grep). Migrating dead code into a shiny new package structure just relocates clutter.

**B. Proposed Module:** N/A — recommend deletion, not migration.

**C. Classes To Create:** None.

**D. Classes To Modify:** Delete `DynamicHttpService.java`.

**E. Dependency Impact:** None — nothing references it.

**F. Risk Level:** **Zero functional risk** (unreachable code cannot affect runtime behavior), but flagged as a **decision point requiring your explicit sign-off** before deletion, since it's possible this class is a placeholder for planned future work you haven't told me about. Default recommendation: delete it as part of this refactor; alternative: leave it in `common` (unused) if you'd rather keep it as a reference implementation.

**G. Why Behavior Remains Unchanged:** A class with no callers, by definition, contributes nothing to current runtime behavior; removing it changes zero observable output.

---

### 3.13 Group flat `dto` package by module

**A. Current Problem:** 11 unrelated DTOs sit in one flat package with no indication of which business capability owns them.

**B. Proposed Module:** As listed in the Phase 1 package structure — `fileconversion/dto`, `filestorage/dto`, `pdf/dto`, `checksum/dto`.

**C. Classes To Create:** None — pure move.

**D. Classes To Modify:** Every controller/service that imports these DTOs needs its `import` statements updated to the new package.

**E. Dependency Impact:** None functionally — Jackson serializes based on field annotations (`@JsonProperty`, `@JsonAnySetter`, etc.), which travel with the class regardless of package. `@JsonProperty("base64_docContent")` on `PdfProtectRequest`, for example, continues to bind the exact same JSON key after the move.

**F. Risk Level:** **Low.** Purely mechanical; verified by recompiling and re-running the full endpoint regression suite (Phase 5).

**G. Why Behavior Remains Unchanged:** JSON field names are controlled by Jackson annotations and Lombok-generated getter/setter names, neither of which reference the Java package. Moving a DTO's `.java` file to a new package never changes its wire format.

---

### 3.14 Extract module-local `AsyncJobRegistry` (supports 3.2 and 3.10)

**A. Current Problem:** In-memory async job state (`ConcurrentHashMap<String, FileConvertResponse> asyncResults` + `ConcurrentHashMap<String, Instant> asyncResultTimes`) is private state of `FileConversionService`, but is logically needed by both the conversion module (writer) and the job-status module (reader) and the scheduler (cleaner) once those are split out.

**B. Proposed Module:** `fileconversion.service.AsyncJobRegistry` (or `common.service` if you prefer to signal it's shared — recommend keeping it in `fileconversion` since conceptually job status *is* about file-conversion jobs specifically, and `jobstatus`/the scheduler both depend inward on `fileconversion`, which is a healthy dependency direction).

**C. Classes To Create:** `AsyncJobRegistry.java` — wraps the two existing maps with `put`, `get`, `Set<Map.Entry<...>> entries()` (for the scheduler's cleanup sweep), `remove`.

**D. Classes To Modify:** `FileConversionService.processFileAsync` (writes via registry), `JobStatusController`/its service (reads via registry), `FileConversionCleanupScheduler.cleanupAsyncResults` (iterates + evicts via registry).

**E. Dependency Impact:** Three classes now depend on one small, focused, easily-unit-testable registry instead of reaching into `FileConversionService`'s private fields (which wasn't even possible before — this data was previously fully encapsulated and only reachable via `FileConversionService`'s own methods, so this is a net encapsulation improvement, not a regression).

**F. Risk Level:** **Low**, same reasoning as 3.2/3.10 — no live caller populates the map in production today.

**G. Why Behavior Remains Unchanged:** Same map identity/semantics, just wrapped in a dedicated class instead of being inline private fields.

---

### 3.15 Introduce typed exceptions — SCOPED CAREFULLY (highest-risk item, optional)

**A. Current Problem:** No shared vocabulary of typed failures; every controller hand-catches `IllegalArgumentException`/`IOException`/generic `Exception` and manually re-builds its own error response shape inline, which is duplicated reasoning (not duplicated *code*, since each shape differs) across ~15 catch blocks.

**B. Proposed Module:** `common.exception` — `InvalidBase64Exception`, `PathTraversalException`, `UnsupportedUrlException`, `PdfAlreadyProtectedException`, `NotAPdfException`.

**C. Classes To Create:** Five small exception classes (extending `RuntimeException`), each thrown from the appropriate validator/service instead of the current ad-hoc `IllegalArgumentException`/`IllegalStateException`.

**D. Classes To Modify:** `UrlAllowlistValidator` (throws `UnsupportedUrlException` instead of generic `IllegalArgumentException`), `PathTraversalGuard` (throws `PathTraversalException`), `PdfProtectionService` (throws `PdfAlreadyProtectedException` instead of `IllegalStateException`), `Base64DecodingService`/protection controllers (throw `InvalidBase64Exception`/`NotAPdfException`) — **and every controller catch block must be updated to catch the new specific type and build the EXACT SAME response body/status code it builds today.**

**E. Dependency Impact:** Every controller with a try/catch touching these failure modes needs its catch clauses widened/narrowed accordingly.

**F. Risk Level:** **Medium-High — explicitly recommend doing this LAST, after full regression coverage exists, and only per-endpoint with its own before/after response diff**, because:
- This is the one refactor category where it is easy to accidentally change a response body or status code (e.g., forgetting that `/pdf/convert/base64` returns `502` on `RestClientException` while `/pdf/convert/base64dynamic` **always** returns `200` with a `FAILED` status string in the body — these two endpoints must keep their divergent, already-inconsistent error-status conventions exactly as they are today, even though it's tempting to "fix" that inconsistency while touching this code. **Do not fix it** — that would violate constraint #4/#7).
- A single blanket `@RestControllerAdvice` returning one generic error DTO shape is explicitly **NOT recommended**, since the current codebase intentionally returns different response shapes per endpoint (`ChecksumController`'s `Map`, `PdfResponse`, `DecodedFileSaveResponse`, `Base64SaveResponse`, `PdfProtectResponse`, and several plain-string/`404`/`403` responses with no body) — collapsing these into one generic advice would be a **breaking response-payload change**, explicitly forbidden.

**G. Why Behavior Remains Unchanged (when done correctly):** Each controller's existing catch block is preserved 1:1, just narrowed to catch the new specific exception type instead of a generic one, and continues to build the identical response DTO/status code it builds today. The exception types are purely a clearer internal vocabulary between validators/services and controllers — they carry no wire-visible representation of their own.

---

# Phase 4: Class-by-Class Refactoring Plan (Step-by-Step)

Recommended execution order — each step is independently buildable, testable, and revertable. **Steps 1–9 are low risk and recommended for immediate execution. Steps 10–11 are higher-touch/optional and should be done as separate, later, individually-verified changes.**

| Step | Action | Depends On | Risk |
|---|---|---|---|
| 1 | Create package skeleton (`common/*`, `fileconversion/*`, `filestorage/*`, `pdf/*`, `checksum/*`, `jobstatus/*`, `health/*`) with no files moved yet — empty packages compile fine | — | None |
| 2 | Move pure, dependency-free utilities first: `Base64FileValidator`, `UrlAllowlistValidator` → `common.validation`; `LoggingInterceptor` → `common.util`; `UnsafeRestTemplate` → `common.config` (3.11) | Step 1 | Low |
| 3 | Extract `FileSizeFormatter`, `FileNameSanitizer`, `PathTraversalGuard` into `common.util`/`common.validation` (3.8, 3.9) — but do NOT switch callers yet, just create + unit test them | Step 1 | Low |
| 4 | Move `Base64OutputWriter`, `FileTypeDetectionService` → `common.service`; promote their inner result classes → `common.model` (3.7) | Step 2 | Low |
| 5 | Split `FileRetrievalController` → `filestorage.controller.{DecodedFileController, Base64FileController, CallbackController}`, switching to the Step 3 shared utilities as part of the same move (3.1, 3.8, 3.9) | Steps 3–4 | Low |
| 6 | Split `PdfController` → `pdf.controller.{PdfFetchController, PdfDeliveryController, PdfProtectionController}`; extract `TargetApiRequestMapper` (3.4, 3.6) | Step 4 | Low |
| 7 | Extract `ChecksumService` + promote `ChecksumRequest`/create `ChecksumResponse` (3.5) | Step 1 | Low |
| 8 | Move all DTOs into module packages (`fileconversion/dto`, `filestorage/dto`, `pdf/dto`, `checksum/dto`) (3.13); move `Base64DecodingService` → `filestorage.service`, `PdfService`/`PdfProtectionService` → `pdf.service`, `FileConversionService` → `fileconversion.service` | Steps 5–7 | Low |
| 9 | Move `TestController` → `health.controller` (no changes needed) | Step 1 | None |
| 10 *(optional)* | Extract `AsyncJobRegistry` + `FileConversionCleanupScheduler` + `JobStatusController` (3.2, 3.10, 3.14) | Step 8 | Low-Medium |
| 11 *(optional, do separately)* | Split `AppProperties` into module-scoped properties classes (3.3) | Step 8 | Medium-High |
| 12 *(optional, do last, separately)* | Introduce typed exceptions per-endpoint with individual before/after diffing (3.15) | Step 8+ | Medium-High |
| 13 *(requires your sign-off)* | Delete `DynamicHttpService` (3.12) | Any time | None (needs confirmation) |

After **every** step, run: full `./mvnw clean package -DskipTests` (compile check), then the full regression checklist (Phase 5) against a running instance before moving to the next step. Commit after each successfully-verified step so any regression can be isolated to a single small diff.

## Dependency Diagram (target state, after all steps)

```
health         ─┐
checksum       ─┤
jobstatus      ─┼──depends on──► common (config, util, validation, exception, service, model)
fileconversion ─┤
filestorage    ─┤
pdf            ─┘

pdf.controller.PdfDeliveryController ──uses──► pdf.mapper.TargetApiRequestMapper
pdf.controller.*                     ──uses──► pdf.service.{PdfService, PdfProtectionService}
pdf.service.*                        ──uses──► common.service.Base64OutputWriter
                                      ──uses──► common.validation.UrlAllowlistValidator

filestorage.controller.*             ──uses──► filestorage.service.Base64DecodingService
                                      ──uses──► common.validation.PathTraversalGuard
                                      ──uses──► common.util.{FileSizeFormatter, FileNameSanitizer}
filestorage.service.Base64DecodingService ──uses──► common.service.FileTypeDetectionService

fileconversion.controller.FileConvertController ──uses──► fileconversion.service.FileConversionService
fileconversion.scheduler.FileConversionCleanupScheduler ──uses──► fileconversion.service.AsyncJobRegistry
                                                          ──uses──► common.service.Base64OutputWriter
jobstatus.controller.JobStatusController ──uses──► fileconversion.service.AsyncJobRegistry

checksum.controller.ChecksumController ──uses──► checksum.service.ChecksumService (no further deps)

NO module depends on another business module directly (fileconversion, filestorage, pdf, checksum,
jobstatus, health are siblings — all shared dependencies flow through `common`). This is the key
architectural property that makes the split "clean": you can reason about/test/modify `pdf` without
needing to understand `filestorage` or `checksum` internals, and vice versa.
```

---

# Phase 5: Regression Validation

## Migration Checklist

- [ ] Package skeleton created, project still compiles with zero files moved (Step 1)
- [ ] Each subsequent step compiles cleanly (`./mvnw clean package -DskipTests`) before moving to the next
- [ ] No `@RequestMapping`/`@GetMapping`/`@PostMapping`/`@DeleteMapping` path string was edited anywhere (grep diff of all path annotations before/after — should be identical set)
- [ ] No DTO field, `@JsonProperty`, `@NotBlank`, or Lombok annotation was changed on any request/response class
- [ ] No `application.properties` key was renamed (Step 11, if done, only changes which Java class binds to an existing key — the key string itself is untouched)
- [ ] `Base64convertorApplication`'s component-scan base package still covers every new package (either keep the app class at the root `com.company.application` or add explicit `@ComponentScan` — default `@SpringBootApplication` scanning is package-and-below, so placing the application class at the new root package handles this automatically)
- [ ] `DynamicHttpService` removal (if approved) confirmed to have zero remaining references via a full-repo grep
- [ ] Postman collection re-run unmodified against the refactored build — 100% pass

## Regression Testing Checklist (per endpoint, all 19)

For **every** endpoint listed in the original architecture doc (`/api/files/checksum`, `/checksumgenerator`, `/convert`, `/convert/single`, `/status/{id}`, `/pdf/send`, `/pdf/convert/base64`, `/pdf/convert/base64dynamic`, `/pdf/single`, `/pdf/protect`, `/save-decoded`, `/download-decoded/{fileName}`, `/metadata/{fileName}`, `/callback`, `/list`, `/download/{fileName}`, `/content/{fileName}`, `DELETE /{fileName}`, `/api/test/ping`, `/api/test/echo`):

1. Capture a baseline request/response pair **before** refactoring (exact JSON body + HTTP status + headers of interest).
2. After each refactoring step that touches that endpoint's controller, re-issue the identical request.
3. Diff the response body, status code, and `Content-Type`/`Content-Disposition` headers byte-for-byte against the baseline.
4. Re-verify all documented error scenarios per endpoint (from the earlier fresher's-guide doc — e.g., path traversal → `403`, missing fields → `400`, invalid Base64 → `400`, already-protected PDF → `400`, batch-size exceeded → `400`) still produce identical status + body.
5. Re-verify file-system side effects are unchanged: same output directory, same file-naming convention (`timestamp_shortId_name.ext`), same `.meta.json` sidecar shape, same download-link format.
6. Re-run the hourly cleanup logic manually (call the scheduled method directly in a test) and confirm identical files are targeted for deletion/archival under the same retention settings.
7. Confirm Micrometer metric names (`base64.conversions`, `pdf.conversions`, `pdf.protect`, `base64.output.files.*`) are unchanged — moving a `@Service` class to a new package does not change its metric names (defined by string literals in `Counter.builder(...)`), but confirm via `/actuator/prometheus` diff anyway.

## Unit Test Recommendations

| New/extracted class | Suggested tests |
|---|---|
| `PathTraversalGuard` | inside-directory path → true/allowed; `../../etc/passwd`-style traversal → false/blocked; symlink edge case if applicable |
| `FileNameSanitizer` | special characters stripped; blank/null → fallback value; already-safe names pass through unchanged |
| `FileSizeFormatter` | 0 bytes → `"0 B"`; exact power-of-1024 boundaries (1024 → `"1.00 KB"`); large values → `"TB"` |
| `ChecksumService` | same input → same CRC32 output (determinism); different `secretKey` → different checksum; nonce is always a valid UUID and differs per call |
| `TargetApiRequestMapper` | full `PdfRequest` → every field correctly copied to `TargetApiRequest`; `null` `message`/`content`/`attachment`/`recipient`/`sender`/`preferences` at each level → no NPE, matching field left `null` exactly as today |
| `AsyncJobRegistry` | put/get round-trip; eviction after configured retention; concurrent put/get thread-safety (it's still backed by `ConcurrentHashMap`) |
| `PdfProtectionService.buildPassword` | already covered manually this session (config-override test) — formalize as a unit test with a fake `AppProperties`/`PdfProtectionProperties` for each token type (`{NAME}`, `{NAME:n}`, `{NAME:n:UPPER}`, `{NAME:n:LOWER}`, `{DOB}`) |
| Each split controller | Spring `@WebMvcTest` slice tests hitting each endpoint with the exact request bodies captured in the regression baseline, asserting byte-identical response bodies |

## Final Verification Matrix

| Verification item | How proven unchanged |
|---|---|
| **Endpoint unchanged** | Every `@RequestMapping`/`@*Mapping` path string is copied verbatim into its new controller class — no path segment, `{pathVariable}` name, or HTTP method is edited at any step. Diff of `grep -rn "@.*Mapping"` output before/after must be identical modulo file path. |
| **Request unchanged** | Every DTO's fields, types, and Jackson/Bean-Validation annotations (`@NotBlank`, `@JsonProperty`, `@JsonAnySetter`, `@Valid`) are copied unmodified when the class moves package — Jackson binds by annotation, not by package, so wire format is unaffected. |
| **Response unchanged** | Every response DTO is likewise moved unmodified; every controller method's response-building logic (including status codes for each error branch) is copied verbatim, not rewritten, except where explicitly flagged (Phase 3.15) as requiring extra care — and that item is scheduled last, done per-endpoint, with an explicit before/after diff gate. |
| **Database unchanged** | Confirmed in Phase 1: this application has no database, JPA, entities, or repositories. This item is trivially satisfied — there is nothing to change. All persistence is filesystem-based and no file-path, naming convention, or directory structure is altered by this refactor (only Java package structure changes, never `app.base64.output-path`/`file.cache.path` values or the file-naming scheme). |
| **Business logic unchanged** | Every extracted method (checksum computation, password derivation, target-request mapping, cleanup thresholds, retry/backoff math, MIME detection, magic-byte validation) is copied verbatim — extraction relocates code, it does not rewrite algorithms. The one deliberately-flagged quirk (`TargetApiRequest.Reference` always built empty) is explicitly preserved, not "fixed," per constraint #4. |

---

# Phase 6: Final Folder Structure

```
src/main/java/com/company/application/
├── Base64convertorApplication.java
│
├── common/
│   ├── config/
│   │   ├── UnsafeRestTemplate.java   (or renamed RestTemplateConfig.java)
│   │   ├── AsyncConfig.java
│   │   └── CommonAppProperties.java  (optional — Phase 3.3, or keep AppProperties whole here)
│   ├── constants/
│   │   └── FileConstants.java
│   ├── exception/
│   │   ├── InvalidBase64Exception.java
│   │   ├── PathTraversalException.java
│   │   ├── UnsupportedUrlException.java
│   │   ├── PdfAlreadyProtectedException.java
│   │   └── NotAPdfException.java
│   ├── util/
│   │   ├── LoggingInterceptor.java
│   │   ├── FileNameSanitizer.java
│   │   └── FileSizeFormatter.java
│   ├── validation/
│   │   ├── Base64FileValidator.java
│   │   ├── UrlAllowlistValidator.java
│   │   └── PathTraversalGuard.java
│   ├── service/
│   │   ├── Base64OutputWriter.java
│   │   └── FileTypeDetectionService.java
│   └── model/
│       ├── DetectionResult.java
│       └── BinaryWriteResult.java
│
├── fileconversion/
│   ├── controller/FileConvertController.java
│   ├── service/
│   │   ├── FileConversionService.java
│   │   └── AsyncJobRegistry.java
│   ├── scheduler/FileConversionCleanupScheduler.java
│   ├── config/FileConversionProperties.java
│   └── dto/
│       ├── FileConvertRequest.java
│       └── FileConvertResponse.java
│
├── filestorage/
│   ├── controller/
│   │   ├── DecodedFileController.java
│   │   ├── Base64FileController.java
│   │   └── CallbackController.java
│   ├── service/Base64DecodingService.java
│   ├── dto/
│   │   ├── Base64SaveRequest.java
│   │   ├── Base64SaveResponse.java
│   │   ├── DecodedFileSaveResponse.java
│   │   └── FileInfo.java
│   └── model/DecodedFileResult.java
│
├── pdf/
│   ├── controller/
│   │   ├── PdfFetchController.java
│   │   ├── PdfDeliveryController.java
│   │   └── PdfProtectionController.java
│   ├── service/
│   │   ├── PdfService.java
│   │   └── PdfProtectionService.java
│   ├── config/PdfProtectionProperties.java  (optional — Phase 3.3)
│   ├── dto/
│   │   ├── PdfRequest.java / PdfResponse.java
│   │   ├── PdfBase64Request.java / PdfBase64RequestDynamic.java
│   │   ├── PdfProtectRequest.java / PdfProtectResponse.java
│   │   └── TargetApiRequest.java
│   └── mapper/TargetApiRequestMapper.java
│
├── checksum/
│   ├── controller/ChecksumController.java
│   ├── service/ChecksumService.java
│   └── dto/
│       ├── ChecksumRequest.java
│       └── ChecksumResponse.java
│
├── jobstatus/
│   └── controller/JobStatusController.java
│
└── health/
    └── controller/TestController.java
```

---

## Next Steps

This document satisfies the requested deliverable (analysis + plan). Given the scale (32 files, full package rename from `com.twixor.base64convertor` to `com.company.application`, plus the class splits above), I recommend executing it as a **separate, phased implementation pass** — Steps 1–9 (Phase 4 table) first as one verified batch (low risk, high value), with Steps 10–13 as explicit follow-ups you sign off on individually, given their higher touch/risk profile. I have not moved or renamed any files yet.
