package com.twixor.base64convertor.pdf.service;

import com.twixor.base64convertor.pdf.dto.PdfBase64Request;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Deprecated during Phase A refactor.
 * No known callers as of architecture review.
 * Scheduled for future removal after verification period.
 */
@Deprecated(since = "phase-a-refactor", forRemoval = true)
@Service
public class DynamicHttpService {

    private final RestTemplate restTemplate;

    public DynamicHttpService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<String> forwardRequest(PdfBase64Request request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (request.getCookie() != null && !request.getCookie().isEmpty()) {
                headers.add("Cookie", request.getCookie());
            }

            HttpEntity<Object> entity = new HttpEntity<>(request.getPayload(), headers);

            return restTemplate.exchange(
                    request.getUrl(),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error forwarding request: " + e.getMessage());
        }
    }
}
