/**
 * 课表页面本地预览（不连学校系统）
 *
 * 目的：布局只能靠看，不能靠推理。这里起一个本地服务，把 /api/* 全部换成
 * 伪造数据，于是能在电脑上用浏览器（或 headless 截图）真实渲染一遍课表页面，
 * 检查左栏时间、课程卡片、周次切换等显示是否正确。
 *
 * 用法：
 *   node preview.js              启动并打印地址
 *   node preview.js --shot       用 Chrome headless 截图到 build/preview-*.png
 *
 * 只读 server/public 下的前端文件，不写入任何东西（截图除外）。
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const WEB_SRC = require('./paths').webSrc();
const ROOT = __dirname;
const PORT = Number(process.env.PREVIEW_PORT || 8791);

// ---------------------------------------------------------------- 伪造数据

/** 节次方案：与学校返回的结构一致（dm/kssj/jssj） */
const JIECI = [
  ['1', '08:30', '09:15'],
  ['2', '09:20', '10:05'],
  ['3', '10:25', '11:10'],
  ['4', '11:15', '12:00'],
  ['5', '13:50', '14:35'],
  ['6', '14:40', '15:25'],
  ['7', '15:45', '16:30'],
  ['8', '16:35', '17:20'],
  ['9', '18:20', '19:05'],
  ['10', '19:10', '19:55'],
  ['11', '20:00', '20:45'],
].map(([dm, kssj, jssj]) => ({ dm, kssj, jssj, jcfadm: '1', mc: `第${dm}节` }));

const COLORS = [
  ['#e8f3ff', '#1b4f8c'],
  ['#e9f7ef', '#1c6b43'],
  ['#fdf0e6', '#8a4b18'],
  ['#f3ebfd', '#5b3a8e'],
  ['#fdeaea', '#8c2b2b'],
];

/** 课程块：结构与 KbModel.build 的 flat 输出一致 */
const COURSES = [
  { kcmc: '高等数值分析', xq: 1, from: 0, to: 1, span: 2, jasmc: '理科北楼 305', jsxm: '陈老师', kssj: '08:30', jssj: '10:05', zcmc: '1-16周', kclb: '学位课', bjmc: '2026级学硕1班', color: COLORS[0] },
  { kcmc: '现代信号处理', xq: 1, from: 4, to: 5, span: 2, jasmc: '工科楼 512', jsxm: '李老师', kssj: '13:50', jssj: '15:25', zcmc: '1-12周', color: COLORS[1] },
  { kcmc: '机器学习导论', xq: 2, from: 2, to: 3, span: 2, jasmc: '计算机楼 208', jsxm: '王老师', kssj: '10:25', jssj: '12:00', zcmc: '1-16周', color: COLORS[2] },
  { kcmc: '学术英语写作', xq: 2, from: 8, to: 9, span: 2, jasmc: '文科楼 401', jsxm: 'Smith J.', kssj: '18:20', jssj: '19:55', zcmc: '3-14周', color: COLORS[3] },
  { kcmc: '专业前沿讲座', xq: 3, from: 0, to: 0, span: 1, jasmc: '报告厅', jsxm: '多位', kssj: '08:30', jssj: '09:15', zcmc: '5-8周', color: COLORS[4] },
  { kcmc: '矩阵论', xq: 3, from: 4, to: 5, span: 2, jasmc: '理科北楼 118', jsxm: '张老师', kssj: '13:50', jssj: '15:25', zcmc: '1-16周', color: COLORS[0] },
  { kcmc: '数字图像处理', xq: 4, from: 2, to: 4, span: 3, jasmc: '计算机楼 305', jsxm: '刘老师', kssj: '10:25', jssj: '12:00', zcmc: '1-10周', color: COLORS[1] },
  { kcmc: '科研方法论', xq: 4, from: 10, to: 10, span: 1, jasmc: '线上', jsxm: '赵老师', kssj: '20:00', jssj: '20:45', zcmc: '2-16周', color: COLORS[2] },
  { kcmc: '随机过程', xq: 5, from: 0, to: 1, span: 2, jasmc: '理科北楼 216', jsxm: '周老师', kssj: '08:30', jssj: '10:05', zcmc: '1-16周', color: COLORS[3] },
  { kcmc: '组会', xq: 5, from: 6, to: 7, span: 2, jasmc: '实验室', jsxm: '导师', kssj: '15:45', jssj: '17:20', zcmc: '1-18周', color: COLORS[4] },
];

const H_ROW = 76;

function buildGrid(week) {
  // 选几门课只在特定周出现，用来验证周次切换确实在变
  const flat = COURSES.map((c, i) => {
    const inWeek = week % 3 === 0 ? i % 4 !== 0 : true;
    return inWeek ? c : null;
  }).filter(Boolean);

  const rows = [];
  let y = 0;
  for (let i = 0; i < JIECI.length; i++) {
    rows.push({ index: i, top: y, height: H_ROW });
    y += H_ROW + 1;
  }
  const totalHeight = (JIECI.length - 1) * (H_ROW + 1) + H_ROW;
  return { flat, rows, totalHeight };
}

