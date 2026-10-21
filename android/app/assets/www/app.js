/* =============== gzhu课表 · 广州大学研究生课表（轻量移动端） =============== */
'use strict';

const $ = (s) => document.querySelector(s);
const state = { terms: [], term: null, week: null, curWeek: null, total: 30, data: null };

/* ---------------- 通用请求 ---------------- */
async function api(pathname, opts = {}) {
  const res = await fetch(pathname, {
    credentials: 'same-origin',
    headers: opts.body ? { 'Content-Type': 'application/json' } : undefined,
    method: opts.body ? 'POST' : 'GET',
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  let j = null;
  try { j = await res.json(); } catch (e) {}
  if (!res.ok || !j || j.ok === false) {
    const err = new Error((j && j.error) || `请求失败(${res.status})`);
    err.status = res.status;
    throw err;
  }
  return j;
}

let loadingCount = 0;
function loading(on) {
  loadingCount += on ? 1 : -1;
  if (loadingCount < 0) loadingCount = 0;
  $('#loading').classList.toggle('hidden', loadingCount === 0);
}

/**
 * 登录请求。
 *
 *  - 网页版：POST /api/login，服务端代做 CAS 认证，一次请求拿到结果
 *  - 安卓 App：走 AndroidBridge。原因很硬：WebView 的 shouldInterceptRequest
 *    读不到 POST 请求体（WebResourceRequest 没有 getInputStream），页面提交的
 *    账号密码传不到原生，只能经 JavaScript 桥交过去。
 *    桥方法不能在里面等网络（它跑在 WebView 的 JavaBridge 线程上，阻塞会把页面
 *    JS 一起卡住），所以约定"发起 + 轮询"：startLogin 立即返回，结果查
 *    /api/login-result。
 *
 * 两条路径对调用方返回同样的 {name, username}。
 */
const LOGIN_POLL_MS = 400;
const LOGIN_TIMEOUT_MS = 60000;

function doLogin(username, password, remember) {
  if (!isAndroid()) return api('/api/login', { body: { username, password, remember: !!remember } });
  return androidLogin(username, password, remember);
}

/**
 * 轮询一次登录尝试的结果。
 *
 * 安卓端的登录都是"发起 + 轮询"：桥方法不能等网络（它跑在 WebView 的
 * JavaBridge 线程上），所以真正的结果记在原生侧的 LoginState 里，页面来查。
 */
function pollLoginResult() {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const tick = async () => {
      let st = null;
      try {
        st = await api('/api/login-result');
      } catch (e) {
        // 单次查询失败不当作登录失败，继续轮询到超时
      }
      if (st) {
        if (st.phase === 'ok') {
          if (window.AndroidBridge && window.AndroidBridge.onLoginSettled) {
            window.AndroidBridge.onLoginSettled();
          }
          return resolve({ name: st.name || '', username: st.username || '' });
        }
        if (st.phase === 'failed') return reject(new Error(st.error || '登录失败'));
      }
      if (Date.now() - started > LOGIN_TIMEOUT_MS) {
        return reject(new Error('登录超时，请重试'));
      }
      setTimeout(tick, LOGIN_POLL_MS);
    };
    tick();
  });
}

/**
 * 安卓版的登录：桥负责"启动"，服务端负责"记账并完成"，页面负责"轮询"。
 *
 * 为什么是这个分工：
 *   - 凭据只能经桥传（WebView 的 shouldInterceptRequest 读不到 POST 请求体）
 *   - 但桥方法跑在 WebView 的 JavaBridge 线程上，在里面等网络会把页面 JS 卡死，
 *     所以桥只做"立即发起"，不返回结果
 *   - 谁来完成认证、结果谁记录，仍归原生侧（KbRepository.startLogin），
 *     页面再轮询 /api/login-result 取结果
 *
 * 注意这里那次 POST 是**故意不 await、也不看响应**的：它只是把这次尝试
 * 正式交给服务端（写进 LoginState），失败也不影响后续轮询——
 * 轮询本身才是判断依据。失败会被静默忽略，不能让一次记账失败把登录卡死。
 */
