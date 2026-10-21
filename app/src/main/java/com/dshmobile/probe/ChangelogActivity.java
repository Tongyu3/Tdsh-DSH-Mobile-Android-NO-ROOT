package com.dshmobile.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 「更新日志」页：把 {@link Changelog} 里的记录摊成一屏。
 *
 * <p>为什么值得有一页：用户问"这版改了什么"时，唯一现成的答案是让开发者去翻仓库 ——
 * 而 App 里本来就有全部信息（{@link Changelog}）。装完新版本第一次进界面弹的那次
 * 提示只有当前版本，想往回看就得有这一页。
 */
public class ChangelogActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        LinearLayout root = Ui.page(this, "更新日志", v -> finish());

        Changelog.Entry cur = Changelog.current();
        TextView sub = Ui.status(this,
                (cur == null ? "" : "当前版本 v" + cur.version + "（第 " + cur.code + " 版）· ")
                + "同签名覆盖安装，不清容器、API Key 与白名单。",
                Ui.SUB);
        root.addView(sub);

        List<Changelog.Entry> all = Changelog.all();
        for (int i = 0; i < all.size(); i++) {
            Changelog.Entry e = all.get(i);
            LinearLayout card = Ui.card(this, "v" + e.version + (i == 0 ? "（当前）" : ""), null);
            for (String item : e.items) {
                card.addView(bullet(item));
            }
            root.addView(card);
        }

        TextView tail = Ui.status(this,
                "\n完整的、带实测记录的技术日志在仓库的 CHANGELOG.md 里。", Ui.SUB);
        root.addView(tail);
    }

    /** 一条更新项：圆点 + 文字（自动换行）。 */
    private View bullet(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView dot = new TextView(this);
        dot.setText("·");
        dot.setTextSize(13f);
        dot.setTextColor(Ui.SUB);
        dot.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 8), 0);
        row.addView(dot);

        TextView t = new TextView(this);
        t.setText(boldify(text));
        t.setTextSize(13f);
        t.setTextColor(Ui.TEXT);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        return row;
    }

    /**
     * 把条目里的 {@code **加粗**} 变成真正的粗体，并把星号本身去掉。
     *
     * <h3>为什么需要这一步</h3>
     * 更新日志的维护约定是「动词：**一句结论** —— 人话解释」，结论加粗是为了让用户
     * 扫一眼就抓住这版改了什么。但这里是 {@code setText(String)}，不做任何解析，
     * 于是屏幕上直接显示成 {@code 新增：**悬浮球换成 9 张表情包贴纸**} —— 星号外露，
     * 像没做完的半成品。2026-10-17 用户看到的就是这个。
     *
     * <p>为什么自己解析而不引 markdown 库：整个 App 刻意不引额外依赖（连 appcompat 都没有），
     * 而这里只有一种标记、几行就够。
     *
     * <p>注意 Spannable **不能删除字符**，所以必须先拼出一个去掉了星号的新串、
     * 同时记下每段要在哪个偏移加粗，最后再套 StyleSpan。
     */
    private CharSequence boldify(String text) {
        if (text == null) return "";
        if (text.indexOf("**") < 0) return text;          // 没有标记就原样返回，省一次拷贝
        StringBuilder out = new StringBuilder(text.length());
        java.util.List<int[]> bolds = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("**", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            int close = text.indexOf("**", open + 2);
            if (close < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            int start = out.length();
            out.append(text, open + 2, close);
            bolds.add(new int[]{start, out.length()});
            i = close + 2;
        }
        android.text.SpannableString ss = new android.text.SpannableString(out.toString());
        for (int k = 0; k < bolds.size(); k++) {
            int[] r = bolds.get(k);
            ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    r[0], r[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }
}
