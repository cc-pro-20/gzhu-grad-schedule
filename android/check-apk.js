// APK 打包正确性检查。这些问题的共同点是「编译期完全看不出来，
// 只有装到手机上才暴露」，所以必须在构建阶段拦住。
//
// 已覆盖的两个真实踩坑：
//   1. aapt2 在 Windows 上把 assets 条目名写成反斜杠 -> AssetManager 读不到 -> 页面 404
//   2. resources.arsc 被压缩或未对齐 -> Android 11+ 安装器直接拒装（"兼容性问题"）
const fs = require('fs');
const path = require('path');
const { readZip } = require('./zip-tool');

/** 必须存在的关键条目 */
const REQUIRED = [
  'AndroidManifest.xml',
  'resources.arsc',
  'classes.dex',
  'assets/www/index.html',
  'assets/www/style.css',
  'assets/www/app.js',
];

/** 不允许出现的条目（临时产物或残留） */
function isJunk(name) {
  return name.startsWith('build/') || name.startsWith('apk-fix/')
      || name.toLowerCase().endsWith('.apk');
}

/** resources.arsc 必须是未压缩（stored）且 4 字节对齐 —— Android 11+ 的硬性要求 */
function checkArsc(apkAbs) {
  const buf = fs.readFileSync(apkAbs);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 65536; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) return ['无法定位 zip 中央目录'];
  const total = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const problems = [];
  for (let n = 0; n < total; n++) {
    const method = buf.readUInt16LE(off + 10);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    if (name === 'resources.arsc') {
      const lNameLen = buf.readUInt16LE(localOff + 26);
      const lExtraLen = buf.readUInt16LE(localOff + 28);
      const dataOffset = localOff + 30 + lNameLen + lExtraLen;
      if (method !== 0) {
        problems.push('resources.arsc 被压缩了（method=' + method + '），Android 11+ 会拒装');
      }
      if (dataOffset % 4 !== 0) {
        problems.push('resources.arsc 未 4 字节对齐（dataOffset=' + dataOffset + '）');
      }
    }
    off += 46 + nameLen + extraLen + commentLen;
  }
  return problems;
}

/**
 * @return {string[]} 问题列表，空数组表示通过
 */
function checkApk(apkAbs) {
  const problems = [];
  if (!fs.existsSync(apkAbs)) return ['APK 不存在: ' + apkAbs];

  let names;
  try {
    names = readZip(apkAbs).map((e) => e.name);
  } catch (e) {
    return ['无法读取 APK 条目: ' + e.message];
  }

  // 1) 反斜杠 —— 会导致 AssetManager 读不到资源
  const backslash = names.filter((n) => n.includes('\\'));
  if (backslash.length > 0) {
    problems.push('条目名含反斜杠（AssetManager 读不到）: '
      + backslash.map((n) => JSON.stringify(n)).join(', '));
  }

  // 2) 关键条目齐全
  const set = new Set(names);
  for (const r of REQUIRED) {
    if (!set.has(r)) problems.push('缺少必需条目: ' + r);
  }

  // 3) 混入多余文件
  const junk = names.filter(isJunk);
  if (junk.length > 0) problems.push('混入多余条目: ' + junk.join(', '));

  // 4) resources.arsc 压缩方式与对齐
  try {
    problems.push(...checkArsc(apkAbs));
  } catch (e) {
    problems.push('检查 resources.arsc 失败: ' + e.message);
  }

  return problems;
}

module.exports = { checkApk, REQUIRED };

if (require.main === module) {
  const apk = path.resolve(__dirname, process.argv[2] || 'out/gzhu-kb-1.1.apk');
  const problems = checkApk(apk);
  if (problems.length === 0) {
    console.log('APK 打包检查通过: ' + path.basename(apk));
    console.log('  条目数 ' + readZip(apk).length
      + '；无反斜杠；关键文件齐全；resources.arsc 未压缩且 4 字节对齐');
  } else {
    console.log('APK 打包检查未通过:');
    problems.forEach((p) => console.log('  - ' + p));
    process.exit(1);
  }
}
