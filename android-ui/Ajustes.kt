package __PACKAGE__

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Fase 4, parte 2 · Lo que hace «Ajustes» (lo que en la web estaba repartido: «renderSound», «setEq», «renderLook»,
 * «setCity», «renderPerms», «checkUpdate», «diagCopy»…).
 */
object Ajustes {
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    /** Un ajuste cambiado: se guarda y la pantalla se vuelve a pintar. */
    fun set(k: String, v: Any?) {
        Prefs.set(k, v)
        when (k) {
            "lkForYou", "lkMixer", "lkTicker", "heroMode" -> Inicio.mark()
            "djFreq", "who" -> Inicio.mark()
            "anim" -> { Nativo.look(); Inicio.mark() }
            "aDirect", "aLevel", "mixStyle" -> { Player.applyEq(); Player.resync(); Inicio.mark() }
        }
        Ver.bump()
    }

    // ---------------------------------------------------------------- la versión («APP_CFG»)
    private var cfgCache: Map<String, Any?>? = null
    /** Lo que la compilación apuntó en «app-config.js»: el repositorio, la versión, el número y si es beta. */
    fun cfg(): Map<String, Any?> {
        cfgCache?.let { return it }
        val a = Nativo.act
        val txt = try { a?.assets?.open("public/app-config.js")?.bufferedReader()?.use { it.readText() } ?: "" } catch (t: Throwable) { "" }
        val j = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(txt)?.value
        val m = Json.obj(Json.parseOr(j ?: "{}", null)) ?: emptyMap()
        val out = LinkedHashMap(m)
        if (Json.s(out["version"]).isEmpty()) try { val pi = a?.packageManager?.getPackageInfo(a.packageName, 0); out["version"] = pi?.versionName ?: "" } catch (ignored: Throwable) { }
        cfgCache = out
        return out
    }
    fun version(): String = Json.s(cfg()["version"])
    fun versionLine(): String { val v = version(); return (if (v.isNotEmpty()) "Órbita $v" + (if (Json.b(cfg()["beta"])) " (beta)" else "") else "Órbita") + " · cerebro grande: " + (if (CerebroGrande.included()) "incluido" else "no incluido") }

    // ---------------------------------------------------------------- las actualizaciones («checkUpdate»)
    class Update(val version: String, val url: String, val notes: String)
    @Volatile var update: Update? = null; private set

    fun checkUpdate(manual: Boolean) {
        val repo = Json.s(cfg()["repo"])
        if (repo.isEmpty()) { if (manual) Nativo.toast("Esta versión no tiene configurado un repositorio de actualizaciones."); return }
        net.execute {
            try {
                val j = Json.obj(Online.getJson("https://api.github.com/repos/$repo/releases/latest")) ?: throw IllegalStateException("sin datos")
                val build = Regex("(\\d+)$").find(Json.s(j["tag_name"]))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val apk = (Json.list(j["assets"]) ?: emptyList()).mapNotNull { Json.obj(it) }.firstOrNull { Regex("\\.apk$", RegexOption.IGNORE_CASE).containsMatchIn(Json.s(it["name"])) }
                val mine = Json.l(cfg()["build"]).toInt()
                val up = if (build > mine && apk != null) Update(Json.s(j["tag_name"]).removePrefix("v"), Json.s(apk["browser_download_url"]), Json.s(j["body"]).split("\n").first().take(140)) else null
                Prefs.set("lastUpdateCheck", System.currentTimeMillis())
                main.post {
                    update = up; Ver.bump(); Inicio.mark()
                    if (manual && up == null) Nativo.toast("Tienes la última versión (${version()})")
                }
            } catch (t: Throwable) { if (manual) Nativo.toast("No he podido comprobar si hay versión nueva. ¿Tienes internet?") }
        }
    }
    /** El aviso de «Ahora» (si hay versión nueva y no se dijo «Luego»). */
    fun banner(): Update? { val u = update ?: return null; return if (Prefs.str("skipUpdate", "") == u.version) null else u }

