/**
 * 课表数据诊断入口：排查"某一节有课但没显示"这类问题。
 *
 * 用法：
 *   set GZHU_USER=...
 *   set GZHU_PASS=...
 *   node run-day-probe.js            # 默认查周一第 5-6 节
 *   node run-day-probe.js 3 1 4      # 查周三第 1-4 节
 *
 * 与 run-live-check.js 一样复用 App 里真实的 School / KbModel 代码，
 * 所以看到的就是手机上会拿到的同一份数据。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const JAVA_HOME = require('./paths').requireJavaHome('run-day-probe.js');
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe');
const JAVA = path.join(JAVA_HOME, 'bin', 'java.exe');
const ROOT = __dirname;
const SRC = path.join(ROOT, 'app', 'src', 'cn', 'edu', 'gzhu', 'kb');
const STUBS = path.join(ROOT, 'test', 'stubs');
const OUT = path.join(ROOT, 'build', 'dayprobe');
const LOG_OUT = path.join(ROOT, 'build', '_probe_out.log');
const LOG_ERR = path.join(ROOT, 'build', '_probe_err.log');

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

// 与 App 相同的取数与合并代码
const NEEDED = [
  'Json.java', 'KbStore.java', 'CredentialStore.java', 'MemoryStores.java',
  'Http.java', 'School.java', 'Des.java', 'KbModel.java',
];

const files = NEEDED.map((f) => path.join(SRC, f))
  .concat(walk(STUBS, '.java'))
  .concat([path.join(ROOT, 'test', 'DayProbe.java')]);

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

let r = run(JAVAC, ['-encoding', 'UTF-8', '-nowarn', '-d', OUT, ...files]);
if (r.error || r.status !== 0) {
  console.error('编译失败:');
  console.error(r.output.trim().slice(0, 5000));
  process.exit(1);
}

const extra = process.argv.slice(2);
r = run(JAVA, ['-Dfile.encoding=UTF-8', '-cp', OUT, 'DayProbe', ...extra], {
  GZHU_USER: user,
  GZHU_PASS: pass,
});
console.log(r.output.trim());
process.exit(r.status === 0 ? 0 : r.status || 1);
