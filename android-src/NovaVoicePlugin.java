package __PACKAGE__;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
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
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * La voz propia de Nova con el motor nativo: el modelo Piper (VITS) corre con ONNX Runtime para Android,
 * con varios núcleos y fuera del WebView, así que habla antes y no le quita memoria a la app.
 * La app calcula los fonemas (espeak-ng) y este módulo los convierte en sonido: devuelve un archivo WAV.
 * Las frases ya dichas se guardan (caché de frases) para decirlas al instante la próxima vez.
 */
@CapacitorPlugin(name = "NovaVoice")
public class NovaVoicePlugin extends Plugin {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    /** Archivos que no son de la voz (el cerebro pequeño): en su propio hilo, para no hacer esperar a la voz. */
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private OrtEnvironment env;
    private OrtSession session;
    private String loadedPath;
    private boolean hasSid;
    private int threads;

    private File voiceDir() {
        File d = new File(getContext().getFilesDir(), "nova-voz");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** La carpeta de cada cosa: «voz» (por defecto) o «cerebro» (el cerebro pequeño de Nova, que no se toca al cambiar de voz). */
    private boolean isBrain(PluginCall call) {
        return "cerebro".equals(call.getString("dir"));
    }

    private File dirFor(PluginCall call) {
        if (!isBrain(call)) return voiceDir();
        File d = new File(getContext().getFilesDir(), "nova-cerebro-pequeno");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File phraseDir() {
        File d = new File(getContext().getCacheDir(), "nova-frases");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String n) {
        if (n == null || n.isEmpty()) return "x";
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores / 2));
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
        session = null;
        loadedPath = null;
    }

    @Override
    protected void handleOnDestroy() {
        try {
            exec.execute(this::closeSession);
            exec.shutdown();
            files.shutdown();
        } catch (Throwable ignored) {
        }
        super.handleOnDestroy();
    }

    /** ¿Funciona el motor nativo en este móvil? */
    @PluginMethod
    public void status(PluginCall call) {
        exec.execute(() -> {
            JSObject r = new JSObject();
            try {
                if (env == null) env = OrtEnvironment.getEnvironment();
                r.put("available", true);
            } catch (Throwable t) {
                r.put("available", false);
                r.put("error", String.valueOf(t.getMessage()));
            }
            r.put("cores", Runtime.getRuntime().availableProcessors());
            r.put("loaded", loadedPath != null);
            r.put("dirs", true);
            call.resolve(r);
        });
    }

