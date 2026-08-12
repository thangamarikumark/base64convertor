package com.twixor.base64convertor.fileconversion.controller;

import com.twixor.base64convertor.common.config.AppProperties;
import com.twixor.base64convertor.fileconversion.dto.FileConvertRequest;
import com.twixor.base64convertor.fileconversion.dto.FileConvertResponse;
import com.twixor.base64convertor.fileconversion.service.FileConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Validated
@RestController
@RequestMapping("/api/files/convert")
@RequiredArgsConstructor
public class FileConvertController {

    private final FileConversionService fileConversionService;
    private final AppProperties appProperties;

    /**
     * Converts a batch of files to Base64.
     * Max batch size is controlled by app.convert.max-batch-size (default 50).
     */
    @PostMapping
    public ResponseEntity<List<FileConvertResponse>> convertFiles(
            @RequestBody @Valid @Size(
                    min = 1,
                    max = Integer.MAX_VALUE,  // upper bound enforced dynamically below
                    message = "Request list must not be empty"
            ) List<@Valid FileConvertRequest> requests) {

        int maxBatch = appProperties.getConvert().getMaxBatchSize();
        if (requests.size() > maxBatch) {
            return ResponseEntity.badRequest().build();
        }

        List<FileConvertResponse> responses = requests.stream()
                .map(fileConversionService::processFile)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * Submits a file for asynchronous conversion and returns immediately with a processingId
     * that can be polled via {@code GET /status/{processingId}}. Previously that polling
     * endpoint was unreachable — nothing ever submitted work through the async path it reads
     * from — so it always returned 404 regardless of the id supplied.
     */
    @PostMapping("/async")
    public ResponseEntity<Map<String, String>> convertFileAsync(
            @RequestBody @Valid FileConvertRequest request) {
        String processingId = fileConversionService.submitAsync(request);
        return ResponseEntity.accepted().body(Map.of("processingId", processingId));
    }

    @GetMapping("/status/{processingId}")
    public ResponseEntity<?> getStatus(@PathVariable String processingId) {
        FileConvertResponse response = fileConversionService.getAsyncResult(processingId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/single")
    public ResponseEntity<FileConvertResponse> convertSingleFile(
            @RequestBody @Valid FileConvertRequest request) {
        FileConvertResponse response = fileConversionService.processFile(request);
        return ResponseEntity.ok(response);
    }
}
