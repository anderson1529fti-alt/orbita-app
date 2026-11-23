package __PACKAGE__

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Fase 4, parte 2 · El traspaso de los datos de la parte web a Android (una vez: la primera vez que se abre la versión
 * nueva). Una página pequeña («migrar.html»), en una vista web invisible con la misma dirección que la app de siempre
 * (https://localhost: así ve sus mismos datos), lo lee todo y se lo pasa a [Store]. Al terminar se comprueba que ha
 * llegado todo (cuántos ajustes, registros, escuchas y archivos, y lo que ocupan); solo entonces queda hecho. Si algo
 * falla no queda nada a medias: la próxima vez se empieza de cero. Lo de la web no se toca (es la copia de seguridad).
 */
object Migra {
    const val KEY = "_migrado"
    @Volatile var state = "nada"          // nada · corriendo · hecho · fallo
    @Volatile var detail = ""
    @Volatile var progress = 0.0
    @Volatile var what = ""
    @Volatile var ms = 0L
    @Volatile private var lastAt = 0L
    private var web: WebView? = null
    private var t0 = 0L
    private val main = Handler(Looper.getMainLooper())
    private val cbs = ArrayList<(Boolean, String) -> Unit>()

    fun done(): Boolean = Store.ready && Store.kv(KEY) != null

    /** Lo pasa todo (si no está hecho ya, o a la fuerza: desde cero). Se llama en el hilo principal. */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(act: Activity, force: Boolean, cb: (Boolean, String) -> Unit) {
        Store.init(act)
        if (!force && done()) { cb(true, "ya estaba"); return }
        cbs.add(cb)
        if (state == "corriendo") return
        state = "corriendo"; detail = ""; progress = 0.0; what = ""; t0 = SystemClock.uptimeMillis(); lastAt = t0
        Store.setKv(KEY, null)
        val wv = WebView(act)
        web = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.isFocusable = false; wv.isClickable = false
        wv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        wv.addJavascriptInterface(Bridge(act), "OrbitaMigra")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host == "localhost" && u.path == "/migrar.html") {
                    return try { WebResourceResponse("text/html", "utf-8", act.assets.open("public/migrar.html")) }
                    catch (t: Throwable) { main.post { finish(false, "falta migrar.html") }; empty(404) }
                }
                return empty(404)   // (nada de fuera: solo lee lo que ya está en el móvil)
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post { finish(false, "la vista web se cerró") }
                return true
            }
        }
        // (invisible y fuera de la pantalla; puesta en la ventana para que vaya a su ritmo)
        try {
            val root = act.window.decorView as ViewGroup
            val lp = FrameLayout.LayoutParams(1, 1)
            wv.alpha = 0f; wv.translationX = -1000f
            root.addView(wv, lp)
        } catch (ignored: Throwable) { }
        wv.loadUrl("https://localhost/migrar.html")
        watch()
    }

    private fun empty(code: Int) = WebResourceResponse("text/plain", "utf-8", code, "No", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    // sin noticias en 90 s (y todavía corriendo): se da por fallido, y la próxima vez se empieza de cero
    private fun watch() {
        main.postDelayed({
            if (state != "corriendo") return@postDelayed
            if (SystemClock.uptimeMillis() - lastAt > 90_000) finish(false, "sin respuesta") else watch()
        }, 5000)
    }

    private fun finish(ok: Boolean, msg: String) {
        if (state != "corriendo") return
        state = if (ok) "hecho" else "fallo"; detail = msg; ms = SystemClock.uptimeMillis() - t0
        if (ok) progress = 1.0
        val wv = web; web = null
        if (wv != null) try {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeJavascriptInterface("OrbitaMigra"); wv.stopLoading(); wv.destroy()
        } catch (ignored: Throwable) { }
        val l = ArrayList(cbs); cbs.clear()
        for (c in l) try { c(ok, msg) } catch (ignored: Throwable) { }
    }

    /** Lo que dice la página (en el hilo de la vista web, no en el de la pantalla). */
    private class Bridge(val act: Activity) {
        private var out: FileOutputStream? = null
        private var outFile: File? = null
        private var written = 0L

        private fun touch() { lastAt = SystemClock.uptimeMillis() }

        // la clave de IndexedDB, en texto JSON → la de aquí (las de texto, tal cual)
        private fun keyOf(kj: String): String = try { val v = JSONTokener(kj).nextValue(); if (v is String) v else "#$kj" } catch (t: Throwable) { "#$kj" }

        private fun open(f: File) {
            closeQuiet()
            f.parentFile?.mkdirs()
            out = FileOutputStream(f); outFile = f; written = 0
        }

        private fun closeQuiet() { try { out?.close() } catch (ignored: Throwable) { }; out = null }

        private fun close(size: Double): Boolean {
            val o = out ?: return false
            o.flush(); o.fd.sync(); o.close(); out = null
            val ok = written == size.toLong() && outFile?.length() == written
            if (!ok) outFile?.delete()
            return ok
        }

        @JavascriptInterface fun begin(info: String) { touch(); closeQuiet(); Store.clearAll() }

        @JavascriptInterface fun kv(json: String): Int {
            touch()
            val o = JSONObject(json); var n = 0
            Store.tx { for (k in o.keys()) { Store.setKv(k, o.getString(k)); n++ } }
            return n
        }

        @JavascriptInterface fun recs(store: String, json: String): Int {
            touch()
            val a = JSONArray(json)
            Store.tx { for (i in 0 until a.length()) { val r = a.getJSONArray(i); Store.putRec(store, keyOf(r.getString(0)), r.getString(1)) } }
            return a.length()
        }

        @JavascriptInterface fun evs(json: String): Int {
            touch()
            val a = JSONArray(json)
            Store.tx { for (i in 0 until a.length()) { val r = a.getJSONArray(i); Store.addEvent(r.getString(1), r.getString(0).toDoubleOrNull()?.toLong()) } }
            return a.length()
        }

        @JavascriptInterface fun blobOpen(store: String, key: String, path: String): String {
            touch(); val rel = Store.blobPath(store, key, path); open(Store.file(rel)); return rel
        }

        @JavascriptInterface fun chunk(b64: String): Int {
            touch()
            val b = Base64.decode(b64, Base64.DEFAULT)
            out!!.write(b); written += b.size
            return b.size
        }

        @JavascriptInterface fun blobClose(size: Double): Boolean { touch(); return close(size) }

        @JavascriptInterface fun hasNative(dir: String, name: String, size: Double): Boolean {
            val f = File(File(act.filesDir, dir), name)
            return f.isFile && f.length() == size.toLong()
        }

        @JavascriptInterface fun fileOpen(group: String, name: String, type: String): String {
            touch(); val rel = "cache/" + Store.safe(group) + "/" + Store.safe(name); open(Store.file(rel)); return rel
        }

        @JavascriptInterface fun fileClose(size: Double): Boolean { touch(); return close(size) }

        @JavascriptInterface fun progress(p: Double, w: String) { touch(); Migra.progress = p; Migra.what = w }

        @JavascriptInterface fun done(json: String) {
            touch(); closeQuiet()
            val bad = verify(JSONObject(json))
            main.post { finish(bad.isEmpty(), if (bad.isEmpty()) "" else "no cuadra: " + bad.joinToString(", ")) }
        }

        @JavascriptInterface fun fail(err: String) { closeQuiet(); main.post { finish(false, err.take(600)) } }

        /** Que esté todo: los mismos ajustes, registros de cada almacén, escuchas y archivos (y lo que ocupan). */
        private fun verify(sum: JSONObject): List<String> {
            val bad = ArrayList<String>()
            if (Store.kvAll().keys.count { !it.startsWith("_") } != sum.optInt("kv", -1)) bad.add("ajustes")
            val st = sum.optJSONObject("stores") ?: JSONObject()
            for (s in st.keys()) if (Store.count(s) != st.getInt(s)) bad.add(s)
            for (s in Store.stores()) if (!st.has(s)) bad.add(s)
            if (Store.evCount() != sum.optInt("events", -1)) bad.add("escuchas")
            val (bn, bb) = Store.blobStats()
            if (bn != sum.optInt("blobs", -1) || bb != sum.optLong("bytes", -1)) bad.add("archivos ($bn/${sum.optInt("blobs")}, $bb/${sum.optLong("bytes")})")
            var fn = 0; var fb = 0L
            File(Store.root, "cache").walkTopDown().filter { it.isFile }.forEach { fn++; fb += it.length() }
            if (fn != sum.optInt("files", -1) || fb != sum.optLong("fbytes", -1)) bad.add("voz ($fn/${sum.optInt("files")})")
            if (bad.isEmpty()) Store.setKv(KEY, JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("sum", sum).toString())
            return bad
        }
    }
}
