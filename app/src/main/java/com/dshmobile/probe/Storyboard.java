package com.dshmobile.probe;

import java.util.ArrayList;
import java.util.List;

/**
 * <h3>出处</h3>
 *
 * <p><b>这个类的设计不是本项目原创，来自群友「古德里安」的建议文档
 * 《07-建议-让设备端挑帧(3).md》。</b>
 *
 * <p>他的结论：瓶颈不在"解码"而在"每帧都要走一次 HTTP + 落一次盘 + 传一份文件"，
 * 所以应当把「抓帧 → 帧间差异 → 挑关键帧 → 拼分镜表」整条链**下沉到设备端**，
 * 一次调用返回一张拼图 + 结构化元数据。他还附了用户态参考实现（dsh-phone-plus）。
 * 这个类就是把那套逻辑搬进 Java。
 *
 * <p>⚠️ 备注这条是因为**第一版漏了出处** —— 只写了"群友实测"，没记是谁、没记文档，
 * 后来无从追溯。此后凡采纳外部建议，一律记名 + 记文档。
 */
/**
 * 「看一段屏幕」的核心逻辑：**从一串连续帧里挑出值得看的，并算出拼图布局**。
 *
 * <h3>为什么要有这个（群友实测结论，2026-10-05）</h3>
 * 按帧全量喂模型是"看视频"最贵的做法：1fps 约 3.6~5.4M tokens/小时。
 * 真正该看的是**画面变化的那一刻**，而且 **N 张关键帧可以拼成一张图** ——
 * 模型读一次图就能看完整个流程（实测 15 帧分开读 ≈ 1.2~2 万 tokens → 拼图 ≈ 700 tokens）。
 *
 * <h3>为什么纯逻辑单独一个类</h3>
 * 与 {@code Relay}/{@code ProcLogic} 同一个理由：**挑帧判据写错 = 要么漏掉关键画面、
 * 要么等于没省**，这种地方必须有离线回归测试兜着。所以这里一行 Android API 都不碰 ——
 * 输入是"每帧的缩略像素 + 帧间差异值"，输出是"选哪几帧、怎么摆"。
 *
 * <h3>判据（刻意简单、可解释）</h3>
 * <ol>
 *   <li><b>帧间差异</b>：把两帧缩到同一张极小的灰度图（默认 32×32）逐点比，
 *       取平均绝对差（0~255）。比"整张原图比"便宜几千倍，而且对压缩噪声不敏感。</li>
 *   <li><b>阈值</b>：差异 &gt; threshold 才算"变了"。默认 8（经验值：
 *       静态界面约 0~3，翻页/弹窗约 20~80）。</li>
 *   <li><b>首帧永远保留</b>（它是"起点"，哪怕后面全没变也要有个基准）。</li>
 *   <li><b>采样上限</b>：最多 maxFrames 张关键帧 —— 防止"整段都在动"时拼出一张巨图。</li>
 * </ol>
 */
public final class Storyboard {

    private Storyboard() { }

    /** 一帧的摘要（设备端算好后交给判据）。 */
    public static final class Frame {
        public int index;          // 第几帧（0 起）
        public long atMs;          // 相对开始的毫秒
        public int diff;           // 与**上一采样帧**的平均绝对差（0~255）；首帧为 255
        public boolean key;        // 是否选中为关键帧

        public Frame(int index, long atMs, int diff) {
            this.index = index;
            this.atMs = atMs;
            this.diff = diff;
        }
    }

