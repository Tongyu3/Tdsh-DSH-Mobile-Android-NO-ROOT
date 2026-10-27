/**
 * dsh-tts —— 语音朗读（浏览器侧）
 * ════════════════════════════════════════════════════════════════════════
 * 干什么：回复生成完，自动把它念出来。输入框下方一个小喇叭，亮=自动朗读、灭=不读。
 *
 * 三种引擎（设置 →「语音朗读」里选）：
 *   1. 系统语音 —— 安卓自带 TTS，本地合成、离线、免费（默认，开箱即用）
 *   2. 云端 TTS —— OpenAI 兼容的 /audio/speech（OpenAI、硅基流动 CosyVoice 等）
 *   3. 自定义   —— 任何"给文字、回音频"的接口，GPT-SoVITS / RVC 变声服务都能接
 *
 * 为什么声音不在网页里发：
 *   · 安卓 WebView 没有 speechSynthesis（Chrome 有、WebView 里是 undefined）；
 *   · 网页 fetch 云端接口会被 CORS 挡掉，API Key 也会躺在页面里。
 *   所以"变成声音"全部交给 App 的原生桥 DshAndroid.ttsSpeak / ttsPlay（见 Tts.java）。
 *
 * 为什么靠 DOM 判断"读哪一段"：
 *   DSH 的会话数据在它自己的 store 里，插件没有稳定渠道去订阅；
 *   而消息节点带了 data-chat-group-part="response" / data-chat-flow-key 这些
 *   结构化标记 —— 监视它们既够用，也不会因为 DSH 改内部结构就整个失效
 *   （最坏情况是退化成"按最后一个文本块读"）。
 */

