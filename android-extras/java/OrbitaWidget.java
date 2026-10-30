package __PACKAGE__;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.RemoteViews;
import java.io.File;

/** Widget de Órbita: portada, canción y botones anterior / reproducir / siguiente. */
public class OrbitaWidget extends AppWidgetProvider {

    static final String ACT_TOGGLE = "__PACKAGE__.WIDGET_TOGGLE";
    static final String ACT_NEXT = "__PACKAGE__.WIDGET_NEXT";
    static final String ACT_PREV = "__PACKAGE__.WIDGET_PREV";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        RemoteViews v = build(context);
        for (int id : ids) manager.updateAppWidget(id, v);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String a = intent.getAction();
        String action = ACT_TOGGLE.equals(a) ? "toggle" : ACT_NEXT.equals(a) ? "next" : ACT_PREV.equals(a) ? "prev" : null;
        if (action == null) return;
        if (MusicLibraryPlugin.sendWidgetAction(action)) return;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if ("toggle".equals(action)) launch.putExtra("orbita_widget_action", action);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {}
    }

    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, OrbitaWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = build(context);
        for (int id : ids) manager.updateAppWidget(id, v);
    }

    static RemoteViews build(Context context) {
        SharedPreferences p = context.getSharedPreferences(MusicLibraryPlugin.WIDGET_PREFS, Context.MODE_PRIVATE);
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.orbita_widget);
        v.setTextViewText(R.id.w_title, p.getString("title", "Órbita"));
        String artist = p.getString("artist", "");
        v.setTextViewText(R.id.w_artist, artist == null || artist.isEmpty() ? "Toca para abrir tu música" : artist);
        v.setImageViewResource(R.id.w_play, p.getBoolean("playing", false) ? R.drawable.orbita_w_pause : R.drawable.orbita_w_play);
        Bitmap art = null;
        try {
            File f = new File(context.getFilesDir(), "widget_art.jpg");
            if (f.exists()) art = BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception ignored) {}
        if (art != null) v.setImageViewBitmap(R.id.w_art, art);
        else v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher);
        v.setOnClickPendingIntent(R.id.w_play, action(context, ACT_TOGGLE, 1));
        v.setOnClickPendingIntent(R.id.w_next, action(context, ACT_NEXT, 2));
        v.setOnClickPendingIntent(R.id.w_prev, action(context, ACT_PREV, 3));
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            v.setOnClickPendingIntent(R.id.w_body, PendingIntent.getActivity(context, 4, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return v;
    }

    static PendingIntent action(Context context, String action, int code) {
        Intent i = new Intent(context, OrbitaWidget.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
