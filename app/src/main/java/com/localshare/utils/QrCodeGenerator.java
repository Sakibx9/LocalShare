package com.localshare.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

public class QrCodeGenerator {

    /**
     * Generates a QR code bitmap encoding the given content.
     * @param content  Text to encode (e.g. "localshare://192.168.43.1:8765/ROOM4")
     * @param size     Width and height in pixels
     * @param darkColor  QR module color
     * @param lightColor Background color
     */
    public static Bitmap generate(String content, int size, int darkColor, int lightColor) {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        try {
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? darkColor : lightColor;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bmp.setPixels(pixels, 0, width, 0, 0, width, height);
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }

    /** Convenience: dark green on black, 400px */
    public static Bitmap generateForRoom(String ip, int port, String roomCode) {
        String url = "localshare://" + ip + ":" + port + "/" + roomCode;
        return generate(url, 400, Color.parseColor("#00FF41"), Color.parseColor("#0D1117"));
    }
}
