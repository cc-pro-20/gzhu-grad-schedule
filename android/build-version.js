// 构建编号管理：保证**每次打包产出的 APK 都是可区分的**。
//
// 为什么用单调递增的构建号而不是时间戳：
//   时间戳在同一秒内连续构建会撞车（文件名相同、版本号相同），
//   而构建号一定不同，且天然满足 Android 对 versionCode 的要求。
//
// versionCode：Android 用它判断升级/降级，必须**单调递增**。
//              这里 = 基数 + 构建号，恒为正、恒增长，远小于上限 2100000000。
// versionName：<基础版本>.<构建号>，例如 1.1.7
// 文件名：      gzhu-kb-<基础版本>-build<构建号>.apk
//
// 构建号存在 build-number.json 里，每次构建 +1。该文件应提交/保留，
// 删掉只会让编号重新从 1 开始（versionCode 也随之变小，可能导致无法覆盖安装）。
const fs = require('fs');
const path = require('path');

const STATE_FILE = 'build-number.json';
/**
 * 基数偏移。作用有两个：
 *   1. 保证 versionCode 恒为正（Android 期望正数）
 *   2. 恒大于早期手写的 1/2，任何情况下都能覆盖安装
 */
const BASE_OFFSET = 1000000;

function pad(n) {
  return String(n).padStart(2, '0');
}

/** 读出当前构建号（文件不存在时为 0） */
function readBuildNumber(root) {
  const f = path.join(root, STATE_FILE);
  if (!fs.existsSync(f)) return { build: 0, lastAt: null };
  try {
    const j = JSON.parse(fs.readFileSync(f, 'utf8'));
    return { build: Number(j.build) || 0, lastAt: j.lastAt || null };
  } catch (e) {
    return { build: 0, lastAt: null };
  }
}

function writeBuildNumber(root, build, at, versionName) {
  fs.writeFileSync(path.join(root, STATE_FILE), JSON.stringify({
    build,
    lastAt: at,
    lastVersionName: versionName,
    note: '每次构建自增；删掉会让编号从 1 重新开始，可能导致无法覆盖安装',
  }, null, 2), 'utf8');
}

/**
 * 分配一个新的构建编号（自增并落盘）。
 * @param {string} root 项目根目录
 * @param {string} baseVersion 基础版本号，如 "1.1"
 * @param {Date} now
 */
function nextBuild(root, baseVersion, now = new Date()) {
  const cur = readBuildNumber(root);
  const build = cur.build + 1;
  const versionName = `${baseVersion}.${build}`;
  const readable = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} `
    + `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
  writeBuildNumber(root, build, readable, versionName);

  return {
    build,
    versionCode: BASE_OFFSET + build,
    versionName,
    apkBase: `gzhu-kb-${baseVersion}-build${build}`,
    readable,
    previous: cur.build,
  };
}

const MANIFEST = 'app/AndroidManifest.xml';

/** 读出清单里当前的 versionCode / versionName */
function readManifestVersion(manifestPath) {
  const xml = fs.readFileSync(manifestPath, 'utf8');
  return {
    versionCode: (xml.match(/android:versionCode="(\d+)"/) || [])[1],
    versionName: (xml.match(/android:versionName="([^"]+)"/) || [])[1],
  };
}

/** 把版本号写回清单，保证 aapt2、文件名、运行时看到的是同一个值 */
function writeManifestVersion(manifestPath, versionCode, versionName) {
  let xml = fs.readFileSync(manifestPath, 'utf8');
  xml = xml.replace(/android:versionCode="\d+"/, `android:versionCode="${versionCode}"`);
  xml = xml.replace(/android:versionName="[^"]*"/, `android:versionName="${versionName}"`);
  fs.writeFileSync(manifestPath, xml, 'utf8');
}

/** 分配编号 + 写回清单，一步到位 */
function applyVersion(root, baseVersion, now = new Date()) {
  const v = nextBuild(root, baseVersion, now);
  writeManifestVersion(path.join(root, MANIFEST), v.versionCode, v.versionName);
  return v;
}

module.exports = {
  nextBuild, applyVersion, readBuildNumber, readManifestVersion, writeManifestVersion,
  BASE_OFFSET, STATE_FILE,
};

if (require.main === module) {
  const root = __dirname;
  const base = process.argv[2] || '1.1';
  const cur = readBuildNumber(root);
  const man = readManifestVersion(path.join(root, MANIFEST));
  console.log('当前构建号     : ' + cur.build + (cur.lastAt ? '（' + cur.lastAt + '）' : ''));
  console.log('清单里的版本   : versionCode=' + man.versionCode + ' versionName=' + man.versionName);
  const next = BASE_OFFSET + cur.build + 1;
  console.log('下次构建将使用 : build' + (cur.build + 1) + '  versionCode=' + next
    + '  versionName=' + base + '.' + (cur.build + 1));
  console.log('               文件名 gzhu-kb-' + base + '-build' + (cur.build + 1) + '.apk');
}
