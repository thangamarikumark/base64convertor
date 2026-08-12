package com.twixor.base64convertor.health.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files/convert/test")
public class TestController {

    private static final Logger logger = LogManager.getLogger(TestController.class);

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        System.out.println("Ping endpoint hit!");
        return ResponseEntity.ok("Pong! Service is alive.");
    }

    @PostMapping("/echo")
    public ResponseEntity<String> echo(@RequestBody String body) {
        // Logging remediation, HIGH #1: never log the raw request body.
        logger.debug("Echo endpoint invoked. BodyLength={}", body != null ? body.length() : 0);
        return ResponseEntity.ok("Received: " + body);
    }
}
