package com.dshmobile.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 常驻前台服务：在容器里维持 `dsh --profile web` 进程。
 *
 * 为什么需要它：
 *   原先 dsh 进程属于 Activity，一旦 App 退到后台，系统随时可能回收，
 *   在 OriginOS 这类后台管理激进的 ROM 上尤其明显。放进前台服务后，
 *   进程有常驻通知与前台优先级，切后台也能继续跑。
 *
 * 注意：本项目 targetSdk = 28（为了 W^X 豁免），所以
 *   · 不需要声明 foregroundServiceType
 *   · 不需要 POST_NOTIFICATIONS 运行时权限
 * 这两个要求都是 targetSdk 34/33 才生效的。
 *
 * 状态通过静态字段暴露给 Activity 轮询（不引入 AndroidX / 广播框架）。
 */
public class DshService extends Service {

    private static final String TAG = "DSH_SERVICE";    private static final String CH_ID = "dsh_runtime";
    private static final int NOTIF_ID = 0x4453;   // "DS"
    public static final String PREFS = "dsh_settings";
    public static final String KEY_API = "deepseek_api_key";

    /**
     * 通知栏那个「悬浮球」按钮的动作。
     *
     * <p>悬浮球不是 Service 了（见 {@link BallWindow} 类注释），所以这里用广播，
     * 由本服务自己收、自己加窗 —— 不引入第二个组件。
     */
    public static final String ACTION_BALL_TOGGLE = "com.dshmobile.probe.BALL_TOGGLE";

    /** 通知栏按钮的接收器（运行时注册，跟着服务生死）。 */
    private android.content.BroadcastReceiver ballReceiver;

    /** 供 Activity 轮询的状态。 */
    private static volatile String sUrl;
    private static volatile String sState = "未启动";
    private static volatile Process sProcess;

    private volatile boolean keepRunning = true;
    /** 由 Activity 置位：请求重启容器内的 dsh（例如 API Key 改过）。 */
    private static volatile boolean sRestartRequested = false;

    /** 当前实例。用来让别处（比如换了图标配色）催它刷新一下通知。 */
    private static volatile DshService sInstance;

    /**
     * 让服务重建一次它的常驻通知。
     *
     * <p>为什么要这个：通知的强调色和图标是**构建那一刻**定下来的，
     * 用户在「桌面图标」里换了配色之后，如果服务不重建通知，
     * 通知栏里那条会一直挂着旧颜色 —— 看起来就像"没生效"。
     */
    public static void refreshNotification() {
        DshService s = sInstance;
        if (s == null) return;
        try {
            s.updateNotification(sState);
        } catch (Throwable ignore) { }
    }

    /**
     * 容器还没装好（首次初始化没完成）。
     *
     * 原来的 startDsh() 遇到这种情况只每 10 秒静默重试一次，界面上完全看不出来，
     * 用户只能看到"一直没反应"。现在把它暴露出去，Activity 回到前台时
     * 可以据此自动补跑初始化。
     */
    private static volatile boolean sNeedsProvision = false;

    public static boolean getNeedsProvision() { return sNeedsProvision; }

    /** onCreate 是否跑过 —— 用来区分"服务根本没起来"和"起来了但 dsh 没就绪"。 */
    private static volatile boolean sCreated = false;
    public static boolean isCreated() { return sCreated; }

    /**
     * dsh 进程最近输出的若干行。
     *
     * 为什么必须留：原来这段输出**只看 URL、其余全部丢弃**，
     * 于是 dsh 起不来的时候，用户和我们都拿不到任何原因 ——
     * 只能看到"等待超时"。现在失败信息里会带上这几行。
     */
    private static final java.util.ArrayDeque<String> sRecent =
            new java.util.ArrayDeque<>();
    private static final int RECENT_MAX = 60;

    /**
     * 最后一条 stdout 的到达时刻。
     *
     * <p>光有"最近一行"不够：那一行会**一直待在那里**，DSH 启动完就不再打东西，
     * 于是半小时后 {@link #getCurrentLine()} 返回的还是启动日志里的那句
     * {@code listening on …}，悬浮球的阶段就被它钉死（见 {@link BallStage#LINE_TTL_MS}）。
     */
    private static volatile long sLastLineAt = 0L;

    /*
     * ── 网页上报的「正在干活」与阶段（见 MainActivity.AppBridge.pageBusy/pageStage）──
     *
     * 为什么还要网页这一路：我们的 stdout 只有启动日志，CPU 采样在"等模型吐字"
     * 时也可能测不到增量。而网页**就长在对话界面上** —— 有没有在生成、有没有工具在跑，
     * 它比谁都清楚。这是把"悬浮球阶段"接对的关键一手（2026-10-21 用户报的 bug）。
     */
    private static volatile boolean sPageBusy = false;
    private static volatile long sPageBusyAt = 0L;
    private static volatile int sPageStage = -1;
    private static volatile long sPageStageAt = 0L;

    /** 网页上报"在生成/在跑工具"。由 patch.js 每秒左右推一次。 */
    public static void setPageBusy(boolean b) {
        sPageBusy = b;
        sPageBusyAt = System.currentTimeMillis();
    }

    /** 网页上报阶段（{@link BallStage} 的常量）。 */
    public static void setPageStage(int stage) {
        if (stage < BallStage.IDLE || stage > BallStage.ERROR) return;
        sPageStage = stage;
        sPageStageAt = System.currentTimeMillis();
    }

