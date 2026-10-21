package com.dshmobile.probe;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 「我们自己的界面现在是不是在前台」—— 悬浮球自动显示/隐藏的依据之一。
 *
 * <h3>为什么需要它</h3>
 * 用户要求悬浮球"**退到后台、有任务时自动出现；回到主界面就消失**"。
 * 所以必须能可靠地回答"用户现在是不是正在看 Tdsh"。
 *
 * <h3>为什么不用 getRunningTasks / UsageStats</h3>
 * · {@code getRunningTasks} 在 Android 5+ 只返回自己的任务，判断不了别的；
 * · {@code UsageStatsManager} 要用户去设置里授权"使用情况访问"，为这点事不值当；
 * · 无障碍能读前台包名，但**无障碍可能被厂商关掉**，靠它就等于"权限掉了功能就废"。
 *
 * <p>而我们真正需要知道的只有一件事：**是不是我们自己在前台**。
 * 用 {@link Application.ActivityLifecycleCallbacks} 数"当前有几个自己的 Activity 处于
 * started 状态"就够准确了 —— 零权限、零跨进程调用、且不会误判别的应用。
 * 代价是它只知道"我们"，不知道"现在是微信还是桌面"——但悬浮球并不需要知道那个。
 *
 * <h3>为什么"设置界面也要算作前台"</h3>
 * 用户特别提了"注意不要让它在设置界面浮现出来"。
 * 我们的设置页（手机权限 / 白名单 / 插件市场那一堆）都是本 App 的 Activity，
 * 所以只要按"自己在前台"判定，它们自然就不会让球冒出来。
 * 那些页面本来就占满屏，球浮在上面只会挡路。
 */
public final class Foreground {

    private Foreground() { }

    private static volatile int sStarted = 0;
    private static volatile boolean sRegistered = false;

    /** 我们的界面是否可见（有任意一个自己的 Activity 处于 started）。 */
    public static boolean ours() { return sStarted > 0; }

    /** 在 Application.onCreate 里注册一次（幂等）。 */
    public static void install(Application app) {
        if (sRegistered) return;
        sRegistered = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { sStarted++; }
            @Override public void onActivityStopped(Activity a) { if (sStarted > 0) sStarted--; }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
