# PDF Delivery Forwarding — Configurability & Reuse Design Proposal

**Type: Design proposal / analysis only.** Nothing in this document has been applied to the codebase. Code samples below are illustrative — they show the *shape* of the proposed solution, not a diff to be merged as-is. Grounded in the current, live source (`PdfDeliveryController`, `PdfDeliveryFacade`, `TargetApiRequestMapper`, `PdfRequest`, `TargetApiRequest`) as of this session, not assumed from memory.

---

## 1. Current Flow Analysis

### 1.1 End-to-end sequence diagram

```
Client
  │  POST /api/files/pdf/send  (List<PdfRequest>)
  │  POST /api/files/pdf/single (PdfRequest)
  ▼
PdfDeliveryController
  │  for each PdfRequest:
  │    catches PdfDeliveryFacade.PdfDeliveryException, maps to
  │    PdfResponse + status code (per endpoint's own convention)
  ▼
PdfDeliveryFacade  (deliverAlways / deliverIfTargetPresent)
  │
  │ 1. pdfService.fetchAndConvertToBase64(req.getUrl(), req.getCookie(), req.getPayload())
  ▼
PdfService  ──POST──►  Source File Host (req.getUrl(), external)
  │ (returns Base64 string of fetched bytes)
  ◄──────────────────────────────
  │
  │ 2. inject base64 into req.message.content.attachment.attachmentData (if attachment present)
  │ 3. targetApiRequestMapper.toTargetApiRequest(req)   → builds TargetApiRequest
  │ 4. targetApiRequestMapper.buildTargetHeaders(req, defaultToken) → builds HttpHeaders
  │        (Authorization: req.target_auth, or "Bearer " + app.default.auth.token if absent)
  │ 5. objectMapper.writeValueAsString(targetApiRequest)
  │
  ▼
RestTemplate.postForEntity(req.getTarget_url(), entity, String.class)
  │
  ▼
Target API  (req.getTarget_url(), external — e.g. a chat/messaging gateway)
  │
  ◄── response discarded (only used to detect failure) ──
  │
  │ finally: req.message.content.attachment.attachmentData = null  (memory hygiene)
  ▼
PdfDeliveryFacade returns DeliveryResult(fileName, mimeType, base64)  [success]
  or throws PdfDeliveryException(fileName, mimeType, cause)          [failure]
  ▼
PdfDeliveryController builds PdfResponse(fileName, "SUCCESS"|"FAILED: ...", base64, mimeType)
  ▼
Client
```

### 1.2 Components involved in fetch → convert → forward

| Component | Role |
|---|---|
| `PdfDeliveryController` | HTTP entrypoint for `/send` (batch) and `/single`; maps facade outcomes to `PdfResponse`/status codes |
| `PdfDeliveryFacade` | Orchestrates the whole workflow; **owns the forwarding call itself** (`restTemplate.postForEntity(req.getTarget_url(), ...)`) |
| `PdfService` | Fetches the source file and Base64-encodes it (the "fetch → convert" half only — no forwarding knowledge) |
| `TargetApiRequestMapper` | Maps `PdfRequest` → `TargetApiRequest` (the outbound messaging payload shape) and builds outbound headers (**owns the auth-header decision**: `target_auth` from the request, or a single global fallback token) |
| `PdfRequest` | Request DTO — carries `url`/`cookie`/`payload` (fetch inputs) **and** `target_url`/`target_auth` (forwarding inputs) in one flat structure |
| `TargetApiRequest` | The messaging-gateway-shaped payload actually sent to the target |
| `RestTemplate` (shared `@Primary` bean, `UnsafeRestTemplate`) | The actual HTTP client used for both the fetch and the forward — same instance, same trust-all-SSL/retry-adjacent config for both very different kinds of calls |
| `app.default.auth.token` (`application.properties`) | The **only** existing piece of "target configuration" — a single, global bearer token with no association to any particular target system |

### 1.3 Differences between `/pdf/send` and `/pdf/single`

