package com.cchong.watermark;

import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

final class PhotoProcessor {
    static final class Saved {
        final String name;
        final boolean motion;
        Saved(String name, boolean motion) { this.name = name; this.motion = motion; }
    }

    private static final String[] EXIF_TAGS = {
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP
    };

    private final Context activity;
    private final WatermarkRenderer renderer;
    private final VideoWatermarker videoWatermarker;

    PhotoProcessor(Context activity) {
        this.activity = activity;
        renderer = new WatermarkRenderer(activity);
        videoWatermarker = new VideoWatermarker(activity);
    }

    Saved process(Uri uri, String originalName, String colorMode, WatermarkSettings settings) throws Exception {
        byte[] input = read(activity.getContentResolver().openInputStream(uri), 256L * 1024 * 1024);
        MotionJpeg.Parts motion = MotionJpeg.split(input);
        if (motion == null && MotionJpeg.looksLikeMotion(input, originalName))
            throw new IOException("检测到动态照片，但无法提取视频；原图未修改");
        byte[] still = motion == null ? input : motion.still;
        WatermarkRenderer.Result rendered = renderer.render(still, colorMode, settings);
        File stillFile = File.createTempFile("cchong-still-", ".jpg", activity.getCacheDir());
        File inputVideo = null, outputVideo = null;
        try {
            try (OutputStream stream = new FileOutputStream(stillFile)) {
                if (!rendered.photo.compress(Bitmap.CompressFormat.JPEG, 94, stream)) throw new IOException("照片编码失败");
            }
            copyExif(still, stillFile);
            byte[] jpeg = Files.readAllBytes(stillFile.toPath());
            byte[] output;
            if (motion != null) {
                inputVideo = File.createTempFile("cchong-input-", ".mp4", activity.getCacheDir());
                outputVideo = File.createTempFile("cchong-output-", ".mp4", activity.getCacheDir());
                Files.write(inputVideo.toPath(), motion.video);
                if (!outputVideo.delete()) throw new IOException("无法准备视频输出文件");
                videoWatermarker.render(inputVideo, outputVideo, rendered.overlay);
                output = MotionJpeg.join(jpeg, motion, Files.readAllBytes(outputVideo.toPath()));
            } else output = jpeg;
            String stem = originalName.replaceAll("(?i)\\.(jpe?g|png|webp|heic|heif|avif)$", "").replaceAll("[^\\p{L}\\p{N}_ .-]", "_");
            String name = stem + (motion == null ? "_Cchong.jpg" : "_CchongMP.jpg");
            saveToGallery(name, output);
            return new Saved(name, motion != null);
        } finally {
            rendered.photo.recycle();
            rendered.overlay.recycle();
            stillFile.delete();
            if (inputVideo != null) inputVideo.delete();
            if (outputVideo != null) outputVideo.delete();
        }
    }

    private static byte[] read(InputStream stream, long maxBytes) throws IOException {
        if (stream == null) throw new IOException("无法打开照片");
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) out.size() + count > maxBytes) throw new IOException("照片超过 256 MB");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void copyExif(byte[] sourceBytes, File targetFile) throws IOException {
        ExifInterface source = new ExifInterface(new ByteArrayInputStream(sourceBytes));
        ExifInterface target = new ExifInterface(targetFile);
        for (String tag : EXIF_TAGS) {
            String value = source.getAttribute(tag);
            if (value != null) target.setAttribute(tag, value);
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
        target.saveAttributes();
    }

    private void saveToGallery(String name, byte[] data) throws IOException {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cchong");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new IOException("无法在系统相册创建照片");
        try {
            try (OutputStream stream = resolver.openOutputStream(target, "w")) {
                if (stream == null) throw new IOException("无法写入系统相册");
                stream.write(data);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(target, ready, null, null);
        } catch (Exception error) {
            resolver.delete(target, null, null);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("保存到系统相册失败", error);
        }
    }
}
