package com.cchong.watermark;

import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class WatermarkRenderer {
    static final class Result {
        final Bitmap photo;
        final Bitmap overlay;
        final Bitmap original;
        Result(Bitmap photo, Bitmap overlay, Bitmap original) { this.photo = photo; this.overlay = overlay; this.original = original; }
    }

    private final Typeface manrope;
    private final Typeface cormorant;

    WatermarkRenderer(Context context) {
        manrope = Typeface.create(Typeface.createFromAsset(context.getAssets(), "fonts/Manrope.ttf"), 500, false);
        cormorant = Typeface.create(Typeface.createFromAsset(context.getAssets(), "fonts/CormorantGaramond.ttf"), 600, false);
    }

    Result render(byte[] imageBytes, String colorMode, WatermarkSettings settings) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
        if (decoded == null) throw new IOException("无法读取照片");
        ExifInterface exif = new ExifInterface(new ByteArrayInputStream(imageBytes));
        Bitmap photo = orient(decoded, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1));
        if (photo != decoded) decoded.recycle();
        return renderBitmap(photo, exif, colorMode, settings, photo.getWidth(), photo.getHeight(), false);
    }

    Result preview(ContentResolver resolver, Uri uri, String colorMode, WatermarkSettings settings) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("无法读取预览图片");
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法读取预览图片");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("无法读取预览图片");
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }
        if (decoded == null) throw new IOException("无法读取预览图片");
        ExifInterface exif;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("无法读取照片参数");
            exif = new ExifInterface(stream);
        }
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        Bitmap photo = orient(decoded, orientation);
        if (photo != decoded) decoded.recycle();
        int originalWidth = orientation >= 5 && orientation <= 8 ? bounds.outHeight : bounds.outWidth;
        int originalHeight = orientation >= 5 && orientation <= 8 ? bounds.outWidth : bounds.outHeight;
        return renderBitmap(photo, exif, colorMode, settings, originalWidth, originalHeight, true);
    }

    private Result renderBitmap(Bitmap photo, ExifInterface exif, String colorMode,
                                WatermarkSettings settings, int originalWidth, int originalHeight, boolean keepOriginal) {
        int width = photo.getWidth(), height = photo.getHeight(), shortSide = Math.min(width, height);
        float overall = settings.overall;
        int histogramWidth = Math.max(1, Math.round(shortSide * .30f * overall * settings.histogramWidth));
        int histogramHeight = Math.max(12, Math.round(shortSide * .087f * overall * settings.histogramHeight));
        int gap = Math.max(3, Math.round(shortSide * .007f * overall));
        int metaHeight = Math.round(shortSide * .024f * overall * settings.metadata);
        int brandGap = Math.max(2, Math.round(shortSide * .003f * overall));
        int brandHeight = Math.round(shortSide * .035f * overall * settings.signature);
        int blockHeight = histogramHeight + gap + metaHeight + brandGap + brandHeight;
        float centerX = Math.max(histogramWidth / 2f, Math.min(width - histogramWidth / 2f, width * settings.x));
        int left = Math.round(centerX - histogramWidth / 2f);
        int top = Math.max(0, Math.min(height - blockHeight, Math.round(height * settings.y - blockHeight / 2f)));
        int rgb = "black".equals(colorMode) || ("auto".equals(colorMode) && average(photo, left, top, histogramWidth, blockHeight) >= 135)
            ? Color.BLACK : Color.WHITE;

        Bitmap overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);
        float[][] channels = histogram(photo);
        int axis = Math.max(2, Math.round(shortSide * .0022f));
        int curve = Math.max(1, Math.round(shortSide * .001f));
        float baseline = top + histogramHeight - axis;
        int[] fillAlpha = {45, 75, 105};
        int[] lineAlpha = {145, 190, 235};
        Path[] outlines = new Path[3];
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeJoin(Paint.Join.MITER);
        for (int channel = 0; channel < 3; channel++) {
            Path fill = new Path();
            Path outline = new Path();
            fill.moveTo(left, baseline);
            for (int i = 0; i < channels[channel].length; i++) {
                float x = left + i * (histogramWidth - 1f) / (channels[channel].length - 1f);
                float y = baseline - channels[channel][i] * Math.max(1, histogramHeight - 3 * axis) * .94f;
                if (i == 0) outline.moveTo(x, y); else outline.lineTo(x, y);
                fill.lineTo(x, y);
            }
            fill.lineTo(left + histogramWidth - 1, baseline);
            fill.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(rgb, fillAlpha[channel]));
            canvas.drawPath(fill, paint);
            outlines[channel] = outline;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(curve);
        for (int channel = 0; channel < 3; channel++) {
            paint.setColor(withAlpha(rgb, lineAlpha[channel]));
            canvas.drawPath(outlines[channel], paint);
        }
        paint.setColor(rgb);
        paint.setStrokeWidth(axis);
        canvas.drawLine(left, baseline, left + histogramWidth, baseline, paint);

        String meta = metadata(exif, originalWidth, originalHeight);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        text.setColor(rgb);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(manrope);
        float metaSize = Math.max(8, Math.round(shortSide * .017f * overall * settings.metadata));
        text.setTextSize(metaSize);
        while (metaSize > 8 && text.measureText(meta) > histogramWidth * .94f) {
            metaSize -= 1;
            text.setTextSize(metaSize);
        }
        float metaTop = top + histogramHeight + gap;
        canvas.drawText(meta, centerX, metaTop - text.getFontMetrics().top, text);
        text.setTypeface(cormorant);
        text.setTextSize(Math.max(10, Math.round(shortSide * .028f * overall * settings.signature)));
        float brandTop = metaTop + metaHeight + brandGap;
        canvas.drawText("@Cchong", centerX, brandTop - text.getFontMetrics().top, text);

        Bitmap watermarked = photo.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(watermarked).drawBitmap(overlay, 0, 0, null);
        if (!keepOriginal) photo.recycle();
        return new Result(watermarked, overlay, keepOriginal ? photo : null);
    }

    private static int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }

    private static Bitmap orient(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.postScale(-1, 1); break;
            case 3: matrix.postRotate(180); break;
            case 4: matrix.postScale(1, -1); break;
            case 5: matrix.postScale(-1, 1); matrix.postRotate(90); break;
            case 6: matrix.postRotate(90); break;
            case 7: matrix.postScale(-1, 1); matrix.postRotate(270); break;
            case 8: matrix.postRotate(270); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static int average(Bitmap bitmap, int x, int y, int w, int h) {
        long total = 0;
        for (int row = 0; row < 16; row++) for (int col = 0; col < 32; col++) {
            int px = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, x + col * w / 32), Math.min(bitmap.getHeight() - 1, y + row * h / 16));
            total += (299L * Color.red(px) + 587L * Color.green(px) + 114L * Color.blue(px)) / 1000;
        }
        return (int) (total / (32 * 16));
    }

    private static float[][] histogram(Bitmap bitmap) {
        float ratio = Math.min(1f, 512f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)), Math.max(1, Math.round(bitmap.getHeight() * ratio)), false);
        int[] pixels = new int[sample.getWidth() * sample.getHeight()];
        sample.getPixels(pixels, 0, sample.getWidth(), 0, 0, sample.getWidth(), sample.getHeight());
        if (sample != bitmap) sample.recycle();
        float[][] bins = new float[3][128];
        float peak = 1;
        for (int pixel : pixels) {
            bins[0][Color.red(pixel) / 2]++;
            bins[1][Color.green(pixel) / 2]++;
            bins[2][Color.blue(pixel) / 2]++;
        }
        for (float[] channel : bins) for (float value : channel) peak = Math.max(peak, value);
        double denominator = Math.log1p(30);
        for (float[] channel : bins) for (int i = 0; i < channel.length; i++) channel[i] = (float) (Math.log1p(30 * channel[i] / peak) / denominator);
        return bins;
    }

    private static String metadata(ExifInterface exif, int width, int height) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0);
        if (iso <= 0) iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0);
        if (iso > 0) parts.add("ISO " + iso);
        double exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0);
        if (exposure > 0) parts.add(exposure >= 1 ? String.format(Locale.US, "%.1fs", exposure) : "1/" + Math.round(1 / exposure) + "s");
        double aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0);
        if (aperture > 0) parts.add(String.format(Locale.US, "f/%.2f", aperture).replaceAll("0+$", "").replaceAll("\\.$", ""));
        if (parts.size() < 3) {
            double[] gps = exif.getLatLong();
            if (gps != null) parts.add(String.format(Locale.US, "%.5f, %.5f", gps[0], gps[1]));
        }
        if (parts.size() < 3) parts.add(width + " × " + height);
        return String.join("   ·   ", parts.subList(0, Math.min(3, parts.size())));
    }
}