const DAY_DATES = ['2026-03-16', '03-17', '03-18', '03-19', '03-20', '03-21', '03-22'];

function kbPayload(week) {
  const g = buildGrid(week);
  return Object.assign({
    xnxqdm: '2025-2026-2',
    termName: '2025-2026学年第二学期',
    week,
    curWeek: 4,
    totalWeeks: 30,
    curDays: week === 4
      ? DAY_DATES.map((rq, i) => ({ xq: i + 1, rq, jt: false }))
      : [],
    jieci: JIECI,
  }, g);
}

/**
 * 布局探针：在页面里量出真实像素，把结果塞进 DOM 供 --dump-dom 读回。
 *
 * 为什么需要它：截图只能"看个大概"，而且手机宽度下到底有没有横向溢出
 * 必须靠数值判断（课表宽度 = 左栏 + 7 列）。把测量结果打印出来，
 * 就能像断言一样检查布局，而不是靠肉眼。
 */
const PROBE = `
<script>
window.addEventListener('load', function () {
  setTimeout(function () {
    var m = {};
    m.innerWidth = window.innerWidth;
    m.docScrollW = document.documentElement.scrollWidth;
    var tt = document.getElementById('timetable');
    var times = document.querySelector('.grid-times');
    var cols = document.querySelector('.grid-cols');
    var head = document.querySelector('.grid-head');
    var firstSlot = document.querySelector('.grid-times .g-time');
    var firstBlk = document.querySelector('.blk');
    var b = document.querySelector('.build-id');
    function box(el) {
      if (!el) return null;
      var r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height), l: Math.round(r.left), r: Math.round(r.right) };
    }
    m.timetable = box(tt);
    m.gridTimes = box(times);
    m.gridCols = box(cols);
    m.gridHead = box(head);
    m.firstTimeCell = box(firstSlot);
    m.firstBlock = box(firstBlk);
    m.buildId = box(b);
    // 底部信息条（仓库地址 + 版本号）现在随页面滚动，应当落在**课表下方**，
    // 而不是固定吸在屏幕底部压住课表。
    var footerEl = document.querySelector('.app-footer');
    m.footer = box(footerEl);
    var gridBodyEl = document.querySelector('.grid-cols');
    if (footerEl && gridBodyEl) {
      var fr = footerEl.getBoundingClientRect();
      var gr = gridBodyEl.getBoundingClientRect();
      var cs = window.getComputedStyle(footerEl);
      m.footerPosition = cs.position;
      m.footerH = Math.round(fr.height);
      // 它必须排在课表内容之后（文档流里在下方），且不覆盖课表
      m.footerBelowGrid = fr.top >= gr.bottom - 1;
      // 固定定位会让它一直吸在屏幕上——那正是用户不想要的
      m.footerIsFixed = cs.position === 'fixed';
    }
    // 仓库地址链接必须存在且可点（这是"方便后续更新"的关键）
    var repoEl = document.querySelector('.repo-link');
    m.repoHref = repoEl ? repoEl.getAttribute('href') : null;
    m.repoText = repoEl ? repoEl.textContent.trim() : null;
    if (repoEl) {
      var cs = window.getComputedStyle(repoEl);
      m.repoClickable = cs.pointerEvents !== 'none';
    }
    if (firstSlot) {
      m.timeCellText = firstSlot.textContent.replace(/\\s+/g, ' ').trim();
      m.timeCellLines = Math.round(firstSlot.getBoundingClientRect().height);
      m.timeCellOverflowY = firstSlot.scrollHeight > firstSlot.clientHeight + 1;
      m.timeCellOverflowX = firstSlot.scrollWidth > firstSlot.clientWidth + 1;
    }
    var allTimes = document.querySelectorAll('.grid-times .g-time');
    m.timeCellCount = allTimes.length;
    var missing = 0, overflowY = 0, overflowX = 0;
    for (var i = 0; i < allTimes.length; i++) {
      if (!/\\d\\d:\\d\\d/.test(allTimes[i].textContent)) missing++;
      if (allTimes[i].scrollHeight > allTimes[i].clientHeight + 1) overflowY++;
      if (allTimes[i].scrollWidth > allTimes[i].clientWidth + 1) overflowX++;
    }
    m.timeCellsWithoutTime = missing;
    m.timeCellsOverflowY = overflowY;
    m.timeCellsOverflowX = overflowX;

    // 表头星期 与 正下方课程列 的横向对齐检查。
    // 真机上出现过"表头'一'对不上下面的课列"，横向错位这种问题必须量左右边界，
    // 只比宽度会漏掉"整体平移"这一类。
    var headCells = document.querySelectorAll('.grid-head .g-cell');
    var gridTimesEl = document.querySelector('.grid-times');
    var bodyLeft = gridTimesEl ? gridTimesEl.getBoundingClientRect().left
                               + gridTimesEl.getBoundingClientRect().width : 0;
    m.colAlign = [];
    for (var c = 0; c < headCells.length && c < 7; c++) {
      var hr = headCells[c].getBoundingClientRect();
      var colEl = document.querySelectorAll('.grid-cols .grid-col')[c];
      var cr = colEl ? colEl.getBoundingClientRect() : null;
      m.colAlign.push({
        day: c + 1,
        headL: Math.round(hr.left), headR: Math.round(hr.right),
        colL: cr ? Math.round(cr.left) : null, colR: cr ? Math.round(cr.right) : null,
        dl: cr ? Math.round(cr.left - hr.left) : null,
        dr: cr ? Math.round(cr.right - hr.right) : null,
      });
    }
    m.maxColOffset = 0;
    for (var k = 0; k < m.colAlign.length; k++) {
      var o = m.colAlign[k];
      if (o.dl == null) continue;
      m.maxColOffset = Math.max(m.maxColOffset, Math.abs(o.dl), Math.abs(o.dr));
    }
    // 表头首格（"第N周"）与左栏时间列的分界是否一致
    var cornerEl = document.querySelector('.grid-head .g-time');
    m.headCornerRight = cornerEl ? Math.round(cornerEl.getBoundingClientRect().right) : null;
    m.leftColRight = gridTimesEl ? Math.round(gridTimesEl.getBoundingClientRect().right) : null;
    m.cornerOffset = (m.headCornerRight != null && m.leftColRight != null)
      ? Math.round(m.leftColRight - m.headCornerRight) : null;

    var pre = document.createElement('pre');
    pre.id = 'probe';

    // 逐块几何检查：卡片的实际像素是否覆盖了它声称的节次范围？
    // "跨两节的课只占了第一节的色块"这种问题只有量出来才算数。
    var slots = document.querySelectorAll('.grid-cols .grid-col:first-child .slot');
    var slotGeom = [];
    for (var s = 0; s < slots.length; s++) {
      var r = slots[s].getBoundingClientRect();
      slotGeom.push({ top: r.top, bottom: r.bottom, h: r.height });
    }
    var blocks = document.querySelectorAll('.blk');
    var blockInfo = [];
    for (var b = 0; b < blocks.length; b++) {
      var el = blocks[b];
      // 只量周一那一列：它是"跨节次卡片没填满"最直观的位置
      if (el.closest('.grid-col') !== document.querySelector('.grid-cols .grid-col')) continue;
      var br = el.getBoundingClientRect();
      var fromRow = Number(el.getAttribute('data-from'));
      var toRow = Number(el.getAttribute('data-to'));
      var item = { from: fromRow, to: toRow, h: Math.round(br.height), top: Math.round(br.top) };
      if (fromRow >= 0 && toRow < slotGeom.length) {
        var spanTop = slotGeom[fromRow].top;
        var spanBottom = slotGeom[toRow].bottom;
        item.spanH = Math.round(spanBottom - spanTop);
        // 覆盖率：卡片高度 / 应覆盖高度。远小于 1 就说明"没填满"。
        item.coverage = Math.round(100 * br.height / (spanBottom - spanTop));
      }
      blockInfo.push(item);
    }
    m.blocks = blockInfo;
    // 第一列（周一）里跨多节的块，正是用户看到问题的地方
    m.multiSpanBlocks = blockInfo.filter(function (x) { return x.to > x.from; });
    m.underfilled = blockInfo.filter(function (x) { return x.coverage != null && x.coverage < 90; });

    pre.textContent = 'PROBE_JSON=' + JSON.stringify(m);
    document.body.appendChild(pre);
  }, 800);
});
</script>
`;

