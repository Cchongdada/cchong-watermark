package com.cchong.watermark;

final class WatermarkSettings {
    static final WatermarkSettings DEFAULT = new WatermarkSettings(1f, 1f, 1f, 1f, 1f, .5f, .83f);

    final float overall, histogramWidth, histogramHeight, metadata, signature, x, y;

    WatermarkSettings(float overall, float histogramWidth, float histogramHeight,
                      float metadata, float signature, float x, float y) {
        this.overall = overall;
        this.histogramWidth = histogramWidth;
        this.histogramHeight = histogramHeight;
        this.metadata = metadata;
        this.signature = signature;
        this.x = x;
        this.y = y;
    }

    float[] values() { return new float[]{overall, histogramWidth, histogramHeight, metadata, signature, x, y}; }
}
