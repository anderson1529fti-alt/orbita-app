package __PACKAGE__;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * El motor de sonido de Android para Órbita (3.5, beta): reproduce las canciones fuera del WebView, con el
 * decodificador del propio móvil, canciones del mismo disco sin hueco, ecualizador, volumen igualado y limitador.
 * La app lo maneja como a sus reproductores de siempre; si algo falla, vuelve al motor del WebView.
 */
@CapacitorPlugin(name = "NovaAudio")
public class NovaAudioPlugin extends Plugin implements NovaAudioEngine.Listener {

    static volatile NovaAudioPlugin instance;
    private volatile NovaSession session;
    private NovaAudioEngine engine;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean hasFocus;
    private PowerManager.WakeLock wake;
    private long wakeAt;
    private BroadcastReceiver noisy;
    private ScheduledExecutorService ticker;
    // 3.6: las escuchas que hace el motor (cuentan aunque la app esté dormida) y el temporizador para dormir
    private NovaListenLog listens;
    private volatile long sleepAt;
    private volatile long sleepFade = 30_000L;
    /* 3.9: otra app que suena (TikTok, un vídeo, una nota de voz…). Antes solo se avisaba a la app y, si Android la tenía
       dormida, la música seguía. Ahora se avisa y, si la app no contesta en 900 ms (dormida), el motor pausa solo con
       un fundido (1,5 s; 0,8 s si es algo corto, y entonces vuelve solo al acabar). Si la app está despierta, decide
       ella (sabe si lo que suena es la voz de Nova o un vídeo de la propia Órbita) y contesta con focusAck. */
    private volatile int focusPending = 0, focusSeq = 0;
    private volatile boolean resumeOnGain = false;
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) hasFocus = true;
        else if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false;
        JSObject o = new JSObject();
        o.put("type", "focus");
        o.put("value", change);
        o.put("t", System.currentTimeMillis());   // 3.9: la app, al despertar, no hace caso a los avisos atrasados
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            final int id = ++focusSeq; focusPending = id; o.put("id", id);
            final boolean shortOne = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
            try { later.schedule(() -> { if (focusPending == id) { focusPending = 0; fadePauseAll(shortOne ? 800 : 1500, shortOne, "otra app"); } }, 900, TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusPending = 0;
            if (resumeOnGain) { resumeOnGain = false; o.put("resumed", resumeFadeIn(700)); }
        }
        notifyListeners("audio", o);
    };

    /** 3.9 · Pausa todo lo que suene con un fundido (lo hace el motor: vale con la app dormida). */
    private void fadePauseAll(int ms, boolean resumeLater, String why) {
        final NovaAudioEngine e = engine;
        if (e == null || !e.anyPlaying()) return;
        resumeOnGain = resumeLater;
        e.fadeTo(0f, ms, () -> {
            for (int i = 0; i < NovaAudioEngine.SLOTS; i++) if (e.playing(i)) { e.pause(i); state(i, false); }
            e.fadeSet(1f);
            JSObject o = new JSObject(); o.put("type", "autopause"); o.put("why", why); notifyListeners("audio", o);
        });
    }
    /** 3.9 · Vuelve a sonar donde estaba, subiendo poco a poco (tras una interrupción corta). */
    private boolean resumeFadeIn(int ms) {
        NovaAudioEngine e = engine; NovaSession s = session;
        if (e == null || s == null || s.external) return false;
        int sl = s.slot;
        if (e.source(sl) == null) return false;
        if (e.playing(sl)) {
            /* 4.0b2 · la otra app duró menos que el fundido: aún estaba bajando. Se cancela la pausa y vuelve a subir. */
            if (!e.fadingOut()) return false;
            e.fadeTo(1f, ms, null); state(sl, true);
            return true;
        }
        e.fadeSet(0f); requestFocus(); e.play(sl); keepAwake(true); e.fadeTo(1f, ms, null); state(sl, true);
        return true;
    }

    /** 3.9 · La app (despierta) ya se encarga de este aviso de otra app: el motor no hace nada por su cuenta. */
    @PluginMethod
    public void focusAck(PluginCall call) {
        Integer id = call.getInt("id", 0);
        if (id == null || id == 0 || id == focusPending) focusPending = 0;
        call.resolve();
    }

    private int outputRate() {
        try {
            String r = audio().getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            int v = r != null ? Integer.parseInt(r) : 48000;
            return v >= 8000 && v <= 192000 ? v : 48000;
        } catch (Throwable t) {
            return 48000;
        }
    }

    /* 4.0b3 · ¿suena por Bluetooth? (se mira cada 2 s) */
    private int btTick;
    private boolean btOut() {
        try {
            for (android.media.AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 || t == 27) return true;   // 26, 27: auriculares y altavoces BLE
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private AudioManager audio() {
        if (am == null) am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        return am;
    }

    @Override
    public void load() {
        instance = this;
        try { listens = new NovaListenLog(getContext()); } catch (Throwable ignored) { }
    }

    private synchronized NovaSession sess() {
        if (session == null) session = new NovaSession(getContext());
        return session;
    }

    private synchronized NovaAudioEngine eng() {
        if (engine == null) {
            final Context ctx = getContext().getApplicationContext();
            engine = new NovaAudioEngine(src -> new AndroidDecoder(ctx, src), new AndroidOutput(outputRate()), this);
            engine.start();
            ticker = Executors.newSingleThreadScheduledExecutor();
            ticker.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
            registerNoisy();
        }
        return engine;
    }

    /** Cuatro veces por segundo: dónde va cada plato (para la barra de progreso) y si hace falta mantener el móvil despierto. */
    private void tick() {
        try {
            NovaAudioEngine e = engine;
            if (e == null) return;
            boolean any = false;
            for (int i = 0; i < NovaAudioEngine.SLOTS; i++) {
                if (!e.playing(i)) continue;
                any = true;
                JSObject o = new JSObject();
                o.put("type", "pos");
                o.put("slot", i);
                o.put("pos", e.heardPosition(i));   // 4.0b3: lo que se oye (la barra, la letra y las luces van con el sonido)
                o.put("dur", e.duration(i));
                notifyListeners("audio", o);
            }
            keepAwake(any);
            countListen(e);
            sleepTick(e);
            if (++btTick % 8 == 0) e.setBluetooth(btOut());
        } catch (Throwable ignored) {
        }
    }

    /** 3.6 · Apunta lo que suena en el plato del aviso (la canción de ahora, cuánto lleva). */
    private void countListen(NovaAudioEngine e) {
        NovaListenLog L = listens; if (L == null) return;
        NovaSession s = session;
        if (s == null || s.external) { L.tick(-1, null, false, 0, 0, null, null); return; }
        int sl = s.slot;
        String src = e.source(sl);
        NovaSession.PItem it = s.now();
        boolean mine = it != null && src != null && src.equals(it.src);
        L.tick(sl, src, e.playing(sl), e.position(sl), e.duration(sl), mine ? it.title : null, mine ? it.artist : null);
    }

    /** 3.6 · El temporizador para dormir, hecho aquí: baja la música poco a poco y la para, aunque la app esté dormida. */
    private void sleepTick(NovaAudioEngine e) {
        long at = sleepAt;
        if (at <= 0) return;
        long left = at - System.currentTimeMillis();
        if (left > 0) {
            long fade = Math.max(1000L, sleepFade);
            e.setSleepGain(left < fade ? Math.max(0.02f, left / (float) fade) : 1f);
            return;
        }
        sleepAt = 0;
        boolean was = false;
        for (int i = 0; i < NovaAudioEngine.SLOTS; i++) if (e.playing(i)) { e.pause(i); was = true; }
        e.setSleepGain(1f);
        NovaSession s = session;
        if (s != null && !s.external && was) state(s.slot, false);
        JSObject o = new JSObject();
        o.put("type", "sleep"); o.put("t", System.currentTimeMillis()); o.put("paused", was);
        notifyListeners("audio", o);
    }

    private synchronized void keepAwake(boolean on) {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orbita:musica");
                wake.setReferenceCounted(false);
            }
            long now = System.currentTimeMillis();
            if (on && (!wake.isHeld() || now - wakeAt > 60_000L)) { wake.acquire(30 * 60 * 1000L); wakeAt = now; }
            else if (!on && wake.isHeld()) wake.release();
        } catch (Throwable ignored) {
        }
    }

    /** Si se desconectan los auriculares, la música se para (y no sale de golpe por el altavoz). */
    private void registerNoisy() {
        if (noisy != null) return;
        noisy = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) {
                    /* 3.9: el motor pausa él solo (antes lo hacía la app, y si estaba dormida la música seguía por el
                       altavoz). Fundido corto: al cuadrado, en 150 ms ya ha bajado a la mitad. */
                    NovaAudioEngine e = engine;
                    boolean mine = e != null && e.anyPlaying();
                    if (mine) { resumeOnGain = false; focusPending = 0; fadePauseAll(500, false, "auriculares"); }
                    JSObject o = new JSObject();
                    o.put("type", "noisy"); o.put("handled", mine); o.put("t", System.currentTimeMillis());
                    notifyListeners("audio", o);
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
            else getContext().registerReceiver(noisy, f);
        } catch (Throwable ignored) {
        }
    }

    private void requestFocus() {
        if (hasFocus) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq == null) {
                    AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(aa).setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(false).build();
                }
                hasFocus = audio().requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                hasFocus = audio().requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Throwable ignored) {
        }
    }

    private int slot(PluginCall call) {
        Integer s = call.getInt("slot", 0);
        int v = s == null ? 0 : s;
        return Math.max(0, Math.min(NovaAudioEngine.SLOTS - 1, v));
    }

    private float num(PluginCall call, String k, float d) {
        Object o = call.getData() != null ? call.getData().opt(k) : null;
        return o instanceof Number ? ((Number) o).floatValue() : d;
    }

    // ---------------------------------------------------------------- lo que pide la app
    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", true);
        r.put("rate", outputRate());
        r.put("v", 2);
        NovaAudioEngine e = engine;   // 4.0b3 · para el diagnóstico: salida y lo que tarda en oírse
        r.put("bt", btOut());
        r.put("lat", e != null ? Math.round(e.latency() * 1000) : -1);
        call.resolve(r);
    }

    @PluginMethod
    public void load(PluginCall call) {
        String src = call.getString("src");
        if (src == null || src.isEmpty()) { call.reject("sin canción", "NO_SRC"); return; }
        boolean play = Boolean.TRUE.equals(call.getBoolean("play", false));
        if (play) requestFocus();
        NovaSession s = session; NovaListenLog L = listens;
        if (L != null && s != null && !s.external && s.slot == slot(call)) L.end("app");
        eng().load(slot(call), src, num(call, "at", 0f), play);
        if (play) keepAwake(true);
        call.resolve();
    }

    @PluginMethod
    public void play(PluginCall call) {
        if (engine != null && engine.fadeLevel() < 1f) engine.fadeSet(1f);   // 3.9: un fundido a medias no se lleva lo que pides
        resumeOnGain = false;
        requestFocus();
        eng().play(slot(call));
        keepAwake(true);
        stateToSession(slot(call));
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        eng().pause(slot(call));
        stateToSession(slot(call));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (engine != null) engine.stop(slot(call));
        call.resolve();
    }

    @PluginMethod
    public void seek(PluginCall call) {
        eng().seek(slot(call), num(call, "pos", 0f));
        call.resolve();
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        eng().setVolume(slot(call), num(call, "v", 1f));
        call.resolve();
    }

    /** 3.5b4: el volumen igualado de la canción de este plato (cada canción el suyo, también durante un fundido). */
    @PluginMethod
    public void setSongGain(PluginCall call) {
        eng().setSongGain(slot(call), num(call, "g", 1f));
        call.resolve();
    }

    @PluginMethod
    public void setNorm(PluginCall call) {
        eng().setNorm(slot(call), num(call, "g", 1f));
        call.resolve();
    }

    @PluginMethod
    public void setMaster(PluginCall call) {
        eng().setMaster(num(call, "v", 1f));
        call.resolve();
    }

    @PluginMethod
    public void setEq(PluginCall call) {
        float[] g = new float[10];
        try {
            JSArray a = call.getArray("gains");
            for (int i = 0; i < 10 && a != null && i < a.length(); i++) g[i] = (float) a.getDouble(i);
        } catch (Throwable ignored) {
        }
        eng().setEq(g, Boolean.TRUE.equals(call.getBoolean("on", true)));
        call.resolve();
    }

    @PluginMethod
    public void setDirect(PluginCall call) {
        eng().setDirect(Boolean.TRUE.equals(call.getBoolean("on", false)));
        call.resolve();
    }

    @PluginMethod
    public void setNext(PluginCall call) {
        eng().setNext(slot(call), call.getString("src"));
        call.resolve();
    }

    private void stateToSession(int slot) {
        NovaSession s = session; NovaAudioEngine e = engine;
        if (s != null && e != null && slot == s.slot && !s.external) s.setState(e.playing(slot), e.heardPosition(slot), e.duration(slot));
    }

    /**
     * 3.5b3: la cola entera, desde la que suena, con lo del aviso (título, artista, disco) y los fundidos. El motor la
     * sigue solo y los botones del aviso la usan para la siguiente y la anterior.
     */
    @PluginMethod
    public void setPlaylist(PluginCall call) {
        try {
            int sl = slot(call);
            Integer ix = call.getInt("index", 0);
            JSArray arr = call.getArray("items");
            java.util.ArrayList<NovaSession.PItem> list = new java.util.ArrayList<>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                NovaSession.PItem it = new NovaSession.PItem();
                it.src = j.optString("src", "");
                if (it.src.isEmpty()) continue;
                it.title = j.optString("title", ""); it.artist = j.optString("artist", ""); it.album = j.optString("album", "");
                it.dur = j.optDouble("dur", 0); it.xf = j.optDouble("xf", 0); it.at = j.optDouble("at", 0); it.st = j.optDouble("st", 0);
                double g = j.optDouble("g", Double.NaN); it.g = Double.isNaN(g) ? Float.NaN : (float) g;
                it.style = Math.max(0, Math.min(2, j.optInt("style", 0)));
                it.swap = j.optDouble("swap", -1); it.rate = Math.max(0.85, Math.min(1.15, j.optDouble("rate", 1)));
                it.gridA = j.optDouble("gridA", 0); it.beatA = j.optDouble("beatA", 0); it.gridB = j.optDouble("gridB", 0); it.beatB = j.optDouble("beatB", 0);
                list.add(it);
            }
            int index = ix == null ? 0 : Math.max(0, Math.min(list.size() - 1, ix));
            String rp = call.getString("repeat", "off");
            int rep = "one".equals(rp) ? NovaSession.REP_ONE : "all".equals(rp) ? NovaSession.REP_ALL : NovaSession.REP_OFF;
            Integer cy = call.getInt("cycle", 0);
            NovaSession s = sess();
            s.setPlaylist(list, index, sl, rep, cy == null ? 0 : Math.max(0, cy));
            NovaAudioEngine e = eng();
            NovaSession.PItem cur = s.item(index);
            e.setNorm(sl, 1f);
            if (cur != null) e.setSongGain(sl, cur.g);
            e.setItems(sl, s.after(index));
            stateToSession(sl);
        } catch (Throwable t) {
            call.reject("No he podido poner la cola: " + t.getMessage(), "PLAYLIST");
            return;
        }
        call.resolve();
    }

    /** 3.5b3: lo que suena fuera del motor (radio, YouTube…), para el aviso. Sus botones los atiende la app. */
    @PluginMethod
    public void setNowPlaying(PluginCall call) {
        NovaSession s = sess();
        s.setExternal(call.getString("title", ""), call.getString("artist", ""), call.getString("album", ""), NovaSession.decodeDataUrl(call.getString("art", null)),
            Boolean.TRUE.equals(call.getBoolean("playing", false)), num(call, "pos", 0f), num(call, "dur", 0f));
        call.resolve();
    }

    @PluginMethod
    public void sessionEnd(PluginCall call) {
        NovaSession s = session;
        if (s != null) s.clear();
        call.resolve();
    }

    /**
     * Un botón del aviso, de los auriculares o del reloj. Si lo que suena es del motor, se hace aquí mismo (al
     * momento); si no (radio, YouTube…), se le pasa a la app.
     */
    void button(String a, double arg) {
        NovaSession s = session; NovaAudioEngine e = engine;
        JSObject o = new JSObject();
        o.put("type", "button"); o.put("action", a); o.put("t", System.currentTimeMillis());
        if (s == null || e == null || s.external || s.now() == null || e.source(s.slot) == null) {
            o.put("forward", true);
            if ("seek".equals(a)) o.put("pos", arg);
            notifyListeners("audio", o);
            return;
        }
        int sl = s.slot;
        try {
            switch (a) {
                case "play":
                    resumeOnGain = false; e.fadeSet(1f); requestFocus(); e.play(sl); keepAwake(true); state(sl, true); break;
                case "pause":
                    e.pause(sl); state(sl, false); break;
                case "playpause":
                    if (e.playing(sl)) { e.pause(sl); state(sl, false); } else { resumeOnGain = false; e.fadeSet(1f); requestFocus(); e.play(sl); keepAwake(true); state(sl, true); }
                    break;
                case "seek":
                    e.seek(sl, arg); break;
                case "next": {
                    if (s.item(s.index + 1) == null) { o.put("forward", true); break; }
                    jump(sl, s.index + 1); break;
                }
                case "prev": {
                    if (e.position(sl) > 3 || s.item(s.index - 1) == null) e.seek(sl, 0);
                    else jump(sl, s.index - 1);
                    break;
                }
                default: o.put("forward", true);
            }
        } catch (Throwable t) {
            o.put("forward", true);
        }
        notifyListeners("audio", o);
    }

    private void state(int slot, boolean playing) {
        stateToSession(slot);
        JSObject o = new JSObject();
        o.put("type", "state"); o.put("slot", slot); o.put("playing", playing);
        o.put("pos", engine.position(slot)); o.put("src", engine.source(slot));
        notifyListeners("audio", o);
    }

    /** Siguiente o anterior desde el aviso: otra canción de la cola en este mismo plato, ya. */
    private void jump(int slot, int idx) {
        NovaSession s = session; NovaAudioEngine e = engine;
        NovaSession.PItem it = s.item(idx);
        if (it == null) return;
        NovaListenLog L = listens;
        if (L != null) L.end("boton");
        int other = 1 - slot;
        if (e.playing(other)) { e.pause(other); }
        requestFocus();
        e.setNorm(slot, 1f);
        e.setSongGain(slot, it.g);
        e.load(slot, it.src, it.st, true);
        s.index = idx;
        s.extend();
        e.setItems(slot, s.after(s.index));
        keepAwake(true);
        s.onSource(slot, it.src, it.st, it.dur);
        JSObject o = new JSObject();
        o.put("type", "jump"); o.put("slot", slot); o.put("src", it.src); o.put("pos", it.st); o.put("dur", it.dur);
        notifyListeners("audio", o);
    }

    /**
     * 3.6 · Las escuchas que apuntó el motor y la app aún no tiene. «after»: la última que la app ya guardó (se borran
     * aquí las que ya tiene).
     */
    @PluginMethod
    public void takeListens(PluginCall call) {
        JSObject r = new JSObject();
        NovaListenLog L = listens;
        Object a = call.getData() != null ? call.getData().opt("after") : null;
        long after = a instanceof Number ? ((Number) a).longValue() : 0;
        if (L == null) { r.put("items", new JSArray()); r.put("seq", 0); call.resolve(r); return; }
        try {
            org.json.JSONArray got = L.take(after);
            JSArray out = new JSArray();
            for (int i = 0; i < got.length(); i++) out.put(got.opt(i));
            r.put("items", out);
            r.put("seq", L.seq());
        } catch (Throwable t) {
            call.reject("No he podido leer las escuchas: " + t.getMessage(), "LISTENS");
            return;
        }
        call.resolve(r);
    }

    /** 3.6 · El temporizador para dormir: «at» (hora del móvil, en ms; 0 lo quita) y cuánto dura el fundido final. */
    @PluginMethod
    public void setSleep(PluginCall call) {
        Object a = call.getData() != null ? call.getData().opt("at") : null;
        Object f = call.getData() != null ? call.getData().opt("fade") : null;
        long at = a instanceof Number ? ((Number) a).longValue() : 0;
        if (f instanceof Number) sleepFade = Math.max(1000L, ((Number) f).longValue());
        NovaAudioEngine e = eng();
        sleepAt = at > 0 ? at : 0;
        if (sleepAt == 0) e.setSleepGain(1f);
        call.resolve();
    }

    /** 3.6 · Los botones del widget, con la música del motor: los hace él al momento (la app puede ir dormida). */
    static boolean widgetButton(String action) {
        NovaAudioPlugin p = instance;
        if (p == null) return false;
        NovaSession s = p.session; NovaAudioEngine e = p.engine;
        if (s == null || e == null || s.external || s.now() == null || e.source(s.slot) == null) return false;
        p.button("toggle".equals(action) ? "playpause" : action, 0);
        return true;
    }

    /** 3.5b2: la cola de canciones siguientes, para que el motor siga solo con la app en segundo plano. */
    @PluginMethod
    public void setQueue(PluginCall call) {
        JSArray arr = call.getArray("srcs");
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) { String x = arr.optString(i, null); if (x != null && !x.isEmpty()) list.add(x); }
        eng().setQueue(slot(call), list);
        call.resolve();
    }

    /**
     * 3.5b2: que Android no duerma la parte web de Órbita en segundo plano. Con el motor de Android el WebView no
     * suena, y sin esto Android lo puede congelar: la canción acaba y nadie pone la siguiente.
     */
    @PluginMethod
    public void keepAlive(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        final android.webkit.WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv == null || Build.VERSION.SDK_INT < 26) { call.resolve(); return; }
        wv.post(() -> {
            try {
                wv.setRendererPriorityPolicy(on ? android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT : android.webkit.WebView.RENDERER_PRIORITY_BOUND, !on);
            } catch (Throwable ignored) {
            }
        });
        call.resolve();
    }

    @PluginMethod
    public void viz(PluginCall call) {
        eng().setViz(Boolean.TRUE.equals(call.getBoolean("on", false)));
        call.resolve();
    }

    @PluginMethod
    public void focus(PluginCall call) {
        hasFocus = false;
        requestFocus();
        JSObject r = new JSObject();
        r.put("granted", hasFocus);
        call.resolve(r);
    }

    @PluginMethod
    public void position(PluginCall call) {
        int s = slot(call);
        JSObject r = new JSObject();
        NovaAudioEngine e = engine;
        r.put("pos", e != null ? e.heardPosition(s) : 0);
        r.put("dur", e != null ? e.duration(s) : 0);
        r.put("lat", e != null ? e.latency() : 0);
        r.put("playing", e != null && e.playing(s));
        call.resolve(r);
    }

    /** Las canciones que no están en el almacenamiento del móvil (importadas o de demostración) pasan por un archivo temporal. */
    @PluginMethod
    public void writeTemp(PluginCall call) {
        final String name = String.valueOf(call.getString("name", "cancion")).replaceAll("[^A-Za-z0-9._-]", "_");
        final String data = call.getString("data", "");
        final boolean append = Boolean.TRUE.equals(call.getBoolean("append", false));
        final boolean done = Boolean.TRUE.equals(call.getBoolean("done", false));
        new Thread(() -> {
            try {
                File dir = new File(getContext().getCacheDir(), "nova-audio");
                if (!dir.exists()) dir.mkdirs();
                File part = new File(dir, name + ".part");
                FileOutputStream fos = new FileOutputStream(part, append);
                try {
                    if (data != null && data.length() > 0) fos.write(Base64.decode(data, Base64.DEFAULT));
                } finally {
                    fos.close();
                }
                JSObject r = new JSObject();
                if (done) {
                    File fin = new File(dir, name);
                    if (fin.exists()) fin.delete();
                    if (!part.renameTo(fin)) throw new Exception("no se pudo renombrar");
                    r.put("path", fin.getAbsolutePath());
                    r.put("size", fin.length());
                    File[] all = dir.listFiles((d, n) -> !n.endsWith(".part"));
                    if (all != null && all.length > 6) {
                        Arrays.sort(all, Comparator.comparingLong(File::lastModified));
                        for (int i = 0; i < all.length - 6; i++) if (!all[i].equals(fin)) all[i].delete();
                    }
                } else r.put("size", part.length());
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("No he podido preparar la canción: " + t.getMessage(), "TEMP_FAILED");
            }
        }, "orbita-temporal").start();
    }

    // ---------------------------------------------------------------- lo que cuenta el motor
    @Override
    public void onEvent(String type, int slot, String src, double pos, double dur, String msg) {
        NovaSession s = session;
        if (s != null && slot == s.slot) {
            try {
                if ("gapless".equals(type)) {
                    final String was = s.now() != null ? s.now().src : null;
                    s.onSource(slot, src, pos, dur);
                    final ScheduledExecutorService tk = ticker;
                    if (tk != null) tk.execute(() -> afterGapless(slot, src, was));
                }
                else if ("loaded".equals(type) || "seeked".equals(type)) s.setState(engine != null && engine.playing(slot), pos, dur);
                else if ("ended".equals(type) || "error".equals(type)) s.setState(false, pos, dur);
            } catch (Throwable ignored) {
            }
        }
        JSObject o = new JSObject();
        o.put("type", type);
        o.put("slot", slot);
        o.put("src", src);
        o.put("pos", pos);
        o.put("dur", dur);
        if (msg != null) o.put("msg", msg);
        notifyListeners("audio", o);
    }

    /** Fuera del hilo del sonido: otra vuelta de la lista (repetir) y, si es la misma canción otra vez, una escucha nueva. */
    private void afterGapless(int slot, String src, String was) {
        try {
            NovaSession s = session; NovaAudioEngine e = engine;
            if (s == null || e == null || slot != s.slot) return;
            if (src != null && src.equals(was)) {
                NovaListenLog L = listens;
                if (L != null) L.restart("auto");
            }
            if (s.repeat == NovaSession.REP_ONE || s.extend()) e.setItems(slot, s.after(s.index));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onSpectrum(byte[] bins) {
        JSObject o = new JSObject();
        o.put("b", Base64.encodeToString(bins, Base64.NO_WRAP));
        notifyListeners("spectrum", o);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (listens != null) listens.end("cierre");
            if (session != null) session.release();
            session = null;
            if (instance == this) instance = null;
            if (ticker != null) ticker.shutdownNow();
            later.shutdownNow();
            if (engine != null) engine.release();
            engine = null;
            keepAwake(false);
            if (noisy != null) getContext().unregisterReceiver(noisy);
            noisy = null;
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) audio().abandonAudioFocusRequest(focusReq);
            else audio().abandonAudioFocus(focusListener);
        } catch (Throwable ignored) {
        }
        super.handleOnDestroy();
    }

    // ---------------------------------------------------------------- decodificar con el propio móvil
    static final class AndroidDecoder implements NovaAudioEngine.Decoder {
        private final MediaExtractor ex = new MediaExtractor();
        private MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int sr, ch, enc = AudioFormat.ENCODING_PCM_16BIT;
        private long dur;
        private boolean inDone, outDone;
        // lo decodificado que espera (muestras intercaladas)
        private float[] pend = new float[0];
        private int pendPos, pendLen;
        private long skipUntil = -1;
        /* MP3: el codificador añade silencio al principio y al final (retardo y relleno). Se quita, para que las
           canciones de un disco vayan pegadas de verdad */
        private int skipStart = 0, holdBack = 0;

        AndroidDecoder(Context ctx, String src) throws Exception {
            // el hilo que decodifica, con prioridad de sonido: que Android no lo frene en segundo plano
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); } catch (Throwable ignored) { }
            try {
                if (src.startsWith("content://")) ex.setDataSource(ctx, Uri.parse(src), null);
                else if (src.startsWith("file://")) ex.setDataSource(Uri.parse(src).getPath());
                else ex.setDataSource(src);
                int track = -1;
                MediaFormat f = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat tf = ex.getTrackFormat(i);
                    String mime = tf.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { track = i; f = tf; break; }
                }
                if (track < 0) throw new Exception("el archivo no tiene sonido");
                ex.selectTrack(track);
                String mime = f.getString(MediaFormat.KEY_MIME);
                sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                dur = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
                if ("audio/mpeg".equals(mime)) {
                    try { if (f.containsKey("encoder-delay")) skipStart = Math.max(0, Math.min(sr, f.getInteger("encoder-delay"))); } catch (Throwable ignored) { }
                    try { if (f.containsKey("encoder-padding")) holdBack = Math.max(0, Math.min(sr, f.getInteger("encoder-padding"))); } catch (Throwable ignored) { }
                }
                codec = MediaCodec.createDecoderByType(mime);
                boolean configured = false;
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        MediaFormat ff = ex.getTrackFormat(track);
                        ff.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT);
                        codec.configure(ff, null, null, 0);
                        configured = true;
                    } catch (Throwable t) {
                        codec.release();
                        codec = MediaCodec.createDecoderByType(mime);
                    }
                }
                if (!configured) codec.configure(f, null, null, 0);
                codec.start();
                /* lee hasta el primer sonido: así se sabe la frecuencia y los canales reales (algunos AAC los cambian) */
                for (int i = 0; i < 400 && pendLen == 0 && !outDone; i++) step();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        public int sampleRate() { return sr; }
        public int channels() { return ch; }
        public long durationUs() { return dur; }

        public int read(float[] dst, int maxFrames) throws Exception {
            for (int tries = 0; tries < 50; tries++) {
                int c = Math.max(1, ch);
                // el relleno del final no se da nunca: se guarda hasta saber que no queda más
                int usable = pendLen / c - holdBack;
                if (usable > 0) {
                    int frames = Math.min(maxFrames, usable);
                    int n = frames * c;
                    System.arraycopy(pend, pendPos, dst, 0, n);
                    pendPos += n;
                    pendLen -= n;
                    return frames;
                }
                if (outDone) return -1;
                step();
            }
            return 0;
        }

        /** Un paso del decodificador: le da datos y recoge sonido. */
        private void step() throws Exception {
            if (!inDone) {
                int ii = codec.dequeueInputBuffer(4000);
                if (ii >= 0) {
                    ByteBuffer b = codec.getInputBuffer(ii);
                    int n = b == null ? -1 : ex.readSampleData(b, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int oi = codec.dequeueOutputBuffer(info, 4000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                enc = Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                return;
            }
            if (oi < 0) return;
            ByteBuffer ob = codec.getOutputBuffer(oi);
            if (ob != null && info.size > 0) {
                ob.position(info.offset);
                ob.limit(info.offset + info.size);
                ob.order(ByteOrder.LITTLE_ENDIAN);
                int c = Math.max(1, ch);
                // lo que aún esperaba se pone al principio y lo nuevo detrás
                if (pendPos > 0 && pendLen > 0) System.arraycopy(pend, pendPos, pend, 0, pendLen);
                pendPos = 0;
                int samples;
                if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                    FloatBuffer fb = ob.asFloatBuffer();
                    samples = fb.remaining();
                    ensure(pendLen + samples);
                    fb.get(pend, pendLen, samples);
                } else {
                    ShortBuffer sb = ob.asShortBuffer();
                    samples = sb.remaining();
                    ensure(pendLen + samples);
                    for (int i = 0; i < samples; i++) pend[pendLen + i] = sb.get() / 32768f;
                }
                samples -= samples % c;
                int newFrom = pendLen;
                pendLen += samples;
                int newFrames = samples / c, drop = 0;
                /* después de saltar: se tira lo que queda antes del punto exacto */
                if (skipUntil >= 0) {
                    long pts = info.presentationTimeUs;
                    if (pts < skipUntil) drop = (int) Math.min(newFrames, (skipUntil - pts) * sr / 1_000_000L);
                    if (newFrames - drop > 0) skipUntil = -1;
                } else if (skipStart > 0) {
                    drop = Math.min(newFrames, skipStart);
                    skipStart -= drop;
                }
                if (drop > 0) {
                    System.arraycopy(pend, newFrom + drop * c, pend, newFrom, (newFrames - drop) * c);
                    pendLen -= drop * c;
                }
            }
            codec.releaseOutputBuffer(oi, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
        }

        private void ensure(int n) { if (pend.length < n) pend = java.util.Arrays.copyOf(pend, Math.max(n, pend.length * 2)); }

        public void seekUs(long us) {
            ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            inDone = false;
            outDone = false;
            pendLen = 0;
            pendPos = 0;
            skipUntil = us;
            skipStart = 0;
        }

        public void close() {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            codec = null;
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- la salida de sonido
    static final class AndroidOutput implements NovaAudioEngine.Output {
        private final AudioTrack track;
        private final int sr;
        /* 4.0b3 · cuánto tarda en oírse lo que se escribe: se pregunta a Android cada 0,2 s qué trama está sonando de
           verdad (incluye el retraso del Bluetooth en los móviles que lo dicen) */
        private long written, lastQ;
        private volatile long lat;
        private final android.media.AudioTimestamp ts = new android.media.AudioTimestamp();

        AndroidOutput(int sr) {
            this.sr = sr;
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat af = new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            track = new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af)
                .setBufferSizeInBytes(Math.max(min * 2, sr / 5 * 2 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build();
            long b = sr / 5; try { b = track.getBufferSizeInFrames(); } catch (Throwable ignored) { }
            lat = b;
        }

        public int sampleRate() { return sr; }
        public void start() {
            // el hilo que da el sonido al altavoz: la prioridad más alta de sonido (sin cortes con la pantalla apagada)
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
            try { track.play(); } catch (Throwable ignored) { }
        }
        public void write(float[] st, int frames) {
            track.write(st, 0, frames * 2, AudioTrack.WRITE_BLOCKING);
            written += frames;
            long now = System.nanoTime();
            if (now - lastQ > 200_000_000L) { lastQ = now; measure(now); }
        }
        private void measure(long now) {
            long l = -1;
            try {
                if (track.getTimestamp(ts)) l = written - (ts.framePosition + Math.round((now - ts.nanoTime) * (double) sr / 1e9));
            } catch (Throwable ignored) { }
            if (l < 0 || l > 2L * sr) { try { l = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL); } catch (Throwable ignored) { l = -1; } }
            if (l < 0 || l > 2L * sr) return;
            long c = lat; lat = c <= 0 ? l : (c * 3 + l) / 4;
        }
        public long latencyFrames() { return lat; }
        public void pause() { try { track.pause(); } catch (Throwable ignored) { } }
        public void flush() { try { track.flush(); } catch (Throwable ignored) { } }
        public void close() { try { track.stop(); } catch (Throwable ignored) { } track.release(); }
    }
}
