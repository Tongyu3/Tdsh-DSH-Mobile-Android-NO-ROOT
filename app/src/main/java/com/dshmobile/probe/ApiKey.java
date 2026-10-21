package com.dshmobile.probe;

/**
 * API Key 的清洗与体检 —— **纯逻辑，不引用任何 Android 类**。
 *
 * <h3>为什么非要有这个类（用户报的是"复制粘贴进去还说 Key 不对"）</h3>
 * 手机上的 Key 几乎都是**复制粘贴**来的，而各家的复制通道会往前后塞看不见的东西：
 * <ul>
 *   <li>微信 / QQ 复制会在末尾带一个 {@code \u200B}（零宽空格）或 {@code \uFEFF}（BOM）；</li>
 *   <li>有的输入法把半角连字符 {@code -} 变成全角 {@code －}，把字母变成全角；</li>
 *   <li>从网页复制常常带上一对引号或一个不换行空格 {@code \u00A0}；</li>
 *   <li>从文档复制可能带 ideographic space {@code \u3000}（中文全角空格）。</li>
 * </ul>
 * {@link String#trim()} **只裁掉 <= 0x20 的字符**，上面这些一个都裁不掉。
 * 于是 Key 看起来"填进去了"，但发给服务端的是 {@code sk-abc\u200B} ——
 * 服务端只会回"无效的 API Key"，用户完全看不出问题在哪，最后只能重装 App 碰运气。
 *
 * <p>所以这里做三件事，缺一不可：
 * <ol>
 *   <li>{@link #sanitize}：把全角转半角、把不可见字符去掉、剥引号；
 *       如果整串里能认出一个 {@code sk-} 开头的片段，就**只取那一段**
 *       （用户复制的常常是"你的 Key：sk-xxx"这种整句话）；</li>
 *   <li>{@link #plausible}：判断"这看起来像不像一个 DeepSeek Key"，
 *       用来在设置页给出**明确**的提示，而不是让服务端报错；</li>
 *   <li>{@link #describe}：给界面/日志一行诊断（长度、掩码、有没被清理过），
 *       出问题时不用再让用户重装一遍来试。</li>
 * </ol>
 *
 * <p>规矩和本工程其它纯逻辑类一致（UrlPick / PluginMsg / ProcLogic …）：
 * **不许 import android**，这样就能在 PC 上用 {@code tools/ApiKeySelfTest.java} 离线回归。
 */
public final class ApiKey {

    private ApiKey() { }

    /** DeepSeek 的 Key 形态：{@code sk-} + 一长串字母数字。 */
    private static final String SHAPE = "sk-[A-Za-z0-9_-]{8,}";

    /**
     * 把用户填/粘贴进来的东西洗成一个**能直接发给服务端**的 Key。
     *
     * <p>顺序有意义，且这一步踩过：**必须先去掉不可见字符，再去认 {@code sk-} 片段**。
     * 反过来的话，一个夹在 Key 中间的软连字符 {@code \u00AD}
     * （从 PDF/网页复制时很常见）会让"允许字符"的扫描在那里断掉，
     * 于是 Key 被截成半截 —— 这比不洗更糟，因为它看起来"填进去了"。
     *
     * @param raw 原始输入（可为 null）
     * @return 清洗后的 Key；输入为空/全是噪声时返回空串（**永不返回 null**）
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        // ① 全角折半角：否则后面既认不出 sk-，也认不出全角的连字符
        String s = toHalfWidth(raw);
        // ② 剥掉成对包裹的引号（"…" '…' “…” `…`）
        s = stripQuotes(s);
        // ③ 去掉所有不可见 / 空白字符
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isInvisible(c)) continue;
            sb.append(c);
        }
        s = sb.toString();
        // ④ 整句话里带 Key（"你的Key：sk-xxx（请勿泄露）"）→ 只取 sk- 那一段
        int at = s.indexOf("sk-");
        if (at >= 0) {
            int end = at + 3;
            while (end < s.length() && isKeyChar(s.charAt(end))) end++;
            if (end > at + 3) s = s.substring(at, end);
        }
        return s;
    }

    /** Key 里允许出现的字符：字母、数字、{@code -}、{@code _}。 */
    private static boolean isKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    /**
     * 这个字符该不该丢。
     *
     * <p>包含：所有空白（含 {@code \u00A0} 不换行空格、{@code \u3000} 全角空格）、
     * BOM、零宽字符（{@code \u200B..\u200D}、{@code \u2060}）、软连字符 {@code \u00AD}。
     */
    private static boolean isInvisible(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return true;
        if (c < 0x20) return true;                       // 控制字符（含换行/制表）
        return c == '\uFEFF' || c == '\u200B' || c == '\u200C' || c == '\u200D'
                || c == '\u2060' || c == '\u00AD' || c == '\u180E';
    }

    /** 全角 ASCII（U+FF01..U+FF5E）折成半角，全角空格折成普通空格。 */
    static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3000') sb.append(' ');
            else if (c >= '\uFF01' && c <= '\uFF5E') sb.append((char) (c - 0xFEE0));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 剥掉成对包裹的引号（"…" '…' “…” ‘…’ `…`）。 */
    static String stripQuotes(String s) {
        String t = s.trim();
        String pairs = "\"'`\u201C\u2018";
        String pairs2 = "\"'`\u201D\u2019";
        if (t.length() >= 2) {
            char a = t.charAt(0), b = t.charAt(t.length() - 1);
            if (pairs.indexOf(a) >= 0 && pairs2.indexOf(b) >= 0) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    /**
     * 看起来像不像一个真 Key。
     *
     * <p>只做**形态**判断，不联网 —— 所以它能抓出"复制坏了"，抓不出"Key 过期了"。
     * 这是刻意的：设置页要能秒回，不能因为没网就什么都不说。
     */
    public static boolean plausible(String key) {
        if (key == null) return false;
        return key.trim().matches(SHAPE);
    }

    /** 界面上显示的掩码（{@code sk-abcd****wxyz}）。 */
    public static String mask(String key) {
        String k = sanitize(key);
        if (k.isEmpty()) return "（未设置）";
        if (k.length() <= 10) return k.substring(0, 2) + "****";
        return k.substring(0, 6) + "****" + k.substring(k.length() - 4);
    }

    /**
     * 一行体检结论（设置页直接用）。
     *
     * @param raw 用户填的原文（可能带噪声）
     * @return 给人看的一句话；**不含 Key 本身**，可以安全写进日志
     */
    public static String describe(String raw) {
        String clean = sanitize(raw);
        if (clean.isEmpty()) return "没有填 Key";
        StringBuilder sb = new StringBuilder();
        sb.append("长度 ").append(clean.length()).append("，").append(mask(clean));
        boolean dirty = raw != null && !clean.equals(raw);
        if (dirty) sb.append("；已自动清掉多余的空白/不可见字符");
        if (!plausible(clean)) {
            sb.append("；⚠ 形态不对（应当以 sk- 开头、后面只有字母数字）");
        }
        return sb.toString();
    }
}
