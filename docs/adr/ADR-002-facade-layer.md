# ADR-002: Introduce a Facade layer for multi-step controller orchestration

## Status
Accepted (Phase A), scoped narrowly

## Context
Three controller responsibilities orchestrate 3+ steps/collaborators directly inline: PDF delivery (fetch → map → forward → build response, plus a `finally`-block side effect), PDF protection (validate → decode → derive password → protect → persist → build response), and file storage (path-traversal guard + filesystem I/O + sanitization + formatting repeated across 8 endpoint methods, 5 of which have no service layer today at all). This orchestration logic sitting directly in controllers makes the controllers hard to read and impossible to unit-test without spinning up MVC infrastructure.

## Decision
Introduce exactly three facades: `FileStorageFacade` (`filestorage.facade`), `PdfDeliveryFacade`, `PdfProtectionFacade` (both `pdf.facade`). Each facade performs pure orchestration — calling existing services/validators/utilities in the same sequence the controller used to — and propagates the same exception types the controller already catches, so each controller's existing try/catch → HTTP-status mapping is preserved unchanged.

We explicitly do **not** introduce a facade for `PdfFetchController`, `FileConvertController`, `ChecksumController`, or `TestController`, because each of those either delegates to exactly one service method with no additional controller-side orchestration, or has no service to orchestrate at all. A facade there would be a value-free pass-through wrapper class.

## Consequences
- Positive: controllers become thin adapters (parse request → call facade → map result to `ResponseEntity`); orchestration logic becomes independently unit-testable without Spring MVC; the file-storage domain gains a service layer for the first time on 5 of its 8 endpoints.
- Negative: three new classes, one additional layer to trace through when debugging.
- Neutral: no facade is introduced where it would add no value — this was a deliberate scoping decision, not an oversight.

## Why behavior is unchanged
Facade methods contain the exact orchestration sequence and exception types the controller previously executed inline; HTTP status-code decisions remain in the controller's (unchanged) catch blocks. Confirmed against the Phase 0 baseline captures.
