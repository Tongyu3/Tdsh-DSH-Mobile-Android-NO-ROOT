package com.dshmobile.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 「录制屏幕内声音」——把**手机正在播放的声音**录成 WAV，交给容器里的 DSH / 语音插件去听。
 *
 * <h3>为什么这个功能在 App 侧，不在插件里</h3>
 * 录制系统/其它应用的声音（Android 10+ 叫 Audio Playback Capture）必须同时具备三样东西：
 * <ol>
 *   <li>{@link android.media.projection.MediaProjection} —— 只能由 Activity 向用户申请，
 *       弹的是系统自己的"开始录制或投放"确认框，任何插件都代劳不了；</li>
 *   <li>{@code RECORD_AUDIO} + {@code MODIFY_AUDIO_SETTINGS} 两条权限；</li>
 *   <li>一个活着的进程（我们已有常驻前台服务 DshService）。</li>
 * </ol>
 * 容器里跑的 dsh 插件（uid 是 App、但没有 Activity 栈和投影令牌）拿不到第 1 条，
 * 所以能力必须落在这里，插件那边只负责"听这个 WAV"。
 *
 * <h3>为什么录成 WAV 而不是直接喂给识别模型</h3>
 * 语音识别（SenseVoice / whisper 之类）要的是 PCM；WAV 就是一种把 PCM 加个 44 字节壳的
 * 容器，任何工具都能读，也不需要编码器。16kHz 单声道 16bit 正好是语音识别的标准输入，
 * 一分钟约 1.9MB —— 比录成 AAC 再让插件去解码省事得多。
 *
 * <h3>录到什么</h3>
 * 只录「媒体类」声音（{@link AudioAttributes#USAGE_MEDIA} / GAME / UNKNOWN）：
 * 音乐、视频、游戏音效都能录；**系统提示音和通话录音不到**（系统限制，不是我们的选择）。
 * 另外，对方 App 可以在自己的播放流上声明"禁止被录制"—— 那样这一段就是静音，
 * 所以停止时会报一个峰值，全是 0 就说明没录到东西，不会让用户以为功能坏了。
 */
public final class AudioCapture {

    private static final String TAG = "DSH_AUDIO";

    /** 一次录制的时长上限（秒）。超时自动停，避免用户忘了停在后台录一小时。 */
    public static final int MAX_SECONDS = 300;
    /** 默认录多久（秒）。 */
    public static final int DEFAULT_SECONDS = 120;

    private static final int NOTIF_ID = 0x4454;
    private static final String CH_AUDIO = "dsh_audio";

    private static volatile boolean running = false;
    private static volatile AudioRecord record;
    private static volatile MediaProjection projection;
    private static volatile File file;
    private static volatile long peak = 0;
    private static volatile long writtenBytes = 0;
    private static Thread worker;

    private AudioCapture() { }

    public static boolean isRecording() { return running; }

    /** 最近一次录到的音量峰值（0 = 全程静音）。 */
    public static long lastPeak() { return peak; }

    /** 正在录的文件（没在录时返回 null）。 */
    public static File currentFile() { return file; }

    /** 录制要落在哪：工作区目录，容器里的 agent 直接就能读。 */
    private static File targetDir() {
        File d = new File(Env.WORKSPACE);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 开始录制。
     *
     * @param projection 系统给的投影令牌（由 {@link AudioCaptureActivity} 申请）
     * @param seconds    录多久，<=0 用 {@link #DEFAULT_SECONDS}
     * @return 成功返回 WAV 文件，失败返回 null（原因在日志/Toast 里，见调用方）
     */
    public static synchronized File start(Context c, MediaProjection projection, int seconds) {
        if (running) return file;
        if (Build.VERSION.SDK_INT < 29) {
            Log.w(TAG, "录制屏幕内声音需要 Android 10（API 29）以上");
            return null;
        }
        if (projection == null) {
            Log.w(TAG, "没有投影令牌，不能录制屏幕内声音");
            return null;
        }
        final int secs = seconds <= 0 ? DEFAULT_SECONDS : Math.min(seconds, MAX_SECONDS);
        try {
            AudioPlaybackCaptureConfiguration cfg =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            /*
             * 采样格式要试：绝大多数机器 16k 单声道就够（语音识别的标准输入），
             * 但有些 ROM 只接受 48k/44.1k 立体声 —— 那样 AudioRecord 会直接
             * 初始化失败（getState() != STATE_INITIALIZED），不试就会"点了没反应"。
             */
            AudioRecord rec = null;
            int rate = 0, channels = 0;
            int[][] tries = {
                    {16000, AudioFormat.CHANNEL_IN_MONO},
                    {44100, AudioFormat.CHANNEL_IN_STEREO},
                    {48000, AudioFormat.CHANNEL_IN_STEREO},
            };
            for (int[] t : tries) {
                try {
                    int minBuf = AudioRecord.getMinBufferSize(t[0], t[1],
                            AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuf <= 0) continue;
                    AudioFormat fmt = new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(t[0])
                            .setChannelMask(t[1])
                            .build();
                    AudioRecord r = new AudioRecord.Builder()
                            .setAudioFormat(fmt)
                            .setBufferSizeInBytes(Math.max(minBuf * 2, 8192))
                            .setAudioPlaybackCaptureConfig(cfg)
                            .build();
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        rec = r; rate = t[0]; channels = t[1]; break;
                    }
                    try { r.release(); } catch (Throwable ignore) { }
                } catch (Throwable e) {
                    Log.w(TAG, "采样格式 " + t[0] + " 不可用：" + e);
                }
            }
            if (rec == null) {
                Log.w(TAG, "所有采样格式都初始化失败（录制屏内声音不被这台机器允许）");
                try { projection.stop(); } catch (Throwable ignore) { }
                return null;
            }

            File out = new File(targetDir(), "rec-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                        .format(new java.util.Date())
                    + ".wav");
            final RandomAccessFile raf = new RandomAccessFile(out, "rw");
            raf.setLength(0);
            writeWavHeader(raf, Math.max(1, rate), channels == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1, 0);

            AudioCapture.record = rec;
            AudioCapture.projection = projection;
            AudioCapture.file = out;
            AudioCapture.peak = 0;
            AudioCapture.writtenBytes = 0;
            running = true;
            rec.startRecording();
            notifyStart(c, out);

            final int ch = channels == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
            // 内部类只能用 final/等效 final 的局部变量：rate 在循环里赋过值，必须拷一份
            final int fRate = rate;
            final int fCh = ch;
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    long deadline = System.currentTimeMillis() + secs * 1000L;
                    int bufSamples = 2048;
                    short[] buf = new short[bufSamples * 2];
                    try {
                        while (running && System.currentTimeMillis() < deadline) {
                            int n = record.read(buf, 0, buf.length);
                            if (n <= 0) {
                                if (n == AudioRecord.ERROR_INVALID_OPERATION
                                        || n == AudioRecord.ERROR_BAD_VALUE) break;
                                continue;
                            }
                            byte[] bytes;
                            long localPeak = 0;
                            if (ch == 2) {
                                // 立体声下混成单声道：取两条声道的平均，省一半体积
                                int frames = n / 2;
                                bytes = new byte[frames * 2];
                                for (int i = 0; i < frames; i++) {
                                    short l = buf[i * 2], r = buf[i * 2 + 1];
                                    int m = ((l + r) / 2);
                                    if (m > 32767) m = 32767;
                                    if (m < -32768) m = -32768;
                                    bytes[i * 2] = (byte) (m & 0xFF);
                                    bytes[i * 2 + 1] = (byte) ((m >> 8) & 0xFF);
                                    long a = Math.abs((long) m);
                                    if (a > localPeak) localPeak = a;
                                }
                            } else {
                                bytes = new byte[n * 2];
                                for (int i = 0; i < n; i++) {
                                    short s = buf[i];
                                    bytes[i * 2] = (byte) (s & 0xFF);
                                    bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                                    long a = Math.abs((long) s);
                                    if (a > localPeak) localPeak = a;
                                }
                            }
                            raf.write(bytes);
                            writtenBytes += bytes.length;
                            if (localPeak > peak) peak = localPeak;
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "录制线程异常", e);
                    } finally {
                        finish(raf, fRate, fCh, c);
                    }
                }
            }, "dsh-audio-rec");
            worker.start();
            Log.i(TAG, "开始录制屏内声音：" + out + " 采样=" + rate + " 声道=" + ch + " 时长≤" + secs + "s");
            return out;
        } catch (Throwable e) {
            Log.e(TAG, "开始录制失败", e);
            try { if (record != null) record.release(); } catch (Throwable ignore) { }
            record = null;
            running = false;
            try { projection.stop(); } catch (Throwable ignore) { }
            return null;
        }
    }

    /** 停止录制（幂等）。返回刚才录的那个文件。 */
    public static synchronized File stop() {
        File f = file;
        if (!running) return f;
        running = false;
        try { if (record != null) record.stop(); } catch (Throwable ignore) { }
        return f;
    }

    /** 真正的收尾：写 WAV 头、释放资源、收通知。 */
    private static void finish(RandomAccessFile raf, int rate, int ch, Context c) {
        running = false;
        try {
            try { if (record != null) record.stop(); } catch (Throwable ignore) { }
            try { if (record != null) record.release(); } catch (Throwable ignore) { }
            record = null;
            try { if (projection != null) projection.stop(); } catch (Throwable ignore) { }
            projection = null;
            long dataLen = writtenBytes;
            raf.seek(0);
            writeWavHeader(raf, rate, ch, dataLen);
            raf.close();
            Log.i(TAG, "录制结束：" + file + " 字节=" + dataLen + " 峰值=" + peak);
            if (c != null) {
                try {
                    NotificationManager nm = (NotificationManager)
                            c.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(NOTIF_ID);
                } catch (Throwable ignore) { }
            }
        } catch (Throwable e) {
            Log.w(TAG, "收尾失败", e);
        }
    }

    /** 44 字节标准 WAV 头（dataLen = PCM 字节数；写 0 之后再回来补）。 */
    private static void writeWavHeader(RandomAccessFile raf, int rate, int channels, long dataLen)
            throws java.io.IOException {
        int bits = 16;
        int byteRate = rate * channels * bits / 8;
        raf.writeBytes("RIFF");
        writeIntLE(raf, (int) (36 + dataLen));
        raf.writeBytes("WAVEfmt ");
        writeIntLE(raf, 16);                 // fmt 块长度
        writeShortLE(raf, (short) 1);        // PCM
        writeShortLE(raf, (short) channels);
        writeIntLE(raf, rate);
        writeIntLE(raf, byteRate);
        writeShortLE(raf, (short) (channels * bits / 8));   // block align
        writeShortLE(raf, (short) bits);
        raf.writeBytes("data");
        writeIntLE(raf, (int) dataLen);
    }

    private static void writeIntLE(RandomAccessFile raf, int v) throws java.io.IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile raf, short v) throws java.io.IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
    }

    /**
     * 常驻通知：录制期间必须有一条，否则用户不知道它在录（也在偷电）。
     * 点它回到 App，从「手机权限」里停止。
     */
    private static void notifyStart(Context c, File out) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CH_AUDIO) == null) {
                    NotificationChannel ch = new NotificationChannel(CH_AUDIO, "录音",
                            NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("录制屏幕内声音时显示");
                    nm.createNotificationChannel(ch);
                }
            }
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int piFlags = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(c, 0, i, piFlags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH_AUDIO)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("正在录制屏幕内声音")
                    .setContentText(out.getName() + " · 在「手机权限」里可停止")
                    .setOngoing(true)
                    .setContentIntent(pi);
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "录音通知失败", t);
        }
    }
}
