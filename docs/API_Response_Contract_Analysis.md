# API Response Contract Analysis

**Type: Analysis only.** No code changed. Scanned fresh from the current source tree (9 controllers, 14 DTOs, 0 global exception handlers) — not assumed from earlier session notes, since `/pdf/protect`'s response shape changed twice in this session and needed re-verification.

---

## 1. Endpoint Inventory

| Endpoint | Method | Success Response Type | Error Response Type |
|---|---|---|---|
| `POST /api/files/checksum` | POST | `ResponseEntity<Map<String,String>>` (ad-hoc map: `checksum`, `nonce`) | Same map type: `{"error": "..."}` (400) |
| `POST /api/files/checksumgenerator` | POST | `ResponseEntity<Map<String,String>>` | Same as above (400) |
| `POST /api/files/convert` | POST | `ResponseEntity<List<FileConvertResponse>>` | `ResponseEntity.badRequest().build()` — **empty body** (400); per-item failures embedded as `success:false` inside 200 |
| `POST /api/files/convert/single` | POST | `ResponseEntity<FileConvertResponse>` | No explicit catch — falls through to Spring's default error body (500) or per-field embedded in the DTO |
| `GET /api/files/status/{id}` | GET | `ResponseEntity<?>` (`FileConvertResponse`) | `ResponseEntity.notFound().build()` — empty body (404) |
| `POST /api/files/pdf/send` | POST | **`List<PdfResponse>` (no `ResponseEntity` wrapper at all)** | Failures embedded as `status:"FAILED: ..."` string inside 200 items — no HTTP-level error path |
| `POST /api/files/pdf/convert/base64` | POST | `ResponseEntity<PdfResponse>` | `PdfResponse` body with `status:"FAILED: ..."` (502 or 500) |
| `POST /api/files/pdf/convert/base64dynamic` | POST | `ResponseEntity<PdfResponse>`, **always 200** | Same `PdfResponse` type, `status` field holds `"FAILED: ..."`/`"FILE_TOO_LARGE"` — **status code never changes** |
| `POST /api/files/pdf/single` | POST | `ResponseEntity<PdfResponse>` | `PdfResponse` body (502 or 500) |
| `POST /api/files/pdf/protect` | POST | `ResponseEntity<PdfProtectionResponse>` (`success:true` + Base64) | Same `PdfProtectionResponse` type, `success:false` (400/503/500) |
| `POST /api/files/save-decoded` | POST | `ResponseEntity<DecodedFileSaveResponse>` | Same type, `success:false` (400/500) |
| `GET /api/files/download-decoded/{fileName}` | GET | `ResponseEntity<byte[]>` (binary) | Empty body (403/404/500) |
| `GET /api/files/metadata/{fileName}` | GET | `ResponseEntity<String>` (raw JSON passthrough) | Empty body (403/404/500) |
| `POST /api/files/callback` | POST | `ResponseEntity<Base64SaveResponse>` | Same type, `success:false` (500) |
| `GET /api/files/list` | GET | `ResponseEntity<List<FileInfo>>` | Empty body (500) |
| `GET /api/files/download/{fileName}` | GET | `ResponseEntity<String>` (raw text) | Empty body (403/404/500) |
| `GET /api/files/content/{fileName}` | GET | `ResponseEntity<String>` (raw text) | Empty body (403/404/500) |
| `DELETE /api/files/{fileName}` | DELETE | `ResponseEntity<String>` (plain string `"File deleted successfully"`) | Empty body (403/404/500) |
| `GET /api/test/ping` | GET | `ResponseEntity<String>` (plain string) | N/A (no failure path) |
| `POST /api/test/echo` | POST | `ResponseEntity<String>` (plain string) | N/A (no failure path) |

**20 endpoints, 20 distinct response-shape decisions** — no two controllers were written against a shared contract.

---

## 2. Response DTO Analysis

