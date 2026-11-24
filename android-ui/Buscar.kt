package __PACKAGE__

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Fase 4, parte 2 · «Buscar» hecho por Android (lo que en la web era «Search» y «Online: song previews, internet radio
 * and YouTube inside the app»): canciones (lo que ya tienes, muestras de 30 segundos de Deezer o Apple Music y las ideas
 * de la DJ con tu clave de Claude), «Por conseguir», miles de radios de radio-browser.info (con tus radios del corazón
 * y la última lista guardada por si el directorio no responde) y YouTube (enlaces, búsqueda con clave y lo visto hace
 * poco). Las mismas peticiones y lo mismo guardado que la web, así que lo de antes sigue ahí.
 */
object Buscar {
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(3)
    /** (en la app de Android la web siempre podía salir a internet: «ONLINE») */
    private const val ONLINE = true

    /** La pestaña: «canciones», «radios» o «youtube» (no se guarda, como en la web). */
    var tab by mutableStateOf("canciones")
    fun pickTab(t: String) {
        tab = if (t in listOf("canciones", "radios", "youtube")) t else "canciones"
        if (tab == "radios" && ONLINE && radio.list == null && !radio.loading) loadRadios()
        Ver.bump()
    }

    /** El país del móvil («ccGuess»: el de su idioma, p. ej. «es-ES» → ES). */
    val cc: String by lazy {
        try {
            val ll = android.os.LocaleList.getDefault()
            for (i in 0 until ll.size()) { val l = ll[i]; if (l.script.isEmpty() && Regex("^[a-z]{2,3}$").matches(l.language) && Regex("^[A-Z]{2}$").matches(l.country)) return@lazy l.country }
        } catch (ignored: Throwable) { }
        ""
    }

    // ---------------------------------------------------------------- canciones
    class Idea(val title: String, val artist: String, val year: String, val genre: String, val why: String)
    class Net(val q: String, val lib: List<Track>) {
        var results: List<Idea>? = null; var note = ""; var loading = false
        var online: List<Track>? = null; var onLoading = false; var onErr = ""
        /** (los «Avance» de las ideas que están buscando su muestra) */
        val busy = HashSet<Int>()
    }
    var cur by mutableStateOf<Net?>(null); private set
    /** Lo escrito en cada buscador. */
    var q by mutableStateOf("")
    var ytQ by mutableStateOf("")
    var rQ by mutableStateOf("")
    var keyIn by mutableStateOf("")
    var keyOpen by mutableStateOf(false)

    /** «libMatches»: lo de tu biblioteca que tiene todas las palabras. */
    fun libMatches(q: String): List<Track> {
        val nq = Biblio.norm(q); if (nq.isEmpty()) return emptyList()
        val words = nq.split(" ")
        return Biblio.visible().filter { t -> val h = Biblio.norm("${t.title} ${t.artist} ${t.album}"); words.all { h.contains(it) } }.take(8)
    }
    fun saved(title: String, artist: String): Boolean = Deseos.all().any { Biblio.norm(Json.s(it["title"])) == Biblio.norm(title) && Biblio.norm(Json.s(it["artist"])) == Biblio.norm(artist) }

    /** «runSearch»: lo que tienes al momento; las muestras de internet y las ideas de la IA, cuando llegan. */
    fun run(q0: String) {
        val qq = q0.trim(); if (qq.isEmpty()) return
        val my = Net(qq, libMatches(qq)); my.loading = Ia.ready(); my.onLoading = ONLINE
        cur = my; Ver.bump()
        net.execute {
            var list: List<Track>? = null; var err = ""
            try { list = onlineSongs(qq) } catch (e: Throwable) { err = "No he podido conectar para buscar canciones en internet." }
            main.post { if (cur === my) { my.online = list; my.onErr = err; my.onLoading = false; Ver.bump() } }
        }
        if (!my.loading) return
        net.execute {
            val (r, code) = Ia.json("Eres el buscador musical de una app. La persona busca: \"$qq\".\n" +
                "Devuelve hasta 8 canciones reales que existan y respondan a la búsqueda. Si es un artista, sus canciones más conocidas y alguna joya menos famosa. Si es una canción, esa canción (con su artista correcto) y otras parecidas. Si es un género, una época o un ambiente, una selección variada.\n" +
                "No inventes canciones: si dudas de que exista, no la pongas. Escribe en español de España.\n" +
                "Responde solo con JSON: {\"resultados\":[{\"titulo\":\"\",\"artista\":\"\",\"año\":\"\",\"genero\":\"\",\"motivo\":\"máximo 10 palabras\"}],\"nota\":\"opcional, una frase corta\"}", cancellable = false)
            main.post {
                if (cur !== my) return@post
                if (r != null) {
                    my.results = (Json.list(r["resultados"]) ?: emptyList()).take(8).map { Json.obj(it) ?: emptyMap() }.map { x ->
                        Idea(Json.s(x["titulo"]).take(120), Json.s(x["artista"]).take(120), Json.s(x["año"] ?: x["ano"]).take(10), Json.s(x["genero"]).take(40), Json.s(x["motivo"]).take(120))
                    }.filter { it.title.isNotEmpty() }
                    my.note = Json.s(r["nota"]).take(200)
                } else my.note = Ia.errorNote(code)
                my.loading = false; Ver.bump()
            }
        }
    }

