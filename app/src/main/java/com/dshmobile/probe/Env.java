package com.dshmobile.probe;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 容器运行环境：proot / 依赖库 / loader / dsh 启动命令的统一出口。
 *
 * 由 MainActivity（首次安装与自检）和 DshService（常驻保活）共用，
 * 避免同一套路径逻辑写两遍而漂移。
 *
 * 关于 proot 的三个必须项（都是实测踩出来的）：
 *  1. LD_LIBRARY_PATH  → proot 依赖 libtalloc.so.2 / libandroid-shmem.so
 *  2. PROOT_TMP_DIR    → 必须存在且路径里不能有 ".."
 *  3. PROOT_LOADER     → Termux 的 proot 把 loader 路径硬编码成自己的目录，
 *                        不覆盖就无法加载"解释器在 guest 内"的动态链接程序
 */
public final class Env {

    /**
     * Node 的安装目录名（解压在容器 /opt 下）。
     *
     * <p>真源在 {@link NodeInfo} —— 那边**不依赖 Android**，所以纯逻辑类
     * （{@code ProcCleanup}）可以引用它而不把 Android 拖进离线自检。
     * 这里保留一个同名别名，是免得改所有既有引用点。
     */
    public static final String NODE_DIR = NodeInfo.NODE_DIR;
    /** dsh web 的监听端口（回环）。 */
    public static final int DSH_PORT = 3080;

    /**
     * 实际使用的端口。默认 {@link #DSH_PORT}，但**被别的进程占用时会自动往后找一个**。
     *
     * 为什么需要：`dsh --port 3080` 在端口被占时起不来（EADDRINUSE），
     * 而我们的监督循环会不停地重启、永远拿不到 URL ——
     * 用户看到的就是"初始化失败 / 一直起不来"，且现象和别的原因长得一样。
     * 3080 是个很容易撞车的端口，所以这里先自己探一遍。
     */
    private static volatile int sPort = DSH_PORT;

    public static int port() { return sPort; }

    public static void setPort(int p) { sPort = p; }

