package com.dshmobile.probe;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮球的「任务阶段」判定 —— **纯逻辑，不引用任何 Android 类**。
 *
 * <h3>为什么要单独抽一个纯逻辑类</h3>
 * 阶段文案直接决定用户看到的字，判错了很难看（比如明明在编译却显示"已完成"）。
 * 抽成纯逻辑后可以用 {@code tools/BallStageSelfTest.java} 在 PC 上离线跑回归 ——
 * 本项目已有的纯逻辑类（UrlPick / PluginMsg / ProcLogic …）都是这个套路，
 * 规矩是"纯逻辑类不许 import android"（见 开发交接-总览.txt §5）。
 *
 * <h3>阶段信号从哪来（这是第一步要梳理清楚的）</h3>
 * 我们**拿不到模型内部的推理过程**，但能拿到这些可观测信号：
 * <ol>
 *   <li><b>DSH stdout 最近一行有意义输出</b>（{@link DshService#getCurrentLine()}）——
 *       起工具、跑命令、写文件都会打到这里。是阶段判定最主要依据。</li>
 *   <li><b>容器 node 进程的 CPU 活跃度</b>（{@link AutoBall#busy()} /
 *       {@link TaskWatch}）—— 有 CPU 消耗说明"有任务在跑"，
 *       哪怕 stdout 暂时没新行（模型在生成 token 时就是这样）。</li>
 *   <li><b>服务状态字符串</b>（{@link DshService#getState()}）——
 *       能区分"正在解压容器/装依赖"这类**初始化阶段**。</li>
 *   <li><b>网页自己报的阶段</b>（{@code DshAndroid.pageStage()} ← patch.js 的 pageStageWatch）——
 *       最准的一路：它就长在对话界面上，"深度求索中"、工具卡片、报错全在它的 DOM 里。
 *       优先级最高，只要还新鲜就直接采信（见 {@link #detect} 完整版）。</li>
 * </ol>
 * 所以"思考中"这个阶段的判据是：**有任务在跑，但输出没有新的可归类内容** ——
 * 对应模型正在生成、还没发起工具调用的时候。这是能自圆其说的解释，不是硬凑。
 *
 * <h3>阶段与触发时机</h3>
 * <pre>
 *   阶段        触发时机（判据）                                文案
 *   BOOT        服务状态含"解压/初始化/准备/启动中"            正在准备运行环境…
 *   THINKING    有任务在跑，但最近输出不可归类（模型在生成）    思考中…
 *   COMPILING   最近输出含编译/构建/安装类关键词                正在编译文件…
 *   WORKING     最近输出含工具调用/命令/读写文件类关键词        正在努力干活…
 *   DONE        任务刚结束（CPU 落下来）                        已完成
 *   ERROR       最近输出含失败/异常/报错类关键词                出错了
 *   IDLE        没有任务在跑                                    空闲
 * </pre>
 * 优先级：ERROR &gt; BOOT &gt; COMPILING &gt; WORKING &gt; DONE &gt; THINKING &gt; IDLE。
 * （错误优先于一切：出了错就该立刻说出来，不该被"正在干活"盖住。）
 */
public final class BallStage {

    private BallStage() { }

    // ── 阶段枚举（用 int 常量而不是 enum：要跨进程塞进 Intent/日志，整数更省事）──
    public static final int IDLE = 0;
    public static final int BOOT = 1;
    public static final int THINKING = 2;
    public static final int COMPILING = 3;
    public static final int WORKING = 4;
    public static final int DONE = 5;
    public static final int ERROR = 6;

    /** 阶段名（诊断/日志用，也是文案映射表的键）。 */
    public static String name(int stage) {
        switch (stage) {
            case BOOT: return "BOOT";
            case THINKING: return "THINKING";
            case COMPILING: return "COMPILING";
            case WORKING: return "WORKING";
            case DONE: return "DONE";
            case ERROR: return "ERROR";
            default: return "IDLE";
        }
    }

    /**
     * 内置文案映射表（键=阶段名，值=用户看到的那句话）。
     *
     * <p>用户要求"从配置读取，便于后续扩展"，所以真正的取值顺序是：
     * 用户配置 → 这张内置表 → 兜底字符串（见 {@link #text}）。
     */
    public static final String[][] DEFAULT_TEXT = {
            {"IDLE", "空闲"},
            {"BOOT", "正在准备运行环境…"},
            {"THINKING", "思考中…"},
            {"COMPILING", "正在编译文件…"},
            {"WORKING", "正在努力干活…"},
            {"DONE", "已完成"},
            {"ERROR", "出错了"},
    };

    /**
     * 各阶段的关键词表 —— **顺序有意义**：先匹配到的先算。
     *
     * <p>把 ERROR 放最前，是因为"报错"里常常同时含"build""文件"等词
     * （例如 "build failed: xxx file not found"），若让 COMPILING 先匹配，
     * 就会把一次失败显示成"正在编译文件…"。
     */
    public static final String[][] KEYWORDS = {
            // 错误优先
            {"ERROR", "error|failed|failure|exception|traceback|cannot|unable|"
                    + "报错|错误|失败|异常|超时|timeout|denied|refused|not found"},
            // 初始化/环境准备
            {"BOOT", "extract|unpack|mount|bootstrap|installing|downloading|prepar|"
                    + "解压|初始化|准备|挂载|下载|安装依赖|provision"},
            // 编译/构建
            {"COMPILING", "compil|build|gradle|webpack|vite|tsc|babel|bundl|transpil|"
                    + "编译|构建|打包|生成产物"},
            // 干活（工具调用/读写/执行）
            {"WORKING", "tool|exec|run |command|write|edit|patch|create|delete|move|copy|"
                    + "read|grep|search|fetch|curl|npm|pnpm|node |git |"
                    + "执行|运行|写入|修改|创建|删除|读取|搜索|抓取"},
            // 收尾
            {"DONE", "done|finished|complete|success|ok$|succeeded|"
                    + "完成|结束|成功"},
    };

    /**
     * stdout 那一行**有多旧**就不算数了。
     *
     * <h3>为什么必须有时效（2026-10-21 用户报的"球永远是空闲"）</h3>
     * {@link DshService#getCurrentLine()} 取的是"最近一行"—— 但那一行会**一直待在那里**：
     * DSH 启动完就不再往 stdout 打东西了，最后一行可能是启动日志里的一句
     * {@code listening on …}，半个小时都不会变。旧逻辑每 1.5 秒拿这行重新判一次，
     * 结果就是**阶段被一行陈年日志钉死**，屏幕上永远显示同一句话。
     *
     * <p>所以超过这个时长的一行直接当"没有输出"处理（20 秒 ≈ 十几拍心跳）。
     */
    public static final long LINE_TTL_MS = 20_000L;

    /**
     * 网页报上来的阶段**多久之内算数**（毫秒）。
     *
     * <p>网页那边在"有动静"时每秒左右上报一次；6 秒没新报告就当作它不说话了
     * （用户切到别的 App、页面被回收、或者对话本来就闲下来了），此时退回本地判据。
     * 取 6 秒而不是 2 秒：一次工具调用中间会有几秒的静默，不该让阶段闪回"空闲"。
     */
    public static final long WEB_TTL_MS = 6_000L;


    /**
     * 判定当前阶段（**没有时效参数的老入口**，供自检与简单调用用）。
     *
     * @param lastLine      DSH stdout 最近一行有意义输出（可为 null）
     * @param taskActive    是否有任务在跑（{@link AutoBall#busy()} 的结果）
     * @param justFinished  任务刚刚结束（"上一拍在跑、这一拍不跑了"）
     * @param serviceState  服务状态字符串（可为 null；用来识别初始化阶段）
     * @return 阶段常量
     */
    public static int detect(String lastLine, boolean taskActive, boolean justFinished,
                             String serviceState) {
        return detect(lastLine, 0L, taskActive, justFinished, serviceState, IDLE, Long.MAX_VALUE);
    }

    /**
     * 判定当前阶段（**完整版**）。
     *
     * <p>多出来的两个信号解决的是"我们到底能不能看见 agent 在干活"：
     * <ul>
     *   <li>{@code lineAgeMs}：那行输出有多旧。太旧就当没有（见 {@link #LINE_TTL_MS}）。</li>
     *   <li>{@code webStage}/{@code webAgeMs}：**网页自己报的阶段**。这是最可信的一路 ——
     *       它就长在对话界面上，"深度求索中"、工具卡片、报错全在它的 DOM 里，
     *       比 stdout 和 CPU 采样都准。只要还新鲜（{@code webAgeMs} 小），直接采信。</li>
     * </ul>
     *
     * @param webStage 网页报上来的阶段；{@code < 0} 表示网页没报过
     * @param webAgeMs 该报告距今多久（没报过就传 {@link Long#MAX_VALUE}）
     */
    public static int detect(String lastLine, long lineAgeMs, boolean taskActive,
                             boolean justFinished, String serviceState,
                             int webStage, long webAgeMs) {
        return detect(lastLine, lineAgeMs, taskActive, justFinished, serviceState,
                webStage, webAgeMs, false, null, System.currentTimeMillis());
    }

    /**
     * 「气泡空转」的粘性窗口：网页刚给过终态（已完成）之后，多久之内的
     * "只有 CPU 在动"不算数（毫秒）。
     *
     * <h3>为什么需要（GitHub issue：气泡每 2–4 秒循环一次，34 分钟不停）</h3>
     * 现场日志把因果写得很清楚：
     * <pre>
     *   15:03:13  IDLE → THINKING   网页=THINKING@381ms    有任务=true
     *   15:03:17  THINKING → DONE   网页=DONE@2373ms       有任务=false
     *   15:03:21  DONE → IDLE       网页=DONE@6387ms       有任务=false
     *   15:03:27  IDLE → THINKING   网页=DONE@12393ms      有任务=true   ← 网页仍是 DONE！
     * </pre>
     * 交叉统计 248 次切换：<b>网页=DONE 却有任务=true 出现了 69 次，全被判成 THINKING</b>。
     * 也就是说"有任务"这一路（容器 CPU 采样）的优先级压过了网页的终态，
     * 而它本身是个会自己抖的标志（容器里的 node 闲着也在轮询/GC，偶尔连烧两拍），
     * 于是终态被反复拉回"思考中"，再由"上一拍在跑、这一拍停了"判回"已完成"，永不收敛。
     *
     * <p>治法是"终态优先 + 要求新的可观测进展"：网页说过 DONE 之后，只有
     * <b>新的 stdout 输出</b>、或<b>网页重新报了一个进行中的阶段</b>、或<b>网页自己说在忙</b>，
     * 才有资格把阶段拉出 DONE/IDLE；纯 CPU 抖动一律不理。
     * 30 秒是有意的上限：真有新任务却看不见（网页那一路坏了）时，
     * 最多 30 秒后仍会回到"思考中"，不会重演 0.4.6 的"永远停在空闲"。
     */
    public static final long POST_DONE_HOLD_MS = 30_000L;

    /**
     * "已完成"这一屏至少站多久（毫秒）。
     *
     * <p>老实现里 DONE 只活一拍（1.5–4 秒）就被 IDLE 顶掉，用户经常根本没看见；
     * 而"已完成"本身是有信息量的（回答完了）。站 6 秒再回空闲，
     * 不会挡住后续阶段 —— 真有新动静会立刻按上面的规则切走。
     */
    public static final long DONE_SHOW_MS = 6_000L;

    /**
     * 判定用的**记忆**：把"网页最近说过什么"和"上一次已完成时的 stdout 长什么样"留住。
     *
     * <p>做成外部传入的可变对象（而不是 BallStage 的静态字段），
     * 是因为它是**每个悬浮球会话各自的状态**：自检要能并发跑多组用例，
     * 静态字段会互相污染。调用方（{@code DshService.ballLoop}）持有一个实例即可。
     */
    public static final class Memo {
        /** 网页最近一次报的阶段（{@code < 0} = 没报过）。 */
        public int webStage = -1;
        /** 网页最近一次上报的时刻。 */
        public long webAt = 0L;
        /** 上一次判为"已完成"的时刻（0 = 还没有过）。 */
        public long doneAt = 0L;
        /** 上一次判为"已完成"时的 stdout 最后一行 —— 用来判断"之后有没有新进展"。 */
        public String lineAtDone = null;

        public void clear() {
            webStage = -1;
            webAt = 0L;
            doneAt = 0L;
            lineAtDone = null;
        }
    }

    /**
     * 判定当前阶段（**带记忆的完整版** —— 悬浮球真正在用的那个）。
     *
     * @param pageBusy 网页自己说"我在忙"（DOM 在动）。它比 CPU 采样可信得多：
     *                 CPU 抖动不代表有任务，网页在动一定是真有活儿。
     * @param memo     跨拍记忆，可为 null（null 时行为与老版本一致）
     * @param now      当前时刻（毫秒）
     */
    public static int detect(String lastLine, long lineAgeMs, boolean taskActive,
                             boolean justFinished, String serviceState,
                             int webStage, long webAgeMs, boolean pageBusy,
                             Memo memo, long now) {
        String raw = lastLine == null ? "" : lastLine.trim();
        boolean webFresh = webStage >= IDLE && webStage <= ERROR && webAgeMs <= WEB_TTL_MS;

        /*
         * ① 记忆维护放在判定之前：这一拍网页说了什么，下面几拍都要用。
         *    DONE 记下"此刻的 stdout"，用来回答"完成之后到底有没有新进展"。
         */
        if (memo != null && webFresh) {
            memo.webStage = webStage;
            memo.webAt = now;
            if (webStage == DONE) {
                memo.doneAt = now;
                memo.lineAtDone = raw;
            } else if (webStage != IDLE) {
                memo.doneAt = 0L;          // 网页重新说"在忙" → 上一次的终态作废
            }
        }

        /* ② 网页还新鲜 → 直接采信（最准的一路，不做任何本地推断） */
        if (webFresh) return webStage;

        String line = raw.toLowerCase();
        String state = serviceState == null ? "" : serviceState;
        // 陈年输出不算数：否则阶段会被一行启动日志钉死
        if (lineAgeMs > LINE_TTL_MS) line = "";

        // ③ 错误优先：只要最近输出里有失败迹象，立刻报错
        if (matchesAny(line, KEYWORDS, "ERROR")) return ERROR;

        // ④ 初始化阶段：服务状态里明确在解压/准备
        if (containsAny(state, "解压", "初始化", "准备", "挂载", "下载", "启动中", "安装")) {
            return BOOT;
        }
        if (matchesAny(line, KEYWORDS, "BOOT")) return BOOT;

        // ⑤ 编译 / 干活
        if (matchesAny(line, KEYWORDS, "COMPILING")) return COMPILING;
        if (matchesAny(line, KEYWORDS, "WORKING")) return WORKING;

        // ⑥ 收尾：明确写了完成
        if (matchesAny(line, KEYWORDS, "DONE")) {
            if (!taskActive) {
                markDone(memo, raw, now);
                return DONE;
            }
            return WORKING;
        }

        // ⑦ 任务刚结束（没有"完成"字样，但 CPU 落下来了）
        if (justFinished) {
            markDone(memo, raw, now);
            return DONE;
        }

        /*
         * ⑧ ★ 终态闸门：刚给过"已完成"，而 stdout 一个字都没再冒、网页也没说自己在忙
         *    → 这一拍的"有任务"只可能是容器 CPU 的抖动，不许把阶段拉回"思考中"。
         *    （这就是那个 2–4 秒空转的根因，见 POST_DONE_HOLD_MS 的注释。）
         */
        boolean quietSinceDone = memo != null && memo.doneAt > 0L
                && (now - memo.doneAt) <= POST_DONE_HOLD_MS
                && raw.equals(memo.lineAtDone == null ? "" : memo.lineAtDone);
        boolean withinDoneShow = memo != null && memo.doneAt > 0L
                && (now - memo.doneAt) <= DONE_SHOW_MS;

        // ⑨ 有任务在跑但输出不可归类 —— 模型正在生成 token，就是"思考中"
        if (taskActive) {
            if (!pageBusy && quietSinceDone) return withinDoneShow ? DONE : IDLE;
            return THINKING;
        }

        if (quietSinceDone && withinDoneShow) return DONE;   // 让"已完成"多站几秒
        return IDLE;
    }

    /** 记下"此刻已完成"，供后续几拍判断有没有新进展。 */
    private static void markDone(Memo memo, String raw, long now) {
        if (memo == null) return;
        memo.doneAt = now;
        memo.lineAtDone = raw;
    }

    /** 这一行是否命中某阶段的关键词。 */
    static boolean matchesAny(String line, String[][] table, String stage) {
        if (line == null || line.isEmpty()) return false;
        for (String[] row : table) {
            if (!row[0].equals(stage)) continue;
            String[] words = row[1].split("\\|");
            for (String w : words) {
                String k = w.trim();
                if (k.isEmpty()) continue;
                // "ok$" 这类带 $ 的当正则用（匹配行尾），其余按子串找 —— 更快也更好懂
                if (k.endsWith("$")) {
                    if (line.matches(".*" + java.util.regex.Pattern.quote(k.substring(0, k.length() - 1)) + "$")) {
                        return true;
                    }
                } else if (line.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAny(String s, String... keys) {
        if (s == null) return false;
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /**
     * 取某阶段的显示文案。
     *
     * <p>取值顺序（用户要求"从配置读取，便于后续扩展"）：
     * <ol>
     *   <li>`custom`（来自 SharedPreferences 的 `ball_stage_map`，形如
     *       `THINKING=让我想想|WORKING=干活中`）；</li>
     *   <li>{@link #DEFAULT_TEXT} 内置表；</li>
     *   <li>阶段名本身（永远不返回 null，气泡不会出现空白）。</li>
     * </ol>
     */
    public static String text(int stage, String custom) {
        String key = name(stage);
        String fromCustom = lookup(custom, key);
        if (fromCustom != null && !fromCustom.isEmpty()) return fromCustom;
        String fromDefault = lookup(tableToConfig(DEFAULT_TEXT), key);
        return fromDefault != null ? fromDefault : key;
    }

    /** 把内置的二维表摊平成 `K=V|K=V` 形式的配置串（便于复用同一套查找逻辑）。 */
    public static String tableToConfig(String[][] table) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : table) {
            if (sb.length() > 0) sb.append('|');
            sb.append(row[0]).append('=').append(row[1]);
        }
        return sb.toString();
    }

    /** 在 `K=V|K=V` 配置串里查一个键；查不到返回 null。 */
    public static String lookup(String config, String key) {
        if (config == null || config.isEmpty() || key == null) return null;
        for (String seg : config.split("\\|")) {
            int eq = seg.indexOf('=');
            if (eq <= 0) continue;
            if (seg.substring(0, eq).trim().equals(key)) {
                return seg.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /**
     * 解析用户配置，返回"合法条目"列表（诊断与设置界面用）。
     * 非法条目（没有 =、键为空）会被丢掉，并在这里集中暴露，便于界面提示。
     */
    public static List<String> parseConfig(String config) {
        List<String> out = new ArrayList<>();
        if (config == null || config.isEmpty()) return out;
        for (String seg : config.split("\\|")) {
            int eq = seg.indexOf('=');
            if (eq <= 0) continue;
            String k = seg.substring(0, eq).trim();
            String v = seg.substring(eq + 1).trim();
            if (k.isEmpty() || v.isEmpty()) continue;
            out.add(k + "=" + v);
        }
        return out;
    }

    /**
     * 状态去重 + 节流。
     *
     * <h3>为什么要它</h3>
     * 用户明确要求"重复状态去重、高频更新做节流"：
     * <ul>
     *   <li><b>去重</b>：阶段没变就不该改文案，否则每次轮询都 setText，
     *       一来浪费、二来会打断正在看的文字；</li>
     *   <li><b>节流</b>：stdout 可能一秒好几行，全刷会导致气泡疯狂闪。</li>
     * </ul>
     * 做成纯函数式的小状态机（不依赖 Android），方便单测。
     */
    public static final class Throttle {
        /** 同一阶段最快多久刷新一次文案（毫秒）。 */
        public static final long MIN_INTERVAL_MS = 700;

        private int lastStage = -1;
        private String lastDetail = null;
        private long lastAt = 0L;

        /**
         * @return true 表示"该刷新气泡了"
         */
        public boolean shouldUpdate(int stage, String detail, long now) {
            boolean stageChanged = (stage != lastStage);
            boolean detailChanged = (detail == null ? lastDetail != null
                                                    : !detail.equals(lastDetail));
            // 阶段变化：立刻放行（这是用户最关心的信息，不该被节流拖住）
            if (stageChanged) {
                lastStage = stage; lastDetail = detail; lastAt = now;
                return true;
            }
            // 阶段没变、明细也没变：不刷
            if (!detailChanged) return false;
            // 明细变了但还在节流窗口内：先不刷（等下一拍）
            if (now - lastAt < MIN_INTERVAL_MS) return false;
            lastDetail = detail; lastAt = now;
            return true;
        }

        /** 强制下次一定刷新（例如用户刚点开气泡）。 */
        public void reset() {
            lastStage = -1; lastDetail = null; lastAt = 0L;
        }
    }
}
