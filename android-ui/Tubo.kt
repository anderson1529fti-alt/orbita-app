package __PACKAGE__

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Fase 4, parte 2 · YouTube dentro de Órbita (lo que en la web era «3.2 · YouTube inside Órbita», la pestaña de
 * YouTube de «Buscar» y «el videoclip de la canción»). Un vídeo entra en la cola como una canción: Nova lo presenta, los
 * botones lo manejan y al acabar sigue la siguiente. Se ve con el reproductor oficial de YouTube, siempre a la vista
 * (así lo pide YouTube): en grande en «Ahora suena» y en pequeño encima del reproductor en lo demás. Fuera de Órbita
 * se para (YouTube no deja que suene en segundo plano) y sigue al volver. Se maneja con los mensajes que entiende su
 * reproductor: dentro de Órbita no corre nada de YouTube. Este WebView es lo único de web que queda en la app.
 */
object Tubo {
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val ERR = mapOf(2 to "el enlace del vídeo no es válido", 5 to "este móvil no puede reproducir ese vídeo", 100 to "ese vídeo ya no existe o es privado",
        101 to "quien lo subió no deja verlo fuera de YouTube", 150 to "quien lo subió no deja verlo fuera de YouTube", 153 to "YouTube no deja reproducirlo aquí")
    private val RE = Regex("(?:youtube\\.com/(?:watch\\?(?:[^#\\s]*&)?v=|embed/|shorts/|live/|v/)|youtu\\.be/|music\\.youtube\\.com/watch\\?(?:[^#\\s]*&)?v=)([A-Za-z0-9_-]{11})")
    private val LIST_RE = Regex("[?&]list=([A-Za-z0-9_-]{10,64})")

    /** La clave de la API de YouTube (opcional: para buscar vídeos sin salir de Órbita). */
    fun key(): String = Prefs.str("ytKey", "")

    // ---------------------------------------------------------------- los enlaces
    fun idFrom(text: String): String? { val s = text.trim(); return RE.find(s)?.groupValues?.get(1) ?: if (Regex("^[A-Za-z0-9_-]{11}$").matches(s)) s else null }
    fun listFrom(text: String): String? = LIST_RE.find(text)?.groupValues?.get(1)
    /** Un enlace de YouTube → (vídeo, lista) (las mezclas de radio y las listas privadas no se pueden poner fuera de YouTube). */
    fun linkFrom(text: String): Pair<String, String>? {
        val id = idFrom(text); var list = listFrom(text)
        if (list != null && Regex("^(RD|LL|WL|UL)").containsMatchIn(list)) list = null
        return if (id != null || list != null) (id ?: "") to (list ?: "") else null
    }

    // ---------------------------------------------------------------- los vídeos en la cola
    class V(val id: String, val list: String = "", val title: String = "", val author: String = "", val clipOf: String? = null, val thumb: String = "")

