package com.dshmobile.probe;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DSH 悬浮球 —— **一个纯窗口管理器，不是 Service**。
 *
 * <h3>为什么从 Service 改成"由 DshService 直接持有"（这轮的关键修复）</h3>
 * 原来的写法是"一个 OverlayService extends Service"，需要时用
 * {@code startForegroundService()} 把它拉起来。实测（2026-10-05）**它从来没起来过** ——
 * `dumpsys activity services` 里始终只有 DshService/DshAccessibilityService，
 * 所以悬浮球一直不显示。
 *
 * <p>根因是 Android 12+ 的**后台启动前台服务限制**：用户退到后台之后我们才想显示球，
 * 而那一刻 App 正处于后台 → {@code startForegroundService} 会被系统直接拒掉
 * （{@code ForegroundServiceStartNotAllowedException}）。
 * 换句话说，**"等需要时才把 Service 拉起来"这个思路本身就是错的**：
 * 越需要它的时候，越不允许启动。
 *
 * <p>而 {@link DshService} 本来就是一个**常驻前台服务**（只要 App 活着它就在，
 * 因为容器要它看着）。所以正确的做法是：**让已经在跑的那个前台服务直接持有这个窗口**，
 * 不再引入第二个服务 —— 少一个组件、少一个被系统拒绝/回收的理由。
 *
 * <h3>三条 Android 硬约束（都是踩过的，改之前先读）</h3>
 * <ol>
 *   <li>整屏 WebView 那个窗口**绝不能加 {@code FLAG_NOT_FOCUSABLE}** —— 加了以后它永远
 *       不会成为输入法目标，DSH 的输入框就再也弹不出软键盘（只能看不能打字）。</li>
 *   <li>整屏窗口**必须整体不透明**（Android 12 起 overlay 属"不可信窗口"，
 *       合成遮挡不透明度 &gt; 0.8 时触摸会被丢弃）。球/气泡尺寸很小，可以用半透明，
 *       但**必须让开顶部那条系统手势区**（否则通知栏拉不出来）。</li>
 *   <li>**加窗时必须有一个前台服务在跑** —— 由 DshService 调用即满足。</li>
 * </ol>
 * 另外：**不要给窗口加 {@code FLAG_LAYOUT_NO_LIMITS}** —— 它会让浮层覆盖到状态栏区域，
 * 系统就认为顶部手势区被遮挡，**从顶部下滑调不出通知栏**；而且拖动能把窗口拖出屏幕。
 */
public final class BallWindow {

    private static final String TAG = "DSH_BALL";

    /** 三种形态。 */
    private static final int MODE_DOT = 0;     // 小圆球
    private static final int MODE_BUBBLE = 1;  // 气泡（状态 + 提示）
    private static final int MODE_FULL = 2;    // 整屏 DSH 界面

    private static volatile BallWindow sInstance;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private int mode = MODE_DOT;

    private View dotView;
    private BallView ballView;
    private LinearLayout bubbleView;
    private TextView bubbleText;
    private LinearLayout fullView;
    /** 整屏界面的容器（WebView 延迟到真正要用时才建，见 buildFull 注释）。 */
    private LinearLayout fullBox;
    private WebView web;

    /** 悬浮球自己的屏幕坐标（px）。见 {@link #applyMode} 里那段说明。 */
    private int ballX = -1, ballY = -1;
    private volatile boolean visible = true;

    /**
     * 气泡在球的哪一侧：{@code true} = 气泡在球**左边**（尾巴朝右指向球）。
     *
     * <p>由球所在屏幕半区决定：球在右半屏 → 气泡放左边；球在左半屏 → 气泡放右边。
     * 这样气泡永远不会顶出屏幕，尾巴也永远朝着球。
     */
    private boolean bubbleOnLeft = true;

    /** 球与气泡在窗口内的布局参数（切边时要改 gravity，所以要留引用）。 */
    private FrameLayout.LayoutParams dotLp, bubLp;

    private BallWindow(Context c) {
        this.app = c.getApplicationContext();
    }

    // ─────────────────────────── 对外 API ───────────────────────────

    public static boolean isShown() {
        BallWindow b = sInstance;
        return b != null && b.root != null;
    }

    /** 球当前是不是**真的看得见**（窗口存在但可能被自动隐藏）。 */
    public static boolean isVisible() {
        BallWindow b = sInstance;
        return b != null && b.visible;
    }

    /**
     * 加上悬浮球窗口（幂等）。
     *
     * <p>必须在**有前台服务在跑**的时候调用（由 {@link DshService} 调）——
     * 这也是它不再是个 Service 的原因，见类注释。
     */
    public static synchronized void show(Context c) {
        setEnabled(c, true);        // 用户/通知栏主动打开 → 记成"开启"
        if (sInstance != null) {
            sInstance.attachIfNeeded();
            return;
        }
        BallWindow b = new BallWindow(c);
        sInstance = b;
        b.ui.post(b::attachIfNeeded);
    }

    /**
     * 收起悬浮球（销毁窗口）—— **并记住"是用户主动关的"**。
     *
     * <h3>为什么要单独记一个 enabled（用户报的 bug）</h3>
     * 第一版只做了"窗口销毁"，但自动出现那条链路（{@link DshService#ballLoop}）
     * 每 1.5 秒就会看到"容器在干活"并 `shouldShow → true`，于是**立刻又把它显示回来** ——
     * 用户点了"收起"，球过一两秒自己又冒出来，感觉像关不掉。
     *
     * <p>所以：主动收起 = 把 `overlay_on` 置为 false；
     * 自动那条路**只在这个开关为 true 时才允许显示**。
     * 想再打开就点界面上的「打开悬浮球」，或点常驻通知上的按钮。
     */
    public static synchronized void hide(Context c) {
        setEnabled(c, false);
        BallWindow b = sInstance;
        if (b == null) return;
        sInstance = null;
        b.ui.post(b::detach);
    }

    /**
     * 只把窗口拆掉、**不改用户开关**。
     *
     * <p>给 {@code DshService.onDestroy()} 这类场景用：服务要没了，窗口当然得跟着拆，
     * 但"用户是否允许悬浮球出现"是**用户的意愿**，不该由服务生死来改写 ——
     * 用 hide() 会让用户下次打开 App 时发现球不自动出现了，而他从没关过它。
     */
    public static synchronized void detachQuietly() {
        BallWindow b = sInstance;
        if (b == null) return;
        sInstance = null;
        b.ui.post(b::detach);
    }

    /** 用户是否允许悬浮球出现（界面上的「打开/收起」和自动出现都以它为准）。 */
    public static boolean enabled(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ON, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(Context c, boolean on) {
        try {
            c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ON, on).apply();
        } catch (Throwable ignore) { }
    }

    /** 按判据设置可见性（窗口保持存在）。 */
    public static void reconcile(Context c, boolean want) {
        BallWindow b = sInstance;
        if (b == null) return;
        b.ui.post(() -> b.applyVisibility(want));
    }

    /** 换图标立刻生效（不用重启）。清缓存，让下次用新图。 */
    public static void refreshBallIcon(Context c) {
        BallWindow b = sInstance;
        if (b == null) return;
        b.ui.post(() -> {
            b.iconCache = null;
            b.iconCachedId = null;
            b.applyBallIcon();
        });
    }

    /** 有没有悬浮窗权限（Android 6+ 是 appop，不能 requestPermissions，只能跳设置页）。 */
    public static boolean canDraw(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try { return Settings.canDrawOverlays(c); } catch (Throwable t) { return false; }
    }

