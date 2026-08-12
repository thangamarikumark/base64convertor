# Design Review: API Observability & Version Metadata

**Type: Design review / assessment.** No code changed. Verified fresh against current source (grep confirmed zero MDC/ThreadContext usage, zero request filters/interceptors, zero `X-Request-Id`/`X-API-Version` handling anywhere in the codebase — this is genuinely greenfield, not a partial implementation to extend).

---

## Current State Assessment

### Response structures (re-confirmed from `docs/API_Response_Contract_Analysis.md`, still accurate)

| DTO | Shape | Has correlation/version metadata? |
|---|---|---|
| `PdfResponse` | `fileName`, `status` (String), `base64`, `mimeType`, `size` | No |
| `FileConvertResponse` | `processingId`, `fileName`, `mimeType`, `type`, `base64Data`, `success` (boolean), `message` | No (`processingId` is a job-tracking ID, not a request-correlation ID — different purpose) |
| `Base64SaveResponse` | `success`, `message`, `fileName`, `downloadLink`, `fileSize`, `savedAt` | No |
| `DecodedFileSaveResponse` | `success`, `message`, `fileName`, `originalFileName`, `downloadLink`, `fileSize`, `fileSizeBytes`, `mimeType`, `metadataFile`, `savedAt` | No |
| `ApiResponse<T>` (new, `/pdf/protect` only) | `status` (String), `message`, `data`, `timestamp`, `requestId` | **Yes — the only endpoint with any of this today.** `timestamp` is `Instant.now().toString()`; `requestId` is `UUID.randomUUID().toString()`, generated fresh per response |
| Checksum (ad-hoc `Map<String,String>`) | `checksum`, `nonce` | No |
| File download endpoints (`byte[]`/`String`) | Raw content, no envelope | No — and structurally can't carry a JSON field at all |

### Existing request correlation mechanisms
**None**, except the one just added to `/pdf/protect`. There is no incoming-header handling (`X-Request-Id` or otherwise), no filter/interceptor that generates or propagates a correlation ID, and no mechanism linking a client's request to anything beyond that single response object.

### Existing logging correlation IDs
**None.** Confirmed by grep: zero `MDC`/`ThreadContext` usage anywhere in `src/main/java`, and the `log4j2-spring.xml` pattern layout (`%d{...} [%t] %-5level %logger{36} - %msg%n`) has no `%X{...}` placeholder to even display one if it existed. This exact gap was independently flagged in the earlier logging-remediation review (`docs/Logging_Remediation_Independent_Review.md`, Phase 7/8) — this request is effectively asking to finally close it, which is good timing.

**Critical existing gap this proposal must account for:** `ApiResponse.requestId` (added for `/pdf/protect`) is generated with `UUID.randomUUID()` **inside the controller**, entirely disconnected from logging — none of `PdfProtectionController`'s own `logger.warn`/`logger.error` calls include it. A support engineer handed a `requestId` from a response today **cannot** grep the logs for it, because it never appears there. Any new metadata strategy should fix this, not repeat it.

### Versioning
There is no API version concept anywhere in the running application distinct from the Maven artifact version (`0.0.1-SNAPSHOT`, pre-release and not semantically meaningful as a public API version). No `management.info.*` properties are configured, so even `/actuator/info` exposes nothing today.

---

## Proposed Standard Response Metadata — Feasibility

The proposed `ApiMetadata{apiVersion, requestId, timestamp}` is essentially `ApiResponse`'s tail three fields, generalized. Structurally sound; two design choices need resolving before it's implementable cleanly (addressed below): **(a)** where it lives (own object vs. flattened vs. headers), and **(b)** how it's *populated* (which is the harder problem — see RequestId Strategy).

---

## Versioning Strategy Review