function androidLogin(username, password, remember) {
  // 1) 把凭据交给原生（异步执行，不阻塞页面）
  let raw;
  try {
    raw = window.AndroidBridge.startLogin(JSON.stringify({
      username, password, remember: !!remember,
    }));
  } catch (e) {
    return Promise.reject(new Error('原生登录调用失败'));
  }
  let res = null;
  try { res = JSON.parse(raw); } catch (e) {}
  if (!res || !res.ok) return Promise.reject(new Error((res && res.error) || '登录失败'));

  // 2) 让原生侧为这次尝试建一条可查询的记录
  try {
    fetch('/api/login-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, remember: !!remember }),
    }).catch(() => {});
  } catch (e) {
    /* 记不上也要继续轮询：真正决定成败的是 LoginState 里的结果 */
  }

  // 3) 轮询结果
  return pollLoginResult();
}

/**
 * 用本机已保存的密码登录，不需要用户重新输入。
 *
 * 这条路径专门解决"经常需要手动登录"：启动时自动登录可能因为学校服务器抖动
 * 失败、或者会话中途过期，但凭据其实还在本机，让用户再敲一遍密码毫无必要。
 */
async function androidLoginWithSaved() {
  await api('/api/login-with-saved', { body: {} });
  return pollLoginResult();
}
let toastTimer = null;
function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 2200);
}

/* ---------------- 登录 ---------------- */
const KEY_UN = 'gzhu_kb_username';

/** 安卓版：既是"本机能加密保存密码"的前提，也是登录要走桥的判据。 */
function isAndroid() {
  return !!(window.AndroidBridge && typeof window.AndroidBridge.startLogin === 'function');
}

function showApp() {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  document.documentElement.classList.remove('js');
}
function showLogin() {
  $('#app').classList.add('hidden');
  $('#login').classList.remove('hidden');
  $('#password').value = '';
  document.documentElement.classList.remove('js');
}

/**
 * 初始化时探测：本机是否已保存过密码，以及"为什么没能免登录"。
 *
 * 安卓版返回 hasPassword=true 时原生会自动完成登录，页面直接进课表；
 * 走到登录页说明自动登录没成功，于是把学号回填、给出原因、并提供一键重试。
 */
async function loadSaved() {
  if (!isAndroid()) return;
  let s = null;
  try { s = await api('/api/saved'); } catch (e) { return; }
  if (!s) return;

  if (s.username && !$('#username').value) $('#username').value = s.username;

  const hint = $('#saved-hint');
  const btn = $('#saved-login-btn');
  const parts = [];
  if (s.hasPassword) {
    parts.push('本机已加密保存密码，打开 App 会自动登录。');
    btn.classList.remove('hidden');
  } else {
    btn.classList.add('hidden');
  }
  // 把失败原因摊开讲。以前这些信息全被吞掉，用户只看到"又要输密码"。
  if (s.credError) parts.push('密码未能保存：' + s.credError);
  if (s.lastAuthError) parts.push(s.lastAuthError);
  if (parts.length) {
    hint.textContent = parts.join(' ');
    hint.classList.remove('hidden');
  } else {
    hint.classList.add('hidden');
  }
}

/** 登录成功后进课表 */
async function enterApp(r, fallbackName) {
  const un = (r && r.username) || fallbackName || '';
  $('#user-name').textContent = (r && r.name) || un || '同学';
  if (un) localStorage.setItem(KEY_UN, un);
  $('#password').value = '';
  showApp();
  await boot();
}

/**
 * 告诉用户"密码到底有没有存住"。
 *
 * 这是本次修复的重点之一：以前勾了"记住密码"但保存失败时毫无提示，
 * 用户只知道下次又要重输，完全没法判断问题出在哪。
 */
async function reportRemembered(remember) {
  if (!isAndroid()) return;
  let s = null;
  try { s = await api('/api/saved'); } catch (e) { return; }
  if (!s) return;
  if (!remember) {
    toast('本次未保存密码');
    return;
  }
  if (s.hasPassword) toast('已记住密码，下次打开免登录');
  else toast('未能记住密码' + (s.credError ? '：' + s.credError : ''));
}

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const username = $('#username').value.trim();
  const password = $('#password').value;
  if (!username || !password) return;
  const remember = $('#remember').checked;
  const btn = $('#login-btn');
  const msg = $('#login-msg');
  msg.textContent = '';
  btn.disabled = true;
  btn.textContent = '登录中…';
  loading(true);
  // 安卓版让原生也转起来：认证由原生侧发起，这样卡在哪一步一眼能看出来
  if (isAndroid() && window.AndroidBridge.setBusy) window.AndroidBridge.setBusy(true);
  try {
    const r = await doLogin(username, password, remember);
    await enterApp(r, username);
    await reportRemembered(remember);
  } catch (err) {
    msg.textContent = err.message;
  } finally {
    btn.disabled = false;
    btn.textContent = '登 录';
    loading(false);
    if (isAndroid() && window.AndroidBridge.setBusy) window.AndroidBridge.setBusy(false);
  }
});

