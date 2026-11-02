package __PACKAGE__;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android borra las alarmas al reiniciar el móvil, al actualizar la app o al cambiar la hora: se vuelven a programar. */
public class NovaAlarmBoot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            NovaAlarm.schedule(c);
        } catch (Throwable ignored) {
        }
    }
}