    // ---------------------------------------------------------------- el espacio
    fun gb(b: Double): String = if (b >= 1e9) String.format(java.util.Locale.US, "%.1f GB", b / 1e9) else "${Math.round(b / 1e6)} MB"
    @Volatile private var storeCache: Pair<Long, Long> = 0L to 0L; @Volatile private var storeAt = 0L; @Volatile private var storeBusy = false
    /** Lo que ocupa Órbita en el móvil y lo que queda libre (se cuenta aparte, sin parar la pantalla). */
    fun storage(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (now - storeAt > 20000 && !storeBusy) {
            val a = Nativo.act ?: return storeCache
            storeBusy = true
            net.execute {
                fun size(f: java.io.File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0L)
                val used = try { size(a.filesDir) + (a.getDatabasePath("orbita.db")?.let { if (it.isFile) it.length() else 0L } ?: 0L) } catch (t: Throwable) { 0L }
                val free = try { a.filesDir.usableSpace } catch (t: Throwable) { 0L }
                storeCache = used to free; storeAt = System.currentTimeMillis(); storeBusy = false
                Ver.bump()
            }
        }
        return storeCache
    }

    // ---------------------------------------------------------------- el micrófono
    fun micState(): String {
        val a = Nativo.act ?: return "prompt"
        return if (androidx.core.content.ContextCompat.checkSelfPermission(a, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) "granted"
            else if (Prefs.bool("_micAsked", false) && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(a, android.Manifest.permission.RECORD_AUDIO)) "denied" else "prompt"
    }
    fun askMic() {
        val a = Nativo.act as? androidx.activity.ComponentActivity ?: return
        Prefs.set("_micAsked", true)
        Actos.launch(a, androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), android.Manifest.permission.RECORD_AUDIO) { ok ->
            Nativo.toast(if (ok == true) "Micrófono permitido" else "Has bloqueado el micrófono. Puedes activarlo en los ajustes del sistema.", if (ok == true) 2600 else 4200)
            Ver.bump()
        }
    }

    // ---------------------------------------------------------------- la clave de la IA
    fun setKey(v: String): Boolean {
        if (v.isEmpty()) return false
        if (!v.startsWith("sk-ant-")) { Nativo.toast("Esa clave no parece de la API de Claude (empieza por sk-ant-).", 4000); return false }
        Prefs.set("apiKey", v); Ver.bump(); Inicio.mark(); Nativo.toast("Clave guardada: la IA de Nova está lista")
        return true
    }

    // ---------------------------------------------------------------- el ecualizador
    fun setEq(id0: String) {
        val id = if (Mezcla.eqPreset(id0) != null) id0 else "plano"
        if (id == "custom" && Prefs.str("eq", "plano") != "custom") Prefs.set("eqCustom", Mezcla.eqGains().toList())
        Prefs.set("eq", id)
        if (!Prefs.bool("eqOn", true)) Prefs.set("eqOn", true)
        Player.applyEq(); Ver.bump()
    }
    fun delEq(id: String) {
        Prefs.set("eqMine", Mezcla.eqMine().filter { Json.s(it["id"]) != id })
        if (Prefs.str("eq", "plano") == "mine-$id") { Prefs.set("eqCustom", Mezcla.eqGains().toList()); Prefs.set("eq", "custom") }
        Player.applyEq(); Ver.bump(); Nativo.toast("Ajuste borrado")
    }
    fun saveEq(name0: String): Boolean {
        val name = name0.trim().take(24); if (name.isEmpty()) return false
        val list = Mezcla.eqMine().filter { Biblio.norm(Json.s(it["name"])) != Biblio.norm(name) }.toMutableList()
        if (list.size >= 8) { Nativo.toast("Puedes guardar hasta 8 ajustes tuyos: borra alguno antes."); return false }
        val id = Listas.uid("eq")
        list.add(linkedMapOf("id" to id, "name" to name, "g" to Mezcla.eqMigrate(Prefs.get("eqCustom")).toList()))
        Prefs.set("eqMine", list); Prefs.set("eq", "mine-$id")
        Player.applyEq(); Ver.bump(); Nativo.toast("Guardado: «$name»"); Nativo.haptic(longArrayOf(0, 8))
        return true
    }

    // ---------------------------------------------------------------- el tema y el aspecto
    fun setTheme(id: String) {
        Prefs.set("theme", id); Nativo.look(); Nativo.bgSync(); Ver.bump(); Inicio.mark()
        if (id == "cover" && Player.current() == null) Nativo.toast("Pon una canción y la app tomará el color de su portada")
    }
    private val START_NAMES = mapOf("inicio" to "Ahora", "biblioteca" to "Música", "buscar" to "Buscar", "tu" to "Tú")
    fun setLook(key: String, v: String) {
        Prefs.set(key, v); Nativo.look(); Nativo.bgSync(); Nativo.haptic(longArrayOf(0, 6)); Ver.bump(); Inicio.mark()
        if (key == "startView") Nativo.toast("Órbita se abrirá en «${START_NAMES[v] ?: v}»")
    }

