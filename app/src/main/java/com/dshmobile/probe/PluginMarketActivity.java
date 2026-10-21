package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 🧩 插件市场：列出 npm 上的 DSH 插件，一键安装。
 *
 * <h3>为什么"市场"放在 App 里而不是做成 DSH 插件</h3>
 * 装插件要跑 {@code dsh plugin add}，那需要容器里的 proot 环境 ——
 * 而**只有 App 能起它**。DSH 插件自己跑在 dsh 进程里，
 * 没法再起一个 dsh 去改自己的 profile。所以这个界面只能在 App 侧。
 *
 * <h3>搜索从哪来</h3>
 * 直接问 npm registry 的 {@code keywords:dsh-plugin} —— 这正是 DSH 官方的
 * 插件发现机制（发布时给仓库/包打这个标签）。自己维护一份清单会很快过期。
 *
 * <h3>这一版改了什么（用户反馈"过于杂乱"）</h3>
 * <ul>
 *   <li><b>一行两个卡片</b>：原来是 ListView 一行一个"文字 + 按钮"，
 *       屏幕利用率很低，一屏看不到几个；换成 2 列 GridView。</li>
 *   <li><b>分类抽屉</b>：可以按「记忆 / 主题皮肤 / 预设 / 界面 / 工具 / 其它」筛。
 *       npm **没有** category 字段，分类是从包的 {@code keywords} 里推出来的
 *       （创作者都会给 dsh 插件打能力词），推不出来就归「其它」——不猜。</li>
 *   <li><b>分页</b>：原来是 {@code size=25} 写死、没有 {@code from}，
 *       界面永远只有 25 条，而 npm 上带这个标签的包实测有 8000+ 条。
 *       现在 40 条一页 + 「加载更多」。</li>
 *   <li><b>配色</b>：行文字原来是 {@code Color.DKGRAY} 配深底，
 *       对比度极差；统一改用 {@link Ui} 的卡片与文字色。</li>
 * </ul>
 *
 * <h3>⚠️ 安全上必须说清楚的事</h3>
 * 装一个第三方插件 = **让它的代码在用户手机上跑**，而且是在容器里、
 * 带着 danger-full-access 的权限跑。所以这个界面：
 *   · 每条结果都显示包名和描述，让用户知道自己在装什么；
 *   · 装之前弹一次确认，把上面这句话原样告诉用户；
 *   · 装完明确提示"重启 DSH 后生效"。
 * 我们不替用户做这个信任判断。
 */
public class PluginMarketActivity extends Activity {

    /* ─────────────── 分类 ─────────────── */

    private static final String[] CAT_NAMES = {
            "全部", "记忆", "主题皮肤", "预设", "界面", "工具", "其它"
    };

    /**
     * 每个分类的匹配词（小写）。下标 0 是"全部"、不参与匹配；
     * 最后一个"其它"是兜底，也参与不了匹配 —— 所以表只到「工具」。
     */
    private static final String[][] CAT_WORDS = {
            {},
            {"memory", "mem", "recall", "context", "session", "记忆", "历史", "上下文"},
            {"theme", "skin", "wallpaper", "appearance", "壁纸", "美化", "外观", "皮肤"},
            {"preset", "persona", "agent", "prompt", "人格", "预设", "角色", "提示词"},
            {"ui", "web", "panel", "sidebar", "widget", "client", "界面", "组件", "可视化"},
            {"tool", "ops", "shell", "file", "git", "dev", "终端", "命令", "文件", "工具"},
    };

    private static final int CAT_ALL = 0;
    private static final int CAT_OTHER = CAT_NAMES.length - 1;

    private static final int PAGE_SIZE = 40;

    /* ─────────────── 状态 ─────────────── */

    private EditText search;
    private TextView status;
    private LinearLayout drawer;          // 分类按钮区（可收起）
    private View drawerToggle;            // Ui.row 返回的是 View，不是 TextView
    private TextView moreBtn;             // Ui.button 返回的是 TextView，不是 Button

    private final List<PhoneBridge.PluginHit> loaded = new ArrayList<>();   // 已加载的原始结果
    private final List<PhoneBridge.PluginHit> shown = new ArrayList<>();    // 当前分类筛出来的
    private int total = 0;
    private int category = CAT_ALL;
    private String lastQuery = "";

    private List<String> installed = new ArrayList<>();
    private HitAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("插件市场");
        title.setTextSize(20f);
        title.setTextColor(Ui.TEXT);
        root.addView(title);

        status = Ui.status(this, "正在读取已安装列表…", Ui.SUB);
        root.addView(status);