/**
 * 让预览页表现得像安卓 App。
 *
 * 页面用 window.AndroidBridge 的存在来判断"我是不是跑在 App 里"，
 * 只有 App 才会去查 /api/saved、走免登录。浏览器里没有这个对象，
 * 于是预览会走网页版分支——那样就验证不到 App 的行为。这里补一个最小桩件，
 * 让预览走的是 App 那条路径。
 */
const BRIDGE_STUB = `
<script>
window.__previewLogin = { started: null, busy: null };
window.AndroidBridge = {
  startLogin: function (json) {
    window.__previewLogin.started = json;
    return '{"ok":true}';
  },
  onLoginSettled: function () {
    window.__previewLogin.settled = true;
  },
  setBusy: function (b) {
    window.__previewLogin.busy = b;
  }
};
</script>
`;

function injectProbe(html) {
  // 桩件要在 app.js 之前，所以插在 </head> 前面
  return html.replace('</head>', BRIDGE_STUB + '</head>').replace('</body>', PROBE + '</body>');
}

// ---------------------------------------------------------------- 假接口

// 假登录的进度：页面点登录后，先 running，稍后 ok——用来验证"发起 + 轮询"这条链路
let fakeLogin = { phase: 'idle', startedAt: 0, username: '' };

/**
 * 真实数据模式：设了 PREVIEW_KB 就用导出的真实课表，而不是伪造样本。
 *
 *   node run-day-probe.js --dump build/real-kb.json
 *   set PREVIEW_KB=build/real-kb.json
 *   node preview.js --probe --shot
 *
 * "某个色块没填满"这类问题必须用真实数据查——伪造样本的课程名长度、
 * 跨节次数都和实际情况不同，量出来的结论不能直接用。
 */
