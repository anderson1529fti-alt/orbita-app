package __PACKAGE__;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicLibraryPlugin.class);
        registerPlugin(NovaVoicePlugin.class);
        // El motor de sonido de Android (3.5) solo está si se pudo compilar (en las compilaciones de respaldo no va)
        try {
            Class<?> audio = Class.forName(getPackageName() + ".NovaAudioPlugin");
            registerPlugin(audio.asSubclass(com.getcapacitor.Plugin.class));
        } catch (Throwable ignored) {
        }
        // El despertador (3.5), igual
        try {
            Class<?> alarm = Class.forName(getPackageName() + ".NovaAlarmPlugin");
            registerPlugin(alarm.asSubclass(com.getcapacitor.Plugin.class));
        } catch (Throwable ignored) {
        }
        // 4.3 · Las pantallas nativas (Kotlin y Compose), igual
        try {
            Class<?> ui = Class.forName(getPackageName() + ".OrbitaUiPlugin");
            registerPlugin(ui.asSubclass(com.getcapacitor.Plugin.class));
        } catch (Throwable ignored) {
        }
        // El cerebro grande, igual
        try {
            Class<?> brain = Class.forName(getPackageName() + ".NovaBrainPlugin");
            registerPlugin(brain.asSubclass(com.getcapacitor.Plugin.class));
        } catch (Throwable ignored) {
        }
        super.onCreate(savedInstanceState);
    }
}
