# ADR-005: Do not rename the root package

## Status
Accepted

## Context
The prior architecture review proposed renaming the root package from `com.twixor.base64convertor` to `com.company.application` as part of the modularization. A root-level rename touches every single source file's package declaration (32+ files) and every import statement referencing them, on top of the internal module reorganization already in scope.

## Decision
Keep the existing root package `com.twixor.base64convertor` unchanged. All new module packages (`common`, `fileconversion`, `filestorage`, `pdf`, `checksum`, `health`) are created **underneath** the existing root:

```
com.twixor.base64convertor
├── common
├── pdf
├── filestorage
├── fileconversion
├── checksum
└── health
```

## Consequences
- Positive: strictly smaller diff for the same structural benefit (module separation); `Base64convertorApplication`'s default component-scan (package-and-below from its own package) continues to cover every new subpackage with zero `@ComponentScan` configuration changes, since the application class and all new modules remain under the same root.
- Negative: the package name still reflects the original project name/organization rather than a generic "company" placeholder — considered a cosmetic concern only, not a functional one.
- Neutral: nothing about REST endpoint URLs, Postman collections, or external integrations depends on the Java package name in any way, so this decision has zero bearing on API compatibility either way — it is purely a risk-minimization choice for this phase.

## Why behavior is unchanged
Java package names have no runtime-visible effect on HTTP routing, JSON serialization, or any external contract. Spring resolves beans by type/annotation, not by package name. This decision reduces the diff size of Phase A without affecting any of the constraints in scope.