function loadRealKb() {
  const f = process.env.PREVIEW_KB;
  if (!f) return null;
  const p = path.isAbsolute(f) ? f : path.join(ROOT, f);
  if (!fs.existsSync(p)) {
    console.error('找不到 ' + p + '，请先执行: node run-day-probe.js --dump build/real-kb.json');
    process.exit(1);
  }
  const data = JSON.parse(fs.readFileSync(p, 'utf8'));
  console.log('  真实课表数据: ' + p);
  console.log('    周次=' + data.week + '  节次=' + (data.jieci || []).length
    + '  课程块=' + (data.flat || []).length);
  return data;
}
const REAL_KB = loadRealKb();

function apiResponse(pathname, search, forceLogin) {
  if (pathname === '/api/state') {
    // 登录完成后再查就变成已登录，页面据此进课表
    if (fakeLogin.phase === 'ok') {
      return { ok: true, loggedIn: true, name: '预览同学', username: fakeLogin.username };
    }
    if (forceLogin) return { ok: true, loggedIn: false };
    return { ok: true, loggedIn: true, name: '预览同学', username: '2000000000' };
  }
  if (pathname === '/api/login-start') {
    fakeLogin = { phase: 'running', startedAt: Date.now(), username: '2000000000' };
    return { ok: true, phase: 'running', running: true, name: '', error: '', elapsedMs: 0 };
  }
  if (pathname === '/api/login-result') {
    // 假装认证花了 900ms
    if (fakeLogin.phase === 'running' && Date.now() - fakeLogin.startedAt > 900) {
      fakeLogin.phase = 'ok';
    }
    return fakeLogin.phase === 'ok'
      ? { ok: true, phase: 'ok', running: false, name: '预览同学', error: '', elapsedMs: 900 }
      : { ok: true, phase: fakeLogin.phase, running: fakeLogin.phase === 'running', name: '', error: '', elapsedMs: 0 };
  }
  if (pathname === '/api/saved') {
    // 登录页预览时假装本机存过密码：这样能顺便验证"已保存"提示与学号回填
    return { ok: true, username: '2000000000', hasPassword: true, platform: 'android' };
  }
  if (pathname === '/api/terms') {
    if (REAL_KB) return { ok: true, terms: [{ dm: REAL_KB.xnxqdm, mc: REAL_KB.termName }] };
    return { ok: true, terms: [{ dm: '2025-2026-2', mc: '2025-2026学年第二学期' }] };
  }
  if (pathname === '/api/week') {
    if (REAL_KB) return { ok: true, zc: REAL_KB.curWeek, days: REAL_KB.days || [] };
    return { ok: true, zc: 4, days: DAY_DATES.map((rq, i) => ({ xq: i + 1, rq, jt: false })) };
  }
  if (pathname === '/api/kb') {
    if (REAL_KB) return REAL_KB;
    const week = Number(search.get('week') || 4);
    return Object.assign({ ok: true }, kbPayload(week));
  }
  return { ok: false, error: '预览模式不支持 ' + pathname };
}

// ---------------------------------------------------------------- 服务

const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript' };

