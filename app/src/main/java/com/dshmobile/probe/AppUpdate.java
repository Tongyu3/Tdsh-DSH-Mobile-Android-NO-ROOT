package com.dshmobile.probe;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * App **自身**的版本检查与"新版本推送"（用户要求："热更新，新版本推送"）。
 *
 * <h3>先分清两件事（这是最容易让用户误解的地方）</h3>
 * <ul>
 *   <li><b>内核热更新</b>：容器里那份 {@code @deepseek-ai/dsh} 的升级 ——
 *       已经由 {@link KernelUpgrade} 做了（查 npm dist-tags + 容器里 npm install），
 *       那是真正的"热更新"：不用重装 App，DSH 本体就换了版本。</li>
 *   <li><b>App 更新</b>：这个 APK 自己。安卓**不允许**一个普通应用静默替换自己 ——
 *       必须由系统安装器接管（要用户点"安装"）。所以这里能自动化到的边界是：
 *       **自动发现新版本 + 通知栏推送 + 一键打开下载页**，最后那一下点击必须是人做的。</li>
 * </ul>
 * 把这条边界写在界面上，用户就不会觉得"说好的热更新怎么还要我点"。
 *
 * <h3>版本从哪来</h3>
 * GitHub Releases API：{@code /repos/<owner>/<repo>/releases/latest}。
 * 不需要 token（公开仓库，未认证限额 60 次/小时，而我们最多几小时查一次）。
 * 拿到的 {@code tag_name} 形如 {@code v0.3.3}，比的是 {@link #compare}（按点分数字比）。
 */
public final class AppUpdate {

    private static final String TAG = "DSH_APPUPDATE";

    /** 仓库（与 GitHub发布步骤.md 里的一致）。 */
    private static final String OWNER = "Tongyu3";
    private static final String REPO = "DSH-Mobile-Android-NO-ROOT";
    private static final String API = "https://api.github.com/repos/" + OWNER + "/" + REPO;

    private static final String KEY_CACHE_VER = "app_update_ver";
    private static final String KEY_CACHE_URL = "app_update_url";
    private static final String KEY_CACHE_AT = "app_update_at";
    /** 已经为哪个版本推送过通知 —— 同一个版本只吵一次。 */
    private static final String KEY_NOTIFIED = "app_update_notified";
    private static final long CACHE_MS = 6 * 60 * 60 * 1000L;   // 6 小时

    private AppUpdate() { }

    /** 查到的结果（给人看的 + 给按钮用的）。 */
    public static final class Info {
        public String version;      // 去掉 v 前缀的版本号，例如 "0.3.3"
        public String tag;          // 原始 tag，例如 "v0.3.3"
        public String notes;        // 发行说明（截断过）
        public String apkUrl;       // 直接下 APK 的地址（没有则回落 release 页面）
        public String pageUrl;      // release 页面
    }

    // ── 本机版本 ────────────────────────────────────────────

    /** 当前装的 App 版本名（读不到返回 "?"）。 */
    public static String currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ── 版本比较 ────────────────────────────────────────────

    /**
     * 按点分数字比较两个版本号（{@code 0.3.10 > 0.3.9}，字符串比会搞错）。
     * 非数字段当 0 处理；前缀 {@code v} 会被忽略。
     *
     * @return &gt;0 表示 a 比 b 新
     */
    public static int compare(String a, String b) {
        String[] x = norm(a).split("\\.");
        String[] y = norm(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0;
            int yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static String norm(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int i = s.indexOf('-');                 // 0.3.3-beta1 → 0.3.3
        if (i > 0) s = s.substring(0, i);
        return s;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── 联网查 ──────────────────────────────────────────────

    /**
     * 查最新 Release（带 6 小时缓存；force=true 强制刷）。
     *
     * <p>失败返回 null —— **调用方不要因此报警**：查版本失败很常见（没网、GitHub 被墙），
     * 它不该影响任何其它功能。
     */
    public static Info fetchLatest(Context c, boolean force) {
        SharedPreferences sp = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
        long at = sp.getLong(KEY_CACHE_AT, 0L);
        if (!force && System.currentTimeMillis() - at < CACHE_MS) {
            String v = sp.getString(KEY_CACHE_VER, null);
            if (v != null) {
                Info i = new Info();
                i.version = v;
                i.tag = "v" + v;
                i.apkUrl = sp.getString(KEY_CACHE_URL, null);
                i.pageUrl = "https://github.com/" + OWNER + "/" + REPO + "/releases";
                return i;
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API + "/releases/latest").openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Tdsh-AppUpdate");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "GitHub 返回 " + code);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 512 * 1024) break;
                }
            }
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            Info i = new Info();
            i.tag = o.optString("tag_name", "");
            i.version = norm(i.tag);
            i.pageUrl = o.optString("html_url", "https://github.com/" + OWNER + "/" + REPO + "/releases");
            i.notes = o.optString("body", "");
            if (i.notes.length() > 1200) i.notes = i.notes.substring(0, 1200) + "…";
            // 优先找 .apk 资产（用户点一下就能装）
            org.json.JSONArray assets = o.optJSONArray("assets");
            for (int k = 0; assets != null && k < assets.length(); k++) {
                org.json.JSONObject a = assets.optJSONObject(k);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    i.apkUrl = a.optString("browser_download_url", null);
                    break;
                }
            }
            if (i.version.isEmpty()) return null;
            sp.edit().putString(KEY_CACHE_VER, i.version)
                    .putString(KEY_CACHE_URL, i.apkUrl)
                    .putLong(KEY_CACHE_AT, System.currentTimeMillis()).apply();
            return i;
        } catch (Throwable t) {
            Log.w(TAG, "查最新版本失败: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 有没有比当前更新的版本。查不到就返回 null（调用方当"不知道"处理）。 */
    public static Info newer(Context c, boolean force) {
        Info i = fetchLatest(c, force);
        if (i == null) return null;
        return compare(i.version, currentVersion(c)) > 0 ? i : null;
    }

    // ── 推送 ────────────────────────────────────────────────

    /**
     * 发现新版本时推一条通知（**同一个版本只推一次**）。
     *
     * <p>由 {@link DshService} 的监督循环定期调用（它已经在 5 秒一轮地跑，
     * 顺手加个计数器就好，不用再起一个定时器）。
     *
     * <p>为什么"只推一次"：这个项目最怕的就是**吵**。用户看到一个版本的更新提示
     * 之后自己决定什么时候装，不该每隔十分钟再提醒一遍。
     *
     * @return 推了返回 true，没推（没有新版/已经推过/查不到）返回 false
     */
    public static boolean notifyIfNewer(Context c) {
        try {
            Info i = newer(c, false);
            if (i == null) return false;
            SharedPreferences sp = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
            if (i.version.equals(sp.getString(KEY_NOTIFIED, null))) return false;

            android.app.NotificationManager nm =
                    (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String ch = "dsh_update";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(ch) == null) {
                    android.app.NotificationChannel nc = new android.app.NotificationChannel(
                            ch, "新版本提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                    nc.setShowBadge(true);
                    nm.createNotificationChannel(nc);
                }
            }
            Intent open = new Intent(c, UpgradeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 10, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(c, ch)
                    : new android.app.Notification.Builder(c);
            b.setContentTitle("Tdsh 有新版本 " + i.tag)
                    .setContentText("点这里看改了什么、一键下载（当前 " + currentVersion(c) + "）")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setColor(IconSwitcher.accentColor(c))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(0x4455, b.build());

            sp.edit().putString(KEY_NOTIFIED, i.version).apply();
            Env.report(c, "  [更新] 发现新版本 " + i.tag + "（当前 " + currentVersion(c) + "），已推送通知\n");
            Log.i(TAG, "已推送新版本通知: " + i.tag);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "推送新版本通知失败", t);
            return false;
        }
    }

    /**
     * 界面上那一行状态文字（**不联网、不阻塞**）。
     *
     * <p>只读已经缓存过的结果 —— 界面可能在主线程调它，绝不能在这里发网络请求。
     * 想真查一次就走 {@link #newer}(c, true)，并且要在后台线程里调。
     */
    public static String statusText(Context c) {
        String cur = currentVersion(c);
        try {
            SharedPreferences sp = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_CACHE_VER, null);
            long at = sp.getLong(KEY_CACHE_AT, 0L);
            if (v == null) return "当前 v" + cur + "（还没查过更新）";
            boolean isNewer = compare(v, cur) > 0;
            String when = at <= 0 ? "" : ("，上次检查 " +
                    android.text.format.DateFormat.format("MM-dd HH:mm", at));
            return "当前 v" + cur + (isNewer ? " → 有新版 v" + v : "（已是最新）") + when;
        } catch (Throwable t) {
            return "当前 v" + cur;
        }
    }
}
