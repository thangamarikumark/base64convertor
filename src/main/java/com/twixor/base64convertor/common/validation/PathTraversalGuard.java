package com.twixor.base64convertor.common.validation;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Shared path-traversal guard (Phase A, A4). Extracted from five previously duplicated
 * inline checks in FileRetrievalController — semantics copied verbatim
 * ({@code candidate.normalize().startsWith(baseDir.normalize())}).
 */
@Component
public class PathTraversalGuard {

    public boolean isWithin(Path candidate, Path baseDir) {
        return candidate.normalize().startsWith(baseDir.normalize());
    }
}