    /** Una muestra de 30 segundos de internet (no está en tu biblioteca). */
    private fun preview(id: String, title: String, artist: String, album: String, art: String, artBig: String, url: String, full: Double, link: String): Track {
        val t = Lib.net[id] ?: Track(id).also { Lib.net[id] = it }
        t.net = true; t.kind = "preview"; t.title = title; t.artist = artist; t.album = album; t.art = art; t.artBig = artBig
        t.url = url; t.duration = 30.0; t.urlAt = System.currentTimeMillis()
        t.extra["full"] = full; t.extra["link"] = link
        return t
    }
    /** «onlineSongs»: canciones de verdad con muestra y portada (Deezer; si falla, iTunes). */
    fun onlineSongs(q: String): List<Track> {
        try {
            val j = Json.obj(Online.getJson("https://api.deezer.com/search?q=${Online.enc(q)}&limit=25", timeout = 15000))
            val list = (Json.list(j?.get("data")) ?: emptyList()).mapNotNull { Json.obj(it) }.filter { Json.s(it["preview"]).isNotEmpty() && it["readable"] != false }
            if (list.isNotEmpty()) return list.map { x ->
                val al = Json.obj(x["album"])
                preview("dz:" + Json.s(x["id"]), Json.s(x["title_short"]).ifEmpty { Json.s(x["title"]) }, Json.s(Json.obj(x["artist"])?.get("name")), Json.s(al?.get("title")),
                    Json.s(al?.get("cover_medium")).ifEmpty { Json.s(al?.get("cover")) }, Json.s(al?.get("cover_xl")).ifEmpty { Json.s(al?.get("cover_big")) }, Json.s(x["preview"]), Json.d(x["duration"]), Json.s(x["link"]))
            }
        } catch (ignored: Throwable) { }
        val j = Online.getJson("https://itunes.apple.com/search?term=${Online.enc(q)}&media=music&entity=song&limit=25${if (cc.isNotEmpty()) "&country=" + cc.lowercase() else ""}", timeout = 15000, throwStatus = true)
        return (Json.list(Json.obj(j)?.get("results")) ?: emptyList()).mapNotNull { Json.obj(it) }.filter { Json.s(it["previewUrl"]).isNotEmpty() }.map { x ->
            val a100 = Json.s(x["artworkUrl100"])
            preview("it:" + Json.s(x["trackId"]), Json.s(x["trackName"]), Json.s(x["artistName"]), Json.s(x["collectionName"]), a100.replace("100x100", "300x300"), a100.replace("100x100", "600x600"),
                Json.s(x["previewUrl"]), Math.round(Json.d(x["trackTimeMillis"]) / 1000).toDouble(), Json.s(x["trackViewUrl"]))
        }
    }
    /** «previewFor»: la muestra de una canción concreta (la que mejor encaja con su título). */
    fun previewFor(title: String, artist: String): Track? {
        val list = onlineSongs("$title $artist".trim())
        val nt = Biblio.norm(Recos.cleanTitle(title))
        return list.firstOrNull { Biblio.norm(Recos.cleanTitle(it.title)) == nt } ?: list.firstOrNull { val n = Biblio.norm(it.title); n.contains(nt) || nt.contains(n) } ?: list.firstOrNull()
    }

    // lo que se toca
    /** «net-play»: una tuya y, después, la radio. */
    fun playLib(id: String) {
        if (Lib.byId(id) == null) return
        Player.playQueue(listOf(id) + Cerebro.radioBatch(listOf(id)), 0, "radio")
        Radio.set(true, true)
    }
    fun playOnline(i: Int) { val l = cur?.online ?: return; if (l.isNotEmpty()) Player.playQueue(l.map { it.id }, i, "online") }
    fun aiPreview(i: Int) {
        val s = cur ?: return; val x = s.results?.getOrNull(i) ?: return
        if (!s.busy.add(i)) return
        Ver.bump()
        net.execute {
            val t = try { previewFor(x.title, x.artist) } catch (e: Throwable) { main.post { s.busy.remove(i); Ver.bump(); Nativo.toast("No he podido conectar para escucharla.") }; return@execute }
            main.post {
                s.busy.remove(i); Ver.bump()
                if (t != null) Player.playQueue(listOf(t.id), 0, "online") else Nativo.toast("No he encontrado un avance de esa canción.")
            }
        }
    }
    fun wishIdea(i: Int) { val x = cur?.results?.getOrNull(i) ?: return; Deseos.add(x.title, x.artist, x.why) }
    fun wishOnline(i: Int) { val t = cur?.online?.getOrNull(i) ?: return; Deseos.add(t.title, t.artist, t.album) }
    fun links(title: String, artist: String): List<Pair<String, String>> {
        val qq = Online.enc("$title $artist".trim())
        return listOf("YouTube" to "https://www.youtube.com/results?search_query=$qq", "Spotify" to "https://open.spotify.com/search/$qq", "SoundCloud" to "https://soundcloud.com/search?q=$qq")
    }

    // ---------------------------------------------------------------- radios (radio-browser.info)
    /** Tiene varios espejos que van y vienen; «all» apunta a los que están vivos. */
    @Volatile private var RB = listOf("https://all.api.radio-browser.info", "https://de2.api.radio-browser.info", "https://fi1.api.radio-browser.info", "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://de1.api.radio-browser.info")
    @Volatile private var rbFound = false
    fun rbGet(path: String): Any? {
        val hosts = (listOf(Prefs.str("rbHost", ""), RB[0]) + RB.drop(1).shuffled()).filter { it.isNotEmpty() }.distinct()
        var err: Throwable? = null
        for (round in 0 until 2) {
            for (h in hosts) {
                try {
                    val j = Online.getJson(h + path, timeout = 8000, throwStatus = true)
                    if (Prefs.str("rbHost", "") != h) Prefs.set("rbHost", h)
                    discover(h)
                    return j
                } catch (e: Throwable) { err = e }
            }
            try { Thread.sleep(1500) } catch (ignored: Throwable) { }
        }
        throw err ?: RuntimeException("radio")
    }
    private fun discover(h: String) {
        if (rbFound) return; rbFound = true
        net.execute {
            try {
                val names = (Json.list(Online.getJson("$h/json/servers", timeout = 8000)) ?: emptyList()).mapNotNull { Json.obj(it) }.map { Json.s(it["name"]) }
                    .filter { Regex("^[a-z0-9.-]+\\.radio-browser\\.info$", RegexOption.IGNORE_CASE).matches(it) }
                if (names.isNotEmpty()) RB = listOf(RB[0]) + names.map { "https://$it" }.distinct()
            } catch (ignored: Throwable) { }
        }
    }
    val TAGS = listOf("" to "Populares", "pop" to "Pop", "reggaeton" to "Reguetón", "latin" to "Latina", "rock" to "Rock", "dance" to "Dance", "electronic" to "Electrónica", "hip hop" to "Hip hop", "salsa" to "Salsa",
        "bachata" to "Bachata", "cumbia" to "Cumbia", "80s" to "Años 80", "90s" to "Años 90", "jazz" to "Jazz", "classical" to "Clásica", "lofi" to "Lo-fi", "chillout" to "Chill", "news" to "Noticias")
    val CC = listOf("" to "Todo el mundo", "ES" to "España", "MX" to "México", "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile", "PE" to "Perú", "VE" to "Venezuela", "EC" to "Ecuador", "US" to "EE. UU.",
        "CU" to "Cuba", "DO" to "Rep. Dominicana", "PR" to "Puerto Rico", "UY" to "Uruguay", "PY" to "Paraguay", "BO" to "Bolivia", "CR" to "Costa Rica", "GT" to "Guatemala", "HN" to "Honduras", "NI" to "Nicaragua",
        "PA" to "Panamá", "SV" to "El Salvador", "GB" to "Reino Unido", "FR" to "Francia", "IT" to "Italia", "DE" to "Alemania", "BR" to "Brasil", "PT" to "Portugal")
    class RadioSt { var list: List<Track>? = null; var loading = false; var err = ""; var stale = false; var req = 0 }
    val radio = RadioSt()
    fun rTag(): String = Prefs.str("rTag", "")
    fun rCC(): String = Prefs.str("rCC", if (CC.any { it.first == cc }) cc else "")
    fun favs(): List<Map<String, Any?>> = (Json.list(Prefs.get("radioFavs")) ?: emptyList()).mapNotNull { Json.obj(it) }
    fun isFav(uuid: String): Boolean = favs().any { Json.s(it["stationuuid"]) == uuid }
    /** (lo buscado por nombre: se aplica medio segundo después de escribir) */
    private var radioQ = ""
    private val rqRun = Runnable { radioQ = rQ.trim(); loadRadios() }
    fun rQChanged(v: String) { rQ = v.take(60); main.removeCallbacks(rqRun); main.postDelayed(rqRun, 450) }
    fun setTag(id: String) { Prefs.set("rTag", id); loadRadios() }
    fun setCC(id: String) { Prefs.set("rCC", id); loadRadios() }

