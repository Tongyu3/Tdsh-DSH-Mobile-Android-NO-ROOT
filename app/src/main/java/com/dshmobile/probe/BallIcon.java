package com.dshmobile.probe;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮球图标的候选与选择。
 *
 * <h3>图从哪来 —— 必须用"原始图资源"（踩过三次才定下来）</h3>
 * `ic_launcher_uuNN` 这些名字在 API 26+ 会被系统解析成**自适应图标**
 * （`mipmap-anydpi-v26` 里的 XML：一个 background 色 + 一个 foreground 图层），
 * 结果怎么弄都是**接近纯黑**：`BitmapFactory.decodeResource` 直接返回 null，
 * `getDrawable()` 栅格化出来中心是 `#FF000000`。
 *
 * <p>所以这里改用**真实位图**，且解码那一步统一走
 * {@link BallView#loadIcon} 的 `openRawResource`：
 * <pre>
 *   · 表情包贴纸  drawable-nodpi/ball_s01..s09.webp  （无损 WebP + alpha）
 *   · uu 投稿图   drawable-nodpi/ic_launcher_uu01..30.jpg
 * </pre>
 * 没有 anydpi XML 覆盖，解出来就是原图。
 *
 * <h3>分组</h3>
 * <ul>
 *   <li>{@link #G_STICKER}：表情包贴纸（9 张，不规则轮廓，**不套圆形裁剪**）；</li>
 *   <li>{@link #G_UU}：群里 uu 们投稿的照片（30 张，保留原样，不动不删）。</li>
 * </ul>
 *
 * <h3>为什么删掉了原来的 4 个纯色球</h3>
 * 用户要求「uu 们添加的图标可以不改不删，其余删除」。纯色球是早期自己画的兜底项，
 * 现在有贴纸可用，不再需要；保留它们只会让图标墙多 4 个没人选的东西。
 */
public final class BallIcon {

    private static final String KEY = "overlay_ball_icon";

    public static final String G_STICKER = "表情包";
    public static final String G_UU = "uu 投稿的图";

    /** 一个可选图标。 */
    public static final class Option {
        public final String id;
        public final String label;
        public final String drawable;   // 资源名（getIdentifier 用，不带扩展名）
        public final String group;

        Option(String id, String label, String drawable, String group) {
            this.id = id;
            this.label = label;
            this.drawable = drawable;
            this.group = group;
        }

        /**
         * 是不是"贴纸"（不规则轮廓、透明底）。
         *
         * <p>为什么要区分：老图标是方形照片，必须裁成圆形才像个球；
         * 而贴纸本身就是剪好的轮廓，再套一层圆裁剪会把耳朵、尾巴、头发全切掉。
         */
        public boolean isSticker() {
            return G_STICKER.equals(group);
        }
    }

    /** 默认图标：开心举手（s07）—— 表情最鲜明，一眼能认出来。 */
    public static final String DEFAULT_ID = "s07";

    /**
     * uu 投稿图的资源名前缀。
     *
     * <p>直接用**已有的投稿图资源**（`ic_launcher_uuNN`）—— 解码那一步改用
     * `openRawResource` 读原始字节（见 {@link BallView#loadIcon}），
     * 所以这个"会被系统当成自适应图标"的名字不再有影响。
     *
     * <p>为什么不另存一份 `ic_buuNN`：那会让 APK 多约 400KB 重复图片，
     * 还会把源码树撑过 GitHub 单批上传的上限（实测第 3 批从 90 涨到 124）。
     */
    private static final String RAW_UU = "ic_launcher_uu";

    /** 贴纸资源名前缀（`ball_s01` … `ball_s09`）。 */
    private static final String RAW_STICKER = "ball_s";

    /** 贴纸清单：资源编号 → 显示名。顺序就是图标墙里的顺序。 */
    private static final String[][] STICKERS = {
            {"01", "睡觉"},
            {"02", "大哭"},
            {"03", "委屈"},
            {"04", "惊讶"},
            {"05", "抱鲸鱼"},
            {"06", "无语"},
            {"07", "开心举手"},
            {"08", "害羞"},
            {"09", "叹气"},
    };

    private BallIcon() { }

    /** 全部候选：先表情包贴纸，再 uu 投稿图。 */
    public static List<Option> all() {
        List<Option> out = new ArrayList<>();
        for (String[] s : STICKERS) {
            out.add(new Option("s" + s[0], s[1], RAW_STICKER + s[0], G_STICKER));
        }
        for (int i = 1; i <= 30; i++) {
            String n = String.format(java.util.Locale.ROOT, "%02d", i);
            out.add(new Option("uu" + n, "投稿 " + n, RAW_UU + n, G_UU));
        }
        return out;
    }

    /** 用户选中的那个（读不到就用默认）。 */
    public static Option current(Context c) {
        String id = prefs(c).getString(KEY, DEFAULT_ID);
        for (Option o : all()) {
            if (o.id.equals(id)) return o;
        }
        return all().get(6);        // 默认 s07 开心举手（下标 6）
    }

    public static void set(Context c, String id) {
        prefs(c).edit().putString(KEY, id == null ? DEFAULT_ID : id).apply();
    }

    /**
     * 取图标资源 id；取不到返回 0。
     *
     * <p>两组图**都在 `drawable-nodpi/`**（贴纸是我们生成的 WebP，投稿图的原始 jpg 也在那儿），
     * 所以查找只有一条路：`drawable`。刻意不再按密度逐个试 `mipmap-*` ——
     * 那正是之前反复取到自适应图标的来源，留两条路只会让"到底取到了哪张"变成猜谜。
     *
     * <p>投稿图的原始 jpg 由 `tools/make-ball-uu-icons.py` 从各 dpi 目录聚合到
     * `drawable-nodpi/`（**不新增文件**，是移动同内容的那一份，避免 APK 里两份重复）。
     */
    public static int resId(Context c, Option o) {
        if (o == null) return 0;
        try {
            android.content.res.Resources r = c.getResources();
            int id = r.getIdentifier(o.drawable, "drawable", c.getPackageName());
            if (id == 0) id = r.getIdentifier(o.drawable, "mipmap", c.getPackageName());
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE);
    }
}