    /**
     * 算两帧的"平均绝对差"（0~255）。
     *
     * <p>入参是同一尺寸的灰度数组（长度必须相等）。不等长时返回 255（当"完全变了"），
     * 而不是抛异常 —— 尺寸变化本身就是"画面变了"的强信号（比如转屏）。
     */
    public static int diff(byte[] a, byte[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 255;
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return (int) (sum / a.length);
    }

    /**
     * 从一串帧里挑出关键帧。
     *
     * <p>与 {@link #diff} 分开，是为了让"挑帧"这件事**不依赖像素**就能测
     * （自检里直接喂 diff 值即可）。
     *
     * @param frames     按时间顺序的帧（diff 字段要已经填好）
     * @param threshold  差异超过它才算"变了"（建议 8）
     * @param maxFrames  最多保留几张关键帧（至少 1）
     * @return 选中为关键帧的那些（保持原顺序；至少包含第 0 帧）
     */
    public static List<Frame> pick(List<Frame> frames, int threshold, int maxFrames) {
        List<Frame> out = new ArrayList<>();
        if (frames == null || frames.isEmpty()) return out;
        int cap = Math.max(1, maxFrames);
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            // 首帧永远留：它是"起点"，否则后面的差异没有参照物
            boolean take = (i == 0) || f.diff > threshold;
            f.key = take;
            if (take) out.add(f);
        }
        /*
         * 超过上限时**均匀抽稀**，而不是简单砍掉尾巴 ——
         * 砍尾巴会丢掉"最后变成了什么样"，那往往正是用户最关心的那一段。
         */
        if (out.size() > cap) {
            List<Frame> thinned = new ArrayList<>(cap);
            for (int i = 0; i < cap; i++) {
                int idx = (int) Math.round((double) i * (out.size() - 1) / (cap - 1 == 0 ? 1 : cap - 1));
                thinned.add(out.get(Math.min(idx, out.size() - 1)));
            }
            for (Frame f : frames) f.key = false;
            for (Frame f : thinned) f.key = true;
            return thinned;
        }
        return out;
    }

    /** 拼图布局：每格尺寸 + 总画布尺寸。 */
    public static final class Layout {
        public int cols, rows, cellW, cellH, canvasW, canvasH;

        /** 第 i 格左上角坐标。 */
        public int x(int i) { return (i % cols) * cellW; }
        public int y(int i) { return (i / cols) * cellH; }
    }

    /**
     * 算拼图布局。
     *
     * @param count   关键帧张数
     * @param cols    每行几列（建议 3~4；列太少会很长，太多每格看不清）
     * @param cellW   每格宽（像素，已经缩放好的）
     * @param cellH   每格高
     * @param gap     格间距（像素，留一点便于人眼分辨）
     * @param padding 四周留白
     */
    public static Layout layout(int count, int cols, int cellW, int cellH, int gap, int padding) {
        Layout l = new Layout();
        int n = Math.max(1, count);
        l.cols = Math.max(1, Math.min(cols, n));
        l.rows = (n + l.cols - 1) / l.cols;      // 向上取整
        l.cellW = cellW + gap;
        l.cellH = cellH + gap;
        l.canvasW = padding * 2 + l.cols * l.cellW - gap;
        l.canvasH = padding * 2 + l.rows * l.cellH - gap;
        return l;
    }

    /** 给人看/给 agent 看的元数据（JSON，一行帧一条）。 */
    public static String toJson(List<Frame> frames, int fps, int threshold, String sheetPath,
                                List<String> keyPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"sheet\": ").append(quote(sheetPath)).append(",\n");
        sb.append("  \"fps\": ").append(fps).append(",\n");
        sb.append("  \"threshold\": ").append(threshold).append(",\n");
        int keys = 0;
        for (Frame f : frames) if (f.key) keys++;
        sb.append("  \"sampled\": ").append(frames.size()).append(",\n");
        sb.append("  \"keys\": ").append(keys).append(",\n");
        sb.append("  \"frames\": [\n");
        boolean first = true;
        int k = 0;
        for (Frame f : frames) {
            if (!f.key) continue;
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    {\"i\": ").append(f.index)
              .append(", \"t_ms\": ").append(f.atMs)
              .append(", \"diff\": ").append(f.diff);
            if (keyPaths != null && k < keyPaths.size()) {
                sb.append(", \"file\": ").append(quote(keyPaths.get(k)));
            }
            sb.append("}");
            k++;
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
