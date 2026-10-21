package com.dshmobile.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「中转站」页：把中转站 / 自部署网关 / Claude 原生接口接进 DSH。
 *
 * <h3>这一页解决什么</h3>
 * DSH 只在**设置里出现 {@code llm-pi-ai:}` 这一节**时才注册自定义路由
 * （见 {@link Providers} 的类注释，那是逐行查过内核源码的）。手机上改这个文件很不方便：
 * 要进容器、要手写 YAML、还得自己派生凭据名。这一页把整件事变成"填几个框"。
 *
 * <h3>两条承诺（也是页面上写着的）</h3>
 * <ul>
 *   <li><b>密钥只存本机</b>：写进 DSH 的只有 {@code apiKeyEnv: XXX_API_KEY} 这种引用，
 *       密钥留在 App 私有目录，启动容器时作为环境变量注入；</li>
 *   <li><b>不碰别人的设置</b>：只改写 settings.yaml 里的 {@code llm-pi-ai} 一节，
 *       DSH 网页端自己加的提供方原样保留（{@link Providers#mergeSection}）。</li>
 * </ul>
 *
 * <p>界面分两态（列表 / 编辑），靠一个字段切换、不另开 Activity ——
 * 手机上返回键的语义更简单：编辑态按返回 = 取消编辑。
 *
 * <p><b>界面灵感来自「小眼睛」</b>（用户要求在这里标注）。
 */
public class RelayActivity extends Activity {

    private LinearLayout root;
    private boolean editing;
    private Relay.Route draft;
    private boolean creating;
    private TextView statusLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        root = Ui.page(this, "中转站", v -> onBackPressed());
        render();
    }

    @Override
    public void onBackPressed() {
        if (editing) {
            editing = false;
            draft = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    // ─────────────────────────── 列表态 ───────────────────────────

    private void render() {
        root.removeAllViews();
        if (editing) renderEditor();
        else renderList();
    }

    private void renderList() {
        root.addView(Ui.card(this, null,
                "连接中转站、自部署服务或其他兼容 OpenAI / Anthropic 协议的接口。"
                + "保存后模型会出现在 DSH 的模型选择器里，选哪个用哪个。"
                + "密钥只存本机（App 私有目录），经环境变量注入容器，不写进配置文件。"));

        // 官方 API 放在最上面：它和下面的自定义提供方是**两套东西**，
        // 以前散在两个页面里，用户看不出"我到底在用哪家"（见 Relay 里的说明）。
        root.addView(buildOfficialCard());

        List<Relay.Route> list = Providers.load(this);
        for (Relay.Route r : list) {
            LinearLayout card = Ui.card(this, null, null);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t = new TextView(this);
            t.setText(r.name == null || r.name.trim().isEmpty() ? r.id : r.name);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(16);
            col.addView(t);
            TextView d = new TextView(this);
            d.setText(r.apiLabel() + " · " + r.models.size() + " 个模型");
            d.setTextColor(Ui.SUB);
            d.setTextSize(12);
            d.setPadding(0, Ui.dp(this, 3), 0, 0);
            col.addView(d);
            TextView addr = new TextView(this);
            addr.setText(r.baseUrl == null ? "" : r.baseUrl);
            addr.setTextColor(Ui.SUB);
            addr.setTextSize(12);
            addr.setPadding(0, Ui.dp(this, 2), 0, 0);
            col.addView(addr);
            head.addView(col);
            card.addView(head);

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView edit = Ui.button(this, "编辑", false, v -> {
                draft = r;
                creating = false;
                editing = true;
                render();
            });
            edit.setLayoutParams(btnLp());
            btns.addView(edit);
            TextView del = Ui.button(this, "删除", false, v -> confirmDelete(r));
            del.setLayoutParams(btnLp());
            btns.addView(del);
            card.addView(btns);
            root.addView(card);
        }

        if (list.isEmpty()) {
            root.addView(Ui.card(this, "还没有添加任何提供方",
                    "点下面的按钮加一个。需要的信息在你买的中转站/网关的「接入说明」里都有："
                    + "接口地址（填到 …/v1）、一个密钥、以及要用的模型名。"));
        }

        TextView add = Ui.button(this, "+ 添加自定义提供商", true, v -> {
            draft = new Relay.Route();
            draft.api = Relay.API_OPENAI_CHAT;
            creating = true;
            editing = true;
            render();
        });
        root.addView(add);

        root.addView(Ui.card(this, "协议说明",
                "OpenAI Chat Completions：适用于绝大多数中转站。\n"
                + "OpenAI Responses：适用于只提供 /v1/responses 的新接口。\n"
                + "Anthropic Messages：适用于 Claude 系原生接口。\n"
                + "接口路径由 DSH 按协议自动拼接，API 地址填到 …/v1 即可。\n"
                + "模型目录里标「文本+图像」的模型才能收图片；不认识的先去掉，"
                + "在对话里直接用模型 ID 也能发（不用非在这里登记）。"));

        /*
         * 生效方式：DSH 是在启动时读设置的，改完必须重启它。
         * 这里给一个一键按钮 —— 否则用户会以为"保存了但没用"。
         */
        TextView restart = Ui.button(this, "重启 DSH 让它生效", false, v -> {
            DshService.requestRestart();
            Toast.makeText(this, "正在重启 DSH，界面会自己回来", Toast.LENGTH_LONG).show();
            appendReport("  [中转站] 用户手动重启 DSH 以加载新的提供方\n");
        });
        root.addView(restart);

        statusLine = Ui.status(this, "", Ui.SUB);
        root.addView(statusLine);

        // 用户要求的署名
        TextView credit = Ui.status(this, "界面灵感来自「小眼睛」", 0xFF6B7076);
        credit.setGravity(Gravity.END);
        root.addView(credit);
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, 8);
        return lp;
    }

    private void confirmDelete(final Relay.Route r) {
        new AlertDialog.Builder(this)
                .setTitle("删除这个提供方？")
                .setMessage((r.name == null || r.name.isEmpty() ? r.id : r.name)
                        + "\n\n会从 DSH 的设置里移除它（模型选择器里不再出现），"
                        + "并忘掉存在本机的密钥。已用它聊过的会话不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    Providers.remove(this, r.id);
                    String msg = Providers.push(this);
                    toast(msg);
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ─────────────────────────── 编辑态 ───────────────────────────

    private void renderEditor() {
        final Relay.Route r = draft;

        root.addView(Ui.card(this, creating ? "新建自定义提供商" : "编辑自定义提供商",
                "连接中转站、自部署服务或其他兼容 OpenAI / Anthropic 协议的接口，需填写 API 地址、协议和模型。"));

        // Provider ID
        if (creating) {
            root.addView(field("Provider ID", r.id, "以小写字母开头的标识，在请求中唯一标识该提供商，"
                    + "并用于派生凭据名（如 ACME_GATEWAY_API_KEY）", false, v -> r.id = v.trim()));
        } else {
            root.addView(Ui.card(this, "Provider ID", r.id
                    + "\n\n（已经保存过的提供方不能改名：改名等于换一个提供方，"
                    + "密钥与模型都要重填。要换就删掉重建。）"));
        }

        root.addView(field("显示名称", r.name, "可选，只用于界面展示", false, v -> r.name = v.trim()));
        root.addView(field("API 地址", r.baseUrl, "填到 …/v1 就行（例如 https://gateway.example/v1）",
                false, v -> r.baseUrl = v.trim()));

        // API 协议
        LinearLayout apiCard = Ui.card(this, "API 协议", null);
        root.addView(apiCard);
        apiCard.addView(choice("OpenAI Chat Completions", "绝大多数中转站用这个",
                Relay.API_OPENAI_CHAT.equals(r.api), () -> {
                    r.api = Relay.API_OPENAI_CHAT; render();
                }));
        apiCard.addView(choice("OpenAI Responses", "只提供 /v1/responses 的新接口",
                Relay.API_OPENAI_RESP.equals(r.api), () -> {
                    r.api = Relay.API_OPENAI_RESP; render();
                }));
        apiCard.addView(choice("Anthropic Messages", "Claude 系原生接口",
                Relay.API_ANTHROPIC.equals(r.api), () -> {
                    r.api = Relay.API_ANTHROPIC; render();
                }));

        // 重试策略（用户要求："自定义连不上重试次数"）
        root.addView(buildRetryCard(r));

        // 密钥
        root.addView(field("API 密钥", Providers.key(this, r.id),
                "只存本机（App 私有目录），经环境变量注入容器，不写进任何配置文件；"
                + "自部署、免鉴权的服务可以留空", true, v -> Providers.setKey(this, r.id, v.trim())));

        // 模型目录
        LinearLayout models = Ui.card(this, "模型目录",
                "目录为空无法保存；模型选择器里没列出的 ID 也可以在对话里直接发。");
        root.addView(models);
        for (final Relay.Model m : new ArrayList<>(r.models)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            TextView id = new TextView(this);
            id.setText(m.id);
            id.setTextColor(Ui.TEXT);
            id.setTextSize(15);
            id.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(id);
            TextView tag = Ui.button(this, m.image ? "文本+图像" : "纯文本", false, v -> {
                m.image = !m.image;
                render();
            });
            tag.setLayoutParams(wrapLp());
            row.addView(tag);
            TextView del = Ui.button(this, "✕", false, v -> {
                r.models.remove(m);
                render();
            });
            del.setLayoutParams(wrapLp());
            row.addView(del);
            models.addView(row);
        }
        if (r.models.isEmpty()) {
            models.addView(Ui.status(this, "目录为空 —— 获取可用模型或手动添加", Ui.SUB));
        }

        // 手动添加模型
        final EditText manual = new EditText(this);
        manual.setHint("手动添加模型 ID");
        manual.setTextSize(15);
        manual.setTextColor(Ui.TEXT);
        manual.setHintTextColor(Ui.SUB);
        manual.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manual.setBackground(Ui.inputBg(this));
        models.addView(manual);
        models.addView(Ui.button(this, "+ 添加", false, v -> {
            String id = manual.getText().toString().trim();
            if (id.isEmpty()) return;
            for (Relay.Model m : r.models) if (m.id.equals(id)) return;
            r.models.add(new Relay.Model(id, id, false));
            render();
        }));

        final TextView discoverState = Ui.status(this, "", Ui.SUB);
        models.addView(Ui.button(this, "↻ 获取可用模型", false, v -> {
            if (r.baseUrl == null || r.baseUrl.trim().isEmpty()) {
                discoverState.setText("先填 API 地址");
                return;
            }
            if (r.id == null || r.id.trim().isEmpty()) {
                discoverState.setText("先填 Provider ID（发现模型要用它派生凭据名）");
                return;
            }
            discoverState.setText("正在问这个服务有哪些模型…");
            final String key = Providers.key(this, r.id);
            new Thread(() -> {
                String err = "";
                List<Relay.Model> got = new ArrayList<>();
                try {
                    got = Providers.discover(r, key);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final List<Relay.Model> found = got;
                final String error = err;
                runOnUiThread(() -> {
                    if (!error.isEmpty()) {
                        discoverState.setText("获取失败：" + error);
                        appendReport("  [中转站] 获取模型失败（" + r.id + "）：" + error + "\n");
                        return;
                    }
                    LinkedHashSet<String> have = new LinkedHashSet<>();
                    for (Relay.Model m : r.models) have.add(m.id);
                    int added = 0;
                    for (Relay.Model m : found) {
                        if (have.add(m.id)) { r.models.add(m); added++; }
                    }
                    discoverState.setText("发现 " + found.size() + " 个模型，新加入 " + added
                            + " 个（不认识的可先去掉，支持图像的记得点亮「文本+图像」）");
                    appendReport("  [中转站] " + r.id + " 发现 " + found.size()
                            + " 个模型，新加入 " + added + " 个\n");
                    render();
                });
            }, "relay-discover").start();
        }));
        models.addView(discoverState);

        // 保存 / 取消
        root.addView(Ui.button(this, creating ? "创建提供商" : "保存修改", true, v -> save(r)));
        root.addView(Ui.button(this, "取消", false, v -> {
            editing = false;
            draft = null;
            render();
        }));
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        lp.topMargin = 0;
        return lp;
    }

    /** 保存：先本地校验，再写进容器的 settings.yaml（写入有备份/校验/回滚，见 Providers）。 */
    private void save(Relay.Route r) {
        String idErr = Relay.validateId(r.id);
        if (!idErr.isEmpty()) { toast(idErr); return; }
        if (r.baseUrl == null || r.baseUrl.trim().isEmpty()) { toast("API 地址不能为空"); return; }
        if (!r.baseUrl.startsWith("http://") && !r.baseUrl.startsWith("https://")) {
            toast("API 地址要以 http:// 或 https:// 开头");
            return;
        }
        if (r.models.isEmpty()) { toast("模型目录不能为空 —— 先「获取可用模型」或手动加一个"); return; }
        if (r.name == null) r.name = "";

        Providers.upsert(this, r);
        String msg = Providers.push(this);
        appendReport("  [中转站] 保存 " + r.id + "（" + r.models.size() + " 个模型）：" + msg + "\n");
        editing = false;
        draft = null;
        render();
        toast(msg);
    }

    private View field(String title, String value, String hint, boolean secret, final FieldCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        box.setBackground(Ui.round(Ui.CARD, Ui.STROKE, 16, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        box.setLayoutParams(lp);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Ui.TEXT);
        label.setTextSize(15);
        box.addView(label);
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(15);
        et.setTextColor(Ui.TEXT);
        et.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setBackground(Ui.inputBg(this));
        box.addView(et);
        et.setOnFocusChangeListener((v, has) -> { if (!has) cb.on(et.getText().toString()); });
        // 编辑态里也可能直接点"保存"而输入框还没失焦，所以再兜一层
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { cb.on(s.toString()); }
        });
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextColor(Ui.SUB);
            h.setTextSize(12);
            h.setPadding(0, Ui.dp(this, 6), 0, 0);
            box.addView(h);
        }
        return box;
    }

    /**
     * 「官方 API」卡片 —— 放在中转站页最上面，让"我到底在用哪家"一眼可见。
     *
     * <p>它解决的是用户那条反馈：「修一下没配置官方 api 联网搜索时会爆红」。
     * 官方的 provider 名是 {@code deepseek-official}（DSH 自带适配器），
     * 密钥走环境变量 {@code DEEPSEEK_API_KEY} —— 也就是这张卡里填的那个。
     * 以前它藏在设置页的一个对话框里，用户在"中转站"页配了一堆自定义提供方之后，
     * 完全看不出官方那家还是空的。
     */
    private View buildOfficialCard() {
        final String key = DshService.getApiKey(this);
        boolean hasKey = key != null && !key.trim().isEmpty();
        LinearLayout card = Ui.card(this, "官方 API（DeepSeek）",
                "DSH 默认就用这一家。密钥只存本机，经环境变量 DEEPSEEK_API_KEY 注入容器。\n"
                + "状态：" + (hasKey
                        ? ("已配置（" + maskKey(key) + "）")
                        : "**还没配置** —— 联网搜索、默认模型都会失败（界面上表现为爆红）")
                + "\n" + ApiKey.describe(key));

        final TextView state = Ui.status(this, "", Ui.SUB);

        card.addView(field("官方 API Key", hasKey ? key : "",
                "在 platform.deepseek.com 申请，形如 sk-…；留空 = 不用官方（只用下面自定义的提供方）\n"
                + "粘贴进来会自动清掉多余的空格、换行、零宽字符和全角符号（这些是"
                + "「明明填了却说 Key 不对」的最常见原因）",
                true, v -> {
                    /*
                     * ⚠️ 这里**必须走 ApiKey.sanitize**，不能只 trim()。
                     *
                     * trim() 只裁 <= 0x20 的字符；而复制粘贴最常见的污染物
                     * （\u200B 零宽空格、\uFEFF BOM、\u3000 全角空格、全角连字符）
                     * 一个都裁不掉。带着它们发出去，服务端只会回"invalid api key"，
                     * 用户完全看不出问题在哪 —— 这正是上一次发的那个 bug。
                     */
                    String clean = ApiKey.sanitize(v);
                    DshService.setApiKey(this, clean);
                    if (clean.isEmpty()) {
                        state.setText("已清空官方 Key —— 点下面的按钮重启 DSH 后才生效");
                    } else if (!ApiKey.plausible(clean)) {
                        state.setText("已记录，但**形态不像**官方 Key（应以 sk- 开头、"
                                + "后面只有字母数字）。当前：" + ApiKey.describe(v));
                    } else {
                        state.setText("已记录：" + ApiKey.describe(v)
                                + " —— 点下面的按钮重启 DSH 后才生效");
                    }
                }));

        card.addView(Ui.button(this, "测试官方 API 能不能连上", true, v -> {
            final String k = DshService.getApiKey(this);
            if (k == null || k.trim().isEmpty()) {
                state.setText("先在上面填一个 Key 再测。");
                return;
            }
            state.setText("正在连 " + Relay.OFFICIAL_BASE + " …");
            new Thread(() -> {
                String msg;
                try {
                    msg = "✅ " + Providers.testOfficial(k.trim());
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    // Providers 抛出来的已经是人话（见 Relay.explainOfficialError）
                    msg = "❌ " + m;
                }
                final String out = msg;
                runOnUiThread(() -> {
                    state.setText(out);
                    appendReport("  [官方API] 测试：" + out + "\n");
                });
            }, "official-api-test").start();
        }));
        card.addView(state);

        card.addView(Ui.button(this, "保存并重启 DSH 让它生效", false, v -> {
            DshService.requestRestart();
            toast("正在重启 DSH，界面会自己回来");
        }));
        return card;
    }

    /** 密钥只露头尾，中间打码（截图/发日志时不至于把密钥带出去）。 */
    private static String maskKey(String k) {
        String s = k == null ? "" : k.trim();
        if (s.length() <= 10) return "已设置";
        return s.substring(0, 7) + "…" + s.substring(s.length() - 4);
    }

    /**
     * 「重试策略」卡片 —— 用户要求"自定义连不上重试次数"。
     *
     * <p>依据（读的是容器里真装的 pi-ai 包，不是猜的）：
     * {@code @deepseek-ai/dsh-llm-pi-ai} 的 README 写明
     * 「每个 profile 都可以设置 {@code retryPolicy}；省略时使用 normal mode、最多重试五次」，
     * 类型定义在 {@code @deepseek-ai/dsh-llm/retry-policy.d.ts}：
     * <pre>
     *   normal: { mode: normal, maxRetries?: number, backoff?: { initialDelayMs?, maxDelayMs?, jitterRatio? } }
     *   always: { mode: always, backoff?: {...} }        // 一直重到成功/取消（没有 maxRetries）
     * </pre>
     *
     * <p>为什么做成"点按钮选"而不是输入框：这几个值是离散的小集合，
     * 手机上打字容易输错（maxRetries 写成 1e3 之类会让整个配置被 schema 拒掉，
     * 而 DSH 的报错原文用户根本看不懂）。点一下就存，零输入错误。
     *
     * <p>为什么默认一个字都不写：不写 = 跟随 DSH 默认（normal / 5 次），
     * 也就是"和升级前完全一样"。只有用户真的点了才写进 YAML —— 少改就少出错。
     */
    private View buildRetryCard(final Relay.Route r) {
        LinearLayout card = Ui.card(this, "重试策略",
                "连不上/服务端临时出错时，DSH 会按这里的策略重试。\n"
                + "不选 = 跟随 DSH 默认（normal，最多 5 次）。\n"
                + "状态：" + r.retryLabel());

        // 模式
        card.addView(choice("跟随默认（normal，5 次）", "什么都不写，和以前完全一样",
                !r.hasRetry() || r.retryMode == null || r.retryMode.isEmpty(), () -> {
                    r.retryMode = ""; r.retryMax = -1; r.retryDelayMs = -1; r.retryMaxDelayMs = -1;
                    render();
                }));
        card.addView(choice("normal（只重试可恢复的错误）", "限流、超时、5xx 这类会重试；参数写错不会",
                "normal".equals(r.retryMode), () -> {
                    r.retryMode = "normal";
                    if (r.retryMax < 0) r.retryMax = 5;
                    render();
                }));
        card.addView(choice("always（一直重试到成功）", "任何失败都重试，直到成功或被取消 —— 断网很久时会一直等",
                "always".equals(r.retryMode), () -> {
                    r.retryMode = "always"; r.retryMax = -1;
                    render();
                }));

        // 次数（always 模式没有次数，不显示）
        if ("normal".equals(r.retryMode)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 10), 0, 0);
            TextView lab = new TextView(this);
            lab.setText("最多重试");
            lab.setTextColor(Ui.TEXT);
            lab.setTextSize(14);
            lab.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(lab);
            for (final int n : new int[]{0, 3, 5, 10}) {
                TextView b = Ui.button(this, String.valueOf(n), r.retryMax == n, v -> {
                    r.retryMax = n;
                    render();
                });
                b.setLayoutParams(wrapLp());
                row.addView(b);
            }
            card.addView(row);
        }

        // 退避（首次间隔）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView lab2 = new TextView(this);
        lab2.setText("首次等待");
        lab2.setTextColor(Ui.TEXT);
        lab2.setTextSize(14);
        lab2.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(lab2);
        int[] delays = {500, 1000, 2000, 5000};
        String[] delayNames = {"0.5s", "1s", "2s", "5s"};
        for (int i = 0; i < delays.length; i++) {
            final int d = delays[i];
            TextView b = Ui.button(this, delayNames[i], r.retryDelayMs == d, v -> {
                // 选默认（-1）之后的第一次点击才算"我改过"
                r.retryDelayMs = d;
                if (r.retryMaxDelayMs < 0) r.retryMaxDelayMs = Math.max(d * 10, 10000);
                if ((r.retryMode == null || r.retryMode.isEmpty())) r.retryMode = "normal";
                render();
            });
            b.setLayoutParams(wrapLp());
            row2.addView(b);
        }
        card.addView(row2);

        TextView note = new TextView(this);
        note.setText("默认是 0.5s 起步、每次翻倍（上限 10s）。次数与等待都只在保存后写进 DSH 的配置，"
                + "重启 DSH 生效。");
        note.setTextColor(Ui.SUB);
        note.setTextSize(12);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(note);
        return card;
    }

    private interface FieldCb { void on(String v); }

    private View choice(String title, String desc, boolean selected, Runnable pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        box.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        box.setBackground(Ui.round(selected ? 0xFF1E3A5F : 0xFF17171A,
                selected ? Ui.ACCENT : Ui.STROKE, 10, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText((selected ? "● " : "○ ") + title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(15);
        box.addView(t);
        if (desc != null) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(Ui.SUB);
            d.setTextSize(12);
            d.setPadding(0, Ui.dp(this, 4), 0, 0);
            box.addView(d);
        }
        box.setOnClickListener(v -> pick.run());
        return box;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void appendReport(String line) {
        Env.report(this, line);
    }
}
