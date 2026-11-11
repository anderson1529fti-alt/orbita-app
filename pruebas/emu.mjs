// Pruebas en el emulador: instala la app, le pone música, la maneja (por dentro, con las herramientas de depuración
// del WebView, y por fuera, con los botones de Android) y guarda capturas y registros en salida/.
import { execSync, spawnSync } from 'node:child_process';
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
setTimeout(() => finish('pasó de 22 minutos'), 22 * 60000).unref();
let adbFails = 0;
const adb = (c, o = {}) => { if (adbFails > 12) finish('el emulador dejó de contestar'); try { const r = execSync('adb ' + c, { encoding: 'utf8', timeout: 45000, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64e6, ...o }); adbFails = 0; return r; } catch (e) { if (e.killed || /ETIMEDOUT|offline|no devices/.test(String(e.message) + e.stderr)) adbFails++; return 'ERR ' + (e.stdout || '') + (e.stderr || e.message); } };
const sh = c => adb(`shell "${c.replace(/"/g, '\\"')}"`);
let shotN = 0;
const shot = name => { const f = `${OUT}/${String(++shotN).padStart(2, '0')}-${name}.png`; const r = spawnSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64e6 }); if (r.stdout && r.stdout.length > 1000) fs.writeFileSync(f, r.stdout); return f; };
const check = (name, ok, info = '') => { res.push({ name, ok: !!ok, info: String(info).slice(0, 600) }); log((ok ? 'ok    ' : 'FALLA ') + '· ' + name + (info !== '' ? ' · ' + String(info).slice(0, 600) : '')); };

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

const crashes = () => adb('logcat -d -b crash').split('\n').filter(l => l.trim()).length;

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
  log('música en el emulador:', sh('content query --uri content://media/external/audio/media --projection title').split('\n').filter(l => /title=/.test(l)).length, 'canciones');

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
  check('sin cierres al abrir la pantalla nativa', alive() && crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
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
  await goView('inicio'); await sleep(1000);
  const off = await js(`return window.__orbita.NUI.shown;`); shot('nativa-otra-pestana');
  await goView('biblioteca'); await sleep(1500);
  const on2 = await js(`return window.__orbita.NUI.shown;`);
  check('al cambiar de pestaña se quita, y al volver vuelve', off === false && on2 === true, `${off} → ${on2}`);
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
  check('sin cierres por error en las pruebas de la pantalla nativa', alive() && crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
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
  check('sin cierres por error tras reiniciar la parte web con la pantalla nativa', alive() && crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
  await goView('inicio'); await sleep(800);
  // ================= fin de la fase 2 =================

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
      const bub = find(cn, 'Puedes pedirme'); if (bub) { const [bx, by] = center(bub); sh(`input swipe ${bx} ${by} ${bx} ${by} 900`); await sleep(1200); }
      check('mantener pulsado un mensaje (copiarlo) no rompe nada', alive() && crashes() === 0 && (await js(`return window.__orbita.NUI.shown;`)) === true);
      cn = ui();
    }
    check('tocar una sugerencia: sale mi mensaje y Nova contesta', cn.filter(n => n.text === 'Ayuda').length === 2 && !!find(cn, 'Puedes pedirme'), cn.filter(n => n.text).map(n => n.text.slice(0, 18)).join(' | ').slice(0, 300));
    // escribir con el teclado
    const inp = cn.find(n => n.text === 'Escribe a la DJ…') || cn.find(n => n.desc === 'Mensaje para la DJ');
    if (inp) {
      tap(...center(inp)); await sleep(2200); sh('input text gracias'); await sleep(1800);
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
    const cl = await js(`const N = window.__orbita.NUI; return { cerrada: document.querySelector('#chat').hidden, shown: N.shown, st: await Capacitor.Plugins.OrbitaUi.status() };`);
    const kb2 = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
    ui(); shot('chat-cerrada');
    check('«Cerrar» cierra la conversación, quita la nativa y baja el teclado', cl && cl.cerrada && !cl.shown && cl.st.shown === false && !kb2, JSON.stringify(cl) + ' · teclado ' + kb2);
    check('sin cierres por error con la conversación nativa', alive() && crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
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
          const t = o.trackById(o.state.queue[o.state.qi]), d = o.decks[o.A];
          return { cur: t && t.id, nueva: !!t && t.url !== stale, paused: d ? d.paused : null, t: d ? d.currentTime : -1, deck: o.A, toast: tw && !tw.hidden ? tw.textContent : '', guardada: !!o.REC.list[0] && o.REC.list[0].url !== stale };`);
        shot('muestra-sonando');
        check('una recomendación guardada con la dirección caducada: la app pide la nueva y suena, sin aviso de error', res && res.cur === 'dz:3135556' && res.nueva && res.paused === false && res.t > 1 && !res.toast && res.guardada, 'la caducada a pelo: ' + rStale + ' · ' + JSON.stringify(res));
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
  check('sin cierres por error hasta aquí', crashes() === 0, adb('logcat -d -b crash').slice(0, 1500));
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
  check('ningún cierre por error en toda la prueba', crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
} catch (e) {
  check('la prueba terminó sin errores propios', false, e && e.stack || e);
}
fs.writeFileSync(`${OUT}/logcat.txt`, adb('logcat -d -v time *:W').split('\n').slice(-6000).join('\n'));
fs.writeFileSync(`${OUT}/logcat-app.txt`, adb('logcat -d -v time').split('\n').filter(l => /orbita|Orbita|Nova|Media3|MediaSess|AndroidRuntime|Capacitor\/Console|chromium/i.test(l)).slice(-4000).join('\n'));
fs.writeFileSync(`${OUT}/cierres.txt`, adb('logcat -d -b crash'));
fs.writeFileSync(`${OUT}/sesion.txt`, session());
finish('');
