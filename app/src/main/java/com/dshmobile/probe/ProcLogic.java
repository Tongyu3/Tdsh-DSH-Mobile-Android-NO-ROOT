package com.dshmobile.probe;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 「清理残留子进程」里那段**纯逻辑**：给一条 cmdline 分类、扫 /proc、把它变成人话。
 *
 * <h3>为什么和 {@link ProcCleanup} 分开</h3>
 * 与 {@code Relay} / {@code Providers} 的拆法完全一致：
 * 真正危险的那一步是"**判断哪些进程是我们的**"（判错 = 误杀别人的应用），
 * 而它必须能被离线自检跑到。所以这里**一行 Android API 都不碰** ——
 * 只用 {@code java.io} 读 {@code /proc/<pid>/cmdline}。
 * 杀进程那一步（{@code android.os.Process.sendSignal}）留在 {@link ProcCleanup}。
 *
 * <h3>安全边界</h3>
 * 只认这三类特征，且都必须带**绝对路径**佐证：
 * <ol>
 *   <li>cmdline 里既有 {@code proot} 又有我们 rootfs 的绝对路径（容器外壳）；</li>
 *   <li>cmdline 里既有 {@code /bin/dsh} 又有 {@code --profile}（容器里的 DSH 本体）；</li>
 *   <li>cmdline 里是我们 rootfs 下那个 {@code /opt/node-…/bin/node} 的绝对路径；
 *       —— 第一版漏了它，结果"清完还剩一个 185MB 的 node"。</li>
 * </ol>
 * 刻意**不按进程名模糊匹配**、也不用 {@code pkill}：那两条路都可能误伤
 * （虚拟屏那边已经因为"按名字杀"踩过坑）。
 */
public final class ProcLogic {

    private ProcLogic() { }

    /** 扫到的进程。 */
    public static final class Item {
        public int pid;
        public String cmd;      // 截断过的 cmdline，给人看
        public String kind;     // 中文分类，给人看
    }

    /**
     * 给一条 cmdline 分类；不是我们的容器进程就返回 null。
     *
     * @param cmd        {@code /proc/<pid>/cmdline} 的内容（NUL 已换成空格）
     * @param rootfsPath 本 App 容器 rootfs 的绝对路径（空 = 该规则不参与）
     * @param nodeBinPath 容器里 node 可执行文件的绝对路径（空 = 该规则不参与）
     */
    public static Item classify(String cmd, String rootfsPath, String nodeBinPath) {
        if (cmd == null || cmd.isEmpty()) return null;
        Item it = new Item();
        it.cmd = cmd.length() > 160 ? cmd.substring(0, 160) + "…" : cmd;
        if (cmd.contains("proot") && rootfsPath != null && !rootfsPath.isEmpty()
                && cmd.contains(rootfsPath)) {
            it.kind = "容器外壳（proot）";
            return it;
        }
        if (cmd.contains("/bin/dsh") && cmd.contains("--profile")) {
            it.kind = "DSH 本体（node）";
            return it;
        }
        if (nodeBinPath != null && !nodeBinPath.isEmpty() && cmd.contains(nodeBinPath)) {
            it.kind = "容器里的 node";
            return it;
        }
        return null;
    }

    /**
     * 扫一遍 /proc（只读）。
     *
     * @param skipPid 跳过的 pid（通常传自己的 pid）
     */
    public static java.util.List<Item> scanProc(File rootfsDir, int skipPid) {
        java.util.List<Item> out = new java.util.ArrayList<Item>();
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) return out;
        String rootfs = rootfsDir == null ? "" : rootfsDir.getAbsolutePath();
        String nodeBin = rootfs.isEmpty() ? ""
                : new File(rootfs, "opt/" + NodeInfo.NODE_DIR + "/bin/node").getAbsolutePath();
        for (File e : entries) {
            int pid;
            try { pid = Integer.parseInt(e.getName()); } catch (NumberFormatException nfe) { continue; }
            if (pid == skipPid) continue;
            String cmd = readCmdline(new File(e, "cmdline"));
            if (cmd == null) continue;
            Item it = classify(cmd, rootfs, nodeBin);
            if (it == null) continue;
            it.pid = pid;
            out.add(it);
        }
        return out;
    }

    /** 给人看的一段话（没残留时也要说清楚"没残留"）。 */
    public static String describe(java.util.List<Item> list) {
        if (list == null || list.isEmpty()) return "没有残留进程";
        StringBuilder sb = new StringBuilder("发现 ").append(list.size()).append(" 个残留进程：\n");
        for (Item it : list) {
            sb.append("  · [").append(it.pid).append("] ").append(it.kind)
              .append("\n      ").append(it.cmd).append('\n');
        }
        return sb.toString();
    }

    /** 读一个进程的 cmdline（内核线程是空的 → 返回 null）。 */
    public static String readCmdline(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").replace('\0', ' ');
        } catch (Throwable t) {
            return null;
        }
    }
}
