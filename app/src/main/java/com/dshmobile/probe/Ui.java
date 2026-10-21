package com.dshmobile.probe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 本机设置页共用的样式。
 *
 * <h3>为什么要统一</h3>
 * 之前每个页面各写各的：手机控制页塞了十几行解释性小字、白名单页整屏列表、
 * 插件市场又是另一套 —— 用户的原话是"过于杂乱"。这里把样式收在一处，
 * 照着 DSH 自己的插件页（DSH-IM / Agent 预设）学：
 *
 * <pre>
 *   深色底 + 圆角卡片 + 一行标题 + 一行说明（需要才写）
 *   解释性长文一律不放，要说明就一句
 * </pre>
 *
 * <p>刻意**不引入 appcompat/Material**：这个 App 现在只依赖 androidx.core，
 * 为了几个圆角卡片把 Material 整套拖进来不值（APK 会大一大截）。
 * 圆角直接用 {@link GradientDrawable} 画。
 */
public final class Ui {

    /*
     * ⚠️ 这七个颜色**故意不是 final**。
     *
     * 用户要求"跟随 DSH"的主题（不是跟随系统）—— DSH 切浅色，App 的原生页也要跟着变浅。
     * 这七个字段被 16 个页面、约 280 处引用，所以**一处改、全 App 跟着变**：
     * 每个 Activity 在 onCreate 里调一次 Ui.applyTheme(this)，之后所有 Ui.BG / Ui.CARD…
     * 读到的就是当前主题的颜色，各页面一行都不用动。
     *
     * （原始值是深色主题，下面按 DSH 的深色取值。）
     */
    /** 页面底色。 */
    public static int BG = 0xFF101014;
    /** 卡片底色。 */
    public static int CARD = 0xFF1B1B20;
    /** 卡片描边。 */
    public static int STROKE = 0xFF2A2A31;
    /** 主文字。 */
    public static int TEXT = 0xFFF2F3F5;
    /** 次要文字。 */
    public static int SUB = 0xFF9BA1A8;
    /** 强调色。 */
    public static int ACCENT = 0xFF4C8DFF;
    /** 危险/警告。 */
    public static int WARN = 0xFFFF8A65;
    /**
     * 输入框/代码块底色。
     *
     * <p>⚠️ 这个必须跟着主题走。第一版把输入框底色直接写死成 0xFF141416
     * （深色主题下的输入框底色），切到浅色主题后**输入框还是黑的**，
     * 而里面的文字跟着主题变成了深色 —— 结果就是"黑底黑字，一个字都看不见"。
     * 用户截图报的就是这个。此后输入框一律走 {@link #inputBg}/{@link #styleInput}。
     */
    public static int FIELD = 0xFF141416;
    /**
     * **页面底色** —— 和 DSH 网页自身的背景对齐，用来铺「状态栏/安全区那一条」。
     *
     * <p>为什么不能直接用 {@link #BG}：BG 是 #F5F6F8（亮度 246），而 DSH 页面是纯白
     * （亮度 254）。全屏后顶部那条 padding 正是用 BG 铺的，于是屏幕上沿出现一条
     * 肉眼可见的浅灰带，和下面的白页面接不上（2026-10-17 用户截图）。
     * 目标是把亮度差压到 5 以内，所以这里单独给一个"页面档"。
     */
    public static int PAGE = 0xFF17181B;

    /** DSH 当前是不是浅色主题（由 WebUI 通过 AppBridge.setTheme 上报）。 */
    static final String KEY_LIGHT = "dsh_theme_light";

    private static boolean light = false;

    public static boolean isLight() {
        return light;
    }

