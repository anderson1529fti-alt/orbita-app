package __PACKAGE__;

import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;

/**
 * Lee la música guardada en el móvil (MediaStore) sin copiarla:
 * permisos, lista de canciones y portadas.
 */
@CapacitorPlugin(
    name = "MusicLibrary",
    permissions = {
        @Permission(alias = "audio", strings = { "android.permission.READ_MEDIA_AUDIO" }),
        @Permission(alias = "storage", strings = { "android.permission.READ_EXTERNAL_STORAGE" })
    }
)
public class MusicLibraryPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    private String stateName() {
        PermissionState s = getPermissionState(alias());
        if (s == PermissionState.GRANTED) return "granted";
        if (s == PermissionState.DENIED) return "denied";
        return "prompt";
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("state", stateName());
        r.put("sdk", Build.VERSION.SDK_INT);
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
}