| Option | Assessment |
|---|---|
| **A — response field only** | Cheap, but invisible to anything that doesn't parse the body — API gateways, load balancers, monitoring dashboards, and CDN/cache layers commonly key off headers, not body content, for routing/observability decisions. Also unusable for the 7 raw `byte[]`/`String` file-download endpoints, which have no JSON body to put it in. |
| **B — response header only** | Universally attachable (works identically on JSON *and* binary/text responses — solves the file-download problem for free), inspectable by infrastructure without a body-parsing step, and trivially added via a single `Filter`/`HandlerInterceptor` with **zero DTO changes** — meaning zero risk of breaking any existing JSON consumer's deserialization, since the body is untouched. |
| **C — both** | Given B is nearly free once a filter exists, and A is genuinely useful for consumers who only look at the body (e.g. simple `fetch()`-based UIs that don't read response headers), C's marginal cost over B alone is small. |

**Recommended: Option C, implemented as B-first.** Build the header mechanism first (it's the part that generalizes across *every* endpoint including file downloads, and is genuinely zero-risk). Add the body field only where a JSON envelope already exists or is being introduced anyway — never force a new field into currently-header-only/bodyless responses just for the sake of symmetry.

---

## RequestId Strategy

| Option | Evaluation |
|---|---|
| Server-generated (`UUID.randomUUID()`) | What `/pdf/protect` does today. Simple, but **loses the ability to correlate a client-side request across a distributed call chain** — if this service is ever called by an upstream gateway/orchestrator that already has its own trace ID, generating a fresh one here discards that context. |
| MDC correlation ID | Correct *mechanism* for making the ID appear in logs, but MDC needs to be *populated* by something — it's the storage/propagation layer, not the source of the value. This is necessary infrastructure regardless of which ID-origin strategy is chosen below. |
| Incoming header reuse (`X-Request-Id`), generate only if absent | **Best practice, and the industry-standard pattern** (this is exactly what AWS ALB `X-Amzn-Trace-Id`, most API gateways, and OpenTelemetry's `traceparent` propagation all do): if a caller — or an upstream proxy/gateway in front of this service — already supplies `X-Request-Id`, honor it so the ID is meaningful across the *whole* call chain, not just this one hop. Only generate a new UUID when the header is genuinely absent (a direct, ungoverned client call). |

**Recommendation: incoming-header-reuse-with-fallback, implemented as a single Servlet `Filter`** (not per-controller code):
1. Read `X-Request-Id` from the incoming request.
2. If absent/blank, generate `UUID.randomUUID().toString()`.
3. Put it into MDC (`ThreadContext.put("requestId", id)` for Log4j2) for the duration of the request — this is what finally makes `requestId` show up in **every** log line during that request, not just the ones a controller manually threads it into.
4. Set it as a response header (`X-Request-Id`) unconditionally.
5. Clear MDC in a `finally` block (critical for thread-pool reuse — Log4j2's `ThreadContext` is thread-local and **will leak into the next request handled by the same pooled thread** if not cleared).
6. Make it available to controllers that want to include it in a body field (e.g. via `MDC.get("requestId")` or a request attribute) — so `ApiResponse.requestId` becomes "read from context," not "freshly generated in the controller," fixing the log-correlation gap noted above.

This is a genuinely small, well-isolated change: one new `Filter` class, registered once, touching zero existing controllers or DTOs.

---

## Timestamp Strategy

`Instant.now().toString()` (already the pattern used by `ApiResponse`) is correct and should remain the standard — it's UTC by construction (`Instant` has no timezone ambiguity, unlike `LocalDateTime`), ISO-8601 compliant, and matches what's already shipping on `/pdf/protect`. No change needed here; just confirming it as the standard going forward rather than reinventing a format per endpoint (this codebase already has enough response-shape fragmentation — see `API_Response_Contract_Analysis.md`).

**One nuance:** `Instant.now().toString()` produces variable-precision fractional seconds (e.g. `2026-07-02T11:20:21.611192799Z` was observed live from `/pdf/protect` earlier this session — 9 fractional digits, not the 3 shown in this request's own example `2026-07-02T20:45:12Z`). If exact consumer-facing formatting consistency matters (some JSON schema validators/consumers are strict about fractional-second digit count), consider `DateTimeFormatter.ISO_INSTANT` with `.withZone(ZoneOffset.UTC)` truncated to milliseconds — a one-line change, not a blocker, but worth deciding once rather than per-endpoint.

---

## Response Design Recommendation — `BaseResponse` (superclass) Feasibility

**Not recommended, for a structural reason specific to this codebase, not a general objection to inheritance:** Nearly every response DTO here (`PdfResponse`, `FileConvertResponse`, `Base64SaveResponse`, `DecodedFileSaveResponse`) uses **Lombok's `@Builder`**, and several also carry hand-written multi-arg constructors (`PdfResponse` has three overloaded constructors, one of them — `PdfResponse(fileName, success, base64)` — a pre-existing dead/empty constructor doing nothing at all, confirmed in source). Retrofitting `extends BaseResponse` onto these:
- Requires every `@Builder`-annotated subclass to either add `@SuperBuilder` (a **mechanical rewrite** of every builder call site across every controller/service that constructs one of these — a non-trivial, error-prone, all-at-once change) or abandon the builder pattern for a constructor-based one (equally invasive).
- Provides no benefit that composition doesn't already provide, since none of these DTOs are used polymorphically (nothing anywhere treats `PdfResponse` and `FileConvertResponse` as interchangeable `BaseResponse` instances) — inheritance here would be structural mimicry, not a real "is-a" relationship being exploited.

**Recommend composition instead**, mirroring what `ApiResponse<T>` already does successfully:
```java
public class ApiMetadata {
    private String apiVersion;
    private String requestId;
    private String timestamp;
}
```
embedded as a field (`private ApiMetadata metadata;`) inside whichever response type wants it — additive, no inheritance hierarchy change, no builder-pattern rewrite, and it's the exact shape `ApiResponse<T>` would need to add anyway to carry `apiVersion` without further bloating its own flat field list.

---

## Alternative Design — Headers Only (No DTO Changes At All)

| | Pros | Cons |
|---|---|---|
| **Headers only** (`X-Request-Id`, `X-API-Version`, `X-Response-Time`) | Zero DTO changes → **zero risk of breaking any existing JSON deserialization**, anywhere, for any consumer, full stop. Works uniformly on *every* response type in this app including the 7 raw binary/text endpoints, which is otherwise unsolvable via body fields. Implementable as a single filter, fully decoupled from business logic — no controller needs to know it exists. | Invisible to consumers that only read the parsed JSON body (common in simple frontend `fetch().then(r => r.json())` code that never touches `r.headers`). Less discoverable in ad-hoc API exploration (Swagger UI shows headers, but less prominently than body fields). |
| **Body fields only** | Self-documenting in the JSON payload itself; shows up automatically in OpenAPI schema generation (`springdoc`, already added to this project for `/pdf/protect`). | Requires a JSON envelope to exist at all (impossible for the 7 raw endpoints without changing what kind of response they are) and requires per-DTO changes if not using a shared envelope. |

**Consumer impact of headers-only:** effectively none for existing consumers — **adding a new response header never breaks an existing client**, unlike adding a body field, which *can* break a consumer using strict/closed-schema deserialization (rare in JSON-based systems, since most JSON libraries ignore unknown fields by default — but this codebase's own `Base64SaveRequest` uses `@JsonAnySetter` specifically because unknown-field handling has been a live concern here before, so it's not a purely theoretical risk).

**Monitoring benefit:** headers are what API gateways, reverse proxies, and APM tools (Datadog, New Relic, OpenTelemetry collectors) actually key off for distributed tracing — a body-only `requestId` is invisible to all of that tooling, which only sees headers unless it's specifically configured to parse response bodies (rare, and expensive to do generically).

---

## Compatibility Review

| Consumer type | Impact of adding response headers | Impact of adding body fields (via `ApiMetadata` composition, additive) |
|---|---|---|
| Postman collections | None — Postman doesn't fail on new headers or new body fields | None, **provided no existing assertion does exact/strict body-shape matching** (e.g. `pm.expect(Object.keys(body)).to.eql([...])` — none of the existing test scripts in `postman/baseline_collection.json` or `postman/pdf_protect_v2.postman_collection.json` do this kind of strict key-set assertion, confirmed by inspection; they check individual fields, which is safe against additive changes) |
| Angular UI / any frontend using typed models (TypeScript interfaces) | None from headers. From body fields: **safe only if the frontend's TypeScript interfaces don't use structural-typing strict-excess-property checks in a context that fails on unknown properties** — normal `JSON.parse()`-based consumption is unaffected either way; only an issue if some consumer explicitly validates against a closed JSON Schema | Same caveat |
| Mobile apps | Same reasoning — additive JSON fields are safe for essentially all standard JSON parsers (Gson, Jackson, Moshi, `Codable` with default settings all ignore unknown keys); risk only exists for explicitly-strict decoders | Same |
| Middleware / workflow engines | Depends entirely on whether any existing middleware does schema-validated routing keyed to an exact response shape — **this is the one category worth explicitly asking about before rollout**, since business process/workflow engines (unlike typical REST clients) sometimes *do* validate against a fixed schema as part of their configuration | Same |
| External consumers | Same additive-safety reasoning applies, but **external consumers are the ones you have the least visibility into** — this is where "backward compatible in principle" and "verified backward compatible in practice" diverge most; recommend rolling out to external-facing endpoints last, after internal validation | Same |

**Overall: adding fields/headers is additive and low-risk by construction**, but "low-risk" is not "zero-risk" — the one genuine risk category is any consumer (internal middleware most likely) doing closed/strict schema validation. This should be confirmed, not assumed, before a wide rollout — see Migration Plan.

---

## File Download Endpoint Review

**Recommendation: headers only, never body, for the 7 raw endpoints** (`download-decoded/{fileName}`, `download/{fileName}`, `content/{fileName}`, `metadata/{fileName}`, `DELETE /{fileName}`, and by extension `/api/test/ping`/`/echo`). This isn't a compromise — it's the *only* structurally valid option, since these endpoints' bodies are literally file bytes or a passthrough string with no room for a JSON object without changing what the endpoint fundamentally returns (turning a "download a PDF" endpoint into "download a JSON envelope containing a PDF" is exactly the kind of unrequested contract change this task explicitly wants to avoid). `X-API-Version`, `X-Request-Id`, and (if desired) `X-Response-Time` on these endpoints costs nothing and requires no DTO or controller change if implemented via the same filter proposed above.

---

## Future-Proofing Review

`apiVersion` as a plain string field (`"1.2.0"`, bumped to `"1.3.0"`, etc.) supports incremental evolution **without** requiring `/api/v2` path versioning, *as long as* the versioning discipline stays additive (new optional fields, new optional endpoints) rather than requiring breaking changes to existing fields. The moment a genuinely breaking change is needed for one endpoint (exactly what happened with `/pdf/protect` three times this session — request shape, response shape, then field rename), a body-level `apiVersion` string **documents** that a breaking change occurred but does **not prevent consumers from being broken by it** — it's an observability aid, not a compatibility guarantee. **This is the core limitation to be explicit about**: `apiVersion` in the body tells a consumer *after the fact* which contract they're looking at; it does not, by itself, let old and new contracts coexist the way `/v1` vs. `/v2` path segments would. For this codebase specifically — which has already demonstrated a pattern of in-place breaking changes to `/pdf/protect` rather than versioned coexistence — `apiVersion` is best understood as a **changelog/diagnostic marker**, not a substitute for the "should this be a new path or an in-place replacement" decision each breaking change already requires (and has, in this engagement, been explicitly surfaced to you for a decision each time).

---

## Recommended Enterprise Standard

**Layered design, in priority order:**

1. **`RequestCorrelationFilter`** (new, single class, `common/config` or a new `common/filter` package) — a `OncePerRequestFilter` that: reads/generates `X-Request-Id`, populates Log4j2 `ThreadContext` (`requestId` key), sets the response header, clears context in `finally`. Zero controller/DTO changes. This is the foundational piece — everything else depends on it existing.
2. **Log pattern update** — add `%X{requestId}` to the `PatternLayout` in `log4j2-spring.xml` (the one place a code-adjacent-but-not-Java change is needed) so every log line during a request now shows its correlation ID, closing the exact gap flagged in the earlier logging review.
3. **Response headers on every endpoint** (via the same filter, or a thin `ResponseHeaderFilter` if kept separate from correlation logic): `X-Request-Id` (from step 1), `X-API-Version` (a fixed value sourced from one place — e.g. a `@Value("${app.api.version}")` property, not hardcoded per-controller), and optionally `X-Response-Time` (requires wrapping request start-time capture, slightly more filter logic but still fully generic).
4. **`ApiMetadata` composition object**, added to `ApiResponse<T>` (already exists, one-field addition: `apiVersion`) and offered as an opt-in addition to any *other* response DTO that wants it — **not retrofitted onto all of them at once**, and never via a `BaseResponse` superclass (see feasibility finding above).
5. **`application.properties`**: `app.api.version=1.0.0` — one source of truth for the version string, read by both the header-setting filter and any DTO wanting to embed it, so it's changed in exactly one place per release.

This combination directly satisfies every stated requirement: backward compatible (additive only, headers always safe, body changes opt-in per-endpoint), no endpoint versioning required, no existing consumer's parsing logic is forced to change, and it's extensible (new metadata fields later just mean growing `ApiMetadata` or adding another header, never a new endpoint).

---

## Deliverables

### 1. Compatibility assessment
Additive-by-construction; genuinely low risk for the vast majority of realistic consumers (standard JSON parsers ignore unknown fields; new headers are always safe). One residual, not-yet-verified risk category: any middleware/workflow engine doing closed-schema validation — see Migration Plan step 0.

### 2. Risk analysis
| Risk | Severity | Mitigation |
|---|---|---|
| Thread-local (`ThreadContext`/MDC) leak across pooled threads if not cleared | Medium — silent, hard-to-diagnose bug class if done wrong (wrong `requestId` attributed to unrelated requests) | Mandatory `finally`-block clear in the filter; this is a correctness requirement, not optional |
| A closed-schema consumer breaks on new body fields | Low probability, but unverified — see below | Confirm before wide rollout (Migration Plan step 0); headers-first rollout sidesteps this risk entirely for the observability goal |
| `BaseResponse` inheritance retrofit (if pursued despite the recommendation above) | High — mechanical, error-prone, all-at-once rewrite across every `@Builder`-based DTO and every call site | Avoided by recommending composition instead |
| Version string drift (hardcoded per-controller) | Low but real — exactly the kind of duplication this codebase's own logging remediation review flagged elsewhere (e.g. the duplicated masking/sanitization logic before it was centralized) | Single `app.api.version` property, read from one place |

### 3. Recommended DTO structure
`ApiMetadata{apiVersion, requestId, timestamp}` as a **composed field**, not an inherited base class. Already-partially-built via `ApiResponse<T>` (`/pdf/protect`) — extend that with `apiVersion`, offer `ApiMetadata` as an optional addition elsewhere, never a blanket retrofit.

### 4. Recommended header strategy
`X-Request-Id`, `X-API-Version`, optionally `X-Response-Time`, set uniformly by one filter, on **every** response including the 7 file-download/raw endpoints where headers are the *only* viable mechanism.

### 5. Request correlation strategy
Reuse incoming `X-Request-Id` if present; generate `UUID.randomUUID()` only if absent. Implemented once, in the filter — never per-controller.

### 6. Logging integration approach
Log4j2 `ThreadContext` populated by the same filter; `%X{requestId}` added to the pattern layout. This is the single highest-value piece of this whole proposal, since it's the one change that actually makes `requestId` *useful* for support/debugging (searchable in logs), not just present in a response a client may or may not have kept.

### 7. File-download endpoint approach
Headers only (§ above) — not optional, structurally required given these endpoints' bodies are raw content.

### 8. Migration plan
- **Step 0 (before any rollout):** confirm with any known internal middleware/workflow-engine consumers whether they do closed/strict schema validation on response bodies. This is the one open question standing between "low risk" and "verified safe."
- **Step 1:** ship `RequestCorrelationFilter` + log pattern update. Zero consumer-visible change beyond a new response header and richer logs — safe to ship immediately, independent of everything else.
- **Step 2:** add `X-API-Version` header (same filter or a sibling one) + `app.api.version` property.
- **Step 3:** extend `ApiResponse<T>` with `apiVersion`, sourced from the correlation filter's context/the property — `/pdf/protect` gets full metadata with no further per-endpoint work.
- **Step 4 (optional, per-endpoint, explicitly approved individually — matching this engagement's established working pattern for any response-shape change):** offer `ApiMetadata` as an addition to other response DTOs, one endpoint at a time, each its own reviewed decision — not a blanket sweep.

### 9. Final verdict

# IMPLEMENT WITH CONDITIONS

**Conditions:**
1. Build the header + filter + MDC/logging layer **first**, as fully general, zero-DTO-touching infrastructure — this alone delivers the majority of the stated observability goal (traceability, correlation, version visibility) with effectively zero compatibility risk.
2. Do **not** adopt a `BaseResponse` inheritance model — composition (`ApiMetadata` as a field) avoids a costly, error-prone rewrite of every `@Builder`-based DTO in the app for no behavioral benefit.
3. Treat body-field `apiVersion`/`ApiMetadata` additions as **opt-in, per-endpoint, individually-approved changes** — exactly the pattern already established and working well in this engagement (the `/pdf/protect` response shape itself changed three times this session, each time as an explicit, scoped decision) — not a single sweeping change across every response DTO.
4. Confirm the one unverified compatibility risk (closed-schema middleware/workflow-engine consumers) before extending body-field changes beyond `/pdf/protect`, even though the risk is assessed as low.
5. Understand and document `apiVersion`'s actual limitation: it's a diagnostic/changelog marker, not a mechanism for old/new contracts to coexist — it does not replace the per-change "new path vs. in-place replacement" decision this codebase has correctly been treating as its own explicit choice each time.
