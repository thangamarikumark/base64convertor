# ADR-001: Split god controllers into intent-scoped controllers

## Status
Accepted (Phase A)

## Context
`FileRetrievalController` owned 8 endpoints spanning three unrelated concerns (decode-and-save, raw-save/retrieve, list/download/delete). `PdfController` owned 5 endpoints spanning three unrelated concerns (fetch-and-return, fetch-and-relay, password-protect). Both violate the Single Responsibility Principle and make it hard to reason about or test one concern in isolation.

## Decision
Split `FileRetrievalController` into `DecodedFileController`, `Base64FileController`, `CallbackController` (package `filestorage.controller`). Split `PdfController` into `PdfFetchController`, `PdfDeliveryController`, `PdfProtectionController` (package `pdf.controller`). Multiple controller classes share the same `@RequestMapping` base path (`/api/files`, `/api/files/pdf`) — Spring permits this as long as no two methods share the same full path + HTTP method, which is guaranteed here since every endpoint's sub-path is already unique today.

## Consequences
- Positive: each controller now has one reason to change; easier to unit-test with `@WebMvcTest` slices; smaller, more navigable classes.
- Negative: six controller classes to open instead of two; slightly more boilerplate (constructor injection repeated per class).
- Neutral: zero change to any `@GetMapping`/`@PostMapping`/`@DeleteMapping` path string, so no endpoint URL, request, or response payload is affected.

## Why behavior is unchanged
Every method body is moved verbatim; every path string, HTTP verb, `@RequestBody` type, and response type is copied unmodified. Confirmed against the Phase 0 baseline captures in `docs/regression-baseline/`.
