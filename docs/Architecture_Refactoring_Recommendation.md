# Base64 Convertor — Architecture Refactoring Recommendation (Revision 2)

**Status:** Production-ready recommendation, supersedes `Modular_Refactoring_Plan.md` for execution purposes (that document remains valid background analysis; this document reflects the reviewed and approved/postponed decisions).
**Nothing in this document has been executed.** No source file has been moved, renamed, or edited as part of this revision — this is the planning deliverable.

## Summary of what changed since the first plan

| Item | v1 recommendation | v2 decision (this document) |
|---|---|---|
| `AppProperties` split into module-scoped classes | Optional Phase-2 | **Postponed entirely.** Not attempted this cycle — high touch (~10 call sites), low business value, large regression surface for a config file that already works. |
| Global exception framework (`@RestControllerAdvice`) | Flagged high-risk, "do last if at all" | **Postponed entirely, not recommended at all.** Endpoints intentionally return different response shapes; a blanket advice risks silently changing a response contract. Existing per-controller try/catch is kept exactly as-is. |
| `DynamicHttpService` | Recommend deletion pending sign-off | **Two-step deprecation path**, not immediate deletion: Phase A marks it `@Deprecated` with an explanatory comment (zero-risk, additive); actual removal moves to Phase D, gated on a verification window. |
| Facade layer | Not present in v1 | **New.** Added where a controller currently orchestrates 3+ collaborators/steps (PDF protection, PDF delivery, file storage). Explicitly **not** added to `PdfFetchController`, `FileConvertController`, `ChecksumController`, or job-status reads, since those already delegate to a single service with no multi-step orchestration in the controller — a facade there would be a value-free pass-through. |
| Class renames bundled into moves (`FileCacheProperties`→`FileConversionProperties`, `UnsafeRestTemplate`→`RestTemplateConfig`) | Bundled into the move step | **Un-bundled.** Phase A moves classes to new packages with **zero renames** to minimize diff size per change. Renames are now an explicit, separate, optional Phase D cosmetic item. |
| `jobstatus` module / `AsyncJobRegistry` | Classified low risk in v1 | **Reclassified to Phase B (Medium Risk)** per your instruction — `GET /status/{id}` stays inside `FileConvertController` until `AsyncJobRegistry` exists. |

---

## Deliverable 1: Updated Architecture Diagram

### Current (as of this session)

```
com.twixor.base64convertor
├── config/          AppProperties, AsyncConfig, FileCacheProperties
├── controller/       ChecksumController, FileConvertController, FileRetrievalController(8 endpoints),
│                      PdfController(5 endpoints), TestController
├── service/          Base64DecodingService, DynamicHttpService(dead), FileConversionService,
│                      FileTypeDetectionService, PdfProtectionService, PdfService
├── dto/               (flat, 13 DTOs across 4 domains)
└── util/              Base64FileValidator, Base64OutputWriter, LoggingInterceptor,
                        UnsafeRestTemplate, UrlAllowlistValidator
```

### Proposed (Phase A end-state — the scope approved for immediate execution)

```
com.company.application
├── common/                                    ← shared, no business-module dependencies
│   ├── config/        AppProperties (moved, content untouched), AsyncConfig, UnsafeRestTemplate
│   ├── util/            LoggingInterceptor, FileNameSanitizer, FileSizeFormatter
│   ├── validation/       Base64FileValidator, UrlAllowlistValidator, PathTraversalGuard
│   ├── service/          Base64OutputWriter, FileTypeDetectionService
│   └── model/             DetectionResult, BinaryWriteResult
│
├── fileconversion/
│   ├── controller/       FileConvertController   (still owns /status/{id} in Phase A — see Phase B)
│   ├── service/           FileConversionService
│   ├── config/             FileCacheProperties (moved, unrenamed)
│   └── dto/                 FileConvertRequest, FileConvertResponse
│
├── filestorage/
│   ├── controller/       DecodedFileController, Base64FileController, CallbackController  (all thin)
│   ├── facade/             FileStorageFacade   ← NEW orchestration layer
│   ├── service/            Base64DecodingService
│   ├── dto/                 Base64SaveRequest/Response, DecodedFileSaveResponse, FileInfo
│   └── model/               DecodedFileResult
│
├── pdf/
│   ├── controller/       PdfFetchController (thin, direct-to-service — no facade, see rationale below),
│   │                      PdfDeliveryController (thin, via facade), PdfProtectionController (thin, via facade)
│   ├── facade/             PdfDeliveryFacade, PdfProtectionFacade   ← NEW orchestration layer
│   ├── service/            PdfService, PdfProtectionService, DynamicHttpService (@Deprecated, unused)
│   ├── mapper/              TargetApiRequestMapper   ← NEW
│   └── dto/                 PdfRequest/Response, PdfBase64Request(Dynamic), PdfProtectRequest/Response, TargetApiRequest
│
├── checksum/
│   ├── controller/       ChecksumController (thin, direct-to-service — no facade)
│   ├── service/            ChecksumService   ← NEW
│   └── dto/                 ChecksumRequest (promoted), ChecksumResponse   ← NEW
│
└── health/
    └── controller/       TestController
```

