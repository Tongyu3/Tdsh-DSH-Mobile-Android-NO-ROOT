package com.dshmobile.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 悬浮球本体：**自己在 Canvas 上画一个圆**，不走 ImageView、不走 Drawable 形状。
 *
 * <h3>为什么推翻重做（踩过的三条死路）</h3>
 * 这个球试过三种做法，前三种在真机上都渲染成**一块黑色方块**：
 * <ol>
 *   <li>`GradientDrawable(OVAL)` 当背景 + `ImageView` 贴图 + `setClipToOutline` 裁圆
 *       —— 自适应图标不吃圆形裁剪，出来是方的；</li>
 *   <li>`CircleIcon` 预先合成一张"本来就是圆"的位图再塞给 `ImageView`
 *       —— 合成结果本身是对的（离屏统计过：中心是白色、四角透明），
 *       但经过 `BitmapDrawable` + `ImageView` 这一层之后屏幕上是黑的；</li>
 *   <li>换成"用真实 PNG 而不是自适应图标" —— 一样黑。</li>
 * </ol>
 *
 * <p>所以现在不再"先做一张圆图再交给别人显示"，而是**直接控制最终那次绘制**：
 * 一个自绘 View，`onDraw` 里先画圆底、再用圆形 clip 画图标。
 * 没有中间层，就没有"中间层自作主张"的余地。
 *
 * <h3>为什么用 clipPath 而不是 Xfermode</h3>
 * `PorterDuffXfermode` 在硬件加速下语义容易受图层影响；
 * `clipPath` 是直接、可预测的，而且这里只需要"画在圆内"这一个效果。
 */
public class BallView extends View {

    /** 球底：深色半透明（图标没加载出来时也看得见球在哪）。 */
    private static final int BG = 0xF21A1A1D;
    /** 外圈描边：浅蓝，让球在浅色壁纸上也清楚。 */
    private static final int RING = 0xFF4C8DFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();

    private Bitmap bmp;

    /**
     * 贴纸模式：不画圆底、不裁剪、不画外圈。
     *
     * <p>两种图的要求是相反的：
     * <ul>
     *   <li><b>投稿照片</b>（方形图）—— 必须裁成圆才像个球，否则是块方砖；</li>
     *   <li><b>表情包贴纸</b>（已抠好的不规则轮廓 + 透明白边）——
     *       再套一层圆裁剪会把耳朵、尾巴、呆毛、飘起来的头发全切掉；
     *       而且贴纸自带的白描边就是它的"外圈"，再加蓝圈是画蛇添足。</li>
     * </ul>
     */
    private boolean sticker;

    public BallView(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(BG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(RING);
    }

    /** 设置要显示的图标（null = 只画圆底，仍然是可见的球）。 */
    public void setIcon(Bitmap b) {
        bmp = b;
        invalidate();
    }

    /** 贴纸模式开关（见字段注释）。 */
    public void setStickerMode(boolean s) {
        sticker = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (sticker) {
            if (bmp == null || bmp.isRecycled()) return;
            // contain：等比缩放到完全放得下，绝不用 cover（那会切掉轮廓）
            float s = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
            float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
            box.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            cv.drawBitmap(bmp, null, box, icon);
            return;
        }

        float r = Math.min(w, h) / 2f;
        float stroke = Math.max(2f, r * 0.07f);
        ring.setStrokeWidth(stroke);

        // ① 圆底
        cv.drawCircle(w / 2f, h / 2f, r - stroke / 2f, fill);

        // ② 图标（圆形裁剪）
        if (bmp != null && !bmp.isRecycled()) {
            cv.save();
            clip.reset();
            clip.addCircle(w / 2f, h / 2f, r - stroke, Path.Direction.CW);
            cv.clipPath(clip);
            // 居中等比铺满（短边对齐），不做拉伸变形
            float s = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
            float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
            box.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            cv.drawBitmap(bmp, null, box, icon);
            cv.restore();
        }

        // ③ 外圈
        cv.drawCircle(w / 2f, h / 2f, r - stroke / 2f, ring);
    }

    /**
     * 把选中的图标解成位图。
     *
     * <h3>为什么用 `openRawResource` 而不是 `decodeResource` / `getDrawable`</h3>
     * 三个都试过，结论是**只有读原始字节这一条路稳**：
     * <ul>
     *   <li>`getDrawable()` —— 对自适应图标能取到，但栅格化出来**纯黑**
     *       （实测 `中心=#FF000000`）；</li>
     *   <li>`decodeResource()` —— 对自适应图标**直接返回 null**
     *       （实测 `bitmap=null(只画圆底)`，屏幕上就只剩圆底）；</li>
     *   <li>`openRawResource()` + `decodeStream()` —— **直接拿文件字节去解码**，
     *       完全不经过"资源 ID → Drawable"那层解析，
     *       所以自适应图标 XML 干扰不到它。实拍验证：
     *       `bitmap=192x192 中心=#FFE0252C 平均=(248,205,181)`（正常彩色照片）。</li>
     * </ul>
     *
     * <p>额外好处：不必为新资源名另存一份位图（那会让 APK 多 ~400KB 重复图片，
     * 还会把源码树撑过上传批次的上限）。直接用现成的投稿原图资源名即可。
     *
     * <p>拿不到就返回 null —— 调用方只画圆底，球仍然可见，不会变成黑块。
     */
    public static Bitmap loadIcon(Context c, BallIcon.Option o) {
        try {
            int res = BallIcon.resId(c, o);
            if (res == 0) return null;
            try (java.io.InputStream in = c.getResources().openRawResource(res)) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
