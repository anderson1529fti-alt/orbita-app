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
function finish(why) {
  try {
    if (why) log('\nLA PRUEBA SE CORTÓ: ' + why);
    fs.writeFileSync(`${OUT}/resultado.json`, JSON.stringify(res, null, 1));
    log(`\n${res.filter(r => r.ok).length} de ${res.length} bien`);
  } catch {}
  process.exit(0);
}
setTimeout(() => finish('pasó de 41 minutos'), 41 * 60000).unref();
let adbFails = 0;
const adb = (c, o = {}) => { if (adbFails > 12) finish('el emulador dejó de contestar'); try { const r = execSync('adb ' + c, { encoding: 'utf8', timeout: 45000, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64e6, ...o }); adbFails = 0; return r; } catch (e) { if (e.killed || /ETIMEDOUT|offline|no devices/.test(String(e.message) + e.stderr)) adbFails++; return 'ERR ' + (e.stdout || '') + (e.stderr || e.message); } };
const sh = c => adb(`shell "${c.replace(/"/g, '\\"')}"`);
let shotN = 0;
const shot = name => { const f = `${OUT}/${String(++shotN).padStart(2, '0')}-${name}.png`; const r = spawnSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64e6 }); if (r.stdout && r.stdout.length > 1000) fs.writeFileSync(f, r.stdout); return f; };
const check = (name, ok, info = '') => { res.push({ name, ok: !!ok, info: String(info).slice(0, 600) }); log((ok ? 'ok    ' : 'FALLA ') + '· ' + name + (info !== '' ? ' · ' + String(info).slice(0, 600) : '')); };

// lo que va diciendo Android, guardado sobre la marcha (si el emulador se cae a mitad, al final ya no se le puede preguntar)
try { const fd = fs.openSync(`${OUT}/logcat-vivo.txt`, 'w'); const lc = spawn('adb', ['logcat', '-v', 'time', '*:W'], { stdio: ['ignore', fd, 'ignore'] }); lc.unref(); } catch {}