    /** 读 DSH 上次上报的主题（没上报过就按深色，和 DSH 默认一致）。 */
    public static boolean dshIsLight(Context c) {
        try {
            return c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_LIGHT, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** WebUI 上报主题时调用（见 patch.js 的 reportTheme）。 */
    public static void setDshLight(Context c, boolean isLight) {
        try {
            c.getSharedPreferences(DshService.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_LIGHT, isLight).apply();
        } catch (Throwable ignore) { }
    }

    /**
     * 按给定主题改写配色。**每个 Activity 的 onCreate 里第一件事就调它**
     * （在 super.onCreate 之后、建任何 View 之前，否则会拿到上一页留下的颜色）。
     *
     * <p>浅色取值是照着 DSH 浅色主题挑的：底 #F5F6F8、卡片纯白、描边 #E3E6EC、
     * 主文字近黑、次要文字中性灰、强调色用比深色档更深的蓝（浅底上太亮的蓝会发飘）。
     */
    public static void applyTheme(Context c) {
        applyTheme(dshIsLight(c));
    }

    public static void applyTheme(boolean isLight) {
        light = isLight;
        if (isLight) {
            BG     = 0xFFF5F6F8;
            CARD   = 0xFFFFFFFF;
            STROKE = 0xFFE3E6EC;
            TEXT   = 0xFF17181B;
            SUB    = 0xFF6B7280;
            ACCENT = 0xFF2F6FEB;
            WARN   = 0xFFC2410C;
            FIELD  = 0xFFF1F3F6;
            PAGE   = 0xFFFFFFFF;
        } else {
            BG     = 0xFF101014;
            CARD   = 0xFF1B1B20;
            STROKE = 0xFF2A2A31;
            TEXT   = 0xFFF2F3F5;
            SUB    = 0xFF9BA1A8;
            ACCENT = 0xFF4C8DFF;
            WARN   = 0xFFFF8A65;
            FIELD  = 0xFF141416;
            PAGE   = 0xFF17181B;
        }
    }

    /**
     * 状态栏/导航栏跟着主题走（浅色底配深色图标）。
     *
     * <p>参数故意收 {@link Context} 而不是 Activity —— 注入脚本给**所有** onCreate
     * 都插了这一行（含 Service / Application），收 Activity 会编译不过；
     * 非 Activity 直接返回即可。
     */
    public static void applySystemBars(Context c) {
        if (!(c instanceof android.app.Activity)) return;
        try {
            android.app.Activity a = (android.app.Activity) c;
            android.view.Window w = a.getWindow();
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.view.View dv = w.getDecorView();
                int flags = dv.getSystemUiVisibility();
                if (light) {
                    flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                dv.setSystemUiVisibility(flags);
            }
            /*
             * 状态栏涂成页面底色。
             *
             * 上一版试过“透明 + 内容画到状态栏底下 + 补内边距”，
             * 但没同时把窗口底色换掉，设置页顶部就露出一条**黑带**（用户截图）。
             * 改回最稳的做法：状态栏直接涂 BG，和页面同色，不做边界处理。
             */
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
        } catch (Throwable ignore) { }
    }

    private Ui() { }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 圆角背景。 */
    public static GradientDrawable round(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(dp(c, 1f), stroke);
        return d;
    }

    /**
     * 按下的水波纹颜色（浅色主题用淡蓝，深色主题用淡白）。
     *
     * <p>为什么不写死一个灰：深底色上的灰根本看不见，而白底上的白也看不见。
     */
    private static int pressColor() {
        return light ? 0x1F2F6FEB : 0x26FFFFFF;
    }

    /**
     * 给一个**已经画好背景**的 View 加"按下水波纹"。
     *
     * <h3>为什么原生设置页需要它（2026-10-21 用户报"点按钮动画卡顿"）</h3>
     * 这个工程为了不拖进 Material 整套库，所有按钮都是 {@code TextView + GradientDrawable}，
     * 结果是**按下去毫无反馈**：手指落下到页面切换之间什么也不发生，
     * 主观上就是"点了没反应 / 卡了一下"。而人的感受里，"有反馈"本身就等于"不卡"。
     *
     * <p>{@link android.graphics.drawable.RippleDrawable} 是系统自带的，
     * 零依赖、只在触摸点周围画一层半透明色，不触发重新布局，
     * 所以它加的是手感而不是开销。
     *
     * @param v       目标 View（背景为 null 时补一个透明底，否则水波纹没有载体）
     * @param radiusDp 圆角半径，**必须和背景的圆角一致**，否则波纹会溢出到方块四角
     */
    public static void press(View v, float radiusDp) {
        if (v == null) return;
        try {
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg == null) bg = new android.graphics.drawable.ColorDrawable(0x00000000);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(dp(v.getContext(), radiusDp));
            v.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(pressColor()), bg, mask));
        } catch (Throwable ignore) {
            // 水波纹加不上不影响功能：保留原背景，按钮照样能点
        }
    }

    /** 页面骨架：深色底 + 可滚动 + 左右留白。往返回的容器里 addView 就行。 */
    public static LinearLayout page(android.app.Activity a, String title, View.OnClickListener back) {
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 16);
        root.setPadding(p, p, p, dp(a, 28));
        scroll.addView(root);

        if (title != null) {
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, dp(a, 4), 0, dp(a, 16));

            if (back != null) {
                TextView b = new TextView(a);
                b.setText("‹ 返回");
                b.setTextSize(14f);
                b.setTextColor(TEXT);
                b.setPadding(dp(a, 12), dp(a, 7), dp(a, 12), dp(a, 7));
                b.setBackground(round(0x00000000, STROKE, 10, a));
                b.setOnClickListener(back);
                press(b, 10);
                head.addView(b);
                TextView spacer = new TextView(a);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                head.addView(spacer);
            }

            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(19f);
            t.setTextColor(TEXT);
            if (back == null) head.addView(t);
            else head.addView(t);   // 标题靠右也能看；保持简单
            root.addView(head);
        }

        a.setContentView(scroll);
        return root;
    }

    /** 一张卡片：可选标题 + 可选一行说明，返回卡片容器（line=vertical）。 */
    public static LinearLayout card(Context c, String title, String desc) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, STROKE, 16, c));
        int p = dp(c, 16);
        box.setPadding(p, dp(c, 14), p, dp(c, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        box.setLayoutParams(lp);

        if (title != null) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(16f);
            t.setTextColor(TEXT);
            box.addView(t);
        }
        if (desc != null) {
            TextView d = new TextView(c);
            d.setText(desc);
            d.setTextSize(13f);
            d.setTextColor(SUB);
            d.setPadding(0, dp(c, 6), 0, 0);
            d.setLineSpacing(dp(c, 3), 1f);
            box.addView(d);
        }
        return box;
    }

    /** 卡片里的一行：左标题（可带一行说明），右箭头；可点。 */
    public static View row(Context c, String title, String desc, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 14);
        r.setPadding(dp(c, 4), p, dp(c, 4), p);
        /*
         * ⚠️ 必须显式 MATCH_PARENT。
         * 默认是 WRAP_CONTENT，于是在卡片里这一行只有"标题+箭头"那么宽 ——
         * 点卡片空白处没反应，看起来就像"点了没用"（实测踩到过）。
         */
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(TEXT);
        col.addView(t);
        if (desc != null) {
            TextView d = new TextView(c);
            d.setText(desc);
            d.setTextSize(12f);
            d.setTextColor(SUB);
            d.setPadding(0, dp(c, 3), 0, 0);
            col.addView(d);
        }
        r.addView(col);

        TextView arrow = new TextView(c);
        arrow.setText("›");
        arrow.setTextSize(18f);
        arrow.setTextColor(SUB);
        arrow.setPadding(dp(c, 10), 0, dp(c, 2), 0);
        r.addView(arrow);

        if (click != null) {
            r.setOnClickListener(click);
            // 按压反馈：透明底 + 水波纹（原来只是一个全透明的空 drawable，点了没任何变化）
            r.setBackground(round(0x00000000, 0, 10, c));
            press(r, 10);
        }
        return r;
    }

    /** 主按钮（强调色描边，不填充，跟 DSH 的次级按钮一致）。 */
    public static TextView button(Context c, String text, boolean primary, View.OnClickListener click) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(14f);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        b.setBackground(round(primary ? 0xFF2A4B8D : 0x00000000, primary ? 0 : STROKE, 10, c));
        b.setOnClickListener(click);
        press(b, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /** 一行小字状态（带颜色）。 */
    public static TextView status(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(color);
        t.setLineSpacing(dp(c, 3), 1f);
        t.setPadding(0, dp(c, 6), 0, dp(c, 2));
        // 同上：不占满宽度的话长句会不换行/被截断
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 是否打开状态用的小圆点 + 文案。 */
    public static String onOff(boolean on) {
        return on ? "● 已开启" : "○ 已关闭";
    }

    /**
     * 输入框 / 代码块底色（圆角 + 描边），随主题走。
     *
     * <p>已经建好的输入框用 {@link #styleInput}，新写的可以直接拿它当 background。
     */
    public static android.graphics.drawable.GradientDrawable inputBg(Context c) {
        return round(FIELD, STROKE, 8, c);
    }

    /**
     * 把一个输入框统一成当前主题：**底色 + 文字色 + 提示色 + 光标 + 选中高亮**。
     *
     * <p>为什么四项都要设：这个 App 刻意不引 appcompat/Material，EditText 的默认文字色
     * 来自系统主题（深色），只改底不改字就会变成"浅底白字"，同样看不见。
     * 用户在「中转站」页截图报的"黑底看不见字"就是漏了这一组。
     */
    public static void styleInput(android.widget.EditText et) {
        if (et == null) return;
        Context c = et.getContext();
        et.setBackground(inputBg(c));
        et.setTextColor(TEXT);
        et.setHintTextColor(SUB);
        try {
            et.setHighlightColor(0x552F6FEB);
        } catch (Throwable ignore) { }
    }

    /**
     * 分组标题 —— 给「手机权限」这类卡片很多（十几张）的页面分块用。
     *
     * <p>刻意做得**比卡片标题轻**（13sp、强调色、无底色）：它只负责说"下面换一类了"，
     * 做成大标题的话整页会变成一堆标题互相打架，反而更乱。
     */
    public static TextView section(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(ACCENT);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(c, 4), dp(c, 12), 0, dp(c, 8));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }
}
