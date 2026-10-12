// Audit geometri: deteksi konten yang overflow / keluar batas slide.
// Pakai: node audit-layout.mjs   (server statis di :8901)
import { spawn } from 'node:child_process';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9225, W = 1600, H = 900;
const URL_BASE = 'http://127.0.0.1:8901/ngoprek-modem.html';
const TOTAL = 15;

const sleep = ms => new Promise(r => setTimeout(r, ms));
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`,
  '--user-data-dir=/tmp/chrome-audit', '--no-first-run', '--hide-scrollbars',
  '--force-device-scale-factor=1', `--window-size=${W},${H}`, '--disable-gpu', 'about:blank',
], { stdio: 'ignore' });

let list = [];
for (let i = 0; i < 50 && !list.length; i++) {
  await sleep(300);
  try { list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json(); } catch {}
}
const ws = new WebSocket(list.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pending = new Map();
ws.onmessage = ev => {
  const m = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString());
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
const send = (m, p = {}) => new Promise(res => { const i = ++seq; pending.set(i, res); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
const evaluate = async expr => (await send('Runtime.evaluate', { expression: expr, returnByValue: true })).result?.result?.value;

await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });

const probe = `(() => {
  const s = document.querySelector('.slide.active'); if(!s) return null;
  const inner = s.querySelector('.inner');
  const ir = inner.getBoundingClientRect();
  const kids = [...inner.children].map(c => {
    const r = c.getBoundingClientRect();
    return { el: c.tagName.toLowerCase() + (c.className ? '.' + String(c.className).trim().split(/\\s+/)[0] : ''),
             top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height) };
  });
  return { title: s.dataset.title,
           slideH: Math.round(s.getBoundingClientRect().height),
           scrollH: s.scrollHeight, clientH: s.clientHeight,
           innerTop: Math.round(ir.top), innerBottom: Math.round(ir.bottom), innerH: Math.round(ir.height),
           kids };
})()`;

for (let n = 1; n <= TOTAL; n++) {
  await send('Page.navigate', { url: `${URL_BASE}#${n}` });
  await sleep(1500);
  const r = await evaluate(probe);
  if (!r) { console.log(`#${n}: (gagal probe)`); continue; }
  const overflow = r.scrollH - r.clientH;
  const offscreen = r.kids.filter(k => k.bottom > r.slideH - 8 || k.top < 8);
  const flag = overflow > 4 ? `  ⚠ OVERFLOW ${overflow}px` : '';
  console.log(`#${String(n).padStart(2)} ${r.title} — slide ${r.slideH}px, konten ${r.innerH}px (top ${r.innerTop} → bottom ${r.innerBottom})${flag}`);
  for (const k of offscreen) console.log(`      ⤷ ${k.el}: top ${k.top} bottom ${k.bottom} h ${k.h}`);
}
chrome.kill();
process.exit(0);
