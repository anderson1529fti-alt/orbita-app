// Pruebas en el emulador: instala la app, le pone música, la maneja (por dentro, con las herramientas de depuración
// del WebView, y por fuera, con los botones de Android) y guarda capturas y registros en salida/.
import { execSync, spawnSync, spawn } from 'node:child_process';
import fs from 'node:fs';

const OUT = 'salida'; fs.mkdirSync(OUT, { recursive: true });
const PKG = JSON.parse(fs.readFileSync('capacitor.config.json', 'utf8')).appId;
const VER = JSON.parse(fs.readFileSync('package.json', 'utf8')).version.split('.').slice(0, 2).join('.');
const res = [], log = (...a) => { const s = a.join(' '); console.log(s); fs.appendFileSync(`${OUT}/pasos.txt`, s + '\n'); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
// si la prueba se atasca (el emulador de GitHub a veces se queda colgado), se guarda lo que haya y se termina
let logsSaved = false;
function finish(why) {
  if (!logsSaved) { try { saveLogs(); } catch {} }
  try {
    if (why) log('\nLA PRUEBA SE CORTÓ: ' + why);
    fs.writeFileSync(`${OUT}/resultado.json`, JSON.stringify(res, null, 1));
    log(`\n${res.filter(r => r.ok).length} de ${res.length} bien`);
  } catch {}
  process.exit(0);
}
setTimeout(() => finish('pasó de 62 minutos'), 62 * 60000).unref();
// (si existe pruebas/solo-ventanas: una prueba rápida, solo de las ventanas y el reproductor a pantalla completa)
const SOLO = fs.existsSync('pruebas/solo-ventanas');
const SOLO_VIZ = fs.existsSync('pruebas/solo-viz');
const SOLO_LY = fs.existsSync('pruebas/solo-letra');
const SOLO_VOZ = fs.existsSync('pruebas/solo-voz');
const SOLO_CEREBRO = fs.existsSync('pruebas/solo-cerebro');
const SOLO_BASE = fs.existsSync('pruebas/solo-base');
// la pantalla en crudo (sin PNG: así se puede comparar aquí mismo, píxel a píxel)
const grab = () => {
  const r = spawnSync('adb', ['exec-out', 'screencap'], { maxBuffer: 64e6 }); const b = r.stdout;
  if (!b || b.length < 16) return null;
  const w = b.readUInt32LE(0), h = b.readUInt32LE(4), hdr = b.length - w * h * 4;
  return hdr === 12 || hdr === 16 ? { w, h, d: b.subarray(hdr) } : null;
};
// cuánto se parecen dos pantallas en un recuadro (en fracciones): por píxel y en bloques de 12 (lo que se ve de lejos)
const vdiff = (A, B, box) => {
  if (!A || !B || A.w !== B.w || A.h !== B.h) return null;
  const x0 = Math.round(box[0] * A.w), y0 = Math.round(box[1] * A.h), x1 = Math.round(box[2] * A.w), y1 = Math.round(box[3] * A.h), BS = 12;
  let s = 0, n = 0, la = 0, lb = 0;
  for (let y = y0; y < y1; y += 2) for (let x = x0; x < x1; x += 2) { const i = (y * A.w + x) * 4; for (let c = 0; c < 3; c++) { s += Math.abs(A.d[i + c] - B.d[i + c]); la += A.d[i + c]; lb += B.d[i + c]; n++; } }
  let sb = 0, nb = 0;
  for (let by = y0; by + BS <= y1; by += BS) for (let bx = x0; bx + BS <= x1; bx += BS) {
    const a = [0, 0, 0], b = [0, 0, 0];
    for (let y = by; y < by + BS; y++) for (let x = bx; x < bx + BS; x++) { const i = (y * A.w + x) * 4; for (let c = 0; c < 3; c++) { a[c] += A.d[i + c]; b[c] += B.d[i + c]; } }
    for (let c = 0; c < 3; c++) sb += Math.abs(a[c] - b[c]) / (BS * BS);
    nb += 3;
  }
  return { px: +(s / n).toFixed(2), blk: +(sb / nb).toFixed(2), luzA: +(la / n).toFixed(1), luzB: +(lb / n).toFixed(1) };
};
function saveLogs() {
  logsSaved = true;
  fs.writeFileSync(`${OUT}/logcat.txt`, adb('logcat -d -v time *:W').split('\n').slice(-6000).join('\n'));
  fs.writeFileSync(`${OUT}/logcat-app.txt`, adb('logcat -d -v time').split('\n').filter(l => /orbita|Orbita|Nova|Media3|MediaSess|AndroidRuntime|Capacitor\/Console|chromium/i.test(l)).slice(-4000).join('\n'));
  fs.writeFileSync(`${OUT}/cierres.txt`, adb('logcat -d -b crash'));
  fs.writeFileSync(`${OUT}/sesion.txt`, session());
}
let adbFails = 0;
const adb = (c, o = {}) => { if (adbFails > 12) finish('el emulador dejó de contestar'); try { const r = execSync('adb ' + c, { encoding: 'utf8', timeout: 45000, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64e6, ...o }); adbFails = 0; return r; } catch (e) { if (e.killed || /ETIMEDOUT|offline|no devices|device '[^']*' not found/.test(String(e.message) + e.stderr)) adbFails++; return 'ERR ' + (e.stdout || '') + (e.stderr || e.message); } };
const sh = c => adb(`shell "${c.replace(/"/g, '\\"')}"`);
let shotN = 0;
const shot = name => { const f = `${OUT}/${String(++shotN).padStart(2, '0')}-${name}.png`; const r = spawnSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64e6 }); if (r.stdout && r.stdout.length > 1000) fs.writeFileSync(f, r.stdout); return f; };
const check = (name, ok, info = '') => { res.push({ name, ok: !!ok, info: String(info).slice(0, 600) }); log((ok ? 'ok    ' : 'FALLA ') + '· ' + name + (info !== '' ? ' · ' + String(info).slice(0, 600) : '')); };

// lo que va diciendo Android, guardado sobre la marcha (si el emulador se cae a mitad, al final ya no se le puede preguntar)
try { const fd = fs.openSync(`${OUT}/logcat-vivo.txt`, 'w'); const lc = spawn('adb', ['logcat', '-v', 'time', '*:W'], { stdio: ['ignore', fd, 'ignore'] }); lc.unref(); } catch {}

// ---- el WebView por dentro (Chrome DevTools Protocol)
let ws = null, msgId = 0, cdpFails = 0; const pending = new Map();
function cdpLost() {
  if (++cdpFails < 3) return;
  check('la app sigue abierta y se puede manejar', false, 'arriba: ' + sh('dumpsys activity activities | grep -m1 topResumedActivity').trim().slice(0, 160) + ' · ' + crashText().slice(0, 1200));
  finish('la app dejó de contestar');
}
async function cdpConnect() {
  if (ws) { try { ws.close(); } catch {} ws = null; }
  for (let k = 0; k < 40; k++) {
    const pid = sh(`pidof ${PKG}`).trim().split(/\s+/)[0];
    if (pid && /^\d+$/.test(pid)) {
      adb(`forward tcp:9222 localabstract:webview_devtools_remote_${pid}`);
      try {
        const list = await (await fetch('http://127.0.0.1:9222/json')).json();
        const page = list.find(p => p.type === 'page' && /localhost|capacitor/.test(p.url)) || list.find(p => p.type === 'page');
        if (page) {
          ws = new WebSocket(page.webSocketDebuggerUrl);
          await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = () => no(new Error('ws')); });
          ws.onmessage = e => { const m = JSON.parse(e.data); const p = pending.get(m.id); if (p) { pending.delete(m.id); p(m); } };
          return true;
        }
      } catch {}
    }
    await sleep(1500);
  }
  return false;
}
const cdp = (method, params = {}) => new Promise((ok, no) => { const id = ++msgId; pending.set(id, ok); try { ws.send(JSON.stringify({ id, method, params })); } catch (e) { no(e); } setTimeout(() => { if (pending.delete(id)) ok({ error: { message: 'sin respuesta' } }); }, 20000); });
async function js(expr, retry = true) {
  if (!ws || ws.readyState !== 1) { if (!(await cdpConnect())) { cdpLost(); return { __err: 'sin conexión con el WebView' }; } }
  let r;
  try { r = await cdp('Runtime.evaluate', { expression: `(async () => { ${expr} })()`, awaitPromise: true, returnByValue: true }); } catch (e) { r = { error: { message: String(e && e.message || e) } }; }
  if (r.error && retry) {
    log('  (el WebView no contesta: ' + r.error.message + '; reconecto)');
    if ((js.dumps = (js.dumps || 0) + 1) <= 2) {
      try { const pid = sh(`pidof ${PKG}`).trim().split(/\s+/)[0]; if (pid) fs.writeFileSync(`${OUT}/hilos-${js.dumps}.txt`, adb(`shell debuggerd -j ${pid}`).slice(0, 400000)); } catch {}
    }
    if (!(await cdpConnect())) {
      const gone = /not found|no devices|offline|ERR/.test(adb('get-state'));
      if (gone) { check('el emulador sigue encendido (se cerró él solo; la app no se había cerrado)', false, crashText().slice(0, 1500)); finish('el emulador se cerró'); }
      cdpLost();
      return { __err: 'sin conexión con el WebView' };
    }
    cdpFails = 0;
    return js(expr, false);
  }
  if (r.error) return { __err: r.error.message };
  cdpFails = 0;
  if (r.result && r.result.exceptionDetails) return { __err: (r.result.exceptionDetails.exception || {}).description || r.result.exceptionDetails.text };
  return r.result && r.result.result ? r.result.result.value : undefined;
}
// arranca (o recarga) la parte web con el modo de pruebas y sin las pantallas de bienvenida
async function webStart(reload) {
  if (!(await cdpConnect())) return false;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `window.ORBITA_DEBUG = true; try { for (const [k, v] of Object.entries({ onboarded: true, newsSeen: '${VER}', devWelcomeDone: true, intros: false, lastUpdateCheck: Date.now() + 864e5 })) localStorage.setItem('orbita:' + k, JSON.stringify(v)); if (localStorage.getItem('orbita:alarm') == null) localStorage.setItem('orbita:alarm', JSON.stringify({ on: false, h: ${(new Date().getUTCHours() + 6) % 24}, m: 0 })); if (localStorage.getItem('orbita:nativeUi') == null && !localStorage.getItem('pruebaDeFabrica')) localStorage.setItem('orbita:nativeUi', 'false'); } catch (e) {}` });
  if (reload) { await cdp('Page.reload'); await sleep(9000); }
  return true;
}
const session = () => sh('dumpsys media_session');
const sessState = () => { const d = session(); const i = d.indexOf(PKG); if (i < 0) return 'sin sesión'; const part = d.slice(i, i + 3000); const st = (part.match(/state=PlaybackState \{state=(\w+)\((\d+)\)|state=PlaybackState \{state=(\d+)/) || []); const md = (part.match(/metadata: size=\d+, description=([^\n]*)/) || [])[1] || ''; return `estado ${st[1] || st[3] || '?'}${st[2] ? '(' + st[2] + ')' : ''} · activa ${/active=true/.test(part)} · ${md.trim()}`; };
const playing = () => /state=(PLAYING\(3\)|3[,}])/.test((() => { const d = session(); const i = d.indexOf(PKG); return i < 0 ? '' : d.slice(i, i + 3000); })());
const alive = () => /^\d+/.test(sh(`pidof ${PKG}`).trim());
const fgService = () => { const d = sh(`dumpsys activity services ${PKG}`); return /isForeground=true/.test(d) ? 'en primer plano' : /ServiceRecord/.test(d) ? 'servicio sin primer plano' : 'sin servicio'; };
// ---- lo que hay en pantalla según Android (también ve las pantallas nativas de Compose)
function ui(again = true) {
  sh('uiautomator dump /sdcard/ui.xml');
  const x = sh('cat /sdcard/ui.xml'), out = [];
  // el emulador de pruebas va justo: si sale «tal app no responde» (del sistema, no de Órbita), «Esperar» y se mira otra vez
  if (again && /isn&apos;t responding|isn't responding|no responde/.test(x)) { const m = x.match(/text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/); log('  (aviso del sistema del emulador: ' + (x.match(/text="([^"]*respond[^"]*)"/) || [])[1] + ')'); if (m) sh(`input tap ${(+m[1] + +m[3]) / 2} ${(+m[2] + +m[4]) / 2}`); else sh('input keyevent KEYCODE_ENTER'); execSync('sleep 2'); return ui(false); }
  ui.last = x;
  const inWeb = [];
  for (const m of x.matchAll(/<node [^>]*?(\/?)>|<\/node>/g)) {
    if (m[0] === '</node>') { inWeb.pop(); continue; }
    const g = k => { const r = m[0].match(new RegExp(' ' + k + '="([^"]*)"')); return r ? r[1].replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)) : ''; };
    const b = (g('bounds').match(/\d+/g) || []).map(Number), cls = g('class');
    const web = !!inWeb[inWeb.length - 1] || cls === 'android.webkit.WebView';
    if (b.length === 4) out.push({ text: g('text'), desc: g('content-desc'), cls, b, web, id: g('resource-id') });
    if (!m[1]) inWeb.push(web);
  }
  return out;
}
const find = (nodes, text) => nodes.find(n => n.text === text) || nodes.find(n => n.text.startsWith(text));
const center = n => [Math.round((n.b[0] + n.b[2]) / 2), Math.round((n.b[1] + n.b[3]) / 2)];
const tap = (x, y) => sh(`input tap ${Math.round(x)} ${Math.round(y)}`);
async function tapText(text, wait = 1200) { const n = find(ui(), text); if (!n) { log('  (no encuentro en pantalla: ' + text + ')'); return false; } tap(...center(n)); await sleep(wait); return true; }
const titlesOnScreen = nodes => nodes.filter(n => SONGS.includes(n.text)).sort((a, b) => a.b[1] - b.b[1]).map(n => n.text);
const SONGS = ['Luz de las siete', 'Faros', 'Pulso', 'Marea lenta', 'Viernes 23:59', 'Domingo', 'Andén 3', 'Lámpara de escritorio'];
const gfx = () => { const d = adb(`shell dumpsys gfxinfo ${PKG}`); const t = (d.match(/Total frames rendered: (\d+)/) || [])[1], j = (d.match(/Janky frames: (\d+) \(([\d.]+)%\)/) || []), p90 = (d.match(/90th percentile: (\d+)ms/) || [])[1], p99 = (d.match(/99th percentile: (\d+)ms/) || [])[1]; return `${t} fotogramas · lentos ${j[1]} (${j[2]} %) · p90 ${p90} ms · p99 ${p99} ms`; };

// los cierres por error DE ÓRBITA (las herramientas del propio emulador, como la que mira la pantalla, también se caen a veces: no cuentan)
const crashText = () => {
  const blocks = []; let cur = null;
  for (const l of adb('logcat -d -b crash').split('\n')) { if (/FATAL EXCEPTION|\*\*\* \*\*\* \*\*\*|beginning of crash/.test(l)) { cur = []; blocks.push(cur); } if (cur) cur.push(l); }
  return blocks.map(b => b.join('\n')).filter(b => b.includes(PKG)).join('\n');
};
const crashes = () => (crashText() ? 1 : 0);

