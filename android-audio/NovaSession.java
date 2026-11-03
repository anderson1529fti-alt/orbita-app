package __PACKAGE__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El aviso de reproducción del motor de Android (3.5 beta 3): título, portada, barra y botones. Los botones los
 * atiende el propio motor al momento, sin esperar a la parte web de la app (que en segundo plano puede ir dormida).
 * Guarda la cola que le manda la app para saber cuál es la siguiente y la anterior.
 */
final class NovaSession {
    static final String CHANNEL = "orbita_reproduccion";
    static final int NOTIF_ID = 7401;
    static volatile NovaSession current;

    /** Una canción de la cola, con lo que hace falta para el aviso y para el fundido. */
    static final class PItem {
        String src, title = "", artist = "", album = "";
        double dur, xf, at, st, swap = -1, rate = 1, gridA, beatA, gridB, beatB;
        int style;
        float g = Float.NaN;
        NovaAudioEngine.Item engineItem() {
            NovaAudioEngine.Item it = new NovaAudioEngine.Item(src, xf, at, st, g);
            it.style = style; it.swap = swap; it.rate = rate; it.gridA = gridA; it.beatA = beatA; it.gridB = gridB; it.beatB = beatB;
            return it;
        }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private MediaSession ms;
    final List<PItem> items = new ArrayList<>();
    int index = -1, slot = 0;
    // 3.6: repetir (0 no, 1 esta, 2 toda la lista) y cuántas canciones forman una vuelta de la lista (0: no se sabe)
    static final int REP_OFF = 0, REP_ONE = 1, REP_ALL = 2;
    int repeat = REP_OFF, cycle = 0;
    boolean playing;
    double pos, dur;
    // lo que suena fuera del motor (radio, YouTube…): lo cuenta la app
    boolean external;
    String exTitle = "", exArtist = "", exAlbum = "";
    Bitmap exArt;
    private Bitmap art;
    private String artFor;
    private long posAt;
    private boolean serviceWanted;
    private final Runnable stopLater = this::stopService;

    NovaSession(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        current = this;
        main.post(this::create);
    }