| Aspect | `/pdf/send` (`deliverAlways`) | `/pdf/single` (`deliverIfTargetPresent`) |
|---|---|---|
| Input shape | `List<PdfRequest>` (batch) | Single `PdfRequest` |
| Forwarding condition | **Always** attempts to POST to `target_url` (even if blank/null — would fail with a `RestClientException` from an invalid URL) | Only POSTs if `target_url` is **non-blank** — silently skips forwarding otherwise |
| Failure handling | Per-item try/catch inside a loop — one item's failure doesn't stop the batch | Single try/catch — the whole request either succeeds or fails |
| `mimeType` on failure | Preserved via `PdfDeliveryException.mimeType` even if only the attachment-injection step succeeded before the forward failed | Same mechanism, but `deliverIfTargetPresent`'s catch path always sets `mimeType = null` (asymmetry already present in the original implementation, preserved intentionally per the Phase A "do not fix existing behavior" constraint) |
| Response shape | `List<PdfResponse>`, always `200` (batch semantics — the list itself always returns; individual item failures are encoded as `"FAILED: ..."` strings inside items) | Single `PdfResponse`, status code varies: `200` success / `502` (`RestClientException`) / `500` (other) |

**This duplication (the forward-or-not condition aside, the entire mapper/header/POST sequence) is copy-pasted between the two facade methods almost verbatim** — this is the core of the coupling problem addressed below.

---

## 2. Coupling Assessment

### 2.1 Tightly-coupled forwarding logic — exact locations

