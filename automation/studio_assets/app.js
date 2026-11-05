// HanJari Studio — "HanJari App.dc.html" (Claude Design) rendered from real local state.
// No demo data: every value comes from /api/state. Actions go through /api/action
// (the existing workspace.py allow-list). Text is always inserted as text, never HTML.
'use strict';

/* ------------------------------------------------------------------ constants */
const C = {
  bg: '#101514', side: '#0B100E', panel: '#19211E', raised: '#222C28', line: '#2A3631', line2: '#34423C',
  text: '#E3EEE9', text2: '#A1B6AC', text3: '#80948A', mint: '#73E2BB', mintInk: '#062018',
  mintSoft: 'rgba(115,226,187,0.12)', amber: '#EBC07A', amberSoft: 'rgba(235,192,122,0.13)',
  coral: '#F2968A', coralSoft: 'rgba(242,150,138,0.13)', gray: 'rgba(161,182,172,0.10)',
};
const circ = (cx, cy, r) => `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
const IC = {
  check: 'M20 6 9 17l-5-5', clock: circ(12, 12, 9) + 'M12 7v5l3 2', alert: circ(12, 12, 9) + 'M12 8v4.5M12 16h.01',
  pause: circ(12, 12, 9) + 'M10 9v6M14 9v6', stop: 'M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z',
  play: 'M7 4.5v15l12.5-7.5z', xc: circ(12, 12, 9) + 'M15 9l-6 6M9 9l6 6', x: 'M18 6 6 18M6 6l12 12',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z' + circ(12, 12, 3),
  shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zM12 8v4M12 15.5h.01',
  usercheck: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2' + circ(9, 7, 4) + 'M16 11l2 2 4-4',
  layout: 'M4 4h6v8H4zM14 4h6v5h-6zM14 13h6v7h-6zM4 16h6v4H4z',
  doc: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M8 13h8M8 17h5',
  list: 'M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01',
  pr: circ(6, 6, 2.5) + circ(18, 18, 2.5) + 'M6 8.5V21M13 6h3a2 2 0 0 1 2 2v7.5',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3.5 2', search: circ(11, 11, 7) + 'M20 20l-4-4',
  refresh: 'M21 12a9 9 0 0 1-15.5 6.3L3 16M3 21v-5h5M3 12a9 9 0 0 1 15.5-6.3L21 8M21 3v5h-5',
  bulb: 'M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z', down: 'M6 9l6 6 6-6', right: 'M9 6l6 6-6 6',
  ext: 'M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6',
  file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6',
  plug: 'M12 22v-5M9 8V2M15 8V2M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8z',
  folder: 'M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.7-.9l-.8-1.2A2 2 0 0 0 7.9 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2z',
  plus: 'M12 5v14M5 12h14', lock: 'M7 11V7a5 5 0 0 1 10 0v4M5 11h14v10H5z', loader: 'M21 12a9 9 0 1 1-6.2-8.6',
  dashed: 'M12 3a9 9 0 0 1 3 .5M19.4 7a9 9 0 0 1 1.1 3M20.6 14a9 9 0 0 1-1.5 3M16.5 20a9 9 0 0 1-3 1M9.5 20.7a9 9 0 0 1-3-1.5M4.3 16.5a9 9 0 0 1-1.2-3M3.4 9.5a9 9 0 0 1 1.5-3M7.5 4a9 9 0 0 1 3-.8',
  send: 'M22 2 11 13M22 2l-7 20-4-9-9-4z', edit: 'M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z',
  info: circ(12, 12, 9) + 'M12 11v5M12 8h.01', terminal: 'M4 17l6-6-6-6M12 19h8',
};
const ST = {
  running: ['진행 중', 'loader', C.mint, C.mintSoft, true], processing: ['처리 중', 'loader', C.mint, C.mintSoft, true],
  in_dev: ['개발 중', 'loader', C.mint, C.mintSoft, true], waiting: ['대기', 'clock', C.text2, C.gray],
  planned: ['개발 전', 'dashed', C.text3, 'rgba(161,182,172,0.07)'], notstarted: ['시작 전', 'dashed', C.text2, C.gray],
  stop_requested: ['중단 요청됨', 'pause', C.amber, C.amberSoft], stopped: ['중단됨', 'stop', C.text2, C.gray],
  needs_decision: ['판단 필요', 'alert', C.amber, C.amberSoft], review: ['리뷰 대기', 'eye', C.amber, C.amberSoft],
  blocked: ['반복 방지 작동', 'shield', C.coral, C.coralSoft], failed: ['실패', 'xc', C.coral, C.coralSoft],
  done: ['완료', 'check', C.mint, C.mintSoft], passed: ['통과', 'check', C.mint, C.mintSoft],
  accepted: ['수락 완료', 'usercheck', C.mint, C.mintSoft], unknown: ['확인 불가', 'info', C.text2, C.gray],
};
const STAGES = [
  ['아이디어 입력', '나'], ['기획 작성', 'Codex'], ['독립 기획 검토', 'Codex'], ['전체 기획 승인', '나'],
  ['기능별 개발', 'Claude Code'], ['개발 테스트·코드 리뷰', 'Claude Code · Codex'], ['최종 검증', 'Codex'],
  ['Draft PR 준비', '한자리'], ['코드 리뷰·수락', '나'],
];
const STAGE_NAME = {
  not_started: '시작 전', plan: '기획 작성', spec_review: '독립 기획 검토', awaiting_plan_approval: '전체 기획 승인',
  implement: '기능별 개발', review: '코드 리뷰', final_test: '최종 검증', pr_ready: '코드 리뷰·수락',
  manager: '문제 분석·재계획', needs_user: '사용자 판단 필요',
};
const NAMES = {
  run: '자동화 실행', pause: '중단 요청', note: '아이디어 기록', revise: '수정 요청', approve: '기획 승인',
  sync: 'Notion 동기화', 'accept-feature': '기능 리뷰 완료', 'retry-sync': '전송 재시도 허용', 'reset-breaker': '반복 방지 해제',
};
const NAV = [['overview', '진행 현황', 'layout'], ['plan', '기획과 결정', 'doc'], ['features', '기능별 업무', 'list'],
  ['review', '리뷰와 검증', 'pr'], ['activity', '활동·요청·복구', 'history'], ['settings', '설정', 'plug']];
const TITLES = Object.fromEntries(NAV.map(([k, l]) => [k, l]));
const LIMITS = { five_hour: 80, seven_day: 70 };

/* ------------------------------------------------------------------ DOM helpers */
const SVGNS = 'http://www.w3.org/2000/svg';
function ic(name, color, size, spin) {
  const s = document.createElementNS(SVGNS, 'svg');
  s.setAttribute('width', size || 16); s.setAttribute('height', size || 16); s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('fill', 'none'); s.setAttribute('stroke', color || 'currentColor'); s.setAttribute('stroke-width', '1.9');
  s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round'); s.setAttribute('aria-hidden', 'true');
  if (spin) s.setAttribute('class', 'spin');
  const p = document.createElementNS(SVGNS, 'path'); p.setAttribute('d', IC[name] || ''); s.appendChild(p);
  s._h = {}; p._h = {};
  return s;
}
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  el._h = {};
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'style') Object.assign(el.style, v);
    else if (k === 'class') el.className = v;
    else if (k.startsWith('on')) { el._h[k] = v; el[k] = v; }
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  add(el, kids);
  return el;
}
function add(el, kids) {
  for (const k of kids) {
    if (k == null || k === false || k === true) continue;
    if (Array.isArray(k)) add(el, k);
    else el.appendChild(typeof k === 'object' ? k : document.createTextNode(String(k)));
  }
}
// Patch the live DOM in place so focus, IME composition, selection and scroll survive polling.
function morph(from, to) {
  if (from.nodeType !== to.nodeType || from.nodeName !== to.nodeName ||
      (from.nodeType === 1 && from.getAttribute('data-k') !== to.getAttribute('data-k'))) {
    from.replaceWith(to); return;
  }
  if (from.nodeType === 3) { if (from.nodeValue !== to.nodeValue) from.nodeValue = to.nodeValue; return; }
  for (const a of Array.from(from.attributes)) if (a.name !== 'style' && !to.hasAttribute(a.name)) from.removeAttribute(a.name);
  for (const a of Array.from(to.attributes)) if (a.name !== 'style' && from.getAttribute(a.name) !== a.value) from.setAttribute(a.name, a.value);
  if (from.style && from.style.cssText !== to.style.cssText) from.style.cssText = to.style.cssText;
  const handlers = new Set([...Object.keys(from._h || {}), ...Object.keys(to._h || {})]);
  for (const k of handlers) from[k] = (to._h || {})[k] || null;
  from._h = to._h || {};
  if ('value' in to && (from.tagName === 'INPUT' || from.tagName === 'TEXTAREA' || from.tagName === 'SELECT')) {
    if (from.value !== to.value && document.activeElement !== from) from.value = to.value;
  }
  if (from.tagName === 'INPUT') from.checked = to.checked;
  const a = Array.from(from.childNodes), b = Array.from(to.childNodes);
  for (let i = 0; i < b.length; i++) { if (i < a.length) morph(a[i], b[i]); else from.appendChild(b[i]); }
  for (let i = b.length; i < a.length; i++) a[i].remove();
}

/* ------------------------------------------------------------------ formatting */
const pad = (n) => String(n).padStart(2, '0');
function toDate(iso) { const d = iso ? new Date(iso) : null; return d && !isNaN(d) ? d : null; }
function hm(iso) { const d = toDate(iso); return d ? pad(d.getHours()) + ':' + pad(d.getMinutes()) : '—'; }
function dayLabel(iso) {
  const d = toDate(iso); if (!d) return '날짜 없음';
  const label = `${d.getMonth() + 1}월 ${d.getDate()}일`;
  return d.toDateString() === new Date().toDateString() ? '오늘 · ' + label : label;
}
function when(iso) {
  const d = toDate(iso); if (!d) return '—';
  return (d.toDateString() === new Date().toDateString() ? '오늘' : `${d.getMonth() + 1}월 ${d.getDate()}일`) + ' ' + hm(iso);
}
function ago(sec) {
  if (sec < 60) return '방금';
  if (sec < 3600) return Math.floor(sec / 60) + '분 전';
  const hr = Math.floor(sec / 3600), mi = Math.floor((sec % 3600) / 60);
  return hr + '시간' + (mi ? ' ' + mi + '분' : '') + ' 전';
}
const clip = (t, n) => { t = String(t || '').replace(/\s+/g, ' ').trim(); return t.length > n ? t.slice(0, n - 1) + '…' : t; };
const first = (t) => String(t || '').split('\n').find((x) => x.trim()) || '';
function textOf(x) { return typeof x === 'string' ? x : x && typeof x === 'object' ? (x.text || x.summary || x.message || JSON.stringify(x)) : String(x ?? ''); }

/* ------------------------------------------------------------------ app state */
const S = {
  token: null, projects: [], project: localStorage.getItem('hj.project') || 'hanjari', data: null,
  conn: 'connecting', lastOk: null, reconnecting: false, sending: false,
  screen: 'overview', projOpen: false, modal: null, modalText: '', toast: null,
  planQ: '', planHit: -1, tocActive: null, approveChecked: false, pinned: null, showHash: false, changeText: '', decOpen: null,
  featQ: '', featFilter: 'all', featSel: null,
  openFile: null, verdict: null, memo: '', showLog: false,
  actFilter: 'all', actOpen: null, fixed: {},
  adding: false, form: { title: '', project: '', repo: '', record_page: '', source_page: '', prefix: 'TASK', baseline: '', idea: '' },
  formError: '', jobSeen: {}, scroll: {}, w: window.innerWidth,
};
const readFiles = JSON.parse(localStorage.getItem('hj.read') || '{}');

let renderQueued = false;
function render() { if (!renderQueued) { renderQueued = true; requestAnimationFrame(() => { renderQueued = false; paint(); }); } }
function set(patch) { Object.assign(S, patch); render(); }
function toast(msg, kind) {
  S.toast = { msg, kind: kind || 'ok' }; render();
  clearTimeout(toast.t); toast.t = setTimeout(() => { S.toast = null; render(); }, 3800);
}
function go(screen) {
  document.querySelectorAll('[data-scroll]').forEach((el) => { S.scroll[el.dataset.scroll] = el.scrollTop; });
  set({ screen, projOpen: false });
  if (screen === 'settings') loadSettings();
  requestAnimationFrame(() => requestAnimationFrame(() => document.querySelectorAll('[data-scroll]').forEach((el) => {
    if (S.scroll[el.dataset.scroll] != null) el.scrollTop = S.scroll[el.dataset.scroll];
  })));
}

/* ------------------------------------------------------------------ server */
async function api(path, body) {
  const opts = body ? { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Dashboard-Token': S.token }, body: JSON.stringify(body) } : {};
  const res = await fetch(path, opts);
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(json.error || '요청을 처리하지 못했어요.'), { status: res.status });
  return json;
}
async function connect() {
  const s = await api('/api/session');
  S.token = s.token; S.projects = s.projects;
  if (!S.projects.some((p) => p.id === S.project)) S.project = S.projects[0] ? S.projects[0].id : 'hanjari';
}
async function poll() {
  try {
    if (!S.token) await connect();
    const project = S.project;
    const data = await api('/api/state?project=' + encodeURIComponent(project));
    if (project !== S.project) return;
    trackJobs(data);
    const changed = JSON.stringify(data) !== JSON.stringify(S.data);
    S.data = data; S.lastOk = new Date();
    if (S.conn !== 'ok') { S.conn = 'ok'; render(); }
    if (changed) { pin(); render(); }
    if (S.screen === 'settings' && (!S.settings || S.settings.project !== S.project) && !S.settingsLoading && !S.settingsError) loadSettings();
  } catch (e) {
    if (S.conn !== 'failed') { S.conn = 'failed'; render(); }
    if (e.status === 403) S.token = null;
  }
}
function trackJobs(data) {
  for (const j of data.jobs || []) {
    const before = S.jobSeen[j.id];
    if (before === 'running' && j.status !== 'running') {
      if (j.status === 'completed') toast(`${NAMES[j.action] || j.action} 요청을 처리했어요.`);
      else toast(`${NAMES[j.action] || j.action} 요청이 실패했어요. 활동·요청·복구에서 로그를 확인하세요.`, 'warn');
    }
    S.jobSeen[j.id] = j.status;
  }
}
async function send(action, extra) {
  if (S.sending) return false;
  if (S.conn !== 'ok') { toast('제어기 연결이 끊겨 보낼 수 없어요.', 'warn'); return false; }
  S.sending = true; render();
  try {
    const job = await api('/api/action', Object.assign({ action, project: S.project }, extra || {}));
    S.jobSeen[job.id] = 'running';
    toast(`${NAMES[action]} 요청을 보냈어요.` + (action === 'run' ? ' 실행 버튼을 누른 이번 한 번만 AI가 작업해요.' : ''));
    setTimeout(poll, 400);
    return true;
  } catch (e) { toast(e.message, 'warn'); return false; } finally { S.sending = false; render(); }
}

/* ------------------------------------------------------------------ derived state */
const busy = (d) => !!(d && (d.active || d.running || d.busy));
const agentBusy = (d) => !!(d && (d.active || d.running));
const latched = (d) => !!(d && d.run_guard && d.run_guard.latched);
function usageState(u) {
  if (!u) return 'missing';
  if (!u.fresh) return 'stale';
  if (u.five_hour >= LIMITS.five_hour || u.seven_day >= LIMITS.seven_day) return 'limit';
  return 'ok';
}
function allowed(d) {
  const a = new Set();
  if (!d || S.conn !== 'ok') return a;
  const started = d.stage !== 'not_started';
  if (busy(d)) { if (started) { a.add('pause'); a.add('note'); } return a; }
  a.add('run');
  if (started) ['pause', 'note', 'sync', 'revise', 'retry-sync', 'reset-breaker'].forEach((x) => a.add(x));
  if (d.stage === 'needs_user' || (d.stage === 'awaiting_plan_approval' && !d.approved)) a.delete('run');
  if (d.parent_stage === 'awaiting_plan_approval' && d.candidate_hash && !d.approved) a.add('approve');
  if (d.stage === 'pr_ready' && d.current_feature) a.add('accept-feature');
  if ((d.questions || []).length) a.delete('approve');
  if (latched(d)) a.delete('run'); else a.delete('reset-breaker');
  if (!(d.receipts || []).some((r) => r.status === 'needs_user')) a.delete('retry-sync');
  if (d.stage === 'pr_ready') a.delete('run');
  // Claude usage gates Claude development only; Codex planning/review is not blocked by it.
  if (d.approved && ['implement', 'awaiting_plan_approval', 'plan'].includes(d.stage) && usageState(d.usage) !== 'ok') a.delete('run');
  return a;
}
function runBlock(d) {
  if (S.conn !== 'ok') return '제어기 연결이 끊겨 실행할 수 없어요. 연결을 복구한 뒤 다시 시도하세요.';
  if (!d) return '상태를 불러오는 중이에요.';
  if (busy(d)) return '다른 작업이 진행 중이에요. 끝난 뒤 실행할 수 있어요.';
  if (latched(d)) return '반복 방지가 작동해 실행을 막았어요. 활동·요청·복구에서 원인을 확인하세요.';
  if (d.stage === 'awaiting_plan_approval' && !d.approved) return '기획 승인 후 실행할 수 있어요.';
  if (d.stage === 'needs_user') return '사용자 판단이 필요해요. 근거를 확인하고 수정 요청으로 답해 주세요.';
  if (d.stage === 'pr_ready') return '현재 기능의 리뷰 판단을 먼저 기록하세요.';
  if (!allowed(d).has('run')) {
    const u = d.usage, st = usageState(u);
    if (st === 'missing') return 'Claude 사용량 값을 읽지 못해 개발 시작을 막았어요. Claude Code에서 사용량을 갱신하세요. 0%로 간주하지 않아요.';
    if (st === 'stale') return `Claude 사용량이 ${ago(u.age_seconds)} 값이라 개발 시작을 막았어요. Claude Code에서 갱신한 뒤 실행하세요.`;
    if (st === 'limit') return `Claude 사용률(5시간 ${u.five_hour}% · 주간 ${u.seven_day}%)이 중단 기준(80% · 70%)에 도달해 새 개발 시작을 막았어요.`;
    return '지금은 실행할 수 없어요.';
  }
  return null;
}
const featureList = (d) => (d && d.features) || [];
const isAccepted = (d, id) => (d.accepted || []).includes(id);
function nextFeature(d) { return featureList(d).find((f) => !isAccepted(d, f.task_id)) || null; }
function curFeature(d) {
  if (!d || !d.approved) return null;
  const id = d.current_feature;
  return featureList(d).find((f) => f.task_id === id) || nextFeature(d);
}
function allDone(d) { return !!(d && d.approved && featureList(d).length && featureList(d).every((f) => isAccepted(d, f.task_id))); }
function featStatus(d, f) {
  if (isAccepted(d, f.task_id)) return 'accepted';
  if (!d.approved) return 'planned';
  if (f.task_id === d.current_feature) {
    if (latched(d)) return 'blocked';
    if (d.stage === 'pr_ready') return 'review';
    if (d.stage === 'needs_user') return 'needs_decision';
    if (d.pause_requested && !busy(d)) return 'stopped';
    if (['implement', 'review', 'final_test', 'manager'].includes(d.stage)) return agentBusy(d) ? 'in_dev' : 'waiting';
  }
  return 'waiting';
}
function scenario(d) {
  if (!d) return 'loading';
  if (d.stage === 'not_started') return busy(d) ? 'processing' : 'notstarted';
  if (agentBusy(d)) return d.pause_requested ? 'stop_requested' : 'running';
  if (d.busy) return 'processing';
  if (latched(d)) return 'blocked';
  if (d.stage === 'needs_user') return 'needs_user';
  if (d.stage === 'awaiting_plan_approval' && !d.approved) return 'plan_approval';
  if (d.stage === 'pr_ready') return 'code_review';
  if (allDone(d)) return 'complete';
  if (d.pause_requested) return 'stopped';
  if (!d.approved) return 'planning_idle';
  return 'ready';
}
function stageIndex(d, sc) {
  if (!d) return 0;
  if (sc === 'complete') return 9;
  const map = { not_started: 0, plan: 1, spec_review: 2, awaiting_plan_approval: 3, implement: 4, review: 5, final_test: 6, pr_ready: 8 };
  let i = map[d.stage];
  if (d.stage === 'manager' || d.stage === 'needs_user' || i == null) i = d.current_feature ? 5 : 2;
  if (d.stage === 'awaiting_plan_approval' && d.approved) i = 4;
  return i;
}
function planIdentity(d) { return [d.parent_revision, d.candidate_hash, (d.document || '').length, hashText(d.document || '')].join('|'); }
function hashText(t) { let x = 0; for (let i = 0; i < t.length; i++) x = (x * 31 + t.charCodeAt(i)) | 0; return x; }
function pin() {
  const d = S.data; if (!d) return;
  if (!S.pinned) S.pinned = { id: planIdentity(d), doc: d.document || '', hash: d.candidate_hash, rev: d.parent_revision };
}
function adoptPlan() { S.pinned = null; S.approveChecked = false; S.showHash = false; pin(); render(); }
const planStale = (d) => !!(d && S.pinned && S.pinned.id !== planIdentity(d));
function latestResult(d, stages) { return [...((d && d.results) || [])].reverse().find((r) => stages.includes(r.stage)) || null; }
function outcomeChip(o) {
  return o === 'pass' ? chip('passed', '통과') : o === 'changes' ? chip('needs_decision', '수정 필요')
    : o === 'blocked' ? chip('failed', '중단') : o ? chip('unknown', o) : chip('waiting', '기록 없음');
}
function jobEvents(d) {
  return (d.jobs || []).map((j) => ({
    at: j.started_at, actor: '나', kind: j.action === 'run' ? 'run' : j.action === 'approve' || j.action === 'accept-feature' ? 'approve' : j.action === 'revise' ? 'change' : 'request',
    title: (NAMES[j.action] || j.action) + ' 요청', desc: j.status === 'failed' ? `실패 · 종료 코드 ${j.exit_code ?? '알 수 없음'}` : '',
    st: j.status === 'running' ? 'processing' : j.status === 'failed' ? 'failed' : 'done',
    raw: [`action=${j.action}`, `status=${j.status}`, `started=${j.started_at}`].concat(j.finished_at ? [`finished=${j.finished_at}`] : []),
    log: j.log_file || null,
  }));
}
const EVENT = {
  activation: ['나', 'run', '실행 시작'], handoff: ['한자리', 'run', '기능 인계'], recheck: ['한자리', 'run', '재검증'],
  approval: ['나', 'approve', '기획 승인 기록'], human_accept: ['나', 'approve', '기능 수락 기록'],
  revision: ['나', 'change', '기획 수정 요청'], repair: ['한자리', 'change', '자동 수정 진행'], idea: ['나', 'request', '아이디어 기록'],
  pause: ['나', 'request', '중단 요청'], breaker_reset: ['나', 'request', '반복 방지 해제'], breaker: ['한자리', 'run', '반복 방지 작동'],
  attention: ['한자리', 'run', '확인 필요'],
};
function allEvents(d) {
  if (!d) return [];
  const evs = (d.events || []).map((e) => {
    const [actor, kind, title] = EVENT[e.kind] || ['한자리', 'run', e.kind];
    const generic = /^[\x00-\x7F]*$/.test(e.message || '');
    return {
      at: e.at, actor, kind, title, desc: generic ? '' : clip(e.message, 220), st: e.kind === 'breaker' ? 'failed' : 'done',
      raw: [`kind=${e.kind}`, `at=${e.at}`, '', e.message || ''],
    };
  });
  return evs.concat(jobEvents(d)).sort((a, b) => String(b.at).localeCompare(String(a.at)));
}

/* ------------------------------------------------------------------ small UI pieces */
function chip(k, label) { const s = ST[k] || ST.waiting; return { label: label || s[0], icon: s[1], fg: s[2], bg: s[3], spin: !!s[4] }; }
function chipEl(c, cls) { return h('span', { class: 'chip ' + (cls || ''), style: { color: c.fg, background: c.bg } }, ic(c.icon, c.fg, 14, c.spin), c.label); }
function miniChip(c) { return h('span', { class: 'mini-chip', style: { color: c.fg } }, ic(c.icon, c.fg, 14, c.spin), c.label); }
function act(label, icon, kind, onclick, title) {
  const color = { primary: C.mintInk, secondary: C.text, danger: C.coral, disabled: '#6E8078' }[kind];
  return { label, icon, kind, title, onclick: kind === 'disabled' ? () => toast(title || '지금은 실행할 수 없어요.', 'warn') : onclick, color };
}
function btn(a, size) {
  return h('button', { class: `btn ${a.kind} ${size || ''}`, onclick: a.onclick, title: a.title || null, 'aria-disabled': a.kind === 'disabled' ? 'true' : null },
    a.icon ? ic(a.icon, a.color, 16, a.icon === 'loader') : null, a.label);
}
function runAct(d, label) {
  const b = runBlock(d);
  return b ? act(label, 'lock', 'disabled', null, b) : act(label, 'play', 'primary', () => send('run'));
}
function textAct(action, label, icon, kind) {
  const ok = allowed(S.data).has(action);
  return ok ? act(label, icon, kind || 'secondary', () => openModal(action)) : act(label, icon, 'disabled', null, disabledWhy(action));
}
function disabledWhy(action) {
  const d = S.data;
  if (S.conn !== 'ok') return '제어기 연결이 끊겨 있어요.';
  if (d && busy(d)) return '현재 작업이 끝난 뒤 사용할 수 있어요.';
  if (action === 'note' || action === 'revise' || action === 'sync') return '기획을 시작한 뒤 사용할 수 있어요.';
  return '지금은 사용할 수 없어요.';
}
function openModal(name) { set({ modal: name, modalText: '' }); }

/* ------------------------------------------------------------------ sidebar */
function sidebar(d, sc, narrow) {
  const cur = S.projects.find((p) => p.id === S.project);
  const name = projectName(cur);
  const heroC = d ? heroData(d, sc).chip : chip('waiting', '불러오는 중');
  const badges = {};
  if (d) {
    if (sc === 'plan_approval') badges.plan = ['승인 대기', C.amber, C.amberSoft];
    if (sc === 'code_review') badges.review = ['1', C.amber, C.amberSoft];
    const fails = recoveries(d).length;
    if (fails) badges.activity = [String(fails), C.coral, C.coralSoft];
  }
  const u = d ? d.usage : null, us = usageState(u);
  const usageChip = { ok: chip('passed', '기준 미만'), missing: chip('unknown', '값 없음'), stale: chip('stop_requested', '오래됨'), limit: chip('failed', '기준 도달') }[us];
  const usageNote = { ok: '', missing: 'Claude Code 사용량 파일을 찾지 못했어요. 0%로 간주하지 않아요.', stale: '갱신된 지 오래돼 실제와 다를 수 있어요. 개발 실행 전에 Claude Code에서 갱신하세요.', limit: '사용률이 기준에 도달해 새 개발 시작을 막았어요.' }[us];
  const bars = [['five_hour', '5시간'], ['seven_day', '주간']].map(([k, l]) => {
    const v = u ? u[k] : null, lim = LIMITS[k];
    const fill = us === 'stale' ? '#4E605A' : v >= lim ? C.coral : C.mint;
    return h('div', { style: { display: 'flex', flexDirection: 'column', gap: '5px' } },
      h('div', { class: 'bar-head' }, h('span', { class: 'muted' }, l),
        h('span', { style: { color: v == null ? C.text3 : us === 'stale' ? C.text2 : v >= lim ? C.coral : C.text, fontWeight: 600, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } },
          v == null ? '—' : v + '%', ' ', h('span', { style: { color: C.text3, fontWeight: 400 } }, `/ 기준 ${lim}%`))),
      h('div', { class: 'bar' }, h('div', { class: 'bar-fill', style: { width: (v || 0) + '%', background: fill } }),
        h('div', { class: 'bar-mark', title: '중단 기준', style: { left: `calc(${lim}% - 1px)` } })));
  });
  const upd = u ? `${hm(new Date(Date.now() - u.age_seconds * 1000).toISOString())} 갱신 · ${ago(u.age_seconds)}` : '갱신 기록 없음';
  const connMap = S.reconnecting ? ['다시 연결 중', C.amber, 'loader'] : S.conn === 'ok' ? ['연결됨', C.mint, 'plug'] : S.conn === 'failed' ? ['연결 끊김', C.coral, 'plug'] : ['연결 중', C.amber, 'loader'];
  const pending = d ? (d.receipts || []).length : 0;
  const notionMap = pending ? [`전송 미완료 ${pending}건`, C.amber, 'alert'] : ['미완료 기록 없음', C.text2, 'check'];
  return h('aside', { class: 'side', style: { width: narrow ? '220px' : '244px' } },
    h('div', { class: 'brand' }, h('div', { class: 'brand-mark' }, '한'), h('span', { class: 'brand-name' }, '한자리'), h('span', { class: 'brand-sub' }, 'AI 오케스트레이터')),
    h('div', { class: 'proj-wrap' },
      h('div', { class: 'side-label' }, '프로젝트'),
      h('button', { class: 'proj-btn', 'aria-haspopup': 'listbox', 'aria-expanded': String(S.projOpen), onclick: () => set({ projOpen: !S.projOpen }) },
        h('div', { class: 'proj-icon' }, ic('folder', null, 16)),
        h('div', { class: 'proj-text' }, h('span', { class: 'proj-name' }, S.adding ? '프로젝트 추가' : name),
          h('span', { class: 'proj-chip', style: { color: S.adding ? C.text2 : heroC.fg } }, S.adding ? '등록 정보 입력' : heroC.label)),
        h('span', { class: 'dim' }, ic('down', null, 16))),
      S.projOpen ? h('div', { class: 'proj-menu', role: 'listbox' },
        S.projects.map((p) => h('button', { class: 'proj-opt' + (p.id === S.project && !S.adding ? ' on' : ''), role: 'option', onclick: () => switchProject(p.id) },
          h('div', { style: { flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' } },
            h('span', { class: 'proj-opt-name' }, projectName(p)), h('span', { class: 'proj-opt-path mono' }, p.id)),
          p.id === S.project && !S.adding ? miniChip(heroC) : null)),
        h('div', { class: 'menu-sep' }),
        h('button', { class: 'menu-add', onclick: () => set({ adding: true, projOpen: false, formError: '' }) }, ic('plus', null, 15), '프로젝트 추가')) : null),
    h('nav', { class: 'nav', 'aria-label': '주요 화면' },
      NAV.map(([k, l, i], idx) => {
        const on = S.screen === k && !S.adding, b = badges[k];
        return h('button', { class: 'nav-btn' + (on ? ' on' : ''), title: `${l} (Ctrl+${idx + 1})`, 'aria-current': on ? 'page' : null, onclick: () => { S.adding = false; go(k); } },
          h('span', { class: 'nav-ic' }, ic(i, null, 17)), h('span', { style: { flex: 1 } }, l),
          b ? h('span', { class: 'nav-badge', style: { color: b[1], background: b[2] } }, b[0]) : null);
      })),
    h('div', { style: { flex: 1 } }),
    h('div', { class: 'side-foot' },
      h('div', { class: 'usage' },
        h('div', { class: 'row-between' }, h('span', { class: 'usage-title' }, 'Claude 사용량'), miniChip(usageChip)),
        bars,
        h('div', { class: 'usage-upd' }, ic('clock', null, 13), upd),
        usageNote ? h('div', { style: { fontSize: '12px', lineHeight: 1.5, color: us === 'limit' ? C.coral : C.amber } }, usageNote) : null),
      h('div', { class: 'health' },
        h('div', { class: 'health-row' }, h('span', { style: { color: connMap[1] } }, ic(connMap[2], connMap[1], 14, connMap[2] === 'loader')), h('span', { style: { flex: 1 } }, '제어기'), h('span', { style: { color: connMap[1], fontWeight: 600, whiteSpace: 'nowrap' } }, connMap[0])),
        h('div', { class: 'health-row' }, h('span', { style: { color: notionMap[1] } }, ic(notionMap[2], notionMap[1], 14)), h('span', { style: { flex: 1 } }, 'Notion'), h('span', { style: { color: notionMap[1], fontWeight: 600, whiteSpace: 'nowrap' } }, notionMap[0])))));
}
function projectName(p) { return !p ? '프로젝트' : p.id === 'hanjari' ? '영화 예매 MVP' : p.title; }
function switchProject(id) {
  localStorage.setItem('hj.project', id);
  Object.assign(S, { project: id, data: null, pinned: null, projOpen: false, adding: false, screen: 'overview', approveChecked: false, featSel: null, verdict: null, memo: '', fixed: {}, planQ: '' });
  render(); poll();
}

/* ------------------------------------------------------------------ overview */
function heroData(d, sc) {
  const cur = curFeature(d), feat = cur ? `${cur.task_id} ${cur.title}` : '';
  const qn = (d.questions || []).length;
  let x;
  switch (sc) {
    case 'notstarted':
      x = { chip: chip('notstarted'), title: '아직 시작하지 않았어요', sub: '아이디어가 기록돼 있어요. 실행하면 Codex가 기획 작성을 시작해요. 실행을 누르기 전에는 아무 작업도 하지 않아요.', actions: [runAct(d, '기획 작성 실행')], note: runBlock(d) };
      break;
    case 'running': {
      const label = {
        plan: ['기획 작성 중 · Codex', 'Codex가 기획안을 작성하고 있어요. 끝나면 독립 검토로 넘어가요. 지금 처리할 일은 없어요.'],
        spec_review: ['독립 기획 검토 중 · Codex', '별도 Codex 실행이 기획안을 검토하고 있어요. 수정 의견이 있으면 자동으로 반영 후 다시 검토해요.'],
        manager: ['문제 분석·재계획 중 · Codex', '반복된 실패의 원인을 분석해 방향을 다시 잡고 있어요.'],
        implement: [`개발 중 · ${feat}`, 'Claude Code가 구현과 개발 테스트를 진행하고 있어요. 지금 처리할 일은 없어요. 판단이 필요해지면 이 화면에 알려드릴게요.'],
        review: [`코드 리뷰 중 · ${feat}`, 'Codex가 변경 코드와 테스트 근거를 독립적으로 검토하고 있어요.'],
        final_test: [`최종 검증 중 · ${feat}`, '제어기가 Maven 검증을 실행하고 Codex가 근거를 판정하고 있어요.'],
      }[d.stage] || ['작업이 진행 중이에요', '현재 단계가 끝나면 다음 판단 지점에서 멈춰요.'];
      x = { chip: chip('running'), title: label[0], sub: label[1], actions: [act('진행 기록 보기', 'history', 'secondary', () => go('activity')), act('중단 요청', 'pause', 'danger', () => openModal('stop'))], note: '중단을 요청하면 지금 단계를 마친 뒤 멈춰요.', noteKind: 'info' };
      break;
    }
    case 'processing': {
      const job = (d.jobs || []).find((j) => j.status === 'running');
      x = { chip: chip('processing'), title: `요청 처리 중 · ${job ? NAMES[job.action] : '요청'}`, sub: '요청을 제어기에 전달했어요. 처리가 끝나면 상태가 바뀌어요.', actions: [act('진행 기록 보기', 'history', 'secondary', () => go('activity'))] };
      break;
    }
    case 'stop_requested': {
      const trail = [['중단 요청됨', 'done'], ['현재 단계 완료', 'active'], ['중단됨', 'todo']];
      x = { chip: chip('stop_requested'), title: '중단 요청됨 · 현재 단계를 마치는 중이에요', sub: `지금 하고 있는 ‘${STAGE_NAME[d.stage] || d.stage}’ 단계를 마치면 멈춰요. 진행 중인 단계를 강제로 끊지는 않아요.`, trail, actions: [act('진행 기록 보기', 'history', 'secondary', () => go('activity'))] };
      break;
    }
    case 'stopped':
      x = { chip: chip('stopped'), title: '중단됨 · 다음 단계를 시작하지 않았어요', sub: `마지막 상태: ${STAGE_NAME[d.stage] || d.stage}. 준비되면 실행을 눌러 이어서 진행하세요.`, trail: [['중단 요청됨', 'done'], ['현재 단계 완료', 'done'], ['중단됨', 'done']], actions: [runAct(d, '실행 재개')], note: runBlock(d) };
      break;
    case 'blocked':
      x = { chip: chip('blocked'), title: '반복 방지가 작동했어요 · 판단이 필요해요', sub: `${d.run_guard.reason || '같은 단계의 실패나 호출 한도가 반복돼 자동 실행을 멈췄어요.'} 원인을 확인하고 다음 방법을 골라 주세요.`, actions: [act('원인과 선택지 보기', 'shield', 'primary', () => go('activity')), cur ? act('기능 상세 보기', 'list', 'secondary', () => { S.featSel = cur.task_id; go('features'); }) : null].filter(Boolean) };
      break;
    case 'needs_user': {
      const r = latestResult(d, ['plan', 'spec_review', 'implement', 'review', 'final_test', 'manager']);
      x = { chip: chip('needs_decision'), title: '판단이 필요해요', sub: r ? clip(r.summary, 260) : '자동 진행을 멈추고 사용자 판단을 기다리고 있어요.', actions: [act('검토 근거 보기', 'eye', 'primary', () => go(d.current_feature ? 'review' : 'plan')), textAct('revise', '수정 요청', 'edit')] };
      break;
    }
    case 'plan_approval':
      x = { chip: chip('needs_decision'), title: '전체 기획 승인을 기다리고 있어요', sub: `Codex 독립 검토를 통과한 기획안(리비전 ${d.parent_revision})이 준비됐어요. ${qn ? `미결정 질문 ${qn}건을 정하고 ` : ''}전체를 확인한 뒤 승인하거나 수정을 요청하세요.`, actions: [act('기획안 검토하기', 'doc', 'primary', () => go('plan')), act('수정 요청', 'edit', 'secondary', () => { go('plan'); setTimeout(() => { const t = document.querySelector('[data-k="change"]'); if (t) t.focus(); }, 120); }), act('개발 실행', 'lock', 'disabled', null, '기획 승인 후 실행할 수 있어요.')], note: '개발 실행은 기획 승인 후에 할 수 있어요. 승인해도 개발은 자동으로 시작하지 않아요.', noteKind: 'info' };
      break;
    case 'code_review':
      x = { chip: chip('review'), title: `코드 리뷰 대기 · ${feat}`, sub: '자동 테스트와 Codex 코드 리뷰, 최종 검증을 마쳤어요. 관련 파일과 근거를 확인하고 판단을 기록하세요.', actions: [act('리뷰 시작', 'eye', 'primary', () => go('review'))], note: '수락은 PR 병합이 아니에요. 병합은 GitHub에서 직접 판단해요.', noteKind: 'info' };
      break;
    case 'complete':
      x = { chip: chip('done', '모두 수락'), title: '승인된 기획의 기능을 모두 수락했어요', sub: '기능별 리뷰 기록이 끝났어요. PR 병합과 배포는 GitHub에서 직접 판단해요.', actions: [act('활동 기록 보기', 'history', 'secondary', () => go('activity'))] };
      break;
    case 'planning_idle':
      x = { chip: chip('waiting', '실행 대기'), title: `실행 대기 · ${STAGE_NAME[d.stage] || d.stage}`, sub: '기획이 이어서 진행될 수 있어요. 실행을 누르기 전에는 아무 작업도 시작하지 않아요.', actions: [runAct(d, '이어서 실행'), act('기획안 보기', 'doc', 'secondary', () => go('plan'))], note: runBlock(d) };
      break;
    default: {
      const any = (d.accepted || []).length > 0;
      x = { chip: chip('waiting', '실행 대기'), title: cur ? `실행 대기 · ${feat}` : '실행 대기', sub: any ? '이전 기능 수락이 기록됐어요. 다음 기능을 실행할 준비가 됐어요. 실행을 누르기 전에는 시작하지 않아요.' : `기획안 r${d.approved_revision || d.parent_revision}이 승인됐어요. 개발 실행을 누르기 전에는 아무 작업도 시작하지 않아요.`, actions: [runAct(d, '개발 실행'), act('기능 목록 보기', 'list', 'secondary', () => go('features'))], note: runBlock(d) };
    }
  }
  x.noteKind = x.noteKind || 'warn';
  return x;
}
function ownerData(d, sc) {
  const cur = curFeature(d);
  const stageTxt = STAGE_NAME[d.stage] || d.stage;
  const M = {
    notstarted: ['없음', '—', C.text3, '실행 대기', [['실행 시 담당', 'Codex · 기획 작성']]],
    running: d.stage === 'implement' ? ['Claude Code', 'CC', C.mint, '기능별 개발', [['현재 단계', stageTxt], ['기능', cur ? cur.task_id : '—']]]
      : ['Codex', 'CX', C.mint, stageTxt, [['현재 단계', stageTxt], ['기록', `전체 호출 ${(d.run_guard || {}).attempts || 0}회`]]],
    processing: ['제어기', '한', C.mint, '요청 처리 중', [['처리 방식', '로컬 workspace.py 호출']]],
    stop_requested: [d.stage === 'implement' ? 'Claude Code' : 'Codex', d.stage === 'implement' ? 'CC' : 'CX', C.amber, '현재 단계 마무리', [['현재 단계', stageTxt], ['멈추는 시점', '현재 단계 완료 후']]],
    stopped: ['없음', '—', C.text3, '중단됨', [['마지막 상태', stageTxt], ['재개 방법', '실행 재개 버튼']]],
    blocked: ['나', '나', C.coral, '반복 방지 해제 또는 방향 결정', [['기록', `전체 호출 ${(d.run_guard || {}).attempts || 0}회`], ['원인', clip((d.run_guard || {}).reason || '기록 없음', 60)]]],
    needs_user: ['나', '나', C.amber, '판단 차례', [['멈춘 단계', stageTxt]]],
    plan_approval: ['나', '나', C.amber, '전체 기획 승인 차례', [['검토 대상', `기획안 r${d.parent_revision} · 독립 검토 통과`], ['다음 담당', 'Claude Code · 기능별 개발 (실행 후)']]],
    code_review: ['나', '나', C.amber, '기능 코드 리뷰·수락', [['코드 리뷰', resultLabel(latestResult(d, ['review']))], ['최종 검증', resultLabel(latestResult(d, ['final_test']))]]],
    complete: ['없음', '—', C.text3, '모든 기능 수락', [['수락 기록', `${(d.accepted || []).length}개 기능`]]],
    planning_idle: ['없음', '—', C.text3, '실행 대기', [['다음 단계', stageTxt]]],
    ready: ['Claude Code', 'CC', C.text2, '실행 대기', [['다음 작업', cur ? `${cur.task_id} 개발과 개발 테스트` : '—'], ['승인 기록', d.approved_at ? `기획안 r${d.approved_revision} · ${when(d.approved_at)}` : '승인됨']]],
  };
  return M[sc] || M.ready;
}
const resultLabel = (r) => r ? ({ pass: '통과', changes: '수정 필요', blocked: '중단' }[r.outcome] || r.outcome) : '기록 없음';
function attention(d, sc) {
  const out = [];
  const qn = (d.questions || []).length;
  if (qn) out.push(['alert', C.amber, `미결정 질문 ${qn}건`, clip((d.questions || []).map(textOf).join(' · '), 120), () => go('plan')]);
  if (planStale(d)) out.push(['alert', C.amber, '검토 중 새 기획 버전 도착', '읽던 문서는 그대로 두었어요. 새 버전을 다시 확인한 뒤 승인하세요.', () => go('plan')]);
  if (sc === 'code_review') out.push(['eye', C.amber, `코드 리뷰 판단 대기 · ${d.current_feature}`, '테스트 통과와 별개로 수락 여부를 직접 기록해요.', () => go('review')]);
  if (sc === 'needs_user') out.push(['alert', C.amber, '사용자 판단 필요', '자동 진행이 멈췄어요. 근거를 확인하고 수정 요청으로 답해 주세요.', () => go(d.current_feature ? 'review' : 'plan')]);
  for (const r of recoveries(d)) out.push([r.icon, C.coral, r.title, clip(r.cause, 110), () => go('activity')]);
  const us = usageState(d.usage);
  if (us !== 'ok') out.push([us === 'limit' ? 'lock' : 'info', C.amber, { missing: 'Claude 사용량 값 없음', stale: 'Claude 사용량 정보가 오래됨', limit: 'Claude 사용량 기준 도달' }[us], { missing: '사용량 파일을 읽지 못했어요. 개발 실행 전에 Claude Code에서 갱신하세요.', stale: `${ago(d.usage?.age_seconds)} 값이에요. 개발 실행 전에 갱신이 필요해요.`, limit: '새 개발 시작을 막았어요. 진행 중인 단계는 영향을 받지 않아요.' }[us], null]);
  return out;
}
function overview(d, sc, narrow) {
  const hero = heroData(d, sc), own = ownerData(d, sc);
  const stage = stageIndex(d, sc);
  const tone = { blocked: 'coral', stopped: 'gray', running: 'mint', processing: 'mint', ready: 'mint', notstarted: 'gray', planning_idle: 'gray' }[sc] || 'amber';
  const toneC = { amber: C.amber, mint: C.mint, coral: C.coral, gray: C.text2 }[tone];
  const soft = tone === 'coral' ? C.coralSoft : tone === 'amber' ? C.amberSoft : C.mintSoft;
  const n = featureList(d).length, cur = curFeature(d);
  const loopNote = !n ? '5–7단계는 기능마다 반복돼요' : !d.approved ? `5–7단계는 기능 ${n}개마다 반복돼요` : cur ? `5–7단계는 기능마다 반복돼요 · 현재 ${cur.task_id} (${featureList(d).indexOf(cur) + 1}/${n})` : `기능 ${n}개 모두 수락`;
  const latest = latestResult(d, ['spec_review', 'review', 'final_test']);
  const attn = attention(d, sc);
  const recent = allEvents(d).slice(0, 5);
  const trailStep = ([label, st], i, arr) => h('li', null,
    h('span', { class: 'trail-step', style: { borderColor: st === 'active' ? 'rgba(235,192,122,0.5)' : C.line, color: st === 'todo' ? C.text3 : C.text, background: st === 'active' ? C.amberSoft : 'transparent' } },
      ic(st === 'done' ? 'check' : st === 'active' ? 'loader' : 'dashed', st === 'done' ? C.mint : st === 'active' ? C.amber : C.text3, 14, st === 'active'), label),
    i < arr.length - 1 ? h('span', { style: { color: '#4E605A' } }, ic('right', C.text3, 14)) : null);
  return h('div', { class: 'scroll', 'data-scroll': 'overview' },
    h('div', { class: 'page', style: { maxWidth: '1180px', padding: `24px ${narrow ? 24 : 32}px 40px`, gap: '18px' } },
      h('section', { class: 'hero', 'aria-label': '현재 상태', style: { gridTemplateColumns: narrow ? 'minmax(0,1fr) 250px' : 'minmax(0,1fr) 290px' } },
        h('div', { class: 'hero-main' },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' } }, chipEl(hero.chip, 'lg'),
            h('span', { class: 'meta', style: { fontSize: '13px', whiteSpace: 'nowrap' } }, stage >= 9 ? '9/9 단계 · 완료' : `${stage + 1}/9 단계 · ${STAGES[stage][0]}`)),
          h('h2', null, hero.title), h('p', null, hero.sub),
          hero.trail ? h('ol', { class: 'trail', 'aria-label': '중단 진행' }, hero.trail.map(trailStep)) : null,
          h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap', marginTop: '4px' } }, hero.actions.map((a) => btn(a))),
          hero.note ? h('div', { class: 'note', style: { color: hero.noteKind === 'warn' ? C.amber : C.text2 } }, ic(hero.noteKind === 'warn' ? 'lock' : 'info', hero.noteKind === 'warn' ? C.amber : C.text3, 14), h('span', null, hero.note)) : null),
        h('div', { class: 'hero-side' },
          h('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px' } }, h('span', { class: 'fact-k' }, '지금 담당'),
            h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, h('div', { class: 'avatar', style: { color: own[2] } }, own[1]),
              h('div', { style: { display: 'flex', flexDirection: 'column', minWidth: 0 } }, h('span', { style: { fontWeight: 700, fontSize: '14.5px' } }, own[0]), h('span', { class: 'muted', style: { fontSize: '12.5px' } }, own[3])))),
          own[4].map(([k, v]) => h('div', { style: { display: 'flex', flexDirection: 'column', gap: '2px' } }, h('span', { class: 'fact-k' }, k), h('span', { class: 'fact-v' }, v))))),
      h('section', { class: 'card', 'aria-label': '전체 단계', style: { padding: '18px 20px 16px', gap: '14px' } },
        h('div', { style: { display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' } }, h('h3', null, '전체 단계'), h('span', { class: 'meta' }, loopNote)),
        h('ol', { class: 'stages' }, STAGES.map(([label, who], i) => {
          const done = i < stage, curr = i === stage;
          return h('li', { 'aria-current': curr ? 'step' : null },
            h('div', { style: { display: 'flex', alignItems: 'center' } },
              h('div', { class: 'stage-dot', style: { background: done ? C.mintSoft : curr ? toneC : 'transparent', color: curr ? C.mintInk : done ? C.mint : C.text3, borderColor: done ? 'transparent' : curr ? toneC : C.line2, boxShadow: curr ? `0 0 0 4px ${soft}` : 'none' } }, done ? ic('check', C.mint, 14) : String(i + 1)),
              h('div', { class: 'stage-line', style: { background: i < stage ? C.mint : C.line2, opacity: i === 8 ? 0 : i < stage ? 0.55 : 1 } })),
            h('div', { style: { display: 'flex', flexDirection: 'column', gap: '1px', paddingRight: '8px' } },
              h('span', { style: { fontSize: '12.5px', lineHeight: 1.35, fontWeight: curr ? 700 : 500, color: curr ? C.text : done ? C.text2 : C.text3 } }, label),
              h('span', { style: { fontSize: '11.5px', color: C.text3 } }, who)));
        }))),
      h('div', { class: 'grid2' },
        h('section', { class: 'card', 'aria-label': '최신 검토 결과' },
          h('div', { class: 'row-between', style: { gap: '10px' } }, h('h3', null, '최신 검토 결과'), latest ? chipEl(outcomeChip(latest.outcome)) : chipEl(chip('notstarted', '없음'))),
          latest ? [
            h('div', { style: { display: 'flex', flexDirection: 'column', gap: '2px' } },
              h('span', { style: { fontWeight: 600, fontSize: '14.5px' } }, latest.stage === 'spec_review' ? `독립 기획 검토 · Codex · 기획안 r${latest.revision}` : `${STAGE_NAME[latest.stage]} · Codex · ${d.current_feature || ''}`),
              h('span', { class: 'meta' }, `${when(latest.finished_at)} · 리비전 ${latest.revision}`)),
            h('ul', { class: 'points' },
              h('li', null, ic('info', C.text3, 14), h('span', null, clip(latest.summary, 220))),
              ((latest.artifact || {}).findings || []).slice(0, 2).map((f) => h('li', null, ic('alert', C.amber, 14), h('span', null, clip(textOf(f), 160)))),
              ((latest.artifact || {}).findings || []).length ? null : (latest.evidence || []).slice(0, 2).map((e) => h('li', null, ic('check', C.mint, 14), h('span', null, clip(e, 160))))),
            h('button', { class: 'link-btn', style: { alignSelf: 'flex-start', marginLeft: '-10px' }, onclick: () => go(latest.stage === 'spec_review' ? 'plan' : 'review') }, latest.stage === 'spec_review' ? '기획과 결정 열기' : '리뷰와 검증 열기', ic('right', null, 14)),
          ] : h('div', { class: 'muted', style: { fontSize: '13.5px' } }, '아직 검토 결과가 없어요. 기획 작성 후 Codex 독립 검토가 진행돼요.')),
        h('section', { class: 'card', 'aria-label': '주의가 필요한 항목' },
          h('div', { class: 'row-between' }, h('h3', null, '주의가 필요한 항목'), h('span', { class: 'meta' }, attn.length ? attn.length + '건' : '')),
          attn.length ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } }, attn.map(([icon, color, title, desc, fn]) =>
            h('button', { class: 'attn', onclick: fn || null, style: { cursor: fn ? 'pointer' : 'default' } },
              h('span', { style: { marginTop: '2px', color } }, ic(icon, color, 16)),
              h('span', { style: { flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: '2px' } }, h('span', { style: { fontWeight: 600, fontSize: '13.5px' } }, title), h('span', { class: 'muted', style: { fontSize: '12.5px', lineHeight: 1.55 } }, desc)),
              fn ? h('span', { class: 'dim', style: { marginTop: '2px' } }, ic('right', null, 14)) : null)))
            : h('div', { class: 'muted', style: { padding: '18px 4px', fontSize: '13.5px', display: 'flex', alignItems: 'center', gap: '8px' } }, ic('check', C.mint, 16), '지금 확인할 항목이 없어요.'))),
      h('section', { class: 'card', 'aria-label': '최근 활동', style: { padding: '18px 20px 10px', gap: '6px' } },
        h('div', { class: 'row-between' }, h('h3', null, '최근 활동'), h('button', { class: 'link-btn', onclick: () => go('activity') }, '전체 기록', ic('right', null, 14))),
        recent.length ? recent.map((e) => {
          const c = e.st === 'processing' ? chip('processing') : e.st === 'failed' ? chip('failed') : chip('done', e.kind === 'approve' ? '기록됨' : '완료');
          return h('div', { class: 'recent-row' }, h('span', { class: 'mono dim', style: { fontSize: '12px' } }, hm(e.at)),
            h('span', { class: 'muted' }, ic(KIND_IC[e.kind] || 'info', e.st === 'failed' ? C.coral : C.text2, 15)),
            h('span', { class: 'ellipsis', style: { minWidth: 0, fontSize: '13.5px' } }, h('span', { class: 'muted' }, `${e.actor} · `), e.title + (e.desc ? ' — ' + e.desc : '')),
            miniChip(c));
        }) : h('div', { class: 'muted', style: { padding: '10px 0 12px', fontSize: '13.5px' } }, '아직 활동 기록이 없어요.'))));
}
const KIND_IC = { run: 'terminal', change: 'edit', approve: 'usercheck', request: 'send', error: 'tri', sync: 'refresh' };

/* ------------------------------------------------------------------ plan */
function parseDoc(md) {
  const blocks = []; let code = null, list = null, table = null;
  const flush = () => { if (list) blocks.push(list); if (table) blocks.push(table); list = null; table = null; };
  for (const raw of String(md || '').split('\n')) {
    const line = raw.replace(/\s+$/, '');
    if (line.trim().startsWith('```')) {
      if (code) { blocks.push(code); code = null; } else { flush(); code = { t: 'code', x: '' }; }
      continue;
    }
    if (code) { code.x += (code.x ? '\n' : '') + raw; continue; }
    let m;
    if (!line.trim()) { flush(); continue; }
    if ((m = line.match(/^(#{1,6})\s+(.*)$/))) { flush(); blocks.push({ t: m[1].length === 1 ? 'h1' : m[1].length === 2 ? 'h2' : 'h3', x: m[2] }); continue; }
    if (/^\s*\|.*\|\s*$/.test(line)) {
      const cells = line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
      if (list) { blocks.push(list); list = null; }
      if (cells.every((c) => /^:?-{2,}:?$/.test(c))) continue;
      if (!table) table = { t: 'table', head: cells, rows: [] }; else table.rows.push(cells);
      continue;
    }
    if ((m = line.match(/^\s*(?:[-*•]|(\d+)[.)])\s+(.*)$/))) {
      if (table) { blocks.push(table); table = null; }
      const type = m[1] ? 'ol' : 'ul';
      if (!list || list.t !== type) { if (list) blocks.push(list); list = { t: type, items: [] }; }
      list.items.push(m[2]); continue;
    }
    flush();
    if ((m = line.match(/^\[([^\]]{1,20})\]\s*(.*)$/))) { blocks.push({ t: 'dec', tag: m[1], x: m[2] }); continue; }
    blocks.push({ t: 'p', x: line });
  }
  if (code) blocks.push(code);
  flush();
  return blocks;
}
function decTag(tag) {
  if (/사용자\s*확정|확정/.test(tag)) return ['사용자 확정', 'usercheck', C.mint, C.mintSoft, C.line];
  if (/미결정|질문|판단/.test(tag)) return ['미결정 · 판단 필요', 'alert', C.amber, C.amberSoft, 'rgba(235,192,122,0.45)'];
  if (/제안/.test(tag)) return [tag.includes('승인') ? '승인 대상 제안 · Codex' : 'AI 제안 · Codex', 'bulb', C.text2, C.gray, C.line];
  return [tag, 'info', C.text2, C.gray, C.line];
}
function inline(text, q) {
  // **bold** and `code` only; everything else stays plain text. Search hits are highlighted.
  const out = [];
  for (const part of String(text).split(/(\*\*[^*\n]+\*\*|`[^`\n]+`)/)) {
    if (!part) continue;
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) out.push(h('strong', null, mark(part.slice(2, -2), q)));
    else if (part.startsWith('`') && part.endsWith('`') && part.length > 2) out.push(h('code', null, mark(part.slice(1, -1), q)));
    else out.push(...mark(part, q));
  }
  return out;
}
function mark(t, q) {
  if (!q) return [t];
  const out = [], lt = t.toLowerCase(), lq = q.toLowerCase(); let i = 0, j;
  while ((j = lt.indexOf(lq, i)) !== -1) { if (j > i) out.push(t.slice(i, j)); out.push(h('span', { class: 'hl' }, t.slice(j, j + q.length))); i = j + q.length; }
  if (i < t.length) out.push(t.slice(i));
  return out;
}
function blockText(b) { return [b.x || '', (b.items || []).join(' '), (b.head || []).join(' '), (b.rows || []).map((r) => r.join(' ')).join(' ')].join(' ').toLowerCase(); }
function planScreen(d, narrow) {
  const pinned = S.pinned || { doc: d.document || '', hash: d.candidate_hash, rev: d.parent_revision };
  const stale = planStale(d);
  const all = parseDoc(pinned.doc);
  const titleBlock = all.find((b) => b.t === 'h1');
  const blocks = all.filter((b) => b !== titleBlock);
  const q = S.planQ.trim(), lq = q.toLowerCase();
  let hits = 0, hitIds = [];
  const heads = [];
  const els = blocks.map((b, i) => {
    const id = 'blk-' + i;
    if (b.t === 'h2') heads.push({ id, label: b.x });
    const hit = !!q && blockText(b).includes(lq);
    if (hit) { hits++; hitIds.push(id); }
    const style = { opacity: q && !hit && b.t !== 'h2' ? 0.4 : 1, background: hit && (b.t === 'code' || b.t === 'table') ? 'rgba(115,226,187,0.07)' : 'transparent' };
    let inner;
    if (b.t === 'h2') inner = h('h2', null, inline(b.x, q));
    else if (b.t === 'h3') inner = h('h3', null, inline(b.x, q));
    else if (b.t === 'ul' || b.t === 'ol') inner = h(b.t, null, b.items.map((it) => h('li', null, inline(it, q))));
    else if (b.t === 'code') inner = h('pre', null, b.x);
    else if (b.t === 'table') inner = h('div', { class: 'tbl-wrap' }, h('table', null, h('thead', null, h('tr', null, b.head.map((c) => h('th', null, inline(c, q))))), h('tbody', null, b.rows.map((r) => h('tr', null, r.map((c) => h('td', null, inline(c, q))))))));
    else if (b.t === 'dec') {
      const [label, icon, fg, bg, bd] = decTag(b.tag);
      inner = h('div', { class: 'dec', style: { borderColor: bd } },
        h('span', { class: 'chip sm', style: { alignSelf: 'flex-start', color: fg, background: bg, fontWeight: 700 } }, ic(icon, fg, 14), label),
        h('div', { class: 'dec-body' }, inline(b.x, q)));
    } else inner = h('p', null, inline(b.x, q));
    return h('div', { class: 'blk', id, style }, inner);
  });
  const scrollTo = (id) => { const c = document.querySelector('[data-scroll="doc"]'), el = document.getElementById(id); if (c && el) c.scrollTo({ top: el.offsetTop - 8, behavior: 'smooth' }); S.tocActive = id; render(); };
  const nextHit = () => { if (!hitIds.length) return; S.planHit = (S.planHit + 1) % hitIds.length; scrollTo(hitIds[S.planHit]); };
  const toc = heads.map((t) => ({ ...t, on: (S.tocActive || (heads[0] && heads[0].id)) === t.id }));
  const qn = (d.questions || []).length;
  const versionOk = !stale && pinned.hash && pinned.hash === d.candidate_hash;
  const can = allowed(d).has('approve') && versionOk && S.approveChecked;
  const approveHint = S.conn !== 'ok' ? '제어기 연결이 끊겨 승인을 기록할 수 없어요.' : busy(d) ? '작업을 처리하는 중이라 승인할 수 없어요.'
    : stale ? '새 버전이 올라와 지금 문서의 승인은 막혔어요. 최신 버전을 확인하세요.' : qn ? `미결정 질문 ${qn}건을 수정 요청으로 답해 주세요.`
    : !S.approveChecked ? '전체를 읽었다면 확인란에 표시해 주세요.' : '승인해도 개발은 자동으로 시작하지 않아요.';
  const spec = [...(d.results || [])].reverse().find((r) => r.stage === 'spec_review' && r.revision === d.parent_revision) || latestResult(d, ['spec_review']);
  const CK = (label, ok) => h('li', { style: { display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: ok ? C.text : C.text2 } }, ic(ok ? 'check' : 'dashed', ok ? C.mint : C.text3, 15), label);
  const decGroups = [['confirmed', '사용자 확정', 'usercheck', C.mint, d.confirmed_constraints || []], ['ai', 'AI 판단·제안', 'bulb', C.text2, d.proposals || []], ['open', '미결정 질문', 'alert', qn ? C.amber : C.text3, (d.questions || []).map(textOf)]];
  const versions = [...(d.plan_versions || [])].reverse();
  const canRevise = allowed(d).has('revise');
  return h('div', { class: 'split', style: { gridTemplateColumns: narrow ? 'minmax(0,1fr) 290px' : '210px minmax(0,1fr) 320px' } },
    narrow ? null : h('nav', { class: 'toc', 'aria-label': '목차' }, h('div', { class: 'meta', style: { fontSize: '12px', fontWeight: 600, padding: '0 10px 8px' } }, '목차'),
      toc.length ? toc.map((t) => h('button', { class: 'toc-btn' + (t.on ? ' on' : ''), 'aria-current': t.on ? 'true' : null, onclick: () => scrollTo(t.id) }, t.label)) : h('div', { class: 'dim', style: { padding: '0 10px', fontSize: '13px' } }, '목차 없음')),
    h('div', { class: 'col' },
      h('div', { class: 'toolbar' },
        h('label', { class: 'search' }, ic('search', null, 15),
          h('input', { 'data-k': 'planq', value: S.planQ, placeholder: '본문 검색 (Ctrl+F · Enter 다음)', 'aria-label': '기획안 본문 검색', oninput: (e) => { S.planQ = e.target.value; S.planHit = -1; render(); }, onkeydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); nextHit(); } } }),
          q ? [h('span', { style: { fontSize: '12px', color: hits ? C.mint : C.amber, whiteSpace: 'nowrap' } }, hits ? `${hits}곳` : '0곳'), h('button', { class: 'icon-btn', 'aria-label': '검색어 지우기', onclick: () => set({ planQ: '' }) }, ic('x', null, 15))] : null),
        narrow && toc.length ? h('select', { class: 'select', 'aria-label': '목차로 이동', onchange: (e) => scrollTo(e.target.value) }, toc.map((t) => h('option', { value: t.id }, t.label))) : null,
        h('div', { style: { flex: 1 } }),
        h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: '6px', fontSize: '12.5px', color: C.text2, whiteSpace: 'nowrap' } }, ic('doc', null, 15), `기획안 r${pinned.rev || '—'} 보는 중`)),
      stale ? h('div', { class: 'banner warn', role: 'alert', style: { padding: '12px 20px' } }, h('span', { style: { color: C.amber } }, ic('alert', null, 18)),
        h('div', { style: { flex: 1, fontSize: '13.5px', lineHeight: 1.6 } }, h('strong', { style: { color: C.amber } }, `검토하는 동안 기획안이 r${d.parent_revision}(으)로 바뀌었어요.`), h('span', { style: { color: '#C9D8D1' } }, ' 승인은 정확히 읽은 버전에만 할 수 있어요. 새 버전을 확인한 뒤 다시 승인하세요.')),
        h('button', { class: 'btn amber', onclick: adoptPlan }, '새 버전 보기')) : null,
      h('div', { class: 'doc-scroll', 'data-scroll': 'doc', onscroll: (e) => {
        const c = e.currentTarget; let a = heads[0] && heads[0].id;
        for (const t of heads) { const el = document.getElementById(t.id); if (el && el.offsetTop - 40 <= c.scrollTop) a = t.id; }
        if (a !== S.tocActive) { S.tocActive = a; render(); }
      } },
        h('article', { class: 'doc' },
          h('div', { class: 'doc-head' }, h('span', { class: 'meta' }, `기획안 · Codex 작성 · r${pinned.rev || '—'}${spec ? ' · ' + when(spec.finished_at) + ' 검토' : ''}`),
            h('h2', null, titleBlock ? titleBlock.x : '전체 기획안')),
          !pinned.doc ? h('div', { class: 'muted' }, '아직 기획안이 없어요. 진행 현황에서 기획 작성을 실행하면 문서가 표시돼요.') : null,
          q && !hits ? h('div', { style: { padding: '16px', marginBottom: '20px', border: '1px dashed #34423C', borderRadius: '10px', color: C.text2, fontSize: '14px', display: 'flex', alignItems: 'center', gap: '10px' } }, ic('search', null, 15), h('span', { style: { flex: 1 } }, `‘${q}’와 일치하는 내용이 없어요. 다른 표현으로 찾아보세요.`), h('button', { class: 'btn secondary xs', onclick: () => set({ planQ: '' }) }, '검색어 지우기')) : null,
          els))),
    h('aside', { class: 'panel', 'aria-label': '버전과 승인' },
      d.approved ? h('div', { class: 'box ok' },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', color: C.mint, fontWeight: 700 } }, ic('usercheck', null, 16), `기획안 r${d.approved_revision || d.parent_revision} 승인됨`),
        h('div', { class: 'muted', style: { fontSize: '13px', lineHeight: 1.6 } }, `${when(d.approved_at)} · 승인 후 변경은 수정 요청으로 새 기획 버전을 만들어요.`),
        h('div', { style: { fontSize: '13px', color: '#C9D8D1', lineHeight: 1.6 } }, '개발은 승인과 별개로 진행 현황에서 실행해요.'),
        h('button', { class: 'btn secondary sm', style: { alignSelf: 'flex-start' }, onclick: () => go('overview') }, '진행 현황으로', ic('right', null, 14)))
        : d.candidate_hash ? h('div', { class: 'box raised' },
          h('div', { class: 'row-between' }, h('span', { style: { fontWeight: 700, fontSize: '14.5px' } }, '전체 기획 승인'), h('span', { class: 'meta', style: { fontSize: '12px' } }, `대상 r${pinned.rev}`)),
          h('ul', { style: { margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '8px' } },
            CK(qn ? `미결정 질문 정리 (${qn}건 남음)` : '미결정 질문 없음', !qn), CK(versionOk ? `최신 버전과 일치 (r${pinned.rev})` : '최신 버전과 일치하지 않음', versionOk), CK('독립 검토 통과', true)),
          h('label', { class: 'check-row' }, h('input', { type: 'checkbox', checked: S.approveChecked, onchange: (e) => set({ approveChecked: e.target.checked }) }), h('span', null, `기획안 r${pinned.rev} 전체를 읽고 확인했어요`)),
          h('button', { class: 'btn block ' + (can ? 'primary' : 'disabled'), 'aria-disabled': can ? null : 'true', onclick: () => can ? openModal('approve') : toast(approveHint, 'warn') }, ic('usercheck', can ? C.mintInk : '#6E8078', 16), `r${pinned.rev} 승인`),
          h('div', { style: { fontSize: '12.5px', lineHeight: 1.6, color: can ? C.text2 : C.amber } }, approveHint))
        : h('div', { class: 'box raised' }, h('span', { style: { fontWeight: 700, fontSize: '14.5px' } }, '전체 기획 승인'),
          h('div', { class: 'muted', style: { fontSize: '13px', lineHeight: 1.6 } }, '독립 검토를 통과한 기획안이 생기면 여기에서 승인할 수 있어요.')),
      h('div', { class: 'box' },
        h('div', { class: 'row-between' }, h('span', { class: 'box-title' }, '독립 검토 · Codex'), spec ? miniChip(outcomeChip(spec.outcome)) : miniChip(chip('waiting', '기록 없음'))),
        spec ? [h('div', { style: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' } },
          h('div', { class: 'stat' }, h('div', { class: 'dim', style: { fontSize: '11.5px' } }, '검토 의견'), h('div', { style: { fontWeight: 700 } }, `${((spec.artifact || {}).findings || []).length}건`)),
          h('div', { class: 'stat' }, h('div', { class: 'dim', style: { fontSize: '11.5px' } }, '검토 근거'), h('div', { style: { fontWeight: 700 } }, `${(spec.evidence || []).length}건`))),
          h('div', { class: 'muted', style: { fontSize: '12.5px', lineHeight: 1.6 } }, clip(spec.summary, 180)),
          h('div', { class: 'meta' }, `r${spec.revision} 검토 · ${when(spec.finished_at)}`)] : h('div', { class: 'muted', style: { fontSize: '12.5px' } }, '아직 검토 기록이 없어요.')),
      h('div', { class: 'box', style: { gap: '8px' } }, h('span', { class: 'box-title' }, '결정 구분'),
        decGroups.map(([k, label, icon, fg, items]) => [
          h('button', { class: 'dec-row', 'aria-expanded': String(S.decOpen === k), onclick: () => set({ decOpen: S.decOpen === k ? null : k }) },
            h('span', { style: { color: fg } }, ic(icon, null, 15)), h('span', { style: { flex: 1, color: '#C9D8D1' } }, label), h('span', { style: { fontWeight: 700, color: fg } }, String(items.length)),
            h('span', { class: 'dim' }, ic(S.decOpen === k ? 'down' : 'right', null, 13))),
          S.decOpen === k ? (items.length ? h('ul', { class: 'dec-list' }, items.map((t) => h('li', null, t))) : h('div', { class: 'dim', style: { fontSize: '12.5px', marginLeft: '22px' } }, '기록 없음')) : null])),
      h('div', { class: 'box' }, h('span', { class: 'box-title' }, '변경 지시·수정 요청'),
        h('textarea', { class: 'textarea', 'data-k': 'change', rows: 4, value: S.changeText, placeholder: '예: 결제 중 브라우저를 닫으면 좌석 확보를 어떻게 할지 정책을 추가해 주세요.', 'aria-label': '수정 요청 내용', oninput: (e) => { S.changeText = e.target.value; render(); } }),
        h('button', { class: 'btn sm ' + (canRevise ? 'secondary' : 'disabled'), style: { alignSelf: 'flex-start', opacity: S.changeText.trim() || !canRevise ? 1 : 0.55 }, onclick: () => {
          if (!canRevise) return toast(disabledWhy('revise'), 'warn');
          if (!S.changeText.trim()) return toast('수정할 내용을 적어 주세요.', 'warn');
          set({ modal: 'reviseConfirm', modalText: S.changeText.trim() });
        } }, ic('send', null, 14), '수정 요청 보내기'),
        h('span', { class: 'dim', style: { fontSize: '12px', lineHeight: 1.5 } }, d.approved ? '승인된 기획을 수정하면 새 버전이 만들어지고 기존 승인이 해제돼요.' : '요청하면 새 버전이 만들어지고, 실행 후 독립 검토를 다시 거쳐요.')),
      h('div', { class: 'box', style: { gap: '8px' } },
        h('div', { class: 'row-between' }, h('span', { class: 'box-title' }, '버전 기록'),
          h('button', { class: 'icon-btn', style: { fontSize: '12px' }, 'aria-pressed': String(S.showHash), onclick: () => set({ showHash: !S.showHash }) }, S.showHash ? '해시 숨기기' : '해시 보기')),
        versions.length ? versions.map((v) => h('div', { class: 'ver-row' },
          h('span', { style: { fontWeight: 700, fontSize: '13px', color: v.revision === pinned.rev ? C.mint : C.text2 } }, 'r' + v.revision),
          h('div', { style: { display: 'flex', flexDirection: 'column', gap: '1px', minWidth: 0 } },
            h('span', { style: { fontSize: '12.5px', color: '#C9D8D1' } }, (v.revision === 1 ? '최초 작성' : '수정 요청 반영') + ' · ' + ({ pass: '독립 검토 통과', changes: '보완 필요 판정', blocked: '검토 중단' }[v.review_outcome] || '검토 전')),
            h('span', { class: 'dim', style: { fontSize: '11.5px' } }, `${when(v.written_at)} · Codex`),
            S.showHash && v.revision === d.parent_revision && d.candidate_hash ? h('span', { class: 'mono dim', style: { fontSize: '11px', wordBreak: 'break-all' } }, d.candidate_hash) : null)))
          : h('div', { class: 'dim', style: { fontSize: '12.5px' } }, '기록 없음'))));
}

/* ------------------------------------------------------------------ features */
function splitReq(t) {
  const s = String(t || '').trim(); if (!s) return [];
  const parts = s.split(/\s(?=AC\d+[\s:])/).map((x) => x.trim()).filter(Boolean);
  return parts.length > 1 ? parts : [s];
}
function featuresScreen(d, narrow) {
  const feats = featureList(d);
  if (!feats.length) return emptyState('list', '아직 기능이 없어요', '기획 작성이 끝나면 기획안의 기능별 업무가 여기에 표시돼요.');
  const groupOf = (st) => ['in_dev', 'review', 'blocked', 'stopped', 'needs_decision'].includes(st) ? 'active' : st === 'accepted' ? 'accepted' : 'waiting';
  const all = feats.map((f) => ({ f, st: featStatus(d, f) }));
  const fq = S.featQ.trim().toLowerCase();
  const visible = all.filter((a) => (S.featFilter === 'all' || groupOf(a.st) === S.featFilter) && (!fq || (a.f.task_id + ' ' + a.f.title + ' ' + a.f.requirements).toLowerCase().includes(fq)));
  const sel = feats.find((f) => f.task_id === S.featSel) || (curFeature(d) || feats[0]);
  const fst = featStatus(d, sel);
  const isCur = (id) => d.approved && id === (curFeature(d) || {}).task_id && !isAccepted(d, id);
  const depBtn = (id) => { const f = feats.find((x) => x.task_id === id); const c = f ? chip(featStatus(d, f)) : chip('unknown'); return h('button', { class: 'dep-btn', onclick: () => set({ featSel: id }) }, h('span', { class: 'mono muted' }, id), h('span', { style: { color: c.fg, display: 'inline-flex' } }, ic(c.icon, c.fg, 14))); };
  const post = feats.filter((g) => (g.depends_on || []).includes(sel.task_id)).map((g) => g.task_id);
  const acDone = fst === 'accepted' || fst === 'review';
  const suites = [...(sel.unit_suites || []).map((s) => ['단위', s]), ...(sel.integration_suites || []).map((s) => ['통합', s])];
  return h('div', { class: 'split', style: { gridTemplateColumns: narrow ? '280px minmax(0,1fr)' : '340px minmax(0,1fr)' } },
    h('div', { class: 'col', style: { borderRight: '1px solid #1F2925' } },
      h('div', { style: { padding: '16px 14px 10px', display: 'flex', flexDirection: 'column', gap: '10px' } },
        h('label', { class: 'search', style: { maxWidth: 'none', flex: 'none' } }, ic('search', null, 15), h('input', { 'data-k': 'featq', value: S.featQ, placeholder: '기능 이름·요구사항 검색', 'aria-label': '기능 검색', oninput: (e) => { S.featQ = e.target.value; render(); } })),
        h('div', { role: 'tablist', 'aria-label': '상태 필터', style: { display: 'flex', gap: '4px', flexWrap: 'wrap' } },
          [['all', '전체'], ['active', '진행·리뷰'], ['waiting', '대기'], ['accepted', '수락 완료']].map(([k, l]) =>
            h('button', { class: 'seg' + (S.featFilter === k ? ' on' : ''), role: 'tab', 'aria-selected': String(S.featFilter === k), onclick: () => set({ featFilter: k }) }, `${l} ${all.filter((a) => k === 'all' || groupOf(a.st) === k).length}`)))),
      h('div', { class: 'feat-list', 'data-scroll': 'featlist' },
        visible.length ? visible.map(({ f, st }) => {
          const on = sel.task_id === f.task_id, c = chip(st);
          return h('button', { class: 'feat-item' + (on ? ' on' : ''), 'aria-current': on ? 'true' : null, onclick: () => set({ featSel: f.task_id }) },
            h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', width: '100%' } },
              h('span', { class: 'mono', style: { fontSize: '12px', color: on ? C.mint : C.text2, fontWeight: 600 } }, f.task_id),
              isCur(f.task_id) ? h('span', { class: 'tag-now' }, '현재') : null, h('span', { style: { flex: 1 } }), miniChip(c)),
            h('span', { style: { fontSize: '14px', fontWeight: 600, color: C.text } }, f.title),
            h('span', { class: 'dim', style: { fontSize: '12px' } }, (f.depends_on || []).length ? '선행: ' + f.depends_on.join(', ') : '선행 없음'));
        }) : h('div', { style: { padding: '24px 12px', textAlign: 'center', color: C.text2, fontSize: '13.5px', display: 'flex', flexDirection: 'column', gap: '10px', alignItems: 'center' } },
          h('span', { class: 'dim' }, ic('search', null, 15)), h('span', null, `‘${S.featQ}’과 일치하는 기능이 없어요.`),
          h('button', { class: 'btn secondary xs', onclick: () => set({ featQ: '', featFilter: 'all' }) }, '검색어와 필터 지우기')))),
    h('div', { class: 'scroll', 'data-scroll': 'featdet', style: { minWidth: 0 } },
      h('div', { style: { maxWidth: '780px', padding: `26px ${narrow ? 24 : 32}px 60px`, display: 'flex', flexDirection: 'column', gap: '20px' } },
        h('div', { style: { display: 'flex', flexDirection: 'column', gap: '10px' } },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' } }, h('span', { class: 'mono muted', style: { fontSize: '13px', fontWeight: 600 } }, sel.task_id), chipEl(chip(fst)),
            isCur(sel.task_id) ? h('span', { style: { fontSize: '12px', fontWeight: 700, color: C.mint } }, '지금 진행 중인 기능') : null),
          h('h2', { style: { margin: 0, fontSize: '22px', fontWeight: 700, letterSpacing: '-0.01em' } }, sel.title),
          h('div', { class: 'muted', style: { fontSize: '13px' } }, `개발 Claude Code · 리뷰 Codex · 필수 테스트 묶음 ${suites.length}개`)),
        fst === 'planned' ? h('div', { class: 'info-box' }, ic('info', C.text3, 16), h('span', null, `기획 승인 전이에요. 아래 내용은 기획안 r${d.parent_revision} 기준이며 승인할 때 확정돼요.`)) : null,
        h('div', { class: 'grid2', style: { gap: '12px' } },
          h('div', { class: 'sub-card' }, h('span', { class: 'fact-k' }, '먼저 끝나야 하는 기능'),
            h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap' } }, (sel.depends_on || []).length ? sel.depends_on.map(depBtn) : h('span', { class: 'muted', style: { fontSize: '13px' } }, '없음 · 바로 시작할 수 있어요'))),
          h('div', { class: 'sub-card' }, h('span', { class: 'fact-k' }, '이 기능을 기다리는 기능'),
            h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap' } }, post.length ? post.map(depBtn) : h('span', { class: 'muted', style: { fontSize: '13px' } }, '없음')))),
        h('section', { class: 'sec' }, h('h3', null, '요구사항'), h('ul', { class: 'plain' }, splitReq(sel.requirements).map((t) => h('li', null, t)))),
        h('section', { class: 'sec' }, h('h3', null, '인수 기준'),
          h('ul', { style: { margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '6px' } },
            (sel.acceptance_criteria || []).map((t) => {
              const color = acDone ? C.mint : C.text3;
              return h('li', { class: 'ac' }, ic(acDone ? 'check' : 'dashed', color, 15), h('span', { style: { flex: 1 } }, t),
                h('span', { style: { fontSize: '12px', color, whiteSpace: 'nowrap', marginTop: '2px' } }, fst === 'accepted' ? '수락 완료' : fst === 'review' ? '검증 근거 있음' : fst === 'in_dev' ? '개발 중' : '검증 전'));
            }))),
        suites.length ? h('section', { class: 'sec' }, h('h3', null, '필수 테스트'),
          h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap' } }, suites.map(([kind, s]) => h('div', { class: 'suite' }, h('span', { class: 'muted', style: { fontSize: '12px' } }, kind), h('span', { class: 'mono', style: { fontSize: '13px', fontWeight: 600 } }, s.name), h('span', { class: 'dim', style: { fontSize: '11.5px' } }, `최소 ${s.minimum_tests}개 · 실패·건너뜀 0`))))) : null,
        h('section', { class: 'sec' }, h('h3', null, '제외 범위'), h('ul', { class: 'plain', style: { color: C.text2 } }, (sel.excluded || []).length ? sel.excluded.map((t) => h('li', null, t)) : h('li', null, '기록 없음'))),
        h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', paddingTop: '4px' } },
          sel.task_id === d.current_feature && (d.results || []).length ? btn(act('리뷰와 검증 열기', 'pr', 'primary', () => go('review'))) : null,
          btn(act('관련 기획 보기', 'doc', 'secondary', () => { S.planQ = sel.task_id; S.planHit = -1; go('plan'); }))))));
}

/* ------------------------------------------------------------------ review */
function reviewScreen(d, narrow) {
  const id = d.current_feature;
  if (!id) return emptyState('pr', '아직 리뷰할 기능이 없어요', '기획 승인 후 개발이 진행되면, 테스트와 Codex 코드 리뷰를 마친 기능이 여기에 나타나요.');
  const f = featureList(d).find((x) => x.task_id === id) || { task_id: id, title: '' };
  const results = d.results || [];
  const rv = latestResult(d, ['review']), ft = latestResult(d, ['final_test']), im = latestResult(d, ['implement']);
  const accepted = isAccepted(d, id);
  const files = [...new Set(results.flatMap((r) => r.review_files || []))];
  const readKey = (p) => `${S.project}:${id}:${p}`;
  const readN = files.filter((p) => readFiles[readKey(p)]).length;
  const evidence = results.filter((r) => ['implement', 'review', 'final_test'].includes(r.stage)).flatMap((r) => (r.evidence || []).map((e) => [STAGE_NAME[r.stage], e]));
  const comments = results.filter((r) => r.stage === 'review').flatMap((r) => ((r.artifact || {}).findings || []).map((x) => [r, textOf(x)]));
  const suites = [...(f.unit_suites || []).map((s) => ['단위', s]), ...(f.integration_suites || []).map((s) => ['통합', s])];
  const canAccept = allowed(d).has('accept-feature'), canRevise = allowed(d).has('revise'), canNote = allowed(d).has('note');
  const memo = S.memo.trim();
  const vNote = S.verdict === 'accept' && !memo ? '수락 근거(읽은 파일, 확인한 동작)를 적어 주세요. 기록에 남아요.'
    : S.verdict === 'changes' ? '수정 요청은 새 기획 버전을 만들고 기존 승인과 인계를 해제해요. 기록은 보존돼요.' + (memo ? '' : ' 무엇을 고쳐야 하는지 적어 주세요.')
    : S.verdict === 'hold' && !memo ? '보류 메모를 적어 주세요.' : '';
  const vOk = !!S.verdict && !!memo && S.conn === 'ok' && ({ accept: canAccept, changes: canRevise, hold: canNote }[S.verdict]);
  const record = () => {
    if (!vOk) {
      const why = !S.verdict ? '판단을 먼저 골라 주세요.' : !memo ? '메모를 적어 주세요.' : S.verdict === 'accept' && !canAccept ? '리뷰 대기(pr_ready) 상태에서만 수락할 수 있어요.' : disabledWhy(S.verdict === 'changes' ? 'revise' : 'note');
      return toast(why, 'warn');
    }
    if (S.verdict === 'accept') set({ modal: 'accept', modalText: memo });
    else if (S.verdict === 'changes') set({ modal: 'reviseConfirm', modalText: `[${id} 리뷰 수정 요청] ${memo}` });
    else send('note', { text: `[${id} 리뷰 보류] ${memo}` }).then((ok) => { if (ok) set({ verdict: null, memo: '' }); });
  };
  const strip = [
    ['자동 테스트·최종 검증', 'Maven verify · Codex 판정', ft ? outcomeChip(ft.outcome) : chip('waiting', '기록 없음'), ft ? clip(ft.summary, 90) : '최종 검증 전이에요', null],
    ['코드 리뷰', 'Codex 판정', rv ? outcomeChip(rv.outcome) : chip('waiting', '기록 없음'), rv ? `의견 ${((rv.artifact || {}).findings || []).length}건 · 리비전 ${rv.revision}` : '코드 리뷰 전이에요', null],
    ['사용자 수락', '나', accepted ? chip('accepted') : chip('waiting', '아직 판단 안 함'), accepted ? '수락 기록됨 · PR 병합과 별개' : '테스트 통과와 별개로 직접 기록해요', accepted ? 'ok' : 'warn'],
  ];
  return h('div', { class: 'scroll', 'data-scroll': 'review' },
    h('div', { class: 'page', style: { maxWidth: '1200px', padding: `22px ${narrow ? 24 : 32}px 60px`, gap: '18px' } },
      h('div', { role: 'tablist', 'aria-label': '리뷰할 기능', style: { display: 'flex', alignItems: 'center', gap: '6px', flexWrap: 'wrap' } },
        h('button', { class: 'seg lg on', role: 'tab', 'aria-selected': 'true', style: { display: 'inline-flex', alignItems: 'center', gap: '8px', height: '34px', padding: '0 12px', fontSize: '13px' } },
          h('span', { class: 'mono muted' }, id), f.title, h('span', { style: { color: chip(featStatus(d, f)).fg, display: 'inline-flex' } }, ic(chip(featStatus(d, f)).icon, chip(featStatus(d, f)).fg, 14)))),
      h('div', { class: 'strip' }, strip.map(([label, who, c, detail, tone]) => h('div', { class: 'strip-card', style: tone ? { borderColor: tone === 'ok' ? 'rgba(115,226,187,0.35)' : 'rgba(235,192,122,0.35)', background: tone === 'ok' ? 'rgba(115,226,187,0.05)' : 'rgba(235,192,122,0.05)' } : null },
        h('div', { class: 'row-between' }, h('span', { class: 'muted', style: { fontSize: '12.5px', fontWeight: 600, whiteSpace: 'nowrap' } }, label), h('span', { class: 'dim ellipsis', style: { fontSize: '11px' } }, who)),
        h('span', { style: { display: 'inline-flex', whiteSpace: 'nowrap', alignItems: 'center', gap: '7px', fontSize: '16px', fontWeight: 700, color: c.fg } }, ic(c.icon, c.fg, 16, c.spin), c.label),
        h('span', { style: { fontSize: '12.5px', color: '#C9D8D1', lineHeight: 1.5 } }, detail)))),
      h('div', { style: { display: 'grid', gridTemplateColumns: narrow ? 'minmax(0,1fr) 290px' : 'minmax(0,1fr) 340px', gap: '18px', alignItems: 'start' } },
        h('div', { style: { display: 'flex', flexDirection: 'column', gap: '16px', minWidth: 0 } },
          h('section', { class: 'card', style: { gap: '10px' } }, h('h3', null, '변경 요약'),
            h('ul', { class: 'plain', style: { margin: 0, paddingLeft: '20px', fontSize: '14px', lineHeight: 1.8, color: '#D3E1DA' } },
              [im, rv].filter(Boolean).length ? [im, rv].filter(Boolean).map((r) => h('li', null, h('span', { class: 'muted' }, STAGE_NAME[r.stage] + ' · '), r.summary)) : h('li', null, '아직 기록된 요약이 없어요.'))),
          h('section', { class: 'card', style: { padding: '18px 20px 12px', gap: '4px' } },
            h('div', { class: 'row-between', style: { marginBottom: '6px' } }, h('h3', null, '관련 파일 · 추천 읽기 순서'), h('span', { class: 'muted', style: { fontSize: '12.5px' } }, `${readN}/${files.length} 읽음`)),
            files.length ? files.map((p, i) => {
              const rd = !!readFiles[readKey(p)], op = S.openFile === p;
              return h('div', { style: { borderTop: '1px solid #1F2925' } },
                h('div', { class: 'file-row' },
                  h('button', { class: 'cb' + (rd ? ' on' : ''), role: 'checkbox', 'aria-checked': String(rd), 'aria-label': '읽음 표시', onclick: () => { readFiles[readKey(p)] = !rd; if (!readFiles[readKey(p)]) delete readFiles[readKey(p)]; localStorage.setItem('hj.read', JSON.stringify(readFiles)); render(); } }, rd ? ic('check', C.mintInk, 13) : null),
                  h('span', { style: { width: '18px', fontSize: '12px', color: C.text3, fontWeight: 700, textAlign: 'right' } }, String(i + 1)),
                  h('button', { class: 'file-open', 'aria-expanded': String(op), onclick: () => set({ openFile: op ? null : p }) },
                    h('span', { class: 'mono ellipsis', style: { fontSize: '12.5px', color: rd ? C.text2 : C.text, flex: 1, minWidth: 0 } }, p),
                    h('span', { class: 'dim' }, ic(op ? 'down' : 'right', null, 14)))),
                op ? h('div', { style: { margin: '0 0 10px 60px', padding: '10px 12px', borderRadius: '8px', background: '#19211E', display: 'flex', gap: '6px' } },
                  h('button', { class: 'btn secondary xs', onclick: () => openPath('file', p) }, ic('ext', null, 13), '편집기에서 열기'),
                  h('button', { class: 'btn xs', style: { border: '1px solid #34423C', background: 'transparent', color: C.text2 }, onclick: () => copy(p) }, '경로 복사')) : null);
            }) : h('div', { class: 'muted', style: { fontSize: '13px', padding: '6px 0' } }, '기록된 관련 파일이 없어요.')),
          h('section', { class: 'card' },
            h('div', { class: 'row-between', style: { gap: '10px' } }, h('h3', null, '테스트 결과와 검증 근거'), h('span', { class: 'dim', style: { fontSize: '12px' } }, ft ? when(ft.finished_at) : '')),
            h('div', { class: 'ev-table' }, h('div', { class: 'ev-row head' }, h('span', null, '단계'), h('span', null, '근거')),
              evidence.length ? evidence.slice(0, 12).map(([stage, e]) => h('div', { class: 'ev-row' }, h('span', { class: 'muted' }, stage), h('span', { style: { color: '#D3E1DA', lineHeight: 1.6 } }, e)))
                : h('div', { class: 'ev-row' }, h('span', { class: 'dim' }, '—'), h('span', { class: 'dim' }, '기록된 근거가 없어요.'))),
            suites.length ? h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap' } }, suites.map(([kind, s]) => h('div', { class: 'suite' }, h('span', { class: 'muted', style: { fontSize: '12px' } }, `${kind} · ${s.name}`), h('span', { style: { fontSize: '13.5px', fontWeight: 700 } }, `최소 ${s.minimum_tests}개 필요`), h('span', { class: 'dim', style: { fontSize: '11.5px' } }, '실패·오류·건너뜀 0')))) : null,
            evidence.length > 12 || results.length ? [h('button', { class: 'link-btn muted', style: { alignSelf: 'flex-start', marginLeft: '-10px' }, 'aria-expanded': String(S.showLog), onclick: () => set({ showLog: !S.showLog }) }, ic(S.showLog ? 'down' : 'right', null, 14), '단계별 결과 원문 펼치기'),
              S.showLog ? h('pre', { class: 'log' }, results.map((r) => `[${STAGE_NAME[r.stage] || r.stage} · r${r.revision} · ${r.outcome}] ${when(r.finished_at)}\n${r.summary}\n${(r.evidence || []).map((e) => '  - ' + e).join('\n')}`).join('\n\n')) : null] : null),
          h('section', { class: 'card', style: { gap: '10px' } },
            h('div', { class: 'row-between' }, h('h3', null, 'AI 리뷰 의견 · Codex'), h('span', { class: 'dim', style: { fontSize: '12px' } }, rv ? `리뷰 ${results.filter((r) => r.stage === 'review').length}회` : '')),
            comments.length ? comments.map(([r, t], i) => {
              const c = r === rv ? (r.outcome === 'pass' ? chip('passed', '통과 판정') : chip('needs_decision', '미해결')) : chip('waiting', '이전 리뷰');
              return h('div', { class: 'comment', style: { borderColor: r === rv && r.outcome !== 'pass' ? 'rgba(235,192,122,0.4)' : C.line } },
                h('span', { class: 'mono muted', style: { fontSize: '12px', fontWeight: 600, marginTop: '2px' } }, 'R-' + (i + 1)),
                h('div', { style: { flex: 1, minWidth: 0, fontSize: '14px', lineHeight: 1.6 } }, t), chipEl(c, 'sm'));
            }) : h('div', { class: 'muted', style: { fontSize: '13px' } }, rv ? '코드 리뷰에서 남긴 의견이 없어요.' : '아직 코드 리뷰 기록이 없어요.'))),
        h('div', { class: 'sticky', style: { display: 'flex', flexDirection: 'column', gap: '14px' } },
          h('section', { class: 'box raised' }, h('h3', { style: { margin: 0, fontSize: '14.5px', fontWeight: 700 } }, '내 리뷰 판단'),
            accepted ? h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: '7px', color: C.mint, fontWeight: 700 } }, ic('usercheck', null, 16), '수락 기록됨')
              : [h('div', { role: 'radiogroup', 'aria-label': '리뷰 판단', style: { display: 'flex', flexDirection: 'column', gap: '6px' } },
                [['accept', '수락', '인수 기준을 충족했어요. 기능 리뷰 완료로 기록해요.'], ['changes', '수정 요청', '요구사항을 바꿔 새 기획 버전으로 다시 진행해요.'], ['hold', '보류', '판단을 미루고 메모만 남겨요.']].map(([k, l, desc]) =>
                  h('button', { class: 'radio' + (S.verdict === k ? ' on' : ''), role: 'radio', 'aria-checked': String(S.verdict === k), onclick: () => set({ verdict: k }) },
                    h('span', { class: 'radio-ring' }, h('span', { class: 'radio-dot' })),
                    h('span', { style: { display: 'flex', flexDirection: 'column', gap: '2px' } }, h('span', { style: { fontWeight: 700, fontSize: '13.5px' } }, l), h('span', { class: 'muted', style: { fontSize: '12.5px', lineHeight: 1.5 } }, desc))))),
              h('textarea', { class: 'textarea', 'data-k': 'memo', rows: 3, style: { minHeight: '72px' }, value: S.memo, placeholder: S.verdict === 'changes' ? '무엇을 고쳐야 하는지 적어 주세요' : '판단 근거: 읽은 파일, 확인한 동작, 결론', 'aria-label': '리뷰 메모', oninput: (e) => { S.memo = e.target.value; render(); } }),
              vNote ? h('span', { style: { fontSize: '12.5px', color: C.amber, lineHeight: 1.55 } }, vNote) : null,
              h('button', { class: 'btn block ' + (vOk ? 'primary' : 'disabled'), 'aria-disabled': vOk ? null : 'true', onclick: record }, ic('usercheck', vOk ? C.mintInk : '#6E8078', 16), '판단 기록'),
              h('span', { class: 'dim', style: { fontSize: '12px', lineHeight: 1.5 } }, '파일 읽음 표시는 참고용이에요. 판단 기록에는 필요하지 않아요.')]),
          h('section', { class: 'box' },
            h('div', { class: 'row-between' }, h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: '7px', fontWeight: 700, fontSize: '14px' } }, ic('pr', null, 16), 'Draft PR 준비'), h('span', { style: { fontSize: '12px', fontWeight: 600, color: C.text2, border: '1px solid #34423C', borderRadius: '5px', padding: '1px 7px' } }, 'Draft')),
            h('span', { style: { fontSize: '13.5px', color: '#D3E1DA' } }, d.stage === 'pr_ready' ? '검증 근거와 리뷰 자료(review-pack.md)가 준비됐어요.' : '최종 검증을 통과하면 PR 준비 상태가 돼요.'),
            h('div', { class: 'note muted', style: { fontSize: '12.5px' } }, ic('info', C.text3, 16), h('span', null, '한자리는 commit·push·PR 생성·병합을 하지 않아요. GitHub에서 직접 판단해요.')))))));
}

/* ------------------------------------------------------------------ activity */
function recoveries(d) {
  const out = [];
  if (S.conn === 'failed') out.push({ key: 'conn', icon: 'plug', title: '제어기 연결 끊김', chip: chip('failed', '연결 실패'), when: S.lastOk ? hm(S.lastOk.toISOString()) + '부터' : '', cause: '로컬 제어기가 응답하지 않아요. 앱은 마지막으로 받은 상태를 보여주고 있어요.', next: '한자리 창을 닫았다 다시 열거나, 아래 버튼으로 다시 연결하세요.', actions: [act(S.reconnecting ? '연결 중…' : '다시 연결', S.reconnecting ? 'loader' : 'refresh', 'primary', reconnect)] });
  if (!d) return out;
  const g = d.run_guard || {};
  if (g.latched) {
    const fix = !!S.fixed.guard;
    out.push({ key: 'guard', icon: 'shield', title: '반복 방지 작동' + (d.current_feature ? ' · ' + d.current_feature : ''), chip: chip('needs_decision', '사용자 판단 대기'),
      trail: [['제한 도달', 'done'], ['자동 실행 정지', 'done'], ['사용자 판단 대기', 'active']], cause: g.reason || '같은 단계의 반복 실패 또는 호출 한도에 도달했어요.',
      next: '원인을 해결한 뒤 이유를 남기고 제한을 해제하거나, 요구사항을 바꿔 수정 요청하세요. 원인을 그대로 두고 다시 실행하면 같은 실패가 반복될 가능성이 커요.',
      records: `기록: 전체 호출 ${g.attempts || 0}회 · 단계별 ${Object.entries(g.stage_attempts || {}).map(([k, v]) => `${k} ${v}회`).join(', ') || '없음'}`,
      fixLabel: '실패 원인을 확인하고 해결했어요', fixed: fix, fixKey: 'guard',
      actions: [fix ? textAct('reset-breaker', '반복 방지 해제…', 'shield', 'danger') : act('반복 방지 해제…', 'lock', 'disabled', null, '원인을 해결했다고 확인한 뒤 해제할 수 있어요.'), textAct('revise', '기획 수정 요청', 'edit')] });
  }
  const stuck = (d.receipts || []).filter((r) => r.status === 'needs_user');
  if (stuck.length) {
    const fix = !!S.fixed.notion;
    out.push({ key: 'notion', icon: 'xc', title: 'Notion 기록 전송 중단', chip: chip('failed'), cause: `문서 기록 ${stuck.length}건의 전송이 재시도 한도에 도달해 멈췄어요. 페이지 권한이 없거나 사람이 같은 기록을 편집했을 수 있어요.`,
      next: 'Notion에서 통합 연결과 페이지 공유를 확인한 뒤 재시도를 허용하고 동기화하세요. 로컬 산출물은 영향을 받지 않았어요.',
      fixLabel: 'Notion 페이지 권한·연결을 확인했어요', fixed: fix, fixKey: 'notion',
      actions: [fix ? textAct('retry-sync', '전송 재시도 허용…', 'refresh', 'primary') : act('전송 재시도 허용…', 'lock', 'disabled', null, '원인을 해결했다고 확인한 뒤 다시 시도할 수 있어요.'), allowed(d).has('sync') ? act('Notion 동기화', 'refresh', 'secondary', () => send('sync')) : null].filter(Boolean) });
  }
  const jobs = d.jobs || [];
  const last = jobs[jobs.length - 1];
  if (last && last.status === 'failed') {
    out.push({ key: 'job', icon: 'tri', title: `요청 실패 · ${NAMES[last.action] || last.action}`, chip: chip('failed'), when: when(last.finished_at),
      cause: `workspace.py가 종료 코드 ${last.exit_code ?? '알 수 없음'}로 끝났어요. 승인·사용량·Git·DB 사전 조건 중 하나가 막았을 수 있어요.`,
      next: '로그 파일에서 원인을 확인한 뒤 다시 시도하세요. 한자리는 실패한 요청을 자동으로 재실행하지 않아요.',
      actions: last.log_file ? [act('로그 파일 열기', 'file', 'secondary', () => openPath('log', last.log_file))] : [] });
  }
  return out;
}
function activityScreen(d, narrow) {
  const rec = recoveries(d);
  const filters = [['all', '전체'], ['run', '실행'], ['change', '변경'], ['approve', '승인'], ['request', '요청'], ['failed', '오류·실패']];
  const evs = allEvents(d).filter((e) => S.actFilter === 'all' || (S.actFilter === 'failed' ? e.st === 'failed' : e.kind === S.actFilter));
  const groups = [];
  for (const e of evs) { const label = dayLabel(e.at); let g = groups.find((x) => x.d === label); if (!g) groups.push(g = { d: label, items: [] }); g.items.push(e); }
  return h('div', { class: 'scroll', 'data-scroll': 'activity' },
    h('div', { class: 'page', style: { maxWidth: '1040px', padding: `24px ${narrow ? 24 : 32}px 60px`, gap: '22px' } },
      rec.length ? h('section', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } }, h('h2', { style: { margin: 0, fontSize: '15px', fontWeight: 700 } }, '복구가 필요한 항목'),
        rec.map((r) => h('div', { class: 'rec' },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' } }, h('span', { style: { color: r.chip.fg } }, ic(r.icon, r.chip.fg, 18)),
            h('span', { style: { fontWeight: 700, fontSize: '15px', whiteSpace: 'nowrap' } }, r.title), chipEl(r.chip, 'sm'), h('span', { style: { flex: 1 } }), h('span', { class: 'dim', style: { fontSize: '12.5px', whiteSpace: 'nowrap' } }, r.when || '')),
          r.trail ? h('ol', { class: 'trail' }, r.trail.map(([label, st], i, arr) => h('li', null,
            h('span', { class: 'trail-step', style: { fontSize: '12.5px', borderColor: st === 'active' ? 'rgba(235,192,122,0.5)' : C.line, background: st === 'active' ? C.amberSoft : 'transparent', color: st === 'active' ? C.amber : C.text2 } }, ic(st === 'done' ? 'check' : 'alert', st === 'done' ? C.text2 : C.amber, 14), label),
            i < arr.length - 1 ? ic('right', C.text3, 14) : null))) : null,
          h('div', { class: 'grid2', style: { gap: '12px' } },
            h('div', { style: { display: 'flex', flexDirection: 'column', gap: '4px' } }, h('span', { class: 'fact-k' }, '원인'), h('span', { style: { fontSize: '14px', lineHeight: 1.7, color: '#D3E1DA' } }, r.cause)),
            h('div', { style: { display: 'flex', flexDirection: 'column', gap: '4px' } }, h('span', { class: 'fact-k' }, '다음 조치'), h('span', { style: { fontSize: '14px', lineHeight: 1.7, color: '#D3E1DA' } }, r.next))),
          r.records ? h('div', { class: 'muted', style: { fontSize: '12.5px', display: 'flex', alignItems: 'center', gap: '7px' } }, ic('history', null, 14), r.records) : null,
          r.fixLabel ? h('label', { class: 'check-row', style: { alignItems: 'center' } }, h('input', { type: 'checkbox', checked: r.fixed, onchange: (e) => { S.fixed[r.fixKey] = e.target.checked; render(); } }), h('span', { style: { flex: 1 } }, r.fixLabel)) : null,
          r.actions.length ? h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap' } }, r.actions.map((a) => btn(a, 'md'))) : null))) : null,
      h('section', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } },
        h('div', { class: 'row-between', style: { gap: '12px', flexWrap: 'wrap' } }, h('h2', { style: { margin: 0, fontSize: '15px', fontWeight: 700 } }, '실행·변경·승인 기록'),
          h('div', { role: 'tablist', 'aria-label': '기록 필터', style: { display: 'flex', gap: '4px', flexWrap: 'wrap' } },
            filters.map(([k, l]) => h('button', { class: 'seg lg' + (S.actFilter === k ? ' on' : ''), role: 'tab', 'aria-selected': String(S.actFilter === k), onclick: () => set({ actFilter: k }) }, l)))),
        groups.length ? groups.map((g) => h('div', { style: { display: 'flex', flexDirection: 'column' } },
          h('div', { class: 'dim', style: { fontSize: '12.5px', fontWeight: 600, padding: '8px 0' } }, g.d),
          h('div', { class: 'ev-group' }, g.items.map((e, i) => {
            const key = e.at + e.title, op = S.actOpen === key;
            const c = e.st === 'processing' ? chip('processing') : e.st === 'failed' ? chip('failed') : chip('done', e.kind === 'approve' ? '기록됨' : '완료');
            return h('div', { style: { borderTop: i ? '1px solid #1F2925' : 'none' } },
              h('button', { class: 'ev-item', 'aria-expanded': String(op), onclick: () => set({ actOpen: op ? null : key }) },
                h('span', { class: 'mono dim', style: { fontSize: '12px', marginTop: '2px' } }, hm(e.at)),
                ic(KIND_IC[e.kind] || 'info', e.st === 'failed' ? C.coral : C.text2, 15),
                h('span', { style: { display: 'flex', flexDirection: 'column', gap: '2px', minWidth: 0 } }, h('span', { style: { fontSize: '14px' } }, h('span', { class: 'muted' }, `${e.actor} · `), e.title),
                  e.desc ? h('span', { class: 'muted', style: { fontSize: '13px', lineHeight: 1.6 } }, e.desc) : null),
                chipEl(c, 'sm'), h('span', { class: 'dim', style: { marginTop: '3px' } }, ic(op ? 'down' : 'right', null, 14))),
              op ? h('div', { style: { padding: '0 16px 14px 96px', display: 'flex', flexDirection: 'column', gap: '8px' } },
                h('pre', { class: 'log', style: { padding: '10px 12px', borderRadius: '8px' } }, e.raw.join('\n')),
                e.log ? h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' } }, h('button', { class: 'btn secondary xs', onclick: () => openPath('log', e.log) }, ic('file', null, 13), '로그 파일 열기'), h('span', { class: 'mono dim', style: { fontSize: '11.5px' } }, e.log)) : null) : null);
          })))) : h('div', { style: { padding: '24px', border: '1px dashed #34423C', borderRadius: '10px', color: C.text2, textAlign: 'center' } }, '이 조건에 해당하는 기록이 없어요.'))));
}

/* ------------------------------------------------------------------ add project / empty */
function emptyState(icon, title, body) {
  return h('div', { class: 'center' }, h('div', { style: { maxWidth: '440px', display: 'flex', flexDirection: 'column', gap: '12px', alignItems: 'flex-start' } },
    h('div', { class: 'empty-ic', style: { width: '40px', height: '40px', color: C.text3 } }, ic(icon, null, 16)),
    h('h2', { style: { margin: 0, fontSize: '19px', fontWeight: 700 } }, title), h('p', { class: 'muted', style: { margin: 0, lineHeight: 1.7 } }, body)));
}
function addProjectScreen() {
  const F = S.form;
  const field = (key, label, hint, picker) => h('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px' } },
    h('span', { class: 'form-label' }, label),
    h('div', { style: { display: 'flex', gap: '8px' } },
      h('input', { class: 'field', 'data-k': 'f-' + key, value: F[key], oninput: (e) => { F[key] = e.target.value; } }),
      picker ? h('button', { class: 'btn secondary sm', onclick: () => pick(key, picker) }, ic('folder', null, 15), '찾아보기') : null),
    hint ? h('span', { class: 'form-hint' }, hint) : null);
  return h('div', { class: 'scroll', 'data-scroll': 'add' }, h('div', { style: { maxWidth: '620px', margin: '0 auto', padding: '40px 32px 60px', display: 'flex', flexDirection: 'column', gap: '18px' } },
    h('div', { class: 'empty-ic', style: { color: C.mint } }, ic('folder', null, 16)),
    h('h2', { style: { margin: 0, fontSize: '22px', fontWeight: 700, letterSpacing: '-0.01em' } }, '관리할 프로젝트를 추가하세요'),
    h('p', { class: 'muted', style: { margin: 0, fontSize: '14.5px', lineHeight: 1.7 } }, '한자리는 프로젝트 폴더 하나를 기준으로 기획, 개발, 리뷰 기록을 관리해요. 추가만으로는 아무 작업도 시작하지 않아요.'),
    h('ol', { class: 'steps' },
      [['프로젝트 폴더 선택', 'GitHub origin이 설정된 Git 저장소 폴더를 고르세요.'], ['첫 아이디어 기록', '만들고 싶은 것을 몇 문장으로 적어 주세요.'], ['실행', '실행을 누르면 Codex가 기획 작성을 시작해요.']].map(([t, s], i) =>
        h('li', null, h('span', { class: 'step-n' }, String(i + 1)), h('span', null, h('strong', null, t), h('br'), h('span', { class: 'muted', style: { fontSize: '13px' } }, s))))),
    field('title', '프로젝트 이름'), field('project', '고유 ID', '영문 소문자로 시작하는 2~40자 (예: bookshelf)'),
    field('repo', '로컬 저장소 폴더', null, 'folder'), field('record_page', 'Notion 결과 페이지', '링크 또는 페이지 ID'),
    field('source_page', 'Notion 원본 페이지 (선택)', '결과 페이지와 달라야 해요'), field('prefix', '업무 접두사', '대문자 2~12자 (예: BK)'),
    field('baseline', '기존 테스트 기준 JSON (선택)', '기존 테스트가 있는 저장소는 필요해요', 'json'),
    h('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px' } }, h('span', { class: 'form-label' }, '아이디어 · 해결할 문제와 원하는 기능'),
      h('textarea', { class: 'textarea', 'data-k': 'f-idea', rows: 4, value: F.idea, oninput: (e) => { F.idea = e.target.value; } })),
    h('div', { class: 'info-box' }, ic('info', C.text3, 16), h('span', null, '현재 자동 개발 지원: Java / Spring Boot / Maven / MySQL. Notion 접근 권한은 실행·동기화 때 확인해요. 저장소·Notion 페이지를 새로 만들지는 않아요.')),
    S.formError ? h('div', { style: { color: C.amber, fontSize: '13px' } }, S.formError) : null,
    h('div', { style: { display: 'flex', gap: '8px' } },
      btn(act(S.sending ? '등록 중…' : '프로젝트 등록', S.sending ? 'loader' : 'check', S.sending ? 'disabled' : 'primary', registerProject, '등록 중이에요')),
      btn(act('기존 프로젝트로 돌아가기', null, 'secondary', () => set({ adding: false }))))));
}
async function pick(key, kind) {
  try { const r = await api('/api/pick', { kind }); if (r.path) { S.form[key] = r.path; render(); } } catch (e) { toast(e.message, 'warn'); }
}
async function registerProject() {
  if (S.sending) return;
  S.sending = true; S.formError = ''; render();
  try {
    const r = await api('/api/register', S.form);
    await connect();
    S.form = { title: '', project: '', repo: '', record_page: '', source_page: '', prefix: 'TASK', baseline: '', idea: '' };
    switchProject(r.id);
    toast('프로젝트를 추가했어요. AI는 아직 실행하지 않았어요. 진행 현황에서 실행을 눌러 기획을 시작하세요.');
  } catch (e) { S.formError = '등록하지 못했어요: ' + e.message; } finally { S.sending = false; render(); }
}
async function loadSettings() {
  const project = S.project;
  S.settingsLoading = true; S.settingsError = ''; render();
  try {
    const value = await api('/api/settings', { project });
    if (S.project === project) S.settings = value;
  } catch (e) { if (S.project === project) S.settingsError = e.message; }
  finally { if (S.project === project) { S.settingsLoading = false; render(); } }
}
function settingsScreen(d) {
  const value = S.settings && S.settings.project === S.project ? S.settings : null;
  return h('div', { class: 'screen settings-screen', 'data-scroll': 'settings', style: { display: 'flex', flexDirection: 'column', gap: '20px' } },
    h('section', { class: 'card' }, h('h2', null, '프로젝트 설정'),
      h('p', { class: 'muted' }, '이 프로젝트를 실행하기 전에 필요한 연결 정보와 설정 파일을 관리하세요.'),
      h('div', { class: 'row-between' }, h('span', { class: 'mono' }, value ? value.folder : '설정 정보를 불러오는 중…'),
        h('button', { class: 'btn secondary', onclick: loadSettings }, '새로고침')),
      S.settingsError ? h('p', { role: 'alert', style: { color: C.coral } }, S.settingsError) : null),
    h('section', { class: 'card' }, h('div', { class: 'row-between' }, h('h3', null, '테스트 DB 연결'),
      h('span', { class: 'meta' }, value ? value.database_error || (value.database_saved ? '암호화 저장됨 · 접속 미검증' : '앱에 저장된 설정 없음') : '확인 중')),
      h('p', { class: 'muted' }, '프로젝트별 주소·계정·비밀번호를 Windows 사용자 계정으로 암호화해 저장합니다.'),
      h('button', { class: 'btn primary', onclick: openDatabase }, 'DB 연결 설정')),
    h('section', { class: 'card' }, h('h3', null, '설정 파일'),
      h('p', { class: 'muted' }, '파일은 VS Code 또는 메모장에서 엽니다. 수정 후 저장하고 다시 실행하세요. .env는 프로젝트가 직접 읽도록 구성되어 있어야 하며, 한자리가 자동으로 불러오지는 않습니다.'),
      value ? value.files.map(f => h('div', { class: 'row-between', style: { padding: '12px 0', borderBottom: '1px solid ' + C.line, gap: '12px' } },
        h('div', null, h('div', { class: 'mono' }, f.path), h('span', { class: 'meta' }, f.kind + ' · ' + (f.exists ? '파일 있음' : '파일 없음'))),
        h('button', { class: 'btn secondary sm', disabled: !f.exists, onclick: () => f.exists && openPath('file', f.path) }, '파일 열기'))) : h('p', null, S.settingsLoading ? '파일 목록 확인 중…' : '새로고침으로 목록을 확인하세요.')));
}
async function openDatabase() {
  const project = S.project;
  try {
    const value = await api('/api/db-settings', { project, operation: 'read' });
    if (project !== S.project) return;
    S.db = { project, saved: value.saved, url: value.url || ('jdbc:mysql://localhost:3306/' + (project === 'hanjari' ? 'hanjari_test' : project.replace(/-/g, '_') + '_test') + '?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8'), username: value.username || '', password: '' };
    set({ modal: 'database' });
  } catch (e) { toast(e.message, 'warn'); }
}
async function saveDatabase(operation) {
  if (S.dbSaving) return;
  const db = S.db;
  S.dbSaving = true; render();
  try {
    await api('/api/db-settings', { ...db, operation });
    db.password = ''; S.modal = null; loadSettings();
    toast(operation === 'delete' ? '저장된 DB 연결 정보를 삭제했어요.' : 'DB 연결 정보를 암호화해 저장했어요. 다음 실행부터 적용돼요.');
  } catch (e) { toast(e.message, 'warn'); }
  finally { S.dbSaving = false; render(); }
}
async function openPath(kind, path) { try { await api('/api/open', { kind, path, project: S.project }); } catch (e) { toast(e.message, 'warn'); } }
function copy(text) { navigator.clipboard.writeText(text).then(() => toast('경로를 복사했어요.'), () => toast('복사하지 못했어요.', 'warn')); }
async function reconnect() {
  if (S.reconnecting) return;
  set({ reconnecting: true }); S.token = null;
  await poll();
  set({ reconnecting: false });
  toast(S.conn === 'ok' ? '제어기에 다시 연결했어요. 상태를 새로 불러왔어요.' : '아직 연결할 수 없어요. 한자리 창을 다시 열어 주세요.', S.conn === 'ok' ? 'ok' : 'warn');
}

/* ------------------------------------------------------------------ dialogs */
function dialog(d) {
  if (!S.modal || !d) return null;
  const close = () => { if (S.db) S.db.password = ''; set({ modal: null }); };
  const cancel = act('취소', null, 'secondary', close);
  const cur = curFeature(d);
  const text = S.modalText.trim();
  const input = (placeholder, hint, hintColor) => [
    h('textarea', { class: 'textarea', 'data-k': 'modal', rows: 4, value: S.modalText, placeholder, 'aria-label': '내용', oninput: (e) => { S.modalText = e.target.value; render(); } }),
    h('span', { style: { fontSize: '12px', color: hintColor || C.text3 } }, hint)];
  const submit = (action, extra, label, icon, kind) => async () => { const ok = await send(action, extra); if (ok) { S.modal = null; S.changeText = action === 'revise' ? '' : S.changeText; if (action === 'accept-feature' || (action === 'revise' && S.verdict)) { S.verdict = null; S.memo = ''; } render(); } };
  let x = null;
  switch (S.modal) {
    case 'database': {
      const db = S.db;
      if (!db) return null;
      const field = (key, label, type = 'text') => h('label', { class: 'form-field' },
        h('span', null, label), h('input', { class: 'input', type, value: db[key], autocomplete: 'off',
          'data-k': 'db-' + key, oninput: e => { db[key] = e.target.value; } }));
      x = { icon: 'plug', color: C.mint, title: projectName(S.projects.find(p => p.id === db.project)) + ' · 테스트 DB',
        body: '준비한 테스트 전용 DB의 연결 정보를 입력하세요. 저장 후 다음 자동화 실행부터 적용됩니다.',
        input: [field('url', 'JDBC 주소'), field('username', 'DB 계정'), field('password', db.saved ? '비밀번호 · 비워두면 기존 값 유지' : '비밀번호', 'password'),
          h('p', { class: 'muted' }, '이 Windows 사용자 계정으로 암호화해 저장합니다. 저장은 실제 DB 접속 확인이나 DB 생성을 수행하지 않습니다.')],
        actions: [cancel, db.saved ? act('저장 정보 삭제', 'trash', S.dbSaving ? 'disabled' : 'danger', () => saveDatabase('delete')) : null,
          act(S.dbSaving ? '저장 중…' : '암호화 저장', 'check', S.dbSaving ? 'disabled' : 'primary', () => saveDatabase('save'))].filter(Boolean) };
      break;
    }

    case 'stop':
      x = { icon: 'pause', color: C.amber, title: '중단을 요청할까요?', body: '지금 하고 있는 단계를 마친 뒤 멈춰요. 진행 중인 단계를 즉시 끊지는 않아요.',
        facts: [['현재 단계', STAGE_NAME[d.stage] || d.stage], ['담당', d.stage === 'implement' ? 'Claude Code' : 'Codex'], ['멈추는 시점', '현재 단계 완료 후']],
        actions: [cancel, act('중단 요청', 'pause', 'primary', submit('pause'))] };
      break;
    case 'approve': {
      const p = S.pinned || {};
      x = { icon: 'usercheck', color: C.mint, title: `기획안 r${p.rev} 전체를 승인할까요?`, body: '정확히 이 버전에 대한 승인으로 기록돼요.',
        facts: [['승인 대상', `기획안 r${p.rev}`], ['해시', String(p.hash || '').slice(0, 16) + '…', 'mono'], ['사용자 확정', `${(d.confirmed_constraints || []).length}건`], ['AI 판단·제안', `${(d.proposals || []).length}건 함께 확정`], ['기능', `${featureList(d).length}개`]],
        note: '승인해도 개발은 자동으로 시작하지 않아요. 진행 현황에서 ‘개발 실행’을 눌러야 시작돼요.',
        actions: [cancel, act(`r${p.rev} 승인`, 'usercheck', 'primary', submit('approve', { hash: p.hash }))] };
      break;
    }
    case 'note':
      x = { icon: 'bulb', color: C.mint, title: '아이디어 기록', body: '떠오른 생각을 기록해 둬요. 기록만 하고 아무 작업도 실행하지 않아요. 확정 범위를 바꾸려면 수정 요청을 사용하세요.',
        input: input('예: 상영 30분 전 알림을 나중에 추가하고 싶어요.', `${S.modalText.length}자`),
        actions: [cancel, text ? act('기록', 'check', 'primary', submit('note', { text })) : act('기록', 'check', 'disabled', null, '내용을 입력해 주세요.')] };
      break;
    case 'revise':
    case 'reviseConfirm':
      x = { icon: 'edit', color: C.amber, title: '수정 요청을 보낼까요?', body: '요청 내용으로 새 기획 버전을 만들어요. 기존 전체 승인과 기능 인계는 해제되고 기록은 보존돼요. 새 버전은 실행 버튼을 눌러야 작성이 시작돼요.',
        input: S.modal === 'revise' ? input('변경할 요구사항이나 질문에 대한 답을 적어 주세요.', `${S.modalText.length}자`) : null,
        facts: S.modal === 'reviseConfirm' ? [['요청 내용', clip(S.modalText, 200)]] : null,
        actions: [cancel, text ? act('수정 요청 보내기', 'send', 'primary', submit('revise', { text })) : act('수정 요청 보내기', 'send', 'disabled', null, '내용을 입력해 주세요.')] };
      break;
    case 'accept':
      x = { icon: 'usercheck', color: C.mint, title: `${d.current_feature} 기능 리뷰를 수락할까요?`, body: '기능 리뷰 완료로 기록해요. PR을 병합하거나 병합됐다고 기록하지 않아요.',
        facts: [['기능', `${d.current_feature} ${cur ? cur.title : ''}`], ['리뷰 메모', clip(S.modalText, 160)]],
        note: '다음 기능의 첫 실행은 깨끗한 작업 폴더가 필요해요. 이번 변경은 먼저 직접 커밋해 보존하세요.',
        actions: [cancel, act('수락 기록', 'usercheck', 'primary', submit('accept-feature', { feature: d.current_feature, text }))] };
      break;
    case 'reset-breaker': {
      const ok = text.length >= 10;
      x = { icon: 'shield', color: C.coral, title: '반복 방지를 해제할까요?', body: '같은 원인으로 실패가 반복돼 멈춘 상태예요. 해제하면 다음 실행에서 다시 시도해요. 이유는 활동 기록에 남아요.',
        input: input('예: 로컬 DB 잠금은 백신 실시간 검사 때문이었고, 예외 폴더를 추가했어요.', ok ? '기록 가능' : `이유를 10자 이상 적어 주세요 (${text.length}/10)`, ok ? C.mint : C.amber),
        actions: [cancel, ok ? act('해제 기록', 'shield', 'danger', submit('reset-breaker', { text })) : act('해제 기록', 'lock', 'disabled', null, '이유를 10자 이상 적어 주세요.')] };
      break;
    }
    case 'retry-sync':
      x = { icon: 'refresh', color: C.mint, title: '전송 재시도를 허용할까요?', body: '멈춘 Notion 기록 전송을 다시 시도할 수 있게 해요. 허용 후 Notion 동기화를 눌러 전송하세요. AI는 실행하지 않아요.',
        input: input('예: Notion에서 한자리 통합을 결과 페이지에 다시 연결했어요.', `${S.modalText.length}자`),
        actions: [cancel, text ? act('재시도 허용', 'refresh', 'primary', submit('retry-sync', { text })) : act('재시도 허용', 'lock', 'disabled', null, '조치 내용을 입력해 주세요.')] };
      break;
  }
  if (!x) return null;
  return h('div', { class: 'overlay', onclick: close },
    h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', onclick: (e) => e.stopPropagation() },
      h('div', { style: { display: 'flex', alignItems: 'flex-start', gap: '12px' } }, h('span', { style: { marginTop: '2px', color: x.color } }, ic(x.icon, x.color, 20)),
        h('div', { style: { flex: 1, display: 'flex', flexDirection: 'column', gap: '6px' } }, h('h2', null, x.title), h('p', null, x.body))),
      x.facts ? h('div', { class: 'facts' }, x.facts.map(([k, v, cls]) => h('div', { class: 'fact' }, h('span', { class: 'dim' }, k), h('span', { class: cls || '', style: { color: C.text } }, v)))) : null,
      x.input ? h('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px' } }, x.input) : null,
      x.note ? h('div', { class: 'note', style: { padding: '10px 12px', borderRadius: '8px', background: '#141B18', color: '#C9D8D1' } }, ic('info', C.text3, 16), h('span', null, x.note)) : null,
      h('div', { style: { display: 'flex', justifyContent: 'flex-end', gap: '8px', flexWrap: 'wrap', marginTop: '2px' } }, x.actions.map((a) => btn(a)))));
}

/* ------------------------------------------------------------------ root */
function paint() {
  const d = S.data, sc = scenario(d), narrow = S.w < 1280;
  const cur = S.projects.find((p) => p.id === S.project);
  const name = projectName(cur);
  document.title = '한자리 — ' + (S.adding ? '프로젝트 추가' : name);
  const hero = d ? heroData(d, sc) : null;
  const gChip = S.adding ? chip('notstarted', '등록') : hero ? hero.chip : chip('waiting', S.conn === 'failed' ? '연결 끊김' : '불러오는 중');
  const can = allowed(d);
  let body;
  if (S.adding) body = addProjectScreen();
  else if (!d) body = S.conn === 'failed' ? emptyState('plug', '제어기에 연결할 수 없어요', '한자리 창을 닫았다가 다시 열어 주세요. 문제가 계속되면 automation/config.json을 확인하세요.') : h('div', { class: 'boot' }, '상태를 불러오는 중…');
  else if (S.screen === 'overview') body = overview(d, sc, narrow);
  else body = { plan: planScreen, features: featuresScreen, review: reviewScreen, activity: activityScreen, settings: settingsScreen }[S.screen](d, narrow);
  const tree = h('div', { class: 'root', onclick: (e) => { if (S.projOpen && !e.target.closest('.proj-wrap')) set({ projOpen: false }); } },
    h('div', { class: 'body' },
      sidebar(d, sc, narrow),
      h('main', { class: 'main' },
        h('header', { class: 'header', style: { padding: `0 ${narrow ? 24 : 32}px` } },
          h('div', { class: 'crumb' }, h('span', { class: 'crumb-proj' }, S.adding ? '한자리' : name), h('span', { style: { color: '#4E605A' } }, ic('right', null, 14)),
            h('h1', null, S.adding ? '프로젝트 추가' : TITLES[S.screen]), h('span', { style: { marginLeft: '8px' } }, chipEl(gChip)),
            d && d.mode === 'mock' ? chipEl(chip('unknown', '모의 실행')) : null),
          d && !S.adding ? h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px' } },
            h('button', { class: 'btn ghost', onclick: () => can.has('note') ? openModal('note') : toast(disabledWhy('note'), 'warn'), style: { opacity: can.has('note') ? 1 : 0.5 } }, ic('bulb', null, 15), '아이디어 기록'),
            h('button', { class: 'btn ghost', 'aria-disabled': can.has('sync') ? null : 'true', style: { opacity: can.has('sync') ? 1 : 0.5 }, onclick: () => can.has('sync') ? send('sync') : toast(disabledWhy('sync'), 'warn') },
              ic((d.jobs || []).some((j) => j.action === 'sync' && j.status === 'running') ? 'loader' : 'refresh', null, 15, (d.jobs || []).some((j) => j.action === 'sync' && j.status === 'running')), 'Notion 동기화')) : null),
        S.conn === 'failed' ? h('div', { class: 'banner err', role: 'alert', style: { padding: `10px ${narrow ? 24 : 32}px` } },
          h('span', { style: { color: C.coral } }, ic('plug', C.coral, 16)),
          h('span', { style: { flex: 1, minWidth: 0 } }, h('strong', { style: { color: C.coral } }, S.reconnecting ? '다시 연결하는 중이에요.' : '제어기에 연결할 수 없어요.'),
            h('span', { style: { color: '#C9D8D1' } }, ` 표시된 상태는 ${S.lastOk ? hm(S.lastOk.toISOString()) : '—'} 기준이에요. 연결될 때까지 실행·승인·동기화를 막았어요.`)),
          h('button', { class: 'btn secondary sm', onclick: reconnect }, ic(S.reconnecting ? 'loader' : 'refresh', null, 15, S.reconnecting), S.reconnecting ? '연결 중…' : '다시 연결')) : null,
        h('div', { class: 'content' }, body))),
    dialog(d),
    S.toast ? h('div', { class: 'toast', role: 'status', 'aria-live': 'polite' }, ic(S.toast.kind === 'warn' ? 'alert' : 'check', S.toast.kind === 'warn' ? C.amber : C.mint, 16), h('span', null, S.toast.msg)) : null);
  const app = document.getElementById('app');
  if (app.firstChild && app.firstChild.classList && app.firstChild.classList.contains('root')) morph(app.firstChild, tree);
  else { app.textContent = ''; app.appendChild(tree); }
}

/* ------------------------------------------------------------------ boot */
window.addEventListener('resize', () => { const narrow = window.innerWidth < 1280; if (narrow !== (S.w < 1280)) { S.w = window.innerWidth; render(); } else S.w = window.innerWidth; });
window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { if (S.modal) { if (S.db) S.db.password = ''; set({ modal: null }); } else if (S.projOpen) set({ projOpen: false }); }
  if (e.ctrlKey && !e.shiftKey && !e.altKey && /^[1-6]$/.test(e.key)) { e.preventDefault(); S.adding = false; go(NAV[+e.key - 1][0]); }
  if (e.ctrlKey && e.key.toLowerCase() === 'f' && S.screen === 'plan' && !S.adding) { e.preventDefault(); const el = document.querySelector('[data-k="planq"]'); if (el) el.focus(); }
  if (e.key === 'F5') { e.preventDefault(); poll(); toast('상태를 새로 불러왔어요.'); }
});
// Closing the window never kills running work; warn because the window is the only control surface.
window.addEventListener('beforeunload', (e) => { if (busy(S.data)) { e.preventDefault(); e.returnValue = ''; } });
if (TITLES[location.hash.slice(1)]) S.screen = location.hash.slice(1);
render();
poll();
setInterval(poll, 3000);
setInterval(() => { if (S.data && S.data.usage) render(); }, 60000);
