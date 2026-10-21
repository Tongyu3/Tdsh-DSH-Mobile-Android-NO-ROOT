package com.dshmobile.probe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从"界面没能加载出来"的原文里认出**是哪个插件**把界面卡住了。
 *
 * <h3>为什么需要单独一个纯逻辑类</h3>
 * 这段解析以前写在 {@code MainActivity.suspectBundles()} 里，只有一个正则，
 * 而那个正则**认不出 DSH 0.2 实际打出来的那句话**：
 *
 * <pre>
 *   Failed to load plugins
 *   dsh-pet: import failed (see console for the import error)   ← 状态词是 "import failed"
 * </pre>
 *
 * 旧正则是 {@code ^name:\s*(pending|failed|error)} —— 冒号后面直接要求状态词，
 * 遇到 {@code import failed} 就匹配不上。结果是：明明点出了罪魁祸首，
 * App 却当成"没点名"，退化成"停掉**全部**第三方插件"（真机上用户的 8 个插件
 * 就是这么被一起摘掉的）。所以把它抽出来，用真机日志做回归测试
 * （见 {@code tools/PluginMsgSelfTest.java}）。
 *
 * <h3>只解析、不判断"该不该停"</h3>
 * 这里把原文里点到的包名分成四类，由调用方决定动作：
 * <ul>
 *   <li>{@link Parsed#suspects}：登记在 profile 里、且不是官方/自带的 —— **可以停**；</li>
 *   <li>{@link Parsed#protectedOnes}：点到的恰好是官方/自带插件 —— 停它们没有意义，
 *       停第三方更是白折腾，调用方应当放弃自动自救；</li>
 *   <li>{@link Parsed#unregistered}：点到的名字不在 profile 的 bundle 列表里 ——
 *       它可能写在 {@code cordis.patch.yml} 的 insert 里（App 停不了），
 *       也可能是上一次自动停用摘掉的，都要如实告诉用户；</li>
 *   <li>{@link Parsed#named}：以上三者之和（按原文出现顺序、去重）。</li>
 * </ul>
 *
 * <p>纯字符串逻辑、不碰 Android API，所以能拿日志直接跑。
 */
public final class PluginMsg {

    private PluginMsg() { }

    /** 解析结果。 */
    public static final class Parsed {
        /** 原文里点名到的所有包名（按出现顺序、去重）。 */
        public final List<String> named = new ArrayList<>();
        /** named 中"登记在 profile、且非官方/自带"的 —— 可以停的。 */
        public final List<String> suspects = new ArrayList<>();
        /** named 中恰是官方/自带的。 */
        public final List<String> protectedOnes = new ArrayList<>();
        /** named 中不在 profile 里的（patch 层插件 / 已被摘掉的）。 */
        public final List<String> unregistered = new ArrayList<>();
        /** 文本特征看起来与插件有关（给报告用，不参与"要不要动手"的判断）。 */
        public boolean pluginRelated;

        @Override
        public String toString() {
            return "named=" + named + " suspects=" + suspects
                    + " protected=" + protectedOnes + " unregistered=" + unregistered;
        }
    }

    /**
     * 一行 {@code <包名>: <状态>}。
     *
     * <p>包名那段**不允许空格**，这一点很关键：DSH 的启动失败里还有一句
     * {@code web boot: 1 entry did not activate} —— 它长得也像"名字: 状态"，
     * 但 "web boot" 中间有空格，所以整行不会被当成插件名。
     */
    private static final Pattern ENTRY_LINE = Pattern.compile(
            "(?m)^\\s*(?:[·•*\\-]\\s*)?([@A-Za-z0-9][@A-Za-z0-9._/\\-]*)\\s*[:：]\\s*(.+?)\\s*$");

    /**
     * 后半截像不像"这个插件没起来"。
     *
     * <p>要认的形态（都来自真机日志）：
     * <pre>
     *   dsh-pet: import failed (see console for the import error)
     *   dsh-pet: import failed: Cannot find module 'x'
     *   dsh-speech: pending (waiting for service: settingsScope)
     *   dsh-x: activate failed
     *   dsh-y: failed
     * </pre>
     */
    private static final Pattern FAIL_TAIL = Pattern.compile(
              "^(?:(?:import|activate|load|mount|resolve|dependency)\\s+)?"
            + "(?:failed|error|pending|missing|aborted)\\b"
            + "|^did not activate\\b"
            + "|waiting for service",
            Pattern.CASE_INSENSITIVE);

    /** 一句话看起来是不是与插件有关（报告里记一笔，便于回看）。 */
    private static final Pattern PLUGIN_HINT = Pattern.compile(
            "plugin|activate|pending|waiting for service|failed to load|插件",
            Pattern.CASE_INSENSITIVE);

    /**
     * 解析一份失败原文。
     *
     * @param detail        页面/日志里的原文（null 安全）
     * @param registered    profile 里登记的 bundle 列表（{@link Env#profileBundles}）
     * @param protectedList 官方 + 自带插件名单（{@link Env#safeBundles}）
     */
    public static Parsed parse(String detail, Collection<String> registered,
                               Collection<String> protectedList) {
        Parsed out = new Parsed();
        if (detail == null || detail.isEmpty()) return out;
        String text = detail;
        out.pluginRelated = PLUGIN_HINT.matcher(text).find();

        Set<String> reg = new LinkedHashSet<>(registered == null ? java.util.Collections.emptyList() : registered);
        Set<String> prot = new LinkedHashSet<>(protectedList == null ? java.util.Collections.emptyList() : protectedList);
        Set<String> seen = new LinkedHashSet<>();

        Matcher m = ENTRY_LINE.matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            String tail = m.group(2) == null ? "" : m.group(2).trim();
            if (!FAIL_TAIL.matcher(tail).find()) continue;
            if (!seen.add(name)) continue;                  // 同一句出现多次只算一次
            out.named.add(name);
            if (prot.contains(name)) out.protectedOnes.add(name);
            else if (reg.contains(name)) out.suspects.add(name);
            else out.unregistered.add(name);
        }
        return out;
    }

    /** 只用来拼日志/面板文案：不超过 n 个名字，其余用"…"省略。 */
    public static String join(List<String> names, int n) {
        if (names == null || names.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < n; i++) {
            if (sb.length() > 0) sb.append('、');
            sb.append(names.get(i));
        }
        if (names.size() > n) sb.append("…（共 ").append(names.size()).append(" 个）");
        return sb.toString();
    }

    /**
     * 从 `cordis.patch.yml` 的**原文**里捞出所有插件包名。
     *
     * <h3>为什么要干这个（真机事故）</h3>
     * 用户（小米 25091RP04C）装了个插件之后 App 直接进不去，日志点名
     * `dsh-agnes-multimodal-fixed`。但那个名字**不在 profile 的 bundles 里** ——
     * 它是通过这份 patch 文件被引用的。于是"自动停用第三方插件"算出来的候选是空的，
     * 连续几次都"停用 0 个"后放弃，用户一直卡在 "Failed to load plugins" 那屏。
     *
     * <p>所以"该停谁"必须能**从配置文件里发现**，而不是只信 bundles 那张清单。
     * 这里用正则捞两类形状（DSH 插件就这两种命名）：
     *   · `@scope/name`（例如 `@linxin666/dsh-web-all`）；
     *   · `dsh-xxx`（裸名，例如 `dsh-pet`）。
     *
     * <p>抽成纯逻辑放这里的理由和 {@link #parse} 一样：**能拿真机文本跑回归**。
     * 误判的代价很实在 —— 多停一个用户自己的插件，或者漏停肇事那个让他继续进不去。
     *
     * @param yaml     patch 文件原文（null/空 → 返回空表）
     * @param safeList 不该被停的（官方包 / 自带插件）；null 表示不过滤
     */
    public static List<String> pluginNamesIn(String yaml, Collection<String> safeList) {
        List<String> out = new java.util.ArrayList<>();
        if (yaml == null || yaml.isEmpty()) return out;
        Set<String> safe = safeList == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(safeList);
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = YAML_NAME.matcher(yaml);
        while (m.find()) {
            String n = m.group();
            if (n == null || n.isEmpty()) continue;
            if (safe.contains(n)) continue;
            if (n.startsWith("@deepseek-ai/")) continue;      // 官方包的任何子路径
            if (seen.add(n)) out.add(n);
        }
        return out;
    }

    /**
     * 捞包名用的正则。
     *
     * <p>刻意**宽松**（宁可多捞几个再由调用方按 safeList 过滤）：
     * 这里的产出只用于"把可疑插件挪走"，漏掉一个的代价是用户继续进不去，
     * 比多挪一个严重得多 —— 而多挪的那些在安全模式页能一键恢复。
     */
    private static final java.util.regex.Pattern YAML_NAME = java.util.regex.Pattern.compile(
            "@[A-Za-z0-9_.\\-]+/[A-Za-z0-9_.\\-]+|\\bdsh-[a-z0-9][A-Za-z0-9_.\\-]*");

    /**
     * 从 `cordis.patch.yml` 里**摘掉**那些引用指定插件名的顶层条目。
     *
     * <h3>为什么必须动配置，不能只挪目录（群友笔记点名的缺口）</h3>
     * 群友的《DSH 插件急救笔记》原话：
     * <blockquote>
     *   App 的「自救·手动安全模式」只认 `dsh.profile.bundles` 里列出的 bundle。
     *   如果坏掉的东西是通过 `cordis.patch.yml` 的 `- insert:` 挂进去的，
     *   **安全模式永远清不掉它**，于是每次开机都重复同一个错 ——
     *   这就是「重启多少次都没用」的原因。
     * </blockquote>
     * 而且这类插件常常**根本不在 node_modules 里**（运行时 insert 进来的），
     * 所以"把包目录挪走"对它完全无效。唯一可靠的办法就是**改这份配置**。
     *
     * <h3>怎么摘</h3>
     * 这份文件的顶层是一个**条目数组**，每个条目以 `- id:` 起头。所以按行扫描：
     * 遇到顶层条目起点就结算上一块；哪个块里提到了要停的插件名，整块丢掉。
     * 这样不会误伤别的条目（例如中转站那条 `llm-pi-ai`），也不破坏缩进结构。
     *
     * <p>抽成纯逻辑放这里的理由同 {@link #parse}：**能拿真机文本跑回归**。
     * 摘错一个条目 = 用户的模型路由没了；漏摘 = 他继续进不去。
     *
     * @param yaml  patch 文件原文（null/空 → 原样返回）
     * @param names 要摘掉的插件名（null/空 → 原样返回）
     * @return 摘干净后的 YAML；**没摘掉任何东西时原样返回**（不做无谓的文件改动）
     */
    public static String stripEntries(String yaml, Collection<String> names) {
        if (yaml == null || yaml.isEmpty() || names == null || names.isEmpty()) return yaml;
        String[] lines = yaml.split("\n", -1);
        StringBuilder out = new StringBuilder();
        StringBuilder block = new StringBuilder();
        boolean dropBlock = false;
        boolean changed = false;

        for (String line : lines) {
            boolean isEntryStart = line.startsWith("- ") || line.equals("-");
            if (isEntryStart && block.length() > 0) {
                if (dropBlock) changed = true; else out.append(block);
                block.setLength(0);
                dropBlock = false;
            }
            if (isEntryStart) {
                dropBlock = containsAny(line, names);
            } else if (!dropBlock && containsAny(line, names)) {
                dropBlock = true;      // 名字写在条目内部（例如 name: dsh-xxx）
            }
            block.append(line).append('\n');
        }
        if (block.length() > 0) {
            if (dropBlock) changed = true; else out.append(block);
        }
        return changed ? out.toString() : yaml;
    }

    /** 这一行有没有提到要停的插件名（按词边界，避免部分匹配误伤）。 */
    private static boolean containsAny(String line, Collection<String> names) {
        if (line == null) return false;
        for (String n : names) {
            if (n == null || n.isEmpty()) continue;
            int i = line.indexOf(n);
            while (i >= 0) {
                char before = i == 0 ? ' ' : line.charAt(i - 1);
                int end = i + n.length();
                char after = end >= line.length() ? ' ' : line.charAt(end);
                if (!isNameChar(before) && !isNameChar(after)) return true;
                i = line.indexOf(n, i + 1);
            }
        }
        return false;
    }

    /** 包名允许出现的字符（用于词边界判断）。 */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '/' || c == '@';
    }

    /**
     * 抽出 `cordis.patch.yml` 里每个顶层条目的 `id:`。
     *
     * <h3>为什么光捞包名不够</h3>
     * 这类 patch 条目长这样：
     * <pre>
     * - id: llm-pi-ai            # 我们自己的（中转站），不能动
     *   config: ...
     * - id: agnes-multimodal     # 肇事插件的挂载点，**id 里没有包名**
     *   name: dsh-agnes-multimodal
     * </pre>
     * 只按包名过滤的话，第二类条目在有的写法下匹配不到（名字写在别处、或只用 id）。
     * 所以两边都要拿：条目 id 与包名一起当作"可疑插件名"送去停用。
     *
     * <p>调用方务必用安全名单把官方/自带/我们自己的条目排除掉
     * （例如 `llm-pi-ai` 是小程序写的中转站路由，摘了用户的模型配置就没了）。
     */
    public static List<String> entryIdsIn(String yaml) {
        List<String> out = new java.util.ArrayList<>();
        if (yaml == null || yaml.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String line : yaml.split("\n", -1)) {
            Matcher m = ENTRY_ID.matcher(line);
            if (m.find()) {
                String id = m.group(1);
                if (id != null && !id.isEmpty() && seen.add(id)) out.add(id);
            }
        }
        return out;
    }

    /** 顶层条目的 `- id: xxx`（只看以 `- ` 起头的行，避免匹配到嵌套的 id）。 */
    private static final java.util.regex.Pattern ENTRY_ID =
            java.util.regex.Pattern.compile("^-\\s+id:\\s*[\"']?([A-Za-z0-9_@./\\-]+)");
}
