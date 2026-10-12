// Render semua slide jadi gambar untuk review visual.
// Pakai: node review-slides.mjs   (server statis harus jalan di :8901)
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9224, W = 1600, H = 900;
const OUT = fileURLToPath(new URL('./review/', import.meta.url));
const URL_BASE = 'http://127.0.0.1:8901/ngoprek-modem.html';
const TOTAL = 15;

mkdirSync(OUT, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));

const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`,
  '--user-data-dir=/tmp/chrome-review', '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--force-device-scale-factor=1', `--window-size=${W},${H}`,
  '--disable-gpu', 'about:blank',
], { stdio: 'ignore' });

let list = [];
for (let i = 0; i < 50 && !list.length; i++) {
  await sleep(300);
  try { list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json(); } catch {}
}
const target = list.find(t => t.type === 'page');
if (!target) { console.error('CDP target tidak ketemu'); process.exit(1); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pending = new Map();
ws.onmessage = ev => {
  const m = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString());
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
const send = (method, params = {}) => new Promise(res => {
  const i = ++seq; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params }));
});

await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });

for (let n = 1; n <= TOTAL; n++) {
  await send('Page.navigate', { url: `${URL_BASE}#${n}` });
  await sleep(1600);                         // hormati animasi reveal (500ms + stagger)
  const r = await send('Page.captureScreenshot', { format: 'jpeg', quality: 72 });
  if (!r.result?.data) { console.error('✗ slide', n, JSON.stringify(r.error ?? r).slice(0, 200)); continue; }
  writeFileSync(`${OUT}slide-${String(n).padStart(2, '0')}.jpg`, Buffer.from(r.result.data, 'base64'));
  console.log('✓', 'slide-' + String(n).padStart(2, '0'));
}
chrome.kill();
process.exit(0);
