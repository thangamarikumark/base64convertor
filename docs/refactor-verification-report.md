# Phase A Refactor — Verification Report

**Root package:** unchanged — `com.twixor.base64convertor` (per ADR-005). All new modules created underneath it.
**Build tool:** `./mvnw clean package` — run after all Phase A changes were applied (49 source files compiled).
**Regression method:** Phase 0 baseline captured live request/response/headers/status for 20 endpoint scenarios (`docs/regression-baseline/`) before any code change; identical requests re-issued post-refactor (`docs/regression-postrefactor/`) and diffed programmatically, ignoring only fields that are inherently non-deterministic between any two runs (timestamps, generated filenames/UUIDs, nonces, and PDFBox's randomized owner-password/document-ID bytes). The existing Postman collection was also run unmodified via Newman against the refactored build.

## Overall Result

| Check | Result |
|---|---|
| `mvn clean package` | **BUILD SUCCESS** (49 source files, 0 errors) |
| Endpoint URL unchanged | **PASS** — every `@*Mapping` path string copied verbatim (verified by re-running all 20 baseline scenarios against the same paths) |
| Request payload unchanged | **PASS** — same request DTOs/fields drove every capture |
| Response payload unchanged | **PASS** — 20/20 endpoint scenarios byte-for-byte equivalent after excluding non-deterministic fields (see `docs/regression-baseline/` vs `docs/regression-postrefactor/`) |
| Status codes unchanged | **PASS** — 20/20 identical, including both real error cases (`pdf-convert-base64` and `pdf-single` both reproduced their original `500` from the same underlying 501-from-source-server condition) |
| Headers unchanged | **PASS** — `Content-Type`/`Content-Disposition` identical on all binary/text-download endpoints |
| File storage path unchanged | **PASS** — no `app.base64.output-path`/`file.cache.path` value or file-naming convention touched; only Java package declarations changed |
| Configuration keys unchanged | **PASS** — `application.properties` was not edited at all during Phase A |
| Postman collection still passes | **PASS with 2 pre-existing, unrelated caveats** — see below |

### Postman/Newman run caveats (not regressions)
1. Six `Actuator & Monitoring` requests failed with `ECONNREFUSED` because the verification instance was started with `--management.server.port=9099` (to avoid a port clash with another process on this shared host) instead of the collection's hardcoded `9090`. Manually confirmed actuator works identically post-refactor: `GET /actuator/health` → `200`, and `GET /actuator/metrics/pdf.protect` returns the correctly-named/tagged metric — proving Micrometer metric names (`base64.conversions`, `pdf.conversions`, `pdf.protect`, `base64.output.files.*`) survived the package moves unchanged, as expected (metric names are string literals, not derived from Java package).
2. The collection's "Save Base64 File" request targets `POST /api/files/save`, which has never existed as a mapped endpoint in this codebase (confirmed absent both before and after this refactor — the real endpoints are `/save-decoded` and `/callback`) — pre-existing collection drift unrelated to Phase A.

---

## Per-Step Verification

| Step | Files Changed | Risk Level | Regression Result | Pass/Fail |
|---|---|---|---|---|
| Create `common/` skeleton + move dependency-free classes (`Base64FileValidator`, `UrlAllowlistValidator`, `LoggingInterceptor`, `UnsafeRestTemplate`, `AsyncConfig`, `AppProperties`, `Base64OutputWriter`, `FileTypeDetectionService`) | 8 files moved + package/import updates across all referencing files | Low | Compiles cleanly after one import fix (`UnsafeRestTemplate` needed an explicit `LoggingInterceptor` import once the two left the same package) | **PASS** |
| Group DTOs by module (`fileconversion/dto`, `filestorage/dto`, `pdf/dto`) | 13 DTO files moved | Low | Compiles cleanly; no field/annotation changes | **PASS** |
| Promote inner result classes (A9): `DetectionResult` → `common/model`, `BinaryWriteResult` → `common/model`, `DecodedFileResult` → `filestorage/model` | 3 new model files; `FileTypeDetectionService`, `Base64OutputWriter`, `Base64DecodingService` updated to reference them | Low | Same fields/builder API preserved (Lombok `@Builder` reproduces the original hand-rolled builder's behavior) | **PASS** |
| Extract `PathTraversalGuard`, `FileNameSanitizer`, `FileSizeFormatter` (A4/A5/A6) | 3 new `common/` classes | Low | Logic copied verbatim from the 5/3/2 duplicated originals respectively | **PASS** |
| Split `FileRetrievalController` → `DecodedFileController` + `Base64FileController` + `CallbackController`, introduce `FileStorageFacade` (A1, A11a) | 3 new controllers + 1 facade created; old `FileRetrievalController` deleted; `Base64DecodingService` moved to `filestorage.service` | Low | All 8 filestorage endpoint scenarios (`files-save-decoded`, `files-download-decoded`, `files-metadata`, `files-callback`, `files-list`, `files-download`, `files-content`, `files-delete`) byte-for-byte equivalent to baseline, including the exact 403/404/500 semantics per branch (verified by re-deriving the guard/extension check ordering per endpoint before extraction) | **PASS** |
| Split `PdfController` → `PdfFetchController` + `PdfDeliveryController` + `PdfProtectionController`, introduce `PdfDeliveryFacade` + `PdfProtectionFacade` + `TargetApiRequestMapper` (A2, A10, A11b, A11c) | 3 new controllers + 2 facades + 1 mapper created; old `PdfController` deleted; `PdfService`/`PdfProtectionService` moved to `pdf.service` | Low-Medium | All 5 PDF endpoint scenarios equivalent to baseline, **including two exact-message edge cases caught and fixed during this step** (see below) | **PASS** |
| Extract `ChecksumService`, promote `ChecksumRequest` (A3) | 1 new service, 1 new DTO, `ChecksumController` moved to `checksum` module | Low | Both checksum scenarios byte-for-byte equivalent (kept `Map<String,String>` return type rather than a new POJO, to guarantee identical serialization) | **PASS** |
| Move `TestController` → `health.controller` | 1 file moved | None | `test-ping`/`test-echo` scenarios equivalent | **PASS** |
| Deprecate `DynamicHttpService` (A12) | 1 file moved to `pdf.service` + `@Deprecated(forRemoval = true)` + comment added | None | No callers exist; annotation-only change; compiles cleanly | **PASS** |

### Two behavior-preservation issues caught during self-review (fixed before final build)

These are called out explicitly because they demonstrate *why* the per-step build+regression discipline matters — both would have been silent regressions if shipped:

1. **`PdfDeliveryFacade` partial-failure fields.** The original `/send` and `/single` controller code set local `fileName`/`mimeType` variables from the request's attachment *before* attempting the forward POST, so a `FAILED` response after a successful fetch-but-failed-forward still carried the real `fileName`/`mimeType` — not `"unknown"`/`null`. A naive facade extraction that only returns data on success would have silently dropped this. Fixed by having the facade throw a `PdfDeliveryException` carrying the partial `fileName`/`mimeType` computed up to the point of failure, which the controller reads in its catch block — reproducing the original's exact per-branch values.
2. **`PdfProtectionFacade` error-message text.** The original controller's "not a valid PDF" branch returned the message `"Provided content is not a valid PDF"` directly (no prefix), while a *different* branch (malformed Base64 itself) returned `"Invalid Base64 content: " + e.getMessage()`. An initial extraction accidentally routed both through the same `IllegalArgumentException` path, which would have added the `"Invalid Base64 content: "` prefix to the first message — a real, user-visible response text change. Fixed by introducing a distinct `PdfProtectionFacade.NotAPdfException`, caught separately in the controller before the generic `IllegalArgumentException` handler, exactly reproducing both original messages.

Both issues were caught by comparing the new code against the original source line-by-line before running the regression suite, and confirmed fixed by the full baseline diff passing 20/20 afterward.

---

## Artifacts

- `docs/regression-baseline/` — pre-refactor request/response/headers/status per endpoint, `endpoints.md`, `api-inventory.md`, `openapi.json`
- `docs/regression-postrefactor/` — identical captures taken after Phase A, used for the diff in this report
- `postman/baseline_collection.json`, `postman/baseline_environment.json`
- `docs/adr/ADR-001` through `ADR-005`
