// Pruebas en el emulador: instala la app, le pone música, la maneja (por dentro, con las herramientas de depuración
// del WebView, y por fuera, con los botones de Android) y guarda capturas y registros en salida/.
import { execSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';

const OUT = 'salida'; fs.mkdirSync(OUT, { recursive: true });
const PKG = JSON.parse(fs.readFileSync('capacitor.config.json', 'utf8')).appId;
const VER = JSON.parse(fs.readFileSync('package.json', 'utf8')).version.split('.').slice(0, 2).join('.');
const res = [], log = (...a) => { const s = a.join(' '); console.log(s); fs.appendFileSync(`${OUT}/pasos.txt`, s + '\n'); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const adb = (c, o = {}) => { try { return execSync('adb ' + c, { encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64e6, ...o }); } catch (e) { return 'ERR ' + (e.stdout || '') + (e.stderr || e.message); } };
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
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `window.ORBITA_DEBUG = true; try { for (const [k, v] of Object.entries({ onboarded: true, newsSeen: '${VER}', devWelcomeDone: true, intros: false, lastUpdateCheck: Date.now() + 864e5 })) localStorage.setItem('orbita:' + k, JSON.stringify(v)); if (localStorage.getItem('orbita:nativeUi') == null) localStorage.setItem('orbita:nativeUi', 'false'); } catch (e) {}` });
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
  await js(`document.querySelector('#main').scrollTop = 0; return 1;`); await sleep(700); shot('par-web-arriba');
  const web0 = await js(WEBQ);
  await js(`document.querySelector('#main').scrollTop = 99999; return 1;`); await sleep(900); shot('par-web-abajo');
  const web1 = await js(WEBQ);
  await js(`document.querySelector('#main').scrollTop = 0; return 1;`);
  // --- se enciende con su interruptor
  await goView('ajustes'); await sleep(1000);
  const tog = await js(`const b = document.querySelector('#tNui'); if (!b || document.querySelector('#nuiRow').hidden) return 'sin interruptor'; b.scrollIntoView({ block: 'center' }); b.click(); await new Promise(r => setTimeout(r, 300)); return b.getAttribute('aria-pressed') + ' · ' + document.querySelector('#nuiNote').textContent;`);
  await sleep(600); shot('ajustes-interruptor');
  check('Ajustes: el interruptor de «Mi música» nativa se enciende', /^true/.test(String(tog)), tog);
  await goView('biblioteca'); await sleep(2500);
  const nui1 = await js(`const N = window.__orbita.NUI; return { on: N.on, shown: N.shown, err: N.err, rect: N.rect };`);
  shot('par-nativa-arriba');
  check('«Mi música» nativa a la vista', nui1 && nui1.on && nui1.shown && !nui1.err, JSON.stringify(nui1));
  check('sin cierres al abrir la pantalla nativa', alive() && crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
  const txt = nodes => nodes.filter(n => n.text).map(n => ({ t: n.text.slice(0, 40), b: n.b }));
  let nodes = ui();
  const nat0 = txt(nodes);
  check('la pantalla nativa enseña el título, la música del móvil y los filtros', ['BIBLIOTECA', 'Añadir canciones', 'Música del móvil', 'Todas', 'Favoritas', 'Del móvil', 'Reproducir todo'].every(t => find(nodes, t)), nodes.filter(n => n.text).map(n => n.text.slice(0, 30)).join(' | '));
  // hasta el final de la lista
  for (let k = 0; k < 3; k++) { sh('input swipe 540 1450 540 350 250'); await sleep(900); }
  await sleep(800); shot('par-nativa-abajo');
  nodes = ui(); const nat1 = txt(nodes);
  fs.writeFileSync(`${OUT}/paridad.json`, JSON.stringify({ web0, web1, nat0, nat1 }));
  const seen = titlesOnScreen(nodes);
  check('se ven las canciones', seen.length >= 3, seen.join(' | '));
  // --- tocar una canción: suena esa
  const pick = seen.includes('Pulso') ? 'Pulso' : seen[0];
  await tapText(pick, 4000);
  const cur = await js(`const o = window.__orbita, t = o.trackById(o.state.queue[o.state.qi]); return { title: t && t.title, paused: o.decks[o.A].paused, q: o.state.queue.length };`);
  shot('nativa-sonando');
  check('tocar una canción en la pantalla nativa la pone', cur && cur.title === pick && cur.paused === false && playing(), JSON.stringify(cur) + ' · ' + sessState());
  // --- el corazón de esa fila
  const geo = await js(`const r = document.querySelector('#main').getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height, dpr: devicePixelRatio };`);
  const rowY = t => { const n = find(ui(), t); return n ? center(n)[1] + 10 * geo.dpr : null; };
  const likeX = (geo.x + geo.w - 16 - 7 - 40 - 10 - 17) * geo.dpr, editX = (geo.x + geo.w - 16 - 7 - 30) * geo.dpr, delX = (geo.x + geo.w - 16 - 7 - 10) * geo.dpr;
  let y = rowY(pick);
  if (y) {
    tap(likeX, y); await sleep(1500);
    const l1 = await js(`return !!window.__orbita.state.tracks.find(t => t.title === ${JSON.stringify(pick)}).liked;`);
    shot('nativa-favorita');
    check('el corazón marca la canción como favorita', l1 === true, l1);
  } else check('encuentro la fila para probar el corazón', false, pick);
  // --- filtro «Favoritas»
  const toTop = async () => { for (let k = 0; k < 3; k++) { sh('input swipe 540 500 540 1450 200'); await sleep(700); } };
  const toTools = async () => { await toTop(); sh('input swipe 540 1400 540 700 600'); await sleep(1200); };
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
    sh('input swipe 540 1400 540 900 600'); await sleep(1200);
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
    sh('input text kora'); await sleep(2000); shot('nativa-buscar');
    const hit = titlesOnScreen(ui());
    check('buscar «kora» deja las dos canciones de Kora (y se ven con el teclado abierto)', hit.length === 2 && hit.includes('Pulso') && hit.includes('Luz de las siete'), hit.join(' | '));
    sh('input keyevent KEYCODE_ENTER'); await sleep(1500); shot('nativa-buscar-sin-teclado');
    const kb = /mInputShown=true/.test(sh('dumpsys input_method | grep mInputShown'));
    check('«buscar» en el teclado lo cierra y la búsqueda sigue', !kb && titlesOnScreen(ui()).length === 2, 'teclado ' + kb);
    // la ✕ de la caja la vacía
    const box = find(ui(), 'kora');
    if (box) { tap((geo.x + geo.w - 16 - 15 - 11) * geo.dpr, center(box)[1]); await sleep(1500); }
    check('la ✕ vacía la búsqueda', titlesOnScreen(ui()).length >= 3 && !!find(ui(), 'Filtrar por título'), titlesOnScreen(ui()).join(' | '));
  } else check('encuentro la caja de búsqueda', false);
  // --- editar: se abre el diálogo de la app y la pantalla nativa se aparta
  nodes = ui(); const r2 = nodes.filter(n => SONGS.includes(n.text))[0];
  if (r2) {
    tap(editX, center(r2)[1] + 10 * geo.dpr); await sleep(1800);
    const ed = await js(`return { shown: window.__orbita.NUI.shown, dialog: !document.querySelector('#editOverlay').hidden, titulo: document.querySelector('#editTitle').value };`);
    shot('nativa-editar');
    check('«editar» abre el diálogo de la app (con esa canción) y la pantalla nativa se aparta', ed && ed.shown === false && ed.dialog === true && ed.titulo === r2.text, JSON.stringify(ed));
    await js(`document.querySelector('#editOverlay').hidden = true; return 1;`); await sleep(1200);
    sh('input keyevent KEYCODE_ENTER'); await sleep(300);
    const ed2 = await js(`return { shown: window.__orbita.NUI.shown, kb: 0 };`);
    shot('nativa-tras-editar');
    check('al cerrar el diálogo, la pantalla nativa vuelve', ed2 && ed2.shown === true, JSON.stringify(ed2));
    // borrar pide un segundo toque
    const n0 = await js(`return window.__orbita.state.tracks.length;`);
    tap(delX, center(r2)[1] + 10 * geo.dpr); await sleep(1200); shot('nativa-borrar');
    const arm = !!find(ui(), '¿Borrar?'), n1 = await js(`return window.__orbita.state.tracks.length;`);
    check('la papelera pide confirmar («¿Borrar?») y no borra con un toque', arm && n1 === n0, `${arm} · ${n0} → ${n1}`);
    await sleep(3400);
    check('si no se confirma, a los 3 s vuelve a su sitio', !find(ui(), '¿Borrar?'));
  }
  // --- aviso corto encima de la lista
  await js(`window.__orbita.toast('Aviso de prueba encima de la lista'); return 1;`); await sleep(900); shot('nativa-aviso');
  check('los avisos de la app se ven encima de la pantalla nativa', !!find(ui(), 'Aviso de prueba'), '');
  // --- otra pestaña y volver
  await goView('inicio'); await sleep(1000);
  const off = await js(`return window.__orbita.NUI.shown;`); shot('nativa-otra-pestana');
  await goView('biblioteca'); await sleep(1500);
  const on2 = await js(`return window.__orbita.NUI.shown;`);
  check('al cambiar de pestaña se quita, y al volver vuelve', off === false && on2 === true, `${off} → ${on2}`);
  // --- el reproductor a pantalla completa por encima
  await js(`document.querySelector('[data-act="np-open"]').click(); return 1;`); await sleep(700); shot('nativa-reproductor-abriendo'); await sleep(1800);
  const pl = await js(`return { shown: window.__orbita.NUI.shown, np: !document.querySelector('#np').hidden };`); shot('nativa-reproductor');
  check('el reproductor a pantalla completa se abre por encima (la nativa se aparta)', pl && pl.shown === false && pl.np === true, JSON.stringify(pl));
  sh('input keyevent KEYCODE_BACK'); await sleep(2200);
  const pl2 = await js(`return { shown: window.__orbita.NUI.shown, np: !document.querySelector('#np').hidden, view: window.__orbita.state.view };`); shot('nativa-reproductor-cerrado');
  check('al cerrarlo, la nativa vuelve', pl2 && pl2.shown === true && pl2.np === false, JSON.stringify(pl2));
  // --- fluidez al deslizar la lista
  sh(`dumpsys gfxinfo ${PKG} reset`);
  for (let k = 0; k < 4; k++) { sh('input swipe 540 1450 540 400 250'); await sleep(700); sh('input swipe 540 400 540 1450 250'); await sleep(700); }
  log('fluidez de la lista nativa:', gfx());
  fs.writeFileSync(`${OUT}/gfx.txt`, adb(`shell dumpsys gfxinfo ${PKG}`).slice(0, 6000));
  shot('nativa-final');
  check('sin cierres por error en las pruebas de la pantalla nativa', alive() && crashes() === 0, adb('logcat -d -b crash').slice(0, 2500));
  await goView('inicio'); await sleep(800);
  // ================= fin de la fase 2 =================

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
  sh('input keyevent KEYCODE_APP_SWITCH'); await sleep(2500); shot('recientes');
  sh('input swipe 540 1300 540 150 180'); await sleep(3500);
  sh('input keyevent KEYCODE_HOME'); await sleep(1500);
  const actGone = !new RegExp(PKG + '/').test(sh('dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity"'));
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
fs.writeFileSync(`${OUT}/resultado.json`, JSON.stringify(res, null, 1));
log(`\n${res.filter(r => r.ok).length} de ${res.length} bien`);
process.exit(0);
