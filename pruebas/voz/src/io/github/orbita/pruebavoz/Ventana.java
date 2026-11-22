package io.github.orbita.pruebavoz;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import java.util.ArrayList;

/** La «ventana de Google» de mentira: contesta enseguida con la frase del guion. */
public class Ventana extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String g = Guion.leer(this);
        if (g.startsWith("lento:")) g = g.substring(6);
        Intent r = new Intent();
        ArrayList<String> l = new ArrayList<>();
        if (!g.equals("nada") && !g.equals("red") && !g.equals("ocupado")) l.add("ventana: " + g);
        r.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, l);
        setResult(l.isEmpty() ? RESULT_CANCELED : RESULT_OK, r);
        finish();
    }
}
