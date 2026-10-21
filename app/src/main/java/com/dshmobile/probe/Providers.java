package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「中转站」里需要 Android 的那一半：**存**（prefs 里的提供方与密钥）、
 * **问**（向中转站要模型列表）、**写**（把配置落进容器里的 DSH 设置）。
 *
 * <p>纯逻辑（数据模型、id 规则、YAML 渲染、合并、顶层键扫描）在 {@link Relay} 里，
 * 那一半有回归测试；这里只做胶水，读起来应该很短。
 *
 * <h3>密钥放哪</h3>
 * 只放 App 私有目录的 prefs 里。写进 DSH 设置的只有 {@code apiKeyEnv: XXX_API_KEY}
 * 这种**凭据引用**；启动容器时 {@link #envPairs} 把它作为环境变量带进去
 * （容器命令是 {@code env -i}，所以必须显式注入）。任何文件里都不会出现密钥。
 */
public final class Providers {

    private Providers() { }

    private static final String K_LIST = "relay_providers";
    private static final String K_KEYS = "relay_keys";          // id → 密钥（只在本机）
    private static final String K_OWNED = "relay_owned_ids";    // 我们写进 DSH 设置过的 id

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PhoneBridge.PREFS, Context.MODE_PRIVATE);
    }

    // ─────────────────────────── 存（App 私有目录）───────────────────────────

    public static List<Relay.Route> load(Context c) {
        List<Relay.Route> out = new ArrayList<>();
        try {
            String s = sp(c).getString(K_LIST, "[]");
            JSONArray arr = new JSONArray(s == null ? "[]" : s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(fromJson(o));
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public static void store(Context c, List<Relay.Route> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Relay.Route r : list) arr.put(toJson(r));
            sp(c).edit().putString(K_LIST, arr.toString()).apply();
        } catch (Throwable ignore) { }
    }

    /** 新增或更新一条（按 id 匹配）。 */
    public static void upsert(Context c, Relay.Route r) {
        List<Relay.Route> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id != null && list.get(i).id.equals(r.id)) {
                list.set(i, r);
                store(c, list);
                return;
            }
        }
        list.add(r);
        store(c, list);
    }

    public static void remove(Context c, String id) {
        List<Relay.Route> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (id.equals(list.get(i).id)) list.remove(i);
        }
        store(c, list);
        setKey(c, id, "");
    }

    // ── 密钥：只在本机 ──

    public static String key(Context c, String id) {
        try {
            String s = sp(c).getString(K_KEYS, "{}");
            return new JSONObject(s == null ? "{}" : s).optString(id, "");
        } catch (Throwable t) { return ""; }
    }

    public static void setKey(Context c, String id, String key) {
        try {
            String s = sp(c).getString(K_KEYS, "{}");
            JSONObject o = new JSONObject(s == null ? "{}" : s);
            o.put(id, key == null ? "" : key);
            sp(c).edit().putString(K_KEYS, o.toString()).apply();
        } catch (Throwable ignore) { }
    }

    /** 启动容器时要注入的环境变量（供 {@link Env#containerCommand} 使用）。 */
    public static List<String> envPairs(Context c) {
        List<String> out = new ArrayList<>();
        for (Relay.Route r : load(c)) {
            String k = key(c, r.id);
            if (k == null || k.trim().isEmpty()) continue;
            out.add(Relay.envNameOf(r.id) + "=" + k.trim());
        }
        return out;
    }

    // ─────────────────────────── JSON 与纯逻辑之间的桥 ───────────────────────────

    private static JSONObject toJson(Relay.Route r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", r.id);
        o.put("name", r.name == null ? "" : r.name);
        o.put("api", r.api);
        o.put("baseUrl", r.baseUrl);
        JSONArray arr = new JSONArray();
        for (Relay.Model m : r.models) {
            JSONObject mo = new JSONObject();
            mo.put("id", m.id);
            mo.put("name", m.name);
            mo.put("image", m.image);
            arr.put(mo);
        }
        o.put("models", arr);
        // 重试策略（老数据里没有这些键，读的时候用默认值兜住）
        o.put("retryMode", r.retryMode == null ? "" : r.retryMode);
        o.put("retryMax", r.retryMax);
        o.put("retryDelayMs", r.retryDelayMs);
        o.put("retryMaxDelayMs", r.retryMaxDelayMs);
        return o;
    }

    private static Relay.Route fromJson(JSONObject o) {
        Relay.Route r = new Relay.Route();
        r.id = o.optString("id");
        r.name = o.optString("name");
        r.api = o.optString("api", Relay.API_OPENAI_CHAT);
        r.baseUrl = o.optString("baseUrl");
        r.retryMode = o.optString("retryMode", "");
        r.retryMax = o.optInt("retryMax", -1);
        r.retryDelayMs = o.optInt("retryDelayMs", -1);
        r.retryMaxDelayMs = o.optInt("retryMaxDelayMs", -1);
        JSONArray arr = o.optJSONArray("models");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) r.models.add(new Relay.Model(
                    m.optString("id"), m.optString("name"), m.optBoolean("image", false)));
        }
        return r;
    }

    // ─────────────────────────── 写进容器里的 DSH 设置 ───────────────────────────

    /** 我们写进过 DSH 设置的 provider id（用来认出"哪些条目可以删"）。 */
    private static List<String> ownedIds(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String s = sp(c).getString(K_OWNED, "[]");
            JSONArray arr = new JSONArray(s == null ? "[]" : s);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i);
                if (id != null && !id.isEmpty()) out.add(id);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    private static void setOwnedIds(Context c, List<Relay.Route> routes) {
        try {
            JSONArray arr = new JSONArray();
            for (Relay.Route r : routes) arr.put(r.id);
            sp(c).edit().putString(K_OWNED, arr.toString()).apply();
        } catch (Throwable ignore) { }
    }

    /**
     * 容器里 DSH 的**设置文件**：profile 的补丁层。
     *
     * <p>真机验证纠正过一次：DSH 0.2 的设置在 `~/.dsh/profiles/web/cordis.patch.yml`
     * 里，是一串"id 定向的 config 覆盖"（主题、模型选择、插件设置都写在这）。
     * `~/.dsh/` 下只有 `settings.yaml.imported`（迁移留下的），没有 settings.yaml ——
     * 按 0.1.5 桌面的形态写到那个文件等于白写。
     */
    public static File settingsFile(Context c) {
        return new File(Env.base(c), "rootfs/root/.dsh/profiles/web/cordis.patch.yml");
    }

    /**
     * 把我们管理的 provider 写进容器的 cordis.patch.yml（profile 补丁层）。
     *
     * <p>安全措施（写坏这个文件会连累用户的主题、模型选择等设置）：
     * <ol>
     *   <li>先备份成 {@code cordis.patch.yml.dshmobile.bak}；</li>
     *   <li>写临时文件再原子替换；</li>
     *   <li>写完全文读回，比对**补丁条目 id** 有没有少 —— 少了就回滚并报错。</li>
     * </ol>
     *
     * @return 给人看的结果（成功/失败原因），同时记进运行报告
     */
    public static String push(Context c) {
        List<Relay.Route> ours = load(c);
        File f = settingsFile(c);
        File bak = new File(f.getParentFile(), "cordis.patch.yml.dshmobile.bak");
        try {
            String old = f.exists() ? readText(f) : "";
            List<String> keysBefore = Relay.entryIds(old);
            // 上次写过的 id 也要带上：否则"删掉最后一个提供方"会被当成别人的条目留下
            String merged = Relay.mergeSection(old, ours, ownedIds(c));

            if (f.exists()) {
                try (FileOutputStream os = new FileOutputStream(bak)) {
                    os.write(old.getBytes(StandardCharsets.UTF_8));
                }
            }
            File tmp = new File(f.getParentFile(), "cordis.patch.yml.tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(merged.getBytes(StandardCharsets.UTF_8));
            }
            if (f.exists() && !f.delete()) throw new IllegalStateException("删不掉旧文件");
            if (!tmp.renameTo(f)) throw new IllegalStateException("替换失败（可能是权限）");

            List<String> keysAfter = Relay.entryIds(readText(f));
            for (String k : keysBefore) {
                if (!Relay.PI_AI_ENTRY_ID.equals(k) && !keysAfter.contains(k)) {
                    throw new IllegalStateException("写完后少了补丁条目 " + k);
                }
            }
            setOwnedIds(c, ours);
            Env.report(c, "  [中转站] 已写入 " + ours.size() + " 个自定义提供方到 cordis.patch.yml\n");
            return "已保存 " + ours.size() + " 个提供方。重启 DSH 后它们会出现在模型选择器里。";
        } catch (Throwable t) {
            // 回滚：备份还在就复制回去
            try {
                if (bak.exists()) {
                    byte[] data = new byte[(int) bak.length()];
                    try (InputStream in = new java.io.FileInputStream(bak)) {
                        int n = in.read(data);
                        if (n > 0) {
                            try (FileOutputStream os = new FileOutputStream(f)) { os.write(data, 0, n); }
                        }
                    }
                }
            } catch (Throwable ignore) { }
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Env.report(c, "  [中转站] 写入失败并已回滚：" + msg + "\n");
            return "写入失败（已回滚，DSH 的设置没被改动）：" + msg;
        }
    }

    // ─────────────────────────── 获取可用模型 ───────────────────────────

    /**
     * 问一遍这个中转站有哪些模型（同步方法，调用方要放工作线程）。
     *
     * <p>两条路，与 DSH 自己的行为一致（见 pi-ai README）：
     * <ul>
     *   <li>OpenAI 系：{@code GET {baseURL}/models} + {@code Authorization: Bearer}；</li>
     *   <li>Anthropic 原生：{@code GET /v1/models?limit=1000} + {@code x-api-key}
     *       与 {@code anthropic-version}（列表地址接受带或不带 /v1）。</li>
     * </ul>
     */
    public static List<Relay.Model> discover(Relay.Route r, String apiKey) throws Exception {
        String base = r.baseUrl == null ? "" : r.baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) throw new IllegalStateException("先填 API 地址");
        boolean anthropic = Relay.API_ANTHROPIC.equals(r.api);
        String url = anthropic
                ? (base.endsWith("/v1") ? base + "/models?limit=1000" : base + "/v1/models?limit=1000")
                : base + "/models";
        String key = apiKey == null ? "" : apiKey.trim();

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (anthropic) {
                if (!key.isEmpty()) conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else if (!key.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in, 512 * 1024);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + "："
                        + body.replaceAll("\\s+", " ").trim());
            }
            return parseModelList(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 解析"有哪些模型"的返回体。
     *
     * <p>见过的三种形态：
     * <pre>
     *   {"data":[{"id":"grok-4.7"},{"id":"grok-4.6"}]}           // OpenAI 系
     *   {"data":["grok-4.7","grok-4.6"]}                         // 有的中转站只给字符串
     *   {"models":[{"id":"claude-sonnet-4","max_tokens":64000}]}  // Anthropic 原生
     * </pre>
     * 去重、去空、保持服务端给的顺序。
     */
    public static List<Relay.Model> parseModelList(String body) throws Exception {
        List<Relay.Model> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return out;
        JSONObject o = new JSONObject(body);
        JSONArray arr = o.optJSONArray("data");
        if (arr == null) arr = o.optJSONArray("models");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Object item = arr.opt(i);
            String id = null, name = null;
            if (item instanceof String) {
                id = (String) item;
            } else if (item instanceof JSONObject) {
                JSONObject mo = (JSONObject) item;
                id = mo.optString("id", mo.optString("name", ""));
                name = mo.optString("display_name", mo.optString("name", ""));
            }
            if (id == null || id.trim().isEmpty()) continue;
            id = id.trim();
            if (!seen.add(id)) continue;
            out.add(new Relay.Model(id, name == null || name.trim().isEmpty() ? id : name.trim(), false));
        }
        return out;
    }

    /**
     * 测一次**官方 API**（{@code https://api.deepseek.com}）能不能用。
     *
     * <p>为什么直接测官方、而不是复用 {@link #discover}：官方那家由 DSH 自带的
     * 适配器提供（provider 名 {@code deepseek-official}），它的地址与协议**不在**
     * 我们写的 {@code llm-pi-ai.providers} 里，所以没有 route 对象可传。
     * 这里按官方文档的 {@code GET /models} 打一发，只要 200 就说明
     * "密钥有效 + 网络通 + 地址对" —— 这三件事正是用户"爆红"时要区分的。
     *
     * @return 成功时给人看的一句话（含可用模型数）
     * @throws Exception 失败时抛出**已经翻译成人话**的原因（{@link Relay#explainOfficialError}）
     */
    public static String testOfficial(String apiKey) throws Exception {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) throw new IllegalStateException("还没填官方 API Key");
        HttpURLConnection conn = null;
        int code = 0;
        String body = "";
        try {
            conn = (HttpURLConnection) new URL(Relay.OFFICIAL_BASE + "/models").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            code = conn.getResponseCode();
            body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    256 * 1024);
        } catch (java.io.IOException io) {
            // 连 DNS 都没解析出来也走这里 —— code=0 在 explain 里就是"检查网络"
            throw new IllegalStateException(Relay.explainOfficialError(0,
                    io.getMessage() == null ? "" : io.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException(Relay.explainOfficialError(code, body));
        int n = 0;
        try {
            n = parseModelList(body).size();
        } catch (Throwable ignore) { /* 数不出来不影响"能连上"这个结论 */ }
        return "官方 API 正常（HTTP 200" + (n > 0 ? "，看到 " + n + " 个模型" : "") + "）";
    }

    private static String readAll(InputStream in, int max) {
        if (in == null) return "";
        try (InputStream is = in) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0 && bos.size() < max) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in, 4 * 1024 * 1024);
        } catch (Throwable t) {
            return "";
        }
    }
}
