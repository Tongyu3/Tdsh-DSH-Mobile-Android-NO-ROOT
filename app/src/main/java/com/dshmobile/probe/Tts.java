package com.dshmobile.probe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文本朗读 —— 插件「语音朗读」的原生那一半。
 *
 * <h3>为什么要落在原生</h3>
 * <ul>
 *   <li><b>系统语音</b>：安卓 WebView <b>没有</b> {@code speechSynthesis}
 *       （Chrome 有、WebView 里是 undefined），所以在网页里根本没法读。
 *       只能调系统的 {@link TextToSpeech}。这也是"本地合成、不花钱、可离线"的那一档。</li>
 *   <li><b>云端 TTS</b>：网页里 fetch 第三方接口会被 CORS 挡掉（对方不回
 *       Access-Control-Allow-Origin 就没戏），而且 API Key 会躺在页面里。
 *       这里由原生发请求、拿音频字节、MediaPlayer 播放 —— 任何返回音频的接口都能接。</li>
 * </ul>
 *
 * <h3>和网页的分工</h3>
 * 网页（插件 dsh-tts）负责"读哪一段、什么时候读、读多快"，原生只负责
 * "把它变成声音"和"停下来"。播放状态通过 {@link #attach} 注册的回调
 * 反推给网页（{@code window.__dshTtsEvent}），喇叭按钮据此变成"停止"。
 */
public final class Tts {

    private static final String TAG = "DSH_TTS";

    /** 单条合成的上限：系统 TTS 对超长文本不可靠，长文按句子切开排队。 */
    private static final int CHUNK = 320;

    private Tts() { }

    /** 往网页回报状态（由 MainActivity 注册，实现里 evaluateJavascript）。 */
    public interface Sink {
        void emit(String json);
    }

    private static Sink sSink;
    private static TextToSpeech sEngine;
    private static boolean sReady;
    private static boolean sSpeaking;
    /** 引擎还没就绪时先存一句，就绪后立刻读（用户点了"试听"不该没反应）。 */
    private static String sPending;
    private static String sLastError;
    /** 每次说话换个令牌，旧的回调回来时据此丢弃（停止/重新朗读时避免串台）。 */
    private static String sToken;
    private static int sRemaining;

    private static MediaPlayer sPlayer;
    private static File sAudioDir;
    private static boolean sCreating;

    public static void attach(Sink sink) {
        sSink = sink;
    }

    // ── 系统语音 ────────────────────────────────────────────────

    /** 首次使用时建引擎；重复调用直接复用。 */
    public static void ensure(final Context c) {
        if (sEngine != null || sCreating) return;
        sCreating = true;
        /*
         * 一定要在主线程建：TextToSpeech 会 attach 到当前线程的 Looper，
         * 而 JS 桥的方法跑在 WebView 的 JavaBridge 线程上（没有 Looper），
         * 在那里 new 出来要么抛异常、要么回调永远不来。
         */
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> create(c));
    }

    private static synchronized void create(final Context c) {
        if (sEngine != null) { sCreating = false; return; }
        try {
            sEngine = new TextToSpeech(c.getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS) {
                    sCreating = false;
                    sLastError = "这台手机没有可用的系统语音引擎";
                    emit("{\"type\":\"error\",\"message\":\"" + esc(sLastError) + "\"}");
                    Log.w(TAG, "TTS 初始化失败: " + status);
                    return;
                }
                sReady = true;
                sCreating = false;
                try {
                    int r = sEngine.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    if (r == TextToSpeech.LANG_MISSING_DATA
                            || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        sEngine.setLanguage(Locale.getDefault());
                    }
                } catch (Throwable ignore) { }
                sEngine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        sSpeaking = true;
                        emit("{\"type\":\"start\"}");
                    }

                    @Override
                    public void onDone(String id) {
                        finishOne(id);
                    }

                    @Override
                    public void onError(String id) {
                        finishOne(id);
                    }

                    @Override
                    public void onError(String id, int code) {
                        finishOne(id);
                    }
                });
                if (sPending != null) {
                    String text = sPending;
                    sPending = null;
                    speak(c, text, "{}");
                }
            });
        } catch (Throwable t) {
            sCreating = false;
            sLastError = "创建系统语音失败：" + t;
            Log.w(TAG, "创建 TTS 失败", t);
        }
    }

    /** 一条念完（或被丢弃）—— 只有最后一条才回报 done。 */
    private static synchronized void finishOne(String id) {
        if (sToken == null || id == null || !id.startsWith(sToken)) return;
        sRemaining--;
        if (sRemaining > 0) return;
        sSpeaking = false;
        emit("{\"type\":\"done\"}");
    }

    /**
     * 用系统语音朗读。
     *
     * @param optsJson {@code {"rate":1.0,"pitch":1.0,"voice":"…"}}
     */
    public static void speak(final Context c, final String text, final String optsJson) {
        if (text == null || text.trim().isEmpty()) return;
        /*
         * 本地模型走另一条路（sherpa-onnx 推理，不进系统 TTS）。
         *
         * 为什么要在这里分流而不是让网页自己判断：
         * 网页只关心"念这段字"，具体用手机系统语音、云端、还是本地模型，
         * 都是"变成声音"的实现细节 —— 分流放在这里，网页那份代码就不用知道
         * 本地模型有没有装、装了几个。
         */
        if (isLocalOpt(optsJson)) {
            speakLocal(c, text, optsJson);
            return;
        }
        ensure(c);
        if (!sReady) {
            sPending = text;                 // 引擎还在初始化，就绪后自动补上
            return;
        }
        // 同样要回主线程：speak 的回调挂在创建引擎那个 Looper 上
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> doSpeak(text, optsJson));
    }

    /** 这次请求是不是"本地模型"。 */
    private static boolean isLocalOpt(String optsJson) {
        try {
            JSONObject o = new JSONObject(optsJson == null || optsJson.isEmpty() ? "{}" : optsJson);
            return "local".equals(o.optString("engine", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用本地模型念（见 {@link TtsLocal}：推理、播放、发热控制都在那边）。 */
    private static void speakLocal(Context c, String text, String optsJson) {
        String model = "";
        float speed = 1f;
        try {
            JSONObject o = new JSONObject(optsJson == null || optsJson.isEmpty() ? "{}" : optsJson);
            model = o.optString("model", "");
            speed = (float) o.optDouble("speed", 1.0);
        } catch (Throwable ignore) { }
        if (model.isEmpty()) {
            List<TtsModels.Model> all = TtsModels.list(c);
            if (!all.isEmpty()) model = all.get(0).id;
        }
        if (model.isEmpty()) {
            emit("{\"type\":\"error\",\"message\":\"还没有安装本地模型（设置 → 语音朗读 → 本地模型）\"}");
            return;
        }
        stopPlayer();
        try { if (sEngine != null) sEngine.stop(); } catch (Throwable ignore) { }
        TtsLocal.speak(c, model, text, speed, null, new Sink() {
            @Override public void emit(String json) { Tts.emit(json); }
        });
    }

    private static void doSpeak(String text, String optsJson) {
        if (sEngine == null) return;
        float rate = 1f, pitch = 1f;
        String voice = null;
        try {
            JSONObject o = new JSONObject(optsJson == null || optsJson.isEmpty() ? "{}" : optsJson);
            rate = (float) o.optDouble("rate", 1.0);
            pitch = (float) o.optDouble("pitch", 1.0);
            voice = o.optString("voice", "");
        } catch (Throwable ignore) { }
        // 语速给个安全区间：0 会让引擎直接不说话，太快也没法听
        if (rate < 0.3f) rate = 0.3f;
        if (rate > 3f) rate = 3f;
        if (!voice.isEmpty()) {
            try {
                Voice v = findVoice(voice);
                if (v != null) sEngine.setVoice(v);
            } catch (Throwable ignore) { }
        }
        stopPlayer();
        List<String> chunks = split(text, CHUNK);
        sToken = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        sRemaining = chunks.size();
        sSpeaking = true;
        for (int i = 0; i < chunks.size(); i++) {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            int mode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            sEngine.speak(chunks.get(i), mode, params, sToken + "-" + i);
        }
        emit("{\"type\":\"start\"}");
    }

    private static Voice findVoice(String name) {
        try {
            Set<Voice> all = sEngine.getVoices();
            if (all == null) return null;
            for (Voice v : all) {
                if (name.equals(v.getName())) return v;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    /** 停止一切声音（系统语音 + 云端音频 + 本地模型）。 */
    public static void stopAll() {
        try {
            if (sEngine != null) sEngine.stop();
        } catch (Throwable ignore) { }
        // 本地模型是另一条独立的播放链路（AudioTrack），必须单独停
        try { TtsLocal.stop(); } catch (Throwable ignore) { }
        sToken = null;
        sRemaining = 0;
        sSpeaking = false;
        stopPlayer();
        emit("{\"type\":\"stopped\"}");
    }

    private static void stopPlayer() {
        MediaPlayer p = sPlayer;
        sPlayer = null;
        if (p == null) return;
        try { p.stop(); } catch (Throwable ignore) { }
        try { p.release(); } catch (Throwable ignore) { }
    }

    public static boolean speaking() {
        return sSpeaking || sPlayer != null;
    }

    /** 给设置页用的能力清单：有没有引擎、有哪些音色。 */
    public static String info(Context c) {
        ensure(c);
        JSONObject o = new JSONObject();
        try {
            o.put("ready", sReady);
            o.put("speaking", speaking());
            if (sLastError != null) o.put("error", sLastError);
            /*
             * 音色列表：**只留中文和本机默认语言**，最多 40 个。
             *
             * 实测这台 vivo 的引擎一次报 275 个音色（俄语、阿拉伯语、印地语…），
             * 原样丢进下拉框就成了一份没法翻的清单 —— 而用户要读的是中文回复。
             */
            List<JSONObject> zh = new ArrayList<>();
            List<JSONObject> other = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            String sysLang = Locale.getDefault().getLanguage();
            try {
                if (sEngine != null) {
                    Set<Voice> all = sEngine.getVoices();
                    if (all != null) {
                        for (Voice v : all) {
                            if (v == null || v.isNetworkConnectionRequired()) continue;
                            String lang = v.getLocale() == null ? "" : v.getLocale().getLanguage();
                            String label = v.getLocale().getDisplayName(Locale.SIMPLIFIED_CHINESE)
                                    + " · " + v.getName();
                            if (!seen.add(label)) continue;
                            JSONObject one = new JSONObject();
                            one.put("name", v.getName());
                            one.put("label", label);
                            one.put("locale", v.getLocale() == null ? "" : v.getLocale().toString());
                            if ("zh".equals(lang) || lang.equals(sysLang)) zh.add(one);
                            else other.add(one);
                        }
                    }
                }
            } catch (Throwable ignore) { }
            JSONArray voices = new JSONArray();
            for (JSONObject one : zh) {
                if (voices.length() >= 40) break;
                voices.put(one);
            }
            for (JSONObject one : other) {
                if (voices.length() >= 40) break;
                voices.put(one);
            }
            o.put("voices", voices);
        } catch (Throwable t) {
            return "{\"ready\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
        return o.toString();
    }

    // ── 云端 / 自定义 TTS ───────────────────────────────────────

    /**
     * 请求一个返回音频的接口并播放。
     *
     * @param reqJson {@code {"url":"…","method":"POST","headers":{…},"body":"…","kind":"cloud|custom"}}
     */
    public static void playHttp(final Context c, final String reqJson) {
        new Thread(() -> {
            String token = "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            sToken = token;
            sSpeaking = true;
            emit("{\"type\":\"start\"}");
            try {
                JSONObject req = new JSONObject(reqJson);
                String url = req.optString("url", "");
                String method = req.optString("method", "POST").toUpperCase(Locale.ROOT);
                String body = req.optString("body", "");
                JSONObject headers = req.optJSONObject("headers");
                String ct = headers == null ? "" : headers.optString("Content-Type", "");
                byte[] audio = fetch(url, method, headers, body);
                if (audio == null || audio.length < 64) {
                    throw new IllegalStateException("接口没有返回音频");
                }
                // 有的接口回的是 JSON（音频地址或 base64），再看一眼
                if (ct.contains("json") || looksJson(audio)) {
                    byte[] real = resolveJsonAudio(audio);
                    if (real != null) audio = real;
                }
                File f = save(c, audio);
                play(c, f.getAbsolutePath(), token);
            } catch (Throwable t) {
                sSpeaking = false;
                sToken = null;
                Log.w(TAG, "云端朗读失败", t);
                emit("{\"type\":\"error\",\"message\":\"" + esc(String.valueOf(t.getMessage())) + "\"}");
            }
        }, "dsh-tts-http").start();
    }

    private static byte[] fetch(String url, String method, JSONObject headers, String body)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                java.util.Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try { conn.setRequestProperty(k, headers.optString(k)); } catch (Throwable ignore) { }
                }
            }
            if (!"GET".equals(method) && body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                byte[] payload = body.getBytes("UTF-8");
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                }
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String msg = "HTTP " + code;
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        String s = new String(readAll(es), "UTF-8");
                        if (s.length() > 200) s = s.substring(0, 200) + "…";
                        msg += " " + s;
                    }
                } catch (Throwable ignore) { }
                throw new IllegalStateException(msg);
            }
            try (InputStream is = conn.getInputStream()) {
                return readAll(is);
            }
        } finally {
            try { conn.disconnect(); } catch (Throwable ignore) { }
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static boolean looksJson(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8); i++) {
            if (b[i] == '{' || b[i] == '[') return true;
            if (b[i] > 32) return false;
        }
        return false;
    }

    /** 从 JSON 响应里把音频捞出来：base64 字段或一个音频地址。 */
    private static byte[] resolveJsonAudio(byte[] raw) throws Exception {
        String s = new String(raw, "UTF-8").trim();
        if (s.length() > 8 * 1024 * 1024) throw new IllegalStateException("响应过大");
        JSONObject o = new JSONObject(s);
        for (String key : new String[]{"audio", "audio_base64", "audioBase64", "data", "b64"}) {
            String v = o.optString(key, "");
            if (v.length() > 64) {
                int comma = v.indexOf(',');
                if (v.startsWith("data:") && comma > 0) v = v.substring(comma + 1);
                return android.util.Base64.decode(v, android.util.Base64.DEFAULT);
            }
        }
        for (String key : new String[]{"url", "audio_url", "audioUrl"}) {
            String v = o.optString(key, "");
            if (v.startsWith("http")) return fetch(v, "GET", null, null);
        }
        return null;
    }

    private static File save(Context c, byte[] data) throws Exception {
        // 放 App 自己的缓存目录：不占用户相册、也不需要存储权限
        if (sAudioDir == null) sAudioDir = new File(c.getCacheDir(), "tts");
        if (!sAudioDir.isDirectory() && !sAudioDir.mkdirs()) {
            throw new IllegalStateException("建不了缓存目录");
        }
        // 只留最近几个，别把用户存储当垃圾场
        File[] old = sAudioDir.listFiles();
        if (old != null && old.length > 6) {
            java.util.Arrays.sort(old, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < old.length - 4; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
        }
        File f = new File(sAudioDir, "tts-" + System.currentTimeMillis() + ".audio");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f;
    }

    private static void play(Context c, String path, String token) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                stopPlayer();
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                p.setDataSource(path);
                p.setOnCompletionListener(mp -> {
                    if (!token.equals(sToken)) return;
                    sSpeaking = false;
                    sPlayer = null;
                    try { mp.release(); } catch (Throwable ignore) { }
                    emit("{\"type\":\"done\"}");
                });
                p.setOnErrorListener((mp, what, extra) -> {
                    sSpeaking = false;
                    sPlayer = null;
                    try { mp.release(); } catch (Throwable ignore) { }
                    emit("{\"type\":\"error\",\"message\":\"音频播放失败（" + what + "）\"}");
                    return true;
                });
                p.prepare();
                p.start();
                sPlayer = p;
            } catch (Throwable t) {
                sSpeaking = false;
                Log.w(TAG, "播放失败", t);
                emit("{\"type\":\"error\",\"message\":\"" + esc("播放失败：" + t.getMessage()) + "\"}");
            }
        });
    }

    // ── 小工具 ─────────────────────────────────────────────────

    /** 按句子切块：优先标点，其次硬切，保证每块不超过 max。 */
    private static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text.replace('\r', ' ').trim();
        while (rest.length() > max) {
            int cut = -1;
            for (int i = max; i > max / 3; i--) {
                char ch = rest.charAt(i);
                if (ch == '。' || ch == '！' || ch == '？' || ch == '\n' || ch == '.' || ch == '!'
                        || ch == '?' || ch == '；' || ch == ';') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut < 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private static void emit(String json) {
        Sink s = sSink;
        if (s == null) return;
        try { s.emit(json); } catch (Throwable ignore) { }
    }

    /** JSON 字符串里嵌中文/引号时要转义，否则网页侧 JSON.parse 会炸。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(' ');
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