    private fun raw(s: Map<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?) { if (v != null) m[k] = v }
        put("stationuuid", s["stationuuid"]); put("name", s["name"]); put("url_resolved", Json.s(s["url_resolved"]).ifEmpty { Json.s(s["url"]) })
        put("favicon", s["favicon"]); put("country", s["country"]); put("countrycode", s["countrycode"]); put("tags", s["tags"]); put("codec", s["codec"]); put("bitrate", s["bitrate"])
        return m
    }
    /** «stationTrack»: una emisora como una canción de la cola (no está en tu biblioteca). */
    fun station(s: Map<String, Any?>): Track {
        val uuid = Json.s(s["stationuuid"]); val id = "rb:$uuid"
        val t = Lib.net[id] ?: Track(id).also { Lib.net[id] = it }
        val tags = Json.s(s["tags"]).split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        t.net = true; t.kind = "radio"
        t.title = Json.s(s["name"]).ifEmpty { "Radio" }.trim().take(80)
        t.artist = (listOf(Json.s(s["country"]).ifEmpty { Json.s(s["countrycode"]) }) + tags).filter { it.isNotEmpty() }.joinToString(" · ")
        val fav = Json.s(s["favicon"]); t.art = if (Regex("^https?:").containsMatchIn(fav)) fav else ""
        t.url = Json.s(s["url_resolved"]).ifEmpty { Json.s(s["url"]) }; t.duration = 0.0
        t.extra["uuid"] = uuid; t.extra["country"] = Json.s(s["country"]); t.extra["codec"] = Json.s(s["codec"]); t.extra["bitrate"] = Json.l(s["bitrate"]); t.extra["raw"] = raw(s)
        return t
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun params(): String {
        val p = arrayListOf("limit" to "60", "hidebroken" to "true", "order" to "clickcount", "reverse" to "true")
        val tag = rTag(); val c = rCC()
        if (tag.isNotEmpty()) p.add("tag" to tag); if (c.isNotEmpty()) p.add("countrycode" to c); if (radioQ.isNotEmpty()) p.add("name" to radioQ)
        return p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
    }
    /** «loadRadios»: las más escuchadas con esos filtros (y una copia, por si el directorio no responde otro día). */
    fun loadRadios() {
        if (!ONLINE) return
        radio.loading = true; radio.err = ""; Ver.bump()
        val p = params(); val my = ++radio.req
        net.execute {
            var raws: List<Map<String, Any?>>? = null; var status = 0
            try {
                val j = rbGet("/json/stations/search?$p")
                val seen = HashSet<String>()
                raws = (Json.list(j) ?: emptyList()).mapNotNull { Json.obj(it) }.filter { s ->
                    (Json.s(s["url_resolved"]).isNotEmpty() || Json.s(s["url"]).isNotEmpty()) && !Json.b(s["hls"]) && !(s["lastcheckok"] is Number && Json.d(s["lastcheckok"]) == 0.0) && seen.add(Biblio.norm(Json.s(s["name"])))
                }.take(40).map { raw(it) }
                try {
                    val c = LinkedHashMap(Json.obj(Prefs.get("radioCache")) ?: emptyMap())
                    c[p] = linkedMapOf("ts" to System.currentTimeMillis(), "list" to raws)
                    val keys = c.keys.sortedByDescending { Json.d(Json.obj(c[it])?.get("ts")) }.take(25)
                    Prefs.set("radioCache", LinkedHashMap<String, Any?>().apply { for (k in keys) put(k, c[k]) })
                } catch (ignored: Throwable) { }
            } catch (e: Throwable) { status = (e as? Online.HttpError)?.status ?: 0 }
            val cached = if (raws == null) (Json.list(Json.obj(Json.obj(Prefs.get("radioCache"))?.get(p))?.get("list")) ?: emptyList()).mapNotNull { Json.obj(it) } else emptyList()
            main.post {
                if (radio.req != my) return@post
                if (raws != null) { radio.list = raws.map { station(it) }; radio.stale = false }
                else if (cached.isNotEmpty()) { radio.list = cached.map { station(it) }; radio.stale = true; radio.err = "" }
                else {
                    radio.list = null
                    radio.err = if (!Online.on()) "No tienes conexión a internet." else "El directorio de radios no responde ahora mismo${if (status > 0) " (error $status, es un fallo de su servidor)" else ""}. Tus radios guardadas con el corazón siguen funcionando. Prueba otra vez en unos minutos."
                }
                radio.loading = false; Ver.bump()
            }
        }
    }
    fun toggleFav(uuid: String) {
        val t = Lib.byId("rb:$uuid") ?: return
        val f = favs()
        val nf = if (f.any { Json.s(it["stationuuid"]) == uuid }) f.filter { Json.s(it["stationuuid"]) != uuid } else listOf(Json.obj(t.extra["raw"]) ?: return) + f
        Prefs.set("radioFavs", nf.take(60)); Ver.bump()
    }
    /** «playStation»: esa emisora (y las demás de la lista detrás), presentada por Nova. */
    fun playStation(id: String, fav: Boolean) {
        val list = if (fav) favs().map { station(it) } else radio.list ?: emptyList()
        val ids = list.map { it.id }; val i = maxOf(0, ids.indexOf(id)); val t = Lib.byId(id) ?: return
        Player.playQueue(if (ids.isNotEmpty()) ids else listOf(id), i, "online", if (Nova.intros()) radioIntro(t) to "saludo" else null)
        // (cuenta como escuchada en el directorio, como hace la web)
        val uuid = Json.s(t.extra["uuid"]); if (uuid.isNotEmpty()) net.execute { try { rbGet("/json/url/$uuid") } catch (ignored: Throwable) { } }
    }
    /** «radioIntro»: lo que dice Nova al sintonizar. */
    fun radioIntro(t: Track): String {
        val country = Json.s(t.extra["country"]); val where = if (country.isNotEmpty()) ", desde $country" else ""
        val P = when (Nova.persona()) {
            "energica" -> listOf("¡Sintonizamos ${t.title}$where!", "¡Cambio de dial! Esto es ${t.title}.")
            "tranquila" -> listOf("Te dejo con ${t.title}$where.", "Ahora suena ${t.title}.")
            "graciosa" -> listOf("Cambio de emisora: ${t.title}. Yo no cobro comisión.", "Te paso con ${t.title}$where. Sé buena con ellos.")
            "breve" -> listOf("${t.title}.")
            else -> listOf("Sintonizamos ${t.title}$where.", "Ahora, en directo: ${t.title}.", "Nos vamos a ${t.title}$where.")
        }
        return Cerebro.pick(P)
    }

    // ---------------------------------------------------------------- YouTube (lo de la pestaña)
    /** «ytForm»: un enlace se pone al momento; lo demás se busca (con clave). */
    fun ytGo() {
        val v = ytQ.trim(); if (v.isEmpty()) return
        val lk = Tubo.linkFrom(v)
        if (lk != null) { Tubo.playV(Tubo.V(lk.first, lk.second), "now"); Ventanas.open("np"); ytQ = "" }
        else if (Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(v)) Nativo.toast("Ese enlace no es de un vídeo ni de una lista de YouTube.")
        else Tubo.doSearch(v)
    }
    fun saveKey() {
        val v = keyIn.replace(Regex("\\s+"), ""); if (v.isEmpty()) return
        if (!Regex("^AIza[0-9A-Za-z_-]{35}$").matches(v)) { Nativo.toast("Esa clave no tiene la forma de una clave de Google (empieza por AIza y tiene 39 caracteres; esta tiene ${v.length}). Cópiala con el botón de copiar de la consola.", 6000); return }
        Prefs.set("ytKey", v); keyIn = ""; Ver.bump(); Nativo.toast("Clave de YouTube guardada en este móvil")
        val qq = ytQ.trim(); if (qq.isNotEmpty() && Tubo.idFrom(qq) == null) Tubo.doSearch(qq)
    }
    fun delKey() { Prefs.set("ytKey", ""); Ver.bump(); Nativo.toast("Clave de YouTube quitada") }
}

