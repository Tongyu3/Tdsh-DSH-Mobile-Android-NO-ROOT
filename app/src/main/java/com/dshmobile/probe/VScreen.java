package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

/**
 * 「虚拟屏自动化」：把群友「如月雨露」做的**受信任虚拟屏**方案随 App 一起分发、一键装起来。
 *
 * <h3>这套方案到底是什么（一句话）</h3>
 * 只有 **shell 身份**（uid 2000）才能建"受信任"虚拟屏，有了它应用才能放进 freeform 窗口容器里
 * 后台跑而不跳屏；取帧/输入走本地 HTTP，不需要无障碍、也不需要每次插线。
 * 所以它天然分成两半：
 * <ul>
 *   <li>**两个 dex**（{@code dsh.VsDaemon} 建屏取帧、{@code dsh.ShellDaemon} 命令端点）——
 *       必须由 shell 身份启动，我们借 **Shizuku** 完成这一步（没有 Shizuku 就给出 adb 命令让用户自己跑一次）；</li>
 *   <li>**dex 本体**随 APK 的 assets 分发（这就是"整合进安装包"），App 负责落到
 *       {@code /data/local/tmp/dsh-vsd} 并拉起来。</li>
 * </ul>
 *
 * <h3>我们相对原方案改了什么（安全，文档 §8 自己点名的三条）</h3>
 * <ol>
 *   <li>原版 {@code VsDaemon} 是 {@code new ServerSocket(port)} —— **绑所有网卡且零鉴权**，
 *       同一个 WiFi 下任何人都能读屏、还能发启动指令。这里改成**只绑回环 + 每个请求校验 X-Token**；</li>
 *   <li>token 原来放 {@code /sdcard/dsh/.shd-token}（共享存储里任何有存储权限的 App 都能读），
 *       这里放 {@code /data/local/tmp/dsh-vsd/} 且 chmod 600；</li>
 *   <li>dex 也一样从 {@code /sdcard} 挪到 {@code /data/local/tmp}（原来能被替换 → 下次启动即任意代码执行）。</li>
 * </ol>
 *
 * <h3>为什么这些重活必须在后台线程</h3>
 * Shizuku 那条路要等 binder、启动脚本里还 {@code sleep 10}；HTTP 查询也有超时。
 * 项目里已经有"主线程 waitFor 把界面卡死"的血泪教训，所以这里**所有方法都是阻塞的**，
 * 调用方（界面或桥）自己放后台线程。
 */
public final class VScreen {

    private static final String TAG = "DSH_VSCREEN";
    private static final String K_TOKEN = "vsd_token";
    /** 暂存目录名（App 外部私有目录，shell 读得到；装完就删）。 */
    private static final String STAGE = "vsd";

    private VScreen() { }

    // ── token ────────────────────────────────────────────────

    /** X-Token 口令：随机一次、存 prefs、之后一直用（改了就两边对不上）。 */
    public static String token(Context c) {
        SharedPreferences p = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
        String t = p.getString(K_TOKEN, null);
        if (t != null && t.length() > 8) return t;
        byte[] raw = new byte[18];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        t = sb.toString();
        p.edit().putString(K_TOKEN, t).apply();
        return t;
    }

    // ── 资产落地 ──────────────────────────────────────────────

