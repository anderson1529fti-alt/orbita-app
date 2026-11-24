package __PACKAGE__

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fase 4, parte 2 · Lo que Nova saca de internet (sin claves ni cuentas): lo que dice Wikipedia de tus artistas y el
 * tiempo que hace en tu ciudad (Open-Meteo). Como en la web, con lo mismo guardado en el móvil.
 */
object Online {
    /** Cómo se presenta Órbita a las webs (las radios, Deezer…). */
    const val UA = "Orbita/2.0 (app de música personal; Android)"
    val net = Executors.newFixedThreadPool(3)

    /** Hay internet (la web lo llamaba «ONLINE»: la app de Android y con conexión). */
    fun on(): Boolean {
        val a = Nativo.act ?: return false
        return try {
            val cm = a.getSystemService(ConnectivityManager::class.java)
            val c = cm.getNetworkCapabilities(cm.activeNetwork)
            c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) { true }
    }

    /** Una petición GET que devuelve JSON (o null). */
    /** Una respuesta que no es «bien» (con lo que dijo el servidor). */
    class HttpError(val status: Int, val body: String) : Exception("http $status")

    fun getJson(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 9000, throwStatus: Boolean = false): Any? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout; c.readTimeout = timeout
            c.setRequestProperty("Accept", "application/json")
            if (headers.keys.none { it.equals("User-Agent", true) }) c.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code !in 200..299) {
                if (throwStatus) throw HttpError(code, try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (t: Throwable) { "" })
                return null
            }
            return Json.parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** Una imagen de internet (portada de una muestra, logo de una radio, miniatura de YouTube), guardada para la próxima. */
    fun cachedImage(url: String): java.io.File? {
        if (!url.startsWith("http")) return null
        val dir = java.io.File(Store.root, "cache/net"); dir.mkdirs()
        val f = java.io.File(dir, Store.sha1(url).take(24))
        if (f.isFile && f.length() > 0) return f
        if (!on()) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 9000; c.setRequestProperty("User-Agent", UA)
                if (c.responseCode !in 200..299) return null
                val tmp = java.io.File(dir, f.name + ".tmp")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.length() > 0) tmp.renameTo(f)
                // (no más de 300 imágenes guardadas)
                dir.listFiles()?.let { all -> if (all.size > 300) all.sortedBy { it.lastModified() }.take(all.size - 300).forEach { it.delete() } }
                if (f.isFile) f else null
            } finally { c.disconnect() }
        } catch (t: Throwable) { null }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------- lo que dice Wikipedia
    private val FACT_RE = Regex("(cantante|banda|grupo|rapero|rapera|musico|musica|dj|productor|productora|compositor|compositora|duo|cantautor|cantautora|orquesta|trap|reggaeton|rock|pop|hip hop|discografica|album)")
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun artistFact(name: String, done: ((Map<String, Any?>?) -> Unit)? = null) {
        val key = Biblio.artistKey(name)
        if (!on() || key.length < 2 || Regex("^(desconocid|unknown|varios|various|orbita|artista)").containsMatchIn(key)) { done?.invoke(null); return }
        val all = Json.obj(Prefs.get("facts")) ?: emptyMap(); val c = Json.obj(all[key])
        if (c != null && System.currentTimeMillis() - Json.l(c["ts"]) < (if (c["f"] != null) 60 else 10) * 864e5) { done?.invoke(Json.obj(c["f"])); return }
        if (!pending.add(key)) { done?.invoke(null); return }
        net.execute {
            var f: Map<String, Any?>? = null; var ok = true
            try {
                val H = mapOf("Api-User-Agent" to "Orbita/2.3 (app de musica personal)")
                val s = Json.obj(getJson("https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&origin=*&srlimit=5&srsearch=" + enc(name), H))
                for (h in Json.list(Json.obj(s?.get("query"))?.get("search")) ?: emptyList()) {
                    val title = Json.s(Json.obj(h)?.get("title")); val tn = Biblio.norm(title.replace(Regex("\\s*\\(.*\\)\\s*$"), ""))
                    if (!(tn == key || tn.replace(" ", "") == key.replace(" ", "") || tn.startsWith("$key ") || key.startsWith("$tn "))) continue
                    val sm = Json.obj(getJson("https://es.wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(' ', '_')), H)) ?: continue
                    val text = Json.s(sm["extract"]); val desc = Json.s(sm["description"])
                    if (Json.s(sm["type"]) == "disambiguation" || !FACT_RE.containsMatchIn(Biblio.norm(desc + " " + text.take(400)))) continue
                    val sent = text.split(Regex("(?<=[.!?])\\s+")); var ex = sent.firstOrNull() ?: ""; if (ex.length < 140 && sent.size > 1) ex += " " + sent[1]
                    val url = Json.s(Json.obj(Json.obj(sm["content_urls"])?.get("mobile"))?.get("page")).ifEmpty { "https://es.wikipedia.org/wiki/" + enc(title.replace(' ', '_')) }
                    f = linkedMapOf("title" to Json.s(sm["title"]).ifEmpty { title }, "desc" to desc.take(120), "text" to ex.take(360), "url" to url)
                    break
                }
            } catch (t: Throwable) { ok = false }
            if (ok) {
                val cur = (Json.obj(Prefs.get("facts")) ?: emptyMap()).toMutableMap()
                cur[key] = linkedMapOf("ts" to System.currentTimeMillis(), "f" to f)
                if (cur.size > 400) cur.keys.sortedBy { Json.l(Json.obj(cur[it])?.get("ts")) }.take(cur.size - 400).forEach { cur.remove(it) }
                Prefs.set("facts", cur); Cerebro.genresChanged()
            }
            pending.remove(key)
            done?.let { d -> android.os.Handler(android.os.Looper.getMainLooper()).post { d(f) } }
        }
    }

    fun shortFact(f: Map<String, Any?>): String {
        val s = Json.s(f["text"]).replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+,"), ",").replace(Regex("\\s+"), " ").trim()
        val first = s.split(Regex("(?<=[.!?])\\s+")).firstOrNull() ?: ""
        return if (first.length <= 240) first else ""
    }

    private val factSaid = HashSet<String>()
    fun factLine(t: Track): String? {
        if (t.net || t.demo || t.mine) return null
        val k = Biblio.artistKey(t.artist); if (k.isEmpty() || k in factSaid) return null
        val f = Cerebro.cachedFact(t.artist) ?: return null; val s = shortFact(f); if (s.isEmpty()) return null
        factSaid.add(k)
        return Cerebro.pickFresh(listOf("Un dato: $s Suena ${t.title}.", "¿Sabías esto? $s Ahora, ${t.title}.", "$s Y esta es ${t.title}."))
    }

    // ---------------------------------------------------------------- el tiempo
    internal fun wxDesc(c: Int): Pair<String, String> = when {
        c == 0 -> "despejado" to "sol"; c <= 2 -> "algo nublado" to "nubes"; c == 3 -> "nublado" to "nubes"; c <= 48 -> "con niebla" to "niebla"
        c <= 57 -> "con llovizna" to "lluvia"; c <= 67 -> "lloviendo" to "lluvia"; c <= 77 -> "nevando" to "nieve"; c <= 82 -> "con chubascos" to "lluvia"; else -> "con tormenta" to "tormenta"
    }
    fun weatherNow(): Map<String, Any?>? {
        val w = Json.obj(Prefs.get("wx")); val c = Json.obj(Prefs.get("city"))
        return if (w != null && c != null && Json.s(w["city"]) == Json.s(c["name"]) && System.currentTimeMillis() - Json.l(w["ts"]) < 3 * 3600e3) w else null
    }
    fun loadWeather(force: Boolean = false, done: ((Map<String, Any?>?) -> Unit)? = null) {
        val c = Json.obj(Prefs.get("city"))
        if (c == null || !on()) { done?.invoke(null); return }
        val old = Json.obj(Prefs.get("wx"))
        if (!force && old != null && Json.s(old["city"]) == Json.s(c["name"]) && System.currentTimeMillis() - Json.l(old["ts"]) < 30 * 60000) { done?.invoke(old); return }
        net.execute {
            val w = try {
                val j = Json.obj(getJson("https://api.open-meteo.com/v1/forecast?latitude=${Json.d(c["lat"])}&longitude=${Json.d(c["lon"])}&current=temperature_2m,weather_code,is_day&timezone=auto"))
                val cur = Json.obj(j?.get("current"))
                if (cur == null) weatherNow() else linkedMapOf("ts" to System.currentTimeMillis(), "city" to Json.s(c["name"]), "temp" to Math.round(Json.d(cur["temperature_2m"])),
                    "code" to Json.l(cur["weather_code"]), "day" to (Json.l(cur["is_day"]) == 1L)).also { Prefs.set("wx", it) }
            } catch (t: Throwable) { weatherNow() }
            done?.let { d -> android.os.Handler(android.os.Looper.getMainLooper()).post { d(w) } }
        }
    }
    fun weatherLine(): String {
        val w = weatherNow() ?: return ""
        val (d, k) = wxDesc(Json.l(w["code"]).toInt()); val temp = Json.l(w["temp"]); val day = Json.b(w["day"])
        val tip = if (k == "lluvia" || k == "tormenta") " Día de música tranquila." else if (k == "sol" && day && temp >= 22) " Buen día para subir el ritmo." else ""
        return " En ${Json.s(w["city"])} está $d y hace $temp grados.$tip"
    }
    fun weatherBias(): Double {
        val w = weatherNow() ?: return 0.0
        val k = wxDesc(Json.l(w["code"]).toInt()).second
        return if (k == "lluvia" || k == "tormenta" || k == "niebla") -0.08 else if (k == "nieve") -0.05 else if (k == "sol" && Json.b(w["day"]) && Json.l(w["temp"]) >= 20) 0.06 else 0.0
    }

    /** Lo que dice Nova al empezar una radio o un vídeo de internet. */
    fun netIntro(t: Track): String = if (t.kind == "yt") Tubo.intro(t) else Buscar.radioIntro(t)
}