    fun track(v: V): Track {
        val key = if (v.id.isNotEmpty()) v.id + (if (v.list.isNotEmpty()) "-" + v.list else "") else "lista-" + v.list
        val id = "yt:$key"
        val t = Lib.net[id] ?: Track(id).also { Lib.net[id] = it }
        t.net = true; t.kind = "yt"; t.url = "yt:" + v.id + (if (v.list.isNotEmpty()) "?list=" + v.list else "")
        if (!Json.b(t.extra["titled"])) { t.title = v.title.ifEmpty { if (v.list.isNotEmpty() && v.id.isEmpty()) "Lista de YouTube" else "Vídeo de YouTube" }; t.artist = v.author.ifEmpty { "YouTube" } }
        t.extra["ytId"] = v.id; t.extra["ytList"] = v.list; if (v.clipOf != null) t.extra["clipOf"] = v.clipOf
        if (v.title.isNotEmpty()) t.extra["titled"] = true
        if (v.id.isNotEmpty()) { t.art = "https://i.ytimg.com/vi/${v.id}/hqdefault.jpg"; t.artBig = t.art }
        if (v.title.isEmpty() && v.id.isNotEmpty() && !Json.b(t.extra["titled"])) net.execute {
            val info = try { Json.obj(Online.getJson("https://www.youtube.com/oembed?url=${Online.enc("https://www.youtube.com/watch?v=" + v.id)}&format=json")) } catch (e: Throwable) { null }
            if (info != null && !Json.b(t.extra["titled"])) main.post {
                t.title = Json.s(info["title"]).take(140).ifEmpty { t.title }; t.artist = Json.s(info["author_name"]).take(80).ifEmpty { t.artist }; t.extra["titled"] = true
                val r = recent().map { LinkedHashMap(it) }; r.firstOrNull { Json.s(it["id"]) == v.id && Json.s(it["list"]) == v.list }?.let { it["title"] = t.title; it["author"] = t.artist; Prefs.set("ytRecent", r) }
                if (Player.current() === t) { Player.barSync(); Player.netState(true) }
                Nativo.onQueue(); Ver.bump()
            }
        }
        return t
    }
    fun recent(): List<Map<String, Any?>> = (Json.list(Prefs.get("ytRecent")) ?: emptyList()).mapNotNull { Json.obj(it) }
    private fun remember(v: V) {
        val rec = linkedMapOf<String, Any?>("id" to v.id, "list" to v.list, "title" to v.title, "author" to v.author, "ts" to System.currentTimeMillis())
        Prefs.set("ytRecent", (listOf<Map<String, Any?>>(rec) + recent().filter { !(Json.s(it["id"]) == v.id && Json.s(it["list"]) == v.list) }).take(12)); Ver.bump()
    }
    fun intro(t: Track): String {
        val clip = Json.s(t.extra["clipOf"])
        if (clip.isNotEmpty()) { val s = Lib.byId(clip); return Cerebro.pick(listOf("El videoclip de «${s?.title ?: t.title}». Disfrútalo.", "Vamos con el videoclip${if (s != null) " de " + s.title else ""}.")) }
        if (Json.s(t.extra["ytList"]).isNotEmpty() && Json.s(t.extra["ytId"]).isEmpty()) return Cerebro.pick(listOf("Ponemos tu lista de YouTube.", "Vamos con esa lista de YouTube."))
        if (!Json.b(t.extra["titled"])) return Cerebro.pick(listOf("Te pongo el vídeo que me has pasado.", "Vamos con ese vídeo de YouTube."))
        return Cerebro.pick(listOf("Del YouTube: «${t.title}».", "Vamos con un vídeo: «${t.title}».", "Te pongo el vídeo: «${t.title}»."))
    }

    /** «ytPlay»: ahora (lo demás de la cola sigue después) o «después» (tras la canción de ahora). */
    fun playV(v: V, how: String = "now") {
        if (v.id.isEmpty() && v.list.isEmpty()) return
        if (!Online.on()) { Nativo.openExternal(if (v.id.isNotEmpty()) "https://www.youtube.com/watch?v=${v.id}" else "https://www.youtube.com/playlist?list=${v.list}"); return }
        val t = track(v); remember(v)
        if (how == "next" && Player.current() != null) {
            Player.playNext(t.id)
            Nativo.toast("Después de esta: ${if (Json.b(t.extra["titled"])) "«${t.title}»" else "el vídeo de YouTube"}"); Nativo.haptic(longArrayOf(0, 8))
            return
        }
        val talk = if (Nova.intros() && Nova.voiceOn()) "@yt" to "saludo" else null
        if (Player.current() == null || Player.queue.isEmpty()) { Player.playQueue(listOf(t.id), 0, "youtube", talk); return }
        Player.playNext(t.id); Player.pendingTalk = talk; Player.next(false)
    }

    /** ✕ en el vídeo pequeño: sigue tu música (o, si no viene nada después, el vídeo sale de la cola). */
    fun close() {
        val t = Player.current() ?: return
        if (t.kind != "yt") return
        if (Player.qi + 1 < Player.queue.size) { Player.next(false); return }
        Player.dropCurrent()
    }

    // ---------------------------------------------------------------- buscar en YouTube (con clave)
    class Search(val q: String) { var loading = true; var results: List<V>? = null; var err = "" }
    var search by mutableStateOf<Search?>(null); private set

    fun doSearch(q: String, then: ((Search) -> Unit)? = null) {
        val k = key()
        val s = Search(q)
        if (k.isEmpty()) { s.loading = false; s.err = "nokey"; search = s; return }
        // (mientras busca, siguen a la vista los resultados de antes)
        s.results = search?.results
        search = s; Ver.bump()
        net.execute {
            try {
                val j = Json.obj(Online.getJson("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=15&q=${Online.enc(q)}&key=${Online.enc(k)}", throwStatus = true))
                s.results = (Json.list(j?.get("items")) ?: emptyList()).mapNotNull { Json.obj(it) }.mapNotNull { x ->
                    val vid = Json.s(Json.obj(x["id"])?.get("videoId")); if (vid.isEmpty()) return@mapNotNull null
                    val sn = Json.obj(x["snippet"])
                    V(vid, "", unescape(Json.s(sn?.get("title"))), unescape(Json.s(sn?.get("channelTitle"))), thumb = Json.s(Json.obj(Json.obj(sn?.get("thumbnails"))?.get("medium"))?.get("url")))
                }
            } catch (e: Online.HttpError) { s.results = emptyList(); s.err = errorText(e) }
            catch (e: Throwable) { s.results = emptyList(); s.err = "No he podido buscar en YouTube. ¿Tienes internet?" }
            s.loading = false
            main.post { if (search === s) { search = Search(q).also { it.loading = false; it.results = s.results; it.err = s.err } }; Ver.bump(); then?.invoke(s) }
        }
    }
    private fun unescape(s: String): String = try { android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString() } catch (t: Throwable) { s }
    /** Lo que dice Google de la clave, dicho para poder arreglarlo. */
    private fun errorText(e: Online.HttpError): String {
        val d = Json.obj(Json.parseOr(e.body, null)); val er = Json.obj(d?.get("error")) ?: emptyMap()
        val msg = Json.s(er["message"])
        val reasons = ((Json.list(er["errors"]) ?: emptyList()) + (Json.list(er["details"]) ?: emptyList())).mapNotNull { Json.obj(it) }.map { Json.s(it["reason"]) }.filter { it.isNotEmpty() }.joinToString(" ")
        val all = "$reasons $msg"
        fun has(re: String) = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(all)
        return when {
            has("keyInvalid|API_KEY_INVALID|API key not valid") -> "Google dice que la clave no es válida: seguramente se copió con alguna letra cambiada. Cópiala otra vez desde la consola de Google con el botón de copiar (no a mano ni con Google Lens) y pégala aquí."
            has("accessNotConfigured|SERVICE_DISABLED|has not been used|is disabled") -> "La clave está bien, pero falta activar «YouTube Data API v3» en ese proyecto de Google (Biblioteca › YouTube Data API v3 › Habilitar). Si acabas de activarla, espera 5 minutos."
            has("quotaExceeded|dailyLimitExceeded|rateLimitExceeded") -> "Se han gastado las búsquedas gratuitas de hoy. Mañana vuelven. Mientras, puedes pegar enlaces o compartir vídeos desde YouTube."
            has("API_KEY_SERVICE_BLOCKED|ipRefererBlocked|API_KEY_ANDROID_APP_BLOCKED|API_KEY_HTTP_REFERRER_BLOCKED|blocked|forbidden") -> "La clave tiene restricciones que bloquean a Órbita. En la consola de Google, edita la clave: en «Restricciones de aplicaciones» pon «Ninguna» y en «Restricciones de API» deja solo YouTube Data API v3."
            else -> "YouTube ha rechazado la búsqueda (error ${e.status}${if (msg.isNotEmpty()) ": " + msg.take(120) else ""})."
        }
    }

    /** «ytFind»: busca y pone el primero (con clave); si no, la búsqueda en YouTube. */
    fun find(q: String) {
        if (Online.on() && key().isNotEmpty()) {
            Buscar.tab = "youtube"; Buscar.ytQ = q; Nativo.go("buscar")
            doSearch(q) { s -> s.results?.firstOrNull()?.let { v -> playV(v, "now"); Ventanas.open("np") } }
        } else Nativo.openExternal("https://www.youtube.com/results?search_query=" + Online.enc(q))
    }

    // ---------------------------------------------------------------- el videoclip de una canción
    private fun cleanT(s: String): String = s.replace(Regex("\\s*[(\\[][^)\\]]*(feat|ft\\.|with|remaster|live|en vivo|version|versión|video|vídeo|audio|lyric|letra|official|oficial)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+-\\s+(remaster|live|en vivo|radio edit|single version).*$", RegexOption.IGNORE_CASE), "").trim()
    private fun clipKey(t: Track) = Biblio.norm(t.artist) + "|" + Biblio.norm(cleanT(t.title))
    private fun clipGet(t: Track): String? { val m = Json.obj(Prefs.get("ytClips")) ?: return null; return if (m.containsKey(clipKey(t))) Json.s(m[clipKey(t)]) else null }
    fun clipSet(t: Track, id: String) {
        val m = LinkedHashMap(Json.obj(Prefs.get("ytClips")) ?: emptyMap()); m[clipKey(t)] = id
        if (m.size > 400) { val drop = m.keys.take(m.size - 400); for (k in drop) m.remove(k) }
        Prefs.set("ytClips", m)
    }
    private fun artistMatch(a: String, names: List<String>): Boolean { val A2 = Biblio.norm(a); return names.any { val N = Biblio.norm(it); N.length >= 3 && (A2.contains(N) || (A2.length >= 3 && N.contains(A2))) } }
    private fun clipWikidata(t: Track): String? {
        val W = "https://www.wikidata.org/w/api.php?format=json&origin=*&"; val title = cleanT(t.title)
        if (title.isEmpty() || t.artist.isEmpty()) return null
        val s = Json.obj(Online.getJson(W + "action=query&list=search&srlimit=10&srsearch=" + Online.enc("$title haswbstatement:P1651"), mapOf("Api-User-Agent" to Online.UA)))
        val ids = (Json.list(Json.obj(s?.get("query"))?.get("search")) ?: emptyList()).mapNotNull { Json.obj(it) }.map { Json.s(it["title"]) }.filter { Regex("^Q\\d+$").matches(it) }
        if (ids.isEmpty()) return null
        val e = Json.obj(Online.getJson(W + "action=wbgetentities&props=claims|labels&languages=es|en&ids=" + ids.joinToString("|"), mapOf("Api-User-Agent" to Online.UA)))
        val ents = ids.mapNotNull { Json.obj(Json.obj(e?.get("entities"))?.get(it)) }
        fun claim(en: Map<String, Any?>, p: String): List<Any?> = (Json.list(Json.obj(en["claims"])?.get(p)) ?: emptyList()).mapNotNull { Json.obj(it) }.mapNotNull { Json.obj(Json.obj(it["mainsnak"])?.get("datavalue"))?.get("value") }
        fun labels(en: Map<String, Any?>): List<String> = (Json.obj(en["labels"]) ?: emptyMap()).values.mapNotNull { Json.obj(it) }.map { Json.s(it["value"]) }.filter { it.isNotEmpty() }
        val T = Biblio.norm(title)
        val cands = ents.filter { en -> claim(en, "P1651").any { Regex("^[A-Za-z0-9_-]{11}$").matches(Json.s(it)) } && labels(en).any { l -> val L = Biblio.norm(cleanT(l)); L == T || (T.length >= 4 && (L.startsWith(T) || T.startsWith(L)) && minOf(L.length, T.length).toDouble() / maxOf(L.length, T.length) > 0.6) } }
        if (cands.isEmpty()) return null
        val perf = cands.flatMap { en -> claim(en, "P175").mapNotNull { Json.s(Json.obj(it)?.get("id")).takeIf { x -> Regex("^Q\\d+$").matches(x) } } }.distinct().take(45)
        val names = HashMap<String, List<String>>()
        if (perf.isNotEmpty()) {
            val p = Json.obj(Online.getJson(W + "action=wbgetentities&props=labels&languages=es|en&ids=" + perf.joinToString("|"), mapOf("Api-User-Agent" to Online.UA)))
            for (id in perf) names[id] = labels(Json.obj(Json.obj(p?.get("entities"))?.get(id)) ?: emptyMap())
        }
        val hit = cands.firstOrNull { en -> artistMatch(t.artist, claim(en, "P175").mapNotNull { Json.s(Json.obj(it)?.get("id")) }.flatMap { names[it] ?: emptyList() }) }
        return hit?.let { h -> claim(h, "P1651").map { Json.s(it) }.firstOrNull { Regex("^[A-Za-z0-9_-]{11}$").matches(it) } }
    }
    private fun clipSearch(t: Track): String? {
        val k = key(); if (k.isEmpty()) return null
        val j = Json.obj(Online.getJson("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoEmbeddable=true&maxResults=5&q=${Online.enc("${t.artist} ${cleanT(t.title)} official video")}&key=${Online.enc(k)}"))
        val items = (Json.list(j?.get("items")) ?: emptyList()).mapNotNull { Json.obj(it) }.filter { Regex("^[A-Za-z0-9_-]{11}$").matches(Json.s(Json.obj(it["id"])?.get("videoId"))) }
        val T = Biblio.norm(cleanT(t.title))
        val good = items.firstOrNull { Biblio.norm(unescape(Json.s(Json.obj(it["snippet"])?.get("title")))).contains(T) } ?: items.firstOrNull()
        return good?.let { Json.s(Json.obj(it["id"])?.get("videoId")) }
    }
    var clipBusy by mutableStateOf(false); private set
    private var clipWait: Pair<String, Long>? = null
    /** «Videoclip» en el reproductor: lo busca (con clave en YouTube; si no, en Wikidata) y lo pone. */
    fun clip(t: Track?) {
        if (t == null || t.net || clipBusy) return
        if (!Online.on()) { Nativo.openExternal("https://www.youtube.com/results?search_query=" + Online.enc("${t.artist} ${t.title}")); return }
        val known = clipGet(t)
        val go = { id: String? ->
            if (id.isNullOrEmpty()) {
                clipWait = t.id to System.currentTimeMillis()
                Nativo.toast("No he encontrado su videoclip. Búscalo en YouTube y compártelo con Órbita: lo dejaré guardado para esta canción.", 6500)
                main.postDelayed({ Nativo.openExternal("https://www.youtube.com/results?search_query=" + Online.enc("${t.artist} ${cleanT(t.title)}")) }, 1800)
            } else playV(V(id, "", "${cleanT(t.title)} (videoclip)", t.artist, t.id), "now")
        }
        if (!known.isNullOrEmpty()) { go(known); return }
        clipBusy = true
        Nativo.toast("Buscando el videoclip…", 4000)
        net.execute {
            val id = try { clipSearch(t) } catch (e: Throwable) { null } ?: try { clipWikidata(t) } catch (e: Throwable) { null }
            main.post { clipBusy = false; if (Player.current() !== t) return@post; if (!id.isNullOrEmpty()) clipSet(t, id); go(id) }
        }
    }
    /** Un vídeo compartido justo después de pedir el videoclip de una canción pasa a ser el de esa canción. */
    fun clipFromShare(id: String): Track? {
        val w = clipWait; clipWait = null
        if (w == null || System.currentTimeMillis() - w.second > 20 * 60000) return null
        val t = Lib.byId(w.first); if (t == null || t.net) return null
        clipSet(t, id); return t
    }

    // ---------------------------------------------------------------- el reproductor de YouTube (en su WebView)
    private var web: WebView? = null
    private var box: FrameLayout? = null
    private var barView: ComposeView? = null
    private var gen = 0
    private var src = ""
    private var trackId = ""
    private var ready = false; private var heard = false
    var noCtl by mutableStateOf(false); private set
    private val pend = ArrayList<JSONObject>()
    private var state = -2
    private var tSec = 0.0; private var tAt = 0L; private var durS = 0.0
    @Volatile var playing = false; private set
    private var auto = false
    private var vol = 1f; private var sentVol = -1
    private var list: JSONArray? = null; private var idx = -1; private var vid = ""
    var title by mutableStateOf(""); private set

    private const val HOST = "<!doctype html><html><head><meta name=viewport content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;left:0;top:0;width:100%;height:100%;border:0}</style></head><body><script>" +
        "var f=null;function load(u){if(f)f.remove();f=document.createElement('iframe');f.src=u;f.title='Vídeo de YouTube';f.setAttribute('frameborder','0');f.allowFullscreen=true;" +
        "f.setAttribute('allow','autoplay; encrypted-media; picture-in-picture; fullscreen');f.setAttribute('referrerpolicy','strict-origin-when-cross-origin');f.addEventListener('load',function(){OrbitaYT.loaded()});document.body.appendChild(f);}" +
        "function post(s){try{if(f&&f.contentWindow)f.contentWindow.postMessage(s,'*')}catch(e){}}" +
        "addEventListener('message',function(e){if(!f||e.source!==f.contentWindow)return;OrbitaYT.msg(String(e.origin),typeof e.data==='string'?e.data:JSON.stringify(e.data))});" +
        "OrbitaYT.ready();</script></body></html>"
    private var hostReady = false
    private val hostWait = ArrayList<String>()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensure(): WebView? {
        web?.let { return it }
        val a = Nativo.act ?: return null
        // (encima de las ventanas nativas; lo que no es el vídeo deja pasar los toques)
        val parent = (Nativo.ui?.bridge?.webView?.parent as? ViewGroup) ?: a.findViewById(android.R.id.content) ?: return null
        val layer = FrameLayout(a)
        layer.translationZ = 55f
        parent.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val w = WebView(a)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                // (un toque en «ver en YouTube» dentro del vídeo: a la app de YouTube)
                val u = request?.url?.toString() ?: return false
                if (request.isForMainFrame && !u.startsWith("https://localhost")) { Nativo.openExternal(u); return true }
                return false
            }
        }
        w.addJavascriptInterface(object {
            @JavascriptInterface fun ready() { main.post { hostReady = true; val l = ArrayList(hostWait); hostWait.clear(); for (s in l) js(s) } }
            @JavascriptInterface fun loaded() { main.post { onLoaded() } }
            @JavascriptInterface fun msg(origin: String, data: String) { main.post { onMsg(origin, data) } }
        }, "OrbitaYT")
        val fl = FrameLayout(a)
        fl.clipToOutline = true
        fl.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, view.width, view.height, cornerPx) }
        }
        fl.setBackgroundColor(android.graphics.Color.BLACK)
        fl.isClickable = true
        val bar = ComposeView(a)
        bar.setContent { DockBar() }
        fl.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fl.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fl.visibility = View.GONE
        layer.addView(fl, FrameLayout.LayoutParams(10, 10))
        box = fl; barView = bar; web = w; this.layer = layer
        hostReady = false
        w.loadDataWithBaseURL("https://localhost/", HOST, "text/html", "utf-8", null)
        return w
    }
    private var layer: FrameLayout? = null
    private var cornerPx = 0f

    private fun js(s: String) { val w = web ?: return; if (!hostReady) { hostWait.add(s); return }; try { w.evaluateJavascript(s, null) } catch (ignored: Throwable) { } }
    private fun post(o: JSONObject) { js("post(${JSONObject.quote(o.toString())})") }
    private fun cmd(func: String, args: JSONArray = JSONArray()) {
        val m = JSONObject().put("event", "command").put("func", func).put("args", args).put("id", 1).put("channel", "widget")
        if (ready) { post(m); return }
        if (src.isEmpty()) return
        val grp = { x: String -> if (x == "playVideo" || x == "pauseVideo") "pp" else x }
        pend.removeAll { grp(it.optString("func")) == grp(func) }; pend.add(m)
    }

    /** Pone el vídeo de «t» (en su sitio: grande con «Ahora suena» abierto, pequeño si no). */
    fun play(t: Track, autoplay: Boolean) {
        val w = ensure() ?: return
        val my = ++gen
        src = t.url; trackId = t.id; reset()
        playing = autoplay; title = t.title
        val v = Json.s(t.extra["ytId"]); val l = Json.s(t.extra["ytList"])
        if (v.isEmpty() && l.isEmpty()) { fail(2); return }
        val q = StringBuilder("enablejsapi=1&playsinline=1&rel=0&iv_load_policy=3&hl=es&autoplay=${if (autoplay) 1 else 0}&origin=https%3A%2F%2Flocalhost&widget_referrer=https%3A%2F%2Flocalhost")
        if (l.isNotEmpty()) q.append("&list=").append(l)
        js("load(${JSONObject.quote("https://www.youtube.com/embed/${v.ifEmpty { "videoseries" }}?$q")})")
        place()
        // (si su reproductor no contesta, sigue siendo un reproductor de YouTube normal: se maneja con sus botones)
        main.postDelayed({ if (my == gen && !heard) { noCtl = true; place() } }, 15000)
        @Suppress("UNUSED_VARIABLE") val unused = w
    }
    private fun reset() {
        ready = false; heard = false; noCtl = false; pend.clear(); state = -2; tSec = 0.0; tAt = SystemClock.uptimeMillis(); durS = 0.0; sentVol = -1
        list = null; idx = -1; vid = ""
    }
    private var listenN = 0
    private fun onLoaded() {
        val my = gen; listenN = 0
        val say = object : Runnable { override fun run() {
            if (my != gen || heard || ++listenN > 40) return
            post(JSONObject().put("event", "listening").put("id", 1).put("channel", "widget"))
            main.postDelayed(this, 300)
        } }
        main.postDelayed(say, 100)
    }
    private fun onMsg(origin: String, data: String) {
        if (origin != "https://www.youtube.com" && origin != "https://www.youtube-nocookie.com") return
        val m = try { JSONObject(data) } catch (t: Throwable) { return }
        heard = true
        val ev = m.optString("event")
        if (!ready && (ev == "onReady" || ev == "initialDelivery" || ev == "infoDelivery")) onReady()
        if (ev == "onError") { fail(m.optInt("info", 0)); return }
        if (ev == "onStateChange" && m.opt("info") is Number) onState(m.optInt("info"))
        val info = m.optJSONObject("info"); if (info != null) onInfo(info)
    }
    private fun onReady() {
        ready = true
        for (e in listOf("onStateChange", "onError")) post(JSONObject().put("event", "command").put("func", "addEventListener").put("args", JSONArray().put(e)).put("id", 1).put("channel", "widget"))
        val p = ArrayList(pend); pend.clear()
        sentVol = -1; setVolume(vol)
        for (m in p) if (m.optString("func") != "setVolume") post(m)
        if (playing) post(JSONObject().put("event", "command").put("func", "playVideo").put("args", JSONArray()).put("id", 1).put("channel", "widget"))
    }
    private fun onInfo(i: JSONObject) {
        val ps = i.optJSONObject("progressState")
        val cur = if (ps != null && ps.has("current")) ps.optDouble("current") else if (i.has("currentTime")) i.optDouble("currentTime") else Double.NaN
        if (!cur.isNaN()) { tSec = cur; tAt = SystemClock.uptimeMillis() }
        val d = i.optDouble("duration", ps?.optDouble("duration", 0.0) ?: 0.0)
        if (d > 0 && Math.abs(d - durS) > 0.5) durS = d
        val vd = i.optJSONObject("videoData")
        if (vd != null) {
            if (vd.optInt("errorCode", 0) != 0) { fail(vd.optInt("errorCode")); return }
            val v = vd.optString("video_id")
            if (v.isNotEmpty() && (v != vid || (vd.optString("title").isNotEmpty() && vd.optString("title") != title))) {
                vid = v
                val t = Player.current()
                if (t != null && t.id == trackId && (Json.s(t.extra["ytList"]).isNotEmpty() || !Json.b(t.extra["titled"]))) {
                    // (dentro de una lista de YouTube, cada vídeo trae su título)
                    t.title = vd.optString("title").ifEmpty { t.title }; t.artist = vd.optString("author").ifEmpty { t.artist }; t.extra["titled"] = true
                    t.art = "https://i.ytimg.com/vi/$v/hqdefault.jpg"; t.artBig = t.art
                    title = t.title; Player.barSync(); Player.netState(true)
                }
            }
        }
        i.optJSONArray("playlist")?.let { list = it }
        if (i.has("playlistIndex")) idx = i.optInt("playlistIndex", -1)
        if (i.has("playerState")) onState(i.optInt("playerState"))
    }
    private fun onState(s: Int) {
        if (s == state) return
        state = s
        when (s) {
            1 -> { tAt = SystemClock.uptimeMillis(); if (!Nativo.awake) { auto = true; pause(); return }; playing = true }
            2 -> { snap(); playing = false }
            3 -> snap()
            0 -> {
                val l = list
                // (una lista de YouTube sigue sola con su vídeo siguiente: solo el último la termina)
                if (l != null && idx >= 0 && idx < l.length() - 1) {
                    val my = gen
                    main.postDelayed({ if (my != gen || state != 0) return@postDelayed; cmd("nextVideo"); main.postDelayed({ if (my == gen && state == 0) ended() }, 5000) }, 4000)
                    return
                }
                ended()
            }
        }
    }
    private fun snap() { tSec = pos(); tAt = SystemClock.uptimeMillis() }
    private fun ended() { if (durS > 0) tSec = durS; playing = false; Player.netEnded(trackId) }
    private fun fail(code: Int) {
        playing = false
        Nativo.toast("YouTube: ${ERR[code] ?: "no ha podido reproducirlo ahora mismo"}.", 4200)
        Player.netEnded(trackId, failed = true)
    }

    fun pause() { cmd("pauseVideo"); if (playing) { snap(); playing = false } }
    fun resume() { if (src.isEmpty()) return; if (!Nativo.awake) { auto = true; return }; playing = true; cmd("playVideo") }
    fun seek(sec: Double) { tSec = sec; tAt = SystemClock.uptimeMillis(); cmd("seekTo", JSONArray().put(sec).put(true)) }
    fun pos(): Double { val t = tSec + (if (playing && state == 1) (SystemClock.uptimeMillis() - tAt) / 1000.0 else 0.0); return if (durS > 0) minOf(t, durS) else t }
    fun dur(): Double = durS
    fun setVolume(v: Float) { vol = v.coerceIn(0f, 1f); val p = Math.round(vol * 100); if (p == sentVol) return; sentVol = p; cmd("setVolume", JSONArray().put(p)) }
    /** Fuera el vídeo (vuelve la música de siempre). */
    fun stop() {
        if (src.isEmpty() && web == null) return
        gen++; src = ""; trackId = ""; playing = false; reset()
        js("if(f){f.remove();f=null}")
        place()
    }

    /** La app se va (otra app, pantalla apagada): el vídeo espera; al volver, sigue. */
    fun onAway() { if (Player.mode == 3 && playing) { auto = true; pause(); Player.netState(true) } }
    fun onBack() { if (auto) { auto = false; if (Player.mode == 3 && src.isNotEmpty()) { Player.resume() } } }

    // ---------------------------------------------------------------- dónde se ve
    /** El sitio del vídeo en «Ahora suena» (en px de la pantalla), o null si no se ve. */
    private var slot: android.graphics.RectF? = null
    fun slotAt(r: android.graphics.RectF?) { if (r == slot) return; slot = r; place() }

    /** El vídeo pequeño está encima del reproductor: las pantallas dejan sitio debajo para verlo todo («yt-docked»). */
    var docked by mutableStateOf(false); private set
    fun viewBottom(): androidx.compose.ui.unit.Dp = if (docked) 300.dp else 48.dp

    fun place() {
        val fl = box ?: return
        val a = Nativo.act ?: return
        val on = src.isNotEmpty() && Player.mode == 3
        docked = on && !("np" in Ventanas.stack && Ventanas.npOpen)
        if (!on) { fl.visibility = View.GONE; return }
        val d = a.resources.displayMetrics.density
        val big = "np" in Ventanas.stack && Ventanas.npOpen
        val r = slot
        val lp = fl.layoutParams as FrameLayout.LayoutParams
        val wl = web?.layoutParams as? FrameLayout.LayoutParams
        if (big) {
            if (r == null) { fl.visibility = View.GONE; return }
            barView?.visibility = View.GONE
            wl?.let { it.topMargin = 0; it.bottomMargin = 0; web?.layoutParams = it }
            cornerPx = 14f * UiState.look.round * d
            // (el sitio llega en coordenadas de la ventana; la capa del vídeo puede no empezar en su esquina)
            val at = IntArray(2); layer?.getLocationInWindow(at)
            lp.width = r.width().toInt(); lp.height = r.height().toInt(); lp.leftMargin = r.left.toInt() - at[0]; lp.topMargin = r.top.toInt() - at[1]
        } else {
            // pequeño, encima del reproductor de abajo (con su barra: el título, «en grande» y «quitar»)
            barView?.visibility = View.VISIBLE
            val barH = (44 * d).toInt(); val noteH = if (noCtl) (30 * d).toInt() else 0
            wl?.let { it.topMargin = barH; it.bottomMargin = noteH; web?.layoutParams = it }
            cornerPx = 16f * UiState.look.round * d
            val W = (layer?.width ?: 0).takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels
            val H = (layer?.height ?: 0).takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels
            val w = minOf(W - (16 * d).toInt(), (520 * d).toInt())
            val playerTop = H - ((Bar.PLAYER + Bar.NAV + Bar.inset) * d).toInt()
            lp.width = w; lp.height = barH + (200 * d).toInt() + noteH
            lp.leftMargin = maxOf((8 * d).toInt(), W - w - (8 * d).toInt())
            lp.topMargin = maxOf(0, playerTop - (8 * d).toInt() - lp.height)
        }
        fl.layoutParams = lp
        fl.visibility = View.VISIBLE
        fl.invalidateOutline()
    }

    @androidx.compose.runtime.Composable
    private fun DockBar() {
        val sk = rememberSkin()
        val p = sk.pal
        Column(Modifier.fillMaxSize().background(Color(0xF70A0714))) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF0033)), contentAlignment = Alignment.Center) { Ico("M8 5v14l11-7z", 14f, Color.White, fill = true) }
                T(title.ifEmpty { "YouTube" }, 14.4f * sk.tk, p.fg, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 1)
                Box(Modifier.size(40.dp).press { Ventanas.open("np") }, contentAlignment = Alignment.Center) { Ico("M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7", 18f, p.muted, sw = 2.2f) }
                Box(Modifier.size(40.dp).press { close() }, contentAlignment = Alignment.Center) { Ico("M6 6l12 12M18 6L6 18", 18f, p.muted, sw = 2.4f) }
            }
            Box(Modifier.weight(1f))
            if (noCtl) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { Note("Este vídeo se maneja con sus propios botones.", sk) }
        }
    }
}
