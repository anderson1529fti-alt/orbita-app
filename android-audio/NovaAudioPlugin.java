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

    private NovaAudioEngine engine;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean hasFocus;
    private PowerManager.WakeLock wake;
    private long wakeAt;
    private BroadcastReceiver noisy;
    private ScheduledExecutorService ticker;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) hasFocus = true;
        else if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false;
        JSObject o = new JSObject();
        o.put("type", "focus");
        o.put("value", change);
        notifyListeners("audio", o);
    };

    private int outputRate() {
        try {
            String r = audio().getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            int v = r != null ? Integer.parseInt(r) : 48000;
            return v >= 8000 && v <= 192000 ? v : 48000;
        } catch (Throwable t) {
            return 48000;
        }
    }

    private AudioManager audio() {
        if (am == null) am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        return am;
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
                o.put("pos", e.position(i));
                o.put("dur", e.duration(i));
                notifyListeners("audio", o);
            }
            keepAwake(any);
        } catch (Throwable ignored) {
        }
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
                    JSObject o = new JSObject();
                    o.put("type", "noisy");
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
        r.put("v", 1);
        call.resolve(r);
    }

    @PluginMethod
    public void load(PluginCall call) {
        String src = call.getString("src");
        if (src == null || src.isEmpty()) { call.reject("sin canción", "NO_SRC"); return; }
        boolean play = Boolean.TRUE.equals(call.getBoolean("play", false));
        if (play) requestFocus();
        eng().load(slot(call), src, num(call, "at", 0f), play);
        call.resolve();
    }

    @PluginMethod
    public void play(PluginCall call) {
        requestFocus();
        eng().play(slot(call));
        keepAwake(true);
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        eng().pause(slot(call));
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
        r.put("pos", e != null ? e.position(s) : 0);
        r.put("dur", e != null ? e.duration(s) : 0);
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
        JSObject o = new JSObject();
        o.put("type", type);
        o.put("slot", slot);
        o.put("src", src);
        o.put("pos", pos);
        o.put("dur", dur);
        if (msg != null) o.put("msg", msg);
        notifyListeners("audio", o);
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
            if (ticker != null) ticker.shutdownNow();
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
        private float[] pend = new float[0];
        private int pendPos, pendLen;
        private long skipUntil = -1;

        AndroidDecoder(Context ctx, String src) throws Exception {
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
                if (pendLen > 0) {
                    int frames = Math.min(maxFrames, pendLen / Math.max(1, ch));
                    int n = frames * ch;
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
                int samples;
                if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                    FloatBuffer fb = ob.asFloatBuffer();
                    samples = fb.remaining();
                    ensure(samples);
                    fb.get(pend, 0, samples);
                } else {
                    ShortBuffer sb = ob.asShortBuffer();
                    samples = sb.remaining();
                    ensure(samples);
                    for (int i = 0; i < samples; i++) pend[i] = sb.get() / 32768f;
                }
                pendPos = 0;
                pendLen = samples - samples % Math.max(1, ch);
                /* después de saltar: se tira lo que queda antes del punto exacto */
                if (skipUntil >= 0) {
                    long pts = info.presentationTimeUs;
                    if (pts < skipUntil) {
                        int dropFrames = (int) Math.min(pendLen / Math.max(1, ch), (skipUntil - pts) * sr / 1_000_000L);
                        pendPos = dropFrames * ch;
                        pendLen -= dropFrames * ch;
                    }
                    if (pendLen > 0) skipUntil = -1;
                }
            }
            codec.releaseOutputBuffer(oi, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
        }

        private void ensure(int n) { if (pend.length < n) pend = new float[n]; }

        public void seekUs(long us) {
            ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            inDone = false;
            outDone = false;
            pendLen = 0;
            pendPos = 0;
            skipUntil = us;
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

        AndroidOutput(int sr) {
            this.sr = sr;
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat af = new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            track = new AudioTrack.Builder().setAudioAttributes(aa).setAudioFormat(af)
                .setBufferSizeInBytes(Math.max(min * 2, sr / 8 * 2 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build();
        }

        public int sampleRate() { return sr; }
        public void start() { try { track.play(); } catch (Throwable ignored) { } }
        public void write(float[] st, int frames) { track.write(st, 0, frames * 2, AudioTrack.WRITE_BLOCKING); }
        public void pause() { try { track.pause(); } catch (Throwable ignored) { } }
        public void flush() { try { track.flush(); } catch (Throwable ignored) { } }
        public void close() { try { track.stop(); } catch (Throwable ignored) { } track.release(); }
    }
}
