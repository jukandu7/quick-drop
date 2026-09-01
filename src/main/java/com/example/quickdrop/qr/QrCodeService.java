package com.example.quickdrop.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@Service
public class QrCodeService {

    private static final int IMAGE_SIZE = 320;

    public byte[] createPng(String text)
            throws WriterException, IOException {
        BitMatrix matrix = new QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                IMAGE_SIZE,
                IMAGE_SIZE,
                Map.of(EncodeHintType.MARGIN, 1)
        );

        BufferedImage image = new BufferedImage(
                IMAGE_SIZE,
                IMAGE_SIZE,
                BufferedImage.TYPE_INT_RGB
        );

        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                image.setRGB(x, y, matrix.get(x, y)
                        ? 0x000000
                        : 0xFFFFFF);
            }
        }

        try (ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return output.toByteArray();
        }
    }
}