    /**
     * 把 assets 里的两个 dex + token 写到 App 外部私有目录，返回该目录路径。
     *
     * <p>为什么不直接写 {@code /data/local/tmp}：App 身份根本写不进去（那是 shell 的目录）。
     * 先落到"App 能写、shell 能读"的 {@code /sdcard/Android/data/<pkg>/files/vsd}，
     * 再由 shell 那条命令搬进最终位置、然后把这个暂存目录删掉。
     */
    public static String stage(Context c, String token) throws Exception {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) throw new IllegalStateException("拿不到 App 外部私有目录");
        File stage = new File(dir, STAGE);
        //noinspection ResultOfMethodCallIgnored
        stage.mkdirs();
        copyAsset(c, "vsd/vs-daemon.dex", new File(stage, "vs-daemon.dex"));
        copyAsset(c, "vsd/shell-daemon.dex", new File(stage, "shell-daemon.dex"));
        try (OutputStream os = new FileOutputStream(new File(stage, VScreenCmd.TOKEN_FILE))) {
            os.write((token + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return stage.getAbsolutePath();
    }

    private static void copyAsset(Context c, String name, File to) throws Exception {
        try (InputStream in = c.getAssets().open(name);
             OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    // ── 装 / 启 / 停 ─────────────────────────────────────────

    /**
     * 一键：落资产 → 借特权身份搬运并拉起 → 等它真的能用。
     *
     * <p>身份优先用 **root**（见 {@link ShizukuBridge#preferRoot}）：root 不受 adbd
     * 生死影响、还能开机自启，是"永久授权"的唯一正解；没有 root 才回落到 Shizuku。
     *
     * @return 给人看的执行报告（含守护自己的启动日志）
     */
    public static String installAndStart(Context c) throws Exception {
        String token = token(c);
        String stageDir = stage(c, token);
        String script = VScreenCmd.installScript(stageDir, token);
        String out;
        try {
            if ("root".equals(ShizukuBridge.privilegedBackend(c))) {
                out = prefix("root") + ShizukuBridge.execAsRoot(script);
            } else {
                out = execViaShizuku(c, script);
            }
        } finally {
            // 暂存目录里有 token 与 dex —— 装完就删，别留在共享存储上
            try { Env.deleteRecursively(new File(stageDir)); } catch (Throwable ignore) { }
        }
        writeContainerToken(c, Env.base(c));
        return out;
    }

    /** 在报告里写明这次用的是哪个身份 —— 排查"守护为什么起不来"时这是第一个要看的信息。 */
    private static String prefix(String backend) {
        return "（身份：" + ("root".equals(backend) ? "root（su）" : "shell（Shizuku）") + "）\n";
    }

    /**
     * 走 Shizuku 拉起，daemon 用户服务失败时退回普通用户服务。
     *
     * <p>为什么要有这个退回：daemon 用户服务在个别 Shizuku 版本/机型上起不来。
     * 守护本身是 {@code nohup} 脱离出去的，所以两种模式**对守护的存活没有区别**，
     * 差别只在"App 进程被杀时服务会不会跟着走"。
     */
    private static String execViaShizuku(Context c, String script) throws Exception {
        try {
            return ShizukuBridge.execShellDaemon(c, script);
        } catch (Throwable daemonFail) {
            return "（daemon 模式不可用，已退回普通模式：" + daemonFail + "）\n"
                    + ShizukuBridge.execShell(c, script);
        }
    }

    /** 同一个脚本按当前首选身份执行（停 / 重启守护用）。 */
    private static String execPrivileged(Context c, String script) throws Exception {
        if ("root".equals(ShizukuBridge.privilegedBackend(c))) {
            return prefix("root") + ShizukuBridge.execAsRoot(script);
        }
        return execViaShizuku(c, script);
    }

    /** 停掉守护。注意：虚拟屏上正在跑的应用会被系统一起收掉（方案固有行为）。 */
    public static String stop(Context c) throws Exception {
        return execPrivileged(c, VScreenCmd.stopScript());
    }

    /** 只重启虚拟屏守护（改完分辨率 / 它崩了之后用）。 */
    public static String restartVsd(Context c) throws Exception {
        return execPrivileged(c, VScreenCmd.restartVsdScript(token(c)));
    }

    /**
     * 把 token 写进容器（{@code /run/dsh-vsd/token}），好让容器里的 DSH 插件能直连守护。
     *
     * <p>写进容器的那份**只有容器里 root 读得到**（0600），而且它只是"访问本机回环"的口令，
     * 不涉及任何外部凭据。容器每次启动都会重写，避免用户清容器后 token 丢失。
     */
    public static void writeContainerToken(Context c, File base) {
        try {
            File dir = new File(new File(base, "rootfs"), "run/dsh-vsd");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "token");
            try (OutputStream os = new FileOutputStream(f)) {
                os.write((token(c) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            try { android.system.Os.chmod(f.getAbsolutePath(), 0600); } catch (Throwable ignore) { }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "写容器 token 失败", t);
        }
    }

    // ── 查状态（HTTP） ───────────────────────────────────────

    /** 查一次状态，返回给人看的多行文本（不抛异常，失败也返回能看懂的话）。 */
    public static String statusText(Context c) {
        String token = token(c);
        StringBuilder sb = new StringBuilder();
        String shizuku = ShizukuBridge.statusText(c);
        sb.append("Shizuku：").append(shizuku).append('\n');
        String list = null, health = null, listErr = null, healthErr = null;
        try {
            list = http(VScreenCmd.PORT_VSD, "/list", token, 4000);
        } catch (Throwable t) {
            listErr = shortErr(t);
        }
        try {
            health = http(VScreenCmd.PORT_SHD, "/health", token, 4000);
        } catch (Throwable t) {
            healthErr = shortErr(t);
        }
        if (listErr != null) sb.append("虚拟屏守护：").append(explain(listErr)).append('\n');
        if (healthErr != null) sb.append("命令端点：").append(explain(healthErr)).append('\n');
        if (list != null) {
            int n = VScreenCmd.screenCount(list);
            boolean trusted = VScreenCmd.trusted(list);
            String desc = VScreenCmd.describe(n, trusted, VScreenCmd.displayId(list, 0),
                    VScreenCmd.frames(list, 0));
            sb.append("虚拟屏守护：").append(desc == null ? "在跑，但没有可用屏" : desc).append('\n');
        }
        if (health != null) {
            int uid = VScreenCmd.uidFromHealth(health);
            sb.append("命令端点：").append(uid == 2000 || uid == 0
                    ? ("在跑（身份 " + (uid == 0 ? "root" : "shell") + "，端口 "
                       + VScreenCmd.PORT_SHD + " / " + VScreenCmd.PORT_SHD2 + "）")
                    : ("返回异常：" + health.trim())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 把"连不上"翻译成人话。
     *
     * <p>为什么要分情况：**403 不是"没在跑"，而是"在跑但口令不对"** ——
     * 手机上很可能装着另一份守护（别的方法装的、或用别的 token 起的）。
     * 这两种情况对用户来说要做的事完全不同：前者要点「一键安装并启动」，
     * 后者要检查为什么装不上。混成一句"没在跑"会把排查带偏（实测就是这样）。
     */
    private static String explain(String err) {
        String e = err == null ? "" : err;
        if (e.contains("403")) return "在跑，但口令对不上（点「一键安装并启动」重装一次即可）";
        if (e.contains("refused") || e.contains("Failed to connect") || e.contains("ECONNREFUSED")) {
            return "没在跑（点「一键安装并启动」）";
        }
        return "连不上（" + e + "）";
    }

    /**
     * 给容器里的 DSH 插件用的桥接口（{@code /vscreen?op=…}）。
     *
     * <p>为什么插件不自己起守护：起守护要 shell 身份，而插件跑在容器里（普通 uid）。
     * 所以"拉起/停止"这一步必须回到 App 这边借 Shizuku 做；查状态、取帧、点按这些
     * 插件可以直接打 {@code 127.0.0.1:18240}（token 在 {@code /run/dsh-vsd/token}），
     * 少一跳、更快。
     */
    public static String bridge(Context c, Map<String, String> p) {
        String op = p.getOrDefault("op", "status");
        try {
            switch (op) {
                case "ensure": {
                    String out = installAndStart(c);
                    return "OK 已拉起虚拟屏守护\n" + VScreenCmdSafe.status(c) + tail(out, 800);
                }
                case "stop":
                    return "OK\n" + stop(c);
                case "restart":
                    return "OK\n" + restartVsd(c);
                default:
                    return VScreenCmdSafe.status(c);
            }
        } catch (Throwable t) {
            return "ERROR " + t + "\n";
        }
    }

    /** 没有 Shizuku 时给用户抄的命令（文档里那套 adb 流程，路径按我们的目录改过）。 */
    public static String adbCommands(Context c) {
        String t = token(c);
        String dir = VScreenCmd.DEVICE_DIR;
        return "# 1) 把两个 dex 推到手机（APK 里的 assets/vsd 下就是它们）\n"
                + "adb push vs-daemon.dex " + dir + "/vs-daemon.dex\n"
                + "adb push shell-daemon.dex " + dir + "/shell-daemon.dex\n"
                + "adb shell \"mkdir -p " + dir + " && chmod 600 " + dir + "/*.dex\"\n\n"
                + "# 2) 写 token（和 App 里那份一致，下面这串就是）\n"
                + "adb shell \"printf '%s\\n' " + t + " > " + dir + "/" + VScreenCmd.TOKEN_FILE + "\"\n"
                + "adb shell \"chmod 600 " + dir + "/" + VScreenCmd.TOKEN_FILE + "\"\n\n"
                + "# 3) 起守护（先清陈旧的 dalvik-cache，否则 app_process 会 Aborted）\n"
                + "adb shell \"rm -rf /data/local/tmp/dalvik-cache\"\n"
                + "adb shell \"nohup sh -c 'CLASSPATH=" + dir + "/vs-daemon.dex app_process /system/bin dsh.VsDaemon "
                + VScreenCmd.PORT_VSD + " " + dir + "/" + VScreenCmd.TOKEN_FILE + "' > " + dir + "/vsd.log 2>&1 &\"\n"
                + "adb shell \"nohup sh -c 'CLASSPATH=" + dir + "/shell-daemon.dex app_process /system/bin dsh.ShellDaemon "
                + VScreenCmd.PORT_SHD + " " + dir + "/" + VScreenCmd.TOKEN_FILE + "' > " + dir + "/shd.log 2>&1 &\"\n\n"
                + "# 4) 校验（应当能看到 displayId 与 uid=2000）\n"
                + "adb shell \"curl -s -H 'X-Token: " + t + "' http://127.0.0.1:"
                + VScreenCmd.PORT_VSD + "/list\"\n";
    }

    // ── 小工具 ───────────────────────────────────────────────

    private static String http(int port, String path, String token, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("X-Token", token);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + bos.toString("UTF-8").trim());
            return bos.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static String shortErr(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /** 包一层，免得 bridge() 里为了"查状态"再写一遍异常处理。 */
    private static final class VScreenCmdSafe {
        static String status(Context c) {
            try { return statusText(c); } catch (Throwable t) { return "（查状态失败：" + t + "）\n"; }
        }
    }
}
