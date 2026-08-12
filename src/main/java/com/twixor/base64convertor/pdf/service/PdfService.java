package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.common.config.AppProperties;
import com.twixor.base64convertor.pdf.dto.PdfBase64RequestDynamic;
import com.twixor.base64convertor.pdf.dto.PdfResponse;
import com.twixor.base64convertor.common.validation.Base64FileValidator;
import com.twixor.base64convertor.common.service.Base64OutputWriter;
import com.twixor.base64convertor.common.util.LogSanitizer;
import com.twixor.base64convertor.common.validation.UrlAllowlistValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfService {

    private static final Logger logger = LogManager.getLogger(PdfService.class);
    private static final Logger httpLogger = LogManager.getLogger("com.twixor.base64convertor.http");
    private static final Pattern CONTENT_DISPOSITION_FILENAME_PATTERN =
            Pattern.compile("filename=\"?([^\";]+)\"?");

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final UrlAllowlistValidator urlAllowlistValidator;
    private final Base64OutputWriter base64OutputWriter;
    private final MeterRegistry meterRegistry;

    private Counter pdfSuccessCounter;
    private Counter pdfFailureCounter;
    private Timer pdfConversionTimer;

    public PdfService(RestTemplate restTemplate,
                      AppProperties appProperties,
                      UrlAllowlistValidator urlAllowlistValidator,
                      Base64OutputWriter base64OutputWriter,
                      MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.appProperties = appProperties;
        this.urlAllowlistValidator = urlAllowlistValidator;
        this.base64OutputWriter = base64OutputWriter;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        pdfSuccessCounter = Counter.builder("pdf.conversions")
                .tag("status", "success")
                .description("Successful PDF/file conversions via PdfService")
                .register(meterRegistry);
        pdfFailureCounter = Counter.builder("pdf.conversions")
                .tag("status", "failure")
                .description("Failed PDF/file conversions via PdfService")
                .register(meterRegistry);
        pdfConversionTimer = Timer.builder("pdf.conversion.duration")
                .description("Time taken for PdfService to fetch and encode a file")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Fetches a file from the given URL and returns it as a Base64 string.
     * Retries on RuntimeException using configurable backoff (app.retry.*).
     */
    @Retryable(
            retryFor = RuntimeException.class,
            maxAttemptsExpression = "#{@appProperties.retry.maxAttempts}",
            backoff = @Backoff(
                    delayExpression    = "#{@appProperties.retry.initialDelayMs}",
                    multiplierExpression = "#{@appProperties.retry.multiplier}"
            )
    )
    public String fetchAndConvertToBase64(String url, String cookie, String payload) {
        urlAllowlistValidator.validate(url);
        logger.debug("Fetching file from URL: {}", LogSanitizer.sanitizeUrl(url));
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (cookie != null && !cookie.isEmpty()) {
                // A bare value (no "=") is treated as a raw JSESSIONID value, preserving this
                // endpoint's original behavior for existing callers. A value that already looks
                // like a full "name=value[; name2=value2]" cookie header (contains "=") is sent
                // verbatim instead of being wrapped in "JSESSIONID=...", which previously mangled
                // full cookie strings and made it impossible to authenticate against a source
                // system using a differently-named session cookie.
                String cookieHeader = cookie.contains("=") ? cookie : "JSESSIONID=" + cookie;
                headers.add("Cookie", cookieHeader);
            }

            HttpEntity<String> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);

            if (response.getStatusCode() != HttpStatus.OK) {
                String msg = String.format("Failed to fetch file. HTTP code: %s", response.getStatusCode());
                logger.error(msg);
                throw new RuntimeException(msg);
            }

            byte[] fileBytes = response.getBody();
            if (fileBytes == null || fileBytes.length == 0) {
                logger.warn("Empty content received from URL: {}", LogSanitizer.sanitizeUrl(url));
                throw new RuntimeException("Empty file content");
            }

            String base64 = Base64.getEncoder().encodeToString(fileBytes);
            Path b64File = base64OutputWriter.write(base64, "file");
            logger.info("Converted file from URL: {}. Base64 saved to: {}",
                    LogSanitizer.sanitizeUrl(url), b64File != null ? b64File.toAbsolutePath() : "(output disabled)");
            pdfSuccessCounter.increment();
            sample.stop(pdfConversionTimer);
            return base64;

        } catch (IllegalArgumentException e) {
            // Do not retry validation errors
            pdfFailureCounter.increment();
            sample.stop(pdfConversionTimer);
            throw e;
        } catch (Exception e) {
            logger.error("Error fetching file from URL: {} - {}", LogSanitizer.sanitizeUrl(url), e.getMessage(), e);
            httpLogger.error("HTTP error during file fetch for URL {}: {}", LogSanitizer.sanitizeUrl(url), e.getMessage(), e);
            pdfFailureCounter.increment();
            sample.stop(pdfConversionTimer);
            throw new RuntimeException("Error fetching file: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches a file dynamically (custom method + headers) and returns a PdfResponse.
     * Retries the HTTP call internally using configurable backoff (app.retry.*).
     */
    public PdfResponse fetchAndConvertToBase64Dynamic(PdfBase64RequestDynamic req) {
        urlAllowlistValidator.validate(req.getUrl());
        Timer.Sample sample = Timer.start(meterRegistry);

        String fileName = "unknown";
        String mimeType = "application/octet-stream";

        try {
            Map<String, Object> payload = req.getPayload();
            HttpMethod method = resolveHttpMethod(req, payload);
            HttpHeaders headers = createHeaders(req, method);

            Object requestBody = null;
            if (method == HttpMethod.POST && payload != null && payload.containsKey("body")) {
                requestBody = payload.get("body");
            }

            HttpEntity<Object> entity = new HttpEntity<>(requestBody, headers);

            httpLogger.info("===== HTTP Request =====");
            httpLogger.info("URI      : {}", LogSanitizer.sanitizeUrl(req.getUrl()));
            httpLogger.info("Method   : {}", method);
            httpLogger.info("Headers  : {}", LogSanitizer.maskHeaders(headers));
            httpLogger.info("Body     : {}", (requestBody != null ? "[Payload present]" : "(none)"));

            // Retry the HTTP call on failure
            ResponseEntity<byte[]> resp = executeWithRetry(req.getUrl(), method, entity);

            httpLogger.info("===== HTTP Response =====");
            httpLogger.info("Status   : {}", resp.getStatusCode());
            httpLogger.info("Headers  : {}", LogSanitizer.maskHeaders(resp.getHeaders()));

            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("Empty response body for URL: " + req.getUrl());
            }

            String prefix = new String(body, 0, Math.min(40, body.length)).toLowerCase();
            if (prefix.contains("<html") || prefix.contains("{\"title\":")) {
                Path errorDump = Files.createTempFile("invalid_", ".html");
                Files.write(errorDump, body);
                throw new RuntimeException("Received HTML/JSON instead of binary (dumped to " + errorDump + ")");
            }

            mimeType = detectMimeType(resp, body, req.getUrl());
            fileName = detectFileName(resp, req.getUrl());
            long bodyLength = body.length;

            long threshold = appProperties.getPdf().getStreamThresholdBytes();
            if (bodyLength > threshold) {
                logger.warn("Large file [{} bytes, type={}] exceeds threshold of {} bytes; Base64 not returned.",
                        bodyLength, mimeType, threshold);
                return new PdfResponse(fileName, "FILE_TOO_LARGE", null, mimeType, bodyLength);
            }

            // java.util.Base64.Encoder never emits line breaks, so no CR/LF stripping is needed here.
            String base64 = Base64.getEncoder().withoutPadding().encodeToString(body);

            double expectedBase64Length = (bodyLength * 4.0) / 3.0;
            if (Math.abs(base64.length() - expectedBase64Length) > 10) {
                logger.warn("Base64 length mismatch - possible truncation: expected ~{}, got {}",
                        (long) expectedBase64Length, base64.length());
            }

            boolean valid = Base64FileValidator.isValidBase64ForMime(base64, mimeType);
            if (!valid) {
                Path dump = Files.createTempFile("corrupted_", "_" + fileName);
                Files.write(dump, body);
                throw new RuntimeException("Invalid Base64 or MIME mismatch. Raw data dumped to " + dump);
            }

            Path b64File = base64OutputWriter.write(base64, fileName);
            logger.info("File [{}] [{} bytes, type={}] converted. Base64 saved to: {}",
                    fileName, bodyLength, mimeType,
                    b64File != null ? b64File.toAbsolutePath() : "(output disabled)");

            pdfSuccessCounter.increment();
            sample.stop(pdfConversionTimer);
            return new PdfResponse(fileName, "SUCCESS", base64, mimeType, bodyLength);

        } catch (IllegalArgumentException e) {
            logger.error("Validation error for URL {}: {}", LogSanitizer.sanitizeUrl(req.getUrl()), e.getMessage());
            pdfFailureCounter.increment();
            sample.stop(pdfConversionTimer);
            return new PdfResponse(fileName, "FAILED: " + e.getMessage(), null, mimeType, 0);
        } catch (Exception e) {
            logger.error("Error converting file from {}: {}", LogSanitizer.sanitizeUrl(req.getUrl()), e.getMessage(), e);
            pdfFailureCounter.increment();
            sample.stop(pdfConversionTimer);
            return new PdfResponse(fileName, "FAILED: " + e.getMessage(), null, mimeType, 0);
        }
    }

    /**
     * Executes the HTTP call with retry logic driven by app.retry.* properties.
     */
    private ResponseEntity<byte[]> executeWithRetry(String url, HttpMethod method, HttpEntity<Object> entity) {
        AppProperties.Retry retryConfig = appProperties.getRetry();
        int maxAttempts = retryConfig.getMaxAttempts();
        long delay = retryConfig.getInitialDelayMs();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restTemplate.exchange(url, method, entity, byte[].class);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    logger.warn("HTTP request attempt {}/{} failed for {}: {}. Retrying in {}ms...",
                            attempt, maxAttempts, url, e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                    delay = (long) (delay * retryConfig.getMultiplier());
                }
            }
        }
        throw new RuntimeException("All " + maxAttempts + " attempts failed for URL: " + url, lastException);
    }

    // -------------------- Helper Methods --------------------

    @SuppressWarnings("unchecked")
    private HttpHeaders createHeaders(PdfBase64RequestDynamic req, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));

        Map<String, Object> payload = req.getPayload();
        if (payload != null && payload.containsKey("headers")) {
            Object hdrObj = payload.get("headers");
            if (hdrObj instanceof Map<?, ?> hdrMap) {
                hdrMap.forEach((k, v) -> {
                    if (k instanceof String key && v instanceof String val) {
                        headers.set(key, val);
                    }
                });
            }
        }

        if (req.getAuthorization() != null && !req.getAuthorization().isEmpty()) {
            headers.set("Authorization", req.getAuthorization());
        }

        if (req.getCookie() != null && !req.getCookie().isEmpty()) {
            headers.add("Cookie", req.getCookie());
        }

        if (method == HttpMethod.POST) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        return headers;
    }

    private HttpMethod resolveHttpMethod(PdfBase64RequestDynamic req, Map<String, Object> payload) {
        if (req.getHttpMethod() != null) {
            try {
                return HttpMethod.valueOf(req.getHttpMethod().toUpperCase());
            } catch (IllegalArgumentException ex) {
                logger.warn("Invalid httpMethod '{}', falling back to auto-detect.", req.getHttpMethod());
            }
        }

        if (payload == null || payload.isEmpty()
                || (payload.containsKey("body") && ((Map<?, ?>) payload.get("body")).isEmpty())) {
            return HttpMethod.GET;
        }
        return HttpMethod.POST;
    }

    private String detectMimeType(ResponseEntity<byte[]> response, byte[] data, String url) throws IOException {
        MediaType mt = response.getHeaders().getContentType();
        if (mt != null) return mt.toString();

        String guessed = URLConnection.guessContentTypeFromName(url);
        if (guessed != null) return guessed;

        guessed = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(data));
        return (guessed != null) ? guessed : "application/octet-stream";
    }

    private String detectFileName(ResponseEntity<byte[]> response, String url) {
        List<String> contentDisposition = response.getHeaders().get(HttpHeaders.CONTENT_DISPOSITION);
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            String headerValue = contentDisposition.get(0);
            Matcher matcher = CONTENT_DISPOSITION_FILENAME_PATTERN.matcher(headerValue);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        try {
            return Paths.get(new URI(url).getPath()).getFileName().toString();
        } catch (Exception e) {
            return "downloaded_file";
        }
    }
}
