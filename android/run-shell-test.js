// 请求拦截链路的验证入口（用 Android 桩件在 JVM 上跑真实代码）
// 运行：node run-shell-test.js
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const JAVA_HOME = require('./paths').requireJavaHome('run-shell-test.js');
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe');
const JAVA = path.join(JAVA_HOME, 'bin', 'java.exe');
const ROOT = __dirname;
const SRC = path.join(ROOT, 'app', 'src', 'cn', 'edu', 'gzhu', 'kb');
const STUBS = path.join(ROOT, 'test', 'stubs');
const OUT = path.join(ROOT, 'build', 'shelltest');
const LOG_OUT = path.join(ROOT, 'build', '_s_out.log');
const LOG_ERR = path.join(ROOT, 'build', '_s_err.log');

function run(exe, args) {
  fs.mkdirSync(path.dirname(LOG_OUT), { recursive: true });
  const o = fs.openSync(LOG_OUT, 'w');
  const e = fs.openSync(LOG_ERR, 'w');
  const r = spawnSync(exe, args, { cwd: ROOT, stdio: ['ignore', o, e] });
  fs.closeSync(o);
  fs.closeSync(e);
  return {
    status: r.status,
    error: r.error,
    output: fs.readFileSync(LOG_OUT, 'utf8') + fs.readFileSync(LOG_ERR, 'utf8'),
  };
}

function walk(dir, ext, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, ext, acc);
    else if (!ext || full.endsWith(ext)) acc.push(full);
  }
  return acc;
}

// WebShell 的依赖闭包。
// CredStore / FileKbStore / KbWidgetProvider 这些真正依赖 android.jar 的类不参与，
// 存储位置改由 MemoryStores 注入 —— 这也是把存储抽成接口的收益之一。
const NEEDED = [
  'Json.java', 'KbStore.java', 'CredentialStore.java', 'MemoryStores.java',
  'Http.java', 'School.java', 'Des.java', 'KbModel.java',
  'WebRouter.java', 'WebShell.java', 'KbRepository.java', 'Diag.java',
  'LoginState.java',
];

// 断言读的是 app/assets/www 里的副本，所以先把前端同步过去。
// 不这么做的话，改了 server/public 但没重新打包，这里测的会是旧代码。
{
  const { syncWebAssets, staleFiles } = require('./web-assets');
  const stale = staleFiles();
  if (stale.length) console.log('检测到 assets 里的前端不是最新，先同步: ' + stale.join('、') + '\n');
  syncWebAssets(null);
}

const files = NEEDED.map((f) => path.join(SRC, f))
  .concat(walk(STUBS, '.java'))
  .concat([path.join(ROOT, 'test', 'WebShellTest.java')]);

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

let r = run(JAVAC, ['-encoding', 'UTF-8', '-nowarn', '-d', OUT, ...files]);
if (r.error || r.status !== 0) {
  console.error('编译失败:');
  console.error(r.output.trim().slice(0, 5000));
  process.exit(1);
}

r = run(JAVA, ['-Dfile.encoding=UTF-8', '-cp', OUT, 'WebShellTest']);
console.log(r.output.trim());
process.exit(r.output.includes('全部通过') ? 0 : 1);