    /** 网页这一路是否还有效（3 秒内有心跳就算在干活）。 */
    public static boolean pageBusy() {
        return sPageBusy && (System.currentTimeMillis() - sPageBusyAt) < 3_000L;
    }

    public static int pageStage() { return sPageStage; }

    public static long pageStageAge() {
        return sPageStageAt == 0L ? Long.MAX_VALUE
                : System.currentTimeMillis() - sPageStageAt;
    }

    /**
     * 已经写进报告的"非 DSH 的本机服务"地址。
     *
     * <p>为什么要有：插件会在自己的端口上起面板服务、并把地址打进 dsh 的输出。
     * 我们要把它忽略掉（见 {@link UrlPick}），但**得在报告里留一行** ——
     * 否则下次用户说"界面又变成插件的网页了"，日志里什么都看不出来。
     * 用 set 去重，免得同一行刷满报告。
     */
    private final java.util.Set<String> foreignReported =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static String getRecentOutput() {
        synchronized (sRecent) {
            if (sRecent.isEmpty()) return "(dsh 没有任何输出)";
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, sRecent.size() - 12);   // 只给最后 12 行
            int i = 0;
            for (String l : sRecent) {
                if (i++ < skip) continue;
                sb.append("      ").append(l).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 最近一行**有意义**的输出 —— 悬浮球气泡用它当"正在干什么"。
     *
     * <p>为什么要有它：用户希望气泡能反映 agent 的进度（"气泡最好能显示思考过程"）。
     * 我们拿不到模型内部的推理，但 DSH 会把进度打到 stdout（起工具、跑命令、写文件），
     * 那一行就是**当前正在做的事** —— 比干巴巴的"正在运行"有用得多。
     *
     * <p>过滤掉纯装饰行（空行、纯分隔线、只有空格），否则气泡会显示成一片空白。
     */
    public static String getCurrentLine() {
        synchronized (sRecent) {
            java.util.Iterator<String> it = sRecent.descendingIterator();
            while (it.hasNext()) {
                String l = it.next();
                if (l == null) continue;
                String t = l.trim();
                if (t.isEmpty()) continue;
                if (t.matches("[-=_*·—\\s]{3,}")) continue;      // 分隔线
                return t.length() > 120 ? t.substring(0, 120) + "…" : t;
            }
            return null;
        }
    }

    /**
     * 最近一行输出**有多旧**（毫秒）。没输出过返回 {@link Long#MAX_VALUE}。
     *
     * <p>给 {@link BallStage#detect} 用：太旧的行不该参与阶段判定（见那边的说明）。
     */
    public static long getCurrentLineAgeMs() {
        long at = sLastLineAt;
        return at == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }

    /**
     * 记一条"非 dsh 进程"的事件到最近输出里（例如无障碍守护的自愈动作）。
     *
     * <p>放进同一个环形缓冲是为了让「复制日志」一次带走全部线索 ——
     * 之前排查权限问题时，最需要的信息恰恰在 dsh 的输出之外。
     */
    public static void note(String line) {
        if (line == null) return;
        synchronized (sRecent) {
            sRecent.addLast("[守护] " + line);
            while (sRecent.size() > RECENT_MAX) sRecent.removeFirst();
        }
    }

    /** 像报错的行（用于把关键输出写进报告）。 */
    private static boolean looksLikeError(String line) {
        String s = line.toLowerCase();
        return s.contains("error") || s.contains("eacces") || s.contains("enoent")
            || s.contains("eaddrinuse") || s.contains("cannot") || s.contains("cannot find")
            || s.contains("throw") || s.contains("exception") || s.contains("failed")
            || s.contains("listen") || s.contains("port");
    }

    /** 往报告文件追加（和 MainActivity 用同一个文件，方便「复制日志」一次带走）。 */
    private void appendReport(String text) {
        try (java.io.OutputStream os = new java.io.FileOutputStream(
                new java.io.File(Env.base(this), "report.txt"), true)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) { }
        /*
         * 同时镜像一份到共享存储（2026-10-21）。
         *
         * 为什么：report.txt 在 App 私有目录里，**非 root 拿不到** —— 用户说"发烫 / 启动慢"
         * 时，我这边什么都看不到，只能让他截图。镜像到 /sdcard/.dsh-phone/ 之后，
         * 用数据线或文件管理器就能把日志发出来（启动耗时分解、插件安装结果都在里面）。
         * 超过 256KB 重来，避免长期使用把存储写满。
         */
        try {
            java.io.File dir = new java.io.File("/sdcard/.dsh-phone");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "report-mirror.log");
            if (f.length() > 256 * 1024) f.delete();
            try (java.io.OutputStream os = new java.io.FileOutputStream(f, true)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignore) { }
    }

    /** 请求重启 dsh 进程（不重启 Service 本身，避免 stop/start 的时序问题）。 */
    public static void requestRestart() { sRestartRequested = true; }

    public static String getUrl() { return sUrl; }

    public static String getState() { return sState; }

    /** 从 URL 里取 host:port（状态栏显示用）。 */
    private static String hostPortOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getHost() + ":" + u.getPort();
        } catch (Throwable t) {
            return url;
        }
    }

    public static boolean isAlive() {
        Process p = sProcess;
        // 走 Proc：API 24/25 上没有 Process#isAlive（见 Proc 的类注释）
        return Proc.isAlive(p);
    }

