package com.localshare.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;

import java.io.File;

public class ThumbnailUtils {

    private static final int THUMB_SIZE = 120; // px

    /**
     * Generate a thumbnail bitmap for a file.
     * Returns null if not possible (caller should show icon instead).
     */
    public static Bitmap getThumbnail(File file, String mimeType) {
        if (mimeType == null) return null;

        if (mimeType.startsWith("image/")) {
            return decodeImageThumb(file);
        }
        if (mimeType.startsWith("video/")) {
            return decodeVideoThumb(file);
        }
        return null;
    }

    private static Bitmap decodeImageThumb(File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);

            int scale = 1;
            while (opts.outWidth / scale > THUMB_SIZE * 2 || opts.outHeight / scale > THUMB_SIZE * 2) {
                scale *= 2;
            }
            opts.inSampleSize = scale;
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // smaller memory

            Bitmap full = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (full == null) return null;
            return cropToSquare(full, THUMB_SIZE);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeVideoThumb(File file) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());
            Bitmap frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            return cropToSquare(frame, THUMB_SIZE);
        } catch (Exception e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private static Bitmap cropToSquare(Bitmap src, int size) {
        int w = src.getWidth(), h = src.getHeight();
        int min = Math.min(w, h);
        int x = (w - min) / 2, y = (h - min) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, min, min);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, size, size, true);
        if (cropped != src) cropped.recycle();
        if (src != scaled) src.recycle();
        return scaled;
    }

    /** Create a placeholder icon bitmap for audio/other files */
    public static Bitmap makePlaceholder(String label, int bgColor, int textColor) {
        Bitmap bmp = Bitmap.createBitmap(THUMB_SIZE, THUMB_SIZE, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(bgColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(textColor);
        paint.setTextSize(32);
        paint.setTextAlign(Paint.Align.CENTER);
        Rect bounds = new Rect();
        paint.getTextBounds(label, 0, label.length(), bounds);
        canvas.drawText(label, THUMB_SIZE / 2f, THUMB_SIZE / 2f - bounds.exactCenterY(), paint);
        return bmp;
    }
}