try {
  // ---- música de prueba
  fs.mkdirSync('musica', { recursive: true });
  const songs = [['Luz de las siete', 'Kora', 'Amanecer', 220], ['Faros', 'Banda Sur', 'Costa', 277], ['Pulso', 'Kora', 'Amanecer', 330], ['Marea lenta', 'Los Rayos', 'Mar', 196], ['Viernes 23:59', 'Neón Norte', 'Ciudad', 392], ['Domingo', 'Aurora Sur', 'Casa', 262]];
  songs.forEach(([t, a, al, f], i) => execSync(`ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=${f}:duration=40" -f lavfi -i "sine=frequency=${f * 1.5}:duration=40" -filter_complex "[0][1]amix=inputs=2,tremolo=f=2:d=0.7,volume=0.5" -ac 2 -ar 44100 -b:a 128k -metadata title="${t}" -metadata artist="${a}" -metadata album="${al}" musica/c${i + 1}.mp3`));
  // dos de ellas llevan portada dentro (para probar las portadas en la lista)
  for (const [i, col] of [[1, '0x1e90ff'], [4, '0xff7a18']]) {
    execSync(`ffmpeg -loglevel error -y -f lavfi -i "testsrc2=s=600x600:r=1" -frames:v 1 -vf "drawbox=x=0:y=0:w=600:h=600:color=${col}@0.55:t=fill" musica/p${i}.png`);
    execSync(`ffmpeg -loglevel error -y -i musica/c${i}.mp3 -i musica/p${i}.png -map 0:a -map 1:v -c:a copy -c:v mjpeg -id3v2_version 3 -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" -disposition:v attached_pic musica/k${i}.mp3`);
    fs.renameSync(`musica/k${i}.mp3`, `musica/c${i}.mp3`);
  }
  sh('mkdir -p /sdcard/Music');
  for (let i = 1; i <= songs.length; i++) adb(`push musica/c${i}.mp3 /sdcard/Music/c${i}.mp3`);
  sh('content call --uri content://media --method scan_volume --arg external_primary');
  for (let i = 1; i <= songs.length; i++) sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/c${i}.mp3`);
  await sleep(4000);
  // (a veces el emulador tarda en apuntar alguna canción: se le vuelve a pedir hasta que estén todas)
  const inStore = () => sh('content query --uri content://media/external/audio/media --projection title').split('\n').filter(l => /title=/.test(l)).length;
  for (let k = 0; k < 6 && inStore() < songs.length; k++) { sh('content call --uri content://media --method scan_volume --arg external_primary'); await sleep(5000); }
  if (inStore() < songs.length) {
    log('  (el emulador no apunta las canciones: se reinicia su almacén de música y se vuelve a pedir)');
    for (const pk of ['com.google.android.providers.media.module', 'com.android.providers.media.module']) sh(`am force-stop ${pk}`);
    await sleep(3000);
    for (let k = 0; k < 8 && inStore() < songs.length; k++) { sh('content call --uri content://media --method scan_volume --arg external_primary'); await sleep(6000); }
  }
  log('música en el emulador:', inStore(), 'canciones');

  // que los avisos de «no responde» del propio emulador no tapen la pantalla
  sh('settings put global hide_error_dialogs 1'); sh('settings put secure anr_show_background 0');
  // ---- instalar y abrir
  const apk = 'android/app/build/outputs/apk/debug/app-debug.apk';
  const inst = adb(`install -r -g ${apk}`, { timeout: 240000 });
  check('la app se instala', /Success/.test(inst), inst.trim().split('\n').pop());
  for (const p of ['READ_MEDIA_AUDIO', 'POST_NOTIFICATIONS']) sh(`pm grant ${PKG} android.permission.${p}`);
  // (el micrófono, como en un móvil recién instalado: sin permiso; el primer «Háblale» lo pide. Aún no está abierta)
  sh(`pm revoke ${PKG} android.permission.RECORD_AUDIO`);
  adb('logcat -c'); adb('logcat -b crash -c');
  sh(`am start -n ${PKG}/.MainActivity`);
  await sleep(12000);
  shot('arranque');
  check('la app sigue abierta tras arrancar', alive());
  check('se puede manejar por dentro (depuración del WebView)', await webStart(true));
  await sleep(4000);
  shot('inicio');
  // la música del móvil
  let n = 0;
  for (let k = 0; k < 20 && n < songs.length; k++) { n = await js(`const o = window.__orbita; return o ? o.state.tracks.filter(t => t.dev).length : -1;`); if (n < songs.length) await sleep(2000); }
  check('la app ve la música del emulador', n >= songs.length, n + ' canciones');
  // las portadas de las canciones del móvil (la app las va leyendo sola)
  let arts = 0;
  for (let k = 0; k < 15 && arts < 2; k++) { arts = await js(`return window.__orbita.state.tracks.filter(t => t.dev && t.thumb).length;`); if (arts < 2) await sleep(2000); }
  check('la app lee las portadas de las canciones del móvil', arts >= 2, arts + ' con portada');
  // cómo se ve cada pantalla de la app web (referencia para las pantallas nativas)
  const goView = async v => { for (let k = 0; k < 5; k++) { const r = await js(`location.hash = '#${v}'; await new Promise(r => setTimeout(r, 400)); return window.__orbita.state.view;`); if (r === v) return true; await sleep(700); } log('  (no cambia a ' + v + ')'); return false; };
  for (const v of ['listas', 'tu', 'ajustes', 'inicio']) { await goView(v); await sleep(1300); shot('web-' + v); }

  // ================= FASE 2 · último paso (4.8): las ventanas y el reproductor a pantalla completa, nativos =================
  const ventanas48 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const D5 = await js(`return devicePixelRatio;`);
    const wst = () => js(`${O} return { on: W.on.join(), shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    // lo que hay en cada paso del reproductor: lo que dice la web, lo último que le mandó a Android, lo que Android tiene
    // y lo que se ve (el volcado de la pantalla, separando lo de la web y lo de Android)
    const diag48 = [];
    const npDiag = async tag => {
      const d = await js(`${O} const st = await Capacitor.Plugins.OrbitaUi.status(); const t = document.querySelector('#npTitle'); return { web: t && t.textContent, np: !document.querySelector('#np').hidden, on: W.on.join(), drag: W.drag, held: W.held, seq: W.seq, log: (W.log || []).slice(-8), nat: { seq: st.winSeq, wins: st.wins, shown: st.winShown, texts: st.winTexts } };`);
      const nodes = ui(); try { fs.writeFileSync(`${OUT}/ui-${tag}.xml`, ui.last || ''); } catch {}
      diag48.push({ tag, d, nativo: nodes.filter(n => !n.web && (n.text || n.desc)).map(n => n.text || '(' + n.desc + ')').slice(0, 60), web: nodes.filter(n => n.web && n.text).map(n => n.text).slice(0, 30) });
      fs.writeFileSync(`${OUT}/ventanas-diag.json`, JSON.stringify(diag48, null, 1));
      return nodes;
    };
    // un texto que dibuja Android (no la web de debajo); un título largo puede ir en dos renglones
    const findNat = (nodes, t) => nodes.find(n => !n.web && n.text && (n.text === t || (t.startsWith(n.text) && n.text.length >= Math.min(4, t.length))));
    let T5 = 0; { const wv = ui().find(n => n.cls === 'android.webkit.WebView'); T5 = wv ? wv.b[1] : 0; }
    // cada renglón de una ventana, donde en la de siempre (± 3): la de siempre (con «classic») contra la nativa
    const pariWin = async (sel, file, fotos) => {
      const Q = `const out = [], g0 = document.createRange(), root = document.querySelector('${sel}');
        const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        for (let n = w.nextNode(); n; n = w.nextNode()) {
          const p = n.parentElement; if (!p || !n.textContent.trim() || p.closest('select, [hidden], canvas, svg, textarea')) continue;
          const cs = getComputedStyle(p); if (cs.display === 'none') continue;
          const raw = n.textContent, ws = [], re = /\\S+/g; let mm; while ((mm = re.exec(raw))) { g0.setStart(n, mm.index); g0.setEnd(n, mm.index + mm[0].length); const q = g0.getBoundingClientRect(); if (q.width) ws.push({ s: mm[0], q }); }
          const L = []; for (const x of ws) { const l = L[L.length - 1]; if (l && Math.abs(x.q.top - l.top) < 3) { l.s += ' ' + x.s; l.bottom = Math.max(l.bottom, x.q.bottom); } else L.push({ s: x.s, left: x.q.left, top: x.q.top, bottom: x.q.bottom }); }
          for (const l of L) { if (l.top < 4 || l.bottom > innerHeight - 4) continue; out.push({ t: (cs.textTransform === 'uppercase' ? l.s.toUpperCase() : l.s).slice(0, 60), x: +l.left.toFixed(1), y: +((l.top + l.bottom) / 2).toFixed(1) }); }
        }
        return out;`;
      await js(`window.__orbita.NUI.classic = true; await new Promise(r => setTimeout(r, 900)); return 1;`); await sleep(600);
      const web = await js(Q); shot(`igual-${fotos}-web`);
      await js(`window.__orbita.NUI.classic = false; await new Promise(r => setTimeout(r, 900)); return 1;`); await sleep(1200);
      const ns = ui(); shot(`igual-${fotos}-nativa`);
      const par = [], bad = [], cnt = {}; for (const e of web || []) cnt[e.t] = (cnt[e.t] || 0) + 1;
      for (const e of web || []) {
        if (cnt[e.t] > 1 || e.t.length < 3 || /^\d+:\d\d$/.test(e.t)) continue;
        const c = ns.filter(n => !n.web && n.text && n.text.trim().slice(0, 60) === e.t);
        if (c.length !== 1) { par.push({ t: e.t, falta: c.length }); if (!c.length) bad.push(`«${e.t.slice(0, 24)}» no está`); continue; }
        const nx = c[0].b[0] / D5, ny = ((c[0].b[1] + c[0].b[3]) / 2 - T5) / D5, dx = +(nx - e.x).toFixed(1), dy = +(ny - e.y).toFixed(1);
        par.push({ t: e.t, web: [e.x, e.y], nat: [+nx.toFixed(1), +ny.toFixed(1)], dx, dy });
        if (Math.abs(dx) > 3 || Math.abs(dy) > 3) bad.push(`«${e.t.slice(0, 24)}» ${dx}/${dy}`);
      }
      fs.writeFileSync(`${OUT}/${file}`, JSON.stringify({ par }));
      return { n: par.filter(e => e.nat).length, bad: [...new Set(bad)] };
    };
    await goView('inicio'); await sleep(1500);
    await js(`${O} const ids = o.state.tracks.slice(0, 7).map(t => t.id); o.playQueue(ids, 0); return 1;`); await sleep(2500);
    // --- el temporizador (la luna de la barra de abajo)
    await js(`document.querySelector('[data-act="sleep-open"]').click(); return 1;`); await sleep(1600);
    let wn = ui(); shot('ventana-temporizador');
    let w0 = await wst();
    check('el temporizador sale nativo, en su capa, y «Ahora» sigue nativa debajo', !!w0 && w0.on === 'sleep' && w0.st.v >= 7 && w0.st.wins === 'sleep' && w0.st.winShown && w0.shown && w0.screen === 'inicio' && !!find(wn, 'Temporizador para dormir') && !!find(wn, '30 min'), JSON.stringify(w0));
    const ps = await pariWin('#sleepOverlay', 'paridad-temporizador.json', 'temporizador');
    check('el temporizador: cada renglón, donde en la de siempre (± 3)', ps.n >= 6 && ps.bad.length === 0, `${ps.n} comparados · ${ps.bad.length} fuera: ${ps.bad.slice(0, 10).join(' · ')}`);
    await tapText('30 min', 1800);
    const sl = await js(`return { on: document.querySelector('[data-act="sleep-open"]').classList.contains('sleep-on'), open: !document.querySelector('#sleepOverlay').hidden, win: window.__orbita.W.on.join() };`);
    const slN = await wst(), slU = ui();
    check('tocar «30 min» pone el temporizador y cierra la ventana (también la nativa)', !!sl && sl.on && !sl.open && sl.win === '' && !!slN && slN.st.wins === '' && !slN.st.winShown && !find(slU.filter(n => !n.web), '45 min'), JSON.stringify(sl) + ' · ' + JSON.stringify(slN && { wins: slN.st.wins, shown: slN.st.winShown }));
    await js(`document.querySelector('[data-act="sleep-open"]').click(); return 1;`); await sleep(1500);
    await tapText('Desactivar', 1600);
    const sl2 = await js(`return document.querySelector('[data-act="sleep-open"]').classList.contains('sleep-on');`);
    if (!(await js(`return document.querySelector('#sleepOverlay').hidden;`))) await tapText('Cerrar', 1200);
    check('y «Desactivar» lo quita', sl2 === false, String(sl2));
    // --- el reproductor a pantalla completa
    // (en pausa: las canciones de prueba duran 30-40 s y se funden con la siguiente, y volcar la pantalla con la barra
    // avanzando le cuesta al emulador más de 10 s; con la música parada, lo que se compara no cambia entretanto)
    const pause = async () => { sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1300); };
    const song = () => js(`const o = window.__orbita, t = document.querySelector('#npTitle'), a = document.querySelector('#npArtist'); return { qi: o.state.qi, t: t && t.textContent, a: a && a.textContent };`);
    await js(`document.querySelector('[data-act="np-open"]').click(); return 1;`); await sleep(2800);
    await pause();
    let pn = await npDiag('abierto'); shot('reproductor-nativo');
    let p0 = await wst();
    const s0 = await song(), tt0 = s0 && s0.t;
    check('el reproductor, abierto y quieto, es nativo (y la pantalla de debajo se aparta: lo tapa todo)', !!p0 && p0.on === 'np' && p0.st.wins === 'np' && p0.st.winShown && !p0.shown && !!findNat(pn, tt0) && !!findNat(pn, 'Cerrar'), JSON.stringify(p0) + ' · ' + tt0);
    const pp = await pariWin('#np', 'paridad-reproductor.json', 'reproductor');
    check('el reproductor: cada renglón, donde en el de siempre (± 3)', pp.n >= 6 && pp.bad.length === 0, `${pp.n} comparados · ${pp.bad.length} fuera: ${pp.bad.slice(0, 10).join(' · ')}`);
    await npDiag('tras-comparar');
    // «Siguiente» (el del reproductor nativo, no el de la barra de abajo)
    pn = ui();
    const nxs = pn.filter(n => !n.web && n.desc === 'Siguiente').sort((a, b) => a.b[1] - b.b[1]);
    if (nxs.length) { tap(...center(nxs[0])); await sleep(2200); }
    await pause();
    const s1 = await song(), tt1 = s1 && s1.t;
    pn = await npDiag('siguiente'); shot('reproductor-siguiente');
    check('«Siguiente» del reproductor nativo pasa de canción (y lo enseña)', nxs.length > 0 && !!s0 && !!s1 && s1.qi !== s0.qi && !!findNat(pn, tt1), `${tt0} → ${tt1} (${s0 && s0.qi} → ${s1 && s1.qi})`);
    // deslizar la portada a la izquierda: la siguiente
    const art = await js(`const r = document.querySelector('#npArt').getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`);
    if (art) { const y = Math.round(T5 + (art[1] + art[3]) / 2 * D5); sh(`input swipe ${Math.round(art[2] * D5 - 60)} ${y} ${Math.round(art[0] * D5 + 60)} ${y} 300`); await sleep(2400); }
    await pause();
    const s2 = await song(), tt2 = s2 && s2.t;
    const pn2 = await npDiag('deslizada');
    check('deslizar la portada a la izquierda pasa a la siguiente (y la nativa la enseña)', !!art && !!s1 && !!s2 && s2.qi !== s1.qi && !!findNat(pn2, tt2), `${tt1} → ${tt2} (${s1 && s1.qi} → ${s2 && s2.qi})`);
    // bajar dentro del reproductor (a lo que viene después) y volver: la de siempre a la misma altura
    // (cuando la nativa se para, le dice a la web dónde se ha quedado: se espera a que llegue, hasta 6 s)
    sh('input swipe 540 1700 540 700 400'); await sleep(1800);
    let sc1 = null;
    for (let k = 0; k < 9; k++) { sc1 = await js(`${O} const sc = document.querySelector('#np .np-inner'); return { web: sc.scrollTop, max: sc.scrollHeight - sc.clientHeight, st: await Capacitor.Plugins.OrbitaUi.status() };`); if (sc1 && sc1.st && Math.abs(sc1.web - sc1.st.winY) < 3) break; await sleep(700); }
    pn = ui(); shot('reproductor-abajo');
    check('dentro del reproductor nativo se baja con el dedo (y el de siempre se pone a la misma altura)', !!sc1 && sc1.web > Math.min(100, sc1.max - 3) && sc1.web > 20 && Math.abs(sc1.web - sc1.st.winY) < 3 && sc1.st.wins === 'np', JSON.stringify(sc1));
    // (a mitad de gesto, al llegar arriba, no se cierra: solo si el dedo empieza arriba del todo, como en el de siempre)
    sh('input swipe 540 700 540 1900 400'); await sleep(1600);
    const sc2 = await js(`${O} return { web: document.querySelector('#np .np-inner').scrollTop, np: !document.querySelector('#np').hidden, on: W.on.join() };`);
    if (sc2 && sc2.np && sc2.web >= 3) { await js(`await Capacitor.Plugins.OrbitaUi.winScroll({ id: 'np', y: 0 }); return 1;`); await sleep(1500); }
    const sc3 = await js(`${O} return { web: document.querySelector('#np .np-inner').scrollTop, np: !document.querySelector('#np').hidden, on: W.on.join() };`);
    check('subir dentro del reproductor no lo cierra aunque se llegue arriba', !!sc2 && sc2.np && sc2.on === 'np', JSON.stringify(sc2));
    // bajarlo con el dedo desde arriba: se cierra (lo mueve el de siempre, como siempre)
    if (sc3 && sc3.np && sc3.web < 3) { sh('input swipe 540 500 540 1900 250'); await sleep(2200); }
    const cl = await js(`${O} return { np: !document.querySelector('#np').hidden, on: W.on.join(), shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    await npDiag('bajado'); shot('reproductor-bajado');
    check('arriba del todo, bajarlo con el dedo lo cierra, y vuelve «Ahora» nativa', !!sc3 && sc3.web < 3 && !!cl && !cl.np && cl.on === '' && !cl.st.winShown && cl.shown && cl.screen === 'inicio', JSON.stringify([sc3, cl]));
    // otra vez, y «Cerrar»
    await js(`document.querySelector('[data-act="np-open"]').click(); return 1;`); await sleep(2800);
    const p2 = await wst();
    await tapText('Cerrar', 2400);
    const p3 = await js(`${O} return { np: !document.querySelector('#np').hidden, on: W.on.join(), shown: N.shown };`);
    check('se abre otra vez nativo, y «Cerrar» lo cierra', !!p2 && p2.on === 'np' && !!p3 && !p3.np && p3.on === '' && p3.shown, JSON.stringify([p2 && p2.on, p3]));
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1200);
    // --- editar una canción: escribir en la ventana nativa y guardar
    const tid = await js(`return window.__orbita.state.tracks[1].id;`);
    const tit0 = await js(`return window.__orbita.trackById('${tid}').title;`);
    await js(`window.__orbita.openEdit('track', '${tid}'); return 1;`); await sleep(1600);
    wn = ui(); shot('ventana-editar');
    const ef = find(wn, tit0);
    if (ef) { tap(ef.b[2] - 20, (ef.b[1] + ef.b[3]) / 2); await sleep(1600); sh('input keyevent KEYCODE_MOVE_END'); sh('input text %s2'); await sleep(1500); }
    const ev = await js(`return document.querySelector('#editTitle').value;`);
    shot('ventana-editar-teclado');
    await sleep(800); wn = ui();
    let gb = find(wn, 'Guardar');
    if (!gb && /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'))) { sh('input keyevent KEYCODE_BACK'); await sleep(1500); gb = find(ui(), 'Guardar'); }
    if (gb) { tap(...center(gb)); await sleep(2000); }
    const tit1 = await js(`${O} return { t: o.trackById('${tid}').title, open: !document.querySelector('#editOverlay').hidden, on: W.on.join() };`);
    check('editar una canción en la ventana nativa: lo escrito llega y «Guardar» lo guarda', !!ef && ev === tit0 + ' 2' && !!tit1 && tit1.t === tit0 + ' 2' && !tit1.open && tit1.on === '', JSON.stringify({ ef: !!ef, ev, tit1 }));
    await js(`const t = window.__orbita.trackById('${tid}'); t.title = ${JSON.stringify(tit0)}; return 1;`);
    // --- la letra: una caja de varias líneas
    await js(`${O} o.openLyricsEdit(o.trackById('${tid}')); return 1;`); await sleep(1500);
    wn = ui(); shot('ventana-letra');
    const lw = await js(`${O} return { on: W.on.join(), r: (() => { const r = document.querySelector('#lyText').getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom]; })() };`);
    if (lw && lw.r) { tap((lw.r[0] + 30) * D5, T5 + (lw.r[1] + 30) * D5); await sleep(1500); sh('input text hola'); await sleep(1200); }
    const lv = await js(`return document.querySelector('#lyText').value;`);
    await sleep(800);
    let cb = find(ui(), 'Cancelar');
    if (!cb && /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'))) { sh('input keyevent KEYCODE_BACK'); await sleep(1500); cb = find(ui(), 'Cancelar'); }
    if (cb) { tap(...center(cb)); await sleep(1500); }
    const lc = await js(`${O} return { open: !document.querySelector('#lyOverlay').hidden, on: W.on.join() };`);
    check('la ventana de la letra es nativa: se escribe en su caja y «Cancelar» la cierra', !!lw && lw.on === 'ly' && /hola/.test(lv) && !!lc && !lc.open && lc.on === '', JSON.stringify({ lw: lw && lw.on, lv, lc }));
    // --- las novedades: un toque en lo oscuro las cierra
    await js(`document.querySelector('#newsOverlay').hidden = false; return 1;`); await sleep(1400);
    const nw = await js(`${O} return W.on.join();`); shot('ventana-novedades');
    tap(40 * D5, T5 + 30 * D5); await sleep(1500);
    const nc = await js(`${O} return { open: !document.querySelector('#newsOverlay').hidden, on: W.on.join() };`);
    check('las novedades salen nativas y un toque en lo oscuro de alrededor las cierra', nw === 'news' && !!nc && !nc.open && nc.on === '', JSON.stringify([nw, nc]));
    check('sin cierres por error con las ventanas y el reproductor nativos', alive() && crashes() === 0, crashText().slice(0, 2500));
    await goView('inicio'); await sleep(1200);
    };
  if (SOLO) {
    // (como en la prueba entera: las pantallas nativas se encienden con su interruptor de Ajustes)
    await goView('ajustes'); await sleep(1000);
    const t0 = await js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); if (b.getAttribute('aria-pressed') !== 'true') b.click(); await new Promise(r => setTimeout(r, 400)); return b.getAttribute('aria-pressed');`);
    check('prueba rápida: las pantallas nativas, encendidas', t0 === 'true', t0);
    await goView('inicio'); await sleep(3000);
    await ventanas48(); saveLogs(); finish('prueba rápida: solo las ventanas');
  }

  // ================= FASE 3 · paso 2 (4.9): los visualizadores, dibujados por Android =================
  const viz49 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    const moving = async (ms = 1500) => { const a = await st(); await sleep(ms); const b = await st(); return { n: a && b ? b.vizFrames - a.vizFrames : -1, st: b }; };
    const D9 = await js(`return devicePixelRatio;`);
    let T9 = 0; { const wv = ui().find(n => n.cls === 'android.webkit.WebView'); T9 = wv ? wv.b[1] : 0; }
    const tapWeb = async (sel, wait = 2200) => { const r = await js(`const e = document.querySelector('${sel}'); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`); if (r) { tap(Math.round((r[0] + r[2]) / 2 * D9), Math.round(T9 + (r[1] + r[3]) / 2 * D9)); await sleep(wait); } return r; };
    await goView('inicio'); await sleep(800);
    await js(`${O} const ids = o.state.tracks.slice(0, 6).map(t => t.id); o.playQueue(ids, 0); return 1;`); await sleep(2500);
    // --- «Ahora» con el visualizador arriba
    await js(`document.querySelector('[data-hero="viz"]').click(); return 1;`); await sleep(3000);
    const h1 = await moving(1500);
    const hs = await js(`${O} return { shown: N.shown, screen: N.screen, which: o.vizNatWhich(), hero: o.state.heroMode };`);
    shot('viz-ahora');
    check('«Ahora» con el visualizador arriba es nativa y el visualizador lo dibuja Android (y se mueve)', !!hs && hs.shown && hs.screen === 'inicio' && hs.which === 'vizHero' && h1.st && h1.st.vizActive === 'vizHero' && h1.n >= 10 && /^vizHero:gl:/.test(h1.st.vizDrawn), JSON.stringify({ hs, n: h1.n, drawn: h1.st && h1.st.vizDrawn, err: h1.st && h1.st.vizErr }));
    // tocarlo abre el reproductor (como siempre); allí, «Visual»
    const until = async (expr, ms = 8000) => { const t0 = Date.now(); let v; while (Date.now() - t0 < ms) { v = await js(expr); if (v === true) return true; await sleep(500); } return false; };
    // (las canciones de prueba duran 30 s y mirar la pantalla con el visualizador moviéndose tarda: que no se acabe a mitad)
    await js(`${O} try { o.act().currentTime = 0.5; } catch (e) {} return 1;`);
    let hn = ui().find(n => !n.web && /Visualizador/.test(n.desc || ''));
    if (hn) { tap(...center(hn)); await sleep(1500); }
    await until(`${O} return W.on.join() === 'np';`);
    await sleep(800);
    // «Visual» (si no está ya puesto); si el toque se pierde, otra vez
    let nv = null;
    for (let t = 0; t < 2; t++) {
      if (await js(`${O} return !!o.VZ.np;`) === true) break;
      nv = null;
      for (let k = 0; k < 3 && !nv; k++) { nv = ui().find(n => !n.web && (n.text === 'Visual' || n.desc === 'Visual')); if (!nv) await sleep(900); }
      if (nv) tap(...center(nv)); else await tapWeb('#npVizBtn', 300);
      await until(`${O} return !!o.VZ.np;`, 4000);
    }
    await until(`return (await Capacitor.Plugins.OrbitaUi.status()).vizActive === 'vizNP';`, 6000); await sleep(800);
    const n1 = await moving(1500);
    shot('viz-reproductor');
    const np1 = await js(`${O} return { np: !document.querySelector('#np').hidden, viz: o.VZ.np, on: W.on.join() };`);
    check('tocar el visualizador de «Ahora» abre el reproductor, y su «Visual» lo dibuja Android (el reproductor sigue nativo)', !!hn && !!np1 && np1.np && np1.viz && np1.on === 'np' && n1.st && n1.st.vizActive === 'vizNP' && n1.n >= 10 && /^vizNP:gl:/.test(n1.st.vizDrawn), JSON.stringify({ hn: !!hn, nv: !!nv, np1, n: n1.n, drawn: n1.st && n1.st.vizDrawn, err: n1.st && n1.st.vizErr }));
    // --- la etiqueta del estilo (encima del visualizador del reproductor) cambia de estilo, como siempre
    const sc0 = await js(`return window.__orbita.VZ.scene;`);
    await tapWeb('#npViz [data-act="viz-scene"]', 300);
    const scOk = await until(`return window.__orbita.VZ.scene !== ${JSON.stringify(String(sc0))};`, 4000); await sleep(1200);
    const sc1 = await st(); const sc2 = await js(`return window.__orbita.VZ.scene;`);
    check('la etiqueta del estilo, encima del visualizador del reproductor, cambia de estilo (y Android lo dibuja)', scOk && !!sc1 && sc1.vizActive === 'vizNP' && new RegExp('^vizNP:gl:' + sc2 + ':').test(sc1.vizDrawn), JSON.stringify({ de: sc0, a: sc2, drawn: sc1 && sc1.vizDrawn }));
    // --- a pantalla completa (tocando el del reproductor, como siempre)
    await tapWeb('#npViz', 1200);
    await until(`${O} return W.on.join() === 'np,viz';`, 6000); await sleep(800);
    const f1 = await moving(1500);
    shot('viz-pantalla-completa');
    const fs1 = await js(`${O} return { full: !document.querySelector('#vizFull').hidden, on: W.on.join(), chrome: document.querySelector('#vizFull').classList.contains('chrome') };`);
    check('a pantalla completa es una ventana nativa y el visualizador lo dibuja Android', !!fs1 && fs1.full && fs1.on === 'np,viz' && f1.st && f1.st.vizActive === 'vizFS' && f1.n >= 10 && /^vizFS:gl:/.test(f1.st.vizDrawn), JSON.stringify({ fs1, n: f1.n, drawn: f1.st && f1.st.vizDrawn }));
    if (!fs1 || !fs1.full) { check('(sin la pantalla completa no se puede seguir con los visualizadores)', false, ''); await js(`document.querySelector('[data-hero="vinyl"]').click(); return 1;`); return; }
    // arrastrar lo gira (y no cuenta como toque); un toque sin mover enseña o esconde el título
    const y0 = f1.st ? f1.st.vizYaw : 0;
    sh('input swipe 250 1100 850 1150 900'); await sleep(900);
    const g1 = await st(); const c1 = await js(`return document.querySelector('#vizFull').classList.contains('chrome');`);
    check('arrastrar el dedo gira el visualizador (y no esconde el título)', !!g1 && g1.vizYaw - y0 > 0.8 && c1 === fs1.chrome, JSON.stringify({ y0, y1: g1 && g1.vizYaw, chrome: [fs1.chrome, c1] }));
    tap(540, 1000); await sleep(1500);
    const c2 = await js(`return document.querySelector('#vizFull').classList.contains('chrome');`);
    check('un toque sin mover esconde el título (como siempre)', c2 === !fs1.chrome, JSON.stringify([fs1.chrome, c2]));
    if (c2 !== fs1.chrome) { tap(540, 1000); await sleep(1200); }
    // --- cada estilo, quieto: Android y la web, píxel a píxel (lo mismo para la tarjeta gráfica, los mismos números)
    const FZ = { t: 3.2, spec: Array.from({ length: 96 }, (_, i) => +(0.45 + 0.35 * Math.sin(i * 0.27) * Math.cos(i * 0.05)).toFixed(4)), bass: 0.55, mid: 0.4, high: 0.3, level: 0.5, beatK: 0.3, k: 0.4, s: 0,
      rot: 1.3, travel: 2.1, yaw: 0.9, pitch: 0.55, zoom: 1, dx: 0.1, dy: -0.05, histT: 0.4, wave: Array.from({ length: 256 }, (_, i) => Math.round(128 + 90 * Math.sin(i * 0.11) * Math.sin(i * 0.023))) };
    const scenes = ['orbita', 'galaxia', 'ondas', 'montanas', 'aurora', 'tunel', 'particulas'], par = {}, nats = {};
    // (en pausa: la canción, sus colores y su portada no cambian entre una foto y la otra)
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    await js(`document.querySelector('#vizFull').classList.add('chrome'); return 1;`); await sleep(600);   // (sin el título encima)
    for (const sc of scenes) {
      await js(`${O} o.vizSetScene('${sc}'); o.vizFreeze(${JSON.stringify(FZ)}); return 1;`);
      // que Android ya lo esté dibujando (y, si la canción tiene portada, con ella)
      const t0 = Date.now(); let sn = null, f0 = -1;
      while (Date.now() - t0 < 9000) {
        sn = await st();
        const on = !!sn && sn.vizActive === 'vizFS' && String(sn.vizDrawn).includes(':' + sc + ':');
        if (on && f0 < 0) f0 = sn.vizFrames;
        if (on && sn.vizFrames - f0 >= 8 && (sc !== 'orbita' && sc !== 'particulas' || sn.vizCover || Date.now() - t0 > 7000)) break;
        await sleep(300);
      }
      await sleep(700);
      nats[sc] = { img: grab(), st: sn && { drawn: sn.vizDrawn, cover: sn.vizCover } }; shot(`viz-${sc}-nativa`);
    }
    await js(`${O} N.classic = true; return 1;`); await sleep(2000);
    for (const sc of scenes) {
      await js(`${O} o.vizSetScene('${sc}'); o.vizFreeze(${JSON.stringify(FZ)}); return 1;`); await sleep(2200);
      const web = grab(); shot(`viz-${sc}-web`);
      par[sc] = { ...vdiff(nats[sc].img, web, [0.04, 0.1, 0.96, 0.72]), ...nats[sc].st };
    }
    await js(`${O} N.classic = false; o.vizFreeze(null); return 1;`); await sleep(2500);
    // (lo distintas que son dos estilos entre sí: así se sabe que la comparación distingue)
    fs.writeFileSync(`${OUT}/paridad-visualizadores.json`, JSON.stringify(par, null, 1));
    const badV = Object.entries(par).filter(([, v]) => !v || v.blk == null || v.blk > 6 || v.luzA < 3);
    check('cada estilo, quieto, igual en Android que en la web (en bloques, ±6 de 255)', badV.length === 0, Object.entries(par).map(([k, v]) => `${k} ${v ? v.blk + '/' + v.px + ' (' + v.luzA + '·' + v.luzB + ')' : '?'}`).join(' · '));
    // --- con «Ligeras»: los clásicos (2D), dibujados por Android
    await js(`document.querySelector('[data-anim="lite"]').click(); return 1;`); await sleep(3000);
    const l1 = await moving(1500); shot('viz-ligeras');
    check('con animaciones «Ligeras», Android dibuja los clásicos (2D)', !!l1.st && /^vizFS:2d:/.test(l1.st.vizDrawn) && l1.n >= 5 && l1.st.vizTwo === true, JSON.stringify({ n: l1.n, drawn: l1.st && l1.st.vizDrawn, err: l1.st && l1.st.vizErr }));
    await js(`document.querySelector('[data-anim="full"]').click(); return 1;`); await sleep(2500);
    const l2 = await moving(1200);
    check('y al volver a «Completas», otra vez los de la tarjeta gráfica', !!l2.st && /^vizFS:gl:/.test(l2.st.vizDrawn) && l2.n >= 5, JSON.stringify({ n: l2.n, drawn: l2.st && l2.st.vizDrawn }));
    // --- cerrar con su «Cerrar» (el de la ventana nativa); el del reproductor vuelve a dibujarlo Android
    if (await js(`return document.querySelector('#vizFull').classList.contains('chrome');`) === true) { tap(540, 1000); await sleep(1200); }
    const cr = await js(`const e = document.querySelector('#vizFull [data-act="viz-close"]'); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`);
    const cx = Array.isArray(cr) ? Math.round((cr[0] + cr[2]) / 2 * D9) : -1, cy = Array.isArray(cr) ? Math.round(T9 + (cr[1] + cr[3]) / 2 * D9) : -1;
    const nc = cx < 0 ? null : ui().find(n => !n.web && (n.text === 'Cerrar' || n.desc === 'Cerrar') && n.b[0] <= cx && cx <= n.b[2] && n.b[1] <= cy && cy <= n.b[3]);
    if (nc) tap(...center(nc)); else if (cx >= 0) tap(cx, cy);
    await until(`return document.querySelector('#vizFull').hidden;`, 5000); await sleep(1500);
    const b1 = await st(); const bw = await js(`${O} return { full: !document.querySelector('#vizFull').hidden, on: W.on.join() };`);
    check('«Cerrar» cierra la pantalla completa y vuelve el del reproductor', !!bw && bw.full === false && bw.on === 'np' && !!b1 && b1.vizActive === 'vizNP', JSON.stringify({ bw, a: b1 && b1.vizActive, nat: !!nc }));
    if (bw && bw.full) await js(`window.__orbita.closeVizFull(); return 1;`);
    await js(`document.querySelector('#npVizBtn').click(); return 1;`); await sleep(1200);
    await js(`document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(2200);
    await js(`document.querySelector('[data-hero="vinyl"]').click(); return 1;`); await sleep(1500);
    const e1 = await st();
    check('con el disco otra vez arriba, Android ya no dibuja ningún visualizador', !!e1 && e1.vizActive === '', JSON.stringify(e1 && { a: e1.vizActive, err: e1.vizErr }));
    check('sin cierres por error con los visualizadores', crashes() === 0, crashText().slice(0, 1500));
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(800);
  };

  // ================= FASE 3 · paso 3 (5.0): la letra que va con la música, dibujada por Android =================
  const ly50 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    const until = async (expr, ms = 8000) => { const t0 = Date.now(); let v; while (Date.now() - t0 < ms) { v = await js(expr); if (v === true) return true; await sleep(500); } return false; };
    const D = await js(`return devicePixelRatio;`);
    let T = 0; { const wv = ui().find(n => n.cls === 'android.webkit.WebView'); T = wv ? wv.b[1] : 0; }
    await goView('inicio'); await sleep(800);
    await js(`${O} const ids = o.state.tracks.slice(0, 6).map(t => t.id); o.playQueue(ids, 0); return 1;`); await sleep(1500);
    await js(`document.querySelector('[data-act="np-open"]').click(); return 1;`);
    await until(`${O} return W.on.join() === 'np';`, 8000); await sleep(600);
    // la letra abierta, y una sincronizada para esta canción (un verso cada dos segundos; alguno, largo)
    await js(`${O} o.setLyricsOpen(true); return 1;`); await sleep(2500);
    const LRC = Array.from({ length: 16 }, (_, k) => `[00:${String(k * 2).padStart(2, '0')}.00]Verso ${k + 1}${k % 5 === 2 ? ', uno bastante más largo que los demás para que se parta en dos renglones' : ' de la letra'}`).join('\n');
    // (la búsqueda de la letra que acaba de empezar ya no escribe encima: LY.gen cambia)
    await js(`${O} o.LY.gen++; o.act().currentTime = 0.2; const t = o.current(); o.LY.id = t.id; o.renderLyrics(t, { id: t.id, synced: ${JSON.stringify(LRC)}, plain: '', source: 'manual', ts: Date.now() }); return 1;`);
    await until(`return (await Capacitor.Plugins.OrbitaUi.status()).lyDrawn.startsWith('s:');`, 6000); await sleep(800);
    const s1 = await st(); const w1 = await js(`${O} return { on: W.on.join(), open: document.querySelector('#np').classList.contains('ly-open'), lg: o.LY.lg, idx: o.LY.idx, t: +o.act().currentTime.toFixed(2) };`);
    shot('letra-nativa');
    check('con la letra abierta, el reproductor sigue siendo nativo y la letra la dibuja Android', !!w1 && w1.on === 'np' && w1.open && !!s1 && s1.lyDrawn === `s:${w1.lg}:16`, JSON.stringify({ w1, drawn: s1 && s1.lyDrawn }));
    // va con la canción: el verso encendido avanza (el mismo que en la web), se va llenando y la caja lo sigue
    // (el de la web se lee justo antes y justo después del de Android: un cambio de verso puede caer entre medias)
    const a = await st(); await sleep(4300); let wa = await js(`return window.__orbita.LY.idx;`); let b = await st(); let wb = await js(`${O} return { idx: o.LY.idx, t: +o.act().currentTime.toFixed(2) };`);
    // (con el emulador muy cargado, Android puede ir un momento por detrás: se mira otra vez, como mucho 1,5 s)
    for (let k = 0; k < 6 && b && wb && ((b.lyIdx !== wb.idx && b.lyIdx !== wa) || !(b.lyFill > 0)); k++) { await sleep(250); wa = await js(`return window.__orbita.LY.idx;`); b = await st(); wb = await js(`${O} return { idx: o.LY.idx, t: +o.act().currentTime.toFixed(2) };`); }
    check('va con la canción: el verso encendido avanza (el mismo que en la web), se llena y la caja lo sigue', !!a && !!b && !!wb && (b.lyIdx === wb.idx || b.lyIdx === wa) && b.lyIdx > a.lyIdx && b.lyFill > 0 && b.lyFill <= 1 && b.lyY > a.lyY, JSON.stringify({ a: a && [a.lyIdx, +a.lyY.toFixed(1), +a.lyFill.toFixed(2)], b: b && [b.lyIdx, +b.lyY.toFixed(1), +b.lyFill.toFixed(2)], web: wb }));
    // tocar un verso (de los que vienen) lleva la canción allí, como en la web (las canciones de prueba son cortas:
    // desde aquí, en pausa y cerca del principio)
    await js(`${O} o.act().pause(); o.LY.userScroll = 0; o.act().currentTime = 2.2; o.lyricsTick(2.2, true); return 1;`); await sleep(1600);
    const cur = await js(`return window.__orbita.LY.idx;`);
    const want = (typeof cur === 'number' ? cur : 0) + 2;
    const re = new RegExp('^Verso ' + (want + 1) + '\\b');
    let nl = null; for (let k = 0; k < 3 && !nl; k++) { nl = ui().find(n => !n.web && re.test(n.text || '')); if (!nl) await sleep(600); }
    const s2 = await st();
    if (nl) tap(...center(nl));
    await sleep(1300);
    const s3 = await st(); const w3 = await js(`${O} return { idx: o.LY.idx, t: +o.act().currentTime.toFixed(2) };`);
    check('tocar un verso lleva la canción allí (y se enciende ese)', !!nl && !!s3 && s3.lyTaps > s2.lyTaps && !!w3 && w3.idx === want && Math.abs(w3.t - (want * 2 - 0.05)) < 0.3 && s3.lyIdx === w3.idx, JSON.stringify({ want, nodo: !!nl && nl.text, w3, nat: s3 && s3.lyIdx, taps: [s2 && s2.lyTaps, s3 && s3.lyTaps] }));
    // deslizar la letra con el dedo la mueve a ella, y no baja el reproductor (como en la web)
    const box = await js(`const r = document.querySelector('#lyScroll').getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`);
    if (Array.isArray(box)) {
      const x = Math.round((box[0] + box[2]) / 2 * D), y0 = Math.round(T + (box[1] + (box[3] - box[1]) * 0.25) * D), y1 = Math.round(T + (box[1] + (box[3] - box[1]) * 0.8) * D);
      const sy0 = (await st()).lyY;
      sh(`input swipe ${x} ${y0} ${x} ${y1} 600`); await sleep(1300);
      let s4 = await st();
      // (si el emulador se ha comido el gesto, otra vez)
      if (s4 && !(s4.lyY < sy0 - 20)) { sh(`input swipe ${x} ${y0} ${x} ${y1} 600`); await sleep(1300); s4 = await st(); } const w4 = await js(`${O} return { on: W.on.join(), np: !document.querySelector('#np').hidden };`);
      check('deslizar la letra con el dedo la mueve a ella, y no baja el reproductor', !!w4 && w4.np && w4.on === 'np' && !!s4 && s4.lyY < sy0 - 20, JSON.stringify({ sy0: +sy0.toFixed(1), sy1: s4 && +s4.lyY.toFixed(1), w4 }));
    } else check('deslizar la letra con el dedo la mueve a ella, y no baja el reproductor', false, 'sin caja de letra');
    // quieta: cada verso, donde en la web (± 3), con la caja en el mismo sitio
    await sleep(3800);   // (que pasen los 3,5 s del dedo)
    await js(`${O} o.LY.userScroll = 0; o.act().currentTime = 9.1; o.lyricsTick(9.1, true); return 1;`); await sleep(1800);
    const sp = await st();
    const web = await js(`const sc = document.querySelector('#lyScroll'), out = [], g = document.createRange(), r0 = sc.getBoundingClientRect();
      for (const p of sc.querySelectorAll('.ly-line')) { const sp = p.firstElementChild || p; const tn = [...sp.childNodes].find(n => n.nodeType === 3); if (!tn || !tn.textContent.trim()) continue;
        g.selectNodeContents(tn); const rs = g.getClientRects(); if (!rs.length) continue; const r = rs[0];
        out.push({ t: tn.textContent.trim().slice(0, 12), x: +r.left.toFixed(1), y: +((r.top + r.bottom) / 2).toFixed(1) }); }
      return { box: [r0.left, r0.top, r0.right, r0.bottom], scroll: sc.scrollTop, rows: out };`);
    const nat = ui().filter(n => !n.web && /^Verso \d+/.test(n.text || ''));
    const par = [], bad = [];
    if (web && web.rows) for (const e of web.rows) {
      if (e.y < web.box[1] + 8 || e.y > web.box[3] - 8) continue;   // (solo lo que se ve en la caja)
      const c = nat.filter(n => n.text.trim().slice(0, 12) === e.t);
      if (c.length !== 1) { bad.push(`«${e.t}» ${c.length}`); continue; }
      const nx = c[0].b[0] / D, ny = ((c[0].b[1] + c[0].b[3]) / 2 - T) / D, dx = +(nx - e.x).toFixed(1), dy = +(ny - e.y).toFixed(1);
      par.push({ t: e.t, dx, dy }); if (Math.abs(dx) > 3 || Math.abs(dy) > 3) bad.push(`«${e.t}» ${dx}/${dy}`);
    }
    fs.writeFileSync(`${OUT}/paridad-letra.json`, JSON.stringify({ web, nat: nat.map(n => [n.text, n.b]), par, natY: sp && sp.lyY }, null, 1));
    shot('letra-quieta-nativa');
    check('quieta, cada verso de la letra está donde en la de siempre (± 3)', par.length >= 4 && bad.length === 0 && !!sp && Math.abs(sp.lyY - web.scroll) < 2, `${par.length} comparados · fuera: ${bad.slice(0, 8).join(' · ')} · caja: Android ${sp && sp.lyY.toFixed(1)} / web ${web && web.scroll}`);
    await js(`${O} N.classic = true; return 1;`); await sleep(1800); shot('letra-quieta-web');
    await js(`${O} N.classic = false; return 1;`); await sleep(1800);
    // cerrarla: el reproductor sigue nativo, con su portada
    await js(`${O} o.setLyricsOpen(false); return 1;`); await sleep(1500);
    const w5 = await js(`${O} return { on: W.on.join(), open: document.querySelector('#np').classList.contains('ly-open') };`);
    check('al cerrar la letra, el reproductor sigue nativo con su portada', !!w5 && w5.on === 'np' && !w5.open, JSON.stringify(w5));
    await js(`document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(1800);
    check('sin cierres por error con la letra nativa', crashes() === 0, crashText().slice(0, 1500));
  };

  // ================= FASE 3 · paso 3 (5.0): el videoclip en el reproductor nativo =================
  const yt50 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    const until = async (expr, ms = 8000) => { const t0 = Date.now(); let v; while (Date.now() - t0 < ms) { v = await js(expr); if (v === true) return true; await sleep(500); } return false; };
    const D = await js(`return devicePixelRatio;`);
    let T = 0; { const wv = ui().find(n => n.cls === 'android.webkit.WebView'); T = wv ? wv.b[1] : 0; }
    const hole = s => { const m = /^(-?\d+),(-?\d+),(\d+)x(\d+)$/.exec(s && s.ytHole || ''); return m ? m.slice(1).map(Number) : null; };
    const vrect = () => js(`const r = document.querySelector('#npVideo').getBoundingClientRect(); return [r.left, r.top, r.width, r.height];`);
    await goView('inicio'); await sleep(800);
    // YouTube no deja reproducir desde las máquinas de las pruebas: la web se pone como con un vídeo en el reproductor
    // (lo que hace ytLayout), con una carta de ajuste en el sitio del vídeo que cuenta los toques que le llegan
    await js(`${O} const ids = o.state.tracks.slice(0, 6).map(t => t.id); o.playQueue(ids, 0); return 1;`); await sleep(1500);
    await js(`${O} o.act().pause(); return 1;`); await sleep(400);
    await js(`if (document.querySelector('#np').hidden) document.querySelector('[data-act="np-open"]').click(); return 1;`);
    await until(`${O} return W.on.join() === 'np';`, 8000); await sleep(800);
    await js(`${O} const np = document.querySelector('#np'), dk = document.querySelector('#ytDock');
      np.classList.add('yt-on'); dk.hidden = false; dk.classList.add('big'); dk.classList.remove('mini');
      let p = document.querySelector('#ytTestPat'); if (!p) { p = document.createElement('div'); p.id = 'ytTestPat'; dk.appendChild(p); }
      p.style.cssText = 'position:absolute;inset:0;z-index:5;background:linear-gradient(90deg,#ff2020 0 33%,#20ff20 33% 66%,#2040ff 66%)';
      window.__ytTaps = 0; p.onclick = () => { window.__ytTaps++; };
      const sc = document.querySelector('#np .np-inner'); if (!sc.__ytt) { sc.__ytt = 1; sc.addEventListener('scroll', () => { if (np.classList.contains('yt-on')) o.ytPlace(); }); }
      o.ytPlace(); return 1;`);
    await until(`return !!(await Capacitor.Plugins.OrbitaUi.status()).ytHole;`, 6000); await sleep(1200);
    const s1 = await st(); const w1 = await js(`${O} return { on: W.on.join(), yt: document.querySelector('#np').classList.contains('yt-on'), nat: o.ytNat };`); const v1 = await vrect();
    const h1 = hole(s1);
    const g1 = grab();
    shot('videoclip-nativo');
    const near = (h, v) => !!h && !!v && Math.abs(h[0] - v[0] * D) <= 3 && Math.abs(h[1] - (v[1] * D)) <= 3 && Math.abs(h[2] - v[2] * D) <= 4 && Math.abs(h[3] - v[3] * D) <= 4;
    // (por el hueco se ve lo que la web tiene ahí: la carta de ajuste, roja, verde y azul de izquierda a derecha)
    const px = (g, x, y) => { if (!g) return null; const i = (Math.round(y) * g.w + Math.round(x)) * 4; return [g.d[i], g.d[i + 1], g.d[i + 2]]; };
    const seen = h1 ? [1 / 6, 1 / 2, 5 / 6].map(f => px(g1, h1[0] + h1[2] * f, T + h1[1] + h1[3] / 2)) : [];
    const isC = (c, k) => !!c && c[k] > 180 && c[(k + 1) % 3] < 110 && c[(k + 2) % 3] < 110;
    check('con un vídeo, el reproductor sigue nativo y deja el hueco del vídeo justo en su sitio (y por él se ve el vídeo)', !!w1 && w1.on === 'np' && w1.yt && near(h1, v1) && isC(seen[0], 0) && isC(seen[1], 1) && isC(seen[2], 2), JSON.stringify({ w1, hueco: s1 && s1.ytHole, web: v1 && v1.map(x => +(x * D).toFixed(1)), seen }));
    // lo que se toca en el hueco va al vídeo (a la web), no a Android: el reproductor sigue igual
    if (h1) {
      const f0 = s1.ytFwd;
      tap(Math.round(h1[0] + h1[2] / 2), Math.round(T + h1[1] + h1[3] / 2)); await sleep(1500);
      const s2 = await st(); const w2 = await js(`${O} return { on: W.on.join(), np: !document.querySelector('#np').hidden, yt: document.querySelector('#np').classList.contains('yt-on') };`);
      const taps = await js(`return window.__ytTaps;`);
      check('lo que se toca en el hueco le llega al vídeo (y el reproductor sigue igual)', !!s2 && s2.ytFwd === f0 + 1 && taps === 1 && !!w2 && w2.on === 'np' && w2.np && w2.yt, JSON.stringify({ f0, f1: s2 && s2.ytFwd, toquesDelVideo: taps, w2 }));
    } else check('lo que se toca en el hueco le llega al vídeo (y el reproductor sigue igual)', false, 'sin hueco');
    // al deslizar el reproductor, el vídeo y su hueco van con él
    {
      const sc = await js(`const sc = document.querySelector('#np .np-inner'); return sc.scrollHeight - sc.clientHeight;`);
      if (typeof sc === 'number' && sc > 30) {
        const dd = Math.min(70, sc - 4) + 10;   // (poco: que el vídeo siga entero a la vista; y lo que se come el dedo al empezar)
        const x = Math.round(206 * D), y0 = Math.round(T + 700 * D), y1 = Math.round(T + (700 - dd) * D);
        sh(`input swipe ${x} ${y0} ${x} ${y1} 700`); await sleep(1800);
        const s3 = await st(); const v3 = await vrect(); const h3 = hole(s3);
        check('al deslizar el reproductor, el vídeo y su hueco van con él', !!h1 && !!h3 && h3[1] < h1[1] - 20 && near(h3, v3), JSON.stringify({ antes: s1 && s1.ytHole, ahora: s3 && s3.ytHole, web: v3 && v3.map(x => +(x * D).toFixed(1)) }));
        sh(`input swipe ${x} ${y1} ${x} ${y0 + 200} 500`); await sleep(1500);
      } else check('al deslizar el reproductor, el vídeo y su hueco van con él', true, 'el reproductor cabe entero: no se desliza (' + sc + ')');
    }
    await js(`${O} N.classic = true; return 1;`); await sleep(1800); shot('videoclip-web');
    await js(`${O} N.classic = false; return 1;`); await sleep(2000);
    // cerrar el reproductor: el hueco se va; quitar el vídeo: se va también, y el reproductor sigue nativo
    await js(`document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(2000);
    const s4 = await st();
    check('al cerrar el reproductor, el hueco del vídeo se va', !!s4 && !s4.ytHole && !s4.ytDock, JSON.stringify({ hueco: s4 && s4.ytHole, dock: s4 && s4.ytDock }));
    // (la web, otra vez como es: sin vídeo)
    await js(`${O} const p = document.querySelector('#ytTestPat'); if (p) p.remove(); o.ytLayout(); return 1;`); await sleep(1200);
    const s5 = await st();
    check('sin vídeo, el hueco no vuelve', !!s5 && !s5.ytHole, JSON.stringify({ hueco: s5 && s5.ytHole }));
    check('sin cierres por error con el videoclip nativo', crashes() === 0, crashText().slice(0, 1500));
  };

  // ================= FASE 3 · paso 4 (5.1): Nova te escucha sin salir de Órbita =================
  // (el reconocimiento de voz del emulador es uno de mentira, con guion: «pruebas/voz»; el de verdad, el del móvil)
  const voz51 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    const until = async (expr, ms = 8000) => { const t0 = Date.now(); let v; while (Date.now() - t0 < ms) { v = await js(expr); if (v === true) return true; await sleep(400); } return false; };
    const untilSt = async (f, ms = 8000) => { const t0 = Date.now(); let v = null; while (Date.now() - t0 < ms) { v = await st(); if (v && f(v)) return v; await sleep(250); } return v; };
    const apk = 'pruebas/voz/voz-de-prueba.apk';
    if (!fs.existsSync(apk)) { check('el reconocimiento de voz de prueba está (para probar «Háblale»)', false, 'no se pudo hacer: mira gradle/voz en los registros'); return; }
    const ins = adb(`install -r ${apk}`, { timeout: 120000 });
    sh('pm grant io.github.orbita.pruebavoz android.permission.RECORD_AUDIO');
    sh('settings put secure voice_recognition_service io.github.orbita.pruebavoz/.Oido');
    const guion = t => sh(`settings put global orbita_oido '${t}'`);
    guion('pon algo tranquilo');
    check('el reconocimiento de voz de prueba se instala y es el del móvil', /Success/.test(ins) && /pruebavoz/.test(sh('settings get secure voice_recognition_service')), ins.trim().split('\n').pop());
    await goView('inicio'); await sleep(1500);
    const input = () => js(`return document.querySelector('#djInput').value;`);
    // (mirar la pantalla puede tardar con el aro moviéndose: se mira hasta tres veces)
    const find = async (pred, n = 3) => { for (let k = 0; k < n; k++) { const f = ui().find(pred); if (f) return f; await sleep(300); } return null; };
    const clearIn = () => js(`document.querySelector('#djInput').value = ''; return 1;`);
    // ---- la primera vez: el recuadro explica lo del permiso, Android lo pide; «No permitir» → la ventana de Google de siempre
    await clearIn();
    await js(`${O} o.voiceAsk(); return 1;`);
    const p0 = await untilSt(s => s.earOn && s.earStep === 'perm', 5000); await sleep(1200);
    let nodes = ui(); shot('voz-permiso');
    const deny = nodes.find(n => /permission_deny_button$/.test(n.id || '')) || nodes.find(n => /^(Don.t allow|No permitir)$/i.test(n.text));
    check('la primera vez, el recuadro de Nova explica lo del permiso y Android lo pide', !!p0 && p0.earStep === 'perm' && !!deny, JSON.stringify({ paso: p0 && p0.earStep, boton: !!deny && (deny.text || deny.id) }));
    // (en el emulador hay dos «ventanas de Google»: la de Google y la de prueba; Android pregunta cuál, y se elige la de prueba)
    // (la segunda vez Android ya propone la última elegida: «Completar la acción con Voz de prueba» › «Solo una vez»)
    const ventana = async () => {
      for (let k = 0; k < 6; k++) {
        await sleep(900);
        if (await js(`return document.querySelector('#djInput').value.startsWith('ventana:');`) === true) return;
        const cn = ui();
        const pick = cn.find(n => n.text === 'Voz de prueba');
        const once = cn.find(n => /button_once$/.test(n.id || ''));
        if (pick) { tap(...center(pick)); await sleep(900); const o2 = ui().find(n => /button_once$/.test(n.id || '')); if (o2) tap(...center(o2)); }
        else if (once && cn.some(n => /Voz de prueba/.test(n.text || ''))) tap(...center(once));
      }
      const ok = await until(`return document.querySelector('#djInput').value.startsWith('ventana:');`, 6000); await sleep(600);
      // (si se quedó algo abierto, «atrás», para que no tape lo siguiente)
      if (!ok && ui().some(n => /resolver|chooser|button_once/i.test(n.id || ''))) { sh('input keyevent KEYCODE_BACK'); await sleep(1200); }
    };
    if (deny) tap(...center(deny));
    await ventana();
    const d1 = await st(); const i1 = await input();
    check('sin permiso: la ventana de Google de siempre, y lo dicho le llega a Nova', i1 === 'ventana: pon algo tranquilo' && !!d1 && !d1.earOn, JSON.stringify({ i1, on: d1 && d1.earOn }));
    // ---- la segunda vez, Android vuelve a preguntar; si le dices otra vez que no, ya no pregunta más: a partir de ahí, directo
    // a la ventana de Google, sin que el recuadro se asome
    guion('pon algo animado'); await clearIn();
    await js(`${O} o.voiceAsk(); return 1;`);
    const p1 = await untilSt(s => s.earOn && s.earStep === 'perm', 5000); await sleep(1200);
    nodes = ui();
    const deny2 = nodes.find(n => /permission_deny_button$/.test(n.id || '')) || nodes.find(n => /^(Don.t allow|No permitir)$/i.test(n.text));
    if (deny2) tap(...center(deny2));
    await ventana();
    const i2b = await input(); const c1 = ((await st()) || {}).earPermCards || 0;
    guion('pon algo de rock'); await clearIn();
    await js(`${O} o.voiceAsk(); return 1;`);
    await ventana();
    const d3 = await st(); const i3b = await input();
    check('si le dices dos veces que no, ya no se pregunta más: directo a la ventana de Google, sin asomar el recuadro', !!p1 && !!deny2 && i2b === 'ventana: pon algo animado' && i3b === 'ventana: pon algo de rock' && !!d3 && d3.earPermCards === c1 && !d3.earAsking && !d3.earOn, JSON.stringify({ segunda: !!deny2, i2b, i3b, recuadros: d3 && d3.earPermCards, antes: c1 }));
    // ---- con permiso: escucha Órbita, con lo que va entendiendo y el aro moviéndose con la voz
    sh(`pm grant ${PKG} android.permission.RECORD_AUDIO`); await sleep(800);
    // (despacio, para que dé tiempo a mirar el recuadro mientras escucha)
    const frase = 'pon algo tranquilo para leer un rato largo';
    guion('lento:' + frase); await clearIn();
    await js(`${O} o.voiceAsk(); return 1;`);
    const e1 = await untilSt(s => s.earOn && s.earPartials >= 3, 9000);
    const mic = await js(`return document.querySelector('#djMic').classList.contains('on');`);
    shot('voz-escuchando'); nodes = ui();
    const card = nodes.find(n => !n.web && /TE ESCUCHA/.test(n.desc || ''));
    check('con permiso, escucha Órbita: el recuadro de Nova con lo que va entendiendo (y el botón parpadea)', !!e1 && e1.earOn && /^pon algo/.test(e1.earWords) && !!card && mic === true, JSON.stringify({ paso: e1 && e1.earStep, palabras: e1 && e1.earWords, recuadro: !!card && card.desc.slice(0, 80), mic }));
    check('el aro se mueve con la voz', !!e1 && e1.earLevels >= 5 && e1.earMax > 0.3, JSON.stringify({ niveles: e1 && e1.earLevels, max: e1 && +e1.earMax.toFixed(2) }));
    await until(`return document.querySelector('#djInput').value === '${frase}';`, 20000);
    const e2 = await untilSt(s => !s.earOn, 4000); const i2 = await input();
    check('al callarte, lo dicho le llega a Nova y el recuadro se va', i2 === frase && !!e2 && !e2.earOn && e2.earLast === 'ok', JSON.stringify({ i2, ultimo: e2 && e2.earLast }));
    // ---- «Listo» termina antes: se queda con lo dicho hasta entonces
    const jazz = 'pon algo de jazz suave por favor y que dure toda la tarde de hoy';
    guion('lento:' + jazz); await clearIn(); await sleep(400);
    await js(`${O} o.voiceAsk(); return 1;`);
    const e3 = await untilSt(s => s.earOn && s.earPartials >= 1, 6000);
    const listo = await find(n => !n.web && n.text === 'Listo');
    if (listo) tap(...center(listo));
    await until(`return document.querySelector('#djInput').value.length > 0;`, 6000); await sleep(400);
    const i3 = await input(); const e4 = await st();
    check('«Listo» termina antes y le manda lo dicho hasta entonces', !!listo && i3.length > 0 && i3.length < jazz.length && jazz.startsWith(i3) && !!e4 && e4.earLast === 'ok', JSON.stringify({ i3, listo: !!listo, ultimo: e4 && e4.earLast, antes: e3 && e3.earWords }));
    // ---- «Cancelar»: no le llega nada a Nova (una frase muy larga, para que dé tiempo a tocarlo)
    guion('lento:' + jazz); await clearIn(); await sleep(300);
    await js(`${O} o.voiceAsk(); return 1;`);
    await untilSt(s => s.earOn && s.earPartials >= 1, 6000);
    const canc = await find(n => !n.web && n.text === 'Cancelar');
    if (canc) tap(...center(canc));
    const e5 = await untilSt(s => !s.earOn, 4000); await sleep(800); const i5 = await input();
    check('«Cancelar» no le manda nada a Nova', !!canc && i5 === '' && !!e5 && e5.earLast === 'cancel', JSON.stringify({ i5, boton: !!canc, ultimo: e5 && e5.earLast }));
    const quieto = async () => { if (((await st()) || {}).earOn) { await js(`try { await Capacitor.Plugins.OrbitaUi.listenCancel(); } catch (e) {} return 1;`); await sleep(1000); } };
    await quieto();
    // ---- «atrás» mientras escucha: deja de escuchar (y la app sigue a la vista)
    await clearIn(); await sleep(300);
    await js(`${O} o.voiceAsk(); return 1;`);
    await untilSt(s => s.earOn && s.earPartials >= 1, 6000);
    sh('input keyevent KEYCODE_BACK');
    const e6 = await untilSt(s => !s.earOn, 4000); await sleep(800); const i6 = await input();
    const top = sh('dumpsys activity activities | grep -m1 topResumedActivity');
    check('«atrás» mientras escucha: deja de escuchar, no le manda nada y la app sigue', i6 === '' && !!e6 && e6.earLast === 'cancel' && new RegExp(PKG).test(top), JSON.stringify({ i6, ultimo: e6 && e6.earLast, arriba: top.trim().slice(-60) }));
    await quieto();
    // ---- no oye nada; sin internet; ocupado: lo dice en el recuadro, y nada a Nova
    for (const [g, code, txt] of [['nada', 'timeout', 'no oye nada'], ['red', 'net', 'sin internet'], ['ocupado', 'busy', 'ocupado (lo intenta dos veces)']]) {
      guion(g); await clearIn(); await sleep(300);
      const s0 = ((await st()) || {}).earSessions || 0;
      await js(`${O} o.voiceAsk(); return 1;`);
      const f1 = await untilSt(s => s.earSessions > s0 && s.earStep === 'fail', 6000);
      if (g === 'red') { await sleep(300); shot('voz-sin-internet'); }
      const f2 = await untilSt(s => !s.earOn, 5000); await sleep(500); const fi = await input();
      check('si ' + txt + ', lo dice en el recuadro y no le manda nada a Nova', !!f1 && f1.earStep === 'fail' && !!f2 && f2.earLast === code && fi === '', JSON.stringify({ paso: f1 && f1.earStep, ultimo: f2 && f2.earLast, fi }));
    }
    // ---- si el reconocimiento de voz del móvil no tiene permiso del micrófono: la ventana de Google (que lo pide)
    sh('pm revoke io.github.orbita.pruebavoz android.permission.RECORD_AUDIO'); await sleep(1500);
    guion('pon algo de pop'); await clearIn();
    await js(`${O} o.voiceAsk(); return 1;`);
    await ventana();
    const d2 = await st(); const i7 = await input();
    check('si el reconocimiento de voz del móvil no tiene permiso del micrófono: la ventana de Google, y lo dicho le llega a Nova', i7 === 'ventana: pon algo de pop' && !!d2 && !d2.earOn && d2.earLast === 'denied', JSON.stringify({ i7, ultimo: d2 && d2.earLast }));
    sh('pm grant io.github.orbita.pruebavoz android.permission.RECORD_AUDIO'); await sleep(1000);
    // ---- desde el reproductor y desde la conversación, lo mismo
    guion('lento:sube el volumen');
    await js(`${O} const ids = o.state.tracks.slice(0, 6).map(t => t.id); o.playQueue(ids, 0); return 1;`); await sleep(1200);
    await js(`${O} o.act().pause(); document.querySelector('[data-act="np-open"]').click(); return 1;`);
    await until(`${O} return W.on.join() === 'np';`, 8000); await sleep(800);
    const npMic = await find(n => !n.web && n.text === 'Háblale');
    await clearIn();
    if (npMic) tap(...center(npMic)); else await js(`document.querySelector('#npMic').click(); return 1;`);
    // (la foto, cuando ya ha entrado del todo y se ve lo que va entendiendo)
    const n1 = await untilSt(s => s.earOn && s.earPartials >= 1, 6000); await sleep(600); shot('voz-desde-el-reproductor');
    await until(`return document.querySelector('#djInput').value === 'sube el volumen';`, 9000);
    const in1 = await input();
    check('desde el reproductor («Háblale»), escucha Órbita por encima de él', !!npMic && !!n1 && n1.earOn && in1 === 'sube el volumen', JSON.stringify({ boton: !!npMic, on: n1 && n1.earOn, in1 }));
    await js(`document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(1500);
    guion('qué está sonando');
    await js(`${O} o.openChat ? o.openChat() : document.querySelector('#openChat').click(); return 1;`); await sleep(2000);
    const chMic = await find(n => !n.web && n.desc === 'Háblale');
    const before = await js(`return (document.querySelector('#chatLog') || {}).textContent || '';`);
    if (chMic) tap(...center(chMic)); else await js(`document.querySelector('#chatMic').click(); return 1;`);
    await untilSt(s => s.earOn, 5000);
    await until(`return ((document.querySelector('#chatLog') || {}).textContent || '').includes('qué está sonando');`, 9000);
    const after = await js(`return (document.querySelector('#chatLog') || {}).textContent || '';`);
    check('desde la conversación con Nova, también: lo dicho aparece como tu mensaje', !!chMic && !String(before).includes('qué está sonando') && String(after).includes('qué está sonando'), JSON.stringify({ boton: !!chMic }));
    await js(`${O} if (o.closeChat) o.closeChat(); else { const b = document.querySelector('[data-chat="close"]'); if (b) b.click(); } return 1;`); await sleep(1200);
    guion('pon algo tranquilo');
    check('sin cierres por error al escuchar', crashes() === 0, crashText().slice(0, 1500));
  };

  // ================== 5.2 · Fase 3, paso 5: el cerebro de comprensión de Nova, en Android ==================
  const cerebro52 = async () => {
    const O = `const o = window.__orbita;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    // (lo largo, en segundo plano dentro de la app, y se pregunta cada segundo: cada consulta tiene 20 s como mucho)
    const job = async (expr, ms) => {
      await js(`window.__jr = undefined; (async () => { ${expr} })().then(v => { window.__jr = { v }; }, e => { window.__jr = { err: String(e && e.message || e) }; }); return 1;`);
      const t0 = Date.now();
      while (Date.now() - t0 < ms) { const r = await js(`return window.__jr || null;`); if (r && (r.v !== undefined || r.err)) return r; await sleep(1000); }
      return { err: 'tiempo' };
    };
    const D = 'pruebas/cerebro', files = ['tokenizer.json', 'modelo.onnx', 'ort.wasm.min.js', 'ort-wasm-simd.wasm'];
    const have = files.every(f => fs.existsSync(`${D}/${f}`) && fs.statSync(`${D}/${f}`).size > 1000);
    check('el cerebro de comprensión de prueba está (el mismo que se instala en el móvil)', have, have ? files.map(f => f + ' ' + Math.round(fs.statSync(`${D}/${f}`).size / 1048576) + ' MB').join(', ') : 'no se pudo bajar: mira salida/cerebro.txt');
    if (!have) return;
    // ---- los archivos, en la carpeta de la app y con los mismos nombres que cuando se instala desde Ajustes
    sh('mkdir -p /data/local/tmp/cerebro');
    for (const f of files) adb(`push ${D}/${f} /data/local/tmp/cerebro/${f}`, { timeout: 240000 });
    sh(`run-as ${PKG} mkdir -p files/nova-cerebro-pequeno`);
    for (const f of files) adb(`shell "cat /data/local/tmp/cerebro/${f} | run-as ${PKG} sh -c 'cat > files/nova-cerebro-pequeno/${f}'"`, { timeout: 240000 });
    const lsz = sh(`run-as ${PKG} ls -l files/nova-cerebro-pequeno`);
    const sizesOk = files.every(f => new RegExp('\\s' + fs.statSync(`${D}/${f}`).size + '\\s.*' + f.replace(/\./g, '\\.')).test(lsz));
    const nbSt = { repo: 'Xenova/multilingual-e5-small', file: 'onnx/model_quantized.onnx', label: 'multilingual-e5-small', by: 'Microsoft (intfloat)', lic: 'MIT', prefix: 'query: ', mb: 146, ts: Date.now(), where: 'nativo' };
    await js(`${O} o.nbReset(); o.NB.st = ${JSON.stringify(nbSt)}; o.NB.on = true; o.NB.fails = 0; o.NB.natFail = null; localStorage.setItem('orbita:nb', JSON.stringify(o.NB.st)); localStorage.setItem('orbita:nbOn', 'true'); return 1;`);
    // ---- arranca en Android: nada del cerebro en la parte web
    const t0 = Date.now();
    const i1 = await job(`${O} await o.nbInit(); return { nat: !!o.NB.nat, dim: o.NB.dim, ms: o.NB.ms, info: o.NB.natInfo || null, fail: o.NB.natFail || '', bank: !!(o.NB.base && o.NB.cal), worker: !!o.NB.worker, n: o.NB.base ? o.NB.base.labels.length : 0 };`, 240000);
    const s1 = await st();
    const iv = i1.v || {};
    check('el cerebro de comprensión arranca en Android (Kotlin + ONNX Runtime), con sus ejemplos, y nada de él en la parte web', sizesOk && iv.nat === true && iv.dim === 384 && iv.bank && !iv.worker && !!s1 && s1.brainThreads > 0,
      JSON.stringify({ archivos: sizesOk, ...iv, err: i1.err || '', hilos: s1 && s1.brainThreads, carga: s1 && s1.brainLoad, s: Math.round((Date.now() - t0) / 1000) }));
    if (!iv.nat) return;
    // ---- la prueba de Ajustes («entiende X de Y frases»), ahora con el de Android (y así no se hace sola en medio de lo que se mide)
    const ck = await job(`${O} const c = await o.nbCheck(true); return c ? { ok: c.ok, n: c.n, ms: c.ms, eng: c.eng } : null;`, 180000);
    // ---- lo mismo que la parte web: las mismas piezas de palabras, los mismos vectores y lo mismo entendido
    const tricky = ['Pon   algo\ttranquilo\n por favor', '¡¡QUÉ TEMAZO!! 🔥🔥', 'ﬁesta en Ñuñoa — 2024', 'música para estudiar', 'Ça va? 你好, Привет мир', '👨‍👩‍👧 Ⅻ ① ㎏', 'ｆｕｌｌ ｗｉｄｔｈ', '', '   ', 'pon ' + 'algo muy muy largo '.repeat(40)];
    const par = await job(`${O}
      const T = o.NB_TEST.map(x => x[0]).concat(${JSON.stringify(tricky)}), C = o.NB_TEST.map(x => x[0]);
      const nt = await o.mindCall({ type: 'tokenize', texts: T }); if (!nt.ok) throw new Error('tokenize: ' + nt.error);
      const t1 = performance.now(); const ne = await o.mindCall({ type: 'embed', texts: T }); const nms = performance.now() - t1; if (!ne.ok) throw new Error('embed: ' + ne.error);
      const nc = []; for (const x of C) { const r = await o.nbClassify(x); nc.push(r.intent); }

      const w = await o.nbWebProbe(T, C);
      const d = ne.dim, diffIds = [], cos = [];
      T.forEach((t, i) => { if (JSON.stringify(nt.ids[i]) !== JSON.stringify(w.ids[i])) diffIds.push({ t: t.slice(0, 40), a: nt.ids[i].slice(0, 12), w: w.ids[i].slice(0, 12) }); let s = 0; for (let k = 0; k < d; k++) s += ne.vecs[i * d + k] * w.vecs[i * d + k]; cos.push(s); });
      const minCos = Math.min(...cos), worst = T[cos.indexOf(minCos)];
      const diffCls = []; C.forEach((t, i) => { if (nc[i] !== w.cls.out[i].intent) diffCls.push({ t, a: nc[i], w: w.cls.out[i].intent }); });
      const want = o.NB_TEST.map(x => x[1]), okA = nc.filter((x, i) => x === want[i]).length, okW = w.cls.out.filter((x, i) => x.intent === want[i]).length;
      return { n: T.length, diffIds: diffIds.slice(0, 4), nIds: diffIds.length, minCos: +minCos.toFixed(5), worst: worst.slice(0, 40), diffCls, okA, okW, nC: C.length, msA: Math.round(nms / T.length), msW: Math.round(w.ms / T.length) };`, 300000);
    const pv = par.v || {};
    check('Android parte las frases en las mismas piezas que la parte web (también con acentos, emojis, otros alfabetos y frases larguísimas)', !par.err && pv.nIds === 0 && pv.n > 40, JSON.stringify({ err: par.err || '', n: pv.n, distintas: pv.nIds, ej: pv.diffIds }));
    check('y saca los mismos vectores de significado (± lo que cambia de un motor a otro)', !par.err && pv.minCos >= 0.999, JSON.stringify({ minimo: pv.minCos, frase: pv.worst }));
    check('y entiende lo mismo que la parte web en las frases de prueba', !par.err && Array.isArray(pv.diffCls) && pv.diffCls.length === 0 && pv.okA >= pv.okW, JSON.stringify({ android: pv.okA, web: pv.okW, de: pv.nC, distintas: pv.diffCls }));
    check('la prueba de Ajustes › Tu DJ se hace con el cerebro de Android', !!ck.v && ck.v.eng === 'android' && ck.v.ok >= (pv.okW || 0) && ck.v.n > 0, JSON.stringify(ck.v || ck.err));
    // ---- de verdad: decirle algo con tus palabras y que Nova lo haga
    await js(`${O} const lk = t => t.demo ? !!(o.state.demoStats[t.id] || {}).liked : !!t.liked; const t = o.state.tracks.find(t => !lk(t) && !t.net); if (t) o.playQueue([t.id], 0); return 1;`); await sleep(1500);
    const before = await js(`${O} const t = o.current(); return t ? { id: t.id, liked: t.demo ? !!(o.state.demoStats[t.id] || {}).liked : !!t.liked } : null;`);
    // (una frase de «me gusta» que el cerebro tenga clara: si duda, Nova pregunta o propone otra cosa, como siempre)
    const said = await job(`${O} for (const x of ['me encanta esta, guárdala', 'esta canción es un temazo', 'qué pasada de canción, me flipa', 'me mola un montón esta']) { const r = await o.nbClassify(x); if (r.ok && r.intent === 'me_gusta') return x; } return '';`, 60000);
    const phrase = (said.v || 'esta canción es un temazo');
    const calls0 = ((await st()) || {}).brainCalls || 0;
    await js(`${O} o.novaCommand(${JSON.stringify(phrase)}); return 1;`);
    let after = null;
    for (let k = 0; k < 16; k++) { await sleep(500); after = await js(`${O} const t = o.current(); return t ? { id: t.id, liked: t.demo ? !!(o.state.demoStats[t.id] || {}).liked : !!t.liked } : null;`); if (after && after.liked) break; }
    const calls1 = ((await st()) || {}).brainCalls || 0;
    check('«' + phrase + '» (sin las palabras de siempre): Android lo entiende y Nova la guarda en favoritas', !!said.v && !!before && !before.liked && !!after && after.liked && after.id === before.id && calls1 > calls0, JSON.stringify({ frase: said.v || said.err, antes: before, despues: after, llamadas: calls1 - calls0 }));
    // ---- «Pantallas nativas» apagado: el de la parte web, como siempre; encendido: otra vez el de Android
    const tog = async on => js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; if ((b.getAttribute('aria-pressed') === 'true') !== ${on}) b.click(); await new Promise(r => setTimeout(r, 600)); return b.getAttribute('aria-pressed');`);
    await goView('ajustes'); await sleep(800);
    await tog(false); await goView('inicio'); await sleep(800);
    // (la primera vez, mientras arranca, Nova no espera más de 6 s y sigue sin él: se espera a que arranque y otra vez)
    // (y lo que tarda: en una pantalla quieta, sin nada más a la vez, 20 frases; «piensa» es lo que tarda el cerebro, «entera» lo que espera Nova)
    const timing = `const C = o.NB_TEST.map(x => x[0]); let think = 0; const te = performance.now(); for (let i = 0; i < 20; i++) { const r = await o.nbClassify(C[i]); think += r.ms || 0; } const whole = (performance.now() - te) / 20;`;
    const again = `try { await o.nbClassify('pásala que no me apetece'); } catch (e) {} try { await o.NB.ready; } catch (e) {} const r = await o.nbClassify('pásala que no me apetece'); location.hash = '#ajustes'; await new Promise(r => setTimeout(r, 2500)); ${timing} location.hash = '#inicio'; return { intent: r.intent, nat: !!o.NB.nat, worker: !!o.NB.worker, piensa: Math.round(think / 20), entera: Math.round(whole) };`;
    const off = await job(`${O} ${again}`, 240000);
    await goView('ajustes'); await sleep(800);
    await tog(true); await goView('inicio'); await sleep(1500);
    const on = await job(`${O} ${again}`, 120000);
    check('el de Android piensa cada frase más rápido que el de la parte web (y lo que espera Nova, de principio a fin)', !!off.v && !!on.v && on.v.piensa < off.v.piensa, JSON.stringify({ android: on.v && { piensa: on.v.piensa, entera: on.v.entera }, web: off.v && { piensa: off.v.piensa, entera: off.v.entera }, nucleos: s1 && s1.brainThreads }));
    check('con «Pantallas nativas» apagado entiende el de la parte web (como siempre), y al encenderlo vuelve el de Android', !!off.v && off.v.intent === 'saltar' && !off.v.nat && off.v.worker && !!on.v && on.v.intent === 'saltar' && on.v.nat && !on.v.worker, JSON.stringify({ apagado: off.v || off.err, encendido: on.v || on.err }));
    const dg = await js(`let got = ''; try { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async t => { got = t; } } }); } catch (e) {} const b = document.querySelector('#diagCopy'); if (b) b.click(); await new Promise(r => setTimeout(r, 900)); return got;`);
    const line = (String(dg || '').match(/Pantallas nativas:[^\n]*/) || [''])[0];
    check('el diagnóstico dice que el cerebro de comprensión corre en Android', /cerebro de comprensión \(\d+ núcleos\)/.test(line), line.slice(-160));
    check('sin cierres por error con el cerebro', crashes() === 0, crashText().slice(0, 1500));
    await js(`${O} o.nbReset(); return 1;`);   // (se suelta: lo que viene después tiene la memoria de siempre)
  };
  // ================= FASE 4 · parte 1 (5.3): el fondo y la barra de abajo, de Android =================
  const base53 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    const until = async (expr, ms = 8000) => { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (await js(expr) === true) return true; await sleep(400); } return false; };
    const D = await js(`return devicePixelRatio;`);
    let T = 0; { const wv = ui().find(n => n.cls === 'android.webkit.WebView'); T = wv ? wv.b[1] : 0; }
    const rectOf = sel => js(`const e = document.querySelector('${sel}'); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`);
    const scr = r => [Math.round((r[0] + r[2]) / 2 * D), Math.round(T + (r[1] + r[3]) / 2 * D)];
    await goView('inicio'); await sleep(1500);
    await until(`return (await Capacitor.Plugins.OrbitaUi.status()).baseLive === true;`, 8000);
    // --- arriba, con «Ahora» nativa
    const s1 = await st();
    const w1 = await js(`${O} return { shown: N.shown, screen: N.screen, on: o.BASE.on, quiere: o.baseWant() };`);
    shot('base-ahora');
    check('con «Ahora» nativa, el fondo y la barra de abajo los dibuja Android', !!s1 && s1.baseShown && s1.baseLive && s1.baseParts === 5 && /^aurora:/.test(s1.baseDrawn) && !!w1 && w1.on, JSON.stringify({ w1, shown: s1 && s1.baseShown, live: s1 && s1.baseLive, partes: s1 && s1.baseParts, drawn: s1 && s1.baseDrawn, grano: s1 && s1.noiseMs }));
    const webT = await js(`const out = []; const walk = el => { for (const n of el.childNodes) { if (n.nodeType === 3) { const t = n.textContent.trim(); if (t && n.parentElement && n.parentElement.getClientRects().length && getComputedStyle(n.parentElement).visibility !== 'hidden') out.push(t); } else if (n.nodeType === 1 && getComputedStyle(n).display !== 'none') walk(n); } }; walk(document.querySelector('.player')); walk(document.querySelector('.nav')); return out;`);
    const natT = (s1 && s1.baseTexts) || [];
    const norm = a => (a || []).map(t => String(t).replace(/\s+/g, ' ').trim().toLowerCase()).filter(Boolean);
    check('la barra nativa dice lo mismo que la de siempre (la canción, el tiempo y las pestañas)', norm(webT).length >= 6 && norm(webT).every(t => norm(natT).some(x => x === t || x.startsWith(t.slice(0, 12)))), JSON.stringify({ web: norm(webT), android: norm(natT) }));
    // --- se mueve, y al mismo punto que la de la web
    const a1 = await st(); await sleep(1600); const a2 = await st();
    check('la aurora se mueve (Android la dibuja en cada fotograma)', !!a1 && !!a2 && a2.baseFrames - a1.baseFrames >= 10 && /:mueve$/.test(a2.baseDrawn), JSON.stringify({ fotogramas: a2 && a1 ? a2.baseFrames - a1.baseFrames : -1, drawn: a2 && a2.baseDrawn }));
    const dr = await js(`const a = document.getAnimations().find(x => x.animationName === 'drift'); if (!a) return null;
      const ease = x => { if (x <= 0) return 0; if (x >= 1) return 1; let lo = 0, hi = 1, t = x; for (let i = 0; i < 30; i++) { const bx = 3 * (1 - t) * (1 - t) * t * .42 + 3 * (1 - t) * t * t * .58 + t * t * t; if (bx < x) lo = t; else hi = t; t = (lo + hi) / 2; } return 3 * (1 - t) * t * t + t * t * t; };
      const at = ms => { const it = Math.floor(ms / 46000); let f = (ms - it * 46000) / 46000; if (it % 2) f = 1 - f; return ease(f); };
      const o = window.__orbita, t0 = o.baseBg().t; const s = await Capacitor.Plugins.OrbitaUi.status(); const t1 = o.baseBg().t;
      return { web0: at(t0), web1: at(t1), nat: s.bgDrift, ms: t1 - t0 };`);
    check('va al mismo punto que la de la web (su animación de siempre, de 46 s)', !!dr && dr.nat >= Math.min(dr.web0, dr.web1) - 0.004 && dr.nat <= Math.max(dr.web0, dr.web1) + 0.004, JSON.stringify(dr));
    // --- lo que se toca en la barra le llega a la web: una pestaña, el play, la barra del tiempo, subirla con el dedo
    const tb = await rectOf('.nav .tab[data-view="buscar"]');
    if (tb) { tap(...scr(tb)); await sleep(1800); }
    const v1 = await js(`${O} return { view: o.state.view, screen: N.screen, shown: N.shown };`);
    const s2 = await st();
    check('tocar «Buscar» en la barra nativa cambia de pestaña (le llega a la web, como siempre)', !!tb && !!v1 && v1.view === 'buscar' && v1.screen === 'buscar' && s2 && s2.baseLive, JSON.stringify({ v1, live: s2 && s2.baseLive }));
    await goView('inicio'); await sleep(1500);
    await js(`${O} const ids = o.state.tracks.slice(0, 6).map(t => t.id); o.playQueue(ids, 0); return 1;`); await sleep(2500);
    const labE = `document.querySelector('.player .ctrl.play').getAttribute('aria-label')`, lab = `return ${labE};`;
    // (que ya esté sonando de verdad, sin cambiar, antes de tocar)
    for (let k = 0; k < 10; k++) { const a = await js(lab); await sleep(700); if (a === 'Pausar' && await js(lab) === 'Pausar') break; }
    const pl0 = await js(lab);
    const pb = await rectOf('.player .ctrl.play');
    if (pb) tap(...scr(pb));
    const pz = await until(`return ${labE} === 'Reproducir';`, 4000);
    check('tocar el play de la barra nativa pausa (le llega a la web)', !!pb && pz, JSON.stringify({ antes: pl0, despues: await js(lab) }));
    if (pb) tap(...scr(pb));
    const pp = await until(`return ${labE} === 'Pausar';`, 4000);
    check('y otra vez, vuelve a sonar', pp, String(await js(lab)));
    // la barra del tiempo: se arrastra aquí (pegada al dedo) y la canción salta a donde se suelta
    const sk = await rectOf('.player .seek');
    if (sk) { const y = Math.round(T + (sk[1] + sk[3]) / 2 * D), x0 = Math.round((sk[0] + (sk[2] - sk[0]) * 0.15) * D), x1 = Math.round((sk[0] + (sk[2] - sk[0]) * 0.75) * D); sh(`input swipe ${x0} ${y} ${x1} ${y} 700`); await sleep(1200); }
    const sv = await js(`return +document.querySelector('.player .seek').value;`), sst = await st();
    check('arrastrar la barra del tiempo (nativa) mueve la canción a donde se suelta', !!sk && sv >= 640 && sv <= 860, JSON.stringify({ valor: sv, android_mando: sst && sst.rgLast, viva: sst && sst.baseLive, cancion: await js(`${O} const t = o.trackById(o.state.queue[o.state.qi]); return t && t.title;`) }));
    // subir la barra con el dedo abre el reproductor (lo hace la web, como siempre; mientras, se ve la de siempre)
    const spy = `const p = document.querySelector('.player'); window.__sw = { s: 0, m: 0, e: 0, c: 0, t: [] }; const k = n => e => { window.__sw[n]++; if (window.__sw.t.length < 12) window.__sw.t.push(n + ':' + Math.round(e.touches && e.touches[0] ? e.touches[0].clientY : (e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientY : -1))); };
      if (!window.__swOn) { window.__swOn = 1; p.addEventListener('touchstart', k('s'), { passive: true, capture: true }); p.addEventListener('touchmove', k('m'), { passive: true, capture: true }); p.addEventListener('touchend', k('e'), { capture: true }); p.addEventListener('touchcancel', k('c'), { capture: true }); } return 1;`;
    const swipeUp = async () => { await js(spy); const tr = await rectOf('.player .pl-meta-t'); if (tr) { const [x, y] = scr(tr); sh(`input swipe ${x} ${y} ${x} ${Math.max(200, y - 900)} 1400`); } return !!tr; };
    await swipeUp();
    const np = await until(`${O} return W.on.includes('np') && !document.querySelector('#np').hidden;`, 6000);
    await sleep(800); shot('base-subir-reproductor');
    const s3 = await st(), sw1 = await js(`${O} return Object.assign({}, window.__sw, { npm: { on: o.NPM && o.NPM.on, p: o.NPM && o.NPM.p }, np: !document.querySelector('#np').hidden });`);
    let ctl = null, ctl2 = null;
    if (!np) {
      // (control: con las pantallas nativas, pero sin la capa de abajo)
      await js(`${O} N.baseOk = false; o.BASE.on && 0; return 1;`); await sleep(1500);
      await swipeUp();
      const np3 = await until(`return !document.querySelector('#np').hidden && window.__orbita.W.on.includes('np');`, 5000);
      ctl2 = { abre: np3, toques: await js(`return window.__sw;`) };
      await js(`if (!document.querySelector('#np').hidden) document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(1500);
      await js(`${O} N.baseOk = true; return 1;`); await sleep(1500);
      // (control: lo mismo con la de siempre, sin la capa nativa)
      await js(`${O} N.classic = true; return 1;`); await sleep(2000);
      await swipeUp();
      const np2 = await until(`return !document.querySelector('#np').hidden;`, 5000);
      ctl = { abre: np2, toques: await js(`return window.__sw;`) };
      await js(`if (!document.querySelector('#np').hidden) document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(1500);
      await js(`${O} N.classic = false; return 1;`); await sleep(2000);
    }
    check('subir la barra con el dedo abre el reproductor (como siempre)', np, JSON.stringify({ on: await js(`${O} return W.on.join();`), base: s3 && s3.baseShown, toques: sw1, sin_capa_de_abajo: ctl2, todo_web: ctl }));
    await js(`if (!document.querySelector('#np').hidden) document.querySelector('[data-act="np-close"]').click(); return 1;`); await until(`${O} return !W.on.length;`, 5000); await sleep(1500);
    // --- con algo de la web encima, se va (y se ve la de siempre); al irse, vuelve
    await js(`document.querySelector('#dropOverlay').hidden = false; return 1;`); await sleep(1200);
    const s4 = await st();
    await js(`document.querySelector('#dropOverlay').hidden = true; return 1;`);
    const back = await until(`return (await Capacitor.Plugins.OrbitaUi.status()).baseLive === true;`, 5000);
    check('con algo de la web encima, la capa nativa se va; y vuelve cuando se va', !!s4 && !s4.baseShown && back, JSON.stringify({ con_algo: s4 && s4.baseShown, vuelve: back }));
    // --- cada fondo, quieto en el mismo punto: Android y la web, píxel a píxel
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1200);
    await goView('ajustes'); await sleep(1800);
    const pr = await rectOf('.player'); const H = await js(`return innerHeight;`);
    const setBg = async v => { await js(`const b = document.querySelector('#lkBg [data-v="${v}"]'); if (b) b.click(); return 1;`); await sleep(1500); };
    // (la luz, quieta en ese punto: la de la web y la cuenta que lleva mientras está guardada)
    const freeze = async ms => js(`window.__orbita.DRIFT.hold = ${ms}; if (!document.querySelector('#pruebaQuieta')) document.head.insertAdjacentHTML('beforeend', '<style id="pruebaQuieta">.bg::before{animation-play-state:paused!important}</style>'); await new Promise(r => requestAnimationFrame(r)); const a = document.getAnimations().find(x => x.animationName === 'drift'); if (!a) return null; a.currentTime = ${ms}; return a.playState;`);
    const par = {};
    const pair = async (name, want) => {
      await until(`const s = await Capacitor.Plugins.OrbitaUi.status(); return s.baseLive === true && ${JSON.stringify(want)}.every(w => s.baseDrawn.includes(w));`, 8000);
      await sleep(1500);
      const sn = await st(); const nat = grab(); shot(`base-${name}-nativa`);
      await js(`${O} N.classic = true; return 1;`); await until(`return (await Capacitor.Plugins.OrbitaUi.status()).baseShown === false;`, 5000); await sleep(2500);
      const web = grab(); shot(`base-${name}-web`);
      await js(`${O} N.classic = false; return 1;`); await sleep(2000);
      if (!nat || !web) { par[name] = null; return; }
      const sh0 = nat.h, top = T / sh0, py = pr ? (T + pr[1] * D) / sh0 : 0.85;
      par[name] = {
        drawn: sn && sn.baseDrawn,
        barra: vdiff(nat, web, [0, py + 0.004, 1, Math.min(1, (T + H * D) / sh0) - 0.002]),
        lados: vdiff(nat, web, [0, top + 0.01, 0.025, py - 0.01]),
        derecha: vdiff(nat, web, [0.975, top + 0.01, 1, py - 0.01]),
        entera: vdiff(nat, web, [0, top, 1, Math.min(1, (T + H * D) / sh0)])
      };
    };
    await setBg('aurora'); await freeze(23000); await pair('aurora', ['aurora:', ':quieta']);
    await setBg('quieto'); await pair('quieto', ['quieto:']);
    await setBg('negro'); await pair('negro', ['negro']);
    await js(`${O} const t = o.state.tracks.find(x => x.title === 'Luz de las siete'); if (t) o.playQueue([t.id], 0); return !!t;`); await sleep(2500);
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1200);
    await setBg('portada'); await freeze(23000); await pair('portada', ['portada:', ':portada']);
    log('  fondo y barra, Android contra la web: ' + JSON.stringify(par));
    const okF = (p, px, blk) => !!p && p.px <= px && p.blk <= blk;
    for (const [k, nm] of [['aurora', 'la aurora (quieta en el mismo punto)'], ['quieto', '«Quieto»'], ['negro', '«Negro puro»'], ['portada', 'la portada de lo que suena']]) {
      const p = par[k];
      check(`fondo ${nm}: Android lo dibuja igual que la web (los bordes, donde solo hay fondo, y la pantalla entera)`, !!p && okF(p.lados, 3, 1.5) && okF(p.derecha, 3, 1.5) && okF(p.entera, 12, 3), JSON.stringify(p && { drawn: p.drawn, lados: p.lados, derecha: p.derecha, entera: p.entera }));
      check(`la barra de abajo con el fondo ${nm}: igual que la de siempre`, !!p && okF(p.barra, 8, 2.5), JSON.stringify(p && p.barra));
    }
    await setBg('aurora'); await js(`window.__orbita.DRIFT.hold = null; const q = document.querySelector('#pruebaQuieta'); if (q) q.remove(); return 1;`); await sleep(1000);
    // con el de Android a la vista, el de la web está guardado (oculto y quieto: no gasta), y vuelve donde va el de Android
    await until(`return (await Capacitor.Plugins.OrbitaUi.status()).baseLive === true && document.documentElement.classList.contains('nui-base');`, 6000);
    const gw = await js(`const a = document.getAnimations().find(x => x.animationName === 'drift'); return { cls: document.documentElement.classList.contains('nui-base'), vis: getComputedStyle(document.querySelector('.bg')).visibility, ps: a && a.playState };`);
    check('con el fondo de Android a la vista, el de la web se guarda (oculto y quieto: no gasta)', !!gw && gw.cls && gw.vis === 'hidden' && gw.ps === 'paused', JSON.stringify(gw));
    // --- «Tu voz», nativa (leída entera, como «Buscar» y «Ajustes»)
    await goView('voz'); await sleep(2500);
    const vz = await js(`${O} return { shown: N.shown, screen: N.screen, view: o.state.view };`);
    const svz = await st(); shot('voz-nativa');
    const vzT = ui().filter(n => !n.web && n.text).map(n => n.text.toLowerCase());
    const webVz = await js(`const out = []; for (const e of document.querySelectorAll('#view-voz h1, #view-voz h2, #view-voz h3')) { const r = e.getBoundingClientRect(); if (r.height && r.top < innerHeight - 200) out.push(e.textContent.trim()); } return out.filter(Boolean).slice(0, 4);`);
    check('«Tu voz» es nativa (Android la dibuja, con sus textos), con el fondo y la barra nativos', !!vz && vz.shown && vz.screen === 'voz' && svz && svz.baseLive && (webVz || []).length > 0 && webVz.every(t => vzT.some(x => x === t.toLowerCase() || x.startsWith(t.toLowerCase().slice(0, 10)))), JSON.stringify({ vz, live: svz && svz.baseLive, web: webVz, android: vzT.slice(10, 26) }));
    await goView('inicio'); await sleep(1500);
    check('sin cierres por error con el fondo y la barra nativos', crashes() === 0, crashText().slice(0, 1500));
  };
  // ================= FASE 4 · parte 1 (5.3): el resumen del año, la bienvenida y el despertador, de Android =================
  const pass53 = async () => {
    const O = `const o = window.__orbita, N = o.NUI, W = o.W;`;
    const st = () => js(`return await Capacitor.Plugins.OrbitaUi.status();`);
    const until = async (expr, ms = 8000) => { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (await js(expr) === true) return true; await sleep(400); } return false; };
    const D = await js(`return devicePixelRatio;`);
    let T = 0; { const wv = ui().find(n => n.cls === 'android.webkit.WebView'); T = wv ? wv.b[1] : 0; }
    const rectOf = sel => js(`const e = document.querySelector('${sel}'); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`);
    const scr = r => [Math.round((r[0] + r[2]) / 2 * D), Math.round(T + (r[1] + r[3]) / 2 * D)];
    const tapWeb = async (sel, wait = 1500) => { const r = await rectOf(sel); if (r) { tap(...scr(r)); await sleep(wait); } return !!r; };
    const H = await js(`return innerHeight;`);
    const low = a => (a || []).map(t => String(t).replace(/\s+/g, ' ').trim().toLowerCase()).filter(Boolean);
    // (quieta: todo lo que se mueve, parado en la web y, con lo que la web le dice, en Android; luego Android y la web, píxel a píxel)
    const still = async id => {
      // (el resumen, además, en pausa como con el dedo encima: que no pase de página ni se llene su barra entre una foto y la otra)
      if (id === 'recap') { await js(`const z = document.querySelector('#rcTap'), r = z.getBoundingClientRect(); z.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 })); return 1;`); await sleep(800); }
      await js(`${O} for (const a of document.querySelector('${'#'}' + ({ recap: 'recap', onb: 'onb', alarm: 'alarmScr' })['${id}']).getAnimations({ subtree: true })) { try { a.pause(); } catch (e) {} } W.dirty['${id}'] = new Set(['*']); return 1;`); await sleep(2500); };
    const pairOf = async name => {
      const nat = grab(); shot(`${name}-nativa`);
      await js(`${O} N.classic = true; return 1;`); await until(`return (await Capacitor.Plugins.OrbitaUi.status()).wins === '';`, 5000); await sleep(2500);
      const web = grab(); shot(`${name}-web`);
      await js(`${O} N.classic = false; return 1;`); await sleep(2500);
      if (!nat || !web) return null;
      const top = T / nat.h, bot = Math.min(1, (T + H * D) / nat.h);
      return { entera: vdiff(nat, web, [0, top, 1, bot]), arriba: vdiff(nat, web, [0, top, 1, top + (bot - top) * 0.3]), medio: vdiff(nat, web, [0, top + (bot - top) * 0.3, 1, top + (bot - top) * 0.75]), abajo: vdiff(nat, web, [0, top + (bot - top) * 0.75, 1, bot]) };
    };
    // ---------- la bienvenida
    const f0 = await st();
    await js(`${O} o.openOnboarding(true); return 1;`);
    const up1 = await until(`${O} return W.on.includes('onb') && (await Capacitor.Plugins.OrbitaUi.status()).winPass === true;`, 8000);
    await sleep(2000); shot('bienvenida-nativa');
    const s1 = await st();
    const wT = await js(`return [...document.querySelectorAll('#onb .onb-skip, #onbStage h2, #onbStage .btn')].map(e => e.textContent.trim()).filter(Boolean);`);
    check('la bienvenida la dibuja Android (ventana nativa «de paso»), con sus textos', up1 && !!s1 && s1.winPass && low(wT).length >= 2 && low(wT).every(t => low(s1.winTexts).some(x => x === t || x.startsWith(t.slice(0, 12)))), JSON.stringify({ up1, web: wT, android: s1 && s1.winTexts }));
    const s2 = await st();
    check('lo que se mueve solo (el aro y las barras de Nova, la luz de fondo) lo mueve Android', !!s2 && s2.animFrames - (f0 ? f0.animFrames : 0) > 20, JSON.stringify({ fotogramas: s2 && f0 ? s2.animFrames - f0.animFrames : -1 }));
    await still('onb'); const pb = await pairOf('bienvenida');
    log('  bienvenida, Android contra la web: ' + JSON.stringify(pb));
    check('la bienvenida, quieta: Android la dibuja igual que la web', !!pb && pb.entera.blk <= 3 && pb.entera.px <= 9 && pb.arriba.blk <= 3.5 && pb.medio.blk <= 3.5 && pb.abajo.blk <= 3.5, JSON.stringify(pb));
    await js(`for (const a of document.querySelector('#onb').getAnimations({ subtree: true })) { try { a.play(); } catch (e) {} } return 1;`); await sleep(800);
    // lo que se toca le llega a la web: «Empezar» pasa al paso 1; «Saltar» la cierra
    const fw0 = (await st()).passFwd;
    const b1 = await tapWeb('#onbStage [data-ob="next"]', 2200);
    const ob1 = await js(`return window.__orbita.OB.step;`), s3 = await st();
    shot('bienvenida-paso-1');
    check('tocar «Empezar» en la nativa le llega a la web: pasa al paso siguiente (y Android lo dibuja)', b1 && ob1 === 1 && s3 && s3.passFwd > fw0 && low(s3.winTexts).some(t => /paso 1/.test(t)), JSON.stringify({ paso: ob1, pasados: s3 && s3.passFwd - fw0, android: s3 && s3.winTexts && s3.winTexts.slice(0, 8) }));
    await tapWeb('#onbSkip', 2000);
    const gone1 = await until(`${O} return document.querySelector('#onb').hidden && !W.on.includes('onb') && (await Capacitor.Plugins.OrbitaUi.status()).winPass === false;`, 5000);
    check('«Saltar» la cierra (la nativa se va)', gone1, JSON.stringify(await js(`${O} return { onb: !document.querySelector('#onb').hidden, on: W.on.join() };`)));
    // ---------- el despertador
    await js(`${O} o.alarmWake('prueba', 'alarm', true); return 1;`);
    const up2 = await until(`${O} return W.on.includes('alarm') && (await Capacitor.Plugins.OrbitaUi.status()).winPass === true;`, 8000);
    await sleep(2000); shot('despertador-nativo');
    const a1 = await st();
    check('el despertador lo dibuja Android, con la hora y sus botones', up2 && !!a1 && ['posponer', 'ya me levanto', 'apagar la música'].every(t => low(a1.winTexts).some(x => x.startsWith(t))), JSON.stringify({ up2, android: a1 && a1.winTexts }));
    await still('alarm'); const pa = await pairOf('despertador');
    log('  despertador, Android contra la web: ' + JSON.stringify(pa));
    check('el despertador, quieto: Android lo dibuja igual que la web', !!pa && pa.entera.blk <= 3 && pa.entera.px <= 9 && pa.arriba.blk <= 3.5 && pa.medio.blk <= 3.5 && pa.abajo.blk <= 3.5, JSON.stringify(pa));
    await js(`for (const a of document.querySelector('#alarmScr').getAnimations({ subtree: true })) { try { a.play(); } catch (e) {} } return 1;`);
    await tapWeb('#alUp', 2000);
    const gone2 = await until(`${O} return document.querySelector('#alarmScr').hidden && !W.on.includes('alarm');`, 5000);
    check('«Ya me levanto» (tocado en la nativa) lo apaga', gone2, JSON.stringify(await js(`${O} return { al: !document.querySelector('#alarmScr').hidden, on: W.on.join(), activa: o.ALM.active };`)));
    // ---------- «Tu año en Órbita»
    await js(`${O} const ts = o.state.tracks, now = Date.now(), y0 = new Date(new Date().getFullYear(), 0, 1).getTime();
      for (let i = 0; i < 60; i++) { const t = ts[i % ts.length], at = Math.max(y0 + 3600000, now - i * 3 * 3600000), d = new Date(at); o.memEvents.push({ ts: at, id: t.id, sec: 210, pct: 1, skip: false, hour: d.getHours(), dow: d.getDay(), src: 'library', title: t.title, artist: t.artist }); }
      o.openRecap(); return 1;`);
    const up3 = await until(`${O} return W.on.includes('recap') && (await Capacitor.Plugins.OrbitaUi.status()).winPass === true;`, 8000);
    await sleep(2500); shot('resumen-nativo');
    // (las barras de arriba: cuánto blanco hay en su fila, en dos fotos seguidas de lo que dibuja Android)
    const rb = await rectOf('#rcBars');
    const white = img => { if (!img || !rb) return -1; const y = Math.round(T + (rb[1] + rb[3]) / 2 * D); let n = 0; for (let x = 0; x < img.w; x++) { const i = (y * img.w + x) * 4; if (img.d[i] > 200 && img.d[i + 1] > 200 && img.d[i + 2] > 200) n++; } return n; };
    const r1 = await st(), g1 = grab(); const rw1 = await js(`const R = window.__orbita.RC; return R.i + ':' + Math.min(1, (performance.now() - R.t0) / R.dur).toFixed(2) + ':' + (R.paused ? 1 : 0);`); await sleep(1500); const g2 = grab(), r2 = await st();
    const w1 = white(g1), w2 = white(g2), rcs = await js(`const R = window.__orbita.RC; return { i: R.i, paused: R.paused, f: Math.min(1, (performance.now() - R.t0) / R.dur) };`);
    check('«Tu año en Órbita» lo dibuja Android, y sus barras de arriba se llenan solas', up3 && !!r1 && !!r2 && r1.winPass && (r1.winTexts || []).length > 2 && (w2 > w1 + 4 || (rcs && rcs.f >= 1)), JSON.stringify({ up3, blanco: [w1, w2], web: rcs, web_antes: rw1, android_barras: [r1 && r1.rcLast, r2 && r2.rcLast], android: r1 && r1.winTexts && r1.winTexts.slice(0, 8) }));
    // tocar a la derecha pasa de página (le llega a la web)
    await sleep(600);
    const i0 = await js(`return window.__orbita.RC.i;`), pf0 = (await st()).passFwd;
    { const r = await rectOf('#rcTap'); if (r) tap(Math.round((r[0] + (r[2] - r[0]) * 0.85) * D), Math.round(T + (r[1] + (r[3] - r[1]) * 0.5) * D)); }
    await until(`return window.__orbita.RC.i > ${i0};`, 3000); await sleep(1200);
    const i1 = await js(`return window.__orbita.RC.i;`), r3 = await st();
    log('  (toques que la ventana nativa le ha pasado a la web: ' + (r3 ? r3.passFwd - pf0 : '?') + ')');
    shot('resumen-pagina-2');
    check('tocar a la derecha en la nativa pasa de página (le llega a la web) y Android lo dibuja', i1 > i0 && !!r3 && r3.winPass, JSON.stringify({ antes: i0, despues: i1, android: r3 && r3.winTexts && r3.winTexts.slice(0, 6) }));
    await still('recap'); const pr = await pairOf('resumen');
    log('  resumen, Android contra la web: ' + JSON.stringify(pr));
    check('«Tu año en Órbita», quieto: Android lo dibuja igual que la web', !!pr && pr.entera.blk <= 3 && pr.entera.px <= 9 && pr.arriba.blk <= 3.5 && pr.medio.blk <= 3.5 && pr.abajo.blk <= 3.5, JSON.stringify(pr));
    await js(`for (const a of document.querySelector('#recap').getAnimations({ subtree: true })) { try { a.play(); } catch (e) {} } document.querySelector('#rcTap').dispatchEvent(new PointerEvent('pointercancel', { bubbles: true })); return 1;`);
    await tapWeb('#rcClose', 2000);
    const gone3 = await until(`${O} return document.querySelector('#recap').hidden && !W.on.includes('recap');`, 5000);
    check('su «x» (tocada en la nativa) lo cierra', gone3, JSON.stringify(await js(`${O} return { rc: !document.querySelector('#recap').hidden, on: W.on.join() };`)));
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(800);
    check('sin cierres por error con el resumen, la bienvenida y el despertador nativos', crashes() === 0, crashText().slice(0, 1500));
  };
  if (SOLO_BASE) {
    await goView('ajustes'); await sleep(1000);
    const t0 = await js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); if (b.getAttribute('aria-pressed') !== 'true') b.click(); await new Promise(r => setTimeout(r, 400)); return b.getAttribute('aria-pressed');`);
    check('prueba rápida: las pantallas nativas, encendidas', t0 === 'true', t0);
    await goView('inicio'); await sleep(3000);
    await base53(); await pass53(); saveLogs(); finish('prueba rápida: solo el fondo, la barra, el resumen, la bienvenida y el despertador');
  }
  if (SOLO_CEREBRO) {
    await goView('ajustes'); await sleep(1000);
    const t0 = await js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); if (b.getAttribute('aria-pressed') !== 'true') b.click(); await new Promise(r => setTimeout(r, 400)); return b.getAttribute('aria-pressed');`);
    check('prueba rápida: las pantallas nativas, encendidas', t0 === 'true', t0);
    await goView('inicio'); await sleep(3000);
    await cerebro52(); saveLogs(); finish('prueba rápida: solo el cerebro');
  }
  if (SOLO_VOZ) {
    await goView('ajustes'); await sleep(1000);
    const t0 = await js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); if (b.getAttribute('aria-pressed') !== 'true') b.click(); await new Promise(r => setTimeout(r, 400)); return b.getAttribute('aria-pressed');`);
    check('prueba rápida: las pantallas nativas, encendidas', t0 === 'true', t0);
    await goView('inicio'); await sleep(3000);
    await voz51(); saveLogs(); finish('prueba rápida: solo la voz');
  }
  if (SOLO_LY) {
    await goView('ajustes'); await sleep(1000);
    const t0 = await js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); if (b.getAttribute('aria-pressed') !== 'true') b.click(); await new Promise(r => setTimeout(r, 400)); return b.getAttribute('aria-pressed');`);
    check('prueba rápida: las pantallas nativas, encendidas', t0 === 'true', t0);
    await goView('inicio'); await sleep(3000);
    await ly50(); await yt50(); saveLogs(); finish('prueba rápida: solo la letra y el videoclip');
  }
  if (SOLO_VIZ) {
    await goView('ajustes'); await sleep(1000);
    const t0 = await js(`const b = document.querySelector('#tNui'); if (!b) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); if (b.getAttribute('aria-pressed') !== 'true') b.click(); await new Promise(r => setTimeout(r, 400)); return b.getAttribute('aria-pressed');`);
    check('prueba rápida: las pantallas nativas, encendidas', t0 === 'true', t0);
    await goView('inicio'); await sleep(3000);
    await viz49(); saveLogs(); finish('prueba rápida: solo los visualizadores');
  }

  // ================= FASE 2 · «Mi música» nativa (Compose) =================
  // --- la pantalla de siempre: captura y medidas exactas de cada cosa (para que la nativa sea igual)
  await goView('biblioteca'); await sleep(1500);
  const WEBQ = `const q = (s, r) => (r || document).querySelector(s);
    const R = el => { if (!el) return null; const r = el.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom].map(v => Math.round(v * 100) / 100); };
    const T = el => { if (!el) return null; const g = document.createRange(); g.selectNodeContents(el); const r = g.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom].map(v => Math.round(v * 100) / 100); };
    const v = q('#view-biblioteca');
    return { dpr: devicePixelRatio, scroll: q('#main').scrollTop, main: R(q('#main')), h1: R(q('h1', v)), h1t: T(q('h1', v)), meta: T(q('#libMeta')), add: R(q('#addLabel')), addT: T(q('#addLabel')), warn: R(q('.warnbox', v)), dev: R(q('#devBox')), devBtn: R(q('#devBox button')), devTitle: T(q('#devBox b')), devText: T(q('#devBox .d-text span')),
      search: R(q('.toolbar .search', v)), searchIn: R(q('#search')), seg: R(q('#filterSeg')), segBtns: [...document.querySelectorAll('#filterSeg button')].filter(b => !b.hidden).map(R), sort: R(q('#sortSel')), playAll: R(q('#btnPlayAll')), playAllT: T(q('#btnPlayAll')), colhd: R(q('.col-hd', v)), colT: T(q('.col-hd span', v)),
      rows: [...document.querySelectorAll('#libRows .row')].slice(0, 5).map(r => ({ row: R(r), cover: R(q('.cover', r)), title: T(q('.t-title', r)), sub: R(q('.t-sub', r)), dur: T(r.querySelectorAll('.num')[1]), like: R(q('[data-act="like"]', r)), acts: [...r.querySelectorAll('.acts button')].map(R) })),
      demo: R(q('#demoBar')), demoNote: T(q('#demoBar .note')), demoBtn: R(q('#demoBar button')), total: q('#main').scrollHeight };`;
  await js(`document.querySelector('#main').scrollTop = 0; return 1;`); await sleep(700); ui(); await sleep(300); shot('par-web-arriba');
  const web0 = await js(WEBQ);
  await js(`document.querySelector('#main').scrollTop = 99999; return 1;`); await sleep(900); ui(); await sleep(300); shot('par-web-abajo');
  const web1 = await js(WEBQ);
  await js(`document.querySelector('#main').scrollTop = 0; return 1;`);
  // --- se enciende con su interruptor
  await goView('ajustes'); await sleep(1000);
  const tog = await js(`const b = document.querySelector('#tNui'); if (!b || document.querySelector('#nuiRow').hidden) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); b.click(); await new Promise(r => setTimeout(r, 300)); return b.getAttribute('aria-pressed') + ' · ' + document.querySelector('#nuiNote').textContent;`);
  await sleep(600); shot('ajustes-interruptor');
  check('Ajustes: el interruptor de «Mi música» nativa se enciende', /^true/.test(String(tog)), tog);
  await goView('biblioteca'); await sleep(2500);
  const nui1 = await js(`const N = window.__orbita.NUI; return { on: N.on, shown: N.shown, err: N.err, rect: N.rect };`);
  const nscroll = async to => { await js(`await Capacitor.Plugins.OrbitaUi.scroll({ to: '${to}' }); return 1;`); await sleep(900); };
  ui(); await sleep(400); shot('par-nativa-arriba');
  check('«Mi música» nativa a la vista', nui1 && nui1.on && nui1.shown && !nui1.err, JSON.stringify(nui1));
  check('sin cierres al abrir la pantalla nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
  const txt = nodes => nodes.filter(n => n.text).map(n => ({ t: n.text.slice(0, 40), b: n.b }));
  let nodes = ui();
  const nat0 = txt(nodes);
  shot('par-nativa-arriba-2');
  check('la pantalla nativa enseña el título, la música del móvil y los filtros', ['BIBLIOTECA', 'Añadir canciones', 'Música del móvil', 'Todas', 'Favoritas', 'Del móvil', 'Reproducir todo'].every(t => find(nodes, t)), nodes.filter(n => n.text).map(n => n.text.slice(0, 30)).join(' | '));
  // hasta el final de la lista
  sh('input swipe 540 1450 540 350 250'); await sleep(1200);   // (un deslizamiento de verdad…)
  await nscroll('end'); ui(); await sleep(300); shot('par-nativa-abajo');   // (…y al final exacto, para comparar con la web)
  nodes = ui(); const nat1 = txt(nodes);
  {
    // misma posición que la web: el botón de las demos, a la misma distancia del borde de abajo
    const btn = find(nodes, 'Ocultar demos'), wv = nodes.find(n => n.cls === 'android.webkit.WebView');
    const top = wv ? wv.b[1] : 0, dpr = web1.dpr, natY = btn ? ((btn.b[1] + btn.b[3]) / 2 - top) / dpr : -1, webY = web1.demoBtn ? (web1.demoBtn[1] + web1.demoBtn[3]) / 2 : -2;
    check('al final de la lista, todo queda donde en la pantalla de siempre (± 3)', btn && Math.abs(natY - webY) <= 3, `nativa ${natY.toFixed(1)} · web ${webY.toFixed(1)}`);
  }
  fs.writeFileSync(`${OUT}/paridad.json`, JSON.stringify({ web0, web1, nat0, nat1 }));
  const seen = titlesOnScreen(nodes);
  check('se ven las canciones', seen.length >= 3, seen.join(' | '));
  const cov = await js(`return await Capacitor.Plugins.OrbitaUi.status();`);
  check('las portadas llegan a la pantalla nativa', cov && cov.covers >= 1 && cov.songs >= songs.length, JSON.stringify(cov));
  // --- tocar una canción: suena esa
  const pick = seen.includes('Pulso') ? 'Pulso' : seen[0];
  await tapText(pick, 4000);
  const cur = await js(`const o = window.__orbita, t = o.trackById(o.state.queue[o.state.qi]); return { title: t && t.title, paused: o.decks[o.A].paused, q: o.state.queue.length };`);
  shot('nativa-sonando');
  check('tocar una canción en la pantalla nativa la pone', cur && cur.title === pick && cur.paused === false && playing(), JSON.stringify(cur) + ' · ' + sessState());
  // --- el corazón de esa fila
  const geo = await js(`const r = document.querySelector('#main').getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height, dpr: devicePixelRatio };`);
  // un botón de la fila de una canción, por su nombre accesible (los que leen los lectores de pantalla)
  const rowBtn = (title, re) => { const ns = ui(), t = find(ns, title); if (!t) return null; const cy = center(t)[1] + 10 * geo.dpr; return ns.find(n => re.test(n.desc) && n.b[1] <= cy && n.b[3] >= cy) || null; };
  const tapBtn = async (title, re, wait = 1500) => { const n = rowBtn(title, re); if (!n) { log('  (no encuentro el botón ' + re + ' de ' + title + ')'); return false; } tap(...center(n)); await sleep(wait); return true; };
  if (await tapBtn(pick, /^Favorita$/)) {
    const l1 = await js(`const o = window.__orbita, t = o.trackById(o.state.queue[o.state.qi]); return !!(t && t.title === ${JSON.stringify(pick)} && t.liked);`);
    shot('nativa-favorita');
    check('el corazón marca la canción como favorita', l1 === true, l1);
  } else check('encuentro la fila para probar el corazón', false, pick);
  // (con la música en pausa, mirar la pantalla tarda mucho menos: Android espera a que nada se mueva)
  sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
  // --- filtro «Favoritas»
  const toTop = async () => { await nscroll('top'); };
  const toTools = async () => { await toTop(); sh(`input swipe 540 1500 540 ${Math.round(1500 - 340 * geo.dpr)} 1600`); await sleep(1300); };
  await toTools();
  await tapText('Favoritas', 1500);
  const fav = titlesOnScreen(ui()); shot('nativa-favoritas');
  check('el filtro «Favoritas» deja solo la favorita', fav.length === 1 && fav[0] === pick, fav.join(' | '));
  await tapText('Todas', 1500);
  // --- ordenar por título (el desplegable)
  const selN = find(ui(), 'Recientes');
  if (selN) {
    tap(...center(selN)); await sleep(1500); shot('nativa-ordenar');
    tap(selN.b[0] + 60, selN.b[1] + (36 + 6 + 43.75 * 1.5 - 7) * geo.dpr); await sleep(1500);
    const ord = titlesOnScreen(ui());
    const sorted = ord.slice().sort((a, b) => a.localeCompare(b, 'es', { sensitivity: 'base', numeric: true }));
    shot('nativa-por-titulo');
    check('ordenar por título', ord.length >= 3 && JSON.stringify(ord) === JSON.stringify(sorted) && !!find(ui(), 'Título'), ord.join(' | '));
    const pref = await js(`return { sort: window.__orbita.state.sort, guardado: JSON.parse(localStorage.getItem('orbita:sort') || 'null') };`);
    check('el orden elegido se guarda en la app', pref && pref.sort === 'title' && pref.guardado === 'title', JSON.stringify(pref));
  } else check('encuentro el desplegable de orden', false);
  // --- buscar (al tocar la caja, la lista sube para que se vean los resultados con el teclado abierto)
  await toTools();
  if (await tapText('Filtrar por título', 2000)) {
    sh('input text kora'); await sleep(2500); shot('nativa-buscar');
    const ns = ui(), hit = titlesOnScreen(ns), box = find(ns, 'kora'), wv = ns.find(n => n.cls === 'android.webkit.WebView');
    const boxY = box && wv ? (box.b[1] - wv.b[1]) / geo.dpr : -1;
    check('buscar «kora»: la caja sube arriba del todo y se ven resultados con el teclado abierto', hit.length >= 1 && hit.every(t => ['Pulso', 'Luz de las siete'].includes(t)) && boxY >= 0 && boxY < 40, hit.join(' | ') + ' · la caja queda a ' + boxY.toFixed(1) + ' del borde');
    sh('input keyevent KEYCODE_ENTER'); await sleep(1500); shot('nativa-buscar-sin-teclado');
    const kb = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
    const hit2 = titlesOnScreen(ui());
    check('«buscar» en el teclado lo cierra y quedan las dos canciones de Kora', !kb && hit2.length === 2 && hit2.includes('Pulso') && hit2.includes('Luz de las siete'), 'teclado ' + kb + ' · ' + hit2.join(' | '));
    // la ✕ de la caja la vacía
    const xb = ui().find(n => n.desc === 'Borrar la búsqueda');
    if (xb) { tap(...center(xb)); await sleep(1500); } else log('  (no encuentro la ✕ de la búsqueda)');
    check('la ✕ vacía la búsqueda', titlesOnScreen(ui()).length >= 3 && !!find(ui(), 'Filtrar por título'), titlesOnScreen(ui()).join(' | '));
  } else check('encuentro la caja de búsqueda', false);
  // --- editar: se abre el diálogo de la app y la pantalla nativa se aparta
  nodes = ui(); const r2 = nodes.filter(n => SONGS.includes(n.text) && nodes.some(m => /^Editar/.test(m.desc) && m.b[1] <= center(n)[1] + 10 * geo.dpr && m.b[3] >= center(n)[1] + 10 * geo.dpr))[0];
  if (!r2) check('encuentro una canción con botón de editar', false, titlesOnScreen(nodes).join(' | '));
  if (r2 && await tapBtn(r2.text, /^Editar/, 1800)) {
    const ed = await js(`return { shown: window.__orbita.NUI.shown, win: window.__orbita.W.on.join(), dialog: !document.querySelector('#editOverlay').hidden, titulo: document.querySelector('#editTitle').value };`);
    shot('nativa-editar');
    check('«editar» abre la ventana (nativa, con esa canción) y «Mi música» nativa sigue debajo', ed && ed.shown === true && ed.win === 'edit' && ed.dialog === true && ed.titulo === r2.text && !!find(ui(), 'Editar canción'), JSON.stringify(ed));
    await js(`document.querySelector('#editCancel').click(); if (document.activeElement) document.activeElement.blur(); return 1;`); await sleep(2000);
    const ed2 = await js(`return { shown: window.__orbita.NUI.shown, kb: 0 };`);
    shot('nativa-tras-editar');
    check('al cerrar el diálogo, la pantalla nativa vuelve', ed2 && ed2.shown === true, JSON.stringify(ed2));
    // borrar pide un segundo toque
    const n0 = await js(`return window.__orbita.state.tracks.length;`);
    const trash = rowBtn(r2.text, /^(Quitar de Órbita|Borrar$)/);
    if (trash) {
      tap(...center(trash)); await sleep(350); shot('nativa-borrar');
      const t0 = Date.now(), armN = ui(), dt = Date.now() - t0, arm = !!find(armN, '¿Borrar?');
      const n1 = await js(`return window.__orbita.state.tracks.length;`);
      if (arm || dt < 2500) check('la papelera pide confirmar («¿Borrar?»)', arm, 'mirar la pantalla tardó ' + dt + ' ms');
      else log('  (no da tiempo a comprobar «¿Borrar?» mirando la pantalla: tardó ' + dt + ' ms; se ve en la captura nativa-borrar)');
      check('un solo toque en la papelera no borra nada', n1 === n0, `${n0} → ${n1}`);
      // lo demás de la fila no se mueve mientras pregunta
      const durA = nodes.find(n => /^\d:\d\d$/.test(n.text) && Math.abs(center(n)[1] - center(r2)[1]) < 14 * geo.dpr), durB = armN.find(n => /^\d:\d\d$/.test(n.text) && Math.abs(center(n)[1] - center(r2)[1]) < 14 * geo.dpr);
      if (arm) check('mientras pregunta, la fila no se descoloca', durA && durB && durA.b[0] === durB.b[0] && durA.b[2] === durB.b[2], JSON.stringify([durA && durA.b, durB && durB.b]));
      await sleep(3400);
      check('si no se confirma, a los 3 s vuelve a su sitio', !find(ui(), '¿Borrar?') && !!rowBtn(r2.text, /^(Quitar de Órbita|Borrar$)/));
      // dos toques seguidos (papelera y «¿Borrar?»): ahora sí se quita
      const trash2 = rowBtn(r2.text, /^(Quitar de Órbita|Borrar$)/);
      if (trash2) {
        tap(...center(trash2)); await sleep(500); tap(...center(trash2)); await sleep(2500);
        const n2 = await js(`return window.__orbita.state.tracks.length;`), still = await js(`return window.__orbita.state.tracks.some(t => t.dev && t.title === ${JSON.stringify(r2.text)});`);
        shot('nativa-borrada');
        check('papelera y luego «¿Borrar?»: la canción se quita de Órbita', n2 === n0 - 1 && still === false && !titlesOnScreen(ui()).includes(r2.text) , `${n0} → ${n2}`);
      }
    } else check('encuentro la papelera', false, r2.text);
  }
  // --- aviso corto encima de la lista
  await js(`window.__orbita.toast('Aviso de prueba encima de la lista', 15000); return 1;`); await sleep(500);
  const t0 = Date.now(), seenToast = !!find(ui(), 'Aviso de prueba'); shot('nativa-aviso');
  check('los avisos de la app se ven encima de la pantalla nativa', seenToast, 'mirar la pantalla tardó ' + (Date.now() - t0) + ' ms');
  await js(`window.__orbita.toast('Listo', 900); return 1;`); await sleep(1800);
  // --- otra pestaña y volver
  await goView('buscar'); await sleep(1000);
  const off = await js(`const N = window.__orbita.NUI; return N.shown ? N.screen : 'ninguna';`); shot('nativa-otra-pestana');
  await goView('biblioteca'); await sleep(1500);
  const on2 = await js(`const N = window.__orbita.NUI; return N.shown ? N.screen : 'ninguna';`);
  check('al cambiar de pestaña cambia la pantalla nativa, y al volver vuelve «Mi música»', off === 'buscar' && on2 === 'biblioteca', `${off} → ${on2}`);
  check('al volver de otra pestaña, la lista empieza arriba (como la de siempre)', !!find(ui(), 'BIBLIOTECA'));
  // --- el reproductor a pantalla completa por encima
  await js(`document.querySelector('[data-act="np-open"]').click(); return 1;`); await sleep(700); shot('nativa-reproductor-abriendo'); await sleep(1800);
  const pl = await js(`return { shown: window.__orbita.NUI.shown, np: !document.querySelector('#np').hidden };`); shot('nativa-reproductor');
  check('el reproductor a pantalla completa se abre por encima (la nativa se aparta)', pl && pl.shown === false && pl.np === true, JSON.stringify(pl));
  await js(`document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(2200);
  const pl2 = await js(`return { shown: window.__orbita.NUI.shown, np: !document.querySelector('#np').hidden, view: window.__orbita.state.view };`); shot('nativa-reproductor-cerrado');
  check('al cerrarlo, la nativa vuelve', pl2 && pl2.shown === true && pl2.np === false, JSON.stringify(pl2));
  // --- «Personaliza Órbita»: bordes rectos, letra grande y títulos retro; la pantalla nativa cambia igual
  await goView('ajustes'); await sleep(900);
  const lk = await js(`const c = (k, v) => { const b = document.querySelector('[data-look="' + k + '"][data-v="' + v + '"]'); if (b) b.click(); return !!b; }; return [c('round', 'recto'), c('text', 'l'), c('titles', 'retro')];`);
  await goView('biblioteca'); await sleep(1800);
  const big = find(ui(), 'BIBLIOTECA'); shot('nativa-otro-aspecto');
  check('el aspecto elegido en «Personaliza Órbita» llega a la pantalla nativa (letra más grande)', Array.isArray(lk) && lk.every(Boolean) && big && (big.b[3] - big.b[1]) > 31.92 * 1.05 * geo.dpr, JSON.stringify(lk) + ' · alto del título ' + (big ? big.b[3] - big.b[1] : '?') + ' px');
  await goView('ajustes'); await sleep(700);
  await js(`const c = (k, v) => { const b = document.querySelector('[data-look="' + k + '"][data-v="' + v + '"]'); if (b) b.click(); }; c('round', 'redondo'); c('text', 'm'); c('titles', 'orbita'); return 1;`);
  await goView('biblioteca'); await sleep(1500);
  // --- fluidez al deslizar la lista
  sh(`dumpsys gfxinfo ${PKG} reset`);
  for (let k = 0; k < 4; k++) { sh('input swipe 540 1450 540 400 250'); await sleep(700); sh('input swipe 540 400 540 1450 250'); await sleep(700); }
  log('fluidez de la lista nativa:', gfx());
  fs.writeFileSync(`${OUT}/gfx.txt`, adb(`shell dumpsys gfxinfo ${PKG}`).slice(0, 6000));
  await js(`window.__orbita.NUI.classic = true; return 1;`); await sleep(1200);
  sh(`dumpsys gfxinfo ${PKG} reset`);
  for (let k = 0; k < 4; k++) { sh('input swipe 540 1450 540 400 250'); await sleep(700); sh('input swipe 540 400 540 1450 250'); await sleep(700); }
  log('fluidez de la lista de siempre (mismo gesto):', gfx());
  await js(`window.__orbita.NUI.classic = false; document.querySelector('#main').scrollTop = 0; return 1;`); await sleep(1500);
  shot('nativa-final');
  check('sin cierres por error en las pruebas de la pantalla nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
  // --- «Añadir canciones»: el selector de archivos de Android y la importación de siempre
  await goView('biblioteca'); await sleep(1200); await nscroll('top');
  const nB = await js(`return window.__orbita.state.tracks.length;`);
  if (await tapText('Añadir canciones', 4500)) {
    const topAct = () => sh('dumpsys activity activities | grep -m1 topResumedActivity').trim();
    const inPicker = /documentsui/i.test(topAct());
    let fileN = null;
    if (inPicker) {
      // «Recent» suele estar vacío en el emulador: se prueba la etiqueta «Audio» y, si no, el almacenamiento del móvil › Music
      const look = () => { const ns = ui(); fileN = ns.find(n => /^c\d\.mp3$/.test(n.text)) || null; return ns; };
      let ns = look();
      if (!fileN) { const chip = ns.find(n => n.text === 'Audio'); if (chip) { tap(...center(chip)); await sleep(3000); ns = look(); shot('selector-audio'); } }
      if (!fileN) {
        const roots = ns.find(n => /Show roots/i.test(n.desc));
        if (roots) {
          tap(...center(roots)); await sleep(2000); ns = ui(); shot('selector-sitios');
          const dev = ns.find(n => /sdk_gphone|Android SDK|^Pixel|emulator/i.test(n.text));
          if (dev) { tap(...center(dev)); await sleep(3000); ns = ui(); const mus = ns.find(n => n.text === 'Music'); if (mus) { tap(...center(mus)); await sleep(3000); ns = look(); } }
        }
      }
      if (!fileN) log('  (lo que enseña el selector: ' + ns.filter(n => n.text).map(n => n.text.slice(0, 24)).join(' | ').slice(0, 500) + ')');
    }
    shot('selector-de-archivos');
    check('«Añadir canciones» abre el selector de archivos de Android', inPicker, topAct().slice(0, 200));
    if (fileN) {
      tap(...center(fileN)); await sleep(3000);
      let nA = nB; for (let k = 0; k < 14 && !(nA > nB); k++) { await sleep(1500); nA = await js(`return window.__orbita.state.tracks.length;`); }
      await sleep(2500); ui(); shot('tras-importar');
      const nuevo = await js(`const o = window.__orbita, t = o.state.tracks.filter(t => !t.dev && !t.demo).pop(); return t ? { id: t.id, title: t.title, artist: t.artist, shown: o.NUI.shown } : null;`);
      check('la canción elegida en el selector entra en la biblioteca', nA === nB + 1 && !!nuevo, `${nB} → ${nA} · ${JSON.stringify(nuevo)}`);
      // se quita otra vez (por el mismo camino que la papelera de la pantalla nativa)
      if (nuevo && nuevo.id) { await js(`window.__orbita.nuiEvent({ type: 'del', id: ${JSON.stringify(nuevo.id)} }); return 1;`); await sleep(1500); }
      const nC = await js(`return window.__orbita.state.tracks.length;`);
      check('borrar desde la pantalla nativa la quita de la biblioteca', nC === nB, `${nA} → ${nC}`);
    } else {
      log('  (el selector no enseña los archivos de prueba a la primera; la importación no se prueba aquí)');
      for (let k = 0; k < 5 && /documentsui/i.test(topAct()); k++) { sh('input keyevent KEYCODE_BACK'); await sleep(1800); }
    }
    for (let k = 0; k < 5 && /documentsui/i.test(topAct()); k++) { sh('input keyevent KEYCODE_BACK'); await sleep(1800); }
    check('tras el selector, la app sigue abierta, delante, y la pantalla nativa vuelve', alive() && !/documentsui/i.test(topAct()) && (await js(`await new Promise(r => setTimeout(r, 600)); return window.__orbita.NUI.shown;`)) === true, topAct().slice(0, 160));
  } else check('encuentro «Añadir canciones»', false);
  // --- como recién instalada: la pantalla nativa viene encendida sin tocar nada, y la app arranca con ella
  await js(`localStorage.setItem('pruebaDeFabrica', '1'); localStorage.removeItem('orbita:nativeUi'); localStorage.removeItem('orbita:nativeUiBroken'); return 1;`);
  await webStart(true); await sleep(3000);
  await goView('biblioteca'); await sleep(2500);
  const fab = await js(`const N = window.__orbita.NUI; return { on: N.on, shown: N.shown, err: N.err, guardado: localStorage.getItem('orbita:nativeUi') };`);
  const fabN = ui(); shot('nativa-de-fabrica');
  check('recién instalada, «Mi música» nativa viene encendida sin tocar nada', fab && fab.on && fab.shown && !fab.err && fab.guardado == null && !!find(fabN, 'BIBLIOTECA') && titlesOnScreen(fabN).length + (find(fabN, 'Reproducir todo') ? 1 : 0) >= 1, JSON.stringify(fab));
  check('sin cierres por error tras reiniciar la parte web con la pantalla nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
  await goView('inicio'); await sleep(800);
  // ================= fin de la fase 2 =================

  // ================= FASE 2 · paso 2 (4.5): «Ahora», nativa =================
  {
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    await goView('inicio'); await sleep(1500);
    const O = `const o = window.__orbita, N = o.NUI;`;
    const st = async () => js(`${O} return { shown: N.shown, screen: N.screen, homeOk: N.homeOk, err: N.err, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    const h0 = await st();
    let hn = ui(); shot('ahora-nativa');
    check('«Ahora» sale nativa al entrar', !!h0 && h0.shown && h0.screen === 'inicio' && h0.st.screen === 'inicio' && h0.st.home === true && h0.st.shown === true && ['ÓRBITA FM', 'DJ Nova', 'Voz', 'Conversación'].every(t => find(hn, t)), JSON.stringify(h0) + ' · ' + hn.filter(n => n.text).map(n => n.text.slice(0, 24)).join(' | ').slice(0, 300));
    check('sin cierres al abrir «Ahora» nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
    // --- cada cosa, donde en la pantalla de siempre (± 3), a varias alturas. (Sin animaciones mientras se mide: así mirar la pantalla no tarda.)
    await js(`document.documentElement.classList.add('lite'); return 1;`); await sleep(600);
    const dprH = await js(`return devicePixelRatio;`);
    const HQ = `const m = document.querySelector('#main'), mr = m.getBoundingClientRect(), out = [];
      const w = document.createTreeWalker(document.querySelector('#view-inicio'), NodeFilter.SHOW_TEXT);
      for (let n = w.nextNode(); n; n = w.nextNode()) {
        const p = n.parentElement; if (!p || !n.textContent.trim() || p.closest('.ticker, select, [hidden], canvas')) continue;
        const cs = getComputedStyle(p); if (cs.display === 'none') continue;
        const g = document.createRange(); g.selectNodeContents(n); const r = g.getBoundingClientRect(); if (!r.width || r.top < mr.top + 6 || r.bottom > mr.bottom - 6) continue;
        let t = n.textContent.replace(/\\s+/g, ' ').trim(); if (cs.textTransform === 'uppercase') t = t.toUpperCase();
        out.push({ t: t.slice(0, 60), x: +(r.left).toFixed(1), y: +((r.top + r.bottom) / 2 - mr.top).toFixed(1), h: +r.height.toFixed(1) });
      }
      return { scroll: m.scrollTop, total: m.scrollHeight, view: mr.height, list: out };`;
    const par = [];
    const bad = [];
    let totalWeb = 0, lastY = 0;
    for (const y of [0, 350, 700, 1050, 1400, 1750, 2100, 2450, 2800, 99999]) {
      await js(`${O} N.classic = true; await new Promise(r => setTimeout(r, 500)); document.querySelector('#main').scrollTop = ${y}; return 1;`); await sleep(500);
      const w = await js(HQ); if (!w) continue;
      totalWeb = w.total; if (y !== 0 && w.scroll === lastY) continue; lastY = w.scroll;
      if (y === 0 || y === 99999) { ui(); await sleep(200); shot('ahora-web-' + (y ? 'abajo' : 'arriba')); }
      await js(`${O} N.classic = false; await new Promise(r => setTimeout(r, 700)); await Capacitor.Plugins.OrbitaUi.scroll({ y: ${w.scroll} }); return 1;`); await sleep(900);
      const ns = ui(), wv = ns.find(n => n.cls === 'android.webkit.WebView'), top = wv ? wv.b[1] : 0;
      if (y === 0 || y === 99999) shot('ahora-nativa-' + (y ? 'abajo' : 'arriba'));
      const cnt = {}; for (const e of w.list) cnt[e.t] = (cnt[e.t] || 0) + 1;
      for (const e of w.list) {
        if (cnt[e.t] > 1 || e.t.length < 3 || /^\d\d:\d\d$/.test(e.t)) continue;   // (la hora puede cambiar de minuto entre una medida y la otra)
        const c = ns.filter(n => n.text && n.text.slice(0, 60) === e.t); if (c.length !== 1) { par.push({ at: w.scroll, t: e.t, falta: c.length }); if (!c.length) bad.push(`«${e.t.slice(0, 26)}» no está`); continue; }
        const nx = c[0].b[0] / dprH, ny = ((c[0].b[1] + c[0].b[3]) / 2 - top) / dprH, dx = +(nx - e.x).toFixed(1), dy = +(ny - e.y).toFixed(1);
        par.push({ at: w.scroll, t: e.t, web: [e.x, e.y], nat: [+nx.toFixed(1), +ny.toFixed(1)], dx, dy });
        if (Math.abs(dy) > 3 || Math.abs(dx) > 3) bad.push(`«${e.t.slice(0, 26)}» ${dx}/${dy}`);
      }
    }
    fs.writeFileSync(`${OUT}/paridad-ahora.json`, JSON.stringify({ totalWeb, par }));
    const okN = par.filter(e => e.nat).length;
    check('«Ahora»: cada texto, donde en la pantalla de siempre (± 3), de arriba abajo', okN >= 30 && bad.length === 0, `${okN} comparados · ${bad.length} fuera: ${[...new Set(bad)].slice(0, 14).join(' · ')}`);
    await js(`document.documentElement.classList.remove('lite'); ${O} N.classic = false; return 1;`); await sleep(700);
    const top0 = async () => { await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: 0 }); return 1;`); await sleep(900); };
    const toY = async y => { await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: ${y} }); return 1;`); await sleep(900); };
    await top0();
    // --- los interruptores
    await tapText('Presenta cada canción', 1200);
    const in1 = await js(`return JSON.parse(localStorage.getItem('orbita:intros'));`);
    await tapText('Presenta cada canción', 2600);
    const in2 = await js(`return JSON.parse(localStorage.getItem('orbita:intros'));`);
    check('«Presenta cada canción» se enciende y se apaga', in1 === true && in2 === false, `${in1} → ${in2}`);
    // --- escribirle a Nova
    const yAsk = await js(`const m = document.querySelector('#main'), e = document.querySelector('#djInput'); return Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 140));`);
    await toY(yAsk); hn = ui();
    const box = hn.find(n => n.desc === 'Pídele algo a la DJ');
    if (box) {
      tap(box.b[2] - 60, center(box)[1]); await sleep(2200); sh('input text ayuda'); await sleep(1500);
      const kb = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
      hn = ui(); shot('ahora-teclado');
      const box2 = hn.find(n => n.desc === 'Pídele algo a la DJ'), wv2 = hn.find(n => n.cls === 'android.webkit.WebView');
      check('al escribirle, sale el teclado y la caja se sigue viendo', kb && !!box2 && !!wv2 && box2.b[3] > box2.b[1] && box2.b[3] <= wv2.b[3] + 4, `teclado ${kb} · caja ${box2 ? box2.b.join(',') + ' «' + box2.text + '»' : '?'} · app ${wv2 ? wv2.b.join(',') : '?'}`);
      sh('input keyevent KEYCODE_ENTER'); await sleep(4500);
      const kb3 = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
      hn = ui(); shot('ahora-pedido');
      check('al mandarlo, Nova contesta en su recuadro y el teclado baja', !!find(hn, 'Puedes pedirme') && !kb3, 'teclado ' + kb3 + ' · ' + hn.filter(n => n.text.length > 40).map(n => n.text.slice(0, 50)).join(' | ').slice(0, 200));
      if (kb3) { sh('input keyevent KEYCODE_BACK'); await sleep(1200); }
    } else check('encuentro la caja para pedirle algo a Nova', false, hn.filter(n => n.desc).map(n => n.desc).join(' | ').slice(0, 300));
    // --- un desplegable
    await top0();
    {
      const cur = await js(`const s = document.querySelector('#mixSel'); return { v: s.value, t: s.selectedOptions[0].textContent, otra: [...s.options].find(x => x.value !== s.value).textContent, i: [...s.options].findIndex(x => x.value !== s.value) };`);
      const ySel = await js(`const m = document.querySelector('#main'), e = document.querySelector('#mixSel'); return Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 200));`);
      await toY(ySel); hn = ui();
      const selN = find(hn, cur.t);
      if (selN) {
        tap(...center(selN)); await sleep(1500); shot('ahora-desplegable');
        // (la lista sale en una ventanita aparte, que la herramienta de mirar la pantalla no ve: se toca por su sitio)
        tap(selN.b[0] + 40, selN.b[1] + (-8 + 36 + 6 + 43.75 * (cur.i + 0.5)) * dprH); await sleep(1800);
        const now = await js(`const s = document.querySelector('#mixSel'); return s.selectedOptions[0].textContent;`);
        check('un desplegable: se abre y lo elegido llega a la app', now === cur.otra && !!find(ui(), cur.otra), `${cur.t} → ${now}`);
        await js(`const s = document.querySelector('#mixSel'); s.value = ${JSON.stringify(cur.v)}; s.dispatchEvent(new Event('change', { bubbles: true })); document.querySelector('#toast').hidden = true; return 1;`); await sleep(800);
      } else check('encuentro el desplegable «Entre canciones»', false, cur.t);
    }
    // --- una mezcla a medida
    const yMixer = await js(`const m = document.querySelector('#main'), e = document.querySelector('#mixer'); return Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 30));`);
    await toY(yMixer); hn = ui(); shot('ahora-mezcla-a-medida');
    await tapText('Energía', 1200);
    const mood = await js(`return [...document.querySelectorAll('#moodChips .chip')].filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.textContent.trim()).join(',');`);
    check('tocar un ánimo lo marca en la app', mood === 'Energía', mood);
    await tapText('Crear mezcla', 7000);
    const mixN = await js(`return document.querySelectorAll('#mixOut .mix-item').length;`);
    const yOut = await js(`const m = document.querySelector('#main'), e = document.querySelector('#mixOut'); return Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 30));`);
    await toY(yOut); hn = ui(); shot('ahora-mezcla');
    const mixSongs = titlesOnScreen(hn);
    check('«Crear mezcla»: la mezcla sale en la nativa, con sus botones y sus canciones', mixN >= 3 && !!find(hn, 'Reproducir') && !!find(hn, 'Guardar lista') && mixSongs.length >= 2, `${mixN} canciones · a la vista: ${mixSongs.join(' | ')}`);
    if (mixSongs.length) {
      const pick2 = mixSongs[mixSongs.length - 1];
      await tapText(pick2, 4500);
      const c2 = await js(`${O} const t = o.trackById(o.state.queue[o.state.qi]); return { title: t && t.title, paused: o.decks[o.A].paused };`);
      shot('ahora-mezcla-sonando');
      check('tocar una canción de la mezcla la pone', c2 && c2.title === pick2 && c2.paused === false && playing(), JSON.stringify(c2) + ' · ' + sessState());
    }
    // --- el disco: gira con la música y, al tocarlo, abre el reproductor
    await top0(); await sleep(1500); shot('ahora-sonando'); await sleep(700); shot('ahora-sonando-2');
    hn = ui();
    const vin = hn.find(n => n.desc === 'Abrir Ahora suena');
    if (vin) {
      tap(...center(vin)); await sleep(2600);
      const np1 = await js(`${O} return { np: !document.querySelector('#np').hidden, shown: N.shown };`); shot('ahora-reproductor');
      await js(`document.querySelector('[data-act="np-close"]').click(); return 1;`); await sleep(2400);
      const np2 = await js(`${O} return { np: !document.querySelector('#np').hidden, shown: N.shown, screen: N.screen };`);
      check('tocar el disco abre el reproductor (la nativa se aparta) y al cerrarlo vuelve', np1 && np1.np && !np1.shown && np2 && !np2.np && np2.shown && np2.screen === 'inicio', JSON.stringify([np1, np2]));
    } else check('encuentro el disco', false, hn.filter(n => n.desc).map(n => n.desc).join(' | ').slice(0, 300));
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    // --- la ruleta
    await top0();
    const yRoul = await js(`const m = document.querySelector('#main'), e = document.querySelector('#slot').parentElement.querySelector('#djAsk'); return Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 160));`);
    await toY(yRoul);
    await tapText('Girar la ruleta', 9000);
    const rl = await js(`${O} const t = o.trackById(o.state.queue[o.state.qi]), s = document.querySelector('#slot'); return { title: t && t.title, landed: s.classList.contains('landed'), radio: document.querySelector('#btnRadio').getAttribute('aria-pressed'), paused: o.decks[o.A].paused };`);
    hn = ui(); shot('ahora-ruleta');
    check('la ruleta: cae en una canción, se ve en la nativa y suena', rl && rl.landed && rl.title && rl.paused === false && hn.filter(n => n.text === rl.title).length >= 1, JSON.stringify(rl));
    if (rl && rl.radio === 'true') { await js(`document.querySelector('#btnRadio').click(); return 1;`); await sleep(800); }
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    // --- un aviso de la app encima
    await js(`window.__orbita.toast('Aviso de prueba en Ahora', 15000); return 1;`); await sleep(500);
    const seenH = !!find(ui(), 'Aviso de prueba en Ahora'); shot('ahora-aviso');
    check('los avisos de la app se ven encima de «Ahora» nativa', seenH);
    await js(`window.__orbita.toast('Listo', 900); return 1;`); await sleep(1800);
    // --- el aviso de versión nueva: se ve en la nativa y su «Descargar» abre el enlace fuera de la app
    {
      const act = () => sh('dumpsys activity activities | grep -m1 topResumedActivity').trim();
      const inj = await js(`${O} o.state.update = { version: '9.9', url: 'https://example.com/orbita-prueba', notes: 'Aviso de prueba de versión nueva.' }; localStorage.removeItem('orbita:skipUpdate'); o.renderUpdate(); return !document.querySelector('#updBanner').hidden;`); await sleep(1500);
      await top0(); hn = ui(); shot('ahora-version-nueva');
      const seenU = inj === true && !!find(hn, 'Nueva versión de Órbita: 9.9') && !!find(hn, 'Descargar') && !!find(hn, 'Luego');
      check('el aviso de versión nueva sale en la nativa, con sus dos botones', seenU, String(inj));
      await tapText('Luego', 1500);
      const gone = await js(`return document.querySelector('#updBanner').hidden;`);
      check('«Luego» quita el aviso de versión nueva', seenU && gone === true && !find(ui(), 'Nueva versión de Órbita: 9.9'), String(gone));
      await js(`${O} o.state.update = null; o.renderUpdate(); localStorage.removeItem('orbita:skipUpdate'); return 1;`); await sleep(600);
    }
    // --- el botón de ajustes, y volver (arriba del todo)
    await top0(); hn = ui();
    const gear = hn.find(n => /^Ajustes/.test(n.desc));
    if (gear) {
      tap(...center(gear)); await sleep(1800);
      const v1 = await js(`${O} return { view: o.state.view, shown: N.shown, screen: N.screen };`);
      await goView('inicio'); await sleep(1800);
      const v2 = await js(`${O} return { view: o.state.view, shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
      check('el botón de ajustes lleva a Ajustes, y al volver «Ahora» es nativa otra vez', v1 && v1.view === 'ajustes' && v1.screen === 'ajustes' && v2 && v2.shown && v2.screen === 'inicio' && v2.st.homeY === 0 && !!find(ui(), 'ÓRBITA FM'), JSON.stringify([v1, v2]));
    } else check('encuentro el botón de ajustes', false);
    // --- la portada en lugar del disco; y con un visualizador (desde la 4.9 también nativa: el visualizador lo dibuja Android)
    await js(`window.__orbita.setHeroMode('art'); return 1;`); await sleep(2500); ui(); shot('ahora-con-portada');
    const ha = await st();
    await js(`window.__orbita.setHeroMode('viz'); return 1;`); await sleep(1500); shot('ahora-con-visualizador');
    const hv = await js(`${O} return { shown: N.shown, vis: getComputedStyle(document.querySelector('#dj')).visibility, which: o.vizNatWhich() };`);
    await js(`window.__orbita.setHeroMode('vinyl'); return 1;`); await sleep(1800);
    const hb = await st();
    check('con la portada arriba sigue nativa; con un visualizador, también (y el visualizador lo dibuja Android); y de vuelta al disco, nativa', ha && ha.shown && ha.screen === 'inicio' && hv && hv.shown === true && hv.which === 'vizHero' && hb && hb.shown && hb.screen === 'inicio', JSON.stringify([ha && ha.shown, hv, hb && hb.shown]));
    // --- otro aspecto (bordes rectos, letra grande, títulos retro): llega igual
    await goView('ajustes'); await sleep(900);
    await js(`const c = (k, v) => { const b = document.querySelector('[data-look="' + k + '"][data-v="' + v + '"]'); if (b) b.click(); }; c('round', 'recto'); c('text', 'l'); c('titles', 'retro'); return 1;`);
    await goView('inicio'); await sleep(2200);
    const bigN = find(ui(), 'DJ Nova'); shot('ahora-otro-aspecto');
    check('el aspecto elegido llega a «Ahora» nativa (letra más grande)', bigN && (bigN.b[3] - bigN.b[1]) > 24 * 1.05 * dprH, 'alto del nombre ' + (bigN ? bigN.b[3] - bigN.b[1] : '?') + ' px');
    await goView('ajustes'); await sleep(700);
    await js(`const c = (k, v) => { const b = document.querySelector('[data-look="' + k + '"][data-v="' + v + '"]'); if (b) b.click(); }; c('round', 'redondo'); c('text', 'm'); c('titles', 'orbita'); return 1;`);
    await goView('inicio'); await sleep(1800);
    await top0(); ui(); shot('ahora-final');
    check('sin cierres por error con «Ahora» nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
  }
  // ================= fin de la fase 2 · paso 2 =================

  // ================= FASE 2 · paso 3 (4.6): «Mis listas», nativa =================
  // (cada texto de una pantalla, donde en la de siempre: sirve para todas las pantallas nuevas)
  const paridad = async (view, file, ys, fotos = '', lines = false) => {
    const dp = await js(`return devicePixelRatio;`);
    const Q = `const m = document.querySelector('#main'), mr = m.getBoundingClientRect(), out = [];
      const w = document.createTreeWalker(document.querySelector('${view}'), NodeFilter.SHOW_TEXT);
      // (renglón a renglón: cada palabra, a la altura de su renglón)
      const LINES = ${lines ? 'true' : 'false'}, g0 = document.createRange();
      const rows = n => { const raw = n.textContent, ws = [], re = /\\S+/g; let mm; while ((mm = re.exec(raw))) { g0.setStart(n, mm.index); g0.setEnd(n, mm.index + mm[0].length); const q = g0.getBoundingClientRect(); if (q.width) ws.push({ s: mm[0], q }); } const L = []; for (const x of ws) { const l = L[L.length - 1]; if (l && Math.abs(x.q.top - l.top) < 3) { l.s += ' ' + x.s; l.bottom = Math.max(l.bottom, x.q.bottom); } else L.push({ s: x.s, left: x.q.left, top: x.q.top, bottom: x.q.bottom }); } return L; };
      for (let n = w.nextNode(); n; n = w.nextNode()) {
        const p = n.parentElement; if (!p || !n.textContent.trim() || p.closest('select, [hidden], canvas, svg')) continue;
        const det = p.closest('details:not([open])'); if (det && !p.closest('summary')) continue;
        const cs = getComputedStyle(p); if (cs.display === 'none') continue;
        const g = document.createRange(); g.selectNodeContents(n); const r = g.getBoundingClientRect(); if (!r.width || r.top < mr.top + 6 || r.bottom > mr.bottom - 6) continue;
        let t = n.textContent.replace(/\\s+/g, ' ').trim(); if (cs.textTransform === 'uppercase') t = t.toUpperCase();
        if (LINES) { for (const l of rows(n)) { if (l.top < mr.top + 6 || l.bottom > mr.bottom - 6) continue; out.push({ t: (cs.textTransform === 'uppercase' ? l.s.toUpperCase() : l.s).slice(0, 60), x: +l.left.toFixed(1), y: +((l.top + l.bottom) / 2 - mr.top).toFixed(1), top: +(l.top - mr.top).toFixed(1), h: +(l.bottom - l.top).toFixed(1), rows: 1 }); } continue; }
        out.push({ t: t.slice(0, 60), x: +(r.left).toFixed(1), y: +((r.top + r.bottom) / 2 - mr.top).toFixed(1), top: +(r.top - mr.top).toFixed(1), h: +r.height.toFixed(1), rows: Math.round(r.height / (parseFloat(cs.lineHeight) || r.height)) });
      }
      return { scroll: m.scrollTop, total: m.scrollHeight, list: out };`;
    const par = [], bad = []; let last = -1;
    for (const y of ys) {
      await js(`window.__orbita.NUI.classic = true; await new Promise(r => setTimeout(r, 500)); document.querySelector('#main').scrollTop = ${y}; return 1;`); await sleep(500);
      const w = await js(Q); if (!w || w.scroll === last) continue; last = w.scroll;
      // (y una captura de cada una a la misma altura, para mirarlas una al lado de la otra)
      if (fotos) shot(`igual-${fotos}-${Math.round(w.scroll)}-web`);
      await js(`window.__orbita.NUI.classic = false; await new Promise(r => setTimeout(r, 700)); await Capacitor.Plugins.OrbitaUi.scroll({ y: ${w.scroll} }); return 1;`); await sleep(900);
      const ns = ui(), wv = ns.find(n => n.cls === 'android.webkit.WebView'), top = wv ? wv.b[1] : 0;
      if (fotos) shot(`igual-${fotos}-${Math.round(w.scroll)}-nativa`);
      const cnt = {}; for (const e of w.list) cnt[e.t] = (cnt[e.t] || 0) + 1;
      for (const e of w.list) {
        if (cnt[e.t] > 1 || e.t.length < 3 || /^\d\d:\d\d$/.test(e.t)) continue;
        let c = ns.filter(n => n.text && (lines ? n.text.trim() : n.text).slice(0, 60) === e.t), part = false;
        // (un título hecho de trozos en la web es un solo texto en la nativa: se mira que esté a su altura)
        if (!c.length) { c = ns.filter(n => n.text && n.text.length > e.t.length && n.text.includes(e.t)); part = c.length === 1; }
        if (c.length !== 1) { par.push({ at: w.scroll, t: e.t, falta: c.length }); if (!c.length) bad.push(`«${e.t.slice(0, 26)}» no está`); continue; }
        const nx = part ? e.x : c[0].b[0] / dp, ny = ((c[0].b[1] + c[0].b[3]) / 2 - top) / dp, dx = +(nx - e.x).toFixed(1);
        let dy = +(ny - e.y).toFixed(1);
        // (un párrafo de varios renglones puede ir partido en dos textos en la nativa: entonces se mira dónde empieza)
        // (y a un texto que queda justo encima de un botón, Android le recorta por abajo el sitio que le cuenta al lector de pantalla,
        //  porque el botón se toca desde un poco más arriba de donde se ve: si mide menos que sus propias letras, se mira también dónde empieza)
        const cut = (c[0].b[3] - c[0].b[1]) / dp < e.h - 1;
        if ((e.rows > 1 || cut) && Math.abs(dy) > 3) { const dt = +((c[0].b[1] - top) / dp - e.top).toFixed(1); if (Math.abs(dt) < Math.abs(dy)) dy = dt; }
        par.push({ at: w.scroll, t: e.t, web: [e.x, e.y], nat: [+nx.toFixed(1), +ny.toFixed(1)], dx, dy });
        if (Math.abs(dy) > 3 || Math.abs(dx) > 3) bad.push(`«${e.t.slice(0, 26)}» ${dx}/${dy}`);
      }
    }
    fs.writeFileSync(`${OUT}/${file}`, JSON.stringify({ par }));
    return { n: par.filter(e => e.nat).length, bad: [...new Set(bad)] };
  };
  {
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1200);
    const O = `const o = window.__orbita, N = o.NUI;`;
    await goView('listas'); await sleep(1500);
    const l0 = await js(`${O} return { shown: N.shown, screen: N.screen, pgOk: N.pgOk, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    let ln = ui(); shot('listas-nativa-vacia');
    check('«Mis listas» sale nativa al entrar', !!l0 && l0.shown && l0.screen === 'listas' && l0.st.screen === 'listas' && l0.st.shown === true && !!find(ln, 'MIS LISTAS'), JSON.stringify(l0) + ' · ' + ln.filter(n => n.text).map(n => n.text.slice(0, 24)).join(' | ').slice(0, 300));
    // se guardan dos listas desde una mezcla
    await goView('inicio'); await sleep(1200);
    for (let k = 0; k < 2; k++) {
      await js(`document.querySelector('#btnMix').click(); return 1;`); await sleep(6000);
      await js(`const b = [...document.querySelectorAll('#mixOut .mix-actions button')].find(x => /Guardar/.test(x.textContent)); if (b) b.click(); return 1;`); await sleep(1200);
    }
    await js(`document.querySelector('#toast').hidden = true; return 1;`);
    await goView('listas'); await sleep(2000);
    const l1 = await js(`${O} return { n: o.state.playlists.length, name: (document.querySelector('#listsOut .pl-card h3') || {}).textContent, screen: N.screen };`);
    shot('listas-nativa');
    // (a la altura de las listas guardadas: las de Nova van antes y ocupan la pantalla)
    const toList = async () => { const y = await js(`const m = document.querySelector('#main'), e = document.querySelector('#listsOut .pl-card h3'); return e ? Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 150)) : 0;`); await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: ${y} }); return 1;`); await sleep(900); };
    await toList(); ln = ui(); shot('listas-nativa-guardadas');
    check('las listas guardadas salen en la nativa', l1 && l1.n >= 1 && l1.screen === 'listas' && !!find(ln, l1.name) && !!find(ln, 'Escuchar') && !!ln.find(n => n.desc === 'Borrar lista'), JSON.stringify(l1) + ' · ' + ln.filter(n => n.text || n.desc).map(n => (n.text || n.desc).slice(0, 22)).join(' | ').slice(0, 300));
    await js(`document.documentElement.classList.add('lite'); return 1;`);
    const pl = await paridad('#view-listas', 'paridad-listas.json', [0, 300, 600, 900, 99999], 'listas');
    await js(`document.documentElement.classList.remove('lite'); ${O} N.classic = true; await new Promise(r => setTimeout(r, 600)); document.querySelector('#main').scrollTop = 0; return 1;`); await sleep(600); ui(); shot('listas-web');
    await js(`${O} N.classic = false; return 1;`); await sleep(900);
    check('«Mis listas»: cada texto, donde en la pantalla de siempre (± 3)', pl.n >= 6 && pl.bad.length === 0, `${pl.n} comparados · ${pl.bad.length} fuera: ${pl.bad.slice(0, 12).join(' · ')}`);
    await toList();
    await tapText('Escuchar', 4000);
    const lp = await js(`${O} return { q: o.state.queue.length, paused: o.decks[o.A].paused };`);
    check('«Escuchar» pone la lista', lp && lp.q >= 2 && lp.paused === false && playing(), JSON.stringify(lp) + ' · ' + sessState());
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    await toList(); ln = ui();
    const del = ln.find(n => n.desc === 'Borrar lista');
    if (del) {
      const before = await js(`return window.__orbita.state.playlists.length;`);
      // (mirar la pantalla desde fuera tarda más de lo que dura el «¿Borrar?»: se pregunta a la app y se toca en el mismo sitio)
      tap(...center(del)); await sleep(900); shot('listas-borrar'); await sleep(3800);
      tap(...center(del)); await sleep(700);
      const mid = await js(`return { n: window.__orbita.state.playlists.length, armed: !!document.querySelector('#view-listas .armed') };`);
      tap(...center(del)); await sleep(1800);
      const after = await js(`return window.__orbita.state.playlists.length;`);
      check('la papelera pide confirmar y, al confirmar, borra la lista', mid && mid.armed && mid.n === before && after === before - 1, `armada ${mid && mid.armed} · ${before} → ${mid && mid.n} → ${after}`);
    } else check('encuentro la papelera de una lista', false, ln.filter(n => n.desc).map(n => n.desc).join(' | ').slice(0, 300));
    await goView('buscar'); await sleep(900);
    const lt = await js(`${O} return N.shown ? N.screen : 'ninguna';`);
    await goView('listas'); await sleep(1500);
    const lb = await js(`${O} return { shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    check('al cambiar de pestaña cambia la nativa, y al volver «Mis listas» es nativa y está arriba', lt === 'buscar' && lb && lb.shown && lb.screen === 'listas' && lb.st.y === 0, JSON.stringify([lt, lb]));
    shot('listas-final');
    check('sin cierres por error con «Mis listas» nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
    await goView('inicio'); await sleep(1200);
  }
  // ----------------- «Tú», nativa -----------------
  {
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1200);
    const O = `const o = window.__orbita, N = o.NUI;`;
    // unas cuantas escuchas apuntadas (lo que normalmente se junta con los días)
    await js(`${O} const now = Date.now(), D = 864e5, ts = o.state.tracks;
      for (let k = 0; k < 60; k++) { const t = ts[(k * 7) % Math.min(ts.length, 9)]; o.memEvents.push({ ts: now - (k % 12) * D - k * 600000, id: t.id, sec: 30 + (k % 5) * 40, pct: 1, skip: k % 9 === 0, hour: (8 + k) % 24, dow: k % 7, src: 'mix', title: t.title, artist: t.artist, net: false }); }
      o.renderTu(); return 1;`);
    await goView('tu'); await sleep(1800);
    const t0 = await js(`${O} return { shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    let tn = ui(); shot('tu-nativa');
    check('«Tú» sale nativa al entrar', !!t0 && t0.shown && t0.screen === 'tu' && t0.st.screen === 'tu' && t0.st.shown === true && !!find(tn, 'Semana') && !!find(tn, 'Siempre'), JSON.stringify(t0) + ' · ' + tn.filter(n => n.text).map(n => n.text.slice(0, 22)).join(' | ').slice(0, 300));
    await js(`document.documentElement.classList.add('lite'); return 1;`);
    const pt = await paridad('#view-tu', 'paridad-tu.json', [0, 350, 700, 1050, 1400, 1750, 2100, 2450, 99999], 'tu');
    await js(`document.documentElement.classList.remove('lite'); ${O} N.classic = true; await new Promise(r => setTimeout(r, 600)); document.querySelector('#main').scrollTop = 0; return 1;`); await sleep(600); ui(); shot('tu-web');
    await js(`${O} N.classic = false; return 1;`); await sleep(900);
    check('«Tú»: cada texto, donde en la pantalla de siempre (± 3), de arriba abajo', pt.n >= 30 && pt.bad.length === 0, `${pt.n} comparados · ${pt.bad.length} fuera: ${pt.bad.slice(0, 14).join(' · ')}`);
    const at = async sel => { const y = await js(`const m = document.querySelector('#main'), e = ${sel}; return e ? Math.max(0, Math.round(e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - 160)) : 0;`); await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: ${y} }); return 1;`); await sleep(900); };
    // una canción de «Tus canciones»
    await at(`document.querySelector('#tuOut .tu-row')`); tn = ui(); shot('tu-canciones');
    const pb = tn.find(n => /^Escuchar /.test(n.desc));
    if (pb) {
      tap(...center(pb)); await sleep(4000);
      const c = await js(`${O} const t = o.trackById(o.state.queue[o.state.qi]); return { title: t && t.title, paused: o.decks[o.A].paused };`);
      check('el botón de una canción de «Tus canciones» la pone', c && pb.desc === 'Escuchar ' + c.title && c.paused === false && playing(), JSON.stringify(c) + ' · ' + pb.desc);
      sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    } else check('encuentro el botón de escuchar de una canción', false, tn.filter(n => n.desc).map(n => n.desc).join(' | ').slice(0, 300));
    // los datos de una gráfica
    await at(`document.querySelector('#tuOut details summary')`);
    await tapText('Ver los datos', 1500);
    const dt = await js(`return document.querySelector('#tuOut details').open;`);
    tn = ui(); shot('tu-datos');
    check('«Ver los datos» despliega la tabla de la gráfica', dt === true && !!find(tn, 'Hora') && !!find(tn, 'Tiempo'), String(dt) + ' · ' + tn.filter(n => n.text).map(n => n.text.slice(0, 12)).join(' | ').slice(0, 260));
    await tapText('Ver los datos', 1200);
    // el periodo
    await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: 0 }); return 1;`); await sleep(900);
    await tapText('Siempre', 1500);
    const rg = await js(`return [...document.querySelectorAll('#tuRange button')].filter(b => b.getAttribute('aria-pressed') === 'true').map(b => b.textContent.trim()).join(',');`);
    check('cambiar el periodo («Siempre») llega a la app', rg === 'Siempre', rg);
    await tapText('Mes', 1200);
    // el interruptor de recordar
    await at(`document.querySelector('#tMem')`);
    await tapText('Recordar lo que escucho', 1500);
    const m1 = await js(`return JSON.parse(localStorage.getItem('orbita:memOn'));`);
    await tapText('Recordar lo que escucho', 2500);
    const m2 = await js(`return JSON.parse(localStorage.getItem('orbita:memOn'));`);
    check('«Recordar lo que escucho» se apaga y se enciende', m1 === false && m2 === true, `${m1} → ${m2}`);
    // abajo del todo: los botones
    await js(`await Capacitor.Plugins.OrbitaUi.scroll({ to: 'end' }); return 1;`); await sleep(1000); tn = ui(); shot('tu-abajo');
    await tapText('Ajustes', 1800);
    const va = await js(`${O} return { view: o.state.view, shown: N.shown, screen: N.screen };`);
    check('el botón «Ajustes» de abajo lleva a Ajustes', va && va.view === 'ajustes' && va.screen === 'ajustes' && !!find(tn, 'Borrar mi historial'), JSON.stringify(va));
    await goView('tu'); await sleep(1500);
    const tb = await js(`${O} return { shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    check('y al volver, «Tú» es nativa y está arriba', tb && tb.shown && tb.screen === 'tu' && tb.st.y === 0, JSON.stringify(tb));
    check('sin cierres por error con «Tú» nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
    await goView('inicio'); await sleep(1200);
  }
  // ================= FASE 2 · último paso (4.8): las ventanas y el reproductor a pantalla completa, nativos =================
  await ventanas48();

  await viz49();
  await ly50();
  await yt50();
  await voz51();

  // ================= FASE 2 · pasos 4 y 5 (4.7): «Buscar» y «Ajustes», nativas =================
  {
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1000);
    const O = `const o = window.__orbita, N = o.NUI;`;
    let T4 = 0; const D4 = await js(`return devicePixelRatio;`);
    // dónde está ahora en la pantalla algo de la web (la nativa lo dibuja en el mismo sitio)
    const at = async sel => { const r = await js(`const e = document.querySelector('${sel}'); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom];`); if (!r) return null; return { x1: r[0] * D4, y1: T4 + r[1] * D4, x2: r[2] * D4, y2: T4 + r[3] * D4, cx: (r[0] + r[2]) / 2 * D4, cy: T4 + (r[1] + r[3]) / 2 * D4 }; };
    // la nativa se mueve hasta que se vea (y la web se pone a la misma altura al pararse)
    const bring = async (sel, off = 300) => { const y = await js(`const m = document.querySelector('#main'), e = document.querySelector('${sel}'); if (!e) return -1; const y = Math.max(0, e.getBoundingClientRect().top - m.getBoundingClientRect().top + m.scrollTop - ${off}); await Capacitor.Plugins.OrbitaUi.scroll({ y }); return y;`); await sleep(1500); return y; };
    await goView('buscar'); await sleep(1600);
    const b0 = await js(`${O} return { shown: N.shown, screen: N.screen, mirOk: N.mirOk, err: N.err, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    let bn = ui(); shot('buscar-nativa');
    { const wv = bn.find(n => n.cls === 'android.webkit.WebView'); T4 = wv ? wv.b[1] : 0; }
    check('«Buscar» sale nativa', !!b0 && b0.shown && b0.screen === 'buscar' && b0.mirOk && b0.st.v >= 6 && b0.st.screen === 'buscar' && !!find(bn, 'BUSCAR') && !!find(bn, 'Busca cualquier canción'), JSON.stringify(b0) + ' · ' + bn.filter(n => n.text).map(n => n.text.slice(0, 20)).join(' | ').slice(0, 300));
    const pb = await paridad('#view-buscar', 'paridad-buscar.json', [0, 99999], 'buscar', true);
    await js(`${O} N.classic = false; await new Promise(r => setTimeout(r, 600)); await Capacitor.Plugins.OrbitaUi.scroll({ to: 'top' }); return 1;`); await sleep(1200);
    check('«Buscar»: cada renglón, donde en la pantalla de siempre (± 3)', pb.n >= 12 && pb.bad.length === 0, `${pb.n} comparados · ${pb.bad.length} fuera: ${pb.bad.slice(0, 12).join(' · ')}`);
    // escribir y buscar con el teclado
    bn = ui();
    const box = find(bn, 'Canción, artista, género o ambiente…') || bn.find(n => /^Canción, artista/.test(n.text));
    if (box) { tap(...center(box)); await sleep(1600); sh('input text kora'); await sleep(2000); }
    const q1 = await js(`return document.querySelector('#netQ').value;`);
    sh('input keyevent KEYCODE_ENTER'); await sleep(4500);
    bn = ui(); shot('buscar-kora');
    const kb4 = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
    const qs = await js(`return { q: document.querySelector('#netQ').value, lib: [...document.querySelectorAll('#netOut .result')].length, out: document.querySelector('#netOut').textContent.slice(0, 200) };`);
    check('escribir en la caja nativa y darle a «buscar»: busca, se cierra el teclado y salen los resultados (las de Kora que ya tienes)', !!box && q1 === 'kora' && !kb4 && qs && qs.lib >= 2 && (!!find(bn, 'Pulso') || !!find(bn, 'Luz de las siete')), JSON.stringify({ box: !!box, q1, kb: kb4, qs }) + ' · ' + bn.filter(n => n.text).map(n => n.text.slice(0, 18)).join(' | ').slice(0, 300));
    // otra pestaña: «Radios», y un país de la lista desplegable
    await tapText('Radios', 2200); bn = ui(); shot('buscar-radios');
    const rt = await js(`const s = document.querySelector('#rCC'); return { radios: !document.querySelector('#netRadios').hidden, cc: s.value, opts: [...s.options].slice(0, 4).map(o => [o.value, o.textContent]) };`);
    check('la pestaña «Radios» se abre tocándola', !!rt && rt.radios, JSON.stringify(rt));
    const cc = await at('#rCC');
    if (cc && rt && rt.opts.length > 1) { tap(cc.cx, cc.cy); await sleep(1600); shot('buscar-paises'); tap(cc.x1 + 90 * D4, cc.y2 + (4 + 6 + 43.75 + 21.9) * D4); await sleep(2500); }
    const rt2 = await js(`return document.querySelector('#rCC').value;`);
    check('y el país se elige en la lista desplegable nativa', !!cc && !!rt && rt2 === rt.opts[1][0] && rt2 !== rt.cc, `${rt && rt.cc} → ${rt2}`);
    if (rt) await js(`const s = document.querySelector('#rCC'); s.value = ${JSON.stringify(rt ? rt.cc : '')}; s.dispatchEvent(new Event('change', { bubbles: true })); return 1;`);
    await tapText('Canciones', 1500);
    check('sin cierres por error con «Buscar» nativa', alive() && crashes() === 0, crashText().slice(0, 2500));

    // ----- «Ajustes»
    await goView('ajustes'); await sleep(1800);
    const a0 = await js(`${O} return { shown: N.shown, screen: N.screen, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    let an = ui(); shot('ajustes-nativa');
    check('«Ajustes» sale nativa', !!a0 && a0.shown && a0.screen === 'ajustes' && a0.st.screen === 'ajustes' && !!find(an, 'AJUSTES') && !!find(an, 'Despertador'), JSON.stringify(a0));
    const H4 = await js(`return document.querySelector('#main').scrollHeight;`);
    const ys = []; for (let y = 0; y < H4 - 900; y += 1400) ys.push(y); ys.push(99999);
    const pa = await paridad('#view-ajustes', 'paridad-ajustes.json', ys, 'ajustes', true);
    await js(`${O} N.classic = false; await new Promise(r => setTimeout(r, 600)); await Capacitor.Plugins.OrbitaUi.scroll({ to: 'top' }); return 1;`); await sleep(1200);
    check('«Ajustes»: cada renglón, donde en la pantalla de siempre (± 3)', pa.n >= 100 && pa.bad.length === 0, `${pa.n} comparados · ${pa.bad.length} fuera: ${pa.bad.slice(0, 12).join(' · ')}`);
    // un interruptor
    const vib0 = await js(`const t = [...document.querySelectorAll('#view-ajustes button')].find(x => /Vibración al tocar/.test(x.textContent)); if (!t) return null; t.dataset.tst = 'vib'; return t.getAttribute('aria-pressed');`);
    await bring('[data-tst="vib"]');
    an = ui(); const vb = find(an, 'Vibración al tocar');
    if (vb) { tap(...center(vb)); await sleep(1600); }
    const vib1 = await js(`return document.querySelector('[data-tst="vib"]').getAttribute('aria-pressed');`);
    shot('ajustes-interruptor-nativo');
    check('un interruptor de «Ajustes» nativa (vibración al tocar) se cambia tocándolo', !!vb && vib0 != null && vib1 !== vib0, `${vib0} → ${vib1}`);
    if (vb) { tap(...center(vb)); await sleep(1300); }
    // una barra para deslizar
    await bring('#mixLenIn', 420);
    const sl = await at('#mixLenIn'); const sv0 = await js(`return document.querySelector('#mixLenIn').value;`);
    if (sl) { sh(`input swipe ${Math.round(sl.x1 + (sl.x2 - sl.x1) * 0.2)} ${Math.round(sl.cy)} ${Math.round(sl.x1 + (sl.x2 - sl.x1) * 0.9)} ${Math.round(sl.cy)} 700`); await sleep(2000); }
    const sv1 = await js(`return document.querySelector('#mixLenIn').value;`); shot('ajustes-deslizar');
    check('una barra de «Ajustes» nativa (duración del fundido) se desliza con el dedo', !!sl && +sv1 > +sv0 && +sv1 >= 13, `${sv0} → ${sv1}`);
    if (sl) { tap(sl.x1 + (sl.x2 - sl.x1) * 0.25, sl.cy); await sleep(1600); }
    const sv2 = await js(`return document.querySelector('#mixLenIn').value;`);
    an = ui();
    check('y un toque en la barra la pone ahí (y la nativa enseña el valor)', +sv2 < +sv1 && +sv2 <= 6 && !!an.find(n => n.text.trim() === sv2 + ' s'), `${sv1} → ${sv2}`);
    await js(`const e = document.querySelector('#mixLenIn'); e.value = ${JSON.stringify(String(sv0))}; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); return 1;`);
    // el ecualizador: una banda arrastrada, y un toque en el medio la deja a 0
    await bring('#eqBands', 260);
    const tr = await at('#eqBands .eqtrack');
    if (tr) { sh(`input swipe ${Math.round(tr.cx)} ${Math.round(tr.y1 + (tr.y2 - tr.y1) * 0.5)} ${Math.round(tr.cx)} ${Math.round(tr.y1 + (tr.y2 - tr.y1) * 0.25)} 600`); await sleep(1800); }
    const eq1 = await js(`return document.querySelector('#eqBands [data-bandval="0"]').textContent.trim();`); shot('ajustes-ecualizador');
    check('una banda del ecualizador nativa se arrastra con el dedo (sube a +6)', !!tr && /^\+?[5-7]$/.test(eq1), eq1);
    if (tr) { tap(tr.cx, tr.cy); await sleep(1600); }
    const eq2 = await js(`return document.querySelector('#eqBands [data-bandval="0"]').textContent.trim();`);
    check('y un toque en el medio la deja a 0', eq2 === '0', eq2);
    // la hora del despertador, con el reloj de Android
    await bring('#alarmTimeBtn', 300);
    an = ui(); const chg = find(an, 'Cambiar la hora');
    if (chg) { tap(...center(chg)); await sleep(2800); }
    const dn = ui(); shot('ajustes-reloj');
    const okB = dn.find(n => /^(OK|Aceptar)$/i.test(n.text));
    if (okB) { tap(...center(okB)); await sleep(1600); }
    const al = await js(`${O} return { t: document.querySelector('#alarmTime').value, shown: N.shown, screen: N.screen };`);
    check('«Cambiar la hora» abre el reloj de Android; al aceptar, la hora queda puesta y «Ajustes» sigue nativa', !!chg && !!okB && !!al && /^\d\d:\d\d$/.test(al.t) && al.shown && al.screen === 'ajustes', JSON.stringify(al) + ' · ' + dn.filter(n => n.text).map(n => n.text).join(' | ').slice(0, 220));
    // una caja de escribir de Ajustes (la ciudad): lo escrito llega a la web
    await bring('#cityIn', 300);
    an = ui(); const cityB = find(an, 'Tu ciudad (opcional)');
    if (cityB) { tap(...center(cityB)); await sleep(1500); sh('input text Soria'); await sleep(1500); sh('input keyevent KEYCODE_BACK'); await sleep(1200); }
    const ci = await js(`return document.querySelector('#cityIn').value;`);
    check('lo que se escribe en una caja de «Ajustes» nativa llega a la de siempre', !!cityB && ci === 'Soria', JSON.stringify(ci));
    await js(`const c = document.querySelector('#cityIn'); c.value = ''; c.dispatchEvent(new Event('input', { bubbles: true })); return 1;`); await sleep(600);
    // un tema (los colores de todo cambian)
    await bring('[data-theme-id="neon"]', 320);
    an = ui(); const neon = find(an, 'Neón');
    if (neon) { tap(...center(neon)); await sleep(2500); }
    const th = await js(`return window.__orbita.state.theme;`); shot('ajustes-tema-neon');
    check('tocar un tema en «Ajustes» nativa lo elige', !!neon && th === 'neon', th);
    await js(`document.querySelector('[data-theme-id="auto"]').click(); return 1;`); await sleep(1500);
    // una ventana encima: «Ajustes» se aparta y la de siempre está a la misma altura; al cerrarla, vuelve la nativa donde estaba
    await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: 2600 }); return 1;`); await sleep(1800);
    const sy = await js(`return { web: document.querySelector('#main').scrollTop, nat: (await Capacitor.Plugins.OrbitaUi.status()).y };`);
    await js(`document.querySelector('#ytOverlay').hidden = false; return 1;`); await sleep(1300);
    const ov = await js(`${O} return { shown: N.shown, web: document.querySelector('#main').scrollTop };`); shot('ajustes-con-ventana');
    await js(`document.querySelector('#ytOverlay').hidden = true; return 1;`); await sleep(1800);
    const bk = await js(`${O} return { shown: N.shown, screen: N.screen, nat: (await Capacitor.Plugins.OrbitaUi.status()).y };`); shot('ajustes-sin-ventana');
    check('con una ventana de las de siempre encima (YouTube), «Ajustes» se aparta (la de siempre a la misma altura) y al cerrarla vuelve donde estaba', !!sy && Math.abs(sy.web - 2600) < 3 && Math.abs(sy.nat - 2600) < 3 && !!ov && ov.shown === false && Math.abs(ov.web - 2600) < 3 && !!bk && bk.shown && bk.screen === 'ajustes' && Math.abs(bk.nat - 2600) < 3, JSON.stringify([sy, ov, bk]));
    await js(`await Capacitor.Plugins.OrbitaUi.scroll({ to: 'top' }); return 1;`); await sleep(900);
    check('sin cierres por error con «Ajustes» nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
    await goView('inicio'); await sleep(1200);
  }
  // ================= fin de la fase 2 · paso 3 =================

  // ================= FASE 3 · paso 1 (4.4): la conversación con Nova, nativa =================
  {
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1500);
    await goView('inicio'); await sleep(900);
    const dpr3 = await js(`return devicePixelRatio;`);
    // la de siempre, de referencia (y sus medidas)
    await js(`window.__orbita.NUI.classic = true; localStorage.setItem('orbita:chat', '[]'); document.querySelector('#toast').hidden = true; document.querySelector('#openChat').click(); return 1;`); await sleep(1500);
    const CHATQ = `const R = e => { if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom].map(v => Math.round(v * 100) / 100); }; const q = s => document.querySelector(s);
      const tr = e => { if (!e) return null; const rg = document.createRange(); rg.selectNodeContents(e); return R(rg); };
      return { name: tr(q('#chatName')), now: tr(q('#chatNow')), borrar: R(q('[data-chat="clear"]')), cerrar: R(q('[data-chat="close"]')), msg: R(q('#chatLog .msg')), msgT: tr(q('#chatLog .msg')), chips: [...document.querySelectorAll('#chatChips .idea')].map(e => [e.textContent, R(e)]), input: R(q('#chatIn')), mic: q('#chatMic').hidden ? null : R(q('#chatMic')), enviar: R(q('#chatForm [type=submit]')), log: R(q('#chatLog')), nowTxt: q('#chatNow').textContent };`;
    const webC = await js(CHATQ); ui(); await sleep(300); shot('chat-web');
    // la nativa
    await js(`window.__orbita.NUI.classic = false; return 1;`); await sleep(1800);
    const cst = await js(`const N = window.__orbita.NUI; return { shown: N.shown, screen: N.screen, chatOk: N.chatOk, err: N.err, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    let cn = ui(); shot('chat-nativa');
    check('la conversación con Nova sale nativa', !!cst && cst.shown && cst.screen === 'chat' && cst.st.screen === 'chat' && cst.st.shown === true && cst.st.msgs >= 1 && !!find(cn, 'Hola, soy') && !!find(cn, 'Enviar') && !!find(cn, 'Cerrar'), JSON.stringify(cst));
    {
      // cada cosa, donde en la pantalla de siempre (± 3)
      const wv = cn.find(n => n.cls === 'android.webkit.WebView'), top = wv ? wv.b[1] : 0;
      const nat = t => { const n = cn.find(x => x.text === t) || cn.find(x => t.length > 12 && x.text.startsWith(t)); return n ? [n.b[0] / dpr3, (n.b[1] - top) / dpr3, n.b[2] / dpr3, (n.b[3] - top) / dpr3] : null; };
      const cx = r => (r[0] + r[2]) / 2, cy = r => (r[1] + r[3]) / 2, diffs = [];
      const cmp = (name, w, n, how) => { if (!w || !n) { diffs.push([name, 'falta', !!w, !!n]); return; } const dx = how === 'left' ? n[0] - w[0] : cx(n) - cx(w), dy = cy(n) - cy(w); diffs.push([name, +dx.toFixed(1), +dy.toFixed(1)]); };
      cmp('Nova', webC.name, nat('Nova'), 'left'); cmp('qué suena', webC.now, nat(webC.nowTxt), 'left');
      cmp('Borrar', webC.borrar, nat('Borrar')); cmp('Cerrar', webC.cerrar, nat('Cerrar')); cmp('Enviar', webC.enviar, nat('Enviar'));
      cmp('saludo', webC.msgT, nat('Hola, soy Nova. Puedes'), 'left');
      for (const [t, r] of webC.chips) cmp('«' + t + '»', r, nat(t));
      const ph = nat('Escribe a la DJ…'); if (ph && webC.input) diffs.push(['caja', +(ph[0] - (webC.input[0] + 16 + Math.floor(dpr3) / dpr3)).toFixed(1), +(cy(ph) - cy(webC.input)).toFixed(1)]); else diffs.push(['caja', 'falta']);
      const mic = cn.find(n => n.desc === 'Háblale'); if (webC.mic || mic) { const m = mic ? [mic.b[0] / dpr3, (mic.b[1] - top) / dpr3, mic.b[2] / dpr3, (mic.b[3] - top) / dpr3] : null; cmp('micrófono', webC.mic, m); }
      const worst = diffs.filter(d => d[1] === 'falta' || Math.abs(d[1]) > 3 || Math.abs(d[2]) > 3);
      check('la conversación nativa: cada cosa donde en la de siempre (± 3)', worst.length === 0 && diffs.length >= 12, JSON.stringify(diffs));
      fs.writeFileSync(`${OUT}/paridad-chat.json`, JSON.stringify({ web: webC, nat: cn.filter(n => n.text || n.desc).map(n => ({ t: (n.text || n.desc).slice(0, 40), b: n.b })), top, dpr: dpr3, diffs }));
    }
    // una sugerencia
    await tapText('Ayuda', 3000); cn = ui(); shot('chat-ayuda');
    for (let k = 0; k < 3 && !find(cn, 'Puedes pedirme'); k++) { await sleep(1500); cn = ui(); }   // (a veces el emulador da la pantalla de un momento antes)
    {
      // mantener pulsado un mensaje lo copia (aquí solo se comprueba que no rompe nada)
      let copied = false; const bub = find(cn, 'Puedes pedirme'); if (bub) { const [bx, by] = center(bub); sh(`input swipe ${bx} ${by} ${bx} ${by} 900`); await sleep(1200); copied = true; }
      check('mantener pulsado un mensaje (copiarlo) no rompe nada', alive() && crashes() === 0 && (await js(`return window.__orbita.NUI.shown;`)) === true);
      // Android enseña unos segundos lo copiado abajo a la izquierda (encima de la caja de escribir): se espera a que se quite
      if (copied) await sleep(7500);
      cn = ui();
    }
    check('tocar una sugerencia: sale mi mensaje y Nova contesta', cn.filter(n => n.text === 'Ayuda').length === 2 && !!find(cn, 'Puedes pedirme'), cn.filter(n => n.text).map(n => n.text.slice(0, 18)).join(' | ').slice(0, 300));
    // escribir con el teclado
    const inp = cn.find(n => n.text === 'Escribe a la DJ…') || cn.find(n => n.desc === 'Mensaje para la DJ');
    if (inp) {
      tap(inp.b[2] - 60, center(inp)[1]); await sleep(2200); sh('input text gracias'); await sleep(1800);
      const kb = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
      cn = ui(); shot('chat-teclado');
      const wv2 = cn.find(n => n.cls === 'android.webkit.WebView');
      check('con el teclado abierto se ve lo último que dijo Nova y lo que escribo', kb && !!find(cn, 'Puedes pedirme') && cn.some(n => n.text === 'gracias') && !!find(cn, 'Enviar'), 'teclado ' + kb + ' · alto de la app ' + (wv2 ? wv2.b[3] - wv2.b[1] : '?'));
      sh('input keyevent KEYCODE_ENTER'); await sleep(4000); cn = ui(); shot('chat-enviado');
      const log1 = await js(`return JSON.parse(localStorage.getItem('orbita:chat') || '[]').map(m => [m.who, m.text.slice(0, 30)]);`);
      check('«enviar» en el teclado manda el mensaje, la caja se vacía y Nova contesta', log1.length >= 4 && log1[2][0] === 'me' && log1[2][1] === 'gracias' && log1[3][0] !== 'me' && cn.filter(n => n.text === 'gracias').length === 1 && !!find(cn, 'Escribe a la DJ…'), JSON.stringify(log1));
      // y con el botón
      sh('input text hola'); await sleep(1500); await tapText('Enviar', 4000);
      const log2 = await js(`return JSON.parse(localStorage.getItem('orbita:chat') || '[]').map(m => [m.who, m.text.slice(0, 30)]);`);
      cn = ui(); shot('chat-enviado-2');
      check('el botón «Enviar» manda el mensaje', log2.length >= 6 && log2[4][0] === 'me' && log2[4][1] === 'hola' && cn.some(n => n.text === 'hola'), JSON.stringify(log2.slice(4)));
    } else check('encuentro la caja para escribir', false);
    // un aviso de la app encima de la conversación
    await js(`window.__orbita.toast('Aviso de prueba en la conversación', 15000); return 1;`); await sleep(500);
    const seenT = !!find(ui(), 'Aviso de prueba en la conversación'); shot('chat-aviso');
    check('los avisos de la app se ven encima de la conversación nativa', seenT);
    await js(`window.__orbita.toast('Listo', 900); return 1;`); await sleep(1800);
    // algo de la app encima (el temporizador): la nativa se aparta y vuelve
    await js(`document.querySelector('#ytOverlay').hidden = false; return 1;`); await sleep(900);
    const as1 = await js(`return window.__orbita.NUI.shown;`); shot('chat-con-ventana');
    await js(`document.querySelector('#ytOverlay').hidden = true; return 1;`); await sleep(1200);
    const as2 = await js(`const N = window.__orbita.NUI; return N.shown && N.screen === 'chat';`);
    check('si una ventana de las de siempre se pone encima, la nativa se aparta y luego vuelve', as1 === false && as2 === true, `${as1} → ${as2}`);
    await js(`document.querySelector('#sleepOverlay').hidden = false; return 1;`); await sleep(1200);
    const as3 = await js(`const o = window.__orbita, N = o.NUI; return { chat: N.shown && N.screen === 'chat', win: o.W.on.join() };`); shot('chat-con-temporizador');
    await js(`document.querySelector('#sleepOverlay').hidden = true; return 1;`); await sleep(900);
    check('el temporizador (nativo) sale encima de la conversación, que sigue nativa debajo', !!as3 && as3.chat && as3.win === 'sleep', JSON.stringify(as3));
    // borrar y cerrar
    await tapText('Borrar', 1800); cn = ui();
    const log3 = await js(`return JSON.parse(localStorage.getItem('orbita:chat') || '[]').length;`);
    check('«Borrar» vacía la conversación', log3 === 0 && !!find(cn, 'Hola, soy') && !cn.some(n => n.text === 'gracias'), String(log3));
    await tapText('Cerrar', 1800);
    const cl = await js(`await new Promise(r => setTimeout(r, 700)); const N = window.__orbita.NUI; return { cerrada: document.querySelector('#chat').hidden, shown: N.shown, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    const kb2 = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
    ui(); shot('chat-cerrada');
    check('«Cerrar» cierra la conversación, vuelve «Ahora» y baja el teclado', cl && cl.cerrada && cl.shown && cl.st.shown === true && cl.st.screen === 'inicio' && !kb2, JSON.stringify(cl) + ' · teclado ' + kb2);
    check('sin cierres por error con la conversación nativa', alive() && crashes() === 0, crashText().slice(0, 2500));
  }
  // ================= fin de la fase 3 · paso 1 =================

  // ---- 4.4 · las muestras de 30 s, con Deezer de verdad (si el emulador llega a Internet)
  {
    const info = await js(`
      try {
        const r = await Capacitor.Plugins.CapacitorHttp.get({ url: 'https://api.deezer.com/track/3135556' });
        const j = typeof r.data === 'string' ? JSON.parse(r.data) : r.data;
        return { status: r.status, title: j && j.title, artist: j && j.artist && j.artist.name, preview: (j && j.preview) || '', error: j && j.error };
      } catch (e) { return { err: String(e && e.message || e) }; }`);
    if (!info || typeof info !== 'object' || !info.preview) log('(Deezer no contesta desde el emulador: ' + JSON.stringify(info).slice(0, 220) + '; las muestras de verdad no se prueban aquí)');
    else {
      const exp = /exp=(\d{9,11})/.exec(info.preview);
      log('muestra de Deezer «' + info.title + '»: ' + (exp ? 'su dirección caduca en ' + Math.round((+exp[1] * 1000 - Date.now()) / 60000) + ' min' : 'su dirección no dice cuándo caduca') + ' · ' + info.preview.replace(/hmac=.*/, 'hmac=…').slice(0, 170));
      const raw = u => js(`return await new Promise(res => { const a = new Audio(); let done = false; const end = v => { if (done) return; done = true; try { a.pause(); a.removeAttribute('src'); a.load(); } catch {} res(v); }; a.muted = true; a.addEventListener('error', () => end('no se puede (error ' + (a.error && a.error.code) + ')')); a.addEventListener('playing', () => end('suena')); a.addEventListener('canplay', () => end('suena')); setTimeout(() => end('nada en 10 s'), 10000); a.src = ${JSON.stringify(u)}; const p = a.play(); if (p && p.catch) p.catch(() => {}); });`);
      // la misma dirección con la fecha de ayer: lo que le llegaba a la app al tocar una recomendación horas después
      const stale = exp ? info.preview.replace(/exp=\d+/, 'exp=' + (Math.floor(Date.now() / 1000) - 86400)) : '';
      const rFresh = await raw(info.preview), rStale = stale ? await raw(stale) : '';
      log('  una dirección recién pedida: ' + rFresh + (stale ? ' · una caducada: ' + rStale : ''));
      check('una muestra de Deezer recién pedida suena en la app', rFresh === 'suena', rFresh);
      if (stale) {
        const res = await js(`
          const o = window.__orbita, stale = ${JSON.stringify(stale)};
          const r = { id: 'dz:3135556', key: 'prueba|x', title: ${JSON.stringify(info.title || 'Muestra')}, artist: ${JSON.stringify(info.artist || '')}, album: '', art: '', artBig: '', url: stale, full: 200, link: '', akey: 'x', from: ['Kora'], own: false, s: 1, why: 'prueba' };
          o.REC.list.length = 0; o.REC.list.push(r); o.REC.at = Date.now() - 6 * 3600e3;
          const tw = document.querySelector('#toast'); if (tw) tw.hidden = true;
          o.recPlay(0, false);
          await new Promise(z => setTimeout(z, 9000));
          const t = o.trackById(o.state.queue[o.state.qi]), b = document.querySelector('[data-ctl="play"]'), c = document.querySelector('[data-t="cur"]');
          const sec = v => { const m = /(\\d+):(\\d\\d)/.exec(v || ''); return m ? +m[1] * 60 + +m[2] : -1; };
          return { cur: t && t.id, nueva: !!t && t.url !== stale, boton: b ? b.getAttribute('aria-label') : '', t: sec(c && c.textContent), toast: tw && !tw.hidden ? tw.textContent : '', guardada: !!o.REC.list[0] && o.REC.list[0].url !== stale };`);
        shot('muestra-sonando');
        check('una recomendación guardada con la dirección caducada: la app pide la nueva y suena, sin aviso de error', res && res.cur === 'dz:3135556' && res.nueva && /Pausa/i.test(res.boton || '') && res.t > 1 && !res.toast && res.guardada, 'la caducada a pelo: ' + rStale + ' · ' + JSON.stringify(res));
      }
    }
  }

  const st0 = await js(`return await Capacitor.Plugins.NovaAudio.status();`);
  check('el reproductor es el de Kotlin', st0 && st0.player === 'kotlin', JSON.stringify(st0));

  // ---- reproducir
  await js(`window.__orbita.startAll(); return 1;`);
  await sleep(9000);
  shot('sonando');
  const st1 = await js(`return await Capacitor.Plugins.NovaAudio.status();`);
  check('suena y la app sigue abierta', alive() && playing(), sessState());
  check('el servicio de música arrancó (Media3)', st1 && st1.service === true && !st1.serviceError, JSON.stringify(st1));
  check('el servicio está en primer plano mientras suena', fgService() === 'en primer plano', fgService());
  check('sin cierres por error hasta aquí', crashes() === 0, crashText().slice(0, 1500));
  sh('cmd statusbar expand-notifications'); await sleep(2500); shot('aviso'); sh('cmd statusbar collapse'); await sleep(800);
  await js(`location.hash = '#biblioteca'; return 1;`); await sleep(1800); shot('web-biblioteca-sonando');
  await js(`document.querySelector('#main').scrollTop = 400; return 1;`); await sleep(800); shot('web-biblioteca-abajo');
  await js(`location.hash = '#inicio'; return 1;`); await sleep(1000);
  const cur1 = await js(`const o = window.__orbita; return o.trackById(o.state.queue[o.state.qi]).title;`);

  // ---- botones de fuera (auriculares)
  sh('input keyevent KEYCODE_MEDIA_NEXT'); await sleep(3500);
  const cur2 = await js(`const o = window.__orbita; return o.trackById(o.state.queue[o.state.qi]).title;`);
  check('«siguiente» de los auriculares: cambia la canción y la app la sigue', typeof cur2 === 'string' && typeof cur1 === 'string' && cur2 !== cur1 && playing(), `${cur1} → ${cur2} · ${sessState()}`);
  sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(2000);
  const paused = await js(`const o = window.__orbita; return o.decks[o.A].paused;`);
  check('«pausa» de los auriculares', !playing() && paused === true, sessState());
  sh('input keyevent KEYCODE_MEDIA_PLAY'); await sleep(2500);
  check('«play» de los auriculares', playing(), sessState());

  // ---- recargar la parte web con la música sonando: la app la recoge
  const before = await js(`const o = window.__orbita; return { id: o.state.queue[o.state.qi], t: o.decks[o.A].currentTime };`);
  await webStart(true); await sleep(3000);
  const after = await js(`const o = window.__orbita; const d = o.decks[o.A]; return { id: o.state.queue[o.state.qi], t: d.currentTime, paused: d.paused };`);
  check('la parte web se reinicia y recoge lo que suena (misma canción, sigue avanzando)', after && before && after.id === before.id && !after.paused && after.t > before.t && playing(), JSON.stringify({ before, after }));
  shot('recogida');

  // ---- cerrar la app desde las recientes con la música sonando
  let actGone = false;
  for (let k = 0; k < 3 && !actGone; k++) {
    if (k) { log('  (el lanzador del emulador no cerró la tarjeta; lo intento otra vez)'); ui(); }
    sh('input keyevent KEYCODE_APP_SWITCH'); await sleep(2500 + k * 1500); if (!k) shot('recientes');
    sh('input swipe 540 1300 540 150 180'); await sleep(3500);
    sh('input keyevent KEYCODE_HOME'); await sleep(1500);
    actGone = !new RegExp(PKG + '/').test(sh('dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity"'));
  }
  check('cerrada desde las recientes: la música sigue', actGone && alive() && playing(), `actividad cerrada ${actGone} · proceso ${alive()} · ${sessState()} · ${fgService()}`);
  sh('cmd statusbar expand-notifications'); await sleep(2500); shot('aviso-app-cerrada'); sh('cmd statusbar collapse'); await sleep(800);
  sh('input keyevent KEYCODE_MEDIA_NEXT'); await sleep(3500);
  check('con la app cerrada, «siguiente» funciona', playing(), sessState());

  // ---- volver a abrir: recoge la canción
  sh(`am start -n ${PKG}/.MainActivity`); await sleep(9000);
  await cdpConnect();
  const re = await js(`const d = document.querySelector('[data-ctl="play"]'); return { boton: d ? d.getAttribute('aria-label') : '', titulo: (document.querySelector('.np-title, #npTitle, #miniTitle') || {}).textContent || '' };`);
  shot('reabierta');
  check('al volver a abrirla, sigue sonando y la app lo muestra', playing() && re && /Pausa/i.test(re.boton || ''), JSON.stringify(re) + ' · ' + sessState());

  // ---- en pausa, cerrada, y «play» de los auriculares
  sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(2500);
  sh('input keyevent KEYCODE_APP_SWITCH'); await sleep(2500); sh('input swipe 540 1300 540 150 180'); await sleep(3000); sh('input keyevent KEYCODE_HOME'); await sleep(2000);
  sh(`am kill ${PKG}`); await sleep(1500);
  log('tras cerrar en pausa: proceso vivo =', alive(), '·', sessState());
  sh('input keyevent KEYCODE_MEDIA_PLAY'); await sleep(8000);
  check('«play» con Órbita cerrada: vuelve la música', playing(), `proceso ${alive()} · ${sessState()} · ${fgService()}`);
  sh('cmd statusbar expand-notifications'); await sleep(2500); shot('reanudada'); sh('cmd statusbar collapse');
  check('ningún cierre por error en toda la prueba', crashes() === 0, crashText().slice(0, 2500));
  // ================= lo último: un enlace de la nativa se abre fuera de la app =================
  // (va al final a propósito: el navegador del emulador pesa tanto que a veces lo tumba todo)
  {
    const act = () => sh('dumpsys activity activities | grep -m1 topResumedActivity').trim();
    sh('input keyevent KEYCODE_MEDIA_PAUSE'); await sleep(1200);
    // (la app se había cerrado en el paso anterior: se abre de nuevo, con su modo de pruebas)
    sh(`am start -n ${PKG}/.MainActivity`); await sleep(9000);
    await webStart(true); await sleep(3000);
    await goView('inicio'); await sleep(2500);
    // (la fluidez de «Ajustes», la pantalla más larga, también aquí al final)
    {
      await goView('ajustes'); await sleep(1800);
      sh(`dumpsys gfxinfo ${PKG} reset`);
      for (let k = 0; k < 3; k++) { sh('input swipe 540 1500 540 350 250'); await sleep(900); }
      for (let k = 0; k < 3; k++) { sh('input swipe 540 350 540 1500 250'); await sleep(900); }
      log('fluidez de «Ajustes» nativa:', gfx());
      check('«Ajustes» nativa aguanta que se deslice deprisa (sin cierres)', alive() && crashes() === 0, crashText().slice(0, 2500));
      await goView('inicio'); await sleep(2000);
    }
    // (y la fluidez de «Ahora» al deslizar, también al final: en el emulador, que dibuja sin gráfica, es lo más pesado de toda la prueba)
    {
      const O = `const o = window.__orbita, N = o.NUI;`;
      log('justo antes de deslizar «Ahora»: proceso vivo =', alive());
      sh(`dumpsys gfxinfo ${PKG} reset`);
      for (let k = 0; k < 3; k++) { sh('input swipe 540 1450 540 400 250'); await sleep(900); sh('input swipe 540 400 540 1450 250'); await sleep(900); }
      log('fluidez de «Ahora» nativa:', gfx());
      await js(`${O} N.classic = true; return 1;`); await sleep(1200);
      sh(`dumpsys gfxinfo ${PKG} reset`);
      for (let k = 0; k < 3; k++) { sh('input swipe 540 1450 540 400 250'); await sleep(900); sh('input swipe 540 400 540 1450 250'); await sleep(900); }
      log('fluidez de «Ahora» de siempre (mismo gesto):', gfx());
      await js(`${O} N.classic = false; document.querySelector('#main').scrollTop = 0; return 1;`); await sleep(1500);
      check('«Ahora» nativa aguanta que se deslice deprisa (sin cierres)', alive() && crashes() === 0, crashText().slice(0, 2500));
    }
    const inj = await js(`const o = window.__orbita; o.state.update = { version: '9.9', url: 'https://example.com/orbita-prueba', notes: 'Aviso de prueba de versión nueva.' }; localStorage.removeItem('orbita:skipUpdate'); o.renderUpdate(); return !document.querySelector('#updBanner').hidden;`); await sleep(1500);
    await js(`await Capacitor.Plugins.OrbitaUi.scroll({ y: 0 }); return 1;`); await sleep(900);
    const a0 = act();
    await tapText('Descargar', 3500);
    const a1 = act(); shot('ahora-descargar');
    check('«Descargar» (aviso de versión nueva, en la nativa) abre el enlace fuera de la app', inj === true && !a1.includes(PKG + '/'), `antes: ${a0.slice(-70)} · después: ${a1.slice(-90)}`);
    const other = (a1.match(/u0 ([\w.]+)\//) || [])[1];
    if (other && other !== PKG) sh(`am force-stop ${other}`);
  }
  // ================= FASE 4 · parte 1 (5.3): el fondo y la barra de abajo, de Android =================
  sh(`am start -n ${PKG}/.MainActivity`); await sleep(3000);
  await goView('inicio'); await sleep(1500);
  await base53();
  await pass53();
  // el cerebro, al final de todo (carga el modelo en Android y, para compararlo, también en la parte web: es lo que más memoria pide)
  await goView('inicio'); await sleep(1500);
  await cerebro52();
} catch (e) {
  check('la prueba terminó sin errores propios', false, e && e.stack || e);
}
saveLogs();
finish('');
