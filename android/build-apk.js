/**
 * 不依赖 Gradle / Android Studio 的 APK 构建脚本
 *
 * 流程：aapt2 compile -> aapt2 link -> javac -> d8 -> 自行组装(对齐/改条目名) -> zipalign -> apksigner
 *
 * 为什么所有参数都用相对路径：
 *   aapt2 等是 Windows 原生程序，命令行参数里的中文路径（D:\自用\...）会因
 *   ANSI 代码页转换而打不开。改成「cwd 设为中文目录 + 参数只用相对路径」即可。
 *
 * 用法：node build-apk.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = __dirname;
const SDK = path.join(ROOT, 'sdk');
const BT = path.join(SDK, 'build-tools', '34.0.0');
const PLATFORM = path.join(SDK, 'platforms', 'android-34', 'android.jar');
const JAVA_HOME = require('./paths').requireJavaHome('build-apk.js');
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe');

const KEYTOOL = path.join(JAVA_HOME, 'bin', 'keytool.exe');

const PKG = 'cn.edu.gzhu.kb';
const { readZip } = require('./zip-tool');
const { applyVersion, readManifestVersion } = require('./build-version');

/**
 * keystore 口令与路径。
 *
 * **口令不能写死在源码里**：这个仓库是公开的，口令一旦提交，配合泄露的 .jks
 * 就等于把签名权给了别人（别人能签出被老用户"覆盖安装"的恶意更新）。
 *
 * 读取顺序：
 *   1. 环境变量 GZHU_KS_PASS（推荐，适合 CI）
 *   2. android/keystore/keystore-pass.txt（本机私有，已被 .gitignore 排除）
 *   3. 首次生成 keystore 时随机产生一个，并写进上面那个文件
 *
 * 注意第 3 步只在"要新建 keystore"时发生：已存在的 keystore 必须沿用原口令，
 * 否则无法覆盖安装（所以千万不要在已有 keystore 的情况下随手改这个逻辑）。
 */
const KEYSTORE_FILE = path.join(ROOT, 'keystore', 'gzhu-kb.jks');
const KEYSTORE_PASS_FILE = path.join(ROOT, 'keystore', 'keystore-pass.txt');

function readKeystorePass() {
  if (process.env.GZHU_KS_PASS) return process.env.GZHU_KS_PASS.trim();
  if (fs.existsSync(KEYSTORE_PASS_FILE)) {
    const v = fs.readFileSync(KEYSTORE_PASS_FILE, 'utf8').trim();
    if (v) return v;
  }
  return null;
}

// 基础版本号：人工维护的"大版本"。
// 每次构建都会分配一个**自增的构建号**，因此每份 APK 都必然不同：
//   versionName = <基础版本>.<构建号>        例如 1.1.7
//   versionCode = 基数 + 构建号             单调递增，能正常覆盖安装
//   文件名      = gzhu-kb-<基础版本>-build<构建号>.apk
// 构建号记录在 build-number.json 里（删掉会从 1 重新开始，注意别删）。
const BASE_VERSION = '1.1';
const BUILD_TIME = new Date();
const manifestBefore = readManifestVersion(path.join(ROOT, 'app/AndroidManifest.xml'));
const V = applyVersion(ROOT, BASE_VERSION, BUILD_TIME);
const VERSION_NAME = V.versionName;
const VERSION_CODE = String(V.versionCode);
const APK_FILE = `${V.apkBase}.apk`;

// 相对 ROOT 的路径（绝不把中文绝对路径传进命令行）
const R = {
  app: 'app',
  src: 'app/src',
  res: 'app/res',
  assets: 'app/assets',
  manifest: 'app/AndroidManifest.xml',
  build: 'build',
  out: 'out',
  platform: path.relative(ROOT, PLATFORM).split(path.sep).join('/'),
};

/**
 * 执行外部命令。
 *
 * 注意：本环境的沙箱禁止 Node 用「管道」捕获子进程输出（会报 EPERM），
 * 所以这里把 stdout/stderr 重定向到文件，再读回文件内容。
 */
