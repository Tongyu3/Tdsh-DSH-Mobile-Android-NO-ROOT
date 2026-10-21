package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 手机控制桥：容器里的 agent 通过它来操作手机。
 *
 * 三层安全：
 *   1. 只监听 127.0.0.1（回环），不对外网开放；
 *   2. 每次请求必须带 token，token 由 App 随机生成、写在应用私有目录里，
 *      再通过只读绑定暴露给容器（放在共享存储会被其它应用读到）；
 *   3. **白名单在动作发生前强制校验**：当前前台包名不在白名单里就直接拒绝，
 *      连界面树都不返回 —— 用户要求"只能操作指定 App"。
 */
public final class PhoneBridge {

    private static final String TAG = "DSH_PHONE";
    public static final int PORT = 3099;
    public static final String PREFS = "dsh_phone";
    private static final String KEY_ALLOWED = "allowed_packages";
    private static final String KEY_TOKEN = "token";

    private static volatile boolean sRunning = false;

    /**
     * 单个请求最多等多久。
     *
     * <p>取值依据：最慢的正常操作是 {@code uiautomator dump}（1~3 秒）；
     * 走 Shizuku 时要加上 binder 连接（上限 15 秒）。25 秒足够覆盖正常路径，
     * 又能把"卡死"和"就是慢"区分开。
     */
    private static final long REQUEST_TIMEOUT_MS = 25_000L;

    /**
     * 同时在跑、还没返回的请求上限。
     *
     * <p>超时后被放弃的工作线程没法安全杀掉（Java 的限制），只能让它们挂着。
     * 这个上限保证它们不会无限堆积 —— 超出就直接回绝新请求，
     * 而不是继续接单然后把整个桥拖垮。
     */
    private static final int MAX_IN_FLIGHT = 12;
    private static final java.util.concurrent.atomic.AtomicInteger sInFlight =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 最近几次"慢请求"的记录。
     *
     * 这台机器上 OriginOS 不让 adb 读 logcat（读出来是空的），所以 App 得自己
     * 把可疑的耗时记下来，通过 /status 暴露出去 —— 否则卡顿只能靠猜。
     */
    private static final java.util.concurrent.ConcurrentLinkedDeque<String> sSlow =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static volatile long sReqCount = 0L;

    private static void noteSlow(String path, long ms) {
        sReqCount++;
        if (ms < 300L) return;
        sSlow.addFirst(path + " 耗时 " + ms + "ms");
        while (sSlow.size() > 8) sSlow.pollLast();
    }

    private PhoneBridge() { }

    // ── 白名单 ──────────────────────────────────────────────

