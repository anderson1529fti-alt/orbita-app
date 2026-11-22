package io.github.orbita.pruebavoz;

import android.content.Context;
import android.provider.Settings;

/** Lo que «oye» la prueba: lo escribe la prueba con «settings put global orbita_oido …». */
final class Guion {
    /** «frase» (la dice entera), «nada» (no oye nada), «lento:frase» (la dice muy despacio), «red» (sin internet), «ocupado». */
    static String leer(Context c) {
        String s = null;
        try { s = Settings.Global.getString(c.getContentResolver(), "orbita_oido"); } catch (Throwable ignored) { }
        return s == null || s.trim().isEmpty() ? "pon algo tranquilo" : s.trim();
    }
    private Guion() { }
}
