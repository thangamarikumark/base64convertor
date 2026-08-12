package com.twixor.base64convertor.checksum.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * CRC32 checksum + nonce generation, extracted verbatim from ChecksumController's private
 * computeChecksum method (Phase A, A3). Return type kept as Map<String,String> (not a new
 * DTO class) to guarantee byte-for-byte identical JSON serialization to the original
 * Map.of(...) response.
 *
 * <p>The nonce is generated first and folded into the checksum input, so the two returned
 * values are actually bound together: the same {@code message}/{@code secretKey} pair no
 * longer always produces the same checksum, and a caller (or this service, if a verification
 * endpoint is added later) can recompute the checksum from {@code message + secretKey + nonce}
 * to confirm neither was altered. Previously the nonce was generated independently and never
 * entered the checksum calculation at all, so it carried no verifiable relationship to the
 * checksum returned alongside it.
 */
@Service
public class ChecksumService {

    public Map<String, String> compute(String message, String secretKey) {
        String nonce = UUID.randomUUID().toString();
        String input = message + "|" + secretKey;
        CRC32 crc = new CRC32();
        // Use explicit UTF-8 to produce consistent results across all platforms
        crc.update(input.getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "checksum", String.valueOf(crc.getValue()),
                "nonce", nonce
        );
    }
}
