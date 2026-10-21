package com.dshmobile.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「容器终端」：在手机上直接敲容器里的命令。
 *
 * <h3>为什么用户会想要它</h3>
 * 这个 App 的价值全在容器里（Node、DSH 内核、插件、工作区文件），
 * 但以前想看容器里到底什么样，只有两条路：DSH 里让 agent 跑命令（要消耗对话、还要模型配合），
 * 或者插电脑 adb 进去。前者绕、后者要电脑 —— 所以给一个自己的终端。
 *
 * <h3>它是怎么跑的（重要，别再踩坑）</h3>
 * 每个命令都是**新起一个 proot 会话**（{@code Env.execInContainer}），以 root 身份、
 * 工作目录 /root、{@code bash -lc} 执行 —— 也就是说它和正在跑的 DSH **不是同一个进程**，
 * 只是共享同一份 rootfs。因此：
 * <ul>
 *   <li>读类命令（ls/cat/ps/df/node -v）随便跑，互不影响；</li>
 *   <li>**写类命令要当心**：`npm install`、`dsh plugin add` 这类会同时改容器文件的操作，
 *       和正在运行的 DSH 抢同一份文件，建议先停掉 DSH（App 里"停止"）再跑；</li>
 *   <li>命令有超时上限（默认 60 秒），到点会被杀掉并把已产生的输出留下。</li>
 * </ul>
 *
 * <h3>为什么界面这么朴素</h3>
 * 终端这东西的体验就在于"输出不被 UI 抢走"：所以输出区**单独滚动**、自动贴底、
 * 可长按复制；输入区固定一行带「执行」；上面一排常用命令省得每次手打。
 * 刻意不做 ANSI 颜色、不做交互式程序（vi/top 这类需要 TTY，这里给不了）。
 */
public class TerminalActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> history = new ArrayList<>();

    private TextView out;
    private ScrollView outScroll;
    private EditText input;
    private int historyPos = -1;
    private boolean busy = false;

    /** 命令超时：给足 npm 之类慢命令的时间，又不至于卡死界面。 */
    private static final long TIMEOUT_MS = 60_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        LinearLayout root = Ui.page(this, "容器终端", v -> finish());

        // ── 说明 ──
        root.addView(Ui.card(this, null,
                "命令在容器里以 root 身份执行（工作目录 /root），和 DSH 用的是同一份 rootfs。\n"
                        + "· 只读命令随便跑；会改容器的命令（npm install / dsh plugin add）建议先停掉 DSH 再跑，\n"
                        + "  否则两边同时改同一份文件；\n"
                        + "· 每条命令最多跑 " + (TIMEOUT_MS / 1000) + " 秒，到点会被杀掉（已产生的输出还会留着）；\n"
                        + "· 不支持交互式程序（vi / top 这类需要终端的），要退出请按返回键。"));

        // ── 输出区 ──
        LinearLayout cOut = Ui.card(this, "输出", null);
        out = new TextView(this);
        out.setTextSize(11.5f);
        out.setTextColor(Ui.TEXT);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setLineSpacing(Ui.dp(this, 2), 1f);
        out.setText("（容器还没起来的话，第一条命令会等它；起不来会返回空输出）\n");
        outScroll = new ScrollView(this);
        outScroll.addView(out);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 320));
        outScroll.setLayoutParams(olp);
        outScroll.setBackground(Ui.inputBg(this));
        int p = Ui.dp(this, 10);
        outScroll.setPadding(p, p, p, p);
        cOut.addView(outScroll);
        cOut.addView(Ui.button(this, "复制输出", false, v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", out.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }));
        cOut.addView(Ui.button(this, "清屏", false, v -> out.setText("")));
        root.addView(cOut);

        // ── 输入 ──
        LinearLayout cIn = Ui.card(this, "命令", null);
        input = new EditText(this);
        input.setHint("例如：ls -la /root");
        input.setTextSize(13f);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.SUB);
        input.setBackground(Ui.inputBg(this));      // 底色随主题（原来吃系统默认下划线）
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { run(input.getText().toString()); return true; }
            return false;
        });
        cIn.addView(input);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = Ui.dp(this, 8);
        TextView go = Ui.button(this, "执行", true, v -> run(input.getText().toString()));
        go.setLayoutParams(blp);
        btns.addView(go);
        TextView prev = Ui.button(this, "上一条", false, v -> {
            if (history.isEmpty()) return;
            historyPos = Math.max(0, Math.min(history.size() - 1, historyPos < 0 ? history.size() - 1 : historyPos - 1));
            input.setText(history.get(historyPos));
            input.setSelection(input.getText().length());
        });
        prev.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(prev);
        cIn.addView(btns);
        root.addView(cIn);

        // ── 常用命令 ──
        LinearLayout cQ = Ui.card(this, "常用", null);
        for (String[] q : QUICK) {
            cQ.addView(Ui.row(this, q[0], q[1], v -> {
                input.setText(q[2]);
                input.setSelection(input.getText().length());
                run(q[2]);
            }));
        }
        root.addView(cQ);
    }

    /** 一排"点一下就懂"的常用命令：名字 / 说明 / 实际命令。 */
    private static final String[][] QUICK = {
            {"容器版本", "node 与 DSH 内核版本", "node -v && dsh --version"},
            {"工作目录", "容器里 /root 有什么", "ls -la /root"},
            {"系统信息", "发行版、内核、内存、磁盘", "uname -a; free -m; df -h /"},
            {"DSH 进程", "dsh 是否在跑、占了什么端口", "ps -A -o PID,ARGS | grep -v grep | grep -i dsh | head -10"},
            {"已装插件", "profile 里装了哪些插件", "ls /root/.dsh/profiles/web/node_modules 2>/dev/null | head -30"},
            {"网络自检", "容器能不能连出去", "curl -s -m 8 -o /dev/null -w '%{http_code}\\n' https://registry.npmjs.org/ || echo 连不上"},
    };

    // ── 执行 ──────────────────────────────────────────────────

    private void run(String raw) {
        final String cmd = raw == null ? "" : raw.trim();
        if (cmd.isEmpty()) return;
        if (busy) { Toast.makeText(this, "上一条还在跑，等一下", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        history.add(cmd);
        historyPos = -1;
        input.setText("");

        append("$ " + cmd + "\n");
        final StringBuilder buf = new StringBuilder();
        io.execute(() -> {
            try {
                /*
                 * onLine 回调发生在**读线程**上，不能碰 UI —— 所以先攒进 buffer，
                 * 再按行 post 到主线程（每行一次 post，命令输出多时也不会卡）。
                 */
                String all = Env.execInContainer(this, cmd, TIMEOUT_MS, line -> {
                    synchronized (buf) { buf.append(line).append('\n'); }
                    final String l = line;
                    ui.post(() -> append(l + "\n"));
                });
                final String tail;
                if (all == null) tail = "\n[命令超时或容器没起来 —— 已产生的输出在上面]\n";
                else if (all.isEmpty()) tail = "[没有输出]\n";
                else tail = "[结束]\n";
                ui.post(() -> { append(tail); busy = false; });
            } catch (Throwable t) {
                ui.post(() -> { append("[失败] " + t + "\n"); busy = false; });
            }
        });
    }

    /** 追加输出并贴到底部（不贴底的话用户要自己往下拉，体验很差）。 */
    private void append(String s) {
        out.append(s);
        outScroll.post(() -> outScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