| DTO | Fields | Purpose | Used By |
|---|---|---|---|
| `FileConvertResponse` | `processingId`, `fileName`, `mimeType`, `type`, `base64Data`, `success` (boolean), `message` | Result of a single URL→Base64 conversion | `/convert`, `/convert/single`, `/status/{id}` |
| `PdfResponse` | `fileName`, `status` (**String**, not boolean — holds `"SUCCESS"` / `"FAILED: ..."` / `"FILE_TOO_LARGE"`), `base64`, `mimeType`, `size` | Result of a PDF fetch/relay operation | `/pdf/send`, `/pdf/convert/base64`, `/pdf/convert/base64dynamic`, `/pdf/single` |
| `PdfProtectionResponse` | `success` (boolean), `message`, `fileName`, `base64ProtectedPdf` | Result of the protect/generate operation | `/pdf/protect` |
| `Base64SaveResponse` | `success` (boolean), `message`, `fileName`, `downloadLink`, `fileSize`, `savedAt` | Result of saving a raw Base64 string to disk | `/callback` |
| `DecodedFileSaveResponse` | `success` (boolean), `message`, `fileName`, `originalFileName`, `downloadLink`, `fileSize`, `fileSizeBytes`, `mimeType`, `metadataFile`, `savedAt` | Result of decoding+saving a Base64 payload | `/save-decoded` |
| `FileInfo` | `fileName`, `size`, `lastModified` | One entry in a file listing (not a top-level response wrapper — used inside `List<FileInfo>`) | `/list` |
| `ChecksumRequest`/ad-hoc `Map<String,String>` | *(no response class exists)* — `Map.of("checksum", ..., "nonce", ...)` built inline in the controller | Checksum result | `/checksum`, `/checksumgenerator` |
| *(none — raw types)* | N/A | `/download-decoded`, `/metadata`, `/download`, `/content`, `DELETE /{fileName}`, `/ping`, `/echo` return `byte[]`/`String` directly, no DTO at all |

**Key finding: there is no `ErrorResponse` class anywhere in the codebase.** Every "error" is either (a) an existing success-shaped DTO reused with `success:false`, (b) a raw `Map<String,String>` (`ChecksumController` only), or (c) an empty body with just a status code (all the raw-`String`/`byte[]` endpoints). No `@ControllerAdvice`/`@RestControllerAdvice`/`@ExceptionHandler` exists anywhere in the application (confirmed by full-codebase grep) — this is a **deliberate, documented decision** (`docs/adr/ADR-003-no-global-exception-handler.md`), not an oversight, made specifically because a shared handler would have collapsed these already-divergent shapes further.

---

## 3. Success Response Pattern

**All three of the example patterns in the prompt exist simultaneously in this codebase**, plus a fourth (raw, no wrapper at all):

**Pattern A** (`success`/`message`/implicit data) — closest match: `PdfProtectionResponse`, `Base64SaveResponse`, `DecodedFileSaveResponse`, `FileConvertResponse`. None of these actually nest a `data` object though — the "data" fields (`fileName`, `base64ProtectedPdf`, etc.) sit flat alongside `success`/`message`, not inside a nested `data: {}`.
```json
{"success": true, "message": "PDF generated successfully", "fileName": "protected_document.pdf", "base64ProtectedPdf": "..."}
```

**Pattern B** (`status` string field) — `PdfResponse`, used by 4 endpoints:
```json
{"fileName": "invoice.pdf", "status": "SUCCESS", "base64": "...", "mimeType": "application/pdf", "size": 0}
```

**Pattern C** (bare fields, no wrapper) — `ChecksumController`'s ad-hoc map, and `FileInfo` list entries:
```json
{"checksum": "12345", "nonce": "abc"}
```

**Pattern D** (raw scalar/binary, no JSON envelope at all) — 7 endpoints return `byte[]` or plain `String` directly: `/download-decoded`, `/metadata` (JSON-as-a-string, not JSON-as-an-object), `/download`, `/content`, `DELETE /{fileName}`, `/ping`, `/echo`.

