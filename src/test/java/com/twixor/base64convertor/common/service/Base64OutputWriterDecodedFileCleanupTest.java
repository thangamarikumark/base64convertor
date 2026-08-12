package com.twixor.base64convertor.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twixor.base64convertor.common.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Base64OutputWriter#cleanupOldDecodedFiles()} — the retention fix for
 * the unbounded-disk-growth gap documented in docs/Production_Readiness_Review.md (P0-3):
 * POST /save-decoded output (files carrying a Tika-detected extension, plus their .meta.json
 * sidecars) was never covered by the existing .b64-only cleanup.
 */
class Base64OutputWriterDecodedFileCleanupTest {

    @TempDir
    Path outputDir;

    private AppProperties appProperties;
    private SimpleMeterRegistry meterRegistry;
    private Base64OutputWriter writer;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getBase64().setEnabled(true);
        appProperties.getBase64().setOutputPath(outputDir.toString());
        appProperties.getDecodedFile().setRetentionDays(7);

        meterRegistry = new SimpleMeterRegistry();
        writer = new Base64OutputWriter(appProperties, meterRegistry, new ObjectMapper());
        writer.initMetrics();
    }

    private Path writeFile(String name, String content, int ageDays) throws IOException {
        Path file = outputDir.resolve(name);
        Files.writeString(file, content);
        if (ageDays > 0) {
            Files.setLastModifiedTime(file,
                    FileTime.from(Instant.now().minus(ageDays, ChronoUnit.DAYS)));
        }
        return file;
    }

    @Test
    void expiredDecodedFileAndItsMetadataSidecar_areBothDeleted() throws IOException {
        writeFile("20260101-000000_abcd1234_report.pdf", "pdf-bytes", 10);
        writeFile("20260101-000000_abcd1234_report.pdf.meta.json", "{}", 10);

        writer.cleanupOldDecodedFiles();

        assertFalse(Files.exists(outputDir.resolve("20260101-000000_abcd1234_report.pdf")));
        assertFalse(Files.exists(outputDir.resolve("20260101-000000_abcd1234_report.pdf.meta.json")));
    }

    @Test
    void recentDecodedFileAndSidecar_areKept() throws IOException {
        writeFile("20260701-000000_ffff0000_fresh.jpg", "jpg-bytes", 1);
        writeFile("20260701-000000_ffff0000_fresh.jpg.meta.json", "{}", 1);

        writer.cleanupOldDecodedFiles();

        assertTrue(Files.exists(outputDir.resolve("20260701-000000_ffff0000_fresh.jpg")));
        assertTrue(Files.exists(outputDir.resolve("20260701-000000_ffff0000_fresh.jpg.meta.json")));
    }

    @Test
    void orphanedExpiredMetadataSidecar_withNoPrimaryFile_isDeleted() throws IOException {
        writeFile("20260101-000000_dead0000_ghost.png.meta.json", "{}", 10);

        writer.cleanupOldDecodedFiles();

        assertFalse(Files.exists(outputDir.resolve("20260101-000000_dead0000_ghost.png.meta.json")));
    }

    @Test
    void orphanedRecentMetadataSidecar_isKept() throws IOException {
        writeFile("20260701-000000_dead0001_ghost2.png.meta.json", "{}", 1);

        writer.cleanupOldDecodedFiles();

        assertTrue(Files.exists(outputDir.resolve("20260701-000000_dead0001_ghost2.png.meta.json")));
    }

    @Test
    void b64Files_areNeverTouchedByDecodedFileCleanup_evenWhenExpired() throws IOException {
        writeFile("20260101-000000_abcd0000_data.b64", "base64content", 10);

        writer.cleanupOldDecodedFiles();

        assertTrue(Files.exists(outputDir.resolve("20260101-000000_abcd0000_data.b64")));
    }

    @Test
    void cleanup_incrementsDeletedMetric_perFileDeleted() throws IOException {
        writeFile("20260101-000000_1111_a.pdf", "x", 10);
        writeFile("20260101-000000_1111_a.pdf.meta.json", "{}", 10);
        writeFile("20260101-000000_2222_b.png", "y", 10);
        writeFile("20260101-000000_2222_b.png.meta.json", "{}", 10);

        writer.cleanupOldDecodedFiles();

        double deleted = meterRegistry.get("base64.output.decoded-files.deleted").counter().count();
        // 2 primary files deleted; sidecars are deleted alongside but only the primary
        // deletion increments the counter (sidecar deletion is bundled, not double-counted).
        assertEquals(2.0, deleted);
    }

    @Test
    void cleanup_disabledOutput_doesNothing() throws IOException {
        appProperties.getBase64().setEnabled(false);
        writeFile("20260101-000000_1111_a.pdf", "x", 10);

        writer.cleanupOldDecodedFiles();

        assertTrue(Files.exists(outputDir.resolve("20260101-000000_1111_a.pdf")));
    }

    @Test
    void cleanup_missingDirectory_doesNotThrow() {
        appProperties.getBase64().setOutputPath(outputDir.resolve("does-not-exist").toString());
        writer.cleanupOldDecodedFiles();
    }

    @Test
    void cleanup_respectsConfiguredRetentionDays_notJustDefault() throws IOException {
        appProperties.getDecodedFile().setRetentionDays(30);
        writeFile("20260101-000000_1111_a.pdf", "x", 10); // 10 days old, retention is 30

        writer.cleanupOldDecodedFiles();

        assertTrue(Files.exists(outputDir.resolve("20260101-000000_1111_a.pdf")),
                "file younger than the configured 30-day retention must be kept");
    }
}