/** Imágenes de internet en las filas (logos de radios, miniaturas de YouTube), guardadas en el móvil para la próxima. */
object NetImg {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    val got = mutableStateMapOf<String, ImageBitmap>()
    private val order = ArrayDeque<String>()
    private val asked = HashSet<String>()
    val failed = mutableStateMapOf<String, Boolean>()
    fun want(url: String, sidePx: Int) {
        if (url.isEmpty() || !asked.add(url)) return
        io.execute {
            val f = Online.cachedImage(url)
            val bmp = try {
                if (f == null) null else {
                    val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.absolutePath, o)
                    var s = 1; while (o.outWidth / (s * 2) >= sidePx && o.outHeight / (s * 2) >= sidePx) s *= 2
                    val o2 = BitmapFactory.Options(); o2.inSampleSize = s
                    BitmapFactory.decodeFile(f.absolutePath, o2)?.asImageBitmap()
                }
            } catch (t: Throwable) { null }
            main.post {
                if (bmp == null) { failed[url] = true; return@post }
                got[url] = bmp; order.addLast(url)
                while (order.size > 160) { val old = order.removeFirst(); got.remove(old); asked.remove(old) }
            }
        }
    }
}

// ================================================================ la pantalla
@Composable
fun BuscarScreen() {
    val sk = rememberSkin()
    @Suppress("UNUSED_VARIABLE") val v = Ver.n
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(UiState.pageScroll("buscar")).padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = Tubo.viewBottom()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            PageHead("Buscar", "canciones, radios y YouTube", sk)
            Seg(listOf("canciones" to "Canciones", "radios" to "Radios", "youtube" to "YouTube"), Buscar.tab, sk, stretch = true, padH = 8.dp) { Buscar.pickTab(it) }
            when (Buscar.tab) { "radios" -> RadiosTab(sk); "youtube" -> YtTab(sk); else -> SongsTab(sk) }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

private const val I_SEARCH2 = "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0M20 20l-4-4"
private const val I_HEART2 = "M12 20s-7.5-4.6-9.2-9.3C1.7 7.5 3.8 4.5 7 4.5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.2 0 5.3 3 4.2 6.2C19.5 15.4 12 20 12 20z"
private const val I_TRASH2 = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"

/** «search» / «search big»: la lupa y lo escrito (el campo mide lo que en la web: su renglón y 1 de relleno arriba y abajo). */
@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, hint: String, sk: Skin, modifier: Modifier = Modifier, big: Boolean = false, onGo: () -> Unit = {}) {
    val p = sk.pal
    val shape = sk.r(if (big) 16f else 13f)
    val fs = (if (big) 16.8f else 15f) * sk.tk
    val focus = LocalFocusManager.current
    Row(modifier.fillMaxWidth().height((if (big) 28.dp else 20.dp) + sk.b * 2 + (fs * 1.45f + 2f).dp).clip(shape).background(Color.Black.copy(alpha = 0.35f)).border(sk.b, p.line2, shape)
        .padding(horizontal = (if (big) 18.dp else 14.dp) + sk.b), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(I_SEARCH2, if (big) 20f else 16f, p.muted, sw = 2.2f)
        BasicTextField(value, onValue, Modifier.weight(1f), singleLine = true, textStyle = style(fs, p.fg), cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onGo() }),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) BasicText(hint, Modifier.lines(fs * 1.45f), style = style(fs, Color(0xFF757575)), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                    inner()
                }
            })
    }
}

