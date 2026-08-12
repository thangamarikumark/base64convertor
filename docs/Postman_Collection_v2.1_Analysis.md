# Postman Collection v2.1 — Source-Verified Analysis

Generated strictly from source code (controllers, DTOs, validation annotations, service-layer response construction, exception handling) — not from any prior Postman collection, README, or Swagger annotation. Collection: `postman/base64convertor_v2.1_full_collection.json` (40 requests, 8 folders). Environment: `postman/base64convertor_v2.1_environment.json`.

Every response in this collection includes assertions for the three global observability headers (`X-Request-Id`, `X-API-Version`, `X-Timestamp`), set on **every** response by `RequestCorrelationFilter` regardless of endpoint or status code — verified live earlier this session, not assumed.

---

## Endpoint Discovery Matrix

| Method | Endpoint | Controller | Request DTO | Response DTO |
|---|---|---|---|---|
| GET | `/api/test/ping` | `TestController` | none | `String` |
| POST | `/api/test/echo` | `TestController` | raw `String` body | `String` |
| POST | `/api/files/checksum` | `ChecksumController` | `ChecksumRequest` (no bean validation — manual null/empty check) | `Map<String,String>` |
| POST | `/api/files/checksumgenerator` | `ChecksumController` | query params `message`, `secretKey` | `Map<String,String>` |
| POST | `/api/files/convert` | `FileConvertController` | `List<@Valid FileConvertRequest>` (`@Size(min=1)`) | `List<FileConvertResponse>` |
| GET | `/api/files/status/{processingId}` | `FileConvertController` | path variable | `FileConvertResponse` or 404 |
| POST | `/api/files/convert/single` | `FileConvertController` | `FileConvertRequest` (`@Valid`) | `FileConvertResponse` |
| GET | `/api/files/list` | `Base64FileController` | none | `List<FileInfo>` |
| GET | `/api/files/download/{fileName}` | `Base64FileController` | path variable | `String` (text) or 403/404/500 |
| GET | `/api/files/content/{fileName}` | `Base64FileController` | path variable | `String` (text) or 403/404/500 |
| DELETE | `/api/files/{fileName}` | `Base64FileController` | path variable | `String` or 403/404/500 |
| POST | `/api/files/callback` | `CallbackController` | `Base64SaveRequest` (`@Valid`) | `Base64SaveResponse` |
| POST | `/api/files/save-decoded` | `DecodedFileController` | `Base64SaveRequest` (`@Valid`) | `DecodedFileSaveResponse` |
| GET | `/api/files/download-decoded/{fileName}` | `DecodedFileController` | path variable | binary `byte[]` or 403/404/500 |
| GET | `/api/files/metadata/{fileName}` | `DecodedFileController` | path variable | raw JSON `String` or 403/404/500 |
| POST | `/api/files/pdf/protect` | `PdfProtectionController` | `PdfProtectionRequest` (`@Valid`) | `ApiResponse<PdfProtectionResponse>` (JSON) **or** binary `byte[]` (`responseType=ATTACHMENT`) |
| POST | `/api/files/pdf/send` | `PdfDeliveryController` | `List<@Valid PdfRequest>` | `List<PdfResponse>` |
| POST | `/api/files/pdf/single` | `PdfDeliveryController` | `PdfRequest` (`@Valid`) | `PdfResponse` |
| POST | `/api/files/pdf/convert/base64` | `PdfFetchController` | `PdfBase64Request` (`@Valid`) | `PdfResponse` |
| POST | `/api/files/pdf/convert/base64dynamic` | `PdfFetchController` | `PdfBase64RequestDynamic` (`@Valid`) | `PdfResponse` (200) **or Spring's default error body (500)** — see divergence note below |
| GET | `/actuator/health` | Spring Boot Actuator (port 9090) | none | `{status: "UP"}` |
| GET | `/actuator/info` | Spring Boot Actuator (port 9090) | none | JSON (empty — no `management.info.*` configured) |
| GET | `/actuator/prometheus` | Spring Boot Actuator (port 9090) | none | Prometheus text format |
| GET | `/actuator/metrics` | Spring Boot Actuator (port 9090) | none | JSON metrics index |

**Consumes/Produces:** every custom JSON endpoint consumes/produces `application/json`. `/api/test/echo` consumes `text/plain` (raw body, no DTO). `/download`, `/content` produce `text/plain`. `/download-decoded` produces `application/octet-stream`. `/pdf/protect` produces `application/json` (BASE64) or `application/pdf` (ATTACHMENT) depending on `responseType`. `/metadata/{fileName}` produces `application/json` but the body is a raw string, not a typed DTO (`Files.readString` passthrough).

---

## Undocumented / Notable Endpoints Discovered During Source Analysis