function createServer() {
  return http.createServer((req, res) => {
    const u = new URL(req.url, 'http://localhost');
    const p = u.pathname;

    if (p.startsWith('/api/')) {
      // 强制未登录用 cookie 传递：页面里的 fetch('/api/state') 是相对地址，
      // 页面 URL 上的 ?login=1 不会跟着过去（实测确实不会），用 cookie 才可靠。
      const forceLogin = /(?:^|;\s*)preview_login=1(?:;|$)/.test(req.headers.cookie || '');
      const body = JSON.stringify(apiResponse(p, u.searchParams, forceLogin));
      if (process.env.PREVIEW_VERBOSE) console.log('      [api] ' + u.pathname + ' -> ' + body.slice(0, 120));
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(body);
    }

    // 静态文件：/ 与 /?login=1 都给 index.html
    const basePath = p.replace(/^\//, '');
    const file = basePath === '' ? 'index.html' : basePath;
    if (process.env.PREVIEW_VERBOSE) console.log('    [web] ' + req.url);
    // 页面里的 %BUILD_VERSION% 占位符在预览时直接说明清楚
    const full = path.join(WEB_SRC, file);
    if (!full.startsWith(WEB_SRC) || !fs.existsSync(full)) {
      res.writeHead(404).end('404');
      return;
    }
    let data = fs.readFileSync(full);
    if (file === 'index.html') {
      let html = String(data).replace('%BUILD_VERSION%', '本地预览（未打包）');
      if (process.argv.includes('--probe')) html = injectProbe(html);
      data = Buffer.from(html, 'utf8');
    }
    const headers = {
      'Content-Type': (MIME[path.extname(file)] || 'application/octet-stream') + '; charset=utf-8',
      'Cache-Control': 'no-store',
    };
    // ?login=1：让后续接口都按"未登录"回应，用来预览登录页。
    // 用 cookie 而不是仅仅看 URL，是因为页面里的 fetch 是相对地址，
    // 拿不到页面 URL 上的查询串（这点踩过坑）。
    if (u.searchParams.get('login') === '1') {
      headers['Set-Cookie'] = 'preview_login=1; Path=/';
    }
    res.writeHead(200, headers);
    res.end(data);
  });
}

// ---------------------------------------------------------------- 入口

const server = createServer();
server.listen(PORT, '127.0.0.1', async () => {
  const url = `http://127.0.0.1:${PORT}/`;
  console.log('课表预览已启动（数据为伪造，不连学校系统）');
  console.log('  地址: ' + url);
  console.log('  提示: 页面会直接进入课表（假接口返回已登录）');

  if (!process.argv.includes('--shot') && !process.argv.includes('--probe')) {
    console.log('\n按 Ctrl+C 结束。加 --shot 截图，加 --probe 量布局。');
    return;
  }

  const chrome = process.env.PREVIEW_BROWSER || [
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  ].find((p) => fs.existsSync(p));
  if (!chrome) {
    console.error('找不到 Chrome/Edge，无法截图');
    server.close();
    process.exit(1);
  }
  console.log('  浏览器: ' + chrome);

  const outDir = path.join(ROOT, 'build', 'preview');
  fs.mkdirSync(outDir, { recursive: true });
  // 独立的用户数据目录：headless 首次启动如果没有指定 profile 目录，
  // 在某些机器上会卡在初始化（实测会一直不退出），给它一个干净目录最稳。
  const profile = path.join(outDir, '_chrome-profile');
  fs.rmSync(profile, { recursive: true, force: true });
  fs.mkdirSync(profile, { recursive: true });

  const shots = [
    ['phone', 400, 900],
    ['narrow', 340, 800],
  ];

  // ---- 布局探针模式：真实视口 + 读回测量值 ----
  //
  // 注意不能靠 --window-size 控制视口：实测 headless 下它不起作用
  // （请求 320/360/400 三种宽度，window.innerWidth 全是 500），
  // 那样"测了三个宽度"其实是同一个宽度测了三遍，等于没测。
  // 这里改用 DevTools 协议的 Emulation.setDeviceMetricsOverride 真正改视口。
  if (process.argv.includes('--probe')) {
  const bad = await probeLayouts(chrome, profile, outDir, url, process.argv.includes('--shot'));
    console.log(bad === 0 ? '\n布局探针全部通过' : `\n布局探针发现 ${bad} 处问题`);
    server.close();
    process.exit(bad === 0 ? 0 : 1);
  }
  for (const [name, w, h] of shots) {
    const out = path.join(outDir, `preview-${name}.png`);
    const errLog = path.join(outDir, `_chrome-${name}.log`);
    fs.rmSync(out, { force: true });
    const errFd = fs.openSync(errLog, 'w');
    const args = [
      '--headless=new', '--disable-gpu', '--hide-scrollbars',
      '--no-first-run', '--no-default-browser-check', '--disable-extensions',
      // 关掉一切后台服务：Chrome 的更新服务会去连自己的命名管道，在受限环境里
      // 这类连接被拒后浏览器会一直不退出（实测卡到超时），截图根本产不出来
      '--disable-background-networking', '--disable-component-update',
      '--disable-client-side-phishing-detection', '--disable-sync',
      '--disable-default-apps', '--disable-features=OptimizationHints,MediaRouter',
      '--no-service-autorun', '--disable-breakpad', '--metrics-recording-only',
      '--force-device-scale-factor=1',
      `--user-data-dir=${profile}`,
      `--window-size=${w},${h}`,
      '--virtual-time-budget=5000',
      `--screenshot=${out}`,
      url,
    ];
    // 说明：截图写盘后浏览器不一定会自己退出——页面里的定时器与事件监听会让
    // 它一直活着。所以这里不把"进程退出"当作完成信号，只等文件出现；
    // 出现即认为成功，然后主动结束进程。管道捕获在本环境会 EPERM，所以用文件。
    const child = spawn(chrome, args, {
      stdio: ['ignore', 'ignore', errFd],
      windowsHide: true,
    });
    const deadline = Date.now() + 40000;
    let ok = false;
    while (Date.now() < deadline) {
      if (fs.existsSync(out) && fs.statSync(out).size > 0) {
        // 稍等一下确保文件写完
        await sleep(250);
        ok = fs.existsSync(out) && fs.statSync(out).size > 0;
        break;
      }
      await sleep(200);
    }
    try { child.kill(); } catch (e) {}
    await sleep(150);
    fs.closeSync(errFd);
    console.log(`  截图 ${name} ${w}x${h} -> ${out} ` + (ok ? '成功' : '失败'));
    if (!ok) {
      const tail = fs.existsSync(errLog)
        ? fs.readFileSync(errLog, 'utf8').split('\n').filter((l) => l.trim()).slice(-3).join('\n      ')
        : '';
      if (tail) console.log('      ' + tail);
    }
  }
  server.close();
});

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------- 布局探针

/** 读取 headless 启动时写下的真实调试端口（port=0 时端口是随机的） */
async function waitForDevToolsPort(profile, ms) {
  const f = path.join(profile, 'DevToolsActivePort');
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (fs.existsSync(f)) {
      const port = fs.readFileSync(f, 'utf8').split('\n')[0].trim();
      if (port) return port;
    }
    await sleep(200);
  }
  throw new Error('等不到 DevToolsActivePort');
}

