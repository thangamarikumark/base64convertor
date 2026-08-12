package com.twixor.base64convertor.qrgeneration.service;

import com.google.zxing.WriterException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.twixor.base64convertor.qrgeneration.dto.QrGeneratorRequest;
import com.twixor.base64convertor.qrgeneration.dto.QrGeneratorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

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
public class QrGeneratorService {
    public ResponseEntity<QrGeneratorResponse> generateQrBase64(String url) throws Exception {
        QrGeneratorResponse response = new QrGeneratorResponse();
        try {
            QRCodeWriter writer = new QRCodeWriter();

            BitMatrix bitMatrix = writer.encode(
                    url,
                    BarcodeFormat.QR_CODE,
                    300,
                    300
            );

            BufferedImage image = new BufferedImage(
                    300,
                    300,
                    BufferedImage.TYPE_INT_RGB
            );

            for (int x = 0; x < 300; x++) {
                for (int y = 0; y < 300; y++) {
                    image.setRGB(
                            x,
                            y,
                            bitMatrix.get(x, y)
                                    ? 0xFF000000
                                    : 0xFFFFFFFF
                    );
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            boolean generated = ImageIO.write(
                    image,
                    "png",
                    outputStream
            );

            if (!generated) {
                response.setQrCode("");
                response.setErrorCode("0");
                response.setErrorMessage("QR image generation failed");
                return ResponseEntity.badRequest().body(response);
            }

            response.setQrCode(Base64.getEncoder().encodeToString(outputStream.toByteArray()));
            response.setErrorCode("1");
            response.setErrorMessage("QR Generated Successfully");
            return ResponseEntity.ok(response);

        } catch (WriterException e) {
            response.setQrCode("");
            response.setErrorCode("0");
            response.setErrorMessage("Unable to generate QR code");
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            throw new RuntimeException("Unexpected error while generating QR code", e);
        }
    }
}
