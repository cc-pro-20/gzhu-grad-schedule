/**
 * 路径解析：让所有脚本不依赖"开发机上的绝对路径"。
 *
 * 起因：这些脚本原本把 JDK 位置写死成 `D:\JDk`、把前端目录写死成
 * `D:\自用\server\public`。别人 clone 下来一跑就找不到文件——仓库里不该有
 * 任何只对某台机器成立的路径。
 *
 * 规则（按优先级）：
 *   JAVA_HOME  环境变量 → 常见安装位置 → 报错并给出提示
 *   (Windows 上可执行文件带 .exe，其它平台不带)
 *
 * 前端目录不用配：它就是仓库里的 server/public，按相对位置算即可。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const EXE = process.platform === 'win32' ? '.exe' : '';

/** 依次找 JDK：环境变量 → 常见位置。找不到就明确报错，不要静默用错的 */
function resolveJavaHome() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);

  if (process.platform === 'win32') {
    candidates.push(
      'D:\\JDk',
      'C:\\Program Files\\Java\\jdk-17',
      'C:\\Program Files\\Java\\jdk-21',
      'C:\\Program Files\\Eclipse Adoptium\\jdk-17'
    );
    // 从 PATH 里的 java.exe 反推
    for (const dir of (process.env.PATH || '').split(path.delimiter)) {
      if (!dir) continue;
      try {
        if (fs.existsSync(path.join(dir, 'java.exe'))) {
          candidates.push(path.dirname(dir) === dir ? dir : path.dirname(dir));
        }
      } catch (e) { /* 忽略无权限的目录 */ }
    }
  } else {
    candidates.push('/usr/lib/jvm/default-java', '/usr/local/opt/openjdk');
  }

  for (const c of candidates) {
    if (!c) continue;
    try {
      if (fs.existsSync(path.join(c, 'bin', 'javac' + EXE))) return c;
    } catch (e) { /* 忽略 */ }
  }
  return null;
}

/**
 * 取 JDK 路径；找不到时抛出带指引的错误。
 * @param {string} scriptName 出错信息里显示是谁在找，方便定位
 */
function requireJavaHome(scriptName) {
  const home = resolveJavaHome();
  if (!home) {
    throw new Error(
      (scriptName || '该脚本') + ' 找不到 JDK。\n' +
      '  请设置环境变量 JAVA_HOME 指向 JDK 安装目录，例如：\n' +
      '    Windows:  set JAVA_HOME=C:\\Program Files\\Java\\jdk-17\n' +
      '    Linux/macOS:  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n' +
      '  需要的是 JDK（含 javac），不是 JRE。'
    );
  }
  return home;
}

/** 前端源码目录：仓库里的 server/public，与 android/ 同级 */
function webSrc() {
  return path.join(ROOT, '..', 'server', 'public');
}

module.exports = { ROOT, EXE, resolveJavaHome, requireJavaHome, webSrc };
