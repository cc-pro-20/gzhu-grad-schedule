/**
 * gzhu课表 · 广州大学研究生课表（轻量移动端）
 * ------------------------------------------------------------
 * 单文件 Node 服务（零第三方依赖），提供：
 *   POST /api/login   账号密码登录（服务端完成 CAS 认证）
 *   POST /api/logout  退出
 *   GET  /api/state   当前登录状态
 *   GET  /api/terms   可选学年学期列表
 *   GET  /api/week    当前周次与每周日期
 *   GET  /api/kb      指定学期 + 周次的课表（已整理为网格）
 *
 * 说明：仅作个人自用/自建部署；账号密码只保存在服务进程内存中，不落盘。
 */
'use strict';

const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const { URL, URLSearchParams } = require('url');

const PORT = Number(process.env.PORT || 8899);
const HOST_BIND = process.env.HOST || '0.0.0.0';
const CAS_HOST = 'newcas.gzhu.edu.cn';
const APP_HOST = 'yjsyxt.gzhu.edu.cn';
const APP = '/gsapp/sys/yddwdkbapp';
const RAW_TARGET = `${APP}/*default/index.do`;
const UA =
  'Mozilla/5.0 (Linux; Android 13; SM-S9010) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36';

// ---------------------------------------------------------------- DES
// 复用学校 CAS 页面自带的 des.js（正方 strEnc），首次使用时从远端拉取并缓存到内存
let strEnc = null;
function ensureStrEnc() {
  if (strEnc) return strEnc;
  const src = fs.readFileSync(path.join(__dirname, 'des.js'), 'utf8');
  const sb = { window: {}, navigator: { userAgent: 'node' } };
  vm.createContext(sb);
  vm.runInContext(src, sb);
  if (typeof sb.strEnc !== 'function') throw new Error('des.js 未提供 strEnc');
  strEnc = sb.strEnc;
  return strEnc;
}

// ---------------------------------------------------------------- HTTP
function request(host, method, rawPath, opts = {}) {
  return new Promise((resolve, reject) => {
    const headers = {
      Host: host,
      'User-Agent': UA,
      Accept: opts.accept || 'application/json, text/javascript, */*; q=0.01',
      'Accept-Language': 'zh-CN,zh;q=0.9',
      ...(opts.headers || {}),
    };
    if (opts.cookie) headers.Cookie = opts.cookie;
    if (opts.body) headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
    const req = https.request(
      {
        hostname: host,
        port: 443,
        path: rawPath,
        method,
        headers,
        rejectUnauthorized: false,
      },
      (res) => {
        const chunks = [];
        res.on('data', (d) => chunks.push(d));
        res.on('end', () => {
          clearTimeout(timer);
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(chunks).toString('utf8'),
          });
        });
      }
    );
    // 校园系统高峰期偶发卡顿，超时给足
    const timer = setTimeout(() => req.destroy(new Error('上游请求超时')), opts.timeout || 45000);
    req.on('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
    if (opts.body) req.write(opts.body);
    req.end();
  });
}

/** 带一次重试的上游请求（校园网偶发抖动） */
async function requestRetry(host, method, rawPath, opts = {}) {
  try {
    return await request(host, method, rawPath, opts);
  } catch (e) {
    await new Promise((r) => setTimeout(r, 1200));
    return request(host, method, rawPath, opts);
  }
}

