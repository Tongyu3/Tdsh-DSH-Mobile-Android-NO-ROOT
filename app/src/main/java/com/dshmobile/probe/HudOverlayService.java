package com.dshmobile.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * DSH 实时进度悬浮条（"灵动岛"）：在屏幕顶部画一条只读的胶囊，
 * 每 1 秒读一次容器写出来的 hud.json，把 agent 的状态显示出来。
 *
 * 与 OverlayService（DSH 自己的交互式浮窗）的区别：
 *   · 它**完全只读、不抢焦点、不拦触摸**（FLAG_NOT_TOUCHABLE / NOT_FOCUSABLE），
 *     所以它浮在任何 App 上方都不会影响下面那层应用的交互，
 *     也不会让学习通被判成"页面最小化"（那是按前台应用判的，跟浮层无关）。
 *   · 数据来源是文件：容器里的 watchdog / phone_hud 写 /sdcard/.dsh-phone/hud.json，
 *     本服务只读不写，天然单向、无权限边界问题。
 *
 * 开关走 PhoneBridge 的 /hud 端点（?on=1|0），也可以后续在设置里加按钮。
 * 需要的权限 SYSTEM_ALERT_WINDOW 已经在清单里声明（和 OverlayService 共用），
 * 用户只需在系统设置里开一次"显示在其他应用上层"。
 */
public class HudOverlayService extends Service {

    private static final String TAG = "DSH_HUD";
    private static final String CH_ID = "dsh_hud";
    private static final int NOTIF_ID = 0x4855;   // "HU"
    private static final String HUD_FILE = "/sdcard/.dsh-phone/hud.json";

    private static volatile boolean sRunning = false;
    private WindowManager wm;
    private TextView pill;
    private HandlerThread thread;
    private Handler handler;

    public static boolean isRunning() { return sRunning; }

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences("dsh_hud", MODE_PRIVATE).getBoolean("enabled", true);
    }

    /** DSH 启动时恢复悬浮条；记住用户的关闭选择。 */
    public static void restore(Context c) {
        if (isEnabled(c) && Settings.canDrawOverlays(c)) {
            String error = start(c);
            if (error != null) Log.w(TAG, error);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) stopSelf();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        sRunning = true;
        createChannel();
        startForeground(NOTIF_ID, buildNotification("实时进度条运行中"));
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addPill();
        if (pill == null) {
            sRunning = false;
            stopSelf();
            return;
        }
        thread = new HandlerThread("hud-poll");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::pollTick);
        Log.i(TAG, "悬浮进度条已启动");
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        try { if (pill != null && wm != null) wm.removeView(pill); } catch (Throwable ignore) { }
        pill = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) { try { thread.quitSafely(); } catch (Throwable ignore) { } }
        super.onDestroy();
        Log.i(TAG, "悬浮进度条已停止");
    }

    /* ---------- 外部开关 ---------- */

    /** 打开悬浮条。返回错误信息；成功返回 null。 */
    public static String start(Context c) {
        if (sRunning) return null;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) {
            return "没有「显示在其他应用上层」权限：设置 → 应用 → Tdsh → 悬浮窗";
        }
        Intent i = new Intent(c, HudOverlayService.class);
        boolean previous = isEnabled(c);
        c.getSharedPreferences("dsh_hud", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            c.getSharedPreferences("dsh_hud", MODE_PRIVATE).edit().putBoolean("enabled", previous).apply();
            Log.e(TAG, "启动悬浮条失败", e);
            return "无法启动悬浮条，请打开 DSH 后重试";
        }
        return null;
    }

    public static void stop(Context c) {
        c.getSharedPreferences("dsh_hud", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        suspend(c);
    }

    /** 伴随 DSH 停止，保留开关，下次启动自动恢复。 */
    public static void suspend(Context c) {
        try { c.stopService(new Intent(c, HudOverlayService.class)); } catch (Throwable ignore) { }
    }

    /* ---------- 悬浮胶囊 ---------- */

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignore) { }
        return dp(24);
    }

    private void addPill() {
        try {
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(13);
            t.setPadding(dp(14), dp(5), dp(14), dp(6));
            t.setMaxLines(1);
            t.setSingleLine(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(999));
            bg.setColor(0xCC15151C);   // 半透明深色胶囊
            t.setBackground(bg);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = statusBarHeight() + dp(8);
            lp.alpha = 0.8f;

            wm.addView(t, lp);
            pill = t;
        } catch (Throwable t) {
            Log.e(TAG, "addPill failed", t);
            pill = null;
        }
    }

    /* ---------- 轮询与渲染 ---------- */

    private void pollTick() {
        if (!sRunning) return;
        String[] s = readHud();
        render(s);
        handler.postDelayed(this::pollTick, 1000);
    }

    /** @return {state, text}，文件不存在或解析失败返回 null。 */
    private String[] readHud() {
        try {
            File f = new File(HUD_FILE);
            if (!f.exists() || f.length() == 0 || f.length() > 4096) return null;
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int offset = 0;
                while (offset < b.length) {
                    int n = in.read(b, offset, b.length - offset);
                    if (n < 0) return null;
                    offset += n;
                }
            }
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            return new String[]{ o.optString("state", "?"), o.optString("text", "") };
        } catch (Throwable t) {
            return null;
        }
    }

    private void render(final String[] s) {
        if (pill == null) return;
        // 必须回主线程改 View（handler 跑在 handlerThread 上）
        runOnUi(() -> {
            if (pill == null || !sRunning) return;
            /*
             * 没有数据（没有任务在跑）→ **把胶囊整个藏起来**，而不是挂着"等待 HUD 数据…"。
             *
             * 为什么必须这样（2026-10-15 真机实测的缺陷）：
             * 原生胶囊的 y 是「状态栏高度 + 8dp」，正好压在 DSH 自己的标题栏上。
             * 空闲时它一直显示一句"等待 HUD 数据…"，把会话标题糊住 —— 用户什么都不干
             * 也被挡着。按"灵动岛"的本意，它应该**只在有进度时出现**。
             */
            if (s == null) {
                if (pill.getVisibility() != android.view.View.GONE) {
                    pill.setVisibility(android.view.View.GONE);
                }
                return;
            }
            if (pill.getVisibility() != android.view.View.VISIBLE) {
                pill.setVisibility(android.view.View.VISIBLE);
            }
            String state = s[0];
            String text = s[1];
            int color;
            String icon;
            switch (state) {
                case "watching":    color = 0xFF7CF0A0; icon = "▶ "; break;
                case "recovering":  color = 0xFFFF8A65; icon = "⚠ "; break;
                case "quiz":        color = 0xFFFFD166; icon = "❓ "; break;
                case "ended":       color = 0xFF7FB7FF; icon = "⏹ "; break;
                case "blocked":     color = 0xFFFF6B6B; icon = "✋ "; break;
                default:            color = 0xFF9AA4B2; icon = "· "; break;
            }
            String line = icon + (text.isEmpty() ? state : text);
            pill.setTextColor(color);
            pill.setText(line.length() > 24 ? line.substring(0, 24) + "…" : line);
        });
    }

    private void runOnUi(Runnable r) {
        try {
            android.os.Handler main = new android.os.Handler(getMainLooper());
            main.post(r);
        } catch (Throwable t) { r.run(); }
    }

    /* ---------- 通知（前台服务必须有） ---------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "DSH 实时进度", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Tdsh 实时进度")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
