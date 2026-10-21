package com.dshmobile.probe;

import java.io.File;
import java.util.List;

/**
 * 「清理残留子进程」（用户要求：第 7 项的"解除后台子进程"）。
 *
 * <h3>这是什么、为什么需要</h3>
 * 容器里跑的是 {@code proot → env → dsh(node)} 这么一条链，node 自己还会再 fork 出
 * 一堆子进程（bash 工具、npm、插件子进程……）。正常情况下
 * {@link DshService} 停服务时会把它们带走（proot 启动参数里有 {@code --kill-on-exit}），
 * 但**异常路径**下会漏：
 * <ul>
 *   <li>App 被系统回收 / 崩过一次，父进程没了，孩子被 detach 继续活着；</li>
 *   <li>DSH 内部起了常驻子进程（本地代理那种），主命令退出时它还在；</li>
 *   <li>升级内核、改设置触发重启时，旧进程还没退干净。</li>
 * </ul>
 * 症状就是"明明没开 DSH，手机上还烫/还费电"，或者新容器起不来（3080 被占）。
 *
 * <h3>分工</h3>
 * 判断"哪些进程是我们的"那段**纯逻辑**在 {@link ProcLogic}（可离线自检，
 * 因为判错就等于误杀别的应用）；这里只做两件必须 Android 的事：
 * 读自己的 pid、发信号。
 */
public final class ProcCleanup {

    private ProcCleanup() { }

    /** 扫一遍（只读，不杀）。 */
    public static List<ProcLogic.Item> scan(File base) {
        File rootfs = base == null ? null : new File(base, "rootfs");
        return ProcLogic.scanProc(rootfs, android.os.Process.myPid());
    }

    /**
     * 把扫到的都杀掉（SIGKILL）。
     *
     * <p>为什么用 9 而不是 15：这些进程本来就是"该退没退"的残留，
     * 它们对 SIGTERM 的处理正是问题本身（可能卡在 IO 上不响应）。
     *
     * @return 实际杀掉的个数
     */
    public static int killAll(File base) {
        int n = 0;
        for (ProcLogic.Item it : scan(base)) {
            try {
                android.os.Process.sendSignal(it.pid, 9);
                n++;
            } catch (Throwable ignore) { }
        }
        return n;
    }

    /** 给人看的一段话。 */
    public static String describe(File base) {
        return ProcLogic.describe(scan(base));
    }
}
