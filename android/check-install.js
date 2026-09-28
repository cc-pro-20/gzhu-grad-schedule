// APK 安装兼容性自检：把 vivo/Android 安装器会拒绝的常见原因逐项查一遍
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { readZip } = require('./zip-tool');

const JAR = path.join(require('./paths').requireJavaHome('check-install.js'), 'bin', 'jar.exe');
const BT = path.join(__dirname, 'sdk', 'build-tools', '34.0.0');

/** 解析中央目录，拿到每个条目的压缩方式/标志（安装器对这些很敏感） */
function zipEntries(zipPath) {
  const buf = fs.readFileSync(zipPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 65536; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('找不到 EOCD');
  const total = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let n = 0; n < total; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('中央目录项异常 @' + off);
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const uncompSize = buf.readUInt32LE(off + 24);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    // 本地头偏移 -> 读 local header 才能算数据起始位置（用于对齐检查）
    const localOff = buf.readUInt32LE(off + 42);
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataOffset = localOff + 30 + lNameLen + lExtraLen;
    out.push({
      name: buf.toString('utf8', off + 46, off + 46 + nameLen),
      method,                   // 0=stored, 8=deflate
      compSize, uncompSize, dataOffset,
      localExtraLen: lExtraLen,
    });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

/**
 * 默认检查 out/ 里最新的那个 APK。
 *
 * 以前这里写死 'out/gzhu-kb-1.1.apk'，但产物文件名带构建号
 * （gzhu-kb-1.1-build13.apk），写死的名字根本不存在——这类
 * "脚本自己先坏了"的问题比工具报错更难发现，所以改成自动找最新的。
 * 也可以显式传路径：node check-install.js out/xxx.apk
 */
function newestApk() {
  const dir = path.join(__dirname, 'out');
  if (!fs.existsSync(dir)) return null;
  const apks = fs.readdirSync(dir)
    .filter((n) => n.endsWith('.apk'))
    .map((n) => ({ n, t: fs.statSync(path.join(dir, n)).mtimeMs }))
    .sort((a, b) => b.t - a.t);
  return apks.length ? 'out/' + apks[0].n : null;
}

const apkRel = process.argv[2] || newestApk();
if (!apkRel) {
  console.error('out/ 里没有 APK，请先执行 node build-apk.js');
  process.exit(1);
}
const apkAbs = path.resolve(__dirname, apkRel);
console.log('=== 检查 ' + apkRel + ' ===');
console.log('文件大小: ' + (fs.statSync(apkAbs).size / 1024).toFixed(1) + ' KB\n');

const entries = zipEntries(apkAbs);
console.log('--- 条目压缩方式（Android 11+ 要求 resources.arsc 必须未压缩且 4 字节对齐）---');
let arscProblem = null;
for (const e of entries) {
  const stored = e.method === 0;
  const aligned = e.dataOffset % 4 === 0;
  const flag = (e.name === 'resources.arsc' && (!stored || !aligned)) ? '  <== 有问题' : '';
  console.log(`  ${stored ? 'stored ' : 'deflate'}  align=${aligned ? 'Y' : 'N'}  ${e.name}${flag}`);
  if (e.name === 'resources.arsc' && (!stored || !aligned)) {
    arscProblem = { stored, aligned };
  }
}
console.log(arscProblem
  ? `\n  !! resources.arsc 不满足要求 (stored=${arscProblem.stored}, aligned=${arscProblem.aligned})`
  : '\n  resources.arsc 满足要求');

console.log('\n--- 是否含 native 库（ABI 兼容性）---');
const libs = entries.filter((e) => e.name.startsWith('lib/'));
console.log(libs.length === 0 ? '  无 lib/ 条目（纯 Java，不涉及 ABI）' : libs.map((l) => '  ' + l.name).join('\n'));

/** 跑外部命令并把输出读回来（沙箱禁止管道捕获，走文件重定向） */
function run(exe, args, cwd) {
  const logDir = path.join(__dirname, 'build');
  fs.mkdirSync(logDir, { recursive: true });
  const o = fs.openSync(path.join(logDir, '_chk.log'), 'w');
  const r = spawnSync(/\.(bat|cmd)$/i.test(exe) ? (process.env.ComSpec || 'cmd.exe') : exe,
    /\.(bat|cmd)$/i.test(exe) ? ['/c', exe, ...args] : args,
    { cwd: cwd || __dirname, stdio: ['ignore', o, o] });
  fs.closeSync(o);
  return { status: r.status, output: fs.readFileSync(path.join(logDir, '_chk.log'), 'utf8') };
}

console.log('\n--- 清单关键属性 ---');
const badging = run(path.join(BT, 'aapt.exe'), ['dump', 'badging', apkRel]).output;
badging.split('\n')
  .filter((l) => /^(package|sdkVersion|targetSdkVersion|uses-permission|native-code|application-label|launchable|provides)/.test(l))
  .forEach((l) => console.log('  ' + l.trim()));

console.log('\n--- 签名方案 ---');
const v = run(path.join(BT, 'apksigner.bat'), ['verify', '--verbose', apkRel]).output;
v.split('\n').filter((l) => /Verifies|Verified using|Number of signers|WARNING|ERROR/.test(l))
  .forEach((l) => console.log('  ' + l.trim()));

console.log('\n--- 已知的兼容性红线检查 ---');
const mp = (badging.match(/sdkVersion:'(\d+)'/) || [])[1];
const tp = (badging.match(/targetSdkVersion:'(\d+)'/) || [])[1];
const vc = (badging.match(/versionCode='(\d+)'/) || [])[1];
const vn = (badging.match(/versionName='([^']+)'/) || [])[1];
console.log(`  minSdk=${mp}  targetSdk=${tp}  versionCode=${vc}  versionName=${vn}`);
console.log('  targetSdk < 23 会被 Android 14+ 拒绝: ' + (Number(tp) < 23 ? '是（有问题）' : '否'));
console.log('  minSdk 是否高于设备 API: 需设备侧确认（iQOO15 应为 Android 15/16 = API 35/36）');