*(`jobstatus/` package and `AsyncJobRegistry` are intentionally absent from this Phase A diagram — they land in Phase B. See "Updated Package Structure" below for the Phase B addition.)*

---

## Deliverable 2: Updated Package Structure

### Phase A (approved, target of this execution cycle)

```
common/config/AppProperties.java                    (moved, unchanged content)
common/config/AsyncConfig.java                       (moved, unchanged content)
common/config/UnsafeRestTemplate.java                (moved, unchanged content, unchanged name)
common/util/LoggingInterceptor.java                  (moved, unchanged content)
common/util/FileNameSanitizer.java                   (NEW — extracted, verified textually identical
                                                        to the 3 duplicates it replaces)
common/util/FileSizeFormatter.java                   (NEW — extracted, verified textually identical
                                                        to the 2 duplicates it replaces)
common/validation/Base64FileValidator.java           (moved, unchanged content)
common/validation/UrlAllowlistValidator.java         (moved, unchanged content)
common/validation/PathTraversalGuard.java            (NEW — extracted, verified equivalent to the
                                                        5 duplicated inline checks it replaces)
common/service/Base64OutputWriter.java               (moved, unchanged content)
common/service/FileTypeDetectionService.java         (moved, unchanged content)
common/model/DetectionResult.java                    (promoted from FileTypeDetectionService inner class)
common/model/BinaryWriteResult.java                  (promoted from Base64OutputWriter inner class)

fileconversion/controller/FileConvertController.java (moved, unchanged content incl. /status/{id})
fileconversion/service/FileConversionService.java    (moved, unchanged content incl. @Scheduled cleanup)
fileconversion/config/FileCacheProperties.java       (moved, unchanged content and name)
fileconversion/dto/FileConvertRequest.java           (moved)
fileconversion/dto/FileConvertResponse.java          (moved)

filestorage/controller/DecodedFileController.java    (NEW — split from FileRetrievalController;
                                                        save-decoded, download-decoded, metadata)
filestorage/controller/Base64FileController.java     (NEW — split; list, download, content, delete)
filestorage/controller/CallbackController.java       (NEW — split; callback)
filestorage/facade/FileStorageFacade.java            (NEW — orchestration; see Deliverable 5)
filestorage/service/Base64DecodingService.java       (moved, unchanged content)
filestorage/dto/Base64SaveRequest.java Base64SaveResponse.java
filestorage/dto/DecodedFileSaveResponse.java FileInfo.java
filestorage/model/DecodedFileResult.java             (promoted from Base64DecodingService inner class)

pdf/controller/PdfFetchController.java               (NEW — split; convert/base64, convert/base64dynamic)
pdf/controller/PdfDeliveryController.java            (NEW — split; send, single)
pdf/controller/PdfProtectionController.java          (NEW — split; protect)
pdf/facade/PdfDeliveryFacade.java                    (NEW — orchestration; see Deliverable 5)
pdf/facade/PdfProtectionFacade.java                  (NEW — orchestration; see Deliverable 5)
pdf/service/PdfService.java                          (moved, unchanged content)
pdf/service/PdfProtectionService.java                (moved, unchanged content)
pdf/service/DynamicHttpService.java                  (moved, + @Deprecated + explanatory comment ONLY)
pdf/mapper/TargetApiRequestMapper.java               (NEW — extracted from PdfController private methods)
pdf/dto/PdfRequest.java PdfResponse.java PdfBase64Request.java PdfBase64RequestDynamic.java
pdf/dto/PdfProtectRequest.java PdfProtectResponse.java TargetApiRequest.java

checksum/controller/ChecksumController.java          (moved)
checksum/service/ChecksumService.java                (NEW — extracted CRC32 logic)
checksum/dto/ChecksumRequest.java                    (promoted from inline nested class)
checksum/dto/ChecksumResponse.java                   (NEW — replaces ad-hoc Map<String,String>)

health/controller/TestController.java                (moved, unchanged content)
```

### Phase B addition (Medium Risk — separate execution cycle, after Phase A is verified in production)

```
fileconversion/service/AsyncJobRegistry.java         (NEW — extracts the two ConcurrentHashMaps
                                                        currently private to FileConversionService)
fileconversion/scheduler/FileConversionCleanupScheduler.java  (NEW — extracts the @Scheduled method
                                                        and its 4 unrelated cleanup routines)
jobstatus/controller/JobStatusController.java        (NEW — /status/{id} moves out of
                                                        FileConvertController once AsyncJobRegistry exists)
```

### Phase C / Phase D — no new packages proposed; see Deliverable 4 for scope.

---

## Deliverable 3: Updated Dependency Diagram

