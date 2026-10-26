package com.cchong.watermark;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
final class VideoWatermarker {
    private final Context activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    VideoWatermarker(Context activity) { this.activity = activity; }

    void render(File input, File output, Bitmap stillOverlay) throws Exception {
        MediaMetadataRetriever probe = new MediaMetadataRetriever();
        int width, height;
        try {
            probe.setDataSource(input.getAbsolutePath());
            width = Integer.parseInt(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = Integer.parseInt(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        } finally { probe.release(); }
        if (width <= 0 || height <= 0) throw new IOException("无法读取动态照片视频尺寸");
        Bitmap videoOverlay = Bitmap.createScaledBitmap(stillOverlay, width, height, true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Transformer> active = new AtomicReference<>();
        main.post(() -> {
            try {
                BitmapOverlay bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(videoOverlay);
                OverlayEffect effect = new OverlayEffect(Collections.singletonList(bitmapOverlay));
                Effects effects = new Effects(Collections.emptyList(), Collections.singletonList(effect));
                EditedMediaItem media = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(effects).build();
                Transformer transformer = new Transformer.Builder(activity).addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult result) { done.countDown(); }
                    @Override public void onError(Composition composition, ExportResult result, ExportException error) {
                        failure.set(error); done.countDown();
                    }
                }).build();
                active.set(transformer);
                transformer.start(media, output.getAbsolutePath());
            } catch (Throwable error) { failure.set(error); done.countDown(); }
        });
        boolean completed = done.await(5, TimeUnit.MINUTES);
        if (!completed) {
            main.post(() -> { Transformer transformer = active.get(); if (transformer != null) transformer.cancel(); });
            videoOverlay.recycle();
            throw new IOException("动态视频处理超时");
        }
        videoOverlay.recycle();
        if (failure.get() != null) throw new IOException("动态视频处理失败", failure.get());
        if (!output.isFile() || output.length() == 0) throw new IOException("动态视频没有生成");
    }
}
