package com.cchong.watermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.provider.DocumentsContract;
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
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Switch;
import android.text.TextWatcher;
import android.text.Editable;
import android.text.InputFilter;
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
    private static final int PICK_PHOTOS = 1001, PICK_FILES = 1002, PICK_FOLDER = 1003;
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
    private final java.util.Map<Uri,Integer> rotations = new java.util.HashMap<>();
    private boolean recognizing, retryWithoutWatermark;
    private TextView batchUpright, exportUpright;
    private TextView rotateLeft, rotateRight, autoUpright, resetRotation;
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
    private TextView filePickerButton, folderPickerButton;
    private final ExecutorService importWorker=Executors.newSingleThreadExecutor();
    private boolean importing;
    private int importGeneration;
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
    private boolean filmMode, globalFilmMode, syncingFilm;
    private String filmStock = "", filmIso = "", filmCamera = "Pentax Espio 125M";
    private String globalFilmStock = "", globalFilmIso = "", globalFilmCamera = "Pentax Espio 125M";
    private Switch filmSwitch;
    private LinearLayout filmFields;
    private AutoCompleteTextView stockInput, brandInput;
    private final List<JSONObject> filmCatalog = new ArrayList<>();
    private final Map<String, String> filmNames = new HashMap<>();
    private final Map<String, String> filmSearchTerms = new HashMap<>();
    private final Map<String, String> filmBrandLabels = new java.util.LinkedHashMap<>();
    private TextView brandNote, previewPrev, previewNext, previewCounter, inheritButton;
    private LinearLayout recentFilmsRow;
    private final List<String> recentFilms = new ArrayList<>();
    private EditText isoInput, cameraInput;
    private final Map<String, String> filmSpeeds = new HashMap<>();
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
        LinearLayout pick = column(); pick.setGravity(Gravity.CENTER); pick.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable pickBorder = shape(Color.WHITE, 18, LINE); pickBorder.setStroke(dp(1), LINE, dp(6), dp(5)); pick.setBackground(pickBorder);
        TextView plus = text("+", 32, INK, sans); plus.setGravity(Gravity.CENTER); plus.setBackground(shape(SOFT, 50, 0));
        pick.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36))); pick.addView(space(8));
        pick.addView(text("从相册选择", 18, INK, sans)); pick.addView(space(3)); pick.addView(text("可多选 · 最多 100 张", 12, MUTED, sans));
        pick.setOnClickListener(v -> choosePhotos()); pickerCard = pick; content.addView(pick, new LinearLayout.LayoutParams(-1, dp(130)));
        content.addView(space(8));
        LinearLayout fileChoices=row();
        filePickerButton=text("从文件选照片",13,INK,sans);folderPickerButton=text("选择整个文件夹",13,INK,sans);
        for(TextView button:new TextView[]{filePickerButton,folderPickerButton}){button.setGravity(Gravity.CENTER);button.setBackground(shape(SOFT,10,LINE));}
        filePickerButton.setOnClickListener(v->chooseFiles());folderPickerButton.setOnClickListener(v->chooseFolder());
        LinearLayout.LayoutParams filesParams=new LinearLayout.LayoutParams(0,dp(48),1);filesParams.rightMargin=dp(8);
        fileChoices.addView(filePickerButton,filesParams);fileChoices.addView(folderPickerButton,new LinearLayout.LayoutParams(0,dp(48),1));content.addView(fileChoices);
        TextView folderHint=text("整卷扫描件可选文件夹 · 超过 50 张会先确认",11,MUTED,sans);folderHint.setPadding(0,dp(8),0,dp(18));content.addView(folderHint);

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
        content.addView(previewCard);
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
        LinearLayout rotationActions = row();
        rotateLeft = text("↶ 左转",12,INK,sans); rotateRight = text("↷ 右转",12,INK,sans);
        autoUpright = text("自动转正",12,INK,sans); resetRotation = text("还原方向",12,INK,sans);
        for (TextView button : new TextView[]{rotateLeft,rotateRight,autoUpright,resetRotation}) {
            button.setGravity(Gravity.CENTER); button.setBackground(shape(SOFT,8,LINE));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(44),1); params.setMargins(dp(2),dp(8),dp(2),0);
            rotationActions.addView(button,params);
        }
        rotateLeft.setOnClickListener(v->turnCurrent(-90)); rotateRight.setOnClickListener(v->turnCurrent(90));
        resetRotation.setOnClickListener(v->{if(!busy&&!recognizing&&previewUri!=null){rotations.remove(previewUri);schedulePreview();}});
        autoUpright.setOnClickListener(v->autoOrient()); content.addView(rotationActions);
        LinearLayout batchActions = row();
        batchUpright = text("整批自动转正", 13, INK, sans);
        exportUpright = text("仅导出转正照片", 13, INK, sans);
        for (TextView button : new TextView[]{batchUpright, exportUpright}) {
            button.setGravity(Gravity.CENTER);
            button.setBackground(shape(SOFT, 8, LINE));
            batchActions.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        batchUpright.setOnClickListener(v -> batchOrient());
        exportUpright.setOnClickListener(v -> dispatch(new ArrayList<>(selected), true));
        content.addView(batchActions);
        LinearLayout navigation=row(); navigation.setGravity(Gravity.CENTER); navigation.setPadding(0,dp(8),0,0);
        previewPrev=text("←",18,INK,sans); previewNext=text("→",18,INK,sans); previewCounter=text("尚未选择照片",12,MUTED,sans); previewCounter.setGravity(Gravity.CENTER);
        for(TextView button:new TextView[]{previewPrev,previewNext}){button.setGravity(Gravity.CENTER);button.setBackground(shape(SOFT,8,LINE));}
        previewPrev.setOnClickListener(v->adjacentPreview(-1));previewNext.setOnClickListener(v->adjacentPreview(1));
        navigation.addView(previewPrev,new LinearLayout.LayoutParams(dp(48),dp(42)));navigation.addView(previewCounter,new LinearLayout.LayoutParams(dp(130),dp(42)));navigation.addView(previewNext,new LinearLayout.LayoutParams(dp(48),dp(42)));content.addView(navigation);
        content.addView(space(26));

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

        inheritButton=text("取消单独设置，使用整批设置",12,INK,sans);inheritButton.setPadding(0,0,0,dp(12));inheritButton.setVisibility(View.GONE);
        inheritButton.setOnClickListener(v->{if(!busy&&previewUri!=null){overrides.remove(previewUri);syncEditor();renderQueue();schedulePreview();}});content.addView(inheritButton);
        addFilmControls(content);
        content.addView(sectionTitle("水印颜色")); content.addView(space(10));
        LinearLayout selector = row(); selector.setPadding(dp(4), dp(4), dp(4), dp(4)); selector.setBackground(shape(SOFT, 13, 0));
        String[] colorLabels = {"自动", "白色", "黑色"};
        for (int i = 0; i < 3; i++) { final int index = i; TextView segment = text(colorLabels[i], 14, INK, sans);
            segment.setGravity(Gravity.CENTER); segment.setOnClickListener(v -> selectColor(index)); colorButtons[i] = segment;
            selector.addView(segment, new LinearLayout.LayoutParams(0, dp(44), 1)); }
        content.addView(selector); selectColor("white".equals(colorMode) ? 1 : "black".equals(colorMode) ? 2 : 0); content.addView(space(23));



        LinearLayout advancedHolder=column();advancedHolder.setVisibility(View.GONE);
        LinearLayout settingsHeader = row(); settingsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView advancedTitle=sectionTitle("大小与位置  ▾");settingsHeader.addView(advancedTitle);
        settingsHeader.setOnClickListener(v->{boolean open=advancedHolder.getVisibility()!=View.VISIBLE;advancedHolder.setVisibility(open?View.VISIBLE:View.GONE);advancedTitle.setText(open?"大小与位置  ▴":"大小与位置  ▾");});
        TextView reset = text("恢复默认", 13, INK, sans); reset.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        reset.setOnClickListener(v -> resetSettings()); settingsHeader.addView(reset, new LinearLayout.LayoutParams(0, dp(38), 1));
        content.addView(settingsHeader);
        LinearLayout controls = column(); controls.setPadding(dp(14), dp(10), dp(14), dp(8)); controls.setBackground(shape(Color.WHITE, 16, LINE));
        for (int i = 0; i < values.length; i++) addSlider(controls, i);
        advancedHolder.addView(controls);content.addView(advancedHolder); content.addView(space(26));

        LinearLayout queueHeader = row(); queueHeader.setGravity(Gravity.CENTER_VERTICAL); queueHeader.addView(sectionTitle("待处理"));
        queueCount = text("0", 12, MUTED, sans); queueCount.setGravity(Gravity.CENTER); queueCount.setBackground(shape(SOFT, 25, 0));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(28), dp(25)); cp.leftMargin = dp(9); queueHeader.addView(queueCount, cp);
        clearButton = text("清空", 13, MUTED, sans); clearButton.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        clearButton.setOnClickListener(v -> { if (!busy && !importing) {
            selected.clear(); names.clear(); overrides.clear(); rotations.clear(); failed.clear();
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
        processButton = text("生成并保存到相册  →", 16, Color.WHITE, sans); processButton.setGravity(Gravity.CENTER);
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
    private WatermarkSettings snapshot() { return new WatermarkSettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6]).withFilm(filmMode, filmStock, filmIso, filmCamera); }
    private WatermarkSettings globalSnapshot() {
        return new WatermarkSettings(globalValues[0], globalValues[1], globalValues[2], globalValues[3],
            globalValues[4], globalValues[5], globalValues[6]).withFilm(globalFilmMode, globalFilmStock, globalFilmIso, globalFilmCamera);
    }
    private Choice effective(Uri uri) {
        Choice choice = overrides.get(uri);
        if (choice == null) choice = new Choice(globalSnapshot(), globalColor);
        return new Choice(choice.settings.rotated(rotations.getOrDefault(uri,0)), choice.color);
    }
    private void commitEditor() {
        if (scopeOne && previewUri != null) overrides.put(previewUri, new Choice(snapshot(), colorMode));
        else { System.arraycopy(values, 0, globalValues, 0, values.length); globalColor = colorMode; globalFilmMode = filmMode; globalFilmStock = filmStock; globalFilmIso = filmIso; globalFilmCamera = filmCamera; saveSettings(); }
        if (queue != null) renderQueue();
    }
    private void syncEditor() {
        Choice choice = scopeOne && previewUri != null ? effective(previewUri) : new Choice(globalSnapshot(), globalColor);
        System.arraycopy(choice.settings.values(), 0, values, 0, values.length);
        colorMode = choice.color;
        filmMode = choice.settings.filmMode; filmStock = choice.settings.filmStock;
        filmIso = choice.settings.filmIso; filmCamera = choice.settings.filmCamera;
        syncFilmControls();
        for (int i = 0; i < values.length; i++) if (sliders[i] != null) { sliders[i].setProgress(progressFor(i)); updateValue(i); }
        updateColorButtons();
        if(inheritButton!=null)inheritButton.setVisibility(scopeOne&&overrides.containsKey(previewUri)?View.VISIBLE:View.GONE);
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
        filmMode = globalFilmMode = prefs.getBoolean("film_mode", false);
        filmStock = globalFilmStock = prefs.getString("film_stock", "");
        filmIso = globalFilmIso = prefs.getString("film_iso", "");
        filmCamera = globalFilmCamera = prefs.getString("film_camera", "Pentax Espio 125M");
        String saved = prefs.getString("color", "auto");
        if ("white".equals(saved) || "black".equals(saved)) colorMode = globalColor = saved;
    }
    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit();
        for (int i = 0; i < values.length; i++) editor.putFloat("value_" + i, globalValues[i]);
        editor.putBoolean("film_mode",globalFilmMode).putString("film_stock",globalFilmStock)
            .putString("film_iso",globalFilmIso).putString("film_camera",globalFilmCamera);
        editor.putString("color", globalColor).apply();
    }
    private void resetSettings() {
        if (busy) return;
        float[] initial = {1, 1, 1, 1, 1, .5f, .83f};
        System.arraycopy(initial, 0, values, 0, values.length);
        filmMode = false; filmStock = ""; filmIso = ""; filmCamera = "Pentax Espio 125M";
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

    private void addFilmControls(LinearLayout content) {
        filmSwitch = new Switch(this); filmSwitch.setText("胶片扫描"); filmSwitch.setTextColor(INK);
        content.addView(filmSwitch); content.addView(space(8));
        filmFields = column(); filmFields.setPadding(dp(14),dp(12),dp(14),dp(14));
        filmFields.setBackground(shape(Color.rgb(247,244,237),12,LINE));
        try{JSONArray saved=new JSONArray(getSharedPreferences(PREFERENCES,MODE_PRIVATE).getString("recent_films","[]"));for(int i=0;i<saved.length()&&i<6;i++){String name=saved.optString(i);if(!name.isEmpty())recentFilms.add(name);}}catch(Exception ignored){}
        android.widget.HorizontalScrollView recents=new android.widget.HorizontalScrollView(this);recents.setHorizontalScrollBarEnabled(false);recentFilmsRow=row();recents.addView(recentFilmsRow);filmFields.addView(recents);renderRecentFilms();
        filmFields.addView(text("胶卷品牌",12,MUTED,sans));
        brandInput=new AutoCompleteTextView(this); brandInput.setThreshold(0); brandInput.setHint("选择品牌，或填写英文品牌"); filmFields.addView(brandInput);

        brandNote=text("",11,MUTED,sans); filmFields.addView(brandNote);
        filmFields.addView(text("胶卷型号",12,MUTED,sans));
        stockInput = new AutoCompleteTextView(this); stockInput.setThreshold(0);
        stockInput.setHint("搜索型号 / 中文名称，或填写英文型号"); filmFields.addView(stockInput);
        isoInput = new EditText(this); isoInput.setVisibility(View.GONE);
        filmFields.addView(text("相机",12,MUTED,sans)); cameraInput = new EditText(this); filmFields.addView(cameraInput);
        for (EditText field : new EditText[]{brandInput,stockInput,isoInput,cameraInput}) {
            field.setSingleLine(true); field.setTextSize(14); field.setTextColor(INK);
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        }
        try (InputStream stream = getAssets().open("films.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int count;
            while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);
            JSONArray catalog=new JSONArray(bytes.toString("UTF-8"));
            for(int i=0;i<catalog.length();i++)filmCatalog.add(catalog.getJSONObject(i));
        } catch(Exception ignored){}
        List<String> brandRows=new ArrayList<>();
        for(JSONObject f:filmCatalog)filmBrandLabels.put(f.optString("brand"),f.optString("brand_zh"));
        for(Map.Entry<String,String> entry:filmBrandLabels.entrySet())brandRows.add(entry.getKey()+" · "+entry.getValue());
        brandInput.setAdapter(new FilmSearchAdapter(this,brandRows,null));
        brandInput.setOnFocusChangeListener((v,focus)->{if(focus)brandInput.showDropDown();});
        brandInput.setOnItemClickListener((parent,v,position,id)->{
            String value=((String)parent.getItemAtPosition(position)).split(" · ")[0];
            syncingFilm=true; brandInput.setText(value,false); stockInput.setText("",false); syncingFilm=false;
            updateFilmModels(); filmChanged(); stockInput.requestFocus(); stockInput.showDropDown();
        });
        stockInput.setOnFocusChangeListener((v,focus)->{if(focus){updateFilmModels();stockInput.showDropDown();}});
        stockInput.setOnItemClickListener((parent,v,position,id)->{
            String row=(String)parent.getItemAtPosition(position), full=filmNames.get(row);
            if(full==null)return;
            syncingFilm=true; brandInput.setText(full.split(" ",2)[0],false); stockInput.setText(full.split(" ",2)[1],false);
            isoInput.setText(filmSpeeds.get(row)); syncingFilm=false; filmChanged();rememberFilm(full);
        });
        TextWatcher watcher=new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(!syncingFilm)filmChanged();}
            public void afterTextChanged(Editable e){}
        };
        brandInput.addTextChangedListener(watcher); stockInput.addTextChangedListener(watcher); isoInput.addTextChangedListener(watcher); cameraInput.addTextChangedListener(watcher);
        filmSwitch.setOnCheckedChangeListener((button,checked)->{if(!syncingFilm){filmMode=checked;filmFields.setVisibility(checked?View.VISIBLE:View.GONE);commitEditor();schedulePreview();}});
        content.addView(filmFields); content.addView(space(20)); syncFilmControls();
    }
    private void rememberFilm(String name){
        if(name.trim().isEmpty())return;recentFilms.remove(name);recentFilms.add(0,name);while(recentFilms.size()>6)recentFilms.remove(recentFilms.size()-1);
        getSharedPreferences(PREFERENCES,MODE_PRIVATE).edit().putString("recent_films",new JSONArray(recentFilms).toString()).apply();renderRecentFilms();
    }
    private void renderRecentFilms(){
        if(recentFilmsRow==null)return;recentFilmsRow.removeAllViews();
        for(String name:recentFilms){TextView chip=text(name,11,INK,sans);chip.setPadding(dp(12),dp(10),dp(12),dp(10));chip.setBackground(shape(SOFT,20,LINE));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(40));params.rightMargin=dp(6);recentFilmsRow.addView(chip,params);
            chip.setOnClickListener(v->{if(busy)return;String[] parts=name.split(" ",2);syncingFilm=true;brandInput.setText(parts[0],false);stockInput.setText(parts.length>1?parts[1]:"",false);syncingFilm=false;updateFilmModels();filmChanged();rememberFilm(name);});
        }
    }
    private void adjacentPreview(int delta){
        if(busy)return;int next=selected.indexOf(previewUri)+delta;
        if(next>=0&&next<selected.size()){previewUri=selected.get(next);renderQueue();syncEditor();schedulePreview();}
    }
    private void updateFilmModels() {
        List<String> rows=new ArrayList<>(); filmNames.clear(); filmSpeeds.clear(); filmSearchTerms.clear();
        String brand=brandInput.getText().toString().trim();
        brandNote.setText(filmBrandLabels.containsKey(brand)?filmBrandLabels.get(brand):"");
        for(JSONObject f:filmCatalog) {
            if(!brand.isEmpty()&&!brand.equalsIgnoreCase(f.optString("brand")))continue;
            String row=(brand.isEmpty()?f.optString("brand")+" ":"")+f.optString("model")+" · "+f.optString("model_zh")+(f.optString("note").contains("历史")?" · 历史型号":"");
            filmSearchTerms.put(row,row+" "+f.optString("aliases")+" "+f.optString("iso")+" "+f.optString("note"));
            rows.add(row);filmNames.put(row,f.optString("name"));filmSpeeds.put(row,f.optString("iso"));
        }
        stockInput.setAdapter(new FilmSearchAdapter(this,rows,new HashMap<>(filmSearchTerms)));
    }
    private void filmChanged() {
        filmStock=(brandInput.getText().toString().trim()+" "+stockInput.getText().toString()).trim(); filmIso=isoInput.getText().toString(); filmCamera=cameraInput.getText().toString();
        commitEditor(); schedulePreview();
    }
    private void syncFilmControls() {
        if(filmSwitch==null)return;
        syncingFilm=true; filmSwitch.setChecked(filmMode); filmFields.setVisibility(filmMode?View.VISIBLE:View.GONE);
        String[] parts=filmStock.split(" ",2);
        brandInput.setText(parts.length>0?parts[0]:"",false); stockInput.setText(parts.length>1?parts[1]:"",false);
        isoInput.setText(filmIso); cameraInput.setText(filmCamera); updateFilmModels(); syncingFilm=false;
    }
    private void choosePhotos() {
        if (busy || importing || selected.size() >= 100) return;
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
    private void chooseFiles(){
        if(busy||importing||selected.size()>=100)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.putExtra(Intent.EXTRA_LOCAL_ONLY,true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(intent,PICK_FILES);}catch(ActivityNotFoundException error){showStatus("没有可用的文件选择器，请从相册选择。");}
    }
    private void chooseFolder(){
        if(busy||importing||selected.size()>=100)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try{startActivityForResult(intent,PICK_FOLDER);}catch(ActivityNotFoundException error){showStatus("系统不支持选择文件夹，请使用从文件选照片。");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null)return;
        if(requestCode==PICK_FOLDER){if(data.getData()!=null)scanFolder(data.getData());return;}
        if(requestCode!=PICK_PHOTOS&&requestCode!=PICK_FILES)return;
        List<Uri> incoming=new ArrayList<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)incoming.add(data.getClipData().getItemAt(i).getUri());
        else if(data.getData()!=null)incoming.add(data.getData());
        int before=selected.size();for(Uri uri:incoming)add(uri);
        renderQueue();syncEditor();schedulePreview();
        if(selected.size()==100&&incoming.size()>selected.size()-before)showStatus("本批最多 100 张，已加入 "+(selected.size()-before)+" 张；重复或超过上限的照片未加入。");
    }
    private void scanFolder(Uri tree){
        if(busy||importing)return;
        try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        importing=true;int generation=++importGeneration;renderQueue();showStatus("正在读取文件夹中的照片…");
        importWorker.execute(()->{
            try{
                String folderId=DocumentsContract.getTreeDocumentId(tree),folderName="所选文件夹";
                Uri folder=DocumentsContract.buildDocumentUriUsingTree(tree,folderId);
                try(Cursor info=getContentResolver().query(folder,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                    if(info!=null&&info.moveToFirst())folderName=info.getString(0);
                }catch(Exception ignored){}
                List<FolderImportPlan.Photo> entries=new ArrayList<>();
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folderId);
                try(Cursor cursor=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
                    if(cursor==null)throw new Exception("无法读取文件夹，请重新选择。");
                    while(cursor.moveToNext()){
                        if(Thread.currentThread().isInterrupted())return;
                        String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
                        if(id==null||!FolderImportPlan.supported(name,mime))continue;
                        entries.add(new FolderImportPlan.Photo(DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),name==null?"photo.jpg":name,mime));
                    }
                }
                String label=folderName==null?"所选文件夹":folderName;
                runOnUiThread(()->{if(generation==importGeneration&&!isFinishing()&&!isDestroyed())confirmFolder(label,entries);});
            }catch(Exception error){runOnUiThread(()->{if(generation==importGeneration&&!isFinishing()&&!isDestroyed()){finishFolderImport();showStatus("读取文件夹失败："+(error.getMessage()==null?"无法访问，请重新选择":error.getMessage()));}});}
        });
    }
    private void confirmFolder(String label,List<FolderImportPlan.Photo> entries){
        Set<String> existing=new HashSet<>();for(Uri uri:selected)existing.add(uri.toString());
        FolderImportPlan plan=FolderImportPlan.create(entries,existing,100-selected.size());
        if(plan.totalPhotos==0){finishFolderImport();showStatus("“"+label+"”中没有支持的照片。只读取当前文件夹，不包含子文件夹。");return;}
        if(plan.newPhotos==0){finishFolderImport();showStatus("此文件夹的照片已在待处理列表中，无需重复加入。");return;}
        if(!plan.needsConfirmation()){acceptFolder(label,plan);return;}
        String message="“"+label+"”包含 "+plan.totalPhotos+" 张照片。";
        if(plan.totalPhotos>50)message+="\n一卷扫描通常少于 50 张，请确认是否选错文件夹。";
        if(plan.duplicatePhotos>0)message+="\n其中 "+plan.duplicatePhotos+" 张已在列表中，将跳过。";
        if(plan.exceedsCapacity)message+="\n每批最多 100 张，本次只能按文件名顺序加入前 "+plan.toImport.size()+" 张新照片。";
        message+="\n只读取当前文件夹，不包含子文件夹。";
        new AlertDialog.Builder(this).setTitle(plan.totalPhotos>50?"照片超过 50 张，请确认文件夹":"本批照片数量超过上限")
            .setMessage(message).setNegativeButton("重新选择",(dialog,which)->{finishFolderImport();showStatus("已取消导入，请重新选择文件夹。");chooseFolder();})
            .setPositiveButton("确认导入 "+plan.toImport.size()+" 张",(dialog,which)->acceptFolder(label,plan))
            .setOnCancelListener(dialog->{finishFolderImport();showStatus("已取消文件夹导入。");}).show();
    }
    private void acceptFolder(String label,FolderImportPlan plan){
        int added=0;for(FolderImportPlan.Photo photo:plan.toImport){Uri uri=Uri.parse(photo.uri);if(selected.size()<100&&!selected.contains(uri)){selected.add(uri);names.add(photo.name);added++;}}
        finishFolderImport();syncEditor();schedulePreview();showStatus("已从“"+label+"”加入 "+added+" 张照片"+(plan.duplicatePhotos>0?"，跳过 "+plan.duplicatePhotos+" 张重复照片":"")+"。");
    }
    private void finishFolderImport(){importing=false;renderQueue();}
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
        int requestedRotation = effective(uri).settings.rotation;
        int overlayWidth = previewOverlay.getWidth(), overlayHeight = previewOverlay.getHeight();
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
                int angle = requestedRotation;
                if(angle!=0){
                    File turned = File.createTempFile("cchong-turned-", ".mp4",getCacheDir()); turned.delete();
                    android.graphics.Bitmap blank=android.graphics.Bitmap.createBitmap(overlayWidth,overlayHeight,android.graphics.Bitmap.Config.ARGB_8888);
                    try {new VideoWatermarker(this).render(videoFile,turned,blank,angle);}
                    catch(Exception error){turned.delete();throw error;}
                    finally{blank.recycle();}
                    videoFile.delete();videoFile=turned;
                }
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
    private void renderQueue() {
        if (!selected.contains(previewUri)) previewUri = selected.isEmpty() ? null : selected.get(0);
        queue.removeAllViews(); queueCount.setText(String.valueOf(selected.size()));
        clearButton.setVisibility(selected.isEmpty() ? View.INVISIBLE : View.VISIBLE); clearButton.setEnabled(!busy&&!importing);
        boolean canPick=!busy&&!importing&&selected.size()<100;
        pickerCard.setEnabled(canPick);pickerCard.setAlpha(canPick?1f:.5f);
        filePickerButton.setEnabled(canPick);folderPickerButton.setEnabled(canPick);filePickerButton.setAlpha(canPick?1f:.5f);folderPickerButton.setAlpha(canPick?1f:.5f);
        processButton.setEnabled(!busy&&!importing&&!recognizing&&!selected.isEmpty());
        if(rotateLeft!=null)for(TextView button:new TextView[]{rotateLeft,rotateRight,autoUpright,resetRotation})button.setEnabled(!busy&&!importing&&!recognizing&&previewUri!=null);
        if (batchUpright != null) {
            batchUpright.setEnabled(processButton.isEnabled());
            exportUpright.setEnabled(processButton.isEnabled());
        }
        int previewIndex=selected.indexOf(previewUri);
        if(previewCounter!=null){previewCounter.setText(previewIndex<0?"尚未选择照片":(previewIndex+1)+" / "+selected.size());previewPrev.setEnabled(!busy&&previewIndex>0);previewNext.setEnabled(!busy&&previewIndex>=0&&previewIndex<selected.size()-1);previewPrev.setAlpha(previewPrev.isEnabled()?1f:.35f);previewNext.setAlpha(previewNext.isEnabled()?1f:.35f);}
        processButton.setText(importing?"正在读取文件夹…":busy?"正在处理…":"生成并保存到相册  →");
        processButton.setBackground(shape(processButton.isEnabled() ? INK : Color.rgb(179, 188, 179), 13, 0));
        boolean orientingBatch = orientationTotal > 0;
        progress.setIndeterminate(!orientingBatch);
        if (orientingBatch) {
            progress.setMax(orientationTotal);
            progress.setProgress(orientationDone, true);
        }
        progress.setVisibility(busy || importing || orientingBatch ? View.VISIBLE : View.GONE);
        if (orientingBatch) processButton.setText("正在转正 " + orientationDone + "/" + orientationTotal);
        retryButton.setVisibility(!busy&&!importing && !failed.isEmpty() ? View.VISIBLE : View.GONE);
        if (filmSwitch != null) { filmSwitch.setEnabled(!busy); brandInput.setEnabled(!busy); stockInput.setEnabled(!busy); isoInput.setEnabled(!busy); cameraInput.setEnabled(!busy); }
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
            item.setOnClickListener(v -> { if (!busy && !importing) { previewUri = selected.get(index); renderQueue(); syncEditor(); schedulePreview(); } });
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
            remove.setOnClickListener(v -> { if (!busy && !importing) {
                Uri removed = selected.remove(index); names.remove(index); overrides.remove(removed); rotations.remove(removed); failed.remove(removed);
                renderQueue(); syncEditor(); schedulePreview();
            } });
            item.addView(remove, new LinearLayout.LayoutParams(dp(32), dp(40)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(62)); p.bottomMargin = dp(8); queue.addView(item, p);
        }
    }
    private void startProcessing() { dispatch(new ArrayList<>(selected), false); }
    private void retryFailed() {
        List<Uri> retry = new ArrayList<>();
        for (Uri uri : selected) if (failed.contains(uri)) retry.add(uri);
        dispatch(retry, retryWithoutWatermark);
    }
    private void dispatch(List<Uri> input, boolean noWatermark) {
        if (busy || importing || recognizing || input.isEmpty()) return;
        JSONObject existing = ProcessingService.read(this);
        if (existing != null && existing.optBoolean("running", false)) {
            currentJobId = existing.optString("id"); jobRevision = -1; syncJob(); return;
        }
        try {
            JSONArray items = new JSONArray();
            for (Uri uri : input) {
                Choice choice = effective(uri);
                if(choice.settings.filmMode)rememberFilm(choice.settings.filmStock);
                JSONObject item = new JSONObject();
                item.put("uri", uri.toString());
                item.put("name", names.get(selected.indexOf(uri)));
                item.put("color", choice.color);
                item.put("state", "pending");
                JSONArray dimensions = new JSONArray();
                for (float value : choice.settings.values()) dimensions.put(value);
                item.put("settings", dimensions); item.put("rotation",choice.settings.rotation); item.put("no_watermark",noWatermark);
                item.put("film_mode",choice.settings.filmMode).put("film_stock",choice.settings.filmStock)
                    .put("film_iso",choice.settings.filmIso).put("film_camera",choice.settings.filmCamera);
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
                rotations.put(uri,item.optInt("rotation",0));
                selected.add(uri); names.add(item.optString("name", "照片"));
                JSONArray dimensions = item.optJSONArray("settings");
                if (dimensions != null && dimensions.length() == 7) {
                    WatermarkSettings setting = new WatermarkSettings(
                        (float) dimensions.optDouble(0, 1), (float) dimensions.optDouble(1, 1),
                        (float) dimensions.optDouble(2, 1), (float) dimensions.optDouble(3, 1),
                        (float) dimensions.optDouble(4, 1), (float) dimensions.optDouble(5, .5),
                        (float) dimensions.optDouble(6, .83));
                    setting.withFilm(item.optBoolean("film_mode",false),item.optString("film_stock",""),
                        item.optString("film_iso",""),item.optString("film_camera","Pentax Espio 125M"));
                    overrides.put(uri, new Choice(setting, item.optString("color", "auto")));
                }
            }
            previewUri = selected.isEmpty() ? null : selected.get(0);
            syncEditor(); schedulePreview();
        }
        retryWithoutWatermark = items.length() > 0 && items.optJSONObject(0).optBoolean("no_watermark",false);
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
    private void turnCurrent(int angle) {
        if (busy || recognizing || previewUri == null) return;
        rotations.put(previewUri,(rotations.getOrDefault(previewUri,0)+angle+360)%360); schedulePreview();
    }
    private int orientationDone, orientationTotal;

    private void batchOrient() {
        if (busy || importing || recognizing || selected.isEmpty()) return;
        List<Uri> files = new ArrayList<>(selected);
        List<String> labels = new ArrayList<>(names);
        List<WatermarkSettings> settings = new ArrayList<>();
        for (Uri uri : files) settings.add(effective(uri).settings);
        recognizing = true;
        orientationDone = 0;
        orientationTotal = files.size();
        renderQueue();
        showStatus("正在整批识别方向…");
        previewWorker.execute(() -> {
            Map<Uri,Integer> corrections = new HashMap<>();
            List<String> review = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                Uri uri = files.get(i);
                WatermarkSettings choice = settings.get(i);
                try {
                    WatermarkRenderer.Result image = new WatermarkRenderer(this)
                        .preview(getContentResolver(), uri, "auto", choice);
                    try {
                        OrientationDetector.Result result = OrientationDetector.detect(this, image.original);
                        if (result.confident) corrections.put(uri, (choice.rotation + result.rotation) % 360);
                        else review.add(labels.get(i));
                    } finally {
                        image.photo.recycle();
                        image.overlay.recycle();
                        image.original.recycle();
                    }
                } catch (Exception error) {
                    review.add(labels.get(i));
                }
                int completed = i + 1;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    orientationDone = completed;
                    progress.setProgress(completed, true);
                    processButton.setText("正在转正 " + completed + "/" + files.size());
                });
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                recognizing = false;
                orientationTotal = 0;
                corrections.forEach((uri, angle) -> { if (selected.contains(uri)) rotations.put(uri, angle); });
                renderQueue();
                schedulePreview();
                showStatus("方向识别完成，" + corrections.size() + " 张已确认。"
                    + (review.isEmpty() ? "" : "\n请检查方向保持原样的 " + review.size() + " 张：" + String.join("、", review)));
            });
        });
    }
    private void autoOrient() {
        if(busy || importing || recognizing || previewUri==null) return;
        Uri uri=previewUri; int starting=rotations.getOrDefault(uri,0); WatermarkSettings detectionSettings=effective(uri).settings; recognizing=true; renderQueue(); showStatus("正在离线识别方向…");
        previewWorker.execute(()->{
            try {
                WatermarkRenderer.Result image = new WatermarkRenderer(this).preview(getContentResolver(),uri,"auto",detectionSettings);
                OrientationDetector.Result result;
                try { result = OrientationDetector.detect(this,image.original); }
                finally { image.photo.recycle();image.overlay.recycle();image.original.recycle(); }
                runOnUiThread(()->{
                    recognizing=false;
                    if(selected.contains(uri) && rotations.getOrDefault(uri,0)==starting) {
                        if(result.confident){rotations.put(uri,(starting+result.rotation)%360); if(uri.equals(previewUri))schedulePreview();
                            showStatus(result.rotation==0?"识别为正向，请检查预览。":"已自动转正，请检查预览。");}
                        else showStatus("方向不确定，已保留当前方向，请使用左转或右转。");
                    }
                    renderQueue();
                });
            } catch(Exception error){runOnUiThread(()->{recognizing=false;renderQueue();showStatus("方向识别失败："+error.getMessage());});}
        });
    }
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
        ++importGeneration;importWorker.shutdownNow();
        ++previewGeneration; if (pendingPreview != null) previewHandler.removeCallbacks(pendingPreview);
        jobHandler.removeCallbacks(jobPoll); previewWorker.shutdownNow();
        stopMotionPreview();
        clearPreviewImage(); super.onDestroy();
    }
}
