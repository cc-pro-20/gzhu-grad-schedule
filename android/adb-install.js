// 用 adb 安装并抓日志，便于在真机上定位问题。
//
// 用法：
//   node adb-install.js              安装 out/ 里最新的 APK 并输出结果
//   node adb-install.js --reinstall  先卸载再安装（解决签名冲突/降级问题）
//   node adb-install.js --logcat     安装后持续抓 GzhuKB 相关日志（Ctrl+C 退出）
//   node adb-install.js --devices    只列出已连接设备
//
// 为什么用 adb 而不是直接点安装：
//   手机安装器只给一句笼统的"出现兼容性问题"，而 adb 会返回确切的失败原因
//   （签名不一致 / 版本降级 / 解析失败 等），排查快很多。
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = __dirname;
const ADB = path.join(ROOT, 'sdk', 'platform-tools', 'adb.exe');
const LOG = path.join(ROOT, 'build', '_adb.log');

const args = process.argv.slice(2);
const wantReinstall = args.includes('--reinstall');
const wantLogcat = args.includes('--logcat');
const onlyDevices = args.includes('--devices');

function adb(adbArgs) {
  fs.mkdirSync(path.dirname(LOG), { recursive: true });
  const fd = fs.openSync(LOG, 'w');
  const r = spawnSync(ADB, adbArgs, { stdio: ['ignore', fd, fd] });
  fs.closeSync(fd);
  return {
    status: r.status,
    code: r.error ? r.error.code : null,
    output: fs.readFileSync(LOG, 'utf8'),
  };
}

if (!fs.existsSync(ADB)) {
  console.error('找不到 adb: ' + ADB);
  process.exit(1);
}

console.log('=== 已连接设备 ===');
const dev = adb(['devices', '-l']);
console.log(dev.output.trim());

if (!/device\s/.test(dev.output.replace(/List of devices attached/, ''))) {
  console.error('\n没有检测到已授权的设备。请确认：');
  console.error('  1. 手机已用 USB 连接电脑，并在手机上选择"传输文件(MTP)"模式');
  console.error('  2. 已在 设置 → 开发者选项 里打开 USB 调试');
  console.error('  3. 手机上弹出"允许 USB 调试吗？"时点了允许');
  console.error('  4. 若仍不出现，执行：' + ADB + ' kill-server 后再试');
  process.exit(1);
}

if (onlyDevices) process.exit(0);

// 找最新的 APK
const outDir = path.join(ROOT, 'out');
const apks = fs.readdirSync(outDir).filter((f) => f.endsWith('.apk'))
  .map((f) => ({ f, t: fs.statSync(path.join(outDir, f)).mtimeMs }))
  .sort((a, b) => b.t - a.t);
if (apks.length === 0) {
  console.error('out/ 里没有 APK，先执行 node build-apk.js');
  process.exit(1);
}
const apk = path.join(outDir, apks[0].f);
console.log('=== 安装 ' + apks[0].f + ' ===');

if (wantReinstall) {
  console.log('  先卸载旧版本（--reinstall）');
  const un = adb(['uninstall', 'cn.edu.gzhu.kb']);
  console.log('  ' + un.output.trim());
}

const inst = adb(['install', '-r', apk]);
console.log(inst.output.trim());
if (inst.status === 0) {
  console.log('\n安装成功。版本号可在 App 登录页底部看到。');
} else {
  console.log('\n安装失败。上面 Failure 后面的文字就是确切原因，常见几种：');
  console.log('  INSTALL_FAILED_UPDATE_INCOMPATIBLE  -> 签名与已装版本不一致，用 --reinstall');
  console.log('  INSTALL_FAILED_VERSION_DOWNGRADE    -> 目标版本更低，用 --reinstall');
  console.log('  INSTALL_PARSE_FAILED_*              -> 包结构有问题，把原文发我');
}

if (wantLogcat) {
  console.log('\n=== 抓日志（Ctrl+C 结束）。请在手机上操作 App ===');
  console.log('  过滤 tag: GzhuKB（原生诊断）与 chromium（页面 CSS/JS 加载）');
  spawnSync(ADB, ['logcat', '-v', 'time', '-s', 'GzhuKB:V', 'chromium:W', '*:S'],
    { stdio: 'inherit' });
}