window.__ModuleLoader__.load({
  id: "dsh-tts",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

    const react = require("react");
    const h = react.createElement;

    const PKG = "dsh-tts";
    const CFG_KEY = "dsh-tts.cfg.v1";

    // ── 配置 ────────────────────────────────────────────────────────
    const DEFAULTS = {
      on: false,                 // 自动朗读开关（就是输入框下面那个小喇叭）
      engine: "system",          // system | cloud | custom | local
      rate: 1.0,                 // 系统语音语速
      pitch: 1.0,
      voice: "",                 // 系统语音音色（空 = 跟随系统）
      local: {
        model: "",               // 本地模型 id（空 = 用第一个已安装的）
        speed: 1.0,
      },
      cloud: {
        preset: "siliconflow",
        base: "https://api.siliconflow.cn/v1",
        key: "",
        model: "FunAudioLLM/CosyVoice2-0.5B",
        voice: "FunAudioLLM/CosyVoice2-0.5B:alex",
        speed: 1.0,
      },
      custom: {
        url: "",
        method: "POST",
        headers: "{\n  \"Content-Type\": \"application/json\"\n}",
        body: "{\n  \"text\": \"{{text}}\",\n  \"text_lang\": \"zh\",\n  \"media_type\": \"wav\"\n}",
      },
    };

    /** 预设只为少填几个框，填完值仍可以手改。 */
    const PRESETS = {
      siliconflow: {
        label: "硅基流动 CosyVoice",
        base: "https://api.siliconflow.cn/v1",
        model: "FunAudioLLM/CosyVoice2-0.5B",
        voice: "FunAudioLLM/CosyVoice2-0.5B:alex",
        hint: "在 cloud.siliconflow.cn 申请 Key，音色可填 FunAudioLLM/CosyVoice2-0.5B:alex 之类",
      },
      openai: {
        label: "OpenAI 兼容",
        base: "https://api.openai.com/v1",
        model: "tts-1",
        voice: "alloy",
        hint: "任何 OpenAI 兼容网关都行：把接口地址换掉、Key 填上即可",
      },
      other: {
        label: "其它兼容接口",
        base: "",
        model: "",
        voice: "",
        hint: "接口必须是 POST {base}/audio/speech，请求体 {model,input,voice,speed}，返回音频",
      },
    };

    function merge(base, patch) {
      const out = Object.assign({}, base);
      for (const k of Object.keys(patch || {})) {
        const v = patch[k];
        if (v && typeof v === "object" && !Array.isArray(v) && base[k] && typeof base[k] === "object") {
          out[k] = Object.assign({}, base[k], v);
        } else {
          out[k] = v;
        }
      }
      return out;
    }

    function loadCfg() {
      try {
        const raw = localStorage.getItem(CFG_KEY);
        if (!raw) return JSON.parse(JSON.stringify(DEFAULTS));
        return merge(JSON.parse(JSON.stringify(DEFAULTS)), JSON.parse(raw));
      } catch (e) {
        return JSON.parse(JSON.stringify(DEFAULTS));
      }
    }

    // ── 极简 store（配置 + 播放状态）────────────────────────────────
    const state = {
      cfg: loadCfg(), speaking: false, status: "", voices: [],
      /** 本地模型：{count, models:[{id,name,kind,mb,ref}], builtin:{...}} */
      models: null,
      /** 模型安装进度：null 或 {pct, step} */
      install: null,
    };
    const subs = new Set();
    function notify() {
      subs.forEach((f) => { try { f(); } catch (e) { /* 单个订阅出错不影响别人 */ } });
    }
    function setCfg(patch) {
      state.cfg = merge(state.cfg, patch);
      try { localStorage.setItem(CFG_KEY, JSON.stringify(state.cfg)); } catch (e) { }
      notify();
      arms();
    }
    function setStatus(msg) {
      state.status = msg || "";
      notify();
    }
    function useStore() {
      const [, force] = react.useState(0);
      react.useEffect(() => {
        const f = () => force((x) => x + 1);
        subs.add(f);
        return () => subs.delete(f);
      }, []);
      return state;
    }

    // ── 原生桥 ──────────────────────────────────────────────────────
    function bridge() {
      const b = window.DshAndroid;
      if (!b || !b.ttsSpeak) return null;
      return b;
    }
    function hasBridge() { return !!bridge(); }

    /**
     * 拉一次本地模型清单。
     *
     * <p>只读目录 + 清单文件，不加载任何模型 —— 所以可以随便调，
     * 不会因为"打开设置页"就把 100MB 的模型读进内存（那是发热的来源）。
     */
    function loadModels() {
      const b = bridge();
      if (!b || !b.ttsModels) { state.models = { count: 0, models: [], builtin: null }; notify(); return; }
      try {
        const o = JSON.parse(b.ttsModels() || "{}");
        state.models = o || { count: 0, models: [] };
        // 配置里选的模型不在了（被删了 / 换机）→ 回落到第一个
        const ids = (state.models.models || []).map((m) => m.id);
        if (ids.length && ids.indexOf(state.cfg.local.model) < 0) {
          setCfg({ local: { model: ids[0] } });
        }
      } catch (e) {
        state.models = { count: 0, models: [], error: String(e && e.message ? e.message : e) };
      }
      notify();
    }

    /** 原生回报的播放状态（播完 / 出错 / 被停止 / 本地模型安装进度）。 */
    window.__dshTtsEvent = function (evt) {
      try {
        const type = evt && evt.type;
        if (type === "start") { state.speaking = true; state.status = ""; }
        else if (type === "loading") { state.status = (evt && evt.message) || "加载模型…"; }
        else if (type === "done" || type === "stopped") { state.speaking = false; onReadFinished(); }
        else if (type === "error") {
          state.speaking = false;
          state.status = (evt && evt.message) || "朗读出错";
        } else if (type === "modelProgress") {
          state.install = { pct: Math.max(0, Math.min(100, Number(evt.pct) || 0)), step: evt.step || "" };
        } else if (type === "modelDone") {
          state.install = null;
          state.status = "已装好：" + (evt.name || evt.id || "");
          loadModels();
        } else if (type === "modelError") {
          state.install = null;
          state.status = "安装失败：" + (evt.message || "");
        }
        notify();
      } catch (e) { }
    };

    /** 念完一段后，如果还排着队就继续下一段（目前一次只念一条）。 */
    function onReadFinished() {
      if (queue.length) {
        const next = queue.shift();
        speakText(next, true);
      }
    }
    const queue = [];

    function stopSpeaking() {
      queue.length = 0;
      const b = bridge();
      if (b) { try { b.ttsStop(); } catch (e) { } }
      state.speaking = false;
      notify();
    }

    /** 把纯文本交给原生。engine 决定走哪条路。 */
    function speakText(text, fromQueue) {
      const cfg = state.cfg;
      if (!text || !text.trim()) return;
      const b = bridge();
      if (!b) {
        setStatus("这个功能要在 DeepSeek Harness 安卓版里用（缺 App 桥）");
        return;
      }
      if (!fromQueue) {
        // 新的一段：先把上一段停掉，避免两段声音叠在一起
        queue.length = 0;
        try { b.ttsStop(); } catch (e) { }
      }
      const clean = text.trim().slice(0, 6000);
      try {
        if (cfg.engine === "system") {
          b.ttsSpeak(clean, JSON.stringify({
            rate: Number(cfg.rate) || 1,
            pitch: Number(cfg.pitch) || 1,
            voice: cfg.voice || "",
          }));
        } else if (cfg.engine === "local") {
          /*
           * 本地模型：文字交给原生，在手机 CPU 上跑开源模型（sherpa-onnx）。
           * 网页这边只负责"用哪个模型"，合成 / 播放 / 释放全在 TtsLocal。
           */
          const list = (state.models && state.models.models) || [];
          let id = (cfg.local && cfg.local.model) || "";
          if (!id || !list.some((m) => m.id === id)) id = list.length ? list[0].id : "";
          if (!id) {
            setStatus("还没有本地模型 —— 去「设置 → 语音朗读 → 本地模型」装一个");
            queue.length = 0;
            return;
          }
          b.ttsSpeak(clean, JSON.stringify({
            engine: "local",
            model: id,
            speed: Number(cfg.local && cfg.local.speed) || 1,
          }));
        } else if (cfg.engine === "cloud") {          const p = PRESETS[cfg.cloud.preset] || PRESETS.siliconflow;
          const base = (cfg.cloud.base || p.base || "").replace(/\/+$/, "");
          if (!base) { setStatus("还没填云端接口地址"); queue.length = 0; return; }
          if (!cfg.cloud.key) { setStatus("还没填云端 API Key"); queue.length = 0; return; }
          b.ttsPlay(JSON.stringify({
            url: base + "/audio/speech",
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "Authorization": "Bearer " + cfg.cloud.key,
            },
            body: JSON.stringify({
              model: cfg.cloud.model || p.model,
              input: clean,
              voice: cfg.cloud.voice || p.voice,
              speed: Number(cfg.cloud.speed) || 1,
              response_format: "mp3",
            }),
          }));
        } else {
          const c = cfg.custom || {};
          if (!c.url) { setStatus("还没填自定义接口地址"); queue.length = 0; return; }
          let headers = {};
          try { headers = JSON.parse(c.headers || "{}"); } catch (e) { headers = {}; }
          const body = (c.body || "").replace(/\{\{text\}\}/g, jsonEscape(clean));
          b.ttsPlay(JSON.stringify({
            url: c.url,
            method: (c.method || "POST").toUpperCase(),
            headers: headers,
            body: body,
          }));
        }
        state.speaking = true;
        state.status = "";
        notify();
      } catch (e) {
        setStatus("朗读失败：" + (e && e.message ? e.message : e));
      }
    }

    /** 放进 JSON 字符串里的转义（自定义请求体模板用）。 */
    function jsonEscape(s) {
      return JSON.stringify(String(s)).slice(1, -1);
    }

    // ── 读哪一段：DOM 取文 ──────────────────────────────────────────
    /**
     * 最新的一条助手回复。
     *
     * DSH 给每个消息行打了结构化标记：
     *   [data-chat-group-part="response"]  助手正文那一组
     *   [data-chat-flow-key] / [data-chat-turn]  行标识与轮次
     * 没有标记时退化成"最后一个流程行"，再不行就整页找正文块 —— 三层兜底。
     */
    function latestAnswerEl() {
      const resp = document.querySelectorAll('[data-chat-group-part="response"]');
      if (resp.length) return resp[resp.length - 1];
      const rows = document.querySelectorAll("[data-chat-flow-key]");
      if (rows.length) return rows[rows.length - 1];
      const md = document.querySelectorAll('[class*="markdown" i]');
      return md.length ? md[md.length - 1] : null;
    }

    /** 还在流式生成？ */
    function isStreaming(el) {
      if (!el) return false;
      try {
        if (el.querySelector("[data-streaming]")) return true;
        if (el.closest("[data-streaming]")) return true;
      } catch (e) { }
      return false;
    }

    /**
     * 把一段消息节点变成"能念的人话"。
     *
     * 跳过的都是"看得见但不该念"的东西：代码块、按钮、工具卡片、图标、KaTeX 隐藏层。
     * 代码块尤其要跳 —— 读 <div class=...> 既没意义又长到没边。
     */
    function textOf(root) {
      if (!root) return "";
      const SKIP = "pre,code,kbd,button,[role=button],svg,img,video,audio,canvas," +
        "[data-actions-reveal],[aria-hidden=true],style,script,noscript";
      const parts = [];
      const BLOCK = /^(P|DIV|LI|H[1-6]|TR|BLOCKQUOTE|SECTION|ARTICLE|UL|OL|TABLE|HR)$/;
      (function walk(node) {
        if (node.nodeType === 3) {
          const t = node.nodeValue;
          if (t && t.trim()) parts.push(t);
          return;
        }
        if (node.nodeType !== 1) return;
        let tag = "";
        try { tag = node.tagName || ""; } catch (e) { }
        try {
          if (node.matches && node.matches(SKIP)) return;
        } catch (e) { }
        const block = BLOCK.test(tag);
        if (block) parts.push("\n");
        const kids = node.childNodes || [];
        for (let i = 0; i < kids.length; i++) walk(kids[i]);
        if (block) parts.push("\n");
      })(root);
      return parts.join("")
        .replace(/[ \t]+/g, " ")
        .replace(/\n[ \t]+/g, "\n")
        .replace(/\n{2,}/g, "\n")
        .trim();
    }

    // ── 什么时候读 ──────────────────────────────────────────────────
    /*
     * 判据是"这一条刚刚在流式生成、现在停了"：
     *   · 只有真的见过它 data-streaming 才算"新回复" —— 翻旧会话、切工作区不会被念；
     *   · 生成太快没看到流式的，用"轮次比之前大"兜底（同一个会话里）。
     * 两条都不会在打开历史会话时触发，这正是用户要的"自动读新回复"。
     */
    let liveKeys = Object.create(null);   // 见过它流式生成的行
    let readKeys = Object.create(null);   // 已经念过的行
    let knownTurns = -1;                  // 上一次见到的最大轮次
    let lastText = "";
    let lastKey = "";
    let lastFingerprint = "";
    let timer = null;
    let observer = null;

    /**
     * 当前会话的指纹（标题 + 第一条用户消息的开头）。
     *
     * 为什么要它：切会话时 DOM 会整个换一遍，"最大轮次"也跟着跳 ——
     * 不识别换会话，切到一个历史会话就会把最后一条回复念出来。
     */
    function sessionFingerprint() {
      let first = "";
      try {
        const u = document.querySelector('[data-chat-flow-kind="user"]');
        if (u) first = (u.innerText || "").replace(/\s+/g, " ").slice(0, 40);
      } catch (e) { }
      const title = (document.title || "").split(" — ")[0];
      return title + "|" + first;
    }

    function keyOf(el) {
      try {
        return el.getAttribute("data-chat-flow-key")
          || el.getAttribute("data-chat-group-key")
          || ("turn:" + el.getAttribute("data-chat-turn"))
          || "";
      } catch (e) { return ""; }
    }

    function maxTurn() {
      let max = -1;
      const rows = document.querySelectorAll("[data-chat-turn]");
      for (let i = 0; i < rows.length; i++) {
        const n = Number(rows[i].getAttribute("data-chat-turn"));
        if (Number.isSafeInteger(n) && n > max) max = n;
      }
      return max;
    }

    function check() {
      if (!state.cfg.on) return;
      try { if (document.hidden) return; } catch (e) { }
      // 换了会话：把"已经念过 / 见过流式 / 最大轮次"全部重置，但**不念**任何东西
      const fp = sessionFingerprint();
      if (fp !== lastFingerprint) {
        lastFingerprint = fp;
        liveKeys = Object.create(null);
        readKeys = Object.create(null);
        knownTurns = maxTurn();
        lastKey = "";
        lastText = textOf(latestAnswerEl());
        return;
      }
      const el = latestAnswerEl();
      if (!el) return;
      const key = keyOf(el) || ("text:" + textOf(el).slice(0, 24));
      const streaming = isStreaming(el);
      if (streaming) {
        liveKeys[key] = 1;
        return;
      }
      const text = textOf(el);
      if (!text) return;
      const turnNow = maxTurn();
      // 轮次变大 = 新的一轮开始了（同一个会话里最可靠的信号）
      const freshTurn = turnNow > knownTurns && turnNow >= 0;
      if (turnNow > knownTurns) knownTurns = turnNow;
      if (readKeys[key]) return;
      // 还没稳定（文字还在变）就再等一拍
      if (key === lastKey && text !== lastText) { lastText = text; return; }
      const live = liveKeys[key] || freshTurn;
      lastKey = key;
      lastText = text;
      if (!live) return;
      // 太短的多半是"已完成，用时 3 秒"这类空壳
      if (text.replace(/\s/g, "").length < 2) { readKeys[key] = 1; return; }
      readKeys[key] = 1;
      speakText(text);
    }

    function schedule(delay) {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => { timer = null; check(); }, delay == null ? 900 : delay);
    }

    /** 装上监视（幂等）。配置里关掉自动朗读时也保持监视 —— 省得反复拆装。 */
    function arms() {
      if (observer) return;
      try {
        knownTurns = maxTurn();
        lastFingerprint = sessionFingerprint();
        observer = new MutationObserver(() => schedule(1100));
        observer.observe(document.body, { childList: true, subtree: true, characterData: true });
      } catch (e) { }
    }

    // ── 图标 ────────────────────────────────────────────────────────
    function IconSpeaker(props) {
      const on = !!props.on;
      const stop = !!props.stop;
      const color = stop ? "#e5484d" : on ? "#4d6bfe" : "currentColor";
      if (stop) {
        return h("svg", { width: 15, height: 15, viewBox: "0 0 16 16", fill: "none", "aria-hidden": true },
          h("rect", { x: 3.5, y: 3.5, width: 9, height: 9, rx: 1.6, fill: color }));
      }
      return h("svg", { width: 15, height: 15, viewBox: "0 0 16 16", fill: "none", "aria-hidden": true },
        h("path", {
          d: "M2.6 6.1h2.1L7.6 3.6c.35-.3.9-.05.9.42v7.96c0 .47-.55.72-.9.42L4.7 9.9H2.6a.8.8 0 0 1-.8-.8V6.9c0-.44.36-.8.8-.8Z",
          fill: color,
        }),
        on
          ? h("path", { d: "M10.6 5.6a3.3 3.3 0 0 1 0 4.8M12.3 4a5.6 5.6 0 0 1 0 8", stroke: color, strokeWidth: 1.3, strokeLinecap: "round" })
          : h("path", { d: "M10.8 6.4l3 3.2M13.8 6.4l-3 3.2", stroke: color, strokeWidth: 1.3, strokeLinecap: "round" }));
    }

    // ── UI：输入框下方的小喇叭 ──────────────────────────────────────
    /*
     * 位置就是 conversation.composer.dock —— 输入框**下面**那一行
     * （"1 轮 12 步 · 191 tok/s | 319K tok | 3%" 那一排的左侧）。
     */
    function SpeakerButton() {
      const s = useStore();
      const on = !!s.cfg.on;
      const speaking = !!s.speaking;
      const title = speaking ? "停止朗读"
        : on ? "自动朗读：开（点一下关闭）"
          : "自动朗读：关（点一下开启）";
      return h("button", {
        type: "button",
        title: title,
        "aria-label": title,
        "data-dsh-tts-btn": on ? "on" : "off",
        onClick: (ev) => {
          ev.preventDefault();
          ev.stopPropagation();
          if (speaking) { stopSpeaking(); return; }
          setCfg({ on: !on });
          if (!on && hasBridge()) speakText("自动朗读已开启");
        },
        style: {
          display: "inline-flex", alignItems: "center", gap: 4,
          height: 20, padding: "0 4px", marginRight: 2,
          border: 0, background: "transparent", cursor: "pointer",
          color: speaking ? "#e5484d" : on ? "#4d6bfe" : "var(--dsw-alias-label-secondary,#8a8f98)",
          opacity: on || speaking ? 1 : 0.75,
        },
      }, h(IconSpeaker, { on: on, stop: speaking }));
    }

    // ── UI：设置页 ──────────────────────────────────────────────────
    const C = {
      text: "var(--dsw-alias-label-primary,#1f2329)",
      dim: "var(--dsw-alias-label-secondary,#8a8f98)",
      line: "var(--dsw-alias-border-l2,rgba(128,128,128,.28))",
      box: "var(--dsw-alias-bg-l2,rgba(128,128,128,.10))",
      /*
       * 强调色**写死**，不跟主题变量。
       *
       * 实测：DSH 深色主题下 --dsw-alias-brand-primary 解析出来是白色，
       * 拿它当按钮底色会变成"白底白字"（第一版在真机上就是这样，按钮看着像空了）。
       * 蓝底白字在深浅两种主题下都成立，所以这里不留变量。
       */
      brand: "#4d6bfe",
    };
    const S = {
      wrap: { padding: "4px 2px 20px", color: C.text, fontSize: 14, lineHeight: 1.6 },
      section: { marginTop: 18 },
      label: { fontSize: 13, color: C.dim, marginBottom: 6 },
      row: { display: "flex", alignItems: "center", gap: 10, marginBottom: 10, flexWrap: "wrap" },
      input: {
        flex: 1, minWidth: 0, width: "100%", boxSizing: "border-box",
        padding: "9px 10px", borderRadius: 8, border: "1px solid " + C.line,
        background: "transparent", color: C.text, fontSize: 13, outline: "none",
      },
      area: {
        width: "100%", boxSizing: "border-box", padding: "9px 10px", borderRadius: 8,
        border: "1px solid " + C.line, background: "transparent", color: C.text,
        fontSize: 12.5, lineHeight: 1.5, fontFamily: "ui-monospace, Menlo, Consolas, monospace",
        resize: "vertical", outline: "none",
      },
      seg: { display: "inline-flex", border: "1px solid " + C.line, borderRadius: 9, overflow: "hidden" },
      segBtn: (active) => ({
        padding: "8px 12px", border: 0, cursor: "pointer", fontSize: 13,
        background: active ? C.brand : "transparent",
        color: active ? "#fff" : C.text,
      }),
      btn: {
        padding: "8px 14px", borderRadius: 8, border: "1px solid " + C.line,
        background: "transparent", color: C.text, fontSize: 13, cursor: "pointer",
      },
      btnMain: {
        padding: "9px 16px", borderRadius: 8, border: 0,
        background: C.brand, color: "#fff", fontSize: 13, cursor: "pointer",
      },
      hint: { fontSize: 12, color: C.dim, lineHeight: 1.55, marginTop: 6 },
      switchRow: {
        display: "flex", alignItems: "center", justifyContent: "space-between",
        gap: 12, padding: "12px 14px", borderRadius: 10, background: C.box,
      },
    };

    /** 一个朴素的开关（不依赖 DSH 的内部组件，样式跟着主题变量走）。 */
    function Switch(props) {
      const on = !!props.value;
      return h("button", {
        type: "button",
        role: "switch",
        "aria-checked": on ? "true" : "false",
        onClick: () => props.onChange(!on),
        style: {
          width: 42, height: 24, borderRadius: 999, border: 0, cursor: "pointer",
          background: on ? C.brand : "rgba(128,128,128,.38)",
          position: "relative", transition: "background .15s", flex: "0 0 auto",
        },
      }, h("span", {
        style: {
          position: "absolute", top: 3, left: on ? 21 : 3, width: 18, height: 18,
          borderRadius: "50%", background: "#fff", transition: "left .15s",
        },
      }));
    }

    function Field(props) {
      return h("div", { style: S.section },
        h("div", { style: S.label }, props.label),
        props.children,
        props.hint ? h("div", { style: S.hint }, props.hint) : null);
    }

    /**
     * 「本地模型」这一栏：模型列表 + 内置纳西妲 + 粘贴链接安装。
     *
     * <h3>为什么要有"内置"和"自己加"两种</h3>
     * 内置的那个（纳西妲）用的是 **ZipVoice 零样本克隆**：模型本身是中英通用的，
     * 音色来自我们打包在 App 里的那段参考人声 —— 所以它真的"自带音色"，
     * 只是模型文件太大（109MB）不能塞进安装包，点一下按钮从发布页下。
     * 而"自己加"接的是任何开源 TTS 模型包（sherpa-onnx 的 tts-models 发布页那种），
     * 粘链接即可 —— 用户要求的就是这个。
     */
    function LocalPanel(props) {
      const cfg = props.cfg;
      const st = props.s;
      const [url, setUrl] = react.useState("");
      const models = (st.models && st.models.models) || [];
      const builtin = (st.models && st.models.builtin) || null;
      const installing = st.install;
      const hasNahida = !!builtin && (builtin.installed !== false);

      react.useEffect(() => { loadModels(); }, []);

      const field = (label, hint, children) =>
        h("div", { style: S.section }, h("div", { style: S.label }, label), children,
          hint ? h("div", { style: S.hint }, hint) : null);

      const progress = installing ? h("div", { style: S.hint },
        h("div", {
          style: { height: 6, borderRadius: 3, background: "rgba(128,128,128,.25)", overflow: "hidden", margin: "8px 0 6px" },
        }, h("div", {
          style: { height: "100%", width: Math.max(2, installing.pct) + "%", background: C.brand, transition: "width .2s" },
        })),
        (installing.pct || 0) + "%  " + (installing.step || "")) : null;

      return h("div", null,
        field("模型",
          models.length ? "" : "还没有安装任何本地模型。先装下面的「纳西妲」，或者粘一个开源模型的下载链接。",
          models.length ? h("select", {
            style: S.input,
            value: (cfg.local && cfg.local.model) || (models[0] && models[0].id) || "",
            onChange: (e) => props.onCfg({ local: { model: e.target.value } }),
          }, models.map((m) => h("option", { key: m.id, value: m.id },
            m.name + "（" + m.kind + " · " + (m.mb || 0) + "MB）"))) : null),

        // 内置：纳西妲
        h("div", { style: S.switchRow },
          h("div", null,
            h("div", { style: { fontWeight: 600 } }, (builtin && builtin.name) || "纳西妲（克隆音色）"),
            h("div", { style: { fontSize: 12, color: C.dim, marginTop: 2 } },
              hasNahida ? "已安装 · 零样本克隆（参考人声打包在 App 里）"
                : "零样本克隆 · 需下载模型约 " + ((builtin && builtin.mb) || 109) + "MB（离线合成，装一次就够）")),
          h("div", { style: { display: "flex", gap: 8, flex: "0 0 auto" } },
            hasNahida
              ? h("button", {
                style: S.btn, onClick: () => {
                  props.onCfg({ local: { model: (builtin && builtin.id) || "nahida" }, engine: "local" });
                  setStatus("已选「纳西妲」，点下面试听");
                },
              }, "用这个")
              : h("button", {
                style: S.btnMain, disabled: !!installing,
                onClick: () => {
                  const b = bridge();
                  if (!b || !b.ttsModelAdd) { setStatus("这个功能要在安卓版里用"); return; }
                  state.install = { pct: 0, step: "准备中…" };
                  notify();
                  b.ttsModelAdd("", "nahida");
                },
              }, installing ? "安装中…" : "安装"),
            hasNahida ? h("button", {
              style: S.btn,
              onClick: () => {
                const b = bridge();
                if (!b || !b.ttsModelDel) return;
                b.ttsModelDel((builtin && builtin.id) || "nahida");
                loadModels();
              },
            }, "删除") : null)),
        progress,

        // 自己加
        field("添加开源模型（粘贴下载链接）",
          "支持 .tar.bz2 / .zip / .tar.gz —— sherpa-onnx 的 tts-models 发布页里那些模型包直接粘就行。"
            + "下载完会自动解包、认出是哪种模型（VITS / Matcha / ZipVoice）、出现在上面的列表里。",
          h("div", { style: S.row },
            h("input", {
              style: S.input, value: url, placeholder: "https://github.com/…/model.tar.bz2",
              onChange: (e) => setUrl(e.target.value),
            }),
            h("button", {
              style: S.btn, disabled: !!installing || !url.trim(),
              onClick: () => {
                const b = bridge();
                if (!b || !b.ttsModelAdd) { setStatus("这个功能要在安卓版里用"); return; }
                state.install = { pct: 0, step: "准备中…" };
                notify();
                b.ttsModelAdd(url.trim(), "");
              },
            }, "下载安装"))),

        // 语速
        h("div", { style: S.section },
          h("div", { style: S.label }, "语速 · " + Number((cfg.local && cfg.local.speed) || 1).toFixed(1) + "×"),
          h("input", {
            type: "range", min: 0.5, max: 2, step: 0.1, value: (cfg.local && cfg.local.speed) || 1,
            style: { width: "100%" },
            onChange: (e) => props.onCfg({ local: { speed: Number(e.target.value) } }),
          })),

        h("div", { style: S.hint },
          "全离线：不联网、不花钱、也不发任何字出去。",
          h("br"),
          "为省电控温：合成只用 2 个核心，念完 3 分钟没动静就把模型从内存里卸掉；"
            + "单次最多念 1200 字（本地合成比云端慢，念太久手机会热）。"),
      );
    }

    function TtsSettings() {
      const s = useStore();
      const cfg = s.cfg;
      const [voices, setVoices] = react.useState(s.voices || []);
      const [info, setInfo] = react.useState("");

      // 系统语音的能力清单要等原生引擎初始化，第一下多半是空的 → 连续问两次
      react.useEffect(() => {
        let alive = true;
        const ask = (n) => {
          const b = bridge();
          if (!b || !b.ttsInfo) { setInfo("没有 App 桥：这个设置页只在 Tdsh 里有用"); return; }
          try {
            const raw = b.ttsInfo();
            const o = JSON.parse(raw || "{}");
            if (!alive) return;
            if (o.error) setInfo(o.error);
            else if (!o.ready && n > 0) setTimeout(() => ask(n - 1), 600);
            else setInfo(o.ready ? "" : "系统语音引擎还在准备…");
            if (o.voices && o.voices.length) setVoices(o.voices);
          } catch (e) {
            setInfo("读系统语音失败：" + (e && e.message ? e.message : e));
          }
        };
        ask(3);
        return () => { alive = false; };
      }, []);

      const engineBtn = (id, text) => h("button", {
        type: "button",
        style: S.segBtn(cfg.engine === id),
        onClick: () => setCfg({ engine: id }),
      }, text);

      const preset = PRESETS[cfg.cloud.preset] || PRESETS.siliconflow;

      return h("div", { style: S.wrap },
        // 开关
        h("div", { style: S.switchRow },
          h("div", null,
            h("div", { style: { fontWeight: 600 } }, "自动朗读"),
            h("div", { style: { fontSize: 12, color: C.dim, marginTop: 2 } },
              "开：回复生成完自动念出来。也可以直接点输入框下面那个小喇叭。")),
          h(Switch, {
            value: cfg.on,
            onChange: (v) => { setCfg({ on: v }); if (!v) stopSpeaking(); },
          })),

        // 引擎
        h(Field, { label: "引擎" },
          h("div", { style: S.seg }, engineBtn("system", "系统语音"), engineBtn("local", "本地模型"),
            engineBtn("cloud", "云端 TTS"), engineBtn("custom", "自定义"))),

        cfg.engine === "system" ? h("div", null,
          h(Field, { label: "语速 · " + Number(cfg.rate).toFixed(1) + "×" },
            h("input", {
              type: "range", min: 0.5, max: 2, step: 0.1, value: cfg.rate,
              style: { width: "100%" },
              onChange: (e) => setCfg({ rate: Number(e.target.value) }),
            })),
          h(Field, {
            label: "音色",
            hint: info || "离线合成，不联网、不花钱。音色列表来自手机里装的系统语音引擎。",
          },
            h("select", {
              style: S.input,
              value: cfg.voice || "",
              onChange: (e) => setCfg({ voice: e.target.value }),
            },
              h("option", { value: "" }, "跟随系统默认"),
              voices.map((v) => h("option", { key: v.name, value: v.name }, v.label || v.name))))
        ) : null,

        cfg.engine === "cloud" ? h("div", null,
          h(Field, { label: "服务" },
            h("select", {
              style: S.input,
              value: cfg.cloud.preset || "siliconflow",
              onChange: (e) => {
                const p = PRESETS[e.target.value] || PRESETS.siliconflow;
                setCfg({ cloud: { preset: e.target.value, base: p.base, model: p.model, voice: p.voice } });
              },
            }, Object.keys(PRESETS).map((k) => h("option", { key: k, value: k }, PRESETS[k].label)))),
          h(Field, { label: "接口地址" },
            h("input", {
              style: S.input, value: cfg.cloud.base || "",
              placeholder: "https://api.siliconflow.cn/v1",
              onChange: (e) => setCfg({ cloud: { base: e.target.value } }),
            })),
          h(Field, { label: "API Key", hint: preset.hint },
            h("input", {
              style: S.input, value: cfg.cloud.key || "", type: "password",
              placeholder: "sk-…",
              onChange: (e) => setCfg({ cloud: { key: e.target.value } }),
            })),
          h("div", { style: S.row },
            h("div", { style: { flex: 1 } },
              h("div", { style: S.label }, "模型"),
              h("input", {
                style: S.input, value: cfg.cloud.model || "",
                onChange: (e) => setCfg({ cloud: { model: e.target.value } }),
              })),
            h("div", { style: { flex: 1 } },
              h("div", { style: S.label }, "音色"),
              h("input", {
                style: S.input, value: cfg.cloud.voice || "",
                onChange: (e) => setCfg({ cloud: { voice: e.target.value } }),
              }))),
          h(Field, { label: "语速 · " + Number(cfg.cloud.speed).toFixed(1) + "×" },
            h("input", {
              type: "range", min: 0.5, max: 2, step: 0.1, value: cfg.cloud.speed,
              style: { width: "100%" },
              onChange: (e) => setCfg({ cloud: { speed: Number(e.target.value) } }),
            }))
        ) : null,

        cfg.engine === "custom" ? h("div", null,
          h(Field, {
            label: "接口地址",
            hint: "给文字、回音频的接口都行：GPT-SoVITS 的 /tts、RVC 变声服务、自建 TTS 都算。返回音频字节，或返回 JSON 里带 audio(base64) / url。",
          },
            h("input", {
              style: S.input, value: cfg.custom.url || "",
              placeholder: "http://192.168.1.10:9880/tts",
              onChange: (e) => setCfg({ custom: { url: e.target.value } }),
            })),
          h(Field, { label: "方法" },
            h("select", {
              style: S.input, value: cfg.custom.method || "POST",
              onChange: (e) => setCfg({ custom: { method: e.target.value } }),
            }, h("option", { value: "POST" }, "POST"), h("option", { value: "GET" }, "GET"))),
          h(Field, {
            label: "请求头（JSON）",
          },
            h("textarea", {
              style: Object.assign({}, S.area, { height: 66 }),
              value: cfg.custom.headers || "",
              onChange: (e) => setCfg({ custom: { headers: e.target.value } }),
            })),
          h(Field, {
            label: "请求体模板",
            hint: "{{text}} 会被替换成要念的文字（已做 JSON 转义）。GET 时这个框不用填。",
          },
            h("textarea", {
              style: Object.assign({}, S.area, { height: 108 }),
              value: cfg.custom.body || "",
              onChange: (e) => setCfg({ custom: { body: e.target.value } }),
            }))
        ) : null,

        cfg.engine === "local" ? h(LocalPanel, { cfg: cfg, s: s, onCfg: setCfg }) : null,

        // 试听 / 停止
        h("div", { style: Object.assign({}, S.row, { marginTop: 20 }) },
          h("button", {
            style: S.btnMain,
            onClick: () => speakText("你好，这是朗读试听。回复生成完，我会自动把它念出来。"),
          }, "试听"),
          h("button", {
            style: S.btn,
            onClick: () => stopSpeaking(),
          }, s.speaking ? "停止朗读" : "停止"),
          h("span", { style: { fontSize: 12, color: s.speaking ? C.brand : C.dim } },
            s.speaking ? "正在朗读…" : (s.status || ""))),

        h("div", { style: S.hint },
          "只念新生成的回复（当前会话里最新的那条），翻旧会话不会自动念。",
          h("br"),
          "代码块、工具调用卡片不会念；太长的回复只念前 6000 字。"),
      );
    }

    // ── 注册 ────────────────────────────────────────────────────────
    function apply(ctx) {
      arms();
      // ① 输入框下方的小喇叭
      try {
        ctx.effect(() => ctx.slots.inject("conversation.composer.dock", () => ctx.slots.register({
          name: "conversation.composer.dock",
          id: PKG,
          order: 40,
        }, SpeakerButton)), "tts: 喇叭按钮");
      } catch (e) { }

      // ② 设置 →「语音朗读」这一页
      try {
        ctx.effect(() => ctx.slots.inject("settings.section", () => ctx.slots.register({
          name: "settings.section",
          id: PKG,
          order: 60,
          label: () => "语音朗读",
        }, TtsSettings)), "tts: 设置分区");
      } catch (e) { }

      // ③ 顺手把配置里的状态给外部（调试/自检用，不影响功能）
      window.__dshTts = {
        get: () => state.cfg,
        set: (p) => setCfg(p),
        speak: (t) => speakText(t),
        stop: () => stopSpeaking(),
        text: () => textOf(latestAnswerEl()),
      };
    }

    exports.apply = apply;
    /** 需要的客户端服务（不是包名 —— 包名写在 package.json 的 dsh.client.inject 里）。 */
    exports.inject = ["slots"];
    return module.exports;
  },
});
