package com.twixor.base64convertor.pdf.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twixor.base64convertor.common.util.LogSanitizer;
import com.twixor.base64convertor.pdf.dto.PdfRequest;
import com.twixor.base64convertor.pdf.dto.TargetApiRequest;
import com.twixor.base64convertor.pdf.mapper.TargetApiRequestMapper;
import com.twixor.base64convertor.pdf.service.PdfService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Orchestration for the PDF-delivery workflow (fetch source file, map to the target API's
 * message contract, forward to target_url), extracted verbatim from PdfController's
 * convertFileAndSendToTarget/convertSingleFile methods (Phase A, A11b).
 *
 * <p>On failure this throws {@link PdfDeliveryException}, which carries the partial
 * {@code fileName}/{@code mimeType} the original controller code would have already computed
 * by the point of failure (read from the request's attachment, not from the fetch result) plus
 * the original cause — so the controller's existing catch-block behavior (including which
 * fileName/mimeType appear in a FAILED response) is reproduced exactly, not just approximated.
 */
@Component
@RequiredArgsConstructor
public class PdfDeliveryFacade {

    private static final Logger logger = LogManager.getLogger(PdfDeliveryFacade.class);

    private final PdfService pdfService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TargetApiRequestMapper targetApiRequestMapper;

    public static class DeliveryResult {
        public final String fileName;
        public final String mimeType;
        public final String base64;

        public DeliveryResult(String fileName, String mimeType, String base64) {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.base64 = base64;
        }
    }

    public static class PdfDeliveryException extends Exception {
        public final String fileName;
        public final String mimeType;

        public PdfDeliveryException(String fileName, String mimeType, Throwable cause) {
            super(cause);
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }

    /**
     * Fetches the source file, injects it into the message attachment (if present), maps to
     * TargetApiRequest, and forwards to {@code req.getTarget_url()} unconditionally (matches
     * the original /send behavior).
     */
    public DeliveryResult deliverAlways(PdfRequest req, String defaultToken) throws PdfDeliveryException {
        String fileName = "unknown";
        String mimeType = null;
        try {
            String base64 = pdfService.fetchAndConvertToBase64(req.getUrl(), req.getCookie(), req.getPayload());

            if (req.getMessage() != null && req.getMessage().getContent() != null
                    && req.getMessage().getContent().getAttachment() != null) {
                var attachment = req.getMessage().getContent().getAttachment();
                attachment.setAttachmentData(base64);
                fileName = attachment.getFileName();
                mimeType = attachment.getMimeType();
            }

            TargetApiRequest targetApiRequest = targetApiRequestMapper.toTargetApiRequest(req);
            HttpHeaders headers = targetApiRequestMapper.buildTargetHeaders(req, defaultToken);
            String targetJson = objectMapper.writeValueAsString(targetApiRequest);
            HttpEntity<String> entity = new HttpEntity<>(targetJson, headers);

            logger.info("Sending converted file '{}' to target URL: {}", fileName, LogSanitizer.sanitizeUrl(req.getTarget_url()));
            restTemplate.postForEntity(req.getTarget_url(), entity, String.class);
            logger.info("Successfully sent '{}' to target API.", fileName);

            return new DeliveryResult(fileName, mimeType, base64);
        } catch (Exception e) {
            throw new PdfDeliveryException(fileName, mimeType, e);
        } finally {
            if (req.getMessage() != null && req.getMessage().getContent() != null
                    && req.getMessage().getContent().getAttachment() != null) {
                req.getMessage().getContent().getAttachment().setAttachmentData(null);
            }
        }
    }

    /**
     * Same as {@link #deliverAlways}, but only forwards to target_url if it is non-blank
     * (matches the original /single behavior).
     */
    public DeliveryResult deliverIfTargetPresent(PdfRequest req, String defaultToken) throws PdfDeliveryException {
        String fileName = "unknown";
        try {
            String base64 = pdfService.fetchAndConvertToBase64(req.getUrl(), req.getCookie(), req.getPayload());

            if (req.getMessage() != null && req.getMessage().getContent() != null
                    && req.getMessage().getContent().getAttachment() != null) {
                req.getMessage().getContent().getAttachment().setAttachmentData(base64);
                fileName = req.getMessage().getContent().getAttachment().getFileName();
            }

            TargetApiRequest targetApiRequest = targetApiRequestMapper.toTargetApiRequest(req);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(targetApiRequest),
                    targetApiRequestMapper.buildTargetHeaders(req, defaultToken));

            if (req.getTarget_url() != null && !req.getTarget_url().isBlank()) {
                logger.info("Sending file '{}' to target URL: {}", fileName, LogSanitizer.sanitizeUrl(req.getTarget_url()));
                restTemplate.postForEntity(req.getTarget_url(), entity, String.class);
            }

            String mimeType = (req.getMessage() != null && req.getMessage().getContent() != null
                    && req.getMessage().getContent().getAttachment() != null)
                    ? req.getMessage().getContent().getAttachment().getMimeType()
                    : null;

            return new DeliveryResult(fileName, mimeType, base64);
        } catch (Exception e) {
            throw new PdfDeliveryException(fileName, null, e);
        } finally {
            if (req.getMessage() != null && req.getMessage().getContent() != null
                    && req.getMessage().getContent().getAttachment() != null) {
                req.getMessage().getContent().getAttachment().setAttachmentData(null);
            }
        }
    }
}
