package com.cchong.watermark;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PHOTOS = 1001;
    private static final int BG = Color.rgb(246, 247, 243), INK = Color.rgb(28, 39, 33);
    private static final int MUTED = Color.rgb(111, 120, 112), LINE = Color.rgb(218, 225, 216);
    private static final int SOFT = Color.rgb(235, 239, 233);
    private final List<Uri> selected = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Map<Uri, Choice> overrides = new HashMap<>();
    private final Set<Uri> failed = new HashSet<>();
    private static final class Choice {
        final WatermarkSettings settings;
        final String color;
        Choice(WatermarkSettings settings, String color) { this.settings = settings; this.color = color; }
    }
    private Uri previewUri;
    private Uri displayedUri;
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private final Handler jobHandler = new Handler(Looper.getMainLooper());
    private final Runnable jobPoll = new Runnable() {
        @Override public void run() { syncJob(); jobHandler.postDelayed(this, 800); }
    };
    private String currentJobId;
    private int jobRevision = -1;
    private Runnable pendingPreview;
    private final float[] values = {1, 1, 1, 1, 1, .5f, .83f};
    private final float[] globalValues = {1, 1, 1, 1, 1, .5f, .83f};
    private final float[] min = {.5f, .5f, .5f, .6f, .6f, 0, 0};
    private final float[] max = {1.6f, 1.5f, 1.8f, 1.6f, 1.6f, 1, 1};
    private final String[] labels = {"整体大小", "直方图宽度", "直方图高度", "参数文字大小", "署名大小", "左右位置", "上下位置"};
    private final SeekBar[] sliders = new SeekBar[7];
    private final TextView[] sliderValues = new TextView[7], colorButtons = new TextView[3];
    private LinearLayout queue;
    private TextView queueCount, clearButton, processButton, status, previewLabel, retryButton, scopeNote;
    private TextView scopeAllButton, scopeOneButton, compareButton, motionButton;
    private View pickerCard, statusCard;
    private ImageView previewImage;
    private FrameLayout previewStage, motionFrame;
    private ImageView motionOverlayView;
    private VideoView motionVideoView;
    private File motionTempFile;
    private boolean motionPlaying;
    private ProgressBar progress;
    private Typeface sans, serif;
    private String colorMode = "auto";
    private String globalColor = "auto";
    private boolean scopeOne;
    private boolean busy;
    private int previewGeneration;
    private Bitmap previewBitmap;
    private Bitmap previewOriginal;
    private Bitmap previewOverlay;
    private static final String PREFERENCES = "watermark_settings";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        loadSettings();
        sans = Typeface.createFromAsset(getAssets(), "fonts/Manrope.ttf");
        serif = Typeface.createFromAsset(getAssets(), "fonts/CormorantGaramond.ttf");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            view.setPadding(0, top, 0, bottom); return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column(); content.setPadding(dp(22), dp(24), dp(22), dp(24)); scroll.addView(content);

        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView monogram = text("C", 33, Color.WHITE, serif); monogram.setGravity(Gravity.CENTER);
        monogram.setBackground(shape(INK, 15, 0)); header.addView(monogram, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = column(); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1); ip.leftMargin = dp(11);
        header.addView(identity, ip); identity.addView(text("Cchong", 28, INK, serif)); identity.addView(text("照片水印", 11, MUTED, sans));
        TextView offline = text("离线", 11, INK, sans); offline.setGravity(Gravity.CENTER); offline.setPadding(dp(12), dp(7), dp(12), dp(7));
        offline.setBackground(shape(SOFT, 30, 0)); header.addView(offline); content.addView(header); content.addView(space(28));

        content.addView(sectionTitle("照片")); content.addView(space(10));
        LinearLayout pick = column(); pick.setGravity(Gravity.CENTER); pick.setPadding(dp(18), dp(20), dp(18), dp(20));
        GradientDrawable pickBorder = shape(Color.WHITE, 18, LINE); pickBorder.setStroke(dp(1), LINE, dp(6), dp(5)); pick.setBackground(pickBorder);
        TextView plus = text("+", 32, INK, sans); plus.setGravity(Gravity.CENTER); plus.setBackground(shape(SOFT, 50, 0));
        pick.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(52))); pick.addView(space(8));
        pick.addView(text("从相册选择", 18, INK, sans)); pick.addView(space(3)); pick.addView(text("可多选 · 最多 100 张", 12, MUTED, sans));
        pick.setOnClickListener(v -> choosePhotos()); pickerCard = pick; content.addView(pick, new LinearLayout.LayoutParams(-1, dp(160)));
        content.addView(space(22));

        LinearLayout previewHeader = row(); previewHeader.setGravity(Gravity.CENTER_VERTICAL);
        previewHeader.addView(sectionTitle("实时预览")); previewLabel = text("选择照片后显示", 11, MUTED, sans);
        previewLabel.setSingleLine(true); previewLabel.setEllipsize(TextUtils.TruncateAt.END);
        previewLabel.setGravity(Gravity.END); previewHeader.addView(previewLabel, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(previewHeader); content.addView(space(10));
        LinearLayout previewCard = column(); previewCard.setPadding(dp(8), dp(8), dp(8), dp(12)); previewCard.setBackground(shape(Color.WHITE, 16, LINE));
        previewStage = new FrameLayout(this); previewStage.setBackground(shape(SOFT, 10, 0));
        previewCard.addView(previewStage, new LinearLayout.LayoutParams(-1, dp(280)));
        previewImage = new ImageView(this); previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewStage.addView(previewImage, new FrameLayout.LayoutParams(-1, -1));
        motionFrame = new FrameLayout(this); motionFrame.setVisibility(View.GONE);
        FrameLayout.LayoutParams motionFrameParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        previewStage.addView(motionFrame, motionFrameParams);
        motionVideoView = new VideoView(this); motionFrame.addView(motionVideoView, new FrameLayout.LayoutParams(-1, -1));
        motionOverlayView = new ImageView(this); motionOverlayView.setScaleType(ImageView.ScaleType.FIT_XY);
        motionFrame.addView(motionOverlayView, new FrameLayout.LayoutParams(-1, -1));
        previewImage.setOnTouchListener(this::onPreviewTouch);
        TextView hint = text("拖动预览图调整位置 · 效果同步用于导出", 11, MUTED, sans);
        hint.setPadding(dp(8), dp(10), dp(8), 0); previewCard.addView(hint); content.addView(previewCard);
        LinearLayout previewActions = row();
        compareButton = text("按住查看原图", 12, INK, sans); compareButton.setGravity(Gravity.CENTER);
        compareButton.setBackground(shape(SOFT, 10, LINE));
        compareButton.setOnTouchListener(this::onCompareTouch);
        LinearLayout.LayoutParams compareParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        compareParams.topMargin = dp(9); previewActions.addView(compareButton, compareParams);
        motionButton = text("播放动态照片", 12, INK, sans); motionButton.setGravity(Gravity.CENTER);
        motionButton.setBackground(shape(SOFT, 10, LINE)); motionButton.setVisibility(View.GONE);
        motionButton.setOnClickListener(v -> toggleMotionPreview());
        LinearLayout.LayoutParams motionParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        motionParams.leftMargin = dp(8); motionParams.topMargin = dp(9); previewActions.addView(motionButton, motionParams);
        content.addView(previewActions);
        content.addView(space(26));

        content.addView(sectionTitle("水印颜色")); content.addView(space(10));
        LinearLayout selector = row(); selector.setPadding(dp(4), dp(4), dp(4), dp(4)); selector.setBackground(shape(SOFT, 13, 0));
        String[] colorLabels = {"自动", "白色", "黑色"};
        for (int i = 0; i < 3; i++) { final int index = i; TextView segment = text(colorLabels[i], 14, INK, sans);
            segment.setGravity(Gravity.CENTER); segment.setOnClickListener(v -> selectColor(index)); colorButtons[i] = segment;
            selector.addView(segment, new LinearLayout.LayoutParams(0, dp(44), 1)); }
        content.addView(selector); selectColor("white".equals(colorMode) ? 1 : "black".equals(colorMode) ? 2 : 0); content.addView(space(23));

        LinearLayout scopeBar = row(); scopeBar.setPadding(dp(4), dp(4), dp(4), dp(4)); scopeBar.setBackground(shape(SOFT, 12, 0));
        scopeAllButton = text("整批设置", 13, INK, sans); scopeAllButton.setGravity(Gravity.CENTER);
        scopeOneButton = text("当前照片", 13, INK, sans); scopeOneButton.setGravity(Gravity.CENTER);
        scopeAllButton.setOnClickListener(v -> { scopeOne = false; syncEditor(); });
        scopeOneButton.setOnClickListener(v -> { if (previewUri != null) { scopeOne = true; syncEditor(); } });
        scopeBar.addView(scopeAllButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        scopeBar.addView(scopeOneButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        content.addView(scopeBar);
        scopeNote = text("调整后应用到没有单独设置的照片", 11, MUTED, sans);
        scopeNote.setPadding(dp(2), dp(8), dp(2), dp(12)); content.addView(scopeNote);

        LinearLayout settingsHeader = row(); settingsHeader.setGravity(Gravity.CENTER_VERTICAL);
        settingsHeader.addView(sectionTitle("大小与位置"));
        TextView reset = text("恢复默认", 13, INK, sans); reset.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        reset.setOnClickListener(v -> resetSettings()); settingsHeader.addView(reset, new LinearLayout.LayoutParams(0, dp(38), 1));
        content.addView(settingsHeader);
        LinearLayout controls = column(); controls.setPadding(dp(14), dp(10), dp(14), dp(8)); controls.setBackground(shape(Color.WHITE, 16, LINE));
        for (int i = 0; i < values.length; i++) addSlider(controls, i);
        content.addView(controls); content.addView(space(26));

        LinearLayout queueHeader = row(); queueHeader.setGravity(Gravity.CENTER_VERTICAL); queueHeader.addView(sectionTitle("待处理"));
        queueCount = text("0", 12, MUTED, sans); queueCount.setGravity(Gravity.CENTER); queueCount.setBackground(shape(SOFT, 25, 0));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(28), dp(25)); cp.leftMargin = dp(9); queueHeader.addView(queueCount, cp);
        clearButton = text("清空", 13, MUTED, sans); clearButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        clearButton.setOnClickListener(v -> { if (!busy) {
            selected.clear(); names.clear(); overrides.clear(); failed.clear();
            ProcessingService.clear(this); currentJobId = null; jobRevision = -1;
            renderQueue(); syncEditor(); schedulePreview();
        } });
        queueHeader.addView(clearButton, new LinearLayout.LayoutParams(0, dp(40), 1)); content.addView(queueHeader); content.addView(space(8));
        queue = column(); content.addView(queue); content.addView(space(15));
        LinearLayout result = column(); result.setPadding(dp(16), dp(15), dp(16), dp(15)); result.setBackground(shape(SOFT, 13, 0));
        status = text("", 13, INK, sans); status.setLineSpacing(dp(3), 1f); result.addView(status);
        statusCard = result; content.addView(result); result.setVisibility(View.GONE);

        LinearLayout bottom = column(); bottom.setPadding(dp(22), dp(12), dp(22), dp(17)); bottom.setBackgroundColor(Color.WHITE);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        bottom.addView(progress, new LinearLayout.LayoutParams(-1, dp(4))); bottom.addView(space(7));
        retryButton = text("只重试失败照片", 13, INK, sans); retryButton.setGravity(Gravity.CENTER);
        retryButton.setBackground(shape(SOFT, 10, LINE)); retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> retryFailed());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, dp(40)); retryParams.bottomMargin = dp(8);
        bottom.addView(retryButton, retryParams);
        processButton = text("开始处理并保存到相册  →", 16, Color.WHITE, sans); processButton.setGravity(Gravity.CENTER);
        processButton.setBackground(shape(INK, 13, 0)); processButton.setOnClickListener(v -> startProcessing());
        bottom.addView(processButton, new LinearLayout.LayoutParams(-1, dp(55))); root.addView(bottom); setContentView(root);
        renderQueue();
        syncEditor();
    }

    private void addSlider(LinearLayout parent, int index) {
        LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(labels[index], 13, INK, sans), new LinearLayout.LayoutParams(0, -2, 1));
        sliderValues[index] = text("100%", 12, MUTED, sans); sliderValues[index].setGravity(Gravity.END); row.addView(sliderValues[index]);
        parent.addView(row); SeekBar slider = new SeekBar(this); slider.setMax(100); slider.setProgress(progressFor(index));
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(INK));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                values[index] = min[index] + (max[index] - min[index]) * progress / 100f;
                updateValue(index); commitEditor(); schedulePreview();
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        sliders[index] = slider; parent.addView(slider); updateValue(index);
    }

    private int progressFor(int index) { return Math.round(100 * (values[index] - min[index]) / (max[index] - min[index])); }
    private void updateValue(int index) { sliderValues[index].setText(Math.round(values[index] * 100) + "%"); }
    private WatermarkSettings snapshot() { return new WatermarkSettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6]); }
    private WatermarkSettings globalSnapshot() {
        return new WatermarkSettings(globalValues[0], globalValues[1], globalValues[2], globalValues[3],
            globalValues[4], globalValues[5], globalValues[6]);
    }
    private Choice effective(Uri uri) {
        Choice choice = overrides.get(uri);
        return choice == null ? new Choice(globalSnapshot(), globalColor) : choice;
    }
    private void commitEditor() {
        if (scopeOne && previewUri != null) overrides.put(previewUri, new Choice(snapshot(), colorMode));
        else { System.arraycopy(values, 0, globalValues, 0, values.length); globalColor = colorMode; saveSettings(); }
        if (queue != null) renderQueue();
    }
    private void syncEditor() {
        Choice choice = scopeOne && previewUri != null ? effective(previewUri) : new Choice(globalSnapshot(), globalColor);
        System.arraycopy(choice.settings.values(), 0, values, 0, values.length);
        colorMode = choice.color;
        for (int i = 0; i < values.length; i++) if (sliders[i] != null) { sliders[i].setProgress(progressFor(i)); updateValue(i); }
        updateColorButtons();
        if (scopeAllButton != null) {
            scopeAllButton.setBackground(shape(scopeOne ? Color.TRANSPARENT : INK, 9, 0));
            scopeAllButton.setTextColor(scopeOne ? INK : Color.WHITE);
            scopeOneButton.setBackground(shape(scopeOne ? INK : Color.TRANSPARENT, 9, 0));
            scopeOneButton.setTextColor(scopeOne ? Color.WHITE : INK);
            scopeOneButton.setAlpha(previewUri == null ? .4f : 1f);
            scopeNote.setText(scopeOne ? "只调整当前照片；其他照片继续使用整批设置" :
                overrides.containsKey(previewUri) ? "这张照片已有单独设置，预览显示它的实际效果" : "调整后应用到没有单独设置的照片");
        }
    }
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        for (int i = 0; i < values.length; i++) {
            float value = prefs.getFloat("value_" + i, values[i]);
            if (Float.isFinite(value) && value >= min[i] && value <= max[i]) values[i] = globalValues[i] = value;
        }
        String saved = prefs.getString("color", "auto");
        if ("white".equals(saved) || "black".equals(saved)) colorMode = globalColor = saved;
    }
    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit();
        for (int i = 0; i < values.length; i++) editor.putFloat("value_" + i, globalValues[i]);
        editor.putString("color", globalColor).apply();
    }
    private void resetSettings() {
        if (busy) return;
        float[] initial = {1, 1, 1, 1, 1, .5f, .83f};
        System.arraycopy(initial, 0, values, 0, values.length);
        colorMode = "auto"; commitEditor(); syncEditor(); schedulePreview();
    }
    private void selectColor(int index) {
        colorMode = index == 1 ? "white" : index == 2 ? "black" : "auto";
        commitEditor(); updateColorButtons();
        if (previewImage != null) schedulePreview();
    }
    private void updateColorButtons() {
        int index = "white".equals(colorMode) ? 1 : "black".equals(colorMode) ? 2 : 0;
        for (int i = 0; i < colorButtons.length; i++) {
            if (colorButtons[i] == null) continue;
            colorButtons[i].setTextColor(i == index ? Color.WHITE : INK);
            colorButtons[i].setBackground(shape(i == index ? INK : Color.TRANSPARENT, 10, 0));
        }
    }

    private void choosePhotos() {
        if (busy || selected.size() >= 100) return;
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33 || Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES); intent.setType("image/*");
            int limit = Math.min(100 - selected.size(), MediaStore.getPickImagesMaxLimit());
            if (limit > 1) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, limit);
        } else { intent = galleryFallback(); }
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try { startActivityForResult(intent, PICK_PHOTOS); }
        catch (ActivityNotFoundException error) { startActivityForResult(galleryFallback(), PICK_PHOTOS); }
    }
    private Intent galleryFallback() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true); return intent;
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PHOTOS || resultCode != RESULT_OK || data == null) return;
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) add(data.getData());
        renderQueue(); syncEditor(); schedulePreview();
    }
    private void add(Uri uri) {
        if (selected.size() >= 100 || selected.contains(uri)) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        selected.add(uri); String name = "照片 " + selected.size();
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        names.add(name == null ? "照片 " + selected.size() : name);
    }
    private void schedulePreview() {
        int generation = ++previewGeneration;
        stopMotionPreview();
        if (selected.isEmpty()) {
            previewUri = null;
            displayedUri = null;
            previewLabel.setText("选择照片后显示"); clearPreviewImage();
            return;
        }
        if (!selected.contains(previewUri)) previewUri = selected.get(0);
        Uri uri = previewUri; Choice choice = effective(uri);
        String mode = choice.color; WatermarkSettings settings = choice.settings;
        String previewName = names.get(selected.indexOf(uri));
        if (!uri.equals(displayedUri)) clearPreviewImage();
        previewLabel.setText(previewName); previewImage.setAlpha(.55f);
        if (pendingPreview != null) previewHandler.removeCallbacks(pendingPreview);
        pendingPreview = () -> previewWorker.execute(() -> {
            if (generation != previewGeneration) return;
            WatermarkRenderer.Result result = null;
            try {
                result = new WatermarkRenderer(this).preview(getContentResolver(), uri, mode, settings);
                Bitmap image = result.photo, original = result.original, overlay = result.overlay;
                boolean hasMotion = looksLikeMotion(uri, previewName);
                runOnUiThread(() -> {
                    if (generation != previewGeneration || isFinishing()) {
                        image.recycle(); original.recycle(); overlay.recycle(); return;
                    }
                    clearPreviewImage();
                    previewImage.setImageBitmap(image); previewImage.setAlpha(1f);
                    previewBitmap = image; previewOriginal = original; previewOverlay = overlay; displayedUri = uri;
                    compareButton.setAlpha(1f);
                    motionButton.setVisibility(hasMotion ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                if (result != null) {
                    result.photo.recycle(); result.overlay.recycle();
                    if (result.original != null) result.original.recycle();
                }
                runOnUiThread(() -> { if (generation == previewGeneration) {
                    clearPreviewImage(); previewLabel.setText("无法预览，仍可尝试处理"); previewImage.setAlpha(1f);
                    motionButton.setVisibility(View.GONE);
                } });
            }
        });
        previewHandler.postDelayed(pendingPreview, 180);
    }
    private void clearPreviewImage() {
        previewImage.setImageDrawable(null);
        if (previewBitmap != null) { previewBitmap.recycle(); previewBitmap = null; }
        if (previewOriginal != null) { previewOriginal.recycle(); previewOriginal = null; }
        if (previewOverlay != null) { previewOverlay.recycle(); previewOverlay = null; }
        displayedUri = null;
    }
    private boolean onCompareTouch(View view, MotionEvent event) {
        if (previewOriginal == null || previewBitmap == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { previewImage.setImageBitmap(previewOriginal); return true; }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            previewImage.setImageBitmap(previewBitmap); return true;
        }
        return true;
    }
    private boolean looksLikeMotion(Uri uri, String name) {
        if (name.toUpperCase(Locale.ROOT).startsWith("MVIMG_")) return true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return false;
            byte[] header = new byte[524288]; int total = 0, count;
            while (total < header.length && (count = input.read(header, total, header.length - total)) > 0) total += count;
            return MotionJpeg.looksLikeMotion(java.util.Arrays.copyOf(header, total), name);
        } catch (Exception error) { return false; }
    }
    private void toggleMotionPreview() {
        if (motionPlaying) { stopMotionPreview(); return; }
        if (previewUri == null || previewOverlay == null) return;
        Uri uri = previewUri; int generation = previewGeneration;
        motionButton.setText("正在准备…"); motionButton.setEnabled(false);
        previewWorker.execute(() -> {
            File videoFile = null;
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new Exception("无法读取动态照片");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if ((long) bytes.size() + count > 256L * 1024 * 1024) throw new Exception("动态照片超过 256 MB");
                    bytes.write(buffer, 0, count);
                }
                MotionJpeg.Parts parts = MotionJpeg.split(bytes.toByteArray());
                if (parts == null) throw new Exception("没有找到可播放的动态视频");
                videoFile = File.createTempFile("cchong-preview-", ".mp4", getCacheDir());
                try (FileOutputStream output = new FileOutputStream(videoFile)) { output.write(parts.video); }
                MediaMetadataRetriever probe = new MediaMetadataRetriever();
                int width, height;
                try {
                    probe.setDataSource(videoFile.getAbsolutePath());
                    width = Integer.parseInt(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    height = Integer.parseInt(probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                } finally { probe.release(); }
                File ready = videoFile;
                runOnUiThread(() -> {
                    if (generation != previewGeneration || !uri.equals(previewUri) || isFinishing()) { ready.delete(); return; }
                    stopMotionPreview(); motionTempFile = ready; motionPlaying = true;
                    motionOverlayView.setImageBitmap(previewOverlay);
                    motionFrame.setVisibility(View.VISIBLE); previewImage.setVisibility(View.INVISIBLE);
                    previewStage.post(() -> {
                        float scale = Math.min((float) previewStage.getWidth() / width, (float) previewStage.getHeight() / height);
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), Gravity.CENTER);
                        motionFrame.setLayoutParams(params);
                    });
                    motionVideoView.setVideoPath(ready.getAbsolutePath());
                    motionVideoView.setOnPreparedListener(player -> { player.setLooping(true); motionVideoView.start(); });
                    motionVideoView.setOnErrorListener((player, what, extra) -> {
                        stopMotionPreview(); showStatus("动态照片播放失败；静态预览仍可使用。"); return true;
                    });
                    motionButton.setEnabled(true); motionButton.setText("停止播放");
                });
            } catch (Exception error) {
                if (videoFile != null) videoFile.delete();
                runOnUiThread(() -> { if (generation == previewGeneration) {
                    motionButton.setEnabled(true); motionButton.setText("播放动态照片"); showStatus(error.getMessage());
                } });
            }
        });
    }
    private void stopMotionPreview() {
        if (motionVideoView == null) return;
        motionVideoView.stopPlayback(); motionFrame.setVisibility(View.GONE);
        previewImage.setVisibility(View.VISIBLE); motionOverlayView.setImageDrawable(null);
        motionPlaying = false;
        if (motionTempFile != null) { motionTempFile.delete(); motionTempFile = null; }
        if (motionButton != null) { motionButton.setText("播放动态照片"); motionButton.setEnabled(true); }
    }
    private boolean onPreviewTouch(View view, MotionEvent event) {
        if (busy || previewBitmap == null || selected.isEmpty()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !scopeOne) { scopeOne = true; syncEditor(); }
            float scale = Math.min((float) view.getWidth() / previewBitmap.getWidth(), (float) view.getHeight() / previewBitmap.getHeight());
            float w = previewBitmap.getWidth() * scale, h = previewBitmap.getHeight() * scale;
            float left = (view.getWidth() - w) / 2f, top = (view.getHeight() - h) / 2f;
            values[5] = Math.max(0, Math.min(1, (event.getX() - left) / w));
            values[6] = Math.max(0, Math.min(1, (event.getY() - top) / h));
            sliders[5].setProgress(progressFor(5)); sliders[6].setProgress(progressFor(6)); updateValue(5); updateValue(6);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) { commitEditor(); schedulePreview(); return true; }
        return false;
    }

    private void renderQueue() {
        if (!selected.contains(previewUri)) previewUri = selected.isEmpty() ? null : selected.get(0);
        queue.removeAllViews(); queueCount.setText(String.valueOf(selected.size()));
        clearButton.setVisibility(selected.isEmpty() ? View.INVISIBLE : View.VISIBLE); clearButton.setEnabled(!busy);
        pickerCard.setAlpha(busy || selected.size() >= 100 ? .5f : 1f);
        processButton.setEnabled(!busy && !selected.isEmpty());
        processButton.setText(busy ? "正在处理…" : "开始处理并保存到相册  →");
        processButton.setBackground(shape(processButton.isEnabled() ? INK : Color.rgb(179, 188, 179), 13, 0));
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(!busy && !failed.isEmpty() ? View.VISIBLE : View.GONE);
        for (SeekBar slider : sliders) if (slider != null) slider.setEnabled(!busy);
        for (TextView button : colorButtons) if (button != null) button.setEnabled(!busy);
        if (scopeAllButton != null) { scopeAllButton.setEnabled(!busy); scopeOneButton.setEnabled(!busy); }
        if (selected.isEmpty()) {
            TextView empty = text("还没有选择照片", 13, MUTED, sans); empty.setGravity(Gravity.CENTER);
            empty.setBackground(shape(Color.WHITE, 13, LINE)); queue.addView(empty, new LinearLayout.LayoutParams(-1, dp(70))); return;
        }
        for (int i = 0; i < names.size(); i++) {
            final int index = i; LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(8), dp(12), dp(8));
            item.setBackground(shape(selected.get(i).equals(previewUri) ? SOFT : Color.WHITE, 12, selected.get(i).equals(previewUri) ? INK : LINE));
            item.setOnClickListener(v -> { if (!busy) { previewUri = selected.get(index); renderQueue(); syncEditor(); schedulePreview(); } });
            TextView ext = text(extension(names.get(i)), 10, INK, sans); ext.setGravity(Gravity.CENTER); ext.setBackground(shape(SOFT, 8, 0));
            item.addView(ext, new LinearLayout.LayoutParams(dp(43), dp(43)));
            TextView filename = text(names.get(i), 13, INK, sans); filename.setSingleLine(true); filename.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1); np.leftMargin = dp(10); item.addView(filename, np);
            if (overrides.containsKey(selected.get(i)) || failed.contains(selected.get(i))) {
                TextView flag = text(failed.contains(selected.get(i)) ? "重试" : "单独", 10,
                    failed.contains(selected.get(i)) ? Color.rgb(155, 57, 49) : INK, sans);
                item.addView(flag);
            }
            TextView remove = text("×", 23, MUTED, sans); remove.setGravity(Gravity.CENTER);
            remove.setOnClickListener(v -> { if (!busy) {
                Uri removed = selected.remove(index); names.remove(index); overrides.remove(removed); failed.remove(removed);
                renderQueue(); syncEditor(); schedulePreview();
            } });
            item.addView(remove, new LinearLayout.LayoutParams(dp(32), dp(40)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(62)); p.bottomMargin = dp(8); queue.addView(item, p);
        }
    }
    private void startProcessing() { dispatch(new ArrayList<>(selected)); }
    private void retryFailed() {
        List<Uri> retry = new ArrayList<>();
        for (Uri uri : selected) if (failed.contains(uri)) retry.add(uri);
        dispatch(retry);
    }
    private void dispatch(List<Uri> input) {
        if (busy || input.isEmpty()) return;
        JSONObject existing = ProcessingService.read(this);
        if (existing != null && existing.optBoolean("running", false)) {
            currentJobId = existing.optString("id"); jobRevision = -1; syncJob(); return;
        }
        try {
            JSONArray items = new JSONArray();
            for (Uri uri : input) {
                Choice choice = effective(uri);
                JSONObject item = new JSONObject();
                item.put("uri", uri.toString());
                item.put("name", names.get(selected.indexOf(uri)));
                item.put("color", choice.color);
                item.put("state", "pending");
                JSONArray dimensions = new JSONArray();
                for (float value : choice.settings.values()) dimensions.put(value);
                item.put("settings", dimensions);
                items.put(item);
                failed.remove(uri);
            }
            JSONObject job = new JSONObject();
            currentJobId = UUID.randomUUID().toString(); job.put("id", currentJobId);
            job.put("items", items); job.put("running", true); job.put("revision", 0);
            Intent intent = new Intent(this, ProcessingService.class);
            intent.putExtra(ProcessingService.EXTRA_JOB, job.toString());
            startForegroundService(intent);
            busy = true; jobRevision = -1; renderQueue();
            showStatus("正在准备处理 " + input.size() + " 张照片…");
        } catch (Exception error) {
            busy = false; renderQueue(); showStatus("无法开始处理：" + error.getMessage());
        }
    }
    private void syncJob() {
        JSONObject job = ProcessingService.read(this);
        if (job == null) return;
        String id = job.optString("id");
        if (currentJobId == null) {
            if (!selected.isEmpty()) return;
            currentJobId = id;
        }
        if (!currentJobId.equals(id) || jobRevision == job.optInt("revision", -1)) return;
        jobRevision = job.optInt("revision", -1);
        JSONArray items = job.optJSONArray("items");
        if (items == null) return;
        if (selected.isEmpty()) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                Uri uri = Uri.parse(item.optString("uri"));
                selected.add(uri); names.add(item.optString("name", "照片"));
                JSONArray dimensions = item.optJSONArray("settings");
                if (dimensions != null && dimensions.length() == 7) {
                    WatermarkSettings setting = new WatermarkSettings(
                        (float) dimensions.optDouble(0, 1), (float) dimensions.optDouble(1, 1),
                        (float) dimensions.optDouble(2, 1), (float) dimensions.optDouble(3, 1),
                        (float) dimensions.optDouble(4, 1), (float) dimensions.optDouble(5, .5),
                        (float) dimensions.optDouble(6, .83));
                    overrides.put(uri, new Choice(setting, item.optString("color", "auto")));
                }
            }
            previewUri = selected.isEmpty() ? null : selected.get(0);
            syncEditor(); schedulePreview();
        }
        failed.clear(); int good = 0, bad = 0, done = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String state = item.optString("state");
            if ("success".equals(state)) { good++; done++; detail.append("✓ ").append(item.optString("output")).append('\n'); }
            if ("failed".equals(state)) { bad++; done++; failed.add(Uri.parse(item.optString("uri")));
                detail.append("× ").append(item.optString("name")).append("：").append(item.optString("error")).append('\n'); }
        }
        busy = job.optBoolean("running", false);
        renderQueue();
        showStatus(busy ? "正在处理：已完成 " + done + "/" + items.length() + " 张。可以切换应用，处理会继续。"
            : "处理完成：" + good + " 张成功，" + bad + " 张失败。\n已保存到相册 Cchong 文件夹。\n\n" + detail);
    }
    @Override protected void onResume() {
        super.onResume(); jobHandler.removeCallbacks(jobPoll); jobHandler.post(jobPoll);
    }
    @Override protected void onPause() { jobHandler.removeCallbacks(jobPoll); stopMotionPreview(); super.onPause(); }
    private void showStatus(String message) { status.setText(message); statusCard.setVisibility(View.VISIBLE); }
    private String extension(String name) {
        int dot = name.lastIndexOf('.'); String ext = dot < 0 ? "照片" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
        return ext.length() > 5 ? "照片" : ext;
    }
    private TextView text(String value, int sp, int color, Typeface font) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); view.setTypeface(font); return view;
    }
    private TextView sectionTitle(String title) { return text(title, 16, INK, sans); }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private View space(int height) { View view = new View(this); view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return view; }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke); return drawable;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() {
        ++previewGeneration; if (pendingPreview != null) previewHandler.removeCallbacks(pendingPreview);
        jobHandler.removeCallbacks(jobPoll); previewWorker.shutdownNow();
        stopMotionPreview();
        clearPreviewImage(); super.onDestroy();
    }
}
