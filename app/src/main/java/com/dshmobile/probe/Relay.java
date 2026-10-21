package com.dshmobile.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
/**
 * 「中转站」里那几段**纯逻辑**：数据模型 + Provider ID 规则 + 写进 DSH 配置的那段 YAML。
 *
 * <h3>为什么和 {@link Providers} 分开</h3>
 * {@code Providers} 要存密钥（SharedPreferences）、要发网络请求（都是 Android），
 * 测不了；而这里决定的是"**写进 DSH 的那个文件长什么样**"——写坏了不是"中转站不能用"，
 * 是用户的主题、模型选择、插件设置一起遭殃。所以这部分不碰 Android API，
 * 由 {@code tools/ProvidersSelfTest.java} 拿**真机上的文件原文**做回归。
 *
 * <h3>DSH 0.2 到底吃什么（真机验证后改正过一次）</h3>
 * 一开始按 DSH 0.1.5 桌面的形态，以为设置写在 {@code ~/.dsh/settings.yaml}。
 * 真机上 {@code ls ~/.dsh/} 只有 {@code settings.yaml.imported}（迁移后留下的），
 * 真正的设置在 **profile 的补丁层**里，长这样：
 *
 * <pre>
 *   # ~/.dsh/profiles/web/cordis.patch.yml —— 顶层是一个"补丁条目数组"
 *   - id: agent-default-model
 *     name: "@deepseek-ai/dsh-agent-default-model"
 *     config:
 *       provider: deepseek-official
 *       model: deepseek-flash
 * </pre>
 *
 * 也就是说：**DSH 0.2 的"设置"就是 id 定向的 config 覆盖**。所以自定义提供方要写成：
 *
 * <pre>
 *   - id: llm-pi-ai
 *     name: "@deepseek-ai/dsh-llm-pi-ai"
 *     config:
 *       providers:
 *         acme-gateway:
 *           apiKeyEnv: ACME_GATEWAY_API_KEY   # 凭据引用：配置里不放密钥
 *           api: openai-completions           # 或 openai-responses / anthropic-messages
 *           baseURL: https://gateway.example/v1
 *           models:
 *             - id: grok-4.7
 *               name: grok-4.7
 *               input: [text, image]
 * </pre>
 *
 * 为什么非要有这条：{@code dsh-llm-pi-ai} 在 base 层是**裸挂载、休眠**的，
 * 源码注释原话是 "mounted dormant: zero routes … until a {@code llm-pi-ai:}
 * settings section supplies provider …" —— 没有这条配置，一条路由都不会注册，
 * 模型也不会出现在模型选择器里。
 */
public final class Relay {

    private Relay() { }

    /** 支持的三种协议（与 DSH 的 pi-ai 适配器一一对应）。 */
    public static final String API_OPENAI_CHAT = "openai-completions";
    public static final String API_OPENAI_RESP = "openai-responses";
    public static final String API_ANTHROPIC   = "anthropic-messages";

    /** 多提供方适配器那条补丁条目的 id 与包名。 */
    public static final String PI_AI_ENTRY_ID = "llm-pi-ai";
    public static final String PI_AI_PLUGIN_NAME = "@deepseek-ai/dsh-llm-pi-ai";

    // ─────────────────────── 官方 API（DeepSeek 自己那家） ───────────────────────
    //
    // 【为什么官方 API 单独列出来，而不是当成一条普通"中转站"】
    // DSH 里官方的 provider 名是 **deepseek-official**（真机上 agent-default-model
    // 那条补丁配的就是 `provider: deepseek-official` / `model: deepseek-flash`），
    // 它由 DSH 自带的适配器提供，密钥走 **环境变量 DEEPSEEK_API_KEY**
    // （App 已经这么注入了）。所以它和我们写进 `llm-pi-ai.providers` 的那些
    // 自定义 route 是**两套东西**：
    //   · 官方：改密钥 = 改环境变量（App 私有 prefs → 启动参数），不需要碰配置文件；
    //   · 自定义：写 cordis.patch.yml 的 `llm-pi-ai` 条目。
    // 正因为是两套，界面上以前它们散在两个地方（「API Key」对话框 + 「中转站」），
    // 用户根本看不出"我到底在用哪家"。现在把官方这张卡放在「中转站」页最上面，
    // 两边的配置在同一个屏幕上看得见。