    public static void requestPermission(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(c, "打不开授权页：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /** 界面用的一句话状态。 */
    public static String statusText(Context c) {
        if (isShown()) {
            return isVisible() ? "已开启（球浮在其他应用上层）" : "已开启（当前自动隐藏中）";
        }
        if (!canDraw(c)) return "没权限 —— 需要「显示在其他应用上层」";
        return enabled(c) ? "已开启（当前没显示，等有任务时自动出现）" : "已关闭";
    }

    // ─────────────────── 任务阶段（气泡第一行）───────────────────

    /** 阶段文案的设置项键（`THINKING=让我想想|WORKING=干活中`，可扩展）。 */
    static final String KEY_STAGE_MAP = "ball_stage_map";

    /** 最近一次算出的阶段与文案（由 ballLoop 线程写、UI 线程读，所以用 volatile）。 */
    private static volatile int stageNow = BallStage.IDLE;
    private static volatile String stageTextNow = null;
    private static volatile long stageAt = 0L;
    private static final BallStage.Throttle stageThrottle = new BallStage.Throttle();
    /** 被节流掉的阶段（等下一拍再补上），避免"阶段变了却没显示"。 */
    private static volatile int stagePending = -1;
    /** 当前阶段是什么时候换上的 —— "最短驻留"去抖用。 */
    private static volatile long stageChangedAt = 0L;

    /**
     * 同一阶段最短驻留多久才允许换下一阶段（毫秒）。
     *
     * <p>这是「气泡空转」的第二道防线：终态粘性（{@link BallStage#POST_DONE_HOLD_MS}）
     * 管的是"只有 CPU 在抖时不许翻回思考中"，这一道管的是"就算所有信号都在抖，
     * 也不许两秒内翻两次"。2.5 秒 ≈ 一次心跳多一点，人眼刚好不会觉得在抽。
     *
     * <p>「出错」不受这条限制：报错必须立刻出来，晚两秒就可能被用户当成没报错。
     */
    static final long MIN_DWELL_MS = 2_500L;

    /** 套上最短驻留去抖，返回这一拍**真正该显示**的阶段。 */
    private static int dwell(int raw, long now) {
        if (raw == stageNow) return raw;
        if (raw == BallStage.ERROR) return raw;
        if (stageChangedAt > 0L && now - stageChangedAt < MIN_DWELL_MS) return stageNow;
        return raw;
    }

    /**
     * 长时间没有新进展就**自动隐藏阶段行**（用户要求"长时间无更新自动隐藏"）。
     *
     * <p>为什么不隐藏整条气泡：气泡里还有 DSH 状态与操作提示（点一次收起等），
     * 那些是常驻有用的。只把"阶段"这一行撤掉，既满足"别一直挂着同样的字"，
     * 又不影响既有交互。
     */
    private static final long STAGE_TTL_MS = 25000L;

    /**
     * 由 {@link DshService#ballLoop()} 每拍（1.5 秒）调用一次 —— 喂入最新信号，算出阶段。
     *
     * <p>三件事都在这一个入口里做掉，避免散落：
     * <ol>
     *   <li><b>判定</b>：交给纯逻辑 {@link BallStage#detect}（可离线回归）；</li>
     *   <li><b>去重 + 节流</b>：{@link BallStage.Throttle}，阶段没变就不刷、明细高频更新不闪；</li>
     *   <li><b>留痕</b>：判定/刷新都写进 {@code __dshRailDiag} 之外的独立日志（见下），
     *       异常绝不静默吞掉（这一条是上一轮踩坑后的硬规矩）。</li>
     * </ol>
     *
     * @param lastLine     DSH stdout 最近一行
     * @param taskActive   是否有任务在跑
     * @param justFinished 任务刚刚结束
     * @param serviceState 服务状态字符串
     */
    public static void updateStage(Context c, String lastLine, long lineAgeMs,
                                  boolean taskActive, boolean justFinished, String serviceState,
                                  int webStage, long webAgeMs, boolean pageBusy,
                                  BallStage.Memo memo, int cpuPct) {
        try {
            long now = System.currentTimeMillis();
            int raw = BallStage.detect(lastLine, lineAgeMs, taskActive, justFinished,
                    serviceState, webStage, webAgeMs, pageBusy, memo, now);
            int stage = dwell(raw, now);
            String custom = null;
            try {
                custom = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_STAGE_MAP, null);
            } catch (Throwable t) {
                // 读配置失败不该影响显示：退回内置文案，但要留痕
                android.util.Log.w("DSH_BALL", "读阶段文案配置失败，用内置表", t);
            }
            String text = BallStage.text(stage, custom);
            if (stageThrottle.shouldUpdate(stage, text, now)) {
                boolean changed = stage != stageNow;
                int from = stageNow;
                stageNow = stage;
                stageTextNow = text;
                stageAt = now;
                if (changed) stageChangedAt = now;
                stagePending = -1;
                /*
                 * 阶段变化的日志用**独立 tag**（GitHub issue 里点名要的 FloatBallStage）：
                 * 用户遇到"球不换表情"时，一条 `adb logcat -s FloatBallStage` 就能看清
                 * 到底是"没判出来"还是"判出来了但没画"。
                 * 只在**真的变了**时才打，避免 1.5 秒一拍的日志刷满缓冲区。
                 */
                if (changed) {
                    android.util.Log.i("FloatBallStage", BallStage.name(from) + " → "
                            + BallStage.name(stage) + " / " + text
                            + "（原始=" + BallStage.name(raw) + "，网页="
                            + (webStage < 0 ? "无" : BallStage.name(webStage))
                            + "，" + (webAgeMs == Long.MAX_VALUE ? "∞" : webAgeMs + "ms")
                            + "；输出行龄=" + (lineAgeMs == Long.MAX_VALUE ? "∞" : lineAgeMs + "ms")
                            + "；有任务=" + taskActive + "（网页忙=" + pageBusy
                            + "，CPU=" + cpuPct + "%））");
                    trace(BallStage.name(from) + " → " + BallStage.name(stage)
                            + " / " + text
                            + "  原始=" + BallStage.name(raw)
                            + "  网页=" + (webStage < 0 ? "无" : BallStage.name(webStage))
                            + "@" + (webAgeMs == Long.MAX_VALUE ? "∞" : webAgeMs + "ms")
                            + " 输出行龄=" + (lineAgeMs == Long.MAX_VALUE ? "∞" : lineAgeMs + "ms")
                            + " 有任务=" + taskActive + "（网页忙=" + pageBusy
                            + " CPU=" + cpuPct + "%）");
                } else {
                    android.util.Log.d("FloatBallStage", "保持 " + BallStage.name(stage)
                            + " / " + text);
                }
                /*
                 * 阶段**真的变了** → 自动把气泡弹出来几秒。
                 * 用户的要求：「到下一步时、任务开始时自动弹气泡提示（正在思考怎么做等等）」。
                 * 注意判据是 changed 而不是"每次刷新"：否则气泡会被 1.5 秒一拍的心跳
                 * 反复顶出来，变成永远收不掉的常驻框。
                 */
                if (changed) {
                    autoPopBubble();
                    // 顺带把球的表情换成这个阶段的（带放大→旋转→缩回的过渡）
                    onStageChanged(stage);
                }
            } else {
                /*
                 * 被节流了 —— 记下待显示的阶段，等下一拍补上。
                 * 不这么做的话，一次短暂阶段（例如一闪而过的"正在编译文件…"）
                 * 会被节流窗口整个吃掉，用户永远看不到它。
                 */
                if (stage != stageNow) stagePending = stage;
            }
        } catch (Throwable t) {
            // 绝不再静默吞异常（上一轮就是因为 catch{} 把 ReferenceError 藏了，
            // 导致排查多花了好几轮）。这里既打日志，也把错误阶段显示出来。
            android.util.Log.e("DSH_BALL", "阶段判定异常", t);
            stageNow = BallStage.ERROR;
            stageTextNow = "阶段判定异常：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            stageAt = System.currentTimeMillis();
        }
    }

    /**
     * 把一行诊断写进共享存储的 {@code /sdcard/.dsh-phone/ball-stage.log}。
     *
     * <h3>为什么光有 logcat 不够</h3>
     * 部分国产 ROM（本机 vivo / Android 16 就是）**不把正式包的 Java 日志放进 logcat**：
     * {@code adb logcat --pid=<应用 pid>} 一条都收不到，logcat 里只有内核 audit 和
     * 子进程（proot/node）的 stderr。所以"加个日志"在那样的机器上等于没加 ——
     * 用户报"球不换表情"时我们依然什么都看不见。
     *
     * <p>写文件最稳，而且用户可以自己把这个文件发出来。超过 64KB 自动重来，
     * 免得长期使用把存储写满。
     */
    static void trace(String line) {
        synchronized (TRACE_LOCK) {
            try {
                java.io.File dir = new java.io.File("/sdcard/.dsh-phone");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, "ball-stage.log");
                if (f.length() > 64 * 1024) f.delete();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
                String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                        java.util.Locale.ROOT).format(new java.util.Date());
                fo.write((ts + "  " + line + "\n").getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignore) {
                // 没有存储权限之类 → 静默放弃。诊断日志绝不该影响悬浮球本身
            }
        }
    }

    private static final Object TRACE_LOCK = new Object();

