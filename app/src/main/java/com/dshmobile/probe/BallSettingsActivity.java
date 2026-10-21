package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮球设置页。
 *
 * <h3>为什么单独开一页</h3>
 * 以前悬浮球的所有东西（开关、换图标、自动出现、**操作教程**）都塞在
 * 「手机权限」页弹出的一个 AlertDialog 里，气球那点文字要滚好几屏。
 * 现在手机权限页只留一行入口，点进来才是完整设置。
 *
 * <h3>为什么教程必须搬进来</h3>
 * 用户明确要求「操作教程放进设置里面」+「气泡不要显示（教程）」。
 * 教程原来还**重复了一份**在气泡里（{@code BallWindow.statusLine()} 里那句
 * "连点两次回主界面 · 点一次收起"）—— 那份已经删掉了，气泡现在只显示当前步骤。
 *
 * <h3>尺寸为什么做成档位而不是滑杆</h3>
 * 这个 App 刻意不引入 Material / appcompat（见 {@link Ui} 的类注释），
 * 原生 SeekBar 在深色底上样式很难看；而悬浮球/气泡的大小本来也不需要无级调节，
 * 5 档足够，点一下就有明确反馈。
 */
public class BallSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        LinearLayout root = Ui.page(this, "悬浮球设置", v -> finish());

        root.addView(Ui.status(this, "这些只影响那个浮在别的应用上面的小球。", Ui.SUB));

        // ── 图标 ──
        LinearLayout cIcon = Ui.card(this, "图标", null);
        cIcon.addView(Ui.row(this, "换个图标",
                "当前：" + BallIcon.current(this).label
                        + "（" + BallIcon.current(this).group + "）",
                v -> startActivity(new Intent(this, BallIconActivity.class))));
        root.addView(cIcon);

        // ── 随任务阶段自动换表情 ──
        final boolean face = BallWindow.autoFace(this);
        LinearLayout cFace = Ui.card(this, "随任务阶段换表情",
                "开着时，球会跟着 DSH 在干什么自动换表情 —— 空闲睡觉、起步抱鲸鱼、"
                + "琢磨时无语、编译叹气、干活举手、做完害羞、出错大哭；"
                + "换的时候带放大 + 轻微旋转的过渡。关掉就只用上面手选的那张。");
        cFace.addView(Ui.row(this, face ? "已开启（默认）" : "已关闭",
                face ? "球会随阶段自动变脸" : "只用你手动选的那张",
                v -> {
                    BallWindow.setAutoFace(this, !face);
                    recreate();
                }));
        root.addView(cFace);

        // ── 图标大小 ──
        LinearLayout cSize = Ui.card(this, "图标大小",
                "球本身多大。太大挡视线，太小不好点。");
        cSize.addView(chips(BallWindow.BALL_SIZES, BallWindow.ballSizeDp(this),
                new String[]{"最小", "小", "标准", "大", "最大"},
                (idx) -> {
                    BallWindow.setBallSizeDp(this, BallWindow.BALL_SIZES[idx]);
                    recreate();
                }));
        root.addView(cSize);

        // ── 气泡大小 ──
        LinearLayout cBub = Ui.card(this, "气泡大小",
                "点球冒出来的那一条。字号、圆角、内边距、最大宽度一起跟着变。");
        cBub.addView(chips(BallWindow.BUBBLE_SCALES, BallWindow.bubbleScalePct(this),
                new String[]{"最小", "小", "标准", "大", "最大"},
                (idx) -> {
                    BallWindow.setBubbleScalePct(this, BallWindow.BUBBLE_SCALES[idx]);
                    recreate();
                }));
        root.addView(cBub);

        // ── 自动出现 ──
        final boolean auto = BallWindow.autoMode(this);
        LinearLayout cAuto = Ui.card(this, "什么时候出现", null);
        cAuto.addView(Ui.row(this, auto ? "自动（默认）" : "常驻",
                auto ? "退到后台、容器正在干活时才出现；回到 Tdsh 或任务结束就消失"
                     : "球会一直在屏幕上",
                v -> {
                    BallWindow.setAutoMode(this, !auto);
                    recreate();
                }));
        root.addView(cAuto);

        // ── 状态 ──
        LinearLayout cSt = Ui.card(this, "当前状态", null);
        cSt.addView(Ui.status(this, BallWindow.statusText(this), Ui.TEXT));
        root.addView(cSt);

        // ── 操作教程（从 PhonePermActivity 的对话框搬过来）──
        LinearLayout cHow = Ui.card(this, "怎么用", TUTORIAL);
        root.addView(cHow);

        // ── 开关 ──
        LinearLayout cOn = Ui.card(this, null, null);
        cOn.addView(Ui.row(this, BallWindow.isShown() ? "收起浮窗" : "打开悬浮球",
                BallWindow.isShown() ? "收起不会关掉 DSH，容器继续在后台跑"
                                     : "需要「显示在其他应用上层」权限",
                v -> {
                    if (BallWindow.isShown()) {
                        BallWindow.hide(this);
                    } else if (!BallWindow.canDraw(this)) {
                        BallWindow.requestPermission(this);
                    } else {
                        BallWindow.show(this);
                    }
                    recreate();
                }));
        root.addView(cOn);
    }

    /** 教程正文（一字不改地从原来的对话框搬来）。 */
    private static final String TUTORIAL =
            "平时是一个小球（可以换成表情包或投稿图）浮在其他应用之上，不挡视线。\n\n"
          + "· 点一次球：冒出一小条气泡，告诉你现在进行到哪一步；\n"
          + "· 再点一次，或点气泡本身：气泡收起，球回原位；\n"
          + "· 连点两次：回到 Tdsh 主界面；\n"
          + "· 按住球拖动可以换位置（会自动避开屏幕顶部的系统手势区）；\n"
          + "· 任务开始时气泡会自己弹出来几秒 —— 不用特意去点；\n"
          + "· 想开整屏界面，从「手机权限」那条路进去。\n\n"
          + "收起浮窗不会关掉 DSH，容器继续在后台跑。";

    /** 把当前（或当前图标组里最接近的）档位映射成下标。 */
    private interface OnPick { void pick(int index); }

    /**
     * 一排可选档位。
     *
     * @param values   档位数值
     * @param current  当前值（不在表里时高亮最接近的一档）
     * @param labels   每档的显示名
     */
    private LinearLayout chips(int[] values, int current, String[] labels, OnPick onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);

        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - current) < Math.abs(values[best] - current)) best = i;
        }

        for (int i = 0; i < values.length; i++) {
            final int idx = i;
            boolean on = i == best;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(13f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(on ? Color.WHITE : Ui.TEXT);
            t.setBackground(Ui.round(on ? 0xFF2A4B8D : 0x00000000,
                    on ? 0 : Ui.STROKE, 10, this));
            t.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i == values.length - 1 ? 0 : Ui.dp(this, 6);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> onPick.pick(idx));
            row.addView(t);
        }
        return row;
    }
}
