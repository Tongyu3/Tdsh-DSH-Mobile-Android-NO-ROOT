package com.dshmobile.probe;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 性能体检 —— 把**容器里谁在吃 CPU**拍一张快照，落到共享存储。
 *
 * <h3>为什么需要它（2026-10-21 用户报"用久了发热严重"）</h3>
 * 实测这台机器上，App 自己只占 1~2%，真正烧的是容器：
 * <pre>
 *   proot 8~9%（其中 7% 是内核态 —— proot 靠 ptrace 拦系统调用，天生有 3 倍开销）
 *   node  2~3%（DSH 本体 + 各个插件）
 * </pre>
 * 而"插件装多了"的场景里，经常有插件在**死循环重试**（界面上表现为一条反复刷新的红字，
 * 例如 {@code prompt-optimizer: Service is t…}）—— 那种情况 CPU 会一直高，
 * 但光看界面根本看不出是谁。
 *
 * <p>所以给一个一键体检：进容器跑 {@code ps} 排一遍，把前几名写进
 * {@code /sdcard/.dsh-phone/perf.txt}。用户把文件发过来，就能指名道姓地说
 * "是 X 插件在空转"，而不是笼统地说"发烫"。
 *
 * <h3>为什么不常驻监控</h3>
 * 监控本身就要 CPU —— 这类诊断只在用户点的时候跑一次，跑完就结束。
 */
public final class Perf {

    private static final String DIR = "/sdcard/.dsh-phone";
    private static final String OUT = DIR + "/perf.txt";

    private Perf() { }

    /**
     * 拍一张快照（**同步**，调用方要放工作线程）。
     *
     * @return 给人看的一句话结论（成功）或失败原因
     */
    public static String snapshot(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("== Tdsh 性能体检 ").append(new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date())).append(" ==\n");
        sb.append("App 自身：pid ").append(android.os.Process.myPid()).append('\n');
        sb.append("容器进程（App 视角）：").append(describeContainerProcs(c)).append('\n');
        sb.append("\n-- 容器里 CPU / 内存前 15 名 --\n");
        /*
         * 用 busybox/ps 的通用写法：安卓容器里没有 procps，但 ps 一般来自 busybox 或 coreutils。
         * `--sort=-pcpu` 在部分实现里不支持，所以先试带排序，失败再退回不带排序的原始输出。
         */
        String out = Env.execInContainer(c,
                "ps -eo pid,pcpu,pmem,args --sort=-pcpu 2>/dev/null | head -16"
                        + " || ps aux 2>/dev/null | head -16"
                        + " || busybox ps 2>/dev/null | head -20"
                        + " || echo '（容器里没有 ps，只能看上面的进程列表）'",
                12000L, null);
        if (out == null || out.trim().isEmpty()) {
            sb.append("（容器没在跑，或 ps 不可用）\n");
        } else {
            sb.append(out.trim()).append('\n');
        }
        sb.append("\n-- 内存 --\n");
        String mem = Env.execInContainer(c, "free -m 2>/dev/null | head -3", 8000L, null);
        if (mem != null && !mem.trim().isEmpty()) sb.append(mem.trim()).append('\n');

        String text = sb.toString();
        try {
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream os = new FileOutputStream(OUT, false)) {
                os.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable ignore) { }

        // 一句话结论：从 ps 输出里挑第一行"有数值的行"
        String top = null;
        if (out != null) {
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.toUpperCase().startsWith("PID")) continue;
                if (t.matches("^\\d+\\s+[\\d.]+.*")) { top = t; break; }
            }
        }
        return top == null ? "体检完成 → " + OUT
                : "容器里最吃 CPU 的：" + (top.length() > 90 ? top.substring(0, 90) + "…" : top)
                  + "\n明细已存 " + OUT;
    }

    /** 从 App 自己的视角列出容器进程（不依赖容器里的 ps）。 */
    private static String describeContainerProcs(Context c) {
        try {
            File rootfs = new File(Env.base(c), "rootfs");
            java.util.List<ProcLogic.Item> list =
                    ProcLogic.scanProc(rootfs, android.os.Process.myPid());
            if (list.isEmpty()) return "没找到（容器可能没在跑）";
            StringBuilder sb = new StringBuilder();
            for (ProcLogic.Item it : list) {
                sb.append(it.pid).append(' ').append(it.kind).append("；");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }

    /** 体检结果的落地路径（给界面显示用）。 */
    public static String outPath() { return OUT; }
}
