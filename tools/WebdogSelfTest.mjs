/*
 * 前端自检（bootWatchdog）的回归测试 —— 0.2.8.4 那次真机事故的护栏。
 *
 * 【为什么要专门测它】
 * 这个自检以前是拿 `document.body.innerText` 整页正则去找
 * "Failed to load plugins / did not activate / waiting for service"。
 * 而这三句话**会出现在会话正文里**（用户把日志粘进对话、agent 复述报错、
 * DSH 自己的启动失败被记进消息）—— 于是一台界面完全正常的手机，
 * 每次重启都会被判成"插件把界面卡死了"：App 自动停用**全部**第三方插件 → 重启
 * → 正文里那几句话还在 → 又判一次 …… 用户的插件被摘光，界面反而进不去。
 * （用户反馈原话："安全模式又出现问题了""插件直接没了，但是文件还在"。）
 *
 * 修法是：只认 DSH 自己的启动卡 `<div data-dsh-boot>`。这个脚本把 patch.js 里
 * **真实的** bootWatchdog 源码抠出来，在一个假 DOM 上跑，逐个场景断言：
 *   · 会话正文里引用失败文案（界面正常）→ 必须**不**上报，且上报 pageReady；
 *   · 启动卡真的报了失败 → 必须上报，且把启动卡原文带回去（App 靠它抠插件名）；
 *   · 页面空白/卡在启动卡 → 仍然要上报（不能因为收紧判据就把老毛病漏掉）。
 *
 * 用法（仓库根目录）：node tools\WebdogSelfTest.mjs
 * 退出码 0 = 全部通过；1 = 有用例失败（并打印差异）。
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const patchPath = join(here, 'patch.js');
const assetPath = join(root, 'app', 'src', 'main', 'assets', 'dsh-mobile.js');

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log(`  ✅ ${name}`);
  } else {
    failures++;
    console.log(`  ❌ ${name}${extra ? '  ← ' + extra : ''}`);
  }
}

// ── 1. 从 patch.js 里抠出真实的 bootWatchdog 源码 ──────────────────
const patch = readFileSync(patchPath, 'utf8');
const start = patch.indexOf('(function bootWatchdog()');
if (start < 0) {
  console.error('❌ patch.js 里找不到 bootWatchdog（源码结构变了？）');
  process.exit(1);
}
const endMark = '\n  })();';
const end = patch.indexOf(endMark, start);
if (end < 0) {
  console.error('❌ 找不到 bootWatchdog 的结尾');
  process.exit(1);
}
const watchdogSrc = patch.slice(start, end + endMark.length);
console.log('── 1/2 取出 patch.js 里的 bootWatchdog ──');
check('源码里含启动卡选择器 [data-dsh-boot]', watchdogSrc.includes("'[data-dsh-boot]'"));
check('源码里不再整页扫 body.innerText 判失败',
  !/FAIL\.test\(bodyText\)\)\s*\{\s*report\(bodyText\)/.test(watchdogSrc) || watchdogSrc.includes('if (!card)'),
  '整页匹配必须被"没有启动卡"这个条件挡住');
check('源码长度合理', watchdogSrc.length > 1200 && watchdogSrc.length < 6000,
  `${watchdogSrc.length} 字符`);

// ── 2. 假 DOM + 假时钟，把 watchdog 跑起来 ────────────────────────
function runWatchdog(scene, limitMs = 70000) {
  let now = 0;
  const timers = [];
  const reports = [];
  let readyCount = 0;

  const el = (text) => ({ innerText: text });
  const card = scene.cardText === undefined ? null : el(scene.cardText);
  const rootEl = el(scene.rootText || '');
  const bodyEl = el(scene.bodyText !== undefined ? scene.bodyText : scene.rootText || '');

  const W = {
    DshAndroid: {
      pluginBootFailed: (t) => reports.push(String(t)),
      pageReady: () => { readyCount++; },
    },
  };
  const documentStub = {
    body: bodyEl,
    readyState: scene.readyState || 'complete',
    getElementById: (id) => (id === 'root' ? (scene.hasRoot === false ? null : rootEl) : null),
    querySelector: (sel) => (sel === '[data-dsh-boot]' ? card : null),
  };

  const ctx = {
    W,
    document: documentStub,
    Date: { now: () => now },
    setTimeout: (fn, ms) => { timers.push({ at: now + (ms || 0), fn }); },
    String,
  };
  vm.createContext(ctx);
  vm.runInContext(watchdogSrc, ctx, { filename: 'bootWatchdog.js' });

  while (timers.length) {
    timers.sort((a, b) => a.at - b.at);
    const t = timers.shift();
    if (t.at > limitMs) break;
    now = t.at;
    t.fn();
  }
  return { reports, readyCount, W };
}

// 真机上出现过的那段会话正文（用户把启动报错粘进了对话 / agent 复述了它）
const QUOTED = [
  '通过QQ加入指定群聊',
  '对话   轨迹   加载更早',
  'web boot: 1 entry did not activate',
  'dsh-pet: import failed (see console for the import error)',
  'our-free-model/mimo-v2.5-free   light',
  '10 轮 188 步·56 tok/s   22.5M tok·缓存命中 97%   20%',
].join('\n');

console.log('\n── 2/2 场景断言 ──');

// ① 界面正常，启动卡早就没了，但正文里引用了失败文案 → 绝不能被判成卡死
{
  const r = runWatchdog({ cardText: undefined, rootText: QUOTED, bodyText: QUOTED });
  check('① 正文引用失败文案（无启动卡）→ 不上报', r.reports.length === 0,
    '上报了：' + JSON.stringify(r.reports));
  check('① 同上 → 上报 pageReady（计数清零）', r.readyCount === 1, 'pageReady=' + r.readyCount);
}

// ② 界面正常，启动卡还在 DOM 里但只是"正在加载" → 也不能上报
{
  const cardText = 'HARNESS\nLoading plugins…';
  const r = runWatchdog({ cardText, rootText: cardText + '\n' + QUOTED, bodyText: 'x' });
  check('② 启动卡仍在转圈 + 正文引用失败文案 → 不上报', r.reports.length === 0,
    '上报了：' + JSON.stringify(r.reports));
  check('② 同上 → 上报 pageReady', r.readyCount === 1, 'pageReady=' + r.readyCount);
}

// ③ 启动卡真的报了失败 → 立刻上报，并且原文要带回去（App 从里面抠插件名）
{
  const cardText = 'Failed to load plugins\ndsh-pet: import failed (see console for the import error)';
  const r = runWatchdog({ cardText, rootText: cardText, bodyText: cardText });
  check('③ 启动卡报失败 → 上报', r.reports.length === 1, '上报次数=' + r.reports.length);
  check('③ 上报内容含 dsh-pet 这一行',
    (r.reports[0] || '').includes('dsh-pet: import failed'),
    JSON.stringify(r.reports[0]));
  check('③ 不上报 pageReady', r.readyCount === 0, 'pageReady=' + r.readyCount);
}

// ④ 真·空白页（脚本没跑起来，连启动卡都没有）→ 25 秒必须上报
{
  const r = runWatchdog({ cardText: undefined, rootText: '', bodyText: '', readyState: 'complete' });
  check('④ 空白页 → 上报', r.reports.length === 1, '上报次数=' + r.reports.length);
  check('④ 上报文案是"空白页面"', (r.reports[0] || '').includes('空白页面'),
    JSON.stringify(r.reports[0]));
}

// ④b 页面**还没加载完**（慢机器上 600KB 前端还在跑）→ 不能急着判空白、
//     更不能因此把用户的第三方插件全停掉；加载完了再报
{
  const r30 = runWatchdog(
    { cardText: undefined, rootText: '', bodyText: '', readyState: 'loading' }, 30000);
  check('④b 还在加载中（30 秒）→ 不报', r30.reports.length === 0,
    '上报了：' + JSON.stringify(r30.reports));
  const r70 = runWatchdog(
    { cardText: undefined, rootText: '', bodyText: '', readyState: 'loading' }, 70000);
  check('④b 一直是 loading（70 秒）→ 兜底上报', r70.reports.length === 1,
    '上报次数=' + r70.reports.length);
}

// ⑤ 卡在启动卡上（转圈转到死）→ 60 秒上报，但**不能**在 25 秒就误报
{
  const cardText = 'HARNESS\nLoading plugins…';
  const r25 = runWatchdog({ cardText, rootText: cardText, bodyText: cardText }, 30000);
  check('⑤ 卡在启动卡 30 秒 → 先不报（可能只是慢）', r25.reports.length === 0,
    '上报了：' + JSON.stringify(r25.reports));
  const r61 = runWatchdog({ cardText, rootText: cardText, bodyText: cardText }, 70000);
  check('⑤ 卡在启动卡 60 秒 → 上报', r61.reports.length === 1, '上报次数=' + r61.reports.length);
  check('⑤ 上报文案点出"60 秒"', (r61.reports[0] || '').includes('60 秒'),
    JSON.stringify(r61.reports[0]));
}

// ⑥ 界面正常且启动卡是渲染完成后的样子（没有卡）→ pageReady
{
  const r = runWatchdog({
    cardText: undefined,
    rootText: '你好，今天帮我看一下这个项目\n新建对话\n发送\n模型  deepseek-chat',
    bodyText: 'x',
  });
  check('⑥ 正常界面 → 无上报', r.reports.length === 0, JSON.stringify(r.reports));
  check('⑥ 正常界面 → pageReady', r.readyCount === 1, 'pageReady=' + r.readyCount);
}

// ⑦ 不是 DSH 的页面（容器没起来 → Chromium 错误页，没有 #root）：
//    一张错误页的正文很长，绝不能因此判"界面正常"，更不能判"插件卡死"
//    —— 那种页面交给 App 的心跳去重载
{
  const errPage = '此网站无法访问\n127.0.0.1 拒绝了我们的连接请求。\nERR_CONNECTION_REFUSED\n请检查代理和防火墙';
  const r = runWatchdog({ hasRoot: false, cardText: undefined, rootText: '', bodyText: errPage });
  check('⑦ Chromium 错误页 → 不上报', r.reports.length === 0, JSON.stringify(r.reports));
  check('⑦ Chromium 错误页 → 不 pageReady', r.readyCount === 0, 'pageReady=' + r.readyCount);
}

// ── 3. 额外：patch.js 与打进 APK 的 assets/dsh-mobile.js 必须逐字节一致 ──
console.log('\n── 附带对账：tools\\patch.js ↔ app\\src\\main\\assets\\dsh-mobile.js ──');
try {
  const a = readFileSync(patchPath);
  const b = readFileSync(assetPath);
  check('两份文件逐字节一致', a.equals(b),
    `patch.js=${a.length} 字节，assets=${b.length} 字节（构建时会自动同步，跑一次 gradle 即可）`);
} catch (e) {
  failures++;
  console.log('  ❌ 读不到 assets/dsh-mobile.js：' + e.message);
}

console.log(failures === 0 ? '\n全部通过 ✅' : `\n有 ${failures} 项失败 ❌`);
process.exit(failures === 0 ? 0 : 1);