/* 用已保存的密码一键重登（凭据还在，不必让用户重新输入） */
$('#saved-login-btn').addEventListener('click', async () => {
  const btn = $('#saved-login-btn');
  const msg = $('#login-msg');
  msg.textContent = '';
  btn.disabled = true;
  btn.textContent = '登录中…';
  loading(true);
  if (window.AndroidBridge && window.AndroidBridge.setBusy) window.AndroidBridge.setBusy(true);
  try {
    const r = await androidLoginWithSaved();
    await enterApp(r, $('#username').value.trim());
  } catch (err) {
    msg.textContent = err.message;
    // 凭据可能真的失效了，刷新提示（按钮会随之隐藏）
    await loadSaved();
  } finally {
    btn.disabled = false;
    btn.textContent = '用已保存的密码登录';
    loading(false);
    if (window.AndroidBridge && window.AndroidBridge.setBusy) window.AndroidBridge.setBusy(false);
  }
});

$('#logout').addEventListener('click', async () => {
  try { await api('/api/logout', { body: {} }); } catch (e) {}
  state.data = null;
  // 退出时把记住学号的痕迹一并清掉，否则登录页还留着上一个人的学号
  localStorage.removeItem(KEY_UN);
  $('#username').value = '';
  $('#saved-hint').classList.add('hidden');
  showLogin();
});

/* ---------------- 初始化 ---------------- */
async function boot() {
  loading(true);
  try {
    const t = await api('/api/terms');
    state.terms = t.terms || [];
    renderTerms();
    if (state.terms.length) state.term = state.terms[0].dm;
    await loadWeek();
    await loadKb(state.week);
  } catch (err) {
    if (err.status === 401) { showLogin(); await loadSaved(); }
    else toast(err.message);
  } finally {
    loading(false);
  }
}

function renderTerms() {
  const sel = $('#term-select');
  sel.innerHTML = '';
  for (const t of state.terms) {
    const o = document.createElement('option');
    o.value = t.dm;
    o.textContent = t.mc;
    sel.appendChild(o);
  }
  if (state.term) sel.value = state.term;
}

$('#term-select').addEventListener('change', async (e) => {
  state.term = e.target.value;
  await loadKb(state.week);
});

async function loadWeek() {
  const w = await api('/api/week');
  state.curWeek = w.zc || 1;
  if (!state.week) state.week = state.curWeek;
  state.days = w.days || [];
}

async function loadKb(week) {
  loading(true);
  try {
    const q = new URLSearchParams({ term: state.term || '', week: String(week || '') });
    const d = await api('/api/kb?' + q.toString());
    state.data = d;
    state.week = d.week;
    state.curWeek = d.curWeek;
    state.total = d.totalWeeks || 30;
    $('#term-label').textContent = d.termName || '';
    renderWeekLabel();
    renderDates();
    renderGrid();
  } catch (err) {
    // 会话彻底不可用时会走到这里。原生侧已经尝试过"用保存的凭据自动重登"，
    // 所以这里除了回登录页，还要把原因和一键重登的入口给出来。
    if (err.status === 401) { showLogin(); await loadSaved(); return; }
    toast(err.message);
  } finally {
    loading(false);
  }
}

/* ---------------- 周次切换 ---------------- */
function renderWeekLabel() {
  const el = $('#week-cur');
  el.textContent = `第 ${state.week} 周`;
  el.classList.toggle('cur', state.week === state.curWeek);
}
$('#week-prev').addEventListener('click', () => stepWeek(-1));
$('#week-next').addEventListener('click', () => stepWeek(1));
$('#week-cur').addEventListener('click', () => {
  if (state.week !== state.curWeek) loadKb(state.curWeek);
});
function stepWeek(d) {
  const next = (state.week || 1) + d;
  if (next < 1 || next > state.total) return toast(`第 1 - ${state.total} 周`);
  loadKb(next);
}

/* ---------------- 渲染 ---------------- */
const WEEK_CN = ['', '一', '二', '三', '四', '五', '六', '日'];

