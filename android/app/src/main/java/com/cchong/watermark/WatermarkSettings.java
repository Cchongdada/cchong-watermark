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

    int rotation;
    boolean noWatermark;
    WatermarkSettings rotated(int angle) {
        WatermarkSettings copy = new WatermarkSettings(overall, histogramWidth, histogramHeight, metadata, signature, x, y)
            .withFilm(filmMode, filmStock, filmIso, filmCamera);
        copy.rotation = ((angle % 360) + 360) % 360; return copy;
    }
    boolean filmMode;
    String filmStock = "", filmIso = "", filmCamera = "Pentax Espio 125M";
    WatermarkSettings withFilm(boolean enabled, String stock, String iso, String camera) {
        filmMode = enabled; filmStock = stock; filmIso = iso; filmCamera = camera; return this;
    }
    String filmText() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!filmStock.trim().isEmpty()) parts.add(english(filmStock));
        if (!filmCamera.trim().isEmpty()) parts.add(english(filmCamera));
        parts.removeAll(java.util.Collections.singleton(""));
        return android.text.TextUtils.join(" · ", parts);
    }
    private static String english(String value) { return value.replaceAll("[\\p{IsHan}]", "").trim(); }
    float[] values() { return new float[]{overall, histogramWidth, histogramHeight, metadata, signature, x, y}; }
}
