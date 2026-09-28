// 用站点自带的 des.js 生成测试向量，用于校验 Java 移植是否逐位一致。
//
// 跑法：node gen-vectors.js
//
// des.js 来自学校登录页（第三方代码，头部带 2006 年的原始版权声明），
// 仓库里放在 server/des.js，这里按**相对路径**引用——
// 以前写的是开发机上的绝对路径，别人 clone 下来根本跑不了。
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const DES_JS = path.join(__dirname, '..', 'server', 'des.js');
if (!fs.existsSync(DES_JS)) {
  console.error('找不到 ' + DES_JS);
  console.error('这个脚本需要 server/des.js（校方登录页自带的加密实现）。');
  process.exit(1);
}
const desSrc = fs.readFileSync(DES_JS, 'utf8');
const sb = { window: {}, navigator: {} };
vm.createContext(sb);
vm.runInContext(desSrc, sb);
const strEnc = sb.strEnc;
if (typeof strEnc !== 'function') {
  console.error('server/des.js 里没有 strEnc 函数，无法生成向量');
  process.exit(1);
}

// 真实场景：un + pd + lt，密钥固定为 '1','2','3'
//
// ⚠️ 这里的数据必须是**假造的**。
// 第一组原本用的是本人的真实学号与密码，生成出来的 des-vectors.json 里会带明文，
// 一旦提交就等于公开自己的密码。现在换成等长的假数据：长度与字符类型保持
// 一致，所以对 strEnc 的覆盖面没有降低，但内容不含任何真实信息。
// 如果你要加新的用例，也请一律用假数据。
const cases = [
  ['2000000000', 'FakePass2026!x', 'LT-186476-GNlYaZfXqqz6FO06yBBZolSAEzXaS7-cas'],
  ['a', 'b', 'c'],
  ['abcd', '1234', 'LT-1'],
  ['测试中文', 'pwd', 'LT-x'],
  ['abcdefghij', 'password123', 'LT-9999999999-cas'],
  ['12345678', 'x', 'LT-1'],
  ['1234', 'x', 'LT-1'],
  ['123', 'x', 'LT-1'],
  ['1', '', ''],
  ['0', '0', '0'],
  ['!@#$%^&*()_+', '~`|\\:";\'<>?,./', 'LT-abc'],
];

const out = cases.map(([un, pd, lt]) => {
  const plain = un + pd + lt;
  return { un, pd, lt, plain, rsa: strEnc(plain, '1', '2', '3') };
});
fs.writeFileSync(__dirname + '/des-vectors.json', JSON.stringify(out, null, 1));
out.forEach((o, i) => console.log(`[${i}] plain="${o.plain}"\n     rsa=${o.rsa}`));