1. **`POST /api/test/echo`** — exists and works, but isn't mentioned in any prior collection or README found in this repo. Included here as a full request/response entry.
2. **Four Actuator endpoints on port 9090** (`health`, `info`, `prometheus`, `metrics`) — a separate management port from the main API port 8080, easy to miss if only port 8080 is tested. Included as the "Administrative APIs" folder with a distinct `managementBaseUrl` environment variable.
3. **`GET /api/files/status/{processingId}` is structurally dead** — confirmed by source review (`docs/Production_Readiness_Review.md`, P1-4): `FileConversionService.processFileAsync` has zero callers anywhere in the codebase, so `asyncResults` is permanently empty and this endpoint returns 404 for every possible `processingId`, in every environment. Included in the collection as a documented-404 example, not a bug in the request itself.
4. **`responseType` field on `PdfProtectionRequest`** — added this session; not yet reflected in any previously-generated Postman collection or OpenAPI cache. Both values (`BASE64`, `ATTACHMENT`) are covered here, plus the invalid-value 400 case.
5. **`/api/files/pdf/convert/base64dynamic`'s divergent security-failure behavior** — confirmed by direct source read of `PdfService.fetchAndConvertToBase64Dynamic`: `urlAllowlistValidator.validate(req.getUrl())` is called **before** the method's own try block, and the controller has no surrounding try/catch either, so a disallowed URL scheme produces Spring Boot's **generic default error page** (500, `{timestamp, status, error, path}`), not this endpoint's own `PdfResponse{status:"FAILED:..."}` shape used everywhere else. This directly contradicts what a reader would assume from the sibling `/pdf/convert/base64` endpoint's behavior (which *does* catch the same exception type and return its normal shape) — captured as its own explicit example per the instruction to treat source code as ground truth over expected/consistent-seeming behavior.
6. **`/pdf/send`/`/pdf/single`'s `target_url` has no URL-allowlist validation** (confirmed by source review, `docs/Production_Readiness_Review.md` P0-1) — unlike every other outbound-URL field in this codebase. A dedicated example request demonstrating the resulting blind-SSRF error-message oracle is included, with an explicit security-note callout — not to encourage exploitation, but so this fact is visible to anyone using the collection against a real environment.
7. **`/api/files/checksum`'s validation failure returns a bare `{error: "..."}` object**, not the `ApiResponse` envelope used by `/pdf/protect` — confirmed this endpoint predates and was never migrated to that pattern. Captured accurately rather than "corrected" to match the newer convention.

---

## Coverage Matrix

| Endpoint | Included in Collection | Success Example | Failure Example(s) |
|---|---|---|---|
| GET /api/test/ping | Yes | Yes | N/A (no failure mode) |
| POST /api/test/echo | Yes | Yes | N/A (no failure mode) |
| POST /api/files/checksum | Yes | Yes | Yes (validation — empty fields) |
| POST /api/files/checksumgenerator | Yes | Yes | N/A (same validation as above, not duplicated) |
| POST /api/files/convert | Yes | Yes | Yes (validation — empty list; security — disallowed scheme, 200 w/ success:false) |
| GET /api/files/status/{processingId} | Yes | N/A (structurally always 404 — see note 3 above) | Yes (404, documented as structural) |
| POST /api/files/convert/single | Yes | Yes | Yes (validation — missing url) |
| GET /api/files/list | Yes | Yes | N/A (no failure mode) |
| GET /api/files/download/{fileName} | Yes | Yes | Yes (404 not found; 403 path traversal, both forms) |
| GET /api/files/content/{fileName} | Yes | Yes | N/A (same failure modes as /download, not duplicated) |
| DELETE /api/files/{fileName} | Yes | Yes | Yes (404 not found) |
| POST /api/files/callback | Yes | Yes | Yes (validation — missing fileName) |
| POST /api/files/save-decoded | Yes | Yes | Yes (business — invalid Base64) |
| GET /api/files/download-decoded/{fileName} | Yes | Yes | Yes (404 not found) |
| GET /api/files/metadata/{fileName} | Yes | Yes | N/A (same failure modes as download-decoded, not duplicated) |
| POST /api/files/pdf/protect | Yes | Yes (both BASE64 and ATTACHMENT) | Yes (validation — missing password; business — invalid PDF; validation — bad responseType; 503 — disabled) |
| POST /api/files/pdf/send | Yes | Yes | Yes (security — SSRF oracle demonstration) |
| POST /api/files/pdf/single | Yes | Yes | Yes (502 — upstream HTTP error) |
| POST /api/files/pdf/convert/base64 | Yes | Yes | Yes (security — disallowed scheme, 500 w/ FAILED status string) |
| POST /api/files/pdf/convert/base64dynamic | Yes | Yes | Yes (security — CONFIRMED DIVERGENT: generic 500, not PdfResponse) |
| GET /actuator/health | Yes | Yes | N/A |
| GET /actuator/info | Yes | Yes | N/A |
| GET /actuator/prometheus | Yes | Yes | N/A |
| GET /actuator/metrics | Yes | Yes | N/A |

**Every discovered endpoint is covered.** 24 distinct endpoints (20 custom + 4 actuator), 40 total requests (several endpoints have 2-3 requests covering distinct success/failure/security scenarios).

---

## Global Test Assertions (applied to nearly every request)

```javascript
pm.test("Has observability headers", function () {
    pm.expect(pm.response.headers.has("X-Request-Id")).to.be.true;
    pm.expect(pm.response.headers.has("X-API-Version")).to.be.true;
    pm.expect(pm.response.headers.has("X-Timestamp")).to.be.true;
});
```

Omitted only where the request itself demonstrates a case where response shape/headers are exactly the point of the example (e.g. the `/pdf/convert/base64dynamic` divergent-failure example, where the assertion is instead that the response does *not* look like a normal `PdfResponse`) or where Spring's own default validation-failure page is being demonstrated (its headers aren't part of this codebase's own contract).

## Environment Variables

`baseUrl`, `managementBaseUrl` (separate from `baseUrl` since Actuator runs on port 9090, not 8080), `requestId`, `apiVersion`, `samplePdfUrl`, `sampleImageUrl`, `targetUrl` (flagged with a security-note description), `targetAuth`, `cookieValue`, `sampleB64FileName`, `sampleDecodedFileName` (the latter two are placeholders — populate from a real `/list` or `/save-decoded` response before running the download/metadata requests, since these filenames are generated at runtime with a timestamp+UUID prefix and cannot be hardcoded).
