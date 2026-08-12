# Base64 Convertor Service — Fresher Knowledge Transfer Document

**Application:** `base64convertor` (Spring Boot 3.5.6, Java 21)
**Package root:** `com.twixor.base64convertor`
**Purpose statement (from source):** *"Spring Boot application to convert PDF to Base64"* — but in practice it is a general-purpose **file ⇄ Base64 conversion micro-service** used to fetch files from remote URLs (or decode raw Base64), convert them, validate them, cache them, and forward them to downstream "target" APIs (e.g. a messaging/chat platform).

> No database, no JPA, no entities/repositories exist in this codebase. All "storage" is the local filesystem (temp cache folder + `.b64` output folder + `.meta.json` metadata files). Section 4 (Database Analysis) is replaced with **Filesystem/Storage Analysis** to stay accurate to the real code.

---

## 0. High-Level Architecture

```
                         ┌───────────────────────────┐
   Client / Bot Platform │   Spring Boot Application  │   Remote file host /
   (e.g. WhatsApp/chat   │      base64convertor        │   Target API
   integration layer) ───►                             ────► (webhook / chat
                         │  Controllers → Services →   │      gateway)
                         │  Utils → Filesystem/Tika     │
                         └───────────────────────────┘
```

The app has **5 controllers**, **5 services**, **11 DTOs**, **5 utility/config classes**, and **3 `@Configuration` classes**. There is no security/auth layer (no Spring Security), no database, and no message queue — it is a stateless-ish HTTP utility service with local disk caching and in-memory maps for async job tracking.

---

## 1. Module Overview

### 1.1 `controller` package — API Layer
**Purpose:** Exposes REST endpoints for checksum generation, file conversion (URL → Base64), PDF/file fetch-and-forward, Base64 save/decode/retrieve, and health testing.
**Why it exists:** Downstream systems (chat/bot platforms, workflow engines) need to move binary files (images, PDFs, documents) as Base64 strings inside JSON payloads (e.g., for WhatsApp Business API style messaging), and need a service that can fetch a file from a URL, convert it, validate it, and hand it back or forward it onward.
**Business problem solved:** Removes the need for every consuming application to reimplement file download, MIME detection, Base64 encoding/decoding, retry logic, and SSRF-safe URL handling.

### 1.2 `service` package — Business Logic Layer
**Purpose:** Implements the actual download/decode/encode/validate/forward logic.
**Why it exists:** Keeps HTTP concerns (controllers) separate from the heavier logic of file I/O, HTTP client calls to external systems, retries, metrics, and scheduled cleanup.

### 1.3 `dto` package — Data Transfer Objects
**Purpose:** Request/response shapes for all endpoints, including a nested "message/content/attachment" structure that mirrors an external chat-messaging API contract (channel, recipient, attachment, etc.).

### 1.4 `util` package — Cross-cutting Utilities
**Purpose:** Base64 file writing/cleanup, Base64+MIME signature validation, SSRF protection (URL allowlisting), a custom "trust-all-SSL" `RestTemplate`, and HTTP request/response logging with header masking.

### 1.5 `config` package — Application Configuration
**Purpose:** Typed configuration binding (`AppProperties`, `FileCacheProperties`), async thread pool setup, and retry enabling.

---

## 2. API Endpoint Analysis

All endpoints are unauthenticated at the Spring Security level (there is none) — the only "auth" concept is an `Authorization: Bearer <token>` header the service **attaches when calling outward** to target APIs, using `app.default.auth.token` from `application.properties` as a fallback.

---

### 2.1 `ChecksumController` — `/api/files`

#### `POST /api/files/checksum`
- **Request Payload (JSON body):**
```json
{ "message": "string", "secretKey": "string" }
```
- **Response Payload:**
```json
{ "checksum": "1234567890", "nonce": "uuid-string" }
```
(or `400` with `{"error": "message and secretKey cannot be empty"}`)

**What it does:** Computes a CRC32 checksum of `message + "|" + secretKey` (UTF-8) and returns it along with a random UUID nonce.
**Why needed:** Lightweight integrity/tamper-check value a caller can send alongside a payload to a downstream system, or use as a simple signature for request validation. Not cryptographically secure (CRC32 is a checksum, not a MAC).
**Who uses it:** Any internal caller needing a quick checksum + nonce pair, likely for webhook-callback validation.
**Execution flow:** Controller validates non-empty fields → builds `message|secretKey` string → CRC32 hash → returns hex/decimal value + random UUID.
**Validation:** `message` and `secretKey` must both be non-null and non-empty; otherwise `400 Bad Request`.
**Storage involved:** None.
**External APIs involved:** None.
**Error scenarios:** Empty/null message or secretKey → 400. No other failure paths (CRC32 cannot throw for text input).