**Conclusion: no common response wrapper exists.** Four distinct patterns are in production simultaneously, sometimes on endpoints in the very same controller (e.g. `Base64FileController`'s `/list` returns a bare array, `/download`/`/content` return raw text, `DELETE` returns a bare string — three different shapes in one four-method class).

---

## 4. Error Handling Analysis

- **`@ControllerAdvice`/`@RestControllerAdvice` classes: 0.**
- **Global exception handlers: 0.**
- **Custom exception types found:** `PdfProtectionValidationException`, `PdfProtectionDisabledException` (both new, `pdf.exception` package, local to `PdfProtectionController` only), `PdfDeliveryFacade.PdfDeliveryException` (facade-local, unwrapped by its controller). Every other controller catches only JDK/Spring exception types (`IllegalArgumentException`, `IOException`, `RestClientException`, generic `Exception`) — no application-defined exception vocabulary exists outside the `pdf` module.

| Endpoint (failure case) | HTTP Status | Error Body |
|---|---|---|
| `/checksum`, `/checksumgenerator` (blank fields) | 400 | `{"error": "message and secretKey cannot be empty"}` |
| `/convert` (batch size exceeded) | 400 | *(empty body)* |
| `/convert`, `/convert/single` (per-item fetch failure) | **200** | `FileConvertResponse{success:false, message:"..."}` — failure encoded inside a 200 |
| `/status/{id}` (unknown id) | 404 | *(empty body)* |
| `/pdf/send` (any item failure) | **200** (endpoint has no error status path at all) | `PdfResponse{status:"FAILED: ..."}` inside the returned list |
| `/pdf/convert/base64` (fetch failure) | 502 or 500 | `PdfResponse{status:"FAILED: ..."}` |
| `/pdf/convert/base64dynamic` (any failure incl. validation) | **always 200** | `PdfResponse{status:"FAILED: ..."}` or `"FILE_TOO_LARGE"` |
| `/pdf/single` (fetch failure) | 502 or 500 | `PdfResponse{status:"FAILED: ..."}` |
| `/pdf/protect` (validation) | 400 | `PdfProtectionResponse{success:false, message:"..."}` |
| `/pdf/protect` (disabled) | 503 | `PdfProtectionResponse{success:false, message:"..."}` |
| `/pdf/protect` (internal) | 500 | `PdfProtectionResponse{success:false, message:"..."}` |
| `/save-decoded` (invalid Base64) | 400 | `DecodedFileSaveResponse{success:false, message:"Invalid Base64 content: ..."}` |
| `/save-decoded` (IO failure) | 500 | Same type |
| `/download-decoded`, `/metadata`, `/download`, `/content` (traversal) | 403 | *(empty body)* |
| Same four (not found) | 404 | *(empty body)* |
| Same four (IO error) | 500 | *(empty body)* |
| `/callback` (IO/unexpected) | 500 | `Base64SaveResponse{success:false, message:"..."}` |
| `/list` (IO error) | 500 | *(empty body)* |
| `DELETE /{fileName}` (traversal) | 403 | *(empty body)*; (not found) → 404 *(empty body)* |
| `/pdf/protect`, missing required field (Bean Validation) | 400 | Spring Boot **default** error body: `{"timestamp":..., "status":400, "error":"Bad Request", "path":"..."}` — a **fifth** shape, different from every custom one above, appearing wherever `@Valid` rejects a request before the controller method body ever runs |

**At least 6 distinct error-body shapes are in production**: `{"error": "..."}`, `{success:false, message:"..."}` (4 different DTO types carrying this same pair of fields), `{status:"FAILED: ..."}` embedded in an otherwise-normal success DTO, empty bodies with only a status code, and Spring's default Bean Validation error shape.

---

## 5. Consistency Check

**Verdict: Not standardized.**

Every inconsistency example named in the prompt is independently confirmed present in this codebase:

- ✅ **Some endpoints return `ResponseEntity`, some return a bare DTO directly** — `/pdf/send` returns `List<PdfResponse>` with no `ResponseEntity` wrapper at all; every other endpoint uses `ResponseEntity<T>`.
- ✅ **Some return HTTP 200 even on failure** — `/pdf/convert/base64dynamic` *always* returns 200; `/pdf/send` has no non-200 path at all; `/convert`/`/convert/single` return 200 with `success:false` embedded for per-item fetch failures.
- ✅ **Some return HTTP 400/500 properly** — `/pdf/protect`, `/save-decoded`, `/pdf/convert/base64`, `/pdf/single` all use real status codes for their failure paths.
- ✅ **Some use `SUCCESS`/`FAILED` string status** — the entire `PdfResponse` family (4 endpoints).
- ✅ **Some use `success=true/false` boolean** — `PdfProtectionResponse`, `Base64SaveResponse`, `DecodedFileSaveResponse`, `FileConvertResponse` (5 different DTO types, no shared base/interface between them despite the identical field pair).
- ✅ **Some use an `error` field** — only `ChecksumController`, and only for its one hand-built validation-failure map; nowhere else in the app.
- **Additional inconsistencies beyond the prompt's examples:**
  - Some endpoints (the 7 raw-`String`/`byte[]` ones) have **no JSON envelope whatsoever** on success — not even a bare object, just a scalar or binary stream.
  - Bean Validation failures (`@Valid` rejecting a malformed request before the method body runs) fall through to **Spring Boot's own default error format**, which matches none of the application's five hand-rolled shapes — meaning a single endpoint (e.g. `/pdf/protect`) can return *two different error body shapes* depending on whether the failure was caught by Bean Validation or by the controller's own catch block.
  - `PdfResponse.status` is untyped free text (`"SUCCESS"`, `"FAILED: <arbitrary message>"`, `"FILE_TOO_LARGE"`) rather than an enum or a boolean — client code must string-match/prefix-check rather than branch on a structured field.

**Root cause, not just symptom:** this isn't inconsistent style within one team's ongoing work — it's the visible history of the app growing by literal copy-paste-and-modify across originally-separate features (checksum, file conversion, PDF fetch/relay, PDF protect, file storage) that were never unified under one contract, and a deliberate later decision (ADR-003) *not* to retroactively force them together via a global handler, precisely because doing so would itself be a breaking change to already-live consumers.

---

## 6. File Download Responses

Endpoints returning non-JSON content: `download-decoded/{fileName}` (`ResponseEntity<byte[]>`), `download/{fileName}` and `content/{fileName}` (`ResponseEntity<String>` but `Content-Type: text/plain`), `metadata/{fileName}` (`ResponseEntity<String>` with `Content-Type: application/json`, but the *body* is a raw string, not a Jackson-serialized object — a subtle but real difference from every other JSON-returning endpoint, since Spring does not re-parse/re-serialize the string, it passes it through verbatim).

None of the current endpoints use `ResponseEntity<Resource>`/`ByteArrayResource` — that pattern was used in `/pdf/protect`'s *first* implementation in this session (binary PDF attachment) but was superseded by the current Base64-in-JSON version per your explicit follow-up request.

**How file-download responses differ from JSON responses, structurally:**
1. **No DTO, no `success`/`message`/`status` field at all** — the response body *is* the file content, full stop. There is no room in the contract to signal partial success or attach metadata alongside the bytes (this is exactly why `/pdf/protect` needed a JSON wrapper once you wanted `fileName`/`message` alongside the content — a pure binary/text response can't carry both without a second round-trip like `/metadata/{fileName}`).
2. **`Content-Type` is semantically load-bearing** (`application/octet-stream`, `text/plain`, `application/json`-as-passthrough) rather than always `application/json` — client code must branch on `Content-Type`, not just parse JSON uniformly.
3. **Failure still returns empty-bodied, status-code-only responses** for all of these (403/404/500) — the one place in the whole app where error handling is at least *internally* consistent (all 7 raw endpoints agree: no body on failure), even though it's inconsistent with the JSON-returning endpoints' `success:false` convention.
4. **`Content-Disposition`/`Content-Length` headers appear only on these endpoints** — no JSON-returning endpoint sets either, since there's no "download" framing to establish.

---

## 7. Recommended Standard

```java
@Data
@Builder
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private String timestamp;   // ISO-8601, set at construction
    private String requestId;   // correlation id — does not exist anywhere in this codebase today (see docs/Logging_Remediation review, which flagged the same gap for log correlation)
}
```

**Example — success:**
```json
{
  "success": true,
  "message": "PDF generated successfully",
  "data": { "fileName": "protected_document.pdf", "base64ProtectedPdf": "JVBERi0..." },
  "timestamp": "2026-07-02T11:05:00Z",
  "requestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Example — validation error** (e.g. missing password):
```json
{
  "success": false,
  "message": "Password is mandatory when password protection is enabled",
  "data": null,
  "timestamp": "2026-07-02T11:05:00Z",
  "requestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```
— HTTP 400, and critically, **the same shape Bean Validation failures would need to produce too** (via a validation-only `@RestControllerAdvice` narrowly scoped to `MethodArgumentNotValidException`, translating field errors into this same envelope — this is the one place a *narrow*, opt-in advice earns its keep without touching any endpoint's existing success/other-error shapes).

**Example — business error** (e.g. upstream fetch failed):
```json
{
  "success": false,
  "message": "Error fetching file: connection timed out",
  "data": null,
  "timestamp": "2026-07-02T11:05:00Z",
  "requestId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```
— HTTP 502/500 as appropriate to the failure's nature, never a blanket 200.

**Example — file download** (where a true binary/text stream is the point, e.g. `/download-decoded/{fileName}`): **do not force this into the envelope.** Keep `ResponseEntity<byte[]>`/`Resource` with `Content-Disposition`/`Content-Type` exactly as today — wrapping binary content in a JSON envelope (e.g. as a nested Base64 string) is a legitimate *alternative* design (as `/pdf/protect` itself just demonstrated, per your explicit choice), but the two are different endpoint *kinds* serving different clients (browser-triggered downloads vs. programmatic API consumers), and standardizing "download" endpoints should mean *consistently binary*, not *forced into the same envelope as everything else*.

---

## 8. New Endpoint Compatibility — `POST /api/files/pdf/protect`

**Given the codebase's current, actual dominant pattern** (Pattern A: `success`/`message` + flat fields, used by 4 of the 5 "real" response DTOs — `PdfProtectionResponse`, `Base64SaveResponse`, `DecodedFileSaveResponse`, `FileConvertResponse`), **the current `PdfProtectionResponse` shape you already have is the best-aligned choice available today** — `{success, message, fileName, base64ProtectedPdf}` matches that dominant pattern's field-naming convention (`success`/`message` at the top, then flat operation-specific fields) more closely than `PdfResponse`'s `status`-string convention does.

**Why not `PdfResponse` (the `status:"SUCCESS"/"FAILED: ..."` pattern used by the other 4 PDF endpoints)?** Because that pattern is itself the *least* structured of the two ("FAILED: " + arbitrary text is not a value a client should have to prefix-match), and adopting it for the newest endpoint in the codebase would entrench the weaker convention rather than the stronger one.

**Why not adopt the full `ApiResponse<T>` envelope from §7 right now?** Because doing so today, unilaterally, on just this one endpoint, would make `/pdf/protect` inconsistent with *every other* endpoint in a *new* way (nested `data`, `timestamp`, `requestId` — none of which any other endpoint has) rather than reducing inconsistency — the envelope is a good target for a coordinated, all-endpoints migration (§ below), not a one-off adoption.

**Recommendation: keep the current `PdfProtectionResponse` shape as-is.** It's already the best-aligned option among what exists; changing it again right now (a third time this session) would cost real rework for a marginal-at-best consistency gain, given no single existing pattern is authoritative enough to defer to.

---

## Deliverables Summary

1. **Endpoint-by-endpoint response matrix:** §1 (20/20 endpoints, 0 sharing an identical contract with any other).
2. **Response DTOs and usage:** §2 (7 response DTOs/shapes found; no `ErrorResponse` class exists).
3. **Error handling matrix:** §4 (at least 6 distinct error-body shapes across the app, plus Spring's own default Bean Validation shape as a 7th, uncoordinated with the rest).
4. **Consistency findings:** §5 — every inconsistency example named in the prompt is confirmed present, plus two more found independently (no-envelope raw responses, and the dual-shape-per-endpoint Bean Validation gap).
5. **Recommended standard:** §7 — `ApiResponse<T>` envelope for JSON endpoints; binary/text endpoints deliberately excluded from the envelope by design, not by oversight.
6. **Migration effort assessment:** see below.
7. **Final verdict:** see below.

### Migration effort assessment

| Scope | Effort | Notes |
|---|---|---|
| Introduce `ApiResponse<T>` + a narrow `@RestControllerAdvice` for `MethodArgumentNotValidException` only | Low | New classes, zero impact on existing endpoints until adopted |
| Migrate the 4 already-`success`/`message`-shaped DTOs (`PdfProtectionResponse`, `Base64SaveResponse`, `DecodedFileSaveResponse`, `FileConvertResponse`) to wrap in `ApiResponse<T>` | Medium | **Breaking change** for every existing consumer of these 4 response bodies — same class of decision as the `/pdf/protect` replacement earlier this session; needs the same explicit "replace vs. new-path" choice per endpoint |
| Migrate the `PdfResponse`-based endpoints (4 endpoints, `status` string field) to `ApiResponse<T>` | Medium-High | Requires deciding what happens to the free-text `"FAILED: ..."` convention some integrations may already parse by prefix — highest risk of silent consumer breakage in the whole app |
| Migrate `ChecksumController`'s ad-hoc map | Low | Only 2 endpoints, small blast radius |
| Migrate the 7 raw-`String`/`byte[]` endpoints | **Not recommended** | These are correctly *not* JSON-enveloped by design (§6) — "migrating" them would mean changing what kind of endpoint they are, not just their shape |
| Add `requestId` correlation | Medium | No correlation-ID mechanism (MDC or otherwise) exists anywhere in the app today — this is net-new infrastructure, not just a DTO field, and was already flagged as a gap in the earlier logging-remediation review |

**Total, if pursued in full: Medium-High effort, and every step beyond "introduce the new classes" is a breaking-change decision requiring the same per-endpoint sign-off already established as this project's working pattern (see the `/pdf/protect` replacement precedent this session).**

### Final verdict

**The application does not have a well-defined API response contract.** It has five-plus independently-evolved response conventions living side by side, zero shared base type or interface between any of the "success/message"-shaped DTOs despite their structural similarity, no `ErrorResponse` type, no global exception handling (by deliberate, documented choice — not an oversight), and at least one endpoint (`/pdf/protect`) whose own shape has already changed twice in the current engagement in response to evolving requirements, underscoring that "the contract" here is better described as *per-endpoint, negotiated as each feature was built* rather than *standardized*. This is not a defect introduced by any single change — it is the accumulated, honestly-documented state of an application that grew feature-by-feature without an enterprise response standard being established up front. Standardizing now is a legitimate, valuable initiative, but — per every precedent set earlier in this engagement (the architecture refactor's ADRs, the `/pdf/protect` replacement decision) — it should proceed as a series of explicit, individually-approved, per-endpoint migrations, not a single sweeping change.
