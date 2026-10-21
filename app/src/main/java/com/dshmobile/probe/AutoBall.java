package com.dshmobile.probe;

/**
 * 「悬浮球什么时候该出现」的全部判据 —— 纯逻辑，不碰 Android，便于自检。
 *
 * <h3>用户要的行为（原话）</h3>
 * "软件一旦退到后台，有任务时就自动出现；回到主界面就消失；
 *  注意不要让它在设置界面浮现出来。"
 *
 * <h3>三条规则（顺序即优先级）</h3>
 * <ol>
 *   <li><b>我们自己的界面在前台 → 一定不显示。</b>
 *       主界面和所有设置页都是本 App 的 Activity，所以这一条同时满足了
 *       "回到主界面就消失"和"别在设置界面浮现"。</li>
 *   <li><b>用户手动关掉"自动" → 常驻显示。</b>
 *       想让它一直在的用户有这个口子；默认是自动。</li>
 *   <li><b>否则：容器在干活 → 显示；空闲 → 消失。</b></li>
 * </ol>
 *
 * <h3>为什么"活跃"要有一个保持窗口</h3>
 * 一次模型调用里有大量"等网络"的空档（CPU 不动），但任务确实是进行中的。
 * 若只按瞬时 CPU 判断，球会随着每个请求间隙一闪一闪 —— 所以判定为活跃后
 * 保持 {@link #ACTIVE_WINDOW_MS} 再算空闲。
 *
 * <h3>为什么单拿出来做自检</h3>
 * 这段逻辑的错误方式全是"静默的"：漏显示 = 用户以为功能没做；
 * 多显示 = 在设置页挡路（正是用户点名不要的）。设备上又不好复现，
 * 所以把判据抽出来用真值表钉死。
 */
public final class AutoBall {

    private AutoBall() { }

    /** 判定为活跃后保持多久才算空闲。8 秒 ≈ 两轮监督循环，够跨过请求间隙。 */
    public static final long ACTIVE_WINDOW_MS = 8_000L;

    /**
     * 两次采样之间，容器进程累计消耗多少 CPU 毫秒算"在干活"。
     *
     * <p>取 30ms：空闲的容器节点进程基本是 0；而真正干活时（跑工具、写文件、
     * 处理流式响应）随便一个 10 秒窗口都会烧掉几百毫秒。定太高会漏显示
     * （用户以为坏了），定太低则等网络时也会被算成活跃（球一直不消失）。
     */
    public static final int BUSY_MS = 30;

    /**
     * 有没有任务在跑。
     *
     * @param prevCpuMs 上一次采样时容器进程的累计 CPU 毫秒
     * @param nowCpuMs  这一次的累计 CPU 毫秒
     * @param procs     这次采样命中的容器进程数（0 = 容器没在跑）
     * @param dtMs      两次采样的时间差
     */
    public static boolean busy(long prevCpuMs, long nowCpuMs, int procs, long dtMs) {
        if (procs <= 0) return false;                 // 容器没进程 = 肯定没在干活
        if (dtMs <= 0) return false;                  // 时钟没走 / 同一时刻采了两次
        return (nowCpuMs - prevCpuMs) >= BUSY_MS;
    }

    /**
     * 「真的在烧 CPU」——比 {@link #busy} 严格得多，专给**阶段**判定用。
     *
     * <h3>为什么"在干活"需要两个档（2026-10-21 实测）</h3>
     * 悬浮球阶段原来直接用 {@link #busy}（30ms 增量就算活跃）。但实测下来，
     * 容器里的 node **闲着的时候也在稳定烧几十毫秒**（它在轮询/心跳），
     * 于是气泡一直显示"思考中…" —— 明明什么都没干。
     * 日志原样：{@code DONE → THINKING / 思考中… 有任务=true}，而那一刻对话是静止的。
     *
     * <p>所以分成两档：
     * <ul>
     *   <li><b>显示球</b>用 {@link #busy}（30ms）：宁可多显示一会儿，别让球一闪一闪；</li>
     *   <li><b>判阶段</b>用这个方法（约占间隔的 20% 才算）：只有真在跑工具/编译才够格。</li>
     * </ul>
     * 真正的"在生成"由网页那一路报（见 pageStageWatch），不靠这里猜。
     */
    public static boolean heavyCpu(long prevCpuMs, long nowCpuMs, int procs, long dtMs) {
        if (procs <= 0 || dtMs <= 0) return false;
        long dcpu = nowCpuMs - prevCpuMs;
        if (dcpu <= 0) return false;
        // 下限 150ms：避免把"间隔极短"时的噪声算成重活
        return dcpu >= Math.max(150L, dtMs / 5L);
    }

    /**
     * 把"活跃时刻"记下来 —— 发现活跃就刷新时刻，否则保留原值。
     *
     * @param lastActiveAt 上次活跃时刻（0 = 从未活跃过）
     * @param now          现在
     * @return 新的"上次活跃时刻"
     */
    public static long markActive(long lastActiveAt, long now, boolean busyNow) {
        return busyNow ? now : lastActiveAt;
    }

    /** 活跃时刻是否还在保持窗口内。 */
    public static boolean stillActive(long lastActiveAt, long now) {
        if (lastActiveAt <= 0) return false;
        return (now - lastActiveAt) < ACTIVE_WINDOW_MS;
    }

    /**
     * 最终决定：悬浮球现在该不该显示。
     *
     * @param autoMode    用户是否开着"自动"（默认开）
     * @param oursInFront 我们自己的界面（主界面/任何设置页）是否在前台
     * @param taskActive  容器是否在干活（含保持窗口，见 {@link #stillActive}）
     */
    public static boolean shouldShow(boolean autoMode, boolean oursInFront, boolean taskActive) {
        // 规则 1 最大：自己的界面在前台一律不显示（含设置页）
        if (oursInFront) return false;
        // 规则 2：关掉自动 = 常驻
        if (!autoMode) return true;
        // 规则 3：有任务才显示
        return taskActive;
    }
}
