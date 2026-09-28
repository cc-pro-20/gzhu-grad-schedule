/**
 * 真实账号联调：跑一遍异步登录 + 取课表（需要外网，且能访问学校系统）。
 *
 * 用法：
 *   set GZHU_USER=2000000000
 *   set GZHU_PASS=...
 *   node run-live-check.js
 *
 * 与 run-shell-test.js 的区别：那边用桩件证明"路由与页面资源正确"，
 * 这里证明"这套逻辑真能连上学校系统拿到课表"。两者都需要。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const JAVA_HOME = require('./paths').requireJavaHome('run-live-check.js');
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe');
const JAVA = path.join(JAVA_HOME, 'bin', 'java.exe');
const ROOT = __dirname;
const SRC = path.join(ROOT, 'app', 'src', 'cn', 'edu', 'gzhu', 'kb');
const STUBS = path.join(ROOT, 'test', 'stubs');
const OUT = path.join(ROOT, 'build', 'livetest');
const LOG_OUT = path.join(ROOT, 'build', '_live_out.log');
const LOG_ERR = path.join(ROOT, 'build', '_live_err.log');

const user = process.env.GZHU_USER;
const pass = process.env.GZHU_PASS;
if (!user || !pass) {
  console.error('请先设置环境变量 GZHU_USER / GZHU_PASS');
  process.exit(1);
}

function run(exe, args, extraEnv) {
  fs.mkdirSync(path.dirname(LOG_OUT), { recursive: true });
  const o = fs.openSync(LOG_OUT, 'w');
  const e = fs.openSync(LOG_ERR, 'w');
  const r = spawnSync(exe, args, {
    cwd: ROOT,
    stdio: ['ignore', o, e],
    env: extraEnv ? Object.assign({}, process.env, extraEnv) : process.env,
  });
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

// 与实机相同的代码路径；只把 Android 相关的存储换成内存实现
const NEEDED = [
  'Json.java', 'KbStore.java', 'CredentialStore.java', 'MemoryStores.java',
  'Http.java', 'School.java', 'Des.java', 'KbModel.java',
  'KbRepository.java', 'LoginState.java',
];

const files = NEEDED.map((f) => path.join(SRC, f))
  .concat(walk(STUBS, '.java'))
  .concat([path.join(ROOT, 'test', 'LiveLoginCheck.java')]);

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

let r = run(JAVAC, ['-encoding', 'UTF-8', '-nowarn', '-d', OUT, ...files]);
if (r.error || r.status !== 0) {
  console.error('编译失败:');
  console.error(r.output.trim().slice(0, 5000));
  process.exit(1);
}

r = run(JAVA, ['-Dfile.encoding=UTF-8', '-cp', OUT, 'LiveLoginCheck'], {
  GZHU_USER: user,
  GZHU_PASS: pass,
});
console.log(r.output.trim());
process.exit(r.output.includes('全部通过') ? 0 : 1);
