// Functional smoke test of a generated single-file app, in jsdom (no browser needed).
// Usage: node app-smoke-test.js <calculator|todo|music-player> <page.html>
// Prints one JSON line: {"ok": bool, "detail": "...", "errors": [...]}
const fs = require('fs');
const { JSDOM, VirtualConsole } = require('jsdom');

const [kind, file] = process.argv.slice(2);
const html = fs.readFileSync(file, 'utf8');
const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', (e) => {
  const msg = String((e && e.message) || e);
  if (!msg.startsWith('Not implemented')) errors.push(msg.slice(0, 200));
});

const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  url: 'https://appassets.androidplatform.net/index.html',
  virtualConsole: vc,
  beforeParse(w) {
    w.alert = () => {};
    w.confirm = () => true;
    w.prompt = () => '';
    w.HTMLMediaElement.prototype.play = () => Promise.resolve();
    w.HTMLMediaElement.prototype.pause = () => {};
    w.HTMLMediaElement.prototype.load = () => {};
  },
});
const w = dom.window;
const d = w.document;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const norm = (s) => (s || '').replace(/\s+/g, '').replace(/×/g, '*').replace(/÷/g, '/').replace(/−/g, '-');

function find(label) {
  const want = norm(label);
  const controls = [...d.querySelectorAll('button, input[type=button], input[type=submit]')];
  const hit = controls.find((e) => norm(e.textContent) === want || norm(e.value) === want);
  if (hit) return hit;
  return [...d.querySelectorAll('[onclick], [role=button], [data-value], td, span, div, a')]
    .find((e) => e.children.length === 0 && (norm(e.textContent) === want || norm(e.getAttribute('data-value')) === want));
}

async function calculator() {
  for (const k of ['7', '+', '8', '=']) {
    const e = find(k);
    if (!e) return { ok: false, detail: `no "${k}" button` };
    e.click();
    await wait(30);
  }
  const shown = [...d.querySelectorAll('input, output, textarea')].map((e) => e.value)
    .concat([...d.querySelectorAll('body *')].filter((e) => e.children.length === 0).map((e) => e.textContent.trim()));
  const ok = shown.some((t) => t === '15' || t === '15.0');
  return { ok, detail: ok ? 'tapping 7 + 8 = shows 15' : `after tapping 7 + 8 = the screen shows: ${shown.filter(Boolean).slice(0, 30).join(' / ').slice(0, 300)}` };
}

async function todo() {
  const item = 'Buy milk CI';
  const input = d.querySelector('input[type=text], input:not([type]), input[type=search], textarea');
  if (!input) return { ok: false, detail: 'no text box to type a task' };
  const listed = () => [...d.querySelectorAll('body *')].some((e) => e !== input && e.tagName !== 'SCRIPT' && e.textContent.includes(item));
  input.value = item;
  input.dispatchEvent(new w.Event('input', { bubbles: true }));
  const add = [...d.querySelectorAll('button, input[type=submit], input[type=button]')]
    .find((b) => /add|\+|save|create|submit/i.test(`${b.textContent} ${b.value || ''} ${b.id} ${b.className}`)) || d.querySelector('button');
  if (add) add.click();
  await wait(50);
  if (!listed()) {
    for (const type of ['keydown', 'keypress', 'keyup']) {
      input.dispatchEvent(new w.KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    }
    const form = input.closest('form');
    if (form) form.dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
    await wait(50);
  }
  const ok = listed();
  return { ok, detail: ok ? 'typing a task and tapping Add puts it in the list' : 'a typed task never appeared in the list' };
}

async function musicPlayer() {
  const problems = [];
  if (!d.querySelector('input[type=file]')) problems.push('no way to choose songs (no file picker)');
  if (!d.querySelector('audio') && !/new\s+Audio\s*\(/.test(html)) problems.push('no audio player');
  const play = d.querySelector('audio[controls]') || find('Play') || find('▶') ||
    [...d.querySelectorAll('button')].find((b) => /play/i.test(`${b.textContent} ${b.id} ${b.className}`));
  if (!play) problems.push('no play button');
  return { ok: problems.length === 0, detail: problems.length ? problems.join('; ') : 'file picker, audio player and play button present' };
}

(async () => {
  await wait(200); // DOMContentLoaded / load handlers
  let result;
  try {
    result = await ({ calculator, todo, 'music-player': musicPlayer }[kind] || (async () => ({ ok: true, detail: 'no check for this app' })))();
  } catch (e) {
    result = { ok: false, detail: `the check crashed: ${e}` };
  }
  if (errors.length) result = { ok: false, detail: `${result.detail}; script errors on the page` };
  console.log(JSON.stringify({ ...result, errors }));
  process.exit(0);
})();