```
                         ┌───────────────────────────────────────────┐
                         │                  common/                    │
                         │  config · util · validation · service ·      │
                         │  model                                       │
                         └───────────────▲─────────▲─────────▲──────────┘
                                          │         │         │
              ┌───────────────────────────┼─────────┼─────────┼───────────────────────────┐
              │                            │         │         │                            │
      ┌───────┴───────┐          ┌─────────┴───┐  ┌──┴──────┐  ┌──────┴──────┐      ┌────────┴────────┐
      │ fileconversion │          │ filestorage  │  │  pdf    │  │  checksum   │      │      health      │
      └───────┬───────┘          └───────┬──────┘  └───┬────┘  └──────┬──────┘      └────────┬────────┘
              │                            │             │              │                        │
   FileConvertController          Decoded/Base64File/    Pdf*Controller  ChecksumController   TestController
              │                    Callback Controller       │              │                        │
              ▼                            │                 ▼              ▼                        (no deps)
   FileConversionService                    ▼        ┌───────┴────────┐  ChecksumService
   (unchanged, incl.               FileStorageFacade  │                │  (no further deps)
    @Scheduled + async map)               │            ▼                ▼
                                           ▼     PdfDeliveryFacade  PdfProtectionFacade
                                  Base64DecodingService    │                │
                                                            ▼                ▼
                                                       PdfService     PdfProtectionService
                                                            │                │
                                                            ▼                ▼
                                                  TargetApiRequestMapper   (uses common/service,
                                                                             common/validation)

Facade-free paths (deliberately direct, no orchestration layer added):
  pdf.controller.PdfFetchController ──────────► pdf.service.PdfService              (single delegated call)
  checksum.controller.ChecksumController ─────► checksum.service.ChecksumService    (single delegated call)
  fileconversion.controller.FileConvertController ─► fileconversion.service.FileConversionService
                                                                                     (service already owns
                                                                                      the full multi-step
                                                                                      workflow internally)
  health.controller.TestController ───────────► (no service at all — static responses)

No business module (fileconversion, filestorage, pdf, checksum, health) depends on another business
module. All cross-module sharing flows through common/. This property is unchanged from v1 and is the
core guarantee that makes each module independently testable/reasoned-about.
```

---

## Deliverable 4: Revised Refactoring Plan — Phase Classification

### Phase A — Safe Refactoring (recommended for immediate execution)

1. Split `FileRetrievalController` → `DecodedFileController` + `Base64FileController` + `CallbackController`
2. Split `PdfController` → `PdfFetchController` + `PdfDeliveryController` + `PdfProtectionController`
3. Extract `ChecksumService` (+ `ChecksumRequest`/`ChecksumResponse` DTOs)
4. Extract `PathTraversalGuard`
5. Extract `FileNameSanitizer`
6. Extract `FileSizeFormatter`
7. Group all DTOs by module package
8. Move shared utilities/services into `common/` (`Base64OutputWriter`, `FileTypeDetectionService`, `Base64FileValidator`, `UrlAllowlistValidator`, `LoggingInterceptor`, `UnsafeRestTemplate`, `AsyncConfig`, `AppProperties`)
9. Promote inner result classes → top-level model classes (`DecodedFileResult`, `DetectionResult`, `BinaryWriteResult`)
10. Introduce `TargetApiRequestMapper`
11. Introduce `FileStorageFacade`, `PdfDeliveryFacade`, `PdfProtectionFacade` (facade layer — new in this revision)
12. Relocate `DynamicHttpService` to `pdf.service`, mark `@Deprecated`, add explanatory comment (no logic change)

### Phase B — Medium Risk (separate cycle, after Phase A is live and verified)

13. Extract `AsyncJobRegistry` from `FileConversionService`'s private maps
14. Extract `FileConversionCleanupScheduler` from `FileConversionService`'s `@Scheduled` method
15. Move `GET /api/files/status/{id}` into new `jobstatus.controller.JobStatusController`, backed by `AsyncJobRegistry`

### Phase C — Optional, Postponed Indefinitely

16. Split `AppProperties` into module-scoped `@ConfigurationProperties` classes — **not scheduled**; revisit only if a concrete pain point emerges (e.g., a module team wants config ownership independent of others). No work planned against this item.

### Phase D — Optional, Deferred

17. Introduce typed exceptions (`InvalidBase64Exception`, `PathTraversalException`, etc.), thrown from validators/services but still caught individually per controller (no global advice) — deferred, revisit after Phase B
18. Delete `DynamicHttpService` entirely — deferred until a verification window (see Deliverable 6) confirms zero invocations in any environment
19. Cosmetic renames (`FileCacheProperties`→`FileConversionProperties`, `UnsafeRestTemplate`→`RestTemplateConfig`) — deferred, zero functional value, bundle into a future cleanup pass if desired

---

## Deliverable 5: Per-Change Detail

Format: **Current State → Proposed State → Risk Level → Files Impacted → Why Behavior Remains Unchanged**

### Phase A items

---

**A1. Split `FileRetrievalController`**
- **Current State:** One class, 8 endpoints (`/save-decoded`, `/download-decoded/{fileName}`, `/metadata/{fileName}`, `/callback`, `/list`, `/download/{fileName}`, `/content/{fileName}`, `DELETE /{fileName}`), each with inline path-traversal checks and filesystem I/O.
- **Proposed State:** `DecodedFileController` (save-decoded, download-decoded, metadata), `Base64FileController` (list, download, content, delete), `CallbackController` (callback) — each delegating to `FileStorageFacade` (see A11) instead of doing inline I/O.
- **Risk Level:** Low.
- **Files Impacted:** New: `DecodedFileController.java`, `Base64FileController.java`, `CallbackController.java`. Deleted: `FileRetrievalController.java`.
- **Why Behavior Remains Unchanged:** Every `@GetMapping`/`@PostMapping`/`@DeleteMapping` path string is copied verbatim; three classes sharing the `/api/files` base path is a no-op for Spring's routing since no two methods share the same full path+verb. Response bodies/status codes for every branch (200/400/403/404/500) are preserved exactly.