    public static Set<String> allowed(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_ALLOWED, "");
        Set<String> out = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public static void setAllowed(Context c, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ALLOWED, sb.toString()).apply();
    }

    // ── token ───────────────────────────────────────────────

    public static String token(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = sp.getString(KEY_TOKEN, "");
        if (t == null || t.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            java.util.Random r = new java.util.Random();
            for (int i = 0; i < 32; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
            t = sb.toString();
            sp.edit().putString(KEY_TOKEN, t).apply();
        }
        return t;
    }

    /** 把 token 写到应用私有目录，供容器通过只读绑定读取。 */
    public static String tokenDir(Context c) {
        File d = new File(c.getFilesDir(), "phone");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File f = new File(d, "token");
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(token(c).getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "写 token 失败", t);
        }
        return d.getAbsolutePath();
    }

    // ── 服务 ────────────────────────────────────────────────

    public static synchronized void start(final Context ctx) {
        if (sRunning) return;
        sRunning = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            try {
                // backlog 从 16 提到 64：16 太小，几个卡住的请求就能把队列占满，
                // 导致连"读状态"都连不上（出过一次，现象是整个手机控制失灵）
                ss = new ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"));
                Log.i(TAG, "控制桥已启动 127.0.0.1:" + PORT);
                while (sRunning) {
                    final Socket s = ss.accept();
                    /*
                     * 每个请求单独一个线程。
                     *
                     * 原来是在 accept 循环里**顺序**处理：实测只要有一个请求卡住
                     * （取界面树时最典型），后面所有请求全部排队等死 ——
                     * 连 `phone status` 这种纯内存查询都拿不到回应。
                     * agent 会同时发好几个请求，一条卡住就整个功能瘫掉，必须并发。
                     */
                    Thread w = new Thread(() -> {
                        try {
                            s.setSoTimeout(8000);
                            handle(app, s);
                        } catch (Throwable e) {
                            Log.w(TAG, "handle 失败", e);
                        } finally {
                            try { s.close(); } catch (Throwable ignore) { }
                        }
                    }, "phone-bridge-req");
                    w.setDaemon(true);
                    w.start();
                }
            } catch (Throwable e) {
                Log.e(TAG, "控制桥退出", e);
                sRunning = false;
            } finally {
                try { if (ss != null) ss.close(); } catch (Throwable ignore) { }
            }
        }, "phone-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Context ctx, Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String path = parts[1];
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

        java.util.Map<String, String> p = parseQuery(query);
        String body;
        int code = 200;

        if (!token(ctx).equals(p.get("token"))) {
            body = "ERROR 403: token 不正确\n";
            code = 403;
        } else {
            /*
             * 关键：dispatch 放到工作线程里跑，本线程最多等 REQUEST_TIMEOUT_MS。
             *
             * 为什么必须这样 —— 出过一次**整个控制桥瘫痪**的事故：
             * 某次调用卡在一个不会返回的 binder 调用上，请求线程就一直挂着；
             * 而 accept 队列只有 16 个位置，几个卡住的请求就把队列占满，
             * 于是**所有** phone 命令（连 /status 这种纯内存查询）全部无响应。
             * agent 那边看到的就是"手机控制突然整个失灵"，且不会自己恢复。
             *
             * 现在即使某个操作真的卡死，也只是**这一个请求**超时返回一句人话，
             * 控制桥本身继续服务其它请求。
             */
            if (sInFlight.get() >= MAX_IN_FLIGHT) {
                body = "ERROR 控制桥繁忙：同时有 " + sInFlight.get()
                     + " 个操作没返回。请稍后重试。\n";
                code = 503;
            } else {
                sInFlight.incrementAndGet();
                final String[] result = new String[1];
                final java.util.Map<String, String> params = p;
                // path 在上面被重新赋值过（剥掉 query），所以不是 effectively final，
                // 不能直接进 lambda —— 先拷一份 final 的
                final String fpath = path;
                final Context fctx = ctx;
                Thread worker = new Thread(() -> {
                    try {
                        result[0] = dispatch(fctx, fpath, params);
                    } catch (Throwable e) {
                        result[0] = "ERROR 500: " + e + "\n";
                    } finally {
                        sInFlight.decrementAndGet();
                    }
                }, "phone-bridge-work");
                worker.setDaemon(true);
                long t0 = System.currentTimeMillis();
                worker.start();
                worker.join(REQUEST_TIMEOUT_MS);
                long cost = System.currentTimeMillis() - t0;
                if (result[0] == null) {
                    /*
                     * 超时。工作线程会继续挂着（Java 没法安全地杀掉线程），
                     * 但 sInFlight 限制了它能堆积多少个，所以不会无限增长。
                     * 这里必须**照常回一句**，绝不能让调用方干等。
                     */
                    body = "ERROR 操作超时（" + REQUEST_TIMEOUT_MS + "ms 未返回）："
                         + path + " 卡住了，已放弃等待。\n"
                         + "控制桥仍然可用，可以重试，或改用别的方式完成这一步。\n";
                    code = 504;
                } else {
                    body = result[0];
                }
                noteSlow(path, cost);
            }
        }

        byte[] bytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(header.getBytes("UTF-8"));
        os.write(bytes);
        os.flush();
    }

    private static java.util.Map<String, String> parseQuery(String q) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try {
                m.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                      java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Throwable ignore) { }
        }
        return m;
    }

    /**
     * 当前前台包名 —— 按"当前生效的操作后端"取。
     *
     * <p>两条后端问前台的方式不同（无障碍靠事件缓存，直接命令靠 dumpsys），
     * 但白名单校验必须用同一套判断，否则两条路的边界会不一致。
     *
     * <h3>⚠️ 这里刻意只走"有界"的两种方式，绝不调 Shizuku</h3>
     * 曾经加过一层"无障碍不可用时用 Shizuku 问前台"的兜底，结果出了事故：
     * Shizuku 的 binder 调用在某些状态下会**长时间不返回**（实测把一次 /shot 拖到几百秒），
     * 而控制桥的 accept 队列只有 16 —— 几个卡住的请求就把队列占满，
     * 于是**所有** phone 命令（连 /status 这种纯内存查询）全部无响应。
     * 前台判断是白名单的守门人，不能把它建在一个可能无限阻塞的调用上。
     *
     * <p>所以规则是：
     * <ul>
     *   <li>用户选了直接命令后端 → 用后端自己的方式（它有界）；</li>
     *   <li>否则用无障碍缓存；**服务没在跑就返回 null**（宁可拒绝，不拿旧值判断）；</li>
     *   <li>都拿不到 → null，调用方拒绝操作。</li>
     * </ul>
     *
     * <p>代价说清楚：无障碍被关掉时，截图会被拒绝 —— 这是**有意的**，
     * 因为那时我们无法确认前台是谁，放行就等于绕过白名单。
     */
    private static String foreground(Context ctx) {
        if (ShellControl.active(ctx)) {
            String f = ShellControl.foreground(ctx);
            if (f != null && !f.isEmpty()) return f;
        }
        String f = DshAccessibilityService.foregroundPackage();
        return (f != null && !f.isEmpty()) ? f : null;
    }

    /** 插件市场里的一条搜索结果。 */
    public static final class PluginHit {
        public final String name, version, description, date;
        /**
         * npm 的 {@code keywords} —— **市场分类就靠它**。
         *
         * <p>为什么是它：npm 没有 {@code category}/{@code tags} 这类专门字段，
         * 而每个 dsh 插件按约定都会打 {@code dsh-plugin} 标签，创作者通常还会
         * 补上能力词（memory / theme / preset / ui …）。分类只能从这些词推。
         */
        public final java.util.List<String> keywords;

        PluginHit(String n, String v, String d, String dt, java.util.List<String> kw) {
            name = n; version = v; description = d; date = dt;
            keywords = kw == null ? java.util.Collections.<String>emptyList() : kw;
        }
    }

    /** 一页搜索结果：带上 npm 的 total，界面才能写"共 N 个"而不是只写"这 40 个"。 */
    public static final class MarketPage {
        public final java.util.List<PluginHit> hits;
        public final int total;
        MarketPage(java.util.List<PluginHit> h, int t) { hits = h; total = t; }
    }

    /**
     * 在 npm 上搜 dsh 插件（旧签名，不带分页）。
     *
     * <p>官方发现机制就是 npm 上的 {@code dsh-plugin} 标签 —— 直接问 registry 最准，
     * 不需要我们自己维护一份清单。
     *
     * <h3>为什么搜索**不**跟着地区走</h3>
     * 实测（2026-09）：npmmirror 的 {@code /-/v1/search} 不认 {@code keywords:} 限定 ——
     * 同一个查询在官方源返回 6395 条，在 npmmirror 返回 **0 条**；把限定词去掉它又
     * 返回 9825 条完全不相干的结果。用它当主源等于把插件市场弄坏，
     * 所以这里**官方源优先**（跟以前一样，市场本来就是这么工作的），
     * 官方源不通时才拿地区镜像兜一下 —— 比原来"一个源失败就整个搜索失败"强。
     *
     * <p>注意：**安装**不受此影响。装插件走容器里的 pnpm，用的是
     * {@code /root/.npmrc} 里按地区写好的 registry（见 Region.applyNpmrc），
     * 而镜像对"按名字装包"是完全可靠的。
     */
    public static java.util.List<PluginHit> marketSearch(Context ctx, String q) throws Exception {
        return marketSearch(ctx, q, 0, 25).hits;
    }

    /**
     * 带分页的搜索。
     *
     * <p>为什么要加 from/size：npm 上带 {@code dsh-plugin} 标签的包实测有 8000+ 条，
     * 而旧代码把 {@code size=25} 写死、也没有 {@code from} —— 界面**永远只显示 25 条**，
     * 状态行还写着"找到 25 个插件"，看起来像市场里就只有这么点东西。
     */
    public static MarketPage marketSearch(Context ctx, String q, int from, int size) throws Exception {
        if (size <= 0 || size > 100) size = 40;
        if (from < 0) from = 0;
        String text = (q == null || q.trim().isEmpty())
                ? "keywords:dsh-plugin"
                : q.trim() + " keywords:dsh-plugin";
        String encoded = java.net.URLEncoder.encode(text, "UTF-8");
        java.util.List<String> sources = new java.util.ArrayList<>();
        sources.add("https://registry.npmjs.org");                 // 主源：搜索语义正确
        for (String r : Region.npmRegistries(ctx)) {               // 兜底：地区镜像
            if (!sources.contains(r)) sources.add(r);
        }
        Throwable last = null;
        for (String base : sources) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(base + "/-/v1/search?size=" + size + "&from=" + from
                                + "&text=" + encoded)
                                .openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/json");
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                org.json.JSONObject o = new org.json.JSONObject(sb.toString());
                org.json.JSONArray arr = o.optJSONArray("objects");
                int total = o.optInt("total", 0);
                java.util.List<PluginHit> out = new java.util.ArrayList<>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    org.json.JSONObject pk = arr.getJSONObject(i).optJSONObject("package");
                    if (pk == null) continue;
                    String desc = pk.optString("description", "").replace('\n', ' ').trim();
                    org.json.JSONArray kw = pk.optJSONArray("keywords");
                    java.util.List<String> kws = new java.util.ArrayList<>();
                    for (int k = 0; kw != null && k < kw.length(); k++) {
                        String s = kw.optString(k, "").trim();
                        if (!s.isEmpty()) kws.add(s);
                    }
                    out.add(new PluginHit(pk.optString("name"), pk.optString("version"), desc,
                            pk.optString("date", ""), kws));
                }
                // 搜到东西才认；空结果继续试下一个源（空的往往意味着"这个源不会搜"）
                if (!out.isEmpty()) return new MarketPage(out, total > 0 ? total : out.size());
            } catch (Throwable t) {
                last = t;
                Log.w(TAG, "插件搜索失败（源 " + base + "）", t);
            }
        }
        if (last != null) throw new Exception("搜索失败：" + last.getMessage());
        return new MarketPage(new java.util.ArrayList<PluginHit>(), 0);
    }

    /** profile 里已装的 bundle 列表。 */
    public static java.util.List<String> marketInstalled(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            File pkg = new File(Env.base(ctx), "rootfs/root/.dsh/profiles/web/package.json");
            if (!pkg.exists()) return out;
            org.json.JSONObject o = new org.json.JSONObject(readFile(pkg));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray b = prof == null ? null : prof.optJSONArray("bundles");
            for (int i = 0; b != null && i < b.length(); i++) out.add(b.optString(i));
        } catch (Throwable ignore) { }
        return out;
    }

    /** 从 npm 装一个插件。要在后台线程调用（可能跑十几秒）。 */
    public static String marketAdd(Context ctx, String pkg) {
        try {
            File base = Env.base(ctx);
            String tokenDir = PhoneBridge.tokenDir(ctx);
            String[] cmd = Env.dshPluginCommand(base, tokenDir, "add", pkg);
            String r = runInContainer(ctx, cmd, "plugin-add");
            // 以 profile 里的实际结果为准，不看退出码
            if (marketInstalled(ctx).contains(pkg)) {
                return "OK 已安装 " + pkg + "。\n重启 DSH 后生效（App 内「重试」或重开 App）。\n\n" + tailOf(r, 400);
            }
            return "没能装上 " + pkg + "：\n" + tailOf(r, 800);
        } catch (Throwable t) {
            return "安装出错: " + t;
        }
    }

    /** 卸载一个插件。 */
    public static String marketRemove(Context ctx, String pkg) {
        try {
            if ("@deepseek-ai/dsh-base".equals(pkg) || "@deepseek-ai/dsh-web-app".equals(pkg)) {
                return "这是 DSH 自带的必需组件，不能卸载。";
            }
            String[] cmd = Env.dshPluginCommand(Env.base(ctx), PhoneBridge.tokenDir(ctx), "remove", pkg);
            String r = runInContainer(ctx, cmd, "plugin-remove");
            if (!marketInstalled(ctx).contains(pkg)) {
                return "OK 已卸载 " + pkg + "。重启 DSH 后生效。";
            }
            return "没能卸载 " + pkg + "：\n" + tailOf(r, 600);
        } catch (Throwable t) {
            return "卸载出错: " + t;
        }
    }

    private static String tailOf(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /**
     * 插件市场接口。
     *
     * <p>op=list   —— 列出 profile 里已装的插件（含自带的 dsh-phone）
     * <p>op=add    —— 从 npm 安装一个插件，装完提示需要重启 DSH
     * <p>op=remove —— 卸载
     * <p>op=search —— 从 npm registry 搜带 dsh-plugin 标签的包
     *
     * <p>刻意**不做白名单那套**：这里的边界是"用户自己在 App 里点的"，
     * 不是 agent 能调用的能力 —— 所以 agent 的 phone CLI 不会暴露这一组接口。
     */
    private static String plugin(Context ctx, java.util.Map<String, String> p) {
        String op = p.getOrDefault("op", "list");
        try {
            if ("search".equals(op)) {
                java.util.List<PluginHit> hits = marketSearch(ctx, p.getOrDefault("q", ""));
                StringBuilder out = new StringBuilder();
                out.append("找到 ").append(hits.size()).append(" 个结果\n\n");
                for (PluginHit h : hits) {
                    String d = h.description.length() > 90
                            ? h.description.substring(0, 90) + "…" : h.description;
                    out.append(h.name).append('@').append(h.version).append('\n')
                       .append("  ").append(d).append("\n\n");
                }
                return out.toString();
            }
            if ("list".equals(op)) {
                StringBuilder sb = new StringBuilder("已安装的插件：\n\n");
                for (String s : marketInstalled(ctx)) sb.append("  · ").append(s).append('\n');
                return sb.toString();
            }
            String pkgName = p.getOrDefault("pkg", "");
            if (pkgName.isEmpty()) return "ERROR 需要 pkg=<包名>\n";
            if (!pkgName.matches("[A-Za-z0-9@/._-]+")) return "ERROR 包名不合法\n";
            return "remove".equals(op) ? marketRemove(ctx, pkgName) : marketAdd(ctx, pkgName);
        } catch (Throwable t) {
            return "ERROR " + t + "\n";
        }
    }

    /** 在容器里跑一条命令并把输出带回来（有超时，绝不让请求线程无限等）。 */
    private static String runInContainer(Context ctx, String[] cmd, String tag) {
        try {
            File base = Env.base(ctx);
            String libDir = Env.prepareProot(ctx, base);
            Process p = Env.buildProcess(base, cmd, libDir).start();
            StringBuilder out = new StringBuilder();
            Thread r = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Throwable ignore) { }
            }, "phone-" + tag + "-reader");
            r.setDaemon(true);
            r.start();
            boolean done = Proc.waitFor(p, 180_000L);
            if (!done) { Proc.killForcibly(p); return "ERROR 安装超时（180 秒），已放弃\n"; }
            r.join(2000);
            String log;
            synchronized (out) { log = out.toString().trim(); }
            return "exit=" + p.exitValue() + "\n" + log + "\n";
        } catch (Throwable t) {
            return "ERROR " + t + "\n";
        }
    }

    private static String readFile(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "{}";
        }
    }

    /**
     * 截屏接口：给 agent 一双"眼睛"。
     *
     * <h3>为什么这条接口特别重要</h3>
     * 微信 / QQ 会屏蔽控件树（无障碍和 uiautomator 读到的都是空树），
     * 但**没有屏蔽屏幕像素**。所以对这类应用，截图是唯一的读取途径。
     *
     * <h3>安全边界照旧</h3>
     * 截图是**信息获取**，比点击更敏感（屏幕上可能有聊天记录、验证码）。
     * 因此前台应用不在白名单里时**一律拒绝**，跟 /ui 完全一致 ——
     * 不能出现"点不了但能截"这种漏洞。
     */
    private static String shot(Context ctx, java.util.Map<String, String> p) {
        java.util.Set<String> allow = allowed(ctx);
        if (allow.isEmpty()) {
            return "ERROR 白名单为空：请先在 DSH 设置 →「手机控制」里勾选允许操作的应用\n";
        }
        String fg = foreground(ctx);
        if (fg == null || fg.isEmpty() || !allow.contains(fg)) {
            return "ERROR 当前前台应用 [" + fg + "] 不在白名单里，已拒绝截图\n";
        }

        Screenshot.Shot s = Screenshot.capture(ctx);
        if (s.bitmap == null) {
            return "ERROR " + s.error + "\n";
        }
        try {
            // 缩到 720 宽：够模型看清文字，又能显著省 token 和上传时间
            android.graphics.Bitmap small = Screenshot.downscale(s.bitmap, 720);
            java.io.File f = Screenshot.save(small, "shot.png");
            if (small != s.bitmap) small.recycle();

            /*
             * 【为什么原图改成"按需"生成（2026-10-05，采纳群友实测建议）】
             *
             * 原来每次 /shot 都额外写一份 1080×2400 的全尺寸 PNG（约 583KB、约 39ms）。
             * 单次无所谓，但"连续看屏幕"这类用法（2fps）就是
             * **~70MB/分钟的闪存写入** —— 省的是闪存寿命和电，不是延迟。
             * 而那份原图绝大多数调用者根本用不到（缩略图已经够模型看清文字）。
             *
             * 现在：只有显式 `/shot?full=1` 才写原图，而且**丢到后台线程**写 ——
             * ⚠️ `recycle()` 必须跟着挪进后台线程：留在主流程先回收，
             * 后台还在读 `s.bitmap` 就会崩（这条是建议里点名的坑）。
             */
            boolean wantFull = "1".equals(p.get("full")) || "true".equalsIgnoreCase(p.get("full"));
            StringBuilder out = new StringBuilder("OK 已截图\n")
                    .append("  文件: ").append(f.getAbsolutePath()).append('\n')
                    .append("  方式: ").append(s.backend).append('\n')
                    .append("  前台: ").append(fg).append('\n')
                    .append("  尺寸: ").append(s.bitmap.getWidth()).append('x').append(s.bitmap.getHeight())
                    .append("（缩略图 ").append(small.getWidth()).append('x').append(small.getHeight()).append("）\n");
            if (wantFull) {
                final android.graphics.Bitmap orig = s.bitmap;
                new Thread(() -> {
                    try {
                        java.io.File full = Screenshot.save(orig, "shot-full.png");
                        Log.i(TAG, "原图已保存: " + full.getAbsolutePath());
                    } catch (Throwable t) {
                        Log.w(TAG, "保存原图失败", t);
                    } finally {
                        try { orig.recycle(); } catch (Throwable ignore) { }
                    }
                }, "shot-full").start();
                out.append("  原图: ").append(new java.io.File(Screenshot.DIR, "shot-full.png")
                                      .getAbsolutePath())
                   .append("（后台写盘中，稍等一下再读）\n");
            } else {
                // 不需要原图 → 立刻回收，别占内存
                s.bitmap.recycle();
                out.append("  原图: 未生成（要的话加 ?full=1）\n");
            }
            return out.toString();
        } catch (Throwable t) {
            return "ERROR 保存截图失败: " + t + "\n";
        }
    }

    /**
     * 「看一段屏幕」：连续抓帧 → 挑关键帧 → 拼成一张分镜表。
     *
     * <h3>为什么放在设备端做</h3>
     * 客户端版（每 500ms 调一次 {@code /shot}）实测 10 秒要 15.6 秒才跑完，
     * 因为每帧都要走 HTTP + 落盘 + 在容器里解 PNG。设备端手里就是原生像素，
     * 比"先传出去再比"更省更准，也不必每帧落盘（原来 2fps 约 70MB/分钟的闪存写入）。
     *
     * <h3>线程与节流</h3>
     * 这个方法在请求线程里跑（不是主线程），中间的 {@code sleep} 是必须的：
     * 无障碍截图有系统节流，间隔小于 ~300ms 就开始**静默丢帧**，所以 fps 上限压到 3。
     *
     * <h3>为什么复用上一次的缩略图做差异基准</h3>
     * 拿每一帧都和"上一采样帧"比（而不是和第一帧比），是因为要看的是
     * "**画面什么时候变了**"（翻页/弹窗/视频换镜头），而不是"和开头差多少"。
     */
    private static String watch(Context ctx, java.util.Map<String, String> p) {
        java.util.Set<String> allow = allowed(ctx);
        if (allow.isEmpty()) {
            return "ERROR 白名单为空：请先在 DSH 设置 →「手机控制」里勾选允许操作的应用\n";
        }
        String fg = foreground(ctx);
        if (fg == null || fg.isEmpty() || !allow.contains(fg)) {
            return "ERROR 当前前台应用 [" + fg + "] 不在白名单里，已拒绝\n";
        }

        int seconds = clampInt(p.get("seconds"), 10, 1, 60);
        int fps = clampInt(p.get("fps"), 2, 1, 3);          // 上限 3：见方法注释的节流说明
        int threshold = clampInt(p.get("threshold"), 8, 0, 255);
        int maxFrames = clampInt(p.get("maxFrames"), 12, 1, 40);
        int cols = clampInt(p.get("cols"), 3, 1, 6);
        int cellW = clampInt(p.get("w"), 360, 120, 1080);

        long t0 = System.currentTimeMillis();
        long intervalMs = 1000L / fps;
        int total = Math.max(1, seconds * fps);
        java.util.List<Storyboard.Frame> frames = new java.util.ArrayList<>();
        java.util.List<android.graphics.Bitmap> shots = new java.util.ArrayList<>();
        byte[] prevGray = null;
        String lastErr = null;

        for (int i = 0; i < total; i++) {
            long target = t0 + (long) i * intervalMs;
            long wait = target - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ie) { break; }
            }
            Screenshot.Shot s = Screenshot.capture(ctx);
            if (s.bitmap == null) {
                lastErr = s.error;
                continue;
            }
            // 每帧统一缩到一个小宽度：拼图用 cellW，算差异用固定的小灰度图
            android.graphics.Bitmap cell = Screenshot.downscale(s.bitmap, cellW);
            s.bitmap.recycle();
            if (cell == null) continue;
            byte[] gray = gray32(cell);
            int d = (prevGray == null) ? 255 : Storyboard.diff(prevGray, gray);
            prevGray = gray;
            frames.add(new Storyboard.Frame(i, System.currentTimeMillis() - t0, d));
            shots.add(cell);
        }

        if (shots.isEmpty()) {
            return "ERROR 一帧都没抓到" + (lastErr == null ? "" : "：" + lastErr) + "\n";
        }

        java.util.List<Storyboard.Frame> keys = Storyboard.pick(frames, threshold, maxFrames);

        // 拼图：把关键帧画到一张画布上（每格左上一个时间戳，便于按时间线读）
        int cw = shots.get(0).getWidth();
        int ch = shots.get(0).getHeight();
        int gap = 6, pad = 6;
        Storyboard.Layout lay = Storyboard.layout(keys.size(), cols, cw, ch, gap, pad);
        android.graphics.Bitmap sheet = android.graphics.Bitmap.createBitmap(
                lay.canvasW, lay.canvasH, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(sheet);
        canvas.drawColor(0xFF101012);
        android.graphics.Paint text = new android.graphics.Paint();
        text.setColor(0xFFE8E8EA);
        text.setTextSize(Math.max(10f, cw * 0.045f));
        text.setAntiAlias(true);
        java.util.List<String> keyPaths = new java.util.ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            Storyboard.Frame f = keys.get(k);
            int x = pad + lay.x(k), y = pad + lay.y(k);
            android.graphics.Bitmap bmp = shots.get(Math.min(f.index, shots.size() - 1));
            canvas.drawBitmap(bmp, x, y, null);
            String label = String.format(java.util.Locale.ROOT, "%.1fs Δ%d", f.atMs / 1000.0, f.diff);
            canvas.drawText(label, x + 4, y + text.getTextSize() + 2, text);
        }

        String sheetPath = null;
        try {
            java.io.File dir = new java.io.File(Screenshot.DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, "storyboard.png");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                sheet.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, os);
            }
            sheetPath = out.getAbsolutePath();
            // 关键帧单独也留一份（agent 想放大看某一张时用）
            for (Storyboard.Frame f : keys) {
                java.io.File one = new java.io.File(dir, "key-" + f.index + ".png");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(one)) {
                    shots.get(Math.min(f.index, shots.size() - 1))
                         .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os);
                }
                keyPaths.add(one.getAbsolutePath());
            }
        } catch (Throwable t) {
            return "ERROR 写分镜表失败: " + t + "\n";
        } finally {
            sheet.recycle();
            for (android.graphics.Bitmap b : shots) { try { b.recycle(); } catch (Throwable ignore) { } }
        }

        StringBuilder head = new StringBuilder();
        head.append("OK 看完了 ").append(seconds).append(" 秒（采样 ").append(frames.size())
            .append(" 帧 @" ).append(fps).append("fps，挑出 ").append(keys.size()).append(" 张关键帧）\n")
            .append("  分镜表: ").append(sheetPath)
            .append("\n  （用 read_image 读这张图就能一次看完整个流程；"
                    + "单帧原图在 key-*.png，要放大细节时读它）\n")
            .append("  前台: ").append(fg).append('\n');
        String json = Storyboard.toJson(frames, fps, threshold, sheetPath, keyPaths);
        return head.append("  元数据:\n").append(json).toString();
    }

    /** 把缩略图变成 32×32 灰度（算帧间差异用）。 */
    private static byte[] gray32(android.graphics.Bitmap src) {
        android.graphics.Bitmap g = android.graphics.Bitmap.createScaledBitmap(src, 32, 32, true);
        int[] px = new int[32 * 32];
        g.getPixels(px, 0, 32, 0, 0, 32, 32);
        g.recycle();
        byte[] out = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, b = c & 0xFF;
            // 亮度权重：人眼对绿最敏感，转写/挑帧只需要"看起来变了没"
            out[i] = (byte) ((r * 299 + gg * 587 + b * 114) / 1000);
        }
        return out;
    }

    private static int clampInt(String v, int def, int lo, int hi) {
        try {
            int n = Integer.parseInt(v == null ? "" : v.trim());
            return Math.max(lo, Math.min(hi, n));
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 把用户说的「应用名或包名」解析成白名单里的真实包名；解析不到返回 null。
     *
     * <p>抽出来是为了让无障碍后端和「直接命令」后端共用同一套匹配规则 ——
     * 否则两条路对"什么算白名单内的应用"判断不一致，等于开了个后门。
     *
     * <h3>为什么"带点的串"必须精确匹配</h3>
     * 真机上出过一次事故：用户让 agent 打开 `com.hihonor.inputmethod`（这台手机
     * 根本没这个包），原来的模糊匹配去撞"带 hihonor 的东西"，命中了
     * `com.hihonor.android.launcher`（荣耀桌面），把桌面拉到了前台 ——
     * 用户看到的就是"说了个不存在的包，结果莫名跳回桌面"，而且那个包
     * 还被加进了白名单（见 {@link #pointlessQuickAdd}）。
     *
     * <p>包名本身就是最精确的标识，对它做模糊匹配没有任何合理场景，
     * 所以只要串里带 `.` 就只认精确命中。
     *
     * <h3>应用名允许模糊，但**不替用户猜**</h3>
     * 精确 → 前缀 → 子串，且只在**唯一**命中时才认；命中 0 个或多个都算没解析出来，
     * 交给 {@link #openMissMessage} 把候选摊开。否则"打开微信"可能开到任何
     * 带"信"字的应用上。
     */
    private static String resolveAllowed(Context ctx, String q) {
        Set<String> allow = allowed(ctx);
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.Intent main = new android.content.Intent(
                android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);

        List<String> candLabels = new ArrayList<>(), candPkgs = new ArrayList<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!allow.contains(pkg)) continue;              // 白名单外直接跳过
            if (pkg.equalsIgnoreCase(q)) return pkg;         // 包名精确命中
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            candLabels.add(label);
            candPkgs.add(pkg);
        }

        // 看着像包名 → 到此为止，不做任何模糊匹配
        if (q.indexOf('.') >= 0) return null;

        String ql = q.toLowerCase();
        String exact = null, prefix = null;
        int prefixHits = 0;
        List<Integer> subHits = new ArrayList<>();
        for (int i = 0; i < candPkgs.size(); i++) {
            String ll = candLabels.get(i).toLowerCase();
            if (ll.equals(ql)) { exact = candPkgs.get(i); break; }
            if (ll.startsWith(ql)) { prefixHits++; prefix = candPkgs.get(i); }
            if (ll.contains(ql)) subHits.add(i);
        }
        if (exact != null) return exact;
        if (prefixHits == 1) return prefix;
        if (prefixHits == 0 && subHits.size() == 1) return candPkgs.get(subHits.get(0));
        return null;
    }

    private static String openMissMessage(Context ctx, String q) {
        Set<String> allow = allowed(ctx);
        StringBuilder sb = new StringBuilder();
        if (q.indexOf('.') >= 0) {
            /*
             * 包名路径：说清是"没装"还是"装了但不在白名单"。
             * 这两件事对用户来说是完全不同的下一步，笼统说一句"没匹配上"
             * 只会让人去白名单页白翻一遍。
             */
            boolean installed = false;
            try {
                ctx.getPackageManager().getApplicationInfo(q, 0);
                installed = true;
            } catch (Throwable ignore) { }
            sb.append("ERROR 没有找到包名「").append(q).append("」。\n");
            if (installed) {
                sb.append("这台手机上装了这个应用，但它不在白名单里 —— ")
                  .append("请让用户在 App 的「手机控制 → 管理白名单」里勾上它。\n");
            } else {
                sb.append("这台手机上也没有这个包名（可能在别的设备上，或者名字写错了）。\n")
                  .append("包名必须完全正确；要按应用名找，请直接给中文名（例如「微信」）。\n");
            }
        } else {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.Intent main = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            List<String> hits = new ArrayList<>();
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!allow.contains(pkg)) continue;
                String label;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Throwable t) {
                    label = pkg;
                }
                if (label.toLowerCase().contains(q.toLowerCase())) hits.add(label + "(" + pkg + ")");
            }
            if (!hits.isEmpty()) {
                sb.append("ERROR 「").append(q).append("」匹配到多个白名单应用，无法确定是哪一个：\n");
                for (String h : hits) sb.append("  · ").append(h).append('\n');
                sb.append("请让用户说得更明确一些，或者直接给包名。\n");
            } else {
                sb.append("ERROR 白名单里没有名字包含「").append(q).append("」的应用。\n")
                  .append("请让用户在 App 的「手机控制 → 管理白名单」里加入它。\n");
            }
        }
        sb.append("白名单当前为: ").append(allow).append('\n');
        return sb.toString();
    }

    /**
     * 有些包**根本不该**发"一键放行"的通知。
     *
     * <p>起因是一个真实故障：agent 想打开某个应用但没打开，此时前台其实是**桌面**
     * （荣耀桌面 {@code com.hihonor.android.launcher}），于是弹出了
     * "要不要把荣耀桌面加入白名单"。用户顺手一点，桌面就进了白名单 ——
     * 而桌面在"桌面列表"里查不到，白名单页显示不出来、也取消不掉
     * （用户反馈的"计数与实际不符、无法取消勾选"就是这么来的）。
     *
     * <p>桌面 / 系统界面 / 当前输入法 / 我们自己，都不是"要 agent 去操作的应用"，
     * 放行它们没有任何意义，所以直接不提示。
     */
    static boolean pointlessQuickAdd(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(ctx.getPackageName())) return true;
        if ("com.android.systemui".equals(pkg)) return true;
        try {
            android.content.Intent home = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null
                    && pkg.equals(ri.activityInfo.packageName)) return true;
        } catch (Throwable ignore) { }
        try {
            String ime = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.startsWith(pkg)) return true;
        } catch (Throwable ignore) { }
        return false;
    }

    /** 白名单校验：返回当前前台包名（通过），或 null（被拒）。 */
    private static String guard(Context ctx) {
        if (!DshAccessibilityService.isRunning()) {
            throw new IllegalStateException(
                    "无障碍服务未开启。请在 设置 → 无障碍 中打开「DSH 手机控制」");
        }
        String fg = DshAccessibilityService.foregroundPackage();
        Set<String> allow = allowed(ctx);
        if (allow.isEmpty()) {
            throw new IllegalStateException(
                    "白名单为空：请先在 DSH 设置 →「手机控制」里勾选允许操作的应用");
        }
        if (fg == null || fg.isEmpty() || !allow.contains(fg)) {
            // 例外：系统弹窗压在白名单应用上面（见 chooserOverAllowed）
            if (chooserOverAllowed(ctx, allow, fg)) return fg;
            /*
             * 被白名单挡住时顺手给用户一条"一键放行"的通知 ——
             * 否则用户要在聊天里读到"不在白名单"，再自己翻六步设置。
             * 白名单本身没有放宽：加不加仍然由用户点那一下决定。
             *
             * 但桌面/系统界面这类包不发（见 pointlessQuickAdd）：放行桌面没有意义，
             * 而且一旦放行，它在白名单页里还可能显示不出来。
             */
            boolean offered = !pointlessQuickAdd(ctx, fg);
            if (offered) QuickAdd.offer(ctx, fg);
            throw new IllegalStateException("当前前台应用 [" + fg + "] 不在白名单里，已拒绝"
                    + (offered ? "\n（已发一条通知，点一下就能把它加入白名单）"
                               : "\n（当前前台是桌面/系统界面这类应用，放行它没有意义；"
                                 + "要操作哪个应用，请让用户先在「手机控制」里加进白名单）"));
        }
        return fg;
    }

    /**
     * 系统「选择打开方式」这类选择器，是否应该放行。
     *
     * <h3>它解决的是什么</h3>
     * 用户让 agent 操控学习通打开一份文档时，安卓会弹出全屏的
     * 「选择打开方式」（com.android.intentresolver，实测窗口 (0,0,1080,2376)、
     * 面积最大、TYPE_APPLICATION）。它**不属于任何白名单应用**，
     * 于是白名单校验看到的前台就是它 —— agent 连看都看不到这个弹窗，
     * 更别说点「用学习通打开」。
     *
     * <h3>边界有没有被放宽</h3>
     * 放宽得非常有限，三个条件必须同时成立：
     * <ol>
     *   <li>当前前台是个**选择器**（包名在白名单候选里，见 {@link #isChooserPackage}）——
     *       普通第三方应用、系统设置、权限弹窗都不算；</li>
     *   <li>**上一个**前台是白名单应用，且发生在 15 秒内 ——
     *       即"这个弹窗是刚被白名单应用拉起来的"；</li>
     *   <li>是白名单**非空**的前提下（调用方已判定）。</li>
     * </ol>
     * 换句话说：agent 能碰的仍然只有"白名单应用弹出来的选择器"，
     * 而这正是用户要它做的那一步。不在白名单里的应用、以及用户自己
     * 从桌面点出来的选择器，一律照旧拒绝。
     */
    static boolean chooserOverAllowed(Context ctx, Set<String> allow, String fg) {
        if (!isChooserPackage(fg)) return false;
        String prev = DshAccessibilityService.previousPackage();
        if (prev == null || !allow.contains(prev)) return false;
        Log.i(TAG, "放行系统选择器 [" + fg + "]（上一个前台是白名单应用 " + prev + "）");
        return true;
    }

    /**
     * 包名看起来是不是系统的"选择器/处理器"界面。
     *
     * <p>实测本机（OriginOS / Android 16）就是标准的 {@code com.android.intentresolver}；
     * 老rom 上 ResolverActivity 在 {@code android} 里，个别 OEM 会用自己的包名，
     * 所以额外放宽到"包名里含 resolver / chooser"。
     * **刻意不包含 systemui** —— 通知栏里能直接点到权限开关，放行它风险太大。
     */
    private static boolean isChooserPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.equals("com.android.intentresolver")
                || p.equals("android")
                || p.contains("resolver")
                || p.contains("chooser");
    }

    /** 特权命令的执行开关（用户可在「手机权限 → 特权命令」里关掉）。 */
    static final String KEY_ALLOW_SH = "allow_priv_sh";

    static boolean allowPrivSh(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ALLOW_SH, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setAllowPrivSh(Context c, boolean on) {
        c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALLOW_SH, on).apply();
    }

    /**
     * 特权命令：{@code /sh?cmd=<命令>}。
     *
     * <h3>为什么这条走 App 而不是插件直连</h3>
     * Shizuku 的连接只有 App 进程有，容器里的插件拿不到；而且按这套工程一贯的
     * 设计约定，**能力边界必须留在 App 侧**（插件跑在容器里，改一行就能绕开
     * 自己写的检查）。所以插件只做"转发一个 HTTP 请求"。
     *
     * <h3>能力边界（必须在工具描述里对模型讲清楚）</h3>
     * {@code execShell} 拿到的身份是 **shell（uid 2000）**，等价于 {@code adb shell}：
     * <ul>
     *   <li>能读 {@code /sdcard}、{@code /data/local/tmp}、跑 {@code pm}/{@code am}/{@code appops}/{@code settings}；</li>
     *   <li>**读不到**别的应用的私有目录 {@code /data/data/<包名>} —— 那是 root 域，
     *       shell 身份同样进不去（这条不许在 UI 上吹成"能访问私有目录"）；</li>
     *   <li>拿不到 root 的设备上，它能做的上限就是 adb 能做的那些。</li>
     * </ul>
     */
    private static String privSh(Context ctx, java.util.Map<String, String> p) {
        String backend = ShizukuBridge.privilegedBackend(ctx);
        String cmd = p.get("cmd");
        if (cmd == null || cmd.trim().isEmpty()) {
            boolean can = ShizukuBridge.hasPermission() || ShizukuBridge.rootAvailable(false);
            return "特权命令状态\n"
                 + "  后端      : " + backend + "\n"
                 + "  可用      : " + (can ? "是" : "否（先启动 Shizuku 并授权）") + "\n"
                 + "  用户开关  : " + (allowPrivSh(ctx) ? "已允许" : "已禁止") + "\n"
                 + "  用法      : /sh?cmd=ls%20-l%20/sdcard\n"
                 + "  注意      : 身份是 shell（等价 adb shell），读不到别的应用私有目录\n";
        }
        if (!allowPrivSh(ctx)) {
            return "ERROR 特权命令已被用户关闭（手机权限 → 特权命令）";
        }
        if (!ShizukuBridge.hasPermission() && !ShizukuBridge.rootAvailable(false)) {
            return "ERROR 没有可用的特权后端：请先启动 Shizuku 并授权（手机权限页可一键授权）";
        }
        try {
            String out = ShizukuBridge.execShell(ctx, cmd);
            return (out == null || out.trim().isEmpty()) ? "(命令执行完毕，无输出)" : out;
        } catch (Throwable t) {
            return "ERROR 执行失败: " + t;
        }
    }

    /**
     * 私有目录读写：{@code /fs?op=ls|read|write|rm&path=<路径>&data=<写内容>}。
     *
     * <h3>为什么要限制根</h3>
     * 不加限制就是一个"任意路径读写"的后门。这里只放行两类：
     * <ul>
     *   <li><b>共享存储</b>：{@code /sdcard} 及其别名（装了 MANAGE_EXTERNAL_STORAGE 后
     *       连 {@code /sdcard/Android/data} 也能进）；</li>
     *   <li><b>容器根</b>：{@code /root/...} → 映射到 App 私有的 {@code <base>/rootfs/root/...}，
     *       这是 DSH 自己的工作区与配置所在。</li>
     * </ul>
     */
    private static String privFs(Context ctx, java.util.Map<String, String> p) {
        String op = p.get("op");
        if (op == null || op.trim().isEmpty()) op = "ls";
        String path = p.get("path");
        if (path == null || path.trim().isEmpty()) {
            return "ERROR 缺少 path\n"
                 + "用法: /fs?op=ls&path=/sdcard\n"
                 + "     /fs?op=read&path=/sdcard/a.txt\n"
                 + "     /fs?op=write&path=/sdcard/a.txt&data=hello\n"
                 + "     /fs?op=rm&path=/sdcard/a.txt\n"
                 + "允许的根: /sdcard（含 Android/data）与 /root（容器内）\n";
        }
        java.io.File f = resolveFsPath(ctx, path.trim());
        if (f == null) {
            return "ERROR 路径不在允许范围内（只允许共享存储 /sdcard 与容器路径 /root）";
        }
        try {
            switch (op) {
                case "ls": {
                    java.io.File[] kids = f.listFiles();
                    if (kids == null) return "ERROR 不是目录或无法读取: " + f;
                    java.util.Arrays.sort(kids, (a, b) ->
                            a.getName().compareToIgnoreCase(b.getName()));
                    StringBuilder sb = new StringBuilder();
                    sb.append(f.getAbsolutePath()).append("  共 ").append(kids.length).append(" 项\n");
                    for (java.io.File k : kids) {
                        sb.append(k.isDirectory() ? "  [D] " : "  [F] ")
                          .append(k.getName());
                        if (!k.isDirectory()) sb.append("  ").append(k.length()).append("B");
                        sb.append('\n');
                    }
                    return sb.toString();
                }
                case "read": {
                    if (!f.isFile()) return "ERROR 不是文件: " + f;
                    if (f.length() > 512 * 1024) {
                        return "ERROR 文件太大（" + (f.length() / 1024) + " KB），"
                             + "本接口上限 512 KB；大文件请用 read_image / 面板预览";
                    }
                    byte[] b = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int off = 0;
                        while (off < b.length) {
                            int n = in.read(b, off, b.length - off);
                            if (n < 0) break;
                            off += n;
                        }
                    }
                    return new String(b, "UTF-8");
                }
                case "write": {
                    String data = p.get("data");
                    if (data == null) data = "";
                    java.io.File parent = f.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        return "ERROR 建父目录失败: " + parent;
                    }
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        out.write(data.getBytes("UTF-8"));
                    }
                    return "OK 已写入 " + f.getAbsolutePath() + "（" + data.getBytes("UTF-8").length + " B）";
                }
                case "rm": {
                    if (!f.exists()) return "ERROR 不存在: " + f;
                    if (f.isDirectory()) {
                        java.io.File[] kids = f.listFiles();
                        if (kids != null && kids.length > 0) {
                            return "ERROR 目录非空，拒绝递归删除（保护性限制）: " + f;
                        }
                    }
                    return f.delete() ? "OK 已删除 " + f.getAbsolutePath() : "ERROR 删除失败: " + f;
                }
                default:
                    return "ERROR 未知 op: " + op + "（可用 ls / read / write / rm）";
            }
        } catch (Throwable t) {
            return "ERROR " + t;
        }
    }

    /**
     * 把请求里的路径解析成 File，并**校验它落在允许的根之内**。
     *
     * <p>校验用的是 canonical path 前缀比对，这样 {@code /sdcard/../data} 这类
     * 用 {@code ..} 绕出来的路径也会被挡住。
     */
    private static java.io.File resolveFsPath(Context ctx, String path) {
        try {
            // 容器路径 → App 私有 rootfs
            java.io.File base = Env.base(ctx);
            String host = path;
            if (path.equals("/root") || path.startsWith("/root/")) {
                host = new java.io.File(base, "rootfs").getAbsolutePath()
                        + path.substring("/root".length());
            } else if (!path.startsWith("/sdcard")
                    && !path.startsWith("/storage/emulated/0")
                    && !path.startsWith("/data/local/tmp")) {
                return null;
            }
            java.io.File f = new java.io.File(host);
            String canon = f.getCanonicalPath();
            // 允许根（canonical 形式）
            String[] roots = {
                    new java.io.File("/sdcard").getCanonicalPath(),
                    "/storage/emulated/0",
                    "/data/local/tmp",
                    new java.io.File(base, "rootfs/root").getAbsolutePath(),
            };
            for (String r : roots) {
                if (canon.equals(r) || canon.startsWith(r + java.io.File.separator)) return f;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "路径解析失败: " + path, t);
            return null;
        }
    }

    private static String dispatch(Context ctx, String path, java.util.Map<String, String> p) {
        /*
         * 截屏放在最前面，**不跟着"操作后端"走**。
         *
         * 它是一条独立能力：无障碍控件树读不到的应用（微信/QQ）恰恰要靠它，
         * 而它自己也已经有两条后端（无障碍 takeScreenshot / 录屏）会自动选。
         * 如果再叠一层"当前是哪个操作后端"的判断，就会出现
         * "切到直接命令后端后截图突然不可用"这种莫名其妙的组合。
         */
        /*
         * /ping —— 唯一一个**不依赖任何子系统**的接口。
         *
         * 为什么要专门有它：控制桥出过两次"整个没响应"的事故，
         * 而每次都无法区分是"桥死了"还是"某个操作卡住了"，
         * 最后只能重启 App 了事，线索全丢。
         * 有了它，agent（和我）就能先问一句"桥还活着吗"：
         *   /ping 有响应 + 别的接口没响应  → 是那个操作卡住了，桥没事；
         *   /ping 也没响应                → 桥本身出问题了。
         * 里面只读两个 volatile 计数器，保证毫秒级返回。
         */
        if ("/ping".equals(path)) {
            return "PONG\n"
                 + "  已处理请求: " + sReqCount + "\n"
                 + "  当前在途: " + sInFlight.get() + " / " + MAX_IN_FLIGHT + "\n"
                 + "  无障碍: " + (DshAccessibilityService.isRunning() ? "在跑" : "没在跑") + "\n"
                 + "  取根节点在途: " + DshAccessibilityService.rootInFlight() + "\n";
        }

        /*
         * 插件市场用：列出/安装/卸载 DSH 插件。
         *
         * 为什么放在 App 侧而不是做成 DSH 插件：安装插件要跑
         * `dsh plugin add`，那需要容器里的 proot 环境 —— 而**只有 App 能起它**
         * （插件自己跑在 dsh 进程里，没法再起一个 dsh 去改自己的 profile）。
         */
        if ("/plugin".equals(path)) {
            return plugin(ctx, p);
        }

        /*
         * 虚拟屏自动化（群友「如月雨露」的受信任虚拟屏方案）。
         *
         * 为什么"起守护"要绕回 App：守护必须是 shell 身份，而插件跑在容器里（普通 uid），
         * 它叫不动 adb、也没有 Shizuku。App 这边有 Shizuku 那条路，所以
         * `op=ensure` 由 App 去做；取帧/点按那些插件直接打 127.0.0.1:18240 更快，不必绕这里。
         */
        if ("/vscreen".equals(path)) {
            return VScreen.bridge(ctx, p);
        }

        /*
         * 实时进度悬浮条（灵动岛）开关：/hud?on=1 开 / on=0 关 / 无参查状态。
         * 它只读容器写出来的 hud.json，不含任何手机操作，所以**不套白名单**。
         */
        if ("/hud".equals(path)) {
            String on = p.get("on");
            if ("1".equals(on) || "true".equals(on)) {
                String e = HudOverlayService.start(ctx);
                if (e != null) return "ERROR " + e + "\n";
                return "OK 实时进度悬浮条已开启\n";
            }
            if ("0".equals(on) || "false".equals(on)) {
                HudOverlayService.stop(ctx);
                return "OK 实时进度悬浮条已关闭\n";
            }
            boolean can = android.os.Build.VERSION.SDK_INT < 23
                    || android.provider.Settings.canDrawOverlays(ctx);
            return "HUD 悬浮条: " + (HudOverlayService.isRunning() ? "运行中" : "未开启")
                    + " | 悬浮窗权限: " + (can ? "已授权" : "未授权") + "\n";
        }

        /*
         * 特权命令 / 私有目录读写。
         *
         * 这两条**不套应用白名单** —— 白名单管的是"操作哪个 App 的界面"，
         * 而这两条是设备级能力，套上去只会让人误解边界。
         * 真正的边界在各自的实现里（/sh 有用户开关 + 后端检查，/fs 有根目录白名单）。
         */
        if ("/sh".equals(path)) {
            return privSh(ctx, p);
        }
        if ("/fs".equals(path)) {
            return privFs(ctx, p);
        }

        if ("/shot".equals(path)) {
            return shot(ctx, p);
        }

        /*
         * 「看一段」：设备端连续抓帧 → 本地算帧间差异 → 只留关键帧 → 拼成一张分镜表。
         *
         * ┌─ 出处 ────────────────────────────────────────────────────────────────┐
         * │ 这个设计**不是我自己想的**，来自群友 **古德里安** 的建议文档：            │
         * │   《07-建议-让设备端挑帧(3).md》                                      │
         * │ 他先量了数据、指出瓶颈，再给出"把挑帧下沉到设备端"的方案，还附了一份    │
         * │ 用户态参考实现（dsh-phone-plus 的 phone_watch）。本方法就是把它搬进     │
         * │ Java —— 同一套逻辑跑在设备端，省掉每帧的 HTTP + 落盘 + 容器解码。        │
         * │                                                                        │
         * │ ⚠️ 备注这条是因为**之前漏了**：第一版只写了"群友实测"没写是谁、          │
         * │ 出处也没留，后来根本查不到这个设计从哪来。此后凡采纳外部建议都记名+记文档。│
         * └────────────────────────────────────────────────────────────────────────┘
         *
         * 为什么值得下沉（古德里安文档里的实测，2026-10-05）：
         *   原来的客户端做法是"每 500ms 调一次 /shot"，每帧都要走一次 HTTP + 落一次盘 +
         *   在容器里解码算差异 —— 实测 `phone_watch(10 秒)` 端到端 15.6 秒（比视频本身还长），
         *   平均每帧 977ms，其中截图 550~700ms、容器解 PNG 232ms。
         *   瓶颈不是"解码"，而是"每帧都要走一次 HTTP + 落一次盘 + 传一份文件"。
         *   设备端拿的是**原生像素**，比"客户端先解码再比"更省更准，而且不用每帧落盘
         *   （原来连续看屏幕约 70 MB/分钟）。
         *
         * 参数：seconds（默认 10）、fps（默认 2，**上限 3** —— 无障碍截图有系统节流，
         * 间隔小于 ~300ms 就开始丢帧）、threshold（默认 8）、maxFrames（默认 12）、
         * cols（默认 3）、w（每格宽，默认 360）。
         */
        if ("/watch".equals(path)) {
            return watch(ctx, p);
        }

        /*
         * 输入法控制也放在最前面：它和"操作后端"正交。
         *
         * 无障碍被关掉 / 目标应用拦截 ACTION_SET_TEXT 时，
         * 输入法是唯一还能把文字打进去的路（微信就属于这种）。
         */
        if ("/ime".equals(path)) {
            String op = p.getOrDefault("op", "status");
            switch (op) {
                case "activate": {
                    String e = ImeInjector.activate(ctx);
                    return e == null ? "OK 输入法已就绪，可以开始输入\n" : "ERROR " + e + "\n";
                }
                case "restore": {
                    String e = ImeInjector.restore(ctx);
                    return e == null ? "OK 已把默认输入法还原成你自己的\n" : "ERROR " + e + "\n";
                }
                default:
                    return ImeInjector.describe(ctx) + "\n";
            }
        }

        /*
         * 用户选了「直接命令」后端且 Shizuku 可用时，这些动作改走 shell。
         *
         * 注意白名单校验在 ShellControl 内部照原样执行 —— 这条路的权限比无障碍大，
         * 边界更不能松。不在这份 switch 里的接口（/apps 等）继续走无障碍实现，
         * 因为它们本来就不需要 shell。
         */
        if (ShellControl.active(ctx)) {
            try {
                switch (path) {
                    case "/status": return ShellControl.status(ctx);
                    case "/ui":     return ShellControl.dumpUi(ctx);
                    case "/tap":    return ShellControl.tap(ctx,
                            Integer.parseInt(p.get("x")), Integer.parseInt(p.get("y")));
                    case "/click":  return ShellControl.clickText(ctx, p.get("text"));
                    case "/swipe":  return ShellControl.swipe(ctx,
                            Integer.parseInt(p.get("x1")), Integer.parseInt(p.get("y1")),
                            Integer.parseInt(p.get("x2")), Integer.parseInt(p.get("y2")),
                            Integer.parseInt(p.getOrDefault("ms", "300")));
                    case "/text":   return ShellControl.inputText(ctx, p.getOrDefault("value", ""));
                    case "/key":    return ShellControl.key(ctx, p.getOrDefault("name", ""));
                    case "/open": {
                        String q = p.get("q");
                        if (q == null || q.trim().isEmpty()) return "ERROR 需要 q=<包名或应用名>\n";
                        String pkg = resolveAllowed(ctx, q.trim());
                        if (pkg == null) return openMissMessage(ctx, q.trim());
                        return ShellControl.open(ctx, pkg);
                    }
                    default: break;   // 其余接口不涉及 shell，落到下面的无障碍实现
                }
            } catch (Throwable t) {
                return "ERROR " + t.getMessage() + "\n";
            }
        }
        switch (path) {
            case "/status": {
                Set<String> allow = allowed(ctx);
                String fg = DshAccessibilityService.foregroundPackage();
                StringBuilder sb = new StringBuilder();
                sb.append("无障碍服务: ").append(DshAccessibilityService.isRunning() ? "已开启" : "未开启").append("\n")
                  .append("当前前台: ").append(fg == null || fg.isEmpty() ? "(未知)" : fg).append("\n")
                  .append("白名单(").append(allow.size()).append("): ").append(allow).append("\n")
                  .append("当前是否允许操作: ")
                  .append(DshAccessibilityService.isRunning() && allow.contains(fg) ? "是" : "否").append("\n");
                int inflight = DshAccessibilityService.rootInFlight();
                if (inflight > 0) {
                    sb.append("界面读取正在等待的调用: ").append(inflight)
                      .append("（数字持续不降说明取界面树在被系统拖住）\n");
                }
                sb.append("已处理请求: ").append(sReqCount).append("\n");
                if (!sSlow.isEmpty()) {
                    sb.append("最近的慢请求:\n");
                    for (String r : sSlow) sb.append("  ").append(r).append('\n');
                }
                return sb.toString();
            }
            case "/ui": {
                String fg = guard(ctx);
                /*
                 * 先试"多窗口合并"：系统应用常把顶部工具栏放在独立窗口里，
                 * 只读活动窗口会**看不到那一排**（实测设置页就是这么丢的）。
                 * 只有一扇窗时它返回 null，自动走下面的单窗口路径。
                 */
                String multi = DshAccessibilityService.dumpAllWindows();
                if (multi != null) {
                    return "前台应用: " + fg + "\n" + multi;
                }
                AccessibilityNodeInfo root = DshAccessibilityService.root();
                if (root == null) return "ERROR: 取不到界面（可能正在切换窗口）\n";
                return "前台应用: " + fg + "\n" + DshAccessibilityService.dumpTree(root);
            }
            case "/windows": {
                /*
                 * 诊断用：列出所有窗口的包名/类型/区域/根节点是否可读。
                 * **刻意不套白名单** —— 它输出的是窗口元信息（不含控件文本），
                 * 而"白名单把系统弹窗误判成前台应用"这种故障，
                 * 恰恰只有在被拒的那一刻看窗口列表才能定位。
                 */
                return DshAccessibilityService.describeWindows();
            }
            case "/services": {
                /*
                 * 容器常驻服务的诊断接口。
                 * 不套白名单：它管的是容器里的进程，跟"操作哪个应用"无关。
                 */
                String op = p.getOrDefault("op", "status");
                java.util.List<Services.Item> list = Services.load(ctx);
                if ("add".equals(op) || "remove".equals(op)) {
                    /*
                     * 让 agent（或脚本）也能配服务 —— 不然"配一次"这件事
                     * 只能靠用户在手机上一行行敲命令。
                     */
                    if ("remove".equals(op)) {
                        String id = p.get("id");
                        if (id == null) return "ERROR 需要 id\n";
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).id.equals(id)) {
                                Services.stop(ctx, list.get(i));
                                list.remove(i);
                                break;
                            }
                        }
                        Services.save(ctx, list);
                        return "OK 已删除 " + id + "\n";
                    }
                    String cmd = p.get("cmd");
                    if (cmd == null || cmd.trim().isEmpty()) return "ERROR 需要 cmd=<启动命令>\n";
                    String name = p.getOrDefault("name", "");
                    if (name.trim().isEmpty()) {
                        name = cmd.trim().length() > 24 ? cmd.trim().substring(0, 24) : cmd.trim();
                    }
                    Services.Item it = new Services.Item(Services.newId(), name.trim(), cmd.trim(), true);
                    list.add(it);
                    Services.save(ctx, list);
                    String r = Services.ensure(ctx, it);
                    return "OK 已添加并启动 [" + it.name + "] id=" + it.id + "\n  " + r + "\n";
                }
                if ("restart".equals(op)) {
                    String id = p.get("id");
                    StringBuilder sb = new StringBuilder();
                    for (Services.Item it : list) {
                        if (id != null && !id.isEmpty() && !it.id.equals(id)) continue;
                        if (!it.enabled) continue;
                        Services.resetFails(it.id);
                        sb.append("  ").append(it.name).append(": ")
                          .append(Services.ensure(ctx, it)).append('\n');
                    }
                    return sb.length() == 0 ? "没有已启用的服务\n" : "已重启:\n" + sb;
                }
                StringBuilder sb = new StringBuilder();
                if (list.isEmpty()) return "还没有配置常驻服务（App 设置 →「常驻服务（本机）」）\n";
                java.util.Map<String, String> st = Services.states(ctx);
                sb.append("常驻服务 ").append(list.size()).append(" 个:\n");
                for (Services.Item it : list) {
                    sb.append("  [").append(it.id).append("] ").append(it.name).append('\n')
                      .append("      命令: ").append(it.cmd).append('\n')
                      .append("      状态: ").append(Services.describe(it, st.get(it.id))).append('\n')
                      .append("      日志: /tmp/dsh-svc-").append(it.id).append(".log（容器内）\n");
                }
                return sb.toString();
            }
            case "/upgrade": {
                /*
                 * 内核版本检测与一键升级。不套白名单：它管的是容器里的 DSH 程序，
                 * 跟"操作哪个应用"无关。
                 */
                String op = p.getOrDefault("op", "status");
                if ("check".equals(op)) {
                    KernelUpgrade.Tags t = KernelUpgrade.fetchTags(ctx, true);
                    String cur = KernelUpgrade.installedVersion(ctx);
                    if (t == null) return "查不到最新版本（没网？）\n当前: " + cur + "\n";
                    String up = KernelUpgrade.availableUpdate(ctx);
                    return "当前内核: " + cur + "\n"
                         + "npm latest: " + t.latest + "\n"
                         + "npm next  : " + t.next + "\n"
                         + "可升级到  : " + (up == null ? "（已是最新）" : up) + "\n";
                }
                if ("run".equals(op)) {
                    return KernelUpgrade.runAsync(ctx, p.get("ver"));
                }
                return "当前内核: " + KernelUpgrade.installedVersion(ctx) + "\n"
                     + KernelUpgrade.progressText();
            }
            case "/icon": {
                /*
                 * 桌面图标配色。不套白名单：它跟"操作哪个应用"无关。
                 * 切换会启用/禁用 activity-alias —— 系统会重启 App，这是正常的。
                 */
                String op = p.getOrDefault("op", "list");
                if ("set".equals(op)) {
                    String id = p.get("id");
                    for (IconSwitcher.Variant v : IconSwitcher.all(ctx)) {
                        if (v.id.equals(id)) {
                            String err = IconSwitcher.apply(ctx, v);
                            return err == null
                                    ? "OK 已切换到「" + v.label + "」（" + v.id + "）\n"
                                      + "桌面图标和通知栏图标都会用这套配色。App 会被系统重启一下。\n"
                                    : "ERROR " + err + "\n";
                        }
                    }
                    return "ERROR 没有这个配色: " + id + "\n";
                }
                StringBuilder sb = new StringBuilder();
                IconSwitcher.Variant cur = IconSwitcher.current(ctx);
                sb.append("当前配色: ").append(cur.label).append("（").append(cur.id).append("）\n");
                sb.append("可选:\n");
                for (IconSwitcher.Variant v : IconSwitcher.all(ctx)) {
                    sb.append("  ").append(v.id.equals(cur.id) ? "*" : " ")
                      .append(v.id).append("  ").append(v.label).append('\n');
                }
                return sb.toString();
            }
            case "/mictest": {
                /*
                 * 诊断：在 App 进程里直接用原生 AudioRecord 开一次麦。
                 * 网页 getUserMedia 报 NotReadableError 时，用它区分
                 * "整个 App 开不了麦" 和 "只有 WebView 开不了"。
                 * 不套白名单：它只碰麦克风，不读别的应用。
                 */
                return MicProbe.run(ctx);
            }
            case "/tap": {
                guard(ctx);
                int x = Integer.parseInt(p.get("x")), y = Integer.parseInt(p.get("y"));
                return DshAccessibilityService.tap(x, y) ? "OK 已点击 (" + x + "," + y + ")\n" : "ERROR 点击失败\n";
            }
            case "/click": {
                guard(ctx);
                String t = p.get("text");
                AccessibilityNodeInfo n = DshAccessibilityService.findByText(DshAccessibilityService.root(), t);
                if (n == null) return "ERROR 界面上找不到文字: " + t + "\n";
                return DshAccessibilityService.clickNode(n) ? "OK 已点击「" + t + "」\n" : "ERROR 点击失败\n";
            }
            case "/swipe": {
                guard(ctx);
                return DshAccessibilityService.swipe(
                        Integer.parseInt(p.get("x1")), Integer.parseInt(p.get("y1")),
                        Integer.parseInt(p.get("x2")), Integer.parseInt(p.get("y2")),
                        Integer.parseInt(p.getOrDefault("ms", "300"))) ? "OK 已滑动\n" : "ERROR 滑动失败\n";
            }
            case "/text": {
                guard(ctx);
                String value = p.getOrDefault("value", "");
                /*
                 * 两条路依次试：
                 *   无障碍 ACTION_SET_TEXT —— 快、不切输入法，绝大多数应用够用；
                 *   内置输入法 —— 微信这类把输入框标成不可设置的应用**只认这条**。
                 * 顺序不能反：只是打个字就切输入法，对用户干扰太大。
                 */
                if (DshAccessibilityService.setTextOnFocused(
                        DshAccessibilityService.root(), value)) {
                    /*
                     * 无障碍这条路成功时**不会**碰输入法；但系统里可能还留着
                     * 我们上次切过去的输入法（实测就是这么卡住的）——
                     * 所以这里补一次"只要还是我们的，就安排自动切回"。
                     */
                    ImeInjector.reconcile(ctx);
                    return "OK 已输入（无障碍方式）\n";
                }
                String err = ImeInjector.type(ctx, value);
                if (err == null) {
                    return "OK 已输入（输入法方式）\n"
                         + "  · 默认输入法已临时切成「DSH 输入」，这样微信这类应用才收得到\n"
                         + "  · 打完 **5 秒后会自动切回**你自己的输入法，不用管\n"
                         + "  · 想立刻切回也可以让 agent 执行 `phone ime restore`\n";
                }
                return "ERROR 两种方式都没能输入：\n"
                     + "  · 无障碍：没有找到可输入的焦点框（或目标应用不允许设置文字）\n"
                     + "  · 输入法：" + err + "\n";
            }
            case "/key": {
                String name = p.getOrDefault("name", "");
                int action;
                switch (name) {
                    case "back": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK; break;
                    case "home": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME; break;
                    case "recents": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS; break;
                    case "notifications": action = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
                    default: return "ERROR 不支持的按键: " + name + "（可用 back/home/recents/notifications）\n";
                }
                // 全局按键不改动目标应用数据，但仍要求服务开启
                if (!DshAccessibilityService.isRunning()) return "ERROR 无障碍服务未开启\n";
                return DshAccessibilityService.globalAction(action) ? "OK 已发送 " + name + "\n" : "ERROR 失败\n";
            }
            case "/apps": {
                Set<String> allow = allowed(ctx);
                StringBuilder sb = new StringBuilder("白名单应用:\n");
                for (String a : allow) sb.append("  ").append(a).append('\n');
                if (allow.isEmpty()) sb.append("  (空)\n");
                return sb.toString();
            }
            case "/open": {
                /*
                 * 启动白名单里的应用。
                 *
                 * 实测暴露的需求：没有这个接口时，agent 只能说"请你手动点开 QQ"——
                 * 而"打开某应用"本来是操控应用最基本的一步。
                 * 安全上仍然只允许启动**白名单内**的应用，
                 * 否则 agent 就能把所有 App 都拉起来，"只能操作指定 App"的约束会被绕开。
                 */
                String q = p.get("q");
                if (q == null || q.trim().isEmpty()) return "ERROR 需要 q=<包名或应用名>\n";
                q = q.trim();
                Set<String> allow = allowed(ctx);
                if (allow.isEmpty()) {
                    return "ERROR 白名单为空：请先在 DSH 设置 →「手机控制」里勾选允许操作的应用\n";
                }
                String hitPkg = resolveAllowed(ctx, q);
                if (hitPkg == null) return openMissMessage(ctx, q);
                String hitLabel = hitPkg;
                try {
                    hitLabel = ctx.getPackageManager()
                            .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(hitPkg, 0))
                            .toString();
                } catch (Throwable ignore) { }
                try {
                    android.content.pm.PackageManager pm = ctx.getPackageManager();
                    android.content.Intent launch = pm.getLaunchIntentForPackage(hitPkg);
                    if (launch == null) return "ERROR 该应用没有可启动的入口: " + hitPkg + "\n";
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return "OK 已启动「" + hitLabel + "」(" + hitPkg + ")\n"
                         + "稍等 1-2 秒后可再用 phone ui 读取它的界面。\n";
                } catch (Throwable t) {
                    return "ERROR 启动失败: " + t + "\n";
                }
            }
            default:
                return "ERROR 404: 未知接口 " + path + "\n"
                     + "可用: /status /ui /windows /shot?full=1 /watch?seconds=10&fps=2 "
                     + "/tap?x&y /click?text /swipe?x1&y1&x2&y2&ms "
                     + "/text?value /key?name /apps /open?q=应用名或包名 /hud?on=1|0\n"
                     + "/sh?cmd=命令 /fs?op=ls|read|write|rm&path=路径\n"
                     + "提示: 连续截图请勿快于约 1~2fps（无障碍截图有系统节流，"
                     + "间隔小于 ~300ms 会静默丢帧）；要看一段就直接用 /watch。\n";
        }
    }
}