    /** 气泡第一行要显示的阶段文案；超过 TTL 没更新则返回 null（自动隐藏）。 */
    private static String currentStageText() {
        // 先把被节流顶掉的阶段补上（下一拍生效）
        if (stagePending >= 0) {
            stageNow = stagePending;
            stageTextNow = null;   // 文案让下面按新阶段重算
            stagePending = -1;
        }
        String t = stageTextNow;
        if (t == null) {
            try {
                t = BallStage.text(stageNow, null);
            } catch (Throwable e) {
                t = BallStage.name(stageNow);
            }
        }
        long age = System.currentTimeMillis() - stageAt;
        if (stageAt > 0 && age > STAGE_TTL_MS) return null;   // 长时间无更新 → 自动隐藏
        return t;
    }

    /**
     * 气泡**正开着**时，让它的文案跟着阶段刷新。
     *
     * <h3>为什么需要单独一个入口</h3>
     * 原来气泡文案只在 {@link #applyMode} 里设置（即"切到气泡形态"那一刻）。
     * 于是出现这个缺口：用户把气泡打开后**放着不动**，阶段从"思考中…"变成
     * "正在编译文件…"也不会更新 —— 而他正是想盯着看进度，这等于功能没生效。
     *
     * <p>由 {@link DshService#ballLoop()} 在阶段刷新后调用（它持的是单例）；
     * 不在气泡形态时立刻返回，所以没有额外开销，也不碰拖动/点击等既有交互。
     */
    private void refreshBubbleText() {
        try {
            if (mode != MODE_BUBBLE) return;
            final TextView tv = bubbleText;
            if (tv == null) return;
            final String s = statusLine();
            // setText 必须在主线程（调用方 ballLoop 是后台线程）
            tv.post(() -> {
                try {
                    if (bubbleText != null) bubbleText.setText(s);
                } catch (Throwable ignore) { }
            });
        } catch (Throwable t) {
            android.util.Log.w("DSH_BALL", "刷新气泡文案失败", t);
        }
    }

    /** 供后台线程使用的入口：没有实例时安全返回。 */
    static void refreshBubbleIfVisible() {
        BallWindow b = sInstance;
        if (b == null) return;
        b.refreshBubbleText();
    }

    // ─────────────────── 阶段变化时自动弹气泡 ───────────────────

    /** 自动弹出的气泡挂多久自动收起。够看清一句"正在思考怎么做"，又不至于挡视线。 */
    private static final long AUTOPOP_MS = 6000L;