| Location | What's coupled |
|---|---|
| `PdfDeliveryFacade.deliverAlways` (lines ~81–87) | Direct `restTemplate.postForEntity(req.getTarget_url(), entity, String.class)` call — the target URL, HTTP method (hardcoded `POST`), and transport (`RestTemplate`) are all fixed in the orchestration method itself, not delegated to any reusable abstraction |
| `PdfDeliveryFacade.deliverIfTargetPresent` (lines ~116–122) | Identical hardcoded `postForEntity` call, duplicated |
| `TargetApiRequestMapper.buildTargetHeaders` | Auth mechanism is hardcoded to exactly one shape: `Authorization: Bearer <token>` (or the caller's raw `target_auth` string substituted wholesale as the header value). No support for Basic Auth, API-Key headers, or "no auth" as a first-class option — a caller wanting a different scheme must construct the entire header value themselves and pass it as `target_auth`, which the code trusts and sets verbatim. |
| `PdfRequest.target_url` / `target_auth` fields | The *target itself* is defined per-request, by the caller, in cleartext, with no association to a named/configured system. There is no concept of "the WhatsApp target" or "the SMS gateway target" anywhere in the code — every request reinvents the destination from scratch. |
| `app.default.auth.token` (single global property) | The only "configuration" that exists is one flat token, used as a fallback for *every* target regardless of which system is actually being called |
| Retry/timeout for the forward call | `RestTemplate`'s connect/response timeouts (`app.http.connect-timeout-seconds`/`app.http.response-timeout-seconds`) are shared with **every other outbound call in the entire application** (source-file fetches, PDF-protect has none, etc.) — there is no way to give the forwarding call to a slow target system a different timeout/retry policy than a fast internal file fetch |
| SSRF/allowlist validation | `UrlAllowlistValidator` is applied to the **source** `url` (via `PdfService.fetchAndConvertToBase64`) but is **never applied to `target_url`** — confirmed by reading `PdfDeliveryFacade`: no `urlAllowlistValidator.validate(req.getTarget_url())` call exists anywhere in the forwarding path |

### 2.2 Duplicated code between the two endpoints

| Duplicated concern | `deliverAlways` | `deliverIfTargetPresent` |
|---|---|---|
| Fetch + attachment injection | Lines 71–79 | Lines 108–114 (near-identical, `deliverAlways` also captures `mimeType` inline, `deliverIfTargetPresent` derives it separately afterward) |
| Build `TargetApiRequest` + headers | Lines 81–82 | Lines 116–118 |
| Serialize + POST | Lines 83–87 | Lines 117–122 |
| `attachmentData` cleanup in `finally` | Lines 93–97 | Lines 133–137 (byte-for-byte identical block) |
| Exception wrapping into `PdfDeliveryException` | Line 92 | Line 132 |

**Net assessment:** roughly 70% of the two methods' bodies are line-for-line duplicated; the only genuine behavioral difference is the forward-condition (`always` vs. `if target_url present`) and the resulting `mimeType`-on-failure asymmetry.

### 2.3 Classes impacted by this coupling

| Layer | Class | Impact |
|---|---|---|
| Controller | `PdfDeliveryController` | Owns none of the forwarding logic directly today, but its two methods' divergent error-handling (status codes, always-vs-conditional) exist *because* the facade methods below it duplicate rather than share logic |
| Facade | `PdfDeliveryFacade` | The primary owner of the coupling — contains the hardcoded `RestTemplate.postForEntity` calls, duplicated across two methods |
| Mapper | `TargetApiRequestMapper` | Owns the single-shape auth-header logic; would need to become channel/target-aware rather than request-field-aware |
| DTO | `PdfRequest` | Carries `target_url`/`target_auth` as flat, ungoverned strings — the shape that needs to change if a target-identifier model is adopted (see §7) |
| DTO | `TargetApiRequest` | The messaging-shaped payload itself is fine as-is (it's channel content, not forwarding mechanics) but is currently built and serialized inline inside the facade rather than by a reusable forwarding component |
| Util | `UrlAllowlistValidator` | Currently only wired to the source-fetch path; a configurable-forwarding design must also apply it (or a target-specific equivalent) to whichever URL actually gets called |
| Config | `AppProperties` | Has no representation of "a target system" at all today — only the single flat `app.default.auth.token` |

---

## 3. Configurable Design Proposal

A configurable forwarding framework should support all of the following without further code changes when a new target is added:

1. **Enable/disable** — a single kill-switch (`forwarding.enabled=false`) to disable all outbound forwarding app-wide, e.g. for a DR/read-only mode, without touching request handling elsewhere.
2. **Multiple named targets** — each target (e.g. `whatsapp`, `sms`, `email-gateway`) configured once, referenced by a short code from the request, not reconstructed per-call.
3. **Environment-specific URLs** — each target's `url` naturally varies per Spring profile (`application-dev.properties`/`-uat`/`-prod`, following the pattern already established in this codebase for logging levels), with zero code change between environments.
4. **Configurable auth mechanisms** — `NONE`, `BEARER`, `BASIC`, `API_KEY`, selected per target via an enum, with mechanism-specific properties (token / username+password / header-name+key).
5. **Configurable headers** — an arbitrary per-target `Map<String,String>` of additional static headers (e.g. `X-Client-Id`, `X-Partner-Code`) merged with the auth header and content-type.
6. **Configurable timeout/retry per target** — distinct from the general-purpose `app.http.*`/`app.retry.*` settings already used for source-file fetching, since a chat gateway and a source file host have no reason to share a timeout budget.
7. **Extensibility for new channels without code changes** — adding a new target is a **config-only** change (new YAML/properties block); adding a fundamentally new *auth mechanism* is a small, additive code change to one `AuthStrategy` implementation, never touching the controller/facade/mapper.

---

## 4. Configuration Model

This project currently uses `application.properties` + profile-specific `application-{dev,uat,prod}.properties` (not YAML) — both are shown below for completeness, since `.properties` is what this codebase actually uses today, and the task explicitly asked for a `.yml`-style structure.

### 4.1 `application.yml` (as requested by the task)

```yaml
forwarding:
  enabled: true
  default-timeout-ms: 5000
  default-retry:
    max-attempts: 3
    initial-delay-ms: 500
    multiplier: 2.0

  targets:
    whatsapp:
      url: https://gateway.internal.example.com/api/whatsapp/send
      auth-type: BEARER
      bearer:
        token: ${WHATSAPP_GATEWAY_TOKEN}
      headers:
        X-Client-Id: base64convertor
      timeout-ms: 8000
      retry:
        max-attempts: 2
        initial-delay-ms: 1000
        multiplier: 2.0

    sms:
      url: https://sms-provider.example.com/v2/messages
      auth-type: API_KEY
      api-key:
        header-name: X-Api-Key
        key: ${SMS_PROVIDER_API_KEY}
      headers: {}
      timeout-ms: 5000

    legacy-partner:
      url: https://partner.example.com/webhook
      auth-type: BASIC
      basic:
        username: ${PARTNER_USERNAME}
        password: ${PARTNER_PASSWORD}
      headers:
        X-Legacy-Version: "1"

    internal-test-sink:
      url: http://localhost:9090/api/test/echo
      auth-type: NONE
      headers: {}
```

### 4.2 Equivalent `application-{env}.properties` (matches this project's actual convention)

```properties
# application-prod.properties (additive to the existing logging.level.* entries)
forwarding.enabled=true
forwarding.default-timeout-ms=5000
forwarding.default-retry.max-attempts=3
forwarding.default-retry.initial-delay-ms=500
forwarding.default-retry.multiplier=2.0

forwarding.targets.whatsapp.url=https://gateway.internal.example.com/api/whatsapp/send
forwarding.targets.whatsapp.auth-type=BEARER
forwarding.targets.whatsapp.bearer.token=${WHATSAPP_GATEWAY_TOKEN}
forwarding.targets.whatsapp.headers.X-Client-Id=base64convertor
forwarding.targets.whatsapp.timeout-ms=8000

forwarding.targets.sms.url=https://sms-provider.example.com/v2/messages
forwarding.targets.sms.auth-type=API_KEY
forwarding.targets.sms.api-key.header-name=X-Api-Key
forwarding.targets.sms.api-key.key=${SMS_PROVIDER_API_KEY}
```

Secrets (`${WHATSAPP_GATEWAY_TOKEN}`, etc.) are environment-variable placeholders — see §8.5 for why these must never be literal values in a committed properties file (the existing `app.default.auth.token=q4ynVHA2s4d3unfWY2ujrQ==` hardcoded secret in this repo's `application.properties` is exactly the anti-pattern this design must not repeat).

---

## 5. Refactoring Recommendation

```
┌─────────────────────┐
│ PdfDeliveryController│  (unchanged responsibility: HTTP in/out, status mapping)
└──────────┬───────────┘
           │
┌──────────▼───────────┐
│  PdfDeliveryFacade    │  (unchanged responsibility: fetch + attachment injection orchestration)
└──────────┬───────────┘
           │  delegates forwarding to
┌──────────▼───────────┐
│   ForwardingService    │◄─────────────┐
│ (interface)            │              │
└──────────┬───────────┘              │
           │ uses                       │ implements
┌──────────▼───────────┐    ┌───────────┴──────────┐
│    TargetResolver      │    │ RestTemplateForwarding│
│ (targetCode -> config)  │    │       Strategy          │
└──────────┬───────────┘    └──────────────────────┘
           │ reads
┌──────────▼───────────┐
│   TargetApiConfig       │  (@ConfigurationProperties("forwarding"))
│  (targets map, enabled,│
│   default timeout/retry)│
└──────────┬───────────┘
           │ each target entry has
┌──────────▼───────────┐
│    AuthStrategy          │  (interface: NONE/BEARER/BASIC/API_KEY implementations)
│  applyAuth(HttpHeaders) │
└──────────────────────┘
```

| Component | Responsibility |
|---|---|
| **`TargetApiConfig`** | `@ConfigurationProperties(prefix = "forwarding")` — pure data holder for `enabled`, `defaultTimeoutMs`, `defaultRetry`, and `Map<String, TargetConfig> targets` (each `TargetConfig`: `url`, `authType`, auth-specific sub-objects, `headers`, `timeoutMs`, `retry`). No logic — this is the config model from §4 bound to Java. |
| **`TargetResolver`** | Given a `targetCode` (e.g. `"whatsapp"`), looks up the matching `TargetConfig` from `TargetApiConfig`. Throws a clear, typed exception (`UnknownTargetException`) if the code isn't configured — replacing today's silent "just POST to whatever `target_url` string the caller sent." |
| **`AuthStrategy`** (interface, one implementation per `authType`) | `void apply(HttpHeaders headers, TargetAuthConfig config)`. Four implementations: `NoAuthStrategy` (no-op), `BearerAuthStrategy`, `BasicAuthStrategy` (Base64-encodes `username:password` per RFC 7617), `ApiKeyAuthStrategy` (sets a configurable header name to the configured key). Selected via a small `Map<AuthType, AuthStrategy>` (Spring injects all beans of the interface, keyed by their declared `AuthType`) — adding a fifth auth type is a single new `@Component` class, zero changes to any caller. |
| **`ForwardingStrategy`** (interface) | `ResponseEntity<String> forward(TargetConfig target, Object payload)`. One production implementation, `RestTemplateForwardingStrategy`, wraps `RestTemplate` with the target's own timeout/retry config (via a per-call `RequestConfig` override or a small per-target `RestTemplate` cache) rather than sharing the app-wide fetch client's settings. This is also the seam where a future channel needing a fundamentally different transport (e.g. an SDK-based push-notification channel instead of raw HTTP) would plug in as a second implementation, selected by target config rather than by new controller code. |
| **`ForwardingService`** (interface, the facade-facing entrypoint) | `ForwardingResult forward(String targetCode, TargetApiRequest payload)`. Internally: resolve target via `TargetResolver` → build headers via the matching `AuthStrategy` + target's static `headers` map → delegate the actual call to `ForwardingStrategy` → wrap success/failure into a `ForwardingResult` (mirrors today's `PdfDeliveryFacade.DeliveryResult`/`PdfDeliveryException` split, but generic and reusable). This single method **replaces both duplicated `postForEntity` blocks** in `PdfDeliveryFacade`. |
| **`PdfDeliveryFacade`** (revised) | Unchanged fetch/attachment-injection responsibility; forwarding calls become one line: `forwardingService.forward(targetCode, targetApiRequest)`, with the `always`-vs-`if-present` distinction between `/send` and `/single` now expressed as "always resolve+forward" vs. "resolve+forward only if a target code was supplied" — the same shape as today, just delegated instead of duplicated. |
| **`TargetApiRequestMapper`** (revised) | Keeps its `PdfRequest → TargetApiRequest` payload-shaping responsibility (channel content is unrelated to forwarding mechanics) but **loses** `buildTargetHeaders` entirely — that responsibility moves into `ForwardingService`/`AuthStrategy`. |
| Channel-specific implementations | **Not needed as separate classes for now.** The messaging-payload shape (`TargetApiRequest`) is already channel-agnostic in this codebase (one shape used for whatsapp/sms/etc. alike via the existing `message.channel` field) — introducing per-channel *payload* classes would be premature. The extensibility point that *is* needed is per-target *transport/auth* configuration, which `TargetConfig` + `AuthStrategy` already cover. Revisit channel-specific payload mapping only if a genuinely different wire format is required by a future target. |

---

## 6. Backward Compatibility

**Existing clients continue working** if the migration follows this sequence:

1. **Phase 1 (additive):** Introduce `TargetApiConfig`, `TargetResolver`, `AuthStrategy`, `ForwardingStrategy`, `ForwardingService` as new classes. `PdfDeliveryFacade` is *not yet* changed to use them. Zero behavior change, zero risk — this is pure scaffolding.
2. **Phase 2 (dual-path):** `PdfRequest` gains an **optional** new field (e.g. `target_code`, see §7). `PdfDeliveryFacade` is updated: if `target_code` is present, resolve via `ForwardingService`; **if absent, fall back to exactly today's behavior** (raw `target_url`/`target_auth` from the request, via a small `AdHocTargetConfig` built on the fly to satisfy the same `ForwardingService.forward(...)` signature). This means **every existing caller who sends `target_url`/`target_auth` exactly as before gets identical behavior**, with zero required changes on their end.
3. **Phase 3 (opt-in migration):** New/updated integrations are given target codes and migrate to `target_code` at their own pace. Existing integrations are not forced to change.
4. **Phase 4 (optional, future, requires explicit approval):** If/when it's acceptable to require all callers to use `target_code`, the raw `target_url`/`target_auth` fields can be deprecated (still accepted, logged as deprecated-usage) and eventually removed — a **breaking change**, gated behind its own decision, not part of this proposal's initial scope.

**Breaking changes in this proposal as scoped (Phases 1–3): none.** `target_url`/`target_auth` continue to work exactly as today for as long as needed. The only genuinely breaking change (removing raw `target_url` support) is explicitly deferred to an optional, separately-approved Phase 4.

---

## 7. API Contract Changes

| Field | Recommendation | Reasoning |
|---|---|---|
| `target_url` | **Keep, but deprecate for new integrations.** Do not remove in this iteration (see §6 Phase 4). | Removing it now is a breaking change with no compensating benefit for existing callers; keeping it as a fallback is what makes Phases 1–3 non-breaking. |
| `target_auth` | **Keep, but deprecate for new integrations**, and continue treating it as an opaque, caller-supplied `Authorization` header value exactly as today. | Same reasoning as `target_url` — this field's *current* behavior (trusting the caller's raw string) is also the least secure part of the current design (see §8.3); deprecating rather than immediately removing preserves compatibility while the safer path is rolled out. |
| **New: `target_code`** | **Introduce as an optional field**, e.g. `"target_code": "whatsapp"`. When present, it takes precedence over `target_url`/`target_auth` (see below) and resolves against `TargetApiConfig`. | This is the mechanism that actually delivers "configurable and reusable" — a caller says *which* target, not *how* to reach it. |
| **Configuration vs. request precedence** | **When `target_code` is present, configuration wins entirely** — `target_url`/`target_auth` in the same request are ignored (not merged) if `target_code` is also present, to avoid an ambiguous "which one wins" situation. **When `target_code` is absent, today's raw `target_url`/`target_auth` behavior applies unchanged** (backward-compat fallback per §6). | A hybrid "configuration provides the URL but the request's `target_auth` overrides the configured auth" mode is explicitly **not recommended** — it reintroduces exactly the credential-trust problem this proposal exists to close (§8.3), for no compatibility benefit (a caller migrating to `target_code` has, by definition, already stopped needing to supply its own auth). |

---

## 8. Security Review

### 8.1 Risks of allowing arbitrary `target_url`

Today, `PdfDeliveryFacade` calls `restTemplate.postForEntity(req.getTarget_url(), entity, String.class)` with **zero validation** of `target_url` — confirmed by reading the current source; `UrlAllowlistValidator` is applied to the *source* `url` in `PdfService`, but never to `target_url`. This means:
- A caller can direct this server to POST an arbitrary JSON payload (containing the fetched file's Base64 content) to **any host reachable from the server**, including internal-network-only services, cloud metadata endpoints (`169.254.169.254`), or `localhost`-bound admin interfaces.
- Because the payload includes the Base64-encoded fetched file, this is also a **data-exfiltration primitive**, not just a routing concern — an attacker who controls both `url` (source) and `target_url` (destination) can use this server as a relay to move data from one location to another under the server's own network identity/credentials.

### 8.2 SSRF concerns

This is a textbook **Server-Side Request Forgery** pattern: user-controlled `target_url`, server-side outbound request, no destination validation. Compounded by two existing factors already documented in this codebase's own architecture review (`Architecture_Refactoring_Recommendation.md`): `app.http.trust-all-ssl=true` by default (so even an HTTPS `target_url` pointed at an internal service with a self-signed cert succeeds without complaint) and `app.url.allowlist-enabled=false` by default (so even the *existing* SSRF guard, where it is applied, is off unless explicitly turned on).

### 8.3 Credential leakage risks

`target_auth` is taken from the request **verbatim** and set as the literal `Authorization` header value on the outbound call (`TargetApiRequestMapper.buildTargetHeaders`, confirmed in current source: `headers.set("Authorization", targetAuth.trim())`). This means:
- Any caller of `/pdf/send`/`/pdf/single` can direct this server to present **arbitrary credentials** to **arbitrary destinations** — the server becomes a generic credential-relay, with no visibility into what's actually being sent where.
- Combined with §8.1/8.2, a malicious or compromised caller could use this endpoint to probe internal services with attacker-supplied bearer tokens/API keys, using this server's network position as cover.
- The fallback path (`app.default.auth.token`) is a single, static, plaintext-in-`application.properties` secret shared across every target that doesn't supply its own auth — a credential-hygiene anti-pattern independent of the SSRF concern (this exact hardcoded-secret issue was already flagged in this codebase's own prior logging/architecture reviews).

### 8.4 Recommended allowlist approach

1. **Target allowlisting via configuration is the primary control**, not a URL-pattern allowlist bolted onto free-text input: once `target_code` (§7) is the primary path, the set of reachable destinations is *exactly* the configured `forwarding.targets` map — there is no way to reach an unconfigured host at all, because there is no code path that accepts an arbitrary URL anymore for migrated callers.
2. **For the backward-compatible `target_url` fallback path (§6 Phase 2–3), apply `UrlAllowlistValidator` to `target_url` exactly as it already is to the source `url`** — this is a small, immediately-actionable fix independent of the larger redesign, and should be considered urgent regardless of this proposal's timeline, since it closes the SSRF gap for the *current* production behavior, not just the future one.
3. **Enable `app.url.allowlist-enabled=true` in UAT/production**, populating `app.url.allowed-hosts` with the known-good source and target hosts — this control already exists in the codebase and is simply switched off by default.
4. **Do not treat `trust-all-ssl=true` as acceptable for the forwarding path** even if it remains a documented, intentional choice for the source-fetch path — recommend `forwarding.targets.*` calls always use standard certificate validation, since these are calls to known, configured, presumably-production-grade endpoints, not arbitrary caller-supplied sources.

### 8.5 Secret management strategy

- **No target credential should ever be a literal value in a committed `.properties`/`.yml` file** — every example in §4 uses `${ENV_VAR}` placeholders for exactly this reason. Spring Boot resolves these from environment variables / a secrets manager (e.g. AWS Secrets Manager, HashiCorp Vault via Spring Cloud Config) at startup; the file committed to source control never contains the actual secret.
- This directly replaces the current anti-pattern (`app.default.auth.token=q4ynVHA2s4d3unfWY2ujrQ==`, a live plaintext secret in `application.properties`) with a model where secrets live only in the deployment environment.
- Once `target_auth` (caller-supplied, request-level) is deprecated per §7, credential management becomes entirely the *operator's* responsibility (via config + env vars), not something any API caller can influence — this is itself a major security improvement independent of SSRF.
- Recommend logging masking (already implemented in this codebase via `LogSanitizer.maskHeaders`) be extended to ensure the resolved `Authorization`/API-key header for a forwarding call is masked in any future forwarding-specific log lines, consistent with the existing logging-remediation work.

---

## 9. Sample Implementation

### 9.1 Updated DTO — `PdfRequest` (illustrative diff, additive only)

```java
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PdfRequest {

    private String url;
    private String cookie;
    private String payload;

    // Existing fields — retained for backward compatibility (see §6, §7)
    private String target_url;
    private String target_auth;

    // NEW — optional; when present, takes precedence over target_url/target_auth
    private String target_code;

    private Message message;
    private MetaData metaData;

    // ... nested Message/Content/Attachment/Recipient/Reference/Sender/Preferences/MetaData unchanged
}
```

### 9.2 Configuration model classes

```java
@ConfigurationProperties(prefix = "forwarding")
@Getter @Setter
public class TargetApiConfig {
    private boolean enabled = true;
    private long defaultTimeoutMs = 5000;
    private RetryConfig defaultRetry = new RetryConfig();
    private Map<String, TargetConfig> targets = new HashMap<>();

    @Getter @Setter
    public static class TargetConfig {
        private String url;
        private AuthType authType = AuthType.NONE;
        private BearerConfig bearer;
        private BasicConfig basic;
        private ApiKeyConfig apiKey;
        private Map<String, String> headers = new HashMap<>();
        private Long timeoutMs;              // falls back to defaultTimeoutMs if null
        private RetryConfig retry;            // falls back to defaultRetry if null
    }

    public enum AuthType { NONE, BEARER, BASIC, API_KEY }

    @Getter @Setter public static class BearerConfig { private String token; }
    @Getter @Setter public static class BasicConfig { private String username; private String password; }
    @Getter @Setter public static class ApiKeyConfig { private String headerName; private String key; }
    @Getter @Setter public static class RetryConfig {
        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private double multiplier = 2.0;
    }
}
```

### 9.3 Service interfaces

```java
public interface AuthStrategy {
    AuthType supports();
    void apply(HttpHeaders headers, TargetApiConfig.TargetConfig target);
}

public interface ForwardingStrategy {
    ResponseEntity<String> forward(TargetApiConfig.TargetConfig target, HttpEntity<String> entity);
}

public interface TargetResolver {
    /** @throws UnknownTargetException if targetCode is not configured */
    TargetApiConfig.TargetConfig resolve(String targetCode);
}

public interface ForwardingService {
    /**
     * Resolves the named target, applies its configured auth/headers, and forwards the
     * payload using that target's own timeout/retry policy.
     * @throws ForwardingDisabledException if forwarding.enabled=false
     * @throws UnknownTargetException if targetCode is not configured
     * @throws ForwardingException on transport failure (wraps the underlying cause)
     */
    ForwardingResult forward(String targetCode, TargetApiRequest payload) throws ForwardingException;
}
```

### 9.4 Controller change (illustrative — facade-level, controller itself barely changes)

```java
// PdfDeliveryFacade — forwarding portion only, rest of fetch/attachment logic unchanged
private ForwardingResult resolveAndForward(PdfRequest req, TargetApiRequest payload, String defaultToken)
        throws ForwardingException {
    if (req.getTargetCode() != null && !req.getTargetCode().isBlank()) {
        return forwardingService.forward(req.getTargetCode(), payload);
    }
    // Backward-compatible fallback: build an ad-hoc target from the legacy fields.
    return forwardingService.forwardAdHoc(req.getTarget_url(), req.getTarget_auth(), defaultToken, payload);
}
```

The controller (`PdfDeliveryController`) itself requires **no changes** under this design — it still only sees `PdfDeliveryFacade.DeliveryResult`/`PdfDeliveryException`, exactly as today.

### 9.5 Class diagram

```
PdfDeliveryController ──► PdfDeliveryFacade ──► ForwardingService (interface)
                                │                        │
                                ▼                        ▼
                      TargetApiRequestMapper    ForwardingServiceImpl
                       (payload shaping only)      │        │        │
                                                     ▼        ▼        ▼
                                          TargetResolver  AuthStrategy  ForwardingStrategy
                                                │            (×4 impls)   (RestTemplate-based)
                                                ▼
                                          TargetApiConfig
                                       (@ConfigurationProperties)
```

---

## 10. Final Recommendation

**Approve — with the phased scope described in §6, not as a single big-bang change.**

- **Estimated implementation complexity: Medium.** The new components (`TargetApiConfig`, `TargetResolver`, 4 small `AuthStrategy` implementations, `ForwardingStrategy`, `ForwardingService`) are individually simple, well-isolated, and independently testable — none require touching business logic elsewhere in the app (fetch/decode/checksum/etc. are untouched). The complexity is in the *migration sequencing* (Phase 2's dual-path fallback) and getting the SSRF/allowlist fix (§8.4 item 2) landed correctly, not in the core design.
- **Risks:**
  - Getting Phase 2's "config wins when `target_code` present, else fall back exactly to today" precedence rule wrong would reintroduce ambiguity — needs explicit test coverage for both paths before rollout.
  - Retrofitting per-target timeout/retry means either a per-target `RestTemplate`/`ClientHttpRequestFactory` (more moving parts) or a request-scoped `RequestConfig` override on the shared client — this needs a concrete spike before committing to one approach, since `RestTemplate`'s connection-pooling story doesn't trivially support "different timeout per call" without care.
  - The SSRF fix (applying `UrlAllowlistValidator` to `target_url`) is technically independent of this redesign and **should not wait** for the full forwarding-framework rollout — recommend landing it as its own small, urgent fix regardless of this proposal's timeline.
- **Suggested phased rollout plan:**
  1. **Immediate, independent of this proposal:** apply `UrlAllowlistValidator` to `target_url` in the current `PdfDeliveryFacade` (closes the live SSRF gap with a one-line change per method).
  2. **Phase 1:** land `TargetApiConfig`/`TargetResolver`/`AuthStrategy`/`ForwardingStrategy`/`ForwardingService` as new, unused-by-default classes + unit tests. Zero production behavior change.
  3. **Phase 2:** wire `PdfDeliveryFacade` to use `ForwardingService` with the dual-path (`target_code` vs. legacy fallback) behavior; add `target_code` to `PdfRequest`; configure one real target (e.g. `internal-test-sink`) in a non-prod profile and verify end-to-end against it.
  4. **Phase 3:** migrate known integrations to `target_code` at their own pace; monitor legacy-path usage (a simple log-count of "used target_url fallback" vs. "used target_code") to know when it's safe to consider deprecation.
  5. **Phase 4 (separately approved, later):** deprecate and eventually remove raw `target_url`/`target_auth` request support once legacy-path usage is at/near zero.
