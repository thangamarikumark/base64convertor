# POST /api/files/pdf/protect — Endpoint Replacement Notes

**Status: Implemented and live-verified.** This is an intentional, explicitly-approved breaking change (see conversation history — user chose "Replace the existing endpoint entirely" after being shown the conflict with the prior name/dob-derived-password implementation).

## What changed

| | Before | After |
|---|---|---|
| Request | `{name, dob, base64_docContent}` | `{passwordProtected, password, base64DocContent}` |
| Password | Derived from `name`+`dob` via a configurable pattern | Supplied directly by the caller |
| Response (success) | JSON (`success`, `base64ProtectedPdf`, `downloadLink`, etc.) | Raw binary PDF, `Content-Type: application/pdf`, `Content-Disposition: attachment` |
| Response (failure) | JSON, various shapes depending on error | JSON `{success, message}`, consistently |
| Persistence | Saved to `app.base64.output-path` + `.meta.json` sidecar | None — stateless |
| Config | `app.pdf.protect.*` (password pattern, encryption key length, permissions) | `pdf.protection.*` (`enabled`, `max-file-size-mb`, `default-filename`) |

## Files removed
- `pdf/dto/PdfProtectRequest.java`, `pdf/dto/PdfProtectResponse.java`
- `pdf/facade/PdfProtectionFacade.java` (orchestration folded directly into the service per the new spec's explicit responsibility list)
- `AppProperties.Pdf.Protect` / `AppProperties.Pdf.Protect.Permissions` nested classes and the corresponding `app.pdf.protect.*` keys in `application.properties`

## Files added
- `pdf/dto/PdfProtectionRequest.java`, `pdf/dto/PdfProtectionErrorResponse.java`
- `pdf/exception/PdfProtectionValidationException.java` (→ 400), `PdfProtectionDisabledException.java` (→ 503)
- `pdf/config/PdfProtectionProperties.java` (`@ConfigurationProperties(prefix = "pdf.protection")`)
- `pdf/service/PdfProtectionService.java` (interface), `PdfProtectionServiceImpl.java`
- `src/test/java/.../pdf/service/PdfProtectionServiceImplTest.java` (9 tests)
- `postman/pdf_protect_v2.postman_collection.json`

## Files modified
- `pdf/controller/PdfProtectionController.java` — rewritten for the binary/JSON hybrid response
- `pom.xml` — added `springdoc-openapi-starter-webmvc-ui` (OpenAPI/Swagger UI, now live at `/v3/api-docs` and `/swagger-ui/index.html`)
- `common/config/AppProperties.java` — removed the now-unused `Protect` nested config
- `application.properties` — removed `app.pdf.protect.*`, added `pdf.protection.*`

## One deliberate simplification vs. the prior implementation
The prior `/protect` used a random, never-returned **owner** password distinct from the **user** (open) password, so PDF permission restrictions (print/copy/edit) held even after the document was opened. This request's own code sample uses the same value for both (`new StandardProtectionPolicy(password, password, accessPermission)`), which was followed literally — the new endpoint does not enforce any permission restrictions beyond the open password itself. Flagging this explicitly since it's a real (if narrow) security/feature difference, not an oversight; happy to reinstate the separate-owner-password approach if desired.

## Verified live (this session)
- Scenario 1 (`passwordProtected: false`) → 200, `application/pdf`, byte-identical passthrough of the decoded input.
- Scenario 2 (`passwordProtected: true, password: "Welcome@123"`) → 200, `application/pdf`; independently verified with Ghostscript: wrong password rejected, `Welcome@123` opens it.
- All 6 documented error cases (missing password, blank password, invalid Base64, non-PDF content, missing required fields ×2) → 400 with the exact `{success, message}` shape requested.
- `pdf.protection.max-file-size-mb=0` override → 400 "File exceeds configured maximum size of 0 MB".
- `pdf.protection.enabled=false` override → 503 "PDF protection is currently disabled".
- `/v3/api-docs` and `/swagger-ui/index.html` live, correctly documenting the new endpoint.
- Unrelated endpoints (`/api/files/checksum`, `/api/test/ping`) spot-checked unaffected.
- `mvn clean package` → BUILD SUCCESS (53 source files); `mvn test` (temporarily unskipped, then reverted, per this session's established procedure) → 20/20 tests pass (11 pre-existing `LogSanitizerTest` + 9 new `PdfProtectionServiceImplTest`).

## ⚠️ Stale artifacts from prior sessions — recommend follow-up

Because the previous `/protect` contract was already captured and certified as a stable baseline earlier in this engagement, the following documents now describe a contract that **no longer exists** and should be regenerated or explicitly annotated as historical before being relied on again:

- `docs/regression-baseline/pdf-protect/*` and `docs/regression-baseline/endpoints.md` / `api-inventory.md` / `openapi.json` (describe the old JSON request/response)
- `docs/api-contract-certification-report.md` (certified the old contract as unchanged; that certification no longer holds for this one endpoint)
- `postman/baseline_collection.json`'s `/pdf/protect` request (still sends the old `{name, dob, base64_docContent}` shape — will now get a `400` from Bean Validation, since `passwordProtected` is required and absent)
- `docs/adr/` — none of the existing ADRs need to change, but a new ADR documenting *this* replacement decision (mirroring the format of `ADR-001` through `ADR-005`) would keep the decision trail complete

This wasn't in scope for the current request (which asked for the endpoint implementation itself), so I didn't regenerate these — flagging so the next certification pass knows to include this endpoint.
