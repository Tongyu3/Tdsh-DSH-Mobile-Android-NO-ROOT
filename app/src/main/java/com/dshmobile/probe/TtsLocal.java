package com.dshmobile.probe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地模型朗读：在手机 CPU 上跑开源 TTS 模型（sherpa-onnx）。
 *
 * <h3>两种模型</h3>
 * <ul>
 *   <li><b>VITS / Matcha</b>：固定音色的中文模型（60–150MB），一句话几百毫秒到一两秒；</li>
 *   <li><b>ZipVoice</b>：**零样本克隆** —— 给一段几秒的参考人声，就能用那个音色说话。
 *       内置的"纳西妲"就是这条路：参考人声是打包在 assets 里的 {@code nahida.wav}。</li>
 * </ul>
 *
 * <h3>发热控制（用户明确要求：不能像以前那样久用就烫）</h3>
 * <ol>
 *   <li><b>只在念的时候算</b>：不开常驻线程、不轮询。一次朗读 = 一个后台线程，
 *       念完线程就退出；没有活儿的时候这段代码一个 CPU 周期都不占。</li>
 *   <li><b>限定线程数</b>：{@code numThreads=2}。手机 SoC 上 4 线程能让合成快一倍，
 *       但那是"整机功耗翻倍"的代价 —— 朗读是长时间任务，宁可慢一点。</li>
 *   <li><b>模型用完就卸</b>：最后一次合成结束 3 分钟没有新任务，就把推理会话释放掉
 *       （{@link #RELEASE_AFTER_MS}）。一直挂着会占着一大块内存，也让系统更容易
 *       在后台把它当成"活跃进程"。</li>
 *   <li><b>文本有上限</b>：{@link #MAX_CHARS}。本地合成比云端慢得多，
 *       几千字念下来就是几分钟的满载 CPU —— 那才是"烫"的来源。超出部分直接不念。</li>
 *   <li><b>一句一句来</b>：按句切块合成 + 播放，中间有自然的间隙，
 *       既能让"停止"立刻生效，也不会让 CPU 连续满载。</li>
 * </ol>
 */
final class TtsLocal {

    private static final String TAG = "DSH_TTS_LOCAL";

    /** 本地合成最多念多少字（超出不念，见类注释第 4 条）。 */
    static final int MAX_CHARS = 1200;

    /** 合成用的线程数（发热与速度的折中，见类注释第 2 条）。 */
    private static final int THREADS = 2;

    /** 一句话最长多少字（切块粒度）。 */
    private static final int CHUNK = 180;

    /** 多久没任务就释放推理会话。 */
    private static final long RELEASE_AFTER_MS = 180_000L;

    private TtsLocal() { }

    // ── 状态 ────────────────────────────────────────────────
    private static final Object LOCK = new Object();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Runnable RELEASE = new Runnable() {
        @Override public void run() { release(); }
    };

    /** 当前推理会话与它对应的模型 id（换模型要重建）。 */
    private static volatile OfflineTts sTts;
    private static volatile String sLoadedId;
    private static volatile String sLastError;

    /** 播放状态。 */
    private static volatile AudioTrack sTrack;
    private static volatile boolean sSpeaking;
    /** 每次 speak 自增；旧线程发现自己的代号过期就立刻退，避免两段声音叠在一起。 */
    private static volatile int sGen;
    private static volatile boolean sStop;

    /** 后台合成线程（一次一个，念完退出）。 */
    private static volatile Thread sWorker;

    static boolean speaking() {
        return sSpeaking;
    }

    static String lastError() {
        return sLastError;
    }

    /** 模型装好了没（只需要问目录，不加载任何东西）。 */
    static int installedCount(Context c) {
        return TtsModels.list(c).size();
    }

    // ── 对外入口 ────────────────────────────────────────────

    /**
     * 用某个本地模型念一段文字。
     *
     * @param modelId 模型 id（{@link TtsModels.Model#id}）
     * @param speed   语速
     */
    static void speak(final Context c, final String modelId, final String text,
                      final float speed, final String token, final Tts.Sink sink) {
        final TtsModels.Model m = TtsModels.get(c, modelId);
        if (m == null) {
            emit(sink, "{\"type\":\"error\",\"message\":\"还没装这个本地模型\"}");
            return;
        }
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) return;
        if (body.length() > MAX_CHARS) body = body.substring(0, MAX_CHARS);

        final int myGen;
        synchronized (LOCK) {
            myGen = ++sGen;
            sStop = false;
        }
        stopPlayback();          // 上一段立刻静音（合成线程会看到代号变了而退出）
        UI.removeCallbacks(RELEASE);
        sLastError = null;

        final String bodyF = body;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                runSpeak(c, m, bodyF, speed, myGen, token, sink);
            }
        }, "dsh-tts-local");
        t.setDaemon(true);
        sWorker = t;
        t.start();
    }

    /** 立刻停（用户点停止 / 关掉自动朗读 / 页面切走）。 */
    static void stop() {
        synchronized (LOCK) {
            sStop = true;
            sGen++;
        }
        stopPlayback();
    }

    /** 释放推理会话（退出前 / 长时间不用）。 */
    static void release() {
        OfflineTts t = sTts;
        sTts = null;
        sLoadedId = null;
        if (t != null) {
            try { t.release(); } catch (Throwable ignore) { }
            try { t.free(); } catch (Throwable ignore) { }
        }
    }

    // ── 合成 + 播放 ─────────────────────────────────────────

    private static void runSpeak(Context c, TtsModels.Model m, String text, float speed,
                                 int myGen, String token, Tts.Sink sink) {
        AudioTrack track = null;
        try {
            OfflineTts tts = ensure(c, m, sink);
            if (tts == null || myGen != sGen) return;

            int sr = tts.sampleRate();
            if (sr <= 0) throw new IllegalStateException("模型没给出采样率");
            track = newTrack(sr);
            sTrack = track;
            track.play();

            List<String> parts = split(text, CHUNK);
            boolean started = false;
            for (String part : parts) {
                if (myGen != sGen || sStop) break;
                if (part.trim().isEmpty()) continue;
                GeneratedAudio audio = generate(tts, m, part, speed, sink);
                if (audio == null) continue;
                float[] samples = audio.getSamples();
                if (samples == null || samples.length == 0) continue;
                /*
                 * 采样率以**这一段的音频**为准（模型自己报的），不要用 tts.sampleRate()：
                 * 有的模型 decoder 输出 24k、vocoder 却是 22.05k，按错的采样率播出来
                 * 会变成"快放/慢放"，而且听起来像音色坏了 —— 很难联想到是采样率。
                 */
                if (track == null) {
                    track = newTrack(audio.getSampleRate() > 0 ? audio.getSampleRate() : sr);
                    sTrack = track;
                    track.play();
                }
                if (!started) {
                    started = true;
                    sSpeaking = true;
                    emit(sink, "{\"type\":\"start\"}");
                }
                if (!playBlocking(track, samples, myGen)) break;
            }

            if (started && myGen == sGen && !sStop) {
                emit(sink, "{\"type\":\"done\"}");
            } else if (started && (sStop || myGen != sGen)) {
                emit(sink, "{\"type\":\"stopped\"}");
            }
        } catch (Throwable t) {
            sLastError = String.valueOf(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            android.util.Log.e(TAG, "本地合成失败", t);
            emit(sink, "{\"type\":\"error\",\"message\":\"" + Tts.esc(sLastError) + "\"}");
        } finally {
            sSpeaking = false;
            sTrack = null;
            if (track != null) {
                try { track.pause(); } catch (Throwable ignore) { }
                try { track.flush(); } catch (Throwable ignore) { }
                try { track.stop(); } catch (Throwable ignore) { }
                try { track.release(); } catch (Throwable ignore) { }
            }
            // 念完排一次"到点卸载"，中间有新任务会把它取消掉
            UI.removeCallbacks(RELEASE);
            UI.postDelayed(RELEASE, RELEASE_AFTER_MS);
        }
    }

    /** 懒加载推理会话：模型换了就重建，没换就复用（加载一次要 1–3 秒，不该每句都来一遍）。 */
    private static OfflineTts ensure(Context c, TtsModels.Model m, Tts.Sink sink) {
        synchronized (LOCK) {
            OfflineTts cur = sTts;
            if (cur != null && m.id.equals(sLoadedId)) return cur;
            if (cur != null) {
                try { cur.release(); } catch (Throwable ignore) { }
                try { cur.free(); } catch (Throwable ignore) { }
                sTts = null;
                sLoadedId = null;
            }
            emit(sink, "{\"type\":\"loading\",\"message\":\"加载模型…\"}");
            try {
                trace("load: " + m.kind + " " + m.name + " dir=" + m.dir);
                OfflineTts built = TtsModels.KIND_VITS.equals(m.kind)
                        ? buildVits(c, m)
                        : TtsModels.KIND_MATCHA.equals(m.kind) ? buildMatcha(c, m) : buildZipVoice(c, m);
                trace("load: ok sr=" + built.sampleRate());
                sTts = built;
                sLoadedId = m.id;
                return built;
            } catch (Throwable t) {
                sLastError = String.valueOf(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                android.util.Log.e(TAG, "加载模型失败: " + m.name, t);
                emit(sink, "{\"type\":\"error\",\"message\":\"加载模型失败：" + Tts.esc(sLastError) + "\"}");
                return null;
            }
        }
    }

    private static OfflineTts buildVits(Context c, TtsModels.Model m) {
        OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
        v.setModel(path(m, m.model));
        v.setTokens(path(m, m.tokens));
        if (m.lexicon != null && !m.lexicon.isEmpty()) v.setLexicon(path(m, m.lexicon));
        if (m.dataDir != null && !m.dataDir.isEmpty()) v.setDataDir(path(m, m.dataDir));
        if (m.dictDir != null && !m.dictDir.isEmpty()) v.setDictDir(path(m, m.dictDir));
        OfflineTtsModelConfig mc = baseModelConfig();
        mc.setVits(v);
        return new OfflineTts(null, wrap(mc));
    }

    private static OfflineTts buildMatcha(Context c, TtsModels.Model m) {
        OfflineTtsMatchaModelConfig v = new OfflineTtsMatchaModelConfig();
        v.setAcousticModel(path(m, m.model));
        v.setVocoder(path(m, m.vocoder));
        v.setTokens(path(m, m.tokens));
        if (m.lexicon != null && !m.lexicon.isEmpty()) v.setLexicon(path(m, m.lexicon));
        if (m.dataDir != null && !m.dataDir.isEmpty()) v.setDataDir(path(m, m.dataDir));
        if (m.dictDir != null && !m.dictDir.isEmpty()) v.setDictDir(path(m, m.dictDir));
        OfflineTtsModelConfig mc = baseModelConfig();
        mc.setMatcha(v);
        return new OfflineTts(null, wrap(mc));
    }

    private static OfflineTts buildZipVoice(Context c, TtsModels.Model m) {
        OfflineTtsZipVoiceModelConfig z = new OfflineTtsZipVoiceModelConfig();
        z.setEncoder(path(m, m.encoder));
        z.setDecoder(path(m, m.decoder));
        // 这个包不带单独的 vocoder（distill 版把声码器合进 decoder 了），有才设
        if (m.vocoder != null && !m.vocoder.isEmpty()) z.setVocoder(path(m, m.vocoder));
        if (m.tokens != null && !m.tokens.isEmpty()) z.setTokens(path(m, m.tokens));
        if (m.dataDir != null && !m.dataDir.isEmpty()) z.setDataDir(path(m, m.dataDir));
        if (m.lexicon != null && !m.lexicon.isEmpty()) z.setLexicon(path(m, m.lexicon));
        OfflineTtsModelConfig mc = baseModelConfig();
        mc.setZipvoice(z);
        return new OfflineTts(null, wrap(mc));
    }

    private static OfflineTtsModelConfig baseModelConfig() {
        OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
        mc.setNumThreads(THREADS);
        mc.setProvider("cpu");
        mc.setDebug(false);
        return mc;
    }

    /*
     * ⚠️ 构造 OfflineTts 时 **AssetManager 必须传 null**。
     *
     * <p>sherpa 的 Kotlin 绑定是这么写的：
     * <pre>
     *   class OfflineTts(assetManager: AssetManager? = null, config: OfflineTtsConfig) {
     *       init { ptr = if (assetManager == null) newFromFile(config) else newFromAsset(assetManager, config) }
     *   }
     * </pre>
     * 也就是说：**传了 assetManager 就变成"从 assets 里找模型"**。我们的模型在私有目录
     * （绝对路径），assets 里当然找不到 → 原生层直接 abort，进程 exit 255，
     * 界面上是"点试听，App 没了"，Java 侧一个异常都接不到。
     *
     * <p>踩过：第一版传的是 c.getAssets()，靠"每一步先落一行日志"才定位到这一行。
     */

    private static OfflineTtsConfig wrap(OfflineTtsModelConfig mc) {
        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(mc);
        cfg.setMaxNumSentences(1);      // 我们自己切句，别让它再按标点堆句子（延迟更低、更好停）
        return cfg;
    }

    /** 真正合成一句。ZipVoice 要带参考人声，其他类型直接 generate。 */
    private static GeneratedAudio generate(OfflineTts tts, TtsModels.Model m, String text,
                                           float speed, Tts.Sink sink) {
        if (TtsModels.KIND_ZIPVOICE.equals(m.kind)) {
            float[] ref = referenceSamples(m, sink);
            if (ref == null || ref.length < 1000) {
                emit(sink, "{\"type\":\"error\",\"message\":\"参考音色读不出来\"}");
                return null;
            }
            GenerationConfig gc = new GenerationConfig();
            gc.setSpeed(speed <= 0 ? 1f : speed);
            gc.setSid(0);
            gc.setReferenceAudio(ref);
            gc.setReferenceSampleRate(sRefRate);
            if (m.referenceText != null && !m.referenceText.isEmpty()) gc.setReferenceText(m.referenceText);
            if (m.numSteps > 0) gc.setNumSteps(m.numSteps);
            /*
             * 这几个字段**必须给非 null**。
             *
             * <p>Kotlin 的 data class 默认值是 null，而 JNI 那边是直接遍历 / 拷这些数组的
             * （C++ 侧没有判空）。null 传下去不是抛 Java 异常，而是**原生层直接 abort**
             * —— 进程 exit 255，界面上表现为"点了试听，App 直接没了"，日志里一个字都没有。
             * 实测就是这么崩的，所以这里全部显式塞空容器。
             */
            gc.setExtra(new java.util.HashMap<String, String>());
            gc.setPhonemeCodepoints(new int[0][]);
            gc.setTokens(new String[0][]);
            trace("generate: zipvoice 时长=" + ref.length + "@" + sRefRate + "hz steps=" + m.numSteps);
            GeneratedAudio a = tts.generateWithConfig(text, gc);
            trace("generate: 返回 " + (a == null ? "null" : a.getSamples().length + " 采样"));
            return a;
        }
        trace("generate: " + m.kind);
        GeneratedAudio a = tts.generate(text, 0, speed <= 0 ? 1f : speed);
        trace("generate: 返回 " + (a == null ? "null" : a.getSamples().length + " 采样"));
        return a;
    }

    /**
     * 把一行诊断写进 {@code /sdcard/.dsh-phone/tts-local.log}。
     *
     * <p>为什么非要有：本地推理崩的时候是**原生层 abort**，Java 的 catch 根本轮不到，
     * logcat 在部分国产 ROM 上又收不到本应用的日志。于是"崩在哪一步"完全看不见 ——
     * 只能在每一步之前先落一行盘，崩完打开文件看最后一行停在哪。
     */
    static void trace(String line) {
        try {
            java.io.File dir = new java.io.File("/sdcard/.dsh-phone");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "tts-local.log");
            if (f.length() > 64 * 1024) f.delete();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date());
            fo.write((ts + "  " + line + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignore) { }
    }

    /** 参考人声的采样点（读一次缓存起来）。 */
    private static volatile float[] sRef;
    private static volatile int sRefRate;
    private static volatile String sRefPath;

    private static float[] referenceSamples(TtsModels.Model m, Tts.Sink sink) {
        String p = m.reference;
        if (p == null || p.isEmpty()) return null;
        float[] cur = sRef;
        if (cur != null && p.equals(sRefPath)) return cur;
        try {
            File f = p.startsWith("/") ? new File(p) : new File(m.dir, p);
            int[] rate = {0};
            float[] data = TtsModels.readWav(f, rate);
            sRef = data;
            sRefRate = rate[0];
            sRefPath = p;
            return data;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读参考音频失败: " + p, t);
            return null;
        }
    }

    // ── 播放 ────────────────────────────────────────────────

    private static AudioTrack newTrack(int sr) {
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min < 0) min = sr / 4;
        int buf = Math.max(min, sr / 2);        // 半秒缓冲，够稳又不至于"按停止还在响"
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        return new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(buf)
                .build();
    }

    /** 阻塞写入一段采样，可被 stop 打断。返回 false 表示被打断。 */
    private static boolean playBlocking(AudioTrack track, float[] samples, int myGen) {
        short[] buf = new short[4096];
        int i = 0;
        while (i < samples.length) {
            if (myGen != sGen || sStop) return false;
            int n = Math.min(buf.length, samples.length - i);
            for (int k = 0; k < n; k++) {
                float v = samples[i + k];
                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                buf[k] = (short) (v * 32767f);
            }
            int wrote = track.write(buf, 0, n);
            if (wrote <= 0) return false;
            i += wrote;
        }
        return true;
    }

    private static void stopPlayback() {
        AudioTrack t = sTrack;
        if (t == null) return;
        try { t.pause(); } catch (Throwable ignore) { }
        try { t.flush(); } catch (Throwable ignore) { }
    }

    // ── 小工具 ──────────────────────────────────────────────

    private static String path(TtsModels.Model m, String rel) {
        if (rel == null) return "";
        if (rel.startsWith("/")) return rel;
        return new File(m.dir, rel).getAbsolutePath();
    }

    private static void emit(Tts.Sink sink, String json) {
        if (sink == null) return;
        try { sink.emit(json); } catch (Throwable ignore) { }
    }

    /** 按句切块（复用系统语音那套规则：优先标点，其次硬切）。 */
    private static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text.replace('\r', ' ').trim();
        while (rest.length() > max) {
            int cut = -1;
            for (int i = max; i > max / 3; i--) {
                char ch = rest.charAt(i);
                if (ch == '。' || ch == '！' || ch == '？' || ch == '\n' || ch == '.' || ch == '!'
                        || ch == '?' || ch == '；' || ch == ';' || ch == '，' || ch == ',') {
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
}