---

**A2. Split `PdfController`**
- **Current State:** One class, 5 endpoints spanning fetch-and-return, fetch-and-relay-to-target, and password-protection concerns, plus 50+ lines of private mapping/header-building logic.
- **Proposed State:** `PdfFetchController` (`/convert/base64`, `/convert/base64dynamic` — direct to `PdfService`, no facade), `PdfDeliveryController` (`/send`, `/single` — via `PdfDeliveryFacade`), `PdfProtectionController` (`/protect` — via `PdfProtectionFacade`).
- **Risk Level:** Low.
- **Files Impacted:** New: `PdfFetchController.java`, `PdfDeliveryController.java`, `PdfProtectionController.java`, `PdfDeliveryFacade.java`, `PdfProtectionFacade.java`, `TargetApiRequestMapper.java`. Deleted: `PdfController.java`.
- **Why Behavior Remains Unchanged:** All 5 endpoint paths, request/response DTOs, and the existing (intentionally inconsistent) error-status conventions per endpoint (`/convert/base64` → 502/500 on failure; `/convert/base64dynamic` → always 200 with `FAILED` in the body) are preserved exactly — this refactor explicitly does **not** "fix" that inconsistency, per constraint #4.

---

**A3. Extract `ChecksumService`**
- **Current State:** CRC32 computation is a private controller method; request DTO is an inline static nested class; response is an ad-hoc `Map<String,String>` built in two places.
- **Proposed State:** `ChecksumService.compute(message, secretKey)` returns a typed `ChecksumResponse`; `ChecksumRequest` promoted to a top-level DTO.
- **Risk Level:** Low.
- **Files Impacted:** New: `ChecksumService.java`, `ChecksumRequest.java`, `ChecksumResponse.java`. Modified: `ChecksumController.java` (now calls the service; the 400 empty-field validation path is unchanged).
- **Why Behavior Remains Unchanged:** CRC32 algorithm, `message + "|" + secretKey"` concatenation, UTF-8 encoding, and `UUID.randomUUID()` nonce generation are copied verbatim. A `@Data` POJO with fields `checksum`/`nonce` serializes to the identical two-key JSON object Jackson already produces from `Map.of(...)`.

---

**A4. Extract `PathTraversalGuard`**
- **Current State:** `filePath.normalize().startsWith(Paths.get(outputPath).normalize())` duplicated 5 times across `FileRetrievalController`.
- **Proposed State:** One shared `PathTraversalGuard.isWithin(candidate, baseDir)` (or equivalent) called from all 5 sites in the new filestorage controllers/facade.
- **Risk Level:** Low.
- **Files Impacted:** New: `PathTraversalGuard.java`. Modified: the 5 call sites (now inside `FileStorageFacade`, see A11).
- **Why Behavior Remains Unchanged:** Identical `normalize()`/`startsWith()` logic; every existing `403 Forbidden` branch is preserved. Verified equivalent by comparing all 5 current inline implementations before extraction (confirmed textually identical).

---

**A5. Extract `FileNameSanitizer`**
- **Current State:** `[^a-zA-Z0-9._-]` sanitization regex duplicated in `FileRetrievalController`, `Base64DecodingService`, `Base64OutputWriter`.
- **Proposed State:** One shared `FileNameSanitizer.sanitize(name, fallback)`.
- **Risk Level:** Low.
- **Files Impacted:** New: `FileNameSanitizer.java`. Modified: `Base64DecodingService.java`, `Base64OutputWriter.java`, `FileStorageFacade.java`.
- **Why Behavior Remains Unchanged:** Confirmed textually identical across all 3 current duplicates before extraction; same regex, same fallback behavior (`"file"` when sanitized result is blank).

---

**A6. Extract `FileSizeFormatter`**
- **Current State:** `formatFileSize(long bytes)` duplicated in `FileRetrievalController` and `Base64DecodingService`.
- **Proposed State:** One shared `FileSizeFormatter.format(bytes)`.
- **Risk Level:** Low.
- **Files Impacted:** New: `FileSizeFormatter.java`. Modified: `Base64DecodingService.java`, `Base64OutputWriter.java`, `FileStorageFacade.java`.
- **Why Behavior Remains Unchanged:** Confirmed textually identical algorithm (same unit thresholds, same `%.2f` formatting) across both current duplicates.

---