    // ---------------------------------------------------------------- la DJ
    fun setCity(name: String) {
        if (!Online.on()) { Nativo.toast("Sin internet no puedo buscar la ciudad."); return }
        net.execute {
            try {
                val j = Json.obj(Online.getJson("https://geocoding-api.open-meteo.com/v1/search?name=${Online.enc(name)}&count=1&language=es&format=json"))
                val r = Json.list(j?.get("results"))?.firstOrNull()?.let { Json.obj(it) }
                if (r == null) { Nativo.toast("No encuentro esa ciudad"); return@execute }
                Prefs.set("city", linkedMapOf("name" to Json.s(r["name"]), "lat" to Json.d(r["latitude"]), "lon" to Json.d(r["longitude"]), "country" to Json.s(r["country"])))
                Prefs.set("wx", null)
                main.post {
                    Ver.bump()
                    Online.loadWeather(true) { w -> Ordenes.say("Apuntado: ${Json.s(r["name"])}.${if (w != null) Online.weatherLine() else ""}") }
                }
            } catch (t: Throwable) { Nativo.toast("No he podido buscar la ciudad. ¿Tienes internet?") }
        }
    }
    fun setDjName(v0: String) {
        val v = v0.replace(Regex("[<>]"), "").trim().take(20).ifEmpty { "Nova" }
        Prefs.set("djName", v); Ver.bump(); Inicio.mark(); Paginas.mark()
        Voz.speak("A partir de ahora me llamo $v.")
    }
    fun setPersona(id: String) {
        Prefs.set("djPersona", id); Ver.bump()
        val t = Player.current() ?: Biblio.visible().firstOrNull()
        Voz.speak(if (t != null) Nova.introFor(t) else "Así hablo en modo ${Nova.PERSONAS[id]?.name?.lowercase() ?: id}.")
    }

    // ---------------------------------------------------------------- el diagnóstico («diagCopy»)
    fun copyDiag() {
        val t = Player.current()
        val sb = StringBuilder()
        sb.append("Órbita ${version().ifEmpty { "?" }}${if (Json.b(cfg()["beta"])) " beta" else ""} · motor Android · Órbita nativa (Kotlin y Compose) · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n")
        sb.append("Ahora: ${if (t != null) t.title + " — " + t.artist else "nada"} · ${if (Player.playing) "sonando" else "en pausa"} · mezcla ${Mezcla.Aud.mix} ${Mezcla.Aud.mixLen.toInt()}s · igualar ${Mezcla.Aud.level} · directo ${Mezcla.Aud.direct}\n")
        sb.append("Canciones: ${Lib.tracks.count { !it.demo }} · listas: ${Lib.playlists.size} · escuchas guardadas: ${Memoria.events.size}\n")
        sb.append("Descubre: ${if (Prefs.bool("recOn", true)) "encendido" else "apagado"} · ${Recos.count()} recomendaciones\n")
        sb.append("Pantalla: ${UiState.screen}${if (Ventanas.stack.isNotEmpty()) " · ventanas: " + Ventanas.stack.joinToString(" + ") else ""}\n\n")
        synchronized(Nativo) { for (l in Nativo.log.takeLast(80)) sb.append(l).append('\n') }
        try {
            val cm = Nativo.act?.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico de Órbita", sb.toString()))
            Nativo.toast("Diagnóstico copiado: pégamelo en el chat", 4200)
        } catch (t: Throwable) { Nativo.toast("No he podido copiar el diagnóstico.") }
    }
}

/**
 * Las copias de seguridad («Hacer copia» y «Restaurar una copia»): tus favoritas, listas, letras, estadísticas,
 * grabaciones de voz y ajustes en un archivo (el mismo formato que la app de siempre: se pueden restaurar las de antes).
 */
object Copia {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile var busy = false; private set
    private val SKIP = setOf("apiKey", "ytKey", "nv", "nb", "session", "daily", "lastUpdateCheck", "skipUpdate", "devWelcomeDone", "devHidden")

