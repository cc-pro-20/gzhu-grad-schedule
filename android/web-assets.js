/**
 * 网页资源同步：server/public -> app/assets/www，并注入构建版本号。
 *
 * 单独抽出这个模块的原因：不止打包脚本需要它，请求拦截的回归测试
 * （run-shell-test.js）也是读 app/assets/www 里的副本来跑断言的。
 * 以前测试直接读那份副本，一旦忘了先打包，测试就会针对**上一次**的前端
 * 代码给出结论——看起来通过，实际测的是旧代码。让两边共用同一个同步函数，
 * 这个问题就不存在了。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const WEB_SRC = require('./paths').webSrc();
const ASSETS_WWW = path.join(ROOT, 'app', 'assets', 'www');

/** 需要同步进 assets 的网页文件（与 server.js 的静态白名单一致） */
const WEB_FILES = ['index.html', 'style.css', 'app.js', 'boot.js'];

const VERSION_TOKEN = '%BUILD_VERSION%';

/**
 * 把前端文件复制到 app/assets/www。
 *
 * @param {{versionName:string, build:number|string}} version 用于注入页面的版本信息
 * @returns {{files:string[], version:string}}
 */
function syncWebAssets(version) {
  if (!fs.existsSync(WEB_SRC)) throw new Error('缺少前端目录: ' + WEB_SRC);
  fs.mkdirSync(ASSETS_WWW, { recursive: true });

  const copied = [];
  for (const f of WEB_FILES) {
    const from = path.join(WEB_SRC, f);
    if (!fs.existsSync(from)) throw new Error('缺少前端文件: ' + from);
    fs.copyFileSync(from, path.join(ASSETS_WWW, f));
    copied.push(f);
  }

  const versionText = version
    ? `构建 ${version.versionName}（build${version.build}）`
    : '开发预览（未打包）';

  const pagePath = path.join(ASSETS_WWW, 'index.html');
  let page = fs.readFileSync(pagePath, 'utf8');
  if (!page.includes(VERSION_TOKEN)) {
    throw new Error('index.html 里找不到 ' + VERSION_TOKEN + ' 占位符，无法注入版本号');
  }
  // 注释里也提到了这个占位符，只替换 <p> 里的那一处
  page = page.replace(`<p class="build-id">${VERSION_TOKEN}</p>`,
    `<p class="build-id">${versionText}</p>`);
  if (page.includes(VERSION_TOKEN)) throw new Error('版本号注入不完整，仍有残留占位符');
  fs.writeFileSync(pagePath, page, 'utf8');

  return { files: copied, version: versionText };
}

/** 某个前端文件比 assets 里的副本更新？用于提醒"测试前先同步" */
function staleFiles() {
  const out = [];
  for (const f of WEB_FILES) {
    const src = path.join(WEB_SRC, f);
    const dst = path.join(ASSETS_WWW, f);
    if (!fs.existsSync(src)) { out.push(f + '（源文件不存在）'); continue; }
    if (!fs.existsSync(dst)) { out.push(f + '（assets 里没有）'); continue; }
    if (fs.statSync(src).mtimeMs > fs.statSync(dst).mtimeMs + 1000) out.push(f + '（源文件更新）');
  }
  return out;
}

module.exports = { syncWebAssets, staleFiles, WEB_FILES, WEB_SRC, ASSETS_WWW, VERSION_TOKEN };