function run(exe, args, opts = {}) {
  if (!opts.quiet) console.log('  $ ' + path.basename(exe) + ' ' + args.join(' ').slice(0, 200));
  const logDir = path.join(ROOT, 'build');
  fs.mkdirSync(logDir, { recursive: true });
  const outPath = path.join(logDir, '_stdout.log');
  const errPath = path.join(logDir, '_stderr.log');
  const outFd = fs.openSync(outPath, 'w');
  const errFd = fs.openSync(errPath, 'w');
  // 用 spawnSync：Windows 上 .bat 无法被直接 spawn（EINVAL），
  // 必须显式经 cmd.exe /c 调用。参数里的相对路径都不含空格，无需再加引号。
  const isBatch = /\.(bat|cmd)$/i.test(exe);
  const realExe = isBatch ? process.env.ComSpec || 'cmd.exe' : exe;
  const realArgs = isBatch ? ['/c', exe, ...args] : args;
  const res = spawnSync(realExe, realArgs, {
    cwd: opts.cwd || ROOT,
    stdio: ['ignore', outFd, errFd],
  });
  fs.closeSync(outFd);
  fs.closeSync(errFd);
  const stdout = fs.readFileSync(outPath, 'utf8');
  const stderr = fs.readFileSync(errPath, 'utf8');
  if (res.error || res.status !== 0) {
    console.error('\n命令失败(status=' + res.status + ', error=' + (res.error ? res.error.code : '-') + '): ' + path.basename(exe));
    const detail = (stdout + '\n' + stderr).trim();
    if (detail) console.error(detail.slice(0, 6000));
    else console.error('(子进程无输出)');
    throw new Error('构建步骤失败');
  }
  return stdout + stderr;
}

const rmrf = (p) => fs.rmSync(path.join(ROOT, p), { recursive: true, force: true });
const mkdir = (p) => fs.mkdirSync(path.join(ROOT, p), { recursive: true });

function walk(dir, ext, acc = []) {
  const abs = path.join(ROOT, dir);
  if (!fs.existsSync(abs)) return acc;
  for (const e of fs.readdirSync(abs, { withFileTypes: true })) {
    const rel = path.join(dir, e.name);
    if (e.isDirectory()) walk(rel, ext, acc);
    else if (!ext || rel.endsWith(ext)) acc.push(rel);
  }
  return acc;
}
const sizeOf = (p) => (fs.statSync(path.join(ROOT, p)).size / 1024).toFixed(1) + ' KB';

// ---------------------------------------------------------------- 环境检查
console.log('=== 构建信息 ===');
console.log('  本次构建号 : build' + V.build + '   (' + V.readable + ')');
console.log('  versionCode: ' + VERSION_CODE + '  (基数+构建号，单调递增)');
console.log('  versionName: ' + VERSION_NAME);
console.log('  产物文件名 : out/' + APK_FILE);
console.log('  上次构建号 : build' + V.previous
  + (manifestBefore.versionName ? '（清单原为 ' + manifestBefore.versionName + '）' : ''));
console.log('  版本号记录 : ' + require('./build-version').STATE_FILE
  + '   <== 别删，删了编号会从 1 重来（可能导致无法覆盖安装）');

console.log('\n=== 环境检查 ===');
for (const [name, p] of [
  ['aapt2', path.join(BT, 'aapt2.exe')],
  ['aapt', path.join(BT, 'aapt.exe')],
  ['d8', path.join(BT, 'd8.bat')],
  ['zipalign', path.join(BT, 'zipalign.exe')],
  ['apksigner', path.join(BT, 'apksigner.bat')],
  ['android.jar', PLATFORM],
  ['javac', JAVAC],
  ['keytool', KEYTOOL],
]) {
  const ok = fs.existsSync(p);
  console.log(`  ${ok ? '✓' : '✗'} ${name.padEnd(11)} ${p}`);
  if (!ok) { console.error('缺少必需工具，终止构建'); process.exit(1); }
}

// ---------------------------------------------------------------- 1. 同步网页资源
console.log('\n=== 1) 同步网页资源到 assets/www ===');
const { syncWebAssets } = require('./web-assets');
const synced = syncWebAssets(V);
for (const f of synced.files) {
  console.log(`  ${f}  ${sizeOf('app/assets/www/' + f)}`);
}
// 把版本号注入登录页，这样在手机上打开 App 就能看到装的是哪一次构建
console.log(`  已注入版本号: ${synced.version}`);

// ---------------------------------------------------------------- 2. 清理
console.log('\n=== 2) 清理构建目录 ===');
rmrf('build');
mkdir('build');
mkdir('out');
mkdir('build/gen');
mkdir('build/classes');
mkdir('build/dex');
console.log('  build/ 已重建');

// ---------------------------------------------------------------- 3. aapt2 compile
console.log('\n=== 3) aapt2 compile 资源 ===');
const resFiles = walk(R.res);
console.log(`  资源文件 ${resFiles.length} 个`);
run(path.join(BT, 'aapt2.exe'), ['compile', '--dir', 'app/res', '-o', 'build/res.zip']);
console.log('  -> build/res.zip ' + sizeOf('build/res.zip'));