    private fun build(): Map<String, Any?> {
        val settings = LinkedHashMap<String, Any?>()
        for ((k, v) in Store.kvAll()) { if (k.startsWith("_") || k in SKIP) continue; settings[k] = Json.parseOr(v, null) }
        val tracks = Lib.tracks.filter { !it.demo && !it.net }.map { t -> LinkedHashMap(t.toRecord()).also { it.remove("cover"); it.remove("thumb") } }
        val events = Store.events().mapNotNull { (_, v) -> Json.obj(Json.parseOr(v, null)) }
        val lyrics = Store.recs("lyrics").mapNotNull { (_, v) -> Json.obj(Json.parseOr(v, null)) }.filter { !Json.b(it["none"]) }
        val clips = ArrayList<Map<String, Any?>>(); var budget = 12_000_000L
        for ((_, v) in Store.recs("voice")) {
            val c = Json.obj(Json.parseOr(v, null)) ?: continue
            val f = Store.blobFile(c["blob"]) ?: continue
            if (!f.isFile || f.length() > budget) continue
            budget -= f.length()
            val out = LinkedHashMap(c); out.remove("blob")
            out["b64"] = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            out["type"] = Json.s(Json.obj(c["blob"])?.get("type")).ifEmpty { "audio/webm" }
            clips.add(out)
        }
        return linkedMapOf("app" to "orbita", "kind" to "backup", "v" to 2, "created" to System.currentTimeMillis(), "version" to Ajustes.version(), "settings" to settings,
            "tracks" to tracks, "playlists" to Lib.playlists, "wishlist" to Store.recs("wishlist").mapNotNull { (_, v) -> Json.obj(Json.parseOr(v, null)) },
            "events" to events, "lyrics" to lyrics, "clips" to clips, "demoStats" to (Prefs.get("demoStats") ?: emptyMap<String, Any?>()))
    }

