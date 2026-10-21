package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「手机权限」—— 把原来散在设置列表里的一堆「（本机）」入口收成一个大类。
 *
 * <p>用户的原话：这些页面"过多杂乱的解释"，要"简约大气一些"。
 * 所以这一页只有：一行标题 + 每项一行字 + 一行说明，不再堆解释。
 */
public class PhonePermActivity extends Activity {

    /** Shell 开关的程序化回弹保护（见 shellCard）。 */
    private boolean refreshingShell;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        LinearLayout root = Ui.page(this, "手机权限", v -> finish());

        TextView sub = Ui.status(this,
                "这些是针对这台手机的本机设置。", Ui.SUB);
        root.addView(sub);

        /*
         * 插件市场**单独置顶、不进分类**（用户要求）。
         * 它和其他项不是一类：别的是"配置这台手机"，它是"装东西"，
         * 而且是最常来的入口，埋进分组里反而难找。
         */
        LinearLayout cMarket = Ui.card(this, null, null);
        cMarket.addView(Ui.row(this, "插件市场",
                "从 npm 搜索并安装 DSH 插件",
                v -> open(PluginMarketActivity.class)));
        root.addView(cMarket);

        /* ═══════════ ① 权限相关 ═══════════ */
        root.addView(Ui.section(this, "权限相关"));

        // 手机控制（含白名单）
        LinearLayout c1 = Ui.card(this, null, null);
        c1.addView(Ui.row(this, "手机控制",
                "允许 DSH 操作哪些应用 · 白名单已选 " + PhoneBridge.allowed(this).size() + " 个",
                v -> open(AppControlActivity.class)));
        root.addView(c1);

        root.addView(shellCard());

        /*
         * 录制屏幕内声音（GitHub issue #5：「建议开启录制屏幕内声音功能」）。
         *
         * 放在「权限相关」而不是「插件」：它本质是一次权限动作 ——
         * 用户要过一下系统那个"开始录制或投放"的确认框。
         * 真正的解码/识别在容器里的语音插件那边，这里只负责把声音录成 WAV 放进工作区。
         * 为什么必须由 App 干这件事：投影令牌只能由前台 Activity 申请，
         * 容器里的插件拿不到（详见 AudioCapture 的类注释）。
         */
        LinearLayout cAudio = Ui.card(this, null, null);
        audioRow = (LinearLayout) Ui.row(this, "录制屏幕内声音", audioHint(),
                v -> toggleAudioCapture());
        cAudio.addView(audioRow);
        root.addView(cAudio);

        /* ═══════════ ② 后台相关 ═══════════ */
        root.addView(Ui.section(this, "后台相关"));

        // 常驻服务（CPA 提示放在说明里）
        LinearLayout c3 = Ui.card(this, null, null);
        c3.addView(Ui.row(this, "常驻服务",
                "让本地代理常驻自启 · CPA（CLIProxyAPI）就配在这里",
                v -> open(ServicesActivity.class)));
        root.addView(c3);

        // 虚拟屏自动化（群友「如月雨露」的受信任虚拟屏方案）
        LinearLayout cVs = Ui.card(this, null, null);
        cVs.addView(Ui.row(this, "虚拟屏自动化",
                "后台跑应用 + 取画面 + 按坐标操作（随包自带，一键装到手机上）",
                v -> open(VScreenActivity.class)));
        root.addView(cVs);

        // DSH 悬浮球（在其他应用之上用 DSH）
        LinearLayout cOv = Ui.card(this, null, null);
        cOv.addView(Ui.row(this, "DSH 悬浮球",
                BallWindow.statusText(this) + " · 图标：" + BallIcon.current(this).label,
                v -> open(BallSettingsActivity.class)));
        root.addView(cOv);

        /*
         * 性能体检（2026-10-21 用户报"用久了发烫 / 插件多了启动慢"）。
         * 放在「后台相关」：它就是给"机器怎么了"这类问题准备的取证工具。
         */
        LinearLayout cPerf = Ui.card(this, null, null);
        cPerf.addView(Ui.row(this, "性能体检（CPU / 内存）",
                "看看容器里到底是谁在吃 CPU（发热时点一下，明细存到共享存储）",
                v -> runPerfCheck()));
        root.addView(cPerf);

