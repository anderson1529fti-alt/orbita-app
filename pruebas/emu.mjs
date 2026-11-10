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
async function js(expr) {
  const r = await cdp('Runtime.evaluate', { expression: `(async () => { ${expr} })()`, awaitPromise: true, returnByValue: true });
  if (r.error) return { __err: r.error.message };
  if (r.result && r.result.exceptionDetails) return { __err: (r.result.exceptionDetails.exception || {}).description || r.result.exceptionDetails.text };
  return r.result && r.result.result ? r.result.result.value : undefined;
}
// arranca (o recarga) la parte web con el modo de pruebas y sin las pantallas de bienvenida
async function webStart(reload) {
  if (!(await cdpConnect())) return false;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `window.ORBITA_DEBUG = true; try { for (const [k, v] of Object.entries({ onboarded: true, newsSeen: '${VER}', devWelcomeDone: true, intros: false, lastUpdateCheck: Date.now() + 864e5 })) localStorage.setItem('orbita:' + k, JSON.stringify(v)); } catch (e) {}` });
  if (reload) { await cdp('Page.reload'); await sleep(9000); }
  return true;
}
const session = () => sh('dumpsys media_session');
const sessState = () => { const d = session(); const i = d.indexOf(PKG); if (i < 0) return 'sin sesión'; const part = d.slice(i, i + 3000); const st = (part.match(/state=PlaybackState \{state=(\w+)\((\d+)\)|state=PlaybackState \{state=(\d+)/) || []); const md = (part.match(/metadata: size=\d+, description=([^\n]*)/) || [])[1] || ''; return `estado ${st[1] || st[3] || '?'}${st[2] ? '(' + st[2] + ')' : ''} · activa ${/active=true/.test(part)} · ${md.trim()}`; };
const playing = () => /state=(PLAYING\(3\)|3[,}])/.test((() => { const d = session(); const i = d.indexOf(PKG); return i < 0 ? '' : d.slice(i, i + 3000); })());
const alive = () => /^\d+/.test(sh(`pidof ${PKG}`).trim());
const fgService = () => { const d = sh(`dumpsys activity services ${PKG}`); return /isForeground=true/.test(d) ? 'en primer plano' : /ServiceRecord/.test(d) ? 'servicio sin primer plano' : 'sin servicio'; };
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
  for (const v of ['biblioteca', 'listas', 'tu', 'ajustes', 'inicio']) { await js(`const b = document.querySelector('[data-view="${v}"]'); if (b) b.click(); return 1;`); await sleep(1800); shot('web-' + v); }
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
  await js(`const b = document.querySelector('[data-view="biblioteca"]'); if (b) b.click(); return 1;`); await sleep(1500); shot('web-biblioteca-sonando');
  await js(`const b = document.querySelector('[data-view="inicio"]'); if (b) b.click(); return 1;`); await sleep(1000);
  const cur1 = await js(`const o = window.__orbita; return o.trackById(o.state.queue[o.state.qi]).title;`);

  // ---- botones de fuera (auriculares)
  sh('input keyevent KEYCODE_MEDIA_NEXT'); await sleep(3500);
  const cur2 = await js(`const o = window.__orbita; return o.trackById(o.state.queue[o.state.qi]).title;`);
  check('«siguiente» de los auriculares: cambia la canción y la app la sigue', cur2 && cur2 !== cur1 && playing(), `${cur1} → ${cur2} · ${sessState()}`);
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