// ---------------------------------------------------------------- 4. aapt2 link
console.log('\n=== 4) aapt2 link（资源表 + R.java + assets）===');
run(path.join(BT, 'aapt2.exe'), [
  'link',
  '-o', 'build/linked.apk',
  '-I', R.platform,
  '--manifest', R.manifest,
  '-R', 'build/res.zip',
  '--java', 'build/gen',
  '--min-sdk-version', '21',
  '--target-sdk-version', '34',
  '--version-code', VERSION_CODE,
  '--version-name', VERSION_NAME,
  '-A', 'app/assets',
  '--auto-add-overlay',
]);
console.log('  -> build/linked.apk ' + sizeOf('build/linked.apk'));
const badEntries = readZip(path.join(ROOT, 'build/linked.apk'))
  .map((e) => e.name).filter((n) => n.includes('\\'));
if (badEntries.length > 0) {
  console.log('  注意: aapt2 产出 ' + badEntries.length + ' 个含反斜杠的条目，组装阶段会修正');
}

// ---------------------------------------------------------------- 5. javac
console.log('\n=== 5) javac 编译 Java ===');
const javaFiles = walk(R.src, '.java').concat(['build/gen/' + PKG.split('.').join('/') + '/R.java']);
console.log(`  源文件 ${javaFiles.length} 个`);
const argFile = path.join(ROOT, 'build/javac-args.txt');
fs.writeFileSync(argFile, javaFiles.map((f) => '"' + f.split(path.sep).join('/') + '"').join('\n'), 'utf8');
run(JAVAC, [
  '-encoding', 'UTF-8',
  // JDK 17 里 -bootclasspath 已废弃，用 --release 8 + -classpath android.jar 才是正确写法：
  // 既限定了 Java 8 语言/API 级别，又能看到 Android 的平台类。
  '--release', '8',
  '-classpath', R.platform,
  '-nowarn',
  '-d', 'build/classes',
  '@build/javac-args.txt',
]);
console.log(`  -> ${walk('build/classes', '.class').length} 个 class`);

// ---------------------------------------------------------------- 6. d8
console.log('\n=== 6) d8 生成 dex ===');
run(path.join(BT, 'd8.bat'), [
  '--release',
  '--min-api', '21',
  '--lib', R.platform,
  '--output', 'build/dex',
  ...walk('build/classes', '.class'),
]);
console.log('  -> build/dex/classes.dex ' + sizeOf('build/dex/classes.dex'));

// ---------------------------------------------------------------- 7. 组装
// 刻意不用 `jar uf`：它会把 resources.arsc 压成 deflate 并破坏 4 字节对齐，
// 而 Android 11+ 要求 resources.arsc 必须「未压缩 + 4 字节对齐」，
// 否则安装器报"兼容性问题"直接拒装（这个坑已经踩过一次）。
// 这里用自写的 zip 写出器精确控制压缩方式与对齐（见 zip-tool.js / assemble-apk.js）。
console.log('\n=== 7) 组装未签名 APK（含 assets 反斜杠修正与对齐保证）===');
const { assembleApk } = require('./assemble-apk');
const asm = assembleApk(
  path.join(ROOT, 'build/linked.apk'),
  path.join(ROOT, 'build/dex/classes.dex'),
  path.join(ROOT, 'build/unsigned.apk'));
console.log(`  -> build/unsigned.apk ${sizeOf('build/unsigned.apk')}（${asm.entries} 个条目）`);
if (asm.fixedAssets.length > 0) {
  console.log(`  修正 assets 条目名 ${asm.fixedAssets.length} 个:`);
  asm.fixedAssets.forEach((s) => console.log('    ' + s));
} else {
  console.log('  assets 条目名无需修正');
}

// ---------------------------------------------------------------- 8. zipalign
console.log('\n=== 8) zipalign 对齐 ===');
run(path.join(BT, 'zipalign.exe'), ['-f', '-p', '4', 'build/unsigned.apk', 'build/aligned.apk']);
console.log('  -> build/aligned.apk ' + sizeOf('build/aligned.apk'));

