package com.twixor.base64convertor.base64topaymenturl.controller;

import com.twixor.base64convertor.base64topaymenturl.service.QrDecoderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final QrDecoderService qrDecoderService;

    public PaymentController(
            QrDecoderService qrDecoderService) {

        this.qrDecoderService = qrDecoderService;
    }

    @PostMapping("/decode-qr")
    public ResponseEntity<?> decodeQr(@RequestBody Map<String, String> request) {

        try {
            String qrString = request.get("qrString");

            if (qrString == null || qrString.isBlank()) {
                return ResponseEntity.badRequest().body(
                        Map.of(
                                "status", "Failed",
                                "message", "qrString is required"
                        )
                );
            }

            String paymentUrl = qrDecoderService.decodeQr(qrString);

            return ResponseEntity.ok(
                    Map.of(
                            "status", "Success",
                            "paymentUrl", paymentUrl
                    )
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "status", "Failed",
                            "message", e.getMessage()
                    )
            );
        }
    }
}