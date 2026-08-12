# Logging Policy

Source of truth: `docs/Logging_Assessment_Report.md` (findings) and `docs/logging-remediation-report.md` (what was implemented). This document is the durable policy going forward — apply it to any new logging added to this codebase.

## 1. Allowed Logging

At any level (INFO and above; DEBUG has a slightly wider allowance — see Environment Levels):

- Request ID / correlation ID (where available)
- File name (generated/sanitized — e.g. `20260702-131004_4f26242c_invoice.pdf`, never a raw user-controlled path)
- MIME type
- File size (bytes / human-readable)
- Processing duration
- HTTP status code
- Sanitized URL (scheme + host + path only — see `LogSanitizer.sanitizeUrl`, never the query string or fragment)
- Counts (e.g. "Listed 12 files")
- Status/outcome strings (SUCCESS, FAILURE, FILE_TOO_LARGE, etc.)
- Masked HTTP headers (see `LogSanitizer.maskHeaders`) — non-sensitive header names/values only; sensitive ones always show as `****MASKED****`
- Payload **presence** and **size** (e.g. `payloadPresent=true, payloadSize=2048`) — never the payload itself

## 2. Forbidden Logging

**Never**, at any level, including DEBUG:

- Base64 content (encoded document/file data)
- PDF content (raw or decoded)
- Any binary file content
- Passwords (including PDF-protection passwords derived from `name`/`dob`)
- Secret keys
- Tokens
- Authorization header values
- Cookie / Set-Cookie header values
- Date of birth or any other personal identifier (name, email, phone, etc.)
- Raw HTTP request bodies
- Raw HTTP response bodies
- Full/unsanitized URLs (query strings may carry tokens or signed-URL credentials)
- Arbitrary caller-supplied maps with no fixed schema (e.g. `Base64SaveRequest.extraParams`) — their contents can never be verified safe in advance

## 3. Environment Levels

| Environment | Level | Config file | Meaning |
|---|---|---|---|
| Development | `DEBUG` | `application-dev.properties` | Full internal execution-flow detail, HTTP request/response **metadata** (method, sanitized URL, masked headers, payload presence/size) — never body content, even here. |
| UAT / SIT / Staging | `INFO` | `application-uat.properties` | Normal operational events only: API received/completed, duration, sanitized file names, counts, status. |
| Production | `ERROR` | `application-prod.properties` | Failures and exceptions only. No payload, no Base64/PDF content, no secrets, no PII — ever. |

Activate a profile via `spring.profiles.active=dev|uat|prod` (e.g. `-Dspring.profiles.active=prod` or the `SPRING_PROFILES_ACTIVE` environment variable). The base `application.properties` file is unchanged and still applies in every environment; the profile files add only `logging.level.*` overrides on top of it.

**Note on the dedicated HTTP logger:** `log4j2-spring.xml` defines a named logger `com.twixor.base64convertor.http`, routed to its own `logs/http.log` file, independent of the console/root logger. Both `LoggingInterceptor` and `PdfService`'s manual HTTP logging now share this exact logger name (previously `LoggingInterceptor` used an unrelated SLF4J logger named `"HTTP_LOGGER"`, which silently bypassed `logs/http.log` entirely). Each profile file sets the level for this logger under both `logging.level.httpLogger` and `logging.level.com.twixor.base64convertor.http` to guarantee the level takes effect regardless of which key is checked.

## Ownership

Any new `logger.*` call added to this codebase must be checked against Section 1/2 above before merging. When in doubt: log presence/size/status, never content.
