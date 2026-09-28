// 直接读 APK 里的 assets/www/index.html，确认打包进去的内容到底长什么样。
// 用途：构建脚本会注入版本号，而测试脚本会把 assets 重新同步成"开发预览"标签，
// 所以磁盘上的 assets 不一定等于 APK 里的内容——要确认必须读 APK 本身。
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { readZip } = require('./zip-tool');

const apk = process.argv[2] || (() => {
  const dir = path.join(__dirname, 'out');
  const a = fs.readdirSync(dir).filter((n) => n.endsWith('.apk'))
    .map((n) => ({ n, t: fs.statSync(path.join(dir, n)).mtimeMs }))
    .sort((x, y) => y.t - x.t);
  return path.join(dir, a[0].n);
})();

const entries = readZip(apk);
console.log('APK: ' + path.basename(apk));

// readZip 给的是条目的**原始字节**（method=8 时是 deflate 后的数据），
// 所以文本资源要自己解压。文档里说的"已解压"是错的，以实测为准。
function readAsset(name) {
  const e = entries.find((x) => x.name === name);
  if (!e) return null;
  const data = e.method === 0 ? e.data : zlib.inflateRawSync(e.data);
  return data.toString('utf8');
}

const html = readAsset('assets/www/index.html');
if (!html) {
  console.log('找不到 assets/www/index.html');
  process.exit(1);
}
const pick = (re) => {
  const m = html.match(re);
  return m ? m[1] : '(未找到)';
};
console.log('  title    = ' + pick(/<title>([^<]*)<\/title>/));
console.log('  h1       = ' + pick(/<h1>([^<]*)<\/h1>/));
console.log('  版本号   = ' + pick(/<p class="build-id">([^<]*)<\/p>/));
console.log('  仓库链接 = ' + pick(/href="(https:\/\/github[^"]*)"/));

const css = readAsset('assets/www/style.css');
console.log('  CSS 修复 = ' +
  ['.app-footer', 'flex: 0 0 auto', 'flex: 0 0 var(--time-w)']
    .map((k) => k + (css.includes(k) ? '✓' : '✗')).join('  '));

const js = readAsset('assets/www/app.js');
console.log('  前端接口 = ' +
  ['/api/login-start', '/api/login-result', 'dataset.from']
    .map((k) => k + (js.includes(k) ? '✓' : '✗')).join('  '));
