package com.twixor.base64convertor.base64topaymenturl.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

@Service
public class QrDecoderService {

    public String decodeQr(String qrString) throws Exception {

        // Remove prefix if the API returns:
        // data:image/png;base64,xxxx
        if (qrString.contains(",")) {
            qrString = qrString.substring(
                    qrString.indexOf(",") + 1
            );
        }

        // Base64 → image bytes
        byte[] imageBytes =
                Base64.getDecoder().decode(qrString);

        // Image bytes → BufferedImage
        BufferedImage image =
                ImageIO.read(new ByteArrayInputStream(imageBytes));

        if (image == null) {
            throw new IllegalArgumentException(
                    "Invalid QR image"
            );
        }

        // Convert image to ZXing format
        BufferedImageLuminanceSource source =
                new BufferedImageLuminanceSource(image);

        BinaryBitmap bitmap =
                new BinaryBitmap(
                        new HybridBinarizer(source)
                );

        // Decode QR
        Result result =
                new MultiFormatReader().decode(bitmap);

        return result.getText();
    }
}