        /*
         * ⚠️ 「实时进度悬浮条」（灵动岛胶囊）**不放在本页**。
         *
         * 2026-10-15 用户明确：它是 **.wan 版特供**（那个版本的触发方式与本版不同），
         * 正式版里去掉这条入口，代码保留在 HudOverlayService，由 .wan 分支自行接回。
         * 开关的代码与 onResume 的续授权逻辑一并撤走，避免留下没人点的死代码。
         */

        /* ═══════════ ③ 模型 ═══════════ */
        root.addView(Ui.section(this, "模型"));

        // 中转站（自定义提供方）：模型会出现在 DSH 的模型选择器里
        int relayN = Providers.load(this).size();
        LinearLayout cRelay = Ui.card(this, null, null);
        cRelay.addView(Ui.row(this, "中转站",
                relayN == 0 ? "接入第三方网关 / 中转站（自定义提供方）"
                            : "已接入 " + relayN + " 个提供方 · 模型会出现在 DSH 的模型选择器里",
                v -> open(RelayActivity.class)));
        root.addView(cRelay);

        /* ═══════════ ④ 自定义相关 ═══════════ */
        root.addView(Ui.section(this, "自定义相关"));

        // 桌面图标
        LinearLayout c5 = Ui.card(this, null, null);
        c5.addView(Ui.row(this, "桌面图标",
                "换桌面快捷方式配色 · 通知栏图标跟着一起换",
                v -> open(IconActivity.class)));
        root.addView(c5);

        // 环境安装源（地区）
        LinearLayout c6 = Ui.card(this, null, null);
        c6.addView(Ui.row(this, "环境安装源",
                "当前：" + Region.label(this) + " · 装环境慢或总失败就改这里",
                v -> pickRegion()));
        root.addView(c6);

        // 更新日志
        Changelog.Entry cur2 = Changelog.current();
        LinearLayout c9 = Ui.card(this, null, null);
        c9.addView(Ui.row(this, "更新日志",
                cur2 == null ? "这一版改了什么"
                             : "v" + cur2.version + " · 第 " + cur2.code + " 版改了什么",
                v -> open(ChangelogActivity.class)));
        root.addView(c9);

        /* ═══════════ ⑤ 进阶操作 ═══════════ */
        root.addView(Ui.section(this, "进阶操作"));

        // 内核升级（顺手把当前版本显示出来）
        String cur = KernelUpgrade.installedVersion(this);
        LinearLayout c4 = Ui.card(this, null, null);
        c4.addView(Ui.row(this, "内核升级",
                "当前 " + (cur == null ? "读不到" : cur) + " · 检测到新版本可一键升级",
                v -> open(UpgradeActivity.class)));
        root.addView(c4);

        // 插件安全模式：有插件被停用时这里最显眼 —— 它是"界面被插件卡死"之后唯一的恢复入口
        int offN = Env.disabledBundles(this).size();
        LinearLayout c7 = Ui.card(this, null, null);
        c7.addView(Ui.row(this, "插件安全模式",
                offN == 0 ? "正常（所有插件都会加载）"
                          : "已停用 " + offN + " 个插件 · 点这里恢复",
                v -> pluginSafeMode()));
        root.addView(c7);

        // 容器终端（每命令一个新 proot 会话，只读命令随便跑）
        LinearLayout cTerm = Ui.card(this, null, null);
        cTerm.addView(Ui.row(this, "容器终端",
                "在手机上直接敲容器里的命令（ls / dsh --version / npm …）",
                v -> open(TerminalActivity.class)));
        root.addView(cTerm);

        // 工作区与残留进程
        LinearLayout cWs = Ui.card(this, null, null);
        cWs.addView(Ui.row(this, "工作区与占用",
                "工作区路径（可用 MT 管理器直接打开） · " + procSummary(),
                v -> workspaceMenu()));
        root.addView(cWs);

