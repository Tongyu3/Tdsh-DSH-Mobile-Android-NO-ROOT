package com.dshmobile.probe;

/**
 * 容器里那套 Node 的路径常量（**故意不碰任何 Android API**）。
 *
 * <h3>为什么单独一个类</h3>
 * {@link Env} 里有这个常量，但 {@code Env} 依赖 {@code android.content.Context} 等，
 * 于是任何引用它的纯逻辑类（例如 {@link ProcCleanup}）都会连带把 Android 拖进来，
 * 就再也没法用裸 {@code javac} 做离线自检了（这个项目栽过一次：
 * {@code Providers.java} 因为 import 了 android.content，一直进不了离线自检清单）。
 *
 * <p>所以凡是"纯逻辑又要用到这个路径"的地方，都从这里取。
 * 值必须与 {@code Env} 里那份保持一致 —— {@code Env.NODE_DIR} 现在直接指向这里，
 * 也就是**只有一份真源**。
 */
public final class NodeInfo {

    private NodeInfo() { }

    /** 容器里 Node 的版本目录名（与 rootfs 的 /opt 下那一个一致）。 */
    public static final String NODE_DIR = "node-v22.23.2-linux-arm64";
}