// ---- el WebView por dentro (Chrome DevTools Protocol)
let ws = null, msgId = 0; const pending = new Map();
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
  if (!ws || ws.readyState !== 1) await cdpConnect();
  const r = await cdp('Runtime.evaluate', { expression: `(async () => { ${expr} })()`, awaitPromise: true, returnByValue: true });
  if (r.error && retry) { log('  (el WebView no contesta: ' + r.error.message + '; reconecto)'); await cdpConnect(); return js(expr, false); }
  if (r.error) return { __err: r.error.message };
  if (r.result && r.result.exceptionDetails) return { __err: (r.result.exceptionDetails.exception || {}).description || r.result.exceptionDetails.text };
  return r.result && r.result.result ? r.result.result.value : undefined;
}
// arranca (o recarga) la parte web con el modo de pruebas y sin las pantallas de bienvenida
async function webStart(reload) {
  if (!(await cdpConnect())) return false;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `window.ORBITA_DEBUG = true; try { for (const [k, v] of Object.entries({ onboarded: true, newsSeen: '${VER}', devWelcomeDone: true, intros: false, lastUpdateCheck: Date.now() + 864e5 })) localStorage.setItem('orbita:' + k, JSON.stringify(v)); if (localStorage.getItem('orbita:nativeUi') == null && !localStorage.getItem('pruebaDeFabrica')) localStorage.setItem('orbita:nativeUi', 'false'); } catch (e) {}` });
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
  for (const m of x.matchAll(/<node [^>]*>/g)) {
    const g = k => { const r = m[0].match(new RegExp(' ' + k + '="([^"]*)"')); return r ? r[1].replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n)) : ''; };
    const b = (g('bounds').match(/\d+/g) || []).map(Number);
    if (b.length === 4) out.push({ text: g('text'), desc: g('content-desc'), cls: g('class'), b });
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
  log('música en el emulador:', inStore(), 'canciones');

  // que los avisos de «no responde» del propio emulador no tapen la pantalla
  sh('settings put global hide_error_dialogs 1'); sh('settings put secure anr_show_background 0');
  // ---- instalar y abrir
  const apk = 'android/app/build/outputs/apk/debug/app-debug.apk';
  const inst = adb(`install -r -g ${apk}`, { timeout: 240000 });
  check('la app se instala', /Success/.test(inst), inst.trim().split('\n').pop());
  for (const p of ['READ_MEDIA_AUDIO', 'POST_NOTIFICATIONS', 'RECORD_AUDIO']) sh(`pm grant ${PKG} android.permission.${p}`);
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
    const ed = await js(`return { shown: window.__orbita.NUI.shown, dialog: !document.querySelector('#editOverlay').hidden, titulo: document.querySelector('#editTitle').value };`);
    shot('nativa-editar');
    check('«editar» abre el diálogo de la app (con esa canción) y la pantalla nativa se aparta', ed && ed.shown === false && ed.dialog === true && ed.titulo === r2.text, JSON.stringify(ed));
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
      if (/documentsui/i.test(topAct())) { sh('input keyevent KEYCODE_BACK'); await sleep(1800); }
    }
    check('tras el selector, la app sigue abierta y la pantalla nativa vuelve', alive() && (await js(`await new Promise(r => setTimeout(r, 600)); return window.__orbita.NUI.shown;`)) === true, topAct().slice(0, 160));
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
    // --- la portada en lugar del disco; y con un visualizador, la de siempre
    await js(`window.__orbita.setHeroMode('art'); return 1;`); await sleep(2500); ui(); shot('ahora-con-portada');
    const ha = await st();
    await js(`window.__orbita.setHeroMode('viz'); return 1;`); await sleep(1500); shot('ahora-con-visualizador');
    const hv = await js(`${O} return { shown: N.shown, vis: getComputedStyle(document.querySelector('#dj')).visibility };`);
    await js(`window.__orbita.setHeroMode('vinyl'); return 1;`); await sleep(1800);
    const hb = await st();
    check('con la portada arriba sigue nativa; con un visualizador, la de siempre; y de vuelta al disco, nativa', ha && ha.shown && ha.screen === 'inicio' && hv && hv.shown === false && hv.vis === 'visible' && hb && hb.shown && hb.screen === 'inicio', JSON.stringify([ha && ha.shown, hv, hb && hb.shown]));
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
    check('«Buscar» sale nativa', !!b0 && b0.shown && b0.screen === 'buscar' && b0.mirOk && b0.st.v === 6 && b0.st.screen === 'buscar' && !!find(bn, 'BUSCAR') && !!find(bn, 'Busca cualquier canción'), JSON.stringify(b0) + ' · ' + bn.filter(n => n.text).map(n => n.text.slice(0, 20)).join(' | ').slice(0, 300));
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
    await js(`document.querySelector('#newsOverlay').hidden = false; return 1;`); await sleep(1300);
    const ov = await js(`${O} return { shown: N.shown, web: document.querySelector('#main').scrollTop };`); shot('ajustes-con-ventana');
    await js(`document.querySelector('#newsOverlay').hidden = true; return 1;`); await sleep(1800);
    const bk = await js(`${O} return { shown: N.shown, screen: N.screen, nat: (await Capacitor.Plugins.OrbitaUi.status()).y };`); shot('ajustes-sin-ventana');
    check('con una ventana encima, «Ajustes» se aparta (la de siempre a la misma altura) y al cerrarla vuelve donde estaba', !!sy && Math.abs(sy.web - 2600) < 3 && Math.abs(sy.nat - 2600) < 3 && !!ov && ov.shown === false && Math.abs(ov.web - 2600) < 3 && !!bk && bk.shown && bk.screen === 'ajustes' && Math.abs(bk.nat - 2600) < 3, JSON.stringify([sy, ov, bk]));
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
    await js(`document.querySelector('#sleepOverlay').hidden = false; return 1;`); await sleep(900);
    const as1 = await js(`return window.__orbita.NUI.shown;`); shot('chat-con-ventana');
    await js(`document.querySelector('#sleepOverlay').hidden = true; return 1;`); await sleep(1200);
    const as2 = await js(`const N = window.__orbita.NUI; return N.shown && N.screen === 'chat';`);
    check('si una ventana de la app se pone encima, la nativa se aparta y luego vuelve', as1 === false && as2 === true, `${as1} → ${as2}`);
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
} catch (e) {
  check('la prueba terminó sin errores propios', false, e && e.stack || e);
}
fs.writeFileSync(`${OUT}/logcat.txt`, adb('logcat -d -v time *:W').split('\n').slice(-6000).join('\n'));
fs.writeFileSync(`${OUT}/logcat-app.txt`, adb('logcat -d -v time').split('\n').filter(l => /orbita|Orbita|Nova|Media3|MediaSess|AndroidRuntime|Capacitor\/Console|chromium/i.test(l)).slice(-4000).join('\n'));
fs.writeFileSync(`${OUT}/cierres.txt`, adb('logcat -d -b crash'));
fs.writeFileSync(`${OUT}/sesion.txt`, session());
finish('');