    private final Runnable autoCollapse = () -> {
        try {
            if (mode == MODE_BUBBLE) applyMode(MODE_DOT, true);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "自动收起气泡失败", t);
        }
    };

    /**
     * 阶段变了 → 把气泡弹出来几秒（由 {@link #updateStage} 触发）。
     *
     * <p>三条"不弹"的规矩，都是为了不打扰用户：
     * <ol>
     *   <li>球本身没显示（用户收起了悬浮球）→ 不弹；</li>
     *   <li>用户正在看整屏界面 → 不弹（那是他主动打开的，别抢）；</li>
     *   <li>气泡已经开着 → 不重复弹，只把自动收起的计时往后推。</li>
     * </ol>
     */
    static void autoPopBubble() {
        BallWindow b = sInstance;
        if (b == null) return;
        b.ui.post(b::autoPopOnUi);
    }

    private void autoPopOnUi() {
        try {
            if (!visible) return;
            if (mode == MODE_FULL) return;
            if (mode != MODE_BUBBLE) applyMode(MODE_BUBBLE, true);
            ui.removeCallbacks(autoCollapse);
            ui.postDelayed(autoCollapse, AUTOPOP_MS);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "自动弹出气泡失败", t);
        }
    }

    // ─────────────────── 随任务阶段换表情 ───────────────────

    /**
     * 「随任务阶段自动换表情」开关（用户要求："悬浮的表情包根据任务的不同阶段变换"）。
     *
     * <p>默认**开**。关掉之后球就一直用「悬浮球设置 → 换个图标」里手动选的那张。
     * 这一点很关键：不能因为有了自动换脸就把用户的选择顶掉 —— 所以做成开关，
     * 而不是硬编码优先级。
     */
    static final String KEY_AUTO_FACE = "overlay_ball_auto_face";

    static boolean autoFace(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_FACE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setAutoFace(Context c, boolean on) {
        c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_FACE, on).apply();
        if (!on) {
            BallWindow b = sInstance;
            if (b != null) b.ui.post(() -> {
                b.stageFaceId = null;      // 回到用户手动选的那张
                b.applyBallIcon();
            });
        }
    }

    /**
     * 阶段 → 表情包编号。
     *
     * <p>映射刻意选"情绪对得上"的，而不是随便配：
     * 空闲睡觉、起步抱东西、琢磨时无语（省略号）、编译慢叹气、
     * 干活时兴奋举手、做完了害羞、出错大哭。
     */
    private static String faceForStage(int stage) {
        switch (stage) {
            case BallStage.IDLE:      return "s01";   // 睡觉
            case BallStage.BOOT:      return "s05";   // 抱鲸鱼（抱着东西起步）
            case BallStage.THINKING:  return "s06";   // 无语（省略号，像在琢磨）
            case BallStage.COMPILING: return "s09";   // 叹气（编译慢）
            case BallStage.WORKING:   return "s07";   // 开心举手（干活中）
            case BallStage.DONE:      return "s08";   // 害羞（做完了）
            case BallStage.ERROR:     return "s02";   // 大哭
            default:                  return "s03";   // 委屈（兜底）
        }
    }

    /** 当前因为"阶段"而显示的那张（null = 用用户手动选的）。 */
    private volatile String stageFaceId;

    /** 由 {@link #updateStage} 在阶段**真的变了**时调用。 */
    static void onStageChanged(int stage) {
        BallWindow b = sInstance;
        if (b == null) return;
        b.ui.post(() -> b.swapFaceForStage(stage));
    }

    /**
     * 上一次做换脸动画的时间戳。
     *
     * <p>⚠️ 必须节流（2026-10-15，用户报"悬浮球还是会回弹抽搐"）。
     * 一次任务的阶段会在 思考中 / 干活 / 编译 之间来回跳，加上 1.5 秒的监督心跳，
     * 一分钟能触发十几次"放大 + 旋转 + 缩回" —— 屏幕上就是球在不停脉动、抽搐。
     * 节流之后：两次动画之间留最小间隔；间隔太近就**只换图、不播动画**（静默切换）。
     */
    private long lastFaceSwapAt = 0;
    private static final long FACE_SWAP_MIN_GAP_MS = 4000;

    private void swapFaceForStage(int stage) {
        try {
            if (!autoFace(app)) return;
            String want = faceForStage(stage);
            if (want.equals(stageFaceId)) return;
            stageFaceId = want;
            long now = System.currentTimeMillis();
            if (now - lastFaceSwapAt < FACE_SWAP_MIN_GAP_MS) {
                applyBallIcon();     // 太频繁 → 静默换图，绝不抽搐
                return;
            }
            lastFaceSwapAt = now;
            animateFaceSwap();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "换阶段表情失败", t);
        }
    }

    /**
     * 换表情的过渡：**放大 + 轻微旋转 → 顶点换图 → 缩回转正**。
     *
     * <p>为什么在"顶点"换图：直接换会显得很硬（图突然跳一下）。
     * 放大到 1.22 倍的那一瞬间，观众的注意力在"球变大了"这件事上，
     * 此时换图几乎看不出来，收回去时已经是新表情了 —— 这就是"衔接流畅"。
     *
     * <p>只动 ballView 自己的 scale/rotation，**不动窗口**：
     * 窗口一动就要重排坐标，那是最容易出 bug 的地方（见 {@link #applyMode} 的警告）。
     */
    private void animateFaceSwap() {
        if (ballView == null) return;
        try {
            ballView.animate().cancel();
            /*
             * 幅度从 1.22 倍 / 16° 收到 **1.10 倍 / 8°**（2026-10-15，用户报"回弹抽搐"）。
             * 1.22 倍在贴着屏幕边缘的小球上会顶出可视区、观感也像"弹了一下"；
             * 1.10 倍已经足够掩护"换图"那一瞬，但不至于抽搐。
             */
            ballView.animate()
                    .scaleX(1.10f).scaleY(1.10f).rotation(8f)
                    .setDuration(140)
                    .withEndAction(() -> {
                        applyBallIcon();               // 顶点换图
                        if (ballView == null) return;
                        ballView.animate().cancel();
                        ballView.animate()
                                .scaleX(1f).scaleY(1f).rotation(0f)
                                .setDuration(180)
                                .start();
                    })
                    .start();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "换表情动画失败", t);
        }
    }

    /**
     * 当前该画哪一张：开了自动换脸就按阶段，否则用用户手动选的那张。
     *
     * <p>抽出来是为了让"贴纸模式判断"和"实际画哪张"永远用同一个答案 ——
     * 两处各算一次的话，很容易出现"按贴纸模式画、却贴了张照片"的错配。
     */
    private BallIcon.Option currentOption() {
        String sid = stageFaceId;
        if (sid != null && autoFace(app)) {
            for (BallIcon.Option x : BallIcon.all()) {
                if (x.id.equals(sid) && x.isSticker()) return x;
            }
        }
        return BallIcon.current(app);
    }

    // ─────────────────── 自动出现 / 消失的开关 ───────────────────

    private static final String KEY_AUTO = "overlay_auto_bg";

    /** 记在 prefs 里，让界面上的入口能反映真实状态。 */
    static final String KEY_ON = "overlay_on";

    /** 自动模式默认**开**（用户要的就是这个行为）；关掉则球常驻。 */
    public static boolean autoMode(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setAutoMode(Context c, boolean on) {
        try {
            c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AUTO, on).apply();
        } catch (Throwable ignore) { }
    }

    // ─────────────────────────── 窗口本体 ───────────────────────────

    private void attachIfNeeded() {
        if (root != null) return;
        if (!canDraw(app)) {
            /*
             * ⚠️ 这条分支一定要打日志：权限被回收时（例如用户点了"清除数据"、
             * 或系统把它关掉）悬浮球会**静默地什么都不做** ——
             * 之前没打日志，害得我以为"代码没生效"，查了很久。
             */
            Log.w(TAG, "没有悬浮窗权限，悬浮球不显示");
            try {
                java.io.File dir = new java.io.File("/sdcard/.dsh-phone");
                if (!dir.exists()) dir.mkdirs();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(
                        new java.io.File(dir, "ball-icon.log"), true);
                fo.write("没有悬浮窗权限，悬浮球不显示\n".getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignore) { }
            sInstance = null;
            return;
        }
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            root = new FrameLayout(app);
            dotView = buildDot();
            bubbleView = buildBubble();
            fullView = buildFull();
            /*
             * 球与气泡**分列两侧**：球贴窗口的一边，气泡贴另一边，尾巴朝球。
             * 具体哪边由 {@link #bubbleOnLeft} 决定，切边时改 gravity。
             */
            dotLp = new FrameLayout.LayoutParams(dotSize(), dotSize());
            dotLp.gravity = Gravity.TOP | Gravity.END;
            root.addView(dotView, dotLp);
            bubLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            root.addView(bubbleView, bubLp);
            root.addView(fullView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            /*
             * ⚠️ 整屏面板**建出来就必须是 GONE**。
             *
             * 它是铺满窗口的近黑面板（0xFF0F0F11）。第一版忘了隐藏，
             * 于是加窗后第一帧就露出一块**黑色矩形**，要等球画出来才"变成球" ——
             * 用户看到的就是"莫名出现一个纯黑正方形，过很久才变成球"。
             * applyMode() 里也会设可见性，但那要等一次 updateViewLayout，
             * 中间这段空档屏幕上是黑的，所以这里先按死。
             */
            fullView.setVisibility(View.GONE);

            /*
             * ⚠️ **必须在这里再贴一次图标**（不是 buildDot 里那次就够）。
             *
             * 踩过：`buildDot()` 里确实调了 applyBallIcon()，但此刻 `dotSize()` 还要用，
             * 而 `dotImage` 的赋值与调用顺序稍一改动就变成"在赋值之前调用" ——
             * 那样每次都在 `if (dotImage == null) return;` 直接返回，
             * **球上永远不贴图**，屏幕上就只剩一个没有内容的空 View（看起来像没渲染）。
             * 窗口建好后统一贴一次最稳，也让"换图标"和"首次显示"走同一条路。
             */
            applyBallIcon();

            lp = new WindowManager.LayoutParams(
                    dotSize(), dotSize(), overlayType(),
                    // 球/气泡必须不可聚焦（不需要打字），且窗口外触摸要穿透给下面的应用。
                    // ⚠️ 绝不加 FLAG_LAYOUT_NO_LIMITS（见类注释）。
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            /*
             * ballX/ballY 是唯一真值 —— 建窗时就在这里定死，之后只由"拖动"和"钳位"改写。
             * 初值：右上角（避开状态栏那条手势区）。
             */
            ballX = app.getResources().getDisplayMetrics().widthPixels - dotSize() - dp(12);
            ballY = topSafeOffset();
            lp.x = ballX;
            lp.y = ballY;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

            applyMode(MODE_DOT, false);
            wm.addView(root, lp);
            /*
             * 建窗时就把可见性定对 —— 不能在设置页里"闪一下"。
             * 用户的规则是"有任务才出现"，而此刻我们并不确定有没有任务，
             * 所以先按判据算一次（自动模式下通常是隐藏，等监督循环确认有任务再显示）。
             */
            visible = AutoBall.shouldShow(autoMode(app), Foreground.ours(), false);
            root.setVisibility(visible ? View.VISIBLE : View.GONE);
            // 注意：这里**不再**写 KEY_ON —— 那个开关表示"用户允许它出现"，
            // 只能由用户的操作（打开/收起）来改，建窗只是实现细节。
            Log.i(TAG, "悬浮球窗口已建立 dot=" + dotSize() + " 坐标=" + lp.x + "," + lp.y);
        } catch (Throwable t) {
            Log.e(TAG, "加窗失败", t);
            root = null;
            sInstance = null;
        }
    }

    private void detach() {
        try {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                if (fullView != null) fullView.removeView(web);
                web.destroy();
            }
        } catch (Throwable ignore) { }
        web = null;
        try {
            if (wm != null && root != null) wm.removeView(root);
        } catch (Throwable ignore) { }
        root = null;
        /*
         * 这里**刻意不改 KEY_ON**。
         *
         * detach() 只负责"把窗口拿掉"，不负责表达用户意愿：
         * 用户点「收起」走 hide()（它自己会 setEnabled(false)），
         * 而"服务停了/切形态失败"之类的内部原因也会走到这里 ——
         * 那些情况下用户并没有要求关掉，把开关清掉会导致下次不自动出现。
         */
    }

    private int dp(int v) {
        return (int) (v * app.getResources().getDisplayMetrics().density);
    }

    /* ─────────────── 悬浮球与气泡的尺寸设置 ─────────────── */

    /** 悬浮球直径档位（dp）。默认第 2 档 = 40dp（历史默认值）。 */
    static final int[] BALL_SIZES = {32, 40, 52, 64, 80};
    static final int BALL_SIZE_DEFAULT = 1;

    /** 气泡缩放档位（百分比）。默认 100%。 */
    static final int[] BUBBLE_SCALES = {80, 90, 100, 115, 130};
    static final int BUBBLE_SCALE_DEFAULT = 2;

    static final String KEY_BALL_SIZE = "overlay_ball_size";
    static final String KEY_BUBBLE_SCALE = "overlay_bubble_scale";

    /** 当前球直径（dp）。 */
    static int ballSizeDp(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_BALL_SIZE, BALL_SIZES[BALL_SIZE_DEFAULT]);
        } catch (Throwable t) {
            return BALL_SIZES[BALL_SIZE_DEFAULT];
        }
    }

    /** 当前气泡缩放（百分比）。 */
    static int bubbleScalePct(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_BUBBLE_SCALE, BUBBLE_SCALES[BUBBLE_SCALE_DEFAULT]);
        } catch (Throwable t) {
            return BUBBLE_SCALES[BUBBLE_SCALE_DEFAULT];
        }
    }

    static void setBallSizeDp(Context c, int v) {
        c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BALL_SIZE, v).apply();
        refreshSizes(c);
    }

    static void setBubbleScalePct(Context c, int v) {
        c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BUBBLE_SCALE, v).apply();
        refreshSizes(c);
    }

    /**
     * 尺寸设置变了之后立刻生效。
     *
     * <p>为什么不能"只写 prefs"就完事：窗口宽高是在 {@link #applyMode} 里算好
     * 交给 WindowManager 的（纯 WindowManager 窗口没有 Activity 的测量流程，
     * 给 WRAP_CONTENT 不会自动撑开），不重算一遍并 updateViewLayout，
     * 改了设置要等下次切形态才看得到。球的直径一变，ballX 也要重新夹回屏内，
     * 否则球会有一部分跑出屏幕（历史上"球跳位"就是这么来的）。
     */
    static void refreshSizes(Context c) {
        BallWindow b = sInstance;
        if (b == null) return;
        b.ui.post(() -> {
            try {
                b.applySizesToViews();
                int keep = b.mode;
                b.mode = -1;                 // 骗过 applyMode 的"同形态早退"
                b.applyMode(keep, false);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "尺寸设置生效失败", t);
            }
        });
    }

    /** 把当前尺寸设置套到**已有**的 View 上（气泡的圆角/内边距/字号都是建时写死的）。 */
    private void applySizesToViews() {
        if (bubbleView == null || bubbleText == null) return;
        float k = bubbleK();
        bubbleText.setTextSize(11f * k);
        bubbleText.setTypeface(bubbleText.getTypeface(), android.graphics.Typeface.BOLD);
        bubbleText.setLineSpacing(bdp(2), 1f);
        // 背景（尾巴方向）+ gravity + 球的 LayoutParams + 内边距一起刷
        applyBubbleSide();
        applyBubblePadding();
        if (ballView != null) ballView.setStickerMode(currentIsSticker());
    }

    /** 气泡缩放系数（1.0 = 100%）。 */
    private float bubbleK() {
        int pct = bubbleScalePct(app);
        return Math.max(0.5f, Math.min(2.0f, pct / 100f));
    }

    /** 按气泡缩放折算的 dp（圆角 / 内边距 / 描边 / 行距都走它）。 */
    private int bdp(int v) {
        return Math.max(1, Math.round(dp(v) * bubbleK()));
    }

    /**
     * 当前选中的图标是不是贴纸（决定 BallView 要不要圆裁剪）。
     *
     * <p>读不到时**按贴纸处理**：贴纸不套圆裁剪只是样式选择，
     * 而给贴纸套圆裁剪会把轮廓切掉，后者是坏的。
     */
    private boolean currentIsSticker() {
        try {
            return currentOption().isSticker();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 悬浮球直径（读设置）。 */
    private int dotSize() {
        return dp(ballSizeDp(app));
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * 悬浮窗默认的顶部偏移：**让开状态栏那条手势区**。
     *
     * <p>用户要能从屏幕顶往下滑调出系统栏；浮层压在那条区域上会让系统认为手势被遮挡。
     * 所以这里取真实状态栏高度（API 30+），取不到就用保守比例兜底。
     */
    private int topSafeOffset() {
        int systemBarsTop = -1;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowManager w = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (w != null) {
                    android.view.WindowInsets ins = w.getCurrentWindowMetrics().getWindowInsets();
                    systemBarsTop = ins.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                }
            }
        } catch (Throwable ignore) { }
        if (systemBarsTop > 0) return systemBarsTop;
        int screenH = app.getResources().getDisplayMetrics().heightPixels;
        return Math.max(40, (int) (screenH * 0.08f));
    }

    /**
     * 切换形态。
     *
     * <h3>⚠️ 最容易写错的一点：球的位置必须"记在球自己身上"</h3>
     * 球在窗口坐标系里固定在右上角。如果用 {@code lp.x/lp.y} 当球的位置就会错，
     * 因为**窗口坐标 ≠ 球坐标**：球形态下两者相同，但气泡形态下窗口几乎占满屏宽，
     * 窗口 x 比球的位置**小得多**。实测踩过两次：
     * <ol>
     *   <li>第一版没管坐标 → 点一下球自己跳到屏幕左上角，气泡一闪就没了；</li>
     *   <li>第二版拿 lp.x 当球位置 → **收起气泡时球跳到屏幕左边**（用户报的就是这条）。</li>
     * </ol>
     * 所以现在用 {@link #ballX}/{@link #ballY} 单独记球自己的屏幕坐标。
     */
    /**
     * 切换形态 / 重排窗口。
     *
     * <h3>⚠️ 2026-10-15 重写：为什么原来会"左右摆"</h3>
     * 旧版在气泡态下把球的坐标**从窗口坐标反推**回来：
     * <pre>
     *   lp.x = ballX + dotSize() - w;        // 正向：球 → 窗口
     *   …
     *   ballX = lp.x + w - dotSize();        // 反向：窗口 → 球（clampToScreen 里）
     * </pre>
     * 这一正一反再叠上钳位，只要中间有任何一步被 <b>钳位</b>（气泡比球宽得多，
     * 球靠近右边缘时 lp.x 很容易撞到 <code>screenW - w</code>），
     * 反推出来的 ballX 就和原值差一点；下一拍再正推回去又差一点 ——
     * 于是**窗口 x 每拍挪一点**，屏幕上就是"球在左右摆"（用户报的"抽搐"）。
     *
     * <p>现在改成：**<code>ballX/ballY</code> 是唯一真值**，只被"用户拖动"和
     * "钳到屏内"改写；窗口矩形永远是它的**纯函数**（球在哪 + 气泡多大 + 在哪一侧）。
     * 同样的输入必然得到同样的输出，所以下面加了**幂等短路**：
     * 目标矩形与当前完全一致时直接返回，不调 updateViewLayout ——
     * 这既消灭了摆动，也顺带省掉了每拍一次无意义的窗口重排（那本身也会闪）。
     */
    private void applyMode(int newMode, boolean animate) {
        if (dotView == null || bubbleView == null || fullView == null || lp == null) return;

        /*
         * ⚠️ 这里**不再**从 lp 反读 ballX/ballY。
         * ballX/ballY 是唯一真值，只由"建窗"、"用户拖动"、"钳到屏内"三处改写；
         * 窗口矩形一律由它们算出来。旧版在这里反读、又在 clampToScreen 里反推，
         * 一正一反就把位置越算越偏 —— 那正是"左右摆"。
         */
        final int screenW = app.getResources().getDisplayMetrics().widthPixels;
        final int screenH = app.getResources().getDisplayMetrics().heightPixels;
        final int D = dotSize();

        int w, h, x, y, flags, format;
        switch (newMode) {
            case MODE_FULL:
                w = (int) (screenW * 0.94f);
                h = (int) (screenH * 0.72f);
                // 整屏界面要能打字 → **去掉 FLAG_NOT_FOCUSABLE**，且必须不透明
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                format = PixelFormat.OPAQUE;
                ensureWeb();          // 延迟建 WebView：加窗时不建，避免主线程卡住
                dotView.setVisibility(View.GONE);
                bubbleView.setVisibility(View.GONE);
                fullView.setVisibility(View.VISIBLE);
                x = lp.x;
                y = lp.y;
                break;

            case MODE_BUBBLE: {
                // ① 量气泡（纯 WindowManager 窗口没有 Activity 的测量流程，必须自己量）
                int maxW = Math.min((int) (screenW * 0.62f), bdp(250));
                bubbleText.setText(statusLine());
                bubbleView.measure(
                        View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int bw = Math.max(1, bubbleView.getMeasuredWidth());
                int bh = Math.max(1, bubbleView.getMeasuredHeight());
                int gap = dp(8);

                /*
                 * ② 定边：球在屏幕右半区 → 气泡放左边；左半区 → 放右边。
                 * 这样气泡永远往"屏幕里"长，不会顶出去；尾巴也永远朝着球。
                 */
                boolean wantLeft = (ballX + D / 2) >= screenW / 2;
                if (wantLeft != bubbleOnLeft || dotLp == null || bubLp == null) {
                    bubbleOnLeft = wantLeft;
                    applyBubbleSide();
                }

                w = D + gap + bw;
                h = Math.max(D, bh);
                // ③ 窗口位置：由球的锚点 + 气泡尺寸纯函数算出（不做任何反推）
                x = bubbleOnLeft ? (ballX + D - w) : ballX;
                y = ballY + D / 2 - h / 2;

                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                format = PixelFormat.TRANSLUCENT;
                dotView.setVisibility(View.VISIBLE);
                bubbleView.setVisibility(View.VISIBLE);
                fullView.setVisibility(View.GONE);
                break;
            }

            default:
                w = D;
                h = D;
                x = ballX;
                y = ballY;
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                format = PixelFormat.TRANSLUCENT;
                dotView.setVisibility(View.VISIBLE);
                bubbleView.setVisibility(View.GONE);
                fullView.setVisibility(View.GONE);
                break;
        }

        // ④ 只用 ballX/ballY 做钳位（它俩是唯一真值），再算最终的窗口位置
        int minY = topSafeOffset();
        ballX = Math.max(0, Math.min(ballX, Math.max(0, screenW - D)));
        ballY = Math.max(minY, Math.min(ballY, Math.max(minY, screenH - D)));
        if (newMode == MODE_BUBBLE) {
            x = bubbleOnLeft ? (ballX + D - w) : ballX;
            y = ballY + D / 2 - h / 2;
            // 气泡比球宽得多，钳一下别让它顶出屏幕；**但不是改 ballX**（那才是摆动之源）
            x = Math.max(0, Math.min(x, Math.max(0, screenW - w)));
            y = Math.max(0, Math.min(y, Math.max(0, screenH - h)));
        } else if (newMode == MODE_DOT) {
            x = ballX;
            y = ballY;
        }

        /*
         * ⑤ 幂等短路：目标与当前完全一致就不动。
         * 这一条是"左右摆"的根治手段 —— 摆动的本质就是每拍都做了一次
         * "位置略有不同"的 updateViewLayout。
         */
        boolean same = (mode == newMode)
                && lp.width == w && lp.height == h
                && lp.x == x && lp.y == y
                && lp.flags == flags && lp.format == format;
        if (same) return;

        lp.width = w;
        lp.height = h;
        lp.x = x;
        lp.y = y;
        lp.flags = flags;
        lp.format = format;
        mode = newMode;
        try { if (wm != null && root != null) wm.updateViewLayout(root, lp); } catch (Throwable t) {
            Log.w(TAG, "切换形态失败", t);
        }
        /*
         * ⚠️ **同步把子 View 按新尺寸排一遍** —— 这是"打开气泡时球会抽一下"的根治手段。
         *
         * 症状（2026-10-15 用户第二段录屏 f05 抓到）：打开气泡的那一帧里**球整个消失**，
         * 下一帧才回到右边。
         *
         * 原因：窗口从 120 宽变成 411 宽。`updateViewLayout` 只是把"窗口要改尺寸"排进
         * WMS 的队列，本帧的 FrameLayout **还是旧的 120 宽**在排子 View；而球是
         * `gravity=TOP|END` —— 旧宽度下它贴着窗口**左边**。窗口表面这一帧已经按新尺寸
         * 合成了，于是球被画在窗口左缘，正好落在气泡底下（气泡后 add，盖在球上面），
         * 屏幕上就是"球没了"。等下一帧真正重排，球才跳到右边。
         *
         * 改法：改完 lp 立刻用新宽高手动 measure+layout 一次。这样本帧的子 View 位置
         * 就已经是对的，不存在"窗口是新的、内容还是旧的"这一帧。
         * 后面 ViewRootImpl 自己的那次 traverse 是幂等的，不会重复开销。
         */
        try {
            if (root != null && w > 0 && h > 0) {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, w, h);
            }
        } catch (Throwable ignore) { }
        if (newMode == MODE_FULL && web != null) {
            String url = DshService.getUrl();
            if (url != null && !url.isEmpty() && !url.equals(web.getUrl())) web.loadUrl(url);
        }
    }

    /**
     * 尾巴画在哪一侧 —— **与 {@link #bubbleOnLeft} 相反**，这是尾巴的全部要点。
     *
     * <p>尾巴的尖必须**朝着球**：
     * <ul>
     *   <li>气泡在球左边（{@code bubbleOnLeft=true}）→ 球在气泡右边 → 尾巴画在**右**侧；</li>
     *   <li>气泡在球右边 → 球在左边 → 尾巴画在**左**侧。</li>
     * </ul>
     * 第一版我图省事直接传了 {@code bubbleOnLeft}，结果尾巴朝着**背离球**的方向 ——
     * 提笔画反了。所以抽成这一个方法，背景和文字内边距都走它，避免两处各写一遍又写反。
     */
    private boolean tailOnLeft() {
        return !bubbleOnLeft;
    }

    /**
     * 按 {@link #bubbleOnLeft} 把球与气泡摆到窗口的两侧，并告诉气泡尾巴朝哪边。
     *
     * <p>只改 gravity 与 LayoutParams，不碰窗口位置 —— 位置由 {@link #applyMode} 统一算。
     */
    private void applyBubbleSide() {
        if (dotLp != null) {
            dotLp.gravity = Gravity.TOP | (bubbleOnLeft ? Gravity.END : Gravity.START);
            dotLp.width = dotSize();
            dotLp.height = dotSize();
            dotLp.topMargin = 0;
            if (dotView != null) dotView.setLayoutParams(dotLp);
        }
        if (bubLp != null) {
            bubLp.gravity = Gravity.CENTER_VERTICAL | (bubbleOnLeft ? Gravity.START : Gravity.END);
            bubLp.topMargin = 0;
            if (bubbleView != null) bubbleView.setLayoutParams(bubLp);
        }
        if (bubbleView != null) {
            bubbleView.setBackground(new BubbleBg(0xFFFFFFFF, 0xFFD8DCE6,
                    bdp(14), bdp(7), Math.max(1f, dp(1)), tailOnLeft()));
        }
    }

    /** 尾巴那一侧要留出的额外内边距（背景画了尾巴，文字别压上去）。 */
    private void applyBubblePadding() {
        int p = bdp(12);
        int side = bdp(7);
        bubbleView.setPadding(tailOnLeft() ? p + side : p, bdp(9),
                tailOnLeft() ? p : p + side, bdp(9));
    }


    /**
     * 把**球**钳在屏幕内，并留出顶部那条系统手势区。
     *
     * <p>只改 {@link #ballX}/{@link #ballY} —— 它们是唯一真值。
     * 窗口位置由 {@link #applyMode} 从它们算出来，**绝不反过来**从窗口坐标推球的位置
     * （旧版那样做，配上钳位就会一点点把球推偏，表现就是"左右摆"）。
     */
    private void clampToScreen() {
        if (lp == null) return;
        int screenW = app.getResources().getDisplayMetrics().widthPixels;
        int screenH = app.getResources().getDisplayMetrics().heightPixels;
        int D = dotSize();
        int minY = topSafeOffset();
        ballX = Math.max(0, Math.min(ballX, Math.max(0, screenW - D)));
        ballY = Math.max(minY, Math.min(ballY, Math.max(minY, screenH - D)));
    }

    private void applyVisibility(boolean want) {
        if (root == null) return;
        /*
         * ⚠️ 这里**不能**写 `if (want == visible) return;`（踩过）。
         *
         * 窗口是按"当前该不该显示"建的，而建的时候通常判定为**隐藏**
         * （自动模式下要先确认"有任务"）。之后调用方调 reconcile(true) 想把它显示出来，
         * 如果这里因为"状态没变"直接 return，球就永远显示不出来 ——
         * 实测现象是：窗口 exists、坐标尺寸都对，但 mViewVisibility=0x8，屏幕上看不见。
         *
         * setVisibility 本身是幂等的（设成当前值不会重绘），所以无条件设最省心。
         */
        visible = want;
        try {
            root.setVisibility(want ? View.VISIBLE : View.GONE);
            Log.i(TAG, "悬浮球" + (want ? "显示" : "隐藏")
                    + "（我们前台=" + Foreground.ours() + "）");
        } catch (Throwable t) {
            Log.w(TAG, "切换可见性失败", t);
        }
    }

    // ─────────────────────────── 小圆球 ───────────────────────────

    /** 悬浮球：**一张可更换的图片**（见 {@link BallIcon}）+ 单击/双击/拖动。 */
    private View buildDot() {
        // 球是**自绘 View**（见 BallView 类注释：ImageView + Drawable 那几条路在真机上都渲染成黑块）
        BallView ball = new BallView(app);
        ballView = ball;
        applyBallIcon();

        final GestureDetector gd = new GestureDetector(app,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                /*
                 * 再复核一次"这真的是一次点击吗"（GitHub issue：滑一下跳到了别的页面）。
                 *
                 * 拖动时窗口跟着手指走，GestureDetector 用的是 **View 内坐标** ——
                 * 内坐标几乎不动，永远到不了 touchSlop，于是它把拖动当成了单击。
                 * 主防线是下面 onTouch 里的 cancelDrag()（一旦判定为拖动就给它补 ACTION_CANCEL），
                 * 这里用 **屏幕坐标** 再兜一层：任何一次"按下点与抬手点真差了 6dp 以上"的，
                 * 一律不算点击。
                 */
                if (tapMovedAway(e)) return false;
                // 点一次 → 冒气泡（或收起气泡）
                applyMode(mode == MODE_BUBBLE ? MODE_DOT : MODE_BUBBLE, true);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 连点两次 → 回主界面（用户明确要的）；同样要排除"两次滑动被当成连点"
                if (tapMovedAway(e)) return false;
                openMain();
                return true;
            }
        });
        ball.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startBallX, startBallY;
            boolean moved;
            /** 已被判定为拖动：本次手势剩下的所有事件都不再喂给 GestureDetector。 */
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                /*
                 * ⚠️ **不能无条件 gd.onTouchEvent(ev)**（GitHub issue 的根因 2）。
                 *
                 * 原来第一句就是它，而 ACTION_MOVE 分支会把窗口坐标跟着手指改 ——
                 * GestureDetector 判"动没动"用的是 View 内坐标，窗口跟着走，
                 * 内坐标就几乎不变 → 永远到不了 touchSlop → 抬手后照样发
                 * onSingleTapConfirmed（切换气泡）；两次滑动还会落进 onDoubleTap（跳主界面）。
                 * 现在：一旦屏幕坐标真的移开，就给它补一个 ACTION_CANCEL 并停止投喂。
                 */
                if (dragging) {
                    handleDrag(ev);
                    return true;
                }
                gd.onTouchEvent(ev);
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX(); downY = ev.getRawY();
                        // 给 onSingleTapConfirmed/onDoubleTap 复核用（见 tapMovedAway）
                        tapDownRawX = downX; tapDownRawY = downY;
                        // 拖动基准取**球**的坐标，不是窗口坐标 —— 气泡态下两者差着一整个气泡宽度
                        startBallX = ballX; startBallY = ballY;
                        moved = false;
                        dragging = false;
                        pressFeedback(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && movedEnough(ev.getRawX() - downX, ev.getRawY() - downY)) {
                            moved = true;
                            dragging = true;
                            cancelGesture(gd, ev);   // 让 GestureDetector 彻底放弃这一次手势
                        }
                        if (moved) {
                            ballX = startBallX + (int) (ev.getRawX() - downX);
                            ballY = startBallY + (int) (ev.getRawY() - downY);
                            clampToScreen();
                            // 交给 applyMode 统一算窗口矩形（带幂等短路，不会抖）
                            applyMode(mode, false);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pressFeedback(false);
                        dragging = false;
                        return true;
                    default:
                        return true;
                }
            }

            /** 拖动已经成立时的后续事件：只负责跟着手指走。 */
            private void handleDrag(MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        ballX = startBallX + (int) (ev.getRawX() - downX);
                        ballY = startBallY + (int) (ev.getRawY() - downY);
                        clampToScreen();
                        applyMode(mode, false);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pressFeedback(false);
                        dragging = false;
                        break;
                    default:
                        break;
                }
            }
        });
        return ball;
    }

    /** 手指在屏幕上真的移开了多少才算拖动（不是"窗口内坐标变了多少"）。 */
    private boolean movedEnough(float dx, float dy) {
        int slop = dp(6);
        try {
            int sys = android.view.ViewConfiguration.get(app).getScaledTouchSlop();
            if (sys > slop) slop = sys;
        } catch (Throwable ignore) { }
        return Math.abs(dx) > slop || Math.abs(dy) > slop;
    }

    /** 给 GestureDetector 补一个 ACTION_CANCEL，让它放弃这次手势（不会再发单击/双击）。 */
    private static void cancelGesture(GestureDetector gd, MotionEvent ev) {
        try {
            MotionEvent c = MotionEvent.obtain(ev);
            c.setAction(MotionEvent.ACTION_CANCEL);
            gd.onTouchEvent(c);
            c.recycle();
        } catch (Throwable ignore) { }
    }

    /**
     * 这次"点击"其实是不是移动过？
     *
     * <p>用**屏幕坐标**（getRawX/getRawY）而不是 View 内坐标 —— 后者在窗口跟着手指移动时
     * 几乎没有变化，正是这个坑的来源。DOWN 的屏幕坐标记在 {@link #tapDownRawX} 里。
     */
    private boolean tapMovedAway(MotionEvent e) {
        if (e == null || tapDownRawX == Float.MIN_VALUE) return false;
        float dx = e.getRawX() - tapDownRawX;
        float dy = e.getRawY() - tapDownRawY;
        return movedEnough(dx, dy);
    }

    /** 按下时的屏幕坐标（给 tapMovedAway 复核用）。 */
    private float tapDownRawX = Float.MIN_VALUE;
    private float tapDownRawY = Float.MIN_VALUE;

    /**
     * 按压反馈：按下去"被挤压"一点，松开弹回。
     *
     * <p>用户要求「点悬浮图标时图标要露出被挤压的表现」。
     *
     * <p>关键取舍：**只缩 {@link #ballView} 自己，不动窗口**。
     * 窗口尺寸一改就得重排坐标（见 {@link #applyMode} 那段警告），
     * 而这里只是视觉反馈，缩 View 既不影响命中区域、也不会让球跳位。
     */
    private void pressFeedback(boolean down) {
        if (ballView == null) return;
        try {
            ballView.animate().cancel();
            /*
             * 缩放幅度从 0.86 收到 **0.93**、回弹时长从 130ms 收到 100ms
             * （2026-10-15 用户报"还是会回弹抽搐"）。
             *
             * 0.86 是 14% 的形变，配上 130ms 的回弹，在贴着屏幕边缘的小球上
             * 看起来像是"猛地一缩又弹回来"，不是"被按了一下"。0.93 + 100ms
             * 才接近 iOS 那种轻按压感；要更明显就只调这两个数，别动结构。
             */
            ballView.animate()
                    .scaleX(down ? 0.93f : 1f)
                    .scaleY(down ? 0.93f : 1f)
                    .setDuration(down ? 60 : 100)
                    .start();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "按压反馈失败", t);
        }
    }

    /**
     * 把用户选中的图标画到球上。
     *
     * <h3>为什么解码要放到后台线程（用户报"卡"的一条）</h3>
     * 读 `openRawResource` + 解一张 192×192 的 jpg 是**几十毫秒到上百毫秒**的活，
     * 而加窗、切形态都发生在**主线程**上。放主线程做，用户点一次"常驻"就要等它，
     * 叠加 WebView 那一下（见 {@link #buildFull}）就变成"点一下卡死"。
     *
     * <p>所以：主线程只负责"把已经解好的位图贴上去"，解码丢到单线程池，
     * 解好再回主线程贴。解出来之前球先显示圆底 —— 比卡住强得多。
     *
     * <p>同一张图**只解一次**（{@link #iconCache}）：切形态、换可见性都会走到这里，
     * 每次都重解毫无必要。
     */
    private void applyBallIcon() {
        if (ballView == null) return;
        // 用同一个入口决定"画哪张"，避免贴纸模式与实际贴的图对不上
        final BallIcon.Option o = currentOption();
        /*
         * 贴纸 / 照片 是两种画法，必须先定下来：
         * 贴纸是不规则轮廓（不裁剪、不画圆底），照片必须裁成圆。
         * 这一步不能省 —— 漏了就会拿贴纸去套圆裁剪，耳朵尾巴全被切掉。
         */
        ballView.setStickerMode(o.isSticker());
        android.graphics.Bitmap cached = iconCache;
        if (cached != null && o.id.equals(iconCachedId) && !cached.isRecycled()) {
            ballView.setIcon(cached);
            return;
        }
        final String wantId = o.id;
        iconPool.execute(() -> {
            final android.graphics.Bitmap b = BallView.loadIcon(app, o);
            ui.post(() -> {
                if (ballView == null) return;
                // 期间用户又换了图标 / 阶段又变了 → 丢弃这次结果，避免贴错图
                BallIcon.Option now = currentOption();
                if (!wantId.equals(now.id)) return;
                iconCache = b;
                iconCachedId = b == null ? null : wantId;
                ballView.setStickerMode(now.isSticker());
                ballView.setIcon(b);
                if (b == null) Log.w(TAG, "图标解码失败（只画圆底）: " + o.drawable);
            });
        });
    }

    /** 已解好的图标（换图标时会重新解）。 */
    private android.graphics.Bitmap iconCache;
    private String iconCachedId;
    /** 图标解码用：单线程就够，也不需要并发。 */
    private static final java.util.concurrent.ExecutorService iconPool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dsh-ball-icon");
                t.setDaemon(true);
                return t;
            });

    // ─────────────────────────── 气泡 ───────────────────────────

    /**
     * 气泡底：**白色圆角 + 底部居中的小尾巴**（用户给了参考图，要求按那个样式来）。
     *
     * <p>为什么不拼两个 View 做尾巴（一个转 45° 的白方块）：气泡窗口的高度是
     * **显式 measure** 出来的（见 {@link #applyMode}），多一个旋转方块会让
     * 测量、裁切、接缝三件事同时变复杂。一个自绘 Drawable 里画完，
     * 控件的 bounds 就是气泡的全部，最稳。
     *
     * <p>描边用很浅的灰，而不是参考图那种深色描边：
     * 悬浮球是浮在**任何**应用上层的，浅色壁纸下白气泡没有描边会糊在一起。
     */
    private static class BubbleBg extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint edge = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path tail = new android.graphics.Path();
        private final android.graphics.RectF body = new android.graphics.RectF();
        private final float radius, tailW, stroke;
        /** 尾巴朝哪边：{@code true} = 朝左（气泡在球的右边），{@code false} = 朝右。 */
        private final boolean tailLeft;

        BubbleBg(int fillColor, int strokeColor, float radius, float tailW, float stroke,
                 boolean tailLeft) {
            fill.setStyle(android.graphics.Paint.Style.FILL);
            fill.setColor(fillColor);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setColor(strokeColor);
            edge.setStrokeWidth(stroke);
            this.radius = radius;
            this.tailW = tailW;
            this.stroke = stroke;
            this.tailLeft = tailLeft;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float inset = stroke / 2f;
            /*
             * 尾巴占掉一侧的一条竖带：圆角矩形只画到那条带之前，
             * 尾巴再从矩形边缘往球的方向伸出去 —— 于是**尾巴朝侧边**，不是朝下。
             */
            float bodyL = b.left + (tailLeft ? tailW : 0f);
            float bodyR = b.right - (tailLeft ? 0f : tailW);
            body.set(bodyL + inset, b.top + inset, bodyR - inset, b.bottom - inset);

            float cy = b.exactCenterY();
            float half = Math.max(inset, tailW * 0.85f);
            tail.reset();
            if (tailLeft) {
                tail.moveTo(bodyL + 1f, cy - half);
                tail.lineTo(bodyL + 1f, cy + half);
                tail.lineTo(b.left, cy);
            } else {
                tail.moveTo(bodyR - 1f, cy - half);
                tail.lineTo(bodyR - 1f, cy + half);
                tail.lineTo(b.right, cy);
            }
            tail.close();

            c.drawPath(tail, fill);
            c.drawRoundRect(body, radius, radius, fill);
            c.drawPath(tail, edge);          // 尾巴也描一圈，否则接缝处断线
            c.drawRoundRect(body, radius, radius, edge);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); edge.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) {
            fill.setColorFilter(f); edge.setColorFilter(f);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 气泡：**只放当前步骤一行**，白底 + 深蓝粗体，尾巴朝**侧边**指向球。
     *
     * <p>（2026-10-15 改）原来是"白底 + 尾巴朝下"，用户指出两个问题：
     * 尾巴方向不对（应该是横向指着球），而且气泡挂在球下面不对；
     * 现在改成气泡在球的**侧面**、尾巴横向指向球，并随左右半区自动翻转。
     */
    private LinearLayout buildBubble() {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new BubbleBg(0xFFFFFFFF, 0xFFD8DCE6,
                bdp(14), bdp(7), Math.max(1f, dp(1)), tailOnLeft()));
        // 尾巴那一侧多留尾巴的宽度（与 applyBubblePadding 同一套算法）
        int bp = bdp(12);
        int bside = bdp(7);
        box.setPadding(tailOnLeft() ? bp + bside : bp, bdp(9),
                tailOnLeft() ? bp : bp + bside, bdp(9));
        bubbleText = new TextView(app);
        bubbleText.setTextColor(0xFF2B3A8F);            // 参考图的深蓝
        bubbleText.setTextSize(11f * bubbleK());
        bubbleText.setTypeface(bubbleText.getTypeface(), android.graphics.Typeface.BOLD);
        bubbleText.setLineSpacing(bdp(2), 1f);
        bubbleText.setText("正在读取状态…");
        box.addView(bubbleText);

        // 点气泡本身 = 收起（用户："再点一次就会隐藏气泡"）
        box.setOnClickListener(v -> applyMode(MODE_DOT, true));
        return box;
    }

    /**
     * 气泡**只显示当前进行到哪一步**。
     *
     * <h3>为什么砍掉了原来那几行</h3>
     * 原来这里是四段拼起来：阶段行 + `▸ 最近一行输出` + `DSH：运行中` + 一行操作教程。
     * 用户的要求是「只显示目前进行到的步骤，实时显示」，并且「操作教程放进设置里面」。
     * 所以：
     * <ul>
     *   <li>明细行（▸ …）—— 删：它是给排查用的，不是"进行到哪一步"；</li>
     *   <li>运行状态行 —— 删：球在不在、DSH 开没开，用户自己看得见；</li>
     *   <li>操作教程行 —— 删：搬进「悬浮球设置」页（见 BallSettingsActivity）。</li>
     * </ul>
     *
     * <p>阶段文案本身仍然由 {@link BallStage#detect} 从三类可观测信号算出来
     * （stdout 最近一行 / 容器 CPU 活跃度 / 服务状态）—— 模型的内部推理不在 App
     * 能拿到的地方，拿不到的东西不假装有。
     */
    private String statusLine() {
        String stageText = currentStageText();
        if (stageText != null && !stageText.isEmpty()) return stageText;

        /*
         * 阶段过期（长时间无更新，见 STAGE_TTL_MS）时的兜底。
         * 必须是**一句有意义的话**而不是空串 —— 空框会让人以为气泡坏了。
         */
        String url = DshService.getUrl();
        boolean running = url != null && !url.isEmpty();
        String st = DshService.getState();
        if (st != null && !st.isEmpty() && !"未启动".equals(st) && !st.startsWith("运行中")) {
            return running ? "DSH 运行中 · " + st : st;
        }
        return running ? "DSH 运行中" : "DSH 未运行";
    }

    // ─────────────────────────── 整屏界面 ───────────────────────────

    /**
     * 整屏界面：**WebView 用到才建**（这是"卡死"和"黑方块"的根因）。
     *
     * <h3>为什么不能在这里就 new WebView（踩得很惨）</h3>
     * `new WebView(...)` 会在这个进程里**启动 Chromium**，实测要 0.5~3 秒，
     * 而且是在**主线程**上。原来的 `attachIfNeeded()` 里无条件调了它，
     * 于是每次加窗（包括"点常驻"那一刻）都要先卡这么久 ——
     * 用户看到的就是"点常驻悬浮球直接卡死"。
     *
     * <p>那块**黑方块也是它**：`fullView` 是铺满窗口的近黑面板（0xFF0F0F11），
     * 建出来又忘了隐藏，WebView 初始化把首帧绘制拖住，屏幕上就先露出一块黑色矩形，
     * 等 Chromium 起来、球画出来之后才"变成球"。
     *
     * <p>现在这里只建**一个空壳**（一根标题栏），等用户真的要看整屏时
     * 才由 {@link #ensureWeb()} 把 WebView 装进去。
     */
    private LinearLayout buildFull() {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF0F0F11);

        LinearLayout bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1A1A1D);
        int p = dp(8);
        bar.setPadding(p, p, p, p);
        TextView title = new TextView(app);
        title.setText("DSH");
        title.setTextColor(0xFFE8E8EA);
        title.setTextSize(13);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        bar.addView(smallButton("刷新", v -> { if (web != null) web.reload(); }));
        bar.addView(smallButton("收起", v -> applyMode(MODE_DOT, true)));
        box.addView(bar);
        fullBox = box;
        return box;
    }

    /** 真正要用整屏界面时才建 WebView（只能主线程，且只建一次）。 */
    private void ensureWeb() {
        if (web != null || fullBox == null) return;
        try {
            web = new WebView(app);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setMediaPlaybackRequiresUserGesture(false);
            web.setBackgroundColor(0xFF0F0F11);
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(new WebChromeClient());
            fullBox.addView(web, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } catch (Throwable t) {
            Log.w(TAG, "建 WebView 失败", t);
        }
    }

    private TextView smallButton(String text, View.OnClickListener onClick) {
        TextView t = new TextView(app);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(0xFFE8E8EA);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2A2E);
        bg.setCornerRadius(dp(8));
        t.setBackground(bg);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(6), 0);
        t.setLayoutParams(p);
        t.setOnClickListener(onClick);
        return t;
    }

    /** 连点两次 → 回 Tdsh 主界面（不是关掉 DSH）。 */
    private void openMain() {
        try {
            Intent i = new Intent(app, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
            // 回到主界面后浮层没必要占着整屏 → 缩回球
            ui.postDelayed(() -> applyMode(MODE_DOT, true), 400);
        } catch (Throwable t) {
            Toast.makeText(app, "打不开主界面：" + t, Toast.LENGTH_SHORT).show();
        }
    }
}
