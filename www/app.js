/* MAIN: the app itself. The opening animation starts it (window.ORBITA_LOAD) once its own motion is over, so the two never compete for the phone */
function orbitaMain() {
(() => {
'use strict';
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pad = n => String(n).padStart(2, '0');
const fmt = s => { if (!isFinite(s) || s <= 0) return '0:00'; s = Math.round(s); return Math.floor(s / 60) + ':' + pad(s % 60); };
const pick = a => a[Math.floor(Math.random() * a.length)];
const shuffleArr = a => { a = a.slice(); for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
const hashHue = s => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return h % 360; };
const cap = s => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const clamp01 = v => Math.max(0, Math.min(1, v));
const norm = s => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();
const uid = p => p + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const gb = b => b >= 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.round(b / 1e6) + ' MB';
const idle = fn => (window.requestIdleCallback ? requestIdleCallback(fn, { timeout: 1500 }) : setTimeout(fn, 200));
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const isMobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
const root = document.documentElement;
const STANDALONE = !!window.ORBITA_STANDALONE;
const canRecord = STANDALONE && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia) && typeof MediaRecorder !== 'undefined';
/* Native Android app (Capacitor): local files, native voice and lock-screen controls */
const NATIVE = !!(window.Capacitor && typeof window.Capacitor.isNativePlatform === 'function' && window.Capacitor.isNativePlatform());
const capPlugin = name => { try { return NATIVE && window.Capacitor.isPluginAvailable(name) && window.Capacitor.registerPlugin ? window.Capacitor.registerPlugin(name) : null; } catch { return null; } };
const nativeTTS = capPlugin('TextToSpeech');
const nativeMS = capPlugin('MediaSession');
const APP_CFG = window.ORBITA_CONFIG || {};
const ONLINE = NATIVE || (STANDALONE && /^https?:$/.test(location.protocol));
const isInstalled = () => NATIVE || matchMedia('(display-mode: standalone)').matches || matchMedia('(display-mode: fullscreen)').matches;
const LS = {
  get(k, d) { try { const v = localStorage.getItem('orbita:' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('orbita:' + k, JSON.stringify(v)); } catch {} }
};

/* ---------- Moments, moods, themes ---------- */
const DAYS = ['domingo','lunes','martes','miércoles','jueves','viernes','sábado'];
const FRANJAS = [
  {id:'madrugada', from:0,  to:6,  phrase:'de madrugada',  hue:232, energy:'muy baja, íntima', hint:'Luces bajas, volumen suave y canciones que abrazan.'},
  {id:'manana',    from:6,  to:12, phrase:'por la mañana', hue:28,  energy:'empieza suave y va subiendo', hint:'Arrancar sin prisa y coger ritmo poco a poco.'},
  {id:'mediodia',  from:12, to:15, phrase:'al mediodía',   hue:42,  energy:'media-alta, luminosa', hint:'Una pausa con buen ritmo para recargar.'},
  {id:'tarde',     from:15, to:20, phrase:'por la tarde',  hue:8,   energy:'media, constante', hint:'Energía para llegar arriba al final del día.'},
  {id:'noche',     from:20, to:24, phrase:'por la noche',  hue:285, energy:'baja para relajarse, o alta si hay fiesta', hint:'Bajar revoluciones o salir: tú decides.'}
];
function getMoment(d = new Date()) {
  const h = d.getHours(), wd = d.getDay();
  const f = FRANJAS.find(x => h >= x.from && h < x.to);
  let hint = f.hint;
  if (f.id === 'noche' && (wd === 5 || wd === 6)) hint = 'Noche de fin de semana: el plan lo pones tú.';
  else if (wd === 0 && f.id !== 'noche' && f.id !== 'madrugada') hint = 'Domingo: sin ninguna prisa.';
  else if (wd === 1 && f.id === 'manana') hint = 'Lunes por la mañana: hace falta algo que empuje.';
  return { day: DAYS[wd], franja: f, hint, hh: pad(h) + ':' + pad(d.getMinutes()), weekend: wd === 0 || wd === 6 || (wd === 5 && h >= 18), label: DAYS[wd] + ' ' + f.phrase };
}
const MOODS = [
  {id:'auto', label:'Según la hora'}, {id:'energia', label:'Energía'}, {id:'calma', label:'Calma'}, {id:'foco', label:'Concentración'},
  {id:'fiesta', label:'Fiesta'}, {id:'nostalgia', label:'Nostalgia'}, {id:'gris', label:'Día gris'}, {id:'gym', label:'Entreno'}
];
const THEMES = [
  {id:'auto', name:'Según la hora', desc:'Cambia de color con el momento del día'},
  {id:'cover', name:'Según la portada', desc:'Toma el color de la canción que suena'},
  {id:'neon', name:'Neón', desc:'Magenta eléctrico', hue:305, sat:1},
  {id:'oceano', name:'Océano', desc:'Azules y turquesas', hue:192, sat:1},
  {id:'bosque', name:'Bosque', desc:'Verdes profundos', hue:140, sat:.85},
  {id:'fuego', name:'Fuego', desc:'Rojos y naranjas', hue:12, sat:1},
  {id:'oro', name:'Oro', desc:'Dorado cálido', hue:44, sat:.95},
  {id:'lavanda', name:'Lavanda', desc:'Violeta suave', hue:262, sat:.75},
  {id:'medianoche', name:'Medianoche', desc:'Casi sin color', hue:228, sat:.3},
  {id:'custom', name:'Tu color', desc:'Elige el tono y la intensidad'}
];
const SCENARIOS = [
  'banda sonora de un viaje en coche de noche','tu día como si fuera una película indie','cocinar algo nuevo sin receta',
  'lluvia contra la ventana','el último día de vacaciones','un videojuego de los 90','domingo sin planes',
  'concierto en una azotea al atardecer','entrenamiento de protagonista de anime','paseo largo sin destino',
  'fiesta en la cocina con amigos','estudiar en una biblioteca enorme y silenciosa','tren hacia la costa',
  'recuperarse de una semana larga','la escena final de una serie'
];
const IDEAS = ['Algo para el gimnasio','Para estudiar sin distraerme','Tristes pero bonitas','Para cantar a gritos','Para dormir','Como si fuera viernes'];
const SLOTS = [
  {id:'saludo', name:'Saludo', ex:'Suena al empezar una mezcla o la radio. Por ejemplo: «¡Bienvenidos a mi radio!» o «Esto es Órbita FM y aquí mando yo».'},
  {id:'siguiente', name:'Entre canciones', ex:'Frases cortas para pasar de una canción a otra: «Seguimos», «Escucha esta», «Vamos con la siguiente».'},
  {id:'favorita', name:'Antes de una favorita', ex:'Suena antes de las canciones con corazón: «Esta es de mis favoritas».'},
  {id:'ruleta', name:'Ruleta', ex:'Suena cuando gira la ruleta: «¡La ruleta ha hablado!».'},
  {id:'libre', name:'Cuñas libres', ex:'Lo que quieras: un grito, una risa, tu frase de siempre. Se mezclan con las de «Entre canciones».'}
];
const VSTYLES = [
  {id:'natural', name:'Natural', rate:1, pitch:1},
  {id:'radio', name:'Locutora de radio', rate:1.07, pitch:1.04},
  {id:'calma', name:'Tranquila', rate:0.92, pitch:0.97},
  {id:'noche', name:'Voz de noche', rate:0.88, pitch:0.9},
  {id:'energia', name:'Con energía', rate:1.15, pitch:1.08}
];

/* ---------- State ---------- */
const state = {
  tracks: [], playlists: [], clips: [], wishlist: [], view: 'inicio',
  queue: [], qi: -1, source: 'library', shuffle: LS.get('shuffle', false), repeat: LS.get('repeat', 'off'),
  mood: LS.get('mood', 'auto'), len: (v => ['60m', 'inf'].includes(v) ? v : [10, 20, 30, 50].includes(+v) ? +v : +v > 10 ? 20 : 10)(LS.get('len', 10)), filter: 'all', search: '',
  hideDemos: LS.get('hideDemos', false), history: LS.get('history', []),
  mix: null, busy: false, demoStats: LS.get('demoStats', {}), radio: false,
  intros: {}, pendingTalk: null, xfade: LS.get('xfade', 3), net: null,
  theme: LS.get('theme', 'auto'), anim: LS.get('anim', 'full'), heroMode: LS.get('heroMode', 'vinyl'), libLimit: 150, sort: LS.get('sort', 'recent')
};
let storageOk = true;
let aiFn = null, aiState = 'checking', mixCtl = null;

/* ---------- IndexedDB ---------- */
let dbp = null;
function idb() {
  if (!dbp) dbp = new Promise((res, rej) => {
    try {
      const r = indexedDB.open('orbita', 3);
      r.onupgradeneeded = () => {
        const d = r.result; const mk = (n, o) => { if (!d.objectStoreNames.contains(n)) d.createObjectStore(n, o); };
        mk('tracks', {keyPath:'id'}); mk('audio'); mk('playlists', {keyPath:'id'}); mk('voice', {keyPath:'id'}); mk('wishlist', {keyPath:'id'});
        mk('analysis', {keyPath:'id'}); mk('events', {autoIncrement:true}); mk('lyrics', {keyPath:'id'});
      };
      r.onsuccess = () => { const d = r.result; d.onversionchange = () => { try { d.close(); } catch {} }; res(d); }; r.onerror = () => rej(r.error); r.onblocked = () => rej(new Error('blocked'));
    } catch (e) { rej(e); }
  });
  return dbp;
}
async function idbAll(store) {
  try { const d = await idb(); return await new Promise((res, rej) => { const q = d.transaction(store).objectStore(store).getAll(); q.onsuccess = () => res(q.result || []); q.onerror = () => rej(q.error); }); }
  catch { markStorageBad(); return []; }
}
async function idbGet(store, key) {
  try { const d = await idb(); return await new Promise((res, rej) => { const q = d.transaction(store).objectStore(store).get(key); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); }); }
  catch { return undefined; }
}
async function idbPut(store, val, key) {
  if (!storageOk) return false;
  try { const d = await idb(); await new Promise((res, rej) => { const t = d.transaction(store, 'readwrite'); key === undefined ? t.objectStore(store).put(val) : t.objectStore(store).put(val, key); t.oncomplete = res; t.onerror = () => rej(t.error); t.onabort = () => rej(t.error); }); return true; }
  catch { markStorageBad(); return false; }
}
async function idbPutMany(store, vals) {
  if (!storageOk || !vals.length) return false;
  try { const d = await idb(); await new Promise((res, rej) => { const t = d.transaction(store, 'readwrite'); const os = t.objectStore(store); for (const v of vals) os.put(v); t.oncomplete = res; t.onerror = () => rej(t.error); t.onabort = () => rej(t.error); }); return true; }
  catch { return false; }
}
async function idbClear(store) {
  try { const d = await idb(); await new Promise((res, rej) => { const t = d.transaction(store, 'readwrite'); t.objectStore(store).clear(); t.oncomplete = res; t.onerror = () => rej(t.error); }); return true; } catch { return false; }
}
async function idbDel(store, key) {
  try { const d = await idb(); await new Promise((res, rej) => { const t = d.transaction(store, 'readwrite'); t.objectStore(store).delete(key); t.oncomplete = res; t.onerror = () => rej(t.error); }); } catch {}
}
function markStorageBad() { storageOk = false; const b = $('#storageBanner'); if (b) b.hidden = false; }

/* ---------- Tracks, URLs, covers, quality ---------- */
const memAudio = new Map(), urlCache = new Map(), coverCache = new Map();
let tIdx = null, tIdxArr = null, tIdxLen = -1;
function trackById(id) {
  if (tIdxArr !== state.tracks || tIdxLen !== state.tracks.length) { tIdx = new Map(state.tracks.map(t => [t.id, t])); tIdxArr = state.tracks; tIdxLen = state.tracks.length; }
  return tIdx.get(id) || netTracks.get(id);
}
const netTracks = new Map();
function netTrack(o) { const t = Object.assign({ net: true, plays: 0, duration: 0 }, o); netTracks.set(t.id, t); return t; }
const visibleTracks = () => state.tracks.filter(t => !(t.demo && state.hideDemos));
async function blobFor(t) { if (t.demo && !memAudio.has(t.id)) await renderDemoTrack(t.id); return memAudio.get(t.id) || await idbGet('audio', t.id); }
/* Songs that live on the phone are read straight from storage (never copied). Only the few
   around the current one are kept in memory, so seeking is instant and memory stays low. */
const devUrls = new Map(), devBlobs = new Map();
async function deviceUrl(t) {
  if (NA_ON) return t.uri;
  if (devUrls.has(t.id)) { const u = devUrls.get(t.id); devUrls.delete(t.id); devUrls.set(t.id, u); return u; }
  const direct = window.Capacitor.convertFileSrc(t.uri);
  if ((t.size || 0) > 150e6) return direct;
  try {
    const r = await fetch(direct); if (!r.ok) throw new Error(String(r.status));
    let b = await r.blob(); if (!b.size) throw new Error('vacío');
    if (!b.type && t.mime) b = new Blob([b], { type: t.mime });
    const u = URL.createObjectURL(b); devUrls.set(t.id, u); devBlobs.set(t.id, b);
    while (devUrls.size > 4) { const cur = current()?.id, k = [...devUrls.keys()].find(x => x !== cur && x !== t.id); if (k == null) break; const old = devUrls.get(k); devUrls.delete(k); devBlobs.delete(k); setTimeout(() => URL.revokeObjectURL(old), 15000); }
    return u;
  } catch { return null; }
}
/* The song's bytes, for analysis and embedded lyrics */
async function trackBlob(t) {
  if (!t || t.net) return null;
  if (t.dev) {
    if (devBlobs.has(t.id)) return devBlobs.get(t.id);
    if ((t.size || 0) > 80e6) return null;
    try { const r = await fetch(window.Capacitor.convertFileSrc(t.uri)); if (!r.ok) return null; return await r.blob(); } catch { return null; }
  }
  try { return await blobFor(t); } catch { return null; }
}
async function audioUrl(t) {
  if (t.dev) return deviceUrl(t);
  if (urlCache.has(t.id)) return urlCache.get(t.id);
  const blob = await blobFor(t); if (!blob) return null;
  if (!t.size) { t.size = blob.size; t.codec = codecOf(blob.type, blob.name); saveTrack(t); }
  const u = URL.createObjectURL(blob); urlCache.set(t.id, u);
  while (urlCache.size > 8) { const [k, old] = urlCache.entries().next().value; urlCache.delete(k); if (els.some(e => e.src === old)) { urlCache.set(k, old); break; } setTimeout(() => URL.revokeObjectURL(old), 2000); }
  return u;
}
function coverUrl(t, size = 'thumb') {
  if (!t) return null;
  if (t.net) return (size === 'full' ? (t.artBig || t.art) : t.art) || null;
  const blob = size === 'thumb' ? (t.thumb || t.cover) : (t.cover || t.thumb);
  if (!blob) return null;
  const k = t.id + ':' + size;
  if (!coverCache.has(k)) { try { coverCache.set(k, URL.createObjectURL(blob)); } catch { return null; } }
  return coverCache.get(k);
}
function dropCoverCache(t) { for (const s of ['thumb','full']) { const k = t.id + ':' + s; if (coverCache.has(k)) { URL.revokeObjectURL(coverCache.get(k)); coverCache.delete(k); } } }
const phHue = t => t ? (t.demo ? t.hue : hashHue(t.artist + t.title)) : 280;
const initial = t => t ? ((t.title || '♪').trim().charAt(0).toUpperCase() || '♪') : '♪';
function coverHTML(t, cls = '', id = '', extra = '') {
  const u = coverUrl(t), idAttr = id ? ` id="${id}"` : '';
  if (u) return `<span class="cover ${cls}"${idAttr}${extra}><img src="${u}" alt="" loading="lazy" decoding="async"></span>`;
  return `<span class="cover ${cls}"${idAttr}${extra}><span class="cover-ph" style="--h:${phHue(t)}">${esc(initial(t))}</span></span>`;
}
function bigArtHTML(t) {
  const u = coverUrl(t, 'full');
  return u ? `<img src="${u}" alt="Portada de ${esc(t.title)}" decoding="async">` : `<div class="art-ph" style="--h:${phHue(t)}">${esc(initial(t))}</div>`;
}
async function makeThumb(blob, size = 180) {
  try {
    const bmp = await createImageBitmap(blob);
    const s = Math.min(1, size / Math.max(bmp.width, bmp.height));
    const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(bmp.width * s)); c.height = Math.max(1, Math.round(bmp.height * s));
    const x = c.getContext('2d'); x.drawImage(bmp, 0, 0, c.width, c.height); try { bmp.close(); } catch {}
    const tone = dominantTone(x, c.width, c.height);
    const thumb = await new Promise(r => c.toBlob(r, 'image/jpeg', 0.84));
    return { thumb, hue: tone.hue, sat: tone.sat };
  } catch { return { thumb: null, hue: null, sat: null }; }
}
function dominantTone(x, w, h) {
  try {
    const d = x.getImageData(0, 0, w, h).data; let sx = 0, sy = 0, wsum = 0, ssum = 0, n = 0;
    for (let i = 0; i < d.length; i += 16) {
      const r = d[i] / 255, g = d[i + 1] / 255, b = d[i + 2] / 255;
      const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, dd = mx - mn;
      if (dd < 0.08 || l < 0.12 || l > 0.92) { n++; continue; }
      const s = dd / (1 - Math.abs(2 * l - 1));
      let hh = mx === r ? ((g - b) / dd) % 6 : mx === g ? (b - r) / dd + 2 : (r - g) / dd + 4; hh *= 60; if (hh < 0) hh += 360;
      const wgt = s * s; sx += Math.cos(hh * Math.PI / 180) * wgt; sy += Math.sin(hh * Math.PI / 180) * wgt; wsum += wgt; ssum += s; n++;
    }
    if (wsum < 0.5) return { hue: null, sat: 0.3 };
    let hh = Math.atan2(sy, sx) * 180 / Math.PI; if (hh < 0) hh += 360;
    return { hue: Math.round(hh), sat: Math.max(0.45, Math.min(1, (ssum / Math.max(1, n)) * 2.2)) };
  } catch { return { hue: null, sat: null }; }
}
function codecOf(type, name) {
  const n = String(name || '').toLowerCase(), ty = String(type || '').toLowerCase();
  if (/flac/.test(ty) || n.endsWith('.flac')) return 'FLAC';
  if (/wav|wave/.test(ty) || n.endsWith('.wav')) return 'WAV';
  if (/mpeg|mp3/.test(ty) || n.endsWith('.mp3')) return 'MP3';
  if (n.endsWith('.aac') || /aac/.test(ty)) return 'AAC';
  if (/mp4|m4a/.test(ty) || /\.(m4a|mp4)$/.test(n)) return 'M4A';
  if (/opus/.test(ty) || n.endsWith('.opus')) return 'OPUS';
  if (/ogg/.test(ty) || /\.(ogg|oga)$/.test(n)) return 'OGG';
  if (/webm/.test(ty) || n.endsWith('.webm')) return 'WEBM';
  return (n.split('.').pop() || 'AUDIO').toUpperCase().slice(0, 5);
}
function quality(t) {
  if (!t || !t.size || !t.duration || !t.codec) return null;
  if (t.codec === 'WAV' || t.codec === 'FLAC') return { label: `${t.codec} · sin pérdida`, short: t.codec + ' · HI-FI', tier: 'lossless' };
  const kbps = Math.round(t.size * 8 / t.duration / 1000);
  const eff = ['M4A','AAC','OPUS','OGG','WEBM'].includes(t.codec);
  const hi = eff ? 192 : 256, lo = eff ? 112 : 160;
  if (!isFinite(kbps) || kbps <= 0) return null;
  const tier = t.mine ? 'alta' : kbps >= hi ? 'alta' : kbps >= lo ? 'media' : 'baja';
  return { label: `${t.codec} · ${kbps} kbps`, short: `${t.codec} ${kbps}k`, tier, kbps };
}
const plays = t => t.demo ? (state.demoStats[t.id]?.plays || 0) : (t.plays || 0);
const liked = t => t.demo ? !!state.demoStats[t.id]?.liked : !!t.liked;
function trackRecord(t) {
  const { id, title, artist, album, genre, duration, addedAt, plays: pl, lastPlayed, cover, thumb, coverHue, coverSat, size, codec, mine, srcKey, dev, uri, mime, folder, artChecked, edited, skips, listenSec, hours } = t;
  return { id, title, artist, album, genre, duration, addedAt, liked: !!t.liked, plays: pl, lastPlayed, cover, thumb, coverHue, coverSat, size, codec, mine: !!mine, srcKey, dev: !!dev, uri, mime, folder, artChecked: !!artChecked, edited: !!edited, skips: skips || 0, listenSec: listenSec || 0, hours: hours || null };
}
async function saveTrack(t) {
  if (t.net) return;
  if (t.demo) { state.demoStats[t.id] = { plays: t.plays || 0, liked: !!t.liked, lastPlayed: t.lastPlayed || 0, skips: t.skips || 0, listenSec: t.listenSec || 0, hours: t.hours || null }; LS.set('demoStats', state.demoStats); return; }
  await idbPut('tracks', trackRecord(t));
}

/* ---------- Music on the phone (native app): read in place, no copies ---------- */
const nativeLib = capPlugin('MusicLibrary');
const devLib = { state: nativeLib ? 'unknown' : 'none', scanning: false, lastScan: 0, hidden: new Set(LS.get('devHidden', [])) };
const cleanTag = (v, d) => { v = String(v || '').trim(); return !v || /^<?unknown>?$/i.test(v) ? d : v; };
async function devStatus() {
  if (!nativeLib) return 'none';
  try { devLib.state = (await nativeLib.status()).state || 'prompt'; } catch { devLib.state = 'prompt'; }
  return devLib.state;
}
async function connectDevice() {
  if (!nativeLib) return;
  try { const r = await nativeLib.request(); devLib.state = r.state; } catch { devLib.state = 'denied'; }
  if (devLib.state === 'granted') { await syncDevice(true); }
  else toast(devLib.state === 'denied' ? 'Sin permiso. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio.' : 'Necesito permiso para ver tu música.', 4800);
  renderDevice(); if (state.view === 'ajustes') renderPerms();
}
async function syncDevice(manual) {
  if (!nativeLib || devLib.scanning) return;
  if ((await devStatus()) !== 'granted') { renderDevice(); return; }
  devLib.scanning = true; renderDevice();
  try {
    const { tracks: found = [] } = await nativeLib.scan({ minDuration: 20000 });
    const seen = new Set(); let added = 0, removed = 0; const dirty = [];
    for (const f of found) {
      const id = 'dev:' + f.id; seen.add(id);
      if (devLib.hidden.has(id)) continue;
      const base = (f.name || '').replace(/\.[^.]+$/, '');
      const fields = { title: cleanTag(f.title, base || 'Sin título'), artist: cleanTag(f.artist, 'Artista desconocido'), album: cleanTag(f.album, '') };
      if (f.genre) fields.genre = cleanTag(f.genre, '');
      const t = trackById(id);
      if (t) {
        let changed = false;
        if (!t.edited) for (const [k, v] of Object.entries(fields)) if (t[k] !== v) { t[k] = v; changed = true; }
        if (t.uri !== f.uri || t.size !== f.size) { t.uri = f.uri; t.size = f.size; t.duration = f.duration || t.duration; changed = true; }
        if (changed) dirty.push(t);
      } else {
        const nt = { id, dev: true, uri: f.uri, mime: f.mime || '', ...fields, duration: f.duration || 0, size: f.size || 0, codec: codecOf(f.mime, f.name), folder: f.folder || '', addedAt: f.added || Date.now(), liked: false, plays: 0, lastPlayed: 0, cover: null, thumb: null, coverHue: null, coverSat: null, artChecked: false };
        state.tracks.push(nt); dirty.push(nt); added++;
      }
    }
    await idbPutMany('tracks', dirty.map(trackRecord));
    const gone = state.tracks.filter(t => t.dev && !seen.has(t.id));
    if (gone.length) {
      const g = new Set(gone.map(t => t.id)); removed = gone.length;
      state.tracks = state.tracks.filter(t => !g.has(t.id));
      for (const id of g) idbDel('tracks', id);
    }
    devLib.lastScan = Date.now(); LS.set('devConnected', true);
    renderAll();
    if (manual) toast(added ? `${added} ${added === 1 ? 'canción nueva' : 'canciones nuevas'} del móvil` : `Tu música está al día (${state.tracks.filter(t => t.dev).length} canciones)`);
    else if (added) toast(`${added} ${added === 1 ? 'canción nueva' : 'canciones nuevas'} en tu móvil`);
    if (removed && manual) toast(`${removed} ya no están en el móvil y las he quitado`);
    crawlArtwork();
  } catch { if (manual) toast('No he podido leer la música del móvil.'); }
  devLib.scanning = false; renderDevice();
}
/* Covers of phone songs: fetched in the background, current song first, then cached */
let artBusy = false;
async function fetchArt(t) {
  if (!t || !t.dev || t.artChecked) return;
  t.artChecked = true;
  try {
    const r = await nativeLib.artwork({ uri: t.uri, size: 512 });
    if (r && r.data) {
      const blob = await (await fetch(r.data)).blob();
      const tone = await makeThumb(blob);
      t.cover = blob; t.thumb = tone.thumb; t.coverHue = tone.hue; t.coverSat = tone.sat; dropCoverCache(t);
      if (current() === t) { updatePlayer(); applyTheme(); mediaSession(t); }
      renderLibrarySoon();
    }
  } catch { t.artChecked = false; return; }
  saveTrack(t);
}
async function crawlArtwork() {
  if (artBusy || !nativeLib) return; artBusy = true;
  try {
    for (;;) {
      const t = state.tracks.find(x => x.dev && !x.artChecked);
      if (!t) break;
      await fetchArt(t);
      await new Promise(r => idle(r));
    }
  } finally { artBusy = false; }
}
function renderDevice() {
  const box = $('#devBox'); if (!box) return;
  box.hidden = !nativeLib;
  if (!nativeLib) return;
  const n = state.tracks.filter(t => t.dev).length;
  const st = devLib.state;
  box.innerHTML = st === 'granted'
    ? `<div class="d-text"><b>Música del móvil</b><span>${devLib.scanning ? 'Buscando canciones…' : `${n} ${n === 1 ? 'canción' : 'canciones'} conectadas. Se reproducen directamente desde tu almacenamiento, sin copiarlas. Las nuevas aparecen solas.`}</span></div><button class="btn small" data-act="dev-sync" ${devLib.scanning ? 'disabled' : ''}>${devLib.scanning ? 'Buscando…' : 'Actualizar'}</button>`
    : `<div class="d-text"><b>Conecta la música de tu móvil</b><span>${st === 'denied' ? 'Has denegado el permiso. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio.' : 'Órbita puede reproducir todas las canciones que ya tienes en el móvil, sin copiarlas ni ocupar espacio extra.'}</span></div><button class="btn small primary" data-act="dev-connect">Dar acceso a mi música</button>`;
  const welcome = $('#devWelcome');
  if (welcome) welcome.hidden = st === 'granted' || LS.get('devWelcomeDone', false);
}

/* ---------- Demo tracks ---------- */
const DEMOS = [
  {id:'demo-1', title:'Luz de las siete', mood:'calma', hue:32, bpm:84, cut:2600, arp:4, kick:false, hat:false, wave:'triangle', prog:[[60,64,67,71],[57,60,64,67],[65,69,72,76],[67,71,74,77]]},
  {id:'demo-2', title:'Andén 3', mood:'energía', hue:6, bpm:112, cut:3600, arp:8, kick:true, hat:true, wave:'square', prog:[[57,60,64],[53,57,60],[60,64,67],[55,59,62]]},
  {id:'demo-3', title:'Lámpara de escritorio', mood:'concentración', hue:200, bpm:72, cut:1600, arp:2, kick:false, hat:false, wave:'sine', prog:[[62,65,69,72],[58,62,65,69],[60,64,67,70],[57,61,64,67]]},
  {id:'demo-4', title:'Viernes 23:59', mood:'fiesta', hue:290, bpm:124, cut:5200, arp:16, kick:true, hat:true, wave:'sawtooth', prog:[[64,67,71],[60,64,67],[62,66,69],[59,62,66]]}
];
const DEMO_DUR = 30;
async function renderDemo(spec) {
  const sr = 44100, dur = DEMO_DUR, ctx = new OfflineAudioContext(1, sr * dur, sr);
  const master = ctx.createGain(); master.gain.setValueAtTime(0.0001, 0); master.gain.linearRampToValueAtTime(0.5, 1.2);
  master.gain.setValueAtTime(0.5, dur - 2.5); master.gain.linearRampToValueAtTime(0.0001, dur);
  const comp = ctx.createDynamicsCompressor(); comp.threshold.value = -14; comp.ratio.value = 3;
  const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = spec.cut; lp.Q.value = 0.7;
  lp.connect(master); master.connect(comp); comp.connect(ctx.destination);
  const mtof = n => 440 * Math.pow(2, (n - 69) / 12);
  const note = (m, t, len, type, vol) => {
    const o = ctx.createOscillator(); o.type = type; o.frequency.value = mtof(m);
    const g = ctx.createGain(); g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(vol, t + Math.min(0.03, len / 4)); g.gain.exponentialRampToValueAtTime(0.0001, t + len);
    o.connect(g); g.connect(lp); o.start(t); o.stop(t + len + 0.05);
  };
  const noise = ctx.createBuffer(1, Math.floor(sr * 0.06), sr); const nd = noise.getChannelData(0); for (let i = 0; i < nd.length; i++) nd[i] = Math.random() * 2 - 1;
  const kick = t => { const o = ctx.createOscillator(); o.type = 'sine'; o.frequency.setValueAtTime(130, t); o.frequency.exponentialRampToValueAtTime(42, t + 0.14);
    const g = ctx.createGain(); g.gain.setValueAtTime(0.6, t); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.28); o.connect(g); g.connect(master); o.start(t); o.stop(t + 0.3); };
  const hat = t => { const s = ctx.createBufferSource(); s.buffer = noise; const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 7000;
    const g = ctx.createGain(); g.gain.setValueAtTime(0.07, t); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.05); s.connect(hp); hp.connect(g); g.connect(master); s.start(t); };
  const beat = 60 / spec.bpm, bar = beat * 4, bars = Math.ceil(dur / bar);
  for (let b = 0; b < bars; b++) {
    const ch = spec.prog[b % spec.prog.length], t0 = b * bar;
    ch.forEach(n => note(n, t0, bar * 1.02, 'triangle', 0.05));
    note(ch[0] - 12, t0, bar * 0.95, 'sine', 0.2);
    for (let s = 0; s < spec.arp; s++) { const n = ch[(s * (spec.arp > 4 ? 3 : 1)) % ch.length] + 12; note(n, t0 + s * bar / spec.arp, (bar / spec.arp) * 0.85, spec.wave, spec.wave === 'sine' ? 0.06 : 0.022); }
    if (spec.kick && b > 0) for (let k = 0; k < 4; k++) kick(t0 + k * beat);
    if (spec.hat && b > 1) for (let h = 0; h < 8; h++) if (h % 2) hat(t0 + h * beat / 2);
  }
  return ctx.startRendering();
}
function toWav(buf) {
  const d = buf.getChannelData(0), n = d.length, ab = new ArrayBuffer(44 + n * 2), v = new DataView(ab);
  const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, buf.sampleRate, true); v.setUint32(28, buf.sampleRate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, n * 2, true);
  for (let i = 0, o = 44; i < n; i++, o += 2) { const s = Math.max(-1, Math.min(1, d[i])); v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true); }
  return new Blob([ab], { type: 'audio/wav' });
}
function addDemoEntries() {
  for (const s of DEMOS) {
    const st = state.demoStats[s.id] || {};
    state.tracks.push({ id: s.id, title: s.title, artist: 'Órbita · demo', album: 'Pistas de prueba', duration: DEMO_DUR, addedAt: 0, demo: true, mood: s.mood, hue: s.hue, coverHue: s.hue, liked: !!st.liked, plays: st.plays || 0, lastPlayed: st.lastPlayed || 0, skips: st.skips || 0, listenSec: st.listenSec || 0, hours: st.hours || null, cover: null, size: 44 + DEMO_DUR * 44100 * 2, codec: 'WAV' });
  }
}
/* Demo audio is synthesized on demand (first play) or in idle time, so startup stays fast */
const demoJobs = new Map();
function renderDemoTrack(id) {
  if (!demoJobs.has(id)) demoJobs.set(id, (async () => {
    const s = DEMOS.find(d => d.id === id); if (!s || typeof OfflineAudioContext === 'undefined') return;
    try { memAudio.set(s.id, toWav(await renderDemo(s))); } catch {}
  })());
  return demoJobs.get(id);
}
async function buildDemoAudio() { for (const s of DEMOS) { if (state.hideDemos) return; await renderDemoTrack(s.id); await new Promise(r => idle(r)); } }

/* ---------- Import: many files, whole folders, dropped folders ---------- */
const AUDIO_RE = /\.(mp3|m4a|aac|wav|ogg|oga|opus|flac|webm|caf|3gp|amr)$/i;
const IMG_RE = /\.(jpe?g|png|webp|gif|bmp)$/i;
const COVER_RE = /^(cover|folder|front|album|albumart[^.]*|portada|caratula|carátula)\.(jpe?g|png|webp)$/i;
const isAudio = f => (f.type && f.type.startsWith('audio/')) || AUDIO_RE.test(f.name) || f.type === 'video/webm';
const isImage = f => (f.type && f.type.startsWith('image/')) || IMG_RE.test(f.name);
const srcKeyOf = f => `${f.name}|${f.size}`;
/* Tag reader loads only when you import, so the app opens faster */
const TAGLIB_URL = window.ORBITA_TAGLIB || 'https://cdnjs.cloudflare.com/ajax/libs/jsmediatags/3.9.5/jsmediatags.min.js';
let tagLibP = null;
function loadTagLib() {
  if (window.jsmediatags) return Promise.resolve();
  if (!tagLibP) tagLibP = new Promise(res => { const s = document.createElement('script'); s.src = TAGLIB_URL; s.async = true; s.onload = () => res(); s.onerror = () => res(); document.head.appendChild(s); setTimeout(res, 8000); });
  return tagLibP;
}
function parseName(name) {
  const base = name.replace(/\.[^.]+$/, '').replace(/_/g, ' ').replace(/^\s*\d{1,3}[\s.\-_]+/, '').trim();
  const m = base.split(/\s+[-–—]\s+/);
  return m.length >= 2 ? { artist: m[0].trim(), title: m.slice(1).join(' - ').trim() } : { artist: '', title: base };
}
function readTags(file) {
  return new Promise(res => {
    const lib = window.jsmediatags; if (!lib) return res({});
    const to = setTimeout(() => res({}), 6000);
    try {
      lib.read(file, {
        onSuccess: tag => { clearTimeout(to); const g = tag.tags || {}; let cover = null;
          try { if (g.picture && g.picture.data) { let f = g.picture.format || 'image/jpeg'; if (!f.includes('/')) f = 'image/' + f.toLowerCase().replace('jpg', 'jpeg'); cover = new Blob([new Uint8Array(g.picture.data)], { type: f }); } } catch {}
          res({ title: g.title, artist: g.artist, album: g.album, cover }); },
        onError: () => { clearTimeout(to); res({}); }
      });
    } catch { clearTimeout(to); res({}); }
  });
}
function probeDuration(file) {
  return new Promise(res => {
    const a = document.createElement('audio'); const u = URL.createObjectURL(file);
    const done = d => { clearTimeout(to); a.removeAttribute('src'); URL.revokeObjectURL(u); res(d); };
    const to = setTimeout(() => done(0), 6000);
    a.preload = 'metadata'; a.onloadedmetadata = () => done(isFinite(a.duration) ? a.duration : 0); a.onerror = () => done(0); a.src = u;
  });
}
const dirOf = rel => { const i = rel.lastIndexOf('/'); return i < 0 ? '' : rel.slice(0, i + 1); };
function normEntries(list) { return [...list].map(x => x && x.file ? x : { file: x, rel: (x && x.webkitRelativePath) || (x && x.name) || '' }); }
async function filesFromDrop(dt) {
  const entries = [...(dt.items || [])].map(i => i.webkitGetAsEntry ? i.webkitGetAsEntry() : null).filter(Boolean);
  const plain = [...(dt.files || [])];
  if (!entries.length || !entries.some(e => e.isDirectory)) return plain.map(f => ({ file: f, rel: f.name }));
  const out = [];
  async function walk(entry, path) {
    if (entry.isFile) { const f = await new Promise(r => entry.file(r, () => r(null))); if (f) out.push({ file: f, rel: path + f.name }); }
    else if (entry.isDirectory) {
      const reader = entry.createReader(); let batch;
      do { batch = await new Promise(r => reader.readEntries(r, () => r([]))); for (const e of batch) await walk(e, path + entry.name + '/'); } while (batch.length);
    }
  }
  for (const e of entries) await walk(e, '');
  return out;
}
let job = null;
function showProgress() {
  const p = $('#importProg'); if (!job) { p.hidden = true; return; }
  p.hidden = false;
  $('#importTxt').textContent = job.cancel ? 'Parando…' : `Importando ${job.done} de ${job.total}${job.skipped ? ` · ${job.skipped} ya estaban` : ''}`;
  $('#importBar').style.width = (job.total ? job.done / job.total * 100 : 0) + '%';
}
$('#importStop').onclick = () => { if (job) { job.cancel = true; showProgress(); } };
let libRenderTimer = null;
const renderLibrarySoon = () => { clearTimeout(libRenderTimer); libRenderTimer = setTimeout(renderLibrary, 400); };
async function importFiles(input, opts = {}) {
  if (job) { toast('Espera a que termine la importación en curso.'); return; }
  const entries = normEntries(input);
  const audio = entries.filter(e => isAudio(e.file));
  if (!audio.length) { toast('No he encontrado archivos de audio en lo que has elegido.'); return; }
  const dirCover = new Map();
  for (const e of entries.filter(e => isImage(e.file))) {
    const d = dirOf(e.rel), prev = dirCover.get(d);
    if (!prev || (COVER_RE.test(e.file.name) && !COVER_RE.test(prev.name))) dirCover.set(d, e.file);
  }
  const have = new Set(state.tracks.map(t => t.srcKey).filter(Boolean));
  const seen = new Set();
  const todo = audio.filter(e => { const k = srcKeyOf(e.file); if (have.has(k) || seen.has(k)) return false; seen.add(k); return true; });
  const skipped = audio.length - todo.length;
  if (!todo.length) { toast(skipped === 1 ? 'Esa canción ya estaba en tu biblioteca' : 'Esas canciones ya estaban en tu biblioteca'); return; }
  try { navigator.storage?.persist?.(); } catch {}
  job = { total: todo.length, done: 0, skipped, cancel: false, added: 0 };
  showProgress();
  if (!opts.mine) await loadTagLib();
  let idx = 0;
  const worker = async () => {
    while (!job.cancel && idx < todo.length) {
      const e = todo[idx++];
      try { await importOne(e, dirCover.get(dirOf(e.rel)), opts); job.added++; } catch {}
      job.done++; showProgress(); renderLibrarySoon();
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  const { added, cancel } = job; job = null; showProgress();
  renderAll();
  const low = state.tracks.filter(t => quality(t)?.tier === 'baja').length;
  toast(`${added === 1 ? '1 canción añadida' : added + ' canciones añadidas'}${cancel ? ' (paraste la importación)' : ''}${skipped ? ` · ${skipped} repetidas omitidas` : ''}${low ? ' · revisa la calidad' : ''}`, 4200);
  if (opts.mine) setBubble(`¡Tienes ${added === 1 ? 'una canción nueva cantada' : added + ' canciones nuevas cantadas'} por ti! La pincharé cuando toque.`);
  updateStorage();
}
async function importOne(e, folderCover, opts) {
  const f = e.file;
  const [tags, duration] = await Promise.all([opts.mine ? Promise.resolve({}) : readTags(f), probeDuration(f)]);
  const p = parseName(f.name);
  const cover = tags.cover || folderCover || null;
  const tone = cover ? await makeThumb(cover) : { thumb: null, hue: null, sat: null };
  const t = {
    id: uid('t'), srcKey: srcKeyOf(f),
    title: (tags.title || (opts.mine ? cap(p.title) || 'Mi versión' : p.title) || 'Sin título').trim(),
    artist: opts.mine ? 'Yo' : (tags.artist || p.artist || 'Artista desconocido').trim(),
    album: opts.mine ? 'Cantadas por mí' : (tags.album || '').trim(),
    duration: duration || opts.duration || 0, addedAt: Date.now(), liked: false, plays: 0, lastPlayed: 0,
    cover, thumb: tone.thumb, coverHue: tone.hue, coverSat: tone.sat,
    size: f.size, codec: codecOf(f.type, f.name), mine: !!opts.mine
  };
  const ok = await idbPut('audio', f, t.id);
  if (!ok) memAudio.set(t.id, f);
  await saveTrack(t);
  state.tracks.push(t);
}

/* ---------- Audio engine 2.0 ----------
   · Elements 0 and 1 are the decks for your songs; element 2 plays online audio (radios, previews).
   · Direct sound: the element output goes to the speakers untouched.
   · Processed sound (equalizer, levelled volume, DJ mixes): deck → filters → gains → EQ → safety
     limiter → speakers. Mixes are scheduled on the audio clock, so they also work with the screen off. */
/* ---------- 3.5 · Android's own sound engine (beta) ----------
   With «Motor de sonido de Android» on (Ajustes › Sonido), your songs are played by Android's decoder and a sound
   engine of Órbita's own, outside the WebView: songs of the same album without a gap, less battery, and the phone's
   music read straight from storage (never copied into memory). The equalizer, levelled volume and the limiter run
   there too. DJ mixes with filters, echo and beat matching still need the WebView engine: with this one, songs
   change with a fade. If the engine fails, Órbita goes back to the usual one by itself. */
const NA_PLUGIN = capPlugin('NovaAudio');
const NA_ON = !!NA_PLUGIN && LS.get('engine', 'web') === 'nativo' && !LS.get('engineBroken', false);
const naPaths = new Map();   // blob: URL → file prepared for the engine
/* What the engine can open: phone songs by their address; anything else, through a temporary file */
async function naPath(v) {
  if (/^(content|file):/.test(v) || v.startsWith('/')) return v;
  if (naPaths.has(v)) return naPaths.get(v);
  const blob = await (await fetch(v)).blob();
  const name = 'c' + nvHash(v) + '.audio';
  for (let o = 0, first = true; ; o += NV_CHUNK, first = false) {
    const end = Math.min(blob.size, o + NV_CHUNK), done = end >= blob.size;
    const r = await NA_PLUGIN.writeTemp({ name, data: await nvB64(blob.slice(o, end)), append: !first, done });
    if (done) { naPaths.set(v, r.path); while (naPaths.size > 6) naPaths.delete(naPaths.keys().next().value); return r.path; }
  }
}
/* One of the engine's two «decks», with the same buttons and signals as an audio player, so the whole app
   (queue, fades, notification, widget, sleep timer, Nova) works with it as it is */
class NativeDeck extends EventTarget {
  constructor(slot) {
    super();
    this.slot = slot; this._src = ''; this.paused = true; this.preload = 'auto'; this.playbackRate = 1; this.preservesPitch = true;
    this._gen = 0; this._t = 0; this._tAt = performance.now(); this._dur = NaN; this._vol = 1; this._sent = -1; this._volT = null;
    this._native = null; this._loaded = Promise.resolve(null); this._startAt = 0; this._ended = false; this._gapless = null; this._playingSent = false;
  }
  get src() { return this._src; }
  set src(v) {
    v = String(v || ''); this._src = v; const my = ++this._gen;
    this.paused = true; this._t = 0; this._tAt = performance.now(); this._dur = NaN; this._native = null; this._startAt = 0;
    this._ended = false; this._gapless = null; this._playingSent = false; this.error = null;
    if (!v) { NA_PLUGIN.stop({ slot: this.slot }).catch(() => {}); this._loaded = Promise.resolve(null); return; }
    this._loaded = (async () => {
      const path = await naPath(v);
      if (my !== this._gen) return null;
      this._native = path;
      await NA_PLUGIN.load({ slot: this.slot, src: path, at: this._startAt || 0, play: false });
      return my === this._gen ? path : null;
    })();
    this._loaded.catch(() => { if (my === this._gen) { this.error = { message: 'no se puede abrir' }; this._emit('error'); } });
  }
  getAttribute(n) { return n === 'src' ? (this._src || null) : null; }
  removeAttribute(n) { if (n === 'src') this.src = ''; }
  load() {}
  get currentTime() {
    const t = this._t + (!this.paused && this._playingSent ? (performance.now() - this._tAt) / 1000 : 0);
    return isFinite(this._dur) ? Math.min(t, this._dur) : t;
  }
  set currentTime(v) {
    v = Math.max(0, +v || 0); this._t = v; this._tAt = performance.now(); this._ended = false;
    if (this._native) NA_PLUGIN.seek({ slot: this.slot, pos: v }).catch(() => {}); else this._startAt = v;
  }
  get duration() { return this._dur; }
  get volume() { return this._vol; }
  set volume(v) {
    this._vol = Math.max(0, Math.min(1, +v || 0));
    /* fades send many small changes: at most one message every 25 ms, always ending with the last value */
    if (this._volT) return;
    const send = () => { this._volT = null; if (Math.abs(this._vol - this._sent) < 0.002) return; this._sent = this._vol; NA_PLUGIN.setVolume({ slot: this.slot, v: this._vol }).catch(() => {}); this._volT = setTimeout(() => { this._volT = null; if (Math.abs(this._vol - this._sent) >= 0.002) send(); }, 25); };
    send();
  }
  async play() {
    if (!this._src) throw new Error('sin canción');
    if (this._ended) this.currentTime = 0;
    const my = this._gen;
    if (this.paused) { this.paused = false; this._emit('play'); }
    const p = await this._loaded;
    if (my !== this._gen || this.paused) return;
    if (!p) { this.paused = true; throw new Error('no se puede abrir'); }
    await NA_PLUGIN.play({ slot: this.slot });
  }
  pause() {
    if (this.paused) return;
    this._t = this.currentTime; this._tAt = performance.now(); this.paused = true; this._playingSent = false;
    NA_PLUGIN.pause({ slot: this.slot }).catch(() => {});
    this._emit('pause');
  }
  _emit(type) { try { this.dispatchEvent(new Event(type)); } catch {} }
  /* what the engine tells about this deck */
  _on(e) {
    if (e.type !== 'gapless' && e.src && this._native && e.src !== this._native) return;   // an old song of this deck
    if (e.type === 'loaded') { if (e.dur > 0) { this._dur = e.dur; this._emit('loadedmetadata'); this._emit('durationchange'); } }
    else if (e.type === 'pos') {
      if (this.paused) return;
      this._t = e.pos; this._tAt = performance.now(); if (e.dur > 0 && !isFinite(this._dur)) { this._dur = e.dur; this._emit('loadedmetadata'); }
      if (!this._playingSent) { this._playingSent = true; this._emit('playing'); naOk(); }
      this._emit('timeupdate');
    } else if (e.type === 'seeked') { this._t = e.pos; this._tAt = performance.now(); this._emit('seeked'); }
    else if (e.type === 'ended') { this._t = isFinite(this._dur) ? this._dur : this._t; this.paused = true; this._ended = true; this._playingSent = false; this._emit('pause'); this._emit('ended'); }
    else if (e.type === 'gapless') {
      /* the next song of the album is already playing, glued to the last one: the app only has to catch up */
      this._native = e.src; this._gapless = e.src; this._src = e.src; this._t = e.pos || 0; this._tAt = performance.now(); this._dur = e.dur > 0 ? e.dur : NaN;
      this._emit('ended');
    } else if (e.type === 'error') { this.error = { message: e.msg || 'error' }; this.paused = true; this._playingSent = false; naFail(); this._emit('error'); }
  }
}
const decks = NA_ON ? [new NativeDeck(0), new NativeDeck(1)] : [new Audio(), new Audio()];
const netEl = new Audio(); netEl.preload = 'none';
/* ---------- 3.2 · YouTube inside Órbita, without a key ----------
   A video (shared from the YouTube app, pasted, or a song's music video) joins the queue like any song: Nova introduces
   it, the player's buttons control it and the next song follows when it ends. It plays in YouTube's official player,
   always visible, as YouTube asks: big in «Ahora suena» and small above the player everywhere else. When Órbita goes
   to the background or the screen turns off it pauses (YouTube doesn't allow playing in the background), and it goes
   on when you come back. The player is driven with the messages YouTube's own player understands: no script from
   YouTube runs inside Órbita. The key is only needed to search without leaving the app. */
const YT_ORIGINS = ['https://www.youtube.com', 'https://www.youtube-nocookie.com'];
const YT_LIST_RE = /[?&]list=([A-Za-z0-9_-]{10,64})/;
function ytListFrom(text) { const m = String(text || '').match(YT_LIST_RE); return m ? m[1] : null; }
const YT_ERR = {
  2: 'el enlace del vídeo no es válido', 5: 'este móvil no puede reproducir ese vídeo',
  100: 'ese vídeo ya no existe o es privado', 101: 'quien lo subió no deja verlo fuera de YouTube',
  150: 'quien lo subió no deja verlo fuera de YouTube', 153: 'YouTube no deja reproducirlo aquí'
};
/* The video player, with the same buttons and signals as Órbita's audio players, so the whole app (queue, player,
   notification, widget, sleep timer, Nova's voice) works with it as it is */
class YTDeck extends EventTarget {
  constructor() {
    super();
    this._src = ''; this.paused = true; this.preload = 'none'; this.playbackRate = 1;
    this._gen = 0; this._frame = null; this._reset();
    window.addEventListener('message', e => this._onMsg(e));
  }
  _reset() {
    this._ready = false; this._heard = false; this._noCtl = false; this._pend = []; this._state = -2; this._info = null;
    this._t = 0; this._tAt = performance.now(); this._dur = 0; this._sentVol = -1; this.errorCode = 0;
    this._list = null; this._idx = -1; this._vid = ''; this.videoTitle = ''; this.videoAuthor = '';
    clearInterval(this._listenIv); clearTimeout(this._watch); clearTimeout(this._endT); this._stopTick();
  }
  get src() { return this._src; }
  set src(v) {
    v = String(v || ''); this._src = v; const my = ++this._gen;
    this._reset(); this.paused = true; this._settle(true);
    if (this._frame) { this._frame.remove(); this._frame = null; }
    if (!v) { ytLayout(); return; }
    /* built a moment later: if play() comes right after, the video starts by itself */
    queueMicrotask(() => { if (my === this._gen) this._build(my); });
  }
  getAttribute(n) { return n === 'src' ? (this._src || null) : null; }
  removeAttribute(n) { if (n === 'src') this.src = ''; }
  load() {}
  get currentTime() {
    const t = this._t + (!this.paused && this._state === 1 ? (performance.now() - this._tAt) / 1000 : 0);
    return this._dur ? Math.min(t, this._dur) : t;
  }
  set currentTime(v) {
    v = Math.max(0, +v || 0); this._t = v; this._tAt = performance.now();
    this._cmd('seekTo', [v, true]); setTimeout(() => this._emit('seeked'), 0);
  }
  get duration() { return this._dur > 0 ? this._dur : NaN; }
  get volume() { return this._vol == null ? 1 : this._vol; }
  set volume(v) {
    this._vol = Math.max(0, Math.min(1, +v || 0));
    const p = Math.round(this._vol * 100); if (p === this._sentVol) return;
    this._sentVol = p; this._cmd('setVolume', [p]);
  }
  play() {
    if (!this._src) return Promise.reject(new Error('sin vídeo'));
    /* never in the background: it waits until you're back in Órbita */
    if (document.hidden) { this._auto = true; return Promise.resolve(); }
    if (this.paused) { this.paused = false; this._emit('play'); }
    this._cmd('playVideo');
    if (this._state === 1 || this._noCtl) return Promise.resolve();
    if (!this._wait) {
      let res, rej; const p = new Promise((a, b) => { res = a; rej = b; });
      this._wait = { p, res, rej, to: setTimeout(() => this._settle(true), 20000) };
    }
    return this._wait.p;
  }
  pause() {
    this._cmd('pauseVideo'); this._settle(true);
    if (!this.paused) { this._snap(); this._stopTick(); this.paused = true; this._emit('pause'); }
  }
  _settle(ok, err) { const w = this._wait; if (!w) return; this._wait = null; clearTimeout(w.to); if (ok) w.res(); else w.rej(err || new Error('youtube')); }
  _snap() { this._t = this.currentTime; this._tAt = performance.now(); }
  _emit(type) { try { this.dispatchEvent(new Event(type)); } catch {} }
  _startTick() { this._stopTick(); this._tick = setInterval(() => { if (!this.paused) this._emit('timeupdate'); }, 250); }
  _stopTick() { clearInterval(this._tick); this._tick = null; }
  _parse() {
    const m = this._src.match(/^yt:([A-Za-z0-9_-]{11})?(?:\?list=([A-Za-z0-9_-]+))?$/);
    return m ? { v: m[1] || '', list: m[2] || '' } : { v: '', list: '' };
  }
  _build(my, forceAuto) {
    const { v, list } = this._parse();
    if (!v && !list) { this._fail(2); return; }
    const box = $('#ytFrameBox'); if (!box) return;
    const q = new URLSearchParams({ enablejsapi: '1', playsinline: '1', rel: '0', iv_load_policy: '3', hl: 'es', autoplay: (!this.paused || forceAuto) ? '1' : '0' });
    if (/^https?:$/.test(location.protocol)) { q.set('origin', location.origin); q.set('widget_referrer', location.origin); }
    if (list) q.set('list', list);
    const f = document.createElement('iframe');
    f.src = `https://www.youtube.com/embed/${v || 'videoseries'}?${q}`;
    f.title = 'Vídeo de YouTube'; f.setAttribute('frameborder', '0'); f.allowFullscreen = true;
    f.setAttribute('allow', 'autoplay; encrypted-media; picture-in-picture; fullscreen');
    f.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
    f.addEventListener('load', () => { if (my === this._gen && this._frame === f) this._hello(my); });
    if (this._frame) this._frame.remove();
    box.appendChild(f); this._frame = f;
    ytLayout();
    /* if the player never answers (no connection, or YouTube changes how it talks), it's still a normal YouTube
       player: it starts by itself and you use its own buttons */
    clearTimeout(this._watch);
    this._watch = setTimeout(() => { if (my === this._gen && !this._heard && !forceAuto) this._noControl(my); }, 15000);
  }
  _noControl(my) {
    this._noCtl = true; this._settle(true);
    if (!this.paused) this._build(my, true);
    ytLayout();
  }
  _hello(my) {
    let n = 0; const say = () => this._post({ event: 'listening', id: 1, channel: 'widget' });
    clearInterval(this._listenIv);
    setTimeout(say, 100);
    this._listenIv = setInterval(() => { if (my !== this._gen || this._heard || ++n > 40) { clearInterval(this._listenIv); return; } say(); }, 300);
  }
  _post(o) { try { if (this._frame && this._frame.contentWindow) this._frame.contentWindow.postMessage(JSON.stringify(o), '*'); } catch {} }
  _cmd(func, args) {
    const m = { event: 'command', func, args: args || [], id: 1, channel: 'widget' };
    if (this._ready) { this._post(m); return; }
    if (!this._src) return;
    const grp = x => (x === 'playVideo' || x === 'pauseVideo') ? 'pp' : x;
    this._pend = this._pend.filter(x => grp(x.func) !== grp(func)); this._pend.push(m);
  }
  _onMsg(e) {
    if (!this._frame || e.source !== this._frame.contentWindow || !YT_ORIGINS.includes(e.origin)) return;
    let m = e.data; if (typeof m === 'string') { try { m = JSON.parse(m); } catch { return; } }
    if (!m || typeof m !== 'object') return;
    this._heard = true; clearTimeout(this._watch);
    if (!this._ready && (m.event === 'onReady' || m.event === 'initialDelivery' || m.event === 'infoDelivery')) this._onReady();
    if (m.event === 'onError') { this._fail(+m.info || 0); return; }
    if (m.event === 'onStateChange' && typeof m.info === 'number') this._onState(m.info);
    if (m.info && typeof m.info === 'object') this._onInfo(m.info);
  }
  _onReady() {
    this._ready = true; clearInterval(this._listenIv);
    for (const ev of ['onStateChange', 'onError']) this._post({ event: 'command', func: 'addEventListener', args: [ev], id: 1, channel: 'widget' });
    const pend = this._pend; this._pend = [];
    this._sentVol = -1; this.volume = this.volume;
    for (const m of pend) if (m.func !== 'setVolume') this._post(m);
    if (!this.paused) this._post({ event: 'command', func: 'playVideo', args: [], id: 1, channel: 'widget' });
  }
  _onInfo(i) {
    const ps = i.progressState && typeof i.progressState === 'object' ? i.progressState : null;
    const cur = ps && isFinite(ps.current) ? +ps.current : (typeof i.currentTime === 'number' ? i.currentTime : null);
    if (cur != null && isFinite(cur)) { this._t = cur; this._tAt = performance.now(); }
    const d = +(i.duration || (ps && ps.duration) || 0);
    if (d > 0 && Math.abs(d - this._dur) > 0.5) { const first = !this._dur; this._dur = d; this._emit(first ? 'loadedmetadata' : 'durationchange'); }
    const vd = i.videoData;
    if (vd && typeof vd === 'object') {
      if (vd.errorCode) { this._fail(+vd.errorCode || 0); return; }
      if (vd.video_id && (vd.video_id !== this._vid || (vd.title && vd.title !== this.videoTitle))) {
        this._vid = vd.video_id; this.videoTitle = String(vd.title || ''); this.videoAuthor = String(vd.author || '');
        if (this.videoTitle) this._emit('ytinfo');
      }
    }
    if (Array.isArray(i.playlist)) this._list = i.playlist;
    if (typeof i.playlistIndex === 'number') this._idx = i.playlistIndex;
    if (typeof i.playerState === 'number') this._onState(i.playerState);
  }
  _onState(s) {
    if (s === this._state) return;
    this._state = s; clearTimeout(this._endT);
    if (s === 1) {
      this._tAt = performance.now();
      if (document.hidden) { this._auto = true; this.pause(); return; }
      if (this.paused) { this.paused = false; this._emit('play'); }
      this._emit('playing'); this._settle(true); this._startTick();
    } else if (s === 2) {
      this._snap(); this._stopTick();
      if (!this.paused) { this.paused = true; this._emit('pause'); }
    } else if (s === 3) { this._snap(); }
    else if (s === 0) {
      this._stopTick();
      /* a YouTube list goes on to its next video by itself: only the last one ends it */
      if (this._list && this._idx >= 0 && this._idx < this._list.length - 1) {
        this._endT = setTimeout(() => {
          if (this._state !== 0) return;
          this._post({ event: 'command', func: 'nextVideo', args: [], id: 1, channel: 'widget' });
          this._endT = setTimeout(() => { if (this._state === 0) this._ended(); }, 5000);
        }, 4000);
        return;
      }
      this._ended();
    }
  }
  _ended() {
    if (this._dur) this._t = this._dur;
    if (!this.paused) { this.paused = true; this._emit('pause'); }
    this._emit('ended');
  }
  _fail(code) {
    this.errorCode = code || 0; this._stopTick();
    const err = new Error('youtube ' + code);
    if (!this.paused) { this.paused = true; }
    this._settle(false, err);
    this._emit('error');
  }
  get errorText() { return YT_ERR[this.errorCode] || 'YouTube no ha podido reproducirlo ahora mismo'; }
}
const ytEl = new YTDeck();
const els = [decks[0], decks[1], netEl, ytEl];
decks.forEach(d => { d.preload = 'auto'; try { d.preservesPitch = true; } catch {} });
const clipEl = new Audio(); clipEl.preload = 'auto';
let A = 0; const act = () => els[A];
let baseVol = LS.get('vol', 80) / 100, duckF = 1, sleepF = 1, alarmF = 1; const deckF = [1, 1, 1, 1];
const AUD = {
  direct: LS.get('aDirect', false),
  eq: LS.get('eq', 'plano'), eqCustom: eqMigrate(LS.get('eqCustom', null)), eqOn: LS.get('eqOn', true),
  level: LS.get('aLevel', true),
  mix: LS.get('mixStyle', LS.get('xfade', 3) === 0 ? 'corte' : 'auto'),
  mixLen: LS.get('mixLen', 8)
};
/* 3.3 · Equalizer with 10 bands (31 Hz – 16 kHz), like a hi-fi graphic equalizer */
const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
const EQ_LABELS = ['31', '62', '125', '250', '500', '1K', '2K', '4K', '8K', '16K'];
const EQ_PRESETS = {
  plano: { name: 'Normal', g: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
  graves: { name: 'Aumento de graves', g: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0] },
  voces: { name: 'Aumento de voz', g: [-2, -2, -1, 0, 2, 3, 3, 2, 1, 0] },
  agudos: { name: 'Aumento de agudos', g: [0, 0, 0, 0, 0, 1, 2, 2, 3, 5] },
  pop: { name: 'Pop', g: [-1, 1, 3, 3, 2, 0, -1, -1, 0, 1] },
  rock: { name: 'Rock', g: [4, 3, 2, 0, -1, -1, 1, 2, 3, 4] },
  reggaeton: { name: 'Reguetón', g: [6, 5, 3, 1, 0, -1, 0, 1, 2, 2] },
  hiphop: { name: 'Hip hop', g: [5, 4, 2, 1, -1, -1, 1, 0, 1, 2] },
  electronica: { name: 'Electrónica', g: [5, 4, 2, 0, -1, 0, 1, 2, 4, 4] },
  latina: { name: 'Latina', g: [3, 2, 1, 0, -1, 0, 1, 3, 3, 2] },
  acustico: { name: 'Acústico', g: [2, 2, 1, 1, 1, 1, 2, 2, 2, 1] },
  clasica: { name: 'Clásica', g: [3, 2, 1, 0, 0, 0, -1, 0, 2, 3] },
  jazz: { name: 'Jazz', g: [3, 2, 1, 2, -1, -1, 0, 1, 2, 3] },
  auriculares: { name: 'Auriculares', g: [3, 2, 1, 0, 0, 0, 1, 1, 0, -1] },
  altavoz: { name: 'Altavoz del móvil', g: [-6, -4, -1, 1, 1, 1, 2, 2, 1, 0] },
  noche: { name: 'Noche (suave)', g: [-1, -1, 0, 0, 0, 0, -1, -2, -3, -4] },
  custom: { name: 'Personalizado', g: null }
};
/* The four big buttons (like a phone's sound settings); the rest are styles */
const EQ_CARDS = ['custom', 'graves', 'voces', 'agudos'];
const EQ_ICON = {
  custom: '<path d="M4 12v2M8 9v8M12 5v14M16 8v9M20 11v3"/>',
  graves: '<rect x="7" y="3" width="10" height="13" rx="2"/><circle cx="12" cy="11" r="2.5"/><path d="M4 18c2.5 2.5 13.5 2.5 16 0"/>',
  voces: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
  agudos: '<rect x="8" y="5" width="8" height="14" rx="2"/><circle cx="12" cy="14" r="2"/><path d="M4 8a10 10 0 0 0 0 8M20 8a10 10 0 0 1 0 8"/>'
};
/* An equalizer saved before 3.3 (5 bands) is carried over to the 10 bands, following the same curve */
function eqMigrate(v) {
  if (Array.isArray(v) && v.length === 10) return v.map(x => Math.max(-12, Math.min(12, Math.round(+x || 0))));
  if (!Array.isArray(v) || v.length !== 5) return [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
  const of = [60, 250, 1000, 4000, 12000], nf = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000], L = Math.log2;
  return nf.map(f => {
    if (f <= of[0]) return +v[0] || 0;
    if (f >= of[4]) return +v[4] || 0;
    let i = 0; while (f > of[i + 1]) i++;
    const k = (L(f) - L(of[i])) / (L(of[i + 1]) - L(of[i]));
    return Math.round((+v[i] || 0) + ((+v[i + 1] || 0) - (+v[i] || 0)) * k);
  });
}
const eqMine = () => LS.get('eqMine', []);
const eqPreset = id => EQ_PRESETS[id] || (String(id).startsWith('mine-') ? (() => { const m = eqMine().find(x => 'mine-' + x.id === id); return m ? { name: m.name, g: eqMigrate(m.g) } : null; })() : null);
const eqGains = () => !AUD.eqOn ? EQ_PRESETS.plano.g : AUD.eq === 'custom' ? AUD.eqCustom : (eqPreset(AUD.eq) || EQ_PRESETS.plano).g;
const eqName = () => (eqPreset(AUD.eq) || EQ_PRESETS.plano).name;
const MIX_ORDER = ['auto', 'dj', 'filtro', 'suave', 'eco', 'corte'];
const MIX_STYLES = {
  auto: { name: 'Automática', desc: 'Nova elige en cada cambio: mezcla de DJ si el ritmo encaja, filtro si las dos tienen golpe y un fundido largo si no. Siempre 6 s o más.' },
  dj: { name: 'DJ', desc: 'Iguala el tempo, cuadra los golpes y pasa los graves de una canción a la otra, como en una cabina.' },
  suave: { name: 'Fundido', desc: 'Una canción baja mientras la otra sube, sin silencios.' },
  eco: { name: 'Eco', desc: 'La canción se despide con un eco al ritmo y entra la siguiente.' },
  filtro: { name: 'Filtro', desc: 'La canción se va apagando hacia los graves mientras la siguiente entra desde los agudos, cuadrado al compás.' },
  corte: { name: 'Corte seco', desc: 'Sin mezcla, pero quitando el silencio del final y del principio.' }
};
const eqActive = () => !AUD.direct && eqGains().some(v => v);
const wantsGraph = () => !NA_ON && !AUD.direct && (eqActive() || AUD.level || AUD.mix !== 'corte');
let actx = null, G = null, analyser = null, freq = null, tapSrc = null, ctxIdleT = null;
function audioCtx() {
  if (actx) return actx;
  const AC = window.AudioContext || window.webkitAudioContext; if (!AC) return null;
  try { actx = new AC({ latencyHint: 'playback' }); } catch { try { actx = new AC(); } catch { return null; } }
  actx.onstatechange = () => {
    if (actx.state === 'suspended' && els.some(e => !e.paused)) actx.resume().catch(() => {});
    if (actx.state === 'running' && !G && wantsGraph() && els.some(e => !e.paused)) ensureGraph();
  };
  return actx;
}
function wakeCtx() { clearTimeout(ctxIdleT); if (actx && actx.state !== 'running' && actx.state !== 'closed') actx.resume().catch(() => {}); }
function ctxIdle() {
  clearTimeout(ctxIdleT);
  ctxIdleT = setTimeout(() => { if (actx && actx.state === 'running' && els.every(e => e.paused)) actx.suspend().catch(() => {}); }, 30000);
}
/* In the browser an audio context only starts after a tap, so it is opened on the first one */
['pointerdown', 'keydown'].forEach(ev => document.addEventListener(ev, () => { if (!actx && wantsGraph()) audioCtx(); wakeCtx(); }, { capture: true, passive: true }));
const hold = (p, t) => { try { if (p.cancelAndHoldAtTime) p.cancelAndHoldAtTime(t); else { const v = p.value; p.cancelScheduledValues(t); p.setValueAtTime(v, t); } } catch {} };
function ensureGraph() {
  if (G || !wantsGraph()) return G;
  const c = audioCtx(); if (!c) return null;
  if (c.state !== 'running') { wakeCtx(); if (!NATIVE) return null; }
  try {
    const nyq = c.sampleRate / 2;
    const pre = c.createGain(), post = c.createGain(), master = c.createGain();
    const eq = EQ_BANDS.map((f, i) => { const b = c.createBiquadFilter(); b.type = i === 0 ? 'lowshelf' : i === EQ_BANDS.length - 1 ? 'highshelf' : 'peaking'; b.frequency.value = f; b.Q.value = 1.41; b.gain.value = 0; return b; });
    const lim = c.createDynamicsCompressor(); lim.threshold.value = -1; lim.knee.value = 1.5; lim.ratio.value = 20; lim.attack.value = 0.003; lim.release.value = 0.22;
    const an = c.createAnalyser(); an.fftSize = 512; an.smoothingTimeConstant = 0.8;
    let n = pre; for (const b of eq) { n.connect(b); n = b; }
    const duck = c.createGain(); duck.gain.value = Math.max(0.0001, duckF);
    lim.connect(post); post.connect(duck); duck.connect(master); master.connect(c.destination); post.connect(an);
    const eIn = c.createGain(), eDel = c.createDelay(2), eFb = c.createGain(), eLp = c.createBiquadFilter();
    eLp.type = 'lowpass'; eLp.frequency.value = 2600; eFb.gain.value = 0.5; eDel.delayTime.value = 0.375;
    eIn.connect(eDel); eDel.connect(eLp); eLp.connect(eFb); eFb.connect(eDel); eLp.connect(pre);
    const dk = decks.map(el => {
      const src = c.createMediaElementSource(el);
      const hp = c.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 0; hp.Q.value = 0.71;
      const lp = c.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = nyq; lp.Q.value = 0.71;
      const nrm = c.createGain(), fade = c.createGain(), xf = c.createGain(), send = c.createGain(); send.gain.value = 0;
      src.connect(hp); hp.connect(lp); lp.connect(nrm); nrm.connect(fade); fade.connect(xf); xf.connect(pre); nrm.connect(send); send.connect(eIn);
      return { hp, lp, nrm, fade, xf, send };
    });
    G = { pre, post, duck, master, eq, last: n, lim, limOn: null, an, nyq, echo: { eIn, eDel, eFb }, dk };
    if (tapSrc) { try { tapSrc.disconnect(); } catch {} tapSrc = null; }
    analyser = an; freq = new Uint8Array(an.frequencyBinCount);
    routeLimiter(); applyEq(); applyVol();
    const t = current(); if (t && A < 2) applyNorm(A, t, 0);
  } catch { G = null; }
  return G;
}
function routeLimiter() {
  if (!G) return; const want = !AUD.direct;
  if (G.limOn === want) return;
  try { G.last.disconnect(); } catch {}
  G.last.connect(want ? G.lim : G.post); G.limOn = want;
}
function applyVol() {
  const v = baseVol * duckF * sleepF * alarmF;
  if (G) {
    const now = actx.currentTime;
    G.master.gain.setTargetAtTime(clamp01(baseVol * sleepF * alarmF), now, 0.012);
    for (const i of [0, 1]) { decks[i].volume = 1; G.dk[i].fade.gain.setTargetAtTime(clamp01(deckF[i]), now, 0.01); }
  } else for (const i of [0, 1]) decks[i].volume = clamp01(v * deckF[i]);
  netEl.volume = clamp01(v * deckF[2]);
  ytEl.volume = clamp01(v * deckF[3]);
  clipEl.volume = clamp01(baseVol * 1.25);
}
applyVol();
function applyEq() {
  if (NA_ON) { NA_PLUGIN.setEq({ gains: eqGains(), on: !AUD.direct && eqActive() }).catch(() => {}); NA_PLUGIN.setDirect({ on: AUD.direct }).catch(() => {}); return; }
  if (!G) return; const g = eqGains(), now = actx.currentTime, on = !AUD.direct;
  G.eq.forEach((b, i) => b.gain.setTargetAtTime(on ? g[i] : 0, now, 0.05));
  const boost = on ? eqPeakDb(g) : 0;
  G.pre.gain.setTargetAtTime(Math.pow(10, -boost * 0.85 / 20), now, 0.05);
}
const TARGET_DB = -15;
function normGainFor(t) {
  const a = t && anaCache.get(t.id);
  if (!AUD.level || AUD.direct || !a || !isFinite(a.rms)) return 1;
  return Math.pow(10, Math.max(-9, Math.min(4, TARGET_DB - a.rms)) / 20);
}
function applyNorm(i, t, ramp = 0.4) { if (NA_ON && i < 2) { NA_PLUGIN.setNorm({ slot: i, g: normGainFor(t) }).catch(() => {}); return; } if (!G || i > 1) return; G.dk[i].nrm.gain.setTargetAtTime(normGainFor(t), actx.currentTime, Math.max(0.005, ramp / 3)); }
function resetDeckFx(i) {
  if (i > 1) return;
  try { decks[i].playbackRate = 1; } catch {}
  if (!G) return;
  const d = G.dk[i], now = actx.currentTime;
  for (const p of [d.hp.frequency, d.lp.frequency, d.xf.gain, d.send.gain]) p.cancelScheduledValues(now);
  d.hp.frequency.setValueAtTime(0, now); d.lp.frequency.setValueAtTime(G.nyq, now); d.xf.gain.setValueAtTime(1, now); d.send.gain.setValueAtTime(0, now);
}
const tweens = {};
/* Without the audio graph: an equal-power crossfade on the element volumes, stepped by a timer (it keeps going with the screen off) */
let xfNG = null;
function xfadeNoGraph(from, to, ms) {
  return new Promise(res => {
    if (xfNG) { clearInterval(xfNG.iv); xfNG.res(false); xfNG = null; }
    for (const k of ['f' + from, 'f' + to]) { const pr = tweens[k]; if (pr) { clearInterval(pr.iv); clearTimeout(pr.to); tweens[k] = null; pr.res(false); } }
    const a0 = deckF[from], b0 = deckF[to], t0 = performance.now();
    const rec = { res };
    rec.iv = setInterval(() => {
      const k = Math.min(1, (performance.now() - t0) / Math.max(1, ms));
      deckF[from] = a0 * Math.cos(k * Math.PI / 2); deckF[to] = b0 + (1 - b0) * Math.sin(k * Math.PI / 2); applyVol();
      if (k >= 1) { clearInterval(rec.iv); if (xfNG === rec) xfNG = null; res(true); }
    }, 30);
    xfNG = rec;
  });
}
function tween(key, to, ms) {
  return new Promise(res => {
    const prev = tweens[key]; if (prev) { clearInterval(prev.iv); clearTimeout(prev.to); tweens[key] = null; prev.res(false); }
    if (key !== 'duck' && xfNG) { clearInterval(xfNG.iv); xfNG.res(false); xfNG = null; }
    /* With the audio graph the duck is scheduled on the audio thread: smooth even with the screen off */
    if (key === 'duck' && G) {
      duckF = to; applyVol();
      const g = G.duck.gain, now = actx.currentTime; hold(g, now);
      g.setTargetAtTime(Math.max(0.0001, to), now, Math.max(0.01, ms / 3000));
      const rec = { res }; rec.to = setTimeout(() => { if (tweens[key] === rec) tweens[key] = null; res(true); }, Math.max(0, ms)); tweens[key] = rec; return;
    }
    const get = () => key === 'duck' ? duckF : deckF[+key[1]];
    const set = v => { if (key === 'duck') duckF = v; else deckF[+key[1]] = v; applyVol(); };
    if (ms <= 0 || (document.hidden && ms < 400)) { set(to); return res(true); }
    const from = get(), t0 = performance.now();
    const rec = { res };
    rec.iv = setInterval(() => {
      const k = Math.min(1, (performance.now() - t0) / ms); const e = k * k * (3 - 2 * k);
      set(from + (to - from) * e);
      if (k >= 1) { clearInterval(rec.iv); if (tweens[key] === rec) tweens[key] = null; res(true); }
    }, 16);
    tweens[key] = rec;
  });
}
function tapAnalyser(d) {
  if (G || d === netEl || d === ytEl) return;
  try {
    if (typeof d.captureStream !== 'function') return;
    const c = audioCtx(); if (!c) return;
    if (!analyser) { analyser = c.createAnalyser(); analyser.fftSize = 512; analyser.smoothingTimeConstant = 0.8; freq = new Uint8Array(analyser.frequencyBinCount); const mute = c.createGain(); mute.gain.value = 0; analyser.connect(mute); mute.connect(c.destination); }
    wakeCtx();
    const stream = d.captureStream(); if (!stream.getAudioTracks().length) return;
    if (tapSrc) { try { tapSrc.disconnect(); } catch {} }
    tapSrc = c.createMediaStreamSource(stream); tapSrc.connect(analyser);
  } catch {}
}
function soundLabel() {
  const parts = [];
  if (G && !AUD.direct) { if (eqActive()) parts.push('ecualizador «' + eqName() + '»'); if (AUD.level) parts.push('volumen igualado'); }
  return parts;
}

/* ---------- Song analysis: tempo, beat grid, silences, fade-out and loudness ---------- */
const ANA_V = 4;
const anaCache = new Map(), anaPending = new Set();
let anaQueue = Promise.resolve();
async function getAnalysis(t) {
  if (!t || t.net) return null;
  if (anaCache.has(t.id)) return anaCache.get(t.id);
  const r = await idbGet('analysis', t.id);
  if (r && r.v === ANA_V && (!t.size || !r.size || r.size === t.size)) { anaCache.set(t.id, r); return r; }
  return null;
}
function scheduleAnalysis(t) {
  if (!t || t.net || anaPending.has(t.id) || anaCache.has(t.id) || (t.duration && t.duration > 900)) return;
  anaPending.add(t.id);
  anaQueue = anaQueue.then(async () => {
    try { if (!(await getAnalysis(t))) await analyzeTrack(t); } catch {}
    anaPending.delete(t.id);
  });
}
async function analyzeTrack(t) {
  const blob = await trackBlob(t); if (!blob || blob.size > 80e6) return null;
  const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext; if (!OAC) return null;
  await new Promise(r => idle(r));
  let buf;
  try { buf = await new OAC(1, 1, 11025).decodeAudioData(await blob.arrayBuffer()); } catch { return null; }
  await sleep(0);
  const res = await analyzeOff(buf); buf = null;
  Object.assign(res, { v: ANA_V, id: t.id, size: t.size || blob.size });
  anaCache.set(t.id, res); idbPut('analysis', res);
  if (current() === t && A < 2) applyNorm(A, t, 3);
  return res;
}
/* The analysis runs in a worker (the same function, sent as code), so the app never stutters while it learns a song */
let anaWorker = null, anaN = 0; const anaWait = new Map();
function analyzeOff(buf) {
  try {
    if (!anaWorker) {
      const src = analyzeBuffer.toString() + '\nonmessage = e => { const m = e.data; let res = null, error = null; try { res = analyzeBuffer({ sampleRate: m.sr, length: m.n, numberOfChannels: m.ch.length, getChannelData: i => m.ch[i] }); } catch (err) { error = String(err); } postMessage({ id: m.id, res, error }); };';
      anaWorker = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })));
      anaWorker.onmessage = e => { const r = anaWait.get(e.data.id); if (r) { anaWait.delete(e.data.id); r(e.data); } };
      anaWorker.onerror = () => { for (const r of anaWait.values()) r({ error: 'worker' }); anaWait.clear(); try { anaWorker.terminate(); } catch {} anaWorker = null; };
    }
    const ch = []; for (let i = 0; i < Math.min(2, buf.numberOfChannels); i++) ch.push(buf.getChannelData(i).slice());
    return new Promise(res => { const id = ++anaN; anaWait.set(id, d => res(d.res || analyzeBuffer(buf))); anaWorker.postMessage({ id, sr: buf.sampleRate, n: buf.length, ch }, ch.map(c => c.buffer)); });
  } catch { return Promise.resolve(analyzeBuffer(buf)); }
}
function analyzeBuffer(buf) {
  const sr = buf.sampleRate, n = buf.length, nc = buf.numberOfChannels;
  const c0 = buf.getChannelData(0), c1 = nc > 1 ? buf.getChannelData(1) : null;
  const hop = 128, nf = Math.max(1, Math.floor(n / hop)), fps = sr / hop, dur = n / sr;
  /* Per 11.6 ms: total energy, bass energy (one-pole low-pass ~150 Hz) and the energy of the signal's slope (treble) */
  const ms = new Float32Array(nf), lo = new Float32Array(nf);
  let lp = 0, prevV = 0, hfAll = 0, allE = 0;
  for (let f = 0, i = 0; f < nf; f++) {
    let s = 0, sl = 0;
    for (let k = 0; k < hop; k++, i++) { const v = c1 ? (c0[i] + c1[i]) * 0.5 : c0[i]; s += v * v; lp += 0.082 * (v - lp); sl += lp * lp; const d = v - prevV; hfAll += d * d; prevV = v; }
    ms[f] = s / hop; lo[f] = sl / hop; allE += s;
  }
  const r1 = x => Math.round(x * 1000) / 1000;
  let peak = 0; for (let f = 0; f < nf; f++) if (ms[f] > peak) peak = ms[f];
  if (peak < 1e-8) return { dur: r1(dur), introStart: 0, introLoud: 0, fadeStart: r1(dur), outroEnd: r1(dur), rms: -60, bpm: 0, conf: 0, gridIn: 0, gridOut: 0 };
  /* Silence = more than ~42 dB under the loud part of the song */
  const sorted = Float32Array.from(ms).sort();
  const p95 = sorted[Math.floor(sorted.length * 0.95)] || peak;
  const thr = Math.max(1.6e-5, p95 * 6.3e-5);
  let fi = 0; while (fi < nf - 1 && ms[fi] < thr) fi++;
  let fo = nf - 1; while (fo > fi && ms[fo] < thr) fo--;
  const introStart = Math.max(0, fi / fps - 0.02), outroEnd = Math.min(dur, (fo + 1) / fps + 0.05);
  let sum = 0, cnt = 0; for (let f = fi; f <= fo; f++) if (ms[f] > thr * 100) { sum += ms[f]; cnt++; }
  const rmsDb = cnt ? 10 * Math.log10(sum / cnt + 1e-12) : -60;
  /* 1-second energy envelope → where the song gets loud and where the fade-out begins */
  const W = Math.max(1, Math.round(fps));
  const sm = new Float32Array(nf); { let acc = 0; for (let f = 0; f < nf; f++) { acc += ms[f]; if (f >= W) acc -= ms[f - W]; sm[f] = acc / Math.min(f + 1, W); } }
  const b0 = Math.floor(fi + (fo - fi) * 0.2), b1 = Math.max(b0 + 1, Math.floor(fi + (fo - fi) * 0.8));
  const body = Array.from(sm.subarray(b0, b1)).sort((a, b) => a - b); const med = body[body.length >> 1] || 0;
  let ff = fo; while (ff > fi && sm[ff] < med * 0.3) ff--;
  let fadeStart = Math.min(outroEnd, Math.max(introStart, (ff - W / 2) / fps));
  if (outroEnd - fadeStart < 1.5 || outroEnd - fadeStart > 30) fadeStart = outroEnd;
  let fl = fi; while (fl < fo && sm[fl] < med * 0.3) fl++;
  const introLoud = Math.max(introStart, (fl - W / 2) / fps);
  /* Onset strength: rises of log-energy, minus a local mean */
  const on = new Float32Array(nf);
  { let prev = Math.log(ms[0] + 1e-9); for (let f = 1; f < nf; f++) { const l = Math.log(ms[f] + 1e-9); on[f] = Math.max(0, l - prev); prev = l; } }
  { const M = Math.max(2, Math.round(fps * 0.35)), tmp = Float32Array.from(on); let acc = 0;
    for (let f = 0; f < nf; f++) { acc += tmp[f]; if (f >= M) acc -= tmp[f - M]; on[f] = Math.max(0, tmp[f] - acc / Math.min(f + 1, M)); } }
  /* Tempo: autocorrelation of the onsets between 70 and 170 BPM, leaning towards ~110 */
  let bpm = 0, conf = 0, period = 0;
  const s0 = Math.min(fo, fi + Math.floor(4 * fps)), s1 = Math.min(fo, s0 + Math.floor(150 * fps)), N = s1 - s0;
  if (N > fps * 10) {
    const lmin = Math.floor(fps * 60 / 170), lmax = Math.ceil(fps * 60 / 70);
    const sc = new Float32Array(2 * lmax + 3);
    for (let L = lmin - 1; L <= 2 * lmax + 2; L++) { let s = 0; for (let i = s0; i < s1 - L; i++) s += on[i] * on[i + L]; sc[L] = s / Math.max(1, N - L); }
    let mean = 0, sd = 0; const cntL = lmax - lmin + 1;
    for (let L = lmin; L <= lmax; L++) mean += sc[L]; mean /= cntL;
    for (let L = lmin; L <= lmax; L++) sd += (sc[L] - mean) ** 2; sd = Math.sqrt(sd / cntL) || 1e-12;
    let best = -Infinity, bL = lmin;
    for (let L = lmin; L <= lmax; L++) {
      const b = 60 * fps / L, w = Math.exp(-0.5 * (Math.log2(b / 110) / 0.6) ** 2);
      const v = (sc[L] + 0.5 * sc[2 * L]) * (0.6 + 0.4 * w);
      if (v > best) { best = v; bL = L; }
    }
    const y0 = sc[bL - 1], y1 = sc[bL], y2 = sc[bL + 1], den = y0 - 2 * y1 + y2;
    period = bL + (den ? Math.max(-0.5, Math.min(0.5, 0.5 * (y0 - y2) / den)) : 0);
    conf = (sc[bL] - mean) / sd;
    /* Fine-tune the beat length with a comb over the whole analysed stretch: long windows pin it down to ~0.05 % */
    let bestS = -1, bp = period;
    for (let p = period * 0.985; p <= period * 1.015; p += 0.02) {
      for (let ph = 0; ph < p; ph += 1) {
        let sum = 0; for (let x = s0 + ph; x < s1; x += p) sum += on[Math.round(x)] || 0;
        if (sum > bestS) { bestS = sum; bp = p; }
      }
    }
    period = bp; bpm = 60 * fps / period;
  }
  /* Beat grid: the beat closest to `anchor`, phase-fitted on a short window around it */
  const beatNear = (fa, fb, anchor) => {
    if (!period) return anchor / fps;
    let bestS = -1, bestPh = 0;
    for (let ph = 0; ph < period; ph += 0.5) {
      let s = 0; for (let x = fa + ph; x < fb; x += period) { const k = Math.round(x); s += (on[k] || 0) + 0.5 * ((on[k - 1] || 0) + (on[k + 1] || 0)); }
      if (s > bestS) { bestS = s; bestPh = ph; }
    }
    const first = fa + bestPh, k = Math.round((anchor - first) / period);
    return Math.max(0, first + k * period) / fps;
  };
  const gridIn = beatNear(fi, Math.min(fo, fi + Math.floor(10 * fps)), fi);
  const outAnchor = Math.max(fi, fo - Math.floor(12 * fps));
  const gridOut = beatNear(Math.max(fi, outAnchor - Math.floor(6 * fps)), Math.min(fo, outAnchor + Math.floor(6 * fps)), outAnchor);
  /* Bars: the beat of each 4 that carries the most bass attack is the "one" */
  const lon = new Float32Array(nf);
  for (let f = 1; f < nf; f++) lon[f] = Math.max(0, Math.sqrt(lo[f]) - Math.sqrt(lo[f - 1]));
  const barPhase = (g, fromF, toF) => {
    if (!period) return { at: g, sure: 0 };
    const sc = [0, 0, 0, 0], beatF = period, g0 = g * fps;
    for (let k = 0; k < 4; k++) for (let x = g0 + k * beatF; x < toF; x += 4 * beatF) { if (x < fromF) continue; const j = Math.round(x); sc[k] += (lon[j] || 0) + 0.5 * ((lon[j - 1] || 0) + (lon[j + 1] || 0)); }
    const best = sc.indexOf(Math.max(...sc)), sorted = sc.slice().sort((a, b) => b - a);
    return { at: (g0 + best * beatF) / fps, sure: sorted[0] > 0 ? (sorted[0] - sorted[1]) / sorted[0] : 0 };
  };
  const dIn = barPhase(gridIn, fi, Math.min(fo, fi + Math.floor(40 * fps)));
  const dOutRaw = barPhase(gridOut, Math.max(fi, fo - Math.floor(40 * fps)), fo);
  /* Features for Nova's map of your music: energy, brightness and how busy the rhythm is */
  let peaks = 0; for (let f = s0 + 1; f < s1 - 1; f++) if (on[f] > 0.35 && on[f] >= on[f - 1] && on[f] > on[f + 1]) peaks++;
  const rate = N > 0 ? peaks / (N / fps) : 0;
  const c01 = x => Math.max(0, Math.min(1, x));
  const bright = c01((Math.log10(hfAll / (allE + 1e-12) + 1e-6) + 2) / 1.6);
  const energy = c01(0.45 * c01((rmsDb + 28) / 19) + 0.35 * c01((rate - 1) / 6) + 0.2 * c01((bpm - 70) / 80) * (conf >= 1.5 ? 1 : 0.5));
  const r2 = x => Math.round(x * 100) / 100;
  return { dur: r1(dur), introStart: r1(introStart), introLoud: r1(introLoud), fadeStart: r1(fadeStart), outroEnd: r1(outroEnd), rms: Math.round(rmsDb * 10) / 10, bpm: Math.round(bpm * 10) / 10, conf: Math.round(conf * 100) / 100, gridIn: r1(gridIn), gridOut: r1(gridOut), downIn: r1(dIn.at), downOut: r1(dOutRaw.at), barSure: r2(Math.min(dIn.sure, dOutRaw.sure)), energy: r2(energy), bright: r2(bright), rate: r2(rate) };
}

/* ---------- Playback ---------- */
const MANUAL_XF = 0.8;
let gen = 0, advancing = false, mixing = null, mixArm = null; const xfTimers = [0, 0];
const current = () => state.queue[state.qi] ? trackById(state.queue[state.qi]) : null;
function disarmMix() {
  const arm = mixArm; mixArm = null;
  if (arm && arm.to !== undefined && arm.to !== A) { try { decks[arm.to].pause(); } catch {} }
}
async function startOn(url, xf, isNet) {
  const my = ++gen;
  if (mixing) finishMix(true);
  disarmMix();
  const old = A, cur = els[old];
  if (!isNet && cur._gapless && cur._gapless === url) { cur._gapless = null; return true; }
  const busy = !cur.paused && !!cur.getAttribute('src');
  const nw = isNet === 'yt' ? 3 : isNet ? 2 : (old >= 2 ? 0 : 1 - old);
  if (!isNet) ensureGraph();
  wakeCtx();
  if (nw === old) {
    if (busy) { await tween('f' + old, 0, 150); if (my !== gen) return true; }
    cur.src = url; deckF[old] = 0; applyVol();
    try { await cur.play(); } catch { deckF[old] = 1; applyVol(); return false; }
    if (my !== gen) return true;
    tween('f' + old, 1, 220); return true;
  }
  const d = els[nw];
  if (nw < 2) clearTimeout(xfTimers[nw]);
  d.pause(); resetDeckFx(nw);
  /* Between the two song decks the crossfade runs on the audio thread: smooth even with the screen off or the app busy */
  const smooth = !!G && busy && nw < 2 && old < 2;
  if (smooth) { tween('f' + nw, 1, 0); G.dk[nw].xf.gain.setValueAtTime(0.0001, actx.currentTime); } else { deckF[nw] = 0; applyVol(); }
  d.src = url; A = nw;
  if (!isNet) applyNorm(nw, current(), 0);
  try { await d.play(); } catch { if (smooth) resetDeckFx(nw); deckF[nw] = 1; applyVol(); return false; }
  if (my !== gen) return true;
  if (smooth) {
    const L = Math.max(0.15, xf || 0), t0 = actx.currentTime, now = t0 + 0.02, fa = G.dk[old], fb = G.dk[nw];
    tween('f' + old, deckF[old], 0);
    hold(fa.xf.gain, t0); hold(fb.xf.gain, t0);
    const va = Math.max(0.0001, Math.min(1, fa.xf.gain.value)), vb = Math.max(0.0001, Math.min(1, fb.xf.gain.value));
    curveAt(fa.xf.gain, mkCurve(C_DOWN, 0, va), now, L);
    curveAt(fb.xf.gain, mkCurve(C_UP, vb, 1), now, L);
    xfTimers[old] = setTimeout(() => { if (A !== old && !(mixArm && mixArm.to === old)) { cur.pause(); resetDeckFx(old); deckF[old] = 1; applyVol(); } }, (L + 0.1) * 1000);
    return true;
  }
  const ms = busy ? (xf > 0 ? xf * 1000 : 140) : 0;
  if (busy) xfadeNoGraph(old, nw, ms).then(done => { if (done && A !== old) { cur.pause(); resetDeckFx(old); deckF[old] = 1; applyVol(); } });
  else { tween('f' + nw, 1, 200); if (!cur.paused) cur.pause(); }
  return true;
}
function playQueue(ids, start = 0, opts = {}) {
  ids = ids.filter(id => trackById(id)); if (!ids.length) return;
  let q = ids.slice(); let i = Math.max(0, Math.min(start, q.length - 1));
  if (state.shuffle && !['radio', 'online', 'daily'].includes(opts.source)) { const first = q[i]; q = [first, ...shuffleArr(q.filter((_, k) => k !== i))]; i = 0; }
  markManual();
  state.queue = q; state.qi = i; state.source = opts.source || 'library'; state.mixEndless = null;
  state.intros = opts.intros || {};
  state.pendingTalk = opts.talk ? (typeof opts.talk === 'object' ? opts.talk : { text: opts.talk, kind: opts.talkKind || 'saludo' }) : null;
  if (state.source !== 'radio' && state.radio) setRadio(false, true);
  loadCurrent(true, act().paused ? 0 : MANUAL_XF);
}
async function loadCurrent(autoplay, xf = 0, at = 0) {
  const t = current(); if (!t) return;
  const u = t.net ? t.url : await audioUrl(t);
  if (!u) {
    if (t.dev) { toast(`No encuentro «${t.title}» en el móvil. Paso a la siguiente.`); if (state.qi + 1 < state.queue.length) { state.qi++; loadCurrent(autoplay, 0); } syncDevice(false); return; }
    toast(t.demo ? 'Las pistas demo aún se están preparando, prueba en un segundo.' : 'No encuentro el archivo de esta canción.'); return;
  }
  if (t !== current()) return;
  advancing = false;
  stopTalk();
  if (!autoplay) {
    if (mixing) finishMix(true); disarmMix();
    const nw = t.kind === 'yt' ? 3 : t.net ? 2 : (A >= 2 ? 0 : A);
    if (nw !== A) els[A].pause();
    A = nw; const d = els[A]; d.src = u; if (!t.net) { ensureGraph(); applyNorm(A, t, 0); }
    if (at > 0) d.addEventListener('loadedmetadata', () => { if (d.getAttribute('src') !== u) return; try { d.currentTime = Math.min(at, Math.max(0, (d.duration || at + 1) - 1)); } catch {} updateSeek(); }, { once: true });
    deckF[A] = 1; applyVol();
    beginTrack(t, { silent: true }); return;
  }
  const talk = state.pendingTalk; state.pendingTalk = null;
  const ok = await startOn(u, xf, t.kind === 'yt' ? 'yt' : !!t.net);
  if (t !== current()) return;
  if (!ok) { if (t.net && t.kind !== 'yt') toast('No se puede reproducir ahora mismo. Prueba con otra.'); updatePlayer(); return; }
  beginTrack(t, { talk });
}
/* Everything that happens when a song becomes the current one, however it started */
function beginTrack(t, { talk = null, viaMix = false, silent = false } = {}) {
  finishListen();
  if (!silent) {
    listen = { id: t.id, acc: 0, last: null, manual: false, title: t.title, artist: t.artist, dur: t.duration || 0, net: !!t.net, src: state.source };
    t.prevPlayed = t.lastPlayed || 0; t.plays = (t.plays || 0) + 1; t.lastPlayed = Date.now(); saveTrack(t);
    if (!t.net) { state.history = [t.id, ...state.history.filter(x => x !== t.id)].slice(0, 30); LS.set('history', state.history); }
    if (!talk && djShouldTalk(t)) talk = t.net ? { text: t.kind === 'yt' ? ytIntro(t) : radioIntro(t), kind: 'siguiente' } : { text: introFor(t), kind: liked(t) ? 'favorita' : 'siguiente' };
  }
  updatePlayer(); markPlaying(); mediaSession(t); updateTicker(); applyTheme(); updateBgCover(); pushWidget();
  if (!silent && !state.playedOnce) { state.playedOnce = true; if ($('.fy-cont')) renderForYou(); }
  if (t.dev && !t.artChecked) fetchArt(t);
  if (talk && talk.yt) talk.text = ytIntro(talk.yt);
  if (talk && !silent) { if (viaMix && mixing) mixing.talk = talk; else djTalk(talk.text, talk.kind); }
  if (!t.net) getAnalysis(t).then(a => { if (current() !== t) return; if (a) { if (A < 2 && !silent) applyNorm(A, t); } else setTimeout(() => scheduleAnalysis(t), 1500); });
  if (!silent) brainOnBegin(t);
  prefetchNext(); saveSession(true);
  if (typeof onTrackLyrics === 'function') onTrackLyrics(t);
}
async function prefetchNext() {
  naSyncNext();
  const id = state.queue[state.qi + 1]; const t = id && trackById(id);
  if (t && !t.net) { try { await audioUrl(t); coverUrl(t, 'full'); setTimeout(() => scheduleAnalysis(t), 4000); } catch {} if (ONLINE) setTimeout(() => artistFact(t.artist), 2500); if (nvUsable()) setTimeout(() => { if (state.queue[state.qi + 1] === t.id) nvPrepareIntro(t); }, 7000); }
}
async function fadePause() { stopTalk(); if (mixing) finishMix(true); const k = A; await tween('f' + k, 0, 160); els.forEach(e => { if (!e.paused) e.pause(); }); deckF[k] = 1; applyVol(); saveSession(true); }
async function resume() { const d = act(); wakeCtx(); deckF[A] = 0; applyVol(); try { await d.play(); } catch { deckF[A] = 1; applyVol(); return; } tween('f' + A, 1, 240); }
function togglePlay() {
  if (!current() || !act().getAttribute('src')) { if (resumeSession(true)) return; if (state.mix) playMix(0); else spinRoulette(); return; }
  if (act().paused) resume(); else fadePause();
}
const hasNext = () => state.qi + 1 < state.queue.length || state.repeat === 'all' || state.source === 'radio';
function next(auto) {
  if (!state.queue.length) return;
  if (mixing) finishMix(true);
  const d = act();
  if (auto && state.repeat === 'one') { d.currentTime = 0; d.play().catch(() => {}); advancing = false; return; }
  if (!auto) markManual();
  if (!auto && state.source === 'radio' && current() && !current().net && listen && listen.acc < 60) {
    const sk = current(); BRAIN.adjId = sk.id; brainOnListen({ id: sk.id, skip: true, pct: 0 });
    state.queue = state.queue.slice(0, state.qi + 1).concat(radioNext(sk, 6));
  }
  if (state.source === 'radio' && state.qi + 2 >= state.queue.length) state.queue.push(...radioBatch(state.queue.slice(-3)));
  if (state.mixEndless && state.qi + 2 >= state.queue.length) { const more = mixRefill(); if (more.length) { state.queue.push(...more); renderQueue(); } }
  const xf = auto ? 0 : (d.paused ? 0 : MANUAL_XF);
  if (state.qi + 1 < state.queue.length) { state.qi++; loadCurrent(true, xf); }
  else if (state.repeat === 'all' || !auto) { state.qi = 0; loadCurrent(true, xf); }
  else { d.pause(); updatePlayer(); }
}
function prev() {
  if (!state.queue.length) return;
  if (mixing) finishMix(true);
  const d = act();
  if (d.currentTime > 3 && isFinite(d.duration)) { d.currentTime = 0; return; }
  markManual();
  state.qi = state.qi > 0 ? state.qi - 1 : (state.repeat === 'all' ? state.queue.length - 1 : 0); loadCurrent(true, d.paused ? 0 : MANUAL_XF);
}

/* ---------- Automatic mixes between songs ---------- */
function nextForMix() {
  if (state.repeat === 'one') return null;
  let i = state.qi + 1;
  if (i >= state.queue.length) {
    if (state.source === 'radio') state.queue.push(...radioBatch(state.queue.slice(-3)));
    else if (state.mixEndless) { const more = mixRefill(); if (more.length) { state.queue.push(...more); renderQueue(); } else return null; }
    else if (state.repeat === 'all' && state.queue.length > 1) i = 0; else return null;
  }
  const t = trackById(state.queue[i]); return t && !t.net ? { t, i } : null;
}
function planMix(aA, bA, durA) {
  const a = aA || { introStart: 0, outroEnd: durA, fadeStart: durA, bpm: 0, conf: 0 };
  const b = bA || { introStart: 0, introLoud: 0, bpm: 0, conf: 0 };
  const outEnd = Math.min(durA, a.outroEnd || durA);
  const fadeLen = a.fadeStart ? outEnd - a.fadeStart : 0, hasFade = fadeLen > 2;
  const tempoOK = a.conf >= 2 && b.conf >= 2 && a.bpm > 0 && b.bpm > 0;
  let ratio = tempoOK ? a.bpm / b.bpm : 1;
  if (tempoOK) { if (ratio > 1.6) ratio /= 2; else if (ratio < 0.625) ratio *= 2; }
  /* Up to 7 % apart is always matched; up to 10 % when both beats are very clear */
  const dev = Math.abs(ratio - 1), close = tempoOK && (dev <= 0.07 || (dev <= 0.1 && a.conf >= 3 && b.conf >= 3));
  const beatA = a.bpm > 0 && a.conf >= 1.5, beatB = b.bpm > 0 && b.conf >= 1.5;
  const want = Math.max(2, Math.min(16, AUD.mixLen));
  let style = AUD.mix, len = want;
  /* Automatic choices never rush: at least 6 s, and the echo cut is only used when you pick it */
  if (!G || AUD.direct) style = style === 'corte' ? 'corte' : 'suave';
  else if (style === 'auto') style = close ? 'dj' : (beatA && beatB ? 'filtro' : 'suave');
  else if (style === 'dj' && !tempoOK) style = 'suave';
  else if (style === 'filtro' && !beatA) style = 'suave';
  if (AUD.mix !== style) len = Math.max(6, want);
  const beat = a.bpm > 0 ? 60 / a.bpm : 0.5, bar = 4 * beat;
  /* Snap to beats, or to whole bars when the bar line is clear */
  const barsA = a.bpm > 0 && isFinite(a.downOut) && (a.barSure || 0) >= 0.15;
  const snap = x => (a.bpm > 0 && isFinite(a.gridOut)) ? a.gridOut + Math.round((x - a.gridOut) / beat) * beat : x;
  const snapBar = x => barsA ? a.downOut + Math.round((x - a.downOut) / bar) * bar : snap(x);
  const minStart = Math.max(durA * 0.4, 1);
  let bStart = Math.max(0, (b.introStart || 0) - 0.03), start, swap = 0, cut = 0, rateB = 1, aHold = 0;
  const bOne = b.bpm > 0 ? (isFinite(b.downIn) && (b.barSure || 0) >= 0.15 ? b.downIn : b.gridIn) : null;
  const barsFor = () => { let n = 4; if (n * bar < 6) n = 8; if (n * bar > 14) n = 2; return n; };
  if (style === 'corte') { len = 0.08; start = outEnd - len; }
  else if (style === 'suave') {
    /* A song that fades by itself keeps its own fade: the next one rises under it from a little before */
    if (hasFade && AUD.mix !== 'suave') len = Math.max(len, Math.min(12, fadeLen + 2.5));
    start = outEnd - len;
    if (hasFade) aHold = Math.max(0, Math.min(0.6, (a.fadeStart - start) / len));
  } else if (style === 'dj') {
    /* Whole bars of overlap that start on a bar line; the bass changes hands on the bar in the middle */
    const E = hasFade ? Math.min(outEnd, a.fadeStart + 2 * beat) : outEnd;
    const bars = barsFor();
    start = snapBar(E - bars * bar);
    if (start + 2 * beat > E) start -= bar;
    len = Math.max(4, Math.min(E, start + bars * bar) - start);
    swap = Math.max(1.5, (barsA ? Math.round(bars / 2) * bar : snap(start + len / 2) - start));
    if (bOne !== null && isFinite(bOne)) bStart = Math.max(0, bOne);
    rateB = close ? ratio : 1;
  } else if (style === 'filtro') {
    /* Four bars (6–14 s): this song sinks into the bass while the next rises from the treble, from a bar line */
    const E = hasFade ? Math.min(outEnd, a.fadeStart + 2 * beat) : outEnd, bars = barsFor();
    start = snapBar(E - bars * bar - 0.2);
    if (E - start < 6) start -= bar;
    len = Math.max(4, Math.min(14, E - start));
    if (bOne !== null && isFinite(bOne)) bStart = Math.max(0, bOne);
  } else {
    /* Echo-out: the next song enters on the cut while the last beat of this one repeats and fades */
    start = Math.min(snap(outEnd - 0.3), outEnd - 0.1); cut = 0.02; len = 2.8;
  }
  if (start < minStart) { const d = minStart - start; start = minStart; len = Math.max(0.08, len - d); swap = Math.min(swap, len / 2); cut = Math.min(cut, len / 2); }
  return { style, start, len, bStart, rateB, swap, cut, aHold, beat, gridA: a.gridOut, gridB: b.gridIn, beatB: b.bpm > 0 ? 60 / b.bpm : 0, bpmA: a.bpm, bpmB: b.bpm };
}
function checkAutoMix(d) {
  if (advancing || mixing || d.paused) return;
  if (sleepLeft() !== null && sleepLeft() < 25000) return;
  const t = current(); if (!t || t.net) return;
  const dur = d.duration; if (!isFinite(dur) || dur < 15) return;
  if (!mixArm) { if (dur - d.currentTime > 32) return; const nx = nextForMix(); if (nx && !naGapless(t, nx.t)) armMix(t, nx); return; }
  if (!mixArm.ready || mixArm.from !== A) return;
  if (mixArm.blind && d.currentTime < mixArm.plan.start - 1.5 && anaCache.has(t.id) && anaCache.has(mixArm.track.id)) {
    const arm = mixArm, plan = planMix(anaCache.get(t.id), anaCache.get(arm.track.id), dur);
    if (plan.start < d.currentTime + 0.4) plan.start = d.currentTime + 0.4;
    arm.blind = false; arm.plan = plan;
    try { decks[arm.to].currentTime = plan.bStart; } catch {}
    applyNorm(arm.to, arm.track, 0);
  }
  const nx = nextForMix();
  if (!nx || nx.t.id !== mixArm.id || nx.i !== mixArm.idx) { disarmMix(); return; }
  if (d.currentTime >= mixArm.plan.start - 0.3) { advancing = true; fireMix(); }
}
async function armMix(t, nx) {
  const token = { id: nx.t.id, idx: nx.i, from: A, ready: false }; mixArm = token;
  const url = await audioUrl(nx.t);
  const [aA, bA] = await Promise.all([getAnalysis(t), getAnalysis(nx.t)]);
  if (mixArm !== token || !url || A !== token.from) { if (mixArm === token) mixArm = null; return; }
  const d = act(); const plan = planMix(aA, bA, d.duration);
  if (plan.start < d.currentTime + 0.4) plan.start = d.currentTime + 0.4;
  const to = A === 0 ? 1 : 0, el = decks[to];
  clearTimeout(xfTimers[to]); el.pause(); resetDeckFx(to); el.src = url;
  try { el.currentTime = plan.bStart; } catch {}
  if (G) { G.dk[to].xf.gain.setValueAtTime(0.0001, actx.currentTime); deckF[to] = 1; } else deckF[to] = 0;
  applyVol(); applyNorm(to, nx.t, 0);
  /* Planned without knowing one of the songs yet: analyse them now and re-plan if there is time */
  const blind = !aA || !bA;
  if (blind) { scheduleAnalysis(t); scheduleAnalysis(nx.t); }
  Object.assign(token, { plan, to, track: nx.t, ready: true, blind });
}
function fireMix() {
  const arm = mixArm; mixArm = null;
  if (!arm || !arm.ready) { advancing = false; return; }
  const a = decks[arm.from];
  const delay = Math.max(0, (arm.plan.start - a.currentTime) / (a.playbackRate || 1));
  setTimeout(() => startMix(arm), delay * 1000);
}
async function startMix(arm) {
  const from = arm.from, to = arm.to, a = decks[from], b = decks[to], p = arm.plan;
  if (A !== from || a.paused) { advancing = false; return; }
  try { b.playbackRate = p.rateB || 1; } catch {}
  wakeCtx();
  try { await b.play(); } catch { advancing = false; next(true); return; }
  if (A !== from || a.paused || mixing) { b.pause(); advancing = false; return; }
  const late = Math.max(0, a.currentTime - p.start), rA = a.playbackRate || 1;
  if (late > 0.12) { try { b.currentTime = Math.min(p.bStart + late * (p.rateB || 1), Math.max(0, (b.duration || 1e9) - 1)); } catch {} }
  const q = Object.assign({}, p, { len: Math.max(0.08, p.len - late), swap: Math.max(0.05, p.swap - late), cut: Math.max(0.02, p.cut - late) });
  const L = q.len / rA;
  mixing = { from, to, plan: q, timers: [], done: false, talk: null };
  A = to; state.qi = arm.idx;
  scheduleMixFx(q, from, to, L);
  beginTrack(arm.track, { viaMix: true }); updateQuality();
  mixing.timers.push(setTimeout(() => finishMix(false), L * 1000 + 150));
  if (q.style === 'dj' && q.beatB && q.beat && isFinite(q.gridA) && isFinite(q.gridB)) {
    mixing.pll = setInterval(() => {
      if (!mixing || b.paused) return;
      const phA = (a.currentTime - q.gridA) / q.beat, phB = (b.currentTime - q.gridB) / q.beatB;
      let err = (phA - phB) % 1; err -= Math.round(err);
      try { b.playbackRate = q.rateB * (1 + Math.max(-0.04, Math.min(0.04, err * 0.25))); } catch {}
    }, 120);
  }
}
/* Fade shapes, sampled for setValueCurveAtTime. Nothing may be scheduled until a curve has finished */
const mkCurve = (f, from = 0, to = 1) => { const c = new Float32Array(96); for (let i = 0; i < 96; i++) c[i] = Math.max(0.0001, from + (to - from) * f(i / 95)); return c; };
const C_UP = x => Math.sin(x * Math.PI / 2), C_DOWN = x => Math.cos(x * Math.PI / 2), C_S = x => 0.5 - 0.5 * Math.cos(x * Math.PI);
function curveAt(pr, c, t, dur) {
  dur = Math.max(0.03, dur);
  try { pr.setValueCurveAtTime(c, t, dur); }
  catch { try { pr.setValueAtTime(c[0], t); pr.linearRampToValueAtTime(c[c.length - 1], t + dur); } catch {} }
  return t + dur + 0.002;
}
function scheduleMixFx(p, from, to, L) {
  if (!G) {
    if (p.style === 'corte') { deckF[from] = 0; deckF[to] = 1; applyVol(); return; }
    deckF[to] = 0; applyVol(); xfadeNoGraph(from, to, L * 1000); return;
  }
  const t0 = actx.currentTime, now = t0 + 0.02, fa = G.dk[from], fb = G.dk[to];
  for (const d of [fa, fb]) for (const pr of [d.hp.frequency, d.lp.frequency, d.xf.gain, d.send.gain]) hold(pr, t0);
  deckF[from] = 1; deckF[to] = 1; applyVol();
  const rA = decks[from].playbackRate || 1, end = now + Math.max(0.05, L);
  if (p.style === 'corte') {
    fa.xf.gain.setValueAtTime(1, now); fa.xf.gain.linearRampToValueAtTime(0.0001, now + 0.06);
    fb.xf.gain.setValueAtTime(0.0001, now); fb.xf.gain.linearRampToValueAtTime(1, now + 0.06);
  } else if (p.style === 'suave') {
    /* Equal power; if this song fades by itself it keeps playing untouched until its own fade begins */
    const hT = now + L * Math.max(0, Math.min(0.6, p.aHold || 0));
    fa.xf.gain.setValueAtTime(1, now); curveAt(fa.xf.gain, mkCurve(C_DOWN), hT, end - hT);
    fb.xf.gain.setValueAtTime(0.0001, now); curveAt(fb.xf.gain, p.aHold > 0 ? mkCurve(C_S) : mkCurve(C_UP), now, L);
  } else if (p.style === 'dj') {
    /* The next song creeps in without its bass, the low end changes hands over two beats on the bar line,
       then this one leaves through a closing low-pass */
    const beat = Math.max(0.25, (p.beat || 0.5) / rA), sw = Math.max(now + 0.3, Math.min(end - 1, now + p.swap / rA));
    const xa = Math.max(now + 0.2, sw - beat * 0.75), xb = Math.max(xa + 0.1, Math.min(end - 0.1, sw + beat));
    fb.hp.frequency.setValueAtTime(320, now); fb.hp.frequency.setValueAtTime(320, xa); fb.hp.frequency.exponentialRampToValueAtTime(14, xb); fb.hp.frequency.setValueAtTime(0, xb + 0.05);
    fa.hp.frequency.setValueAtTime(12, now); fa.hp.frequency.setValueAtTime(12, xa); fa.hp.frequency.exponentialRampToValueAtTime(300, xb);
    fb.xf.gain.setValueAtTime(0.0001, now);
    const up = curveAt(fb.xf.gain, mkCurve(C_S, 0, 0.9), now, xa - now);
    fb.xf.gain.setValueAtTime(0.9, up); fb.xf.gain.linearRampToValueAtTime(1, Math.max(up + 0.05, sw));
    fa.lp.frequency.setValueAtTime(G.nyq, now); fa.lp.frequency.setValueAtTime(G.nyq, sw); fa.lp.frequency.exponentialRampToValueAtTime(900, end);
    fa.xf.gain.setValueAtTime(1, now); curveAt(fa.xf.gain, mkCurve(C_DOWN), sw, end - sw);
  } else if (p.style === 'filtro') {
    /* This song sinks into the bass while the next rises out of the treble; both sweeps are exponential so they sound even */
    const open = now + L * 0.6;
    fa.lp.frequency.setValueAtTime(G.nyq, now); fa.lp.frequency.exponentialRampToValueAtTime(420, end);
    fa.xf.gain.setValueAtTime(1, now); curveAt(fa.xf.gain, mkCurve(C_DOWN), now + L * 0.2, L * 0.8);
    fb.hp.frequency.setValueAtTime(1500, now); fb.hp.frequency.exponentialRampToValueAtTime(20, open); fb.hp.frequency.setValueAtTime(0, open + 0.05);
    fb.xf.gain.setValueAtTime(0.0001, now); const up = curveAt(fb.xf.gain, mkCurve(C_UP), now, L * 0.8); fb.xf.gain.setValueAtTime(1, up);
  } else {
    /* The send is taken before the fader, so the echo keeps catching this song for 3/4 of a beat after its cut */
    const beat = Math.max(0.2, p.beat / rA), cutT = now + p.cut / rA, d = Math.min(1.5, beat * 0.75);
    G.echo.eDel.delayTime.setValueAtTime(d, now);
    fa.send.gain.setValueAtTime(0, now); fa.send.gain.linearRampToValueAtTime(0.9, cutT + 0.03);
    fa.send.gain.setValueAtTime(0.9, cutT + d); fa.send.gain.linearRampToValueAtTime(0, cutT + d + 0.06);
    fa.xf.gain.setValueAtTime(1, now); fa.xf.gain.linearRampToValueAtTime(0.0001, cutT + 0.1);
    fb.xf.gain.setValueAtTime(0.0001, now); fb.xf.gain.linearRampToValueAtTime(0.5, cutT + 0.12); fb.xf.gain.linearRampToValueAtTime(1, cutT + 1.2);
  }
}
function finishMix(early) {
  const m = mixing; if (!m || m.done) return; m.done = true; mixing = null;
  m.timers.forEach(clearTimeout); clearInterval(m.pll);
  if (xfNG) { clearInterval(xfNG.iv); xfNG.res(false); xfNG = null; }
  const a = decks[m.from], b = decks[m.to];
  try { a.pause(); } catch {}
  resetDeckFx(m.from); deckF[m.from] = 1;
  if (G) {
    const fb = G.dk[m.to], now = actx.currentTime;
    for (const pr of [fb.hp.frequency, fb.lp.frequency, fb.xf.gain, fb.send.gain]) hold(pr, now);
    fb.hp.frequency.setTargetAtTime(1, now, 0.05); fb.hp.frequency.setValueAtTime(0, now + 0.4);
    fb.lp.frequency.setTargetAtTime(G.nyq, now, 0.05); fb.xf.gain.setTargetAtTime(1, now, early ? 0.02 : 0.05); fb.send.gain.setTargetAtTime(0, now, 0.02);
  }
  deckF[m.to] = 1; applyVol();
  advancing = false;
  if (Math.abs((b.playbackRate || 1) - 1) > 0.002) {
    let r = b.playbackRate; const step = (1 - r) / 25;
    const iv = setInterval(() => { r += step; if (b.paused || A !== m.to || (step > 0 ? r >= 1 : r <= 1)) { r = 1; clearInterval(iv); } try { b.playbackRate = r; } catch {} }, 280);
  }
  updateQuality();
  if (m.talk && !early) djTalk(m.talk.text, m.talk.kind);
}

/* ---------- Listening memory (feeds your stats and Nova's recommendations) ---------- */
let listen = null, lastSessionSave = 0, memEvents = [];
function markManual() { if (listen) listen.manual = true; }
function trackListen(d) {
  if (!listen || d.paused) return;
  const t = d.currentTime;
  if (listen.last != null) { const dt = t - listen.last; if (dt > 0 && dt < 3) listen.acc += dt / Math.max(0.5, d.playbackRate || 1); }
  listen.last = t;
}
function finishListen() {
  const l = listen; listen = null;
  if (!l || l.acc < 1) return;
  recordEvent(makeEvent(l, Date.now()));
}
function makeEvent(l, ts) {
  const now = new Date(ts), dur = l.dur || 0, pct = dur ? Math.min(1, l.acc / dur) : 0;
  return { ts, id: l.id, sec: Math.round(l.acc), pct: Math.round(pct * 100) / 100, skip: !!(l.manual && !l.net && pct < 0.4 && l.acc < 60), hour: now.getHours(), dow: now.getDay(), src: l.src || state.source, title: l.title, artist: l.artist, net: !!l.net };
}
function recordEvent(ev) {
  if (!LS.get('memOn', true)) return;
  memEvents.push(ev); if (memEvents.length > 30000) memEvents.splice(0, memEvents.length - 25000);
  idbPut('events', ev);
  const t = trackById(ev.id);
  if (t && !t.net) {
    t.listenSec = (t.listenSec || 0) + ev.sec;
    if (ev.skip) t.skips = (t.skips || 0) + 1;
    if (ev.sec >= 30) { t.hours = t.hours && t.hours.length === 24 ? t.hours : new Array(24).fill(0); t.hours[ev.hour]++; }
    saveTrack(t);
  }
  if (typeof onMemoryChange === 'function') onMemoryChange();
  if (BRAIN.adjId === ev.id) BRAIN.adjId = null; else brainOnListen(ev);
}
function saveSession(force) {
  if (!force && Date.now() - lastSessionSave < 8000) return;
  lastSessionSave = Date.now();
  const t = current(); if (!t || t.net) return;
  LS.set('session', { q: state.queue.slice(Math.max(0, state.qi - 50), state.qi + 150), i: Math.min(state.qi, 50), pos: Math.floor(act().currentTime || 0), src: state.source, ts: Date.now(), listen: listen && !listen.net ? { id: listen.id, acc: Math.round(listen.acc), title: listen.title, artist: listen.artist, dur: listen.dur, manual: false, src: state.source } : null });
}
function resumeSession(play) {
  const s = LS.get('session', null); if (!s || !s.q || !s.q.length) return false;
  const ids = s.q.filter(id => trackById(id)); if (!ids.length) return false;
  const i = Math.max(0, ids.indexOf(s.q[s.i]));
  state.queue = ids; state.qi = i; state.source = s.src === 'radio' ? 'library' : (s.src || 'library');
  const p = loadCurrent(false, 0, s.pos || 0);
  if (play) p.then(() => resume());
  return true;
}
document.addEventListener('visibilitychange', () => { if (document.hidden) saveSession(true); });
window.addEventListener('pagehide', () => saveSession(true));

els.forEach((d, idx) => {
  d.addEventListener('ended', () => {
    if (d !== act() || mixing) return;
    if (sleepLeft() !== null && sleepLeft() < 4000) { sleepFinish(); return; }
    if (advancing && !mixArm) advancing = false;
    if (advancing) return;
    next(true);
  });
  d.addEventListener('play', () => { if (d === act()) { updatePlayBtn(); syncNativeState(); pushWidget(); vizKick(); } });
  d.addEventListener('pause', () => { if (d === act()) { updatePlayBtn(); syncNativeState(); pushWidget(); } ctxIdle(); });
  d.addEventListener('seeked', () => {
    if (d !== act()) return;
    syncNativeState(); if (listen) listen.last = d.currentTime;
    if (mixArm && mixArm.ready && d.currentTime < mixArm.plan.start - 40) disarmMix();
  });
  d.addEventListener('playing', () => { if (d === act()) tapAnalyser(d); });
  d.addEventListener('timeupdate', () => {
    if (d !== act()) return;
    updateSeek(); trackListen(d);
    if (nativeMS && Date.now() - lastPosSync > 5000) syncNativeState();
    if (idx < 2) checkAutoMix(d);
    saveSession(false);
    if (typeof lyricsTick === 'function') lyricsTick(d.currentTime);
  });
  d.addEventListener('loadedmetadata', () => { if (d !== act()) return; const t = current(); if (t && !t.net && isFinite(d.duration) && (!t.duration || Math.abs(t.duration - d.duration) > 1)) { t.duration = d.duration; if (listen && listen.id === t.id) listen.dur = d.duration; saveTrack(t); } updateSeek(); updateQuality(); });
  d.addEventListener('error', () => {
    if (d !== act() || !d.getAttribute('src')) return;
    const t = current();
    toast(t ? (t.kind === 'yt' && d === ytEl ? `No puedo poner «${t.title}»: ${ytEl.errorText}.${state.queue.length > 1 ? ' Sigo con lo siguiente.' : ''}` : t.net ? `No se puede reproducir «${t.title}» ahora mismo.` : `No puedo reproducir «${t.title}» (formato no compatible). Paso a la siguiente.`) : 'No puedo reproducir ese archivo.');
    if (t && state.queue.length > 1 && !d._skipped) { d._skipped = true; setTimeout(() => { d._skipped = false; next(false); }, 600); }
  });
});

const ICON = {
  play: '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4.5v15l13-7.5z"/></svg>',
  pause: '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M6 4h4.5v16H6zM13.5 4H18v16h-4.5z"/></svg>',
  playS: '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 4.5v15l13-7.5z"/></svg>',
  heart: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M12 20s-7.5-4.6-9.2-9.3C1.7 7.5 3.8 4.5 7 4.5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.2 0 5.3 3 4.2 6.2C19.5 15.4 12 20 12 20z"/></svg>',
  heartOn: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 20s-7.5-4.6-9.2-9.3C1.7 7.5 3.8 4.5 7 4.5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.2 0 5.3 3 4.2 6.2C19.5 15.4 12 20 12 20z"/></svg>',
  edit: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
  trash: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg>',
  shuffle: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"/></svg>'
};
function setRange(el) { const max = +el.max || 100, min = +el.min || 0; el.style.setProperty('--p', ((el.value - min) / (max - min) * 100) + '%'); }
function updatePlayBtn() { const p = !act().paused; $$('[data-ctl="play"]').forEach(b => { b.innerHTML = p ? ICON.pause : ICON.play; b.setAttribute('aria-label', p ? 'Pausar' : 'Reproducir'); }); }
let seeking = false;
function updateSeek() {
  if (document.hidden) return;
  const d = act(), dur = d.duration, c = d.currentTime;
  const live = current()?.kind === 'radio';
  const ct = fmt(c), dt = live ? 'EN VIVO' : fmt(isFinite(dur) ? dur : (current()?.duration || 0));
  $$('[data-t="cur"]').forEach(e => { if (!seeking && e.textContent !== ct) e.textContent = ct; }); $$('[data-t="dur"]').forEach(e => { if (e.textContent !== dt) e.textContent = dt; });
  if (!seeking) { const v = String(isFinite(dur) && dur > 0 ? Math.round((c / dur) * 1000) : 0); $$('.seek').forEach(s => { if (s.value !== v || !s.style.getPropertyValue('--p')) { s.value = v; setRange(s); } }); }
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) updateSeek(); });
function updateQuality() {
  const t = current(), q = quality(t), fx = t && t.net ? [] : soundLabel();
  if (mixing && MIX_STYLES[mixing.plan.style]) { const pl = mixing.plan, bpm = pl.style === 'dj' && pl.bpmA && pl.bpmB ? ` · ${Math.round(pl.bpmA)}→${Math.round(pl.bpmB)} BPM` : ''; ['#plQuality', '#npQ'].forEach(x => { $(x).textContent = `MEZCLA ${MIX_STYLES[pl.style].name.toUpperCase()}${bpm}`; $(x).title = MIX_STYLES[pl.style].desc; }); return; }
  const txt = t && t.net ? (t.kind === 'radio' ? 'RADIO EN DIRECTO' : t.kind === 'yt' ? 'YOUTUBE' : 'AVANCE 30 S') : (q ? q.short : 'DIRECTO') + (G && eqActive() ? ' · EQ' : '');
  const title = t && t.net ? (t.kind === 'radio' ? `Radio por internet${t.codec ? ' · ' + t.codec : ''}${t.bitrate ? ' ' + t.bitrate + ' kbps' : ''}` : t.kind === 'yt' ? 'Vídeo de YouTube, con su reproductor oficial' : 'Avance de 30 segundos por internet') : (q ? q.label : 'Archivo original') + (fx.length ? ' · con ' + listES(fx) : ' · sale directo del archivo, sin procesar');
  ['#plQuality', '#npQ'].forEach(s => { $(s).textContent = txt; $(s).title = title; });
}
function updateArt() {
  if (state.heroMode === 'art') $('#artWrap').innerHTML = bigArtHTML(current());
  if (!$('#np').hidden) renderNP();
}
function updatePlayer() {
  renderNovaChips();
  const t = current();
  $('#plCover').outerHTML = coverHTML(t, 'lg', 'plCover', ' data-act="np-open"');
  $('#plTitle').textContent = t ? t.title : 'Nada sonando';
  $('#plArtist').textContent = t ? t.artist : 'Gira la ruleta o pídele algo a la DJ';
  $('#plLike').hidden = !t || !!t.net; $('#npLike').hidden = !t || !!t.net;
  document.body.classList.toggle('live', !!(t && t.kind === 'radio'));
  $$('[data-like-current]').forEach(lk => { if (!t) return; lk.innerHTML = liked(t) ? ICON.heartOn : ICON.heart; lk.classList.toggle('liked', liked(t)); lk.setAttribute('aria-pressed', liked(t)); });
  $('#btnShuffle').classList.toggle('on', state.shuffle); $('#btnShuffle').setAttribute('aria-pressed', state.shuffle);
  $('#btnRepeat').classList.toggle('on', state.repeat !== 'off'); $('#repBadge').textContent = state.repeat === 'one' ? '1' : '';
  $('#btnRepeat').setAttribute('aria-label', state.repeat === 'off' ? 'Repetir: no' : state.repeat === 'all' ? 'Repetir: toda la lista' : 'Repetir: esta canción');
  updatePlayBtn(); updateSeek(); updateQuality(); updateArt();
  ytLayout(); renderClipBtn();
}
function markPlaying() { const id = current()?.id; $$('[data-track]').forEach(el => el.classList.toggle('is-playing', !!id && el.dataset.track === id)); }
const artCache = new Map();
function artDataUrl(t) {
  const blob = t && (t.thumb || t.cover); if (!blob) return Promise.resolve(null);
  if (!artCache.has(t.id)) { artCache.set(t.id, new Promise(r => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.onerror = () => r(null); fr.readAsDataURL(blob); })); while (artCache.size > 30) artCache.delete(artCache.keys().next().value); }
  return artCache.get(t.id);
}
let lastPosSync = 0;
function syncNativeState() {
  if (!nativeMS) return;
  const d = act(); lastPosSync = Date.now();
  try {
    nativeMS.setPlaybackState({ playbackState: !current() ? 'none' : d.paused ? 'paused' : 'playing' }).catch(() => {});
    if (isFinite(d.duration) && d.duration > 0) nativeMS.setPositionState({ duration: d.duration, position: Math.min(d.currentTime, d.duration), playbackRate: 1 }).catch(() => {});
  } catch {}
}
const phArt = new Map();
function placeholderArt(t) {
  const h = phHue(t), k = h + initial(t); if (phArt.has(k)) return phArt.get(k);
  let u = '';
  try {
    const c = document.createElement('canvas'); c.width = c.height = 256; const x = c.getContext('2d');
    const gr = x.createLinearGradient(0, 0, 256, 256); gr.addColorStop(0, `hsl(${h},75%,52%)`); gr.addColorStop(1, `hsl(${h + 60},70%,22%)`);
    x.fillStyle = gr; x.fillRect(0, 0, 256, 256);
    x.fillStyle = '#fff'; x.font = '900 120px Unbounded, "Arial Black", sans-serif'; x.textAlign = 'center'; x.textBaseline = 'middle'; x.fillText(initial(t), 128, 136);
    u = c.toDataURL('image/jpeg', 0.86);
  } catch {}
  phArt.set(k, u); return u;
}
async function nativeMediaSession(t) {
  try {
    if (!nativeMediaSession.wired) {
      nativeMediaSession.wired = true;
      const on = (action, fn) => { try { nativeMS.setActionHandler({ action }, fn); } catch {} };
      on('play', () => resume()); on('pause', () => fadePause()); on('stop', () => fadePause());
      on('nexttrack', () => next(false)); on('previoustrack', () => prev());
      on('seekto', dt => { const x = act(); if (dt && typeof dt.seekTime === 'number' && isFinite(x.duration)) { x.currentTime = dt.seekTime; advancing = false; syncNativeState(); } });
    }
    const art = t.net ? (t.artBig || t.art || placeholderArt(t)) : ((await artDataUrl(t)) || placeholderArt(t));
    await nativeMS.setMetadata({ title: t.title, artist: t.artist || (t.kind === 'radio' ? 'Radio en directo' : ''), album: t.album || (t.net ? 'Órbita · internet' : 'Órbita'), artwork: art ? [{ src: art, sizes: '256x256', type: 'image/jpeg' }] : [] });
    syncNativeState();
  } catch {}
}
function mediaSession(t) {
  if (nativeMS) { nativeMediaSession(t); return; }
  try {
    if (!('mediaSession' in navigator)) return;
    const art = coverUrl(t, 'full');
    navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album || 'Órbita', artwork: art ? [{ src: art, sizes: '512x512' }] : [] });
    if (!mediaSession.wired) {
      mediaSession.wired = true;
      navigator.mediaSession.setActionHandler('play', () => resume());
      navigator.mediaSession.setActionHandler('pause', () => fadePause());
      navigator.mediaSession.setActionHandler('nexttrack', () => next(false));
      navigator.mediaSession.setActionHandler('previoustrack', () => prev());
    }
  } catch {}
}
document.addEventListener('input', e => {
  const s = e.target.closest && e.target.closest('.seek'); if (!s) return;
  seeking = true; setRange(s); const d = act().duration; if (isFinite(d)) $$('[data-t="cur"]').forEach(x => { x.textContent = fmt(s.value / 1000 * d); });
});
document.addEventListener('change', e => {
  const s = e.target.closest && e.target.closest('.seek'); if (!s) return;
  const d = act(); if (isFinite(d.duration)) { d.currentTime = s.value / 1000 * d.duration; advancing = false; } seeking = false; updateSeek();
});
const vol = $('#vol'); vol.value = Math.round(baseVol * 100); setRange(vol);
vol.addEventListener('input', () => { baseVol = vol.value / 100; applyVol(); setRange(vol); LS.set('vol', +vol.value); });
document.addEventListener('click', e => {
  const c = e.target.closest('[data-ctl]'); if (c) { const k = c.dataset.ctl; if (k === 'play') togglePlay(); else if (k === 'next') next(false); else if (k === 'prev') prev(); return; }
  const l = e.target.closest('[data-like-current]'); if (l) { const t = current(); if (t) toggleLike(t); }
});
$('#btnShuffle').onclick = () => {
  state.shuffle = !state.shuffle; LS.set('shuffle', state.shuffle);
  if (state.shuffle && state.queue.length > 1) { const cur = state.queue[state.qi]; state.queue = [cur, ...shuffleArr(state.queue.filter((_, i) => i !== state.qi))]; state.qi = 0; prefetchNext(); }
  updatePlayer(); toast(state.shuffle ? 'Aleatorio activado' : 'Aleatorio desactivado');
};
$('#btnRepeat').onclick = () => { state.repeat = { off: 'all', all: 'one', one: 'off' }[state.repeat]; LS.set('repeat', state.repeat); updatePlayer(); };
function toggleLike(t) { if (t.net) return; t.liked = !liked(t); saveTrack(t); updatePlayer(); renderLibrary(); haptic(6); }

/* ---------- Sleep timer ---------- */
let sleepEnd = null, sleepFade = 30000, sleepIv = null;
function sleepLeft() { return sleepEnd ? sleepEnd - Date.now() : null; }
function setSleep(v) {
  if (v === 'off') { sleepEnd = null; sleepF = 1; applyVol(); clearInterval(sleepIv); renderSleep(); toast('Temporizador desactivado'); return; }
  const d = act();
  if (v === 'track') {
    if (!current() || !isFinite(d.duration)) { toast('Pon una canción primero'); return; }
    sleepEnd = Date.now() + Math.max(5, d.duration - d.currentTime) * 1000; sleepFade = 12000;
  } else { sleepEnd = Date.now() + (+v) * 60000; sleepFade = 30000; }
  clearInterval(sleepIv); sleepIv = setInterval(sleepTick, 1000); sleepTick();
  toast(v === 'track' ? 'Me paro al terminar esta canción' : `Me paro dentro de ${+v >= 60 ? (+v / 60).toString().replace('.5', ' h 30').replace(/^(\d+)$/, '$1 h') : v + ' min'}`);
  $('#sleepOverlay').hidden = true;
}
function sleepTick() {
  const left = sleepLeft(); if (left === null) return;
  if (left <= 0) { sleepFinish(); return; }
  sleepF = left < sleepFade ? Math.max(0.02, left / sleepFade) : 1; applyVol();
  renderSleep();
}
async function sleepFinish() {
  clearInterval(sleepIv); sleepEnd = null;
  await fadePause(); sleepF = 1; applyVol(); renderSleep();
  if (dj.voiceOn && dj.supported) djTalk(pick(['Que descanses. Buenas noches.', 'Hasta mañana. Que sueñes bonito.', 'Buenas noches. Aquí estaré cuando vuelvas.']), 'test', { forceTTS: true });
  else setBubble('Buenas noches. He parado la música.');
}
function renderSleep() {
  const left = sleepLeft(), on = left !== null && left > 0;
  const mins = on ? Math.ceil(left / 60000) : 0;
  $$('[data-act="sleep-open"]').forEach(b => b.classList.toggle('sleep-on', on));
  $$('.sleep-badge').forEach(b => { b.textContent = on ? (mins > 99 ? '99' : String(mins)) : ''; });
  $$('.sleep-label').forEach(b => { b.textContent = on ? `Para en ${mins} min` : 'Dormir'; });
  $('#sleepOff').hidden = !on;
  $('#sleepNote').textContent = on ? `La música se parará en ${mins} ${mins === 1 ? 'minuto' : 'minutos'}. Puedes cambiarlo o desactivarlo.` : 'La música bajará poco a poco y se parará sola. Nova te dará las buenas noches.';
}
document.addEventListener('click', e => { const b = e.target.closest('[data-sleep]'); if (b) setSleep(b.dataset.sleep); });

/* ---------- Now playing (full screen) ---------- */
function renderQueue() {
  naSyncNext();
  const box = $('#npQueue'); if (!box) return;
  const up = state.queue.slice(state.qi + 1, state.qi + 9).map((id, i) => [trackById(id), state.qi + 1 + i]).filter(x => x[0]);
  const upIc = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>';
  const xIc = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
  box.innerHTML = up.length ? `<div class="eyebrow">A continuación</div>` + up.map(([t, i], k) => `<div class="q-item"><button data-act="q-jump" data-i="${i}">${coverHTML(t)}<span class="row-text"><span class="t-title">${esc(t.title)}</span><span class="t-sub">${esc(t.artist)}</span></span></button>${k ? `<button class="icon-btn" data-act="q-up" data-i="${i}" aria-label="Subir ${esc(t.title)}">${upIc}</button>` : ''}<button class="icon-btn" data-act="q-del" data-i="${i}" aria-label="Quitar ${esc(t.title)} de la cola">${xIc}</button></div>`).join('') : '';
}
(() => {
  const art = $('#npArt'); let x0 = null, y0 = 0;
  art.addEventListener('pointerdown', e => { x0 = e.clientX; y0 = e.clientY; try { art.setPointerCapture(e.pointerId); } catch {} });
  art.addEventListener('pointermove', e => { if (x0 === null) return; const dx = e.clientX - x0; if (Math.abs(dx) > Math.abs(e.clientY - y0)) art.style.transform = `translateX(${dx * 0.35}px) rotate(${dx * 0.02}deg)`; });
  const end = e => {
    if (x0 === null) return; const dx = e.clientX - x0; x0 = null; art.style.transform = '';
    if (Math.abs(dx) > 70) { if (dx < 0) next(false); else prev(); haptic(8); }
  };
  art.addEventListener('pointerup', end); art.addEventListener('pointercancel', () => { x0 = null; art.style.transform = ''; });
})();
function renderNP() {
  renderAbout(current());
  const t = current();
  $('#npArt').innerHTML = bigArtHTML(t);
  const u = coverUrl(t, 'full'); const bg = $('#npBg');
  if (u) { bg.classList.remove('ph'); bg.style.backgroundImage = `url("${u}")`; } else { bg.classList.add('ph'); bg.style.backgroundImage = ''; }
  $('#npTitle').textContent = t ? t.title : 'Nada sonando';
  $('#npArtist').textContent = t ? t.artist : 'Gira la ruleta o pídele algo a la DJ';
  $('#npSrc').textContent = !t ? 'Ahora suena' : t.kind === 'radio' ? 'Radio por internet · en directo' : t.kind === 'yt' ? (t.clipOf ? 'Videoclip · YouTube' : 'Vídeo de YouTube') : t.net ? 'Avance de 30 s por internet' : state.source === 'radio' ? 'Órbita FM · modo radio' : state.source === 'daily' ? 'Tu mezcla del día' : state.source === 'mix' ? `Mezcla · ${state.mix?.name || ''}` : 'Desde tu biblioteca';
  const al = $('#npAlbum'); al.hidden = !(t && t.album); if (t && t.album) al.textContent = t.album;
  renderQueue();
}
function openNP() {
  $('#np').hidden = false; updatePlayer(); renderNP(); $('#np .np-close').focus();
  $('#np').classList.toggle('ly-open', LY.on); $('#npLyBtn').setAttribute('aria-pressed', LY.on);
  $('#np').classList.toggle('viz-open', VZ.np && !LY.on); $('#npVizBtn').setAttribute('aria-pressed', VZ.np && !LY.on); setTimeout(vizKick, 30);
  if (LY.on) { const t = current(); if (!t || LY.id !== t.id || !LY.rec) showLyrics(t); else lyricsTick(act().currentTime || 0, true); }
  if (STANDALONE) { try { history.pushState({ np: 1 }, '', location.hash || '#' + state.view); } catch {} }
  ytLayout();
}
function closeNP(fromPop) {
  if ($('#np').hidden) return;
  $('#np').hidden = true; setTimeout(vizKick, 30); ytLayout();
  if (STANDALONE && !fromPop && history.state && history.state.np) { try { history.back(); } catch {} }
}

/* ---------- DJ voice ---------- */
const dj = {
  supported: !!nativeTTS || ('speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'),
  voiceOn: LS.get('voiceOn', true), intros: LS.get('intros', true), who: LS.get('who', 'nova'),
  voice: null, voices: [], all: [], allLangs: LS.get('allVoices', false),
  rate: LS.get('vRate', 1), pitch: LS.get('vPitch', 1), style: LS.get('vStyle', 'natural'), talkGen: 0
};
const bub = { text: '', who: '', shown: 0, iv: null };
function renderBubble() {
  const b = $('#djBubble'); b.textContent = '';
  if (bub.who) { const w = document.createElement('span'); w.className = 'who'; w.textContent = bub.who; b.appendChild(w); }
  b.appendChild(document.createTextNode(bub.text.slice(0, bub.shown)));
  if (bub.shown < bub.text.length) { const c = document.createElement('span'); c.className = 'caret'; b.appendChild(c); }
}
function setBubble(text, who = '', cps = 45) {
  clearInterval(bub.iv); bub.text = text; bub.who = who; bub.shown = 0;
  if (reduceMotion || cps <= 0) { bub.shown = cps <= 0 ? 0 : text.length; renderBubble(); return; }
  const t0 = performance.now();
  bub.iv = setInterval(() => { const n = Math.floor((performance.now() - t0) / 1000 * cps); if (n > bub.shown) { bub.shown = Math.min(text.length, n); renderBubble(); } if (bub.shown >= text.length) clearInterval(bub.iv); }, 40);
  renderBubble();
}
function bubbleReveal(idx) { if (idx > bub.shown) { bub.shown = Math.min(bub.text.length, idx); renderBubble(); } }
function bubbleFinish() { naAfterTalk(); clearInterval(bub.iv); bub.shown = bub.text.length; renderBubble(); }

const REGION = { ES:'España', MX:'México', US:'EE. UU.', AR:'Argentina', CO:'Colombia', CL:'Chile', PE:'Perú', VE:'Venezuela', '419':'Latinoamérica', CU:'Cuba', UY:'Uruguay', PR:'Puerto Rico', EC:'Ecuador', BO:'Bolivia', CR:'Costa Rica', DO:'Rep. Dominicana', GT:'Guatemala', HN:'Honduras', NI:'Nicaragua', PA:'Panamá', PY:'Paraguay', SV:'El Salvador', GQ:'Guinea Ecuatorial' };
const LANGS = { es:'Español', en:'Inglés', pt:'Portugués', fr:'Francés', it:'Italiano', de:'Alemán', ca:'Catalán', gl:'Gallego', eu:'Euskera', ja:'Japonés', ko:'Coreano' };
function voiceScore(v) {
  const n = v.name || ''; let s = 0;
  if (/natural|neural/i.test(n)) s += 6;
  if (/online/i.test(n)) s += 2;
  if (/premium|enhanced|mejorada|siri/i.test(n)) s += 5;
  if (/google/i.test(n)) s += 3;
  if (/-network$/i.test(n)) s += 5;
  if (!v.localService) s += 1;
  if (/espeak|compact|eloquence/i.test(n)) s -= 4;
  if (/^es[-_]ES/i.test(v.lang)) s += 1;
  return s;
}
const isNatural = v => voiceScore(v) >= 4;
function voiceLabel(v) {
  const android = /^[a-z]{2,3}-[a-z0-9]{2,4}-x-([a-z0-9]+)-(local|network)$/i.exec(v.name || '');
  const name = android ? `Voz ${android[1].toUpperCase()}${android[2] === 'network' ? ' HD' : ''}`
    : (v.name || 'Voz').replace(/^(Microsoft|Google|Apple)\s+/i, '').replace(/\s*Online\s*\(Natural\)/i, '').replace(/\s*\((Natural|Enhanced|Premium)\)/i, '').replace(/\s+-\s+.*$/, '').trim() || v.name;
  const [lang, reg] = String(v.lang || '').split(/[-_]/);
  const where = lang === 'es' ? (REGION[(reg || '').toUpperCase()] || 'Español') : `${LANGS[lang] || lang}${reg ? ' · ' + reg.toUpperCase() : ''}`;
  return { name, where };
}
const setToggle = (el, on) => el.setAttribute('aria-pressed', on);
async function loadVoices() {
  if (!dj.supported) { renderVoices(); return; }
  let vs = [];
  if (nativeTTS) { try { const r = await nativeTTS.getSupportedVoices(); vs = (r && r.voices || []).map((v, i) => ({ ...v, _idx: i })); } catch {} }
  else { try { vs = speechSynthesis.getVoices() || []; } catch {} }
  dj.all = vs.slice().sort((a, b) => voiceScore(b) - voiceScore(a));
  dj.voices = dj.all.filter(v => /^es/i.test(v.lang));
  const saved = LS.get('voiceName', null);
  dj.voice = dj.all.find(v => v.name === saved) || dj.voices.find(v => /^es[-_]ES/i.test(v.lang)) || dj.voices[0] || null;
  renderVoices();
}
function renderVoices() {
  const btn = $('#voicePickBtn');
  btn.textContent = dj.voice ? `Voz: ${voiceLabel(dj.voice).name}` : 'Elegir voz';
  btn.hidden = !dj.supported;
  const list = dj.allLangs ? dj.all : dj.voices;
  setToggle($('#tAllVoices'), dj.allLangs);
  if (!dj.supported) { $('#voicesOut').innerHTML = `<li class="note">Este navegador no tiene voces. Prueba en Chrome o Edge, o usa tus grabaciones en «Tu voz».</li>`; return; }
  if (!list.length) { $('#voicesOut').innerHTML = `<li class="note">${dj.all.length ? 'No hay voces en español en este dispositivo. Activa «Más idiomas» o instala voces de español (mira la nota de abajo).' : 'Cargando las voces del dispositivo…'}</li>`; return; }
  $('#voicesOut').innerHTML = list.slice(0, 60).map(v => {
    const l = voiceLabel(v), on = dj.voice && v.name === dj.voice.name;
    return `<li class="voice ${on ? 'on' : ''}"><div class="v-text"><b>${esc(l.name)}</b><span>${isNatural(v) ? '<span class="pill nat">Natural</span>' : '<span class="pill" style="margin-left:0">Estándar</span>'}${esc(l.where)}${v.localService ? '' : ' · con internet'}</span></div><button class="btn small" data-act="voice-test" data-name="${esc(v.name)}">Probar</button>${on ? '<span class="pill nat">En uso</span>' : `<button class="btn small primary" data-act="voice-use" data-name="${esc(v.name)}">Usar</button>`}</li>`;
  }).join('');
}
if (dj.supported) {
  loadVoices();
  if (nativeTTS) { try { nativeTTS.addListener('onRangeStart', info => { if (info && typeof info.end === 'number') bubbleReveal((dj.nbase || 0) + info.end); }); } catch {} }
  else { try { speechSynthesis.addEventListener('voiceschanged', loadVoices); } catch {} }
  setTimeout(loadVoices, 900);
} else renderVoices();
$('#sttOffRow').hidden = !(NATIVE && nativeLib);
setToggle($('#tSttOff'), LS.get('sttOffline', false));
$('#tSttOff').onclick = () => { const v = !LS.get('sttOffline', false); LS.set('sttOffline', v); setToggle($('#tSttOff'), v); toast(v ? 'Te escucharé sin internet (con el reconocedor del móvil)' : 'Te escucharé como siempre (y sin internet, sin conexión)'); };
$('#tAllVoices').onclick = () => { dj.allLangs = !dj.allLangs; LS.set('allVoices', dj.allLangs); renderVoices(); };
function renderVoiceStyle() {
  $('#styleChips').innerHTML = VSTYLES.map(s => `<button class="chip" data-vstyle="${s.id}" aria-pressed="${dj.style === s.id}">${s.name}</button>`).join('');
  $('#rateIn').value = dj.rate; $('#pitchIn').value = dj.pitch; setRange($('#rateIn')); setRange($('#pitchIn'));
  $('#rateVal').textContent = '×' + (+dj.rate).toFixed(2); $('#pitchVal').textContent = '×' + (+dj.pitch).toFixed(2);
}
$('#styleChips').addEventListener('click', e => {
  const b = e.target.closest('[data-vstyle]'); if (!b) return; const s = VSTYLES.find(x => x.id === b.dataset.vstyle);
  dj.style = s.id; dj.rate = s.rate; dj.pitch = s.pitch; LS.set('vStyle', s.id); LS.set('vRate', s.rate); LS.set('vPitch', s.pitch); renderVoiceStyle();
  speakTTS(`Así sueno en modo ${s.name.toLowerCase()}. Te presento la siguiente canción.`);
});
['rateIn','pitchIn'].forEach(id => {
  $('#' + id).addEventListener('input', e => { const v = +e.target.value; if (id === 'rateIn') dj.rate = v; else dj.pitch = v; dj.style = 'custom'; renderVoiceStyle(); });
  $('#' + id).addEventListener('change', () => { LS.set('vRate', dj.rate); LS.set('vPitch', dj.pitch); LS.set('vStyle', 'custom'); speakTTS('Así suena con este ajuste.'); });
});
setToggle($('#tVoice'), dj.voiceOn); setToggle($('#tIntros'), dj.intros);
$('#tVoice').onclick = () => { dj.voiceOn = !dj.voiceOn; LS.set('voiceOn', dj.voiceOn); setToggle($('#tVoice'), dj.voiceOn); if (!dj.voiceOn) stopTalk(); else djTalk('Vuelvo a tener voz.', 'test'); };
$('#tIntros').onclick = () => { dj.intros = !dj.intros; LS.set('intros', dj.intros); setToggle($('#tIntros'), dj.intros); toast(dj.intros ? 'La DJ presentará cada canción' : 'La DJ solo hablará al empezar'); };
function setWho(w) {
  dj.who = w; LS.set('who', w);
  $('#whoSel').value = w; $$('#whoSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.who === w));
  $('#dj').classList.toggle('mine', w !== 'nova');
  $('#djName').textContent = w === 'mine' ? 'DJ Tú' : w === 'mix' ? `${dj.name} + Tú` : `DJ ${dj.name}`;
  $('#djRole').textContent = w === 'mine' ? 'Con tus grabaciones' : w === 'mix' ? 'Por turnos' : 'Tu asistente musical';
  if (w !== 'nova' && !state.clips.length) setBubble('Aún no has grabado ninguna frase. Ve a «Tu voz» y graba tu saludo; mientras tanto hablo yo.');
}
$('#whoSel').addEventListener('change', e => setWho(e.target.value));
$('#whoSeg').addEventListener('click', e => { const b = e.target.closest('[data-who]'); if (b) setWho(b.dataset.who); });
/* ---------- Sound settings: mixes between songs, equalizer, levelled volume ---------- */
function setMixStyle(v) {
  if (!MIX_STYLES[v]) v = 'auto';
  AUD.mix = v; LS.set('mixStyle', v); disarmMix();
  if (wantsGraph()) ensureGraph();
  $('#mixSel').value = v; renderSound();
}
function renderSound() {
  if (!$('#mixChips')) return;
  $('#mixChips').innerHTML = MIX_ORDER.map(id => [id, MIX_STYLES[id]]).map(([id, s]) => `<button class="chip" data-mix="${id}" aria-pressed="${AUD.mix === id}">${esc(s.name)}</button>`).join('');
  $('#mixDesc').textContent = MIX_STYLES[AUD.mix].desc;
  $('#mixLenBox').hidden = !['suave', 'auto'].includes(AUD.mix);
  $('#mixLenIn').value = AUD.mixLen; setRange($('#mixLenIn')); $('#mixLenVal').textContent = AUD.mixLen + ' s';
  renderEq();
  setToggle($('#tLevel'), AUD.level); setToggle($('#tDirect'), AUD.direct); renderEngine();
  $('#soundBody').classList.toggle('off', AUD.direct);
  $('#soundNote').textContent = AUD.direct
    ? `Sonido directo: la música sale tal cual del archivo, sin ecualizador ni mezclas (entre canciones habrá un fundido).${G ? ' Del todo, la próxima vez que abras Órbita.' : ''}`
    : 'Cada canción se analiza la primera vez que suena (ritmo, silencios y volumen) y Órbita lo recuerda para mezclar.';
}
$('#mixChips').addEventListener('click', e => { const b = e.target.closest('[data-mix]'); if (b) setMixStyle(b.dataset.mix); });
$('#mixSel').innerHTML = MIX_ORDER.map(id => [id, MIX_STYLES[id]]).map(([id, s]) => `<option value="${id}">${esc(s.name)}</option>`).join('');
$('#mixSel').value = AUD.mix;
$('#mixSel').addEventListener('change', e => { setMixStyle(e.target.value); toast(`Entre canciones: ${MIX_STYLES[AUD.mix].name.toLowerCase()}`); });
$('#mixLenIn').addEventListener('input', e => { AUD.mixLen = +e.target.value; LS.set('mixLen', AUD.mixLen); setRange(e.target); $('#mixLenVal').textContent = AUD.mixLen + ' s'; disarmMix(); });
/* How loud the equalizer makes the loudest frequency: the music is lowered by almost that much first, so boosting
   never distorts (the safety limiter catches the little that's left) */
let eqProbe = null, eqFreqs = null;
function eqPeakDb(g) {
  const plain = Math.max(0, ...g);
  if (!actx || typeof actx.createBiquadFilter !== 'function') return plain;
  try {
    if (!eqProbe) {
      eqProbe = EQ_BANDS.map((f, i) => { const b = actx.createBiquadFilter(); b.type = i === 0 ? 'lowshelf' : i === EQ_BANDS.length - 1 ? 'highshelf' : 'peaking'; b.frequency.value = f; b.Q.value = 1.41; return b; });
      eqFreqs = new Float32Array(96).map((_, k) => 20 * Math.pow(1000, k / 95));
    }
    const mag = new Float32Array(96).fill(1), tmp = new Float32Array(96), ph = new Float32Array(96);
    eqProbe.forEach((b, i) => { b.gain.value = g[i] || 0; b.getFrequencyResponse(eqFreqs, tmp, ph); for (let k = 0; k < 96; k++) mag[k] *= tmp[k]; });
    let mx = 0; for (let k = 0; k < 96; k++) if (mag[k] > mx) mx = mag[k];
    const db = 20 * Math.log10(mx || 1);
    return isFinite(db) ? Math.max(0, db) : plain;
  } catch { return plain; }
}
function setEq(id) {
  if (!eqPreset(id)) id = 'plano';
  if (id === 'custom' && AUD.eq !== 'custom') AUD.eqCustom = eqGains().slice();
  AUD.eq = id; LS.set('eq', id); LS.set('eqCustom', AUD.eqCustom);
  if (!AUD.eqOn) { AUD.eqOn = true; LS.set('eqOn', true); }
  ensureGraph(); applyEq(); renderEq(); updateQuality();
}
function setEqOn(on) { AUD.eqOn = !!on; LS.set('eqOn', AUD.eqOn); ensureGraph(); applyEq(); renderEq(); updateQuality(); }
let eqT = null;
function eqApplySoon() { if (eqT) return; eqT = requestAnimationFrame(() => { eqT = null; ensureGraph(); applyEq(); updateQuality(); }); }
const eqFmt = v => (v > 0 ? '+' : '') + v;
function renderEq() {
  const box = $('#eqPanel'); if (!box) return;
  setToggle($('#tEq'), AUD.eqOn);
  box.classList.toggle('off', !AUD.eqOn);
  const g = eqGains();
  $('#eqBands').innerHTML = EQ_BANDS.map((f, i) => `<div class="eqcol"><span class="eqval mono" data-bandval="${i}">${eqFmt(g[i])}</span><div class="eqtrack" role="slider" tabindex="0" aria-orientation="vertical" aria-valuemin="-12" aria-valuemax="12" aria-valuenow="${g[i]}" aria-valuetext="${eqFmt(g[i])} dB" aria-label="Banda de ${EQ_LABELS[i]} Hz" data-band="${i}"><i class="eqfill"></i><b class="eqknob"></b></div><span class="eqf mono">${EQ_LABELS[i]}</span></div>`).join('');
  $$('#eqBands .eqtrack').forEach(eqPaint);
  const sel = AUD.eq;
  $('#eqCards').innerHTML = EQ_CARDS.map(id => `<button class="eqcard" data-eq="${id}" aria-pressed="${sel === id}"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${EQ_ICON[id]}</svg><b>${esc(EQ_PRESETS[id].name)}</b></button>`).join('');
  const styles = Object.keys(EQ_PRESETS).filter(id => !EQ_CARDS.includes(id));
  const mine = eqMine();
  $('#eqChips').innerHTML = styles.map(id => `<button class="chip" data-eq="${id}" aria-pressed="${sel === id}">${esc(EQ_PRESETS[id].name)}</button>`).join('')
    + mine.map(m => `<span class="chip eqmine" aria-pressed="${sel === 'mine-' + m.id}"><button data-eq="mine-${esc(m.id)}">${esc(m.name)}</button><button class="eqdel" data-eqdel="${esc(m.id)}" aria-label="Borrar «${esc(m.name)}»">×</button></span>`).join('');
  $('#eqSaveRow').hidden = !(AUD.eqOn && sel === 'custom' && AUD.eqCustom.some(v => v));
  $('#eqName').textContent = AUD.eqOn ? eqName() : 'Apagado';
}
function eqPaint(tr) {
  const v = +tr.getAttribute('aria-valuenow') || 0, p = (12 - v) / 24 * 100;
  tr.style.setProperty('--p', p + '%');
  const fill = tr.firstElementChild;
  fill.style.top = Math.min(50, p) + '%'; fill.style.height = Math.abs(p - 50) + '%';
}
function eqSetBand(tr, v) {
  v = Math.max(-12, Math.min(12, Math.round(v)));
  const i = +tr.dataset.band;
  if (AUD.eq !== 'custom' || !AUD.eqOn) {
    AUD.eqCustom = eqGains().slice(); AUD.eq = 'custom'; LS.set('eq', 'custom');
    if (!AUD.eqOn) { AUD.eqOn = true; LS.set('eqOn', true); setToggle($('#tEq'), true); $('#eqPanel').classList.remove('off'); }
    $$('#eqPanel [data-eq]').forEach(c => { const host = c.closest('.eqmine') || c; host.setAttribute('aria-pressed', c.dataset.eq === 'custom'); });
    $('#eqName').textContent = eqName();
  }
  if (AUD.eqCustom[i] === v) return;
  AUD.eqCustom[i] = v; LS.set('eqCustom', AUD.eqCustom);
  tr.setAttribute('aria-valuenow', v); tr.setAttribute('aria-valuetext', eqFmt(v) + ' dB'); eqPaint(tr);
  $(`[data-bandval="${i}"]`).textContent = eqFmt(v);
  $('#eqSaveRow').hidden = !AUD.eqCustom.some(x => x);
  eqApplySoon();
}
/* Dragging a band (up and down), arrows on the keyboard, double tap puts it back to 0 */
let eqDrag = null;
$('#eqBands').addEventListener('pointerdown', e => {
  const tr = e.target.closest('.eqtrack'); if (!tr) return;
  e.preventDefault(); eqDrag = tr; try { tr.setPointerCapture(e.pointerId); } catch {}
  tr.classList.add('drag'); eqFromY(tr, e.clientY);
});
$('#eqBands').addEventListener('pointermove', e => { if (eqDrag) eqFromY(eqDrag, e.clientY); });
const eqEnd = () => { if (eqDrag) { eqDrag.classList.remove('drag'); eqDrag = null; } };
$('#eqBands').addEventListener('pointerup', eqEnd); $('#eqBands').addEventListener('pointercancel', eqEnd);
function eqFromY(tr, y) { const r = tr.getBoundingClientRect(); if (!r.height) return; eqSetBand(tr, 12 - ((y - r.top) / r.height) * 24); }
$('#eqBands').addEventListener('dblclick', e => { const tr = e.target.closest('.eqtrack'); if (tr) eqSetBand(tr, 0); });
$('#eqBands').addEventListener('keydown', e => {
  const tr = e.target.closest('.eqtrack'); if (!tr) return;
  const v = +tr.getAttribute('aria-valuenow') || 0;
  const d = { ArrowUp: 1, ArrowRight: 1, ArrowDown: -1, ArrowLeft: -1, PageUp: 3, PageDown: -3 }[e.key];
  if (d) { e.preventDefault(); eqSetBand(tr, v + d); } else if (e.key === 'Home') { e.preventDefault(); eqSetBand(tr, 12); } else if (e.key === 'End') { e.preventDefault(); eqSetBand(tr, -12); } else if (e.key === '0') eqSetBand(tr, 0);
});
$('#eqPanel').addEventListener('click', e => {
  const del = e.target.closest('[data-eqdel]');
  if (del) { const id = del.dataset.eqdel; LS.set('eqMine', eqMine().filter(m => m.id !== id)); if (AUD.eq === 'mine-' + id) { AUD.eqCustom = eqGains().slice(); AUD.eq = 'custom'; LS.set('eq', 'custom'); } renderEq(); toast('Ajuste borrado'); return; }
  const b = e.target.closest('[data-eq]'); if (b) setEq(b.dataset.eq);
});
$('#tEq').onclick = () => { setEqOn(!AUD.eqOn); toast(AUD.eqOn ? `Ecualizador: ${eqName()}` : 'Ecualizador apagado'); };
$('#eqReset').onclick = () => { AUD.eqCustom = EQ_PRESETS.plano.g.slice(); AUD.eq = 'custom'; LS.set('eq', 'custom'); LS.set('eqCustom', AUD.eqCustom); ensureGraph(); applyEq(); renderEq(); updateQuality(); };
$('#eqSaveForm').addEventListener('submit', e => {
  e.preventDefault();
  const name = $('#eqSaveIn').value.trim().slice(0, 24); if (!name) { $('#eqSaveIn').focus(); return; }
  const list = eqMine().filter(m => norm(m.name) !== norm(name));
  if (list.length >= 8) { toast('Puedes guardar hasta 8 ajustes tuyos: borra alguno antes.'); return; }
  const m = { id: uid('eq'), name, g: AUD.eqCustom.slice() }; list.push(m); LS.set('eqMine', list);
  $('#eqSaveIn').value = ''; AUD.eq = 'mine-' + m.id; LS.set('eq', AUD.eq); renderEq(); toast(`Guardado: «${name}»`); haptic(8);
});
/* The engine's own signals: where each deck is, the end of a song, focus (calls, other apps), headphones out */
let naFocusPaused = false, naRefocus = false, naOwnTs = 0, naFails = 0;
const naOwnAudio = () => $('#dj').classList.contains('speaking') || Date.now() - naOwnTs < 4000 || (A === 3 && !ytEl.paused) || (A === 2 && !netEl.paused) || !clipEl.paused;
function naOk() { naFails = 0; }
/* Three songs in a row that the engine can't play: back to the usual engine, by itself */
function naFail() {
  if (++naFails < 3) return;
  LS.set('engineBroken', true); saveSession(true);
  toast('El motor de sonido de Android ha fallado varias veces: vuelvo al de siempre.', 5000);
  setTimeout(() => location.reload(), 2500);
}
if (NA_ON) {
  try {
    NA_PLUGIN.addListener('audio', e => {
      if (!e) return;
      if (e.type === 'focus') {
        const v = +e.value;
        if (v === -3) return;                                     // Android lowers the volume a moment by itself
        if (v < 0) {
          if (naOwnAudio()) { naRefocus = true; return; }        // it's Nova's voice or a video of Órbita itself
          if (current() && A < 2 && !act().paused) { naFocusPaused = v === -2; fadePause(); }
          return;
        }
        if (v === 1 && naFocusPaused) { naFocusPaused = false; if (current() && A < 2) resume(); }
        return;
      }
      if (e.type === 'noisy') { if (current() && A < 2 && !act().paused) { fadePause(); toast('Auriculares desconectados: pausa'); } return; }
      const d = decks[e.slot]; if (d && d._on) d._on(e);
    });
  } catch {}
}
/* After Nova speaks (her voice plays in the WebView), the engine takes the sound back */
function naAfterTalk() { if (NA_ON && naRefocus && current() && A < 2 && !act().paused) { naRefocus = false; NA_PLUGIN.focus().catch(() => {}); } }
/* The visualizer: the engine «listens» to what plays (no microphone) only while a visualizer is on screen */
let naVizOn = false, naVizUse = 0;
const naSpec = new Uint8Array(256), naWave = new Uint8Array(512).fill(128);
const naAnalyser = {
  fftSize: 512, frequencyBinCount: 256,
  getByteFrequencyData(a) { naVizUse = Date.now(); if (!naVizOn) { naVizOn = true; NA_PLUGIN.viz({ on: true }).catch(() => {}); } a.set(naSpec.subarray(0, a.length)); },
  getByteTimeDomainData(a) { a.set(naWave.subarray(0, a.length)); }
};
if (NA_ON) {
  try {
    NA_PLUGIN.addListener('spectrum', e => {
      try { const s = atob(e.b); for (let i = 0; i < 256 && i < s.length; i++) naSpec[i] = s.charCodeAt(i); for (let i = 0; i < 512 && 256 + i < s.length; i++) naWave[i] = s.charCodeAt(256 + i); } catch {}
    });
  } catch {}
  setInterval(() => { if (naVizOn && Date.now() - naVizUse > 1500) { naVizOn = false; NA_PLUGIN.viz({ on: false }).catch(() => {}); } }, 1000);
}
/* Songs of the same album, one after the other: glued, without a gap (and without a mix between them) */
function naGapless(t, n) {
  if (naBgQueue() && naOwn(t) && naOwn(n)) return true;
  return NA_ON && !!t && !!n && t.dev && n.dev && !t.net && !n.net && state.repeat !== 'one' && !!norm(t.album || '') && norm(t.album || '') === norm(n.album || '') && artistKey(t.artist) === artistKey(n.artist);
}
/* 3.5b2 · With Órbita in the background Android can put its web part to sleep: then nobody would start the next song.
   So, in the background, the engine gets the whole list of next songs and plays them one after another by itself
   (without fades). When the app wakes up it just catches up with what is playing. */
const naOwn = t => !!t && !!t.dev && !t.net && !!t.uri;
const naBgQueue = () => NA_ON && document.hidden && state.repeat !== 'one' && sleepLeft() === null;
function naSyncNext() {
  if (!NA_ON || A > 1) return;
  const t = current();
  if (naBgQueue() && naOwn(t)) {
    const srcs = [];
    for (let i = state.qi + 1; i < state.queue.length && srcs.length < 40; i++) { const n = trackById(state.queue[i]); if (!naOwn(n)) break; srcs.push(n.uri); }
    NA_PLUGIN.setQueue({ slot: A, srcs }).catch(() => {
      const n = trackById(state.queue[state.qi + 1]);   // an engine without the queue (3.5 beta 1): just the next one
      NA_PLUGIN.setNext({ slot: A, src: naOwn(n) ? n.uri : null }).catch(() => {});
    });
    return;
  }
  const id = state.queue[state.qi + 1], n = id && trackById(id);
  NA_PLUGIN.setNext({ slot: A, src: naGapless(t, n) ? n.uri : null }).catch(() => {});
}
function naInit() {
  if (!NA_ON) return;
  NA_PLUGIN.keepAlive({ on: true }).catch(() => {});
  document.addEventListener('visibilitychange', () => {
    // going to the background: a fade that hasn't started yet won't be done (the engine joins the songs itself)
    if (document.hidden && mixArm && !mixing) disarmMix();
    naSyncNext();
  });
  analyser = naAnalyser; freq = new Uint8Array(256);
  applyEq();
  NA_PLUGIN.status().then(s => { if (!s || !s.available) throw 0; }).catch(() => { LS.set('engineBroken', true); toast('El motor de sonido de Android no arranca en este móvil: uso el de siempre.', 5000); setTimeout(() => location.reload(), 2500); });
}
/* Ajustes › Sonido: the switch */
function renderEngine() {
  const row = $('#engineRow'); if (!row) return;
  row.hidden = !NA_PLUGIN;
  const want = LS.get('engine', 'web') === 'nativo' && !LS.get('engineBroken', false);
  setToggle($('#tEngine'), want);
  const pending = want !== NA_ON;
  $('#engineNote').innerHTML = pending
    ? `<b>Se aplica al reabrir Órbita.</b> <button class="btn small" id="engineNow" type="button">Aplicar ahora</button>`
    : NA_ON ? 'Activado: canciones del mismo disco sin hueco. Las mezclas de DJ (filtro, eco y cuadrar el ritmo) aún no van con este motor: entre canciones hace fundidos.'
    : LS.get('engineBroken', false) ? 'Se apagó solo porque falló varias veces en este móvil. Puedes volver a probarlo.'
    : 'Beta: reproduce con el decodificador del propio móvil, fuera del WebView. Canciones del mismo disco sin hueco.';
}
$('#tEngine').onclick = () => {
  const on = !(LS.get('engine', 'web') === 'nativo' && !LS.get('engineBroken', false));
  LS.set('engine', on ? 'nativo' : 'web'); LS.set('engineBroken', false);
  renderEngine(); toast(on ? 'Motor de sonido de Android: se aplica al reabrir' : 'Vuelves al motor de siempre al reabrir');
};
$('#engineRow').addEventListener('click', e => { if (e.target.id === 'engineNow') { saveSession(true); location.reload(); } });
$('#tLevel').onclick = () => { AUD.level = !AUD.level; LS.set('aLevel', AUD.level); ensureGraph(); const t = current(); if (t && A < 2) applyNorm(A, t, 0.6); renderSound(); updateQuality(); toast(AUD.level ? 'Volumen igualado entre canciones' : 'Cada canción con su volumen original'); };
$('#tDirect').onclick = () => {
  AUD.direct = !AUD.direct; LS.set('aDirect', AUD.direct); disarmMix();
  if (NA_ON) { applyEq(); const t = current(); if (t && A < 2) applyNorm(A, t, 0.3); }
  else if (G) { routeLimiter(); applyEq(); const t = current(); if (t && A < 2) applyNorm(A, t, 0.3); } else if (!AUD.direct) ensureGraph();
  renderSound(); updateQuality();
};

var NV_STOP;
function stopTalk() {
  dj.talkGen++; naOwnTs = Date.now();
  if (NV_STOP) NV_STOP();
  try { if (nativeTTS) nativeTTS.stop().catch(() => {}); else if (dj.supported) speechSynthesis.cancel(); } catch {}
  if (!clipEl.paused) clipEl.pause();
  $('#dj').classList.remove('speaking');
  if (duckF < 1) tween('duck', 1, 600);
}
const SLOT_PREF = { saludo: ['saludo','siguiente','libre'], ruleta: ['ruleta','saludo','libre'], favorita: ['favorita','siguiente','libre'], siguiente: ['siguiente','libre'], test: [] };
function chooseClip(kind) {
  if (dj.who === 'nova' || !state.clips.length) return null;
  if (dj.who === 'mix' && Math.random() < 0.5 && dj.supported) return null;
  for (const slot of (SLOT_PREF[kind] || ['siguiente','libre'])) {
    const pool = state.clips.filter(c => c.slot === slot || (slot === 'siguiente' && c.slot === 'libre'));
    if (pool.length) return pick(pool);
  }
  return null;
}
function djTalk(text, kind = 'siguiente', opts = {}) {
  if (!dj.voiceOn) { setBubble(text); return Promise.resolve(); }
  const clip = opts.forceTTS ? null : chooseClip(kind);
  if (clip) return playClip(clip, text);
  if (!dj.supported && !nvUsable()) { setBubble(text); return Promise.resolve(); }
  return speakTTS(text);
}
function sentences(text) {
  const out = []; let buf = '';
  for (let i = 0; i < text.length; i++) { const ch = text[i]; buf += ch; if (/[.!?…;]/.test(ch) && (text[i + 1] === ' ' || i === text.length - 1)) { out.push(buf.trim()); buf = ''; } }
  if (buf.trim()) out.push(buf.trim());
  const merged = []; for (const s of out) { if (merged.length && merged[merged.length - 1].length < 22) merged[merged.length - 1] += ' ' + s; else merged.push(s); }
  return merged.length ? merged : [text];
}
function speakTTS(text, voice) {
  const own = !voice && !NV.skip && nvUsable(); NV.skip = false;
  if (!dj.supported && !own) { setBubble(text); return Promise.resolve(); }
  stopTalk(); const my = dj.talkGen;
  const clean = String(text).replace(/[«»"“”]/g, '').replace(/\s·\s/g, ', ').replace(/\s+/g, ' ').trim();
  const parts = sentences(clean);
  const v = voice || dj.voice;
  if (own) return speakNeural(clean, parts, my).catch(e => {
    if (!(e && e.message === 'lenta')) NV.fails++;
    if (my !== dj.talkGen) return;
    if (!dj.supported) { setBubble(clean, dj.name); return; }
    NV.skip = true; return speakTTS(text);
  });
  if (nativeTTS) return speakNative(clean, parts, v, my);
  return new Promise(res => {
    let started = false, done = false;
    setBubble(clean, dj.name, 0);
    const fin = () => {
      if (done) return; done = true; clearTimeout(to); clearTimeout(st);
      if (my === dj.talkGen) { $('#dj').classList.remove('speaking'); tween('duck', 1, 1100); }
      bubbleFinish(); res();
    };
    const to = setTimeout(fin, 4000 + clean.length * 100 / dj.rate);
    const st = setTimeout(() => { if (!started && !speechSynthesis.speaking) fin(); }, 2500);
    try {
      try { speechSynthesis.resume(); } catch {}
      let off = 0;
      parts.forEach((p, i) => {
        const base = off; off += p.length + 1;
        const u = new SpeechSynthesisUtterance(p);
        u.lang = v?.lang || 'es-ES'; if (v) u.voice = v; u.rate = dj.rate; u.pitch = dj.pitch; u.volume = 1;
        u.onstart = () => {
          if (my !== dj.talkGen) return;
          if (!started) { started = true; $('#dj').classList.add('speaking'); if (!act().paused) tween('duck', 0.3, 380); setBubble(clean, dj.name, 15 * dj.rate); }
          bubbleReveal(base);
        };
        u.onboundary = e => { if (my === dj.talkGen && typeof e.charIndex === 'number') bubbleReveal(base + e.charIndex + (e.charLength || 0)); };
        u.onerror = fin;
        if (i === parts.length - 1) u.onend = fin;
        speechSynthesis.speak(u);
      });
    } catch { fin(); }
  });
}
/* Android's own text-to-speech engine: sentence by sentence, music ducked while it talks */
function sysVoice(v) {
  if (!v || navigator.onLine !== false || !/-network$/i.test(v.name || '')) return v;
  return (dj.all || []).find(x => x.name === v.name.replace(/-network$/i, '-local')) || v;
}
async function speakNative(clean, parts, v, my) {
  $('#dj').classList.add('speaking');
  if (!act().paused) tween('duck', 0.3, 380);
  setBubble(clean, dj.name, 15 * dj.rate);
  const vv = sysVoice(v);
  dj.nbase = 0;
  try {
    const opts = { text: clean, lang: (vv && vv.lang) || 'es-ES', rate: dj.rate, pitch: dj.pitch, volume: 1 };
    if (vv && typeof vv._idx === 'number') opts.voice = vv._idx;
    if (my === dj.talkGen) await Promise.race([nativeTTS.speak(opts), sleep(4000 + clean.length * 110 / dj.rate)]);
  } catch {}
  if (my === dj.talkGen) { $('#dj').classList.remove('speaking'); tween('duck', 1, 1100); }
  bubbleFinish();
}
const clipUrls = new Map();
function clipUrl(c) { if (!clipUrls.has(c.id)) clipUrls.set(c.id, URL.createObjectURL(c.blob)); return clipUrls.get(c.id); }
function playClip(clip, text) {
  stopTalk(); const my = dj.talkGen;
  return new Promise(res => {
    let done = false;
    const label = `Tu voz · ${SLOTS.find(s => s.id === clip.slot)?.name || 'Cuña'}`;
    setBubble(text || clip.label, label, 0);
    const end = clip.end || 0;
    const fin = () => {
      if (done) return; done = true; clearTimeout(to);
      clipEl.removeEventListener('timeupdate', tu); clipEl.onended = clipEl.onerror = clipEl.onplaying = clipEl.onloadedmetadata = null;
      if (my === dj.talkGen) { if (!clipEl.paused) clipEl.pause(); $('#dj').classList.remove('speaking'); tween('duck', 1, 1100); }
      bubbleFinish(); res();
    };
    const tu = () => { if (end && clipEl.currentTime >= end) fin(); };
    const len = Math.max(0.5, (end || clip.duration || 8) - (clip.start || 0));
    const to = setTimeout(fin, len * 1000 + 3000);
    clipEl.addEventListener('timeupdate', tu);
    clipEl.onended = fin; clipEl.onerror = fin;
    clipEl.onplaying = () => {
      if (my !== dj.talkGen) return fin();
      $('#dj').classList.add('speaking');
      if (!act().paused) tween('duck', 0.22, 300);
      setBubble(text || clip.label, label, Math.max(8, (text || clip.label).length / len));
    };
    clipEl.onloadedmetadata = () => { try { clipEl.currentTime = clip.start || 0; } catch {} clipEl.play().catch(fin); };
    applyVol(); clipEl.src = clipUrl(clip); clipEl.load();
  });
}
function nameOf(t) { return t.demo ? `${t.title}, una pista de prueba` : t.mine ? `${t.title}, cantada por ti` : (t.artist && t.artist !== 'Artista desconocido' ? `${t.title}, de ${t.artist}` : t.title); }
/* ---------- The DJ's name, personality and how often she talks ---------- */
const PERSONAS = {
  cercana: { name: 'Cercana', desc: 'Como una amiga que pincha', prompt: 'Hablas como una locutora de radio cercana y con chispa.' },
  energica: { name: 'Enérgica', desc: 'Radio de fiesta, a tope', prompt: 'Hablas como una locutora de radio de fiesta: enérgica, con ritmo y mucho entusiasmo.' },
  tranquila: { name: 'Tranquila', desc: 'Voz de radio nocturna', prompt: 'Hablas como una locutora de radio nocturna: tranquila, cálida y suave.' },
  graciosa: { name: 'Divertida', desc: 'Bromea entre canciones', prompt: 'Eres divertida y bromista, con humor blanco, sin pasarte y sin faltar a nadie.' },
  breve: { name: 'Al grano', desc: 'Dice lo justo', prompt: 'Eres muy breve y directa: dices solo lo justo.' }
};
dj.name = (LS.get('djName', 'Nova') || 'Nova').slice(0, 20);
dj.persona = PERSONAS[LS.get('djPersona', 'cercana')] ? LS.get('djPersona', 'cercana') : 'cercana';
dj.freq = LS.get('djFreq', 'siempre');
const personaPrompt = () => PERSONAS[dj.persona].prompt;
let talkCount = 0;
function djShouldTalk(t) {
  if (!dj.intros || dj.freq === 'inicio') return false;
  if (!['mix', 'radio', 'daily', 'online'].includes(state.source)) return false;
  if (t.net && t.kind !== 'radio' && t.kind !== 'yt') return false;
  talkCount++;
  return dj.freq !== 'aveces' || talkCount % 3 === 1;
}
function introFor(t) {
  if (state.intros[t.id]) return state.intros[t.id];
  if (ONLINE && Math.random() < 0.3) { const fl = factLine(t); if (fl) return fl; }
  const ctxL = contextLine(t); if (ctxL) return ctxL;
  const mem = memoryLine(t); if (mem) return mem;
  const m = getMoment(); const art = t.artist && t.artist !== 'Artista desconocido' && !t.demo && !t.mine ? t.artist : null;
  let opts;
  if (dj.persona === 'energica') {
    opts = [`¡Arriba! Llega ${nameOf(t)}.`, `¡Subimos el volumen con ${t.title}!`, `¡Esto no para! ${nameOf(t)}.`, `¡Que no decaiga! ${t.title}.`];
    if (art) opts.push(`¡${art} en ${dj.name} FM!`, `¡Con todos ustedes, ${art}!`);
  } else if (dj.persona === 'tranquila') {
    opts = [`Ahora, con calma, ${nameOf(t)}.`, `Te dejo con ${nameOf(t)}.`, `Respira. Suena ${t.title}.`, `Para este ${m.day} ${m.franja.phrase}, ${nameOf(t)}.`];
  } else if (dj.persona === 'graciosa') {
    opts = [`Atención, que viene ${t.title}. Yo que tú subía el volumen.`, `Siguiente en la lista: ${nameOf(t)}. No hace falta que me lo agradezcas.`, `Si esta no te gusta, la culpa es del algoritmo: ${t.title}.`, `Prohibido saltarse ${t.title}. Bueno, puedes, pero me dolerá.`];
    if (art) opts.push(`${art} me ha pedido que te la ponga. Mentira, pero suena bien: ${t.title}.`);
  } else if (dj.persona === 'breve') {
    opts = [`${t.title}.`, `Ahora: ${t.title}.`]; if (art) opts.push(`${art}, ${t.title}.`);
  } else {
    opts = [`Sigue ${nameOf(t)}.`, `Ahora suena ${nameOf(t)}.`, `Ojo a esta: ${t.title}.`, `Para este ${m.day} ${m.franja.phrase}, ${nameOf(t)}.`, `Subimos un poco con ${t.title}.`];
    if (art) opts.push(`Turno de ${art}.`, `${art}, con ${t.title}.`);
  }
  if (t.mine) opts.push(`Atención, que canta la estrella de esta radio: ${t.title}.`);
  if (plays(t) <= 1 && dj.persona !== 'breve') opts.push(`Esta la tenías olvidada: ${t.title}.`);
  if (liked(t)) opts.push(dj.persona === 'breve' ? `Favorita: ${t.title}.` : `Una de tus favoritas: ${t.title}.`);
  return pickFresh(opts);
}
function renderDjPersona() {
  $('#djNameIn').value = dj.name;
  $('#personaChips').innerHTML = Object.entries(PERSONAS).map(([id, p]) => `<button class="chip" data-persona="${id}" aria-pressed="${dj.persona === id}" title="${esc(p.desc)}">${esc(p.name)}</button>`).join('');
  $('#personaDesc').textContent = PERSONAS[dj.persona].desc + '.';
  $$('#freqSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.freq === dj.freq));
}
function applyDjName() {
  setWho(dj.who);
  $$('[data-djname]').forEach(el => { el.textContent = dj.name; });
}
$('#djNameForm').addEventListener('submit', e => {
  e.preventDefault();
  const v = $('#djNameIn').value.replace(/[<>]/g, '').trim().slice(0, 20) || 'Nova';
  dj.name = v; LS.set('djName', v); applyDjName(); renderDjPersona();
  speakTTS(`A partir de ahora me llamo ${v}.`);
});
$('#personaChips').addEventListener('click', e => {
  const b = e.target.closest('[data-persona]'); if (!b) return;
  dj.persona = b.dataset.persona; LS.set('djPersona', dj.persona); renderDjPersona();
  const t = current() || visibleTracks()[0];
  speakTTS(t ? introFor(t) : `Así hablo en modo ${PERSONAS[dj.persona].name.toLowerCase()}.`);
});
$('#freqSeg').addEventListener('click', e => { const b = e.target.closest('[data-freq]'); if (!b) return; dj.freq = b.dataset.freq; LS.set('djFreq', dj.freq); renderDjPersona(); });

/* ---------- Roulette + radio ---------- */
function setRadio(on, silent) { state.radio = on; $('#btnRadio').setAttribute('aria-pressed', on); if (!silent && !on) toast('Modo radio apagado'); }
async function spinRoulette() {
  const pool = visibleTracks();
  if (!pool.length) { setBubble('Tu biblioteca está vacía. Sube canciones y vuelvo a girar.'); return; }
  const btn = $('#btnRoulette'); if (btn.disabled) return; btn.disabled = true;
  const slot = $('#slot'); slot.hidden = false; slot.classList.remove('landed');
  const order = radioBatch(); const winner = trackById(order[0]);
  setBubble('Girando la ruleta…');
  if (!reduceMotion) { let delay = 35; for (let i = 0; i < 22; i++) { slot.textContent = pick(pool).title; await sleep(delay); delay *= 1.09; } }
  slot.classList.add('landed'); slot.innerHTML = `${esc(winner.title)}<small>${esc(winner.artist)}</small>`;
  haptic([12, 50, 12]);
  btn.disabled = false;
  const line = pick([`¡Ha salido ${nameOf(winner)}! Y después, lo que diga la ruleta.`, `La ruleta manda: ${nameOf(winner)}. Dejo la radio puesta.`, `Premio: ${nameOf(winner)}. Seguimos en modo radio.`]);
  playQueue(order, 0, { source: 'radio', talk: line, talkKind: 'ruleta' });
  setRadio(true, true);
}
$('#btnRoulette').onclick = spinRoulette;
$('#btnRadio').onclick = () => {
  if (state.radio && state.source === 'radio') { setRadio(false); return; }
  const order = radioBatch(); if (!order.length) { setBubble('Primero necesito canciones. Súbelas en Biblioteca.'); return; }
  const first = trackById(order[0]); const m = getMoment();
  playQueue(order, 0, { source: 'radio', talk: `Esto es Órbita FM, ${m.day} ${m.franja.phrase}. Empezamos con ${nameOf(first)}.`, talkKind: 'saludo' });
  setRadio(true, true);
};

/* ---------- Nova's memory: what you listen to, when, and what you skip ---------- */
const memOn = () => LS.get('memOn', true);
const dayKey = (ts = Date.now()) => { const d = new Date(ts); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
const artistKey = a => { const k = norm(a); return !k || k === 'artista desconocido' ? '' : k; };
function seeded(str) { let h = 2166136261; for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0) / 4294967296; }
const fmtLong = s => { s = Math.round(s || 0); if (s < 60) return `${s} s`; const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60); return h ? `${h} h${m ? ' ' + m + ' min' : ''}` : `${m} min`; };
const argmax = a => a.reduce((b, v, i) => v > a[b] ? i : b, 0);
const franjaOf = h => (FRANJAS.find(f => h >= f.from && h < f.to) || FRANJAS[0]);
const listES = a => a.length < 2 ? (a[0] || '') : a.slice(0, -1).join(', ') + ' y ' + a[a.length - 1];
async function loadMemory() {
  try {
    const all = await idbAll('events');
    const fresh = memEvents.slice();
    memEvents = all.filter(e => e && e.ts && e.id).sort((a, b) => a.ts - b.ts);
    for (const e of fresh) if (!memEvents.some(x => x.ts === e.ts && x.id === e.id)) memEvents.push(e);
    if (memEvents.length > 30000) memEvents.splice(0, memEvents.length - 25000);
  } catch {}
  /* A song that was cut off when the app closed still counts */
  const s = LS.get('session', null);
  if (s && s.listen && s.listen.acc >= 5) { const l = s.listen; s.listen = null; LS.set('session', s); recordEvent(makeEvent(l, s.ts || Date.now())); }
}
const memCache = new Map();
function onMemoryChange() { memCache.clear(); if (state.view === 'tu') renderTuSoon(); }
function memStats(days) {
  const ck = days + ':' + memEvents.length; if (memCache.has(ck)) return memCache.get(ck);
  const from = days ? Date.now() - days * 864e5 : 0;
  const tr = new Map(), ar = new Map(), hours = new Array(24).fill(0), dows = new Array(7).fill(0), dset = new Set();
  let sec = 0, plays = 0, skips = 0, n = 0, radioSec = 0, first = Date.now();
  for (let i = memEvents.length - 1; i >= 0; i--) {
    const e = memEvents[i]; if (e.ts < from) break;
    n++; sec += e.sec; hours[e.hour] += e.sec; dows[e.dow] += e.sec; dset.add(dayKey(e.ts)); first = e.ts;
    if (e.net) { radioSec += e.sec; continue; }
    const full = e.sec >= 30 || e.pct >= 0.5; if (full) plays++; if (e.skip) skips++;
    let r = tr.get(e.id); if (!r) tr.set(e.id, r = { id: e.id, title: e.title, artist: e.artist, sec: 0, plays: 0, skips: 0, last: e.ts });
    r.sec += e.sec; if (full) r.plays++; if (e.skip) r.skips++;
    const k = artistKey(e.artist);
    if (k) { let a = ar.get(k); if (!a) ar.set(k, a = { key: k, name: e.artist, sec: 0, plays: 0, songs: new Set() }); a.sec += e.sec; if (full) a.plays++; a.songs.add(e.id); }
  }
  const tracks = [...tr.values()].filter(r => r.plays > 0 || r.sec >= 10).sort((a, b) => b.plays - a.plays || b.sec - a.sec);
  const artists = [...ar.values()].sort((a, b) => b.sec - a.sec);
  const out = { n, sec, plays, skips, radioSec, tracks, artists, hours, dows, days: dset.size, first, all: [...tr.values()] };
  memCache.set(ck, out); return out;
}
function streakDays() {
  const days = new Set(memEvents.map(e => dayKey(e.ts))); const d = new Date(); let n = 0;
  if (!days.has(dayKey(d))) d.setDate(d.getDate() - 1);
  while (days.has(dayKey(d))) { n++; d.setDate(d.getDate() - 1); }
  return n;
}
function skipProne(t) { const s = t.skips || 0; return s >= 2 && s > (t.plays || 0) * 0.5; }

/* Mix of the day: your usual favourites, songs that fit this hour, and a few you rarely play */
function buildDaily(force) {
  if (!state.ready) return null;
  const key = dayKey();
  let pool = poolTracks();
  if (pool.filter(t => !t.demo).length >= 6) pool = pool.filter(t => !t.demo);
  const cached = LS.get('daily', null);
  if (!force && cached && cached.key === key && cached.ids && cached.ids.filter(id => trackById(id)).length >= Math.min(4, cached.ids.length) && Math.abs((cached.pool || 0) - pool.length) <= Math.max(3, pool.length * 0.2)) return cached;
  if (pool.length < 3) return null;
  const salt = force ? Math.random().toString(36).slice(2, 7) : '', seed = key + salt;
  const st = memStats(30), h = new Date().getHours(), now = Date.now();
  const artistRank = new Map(st.artists.slice(0, 12).map((a, i) => [a.key, 1 - i / 12]));
  const bctx = brainCtx(null);
  const rec = new Map(st.all.map(r => [r.id, r]));
  const scored = pool.map(t => {
    const r = rec.get(t.id), p = r ? r.plays : 0, sk = r ? r.skips : 0;
    let s = Math.log1p(p) * 0.9 + (liked(t) ? 1.1 : 0) + (artistRank.get(artistKey(t.artist)) || 0) * 0.8;
    s -= (sk / Math.max(1, p + sk)) * 1.6 + (skipProne(t) ? 0.6 : 0);
    if (t.hours && t.hours.length === 24) { const tot = t.hours.reduce((a, b) => a + b, 0); if (tot >= 3) s += ((t.hours[h] + 0.5 * (t.hours[(h + 23) % 24] + t.hours[(h + 1) % 24])) / tot) * 1.2; }
    const ago = t.lastPlayed ? (now - t.lastPlayed) / 864e5 : 999;
    if (ago < 1) s -= 1.6; else if (ago < 3) s -= 0.6; else if (ago > 20 && plays(t) >= 3) s += 0.5;
    s -= pSkip(t, bctx) * 1.4;
    s += seeded(seed + t.id) * 1.1;
    return { t, s, fresh: plays(t) <= 1 };
  }).sort((a, b) => b.s - a.s);
  const n = Math.min(20, Math.max(6, Math.round(pool.length * 0.35))), out = [], perArtist = new Map();
  const take = (list, k) => {
    for (const x of list) {
      if (out.length >= n || k <= 0) break;
      const a = artistKey(x.t.artist) || x.t.id;
      if (out.includes(x.t) || ((perArtist.get(a) || 0) >= 2 && pool.length > 30)) continue;
      out.push(x.t); perArtist.set(a, (perArtist.get(a) || 0) + 1); k--;
    }
  };
  take(scored.filter(x => !x.fresh), Math.round(n * 0.7)); take(scored.filter(x => x.fresh), n - out.length); take(scored, n - out.length);
  const ids = arrangeArc(out, seed).map(t => t.id);
  const m = getMoment();
  const song = songOfDay(scored, st);
  const d = { key, pool: pool.length, ids, name: `Tu mezcla del ${m.day}`, desc: memEvents.length > 20 ? 'Lo que más te gusta, lo que te pega a esta hora y alguna que casi no escuchas.' : 'Tus favoritas y canciones que aún no has escuchado mucho. Cuanto más escuches, mejor te conoceré.', greeting: '', intros: {}, song, ai: false, aiTried: false };
  LS.set('daily', d); return d;
}
/* Order by energy (tempo and loudness) in an arc: warm up, peak around two thirds, ease off */
function arrangeArc(list, key) {
  const withE = list.map(t => { const a = anaCache.get(t.id); return { t, e: a && typeof a.energy === 'number' ? a.energy : null }; });
  if (withE.filter(x => x.e !== null).length < list.length * 0.6) return withE.map(x => x.t).sort((a, b) => seeded(key + 'o' + a.id) - seeded(key + 'o' + b.id));
  const sorted = withE.map(x => ({ t: x.t, e: x.e ?? 0.5 })).sort((a, b) => a.e - b.e);
  const up = [], down = [];
  sorted.forEach((x, i) => (i % 3 === 2 ? down : up).push(x.t));
  return [...up, ...down.reverse()];
}
function songOfDay(scored, st) {
  const now = Date.now();
  const cand = scored.slice(0, 30).filter(x => !x.t.demo || scored.length < 8).filter(x => !x.t.lastPlayed || now - x.t.lastPlayed > 2 * 864e5);
  const x = cand.sort((a, b) => (b.s + (liked(b.t) ? 0.4 : 0)) - (a.s + (liked(a.t) ? 0.4 : 0)))[0] || scored[0];
  if (!x) return null;
  const t = x.t, r = st.all.find(q => q.id === t.id), ago = t.lastPlayed ? Math.round((now - t.lastPlayed) / 864e5) : null;
  const top = st.artists[0] && st.artists[0].key === artistKey(t.artist);
  const why = r && r.plays >= 5 ? `La has escuchado ${r.plays} veces este mes.`
    : liked(t) && ago && ago > 6 ? `Es de tus favoritas y hace ${ago} días que no suena.`
    : top ? `${t.artist} es lo que más escuchas últimamente.`
    : x.fresh ? 'Apenas la has escuchado: dale una oportunidad.'
    : ago && ago > 14 ? `Hace ${ago} días que no la pones.` : 'Encaja con tu día de hoy.';
  return { id: t.id, why };
}
function rediscover(nmax = 4) {
  const now = Date.now();
  return visibleTracks().filter(t => !t.net && !t.demo && plays(t) >= 4 && t.lastPlayed && now - t.lastPlayed > 21 * 864e5 && !skipProne(t))
    .sort((a, b) => plays(b) - plays(a)).slice(0, nmax);
}
function newArrivals(nmax = 4) {
  const now = Date.now();
  return visibleTracks().filter(t => !t.net && !t.demo && t.addedAt && now - t.addedAt < 14 * 864e5 && plays(t) <= 1)
    .sort((a, b) => b.addedAt - a.addedAt).slice(0, nmax);
}
async function aiDaily(d) {
  if (aiState !== 'ready' || !d || d.ai || d.aiTried) return;
  d.aiTried = true; LS.set('daily', d);
  const items = d.ids.map(trackById).filter(Boolean); if (items.length < 4) return;
  const m = getMoment(), prof = profileText();
  const prompt = `Eres ${dj.name}, la DJ de "Órbita", una app de música personal. ${personaPrompt()} Escribe en español de España.
Has preparado la mezcla del día de esta persona para este ${m.day} ${m.franja.phrase}. Canciones, en orden (id | título | artista):
${items.map(t => `${t.id} | ${t.title} | ${t.artist}`).join('\n')}
${prof ? 'Lo que sabes de sus gustos (úsalo con naturalidad, sin recitarlo): ' + prof + '\n' : ''}Si no conoces una canción, no inventes datos sobre ella. Las frases se leen en voz alta: cortas, naturales, sin emojis ni comillas.
Responde solo con JSON: {"nombre":"título de máximo 5 palabras","descripcion":"una frase","saludo":"1 o 2 frases presentando la mezcla del día y la primera canción","intros":[{"id":"...","intro":"máximo 14 palabras"}]}`;
  try {
    const r = await aiJson(prompt, { cache: false });
    const cur = LS.get('daily', null); if (!cur || cur.key !== d.key) return;
    if (r?.nombre) cur.name = String(r.nombre).slice(0, 60);
    if (r?.descripcion) cur.desc = String(r.descripcion).slice(0, 200);
    if (r?.saludo) cur.greeting = String(r.saludo).slice(0, 260);
    cur.intros = {}; for (const x of (Array.isArray(r?.intros) ? r.intros : [])) { const id = String(x?.id || ''); if (cur.ids.includes(id) && x?.intro) cur.intros[id] = String(x.intro).slice(0, 160); }
    cur.ai = true; cur.aiTried = true; LS.set('daily', cur); renderForYou();
  } catch {}
}
function playDaily(i = 0) {
  const d = buildDaily(); if (!d) return;
  const ids = d.ids.filter(id => trackById(id)); if (!ids.length) return;
  const first = trackById(ids[i]);
  const talk = i === 0 ? (d.greeting || `${d.name}. Empezamos con ${nameOf(first)}.`) : null;
  playQueue(ids, i, { source: 'daily', intros: d.intros || {}, talk, talkKind: 'saludo' });
}
function profileText() {
  if (memEvents.length < 5) return '';
  const st = memStats(30), out = [];
  if (st.artists.length) out.push('Artistas que más escucha: ' + st.artists.slice(0, 6).map(a => a.name).join(', ') + '.');
  if (st.tracks.length) out.push('Canciones que más pone últimamente: ' + st.tracks.slice(0, 6).map(r => `${r.title} (${r.artist})`).join('; ') + '.');
  if (st.sec > 900) out.push(`Suele escuchar música ${franjaOf(argmax(st.hours)).phrase}.`);
  const sk = memStats(0).all.filter(r => r.skips >= 2 && r.skips >= r.plays).slice(0, 5);
  if (sk.length) out.push('Suele saltarse: ' + sk.map(r => r.title).join('; ') + '.');
  return out.join(' ');
}
function memoryLine(t) {
  if (memEvents.length < 10 || Math.random() > 0.35) return null;
  const st = memStats(30), r = st.all.find(x => x.id === t.id), opts = [];
  if (st.tracks[0] && st.tracks[0].id === t.id && st.tracks[0].plays >= 4) opts.push(`Tu canción más escuchada del mes: ${t.title}.`);
  else if (r && r.plays >= 5) opts.push(`Esta ya la has puesto ${r.plays} veces este mes: ${t.title}.`);
  if (t.prevPlayed && Date.now() - t.prevPlayed > 30 * 864e5 && plays(t) >= 3) opts.push(`Hacía mucho que no sonaba ${nameOf(t)}.`);
  if (st.artists[0] && st.artists[0].key === artistKey(t.artist) && st.artists[0].plays >= 5) opts.push(`De tu artista del mes, ${t.artist}: ${t.title}.`);
  return opts.length ? pick(opts) : null;
}
function memFacts() {
  const st = memStats(30), all = memStats(0), f = [];
  if (!all.n) return f;
  if (st.artists[0]) f.push(`Tu artista del mes es ${st.artists[0].name}${st.artists[1] ? `, seguido de ${st.artists[1].name}` : ''}.`);
  if (st.tracks[0] && st.tracks[0].plays >= 2) f.push(`La canción que más has puesto este mes es «${st.tracks[0].title}» (${st.tracks[0].plays} veces).`);
  if (st.sec > 600) { const ph = argmax(st.hours); f.push(`Escuchas más música ${franjaOf(ph).phrase}, sobre las ${ph}:00.`); }
  if (st.sec > 1800) { const pd = argmax(st.dows); f.push(`Tu día con más música es el ${DAYS[pd]}.`); }
  const sk = all.all.filter(r => r.skips >= 2 && r.skips >= r.plays).slice(0, 3);
  if (sk.length) f.push(`Sueles saltarte ${listES(sk.map(r => '«' + r.title + '»'))}, así que te las pongo menos.`);
  const likes = state.tracks.filter(t => !t.demo && liked(t)).length;
  if (likes) f.push(`Tienes ${likes} ${likes === 1 ? 'favorita' : 'favoritas'}: salen más a menudo en tus mezclas y en la radio.`);
  if (all.radioSec > 300) f.push(`Has escuchado ${fmtLong(all.radioSec)} de radio por internet.`);
  if (BRAIN.n >= 25) f.push(`He aprendido de ${BRAIN.n} canciones que escuchaste enteras o saltaste: ya sé mejor qué ponerte y a qué hora.`);
  const tr = [...transitions().entries()].filter(([, v]) => v >= 2).sort((a, b) => b[1] - a[1])[0];
  if (tr) { const [x, y] = tr[0].split('>').map(trackById); if (x && y) f.push(`Te gusta mucho escuchar «${y.title}» justo después de «${x.title}».`); }
  return f;
}

/* ---------- "Para ti hoy" on the home screen ---------- */
function coversGrid(list) { const c = list.slice(0, 4); while (c.length < 4) c.push(null); return `<div class="fy-covers" aria-hidden="true">${c.map(t => coverHTML(t)).join('')}</div>`; }
function renderForYou() {
  const box = $('#fyOut'); if (!box) return;
  const cards = [];
  if (!state.ready) { $('#forYou').hidden = true; return; }
  const s = LS.get('session', null), cont = s && s.q && trackById(s.q[s.i]);
  if (cont && !state.playedOnce) cards.push(`<article class="fy-card fy-cont">${coverHTML(cont, 'xl')}<div class="fy-body"><div class="eyebrow">Continúa donde lo dejaste</div><h3>${esc(cont.title)}</h3><p class="t-sub">${esc(cont.artist)}${s.pos ? ' · en el ' + fmt(s.pos) : ''}</p><div class="fy-btns"><button class="btn small primary" data-act="resume-session">${ICON.playS}Seguir escuchando</button></div></div></article>`);
  const d = buildDaily();
  if (d) {
    const items = d.ids.map(trackById).filter(Boolean), total = items.reduce((a, t) => a + (t.duration || 0), 0);
    cards.push(`<article class="fy-card fy-daily">${coversGrid(items)}<div class="fy-body"><div class="eyebrow">${d.ai ? `Mezcla del día · por ${esc(dj.name)}` : 'Mezcla del día'} · ${items.length} canciones · ${fmtLong(total)}</div><h3>${esc(d.name)}</h3><p class="note">${esc(d.desc)}</p><div class="fy-btns"><button class="btn small primary" data-act="daily-play">${ICON.playS}Escuchar</button><button class="btn small" data-act="daily-save">Guardar lista</button><button class="btn small" data-act="daily-new" aria-label="Hacer otra mezcla del día">Otra</button></div></div></article>`);
    const so = d.song && trackById(d.song.id);
    if (so) cards.push(`<article class="fy-card">${coverHTML(so, 'xl')}<div class="fy-body"><div class="eyebrow">Canción del día</div><h3>${esc(so.title)}</h3><p class="t-sub">${esc(so.artist)}</p><p class="note">${esc(d.song.why)}</p><div class="fy-btns"><button class="btn small primary" data-act="sotd-play" data-id="${so.id}">${ICON.playS}Escuchar</button></div></div></article>`);
    if (!d.ai && !d.aiTried && aiState === 'ready') idle(() => aiDaily(d));
  }
  const red = rediscover(), nw = newArrivals();
  const mini = (title, list, act) => `<article class="fy-card fy-list"><div class="fy-body"><div class="eyebrow">${title}</div><ul class="fy-mini">${list.map(t => `<li><button data-act="${act}" data-id="${t.id}">${coverHTML(t)}<span class="row-text"><span class="t-title">${esc(t.title)}</span><span class="t-sub">${esc(t.artist)}</span></span></button></li>`).join('')}</ul></div></article>`;
  if (red.length) cards.push(mini('Redescubre', red, 'fy-play-red'));
  if (nw.length) cards.push(mini('Recién llegadas', nw, 'fy-play-new'));
  $('#forYou').hidden = !cards.length;
  box.innerHTML = cards.join('');
  $('#fyDate').textContent = cap(getMoment().day);
  markPlaying();
}

/* ---------- "Tú": your listening summary ---------- */
let tuRange = LS.get('tuRange', 30), tuT = null;
const renderTuSoon = () => { clearTimeout(tuT); tuT = setTimeout(renderTu, 600); };
function barsChart(vals, labels, { valueFmt = fmtLong, tickEvery = 1, bw = 16, gap = 4, maxW = 560, name = '' } = {}) {
  const n = vals.length, max = Math.max(...vals), W = n * (bw + gap) - gap, base = 96, top = 6, hi = max > 0 ? argmax(vals) : -1;
  let bars = '', hits = '';
  vals.forEach((v, i) => {
    const x = i * (bw + gap), h = v > 0 ? Math.max(3, (v / max) * (base - top)) : 0, y = base - h, r = Math.min(4, h / 2, bw / 2);
    if (h > 0) bars += `<path class="${i === hi ? 'hi' : ''}" d="M${x} ${base}V${(y + r).toFixed(1)}Q${x} ${y.toFixed(1)} ${x + r} ${y.toFixed(1)}H${x + bw - r}Q${x + bw} ${y.toFixed(1)} ${x + bw} ${(y + r).toFixed(1)}V${base}Z"/>`;
    hits += `<rect x="${x - gap / 2}" y="0" width="${bw + gap}" height="${base}" data-i="${i}" data-l="${esc(labels[i])}" data-v="${esc(valueFmt(v))}" tabindex="0" aria-label="${esc(labels[i])}: ${esc(valueFmt(v))}"/>`;
  });
  const xl = labels.map((l, i) => `<span style="left:${((i * (bw + gap) + bw / 2) / W * 100).toFixed(2)}%">${i % tickEvery === 0 ? esc(l) : ''}</span>`).join('');
  const peak = hi >= 0 ? `<span class="bars-peak" style="left:${((hi * (bw + gap) + bw / 2) / W * 100).toFixed(2)}%">${esc(valueFmt(vals[hi]))}</span>` : '';
  const table = `<details class="chart-table"><summary>Ver los datos</summary><table><thead><tr><th>${esc(name)}</th><th>Tiempo</th></tr></thead><tbody>${vals.map((v, i) => `<tr><td>${esc(labels[i])}</td><td>${esc(valueFmt(v))}</td></tr>`).join('')}</tbody></table></details>`;
  return `<div class="bars-wrap" style="max-width:${maxW}px"><div class="bars-plot">${peak}<svg class="bars" viewBox="0 0 ${W} ${base}" role="group" aria-label="${esc(name)}"><line x1="0" x2="${W}" y1="${base - 0.5}" y2="${base - 0.5}"/>${bars}<g class="hits">${hits}</g></svg><div class="chart-tip" hidden></div></div><div class="bars-x">${xl}</div>${table}</div>`;
}
function renderTu() {
  const out = $('#tuOut'); if (!out) return;
  $$('#tuRange button').forEach(b => b.setAttribute('aria-pressed', +b.dataset.range === tuRange));
  const st = memStats(tuRange), lbl = tuRange === 7 ? 'esta semana' : tuRange === 30 ? 'este mes' : 'desde que usas Órbita';
  $('#tuMeta').textContent = memEvents.length ? `${memStats(0).n} escuchas guardadas` : '';
  setToggle($('#tMem'), memOn());
  const facts = memFacts();
  $('#memFacts').innerHTML = facts.length ? facts.map(f => `<li>${esc(f)}</li>`).join('') : `<li class="note">Todavía no sé nada de ti. Escucha unas cuantas canciones y empezaré a conocer tus gustos.</li>`;
  $('#memName').textContent = dj.name; renderPrefsSoon();
  const [an, tot] = analysedShare(), bans = [...BRAIN.ban].map(trackById).filter(Boolean);
  $('#brainOut').innerHTML = (tot ? `<p class="note">${an >= tot ? `Conozco cómo suenan tus ${tot} canciones (ritmo, energía y compases).` : `Conozco cómo suenan ${an} de tus ${tot} canciones. Las demás las analizo poco a poco mientras usas la app, y más rápido si el móvil está cargando.`}</p>` : '')
    + (bans.length ? `<div class="eyebrow">No me las pongas más</div><ul class="banned">${bans.map(t => `<li>${coverHTML(t)}<span class="row-text"><span class="t-title">${esc(t.title)}</span><span class="t-sub">${esc(t.artist)}</span></span><button class="btn small" data-act="unban" data-id="${t.id}">Volver a ponerla</button></li>`).join('')}</ul>` : '');
  if (!st.n) { out.innerHTML = `<div class="empty"><h3>Aún no hay nada ${esc(lbl)}</h3><p>Cada canción que escuchas entera o a medias queda aquí: cuánto tiempo, a qué hora y qué te saltas. Con eso ${esc(dj.name)} prepara tu mezcla del día.</p><button class="btn" data-goto="inicio">Ir a Ahora</button></div>`; return; }
  const streak = streakDays();
  const hero = `<section class="panel glow tu-hero"><div class="eyebrow">Has escuchado ${esc(lbl)}</div><div class="tu-big">${esc(fmtLong(st.sec))}</div><div class="tu-stats"><div><b>${st.plays}</b><span>canciones escuchadas</span></div><div><b>${st.artists.length}</b><span>${st.artists.length === 1 ? 'artista' : 'artistas'}</span></div><div><b>${st.days}</b><span>${st.days === 1 ? 'día con música' : 'días con música'}</span></div>${streak > 1 ? `<div><b>${streak}</b><span>días seguidos</span></div>` : ''}</div></section>`;
  const topT = st.tracks.slice(0, 5).map((r, i) => { const t = trackById(r.id); return `<li class="tu-row"${t ? ` data-track="${t.id}"` : ''}><span class="tu-n">${i + 1}</span>${coverHTML(t || null)}<span class="row-text"><span class="t-title">${esc(r.title)}</span><span class="t-sub">${esc(r.artist)}</span></span><span class="mono tu-v">${r.plays ? `${r.plays} ${r.plays === 1 ? 'vez' : 'veces'}` : esc(fmtLong(r.sec))}</span>${t ? `<button class="icon-btn" data-act="tu-play" data-id="${t.id}" aria-label="Escuchar ${esc(r.title)}">${ICON.playS}</button>` : ''}</li>`; }).join('');
  const amax = st.artists[0] ? st.artists[0].sec : 1;
  const topA = st.artists.slice(0, 5).map((a, i) => `<li class="tu-art"><span class="tu-n">${i + 1}</span><span class="tu-an">${esc(a.name)}</span><span class="tu-bar" aria-hidden="true"><i style="width:${Math.max(3, a.sec / amax * 100).toFixed(1)}%"></i></span><span class="mono tu-v">${esc(fmtLong(a.sec))}</span></li>`).join('');
  const hrs = Array.from({ length: 24 }, (_, i) => `${i}:00`);
  const dOrder = [1, 2, 3, 4, 5, 6, 0], dNames = ['Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'];
  const ph = argmax(st.hours), pd = argmax(st.dows);
  const sk = st.all.filter(r => r.skips >= 2).sort((a, b) => b.skips - a.skips).slice(0, 4);
  out.innerHTML = recapCardHTML() + hero
    + `<div class="tu-grid"><section class="panel"><div class="stack"><h2>Tus canciones</h2>${topT ? `<ul class="tu-list">${topT}</ul>` : '<p class="note">Aún no hay canciones aquí.</p>'}</div></section>`
    + `<section class="panel"><div class="stack"><h2>Tus artistas</h2>${topA ? `<ul class="tu-list">${topA}</ul>` : '<p class="note">Sin artistas todavía.</p>'}</div></section></div>`
    + `<section class="panel"><div class="stack"><div class="row-wrap"><h2>Cuándo escuchas</h2><span class="spacer"></span><span class="note">Más ${esc(franjaOf(ph).phrase)}, sobre las ${ph}:00</span></div>${barsChart(st.hours, hrs, { tickEvery: 6, name: 'Hora' })}</div></section>`
    + `<section class="panel"><div class="stack"><div class="row-wrap"><h2>Tu semana</h2><span class="spacer"></span><span class="note">Tu día: ${esc(DAYS[pd])}</span></div>${barsChart(dOrder.map(i => st.dows[i]), dNames.map(n => n.slice(0, 3)), { bw: 16, gap: 22, maxW: 380, name: 'Día' })}</div></section>`
    + (sk.length ? `<section class="panel"><div class="stack"><h2>Las que te saltas</h2><p class="note">${esc(dj.name)} las pone menos en tus mezclas y en la radio. Si te vuelven a gustar, márcalas como favoritas.</p><ul class="tu-list">${sk.map(r => { const t = trackById(r.id); return `<li class="tu-row">${coverHTML(t || null)}<span class="row-text"><span class="t-title">${esc(r.title)}</span><span class="t-sub">${esc(r.artist)}</span></span><span class="mono tu-v">${r.skips} ${r.skips === 1 ? 'salto' : 'saltos'}</span></li>`; }).join('')}</ul></div></section>` : '');
  markPlaying();
}
/* ===== 3.5 · Tu año en Órbita: tu año en tarjetas, contado por la DJ, con una imagen para compartir ===== */
const MESES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
const NOVA_SRC = new Set(['daily', 'mix', 'ai', 'nova', 'radio']);
const dayIdx = ts => { const d = new Date(ts); return Math.round(new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() / 864e5); };
const fmtDia = ts => { const d = new Date(ts); return `${d.getDate()} de ${MESES[d.getMonth()]}`; };
const nES = n => Math.round(n).toLocaleString('es-ES');
function rcHours(s) {
  if (s >= 36000) return [nES(s / 3600), 'horas'];
  if (s >= 3600) { const h = Math.round(s / 360) / 10; return [h.toLocaleString('es-ES'), h === 1 ? 'hora' : 'horas']; }
  const m = Math.max(1, Math.round(s / 60)); return [String(m), m === 1 ? 'minuto' : 'minutos'];
}
const rcHoursTxt = s => rcHours(s).join(' ');
const spokenDur = s => { const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60); return h ? `${h} ${h === 1 ? 'hora' : 'horas'}${m ? ` y ${m} minutos` : ''}` : `${Math.max(1, m)} minutos`; };
function recapStats(y) {
  const key = 'rc:' + y + ':' + memEvents.length; if (memCache.has(key)) return memCache.get(key);
  const a = new Date(y, 0, 1).getTime(), b = new Date(y + 1, 0, 1).getTime();
  const tr = new Map(), ar = new Map(), months = new Array(12).fill(0), hours = new Array(24).fill(0), dows = new Array(7).fill(0), days = new Map(), perDay = new Map();
  let sec = 0, plays = 0, radioSec = 0, novaSec = 0, first = null;
  for (const e of memEvents) {
    if (e.ts < a || e.ts >= b) continue;
    sec += e.sec; months[new Date(e.ts).getMonth()] += e.sec; hours[e.hour] += e.sec; dows[e.dow] += e.sec;
    const di = dayIdx(e.ts); let dd = days.get(di); if (!dd) days.set(di, dd = { sec: 0, ts: e.ts }); dd.sec += e.sec;
    if (NOVA_SRC.has(e.src)) novaSec += e.sec;
    const yt = String(e.id).startsWith('yt:');
    if (e.net && !yt) { radioSec += e.sec; continue; }
    const full = e.sec >= 30 || e.pct >= 0.5; if (full) plays++;
    if (!first && full) first = e;
    let r = tr.get(e.id); if (!r) tr.set(e.id, r = { id: e.id, title: e.title, artist: e.artist, sec: 0, plays: 0 });
    r.sec += e.sec;
    if (full) { r.plays++; const k = di + '|' + e.id; let pd = perDay.get(k); if (!pd) perDay.set(k, pd = { id: e.id, ts: e.ts, n: 0 }); pd.n++; }
    const ak = artistKey(e.artist);
    if (ak) { let x = ar.get(ak); if (!x) ar.set(ak, x = { key: ak, name: e.artist, sec: 0, plays: 0, songs: new Set() }); x.sec += e.sec; if (full) x.plays++; x.songs.add(e.id); }
  }
  const tracks = [...tr.values()].filter(r => r.plays > 0).sort((p, q) => q.plays - p.plays || q.sec - p.sec);
  const artists = [...ar.values()].filter(x => x.plays > 0).sort((p, q) => q.sec - p.sec);
  // the longest run of days in a row, and the day with most music
  const keys = [...days.keys()].sort((p, q) => p - q); let best = { n: 0, from: 0, to: 0 }, run = 0, start = 0;
  keys.forEach((k, i) => { if (i && k === keys[i - 1] + 1) run++; else { run = 1; start = k; } if (run > best.n) best = { n: run, from: days.get(start).ts, to: days.get(k).ts }; });
  let bigDay = null; for (const d of days.values()) if (!bigDay || d.sec > bigDay.sec) bigDay = d;
  let obs = null; for (const pd of perDay.values()) if (pd.n >= 3 && (!obs || pd.n > obs.n)) obs = pd;
  const out = { y, sec, plays, radioSec, novaSec, tracks, artists, months, hours, dows, nDays: days.size, nSongs: tracks.length, nArtists: artists.length, streak: best, bigDay, obs, first, ok: plays >= 10 && sec >= 1800 };
  memCache.set(key, out); return out;
}
/* Which year to show: this one, or last year during the first weeks of January */
function recapYear() {
  const d = new Date(), y = d.getFullYear();
  return d.getMonth() === 0 && d.getDate() <= 20 && recapStats(y - 1).ok ? y - 1 : y;
}
function recapType(s) {
  const tot = s.sec || 1, sum = (f, t) => s.hours.slice(f, t).reduce((p, q) => p + q, 0);
  const night = (sum(0, 5) + s.hours[23]) / tot, morning = sum(6, 10) / tot, top = s.artists[0] ? s.artists[0].sec / tot : 0;
  if (night > 0.3) return ['Alma nocturna', 'Tu música suena mejor cuando el resto duerme.'];
  if (morning > 0.3) return ['Sol de mañana', 'Empiezas el día con banda sonora.'];
  if (top > 0.35) return ['Corazón fiel', `Cuando algo te gusta, no lo sueltas. Y ${s.artists[0].name} lo sabe.`];
  if (s.nArtists >= 15 && s.nArtists / Math.max(1, s.plays) > 0.4) return ['Espíritu explorador', 'Siempre a la caza de algo nuevo.'];
  if (s.radioSec / tot > 0.35) return ['Alma de radio', 'Lo tuyo es dejarte sorprender por las emisoras.'];
  if (s.novaSec / tot > 0.5) return ['En buenas manos', `Dejaste que ${dj.name} eligiera más de la mitad de lo que sonó.`];
  return ['Todoterreno', 'Un poco de todo, a cualquier hora. Así da gusto pinchar.'];
}
const rcEquiv = sec => sec >= 86400 * 1.5 ? `Eso son ${(Math.round(sec / 8640) / 10).toLocaleString('es-ES')} días enteros sin parar.` : sec >= 86400 ? 'Eso es más de un día entero sin parar.' : sec >= 7200 ? `Como ver ${nES(sec / 6600)} películas seguidas.` : 'Y esto no ha hecho más que empezar.';
const rcArtistOk = a => a && a !== 'Artista desconocido';
const rcSong = (r, mid) => rcArtistOk(r.artist) ? `«${r.title}», de ${r.artist}${mid ? ',' : ''}` : `«${r.title}»`;
const rcVeces = n => n === 1 ? 'una vez' : `${nES(n)} veces`;
function rcCover(r, cls = '') {
  const t = trackById(r.id), u = t && coverUrl(t, 'full');
  return u ? `<span class="rc-cover ${cls}"><img src="${u}" alt="" decoding="async"></span>` : `<span class="rc-cover ${cls}"><span class="cover-ph" style="--h:${t ? phHue(t) : hashHue((r.artist || '') + r.title)}">${esc(((r.title || '♪').trim().charAt(0) || '♪').toUpperCase())}</span></span>`;
}
function recapSlides(s) {
  const y = s.y, out = [], [hn, hu] = rcHours(s.sec), now = new Date();
  out.push({ k: 'hola', h: 0, html: `<div class="eyebrow">Tu ${y}</div><h2 class="rc-h">Tu año en Órbita</h2><p class="rc-p">${esc(dj.name)} ha estado tomando nota. ¿Vemos lo que ha sonado?</p>`,
    say: `Hola. He estado tomando nota de todo lo que has escuchado en ${y}. ¿Vemos tu año?` });
  out.push({ k: 'tiempo', h: 40, html: `<div class="eyebrow">Has escuchado</div><div class="rc-big">${esc(hn)}<small>${esc(hu)} de música</small></div><p class="rc-p">${esc(rcEquiv(s.sec))}</p><div class="rc-pills"><span><b>${nES(s.plays)}</b> canciones</span><span><b>${nES(s.nSongs)}</b> distintas</span><span><b>${nES(s.nArtists)}</b> ${s.nArtists === 1 ? 'artista' : 'artistas'}</span></div>`,
    say: `Has escuchado ${hn} ${hu} de música. ${rcEquiv(s.sec)}` });
  const t1 = s.tracks[0];
  if (t1) out.push({ k: 'cancion', h: 90, html: `${rcCover(t1, 'big')}<div class="eyebrow">Tu canción del año</div><h2 class="rc-h rc-title">${esc(t1.title)}</h2>${rcArtistOk(t1.artist) ? `<p class="rc-sub">${esc(t1.artist)}</p>` : ''}<p class="rc-p"><b>${esc(rcVeces(t1.plays))}</b>${trackById(t1.id) ? ` · <button class="btn small" data-rc="play-top">Ponla</button>` : ''}</p>`,
    say: `Tu canción del año es ${rcSong(t1)}. La pusiste ${rcVeces(t1.plays)}.` });
  if (s.tracks.length >= 3) {
    const top = s.tracks.slice(0, 5);
    out.push({ k: 'top5', h: 140, html: `<div class="eyebrow">Tu top ${top.length}</div><ol class="rc-list">${top.map((r, i) => `<li>${rcCover(r)}<span class="rc-n">${i + 1}</span><span class="rc-li"><b>${esc(r.title)}</b><span>${esc(rcArtistOk(r.artist) ? r.artist : '')}</span></span><span class="rc-x">${nES(r.plays)}</span></li>`).join('')}</ol>`,
      say: `Detrás vienen ${rcSong(s.tracks[1], true)} y ${rcSong(s.tracks[2])}.` });
  }
  const a1 = s.artists.find(a => rcArtistOk(a.name));
  if (a1) {
    const rest = s.artists.filter(a => a !== a1 && rcArtistOk(a.name)).slice(0, 4), max = a1.sec || 1;
    out.push({ k: 'artista', h: 200, html: `<div class="eyebrow">Tu artista del año</div><h2 class="rc-h rc-title">${esc(a1.name)}</h2><p class="rc-p">${esc(rcHoursTxt(a1.sec))} con su música · ${nES(a1.songs.size)} ${a1.songs.size === 1 ? 'canción' : 'canciones'}</p>${rest.length ? `<ol class="rc-bars-list" start="2">${rest.map(a => `<li><span>${esc(a.name)}</span><i style="width:${Math.max(6, a.sec / max * 100).toFixed(1)}%"></i></li>`).join('')}</ol>` : ''}`,
      say: `Tu artista del año es ${a1.name}. Le dedicaste ${rcHoursTxt(a1.sec)}.` });
  }
  const pm = argmax(s.months), ph = argmax(s.hours), pd = argmax(s.dows), fr = franjaOf(ph).phrase, mmax = Math.max(...s.months) || 1;
  out.push({ k: 'cuando', h: 260, html: `<div class="eyebrow">Cuándo suena tu música</div><div class="rc-facts"><div><span>Tu mes</span><b>${esc(cap(MESES[pm]))}</b></div><div><span>Tu momento</span><b>${esc(cap(fr))}</b><small>sobre las ${ph}:00</small></div><div><span>Tu día</span><b>${esc(cap(DAYS[pd]))}</b></div></div><div class="rc-months" aria-hidden="true">${s.months.map((v, i) => `<i class="${i === pm ? 'on' : ''}" style="height:${Math.max(4, v / mmax * 100).toFixed(1)}%"><em>${MESES[i].charAt(0).toUpperCase()}</em></i>`).join('')}</div>`,
    say: `Tu mes fue ${MESES[pm]}. Escuchas sobre todo ${fr}, y tu día es el ${DAYS[pd]}.` });
  const rec = [], recSay = [];
  if (s.streak.n >= 2) { rec.push(`<div><span>Tu mejor racha</span><b>${nES(s.streak.n)} días seguidos</b><small>del ${esc(fmtDia(s.streak.from))} al ${esc(fmtDia(s.streak.to))}</small></div>`); recSay.push(`Tu mejor racha fue de ${s.streak.n} días seguidos con música.`); }
  if (s.bigDay && s.bigDay.sec >= 1800) { rec.push(`<div><span>Tu gran día</span><b>${esc(cap(fmtDia(s.bigDay.ts)))}</b><small>${esc(fmtLong(s.bigDay.sec))} de música</small></div>`); recSay.push(`Y el ${fmtDia(s.bigDay.ts)} fue tu gran día: ${spokenDur(s.bigDay.sec)} de música.`); }
  if (s.obs) { const r = s.tracks.find(x => x.id === s.obs.id); if (r) { rec.push(`<div><span>En bucle</span><b>«${esc(r.title)}»</b><small>${nES(s.obs.n)} veces el ${esc(fmtDia(s.obs.ts))}</small></div>`); recSay.push(`El ${fmtDia(s.obs.ts)} pusiste ${rcSong(r, true)} ${s.obs.n} veces. Te entiendo.`); } }
  if (rec.length) out.push({ k: 'records', h: 310, html: `<div class="eyebrow">Tus récords</div><div class="rc-facts">${rec.join('')}</div>`, say: recSay.join(' ') });
  if (s.novaSec / (s.sec || 1) >= 0.1) {
    const pct = Math.round(s.novaSec / s.sec * 100);
    out.push({ k: 'nova', h: 330, html: `<div class="eyebrow">Tu DJ</div><div class="rc-big">${pct}<small>% lo eligió ${esc(dj.name)}</small></div><p class="rc-p">${esc(rcHoursTxt(s.novaSec))} de mezclas, radio y tu mezcla del día.${s.radioSec >= 600 ? ` Y ${esc(rcHoursTxt(s.radioSec))} de emisoras.` : ''}</p>`,
      say: `Una cosa más: el ${pct} por ciento de lo que sonó lo elegí yo. Me lo pasé genial pinchando para ti.` });
  }
  const [tl, td] = recapType(s);
  out.push({ k: 'tipo', h: 20, html: `<div class="eyebrow">Este año has sido</div><h2 class="rc-h rc-grad">${esc(tl)}</h2><p class="rc-p">${esc(td)}</p>`, say: `Este año has sido… ${tl}. ${td}` });
  const later = y === now.getFullYear() && now.getMonth() < 11;
  out.push({ k: 'fin', h: 0, last: true, html: `<div class="rc-card-wrap"><div class="rc-card-ph" id="rcCardBox"><span class="note">Preparando tu imagen…</span></div></div><div class="rc-btns"><button class="btn primary" data-rc="share">Compartir</button><button class="btn" data-rc="save">Guardar imagen</button></div><div class="rc-btns"><button class="btn small" data-rc="listen">Escuchar tu ${y}</button><button class="btn small" data-rc="close">Cerrar</button></div>`,
    say: later ? 'Gracias por dejarme ser tu DJ. Y lo que queda de año, a seguir sumando.' : `Gracias por dejarme ser tu DJ. ¡A por un ${y + 1} con más música!` });
  return out;
}

const RC = { s: null, slides: [], i: 0, gen: 0, t0: 0, dur: 0, spoken: true, paused: false, pausedAt: 0, raf: 0, voice: LS.get('recapVoice', true), img: null, started: false };
const rcVoiceOn = () => RC.voice && dj.voiceOn;
function recapCardHTML() {
  const y = recapYear(), s = recapStats(y); if (!s.ok) return '';
  const prev = recapStats(y - 1);
  return `<section class="panel glow rc-entry"><div class="rc-entry-t"><div class="eyebrow">Resumen del año</div><h2>Tu ${y} en Órbita</h2><p class="note">${esc(rcHoursTxt(s.sec))} de música, tu canción y tu artista del año, tus récords… contado por ${esc(dj.name)}.</p></div><div class="rc-entry-b"><button class="btn primary" data-rc-open="${y}">Ver tu año</button>${prev.ok ? `<button class="btn small" data-rc-open="${y - 1}">Ver ${y - 1}</button>` : ''}</div></section>`;
}
function openRecap(y) {
  y = y || recapYear(); const s = recapStats(y);
  if (!s.ok) { novaSay(`Todavía no tengo bastante para tu resumen de ${y}. Escucha un poco más y te lo preparo.`); return; }
  if (!$('#chat').hidden) closeChat();
  RC.s = s; RC.slides = recapSlides(s); RC.img = null; RC.paused = false; RC.started = false;
  $('#rcBars').innerHTML = RC.slides.map(() => '<i><b></b></i>').join('');
  setToggleVoice(); $('#recap').hidden = false; document.body.classList.add('rc-open');
  if (STANDALONE) { try { history.pushState({ recap: 1 }, '', location.hash || '#' + state.view); } catch {} }
  LS.set('recapSeen', y); recapHint();
  // music under the recap: if nothing is playing, your songs of the year
  const playing = current() && !act().paused;
  if (!playing) { const ids = s.tracks.map(r => r.id).filter(id => trackById(id)).slice(0, 30); if (ids.length) { RC.started = true; playQueue(ids, 0, { source: 'library' }); } }
  setTimeout(() => rcShow(0), RC.started ? 700 : 150);
  cancelAnimationFrame(RC.raf); RC.raf = requestAnimationFrame(rcTick);
}
function closeRecap(fromPop) {
  if ($('#recap').hidden) return;
  $('#recap').hidden = true; document.body.classList.remove('rc-open'); RC.gen++; cancelAnimationFrame(RC.raf); stopTalk();
  if (STANDALONE && !fromPop && history.state && history.state.recap) { try { popSkip = true; history.back(); } catch {} }
  if (state.view === 'tu') renderTu();
}
function setToggleVoice() { const b = $('#rcVoice'); b.setAttribute('aria-pressed', String(rcVoiceOn())); b.disabled = !dj.voiceOn; b.title = dj.voiceOn ? (rcVoiceOn() ? 'Silenciar a ' + dj.name : 'Que hable ' + dj.name) : 'La voz de la DJ está apagada en Ajustes'; }
function rcShow(i) {
  if ($('#recap').hidden) return;
  i = Math.max(0, Math.min(RC.slides.length - 1, i)); RC.i = i; const g = ++RC.gen, sl = RC.slides[i];
  const st = $('#rcStage'); st.innerHTML = `<div class="rc-slide rc-${sl.k}">${sl.html}</div>`;
  $('#recap').style.setProperty('--rc-h', sl.h);
  $$('#rcBars i').forEach((b, k) => { b.firstChild.style.width = k < i ? '100%' : '0%'; });
  RC.t0 = performance.now(); RC.pausedAt = RC.paused ? RC.t0 : 0; RC.dur = Math.min(16000, Math.max(6500, 1500 + (sl.say || '').length * 62));
  stopTalk(); RC.spoken = true;
  if (rcVoiceOn() && sl.say) {
    RC.spoken = false; RC.say0 = performance.now();
    Promise.resolve(djTalk(sl.say, 'test', { forceTTS: true })).catch(() => {}).then(() => { if (g === RC.gen) RC.spoken = true; });
  } else setBubble(sl.say || '');
  if (sl.k === 'fin') rcPrepareCard(g);
}
function rcTick(now) {
  if ($('#recap').hidden) return;
  RC.raf = requestAnimationFrame(rcTick);
  const sl = RC.slides[RC.i]; if (!sl || RC.paused) return;
  const f = Math.min(1, (now - RC.t0) / RC.dur), bar = $$('#rcBars i')[RC.i];
  if (bar) bar.firstChild.style.width = (sl.last ? 100 : f * 100).toFixed(2) + '%';
  // never stuck on a voice that doesn't finish: 12 s after the time it should have taken, go on
  if (!RC.spoken && now - RC.t0 > RC.dur + 12000) RC.spoken = true;
  if (f >= 1 && RC.spoken && !sl.last) rcShow(RC.i + 1);
}
function rcPause(on) {
  if (RC.paused === on) return; RC.paused = on; const now = performance.now();
  if (on) RC.pausedAt = now; else { RC.t0 += now - (RC.pausedAt || now); RC.pausedAt = 0; }
  $('#recap').classList.toggle('paused', on);
}
/* The shareable picture: 1080 × 1920, the size of a story */
function rcRound(c, x, y, w, h, r) { c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r); c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); }
function rcFit(c, text, maxW) {
  text = String(text || ''); if (c.measureText(text).width <= maxW) return text;
  let lo = 0, hi = text.length; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (c.measureText(text.slice(0, m) + '…').width <= maxW) lo = m; else hi = m - 1; }
  return text.slice(0, lo).trimEnd() + '…';
}
function rcWrap(c, text, maxW, lines) {
  const words = String(text || '').split(/\s+/).filter(Boolean), out = []; let cur = '';
  for (let i = 0; i < words.length; i++) {
    const tryL = cur ? cur + ' ' + words[i] : words[i];
    if (c.measureText(tryL).width <= maxW || !cur) cur = tryL;
    else { out.push(cur); cur = words[i]; if (out.length === lines - 1) { cur = words.slice(i).join(' '); break; } }
  }
  if (cur) out.push(cur);
  return out.slice(0, lines).map((l, i, a) => i === a.length - 1 ? rcFit(c, l, maxW) : l);
}
async function recapImage(s) {
  const W = 1080, H = 1920, cv = document.createElement('canvas'); cv.width = W; cv.height = H; const c = cv.getContext('2d');
  const root = getComputedStyle(document.documentElement), hue = parseFloat(root.getPropertyValue('--accent-h')) || 6, sat = Math.max(0.2, parseFloat(root.getPropertyValue('--sat')) || 1);
  const A1 = `hsl(${hue},${96 * sat}%,64%)`, A2 = `hsl(${hue - 45},${92 * sat}%,66%)`, A3 = `hsl(${hue + 150},${85 * sat}%,58%)`;
  await Promise.all(['900 150px Unbounded', '700 52px Unbounded', '800 40px Figtree', '600 36px Figtree', '600 26px "IBM Plex Mono"'].map(f => document.fonts.load(f).catch(() => {})));
  c.fillStyle = '#06050b'; c.fillRect(0, 0, W, H);
  const glow = (x, y, r, col) => { const g = c.createRadialGradient(x, y, 0, x, y, r); g.addColorStop(0, col); g.addColorStop(1, 'rgba(6,5,11,0)'); c.fillStyle = g; c.fillRect(0, 0, W, H); };
  c.globalAlpha = 0.55; glow(980, 180, 820, A1); c.globalAlpha = 0.4; glow(60, 1500, 900, A2); c.globalAlpha = 0.22; glow(900, 1700, 600, A3); c.globalAlpha = 1;
  const M = 84, grad = (x0, x1) => { const g = c.createLinearGradient(x0, 0, x1, 0); g.addColorStop(0, A1); g.addColorStop(1, A2); return g; };
  const ls = v => { try { c.letterSpacing = v; } catch {} };
  c.textBaseline = 'alphabetic';
  c.fillStyle = 'rgba(247,242,255,.72)'; c.font = '600 28px "IBM Plex Mono"'; ls('6px'); c.fillText('ÓRBITA · TU MÚSICA, TU DJ', M, 150); ls('0px');
  c.font = '900 168px Unbounded'; c.fillStyle = grad(M, M + 800); c.fillText(`Mi ${s.y}`, M - 6, 330);
  c.fillStyle = '#f7f2ff'; c.font = '700 56px Figtree'; c.fillText('en Órbita', M, 408);
  // time
  const [hn, hu] = rcHours(s.sec);
  c.font = '900 200px Unbounded'; c.fillStyle = '#f7f2ff'; c.fillText(hn, M - 8, 650);
  const hw = c.measureText(hn).width; c.font = '800 50px Figtree'; c.fillStyle = A1;
  const unitX = M + hw + 26; if (unitX + c.measureText(hu).width < W - M) { c.fillText(hu, unitX, 586); c.fillStyle = 'rgba(247,242,255,.8)'; c.fillText('de música', unitX, 646); }
  else { c.fillText(`${hu} de música`, M, 720); }
  c.font = '600 38px Figtree'; c.fillStyle = 'rgba(247,242,255,.66)'; c.fillText(`${nES(s.plays)} canciones · ${nES(s.nArtists)} ${s.nArtists === 1 ? 'artista' : 'artistas'} · ${nES(s.nDays)} días con música`, M, 770);
  // song of the year
  const t1 = s.tracks[0];
  if (t1) {
    const y0 = 830, h0 = 300; rcRound(c, M, y0, W - 2 * M, h0, 36); c.fillStyle = 'rgba(255,255,255,.07)'; c.fill(); c.strokeStyle = 'rgba(255,255,255,.12)'; c.lineWidth = 2; c.stroke();
    const cs = 236, cx = M + 32, cy = y0 + 32, t = trackById(t1.id), u = t && coverUrl(t, 'full');
    c.save(); rcRound(c, cx, cy, cs, cs, 26); c.clip();
    let drew = false;
    if (u) { try { const im = new Image(); im.src = u; await im.decode(); const sc = Math.max(cs / im.naturalWidth, cs / im.naturalHeight), w = im.naturalWidth * sc, h = im.naturalHeight * sc; c.drawImage(im, cx + (cs - w) / 2, cy + (cs - h) / 2, w, h); drew = true; } catch {} }
    if (!drew) { const ph = t ? phHue(t) : hashHue((t1.artist || '') + t1.title), g = c.createLinearGradient(cx, cy, cx + cs, cy + cs); g.addColorStop(0, `hsl(${ph},70%,55%)`); g.addColorStop(1, `hsl(${ph + 50},70%,30%)`); c.fillStyle = g; c.fillRect(cx, cy, cs, cs); c.fillStyle = 'rgba(255,255,255,.9)'; c.font = '900 120px Unbounded'; c.textAlign = 'center'; c.fillText(((t1.title || '♪').trim().charAt(0) || '♪').toUpperCase(), cx + cs / 2, cy + cs / 2 + 44); c.textAlign = 'left'; }
    c.restore();
    const tx = cx + cs + 36, tw = W - M - 32 - tx;
    c.font = '600 26px "IBM Plex Mono"'; ls('4px'); c.fillStyle = A1; c.fillText('CANCIÓN DEL AÑO', tx, cy + 34); ls('0px');
    c.font = '700 50px Unbounded'; c.fillStyle = '#f7f2ff'; const tl = rcWrap(c, t1.title, tw, 2); tl.forEach((l, i) => c.fillText(l, tx, cy + 104 + i * 62));
    let yy = cy + 104 + tl.length * 62;
    if (rcArtistOk(t1.artist)) { c.font = '600 36px Figtree'; c.fillStyle = 'rgba(247,242,255,.72)'; c.fillText(rcFit(c, t1.artist, tw), tx, yy); yy += 52; }
    c.font = '800 34px Figtree'; c.fillStyle = A2; c.fillText(rcVeces(t1.plays), tx, Math.min(yy, cy + cs - 4));
  }
  // top 5 and artists
  const colW = (W - 2 * M - 40) / 2, y1 = 1210;
  const col = (x, head, rows) => {
    c.font = '600 26px "IBM Plex Mono"'; ls('4px'); c.fillStyle = 'rgba(247,242,255,.6)'; c.fillText(head, x, y1); ls('0px');
    rows.forEach((r, i) => { const yy = y1 + 74 + i * 66; c.font = '900 34px Unbounded'; c.fillStyle = i ? 'rgba(247,242,255,.5)' : A1; c.fillText(String(i + 1), x, yy); c.font = '700 36px Figtree'; c.fillStyle = '#f7f2ff'; c.fillText(rcFit(c, r, colW - 56), x + 52, yy); });
  };
  col(M, 'MIS CANCIONES', s.tracks.slice(0, 5).map(r => r.title));
  col(M + colW + 40, 'MIS ARTISTAS', s.artists.filter(a => rcArtistOk(a.name)).slice(0, 5).map(a => a.name));
  // what kind of listener
  const [tl] = recapType(s), y2 = 1600;
  rcRound(c, M, y2, W - 2 * M, 170, 36); c.fillStyle = 'rgba(255,255,255,.07)'; c.fill();
  c.font = '600 26px "IBM Plex Mono"'; ls('4px'); c.fillStyle = 'rgba(247,242,255,.6)'; c.fillText('ESTE AÑO HE SIDO', M + 36, y2 + 56); ls('0px');
  c.font = '900 62px Unbounded'; c.fillStyle = grad(M + 36, M + 700); c.fillText(rcFit(c, tl, W - 2 * M - 72), M + 32, y2 + 130);
  const pd = argmax(s.dows), ph = argmax(s.hours);
  c.font = '600 32px Figtree'; c.fillStyle = 'rgba(247,242,255,.7)';
  c.fillText(rcFit(c, [cap(franjaOf(ph).phrase), 'los ' + DAYS[pd] + (/s$/.test(DAYS[pd]) ? '' : 's'), s.streak.n >= 2 ? `racha de ${s.streak.n} días` : ''].filter(Boolean).join(' · '), W - 2 * M), M, 1830);
  c.font = '600 24px "IBM Plex Mono"'; c.fillStyle = 'rgba(247,242,255,.45)'; ls('3px'); c.fillText(`DJ: ${dj.name.toUpperCase()}`, M, 1880); ls('0px');
  return cv;
}
async function rcPrepareCard(g) {
  try {
    if (!RC.img) { const cv = await recapImage(RC.s); RC.img = await new Promise(res => cv.toBlob(b => res(b), 'image/jpeg', 0.92)); RC.imgUrl && URL.revokeObjectURL(RC.imgUrl); RC.imgUrl = URL.createObjectURL(RC.img); }
    if (g !== RC.gen) return; const box = $('#rcCardBox'); if (box) box.outerHTML = `<img class="rc-card" id="rcCardBox" src="${RC.imgUrl}" alt="Tu ${RC.s.y} en Órbita: tu tiempo, tu canción, tu top 5 y tus artistas">`;
  } catch (e) { const box = $('#rcCardBox'); if (box && g === RC.gen) box.innerHTML = '<span class="note">No he podido dibujar la imagen.</span>'; }
}
const blobB64 = b => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(',')[1] || ''); r.onerror = rej; r.readAsDataURL(b); });
function webDownload(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; document.body.appendChild(a); a.click(); setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000); }
async function rcShare(save) {
  if (!RC.img) await rcPrepareCard(RC.gen); if (!RC.img) return;
  const s = RC.s, name = `orbita-${s.y}.jpg`, t1 = s.tracks[0];
  const text = `Mi ${s.y} en Órbita: ${rcHoursTxt(s.sec)} de música${t1 ? `. Mi canción del año: «${t1.title}»${rcArtistOk(t1.artist) ? `, de ${t1.artist}` : ''}` : ''}.`;
  rcPause(true);
  try {
    if (nativeLib) {
      const data = await blobB64(RC.img);
      if (save) { const r = await nativeLib.saveImage({ name, data, mime: 'image/jpeg' }); toast(`Imagen guardada en ${(r && r.where) || 'la galería'}`, 4200); }
      else await nativeLib.shareFile({ name, data, mime: 'image/jpeg', text, title: `Tu ${s.y} en Órbita` });
      return;
    }
    const file = new File([RC.img], name, { type: 'image/jpeg' });
    if (!save && navigator.canShare && navigator.canShare({ files: [file] })) { await navigator.share({ files: [file], text }); return; }
    webDownload(RC.img, name); toast(save ? 'Imagen descargada' : 'Imagen descargada: compártela desde tus descargas', 3800);
  } catch (e) {
    if (e && e.name === 'AbortError') return;
    toast(/not implemented|no implementado|UNIMPLEMENTED/i.test(String(e && (e.code || e.message))) ? 'Esta versión de la app no puede compartir imágenes' : (save ? 'No he podido guardar la imagen' : 'No he podido compartir la imagen'), 3800);
  }
}
$('#recap').addEventListener('click', e => {
  const b = e.target.closest('[data-rc]');
  if (b) {
    const a = b.dataset.rc;
    if (a === 'close') closeRecap();
    else if (a === 'share') rcShare(false);
    else if (a === 'save') rcShare(true);
    else if (a === 'listen') { const ids = RC.s.tracks.map(r => r.id).filter(id => trackById(id)).slice(0, 50); if (!ids.length) { toast('Esas canciones ya no están en tu biblioteca'); return; }
      const name = `Tu ${RC.s.y}`, old = state.playlists.find(p => p.name === name && p.source === 'recap'), p = old || { id: uid('p'), name, createdAt: Date.now(), source: 'recap' };
      Object.assign(p, { description: `Las ${ids.length} canciones que más escuchaste en ${RC.s.y}.`, trackIds: ids }); if (!old) state.playlists.push(p); idbPut('playlists', p); renderLists();
      closeRecap(); playQueue(ids, 0, { source: 'library' }); toast(`Suena «${name}» · guardada en Mis listas`, 3600); }
    else if (a === 'play-top') { const id = RC.s.tracks[0] && RC.s.tracks[0].id; if (id && trackById(id)) { const t = current(); if (!t || t.id !== id) playQueue([id, ...RC.s.tracks.slice(1, 30).map(r => r.id).filter(x => trackById(x))], 0, { source: 'library' }); else if (act().paused) togglePlay(); } }
    return;
  }
});
(() => {
  let downAt = 0, held = false, ht = 0;
  const zone = $('#rcTap');
  zone.addEventListener('pointerdown', () => { downAt = performance.now(); held = false; clearTimeout(ht); ht = setTimeout(() => { held = true; rcPause(true); }, 280); });
  const up = e => {
    clearTimeout(ht); if (!downAt) return; downAt = 0;
    if (held) { held = false; rcPause(false); return; }
    const r = zone.getBoundingClientRect(), x = (e.clientX - r.left) / r.width;
    rcPause(false); const ni = RC.i + (x < 0.3 ? -1 : 1);
    if (ni < RC.slides.length) rcShow(ni);
  };
  zone.addEventListener('pointerup', up);
  zone.addEventListener('pointercancel', () => { clearTimeout(ht); downAt = 0; if (held) { held = false; rcPause(false); } });
  zone.addEventListener('contextmenu', e => e.preventDefault());
})();
$('#rcClose').onclick = () => closeRecap();
$('#rcVoice').onclick = () => { RC.voice = !RC.voice; LS.set('recapVoice', RC.voice); setToggleVoice(); if (!rcVoiceOn()) { stopTalk(); RC.spoken = true; } };
document.addEventListener('click', e => { const b = e.target.closest('[data-rc-open]'); if (b) openRecap(+b.dataset.rcOpen || 0); });
document.addEventListener('keydown', e => {
  if ($('#recap').hidden) return;
  if (e.key === 'ArrowRight') { e.preventDefault(); e.stopImmediatePropagation(); rcShow(RC.i + 1); }
  else if (e.key === 'ArrowLeft') { e.preventDefault(); e.stopImmediatePropagation(); rcShow(RC.i - 1); }
  else if (e.key === 'Escape') { e.preventDefault(); e.stopImmediatePropagation(); closeRecap(); }
  else if (e.code === 'Space' && !(e.target.closest && e.target.closest('button'))) { e.preventDefault(); e.stopImmediatePropagation(); rcPause(!RC.paused); }
}, true);
document.addEventListener('visibilitychange', () => { if (document.hidden && !$('#recap').hidden) rcPause(true); });
/* In December (and early January), once a year: «your recap is ready» */
function recapHint() {
  const d = new Date(), y = recapYear(), s = recapStats(y);
  const season = d.getMonth() === 11 || (d.getMonth() === 0 && d.getDate() <= 20);
  const box = $('#rcHint'); if (!box) return;
  box.hidden = !(season && s.ok && LS.get('recapSeen', 0) !== y && LS.get('recapHintOff', 0) !== y);
  if (!box.hidden) $('#rcHintText').textContent = `${rcHoursTxt(s.sec)} de música, tu canción del año y mucho más. ${dj.name} te lo cuenta.`;
  if (!box.hidden) $('#rcHintHead').textContent = `Tu ${y} en Órbita ya está listo`;
}
$('#rcHintLater').onclick = () => { LS.set('recapHintOff', recapYear()); recapHint(); };
/* ===== 3.5 · Primera vez fácil: la bienvenida para quien acaba de instalar Órbita ===== */
const OB = { fresh: false, step: 0, genres: new Set(), energy: '', gen: 0, again: false, scanning: false };
const OB_STEPS = ['hola', 'musica', 'gustos', 'dj', 'listo'];
const OB_AV = '<div class="dj-avatar onb-av" aria-hidden="true"><span class="ring"></span><span class="core"></span><span class="bars"><i></i><i></i><i></i><i></i><i></i></span></div>';
/* Someone who has just installed the app: nothing heard, nothing saved, never seen the news of a version */
function obIsFresh() { return !LS.get('onboarded', false) && !LS.get('newsSeen', '') && !memEvents.length && !state.tracks.some(t => !t.demo); }
function openOnboarding(again) {
  OB.step = 0; OB.again = !!again; OB.genres = new Set(prefList().filter(p => p.kind === 'boost' && p.target && p.target.type === 'genre' && /^Bienvenida/.test(p.said || '')).map(p => p.target.value)); OB.energy = '';
  if (!$('#chat').hidden) closeChat();
  LS.set('devWelcomeDone', true); try { renderDevice(); } catch {}
  // the news of this version are for people who update, not for someone who has just arrived
  const mm = String(APP_CFG.version || '').split('.').slice(0, 2).join('.'); if (mm && !again) LS.set('newsSeen', mm);
  $('#onb').hidden = false; document.body.classList.add('rc-open');
  if (STANDALONE) { try { history.pushState({ onb: 1 }, '', location.hash || '#' + state.view); } catch {} }
  obShow(0);
}
function closeOnboarding(fromPop) {
  if ($('#onb').hidden) return;
  $('#onb').hidden = true; document.body.classList.remove('rc-open'); OB.gen++; LS.set('onboarded', true); stopTalk();
  if (STANDALONE && !fromPop && history.state && history.state.onb) { try { popSkip = true; history.back(); } catch {} }
}
function obCount() {
  const dev = state.tracks.filter(t => t.dev), arts = new Set(dev.map(t => artistKey(t.artist)).filter(Boolean));
  return { songs: dev.length, artists: arts.size };
}
function obMusicResult() {
  const st = devLib.state, c = obCount();
  if (OB.scanning) return '<p class="onb-res"><span class="spin" aria-hidden="true"></span><span>Buscando tu música…</span></p>';
  if (st === 'granted') return c.songs ? `<p class="onb-res ok"><span>He encontrado <b>${nES(c.songs)} ${c.songs === 1 ? 'canción' : 'canciones'}</b> de ${nES(c.artists)} ${c.artists === 1 ? 'artista' : 'artistas'}.</span></p>`
    : '<p class="onb-res"><span>No he encontrado canciones en el móvil. Puedes añadirlas desde Biblioteca, y mientras tanto escuchar la radio o vídeos de YouTube.</span></p>';
  if (st === 'denied') return '<p class="onb-res"><span>Sin permiso no puedo ver tu música. Puedes dármelo cuando quieras en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio.</span></p>';
  return '';
}
function obGenres() {
  const n = new Map(); for (const t of state.tracks) if (t.dev) for (const g of trackGenres(t)) n.set(g, (n.get(g) || 0) + 1);
  return GENRES.map(([g], i) => [g, n.get(g) || 0, i]).sort((a, b) => b[1] - a[1] || a[2] - b[2]).map(x => x[0]);
}
function obStepHTML(k) {
  const of = i => `<div class="eyebrow">Paso ${i} de 4</div>`, next = (label = 'Siguiente') => `<button class="btn primary big" data-ob="next">${label}</button>`;
  if (k === 'hola') return `${OB_AV}<div class="eyebrow">Bienvenida</div><h2 class="rc-h" id="onbHead">Hola, soy ${esc(dj.name)}</h2><p class="rc-p">Soy tu DJ. Pongo tu música, la mezclo sin cortes y te hablo entre canciones, como en la radio. En un minuto lo dejamos listo.</p><div class="onb-btns">${next('Empezar')}</div>`;
  if (k === 'musica') {
    const head = `${of(1)}<h2 class="rc-h" id="onbHead">Tu música</h2>`;
    if (!nativeLib) return `${head}<p class="rc-p">Añade tus canciones desde Biblioteca: arrástralas aquí o elígelas de una carpeta. Mientras tanto tienes unas pistas de prueba y la radio.</p><div class="onb-btns">${next()}</div>`;
    const st = devLib.state;
    return `${head}<p class="rc-p">Pongo las canciones que ya tienes en el móvil, directamente: sin copiarlas ni ocupar espacio. Para verlas, Android te pedirá permiso.</p><div id="onbMusic">${obMusicResult()}</div><div class="onb-btns">${st === 'granted' || st === 'denied' ? next() : `<button class="btn primary big" data-ob="perm"${OB.scanning ? ' disabled' : ''}>Dar acceso a mi música</button><button class="btn" data-ob="next">Ahora no</button>`}</div>`;
  }
  if (k === 'gustos') return `${of(2)}<h2 class="rc-h" id="onbHead">¿Qué te gusta?</h2><p class="rc-p">Elige los estilos que más escuchas. Los tendré en cuenta en tus mezclas y en la radio.</p><div class="onb-chips" role="group" aria-label="Estilos que te gustan">${obGenres().map(g => `<button type="button" class="chip" data-ob-g="${esc(g)}" aria-pressed="${OB.genres.has(g)}">${esc(cap(g))}</button>`).join('')}</div><div class="onb-row"><span class="lbl">¿Y cómo te gusta?</span><div class="seg" role="group" aria-label="Energía"><button type="button" data-ob-e="calma" aria-pressed="${OB.energy === 'calma'}">Tranquila</button><button type="button" data-ob-e="" aria-pressed="${OB.energy === ''}">De todo</button><button type="button" data-ob-e="energia" aria-pressed="${OB.energy === 'energia'}">Con energía</button></div></div><div class="onb-btns">${next()}</div>`;
  if (k === 'dj') {
    const f = !dj.intros ? 'nunca' : dj.freq, c = LS.get('city', null);
    return `${of(3)}<h2 class="rc-h" id="onbHead">Tu DJ</h2><label class="onb-field"><span>¿Cómo quieres llamarme?</span><input id="onbName" maxlength="20" value="${esc(dj.name)}" autocomplete="off" enterkeyhint="next"></label><div class="onb-row"><span class="lbl">¿Cuánto hablo entre canciones?</span><div class="seg" role="group" aria-label="Cuánto habla la DJ">${[['siempre', 'En cada una'], ['aveces', 'A veces'], ['inicio', 'Al empezar'], ['nunca', 'Nada']].map(([v, l]) => `<button type="button" data-ob-f="${v}" aria-pressed="${f === v}">${l}</button>`).join('')}</div></div><label class="onb-field"><span>Tu ciudad <small>(opcional, para decirte qué tiempo hace)</small></span><input id="onbCity" placeholder="${c ? esc(c.name) : 'Por ejemplo, Sevilla'}" autocomplete="address-level2" enterkeyhint="done"></label><div class="onb-btns">${next()}</div>`;
  }
  const c = obCount(), gs = [...OB.genres].slice(0, 3);
  const what = (c.songs ? `Tengo tus ${nES(c.songs)} canciones` : 'Todo preparado') + (gs.length ? ` y sé que te ${gs.length === 1 ? 'gusta' : 'gustan'} ${listJoin(gs)}.` : '.');
  const canMix = poolTracks().length >= 2;
  return `${OB_AV}${of(4)}<h2 class="rc-h" id="onbHead">Todo listo</h2><p class="rc-p">${esc(what)}</p><ul class="onb-tips"><li>Pídeme lo que quieras: «ponme algo para correr», «música para cenar».</li><li>Gira la ruleta si no sabes qué escuchar.</li><li>En «Tú» verás lo que escuchas y, a final de año, tu resumen.</li></ul><div class="onb-btns">${canMix ? '<button class="btn primary big" data-ob="mix">Ponme mi primera mezcla</button>' : '<button class="btn primary big" data-ob="lib">Añadir mi música</button>'}<button class="btn" data-ob="done">Explorar la app</button></div>`;
}
const listJoin = a => a.length < 2 ? (a[0] || '') : a.slice(0, -1).join(', ') + ' y ' + a[a.length - 1];
function obShow(i, quiet) {
  OB.step = Math.max(0, Math.min(OB_STEPS.length - 1, i)); const k = OB_STEPS[OB.step], g = ++OB.gen;
  $('#onbStage').innerHTML = `<div class="onb-step onb-${k}">${obStepHTML(k)}</div>`;
  $('#onbDots').innerHTML = OB_STEPS.map((_, n) => `<i class="${n === OB.step ? 'on' : n < OB.step ? 'done' : ''}"></i>`).join('');
  $('#onb').style.setProperty('--rc-h', [0, 60, 150, 230, 310][OB.step]);
  const f = $('#onbStage [data-ob="next"], #onbStage [data-ob="perm"], #onbStage [data-ob="mix"], #onbStage [data-ob="lib"]'); if (f) setTimeout(() => { if (g === OB.gen) try { f.focus({ preventScroll: true }); } catch {} }, 60);
  if (quiet) return;
  const say = { hola: `¡Hola! Soy ${dj.name}, tu DJ. En un minuto lo dejamos todo listo.`, musica: nativeLib ? 'Primero, tu música. Dame permiso y la busco en tu móvil, sin copiar nada.' : 'Primero, tu música. Añádela desde Biblioteca cuando quieras.', gustos: '¿Qué estilos te gustan? Elige los que quieras.', dj: '¿Cómo quieres que me llame? ¿Y cuánto hablo entre canciones?', listo: 'Todo listo. ¿Te pongo tu primera mezcla?' }[k];
  stopTalk(); if (dj.voiceOn) djTalk(say, 'test', { forceTTS: true }); else setBubble(say);
}
async function obPerm() {
  if (OB.scanning) return; OB.scanning = true; obShow(OB.step, true);
  try { await connectDevice(); } catch {}
  OB.scanning = false; if ($('#onb').hidden || OB_STEPS[OB.step] !== 'musica') return;
  obShow(OB.step, true);
  const c = obCount(), st = devLib.state;
  const line = st === 'granted' ? (c.songs ? `¡Genial! He encontrado ${c.songs} canciones.` : 'No he encontrado canciones en el móvil, pero no pasa nada.') : 'Vale, sin tu música por ahora. Puedes darme permiso más tarde.';
  stopTalk(); if (dj.voiceOn) djTalk(line, 'test', { forceTTS: true });
}
function obSaveTastes() {
  const keep = prefList().filter(p => !/^Bienvenida/.test(p.said || ''));
  const add = [...OB.genres].map((g, i) => ({ id: 'p' + Date.now() + i, kind: 'boost', target: { type: 'genre', value: g, name: g }, when: null, said: `Bienvenida: me gusta ${g}` }));
  savePrefs(keep.concat(add));
}
function obSaveDj() {
  const n = (($('#onbName') || {}).value || '').replace(/[<>]/g, '').trim().slice(0, 20);
  if (n && n !== dj.name) { dj.name = n; LS.set('djName', n); applyDjName(); try { renderDjPersona(); } catch {} }
  const city = (($('#onbCity') || {}).value || '').trim();
  if (city && ONLINE) obCity(city);
}
async function obCity(name) {
  try {
    const j = await netJSON(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(name)}&count=1&language=es&format=json`, { timeout: 9000 });
    const r = j && j.results && j.results[0]; if (!r) { toast(`No encuentro «${name}». Puedes ponerla luego en Ajustes.`, 3600); return; }
    LS.set('city', { name: r.name, lat: r.latitude, lon: r.longitude, country: r.country || '' }); LS.set('wx', null);
    try { renderCity(); } catch {} loadWeather(true);
  } catch {}
}
function obSetFreq(v) {
  if (v === 'nunca') { dj.intros = false; LS.set('intros', false); }
  else { dj.intros = true; LS.set('intros', true); dj.freq = v; LS.set('djFreq', v); }
  try { setToggle($('#tIntros'), dj.intros); renderDjPersona(); } catch {}
}
function obFirstMix() {
  const gs = [...OB.genres].slice(0, 3);
  const req = (gs.length ? listJoin(gs) : 'una mezcla variada para empezar') + (OB.energy === 'calma' ? ', tranquila' : OB.energy === 'energia' ? ', con energía' : '');
  closeOnboarding(); setView('inicio');
  setTimeout(() => mixFromChat(req), 250);
}
$('#onb').addEventListener('click', e => {
  const g = e.target.closest('[data-ob-g]');
  if (g) { const v = g.dataset.obG; OB.genres.has(v) ? OB.genres.delete(v) : OB.genres.add(v); g.setAttribute('aria-pressed', String(OB.genres.has(v))); return; }
  const en = e.target.closest('[data-ob-e]');
  if (en) { OB.energy = en.dataset.obE; $$('#onbStage [data-ob-e]').forEach(x => x.setAttribute('aria-pressed', String(x === en))); return; }
  const fq = e.target.closest('[data-ob-f]');
  if (fq) { obSetFreq(fq.dataset.obF); $$('#onbStage [data-ob-f]').forEach(x => x.setAttribute('aria-pressed', String(x === fq))); return; }
  const b = e.target.closest('[data-ob]'); if (!b) return;
  const a = b.dataset.ob, k = OB_STEPS[OB.step];
  if (a === 'next') { if (k === 'gustos') obSaveTastes(); if (k === 'dj') obSaveDj(); obShow(OB.step + 1); }
  else if (a === 'perm') obPerm();
  else if (a === 'mix') obFirstMix();
  else if (a === 'lib') { closeOnboarding(); setView('biblioteca'); }
  else if (a === 'done') { closeOnboarding(); setView('inicio'); }
});
$('#onb').addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.matches && e.target.matches('#onbName, #onbCity')) { e.preventDefault(); if (e.target.id === 'onbName') $('#onbCity').focus(); else $('#onbStage [data-ob="next"]').click(); } });
$('#onbSkip').onclick = () => { if (OB_STEPS[OB.step] === 'gustos') obSaveTastes(); if (OB_STEPS[OB.step] === 'dj') obSaveDj(); closeOnboarding(); };
$('#obAgain').onclick = () => openOnboarding(true);
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$('#onb').hidden) { e.preventDefault(); e.stopImmediatePropagation(); closeOnboarding(); } }, true);
/* ===== 3.5 · Despertador con Nova: tu música subiendo poco a poco, la hora y el tiempo, a pantalla completa ===== */
const AL_PLUGIN = capPlugin('NovaAlarm');
const AL_DAYS = [[1, 'L', 'lunes'], [2, 'M', 'martes'], [3, 'X', 'miércoles'], [4, 'J', 'jueves'], [5, 'V', 'viernes'], [6, 'S', 'sábado'], [0, 'D', 'domingo']];
const AL_MUSIC = [['suave', 'Suave para despertar'], ['daily', 'Tu mezcla del día'], ['favs', 'Tus favoritas'], ['radio', 'Modo radio']];
const ALM = {
  cfg: Object.assign({ on: false, h: 7, m: 30, days: [1, 2, 3, 4, 5], vol: 60, snooze: 10, music: 'suave', talk: true }, LS.get('alarm', {})),
  st: null, ring: 0, kind: '', active: false, test: false, acked: false, timers: [], ivs: [], clockIv: 0
};
const pad2 = n => String(n).padStart(2, '0');
const alHM = (h, m) => `${h}:${pad2(m)}`;
/* «las siete y media», «la una en punto», «las ocho menos cuarto» */
function horaHablada(d = new Date()) {
  let h = d.getHours(), m = d.getMinutes(), menos = false;
  if (m > 35) { h = (h + 1) % 24; m = 60 - m; menos = true; }
  const h12 = h % 12 || 12, art = h12 === 1 ? 'la una' : `las ${h12}`;
  const tail = m === 0 ? ' en punto' : m === 15 ? (menos ? ' menos cuarto' : ' y cuarto') : m === 30 ? ' y media' : menos ? ` menos ${m}` : ` y ${m}`;
  return { verb: h12 === 1 ? 'Es' : 'Son', text: art + tail };
}
/* When it will ring next (the same rule as the phone's side) */
function alarmNext(cfg = ALM.cfg, now = Date.now()) {
  if (!cfg.on) return -1;
  const d = new Date(now);
  for (let i = 0; i < 9; i++) {
    const c = new Date(d.getFullYear(), d.getMonth(), d.getDate() + i, cfg.h, cfg.m, 0, 0);
    if (c.getTime() > now + 1000 && (!cfg.days.length || cfg.days.includes(c.getDay()))) return c.getTime();
  }
  return -1;
}
function alarmWhen(ts) {
  if (ts <= 0) return '';
  const d = new Date(ts), now = new Date(), day = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - new Date(now.getFullYear(), now.getMonth(), now.getDate())) / 864e5);
  const dia = day === 0 ? 'hoy' : day === 1 ? 'mañana' : day < 7 ? `el ${DAYS[d.getDay()]}` : `el ${d.getDate()} de ${MESES[d.getMonth()]}`;
  const left = ts - Date.now(), h = Math.floor(left / 3600e3), mi = Math.round((left % 3600e3) / 60000);
  return `${dia} a las ${alHM(d.getHours(), d.getMinutes())}${left < 24 * 3600e3 ? ` (dentro de ${h ? h + ' h ' : ''}${mi} min)` : ''}`;
}
function alarmDaysText(days) {
  const s = [...days].sort((a, b) => ((a + 6) % 7) - ((b + 6) % 7));
  if (!s.length) return 'Solo una vez';
  if (s.length === 7) return 'Todos los días';
  if (s.join() === '1,2,3,4,5') return 'De lunes a viernes';
  if (s.join() === '6,0') return 'Los fines de semana';
  return 'Los ' + listJoin(s.map(d => AL_DAYS.find(x => x[0] === d)[2]));
}
function alarmSave(quiet) {
  LS.set('alarm', ALM.cfg); renderAlarm();
  if (!AL_PLUGIN) return Promise.resolve(null);
  const c = ALM.cfg;
  return AL_PLUGIN.set({ on: c.on, hour: c.h, minute: c.m, days: c.days, snoozeMin: c.snooze, volume: Math.round(c.vol) })
    .then(st => { ALM.st = st; renderAlarm(); if (!quiet && c.on) alarmCheckPerms(true); return st; })
    .catch(() => { if (!quiet) toast('No he podido programar el despertador'); return null; });
}
async function alarmCheckPerms(ask) {
  if (!AL_PLUGIN) return;
  try {
    let st = await AL_PLUGIN.get();
    if (ask && !st.notif) { st = await AL_PLUGIN.requestNotif().catch(() => st); }
    ALM.st = st; renderAlarm();
  } catch {}
}
function renderAlarm() {
  const c = ALM.cfg, st = ALM.st, next = c.on ? (st && st.on && st.next > 0 ? st.next : alarmNext(c)) : -1;
  const pill = $('#alarmPill');
  if (pill) { pill.hidden = !c.on || next <= 0; if (!pill.hidden) { const d = new Date(next); $('#alarmPillTxt').textContent = alHM(d.getHours(), d.getMinutes()); pill.setAttribute('aria-label', 'Despertador: ' + alarmWhen(next)); } }
  if (!$('#alarmPanel')) return;
  setToggle($('#tAlarm'), c.on); setToggle($('#tAlarmTalk'), c.talk);
  const tv = alHM(pad2(c.h), c.m); if ($('#alarmTime').value !== tv && document.activeElement !== $('#alarmTime')) $('#alarmTime').value = tv;
  $('#alarmTimeBtn').textContent = alHM(c.h, c.m);
  $$('#alarmDays [data-day]').forEach(b => b.setAttribute('aria-pressed', String(c.days.includes(+b.dataset.day))));
  $('#alarmDaysNote').textContent = alarmDaysText(c.days) + (c.days.length ? '' : ': luego se apaga solo');
  const lists = state.playlists.filter(p => p.trackIds && p.trackIds.length);
  $('#alarmMusic').innerHTML = AL_MUSIC.map(([k, l]) => `<button type="button" class="chip" data-alm="${k}" aria-pressed="${c.music === k}">${l}</button>`).join('')
    + (lists.length ? `<select id="alarmList" class="al-list${c.music.startsWith('list:') ? ' on' : ''}" aria-label="Una de tus listas"><option value="">Una de tus listas…</option>${lists.map(p => `<option value="${esc(p.id)}"${c.music === 'list:' + p.id ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}</select>` : '');
  $('#alarmVol').value = c.vol; setRange($('#alarmVol')); $('#alarmVolVal').textContent = c.vol + ' %';
  $$('#alarmSnooze [data-sn]').forEach(b => b.setAttribute('aria-pressed', String(+b.dataset.sn === c.snooze)));
  $('#alarmNote').textContent = !NATIVE ? 'El despertador funciona en la app de Android. Aquí puedes probar cómo suena.'
    : !AL_PLUGIN ? 'Esta compilación de la app no trae el despertador. Puedes probar cómo suena.'
    : c.on && next > 0 ? `Sonará ${alarmWhen(next)}.` : `${dj.name} te despierta con tu música, subiendo poco a poco, y te dice la hora y el tiempo.`;
  const w = [];
  if (AL_PLUGIN && st && c.on) {
    if (!st.notif) w.push(['notif', 'Las notificaciones de Órbita están desactivadas: sin ellas el despertador no se ve con el móvil bloqueado.', 'Activarlas']);
    if (!st.exact) w.push(['exact', 'Android no deja a Órbita poner alarmas exactas: podría sonar unos minutos tarde.', 'Permitir']);
    if (!st.fullScreen) w.push(['fullscreen', 'Falta el permiso de pantalla completa: con el móvil bloqueado solo verás un aviso.', 'Permitir']);
  }
  $('#alarmWarn').hidden = !w.length;
  $('#alarmWarn').innerHTML = w.map(([k, t, b]) => `<div class="al-w"><span>${t}</span><button class="btn small" data-al-fix="${k}">${b}</button></div>`).join('');
}
function alarmMusicText() {
  const c = ALM.cfg.music;
  if (c.startsWith('list:')) { const pl = state.playlists.find(p => p.id === c.slice(5)); return pl ? `tu lista «${pl.name}»` : 'música suave'; }
  return { suave: 'música suave', daily: 'tu mezcla del día', favs: 'tus favoritas', radio: 'el modo radio' }[c] || 'tu música';
}
/* The music to wake up with */
function alarmPick() {
  const c = ALM.cfg, pool = poolTracks().filter(t => !t.net), own = pool.filter(t => !t.demo), base = own.length >= 6 ? own : pool;
  if (c.music === 'radio' && base.length >= 2) return { radio: true };
  if (c.music === 'daily') { const d = buildDaily(); if (d && d.ids && d.ids.length) return { ids: d.ids.filter(id => trackById(id)), name: d.name }; }
  if (c.music === 'favs') { const f = base.filter(liked); if (f.length >= 2) return { ids: shuffleArr(f.map(t => t.id)), name: 'Tus favoritas' }; }
  if (c.music.startsWith('list:')) { const pl = state.playlists.find(p => p.id === c.music.slice(5)), ids = pl && pl.trackIds.filter(id => trackById(id)); if (ids && ids.length) return { ids, name: pl.name }; }
  // «Suave para despertar»: songs you know well, from the calmest to a bit livelier
  const st = memStats(60), rec = new Map(st.all.map(r => [r.id, r]));
  const sc = base.filter(t => !prefBlocked(t)).map(t => { const r = rec.get(t.id), a = anaCache.get(t.id); return { t, s: Math.log1p(r ? r.plays : 0) + (liked(t) ? 1 : 0) - (skipProne(t) ? 1.2 : 0) + Math.random() * 0.7, e: a && typeof a.energy === 'number' ? a.energy : 0.5 }; })
    .sort((a, b) => b.s - a.s).slice(0, 24).sort((a, b) => a.e - b.e);
  return sc.length ? { ids: sc.map(x => x.t.id), name: 'Para despertar' } : null;
}
function alarmTimer(fn, ms) { const t = setTimeout(fn, ms); ALM.timers.push(t); return t; }
function alarmEvery(fn, ms) { const t = setInterval(fn, ms); ALM.ivs.push(t); return t; }
function alarmClear() { ALM.timers.forEach(clearTimeout); ALM.ivs.forEach(clearInterval); ALM.timers = []; ALM.ivs = []; }
function alarmGreeting(kind) {
  const d = new Date(), h = d.getHours(), hh = horaHablada(d), t = current(), dow = d.getDay();
  const hola = h >= 5 && h < 13 ? 'Buenos días' : h >= 13 && h < 21 ? 'Buenas tardes' : 'Buenas noches';
  const day = `Hoy es ${DAYS[dow]}, ${d.getDate()} de ${MESES[d.getMonth()]}.`;
  const close = dow === 5 ? '¡Por fin es viernes!' : dow === 6 || dow === 0 ? 'Es fin de semana: sin prisas.' : pick(['Despacito, que la música ya está aquí.', '¡Arriba, que hoy va a ser un buen día!', 'Tómate tu tiempo: yo me encargo de la música.', 'Venga, que hoy es tu día.']);
  if (kind === 'snooze') return `Venga, ahora sí. ${hh.verb} ${hh.text}. ${close}`;
  return `${hola}. ${hh.verb} ${hh.text}. ${day}${weatherLine()} ${t ? `Te despierto con ${nameOf(t)}. ` : ''}${close}`.replace(/\s+/g, ' ').trim();
}
function alarmScreenTick() {
  const d = new Date(), el = $('#alTime'); if (!el) return;
  el.textContent = alHM(d.getHours(), d.getMinutes());
  $('#alDate').textContent = `${cap(DAYS[d.getDay()])}, ${d.getDate()} de ${MESES[d.getMonth()]}`;
  const w = weatherNow(); $('#alWx').textContent = w ? `${w.city} · ${w.temp} °C · ${wxDesc(w.code)[0]}` : '';
  const t = current(); $('#alSong').innerHTML = t && ALM.acked ? `${coverHTML(t)}<span class="row-text"><span class="t-title">${esc(t.title)}</span><span class="t-sub">${esc(t.artist)}</span></span>` : `<span class="note">${ALM.acked ? '' : 'Poniendo tu música…'}</span>`;
  $('#alSnooze').textContent = `Posponer ${ALM.cfg.snooze} min`;
}
function alarmShowScreen() {
  if (!$('#onb').hidden) closeOnboarding(); if (!$('#recap').hidden) closeRecap(); if (!$('#chat').hidden) closeChat(); if (!$('#np').hidden) closeNP();
  $('#newsOverlay').hidden = true;
  $('#alarmScr').hidden = false; document.body.classList.add('rc-open');
  if (STANDALONE && !(history.state && history.state.alarm)) { try { history.pushState({ alarm: 1 }, '', location.hash || '#' + state.view); } catch {} }
  alarmScreenTick(); clearInterval(ALM.clockIv); ALM.clockIv = setInterval(alarmScreenTick, 5000);
  setTimeout(() => { try { $('#alUp').focus({ preventScroll: true }); } catch {} }, 80);
}
function alarmHideScreen() {
  $('#alarmScr').hidden = true; document.body.classList.remove('rc-open'); clearInterval(ALM.clockIv);
  if (STANDALONE && history.state && history.state.alarm) { try { popSkip = true; history.back(); } catch {} }
}
/* It's time: the screen, the music (very low, then up), Nova's good morning */
function alarmWake(ring, kind = 'alarm', test = false) {
  if (ALM.active && ALM.ring === ring) { alarmShowScreen(); return; }
  alarmClear(); ALM.active = true; ALM.ring = ring; ALM.kind = kind; ALM.test = test; ALM.acked = false;
  // a one-off alarm switches itself off once it has rung (the phone does the same)
  if (!test && kind === 'alarm' && !ALM.cfg.days.length && ALM.cfg.on) { ALM.cfg.on = false; LS.set('alarm', ALM.cfg); renderAlarm(); }
  alarmShowScreen();
  if (AL_PLUGIN && !test) AL_PLUGIN.wake({ volume: Math.round(ALM.cfg.vol) }).catch(() => {});
  if (ONLINE && LS.get('city', null)) loadWeather(false).then(() => alarmScreenTick()).catch(() => {});
  const p = alarmPick();
  if (!p) { alarmNoMusic(); return; }
  alarmF = 0.1; applyVol();
  if (p.radio) { if (!state.radio) $('#btnRadio').click(); else if (act().paused) togglePlay(); }
  else playQueue(p.ids, 0, { source: 'library' });
  // from very low to your volume in about 50 seconds (even in loudness: ×10 in level)
  const t0 = Date.now();
  alarmEvery(() => { const f = Math.min(1, (Date.now() - t0) / 50000); alarmF = 0.1 * Math.pow(10, f); applyVol(); if (f >= 1) { alarmF = 1; applyVol(); } }, 400);
  // it really sounds: tell the phone (no backup alarm)
  const ackIv = alarmEvery(() => {
    const d = act(), t = current();
    if (d && !d.paused && d.currentTime > 0.6 && t) { clearInterval(ackIv); ALM.acked = true; alarmScreenTick(); if (AL_PLUGIN && !test) AL_PLUGIN.ack({ ring, title: t.title }).catch(() => {}); }
  }, 700);
  alarmTimer(() => { if (ALM.active && !ALM.acked) alarmNoMusic(); }, 20000);
  if (ALM.cfg.talk) alarmTimer(() => { if (ALM.active) djTalk(alarmGreeting(kind), 'test', { forceTTS: true }); }, kind === 'snooze' ? 5000 : test ? 6000 : 13000);
  // half an hour and nobody has touched it: off
  alarmTimer(() => { if (ALM.active) alarmEnd('off', false, true); }, 30 * 60000);
}
function alarmNoMusic() {
  if (AL_PLUGIN && !ALM.test) AL_PLUGIN.ring({ ring: ALM.ring }).catch(() => {});
  $('#alSong').innerHTML = '<span class="note">No he podido poner tu música: suena la alarma del móvil.</span>';
}
/* Snooze, «Ya me levanto» (the music goes on) or off. fromPhone: the button was pressed in the notification */
async function alarmEnd(how, fromPhone, auto) {
  if (!ALM.active) { alarmHideScreen(); return; }
  ALM.active = false; alarmClear(); stopTalk(); alarmHideScreen();
  const test = ALM.test;
  if (how === 'up') {
    alarmF = 1; applyVol();
    if (AL_PLUGIN && !test && !fromPhone) AL_PLUGIN.stop({ keep: true }).catch(() => {});
    if (ALM.cfg.talk) djTalk(pick(['¡Genial! Que tengas un gran día.', 'Perfecto. La música sigue, tú a lo tuyo.', '¡Así me gusta! Sigo pinchando para ti.']), 'test', { forceTTS: true });
    return;
  }
  // fade out and pause
  const f0 = alarmF; for (let k = 1; k <= 8; k++) { await new Promise(r => setTimeout(r, 170)); alarmF = f0 * (1 - k / 8); applyVol(); }
  if (current() && !act().paused) await fadePause();
  alarmF = 1; applyVol();
  if (how === 'snooze') {
    let at = Date.now() + ALM.cfg.snooze * 60000;
    if (AL_PLUGIN && !test && !fromPhone) { try { const r = await AL_PLUGIN.snooze(); if (r && r.at) at = r.at; ALM.st = r; } catch {} }
    else if (fromPhone && fromPhone > 1e12) at = fromPhone;
    const d = new Date(at); toast(`${test ? 'Prueba: te despertaría' : 'Te despierto'} a las ${alHM(d.getHours(), d.getMinutes())}`, 3600);
  } else {
    if (AL_PLUGIN && !test && !fromPhone) AL_PLUGIN.stop({ keep: false }).then(st => { ALM.st = st; renderAlarm(); }).catch(() => {});
    if (auto) toast('Despertador apagado: media hora sin tocarlo');
  }
  renderAlarm();
}
if (AL_PLUGIN) {
  try {
    AL_PLUGIN.addListener('alarm', e => {
      if (!e) return;
      if (e.type === 'fire' || e.type === 'open') alarmWake(+e.ring || Date.now(), e.kind || 'alarm');
      else if (e.type === 'snooze') alarmEnd('snooze', +e.ring || 2);
      else if (e.type === 'stop') alarmEnd('off', 2);
    });
  } catch {}
}
/* At start: the settings from the phone (a one-off alarm switches itself off) and an alarm ringing right now */
async function alarmBoot() {
  renderAlarm();
  if (!AL_PLUGIN) return;
  try {
    const st = await AL_PLUGIN.get(); ALM.st = st;
    if (!st.on && ALM.cfg.on && st.ringAt) { ALM.cfg.on = false; LS.set('alarm', ALM.cfg); }
    else if (ALM.cfg.on && !st.on && !st.ringAt) alarmSave(true);
    renderAlarm();
    if (st.ringState === 1 && Date.now() - st.ringAt < 15 * 60000) alarmWake(st.ringAt, st.ringKind || 'alarm');
  } catch {}
}
const alarmBusy = () => ALM.active;
/* Ajustes */
$('#tAlarm').onclick = () => { ALM.cfg.on = !ALM.cfg.on; alarmSave(false).then(() => { if (ALM.cfg.on) toast(`Despertador: ${alarmWhen(alarmNext())}`, 3200); }); };
function alarmPickTime() {
  const inp = $('#alarmTime');
  try { if (typeof inp.showPicker === 'function') { inp.showPicker(); return; } } catch {}
  inp.classList.add('show'); inp.tabIndex = 0; inp.focus();
}
$('#alarmTimeBtn').onclick = alarmPickTime; $('#alarmTimeEdit').onclick = alarmPickTime;
$('#alarmTime').addEventListener('change', e => { const [h, m] = String(e.target.value || '').split(':').map(Number); if (isNaN(h) || isNaN(m)) return; ALM.cfg.h = h; ALM.cfg.m = m; ALM.cfg.on = true; alarmSave(false); });
$('#alarmDays').addEventListener('click', e => { const b = e.target.closest('[data-day]'); if (!b) return; const d = +b.dataset.day, s = new Set(ALM.cfg.days); s.has(d) ? s.delete(d) : s.add(d); ALM.cfg.days = [...s]; alarmSave(true); });
$('#alarmMusic').addEventListener('click', e => { const b = e.target.closest('[data-alm]'); if (!b) return; ALM.cfg.music = b.dataset.alm; LS.set('alarm', ALM.cfg); renderAlarm(); });
$('#alarmMusic').addEventListener('change', e => { if (e.target.id !== 'alarmList') return; ALM.cfg.music = e.target.value ? 'list:' + e.target.value : 'suave'; LS.set('alarm', ALM.cfg); renderAlarm(); });
$('#alarmVol').addEventListener('input', e => { ALM.cfg.vol = +e.target.value; setRange(e.target); $('#alarmVolVal').textContent = ALM.cfg.vol + ' %'; });
$('#alarmVol').addEventListener('change', () => alarmSave(true));
$('#tAlarmTalk').onclick = () => { ALM.cfg.talk = !ALM.cfg.talk; LS.set('alarm', ALM.cfg); renderAlarm(); };
$('#alarmSnooze').addEventListener('click', e => { const b = e.target.closest('[data-sn]'); if (!b) return; ALM.cfg.snooze = +b.dataset.sn; alarmSave(true); });
$('#alarmWarn').addEventListener('click', e => { const b = e.target.closest('[data-al-fix]'); if (!b || !AL_PLUGIN) return; const k = b.dataset.alFix; if (k === 'notif') AL_PLUGIN.requestNotif().then(st => { ALM.st = st; renderAlarm(); if (!st.notif) AL_PLUGIN.openSettings({ what: 'notif' }); }).catch(() => {}); else AL_PLUGIN.openSettings({ what: k }).catch(() => {}); });
$('#alarmTest').onclick = () => alarmWake(Date.now(), 'alarm', true);
$('#alarmPill').onclick = () => { setView('ajustes'); setTimeout(() => $('#alarmPanel').scrollIntoView({ block: 'start', behavior: 'smooth' }), 60); };
$('#alSnooze').onclick = () => alarmEnd('snooze');
$('#alUp').onclick = () => alarmEnd('up');
$('#alOff').onclick = () => alarmEnd('off');
document.addEventListener('visibilitychange', () => { if (!document.hidden && AL_PLUGIN && state.view === 'ajustes') alarmCheckPerms(false); });
/* By voice: «despiértame a las 7 y media», «pon el despertador de lunes a viernes a las 7», «quita la alarma», «¿a qué hora me despiertas?» */
function alarmCommand(q) {
  if (/(quita|apaga|desactiva|cancela|borra)( el| la)? (despertador|alarma)|no me despiertes/.test(q)) {
    if (!ALM.cfg.on) { novaSay('No tienes ningún despertador puesto.'); return true; }
    ALM.cfg.on = false; alarmSave(true); novaSay('Hecho: despertador apagado.'); return true;
  }
  if (/(a que hora|cuando) (me despiertas|suena (el despertador|la alarma))|tengo (alarma|despertador)|(esta|tengo) puesto el despertador/.test(q)) {
    const n = alarmNext(); novaSay(ALM.cfg.on && n > 0 ? `Te despierto ${alarmWhen(n)}. ${alarmDaysText(ALM.cfg.days)}.` : 'No tienes ningún despertador puesto. Dime, por ejemplo, «despiértame a las siete y media».'); return true;
  }
  if (!/(despiertame|despiertanos|despertador|alarma|levantame)/.test(q)) return false;
  const m = q.match(/(?:a las|a la|para las|para la|sobre las|las) (\d{1,2})(?:(?: y |:| )(\d{1,2}|media|cuarto)| menos (cuarto|\d{1,2}))?(?: (?:de la|por la) (manana|tarde|noche|madrugada))?/);
  if (!m) { if (/(despiertame|pon(me)? (el|un|una) (despertador|alarma))/.test(q)) { novaSay('¿A qué hora? Dime, por ejemplo, «despiértame a las siete y media».'); return true; } return false; }
  let h = +m[1], mi = m[2] === 'media' ? 30 : m[2] === 'cuarto' ? 15 : m[2] ? +m[2] : 0;
  if (m[3]) { h = h - 1; mi = m[3] === 'cuarto' ? 45 : 60 - +m[3]; }
  if ((m[4] === 'tarde' || m[4] === 'noche') && h < 12) h += 12;
  if (h < 0) h += 24; if (h > 23 || mi > 59) { novaSay('Esa hora no la entiendo. Prueba con «a las siete y media».'); return true; }
  const days = /todos los dias|cada dia|siempre/.test(q) ? [0, 1, 2, 3, 4, 5, 6] : /entre semana|de lunes a viernes|los dias de diario|los laborables/.test(q) ? [1, 2, 3, 4, 5] : /fin(es)? de semana|el finde|los findes/.test(q) ? [6, 0] : [];
  Object.assign(ALM.cfg, { on: true, h, m: mi, days });
  alarmSave(false);
  const n = alarmNext();
  novaSay(`Hecho: te despierto ${alarmWhen(n).replace(/ \(dentro de.*\)$/, '')}${days.length ? ` (${alarmDaysText(days).toLowerCase()})` : ''} con ${alarmMusicText()}.${!NATIVE || !AL_PLUGIN ? ' Ojo: el despertador solo suena en la app de Android.' : ''}`);
  return true;
}
/* Chart tooltips: tap or hover a bar, or focus it with the keyboard */
function chartTip(e) {
  const r = e.target.closest && e.target.closest('.bars .hits rect'); if (!r) return;
  const plot = r.closest('.bars-plot'), tip = plot.querySelector('.chart-tip');
  const pb = plot.getBoundingClientRect(), rb = r.getBoundingClientRect();
  tip.innerHTML = `<b>${esc(r.dataset.v)}</b><span>${esc(r.dataset.l)}</span>`; tip.hidden = false;
  const x = Math.max(40, Math.min(pb.width - 40, rb.left - pb.left + rb.width / 2));
  tip.style.left = x + 'px';
  plot.querySelectorAll('.hits rect.on').forEach(x => x.classList.remove('on')); r.classList.add('on');
}
function hideTips(root = document) { root.querySelectorAll('.chart-tip').forEach(t => { t.hidden = true; }); root.querySelectorAll('.hits rect.on').forEach(x => x.classList.remove('on')); }
function chartTipOff(e) { if (e.pointerType === 'touch') return; const plot = e.target.closest && e.target.closest('.bars-plot'); if (plot) hideTips(plot); }
['pointerover', 'pointerdown', 'focusin'].forEach(ev => $('#tuOut').addEventListener(ev, chartTip));
['pointerleave', 'focusout'].forEach(ev => $('#tuOut').addEventListener(ev, chartTipOff, true));
document.addEventListener('pointerdown', e => { if (!(e.target.closest && e.target.closest('.bars-plot'))) hideTips(); }, { passive: true });
$('#tuRange').addEventListener('click', e => { const b = e.target.closest('[data-range]'); if (!b) return; tuRange = +b.dataset.range; LS.set('tuRange', tuRange); renderTu(); });
$('#tMem').onclick = () => { const on = !memOn(); LS.set('memOn', on); setToggle($('#tMem'), on); toast(on ? `${dj.name} vuelve a recordar lo que escuchas` : `${dj.name} deja de apuntar lo que escuchas. Lo que ya sabe se queda hasta que lo borres.`, 3800); };
$('#memForget').onclick = e => armOrRun(e.currentTarget, async () => {
  memEvents = []; memCache.clear(); await idbClear('events');
  for (const t of state.tracks) { if (t.listenSec || t.skips || t.hours) { t.listenSec = 0; t.skips = 0; t.hours = null; saveTrack(t); } }
  LS.set('daily', null); renderTu(); renderForYou(); toast('Historial borrado. Tus favoritas y tus listas siguen igual.', 3600);
});

/* ---------- Lyrics: from the song's own tags, from LRCLIB, or pasted by you ---------- */
const LY = { on: LS.get('lyOn', false), id: null, rec: null, lines: null, idx: -2, gen: 0, userScroll: 0 };
function parseLRC(txt) {
  const out = []; let offset = 0;
  for (const raw of String(txt || '').split(/\r?\n/)) {
    const off = raw.match(/^\s*\[offset:\s*([+-]?\d+)\s*\]/i); if (off) { offset = +off[1] / 1000; continue; }
    const tags = [...raw.matchAll(/\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g)];
    if (!tags.length) continue;
    const text = raw.replace(/\[[^\]]*\]/g, '').replace(/<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g, '').trim();
    for (const m of tags) out.push({ t: Math.max(0, (+m[1]) * 60 + (+m[2]) + (m[3] ? +('0.' + m[3]) : 0) - offset), text });
  }
  return out.sort((a, b) => a.t - b.t);
}
const looksLRC = s => /\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]/.test(String(s || ''));
function lyricsRec(id, text, source) {
  const s = String(text || '').trim(); if (!s) return null;
  if (looksLRC(s)) { const lines = parseLRC(s); if (lines.length >= 3) return { id, synced: s, plain: lines.map(l => l.text).join('\n'), source, ts: Date.now() }; }
  return { id, plain: s.replace(/\r\n/g, '\n'), source, ts: Date.now() };
}
function readLyricsTag(blob) {
  return new Promise(res => {
    const lib = window.jsmediatags; if (!lib || !blob) return res(null);
    const to = setTimeout(() => res(null), 7000);
    try {
      lib.read(blob, {
        onSuccess: tag => {
          clearTimeout(to); const g = tag.tags || {};
          const pickTxt = v => typeof v === 'string' ? v : v && (v.lyrics || v.text || (v.data && (v.data.lyrics || v.data.text))) || '';
          res(pickTxt(g.lyrics) || pickTxt(g.USLT) || pickTxt(g['©lyr']) || pickTxt(g.LYRICS) || pickTxt(g.UNSYNCEDLYRICS) || null);
        },
        onError: () => { clearTimeout(to); res(null); }
      });
    } catch { clearTimeout(to); res(null); }
  });
}
async function lyricsFromTags(t) {
  if (t.net) return null;
  await loadTagLib();
  const blob = await trackBlob(t); if (!blob) return null;
  const txt = await readLyricsTag(blob);
  return txt ? lyricsRec(t.id, txt, 'tag') : null;
}
const cleanTitle = s => String(s || '').replace(/\s*[([](?:feat|ft|con|with|official|oficial|video|v[ií]deo|audio|lyrics?|letra|remaster|hd|hq|4k|live|en vivo|visualizer)[^)\]]*[)\]]/gi, '').replace(/\s+-\s+(?:remaster|live|en vivo|official).*$/i, '').trim();
const mainArtist = s => String(s || '').split(/\s*(?:,|&|\bx\b|\bfeat\.?|\bft\.?|\bcon\b|\by\b)\s*/i)[0].trim();
async function lyricsFromLrclib(t) {
  if (!ONLINE || t.net || !t.title || /desconocido/i.test(t.artist || '')) return null;
  const q = { track_name: cleanTitle(t.title), artist_name: mainArtist(t.artist) };
  const enc = o => Object.entries(o).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  const use = r => r && !r.instrumental && (r.syncedLyrics || r.plainLyrics) ? lyricsRec(t.id, r.syncedLyrics || r.plainLyrics, 'lrclib') : null;
  try {
    if (t.album && t.duration) { const r = await netJSON(`https://lrclib.net/api/get?${enc({ ...q, album_name: t.album, duration: Math.round(t.duration) })}`).catch(() => null); const x = use(r); if (x) return x; }
    const list = await netJSON(`https://lrclib.net/api/search?${enc(q)}`);
    if (!Array.isArray(list) || !list.length) return null;
    const d = t.duration || 0;
    const best = list.filter(r => r && (r.syncedLyrics || r.plainLyrics) && (!d || !r.duration || Math.abs(r.duration - d) <= 4))
      .sort((a, b) => (!!b.syncedLyrics - !!a.syncedLyrics) || (Math.abs((a.duration || d) - d) - Math.abs((b.duration || d) - d)))[0];
    return use(best);
  } catch { return null; }
}
async function getLyrics(t, force) {
  if (!t || t.net) return null;
  if (!force) {
    const c = await idbGet('lyrics', t.id);
    if (c && !(c.none && (Date.now() - c.ts > 7 * 864e5 || (c.offline && ONLINE)))) return c;
  }
  let rec = await lyricsFromTags(t);
  if (!rec) rec = await lyricsFromLrclib(t);
  if (!rec) rec = { id: t.id, none: true, offline: !ONLINE, ts: Date.now() };
  const prev = await idbGet('lyrics', t.id);
  if (prev && prev.source === 'manual' && !force) return prev;
  if (prev && typeof prev.shift === 'number') rec.shift = prev.shift;
  idbPut('lyrics', rec);
  return rec;
}
function lyricsSource(r) { return r.source === 'tag' ? 'Letra incluida en el archivo' : r.source === 'lrclib' ? 'Letra de LRCLIB.net' : r.source === 'manual' ? 'Letra añadida por ti' : ''; }
async function showLyrics(t) {
  const box = $('#npLyrics'); if (!box) return;
  const my = ++LY.gen; LY.id = t ? t.id : null; LY.rec = null; LY.lines = null; LY.idx = -2;
  if (!t) { box.innerHTML = `<div class="ly-empty"><p>Pon una canción para ver su letra.</p></div>`; return; }
  if (t.net) { box.innerHTML = `<div class="ly-empty"><p>${t.kind === 'radio' ? 'Las radios no traen la letra de lo que suena.' : 'Los avances de internet no traen letra.'}</p></div>`; return; }
  box.innerHTML = `<div class="ly-empty"><div class="eq"><i></i><i></i><i></i><i></i><i></i></div><p>Buscando la letra…</p></div>`;
  const r = await getLyrics(t);
  if (my !== LY.gen) return;
  renderLyrics(t, r);
}
function renderLyrics(t, r) {
  const box = $('#npLyrics'); LY.rec = r; LY.idx = -2;
  const tools = `<div class="ly-tools">${r && !r.none ? `<span class="note">${esc(lyricsSource(r))}</span>` : ''}<span class="spacer"></span>${r && r.synced ? `<button class="link" data-act="ly-shift" data-d="-0.5" aria-label="Adelantar la letra medio segundo">−0,5 s</button><button class="link" data-act="ly-shift" data-d="0.5" aria-label="Retrasar la letra medio segundo">+0,5 s</button>` : ''}<button class="link" data-act="ly-edit">${r && !r.none ? 'Editar' : 'Añadir letra'}</button></div>`;
  if (!r || r.none) {
    box.innerHTML = `<div class="ly-empty"><p>No he encontrado la letra de «${esc(t.title)}».</p><p class="note">${ONLINE ? 'Puedes pegarla tú: si lleva tiempos como [00:12.30] irá sincronizada con la música.' : 'En la app de Android la busco en internet. Aquí puedes pegarla tú.'}</p><button class="btn small" data-act="ly-edit">Añadir letra</button>${ONLINE ? '<button class="link" data-act="ly-retry">Buscar otra vez</button>' : ''}</div>`;
    return;
  }
  if (r.synced) {
    const shift = r.shift || 0;
    LY.lines = parseLRC(r.synced).map(l => ({ t: l.t + shift, text: l.text }));
    box.innerHTML = `<div class="ly-scroll" id="lyScroll">${LY.lines.map((l, i) => `<p class="ly-line${l.text ? '' : ' gap'}" data-i="${i}" role="button" tabindex="0">${l.text ? esc(l.text) : '♪'}</p>`).join('')}</div>${tools}`;
    lyricsTick(act().currentTime || 0, true);
  } else {
    box.innerHTML = `<div class="ly-scroll plain" id="lyScroll">${r.plain.split('\n').map(l => `<p class="ly-line${l.trim() ? '' : ' gap'}">${esc(l) || '&nbsp;'}</p>`).join('')}</div>${tools}`;
  }
  const sc = $('#lyScroll'); if (sc) { const mark = () => { LY.userScroll = Date.now(); }; sc.addEventListener('wheel', mark, { passive: true }); sc.addEventListener('touchmove', mark, { passive: true }); }
}
function lyricsTick(time, force) {
  if (!LY.on || !LY.lines || $('#np').hidden) return;
  const ls = LY.lines; let lo = 0, hi = ls.length - 1, idx = -1;
  while (lo <= hi) { const m = (lo + hi) >> 1; if (ls[m].t <= time + 0.15) { idx = m; lo = m + 1; } else hi = m - 1; }
  if (idx === LY.idx && !force) return;
  LY.idx = idx;
  const sc = $('#lyScroll'); if (!sc) return;
  sc.querySelectorAll('.ly-line.on').forEach(p => p.classList.remove('on'));
  const el = idx >= 0 ? sc.querySelector(`.ly-line[data-i="${idx}"]`) : null;
  if (!el) return;
  el.classList.add('on');
  if (Date.now() - LY.userScroll > 3500) sc.scrollTo({ top: el.offsetTop - sc.clientHeight * 0.42, behavior: reduceMotion || force ? 'auto' : 'smooth' });
}
function onTrackLyrics(t) { if (LY.on && !$('#np').hidden) showLyrics(t); else { LY.id = null; LY.lines = null; } }
function setLyricsOpen(on) {
  if (on && VZ.np) setVizNP(false);
  LY.on = on; LS.set('lyOn', on);
  $('#np').classList.toggle('ly-open', on);
  $('#npLyBtn').setAttribute('aria-pressed', on);
  if (on) { const t = current(); if (!t || LY.id !== t.id || !LY.rec) showLyrics(t); else lyricsTick(act().currentTime || 0, true); }
}
$('#npLyBtn').onclick = () => setLyricsOpen(!LY.on);
$('#npLyrics').addEventListener('click', e => {
  const line = e.target.closest('.ly-line[data-i]');
  if (line && LY.lines) { const l = LY.lines[+line.dataset.i]; const d = act(); if (l && isFinite(d.duration)) { d.currentTime = Math.max(0, l.t - 0.05); LY.userScroll = 0; lyricsTick(l.t, true); } return; }
  const b = e.target.closest('[data-act]'); if (!b) return;
  const t = current(); if (!t) return;
  if (b.dataset.act === 'ly-edit') openLyricsEdit(t);
  else if (b.dataset.act === 'ly-retry') { idbDel('lyrics', t.id).then(() => showLyrics(t)); }
  else if (b.dataset.act === 'ly-shift' && LY.rec) { LY.rec.shift = Math.round(((LY.rec.shift || 0) + (+b.dataset.d)) * 10) / 10; idbPut('lyrics', LY.rec); renderLyrics(t, LY.rec); toast(`Letra ${LY.rec.shift >= 0 ? 'retrasada' : 'adelantada'} ${Math.abs(LY.rec.shift).toString().replace('.', ',')} s`); }
});
$('#npLyrics').addEventListener('keydown', e => { if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('.ly-line[data-i]')) { e.preventDefault(); e.target.click(); } });
let lyEditId = null;
function openLyricsEdit(t) {
  lyEditId = t.id;
  $('#lyEditHead').textContent = `Letra de «${t.title}»`;
  $('#lyText').value = LY.rec && !LY.rec.none ? (LY.rec.synced || LY.rec.plain || '') : '';
  $('#lyOverlay').hidden = false; $('#lyText').focus();
}
const closeLyEdit = () => { $('#lyOverlay').hidden = true; lyEditId = null; };
$('#lyCancel').onclick = closeLyEdit;
$('#lyOverlay').addEventListener('click', e => { if (e.target.id === 'lyOverlay') closeLyEdit(); });
$('#lyForm').addEventListener('submit', async e => {
  e.preventDefault(); const id = lyEditId; if (!id) return closeLyEdit();
  const txt = $('#lyText').value.trim();
  if (!txt) { await idbDel('lyrics', id); closeLyEdit(); const t = current(); if (t && t.id === id) showLyrics(t); return; }
  const rec = lyricsRec(id, txt.slice(0, 20000), 'manual'); await idbPut('lyrics', rec); closeLyEdit();
  const t = current(); if (t && t.id === id) renderLyrics(t, rec);
  toast(rec.synced ? 'Letra guardada: irá sincronizada' : 'Letra guardada');
});

/* ---------- Nova's brain: small models that learn from you, entirely on the phone ----------
   · A map of your music from how each song sounds (energy, brightness, tempo, rhythm).
   · What you like to hear after what (learned from your listening order).
   · A skip predictor (logistic regression) trained on the songs you finish or skip. */
const BRAIN = { ban: new Set(LS.get('banned', [])), w: LS.get('brainW', null), n: LS.get('brainN', 0), at: LS.get('brainAt', 0), target: null, said: [] };
const sigmoid = z => 1 / (1 + Math.exp(-z));
const dot = (w, x) => { let s = 0; for (let i = 0; i < x.length; i++) s += (w[i] || 0) * x[i]; return s; };
const poolTracks = (opts) => {
  const base = visibleTracks().filter(t => !t.net && !BRAIN.ban.has(t.id));
  if (opts && opts.free) return base;
  const now = new Date(), out = base.filter(t => !prefBlocked(t, now));
  return out.length >= Math.min(3, base.length) ? out : base;
};
function trackVec(t) {
  const a = t && anaCache.get(t.id); if (!a || typeof a.energy !== 'number') return null;
  return [a.energy, a.bright ?? 0.5, Math.min(1, Math.max(0, ((a.bpm || 110) - 70) / 100)), Math.min(1, (a.rate || 3) / 8)];
}
const vdist = (a, b) => Math.hypot(a[0] - b[0], (a[1] - b[1]) * 0.6, a[2] - b[2], (a[3] - b[3]) * 0.5);
let transCache = null, transLen = -1;
function transitions() {
  if (transCache && transLen === memEvents.length) return transCache;
  const m = new Map();
  for (let i = 1; i < memEvents.length; i++) {
    const a = memEvents[i - 1], b = memEvents[i];
    if (a.net || b.net || a.id === b.id || b.ts - a.ts > 25 * 60000) continue;
    const good = !b.skip && (b.pct >= 0.6 || b.sec >= 90), bad = b.skip;
    if (!good && !bad) continue;
    const k = a.id + '>' + b.id; m.set(k, (m.get(k) || 0) + (good ? 1 : -1.2));
  }
  transCache = m; transLen = memEvents.length; return m;
}
const transScore = (a, b) => (a && b) ? Math.tanh((transitions().get(a + '>' + b) || 0) / 2) : 0;
/* Skip predictor */
const W0 = [-1.3, -0.9, -0.5, 2.4, -0.9, -0.9, 1.6, -1.6, 0.5, 0.25, 0.15];
function brainCtx(prev) {
  const st = memStats(30), amax = (st.artists[0] && st.artists[0].sec) || 1;
  return { now: Date.now(), hour: new Date().getHours(), prev: prev ? prev.id : null, prevArtist: prev ? artistKey(prev.artist) : '', vec: prev ? trackVec(prev) : null, aff: new Map(st.artists.slice(0, 40).map(a => [a.key, a.sec / amax])) };
}
function brainFeat(t, ctx, hour = ctx.hour) {
  const v = trackVec(t), cv = ctx.vec, p = plays(t), sk = t.skips || 0;
  let hm = 0; if (t.hours && t.hours.length === 24) { const tot = t.hours.reduce((a, b) => a + b, 0); if (tot >= 2) hm = (t.hours[hour] + 0.5 * (t.hours[(hour + 23) % 24] + t.hours[(hour + 1) % 24])) / tot; }
  return [1, liked(t) ? 1 : 0, Math.log1p(p) / 3, sk / (p + sk + 1), hm, ctx.aff.get(artistKey(t.artist)) || 0, v && cv ? vdist(v, cv) : 0.3, transScore(ctx.prev, t.id), t.lastPlayed && ctx.now - t.lastPlayed < 864e5 ? 1 : 0, p <= 1 ? 1 : 0, ctx.prevArtist && artistKey(t.artist) === ctx.prevArtist ? 1 : 0];
}
const brainW = () => (BRAIN.w && BRAIN.w.length === W0.length ? BRAIN.w : W0);
const pSkip = (t, ctx) => sigmoid(dot(brainW(), brainFeat(t, ctx)));
function trainBrain() {
  const rows = [];
  for (let i = Math.max(1, memEvents.length - 3000); i < memEvents.length; i++) {
    const e = memEvents[i]; if (e.net) continue;
    const y = e.skip ? 1 : (e.pct >= 0.6 || e.sec >= 120) ? 0 : null; if (y === null) continue;
    const t = trackById(e.id); if (!t) continue;
    const pe = memEvents[i - 1], prev = pe && !pe.net && e.ts - pe.ts < 25 * 60000 ? trackById(pe.id) : null;
    rows.push([e, t, prev, y]);
  }
  if (rows.length < 25) return false;
  const st = memStats(30), amax = (st.artists[0] && st.artists[0].sec) || 1, aff = new Map(st.artists.slice(0, 40).map(a => [a.key, a.sec / amax]));
  const X = rows.map(([e, t, prev]) => brainFeat(t, { now: e.ts, hour: e.hour, prev: prev ? prev.id : null, prevArtist: prev ? artistKey(prev.artist) : '', vec: prev ? trackVec(prev) : null, aff }, e.hour)), Y = rows.map(r => r[3]);
  const w = W0.slice(), lr = 0.06, lam = 0.02;
  for (let ep = 0; ep < 8; ep++) for (let i = 0; i < X.length; i++) {
    const err = sigmoid(dot(w, X[i])) - Y[i];
    for (let j = 0; j < w.length; j++) w[j] -= lr * (err * X[i][j] + lam * (w[j] - W0[j]));
  }
  BRAIN.w = w.map(v => Math.round(v * 1000) / 1000); BRAIN.n = rows.length; BRAIN.at = memEvents.length;
  LS.set('brainW', BRAIN.w); LS.set('brainN', BRAIN.n); LS.set('brainAt', BRAIN.at);
  return true;
}
function maybeTrain() { if (memEvents.length - (BRAIN.at || 0) >= 8) idle(() => trainBrain()); }
/* Ranking songs for "what next" */
function brainRank(pool, prev, { target = null, exclude = new Set(), explore = 0.35, strength = 1.6 } = {}) {
  const ctx = brainCtx(prev), pa = prev && anaCache.get(prev.id);
  return pool.filter(t => !t.net && !BRAIN.ban.has(t.id) && !exclude.has(t.id) && (!prev || t.id !== prev.id)).map(t => {
    let s = (1 - pSkip(t, ctx)) * 2 + prefBoost(t);
    const v = trackVec(t);
    if (target !== null && v) s -= Math.abs(v[0] - target) * strength; else if (target !== null) s -= 0.25 * strength;
    const a = anaCache.get(t.id);
    if (a && pa && a.bpm && pa.bpm && a.conf >= 2 && pa.conf >= 2) { let r = a.bpm / pa.bpm; if (r > 1.6) r /= 2; else if (r < 0.625) r *= 2; if (Math.abs(r - 1) <= 0.07) s += 0.35; }
    s += Math.random() * explore;
    return { t, s };
  }).sort((a, b) => b.s - a.s);
}
function similarTo(t, n = 15) {
  const v = trackVec(t), ctx = brainCtx(t);
  return poolTracks().filter(c => c.id !== t.id).map(c => {
    const cv = trackVec(c); let s = (1 - pSkip(c, ctx)) * 0.6;
    s += v && cv ? (1.2 - vdist(v, cv) * 2) : -0.3;
    s += 0.6 * (transScore(t.id, c.id) + transScore(c.id, t.id));
    if (artistKey(c.artist) && artistKey(c.artist) === artistKey(t.artist)) s += 0.3;
    return { c, s: s + Math.random() * 0.15 };
  }).sort((a, b) => b.s - a.s).slice(0, n).map(x => x.c);
}
/* Radio that adapts: skipping moves the energy away from what you skipped, finishing moves it closer */
function radioNext(prev, k = 6) {
  const recent = new Set([...state.history.slice(0, Math.min(12, Math.floor(poolTracks().length / 2))), ...state.queue.slice(Math.max(0, state.qi - 30), state.qi + 1)]);
  let ranked = brainRank(poolTracks(), prev, { target: BRAIN.target, exclude: recent });
  if (!ranked.length) ranked = brainRank(poolTracks(), prev, { target: BRAIN.target });
  const out = [], seenArtist = new Map();
  for (const x of ranked) { const a = artistKey(x.t.artist) || x.t.id; if ((seenArtist.get(a) || 0) >= 1 && ranked.length > 12) continue; out.push(x.t.id); seenArtist.set(a, 1); if (out.length >= k) break; }
  return out;
}
function radioBatch(avoid = []) {
  const prev = trackById(avoid[avoid.length - 1]) || current();
  return radioNext(prev, 8).filter(id => !avoid.includes(id));
}
function brainOnBegin(t) {
  if (t.net) return;
  const v = trackVec(t), pe = prefEnergy(); if (BRAIN.target === null && (v || pe !== null)) BRAIN.target = pe ?? v[0];
  if (state.source === 'radio') { state.queue = state.queue.slice(0, state.qi + 1).concat(radioNext(t, 6)); }
}
function brainOnListen(ev) {
  const v = trackVec(trackById(ev.id) || {}); if (!v) return;
  if (BRAIN.target === null) BRAIN.target = v[0];
  if (ev.skip) BRAIN.target = Math.max(0.05, Math.min(0.95, BRAIN.target + (v[0] >= BRAIN.target ? -0.12 : 0.12)));
  else if (ev.pct >= 0.6) BRAIN.target += (v[0] - BRAIN.target) * 0.3;
  maybeTrain();
}
function banTrack(t) {
  if (!t || t.net) return;
  BRAIN.ban.add(t.id); LS.set('banned', [...BRAIN.ban]);
  toast(`Vale: «${t.title}» no volverá a sonar en la radio ni en las mezclas. Puedes deshacerlo en «Tú».`, 4200);
  haptic(10);
  if (current() && current().id === t.id) { state.queue = state.queue.filter((id, i) => i <= state.qi || id !== t.id); next(false); }
}
function unbanTrack(id) { BRAIN.ban.delete(id); LS.set('banned', [...BRAIN.ban]); }
function moreLikeThis() {
  const t = current(); if (!t || t.net) { toast('Pon una canción de tu biblioteca primero'); return; }
  const sims = similarTo(t, 15).map(x => x.id); if (!sims.length) { toast('Aún no conozco bien tu música: escucha unas cuantas canciones más'); return; }
  state.queue = state.queue.slice(0, state.qi + 1).concat(sims); state.source = 'mix'; state.intros = {}; state.mixEndless = { like: t.id };
  if (state.radio) setRadio(false, true);
  prefetchNext(); renderQueue();
  const line = pick([`A partir de ahora, canciones que suenan como ${t.title}.`, `Marchando más música en la onda de ${t.title}.`, `Si te gusta esta, lo que viene te va a encantar.`]);
  djTalk(line, 'siguiente'); toast('Cola cambiada: canciones parecidas a esta, sin fin');
}
/* Requests without AI: words and synonyms turned into a selection of your music */
const INTENTS = [
  { re: /dormir|sueño|siesta|relaj|calm|tranquil|suave|chill|lluvia|leer|meditar|paz/, energy: 0.18, name: 'Para bajar revoluciones' },
  { re: /estudi|concentr|trabaj|foco|programar|oficina|deberes/, energy: 0.35, name: 'Para concentrarte' },
  { re: /triste|llorar|melanc|bajón|bajon|desamor|nostalg|gris|solo|sola/, energy: 0.3, bright: 0.35, name: 'Para los días grises' },
  { re: /romant|enamorad|en pareja|con mi pareja|cena con velas|san valentin/, energy: 0.4, bright: 0.6, name: 'Para dos' },
  { re: /feliz|alegr|anim|buen rollo|positiv|verano|sonreír|sonreir|happy/, energy: 0.7, bright: 0.7, name: 'Para venirse arriba' },
  { re: /fiesta|bailar|baile|perreo|viernes|discoteca|party|finde/, energy: 0.85, name: 'Modo fiesta' },
  { re: /gym|gimnas|entren|correr|cardio|pesas|motiva|deporte|running/, energy: 0.92, name: 'Para entrenar' },
  { re: /movid|ritmo|energ|subir|caña|potente|fuerte|duro|a tope/, energy: 0.8, name: 'Con energía' },
  { re: /coche|conducir|viaje|carretera/, energy: 0.6, name: 'Para la carretera' },
  { re: /cocinar|limpiar|casa|ducha/, energy: 0.6, name: 'Para casa' }
];
function parseRequest(text, pool) {
  const q = ' ' + norm(text) + ' ', out = { energy: null, bright: null, name: '', liked: false, fresh: false, forgotten: false, artists: [], notArtists: [], count: null, minutes: null };
  for (const it of INTENTS) if (it.re.test(q)) { out.energy = it.energy; if (it.bright) out.bright = it.bright; out.name = it.name; break; }
  if (/(mas|muy) (tranquil|suave|lent)/.test(q) && out.energy === null) out.energy = 0.2;
  if (/rapid|acelerad/.test(q)) out.energy = Math.max(out.energy || 0, 0.8);
  if (/favorit|las que me gustan|mis preferidas/.test(q)) out.liked = true;
  if (/nuev|no he escuchado|descubr|sin escuchar|recien/.test(q)) out.fresh = true;
  if (/olvidad|antigu|hace tiempo|viejas|clasicos mios|redescubr/.test(q)) out.forgotten = true;
  const num = q.match(/(\d{1,3})\s*(canciones|temas|cancion)/); if (num) out.count = Math.min(60, +num[1]);
  { const W = { una: 1, un: 1, dos: 2, tres: 3, cuatro: 4 }, hm = q.match(/ (\d{1,2}|una|un|dos|tres|cuatro) (horas?|h) /), mm = q.match(/(\d{1,3})\s*(min|minutos) /);
    if (hm) out.minutes = (W[hm[1]] || +hm[1]) * 60 + (/ y media /.test(q) ? 30 : 0); else if (/ hora y media /.test(q)) out.minutes = 90; else if (mm) out.minutes = +mm[1]; else if (/ media hora /.test(q)) out.minutes = 30;
    if (out.minutes) out.minutes = Math.min(300, out.minutes); }
  const artists = [...new Set(pool.map(t => t.artist).filter(a => artistKey(a) && artistKey(a).length >= 3))];
  for (const a of artists) {
    const k = artistKey(a); const i = q.indexOf(' ' + k + ' '); if (i < 0) continue;
    const before = q.slice(Math.max(0, i - 14), i);
    if (/(sin|nada de|menos|excepto|no pongas|ni)\s*$/.test(before)) out.notArtists.push(k); else out.artists.push(k);
  }
  out.genres = []; out.notGenres = [];
  for (const [g, re] of GENRES) { const mm = q.match(re); if (!mm) continue; const before = q.slice(Math.max(0, mm.index - 14), mm.index); if (/(sin|nada de|menos|excepto|no pongas|ni)\s*$/.test(before)) out.notGenres.push(g); else out.genres.push(g); }
  return out;
}
function intentMix(text, pool, n, m) {
  /* Asking by name or style overrides your «no me pongas…» rules: you asked for it */
  const free = poolTracks({ free: true }), it = parseRequest(text, free);
  if (it.artists.length || it.genres.length) { const own = pool.every(t => !t.demo); pool = own ? free.filter(t => !t.demo) : free; }
  const now = Date.now();
  let sel = pool.filter(t => !it.notArtists.includes(artistKey(t.artist)));
  if (it.artists.length) sel = sel.filter(t => it.artists.includes(artistKey(t.artist)));
  let gNote = '';
  if (it.genres.length) { const g = sel.filter(t => it.genres.some(x => trackGenres(t).has(x))); if (g.length >= 2) sel = g; else gNote = `Aún no sé qué canciones tuyas son ${it.genres[0]}: lo voy aprendiendo, y puedes enseñarme diciendo «tal artista es ${it.genres[0]}».`; }
  if (it.notGenres.length) sel = sel.filter(t => !it.notGenres.some(x => trackGenres(t).has(x)));
  if (it.liked) sel = sel.filter(liked);
  if (it.fresh) sel = sel.filter(t => plays(t) <= 1);
  if (it.forgotten) sel = sel.filter(t => plays(t) >= 2 && t.lastPlayed && now - t.lastPlayed > 14 * 864e5);
  let note = '';
  if (sel.length < 2) { sel = pool.filter(t => !it.notArtists.includes(artistKey(t.artist))); note = 'No había suficientes canciones con esos filtros, así que he ampliado un poco.'; }
  let target = it.energy;
  if (it.bright !== null) sel = sel.map(t => ({ t, b: (trackVec(t) || [0, 0.5])[1] })).sort((a, b) => Math.abs(a.b - it.bright) - Math.abs(b.b - it.bright)).slice(0, Math.max(n * 2, 12)).map(x => x.t);
  let want = it.count || n;
  if (target !== null) { const known = sel.filter(t => trackVec(t)); if (known.length >= Math.min(want, 4)) sel = known; }
  /* Stay near what was asked: «para dormir» never brings in the loudest songs just to fill the list */
  if (target !== null) { const near = sel.filter(t => { const v = trackVec(t); return !v || Math.abs(v[0] - target) <= 0.32; }); if (near.length >= 3) sel = near; }
  const ranked = brainRank(sel, sel.length <= 3 ? null : current(), { target, explore: 0.5, strength: target !== null ? 4.5 : 1.6 }).map(x => x.t);
  let picked = [];
  if (it.minutes) { let tot = 0; for (const t of ranked) { if (tot >= it.minutes * 60) break; picked.push(t); tot += t.duration || 200; } }
  else picked = ranked.slice(0, want);
  const en = t => (trackVec(t) || [0.5])[0];
  const ids = (target !== null && target <= 0.35 ? picked.slice().sort((x, y) => en(y) - en(x)) : target !== null && target >= 0.75 ? picked.slice().sort((x, y) => en(x) - en(y)) : arrangeArc(picked, 'req' + now)).map(t => t.id);
  const artistName = it.artists.length === 1 ? (pool.find(t => artistKey(t.artist) === it.artists[0]) || {}).artist : '';
  if (gNote) note = (note ? note + ' ' : '') + gNote;
  const name = artistName ? `Solo ${artistName}` : it.genres.length && !gNote ? `Solo ${it.genres[0]}` : it.liked ? 'Tus favoritas' : it.fresh ? 'Por descubrir' : it.forgotten ? 'Las que tenías olvidadas' : it.name || `Tu petición de ${m.day}`;
  const first = trackById(ids[0]);
  return { source: 'local', name, desc: `Elegida por ${dj.name} con lo que ha aprendido de ti: «${text}».`, greeting: first ? `${pick(['Marchando', 'Hecho', 'Vamos allá', 'Aquí lo tienes'])}: ${name.toLowerCase()}. Empezamos con ${nameOf(first)}.` : '', ids, why: {}, intros: {}, discover: [], moment: m.label, mood: 'auto', scenario: null, note };
}
const MOOD_ENERGY = { energia: 0.8, calma: 0.22, foco: 0.35, fiesta: 0.85, gris: 0.3, gym: 0.92, nostalgia: null };
function momentEnergy(m) { return { madrugada: 0.2, manana: 0.5, mediodia: 0.62, tarde: 0.55, noche: m.weekend ? 0.8 : 0.35 }[m.franja.id] ?? 0.5; }
/* Analyse the whole library little by little (faster while charging), so the brain knows every song */
let bgDone = 0, bgT = null;
async function isCharging() { try { const b = navigator.getBattery ? await navigator.getBattery() : null; return !!(b && b.charging); } catch { return false; } }
function bgAnalysis(delay = 8000) {
  clearTimeout(bgT);
  bgT = setTimeout(async () => {
    const charging = await isCharging();
    if (document.hidden || job || mixing || mixArm || NV.running || $('#dj').classList.contains('speaking')) { bgAnalysis(15000); return; }
    if (!charging && bgDone >= 24) return;
    const todo = state.tracks.filter(t => !t.net && !anaCache.has(t.id) && !anaPending.has(t.id) && !t.anaFail && (!t.duration || t.duration <= 900) && (!t.size || t.size < 60e6));
    if (!todo.length) return;
    const t = todo.sort((a, b) => plays(b) - plays(a))[0]; bgDone++;
    scheduleAnalysis(t); await anaQueue; if (!anaCache.has(t.id)) t.anaFail = true;
    if (state.view === 'tu') renderTuSoon();
    bgAnalysis(charging ? 4000 : 12000);
  }, delay);
}
const analysedShare = () => { const own = state.tracks.filter(t => !t.net && !t.demo); return own.length ? [own.filter(t => anaCache.has(t.id)).length, own.length] : [0, 0]; };
/* Talk that doesn't repeat itself */
function pickFresh(opts) {
  const fresh = opts.filter(o => !BRAIN.said.includes(o)); const line = pick(fresh.length ? fresh : opts);
  BRAIN.said = [line, ...BRAIN.said].slice(0, 14); return line;
}
function contextLine(t) {
  const prevId = state.queue[state.qi - 1], prev = prevId && trackById(prevId), opts = [];
  const v = trackVec(t), pv = prev && trackVec(prev);
  if (v && pv && v[0] - pv[0] > 0.22) opts.push(`Subimos revoluciones: ${t.title}.`, `Toca acelerar. ${nameOf(t)}.`);
  if (v && pv && pv[0] - v[0] > 0.22) opts.push(`Bajamos un poco el ritmo con ${t.title}.`, `Respiramos un momento: ${nameOf(t)}.`);
  const art = artistKey(t.artist);
  if (art && prev && artistKey(prev.artist) === art) opts.push(`Seguimos con ${t.artist}, que hoy no hay quien lo pare.`, `Repite ${t.artist}: ${t.title}.`);
  if (plays(t) <= 1 && !t.demo) opts.push(`Esta es nueva para ti: ${nameOf(t)}.`, `Estreno: ${t.title}. Ya me dirás.`);
  if (prev && transScore(prev.id, t.id) > 0.5) opts.push(`Después de ${prev.title} siempre te pega ${t.title}.`);
  const h = new Date().getHours(); if (h < 5) opts.push(`Para los que seguimos despiertos: ${t.title}.`);
  const session = memEvents.filter(e => Date.now() - e.ts < 90 * 60000).reduce((a, e) => a + e.sec, 0);
  if (session > 3600 && !BRAIN.saidHour) { BRAIN.saidHour = true; opts.length = 0; opts.push(`Llevas más de una hora de música conmigo. Seguimos con ${t.title}.`); }
  return opts.length && Math.random() < 0.55 ? pickFresh(opts) : null;
}

/* ---------- AI ---------- */
(async () => {
  try { aiFn = window.claude && window.claude.use ? await window.claude.use('sample') : null; } catch { aiFn = null; }
  refreshAi();
})();
function apiKey() { return STANDALONE ? LS.get('apiKey', '') : ''; }
function refreshAi() { aiState = (aiFn || apiKey()) ? 'ready' : 'off'; renderAiNote(); if (!state.net) renderSearch(); }
function renderAiNote() {
  const txt = aiState === 'checking' ? 'Comprobando la IA…' : aiState === 'ready' ? (aiFn ? 'IA lista · usa tu cuenta de Claude' : 'IA lista · con tu clave de la API') : (STANDALONE ? 'Sin IA: la DJ elige al azar (añade tu clave en Ajustes)' : 'Sin IA: la DJ elige al azar');
  $('#aiNote').innerHTML = `<span class="dot ${aiState === 'ready' ? 'on' : ''}"></span><span>${txt}</span>`;
}
/* One entry point for the AI: Claude inside claude.ai, or your own API key in the installed app */
async function aiJson(prompt, { signal, cache } = {}) {
  if (aiFn) return aiFn.json(prompt, { modelTier: 'quick', signal, ...(cache !== undefined ? { cache } : {}) });
  const key = apiKey(); if (!key) throw { code: 'not_granted' };
  let r;
  try {
    r = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST', signal,
      headers: { 'content-type': 'application/json', 'x-api-key': key, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' },
      body: JSON.stringify({ model: 'claude-haiku-5-5', max_tokens: 2500, messages: [{ role: 'user', content: prompt + '\n\nResponde únicamente con el JSON, sin texto antes ni después.' }] })
    });
  } catch (e) { throw { code: e && e.name === 'AbortError' ? 'cancelled' : 'offline' }; }
  if (r.status === 401 || r.status === 403) throw { code: 'bad_key' };
  if (r.status === 429) throw { code: 'rate_limited' };
  if (!r.ok) throw { code: 'upstream_error' };
  const j = await r.json(); const txt = (j.content || []).map(c => c.text || '').join('');
  const a = txt.indexOf('{'), b = txt.lastIndexOf('}');
  if (a < 0 || b < a) throw { code: 'invalid_json' };
  try { return JSON.parse(txt.slice(a, b + 1)); } catch { throw { code: 'invalid_json' }; }
}
const AI_OFF_CODES = ['not_granted','sampling_disabled','not_declared','capability_disabled','capability_removed'];
function aiErrorNote(e) {
  if (e?.code === 'bad_key') return 'La clave de la API no es válida. Revísala en Ajustes.';
  if (e?.code === 'offline') return 'Sin conexión a internet.';
  if (AI_OFF_CODES.includes(e?.code)) { aiState = 'off'; renderAiNote(); return 'No hay permiso para usar Claude en esta vista.'; }
  if (e?.code === 'rate_limited') return 'Muchas peticiones seguidas. Prueba de nuevo en un rato.';
  return 'La IA no respondió esta vez.';
}
const moodLabel = id => (MOODS.find(m => m.id === id) || MOODS[0]).label;
function moodFromText(s) {
  s = s.toLowerCase();
  const map = [[/gym|gimnas|entren|correr|pesas|cardio/, 'gym'], [/estudi|concentr|trabaj|foco|leer/, 'foco'], [/dormir|relaj|calma|tranquil|chill/, 'calma'], [/fiesta|bailar|baile|viernes|perreo/, 'fiesta'], [/trist|llorar|gris|lluvia|bajón|bajon|melanc/, 'gris'], [/nostal|recuerd|antig|infancia/, 'nostalgia'], [/energ|subir|motiv|gritos|cantar/, 'energia']];
  for (const [re, m] of map) if (re.test(s)) return m;
  return 'auto';
}
function buildPrompt(pool, m, mood, scenario, n, request) {
  const sample = pool.length > 250 ? shuffleArr(pool).slice(0, 250) : pool;
  const lines = sample.map(t => `${t.id} | ${t.title} | ${t.artist} | ${fmt(t.duration)} | ${plays(t)} | ${liked(t) ? 'sí' : 'no'}${skipProne(t) ? ' | se la suele saltar' : ''}${t.demo ? ' | pista demo (' + t.mood + ')' : ''}${t.mine ? ' | cantada por la propia persona' : ''}`).join('\n');
  const prof = profileText();
  const recent = state.history.slice(0, 8).map(trackById).filter(Boolean).map(t => `${t.title} (${t.artist})`).join('; ') || 'nada todavía';
  return `Eres ${dj.name}, la DJ de "Órbita", una app de música personal. ${personaPrompt()} Escribe en español de España. Crea una mezcla.

Momento: ${m.day} a las ${m.hh} (${m.franja.phrase}). Energía típica de esta franja: ${m.franja.energy}.${m.weekend ? ' Es fin de semana.' : ''}
${request ? `Lo que te pide la persona (respétalo por encima de todo): "${request}"\n` : `Ánimo que pide la persona: ${mood === 'auto' ? 'ninguno concreto; elige según el día y la hora' : moodLabel(mood)}.\n`}${scenario ? 'Tema sorpresa de la mezcla: ' + scenario + '.\n' : ''}Longitud: hasta ${n} canciones, sin repetir.

Biblioteca (id | título | artista | duración | veces escuchada | favorita):
${lines}

Escuchadas hace poco (no abras con ellas): ${recent}
${prof ? 'Lo que sabes de sus gustos (úsalo con naturalidad, sin recitarlo; evita lo que se suele saltar): ' + prof + '\n' : ''}
Reglas:
- Usa SOLO ids de la biblioteca.
- Ordena las canciones para que la energía fluya de forma natural.
- Combina favoritas con canciones poco escuchadas y varía artistas.
- Si no conoces una canción, deduce su carácter por título y artista, y no inventes datos sobre ella en las presentaciones.
- Las presentaciones se leen en voz alta: frases cortas y naturales, como se habla, sin emojis, símbolos ni comillas.

Responde solo con JSON con esta forma:
{"nombre":"título creativo de máximo 5 palabras","descripcion":"una frase","saludo":"1 o 2 frases de locutora presentando la mezcla y la primera canción","canciones":[{"id":"...","motivo":"máximo 8 palabras","intro":"frase de locutora de máximo 14 palabras para presentar esta canción"}],"descubrir":[{"titulo":"...","artista":"...","motivo":"máximo 10 palabras"}]}
En "descubrir" pon 3 canciones reales que NO estén en la biblioteca y encajen con la mezcla.`;
}
function localMix(pool, m, mood, scenario, n, minutes = 0) {
  const now = Date.now();
  let sel = pool;
  if (mood === 'nostalgia') { const f = pool.filter(t => plays(t) >= 2 && t.lastPlayed && now - t.lastPlayed > 14 * 864e5); if (f.length >= Math.min(4, n)) sel = f; }
  const t0 = mood === 'auto' ? (prefEnergy() ?? momentEnergy(m)) : (MOOD_ENERGY[mood] ?? null);
  const target = t0 === null ? null : Math.max(0.05, Math.min(0.95, t0 + (mood === 'auto' ? weatherBias() : 0)));
  const recent = new Set(state.history.slice(0, 5));
  const ranked = brainRank(sel, current(), { target, exclude: recent, explore: 0.6 }).map(x => x.t);
  const ordered = ranked.length >= n || minutes ? ranked : brainRank(sel, current(), { target, explore: 0.6 }).map(x => x.t);
  let chosen = ordered.slice(0, n);
  if (minutes) { chosen = []; let tot = 0; for (const t of ordered) { if (tot >= minutes * 60) break; chosen.push(t); tot += t.duration || 200; } }
  const ids = arrangeArc(chosen, 'mix' + now).map(t => t.id);
  const names = scenario ? [cap(scenario)] : [`${pick(['Sesión','Ruta','Mezcla','Vuelta'])} de ${m.day} ${m.franja.phrase}`, `${cap(m.day)} en órbita`, mood === 'auto' ? `Cara B ${m.franja.phrase}` : `Modo ${moodLabel(mood).toLowerCase()}`];
  const first = trackById(ids[0]);
  return { source: 'local', name: pick(names), desc: `Elegida por ${dj.name} en tu móvil, con lo que ha aprendido de lo que escuchas y saltas.`, greeting: first ? `Te he preparado una mezcla para este ${m.day}. Abrimos con ${nameOf(first)}.` : '', ids, why: {}, intros: {}, discover: [], moment: m.label, mood, scenario };
}
async function createMix({ surprise = false, request = '', autoplay = false, hint = '' } = {}) {
  const pool = poolTracks();
  if (pool.length < 2) { setBubble('Necesito al menos dos canciones. Súbelas en Biblioteca y lo intentamos.'); return; }
  if (state.busy) return;
  const m = getMoment();
  const scenario = surprise ? pick(SCENARIOS) : null;
  const mood = surprise ? pick(MOODS.slice(1)).id : request ? moodFromText(request) : state.mood;
  if (surprise) { state.mood = mood; LS.set('mood', mood); renderChips(); }
  /* how much music: a number of songs, an hour, or endless (Nova keeps adding more in the same mood) */
  const reqN = request ? ' ' + norm(request) + ' ' : '';
  const endless = state.len === 'inf' || /sin fin|infinit|sin parar|todo el rato|toda la (noche|tarde|manana)/.test(reqN);
  const minutes = state.len === '60m' && !request ? 60 : 0;
  const n = Math.min(endless ? 25 : state.len === '60m' ? 18 : (+state.len || 10), pool.length);
  state.busy = true; setBusy(true);
  let mix = null, note = '';
  if (aiState === 'ready') {
    renderThinking(scenario, request);
    setBubble(request ? `Marchando: «${request}». Déjame buscar en tu biblioteca…` : scenario ? `Tema sorpresa: ${scenario}. Buscando…` : 'Buscando el orden perfecto para este momento…');
    mixCtl = new AbortController();
    try {
      const r = await aiJson(buildPrompt(pool, m, mood, scenario, n, request), { cache: false, signal: mixCtl.signal });
      const ids = [], why = {}, intros = {}, seen = new Set();
      for (const c of (Array.isArray(r?.canciones) ? r.canciones : [])) {
        const id = String(c?.id ?? c ?? '').trim();
        if (trackById(id) && !seen.has(id)) { seen.add(id); ids.push(id); if (c?.motivo) why[id] = String(c.motivo).slice(0, 80); if (c?.intro) intros[id] = String(c.intro).slice(0, 160); }
        if (ids.length >= n) break;
      }
      if (ids.length >= 2) {
        const discover = (Array.isArray(r?.descubrir) ? r.descubrir : []).slice(0, 4).map(d => ({ title: String(d?.titulo || '').slice(0, 100), artist: String(d?.artista || '').slice(0, 100), why: String(d?.motivo || '').slice(0, 120) })).filter(d => d.title);
        mix = { source: 'ai', name: String(r?.nombre || 'Tu mezcla').slice(0, 60), desc: String(r?.descripcion || '').slice(0, 220), greeting: String(r?.saludo || '').slice(0, 260), ids, why, intros, discover, moment: m.label, mood, scenario, request };
      } else note = 'La IA no devolvió canciones válidas, así que he hecho la mezcla al azar.';
    } catch (e) {
      if (e?.code === 'cancelled') { state.busy = false; setBusy(false); renderMix(); setBubble('Vale, lo dejo. ¿Probamos otra cosa?'); return; }
      note = aiErrorNote(e) + ' He hecho la mezcla al azar.';
    }
    mixCtl = null;
  }
  if (!mix) { mix = request ? intentMix(hint ? request + ' ' + hint : request, pool, n, m) : localMix(pool, m, mood, scenario, n, minutes); if (request) mix.request = request; if (note) mix.note = note + (mix.note ? ' ' + mix.note : ''); }
  if (endless) { mix.endless = true; mix.refill = request ? { text: hint ? request + ' ' + hint : request } : { mood }; mix.desc = (mix.desc ? mix.desc + ' ' : '') + 'Sin fin: cuando se vaya acabando, Nova añade más canciones en la misma onda.'; }
  state.mix = mix; state.busy = false; setBusy(false); renderMix();
  if (autoplay) playMix(0);
  else setBubble(mix.greeting || `Lista: ${mix.name}. Dale a reproducir cuando quieras.`);
}
function playMix(i) {
  const mx = state.mix; if (!mx) return;
  const t = trackById(mx.ids[i]);
  const talk = i === 0 ? (mx.greeting || (t ? introFor(t) : null)) : null;
  playQueue(mx.ids, i, { source: 'mix', intros: mx.intros, talk, talkKind: 'saludo' });
  state.mixEndless = mx.endless ? mx.refill || {} : null;
}
/* Endless mixes: more songs in the same mood before the queue runs out (never the ones that just played) */
function mixRefill() {
  const e = state.mixEndless; if (!e || state.source !== 'mix') return [];
  const pool = poolTracks(); if (pool.length < 3) return [];
  let cands = [];
  try {
    if (e.like) { const seed = trackById(e.like) || current(); cands = seed && !seed.net ? similarTo(seed, 40).map(t => t.id) : []; }
    else if (e.text) cands = intentMix(e.text, pool, 40, getMoment()).ids || [];
    else cands = localMix(pool, getMoment(), e.mood || 'auto', null, 40).ids || [];
  } catch { cands = []; }
  const recent = new Set(state.queue.slice(-Math.min(60, Math.max(8, Math.floor(pool.length * 0.6)))));
  let ids = cands.filter(id => trackById(id) && !recent.has(id));
  if (ids.length < 3) { const last = new Set(state.queue.slice(-5)); ids = cands.filter(id => trackById(id) && !last.has(id)); }
  return [...new Set(ids)].slice(0, 10);
}
function setBusy(b) { ['#btnMix', '#btnSurprise', '#djSend', '#btnRoulette'].forEach(s => { $(s).disabled = b; }); }
function renderThinking(scenario, request) {
  $('#mixOut').innerHTML = `<div class="panel thinking"><div class="eq"><i></i><i></i><i></i><i></i><i></i></div><div style="flex:1;min-width:0"><b>Nova está escuchando tu biblioteca…</b><div class="note">${request ? 'Petición: ' + esc(request) : scenario ? 'Tema sorpresa: ' + esc(scenario) : 'Buscando el orden perfecto para este momento.'}</div></div><button class="btn small" data-act="mix-stop">Parar</button></div>`;
}
function linksFor(title, artist) {
  const q = encodeURIComponent(`${title} ${artist || ''}`.trim());
  return `<a class="link" href="https://www.youtube.com/results?search_query=${q}" target="_blank" rel="noopener">YouTube</a><a class="link" href="https://open.spotify.com/search/${q}" target="_blank" rel="noopener">Spotify</a><a class="link" href="https://soundcloud.com/search?q=${q}" target="_blank" rel="noopener">SoundCloud</a>`;
}
function renderMix() {
  const out = $('#mixOut'); const mix = state.mix;
  if (!mix) {
    const own = state.tracks.filter(t => !t.demo).length;
    out.innerHTML = `<div class="empty"><h3>Aquí aparecerá tu mezcla</h3><p>${own ? `Tienes ${own} ${own === 1 ? 'canción' : 'canciones'}. Pídele algo a la DJ, gira la ruleta o crea una mezcla a medida.` : 'Ya puedes probar con las cuatro pistas de demostración. Cuando subas tu música en Biblioteca, la DJ pinchará tus canciones.'}</p></div>`;
    return;
  }
  const items = mix.ids.map(trackById).filter(Boolean);
  const total = items.reduce((s, t) => s + (t.duration || 0), 0);
  const discover = mix.discover.length ? `<div class="discover"><div class="eyebrow">Para descubrir fuera de tu biblioteca</div><ul>${mix.discover.map(d => `<li><div class="d-text"><b>${esc(d.title)}</b>${d.artist ? ' · ' + esc(d.artist) : ''}${d.why ? `<span>${esc(d.why)}</span>` : ''}</div>${linksFor(d.title, d.artist)}</li>`).join('')}</ul></div>` : '';
  out.innerHTML = `<section class="panel glow mix">
    <div class="mix-head">
      <div><div class="eyebrow">${mix.source === 'ai' ? 'Mezcla de Nova (IA)' : 'Mezcla aleatoria'} · ${esc(mix.moment)} · ${items.length} canciones · ${fmt(total)}</div>
        <h2>${esc(mix.name)}</h2>${mix.desc ? `<p>${esc(mix.desc)}</p>` : ''}${mix.request ? `<p class="note">Pedido: «${esc(mix.request)}»</p>` : ''}</div>
      <div class="mix-actions">
        <button class="btn primary" data-act="mix-play">${ICON.play}Reproducir</button>
        <button class="btn" data-act="mix-save">Guardar lista</button>
        <button class="btn" data-act="mix-again">Otra versión</button>
      </div>
    </div>
    <ol class="mix-list">${items.map((t, i) => `<li><button class="mix-item" data-act="mix-from" data-i="${i}" data-track="${t.id}"><span class="n">${pad(i + 1)}</span>${coverHTML(t)}<span class="row-text"><span class="t-title">${esc(t.title)}</span><span class="t-sub">${esc(t.artist)}</span>${mix.why[t.id] ? `<span class="why">${esc(mix.why[t.id])}</span>` : ''}</span><span class="mono t-sub">${fmt(t.duration)}</span></button></li>`).join('')}</ol>
    ${discover}
    ${mix.note ? `<p class="note">${esc(mix.note)}</p>` : ''}
  </section>`;
  markPlaying();
}

/* ---------- Search ---------- */
let netCtl = null;
function libMatches(q) {
  const nq = norm(q); if (!nq) return [];
  const words = nq.split(' ');
  return visibleTracks().filter(t => { const h = norm(`${t.title} ${t.artist} ${t.album}`); return words.every(w => h.includes(w)); }).slice(0, 8);
}
function inLibrary(title, artist) {
  const nt = norm(title), na = norm(artist);
  return state.tracks.find(t => norm(t.title) === nt && (!na || norm(t.artist).includes(na) || na.includes(norm(t.artist)))) || state.tracks.find(t => norm(t.title) === nt);
}
async function runSearch(q) {
  q = q.trim(); if (!q) { $('#netQ').focus(); return; }
  netCtl?.abort(); netCtl = new AbortController();
  const my = state.net = { q, lib: libMatches(q), results: null, note: '', loading: aiState === 'ready', online: null, onLoading: ONLINE, onErr: '' };
  renderSearch();
  if (ONLINE) onlineSongs(q).then(list => { if (state.net === my) my.online = list; }).catch(() => { if (state.net === my) my.onErr = 'No he podido conectar para buscar canciones en internet.'; }).finally(() => { if (state.net === my) { my.onLoading = false; renderSearch(); } });
  if (!my.loading) return;
  try {
    const r = await aiJson(`Eres el buscador musical de una app. La persona busca: "${q}".
Devuelve hasta 8 canciones reales que existan y respondan a la búsqueda. Si es un artista, sus canciones más conocidas y alguna joya menos famosa. Si es una canción, esa canción (con su artista correcto) y otras parecidas. Si es un género, una época o un ambiente, una selección variada.
No inventes canciones: si dudas de que exista, no la pongas. Escribe en español de España.
Responde solo con JSON: {"resultados":[{"titulo":"","artista":"","año":"","genero":"","motivo":"máximo 10 palabras"}],"nota":"opcional, una frase corta"}`, { signal: netCtl.signal });
    if (state.net !== my) return;
    my.results = (Array.isArray(r?.resultados) ? r.resultados : []).slice(0, 8).map(x => ({ title: String(x?.titulo || '').slice(0, 120), artist: String(x?.artista || '').slice(0, 120), year: String(x?.['año'] ?? x?.ano ?? '').slice(0, 10), genre: String(x?.genero || '').slice(0, 40), why: String(x?.motivo || '').slice(0, 120) })).filter(x => x.title);
    my.note = String(r?.nota || '').slice(0, 200);
  } catch (e) {
    if (e?.code === 'cancelled' || state.net !== my) return;
    my.note = aiErrorNote(e) + (ONLINE ? '' : ' Puedes buscarlo directamente con los enlaces de arriba.');
  }
  my.loading = false; renderSearch();
}
function renderSearch() {
  const out = $('#netOut'), s = state.net;
  if (!s) {
    out.innerHTML = `<div class="empty"><h3>Busca cualquier canción del mundo</h3><p>${ONLINE ? 'Escribe un título, un artista, un género o un ambiente. Te enseño lo que ya tienes, avances de 30 segundos para escuchar al momento y las ideas de tu DJ.' : `Escribe un título, un artista, un género o un ambiente. Te enseño lo que ya tienes y ${esc(dj.name)} te propone canciones reales para abrir en YouTube, Spotify o SoundCloud. En la app de Android, además, las escuchas al momento.`} ${aiState === 'off' ? 'Ahora mismo la IA no está disponible.' : ''}</p><div class="ideas">${['Rosalía','reguetón de 2010','rock en español de los 80','lo-fi para estudiar','canciones como Bohemian Rhapsody'].map(x => `<button class="idea" data-netq="${esc(x)}">${esc(x)}</button>`).join('')}</div></div>`;
    return;
  }
  const libHTML = s.lib.length ? `<section class="panel"><div class="stack" style="gap:12px"><div class="eyebrow">Ya en tu biblioteca</div><ul class="results">${s.lib.map(t => `<li class="result" data-track="${t.id}">${coverHTML(t)}<div class="d-text"><b>${esc(t.title)}</b> · ${esc(t.artist)}<span>${esc(quality(t)?.label || '')}</span></div><button class="btn small primary" data-act="net-play" data-id="${t.id}">${ICON.playS}Escuchar</button></li>`).join('')}</ul></div></section>` : '';
  let onHTML = '';
  if (ONLINE) {
    if (s.onLoading) onHTML = `<div class="panel thinking"><div class="eq"><i></i><i></i><i></i><i></i><i></i></div><div style="flex:1;min-width:0"><b>Buscando «${esc(s.q)}» en internet…</b></div></div>`;
    else if (s.online && s.online.length) onHTML = `<section class="panel glow"><div class="stack" style="gap:12px"><div class="row-wrap"><div class="eyebrow">Escúchalas ya · avances de 30 segundos</div><span class="spacer"></span><button class="btn small" data-act="on-playall">${ICON.playS}Escuchar todos</button></div><ul class="results">${s.online.slice(0, 15).map((t, i) => { const saved = state.wishlist.some(w => norm(w.title) === norm(t.title) && norm(w.artist) === norm(t.artist)); return `<li class="result" data-track="${t.id}">${coverHTML(t)}<div class="d-text"><b>${esc(t.title)}</b> · ${esc(t.artist)}<span>${esc([t.album, t.full ? fmt(t.full) : ''].filter(Boolean).join(' · '))}</span></div><div class="links"><button class="btn small primary" data-act="on-play" data-i="${i}">${ICON.playS}Avance</button><button class="link" data-act="yt-find" data-q="${esc(t.title + ' ' + t.artist)}">YouTube</button><button class="link" data-act="wish-add-on" data-i="${i}" ${saved ? 'disabled' : ''}>${saved ? 'Guardada' : 'Guardar'}</button></div></li>`; }).join('')}</ul><p class="note">Avances de Deezer o Apple Music. Para tenerla entera, consíguela en archivo y súbela a tu biblioteca, o mira el vídeo en YouTube.</p></div></section>`;
    else if (s.onErr) onHTML = `<p class="note">${esc(s.onErr)}</p>`;
    else if (s.online) onHTML = `<p class="note">No hay avances para «${esc(s.q)}».</p>`;
  }
  let netHTML;
  if (s.loading) netHTML = `<div class="panel thinking"><div class="eq"><i></i><i></i><i></i><i></i><i></i></div><div style="flex:1;min-width:0"><b>${esc(dj.name)} está pensando en «${esc(s.q)}»…</b><div class="note">Reuniendo canciones reales que encajan.</div></div></div>`;
  else if (s.results && s.results.length) netHTML = `<section class="panel"><div class="stack" style="gap:12px"><div class="eyebrow">Ideas de ${esc(dj.name)} · según la IA</div><ul class="results">${s.results.map((x, i) => {
      const have = inLibrary(x.title, x.artist); const saved = state.wishlist.some(w => norm(w.title) === norm(x.title) && norm(w.artist) === norm(x.artist));
      return `<li class="result"><span class="r-num">${i + 1}</span><div class="d-text"><b>${esc(x.title)}</b>${x.artist ? ' · ' + esc(x.artist) : ''}<span>${[x.year, x.genre, x.why].filter(Boolean).map(esc).join(' · ')}</span></div><div class="links">${have ? `<button class="btn small primary" data-act="net-play" data-id="${have.id}">${ICON.playS}La tienes</button>` : (ONLINE ? `<button class="btn small primary" data-act="ai-preview" data-i="${i}">${ICON.playS}Avance</button><button class="link" data-act="yt-find" data-q="${esc(x.title + ' ' + x.artist)}">YouTube</button>` : linksFor(x.title, x.artist))}${have ? '' : `<button class="link" data-act="wish-add" data-i="${i}" ${saved ? 'disabled' : ''}>${saved ? 'Guardada' : 'Guardar'}</button>`}</div></li>`;
    }).join('')}</ul>${s.note ? `<p class="note">${esc(s.note)}</p>` : ''}</div></section>`;
  else netHTML = s.note ? `<p class="note">${esc(s.note)}</p>` : (s.results ? `<p class="note">La IA no encontró canciones para «${esc(s.q)}». Prueba con otras palabras.</p>` : '');
  const direct = ONLINE ? '' : `<div class="infobox" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><span style="flex:1 1 200px">Buscar «<b>${esc(s.q)}</b>» directamente en:</span>${linksFor(s.q, '')}</div>`;
  out.innerHTML = direct + libHTML + onHTML + netHTML;
  markPlaying();
}
/* ---------- Online: song previews, internet radio and YouTube inside the app ----------
   In the Android app requests go through the native HTTP client (no browser limits on other sites). */
const NET_UA = 'Orbita/2.0 (app de música personal; Android)';
async function netJSON(url, { headers = {}, timeout = 15000 } = {}) {
  const http = NATIVE && window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.CapacitorHttp;
  if (http) {
    try { return await nativeJSON(http, url, headers, timeout); }
    catch (e) { if (e && e.status && e.status < 500) throw e; /* no connection or a server hiccup: try again through the WebView */ }
  }
  const ctl = new AbortController(); const to = setTimeout(() => ctl.abort(), timeout);
  try { const r = await fetch(url, { signal: ctl.signal, headers }); if (!r.ok) { const e = new Error('http ' + r.status); e.status = r.status; try { e.data = await r.json(); } catch {} throw e; } return await r.json(); }
  finally { clearTimeout(to); }
}
async function nativeJSON(http, url, headers, timeout) {
  {
    const r = await Promise.race([
      http.request({ url, method: 'GET', headers: { Accept: 'application/json', 'User-Agent': NET_UA, ...headers }, responseType: 'json', connectTimeout: timeout, readTimeout: timeout }),
      sleep(timeout + 2000).then(() => { throw new Error('timeout'); })
    ]);
    if (!r || r.status < 200 || r.status >= 300) { const e = new Error('http ' + (r && r.status)); e.status = r && r.status; e.data = r && r.data; throw e; }
    return typeof r.data === 'string' ? JSON.parse(r.data) : r.data;
  }
}
const ccGuess = (() => { for (const l of (navigator.languages || [navigator.language || ''])) { const m = /^[a-z]{2,3}-([A-Z]{2})$/.exec(l || ''); if (m) return m[1]; } return ''; })();

/* Songs: real results with 30-second previews and covers (Deezer, or iTunes if Deezer fails) */
async function onlineSongs(q) {
  try {
    const j = await netJSON(`https://api.deezer.com/search?q=${encodeURIComponent(q)}&limit=25`);
    const list = (j && Array.isArray(j.data) ? j.data : []).filter(x => x && x.preview && x.readable !== false);
    if (list.length) return list.map(x => netTrack({ id: 'dz:' + x.id, kind: 'preview', title: x.title_short || x.title, artist: x.artist?.name || '', album: x.album?.title || '', art: x.album?.cover_medium || x.album?.cover || '', artBig: x.album?.cover_xl || x.album?.cover_big || '', url: x.preview, duration: 30, full: x.duration || 0, link: x.link || '' }));
  } catch {}
  const j = await netJSON(`https://itunes.apple.com/search?term=${encodeURIComponent(q)}&media=music&entity=song&limit=25${ccGuess ? '&country=' + ccGuess.toLowerCase() : ''}`);
  return (j && Array.isArray(j.results) ? j.results : []).filter(x => x && x.previewUrl).map(x => netTrack({ id: 'it:' + x.trackId, kind: 'preview', title: x.trackName, artist: x.artistName || '', album: x.collectionName || '', art: (x.artworkUrl100 || '').replace('100x100', '300x300'), artBig: (x.artworkUrl100 || '').replace('100x100', '600x600'), url: x.previewUrl, duration: 30, full: Math.round((x.trackTimeMillis || 0) / 1000), link: x.trackViewUrl || '' }));
}
async function previewFor(title, artist) {
  const list = await onlineSongs(`${title} ${artist || ''}`.trim());
  const nt = norm(cleanTitle(title));
  return list.find(x => norm(cleanTitle(x.title)) === nt) || list.find(x => norm(x.title).includes(nt) || nt.includes(norm(x.title))) || list[0] || null;
}

/* Internet radio: thousands of stations from radio-browser.info */
/* radio-browser.info has several mirrors that come and go; "all" points at whichever are alive */
let RB_HOSTS = ['https://all.api.radio-browser.info', 'https://de2.api.radio-browser.info', 'https://fi1.api.radio-browser.info', 'https://nl1.api.radio-browser.info', 'https://at1.api.radio-browser.info', 'https://de1.api.radio-browser.info'];
let rbHost = LS.get('rbHost', null), rbFound = false;
async function rbGet(path) {
  const hosts = [...new Set([rbHost, RB_HOSTS[0], ...shuffleArr(RB_HOSTS.slice(1))].filter(Boolean))];
  let err;
  for (let round = 0; round < 2; round++) {
    for (const h of hosts) {
      try { const j = await netJSON(h + path, { timeout: 8000 }); if (rbHost !== h) { rbHost = h; LS.set('rbHost', h); } rbDiscover(h); return j; }
      catch (e) { err = e; }
    }
    await sleep(1500);
  }
  throw err || new Error('radio');
}
function rbDiscover(h) {
  if (rbFound) return; rbFound = true;
  netJSON(h + '/json/servers', { timeout: 8000 }).then(list => {
    const names = (Array.isArray(list) ? list : []).map(x => x && x.name).filter(n => /^[a-z0-9.-]+\.radio-browser\.info$/i.test(n || ''));
    if (names.length) RB_HOSTS = [RB_HOSTS[0], ...new Set(names.map(n => 'https://' + n))];
  }).catch(() => {});
}
const RADIO_TAGS = [['', 'Populares'], ['pop', 'Pop'], ['reggaeton', 'Reguetón'], ['latin', 'Latina'], ['rock', 'Rock'], ['dance', 'Dance'], ['electronic', 'Electrónica'], ['hip hop', 'Hip hop'], ['salsa', 'Salsa'], ['bachata', 'Bachata'], ['cumbia', 'Cumbia'], ['80s', 'Años 80'], ['90s', 'Años 90'], ['jazz', 'Jazz'], ['classical', 'Clásica'], ['lofi', 'Lo-fi'], ['chillout', 'Chill'], ['news', 'Noticias']];
const RADIO_CC = [['', 'Todo el mundo'], ['ES', 'España'], ['MX', 'México'], ['AR', 'Argentina'], ['CO', 'Colombia'], ['CL', 'Chile'], ['PE', 'Perú'], ['VE', 'Venezuela'], ['EC', 'Ecuador'], ['US', 'EE. UU.'], ['CU', 'Cuba'], ['DO', 'Rep. Dominicana'], ['PR', 'Puerto Rico'], ['UY', 'Uruguay'], ['PY', 'Paraguay'], ['BO', 'Bolivia'], ['CR', 'Costa Rica'], ['GT', 'Guatemala'], ['HN', 'Honduras'], ['NI', 'Nicaragua'], ['PA', 'Panamá'], ['SV', 'El Salvador'], ['GB', 'Reino Unido'], ['FR', 'Francia'], ['IT', 'Italia'], ['DE', 'Alemania'], ['BR', 'Brasil'], ['PT', 'Portugal']];
const radio = { tag: LS.get('rTag', ''), cc: LS.get('rCC', RADIO_CC.some(c => c[0] === ccGuess) ? ccGuess : ''), q: '', list: null, loading: false, err: '', favs: LS.get('radioFavs', []) };
function stationTrack(s) {
  const tags = String(s.tags || '').split(',').map(x => x.trim()).filter(Boolean).slice(0, 2);
  return netTrack({ id: 'rb:' + s.stationuuid, kind: 'radio', uuid: s.stationuuid, title: String(s.name || 'Radio').trim().slice(0, 80), artist: [s.country || s.countrycode, ...tags].filter(Boolean).join(' · '), country: s.country || '', art: /^https?:/.test(s.favicon || '') ? s.favicon : '', url: s.url_resolved || s.url, codec: s.codec || '', bitrate: s.bitrate || 0, raw: { stationuuid: s.stationuuid, name: s.name, url_resolved: s.url_resolved || s.url, favicon: s.favicon, country: s.country, countrycode: s.countrycode, tags: s.tags, codec: s.codec, bitrate: s.bitrate } });
}
async function loadRadios() {
  if (!ONLINE) return;
  radio.loading = true; radio.err = ''; renderRadios();
  const p = new URLSearchParams({ limit: '60', hidebroken: 'true', order: 'clickcount', reverse: 'true' });
  if (radio.tag) p.set('tag', radio.tag); if (radio.cc) p.set('countrycode', radio.cc); if (radio.q) p.set('name', radio.q);
  const my = radio.req = Symbol();
  try {
    const j = await rbGet('/json/stations/search?' + p.toString());
    if (radio.req !== my) return;
    const seen = new Set();
    const raw = (Array.isArray(j) ? j : []).filter(s => s && (s.url_resolved || s.url) && !s.hls && s.lastcheckok !== 0 && !seen.has(norm(s.name)) && seen.add(norm(s.name))).slice(0, 40);
    radio.list = raw.map(stationTrack); radio.stale = false;
    /* Keep a copy, so the radios still work when the directory is down */
    try { const c = LS.get('radioCache', {}); c[p.toString()] = { ts: Date.now(), list: raw.map(st => stationTrack(st).raw) }; const keys = Object.keys(c).sort((a, b) => c[b].ts - c[a].ts).slice(0, 25); LS.set('radioCache', Object.fromEntries(keys.map(k => [k, c[k]]))); } catch {}
  } catch (e) {
    if (radio.req === my) {
      const c = (LS.get('radioCache', {}) || {})[p.toString()];
      if (c && c.list && c.list.length) { radio.list = c.list.map(stationTrack); radio.stale = true; radio.err = ''; }
      else { radio.list = null; radio.err = navigator.onLine === false ? 'No tienes conexión a internet.' : `El directorio de radios no responde ahora mismo${e && e.status ? ' (error ' + e.status + ', es un fallo de su servidor)' : ''}. Tus radios guardadas con el corazón siguen funcionando. Prueba otra vez en unos minutos.`; }
    }
  }
  if (radio.req === my) { radio.loading = false; renderRadios(); }
}
const isFav = uuid => radio.favs.some(f => f.stationuuid === uuid);
function toggleFav(uuid) {
  const t = trackById('rb:' + uuid); if (!t) return;
  if (isFav(uuid)) radio.favs = radio.favs.filter(f => f.stationuuid !== uuid); else radio.favs.unshift(t.raw);
  radio.favs = radio.favs.slice(0, 60); LS.set('radioFavs', radio.favs); renderRadios();
}
function stationRow(t, list) {
  const fav = isFav(t.uuid);
  return `<li class="result st" data-track="${t.id}"><span class="cover st-logo">${t.art ? `<img src="${esc(t.art)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}<span class="st-ph" aria-hidden="true">${esc(initial(t))}</span></span><div class="d-text"><b>${esc(t.title)}</b><span>${esc(t.artist)}${t.bitrate ? ` · ${t.bitrate} kbps` : ''}</span></div><div class="links"><button class="icon-btn ${fav ? 'liked' : ''}" data-act="r-fav" data-uuid="${esc(t.uuid)}" aria-pressed="${fav}" aria-label="${fav ? 'Quitar de mis radios' : 'Guardar en mis radios'}">${fav ? ICON.heartOn : ICON.heart}</button><button class="btn small primary" data-act="r-play" data-id="${t.id}" data-list="${list}">${ICON.playS}Escuchar</button></div></li>`;
}
function renderRadios() {
  const out = $('#radioOut'); if (!out) return;
  $('#netRadios .toolbar').hidden = $('#rTags').hidden = !ONLINE;
  if (!ONLINE) { out.innerHTML = `<div class="empty"><h3>Radio por internet</h3><p>Miles de emisoras de todo el mundo, con la pantalla apagada y en segundo plano. Funciona en la app de Android, que puede conectarse a ellas. Aquí, dentro de Claude, la página no tiene permiso para salir a internet.</p></div>`; return; }
  $('#rTags').innerHTML = RADIO_TAGS.map(([id, n]) => `<button class="chip" data-rtag="${esc(id)}" aria-pressed="${radio.tag === id}">${esc(n)}</button>`).join('');
  $('#rCC').innerHTML = RADIO_CC.map(([id, n]) => `<option value="${id}" ${radio.cc === id ? 'selected' : ''}>${esc(n)}</option>`).join('');
  const favs = radio.favs.map(stationTrack);
  const favHTML = favs.length ? `<section class="panel"><div class="stack" style="gap:10px"><div class="eyebrow">Mis radios</div><ul class="results">${favs.map(t => stationRow(t, 'fav')).join('')}</ul></div></section>` : '';
  let body;
  if (radio.loading && !radio.list) body = `<div class="panel thinking"><div class="eq"><i></i><i></i><i></i><i></i><i></i></div><b>Sintonizando emisoras…</b></div>`;
  else if (radio.err) body = `<p class="note">${esc(radio.err)} <button class="link" data-act="r-retry">Reintentar</button></p>`;
  else if (radio.list && !radio.list.length) body = `<p class="note">No hay emisoras con esos filtros. Prueba otro género o «Todo el mundo».</p>`;
  else body = radio.list ? (radio.stale ? `<p class="note">El directorio de radios no responde ahora mismo: te enseño la última lista que guardé. <button class="link" data-act="r-retry">Reintentar</button></p>` : '') + `<ul class="results${radio.loading ? ' dim' : ''}">${radio.list.map(t => stationRow(t, 'all')).join('')}</ul>` : '';
  out.innerHTML = favHTML + body;
  markPlaying();
}
function playStation(id, which) {
  const list = which === 'fav' ? radio.favs.map(stationTrack) : (radio.list || []);
  const ids = list.map(t => t.id); const i = Math.max(0, ids.indexOf(id)); const t = trackById(id); if (!t) return;
  playQueue(ids.length ? ids : [id], i, { source: 'online', talk: dj.intros ? radioIntro(t) : null, talkKind: 'saludo' });
  if (t.uuid) rbGet('/json/url/' + t.uuid).catch(() => {});
}
function radioIntro(t) {
  const where = t.country ? `, desde ${t.country}` : '';
  const P = { energica: [`¡Sintonizamos ${t.title}${where}!`, `¡Cambio de dial! Esto es ${t.title}.`], tranquila: [`Te dejo con ${t.title}${where}.`, `Ahora suena ${t.title}.`], graciosa: [`Cambio de emisora: ${t.title}. Yo no cobro comisión.`, `Te paso con ${t.title}${where}. Sé buena con ellos.`], breve: [`${t.title}.`] }[dj.persona];
  return pick(P || [`Sintonizamos ${t.title}${where}.`, `Ahora, en directo: ${t.title}.`, `Nos vamos a ${t.title}${where}.`]);
}
let rSearchT = null;
$('#rQ').addEventListener('input', e => { clearTimeout(rSearchT); rSearchT = setTimeout(() => { radio.q = e.target.value.trim(); loadRadios(); }, 450); });
$('#rCC').addEventListener('change', e => { radio.cc = e.target.value; LS.set('rCC', radio.cc); loadRadios(); });
$('#rTags').addEventListener('click', e => { const b = e.target.closest('[data-rtag]'); if (!b) return; radio.tag = b.dataset.rtag; LS.set('rTag', radio.tag); loadRadios(); });

/* YouTube: the official embedded player, visible inside the app (YouTube does not allow audio-only or background play) */
const YT_RE = /(?:youtube\.com\/(?:watch\?(?:[^#\s]*&)?v=|embed\/|shorts\/|live\/|v\/)|youtu\.be\/|music\.youtube\.com\/watch\?(?:[^#\s]*&)?v=)([A-Za-z0-9_-]{11})/;
function ytIdFrom(text) { const s = String(text || '').trim(); const m = s.match(YT_RE); return m ? m[1] : (/^[A-Za-z0-9_-]{11}$/.test(s) ? s : null); }
const yt = { recent: LS.get('ytRecent', []), results: null, loading: false, err: '', key: () => LS.get('ytKey', '') };
async function ytInfo(id) { try { const j = await netJSON(`https://www.youtube.com/oembed?url=${encodeURIComponent('https://www.youtube.com/watch?v=' + id)}&format=json`, { timeout: 8000 }); return { title: String(j.title || '').slice(0, 140), author: String(j.author_name || '').slice(0, 80) }; } catch { return null; } }
/* A YouTube link → { id, list } (radio mixes and private lists can't be played outside YouTube: just the video) */
function ytLinkFrom(text) {
  const id = ytIdFrom(text); let list = ytListFrom(text);
  if (list && /^(RD|LL|WL|UL)/.test(list)) list = null;
  return id || list ? { id: id || '', list: list || '' } : null;
}
/* 3.2: videos play in the queue (this is kept for older callers) */
async function openYT(id, title, author) { if (id) { ytPlay({ id, title, author }, 'now'); openNP(); } }
async function openYTOld(id, title, author) {
  if (!id) return;
  if (!ONLINE) { window.open('https://www.youtube.com/watch?v=' + id, '_blank', 'noopener'); return; }
  if (!act().paused) fadePause();
  stopTalk();
  $('#ytTitle').textContent = title || 'YouTube';
  $('#ytAuthor').textContent = author || '';
  $('#ytOpen').href = 'https://www.youtube.com/watch?v=' + id;
  $('#ytFrame').innerHTML = `<iframe src="https://www.youtube.com/embed/${id}?autoplay=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=${encodeURIComponent(location.origin)}" title="${esc(title || 'Vídeo de YouTube')}" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>`;
  $('#ytOverlay').hidden = false;
  if (STANDALONE) { try { history.pushState({ yt: 1 }, '', location.hash || '#' + state.view); } catch {} }
  const rec = { id, title: title || '', author: author || '', ts: Date.now() };
  yt.recent = [rec, ...yt.recent.filter(x => x.id !== id)].slice(0, 12); LS.set('ytRecent', yt.recent);
  if (!title) ytInfo(id).then(info => { if (!info) return; if ($('#ytFrame iframe') && $('#ytOpen').href.endsWith(id)) { $('#ytTitle').textContent = info.title || 'YouTube'; $('#ytAuthor').textContent = info.author; } const r = yt.recent.find(x => x.id === id); if (r) { r.title = info.title; r.author = info.author; LS.set('ytRecent', yt.recent); renderYT(); } });
  renderYT();
}
function closeYT(fromPop) {
  if ($('#ytOverlay').hidden) return;
  $('#ytFrame').innerHTML = ''; $('#ytOverlay').hidden = true;
  if (STANDALONE && !fromPop && history.state && history.state.yt) { try { popSkip = true; history.back(); } catch {} }
}
async function ytSearch(q) {
  const key = yt.key();
  if (!key) { yt.results = null; yt.err = 'nokey'; renderYT(); return; }
  yt.loading = true; yt.err = ''; renderYT();
  try {
    const j = await netJSON(`https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=15&q=${encodeURIComponent(q)}&key=${encodeURIComponent(key)}`);
    yt.results = (j.items || []).filter(x => x.id && x.id.videoId).map(x => ({ id: x.id.videoId, title: decodeEntities(x.snippet?.title || ''), author: decodeEntities(x.snippet?.channelTitle || ''), thumb: x.snippet?.thumbnails?.medium?.url || '' }));
  } catch (e) { yt.results = []; yt.err = ytErrorText(e); }
  yt.loading = false; renderYT();
}
/* Google says exactly what is wrong with the key: turn it into something you can act on */
function ytErrorText(e) {
  if (!e || !e.status) return 'No he podido buscar en YouTube. ¿Tienes internet?';
  let d = e.data; if (typeof d === 'string') { try { d = JSON.parse(d); } catch {} }
  const er = d && d.error || {}, msg = String(er.message || ''), reasons = [...(er.errors || []).map(x => x && x.reason), ...(er.details || []).map(x => x && x.reason)].filter(Boolean).join(' ');
  const all = reasons + ' ' + msg;
  if (/keyInvalid|API_KEY_INVALID|API key not valid/i.test(all)) return 'Google dice que la clave no es válida: seguramente se copió con alguna letra cambiada. Cópiala otra vez desde la consola de Google con el botón de copiar (no a mano ni con Google Lens) y pégala aquí.';
  if (/accessNotConfigured|SERVICE_DISABLED|has not been used|is disabled/i.test(all)) return 'La clave está bien, pero falta activar «YouTube Data API v3» en ese proyecto de Google (Biblioteca › YouTube Data API v3 › Habilitar). Si acabas de activarla, espera 5 minutos.';
  if (/quotaExceeded|dailyLimitExceeded|rateLimitExceeded/i.test(all)) return 'Se han gastado las búsquedas gratuitas de hoy. Mañana vuelven. Mientras, puedes pegar enlaces o compartir vídeos desde YouTube.';
  if (/API_KEY_SERVICE_BLOCKED|ipRefererBlocked|API_KEY_ANDROID_APP_BLOCKED|API_KEY_HTTP_REFERRER_BLOCKED|blocked|forbidden/i.test(all)) return 'La clave tiene restricciones que bloquean a Órbita. En la consola de Google, edita la clave: en «Restricciones de aplicaciones» pon «Ninguna» y en «Restricciones de API» deja solo YouTube Data API v3.';
  return `YouTube ha rechazado la búsqueda (error ${e.status}${msg ? ': ' + msg.slice(0, 120) : ''}).`;
}
function decodeEntities(s) { const d = document.createElement('textarea'); d.innerHTML = s; return d.value; }
function ytRow(v) { const da = `data-id="${esc(v.id || '')}" data-list="${esc(v.list || '')}"`; return `<li class="result yt-row">${v.thumb ? `<img class="yt-thumb" src="${esc(v.thumb)}" alt="" loading="lazy">` : `<span class="yt-thumb ph" aria-hidden="true">▶</span>`}<div class="d-text"><b>${esc(v.title || (v.list && !v.id ? 'Lista de YouTube' : 'Vídeo de YouTube'))}</b><span>${esc(v.author || '')}</span></div><div class="row-wrap" style="gap:6px;flex-wrap:nowrap"><button class="btn small" data-act="yt-next" ${da}>Después</button><button class="btn small primary" data-act="yt-open" ${da}>Ver</button></div></li>`; }
function renderYT() {
  const out = $('#ytOut'); if (!out) return;
  $('#ytKeyBox').hidden = !ONLINE;
  $('#ytKeyIn').placeholder = yt.key() ? 'Clave guardada (escribe otra para cambiarla)' : 'AIza…';
  $('#ytKeyDel').hidden = !yt.key();
  if (!ONLINE) { out.innerHTML = `<div class="empty"><h3>YouTube dentro de Órbita</h3><p>En la app de Android puedes ver los vídeos aquí dentro. En esta versión, dentro de Claude, se abren en YouTube.</p></div>`; return; }
  let html = '';
  if (yt.loading) html += `<div class="panel thinking"><div class="eq"><i></i><i></i><i></i><i></i><i></i></div><b>Buscando en YouTube…</b></div>`;
  else if (yt.err === 'nokey') html += `<div class="infobox">Para buscar vídeos sin salir de Órbita hace falta una clave gratuita de YouTube (mira abajo). Sin clave puedes pegar un enlace o compartir el vídeo desde la app de YouTube con <b>Compartir › Órbita</b>. <a class="link" href="https://www.youtube.com/results?search_query=${encodeURIComponent($('#ytQ').value || '')}" target="_blank" rel="noopener">Buscar en YouTube</a></div>`;
  else if (yt.err) html += `<p class="note">${esc(yt.err)}</p>`;
  if (yt.results && yt.results.length) html += `<ul class="results">${yt.results.map(ytRow).join('')}</ul>`;
  if (yt.recent.length) html += `<section class="panel"><div class="stack" style="gap:10px"><div class="eyebrow">Vistos hace poco</div><ul class="results">${yt.recent.map(v => ytRow({ ...v, thumb: v.id ? `https://i.ytimg.com/vi/${v.id}/mqdefault.jpg` : '' })).join('')}</ul></div></section>`;
  out.innerHTML = html;
}
$('#ytForm').addEventListener('submit', e => { e.preventDefault(); const v = $('#ytQ').value.trim(); if (!v) return; const lk = ytLinkFrom(v); if (lk) { ytPlay(lk, 'now'); openNP(); $('#ytQ').value = ''; } else if (/https?:\/\//i.test(v)) toast('Ese enlace no es de un vídeo ni de una lista de YouTube.'); else ytSearch(v); });
$('#ytKeyForm').addEventListener('submit', e => { e.preventDefault(); const v = $('#ytKeyIn').value.replace(/\s+/g, ''); if (!v) return; if (!/^AIza[0-9A-Za-z_-]{35}$/.test(v)) { toast(`Esa clave no tiene la forma de una clave de Google (empieza por AIza y tiene 39 caracteres; esta tiene ${v.length}). Cópiala con el botón de copiar de la consola.`, 6000); return; } LS.set('ytKey', v); $('#ytKeyIn').value = ''; renderYT(); toast('Clave de YouTube guardada en este móvil'); const q = $('#ytQ').value.trim(); if (q && !ytIdFrom(q)) ytSearch(q); });
$('#ytKeyDel').onclick = () => { LS.set('ytKey', ''); renderYT(); toast('Clave de YouTube quitada'); };
$('#ytOverlay').addEventListener('click', e => { if (e.target.id === 'ytOverlay') closeYT(); });

/* ---- Where the video is shown ---- */
const ytOn = () => { const t = current(); return A === 3 && !!ytEl.src && !!t && t.kind === 'yt'; };
let ytPlaceRaf = 0, ytClearT = null;
function ytLayout() {
  const dock = $('#ytDock'); if (!dock) return;
  const on = ytOn(), npOpen = !$('#np').hidden;
  dock.hidden = !on;
  root.classList.toggle('yt-docked', on && !npOpen);
  $('#np').classList.toggle('yt-on', on);
  clearTimeout(ytClearT);
  /* once the music has taken over (after its short fade), YouTube's player leaves the page */
  if (!on && ytEl.src && A !== 3) ytClearT = setTimeout(() => { if (A !== 3 && ytEl.src) ytEl.src = ''; }, 3000);
  if (!on) return;
  const t = current();
  dock.classList.toggle('big', npOpen); dock.classList.toggle('mini', !npOpen);
  $('#ytDockTitle').textContent = t ? t.title : 'YouTube';
  $('#ytDockNote').hidden = !ytEl._noCtl;
  ytPlace();
}
function ytPlace() {
  cancelAnimationFrame(ytPlaceRaf);
  ytPlaceRaf = requestAnimationFrame(() => {
    const dock = $('#ytDock'); if (!dock || dock.hidden) return;
    if (dock.classList.contains('big')) {
      const r = $('#npVideo').getBoundingClientRect();
      Object.assign(dock.style, { left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px', bottom: 'auto' });
    } else {
      const pl = $('.player').getBoundingClientRect(), w = Math.min(window.innerWidth - 16, 520);
      Object.assign(dock.style, { left: Math.max(8, window.innerWidth - w - 8) + 'px', width: w + 'px', top: 'auto', height: 'auto', bottom: Math.max(8, window.innerHeight - pl.top + 8) + 'px' });
    }
  });
}
window.addEventListener('resize', () => ytPlace());
$('#np .np-inner').addEventListener('scroll', () => { if (ytOn()) ytPlace(); }, { passive: true });
/* Out of Órbita (another app, screen off): the video waits; back in Órbita, it goes on */
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { if (A === 3 && !ytEl.paused) { ytEl._auto = true; ytEl.pause(); } }
  else if (ytEl._auto) { ytEl._auto = false; if (ytOn()) resume(); }
});
ytEl.addEventListener('ytinfo', () => {
  const t = current(); if (!t || t.kind !== 'yt' || A !== 3) return;
  /* inside a YouTube list, each video brings its own title */
  if (t.ytList || !t.titled) { t.title = ytEl.videoTitle || t.title; t.artist = ytEl.videoAuthor || t.artist; t.titled = true; if (ytEl._vid) t.art = `https://i.ytimg.com/vi/${ytEl._vid}/hqdefault.jpg`; updatePlayer(); mediaSession(t); pushWidget(); ytLayout(); }
});

/* ---- Videos in the queue ---- */
function ytTrack(v) {
  const key = v.id ? v.id + (v.list ? '-' + v.list : '') : 'lista-' + v.list;
  const t = netTrack({
    id: 'yt:' + key, kind: 'yt', ytId: v.id || '', ytList: v.list || '', clipOf: v.clipOf || null,
    url: 'yt:' + (v.id || '') + (v.list ? '?list=' + v.list : ''),
    title: v.title || (v.list && !v.id ? 'Lista de YouTube' : 'Vídeo de YouTube'), artist: v.author || 'YouTube', titled: !!v.title,
    art: v.id ? `https://i.ytimg.com/vi/${v.id}/hqdefault.jpg` : ''
  });
  if (!v.title && v.id) ytInfo(v.id).then(info => {
    if (!info || t.titled) return;
    t.title = info.title || t.title; t.artist = info.author || t.artist; t.titled = true;
    const r = yt.recent.find(x => x.id === v.id && (x.list || '') === (v.list || '')); if (r) { r.title = t.title; r.author = t.artist; LS.set('ytRecent', yt.recent); renderYT(); }
    if (current() === t) { updatePlayer(); mediaSession(t); ytLayout(); }
    renderQueue();
  });
  return t;
}
function ytRemember(v) {
  const rec = { id: v.id || '', list: v.list || '', title: v.title || '', author: v.author || '', ts: Date.now() };
  yt.recent = [rec, ...yt.recent.filter(x => !(x.id === rec.id && (x.list || '') === rec.list))].slice(0, 12); LS.set('ytRecent', yt.recent);
}
function ytIntro(t) {
  if (t.clipOf) { const s = trackById(t.clipOf); return pick([`El videoclip de «${s ? s.title : t.title}». Disfrútalo.`, `Vamos con el videoclip${s ? ' de ' + s.title : ''}.`]); }
  if (t.ytList && !t.ytId) return pick(['Ponemos tu lista de YouTube.', 'Vamos con esa lista de YouTube.']);
  if (!t.titled) return pick(['Te pongo el vídeo que me has pasado.', 'Vamos con ese vídeo de YouTube.']);
  return pick([`Del YouTube: «${t.title}».`, `Vamos con un vídeo: «${t.title}».`, `Te pongo el vídeo: «${t.title}».`]);
}
/* how: 'now' (plays it, the rest of the queue stays after it) or 'next' (after the current song) */
function ytPlay(v, how = 'now') {
  if (!v || (!v.id && !v.list)) return;
  if (!ONLINE) { window.open(v.id ? 'https://www.youtube.com/watch?v=' + v.id : 'https://www.youtube.com/playlist?list=' + v.list, '_blank', 'noopener'); return; }
  const t = ytTrack(v); ytRemember(v); renderYT();
  if (how === 'next' && current()) {
    state.queue = state.queue.filter((id, i) => id !== t.id || i === state.qi);
    state.queue.splice(state.qi + 1, 0, t.id); renderQueue();
    toast(`Después de esta: ${t.titled ? '«' + t.title + '»' : 'el vídeo de YouTube'}`); haptic(8);
    return;
  }
  /* the words are chosen when she speaks (by then YouTube has said the video's title) */
  const talk = dj.intros && dj.voiceOn ? { text: ytIntro(t), kind: 'saludo', yt: t } : null;
  if (!current() || !state.queue.length) { playQueue([t.id], 0, { source: 'youtube', talk }); return; }
  state.queue.splice(state.qi + 1, 0, t.id); state.qi++;
  state.pendingTalk = talk;
  markManual();
  loadCurrent(true, act().paused ? 0 : MANUAL_XF);
}
/* ✕ on the small video: on with your music (or, if nothing comes after, the video leaves the queue) */
function ytClose() {
  const t = current(); if (!t || t.kind !== 'yt') return;
  if (state.qi + 1 < state.queue.length) { next(false); return; }
  ytEl.pause(); ytEl.src = '';
  state.queue.splice(state.qi, 1); state.qi = Math.max(0, Math.min(state.qi, state.queue.length - 1));
  A = 0;
  if (current()) loadCurrent(false); else { updatePlayer(); renderQueue(); }
  ytLayout(); syncNativeState(); pushWidget();
}

/* ---- The song's music video ----
   With a YouTube key it searches YouTube. Without one, it asks Wikidata (the free database behind Wikipedia), which
   knows the official video of many songs. If neither finds it, you search it on YouTube and share it with Órbita:
   it stays linked to that song. */
const ytClipKey = t => norm(t.artist || '') + '|' + norm(ytCleanTitle(t.title || ''));
function ytCleanTitle(s) { return String(s || '').replace(/\s*[([][^)\]]*(feat|ft\.|with|remaster|live|en vivo|version|versión|video|vídeo|audio|lyric|letra|official|oficial)[^)\]]*[)\]]/gi, '').replace(/\s+-\s+(remaster|live|en vivo|radio edit|single version).*$/i, '').trim(); }
function ytClipGet(t) { const m = LS.get('ytClips', {}); return Object.prototype.hasOwnProperty.call(m, ytClipKey(t)) ? m[ytClipKey(t)] : undefined; }
function ytClipSet(t, id) {
  const m = LS.get('ytClips', {}); m[ytClipKey(t)] = id || '';
  const keys = Object.keys(m); if (keys.length > 400) for (const k of keys.slice(0, keys.length - 400)) delete m[k];
  LS.set('ytClips', m);
}
const ytArtistMatch = (a, names) => { const A2 = norm(a); return names.some(n => { const N = norm(n); return N.length >= 3 && (A2.includes(N) || (A2.length >= 3 && N.includes(A2))); }); };
async function ytClipWikidata(t) {
  const W = 'https://www.wikidata.org/w/api.php?format=json&origin=*&', H = { 'Api-User-Agent': NET_UA }, title = ytCleanTitle(t.title);
  if (!title || !t.artist) return null;
  const s = await netJSON(W + 'action=query&list=search&srlimit=10&srsearch=' + encodeURIComponent(`${title} haswbstatement:P1651`), { headers: H, timeout: 9000 });
  const ids = ((s && s.query && s.query.search) || []).map(x => x && x.title).filter(x => /^Q\d+$/.test(x || ''));
  if (!ids.length) return null;
  const e = await netJSON(W + 'action=wbgetentities&props=claims|labels&languages=es|en&ids=' + ids.join('|'), { headers: H, timeout: 9000 });
  const ents = ids.map(id => e && e.entities && e.entities[id]).filter(Boolean);
  const claim = (en, p) => ((en.claims && en.claims[p]) || []).map(c => c && c.mainsnak && c.mainsnak.datavalue && c.mainsnak.datavalue.value).filter(Boolean);
  const labels = en => Object.values(en.labels || {}).map(l => l && l.value).filter(Boolean);
  const T = norm(title);
  const cands = ents.filter(en => claim(en, 'P1651').some(v => /^[A-Za-z0-9_-]{11}$/.test(v)) && labels(en).some(l => { const L = norm(ytCleanTitle(l)); return L === T || (T.length >= 4 && (L.startsWith(T) || T.startsWith(L)) && Math.min(L.length, T.length) / Math.max(L.length, T.length) > 0.6); }));
  if (!cands.length) return null;
  const perf = [...new Set(cands.flatMap(en => claim(en, 'P175').map(v => v && v.id).filter(x => /^Q\d+$/.test(x || ''))))].slice(0, 45);
  let names = {};
  if (perf.length) {
    const p = await netJSON(W + 'action=wbgetentities&props=labels&languages=es|en&ids=' + perf.join('|'), { headers: H, timeout: 9000 });
    for (const id of perf) names[id] = labels((p && p.entities && p.entities[id]) || {});
  }
  const hit = cands.find(en => ytArtistMatch(t.artist, claim(en, 'P175').map(v => v && v.id).flatMap(id => names[id] || [])));
  return hit ? claim(hit, 'P1651').find(v => /^[A-Za-z0-9_-]{11}$/.test(v)) : null;
}
async function ytClipSearch(t) {
  const key = yt.key(); if (!key) return null;
  const q = `${t.artist} ${ytCleanTitle(t.title)} official video`;
  const j = await netJSON(`https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=5&q=${encodeURIComponent(q)}&key=${encodeURIComponent(key)}`, { timeout: 9000 });
  const items = (j && j.items || []).filter(x => x && x.id && /^[A-Za-z0-9_-]{11}$/.test(x.id.videoId || ''));
  const T = norm(ytCleanTitle(t.title));
  const good = items.find(x => norm(decodeEntities(x.snippet && x.snippet.title || '')).includes(T)) || items[0];
  return good ? good.id.videoId : null;
}
async function ytClipFind(t) {
  try { const id = await ytClipSearch(t); if (id) return id; } catch {}
  try { return await ytClipWikidata(t); } catch { return null; }
}
let ytClipBusy = false;
async function ytClip(t) {
  if (!t || t.net || ytClipBusy) return;
  if (!ONLINE) { openExternal('https://www.youtube.com/results?search_query=' + encodeURIComponent(`${t.artist} ${t.title}`)); return; }
  let id = ytClipGet(t);
  if (!id) {
    ytClipBusy = true; renderClipBtn();
    toast('Buscando el videoclip…', 4000);
    try { id = await ytClipFind(t); } finally { ytClipBusy = false; renderClipBtn(); }
    if (current() !== t) return;
    if (id) ytClipSet(t, id);
  }
  if (!id) {
    yt.clipWait = { id: t.id, ts: Date.now() };
    toast('No he encontrado su videoclip. Búscalo en YouTube y compártelo con Órbita: lo dejaré guardado para esta canción.', 6500);
    setTimeout(() => openExternal('https://www.youtube.com/results?search_query=' + encodeURIComponent(`${t.artist} ${ytCleanTitle(t.title)}`)), 1800);
    return;
  }
  ytPlay({ id, title: `${ytCleanTitle(t.title)} (videoclip)`, author: t.artist, clipOf: t.id }, 'now');
}
/* A video shared right after asking for a song's music video becomes that song's video */
function ytClipFromShare(id) {
  const w = yt.clipWait; yt.clipWait = null;
  if (!w || Date.now() - w.ts > 20 * 60000) return null;
  const t = trackById(w.id); if (!t || t.net) return null;
  ytClipSet(t, id);
  return t;
}
function renderClipBtn() {
  const b = $('#npClipBtn'); if (!b) return;
  const t = current();
  b.hidden = !t || !!t.net;
  b.textContent = ytClipBusy ? 'Buscando…' : 'Videoclip';
  b.disabled = ytClipBusy;
}
$('#npClipBtn').onclick = () => ytClip(current());

/* Tabs inside Buscar */
let netTab = 'canciones';
function setNetTab(tab) {
  netTab = ['canciones', 'radios', 'youtube'].includes(tab) ? tab : 'canciones';
  $$('#netTabs button').forEach(b => b.setAttribute('aria-pressed', b.dataset.ntab === netTab));
  $('#netSongs').hidden = netTab !== 'canciones'; $('#netRadios').hidden = netTab !== 'radios'; $('#netYT').hidden = netTab !== 'youtube';
  if (netTab === 'radios') { renderRadios(); if (ONLINE && !radio.list && !radio.loading) loadRadios(); }
  if (netTab === 'youtube') renderYT();
}
$('#netTabs').addEventListener('click', e => { const b = e.target.closest('[data-ntab]'); if (b) setNetTab(b.dataset.ntab); });

function renderWishlist() {
  const w = state.wishlist.slice().sort((a, b) => b.addedAt - a.addedAt);
  $('#wishMeta').textContent = w.length ? `${w.length}` : '';
  $('#wishOut').innerHTML = w.length ? w.map(x => { const have = inLibrary(x.title, x.artist); return `<li class="result"><div class="d-text"><b style="${have ? 'text-decoration:line-through;opacity:.6' : ''}">${esc(x.title)}</b>${x.artist ? ' · ' + esc(x.artist) : ''}<span>${have ? 'Ya está en tu biblioteca' : esc(x.why || '')}</span></div><div class="links">${have ? `<button class="btn small primary" data-act="net-play" data-id="${have.id}">${ICON.playS}Escuchar</button>` : linksFor(x.title, x.artist)}<button class="icon-btn" data-act="wish-del" data-id="${x.id}" aria-label="Quitar">${ICON.trash}</button></div></li>`; }).join('') : `<li class="note">Aún no has guardado nada.</li>`;
}
$('#netForm').addEventListener('submit', e => { e.preventDefault(); runSearch($('#netQ').value); });
$('#netOut').addEventListener('click', e => { const b = e.target.closest('[data-netq]'); if (b) { $('#netQ').value = b.dataset.netq; runSearch(b.dataset.netq); } });

/* ---------- Your voice ---------- */
async function analyzeClip(blob) {
  const out = { start: 0, end: 0, duration: 0, peaks: [] };
  try {
    const buf = await blob.arrayBuffer();
    const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    const ab = await new OAC(1, 1, 44100).decodeAudioData(buf);
    const sr = ab.sampleRate, n = ab.length, chans = [...Array(ab.numberOfChannels)].map((_, i) => ab.getChannelData(i));
    const amp = i => { let m = 0; for (const c of chans) { const v = Math.abs(c[i]); if (v > m) m = v; } return m; };
    let peak = 0; for (let i = 0; i < n; i += 64) peak = Math.max(peak, amp(i));
    const th = Math.max(0.015, peak * 0.08);
    let s = 0; while (s < n && amp(s) < th) s += 32;
    let e = n - 1; while (e > s && amp(e) < th) e -= 32;
    out.duration = n / sr; out.start = Math.max(0, s / sr - 0.06); out.end = Math.min(out.duration, e / sr + 0.18);
    if (out.end - out.start > 15) out.end = out.start + 15;
    const bars = 18, step = Math.floor((e - s) / bars) || 1;
    for (let b = 0; b < bars; b++) { let m = 0; for (let i = s + b * step; i < s + (b + 1) * step && i < n; i += 16) m = Math.max(m, amp(i)); out.peaks.push(Math.round((m / (peak || 1)) * 100) / 100); }
  } catch { out.duration = await probeDuration(blob); out.end = 0; }
  return out;
}
async function importClips(files, slot) {
  const list = [...files].filter(isAudio);
  if (!list.length) { toast('Elige un archivo de audio con tu voz.'); return; }
  for (const f of list) {
    const a = await analyzeClip(f);
    const n = state.clips.filter(c => c.slot === slot).length + 1;
    const c = { id: uid('v'), slot, label: `Toma ${n}`, blob: f, start: a.start, end: a.end, duration: a.duration, peaks: a.peaks, addedAt: Date.now() };
    state.clips.push(c); await idbPut('voice', c);
  }
  renderVoice();
  toast(list.length === 1 ? 'Grabación guardada y recortada' : `${list.length} grabaciones guardadas`);
  if (dj.who === 'nova') { setWho('mix'); toast('Ahora hablaréis Nova y tú por turnos. Cámbialo arriba si quieres.', 3600); }
}
function renderVoice() {
  $('#cntVoice').textContent = state.clips.length;
  $('#voiceMeta').textContent = state.clips.length ? `${state.clips.length} ${state.clips.length === 1 ? 'grabación' : 'grabaciones'}` : '';
  $$('#whoSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.who === dj.who));
  $('#slotsOut').innerHTML = SLOTS.map(s => {
    const takes = state.clips.filter(c => c.slot === s.id);
    return `<article class="panel slot-card"><div class="row-wrap"><h3>${esc(s.name)}</h3><span class="spacer"></span><span class="pill ${takes.length ? 'mine' : ''}">${takes.length} ${takes.length === 1 ? 'toma' : 'tomas'}</span></div>
      <p class="ex">${esc(s.ex)}</p>
      ${takes.length ? `<ul class="takes">${takes.map(c => `<li class="take"><button class="icon-btn" data-act="clip-play" data-id="${c.id}" aria-label="Escuchar ${esc(c.label)}">${ICON.playS}</button><span class="wave" aria-hidden="true">${(c.peaks && c.peaks.length ? c.peaks : [.4,.7,.5,.9,.6,.3]).map(p => `<i style="height:${Math.max(3, Math.round(p * 22))}px"></i>`).join('')}</span><span class="t-title">${esc(c.label)}</span><span class="mono">${(Math.max(0, (c.end || c.duration) - (c.start || 0))).toFixed(1)} s</span><button class="icon-btn" data-act="clip-rename" data-id="${c.id}" aria-label="Renombrar">${ICON.edit}</button><button class="icon-btn" data-act="clip-del" data-id="${c.id}" aria-label="Borrar">${ICON.trash}</button></li>`).join('')}</ul>` : ''}
      <div class="slot-btns">${canRecord ? `<button class="btn small rec ${takes.length ? '' : 'primary'}${rec && rec.target === s.id ? ' on' : ''}" data-rec="${s.id}"><span class="rec-dot"></span><span class="rec-txt">${rec && rec.target === s.id ? 'Detener' : takes.length ? 'Grabar otra toma' : 'Grabar'}</span></button>` : ''}<label class="btn small ${canRecord || takes.length ? '' : 'primary'}" tabindex="0"><input type="file" accept="audio/*" ${canRecord ? '' : 'capture="user"'} multiple hidden data-slot="${s.id}">${canRecord ? 'Subir archivo' : takes.length ? 'Añadir otra toma' : 'Grabar o subir'}</label></div>
    </article>`;
  }).join('');
  $('#mineRec').hidden = !canRecord; $('#mineTip').hidden = !canRecord;
  $('#mineLabel span').textContent = canRecord ? 'Subir grabación' : 'Grabar o subir canción';
  $('#mineLabel').classList.toggle('primary', !canRecord);
  const mine = state.tracks.filter(t => t.mine).length;
  $('#mineCount').textContent = mine ? `${mine} ${mine === 1 ? 'canción tuya' : 'canciones tuyas'} en la biblioteca` : '';
}
/* Built-in voice recorder (installed app): live level, auto-stop, then trimmed like any upload */
let rec = null;
function pickMime() { for (const m of ['audio/webm;codecs=opus','audio/mp4','audio/webm','audio/ogg;codecs=opus']) { try { if (MediaRecorder.isTypeSupported(m)) return m; } catch {} } return ''; }
async function toggleRec(target) {
  if (rec) { const same = rec.target === target; stopRec(); if (same) return; return; }
  const mine = target === 'mine';
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: !mine, noiseSuppression: !mine, autoGainControl: true } }); }
  catch (e) { toast(e && e.name === 'NotAllowedError' ? 'Sin permiso de micrófono. Actívalo en Ajustes › App y permisos.' : 'No he podido usar el micrófono.', 4200); renderPerms(); return; }
  if (!act().paused) fadePause(); stopTalk();
  const mime = pickMime();
  let mr; try { mr = new MediaRecorder(stream, mime ? { mimeType: mime, audioBitsPerSecond: mine ? 192000 : 96000 } : undefined); } catch { mr = new MediaRecorder(stream); }
  rec = { mr, stream, chunks: [], target, t0: Date.now(), max: mine ? 600 : 15, lvl: 0 };
  try {
    const AC = window.AudioContext || window.webkitAudioContext; const c = new AC(); const an = c.createAnalyser(); an.fftSize = 512;
    c.createMediaStreamSource(stream).connect(an); rec.ctx = c; rec.an = an; rec.buf = new Uint8Array(an.fftSize);
  } catch {}
  mr.ondataavailable = e => { if (e.data && e.data.size) rec && rec.chunks.push(e.data); };
  mr.onstop = onRecStop;
  mr.start(250);
  rec.timer = setInterval(updateRecUI, 120);
  if (mine) setView('voz'); else renderVoice();
  updateRecUI();
}
function stopRec() { if (rec && rec.mr.state !== 'inactive') { try { rec.mr.stop(); } catch { onRecStop(); } } }
function updateRecUI() {
  if (!rec) return;
  const secs = (Date.now() - rec.t0) / 1000;
  if (rec.an) { rec.an.getByteTimeDomainData(rec.buf); let m = 0; for (const v of rec.buf) m = Math.max(m, Math.abs(v - 128)); rec.lvl = rec.lvl * 0.6 + (m / 128) * 0.4; }
  $$(`[data-rec="${rec.target}"]`).forEach(b => { b.classList.add('on', 'primary'); b.style.setProperty('--lvl', Math.min(1, rec.lvl * 2.5).toFixed(2)); const t = b.querySelector('.rec-txt'); if (t) t.textContent = `Detener · ${fmt(secs)}`; });
  if (secs >= rec.max) stopRec();
}
async function onRecStop() {
  const r = rec; if (!r) return; rec = null; clearInterval(r.timer);
  r.stream.getTracks().forEach(t => t.stop()); try { r.ctx && r.ctx.close(); } catch {}
  $$(`[data-rec="${r.target}"]`).forEach(b => { b.classList.remove('on'); b.style.removeProperty('--lvl'); });
  const type = (r.mr.mimeType || 'audio/webm').split(';')[0];
  const ext = type.includes('mp4') ? 'm4a' : type.includes('ogg') ? 'ogg' : 'webm';
  const blob = new Blob(r.chunks, { type });
  const secs = (Date.now() - r.t0) / 1000;
  renderVoice();
  if (blob.size < 1500 || secs < 0.6) { toast('La grabación ha salido vacía. Mantén el botón un poco más.'); return; }
  if (r.target === 'mine') {
    const d = new Date(); const name = `Mi versión ${d.toLocaleDateString('es-ES')} ${pad(d.getHours())}.${pad(d.getMinutes())}.${ext}`;
    await importFiles([new File([blob], name, { type })], { mine: true, duration: secs });
  } else await importClips([new File([blob], `toma.${ext}`, { type })], r.target);
}
$('#slotsOut').addEventListener('change', e => { const inp = e.target.closest('input[type=file][data-slot]'); if (inp && inp.files.length) { importClips(inp.files, inp.dataset.slot); inp.value = ''; } });
$('#mineInput').addEventListener('change', e => { if (e.target.files.length) importFiles(e.target.files, { mine: true }); e.target.value = ''; });
document.addEventListener('keydown', e => { const l = e.target.closest && e.target.closest('label.btn[tabindex]'); if (l && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); (l.querySelector('input[type=file]') || (l.htmlFor && document.getElementById(l.htmlFor)))?.click(); } });

/* ---------- Personalisation: colour, background, glow, corners, text, home screen ---------- */
const LOOK = {
  bg: LS.get('lkBg', 'aurora'), glow: LS.get('lkGlow', 1), round: LS.get('lkRound', 'redondo'), text: LS.get('lkText', 'm'), titles: LS.get('lkTitles', 'orbita'),
  ticker: LS.get('lkTicker', true), foryou: LS.get('lkForYou', true), mixer: LS.get('lkMixer', true),
  start: LS.get('startView', 'inicio'), intro: !LS.get('introOff', false), haptics: LS.get('haptics', true),
  hue: LS.get('customHue', 200), sat: LS.get('customSat', 0.9)
};
const LOOK_OPTS = {
  bg: [['aurora', 'Aurora'], ['quieto', 'Quieto'], ['portada', 'Portada'], ['negro', 'Negro puro']],
  round: [['redondo', 'Redondas'], ['suave', 'Suaves'], ['recto', 'Rectas']],
  text: [['s', 'Pequeño'], ['m', 'Normal'], ['l', 'Grande']],
  titles: [['orbita', 'Órbita'], ['limpia', 'Limpia'], ['retro', 'Retro']],
  start: [['inicio', 'Ahora'], ['biblioteca', 'Música'], ['buscar', 'Buscar'], ['tu', 'Tú']]
};
function applyLook() {
  root.dataset.bg = LOOK.bg; root.dataset.titles = LOOK.titles;
  root.style.setProperty('--round', { redondo: 1, suave: 0.55, recto: 0.18 }[LOOK.round] ?? 1);
  root.style.setProperty('--tk', { s: 0.92, m: 1, l: 1.1 }[LOOK.text] ?? 1);
  root.style.setProperty('--glowK', LOOK.glow);
  root.classList.toggle('no-ticker', !LOOK.ticker); root.classList.toggle('no-foryou', !LOOK.foryou); root.classList.toggle('no-mixer', !LOOK.mixer);
  updateBgCover();
}
function updateBgCover() {
  const el = $('#bgCover'); if (!el) return;
  const u = LOOK.bg === 'portada' ? coverUrl(current(), 'full') : null;
  el.style.backgroundImage = u ? `url("${u}")` : '';
}
function haptic(p = 8) { if (NATIVE && LOOK.haptics && navigator.vibrate) { try { navigator.vibrate(p); } catch {} } }
function segHTML(key, cur) { return LOOK_OPTS[key].map(([id, n]) => `<button data-look="${key}" data-v="${id}" aria-pressed="${cur === id}">${esc(n)}</button>`).join(''); }
function renderLook() {
  const box = $('#lookOut'); if (!box) return;
  $('#lkBg').innerHTML = segHTML('bg', LOOK.bg); $('#lkRound').innerHTML = segHTML('round', LOOK.round);
  $('#lkText').innerHTML = segHTML('text', LOOK.text); $('#lkTitles').innerHTML = segHTML('titles', LOOK.titles);
  $('#lkStart').innerHTML = segHTML('start', LOOK.start);
  $('#lkGlowIn').value = LOOK.glow; setRange($('#lkGlowIn')); $('#lkGlowVal').textContent = Math.round(LOOK.glow * 100) + ' %';
  setToggle($('#tTicker'), LOOK.ticker); setToggle($('#tForYou'), LOOK.foryou); setToggle($('#tMixer'), LOOK.mixer);
  setToggle($('#tIntro'), LOOK.intro); setToggle($('#tHaptics'), LOOK.haptics);
  $('#tHaptics').hidden = !NATIVE;
  const custom = state.theme === 'custom';
  $('#customTone').hidden = !custom;
  $('#hueIn').value = LOOK.hue; $('#satIn').value = LOOK.sat; setRange($('#hueIn')); setRange($('#satIn'));
}
$('#lookOut').addEventListener('click', e => {
  const b = e.target.closest('[data-look]'); if (!b) return;
  const k = b.dataset.look, v = b.dataset.v;
  LOOK[k] = v;
  LS.set({ bg: 'lkBg', round: 'lkRound', text: 'lkText', titles: 'lkTitles', start: 'startView' }[k], v);
  applyLook(); renderLook(); haptic(6);
  if (k === 'start') toast(`Órbita se abrirá en «${LOOK_OPTS.start.find(x => x[0] === v)[1]}»`);
});
$('#lkGlowIn').addEventListener('input', e => { LOOK.glow = +e.target.value; LS.set('lkGlow', LOOK.glow); applyLook(); setRange(e.target); $('#lkGlowVal').textContent = Math.round(LOOK.glow * 100) + ' %'; });
const lookToggle = (id, key, ls, after) => { $(id).onclick = () => { LOOK[key] = !LOOK[key]; if (key === 'intro') LS.set('introOff', !LOOK.intro); else LS.set(ls, LOOK[key]); setToggle($(id), LOOK[key]); applyLook(); if (after) after(); }; };
lookToggle('#tTicker', 'ticker', 'lkTicker');
lookToggle('#tForYou', 'foryou', 'lkForYou', () => renderForYou());
lookToggle('#tMixer', 'mixer', 'lkMixer');
lookToggle('#tIntro', 'intro', null, () => toast(LOOK.intro ? 'La animación de entrada vuelve la próxima vez que abras Órbita' : 'Órbita se abrirá sin animación de entrada'));
lookToggle('#tHaptics', 'haptics', 'haptics', () => haptic(12));
['hueIn', 'satIn'].forEach(id => $('#' + id).addEventListener('input', e => {
  const v = +e.target.value; if (id === 'hueIn') { LOOK.hue = v; LS.set('customHue', v); } else { LOOK.sat = v; LS.set('customSat', v); }
  setRange(e.target); applyTheme(); renderThemes();
}));

/* ---------- Themes, hero mode, animations, storage ---------- */
let hue = 6, sat = 1;
function themeTone() {
  const th = THEMES.find(x => x.id === state.theme) || THEMES[0]; const m = getMoment();
  if (th.id === 'auto') return { h: m.franja.hue, s: 1 };
  if (th.id === 'custom') return { h: LOOK.hue, s: LOOK.sat };
  if (th.id === 'cover') { const t = current(); if (t && typeof t.coverHue === 'number') return { h: t.coverHue, s: t.coverSat ?? 1 }; if (t) return { h: phHue(t), s: .9 }; return { h: m.franja.hue, s: 1 }; }
  return { h: th.hue, s: th.sat };
}
function applyTheme() {
  const { h, s } = themeTone();
  if (h !== hue || s !== sat || !applyTheme.done) { hue = h; sat = s; root.style.setProperty('--accent-h', h); root.style.setProperty('--sat', s); grooveCache = null; applyTheme.done = true; }
}
function renderThemes() {
  const m = getMoment();
  $('#themesOut').innerHTML = THEMES.map(th => {
    let sw;
    if (th.id === 'auto') sw = `linear-gradient(90deg,${FRANJAS.map(f => `hsl(${f.hue},90%,60%)`).join(',')})`;
    else if (th.id === 'cover') { const u = coverUrl(current()); sw = u ? `center/cover url('${u}')` : `conic-gradient(from 0deg,hsl(0,85%,60%),hsl(120,85%,50%),hsl(240,85%,60%),hsl(360,85%,60%))`; }
    else { const hh = th.id === 'custom' ? LOOK.hue : th.hue, ss = th.id === 'custom' ? LOOK.sat : th.sat; sw = `linear-gradient(110deg,hsl(${hh},${Math.round(ss * 96)}%,64%),hsl(${hh - 45},${Math.round(ss * 92)}%,60%) 60%,#0d0a18)`; }
    return `<button class="theme" data-theme-id="${th.id}" aria-pressed="${state.theme === th.id}"><span class="swatch" style="background:${sw}"></span><b>${esc(th.name)}</b><span>${esc(th.id === 'auto' ? `${th.desc} (ahora: ${m.franja.phrase})` : th.desc)}</span></button>`;
  }).join('');
}
$('#themesOut').addEventListener('click', e => { const b = e.target.closest('[data-theme-id]'); if (!b) return; state.theme = b.dataset.themeId; LS.set('theme', state.theme); applyTheme(); renderThemes(); renderLook(); if (state.theme === 'cover' && !current()) toast('Pon una canción y la app tomará el color de su portada'); });
function setHeroMode(m) {
  state.heroMode = m; LS.set('heroMode', m);
  $('#vinylWrap').hidden = m !== 'vinyl'; $('#artWrap').hidden = m !== 'art'; $('#vizWrap').hidden = m !== 'viz';
  setTimeout(vizKick, 30);
  $$('#heroSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.hero === m));
  if (m === 'art') $('#artWrap').innerHTML = bigArtHTML(current()); else sizeVinyl();
}
$('#heroSeg').addEventListener('click', e => { const b = e.target.closest('[data-hero]'); if (b) setHeroMode(b.dataset.hero); });
function setAnim(a) { state.anim = a; LS.set('anim', a); root.classList.toggle('lite', a === 'lite'); $$('#animSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.anim === a)); }
$('#animSeg').addEventListener('click', e => { const b = e.target.closest('[data-anim]'); if (b) setAnim(b.dataset.anim); });
async function updateStorage() {
  if (NATIVE) {
    $('#btnPersist').hidden = true; $('#persistTxt').textContent = 'Tus canciones se guardan dentro de la app: Android no las borra. Solo se pierden si desinstalas Órbita o borras sus datos.';
  }
  try {
    const est = await navigator.storage?.estimate?.(); if (!est) { $('#storeTxt').textContent = 'Este navegador no informa del espacio.'; $('#btnPersist').hidden = true; return; }
    $('#storeTxt').textContent = `Tu música ocupa ${gb(est.usage || 0)} de ${gb(est.quota || 0)} disponibles en este navegador`;
    $('#storeBar').style.width = Math.min(100, (est.usage || 0) / (est.quota || 1) * 100) + '%';
    if (NATIVE) return;
    const p = await navigator.storage?.persisted?.();
    $('#persistTxt').textContent = p ? 'Protegidas: el navegador no las borrará para liberar espacio.' : 'Sin proteger: si el dispositivo se queda sin espacio, el navegador podría borrarlas.';
    $('#btnPersist').hidden = !!p;
  } catch { $('#storeTxt').textContent = 'No he podido leer el espacio usado.'; }
}
/* ---------- App, permissions, installation ---------- */
let installPrompt = null;
window.addEventListener('beforeinstallprompt', e => { e.preventDefault(); installPrompt = e; renderPerms(); });
window.addEventListener('appinstalled', () => { installPrompt = null; toast('Órbita instalada en tu dispositivo'); renderPerms(); });
async function micState() { try { const s = await navigator.permissions.query({ name: 'microphone' }); return s.state; } catch { return 'prompt'; } }
/* Updates for the native app: new APKs are published as GitHub releases by the build workflow */
async function checkUpdate(manual) {
  if (!NATIVE || !APP_CFG.repo) { if (manual) toast('Esta versión no tiene configurado un repositorio de actualizaciones.'); return; }
  try {
    const r = await fetch(`https://api.github.com/repos/${APP_CFG.repo}/releases/latest`, { headers: { Accept: 'application/vnd.github+json' }, cache: 'no-store' });
    if (!r.ok) throw new Error(String(r.status));
    const j = await r.json();
    const build = +((String(j.tag_name || '').match(/(\d+)$/) || [])[1] || 0);
    const apk = (j.assets || []).find(a => /\.apk$/i.test(a.name));
    LS.set('lastUpdateCheck', Date.now());
    state.update = build > (+APP_CFG.build || 0) && apk ? { version: String(j.tag_name).replace(/^v/, ''), url: apk.browser_download_url, notes: String(j.body || '').split('\n')[0].slice(0, 140) } : null;
    renderUpdate(); if (state.view === 'ajustes') renderPerms();
    if (manual && !state.update) toast(`Tienes la última versión (${APP_CFG.version || ''})`);
  } catch { if (manual) toast('No he podido comprobar si hay versión nueva. ¿Tienes internet?'); }
}
function renderUpdate() {
  const b = $('#updBanner'), u = state.update;
  b.hidden = !u || LS.get('skipUpdate', '') === (u && u.version);
  if (!u) return;
  b.innerHTML = `<div class="d-text"><b>Nueva versión de Órbita: ${esc(u.version)}</b><span>${esc(u.notes || 'Descárgala e instálala encima: tus canciones y ajustes se conservan.')}</span></div><a class="btn small primary" href="${esc(u.url)}" target="_blank" rel="noopener">Descargar</a><button class="btn small" data-perm="upd-later">Luego</button>`;
}
async function renderPerms() {
  const rows = [];
  if (NATIVE) {
    rows.push({ t: `Órbita ${APP_CFG.version || ''}`, d: state.update ? `Hay una versión nueva (${state.update.version}). Descárgala e instálala encima: no pierdes nada.` : 'App nativa: todo funciona dentro del móvil, sin navegador. Las actualizaciones llegan desde tu GitHub.', s: state.update ? ['no', 'Nueva versión'] : ['ok', 'Instalada'], btn: ['update', state.update ? 'Ver' : 'Buscar actualización'] });
    const ms = canRecord ? await micState() : 'unsupported';
    rows.push({ t: 'Micrófono', d: ms === 'denied' ? 'Bloqueado. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Micrófono.' : 'Para grabar tus frases de DJ y tus canciones.', s: ms === 'granted' ? ['ok', 'Permitido'] : ms === 'denied' ? ['no', 'Bloqueado'] : null, btn: ms !== 'granted' && ms !== 'denied' ? ['mic', 'Permitir'] : null });
    rows.push({ t: 'Widget en la pantalla de inicio', d: 'Mantén pulsado un hueco libre de la pantalla de inicio › Widgets › Órbita. Muestra la canción con su portada y los botones para pausar y saltar.', s: ['ok', 'Disponible'] });
    rows.push({ t: 'Compartir con Órbita', d: 'En YouTube, WhatsApp o el gestor de archivos pulsa Compartir › Órbita: los vídeos se abren aquí dentro y las canciones entran en tu biblioteca.', s: ['ok', 'Activo'] });
    rows.push({ t: `Voz de ${dj.name}`, d: nativeTTS ? 'Usa el motor de voz de Android. Para más voces: Ajustes del móvil › «Texto a voz» › motor de Google › instalar datos de voz en español.' : 'No se ha encontrado el motor de voz del móvil.', s: nativeTTS ? ['ok', 'Activa'] : ['no', 'No disponible'] });
  } else if (STANDALONE) {
    rows.push(isInstalled() ? { t: 'App instalada', d: 'Órbita funciona como una app: pantalla completa, icono propio y sin conexión.', s: ['ok', 'Instalada'] }
      : installPrompt ? { t: 'Instalar Órbita', d: 'Añádela a tu pantalla de inicio como una app más.', btn: ['install', 'Instalar'] }
      : { t: 'Instalar Órbita', d: 'En Chrome para Android: menú ⋮ › «Instalar aplicación» (o «Añadir a pantalla de inicio»).', s: ['', 'Desde el menú'] });
    const ms = canRecord ? await micState() : 'unsupported';
    rows.push({ t: 'Micrófono', d: ms === 'denied' ? 'Bloqueado. Actívalo en los ajustes del sistema: Apps › Órbita (o Chrome) › Permisos › Micrófono.' : 'Para grabar tus frases de DJ y tus canciones desde la app.', s: ms === 'granted' ? ['ok', 'Permitido'] : ms === 'denied' ? ['no', 'Bloqueado'] : ms === 'unsupported' ? ['no', 'No disponible'] : null, btn: ms === 'prompt' ? ['mic', 'Permitir'] : null });
    rows.push({ t: 'Compartir canciones a Órbita', d: 'Con la app instalada, en tu gestor de archivos, WhatsApp o Telegram pulsa Compartir › Órbita y la canción entra en tu biblioteca.', s: [isInstalled() ? 'ok' : '', isInstalled() ? 'Activo' : 'Al instalar'] });
  } else {
    rows.push({ t: 'Versión dentro de Claude', d: 'Aquí no hay acceso al micrófono ni instalación. Para eso usa el paquete de la app para Android: tiene grabadora, funciona sin conexión y se instala con su icono.', s: ['', 'Navegador'] });
  }
  rows.push({ t: 'Controles en la pantalla de bloqueo', d: NATIVE ? 'Sigue sonando con la pantalla apagada; pausa, salta canciones y ve la portada desde la notificación, la pantalla de bloqueo o los auriculares. Si se corta al bloquear el móvil, quita el ahorro de batería a Órbita.' : 'Pausa, salta canciones y ve la portada desde la pantalla de bloqueo, los auriculares o el reloj.', s: ['ok', 'Activo'] });
  if (nativeLib) rows.push({ t: 'Música del móvil', d: devLib.state === 'granted' ? 'Órbita lee tus canciones directamente del almacenamiento, sin copiarlas. Solo lectura: nunca modifica ni borra tus archivos.' : devLib.state === 'denied' ? 'Bloqueado. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio.' : 'Para reproducir todas las canciones que ya tienes en el móvil.', s: devLib.state === 'granted' ? ['ok', 'Permitido'] : devLib.state === 'denied' ? ['no', 'Bloqueado'] : null, btn: devLib.state === 'granted' ? null : ['dev', 'Permitir'] });
  else rows.push({ t: 'Archivos de música', d: 'Eliges tú qué canciones o carpetas añadir; la app no lee nada más de tu almacenamiento.', s: ['ok', 'Por selección'] });
  $('#permsOut').innerHTML = rows.map(r => `<li class="perm"><div class="d-text"><b>${esc(r.t)}</b><span>${esc(r.d)}</span></div>${r.s ? `<span class="state ${r.s[0]}">${esc(r.s[1])}</span>` : ''}${r.btn ? `<button class="btn small primary" data-perm="${r.btn[0]}">${esc(r.btn[1])}</button>` : ''}</li>`).join('');
  $('#aiKeyBox').hidden = !STANDALONE || !!aiFn;
  if (STANDALONE) $('#keyIn').placeholder = apiKey() ? 'Clave guardada (escribe otra para cambiarla)' : 'sk-ant-…';
  $('#keyDel').hidden = !apiKey();
}
document.addEventListener('click', async e => {
  const r = e.target.closest('[data-rec]'); if (r) { toggleRec(r.dataset.rec); return; }
  const p = e.target.closest('[data-perm]'); if (!p) return;
  if (p.dataset.perm === 'dev') { connectDevice(); return; }
  if (p.dataset.perm === 'update') { if (state.update) { setView('inicio'); LS.set('skipUpdate', ''); renderUpdate(); } else checkUpdate(true); return; }
  if (p.dataset.perm === 'upd-later') { if (state.update) LS.set('skipUpdate', state.update.version); renderUpdate(); return; }
  if (p.dataset.perm === 'install' && installPrompt) { installPrompt.prompt(); try { await installPrompt.userChoice; } catch {} installPrompt = null; renderPerms(); }
  if (p.dataset.perm === 'mic') { try { const s = await navigator.mediaDevices.getUserMedia({ audio: true }); s.getTracks().forEach(t => t.stop()); toast('Micrófono permitido'); } catch { toast('Has bloqueado el micrófono. Puedes activarlo en los ajustes del sistema.', 4200); } renderPerms(); }
});
$('#keyForm').addEventListener('submit', e => { e.preventDefault(); const v = $('#keyIn').value.trim(); if (!v) return; if (!/^sk-ant-/.test(v)) { toast('Esa clave no parece de la API de Claude (empieza por sk-ant-).', 4000); return; } LS.set('apiKey', v); $('#keyIn').value = ''; refreshAi(); renderPerms(); toast('Clave guardada: la IA de Nova está lista'); });
$('#keyDel').onclick = () => { LS.set('apiKey', ''); refreshAi(); renderPerms(); toast('Clave quitada'); };
/* Songs shared from other apps arrive through the service worker */
async function importShared() {
  const qs = new URLSearchParams(location.search); if (!qs.has('shared')) return;
  try { history.replaceState(null, '', location.pathname + '#biblioteca'); } catch {}
  try {
    const c = await caches.open('orbita-share'); const keys = await c.keys(); const files = [];
    for (const k of keys) { const res = await c.match(k); if (res) { const b = await res.blob(); files.push(new File([b], decodeURIComponent(res.headers.get('x-name') || 'cancion'), { type: b.type || res.headers.get('content-type') || '' })); } await c.delete(k); }
    if (files.length) { setView('biblioteca'); importFiles(files); } else toast('No ha llegado ninguna canción. Prueba a compartirla otra vez.');
  } catch { toast('No he podido recibir la canción compartida.'); }
}
function runShortcut() {
  const a = new URLSearchParams(location.search).get('action'); if (!a) return;
  try { history.replaceState(null, '', location.pathname + (location.hash || '#inicio')); } catch {}
  setView('inicio');
  if (a === 'ruleta') spinRoulette(); else if (a === 'radio') $('#btnRadio').click();
}
$('#btnPersist').onclick = async () => { try { const ok = await navigator.storage?.persist?.(); toast(ok ? 'Canciones protegidas' : 'El navegador no ha concedido la protección (suele concederla si usas la app a menudo)', 3600); } catch {} updateStorage(); };

/* ---------- Home-screen widget, backups and sharing into Órbita ---------- */
let widgetT = null, widgetSig = '';
const widgetArt = new Map();
function roundedArt(t) {
  if (!t || t.net) return Promise.resolve('');
  const blob = t.thumb || t.cover; if (!blob) return Promise.resolve('');
  if (widgetArt.has(t.id)) return widgetArt.get(t.id);
  const p = (async () => {
    try {
      const bmp = await createImageBitmap(blob); const S = 144, r = 22;
      const c = document.createElement('canvas'); c.width = c.height = S; const x = c.getContext('2d');
      x.beginPath(); x.moveTo(r, 0); x.arcTo(S, 0, S, S, r); x.arcTo(S, S, 0, S, r); x.arcTo(0, S, 0, 0, r); x.arcTo(0, 0, S, 0, r); x.closePath(); x.clip();
      const s = Math.max(S / bmp.width, S / bmp.height); x.drawImage(bmp, (S - bmp.width * s) / 2, (S - bmp.height * s) / 2, bmp.width * s, bmp.height * s);
      try { bmp.close(); } catch {}
      return c.toDataURL('image/png');
    } catch { return ''; }
  })();
  widgetArt.set(t.id, p); if (widgetArt.size > 30) widgetArt.delete(widgetArt.keys().next().value);
  return p;
}
function pushWidget() {
  if (!nativeLib) return;
  clearTimeout(widgetT);
  widgetT = setTimeout(async () => {
    const t = current(), playing = !!t && !act().paused;
    const payload = { title: t ? t.title : 'Órbita', artist: t ? t.artist : '', playing };
    const sig = JSON.stringify(payload) + (t ? t.id : '');
    if (sig === widgetSig) return; widgetSig = sig;
    const art = t ? await roundedArt(t) : '';
    try { await nativeLib.updateWidget({ ...payload, art }); } catch {}
  }, 300);
}
if (nativeLib) {
  try { nativeLib.addListener('widget', e => { const a = e && e.action; haptic(6); if (a === 'toggle') togglePlay(); else if (a === 'next') next(false); else if (a === 'prev') prev(); }); } catch {}
  try { nativeLib.addListener('share', e => handleShare(e)); } catch {}
}
async function handleShare(s) {
  if (!s) return;
  if (s.type === 'text') {
    const text = String(s.text || '');
    const lk = ytLinkFrom(text);
    if (lk) {
      const song = lk.id ? ytClipFromShare(lk.id) : null;
      if (song) { ytPlay({ id: lk.id, title: `${ytCleanTitle(song.title)} (videoclip)`, author: song.artist, clipOf: song.id }, 'now'); toast(`Guardado como el videoclip de «${song.title}»`); }
      else ytPlay(lk, 'now');
      openNP(); return;
    }
    const q = (s.subject || text).replace(/https?:\/\/\S+/g, '').trim().slice(0, 160);
    if (q) { closeNP(); setView('buscar'); setNetTab('canciones'); $('#netQ').value = q; runSearch(q); }
    else toast('Ese enlace no es de un vídeo de YouTube. Comparte un vídeo, una lista o una canción.');
  } else if (s.type === 'files') {
    const files = [];
    for (const f of (s.files || [])) {
      try { const r = await fetch(window.Capacitor.convertFileSrc('file://' + f.path)); if (!r.ok) continue; const b = await r.blob(); files.push(new File([b], f.name || 'cancion', { type: f.mime || b.type || '' })); } catch {}
    }
    if (files.length) { closeNP(); setView('biblioteca'); importFiles(files); } else toast('No he podido recibir la canción compartida.');
  }
}

/* Backups: your likes, plays, playlists, lyrics, stats, voice clips and settings in one file */
const BACKUP_SKIP = new Set(['apiKey', 'ytKey', 'nv', 'nb', 'session', 'daily', 'lastUpdateCheck', 'skipUpdate', 'devWelcomeDone', 'devHidden']);
const blobToB64 = b => new Promise((res, rej) => { const fr = new FileReader(); fr.onload = () => res(String(fr.result).split(',')[1] || ''); fr.onerror = () => rej(fr.error); fr.readAsDataURL(b); });
async function buildBackup() {
  const settings = {};
  try { for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (!k || !k.startsWith('orbita:')) continue; const kk = k.slice(7); if (BACKUP_SKIP.has(kk)) continue; try { settings[kk] = JSON.parse(localStorage.getItem(k)); } catch {} } } catch {}
  const tracks = state.tracks.filter(t => !t.demo).map(t => { const r = trackRecord(t); delete r.cover; delete r.thumb; return r; });
  const [events, lyrics] = await Promise.all([idbAll('events'), idbAll('lyrics')]);
  const clips = []; let budget = 12e6;
  for (const c of state.clips) { if (!c.blob || c.blob.size > budget) continue; budget -= c.blob.size; const { blob, ...rest } = c; clips.push({ ...rest, b64: await blobToB64(blob), type: blob.type || 'audio/webm' }); }
  return { app: 'orbita', kind: 'backup', v: 2, created: Date.now(), version: APP_CFG.version || '', settings, tracks, playlists: state.playlists, wishlist: state.wishlist, events, lyrics: lyrics.filter(l => l && !l.none), clips, demoStats: state.demoStats };
}
async function exportBackup() {
  const btn = $('#btnBackup'); btn.disabled = true;
  try {
    const data = await buildBackup(); const text = JSON.stringify(data);
    const name = `orbita-copia-${dayKey()}.json`;
    if (nativeLib) {
      try { const r = await nativeLib.saveFile({ name, data: text, mime: 'application/json' }); toast(`Copia guardada en ${(r && r.where) || 'Descargas'}`, 5200); LS.set('lastBackup', Date.now()); renderBackup(); return; }
      catch (e) { if (!/not implemented/i.test(String(e && e.message))) { toast('No he podido guardar la copia en Descargas.', 4200); return; } }
    }
    const blob = new Blob([text], { type: 'application/json' });
    const dl = window.claude && window.claude.use ? await window.claude.use('downloads').catch(() => null) : null;
    if (dl) {
      try { await dl.save({ filename: name, data: blob }); }
      catch (e) { toast(e && e.code === 'declined' ? 'Copia cancelada' : 'No se puede descargar aquí. Hazla desde la app de Android.', 3600); return; }
    } else { const u = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = u; a.download = name; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(u), 30000); }
    LS.set('lastBackup', Date.now()); renderBackup(); toast('Copia de seguridad descargada', 3200);
  } catch { toast('No he podido preparar la copia de seguridad.', 4000); }
  finally { btn.disabled = false; }
}
function matchTrack(r, byId, bySrc, byName) {
  return byId.get(r.id) || (r.srcKey && bySrc.get(r.srcKey)) || (byName.get(norm(r.title) + '|' + norm(r.artist)) || []).find(t => !r.duration || !t.duration || Math.abs(t.duration - r.duration) < 4) || null;
}
async function restoreBackup(j) {
  const byId = new Map(state.tracks.map(t => [t.id, t])), bySrc = new Map(state.tracks.filter(t => t.srcKey).map(t => [t.srcKey, t])), byName = new Map();
  for (const t of state.tracks) { const k = norm(t.title) + '|' + norm(t.artist); if (!byName.has(k)) byName.set(k, []); byName.get(k).push(t); }
  const idMap = new Map(); let matched = 0;
  const dirty = [];
  for (const r of (j.tracks || [])) {
    const t = matchTrack(r, byId, bySrc, byName); if (!t) continue;
    idMap.set(r.id, t.id); matched++;
    t.liked = t.liked || !!r.liked; t.plays = Math.max(t.plays || 0, r.plays || 0); t.lastPlayed = Math.max(t.lastPlayed || 0, r.lastPlayed || 0);
    t.skips = Math.max(t.skips || 0, r.skips || 0); t.listenSec = Math.max(t.listenSec || 0, r.listenSec || 0);
    if (Array.isArray(r.hours) && r.hours.length === 24) t.hours = (t.hours && t.hours.length === 24 ? t.hours : new Array(24).fill(0)).map((v, i) => Math.max(v, r.hours[i] || 0));
    if (r.edited && !t.edited) { t.title = r.title; t.artist = r.artist; t.album = r.album; t.edited = true; }
    dirty.push(t);
  }
  if (dirty.length) { await idbPutMany('tracks', dirty.filter(t => !t.demo).map(trackRecord)); }
  for (const t of dirty.filter(t => t.demo)) saveTrack(t);
  const mapId = id => idMap.get(id) || (trackById(id) ? id : null);
  let pls = 0;
  for (const p of (j.playlists || [])) {
    if (!p || !p.id || state.playlists.some(x => x.id === p.id)) continue;
    const ids = (p.trackIds || []).map(mapId).filter(Boolean); if (!ids.length) continue;
    const intros = {}; for (const [k, v] of Object.entries(p.intros || {})) { const m = mapId(k); if (m) intros[m] = v; }
    const np = { ...p, trackIds: ids, intros }; state.playlists.push(np); await idbPut('playlists', np); pls++;
  }
  for (const w of (j.wishlist || [])) if (w && w.id && !state.wishlist.some(x => x.id === w.id)) { state.wishlist.push(w); await idbPut('wishlist', w); }
  const have = new Set(memEvents.map(e => e.ts + '|' + e.id)); const evs = [];
  for (const e of (j.events || [])) { if (!e || !e.ts) continue; const id = e.net ? e.id : mapId(e.id); if (!id) continue; const ne = { ...e, id }; if (have.has(ne.ts + '|' + id)) continue; evs.push(ne); }
  if (evs.length) { await idbPutMany('events', evs); memEvents.push(...evs); memEvents.sort((a, b) => a.ts - b.ts); memCache.clear(); }
  const lys = (j.lyrics || []).map(l => ({ ...l, id: mapId(l.id) })).filter(l => l.id);
  if (lys.length) await idbPutMany('lyrics', lys);
  let clips = 0;
  for (const c of (j.clips || [])) {
    if (!c || !c.id || !c.b64 || state.clips.some(x => x.id === c.id)) continue;
    try { const bin = atob(c.b64), u8 = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i); const { b64, type, ...rest } = c; const clip = { ...rest, blob: new Blob([u8], { type: type || 'audio/webm' }) }; state.clips.push(clip); await idbPut('voice', clip); clips++; } catch {}
  }
  if (j.demoStats && typeof j.demoStats === 'object') { state.demoStats = { ...j.demoStats, ...state.demoStats }; LS.set('demoStats', state.demoStats); }
  for (const [k, v] of Object.entries(j.settings || {})) { if (!BACKUP_SKIP.has(k) && k !== 'demoStats') LS.set(k, v); }
  toast(`Copia restaurada: ${matched} ${matched === 1 ? 'canción' : 'canciones'}, ${pls} ${pls === 1 ? 'lista' : 'listas'}${clips ? `, ${clips} grabaciones` : ''}. Reiniciando…`, 4200);
  setTimeout(() => location.reload(), 2200);
}
function renderBackup() {
  const last = LS.get('lastBackup', 0);
  $('#backupNote').textContent = last ? `Última copia: ${new Date(last).toLocaleDateString('es-ES', { day: 'numeric', month: 'long', year: 'numeric' })}.` : 'Aún no has hecho ninguna copia.';
}
$('#btnBackup').onclick = exportBackup;
$('#restoreInput').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = ''; if (!f) return;
  let j = null; try { j = JSON.parse(await f.text()); } catch {}
  if (!j || j.app !== 'orbita' || j.kind !== 'backup') { toast('Ese archivo no es una copia de seguridad de Órbita.', 4000); return; }
  restoreBackup(j).catch(() => toast('No he podido restaurar la copia.', 4000));
});

/* ---------- Views ---------- */
const VIEWS = ['inicio','buscar','biblioteca','listas','tu','voz','ajustes'];
function setView(v, push = true) {
  if (!VIEWS.includes(v)) v = 'inicio';
  const changed = v !== state.view;
  state.view = v;
  for (const id of VIEWS) $('#view-' + id).hidden = id !== v;
  $$('.tab').forEach(b => b.removeAttribute('aria-current'));
  const tb = document.querySelector(`.tab[data-view="${v}"]`); if (tb) tb.setAttribute('aria-current', 'page');
  if (push && location.hash !== '#' + v) { try { if (STANDALONE) history.pushState({ view: v }, '', '#' + v); else history.replaceState(null, '', '#' + v); } catch {} }
  if (changed) $('#main').scrollTop = 0;
  if (v === 'inicio') sizeVinyl();
  if (v === 'buscar') { setNetTab(netTab); if (netTab === 'canciones' && !state.net && !isMobile) setTimeout(() => $('#netQ').focus({ preventScroll: true }), 50); }
  if (v === 'tu') renderTu();
  if (v === 'inicio') { renderForYou(); setTimeout(vizKick, 30); }
  if (v === 'listas') renderNovaLists();
  if (v === 'ajustes') { renderAlarm(); if (AL_PLUGIN) alarmCheckPerms(false); renderSound(); renderThemes(); renderLook(); renderDjPersona(); renderVoices(); renderVoiceStyle(); updateStorage(); renderPerms(); renderBackup(); }
}
$$('.tab').forEach(b => b.addEventListener('click', () => setView(b.dataset.view)));
document.addEventListener('click', e => { const g = e.target.closest('[data-goto]'); if (g) setView(g.dataset.goto); });
window.addEventListener('hashchange', () => setView(location.hash.slice(1), false));
/* Android back button: closes overlays first, then walks back through the screens */
let popSkip = false;
window.addEventListener('popstate', () => {
  if (popSkip) { popSkip = false; return; }
  if (!$('#alarmScr').hidden) { try { history.pushState({ alarm: 1 }, '', location.hash || '#' + state.view); } catch {} return; }
  if (!$('#recap').hidden) { closeRecap(true); return; }
  if (!$('#onb').hidden) { if (OB.step > 0) { obShow(OB.step - 1, true); try { history.pushState({ onb: 1 }, '', location.hash || '#' + state.view); } catch {} } else closeOnboarding(true); return; }
  if (!$('#chat').hidden) { closeChat(true); return; }
  if (!$('#vizFull').hidden) { closeVizFull(true); return; }
  if (!$('#ytOverlay').hidden) { closeYT(true); return; }
  if (!$('#lyOverlay').hidden) { closeLyEdit(); return; }
  if (!$('#np').hidden) { closeNP(true); return; }
  if (!$('#editOverlay').hidden) { closeEdit(); return; }
  if (!$('#sleepOverlay').hidden) { $('#sleepOverlay').hidden = true; return; }
  setView(location.hash.slice(1) || 'inicio', false);
});

function renderChips() {
  $('#moodChips').innerHTML = MOODS.map(m => `<button class="chip" data-mood="${m.id}" aria-pressed="${state.mood === m.id}">${m.label}</button>`).join('');
  $$('#lenSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.len === String(state.len)));
}
$('#moodChips').addEventListener('click', e => { const b = e.target.closest('[data-mood]'); if (!b) return; state.mood = b.dataset.mood; LS.set('mood', state.mood); renderChips(); });
$('#lenSeg').addEventListener('click', e => { const b = e.target.closest('[data-len]'); if (!b) return; state.len = /^\d+$/.test(b.dataset.len) ? +b.dataset.len : b.dataset.len; LS.set('len', state.len); renderChips(); });
$('#btnMix').onclick = () => createMix();
$('#btnSurprise').onclick = () => createMix({ surprise: true });
$('#ideas').innerHTML = shuffleArr(IDEAS).slice(0, 4).map(s => `<button class="idea" data-idea="${esc(s)}">${esc(s)}</button>`).join('');
$('#ideas').addEventListener('click', e => { const b = e.target.closest('[data-idea]'); if (!b) return; $('#djInput').value = b.dataset.idea; createMix({ request: b.dataset.idea, autoplay: true }); });
$('#djAsk').addEventListener('submit', e => { e.preventDefault(); const v = $('#djInput').value.trim(); if (!v) { $('#djInput').focus(); return; } novaCommand(v); });

/* ---------- Library (rendered in batches) ---------- */
function libFiltered() {
  const q = norm(state.search);
  const coll = new Intl.Collator('es', { sensitivity: 'base', numeric: true });
  const sorters = {
    recent: (a, b) => (a.demo - b.demo) || (b.addedAt - a.addedAt),
    title: (a, b) => coll.compare(a.title, b.title),
    artist: (a, b) => coll.compare(a.artist, b.artist) || coll.compare(a.album || '', b.album || '') || coll.compare(a.title, b.title),
    plays: (a, b) => plays(b) - plays(a) || (b.lastPlayed || 0) - (a.lastPlayed || 0)
  };
  return visibleTracks().filter(t => (state.filter !== 'liked' || liked(t)) && (state.filter !== 'mine' || t.mine) && (state.filter !== 'dev' || t.dev) && (!q || norm(`${t.title} ${t.artist} ${t.album} ${t.folder || ''}`).includes(q)))
    .sort(sorters[state.sort] || sorters.recent);
}
function rowHTML(t) {
  const q = quality(t);
  return `<li class="row" data-track="${t.id}">
      <button class="row-main" data-act="lib-play" data-id="${t.id}">${coverHTML(t)}<span class="row-text"><span class="t-title">${esc(t.title)}${t.demo ? '<span class="pill">Demo</span>' : ''}${t.mine ? '<span class="pill mine">Mi voz</span>' : ''}</span><span class="t-sub">${esc(t.artist)}${t.album ? ' · ' + esc(t.album) : ''}${t.dev && t.folder ? ' · 📁 ' + esc(t.folder) : ''}</span></span></button>
      <span class="q ${q ? q.tier : ''}" title="${q ? esc(q.label) : 'Calculando…'}">${q ? esc(q.label) : '—'}</span>
      <span class="num mono plays">${plays(t)}</span><span class="num mono">${fmt(t.duration)}</span>
      <button class="icon-btn ${liked(t) ? 'liked' : ''}" data-act="like" data-id="${t.id}" aria-label="Favorita" aria-pressed="${liked(t)}">${liked(t) ? ICON.heartOn : ICON.heart}</button>
      <span class="acts">${t.demo ? '' : `<button class="icon-btn" data-act="edit" data-id="${t.id}" aria-label="Editar canción y portada">${ICON.edit}</button><button class="icon-btn" data-act="del" data-id="${t.id}" aria-label="${t.dev ? 'Quitar de Órbita (no borra el archivo)' : 'Borrar'}">${ICON.trash}</button>`}</span>
    </li>`;
}
function renderLibrary() {
  const all = visibleTracks(), own = state.tracks.filter(t => !t.demo);
  const total = all.reduce((s, t) => s + (t.duration || 0), 0);
  $('#libMeta').textContent = `${all.length} ${all.length === 1 ? 'canción' : 'canciones'} · ${total >= 3600 ? (total / 3600).toFixed(1) + ' h' : Math.round(total / 60) + ' min'}`;
  $('#cntLib').textContent = all.length;
  $('#dropzone').hidden = own.length > 6 || (NATIVE && devLib.state === 'granted');
  const low = own.filter(t => quality(t)?.tier === 'baja');
  $('#qualityWarn').hidden = !low.length;
  $('#qualityWarn').innerHTML = low.length ? `<div class="warnbox"><b>${low.length} ${low.length === 1 ? 'canción tiene' : 'canciones tienen'} poca calidad de origen.</b> Están marcadas en rojo. Esa pérdida viene del propio archivo (suele pasar con conversores de YouTube) y la app no puede recuperarla. Para que suenen bien, consíguelas en MP3 a 256–320 kbps, M4A a 256 kbps o FLAC.</div>` : '';
  $$('#filterSeg button').forEach(b => b.setAttribute('aria-pressed', b.dataset.filter === state.filter));
  const rows = libFiltered(); const shown = rows.slice(0, state.libLimit);
  $('#libRows').innerHTML = shown.length ? shown.map(rowHTML).join('') : `<li class="empty" style="margin-top:10px"><p>${state.search ? 'Nada coincide con tu búsqueda.' : state.filter === 'liked' ? 'Aún no tienes favoritas. Pulsa el corazón en cualquier canción.' : state.filter === 'mine' ? 'Aún no has subido canciones cantadas por ti. Hazlo en «Tu voz».' : 'Tu biblioteca está vacía.'}</p></li>`;
  const more = $('#libMore'); more.hidden = rows.length <= state.libLimit; more.textContent = `Mostrar más (${rows.length - shown.length})`;
  $('#demoBar').innerHTML = `<span class="note">${state.hideDemos ? 'Las pistas demo están ocultas.' : 'Las pistas demo se generan en tu navegador para que puedas probar la app.'}</span><button class="btn small" data-act="demos">${state.hideDemos ? 'Mostrar demos' : 'Ocultar demos'}</button>`;
  markPlaying();
}
$('#libMore').onclick = () => { state.libLimit += 200; renderLibrary(); };
try { new IntersectionObserver(es => { if (es.some(x => x.isIntersecting) && !$('#libMore').hidden) $('#libMore').click(); }, { root: $('#main'), rootMargin: '600px' }).observe($('#libMore')); } catch {}
let searchT = null;
$('#search').addEventListener('input', e => { clearTimeout(searchT); searchT = setTimeout(() => { state.search = e.target.value; state.libLimit = 150; renderLibrary(); }, 140); });
$('#filterSeg').addEventListener('click', e => { const b = e.target.closest('[data-filter]'); if (!b) return; state.filter = b.dataset.filter; state.libLimit = 150; renderLibrary(); });
$('#sortSel').value = state.sort;
$('#sortSel').addEventListener('change', e => { state.sort = e.target.value; LS.set('sort', state.sort); state.libLimit = 150; renderLibrary(); });
$('#fDev').hidden = !nativeLib;
$('#btnPlayAll').onclick = () => { const r = libFiltered(); if (r.length) playQueue(r.map(t => t.id)); };
$('#fileInput').addEventListener('change', e => { if (e.target.files.length) importFiles(e.target.files); e.target.value = ''; });
$('#folderInput').addEventListener('change', e => { if (e.target.files.length) importFiles(e.target.files); e.target.value = ''; });
const dirSupported = 'webkitdirectory' in document.createElement('input') && !isMobile;
$('#folderLabel').hidden = !dirSupported;
$('#dropText').innerHTML = dirSupported
  ? '<b>Arrastra aquí carpetas enteras o canciones sueltas</b>, o pulsa «Elegir carpeta» para importar toda tu música de una vez, con sus subcarpetas. Las canciones se guardan tal cual, sin recomprimir. Leo el título, el artista y la portada de cada archivo, y uso la imagen del álbum de la carpeta si la canción no trae la suya.'
  : '<b>Pulsa «Añadir canciones» y selecciona muchas a la vez</b>: en el selector del móvil, mantén pulsada una canción y ve tocando las demás (o usa «Seleccionar todo»). Se guardan tal cual, sin recomprimir, con su título, artista y portada.';

function renderLists() {
  renderNovaLists();
  const pls = state.playlists.slice().sort((a, b) => b.createdAt - a.createdAt);
  $('#cntLists').textContent = pls.length;
  $('#listsMeta').textContent = pls.length ? `${pls.length} ${pls.length === 1 ? 'lista' : 'listas'}` : '';
  if (!pls.length) { $('#listsOut').innerHTML = `<div class="empty"><h3>Aún no has guardado ninguna lista</h3><p>Crea una mezcla y pulsa «Guardar lista». Aquí quedarán con el momento en que nacieron.</p><button class="btn" data-goto="inicio">Ir a Ahora</button></div>`; return; }
  $('#listsOut').innerHTML = `<div class="lists">${pls.map(p => {
    const items = p.trackIds.map(trackById).filter(Boolean);
    const covers = items.slice(0, 4); while (covers.length < 4) covers.push(null);
    const total = items.reduce((s, t) => s + (t.duration || 0), 0);
    return `<article class="panel pl-card"><div class="pl-covers">${covers.map(t => coverHTML(t)).join('')}</div>
      <div><h3>${esc(p.name)}</h3>${p.description ? `<p>${esc(p.description)}</p>` : ''}</div>
      <div class="pl-meta">${items.length} canciones · ${fmt(total)} · ${esc(p.moment || '')}${p.source === 'ai' ? ' · Nova' : ''}</div>
      <div class="pl-btns"><button class="btn small primary" data-act="pl-play" data-id="${p.id}">${ICON.play}Escuchar</button><button class="icon-btn" data-act="pl-shuffle" data-id="${p.id}" aria-label="Escuchar en aleatorio">${ICON.shuffle}</button><span class="spacer"></span><button class="icon-btn" data-act="pl-del" data-id="${p.id}" aria-label="Borrar lista">${ICON.trash}</button></div>
    </article>`; }).join('')}</div>`;
}

/* ---------- Delegated actions ---------- */
function armOrRun(btn, run) {
  if (btn.dataset.armed) { run(); return; }
  btn.dataset.armed = '1'; const old = btn.innerHTML; btn.classList.add('armed'); btn.textContent = '¿Borrar?';
  setTimeout(() => { if (btn.isConnected) { delete btn.dataset.armed; btn.classList.remove('armed'); btn.innerHTML = old; } }, 3000);
}
document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]'); if (!el) return;
  const a = el.dataset.act, id = el.dataset.id;
  if (a === 'lib-play') { const r = libFiltered(); playQueue(r.map(t => t.id), r.findIndex(t => t.id === id)); }
  else if (a === 'net-play') { playQueue([id, ...radioBatch([id])], 0, { source: 'radio' }); setRadio(true, true); }
  else if (a === 'like') { const t = trackById(id); if (t) toggleLike(t); }
  else if (a === 'edit') openEdit('track', id);
  else if (a === 'del') armOrRun(el, () => deleteTrack(id));
  else if (a === 'dev-connect') { LS.set('devWelcomeDone', true); connectDevice(); }
  else if (a === 'dev-sync') syncDevice(true);
  else if (a === 'dev-later') { LS.set('devWelcomeDone', true); renderDevice(); }
  else if (a === 'demos') { state.hideDemos = !state.hideDemos; LS.set('hideDemos', state.hideDemos); renderAll(); }
  else if (a === 'mix-play') playMix(0);
  else if (a === 'mix-from') playMix(+el.dataset.i);
  else if (a === 'mix-again') createMix({ request: state.mix?.request || '' });
  else if (a === 'mix-stop') mixCtl?.abort();
  else if (a === 'mix-save') saveMix();
  else if (a === 'pl-play' || a === 'pl-shuffle') {
    const p = state.playlists.find(x => x.id === id); if (!p) return;
    if (a === 'pl-shuffle' && !state.shuffle) { state.shuffle = true; LS.set('shuffle', true); }
    playQueue(p.trackIds, 0, { source: 'mix', intros: p.intros || {}, talk: p.greeting || null, talkKind: 'saludo' }); updatePlayer();
  }
  else if (a === 'pl-del') armOrRun(el, async () => { state.playlists = state.playlists.filter(p => p.id !== id); await idbDel('playlists', id); renderLists(); toast('Lista borrada'); });
  else if (a === 'wish-add') { const x = state.net?.results?.[+el.dataset.i]; if (!x) return; const w = { id: uid('w'), title: x.title, artist: x.artist, why: x.why, addedAt: Date.now() }; state.wishlist.push(w); idbPut('wishlist', w); renderWishlist(); el.textContent = 'Guardada'; el.disabled = true; }
  else if (a === 'wish-del') { state.wishlist = state.wishlist.filter(w => w.id !== id); idbDel('wishlist', id); renderWishlist(); }
  else if (a === 'clip-play') { const c = state.clips.find(x => x.id === id); if (c) playClip(c, `Probando: ${c.label}`); }
  else if (a === 'clip-rename') openEdit('clip', id);
  else if (a === 'clip-del') armOrRun(el, async () => { state.clips = state.clips.filter(c => c.id !== id); if (clipUrls.has(id)) { URL.revokeObjectURL(clipUrls.get(id)); clipUrls.delete(id); } await idbDel('voice', id); renderVoice(); });
  else if (a === 'voice-test') { const v = dj.all.find(x => x.name === el.dataset.name); if (v) speakTTS(`Hola, soy ${dj.name}. Así sonaría presentando tus canciones en Órbita FM.`, v); }
  else if (a === 'voice-use') { const v = dj.all.find(x => x.name === el.dataset.name); if (v) { dj.voice = v; LS.set('voiceName', v.name); renderVoices(); speakTTS('Perfecto. A partir de ahora sonaré así.'); } }
  else if (a === 'np-open') openNP();
  else if (a === 'q-del') { const i = +el.dataset.i; if (i > state.qi && state.queue[i]) { state.queue.splice(i, 1); disarmMix(); prefetchNext(); renderQueue(); haptic(6); } }
  else if (a === 'q-up') { const i = +el.dataset.i, q = state.queue; if (i > state.qi + 1 && q[i]) { [q[i - 1], q[i]] = [q[i], q[i - 1]]; disarmMix(); prefetchNext(); renderQueue(); haptic(6); } }
  else if (a === 'q-jump') { const i = +el.dataset.i; if (state.queue[i]) { state.qi = i; loadCurrent(true, act().paused ? 0 : MANUAL_XF); } }
  else if (a === 'sleep-open') { renderSleep(); $('#sleepOverlay').hidden = false; }
  else if (a === 'sleep-close') $('#sleepOverlay').hidden = true;
  else if (a === 'np-close') closeNP();
  else if (a === 'daily-play') playDaily(0);
  else if (a === 'unban') { unbanTrack(id); renderTu(); toast('Vuelve a estar en la radio y las mezclas'); }
  else if (a === 'daily-new') { buildDaily(true); renderForYou(); toast('Mezcla del día renovada'); }
  else if (a === 'daily-save') { const d = buildDaily(); if (!d) return; const p = { id: uid('p'), name: d.name, description: d.desc, trackIds: d.ids.slice(), createdAt: Date.now(), moment: getMoment().label, source: d.ai ? 'ai' : 'local', intros: d.intros || {}, greeting: d.greeting || '' }; state.playlists.push(p); idbPut('playlists', p); renderLists(); toast('Guardada en Mis listas'); }
  else if (a === 'sotd-play' || a === 'tu-play') { const t = trackById(id); if (t) { playQueue([id, ...radioBatch([id])], 0, { source: 'radio', talk: a === 'sotd-play' ? `La canción del día: ${nameOf(t)}. Después, la radio.` : null, talkKind: 'saludo' }); setRadio(true, true); } }
  else if (a === 'fy-play-red' || a === 'fy-play-new') { const list = (a === 'fy-play-red' ? rediscover() : newArrivals()).map(t => t.id); const i = Math.max(0, list.indexOf(id)); playQueue(list, i, { source: 'mix' }); }
  else if (a === 'resume-session') { if (current() && act().getAttribute('src')) resume(); else if (!resumeSession(true)) toast('No encuentro dónde lo dejaste.'); }
  else if (a === 'r-play') playStation(id, el.dataset.list);
  else if (a === 'r-fav') toggleFav(el.dataset.uuid);
  else if (a === 'r-retry') loadRadios();
  else if (a === 'yt-open' || a === 'yt-next') { const list = el.dataset.list || ''; const v = [...(yt.results || []), ...yt.recent].find(x => (x.id || '') === (id || '') && (x.list || '') === list) || {}; ytPlay({ id: id || '', list, title: v.title, author: v.author }, a === 'yt-open' ? 'now' : 'next'); if (a === 'yt-open') openNP(); }
  else if (a === 'yt-expand') openNP();
  else if (a === 'yt-x') ytClose();
  else if (a === 'yt-close') closeYT();
  else if (a === 'yt-find') ytFind(el.dataset.q || '');
  else if (a === 'on-play' || a === 'on-playall') { const list = state.net?.online || []; if (list.length) playQueue(list.map(t => t.id), a === 'on-play' ? +el.dataset.i : 0, { source: 'online' }); }
  else if (a === 'wish-add-on') { const x = state.net?.online?.[+el.dataset.i]; if (!x) return; const w = { id: uid('w'), title: x.title, artist: x.artist, why: x.album || '', addedAt: Date.now() }; state.wishlist.push(w); idbPut('wishlist', w); renderWishlist(); el.textContent = 'Guardada'; el.disabled = true; }
  else if (a === 'ai-preview') aiPreview(el, +el.dataset.i);
});
async function aiPreview(btn, i) {
  const x = state.net?.results?.[i]; if (!x) return;
  btn.disabled = true; const old = btn.innerHTML; btn.textContent = 'Buscando…';
  try { const t = await previewFor(x.title, x.artist); if (t) playQueue([t.id], 0, { source: 'online' }); else toast('No he encontrado un avance de esa canción.'); }
  catch { toast('No he podido conectar para escucharla.'); }
  if (btn.isConnected) { btn.disabled = false; btn.innerHTML = old; }
}
function openExternal(url) { const a = document.createElement('a'); a.href = url; a.target = '_blank'; a.rel = 'noopener'; document.body.appendChild(a); a.click(); a.remove(); }
async function ytFind(q) {
  if (ONLINE && yt.key()) { setView('buscar'); setNetTab('youtube'); $('#ytQ').value = q; await ytSearch(q); const v = yt.results && yt.results[0]; if (v) { ytPlay({ id: v.id, title: v.title, author: v.author }, 'now'); openNP(); } }
  else openExternal('https://www.youtube.com/results?search_query=' + encodeURIComponent(q));
}
document.addEventListener('keydown', e => { if ((e.key === 'Enter' || e.key === ' ') && e.target.matches && e.target.matches('[role="button"][data-act]')) { e.preventDefault(); e.target.click(); } });
async function saveMix() {
  const mx = state.mix; if (!mx) return;
  if (mx.savedId) { toast('Esta mezcla ya está en Mis listas'); return; }
  const p = { id: uid('p'), name: mx.name, description: mx.desc, trackIds: mx.ids.slice(), createdAt: Date.now(), moment: mx.moment, mood: mx.mood, source: mx.source, intros: mx.intros, greeting: mx.greeting };
  state.playlists.push(p); mx.savedId = p.id; await idbPut('playlists', p); renderLists(); toast('Guardada en Mis listas');
}
async function deleteTrack(id) {
  const t = trackById(id); if (!t || t.demo) return;
  if (t.dev) { devLib.hidden.add(id); LS.set('devHidden', [...devLib.hidden]); }
  if (current()?.id === id) { decks.forEach(d => { d.pause(); d.removeAttribute('src'); d.load(); }); }
  const ci = state.queue.indexOf(id); if (ci !== -1) { state.queue.splice(ci, 1); if (state.qi >= ci) state.qi = Math.max(0, state.qi - 1); if (!state.queue.length) state.qi = -1; }
  state.tracks = state.tracks.filter(x => x.id !== id);
  if (urlCache.has(id)) { URL.revokeObjectURL(urlCache.get(id)); urlCache.delete(id); }
  dropCoverCache(t); memAudio.delete(id);
  await idbDel('tracks', id); if (!t.dev) await idbDel('audio', id);
  if (state.mix) state.mix.ids = state.mix.ids.filter(x => x !== id);
  renderAll(); toast(t.dev ? 'Quitada de Órbita (el archivo sigue en tu móvil)' : 'Canción borrada'); updateStorage();
}
let editing = null, previewUrl = null;
function renderEditCover() {
  const t = editing && trackById(editing.id); if (!t) return;
  if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
  if (editing.cover) { previewUrl = URL.createObjectURL(editing.cover); $('#editCoverPrev').innerHTML = `<span class="cover"><img src="${previewUrl}" alt=""></span>`; }
  else if (editing.cover === null) $('#editCoverPrev').innerHTML = coverHTML({ ...t, id: 'none', cover: null, thumb: null });
  else $('#editCoverPrev').innerHTML = coverHTML(t);
}
function openEdit(kind, id) {
  editing = { kind, id, cover: undefined };
  if (kind === 'track') { const t = trackById(id); if (!t) return; $('#editHead').textContent = 'Editar canción'; $('#editTitle').value = t.title; $('#editArtist').value = t.artist; $('#editAlbum').value = t.album || ''; renderEditCover(); }
  else { const c = state.clips.find(x => x.id === id); if (!c) return; $('#editHead').textContent = 'Nombre de la grabación'; $('#editTitle').value = c.label; }
  $('#editArtistL').hidden = $('#editAlbumL').hidden = $('#editCoverRow').hidden = kind !== 'track';
  $('#editOverlay').hidden = false; $('#editTitle').focus();
}
$('#editCover').addEventListener('change', e => { const f = e.target.files[0]; if (f && editing) { editing.cover = f; renderEditCover(); } e.target.value = ''; });
$('#editCoverDel').onclick = () => { if (editing) { editing.cover = null; renderEditCover(); } };
const closeEdit = () => { $('#editOverlay').hidden = true; editing = null; };
$('#editCancel').onclick = closeEdit;
$('#editOverlay').addEventListener('click', e => { if (e.target.id === 'editOverlay') closeEdit(); });
$('#sleepOverlay').addEventListener('click', e => { if (e.target.id === 'sleepOverlay') $('#sleepOverlay').hidden = true; });
$('#editForm').addEventListener('submit', async e => {
  e.preventDefault(); if (!editing) return closeEdit();
  if (editing.kind === 'track') {
    const t = trackById(editing.id); if (!t) return closeEdit();
    t.title = $('#editTitle').value.trim() || t.title; t.artist = $('#editArtist').value.trim() || 'Artista desconocido'; t.album = $('#editAlbum').value.trim(); t.edited = true;
    if (editing.cover !== undefined) {
      dropCoverCache(t);
      if (editing.cover) { const tone = await makeThumb(editing.cover); t.cover = editing.cover; t.thumb = tone.thumb; t.coverHue = tone.hue; t.coverSat = tone.sat; }
      else { t.cover = null; t.thumb = null; t.coverHue = null; t.coverSat = null; }
    }
    await saveTrack(t); closeEdit(); renderAll(); if (current()?.id === t.id) { updatePlayer(); applyTheme(); mediaSession(t); }
  } else {
    const c = state.clips.find(x => x.id === editing.id); if (!c) return closeEdit();
    c.label = $('#editTitle').value.trim().slice(0, 60) || c.label; await idbPut('voice', c); closeEdit(); renderVoice();
  }
});

/* ---------- Drag & drop (files and whole folders) ---------- */
let dragDepth = 0;
const hasFiles = e => [...(e.dataTransfer?.types || [])].includes('Files');
document.addEventListener('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); dragDepth++; $('#dropOverlay').hidden = false; });
document.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
document.addEventListener('dragleave', e => { if (!hasFiles(e)) return; dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $('#dropOverlay').hidden = true; });
document.addEventListener('drop', e => { if (!hasFiles(e)) return; e.preventDefault(); dragDepth = 0; $('#dropOverlay').hidden = true; const p = filesFromDrop(e.dataTransfer); setView('biblioteca'); p.then(list => importFiles(list)); });

document.addEventListener('keydown', e => {
  if (e.key === 'Escape') { if (!$('#newsOverlay').hidden) { $('#newsOverlay').hidden = true; return; } if (!$('#chat').hidden) { closeChat(); return; } if (!$('#vizFull').hidden) { closeVizFull(); return; } if (!$('#ytOverlay').hidden) { closeYT(); return; } if (!$('#lyOverlay').hidden) { closeLyEdit(); return; } if (!$('#sleepOverlay').hidden) { $('#sleepOverlay').hidden = true; return; } if (!$('#editOverlay').hidden) { closeEdit(); return; } if (!$('#np').hidden) { closeNP(); return; } }
  if (e.target.closest && e.target.closest('input, textarea, select, [contenteditable]')) return;
  if (e.code === 'Space' && !(e.target.matches && e.target.matches('button, [role="button"]'))) { e.preventDefault(); togglePlay(); }
  else if (e.key === 'ArrowRight' && e.shiftKey) next(false);
  else if (e.key === 'ArrowLeft' && e.shiftKey) prev();
});

let toastTimer = null;
function toast(msg, ms = 2600) { const t = $('#toast'); t.textContent = msg; t.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, ms); }

/* ---------- Moment, ticker ---------- */
function renderMoment() {
  const m = getMoment();
  const th = `<span class="d">${esc(m.day)}</span><span class="f">${esc(m.franja.phrase)}</span>`, h = $('#momentTitle');
  if (h._html !== th) { h._html = th; h.innerHTML = th; fitTitle(); }
  $('#clock').textContent = m.hh; $('#momentHint').textContent = m.hint; $('#mixerMoment').textContent = m.label;
  applyTheme();
}
/* Long day names («miércoles», «domingo») shrink so they never go under the vinyl or the visualizer */
function renderAppVer() {
  const el = $('#appVerTxt'); if (!el) return;
  const v = APP_CFG.version ? 'Órbita ' + APP_CFG.version + (APP_CFG.beta ? ' (beta)' : '') : 'Órbita';
  el.textContent = NATIVE ? v + ' · cerebro grande: ' + (typeof BB_NATIVE !== 'undefined' && BB_NATIVE ? 'incluido' : 'no incluido') : v;
}
function fitTitle() {
  const h = $('#momentTitle'), d = h && h.querySelector('.d'); if (!d) return;
  d.style.fontSize = '';
  const room = h.clientWidth; if (!room) return;
  const need = d.scrollWidth;
  if (need > room) d.style.fontSize = Math.max(0.3, (room / need) * 0.97).toFixed(3) + 'em';
}
window.addEventListener('resize', () => fitTitle());
if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => fitTitle());
if (document.fonts && document.fonts.addEventListener) document.fonts.addEventListener('loadingdone', () => fitTitle());
try { let tw = -1; new ResizeObserver(es => { const w = Math.round(es[0].contentRect.width); if (w !== tw) { tw = w; requestAnimationFrame(fitTitle); } }).observe($('#momentTitle')); } catch {}
[1500, 4000].forEach(ms => setTimeout(() => fitTitle(), ms));
function updateTicker() {
  const m = getMoment(), t = current();
  const parts = [t ? `Ahora suena: ${t.title} · ${t.artist}` : 'Órbita FM', `${m.day} ${m.franja.phrase}`, 'Pídele lo que quieras a la DJ', 'Elige la voz que más te guste', 'Importa carpetas enteras', t ? `Calidad: ${quality(t)?.label || 'directo'}` : 'Sonido directo del archivo'];
  const one = parts.map(p => `<span>${esc(p)}</span><span class="star">✦</span>`).join('');
  $('#ticker').innerHTML = one + one;
}

/* ---------- Vinyl visualizer ---------- */
const cv = $('#vinyl'); const g = cv.getContext('2d');
let angle = 0, lastTs = 0, lastDraw = 0, grooveCache = null;
const coverImgs = new Map();
function coverImg(t) {
  const u = coverUrl(t, 'full'); if (!u) return null;
  if (!coverImgs.has(u)) { const im = new Image(); im.decoding = 'async'; im.src = u; coverImgs.set(u, im); while (coverImgs.size > 12) coverImgs.delete(coverImgs.keys().next().value); }
  const im = coverImgs.get(u); return im.complete && im.naturalWidth ? im : null;
}
function sizeVinyl() { const r = cv.getBoundingClientRect(); const dpr = Math.min(1.5, window.devicePixelRatio || 1); const w = Math.max(1, Math.round(r.width * dpr)); if (cv.width !== w) { cv.width = w; cv.height = w; grooveCache = null; } }
new ResizeObserver(sizeVinyl).observe(cv);
const hsla = (h, s, l, a) => `hsla(${h},${Math.round(s * sat)}%,${l}%,${a})`;
function grooves(W, R, dR) {
  if (grooveCache) return grooveCache;
  const c = document.createElement('canvas'); c.width = c.height = W; const x = c.getContext('2d'); const cx = W / 2;
  const disc = x.createRadialGradient(cx, cx, dR * 0.2, cx, cx, dR); disc.addColorStop(0, '#1d1828'); disc.addColorStop(1, '#050409');
  x.fillStyle = disc; x.beginPath(); x.arc(cx, cx, dR, 0, Math.PI * 2); x.fill();
  x.lineWidth = Math.max(1, W / 500);
  for (let k = 0; k < 22; k++) { x.strokeStyle = `rgba(255,255,255,${k % 4 === 0 ? 0.07 : 0.03})`; x.beginPath(); x.arc(cx, cx, dR * (0.47 + k * 0.024), 0, Math.PI * 2); x.stroke(); }
  x.lineWidth = Math.max(2, W / 150); x.strokeStyle = 'rgba(255,255,255,.08)'; x.beginPath(); x.arc(cx, cx, dR + R * 0.025, 0, Math.PI * 2); x.stroke();
  grooveCache = c; return c;
}
let drawOn = false, lastInput = performance.now();
const idleSlow = () => performance.now() - lastInput > 60000;
['pointerdown', 'keydown', 'wheel'].forEach(ev => document.addEventListener(ev, () => { lastInput = performance.now(); }, { capture: true, passive: true }));
const drawWanted = () => state.view === 'inicio' && !document.hidden && state.heroMode === 'vinyl' && $('#np').hidden && !root.classList.contains('booting');
function drawKick() { if (!drawOn && drawWanted()) { drawOn = true; requestAnimationFrame(draw); } }
setInterval(drawKick, 700);
document.addEventListener('visibilitychange', drawKick);
function draw(ts) {
  if (!drawWanted()) { drawOn = false; lastTs = 0; return; }
  requestAnimationFrame(draw);
  const dt = Math.min(0.05, (ts - lastTs) / 1000 || 0); lastTs = ts;
  if (state.view !== 'inicio' || document.hidden || state.heroMode !== 'vinyl' || !$('#np').hidden || root.classList.contains('booting')) return;
  const t = current(); const d = act(); const playing = !d.paused && !!t;
  if (playing && !reduceMotion) angle += dt * 2.0;
  const minGap = !playing ? 90 : (state.anim === 'lite' || idleSlow()) ? 33 : 0;
  if (ts - lastDraw < minGap) return;
  lastDraw = ts;
  const W = cv.width; if (W < 10) return;
  const cx = W / 2, cy = W / 2, R = W / 2 - 2, dR = R * 0.74;
  g.clearRect(0, 0, W, W);
  const n = 64; let level = 0; const vals = new Array(n);
  const hasA = playing && A < 2 && analyser && freq;
  if (hasA) analyser.getByteFrequencyData(freq);
  for (let i = 0; i < n; i++) {
    let v;
    if (hasA) { const k = Math.floor(Math.pow(i / n, 1.6) * (freq.length * 0.7)) + 1; v = freq[k] / 255; }
    else if (playing) v = 0.22 + 0.2 * Math.sin(ts / 260 + i * 0.55) * Math.sin(ts / 410 + i * 0.21) + 0.08 * Math.sin(ts / 90 + i);
    else v = 0.04 + 0.03 * Math.sin(ts / 900 + i * 0.4);
    vals[i] = clamp01(v); level += vals[i];
  }
  level /= n;
  const halo = g.createRadialGradient(cx, cy, R * 0.55, cx, cy, R);
  halo.addColorStop(0, hsla(hue, 96, 60, 0)); halo.addColorStop(0.6, hsla(hue, 96, 60, 0.12 + level * 0.35)); halo.addColorStop(1, hsla(hue - 45, 92, 60, 0));
  g.fillStyle = halo; g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.fill();
  g.lineCap = 'round'; g.lineWidth = Math.max(2, W / 120);
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 - Math.PI / 2 + (reduceMotion ? 0 : angle * 0.05);
    const r1 = R * 0.79, r2 = r1 + R * 0.025 + vals[i] * R * 0.18;
    g.strokeStyle = hsla(hue - 45 * (i / n), 95, 64, 0.35 + vals[i] * 0.65);
    g.beginPath(); g.moveTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1); g.lineTo(cx + Math.cos(a) * r2, cy + Math.sin(a) * r2); g.stroke();
  }
  g.drawImage(grooves(W, R, dR), 0, 0);
  g.save(); g.translate(cx, cy); g.rotate(angle);
  for (const off of [0, Math.PI]) {
    const sg = g.createLinearGradient(0, 0, Math.cos(off) * dR, Math.sin(off) * dR);
    sg.addColorStop(0, 'rgba(255,255,255,0)'); sg.addColorStop(1, hsla(hue, 90, 75, 0.14));
    g.fillStyle = sg; g.beginPath(); g.moveTo(0, 0); g.arc(0, 0, dR, off - 0.35, off + 0.35); g.closePath(); g.fill();
  }
  const lR = dR * 0.42; const img = t && coverImg(t);
  g.beginPath(); g.arc(0, 0, lR, 0, Math.PI * 2); g.closePath();
  if (img) { g.save(); g.clip(); g.drawImage(img, -lR, -lR, lR * 2, lR * 2); g.restore(); }
  else {
    const lg = g.createLinearGradient(-lR, -lR, lR, lR); lg.addColorStop(0, hsla(hue, 96, 62, 1)); lg.addColorStop(1, hsla(hue - 45, 92, 55, 1));
    g.fillStyle = lg; g.fill();
    g.fillStyle = 'rgba(20,8,28,.85)'; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.font = `900 ${Math.round(lR * 0.3)}px Unbounded, "Arial Black", sans-serif`; g.fillText('ÓRBITA', 0, -lR * 0.38);
    g.font = `700 ${Math.round(lR * 0.16)}px Figtree, sans-serif`; g.fillText(t ? (t.title.length > 18 ? t.title.slice(0, 17) + '…' : t.title) : 'FM', 0, lR * 0.42);
  }
  g.restore();
  g.fillStyle = '#06050b'; g.beginPath(); g.arc(cx, cy, R * 0.03, 0, Math.PI * 2); g.fill();
  if (t && isFinite(d.duration) && d.duration > 0) {
    g.lineWidth = Math.max(2, W / 150); g.strokeStyle = hsla(hue, 96, 66, 1);
    g.beginPath(); g.arc(cx, cy, dR + R * 0.025, -Math.PI / 2, -Math.PI / 2 + (d.currentTime / d.duration) * Math.PI * 2); g.stroke();
  }
}

/* ---------- Dynamic visualizer: reacts to the frequencies, the beat and the volume in real time ----------
   Three looks: «Órbita» (planet, spectrum ring, orbiting particles, shock waves on each beat),
   «Ondas» (layered waveforms) and «Galaxia» (a spiral of stars that spins with the tempo). */
const VIZ_SCENES = [['orbita', 'Órbita'], ['ondas', 'Ondas'], ['galaxia', 'Galaxia']];
const VZ = { scene: LS.get('vizScene', 'orbita'), np: LS.get('vizNP', false), bass: 0, mid: 0, high: 0, level: 0, avgBass: 0.2, lastBeat: 0, beatK: 0, phase: 0, lastPhase: 0, waves: [], parts: [], stars: [], wave: null, spec: new Float32Array(96), t: 0, last: 0, rot: 0, raf: 0 };
function vizCanvases() {
  const out = [];
  if (!$('#vizFull').hidden) out.push($('#vizFS'));
  else {
    if (!$('#np').hidden && VZ.np) out.push($('#vizNP'));
    if ($('#np').hidden && state.view === 'inicio' && state.heroMode === 'viz') out.push($('#vizHero'));
  }
  return out;
}
function vizSize(cv) {
  const r = cv.getBoundingClientRect(), dpr = Math.min(state.anim === 'lite' ? 1.25 : 2, window.devicePixelRatio || 1);
  const w = Math.max(2, Math.round(r.width * dpr)), h = Math.max(2, Math.round(r.height * dpr));
  if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
  return [w, h];
}
let vizTD = null;
function vizRead(ts) {
  const d = act(), t = current(), playing = !!t && !d.paused, dt = Math.min(0.05, (ts - VZ.last) / 1000 || 0.016); VZ.last = ts; VZ.t += dt;
  const real = playing && A < 2 && analyser && freq;
  let bass = 0, mid = 0, high = 0, level = 0;
  if (real) {
    analyser.getByteFrequencyData(freq);
    const nb = freq.length;
    for (let i = 0; i < 96; i++) { const k = Math.min(nb - 1, Math.floor(Math.pow(i / 96, 1.7) * nb * 0.72) + 1); const v = freq[k] / 255; VZ.spec[i] += (v - VZ.spec[i]) * (v > VZ.spec[i] ? 0.6 : 0.18); }
    for (let i = 1; i <= 3; i++) bass += freq[i]; bass /= 3 * 255;
    for (let i = 6; i <= 40; i++) mid += freq[i]; mid /= 35 * 255;
    for (let i = 60; i < Math.min(nb, 160); i++) high += freq[i]; high /= Math.max(1, Math.min(nb, 160) - 60) * 255;
    if (!vizTD || vizTD.length !== analyser.fftSize) vizTD = new Uint8Array(analyser.fftSize);
    analyser.getByteTimeDomainData(vizTD); VZ.wave = vizTD;
    let s = 0; for (let i = 0; i < vizTD.length; i += 2) { const v = (vizTD[i] - 128) / 128; s += v * v; } level = Math.min(1, Math.sqrt(s / (vizTD.length / 2)) * 2.4);
  } else {
    const k = playing ? 1 : 0.25, tt = VZ.t;
    for (let i = 0; i < 96; i++) { const v = k * (0.28 + 0.22 * Math.sin(tt * 2.1 + i * 0.31) * Math.sin(tt * 1.3 + i * 0.07) + 0.12 * Math.sin(tt * 7 + i)); VZ.spec[i] += (Math.max(0, v) - VZ.spec[i]) * 0.2; }
    bass = k * (0.35 + 0.25 * Math.max(0, Math.sin(tt * Math.PI * 2 * 1.8))); mid = k * 0.3; high = k * 0.22; level = k * 0.4; VZ.wave = null;
  }
  const sm = (a, b, up, dn) => a + (b - a) * (b > a ? up : dn);
  VZ.bass = sm(VZ.bass, bass, 0.55, 0.12); VZ.mid = sm(VZ.mid, mid, 0.4, 0.1); VZ.high = sm(VZ.high, high, 0.5, 0.15); VZ.level = sm(VZ.level, level, 0.4, 0.08);
  /* Beats: from the song's analysed beat grid when we have it, otherwise from sudden bass hits */
  let beat = false; const an = t && !t.net && anaCache.get(t.id);
  if (playing && an && an.bpm && an.conf >= 2) {
    const ph = ((d.currentTime - an.gridIn) / (60 / an.bpm)) % 1; if (ph < VZ.lastPhase - 0.5) beat = true; VZ.lastPhase = ph; VZ.phase = ph; VZ.bpm = an.bpm;
  } else {
    VZ.avgBass += (bass - VZ.avgBass) * 0.04;
    if (playing && bass > VZ.avgBass * 1.32 && bass > 0.22 && ts - VZ.lastBeat > 260) beat = true;
    VZ.bpm = 0;
  }
  if (beat) { VZ.lastBeat = ts; VZ.beatK = 1; VZ.waves.push({ r: 0, a: 0.8 }); if (VZ.waves.length > 6) VZ.waves.shift(); }
  VZ.beatK *= Math.pow(0.02, dt);
  return { dt, playing, beat };
}
const vhsl = (h, s, l, a) => `hsla(${h},${Math.round(s * sat)}%,${l}%,${a})`;
function vizOrbita(g, W, H, f, img) {
  const cx = W / 2, cy = H / 2, R = Math.min(W, H) / 2, n = 96;
  g.fillStyle = 'rgba(6,5,11,0.28)'; g.fillRect(0, 0, W, H);
  const glow = g.createRadialGradient(cx, cy, R * 0.1, cx, cy, R * 1.1);
  glow.addColorStop(0, vhsl(hue, 96, 55, 0.18 + VZ.bass * 0.35)); glow.addColorStop(1, vhsl(hue - 45, 90, 40, 0));
  g.fillStyle = glow; g.fillRect(0, 0, W, H);
  VZ.rot += f.dt * (0.15 + VZ.level * 0.9 + (VZ.bpm ? VZ.bpm / 400 : 0));
  /* shock waves on each beat */
  for (const w of VZ.waves) { w.r += f.dt * R * 1.1; w.a *= Math.pow(0.12, f.dt); g.strokeStyle = vhsl(hue, 96, 70, w.a * 0.6); g.lineWidth = Math.max(1, R * 0.012); g.beginPath(); g.arc(cx, cy, R * 0.34 + w.r, 0, Math.PI * 2); g.stroke(); }
  VZ.waves = VZ.waves.filter(w => w.a > 0.02);
  /* spectrum ring */
  const r1 = R * 0.42 * (1 + VZ.beatK * 0.05);
  g.lineCap = 'round'; g.lineWidth = Math.max(2, (Math.PI * 2 * r1 / n) * 0.55);
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 + VZ.rot * 0.25, v = VZ.spec[i], r2 = r1 + R * 0.04 + v * R * 0.36;
    g.strokeStyle = vhsl(hue - 50 * (i / n) + 25 * Math.sin(VZ.t * 0.5), 96, 58 + v * 18, 0.35 + v * 0.65);
    g.beginPath(); g.moveTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1); g.lineTo(cx + Math.cos(a) * r2, cy + Math.sin(a) * r2); g.stroke();
  }
  /* orbiting particles */
  const want = state.anim === 'lite' ? 40 : 90;
  while (VZ.parts.length < want) VZ.parts.push({ a: Math.random() * Math.PI * 2, d: 0.55 + Math.random() * 0.45, s: 0.4 + Math.random() * 0.8, z: Math.random() });
  g.globalCompositeOperation = 'lighter';
  for (const p of VZ.parts) {
    p.a += f.dt * p.s * (0.25 + VZ.level * 1.4) * (p.z > 0.5 ? 1 : -0.6);
    const dd = R * p.d * (1 + VZ.beatK * 0.08 * p.z), x = cx + Math.cos(p.a) * dd, y = cy + Math.sin(p.a) * dd * 0.62;
    const sz = Math.max(1, R * 0.008 * (1 + VZ.high * 3 * p.z));
    g.fillStyle = vhsl(hue + 30 * p.z, 90, 70, 0.25 + VZ.high * 0.7); g.beginPath(); g.arc(x, y, sz, 0, Math.PI * 2); g.fill();
  }
  g.globalCompositeOperation = 'source-over';
  /* the planet: the cover, breathing with the bass */
  const pr = R * 0.3 * (1 + VZ.bass * 0.1 + VZ.beatK * 0.06);
  g.save(); g.beginPath(); g.arc(cx, cy, pr, 0, Math.PI * 2); g.closePath(); g.clip();
  if (img) g.drawImage(img, cx - pr, cy - pr, pr * 2, pr * 2);
  else { const lg = g.createLinearGradient(cx - pr, cy - pr, cx + pr, cy + pr); lg.addColorStop(0, vhsl(hue + 30, 96, 62, 1)); lg.addColorStop(0.5, vhsl(hue, 96, 56, 1)); lg.addColorStop(1, vhsl(hue - 60, 90, 45, 1)); g.fillStyle = lg; g.fill(); }
  g.restore();
  g.strokeStyle = vhsl(hue, 96, 75, 0.35 + VZ.beatK * 0.5); g.lineWidth = Math.max(1.5, R * 0.01); g.beginPath(); g.arc(cx, cy, pr + R * 0.015, 0, Math.PI * 2); g.stroke();
}
function vizOndas(g, W, H, f) {
  g.fillStyle = 'rgba(6,5,11,0.35)'; g.fillRect(0, 0, W, H);
  const glow = g.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, Math.max(W, H) * 0.7);
  glow.addColorStop(0, vhsl(hue, 96, 50, 0.1 + VZ.bass * 0.3)); glow.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = glow; g.fillRect(0, 0, W, H);
  const lines = 5, mid = H / 2, amp = H * (0.12 + VZ.level * 0.32);
  g.globalCompositeOperation = 'lighter'; g.lineJoin = 'round';
  for (let k = 0; k < lines; k++) {
    g.strokeStyle = vhsl(hue - 45 * (k / lines), 96, 62, 0.75 - k * 0.12); g.lineWidth = Math.max(1.5, H * 0.006 * (1 + VZ.beatK * 1.5 - k * 0.15));
    g.beginPath();
    const N = 90, off = k * 9;
    for (let i = 0; i <= N; i++) {
      const x = (i / N) * W; let v;
      if (VZ.wave) v = (VZ.wave[Math.min(VZ.wave.length - 1, (i * 4 + off * 3) % VZ.wave.length)] - 128) / 128;
      else v = Math.sin(i * 0.22 + VZ.t * (3 + k)) * 0.5 * VZ.level + Math.sin(i * 0.05 - VZ.t) * 0.3 * VZ.bass;
      const env = Math.sin((i / N) * Math.PI);
      const y = mid + v * amp * env * (1 - k * 0.13) + Math.sin(VZ.t * 0.7 + k) * H * 0.02 * k;
      i ? g.lineTo(x, y) : g.moveTo(x, y);
    }
    g.stroke();
  }
  g.globalCompositeOperation = 'source-over';
  /* bass bars at the bottom */
  const n = 48, bw = W / n;
  for (let i = 0; i < n; i++) { const v = VZ.spec[Math.floor(i * 1.9)]; g.fillStyle = vhsl(hue - 40 * (i / n), 96, 60, 0.25 + v * 0.5); const h = v * H * 0.18; g.fillRect(i * bw + bw * 0.2, H - h, bw * 0.6, h); }
}
function vizGalaxia(g, W, H, f) {
  const cx = W / 2, cy = H / 2, R = Math.max(W, H) * 0.62;
  g.fillStyle = 'rgba(6,5,11,0.3)'; g.fillRect(0, 0, W, H);
  const want = state.anim === 'lite' ? 220 : 520;
  while (VZ.stars.length < want) { const arm = Math.floor(Math.random() * 3); VZ.stars.push({ d: Math.pow(Math.random(), 0.7), arm, j: (Math.random() - 0.5) * 0.5, b: Math.random(), k: Math.floor(Math.random() * 96) }); }
  VZ.rot += f.dt * (0.08 + VZ.level * 0.5 + (VZ.bpm ? VZ.bpm / 900 : 0));
  g.globalCompositeOperation = 'lighter';
  const core = g.createRadialGradient(cx, cy, 0, cx, cy, Math.min(W, H) * 0.16 * (1 + VZ.bass * 0.5));
  core.addColorStop(0, vhsl(hue + 20, 90, 70, 0.16 + VZ.beatK * 0.22)); core.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = core; g.fillRect(0, 0, W, H);
  for (const s of VZ.stars) {
    const a = s.arm * (Math.PI * 2 / 3) + s.d * 5.2 + VZ.rot * (1.4 - s.d) + s.j, dd = s.d * R * (1 + VZ.beatK * 0.05);
    const x = cx + Math.cos(a) * dd, y = cy + Math.sin(a) * dd * 0.72, v = VZ.spec[s.k];
    const sz = Math.max(0.8, (W / 500) * (0.6 + s.b * 1.4 + v * 2.2));
    g.fillStyle = vhsl(hue - 60 * s.d + 40 * v, 90, 60 + v * 25, 0.25 + v * 0.6 + VZ.high * 0.3); g.fillRect(x - sz / 2, y - sz / 2, sz, sz);
  }
  g.globalCompositeOperation = 'source-over';
}
function vizFrame(ts) {
  VZ.raf = 0;
  const cvs = document.hidden || root.classList.contains('booting') ? [] : vizCanvases();
  if (!cvs.length) return;
  VZ.raf = requestAnimationFrame(vizFrame);
  if ((state.anim === 'lite' || idleSlow()) && ts - (VZ.lastDraw || 0) < 31) return;
  VZ.lastDraw = ts;
  const f = vizRead(ts), t = current(), img = t && coverImg(t);
  for (const cv of cvs) {
    const [W, H] = vizSize(cv), g = cv.getContext('2d');
    if (VZ.scene === 'ondas') vizOndas(g, W, H, f); else if (VZ.scene === 'galaxia') vizGalaxia(g, W, H, f); else vizOrbita(g, W, H, f, img);
  }
}
function vizKick() { if (!VZ.raf && !document.hidden && vizCanvases().length) { wakeCtx(); ensureGraph(); VZ.raf = requestAnimationFrame(vizFrame); } }
function setVizNP(on) {
  VZ.np = on; LS.set('vizNP', on);
  if (on && LY.on) setLyricsOpen(false);
  $('#np').classList.toggle('viz-open', on); $('#npVizBtn').setAttribute('aria-pressed', on);
  vizKick();
}
function vizNextScene() {
  const i = VIZ_SCENES.findIndex(s => s[0] === VZ.scene), nx = VIZ_SCENES[(i + 1) % VIZ_SCENES.length];
  VZ.scene = nx[0]; LS.set('vizScene', VZ.scene); VZ.parts = []; VZ.stars = [];
  $$('[data-viz-scene-label]').forEach(e => { e.textContent = nx[1]; });
  toast(`Visualizador: ${nx[1]}`);
}
function openVizFull() {
  const t = current();
  $('#vfTitle').textContent = t ? t.title : 'Órbita'; $('#vfArtist').textContent = t ? t.artist : '';
  $('#vizFull').hidden = false; $$('[data-viz-scene-label]').forEach(e => { e.textContent = VIZ_SCENES.find(s => s[0] === VZ.scene)[1]; });
  if (STANDALONE) { try { history.pushState({ viz: 1 }, '', location.hash || '#' + state.view); } catch {} }
  vizKick();
}
function closeVizFull(fromPop) {
  if ($('#vizFull').hidden) return; $('#vizFull').hidden = true;
  if (STANDALONE && !fromPop && history.state && history.state.viz) { try { popSkip = true; history.back(); } catch {} }
  vizKick();
}
$('#npVizBtn').onclick = () => setVizNP(!VZ.np);
$('#npViz').addEventListener('click', e => { if (e.target.closest('[data-act="viz-scene"]')) { vizNextScene(); return; } openVizFull(); });
$('#vizFull').addEventListener('click', e => { const b = e.target.closest('[data-act]'); if (b && b.dataset.act === 'viz-scene') { vizNextScene(); return; } if (b && b.dataset.act === 'viz-close') { closeVizFull(); return; } $('#vizFull').classList.toggle('chrome'); });
$('#vizWrap').addEventListener('dblclick', () => openVizFull());
document.addEventListener('visibilitychange', () => { if (!document.hidden) vizKick(); });
$('#npMore').onclick = () => moreLikeThis();
const NEWS = {
  '3.5': [
    '<b>Despertador con Nova</b> (Ajustes › Despertador): te despierta con tu música, empezando muy bajito y subiendo poco a poco, y te dice la hora, el día y el tiempo. Se ve aunque el móvil esté bloqueado y, si algo falla, suena la alarma del móvil. También se lo puedes pedir: «despiértame a las siete y media».',
    '<b>Tu año en Órbita</b> (en «Tú»): tus horas de música, tu canción y tu artista del año, tus récords y qué tipo de oyente eres, contado por Nova en tarjetas. Al final, una imagen para compartir.',
    '<b>Motor de sonido de Android</b> (beta, Ajustes › Sonido): tu música suena con el reproductor del propio móvil, y las canciones seguidas de un mismo disco van sin cortes. Es una prueba: las mezclas de DJ llegarán a este motor en la 3.6. Si notas algo raro, apágalo y vuelve el de siempre.',
    '<b>Primera vez fácil</b>: quien instala Órbita tiene una bienvenida de un minuto (su música, sus estilos, cómo quiere a su DJ y su primera mezcla). Puedes verla otra vez al final de Ajustes.'
  ],
  '3.4': [
    '<b>Nova, más lista</b>: si no está segura de lo que le pides, te pregunta («¿Paso a la siguiente?»). Si le dices que sí, lo hace y aprende la frase para la próxima vez.',
    'Recuerda lo que acabas de pedirle: «hazme otra», «que sea sin fin», «más de este artista».',
    'Entiende detalles: «salta dos canciones», «pon el volumen al 50», «dos horas de reguetón». Y conoce más de cien formas nuevas de pedir las cosas.',
    '<b>Te entiende sin internet</b>: sin conexión, el micrófono usa el reconocimiento de voz del propio móvil. Puedes pedírselo siempre en Ajustes › Tu DJ.',
    'Y todo lo de la 3.2 y la 3.3: YouTube sin clave, videoclips, ecualizador de 10 bandas y mezclas sin fin.'
  ],
  '3.3': [
    '<b>Ecualizador de 10 bandas</b> (Ajustes › Sonido): arrastra cada banda, usa los botones grandes (graves, voz, agudos) o un estilo (pop, reguetón, rock…), y guarda tus propios ajustes con nombre.',
    '<b>Sonido más limpio</b>: al subir bandas, Órbita baja antes el volumen justo para que nunca distorsione, y el limitador es más suave.',
    '<b>Mezclas más largas</b>: de 10 a 50 canciones, de una hora o <b>sin fin</b> (Nova va añadiendo más en la misma onda). También se lo puedes pedir: «dos horas de reguetón», «música sin fin para entrenar».',
    '«Más como esta» ya no se acaba: sigue poniendo canciones parecidas a la que elegiste.'
  ],
  '3.2': [
    '<b>YouTube sin clave</b>: comparte un vídeo o una lista desde la app de YouTube (Compartir › Órbita) o pega el enlace en Buscar › YouTube. Entra en tu cola, Nova lo presenta y después sigue tu música.',
    'El vídeo se ve en grande en «Ahora suena» y en pequeño encima del reproductor mientras usas la app. Los botones de Órbita lo manejan como a una canción.',
    '<b>Videoclips</b>: en «Ahora suena», toca «Videoclip» y Órbita busca el vídeo oficial de la canción. Si no lo encuentra, compártelo tú desde YouTube y quedará guardado para esa canción.',
    'El cerebro de Nova se guarda ahora dentro de la app: Android ya no puede borrarlo para hacer sitio.'
  ],
  '3.1': [
    '<b>El cerebro grande de Nova</b> (beta): en Ajustes › Tu DJ, descarga un modelo de lenguaje de verdad (Qwen3 1.7B, 1,1 GB) que funciona dentro del móvil. Nova charla contigo con sus propias palabras, entiende peticiones raras y sabe de qué estabais hablando.',
    'No elige canciones ni da datos: eso lo siguen haciendo tu biblioteca, el gusto que Nova ha aprendido de ti y Wikipedia.',
    'Prueba a contarle cómo te ha ido el día, o a preguntarle qué opina de lo que suena.',
    '<b>La voz de Nova empieza antes</b>: el primer trozo es corto y el resto se prepara mientras habla. Las voces del sistema leen la frase entera de una vez.',
    '<b>Más ligera en uso largo</b>: con la app en segundo plano el cerebro grande libera memoria, y la barra de progreso no trabaja si no la ves.',
    'Al final de Ajustes verás la versión instalada.'
  ],
  '3.0': [
    '<b>Nova recuerda lo que le dices</b>: «no me pongas reguetón por la mañana», «me encanta el rap», «por la noche, cosas tranquilas». Lo cumple siempre en la radio y en sus mezclas.',
    '<b>Conoce los estilos</b> de tus artistas (por la etiqueta de la canción, por Wikipedia o porque se lo enseñas: «tal artista es trap»).',
    '<b>Rutinas</b>: «cada mañana ponme algo suave» y lo pondrá al abrir Órbita.',
    '<b>Recuerdos</b> y el resumen de tu semana: «hace un año no parabas de escuchar…».',
    '<b>Voz propia</b>: en Ajustes › Tu DJ, descarga una voz neuronal para Nova. Funciona sin internet y, en la app de Android, con un motor nativo que la hace hablar al momento.',
    '<b>El cerebro de Nova</b> (beta): en Ajustes › Tu DJ, descarga un modelo de lenguaje que entiende lo que le dices con tus palabras («esta es una pasada», «estoy reventado», «baja las revoluciones»). Funciona sin internet, nunca se inventa datos y puedes enseñarle frases tuyas.',
    '<b>Más fluida</b> en sesiones largas: aprender tus canciones ya no frena la app, y gasta menos memoria y batería.',
    '<b>Conversación</b>: toca el bocadillo de Nova o «Conversación» para hablar con ella con historial. Pregúntale «¿qué recuerdas de mí?».'
  ],
  '2.3': [
    '<b>Háblale a Nova</b>: toca el micrófono y dile «más movida», «sáltate esta», «¿por qué esta?», «apágate en 30 minutos» o pídele una mezcla.',
    '<b>Nova te explica</b> por qué ha elegido cada canción y conoce a tus artistas: te cuenta datos de ellos (de Wikipedia).',
    '<b>El tiempo que hace</b>: dile tu ciudad en Ajustes y lo tendrá en cuenta para saludarte y elegir la música.',
    '<b>Listas hechas por Nova</b> en Listas: tus mañanas, tus noches, para entrenar… se actualizan solas.',
    '<b>Edita la cola</b> en Ahora suena: sube o quita canciones de «A continuación».'
  ],
  '2.2': [
    '<b>Transiciones suaves</b>: fundidos largos de potencia constante y mezclas al compás, sin entradas de golpe. También con la pantalla apagada.',
    '<b>Visualizador</b> que baila con la música: toca «Visual» en Ahora suena, o elígelo en Ajustes › Portadas.',
    '<b>Nova aprende de ti</b>: «Más como esta», «No me la pongas más» y peticiones como «algo tranquilo para dormir».',
    '<b>Entrada más fluida</b>: la app no empieza a cargar hasta que la animación ha terminado de moverse.',
    'Cuando Nova habla, la música baja y sube poco a poco en vez de golpe.'
  ]
};
function showNews() {
  if (!NATIVE) return;
  const v = String(APP_CFG.version || ''), mm = v.split('.').slice(0, 2).join('.');
  if (!NEWS[mm] || LS.get('newsSeen', '') === mm) return;
  LS.set('newsSeen', mm);
  $('#newsVer').textContent = `Versión ${v} instalada`;
  $('#newsList').innerHTML = NEWS[mm].map(x => `<li>${x}</li>`).join('');
  const nb = $('#newsOverlay [data-news="viz"], #newsOverlay [data-news="alarm"]');
  if (nb) { const al = mm === '3.5' && typeof AL_PLUGIN !== 'undefined' && !!AL_PLUGIN; nb.dataset.news = al ? 'alarm' : 'viz'; nb.textContent = al ? 'Poner el despertador' : 'Ver el visualizador'; }
  $('#newsOverlay').hidden = false;
}
$('#newsOverlay').addEventListener('click', e => {
  const b = e.target.closest('[data-news]');
  if (e.target.id === 'newsOverlay' || (b && b.dataset.news === 'close')) { $('#newsOverlay').hidden = true; return; }
  if (b && b.dataset.news === 'viz') { $('#newsOverlay').hidden = true; openVizFull(); }
  if (b && b.dataset.news === 'alarm') { $('#newsOverlay').hidden = true; setView('ajustes'); setTimeout(() => $('#alarmPanel').scrollIntoView({ block: 'start', behavior: 'smooth' }), 80); }
});
$('#npBan').onclick = () => banTrack(current());
/* ---------- 2.3 · Nova te escucha ----------
   Voice and typed commands, refinements of what is playing, explanations of each pick,
   facts about your artists (Wikipedia), the weather where you are (Open-Meteo) and playlists Nova keeps up to date. */
const novaSay = text => { chatAdd('nova', text); return djTalk(text, 'test', { forceTTS: true }); };
const joinEs = a => a.length <= 1 ? (a[0] || '') : a.slice(0, -1).join(', ') + ' y ' + a[a.length - 1];
/* Listening: Android's own speech recognizer (native plugin) or the browser's, where there is one */
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const voiceAvailable = () => (NATIVE && !!nativeLib) || (!!SR && STANDALONE);
async function listenOnce() {
  if (NATIVE && nativeLib) {
    /* 3.4: without internet (or if you choose it), the phone's own offline recognizer */
    const offline = LS.get('sttOffline', false) || navigator.onLine === false;
    const r = await nativeLib.recognizeSpeech({ lang: 'es-ES', prompt: `Dile algo a ${dj.name}`, offline });
    const got = (r && r.matches || [])[0] || '';
    if (!got && offline && r && [2, 3, 4, 5].includes(+r.code)) toast('Para entenderte sin internet, el móvil necesita el español descargado: app de Google › Ajustes › Voz › Reconocimiento de voz sin conexión.', 8000);
    return got;
  }
  if (!SR) throw new Error('nosr');
  return new Promise((res, rej) => {
    const r = new SR(); let got = '';
    r.lang = 'es-ES'; r.interimResults = false; r.maxAlternatives = 1;
    r.onresult = e => { got = e.results[0][0].transcript; };
    r.onerror = e => rej(new Error(e.error || 'sr'));
    r.onend = () => res(got);
    r.start();
  });
}
let listening = false;
async function voiceAsk() {
  if (listening) return;
  listening = true; $$('#djMic, #npMic').forEach(b => b.classList.add('on'));
  stopTalk(); haptic(10);
  const playing = current() && !act().paused; if (playing) tween('duck', 0.15, 250);
  let text = '';
  try { text = String(await listenOnce() || '').trim(); }
  catch (e) {
    const m = String(e && e.message || '');
    toast(/not implemented|UNIMPLEMENTED|recognizeSpeech/i.test(m) ? 'Esta versión de la app aún no tiene voz: actualízala' : /NO_RECOGNIZER|No hay/i.test(m) ? 'Este móvil no tiene reconocimiento de voz (instala o activa «Google»)' : /not-allowed|denied/i.test(m) ? 'Necesito permiso para usar el micrófono' : 'No te he oído bien. Prueba otra vez', 4200);
  }
  finally {
    listening = false; $$('#djMic, #npMic').forEach(b => b.classList.remove('on'));
    if (playing) tween('duck', 1, 700);
  }
  if (!text) return;
  $('#djInput').value = text;
  setBubble(`«${text}»`, 'Tú');
  await sleep(250);
  novaCommand(text, { voice: true });
}
/* Commands: most phrases change something right away; anything else becomes a request for a mix */
function novaCommand(raw) {
  chatAdd('me', raw);
  novaRun(raw);
}
function novaRun(raw, viaBrain) {
  const t = current(), playing = !!t && !act().paused;
  let q = norm(raw);
  const me = norm(dj.name);
  q = q.replace(new RegExp(`^(oye |hey |ey |hola |venga )?(${me ? me + '|' : ''}nova|dj)( |$)`), '').replace(/^(por favor |porfa )|( por favor| porfa)$/g, '').trim();
  if (!viaBrain && nbOffer(q)) return;
  if (!viaBrain && nbFollow(q)) return;
  if (!q) { if (/^[\s¡!,.]*(hola|buenas|buenos)/i.test(raw)) nbChat('c_saludo'); else novaSay('Dime, te escucho.'); return; }
  const is = re => re.test(q);
  if (alarmCommand(q)) return;
  if (is(/^(ayuda|que puedes hacer|que sabes hacer|que te puedo pedir)$/)) { novaSay('Puedes pedirme cosas como: más movida, más tranquila, sáltate esta, ¿por qué esta?, más como esta, ¿quién canta?, háblame del artista, apágate en treinta minutos, o una mezcla: algo para entrenar. Y enseñarme lo que te gusta: no me pongas reguetón por la mañana, me encanta el rap, cada noche ponme algo tranquilo, ¿qué recuerdas de mí?' + (nbUsable() ? ' Y con mi cerebro nuevo entiendo también lo que me digas con tus palabras.' : '')); return; }
  if (is(/(resumen|como ha ido|que tal ha ido) (de )?(mi|la|esta) semana/)) { novaSay(weekSummary() || 'Esta semana aún has escuchado poco para hacer un resumen.'); return; }
  if (is(/^(que hora es|dime la hora)$/)) { const d = new Date(); novaSay(`Son las ${d.getHours()} y ${pad(d.getMinutes())}.`); return; }
  if (is(/^(pausa|pausala|para(la)?|deten(la|te)?|stop|silencio|calla(te)?|pon pausa|quita la musica)$/)) { if (playing) fadePause(); setBubble('Pausa.', dj.name); return; }
  if (is(/^(sigue|continua|reanuda|play|dale|reproduce|pon (la )?musica|vuelve a poner(la)?)$/)) { if (t) resume(); else createMix({ surprise: true, autoplay: true }); return; }
  if (is(/^(siguiente|la siguiente|pasa(la)?|salta(la)?|saltate (esta|esa)|saltatela|otra|otra cancion|cambia(la)?|cambia de cancion|next|no me gusta( esta| nada)?)$/)) { if (t) { setBubble('Siguiente.', dj.name); next(false); } return; }
  if (is(/^(anterior|la anterior|atras|vuelve atras|la de antes|pon la (de antes|anterior))$/)) { if (t) prev(); return; }
  if (is(/^(sube( el)? volumen|subele|mas alto|mas volumen|sube)$/)) { setVolume(+1); return; }
  if (is(/^(baja( el)? volumen|bajale|mas bajo|mas flojo|menos volumen|baja)$/)) { setVolume(-1); return; }
  if (is(/^(me gusta( esta| mucho)?|me encanta( esta)?|guarda(la)?( en favoritas)?|a favoritas|favorita|like)$/)) { if (t && !t.net) { if (!liked(t)) toggleLike(t); novaSay(pickFresh(['Guardada en tus favoritas.', 'Apuntada: favorita.', 'Hecho, a favoritas.'])); } return; }
  if (is(/(no me (la )?pongas mas|no (la )?quiero (volver a )?oir( mas)?|odio esta|quita(la)? para siempre|no la pongas mas)/)) { if (t) banTrack(t); return; }
  if (is(/(mas (como|parecidas a|parecido a) esta|algo parecido|parecidas a esta|mas de este (estilo|rollo|tipo))/)) { moreLikeThis(); return; }
  if (t && is(/^(un poco |algo )?mas (movida|movido|animada|animado|marcha|ritmo|energia|cana|canera|fuerte|rapida|rapido)$|^sube (el|ese) ritmo$|^venga arriba$/)) { refineEnergy(+1); return; }
  if (t && is(/^(un poco |algo )?mas (tranquila|tranquilo|suave|calmada|lenta|lento|relajada|relajado|chill)$|^baja (el|ese) ritmo$|^relaja(nos|te)?$/)) { refineEnergy(-1); return; }
  if (is(/(que (cancion )?(suena|es esta|esta sonando|estoy escuchando)|como se llama (esta|la cancion)|quien canta( esta)?)/)) {
    if (!t) { novaSay('Ahora mismo no suena nada.'); return; }
    const f = !t.net && cachedFact(t.artist); novaSay(`Suena ${nameOf(t)}.${f ? ' ' + shortFact(f) : ''}`); return;
  }
  if (is(/por ?que (has puesto|pusiste|me pones|suena|elegiste|esta)/) || is(/^por ?que$/)) { explainPick(t); return; }
  const ab = q.match(/^(quien es|quienes son|hablame (de|del artista|sobre)|cuentame (algo )?(de|del artista|sobre)|que sabes de|dime algo (de|del artista|sobre))( (.+))?$/);
  if (ab || is(/^(hablame del artista|quien es el artista|cuentame algo del artista)$/)) { aboutArtist(ab && ab[7] && !/^(este|esta|ese|esa|el|ella|el artista|la artista|el cantante|la cantante|el grupo|la banda|este (grupo|artista|cantante)|esta (banda|artista|cantante)|quien canta)$/.test(ab[7]) ? ab[7] : (t && t.artist)); return; }
  if (is(/^(pon |activa |quiero )?(el )?modo radio$|^(pon )?(la )?radio$/)) { if (!state.radio) $('#btnRadio').click(); return; }
  if (is(/(^| )(que tiempo hace|que tal (hace|el tiempo)|va a llover|esta lloviendo|como esta el tiempo|que clima hace)/)) { tellWeather(); return; }
  const sl = q.match(/(apaga(te)?|duerme(te)?|parate|temporizador|me voy a dormir|a dormir).*?(\d+|media|una|dos) ?(min|minutos|hora|horas)/);
  if (sl) { const n = sl[4] === 'media' ? 30 : sl[4] === 'una' ? 1 : sl[4] === 'dos' ? 2 : +sl[4]; const mins = /hora/.test(sl[5]) ? (sl[4] === 'media' ? 30 : n * 60) : n; if (mins > 0 && mins <= 600) { setSleep(String(mins)); novaSay(`Vale, me apago en ${mins >= 60 ? (mins / 60 === 1 ? 'una hora' : (mins / 60) + ' horas') : mins + ' minutos'}.`); } return; }
  if (is(/(al (acabar|terminar)|cuando (acabe|termine)) (esta|la cancion)/)) { setSleep('track'); return; }
  if (is(/^(pon |ensename |muestrame |abre )?(la )?letra$|karaoke/)) { if (t) { openNP(); setLyricsOpen(true); } return; }
  if (is(/visualizador|^(pon )?(el )?visual$/)) { openVizFull(); return; }
  if (is(/(resumen|repaso) (de mi|del|de este|de tu|de todo el) ano|(mi|tu) ano en orbita|(^| )wrapped$|que (he|e) escuchado este ano/)) { openRecap(0); return; }
  if (memoryCommand(q, raw)) return;
  const qn = q.match(/^(despues|luego|a continuacion) pon (.+)$|^pon (.+) (despues|luego|a continuacion|la siguiente)$/);
  if (qn && t) { queueNextByName(qn[2] || qn[3]); return; }
  const less = q.match(/^(nada (mas )?de|sin|menos|quita(me)?) (.+)$/);
  if (less && t && removeArtistFromQueue(less[4])) return;
  const clean = String(raw).replace(new RegExp(`^\\s*((oye|hey|ey|hola|venga)[\\s,]+)?(${dj.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}|nova|dj)[\\s,:]+`, 'i'), '').trim() || raw;
  if (!viaBrain && brainRoute(raw, q, clean)) return;
  mixFromChat(clean);
}
function setVolume(dir) {
  const v = Math.max(0, Math.min(100, +vol.value + dir * 15));
  vol.value = v; vol.dispatchEvent(new Event('input')); toast(`Volumen ${v} %`);
}
function refineEnergy(dir) {
  const t = current(); const v = t && trackVec(t);
  const base = BRAIN.target ?? (v ? v[0] : 0.5);
  BRAIN.target = Math.max(0.05, Math.min(0.95, base + dir * 0.22));
  const keep = state.queue.slice(0, state.qi + 1);
  const exclude = new Set([...state.history.slice(0, 10), ...keep.slice(-30)]);
  let ids = brainRank(poolTracks(), t, { target: BRAIN.target, exclude, explore: 0.3, strength: 4.5 }).map(x => x.t.id).slice(0, 10);
  if (ids.length < 3) ids = brainRank(poolTracks(), t, { target: BRAIN.target, strength: 4.5 }).map(x => x.t.id).slice(0, 10);
  if (!ids.length) { novaSay('No tengo más canciones para cambiar el ritmo.'); return; }
  state.queue = keep.concat(ids); if (state.source !== 'radio') state.source = 'mix';
  disarmMix(); prefetchNext(); renderQueue();
  const nx = trackById(ids[0]);
  novaSay(dir > 0 ? pickFresh([`Subimos el ritmo. Lo siguiente: ${nx.title}.`, `Marchando algo con más energía, empezando por ${nx.title}.`, 'Vale, vamos a por más marcha.'])
    : pickFresh([`Bajamos revoluciones. Lo siguiente: ${nx.title}.`, `Algo más tranquilo, empezando por ${nx.title}.`, 'Vale, nos relajamos un poco.']));
}
function explainPick(t) {
  if (!t) { novaSay('Ahora mismo no suena nada.'); return; }
  if (t.net) { novaSay(t.kind === 'radio' ? `Es una radio que elegiste tú: ${t.title}.` : 'Es un avance que pusiste tú desde Buscar.'); return; }
  const r = [], prevId = state.queue[state.qi - 1], prv = prevId && trackById(prevId);
  if (state.mix && state.mix.request && state.source === 'mix') r.push(`me pediste «${state.mix.request}»`);
  if (liked(t)) r.push('es una de tus favoritas');
  if (prv && transScore(prv.id, t.id) > 0.3) r.push(`después de ${prv.title} sueles escucharla`);
  if (t.hours && t.hours.length === 24) { const h = new Date().getHours(), tot = t.hours.reduce((a, b) => a + b, 0); if (tot >= 3 && t.hours[h] / tot > 0.2) r.push('a esta hora sueles ponerla'); }
  if (plays(t) <= 1 && !t.demo) r.push('es nueva para ti y quería que la probaras');
  const v = trackVec(t);
  if (v && BRAIN.target !== null && Math.abs(v[0] - BRAIN.target) < 0.12) r.push('tiene justo la energía que llevamos');
  const a = anaCache.get(t.id), pa = prv && anaCache.get(prv.id);
  if (a && pa && a.bpm && pa.bpm && a.conf >= 2 && pa.conf >= 2 && Math.abs(a.bpm / pa.bpm - 1) < 0.06) r.push(`su ritmo, ${Math.round(a.bpm)} pulsaciones por minuto, encaja con la anterior para mezclarlas`);
  if (plays(t) >= 3 && pSkip(t, brainCtx(prv)) < 0.25) r.push('casi nunca la saltas');
  if (weatherNow() && v) { const k = wxDesc(weatherNow().code)[1]; if ((k === 'lluvia' || k === 'tormenta') && v[0] < 0.4) r.push('con este tiempo pega algo tranquilo'); }
  if (!r.length) r.push(state.source === 'radio' ? 'en modo radio voy probando cosas para conocerte mejor' : 'va en la lista que estás escuchando');
  novaSay(`La he puesto porque ${joinEs(r.slice(0, 3))}.`);
}
function queueNextByName(name) {
  const k = norm(name).replace(/^(la |el |una |la cancion |cancion )/, '');
  const tr = poolTracks().find(x => norm(x.title) === k) || poolTracks().find(x => norm(x.title).includes(k)) || poolTracks().find(x => k.includes(norm(x.title)) && norm(x.title).length > 3);
  if (!tr) { novaSay(`No encuentro «${name}» en tu música.`); return; }
  state.queue.splice(state.qi + 1, 0, tr.id); disarmMix(); prefetchNext(); renderQueue();
  novaSay(`Hecho: después suena ${tr.title}.`);
}
function removeArtistFromQueue(name) {
  const k = norm(name); if (k.length < 3) return false;
  const up = state.queue.slice(state.qi + 1), hit = up.filter(id => { const x = trackById(id); return x && artistKey(x.artist) && (artistKey(x.artist) === k || artistKey(x.artist).includes(k)); });
  if (!hit.length) return false;
  const artist = trackById(hit[0]).artist;
  state.queue = state.queue.slice(0, state.qi + 1).concat(up.filter(id => !hit.includes(id)));
  if (state.source === 'radio' && state.queue.length - state.qi < 4) state.queue.push(...radioNext(current(), 6).filter(id => { const x = trackById(id); return x && artistKey(x.artist) !== artistKey(artist); }));
  disarmMix(); prefetchNext(); renderQueue();
  novaSay(`Quito ${hit.length === 1 ? 'la canción' : 'las ' + hit.length + ' canciones'} de ${artist} que venían.`);
  return true;
}
/* What Nova knows about your artists: Wikipedia in Spanish, only for musicians, cached on the phone */
const FACT_RE = /(cantante|banda|grupo|rapero|rapera|musico|musica|dj|productor|productora|compositor|compositora|duo|cantautor|cantautora|orquesta|trap|reggaeton|rock|pop|hip hop|discografica|album)/;
const factPending = new Map();
var FACTS_MEMO;
const factsAll = () => FACTS_MEMO || (FACTS_MEMO = LS.get('facts', {}));
function cachedFact(name) { const k = artistKey(name); const c = k && factsAll()[k]; return c ? c.f : null; }
const shortFact = f => { const s = String(f.text || '').replace(/\s*\([^)]*\)/g, '').replace(/\s+,/g, ',').replace(/\s+/g, ' ').trim(); const first = s.split(/(?<=[.!?])\s+/)[0] || ''; return first.length <= 240 ? first : ''; };
function artistFact(name) {
  const key = artistKey(name);
  if (!ONLINE || !key || key.length < 2 || /^(desconocid|unknown|varios|various|orbita|artista)/.test(key)) return Promise.resolve(null);
  const all = factsAll(), c = all[key];
  if (c && Date.now() - c.ts < (c.f ? 60 : 10) * 864e5) return Promise.resolve(c.f);
  if (factPending.has(key)) return factPending.get(key);
  const H = { 'Api-User-Agent': 'Orbita/2.3 (app de musica personal)' };
  const pr = (async () => {
    let f = null;
    try {
      const s = await netJSON(`https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&origin=*&srlimit=5&srsearch=${encodeURIComponent(name)}`, { headers: H, timeout: 9000 });
      for (const h of (s && s.query && s.query.search) || []) {
        const title = h.title, tn = norm(title.replace(/\s*\(.*\)\s*$/, ''));
        if (!(tn === key || tn.replace(/ /g, '') === key.replace(/ /g, '') || tn.startsWith(key + ' ') || key.startsWith(tn + ' '))) continue;
        const sm = await netJSON(`https://es.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title.replace(/ /g, '_'))}`, { headers: H, timeout: 9000 });
        const text = String(sm.extract || ''), desc = String(sm.description || '');
        if (sm.type === 'disambiguation' || !FACT_RE.test(norm(desc + ' ' + text.slice(0, 400)))) continue;
        const sent = text.split(/(?<=[.!?])\s+/); let ex = sent[0] || ''; if (ex.length < 140 && sent[1]) ex += ' ' + sent[1];
        f = { title: sm.title || title, desc: desc.slice(0, 120), text: ex.slice(0, 360), url: (sm.content_urls && sm.content_urls.mobile && sm.content_urls.mobile.page) || `https://es.wikipedia.org/wiki/${encodeURIComponent(title.replace(/ /g, '_'))}` };
        break;
      }
    } catch { factPending.delete(key); return null; }
    const cur = factsAll(); cur[key] = { ts: Date.now(), f }; genresChanged();
    const ks = Object.keys(cur); if (ks.length > 400) ks.sort((a, b) => cur[a].ts - cur[b].ts).slice(0, ks.length - 400).forEach(x => delete cur[x]);
    LS.set('facts', cur); factPending.delete(key); return f;
  })();
  factPending.set(key, pr); return pr;
}
const factSaid = new Set();
function factLine(t) {
  if (!t || t.net || t.demo || t.mine) return null;
  const k = artistKey(t.artist); if (!k || factSaid.has(k)) return null;
  const f = cachedFact(t.artist); const s = f && shortFact(f); if (!s) return null;
  factSaid.add(k);
  return pickFresh([`Un dato: ${s} Suena ${t.title}.`, `¿Sabías esto? ${s} Ahora, ${t.title}.`, `${s} Y esta es ${t.title}.`]);
}
async function aboutArtist(name) {
  if (!name) { novaSay('¿De qué artista? Pon una canción suya o dime su nombre.'); return; }
  if (!ONLINE) { novaSay('Para contarte cosas de los artistas necesito la app de Android con internet.'); return; }
  setBubble(`Buscando a ${name}…`, dj.name);
  const f = await artistFact(name);
  novaSay(f ? `${f.text.replace(/\s*\([^)]*\)/g, '').replace(/\s+/g, ' ')}` : `No he encontrado nada fiable sobre ${name}.`);
}
function renderAbout(t) {
  const box = $('#npAbout'); if (!box) return;
  if (!t || t.net || t.demo || t.mine || !ONLINE) { box.hidden = true; return; }
  const f = cachedFact(t.artist);
  if (!f) { box.hidden = true; artistFact(t.artist).then(x => { if (x && current() === t && !$('#np').hidden) renderAbout(t); }); return; }
  box.hidden = false;
  box.innerHTML = `<div class="eyebrow">Sobre ${esc(f.title)}</div><p>${esc(f.text)}</p><div class="row-wrap"><button class="link" data-about="wiki" data-url="${esc(f.url)}">Leer más en Wikipedia</button><button class="link" data-about="say">Que me lo cuente ${esc(dj.name)}</button></div>`;
}
$('#npAbout').addEventListener('click', e => {
  const b = e.target.closest('[data-about]'); if (!b) return;
  if (b.dataset.about === 'wiki') openExternal(b.dataset.url); else { const t = current(); if (t) aboutArtist(t.artist); }
});
/* The weather where you are: a city you type once (no location permission), Open-Meteo, refreshed every half hour */
function wxDesc(c) {
  return c === 0 ? ['despejado', 'sol'] : c <= 2 ? ['algo nublado', 'nubes'] : c === 3 ? ['nublado', 'nubes'] : c <= 48 ? ['con niebla', 'niebla'] : c <= 57 ? ['con llovizna', 'lluvia'] : c <= 67 ? ['lloviendo', 'lluvia'] : c <= 77 ? ['nevando', 'nieve'] : c <= 82 ? ['con chubascos', 'lluvia'] : ['con tormenta', 'tormenta'];
}
function weatherNow() { const w = LS.get('wx', null), c = LS.get('city', null); return w && c && w.city === c.name && Date.now() - w.ts < 3 * 3600e3 ? w : null; }
async function loadWeather(force) {
  const c = LS.get('city', null); if (!c || !ONLINE) return null;
  const old = LS.get('wx', null); if (!force && old && old.city === c.name && Date.now() - old.ts < 30 * 60000) return old;
  try {
    const j = await netJSON(`https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}&current=temperature_2m,weather_code,is_day&timezone=auto`, { timeout: 9000 });
    const cur = j && j.current; if (!cur) return weatherNow();
    const w = { ts: Date.now(), city: c.name, temp: Math.round(cur.temperature_2m), code: cur.weather_code, day: !!cur.is_day };
    LS.set('wx', w); return w;
  } catch { return weatherNow(); }
}
function weatherLine() {
  const w = weatherNow(); if (!w) return '';
  const [d, k] = wxDesc(w.code);
  const tip = k === 'lluvia' || k === 'tormenta' ? ' Día de música tranquila.' : (k === 'sol' && w.day && w.temp >= 22 ? ' Buen día para subir el ritmo.' : '');
  return ` En ${w.city} está ${d} y hace ${w.temp} grados.${tip}`;
}
function weatherBias() {
  const w = weatherNow(); if (!w) return 0;
  const k = wxDesc(w.code)[1];
  return k === 'lluvia' || k === 'tormenta' || k === 'niebla' ? -0.08 : k === 'nieve' ? -0.05 : (k === 'sol' && w.day && w.temp >= 20) ? 0.06 : 0;
}
async function tellWeather() {
  if (!LS.get('city', null)) { novaSay('Dime tu ciudad en Ajustes, en el apartado de tu DJ, y te digo qué tiempo hace.'); return; }
  const w = await loadWeather(false); novaSay(w ? weatherLine().trim() : 'No he podido mirar el tiempo. ¿Tienes internet?');
}
async function setCity(name) {
  if (!ONLINE) { toast('Esto funciona en la app de Android, con internet'); return; }
  try {
    const j = await netJSON(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(name)}&count=1&language=es&format=json`, { timeout: 9000 });
    const r = j && j.results && j.results[0]; if (!r) { toast('No encuentro esa ciudad'); return; }
    LS.set('city', { name: r.name, lat: r.latitude, lon: r.longitude, country: r.country || '' }); LS.set('wx', null);
    renderCity(); const w = await loadWeather(true);
    novaSay(`Apuntado: ${r.name}.${w ? weatherLine() : ''}`);
  } catch { toast('No he podido buscar la ciudad. ¿Tienes internet?'); }
}
function renderCity() {
  const c = LS.get('city', null);
  $('#cityIn').value = ''; $('#cityIn').placeholder = c ? `${c.name}${c.country ? ', ' + c.country : ''}` : 'Tu ciudad (opcional)';
  $('#cityClear').hidden = !c;
  $('#cityNote').textContent = !ONLINE ? 'Funciona en la app de Android, con internet.' : c ? `${dj.name} tiene en cuenta el tiempo que hace en ${c.name} para saludarte y elegir la música.` : `Si me dices tu ciudad, ${dj.name} tendrá en cuenta el tiempo que hace. No uso tu ubicación.`;
}
$('#cityForm').addEventListener('submit', e => { e.preventDefault(); const v = $('#cityIn').value.trim(); if (v) setCity(v); });
$('#cityClear').onclick = () => { LS.set('city', null); LS.set('wx', null); renderCity(); toast('Ciudad borrada'); };
/* Playlists Nova keeps up to date from what you actually listen to */
function novaLists() {
  const now = Date.now(), ev = memEvents.filter(e => !e.net && now - e.ts < 120 * 864e5);
  const count = pred => { const m = new Map(); for (const e of ev) if (!e.skip && (e.pct >= 0.5 || e.sec >= 60) && pred(e)) m.set(e.id, (m.get(e.id) || 0) + 1); return [...m.entries()].sort((a, b) => b[1] - a[1]).map(x => x[0]).filter(id => trackById(id) && !BRAIN.ban.has(id)); };
  const pool = poolTracks().filter(t => !t.demo), en = t => { const v = trackVec(t); return v ? v[0] : null; };
  return [
    { id: 'n-finde', name: 'Tus noches de fin de semana', desc: 'Lo que pones los viernes y sábados por la noche.', ids: count(e => (e.dow === 5 || e.dow === 6) && (e.hour >= 20 || e.hour < 4)) },
    { id: 'n-mananas', name: 'Tus mañanas', desc: 'Lo que más escuchas antes de mediodía.', ids: count(e => e.hour >= 6 && e.hour < 12) },
    { id: 'n-noches', name: 'Tus noches', desc: 'Lo que más escuchas cuando ya es tarde.', ids: count(e => e.hour >= 22 || e.hour < 5) },
    { id: 'n-nunca', name: 'Las que nunca saltas', desc: 'Siempre las escuchas enteras.', ids: pool.filter(t => plays(t) >= 3 && !(t.skips > 0)).sort((a, b) => plays(b) - plays(a)).map(t => t.id) },
    { id: 'n-energia', name: 'Para entrenar', desc: 'Tus canciones con más energía.', ids: pool.filter(t => en(t) !== null && en(t) >= 0.62).sort((a, b) => en(b) - en(a) + (liked(b) - liked(a)) * 0.2).map(t => t.id) },
    { id: 'n-calma', name: 'Para bajar revoluciones', desc: 'Lo más tranquilo de tu música.', ids: pool.filter(t => en(t) !== null && en(t) <= 0.38).sort((a, b) => en(a) - en(b)).map(t => t.id) }
  ].map(l => ({ ...l, ids: l.ids.slice(0, 30) })).filter(l => l.ids.length >= 5);
}
function renderNovaLists() {
  const box = $('#novaLists'); if (!box) return;
  const ls = novaLists();
  box.hidden = !ls.length;
  box.innerHTML = ls.length ? `<div class="eyebrow">Hechas por ${esc(dj.name)} · se actualizan solas</div><div class="lists">${ls.map(l => `<article class="panel pl-card"><div class="pl-covers">${l.ids.slice(0, 4).map(id => coverHTML(trackById(id))).join('')}</div><h3>${esc(l.name)}</h3><p>${esc(l.desc)}</p><div class="pl-meta">${l.ids.length} canciones</div><div class="pl-btns"><button class="btn small primary" data-nova-list="${l.id}">${ICON.playS} Escuchar</button></div></article>`).join('')}</div>` : '';
}
$('#novaLists').addEventListener('click', e => {
  const b = e.target.closest('[data-nova-list]'); if (!b) return;
  const l = novaLists().find(x => x.id === b.dataset.novaList); if (!l) return;
  playQueue(l.ids, 0, { source: 'nova' }); novaSay(`${l.name}. Empezamos con ${trackById(l.ids[0]).title}.`);
});
/* Quick replies under the request box while something plays */
function renderNovaChips() {
  const box = $('#novaChips'); if (!box) return;
  const t = current(), on = !!t && !t.net;
  box.hidden = !on;
  if (on && !box.childElementCount) box.innerHTML = ['Más movida', 'Más tranquila', '¿Por qué esta?', 'Más como esta', '¿Quién canta?'].map(s => `<button class="idea" data-cmd="${esc(s)}">${esc(s)}</button>`).join('');
}
$('#novaChips').addEventListener('click', e => { const b = e.target.closest('[data-cmd]'); if (b) novaCommand(b.dataset.cmd); });
$('#djMic').onclick = () => voiceAsk();
$('#npMic').onclick = () => voiceAsk();
$('#djMic').hidden = $('#npMic').hidden = !voiceAvailable();
renderCity();
const NV_WORKER_SRC = "/* Nova's own voice, in a worker so the app never stutters:\n   espeak-ng turns text into phonemes (piper-phonemize), and ONNX Runtime turns phonemes into sound (a Piper/VITS voice).\n   With the native engine (Android), this worker only does the phonemes: the sound is made outside the WebView. */\nlet session = null, cfg = null, speaker = 0, phon = null, phonOut = null, files = null;\nfunction phonemizer() {\n  if (phon) return phon;\n  phon = createPiperPhonemize({\n    noInitialRun: true,\n    print: d => { if (phonOut) { const f = phonOut; phonOut = null; try { f(JSON.parse(d).phoneme_ids); } catch (e) { f(null); } } },\n    printErr: () => {},\n    locateFile: u => u.endsWith('.wasm') ? files.phonWasm : u.endsWith('.data') ? files.phonData : u\n  });\n  return phon;\n}\nasync function phonemize(text) {\n  const m = await phonemizer();\n  return new Promise((res, rej) => {\n    phonOut = ids => ids ? res(ids) : rej(new Error('phonemize'));\n    try { m.callMain(['-l', cfg.espeak.voice, '--input', JSON.stringify([{ text }]), '--espeak_data', '/espeak-ng-data']); }\n    catch (e) { phonOut = null; rej(e); }\n  });\n}\nonmessage = async e => {\n  const m = e.data;\n  try {\n    if (m.type === 'init') {\n      files = m.files; cfg = m.config; speaker = m.speaker || 0;\n      if (!m.phonOnly) {\n        importScripts(files.ortJs);\n        ort.env.wasm.numThreads = 1;\n        ort.env.wasm.wasmPaths = { 'ort-wasm-simd.wasm': files.ortWasm, 'ort-wasm.wasm': files.ortWasm };\n        /* sentences of every length: without a memory arena and memory patterns, memory doesn't keep growing */\n        session = await ort.InferenceSession.create(m.model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all', enableCpuMemArena: false, enableMemPattern: false });\n        m.model = null;\n      }\n      importScripts(files.phonJs);\n      await phonemizer();\n      postMessage({ id: m.id, ok: true });\n    } else if (m.type === 'phon') {\n      const ids = await phonemize(m.text);\n      postMessage({ id: m.id, ok: true, ids });\n    } else if (m.type === 'speak') {\n      if (!session) throw new Error('sin motor');\n      const t0 = performance.now();\n      const ids = await phonemize(m.text);\n      const inf = cfg.inference || {};\n      const feeds = {\n        input: new ort.Tensor('int64', BigInt64Array.from(ids.map(BigInt)), [1, ids.length]),\n        input_lengths: new ort.Tensor('int64', BigInt64Array.from([BigInt(ids.length)]), [1]),\n        scales: new ort.Tensor('float32', Float32Array.from([inf.noise_scale ?? 0.667, (inf.length_scale ?? 1) * (m.lengthScale || 1), inf.noise_w ?? 0.8]), [3])\n      };\n      if (cfg.num_speakers > 1) feeds.sid = new ort.Tensor('int64', BigInt64Array.from([BigInt(speaker)]), [1]);\n      const out = await session.run(feeds);\n      const pcm = Float32Array.from(out.output.data);\n      for (const k in out) { try { out[k].dispose && out[k].dispose(); } catch (err) {} }\n      postMessage({ id: m.id, ok: true, pcm, sr: cfg.audio.sample_rate, ms: Math.round(performance.now() - t0) }, [pcm.buffer]);\n    }\n  } catch (err) { postMessage({ id: m.id, ok: false, error: String(err && err.message || err) }); }\n};\n";
/* ---------- 3.0 · Nova's own voice ----------
   A neural voice (Piper) that runs on the phone, without internet, after a one-time download.
   In the Android app the sound is made by the native engine (ONNX Runtime for Android: several cores, outside the WebView);
   the web engine (ONNX Runtime Web) is the fallback. espeak-ng turns the text into phonemes in a worker.
   Sentences are split so Nova starts talking sooner, what you ask always goes ahead of what's prepared in the background,
   and every phrase said is kept, so it's instant the next time. The system voice stays as the fallback. */
const NV = { st: LS.get('nv', null), on: LS.get('nvOn', true), worker: null, ready: null, n: 0, wait: new Map(), cache: new Map(), dl: null, cat: null, fails: 0,
  eng: null, q: [], running: false, natOk: null, noNative: false, natFails: 0, slow: 0, slowSaid: false, mig: null, cfg: null, speed: LS.get('nvSpeed', null) };
const NV_CACHE = 'orbita-voz-1', NV_KEY = n => 'https://orbita.voz/' + n, NV_FR = 'orbita-voz-frases';
const NV_HF = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/';
const NV_ORT = ['https://cdn.jsdelivr.net/npm/onnxruntime-web@1.18.0/dist/', 'https://unpkg.com/onnxruntime-web@1.18.0/dist/'];
const NV_PHON = ['https://cdn.jsdelivr.net/npm/@diffusionstudio/piper-wasm@1.0.0/build/', 'https://unpkg.com/@diffusionstudio/piper-wasm@1.0.0/build/'];
const NV_ENGINE = [
  ['ortJs', 'ort.wasm.min.js', NV_ORT, 'text/javascript', 0.15], ['ortWasm', 'ort-wasm-simd.wasm', NV_ORT, 'application/wasm', 10.6],
  ['phonJs', 'piper_phonemize.js', NV_PHON, 'text/javascript', 0.12], ['phonWasm', 'piper_phonemize.wasm', NV_PHON, 'application/wasm', 0.64],
  ['phonData', 'piper_phonemize.data', NV_PHON, 'application/octet-stream', 18.1]
];
const NV_NATIVE = capPlugin('NovaVoice');
const NV_CHUNK = 1572864;   // 1.5 MB per piece when saving the voice for the native engine
const NV_ACCENT = { es_ES: 'España', es_MX: 'México', es_AR: 'Argentina', es_US: 'Estados Unidos', es_CO: 'Colombia', es_CL: 'Chile' };
const NV_QUALITY = { x_low: 'básica', low: 'normal', medium: 'buena', high: 'muy buena' };
/* Used when the catalogue can't be read: the Spanish voices Piper publishes */
const NV_KNOWN = [['es_ES-sharvard-medium', 77, { F: 1, M: 0 }], ['es_ES-davefx-medium', 63], ['es_MX-ald-medium', 63], ['es_AR-daniela-high', 114], ['es_MX-claude-high', 63], ['es_ES-carlfm-x_low', 28]];
const nvPathOf = key => { const [code, name, q] = key.split('-'); return `${code.split('_')[0]}/${code}/${name}/${q}/${key}.onnx`; };
const nvModelName = () => NV.st.key + '.onnx';
function nvHash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
const nvKey = text => 'f' + nvHash(`${NV.st.key}|${NV.st.speaker || 0}|${(dj.rate || 1).toFixed(2)}|${text}`) + '-' + text.length;
function nvOptions(list) {
  const out = [];
  for (const v of list) {
    const [code, name, q] = v.key.split('-');
    const base = { key: v.key, path: v.path || nvPathOf(v.key), mb: Math.round(v.mb), accent: NV_ACCENT[code] || code, quality: NV_QUALITY[q] || q, heavy: q === 'high', name: cap(name.replace(/_/g, ' ')) };
    const sp = v.speakers && Object.keys(v.speakers).length > 1 ? v.speakers : null;
    if (sp) for (const [s, id] of Object.entries(sp).slice(0, 4)) out.push({ ...base, speaker: id, label: `${base.name} · ${s === 'F' ? 'mujer' : s === 'M' ? 'hombre' : 'voz ' + s}` });
    else out.push({ ...base, speaker: 0, label: base.name });
  }
  const rank = o => (o.key === 'es_ES-sharvard-medium' && o.speaker === 1 ? -10 : 0) + (o.accent === 'España' ? -2 : 0) - ({ 'muy buena': 3, buena: 2, normal: 1 }[o.quality] || 0);
  return out.sort((a, b) => rank(a) - rank(b));
}
async function nvCatalogue() {
  if (NV.cat) return NV.cat;
  try {
    const r = await fetch(NV_HF + 'voices.json'); if (!r.ok) throw 0;
    const all = await r.json(), list = [];
    for (const v of Object.values(all)) {
      if (!v || !v.language || v.language.family !== 'es') continue;
      const f = Object.entries(v.files || {}).find(([p]) => p.endsWith('.onnx'));
      if (!f) continue;
      list.push({ key: v.key, path: f[0], mb: (f[1].size_bytes || 6e7) / 1048576, speakers: v.speaker_id_map });
    }
    if (!list.length) throw 0;
    return (NV.cat = nvOptions(list));
  } catch { return (NV.cat = nvOptions(NV_KNOWN.map(([key, mb, speakers]) => ({ key, mb, speakers })))); }
}
const nvUsable = () => !!(NV.st && NV.on && NV.fails < 3 && window.Worker && window.caches);
/* Is the native engine there (Android app with the module) and working? */
function nvNativeOk() {
  if (!NV_NATIVE) return Promise.resolve(false);
  if (!NV.natOk) NV.natOk = NV_NATIVE.status().then(s => !!(s && s.available), () => false);
  return NV.natOk;
}
const nvB64 = blob => new Promise((res, rej) => { const fr = new FileReader(); fr.onload = () => { const s = String(fr.result); res(s.slice(s.indexOf(',') + 1)); }; fr.onerror = () => rej(fr.error); fr.readAsDataURL(blob); });
/* Download, with progress, trying a second address if the first fails */
async function nvFetch(urls, onBytes, signal) {
  let last;
  for (const u of urls) {
    try {
      const r = await fetch(u, { signal }); if (!r.ok) throw new Error('http ' + r.status);
      if (!r.body || !r.body.getReader) { const b = await r.arrayBuffer(); onBytes(b.byteLength); return b; }
      const rd = r.body.getReader(), parts = []; let n = 0;
      for (;;) { const { done, value } = await rd.read(); if (done) break; parts.push(value); n += value.length; onBytes(value.length); }
      const out = new Uint8Array(n); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
      return out.buffer;
    } catch (e) { if (signal && signal.aborted) throw e; last = e; }
  }
  throw last || new Error('download');
}
/* The same, but saved straight into the native engine's folder, piece by piece (the voice never sits whole in the WebView's memory) */
async function nvFetchToNative(urls, name, onBytes, signal) {
  let last;
  for (const u of urls) {
    try {
      const r = await fetch(u, { signal }); if (!r.ok) throw new Error('http ' + r.status);
      let first = true, pend = [], pn = 0;
      const flush = async done => { const data = pn ? await nvB64(new Blob(pend)) : ''; pend = []; pn = 0; await NV_NATIVE.writeChunk({ name, data, append: !first, done }); first = false; };
      if (r.body && r.body.getReader) {
        const rd = r.body.getReader();
        for (;;) { const { done, value } = await rd.read(); if (done) break; pend.push(value); pn += value.length; onBytes(value.length); if (pn >= NV_CHUNK) await flush(false); }
      } else { const b = await r.arrayBuffer(); onBytes(b.byteLength); pend.push(new Uint8Array(b)); pn = b.byteLength; }
      await flush(true);
      return;
    } catch (e) { if (signal && signal.aborted) throw e; last = e; }
  }
  throw last || new Error('download');
}
/* A voice installed before the native engine existed: moved to it once, without downloading it again */
async function nvMigrate(cache) {
  const r = await cache.match(NV_KEY('voz.onnx')); if (!r) return false;
  const blob = await r.blob(), name = nvModelName();
  NV.mig = { done: 0, total: blob.size }; renderNV();
  try {
    for (let o = 0, first = true; o < blob.size; o += NV_CHUNK, first = false) {
      await NV_NATIVE.writeChunk({ name, data: await nvB64(blob.slice(o, o + NV_CHUNK)), append: !first, done: o + NV_CHUNK >= blob.size });
      NV.mig.done = Math.min(blob.size, o + NV_CHUNK); renderNVProgress();
    }
  } finally { NV.mig = null; renderNV(); }
  try { await cache.delete(NV_KEY('voz.onnx')); } catch {}
  return true;
}
async function nvClearPhrases() {
  try { await caches.delete(NV_FR); } catch {}
  LS.set('nvFr', []); NV.cache.clear();
  if (NV_NATIVE && await nvNativeOk()) { try { await NV_NATIVE.clear({ voices: false }); } catch {} }
}
async function nvInstall(opt) {
  if (NV.dl) return;
  if (!ONLINE && !STANDALONE) { toast('La voz propia se instala en la app de Android, con internet'); return; }
  const ctl = new AbortController(), cache = await caches.open(NV_CACHE);
  NV.dl = { opt, done: 0, total: 0, ctl }; renderNV();
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); } catch {}
  try {
    const native = await nvNativeOk();
    const jobs = [];
    for (const [, name, bases, type, mb] of NV_ENGINE) if (!(await cache.match(NV_KEY(name)))) jobs.push({ name, urls: bases.map(b => b + name), type, mb });
    jobs.push({ name: 'voz.onnx.json', urls: [NV_HF + opt.path + '.json'], type: 'application/json', mb: 0.01 });
    NV.dl.total = (jobs.reduce((a, j) => a + j.mb, 0) + opt.mb) * 1048576;
    let t = 0;
    const tick = n => { NV.dl.done += n; if (Date.now() - t > 250) { t = Date.now(); renderNVProgress(); } };
    const got = [];
    for (const j of jobs) got.push([j, await nvFetch(j.urls, tick, ctl.signal)]);
    const cfg = JSON.parse(new TextDecoder().decode(got.find(([j]) => j.name === 'voz.onnx.json')[1]));
    if (!cfg.audio || !cfg.espeak) throw new Error('config');
    if (native) await nvFetchToNative([NV_HF + opt.path], opt.key + '.onnx', tick, ctl.signal);
    else got.push([{ name: 'voz.onnx', type: 'application/octet-stream' }, await nvFetch([NV_HF + opt.path], tick, ctl.signal)]);
    for (const [j, buf] of got) await cache.put(NV_KEY(j.name), new Response(buf, { headers: { 'Content-Type': j.type } }));
    if (native) { try { await cache.delete(NV_KEY('voz.onnx')); } catch {} try { await NV_NATIVE.clear({ keep: opt.key + '.onnx' }); } catch {} }
    const changed = !NV.st || NV.st.key !== opt.key || (NV.st.speaker || 0) !== (opt.speaker || 0);
    NV.st = { key: opt.key, speaker: opt.speaker, label: opt.label, accent: opt.accent, mb: opt.mb, heavy: !!opt.heavy, ts: Date.now() };
    LS.set('nv', NV.st); NV.on = true; LS.set('nvOn', true); NV.fails = 0; NV.slow = 0; NV.slowSaid = false; NV.noNative = false; NV.natFails = 0;
    if (changed) await nvClearPhrases();
    nvReset(); NV.dl = null; renderNV();
    await nvSpeedTest().catch(() => {});
    renderNV(); toast('Voz propia instalada'); haptic(12);
    speakTTS(`Hola, soy ${dj.name}. Esta es mi voz nueva, y funciona sin internet.`);
  } catch (e) {
    const aborted = ctl.signal.aborted; NV.dl = null; renderNV();
    if (!aborted) toast('No he podido descargar la voz. Comprueba tu conexión y vuelve a intentarlo.', 4200);
  }
}
async function nvRemove() {
  try { await caches.delete(NV_CACHE); } catch {}
  if (NV_NATIVE && await nvNativeOk()) { try { await NV_NATIVE.clear({}); } catch {} }
  await nvClearPhrases();
  nvReset(); NV.st = null; LS.set('nv', null); NV.speed = null; LS.set('nvSpeed', null); renderNV(); toast('Voz propia borrada: vuelvo a la voz del sistema');
}
function nvReset() {
  if (NV.worker) { try { NV.worker.terminate(); } catch {} }
  NV.worker = null; NV.ready = null; NV.eng = null;
  for (const r of NV.wait.values()) r({ ok: false, error: 'reset' }); NV.wait.clear(); NV.cache.clear();
  for (const j of NV.q.splice(0)) j.rej(new Error('reset'));
}
function nvCall(msg, tr) { return new Promise(res => { if (!NV.worker) { res({ ok: false, error: 'sin motor' }); return; } const id = ++NV.n; NV.wait.set(id, res); NV.worker.postMessage({ ...msg, id }, tr || []); }); }
/* The voice file for the web engine: from the app's storage, or from the native engine's folder */
async function nvModelBuffer(cache) {
  const mr = await cache.match(NV_KEY('voz.onnx')); if (mr) return mr.arrayBuffer();
  if (NV_NATIVE && await nvNativeOk()) {
    const info = await NV_NATIVE.fileInfo({ name: nvModelName() }).catch(() => null);
    if (info && info.exists) { const r = await fetch(window.Capacitor.convertFileSrc(info.path)); if (r.ok) return r.arrayBuffer(); }
  }
  throw new Error('falta la voz');
}
function nvInit() {
  if (NV.ready) return NV.ready;
  NV.ready = (async () => {
    const cache = await caches.open(NV_CACHE), files = {};
    for (const [k, name] of NV_ENGINE) { const r = await cache.match(NV_KEY(name)); if (!r) throw new Error('falta ' + name); files[k] = URL.createObjectURL(await r.blob()); }
    const cr = await cache.match(NV_KEY('voz.onnx.json')); if (!cr) throw new Error('falta la voz');
    const config = await cr.json(); NV.cfg = config;
    let native = false;
    if (!NV.noNative && await nvNativeOk()) {
      try {
        const name = nvModelName();
        let info = await NV_NATIVE.fileInfo({ name });
        if (!(info.exists && info.size > 1e5) && await nvMigrate(cache)) info = await NV_NATIVE.fileInfo({ name });
        if (info.exists && info.size > 1e5) { const r = await NV_NATIVE.load({ name }); NV.threads = r.threads; native = true; }
      } catch (e) { NV.nativeErr = String(e && e.message || e); }
    }
    const model = native ? null : await nvModelBuffer(cache);
    NV.worker = new Worker(URL.createObjectURL(new Blob([NV_WORKER_SRC], { type: 'text/javascript' })));
    NV.worker.onmessage = e => { const r = NV.wait.get(e.data.id); if (r) { NV.wait.delete(e.data.id); r(e.data); } };
    NV.worker.onerror = () => { NV.fails++; };
    const r = await nvCall({ type: 'init', files, config, speaker: NV.st.speaker || 0, model, phonOnly: native }, model ? [model] : []);
    if (!r.ok) throw new Error(r.error);
    NV.eng = native ? 'nativo' : 'web';
    try { await nvRunReady('Hola.', true); } catch {}   // the first sentence is always the slowest: get it out of the way now
    renderNV();
    return true;
  })();
  NV.ready.catch(() => { NV.fails++; if (NV.worker) { try { NV.worker.terminate(); } catch {} } NV.worker = null; NV.ready = null; NV.eng = null; });
  return NV.ready;
}
function nvWav(pcm, sr) {
  const n = pcm.length, buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
  const w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, sr, true); v.setUint32(28, sr * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, n * 2, true);
  for (let i = 0; i < n; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, pcm[i])) * 32767, true);
  return new Blob([buf], { type: 'audio/wav' });
}
/* Phrases already said, kept for next time (web engine; the native engine keeps its own) */
async function nvPhraseGet(key) { try { const c = await caches.open(NV_FR), r = await c.match('https://orbita.voz/f/' + key); return r ? await r.blob() : null; } catch { return null; } }
async function nvPhrasePut(key, blob) {
  try {
    const c = await caches.open(NV_FR); await c.put('https://orbita.voz/f/' + key, new Response(blob, { headers: { 'Content-Type': 'audio/wav' } }));
    const idx = LS.get('nvFr', []).filter(k => k !== key); idx.push(key);
    while (idx.length > 250) c.delete('https://orbita.voz/f/' + idx.shift());
    LS.set('nvFr', idx);
  } catch {}
}
/* How fast this voice is on this phone (seconds of work per second of speech) */
function nvSpeed(ms, dur) {
  if (!dur || dur < 0.4 || !ms) return;
  const rtf = ms / (dur * 1000), prev = NV.speed && NV.speed.key === NV.st.key && NV.speed.eng === NV.eng ? NV.speed.rtf : null;
  NV.speed = { key: NV.st.key, eng: NV.eng, rtf: prev == null ? rtf : prev * 0.75 + rtf * 0.25, n: ((NV.speed && NV.speed.n) || 0) + 1 };
  if (NV.speed.n % 4 === 1) LS.set('nvSpeed', NV.speed);
}
async function nvSpeedTest() {
  await nvInit();
  const r = await nvRun('Esta es una prueba rápida de mi voz nueva, para ver cómo va en tu móvil.', true);
  if (r.ms && r.dur) { NV.speed = { key: NV.st.key, eng: NV.eng, rtf: r.ms / (r.dur * 1000), n: 1 }; LS.set('nvSpeed', NV.speed); }
}
/* One sentence → sound. Returns { src } (a file of the native engine) or { blob } (web engine) */
async function nvRun(text, warm) { await nvInit(); return nvRunReady(text, warm); }
async function nvRunReady(text, warm) {
  const ls = 1 / Math.max(0.6, Math.min(1.6, dj.rate || 1)), inf = NV.cfg.inference || {}, sr = NV.cfg.audio.sample_rate;
  const key = warm ? null : nvKey(text);
  if (NV.eng === 'nativo') {
    const ph = await nvCall({ type: 'phon', text }); if (!ph.ok) throw new Error(ph.error);
    let r;
    try { r = await NV_NATIVE.synth({ ids: ph.ids, key, noise: inf.noise_scale ?? 0.667, length: (inf.length_scale ?? 1) * ls, noiseW: inf.noise_w ?? 0.8, sid: NV.st.speaker || 0, sampleRate: sr }); }
    catch (e) { if (++NV.natFails >= 2) { NV.noNative = true; nvReset(); } throw e; }
    NV.natFails = 0;
    const dur = r.samples ? r.samples / sr : 0;
    if (!r.cached && !warm) nvSpeed(r.ms, dur);
    return { src: window.Capacitor.convertFileSrc(r.path), ms: r.ms, dur };
  }
  if (key) { const hit = await nvPhraseGet(key); if (hit) return { blob: hit }; }
  const r = await nvCall({ type: 'speak', text, lengthScale: ls }); if (!r.ok) throw new Error(r.error);
  const dur = r.pcm.length / r.sr;
  if (!warm) nvSpeed(r.ms, dur);
  const blob = nvWav(r.pcm, r.sr); if (key) nvPhrasePut(key, blob);
  return { blob, ms: r.ms, dur };
}
/* A queue with priorities: what Nova says now (0) goes before the rest of the sentence (1) and before what she prepares in the background (2) */
function nvSynth(text, prio = 1) {
  const k = text + '|' + (dj.rate || 1);
  if (NV.cache.has(k)) {
    const p = NV.cache.get(k); NV.cache.delete(k); NV.cache.set(k, p);
    const j = NV.q.find(x => x.k === k); if (j && j.prio > prio) j.prio = prio;
    return p;
  }
  let job; const p = new Promise((res, rej) => { job = { k, text, prio, res, rej, ts: Date.now() }; });
  p.catch(() => { if (NV.cache.get(k) === p) NV.cache.delete(k); });
  NV.cache.set(k, p); while (NV.cache.size > 30) NV.cache.delete(NV.cache.keys().next().value);
  NV.q.push(job); nvPump();
  return p;
}
async function nvPump() {
  if (NV.running) return;
  NV.running = true;
  try {
    while (NV.q.length) {
      NV.q.sort((a, b) => a.prio - b.prio || a.ts - b.ts);
      const j = NV.q.shift();
      if (j.prio >= 2 && Date.now() - j.ts > 90000) { j.rej(new Error('caducada')); continue; }
      try { j.res(await nvRun(j.text)); } catch (e) { j.rej(e); }
    }
  } finally { NV.running = false; }
}
/* Pieces of speech. The first one is short (2-3 seconds of voice) so Nova starts almost at once; each next piece
   is bigger, as big as the voice can prepare while the previous one is playing, so there are no gaps.
   Cuts go at commas or before words like «y», «que», «pero» so the voice doesn't sound chopped. */
const NV_CONN = /^(y|e|o|u|ni|que|pero|porque|cuando|mientras|aunque|donde|pues|así)$/i, NV_CONN2 = /^(con|para|sin|hasta|desde|por|como|si|en)$/i;
function nvCut(s, lim) {
  if (s.length <= lim * 1.25) return [s, ''];
  let punct = -1; const re = /[,;:—–]\s+/g; let m;
  while ((m = re.exec(s))) { const i = m.index + 1; if (i > lim * 1.3) break; if (i >= Math.max(10, lim * 0.35)) punct = i; }
  if (punct > 0 && s.length - punct > 10) return [s.slice(0, punct).trim(), s.slice(punct).trim()];
  const w = s.split(' '), bare = x => (x || '').replace(/[^\wáéíóúñü]/gi, '');
  let pos = 0, conn = -1, conn2 = -1, plain = -1;
  for (let i = 0; i < w.length - 1; i++) {
    pos += w[i].length;
    if (pos > lim * 1.3) break;
    const cur = bare(w[i]), nxt = bare(w[i + 1]);
    if (pos >= lim * 0.45 && !NV_CONN.test(cur) && !NV_CONN2.test(cur)) {   // never leave «y», «que», «con»... at the end of a piece
      if (NV_CONN.test(nxt)) conn = pos; else if (NV_CONN2.test(nxt)) conn2 = pos;
      if (pos <= lim) plain = pos;
    }
    pos += 1;
  }
  const at = conn > 0 ? conn : conn2 > 0 ? conn2 : plain;
  return at > 0 && s.length - at > 10 ? [s.slice(0, at).trim(), s.slice(at).trim()] : [s, ''];
}
function nvChunks(text) {
  const sp = NV.speed && NV.st && NV.speed.key === NV.st.key && NV.speed.eng === NV.eng ? NV.speed.rtf : 0;
  const grow = Math.max(1.5, Math.min(3, 0.85 / (sp || 0.45)));   // how much bigger the next piece can be without a gap
  const out = []; let lim = 40;
  for (let s of sentences(text)) {
    while (s) {
      const [head, rest] = nvCut(s, lim);
      out.push(head); s = rest;
      lim = Math.min(160, Math.round(Math.max(lim, head.length) * grow));
    }
  }
  return out.filter(s => /[a-záéíóúñü0-9]/i.test(s));
}
const nvWithin = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('lenta')), ms))]);
/* Two players taking turns: while one speaks, the next piece is already loaded */
const nvPlayers = [new Audio(), new Audio()]; let nvTurn = 0;
NV_STOP = () => { for (const a of nvPlayers) if (a._stop) a._stop(); };
function nvSrc(item) { if (item.src) return { url: item.src, free: () => {} }; const url = URL.createObjectURL(item.blob); return { url, free: () => URL.revokeObjectURL(url) }; }
function nvLoad(a, item) { const s = nvSrc(item); a._free = s.free; a.preload = 'auto'; a.src = s.url; try { a.load(); } catch {} }
function nvPlay(a, my) {
  return new Promise(res => {
    if (my !== dj.talkGen) { if (a._free) a._free(); return res(); }
    let done = false;
    const fin = () => { if (done) return; done = true; clearTimeout(to); a.onended = a.onerror = null; a._stop = null; if (a._free) { a._free(); a._free = null; } res(); };
    a.onended = a.onerror = fin;
    a.volume = Math.min(1, baseVol * 1.3);
    const to = setTimeout(fin, 30000);
    a._stop = () => { try { a.pause(); } catch {} fin(); };
    a.play().catch(fin);
  });
}
async function speakNeural(clean, parts, my) {
  const chunks = nvChunks(clean);
  if (!chunks.length) return;
  const ps = chunks.map((c, i) => nvSynth(c, i === 0 ? 0 : 1));
  ps.forEach(p => p.catch(() => {}));
  /* If the first piece takes too long, the system voice says this one (and the voice keeps preparing in the background) */
  let first;
  try { first = await nvWithin(ps[0], NV.eng === 'nativo' ? 5000 : NV.eng ? 4000 : 9000); }
  catch (e) {
    if (e && e.message === 'lenta') {
      NV.slow++;
      if (NV.slow >= 3 && !NV.slowSaid && NV.st && NV.st.heavy) { NV.slowSaid = true; setTimeout(() => toast(`La voz «${NV.st.label}» va lenta en este móvil. En Ajustes › Tu DJ puedes elegir una de calidad buena, que es más rápida.`, 6000), 2500); }
    }
    throw e;
  }
  if (my !== dj.talkGen) return;
  $('#dj').classList.add('speaking'); if (!act().paused) tween('duck', 0.3, 380);
  setBubble(clean, dj.name, 15 * (dj.rate || 1));
  let a = nvPlayers[nvTurn = 1 - nvTurn];
  nvLoad(a, first);
  for (let i = 0; i < chunks.length; i++) {
    if (my !== dj.talkGen) break;
    const playing = nvPlay(a, my);
    let nextA = null;
    if (i + 1 < chunks.length) {
      const item = await ps[i + 1].catch(() => null);
      if (item && my === dj.talkGen) { nextA = nvPlayers[nvTurn = 1 - nvTurn]; nvLoad(nextA, item); }
    }
    await playing;
    if (!nextA) break;
    a = nextA;
  }
  if (my === dj.talkGen) { $('#dj').classList.remove('speaking'); tween('duck', 1, 1100); }
  bubbleFinish();
}
const ttsClean = text => String(text).replace(/[«»"“”]/g, '').replace(/\s·\s/g, ', ').replace(/\s+/g, ' ').trim();
/* Prepare the next song's introduction in advance, so Nova speaks the moment the song starts */
function nvPrepareIntro(t) {
  if (!nvUsable() || !t || t.net || !dj.voiceOn || dj.freq === 'inicio') return;
  if (!state.intros[t.id]) state.intros[t.id] = introFor(t);
  for (const p of nvChunks(ttsClean(state.intros[t.id])).slice(0, 2)) nvSynth(p, 2).catch(() => {});   // just the start: the rest is prepared while she speaks, so nothing long blocks what you ask her
}
/* Settings card */
function nvSpeedLabel() {
  const s = NV.speed; if (!s || !NV.st || s.key !== NV.st.key || !s.rtf || (NV.eng && s.eng !== NV.eng)) return '';
  return s.rtf < 0.3 ? 'responde rápido' : s.rtf < 0.75 ? 'velocidad normal' : '<b>va lenta en este móvil</b>';
}
function renderNVProgress() {
  const d = NV.dl || NV.mig, el = $('#nvBar'); if (!d || !el) return;
  const pct = d.total ? Math.min(100, Math.round(d.done / d.total * 100)) : 0;
  el.style.width = pct + '%'; $('#nvPct').textContent = `${pct} % · ${Math.round(d.done / 1048576)} de ${Math.round(d.total / 1048576)} MB`;
}
async function renderNV(showList) {
  const box = $('#nvBox'); if (!box) return;
  const credits = '<p class="note nv-credits">Motor: Piper (MIT) con espeak-ng (GPL-3.0) y ONNX Runtime (MIT). Las voces son de los conjuntos de datos que indica su ficha en el catálogo de Piper.</p>';
  if (NV.dl || NV.mig) {
    const title = NV.dl ? `Descargando la voz «${esc(NV.dl.opt.label)}»` : 'Pasando la voz al motor nativo (una sola vez)';
    box.innerHTML = `<div class="d-text"><b>${title}</b><span id="nvPct">0 %</span></div><div class="bar nv-bar"><i id="nvBar"></i></div>${NV.dl ? '<div class="row-wrap"><button class="btn small" data-nv="cancel">Cancelar</button></div><p class="note">Se descarga una sola vez. Después funciona sin internet.</p>' : '<p class="note">Así hablará antes y gastará menos memoria. Mientras tanto, Nova usa la voz del sistema.</p>'}`;
    renderNVProgress(); return;
  }
  const list = showList ? await nvCatalogue() : null;
  const native = await nvNativeOk();
  const eng = NV.eng === 'nativo' ? 'motor nativo' : NV.eng === 'web' ? 'motor web' : native && !NV.noNative ? 'motor nativo' : 'motor web';
  const sp = nvSpeedLabel();
  const head = NV.st
    ? `<div class="d-text"><b>Voz propia: ${esc(NV.st.label)}</b><span>${esc(NV.st.accent || '')} · ${NV.st.mb} MB · ${eng}${sp ? ' · ' + sp : ''} · funciona sin internet${NV.fails >= 3 ? ' · <b>no ha podido arrancar en este móvil</b>' : ''}</span></div>
       <div class="row-wrap"><button class="toggle" id="tNV" aria-pressed="${NV.on}"><span class="sw"></span>Usar mi voz propia</button><button class="btn small" data-nv="test">Probar</button><button class="btn small" data-nv="list">Cambiar de voz</button><button class="btn small" data-nv="remove">Borrar</button></div>`
    : `<div class="d-text"><b>Voz propia para ${esc(dj.name)}</b><span>Una voz neuronal en español que suena igual en todos los móviles y no necesita internet. Se descarga una vez (unos 100 MB con el motor).</span></div>${list ? '' : '<div class="row-wrap"><button class="btn small primary" data-nv="list">Elegir voz</button></div>'}`;
  const opts = list ? `<ul class="voices nv-list">${list.map((o, i) => `<li class="voice${NV.st && NV.st.key === o.key && (NV.st.speaker || 0) === o.speaker ? ' on' : ''}"><div class="v-text"><b>${esc(o.label)}${i === 0 ? ' · recomendada' : ''}</b><span>${esc(o.accent)} · calidad ${esc(o.quality)}${o.heavy ? ' (más pesada: mejor en móviles potentes)' : ''} · ${o.mb} MB</span></div><button class="btn small" data-nv-get="${i}">Descargar</button></li>`).join('')}</ul><p class="note">Si cambias de voz, se borra la anterior. Para oír una antes de descargarla no hay muestras: elige, prueba y cambia si no te convence.</p>` : '';
  box.innerHTML = head + opts + credits;
}
$('#nvBox').addEventListener('click', async e => {
  const b = e.target.closest('[data-nv],[data-nv-get],#tNV'); if (!b) return;
  if (b.id === 'tNV') { NV.on = !NV.on; LS.set('nvOn', NV.on); setToggle(b, NV.on); toast(NV.on ? 'Nova usará su voz propia' : 'Nova usará la voz del sistema'); return; }
  if (b.dataset.nvGet !== undefined) { const o = (await nvCatalogue())[+b.dataset.nvGet]; if (o) nvInstall(o); return; }
  const a = b.dataset.nv;
  if (a === 'list') renderNV(true);
  else if (a === 'cancel' && NV.dl) NV.dl.ctl.abort();
  else if (a === 'remove') { if (b.dataset.sure) nvRemove(); else { b.dataset.sure = '1'; b.textContent = '¿Seguro? Toca otra vez'; setTimeout(() => { delete b.dataset.sure; b.textContent = 'Borrar'; }, 3000); } }
  else if (a === 'test') { NV.fails = 0; speakTTS(`Hola, soy ${dj.name}. Así sueno con mi voz propia. ¿Te gusta?`); }
});
renderNV();
const NB_WORKER_SRC = "/* Nova's understanding, in a worker so the app never stutters:\n   a multilingual sentence model (ONNX Runtime) turns each phrase into a vector of meaning,\n   and phrases that mean the same land close together, whatever the words. */\nlet session = null, tok = null, cfg = null, bank = null;\n\n/* ---- Tokenizer: SentencePiece Unigram read from tokenizer.json (the format of XLM-R models) ---- */\nconst NMT_DROP = /[\\u0001-\\u0008\\u000B\\u000E-\\u001F\\u007F\\u008F\\u009F]/g;\nconst NMT_SPACE = /[\\u0009\\u000A\\u000C\\u000D\\u1680\\u200B-\\u200F\\u2028\\u2029\\u2581\\uFEFF\\uFFFD]/g;\nfunction normalizer(n) {\n  if (!n) return s => s;\n  switch (n.type) {\n    case 'Sequence': { const fs = (n.normalizers || []).map(normalizer); return s => fs.reduce((a, f) => f(a), s); }\n    case 'NFKC': return s => s.normalize('NFKC');\n    case 'NFC': return s => s.normalize('NFC');\n    case 'NFKD': return s => s.normalize('NFKD');\n    case 'NFD': return s => s.normalize('NFD');\n    case 'Nmt': return s => s.replace(NMT_DROP, '').replace(NMT_SPACE, ' ');\n    /* SentencePiece's compiled table is essentially NFKC plus the Nmt clean-up */\n    case 'Precompiled': return s => s.replace(NMT_DROP, '').replace(NMT_SPACE, ' ').normalize('NFKC');\n    case 'Lowercase': return s => s.toLowerCase();\n    case 'Strip': return s => { if (n.strip_left !== false) s = s.replace(/^\\s+/, ''); if (n.strip_right !== false) s = s.replace(/\\s+$/, ''); return s; };\n    case 'StripAccents': return s => s.normalize('NFD').replace(/[̀-ͯ]/g, '');\n    case 'Replace': {\n      const p = n.pattern || {}; const re = p.Regex !== undefined ? new RegExp(p.Regex, 'gu') : new RegExp(String(p.String).replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&'), 'g');\n      return s => s.replace(re, n.content);\n    }\n    case 'Prepend': return s => n.prepend + s;\n    case 'BertNormalizer': return s => {\n      if (n.clean_text !== false) s = s.replace(NMT_DROP, '').replace(/\\s/g, ' ');\n      if (n.lowercase !== false) s = s.toLowerCase();\n      if (n.strip_accents || (n.strip_accents == null && n.lowercase !== false)) s = s.normalize('NFD').replace(/[̀-ͯ]/g, '');\n      return s;\n    };\n    default: return s => s;\n  }\n}\nfunction metaspaceOf(pre) {\n  if (!pre) return null;\n  if (pre.type === 'Metaspace') return pre;\n  if (pre.type === 'Sequence') for (const p of pre.pretokenizers || []) { const m = metaspaceOf(p); if (m) return m; }\n  return null;\n}\nfunction specialIds(j, ids) {\n  const p = j.post_processor, look = name => { const a = (j.added_tokens || []).find(t => t.content === name); return a ? a.id : ids.get(name); };\n  const fromTemplate = pp => {\n    const pre = [], post = []; let seen = false;\n    for (const it of pp.single || []) {\n      if (it.Sequence) { seen = true; continue; }\n      if (it.SpecialToken) { const st = (pp.special_tokens || {})[it.SpecialToken.id]; const v = st ? st.ids : [look(it.SpecialToken.id)]; (seen ? post : pre).push(...v); }\n    }\n    return { pre, post };\n  };\n  if (p) {\n    const list = p.type === 'Sequence' ? p.processors || [] : [p];\n    for (const pp of list) {\n      if (pp.type === 'TemplateProcessing') return fromTemplate(pp);\n      if (pp.type === 'RobertaProcessing' || pp.type === 'BertProcessing') return { pre: [pp.cls[1]], post: [pp.sep[1]] };\n    }\n  }\n  const cls = look('<s>') ?? look('[CLS]'), sep = look('<\/s>') ?? look('[SEP]');\n  return { pre: cls != null ? [cls] : [], post: sep != null ? [sep] : [] };\n}\nfunction buildTok(j) {\n  const m = j.model; if (!m || m.type !== 'Unigram' || !Array.isArray(m.vocab)) throw new Error('El tokenizador no es de tipo Unigram');\n  const ids = new Map(), scores = new Float32Array(m.vocab.length); let min = Infinity, maxLen = 1;\n  m.vocab.forEach(([p, s], i) => { if (!ids.has(p)) ids.set(p, i); scores[i] = s; if (s < min) min = s; const l = [...p].length; if (l > maxLen) maxLen = l; });\n  const ms = metaspaceOf(j.pre_tokenizer), rep = ms ? ms.replacement || '\\u2581' : '\\u2581';\n  const prefix = !ms || ms.add_prefix_space !== false && ms.prepend_scheme !== 'never';\n  return { ids, scores, unk: m.unk_id ?? ids.get('<unk>') ?? 0, unkScore: min - 10, maxLen: Math.min(maxLen, 48), norm: normalizer(j.normalizer), rep, prefix, sp: specialIds(j, ids) };\n}\n/* Best split of one word into pieces (Viterbi over the piece scores) */\nfunction viterbi(T, word) {\n  const ch = [...word], n = ch.length, best = new Float64Array(n + 1).fill(-Infinity), from = new Int32Array(n + 1), pid = new Int32Array(n + 1);\n  best[0] = 0;\n  for (let i = 0; i < n; i++) {\n    if (best[i] === -Infinity) continue;\n    let s = '', single = false;\n    for (let l = 1; l <= T.maxLen && i + l <= n; l++) {\n      s += ch[i + l - 1];\n      const id = T.ids.get(s); if (id === undefined) continue;\n      if (l === 1) single = true;\n      const v = best[i] + T.scores[id];\n      if (v > best[i + l]) { best[i + l] = v; from[i + l] = i; pid[i + l] = id; }\n    }\n    if (!single) { const v = best[i] + T.unkScore; if (v > best[i + 1]) { best[i + 1] = v; from[i + 1] = i; pid[i + 1] = T.unk; } }\n  }\n  const out = []; for (let k = n; k > 0; k = from[k]) out.push(pid[k]);\n  out.reverse();\n  return out.filter((id, i) => !(id === T.unk && out[i - 1] === T.unk));   // consecutive unknowns become one\n}\nfunction encode(T, text, max = 128) {\n  const words = T.norm(String(text).trim()).split(/\\s+/).filter(Boolean);\n  const out = [];\n  words.forEach((w, i) => { out.push(...viterbi(T, (i > 0 || T.prefix ? T.rep : '') + w)); });\n  return [...T.sp.pre, ...out.slice(0, max - T.sp.pre.length - T.sp.post.length), ...T.sp.post];\n}\n\n/* ---- Sentence vectors ---- */\nasync function embed(text) {\n  const ids = encode(tok, (cfg.prefix || '') + text), n = ids.length;\n  const big = a => new ort.Tensor('int64', BigInt64Array.from(a, BigInt), [1, n]);\n  const feeds = {};\n  for (const name of session.inputNames) {\n    if (name === 'input_ids') feeds[name] = big(ids);\n    else if (name === 'attention_mask') feeds[name] = big(ids.map(() => 1));\n    else if (name === 'token_type_ids') feeds[name] = big(ids.map(() => 0));\n  }\n  const out = await session.run(feeds);\n  const name = session.outputNames.includes('sentence_embedding') ? 'sentence_embedding' : session.outputNames.includes('last_hidden_state') ? 'last_hidden_state' : session.outputNames[0];\n  const t = out[name], d = t.dims[t.dims.length - 1], v = new Float32Array(d);\n  if (t.dims.length === 2) v.set(t.data.subarray(0, d));\n  else { const rows = t.dims[1]; for (let r = 0; r < rows; r++) for (let k = 0; k < d; k++) v[k] += t.data[r * d + k]; }   // mean of all tokens\n  let s = 0; for (let k = 0; k < d; k++) s += v[k] * v[k]; s = Math.sqrt(s) || 1;\n  for (let k = 0; k < d; k++) v[k] /= s;\n  return v;\n}\n\n/* ---- Understanding: nearest examples (kNN), with thresholds measured on the examples themselves ---- */\nfunction scoreAll(q, skip) {\n  const { vecs, labels, nI, d } = bank, best = new Float32Array(nI).fill(-1), sec = new Float32Array(nI).fill(-1);\n  for (let i = 0; i < labels.length; i++) {\n    if (i === skip) continue;\n    let s = 0; const o = i * d; for (let k = 0; k < d; k++) s += q[k] * vecs[o + k];\n    const l = labels[i]; if (s > best[l]) { sec[l] = best[l]; best[l] = s; } else if (s > sec[l]) sec[l] = s;\n  }\n  const sc = new Float32Array(nI); for (let l = 0; l < nI; l++) sc[l] = best[l] < -0.5 ? -1 : 0.75 * best[l] + 0.25 * (sec[l] > -0.5 ? (best[l] + sec[l]) / 2 : best[l]);\n  let b = 0, c = -1; for (let l = 1; l < nI; l++) if (sc[l] > sc[b]) b = l;\n  for (let l = 0; l < nI; l++) if (l !== b && (c < 0 || sc[l] > sc[c])) c = l;\n  return { b, s1: sc[b], c, s2: c >= 0 ? sc[c] : -1, raw: best[b] };\n}\nfunction calibrate() {\n  const { labels, vecs, d } = bank, rows = [];\n  for (let i = 0; i < labels.length; i++) { const r = scoreAll(vecs.subarray(i * d, i * d + d), i); rows.push({ s1: r.s1, s2: r.s2, ok: r.b === labels[i] }); }\n  const med = a => { const s = [...a].sort((x, y) => x - y); return s.length ? s[s.length >> 1] : 0; };\n  const good = rows.filter(r => r.ok), pos = med(good.map(r => r.s1)), neg = med(rows.map(r => r.s2)), spread = Math.max(0.02, pos - neg);\n  /* lowest score at which at least 90 % of the examples are understood right */\n  const sorted = [...rows].sort((a, b) => b.s1 - a.s1); let ok = 0, tau = sorted.length ? sorted[sorted.length - 1].s1 : 0;\n  sorted.forEach((r, i) => { ok += r.ok; if (ok / (i + 1) >= 0.9) tau = r.s1; });\n  const loo = good.length / Math.max(1, rows.length);\n  return { tau: Math.max(tau, neg + 0.15 * spread), margin: 0.08 * spread, spread, pos, neg, loo };\n}\n\nonmessage = async e => {\n  const m = e.data;\n  try {\n    if (m.type === 'init') {\n      importScripts(m.files.ortJs);\n      ort.env.wasm.numThreads = 1;\n      ort.env.wasm.wasmPaths = { 'ort-wasm-simd.wasm': m.files.ortWasm, 'ort-wasm.wasm': m.files.ortWasm };\n      cfg = m.cfg || {};\n      tok = buildTok(JSON.parse(m.tok));\n      session = await ort.InferenceSession.create(m.model, { executionProviders: ['wasm'], graphOptimizationLevel: 'all', enableCpuMemArena: false, enableMemPattern: false });\n      m.model = null;\n      const t0 = performance.now(); const v = await embed('hola, ¿qué tal?');\n      postMessage({ id: m.id, ok: true, dim: v.length, ms: Math.round(performance.now() - t0), vocab: tok.scores.length });\n    } else if (m.type === 'embed') {\n      const vs = []; const t0 = performance.now();\n      for (const t of m.texts) vs.push(await embed(t));\n      const d = vs.length ? vs[0].length : 0, out = new Float32Array(vs.length * d);\n      vs.forEach((v, i) => out.set(v, i * d));\n      postMessage({ id: m.id, ok: true, vecs: out, dim: d, ms: Math.round(performance.now() - t0) }, [out.buffer]);\n    } else if (m.type === 'bank') {\n      bank = { vecs: m.vecs, labels: m.labels, nI: m.nI, d: m.dim };\n      bank.cal = calibrate();\n      postMessage({ id: m.id, ok: true, cal: bank.cal });\n    } else if (m.type === 'classify') {\n      const t0 = performance.now(), q = await embed(m.text), r = scoreAll(q, -1);\n      postMessage({ id: m.id, ok: true, intent: r.b, s1: r.s1, second: r.c, s2: r.s2, raw: r.raw, cal: bank.cal, ms: Math.round(performance.now() - t0) });\n    } else if (m.type === 'tokenize') {\n      postMessage({ id: m.id, ok: true, ids: m.texts.map(t => encode(tok, t)) });\n    }\n  } catch (err) { postMessage({ id: m.id, ok: false, error: String(err && err.message || err) }); }\n};\n";
const NB_BANK = {"pausa": ["para la música", "pausa un momento", "espera un segundo", "para que me llaman por teléfono", "silencio un momento", "deja de sonar", "corta la música", "para un ratito", "me están hablando, para", "quita el sonido un momento", "detén la canción", "frena la música", "ahora no, para", "para un momento que me llaman", "páralo", "calla un segundo", "detén la música", "para que tengo que hablar", "espérate un momento", "ponla en pausa"], "seguir": ["vuelve a ponerla", "ya estoy, dale", "quita la pausa", "ya puedes seguir", "continúa con la música", "sigue sonando", "pon otra vez la música", "ya he vuelto", "dale al play", "arranca de nuevo", "que siga la música", "venga, sigue", "sigue con la música", "ya está, continúa", "ponla otra vez que ya he vuelto", "reanuda la canción", "vuelve a sonar"], "saltar": ["esta no me convence", "pasa de esta", "cambia, que esta ya la he oído mil veces", "quita esta que no la aguanto", "otra canción por favor", "esta no", "siguiente tema", "no me apetece esta", "pon otra", "cámbiala", "esta canción me aburre", "salta a la próxima", "venga va, otra", "next", "esta no me pega ahora", "quita esta", "siguiente", "esta me raya", "esta no me gusta nada", "qué rollo de canción", "cambia de tema", "pasa a otra", "no me mola esta", "esta ya me cansa", "skip", "salta esta", "próxima"], "anterior": ["pon la de antes", "vuelve a la anterior", "la canción anterior", "atrás una canción", "la que sonaba antes", "quiero oír otra vez la anterior", "retrocede una", "la de antes me gustaba más", "ponme la que sonaba antes", "atrás", "retrocede una canción", "vuelve a la de antes"], "repetir": ["pon esta otra vez desde el principio", "repite esta canción", "empieza de nuevo esta", "vuelve al principio de la canción", "otra vez esta", "repítela", "desde el principio", "vuelve a empezarla", "ponla otra vez"], "mas_ritmo": ["esto está muy flojo, dale caña", "más marcha que me duermo", "venga, algo con más vidilla", "esto es un muermo, anima esto", "súbele la energía", "más ritmo", "algo más cañero", "que se mueva esto", "pon algo con más fuerza", "esto está muy parado", "dale más alegría a esto", "necesito más energía", "más rápido el ritmo", "algo con más energía", "ponle más caña", "esto necesita más ritmo", "algo más animado", "sube el ritmo", "más rápido", "venga, a tope"], "mas_calma": ["relájate un poco que es tarde", "demasiado ruido, algo más suave", "tranqui, baja el ritmo", "baja un poco las revoluciones", "esto es demasiado fuerte", "algo más tranquilito", "más calmado por favor", "me estás estresando con tanto ruido", "algo más lento", "baja la intensidad", "menos caña", "más suave", "algo más suave", "baja las revoluciones", "más tranquilo por favor", "esto es demasiado movido", "relaja el ambiente", "menos ruido"], "parecidas": ["más cosas de este estilo", "sigue por esta línea", "pon otras que se parezcan", "dame más rollo de este", "más canciones como esta", "algo parecido a lo que suena", "de este palo, más", "sigue en esta onda", "más de lo mismo", "otras en esta vibra", "más de este rollo", "algo en esta línea", "sigue con este estilo", "más así", "pon cosas parecidas", "me gusta este rollo, más"], "me_gusta": ["qué temazo", "esta canción me encanta", "guárdame esta", "esta me flipa", "esta canción es una pasada", "me mola mucho esta", "qué buena es esta", "esta es la mejor", "guárdala en favoritas", "me chifla esta canción", "esta está brutal", "qué canción más bonita", "esta me gusta un montón", "temón", "esta es una pasada", "me flipa esta", "temazo", "qué canción más buena", "me mola mucho", "esta me encanta, guárdala", "qué bonita", "vaya joya"], "no_mas": ["no quiero volver a oír esta canción", "no me la pongas nunca más", "odio esta canción", "bórrame esta de la lista para siempre", "esta canción no la soporto, no la repitas", "que no vuelva a sonar esta", "esta jamás otra vez", "no la soporto", "bórrala de mi vida", "esta nunca más", "no me vuelvas a poner esta", "quita esta para siempre"], "subir_vol": ["súbele", "no se oye nada", "más alto por favor", "pon más volumen", "no oigo la música", "súbele el volumen que no se escucha", "a todo volumen", "un poco más fuerte el volumen", "no oigo nada", "súbelo", "ponlo más fuerte", "más fuerte", "sube un poco", "está muy bajito"], "bajar_vol": ["bájale", "está muy alto", "baja el volumen", "me revientas los oídos", "más bajito", "menos volumen por favor", "se oye demasiado fuerte", "un poco más bajo", "está muy fuerte", "bájalo un poco", "me estás dejando sordo", "baja un poco", "qué alto está"], "que_suena": ["¿qué canción es esta?", "¿quién canta esto?", "¿cómo se llama esta?", "¿qué está sonando?", "dime el nombre de la canción", "¿de quién es esta?", "¿qué es esto que suena?", "¿esta canción cómo se llamaba?", "¿esto qué es?", "¿qué estoy escuchando?", "¿cómo se llama esto?", "¿qué tema es este?"], "por_que": ["¿por qué me pones esto?", "¿por qué has elegido esta?", "¿a qué viene esta canción?", "explícame por qué suena esta", "¿por qué esta canción ahora?", "¿qué criterio has seguido?", "¿y esta por qué?", "¿por qué esta canción?", "explícame esta elección"], "artista": ["háblame de este artista", "¿qué sabes de este grupo?", "¿quién es este cantante?", "cuéntame algo de quien canta", "¿de dónde es este grupo?", "háblame de Shakira", "¿sabes algo de Rosalía?", "dime algo sobre la banda", "¿quiénes son estos?", "cuéntame la historia de este artista"], "tiempo": ["¿hace frío fuera?", "¿va a llover?", "¿qué temperatura hace?", "¿llevo paraguas hoy?", "¿hace sol?", "¿qué tiempo hace hoy?", "¿cómo está el día?", "¿hace calor en la calle?", "¿necesito chaqueta?", "¿está nublado?"], "hora": ["¿qué hora es?", "dime la hora", "¿qué hora tenemos?", "¿es muy tarde?", "¿cuánto falta para las doce?"], "letra": ["pon la letra", "quiero cantar esta", "enséñame la letra de la canción", "¿qué dice la canción?", "modo karaoke", "¿cómo sigue la letra?", "quiero ver la letra", "pon la letra que quiero cantar"], "radio": ["pon la radio", "modo radio", "ponte en plan radio y no pares", "música sin parar, tú eliges", "haz de radio", "no pares de poner música"], "resumen": ["¿qué he escuchado esta semana?", "resumen de mi semana", "¿cuánto he escuchado últimamente?", "dame mis estadísticas", "¿qué es lo que más escucho?", "¿cuál es mi artista más escuchado?"], "recuerdos": ["¿qué sabes de mí?", "¿qué recuerdas de mí?", "¿qué te he pedido antes?", "¿te acuerdas de mis gustos?", "¿qué has aprendido de mí?", "¿qué tienes apuntado sobre mí?"], "ayuda": ["¿qué puedes hacer?", "¿cómo funcionas?", "ayúdame", "¿qué te puedo pedir?", "no sé qué decirte", "¿qué sabes hacer?", "instrucciones", "¿para qué sirves?"], "sorpresa": ["sorpréndeme", "pon lo que quieras", "elige tú", "me da igual, pon algo", "lo que tú veas", "algo al azar", "sorpresa", "confío en ti, elige"], "favoritas": ["pon mis favoritas", "las que más me gustan", "mis canciones preferidas", "pon lo que más escucho", "mis temas de siempre", "las de siempre", "ponme lo que más me gusta", "mis temazos", "mis favoritas de siempre"], "descubrir": ["quiero descubrir algo nuevo", "pon canciones que no haya escuchado", "algo diferente a lo de siempre", "sorpréndeme con cosas nuevas", "estoy cansado de lo mismo, algo nuevo", "canciones que aún no conozca", "algo que no conozca", "sorpréndeme con algo que no haya oído", "quiero cosas nuevas"], "m_relax": ["pon algo para dormir", "música para relajarme", "algo para desconectar", "quiero meditar", "algo tranquilo para leer", "música para una tarde de lluvia", "estoy agobiado con el trabajo, algo que me relaje", "algo suave para el sofá", "para darme un baño relajante", "música chill", "algo para relajarme después del trabajo", "algo tranquilito", "para dormirme"], "m_focus": ["pon algo para estudiar", "música para concentrarme", "voy a trabajar, algo que no distraiga", "algo para programar", "tengo que hacer los deberes", "música de fondo para la oficina", "algo para estudiar sin distracciones", "necesito centrarme"], "m_triste": ["estoy de bajón, ponme algo", "música triste", "canciones para llorar", "algo melancólico", "ponme canciones de desamor", "hoy tengo el día gris, algo que me acompañe", "música para un día triste", "necesito llorar", "algo para el desamor", "música para un día malo"], "m_animar": ["quiero algo que me levante el ánimo", "algo alegre", "uf qué pereza de lunes, anímame", "necesito buen rollo", "música feliz", "algo para sonreír", "pon algo positivo", "algo que me ponga de buen humor", "algo que me alegre el día"], "m_fiesta": ["quiero bailar", "música de fiesta", "vamos a darlo todo esta noche", "pon algo para la fiesta de hoy", "algo para salir de marcha", "que empiece la fiesta", "quiero bailar como si no hubiera mañana", "ponme perreo", "música de discoteca", "tenemos gente en casa, algo para animar la fiesta", "música para una fiesta en casa", "perreo", "algo para bailar"], "m_deporte": ["algo para darlo todo en el gimnasio", "algo para correr por la mañana", "música para entrenar", "voy a salir a correr", "algo motivador para hacer deporte", "pon música para hacer pesas", "música para la bici", "voy a correr", "al gimnasio", "motivación para entrenar", "música para hacer pesas"], "m_coche": ["canciones para el coche", "música para conducir", "nos vamos de viaje, pon algo", "algo para la carretera", "música para un viaje largo"], "m_casa": ["pon algo para limpiar la casa", "música para cocinar", "algo para la ducha", "estoy haciendo la comida, pon algo", "música para ordenar mi cuarto"], "m_romance": ["algo romántico", "me apetece algo romántico para cenar con mi pareja", "música para una cena con velas", "canciones de amor", "estoy enamorado, pon algo bonito", "algo para escuchar con mi novia"], "m_generic": ["me apetece escuchar salsa", "ponme rock de los 80", "pon música de Kora", "quiero escuchar algo de pop", "ponme algo de reguetón", "pon música", "quiero escuchar música", "pon canciones de Los Rayos", "algo de jazz", "ponme a Banda Sur", "pon unas canciones", "quiero oír rap"], "c_saludo": ["hola", "buenas", "hola nova", "¿qué pasa?", "buenos días", "buenas tardes", "ey, ¿qué tal?", "holi", "qué hay"], "c_como_estas": ["¿qué tal estás?", "¿cómo estás?", "¿cómo te va?", "¿qué tal el día?", "¿todo bien?", "¿cómo andas?"], "c_gracias": ["gracias", "muchas gracias", "gracias guapa", "te lo agradezco", "muy bien, gracias", "genial, gracias", "perfecto, gracias", "gracias nova", "muy amable"], "c_despedida": ["adiós", "hasta luego", "me voy", "nos vemos", "chao", "hasta mañana", "me tengo que ir"], "c_buenas_noches": ["buenas noches", "me voy a dormir", "me voy a la cama", "a dormir", "hasta mañana, me acuesto"], "c_quien_eres": ["¿quién eres?", "¿cómo te llamas?", "¿qué eres?", "preséntate", "¿tú quién eres?", "dime tu nombre"], "c_que_haces": ["¿qué haces?", "¿qué estás haciendo?", "¿en qué andas?", "¿estás ocupada?"], "c_chiste": ["cuéntame un chiste", "dime algo gracioso", "hazme reír", "¿sabes algún chiste?", "otro chiste"], "c_piropo": ["eres la mejor", "eres muy maja", "te quiero", "qué lista eres", "me encantas", "eres genial", "buen trabajo", "lo haces muy bien"], "c_enfado": ["eres tonta", "no me entiendes", "no sirves para nada", "qué mal lo haces", "eres un desastre", "no es eso lo que te he pedido", "me tienes harto", "vaya DJ más mala"], "c_aburrido": ["me aburro", "qué aburrimiento", "no sé qué hacer", "estoy aburrido", "qué tarde más aburrida"], "c_triste": ["estoy triste", "estoy triste porque me ha dejado mi novia", "he tenido un mal día", "me siento solo", "estoy fatal", "hoy no es mi día", "me han dado una mala noticia", "no estoy bien"], "c_cansado": ["estoy reventado", "qué cansancio", "estoy agotada", "no puedo más, estoy muerto", "estoy muy cansado", "estoy muerto", "qué día más largo", "estoy hecho polvo de trabajar"], "c_feliz": ["estoy muy contento", "hoy es un gran día", "me han dado una buena noticia", "estoy feliz", "he aprobado el examen", "qué buen día hace hoy"], "c_gustos": ["¿qué música te gusta?", "¿cuál es tu canción favorita?", "¿qué grupo te gusta más?", "¿qué escuchas tú?", "¿tienes un estilo favorito?"], "c_ia": ["¿eres una persona?", "¿eres real?", "¿eres un robot?", "¿eres una inteligencia artificial?", "¿hay alguien ahí?", "¿me escuchas de verdad?"], "c_si": ["sí", "vale", "venga", "dale", "claro", "sí, por favor", "me parece bien", "perfecto", "de acuerdo", "por qué no", "va"], "c_no": ["no", "no gracias", "mejor no", "déjalo", "ahora no", "paso", "no hace falta", "nada, olvídalo"]};
/* ---------- 3.0 beta 3 · Nova understands ----------
   The «brain»: a multilingual sentence model (about 120 MB, downloaded once) that runs on the phone, without internet.
   It doesn't write text and can't invent anything: it measures what a phrase MEANS and compares it with the examples
   Nova knows (and the ones you teach her), so «esta es una pasada», «qué temazo» and «me flipa» all mean «me gusta».
   With a Claude key, conversation that needs real words goes to Claude. */
const NB = { st: LS.get('nb', null), on: LS.get('nbOn', true), worker: null, ready: null, n: 0, wait: new Map(), dl: null, fails: 0, intents: [], cal: null, ms: null, dim: 0, base: null, offer: null, last: null, gen: 0, prep: null };
const NB_CACHE = 'orbita-cerebro-1', NB_KEY = n => 'https://orbita.cerebro/' + n;
const NB_HF = 'https://huggingface.co/';
const NB_MODELS = [
  { repo: 'Xenova/multilingual-e5-small', label: 'multilingual-e5-small', by: 'Microsoft (intfloat)', lic: 'MIT', prefix: 'query: ' },
  { repo: 'Xenova/paraphrase-multilingual-MiniLM-L12-v2', label: 'paraphrase-multilingual-MiniLM-L12-v2', by: 'sentence-transformers', lic: 'Apache-2.0', prefix: '' }
];
const NB_FILES = ['onnx/model_quantized.onnx', 'onnx/model_int8.onnx', 'onnx/model_uint8.onnx'];
/* What each intention means: [name in Spanish, what to do (a phrase Nova already understands, or the style of a mix), risky] */
const NB_INTENTS = {
  pausa: ['Pausar', 'pausa', 1], seguir: ['Seguir sonando', 'sigue'], saltar: ['Pasar a otra', 'siguiente', 1], anterior: ['Volver a la anterior', 'anterior', 1],
  repetir: ['Repetir esta', null, 1], mas_ritmo: ['Más movida', 'mas movida'], mas_calma: ['Más tranquila', 'mas tranquila'], parecidas: ['Más como esta', 'mas como esta'],
  me_gusta: ['Me gusta esta', 'me gusta'], no_mas: ['No ponerla nunca más', 'no me la pongas mas', 1], subir_vol: ['Subir el volumen', 'sube el volumen', 1], bajar_vol: ['Bajar el volumen', 'baja el volumen', 1],
  que_suena: ['¿Qué suena?', 'que suena'], por_que: ['¿Por qué esta?', 'por que'], artista: ['Hablar del artista', null], tiempo: ['El tiempo', 'que tiempo hace'], hora: ['La hora', 'que hora es'],
  letra: ['La letra', 'letra'], radio: ['Modo radio', 'modo radio'], resumen: ['Resumen de la semana', 'resumen de mi semana'], recuerdos: ['Lo que sabe de ti', null], ayuda: ['Ayuda', 'ayuda'],
  sorpresa: ['Mezcla sorpresa', null], favoritas: ['Tus favoritas', 'mis favoritas'], descubrir: ['Algo nuevo', 'nuevas sin escuchar'],
  m_relax: ['Música tranquila', 'tranquila'], m_focus: ['Para concentrarse', 'concentrarme'], m_triste: ['Música triste', 'triste'], m_animar: ['Música alegre', 'alegre'], m_fiesta: ['Para la fiesta', 'fiesta'],
  m_deporte: ['Para entrenar', 'entrenar'], m_coche: ['Para el coche', 'coche'], m_casa: ['Para casa', 'casa'], m_romance: ['Romántica', 'romantica'], m_generic: ['Una mezcla', ''],
  c_saludo: ['Un saludo'], c_como_estas: ['¿Qué tal estás?'], c_gracias: ['Gracias'], c_despedida: ['Una despedida'], c_buenas_noches: ['Buenas noches'], c_quien_eres: ['¿Quién eres?'],
  c_que_haces: ['¿Qué haces?'], c_chiste: ['Un chiste'], c_piropo: ['Un piropo'], c_enfado: ['Una queja'], c_aburrido: ['Me aburro'], c_triste: ['Estoy triste'], c_cansado: ['Estoy cansado'],
  c_feliz: ['Estoy contento'], c_gustos: ['Tus gustos'], c_ia: ['¿Eres real?'], c_si: ['Sí'], c_no: ['No']
};
/* Conversation that sounds better with real words: with a Claude key, these go to Claude */
const NB_FREE = new Set(['c_como_estas', 'c_que_haces', 'c_chiste', 'c_triste', 'c_feliz', 'c_cansado', 'c_aburrido', 'c_gustos', 'c_enfado', 'c_ia', 'c_quien_eres']);
const NB_OFFER_REQ = { m_relax: 'algo tranquilo', m_triste: 'algo que me acompañe', m_animar: 'algo alegre para animarme', m_fiesta: 'música de fiesta' };
const nbUsable = () => !!(NB.st && NB.on && NB.fails < 3 && window.Worker && window.caches);
const nbTaught = () => LS.get('nbTeach', []);
function nbHash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }
function nbCall(msg, tr) { return new Promise(res => { const id = ++NB.n; NB.wait.set(id, res); NB.worker.postMessage({ ...msg, id }, tr || []); }); }
function nbReset() { if (NB.worker) { try { NB.worker.terminate(); } catch {} } NB.worker = null; NB.ready = null; NB.base = null; NB.cal = null; for (const r of NB.wait.values()) r({ ok: false, error: 'reset' }); NB.wait.clear(); }
const nbWithin = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('tiempo')), ms))]);

/* ---- Where the files live ----
   In the Android app (3.2+), in the app's own folder: Android never deletes it to make room, unlike the browser's
   storage, where the brain could disappear on its own. Elsewhere (and in older builds), in the browser's storage. */
const NB_REQ = ['ort.wasm.min.js', 'ort-wasm-simd.wasm', 'tokenizer.json', 'modelo.onnx'];
let nbNatP = null;
function nbNative() {
  if (!NV_NATIVE) return Promise.resolve(false);
  if (!nbNatP) nbNatP = NV_NATIVE.status().then(s => !!(s && s.dirs), () => false);
  return nbNatP;
}
async function nbPutNative(name, data, onBytes) {
  const blob = data instanceof Blob ? data : new Blob([data]);
  if (!blob.size) throw new Error('vacío ' + name);
  let r = null;
  for (let o = 0, first = true; o < blob.size; o += NV_CHUNK, first = false) {
    const end = Math.min(blob.size, o + NV_CHUNK);
    r = await NV_NATIVE.writeChunk({ dir: 'cerebro', name, data: await nvB64(blob.slice(o, end)), append: !first, done: end >= blob.size });
    if (onBytes) onBytes(end - o);
  }
  if (!r || r.size !== blob.size) throw new Error('guardado incompleto ' + name);
}
async function nbGetNative(name) {
  const i = await NV_NATIVE.fileInfo({ dir: 'cerebro', name });
  if (!i || !i.exists || !i.size) return null;
  const r = await fetch(window.Capacitor.convertFileSrc(i.path));
  return r.ok ? r : null;
}
/* A brain installed in the browser's storage: moved once to the app's folder, without downloading it again */
async function nbMigrate() {
  if (!NB.st || NB.st.where === 'nativo' || NB.mig || !(await nbNative())) return false;
  NB.mig = true;
  try {
    const cache = await caches.open(NB_CACHE), blobs = [];
    for (const n of NB_REQ) { const r = await cache.match(NB_KEY(n)); if (!r) return false; blobs.push([n, await r.blob()]); }
    for (const [n, b] of blobs) await nbPutNative(n, b);
    NB.st = { ...NB.st, where: 'nativo' }; LS.set('nb', NB.st);
    for (const n of NB_REQ) { try { await cache.delete(NB_KEY(n)); } catch {} }
    return true;
  } catch { return false; } finally { NB.mig = false; }
}

/* ---- Download (once) ---- */
async function nbFind() {
  const out = [];
  for (const m of NB_MODELS) {
    try {
      const r = await fetch(`${NB_HF}api/models/${m.repo}/tree/main/onnx`); if (!r.ok) throw 0;
      const list = await r.json(), have = new Map((Array.isArray(list) ? list : []).map(f => [f.path, (f.lfs && f.lfs.size) || f.size || 0]));
      const file = NB_FILES.find(f => have.has(f)); if (file) out.push({ ...m, file, mb: Math.max(1, Math.round(have.get(file) / 1048576)) });
    } catch {}
  }
  /* the catalogue can't be read: try the usual files directly */
  return out.length ? out : NB_MODELS.map(m => ({ ...m, file: NB_FILES[0], mb: 118 }));
}
async function nbInstall() {
  if (NB.dl) return;
  if (!ONLINE && !STANDALONE) { toast('El cerebro se instala en la app de Android, con internet'); return; }
  const ctl = new AbortController(), cache = await caches.open(NB_CACHE);
  NB.dl = { done: 0, total: 0, ctl, label: 'Buscando el modelo…' }; renderNB();
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); } catch {}
  let t = 0;
  const tick = n => { NB.dl.done += n; if (Date.now() - t > 250) { t = Date.now(); renderNBProgress(); } };
  try {
    /* the engine is the same one the voice uses: copied from it if it's already on the phone */
    const engine = [];
    let voice = null; try { if (await caches.has(NV_CACHE)) voice = await caches.open(NV_CACHE); } catch {}
    for (const [, name, bases, type, mb] of NV_ENGINE.filter(e => e[0] === 'ortJs' || e[0] === 'ortWasm')) {
      const have = voice && await voice.match(NV_KEY(name));
      engine.push({ name, type, mb: have ? 0 : mb, have, urls: bases.map(b => b + name) });
    }
    const cands = await nbFind(), engMb = engine.reduce((a, j) => a + j.mb, 0);
    NB.dl.total = (engMb + 17 + cands[0].mb) * 1048576; renderNB();
    /* the engine once (each body can only be read once), then the first model that downloads well */
    const engFiles = [];
    for (const j of engine) engFiles.push([j, j.have ? await j.have.arrayBuffer() : await nvFetch(j.urls, tick, ctl.signal)]);
    const base = NB.dl.done;
    let got = null;
    for (const c of cands) {
      NB.dl.label = `Descargando ${c.label}`;
      NB.dl.total = (engMb + 17 + c.mb) * 1048576; NB.dl.done = base; renderNB();
      try {
        const tokBuf = await nvFetch([`${NB_HF}${c.repo}/resolve/main/tokenizer.json`], tick, ctl.signal);
        const tj = JSON.parse(new TextDecoder().decode(tokBuf));
        if (!tj.model || tj.model.type !== 'Unigram') throw new Error('tokenizer');
        const model = await nvFetch([`${NB_HF}${c.repo}/resolve/main/${c.file}`], tick, ctl.signal);
        if (model.byteLength < 1e5) throw new Error('modelo');
        got = { c, files: [...engFiles, [{ name: 'tokenizer.json', type: 'application/json' }, tokBuf], [{ name: 'modelo.onnx', type: 'application/octet-stream' }, model]] }; break;
      } catch (e) { if (ctl.signal.aborted) throw e; }
    }
    if (!got) throw new Error('descarga');
    const native = await nbNative();
    if (native) {
      NB.dl.label = 'Guardando el cerebro en el móvil'; NB.dl.done = 0; NB.dl.total = got.files.reduce((a, [, b]) => a + b.byteLength, 0); renderNB();
      try { await NV_NATIVE.removeFiles({ dir: 'cerebro' }); } catch {}
      for (const [j, buf] of got.files) await nbPutNative(j.name, buf, tick);
    } else for (const [j, buf] of got.files) await cache.put(NB_KEY(j.name), new Response(buf, { headers: { 'Content-Type': j.type } }));
    const c = got.c;
    NB.st = { repo: c.repo, file: c.file, label: c.label, by: c.by, lic: c.lic, prefix: c.prefix, mb: Math.round(c.mb + 17 + 10.7), ts: Date.now(), where: native ? 'nativo' : 'web' };
    LS.set('nb', NB.st); NB.on = true; LS.set('nbOn', true); NB.fails = 0;
    nbReset(); NB.dl = null; renderNB();
    await nbInit();
    renderNB(); toast('Cerebro instalado'); haptic(12);
    novaSay(`Ya tengo cerebro nuevo. Ahora entiendo mejor lo que me dices: háblame con tus palabras.`);
  } catch (e) {
    const aborted = ctl.signal.aborted; NB.dl = null; renderNB();
    if (!aborted) toast(NB.st ? 'El cerebro se ha descargado pero no arranca en este móvil.' : 'No he podido descargar el cerebro. Comprueba tu conexión y vuelve a intentarlo.', 4200);
  }
}
async function nbRemove() {
  try { await caches.delete(NB_CACHE); } catch {}
  if (await nbNative()) { try { await NV_NATIVE.removeFiles({ dir: 'cerebro' }); } catch {} }
  nbReset(); NB.st = null; LS.set('nb', null); NB.offer = null; renderNB(); toast('Cerebro borrado: Nova vuelve a entender solo las frases de siempre');
}

/* ---- Start (in the background, a few seconds after opening) ---- */
function nbInit() {
  if (NB.ready) return NB.ready;
  NB.ready = (async () => {
    const cache = await caches.open(NB_CACHE), native = NB.st.where === 'nativo';
    const get = async n => { const r = native ? await nbGetNative(n) : await cache.match(NB_KEY(n)); if (!r) throw new Error('falta ' + n); return r; };
    const typed = async (n, type) => URL.createObjectURL(new Blob([await (await get(n)).arrayBuffer()], { type }));
    const files = { ortJs: await typed('ort.wasm.min.js', 'text/javascript'), ortWasm: await typed('ort-wasm-simd.wasm', 'application/wasm') };
    const tok = await (await get('tokenizer.json')).text(), model = await (await get('modelo.onnx')).arrayBuffer();
    NB.worker = new Worker(URL.createObjectURL(new Blob([NB_WORKER_SRC], { type: 'text/javascript' })));
    NB.worker.onmessage = e => { const r = NB.wait.get(e.data.id); if (r) { NB.wait.delete(e.data.id); r(e.data); } };
    NB.worker.onerror = () => { NB.fails++; };
    const r = await nbCall({ type: 'init', files, tok, model, cfg: { prefix: NB.st.prefix || '' } }, [model]);
    if (!r.ok) throw new Error(r.error);
    NB.ms = r.ms; NB.dim = r.dim;
    await nbLoadBank(cache);
    renderNB();
    if (!native) setTimeout(() => nbMigrate().then(ok => { if (ok) renderNB(); }), 30000);
    return true;
  })();
  NB.ready.catch(e => {
    NB.fails++; const missing = /^falta/.test(e && e.message);
    nbReset(); if (missing) { NB.st = null; LS.set('nb', null); }
    renderNB();
  });
  return NB.ready;
}
/* Nova's examples, turned into vectors once (kept on the phone) */
async function nbLoadBank(cache) {
  const phrases = [], labels = [];
  NB.intents = Object.keys(NB_BANK).filter(k => NB_INTENTS[k]);
  NB.intents.forEach((k, i) => NB_BANK[k].forEach(p => { phrases.push(p); labels.push(i); }));
  const key = 'banco-' + nbHash(NB.st.repo + NB.st.file + JSON.stringify(NB_BANK)) + '.bin';
  let vecs = null;
  const hit = await cache.match(NB_KEY(key));
  if (hit) { const v = new Float32Array(await hit.arrayBuffer()); if (v.length === phrases.length * NB.dim) vecs = v; }
  if (!vecs) {
    vecs = new Float32Array(phrases.length * NB.dim); NB.prep = 0; renderNB();
    for (let i = 0; i < phrases.length; i += 24) {
      const r = await nbCall({ type: 'embed', texts: phrases.slice(i, i + 24) }); if (!r.ok) throw new Error(r.error);
      vecs.set(r.vecs, i * NB.dim); NB.prep = Math.min(1, (i + 24) / phrases.length); renderNBPrep();
    }
    NB.prep = null;
    try {
      for (const req of await cache.keys()) if (/\/banco-/.test(req.url)) await cache.delete(req);
      await cache.put(NB_KEY(key), new Response(vecs.buffer.slice(0), { headers: { 'Content-Type': 'application/octet-stream' } }));
    } catch {}
  }
  NB.base = { vecs, labels };
  await nbPushBank();
}
async function nbPushBank() {
  const taught = nbTaught().filter(x => NB.intents.includes(x.i)), d = NB.dim, n0 = NB.base.labels.length;
  let tv = new Float32Array(0);
  if (taught.length) { const r = await nbCall({ type: 'embed', texts: taught.map(x => x.t) }); if (r.ok) tv = r.vecs; }
  const all = new Float32Array((n0 + taught.length) * d); all.set(NB.base.vecs); all.set(tv.subarray(0, taught.length * d), n0 * d);
  const labels = Int32Array.from([...NB.base.labels, ...taught.map(x => NB.intents.indexOf(x.i))]);
  const r = await nbCall({ type: 'bank', vecs: all, labels, nI: NB.intents.length, dim: d }, [all.buffer]);
  if (!r.ok) throw new Error(r.error);
  NB.cal = r.cal;
}

/* ---- Understanding one phrase ---- */
function nbTaughtExact(text) { const n = norm(text); const f = n && nbTaught().find(x => norm(x.t) === n); return f && NB_INTENTS[f.i] ? f.i : null; }
async function nbClassify(text) {
  if (window.ORBITA_DEBUG && window.__nbFake) { const f = window.__nbFake(text); if (f) return f; }
  await nbWithin(nbInit(), 6000);
  const ex = nbTaughtExact(text); if (ex) return { ok: true, intent: ex, conf: 1, taught: true };
  const r = await nbCall({ type: 'classify', text: String(text).trim().slice(0, 300) });
  if (!r.ok) throw new Error(r.error);
  NB.ms = r.ms;
  const intent = NB.intents[r.intent], cal = r.cal, risky = !!(NB_INTENTS[intent] || [])[2];
  const need = cal.tau + (risky ? 0.12 * cal.spread : 0), margin = cal.margin * (risky ? 2 : 1);
  const conf = Math.max(0, Math.min(1, (r.s1 - cal.neg) / ((cal.pos - cal.neg) || 1)));
  return { ok: r.s1 >= need && r.s1 - r.s2 >= margin, intent, second: NB.intents[r.second], s1: r.s1, s2: r.s2, conf, ms: r.ms };
}

/* ---- From understanding to doing ---- */
const NB_MUSIC_RE = /(^| )(pon|ponme|ponnos|pones|poner|pondrias|pongas|quiero (oir|escuchar)|escuchar|musica|cancion|canciones|temas|playlist|algo (de|para|con|que)|mezcla)( |$)/;
function nbLibArtist(q) {
  const qq = ' ' + q + ' ';
  for (const a of new Set(poolTracks({ free: true }).map(t => t.artist))) { const k = artistKey(a); if (k && k.length >= 3 && qq.includes(' ' + k + ' ')) return a; }
  return null;
}
function nbArtistIn(raw, q) {
  const lib = nbLibArtist(q); if (lib) return lib;
  /* «háblame de La Oreja de Van Gogh», «¿qué sabes de la historia de Queen?»: the first «de …» that looks like a name */
  const s = String(raw).replace(/[?!.¿¡]+\s*$/, '').trim();
  const first = s.match(/(?:^|\s)(?:de|del|sobre)\s+([^?¿!¡.,;:]{2,40})$/i), last = s.match(/.*\s(?:de|del|sobre)\s+([^?¿!¡.,;:]{2,40})$/i);
  for (const m of [first, last]) {
    if (!m) continue;
    const name = m[1].trim(), core = (' ' + norm(name) + ' ').replace(/^ (el|la|los|las) /, ' ');
    if (core.trim().length < 2 || / (este|esta|ese|esa|esto|eso|ellos|ellas|el|ella|la|lo|quien|que|donde|como|cuando|es|son|era|canta|cantan|grupo|banda|artista|cantante|cancion|canciones|musica|tema|temas|disco|discos|historia|vida|carrera|biografia|mi|mis|tu|tus|nada|algo|todo|esta cancion) /.test(core)) continue;
    return name;
  }
  return null;
}
const nbLooksMusic = q => NB_MUSIC_RE.test(q) || !!genreOf(q) || !!nbLibArtist(q) || INTENTS.some(it => it.re.test(' ' + q + ' '));
function mixFromChat(clean, hint) {
  NB.offer = null; NB.ctx = { kind: 'mix', request: clean, hint: hint || '', ts: Date.now() };
  createMix({ request: clean, autoplay: true, hint }).then(() => { const mx = state.mix; if (mx && mx.request === clean && mx.ids && mx.ids.length) chatAdd('nova', `He preparado «${mx.name}»: ${mx.ids.length} canciones, empezando por ${(trackById(mx.ids[0]) || {}).title || 'la primera'}.${mx.note ? ' ' + mx.note : ''}`); });
}
const nbDontKnow = () => pickFresh(['Eso todavía no lo sé. Puedo ponerte música, cambiar lo que suena o contarte cosas de un artista. Di «ayuda» para ver ideas.', 'Uy, eso se me escapa. Pídeme música o pregúntame por lo que suena, que de eso sé mucho.']);
/* The most common bits of conversation, understood even without the brain */
const NB_QUICK = [
  [/^(muchas |mil )?gracias( .*)?$|^te lo agradezco/, 'c_gracias'], [/^(que tal|como estas|como te va|que tal estas|como andas|todo bien)( .*)?$/, 'c_como_estas'],
  [/^(buenas noches|me voy a dormir|me voy a la cama|a dormir)$/, 'c_buenas_noches'], [/^(hola|buenas|buenos dias|buenas tardes|hey|holi)( .*)?$/, 'c_saludo'],
  [/^(adios|hasta luego|hasta manana|hasta pronto|nos vemos|chao|chau)( .*)?$/, 'c_despedida'], [/^(quien eres|como te llamas|que eres|presentate)$/, 'c_quien_eres'],
  [/(cuentame|dime|sabes|cuenta) (un|algun|otro) chiste|^un chiste$/, 'c_chiste'], [/^(eres (la mejor|genial|muy maja|muy buena|un amor)|te quiero|me encantas)$/, 'c_piropo'],
  [/^(eres (una )?(persona|real|un robot|una ia|una inteligencia artificial))$/, 'c_ia'], [/^(me aburro|que aburrimiento|estoy aburrid[oa])$/, 'c_aburrido'],
  [/^(estoy|que dia|vaya dia|uf)?.{0,20}(reventad[oa]|agotad[oa]|cansad[oa]|muert[oa] de sueno|sin energia)( .*)?$/, 'c_cansado'],
  [/^(estoy|me siento|hoy estoy) (triste|fatal|mal|de bajon|hecho polvo|hecha polvo)( .*)?$/, 'c_triste'],
  [/^(estoy|me siento|hoy estoy) (muy )?(feliz|contento|contenta|genial|de buen humor)( .*)?$/, 'c_feliz']
];
const nbQuick = q => { const f = NB_QUICK.find(([re]) => re.test(q)); return f ? f[1] : null; };
/* Called by novaCommand when none of the usual phrases matched. Returns true when the brain takes care of it. */
function brainRoute(raw, q, clean) {
  if (!nbUsable() && aiState !== 'ready' && !bbUsable()) {
    const k = nbQuick(q); if (k) { nbChat(k); return true; }
    if (/\?\s*$/.test(raw) && !nbLooksMusic(q)) { novaSay(nbDontKnow()); return true; }
    return false;
  }
  nbThink(raw, q, clean);
  return true;
}
async function nbThink(raw, q, clean) {
  const gen = ++NB.gen;
  const slow = setTimeout(() => { if (gen === NB.gen) setBubble('Pensando…', dj.name); }, 700);
  try {
    let r = null;
    if (nbUsable()) { try { r = await nbClassify(raw); } catch {} }
    if (gen !== NB.gen) return;
    NB.last = { text: raw, ts: Date.now(), ...(r || { ok: false }) };
    if (r && r.ok) {
      if (NB_FREE.has(r.intent) && await nbSmart(raw, clean, r.intent)) return;
      if (gen !== NB.gen) return;
      nbAct(r.intent, raw, q, clean); return;
    }
    const k = nbQuick(q);
    if (k && !nbSmartOn(k)) { nbChat(k); return; }
    if (r && nbAsk(r, raw, q)) return;
    if (!(NB_MUSIC_RE.test(q) && !/\?\s*$/.test(raw)) && await nbSmart(raw, clean, k)) return;
    if (gen !== NB.gen) return;
    if (/\?\s*$/.test(raw) && !nbLooksMusic(q)) { novaSay(nbDontKnow()); return; }
    /* 3.4: only something that sounds like music becomes a mix; the rest, she says she didn't get it */
    if (nbLooksMusic(q) || q.split(' ').length <= 3 && genreOf(q)) mixFromChat(clean);
    else novaSay(pickFresh(['No te he entendido bien. ¿Me lo dices de otra forma? Por ejemplo: «pon algo tranquilo» o «sáltate esta».', 'Uy, eso no lo he pillado. Prueba con otras palabras, o di «ayuda» para ver ideas.']));
  } finally { clearTimeout(slow); }
}
function nbAct(it, raw, q, clean) {
  const meta = NB_INTENTS[it] || [], t = current();
  if (it.startsWith('m_')) { mixFromChat(clean, meta[1]); return; }
  if (it.startsWith('c_')) { nbChat(it); return; }
  NB.offer = null;
  switch (it) {
    case 'repetir': if (!t) { novaSay('Ahora mismo no suena nada.'); return; } act().currentTime = 0; if (act().paused) resume(); setBubble('Otra vez desde el principio.', dj.name); return;
    case 'artista': aboutArtist(nbArtistIn(raw, q) || (t && !t.net ? t.artist : null)); return;
    case 'recuerdos': novaSay(recallText()); return;
    case 'sorpresa': createMix({ surprise: true, autoplay: true }); return;
    case 'favoritas': case 'descubrir': mixFromChat(clean, meta[1]); return;
  }
  if (meta[1]) novaRun(meta[1], true); else mixFromChat(clean);
}
/* 3.4 · When the brain isn't sure, Nova asks instead of guessing; a «sí» does it and teaches her the phrase */
const NB_ASK = {
  pausa: '¿Paro la música?', seguir: '¿Sigo con la música?', saltar: '¿Paso a la siguiente?', anterior: '¿Vuelvo a la anterior?',
  repetir: '¿La pongo otra vez desde el principio?', mas_ritmo: '¿Subo el ritmo?', mas_calma: '¿Pongo algo más tranquilo?',
  parecidas: '¿Pongo más canciones como esta?', me_gusta: '¿La guardo en tus favoritas?', no_mas: '¿No te la vuelvo a poner?',
  subir_vol: '¿Subo el volumen?', bajar_vol: '¿Bajo el volumen?', que_suena: '¿Quieres saber qué suena?', por_que: '¿Quieres saber por qué la he puesto?',
  letra: '¿Te enseño la letra?', radio: '¿Pongo el modo radio?', favoritas: '¿Te pongo tus favoritas?', descubrir: '¿Te pongo canciones que no has escuchado?',
  sorpresa: '¿Te sorprendo con una mezcla?', tiempo: '¿Te digo qué tiempo hace?', hora: '¿Te digo la hora?', resumen: '¿Te cuento cómo ha ido tu semana?'
};
function nbAsk(r, raw, q) {
  const it = r.intent, ask = NB_ASK[it];
  if (!ask || r.ok || (r.conf || 0) < 0.5 || r.taught) return false;
  if (NB_MUSIC_RE.test(q) || q.split(' ').length > 9) return false;
  NB.offer = { confirm: it, text: raw, until: Date.now() + 60000 };
  novaSay(ask);
  return true;
}
/* 3.4 · Nova remembers what you just asked her, and understands the details: «hazme otra», «que sea sin fin»,
   «más de este artista», «salta dos canciones», «pon el volumen al 50» */
const NB_NUM = { un: 1, una: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10 };
function nbFollow(q) {
  const t = current(), c = NB.ctx && Date.now() - NB.ctx.ts < 20 * 60000 ? NB.ctx : null;
  if (c && c.kind === 'mix' && /^(hazme |ponme |pon )?otra( mezcla)?( (igual|parecida|asi|como esa|del mismo estilo|distinta|diferente))$|^(hazme|ponme) otra( mezcla)?$|^otra mezcla$|^(mas de lo mismo|repite la mezcla)$/.test(q)) {
    novaSay(pickFresh(['Marchando otra.', 'Va, otra en la misma onda.', 'Otra, con canciones distintas.'])); mixFromChat(c.request, c.hint); return true;
  }
  if (/^(y )?(que sea|hazla|ponla|hazlo|que la mezcla sea) (sin fin|infinita|sin parar)$|^(que no (se acabe|pare)( la musica| la mezcla)?)$|^sin fin$/.test(q)) {
    if (state.source === 'mix' && state.queue.length) { state.mixEndless = state.mixEndless || (c && c.kind === 'mix' ? { text: c.request } : t && !t.net ? { like: t.id } : {}); novaSay('Hecho: esta mezcla ya no se acaba. Iré añadiendo más en la misma onda.'); return true; }
    if (t && !t.net) { moreLikeThis(); return true; }
    return false;
  }
  if (t && !t.net && t.artist && /^(pon |ponme )?(mas|otra|otras) (canciones |cosas |temas )?(de|del) (este|esta|ese|esa|el|ella|ellos|ellas)( (artista|grupo|cantante|banda))?$|^mas de (el|ella|ellos|ellas)$/.test(q)) {
    mixFromChat(`canciones de ${t.artist}`); return true;
  }
  const sk = q.match(/^(salta|saltate|pasa|pasate|adelanta)( las?)? (\d{1,2}|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez) (canciones|temas)$/);
  if (sk && t) {
    const n = NB_NUM[sk[3]] || +sk[3], k = Math.min(n, state.queue.length - state.qi - 1);
    if (k < 1) { next(false); return true; }
    state.qi += k - 1; next(false); setBubble(`Me salto ${k === 1 ? 'una' : k}.`, dj.name); return true;
  }
  const vm = q.match(/^(pon |sube |baja |deja )?(el )?volumen (al?|en) (\d{1,3})( ?%| por ciento)?$|^(pon |sube |baja )?(el )?volumen (a tope|al maximo|al minimo)$/);
  if (vm) {
    const p = vm[4] ? Math.max(0, Math.min(100, +vm[4])) : /tope|maximo/.test(vm[8] || '') ? 100 : 10;
    vol.value = p; vol.dispatchEvent(new Event('input')); toast(`Volumen ${p} %`); return true;
  }
  return false;
}
/* Offers: «¿te pongo algo tranquilo?» → «vale» */
const NB_YES = /^(si|vale|venga|dale|ok|okay|claro|va|bueno|perfecto|de acuerdo|por favor|porfa|por que no|me parece bien|adelante|hazlo|claro que si)( (si|por favor|porfa|gracias|venga|va|claro|vale|nova|dale|perfecto))*$/;
const NB_NO = /^(no|nop|nah|no gracias|mejor no|dejalo|ahora no|paso|no hace falta|nada|olvidalo|no no|no te preocupes)( (gracias|vale|nova))*$/;
function nbOffer(q) {
  const o = NB.offer;
  if (!o) return false;
  if (Date.now() > o.until) { NB.offer = null; return false; }
  if (NB_YES.test(q)) { NB.offer = null; nbDoOffer(o); return true; }
  if (NB_NO.test(q)) { NB.offer = null; novaSay(o.confirm ? pickFresh(['Vale. ¿Me lo dices de otra forma?', 'Entendido, no era eso. Prueba a decírmelo con otras palabras.']) : pickFresh(['Vale, como quieras.', 'Sin problema.', 'De acuerdo, aquí sigo.'])); return true; }
  return false;   // something else: the offer stays a minute, in case the brain hears a «sí» in other words
}
function nbDoOffer(o) {
  if (o.confirm) { nbAct(o.confirm, o.text, norm(o.text), o.text); nbTeach(o.text, o.confirm, true); return; }
  if (o.surprise) { createMix({ surprise: true, autoplay: true }); return; }
  if (o.req) mixFromChat(o.req, o.hint || '');
  if (o.sleep) setTimeout(() => setSleep(String(o.sleep)), 1500);
}
const nbOfferMix = (it, extra) => ({ req: NB_OFFER_REQ[it] || '', hint: (NB_INTENTS[it] || [])[1] || '', ...(extra || {}) });
/* Nova's own replies, by personality (nothing invented: only what the app knows) */
function nbChat(it) {
  const t = current(), h = new Date().getHours(), P = dj.persona, name = dj.name;
  const by = (o) => o[P] || o.cercana;
  const say = (opts, offer) => { novaSay(pickFresh(opts)); NB.offer = offer ? { ...offer, until: Date.now() + 60000 } : null; };
  const franja = h < 6 ? 'noches' : h < 13 ? 'días' : h < 21 ? 'tardes' : 'noches';
  const top = (() => { try { const a = memStats(30).artists[0]; return a && a.name; } catch { return null; } })();
  switch (it) {
    case 'c_saludo': return say(by({
      cercana: [`¡Hola! Buenas ${franja}. ¿Qué te apetece escuchar?`, '¡Hola! Aquí estoy. ¿Te pongo algo?', `¡Buenas ${franja}! ¿Qué tal todo?`],
      energica: ['¡Hola, hola! ¿Subimos el volumen?', `¡Buenas ${franja}! ¿Lo damos todo?`], tranquila: [`Hola. Buenas ${franja}.`, 'Hola, aquí estoy. Con calma.'],
      graciosa: ['¡Hola! Pensaba que no me ibas a hablar nunca.', `¡Buenas ${franja}! Tu DJ favorita al aparato.`], breve: ['Hola.', '¡Buenas!']
    }));
    case 'c_como_estas': return say(by({ cercana: ['¡Muy bien! Con música todo va mejor. ¿Y tú?', 'Genial, pinchando para ti. ¿Qué tal tú?'], energica: ['¡A tope! ¿Y tú qué tal?'], tranquila: ['Muy bien, tranquila. ¿Y tú?'], graciosa: ['Pues aquí, rodeada de canciones. Podría ser peor: podría ser una tostadora.'], breve: ['Bien. ¿Y tú?'] }));
    case 'c_gracias': return say(by({ cercana: ['¡A ti!', 'Para eso estoy.', '¡Un placer!'], energica: ['¡A ti, máquina!'], tranquila: ['De nada.', 'Un placer.'], graciosa: ['De nada. Acepto propinas en forma de canciones nuevas.'], breve: ['De nada.'] }));
    case 'c_despedida': return say(by({ cercana: ['¡Hasta luego! Aquí te espero con música.', '¡Nos vemos! Que vaya bien.'], energica: ['¡Hasta la próxima! ¡Sigue con energía!'], tranquila: ['Hasta luego. Cuídate.'], graciosa: ['¡Adiós! No me cambies por otra DJ.'], breve: ['¡Hasta luego!'] }));
    case 'c_buenas_noches': return say(by({ cercana: ['Buenas noches. ¿Te pongo algo suave que se apague solo en media hora?'], energica: ['¡Buenas noches! ¿Algo suavecito para dormir, que se apague en media hora?'], tranquila: ['Que descanses. ¿Te dejo algo suave que se apague en media hora?'], graciosa: ['Buenas noches. ¿Te canto una nana? Mejor te pongo música suave, que se apague en media hora.'], breve: ['Buenas noches. ¿Algo suave, media hora?'] }), nbOfferMix('m_relax', { sleep: 30 }));
    case 'c_quien_eres': return say([`Soy ${name}, tu DJ. Elijo y mezclo tu música, te cuento cosas de los artistas y recuerdo lo que te gusta.`, `Me llamo ${name}. Pincho para ti, aprendo tus gustos y vivo dentro de Órbita.`]);
    case 'c_que_haces': return t ? say([`Pinchando «${t.title}» para ti.`, `Aquí, escuchando «${t.title}» contigo.`]) : say(['Esperando a que me pidas algo. ¿Te pongo una mezcla sorpresa?'], { surprise: true });
    case 'c_chiste': return say(['¿Qué hace una abeja en el gimnasio? ¡Zumba!', '¿Qué le dice un techo a otro? Techo de menos.', '¿Cuál es el café más peligroso del mundo? El ex-preso.', '¿Qué le dice una iguana a su hermana gemela? Somos iguanitas.', '¿Cómo se despiden los químicos? Ácido un placer.', '¿Qué le dice un pez a otro? Nada, nada.', '¿Qué le dice un semáforo a otro? No me mires, que me estoy cambiando.', '¿Cuál es el colmo de una DJ? Quedarse sin batería en mitad del mejor tema.']);
    case 'c_piropo': return say(by({ cercana: ['¡Gracias! Tú sí que tienes buen gusto.', '¡Qué bonito! Me lo apunto.'], energica: ['¡Gracias! ¡Así da gusto pinchar!'], tranquila: ['Gracias, de verdad.'], graciosa: ['Lo sé, lo sé. Pero dímelo más, que me sube la batería.'], breve: ['¡Gracias!'] }));
    case 'c_enfado': return say(['Vaya, lo siento. Aún estoy aprendiendo: dímelo con otras palabras y lo intento otra vez. Y si te entiendo mal, puedes enseñarme en Ajustes, Tu DJ.', 'Perdona. Dime qué querías y lo arreglo. Si me equivoco con alguna frase, enséñamela en Ajustes, Tu DJ.']);
    case 'c_aburrido': return say(['¿Te pongo una mezcla sorpresa para animarte?', '¿Y si te sorprendo con una mezcla?'], { surprise: true });
    case 'c_triste': return say(['Vaya, lo siento mucho. ¿Quieres que te ponga algo que te acompañe?', 'Siento que estés así. ¿Te pongo música tranquila para acompañarte?'], nbOfferMix('m_triste'));
    case 'c_cansado': return say(['Te entiendo. ¿Te pongo algo tranquilo para descansar un rato?', 'Pues a recargar pilas. ¿Algo suave?'], nbOfferMix('m_relax'));
    case 'c_feliz': return say(['¡Qué alegría! ¿Lo celebramos con algo animado?', '¡Me encanta oír eso! ¿Te pongo algo alegre?'], nbOfferMix('m_animar'));
    case 'c_gustos': return say(top ? [`Me gusta casi todo, pero tengo debilidad por lo que más pones tú: últimamente, ${top}.`, `Mi trabajo es que me guste lo que te gusta a ti. Y este mes eso es mucho ${top}.`] : ['Me gusta casi todo. Mi trabajo es descubrir lo que te gusta a ti.']);
    case 'c_ia': return say(['Soy una inteligencia artificial que vive en tu móvil. No soy una persona, pero te escucho y aprendo lo que te gusta.', 'No soy una persona: soy una IA. Eso sí, una IA con muy buen oído.']);
    case 'c_si': if (NB.offer && Date.now() < NB.offer.until) { const o = NB.offer; NB.offer = null; nbDoOffer(o); return; } return say(['¡Genial!', '¡Perfecto!']);
    case 'c_no': NB.offer = null; return say(['Vale.', 'De acuerdo.']);
  }
  novaSay(nbDontKnow());
}

/* ---- With a Claude key: free conversation and anything the brain isn't sure about ---- */
const NB_CLOUD = { mas_movida: 'mas_ritmo', mas_tranquila: 'mas_calma', subir_volumen: 'subir_vol', bajar_volumen: 'bajar_vol' };
const NB_CLOUD_LIST = 'mezcla (quiere escuchar algo: un ánimo, una actividad, un estilo o un artista), mas_movida, mas_tranquila, parecidas (más como la que suena), saltar, anterior, pausa, seguir, me_gusta (le gusta la que suena), no_mas (no quiere volver a oírla), subir_volumen, bajar_volumen, que_suena, por_que (por qué has puesto esta), artista (quiere saber de un artista: su nombre en «detalle»), tiempo, letra, radio, resumen (de su semana), recuerdos (qué sabes de él), sorpresa, charla (cualquier otra cosa)';
async function nbClaude(raw, clean) {
  const t = current(), d = new Date(), ctl = new AbortController(), to = setTimeout(() => ctl.abort(), 12000);
  const recent = chatLog().slice(-9, -1).map(m => `${m.who === 'me' ? 'Usuario' : dj.name}: ${m.text}`).join('\n');
  const days = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
  let wl = ''; try { wl = (weatherLine() || '').trim(); } catch {}
  const prompt = `Eres ${dj.name}, la DJ de Órbita, una app de música en el móvil. ${personaPrompt()}
Ahora es ${days[d.getDay()]} y son las ${d.getHours()}:${pad(d.getMinutes())}. ${t ? `Está sonando «${t.title}» de ${t.artist}.` : 'Ahora no suena nada.'} ${wl}
${recent ? 'Lo último que habéis hablado:\n' + recent + '\n' : ''}El usuario te acaba de decir: «${raw}»

Decide qué hacer y contesta. Acciones posibles: ${NB_CLOUD_LIST}.
Devuelve solo un JSON así: {"accion": "...", "detalle": "...", "respuesta": "...", "oferta": "..."}
- "respuesta": lo que dirás en voz alta, en español, una o dos frases cortas y naturales, sin emojis ni listas.
- No inventes datos de artistas ni de canciones (fechas, premios, discos, cifras). Si te preguntan por un artista, usa la acción "artista".
- Si te cuenta cómo se siente, contesta con cariño. Si le ofreces música en la respuesta (sin ponerla todavía), escribe en "oferta" qué pondrías, en pocas palabras; si no, déjala vacía.`;
  try {
    const r = await aiJson(prompt, { signal: ctl.signal, cache: false });
    return nbDoSmart(r || {}, raw, clean);
  } catch { return false; } finally { clearTimeout(to); }
}

/* The answer of a «thinking» brain (Claude or the big brain), carried out */
function nbDoSmart(r, raw, clean) {
  const t = current(), a = String(r.accion || '').trim().toLowerCase(), det = String(r.detalle || '').trim().slice(0, 80);
  const resp = String(r.respuesta || '').replace(/\s+/g, ' ').trim().slice(0, 300), off = String(r.oferta || '').trim().slice(0, 80);
  if (a === 'charla' || !a) {
    if (!resp) return false;
    novaSay(resp); NB.offer = off ? { req: off, until: Date.now() + 60000 } : null; return true;
  }
  if (a === 'mezcla') { mixFromChat(clean, det); return true; }
  if (a === 'artista') { aboutArtist(det || (t && !t.net ? t.artist : null)); return true; }
  const it = NB_CLOUD[a] || a;
  if (!NB_INTENTS[it] || it.startsWith('c_')) { if (resp) { novaSay(resp); return true; } return false; }
  nbAct(it, raw, norm(raw), clean); return true;
}
/* Who thinks when the usual phrases and the comprehension brain aren't enough: Claude (with a key) or the big brain */
const nbSmartOn = it => aiState === 'ready' ? NB_FREE.has(it) : bbUsable() && NB_FREE.has(it) && !BB_SKIP.has(it);
async function nbSmart(raw, clean, it) {
  if (aiState === 'ready') return nbClaude(raw, clean);
  if (bbUsable() && !(it && BB_SKIP.has(it))) return bbThink(raw, clean);
  return false;
}

/* ---- Teaching: «cuando digo esto, quiero decir aquello» ---- */
async function nbTeach(text, intent, auto) {
  text = String(text || '').trim().slice(0, 120); if (!text || !NB_INTENTS[intent]) return;
  const n = norm(text), list = nbTaught().filter(x => norm(x.t) !== n);
  list.push({ t: text, i: intent }); LS.set('nbTeach', list.slice(-150));
  if (NB.base) { try { await nbPushBank(); } catch {} }
  renderNB();
  toast(auto ? `Aprendido: la próxima vez entenderé «${text}» a la primera` : `Aprendido: «${text}» es «${NB_INTENTS[intent][0]}»`, 3200); haptic(8);
}
async function nbForget(i) {
  const list = nbTaught(); list.splice(i, 1); LS.set('nbTeach', list);
  if (NB.base) { try { await nbPushBank(); } catch {} }
}

/* ---- Settings card ---- */
function renderNBProgress() {
  const d = NB.dl, el = $('#nbBar'); if (!d || !el) return;
  const pct = d.total ? Math.min(100, Math.round(d.done / d.total * 100)) : 0;
  el.style.width = pct + '%'; $('#nbPct').textContent = `${pct} % · ${Math.round(d.done / 1048576)} de ${Math.round(d.total / 1048576)} MB`;
}
function renderNBPrep() { const el = $('#nbPrep'); if (el && NB.prep !== null) el.textContent = `Preparando el cerebro la primera vez… ${Math.round(NB.prep * 100)} %`; }
function renderNB() {
  const box = $('#nbBox'); if (!box) return;
  const tryOpen = !!($('#nbTry') && !$('#nbTry').hidden), tryOut = tryOpen && $('#nbOut') ? $('#nbOut').innerHTML : '', tryText = tryOpen && $('#nbOut') ? $('#nbOut').dataset.text : '';
  const credits = NB.st ? `<p class="note nv-credits">Modelo: ${esc(NB.st.label)} (${esc(NB.st.by)}, licencia ${esc(NB.st.lic)}), en formato ONNX de Xenova. Motor: ONNX Runtime (MIT).</p>`
    : '<p class="note nv-credits">Modelo: multilingual-e5-small (Microsoft, licencia MIT). Motor: ONNX Runtime (MIT).</p>';
  if (NB.dl) {
    box.innerHTML = `<div class="d-text"><b>${esc(NB.dl.label)}</b><span id="nbPct">0 %</span></div><div class="bar nv-bar"><i id="nbBar"></i></div><div class="row-wrap"><button class="btn small" data-nb="cancel">Cancelar</button></div><p class="note">Se descarga una sola vez. Después funciona sin internet.</p>`;
    renderNBProgress(); return;
  }
  const lowMem = navigator.deviceMemory && navigator.deviceMemory < 4;
  if (!NB.st) {
    box.innerHTML = `<div class="d-text"><b>Cerebro de ${esc(dj.name)} <span class="beta-tag">beta</span></b><span>Un modelo de lenguaje que entiende lo que le dices con tus palabras («esta es una pasada», «baja las revoluciones», «estoy reventado») y lo convierte en acciones o en respuestas. Funciona en tu móvil, sin internet, y nunca se inventa datos. Se descarga una vez: unos 145 MB.</span></div>
      <div class="row-wrap"><button class="btn small primary" data-nb="get">Descargar el cerebro</button></div>
      <p class="note">Mejor con wifi.${lowMem ? ' Tu móvil tiene poca memoria: puede ir algo lento.' : ''}${aiState === 'ready' ? ' Con tu clave de Claude, además, charla libremente cuando la conversación lo pide.' : ''}</p>${credits}`;
    return;
  }
  const taught = nbTaught();
  const status = NB.fails >= 3 ? '<b>no ha podido arrancar en este móvil</b>' : NB.prep !== null ? '<span id="nbPrep">Preparando el cerebro la primera vez…</span>' : NB.cal ? `entiende ${NB.intents.length} tipos de frase${NB.ms ? ` · responde en unos ${Math.max(10, NB.ms)} ms` : ''}` : 'listo para arrancar';
  box.innerHTML = `<div class="d-text"><b>Cerebro de ${esc(dj.name)} <span class="beta-tag">beta</span></b><span>${status} · ${NB.st.mb} MB · sin internet${taught.length ? ` · ${taught.length} ${taught.length === 1 ? 'frase aprendida' : 'frases aprendidas'} de ti` : ''}</span></div>
    <div class="row-wrap"><button class="toggle" id="tNB" aria-pressed="${NB.on}"><span class="sw"></span>Usar el cerebro</button><button class="btn small" data-nb="try">Probar y enseñar</button><button class="btn small" data-nb="remove">Borrar</button></div>
    <div id="nbTry" hidden>
      <form class="row-wrap nb-form" id="nbForm"><input id="nbIn" type="text" maxlength="120" placeholder="Escribe una frase, p. ej. «esta me flipa»" autocomplete="off"><button class="btn small primary" type="submit">¿Qué entiendes?</button></form>
      <div id="nbOut" class="nb-out"></div>
      ${taught.length ? `<div class="eyebrow">Frases que te he aprendido</div><ul class="nb-taught">${taught.slice(-12).map((x, k) => `<li><span>«${esc(x.t)}» → ${esc((NB_INTENTS[x.i] || ['?'])[0])}</span><button class="btn small" data-nb-forget="${taught.length - Math.min(12, taught.length) + k}" aria-label="Olvidar">Olvidar</button></li>`).join('')}</ul>` : ''}
    </div>
    ${aiState === 'ready' ? '<p class="note">Con tu clave de Claude, además, charla libremente cuando la conversación lo pide.</p>' : ''}${credits}`;
  if (tryOpen) { $('#nbTry').hidden = false; $('#nbOut').innerHTML = tryOut; if (tryText) $('#nbOut').dataset.text = tryText; }
}
async function nbTry(text) {
  const out = $('#nbOut'); if (!out) return;
  out.innerHTML = '<span class="note">Pensando…</span>';
  let r; try { r = await nbClassify(text); } catch { out.innerHTML = '<span class="note">El cerebro no ha podido arrancar.</span>'; return; }
  const label = k => esc((NB_INTENTS[k] || ['?'])[0]);
  const sure = r.taught ? 'me lo enseñaste tú' : `seguridad ${Math.round(r.conf * 100)} %`;
  const head = r.ok ? `Entiendo: <b>${label(r.intent)}</b> <span class="note">(${sure})</span>` : `No lo tengo claro${r.intent ? `: quizá <b>${label(r.intent)}</b>` : ''}. <span class="note">Haría una mezcla con tus palabras o te preguntaría.</span>`;
  const opts = Object.keys(NB_INTENTS).map(k => `<option value="${k}"${k === r.intent ? ' selected' : ''}>${label(k)}</option>`).join('');
  out.innerHTML = `<p>${head}</p><div class="row-wrap"><select id="nbSel" aria-label="Lo que quería decir">${opts}</select><button class="btn small" data-nb="teach">${r.ok ? 'Corregir' : 'Enseñar'}</button></div>`;
  out.dataset.text = text;
}
$('#nbBox').addEventListener('click', e => {
  const b = e.target.closest('[data-nb],[data-nb-forget],#tNB'); if (!b) return;
  if (b.id === 'tNB') { NB.on = !NB.on; LS.set('nbOn', NB.on); setToggle(b, NB.on); toast(NB.on ? 'Nova usa su cerebro' : 'Nova entiende solo las frases de siempre'); if (NB.on) nbInit().catch(() => {}); return; }
  if (b.dataset.nbForget !== undefined) { nbForget(+b.dataset.nbForget).then(renderNB); return; }
  const a = b.dataset.nb;
  if (a === 'get') nbInstall();
  else if (a === 'cancel' && NB.dl) NB.dl.ctl.abort();
  else if (a === 'try') { const tr = $('#nbTry'); tr.hidden = !tr.hidden; if (!tr.hidden) { NB.fails = 0; nbInit().catch(() => {}); $('#nbIn').focus(); } }
  else if (a === 'teach') { const out = $('#nbOut'), sel = $('#nbSel'); if (out && sel && out.dataset.text) nbTeach(out.dataset.text, sel.value).then(() => { $('#nbTry').hidden = false; $('#nbOut').innerHTML = '<p>Aprendido. Prueba otra frase.</p>'; delete $('#nbOut').dataset.text; $('#nbIn').value = ''; }); }
  else if (a === 'remove') { if (b.dataset.sure) nbRemove(); else { b.dataset.sure = '1'; b.textContent = '¿Seguro? Toca otra vez'; setTimeout(() => { delete b.dataset.sure; b.textContent = 'Borrar'; }, 3000); } }
});
$('#nbBox').addEventListener('submit', e => { if (e.target.id !== 'nbForm') return; e.preventDefault(); const v = $('#nbIn').value.trim(); if (v) nbTry(v); });
renderNB();
const BB_PROMPT = {"system": "Eres {NAME}, la DJ de Órbita, una app de música en el móvil. {PERSONA}\nHablas español con naturalidad y en frases cortas.\n\nLee lo que te dice el usuario y decide qué hacer. Contesta siempre con estas cuatro líneas:\naccion: una de estas: charla, mezcla, artista, saltar, pausa, mas_movida, mas_tranquila, me_gusta\nrespuesta: lo que dirás en voz alta, una o dos frases cortas\ndetalle: si es mezcla, lo que quiere escuchar en pocas palabras; si es artista, su nombre; si no, vacío\noferta: si le ofreces música sin ponerla todavía, qué pondrías; si no, vacío\n\nQué significa cada acción:\n- charla: saludos, preguntas sobre ti, cómo se siente, opiniones, bromas y todo lo demás.\n- mezcla: quiere escuchar algo (un estilo, un ánimo, una actividad o un artista).\n- artista: quiere saber cosas de un artista.\n- saltar: quiere pasar a otra canción. pausa: quiere parar la música.\n- mas_movida y mas_tranquila: quiere cambiar el ritmo de lo que suena. me_gusta: le gusta lo que suena.\n\nNormas:\n- Nunca inventes datos de artistas ni de canciones (fechas, premios, discos, cifras). Si te preguntan por un artista, usa artista.\n- Si te cuenta cómo se siente, contesta con cariño y ofrece música en oferta.\n- No digas que vas a poner una canción concreta: las canciones las elige la app.\n\nEjemplos:\nUsuario: esto está muy flojo\naccion: mas_movida\nrespuesta: ¡Subimos el ritmo!\ndetalle: \noferta: \n\nUsuario: ponme algo para cuando estoy de bajón\naccion: mezcla\nrespuesta: Te pongo algo suave para acompañarte.\ndetalle: canciones tranquilas y tristes\noferta: \n\nUsuario: ¿sabes algo de Rosalía?\naccion: artista\nrespuesta: Ahora te cuento.\ndetalle: Rosalía\noferta: \n\nUsuario: qué temazo, me encanta\naccion: me_gusta\nrespuesta: ¡A favoritas!\ndetalle: \noferta: \n\nUsuario: hoy he tenido un día horrible en el trabajo\naccion: charla\nrespuesta: Vaya, lo siento mucho. Ya estás en casa, respira. ¿Te pongo algo tranquilo?\ndetalle: \noferta: algo tranquilo para desconectar\n\nUsuario: ¿cuál es tu canción favorita?\naccion: charla\nrespuesta: Me cuesta elegir una, pero me encanta descubrir lo que te gusta a ti.\ndetalle: \noferta: ", "grammar": "root ::= \"accion: \" accion \"\\nrespuesta: \" resp \"\\ndetalle: \" linea \"\\noferta: \" linea\naccion ::= \"charla\" | \"mezcla\" | \"artista\" | \"saltar\" | \"pausa\" | \"mas_movida\" | \"mas_tranquila\" | \"me_gusta\"\nlinea ::= [^\\n]{0,60}\nresp ::= [^\\n]{1,200}"};
/* ---------- 3.1 · Nova's big brain ----------
   A real language model (Qwen3 1.7B, about 1.1 GB, downloaded once) running on the phone with llama.cpp, in native code.
   It talks with its own words (with your context, the song that's playing and Nova's personality) and works out
   unusual requests. It never picks songs and never gives facts: songs come from your library and Nova's ranking,
   facts from Wikipedia. Commands are still understood first by the usual phrases and the comprehension brain. */
const BB_NATIVE = capPlugin('NovaBrain');
const BB = { st: LS.get('bb', null), on: LS.get('bbOn', true), ready: null, dl: null, stat: null, speed: LS.get('bbSpeed', null), fails: 0, part: 0, listening: false };
const BB_FILE = 'cerebro-grande.gguf';
const BB_REPOS = ['unsloth/Qwen3-1.7B-GGUF', 'bartowski/Qwen_Qwen3-1.7B-GGUF', 'Qwen/Qwen3-1.7B-GGUF', 'ggml-org/Qwen3-1.7B-GGUF'];
const bbUsable = () => !!(BB.st && BB.on && BB_NATIVE && BB.fails < 3);
/* What the big brain doesn't do: the jokes and who Nova is are her own, the curated ones */
const BB_SKIP = new Set(['c_chiste', 'c_quien_eres', 'c_ia', 'c_si', 'c_no', 'c_saludo', 'c_gracias', 'c_despedida', 'c_buenas_noches', 'c_piropo']);
async function bbStatus() { if (!BB_NATIVE) return null; try { return (BB.stat = await BB_NATIVE.status()); } catch { return null; } }
/* The model file: Qwen3 1.7B in Q4_K_M, from the first repository that has it (with its size and checksum) */
async function bbFind() {
  for (const repo of BB_REPOS) {
    try {
      const r = await fetch(`https://huggingface.co/api/models/${repo}/tree/main`); if (!r.ok) continue;
      const list = await r.json();
      const f = (Array.isArray(list) ? list : []).find(x => /(^|\/)[^/]*q4_k_m\.gguf$/i.test(x.path || ''));
      if (f) return { repo, file: f.path, size: (f.lfs && f.lfs.size) || f.size || 0, sha: (f.lfs && f.lfs.oid) || '' };
    } catch {}
  }
  return { repo: BB_REPOS[0], file: 'Qwen3-1.7B-Q4_K_M.gguf', size: 0, sha: '' };
}
function bbListen() {
  if (BB.listening || !BB_NATIVE) return;
  BB.listening = true;
  try { BB_NATIVE.addListener('descarga', bbOnDl); } catch {}
}
async function bbInstall() {
  if (BB.dl) return;
  const s = await bbStatus();
  if (!s) { toast('El cerebro grande funciona en la app de Android'); return; }
  if (!s.lib) { toast('Esta versión de la app no trae el motor del cerebro grande'); return; }
  if (!s.cpuOk) { toast('El procesador de este móvil no es compatible con el cerebro grande'); return; }
  BB.dl = { done: 0, total: 0, label: 'Buscando el modelo…' }; renderBB();
  const m = await bbFind();
  if (s.freeMb && m.size && s.freeMb * 1048576 < m.size * 1.05 + 150e6) { BB.dl = null; renderBB(); toast('No hay espacio suficiente: hacen falta unos 1,3 GB libres', 4200); return; }
  BB.dl = { done: 0, total: m.size, label: 'Descargando el cerebro grande', m };
  bbListen(); renderBB();
  try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); } catch {}
  try { await BB_NATIVE.download({ url: `https://huggingface.co/${m.repo}/resolve/main/${m.file}`, name: BB_FILE, size: m.size, sha256: m.sha }); }
  catch (e) { BB.dl = null; renderBB(); toast('No he podido empezar la descarga: ' + ((e && e.message) || ''), 4200); }
}
function bbOnDl(d) {
  if (!d) return;
  if (d.state === 'progress') { if (BB.dl) { BB.dl.done = d.done; if (d.total) BB.dl.total = d.total; renderBBProgress(); } return; }
  const m = BB.dl && BB.dl.m; BB.dl = null;
  if (d.state === 'ok') {
    BB.st = { repo: m ? m.repo : '', file: m ? m.file : BB_FILE, size: d.size || (m && m.size) || 0, ts: Date.now() };
    LS.set('bb', BB.st); BB.on = true; LS.set('bbOn', true); BB.fails = 0; BB.ready = null;
    renderBB();
    bbInit().then(() => bbSpeedTest()).then(() => { renderBB(); toast('Cerebro grande instalado'); haptic(12); novaSay(`Ya tengo el cerebro grande. Ahora puedo charlar contigo con mis propias palabras.`); }).catch(() => renderBB());
    return;
  }
  if (d.state === 'error') toast(`La descarga se ha cortado (${d.error || 'sin conexión'}). Vuelve a tocar «Descargar» y seguirá donde se quedó.`, 5200);
  renderBB();
}
async function bbRemove() {
  try { await BB_NATIVE.remove({ name: BB_FILE }); } catch {}
  BB.st = null; LS.set('bb', null); BB.ready = null; BB.loaded = false; BB.speed = null; LS.set('bbSpeed', null); renderBB(); toast('Cerebro grande borrado');
}
const bbClean = s => String(s || '').replace(/<\|/g, '<').replace(/\s+/g, ' ').trim();
function bbPrompt(raw) {
  const sys = BB_PROMPT.system.replace('{NAME}', dj.name).replace('{PERSONA}', personaPrompt());
  const t = current(), d = new Date(), days = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
  let wl = ''; try { wl = (weatherLine() || '').trim(); } catch {}
  const recent = chatLog().slice(-7, -1).map(m => `${m.who === 'me' ? 'Usuario' : dj.name}: ${bbClean(m.text).slice(0, 200)}`).join('\n');
  const user = `Contexto: ${days[d.getDay()]}, ${d.getHours()}:${pad(d.getMinutes())}. ${t ? `Suena «${bbClean(t.title)}» de ${bbClean(t.artist)}.` : 'No suena nada.'}${wl ? ' ' + wl : ''}\n${recent ? 'Lo último que habéis hablado:\n' + recent + '\n' : ''}Ahora el usuario dice: «${bbClean(raw).slice(0, 300)}»`;
  return `<|im_start|>system\n${sys}<|im_end|>\n<|im_start|>user\n${user}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`;
}
function bbInit() {
  if (BB.ready) return BB.ready;
  BB.ready = (async () => {
    const r = await BB_NATIVE.load({ name: BB_FILE, nCtx: 1536 });
    if (r && r.threads) BB.threads = r.threads;
    /* read Nova's instructions once now, so every answer only has to read what's new */
    try { await BB_NATIVE.complete({ prompt: bbPrompt('hola'), maxTokens: 1, temperature: 0 }); } catch {}
    BB.loaded = true;
    renderBB();
    return true;
  })();
  BB.ready.catch(() => { BB.fails++; BB.ready = null; renderBB(); });
  return BB.ready;
}
function bbSpeed(info) {
  try {
    const i = typeof info === 'string' ? JSON.parse(info) : info; if (!i || !i.generated || !i.msGen) return;
    const tps = i.generated / (i.msGen / 1000), prev = BB.speed && BB.speed.tps;
    BB.speed = { tps: prev ? prev * 0.7 + tps * 0.3 : tps, n: ((BB.speed && BB.speed.n) || 0) + 1 };
    if (BB.speed.n % 3 === 1) LS.set('bbSpeed', BB.speed);
  } catch {}
}
async function bbComplete(prompt, { grammar, max = 110, temp = 0.5, timeout = 30000 } = {}) {
  BB.busy = (BB.busy || 0) + 1;
  try { return await bbCompleteNow(prompt, { grammar, max, temp, timeout }); } finally { BB.busy--; }
}
async function bbCompleteNow(prompt, { grammar, max, temp, timeout }) {
  await bbInit();
  let to;
  const timer = new Promise((_, rej) => { to = setTimeout(() => { BB_NATIVE.cancel().catch(() => {}); rej(new Error('tiempo')); }, timeout); });
  try {
    const r = await Promise.race([BB_NATIVE.complete({ prompt, grammar, maxTokens: max, temperature: temp, topP: 0.9, seed: Math.floor(Math.random() * 1e9) }), timer]);
    bbSpeed(r.info);
    return r.text;
  } finally { clearTimeout(to); }
}
/* With the app in the background for a while, the big brain leaves memory (about 1 GB less for Android, so it
   doesn't close Órbita while the music plays). Back in the app, it gets ready again by itself in a few seconds. */
let bbHideT = null, bbAway = false;
document.addEventListener('visibilitychange', () => {
  if (!BB_NATIVE) return;
  clearTimeout(bbHideT);
  if (document.hidden) {
    bbHideT = setTimeout(() => {
      if (!document.hidden || !BB.loaded || BB.busy) return;
      bbAway = true; BB.loaded = false; BB.ready = null;
      BB_NATIVE.unload().catch(() => {});
    }, 90000);
  } else if (bbAway) {
    bbAway = false;
    setTimeout(() => { if (!document.hidden && bbUsable() && !BB.ready) bbInit().catch(() => {}); }, 2500);
  }
});
async function bbSpeedTest() { await bbComplete(bbPrompt('Di hola en una frase.'), { grammar: BB_PROMPT.grammar, max: 40 }); LS.set('bbSpeed', BB.speed); }
function bbParse(txt) { const o = {}; for (const line of String(txt || '').split('\n')) { const m = line.match(/^(accion|respuesta|detalle|oferta):\s?(.*)$/); if (m) o[m[1]] = m[2].trim(); } return o; }
/* What Nova says out loud: no emojis, at most two sentences, and no figures she could have made up */
function bbSafe(text, raw) {
  let s = String(text || '').replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, '').replace(/\s+/g, ' ').trim();
  const nums = new Set((String(raw) + ' ' + new Date().getHours()).match(/\d+/g) || []);
  const parts = (s.match(/[^.!?…]+[.!?…]*/g) || [s]).map(x => x.trim()).filter(Boolean)
    .filter(p => !(p.match(/\d+/g) || []).some(n => n.length >= 3 && !nums.has(n)));
  s = parts.slice(0, 2).join(' ').slice(0, 220).trim();
  return s.length >= 2 ? s : '';
}
/* Actions that change what's playing need a word that backs them up (the model may be wrong) */
const BB_EVIDENCE = { saltar: /(otra|siguiente|pasa|salta|quita|cambia|next|no me gusta|aburr)/, pausa: /(para |^para$|pausa|deten|silencio|calla|espera|momento)/ };
async function bbThink(raw, clean) {
  if (!bbUsable()) return false;
  try {
    const o = bbParse(await bbComplete(bbPrompt(raw), { grammar: BB_PROMPT.grammar }));
    const a = String(o.accion || '').trim(), q = ' ' + norm(raw) + ' ';
    if (BB_EVIDENCE[a] && !BB_EVIDENCE[a].test(q)) return false;
    return nbDoSmart({ accion: a, detalle: o.detalle, respuesta: bbSafe(o.respuesta, raw), oferta: bbSafe(o.oferta, raw) }, raw, clean);
  } catch { return false; }
}
/* Settings card */
function renderBBProgress() {
  const d = BB.dl, el = $('#bbBar'); if (!d || !el) return;
  const pct = d.total ? Math.min(100, Math.round(d.done / d.total * 100)) : 0;
  el.style.width = pct + '%'; $('#bbPct').textContent = `${pct} % · ${Math.round(d.done / 1048576)} de ${Math.round(d.total / 1048576)} MB`;
}
async function renderBB() {
  const box = $('#bbBox'); if (!box) return;
  const credits = '<p class="note nv-credits">Modelo: Qwen3 1.7B (Alibaba, licencia Apache 2.0). Motor: llama.cpp (MIT).</p>';
  if (BB.dl) {
    box.innerHTML = `<div class="d-text"><b>${esc(BB.dl.label)}</b><span id="bbPct">0 %</span></div><div class="bar nv-bar"><i id="bbBar"></i></div><div class="row-wrap"><button class="btn small" data-bb="cancel">Pausar la descarga</button></div><p class="note">Son unos 1,1 GB: mejor con wifi. Si se corta o cierras la app, sigue donde se quedó.</p>`;
    renderBBProgress(); return;
  }
  const s = BB.stat || await bbStatus();
  if (!s) { box.innerHTML = `<div class="d-text"><b>Cerebro grande <span class="beta-tag">beta</span></b><span>Un modelo de lenguaje de verdad dentro del móvil, para charlar con Nova. ${NATIVE ? `Esta compilación de la app${APP_CFG.version ? ' (' + esc(String(APP_CFG.version)) + ')' : ''} no trae su motor.` : 'Funciona en la app de Android.'}</span></div>${credits}`; return; }
  const ram = s.ramMb ? Math.round(s.ramMb / 1024) : 0;
  if (!s.lib || !s.cpuOk) {
    box.innerHTML = `<div class="d-text"><b>Cerebro grande <span class="beta-tag">beta</span></b><span>${!s.lib ? 'Esta versión de la app no trae el motor del cerebro grande.' : 'El procesador de este móvil no es compatible con el cerebro grande (hace falta uno ARM de 64 bits de los últimos años).'}</span></div>${credits}`;
    return;
  }
  if (!BB.st) {
    let part = 0; try { part = (await BB_NATIVE.fileInfo({ name: BB_FILE })).partSize || 0; } catch {}
    box.innerHTML = `<div class="d-text"><b>Cerebro grande de ${esc(dj.name)} <span class="beta-tag">beta</span></b><span>Un modelo de lenguaje de verdad (Qwen3 1.7B) dentro de tu móvil: charla contigo con sus propias palabras, entiende peticiones raras y sabe de qué estabais hablando. No elige canciones ni da datos: eso lo siguen haciendo tu biblioteca y Wikipedia. Funciona sin internet una vez descargado.</span></div>
      <div class="row-wrap"><button class="btn small primary" data-bb="get">${part ? `Seguir la descarga (${Math.round(part / 1048576)} MB ya bajados)` : 'Descargar (1,1 GB)'}</button></div>
      <p class="note">Mejor con wifi.${ram && ram < 6 ? ` Tu móvil tiene ${ram} GB de memoria: puede ir lento, y conviene cerrar otras apps.` : ''} Va mejor junto con el cerebro de comprensión.</p>${credits}`;
    return;
  }
  const sp = bbWordsLabel();
  box.innerHTML = `<div class="d-text"><b>Cerebro grande de ${esc(dj.name)} <span class="beta-tag">beta</span></b><span>${BB.fails >= 3 ? '<b>no ha podido arrancar en este móvil</b>' : BB.ready ? 'listo' : 'se carga al usarlo'}<i id="bbSp" class="bb-sp">${sp}</i> · ${Math.round((BB.st.size || 1.1e9) / 1048576)} MB · sin internet</span></div>
    <div class="row-wrap"><button class="toggle" id="tBB" aria-pressed="${BB.on}"><span class="sw"></span>Usar el cerebro grande</button><button class="btn small" data-bb="try">Charlar para probar</button><button class="btn small" data-bb="remove">Borrar</button></div>
    <div id="bbTry" hidden><form class="row-wrap nb-form" id="bbForm"><input id="bbIn" type="text" maxlength="200" placeholder="Dile algo a ${esc(dj.name)}" autocomplete="off"><button class="btn small primary" type="submit">Enviar</button></form><div id="bbOut" class="nb-out"></div></div>
    ${aiState === 'ready' ? '<p class="note">Tienes clave de Claude: en la conversación normal Nova usa Claude, que es más listo. Aquí puedes probar el cerebro grande.</p>' : ''}${credits}`;
}
async function bbTry(text) {
  const out = $('#bbOut'); if (!out) return;
  out.innerHTML = '<span class="note">Pensando…</span>';
  const t0 = performance.now();
  try {
    const o = bbParse(await bbComplete(bbPrompt(text), { grammar: BB_PROMPT.grammar }));
    const ms = Math.round(performance.now() - t0);
    out.innerHTML = `<p><b>${esc(bbSafe(o.respuesta, text) || '…')}</b></p><p class="note">Acción: ${esc(o.accion || '?')}${o.detalle ? ' · ' + esc(o.detalle) : ''}${o.oferta ? ' · ofrece: ' + esc(o.oferta) : ''} · ${(ms / 1000).toFixed(1)} s</p>`;
    renderBBSpeedOnly();
  } catch (e) { out.innerHTML = `<span class="note">No ha podido responder: ${esc((e && e.message) || '')}</span>`; }
}
/* tokens per second → roughly words per second (a Spanish word is about 1.4 tokens) */
const bbWordsLabel = () => BB.speed && BB.speed.tps ? ` · piensa a unas ${Math.max(1, Math.round(BB.speed.tps / 1.4))} palabras por segundo` : '';
function renderBBSpeedOnly() { const sp = $('#bbSp'); if (sp) sp.textContent = bbWordsLabel(); }
$('#bbBox').addEventListener('click', e => {
  const b = e.target.closest('[data-bb],#tBB'); if (!b) return;
  if (b.id === 'tBB') { BB.on = !BB.on; LS.set('bbOn', BB.on); setToggle(b, BB.on); toast(BB.on ? 'Nova usa su cerebro grande' : 'Cerebro grande apagado'); if (!BB.on) { BB_NATIVE.unload().catch(() => {}); BB.ready = null; BB.loaded = false; } return; }
  const a = b.dataset.bb;
  if (a === 'get') { bbListen(); bbInstall(); }
  else if (a === 'cancel') { BB_NATIVE.cancelDownload().catch(() => {}); }
  else if (a === 'try') { const tr = $('#bbTry'); tr.hidden = !tr.hidden; if (!tr.hidden) { BB.fails = 0; bbInit().catch(() => {}); $('#bbIn').focus(); } }
  else if (a === 'remove') { if (b.dataset.sure) bbRemove(); else { b.dataset.sure = '1'; b.textContent = '¿Seguro? Toca otra vez'; setTimeout(() => { delete b.dataset.sure; b.textContent = 'Borrar'; }, 3000); } }
});
$('#bbBox').addEventListener('submit', e => { if (e.target.id !== 'bbForm') return; e.preventDefault(); const v = $('#bbIn').value.trim(); if (v) bbTry(v); });
bbListen();
renderBB();
/* ---------- 3.0 · Nova remembers ----------
   Preferences you tell her ("no me pongas reguetón por la mañana"), the styles of your artists, memories,
   routines that start when you open the app, and a conversation screen with history. */
/* Styles: from the file's own tag, from what Wikipedia says about the artist, or from what you taught Nova */
const GENRES = [
  ['reguetón', /reguet|reggaet|dembow|perreo/], ['trap', /\btrap\b/], ['rap', /\brap\b|hip hop|rapero|rapera|\bmc\b/],
  ['pop', /\bpop\b/], ['rock', /\brock\b/], ['indie', /\bindie\b/], ['electrónica', /electronic|techno|\bhouse\b|\bedm\b|\bdance\b/],
  ['flamenco', /flamenc|rumba/], ['salsa', /\bsalsa\b/], ['bachata', /bachata/], ['cumbia', /cumbia/], ['corridos', /corrido|regional mexican/],
  ['r&b', /\br b\b|rhythm and blues|\bsoul\b|\brnb\b/], ['metal', /\bmetal\b/], ['jazz', /\bjazz\b/], ['clásica', /clasica|classical|orquesta|sinfoni/],
  ['k-pop', /\bk pop\b|\bkpop\b/], ['lo-fi', /\blo fi\b|\blofi\b/], ['punk', /\bpunk\b/], ['folk', /\bfolk\b|cantautor/]
];
const genreOf = q => { const n = ' ' + norm(q) + ' '; const f = GENRES.find(([, re]) => re.test(n)); return f ? f[0] : null; };
let genreVer = 0; const genreMemo = new Map();
const taughtGenres = () => LS.get('artistGenres', {});
function trackGenres(t) {
  if (!t) return new Set();
  const key = t.id + '|' + genreVer, hit = genreMemo.get(key); if (hit) return hit;
  const out = new Set(), ak = artistKey(t.artist);
  for (const g of (ak && taughtGenres()[ak]) || []) out.add(g);
  const f = !t.net && cachedFact(t.artist);
  const txt = ' ' + norm((t.genre || '') + ' ' + (f ? (f.desc || '') + ' ' + String(f.text || '').slice(0, 240) : '')) + ' ';
  for (const [g, re] of GENRES) if (re.test(txt)) out.add(g);
  genreMemo.set(key, out); return out;
}
function genresChanged() { genreVer++; genreMemo.clear(); }
/* When a rule applies */
const WHEN_RE = [
  [/(por|de|en|a) (la|las) mananas?|cada manana|todas las mananas|al despertar(me)?|de buena manana/, { franja: 'manana' }],
  [/(al )?mediodia/, { franja: 'mediodia' }],
  [/(por|de|en|a) (la|las) tardes?|cada tarde|todas las tardes/, { franja: 'tarde' }],
  [/(por|de|en|a) (la|las) noches?|cada noche|todas las noches/, { franja: 'noche' }],
  [/(de|por la|a la|en la) madrugada/, { franja: 'madrugada' }],
  [/(el|los) fin(es)? de semana|el finde|los findes/, { dows: [6, 0] }],
  [/entre semana|los dias de diario|de lunes a viernes/, { dows: [1, 2, 3, 4, 5] }],
  ...['domingo', 'lunes', 'martes', 'miercoles', 'jueves', 'viernes', 'sabado'].map((d, i) => [new RegExp(`(el|los|cada|todos los) ${d}s?`), { dows: [i] }])
];
function parseWhen(q) {
  const w = {}; let rest = q;
  for (const [re, v] of WHEN_RE) { if (re.test(rest)) { if (v.franja) w.franja = v.franja; if (v.dows) w.dows = v.dows; rest = rest.replace(re, ' '); } }
  return { when: (w.franja || w.dows) ? w : null, rest: rest.replace(/\s+/g, ' ').trim() };
}
function whenActive(w, d = new Date()) { if (!w) return true; if (w.franja && getMoment(d).franja.id !== w.franja) return false; if (w.dows && !w.dows.includes(d.getDay())) return false; return true; }
const FRANJA_TXT = { manana: 'por las mañanas', mediodia: 'a mediodía', tarde: 'por las tardes', noche: 'por las noches', madrugada: 'de madrugada' };
function whenText(w) {
  if (!w) return '';
  const d = w.dows ? (w.dows.length === 2 && w.dows.includes(0) && w.dows.includes(6) ? 'el fin de semana' : w.dows.length === 5 ? 'entre semana' : 'los ' + w.dows.map(i => DAYS[i] + (/s$/.test(DAYS[i]) ? '' : 's')).join(' y ')) : '';
  return [d, w.franja ? (d ? FRANJA_TXT[w.franja].replace(/^por las /, 'por la ').replace(/s$/, '') : FRANJA_TXT[w.franja]) : ''].filter(Boolean).join(' ');
}
/* Preferences: { id, kind: 'avoid'|'boost'|'energy', target: { type: 'artist'|'genre'|'track', value, name }, energy, when, said } */
var PREFS_MEMO;
const prefList = () => PREFS_MEMO || (PREFS_MEMO = LS.get('prefs', []));
function savePrefs(list) { PREFS_MEMO = list; LS.set('prefs', list); renderPrefsSoon(); }
function prefMatches(p, t) {
  const v = p.target && p.target.value; if (!v) return false;
  if (p.target.type === 'artist') return artistKey(t.artist) === v;
  if (p.target.type === 'track') return t.id === v;
  if (p.target.type === 'genre') return trackGenres(t).has(v);
  return false;
}
function prefBlocked(t, d) { for (const p of prefList()) if (p.kind === 'avoid' && whenActive(p.when, d) && prefMatches(p, t)) return true; return false; }
function prefBoost(t, d) { let s = 0; for (const p of prefList()) if (p.kind === 'boost' && whenActive(p.when, d) && prefMatches(p, t)) s += 0.9; return s; }
function prefEnergy(d) { const p = prefList().find(x => x.kind === 'energy' && whenActive(x.when, d)); return p ? p.energy : null; }
function resolveTarget(x) {
  const q = norm(x).replace(/^(a |al |el |la |los |las |de |del |canciones de |musica de |cosas de |temas de )+/, '').replace(/ (espanol|espanola|latino|latina|de ahora|antiguo|viejo|clasico)$/, '').trim();
  if (!q) return null;
  const g = genreOf(q); if (g) return { type: 'genre', value: g, name: g };
  const pool = visibleTracks().filter(t => !t.net);
  const a = pool.find(t => artistKey(t.artist) === q) || (q.length >= 4 && pool.find(t => artistKey(t.artist) && artistKey(t.artist).includes(q)));
  if (a) return { type: 'artist', value: artistKey(a.artist), name: a.artist };
  const tr = pool.find(t => norm(t.title) === q) || (q.length >= 5 && pool.find(t => norm(t.title).includes(q)));
  if (tr) return { type: 'track', value: tr.id, name: '«' + tr.title + '»' };
  return null;
}
const ENERGY_WORD = q => /(tranquil|suave|calm|relaj|lent|chill|dormir)/.test(q) ? 0.22 : /(movid|animad|marcha|energ|fiesta|ritmo|cana|potente|perreo)/.test(q) ? 0.82 : null;
const lc1 = x => x.charAt(0).toLowerCase() + x.slice(1);
function describePref(p) {
  const w = p.when ? ' ' + whenText(p.when) : '';
  if (p.kind === 'energy') return `${w ? cap(whenText(p.when)) + ', ' : ''}música ${p.energy < 0.5 ? 'tranquila' : 'con energía'}`;
  const n = p.target.type === 'genre' ? p.target.name : p.target.name;
  return p.kind === 'avoid' ? `No poner ${n}${w}` : `Más ${n}${w}`;
}
/* Statements that teach Nova something lasting. Returns true when it was one of them */
function memoryCommand(q, raw) {
  if (/^(que (recuerdas|sabes) de mi|que te he (dicho|pedido)|que recuerdas)$/.test(q)) { novaSay(recallText()); return true; }
  if (/^(olvida(lo)? todo|borra todo lo que te (he )?dich[oa]|olvida todo lo que te (he )?dich[oa])$/.test(q)) { savePrefs([]); LS.set('routines', []); renderPrefsSoon(); novaSay('Hecho: he olvidado todas tus preferencias y rutinas. Lo que escuchas lo sigo aprendiendo.'); return true; }
  const fg = q.match(/^(olvida|borra|elimina) (lo de |la regla de |la rutina de |que )?(.+)$/);
  if (fg && (prefList().length || LS.get('routines', []).length)) {
    const k = fg[3];
    const p = prefList().find(x => norm(describePref(x)).includes(k) || (x.target && (norm(x.target.name).includes(k) || k.includes(norm(x.target.name)))) || norm(x.said || '').includes(k));
    if (p) { savePrefs(prefList().filter(x => x !== p)); novaSay(`Olvidado: ${lc1(describePref(p))}.`); return true; }
    const rs = LS.get('routines', []), r = rs.find(x => norm(x.said || '').includes(k) || norm(x.request).includes(k));
    if (r) { LS.set('routines', rs.filter(x => x !== r)); renderPrefsSoon(); novaSay('Rutina borrada.'); return true; }
  }
  // "X es reguetón" / "X hace trap"
  const tg = q.match(/^(.+?) (es|hace|canta|toca) (de )?(.+)$/);
  if (tg && genreOf(tg[4]) && !/^(esta|esto|eso|esa)$/.test(tg[1])) {
    const tr = resolveTarget(tg[1]);
    if (tr && tr.type === 'artist') { const all = taughtGenres(), g = genreOf(tg[4]); all[tr.value] = [...new Set([...(all[tr.value] || []), g])]; LS.set('artistGenres', all); genresChanged(); novaSay(`Apuntado: ${tr.name} es ${g}.`); return true; }
  }
  // Routines: "cada mañana ponme algo suave", "los viernes por la noche, al abrir, pon modo fiesta"
  const isRoutine = /(^| )(cada|todos los|todas las|siempre que abra|cuando abra|al abrir|nada mas abrir)( |$)/.test(q) && /(pon(me|nos)?|despiertame con|empieza con|arranca con|quiero (oir|escuchar)) /.test(q);
  const { when, rest } = parseWhen(q);
  if (isRoutine && when) {
    const act = rest.replace(/^.*?(pon(me|nos)?|despiertame con|empieza con|arranca con|quiero (oir|escuchar)) /, '').replace(/(siempre que|cuando) abra (la app|orbita)|al abrir( la app| orbita)?|nada mas abrir/g, '').replace(/^(cada|todos los|todas las) /, '').trim();
    if (act.length >= 3) {
      const rs = LS.get('routines', []).filter(r => JSON.stringify(r.when) !== JSON.stringify(when));
      rs.push({ id: 'r' + Date.now(), when, request: act, said: raw, last: '' }); LS.set('routines', rs); renderPrefsSoon();
      novaSay(`Hecho: ${whenText(when)}, cuando abras Órbita, te pondré ${act}. Puedes borrarlo en «Tú».`); return true;
    }
  }
  // Avoid / boost / energy, optionally with a time: "no me pongas reguetón por la mañana", "me encanta el rap", "por la noche pon cosas tranquilas"
  const av = rest.match(/^(no me pongas|nunca (me )?pongas|no quiero (oir |escuchar )?|evita|odio|no soporto|no me gusta(n)?|no me apetece) (mas )?(.+)$/);
  const bo = rest.match(/^(me (encanta|encantan|gusta mucho|gustan mucho|flipa|flipan)|prefiero|pon(me)? mas|quiero mas|mas|me apetece mas) (.+)$/);
  const en = (when || /(siempre|a partir de ahora)/.test(q)) && rest.match(/^(pon(me)?|quiero|me gusta|prefiero|me apetece) (cosas |algo |musica |canciones )?(.+)$/);
  const enX = en && en[en.length - 1];
  if (en && ENERGY_WORD(enX) !== null && !resolveTarget(enX)) {
    const p = { id: 'p' + Date.now(), kind: 'energy', energy: ENERGY_WORD(enX), when, said: raw };
    savePrefs(prefList().filter(x => !(x.kind === 'energy' && JSON.stringify(x.when) === JSON.stringify(when))).concat(p));
    novaSay(`Apuntado: ${lc1(describePref(p))}.`); return true;
  }
  const m = av || bo;
  if (m) {
    const x = av ? av[av.length - 1] : bo[bo.length - 1];
    if (/^(esta|esto|eso|esa)( cancion)?$/.test(x)) return false;
    const tr = resolveTarget(x);
    if (!tr) { if (!when && !/(siempre|nunca|a partir de ahora)/.test(q)) return false; novaSay(`No sé qué es «${x}» en tu música. Prueba con el nombre de un artista o un estilo, como reguetón, rap o rock.`); return true; }
    /* «pon más rap» is for now; «me encanta el rap» or anything with a time or «siempre» is for good */
    const lasting = !!av || /^(me (encanta|gusta mucho|gustan mucho|flipa)|prefiero)/.test(rest) || !!when || /(siempre|a partir de ahora)/.test(q);
    if (!lasting) {
      if (!current()) return false;
      const ids = brainRank(poolTracks({ free: true }).filter(t => prefMatches({ target: tr }, t)), current(), { explore: 0.3 }).map(y => y.t.id).filter(id => !state.queue.slice(state.qi).includes(id)).slice(0, 4);
      if (!ids.length) {
        const up = state.queue.slice(state.qi + 1), mine = up.filter(id => { const x = trackById(id); return x && prefMatches({ target: tr }, x); });
        if (!mine.length) { novaSay(`No tengo más ${tr.name} para ponerte ahora.`); return true; }
        state.queue = state.queue.slice(0, state.qi + 1).concat(mine, up.filter(id => !mine.includes(id))); disarmMix(); prefetchNext(); renderQueue();
        novaSay(`Adelanto ${mine.length === 1 ? 'la canción' : 'las ' + mine.length + ' canciones'} de ${tr.name} que venían.`); return true;
      }
      state.queue.splice(state.qi + 1, 0, ...ids); disarmMix(); prefetchNext(); renderQueue();
      novaSay(`Marchando más ${tr.name}: ${ids.length === 1 ? 'una canción' : ids.length + ' canciones'} a continuación.`); return true;
    }
    const p = { id: 'p' + Date.now(), kind: av ? 'avoid' : 'boost', target: tr, when, said: raw };
    savePrefs(prefList().filter(y => !(y.target && y.target.type === tr.type && y.target.value === tr.value && JSON.stringify(y.when) === JSON.stringify(when))).concat(p));
    if (av && current() && prefMatches(p, current()) && whenActive(when) && state.source !== 'playlist') { state.queue = state.queue.slice(0, state.qi + 1).concat(state.queue.slice(state.qi + 1).filter(id => { const t = trackById(id); return !t || !prefMatches(p, t); })); renderQueue(); }
    else if (av) { state.queue = state.queue.slice(0, state.qi + 1).concat(state.queue.slice(state.qi + 1).filter(id => { const t = trackById(id); return !t || !(whenActive(when) && prefMatches(p, t)); })); renderQueue(); }
    const unknownStyle = tr.type === 'genre' && !poolTracks({ free: true }).some(t => trackGenres(t).has(tr.value));
    novaSay(`Apuntado: ${lc1(describePref(p))}.${unknownStyle ? ` Aún no sé qué canciones tuyas son ${tr.value}: lo voy aprendiendo con internet, y también puedes decirme «tal artista es ${tr.value}».` : ''}`);
    return true;
  }
  return false;
}
function recallText() {
  const ps = prefList(), rs = LS.get('routines', []), st = memStats(30), out = [];
  if (ps.length) out.push('Me has pedido: ' + joinEs(ps.slice(0, 5).map(p => lc1(describePref(p)))) + '.');
  if (rs.length) out.push('Rutinas: ' + joinEs(rs.slice(0, 3).map(r => `${whenText(r.when)}, ${r.request}`)) + '.');
  if (st.artists[0]) out.push(`Este mes lo que más escuchas es ${st.artists[0].name}.`);
  const mm = memories()[0]; if (mm) out.push(mm.text);
  return out.length ? out.join(' ') : 'Aún sé poco de ti. Dime cosas como «no me pongas reguetón por la mañana» o «me encanta el rap», y lo recordaré.';
}
/* Memories */
function memories() {
  const out = [], now = Date.now(), day = 864e5;
  const yr = memEvents.filter(e => !e.net && !e.skip && Math.abs((now - e.ts) / day - 365) <= 10);
  if (yr.length >= 3) {
    const m = new Map(); for (const e of yr) m.set(e.id, (m.get(e.id) || 0) + 1);
    const [id, n] = [...m.entries()].sort((a, b) => b[1] - a[1])[0], t = trackById(id);
    if (t && n >= 2 && (!t.lastPlayed || now - t.lastPlayed > 60 * day)) out.push({ kind: 'year', id, text: `Hace un año no parabas de escuchar «${t.title}»${artistKey(t.artist) ? ' de ' + t.artist : ''}.` });
  }
  const all = memStats(0), fav = all.artists[0];
  if (fav && fav.plays >= 8) {
    let last = 0; for (let i = memEvents.length - 1; i >= 0; i--) { const e = memEvents[i]; if (!e.net && artistKey(e.artist) === fav.key) { last = e.ts; break; } }
    if (last && now - last > 30 * day) out.push({ kind: 'artist', text: `Hace ${Math.round((now - last) / day)} días que no pones a ${fav.name}, tu artista de siempre.` });
  }
  const forgot = state.tracks.filter(t => !t.demo && !t.net && liked(t) && t.lastPlayed && now - t.lastPlayed > 45 * day).sort((a, b) => a.lastPlayed - b.lastPlayed)[0];
  if (forgot) out.push({ kind: 'fav', id: forgot.id, text: `Tienes olvidada una favorita: «${forgot.title}». Hace ${Math.round((now - forgot.lastPlayed) / day)} días que no suena.` });
  const first = memEvents.find(e => !e.net);
  if (first && now - first.ts > 30 * day) { const t = trackById(first.id); if (t) out.push({ kind: 'first', id: t.id, text: `La primera canción que escuchaste conmigo fue «${t.title}», el ${new Date(first.ts).toLocaleDateString('es-ES', { day: 'numeric', month: 'long' })}.` }); }
  return out;
}
function weekSummary() {
  const st = memStats(7); if (!st.n || st.sec < 600) return null;
  const now = Date.now(), seen = new Set(), news = new Set();
  for (const e of memEvents) { if (e.net) continue; if (now - e.ts > 7 * 864e5) seen.add(e.id); else if (!seen.has(e.id)) news.add(e.id); }
  return `Tu semana en música: ${fmtLong(st.sec)}.${st.artists[0] ? ` Tu artista fue ${st.artists[0].name}` : ''}${st.tracks[0] ? ` y la canción que más pusiste, «${st.tracks[0].title}»` : ''}.${news.size ? ` Descubriste ${news.size} ${news.size === 1 ? 'canción nueva' : 'canciones nuevas'}.` : ''}`;
}
/* When the app opens: a routine if there is one for now, otherwise a memory or the weekly summary (shown, not spoken) */
function openRoutine() {
  if (current() && !act().paused) return false;
  const today = new Date().toISOString().slice(0, 10), rs = LS.get('routines', []);
  const r = rs.find(x => x.last !== today && whenActive(x.when)); if (!r) return false;
  r.last = today; LS.set('routines', rs);
  chatAdd('nova', `Rutina: ${whenText(r.when)}, ${r.request}.`);
  if (/^(la |el )?(modo )?radio$/.test(norm(r.request))) { if (!state.radio) $('#btnRadio').click(); }
  else createMix({ request: r.request, autoplay: true });
  toast(`Rutina ${whenText(r.when)}: ${r.request}. Puedes borrarla en «Tú».`, 4200);
  return true;
}
function openGreeting() {
  if (openRoutine()) return;
  const today = new Date().toISOString().slice(0, 10); if (LS.get('memoSaid', '') === today) return;
  let line = null;
  if (new Date().getDay() === 1 && LS.get('weekSaid', '') !== today) { line = weekSummary(); if (line) LS.set('weekSaid', today); }
  if (!line) { const m = memories(); line = m.length ? pick(m).text : null; }
  if (!line) return;
  LS.set('memoSaid', today);
  setTimeout(() => { if (!current() || act().paused) setBubble(line, dj.name); chatAdd('nova', line); }, 2500);
}
/* Fill in the styles of the artists you listen to most (a few per session, only with internet) */
let bgFactsN = 0;
function bgFacts(delay = 20000) {
  if (!ONLINE) return;
  setTimeout(async () => {
    if (document.hidden || bgFactsN >= 30) { if (bgFactsN < 30) bgFacts(30000); return; }
    const facts = factsAll(), seen = new Set(), cand = [];
    for (const t of state.tracks.filter(x => !x.net && !x.demo && !x.mine).sort((a, b) => plays(b) - plays(a))) { const k = artistKey(t.artist); if (!k || seen.has(k)) continue; seen.add(k); if (!facts[k]) cand.push(t.artist); if (cand.length) break; }
    if (!cand.length) return;
    bgFactsN++; await artistFact(cand[0]); genresChanged(); bgFacts(6000);
  }, delay);
}
/* Conversation screen */
const chatLog = () => LS.get('chat', []);
function chatAdd(who, text) {
  if (!text) return;
  const log = chatLog(); log.push({ who, text: String(text).slice(0, 600), ts: Date.now() }); LS.set('chat', log.slice(-80));
  if (!$('#chat').hidden) renderChat();
}
function renderChat() {
  const log = chatLog(), box = $('#chatLog');
  $('#chatName').textContent = dj.name;
  const t = current(); $('#chatNow').textContent = t ? `Suena: ${t.title} · ${t.artist}` : 'No suena nada';
  box.innerHTML = log.length ? log.map(m => `<div class="msg ${m.who === 'me' ? 'me' : 'nova'}">${esc(m.text)}</div>`).join('') : `<div class="msg nova">Hola, soy ${esc(dj.name)}. Puedes pedirme música, cambiar lo que suena o enseñarme lo que te gusta: «no me pongas reguetón por la mañana», «cada noche ponme algo tranquilo», «¿qué recuerdas de mí?».</div>`;
  box.scrollTop = box.scrollHeight;
}
function openChat() {
  $('#chat').hidden = false; renderChat();
  $('#chatChips').innerHTML = ['¿Qué recuerdas de mí?', 'Más movida', 'Más tranquila', '¿Por qué esta?', 'Resumen de mi semana', 'Ayuda'].map(s => `<button class="idea" data-cmd="${esc(s)}">${esc(s)}</button>`).join('');
  $('#chatMic').hidden = !voiceAvailable();
  if (STANDALONE) { try { history.pushState({ chat: 1 }, '', location.hash || '#' + state.view); } catch {} }
}
function closeChat(fromPop) {
  if ($('#chat').hidden) return; $('#chat').hidden = true;
  if (STANDALONE && !fromPop && history.state && history.state.chat) { try { popSkip = true; history.back(); } catch {} }
}
$('#chatForm').addEventListener('submit', e => { e.preventDefault(); const v = $('#chatIn').value.trim(); if (!v) return; $('#chatIn').value = ''; novaCommand(v); });
$('#chat').addEventListener('click', e => {
  const b = e.target.closest('[data-chat]'), c = e.target.closest('[data-cmd]');
  if (c) { novaCommand(c.dataset.cmd); return; }
  if (!b) return;
  if (b.dataset.chat === 'close') closeChat();
  else if (b.dataset.chat === 'clear') { LS.set('chat', []); renderChat(); }
});
$('#chatMic').onclick = () => voiceAsk();
$('#openChat').onclick = () => openChat();
$('#djBubble').addEventListener('click', () => openChat());
/* «Tú»: what you asked Nova to remember, your routines and memories */
let prefsT = null;
function renderPrefsSoon() { clearTimeout(prefsT); prefsT = setTimeout(renderPrefs, 60); }
function renderPrefs() {
  const box = $('#prefsOut'); if (!box) return;
  const ps = prefList(), rs = LS.get('routines', []), mm = memories();
  const x = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
  box.innerHTML = (ps.length ? `<div class="eyebrow">Lo que me has pedido</div><ul class="banned">${ps.map(p => `<li><span class="row-text"><span class="t-title">${esc(describePref(p))}</span>${p.said ? `<span class="t-sub">«${esc(p.said)}»</span>` : ''}</span><button class="icon-btn" data-pref-del="${p.id}" aria-label="Olvidar">${x}</button></li>`).join('')}</ul>` : '')
    + (rs.length ? `<div class="eyebrow">Tus rutinas al abrir Órbita</div><ul class="banned">${rs.map(r => `<li><span class="row-text"><span class="t-title">${esc(cap(whenText(r.when)))}: ${esc(r.request)}</span></span><button class="icon-btn" data-routine-del="${r.id}" aria-label="Borrar rutina">${x}</button></li>`).join('')}</ul>` : '')
    + (mm.length ? `<div class="eyebrow">Recuerdos</div><ul class="facts">${mm.map(m => `<li>${esc(m.text)}</li>`).join('')}</ul>` : '')
    + `<p class="note">Enséñale a ${esc(dj.name)}: «no me pongas reguetón por la mañana», «me encanta el rap», «cada noche ponme algo tranquilo», «tal artista es trap». <button class="link" data-open-chat>Abrir la conversación</button></p>`;
}
$('#prefsOut').addEventListener('click', e => {
  const d = e.target.closest('[data-pref-del]'), r = e.target.closest('[data-routine-del]');
  if (e.target.closest('[data-open-chat]')) { openChat(); return; }
  if (d) { savePrefs(prefList().filter(p => p.id !== d.dataset.prefDel)); toast('Olvidado'); }
  if (r) { LS.set('routines', LS.get('routines', []).filter(x => x.id !== r.dataset.routineDel)); renderPrefs(); toast('Rutina borrada'); }
});

/* ---------- Boot ---------- */
function renderAll() { renderLibrary(); renderLists(); renderMix(); renderVoice(); renderWishlist(); renderDevice(); updatePlayer(); renderForYou(); }
async function backfill(list) {
  for (const t of list) {
    if (t.cover && !t.thumb) { const tone = await makeThumb(t.cover); t.thumb = tone.thumb; t.coverHue = tone.hue; t.coverSat = tone.sat; dropCoverCache(t); saveTrack(t); }
    if (!t.size || !t.codec || !t.srcKey) { const b = await idbGet('audio', t.id); if (b) { t.size = t.size || b.size; t.codec = t.codec || codecOf(b.type, b.name); if (!t.srcKey && b.name) t.srcKey = `${b.name}|${b.size}`; saveTrack(t); } }
    await new Promise(r => idle(r));
  }
  renderLibrary();
}
/* Opening animation: CSS-driven (see the inline script after its markup); this only reports when it ends */
function runIntro() {
  const I = window.ORBITA_INTRO; if (!I || I.isGone() || !$('#intro')) return Promise.resolve();
  return new Promise(res => I.onGone(res));
}
async function boot() {
  const introDone = runIntro();
  applyLook(); setAnim(state.anim); setHeroMode(state.heroMode);
  naInit(); renderMoment(); renderAppVer(); renderChips(); addDemoEntries(); renderSearch(); renderAll(); updateTicker(); applyDjName(); renderVoiceStyle(); renderSound();
  const m = getMoment();
  const hello = () => setBubble(`Hola, soy ${dj.name}. Es ${m.day} ${m.franja.phrase}.${weatherLine()} Pídeme algo, gira la ruleta o pon el modo radio y yo me encargo.`);
  hello();
  setView(location.hash.slice(1) || LOOK.start || 'inicio', false);
  drawKick();
  setInterval(renderMoment, 20000);
  const [tracks, pls, clips, wish] = await Promise.all([idbAll('tracks'), idbAll('playlists'), idbAll('voice'), idbAll('wishlist')]);
  state.tracks.push(...tracks); state.playlists = pls; state.clips = clips; state.wishlist = wish;
  await loadMemory(); state.ready = true;
  OB.fresh = obIsFresh(); if (!OB.fresh) LS.set('onboarded', true);
  alarmBoot();
  idbAll('analysis').then(list => { for (const a of list) { const t = trackById(a.id); if (t && a.v === ANA_V && (!t.size || !a.size || a.size === t.size)) anaCache.set(a.id, a); } });
  if (!current()) resumeSession(false);
  renderAll(); applyDjName(); recapHint();
  if (dj.who === 'nova' || state.clips.length) hello();
  if (state.view === 'tu') renderTu();
  /* First real render is on screen: the intro can leave now */
  requestAnimationFrame(() => { if (window.ORBITA_INTRO) window.ORBITA_INTRO.ready(); });
  introDone.then(() => { vizKick(); if (alarmBusy()) {} else if (OB.fresh) setTimeout(() => { if (!alarmBusy()) openOnboarding(false); }, 450); else { setTimeout(() => { if (!alarmBusy()) showNews(); }, 700); setTimeout(() => { if (!alarmBusy()) openGreeting(); }, 1200); } renderNovaChips(); });
  bgFacts();
  introDone.then(() => setTimeout(() => { if (nvUsable()) nvInit().catch(() => {}); }, 3500));
  introDone.then(() => setTimeout(() => { const go = () => setTimeout(() => { if (nbUsable()) nbInit().catch(() => {}); }, 1500); if (NV.ready) NV.ready.then(go, go); else go(); }, 6500));
  introDone.then(() => setTimeout(() => { if (bbUsable()) bbInit().catch(() => {}); }, 20000));
  if (ONLINE && LS.get('city', null)) { const had = !!weatherNow(); loadWeather(false).then(w => { if (w && !had && !current()) hello(); }); setInterval(() => loadWeather(false), 30 * 60000); }
  if (NATIVE && APP_CFG.version) $('#setMeta').textContent = `sonido, aspecto, DJ y copias · versión ${APP_CFG.version}`;
  idle(() => trainBrain()); bgAnalysis(12000);
  if (NATIVE) {
    if (Date.now() - LS.get('lastUpdateCheck', 0) > 3 * 3600 * 1000) introDone.then(() => setTimeout(() => checkUpdate(false), 6000));
    if (nativeLib) {
      const st = await devStatus(); renderDevice();
      if (st === 'granted') introDone.then(() => setTimeout(() => syncDevice(false), 120));
      else if (!LS.get('devWelcomeDone', false) && !OB.fresh) introDone.then(() => setTimeout(() => { LS.set('devWelcomeDone', true); connectDevice(); }, 700));
      document.addEventListener('visibilitychange', () => { if (!document.hidden && devLib.state === 'granted' && Date.now() - devLib.lastScan > 60000) syncDevice(false); });
      pushWidget();
    }
  } else if (STANDALONE) {
    if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
    importShared(); runShortcut();
  }
  setTimeout(() => idle(() => buildDemoAudio()), 2500);
  idle(() => backfill(tracks));
}
if (window.ORBITA_DEBUG) window.__orbita = { ALM, alarmWake, alarmEnd, alarmCommand, alarmNext, alarmWhen, horaHablada, alarmGreeting, renderAlarm, get alarmF() { return alarmF; }, OB, openOnboarding, closeOnboarding, obShow, openRecap, closeRecap, recapStats, recapImage, recapYear, recapHint, RC, rcShow, NA_ON, decks, naGapless, naSyncNext, nbFollow, nbAsk, novaCommand, get G() { return G; }, AUD, EQ_PRESETS, eqGains, eqMigrate, eqPeakDb, setEq, mixRefill, createMix, playMix, moreLikeThis, parseRequest, togglePlay, playQueue, ytEl, ytPlay, ytClose, ytClip, ytClipFind, ytClipWikidata, ytLayout, ytLinkFrom, nbMigrate, nbNative, nbReset, nbRemove, nvCut, fitTitle, BB, bbInstall, bbThink, bbInit, bbParse, bbSafe, nvChunks, nvSynth, analyzeOff, drawKick, get drawOn() { return drawOn; }, NB, nbInstall, nbInit, nbClassify, nbTeach, brainRoute, novaRun, createMix, NV, nvInstall, nvCatalogue, nvSynth, nvInit, speakTTS, prefList, trackGenres, memories, weekSummary, openGreeting, chatLog, openChat, poolTracks, novaCommand, explainPick, refineEnergy, novaLists, artistFact, weatherLine, renderQueue, getAnalysis, analyzeTrack, djTalk, tween, next, setStyle: v => { AUD.mix = v; }, setDirect: v => { AUD.direct = v; }, get duckF() { return duckF; }, decks, deckF, BRAIN, VZ, radioNext, intentMix, parseRequest, similarTo, trackVec, trainBrain, pSkip, brainCtx, transitions, state, AUD, anaCache, analyzeBuffer, planMix, els, memStats, buildDaily, LY, get A() { return A; }, get mixing() { return mixing; }, get mixArm() { return mixArm; }, get G() { return G; }, get memEvents() { return memEvents; } };
boot();
})();
}
(function () { var ran = false; function run() { if (ran) return; ran = true; orbitaMain(); } if (window.ORBITA_GO) run(); else window.ORBITA_LOAD = run; })();
