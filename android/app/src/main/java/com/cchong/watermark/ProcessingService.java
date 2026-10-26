package com.cchong.watermark;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProcessingService extends Service {
    static final String EXTRA_JOB = "job";
    private static final String PREFS = "processing_state";
    private static final String CHANNEL = "watermark_processing";
    private static final int NOTIFICATION_ID = 107;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean active;
    private PowerManager.WakeLock wakeLock;

    static JSONObject read(Context context) {
        try {
            String raw = context.getSharedPreferences(PREFS, MODE_PRIVATE).getString("job", "");
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception error) { return null; }
    }

    static void clear(Context context) { context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply(); }

    private void write(JSONObject state) {
        putSafely(state, "revision", state.optInt("revision") + 1);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("job", state.toString()).commit();
    }
    private static void putSafely(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "照片处理", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_JOB)) { stopSelf(startId); return START_NOT_STICKY; }
        if (active) return START_REDELIVER_INTENT;
        JSONObject incoming;
        try { incoming = new JSONObject(intent.getStringExtra(EXTRA_JOB)); }
        catch (Exception error) { stopSelf(startId); return START_NOT_STICKY; }
        JSONObject previous = read(this);
        JSONObject state = previous != null && incoming.optString("id").equals(previous.optString("id")) ? previous : incoming;
        JSONArray incomingItems = state.optJSONArray("items");
        if (incomingItems == null || incomingItems.length() == 0) { stopSelf(startId); return START_NOT_STICKY; }
        try {
            putSafely(state, "running", true);
            write(state);
            startForeground(NOTIFICATION_ID, notification("正在准备照片", 0, incomingItems.length(), true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception error) {
            for (int i = 0; i < incomingItems.length(); i++) {
                JSONObject item = incomingItems.optJSONObject(i);
                if (item != null) { putSafely(item, "state", "failed"); putSafely(item, "error", "无法启动后台处理"); }
            }
            putSafely(state, "running", false); write(state);
            stopSelf(startId); return START_NOT_STICKY;
        }
        active = true;
        try {
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CchongWatermark:processing");
            wakeLock.acquire(5L * 60 * 60 * 1000);
        } catch (Exception ignored) { wakeLock = null; }
        worker.execute(() -> process(state, startId));
        return START_REDELIVER_INTENT;
    }

    private void process(JSONObject state, int startId) {
        JSONArray items = state.optJSONArray("items");
        if (items == null) { releaseWakeLock(); active = false; stopSelf(startId); return; }
        PhotoProcessor processor = new PhotoProcessor(getApplicationContext());
        for (int i = 0; i < items.length(); i++) {
            if (Thread.currentThread().isInterrupted()) break;
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"pending".equals(item.optString("state"))) continue;
            String name = item.optString("name", "照片");
            int done = completed(items);
            notifyProgress("正在处理 " + (i + 1) + "/" + items.length() + " · " + name, done, items.length(), true);
            try {
                JSONArray dimensions = item.getJSONArray("settings");
                WatermarkSettings settings = new WatermarkSettings(
                    (float) dimensions.getDouble(0), (float) dimensions.getDouble(1), (float) dimensions.getDouble(2),
                    (float) dimensions.getDouble(3), (float) dimensions.getDouble(4),
                    (float) dimensions.getDouble(5), (float) dimensions.getDouble(6));
                PhotoProcessor.Saved saved = processor.process(Uri.parse(item.getString("uri")), name,
                    item.optString("color", "auto"), settings);
                item.put("state", "success"); item.put("output", saved.name);
            } catch (Exception error) {
                putSafely(item, "state", "failed");
                putSafely(item, "error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            write(state);
        }
        if (!Thread.currentThread().isInterrupted()) {
            putSafely(state, "running", false); write(state);
            int success = 0, failed = 0;
            for (int i = 0; i < items.length(); i++) {
                String itemState = items.optJSONObject(i).optString("state");
                if ("success".equals(itemState)) success++;
                if ("failed".equals(itemState)) failed++;
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            notifyProgress("处理完成：" + success + " 张成功，" + failed + " 张失败", items.length(), items.length(), false);
        }
        releaseWakeLock(); active = false; stopSelf(startId);
    }

    private static int completed(JSONArray items) {
        int count = 0;
        for (int i = 0; i < items.length(); i++)
            if (!"pending".equals(items.optJSONObject(i).optString("state"))) count++;
        return count;
    }

    private Notification notification(String message, int completed, int total, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Cchong 水印")
            .setContentText(message)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(Math.max(1, total), completed, ongoing && total == 1)
            .build();
    }

    private void notifyProgress(String message, int completed, int total, boolean ongoing) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message, completed, total, ongoing));
    }

    @Override public void onTimeout(int startId, int fgsType) {
        JSONObject state = read(this);
        if (state != null) {
            JSONArray items = state.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && "pending".equals(item.optString("state"))) {
                    putSafely(item, "state", "failed"); putSafely(item, "error", "后台处理超时，请重试");
                }
            }
            putSafely(state, "running", false); write(state);
        }
        worker.shutdownNow(); releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
    @Override public void onDestroy() { worker.shutdownNow(); releaseWakeLock(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