    /** 从 from 开始找一个**能绑定**的回环端口；全被占就返回 from（让 dsh 自己报错）。 */
    public static int pickFreePort(int from, int to) {
        for (int p = from; p <= to; p++) {
            java.net.ServerSocket ss = null;
            try {
                ss = new java.net.ServerSocket(p, 1,
                        java.net.InetAddress.getByName("127.0.0.1"));
                return p;
            } catch (Throwable ignore) {
                // 该端口被占用，试下一个
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignore) { }
            }
        }
        return from;
    }

    /** 手机共享存储在宿主机上的真实路径。 */
    public static final String SDCARD_HOST = "/storage/emulated/0";
    /** 共享存储挂进容器后使用的路径（和安卓习惯一致）。 */
    public static final String SDCARD_GUEST = "/sdcard";
    /**
     * 用户要求的测试文件夹名。
     * 必须声明在 WORKSPACE 之前 —— Java 静态字段按声明顺序初始化，
     * 反过来的话 WORKSPACE 会拼出 "/sdcard/null"。
     */
    public static final String TEST_DIR_NAME = "dsh";
    /**
     * 默认工作区 = 手机共享存储里的 dsh 文件夹（用户指定的测试工作区）。
     *
     * 注意：因为容器跑在 DSH_PERMISSION_MODE=danger-full-access 下，
     * 工作区只是"项目目录"语义，并不会限制文件访问范围 ——
     * agent 依然可以按绝对路径访问整个 /sdcard。
     * 若想让工作区变成整个共享存储，把这里改成 SDCARD_GUEST 即可。
     */
    public static final String WORKSPACE = SDCARD_GUEST + "/" + TEST_DIR_NAME;

    /**
     * App 私有目录挂进容器后的路径（用户要求："能让 dsh 访问软件私有目录"）。
     *
     * <h3>为什么要这么做，以及为什么不用 Shizuku</h3>
     * 容器里访问不到 {@code /data/data/com.dshmobile.probe/} —— **不是权限问题，
     * 是 proot 没有挂载它**。PRoot 官方语义：未绑定的宿主路径在 guest 里根本不存在
     * （典型表现是 "No such file or directory"，而不是 EACCES）。
     *
     * <p>所以解法就是这里一行 {@code -b}，**零提权**：进程的真实 uid 就是 App 自己、
     * SELinux 上下文也是它自己的，访问自己的 filesDir 内核本来就允许；
     * proot 的 {@code -0} 只是伪装身份，不做提权。
     *
     * <p>反过来，**用 Shizuku（uid 2000）去读私有目录是做不到的**：
     * DAC（/data/data/&lt;pkg&gt; 是 0700，且 /data/data 不让 shell 穿越）加上
     * SELinux 的 per-app MLS category（{@code app_data_file} 带只有该 App 才持有的
     * category，shell 域不持有 → allow 规则在 MLS 层根本不匹配）。
     * 所以 {@code chmod 0771} 没用，而"为了给 shell 开洞把私有目录变全局可读"
     * 本身就是错的 —— 那会连 API Key、容器 rootfs、会话日志一起暴露。
     *
     * <p>刻意**不绑整个 /data**：DSH 跑在 danger-full-access 下，
     * 绑整个 /data 只会给它一堆"看着在、打不开"的路径（别的应用的私有目录），
     * 白烧 token 又没有任何用处。
     */
    public static final String APPDATA_GUEST = "/app-data";

    /** 手机控制 CLI 在容器里的安装位置。 */
    public static final String PHONE_CLI_PATH = "/usr/local/bin/phone";
    /** token 目录在容器里的挂载点（由 App 只读绑定进来）。 */
    public static final String PHONE_RUN_DIR = "/run/dsh-phone";

    private Env() { }

    /** App 沙箱内的容器根目录。 */
    public static File base(Context c) {
        File f = new File(c.getFilesDir(), "probe");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    public static File extractAsset(Context c, String name, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (InputStream in = c.getAssets().open(name);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        Os.chmod(dst.getAbsolutePath(), 0700);
        return dst;
    }

    /**
     * 安全安装一个可执行文件：先写到 <dst>.new，再 rename 覆盖。
     *
     * 为什么不能直接原地写：如果 dst 正在被执行（例如旧的 proot 进程还在跑），
     * Linux 会以 **ETXTBSY (Text file busy)** 拒绝打开它写入。
     * 这个坑实际踩到过——导致"改完 API Key 后容器永远重启不了"。
     * rename 是原子替换，旧 inode 会留给仍在运行的进程，互不影响。
     */
    public static File installAsset(Context c, String name, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(dst.getAbsolutePath() + ".new");
        //noinspection ResultOfMethodCallIgnored
        if (tmp.exists()) tmp.delete();
        try (InputStream in = c.getAssets().open(name);
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        Os.chmod(tmp.getAbsolutePath(), 0700);
        Os.rename(tmp.getAbsolutePath(), dst.getAbsolutePath());
        return dst;
    }

    /**
     * 清理残留的容器进程。
     *
     * proot 用 ptrace 托管子进程：当 proot 被杀掉时，它的 tracee（node/dsh）
     * 会被 detach 并**继续存活**，变成占着 3080 端口的孤儿进程，
     * 导致新的 dsh 起不来。所以重启前主动扫 /proc 清掉。
     * 只动本 App UID 自己的进程。
     */
    public static int killStaleDsh(File base) {
        int killed = 0;
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) return 0;
        int myPid = android.os.Process.myPid();
        String rootfsPath = new File(base, "rootfs").getAbsolutePath();
        for (File e : entries) {
            if (!e.isDirectory()) continue;
            int pid;
            try {
                pid = Integer.parseInt(e.getName());
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (pid == myPid) continue;
            String cmd = readSmall(new File(e, "cmdline"));
            if (cmd == null) continue;
            boolean isDsh = cmd.contains("/bin/dsh") && cmd.contains("--profile");
            boolean isOurProot = cmd.contains("proot") && cmd.contains(rootfsPath);
            if (isDsh || isOurProot) {
                try {
                    android.os.Process.sendSignal(pid, 9);
                    killed++;
                } catch (Throwable ignore) { }
            }
        }
        return killed;
    }

    private static String readSmall(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").replace('\0', ' ');
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 proot、它的两个依赖库、以及 loader 解到沙箱。
     * 幂等：已存在就覆盖（文件很小）。
     *
     * @return 库目录路径（供 LD_LIBRARY_PATH）
     */
    public static String prepareProot(Context c, File base) throws Exception {
        File libDir = new File(base, "lib");
        File tmpDir = new File(base, "tmp");
        File libexec = new File(base, "libexec");
        //noinspection ResultOfMethodCallIgnored
        libDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        libexec.mkdirs();

        installAsset(c, "proot", new File(base, "proot"));
        String[] libs = c.getAssets().list("libs");
        if (libs != null) {
            for (String n : libs) installAsset(c, "libs/" + n, new File(libDir, n));
        }
        installAsset(c, "loader", new File(libexec, "loader"));
        try {
            installAsset(c, "loader32", new File(libexec, "loader32"));
        } catch (Throwable ignore) {
            /* 32 位 loader 可选 */
        }
        return libDir.getAbsolutePath();
    }

    /** 注入 proot 需要的全部环境变量。 */
    public static ProcessBuilder buildProcess(File base, String[] cmd, String libDir) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (libDir != null) {
            pb.environment().put("LD_LIBRARY_PATH", libDir);
            pb.environment().put("PROOT_TMP_DIR", new File(base, "tmp").getAbsolutePath());
            /*
             * ⚠️ 这里**故意不设** PROOT_NO_SECCOMP。
             *
             * 它原本是 `1`，含义是"关掉 proot 的 seccomp 加速、只用纯 ptrace"。
             * 关掉之后**每一个系统调用**都要陷入 ptrace 再被翻译一遍，
             * 而 `dsh web` 启动时要读几百个 JS 文件 ——
             * 实测那 12 秒里绝大部分就花在这儿（见 CHANGELOG 的启动计时）。
             *
             * seccomp 加速才是 proot 的正常模式；只有当内核/SELinux 不允许
             * 时才需要退回去。真退回去会很明显：proot 会直接报
             * "ptrace acceleration (seccomp) not available" 或者容器起不来，
             * 而自检的 TEST 4/5（proot 能否运行 / 容器内 bash）会立刻抓到。
             */
            pb.environment().put("PROOT_LOADER", new File(base, "libexec/loader").getAbsolutePath());
            File l32 = new File(base, "libexec/loader32");
            if (l32.exists()) pb.environment().put("PROOT_LOADER_32", l32.getAbsolutePath());
        }
        return pb;
    }

    /**
     * 组装启动 dsh web 的完整命令。
     *
     * ⚠️ 这里用的是 `env -i`（清空继承的环境），所以 ProcessBuilder 里设的
     * DEEPSEEK_API_KEY 会被丢掉 —— API Key 必须作为参数显式传给容器内的 env。
     * 这也是 DSH 读取凭据的第一优先级来源。
     */
    public static String[] dshWebCommand(File base, String apiKey, String phoneTokenDir) {
        return dshWebCommand(base, apiKey, phoneTokenDir, null);
    }

    /** 同上，外加自定义提供方（中转站）的环境变量（见 containerCommand 的说明）。 */
    public static String[] dshWebCommand(File base, String apiKey, String phoneTokenDir,
                                        java.util.List<String> extraEnv) {
        java.util.List<String> extra = new java.util.ArrayList<>();
        extra.add("--no-open");
        extra.add("--port"); extra.add(String.valueOf(sPort));
        return dshCommand(base, apiKey, phoneTokenDir, extra, extraEnv);
    }

    /**
     * 跑 dsh 的 plugin 子命令。
     *
     * <p>⚠️ 参数顺序是 {@code dsh plugin --profile web add <包>} ——
     * {@code --profile} 必须放在子命令**之后**。
     * 一开始按 {@code dsh --profile web plugin add} 写，dsh 直接报
     * {@code error: --profile <name> is required}，看着像"没给参数"，
     * 其实是位置不对。
     */
    public static String[] dshPluginCommand(File base, String tokenDir, String... pluginArgs) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("plugin");
        args.add("--profile"); args.add("web");
        for (String s : pluginArgs) args.add(s);
        return containerCommand(base, null, tokenDir,
                "/opt/" + NODE_DIR + "/bin/dsh", args);
    }

    /**
     * 在容器里跑任意 dsh 子命令。
     *
     * <p>proot 的挂载与环境变量和 {@code dsh web} 完全一致，只有尾部参数不同 ——
     * 所以抽出来共用，避免两处各写一份 proot 参数、改一处漏一处。
     */
    public static String[] dshCommand(File base, String apiKey, String phoneTokenDir,
                                      java.util.List<String> extra) {
        return dshCommand(base, apiKey, phoneTokenDir, extra, null);
    }

    /** 同上，外加自定义提供方的环境变量。 */
    public static String[] dshCommand(File base, String apiKey, String phoneTokenDir,
                                      java.util.List<String> extra,
                                      java.util.List<String> extraEnv) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("--profile"); args.add("web");
        args.addAll(extra);
        return containerCommand(base, apiKey, phoneTokenDir,
                "/opt/" + NODE_DIR + "/bin/dsh", args, extraEnv);
    }

    /**
     * 在容器里跑任意可执行文件（proot 参数与挂载对所有命令都一样）。
     *
     * <p>抽出来的原因：{@code dsh} 和 {@code npm} 共用同一套 proot 环境，
     * 但命令行完全不同。之前图省事在 dsh 命令里塞了个占位符再替换成 npm，
     * 结果**两个可执行文件同时出现在命令行上**，dsh 先跑起来并报
     * {@code error: --profile <name> is required} —— 排查了一轮才看明白。
     * 可执行文件必须作为参数传进来，不能靠替换。
     */
    public static String[] containerCommand(File base, String apiKey, String phoneTokenDir,
                                            String exe, java.util.List<String> args) {
        return containerCommand(base, apiKey, phoneTokenDir, exe, args, null);
    }

    /**
     * 同上，外加**自定义提供方（中转站）的密钥**。
     *
     * <p>为什么要单独传进来：容器命令用的是 {@code env -i}（清空继承的环境），
     * 所以 ProcessBuilder 里设的变量一个都传不进去 —— 凭据必须显式写成
     * {@code NAME=value} 放在 {@code env -i} 之后。DSH 的 {@code apiKeyEnv} 走的是
     * "凭据引用"，它会从进程环境里解析这个名字，因此这样注入的密钥**不落盘**：
     * settings.yaml 里只有 {@code apiKeyEnv: ACME_GATEWAY_API_KEY}，密钥留在手机里。
     *
     * <p>格式是 {@code NAME=value}（{@link Providers#envPairs} 生成），
     * 名字由 Provider ID 派生（{@code acme-gateway} → {@code ACME_GATEWAY_API_KEY}），
     * 与 DSH 的派生规则一致。
     */
    public static String[] containerCommand(File base, String apiKey, String phoneTokenDir,
                                            String exe, java.util.List<String> args,
                                            java.util.List<String> extraEnv) {
        String nodePath = "/opt/" + NODE_DIR + "/bin";
        java.util.ArrayList<String> a = new java.util.ArrayList<>();
        a.add(new File(base, "proot").getAbsolutePath());
        a.add("-r"); a.add(new File(base, "rootfs").getAbsolutePath());
        a.add("-0");                       // 伪装成 root
        a.add("-w"); a.add(WORKSPACE);     // 工作目录 = 手机共享存储里的 dsh 文件夹
        /*
         * ★ 必须加 --link2symlink：
         *   Android 自 6.0 起通过 SELinux 禁止普通 App 创建硬链接
         *   （实测 App 进程自身 Os.link() 也返回 EACCES）。
         *   而 DSH 写会话日志用的是"临时文件 + 硬链接"的原子替换
         *   （@deepseek-ai/dsh-atomic-write），没有这个选项每一轮对话都会直接失败：
         *     EACCES: permission denied, link '.../session.v3.jsonl.zstd.xxx.tmp' -> '...'
         *   proot 的 --link2symlink 用符号链接模拟硬链接，正是为此场景设计
         *   （help 原文：Emulates hard links with symbolic links when SELinux
         *    policies do not allow hard links.）
         */
        a.add("--link2symlink");
        a.add("--kill-on-exit");           // 主命令退出时一并收掉残留的子进程
        a.add("-b"); a.add("/dev");
        a.add("-b"); a.add("/proc");
        a.add("-b"); a.add("/sys");
        // ★ 关键：把手机共享存储绑进容器。不绑的话容器里看不到手机上的任何文件，
        //   DSH 的"读取手机文件"就无从谈起。
        a.add("-b"); a.add(SDCARD_HOST + ":" + SDCARD_GUEST);
        a.add("-b"); a.add(SDCARD_HOST + ":" + SDCARD_HOST);
        /*
         * 手机控制桥的 token 走**只读绑定**进来，不放在共享存储 ——
         * 共享存储上任何应用都能读，token 就等于公开了。
         */
        if (phoneTokenDir != null && !phoneTokenDir.isEmpty()) {
            a.add("-b"); a.add(phoneTokenDir + ":" + PHONE_RUN_DIR);
        }
        /*
         * App 私有目录（见 APPDATA_GUEST 的说明）：一行 bind，零提权。
         * 挂在 /app-data 而不是 /data —— 别把整个 /data 递进去。
         */
        a.add("-b"); a.add(base.getAbsolutePath() + ":" + APPDATA_GUEST);
        a.add("/usr/bin/env"); a.add("-i");
        a.add("HOME=/root");
        a.add("PATH=" + nodePath + ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        a.add("LANG=C.UTF-8");
        a.add("TERM=xterm-256color");
        /*
         * ★ 必须用 danger-full-access：
         *   DSH 的 bash 工具默认（workspace-write）要求宿主机有可用的沙箱后端
         *   （bubblewrap 或 Landlock）。容器里两者都没有 —— Ubuntu base 不含
         *   bubblewrap，而 Android 内核不给普通应用用户命名空间 / Landlock。
         *   实测默认模式下每条命令都会失败：
         *     Error: sandbox mode "workspace-write" is requested but no sandbox
         *     backend is usable on this host; refusing to run the command unconfined.
         *   而本 App 的容器**本身就是一个沙箱**（proot + 应用私有目录 + 显式绑定的
         *   /sdcard），再叠一层无法启用的沙箱只会让 agent 完全不能干活。
         *   DSH 的报错信息也明确指向这个选项。
         *
         *   副作用：此模式下审批策略为 never（不再逐条弹窗确认），
         *   文件工具也不再限制在工作区内 —— 这正是"文件管理器级别访问"所需。
         *   可达范围仍限于：容器 rootfs + 手机共享存储（App 无法触及 /system
         *   与其他应用私有数据）。
         */
        a.add("DSH_PERMISSION_MODE=danger-full-access");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            a.add("DEEPSEEK_API_KEY=" + apiKey.trim());
        }
        // 中转站的密钥（见上面那段说明）：同样作为环境变量进容器，不写进任何文件
        if (extraEnv != null) {
            for (String kv : extraEnv) {
                if (kv == null || kv.trim().isEmpty()) continue;
                a.add(kv.trim());
            }
        }
        a.add(exe);
        a.addAll(args);
        return a.toArray(new String[0]);
    }

    /**
     * 给**要长期驻留**的容器服务用的容器命令：去掉 {@code --kill-on-exit}。
     *
     * <p>{@link #containerCommand} 会带上 {@code --kill-on-exit}（"主命令退出时
     * 一并收掉残留子进程"），这对一次性命令是对的 —— 但常驻服务恰恰要反过来：
     * 启动器（proot + bash）**退出了，服务还得活着**。
     *
     * <p>带上它的话，`nohup ... &` 起来的后台进程会在 bash 退出那一刻被 proot 收掉，
     * 表现就是"启动成功了，一秒后进程没了"。
     */
    public static String[] containerCommandDetached(File base, String apiKey, String tokenDir,
                                                    String exe, java.util.List<String> args) {
        String[] full = containerCommand(base, apiKey, tokenDir, exe, args);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : full) {
            if (!"--kill-on-exit".equals(s)) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 在容器里跑一段 shell，把输出取回来（**有界超时**）。
     *
     * <p>抽出来是因为现在有两处需要它：常驻服务的启停检查，和内核升级
     * （{@code npm install -g}）。以前这段逻辑散在 Services 里，
     * 升级那块再抄一份就会出现"两处 proot 参数不一致"的问题。
     *
     * @param timeoutMs 超时上限；到点就 destroy，绝不无限等
     * @param onLine    每读到一行回调一次（可为 null），用来做进度显示
     * @return 输出文本；起不来或超时返回 null
     */
    public static String execInContainer(android.content.Context c, String script,
                                         long timeoutMs,
                                         java.util.function.Consumer<String> onLine) {
        try {
            File base = base(c);
            String tokenDir = PhoneBridge.tokenDir(c);
            String apiKey = DshService.getApiKey(c);
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-lc");
            args.add(script);
            String[] cmd = containerCommandDetached(base, apiKey, tokenDir, "/bin/bash", args);
            ProcessBuilder pb = buildProcess(base, cmd, new File(base, "lib").getAbsolutePath());
            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) {
                            if (sb.length() < 200000) sb.append(line).append('\n');
                        }
                        if (onLine != null) {
                            try { onLine.accept(line); } catch (Throwable ignore) { }
                        }
                    }
                } catch (Throwable ignore) { }
            }, "dsh-exec-out");
            reader.setDaemon(true);
            reader.start();
            if (!Proc.waitFor(p, timeoutMs)) {
                p.destroy();
                android.util.Log.w("DSH_ENV", "容器命令超时（" + timeoutMs + "ms）");
                return null;
            }
            reader.join(2000L);
            synchronized (sb) { return sb.toString(); }
        } catch (Throwable t) {
            android.util.Log.w("DSH_ENV", "容器命令失败", t);
            return null;
        }
    }

    /**
     * 该不该写工作区里的 AGENTS.md。
     *
     * <p>文件不存在 → 写；
     * 存在且**是我们写的**（含固定标题）→ 覆盖成最新版；
     * 存在但不是我们写的（用户自己编辑过）→ **不碰**。
     *
     * <p>这样既保证说明书能跟着版本升级，又不会覆盖用户自己的内容。
     */
    private static boolean shouldWriteAgents(File f) {
        if (!f.exists()) return true;
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[512];
            int n = in.read(head);
            if (n <= 0) return true;          // 空文件当没写过
            String s = new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            return s.contains(AGENTS_MARKER);
        } catch (Throwable t) {
            return false;                     // 读不出来就别动它
        }
    }

    /** 我们写的那份 AGENTS.md 的固定标题，用来识别"这是我们写的"。 */
    private static final String AGENTS_MARKER = "这是手机上的工作区";

    /** 我们自带的 DSH 插件（tgz 打进 assets，首次启动自动装进 profile）。 */
    public static final String PHONE_PLUGIN_NAME = "dsh-phone";
    /*
     * ⚠️ 改这个文件名就等于"发布新版本"。
     *
     * 下面判重用的是**文件名**而不是包名 —— 因为包名永远是 dsh-phone，
     * 用它判重的话，App 升级带了新插件也永远会被当成"已安装、跳过"，
     * 用户就再也拿不到插件的更新了。
     */
    public static final String PHONE_PLUGIN_TGZ = "dsh-phone-1.0.4.tgz";
    /** 右侧栏「手机文件」插件（浏览手机存储 + 多选加进会话）。 */
    public static final String PHONE_FILES_TGZ = "dsh-phone-files-1.0.2.tgz";
    public static final String PHONE_FILES_NAME = "dsh-phone-files";
    /** 双端互通插件（狼狼做的 dsh-link）：纯 host 侧、只拨号出站，随 APK 一起装。 */
    public static final String DSLINK_TGZ = "dsh-link-1.2.3.tgz";
    public static final String DSLINK_NAME = "dsh-link";
    /**
     * 虚拟屏客户端插件（受信任虚拟屏那套；守护由群友「如月雨露」制作）。
     *
     * <p>它只负责让 DSH 里的 agent 会调虚拟屏（取帧/点按/放应用上屏），
     * 真正的两个 dex 在 {@code assets/vsd/}，由「手机权限 → 虚拟屏自动化」装起来。
     */
    public static final String VSCREEN_TGZ = "dsh-vscreen-1.1.0.tgz";
    public static final String VSCREEN_NAME = "dsh-vscreen";
    /**
     * 环境预设插件：往 systemPrompt 里注入"你在安卓手机上、在 proot 容器里"那段。
     *
     * <p>解决的是"模型以为自己在服务器上"这类反复出现的错（用 systemctl、
     * 以为有 GUI、以为 apt 能拿到真 root、不知道 phone_* 工具存在）。
     */
    public static final String ANDROID_ENV_TGZ = "dsh-android-env-1.0.0.tgz";
    public static final String ANDROID_ENV_NAME = "dsh-android-env";

    /**
     * 思考链插件：**探针 + 只读诊断**。
     *
     * <p>刻意不提供"一键删思考链"的开关 —— 上一轮实测证明包 {@code llm.stream}
     * 那条路是死的（插件看到的是门面，适配器那层拦不到）。先探明真正可用的钩子，
     * 再谈实现；在那之前给开关只会让人以为它在生效。
     */
    public static final String THINKING_TGZ = "dsh-thinking-1.0.1.tgz";
    public static final String THINKING_NAME = "dsh-thinking";
    /**
     * 语音朗读插件（界面侧）：输入框下方的小喇叭 + 设置页「语音朗读」。
     *
     * <p>声音本身不在这里 —— 安卓 WebView 没有 speechSynthesis，
     * 系统语音与云端 TTS 都由 {@link Tts} 走原生。插件只决定"读哪段、怎么读"。
     */
    public static final String TTS_TGZ = "dsh-tts-1.1.0.tgz";
    public static final String TTS_NAME = "dsh-tts";
    /**
     * 随 APK 内置的插件：{tarball 名, 包名}。
     * 加新插件只要在这里追加一行 + 跑 plugin\build-tgz.ps1。
     */
    private static final String[][] BUNDLED_PLUGINS = {
            { PHONE_PLUGIN_TGZ, PHONE_PLUGIN_NAME },
            { PHONE_FILES_TGZ, PHONE_FILES_NAME },
            { DSLINK_TGZ, DSLINK_NAME },
            { VSCREEN_TGZ, VSCREEN_NAME },
            { ANDROID_ENV_TGZ, ANDROID_ENV_NAME },
            { THINKING_TGZ, THINKING_NAME },
            { TTS_TGZ, TTS_NAME },
    };

    /**
     * 把随 APK 自带的 DSH 插件装进 profile —— 只需装一次。
     *
     * <h3>为什么不走官方的 {@code dsh plugin add}</h3>
     * 那条路内部转发给 **pnpm**，而容器里根本没有 pnpm
     * （实测 {@code /opt/node-…/bin/} 下只有 corepack / dsh / node / npm / npx）。
     * 为了装一个插件去下载整个 pnpm，既慢又多一个失败点。
     *
     * <p>而我们的插件**零依赖**，所以"装"这件事可以完全离线、确定性地做掉：
     * 把 tgz 解到 {@code profiles/web/node_modules/dsh-phone/}，
     * 再把包名写进 profile 的 {@code dsh.profile.bundles} 与 {@code dependencies}。
     * 这正是 pnpm 装完之后该有的样子。
     *
     * <p>运行期解析不用担心：dsh 会维护 {@code ~/.dsh/profiles/node_modules}
     * 作为依赖备援目录，插件里 import 的 {@code @deepseek-ai/*} 从那里解析得到。
     *
     * @return 给日志用的一句话
     */
    public static String installPhonePlugin(Context c, File base, String tokenDir, String libDir) {
        File profileDir = new File(base, "rootfs/root/.dsh/profiles/web");
        File pkgJson = new File(profileDir, "package.json");
        if (!pkgJson.exists()) {
            return "profile 还没建好，本次跳过（下次启动再装）";
        }
        /*
         * ── 快速跳过（2026-10-21，用户报"插件装多了启动特别慢"）──
         *
         * 判据改成**三样同时成立**才跳过：本版已装过 + 目录都在 + package.json 里
         * 那几条 file: 依赖也都在。这样既不会重复解包（几十个文件写盘），
         * 也不会因为 DSH 自己把 package.json 改写掉而误判成"已装好"。
         *
         * 为什么值得单独做：`readText(pkgJson)` 原来在循环里被调了 6 次，
         * 而且**每次启动**都会跑一遍；插件一多，光这一项就是几百毫秒的启动时间。
         */
        String pkgText = null;
        try {
            pkgText = readText(pkgJson);
            int vc = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            boolean markerOk = c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_BUNDLED_PLUGIN_VC, -1) == vc;
            if (markerOk && pkgText != null) {
                boolean all = true;
                for (String[] spec : BUNDLED_PLUGINS) {
                    if (!pkgText.contains(spec[0])
                            || !new File(profileDir, "node_modules/" + spec[1]).isDirectory()) {
                        all = false;
                        break;
                    }
                }
                if (all) return "自带插件已装（第 " + vc + " 版），本次跳过";
            }
        } catch (Throwable ignore) { }

        StringBuilder log = new StringBuilder("自带插件：");
        for (String[] spec : BUNDLED_PLUGINS) {
            String tgzName = spec[0], pkgName = spec[1];
            try {
                if (readText(pkgJson).contains(tgzName)) {
                    log.append(pkgName).append(" 已是最新；");
                    continue;
                }

                // ① 解包到 node_modules/<包名>
                File destDir = new File(profileDir, "node_modules/" + pkgName);
                deleteRecursively(destDir);
                if (!destDir.mkdirs()) { log.append(pkgName).append(" 建不了目录；"); continue; }

                String destPath = destDir.getCanonicalPath();
                int files = 0;
                try (InputStream raw = c.getAssets().open(tgzName);
                     java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw, 1 << 16);
                     org.apache.commons.compress.archivers.tar.TarArchiveInputStream tin =
                             new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz)) {
                    org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
                    byte[] buf = new byte[1 << 16];
                    while ((e = tin.getNextEntry()) != null) {
                        String name = e.getName();
                        if (!name.startsWith("package/")) continue;      // npm tarball 的外层目录
                        String rel = name.substring("package/".length());
                        if (rel.isEmpty()) continue;
                        File out = new File(destDir, rel);
                        if (!out.getCanonicalPath().startsWith(destPath)) continue;   // 防目录穿越
                        if (e.isDirectory()) {
                            //noinspection ResultOfMethodCallIgnored
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) //noinspection ResultOfMethodCallIgnored
                                parent.mkdirs();
                            try (OutputStream os = new FileOutputStream(out)) {
                                int n;
                                while ((n = tin.read(buf)) > 0) os.write(buf, 0, n);
                            }
                            files++;
                        }
                    }
                }

                // ② 把 tgz 也留一份在 profile 目录（dependencies 里的 file: 指向它）
                File tgzCopy = new File(profileDir, tgzName);
                try (InputStream in = c.getAssets().open(tgzName);
                     OutputStream os = new FileOutputStream(tgzCopy)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }

                // ③ 写进 profile 的 package.json：bundles 才是真正决定"加载谁"的地方
                org.json.JSONObject o = new org.json.JSONObject(readText(pkgJson));
                org.json.JSONObject deps = o.optJSONObject("dependencies");
                if (deps == null) { deps = new org.json.JSONObject(); o.put("dependencies", deps); }
                deps.put(pkgName, "file:./" + tgzName);

                org.json.JSONObject dsh = o.optJSONObject("dsh");
                if (dsh == null) { dsh = new org.json.JSONObject(); o.put("dsh", dsh); }
                org.json.JSONObject profile = dsh.optJSONObject("profile");
                if (profile == null) { profile = new org.json.JSONObject(); dsh.put("profile", profile); }
                org.json.JSONArray bundles = profile.optJSONArray("bundles");
                if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
                boolean has = false;
                for (int i = 0; i < bundles.length(); i++) {
                    if (pkgName.equals(bundles.optString(i))) { has = true; break; }
                }
                if (!has) bundles.put(pkgName);

                try (OutputStream os = new FileOutputStream(pkgJson)) {
                    os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                log.append(pkgName).append(" 已装入（").append(files).append(" 个文件）；");
            } catch (Throwable t) {
                log.append(pkgName).append(" 失败: ").append(t).append("；");
            }
        }
        /*
         * 记下"本版本已经装过了"，下次启动直接走快速跳过。
         * 只在**整轮没出错**时写入：某个插件装失败的话，下次还要重试。
         */
        try {
            if (log.indexOf("失败") < 0) {
                int vc = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
                c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_BUNDLED_PLUGIN_VC, vc).apply();
            }
        } catch (Throwable ignore) { }
        return log.toString();
    }

    /** 自带插件的"本版本已装过"标记键（见 {@link #installPhonePlugin}）。 */
    private static final String KEY_BUNDLED_PLUGIN_VC = "bundled_plugin_vc";

    /**
     * 把「桌宠」插件从容器里摘掉 —— 它的宿主已经被删了，留着只会白烧电。
     *
     * <h3>为什么必须做（2026-10-21 用户报"用久了发烫"）</h3>
     * 桌宠的显示层（{@code PetOverlayHost}）已经按用户要求删掉了，但**插件本身还在容器里**：
     * 它每隔一两百毫秒改一次 {@code /sdcard/.dsh-phone/pet-overlay.json}，等宿主来读。
     * 宿主没了，这些写盘就全是白费 —— 而每一次写都要穿过 proot（ptrace 拦截，
     * 系统调用成本约 3 倍），正好是持续发热的来源之一。
     * 实测证据：每次 DSH 启动后那份 json 的修改时间都会刷新。
     *
     * <p>所以从 profile 的 {@code dependencies} 与 {@code dsh.profile.bundles} 里摘掉
     * {@code dsh-pet*} 这两个字段。**只动这两个名字**，别的插件一个不碰；
     * 改之前把原文件备份成 {@code package.json.bak-pet}，用户想恢复随时能恢复。
     *
     * @return 给人看的一句话；没什么可做时返回 null
     */
    public static String disablePetPlugin(File base) {
        File profileDir = new File(base, "rootfs/root/.dsh/profiles/web");
        File pkgJson = new File(profileDir, "package.json");
        if (!pkgJson.exists()) return null;
        try {
            String text = readText(pkgJson);
            org.json.JSONObject o = new org.json.JSONObject(text);
            boolean changed = false;

            org.json.JSONObject deps = o.optJSONObject("dependencies");
            if (deps != null) {
                java.util.List<String> drop = new java.util.ArrayList<>();
                java.util.Iterator<String> it = deps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.contains("dsh-pet")) drop.add(k);
                }
                for (String k : drop) { deps.remove(k); changed = true; }
            }

            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject profile = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray bundles = profile == null ? null : profile.optJSONArray("bundles");
            if (bundles != null) {
                org.json.JSONArray keep = new org.json.JSONArray();
                for (int i = 0; i < bundles.length(); i++) {
                    String n = bundles.optString(i, "");
                    if (n.contains("dsh-pet")) { changed = true; continue; }
                    keep.put(n);
                }
                if (changed) profile.put("bundles", keep);
            }
            if (!changed) return null;

            // 备份（只在第一次覆盖时留，别把原备份冲掉）
            File bak = new File(profileDir, "package.json.bak-pet");
            if (!bak.exists()) {
                try (java.io.OutputStream os = new java.io.FileOutputStream(bak)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            try (java.io.OutputStream os = new java.io.FileOutputStream(pkgJson)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return "已从容器里摘掉桌宠插件（省电；原配置备份在 package.json.bak-pet）";
        } catch (Throwable t) {
            return "摘桌宠插件失败（不影响其它功能）：" + t;
        }
    }

    // ══════════ 插件自救：停用某个插件 / 安全模式 ══════════
    //
    // 【为什么必须有这套东西】真机事故：用户装了两个**为 DSH 0.1.x 写的**第三方插件
    // （dsh-speech / dsh-mobile-gateway），它们的客户端声明
    // `inject = ["slots", "settingsScope"]` —— 而 `settingsScope` 这个服务名在 DSH 0.2
    // 里已经改成了 settings / settingsSchema。服务等不到，前端**永远停在 pending**，
    // 整个 GUI 起不来（白屏或 "Failed to load plugins"）。
    //
    // 而 App 在"服务就绪"后会把状态栏收起来 —— 用户连「重试」都点不到，
    // 唯一出路是卸载重装（等于把 150MB 容器再下一遍）。
    //
    // 所以要有"从 App 侧把坏插件摘掉"的能力：直接改 profile 的 bundles
    // （那才是真正决定加载谁的地方），被摘掉的记在 App 自己的 prefs 里，可一键恢复。

    /** 安全模式只加载这些：两个官方包 + 自带插件。 */
    private static final String[] SAFE_BUNDLES = {
            "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app",
            PHONE_PLUGIN_NAME, PHONE_FILES_NAME
    };

    /**
     * 安全名单（官方 + 自带）—— 给 {@code PluginMsg.parse} 和 MainActivity 共用。
     *
     * <p>只此一份：以前 MainActivity 里另抄了一遍同样的四个名字，
     * 改名单时两边容易走散。
     */
    public static java.util.List<String> safeBundles() {
        return java.util.Arrays.asList(SAFE_BUNDLES);
    }

    private static final String KEY_DISABLED = "disabled_bundles";
    /** 停用时把"依赖声明"也记下来（恢复时要照原样写回 dependencies）。 */
    private static final String KEY_DISABLED_SPECS = "disabled_bundle_specs";
    /** 被停用的插件包挪到这里 —— 目录都不在了，DSH 就没法把它加载回来。 */
    private static final String DISABLED_DIR = "node_modules/.dsh-disabled";

    /** profile 的 package.json —— 决定"加载哪些插件"的就是它。 */
    public static File profilePkg(Context c) {
        return new File(base(c), "rootfs/root/.dsh/profiles/web/package.json");
    }

    /** profile 的 patch 层 —— 模型路由（provider / model）就写在这里面。 */
    private static File profilePatch(Context c) {
        return new File(base(c), "rootfs/root/.dsh/profiles/web/cordis.patch.yml");
    }

    /**
     * 当前配的模型路由（provider / model），给报告用。
     *
     * <p>为什么要记：用户反馈"压缩上下文时报
     * {@code gateway/internal: …imageRequestPricing is not a function}"。
     * 追到 DSH 内核后发现，这个错只会在**某个 provider 的适配器没实现
     * {@code imageRequestPricing}** 时出现（DSH 自带的两个适配器都实现了，
     * 详见 CHANGELOG v0.2.8.3）。所以"这台机器配的是哪个 provider"就是定位的关键 ——
     * 它写在 {@code cordis.patch.yml} 里，App 直接读得到，顺手记进报告。
     *
     * <p>**只记 provider / model 这类标识，不碰任何凭据**（凭据在
     * {@code .credentials.yaml}，我们不读它）。
     */
    public static String modelRoutes(Context c) {
        try {
            File f = profilePatch(c);
            if (!f.exists()) return "（读不到 cordis.patch.yml）";
            java.util.List<String> routes = new java.util.ArrayList<>();
            String provider = null;
            String model = null;
            for (String raw : readText(f).split("\n")) {
                String line = raw.trim();
                if (line.startsWith("provider:")) {
                    if (provider != null) routes.add(provider + "/" + (model == null ? "?" : model));
                    provider = unquote(line.substring("provider:".length()));
                    model = null;
                } else if (line.startsWith("model:") && provider != null) {
                    model = unquote(line.substring("model:".length()));
                }
            }
            if (provider != null) routes.add(provider + "/" + (model == null ? "?" : model));
            return routes.isEmpty() ? "（没写 provider，用默认）" : String.join("、", routes);
        } catch (Throwable t) {
            return "（读取失败：" + t.getClass().getSimpleName() + "）";
        }
    }

    /**
     * {@code cordis.patch.yml} 里"插"进来的插件（App 停不了它们，只能报给用户看）。
     *
     * <h3>为什么必须把它们摊出来</h3>
     * "加载哪些插件"**不止** profile 的 {@code dsh.profile.bundles} 一处（DSH 0.2 的
     * profile 是分层 patch 组合出来的）：
     * <pre>
     *   ~/.dsh/profiles/web/package.json   dsh.profile.bundles   ← App 一直只认这里
     *   ~/.dsh/profiles/web/cordis.patch.yml   - insert: [{id, name}]  ← 也能挂插件
     *   ~/.dsh/cordis.patch.yml                同上，而且对**所有** profile 生效
     * </pre>
     * 用户反馈过"界面起不来，但 profile 里只有 4 个插件、没有第三方可停"——
     * 因为插件是写在 patch 层里的：App 的自救看不到它们，那句"问题不在插件上"
     * 就成了误导。这里把名字读出来放进面板/日志，至少让排查有的放矢。
     *
     * <p>**只读、不改**：动 patch 层要理解 DSH 的组合规则（insert / id 定向覆盖 /
     * {@code !!js} 表达式），猜错了会把用户整个 profile 弄坏 —— 那不是 App 该做的，
     * 所以只在面板里如实说"要停得自己改那个文件"。
     *
     * @return "、" 连接的包名（没有则空串）
     */
    public static String patchLayerPlugins(Context c) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try {
            for (File f : new File[]{ profilePatch(c), homePatch(c) }) {
                if (f == null || !f.exists()) continue;
                for (String raw : readText(f).split("\n")) {
                    String line = raw.trim();
                    if (line.startsWith("- ")) line = line.substring(2).trim();
                    if (!line.startsWith("name:")) continue;
                    String v = unquote(line.substring("name:".length()));
                    // package.json / YAML 里可能出现别的 name:（比如系统提示词的名字），
                    // 只留下"像 npm 包名、又不是官方那几件"的
                    if (v.isEmpty() || v.contains(" ") || !looksLikePackageName(v)) continue;
                    if (SAFE_BUNDLES.length > 0 && safeBundles().contains(v)) continue;
                    names.add(v);
                }
            }
        } catch (Throwable ignore) { }
        return String.join("、", names);
    }

    /** 家目录那一层 patch（对所有 profile 生效）。 */
    private static File homePatch(Context c) {
        return new File(base(c), "rootfs/root/.dsh/cordis.patch.yml");
    }

    private static String unquote(String s) {
        String v = s == null ? "" : s.trim();
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                && v.charAt(v.length() - 1) == v.charAt(0)) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 当前 profile 里登记的 bundle 列表（读不到返回空表）。 */
    public static java.util.List<String> profileBundles(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            File f = profilePkg(c);
            if (!f.exists()) return out;
            org.json.JSONObject dsh = new org.json.JSONObject(readText(f)).optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray b = prof == null ? null : prof.optJSONArray("bundles");
            for (int i = 0; b != null && i < b.length(); i++) out.add(b.optString(i));
        } catch (Throwable ignore) { }
        return out;
    }

    /**
     * 从**磁盘上**找出所有可能是"肇事者"的第三方插件名字。
     *
     * <h3>为什么不能只看 profile.bundles（真机事故）</h3>
     * 用户（小米 25091RP04C）装了个插件之后 App 直接进不去。日志里点名的是
     * `dsh-agnes-multimodal-fixed`，但那个名字**不在 profile.bundles 里** ——
     * 它是通过 `cordis.patch.yml` 或别的路径被加载的。
     * 结果自动自救连续两次都"停用 0 个"（因为 {@link #disableBundles} 当时只认
     * bundles 里的名字），撞到上限后放弃，用户就一直卡在报错页。
     *
     * <p>所以这里把"可能加载插件的三个地方"都扫一遍：
     * <ol>
     *   <li>profile 的 `package.json` → `dsh.profile.bundles`（正常安装的插件在这里）；</li>
     *   <li>`cordis.patch.yml` 全文里的插件名（正则捞 `@scope/name` 与 `dsh-xxx`）；</li>
     *   <li>`node_modules/` 下的实际包目录（前面两处都没登记、但文件确实躺在那儿）。</li>
     * </ol>
     * 官方包与自带插件（{@link #SAFE_BUNDLES}）一律排除 —— 停它们没有意义。
     *
     * <p>纯文件读取，不改任何东西；调用方决定要停谁。
     */
    public static java.util.List<String> discoverThirdParty(Context c) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            // ① profile.bundles
            for (String b : profileBundles(c)) if (!isSafeBundle(b)) out.add(b);

            // ② cordis.patch.yml 里出现的**包名** + 每个条目的 **id**
            //
            // 为什么 id 也要收：有的插件是 `- id: agnes-multimodal` 这样挂进来的，
            // id 里没有包名，只按包名过滤会漏掉它 —— 而漏掉就是"重启多少次都没用"。
            // `llm-pi-ai` 是我们自己写的中转站条目，绝不能停（摘了模型配置就没了）。
            try {
                File patch = profilePatch(c);
                if (patch.exists()) {
                    String yml = readText(patch);
                    for (String n : PluginMsg.pluginNamesIn(yml, safeBundles())) {
                        out.add(n);
                    }
                    for (String id : PluginMsg.entryIdsIn(yml)) {
                        if (Relay.PI_AI_ENTRY_ID.equals(id)) continue;   // 中转站那条，永远不动
                        if (!isSafeBundle(id)) out.add(id);
                    }
                }
            } catch (Throwable ignore) { }

            // ③ node_modules 下的实际目录
            //
            // ⚠️ 这里**必须**用 looksLikeDshPlugin 过滤，不能"见到目录就当插件"。
            // 上一版就是那样：第三方插件自带的**普通依赖**（lodash 之类）也会被当成
            // "插件"收进来，于是面板上冒出几百个根本不是插件的东西。
            // 用户报的"怎么这么多插件"里，这是 App 侧唯一能产生大数字的地方
            // （另一个"200 多个"是 DSH 自己的官方依赖包，见 docs/第三方插件清单.md）。
            try {
                File nm = new File(profilePkg(c).getParentFile(), "node_modules");
                File[] kids = nm.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        String n = k.getName();
                        if (n.startsWith(".")) continue;            // .bin / .dsh-disabled / .pnpm
                        if (n.startsWith("@")) {                     // scope 目录
                            File[] sub = k.listFiles();
                            if (sub == null) continue;
                            for (File s : sub) {
                                String full = n + "/" + s.getName();
                                if (isSafeBundle(full)) continue;
                                if (looksLikeDshPlugin(s)) out.add(full);
                            }
                        } else if (!isSafeBundle(n) && looksLikeDshPlugin(k)) {
                            out.add(n);
                        }
                    }
                }
            } catch (Throwable ignore) { }
        } catch (Throwable ignore) { }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 这个 node_modules 目录到底是不是一个 **DSH 插件**。
     *
     * <p>判据（任一成立即可，全部来自 DSH 的发布约定）：
     * <ul>
     *   <li>{@code keywords} 里有 {@code dsh-plugin} —— 官方发现机制就是找这个标签；</li>
     *   <li>声明了 {@code dsh.bundle}（host 侧 patch）；</li>
     *   <li>声明了 {@code dsh.client}（浏览器侧注入）。</li>
     * </ul>
     *
     * <p>为什么要判：第三方插件会把自己的普通依赖装进**同一个** node_modules，
     * 那些包不是插件，不该出现在插件管理里，更不该被安全模式当成可疑对象。
     */
    private static boolean looksLikeDshPlugin(File dir) {
        try {
            File pj = new File(dir, "package.json");
            if (!pj.isFile() || pj.length() > 512 * 1024) return false;
            String t = readText(pj);
            if (t == null) return false;
            org.json.JSONObject o = new org.json.JSONObject(t);
            org.json.JSONArray kw = o.optJSONArray("keywords");
            for (int i = 0; kw != null && i < kw.length(); i++) {
                if ("dsh-plugin".equals(kw.optString(i))) return true;
            }
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            return dsh != null && (dsh.has("bundle") || dsh.has("client"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是不是"官方包 / 自带插件"（这些不该被安全模式停掉）。 */
    private static boolean isSafeBundle(String name) {
        if (name == null || name.isEmpty()) return true;
        for (String s : SAFE_BUNDLES) if (s.equals(name)) return true;
        // 官方两个包的任何子路径也算官方
        return name.startsWith("@deepseek-ai/");
    }

    /** 被我们停用的 bundle（存 App 的 prefs，不往 DSH 的配置里塞自定义字段）。 */
    public static java.util.List<String> disabledBundles(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String s = c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DISABLED, "");
            if (s != null && !s.isEmpty()) {
                org.json.JSONArray a = new org.json.JSONArray(s);
                for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    private static void setDisabled(Context c, java.util.List<String> list) {
        try {
            c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DISABLED, new org.json.JSONArray(list).toString()).apply();
        } catch (Throwable ignore) { }
    }

    /** 安全模式是否生效中（即：有插件被我们摘掉了）。 */
    public static boolean safeModeActive(Context c) {
        return !disabledBundles(c).isEmpty();
    }

    /**
     * 某个被停用的插件，**它的文件现在在哪**（给「插件安全模式」页展示）。
     *
     * <p>为什么要把这件事摊给用户看：用户反馈"开了安全模式之后插件直接没了，
     * 但文件还在"。真相是 —— 我们只把 {@code node_modules/<包名>} 挪进了
     * {@code node_modules/.dsh-disabled/}（所以 DSH 加载不到它了），
     * 而 **pnpm 的共享商店 {@code node_modules/.pnpm/} 里还留着一份真身**：
     * 那是包管理器自己的缓存，不跟着我们挪。所以"文件还在"是正常的，
     * 但用户看不到这个解释，就会以为没停干净或者恢复不了。
     */
    public static String pluginFileState(Context c, String name) {
        try {
            File nm = new File(profilePkg(c).getParentFile(), "node_modules");
            File grave = new File(new File(nm, ".dsh-disabled"), name);
            File orig = new File(nm, name);
            int store = pnpmStoreCopies(nm, name);
            StringBuilder sb = new StringBuilder();
            // exists() 会跟进链接：链接还在、目标没了时它也报 false，所以单独认一下
            sb.append(grave.exists() ? "已挪到 .dsh-disabled"
                    : (isSymlink(grave) ? "坟场里有个失效的链接" : "坟场里没有"));
            sb.append(" · 原位").append(orig.exists() ? "还在" : "已不在");
            if (store > 0) sb.append(" · pnpm 商店里还有 ").append(store).append(" 份");
            return sb.toString();
        } catch (Throwable t) {
            return "读不到文件状态";
        }
    }

    /** pnpm 商店里与这个包有关的目录数（也就是"文件还在"的那些）。 */
    private static int pnpmStoreCopies(File nodeModules, String name) {
        try {
            File store = new File(nodeModules, ".pnpm");
            if (!store.isDirectory()) return 0;
            String flat = name.replace('/', '+');          // @scope/name → @scope+name
            File[] kids = store.listFiles();
            if (kids == null) return 0;
            int n = 0;
            for (File k : kids) {
                String kn = k.getName();
                if (kn.equals(flat) || kn.startsWith(flat + "@")) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 彻底删除所有"被停用"的插件：坟场里的、原位的、以及 pnpm 商店里的副本，
     * 并把 App 里的停用记录一并抹掉。
     *
     * <p>这是**不可逆**操作（用户要的就是"别留文件"），所以界面上必须先确认。
     *
     * <h3>三处不能想当然的地方（都是复查时发现并改掉的）</h3>
     * <ol>
     *   <li><b>范围校验不能用 {@code getCanonicalPath()}</b>：pnpm 装出来的
     *       {@code node_modules/<包名>} 常常是**符号链接**，canonical 会解析到链接目标 ——
     *       目标在 node_modules 内时能过（走 {@code .pnpm/}），
     *       目标在外时（{@code file:../xxx}）就被判成"越界"而整条跳过。
     *       现在改成校验**父目录**（不解析叶子），叶子交给符号链接安全的删除去处理；
     *       跨目录的名字则由 {@link #looksLikePackageName} 挡掉。</li>
     *   <li><b>删不掉的项必须留在"待恢复"名单里</b>：早先的写法是无论成功失败都
     *       把停用记录清空 —— 万一某个包没删掉（名字畸形 / 权限），用户就得到
     *       "文件还在、但恢复入口没了"，正是这次要修的那种状态。</li>
     *   <li><b>只有真的删掉了，才从 profile 里抹掉名字</b>，否则那个包既不在
     *       bundle 列表里、文件又还在，谁也说不清它到底算不算装过。</li>
     * </ol>
     *
     * @return 真正处理掉的插件个数
     */
    public static int purgeDisabledPlugins(Context c) {
        java.util.List<String> dis = disabledBundles(c);
        if (dis.isEmpty()) return 0;
        java.util.List<String> purged = new java.util.ArrayList<>();
        java.util.List<String> kept = new java.util.ArrayList<>();
        try {
            File nm = new File(profilePkg(c).getParentFile(), "node_modules");
            File grave = new File(nm, ".dsh-disabled");
            File store = new File(nm, ".pnpm");
            String nmCanon = nm.getCanonicalPath() + File.separator;
            for (String name : dis) {
                try {
                    if (!looksLikePackageName(name)) {
                        android.util.Log.w("DSH_SAFEMODE", "包名不像包名，保留记录不动：" + name);
                        kept.add(name);
                        continue;
                    }
                    File target = new File(nm, name);
                    File graveTarget = new File(grave, name);
                    // 只看父目录：既挡住 ../.. 这类越界，又不会因为叶子是符号链接而误判
                    File p1 = target.getParentFile();
                    File p2 = graveTarget.getParentFile();
                    if (p1 == null || p2 == null
                            || !p1.getCanonicalPath().startsWith(nmCanon)
                            || !p2.getCanonicalPath().startsWith(nmCanon)) {
                        android.util.Log.w("DSH_SAFEMODE", "路径越界，保留记录不动：" + name);
                        kept.add(name);
                        continue;
                    }
                    deleteRecursively(graveTarget);                // 坟场里那份（是链接就只摘链接）
                    deleteRecursively(target);                     // 万一没挪走
                    String flat = name.replace('/', '+');
                    File[] kids = store.listFiles();
                    if (kids != null) {
                        for (File k : kids) {
                            String kn = k.getName();
                            if (kn.equals(flat) || kn.startsWith(flat + "@")) {
                                deleteRecursively(k);              // pnpm 商店里那份
                            }
                        }
                    }
                    purged.add(name);
                    android.util.Log.i("DSH_SAFEMODE", "已彻底删除 " + name);
                } catch (Throwable e) {
                    android.util.Log.w("DSH_SAFEMODE", "删除失败，保留记录：" + name, e);
                    kept.add(name);
                }
            }
            // 只有删成功的才清记录；没删掉的仍旧留在"待恢复"里（用户还能恢复或再试）
            setDisabled(c, kept);
            try {
                String specs = c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_DISABLED_SPECS, "{}");
                org.json.JSONObject sj = new org.json.JSONObject(specs);
                for (String x : purged) sj.remove(x);
                c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_DISABLED_SPECS, sj.toString()).apply();
            } catch (Throwable ignore) { }
            // profile 里也只抹掉真的删掉了的那些
            try {
                File f = profilePkg(c);
                if (f.exists()) {
                    org.json.JSONObject o = new org.json.JSONObject(readText(f));
                    org.json.JSONObject dsh = o.optJSONObject("dsh");
                    org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
                    org.json.JSONArray b = prof == null ? null : prof.optJSONArray("bundles");
                    if (b != null) {
                        org.json.JSONArray nb = new org.json.JSONArray();
                        for (int i = 0; i < b.length(); i++) {
                            if (!purged.contains(b.optString(i))) nb.put(b.optString(i));
                        }
                        prof.put("bundles", nb);
                    }
                    org.json.JSONObject deps = o.optJSONObject("dependencies");
                    if (deps != null) for (String n : purged) deps.remove(n);
                    try (OutputStream os = new FileOutputStream(f)) {
                        os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            android.util.Log.w("DSH_SAFEMODE", "彻底删除失败", t);
        }
        return purged.size();
    }

    public static void report(Context c, String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (Env.class) {
            try (OutputStream os = new FileOutputStream(new File(base(c), "report.txt"), true)) {
                os.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignore) {
                // 报告写不进去不能影响主流程（磁盘满、目录被删都可能）
            }
        }
    }

    /**
     * 把运行报告（report.txt）末尾若干行复制到剪贴板。
     *
     * <p>存在的意义：App 的大量现场诊断都写在这个文件里 ——
     * 界面白屏后的自动重连、附件为什么没进去、自带插件的安装结果……
     * 而它在 App 私有目录里，用户既看不见也导不出来。
     * 没有这个入口，"让用户把日志发我"就只是一句空话。
     *
     * @return 给界面显示的一句话（成功/失败原因）
     */
    public static String copyReportToClipboard(Context c, int maxLines) {
        File f = new File(base(c), "report.txt");
        if (!f.exists()) return "还没有日志文件";
        try {
            // 文件可能很大（几十万行），从尾部倒着攒够行数就停
            java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
            try (java.io.BufferedReader br =
                         new java.io.BufferedReader(new java.io.InputStreamReader(
                                 new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.addLast(line);
                    while (lines.size() > maxLines) lines.removeFirst();
                }
            }
            String text = String.join("\n", lines);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "这台设备不支持剪贴板";
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH 日志", text));
            return "已复制最近 " + lines.size() + " 行日志，粘贴发出来即可";
        } catch (Throwable t) {
            return "复制失败：" + t;
        }
    }

    /**
     * 【仅调试用】往 profile 的 bundles 里塞一个**不存在的包名**。
     *
     * <p>为什么需要它：真机事故里罪魁祸首是第三方插件，而这台测试机上
     * profile 只有官方两个包 + 自带的 dsh-phone（后者我特意放进安全名单），
     * 于是"停用第三方插件"这条路径永远是空集、测不到。
     * 塞一个假包名就能复现出**和真机一样的启动失败**（DSH 会报
     * "did not activate / waiting for service"），从而把
     * 「前端自检 → 自救面板 → 摘插件 → 重启恢复」整条链路跑通。
     *
     * @return 给界面看的一句话
     */
    public static String debugAddFakeBundle(Context c, String name) {
        try {
            File f = profilePkg(c);
            if (!f.exists()) return "profile 还不存在";
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) return "profile 结构不对";
            org.json.JSONArray b = prof.optJSONArray("bundles");
            if (b == null) { b = new org.json.JSONArray(); prof.put("bundles", b); }
            for (int i = 0; i < b.length(); i++) {
                if (name.equals(b.optString(i))) return "里面已经有 " + name + " 了";
            }
            b.put(name);
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return "已加入 " + name + "（现在共 " + b.length() + " 个 bundle）";
        } catch (Throwable t) {
            return "加入失败: " + t;
        }
    }

    /** 【仅调试用】把调试塞进去的假包名彻底移除（bundles + 待恢复清单）。 */
    public static String debugRemoveFakeBundle(Context c, String name) {
        int n = disableBundles(c, java.util.Arrays.asList(name));
        java.util.List<String> dis = disabledBundles(c);
        if (dis.remove(name)) setDisabled(c, dis);
        return "移除 " + name + "（bundles 命中 " + n + " 个）";
    }

    /**
     * 从 profile 的 bundles 里摘掉指定的几个。
     *
     * @return 实际摘掉的个数（0 表示这几个本来就不在，或 profile 还没建好）
     */
    public static int disableBundles(Context c, java.util.List<String> names) {
        if (names == null || names.isEmpty()) {
            android.util.Log.i("DSH_SAFEMODE", "disableBundles: 名字为空，不动");
            return 0;
        }
        try {
            File f = profilePkg(c);
            android.util.Log.i("DSH_SAFEMODE", "disableBundles(" + names + ") 文件=" + f
                    + " 存在=" + f.exists());
            if (!f.exists()) return 0;
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) return 0;
            org.json.JSONArray b = prof.optJSONArray("bundles");
            if (b == null) return 0;

            java.util.List<String> keep = new java.util.ArrayList<>();
            java.util.List<String> off = new java.util.ArrayList<>();
            for (int i = 0; i < b.length(); i++) {
                String name = b.optString(i);
                if (names.contains(name)) off.add(name); else keep.add(name);
            }

            /*
             * ⚠️ 关键修复（2026-10-05）：**除了 bundles 里点名的，还要处理"只在别处被引用"的插件**。
             *
             * 原来的写法是 `if (off.isEmpty()) return 0;` —— 也就是"这个名字不在 profile.bundles
             * 里就什么都不做"。真机事故（小米 25091RP04C，用户装插件后直接进不去）：
             * 肇事插件是通过 `cordis.patch.yml` / 别的路径被加载的，压根不在 bundles 里，
             * 于是自动自救连续两次都"停用 0 个"，撞到上限后彻底放弃，用户就一直卡在报错页。
             *
             * 现在按两部分处理，**互不依赖**：
             *   · off     —— 在 bundles 里 → 从 bundles/dependencies 摘掉（原声明记下来）；
             *   · alsoOff —— 不在 bundles 里，但**名字是我们确认该停的** → 只挪包目录。
             * 挪目录这一招不依赖任何配置文件，只要包目录不在了，它就没法被加载。
             */
            java.util.List<String> alsoOff = new java.util.ArrayList<>();
            for (String n : names) {
                if (!off.contains(n) && !keep.contains(n)) alsoOff.add(n);
            }
            if (off.isEmpty() && alsoOff.isEmpty()) return 0;
            java.util.List<String> allOff = new java.util.ArrayList<>(off);
            allOff.addAll(alsoOff);

            org.json.JSONArray nb = new org.json.JSONArray();
            for (String k : keep) nb.put(k);
            prof.put("bundles", nb);

            /*
             * ⚠️ 只改 bundles **不够** —— 用户实测"停用不生效"：
             * DSH 还会按自己的库存/依赖把包加载回来。所以这里同时做两件事：
             *   1) 从 dependencies 里摘掉（原声明记下来，恢复时照原样写回）；
             *   2) 把 node_modules/<包名> 整个挪到 .dsh-disabled/ 下 ——
             *      包目录都不在了，它绝无可能被加载。
             * 挪走而不是删除，所以能一键挪回来（这就是"回退到装插件之前"）。
             */
            java.util.Map<String, String> specs = new java.util.HashMap<>();
            org.json.JSONObject deps = o.optJSONObject("dependencies");
            File nodeModules = new File(profilePkg(c).getParentFile(), "node_modules");
            File grave = new File(nodeModules, ".dsh-disabled");
            for (String n : allOff) {
                try {
                    if (deps != null && deps.has(n)) { specs.put(n, deps.optString(n)); deps.remove(n); }
                    File from = new File(nodeModules, n);
                    if (from.exists()) {
                        File to = new File(grave, n);
                        File toParent = to.getParentFile();
                        if (toParent != null && !toParent.exists()) //noinspection ResultOfMethodCallIgnored
                            toParent.mkdirs();
                        if (!grave.exists()) //noinspection ResultOfMethodCallIgnored
                            grave.mkdirs();
                        deleteRecursively(to);
                        boolean moved = from.renameTo(to);
                        android.util.Log.i("DSH_SAFEMODE", "挪走包目录 " + n + " → " + moved
                                + " [源还在=" + from.exists() + " 目标目录在=" + to.exists()
                                + " 目标含package.json=" + new File(to, "package.json").exists() + "]");
                    } else {
                        /*
                         * 包目录不在 —— 这**不代表没事**。
                         * `cordis.patch.yml` 里 `- insert:` 进来的插件就是这样：
                         * 它根本不是 node_modules 里的包，所以"挪目录"对它无效，
                         * 只能靠下面那段"摘配置"处理（群友笔记第 43 行点名的正是这个缺口）。
                         */
                        android.util.Log.i("DSH_SAFEMODE", "包目录不存在（交给摘配置处理）：" + n);
                    }
                } catch (Throwable e2) {
                    android.util.Log.w("DSH_SAFEMODE", "挪走失败 " + n, e2);
                }
            }

            /*
             * ★ 关键补上的一步：**从 cordis.patch.yml 里摘掉这些条目**。
             *
             * 只挪 node_modules 目录是不够的 —— 通过 patch 文件 `- insert:` 挂进来的插件
             * 根本不在 node_modules 里，挪目录对它完全无效，于是每次冷启动都重复同一个错，
             * 用户"重启多少次都没用"（群友笔记里写得很清楚）。
             *
             * 摘之前**先备份**：改错了要能一键回到原样，这是用户唯一的路由配置文件。
             */
            try {
                File patch = profilePatch(c);
                if (patch.exists()) {
                    String before = readText(patch);
                    String after = PluginMsg.stripEntries(before, allOff);
                    if (!after.equals(before)) {
                        File bak = new File(patch.getParentFile(),
                                "cordis.patch.yml.bak-" + System.currentTimeMillis());
                        try (OutputStream bos = new FileOutputStream(bak)) {
                            bos.write(before.getBytes(StandardCharsets.UTF_8));
                        }
                        try (OutputStream os = new FileOutputStream(patch)) {
                            os.write(after.getBytes(StandardCharsets.UTF_8));
                        }
                        android.util.Log.i("DSH_SAFEMODE",
                                "已从 cordis.patch.yml 摘掉引用这些插件的条目；备份=" + bak.getName());
                    } else {
                        android.util.Log.i("DSH_SAFEMODE", "cordis.patch.yml 里没有这些插件的条目，未改动");
                    }
                }
            } catch (Throwable e3) {
                android.util.Log.w("DSH_SAFEMODE", "摘 cordis.patch.yml 条目失败", e3);
            }
            try {
                /*
                 * ⚠️ 这里是**合并**写回，不是覆盖：
                 * 早先的写法是 `putString(KEY_DISABLED_SPECS, new JSONObject(specs))`，
                 * 于是第二次停用（例如先自动停了一个、用户又手动进了一次安全模式）
                 * 会把上一批的依赖声明冲掉。恢复时 dependencies 写不回去，
                 * 插件目录挪回来了、pnpm 却认为它没装 —— "恢复了但还是坏的"。
                 */
                android.content.SharedPreferences sp =
                        c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE);
                org.json.JSONObject merged = new org.json.JSONObject();
                try {
                    merged = new org.json.JSONObject(sp.getString(KEY_DISABLED_SPECS, "{}"));
                } catch (Throwable ignore2) { merged = new org.json.JSONObject(); }
                for (java.util.Map.Entry<String, String> e : specs.entrySet()) {
                    merged.put(e.getKey(), e.getValue());
                }
                sp.edit().putString(KEY_DISABLED_SPECS, merged.toString()).apply();
            } catch (Throwable ignore) { }
            // 缩进写回，保持和 pnpm/dsh 自己写出来的格式一致（便于用户自己看）
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }

            java.util.List<String> dis = disabledBundles(c);
            for (String x : allOff) if (!dis.contains(x)) dis.add(x);
            setDisabled(c, dis);
            android.util.Log.i("DSH_SAFEMODE", "停用成功 bundles内=" + off + " 仅挪目录=" + alsoOff
                    + " 剩余bundle=" + keep + " 待恢复=" + dis);
            return allOff.size();
        } catch (Throwable t) {
            android.util.Log.w("DSH_SAFEMODE", "停用失败", t);
            return 0;
        }
    }

    /**
     * 进安全模式：只留官方包 + 自带插件，其余（第三方插件）全部停用并记下来。
     *
     * @return 被停用的个数
     */
    public static int enterSafeMode(Context c) {
        java.util.List<String> off = new java.util.ArrayList<>();
        java.util.List<String> all = profileBundles(c);
        for (String b : all) {
            boolean safe = false;
            for (String s : SAFE_BUNDLES) if (s.equals(b)) { safe = true; break; }
            if (!safe) off.add(b);
        }
        android.util.Log.i("DSH_SAFEMODE", "enterSafeMode: 当前=" + all + " 判定要停=" + off);
        return disableBundles(c, off);
    }

    /**
     * 把之前停用的插件全部恢复（一键回到正常模式）。
     *
     * @return 恢复的个数
     */
    public static int restoreDisabledBundles(Context c) {
        try {
            java.util.List<String> dis = disabledBundles(c);
            if (dis.isEmpty()) return 0;
            org.json.JSONObject specsJson;
            try {
                specsJson = new org.json.JSONObject(c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_DISABLED_SPECS, "{}"));
            } catch (Throwable t2) { specsJson = new org.json.JSONObject(); }
            File f = profilePkg(c);
            if (!f.exists()) return 0;
            org.json.JSONObject o = new org.json.JSONObject(readText(f));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) return 0;
            org.json.JSONArray b = prof.optJSONArray("bundles");
            if (b == null) { b = new org.json.JSONArray(); prof.put("bundles", b); }

            java.util.List<String> cur = new java.util.ArrayList<>();
            for (int i = 0; i < b.length(); i++) cur.add(b.optString(i));
            int n = 0;
            for (String x : dis) {
                if (!cur.contains(x)) { b.put(x); cur.add(x); n++; }
                // 包目录挪回来（停用时挪到了 .dsh-disabled/），依赖声明照原样写回
                try {
                    File nodeModules = new File(f.getParentFile(), "node_modules");
                    File from = new File(nodeModules, DISABLED_DIR.replace("node_modules/", "") + "/" + x);
                    File to = new File(nodeModules, x);
                    if (from.exists()) {
                        File parent = to.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();
                        deleteRecursively(to);
                        android.util.Log.i("DSH_SAFEMODE", "挪回包目录 " + x + " → " + from.renameTo(to)
                                + " [坟场里还有=" + from.exists() + " 原位目录在=" + to.exists()
                                + " 原位含package.json=" + new File(to, "package.json").exists() + "]");
                    }
                    String spec = specsJson.optString(x, null);
                    if (spec != null && !spec.isEmpty()) {
                        org.json.JSONObject deps2 = o.optJSONObject("dependencies");
                        if (deps2 == null) { deps2 = new org.json.JSONObject(); o.put("dependencies", deps2); }
                        deps2.put(x, spec);
                    }
                } catch (Throwable ignore) { }
            }
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            setDisabled(c, new java.util.ArrayList<>());
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 确保容器里有 pnpm —— 插件市场要用。
     *
     * <h3>为什么必须要它</h3>
     * {@code dsh plugin add <npm包>} 内部转发给 pnpm 做依赖解析与安装。
     * 我们自己的插件能靠"手工解包"绕过 pnpm，**只是因为它是零依赖的**；
     * 市场上任何一个正常的插件都带依赖，没有 pnpm 就装不了。
     *
     * <p>实测容器里 {@code /opt/node-…/bin/} 只有 corepack / dsh / node / npm / npx，
     * 没有 pnpm。所以这一步是插件市场的前置条件。
     *
     * <p>幂等：pnpm 已存在就直接返回。失败也不阻断启动 ——
     * 只是插件市场用不了，其它功能不受影响。
     */
    public static String ensurePnpm(File base, String tokenDir, String libDir) {
        try {
            File rootfs = new File(base, "rootfs");
            File binDir = new File(rootfs, "opt/" + NODE_DIR + "/bin");
            File pnpm = new File(binDir, "pnpm");
            if (pnpm.exists()) return "pnpm 已就绪";

            String nodePath = "/opt/" + NODE_DIR + "/bin";
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-g"); args.add("install"); args.add("pnpm");
            String[] cmd = containerCommand(base, null, tokenDir, nodePath + "/npm", args);

            Process p = buildProcess(base, cmd, libDir).start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Throwable ignore) { }
            }, "pnpm-install-reader");
            reader.setDaemon(true);
            reader.start();

            boolean done = Proc.waitFor(p, PLUGIN_INSTALL_TIMEOUT_MS);
            if (!done) {
                Proc.killForcibly(p);
                return "装 pnpm 超时，插件市场暂时不可用（其它功能不受影响）";
            }
            reader.join(2000);

            // 以文件是否存在为准，而不是退出码
            if (pnpm.exists()) return "pnpm 已装好，插件市场可用";
            String log;
            synchronized (out) { log = out.toString().trim(); }
            return "装 pnpm 没成功（插件市场将不可用）：" + tail(log, 300);
        } catch (Throwable t) {
            return "装 pnpm 异常（插件市场将不可用）: " + t;
        }
    }

    /**
     * 删除文件/目录，**绝不跟进符号链接**。
     *
     * <h3>为什么必须这样（这是"彻底删除"按钮的安全底线）</h3>
     * pnpm / npm 的 node_modules 里大量使用符号链接：
     * <ul>
     *   <li>{@code node_modules/<包名>} 往往是指向 {@code .pnpm/<包名>@<版本>/...} 的**链接**；</li>
     *   <li>{@code .pnpm/<包名>@<版本>/node_modules/} 里，每个依赖又是一个指向**别的**包目录的链接。</li>
     * </ul>
     * 而 {@link File#isDirectory()} 对"指向目录的链接"返回 true —— 于是天真的递归删除
     * 会**跟进链接、把链接目标里的真实文件删掉**：轻则删掉别的包（pnpm 商店被删花），
     * 重则删掉 {@code file:../xxx} 这种链接指向的用户自己的目录。
     *
     * <p>所以这里先判断"这一项本身是不是链接"（{@code Os.lstat}，API 21+，O(1)），
     * 是链接就只删链接本身；不是才往下递归。
     */
    public static void deleteRecursively(File f) {
        if (f == null) return;
        if (isSymlink(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();                 // 只摘掉链接，不碰目标
            return;
        }
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 这一项本身是不是符号链接（不看它指向谁）。
     *
     * <p>用 {@code Os.lstat} 而不是 {@code File.isDirectory()/exists()}：
     * 后两者都会**跟进**链接，判断不出"这是个链接"。
     * 也不能用 {@code java.nio.file.Files.isSymbolicLink} —— 那是 API 26+，
     * 而本 App 的 minSdk 是 24。
     */
    private static boolean isSymlink(File f) {
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getAbsolutePath());
            return android.system.OsConstants.S_ISLNK(st.st_mode);
        } catch (Throwable t) {
            return false;               // 问不出来就当它不是链接，按普通文件走
        }
    }

    /**
     * 这个字符串像不像一个 npm 包名（用于"彻底删除"前的基本校验）。
     *
     * <p>包名来自 profile 的 {@code bundles}，而那是**插件自己也能写**的地方。
     * 删除是不可逆操作，所以先挡掉 {@code ..}、绝对路径、反斜杠这类东西 ——
     * 免得一个畸形（或恶意）的包名把删除带到 node_modules 之外去。
     */
    private static boolean looksLikePackageName(String n) {
        if (n == null || n.isEmpty() || n.length() > 200) return false;
        if (n.contains("..") || n.contains("\\") || n.contains("\u0000")) return false;
        if (n.startsWith("/") || n.startsWith(".")) return false;
        return n.matches("@?[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?");
    }

    /** 插件安装最多等这么久（留给将来可能恢复的 pnpm 路径）。 */
    private static final long PLUGIN_INSTALL_TIMEOUT_MS = 240_000L;

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    /**
     * 把"操作手机"所需的工具装进容器：
     *   · /usr/local/bin/phone —— CLI（agent 用 bash 工具调用它）
     *   · /run/dsh-phone       —— token 的挂载点
     *   · 工作区里的 AGENTS.md  —— 告诉 agent 这个能力怎么用（已存在则不覆盖）
     *
     * 幂等，每次启动都会重写（文件很小）。
     */
    public static void installPhoneTools(Context c, File base) {
        try {
            File rootfs = new File(base, "rootfs");
            File binDir = new File(rootfs, "usr/local/bin");
            //noinspection ResultOfMethodCallIgnored
            binDir.mkdirs();
            File cli = new File(binDir, "phone");
            try (InputStream in = c.getAssets().open("phone-cli.js");
                 OutputStream os = new FileOutputStream(cli)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            Os.chmod(cli.getAbsolutePath(), 0755);

            //noinspection ResultOfMethodCallIgnored
            new File(rootfs, "run/dsh-phone").mkdirs();

            /*
             * 虚拟屏守护的 token 也放一份进容器（/run/dsh-vsd/token）。
             *
             * 为什么要放：容器里的 DSH 插件要直连 127.0.0.1:18240 取帧/点按，
             * 而那个守护从 0.3.2 起**每个请求都校验 X-Token**（原来是绑所有网卡 + 零鉴权）。
             * token 本身不含任何外部凭据，只是一个"访问本机回环"的口令；
             * 每次容器启动重写一次，用户清过容器也不会丢。
             */
            VScreen.writeContainerToken(c, base);

            /*
             * 工作区里也放一份 AGENTS.md。
             *
             * 早先是"已存在就不覆盖"，结果踩了坑：修好微信读取方式（改用截图）之后，
             * 老用户工作区里那份**旧说明一直留着**，还在教 agent 用 `phone ui` 读微信
             * —— 而那条路对微信永远返回空树。能力升级了，说明书却没升级。
             *
             * 改法：**只覆盖我们自己写的那份**。判断依据是文件里有我们的固定标题；
             * 用户自己写的 / 改过的 AGENTS.md 不含这个标记，原样保留，绝不覆盖。
             */
            File agents = new File(SDCARD_HOST + "/" + TEST_DIR_NAME, "AGENTS.md");
            if (shouldWriteAgents(agents)) {
                try (InputStream in = c.getAssets().open("AGENTS.md");
                     OutputStream os = new FileOutputStream(agents)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }

            /*
             * 同时写进 DSH 的**全局**指令目录（容器里的 $DSH_HOME/AGENTS.md）。
             *
             * 为什么必须：放在工作区里的 AGENTS.md 只对那个工作区生效。
             * 用户一旦把工作区切到别处（例如 /sdcard/Download），
             * agent 就看不到"手机操作"这份说明 —— 能力等于消失了。
             * 全局位置对**所有工作区**生效，这份每次覆盖以保证是最新版。
             */
            File globalAgents = new File(rootfs, "root/.dsh/AGENTS.md");
            File globalDir = globalAgents.getParentFile();
            if (globalDir != null) //noinspection ResultOfMethodCallIgnored
                globalDir.mkdirs();
            try (InputStream in = c.getAssets().open("AGENTS.md");
                 OutputStream os = new FileOutputStream(globalAgents)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } catch (Throwable t) {
            android.util.Log.e("DSH_ENV", "installPhoneTools failed", t);
        }
    }

    /**
     * 准备容器内的存储挂载点，并创建测试工作区文件夹。
     *
     * 挂载点目录必须**预先存在**：proot 在 guest 侧找不到挂载点时会尝试自己创建，
     * 我们此前实测它会失败并报 "can't sanitize binding"。
     *
     * @return 测试文件夹的宿主机路径（失败时返回 null）
     */
    public static String ensureStorageMounts(File base) {
        File rootfs = new File(base, "rootfs");
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "sdcard").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "storage/emulated/0").mkdirs();
        // 用户要求的测试文件夹：/sdcard/dsh
        File test = new File(SDCARD_HOST, TEST_DIR_NAME);
        boolean ok = test.exists() || test.mkdirs();
        return ok ? test.getAbsolutePath() : null;
    }

    /**
     * 容器里的组件是否都已就绪。
     *
     * <p>【为什么要连 /usr/bin/env 和 /bin/sh 一起看】这两个是**快速路径的守门人**：
     * 启动时 {@code isInstalled()} 为真 + 自检标记有效，就会跳过全部探针直接启动。
     * 而"rootfs 解压到一半"的坏容器恰恰能骗过原来那条只看 bash 的判断
     * （归档按字母序解压，bash 在 env 前面），于是每次启动都直接跳过自检、
     * 然后在 proot 里报 `'/usr/bin/env' not found` —— 用户看到的就是
     * "装不上、点重试也没用"。这里把判据补齐，坏容器一定会重新走完整自检。
     *
     * <p>{@code bin/sh} 是 merged-/usr 的符号链接，顺手验证符号链接有没有正确还原。
     */
    public static boolean isInstalled(File base) {
        return new File(base, "rootfs/usr/bin/bash").exists()
                && new File(base, "rootfs/usr/bin/env").exists()
                && new File(base, "rootfs/bin/sh").exists()
                && new File(base, "rootfs/opt/" + NODE_DIR + "/bin/node").exists()
                && new File(base, "rootfs/opt/" + NODE_DIR + "/bin/dsh").exists();
    }
}