**A7. Group DTOs by module**
- **Current State:** 13 DTOs in one flat `dto` package spanning 4 business domains.
- **Proposed State:** DTOs moved into `fileconversion/dto`, `filestorage/dto`, `pdf/dto`, `checksum/dto`.
- **Risk Level:** Low.
- **Files Impacted:** All 13 DTO files (package declaration + import statements in every consuming class).
- **Why Behavior Remains Unchanged:** Jackson serialization is driven by field names, `@JsonProperty`, `@JsonAnySetter`, and Lombok-generated accessors — none of which reference the Java package. Bean Validation annotations (`@NotBlank`, `@Valid`) likewise travel with the class. Moving a `.java` file's package never changes its JSON wire format.

---

**A8. Move shared utilities/services into `common/`**
- **Current State:** `Base64OutputWriter`, `FileTypeDetectionService`, `Base64FileValidator`, `UrlAllowlistValidator`, `LoggingInterceptor`, `UnsafeRestTemplate`, `AsyncConfig`, `AppProperties` live in the flat `util`/`config` packages.
- **Proposed State:** Relocated into `common/service`, `common/validation`, `common/util`, `common/config` respectively — **file content unchanged, package declaration and consumers' imports updated only.** `AppProperties` is moved as a single whole class (Phase C split is explicitly not part of this item).
- **Risk Level:** Low.
- **Files Impacted:** The 8 moved files, plus every class that imports them (≈15 files' import statements).
- **Why Behavior Remains Unchanged:** `@ConfigurationProperties(prefix = "app")` binding is driven by the prefix string, not the class's package — `application.properties` needs zero edits (constraint #6 satisfied by construction). `@Configuration`/`@Bean`/`@Component`/`@Service` resolution is by type, not package. RestTemplate interceptor wiring (`LoggingInterceptor`) is unaffected by its package.

---

**A9. Promote inner result classes → top-level models**
- **Current State:** `Base64DecodingService.DecodedFileResult`, `FileTypeDetectionService.DetectionResult`, `Base64OutputWriter.BinaryWriteResult` are public static nested classes.
- **Proposed State:** Top-level classes in `filestorage/model` and `common/model`, same fields, same builder API (Lombok `@Builder` where not already used).
- **Risk Level:** Low.
- **Files Impacted:** New: `DecodedFileResult.java`, `DetectionResult.java`, `BinaryWriteResult.java`. Modified: their 3 producing services (import + qualified-name update only).
- **Why Behavior Remains Unchanged:** These are internal data carriers never directly serialized as an HTTP response (every controller builds its own response DTO from these models) — relocating them cannot affect any JSON contract.

---

**A10. Introduce `TargetApiRequestMapper`**
- **Current State:** `PdfController.buildTargetApiRequest()`/`buildTargetHeaders()` — 50+ lines of private controller logic.
- **Proposed State:** `TargetApiRequestMapper.toTargetApiRequest(PdfRequest)` / `.buildTargetHeaders(PdfRequest, defaultToken)`, injected into `PdfDeliveryFacade`.
- **Risk Level:** Low.
- **Files Impacted:** New: `TargetApiRequestMapper.java`. Modified: logic removed from `PdfController`/relocated into `PdfDeliveryFacade`.
- **Why Behavior Remains Unchanged:** Field-by-field copy logic (including the existing quirk where `TargetApiRequest.Reference` is always built empty regardless of source) is copied verbatim — that quirk is preserved, not "fixed," per constraint #4.

---

**A11. Introduce the Facade Layer**

This is new in this revision. Three facades are added; three call sites are **deliberately left facade-free** with rationale given.

**A11a. `FileStorageFacade`** (`filestorage.facade`)
- **Current State:** `FileRetrievalController`'s 8 methods each inline their own path-traversal check, `Files.*` I/O calls, filename sanitization, and file-size formatting directly in the controller. `Base64DecodingService.decodeAndSaveFile()` is the only method with a pre-existing service seam; the other 7 endpoints have no service layer at all today.
- **Proposed State:** `FileStorageFacade` centralizes all of it: `decodeAndSave(request)` (delegates to `Base64DecodingService`, unchanged), `readDecodedFile(fileName)`, `readMetadata(fileName)`, `saveRawBase64(request)` (absorbs the current inline `/callback` logic), `listBase64Files()`, `readBase64File(fileName)`, `deleteBase64File(fileName)` — each internally using `PathTraversalGuard`, `FileNameSanitizer`, `FileSizeFormatter`. The three new controllers (A1) become thin: decode `@RequestBody`/`@PathVariable` → call one facade method → map the result to a `ResponseEntity` with the exact status/headers used today.
- **Risk Level:** Low. This is the highest-value facade (it removes real duplication, not just adds a layer), but every operation it performs is a verbatim relocation of existing controller code, not a rewrite.
- **Files Impacted:** New: `FileStorageFacade.java`. Modified: the 3 controllers from A1 (bodies simplified to delegate).
- **Why Behavior Remains Unchanged:** The facade's methods contain the *exact same* guard checks, I/O calls, and error conditions the controller methods contain today — only the caller (controller vs. facade) changes. HTTP status-code decisions (403/404/500) remain in the controller's response-mapping step, unchanged from today, so error-handling behavior (postponed Global Exception Framework notwithstanding) is untouched.

**A11b. `PdfDeliveryFacade`** (`pdf.facade`)
- **Current State:** `PdfController.convertFileAndSendToTarget()`/`convertSingleFile()` each inline: fetch+encode via `PdfService`, attachment injection, `TargetApiRequest` building, header building, `RestTemplate` POST, response building, and a `finally` block clearing `attachmentData`.
- **Proposed State:** `PdfDeliveryFacade.deliver(PdfRequest)` performs the full orchestration (fetch → map → forward → build `PdfResponse`), including the `finally`-block attachment-clearing side effect. `PdfDeliveryController`'s two methods become thin: call the facade, catch `RestClientException`/`Exception` exactly as today and map to the same status codes.
- **Risk Level:** Low.
- **Files Impacted:** New: `PdfDeliveryFacade.java`. Modified: `PdfDeliveryController.java` (bodies simplified).
- **Why Behavior Remains Unchanged:** The facade re-throws the same exception types (`RestClientException`, generic `Exception`) it encounters today — it does not catch-and-translate them into something new, so the controller's existing catch blocks and status-code mapping (502 for `RestClientException`, 500 otherwise) work unmodified. The `finally`-block memory-hygiene behavior (clearing `attachmentData`) is preserved inside the facade.

**A11c. `PdfProtectionFacade`** (`pdf.facade`)
- **Current State:** `PdfController.protectPdf()` inlines: Base64 validation (`Base64FileValidator`), decode, password derivation (`PdfProtectionService.buildPassword`), protection (`PdfProtectionService.protect`), persistence (`Base64OutputWriter.writeBinaryWithMetadata`), and response building — roughly 50 lines of orchestration added in the previous session.
- **Proposed State:** `PdfProtectionFacade.protect(PdfProtectRequest)` performs the same sequence and returns a result the controller maps into `PdfProtectResponse`. `PdfProtectionController.protectPdf()` becomes a thin try/catch → facade call → response mapping.
- **Risk Level:** Low.
- **Files Impacted:** New: `PdfProtectionFacade.java`. Modified: `PdfProtectionController.java`.
- **Why Behavior Remains Unchanged:** Same exception types propagate unchanged (`IllegalArgumentException` for invalid Base64/not-a-PDF, `IllegalStateException` for already-protected source, `IOException`/generic `Exception` otherwise), so the controller's existing 400/500 mapping is untouched. The metadata sidecar's deliberate exclusion of `dob`/password (a security property established in the prior session) is preserved inside the facade.

**A11d. Deliberately facade-free: `PdfFetchController`, `FileConvertController`, `ChecksumController`, health/job-status reads**
- **Rationale (applies to all four):** Each of these either (a) delegates to exactly one service method with no additional orchestration in the controller (`PdfFetchController` → `PdfService.fetchAndConvertToBase64[Dynamic]`; `ChecksumController` → `ChecksumService.compute`), or (b) the "orchestration" already fully lives inside the service itself, not the controller (`FileConversionService.handleFileProcessing` already does validate→download→encode→persist→audit internally; `FileConvertController` just calls `processFile()` per item), or (c) has no service at all because there's nothing to orchestrate (`TestController`). Introducing a facade in any of these cases would be a pure pass-through wrapper adding a class with no behavior of its own — explicitly out of scope per "DO NOT introduce facades where they add no value."

---

**A12. `DynamicHttpService` — Phase A step (deprecate only, do not delete)**
- **Current State:** Zero callers anywhere in the codebase (re-confirmed); lives in the flat `service` package.
- **Proposed State:** Relocated to `pdf.service` (its natural domain), annotated `@Deprecated(since = "<this refactor's version>", forRemoval = true)`, with a class-level comment stating it has no known callers as of this refactor and is scheduled for removal in Phase D pending a verification window.
- **Risk Level:** None — purely additive (an annotation and a comment cannot change runtime behavior).
- **Files Impacted:** `DynamicHttpService.java` only.
- **Why Behavior Remains Unchanged:** `@Deprecated` has zero runtime effect (it is not `@Deprecated(forRemoval = true)` acted upon by anything at runtime — Spring does not refuse to register a deprecated `@Service` bean). The class continues to compile and, if it were ever called, continues to behave identically.

---

### Phase B items (Medium Risk — separate cycle)

**B1. Extract `AsyncJobRegistry`**
- **Current State:** `FileConversionService` privately owns two `ConcurrentHashMap`s (`asyncResults`, `asyncResultTimes`) used by `processFileAsync` (writer, currently never invoked by any controller) and `getAsyncResult` (reader, called by `/status/{id}`).
- **Proposed State:** `AsyncJobRegistry` wraps both maps with `put`/`get`/`entries`/`remove`; `FileConversionService`, the future `JobStatusController`, and the future cleanup scheduler all depend on it instead of on `FileConversionService`'s private state.
- **Risk Level:** Medium (touches 3 call sites; must preserve exact map identity/semantics during the transition — see v1 plan §3.14 for full detail).
- **Files Impacted:** New: `AsyncJobRegistry.java`. Modified: `FileConversionService.java`.
- **Why Behavior Remains Unchanged:** Same `ConcurrentHashMap` semantics, same retention math, wrapped rather than rewritten. Since no controller currently triggers `processFileAsync` in production, there is no realistic scenario where an in-flight async job's visibility changes during this extraction.

**B2. Extract `FileConversionCleanupScheduler`**
- **Current State:** One `@Scheduled(cron = "0 0 * * * *")` method in `FileConversionService` fans out to 4 unrelated cleanup routines (temp cache, audit-log rotation/archival, async-result GC, delegated `.b64` cleanup).
- **Proposed State:** `FileConversionCleanupScheduler` owns the `@Scheduled` method and all 4 routines, depending on `FileConversionService`'s retained audit/cache-path config, `AsyncJobRegistry` (B1), and `Base64OutputWriter`.
- **Risk Level:** Medium (must confirm the cron trigger fires exactly once, from exactly one bean, after extraction — a duplicate `@Scheduled` bean would double-run cleanup).
- **Files Impacted:** New: `FileConversionCleanupScheduler.java`. Modified: `FileConversionService.java` (method removed).
- **Why Behavior Remains Unchanged:** Cron expression, retention thresholds, and file-system side effects are copied verbatim; the only change is which class the `@Scheduled` annotation lives on.

**B3. `JobStatusController` + move `/status/{id}`**
- **Current State:** `GET /api/files/status/{processingId}` lives in `FileConvertController` alongside `/convert` and `/convert/single`.
- **Proposed State:** Moved to `jobstatus.controller.JobStatusController`, reading from `AsyncJobRegistry` (B1).
- **Risk Level:** Low-Medium (depends on B1 being correct first).
- **Files Impacted:** New: `JobStatusController.java`. Modified: `FileConvertController.java` (method removed).
- **Why Behavior Remains Unchanged:** Path, method, response DTO, and 404-on-miss semantics copied verbatim; since this endpoint has no live production traffic pattern that depends on `processFileAsync` (which nothing currently triggers), this is the lowest-risk item in Phase B despite touching shared state.

---

### Phase C item (Postponed Indefinitely)

**C1. `AppProperties` split** — not scheduled. See Summary table for rationale. No files impacted; no risk incurred because no work is planned.

### Phase D items (Deferred)

**D1. Typed exceptions** — deferred; would touch every validator/service that currently throws generic `IllegalArgumentException`/`IllegalStateException`/`IOException`, plus every controller catch block (must remain narrowed to preserve exact existing status/body per branch). Medium-High risk if attempted without full regression coverage in place first.

**D2. `DynamicHttpService` deletion** — deferred until the verification window (Deliverable 6) confirms zero invocations across all environments following the Phase A deprecation (A12).

**D3. Cosmetic renames** — deferred; zero functional value, bundle into a future pass if desired.

---

## Deliverable 6: Implementation Roadmap

Intended audience: a development team executing this in a real sprint, with build/verify gates between each step so a regression is always isolated to a small, revertible diff.

| Step | Work | Gate before proceeding |
|---|---|---|
| 0 | Capture baseline: for all 19 endpoints, record exact request/response pairs (body, status, headers) + a copy of the Postman collection run results. This baseline is the regression oracle for every subsequent step. | Baseline captured and committed to `docs/` or test fixtures. |
| 1 | Create the Phase A package skeleton (empty packages) at `com.company.application.*`. | `./mvnw clean package -DskipTests` succeeds with zero files moved. |
| 2 | Move dependency-free items first: `common/validation/{Base64FileValidator, UrlAllowlistValidator}`, `common/util/LoggingInterceptor`, `common/config/{UnsafeRestTemplate, AsyncConfig, AppProperties}` (A8, partial). | Build succeeds; `application.properties` untouched; app boots locally. |
| 3 | Extract A4/A5/A6 (`PathTraversalGuard`, `FileNameSanitizer`, `FileSizeFormatter`) into `common/`, unit-tested in isolation (Deliverable in v1 doc's Phase 5 unit-test table still applies) — do not switch callers yet. | New unit tests pass; build succeeds. |
| 4 | Move `common/service/{Base64OutputWriter, FileTypeDetectionService}` + promote `common/model/{DetectionResult, BinaryWriteResult}` (A8/A9, remainder). | Build succeeds. |
| 5 | Build `FileStorageFacade` (A11a) using the Step 3 utilities; build `DecodedFileController`/`Base64FileController`/`CallbackController` (A1) against it; delete `FileRetrievalController`. | Full endpoint regression (Step 0 baseline) passes for all 8 filestorage endpoints. |
| 6 | Build `TargetApiRequestMapper` (A10), `PdfDeliveryFacade` (A11b), `PdfProtectionFacade` (A11c); build `PdfFetchController`/`PdfDeliveryController`/`PdfProtectionController` (A2); delete `PdfController`. | Full endpoint regression passes for all 5 pdf endpoints. |
| 7 | Extract `ChecksumService` + DTOs (A3). | Regression passes for both checksum endpoints. |
| 8 | Complete DTO regrouping (A7) and remaining service/controller moves (`Base64DecodingService`, `FileConversionService`, `FileCacheProperties`, `FileConvertController`, `TestController`). | Full build + full 19-endpoint regression + Postman collection re-run, unmodified, 100% pass. |
| 9 | Relocate + deprecate `DynamicHttpService` (A12). | Build succeeds (annotation-only change). |
| 10 | **Phase A sign-off.** Deploy to staging, run the full regression suite + Postman collection against staging, monitor for one full cleanup-cycle (≥1 hour, to observe the still-unmoved `@Scheduled` job run correctly from its original class). | Staging soak passes. |
| 11 | **Phase A production release.** | Production monitoring: error rates, `/actuator/prometheus` metric names (`base64.conversions`, `pdf.conversions`, `pdf.protect`, `base64.output.files.*`) unchanged, no new 4xx/5xx patterns. |
| 12 | *(Separate cycle)* Execute Phase B (B1–B3) with its own baseline/build/regression gates, since `AsyncJobRegistry`/scheduler extraction touches shared mutable state. | Same gate structure as Steps 0–11, scoped to fileconversion/jobstatus. |
| 13 | *(Later, optional)* Begin the `DynamicHttpService` verification window for D2 — e.g., confirm via logs/APM across one full release cycle that the deprecated class is never invoked, then delete. | Zero invocation evidence over the agreed window. |
| 14 | *(Later, optional, requires separate approval)* Consider Phase D1 (typed exceptions) endpoint-by-endpoint, each with its own before/after response diff, only after Phase B has been stable in production for a full cycle. | Not scheduled by default. |

---

## Deliverable 7: Go / No-Go Recommendation Table

| Change | Go now? | Justification |
|---|---|---|
| Split `FileRetrievalController` (A1) | **GO** | Pure move/split, Low risk, immediately improves the single worst SRP violation in the codebase. |
| Split `PdfController` (A2) | **GO** | Pure move/split, Low risk. |
| Extract `ChecksumService` (A3) | **GO** | Low risk, small surface, clear win. |
| Extract `PathTraversalGuard` (A4) | **GO** | Low risk, removes a 5x-duplicated security check into one testable place — arguably a security-posture improvement, not just cleanliness. |
| Extract `FileNameSanitizer` (A5) | **GO** | Low risk, removes 3x duplication. |
| Extract `FileSizeFormatter` (A6) | **GO** | Low risk, removes 2x duplication. |
| Group DTOs by module (A7) | **GO** | Low risk, mechanical. |
| Move shared utilities to `common/` (A8) | **GO** | Low risk; `AppProperties` moved whole (not split) per your explicit instruction. |
| Promote inner classes to models (A9) | **GO** | Low risk, no wire-format exposure. |
| Introduce `TargetApiRequestMapper` (A10) | **GO** | Low risk, removes embedded mapping logic from a controller. |
| Introduce Facade layer (A11a–c) | **GO** | Low risk *when scoped as specified* (3 facades, not applied everywhere) — directly serves "keep controllers thin" without adding value-free wrapper classes. |
| Deprecate `DynamicHttpService` (A12) | **GO** | Zero risk, additive only. |
| Extract `AsyncJobRegistry` (B1) | **CONDITIONAL GO** | Medium risk; approve for a *separate* execution cycle after Phase A is stable in production, not bundled into the same release. |
| Extract `FileConversionCleanupScheduler` (B2) | **CONDITIONAL GO** | Medium risk (duplicate-`@Scheduled` hazard); same conditional as B1 — verify only one bean carries the cron trigger post-extraction. |
| `JobStatusController` extraction (B3) | **CONDITIONAL GO** | Depends on B1; low incremental risk once B1 is correct. |
| `AppProperties` split (C1) | **NO-GO** | Explicitly postponed per your review decision — high touch, low business value, no work planned. |
| Global exception framework | **NO-GO, permanently** | Structurally risks changing response contracts across endpoints that intentionally differ today; not recommended at any point, not just deferred. |
| Typed exceptions without global advice (D1) | **NO-GO for now** | Legitimate future improvement, but only after Phase B is stable and with full regression coverage in place first; not scheduled in this roadmap. |
| Delete `DynamicHttpService` (D2) | **NO-GO for now** | Deprecate now (A12), delete only after the verification window in Roadmap Step 13. |
| Cosmetic renames (D3) | **NO-GO** | Zero functional value; skip unless bundled into an unrelated future cleanup pass. |

---

## Final Compliance Statement

Every item classified **GO** in this document satisfies all 10 constraints by construction:
1–2–3 (endpoint URL / request / response unchanged): every Phase A change is a verbatim relocation or extraction of existing code; no `@*Mapping` path, DTO field, or response-building branch is edited.
4 (business logic unchanged): every extracted method's algorithm is copied, not rewritten, including deliberately-preserved quirks (e.g., `TargetApiRequest.Reference` built empty).
5 (file storage locations unchanged): no `app.base64.output-path`, `file.cache.path`, or file-naming convention is touched by any Phase A or Phase B item.
6 (property names unchanged): `AppProperties` moves as one class with unchanged `@ConfigurationProperties` prefixes; `application.properties` requires zero edits for Phase A.
7–8–9–10 (no breaking changes / Postman compatibility / integration compatibility / zero regression): guaranteed by 1–6 above, and enforced procedurally by the baseline-capture-and-diff gate at every roadmap step (Deliverable 6).