/** 极简 CDP 客户端：只用到 evaluate，够用就不引依赖 */
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      if (msg.error) p.reject(new Error(msg.error.message));
      else p.resolve(msg.result);
    });
  }
  send(method, params) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(method + ' 超时'));
      }, 20000);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify({ id, method, params: params || {} }));
    });
  }
  async evalJs(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    return r && r.result ? r.result.value : undefined;
  }
}

const READ_METRICS = `(function(){
  var pre = document.getElementById('probe');
  if (!pre) return null;
  var t = pre.textContent || '';
  var i = t.indexOf('PROBE_JSON=');
  return i < 0 ? null : t.slice(i + 11);
})()`;

/**
 * 在若干真实视口宽度下量布局，返回问题数。
 *
 * 判定标准（都对应实际会在手机上暴露的问题）：
 *   - 文档比视口宽      -> 需要左右滚动才能看全一周
 *   - 课程卡超出视口    -> 内容被裁
 *   - 左栏格缺时间      -> 需求没满足（要有上课与下课时间）
 *   - 左栏格竖向被裁    -> 时间挤没了，等于没显示
 *   - 版本号超出视口    -> 固定底栏跑出屏幕
 */
async function probeLayouts(chrome, profile, outDir, url, wantShots) {
  const widths = [320, 360, 400, 412];
  const errFd = fs.openSync(path.join(outDir, '_probe.log'), 'w');
  const child = spawn(chrome, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--disable-background-networking', '--disable-component-update',
    '--no-service-autorun', '--disable-breakpad', '--disable-sync',
    '--disable-features=OptimizationHints,MediaRouter',
    '--force-device-scale-factor=1',
    `--user-data-dir=${profile}`,
    '--remote-debugging-port=0',
    'about:blank',
  ], { stdio: ['ignore', 'ignore', errFd], windowsHide: true });

  let bad = 0;
  try {
    const port = await waitForDevToolsPort(profile, 20000);
    const list = JSON.parse(await httpGet(`http://127.0.0.1:${port}/json/list`));
    const page = list.find((t) => t.type === 'page');
    if (!page) throw new Error('没有可用的 page target');

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve);
      ws.addEventListener('error', () => reject(new Error('CDP 连接失败')));
    });
    const cdp = new Cdp(ws);
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');

    for (const w of widths) {
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: w, height: 850, deviceScaleFactor: 1, mobile: true,
      });
      await cdp.send('Page.navigate', { url });
      await sleep(600);

      let m = null;
      const deadline = Date.now() + 15000;
      while (Date.now() < deadline) {
        const raw = await cdp.evalJs(READ_METRICS).catch(() => null);
        if (raw) { try { m = JSON.parse(raw); } catch (e) {} }
        if (m) break;
        await sleep(300);
      }

      console.log(`\n  --- 视口 ${w}px ---`);
      if (wantShots) {
        // 用 CDP 截图：这样抓到的才是刚才那个真实视口，而不是 --window-size 的产物
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' }).catch(() => null);
        if (shot && shot.data) {
          const p = path.join(outDir, `preview-${w}.png`);
          fs.writeFileSync(p, Buffer.from(shot.data, 'base64'));
          console.log(`    截图 -> ${p}`);
        }
      }
      if (!m) {
        console.log('    探针未取到数据（页面可能没渲染出来）');
        bad++;
        continue;
      }
      if (Math.abs(m.innerWidth - w) > 1) {
        console.log(`    !! 视口没有按预期生效：innerWidth=${m.innerWidth}，期望 ${w}`);
        bad++;
      }
      const overflow = m.docScrollW > m.innerWidth + 1;
      console.log(`    文档宽 ${m.docScrollW} / 视口 ${m.innerWidth}  => ${overflow ? '横向溢出 !!' : '无横向溢出'}`);
      console.log(`    课表宽 ${m.timetable && m.timetable.w}  左栏 ${m.gridTimes && m.gridTimes.w}  7列 ${m.gridCols && m.gridCols.w}`);
      console.log(`    左栏格数 ${m.timeCellCount}  缺时间 ${m.timeCellsWithoutTime}  竖向裁切 ${m.timeCellsOverflowY}`);
      console.log(`    首个左栏格 "${m.timeCellText}" 高 ${m.firstTimeCell && m.firstTimeCell.h}px 横向裁切=${m.timeCellOverflowX}`);
      if (m.firstBlock) console.log(`    首张课程卡 left=${m.firstBlock.l} right=${m.firstBlock.r} 高=${m.firstBlock.h}`);

      // 跨节次卡片的填充率：这是"第 5-6 节有课但只看到第 5 节色块"的判据。
      // coverage 是"卡片实际高度 / 它应覆盖的节次总高"，远小于 100% 就是没填满。
      if (m.multiSpanBlocks && m.multiSpanBlocks.length) {
        console.log(`    周一跨节次卡片 ${m.multiSpanBlocks.length} 张:`);
        for (const blk of m.multiSpanBlocks) {
          console.log(`      节次 ${blk.from + 1}-${blk.to + 1}:  卡高 ${blk.h}px / 应覆盖 ${blk.spanH}px  填充率 ${blk.coverage}%`);
        }
      }
      if (m.underfilled && m.underfilled.length) {
        for (const blk of m.underfilled) {
          console.log(`    !! 节次 ${blk.from + 1}-${blk.to + 1} 的卡片没填满（${blk.coverage}%），
        第 ${blk.to + 1} 节会看起来是空的`.replace(/\n\s+/g, ' '));
          bad++;
        }
      }

      // 表头星期 与 课程列 的横向对齐
      if (m.colAlign && m.colAlign.length) {
        console.log(`    表头/列对齐（左栏分界 表头=${m.headCornerRight} 正文=${m.leftColRight} 差=${m.cornerOffset}px）`);
        for (const o of m.colAlign) {
          console.log(`      周${['一','二','三','四','五','六','日'][o.day - 1]}: 表头[${o.headL},${o.headR}]  列[${o.colL},${o.colR}]  左差=${o.dl} 右差=${o.dr}`);
        }
        if (m.maxColOffset > 2) {
          console.log(`    !! 表头与课程列横向错位最大 ${m.maxColOffset}px（应≤2px）`);
          bad++;
        }
      }
      if (m.cornerOffset != null && Math.abs(m.cornerOffset) > 2) {
        console.log(`    !! 表头左角与左栏分界不一致（${m.cornerOffset}px）`);
        bad++;
      }

      // 底部信息条：应在课表下方、随页面滚动，而不是固定吸在屏幕底部
      console.log(`    信息条 高${m.footerH}px  定位=${m.footerPosition}  在课表下方=${m.footerBelowGrid}  仓库地址="${m.repoText}"`);
      if (m.footerIsFixed) {
        console.log('    !! 信息条仍是固定定位，会一直显示并压住课表');
        bad++;
      }
      if (m.footerBelowGrid !== true) {
        console.log('    !! 信息条没有排在课表下方');
        bad++;
      }
      if (m.repoHref !== 'https://github.com/cc-pro-20/gzhu-grad-schedule') {
        console.log(`    !! 仓库地址链接不对: ${m.repoHref}`);
        bad++;
      }
      if (m.repoClickable !== true) { console.log('    !! 仓库地址不可点击'); bad++; }

      if (overflow) { console.log('    !! 文档比视口宽，手机上要左右滚动'); bad++; }
      if (m.firstBlock && m.firstBlock.r > m.innerWidth + 1) { console.log('    !! 课程卡超出视口右侧'); bad++; }
      if (m.buildId && m.buildId.r > m.innerWidth + 1) { console.log('    !! 版本号超出视口'); bad++; }
      if (m.timeCellsWithoutTime) { console.log('    !! 有左栏格缺少上课/下课时间'); bad++; }
      if (m.timeCellOverflowY) { console.log('    !! 左栏格内容被竖向裁掉'); bad++; }
      if (m.timeCellOverflowX) { console.log('    !! 左栏格时间被横向裁掉'); bad++; }
    }
    // 额外抓一张登录页（?login=1）：用于确认"只有一个登录窗口"、样式生效、
    // 以及在只支持网页登录时"已保存密码"提示与学号回填是否正常
    {
      const w = 360;
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: w, height: 850, deviceScaleFactor: 1, mobile: true,
      });
      const navRes = await cdp.send('Page.navigate', { url: url + '?login=1' }).catch((e) => ({ error: String(e) }));
      if (navRes && navRes.errorText) console.log('    导航报错: ' + navRes.errorText);
      await sleep(1500);
      const shot = await cdp.send('Page.captureScreenshot', { format: 'png' }).catch(() => null);
      if (shot && shot.data) {
        const p = path.join(outDir, 'preview-login.png');
        fs.writeFileSync(p, Buffer.from(shot.data, 'base64'));
        console.log(`\n  --- 登录页 ${w}px ---`);
        console.log(`    截图 -> ${p}`);
        const href = await cdp.evalJs('location.href + "  title=" + document.title').catch(() => '?');
        console.log(`    当前地址: ${href}`);
        const dbg = await cdp.evalJs(`fetch('/api/state?probe=' + Date.now(), {cache:'no-store'}).then(function(r){return r.text();})`).catch(() => null);
        console.log('    直接取 /api/state -> ' + dbg);
        const s = await cdp.evalJs(`(function(){
          var l = document.getElementById('login');
          var a = document.getElementById('app');
          function vis(e){ return !!e && e.getBoundingClientRect().height > 0; }
          return JSON.stringify({
            loginVisible: vis(l), appVisible: vis(a),
            username: (document.getElementById('username')||{}).value || '',
            hintVisible: vis(document.getElementById('saved-hint')),
            hintText: (document.getElementById('saved-hint')||{}).textContent || '',
            cardWidth: l ? Math.round(l.querySelector('.login-card').getBoundingClientRect().width) : 0
          });
        })()`).catch(() => null);
        if (s) {
          const o = JSON.parse(s);
          console.log(`    登录页可见=${o.loginVisible}  课表页可见=${o.appVisible}  登录卡宽=${o.cardWidth}`);
          console.log(`    学号回填="${o.username}"  已保存提示=${o.hintVisible} "${o.hintText.trim()}"`);
          if (!o.loginVisible) { console.log('    !! 登录页没显示出来'); bad++; }
          if (o.appVisible) { console.log('    !! 登录页与课表页同时可见（会出现"两版窗口"的观感）'); bad++; }
          if (!o.username) { console.log('    !! 学号没有回填'); bad++; }
        }

        // 走一遍真实登录：填表 -> 提交 -> 桥发起 -> 轮询 -> 进课表。
        // 这段覆盖的是"页面登录逻辑"本身，不依赖真机也不依赖学校系统。
        fakeLogin = { phase: 'idle', startedAt: 0, username: '' };
        const flow = await cdp.evalJs(`(async function(){
          var u = document.getElementById('username');
          var p = document.getElementById('password');
          u.value = '2000000000';
          p.value = 'preview-pass';
          document.getElementById('login-btn').click();
          var t0 = Date.now();
          while (Date.now() - t0 < 15000) {
            await new Promise(function(r){ setTimeout(r, 200); });
            var app = document.getElementById('app');
            if (app && !app.classList.contains('hidden')) break;
          }
          var st = window.__previewLogin || {};
          return JSON.stringify({
            bridgeCalled: !!st.started,
            sentUser: st.started ? (JSON.parse(st.started).username || '') : '',
            sentRemember: st.started ? !!JSON.parse(st.started).remember : null,
            settled: !!st.settled,
            appShown: !document.getElementById('app').classList.contains('hidden'),
            loginHidden: document.getElementById('login').classList.contains('hidden'),
            msg: (document.getElementById('login-msg') || {}).textContent || '',
            rows: document.querySelectorAll('.grid-times .g-time').length,
            blocks: document.querySelectorAll('.blk').length
          });
        })()`).catch(() => null);
        if (!flow) {
          console.log('    !! 登录流程探针没拿到结果');
          bad++;
        } else {
          const f = JSON.parse(flow);
          console.log(`\n  --- 登录流程（页面表单 → 桥 → 轮询 → 课表）---`);
          console.log(`    桥被调用=${f.bridgeCalled}  传入学号="${f.sentUser}"  记住密码=${f.sentRemember}`);
          console.log(`    页面已切到课表=${f.appShown}  登录页已隐藏=${f.loginHidden}  提示="${f.msg}"`);
          console.log(`    渲染出左栏 ${f.rows} 格 / 课程卡 ${f.blocks} 个`);
          if (!f.bridgeCalled) { console.log('    !! 登录没有走原生桥'); bad++; }
          if (f.sentUser !== '2000000000') { console.log('    !! 桥收到的学号不对'); bad++; }
          if (!f.appShown) { console.log('    !! 登录成功后没进入课表'); bad++; }
          if (!f.loginHidden) { console.log('    !! 登录页没有隐藏（会出现两个界面同时可见）'); bad++; }
          if (f.rows < 1 || f.blocks < 1) { console.log('    !! 课表没渲染出来'); bad++; }
        }
      }
    }

    // 额外抓一张"划到底"的截图：确认信息条只在底部出现，且不压住课表最后一行
    if (wantShots) {
      await cdp.evalJs('window.scrollTo(0, document.body.scrollHeight)');
      await sleep(500);
      const bottomShot = await cdp.send('Page.captureScreenshot', { format: 'png' }).catch(() => null);
      if (bottomShot && bottomShot.data) {
        const p = path.join(outDir, 'preview-bottom.png');
        fs.writeFileSync(p, Buffer.from(bottomShot.data, 'base64'));
        console.log(`\n  --- 划到底部（360px 视口）---`);
        console.log(`    截图 -> ${p}`);
        const b = await cdp.evalJs(`(function(){
          var f = document.querySelector('.app-footer');
          var g = document.querySelector('.grid-cols');
          if (!f || !g) return null;
          var fr = f.getBoundingClientRect(), gr = g.getBoundingClientRect();
          return JSON.stringify({
            footerTop: Math.round(fr.top), footerBottom: Math.round(fr.bottom),
            gridBottom: Math.round(gr.bottom),
            viewportH: window.innerHeight,
            footerInView: fr.bottom <= window.innerHeight + 1,
            overlapsLastRow: fr.top < gr.bottom - 1
          });
        })()`).catch(() => null);
        if (b) {
          const o = JSON.parse(b);
          console.log(`    信息条 top=${o.footerTop} bottom=${o.footerBottom}  课表底部=${o.gridBottom}  视口高=${o.viewportH}`);
          console.log(`    划到底后信息条在视口内=${o.footerInView}  压住课表=${o.overlapsLastRow}`);
          if (!o.footerInView) { console.log('    !! 划到底了却看不到信息条'); bad++; }
          if (o.overlapsLastRow) { console.log('    !! 信息条压住了课表内容'); bad++; }
        }
      }
    }

    try { ws.close(); } catch (e) {}
  } finally {
    try { child.kill(); } catch (e) {}
    fs.closeSync(errFd);
  }
  return bad;
}

function httpGet(u) {
  return new Promise((resolve, reject) => {
    http.get(u, (res) => {
      let s = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { s += c; });
      res.on('end', () => resolve(s));
    }).on('error', reject);
  });
}
