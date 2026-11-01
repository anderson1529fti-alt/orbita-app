package __PACKAGE__;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import orbita.nova.NovaLlm;

/**
 * El cerebro grande de Nova: un modelo de lenguaje (Qwen3, formato GGUF) que corre en el móvil con llama.cpp.
 * Descarga el modelo (con reanudación si se corta), lo carga y completa textos. Sin internet una vez descargado.
 */
@CapacitorPlugin(name = "NovaBrain")
public class NovaBrainPlugin extends Plugin {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final AtomicBoolean dlCancel = new AtomicBoolean(false);
    private volatile boolean downloading;
    private volatile long handle;
    private String loadedName;

    private File dir() {
        File d = new File(getContext().getFilesDir(), "nova-cerebro");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String n) {
        if (n == null || n.isEmpty()) return "modelo.gguf";
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Las instrucciones del procesador: el cerebro está compilado para ARMv8.2 con dotprod y fp16. */
    private static String cpuFeatures() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Features")) return line.substring(line.indexOf(':') + 1).trim();
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean cpuOk() {
        String f = " " + cpuFeatures() + " ";
        boolean arm64 = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]);
        return arm64 && f.contains(" asimddp ") && (f.contains(" asimdhp ") || f.contains(" fphp "));
    }

    private long ramMb() {
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024L * 1024L);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    protected void handleOnDestroy() {
        dlCancel.set(true);
        try {
            if (handle != 0) NovaLlm.nCancel(handle);
            exec.execute(this::freeModel);
            exec.shutdown();
            net.shutdown();
        } catch (Throwable ignored) {
        }
        super.handleOnDestroy();
    }

    private void freeModel() {
        if (handle != 0) {
            try {
                NovaLlm.nFree(handle);
            } catch (Throwable ignored) {
            }
        }
        handle = 0;
        loadedName = null;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        boolean lib = false;
        String libError = "";
        try {
            lib = NovaLlm.available();
            libError = NovaLlm.loadError();
        } catch (Throwable t) {
            libError = String.valueOf(t.getMessage());
        }
        r.put("lib", lib);
        r.put("libError", libError);
        r.put("cpuOk", cpuOk());
        r.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        r.put("ramMb", ramMb());
        r.put("cores", Runtime.getRuntime().availableProcessors());
        r.put("loaded", handle != 0);
        r.put("downloading", downloading);
        r.put("freeMb", dir().getUsableSpace() / (1024L * 1024L));
        call.resolve(r);
    }

    @PluginMethod
    public void fileInfo(PluginCall call) {
        String name = safe(call.getString("name"));
        File f = new File(dir(), name), part = new File(dir(), name + ".part");
        JSObject r = new JSObject();
        r.put("exists", f.exists());
        r.put("size", f.exists() ? f.length() : 0L);
        r.put("partSize", part.exists() ? part.length() : 0L);
        call.resolve(r);
    }

    /** Descarga en segundo plano. El progreso llega con el evento «descarga»: {state, done, total, error}. */
    @PluginMethod
    public void download(PluginCall call) {
        final String url = call.getString("url");
        final String name = safe(call.getString("name"));
        final Object sizeObj = call.getData().opt("size");   // a number: Integer or Long depending on how big it is
        final long size = sizeObj instanceof Number ? ((Number) sizeObj).longValue() : 0L;
        final String sha = call.getString("sha256");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Dirección no válida", "BAD_URL");
            return;
        }
        if (downloading) {
            call.reject("Ya hay una descarga en marcha", "BUSY");
            return;
        }
        downloading = true;
        dlCancel.set(false);
        JSObject ok = new JSObject();
        ok.put("started", true);
        call.resolve(ok);
        net.execute(() -> {
            JSObject end = new JSObject();
            try {
                long got = doDownload(url, name, size, sha);
                end.put("state", "ok");
                end.put("size", got);
            } catch (InterruptedException e) {
                end.put("state", "cancelled");
            } catch (Throwable t) {
                end.put("state", "error");
                end.put("error", String.valueOf(t.getMessage()));
            } finally {
                downloading = false;
            }
            notifyListeners("descarga", end);
        });
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        dlCancel.set(true);
        call.resolve();
    }

    private HttpURLConnection open(String url, long from) throws Exception {
        String u = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Orbita");
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirección sin destino");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            return c;
        }
        throw new Exception("demasiadas redirecciones");
    }

    private long doDownload(String url, String name, long size, String sha) throws Exception {
        File part = new File(dir(), name + ".part"), fin = new File(dir(), name);
        long have = part.exists() ? part.length() : 0L;
        if (size > 0 && have > size) {
            part.delete();
            have = 0;
        }
        MessageDigest md = sha != null && sha.length() == 64 ? MessageDigest.getInstance("SHA-256") : null;
        byte[] buf = new byte[1 << 16];
        if (md != null && have > 0) {
            InputStream in = new FileInputStream(part);
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (dlCancel.get()) throw new InterruptedException();
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
        }
        long done = have;
        if (size <= 0 || have < size) {
            HttpURLConnection c = open(url, have);
            int code = c.getResponseCode();
            if (code == 200 && have > 0) {
                have = 0;
                done = 0;
                if (md != null) md.reset();
            } else if (code == 416 && size > 0 && have == size) {
                c.disconnect();
            } else if (code != 200 && code != 206) {
                c.disconnect();
                throw new Exception("el servidor respondió " + code);
            }
            if (code == 200 || code == 206) {
                long total = size > 0 ? size : have + Math.max(0, c.getContentLengthLong());
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(part, have > 0);
                long last = 0;
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (dlCancel.get()) throw new InterruptedException();
                        out.write(buf, 0, n);
                        if (md != null) md.update(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 500) {
                            last = now;
                            JSObject p = new JSObject();
                            p.put("state", "progress");
                            p.put("done", done);
                            p.put("total", total);
                            notifyListeners("descarga", p);
                        }
                    }
                } finally {
                    out.close();
                    in.close();
                    c.disconnect();
                }
            }
        }
        if (size > 0 && done != size) throw new Exception("la descarga se ha cortado");
        if (md != null) {
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!hex.toString().equalsIgnoreCase(sha)) {
                part.delete();
                throw new Exception("el archivo descargado no es correcto");
            }
        }
        if (fin.exists()) fin.delete();
        if (!part.renameTo(fin)) throw new Exception("no se pudo guardar");
        return fin.length();
    }

    @PluginMethod
    public void remove(PluginCall call) {
        final String name = safe(call.getString("name"));
        exec.execute(() -> {
            if (name.equals(loadedName)) freeModel();
            new File(dir(), name).delete();
            new File(dir(), name + ".part").delete();
            call.resolve();
        });
    }

    @PluginMethod
    public void load(PluginCall call) {
        final String name = safe(call.getString("name"));
        final int nCtx = call.getInt("nCtx", 1536);
        final int threads = call.getInt("threads", 0);
        exec.execute(() -> {
            try {
                if (!NovaLlm.available()) throw new Exception("el motor no está en esta versión: " + NovaLlm.loadError());
                if (!cpuOk()) throw new Exception("este móvil no es compatible");
                File f = new File(dir(), name);
                if (!f.exists()) throw new Exception("falta el modelo");
                JSObject r = new JSObject();
                if (handle == 0 || !name.equals(loadedName)) {
                    freeModel();
                    int cores = Runtime.getRuntime().availableProcessors();
                    int th = threads > 0 ? threads : Math.max(2, Math.min(4, cores / 2));
                    long t0 = System.currentTimeMillis();
                    long h = NovaLlm.nLoad(f.getAbsolutePath(), nCtx, th);
                    if (h == 0) throw new Exception(NovaLlm.nLastError());
                    handle = h;
                    loadedName = name;
                    r.put("ms", System.currentTimeMillis() - t0);
                    r.put("threads", th);
                }
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("No he podido cargar el cerebro grande: " + t.getMessage(), "LOAD_FAILED");
            }
        });
    }

    @PluginMethod
    public void complete(PluginCall call) {
        final String prompt = call.getString("prompt", "");
        final String grammar = call.getString("grammar");
        final int maxTokens = call.getInt("maxTokens", 96);
        final double temp = call.getDouble("temperature", 0.5);
        final double topP = call.getDouble("topP", 0.9);
        final int seed = call.getInt("seed", 0);
        exec.execute(() -> {
            try {
                if (handle == 0) throw new Exception("el cerebro grande no está cargado");
                long t0 = System.currentTimeMillis();
                byte[] out = NovaLlm.nComplete(handle, prompt.getBytes(StandardCharsets.UTF_8),
                    grammar == null ? null : grammar.getBytes(StandardCharsets.UTF_8), maxTokens, (float) temp, (float) topP, seed);
                if (out == null) throw new Exception(NovaLlm.nLastError());
                JSObject r = new JSObject();
                r.put("text", new String(out, StandardCharsets.UTF_8));
                r.put("info", NovaLlm.nInfo(handle));
                r.put("ms", System.currentTimeMillis() - t0);
                call.resolve(r);
            } catch (Throwable t) {
                call.reject("El cerebro grande no ha podido responder: " + t.getMessage(), "COMPLETE_FAILED");
            }
        });
    }

    /** Corta lo que esté pensando (se llama fuera de la cola, para que actúe al momento). */
    @PluginMethod
    public void cancel(PluginCall call) {
        long h = handle;
        if (h != 0) {
            try {
                NovaLlm.nCancel(h);
            } catch (Throwable ignored) {
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void unload(PluginCall call) {
        exec.execute(() -> {
            freeModel();
            call.resolve();
        });
    }
}
