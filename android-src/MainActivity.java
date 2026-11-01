package __PACKAGE__;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicLibraryPlugin.class);
        registerPlugin(NovaVoicePlugin.class);
        // El cerebro grande solo está si se pudo compilar (en las compilaciones de respaldo no va)
        try {
            Class<?> brain = Class.forName(getPackageName() + ".NovaBrainPlugin");
            registerPlugin(brain.asSubclass(com.getcapacitor.Plugin.class));
        } catch (Throwable ignored) {
        }
        super.onCreate(savedInstanceState);
    }
}