    /** «Hacer copia»: el archivo, en Descargas › Orbita. */
    fun export() {
        if (busy) return
        busy = true; Ver.bump()
        io.execute {
            try {
                val text = Json.str(build())
                val name = "orbita-copia-${Memoria.dayKey()}.json"
                val a = Nativo.act ?: throw IllegalStateException("sin app")
                val where: String
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val v = android.content.ContentValues()
                    v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Orbita")
                    val uri = a.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("insert")
                    a.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("open")
                    where = "Descargas › Orbita › $name"
                } else {
                    val dir = a.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: a.filesDir
                    dir.mkdirs(); val f = java.io.File(dir, name); f.writeText(text); where = f.absolutePath
                }
                Prefs.set("lastBackup", System.currentTimeMillis())
                Nativo.toast("Copia guardada en $where", 5200)
            } catch (t: Throwable) { Nativo.toast("No he podido guardar la copia en Descargas.", 4200) }
            main.post { busy = false; Ver.bump() }
        }
    }

    /** «Restaurar una copia»: se elige el archivo y se junta con lo que hay (las canciones, por título y artista). */
    fun pickAndRestore() {
        val a = Nativo.act as? androidx.activity.ComponentActivity ?: return
        Actos.launch(a, androidx.activity.result.contract.ActivityResultContracts.GetContent(), "*/*") { uri ->
            val u = uri as? android.net.Uri ?: return@launch
            io.execute {
                val j = try { Json.obj(Json.parseOr(a.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: "", null)) } catch (t: Throwable) { null }
                if (j == null || Json.s(j["app"]) != "orbita" || Json.s(j["kind"]) != "backup") { Nativo.toast("Ese archivo no es una copia de seguridad de Órbita.", 4000); return@execute }
                try { restore(j) } catch (t: Throwable) { Nativo.toast("No he podido restaurar la copia.", 4000) }
            }
        }
    }

    private fun restore(j: Map<String, Any?>) {
        val byId = Lib.tracks.associateBy { it.id }
        val bySrc = Lib.tracks.filter { it.srcKey.isNotEmpty() }.associateBy { it.srcKey }
        val byName = HashMap<String, MutableList<Track>>()
        for (t in Lib.tracks) byName.getOrPut(Biblio.norm(t.title) + "|" + Biblio.norm(t.artist)) { ArrayList() }.add(t)
        val idMap = HashMap<String, String>(); var matched = 0
        for (r in (Json.list(j["tracks"]) ?: emptyList()).mapNotNull { Json.obj(it) }) {
            val rid = Json.s(r["id"]); val dur = Json.d(r["duration"])
            val t = byId[rid] ?: Json.s(r["srcKey"]).let { if (it.isNotEmpty()) bySrc[it] else null }
                ?: byName[Biblio.norm(Json.s(r["title"])) + "|" + Biblio.norm(Json.s(r["artist"]))]?.firstOrNull { dur == 0.0 || it.duration == 0.0 || Math.abs(it.duration - dur) < 4 } ?: continue
            idMap[rid] = t.id; matched++
            t.liked = t.liked || Json.b(r["liked"]); t.plays = maxOf(t.plays, Json.l(r["plays"]).toInt()); t.lastPlayed = maxOf(t.lastPlayed, Json.d(r["lastPlayed"]))
            t.skips = maxOf(t.skips, Json.l(r["skips"]).toInt()); t.listenSec = maxOf(t.listenSec, Json.d(r["listenSec"]))
            val rh = Json.list(r["hours"])?.map { Json.d(it) }
            if (rh != null && rh.size == 24) { val h = if (t.hours?.size == 24) t.hours!! else MutableList(24) { 0.0 }; for (i in 0 until 24) h[i] = maxOf(h[i], rh[i]); t.hours = h }
            if (Json.b(r["edited"]) && !t.edited) { t.title = Json.s(r["title"]); t.artist = Json.s(r["artist"]); t.album = Json.s(r["album"]); t.edited = true }
            Biblio.save(t)
        }
        fun mapId(id: String): String? = idMap[id] ?: if (Lib.byId(id) != null) id else null
        var pls = 0
        for (p in (Json.list(j["playlists"]) ?: emptyList()).mapNotNull { Json.obj(it) }) {
            val pid = Json.s(p["id"]); if (pid.isEmpty() || Lib.playlists.any { Json.s(it["id"]) == pid }) continue
            val ids = (Json.list(p["trackIds"]) ?: emptyList()).mapNotNull { mapId(Json.s(it)) }; if (ids.isEmpty()) continue
            val intros = LinkedHashMap<String, Any?>(); for ((k, v) in Json.obj(p["intros"]) ?: emptyMap()) { mapId(k)?.let { intros[it] = v } }
            val np = LinkedHashMap(p); np["trackIds"] = ids; np["intros"] = intros
            main.post { Listas.put(np) }; pls++
        }
        for (w in (Json.list(j["wishlist"]) ?: emptyList()).mapNotNull { Json.obj(it) }) { val wid = Json.s(w["id"]); if (wid.isNotEmpty() && Store.rec("wishlist", wid) == null) Store.putRec("wishlist", wid, Json.str(w)) }
        val have = synchronized(Memoria) { Memoria.events.map { "${it.ts}|${it.id}" }.toHashSet() }
        var evs = 0
        for (e in (Json.list(j["events"]) ?: emptyList()).mapNotNull { Json.obj(it) }) {
            val ts = Json.l(e["ts"]); if (ts == 0L) continue
            val id = if (Json.b(e["net"])) Json.s(e["id"]) else mapId(Json.s(e["id"])) ?: continue
            if ("$ts|$id" in have) continue
            val ne = LinkedHashMap(e); ne["id"] = id; Store.addEvent(Json.str(ne)); evs++
        }
        for (l in (Json.list(j["lyrics"]) ?: emptyList()).mapNotNull { Json.obj(it) }) { val id = mapId(Json.s(l["id"])) ?: continue; val nl = LinkedHashMap(l); nl["id"] = id; Store.putRec("lyrics", id, Json.str(nl)) }
        var clips = 0
        for (c in (Json.list(j["clips"]) ?: emptyList()).mapNotNull { Json.obj(it) }) {
            val cid = Json.s(c["id"]); val b64 = Json.s(c["b64"]); if (cid.isEmpty() || b64.isEmpty() || Store.rec("voice", cid) != null) continue
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val blob = Store.putBlob("voice", cid, "blob", bytes, Json.s(c["type"]).ifEmpty { "audio/webm" })
                val nc = LinkedHashMap(c); nc.remove("b64"); nc.remove("type"); nc["blob"] = blob
                Store.putRec("voice", cid, Json.str(nc)); clips++
            } catch (ignored: Throwable) { }
        }
        Json.obj(j["demoStats"])?.let { ds -> val cur = Json.obj(Prefs.get("demoStats")) ?: emptyMap(); Prefs.set("demoStats", LinkedHashMap(ds).apply { putAll(cur) }) }
        for ((k, v) in Json.obj(j["settings"]) ?: emptyMap()) if (k !in SKIP && k != "demoStats" && !k.startsWith("_")) Prefs.set(k, v)
        Nativo.toast("Copia restaurada: $matched ${if (matched == 1) "canción" else "canciones"}, $pls ${if (pls == 1) "lista" else "listas"}${if (clips > 0) ", $clips grabaciones" else ""}. Reiniciando…", 4200)
        main.postDelayed({ Nativo.restart() }, 2200)
    }
}
