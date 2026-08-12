# ADR-003: Do not introduce a global exception handler

## Status
Rejected (permanently, not just deferred)

## Context
The codebase has no shared vocabulary of typed exceptions — every controller hand-catches generic `Exception`/`IllegalArgumentException`/`IOException` and builds its own response shape inline. A `@RestControllerAdvice` with `@ExceptionHandler` methods is the conventional Spring Boot way to centralize this.

## Decision
**Do not** introduce a global `@RestControllerAdvice`/generic error-response DTO in this refactor, and do not plan to introduce one later as a "quick win." Endpoints in this codebase intentionally return **different response shapes on error** today: `ChecksumController` returns a raw `Map<String,String>`; `PdfController`'s `/convert/base64` returns `502`/`500` with a `PdfResponse` body; `/convert/base64dynamic` **always** returns `200` with a `FAILED` status string inside a `PdfResponse` body (a deliberately different convention from its sibling endpoint); `FileRetrievalController`'s download/metadata/delete endpoints return bodiless `403`/`404`/`500` responses. A single blanket exception handler returning one generic error schema would collapse these into a uniform shape, which is a breaking response-payload change under constraints #3/#7/#10.

## Consequences
- Positive: zero risk of accidentally changing any endpoint's error contract.
- Negative: the duplication of catch-block reasoning across ~15 methods remains unaddressed in this refactor cycle.
- Neutral: individual controllers may still throw/catch more specific exception types internally in a future, separately-scoped and separately-verified change (see the "typed exceptions" item in the roadmap, deferred to Phase D) — but that is explicitly **not** the same thing as a global handler, and is not scheduled as part of this refactor.

## Why behavior is unchanged
Not applicable in the usual sense — this ADR documents a decision to change nothing about exception handling. Every controller's existing try/catch blocks and per-branch response construction remain untouched in Phase A.