    /**
     * 读 API Key —— **读出来先洗一遍**（见 {@link ApiKey}）。
     *
     * <p>为什么在"读"的时候就洗：用户早先存进去的值可能是脏的（带零宽字符之类），
     * 而我们没法让他重填。这里顺手改写回干净值（自愈），于是
     * 「明明填了却说 Key 不对」这一类历史遗留数据在**下一次启动时就自己好了**，
     * 不需要重装 App。
     */
    public static String getApiKey(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_API, "");
        if (raw == null) raw = "";
        String clean = ApiKey.sanitize(raw);
        if (!clean.equals(raw)) {
            try {
                sp.edit().putString(KEY_API, clean).apply();
                note("API Key 里有 " + (raw.length() - clean.length())
                        + " 个多余字符（空白/零宽/全角），已自动清理："
                        + ApiKey.describe(clean));
            } catch (Throwable ignore) { }
        }
        return clean;
    }

    public static void setApiKey(Context c, String key) {
        // 存之前洗一遍：脏值一旦进 prefs，就只会在下次读的时候才被发现
        String clean = ApiKey.sanitize(key);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, clean).apply();
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
        sCreated = true;
        sInstance = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动容器…"));
        /*
         * 通知栏那个「悬浮球」按钮：用运行时注册的接收器接住。
         *
         * 为什么不放 manifest：这个接收器只在服务活着时有意义
         * （它要做的事就是"往本服务的窗口里加个球"），
         * 随服务生死自动注册/注销最省事，也不会在服务没跑时被空唤起。
         */
        ballReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (BallWindow.isShown()) {
                        BallWindow.hide(DshService.this);
                        Toast.makeText(DshService.this, "已收起悬浮球（DSH 仍在跑）",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        BallWindow.show(DshService.this);
                        BallWindow.reconcile(DshService.this, true);
                        Toast.makeText(DshService.this, "已打开悬浮球", Toast.LENGTH_SHORT).show();
                    }
                    updateNotification(sState);
                } catch (Throwable t) {
                    Log.w(TAG, "通知栏悬浮球按钮失败", t);
                }
            }
        };
        registerReceiver(ballReceiver, new android.content.IntentFilter(ACTION_BALL_TOGGLE));
        new Thread(this::supervise, "dsh-supervisor").start();
        // 悬浮球单独一个看护线程 —— 为什么不能塞进 supervise()，见 ballLoop() 的注释
        new Thread(this::ballLoop, "dsh-ball").start();
        new Thread(this::watchRestart, "dsh-restart-watcher").start();
        // 无障碍守护：厂商把无障碍开关关掉时自动写回去（需要一次性 WRITE_SECURE_SETTINGS 授权）
        A11yGuard.start(this);
        /*
         * 桌宠悬浮窗宿主**已移除**（2026-10-21，用户要求）。
         *
         * 它原来会读 /sdcard/.dsh-phone/pet-overlay.json，替 dsh-pet-android 插件
         * 建一个 `fullscreen-transparent` 的整屏透明窗来放宠物页面。
         * 问题是那个页面一报错就整块红字浮在对话上面（用户截图："dsh-pet 配置错误：
         * 配置加载失败：config http 404"）—— 而那个窗口归本类管，删掉它就不会再出现。
         * 用户明确说"我已经不需要了"，所以连带 {@code PetOverlayHost.java} 一起删除。
         */
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 灵动岛（实时进度悬浮条）：DSH 起来就按用户保存的开关恢复；关过的不再自动开
        HudOverlayService.restore(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 伴随 DSH 停止收起胶囊，但**保留开关**，下次启动自动恢复
        HudOverlayService.suspend(this);
        keepRunning = false;
        if (sInstance == this) sInstance = null;
        try {
            if (sProcess != null) sProcess.destroy();
        } catch (Throwable ignore) { }
        sProcess = null;
        sUrl = null;
        sState = "已停止";
        // 服务没了，窗口也不该留着；但**不要动用户的开关**（见 detachQuietly 注释）
        try { BallWindow.detachQuietly(); } catch (Throwable ignore) { }
        try { if (ballReceiver != null) unregisterReceiver(ballReceiver); } catch (Throwable ignore) { }
        ballReceiver = null;
        super.onDestroy();
    }

    /**
     * 悬浮球看护线程：用户要"退到后台、有任务时自动出现；回到主界面就消失"。
     *
     * <h3>为什么必须是独立线程（这是踩出来的）</h3>
     * 一开始把它放在 {@link #supervise()} 的循环里，想着"那儿已经每 5 秒跑一次，
     * 不用多起线程"。结果**它一次都没执行过**：{@code startDsh()} 内部会
     * **阻塞读容器的输出流直到进程结束**（这是设计如此 —— 就靠它解析带 token 的 URL），
     * 所以容器活着的整个期间，那个循环根本回不到顶部。
     * 换句话说：**监督循环的"5 秒一次"只在容器没跑的时候成立**，
     * 而悬浮球恰恰要在容器跑着的时候工作。两者节奏完全不搭。
     *
     * <p>独立线程反而更省心：它的周期不受容器状态影响，
     * 也不会因为容器卡住而一起卡住。
     */
    /**
     * 悬浮球看护线程这一拍该睡多久（毫秒）。
     *
     * <h3>为什么不是固定值（2026-10-21 用户报"用久了发热严重"）</h3>
     * 这段循环原来固定 1.5 秒一拍，每拍扫一遍 /proc。手机有几百个进程，
     * 等于**永不停止**地每分钟上万次文件读取 —— 屏幕黑着也照跑，电和热都浪费在这上面。
     *
     * <p>三档依据很简单：**球看不见的时候，没有任何理由高频轮询**。
     * <ul>
     *   <li>息屏 → 15 秒：什么都看不见；</li>
     *   <li>我们的界面在前台 → 4 秒：按规则球本来就不显示（自己的界面在前台一律不显示），
     *       阶段主要由网页那一路报送上来；</li>
     *   <li>其余（在后台）→ 2 秒：球可能随时要冒出来，得跟上。</li>
     * </ul>
     */
    private long ballTickMs() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return 15_000L;
        } catch (Throwable ignore) { }
        return Foreground.ours() ? 4_000L : 2_000L;
    }

    private void ballLoop() {
        TaskWatch.Snap snap = null;
        long activeAt = 0L;
        /** 上一拍是否"有任务在跑" —— 用来算出"任务刚刚结束"（→ 已完成）。 */
        boolean wasBusy = false;
        /** 连续几拍 CPU 都在重烧（≥2 才算真的在干活，见下面的说明）。 */
        int heavyStreak = 0;
        /** 带 pid 缓存的采样器（进程列表 20 秒才重扫一次，见 TaskWatch.Sampler）。 */
        TaskWatch.Sampler sampler = null;
        /*
         * 起手先留一行痕迹：用户报"球不换表情"时，第一个要回答的问题是
         * "这个心跳到底跑没跑起来" —— 没有这一行的话，日志文件是空的，
         * "没跑"和"跑了但没判出来"就分不开。
         */
        BallWindow.trace("阶段心跳启动（悬浮球开关=" + BallWindow.enabled(this)
                + "，自动模式=" + BallWindow.autoMode(this) + "）");
        /*
         * 采样间隔**按状态自适应**（2026-10-21 用户报"用久了发热严重"）。
         *
         * 老写法固定 1.5 秒一拍，每拍都把 /proc 全扫一遍 —— 屏幕黑着也照跑，
         * 一小时就是几千次全表扫描。现在分成三档，并且用 {@link TaskWatch.Sampler}
         * 把进程列表缓存起来（20 秒才重扫一次，中间每拍只读 3 个进程的 stat）：
         *
         *   息屏       15 秒：什么都看不见，只有极少数线索要维持
         *   我们在前台  4 秒：球本来就不显示（规则：自己的界面在前台一律不显示），
         *                    阶段主要靠网页那一路报，不需要高频
         *   在后台      2 秒：球可能随时要冒出来，得跟上
         */
        while (keepRunning) {
            try {
                Thread.sleep(ballTickMs());
            } catch (InterruptedException ie) {
                return;
            }
            try {
                /*
                 * ⚠️ **用户主动收起后，这里必须彻底不动**（用户报的 bug）。
                 *
                 * 第一版没有这道闸：用户点「收起」销毁了窗口，但本循环 1.5 秒后
                 * 又看到"容器在干活"，于是 `shouldShow → true` 把它**又显示回来** ——
                 * 用户看到的现象就是"关掉了还会自己冒出来，像关不掉"。
                 *
                 * 现在 `BallWindow.enabled()`（= 用户是否允许它出现）是唯一的总闸：
                 * 关掉后自动那条路完全不介入，直到用户点「打开悬浮球」
                 * 或点常驻通知上的按钮（两者都会把它置回 true）。
                 */
                if (!BallWindow.enabled(this)) {
                    snap = null;                 // 下次开启时重新取基准，避免用过期读数
                    activeAt = 0L;
                    continue;
                }
                /*
                 * ① 先算"在不在干活"——**常驻模式和自动模式都要算**。
                 *
                 * ⚠️ 2026-10-21 修的关键 bug：原来这段在 `if (!autoMode) { … continue; }`
                 * **之后**，于是用户一关掉「自动出现」（很多人为了让球一直挂着就会关），
                 * 这个函数就再也走不到下面的 {@link BallWindow#updateStage} ——
                 * 阶段永远停在初始值 IDLE，球的表情也永远不换。
                 * 用户报的"开了随任务阶段换表情但从来不变"就是这么来的。
                 */
                File rootfs = new File(Env.base(this), "rootfs");
                if (sampler == null) {
                    sampler = new TaskWatch.Sampler(rootfs, android.os.Process.myPid());
                }
                boolean busyNow = false;
                boolean heavyNow = false;
                if (snap == null) {
                    // 首次只做基准：没有上一次的读数就算不出增量（下一拍就能算了）
                    snap = sampler.sample(false);
                } else {
                    TaskWatch.Snap now = sampler.sample(false);
                    long dt = now.at - snap.at;
                    busyNow = AutoBall.busy(snap.cpuMs, now.cpuMs, now.procs, dt);
                    // 判阶段用更严格的档位：闲着时的 node 也在烧几十毫秒，不能用它说"在思考"
                    heavyNow = AutoBall.heavyCpu(snap.cpuMs, now.cpuMs, now.procs, dt);
                    activeAt = AutoBall.markActive(activeAt, now.at, busyNow);
                    snap = now;
                }
                boolean cpuBusy = AutoBall.stillActive(activeAt, System.currentTimeMillis());
                /*
                 * ② "在干活" = 容器 CPU 活跃 **或** 网页说它正在生成/跑工具。
                 *
                 * 为什么要或上网页这一路：CPU 采样只是间接证据（等模型吐字时可能不动），
                 * 而网页是**第一现场**——它自己就在渲染"深度求索中"和工具卡片。
                 */
                boolean busy = cpuBusy || pageBusy();
                /*
                 * 阶段用的"在干活"和显示用的 `busy` **故意不一样**：
                 * `busy` 带 8 秒保持窗口（防止球一闪一闪），但**当前这一刻**是否在干活
                 * 只有"网页怎么说 + CPU 有没有连续在烧"说得准。
                 *
                 * 用带保持窗口的那个去判阶段的话，任务结束后会多出几秒的"思考中…"——
                 * 明明已经做完了却说在思考，比不显示更糟（用户盯着气泡就是想看准）。
                 *
                 * 单拍的重活**也不算数**：容器偶尔一次 GC/写盘就会让增量冒一下，
                 * 直接采信的话气泡会"思考中 → 已完成"闪一下（实测日志里 16:45:14 那一次）。
                 * 连续两拍都重才算真的在跑东西 —— 1.5 秒一拍，代价是晚 1.5 秒，
                 * 换来的是一条不抽搐的气泡。
                 */
                if (heavyNow) heavyStreak++; else heavyStreak = 0;
                // 网页说在干活就是干活；网页不说话时才看 CPU（且要连续两拍）
                boolean stageActive = pageBusy() || heavyStreak >= 2;
                boolean justFinished = wasBusy && !stageActive;
                wasBusy = stageActive;

                /*
                 * ③ ★ 喂给悬浮球的"任务阶段"（气泡第一行 + 球的表情）。
                 *
                 * 信号四路，优先级从高到低（判据本身在纯逻辑 {@link BallStage} 里）：
                 *   · 网页报的阶段（pageStage，最准，它就长在对话界面上）
                 *   · stdout 最近一行（有 20 秒时效，陈年启动日志不算数）
                 *   · busy（CPU / 网页两路取或）
                 *   · justFinished（上一拍在跑、这一拍停了 = 已完成）
                 *
                 * 放在这里而不是另起线程：这里本来就是 1.5 秒一拍的心跳，顺手算完最省。
                 */
                try {
                    BallWindow.updateStage(this, getCurrentLine(), getCurrentLineAgeMs(),
                            stageActive, justFinished, getState(),
                            pageStage(), pageStageAge());
                    /*
                     * 气泡正开着时，让它的文案跟着阶段走。
                     * 不刷新的话，用户把气泡放着不动就永远停在第一次打开时的那句话 ——
                     * 而他打开气泡正是为了盯进度。
                     */
                    BallWindow.refreshBubbleIfVisible();
                } catch (Throwable t) {
                    Log.w(TAG, "阶段更新失败（不影响悬浮球其它行为）", t);
                }

                /*
                 * ④ 再决定窗口要不要显示。
                 *
                 * 常驻模式：球应当一直看得见（但设置页/主界面这种"我们自己的界面在前台"
                 * 仍然要让开，那是用户点名不要的）。
                 */
                boolean want;
                if (!BallWindow.autoMode(this)) {
                    want = !Foreground.ours();
                } else {
                    want = AutoBall.shouldShow(true, Foreground.ours(), busy);
                }
                /*
                 * 窗口按需创建：BallWindow.show() 幂等，而且它只是往**本服务**这个
                 * 已经在跑的前台服务里加个窗口 —— 不再有"在后台 startForegroundService
                 * 被系统拒"的问题（那正是它从 Service 改成窗口管理器的原因）。
                 */
                if (want) {
                    if (!BallWindow.isShown()) BallWindow.show(this);
                    if (!BallWindow.isVisible()) BallWindow.reconcile(this, true);
                } else if (BallWindow.isShown()) {
                    BallWindow.reconcile(this, false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "悬浮球看护异常", t);
            }
        }
    }

    // ── 监督循环：进程挂了就重启 ──────────────────────────────
    private void supervise() {
        /** 已经跑了多少轮 5 秒（用来把"每 N 分钟做一次"的活摊进来）。 */
        int ticks = 0;
        while (keepRunning) {
            try {
                if (!isAlive()) {
                    startDsh();
                }
                /*
                 * 每 ~60 轮（= 5 分钟）查一次 App 有没有新版本。
                 *
                 * 为什么放在这个循环里：它已经在每 5 秒跑一次了，顺手加个计数器
                 * 就不用再起一个定时器/服务（多一个后台组件就多一个被系统清掉的理由）。
                 * AppUpdate 自己带 6 小时缓存、而且**同一个版本只推一次通知**，
                 * 所以这里的频率不会变成打扰。
                 *
                 * 为什么放在独立线程：查 GitHub 要走网络、可能卡十几秒 ——
                 * 绝不能堵住监督循环（那会让容器挂了都不重启）。
                 */
                if (++ticks % 60 == 0) {
                    new Thread(() -> {
                        try { AppUpdate.notifyIfNewer(DshService.this); } catch (Throwable ignore) { }
                    }, "app-update-check").start();
                }

                /*
                 * 悬浮球**不放在这个循环里** —— 见下面 {@link #ballLoop()} 的注释：
                 * startDsh() 会阻塞读容器输出流直到进程结束，
                 * 所以这个循环在容器运行期间根本回不到顶部。
                 */
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "supervise error", t);
                setState("异常: " + t.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /**
     * 处理"用户改了设置"导致的重启请求。
     *
     * 为什么必须是独立线程：startDsh() 内部会**阻塞读取 dsh 的输出流**直到进程结束，
     * 所以 supervise() 在 dsh 运行期间根本回不到循环顶部去检查重启标记。
     * （这是实际踩过的坑：改完 API Key 点保存，界面显示"正在重启"但容器纹丝不动。）
     *
     * 这里主动杀掉进程，读取线程随之 EOF、startDsh() 返回，
     * supervise() 就会用新的 API Key 重新拉起容器。
     */
    private void watchRestart() {
        while (keepRunning) {
            try {
                if (sRestartRequested) {
                    sRestartRequested = false;
                    Log.i(TAG, "收到重启请求，杀掉当前 dsh 进程");
                    setState("正在重启…");
                    updateNotification("正在按新设置重启…");
                    Process p = sProcess;
                    /*
                     * sUrl 必须在 destroy **之前**清掉。
                     * 否则会和 supervise() 里刚起来的 dsh 抢时序：
                     * 新进程已经打印了 URL、sUrl 刚被赋值，这里再清一次就把它抹掉了，
                     * 而 dsh 只在启动时打印一次 URL —— 结果是 awaitUrl 永远等不到。
                     */
                    sUrl = null;
                    if (p != null) {
                        p.destroy();
                        Thread.sleep(800);
                        /*
                         * 只有**确实杀过进程**才去扫残留。
                         * 如果 p 为 null（本来就没进程在跑，例如首次初始化的重启请求），
                         * 这里扫 /proc 会把 supervise() 刚拉起来的 dsh 一起杀掉 ——
                         * 表现就是无限重启。
                         * 启动前的清理已经在 startDsh() 里做过，这里是多余的。
                         */
                        int n = Env.killStaleDsh(Env.base(this));
                        Log.i(TAG, "清理残留进程: " + n + " 个");
                    }
                }
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.e(TAG, "watchRestart error", t);
            }
        }
    }

    private void startDsh() {
        try {
            File base = Env.base(this);
            if (!Env.isInstalled(base)) {
                sNeedsProvision = true;
                setState("容器未安装完成");
                updateNotification("容器尚未安装完成，请打开 App 完成初始化");
                Thread.sleep(10000);
                return;
            }
            sNeedsProvision = false;
            /*
             * 先挑一个**没被占用**的端口。3080 很容易撞车，
             * 而端口被占时 dsh 会直接起不来、监督循环无限重启 ——
             * 用户看到的现象和"卡死"完全一样。
             */
            int port = Env.pickFreePort(Env.DSH_PORT, Env.DSH_PORT + 10);
            Env.setPort(port);
            if (port != Env.DSH_PORT) {
                appendReport("  · 默认端口 " + Env.DSH_PORT + " 被占用，改用 " + port + "\n");
                Log.w(TAG, "端口 " + Env.DSH_PORT + " 被占用，改用 " + port);
            } else {
                appendReport("  · 使用端口 127.0.0.1:" + port + "\n");
            }
            setState("正在启动 dsh web…");
            updateNotification("正在启动 DSH…");

            /*
             * 启动计时（2026-10-21，用户报"插件装多了启动特别慢"）。
             *
             * 以前只知道一个总耗时，不知道慢在哪一段 —— 是解包自带插件、
             * 还是容器里 node 加载插件。埋上之后 report.txt 会逐年段写出毫秒数，
             * 用户把日志发过来就能定位，不用再猜。
             */
            final long tBoot = System.currentTimeMillis();

            /*
             * 先把「桌宠」插件摘掉（2026-10-21）：它的显示层已经被删了，
             * 留在容器里只会每 100~200ms 写一次请求文件穿 proot，纯烧电。
             * 幂等：摘干净之后这里每次都返回 null。
             */
            try {
                String petMsg = Env.disablePetPlugin(base);
                if (petMsg != null) {
                    Log.i(TAG, petMsg);
                    appendReport("  · " + petMsg + "\n");
                }
            } catch (Throwable t) {
                Log.w(TAG, "摘桌宠插件异常", t);
            }

            // 先准备好共享存储挂载点与测试文件夹（工作区）
            String ws = Env.ensureStorageMounts(base);
            Log.i(TAG, "工作区: " + Env.WORKSPACE + " · 测试文件夹: " + ws);
            appendReport("  · [启动] 挂载点/工作区 " + (System.currentTimeMillis() - tBoot) + "ms\n");

            // 手机控制：启动回环控制桥（token 目录随后只读绑定进容器），
            // 并把 phone CLI 与 AGENTS.md 装进容器/工作区
            PhoneBridge.start(this);
            String tokenDir = PhoneBridge.tokenDir(this);
            Env.installPhoneTools(this, base);
            Log.i(TAG, "手机控制桥 token 目录: " + tokenDir
                    + " · 白名单 " + PhoneBridge.allowed(this).size() + " 个应用");
            appendReport("  · [启动] 手机控制桥 " + (System.currentTimeMillis() - tBoot) + "ms\n");

            String libDir = Env.prepareProot(this, base);
            String apiKey = getApiKey(this);
            /*
             * 把 Key 的**形态**记进报告（不含密钥本身）。
             *
             * 为什么要留这一行：用户报"用久了 API 掉了""重装就好了"时，
             * 以前日志里只有一个"已设置/未设置"，根本看不出 Key 是被截断了、
             * 还是带了不可见字符。现在一眼能看长度和掩码，再配上
             * {@link ApiKey#plausible} 的形态警告，同类问题不用再猜。
             */
            appendReport("  · API Key：" + ApiKey.describe(apiKey) + "\n");
            appendReport("  · [启动] proot 就绪 " + (System.currentTimeMillis() - tBoot) + "ms\n");

            /*
             * 装随 APK 自带的 DSH 插件（phone 工具集）。
             *
             * 放在起 dsh 之前：插件是 bundle，要靠 profile 启动时加载 ——
             * 起完再装的话本次会话看不到那些工具，用户会以为没生效。
             * 幂等（已装过就跳过一次文件读），所以只在首次/升级后跑一次。
             * 失败也不阻断启动：phone CLI 那条路还在，功能不会全丢。
             */
            try {
                String pluginLog = Env.installPhonePlugin(this, base, tokenDir, libDir);
                Log.i(TAG, pluginLog);
                appendReport("  " + pluginLog + "\n");
                // 插件市场的前置条件：容器里得有 pnpm（dsh plugin add 内部要用）
                String pnpmLog = Env.ensurePnpm(base, tokenDir, libDir);
                Log.i(TAG, pnpmLog);
                appendReport("  " + pnpmLog + "\n");
            } catch (Throwable t) {
                Log.w(TAG, "装自带插件异常", t);
            }
            appendReport("  · [启动] 自带插件+pnpm " + (System.currentTimeMillis() - tBoot) + "ms\n");

            /*
             * 中转站（自定义提供方）的密钥作为环境变量进容器。
             *
             * 为什么在这里注入而不是写进 settings.yaml：DSH 的 llm-pi-ai 用
             * `apiKeyEnv: ACME_GATEWAY_API_KEY` 这种**凭据引用**，配置文件里不放密钥；
             * 而容器命令是 `env -i`（清空环境），所以必须显式带到命令行上。
             * 好处是密钥始终只在这台手机上（App 私有目录 + 启动参数），
             * 容器里、容器写出来的文件里都搜不到它。
             */
            java.util.List<String> relayEnv = Providers.envPairs(this);
            if (!relayEnv.isEmpty()) {
                appendReport("  中转站密钥注入 " + relayEnv.size() + " 个（"
                        + Providers.load(this).size() + " 个自定义提供方）\n");
            }
            String[] cmd = Env.dshWebCommand(base, apiKey, tokenDir, relayEnv);

            // 起新的之前先把可能残留的旧容器进程清掉（否则 3080 端口会被占住）
            int stale = Env.killStaleDsh(base);
            if (stale > 0) Log.i(TAG, "启动前清理残留进程 " + stale + " 个");

            sUrl = null;
            Process p = Env.buildProcess(base, cmd, libDir).start();
            sProcess = p;
            Log.i(TAG, "dsh 进程已启动, apiKey=" + (apiKey.isEmpty() ? "未设置" : "已设置"));
            appendReport("  · [启动] 拉起 dsh 进程 " + (System.currentTimeMillis() - tBoot)
                    + "ms（之后是容器里 node 加载插件的时间）\n");
            updateNotification(apiKey.isEmpty()
                    ? "运行中（未设置 API Key）"
                    : "运行中（已设置 API Key）");

            // 持续读取输出：解析带 token 的 URL，同时**保留最近若干行**用于诊断
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = br.readLine()) != null) {
                    n++;
                    synchronized (sRecent) {
                        sRecent.addLast(line);
                        sLastLineAt = System.currentTimeMillis();
                        while (sRecent.size() > RECENT_MAX) sRecent.removeFirst();
                    }
                    // 头几行 + 任何像报错的行都写进报告
                    if (n <= 30 || looksLikeError(line)) {
                        appendReport("    dsh| " + line + "\n");
                    }
                    if (sUrl == null) {
                        /*
                         * 只认 DSH **自己**的地址 —— 见 {@link UrlPick} 的类注释。
                         *
                         * 【真实事故】装了第三方插件（billion-context）之后，那个插件
                         * 在自己的端口上起了个面板服务，并把地址打进了 dsh 的输出；
                         * 旧逻辑"抓第一个 127.0.0.1 地址"于是抓到插件那个端口，
                         * 整个界面被加载成插件的网页 —— 用户看到的就是"点开网页退不出去"。
                         *
                         * 现在要求地址带鉴权 token（DSH 的形态），插件/本地代理的
                         * 裸端口一律不认；另外顺手把"看到的外来回环地址"记进报告，
                         * 下次再有这种事，日志里直接能看出来。
                         */
                        String foreign = UrlPick.pickForeignLoopback(line);
                        if (foreign != null && foreignReported.add(foreign)) {
                            appendReport("  · 输出里还有别的本地服务（不是 DSH，已忽略）："
                                    + foreign + "\n");
                            Log.i(TAG, "忽略非 DSH 的本机地址: " + foreign);
                        }
                        String picked = UrlPick.pickDshUrl(line);
                        if (picked != null) {
                            sUrl = picked;
                            setState("运行中 · " + hostPortOf(sUrl));
                            appendReport("  dsh 就绪: " + sUrl + "\n");
                            /*
                             * 顺手把"配的是哪个模型 provider"记进报告。
                             *
                             * 起因：用户报"压缩上下文时 gateway/internal: … imageRequestPricing
                             * is not a function"。那个错只跟 provider 的适配器有关
                             * （见 Env.modelRoutes 的注释与 CHANGELOG v0.2.8.3），
                             * 所以这一行是定位它的关键线索；只记 provider/model，不碰凭据。
                             */
                            appendReport("  · 模型路由：" + Env.modelRoutes(this) + "\n");
                            Log.i(TAG, "就绪: " + sUrl);
                            /*
                             * 容器活了才去拉常驻服务。
                             *
                             * 顺序很重要：服务（比如本地代理）要在容器**已经可用**之后
                             * 才起得来；而且它得跑在 dsh web 之前或同时，
                             * 否则"客户端先连、代理还没起来"又会回到用户抱怨的那个绕法。
                             */
                            Services.startEnabled(DshService.this);
                            Services.startWatchdog(DshService.this);
                            /*
                             * 内核版本检测：**不占启动路径**，另起一个后台线程。
                             * 它要联网查 npm，快则几百毫秒、慢则几秒；
                             * 卡在这儿没有意义 —— 界面早就该出来了。
                             */
                            new Thread(() -> {
                                try {
                                    String cur = KernelUpgrade.installedVersion(DshService.this);
                                    String up = KernelUpgrade.availableUpdate(DshService.this);
                                    if (up != null) {
                                        appendReport("  ⬆ 内核有新版本: " + up
                                                + "（当前 " + cur + "）—— 设置 →「内核升级（本机）」可一键升级\n");
                                        KernelUpgrade.notifyUpdate(DshService.this, up);
                                    } else if (cur != null) {
                                        appendReport("  内核已是最新（" + cur + "）\n");
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "检查内核版本失败", t);
                                }
                            }, "dsh-version-check").start();
                        }
                    }
                }
            }
            int code = p.waitFor();
            Log.w(TAG, "dsh 进程退出, code=" + code);
            appendReport("  ❌ dsh 进程退出 code=" + code + "\n" + getRecentOutput());
            setState("进程已退出 (" + code + ")，将自动重启");
            updateNotification("DSH 已退出，正在重启…");
        } catch (Throwable t) {
            Log.e(TAG, "startDsh failed", t);
            setState("启动失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            updateNotification("DSH 启动失败，详见 App 内日志");
        }
    }

    private void setState(String s) { sState = s; }

    // ── 通知 ──────────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "DSH 运行状态", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持 DSH 容器在后台运行");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    /**
     * 通知右侧的大图 = 当前配色的桌面图标。
     *
     * <p>用 {@link android.graphics.drawable.Icon} 而不是 BitmapFactory：
     * v26+ 的桌面图标是**自适应图标 XML**，BitmapFactory 解不出来（返回 null），
     * 用 Icon 才能正确渲染。
     */
    private android.graphics.drawable.Icon largeIcon() {
        try {
            int res = IconSwitcher.iconRes(this);
            if (res == 0) return null;
            return android.graphics.drawable.Icon.createWithResource(this, res);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        /*
         * 点通知栏「正在运行」要回到**原来那个** MainActivity，不能开新的。
         *
         * 2026-10-15（GitHub issue #3）：原来这里只给了 MainActivity.class、没带任何
         * ACTIVITY 标志，配上 manifest 里也没有 launchMode → 每次点通知都新建一个实例，
         * 新实例的 WebView 全新加载、显示空白会话页，用户以为会话被清空。
         *
         * 现在：manifest 已改成 singleTask（保证只有一份）；这里再补
         * CLEAR_TOP + SINGLE_TOP 双保险 —— 即使有人把 manifest 改回去，
         * 点通知也只会把已有实例提前，不会叠出第二个。
         */
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        /*
         * 通知上的快捷动作（用户要求"在其他软件时也能用 DSH"）：
         * 常驻通知是 App 在后台时**唯一**一直可见的入口，
         * 把「悬浮球」放这里，就不用先切回 App 再点。
         *
         * 用广播而不是 getService()：悬浮球现在**不是 Service 了**（改成由这个
         * 已经在跑的前台服务直接持有窗口 —— 见 BallWindow 类注释里那段
         * "在后台 startForegroundService 会被系统拒"的踩坑记录）。
         */
        PendingIntent overlay = PendingIntent.getBroadcast(this, 2,
                new Intent(this, DshService.class).setAction(ACTION_BALL_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Tdsh")
                .setContentText(text)
                /*
                 * 通知图标跟随用户在「桌面图标」里选的那套配色：
                 *   setSmallIcon 用小图标（系统会按强调色着色）
                 *   setColor     决定强调色 —— 小图标和应用名都会被它染色
                 *   setLargeIcon 右侧那张大图，直接用该配色的桌面图标
                 */
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setColor(IconSwitcher.accentColor(this))
                /*
                 * 大图 = 当前配色的桌面图标。
                 * 光靠 setColor 只能给小图标和标题染色，用户看不到"图"本身变了；
                 * 加上 largeIcon 之后，通知右侧那张方图就是桌面图标那张 ——
                 * 这才叫"通知栏里的图片也跟着换"。
                 */
                .setLargeIcon(largeIcon())
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view, "悬浮窗", overlay).build())
                .setOngoing(true)
                .build();
    }

    /**
     * 用户把 App 从最近任务里划掉时**不跟着死**（用户要求"后台更稳定"）。
     *
     * <p>默认行为是：任务被移除 → 服务被杀。对 DSH 这种"后台常驻跑容器"的定位
     * 明显不合适 —— 用户划掉卡片只是"不想看界面"，不是"把容器关掉"。
     * 这里配合清单里的 {@code android:stopWithTask="false"}，
     * 让进程与容器继续活着，回到 App 时界面直接接上。
     *
     * <p>注意：这**不是**黑科技保活。用户在系统设置里"强行停止"之后，
     * 任何合规手段都无法自动复活 —— 那种需求本项目不做
     * （1px Activity / 静音音频 / 双进程互拉 一类都是要被系统打死的）。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "任务被移除，但服务继续运行（容器不受影响）");
        appendReport("  · 用户把 App 从最近任务划掉 —— 容器继续在后台运行\n");
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification failed", t);
        }
    }
}
