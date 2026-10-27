/**
 * 宿主侧（Node 进程）—— 什么都不做。
 *
 * 这个插件的全部逻辑都在浏览器侧（lib/client.js）与 App 的原生桥：
 *   · 读哪一段、什么时候读      → client.js
 *   · 变成声音（系统语音 / 云端） → App 的 Tts.java（DshAndroid.ttsSpeak / ttsPlay）
 *
 * 为什么不在宿主侧做：安卓 WebView 根本没有 speechSynthesis，TTS 只能走原生；
 * 而云端接口放宿主侧还要过 DSH 的 Remote/typert 那一整套，为一个朗读功能不值。
 *
 * 导出的 apply 是 DSH 加载插件时要求的形状（空实现也要有）。
 */
export const name = "tts";

export function apply() { }