/**
 * 把日期字符串（形如 "10.19"、"10-19" 或 "2026-10-19"）平移若干天，
 * 保持原来的分隔符与格式。
 *
 * 学校接口只给**本周**的日期，看别的周就没数据。以前的做法是"不是本周就不显示
 * 日期"，结果点上一周/下一周后日期整排消失——这正是用户报的问题。
 * 现在按周差平移即可：同一星期几相差 7 的整数倍。
 *
 * ⚠️ 真实格式是点号（`10.19`），不是短横线。第一版只认 `MM-DD`，
 * 于是平移直接不生效、每周都显示本周日期——所以分隔符必须按输入原样保留。
 *
 * 只有月日时用固定年份（2000）参与计算，跨年由日期自然滚动处理，
 * 反正只显示月日，年份不参与显示。
 */
function shiftRq(rq, days) {
  if (!rq) return '';
  if (!days) return rq; // 不平移就原样返回，避免改动格式
  const full = /^(\d{4})([./-])(\d{1,2})\2(\d{1,2})$/.exec(rq);
  const md = /^(\d{1,2})([./-])(\d{1,2})$/.exec(rq);
  let d;
  let sep;
  let hasYear = false;
  if (full) {
    hasYear = true;
    sep = full[2];
    d = new Date(+full[1], +full[3] - 1, +full[4]);
  } else if (md) {
    sep = md[2];
    d = new Date(2000, +md[1] - 1, +md[3]);
  } else {
    return rq; // 认不出的格式就别乱改，原样返回
  }
  d.setDate(d.getDate() + days);
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return hasYear ? `${d.getFullYear()}${sep}${mm}${sep}${dd}` : `${mm}${sep}${dd}`;
}

function renderDates() {
  const box = $('#week-dates');
  box.innerHTML = '';

  // 基准日期一律用**本周**的（/api/kb 的 curDays），再按周差平移到当前查看的周。
  const base = (state.data && state.data.curDays && state.data.curDays.length)
    ? state.data.curDays
    : (state.days || []);
  if (!base.length) {
    box.classList.add('hidden');
    return;
  }
  const curWeek = state.curWeek || 1;
  const week = state.week || curWeek;
  const offsetDays = (week - curWeek) * 7;
  const isCur = week === curWeek;

  box.classList.remove('hidden');
  const spacer = document.createElement('div');
  spacer.className = 'd-label';
  box.appendChild(spacer);
  for (let xq = 1; xq <= 7; xq++) {
    const src = base.find((d) => d.xq === xq);
    const el = document.createElement('div');
    el.textContent = src ? shiftRq(src.rq, offsetDays) : '';
    // 调休/补课只对本周有意义，别的周平移出来的日期不代表真实上课日
    if (isCur && src && src.jt) el.title = '调休/补课';
    box.appendChild(el);
  }
}

function renderGrid() {
  const d = state.data;
  const box = $('#timetable');
  box.innerHTML = '';
  if (!d || !d.jieci || !d.jieci.length) {
    box.innerHTML = '<div class="empty-hint">该学期暂无课表数据</div>';
    return;
  }
  const jieci = d.jieci;
  const rows = d.rows || jieci.map((_, i) => ({ index: i, top: i * 55, height: 54, tall: false }));
  const totalHeight = d.totalHeight || rows.reduce((a, r) => a + r.height + 1, 0);
  const isCur = d.week === d.curWeek;
  const todayXq = isCur ? new Date().getDay() : -1; // 0=周日
  const today = todayXq === 0 ? 7 : todayXq;

  // ---- 表头（星期） ----
  const head = document.createElement('div');
  head.className = 'grid-head';
  const corner = document.createElement('div');
  corner.className = 'g-time';
  corner.innerHTML = `<b>第${d.week}周</b>`;
  head.appendChild(corner);
  for (let xq = 1; xq <= 7; xq++) {
    const c = document.createElement('div');
    c.className = 'g-cell' + (xq === today ? ' today' : '');
    c.innerHTML = `<b>${WEEK_CN[xq]}</b>`;
    head.appendChild(c);
  }
  box.appendChild(head);

  // ---- 表体 ----
  const body = document.createElement('div');
  body.className = 'grid-body';

  // 左列时间：节次 + 上课/下课时间
  const times = document.createElement('div');
  times.className = 'grid-times';
  rows.forEach((r, i) => {
    const j = jieci[i] || {};
    const t = document.createElement('div');
    t.className = 'g-time';
    t.style.height = r.height + 'px';
    const ks = esc(j.kssj || '');
    const js = esc(j.jssj || '');
    const range = ks && js ? `<span class="t-range">${ks}<br>${js}</span>`
      : (ks || js ? `<span class="t-range">${ks || js}</span>` : '');
    t.innerHTML = `<b>${esc(j.dm || i + 1)}</b>${range}`;
    // 完整信息放 title，格子窄的时候还能长按/悬停看到
    if (ks || js) t.title = `第 ${j.dm || i + 1} 节 ${ks}-${js}`;
    times.appendChild(t);
  });
  body.appendChild(times);

  // 7 列背景 + 课程格
  const cols = document.createElement('div');
  cols.className = 'grid-cols';
  for (let xq = 1; xq <= 7; xq++) {
    const col = document.createElement('div');
    col.className = 'grid-col' + (xq === today ? ' today' : '');
    rows.forEach((r, i) => {
      const cell = document.createElement('div');
      cell.className = 'slot';
      cell.style.height = r.height + 'px';
      const here = (d.flat || []).filter((b) => b.xq === xq && b.from === i);
      if (here.length) {
        cell.classList.add('has-course');
        for (const it of here) {
          cell.appendChild(makeBlock(it, r, rows));
        }
      }
      col.appendChild(cell);
    });
    cols.appendChild(col);
  }
  body.appendChild(cols);
  cols.style.height = totalHeight + 'px';
  box.appendChild(body);

  if (!(d.flat || []).length) {
    const hint = document.createElement('div');
    hint.className = 'empty-hint';
    hint.textContent = `第 ${d.week} 周没有课程安排`;
    box.appendChild(hint);
  }
}

