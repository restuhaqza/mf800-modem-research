// Capture screenshot UI modem MF800 — sebelum (mirror offline) & sesudah (live)
// Pakai: node capture-ui.mjs   (butuh Chrome + Node >= 21 untuk WebSocket global)
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT   = 9223;
const OUT    = fileURLToPath(new URL('./shots/', import.meta.url));
const LOCAL  = 'http://127.0.0.1:8899/';
const LIVE   = 'http://192.168.100.1/';

mkdirSync(OUT, { recursive: true });
const sleep = ms => new Promise(r => setTimeout(r, ms));

const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`,
  '--user-data-dir=/tmp/chrome-mf800', '--no-first-run', '--no-default-browser-check',
  '--hide-scrollbars', '--force-device-scale-factor=1', '--window-size=1280,800',
  '--disable-gpu', 'about:blank',
], { stdio: 'ignore' });

// tunggu CDP siap
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
  const i = ++seq; pending.set(i, res);
  ws.send(JSON.stringify({ id: i, method, params }));
});
const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  return r.result?.result?.value;
};
const shot = async name => {
  const r = await send('Page.captureScreenshot', { format: 'jpeg', quality: 82 });
  if (!r.result?.data) {
    console.error('✗', name, '→ CDP error:', JSON.stringify(r.error ?? r).slice(0, 300));
    return false;
  }
  writeFileSync(OUT + name + '.jpg', Buffer.from(r.result.data, 'base64'));
  console.log('✓', name);
  return true;
};
const nav = async url => {
  await send('Page.navigate', { url });
  for (let i = 0; i < 40; i++) {          // tunggu document siap (maks ~10 dtk)
    await sleep(250);
    if (String(await evalJs('document.readyState')) === 'complete') break;
  }
};

await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false });
const SKIP_BEFORE = process.argv.includes('--after-only');
const SKIP_AFTER  = process.argv.includes('--before-only');

// ---------- SEBELUM (mirror offline, index/main asli dari APK) ----------
if (!SKIP_BEFORE) {
  await nav(LOCAL + 'index.html'); await sleep(2600);   // index asli reload sekali untuk cookie bahasa
  await shot('before-login');
  await evalJs(`sessionStorage.setItem('conn_mode','1');sessionStorage.setItem('net_mode','0');` +
              `sessionStorage.setItem('imei','000000000000000');sessionStorage.setItem('fwversion','V3.3.3'); 1`);
  await nav(LOCAL + 'main.html'); await sleep(3000);
  await shot('before-home');
}

// ---------- SESUDAH (live modem, UI hasil modifikasi) ----------
if (!SKIP_AFTER) {
await nav(LIVE + 'index.html'); await sleep(2600);
await evalJs(`try{localStorage.setItem('mifi_pin','admin')}catch(e){} 1`);
await shot('after-login');

// login nyata → redirect ke main.html
await evalJs(`(function(){var u=document.querySelector('#username'),p=document.querySelector('#password');` +
            `if(u)u.value='admin';if(p)p.value='admin';if(typeof login==='function')login();})()`);
let redirected = false;
for (let i = 0; i < 20; i++) {
  await sleep(400);
  if (String(await evalJs('location.pathname')).includes('main.html')) { redirected = true; break; }
}
await sleep(redirected ? 2800 : 400);
if (!redirected) { await nav(LIVE + 'main.html'); await sleep(3200); }
// sembunyikan nilai password Wi-Fi (jangan diproyeksikan) — termasuk di dalam iframe status
await evalJs(`(function(){var n=0,docs=[document];` +
            `var f=document.getElementById('mainifr');if(f&&f.contentDocument)docs.push(f.contentDocument);` +
            `docs.forEach(function(d){try{d.querySelectorAll('td,th,div,span,label').forEach(function(e){` +
            `if(!e.children.length&&/^\\s*kamurindu\\s*$/.test(e.textContent)){e.textContent='\\u2022\\u2022\\u2022\\u2022\\u2022\\u2022\\u2022\\u2022';n++;}});}catch(x){}});` +
            `return n;})()`);
await sleep(300);
await shot('after-home');

// SMS (butuh PIN di localStorage — sudah diset di atas)
await nav(LIVE + 'sms.html'); await sleep(4800);
await shot('after-sms');

// Terminal (PTY via WebSocket ke :8080)
await nav(LIVE + 'terminal.html'); await sleep(6500);
await shot('after-terminal');
}

chrome.kill();
console.log('selesai →', OUT);
process.exit(0);
