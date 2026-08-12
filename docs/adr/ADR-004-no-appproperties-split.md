# ADR-004: Do not split AppProperties in this phase

## Status
Rejected for this phase (revisit only if a concrete pain point emerges)

## Context
`AppProperties` is a single `@ConfigurationProperties(prefix = "app")` class binding settings for five otherwise-unrelated concerns (HTTP client, PDF streaming/protection, batch conversion, URL allowlist, retry, Base64 output). Splitting it into module-scoped properties classes (e.g. `PdfProtectionProperties`, `Base64OutputProperties`) would improve per-module config ownership, but every read site (~10 files) would need to be re-pointed to the correct narrower bean.

## Decision
`AppProperties` moves into `common/config` **as a single, unmodified class** during Phase A (a pure package relocation). It is **not** split into multiple classes in this phase. `application.properties` requires zero edits either way, since `@ConfigurationProperties` binds by prefix string, not by class location or name.

## Consequences
- Positive: zero risk to the highest-touch item identified in the architecture review; `application.properties` is completely untouched (constraint #6 satisfied trivially).
- Negative: `common/config` retains one class that, by content, spans concerns beyond what's "common" — a structural compromise accepted deliberately in exchange for minimizing regression surface.
- Neutral: nothing prevents revisiting this later as an isolated, separately-verified change if a specific module team needs independent config ownership.

## Why behavior is unchanged
Moving a Java class between packages does not affect Spring's `@ConfigurationProperties` prefix binding. Since the class's fields, nested structure, and prefix are completely unmodified, every property read anywhere in the app resolves to the identical value it does today.
