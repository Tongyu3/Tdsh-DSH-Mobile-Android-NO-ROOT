package com.dshmobile.probe;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 「本地语音模型」的仓库：下载、解包、登记、删除、读参考音频。
 *
 * <h3>为什么要有这一层</h3>
 * 用户要的是"自己找开源模型，把链接粘进去就能用"。所以必须有一块**可写的模型目录**、
 * 一套**能认出这是什么模型**的规则（VITS / Matcha / ZipVoice 的文件名不一样），
 * 以及一个**能下载 + 解包**（发布出来的模型几乎都是 {@code .tar.bz2}）的实现。
 * 这里只管"文件从网上下到手机、摆成推理能直接读的目录"，不碰任何推理代码。
 *
 * <h3>目录约定</h3>
 * <pre>
 *   files/tts-models/&lt;id&gt;/            一个模型一个目录
 *        tts-model.json              清单（下面这些字段）
 *        *.onnx / tokens.txt / ...   原始模型文件（原样解出来的）
 *   files/tts-ref/nahida.wav         内置的参考人声（见 copyBundledReference）
 * </pre>
 *
 * <h3>发热</h3>
 * 这一层唯一会长时间占 CPU 的是下载（网络等待，不是算）和解包（几秒的 IO）。
 * 两者都跑在调用方的后台线程里，**不常驻、不轮询**；下载完成就退出。
 */
final class TtsModels {

    private static final String TAG = "DSH_TTS_MODEL";

    /** 模型根目录名。 */
    private static final String DIR = "tts-models";
    /** 每个模型目录里的清单文件名。 */
    private static final String MANIFEST = "tts-model.json";

    // ── 模型种类 ─────────────────────────────────────────────
    static final String KIND_VITS = "vits";
    static final String KIND_MATCHA = "matcha";
    static final String KIND_ZIPVOICE = "zipvoice";

    private TtsModels() { }

    /** 一个已安装的模型。字段就是清单内容，另外带上目录与体积。 */
    static final class Model {
        String id = "";
        String name = "";
        String kind = KIND_VITS;
        File dir;

        // VITS / Matcha
        String model = "";
        String tokens = "";
        String lexicon = "";
        String dataDir = "";
        String dictDir = "";

        // ZipVoice（零样本克隆：给一段参考人声就能用）
        String encoder = "";
        String decoder = "";
        String vocoder = "";

        /** 参考人声（绝对路径或相对目录的文件名）。只在 ZipVoice 上用。 */
        String reference = "";
        /** 参考人声里说的那句话（可以不填，填了效果更好）。 */
        String referenceText = "";
        /** ZipVoice 的步数（越小越快、越抖；默认交给运行时）。 */
        int numSteps = 0;

        long bytes;

        /** 给网页看的摘要。 */
        String toJson() {
            return "{\"id\":\"" + Tts.esc(id) + "\",\"name\":\"" + Tts.esc(name)
                    + "\",\"kind\":\"" + Tts.esc(kind) + "\",\"mb\":" + (bytes / 1048576L)
                    + ",\"ref\":" + (reference != null && !reference.isEmpty()) + "}";
        }
    }

    /** 进度回调（下载百分比 / 正在做什么）。 */
    interface Progress {
        void onProgress(int pct, String step);
    }

    // ── 目录 ────────────────────────────────────────────────

