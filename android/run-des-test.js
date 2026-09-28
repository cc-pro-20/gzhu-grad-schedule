/**
 * DES 向量校验：确认 Java 版 strEnc 与校方 des.js 输出逐位一致。
 *
 * 这是登录能否成功的关键（密码错了就是登录失败），所以单独有个入口。
 * 期望值由 gen-vectors.js 用 des.js 生成，不要手改。
 *
 * 用法：
 *   node gen-vectors.js      # 先生成/更新 des-vectors.json
 *   node run-des-test.js     # 校验（11/11 通过才算好）
 *
 * 注意：编译产物放在 build/destest，而 build/ 会被 build-apk.js 重建清掉，
 * 所以重新打包后要再跑一次这个脚本（脚本自己会重新编译，直接跑即可）。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const JAVA_HOME = require('./paths').requireJavaHome('run-des-test.js');
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe');
const JAVA = path.join(JAVA_HOME, 'bin', 'java.exe');
const ROOT = __dirname;
const OUT = path.join(ROOT, 'build', 'destest');
const LOG_OUT = path.join(ROOT, 'build', '_des_out.log');
const LOG_ERR = path.join(ROOT, 'build', '_des_err.log');

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

const vectors = path.join(ROOT, 'des-vectors.json');
if (!fs.existsSync(vectors)) {
  console.error('缺少 des-vectors.json，请先执行: node gen-vectors.js');
  process.exit(1);
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

let r = run(JAVAC, [
  '-encoding', 'UTF-8', '-nowarn', '-d', OUT,
  path.join('app', 'src', 'cn', 'edu', 'gzhu', 'kb', 'Des.java'),
  path.join('test', 'DesCheck.java'),
]);
if (r.error || r.status !== 0) {
  console.error('编译失败:');
  console.error(r.output.trim().slice(0, 4000));
  process.exit(1);
}

r = run(JAVA, ['-Dfile.encoding=UTF-8', '-cp', OUT, 'DesCheck', 'des-vectors.json']);
console.log(r.output.trim());
process.exit(/11\/11 通过/.test(r.output) ? 0 : 1);
