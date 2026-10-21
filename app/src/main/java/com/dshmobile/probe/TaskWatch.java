package com.dshmobile.probe;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 「容器里现在是不是真的在干活」的探测器 —— 悬浮球自动出现的判据。
 *
 * <h3>为什么需要一个专门的探测器</h3>
 * 用户要求："软件退到后台、**有任务时**自动出现悬浮球，回到主界面就消失"。
 * 那么"有任务"就必须能从 App 侧**客观**判断出来 —— 不能靠猜，
 * 也不能让用户自己去点。而 {@link DshService#getState()} 只说了
 * "dsh web 在不在跑"，**它一直在跑**（那是常驻服务），跟"有没有任务"是两回事。
 *
 * <h3>判据：容器里那几个进程在不在烧 CPU</h3>
 * DSH 真正干活（等模型、跑工具、写文件）时，容器里的 node 会跑起来；
 * 空闲时几乎不动。所以读 {@code /proc/<pid>/stat} 的 utime+stime，
 * 和上一次采样比 —— 增量超过阈值就算"有任务"。
 *
 * <p>为什么不用"网络有没有流量"：那要去读 {@code /proc/net/}，
 * 各 ROM 的可用性不一，而且我们本来就要拿 pid 去核对"是不是我们的进程"，
 * 顺手把 CPU 一起读了更省事。
 *
 * <h3>为什么"看过一次就算活跃一段时间"</h3>
 * 一次模型调用里会有大量"等网络"的空档（CPU 不动），但任务确实是进行中的。
 * 所以判定为活跃之后**保持一段时间**（{@link #ACTIVE_WINDOW_MS}）再算空闲，
 * 否则悬浮球会随着每个请求间隙一闪一闪。
 *
 * <p>纯逻辑（不碰 Android），便于自检。
 */
public final class TaskWatch {

    private TaskWatch() { }

    /** 判定为活跃后，保持多久才算空闲（8 秒）。 */
    public static final long ACTIVE_WINDOW_MS = 8_000L;

    /** 两次采样之间，容器进程消耗多少 CPU 毫秒算"在干活"。 */
    public static final int BUSY_MS = 60;

    /** 一次采样结果。 */
    public static final class Snap {
        public long at;            // 采样时刻
        public long cpuMs;         // 容器进程累计 CPU 时间（ms）
        public int procs;          // 命中的进程数

        public Snap(long at, long cpuMs, int procs) {
            this.at = at;
            this.cpuMs = cpuMs;
            this.procs = procs;
        }
    }

    /**
     * 采一次：把容器相关进程的 CPU 时间加起来。
     *
     * <p>用 {@link ProcLogic#classify} 判断"哪些进程是我们的"——
     * 和「清理残留进程」共用同一套判据，**不重复实现**（那套判据有自检兜着）。
     */
    public static Snap sample(File rootfsDir, int skipPid) {
        long total = 0;
        int n = 0;
        for (ProcLogic.Item it : ProcLogic.scanProc(rootfsDir, skipPid)) {
            long t = cpuMsOf(it.pid);
            if (t >= 0) { total += t; n++; }
        }
        return new Snap(System.currentTimeMillis(), total, n);
    }

    /**
     * 读某个进程的累计 CPU 时间（毫秒）；读不到返回 -1。
     *
     * <p>{@code /proc/<pid>/stat} 的第 14/15 个字段是 utime/stime（单位 jiffy）。
     * 解析时要注意**第 2 个字段是进程名且可能含空格与括号** ——
     * 所以不能简单 split 空格，必须从**最后一个 ')' 之后**开始数。
     * 这是解析 /proc/stat 的经典坑（内核文档也专门提过）。
     */
    public static long cpuMsOf(int pid) {
        String s = read(new File("/proc/" + pid + "/stat"));
        if (s == null) return -1;
        int rp = s.lastIndexOf(')');
        if (rp < 0 || rp + 2 >= s.length()) return -1;
        String[] f = s.substring(rp + 2).trim().split("\\s+");
        // ')' 之后第 1 个字段是 state(第3字段) → utime 是第14字段、stime 第15字段
        // 相对下标：14-3=11、15-3=12
        if (f.length <= 12) return -1;
        try {
            long utime = Long.parseLong(f[11]);
            long stime = Long.parseLong(f[12]);
            // jiffy 一般是 100Hz（10ms）—— 不追求精确，只要"活跃/不活跃"可比
            return (utime + stime) * 10L;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 由两次采样判断"有没有任务在跑"。
     *
     * @param prev 上一次采样（首次传 null → 不算活跃，避免第一次就误判）
     * @return true 表示容器在干活
     */
    public static boolean busy(Snap prev, Snap now) {
        if (prev == null || now == null) return false;
        if (now.procs == 0) return false;                 // 容器没进程 = 没在跑
        long dt = Math.max(1, now.at - prev.at);
        long dcpu = now.cpuMs - prev.cpuMs;
        if (dcpu <= 0) return false;
        // 增量换算成"这一段时间里 CPU 占了多少"：单核视角
        return dcpu >= BUSY_MS;
    }

    /**
     * 带 **pid 缓存**的采样器 —— 手机长时间挂着不发烫的关键。
     *
     * <h3>为什么不能每次都扫 /proc（2026-10-21 用户报"用久了发热严重"）</h3>
     * 老写法每 1.5 秒 {@code /proc} 全扫一遍：列出目录 → 对**每个** pid 读一次
     * {@code cmdline} → 再读 {@code stat}。一台正常手机有 400~800 个进程，
     * 也就是每分钟两三万次文件打开。数值上只占 0.5% CPU，但它是**永不停止**的，
     * 而且每次都把 CPU 从深度睡眠里叫醒 —— 屏幕黑着也照样跑，机身自然热。
     *
     * <h3>缓存怎么保证不失效</h3>
     * 容器里的进程集合变化很慢（起来就那几个：proot / dsh / node）。
     * 所以：**进程列表每 {@link #RESCAN_MS} 才重扫一次**，中间的每一拍只读
     * 那几个已知 pid 的 {@code stat}（3 个文件，而不是七八百个）。
     * 另外只要发现缓存里**一个都不剩**（容器重启过），立刻重扫，不用等那一刻。
     */
    public static final class Sampler {

        /** 进程列表多久重扫一次（毫秒）。20 秒 ≈ 13 拍。 */
        public static final long RESCAN_MS = 20_000L;

        private final File rootfsDir;
        private final int skipPid;
        private long lastScanAt = 0L;
        private int[] pids = new int[0];
        private long prevCpuMs = -1L;
        private long prevAt = 0L;

        public Sampler(File rootfsDir, int skipPid) {
            this.rootfsDir = rootfsDir;
            this.skipPid = skipPid;
        }

        /**
         * 采一次。
         *
         * @param forceRescan true 时无视缓存立刻重扫（例如容器刚重启过）
         */
        public Snap sample(boolean forceRescan) {
            long now = System.currentTimeMillis();
            boolean needScan = forceRescan || pids.length == 0
                    || (now - lastScanAt) >= RESCAN_MS;
            if (needScan) {
                java.util.List<ProcLogic.Item> list = ProcLogic.scanProc(rootfsDir, skipPid);
                pids = new int[list.size()];
                for (int i = 0; i < list.size(); i++) pids[i] = list.get(i).pid;
                lastScanAt = now;
            }
            long total = 0;
            int alive = 0;
            for (int pid : pids) {
                long t = cpuMsOf(pid);
                if (t >= 0) { total += t; alive++; }
            }
            /*
             * 缓存的 pid 全没了 = 容器被重启过。清空缓存，下一拍重扫。
             * 不这么做的话，容器重启后要等满 RESCAN_MS 才能重新"看见"它，
             * 悬浮球会在这段时间里判成"没在跑"。
             */
            if (alive == 0 && pids.length > 0) {
                pids = new int[0];
                lastScanAt = 0L;
            }
            prevCpuMs = total;
            prevAt = now;
            return new Snap(now, total, alive);
        }
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