    static File root(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File refDir(Context c) {
        File d = new File(c.getFilesDir(), "tts-ref");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ── 读清单 / 列表 ────────────────────────────────────────

    /** 列出全部已安装模型（按名字排序）。 */
    static List<Model> list(Context c) {
        List<Model> out = new ArrayList<>();
        File[] kids = root(c).listFiles();
        if (kids == null) return out;
        Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : kids) {
            if (!d.isDirectory()) continue;
            Model m = read(c, d);
            if (m != null) out.add(m);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static Model get(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        File d = new File(root(c), id);
        return d.isDirectory() ? read(c, d) : null;
    }

    /** 读一个目录里的清单；坏了就返回 null（宁可不显示，也不让推理去读半个模型）。 */
    private static Model read(Context c, File dir) {
        File f = new File(dir, MANIFEST);
        if (!f.isFile()) return null;
        try {
            String s = new String(readAll(new FileInputStream(f)), "UTF-8");
            Model m = new Model();
            m.dir = dir;
            m.id = str(s, "id", dir.getName());
            m.name = str(s, "name", m.id);
            m.kind = str(s, "kind", KIND_VITS);
            m.model = str(s, "model", "");
            m.tokens = str(s, "tokens", "");
            m.lexicon = str(s, "lexicon", "");
            m.dataDir = str(s, "dataDir", "");
            m.dictDir = str(s, "dictDir", "");
            m.encoder = str(s, "encoder", "");
            m.decoder = str(s, "decoder", "");
            m.vocoder = str(s, "vocoder", "");
            m.reference = str(s, "reference", "");
            m.referenceText = str(s, "referenceText", "");
            m.numSteps = num(s, "numSteps", 0);
            m.bytes = dirSize(dir);
            // 必需文件不在了（用户手动删过）→ 当作没装
            if (KIND_ZIPVOICE.equals(m.kind)) {
                if (!ok(dir, m.encoder) || !ok(dir, m.decoder)) return null;
            } else {
                if (!ok(dir, m.model) || !ok(dir, m.tokens)) return null;
            }
            return m;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读模型清单失败: " + dir, t);
            return null;
        }
    }

    private static boolean ok(File dir, String rel) {
        if (rel == null || rel.isEmpty()) return false;
        File f = rel.startsWith("/") ? new File(rel) : new File(dir, rel);
        return f.isFile() && f.length() > 0;
    }

    static boolean remove(Context c, String id) {
        File d = new File(root(c), id);
        if (!d.isDirectory()) return false;
        return deleteTree(d);
    }

    private static boolean deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        return f.delete();
    }

    private static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += dirSize(k);
        return n;
    }

    // ── 内置参考人声（纳西妲）────────────────────────────────

    /**
     * 把打包在 assets 里的参考人声落到私有目录，返回文件。
     *
     * <p>为什么要落到文件：Sherpa 的零样本接口要 {@code float[]} 采样点 + 采样率，
     * 直接读 assets 也能做，但读一次要解一次压缩包；落到文件后可以按需读、也能让
     * 用户看到"这段就是纳西妲的参考音色"。只写一次（长度对得上就复用）。
     */
    static File bundledReference(Context c) {
        File out = new File(refDir(c), "nahida.wav");
        try {
            long want = -1;
            try (InputStream in = c.getAssets().open("nahida.wav")) {
                byte[] head = new byte[64];
                int n = in.read(head);
                byte[] all = concat(head, n, readAll(in));
                want = all.length;
                if (out.isFile() && out.length() == want) return out;
                try (OutputStream o = new FileOutputStream(out)) { o.write(all); }
                return out;
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "释放内置参考音频失败", t);
            return out.isFile() ? out : null;
        }
    }

    private static byte[] concat(byte[] a, int an, byte[] b) {
        byte[] out = new byte[an + b.length];
        System.arraycopy(a, 0, out, 0, an);
        System.arraycopy(b, 0, out, an, b.length);
        return out;
    }

    // ── 下载 + 安装 ─────────────────────────────────────────

    /**
     * ZipVoice 的**声码器**（公有模型，54MB）。
     *
     * <p>发布包里**没有**它 —— 官方文档明确写了要另外下 {@code vocos_24khz.onnx}。
     * 第一次装 zipvoice 类模型时自动补上（同一份声码器所有 zipvoice 模型通用）。
     * 缺了它的后果不是"声音难听"，而是**原生层直接 abort 把整个 App 弄崩**
     * （实测：进程 exit 255，界面上只看到"点了试听，App 没了"）。
     */
    static final String VOCODER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx";
    static final String VOCODER_NAME = "vocos_24khz.onnx";

    /**
     * 从链接安装一个模型（{@code .tar.bz2} / {@code .zip} / {@code .tar.gz} 都收）。
     *
     * <p>解包 → 认文件 → 写清单 → 改名成正式目录。任何一步失败都会把临时目录删掉，
     * 不会留下"看着装了其实半截"的目录。
     *
     * @param idHint   指定的 id（内置模型用固定 id，方便重复安装时覆盖）；null 则按文件名生成
     * @param name     显示名（null 则按文件名）
     * @param ref      参考人声路径（ZipVoice 用；其他类型传 null）
     * @param refText  参考人声对应的文字（ZipVoice 必须给，和音频对得上才像；其他类型传 null）
     */
    static Model install(Context c, String url, String idHint, String name, String ref, String refText,
                         Progress cb) throws Exception {
        if (url == null || url.trim().isEmpty()) throw new IOException("链接是空的");
        url = url.trim();
        File root = root(c);
        File tmp = new File(root, ".incoming-" + System.currentTimeMillis());
        if (!tmp.mkdirs()) throw new IOException("建不了临时目录");
        File archive = null;
        boolean done = false;
        try {
            archive = download(c, url, cb);
            if (cb != null) cb.onProgress(93, "解包");
            File tree = unpack(archive, tmp);
            if (cb != null) cb.onProgress(96, "识别模型");
            String display = (name != null && !name.isEmpty()) ? name : prettyName(nameFromUrl(url));
            Model m = detect(c, tree, display);
            m.reference = ref == null ? "" : ref;
            m.referenceText = refText == null ? "" : refText;
            if (KIND_ZIPVOICE.equals(m.kind)) {
                m.numSteps = 4;      // 官方对这个 distill 模型的推荐值（快 4 倍，音质够用）
                if (m.vocoder == null || m.vocoder.isEmpty()) {
                    // 发布包不带声码器 → 自动补一份（缺了会让原生层 abort）
                    if (cb != null) cb.onProgress(96, "补声码器（54MB）");
                    File voc = download(c, VOCODER_URL, cb);
                    File dst = new File(tree, VOCODER_NAME);
                    copyFile(voc, dst);
                    m.vocoder = VOCODER_NAME;
                    if (voc.exists()) voc.delete();
                }
            }

            String id = (idHint != null && !idHint.isEmpty()) ? idHint : slug(m.name);
            m.id = id;
            writeManifest(tree, m);

            File dst = new File(root, id);
            if (dst.exists()) deleteTree(dst);
            if (!tree.renameTo(dst)) {
                // rename 在跨文件系统/占用时会失败 → 退化成拷贝
                copyTree(tree, dst);
                deleteTree(tree);
            }
            m.dir = dst;
            m.bytes = dirSize(dst);
            if (cb != null) cb.onProgress(100, "完成");
            done = true;
            return read(c, dst);
        } finally {
            /*
             * 只在**成功**之后删掉下载的压缩包。
             *
             * 失败时留着它（连同 .ok 标记）：用户重试时直接复用，不用再等十几分钟。
             * 这条是实测踩出来的 —— 原来无条件删，一次解包报错就把刚下好的 104MB 扔了，
             * 用户点第二次又是从头下载，看着像"每次装都要重下"。
             */
            if (archive != null && archive.exists() && done) archive.delete();
            if (tmp.exists()) deleteTree(tmp);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream o = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    /**
     * 下载到缓存目录。
     *
     * <p>URL 会按顺序尝试：原地址 → 两个 GitHub 反代。为什么要反代：
     * 手机端不一定能连上 GitHub 的 release 下载域（release-assets.githubusercontent.com），
     * 而模型发布页正好都在那儿。反代只对 github.com 的链接追加，别的域名直接跳过。
     */
    private static File download(Context c, String url, Progress cb) throws Exception {
        File dir = new File(c.getCacheDir(), "tts-dl");
        if (!dir.exists()) dir.mkdirs();
        String name = nameFromUrl(url);
        File out = new File(dir, name);
        /*
         * 下完的文件旁边放一个同名 `.ok` 空文件当"完整"标记。
         *
         * <p>为什么需要：模型动辄一百多 MB，手机网络断一次就得从头再来 —— 用户看到的
         * 是"下载到 60% 又回 0%"。有了标记，同一份文件重试时直接复用。
         *
         * <p>⚠️ 标记必须是**单独的文件**，不能把档案本身改名成 `.xxx.done`：
         * 解包那一步是按**后缀**判格式的，名字一变就变成"不认的压缩格式"
         * （实测踩过：下好了、解包时才报格式错，看着像下载坏了）。
         */
        File ok = new File(dir, name + ".ok");
        if (ok.exists() && out.length() > 1024) {
            if (cb != null) cb.onProgress(90, "用已下好的 " + mb(out.length()));
            return out;
        }
        if (out.exists() && out.length() > 1024) out.delete();
        if (ok.exists()) ok.delete();
        String[] tries = candidates(url);
        Exception last = null;
        for (int i = 0; i < tries.length; i++) {
            String u = tries[i];
            try {
                if (cb != null) cb.onProgress(0, (i == 0 ? "连接中…" : "换线路重试…"));
                fetch(u, out, cb);
                if (out.length() > 1024) {
                    try { ok.createNewFile(); } catch (Exception ignore) { }
                    return out;
                }
                last = new IOException("下载到的文件太小: " + out.length());
            } catch (Exception e) {
                last = e;
                android.util.Log.w(TAG, "下载失败（" + host(u) + "）: " + e);
            }
            if (out.exists()) out.delete();
        }
        throw last != null ? last : new IOException("下载失败");
    }

    private static String[] candidates(String url) {
        if (url.contains("github.com/") || url.contains("githubusercontent.com/")) {
            return new String[]{
                    url,
                    "https://ghproxy.net/" + url,
                    "https://gh-proxy.com/" + url,
            };
        }
        return new String[]{url};
    }

    private static String host(String u) {
        try { return new URL(u).getHost(); } catch (Exception e) { return "?"; }
    }

    private static void fetch(String url, File out, Progress cb) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Tdsh/1.0 (Android)");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            long done = 0;
            long lastAt = 0;
            long startAt = System.currentTimeMillis();
            int lastPct = -1;
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                 OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    /*
                     * ★ 太慢就换线路（实测必踩）。
                     *
                     * 手机直连 GitHub 的下载域能连上，但速度经常只有 30–40KB/s ——
                     * 109MB 的模型要下 45 分钟，界面一直显示"下载 3%"，
                     * 用户只会以为卡死了。而反代通常快一个数量级。
                     *
                     * 判据：前 15 秒如果拿到不到 400KB（< 27KB/s），就当这条线路不行，
                     * 抛出去让 download() 试下一个。已经下到的字节会随文件一起丢掉，
                     * 所以卡住的那 15 秒不白等太久。
                     */
                    if (now - startAt > 15000L && done < 400 * 1024) {
                        throw new IOException("线路太慢（" + (done / 1024) + "KB/15s），换下一条");
                    }
                    if (now - lastAt > 400) {
                        lastAt = now;
                        int pct = total > 0 ? (int) (done * 90 / total) : 0;
                        if (pct != lastPct && cb != null) {
                            lastPct = pct;
                            cb.onProgress(pct, "下载 " + mb(done) + (total > 0 ? " / " + mb(total) : ""));
                        }
                    }
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String mb(long b) {
        return String.format(Locale.ROOT, "%.1fMB", b / 1048576.0);
    }

    /** 解包（tar.bz2 / tar.gz / zip / 裸 tar）。返回解出来的"模型根目录"。 */
    private static File unpack(File archive, File into) throws Exception {
        String n = archive.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) unzip(archive, into);
        else if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz")) untar(archive, into, true);
        else if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) untar(archive, into, false);
        else if (n.endsWith(".tar")) untar(archive, into, false);
        else throw new IOException("不认的压缩格式：" + n + "（要 .tar.bz2 / .tar.gz / .zip）");
        File tree = into;
        // 发布包通常带一层顶层目录 → 往下钻到"真正装模型文件的那一层"
        for (int i = 0; i < 3; i++) {
            File[] kids = tree.listFiles();
            if (kids == null) break;
            File onlyDir = null;
            int dirs = 0, files = 0;
            for (File k : kids) {
                if (k.isDirectory()) { dirs++; onlyDir = k; } else files++;
            }
            if (dirs == 1 && files == 0) tree = onlyDir;
            else break;
        }
        return tree;
    }

    private static void untar(File f, File into, boolean bz2) throws Exception {
        InputStream fin = new BufferedInputStream(new FileInputStream(f), 64 * 1024);
        InputStream zin = bz2 ? new BZip2CompressorInputStream(fin, true) : new GzipCompressorInputStream(fin, true);
        try (TarArchiveInputStream tin = new TarArchiveInputStream(zin)) {
            TarArchiveEntry e;
            while ((e = tin.getNextTarEntry()) != null) {
                File out = safeChild(into, e.getName());
                if (out == null) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = tin.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    private static void unzip(File f, File into) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                File out = safeChild(into, e.getName());
                if (out == null) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zin.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    /**
     * 防"压缩包路径穿越"（{@code ../../} 写到别的目录去）。
     *
     * <p>虽然模型都是我们指定的来源，但这条路径将来要接用户自己粘的任何链接 ——
     * 一个恶意的 tar 就能往 App 私有目录外写文件。所以这里必须挡。
     */
    private static File safeChild(File base, String name) {
        if (name == null) return null;
        String clean = name.replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        if (clean.startsWith("/") || clean.contains("..")) return null;
        File out = new File(base, clean);
        try {
            String bp = base.getCanonicalPath();
            String op = out.getCanonicalPath();
            if (!op.startsWith(bp + File.separator) && !op.equals(bp)) return null;
        } catch (IOException e) {
            return null;
        }
        return out;
    }

    /**
     * 认出这是哪种模型、文件都在哪。
     *
     * <p>发布包的文件名不完全统一（{@code model.onnx} / {@code xxx.onnx} /
     * {@code encoder.int8.onnx} …），所以按"关键词 + 后缀"扫一遍整棵树。
     * 认不出来就直接报错 —— 宁可让用户看到一句"这个包不像 TTS 模型"，
     * 也不要装一个运行时才炸的模型进去。
     */
    private static Model detect(Context c, File tree, String name) throws IOException {
        List<File> all = new ArrayList<>();
        collect(tree, all);
        String encoder = pick(all, tree, "encoder", ".onnx");
        String decoder = pick(all, tree, "decoder", ".onnx");
        String vocoder = pick(all, tree, "vocoder", ".onnx");
        File tokens = byName(all, "tokens.txt");
        Model m = new Model();
        m.name = name;

        if (encoder != null && decoder != null) {
            /*
             * ZipVoice 打包出来**没有单独的 vocoder**（distill-int8 那个包只有
             * encoder.int8.onnx + decoder.int8.onnx + tokens.txt + lexicon.txt + espeak-ng-data）。
             * 所以判据是"有 encoder 有 decoder"，vocoder 有就用、没有就留空 ——
             * 早先写成三个都必须有，结果真机上直接判成"不像 TTS 模型"，一个都装不上。
             */
            m.kind = KIND_ZIPVOICE;
            m.encoder = rel(tree, encoder);
            m.decoder = rel(tree, decoder);
            m.vocoder = vocoder == null ? "" : rel(tree, vocoder);
            if (tokens != null) m.tokens = rel(tree, tokens.getAbsolutePath());
            File zvLex = byName(all, "lexicon.txt");
            if (zvLex != null) m.lexicon = rel(tree, zvLex.getAbsolutePath());
            File zvData = findDir(tree, "espeak-ng-data");
            if (zvData != null) m.dataDir = rel(tree, zvData.getAbsolutePath());
            return m;
        }

        // VITS / Matcha：model.onnx + tokens.txt（Matcha 还有 acoustic-model / vocoder 两个文件）
        String acoustic = pick(all, tree, "acoustic", ".onnx");
        String voc = pick(all, tree, "vocoder", ".onnx");
        String model = pick(all, tree, "model", ".onnx");
        if (acoustic != null && voc != null) {
            m.kind = KIND_MATCHA;
            m.model = rel(tree, acoustic);
            m.vocoder = rel(tree, voc);
            if (tokens == null) throw new IOException("这个包里没有 tokens.txt");
            m.tokens = rel(tree, tokens.getAbsolutePath());
        } else if (model != null) {
            m.kind = KIND_VITS;
            m.model = rel(tree, model);
            if (tokens == null) throw new IOException("这个包里没有 tokens.txt");
            m.tokens = rel(tree, tokens.getAbsolutePath());
        } else {
            throw new IOException("这个包里找不到 .onnx 模型文件（不像 TTS 模型）");
        }
        File lex = byName(all, "lexicon.txt");
        if (lex != null) m.lexicon = rel(tree, lex.getAbsolutePath());
        /*
         * espeak-ng-data / dict 是**目录**，不在上面的文件清单里（collect 只收文件）。
         * 所以单独按常见位置找一遍：有的包把它们放在模型根目录，有的放在上一级。
         */
        File dd = findDir(tree, "espeak-ng-data");
        if (dd != null) m.dataDir = rel(tree, dd.getAbsolutePath());
        File dict = findDir(tree, "dict");
        if (dict != null) m.dictDir = rel(tree, dict.getAbsolutePath());
        return m;
    }

    /** 在树里找一个叫这个名字的目录（最多看两层，够用且不会扫太久）。 */
    private static File findDir(File tree, String name) {
        File direct = new File(tree, name);
        if (direct.isDirectory()) return direct;
        File[] kids = tree.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (!k.isDirectory()) continue;
                File hit = new File(k, name);
                if (hit.isDirectory()) return hit;
            }
        }
        return null;
    }

    private static void collect(File f, List<File> out) {
        if (out.size() > 4000 || f == null) return;
        if (f.isFile()) { out.add(f); return; }
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) collect(k, out);
    }

    /** 找"名字里带 key、后缀是 suffix"的第一个文件。 */
    private static String pick(List<File> all, File tree, String key, String suffix) {
        String best = null;
        for (File f : all) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(suffix)) continue;
            if (!n.contains(key)) continue;
            // int8/fp32 的优先级：int8 更小更快，手机上优先
            if (best == null || (n.contains("int8") && !best.toLowerCase(Locale.ROOT).contains("int8"))) {
                best = f.getAbsolutePath();
            }
        }
        return best;
    }

    private static File byName(List<File> all, String name) {
        for (File f : all) {
            if (f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    /** 绝对路径 → 相对模型根目录的路径（清单里存的都是相对路径）。 */
    private static String rel(File tree, String abs) {
        try {
            String base = tree.getCanonicalPath();
            String p = new File(abs).getCanonicalPath();
            if (p.startsWith(base + File.separator)) return p.substring(base.length() + 1);
            return p;
        } catch (IOException e) {
            return abs;
        }
    }

    private static String nameFromUrl(String url) {
        String n = url;
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        int s = n.lastIndexOf('/');
        if (s >= 0) n = n.substring(s + 1);
        if (n.isEmpty()) n = "model.tar.bz2";
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 显示名：把发布包名整理成人看得懂的样子。 */
    private static String prettyName(String file) {
        String n = file;
        String[] cut = {".tar.bz2", ".tar.gz", ".tbz2", ".tgz", ".zip", ".tar"};
        for (String c : cut) {
            if (n.toLowerCase(Locale.ROOT).endsWith(c)) {
                n = n.substring(0, n.length() - c.length());
                break;
            }
        }
        n = n.replaceFirst("(?i)^sherpa-onnx-", "").replace('_', ' ').trim();
        return n.isEmpty() ? file : n;
    }

    private static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (s.isEmpty()) s = "model";
        if (s.length() > 40) s = s.substring(0, 40);
        return s + "-" + Integer.toHexString(name == null ? 0 : name.hashCode()).substring(0, 4);
    }

    private static void writeManifest(File dir, Model m) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"id\": \"").append(Tts.esc(m.id)).append("\",\n");
        sb.append("  \"name\": \"").append(Tts.esc(m.name)).append("\",\n");
        sb.append("  \"kind\": \"").append(Tts.esc(m.kind)).append("\",\n");
        sb.append("  \"model\": \"").append(Tts.esc(m.model)).append("\",\n");
        sb.append("  \"tokens\": \"").append(Tts.esc(m.tokens)).append("\",\n");
        sb.append("  \"lexicon\": \"").append(Tts.esc(m.lexicon)).append("\",\n");
        sb.append("  \"dataDir\": \"").append(Tts.esc(m.dataDir)).append("\",\n");
        sb.append("  \"dictDir\": \"").append(Tts.esc(m.dictDir)).append("\",\n");
        sb.append("  \"encoder\": \"").append(Tts.esc(m.encoder)).append("\",\n");
        sb.append("  \"decoder\": \"").append(Tts.esc(m.decoder)).append("\",\n");
        sb.append("  \"vocoder\": \"").append(Tts.esc(m.vocoder)).append("\",\n");
        sb.append("  \"reference\": \"").append(Tts.esc(m.reference)).append("\",\n");
        sb.append("  \"referenceText\": \"").append(Tts.esc(m.referenceText)).append("\",\n");
        sb.append("  \"numSteps\": ").append(m.numSteps).append("\n");
        sb.append("}\n");
        try (OutputStream o = new FileOutputStream(new File(dir, MANIFEST))) {
            o.write(sb.toString().getBytes("UTF-8"));
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream o = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
        }
    }

    // ── 极简 JSON 取值（不引第三方库）────────────────────────

    private static String str(String json, String key, String def) {
        String v = raw(json, key);
        if (v == null) return def;
        if (v.startsWith("\"")) {
            v = v.substring(1);
            int end = v.lastIndexOf('"');
            if (end >= 0) v = v.substring(0, end);
            return v.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
        }
        return v;
    }

    private static int num(String json, String key, int def) {
        try {
            String v = raw(json, key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String raw(String json, String key) {
        if (json == null) return null;
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int c = json.indexOf(':', i + k.length());
        if (c < 0) return null;
        int p = c + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        if (json.charAt(p) == '"') {
            int e = p + 1;
            while (e < json.length()) {
                char ch = json.charAt(e);
                if (ch == '\\') { e += 2; continue; }
                if (ch == '"') break;
                e++;
            }
            return json.substring(p, Math.min(e + 1, json.length()));
        }
        int e = p;
        while (e < json.length() && ",}\n\r ".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(p, e);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 读 WAV（16/24/32 位 PCM 或 32 位浮点），返回 float[] 采样 + 采样率写进 out[0]。 */
    static float[] readWav(File f, int[] outRate) throws IOException {
        byte[] b = readAll(new FileInputStream(f));
        return parseWav(b, outRate);
    }

    static float[] parseWav(byte[] b, int[] outRate) throws IOException {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') {
            throw new IOException("不是 WAV 文件");
        }
        int pos = 12;
        int channels = 1, bits = 16, rate = 16000, fmt = 1;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, "US-ASCII");
            int sz = le32(b, pos + 4);
            int data = pos + 8;
            if ("fmt ".equals(id)) {
                fmt = le16(b, data);
                channels = Math.max(1, le16(b, data + 2));
                rate = le32(b, data + 4);
                bits = le16(b, data + 14);
            } else if ("data".equals(id)) {
                int n = Math.min(sz, b.length - data);
                int frame = Math.max(1, (bits / 8) * channels);
                int count = n / frame;
                float[] out = new float[count];
                for (int i = 0; i < count; i++) {
                    int o = data + i * frame;
                    float v;
                    if (fmt == 3 && bits == 32) v = Float.intBitsToFloat(le32(b, o));
                    else if (bits == 16) v = (short) le16(b, o) / 32768f;
                    else if (bits == 24) {
                        int s = (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] << 16);
                        v = s / 8388608f;
                    } else if (bits == 32) v = (int) le32(b, o) / 2147483648f;
                    else v = (short) le16(b, o) / 32768f;
                    out[i] = v;
                }
                if (outRate != null && outRate.length > 0) outRate[0] = rate;
                return out;
            }
            pos = data + sz + (sz & 1);
        }
        throw new IOException("WAV 里没有 data 块");
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