/** 极简 cookie jar：只保存 name=value，按域名区分 */
class Jar {
  constructor() {
    this.map = new Map();
  }
  add(res) {
    const sc = res.headers['set-cookie'] || [];
    for (const c of sc) {
      const [pair] = c.split(';');
      const i = pair.indexOf('=');
      if (i > 0) this.map.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
    }
  }
  header() {
    return [...this.map.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }
}

/**
 * 上游客户端：维护一个独立 cookie 会话。
 * 重要：课表业务路径里的 `*` 必须原样发送，一旦被 URL 编码成 %2A 会被服务端拒绝(403)。
 */
class Upstream {
  constructor() {
    this.jar = new Jar();
  }
  get(pathname, opts = {}) {
    return request(APP_HOST, 'GET', pathname, { ...opts, cookie: this.jar.header() }).then((r) => {
      this.jar.add(r);
      return r;
    });
  }
  post(pathname, params, opts = {}) {
    const body = new URLSearchParams(params).toString();
    return request(APP_HOST, 'POST', pathname, {
      ...opts,
      body,
      cookie: this.jar.header(),
      headers: {
        Referer: `https://${APP_HOST}${RAW_TARGET}`,
        Origin: `https://${APP_HOST}`,
        'X-Requested-With': 'XMLHttpRequest',
        ...(opts.headers || {}),
      },
    }).then((r) => {
      this.jar.add(r);
      return r;
    });
  }
  /** CAS 登录，成功后 jar 内即有 GS_SESSIONID 等业务会话 */
  async casLogin(un, pd) {
    const svc = encodeURIComponent(`https://${APP_HOST}${RAW_TARGET}`);
    const loginPath = `/cas/login?service=${svc}`;
    const p1 = await requestRetry(CAS_HOST, 'GET', loginPath, { accept: 'text/html', cookie: this.jar.header() });
    this.jar.add(p1);
    const lt = (p1.body.match(/name="lt"\s+value="([^"]+)"/) || [])[1];
    const execution = (p1.body.match(/name="execution"\s+value="([^"]+)"/) || [])[1] || 'e1s1';
    if (!lt) throw new Error('无法获取 CAS 登录票据(lt)，认证服务可能已改版');

    const rsa = ensureStrEnc()(un + pd + lt, '1', '2', '3');
    const body = new URLSearchParams({
      un,
      pd,
      lt,
      execution,
      _eventId: 'submit',
      ul: String(un.length),
      pl: String(pd.length),
      rsa,
    }).toString();
    const p2 = await request(CAS_HOST, 'POST', loginPath, {
      accept: 'text/html',
      body,
      cookie: this.jar.header(),
    });
    this.jar.add(p2);
    let loc = p2.headers.location;
    if (!loc) {
      const err = (p2.body.match(/id="errmsg"[^>]*>([^<]+)</) || [])[1];
      throw new Error(err ? err.trim() : '账号或密码错误');
    }
    // 跟随 ticket 换取业务会话
    let last = { body: '' };
    for (let i = 0; i < 6 && loc; i++) {
      const u = new URL(loc);
      const r = await requestRetry(u.hostname, 'GET', u.pathname + u.search, {
        accept: 'text/html',
        cookie: this.jar.header(),
      });
      this.jar.add(r);
      last = r;
      loc = r.status >= 300 && r.status < 400 ? r.headers.location : null;
    }
    if (!this.jar.map.has('GS_SESSIONID')) throw new Error('登录未建立业务会话');
    // 首页 HTML 里带有 USERNAME / USERID，直接取用，省一次请求
    this.profile = {
      name: (last.body.match(/USERNAME='([^']*)'/) || [])[1] || '',
      userId: (last.body.match(/USERID='([^']*)'/) || [])[1] || '',
    };
    return true;
  }
  /** 业务接口封装：{code:0,datas:...} */
  async api(name, params = {}) {
    const r = await this.post(`${APP}/modules/wdkb/${name}.do`, params);
    if (r.status === 401) {
      const e = new Error('登录状态已失效');
      e.code = 'UNAUTHORIZED';
      throw e;
    }
    let j;
    try {
      j = JSON.parse(r.body);
    } catch (e) {
      const err = new Error(`接口 ${name} 返回异常(${r.status})`);
      err.code = 'UPSTREAM';
      throw err;
    }
    // 业务约定：code === 0 为成功
    if (j.code !== undefined && j.code !== null && Number(j.code) !== 0) {
      const err = new Error(j.msg || `接口 ${name} 返回 code=${j.code}`);
      err.code = 'UPSTREAM';
      throw err;
    }
    return j.datas;
  }
  async terms() {
    const d = await this.api('kfdxnxqcx');
    const rows = (d && d.kfdxnxqcx && d.kfdxnxqcx.rows) || [];
    return rows.map((x) => ({ dm: x.XNXQDM, mc: x.XNXQDM_DISPLAY }));
  }
  async weeks() {
    const r = await this.post(`${APP}/modules/wdkb/getXnxqdyzc.do`, {});
    let j;
    try {
      j = JSON.parse(r.body);
    } catch (e) {
      return { zc: null, days: [] };
    }
    const rows = j.rqData || [];
    const first = rows[0] || {};
    return {
      zc: first.ZC ? Number(first.ZC) : null,
      xnxqdm: first.XNXQDM || null,
      days: rows.map((x) => ({ xq: Number(x.XQ), rq: x.RQ, jt: x.SFJT === '1' })),
    };
  }
  async jieci(xnxqdm, xh) {
    const d = await this.api('xsskjccx', { XNXQDM: xnxqdm, XH: xh });
    const rows = (d && d.xsskjccx && d.xsskjccx.rows) || [];
    return rows
      .map((x) => ({
        dm: String(x.DM),
        jcfadm: String(x.JCFADM),
        mc: x.MC,
        jcfamc: x.JCFAMC,
        kssj: fmtTime(x.KSSJ),
        jssj: fmtTime(x.JSSJ),
      }))
      .sort((a, b) => Number(a.dm) - Number(b.dm));
  }
  async courses(xnxqdm) {
    const d = await this.api('xspkjgcx', { XNXQDM: xnxqdm, '*order': '+KCDM,-ZCBH,+XQ,+KSJCDM' });
    const rows = (d && d.xspkjgcx && d.xspkjgcx.rows) || [];
    return rows.map((x) => ({
      kcmc: x.KCMC || '未命名课程',
      kcdm: x.KCDM || '',
      jasmc: x.JASMC || '',
      jsxm: x.JSXM || '',
      xq: Number(x.XQ),
      ksjcdm: String(x.KSJCDM),
      jsjcdm: String(x.JSJCDM),
      jcfadm: String(x.JCFADM),
      zcbh: x.ZCBH || '',
      zcmc: x.ZCMC || '',
      kssj: fmtTime(x.KSSJ),
      jssj: fmtTime(x.JSSJ),
      bjmc: x.BJMC || '',
      kclb: x.KCLBDM_DISPLAY || '',
    }));
  }
}

function fmtTime(v) {
  const s = String(v == null ? '' : v).padStart(4, '0');
  return `${s.slice(0, 2)}:${s.slice(2, 4)}`;
}

// ---------------------------------------------------------------- 会话
const sessions = new Map(); // sid -> { up, un, name, ts }
const SID_COOKIE = 'kb_sid';

function createSession(up, un, name) {
  const sid = crypto.randomBytes(18).toString('hex');
  sessions.set(sid, { up, un, name, ts: Date.now() });
  return sid;
}
function getSession(req) {
  const raw = req.headers.cookie || '';
  const m = raw.match(new RegExp(`(?:^|;\\s*)${SID_COOKIE}=([^;]+)`));
  if (!m) return null;
  const s = sessions.get(m[1]);
  if (s) s.ts = Date.now();
  return s ? { sid: m[1], ...s } : null;
}
function dropSession(sid) {
  sessions.delete(sid);
}
// 12 小时无活动自动清理，避免内存里长期留存凭据
setInterval(() => {
  const now = Date.now();
  for (const [sid, s] of sessions) if (now - s.ts > 12 * 3600 * 1000) sessions.delete(sid);
}, 10 * 60 * 1000).unref();

// ---------------------------------------------------------------- 业务组装
/** 课表网格：grid[星期][节次序号] = 课程对象 */
function buildGrid(courses, jieci, week) {
  const cellIndex = new Map(jieci.map((j, i) => [j.dm, i]));
  const n = jieci.length;

  // 1) 过滤出本周有效的记录，并按「星期 + 起始节次」排序
  const kept = [];
  for (const c of courses) {
    if (!weekOn(c.zcbh, week)) continue;
    const si = cellIndex.get(c.ksjcdm);
    if (si == null || c.xq < 1 || c.xq > 7) continue;
    const eiRaw = cellIndex.get(c.jsjcdm);
    const ei = eiRaw == null || eiRaw < si ? si : eiRaw;
    kept.push({ c, si, ei });
  }
  kept.sort((a, b) => a.c.xq - b.c.xq || a.si - b.si || a.ei - b.ei);

  // 2) 合并「同一天 + 同课程 + 节次首尾相接」的记录，得到完整课程块
  //    上游是按节次逐条返回的，必须合并，否则一个连堂课会拆成多个小块
  const flat = [];
  for (const item of kept) {
    const prev = flat[flat.length - 1];
    const sameCourse =
      prev &&
      prev.xq === item.c.xq &&
      prev.kcdm === item.c.kcdm &&
      prev.jasmc === item.c.jasmc &&
      prev.to === item.si - 1;
    if (sameCourse) {
      prev.to = item.ei;
      prev.span = prev.to - prev.from + 1;
      prev.jssj = item.c.jssj;
      continue;
    }
    flat.push({
      kcmc: item.c.kcmc,
      kcdm: item.c.kcdm,
      jasmc: item.c.jasmc,
      jsxm: item.c.jsxm,
      zcmc: item.c.zcmc,
      kclb: item.c.kclb,
      bjmc: item.c.bjmc,
      kssj: item.c.kssj,
      jssj: item.c.jssj,
      xq: item.c.xq,
      from: item.si,
      to: item.ei,
      span: item.ei - item.si + 1,
      color: colorOf(item.c.kcdm || item.c.kcmc),
    });
  }

  // 3) 填充网格（供接口自检/扩展使用）
  const grid = Array.from({ length: 8 }, () => Array.from({ length: n }, () => []));
  for (const it of flat) {
    for (let k = it.from; k <= it.to && k < n; k++) grid[it.xq][k].push(it);
  }

  // 4) 行高统一，跨节次课程块由前端按覆盖行累加高度（避免"每行都很高"）
  const H_ROW = 76;
  const rows = [];
  let y = 0;
  for (let i = 0; i < n; i++) {
    rows.push({ index: i, top: y, height: H_ROW });
    y += H_ROW + 1; // +1 为行间分隔线
  }
  const totalHeight = rows.length ? rows[rows.length - 1].top + rows[rows.length - 1].height : 0;

  return { grid, flat, jieci, rows, totalHeight, unit: { row: H_ROW } };
}

/** ZCBH 是 30 位字符串，第 i 位为 1 表示第 i 周上课 */
function weekOn(zcbh, week) {
  if (!zcbh) return true;
  if (!week) return true;
  const ch = String(zcbh)[week - 1];
  return ch === undefined ? true : ch === '1';
}

/** 按课程代码稳定分配颜色 */
const PALETTE = [
  ['#e8f0fe', '#1a56db'],
  ['#fde8e8', '#c81e1e'],
  ['#e6f4ea', '#046c4e'],
  ['#fef3c7', '#92400e'],
  ['#ede9fe', '#5b21b6'],
  ['#e0f2fe', '#075985'],
  ['#fce7f3', '#9d174d'],
  ['#ecfccb', '#3f6212'],
  ['#ffe4e6', '#9f1239'],
  ['#dbeafe', '#1e40af'],
];
function colorOf(key) {
  let h = 0;
  const s = String(key);
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

// ---------------------------------------------------------------- HTTP 服务
function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let b = '';
    req.on('data', (d) => {
      b += d;
      if (b.length > 1e6) reject(new Error('请求体过大'));
    });
    req.on('end', () => {
      const ct = req.headers['content-type'] || '';
      if (ct.includes('application/json')) {
        try {
          return resolve(JSON.parse(b || '{}'));
        } catch (e) {
          return resolve({});
        }
      }
      const out = {};
      for (const [k, v] of new URLSearchParams(b)) out[k] = v;
      resolve(out);
    });
    req.on('error', reject);
  });
}
function fail(res, e) {
  const code = e.code === 'UNAUTHORIZED' ? 401 : 400;
  sendJson(res, code, { ok: false, error: e.message || '请求失败' });
}
const ok = (res, data) => sendJson(res, 200, { ok: true, ...data });

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const p = u.pathname;
  const t0 = Date.now();
  // 简易访问日志（不含任何凭据）
  res.on('finish', () => {
    if (p.startsWith('/api/') && p !== '/api/state') {
      console.log(`${new Date().toLocaleTimeString('zh-CN')} ${req.method} ${p} -> ${res.statusCode} (${Date.now() - t0}ms)`);
    }
  });
  try {
    if (p === '/' || p === '/index.html') {
      const html = PAGE;
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
        'Content-Length': Buffer.byteLength(html),
      });
      return res.end(html);
    }

    // 静态资源（仅白名单这几个文件，不做目录遍历）
    if (p === '/style.css' || p === '/app.js' || p === '/boot.js' || p === '/favicon.ico') {
      if (p === '/favicon.ico') {
        res.writeHead(204).end();
        return;
      }
      const file = path.join(__dirname, 'public', p.slice(1));
      const body = fs.readFileSync(file);
      res.writeHead(200, {
        'Content-Type': p.endsWith('.css') ? 'text/css; charset=utf-8' : 'application/javascript; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Content-Length': body.length,
      });
      return res.end(body);
    }

    if (p === '/api/login' && req.method === 'POST') {
      // remember 字段（是否记住密码）只在安卓版有意义：那边会写进 Keystore 加密存储。
      // 服务端本来就不落盘保存密码，这里读到即可，不做处理。
      const { username, password } = await readBody(req);
      if (!username || !password) return sendJson(res, 400, { ok: false, error: '请输入账号和密码' });
      const up = new Upstream();
      await up.casLogin(String(username).trim(), String(password));
      const name = (up.profile && up.profile.name) || '';
      const sid = createSession(up, String(username).trim(), name);
      res.setHeader(
        'Set-Cookie',
        `${SID_COOKIE}=${sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${12 * 3600}`
      );
      return ok(res, { name, username: String(username).trim() });
    }

    // 安卓版用它判断"本机是否已加密保存过密码"，从而决定要不要免登录。
    // 网页版永远没有保存过密码，固定回 false。
    if (p === '/api/saved') {
      return ok(res, { username: '', hasPassword: false, platform: 'web' });
    }

    if (p === '/api/logout' && req.method === 'POST') {
      const s = getSession(req);
      if (s) dropSession(s.sid);
      res.setHeader('Set-Cookie', `${SID_COOKIE}=; Path=/; HttpOnly; Max-Age=0`);
      return ok(res, {});
    }

    if (p === '/api/state') {
      const s = getSession(req);
      return ok(res, s ? { loggedIn: true, name: s.name, username: s.un } : { loggedIn: false });
    }

    // 以下接口都需要登录
    const s = getSession(req);
    if (p.startsWith('/api/')) {
      if (!s) return sendJson(res, 401, { ok: false, error: '未登录' });

      if (p === '/api/terms') return ok(res, { terms: await s.up.terms() });

      if (p === '/api/week') {
        const w = await s.up.weeks();
        return ok(res, w);
      }

      if (p === '/api/kb') {
        const terms = await s.up.terms();
        let xnxqdm = u.searchParams.get('term');
        if (!xnxqdm || !terms.some((t) => t.dm === xnxqdm)) xnxqdm = terms[0] ? terms[0].dm : null;
        const weeks = await s.up.weeks();
        const jieci = await s.up.jieci(xnxqdm, s.un);
        const courses = await s.up.courses(xnxqdm);

        // 可选：直接返回原始记录（?raw=1）
        if (u.searchParams.get('raw') === '1') {
          return ok(res, { xnxqdm, jieci, courses });
        }

        const weekParam = Number(u.searchParams.get('week') || 0);
        const curWeek = weeks.zc || 1;
        const week = weekParam >= 1 && weekParam <= 30 ? weekParam : curWeek;
        const { grid, flat, rows, totalHeight } = buildGrid(courses, jieci, week);
        const days = weeks.days.length
          ? weeks.days
          : [1, 2, 3, 4, 5, 6, 7].map((xq) => ({ xq, rq: '', jt: false }));
        // 只有在查看“当前周”时，日期才与真实日期对应
        const curDays = week === curWeek ? weeks.days : [];
        return ok(res, {
          xnxqdm,
          termName: (terms.find((t) => t.dm === xnxqdm) || {}).mc || xnxqdm,
          week,
          curWeek,
          totalWeeks: 30,
          days,
          curDays,
          jieci,
          rows,
          totalHeight,
          grid,
          flat,
        });
      }
      return sendJson(res, 404, { ok: false, error: '未知接口' });
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('404');
  } catch (e) {
    if (e.code === 'UNAUTHORIZED') {
      const s2 = getSession(req);
      if (s2) dropSession(s2.sid);
    }
    fail(res, e);
  }
});

// ---------------------------------------------------------------- 前端页面
const PAGE = fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8');

server.listen(PORT, HOST_BIND, () => {
  console.log(`gzhu课表服务已启动: http://127.0.0.1:${PORT}`);
  console.log(`局域网访问: http://<本机IP>:${PORT}`);
});