/** «toolbar» con un buscador grande y su botón (si no caben, el botón debajo). */
@Composable
private fun BigSearchBar(value: String, onValue: (String) -> Unit, hint: String, btn: String, sk: Skin, onGo: () -> Unit) {
    val focus = LocalFocusManager.current
    KeyRow(gap = 10.dp, field = { m -> SearchField(value, onValue, hint, sk, m, big = true, onGo = onGo) }) {
        HBtn(btn, sk, primary = true, kind = 2) { focus.clearFocus(); onGo() }
    }
}

/** Una lista de resultados («results»: a 8). */
@Composable
private fun Results(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** Un resultado («result»): en fila y, si no cabe, al renglón siguiente (el texto, de 240 como poco, se estira). */
@Composable
private fun ResultBox(sk: Skin, grow: Int, content: @Composable () -> Unit) {
    val p = sk.pal
    val shape = sk.r(14f)
    HFlow(12.dp, 12.dp, Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.25f)).border(sk.b, p.line, shape).padding(horizontal = 14.dp + sk.b, vertical = 12.dp + sk.b), grow = grow, basis = 240.dp) { content() }
}

/** «d-text»: el título en negrita (con « · artista» a continuación) y su explicación debajo. */
@Composable
private fun RText(b: String, rest: String, sub: String, sk: Skin, strike: Boolean = false) {
    val p = sk.pal
    Column {
        val txt = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = if (strike) p.fg.copy(alpha = 0.6f) else p.fg, textDecoration = if (strike) TextDecoration.LineThrough else null)) { append(b) }
            append(rest)
        }
        BasicText(txt, Modifier.lines(15f * sk.tk * 1.45f), style = style(15f * sk.tk, p.fg))
        if (sub.isNotEmpty()) T(sub, 13.12f * sk.tk, p.muted)
    }
}

/** «links»: lo de la derecha de un resultado (a 6, y al renglón siguiente si no cabe). */
@Composable
private fun Links(content: @Composable () -> Unit) = HFlow(6.dp, 6.dp) { content() }