        /*
         * 复制运行日志 —— App 的现场诊断（界面白屏后怎么恢复的、附件为什么没加上、
         * 自带插件装没装上）全写在私有目录的 report.txt 里，用户既看不到也导不出来。
         * 没有这个入口，"把日志发我看看"就没法执行，只能靠反复猜。
         */
        LinearLayout c8 = Ui.card(this, null, null);
        c8.addView(Ui.row(this, "复制运行日志",
                "遇到问题（加不上文件 / 界面白屏）时点这里，然后粘贴给开发者",
                v -> android.widget.Toast.makeText(this,
                        Env.copyReportToClipboard(this, 300),
                        android.widget.Toast.LENGTH_LONG).show()));
        root.addView(c8);

        /* Shell 开关：见 shellCard() 的注释 */
    }

    /**
     * Shell 开关 —— 用户要求"加一个 shell 开关"。
     *
     * <p>它管的是自带插件里的 {@code phone_sh} / {@code phone_fs} 两个工具：
     * 关掉之后 agent 就再也执行不了系统命令、读写不了白名单目录。
     * 默认**开**（和旧行为一致），但给一个显式开关 —— 把系统级能力交给 agent 这件事上，
     * 唯一该由人亲手按的闸就是这个。
     *
     * <p>打开时会弹一次确认：这个开关不能被误触，也不该被 agent 自己打开。
     */
    private View shellCard() {
        boolean on = PhoneBridge.allowPrivSh(this);
        LinearLayout box = Ui.card(this, "Shell 开关",
                "允许 DSH 执行系统命令（phone_sh / phone_fs）"
                + " · Shizuku：" + ShizukuBridge.statusText(this));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setText(on ? "已允许" : "已禁止");
        sw.setTextColor(Ui.TEXT);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, checked) -> {
            /*
             * 程序化 setChecked（比如取消确认时回弹）会回调进来，必须挡掉，
             * 否则"点取消"反而会被当成"点允许"再把开关打开一次。
             */
            if (refreshingShell) return;
            if (checked == PhoneBridge.allowPrivSh(this)) return;
            if (!checked) {
                PhoneBridge.setAllowPrivSh(this, false);
                b.setText("已禁止");
                android.widget.Toast.makeText(this, "已关闭：DSH 不能再执行系统命令",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("允许 DSH 执行系统命令？")
                    .setMessage("打开之后，DSH 里的 agent 可以：\n"
                            + "· 执行 shell 命令（需要 Shizuku 已授权）\n"
                            + "· 读写 /sdcard、/data/local/tmp、容器 root 目录\n\n"
                            + "这等于把手机的一部分控制权交出去。只在需要时打开。")
                    .setNegativeButton("取消", (d, w) -> {
                        refreshingShell = true;
                        b.setChecked(false);
                        refreshingShell = false;
                    })
                    .setPositiveButton("允许", (d, w) -> {
                        PhoneBridge.setAllowPrivSh(this, true);
                        b.setText("已允许");
                    })
                    .setOnCancelListener(d -> {
                        refreshingShell = true;
                        b.setChecked(false);
                        refreshingShell = false;
                    })
                    .show();
        });
        box.addView(sw);
        if (!ShizukuBridge.hasPermission()) {
            box.addView(Ui.button(this, "用 Shizuku 授权（不用电脑）", false,
                    v -> ShizukuBridge.showGrantDialog(this, this::recreate)));
            box.addView(Ui.status(this,
                    "没授权的话开关打开也不会生效。Shizuku 现在：" + ShizukuBridge.statusText(this),
                    Ui.WARN));
        }
        return box;
    }

    /**
     * ⚠️ 这里**曾经**有「实时进度悬浮条」的续授权逻辑（用户去系统页授权后回来自动把胶囊起起来）。
     *
     * 2026-10-15 用户确认那条胶囊是 **.wan 版特供**，正式版去掉入口，
     * 连同那段 onResume 收尾一起撤掉 —— 留着就是没人会触发的死代码。
     * HudOverlayService 本身保留，由 .wan 分支接回。
     */

    /**
     * 插件安全模式：恢复被停用的插件 / 手动进入安全模式。
     *
     * <p>什么时候会用到：装了**为 DSH 0.1.x 写的**第三方插件时，
     * 它们等的服务名在 0.2 里已经改了（settingsScope → settings），
     * 前端会一直 pending，整个界面起不来。App 会把它们摘掉让界面先回来，
     * 恢复（或再摘一次）就是这个入口。
     *
     * <p>0.2.8.2 起这里会把**每个被停用插件的文件现在在哪**写出来，并给「彻底删除」——
     * 起因是用户反馈"开了安全模式之后插件直接没了，但文件还在"：
     * 我们只把 {@code node_modules/<包名>} 挪进了 {@code .dsh-disabled/}，
     * 而 pnpm 的共享商店里还留着一份真身（那是包管理器自己的缓存）。
     */
    private void pluginSafeMode() {
        final java.util.List<String> off = Env.disabledBundles(this);
        String msg;
        if (off.isEmpty()) {
            msg = "当前所有插件都启用着。\n\n"
                + "如果界面被某个插件卡住（白屏 / Failed to load plugins），"
                + "可以在这里手动进安全模式：只加载官方插件和自带插件，"
                + "其余第三方插件先停掉，等界面回来了再逐个试。";
        } else {
            StringBuilder sb = new StringBuilder("已停用的插件：\n");
            for (String s : off) {
                sb.append("  · ").append(s).append('\n')
                  .append("      ").append(Env.pluginFileState(this, s)).append('\n');
            }
            sb.append("\n「恢复全部」会把它们重新加回加载列表，文件挪回原位（推荐）。\n")
              .append("「彻底删除」则把文件也一并删掉 —— 连 pnpm 商店里那份副本一起，")
              .append("之后想再用只能重新装一次。\n\n")
              .append("说明：停用时文件是**挪走**而不是删除，所以还能一键恢复；")
              .append("你在手机里可能仍能看到它占的空间，那是 pnpm 的共享商店，")
              .append("用「彻底删除」才会真正清掉。");
            msg = sb.toString();
        }
        final boolean empty = off.isEmpty();
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("插件安全模式")
                .setMessage(msg)
                .setPositiveButton(empty ? "进入安全模式" : "恢复全部", (d, w) -> {
                    int n = empty ? Env.enterSafeMode(this) : Env.restoreDisabledBundles(this);
                    android.widget.Toast.makeText(this,
                            empty ? ("已停用 " + n + " 个插件，重启 DSH 后生效")
                                  : ("已恢复 " + n + " 个插件，重启 DSH 后生效"),
                            android.widget.Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("取消", null);
        if (!empty) {
            b.setNeutralButton("彻底删除", (d, w) -> confirmPurge(off));
        }
        b.show();
    }

    /** 「彻底删除」前的二次确认 —— 这一步不可逆。 */
    private void confirmPurge(final java.util.List<String> off) {
        StringBuilder sb = new StringBuilder("会**彻底删除**这些插件的所有文件：\n");
        for (String s : off) sb.append("  · ").append(s).append('\n');
        sb.append("\n包括 node_modules 里的原位目录、.dsh-disabled 里的备份，")
          .append("以及 pnpm 商店里的副本。\n\n")
          .append("删掉之后想再用只能重新安装。确定吗？");
        new android.app.AlertDialog.Builder(this)
                .setTitle("彻底删除这些插件？")
                .setMessage(sb.toString())
                .setPositiveButton("删除", (d, w) -> {
                    int n = Env.purgeDisabledPlugins(this);
                    android.widget.Toast.makeText(this,
                            "已彻底删除 " + n + " 个插件（含文件）",
                            android.widget.Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("不删", null)
                .show();
    }

    /**
     * 改地区（中国大陆 / 非中国大陆）。
     *
     * <p>只影响**之后**的下载：已装好的容器不会因为改这里就重装。
     * 想让新地区立刻生效，用主界面长按「重试」清空容器重装。
     */
    private void pickRegion() {
        final String[] values = {Region.CN, Region.GLOBAL};
        final String[] labels = {"中国大陆（阿里云 / 中科大镜像）", "非中国大陆（Ubuntu 官方源 / nodejs.org）"};
        int cur = Region.isCn(this) ? 0 : 1;
        new android.app.AlertDialog.Builder(this)
                .setTitle("环境安装源")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    Region.set(this, values[which]);
                    d.dismiss();
                    /*
                     * 立刻写进**已经装好的**容器（apt 源 + /root/.npmrc）。
                     * 只改 App 里的偏好、不动容器，用户会觉得"改了没反应"：
                     * 容器内装插件/升级内核仍然打官方源。
                     */
                    final boolean[] wrote = {false};
                    try {
                        java.io.File rootfs = new java.io.File(Env.base(this), "rootfs");
                        if (new java.io.File(rootfs, "usr/bin/bash").exists()) {
                            Region.applyAptSources(this, rootfs);
                            Region.applyNpmrc(this, rootfs);
                            wrote[0] = true;
                        }
                    } catch (Throwable ignore) { }
                    android.widget.Toast.makeText(this,
                            "已改为" + Region.label(this)
                            + (wrote[0] ? " —— 容器里的 apt 源与 npm 源已同步更新"
                                        : " —— 容器还没装好，装的时候会按新地区走"),
                            android.widget.Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 悬浮窗的开关菜单。
     *
     * <p>为什么要先讲清"能穿透"和"能打字"不可兼得：这是 Android 的硬约束 ——
     * 想让浮层里的 DSH 输入框能弹出软键盘，窗口就必须可聚焦，于是它会短暂持有焦点；
     * 反过来加了 {@code FLAG_NOT_FOCUSABLE} 就永远打不了字。
     * 我们选了"能打字"，用户提前知道就不会以为坏了。
     */
    private void overlayMenu() {
        final boolean canDraw = BallWindow.canDraw(this);
        final boolean shown = BallWindow.isShown();
        String msg = "平时是**一个小圆球**（可以换成别的图标）浮在其他应用之上，不挡视线。\n\n"
                + "**怎么用**\n"
                + "· **点一次**球：冒出一小条气泡，告诉你现在在运行什么；\n"
                + "· **再点一次**：气泡收起，球回到原来的位置；\n"
                + "· **连点两次**：回到 Tdsh 主界面；\n"
                + "· 按住球**拖动**可以换位置（会自动避开屏幕顶部的系统手势区）；\n"
                + "· 气泡上点一下也能收起；想开整屏界面从「手机权限 → 虚拟屏/终端」那条路进去。\n\n"
                + "收起浮窗**不会**关掉 DSH，容器继续在后台跑。\n\n"
                + "**自动出现（默认开）**：App 退到后台、且容器**正在干活**时它自己冒出来；"
                + "回到 Tdsh（含所有设置页）或任务结束就自动消失。关掉它就变常驻。\n\n"
                + "当前状态：" + BallWindow.statusText(this)
                + "\n当前图标：" + BallIcon.current(this).label
                + "\n自动出现：" + (BallWindow.autoMode(this) ? "开" : "关（常驻）");
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("DSH 悬浮球")
                .setMessage(msg);
        if (!canDraw) {
            b.setPositiveButton("去授权", (d, w) -> {
                BallWindow.requestPermission(this);
                android.widget.Toast.makeText(this,
                        "打开「显示在其他应用上层」开关，然后回来点一次这里",
                        android.widget.Toast.LENGTH_LONG).show();
            });
        } else if (shown) {
            b.setPositiveButton("收起浮窗", (d, w) -> {
                BallWindow.hide(this);
                android.widget.Toast.makeText(this, "已收起（DSH 仍在后台）",
                        android.widget.Toast.LENGTH_LONG).show();
                recreate();
            });
            b.setNeutralButton("换图标", (d, w) -> open(BallIconActivity.class));
        } else {
            b.setPositiveButton("打开悬浮球", (d, w) -> {
                BallWindow.show(this);
                android.widget.Toast.makeText(this, "正在打开…（DSH 没就绪时会提示）",
                        android.widget.Toast.LENGTH_LONG).show();
                recreate();
            });
            b.setNeutralButton("换图标", (d, w) -> open(BallIconActivity.class));
        }
        /*
         * 按钮上写的是**点了会发生什么**（动作目标），不是当前状态 ——
         * 第一版把 Toast 和按钮的措辞写反了：当前是常驻时按钮却写"改成常驻"，
         * 用户点一下看到提示"已改成自动"，完全对不上（用户报的就是这条）。
         */
        final boolean auto = BallWindow.autoMode(this);
        b.setNegativeButton(auto ? "改成常驻" : "改成自动", (d, w) -> {
            BallWindow.setAutoMode(this, !auto);
            android.widget.Toast.makeText(this,
                    auto ? "已改成常驻：球会一直在"
                         : "已改成自动：退到后台有任务时才出现",
                    android.widget.Toast.LENGTH_LONG).show();
            recreate();
        });
        b.show();
    }

    /**
     * 「工作区与占用」菜单：两件事拼在一张卡里，因为它们都属于
     * "那些不在 App 界面里、但用户会关心"的东西。
     *
     * <h3>为什么要"复制工作区路径"而不是直接打开</h3>
     * 工作区在共享存储（`/sdcard/dsh`），**任何第三方文件管理器本来就能打开它** ——
     * App 侧其实只需要把路径告诉用户。而"App 直接打开一个文件夹"在安卓上做不到：
     *   · `ACTION_VIEW` + FileProvider 只能开**单个文件**，FileProvider 不实现目录列举；
     *   · SAF（`ACTION_OPEN_DOCUMENT_TREE`）对 App 私有目录无效，
     *     而且 Android 11+ 明确禁止通过它选 `Android/data`；
     *   · 把工作区软链到 `Android/data/` 也没用（那里第三方管理器同样进不去）。
     * 所以这里只做"复制路径 + 说清怎么打开"，比堆一个用不了的 Provider 划算。
     */
    private void workspaceMenu() {
        final String ws = "/sdcard/dsh";
        java.io.File base = Env.base(this);
        String procs = ProcCleanup.describe(base);
        String msg = "**工作区路径**（在电脑/文件管理器里就是手机的）\n"
                + "  内部路径：/storage/emulated/0/dsh\n"
                + "  手机上看：内部存储 → dsh 文件夹\n"
                + "  用 MT 管理器 / ES 之类的文件管理器直接进这个目录即可，"
                + "DSH 读写的文件都在里面。\n\n"
                + "**容器相关进程**\n" + procs;
        new android.app.AlertDialog.Builder(this)
                .setTitle("工作区与占用")
                .setMessage(msg)
                .setPositiveButton("复制工作区路径", (d, w) -> {
                    copyText("内部存储/dsh（完整路径 " + ws + "）");
                })
                .setNeutralButton("清理残留进程", (d, w) -> confirmCleanup())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmCleanup() {
        java.io.File base = Env.base(this);
        final java.util.List<ProcLogic.Item> list = ProcCleanup.scan(base);
        if (list.isEmpty()) {
            android.widget.Toast.makeText(this, "没有残留进程，不用清理", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder("会**强制结束**下面这些进程（SIGKILL）：\n\n");
        for (ProcLogic.Item it : list) {
            sb.append("· [").append(it.pid).append("] ").append(it.kind).append('\n');
        }
        sb.append("\n它们都是本 App 自己的容器进程，不是别的应用。\n\n")
          .append("**结束 DSH 会中断正在进行的对话**（会话记录不会丢，下次打开还在）。")
          .append("容器里跑着的虚拟屏应用也会一起没。\n\n")
          .append("什么时候该用：明明没在用 DSH，手机却还烫/还费电；"
                  + "或者容器起不来（端口被占）。");
        new android.app.AlertDialog.Builder(this)
                .setTitle("清理 " + list.size() + " 个残留进程？")
                .setMessage(sb.toString())
                .setPositiveButton("清理", (d, w) -> {
                    int n = ProcCleanup.killAll(base);
                    Env.report(this, "  [清理] 用户手动清理残留容器进程 " + n + " 个\n");
                    android.widget.Toast.makeText(this,
                            "已清理 " + n + " 个进程；回到界面会重新拉起容器",
                            android.widget.Toast.LENGTH_LONG).show();
                    // 监督循环会在 5 秒内发现 dsh 不在了，自己重新起一个
                    DshService.requestRestart();
                    recreate();
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** 当前容器进程数的一句话摘要（列表页上那一行）。 */
    private String procSummary() {
        try {
            int n = ProcCleanup.scan(Env.base(this)).size();
            return n == 0 ? "容器没在跑" : ("容器进程 " + n + " 个");
        } catch (Throwable t) {
            return "容器状态读不到";
        }
    }

    private void copyText(String s) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", s));
            android.widget.Toast.makeText(this, "已复制：" + s, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "复制失败：" + t, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Class<?> cls) {
        try {
            startActivity(new Intent(this, cls));
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "打不开: " + t, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────── 录制屏幕内声音 ───────────────────

    /** 「录制屏幕内声音」那一行（要能改标题/说明，所以留引用）。 */
    private LinearLayout audioRow;

    /** 这一行现在该显示什么。 */
    private String audioHint() {
        if (AudioCapture.isRecording()) {
            java.io.File f = AudioCapture.currentFile();
            return "正在录" + (f == null ? "" : "：" + f.getName())
                    + " · 点这里停止（录到声音需要对方应用允许被录音）";
        }
        return "让 AI 听到手机正在播放的声音（视频/音乐/游戏），录成 WAV 放进工作区；"
                + "系统提示音和通话录不到";
    }

    /** 点一下：没在录就申请投影开始录，正在录就停止。 */
    private void toggleAudioCapture() {
        if (AudioCapture.isRecording()) {
            java.io.File f = AudioCapture.stop();
            long peak = AudioCapture.lastPeak();
            String msg = "已停止录制";
            if (peak == 0) {
                // 峰值 0 = 全程静音。这不是 bug：对方应用可以在自己的播放流上禁止被录制
                msg += "（这段是静音：正在播放的应用不允许被录音）";
            }
            if (f != null) msg += "\n" + f.getAbsolutePath();
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
            refreshAudioRow();
            return;
        }
        open(AudioCaptureActivity.class);
    }

    /** onResume 时刷一次：用户刚过完系统授权框回来，标题/说明要跟着变。 */
    private void refreshAudioRow() {
        if (audioRow == null) return;
        try {
            LinearLayout col = (LinearLayout) audioRow.getChildAt(0);
            android.widget.TextView title = (android.widget.TextView) col.getChildAt(0);
            android.widget.TextView desc = (android.widget.TextView) col.getChildAt(1);
            title.setText(AudioCapture.isRecording() ? "停止录制屏幕内声音" : "录制屏幕内声音");
            desc.setText(audioHint());
        } catch (Throwable ignore) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAudioRow();
    }

    // ─────────────────── 性能体检 ───────────────────

    /**
     * 跑一次体检（**必须放工作线程**：里面要起容器命令，可能几秒）。
     * 结果写进 /sdcard/.dsh-phone/perf.txt，并把一句话结论弹出来。
     */
    private void runPerfCheck() {
        android.widget.Toast.makeText(this, "正在体检（约 1–10 秒）…",
                android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                msg = Perf.snapshot(PhonePermActivity.this);
            } catch (Throwable t) {
                msg = "体检失败：" + t;
            }
            final String out = msg;
            runOnUiThread(() -> {
                android.widget.Toast.makeText(PhonePermActivity.this, out,
                        android.widget.Toast.LENGTH_LONG).show();
                copyText(out);   // 顺手复制，用户直接粘贴发出来
            });
        }, "dsh-perf").start();
    }
}
