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
        /*
         * 「无障碍守护」从现在开始盯着（幂等，DshService/AppControlActivity 里也会调）。
         *
         * <h3>为什么必须挂在 Application 上（2026-10-27 用户报"掉了不会自动修"）</h3>
         * 原来只在 {@link DshService}（前台服务）和「手机控制」页里启动守护。
         * 但系统是**按需拉起无障碍服务的**：进程被杀之后，系统要重新绑服务时会
         * 直接把本进程拉起来跑 {@link DshAccessibilityService}，**不会**顺手把 DshService 也启动。
         * 在那种进程里守护线程从来不存在 —— 于是"设置被改了没人写回"，
         * 用户看到的正是"明明显示正常运行，但权限掉了一直不修"。
         *
         * 挂在 Application 上就没有这个缺口：任何原因拉起的进程都会带上守护。
         * 代价只有一个每 3 秒读两次设置的守护线程（毫秒级），冷启动路径上不做事。
         */
        A11yGuard.start(this);
    }
}