#### `POST /api/files/checksumgenerator`
- **Request:** Query params `message`, `secretKey` (not JSON body — `@RequestParam`).
- **Response:** Same shape as `/checksum`.
- **What/why:** Identical logic to `/checksum`, but exposed via query parameters instead of JSON body — likely kept for backward compatibility with an older integration style (**this endpoint duplicates `/checksum`'s logic — see Technical Debt**).

---

### 2.2 `FileConvertController` — `/api/files`

#### `POST /api/files/convert`
- **Request Payload:** JSON array of file conversion requests:
```json
[
  {
    "url": "https://example.com/file.pdf",
    "fileName": "invoice.pdf",
    "caption": "optional caption",
    "mimeType": "application/pdf",
    "type": "document"
  }
]
```
- **Response Payload:** JSON array:
```json
[
  {
    "processingId": null,
    "fileName": "invoice.pdf",
    "mimeType": "application/pdf",
    "type": "document",
    "base64Data": "JVBERi0xLjQK...",
    "success": true,
    "message": "File converted successfully"
  }
]
```

**What it does:** Batch-converts a list of remote file URLs into Base64 strings, synchronously, one by one.
**Why needed:** A calling system (e.g., a bot flow builder) wants to send several attachments in a single request/response round-trip rather than one call per file.
**Who uses it:** Any client wanting bulk URL→Base64 conversion (e.g., forwarding multiple attachments in a chat conversation).
**Step-by-step execution:**
1. `@Valid @Size` on the list ensures it isn't empty.
2. Controller checks `requests.size()` against `app.convert.max-batch-size` (default 50); if exceeded → `400`.
3. For each request, `FileConversionService.processFile()` is called (sequentially, not in parallel).
4. For each: validates URL/mimeType/type are non-empty → validates URL scheme (and optional host allowlist) via `UrlAllowlistValidator` → generates a UUID temp filename with extension inferred from MIME type → downloads with retry (`app.retry.*`) → reads bytes → Base64-encodes → writes `.b64` output file via `Base64OutputWriter` → writes an audit log line → deletes the temp file → returns success response.
5. On any exception, an audit log entry with `FAILURE` status is written, the temp file is deleted, and an error response (`success:false`) is returned for that item (batch continues for other items).
**Validation performed:** Non-blank `url`, `mimeType`, `type` (bean validation `@NotBlank`); batch size ≤ configured max; URL scheme must be `http`/`https`; optional host allowlist.
**Storage involved:** Temp cache directory (`file.cache.path`), `.b64` output directory (`app.base64.output-path`), audit log files under `<cache path>/logs/conversion_audit_<date>.log`.
**External APIs involved:** Whatever host is in `request.url` (the file source) — arbitrary outbound GET via `URLConnection`.
**Error scenarios:** Missing url/mimeType/type → per-item error response (not HTTP-level failure); URL not http/https or not in allowlist → `IllegalArgumentException` → error response; download failure after all retries → error response; batch size over limit → `400` for the whole request.

#### `GET /api/files/status/{processingId}`
- **Request:** Path variable `processingId` (a UUID string).
- **Response:** A `FileConvertResponse` JSON object (see above), or `404` if not found.

**What it does:** Polls the result of an **asynchronous** file conversion job previously started via `processFileAsync` (see Service Layer — note: there is currently **no controller endpoint that triggers the async path**; see Technical Debt).
**Why needed:** For long-running downloads, a client can fire-and-poll instead of blocking on a slow synchronous call.
**Execution flow:** Looks up `processingId` in an in-memory `ConcurrentHashMap` (`asyncResults`) inside `FileConversionService`. Returns the current state (`"Processing"`, success, or `"Failed: ..."`).
**Storage:** In-memory only — **not persisted**; lost on app restart, and entries also expire via the hourly cleanup job (`cacheProps.getRetentionHours()`).
**Error scenarios:** Unknown/expired `processingId` → `404`.

#### `POST /api/files/convert/single`
- **Request:** Single `FileConvertRequest` object (same shape as one array item above).
- **Response:** Single `FileConvertResponse` object.
**What it does:** Same as `/convert` but for exactly one file, synchronously.
**Why needed:** Convenience endpoint to avoid wrapping a single file in an array.

---

### 2.3 `PdfController` — `/api/files/pdf`

This controller is the "fetch a file from a source system and forward it to a target system" gateway — heavily shaped around a **chat/messaging attachment contract** (`message.content.attachment`, `recipient`, `sender`, `preferences`).

#### `POST /api/files/pdf/send`
- **Request Payload:** JSON array of `PdfRequest`:
```json
[
  {
    "url": "https://source.example.com/getFile",
    "cookie": "SESSIONVALUE",
    "payload": "{\"docId\":123}",
    "target_url": "https://chatgateway.example.com/api/send",
    "target_auth": "Bearer xyz",
    "message": {
      "channel": "whatsapp",
      "content": {
        "type": "attachment",
        "attachment": {
          "type": "document",
          "caption": "Invoice",
          "fileName": "invoice.pdf",
          "mimeType": "application/pdf",
          "attachmentData": null
        }
      },
      "recipient": { "to": "919999999999", "recipient_type": "individual" },
      "sender": { "from": "919888888888" },
      "preferences": { "webHookDNId": "wh-123" }
    },
    "metaData": { "version": "1.0" }
  }
]
```
- **Response Payload:** JSON array of `PdfResponse`:
```json
[
  { "fileName": "invoice.pdf", "status": "SUCCESS", "base64": "JVBERi0...", "mimeType": "application/pdf", "size": 0 }
]
```

**What it does:** For each request: (1) fetches the source file (POST to `url` with `cookie` and `payload`) and Base64-encodes it, (2) injects the Base64 into `message.content.attachment.attachmentData`, (3) rebuilds a `TargetApiRequest` DTO (a "cleaned" copy of the message contract, stripping some source-only fields like `url`/`cookie`/`target_auth`), (4) POSTs that JSON to `target_url` with an `Authorization` header (either `target_auth` or the default token from config).
**Why needed:** This is the core "relay" use case — e.g., a bot builder platform asks this service to fetch a document from an internal system and deliver it to an external chat gateway attachment API, without the chat gateway needing to know how to reach the internal system.
**Who uses it:** A workflow/orchestration layer driving a chat conversation that needs to attach a fetched document.
**Step-by-step:**
1. Loop over each `PdfRequest`.
2. Call `pdfService.fetchAndConvertToBase64(url, cookie, payload)` — POSTs to `url` with `Cookie: JSESSIONID=<cookie>` header and the raw `payload` string as body; expects HTTP 200 and non-empty bytes back; Base64-encodes the response body; writes the Base64 to a `.b64` file for audit/debug.
3. If message/content/attachment exist, sets `attachmentData` on it, captures `fileName`/`mimeType`.
4. Builds `TargetApiRequest` (nested DTO builder logic — copies channel, content/attachment, recipient/reference, sender, preferences, metaData).
5. Builds headers (`Content-Type: application/json`, `Authorization` = `target_auth` if provided else `Bearer <app.default.auth.token>`).
6. Serializes to JSON via Jackson `ObjectMapper` and POSTs to `target_url`.
7. Collects a `PdfResponse` (SUCCESS/FAILED) per item.
8. **Finally block:** clears `attachmentData` back to `null` on the *request* object (memory hygiene — avoids holding large Base64 blobs / avoids accidentally logging them later), regardless of success/failure.
**Validation performed:** Bean validation `@NotBlank` on `url` inside `PdfRequest`... **actually note:** `PdfRequest.url` has no `@NotBlank` annotation in this DTO (only `PdfBase64Request`/`PdfBase64RequestDynamic` do) — see Technical Debt. URL scheme/allowlist validated inside `PdfService.fetchAndConvertToBase64` via `UrlAllowlistValidator`.
**Storage involved:** `.b64` output directory (audit copy of every converted Base64); no DB.
**External APIs involved:** (a) the source `url` (fetch), (b) the `target_url` (forward) — both arbitrary outbound HTTP calls.
**Error scenarios:** `RestClientException` (HTTP-level failure calling source or target) → per-item `FAILED: <message>` response, loop continues to next item; any other `Exception` → same handling; empty response body from source → `RuntimeException("Empty file content")` → retried (see retry config) then failed.

#### `POST /api/files/pdf/convert/base64`
- **Request Payload (`PdfBase64Request`):**
```json
{ "url": "https://source.example.com/file", "cookie": "optional", "payload": "optional-json-string" }
```
- **Response Payload (`PdfResponse`):**
```json
{ "fileName": "downloaded.pdf", "status": "SUCCESS", "base64": "JVBERi0...", "mimeType": "application/pdf", "size": 0 }
```
**What it does:** Simple "fetch one URL, return its Base64" endpoint — no forwarding to a target system, no chat-message wrapping.
**Why needed:** When a caller just wants the Base64 bytes back directly (e.g. to embed in their own response) rather than relaying to another API.
**Execution flow:** Calls the same `pdfService.fetchAndConvertToBase64()` used by `/send`.
**Error scenarios:** `RestClientException` → `502 Bad Gateway` with `FAILED:` message; any other exception → `500`.
**Note:** `fileName` is always hardcoded `"downloaded.pdf"` and `mimeType` always `"application/pdf"` regardless of actual content — misleading for non-PDF files (see Technical Debt).

#### `POST /api/files/pdf/convert/base64dynamic`
- **Request Payload (`PdfBase64RequestDynamic`):**
```json
{
  "url": "https://source.example.com/file",
  "cookie": "optional",
  "httpMethod": "GET",
  "payload": { "headers": {"X-Custom": "value"}, "body": {"key": "value"} },
  "authorization": "Bearer sometoken"
}
```
- **Response Payload (`PdfResponse`):** Same shape as above, but with `mimeType` auto-detected and `size` populated, and a distinct status `FILE_TOO_LARGE` possible.

**What it does:** The most feature-rich fetch endpoint — supports GET or POST (auto-detected from payload if not specified), custom headers, custom Authorization/Cookie, streaming-size threshold check, MIME/filename auto-detection from response headers, Base64 validity + magic-byte MIME verification, and large-file short-circuiting.
**Why needed:** Real-world source systems vary (some need GET, some need custom auth headers, some return `Content-Disposition` filenames) — this endpoint is flexible enough to integrate with most of them without new code.
**Step-by-step execution flow:**
1. Validate URL (scheme + allowlist).
2. Determine HTTP method: explicit `httpMethod` if valid, else GET if no payload/body, else POST.
3. Build headers: `Accept: application/octet-stream, */*`; merge any `payload.headers` map; set `Authorization`/`Cookie` if provided; `Content-Type: application/json` if POST.
4. Log the outbound request (headers masked for `Authorization`/`Cookie`).
5. Execute the HTTP call **with its own manual retry loop** (`executeWithRetry`, driven by `app.retry.*`).
6. Log the response status/headers.
7. Reject empty bodies (`IllegalStateException`).
8. Sniff the first 40 bytes for HTML/JSON error pages disguised as file content — if detected, dumps the body to a temp file and throws (protects against silently Base64-encoding an error page as if it were the real file).
9. Detect MIME type (from `Content-Type` header → else guess from URL extension → else guess from byte stream → else `application/octet-stream`).
10. Detect filename (from `Content-Disposition` header → else from URL path → else `"downloaded_file"`).
11. Compare body size against `app.pdf.stream-threshold-bytes` (default 5,000,000 bytes / ~5MB); if exceeded, returns `FILE_TOO_LARGE` status **without** the Base64 payload (to avoid huge JSON responses).
12. Base64-encode (without padding, no CRLF) and sanity-check the encoded length against the expected 4/3 ratio (logs a warning if mismatched — doesn't fail the request).
13. Validate the Base64 against MIME magic bytes via `Base64FileValidator.isValidBase64ForMime` — if invalid, dumps raw bytes to a temp file and throws (protects against corrupted/truncated downloads being silently returned as valid).
14. Writes the Base64 to a `.b64` output file for audit.
15. Returns `SUCCESS` with the Base64 payload.
**Validation performed:** URL non-blank + scheme/allowlist; empty-body check; HTML/JSON-disguised-as-binary check; Base64 length sanity check; magic-byte MIME signature check.
**Storage involved:** `.b64` output dir; temp files for error dumps (`invalid_*.html`, `corrupted_*_<filename>`) written to the OS temp directory via `Files.createTempFile` — **these are never cleaned up automatically** (see Technical Debt).
**External APIs involved:** The source `url` only (no target/forward step on this endpoint).
**Error scenarios:** Validation error (`IllegalArgumentException`) → `FAILED:` response (HTTP 200, not 502/500 — inconsistent with other endpoints, see Technical Debt); any other exception (including retry exhaustion) → `FAILED:` response, still HTTP `200 OK` (`ResponseEntity.ok(...)` always used) — **callers must inspect the `status` field in the body, not the HTTP status code**, which differs from `/convert/base64`.

#### `POST /api/files/pdf/single`
- **Request:** `PdfRequest` (single object, not array).
- **Response:** `PdfResponse`.
**What it does:** Same as `/send` but for exactly one request instead of a batch, and only forwards to `target_url` if it's non-blank (optional forwarding).
**Why needed:** Convenience single-item variant; also demonstrates that forwarding is optional here (unlike `/send` which always tries to forward if `message` exists). **This inconsistency between `/send` and `/single` is a technical-debt item.**

---

### 2.4 `FileRetrievalController` — `/api/files` (Base64 file management)

#### `POST /api/files/save-decoded`
- **Request Payload (`Base64SaveRequest`):**
```json
{ "base64Content": "JVBERi0...", "fileName": "myfile", "anyCustomField": "captured via JsonAnySetter" }
```
- **Response Payload (`DecodedFileSaveResponse`):**
```json
{
  "success": true,
  "message": "File decoded and saved successfully",
  "fileName": "20260702-101530_a1b2c3d4_myfile.pdf",
  "originalFileName": "myfile",
  "downloadLink": "http://localhost:8080/api/files/download-decoded/20260702-101530_a1b2c3d4_myfile.pdf",
  "fileSize": "128.45 KB",
  "fileSizeBytes": 131532,
  "mimeType": "application/pdf",
  "metadataFile": "20260702-101530_a1b2c3d4_myfile.pdf.meta.json",
  "savedAt": "2026-07-02T10:15:30.123"
}
```
**What it does:** Decodes an incoming Base64 string into raw bytes, auto-detects the real file type via Apache Tika (ignores any extension the client might have implied), saves the binary file to `app.base64.output-path`, and writes a companion `.meta.json` file capturing original filename, detected MIME type, size, timestamp, and any extra request fields.
**Why needed:** Inbound systems (e.g., a chat platform delivering a received attachment as Base64) need this service to persist the file to disk and hand back a stable download URL + metadata.
**Execution flow:** Validate config → create output dir → Base64-decode → Tika-detect MIME/extension → sanitize filename (strip unsafe chars, strip original extension, use detected one instead) → build unique filename `timestamp_shortId_name.ext` → write bytes → write metadata JSON → build download link → return.
**Validation performed:** `@NotBlank` on `base64Content` and `fileName` (Jakarta Bean Validation); Tika detection failure falls back to `application/octet-stream` / `.bin` rather than throwing.
**Storage involved:** `app.base64.output-path` directory — binary file + `.meta.json` sidecar.
**Error scenarios:** Invalid Base64 → `400` with `"Invalid Base64 content: ..."`; I/O failure → `500`; anything else → `500`.

#### `GET /api/files/download-decoded/{fileName}`
**What it does:** Streams the raw binary content of a previously decoded/saved file as an octet-stream download (`Content-Disposition: attachment`).
**Validation:** Path-traversal protection — resolves the path and checks it's still inside the configured output directory (`normalize().startsWith(...)`); blocks direct download of `.meta.json` files (`403`).
**Error scenarios:** File outside allowed dir → `403`; not found → `404`; I/O error → `500`.

#### `GET /api/files/metadata/{fileName}`
**What it does:** Returns the raw `.meta.json` content for a given saved (decoded) file as `application/json`.
**Validation:** Same directory-traversal guard as above.
**Error scenarios:** `403` traversal, `404` not found, `500` I/O.

#### `POST /api/files/callback`
- **Request Payload (`Base64SaveRequest`):** same shape as `/save-decoded`.
- **Response Payload (`Base64SaveResponse`):**
```json
{ "success": true, "message": "File saved successfully", "fileName": "20260702-101530_a1b2c3d4_myfile.pdf.b64", "downloadLink": "http://localhost:8080/api/files/download/...b64", "fileSize": "175.30 KB", "savedAt": "2026-07-02T10:15:30.456" }
```
**What it does:** Saves the **raw Base64 text itself** (not decoded bytes) to a `.b64` text file — this is essentially a generic "webhook callback receiver" for any system that wants to hand this service a Base64 blob and get a stable download link back. Commonly used as the `target_url` counterpart or as a simple storage callback endpoint.
**Why needed:** Some downstream/upstream integrations just want to drop a Base64 payload somewhere retrievable by URL, without needing MIME detection or decoding.
**Validation:** `@NotBlank` on `base64Content`/`fileName`.
**Error scenarios:** I/O failure → `500`.

#### `GET /api/files/list`
**What it does:** Lists all `.b64` files currently in the output directory with size and last-modified time.
**Response:** Array of `FileInfo { fileName, size, lastModified }`.

#### `GET /api/files/download/{fileName}`
**What it does:** Downloads a `.b64` text file's raw content as `text/plain` with an attachment header. Rejects non-`.b64` filenames and path traversal.

#### `GET /api/files/content/{fileName}`
**What it does:** Same as `/download/{fileName}` but returns the Base64 text inline (`200 OK` with body) rather than as an attachment header — functionally near-duplicate of `/download` (see Technical Debt).

#### `DELETE /api/files/{fileName}`
**What it does:** Deletes a `.b64` file from the output directory. Rejects non-`.b64` names and traversal attempts.
**Error scenarios:** `403` traversal/wrong extension, `404` not found, `500` I/O error.

---

### 2.5 `TestController` — `/api/test`

#### `GET /api/test/ping`
**What it does:** Health/liveness check — returns `"Pong! Service is alive."` Also `System.out.println`s to console (not using the structured logger — see Technical Debt).

#### `POST /api/test/echo`
**What it does:** Echoes back whatever raw string body was posted, prefixed with `"Received: "`. Used for connectivity/debugging during integration testing (confirms the service can receive a POST body from a given client/network path).

---

## 3. Business Logic Explanation (Layman's Terms)

> "When another system needs to send us a file (like a PDF invoice or a photo) that lives somewhere else on the internet, they tell us the web address. We go fetch that file, turn it into a long text string (Base64) that's safe to put inside JSON, double-check it really is the file type it claims to be, save a backup copy on disk, and either hand the text string back to the caller or forward it straight on to another system (like a chat platform) that needs to attach it to a message. If the file is too big, we say so instead of choking on a giant JSON response. If the download fails, we automatically retry a few times before giving up. Old backup copies and old log files get cleaned up automatically every hour so the disk doesn't fill up."

> "In the other direction: when someone sends us a Base64 string (they already have the file, just encoded as text), we can decode it back into the real file, figure out what kind of file it is automatically (even if they didn't tell us), save it, and give them a link to download it later."

---

## 4. Filesystem/Storage Analysis (replaces "Database Analysis" — no DB exists)

| Location | Purpose | Written By | Read By | Cleaned By |
|---|---|---|---|---|
| `file.cache.path` (temp cache dir) | Scratch space for downloading a file before Base64-encoding it (`/convert`, `/convert/single`) | `FileConversionService.handleFileProcessing` | Same method (immediately re-read to encode) | Deleted immediately after use; also hourly sweep for anything > `file.cache.retention-hours` old |
| `file.cache.path/logs/` | Daily audit log of every conversion attempt (`conversion_audit_<date>.log`) | `FileConversionService.logAudit` | Manual/ops review only | Rotated to `.zip` daily if `file.cache.audit.rotation-enabled`; archives older than `archive-retention-days` deleted |
| `app.base64.output-path` (`.b64` files) | Persisted copy of every generated Base64 string, for audit/manual retrieval | `Base64OutputWriter.write` (called from `FileConversionService`, `PdfService`) and `FileRetrievalController.saveBase64File` (`/callback`) | `FileRetrievalController` (`/list`, `/download`, `/content`, `/download/{fileName}`) | Hourly job deletes files older than `app.base64.retention-days` |
| `app.base64.output-path` (decoded binary + `.meta.json`) | Persisted binary file reconstructed from an inbound Base64 string, plus its metadata | `Base64DecodingService.decodeAndSaveFile` (via `/save-decoded`) | `/download-decoded/{fileName}`, `/metadata/{fileName}` | **Not covered by the hourly cleanup job** — grows unbounded (see Technical Debt) |
| OS temp dir (`Files.createTempFile`) | Error-dump files for corrupted/HTML-instead-of-binary responses in `PdfService.fetchAndConvertToBase64Dynamic` | `PdfService` | Manual debugging only | **Never cleaned up** (see Technical Debt) |
| In-memory `ConcurrentHashMap` (`asyncResults`, `asyncResultTimes`) | Tracks async conversion job status/results | `FileConversionService.processFileAsync` | `GET /api/files/status/{processingId}` | Hourly job purges entries older than `file.cache.retention-hours`; **lost entirely on app restart** |

---

## 5. Service Layer Analysis

### `FileConversionService`
| Method | Purpose | Input | Output | Business Rules |
|---|---|---|---|---|
| `processFile(request)` | Synchronously convert one URL to Base64 | `FileConvertRequest` | `FileConvertResponse` | Delegates to `handleFileProcessing` |
| `processFileAsync(request)` | Same, but on `fileExecutor` thread pool, tracked by ID | `FileConvertRequest` | `CompletableFuture<String>` (the processingId) | Not currently wired to any controller endpoint |
| `getAsyncResult(id)` | Poll async job result | `processingId` | `FileConvertResponse` or `null` | In-memory lookup only |
| `handleFileProcessing(request)` (private) | Core logic: validate → download (with retry) → encode → write audit + output | `FileConvertRequest` | `FileConvertResponse` | URL/mimeType/type required; URL must pass allowlist; metrics counters incremented; temp file always cleaned up (success or failure) |
| `downloadWithRetry(url, target)` (private) | Download with exponential backoff | `URL`, target `Path` | none (throws on exhaustion) | Attempts = `app.retry.max-attempts`; delay multiplies by `app.retry.multiplier` each retry |
| `cleanupOldFilesAndLogs()` (`@Scheduled` hourly) | Housekeeping | none | none | Cleans temp cache, rotates/archives logs, purges expired async results, delegates `.b64` cleanup to `Base64OutputWriter` |

### `PdfService`
| Method | Purpose | Input | Output | Business Rules |
|---|---|---|---|---|
| `fetchAndConvertToBase64(url, cookie, payload)` | Simple POST-fetch-and-encode, Spring-Retry annotated | url/cookie/payload | Base64 `String` | `@Retryable` on `RuntimeException`; validation errors (`IllegalArgumentException`) are **not** retried; requires HTTP 200 + non-empty body |
| `fetchAndConvertToBase64Dynamic(req)` | Flexible fetch (any method/headers) with full validation pipeline | `PdfBase64RequestDynamic` | `PdfResponse` | Manual retry loop (not `@Retryable`); large files short-circuit with `FILE_TOO_LARGE`; Base64/MIME signature must match or throws |

### `Base64DecodingService`
| Method | Purpose | Input | Output | Business Rules |
|---|---|---|---|---|
| `decodeAndSaveFile(request)` | Decode + persist + Tika-detect + metadata | `Base64SaveRequest` | `DecodedFileResult` | Filename sanitized to `[a-zA-Z0-9._-]`; original extension stripped and replaced by Tika-detected one; falls back to `"file"` if sanitization yields empty string |

### `FileTypeDetectionService`
| Method | Purpose | Input | Output | Business Rules |
|---|---|---|---|---|
| `detectFileType(base64Content)` | Tika-based MIME + extension detection | Base64 string | `DetectionResult{mimeType, extension, success, error}` | Empty/invalid Base64 → safe fallback `application/octet-stream`/`.bin`, `success=false`; warns (doesn't fail) if decoded content < 10 bytes |
| `detectExtension` / `detectMimeType` | Convenience wrappers | Base64 string | `String` | Thin wrappers around `detectFileType` |

### `DynamicHttpService`
| Method | Purpose | Input | Output | Business Rules |
|---|---|---|---|---|
| `forwardRequest(request)` | Generic POST forwarder | `PdfBase64Request` | `ResponseEntity<String>` | **Not called from any controller in this codebase** — appears to be dead/unused code (see Technical Debt) |

---

## 6. Sequence Flow

### Generic pattern (applies to nearly every endpoint):
```
Client
   ↓  HTTP request (JSON)
Controller  (validates @Valid DTO, applies batch-size/business checks)
   ↓
Service     (UrlAllowlistValidator → HTTP fetch/decode with retry → Tika/MIME/Base64 validation)
   ↓
Util        (Base64OutputWriter writes .b64 audit file / Base64FileValidator checks magic bytes)
   ↓
Filesystem  (cache dir, output dir, audit log dir — NO database)
   ↓
Response DTO ← Controller ← Service
   ↓
Client
```

### Specific: `POST /api/files/pdf/send`
```
Client
  ↓
PdfController.convertFileAndSendToTarget
  ↓
PdfService.fetchAndConvertToBase64(url, cookie, payload)
  ↓            ↑
UrlAllowlistValidator.validate(url)     RestTemplate → Source File Host (external)
  ↓
Base64OutputWriter.write(...)  →  Filesystem (.b64 audit copy)
  ↓
PdfController builds TargetApiRequest + headers
  ↓
RestTemplate.postForEntity(target_url, ...)  →  Target/Chat Gateway API (external)
  ↓
PdfResponse ← PdfController
  ↓
Client
```

---

## 7. Endpoint Summary Table

| Endpoint | Purpose | Storage Used | Business Function |
|---|---|---|---|
| `POST /api/files/checksum` | CRC32 checksum + nonce | None | Lightweight payload integrity check |
| `POST /api/files/checksumgenerator` | Same as above via query params | None | Legacy/alt-format checksum |
| `POST /api/files/convert` | Batch URL→Base64 conversion | Cache dir, `.b64` output dir, audit logs | Bulk attachment conversion |
| `POST /api/files/convert/single` | Single URL→Base64 conversion | Same as above | Single attachment conversion |
| `GET /api/files/status/{id}` | Poll async conversion status | In-memory map | Async job polling (unused trigger path) |
| `POST /api/files/pdf/send` | Fetch file + forward to target chat API | `.b64` output dir | Deliver attachment into a chat/message flow |
| `POST /api/files/pdf/convert/base64` | Fetch file, return Base64 | `.b64` output dir | Simple fetch-and-return |
| `POST /api/files/pdf/convert/base64dynamic` | Flexible fetch (any method/headers) with full validation | `.b64` output dir, OS temp (error dumps) | Robust integration with varied source systems |
| `POST /api/files/pdf/single` | Fetch + optional forward, single item | `.b64` output dir | Single-item variant of `/send` |
| `POST /api/files/save-decoded` | Decode Base64 → binary file + metadata | Output dir (binary + `.meta.json`) | Persist an inbound attachment |
| `GET /api/files/download-decoded/{fileName}` | Download decoded binary file | Output dir | Retrieve a previously decoded file |
| `GET /api/files/metadata/{fileName}` | Fetch metadata JSON for decoded file | Output dir | Inspect stored file's metadata |
| `POST /api/files/callback` | Save raw Base64 text to `.b64` file | Output dir | Generic Base64 storage callback |
| `GET /api/files/list` | List all `.b64` files | Output dir | Inventory of stored Base64 files |
| `GET /api/files/download/{fileName}` | Download `.b64` as text attachment | Output dir | Retrieve Base64 text file |
| `GET /api/files/content/{fileName}` | Return `.b64` content inline | Output dir | Retrieve Base64 text inline |
| `DELETE /api/files/{fileName}` | Delete a `.b64` file | Output dir | Cleanup/manual file removal |
| `GET /api/test/ping` | Liveness probe | None | Health check |
| `POST /api/test/echo` | Echo request body | None | Connectivity debugging |

---

## 8. Technical Debt Identification

**Security concerns**
- `app.default.auth.token` is a **hardcoded secret committed in `application.properties`** (`q4ynVHA2s4d3unfWY2ujrQ==`) — should be externalized to a secrets manager / environment variable.
- `app.http.trust-all-ssl=true` by default — `UnsafeRestTemplate` trusts **any** SSL certificate, disabling TLS verification for all outbound calls unless explicitly turned off in a given environment. This is a serious MITM risk if left on in production (the property comment does warn about this, but the default is still `true`).
- `app.url.allowlist-enabled=false` by default — SSRF protection (host allowlisting) is present in code (`UrlAllowlistValidator`) but **disabled out of the box**; only scheme (`http`/`https`) is enforced by default. Since this service accepts arbitrary URLs from callers and fetches them server-side, this is a classic SSRF vector (could be pointed at internal/metadata endpoints) unless the allowlist is enabled in deployment config.
- No authentication/authorization on any endpoint (no Spring Security). Anyone who can reach the service can list/download/delete stored files, or make the server issue arbitrary outbound HTTP requests.
- `Cookie` and `Authorization` values are accepted directly from client-supplied request bodies and forwarded server-side to arbitrary URLs — potential for credential leakage if `url`/`target_url` are attacker-controlled.
- Windows-specific hardcoded paths in `application.properties` (`C:\Users\Twixoradmin\Pictures\...`) — will not work in Linux/containerized deployments without override; also reveals a specific developer's local machine path in source control.

**Performance issues**
- `/api/files/convert` processes the batch **sequentially** in a loop (`requests.stream().map(...)`), not in parallel, even though an `@Async` thread pool (`fileExecutor`) exists and is unused for this path — a batch of 50 slow downloads will serialize.
- `PdfService.fetchAndConvertToBase64Dynamic` retries manually inside the method with `Thread.sleep()` — this **blocks the request-handling thread** during backoff, tying up a servlet thread per retrying request instead of using non-blocking/async retry.
- `Files.readAllBytes` / `Base64.getEncoder().encodeToString` load entire files into memory — for the max-allowed file (just under the 5MB stream threshold, or larger for `/convert` which has no size threshold at all) this means multiple full-size byte-array copies in memory per request.

**Duplicate code**
- `/api/files/checksum` and `/api/files/checksumgenerator` implement identical logic via two entry points (body vs. query params) — could be unified.
- `/api/files/download/{fileName}` and `/api/files/content/{fileName}` are near-identical (only the response header differs).
- Directory-traversal-prevention logic (`filePath.normalize().startsWith(...)`) is copy-pasted across 5 different methods in `FileRetrievalController` instead of extracted into a shared utility.
- `formatFileSize()` is duplicated verbatim in both `FileRetrievalController` and `Base64DecodingService`.
- `sanitizeFileName`/inline filename-sanitizing regex (`[^a-zA-Z0-9._-]`) is repeated in `FileRetrievalController.saveBase64File`, `Base64DecodingService.sanitizeFileName`, and `Base64OutputWriter.write`.
- `PdfController.buildTargetApiRequest`/`buildTargetHeaders` logic overlaps significantly between `/send` and `/single` handlers.
- `DynamicHttpService` appears to be entirely dead code — no controller references it.

**Hardcoded values**
- Default auth token and content type in `application.properties` (flagged above under security).
- `PdfController.convertToBase64` hardcodes `fileName = "downloaded.pdf"` and `mimeType = "application/pdf"` regardless of the actual fetched content type.
- Base URL for generated download links is hardcoded to `"http://localhost:" + serverPort` in both `FileRetrievalController` and `Base64DecodingService` — will produce **broken/unreachable links** in any real deployment behind a load balancer, reverse proxy, or different hostname (should use `X-Forwarded-Host`/configured public base URL instead).
- Windows file-system paths as defaults (noted above).

**Missing validations**
- `PdfRequest.url` (used by `/send` and `/single`) has **no `@NotBlank`/`@Valid` constraint**, unlike `PdfBase64Request`/`PdfBase64RequestDynamic` — a request with a missing URL will NPE deep inside `PdfService` rather than failing fast with a clean 400.
- No max-file-size enforcement on `/api/files/convert` (only `/pdf/convert/base64dynamic` has `app.pdf.stream-threshold-bytes`) — a very large file could be fully downloaded and Base64-encoded in memory before any size check.
- No validation that `target_url` in `PdfRequest` is itself allowlisted/scheme-checked before the outbound POST in `PdfController` (only the **source** `url` goes through `UrlAllowlistValidator`, not `target_url`) — a caller could exfiltrate fetched file data to any arbitrary internal/external `target_url`.
- Decoded-file cleanup (`/save-decoded` outputs) is not covered by the hourly retention job that cleans `.b64` files — binary files + metadata accumulate indefinitely.
- Temp error-dump files (`invalid_*.html`, `corrupted_*`) created in `PdfService.fetchAndConvertToBase64Dynamic` are never deleted.

**Miscellaneous / code-quality**
- `TestController` uses raw `System.out.println` instead of the configured Log4j2 logger used everywhere else.
- `PdfResponse` has a constructor `PdfResponse(fileName, success, base64)` that is **completely empty** (does nothing) — dead/broken constructor, easy to misuse accidentally.
- Inconsistent HTTP status semantics: `/pdf/convert/base64` returns `502`/`500` on failure, while `/pdf/convert/base64dynamic` **always returns `200 OK`** even on failure (status communicated only in the JSON body) — callers must handle these two endpoints differently, which is error-prone.
- `Base64convertorApplication.main` prints a startup banner via `System.out.println` rather than the logger.

---

## 9. Fresher Knowledge Transfer Summary

| Endpoint | One-line Summary | Business Summary | Technical Summary |
|---|---|---|---|
| `POST /api/files/checksum` | Computes a CRC32 checksum + nonce for a message/secret pair. | Gives callers a quick integrity value to attach to a payload. | CRC32 over `message\|secretKey` UTF-8 bytes + random UUID. |
| `POST /api/files/checksumgenerator` | Same as above, via query params. | Legacy-format checksum access. | Duplicate logic, `@RequestParam` instead of body. |
| `POST /api/files/convert` | Converts a batch of file URLs into Base64. | Lets a caller move many attachments in one call. | Sequential loop over `FileConversionService.processFile`; retry + audit log + `.b64` output per item. |
| `POST /api/files/convert/single` | Converts one file URL into Base64. | Single-attachment convenience call. | Delegates directly to `FileConversionService.processFile`. |
| `GET /api/files/status/{id}` | Polls an async conversion job. | Lets a caller check progress without blocking. | Reads an in-memory map; async trigger path currently unused. |
| `POST /api/files/pdf/send` | Fetches a file and forwards it into a chat-message attachment API. | Delivers a document into an outgoing chat/message flow. | Fetch → Base64 → rebuild `TargetApiRequest` → POST to `target_url`. |
| `POST /api/files/pdf/convert/base64` | Fetches a file and returns its Base64. | Simple "get me this file as text" call. | POST fetch with retry via Spring Retry `@Retryable`. |
| `POST /api/files/pdf/convert/base64dynamic` | Flexible fetch with full validation (MIME check, size limit, retry). | Robust integration point for varied/unreliable source systems. | Auto GET/POST, header injection, magic-byte MIME validation, size threshold. |
| `POST /api/files/pdf/single` | Fetch + optional forward, single item. | Single-item variant of the relay use case. | Forwards only if `target_url` present. |
| `POST /api/files/save-decoded` | Decodes Base64 into a real file on disk with metadata. | Stores an inbound attachment for later retrieval. | Tika MIME detection, unique filename, `.meta.json` sidecar. |
| `GET /api/files/download-decoded/{fileName}` | Downloads a previously decoded binary file. | Retrieve a stored attachment. | Path-traversal-guarded file stream. |
| `GET /api/files/metadata/{fileName}` | Returns metadata for a decoded file. | Inspect stored attachment details. | Reads the `.meta.json` sidecar. |
| `POST /api/files/callback` | Saves raw Base64 text and returns a download link. | Generic storage endpoint for any Base64 payload handoff. | Writes `.b64` file, no decoding/validation. |
| `GET /api/files/list` | Lists stored `.b64` files. | Inventory/audit view of stored Base64 files. | Directory listing filtered by `.b64` extension. |
| `GET /api/files/download/{fileName}` | Downloads a `.b64` text file. | Retrieve raw Base64 text as an attachment. | Reads and streams file content as `text/plain`. |
| `GET /api/files/content/{fileName}` | Returns `.b64` content inline. | Retrieve raw Base64 text in the response body. | Same as `/download` minus attachment header. |
| `DELETE /api/files/{fileName}` | Deletes a `.b64` file. | Manual cleanup of stored Base64 files. | Deletes file after extension/path checks. |
| `GET /api/test/ping` | Health check. | Confirms the service is up. | Static string response. |
| `POST /api/test/echo` | Echoes the request body. | Connectivity/debug tool. | Returns `"Received: " + body`. |

---

*Document generated for onboarding purposes. No database/entity/repository layer exists in this application; all persistence is filesystem-based.*
