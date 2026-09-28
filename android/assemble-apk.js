// 从 aapt2 产物组装出可安装的未签名 APK。
//
// 为什么不用 jar：`jar uf` 会把 resources.arsc 压成 deflate 并破坏 4 字节对齐，
// 而 Android 11+ 要求它 **未压缩 + 4 字节对齐**，否则安装器报"兼容性问题"直接拒装。
// 所以这里用 zip-tool.js 自己写，精确控制：
//   1. resources.arsc 强制 stored + 4 字节对齐
//   2. assets 条目名里的反斜杠换成目录层级（aapt2 在 Windows 上的问题）
//   3. 追加 classes.dex
const fs = require('fs');
const path = require('path');
const { readZip, writeZip } = require('./zip-tool');

/**
 * @param {string} linkedApk aapt2 link 的产物
 * @param {string} dexFile   已编译好的 classes.dex
 * @param {string} outApk    输出路径
 * @returns {{entries:number, fixedAssets:string[], arsc:string}}
 */
function assembleApk(linkedApk, dexFile, outApk) {
  const entries = readZip(linkedApk);

  // 1) 修正 assets 条目名：aapt2 在 Windows 上会写成 `assets/www\index.html`
  const fixedAssets = [];
  for (const e of entries) {
    if (e.name.includes('\\')) {
      const oldName = e.name;
      // 把完整名字里的反斜杠当作目录分隔符处理
      e.name = oldName.split('\\').filter(Boolean).join('/');
      fixedAssets.push(`${JSON.stringify(oldName)} -> ${JSON.stringify(e.name)}`);
    }
  }

  // 2) 追加 classes.dex（若已存在则替换）
  const dex = fs.readFileSync(dexFile);
  const zlib = require('zlib');
  const { crc32 } = require('./zip-tool');
  const dexComp = zlib.deflateRawSync(dex, { level: 9 });
  const dexEntry = {
    name: 'classes.dex',
    method: 8,
    flags: 0,
    modTime: 0,
    modDate: 0,
    crc: crc32(dex),
    compSize: dexComp.length,
    uncompSize: dex.length,
    externalAttr: 0,
    data: dexComp,
    isDir: false,
  };
  const idx = entries.findIndex((e) => e.name === 'classes.dex');
  if (idx >= 0) entries[idx] = dexEntry;
  else entries.push(dexEntry);

  // 3) 写出：resources.arsc 必须 stored + 4 字节对齐
  const n = writeZip(outApk, entries, ['resources.arsc']);

  // 4) 自证：写入后立刻复核
  const check = readZip(outApk).find((e) => e.name === 'resources.arsc');
  if (!check) throw new Error('写出后找不到 resources.arsc');

  return { entries: n, fixedAssets };
}

module.exports = { assembleApk };

if (require.main === module) {
  const [linked, dex, out] = process.argv.slice(2);
  if (!linked || !dex || !out) {
    console.error('用法: node assemble-apk.js <linked.apk> <classes.dex> <out.apk>');
    process.exit(1);
  }
  const r = assembleApk(
    path.resolve(__dirname, linked),
    path.resolve(__dirname, dex),
    path.resolve(__dirname, out));
  console.log(`写出 ${r.entries} 个条目；修正 assets: ${r.fixedAssets.length} 个`);
  r.fixedAssets.forEach((s) => console.log('  ' + s));
}