    /** 官方 API 的基址（只用于界面上展示与连通性测试）。 */
    public static final String OFFICIAL_BASE = "https://api.deepseek.com";
    /** 官方 API 的 provider 名（DSH 的默认值，别改）。 */
    public static final String OFFICIAL_PROVIDER = "deepseek-official";

    /**
     * 官方 API 的「人话报错」：把 HTTP 状态码翻译成用户能照做的下一步。
     *
     * <p>为什么必须做这个：DSH 里报错是一串英文（
     * {@code MISSING_CREDENTIAL} / {@code 401} / {@code fetch failed}），
     * 用户看完只知道"爆红了"。这里把它翻成"你该去做什么"。
     *
     * @param code HTTP 状态码（0 表示连都没连上）
     * @param body 响应体（可能为空）
     * @return 给用户看的一句话
     */
    public static String explainOfficialError(int code, String body) {
        String b = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (b.length() > 160) b = b.substring(0, 160) + "…";
        switch (code) {
            case 0:
                return "连不上 api.deepseek.com —— 检查手机网络（或是否被代理/VPN 拦住）。";
            case 400:
                return "请求被拒绝（400）。多半是模型名不对，或这个 Key 没有该模型的权限。原始返回：" + b;
            case 401:
                return "密钥不对或已失效（401）—— 去 platform.deepseek.com 重新生成一个再填进来。";
            case 402:
                return "余额不足（402）—— 账户里没钱了，充值后即可恢复。";
            case 403:
                return "没有权限（403）—— 这个 Key 可能被限制或已停用。原始返回：" + b;
            case 404:
                return "地址不对（404）—— 官方基址应当是 " + OFFICIAL_BASE + "（不要带 /v1 之外的后缀）。";
            case 429:
                return "请求太频繁或额度用尽（429）—— 等一会儿再试，或在「重试策略」里把次数调大。";
            case 500: case 502: case 503: case 504:
                return "官方服务临时故障（" + code + "）—— 这不是你的配置问题，稍后自动重试即可。";
            default:
                return "HTTP " + code + "：" + b;
        }
    }

    /** 一个模型条目。 */
    public static final class Model {
        public String id;
        public String name;
        public boolean image;          // 支持图像输入（文本+图像），否则纯文本

