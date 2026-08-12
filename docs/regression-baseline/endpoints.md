# Endpoint Inventory (Baseline, pre-refactor)

Captured against `com.twixor.base64convertor`, build as of this session (includes the `pdf/protect` endpoint added earlier).

| Endpoint | Method | Controller | Response Type |
|---|---|---|---|
| `/api/files/checksum` | POST | `ChecksumController` | `Map<String,String>` (JSON: `checksum`, `nonce`) |
| `/api/files/checksumgenerator` | POST | `ChecksumController` | `Map<String,String>` (JSON: `checksum`, `nonce`) |
| `/api/files/convert` | POST | `FileConvertController` | `List<FileConvertResponse>` |
| `/api/files/convert/single` | POST | `FileConvertController` | `FileConvertResponse` |
| `/api/files/status/{processingId}` | GET | `FileConvertController` | `FileConvertResponse` (or 404) |
| `/api/files/pdf/send` | POST | `PdfController` | `List<PdfResponse>` |
| `/api/files/pdf/convert/base64` | POST | `PdfController` | `PdfResponse` |
| `/api/files/pdf/convert/base64dynamic` | POST | `PdfController` | `PdfResponse` |
| `/api/files/pdf/single` | POST | `PdfController` | `PdfResponse` |
| `/api/files/pdf/protect` | POST | `PdfController` | `PdfProtectResponse` |
| `/api/files/save-decoded` | POST | `FileRetrievalController` | `DecodedFileSaveResponse` |
| `/api/files/download-decoded/{fileName}` | GET | `FileRetrievalController` | `byte[]` (octet-stream) |
| `/api/files/metadata/{fileName}` | GET | `FileRetrievalController` | `String` (JSON passthrough) |
| `/api/files/callback` | POST | `FileRetrievalController` | `Base64SaveResponse` |
| `/api/files/list` | GET | `FileRetrievalController` | `List<FileInfo>` |
| `/api/files/download/{fileName}` | GET | `FileRetrievalController` | `String` (text/plain) |
| `/api/files/content/{fileName}` | GET | `FileRetrievalController` | `String` (text/plain body) |
| `/api/files/{fileName}` | DELETE | `FileRetrievalController` | `String` |
| `/api/test/ping` | GET | `TestController` | `String` |
| `/api/test/echo` | POST | `TestController` | `String` |

20 endpoint entries covering 19 distinct routes (checksum has 2 sibling routes; PDF has 5; filestorage has 8; conversion has 3; health has 2).
