/* =============== 首帧防闪 ===============
 * 在 <head> 里同步执行，早于任何内容渲染。
 *
 * 起因：登录页与课表页默认都带 hidden，第一帧本来什么都不显示。但脚本若在
 * <body> 末尾（或带 defer）才执行，浏览器可能已经先渲染过一次 HTML，
 * 于是"两块同时出现"闪一下——在手机上看起来就像出现了两版登录窗口。
 *
 * 这里不直接操作 DOM（此时还没有元素），只做两件事：
 *   1. 给 <html> 打上 js 标记，CSS 用它把两块都藏住，直到 app.js 宣布就绪；
 *   2. 兜底定时器——万一 app.js 因语法错误等原因没跑起来，3 秒后也要把内容放出来，
 *      否则页面会永远空白，比闪一下难排查得多。
 */
document.documentElement.classList.add('js');
setTimeout(function () {
  document.documentElement.classList.remove('js');
}, 3000);