    private void create() {
        try {
            ms = new MediaSession(ctx, "Orbita");
            ms.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { button("play", 0); }
                @Override public void onPause() { button("pause", 0); }
                @Override public void onStop() { button("pause", 0); }
                @Override public void onSkipToNext() { button("next", 0); }
                @Override public void onSkipToPrevious() { button("prev", 0); }
                @Override public void onSeekTo(long ms) { button("seek", ms / 1000.0); }
            }, main);
            Intent open = launch(ctx);
            if (open != null) ms.setSessionActivity(PendingIntent.getActivity(ctx, 7402, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (Throwable t) {
            ms = null;
        }
        // lo que llegó antes de estar listo
        synchronized (this) { if (index >= 0 || external) refresh(true); }
    }

    private static void button(String a, double arg) {
        NovaAudioPlugin p = NovaAudioPlugin.instance;
        if (p != null) p.button(a, arg);
    }

    synchronized PItem item(int i) { return i >= 0 && i < items.size() ? items.get(i) : null; }
    synchronized PItem now() { return external ? null : item(index); }

    /** La cola desde la canción que suena (las siguientes, para el motor). */
    synchronized List<NovaAudioEngine.Item> after(int i) {
        List<NovaAudioEngine.Item> out = new ArrayList<>();
        if (repeat == REP_ONE) {
            // repetir esta: la misma otra vez, pegada y desde el principio
            PItem it = item(i);
            if (it != null) out.add(new NovaAudioEngine.Item(it.src, 0, 0, 0, it.g));
            return out;
        }
        for (int k = i + 1; k < items.size(); k++) out.add(items.get(k).engineItem());
        return out;
    }

    /**
     * Repetir toda la lista: cuando quedan pocas, otra vuelta al final (la app manda la lista entera desde la que
     * suena). Así sigue sonando aunque la app no despierte. Devuelve si ha cambiado.
     */
    synchronized boolean extend() {
        if (repeat != REP_ALL || cycle <= 0 || cycle > items.size() || index < items.size() - 3) return false;
        List<PItem> again = new ArrayList<>(items.subList(items.size() - cycle, items.size()));
        items.addAll(again);
        // las de hace mucho ya no hacen falta (solo unas pocas, para «anterior»)
        if (index > 60) { int cut = index - 3; items.subList(0, cut).clear(); index -= cut; }
        return true;
    }

    synchronized void setPlaylist(List<PItem> list, int idx, int slot, int repeat, int cycle) {
        items.clear(); items.addAll(list);
        this.repeat = repeat; this.cycle = cycle;
        index = idx; this.slot = slot; external = false;
        PItem it = item(idx);
        if (it != null && it.dur > 0) dur = it.dur;
        refresh(true);
    }

    synchronized void setExternal(String title, String artist, String album, Bitmap a, boolean playing, double pos, double dur) {
        external = true; exTitle = title; exArtist = artist; exAlbum = album; if (a != null) exArt = a;
        this.playing = playing; this.pos = pos; this.dur = dur; posAt = System.currentTimeMillis();
        refresh(true);
    }

    /** El motor ha pasado solo a otra canción de la cola (o un botón lo ha hecho): se busca en la cola. */
    synchronized void onSource(int slot, String src, double pos, double dur) {
        if (slot != this.slot || src == null) return;
        int found = -1;
        PItem here = item(index);
        if (repeat == REP_ONE && here != null && src.equals(here.src)) found = index;
        for (int k = Math.max(0, index + 1); k < items.size() && found < 0; k++) if (src.equals(items.get(k).src)) found = k;
        for (int k = 0; k < items.size() && found < 0; k++) if (src.equals(items.get(k).src)) found = k;
        if (found >= 0) index = found;
        external = false;
        this.pos = pos; if (dur > 0) this.dur = dur; posAt = System.currentTimeMillis();
        refresh(true);
    }

    synchronized void setState(boolean playing, double pos, double dur) {
        this.playing = playing; this.pos = pos; if (dur > 0) this.dur = dur; posAt = System.currentTimeMillis();
        refresh(false);
    }

    synchronized void clear() {
        items.clear(); index = -1; external = false; playing = false;
        main.post(() -> {
            try { if (ms != null) ms.setActive(false); } catch (Throwable ignored) { }
            stopService();
        });
    }

    void release() {
        clear();
        main.post(() -> { try { if (ms != null) ms.release(); } catch (Throwable ignored) { } ms = null; });
        bg.shutdownNow();
        if (current == this) current = null;
    }

    // ---------------------------------------------------------------- lo que se ve
    private String title() { PItem it = now(); return external ? exTitle : it != null ? it.title : ""; }
    private String artist() { PItem it = now(); return external ? exArtist : it != null ? it.artist : ""; }
    private String album() { PItem it = now(); return external ? exAlbum : it != null ? it.album : ""; }

    private void refresh(boolean meta) {
        final boolean pl = playing;
        final double p = pos, d = dur;
        final String t = title(), ar = artist(), al = album();
        final PItem it = now();
        if (meta && it != null && (artFor == null || !artFor.equals(it.src))) {
            final String src = it.src;
            artFor = src; art = null;
            bg.execute(() -> {
                Bitmap b = loadArt(ctx, src);
                artTried.add(src);
                if (artTried.size() > 200) artTried.clear();
                synchronized (NovaSession.this) { if (src.equals(artFor)) art = b; }
                main.post(() -> publish(pl, p, d, t, ar, al, true));
            });
        }
        main.post(() -> publish(pl, p, d, t, ar, al, meta));
    }

    private Bitmap currentArt() { synchronized (this) { return external ? exArt : art; } }

    private void publish(boolean pl, double p, double d, String t, String ar, String al, boolean meta) {
        if (ms == null) return;
        try {
            if (meta) {
                MediaMetadata.Builder mb = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, ar)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, al)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (Math.max(0, d) * 1000));
                Bitmap a = currentArt();
                if (a != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, a);
                ms.setMetadata(mb.build());
            }
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
            ms.setPlaybackState(new PlaybackState.Builder().setActions(acts)
                .setState(pl ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, (long) (Math.max(0, p) * 1000), pl ? 1f : 0f).build());
            if (!ms.isActive()) ms.setActive(true);
            boolean has = !t.isEmpty();
            if (has && pl) startService();
            if (has) notifyNow();
            main.removeCallbacks(stopLater);
            // en pausa, el aviso se queda un rato; luego se puede quitar
            if (!pl) main.postDelayed(stopLater, 10 * 60_000L);
        } catch (Throwable ignored) {
        }
        widget(t, ar, pl);
    }

    // ---------------------------------------------------------------- el widget (3.6)
    private String wSig, wArtFor;
    /**
     * El widget de la pantalla de inicio cambia con la canción aunque la parte web de la app esté dormida. Lo de la
     * radio o YouTube lo sigue poniendo la app (está despierta mientras suena).
     */
    private void widget(String t, String ar, boolean pl) {
        final PItem it;
        final Bitmap a;
        synchronized (this) {
            if (external) return;
            it = now();
            if (it == null || t.isEmpty()) return;
            a = it.src.equals(artFor) ? art : null;
        }
        final String src = it.src;
        // la portada se escribe cuando ya está cargada (o se sabe que no tiene)
        final boolean artReady = src.equals(artFor) && (a != null || artTried.contains(src));
        final String sig = t + "\n" + ar + "\n" + pl + "\n" + (artReady ? src : "");
        if (sig.equals(wSig)) return;
        wSig = sig;
        bg.execute(() -> {
            try {
                android.content.SharedPreferences.Editor ed = ctx.getSharedPreferences("orbita_widget", Context.MODE_PRIVATE).edit();
                ed.putString("title", t); ed.putString("artist", ar); ed.putBoolean("playing", pl);
                ed.apply();
                if (artReady && !src.equals(wArtFor)) {
                    wArtFor = src;
                    File f = new File(ctx.getFilesDir(), "widget_art.jpg");
                    Bitmap r = a != null ? rounded(a, 144, 22) : null;
                    if (r != null) { FileOutputStream fos = new FileOutputStream(f); try { r.compress(Bitmap.CompressFormat.PNG, 100, fos); } finally { fos.close(); } }
                    else if (f.exists()) f.delete();
                }
                Class.forName(ctx.getPackageName() + ".OrbitaWidget").getMethod("refresh", Context.class).invoke(null, ctx);
            } catch (Throwable ignored) {
                // sin widget (la versión segura de la app no lo tiene)
            }
        });
    }
    private final java.util.Set<String> artTried = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** La portada como la pone la app en el widget: cuadrada, recortada al centro, con las esquinas redondeadas. */
    static Bitmap rounded(Bitmap src, int size, float radius) {
        try {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = Math.max(size / (float) src.getWidth(), size / (float) src.getHeight());
            Matrix m = new Matrix();
            m.setScale(sc, sc);
            m.postTranslate((size - src.getWidth() * sc) / 2f, (size - src.getHeight() * sc) / 2f);
            sh.setLocalMatrix(m);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setShader(sh);
            c.drawRoundRect(new RectF(0, 0, size, size), radius, radius, p);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void startService() {
        if (serviceWanted && NovaMediaService.running != null) return;
        serviceWanted = true;
        try {
            Intent i = new Intent(ctx, NovaMediaService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Throwable t) {
            // Android no deja empezar el servicio ahora (la app está en segundo plano): el aviso sale igual
            serviceWanted = false;
        }
    }

    private void stopService() {
        serviceWanted = false;
        try { ctx.stopService(new Intent(ctx, NovaMediaService.class)); } catch (Throwable ignored) { }
        if (!playing) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            try { if (nm != null) nm.cancel(NOTIF_ID); } catch (Throwable ignored) { }
        }
    }

    private void notifyNow() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NOTIF_ID, build()); } catch (Throwable ignored) { }
    }

    /** El aviso con estilo de reproductor (el que sale arriba y en la pantalla de bloqueo). */
    Notification build() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("La canción que suena y sus botones");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        boolean pl;
        synchronized (this) { pl = playing; }
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title())
            .setContentText(artist())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(pl)
            .setOnlyAlertOnce(true);
        Bitmap a = currentArt();
        if (a != null) b.setLargeIcon(a);
        Intent open = launch(ctx);
        if (open != null) b.setContentIntent(PendingIntent.getActivity(ctx, 7402, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        b.addAction(action(android.R.drawable.ic_media_previous, "Anterior", "prev"));
        b.addAction(pl ? action(android.R.drawable.ic_media_pause, "Pausa", "pause") : action(android.R.drawable.ic_media_play, "Seguir", "play"));
        b.addAction(action(android.R.drawable.ic_media_next, "Siguiente", "next"));
        Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (ms != null) st.setMediaSession(ms.getSessionToken());
        b.setStyle(st);
        return b.build();
    }

    private Notification.Action action(int icon, String label, String a) {
        Intent i = new Intent(ctx, NovaMediaService.class).setAction(NovaMediaService.PREFIX + a);
        PendingIntent pi = PendingIntent.getService(ctx, a.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, pi).build();
    }

    /** Abrir Órbita al tocar el aviso. */
    static Intent launch(Context c) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return i;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- portadas
    static Bitmap loadArt(Context ctx, String src) {
        try {
            Uri u = Uri.parse(src);
            if (Build.VERSION.SDK_INT >= 29 && "content".equals(u.getScheme())) {
                try { return ctx.getContentResolver().loadThumbnail(u, new Size(512, 512), null); } catch (Throwable ignored) { }
            }
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (src.startsWith("content:")) r.setDataSource(ctx, u);
                else r.setDataSource(src.startsWith("file://") ? u.getPath() : src);
                byte[] pic = r.getEmbeddedPicture();
                if (pic == null) return null;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            } finally {
                try { r.release(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static Bitmap decodeDataUrl(String u) {
        try {
            if (u == null) return null;
            int i = u.indexOf(";base64,");
            if (i < 0) return null;
            byte[] b = Base64.decode(u.substring(i + 8), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Throwable t) {
            return null;
        }
    }
}