/** «link»: el botón pequeño con borde (YouTube, Guardar…). */
@Composable
private fun LinkBtn(t: String, sk: Skin, enabled: Boolean = true, onClick: () -> Unit) {
    val p = sk.pal
    val shape = sk.r(9f)
    // (en la web los «link» no se hunden al apretarlos)
    Box(Modifier.press(scale = 1f, enabled = enabled, onClick = onClick).clip(shape).border(sk.b, p.line2, shape).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) {
        T(t, 12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** «icon-btn»: el corazón, la papelera… */
@Composable
private fun IconBtn(path: String, sk: Skin, on: Boolean = false, fill: Boolean = false, size: Float = 18f, onClick: () -> Unit) {
    val p = sk.pal
    Box(Modifier.size(34.dp).clip(sk.r(10f)).press(onClick = onClick), contentAlignment = Alignment.Center) { Ico(path, size, if (on) p.accent else p.muted, fill = fill, sw = 2f) }
}

/** El botón «▶ Escuchar» (pequeño, el principal). */
@Composable
private fun PlayBtn(t: String, sk: Skin, enabled: Boolean = true, icon: Boolean = true, onClick: () -> Unit) =
    HBtn(t, sk, primary = true, kind = 1, enabled = enabled, icon = if (icon) H_PLAY else null, iconFill = true, iconSize = 16f, onClick = onClick)

/** «panel thinking»: las barritas que bailan y lo que se está haciendo. */
@Composable
private fun Thinking(b: String, t: String?, sk: Skin) {
    val p = sk.pal
    Row(Modifier.fillMaxWidth().panel(sk).padding(18.dp + sk.b), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val still = Nativo.reduceMotion()
        val ph = rememberInfiniteTransition(label = "eq").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "e").value
        Canvas(Modifier.size(41.dp, 28.dp)) {
            val dl = floatArrayOf(0f, 0.3f, 0.6f, 0.15f, 0.45f)
            for (i in 0 until 5) {
                val k = (ph + dl[i]) % 1f
                val h = (if (still) 14f else 6f + 22f * (0.5f - 0.5f * cos(k * 2f * PI.toFloat()))).dp.toPx()
                drawRoundRect(p.grad, Offset(i * 9.dp.toPx(), size.height - h), Size(5.dp.toPx(), h), CornerRadius(3.dp.toPx() * sk.round))
            }
        }
        Column(Modifier.weight(1f)) {
            T(b, 15f * sk.tk, p.fg, weight = FontWeight.Bold)
            if (t != null) Note(t, sk)
        }
    }
}

/**
 * Un texto con un botón «link» dentro, al final (como en la web: «… Prueba otra vez. [Reintentar]»). En un párrafo el
 * botón cuenta para el alto de su renglón; en un enlace («a»), no (se dibuja por encima).
 */
@Composable
private fun TextWithLink(parts: List<Pair<String, Boolean>>, link: String, sk: Skin, size: Float, color: Color, block: Boolean, onLink: () -> Unit) {
    val p = sk.pal
    val tm = rememberTextMeasurer()
    val dens = LocalDensity.current
    val lst = style(12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold)
    val lw = with(dens) { tm.measure(link, lst).size.width.toDp() } + 22.dp + sk.b * 2
    val lineH = 12.8f * sk.tk * 1.45f
    val boxH = if (block) lineH + 12f + 2f else lineH
    val txt = buildAnnotatedString {
        for ((t, bold) in parts) if (bold) withStyle(SpanStyle(color = p.fg, fontWeight = FontWeight.Bold)) { append(t) } else append(t)
        append(" "); appendInlineContent("lk", link)
    }
    val fsPx = size
    val inline = mapOf("lk" to InlineTextContent(Placeholder((lw.value / fsPx).em, (boxH / fsPx).em, PlaceholderVerticalAlign.TextCenter)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredHeight((lineH + 12f + 2f).dp).press(onClick = onLink).clip(sk.r(9f)).border(sk.b, p.line2, sk.r(9f)).padding(horizontal = 11.dp + sk.b), contentAlignment = Alignment.Center) {
                T(link, 12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    })
    BasicText(txt, Modifier, style = style(size, color), inlineContent = inline)
}

// ---------------------------------------------------------------- canciones
private val IDEAS = listOf("Rosalía", "reguetón de 2010", "rock en español de los 80", "lo-fi para estudiar", "canciones como Bohemian Rhapsody")

@Composable
private fun SongsTab(sk: Skin) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BigSearchBar(Buscar.q, { Buscar.q = it.take(160) }, "Canción, artista, género o ambiente…", "Buscar", sk) { Buscar.run(Buscar.q) }
        NetOut(sk)
        WishPanel(sk)
    }
}

@Composable
private fun NetOut(sk: Skin) {
    val p = sk.pal
    val s = Buscar.cur
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (s == null) {
            val r = (20f * sk.round).dp
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(r)).dashed(p.line2, r, sk.b).padding(26.dp + sk.b), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("Busca cualquier canción del mundo", 18.4f * sk.tk, p.fg, family = sk.display, weight = FontWeight.ExtraBold, ls = -0.02f)
                T("Escribe un título, un artista, un género o un ambiente. Te enseño lo que ya tienes, avances de 30 segundos para escuchar al momento y las ideas de tu DJ." +
                    (if (!Ia.ready()) " Ahora mismo la IA no está disponible." else ""), 15f * sk.tk, p.muted)
                HFlow(8.dp, 8.dp) {
                    for (x in IDEAS) Box(Modifier.press { Buscar.q = x; Buscar.run(x) }.dashed(p.line2, 999.dp, sk.b).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) {
                        T(x, 12.8f * sk.tk, p.muted, weight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            return@Column
        }
        // lo que ya tienes
        if (s.lib.isNotEmpty()) Panel(sk, gap = 12.dp) {
            Eyebrow("Ya en tu biblioteca", sk)
            Results { for (t in s.lib) ResultBox(sk, 1) { Cover(Player.uiTrack(t), sk, 48f); RText(t.title, " · ${t.artist}", Biblio.quality(t)?.label ?: "", sk); PlayBtn("Escuchar", sk) { Buscar.playLib(t.id) } } }
        }
        // las muestras de internet
        val on = s.online
        when {
            s.onLoading -> Thinking("Buscando «${s.q}» en internet…", null, sk)
            on != null && on.isNotEmpty() -> Panel(sk, glow = true, gap = 12.dp) {
                HeadWith({ Eyebrow("Escúchalas ya · avances de 30 segundos", sk) }) { HBtn("Escuchar todos", sk, kind = 1, icon = H_PLAY, iconFill = true, iconSize = 16f) { Buscar.playOnline(0) } }
                Results {
                    on.take(15).forEachIndexed { i, t ->
                        val saved = Buscar.saved(t.title, t.artist)
                        ResultBox(sk, 1) {
                            Cover(Player.uiTrack(t), sk, 48f)
                            val full = Json.d(t.extra["full"])
                            RText(t.title, " · ${t.artist}", listOf(t.album, if (full > 0) Cerebro.fmt(full) else "").filter { it.isNotEmpty() }.joinToString(" · "), sk)
                            Links {
                                PlayBtn("Avance", sk) { Buscar.playOnline(i) }
                                LinkBtn("YouTube", sk) { Tubo.find("${t.title} ${t.artist}") }
                                LinkBtn(if (saved) "Guardada" else "Guardar", sk, enabled = !saved) { Buscar.wishOnline(i) }
                            }
                        }
                    }
                }
                Note("Avances de Deezer o Apple Music. Para tenerla entera, consíguela en archivo y súbela a tu biblioteca, o mira el vídeo en YouTube.", sk)
            }
            s.onErr.isNotEmpty() -> Note(s.onErr, sk)
            on != null -> Note("No hay avances para «${s.q}».", sk)
        }
        // las ideas de la DJ (con la IA)
        val res = s.results
        when {
            s.loading -> Thinking("${Nova.name()} está pensando en «${s.q}»…", "Reuniendo canciones reales que encajan.", sk)
            res != null && res.isNotEmpty() -> Panel(sk, gap = 12.dp) {
                Eyebrow("Ideas de ${Nova.name()} · según la IA", sk)
                Results {
                    res.forEachIndexed { i, x ->
                        val have = Recos.inLibrary(x.title, x.artist)
                        val saved = Buscar.saved(x.title, x.artist)
                        ResultBox(sk, 1) {
                            RNum(i + 1, sk)
                            RText(x.title, if (x.artist.isNotEmpty()) " · ${x.artist}" else "", listOf(x.year, x.genre, x.why).filter { it.isNotEmpty() }.joinToString(" · "), sk)
                            Links {
                                if (have != null) PlayBtn("La tienes", sk) { Buscar.playLib(have.id) }
                                else {
                                    val busy = i in s.busy
                                    PlayBtn(if (busy) "Buscando…" else "Avance", sk, enabled = !busy, icon = !busy) { Buscar.aiPreview(i) }
                                    LinkBtn("YouTube", sk) { Tubo.find("${x.title} ${x.artist}") }
                                    LinkBtn(if (saved) "Guardada" else "Guardar", sk, enabled = !saved) { Buscar.wishIdea(i) }
                                }
                            }
                        }
                    }
                }
                if (s.note.isNotEmpty()) Note(s.note, sk)
            }
            s.note.isNotEmpty() -> Note(s.note, sk)
            res != null -> Note("La IA no encontró canciones para «${s.q}». Prueba con otras palabras.", sk)
        }
    }
}

/** «r-num»: el número de la idea, solo con el borde del color de la app. */
@Composable
private fun RNum(n: Int, sk: Skin) {
    val p = sk.pal
    val tm = rememberTextMeasurer()
    val st = style(20.8f * sk.tk, p.accent, sk.display, FontWeight.Black)
    val dens = LocalDensity.current
    val minW = with(dens) { tm.measure("00", st).size.width.toDp() }
    val sw = with(dens) { 1.dp.toPx() }
    Box(Modifier.widthIn(min = minW), contentAlignment = Alignment.CenterEnd) {
        BasicText("$n", Modifier.lines(20.8f * sk.tk * 1.45f), style = st.copy(drawStyle = Stroke(width = sw), textAlign = TextAlign.End), maxLines = 1)
    }
}

@Composable
private fun WishPanel(sk: Skin) {
    val p = sk.pal
    val w = Deseos.all().sortedByDescending { Json.d(it["addedAt"]) }
    Panel(sk) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            H2("Por conseguir", sk)
            if (w.isNotEmpty()) T("${w.size}", 13.6f * sk.tk, p.muted, family = Mono, maxLines = 1)
        }
        Note("Canciones que has guardado desde la búsqueda. Cuando las tengas en archivo, súbelas a la Biblioteca y se tacharán solas.", sk)
        Results {
            if (w.isEmpty()) Note("Aún no has guardado nada.", sk)
            for (x in w) {
                val title = Json.s(x["title"]); val artist = Json.s(x["artist"])
                val have = Recos.inLibrary(title, artist)
                ResultBox(sk, 0) {
                    RText(title, if (artist.isNotEmpty()) " · $artist" else "", if (have != null) "Ya está en tu biblioteca" else Json.s(x["why"]), sk, strike = have != null)
                    Links {
                        if (have != null) PlayBtn("Escuchar", sk) { Buscar.playLib(have.id) }
                        else for ((n, u) in Buscar.links(title, artist)) LinkBtn(n, sk) { Nativo.openExternal(u) }
                        IconBtn(I_TRASH2, sk, size = 16f) { Deseos.del(Json.s(x["id"])) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- radios
@Composable
private fun RadiosTab(sk: Skin) {
    val p = sk.pal
    val r = Buscar.radio
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // el nombre y el país
        KeyRow(gap = 10.dp, field = { m -> SearchField(Buscar.rQ, { Buscar.rQChanged(it) }, "Nombre de la emisora…", sk, m) }) { CountrySelect(sk) }
        Chips { for ((id, n) in Buscar.TAGS) Chip(n, Buscar.rTag() == id, sk) { Buscar.setTag(id) } }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val favs = Buscar.favs().map { Buscar.station(it) }
            if (favs.isNotEmpty()) Panel(sk, gap = 10.dp) {
                Eyebrow("Mis radios", sk)
                Results { for (t in favs) StationRow(t, true, sk) }
            }
            val list = r.list
            when {
                r.loading && list == null -> Row(Modifier.fillMaxWidth().panel(sk).padding(18.dp + sk.b), verticalAlignment = Alignment.CenterVertically) { Thinking0(sk); Spacer(Modifier.width(16.dp)); T("Sintonizando emisoras…", 15f * sk.tk, p.fg, weight = FontWeight.Bold) }
                r.err.isNotEmpty() -> TextWithLink(listOf(r.err to false), "Reintentar", sk, 13.44f * sk.tk, p.muted, true) { Buscar.loadRadios() }
                list != null && list.isEmpty() -> Note("No hay emisoras con esos filtros. Prueba otro género o «Todo el mundo».", sk)
                list != null -> {
                    if (r.stale) TextWithLink(listOf("El directorio de radios no responde ahora mismo: te enseño la última lista que guardé." to false), "Reintentar", sk, 13.44f * sk.tk, p.muted, true) { Buscar.loadRadios() }
                    Results(Modifier.graphicsLayer { alpha = if (r.loading) 0.5f else 1f }) { for (t in list) StationRow(t, false, sk) }
                }
            }
        }
    }
}

/** Las barritas solas (para «Sintonizando…», sin columna de texto). */
@Composable
private fun Thinking0(sk: Skin) {
    val p = sk.pal
    val still = Nativo.reduceMotion()
    val ph = rememberInfiniteTransition(label = "eq0").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "e0").value
    Canvas(Modifier.size(41.dp, 28.dp)) {
        val dl = floatArrayOf(0f, 0.3f, 0.6f, 0.15f, 0.45f)
        for (i in 0 until 5) {
            val k = (ph + dl[i]) % 1f
            val h = (if (still) 14f else 6f + 22f * (0.5f - 0.5f * cos(k * 2f * PI.toFloat()))).dp.toPx()
            drawRoundRect(p.grad, Offset(i * 9.dp.toPx(), size.height - h), Size(5.dp.toPx(), h), CornerRadius(3.dp.toPx() * sk.round))
        }
    }
}

/** El país («select»): al tocarlo, la lista del móvil para elegir (como hace el navegador). */
@Composable
private fun CountrySelect(sk: Skin) {
    val p = sk.pal
    val cur = Buscar.rCC()
    val name = Buscar.CC.firstOrNull { it.first == cur }?.second ?: Buscar.CC[0].second
    val shape = sk.r(10f)
    // (el navegador le da el ancho de su opción más larga, con la flecha a la derecha)
    val tm = rememberTextMeasurer()
    val dens = LocalDensity.current
    val tst = style(13.12f * sk.tk, p.fg)
    val wMax = remember(sk.tk) { Buscar.CC.maxOf { tm.measure(it.second, tst).size.width } }
    val selW = with(dens) { wMax.toDp() } + 20.dp + 18.dp + sk.b * 2
    Row(Modifier.width(min(220f, selW.value).dp).press(0.98f) {
        val a = Nativo.act ?: return@press
        val names = Buscar.CC.map { it.second }.toTypedArray()
        val idx = Buscar.CC.indexOfFirst { it.first == Buscar.rCC() }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(a).setSingleChoiceItems(names, idx) { d, which -> d.dismiss(); val id = Buscar.CC[which].first; if (id != Buscar.rCC()) Buscar.setCC(id) }.show()
    }.clip(shape).background(p.panelSolid).border(sk.b, p.line2, shape).padding(start = 10.dp + sk.b, end = 8.dp + sk.b, top = 7.dp + sk.b, bottom = 7.dp + sk.b), verticalAlignment = Alignment.CenterVertically) {
        T(name, 13.12f * sk.tk, p.fg, Modifier.weight(1f), maxLines = 1)
        Ico("M6 9l6 6 6-6", 12f, p.fg, sw = 2f)
    }
}

/** El logo de una emisora («st-logo»): su imagen sobre blanco, o su inicial sobre el color de la app. */
@Composable
private fun StationLogo(t: Track, sk: Skin) {
    val p = sk.pal
    val side = 48
    val px = with(LocalDensity.current) { side.dp.roundToPx() }
    if (t.art.isNotEmpty()) LaunchedEffect(t.art) { NetImg.want(t.art, px) }
    val bmp = NetImg.got[t.art]
    Box(Modifier.size(side.dp).clip(sk.r(10f)).background(Brush.linearGradient(listOf(hsl(p.h, 0.8f, 0.5f, 0.5f), hsl(p.h - 45f, 0.8f, 0.3f, 0.5f)), start = Offset.Zero, end = Offset.Infinite)), contentAlignment = Alignment.Center) {
        T((t.title.trim().take(1).ifEmpty { "♪" }).uppercase(), 15f * sk.tk, Color.White, family = sk.display, weight = FontWeight.Black, lh = 1f)
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun StationRow(t: Track, fav: Boolean, sk: Skin) {
    val uuid = Json.s(t.extra["uuid"])
    val liked = Buscar.isFav(uuid)
    val br = Json.l(t.extra["bitrate"])
    ResultBox(sk, 1) {
        StationLogo(t, sk)
        RText(t.title, "", t.artist + (if (br > 0) " · $br kbps" else ""), sk)
        Links {
            IconBtn(I_HEART2, sk, on = liked, fill = liked) { Buscar.toggleFav(uuid) }
            PlayBtn("Escuchar", sk) { Buscar.playStation(t.id, fav) }
        }
    }
}

// ---------------------------------------------------------------- YouTube
@Composable
private fun YtTab(sk: Skin) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BigSearchBar(Buscar.ytQ, { Buscar.ytQ = it.take(300) }, "Pega un enlace de YouTube o busca…", "Ver", sk) { Buscar.ytGo() }
        Note("Pega el enlace de un vídeo o de una lista, o compártelo desde la app de YouTube con «Compartir › Órbita». No hace falta ninguna clave: el vídeo entra en tu cola, Nova lo presenta y después sigue tu música. Se ve con el reproductor oficial de YouTube, que se para si sales de Órbita o apagas la pantalla (YouTube no deja que suene en segundo plano).", sk)
        YtOut(sk)
        KeyPanel(sk)
    }
}

@Composable
private fun YtOut(sk: Skin) {
    val p = sk.pal
    val s = Tubo.search
    val recent = Tubo.recent()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (s != null) {
            if (s.loading) Row(Modifier.fillMaxWidth().panel(sk).padding(18.dp + sk.b), verticalAlignment = Alignment.CenterVertically) { Thinking0(sk); Spacer(Modifier.width(16.dp)); T("Buscando en YouTube…", 15f * sk.tk, p.fg, weight = FontWeight.Bold) }
            else if (s.err == "nokey") Box(Modifier.fillMaxWidth().clip(sk.r(14f)).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, sk.r(14f)).padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b)) {
                TextWithLink(listOf("Para buscar vídeos sin salir de Órbita hace falta una clave gratuita de YouTube (mira abajo). Sin clave puedes pegar un enlace o compartir el vídeo desde la app de YouTube con " to false, "Compartir › Órbita" to true, "." to false),
                    "Buscar en YouTube", sk, 14.4f * sk.tk, p.muted, false) { Nativo.openExternal("https://www.youtube.com/results?search_query=" + Online.enc(Buscar.ytQ)) }
            }
            else if (s.err.isNotEmpty()) Note(s.err, sk)
            val res = s.results
            if (res != null && res.isNotEmpty()) Results { for (v in res) YtRow(v, sk) }
        }
        if (recent.isNotEmpty()) Panel(sk, gap = 10.dp) {
            Eyebrow("Vistos hace poco", sk)
            Results { for (r in recent) { val id = Json.s(r["id"]); YtRow(Tubo.V(id, Json.s(r["list"]), Json.s(r["title"]), Json.s(r["author"]), thumb = if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else ""), sk) } }
        }
    }
}

@Composable
private fun YtRow(v: Tubo.V, sk: Skin) {
    val p = sk.pal
    ResultBox(sk, 1) {
        // la miniatura (16:9; sin ella, el triángulo)
        val w = 80
        val px = with(LocalDensity.current) { w.dp.roundToPx() }
        if (v.thumb.isNotEmpty()) LaunchedEffect(v.thumb) { NetImg.want(v.thumb, px / 2) }
        val bmp = NetImg.got[v.thumb]
        Box(Modifier.size(w.dp, (w * 9f / 16f).dp).clip(sk.r(8f)).background(Color(0xFF1A1430)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (v.thumb.isEmpty() || NetImg.failed[v.thumb] == true) T("▶", 15f * sk.tk, p.muted, lh = 1f)
        }
        RText(v.title.ifEmpty { if (v.list.isNotEmpty() && v.id.isEmpty()) "Lista de YouTube" else "Vídeo de YouTube" }, "", v.author, sk)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HBtn("Después", sk, kind = 1) { Tubo.playV(Tubo.V(v.id, v.list, v.title, v.author), "next") }
            HBtn("Ver", sk, primary = true, kind = 1) { Tubo.playV(Tubo.V(v.id, v.list, v.title, v.author), "now"); Ventanas.open("np") }
        }
    }
}

/** «Buscar vídeos sin salir de Órbita» (se abre al tocarlo, como un «details» de la web). */
@Composable
private fun KeyPanel(sk: Skin) {
    val p = sk.pal
    val has = Tubo.key().isNotEmpty()
    Panel(sk, gap = 12.dp) {
        Row(Modifier.fillMaxWidth().press(0.99f) { Buscar.keyOpen = !Buscar.keyOpen }, verticalAlignment = Alignment.CenterVertically) {
            // (el triángulo del «details»: a la derecha si está cerrado, hacia abajo si está abierto)
            val open = Buscar.keyOpen
            Canvas(Modifier.size(9.dp, 15f.times(sk.tk).times(1.45f).dp)) {
                val s = 7.5.dp.toPx(); val cx = size.width / 2; val cy = size.height / 2
                val path = Path()
                if (open) { path.moveTo(cx - s / 2, cy - s * 0.3f); path.lineTo(cx + s / 2, cy - s * 0.3f); path.lineTo(cx, cy + s * 0.45f) }
                else { path.moveTo(cx - s * 0.3f, cy - s / 2); path.lineTo(cx + s * 0.45f, cy); path.lineTo(cx - s * 0.3f, cy + s / 2) }
                path.close(); drawPath(path, p.muted)
            }
            Spacer(Modifier.width(5.dp))
            val txt = buildAnnotatedString { withStyle(SpanStyle(color = p.fg, fontWeight = FontWeight.Bold)) { append("Buscar vídeos sin salir de Órbita") }; append(" (opcional)") }
            BasicText(txt, Modifier.weight(1f).lines(15f * sk.tk * 1.45f), style = style(15f * sk.tk, p.muted))
        }
        if (Buscar.keyOpen) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Note("Hace falta una clave gratuita de la API de YouTube: entra en console.cloud.google.com, crea un proyecto, activa «YouTube Data API v3» y crea una clave de API. Se guarda solo en este móvil. La cuota gratuita da para unas 100 búsquedas al día.", sk)
            KeyRow(field = { m -> Field(Buscar.keyIn, { Buscar.keyIn = it }, sk, m, placeholder = if (has) "Clave guardada (escribe otra para cambiarla)" else "AIza…", password = true, onDone = { Buscar.saveKey() }) }) {
                HBtn("Guardar clave", sk, primary = true, kind = 1) { Buscar.saveKey() }
                if (has) HBtn("Quitar", sk, kind = 1) { Buscar.delKey() }
            }
        }
    }
}

