# API Inventory (Baseline, pre-refactor)

| URL | Method | Request DTO | Response DTO | Service Called | External Systems | Filesystem Usage |
|---|---|---|---|---|---|---|
| `/api/files/checksum` | POST | `ChecksumController.ChecksumRequest` (inline) | `Map<String,String>` | none (inline controller logic) | none | none |
| `/api/files/checksumgenerator` | POST | query params `message`, `secretKey` | `Map<String,String>` | none (inline controller logic) | none | none |
| `/api/files/convert` | POST | `List<FileConvertRequest>` | `List<FileConvertResponse>` | `FileConversionService.processFile` | arbitrary URL in `request.url` | `file.cache.path` (temp), `app.base64.output-path` (.b64), `file.cache.path/logs` (audit) |
| `/api/files/convert/single` | POST | `FileConvertRequest` | `FileConvertResponse` | `FileConversionService.processFile` | arbitrary URL | same as above |
| `/api/files/status/{id}` | GET | path variable | `FileConvertResponse` | `FileConversionService.getAsyncResult` | none | none (in-memory map) |
| `/api/files/pdf/send` | POST | `List<PdfRequest>` | `List<PdfResponse>` | `PdfService.fetchAndConvertToBase64` | source `url`, `target_url` | `app.base64.output-path` (.b64 audit) |
| `/api/files/pdf/convert/base64` | POST | `PdfBase64Request` | `PdfResponse` | `PdfService.fetchAndConvertToBase64` | source `url` | `app.base64.output-path` |
| `/api/files/pdf/convert/base64dynamic` | POST | `PdfBase64RequestDynamic` | `PdfResponse` | `PdfService.fetchAndConvertToBase64Dynamic` | source `url` | `app.base64.output-path`, OS temp (error dumps) |
| `/api/files/pdf/single` | POST | `PdfRequest` | `PdfResponse` | `PdfService.fetchAndConvertToBase64` | source `url`, optional `target_url` | `app.base64.output-path` |
| `/api/files/pdf/protect` | POST | `PdfProtectRequest` | `PdfProtectResponse` | `PdfProtectionService.buildPassword/protect` | none | `app.base64.output-path` (binary + .meta.json) |
| `/api/files/save-decoded` | POST | `Base64SaveRequest` | `DecodedFileSaveResponse` | `Base64DecodingService.decodeAndSaveFile` | none | `app.base64.output-path` (binary + .meta.json) |
| `/api/files/download-decoded/{fileName}` | GET | path variable | raw bytes | none (direct `Files.readAllBytes`) | none | `app.base64.output-path` |
| `/api/files/metadata/{fileName}` | GET | path variable | raw JSON string | none (direct `Files.readString`) | none | `app.base64.output-path` |
| `/api/files/callback` | POST | `Base64SaveRequest` | `Base64SaveResponse` | none (direct `Files.writeString`) | none | `app.base64.output-path` |
| `/api/files/list` | GET | none | `List<FileInfo>` | none (direct `Files.list`) | none | `app.base64.output-path` |
| `/api/files/download/{fileName}` | GET | path variable | raw text | none (direct `Files.readString`) | none | `app.base64.output-path` |
| `/api/files/content/{fileName}` | GET | path variable | raw text | none (direct `Files.readString`) | none | `app.base64.output-path` |
| `/api/files/{fileName}` | DELETE | path variable | plain string | none (direct `Files.deleteIfExists`) | none | `app.base64.output-path` |
| `/api/test/ping` | GET | none | plain string | none | none | none |
| `/api/test/echo` | POST | raw `String` body | plain string | none | none | none |

**Note on "Service Called = none":** several `FileRetrievalController` endpoints have no service layer today — this is Code Smell #1 from the architecture review (documented in `Architecture_Refactoring_Recommendation.md`), and is exactly what `FileStorageFacade` (A11a) absorbs during Phase A. Its "no service" status here is the accurate baseline, not an omission.
