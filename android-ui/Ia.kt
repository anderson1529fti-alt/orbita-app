package __PACKAGE__

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Future

/**
 * Fase 4, parte 2 · La IA de las mezclas (lo de «AI» de la parte web): Claude, con tu propia clave de la API (la que
 * guardas en Ajustes). Sin clave, Nova lo hace con su cerebro del móvil.
 */
object Ia {
    private var job: Future<*>? = null
    @Volatile private var cancelled = false

    fun key(): String = Prefs.str("apiKey", "")
    fun ready(): Boolean = key().isNotEmpty()

    /** «aiJson»: le pide algo a Claude y devuelve el JSON de su respuesta (o el código del error). */
    fun json(prompt: String): Pair<Map<String, Any?>?, String?> {
        val k = key(); if (k.isEmpty()) return null to "not_granted"
        val c = try { URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection } catch (t: Throwable) { return null to "offline" }
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("content-type", "application/json"); c.setRequestProperty("x-api-key", k); c.setRequestProperty("anthropic-version", "2023-06-01")
            val body = Json.str(mapOf("model" to "claude-haiku-5-5", "max_tokens" to 2500, "messages" to listOf(mapOf("role" to "user", "content" to prompt + "\n\nResponde únicamente con el JSON, sin texto antes ni después."))))
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (cancelled) return null to "cancelled"
            if (code == 401 || code == 403) return null to "bad_key"
            if (code == 429) return null to "rate_limited"
            if (code !in 200..299) return null to "upstream_error"
            val j = Json.obj(Json.parse(c.inputStream.bufferedReader().use { it.readText() }))
            val txt = (Json.list(j?.get("content")) ?: emptyList()).joinToString("") { Json.s(Json.obj(it)?.get("text")) }
            val a = txt.indexOf('{'); val b = txt.lastIndexOf('}')
            if (a < 0 || b < a) return null to "invalid_json"
            return (Json.obj(Json.parseOr(txt.substring(a, b + 1), null)) ?: return null to "invalid_json") to null
        } catch (t: Throwable) { return null to (if (cancelled) "cancelled" else "offline") } finally { c.disconnect() }
    }

    fun errorNote(code: String?): String = when (code) {
        "bad_key" -> "La clave de la API no es válida. Revísala en Ajustes."
        "offline" -> "Sin conexión a internet."
        "rate_limited" -> "Muchas peticiones seguidas. Prueba de nuevo en un rato."
        else -> "La IA no respondió esta vez."
    }

    fun cancel() { cancelled = true; job?.cancel(true) }

    /** «buildPrompt» de la web, igual. */
    private fun prompt(pool: List<Track>, m: Momento.Moment, mood: String, scenario: String?, n: Int, request: String): String {
        val sample = if (pool.size > 250) pool.shuffled().take(250) else pool
        val lines = sample.joinToString("\n") { t -> "${t.id} | ${t.title} | ${t.artist} | ${Cerebro.fmt(t.duration)} | ${Biblio.plays(t)} | ${if (Biblio.liked(t)) "sí" else "no"}" +
            (if (Memoria.skipProne(t)) " | se la suele saltar" else "") + (if (t.demo) " | pista demo (${t.extra["mood"] ?: ""})" else "") + (if (t.mine) " | cantada por la propia persona" else "") }
        val prof = Cerebro.profileText()
        val recent = Biblio.history().take(8).mapNotNull { Lib.byId(it) }.joinToString("; ") { "${it.title} (${it.artist})" }.ifEmpty { "nada todavía" }
        return "Eres ${Nova.name()}, la DJ de \"Órbita\", una app de música personal. ${Nova.personaPrompt()} Escribe en español de España. Crea una mezcla.\n\n" +
            "Momento: ${m.day} a las ${m.hh} (${m.franja.phrase}). Energía típica de esta franja: ${m.franja.energy}.${if (m.weekend) " Es fin de semana." else ""}\n" +
            (if (request.isNotEmpty()) "Lo que te pide la persona (respétalo por encima de todo): \"$request\"\n" else "Ánimo que pide la persona: ${if (mood == "auto") "ninguno concreto; elige según el día y la hora" else Cerebro.moodLabel(mood)}.\n") +
            (if (scenario != null) "Tema sorpresa de la mezcla: $scenario.\n" else "") + "Longitud: hasta $n canciones, sin repetir.\n\n" +
            "Biblioteca (id | título | artista | duración | veces escuchada | favorita):\n$lines\n\n" +
            "Escuchadas hace poco (no abras con ellas): $recent\n" +
            (if (prof.isNotEmpty()) "Lo que sabes de sus gustos (úsalo con naturalidad, sin recitarlo; evita lo que se suele saltar): $prof\n" else "") +
            "\nReglas:\n- Usa SOLO ids de la biblioteca.\n- Ordena las canciones para que la energía fluya de forma natural.\n- Combina favoritas con canciones poco escuchadas y varía artistas.\n" +
            "- Si no conoces una canción, deduce su carácter por título y artista, y no inventes datos sobre ella en las presentaciones.\n" +
            "- Las presentaciones se leen en voz alta: frases cortas y naturales, como se habla, sin emojis, símbolos ni comillas.\n\n" +
            "Responde solo con JSON con esta forma:\n{\"nombre\":\"título creativo de máximo 5 palabras\",\"descripcion\":\"una frase\",\"saludo\":\"1 o 2 frases de locutora presentando la mezcla y la primera canción\",\"canciones\":[{\"id\":\"...\",\"motivo\":\"máximo 8 palabras\",\"intro\":\"frase de locutora de máximo 14 palabras para presentar esta canción\"}],\"descubrir\":[{\"titulo\":\"...\",\"artista\":\"...\",\"motivo\":\"máximo 10 palabras\"}]}\n" +
            "En \"descubrir\" pon 3 canciones reales que NO estén en la biblioteca y encajen con la mezcla."
    }

    /** Una mezcla hecha por Claude (o null y el código del error). */
    fun mix(pool: List<Track>, m: Momento.Moment, mood: String, scenario: String?, n: Int, request: String, done: (Cerebro.Mix?, String?) -> Unit) {
        cancelled = false
        job = Online.net.submit {
            val (r, err) = json(prompt(pool, m, mood, scenario, n, request))
            if (r == null) { done(null, err); return@submit }
            val ids = ArrayList<String>(); val why = HashMap<String, String>(); val intros = HashMap<String, String>(); val seen = HashSet<String>()
            for (c in Json.list(r["canciones"]) ?: emptyList()) {
                val o = Json.obj(c); val id = (if (o != null) Json.s(o["id"]) else Json.s(c)).trim()
                if (Lib.byId(id) != null && seen.add(id)) {
                    ids.add(id)
                    o?.get("motivo")?.let { why[id] = Json.s(it).take(80) }
                    o?.get("intro")?.let { intros[id] = Json.s(it).take(160) }
                }
                if (ids.size >= n) break
            }
            if (ids.size < 2) { done(null, "invalid"); return@submit }
            val disc = (Json.list(r["descubrir"]) ?: emptyList()).take(4).mapNotNull { d -> val o = Json.obj(d) ?: return@mapNotNull null
                mapOf("title" to Json.s(o["titulo"]).take(100), "artist" to Json.s(o["artista"]).take(100), "why" to Json.s(o["motivo"]).take(120)).takeIf { it["title"]!!.isNotEmpty() } }
            val mx = Cerebro.Mix("ai", Json.s(r["nombre"]).ifEmpty { "Tu mezcla" }.take(60), Json.s(r["descripcion"]).take(220), Json.s(r["saludo"]).take(260), ids, why, intros, disc, m.label, mood, scenario)
            if (request.isNotEmpty()) mx.request = request
            done(mx, null)
        }
    }

    /** «aiDaily»: el nombre, la frase y las presentaciones de la mezcla del día, por Claude (una vez al día). */
    fun daily(d: Cerebro.Daily) {
        if (!ready() || d.ai || d.aiTried) return
        d.aiTried = true; Prefs.set("daily", d.toMap())
        val items = d.ids.mapNotNull { Lib.byId(it) }; if (items.size < 4) return
        val m = Momento.moment(); val prof = Cerebro.profileText()
        val p = "Eres ${Nova.name()}, la DJ de \"Órbita\", una app de música personal. ${Nova.personaPrompt()} Escribe en español de España.\n" +
            "Has preparado la mezcla del día de esta persona para este ${m.day} ${m.franja.phrase}. Canciones, en orden (id | título | artista):\n" +
            items.joinToString("\n") { "${it.id} | ${it.title} | ${it.artist}" } + "\n" +
            (if (prof.isNotEmpty()) "Lo que sabes de sus gustos (úsalo con naturalidad, sin recitarlo): $prof\n" else "") +
            "Si no conoces una canción, no inventes datos sobre ella. Las frases se leen en voz alta: cortas, naturales, sin emojis ni comillas.\n" +
            "Responde solo con JSON: {\"nombre\":\"título de máximo 5 palabras\",\"descripcion\":\"una frase\",\"saludo\":\"1 o 2 frases presentando la mezcla del día y la primera canción\",\"intros\":[{\"id\":\"...\",\"intro\":\"máximo 14 palabras\"}]}"
        Online.net.execute {
            val (r, _) = json(p); if (r == null) return@execute
            val cur = Cerebro.Daily.from(Json.obj(Prefs.get("daily"))) ?: return@execute
            if (cur.key != d.key) return@execute
            r["nombre"]?.let { cur.name = Json.s(it).take(60) }; r["descripcion"]?.let { cur.desc = Json.s(it).take(200) }; r["saludo"]?.let { cur.greeting = Json.s(it).take(260) }
            val ins = HashMap<String, String>()
            for (x in Json.list(r["intros"]) ?: emptyList()) { val o = Json.obj(x) ?: continue; val id = Json.s(o["id"]); if (id in cur.ids && o["intro"] != null) ins[id] = Json.s(o["intro"]).take(160) }
            cur.intros = ins; cur.ai = true; cur.aiTried = true; Prefs.set("daily", cur.toMap())
            android.os.Handler(android.os.Looper.getMainLooper()).post { Inicio.mark() }
        }
    }
}