// ---------------------------------------------------------------- 9. 签名
console.log('\n=== 9) 签名 ===');
mkdir('keystore');
let KEYSTORE_PASS = readKeystorePass();
if (!fs.existsSync(KEYSTORE_FILE)) {
  // 只在新建 keystore 时才允许生成口令，并且立刻落盘：
  // 否则下次构建拿不到同一口令，就无法覆盖安装（等于换了签名）。
  if (!KEYSTORE_PASS) {
    KEYSTORE_PASS = 'gzhu-' + require('crypto').randomBytes(12).toString('base64url');
    fs.writeFileSync(KEYSTORE_PASS_FILE, KEYSTORE_PASS + '\n', 'utf8');
    console.log('  已生成随机口令并写入 keystore/keystore-pass.txt（已被 .gitignore 排除）');
  }
  run(KEYTOOL, [
    '-genkeypair', '-keystore', 'keystore/gzhu-kb.jks', '-alias', 'gzhu-kb',
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10950',
    '-storepass', KEYSTORE_PASS, '-keypass', KEYSTORE_PASS,
    '-dname', 'CN=GZHU KB, OU=Personal, O=Personal, L=Guangzhou, ST=Guangdong, C=CN',
  ]);
  console.log('  已生成 keystore/gzhu-kb.jks');
} else {
  if (!KEYSTORE_PASS) {
    console.error('  找不到 keystore 口令。请设置环境变量 GZHU_KS_PASS，');
    console.error('  或把口令写进 keystore/keystore-pass.txt 后重试。');
    throw new Error('缺少 keystore 口令');
  }
  console.log('  复用已有 keystore/gzhu-kb.jks（口令来自环境变量或本地文件）');
}
const apkName = `out/${APK_FILE}`;
fs.copyFileSync(path.join(ROOT, 'build/aligned.apk'), path.join(ROOT, apkName));
run(path.join(BT, 'apksigner.bat'), [
  'sign',
  '--ks', 'keystore/gzhu-kb.jks',
  '--ks-key-alias', 'gzhu-kb',
  '--ks-pass', 'pass:' + KEYSTORE_PASS,
  '--key-pass', 'pass:' + KEYSTORE_PASS,
  '--v1-signing-enabled', 'true',
  '--v2-signing-enabled', 'true',
  apkName,
]);

// ---------------------------------------------------------------- 10. 校验
console.log('\n=== 10) 校验 ===');
const verify = run(path.join(BT, 'apksigner.bat'), ['verify', '--print-certs', apkName], { quiet: true });
verify.split('\n').filter((l) => l.trim()).slice(0, 5).forEach((l) => console.log('  ' + l.trim()));

const badging = run(path.join(BT, 'aapt.exe'), ['dump', 'badging', apkName], { quiet: true });
console.log('\n=== APK 信息 ===');
badging.split('\n')
  .filter((l) => /^(package|application-label|sdkVersion|targetSdkVersion|launchable-activity|uses-permission)/.test(l))
  .forEach((l) => console.log('  ' + l.trim()));

// 打包正确性：反斜杠条目、关键文件缺失、混入临时文件
const { checkApk } = require('./check-apk');
const problems = checkApk(path.join(ROOT, apkName));
if (problems.length > 0) {
  console.error('\n打包检查未通过:');
  problems.forEach((p) => console.error('  - ' + p));
  throw new Error('APK 打包检查失败');
}
console.log('  打包检查通过（无反斜杠条目、关键文件齐全）');

// ---------------------------------------------------------------- 11. 清理旧产物
// out/ 里只保留本次这一份。
// 之前这里漏了清理，结果 out/ 里同时躺着 build8/11/12 三个 APK，
// 到底该装哪一个得靠人去猜——这本身就是一个会装错版本的坑。
{
  const outDirPath = path.join(ROOT, 'out');
  const keep = new Set([APK_FILE, APK_FILE + '.idsig']);
  const removed = [];
  for (const name of fs.readdirSync(outDirPath)) {
    if (name.startsWith('gzhu-kb-') && !keep.has(name)) {
      fs.rmSync(path.join(outDirPath, name), { force: true });
      removed.push(name);
    }
  }
  const left = fs.readdirSync(outDirPath).filter((n) => n.endsWith('.apk'));
  if (left.length !== 1) {
    throw new Error('out/ 里应当只剩一个 APK，实际有 ' + left.length + ' 个: ' + left.join(', '));
  }
  if (removed.length) {
    console.log('\n=== 11) 清理旧产物 ===');
    removed.forEach((n) => console.log('  已删除 ' + n));
  }
}

console.log('\n=== 构建完成 ===');
console.log('  APK : ' + path.join(ROOT, apkName));
console.log('  大小: ' + sizeOf(apkName));
console.log('  out/ 里只保留这一份，直接装它即可');