        public Model(String id, String name, boolean image) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.image = image;
        }
    }

    /** 一个中转站（= DSH 里的一条 llm route）。 */
    public static final class Route {
        public String id;              // Provider ID，小写字母开头
        public String name;            // 显示名称（可空）
        public String api = API_OPENAI_CHAT;
        public String baseUrl;
        public final List<Model> models = new ArrayList<>();

        /*
         * 重试策略（用户要求"自定义连不上重试次数"）。
         *
         * 依据：pi-ai 的 README「每个 profile 都可以设置 retryPolicy；省略时使用
         * normal mode、最多重试五次」，类型定义在 dsh-llm 的 retry-policy.d.ts：
         *   NormalRetryPolicyConfig { mode:'normal', maxRetries?, retryableCodes?, backoff? }
         *   AlwaysRetryPolicyConfig { mode:'always', backoff? }
         *
         * ⚠️ retryPolicy 是**提供方路由级**的，不是全局的 —— 所以每个提供方各存一份。
         * 用户没动过这几个字段时**一个字都不写**（见下文 renderRoutes），
         * 这样配置文件保持和以前完全一样，不会因为"多了几个默认字段"而改变行为。
         */
        public String retryMode = "";      // "" = 不写（用 DSH 默认 normal/5）| "normal" | "always"
        public int retryMax = -1;          // <0 = 不写
        public int retryDelayMs = -1;      // <0 = 不写（DSH 默认 500）
        public int retryMaxDelayMs = -1;   // <0 = 不写（DSH 默认 10000）

        public Route() { }

        /** 用户是否改过重试策略（改过才写进 YAML）。 */
        public boolean hasRetry() {
            return (retryMode != null && !retryMode.isEmpty()) || retryMax >= 0
                    || retryDelayMs >= 0 || retryMaxDelayMs >= 0;
        }

        /** 给人看的一行重试说明（界面上显示）。 */
        public String retryLabel() {
            if (!hasRetry()) return "重试：跟随 DSH 默认（normal，5 次）";
            String mode = "always".equals(retryMode) ? "always（一直重试到成功）" : "normal（只重试可恢复的错误）";
            StringBuilder sb = new StringBuilder("重试：").append(mode);
            if (retryMax >= 0 && !"always".equals(retryMode)) sb.append("，最多 ").append(retryMax).append(" 次");
            if (retryDelayMs >= 0) sb.append("，首次间隔 ").append(retryDelayMs).append("ms");
            if (retryMaxDelayMs >= 0) sb.append("，最长间隔 ").append(retryMaxDelayMs).append("ms");
            return sb.toString();
        }

        public String apiLabel() {
            if (API_OPENAI_RESP.equals(api)) return "OpenAI Responses";
            if (API_ANTHROPIC.equals(api)) return "Anthropic Messages";
            return "OpenAI Chat Completions";
        }
    }

    /**
     * Provider ID 校验。
     *
     * <p>规则来自 DSH：**小写字母开头**，只允许小写字母/数字/连字符 ——
     * 因为 id 会被用来派生凭据名（{@code acme-gateway} → {@code ACME_GATEWAY_API_KEY}），
     * 大写字母、下划线、点号都会让派生出来的凭据名对不上，
     * 于是 DSH 报的是 {@code MISSING_CREDENTIAL}，用户完全看不出是名字写错了。
     *
     * @return 空串表示合法，否则是给用户看的原因
     */
    public static String validateId(String id) {
        if (id == null || id.trim().isEmpty()) return "Provider ID 不能为空";
        String s = id.trim();
        if (!s.matches("[a-z][a-z0-9-]*")) {
            return "Provider ID 要以小写字母开头，只能用小写字母、数字和连字符";
        }
        if (s.length() > 40) return "Provider ID 太长了（40 个字符以内）";
        return "";
    }

    /** id → 凭据名（{@code acme-gateway} → {@code ACME_GATEWAY_API_KEY}）。 */
    public static String envNameOf(String id) {
        String s = id == null ? "" : id.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return s + "_API_KEY";
    }

    /** YAML 标量：需要时加引号（免得地址里的冒号、井号把文件写坏）。 */
    public static String yamlScalar(String v) {
        String s = v == null ? "" : v;
        boolean plain = !s.isEmpty() && s.matches("[A-Za-z0-9_./:@+\\-]+")
                && !s.startsWith("-") && !s.startsWith("?") && !s.startsWith("!")
                && !s.contains(": ") && !s.endsWith(":");
        if (plain) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 渲染我们这些 route（缩进 6 空格起，正好落在
     * {@code - id: llm-pi-ai} → {@code config:} → {@code providers:} 下面）。
     */
    public static String renderRoutes(List<Route> routes) {
        StringBuilder sb = new StringBuilder();
        for (Route r : routes) {
            sb.append("      ").append(r.id).append(":\n");
            sb.append("        apiKeyEnv: ").append(envNameOf(r.id)).append('\n');
            if (r.name != null && !r.name.trim().isEmpty()) {
                sb.append("        displayName: ").append(yamlScalar(r.name.trim())).append('\n');
            }
            sb.append("        api: ").append(r.api).append('\n');
            sb.append("        baseURL: ").append(yamlScalar(r.baseUrl == null ? "" : r.baseUrl.trim())).append('\n');
            // 重试策略：没改过就一个字都不写（保持与以前完全一致的配置）
            if (r.hasRetry()) {
                sb.append("        retryPolicy:\n");
                sb.append("          mode: ").append("always".equals(r.retryMode) ? "always" : "normal").append('\n');
                // always 模式没有 maxRetries（它一直重到成功/取消），写了会被 schema 拒绝
                if (!"always".equals(r.retryMode) && r.retryMax >= 0) {
                    sb.append("          maxRetries: ").append(r.retryMax).append('\n');
                }
                if (r.retryDelayMs >= 0 || r.retryMaxDelayMs >= 0) {
                    sb.append("          backoff:\n");
                    if (r.retryDelayMs >= 0) sb.append("            initialDelayMs: ").append(r.retryDelayMs).append('\n');
                    if (r.retryMaxDelayMs >= 0) sb.append("            maxDelayMs: ").append(r.retryMaxDelayMs).append('\n');
                }
            }
            sb.append("        models:\n");
            for (Model m : r.models) {
                sb.append("          - id: ").append(yamlScalar(m.id)).append('\n');
                sb.append("            name: ").append(yamlScalar(m.name)).append('\n');
                sb.append("            input: [").append(m.image ? "text, image" : "text").append("]\n");
            }
        }
        return sb.toString();
    }

    /** 我们那条补丁条目的头部。 */
    private static String entryHeader() {
        return "- id: " + PI_AI_ENTRY_ID + "\n"
             + "  name: \"" + PI_AI_PLUGIN_NAME + "\"\n"
             + "  config:\n"
             + "    providers:\n";
    }

    /**
     * 把 {@code llm-pi-ai} 那条补丁条目合并进 cordis.patch.yml 的原文里。
     *
     * <p>要处理的情况：
     * <ol>
     *   <li>文件里没有这条 → 追加一条；</li>
     *   <li>有这条、里面有**别人（DSH 网页端）加的** provider → 原样保留，
     *       只重写属于我们的（用户明确要的，也是界面上那句承诺）；</li>
     *   <li>有我们管的 provider → 用新内容替换；</li>
     *   <li>我们删掉了某个 provider → 它也得跟着消失，不能因为"当前列表为空"
     *       就认不出自己写过什么（所以调用方要把 {@code owned} 传进来）。</li>
     * </ol>
     * 其它补丁条目（主题、模型选择…）一个字节都不动。
     *
     * @param yaml  cordis.patch.yml 原文（null/空 = 新文件）
     * @param ours  我们管理的路由
     * @param owned 我们写过的 provider id（含这次要删的）
     * @return 合并后的全文
     */
    public static String mergeSection(String yaml, List<Route> ours, java.util.Collection<String> owned) {
        String src = yaml == null ? "" : yaml.replace("\r\n", "\n");
        LinkedHashSet<String> ourIds = new LinkedHashSet<>();
        if (owned != null) ourIds.addAll(owned);
        for (Route r : ours) ourIds.add(r.id);

        String[] lines = src.split("\n", -1);
        int start = -1, end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.startsWith("- id: " + PI_AI_ENTRY_ID)
                    || l.equals("- id: " + PI_AI_ENTRY_ID)) {
                start = i;
                continue;
            }
            // 顶层数组的下一个条目 = 这条的结束
            if (start >= 0 && i > start && l.startsWith("- ")) { end = i; break; }
        }

        // 收集"别人加的 provider"原文（缩进 6 空格那一层）
        StringBuilder foreign = new StringBuilder();
        if (start >= 0) {
            boolean inProviders = false;
            List<String> block = new ArrayList<>();
            String blockId = null;
            for (int i = start + 1; i < end; i++) {
                String l = lines[i];
                if (l.startsWith("    providers:")) { inProviders = true; continue; }
                if (!inProviders) continue;
                boolean head = l.startsWith("      ") && !l.startsWith("        ") && l.trim().endsWith(":");
                if (head) {
                    if (blockId != null && !ourIds.contains(blockId)) {
                        for (String b : block) foreign.append(b).append('\n');
                    }
                    block.clear();
                    blockId = l.trim().substring(0, l.trim().length() - 1);
                }
                if (blockId != null) block.add(l);
            }
            if (blockId != null && !ourIds.contains(blockId)) {
                for (String b : block) foreign.append(b).append('\n');
            }
        }

        StringBuilder entry = new StringBuilder();
        if (foreign.length() > 0 || !ours.isEmpty()) {
            entry.append(entryHeader());
            entry.append(foreign);
            entry.append(renderRoutes(ours));
        }

        StringBuilder out = new StringBuilder();
        if (start < 0) {
            out.append(src);
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append(entry);
        } else {
            for (int i = 0; i < start; i++) out.append(lines[i]).append('\n');
            out.append(entry);
            for (int i = end; i < lines.length; i++) out.append(lines[i]).append('\n');
        }
        String res = out.toString().replaceAll("\\n{3,}", "\n\n");
        while (res.endsWith("\n\n")) res = res.substring(0, res.length() - 1);
        if (!res.endsWith("\n")) res = res + "\n";
        return res;
    }

    /** 便利重载：把"我们的 id"当作已拥有的全部。 */
    public static String mergeSection(String yaml, List<Route> ours) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Route r : ours) ids.add(r.id);
        return mergeSection(yaml, ours, ids);
    }

    /** 文件里所有补丁条目的 id（写入前后比对用：少了任何一个就说明我们把它写坏了）。 */
    public static List<String> entryIds(String yaml) {
        List<String> out = new ArrayList<>();
        if (yaml == null) return out;
        for (String l : yaml.replace("\r\n", "\n").split("\n")) {
            if (!l.startsWith("- id:")) continue;
            String id = l.substring("- id:".length()).trim();
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }
}
