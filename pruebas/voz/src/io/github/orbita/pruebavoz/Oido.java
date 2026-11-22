package io.github.orbita.pruebavoz;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * Un reconocimiento de voz de mentira para las pruebas: dice que está listo, que empieza a hablar, mueve el nivel, va
 * dando palabras sueltas («pon», «pon algo»…) y al final la frase entera; o no oye nada, o va sin internet, según el guion.
 */
public class Oido extends RecognitionService {
    private final Handler h = new Handler(Looper.getMainLooper());
    private int gen = 0;
    private String[] words = new String[0];
    private int said = 0;
    private Callback cb;
    private boolean over = true;

    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        final int g = ++gen;
        cb = callback; over = false; said = 0;
        String guion = Guion.leer(this);
        boolean slow = guion.startsWith("lento:");
        if (slow) guion = guion.substring(6);
        words = guion.split("\\s+");
        final int step = slow ? 1600 : 380;
        try { callback.readyForSpeech(new Bundle()); } catch (Exception ignored) { }
        if (guion.equals("nada")) { at(g, 1500, () -> fail(g, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)); return; }
        if (guion.equals("red")) { at(g, 700, () -> fail(g, SpeechRecognizer.ERROR_NETWORK)); return; }
        if (guion.equals("ocupado")) { at(g, 200, () -> fail(g, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)); return; }
        at(g, 300, () -> { try { cb.beginningOfSpeech(); } catch (Exception ignored) { } });
        // el nivel de la voz: sube y baja mientras «habla»
        final int total = 300 + step * words.length + 200;
        for (int t = 300; t < total; t += 60) {
            final float v = (float) (2 + 7 * Math.abs(Math.sin(t / 170.0)));
            at(g, t, () -> { try { cb.rmsChanged(v); } catch (Exception ignored) { } });
        }
        for (int i = 1; i <= words.length; i++) {
            final int n = i;
            at(g, 300 + step * i, () -> partial(g, n));
        }
        at(g, total, () -> { try { cb.endOfSpeech(); } catch (Exception ignored) { } });
        at(g, total + 350, () -> results(g));
    }

    private void partial(int g, int n) {
        if (g != gen || over) return;
        said = n;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) b.append(' '); b.append(words[i]); }
        Bundle r = new Bundle();
        ArrayList<String> l = new ArrayList<>(); l.add(b.toString());
        r.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, l);
        try { cb.partialResults(r); } catch (Exception ignored) { }
    }

    private void results(int g) {
        if (g != gen || over) return;
        over = true;
        StringBuilder b = new StringBuilder();
        int n = said == 0 ? words.length : said;
        for (int i = 0; i < n; i++) { if (i > 0) b.append(' '); b.append(words[i]); }
        Bundle r = new Bundle();
        ArrayList<String> l = new ArrayList<>(); l.add(b.toString()); l.add(b.toString() + " también");
        r.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, l);
        try { cb.results(r); } catch (Exception ignored) { }
    }

    private void fail(int g, int code) {
        if (g != gen || over) return;
        over = true;
        try { cb.error(code); } catch (Exception ignored) { }
    }

    private void at(final int g, long ms, final Runnable r) { h.postDelayed(() -> { if (g == gen) r.run(); }, ms); }

    /** «Listo»: lo dicho hasta ahora es lo que vale. */
    @Override
    protected void onStopListening(Callback callback) {
        final int g = gen;
        if (over) return;
        try { callback.endOfSpeech(); } catch (Exception ignored) { }
        h.postDelayed(() -> results(g), 250);
    }

    @Override
    protected void onCancel(Callback callback) { gen++; over = true; }
}
