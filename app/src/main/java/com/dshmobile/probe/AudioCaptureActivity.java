package com.dshmobile.probe;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 「开始录制屏幕内声音」的入口 Activity —— **只做一件事：向系统要投影令牌**。
 *
 * <h3>为什么必须是一个 Activity</h3>
 * {@link MediaProjectionManager#createScreenCaptureIntent()} 弹出的确认框
 * （"开始录制或投放屏幕上的内容？"）只能由**处于前台的 Activity** 发起，
 * Service / 广播 / 容器里的插件都拿不到。所以要有一个 Activity 专门跑这一趟，
 * 拿到令牌就交给 {@link AudioCapture}，然后立刻自己结束（用户看不到任何界面）。
 *
 * <p>用 {@code Theme.Translucent.NoTitleBar}：它只是给系统弹框当个发起方，
 * 不能让用户看到 Flash 一下的空白页。
 *
 * <h3>为什么不再申请 RECORD_AUDIO / MICROPHONE 授权</h3>
 * 两条权限的用途不同：
 * <ul>
 *   <li>{@code RECORD_AUDIO}：麦克风（网页语音输入那条路，见 MainActivity.onPermissionRequest）；</li>
 *   <li>录制屏内声音：**不需要**麦克风权限，需要的是投影令牌 + MODIFY_AUDIO_SETTINGS。</li>
 * </ul>
 * 所以这里不做运行时权限申请，免得用户莫名其妙被要一次麦克风。
 */
public class AudioCaptureActivity extends Activity {

    private static final int REQ_PROJECTION = 0x4453;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "录制屏幕内声音需要 Android 10 以上", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                Toast.makeText(this, "这台设备不支持录制屏幕内声音", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开录制授权：" + t, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) { finish(); return; }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "没有授权，无法录制屏幕内声音", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        int seconds = getIntent() == null ? AudioCapture.DEFAULT_SECONDS
                : getIntent().getIntExtra("seconds", AudioCapture.DEFAULT_SECONDS);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            android.media.projection.MediaProjection mp =
                    mpm.getMediaProjection(resultCode, data);
            java.io.File out = AudioCapture.start(getApplicationContext(), mp, seconds);
            if (out == null) {
                Toast.makeText(this,
                        "录制没能启动：这台机器不允许录制其他应用的声音",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        "开始录音 → " + out.getAbsolutePath()
                                + "\n（在「手机权限」里可停止）",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "录制启动失败：" + t, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