/** 生成课程卡片；跨多节次时高度 = 覆盖各行的像素高度之和 */
function makeBlock(it, row, rows) {
  const el = document.createElement('button');
  el.type = 'button';
  el.className = 'blk';
  el.style.background = it.color[0];
  el.style.color = it.color[1];
  let h = 0;
  for (let i = it.from; i <= it.to && i < rows.length; i++) h += rows[i].height + (i > it.from ? 1 : 0);
  // 每行槽位上下 padding 各 1px，减去避免压到下一行
  el.style.height = h - 2 + 'px';
  // 标出这张卡覆盖的节次（0-based 行号，与 /api/kb 的 flat[].from/to 一致）。
  // 一是给 CSS 用（跨节的卡片可以据此做视觉区分），
  // 二是排障时能直接在审查元素里看到"这张卡本该覆盖哪几节"。
  el.dataset.from = String(it.from);
  el.dataset.to = String(it.to);
  const showRoom = it.span >= 2 || h >= 90;
  el.innerHTML =
    `<span class="b-name">${esc(it.kcmc)}</span>` +
    (showRoom && it.jasmc ? `<span class="b-room">${esc(it.jasmc)}</span>` : '');
  el.addEventListener('click', () => showDetail(it));
  return el;
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (m) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]));
}

/* ---------------- 课程详情 ---------------- */
function showDetail(it) {
  $('#d-title').textContent = it.kcmc;
  const rows = [
    ['上课时间', `周${WEEK_CN[it.xq]} 第 ${it.from + 1}-${it.to + 1} 节${it.kssj ? `（${it.kssj}-${it.jssj}）` : ''}`],
    ['上课周次', it.zcmc || '—'],
    ['上课地点', it.jasmc || '—'],
    ['任课教师', it.jsxm || '—'],
  ];
  if (it.kclb) rows.push(['课程类别', it.kclb]);
  if (it.bjmc) rows.push(['教学班', it.bjmc]);
  $('#d-list').innerHTML = rows.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
  $('#detail').classList.remove('hidden');
}
$('#d-close').addEventListener('click', () => $('#detail').classList.add('hidden'));
$('#detail').querySelector('.sheet-mask').addEventListener('click', () => $('#detail').classList.add('hidden'));

/* ---------------- 启动：探测已有会话 ---------------- */
(async function start() {
  const saved = localStorage.getItem(KEY_UN);
  if (saved) $('#username').value = saved;
  // 安卓版先把"本机是否存过密码"查出来：存过的话原生通常已经自动登录好，
  // 下面 /api/state 会直接给出已登录状态
  try { await loadSaved(); } catch (e) {}
  try {
    const st = await api('/api/state');
    if (st.loggedIn) {
      $('#user-name').textContent = st.name || st.username || '同学';
      showApp();
      await boot();
      return;
    }
  } catch (e) {}
  showLogin();
})();
