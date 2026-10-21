package com.dshmobile.probe;

import android.app.Application;

/**
 * 本 App 的 Application —— 只做一件很轻的事：把"自己是否在前台"这件事接上。
 *
 * <h3>为什么需要它</h3>
 * 悬浮球要"退到后台有任务才出现、回到我们自己的界面就消失"，
 * 而判断"是不是我们自己在前面"靠 {@link Application.ActivityLifecycleCallbacks}
 * 最稳（零权限、零跨进程调用，且只看自己）。
 * 那套回调必须从 Application 上注册，所以需要这个类。
 *
 * <p>刻意**不做别的**：Application 的 onCreate 在冷启动路径上，
 * 这里多放一点逻辑就多一分"启动变慢"的风险（这个项目对启动时间很敏感）。
 */
public class DshApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 跟随 DSH 主题：必须在建任何 View 之前设定，
        // 否则会拿到上一个页面留下的颜色。
        Ui.applyTheme(this);
        Ui.applySystemBars(this);
        Foreground.install(this);
    }
}
