package com.dshmobile.probe;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「悬浮球图标」选择页：**纯色球** 4 个 + **uu 投稿图** 30 张，点一下立刻生效。
 *
 * <p>和「桌面图标」那页长得像，但轻得多：换悬浮球图标**不需要重启 App**
 * （那页要重启，因为走的是 activity-alias 组件切换）。
 *
 * <p>缩略图直接用 {@code setImageResource(resId)}：这里只是给人挑图的小预览，
 * 就算某个资源解析成自适应图标也看得出是哪张；真正贴到球上的是
 * {@link BallView#loadIcon} 那条路（按具体密度目录取真位图）。
 */
public class BallIconActivity extends Activity {

    private static final int PER_ROW = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("悬浮球图标");
        title.setTextSize(20f);
        title.setTextColor(Ui.TEXT);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setTextSize(12f);
        tip.setTextColor(Ui.SUB);
        tip.setPadding(0, pad / 2, 0, pad);
        tip.setText("点一张就换，**立刻生效**（不用重启 App）。\n"
                + "「uu 投稿的图」是群里大家发来的图，和「桌面图标」用的是同一批。\n"
                + "想加自己的图：把图片发给开发者即可，加进去之后这里会多一张。");
        root.addView(tip);

        final BallIcon.Option cur = BallIcon.current(this);
        String lastGroup = null;
        LinearLayout row = null;
        int inRow = 0;
        for (BallIcon.Option o : BallIcon.all()) {
            if (!o.group.equals(lastGroup)) {
                lastGroup = o.group;
                row = null;
                inRow = 0;
                TextView h = new TextView(this);
                h.setText(o.group);
                h.setTextSize(13f);
                h.setTextColor(Ui.SUB);
                h.setPadding(0, pad, 0, pad / 2);
                root.addView(h);
            }
            if (row == null) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(row);
                inRow = 0;
            }
            row.addView(buildCell(o, cur));
            if (++inRow % PER_ROW == 0) row = null;
        }

        setContentView(scroll);
    }

    private View buildCell(final BallIcon.Option o, BallIcon.Option cur) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);
        int p = Ui.dp(this, 4);
        cell.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));

        boolean selected = o.id.equals(cur.id);
        cell.setBackground(Ui.round(selected ? 0xFF1E3A5F : 0x00000000,
                selected ? Ui.ACCENT : 0x00000000, 10, this));

        ImageView iv = new ImageView(this);
        /*
         * 预览也走 BallView.loadIcon 那条解码路径（decodeResource），
         * 不用 setImageResource —— 后者会把自适应图标之类的资源也画进来，
         * 于是"预览里能看到、贴到球上是黑的"这种不一致又会出现。
         * 解不出来就留空白格，不画错误的东西。
         */
        android.graphics.Bitmap b = BallView.loadIcon(this, o);
        if (b != null) iv.setImageBitmap(b);
        int size = Ui.dp(this, 52);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        cell.addView(iv);

        TextView label = new TextView(this);
        label.setText(o.label);
        label.setTextSize(11f);
        label.setTextColor(selected ? Ui.TEXT : Ui.SUB);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, p, 0, 0);
        cell.addView(label);

        cell.setOnClickListener(v -> {
            BallIcon.set(this, o.id);
            Toast.makeText(this, "已换成「" + o.label + "」", Toast.LENGTH_SHORT).show();
            BallWindow.refreshBallIcon(this);      // 球开着的话立刻换图
            recreate();
        });
        return cell;
    }
}
