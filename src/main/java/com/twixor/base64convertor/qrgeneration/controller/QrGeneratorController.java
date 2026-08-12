package com.twixor.base64convertor.qrgeneration.controller;

import com.twixor.base64convertor.qrgeneration.dto.QrGeneratorRequest;
import com.twixor.base64convertor.qrgeneration.dto.QrGeneratorResponse;
import com.twixor.base64convertor.qrgeneration.service.QrGeneratorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files/convert/")
public class QrGeneratorController {

    private final QrGeneratorService qrGeneratorService;
    public QrGeneratorController(QrGeneratorService qrGeneratorService) {
        this.qrGeneratorService = qrGeneratorService;
    }

    QrGeneratorResponse response = new QrGeneratorResponse();

    @PostMapping("/qrcode")
    public ResponseEntity<QrGeneratorResponse> generateQr(
            @RequestBody QrGeneratorRequest request) throws Exception {

        if (request == null ||
                request.getUrl() == null ||
                request.getUrl().trim().isEmpty()) {

            response.setQrCode("");
            response.setErrorCode("0");
            response.setErrorMessage("URL Cannot be null or empty");

            return ResponseEntity.badRequest()
                    .body(response);
        }

        ResponseEntity<QrGeneratorResponse> outputResponse =
                qrGeneratorService.generateQrBase64(request.getUrl());

        if (outputResponse.getStatusCode() == HttpStatus.OK) {
            return ResponseEntity.ok(outputResponse.getBody());
        } else {
            return ResponseEntity.badRequest().body(outputResponse.getBody());
        }
    }
}