    /** Guarda un archivo de la voz por trozos (en base64). Con done=true se da por terminado y se renombra. */
    @PluginMethod
    public void writeChunk(PluginCall call) {
        final String name = safe(call.getString("name"));
        final String data = call.getString("data", "");
        final boolean append = Boolean.TRUE.equals(call.getBoolean("append", false));
        final boolean done = Boolean.TRUE.equals(call.getBoolean("done", false));
        final File dir = dirFor(call);
        (isBrain(call) ? files : exec).execute(() -> {
            try {
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
                    if (fin.exists() && fin.getAbsolutePath().equals(loadedPath)) closeSession();
                    if (fin.exists()) fin.delete();
                    if (!part.renameTo(fin)) throw new Exception("no se pudo renombrar");
                    r.put("path", fin.getAbsolutePath());
                    r.put("size", fin.length());
                } else {
                    r.put("size", part.length());
                }
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("No he podido guardar la voz: " + t.getMessage(), "WRITE_FAILED");
            }
        });
    }

    @PluginMethod
    public void fileInfo(PluginCall call) {
        final String name = safe(call.getString("name"));
        final File dir = dirFor(call);
        (isBrain(call) ? files : exec).execute(() -> {
            File f = new File(dir, name);
            JSObject r = new JSObject();
            r.put("exists", f.exists());
            r.put("size", f.exists() ? f.length() : 0L);
            r.put("path", f.getAbsolutePath());
            call.resolve(r);
        });
    }

    /** Borra los archivos del cerebro pequeño (solo esa carpeta: nunca la voz). */
    @PluginMethod
    public void removeFiles(PluginCall call) {
        if (!isBrain(call)) {
            call.reject("Solo se puede vaciar la carpeta del cerebro", "BAD_DIR");
            return;
        }
        final File dir = dirFor(call);
        files.execute(() -> {
            int n = 0;
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) if (f.delete()) n++;
            JSObject r = new JSObject();
            r.put("deleted", n);
            call.resolve(r);
        });
    }

    /** Borra las voces descargadas (menos la que se indique en «keep») y las frases guardadas. */
    @PluginMethod
    public void clear(PluginCall call) {
        final String keep = call.getString("keep");
        final boolean voices = !Boolean.FALSE.equals(call.getBoolean("voices", true));
        exec.execute(() -> {
            int n = 0;
            if (voices) {
                File[] fs = voiceDir().listFiles();
                if (fs != null) for (File f : fs) {
                    if (keep != null && f.getName().equals(safe(keep))) continue;
                    if (f.getAbsolutePath().equals(loadedPath)) closeSession();
                    if (f.delete()) n++;
                }
            }
            File[] ps = phraseDir().listFiles();
            if (ps != null) for (File f : ps) if (f.delete()) n++;
            JSObject r = new JSObject();
            r.put("deleted", n);
            call.resolve(r);
        });
    }

    /** Carga una voz (archivo .onnx ya guardado) en ONNX Runtime. */
    @PluginMethod
    public void load(PluginCall call) {
        final String name = safe(call.getString("name"));
        final Integer want = call.getInt("threads", 0);
        exec.execute(() -> {
            try {
                File f = new File(voiceDir(), name);
                if (!f.exists()) throw new Exception("falta el archivo de la voz");
                JSObject r = new JSObject();
                if (session == null || !f.getAbsolutePath().equals(loadedPath)) {
                    closeSession();
                    if (env == null) env = OrtEnvironment.getEnvironment();
                    threads = want != null && want > 0 ? Math.min(8, want) : defaultThreads();
                    OrtSession.SessionOptions o = new OrtSession.SessionOptions();
                    o.setIntraOpNumThreads(threads);
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    // frases de distinta longitud: sin estas dos, la memoria crece con cada frase
                    o.setMemoryPatternOptimization(false);
                    o.setCPUArenaAllocator(false);
                    long t0 = System.currentTimeMillis();
                    session = env.createSession(f.getAbsolutePath(), o);
                    loadedPath = f.getAbsolutePath();
                    hasSid = session.getInputNames().contains("sid");
                    r.put("ms", System.currentTimeMillis() - t0);
                }
                r.put("threads", threads);
                r.put("sid", hasSid);
                call.resolve(r);
            } catch (Throwable t) {
                closeSession();
                call.reject("No he podido cargar la voz: " + t.getMessage(), "LOAD_FAILED");
            }
        });
    }

    @PluginMethod
    public void unload(PluginCall call) {
        exec.execute(() -> {
            closeSession();
            call.resolve();
        });
    }

    /** Fonemas → sonido. Devuelve la ruta de un WAV (o la de la frase guardada, si ya se dijo antes). */
    @PluginMethod
    public void synth(PluginCall call) {
        final JSArray ids = call.getArray("ids");
        final String key = call.getString("key");
        final double noise = call.getDouble("noise", 0.667);
        final double length = call.getDouble("length", 1.0);
        final double noiseW = call.getDouble("noiseW", 0.8);
        final int sid = call.getInt("sid", 0);
        final int sr = call.getInt("sampleRate", 22050);
        exec.execute(() -> {
            OnnxTensor tIn = null, tLen = null, tScales = null, tSid = null;
            OrtSession.Result res = null;
            try {
                File out = new File(phraseDir(), (key != null ? safe(key) : "tmp-" + System.nanoTime()) + ".wav");
                if (key != null && out.exists() && out.length() > 44) {
                    out.setLastModified(System.currentTimeMillis());
                    JSObject r = new JSObject();
                    r.put("path", out.getAbsolutePath());
                    r.put("cached", true);
                    r.put("samples", (out.length() - 44) / 2);
                    call.resolve(r);
                    return;
                }
                if (session == null) throw new Exception("la voz no está cargada");
                if (ids == null || ids.length() == 0) throw new Exception("sin fonemas");
                int n = ids.length();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = ids.getLong(i);
                tIn = OnnxTensor.createTensor(env, LongBuffer.wrap(a), new long[] { 1, n });
                tLen = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] { n }), new long[] { 1 });
                tScales = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[] { (float) noise, (float) length, (float) noiseW }), new long[] { 3 });
                Map<String, OnnxTensor> in = new HashMap<>();
                in.put("input", tIn);
                in.put("input_lengths", tLen);
                in.put("scales", tScales);
                if (hasSid) {
                    tSid = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] { sid }), new long[] { 1 });
                    in.put("sid", tSid);
                }
                long t0 = System.currentTimeMillis();
                res = session.run(in);
                OnnxValue v = res.get(0);
                FloatBuffer fb = ((OnnxTensor) v).getFloatBuffer();
                if (fb == null) throw new Exception("salida vacía");
                int samples = fb.remaining();
                long ms = System.currentTimeMillis() - t0;
                writeWav(out, fb, samples, sr);
                JSObject r = new JSObject();
                r.put("path", out.getAbsolutePath());
                r.put("ms", ms);
                r.put("samples", samples);
                call.resolve(r);
                trimPhrases();
            } catch (Throwable t) {
                call.reject("No he podido hablar: " + t.getMessage(), "SYNTH_FAILED");
            } finally {
                closeQuietly(tIn);
                closeQuietly(tLen);
                closeQuietly(tScales);
                closeQuietly(tSid);
                if (res != null) {
                    try {
                        res.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static void closeQuietly(OnnxTensor t) {
        if (t == null) return;
        try {
            t.close();
        } catch (Throwable ignored) {
        }
    }

    private static void writeWav(File out, FloatBuffer pcm, int n, int sr) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] { 'R', 'I', 'F', 'F' }).putInt(36 + n * 2).put(new byte[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' });
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[] { 'd', 'a', 't', 'a' }).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            float s = pcm.get();
            if (s > 1f) s = 1f;
            else if (s < -1f) s = -1f;
            b.putShort((short) (s * 32767f));
        }
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(b.array());
        } finally {
            fos.close();
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new Exception("no se pudo guardar el audio");
    }

    /** Las frases guardadas ocupan como mucho unos 60 MB: se borran las que llevan más tiempo sin decirse. */
    private void trimPhrases() {
        File[] fs = phraseDir().listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis(), total = 0;
        for (File f : fs) {
            if (f.getName().startsWith("tmp-") && now - f.lastModified() > 10 * 60 * 1000L) f.delete();
            else total += f.length();
        }
        fs = phraseDir().listFiles();
        if (fs == null || (fs.length <= 400 && total <= 60L * 1024 * 1024)) return;
        Arrays.sort(fs, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        int left = fs.length;
        for (File f : fs) {
            if (left <= 300 && total <= 45L * 1024 * 1024) break;
            if (f.getName().startsWith("tmp-") && now - f.lastModified() < 10 * 60 * 1000L) continue;
            total -= f.length();
            if (f.delete()) left--;
        }
    }
}
