# Streaming Downloads — Evaluation (Analysis Only, Not Implemented)

Follow-up evaluation to P2 finding #5 in `docs/Performance_Review_Quick_Wins.md`. Scope: assess whether to convert the codebase's `Files.readAllBytes`-based download endpoints to streamed `Resource` responses. No code changed for this evaluation.

---

## Current State — Exhaustive Enumeration

Only **one** endpoint in the codebase fully buffers a file into a `byte[]` before serving it:

| File | Method | Read call | Response type |
|---|---|---|---|
| `FileStorageFacade.java:79` | `readDecodedFile` | `Files.readAllBytes(filePath)` | called from `DecodedFileController.downloadDecodedFile` → `ResponseEntity<byte[]>` |

**`Base64FileController` was re-checked in full for this evaluation and does *not* use `readAllBytes`** — its `downloadFile`/`getFileContent` endpoints (`Base64FileController.java:44,63`) both call `FileStorageFacade.readBase64File`, which uses `Files.readString(filePath)` (`FileStorageFacade.java:180`) — a `String`, not a `byte[]`. This is because those endpoints only ever serve `.b64` files (plain-text Base64 content), which this codebase's own retention/size conventions keep small relative to the binary files `readDecodedFile` serves. Functionally this has the same "fully buffer before responding" characteristic as `readAllBytes`, but since `.b64` output is text (not the raw binary content, which is typically ~33% larger before Base64 encoding), it's a smaller and different-shaped problem — noted here for completeness but not scored as equally urgent.

**So the actual scope of this evaluation is one method: `FileStorageFacade.readDecodedFile` / `DecodedFileController.downloadDecodedFile`.**

---

## What a `Resource`-Based Approach Would Look Like

```java
// FileStorageFacade
public Resource readDecodedFileAsResource(String fileName) throws IOException {
    String outputPath = appProperties.getBase64().getOutputPath();
    Path filePath = Paths.get(outputPath).resolve(fileName);

    if (!pathTraversalGuard.isWithin(filePath, Paths.get(outputPath))) {
        throw new AccessDeniedException(fileName);
    }
    if (!Files.exists(filePath)) {
        throw new NoSuchFileException(fileName);
    }
    if (fileName.endsWith(".meta.json")) {
        throw new AccessDeniedException(fileName);
    }
    return new FileSystemResource(filePath);
}

// DecodedFileController
@GetMapping("/download-decoded/{fileName}")
public ResponseEntity<Resource> downloadDecodedFile(@PathVariable String fileName) {
    try {
        Resource resource = fileStorageFacade.readDecodedFileAsResource(fileName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentLength(resource.contentLength())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    } catch (AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    } catch (NoSuchFileException e) {
        return ResponseEntity.notFound().build();
    } catch (IOException e) {
        logger.error("Error downloading file {}: {}", fileName, e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
```

All existing validation (path-traversal guard, existence check, `.meta.json` access block) carries over unchanged — only the terminal read/response step changes. `FileSystemResource` is already transitively available (core Spring, no new dependency) and Spring MVC's `ResourceHttpMessageConverter` streams it directly to the servlet output stream rather than materializing it in the JVM heap first.

## Header Considerations

- **`Content-Length`:** `resource.contentLength()` for a `FileSystemResource` calls `File.length()` — a filesystem stat, not a read — so the header is still set accurately up front (clients that rely on `Content-Length` for progress bars/validation see no behavior change). This is different from a true chunked/unbounded stream (e.g., a live-generated response) where `Content-Length` genuinely can't be known in advance; here the file already exists on disk with a fixed size, so this concern doesn't apply.
- **`Content-Disposition`:** Unchanged — same header, same value, set the same way. No behavior difference.
- **`Content-Type`:** Unchanged — still `application/octet-stream`, matching current behavior exactly (the endpoint doesn't currently attempt content-type sniffing on download, and this change wouldn't either).
- **Byte-range requests (`Range`/`Accept-Ranges`):** Not currently supported either way (the current `byte[]` response doesn't support partial content, and a naive `Resource` swap wouldn't automatically add it either — `ResourceHttpMessageConverter` used via a plain `ResponseEntity<Resource>` does not enable range support the way Spring's dedicated static-resource-serving infrastructure does). Out of scope for this evaluation since it wasn't a stated requirement and isn't a regression from current behavior.

## Effort / Risk

| | Assessment |
|---|---|
| Code change size | Small — one new/modified facade method, one controller method signature change, no DTO changes, no changes to the validation logic that already exists |
| New dependencies | None — `FileSystemResource`/`Resource` are core Spring |
| Test coverage | No existing test covers `downloadDecodedFile` directly (confirmed — the only tests in the repo are `LogSanitizerTest`, `PdfProtectionServiceImplTest`, and this session's `RequestCorrelationFilterTest`/`RequestCorrelationIntegrationTest`); a live download smoke-test (as done for the P1 fixes this session) is the practical verification path, plus optionally a new unit/integration test added alongside the change |
| Behavioral risk | Low — headers, status codes, and error-path behavior (403/404/500) are all preserved identically; the only observable difference is *how* the bytes reach the client (streamed vs. buffered), which is not part of this codebase's documented contract for the endpoint |
| Rollback risk | Low — small, isolated diff, easy to revert independently of any other change |

## Recommendation

**Proceed as a follow-up**, separate from the four P1 fixes already implemented and verified this session. It's low-risk and mechanically simple (smaller in scope than any of the four P1 changes already made), but it touches response-construction code on a live download path, so it warrants its own change + its own live verification pass (a real download compared byte-for-byte against the pre-change response, plus a check that `Content-Length`/`Content-Disposition` still arrive correctly) rather than being bundled silently into another change. Priority-wise this is the natural next item after the P1s — real memory-footprint benefit for the largest files this codebase permits (up to 20MB protected PDFs saved via other endpoints, retrieved through this one), genuinely low implementation risk, and no dependency or architectural change required.
