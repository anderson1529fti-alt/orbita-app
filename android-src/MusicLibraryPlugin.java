package __PACKAGE__;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.util.Size;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import androidx.activity.result.ActivityResult;

/**
 * Lee la música guardada en el móvil (MediaStore) sin copiarla: permisos, lista de canciones y portadas.
 * También: widget de la pantalla de inicio, copias de seguridad en Descargas y lo que otras apps
 * comparten con Órbita (enlaces de YouTube, canciones). Y el reconocimiento de voz de Android para hablar con Nova.
 */
@CapacitorPlugin(
    name = "MusicLibrary",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { "android.permission.READ_EXTERNAL_STORAGE" })
    }
)
public class MusicLibraryPlugin extends Plugin {

    static MusicLibraryPlugin instance;

    @Override
    public void load() {
        instance = this;
        cleanSharedCache();
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    private String stateName() {
        PermissionState s = getPermissionState(alias());
        if (s == PermissionState.GRANTED) return "granted";
        if (s == PermissionState.DENIED) return "denied";
        return "prompt";
    }

    /** Abre el reconocedor de voz del sistema (el de Google) y devuelve lo que ha entendido. */
    @PluginMethod
    public void recognizeSpeech(PluginCall call) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        String lang = call.getString("lang", "es-ES");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, call.getString("prompt", "Dile algo a Nova"));
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        // 3.4: sin internet, con el reconocedor del propio móvil (necesita el idioma descargado para usar sin conexión)
        if (Boolean.TRUE.equals(call.getBoolean("offline", false))) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try {
            startActivityForResult(call, i, "onSpeechResult");
        } catch (Exception e) {
            call.reject("No hay reconocimiento de voz en este móvil", "NO_RECOGNIZER");
        }
    }

    @ActivityCallback
    private void onSpeechResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSArray list = new JSArray();
        Intent data = result.getData();
        if (result.getResultCode() == android.app.Activity.RESULT_OK && data != null) {
            ArrayList<String> found = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (found != null) for (String s : found) list.put(s);
        }
        JSObject ret = new JSObject();
        ret.put("matches", list);
        ret.put("code", result.getResultCode());
        call.resolve(ret);
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("state", stateName());
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("v", 2);
        call.resolve(r);
    }

    @PluginMethod
    public void request(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            status(call);
            return;
        }
        requestPermissionForAlias(alias(), call, "afterRequest");
    }

    @PermissionCallback
    private void afterRequest(PluginCall call) {
        status(call);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            call.reject("Sin permiso para leer la música", "NO_PERMISSION");
            return;
        }
        int minMs = call.getInt("minDuration", 20000);
        boolean modern = Build.VERSION.SDK_INT >= 29;
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String folderCol = modern ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
        String[] proj = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            folderCol
        };
        // El estilo (columna "genre") solo existe desde Android 11
        boolean withGenre = Build.VERSION.SDK_INT >= 30;
        if (withGenre) {
            String[] more = new String[proj.length + 1];
            System.arraycopy(proj, 0, more, 0, proj.length);
            more[proj.length] = "genre";
            proj = more;
        }
        String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        JSArray out = new JSArray();
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(base, proj, sel, null, MediaStore.Audio.Media.DATE_ADDED + " DESC");
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
                int iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                int iFolder = c.getColumnIndexOrThrow(folderCol);
                int iGenre = withGenre ? c.getColumnIndex("genre") : -1;
                while (c.moveToNext()) {
                    long dur = c.getLong(iDur);
                    if (dur > 0 && dur < minMs) continue;
                    long id = c.getLong(iId);
                    JSObject o = new JSObject();
                    o.put("id", String.valueOf(id));
                    o.put("uri", ContentUris.withAppendedId(base, id).toString());
                    o.put("title", c.getString(iTitle));
                    o.put("artist", c.getString(iArtist));
                    o.put("album", c.getString(iAlbum));
                    o.put("duration", dur / 1000.0);
                    o.put("size", c.getLong(iSize));
                    o.put("mime", c.getString(iMime));
                    o.put("name", c.getString(iName));
                    o.put("added", c.getLong(iAdded) * 1000L);
                    o.put("folder", folderName(c.getString(iFolder), modern));
                    if (iGenre >= 0) {
                        String g = c.getString(iGenre);
                        if (g != null && g.length() > 0) o.put("genre", g);
                    }
                    out.put(o);
                }
            }
        } catch (Exception e) {
            call.reject("No he podido leer la música: " + e.getMessage(), "SCAN_FAILED");
            return;
        } finally {
            if (c != null) c.close();
        }
        JSObject r = new JSObject();
        r.put("tracks", out);
        call.resolve(r);
    }

    private static String folderName(String raw, boolean relative) {
        if (raw == null) return "";
        String p = raw;
        if (!relative) {
            int slash = p.lastIndexOf('/');
            p = slash > 0 ? p.substring(0, slash) : "";
        }
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int last = p.lastIndexOf('/');
        return last >= 0 ? p.substring(last + 1) : p;
    }

    @PluginMethod
    public void artwork(PluginCall call) {
        String uriStr = call.getString("uri");
        int size = call.getInt("size", 512);
        if (uriStr == null) {
            call.reject("Falta la canción");
            return;
        }
        Uri uri = Uri.parse(uriStr);
        Bitmap bmp = null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(getContext(), uri);
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null) bmp = decodeScaled(pic, size);
        } catch (Exception ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {}
        }
        if (bmp == null && Build.VERSION.SDK_INT >= 29) {
            try {
                bmp = getContext().getContentResolver().loadThumbnail(uri, new Size(size, size), null);
            } catch (Exception ignored) {}
        }
        JSObject r = new JSObject();
        if (bmp != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, bos);
            r.put("data", "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
        }
        call.resolve(r);
    }

    private static Bitmap decodeScaled(byte[] data, int size) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /* ---------- Widget de la pantalla de inicio ---------- */

    static final String WIDGET_PREFS = "orbita_widget";

    @PluginMethod
    public void updateWidget(PluginCall call) {
        Context ctx = getContext();
        SharedPreferences.Editor ed = ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE).edit();
        ed.putString("title", call.getString("title", "Órbita"));
        ed.putString("artist", call.getString("artist", ""));
        ed.putBoolean("playing", Boolean.TRUE.equals(call.getBoolean("playing", false)));
        ed.apply();
        String art = call.getString("art", "");
        File artFile = new File(ctx.getFilesDir(), "widget_art.jpg");
        try {
            if (art != null && art.contains(",")) {
                byte[] bytes = Base64.decode(art.substring(art.indexOf(',') + 1), Base64.DEFAULT);
                FileOutputStream fos = new FileOutputStream(artFile);
                try {
                    fos.write(bytes);
                } finally {
                    fos.close();
                }
            } else if (artFile.exists()) {
                artFile.delete();
            }
        } catch (Exception ignored) {}
        JSObject r = new JSObject();
        r.put("ok", refreshWidget(ctx));
        call.resolve(r);
    }

    /** The widget class is optional (the safe build leaves it out), so it is reached by name. */
    static boolean refreshWidget(Context ctx) {
        try {
            Class<?> w = Class.forName(ctx.getPackageName() + ".OrbitaWidget");
            w.getMethod("refresh", Context.class).invoke(null, ctx);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called by the widget buttons. Returns false when the app is not open to receive it. */
    static boolean sendWidgetAction(String action) {
        MusicLibraryPlugin p = instance;
        if (p == null || p.getBridge() == null || !p.hasListeners("widget")) return false;
        JSObject d = new JSObject();
        d.put("action", action);
        p.notifyListeners("widget", d, false);
        return true;
    }

    /* ---------- Copias de seguridad (Descargas) ---------- */

    @PluginMethod
    public void saveFile(PluginCall call) {
        final String name = safeName(call.getString("name", "orbita.json"));
        final String data = call.getString("data", "");
        final String mime = call.getString("mime", "application/json");
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        try {
            JSObject r = new JSObject();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Orbita");
                Uri uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("insert");
                OutputStream os = getContext().getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("open");
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
                r.put("where", "Descargas › Orbita › " + name);
            } else {
                File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getContext().getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(bytes);
                } finally {
                    fos.close();
                }
                r.put("where", f.getAbsolutePath());
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No he podido guardar el archivo: " + e.getMessage(), "SAVE_FAILED");
        }
    }

    /** 3.5: guarda una imagen (en base64) en la galería, en Imágenes › Orbita. */
    @PluginMethod
    public void saveImage(PluginCall call) {
        final String name = safeName(call.getString("name", "orbita.png"));
        final String mime = call.getString("mime", "image/png");
        final byte[] bytes;
        try {
            bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("La imagen no es válida", "BAD_DATA");
            return;
        }
        try {
            JSObject r = new JSObject();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Orbita");
                Uri uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("insert");
                OutputStream os = getContext().getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("open");
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
                r.put("where", "Galería › Orbita");
            } else {
                File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = getContext().getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(bytes);
                } finally {
                    fos.close();
                }
                r.put("where", f.getAbsolutePath());
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No he podido guardar la imagen: " + e.getMessage(), "SAVE_FAILED");
        }
    }

    /** 3.5: comparte un archivo (en base64) con otras apps: WhatsApp, Instagram, Telegram… */
    @PluginMethod
    public void shareFile(PluginCall call) {
        final String name = safeName(call.getString("name", "orbita.png"));
        final String mime = call.getString("mime", "image/png");
        final String text = call.getString("text", "");
        final String title = call.getString("title", "Compartir");
        if (getActivity() == null) {
            call.reject("La app no está en pantalla", "NO_ACTIVITY");
            return;
        }
        try {
            byte[] bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            File dir = new File(getContext().getCacheDir(), "compartir");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File o : old) if (System.currentTimeMillis() - o.lastModified() > 3600_000L) o.delete();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(bytes);
            } finally {
                fos.close();
            }
            Uri uri;
            try {
                uri = androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            } catch (Throwable noProvider) {
                // sin FileProvider: la copia de la galería también vale para compartir
                if (Build.VERSION.SDK_INT < 29) throw new Exception("sin FileProvider");
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Orbita");
                uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("insert");
                OutputStream os = getContext().getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("open");
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            if (!text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
            send.setClipData(android.content.ClipData.newRawUri(name, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, title);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(chooser);
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No he podido compartir: " + e.getMessage(), "SHARE_FAILED");
        }
    }

    private static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.isEmpty()) s = "orbita";
        return s.length() > 80 ? s.substring(s.length() - 80) : s;
    }

    /* ---------- Lo que otras apps comparten con Órbita ---------- */

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (intent == null) return;
        if (intent.getBooleanExtra("orbita_done", false)) return;
        String wa = intent.getStringExtra("orbita_widget_action");
        if (wa != null) {
            intent.putExtra("orbita_done", true);
            JSObject d = new JSObject();
            d.put("action", wa);
            notifyListeners("widget", d, true);
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;
        intent.putExtra("orbita_done", true);
        final String type = intent.getType() == null ? "" : intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type.startsWith("text/")) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return;
            JSObject d = new JSObject();
            d.put("type", "text");
            d.put("text", text);
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (subject != null) d.put("subject", subject);
            notifyListeners("share", d, true);
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (intent.getData() != null) {
            uris.add(intent.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            JSArray files = new JSArray();
            for (Uri u : uris) {
                JSObject f = copyToCache(u, type);
                if (f != null) files.put(f);
            }
            JSObject d = new JSObject();
            d.put("type", "files");
            d.put("files", files);
            notifyListeners("share", d, true);
        }).start();
    }

    private JSObject copyToCache(Uri uri, String type) {
        String name = "cancion";
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) name = n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        try {
            File dir = new File(getContext().getCacheDir(), "shared");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, System.currentTimeMillis() + "-" + safeName(name));
            InputStream in = getContext().getContentResolver().openInputStream(uri);
            if (in == null) return null;
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                    if (total > 300L * 1024 * 1024) break;
                }
            } finally {
                fos.close();
                in.close();
            }
            JSObject f = new JSObject();
            f.put("path", out.getAbsolutePath());
            f.put("name", name);
            String mime = getContext().getContentResolver().getType(uri);
            f.put("mime", mime != null ? mime : type);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private void cleanSharedCache() {
        try {
            File dir = new File(getContext().getCacheDir(), "shared");
            File[] list = dir.listFiles();
            if (list == null) return;
            long old = System.currentTimeMillis() - 24L * 3600 * 1000;
            for (File f : list) if (f.lastModified() < old) f.delete();
        } catch (Exception ignored) {}
    }
}
