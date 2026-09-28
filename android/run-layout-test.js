// 小组件排版算法的单元测试（纯计算，不依赖 Android API）
// 运行：node run-layout-test.js
//
// 注意：本机沙箱禁止 Node 用管道捕获子进程输出(EPERM)，所以统一走文件重定向。
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const JAVA_HOME = require('./paths').requireJavaHome('run-layout-test.js');
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe');
const JAVA = path.join(JAVA_HOME, 'bin', 'java.exe');
const ROOT = __dirname;
const SRC = path.join(ROOT, 'app', 'src', 'cn', 'edu', 'gzhu', 'kb');
const OUT = path.join(ROOT, 'build', 'layouttest');
const LOG_OUT = path.join(ROOT, 'build', '_t_out.log');
const LOG_ERR = path.join(ROOT, 'build', '_t_err.log');

function run(exe, args) {
  fs.mkdirSync(path.dirname(LOG_OUT), { recursive: true });
  const o = fs.openSync(LOG_OUT, 'w');
  const e = fs.openSync(LOG_ERR, 'w');
  const r = spawnSync(exe, args, { cwd: ROOT, stdio: ['ignore', o, e] });
  fs.closeSync(o);
  fs.closeSync(e);
  const stdout = fs.readFileSync(LOG_OUT, 'utf8');
  const stderr = fs.readFileSync(LOG_ERR, 'utf8');
  return { status: r.status, error: r.error, output: stdout + stderr };
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const files = ['Json.java', 'WidgetLayout.java', 'KbStore.java'].map((f) => path.join(SRC, f));
files.push(path.join(ROOT, 'test', 'WidgetLayoutTest.java'));

let r = run(JAVAC, ['-encoding', 'UTF-8', '-nowarn', '-d', OUT, ...files]);
if (r.error || r.status !== 0) {
  console.error('编译失败:');
  console.error(r.output.trim().slice(0, 4000));
  process.exit(1);
}

r = run(JAVA, ['-Dfile.encoding=UTF-8', '-cp', OUT, 'WidgetLayoutTest']);
console.log(r.output.trim());
process.exit(r.output.includes('全部通过') ? 0 : 1);
