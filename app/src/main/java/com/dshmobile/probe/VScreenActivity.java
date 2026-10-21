package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「虚拟屏自动化」页：一件一件把群友「如月雨露」的受信任虚拟屏方案装起来。
 *
 * <h3>为什么要有这个页面（而不是塞进别的页）</h3>
 * 这套东西的"装"不是复制文件那么简单：dex 必须以 **shell 身份**放到
 * {@code /data/local/tmp} 并拉起来，普通 App 做不到，所以要借 Shizuku（或让用户跑一次 adb）。
 * 而"装完到底成没成"又只能从 HTTP 接口回查。这么多步骤必须有地方说清楚、
 * 有按钮点、失败时能看到原文 —— 所以单独一页。
 *
 * <h3>页面上为什么这么排</h3>
 * 从上到下就是用户实际会走的路：先看能不能装（Shizuku 状态）→ 一键装 → 看结果 →
 * 没有 Shizuku 就抄命令 → 最后才是"这是什么、有什么风险"。
 */
public class VScreenActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        LinearLayout root = Ui.page(this, "虚拟屏自动化", v -> finish());

        // ── 状态卡 ──
        LinearLayout cStatus = Ui.card(this, "当前状态", null);
        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTextColor(Ui.SUB);
        statusView.setLineSpacing(Ui.dp(this, 3), 1f);
        statusView.setText("正在查询…");
        cStatus.addView(statusView);
        cStatus.addView(Ui.button(this, "重新查询", false, v -> refresh()));
        root.addView(cStatus);

        // ── 一键装 ──
        LinearLayout cInstall = Ui.card(this, "安装 / 启动",
                "把随 App 自带的那两个守护装到手机上并拉起来。\n"
                        + "· 走 Shizuku（推荐）：它已经是 shell 身份，点一下就好；\n"
                        + "· 没有 Shizuku：下面「没有 Shizuku？」里有现成的 adb 命令，插一次线跑一遍即可。\n"
                        + "· 手机重启后要重新点一次 —— 守护的命绑在 adb 调试通道上，这是方案的固有边界。");
        cInstall.addView(Ui.button(this, "一键安装并启动", true, v -> doInstall()));
        cInstall.addView(Ui.button(this, "只重启虚拟屏守护", false, v -> doRestart()));
        cInstall.addView(Ui.button(this, "停止全部守护", false, v -> doStop()));
        root.addView(cInstall);

        // ── 身份选择（root / Shizuku）──
        // 用户要求"加入 root 启动选项"。有 root 的话它比 Shizuku 强在两点：
        //   ① 不受 adbd 生死影响（adb 一重启 Shizuku 那条路的守护就全灭）；
        //   ② Magisk 这类能开机自启 → 重启手机也不用管，才是真正的"永久授权"。
        root.addView(buildIdentityCard());

        // ── 执行输出 ──
        LinearLayout cLog = Ui.card(this, "执行输出", "守护自己打的启动日志也在这里，失败时把它发给开发者。");
        logView = new TextView(this);
        logView.setTextSize(11.5f);
        logView.setTextColor(Ui.SUB);
        logView.setLineSpacing(Ui.dp(this, 2), 1f);
        logView.setTextIsSelectable(true);
        logView.setText("（还没执行过）");
        cLog.addView(logView);
        cLog.addView(Ui.button(this, "复制输出", false, v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("vscreen", logView.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }));
        root.addView(cLog);

        // ── 没有 Shizuku？ ──
        LinearLayout cAdb = Ui.card(this, "没有 Shizuku？用 adb 跑一次",
                "把手机插上电脑、打开 USB 调试，然后在电脑上执行下面这些命令。\n"
                        + "两个 dex 在本机 APK 里：解压 APK 的 assets/vsd/ 就是。");
        cAdb.addView(Ui.button(this, "查看 / 复制 adb 命令", false, v -> showAdb()));
        root.addView(cAdb);

        // ── 这是什么 ──
        LinearLayout cAbout = Ui.card(this, "这是什么",
                "「受信任虚拟屏」= 用 shell 身份建一块系统认可的虚拟屏，应用放进 freeform 窗口容器里\n"
                        + "后台运行（不跳屏、不抢前台），取画面走本地 HTTP（毫秒级），操作走 input -d 坐标。\n"
                        + "DSH 里因此可以：后台开应用、截虚拟屏的画面、按坐标点按、读画面里的文字。\n\n"
                        + "方案与两个守护由群友「如月雨露」制作，按原样随 App 分发；本 App 只做三件事：\n"
                        + "① 把 dex 自带进安装包；② 借 Shizuku 以 shell 身份装好并拉起；③ 提供这页开关与状态。\n\n"
                        + "安全：原方案里的守护是「绑所有网卡 + 零鉴权」、token/dex 放共享存储。\n"
                        + "随 App 分发的这一份已经改成：只绑回环 127.0.0.1 + 每个请求校验 X-Token，\n"
                        + "token 与 dex 都放在 /data/local/tmp/dsh-vsd 且仅 shell 可读（0600）。");
        root.addView(cAbout);

        // ── 风险 ──
        LinearLayout cRisk = Ui.card(this, "已知边界",
                "· 守护的命绑 adb 调试通道：关掉所选调试开关、或重启手机 → 守护 ~3 秒内全灭，重装一次即可；\n"
                        + "· 停止守护会连带杀掉虚拟屏上跑着的应用（系统行为，不是 bug）；\n"
                        + "· 命令端点（:18242）等于「常驻在手机上的 adb shell」，能执行系统命令 —— \n"
                        + "  不要把它暴露给不可信的人；token 泄露等于交出 shell；\n"
                        + "· 本页不修改任何系统设置，也不会自动重启你的应用。");
        root.addView(cRisk);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ── 动作 ──────────────────────────────────────────────────

    private void refresh() {
        statusView.setText("正在查询…");
        io.execute(() -> {
            final String s = VScreen.statusText(this);
            ui.post(() -> statusView.setText(s));
        });
    }

    /**
     * 开始一次需要特权身份的操作前的统一闸门。
     *
     * <p>身份可以是 **root**（用户开了开关且 su 可用）或 **Shizuku**（原路径）。
     * 两条都没有时才去走 Shizuku 的引导对话框 —— 以前这里硬判 Shizuku，
     * 于是开了 root 的用户也会被拉去装/授权 Shizuku，是错的。
     */
    private boolean ensureBackend(String what) {
        if ("none".equals(ShizukuBridge.privilegedBackend(this))) {
            ShizukuBridge.showGrantDialog(this, this::refresh);
            return false;
        }
        return true;
    }

    private void doInstall() {
        if (!ensureBackend("一键安装并启动")) return;
        run("一键安装并启动", () -> VScreen.installAndStart(this));
    }

    private void doRestart() {
        if (!ensureBackend("重启虚拟屏守护")) return;
        run("重启虚拟屏守护", () -> VScreen.restartVsd(this));
    }

    /**
     * 「拉起守护的身份」卡片 —— 用户要求"加入 root 启动选项"。
     *
     * <p>为什么值得单独一张卡：身份决定**守护能活多久** ——
     * Shizuku（uid 2000）的命绑在 adbd 的 cgroup 上，adb 服务重启/关掉调试开关/
     * 重启手机都会让守护几秒内被 SIGKILL；root 不受这条约束，而且 Magisk 这类能开机自启，
     * 所以它才是"永久授权"的正解。但没 root 的手机上探测 su 会弹授权框，
     * 所以这个开关**默认关**，只在用户主动打开时才探一次。
     */
    private LinearLayout buildIdentityCard() {
        final boolean prefer = ShizukuBridge.preferRoot(this);
        boolean rootKnown = prefer && ShizukuBridge.rootAvailable(false);
        LinearLayout card = Ui.card(this, "拉起守护的身份",
                "决定守护能活多久：\n"
                + "· Shizuku（shell）：不用 root，但守护的命绑在 adb 调试通道上 —— "
                + "adb 一重启、关掉调试开关、或手机重启，守护几秒内就没；\n"
                + "· root（su）：不受 adb 生死影响，而且 Magisk 这类能开机自启 —— "
                + "重启手机也不用管，这才叫永久授权。\n\n"
                + "当前：" + (prefer
                        ? (rootKnown ? "优先用 root" : "已选 root，但 su 不可用（会回落到 Shizuku）")
                        : "用 Shizuku（" + ShizukuBridge.statusText(this) + "）"));

        card.addView(Ui.button(this, prefer ? "关掉 root，改用 Shizuku" : "试试用 root 拉起", true, v -> {
            if (prefer) {
                ShizukuBridge.setPreferRoot(this, false);
                Toast.makeText(this, "已改为用 Shizuku", Toast.LENGTH_SHORT).show();
                recreate();
                return;
            }
            // 先讲清楚"马上会弹 root 授权框"，否则用户会以为弹窗是流氓行为
            new android.app.AlertDialog.Builder(this)
                    .setTitle("用 root 拉起守护")
                    .setMessage("接下来会执行 `su -c id` 探测一次 root。\n\n"
                            + "如果你装了 Magisk 之类的授权管理，会弹一个授权框，"
                            + "请选「允许」（通常会记住选择）。\n\n"
                            + "为什么值得：root 身份起的守护不受 adb 调试通道生死影响，"
                            + "而且能开机自启 —— 就不用每次重启手机都手动点一次了。\n\n"
                            + "没有 root 的手机上这一步会失败，什么都不会改，仍然用 Shizuku。")
                    .setPositiveButton("探测", (d, w) -> {
                        new Thread(() -> {
                            final boolean ok = ShizukuBridge.rootAvailable(true);
                            runOnUiThread(() -> {
                                ShizukuBridge.setPreferRoot(this, ok);
                                Toast.makeText(this, ok
                                        ? "root 可用，之后都用 root 拉起守护"
                                        : "没有拿到 root（su 不可用或被拒绝），继续用 Shizuku",
                                        Toast.LENGTH_LONG).show();
                                recreate();
                            });
                        }, "root-probe").start();
                    })
                    .setNegativeButton("算了", null)
                    .show();
        }));
        return card;
    }

    private void doStop() {
        if (!ensureBackend("停止守护")) return;
        new AlertDialog.Builder(this)
                .setTitle("停止全部守护？")
                .setMessage("守护一停，虚拟屏上正在跑的应用会被系统一起收掉，之后要重新「一键安装并启动」。")
                .setPositiveButton("停", (d, w) -> run("停止守护", () -> VScreen.stop(this)))
                .setNegativeButton("算了", null)
                .show();
    }

    /** 统一：后台跑、把原文贴到输出框、结束再查一次状态。 */
    private void run(String what, Call task) {
        logView.setText("【" + what + "】执行中…\n");
        io.execute(() -> {
            String out;
            try {
                out = task.run();
            } catch (Throwable t) {
                out = "执行失败：" + t;
            }
            final String text = "【" + what + "】\n" + (out == null || out.isEmpty() ? "（没有输出）" : out);
            ui.post(() -> {
                logView.setText(text);
                refresh();
            });
        });
    }

    private interface Call {
        String run() throws Exception;
    }

    private void showAdb() {
        final String cmds = VScreen.adbCommands(this);
        new AlertDialog.Builder(this)
                .setTitle("adb 命令")
                .setMessage("在电脑上依次执行（dex 从 APK 的 assets/vsd/ 里解出来）：\n\n" + cmds)
                .setPositiveButton("复制全部", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("vscreen-adb", cmds));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