        // ── 搜索行 ──
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setHint("搜索插件（留空 = 全部）");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.SUB);
        search.setSingleLine(true);
        srow.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = Ui.button(this, "搜索", true, v -> {
            loaded.clear();
            doSearch(0);
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = Ui.dp(this, 8);
        go.setLayoutParams(glp);
        srow.addView(go);
        root.addView(srow);

        // ── 分类抽屉 ──
        LinearLayout drawerHead = Ui.card(this, null, null);
        drawerToggle = Ui.row(this, "分类：全部", "点一下展开 / 收起筛选", v -> toggleDrawer());
        drawerHead.addView(drawerToggle);
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setVisibility(View.GONE);
        buildCategoryChips(drawer);
        drawerHead.addView(drawer);
        root.addView(drawerHead);

        // ── 结果网格（一行两个）──
        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setVerticalSpacing(Ui.dp(this, 10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setBackgroundColor(Ui.BG);
        adapter = new HitAdapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 底部：加载更多 + 刷新已装 ──
        moreBtn = Ui.button(this, "加载更多", false, v -> doSearch(loaded.size()));
        moreBtn.setVisibility(View.GONE);
        root.addView(moreBtn);

        root.addView(Ui.button(this, "刷新已安装列表", false, v -> refreshInstalled()));

        setContentView(root);

        refreshInstalled();
        doSearch(0);
    }

    /* ─────────────── 分类 ─────────────── */

    private void buildCategoryChips(LinearLayout box) {
        // 每行 3 个，避免一列拉太长；用 chip 而不是下拉菜单，点起来更快
        LinearLayout row = null;
        for (int i = 0; i < CAT_NAMES.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, Ui.dp(this, 6), 0, 0);
                box.addView(row);
            }
            final int cat = i;
            boolean on = cat == category;
            TextView t = new TextView(this);
            t.setText(CAT_NAMES[i]);
            t.setTextSize(13f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(on ? Color.WHITE : Ui.TEXT);
            t.setBackground(Ui.round(on ? 0xFF2A4B8D : 0x00000000,
                    on ? 0 : Ui.STROKE, 10, this));
            t.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, 6);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                category = cat;
                rebuildDrawer();
                applyCategory();
            });
            row.addView(t);
        }
    }

    /** 重建分类按钮区（选中态变了就重画）。 */
    private void rebuildDrawer() {
        boolean open = drawer.getVisibility() == View.VISIBLE;
        drawer.removeAllViews();
        buildCategoryChips(drawer);
        drawer.setVisibility(open ? View.VISIBLE : View.GONE);
        drawerToggle = null;   // 标题行不用变（下面 applyCategory 会更新 status）
    }

    private void toggleDrawer() {
        boolean open = drawer.getVisibility() == View.VISIBLE;
        drawer.setVisibility(open ? View.GONE : View.VISIBLE);
    }

    /**
     * 从 keywords / 名字 / 描述里推分类。
     *
     * <p>顺序敏感：先命中先赢。所以"记忆"这类辨识度高的词排在 "ui"、"file"
     * 这些很泛的词前面 —— 否则一个叫 "xxx-ui-memory" 的插件会被泛词抢走。
     */
    private int categoryOf(PhoneBridge.PluginHit h) {
        StringBuilder sb = new StringBuilder();
        for (String k : h.keywords) sb.append(k).append(' ');
        sb.append(h.name).append(' ').append(h.description);
        String hay = sb.toString().toLowerCase(java.util.Locale.ROOT);
        for (int c = 1; c < CAT_WORDS.length; c++) {
            for (String w : CAT_WORDS[c]) {
                if (hay.contains(w)) return c;
            }
        }
        return CAT_OTHER;
    }

    private void applyCategory() {
        shown.clear();
        for (PhoneBridge.PluginHit h : loaded) {
            if (category == CAT_ALL || categoryOf(h) == category) shown.add(h);
        }
        adapter.notifyDataSetChanged();
        String catName = CAT_NAMES[category];
        status.setText("共 " + total + " 个 · 已载入 " + loaded.size()
                + " · 当前「" + catName + "」" + shown.size() + " 个"
                + (category == CAT_ALL ? "" : "（筛选只作用于已载入的部分）"));
        // "其它"这一档只有在已经翻到底时才是准的，所以照常允许继续加载
        moreBtn.setVisibility(loaded.size() < total ? View.VISIBLE : View.GONE);
    }

    /* ─────────────── 数据 ─────────────── */

    private void refreshInstalled() {
        installed = PhoneBridge.marketInstalled(this);
        applyCategory();
    }

    private void doSearch(final int from) {
        final String q = search.getText().toString();
        if (from == 0) {
            lastQuery = q;
            status.setText("正在搜索 npm…");
        } else {
            status.setText("正在加载更多…");
        }
        new Thread(() -> {
            try {
                final PhoneBridge.MarketPage page =
                        PhoneBridge.marketSearch(this, q, from, PAGE_SIZE);
                ui.post(() -> {
                    total = page.total;
                    loaded.addAll(page.hits);
                    applyCategory();
                });
            } catch (Throwable t) {
                ui.post(() -> status.setText("搜索失败：" + t.getMessage()
                        + "\n（需要联网；容器里装插件同样需要联网）"));
            }
        }, "market-search").start();
    }

    /* ─────────────── 安装 / 卸载 ─────────────── */

    /** 装之前必须让用户知道自己在做什么。 */
    private void confirmInstall(final String pkg) {
        String warn = "即将安装：\n\n" + pkg + "\n\n"
                + "插件是第三方代码，装上之后会在你的手机里运行。"
                + "（跑在 App 的 Linux 容器内）。\n\n"
                + "请只安装你信任的来源。装完需要重启 DSH 才会生效。\n\n"
                + "确定安装吗？";
        new AlertDialog.Builder(this)
                .setTitle("确认安装插件")
                .setMessage(warn)
                .setPositiveButton("安装", (d, w) -> doInstall(pkg))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doInstall(final String pkg) {
        final ProgressDialog pd = ProgressDialog.show(this, "正在安装",
                pkg + "\n\n第一次装可能会慢一些（要下依赖）", true, false);
        new Thread(() -> {
            final String r = PhoneBridge.marketAdd(this, pkg);
            ui.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignore) { }
                boolean ok = r.startsWith("OK");
                new AlertDialog.Builder(this)
                        .setTitle(ok ? "安装完成" : "安装失败")
                        .setMessage(r + (ok ? "\n\n要现在重启 DSH 吗？" : ""))
                        .setPositiveButton(ok ? "重启 DSH" : "好", (d, w) -> {
                            if (ok) {
                                DshService.requestRestart();
                                Toast.makeText(this, "正在重启 DSH…", Toast.LENGTH_LONG).show();
                                finish();
                            }
                        })
                        .setNegativeButton(ok ? "稍后" : null, null)
                        .show();
                refreshInstalled();
            });
        }, "market-install").start();
    }

    private void confirmRemove(final String pkg) {
        new AlertDialog.Builder(this)
                .setTitle("卸载插件")
                .setMessage("确定卸载 " + pkg + " 吗？\n\n重启 DSH 后生效。")
                .setPositiveButton("卸载", (d, w) -> {
                    final ProgressDialog pd = ProgressDialog.show(this, "正在卸载", pkg, true, false);
                    new Thread(() -> {
                        final String r = PhoneBridge.marketRemove(this, pkg);
                        ui.post(() -> {
                            try { pd.dismiss(); } catch (Throwable ignore) { }
                            Toast.makeText(this, r, Toast.LENGTH_LONG).show();
                            refreshInstalled();
                        });
                    }, "market-remove").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ─────────────── 卡片 ─────────────── */

    /** 一行两个的卡片。 */
    private class HitAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            TextView name, desc, btn;
            if (convertView instanceof LinearLayout
                    && ((LinearLayout) convertView).getChildCount() == 3) {
                card = (LinearLayout) convertView;
                name = (TextView) card.getChildAt(0);
                desc = (TextView) card.getChildAt(1);
                btn = (TextView) card.getChildAt(2);
            } else {
                card = new LinearLayout(PluginMarketActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Ui.round(Ui.CARD, Ui.STROKE, 14, PluginMarketActivity.this));
                int p = Ui.dp(PluginMarketActivity.this, 12);
                card.setPadding(p, p, p, p);
                card.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                name = new TextView(PluginMarketActivity.this);
                name.setTextSize(14f);
                name.setMaxLines(2);
                card.addView(name);

                desc = new TextView(PluginMarketActivity.this);
                desc.setTextSize(11f);
                desc.setTextColor(Ui.SUB);
                desc.setPadding(0, Ui.dp(PluginMarketActivity.this, 6), 0, 0);
                desc.setMaxLines(3);
                desc.setLineSpacing(Ui.dp(PluginMarketActivity.this, 2), 1f);
                card.addView(desc);

                btn = new TextView(PluginMarketActivity.this);
                btn.setTextSize(12f);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(0, Ui.dp(PluginMarketActivity.this, 8),
                        0, Ui.dp(PluginMarketActivity.this, 8));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = Ui.dp(PluginMarketActivity.this, 10);
                btn.setLayoutParams(blp);
                card.addView(btn);
            }

            final PhoneBridge.PluginHit h = shown.get(position);
            boolean isInstalled = installed.contains(h.name);

            name.setText(h.name);
            name.setTextColor(isInstalled ? 0xFF4CAF50 : Ui.TEXT);

            String d = h.description == null || h.description.isEmpty()
                    ? "（作者没写描述）" : h.description;
            if (d.length() > 80) d = d.substring(0, 80) + "…";
            desc.setText(h.version + " · " + d);

            if (isInstalled) {
                btn.setText("已装 · 卸载");
                btn.setTextColor(Ui.WARN);
                btn.setBackground(Ui.round(0x00000000, Ui.STROKE, 10, PluginMarketActivity.this));
                btn.setOnClickListener(v -> confirmRemove(h.name));
            } else {
                btn.setText("安装");
                btn.setTextColor(Color.WHITE);
                btn.setBackground(Ui.round(Ui.ACCENT, 0, 10, PluginMarketActivity.this));
                btn.setOnClickListener(v -> confirmInstall(h.name));
            }
            return card;
        }
    }
}
