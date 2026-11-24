package __PACKAGE__

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Fase 4, parte 2 · Nova, la DJ (lo de «DJ voice», «The DJ's name, personality…» y «Roulette + radio» de la parte web):
 * su nombre y su forma de hablar, cuándo habla, lo que dice al presentar cada canción, su bocadillo y su voz.
 */
object Nova {
    private val main = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- quién es
    class Persona(val name: String, val desc: String, val prompt: String)
    val PERSONAS = linkedMapOf(
        "cercana" to Persona("Cercana", "Como una amiga que pincha", "Hablas como una locutora de radio cercana y con chispa."),
        "energica" to Persona("Enérgica", "Radio de fiesta, a tope", "Hablas como una locutora de radio de fiesta: enérgica, con ritmo y mucho entusiasmo."),
        "tranquila" to Persona("Tranquila", "Voz de radio nocturna", "Hablas como una locutora de radio nocturna: tranquila, cálida y suave."),
        "graciosa" to Persona("Divertida", "Bromea entre canciones", "Eres divertida y bromista, con humor blanco, sin pasarte y sin faltar a nadie."),
        "breve" to Persona("Al grano", "Dice lo justo", "Eres muy breve y directa: dices solo lo justo.")
    )
    fun name(): String = Prefs.str("djName", "Nova").ifEmpty { "Nova" }.take(20)
    fun persona(): String = Prefs.str("djPersona", "cercana").let { if (PERSONAS.containsKey(it)) it else "cercana" }
    fun personaPrompt(): String = PERSONAS[persona()]!!.prompt
    fun freq(): String = Prefs.str("djFreq", "siempre")
    fun voiceOn(): Boolean = Prefs.bool("voiceOn", true)
    fun intros(): Boolean = Prefs.bool("intros", true)
    fun who(): String = Prefs.str("who", "nova")
    fun djName(): String = when (who()) { "mine" -> "DJ Tú"; "mix" -> "${name()} + Tú"; else -> "DJ ${name()}" }
    fun djRole(): String = when (who()) { "mine" -> "Con tus grabaciones"; "mix" -> "Por turnos"; else -> "Tu asistente musical" }
    /** El nombre de la mezcla que suena (si es una mezcla). */
    var mixName: String? = null

    // ---------------------------------------------------------------- el bocadillo (lo que dice, letra a letra)
    var bubText by mutableStateOf(""); private set
    var bubWho by mutableStateOf(""); private set
    var bubShown by mutableIntStateOf(0); private set
    private var bubRun: Runnable? = null

    fun setBubble(text: String, who: String = "", cps: Double = 45.0) {
        bubRun?.let { main.removeCallbacks(it) }; bubRun = null
        bubText = text; bubWho = who; bubShown = 0
        if (Nativo.reduceMotion() || cps <= 0) { bubShown = if (cps <= 0) 0 else text.length; Nativo.onNova(); return }
        val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val n = ((SystemClock.uptimeMillis() - t0) / 1000.0 * cps).toInt()
                if (n > bubShown) { bubShown = min(text.length, n); Nativo.onNova() }
                if (bubShown < text.length) main.postDelayed(this, 40) else bubRun = null
            }
        }
        bubRun = r; main.postDelayed(r, 40)
        Nativo.onNova()
    }
    fun bubbleReveal(idx: Int) { if (idx > bubShown) { bubShown = min(bubText.length, idx); Nativo.onNova() } }
    fun bubbleFinish() { bubRun?.let { main.removeCallbacks(it) }; bubRun = null; bubShown = bubText.length; Nativo.onNova() }
    fun bubble(text: String) = setBubble(text)
    /** El bocadillo de ahora (para los que lo leen fuera de «Ahora»). */
    val bubbleText: String get() = bubText

    // ---------------------------------------------------------------- cuándo y qué dice
    private var talkCount = 0
    fun shouldTalk(t: Track): Boolean {
        if (!intros() || freq() == "inicio") return false
        if (Player.source !in listOf("mix", "radio", "daily", "online")) return false
        if (t.net && t.kind != "radio" && t.kind != "yt") return false
        talkCount++
        return freq() != "aveces" || talkCount % 3 == 1
    }

    fun introFor(t: Track): String {
        Player.intros[t.id]?.let { return it }
        if (Online.on() && Math.random() < 0.3) { Online.factLine(t)?.let { return it } }
        Cerebro.contextLine(t)?.let { return it }
        Cerebro.memoryLine(t)?.let { return it }
        val m = Momento.moment(); val art = if (t.artist.isNotEmpty() && t.artist != "Artista desconocido" && !t.demo && !t.mine) t.artist else null
        val nm = Cerebro.nameOf(t)
        val opts = ArrayList<String>()
        when (persona()) {
            "energica" -> { opts.addAll(listOf("¡Arriba! Llega $nm.", "¡Subimos el volumen con ${t.title}!", "¡Esto no para! $nm.", "¡Que no decaiga! ${t.title}.")); if (art != null) opts.addAll(listOf("¡$art en ${name()} FM!", "¡Con todos ustedes, $art!")) }
            "tranquila" -> opts.addAll(listOf("Ahora, con calma, $nm.", "Te dejo con $nm.", "Respira. Suena ${t.title}.", "Para este ${m.day} ${m.franja.phrase}, $nm."))
            "graciosa" -> { opts.addAll(listOf("Atención, que viene ${t.title}. Yo que tú subía el volumen.", "Siguiente en la lista: $nm. No hace falta que me lo agradezcas.", "Si esta no te gusta, la culpa es del algoritmo: ${t.title}.", "Prohibido saltarse ${t.title}. Bueno, puedes, pero me dolerá.")); if (art != null) opts.add("$art me ha pedido que te la ponga. Mentira, pero suena bien: ${t.title}.") }
            "breve" -> { opts.addAll(listOf("${t.title}.", "Ahora: ${t.title}.")); if (art != null) opts.add("$art, ${t.title}.") }
            else -> { opts.addAll(listOf("Sigue $nm.", "Ahora suena $nm.", "Ojo a esta: ${t.title}.", "Para este ${m.day} ${m.franja.phrase}, $nm.", "Subimos un poco con ${t.title}.")); if (art != null) opts.addAll(listOf("Turno de $art.", "$art, con ${t.title}.")) }
        }
        if (t.mine) opts.add("Atención, que canta la estrella de esta radio: ${t.title}.")
        if (Biblio.plays(t) <= 1 && persona() != "breve") opts.add("Esta la tenías olvidada: ${t.title}.")
        if (Biblio.liked(t)) opts.add(if (persona() == "breve") "Favorita: ${t.title}." else "Una de tus favoritas: ${t.title}.")
        return Cerebro.pickFresh(opts)
    }

    /** Empieza una canción: lo que dice al presentarla (si toca) y lo que aprende. */
    fun onBegin(t: Track, talk0: Pair<String, String>?, again: Boolean) {
        var talk = talk0
        if (talk?.first == "@yt") talk = Tubo.intro(t) to talk.second
        if (talk == null && !again && shouldTalk(t)) talk = if (t.net) (Online.netIntro(t) to "siguiente") else (introFor(t) to (if (Biblio.liked(t)) "favorita" else "siguiente"))
        if (talk != null) djTalk(talk.first, talk.second)
        Cerebro.onBegin(t)
        if (Online.on()) main.postDelayed({ Player.queue.getOrNull(Player.qi + 1)?.let { Lib.byId(it) }?.let { Online.artistFact(it.artist) } }, 2500)
    }

    fun onListen(ev: Memoria.Ev) { Cerebro.onListen(ev) }
    fun onLike(t: Track) {}

    /** «djTalk»: con su voz (o con tus grabaciones, si toca), o solo escrito si la voz está apagada. */
    fun djTalk(text: String, kind: String = "siguiente", forceTTS: Boolean = false) {
        if (!voiceOn()) { setBubble(text); return }
        val clip = if (forceTTS) null else Grabaciones.choose(kind)
        if (clip != null) { Grabaciones.play(clip, text); return }
        Voz.speak(text)
    }
    fun say(text: String, kind: String = "siguiente") = djTalk(text, kind)
    fun stopTalk() = Voz.stop()

    // ---------------------------------------------------------------- la ruleta y la radio
    var spinning by mutableStateOf(false); private set
    var slotText by mutableStateOf(""); private set
    var slotSub by mutableStateOf(""); private set
    var slotLanded by mutableStateOf(false); private set
    var slotOn by mutableStateOf(false); private set

    /** «spinRoulette»: la ruleta da vueltas por tus canciones y sale una; detrás, la radio. */
    fun spinRoulette() {
        val pool = Biblio.visible()
        if (pool.isEmpty()) { setBubble("Tu biblioteca está vacía. Sube canciones y vuelvo a girar."); return }
        if (spinning) return
        spinning = true; slotOn = true; slotLanded = false; slotSub = ""
        val order = Cerebro.radioBatch(); val winner = order.firstOrNull()?.let { Lib.byId(it) } ?: pool.random()
        setBubble("Girando la ruleta…")
        Nativo.onHome()
        fun land() {
            slotLanded = true; slotText = winner.title; slotSub = winner.artist
            Nativo.haptic(longArrayOf(0, 12, 50, 12))
            spinning = false
            val line = Cerebro.pick(listOf("¡Ha salido ${Cerebro.nameOf(winner)}! Y después, lo que diga la ruleta.", "La ruleta manda: ${Cerebro.nameOf(winner)}. Dejo la radio puesta.", "Premio: ${Cerebro.nameOf(winner)}. Seguimos en modo radio."))
            Player.playQueue(if (order.isNotEmpty()) order else listOf(winner.id), 0, "radio", line to "ruleta")
            Radio.set(true, true)
            Nativo.onHome()
        }
        if (Nativo.reduceMotion()) { land(); return }
        var delay = 35.0; var i = 0
        val r = object : Runnable {
            override fun run() {
                if (i >= 22) { land(); return }
                slotText = pool.random().title; Nativo.onHome()
                i++; main.postDelayed(this, delay.toLong()); delay *= 1.09
            }
        }
        main.post(r)
    }

    fun radioButton() {
        if (Radio.on && Player.source == "radio") { Radio.set(false); return }
        val order = Cerebro.radioBatch(); if (order.isEmpty()) { setBubble("Primero necesito canciones. Súbelas en Biblioteca."); return }
        val first = Lib.byId(order[0])!!; val m = Momento.moment()
        Player.playQueue(order, 0, "radio", "Esto es Órbita FM, ${m.day} ${m.franja.phrase}. Empezamos con ${Cerebro.nameOf(first)}." to "saludo")
        Radio.set(true, true)
    }

    /** «Más como esta» y «No me la pongas más» del reproductor. */
    fun moreLike(t: Track) { Cerebro.moreLikeThis() }
    fun ban(t: Track) { Cerebro.banTrack(t) }

    fun goodNight() {
        val line = Cerebro.pick(listOf("Buenas noches. Que descanses.", "Hasta mañana. Apago la música.", "Fin de la emisión por hoy. Que duermas bien."))
        setBubble(line)
        if (voiceOn()) Voz.speak(line)
    }
}

/** La radio de Nova y las colas sin fin (lo que va añadiendo canciones al final). */
object Radio {
    var on = false; private set
    fun set(v: Boolean, silent: Boolean = false) { on = v; if (!silent && !v) Nativo.toast("Modo radio apagado"); Nativo.onHome() }

    /** «naRunway»: con la radio, una mezcla sin fin o todas tus canciones, siempre hay canciones por delante. */
    fun runway() {
        val ahead = Player.queue.size - 1 - Player.qi
        if (ahead >= 6) return
        val more = when {
            Player.source == "radio" && on -> Cerebro.radioNext(Player.queue.lastOrNull()?.let { Lib.byId(it) } ?: Player.current(), 6)
            Player.source == "todo" -> Todo.refill()
            Player.source == "mix" && Player.mixEndless != null -> Mezclas.refill()
            else -> emptyList()
        }.filter { it !in Player.queue.takeLast(12) }
        if (more.isNotEmpty()) Player.append(more)
    }
    fun onNext(auto: Boolean) { runway() }
}

/** La voz de Nova: la del sistema (Android), con la música más baja mientras habla. */
object Voz {
    @Volatile var speaking = false; private set
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())
    private var duck = 1f

    private fun ensure() {
        if (tts != null) return
        val a = Nativo.act ?: return
        tts = TextToSpeech(a.applicationContext) { st ->
            ready = st == TextToSpeech.SUCCESS
            if (ready) try { tts?.language = Locale("es", "ES") } catch (ignored: Throwable) { }
            if (ready) { cache = null; applyVoice(); Ver.bump(); Inicio.mark() }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { if (id == "o$gen") finish() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { if (id == "o$gen") finish() } }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { main.post { if (id == "o$gen") Nova.bubbleReveal(end) } }
        })
    }

    /** El volumen de la música: lo que baja mientras habla Nova, por lo que suba el despertador. */
    fun applyMaster() { try { NovaCore.eng().setMaster(duck * Alarma.f) } catch (ignored: Throwable) { }; Calle.setVolume(duck * Alarma.f); Tubo.setVolume(duck * Alarma.f) }

    private fun duckTo(v: Float, ms: Long) {
        val from = duck; val t0 = SystemClock.uptimeMillis()
        val r = object : Runnable { override fun run() {
            val k = min(1f, (SystemClock.uptimeMillis() - t0).toFloat() / max(1L, ms))
            duck = from + (v - from) * k
            applyMaster()
            if (k < 1) main.postDelayed(this, 25)
        } }
        main.post(r)
    }

    fun speak(text: String) {
        ensure()
        stop()
        val my = ++gen
        val clean = text.replace(Regex("[«»\"“”]"), "").replace(" · ", ", ").replace(Regex("\\s+"), " ").trim()
        val rate = Prefs.num("vRate", 1.0)
        Nova.setBubble(clean, Nova.name(), 15 * rate)
        if (tts == null || !ready) {
            // (el motor de voz aún arranca: lo escrito, y la voz en cuanto pueda)
            main.postDelayed({ if (my == gen && ready) speakNow(clean, my) else if (my == gen) Nova.bubbleFinish() }, 900)
            return
        }
        speakNow(clean, my)
    }

    private fun speakNow(clean: String, my: Int) {
        val t = tts ?: return
        speaking = true
        if (Player.playing) duckTo(0.3f, 380)
        try {
            t.setSpeechRate(Prefs.num("vRate", 1.0).toFloat()); t.setPitch(Prefs.num("vPitch", 1.0).toFloat())
            t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "o$my")
        } catch (e: Throwable) { finish(); return }
        // (por si el motor no avisa al acabar)
        main.postDelayed({ if (my == gen && speaking) finish() }, 4000L + clean.length * 110L)
    }

    private fun finish() {
        if (!speaking) { Nova.bubbleFinish(); return }
        speaking = false
        duckTo(1f, 1100)
        Nova.bubbleFinish()
    }

    fun stop() {
        gen++
        try { tts?.stop() } catch (ignored: Throwable) { }
        clipMp?.let { mp -> clipMp = null; try { mp.release() } catch (ignored: Throwable) { } }
        if (speaking) { speaking = false; duckTo(1f, 600) }
    }

    // ---------------------------------------------------------------- tus grabaciones («playClip»)
    private var clipMp: android.media.MediaPlayer? = null
    /** Una grabación tuya en lugar de la voz de Nova: desde su principio recortado hasta su final, con la música más baja. */
    fun clip(c: Grabaciones.Clip, text: String) {
        stop()
        val my = ++gen
        val shown = text.ifEmpty { c.label }
        val label = "Tu voz · " + (TuVoz.SLOTS.firstOrNull { it.id == c.slot }?.name ?: "Cuña")
        Nova.setBubble(shown, label, 0.0)
        val f = Grabaciones.file(c.id)
        val len = max(0.5, (if (c.end > 0) c.end else if (c.duration > 0) c.duration else 8.0) - c.start)
        val fin = {
            if (my == gen) {
                clipMp?.let { mp -> clipMp = null; try { mp.release() } catch (ignored: Throwable) { } }
                if (speaking) { speaking = false; duckTo(1f, 1100) }
            }
            Nova.bubbleFinish()
        }
        if (f == null) { fin(); return }
        val mp = android.media.MediaPlayer(); clipMp = mp
        try {
            mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener { m ->
                if (my != gen) return@setOnPreparedListener
                try { if (c.start > 0) m.seekTo((c.start * 1000).toInt()); m.start() } catch (t: Throwable) { fin(); return@setOnPreparedListener }
                speaking = true
                if (Player.playing) duckTo(0.22f, 300)
                Nova.setBubble(shown, label, max(8.0, shown.length / len))
                // (se para en su final recortado)
                val endMs = (c.end * 1000).toInt()
                if (endMs > 0) {
                    val w = object : Runnable { override fun run() {
                        if (my != gen || clipMp !== m) return
                        val at = try { m.currentPosition } catch (t: Throwable) { endMs }
                        if (at >= endMs) fin() else main.postDelayed(this, 50)
                    } }
                    main.postDelayed(w, 50)
                }
            }
            mp.setOnCompletionListener { fin() }
            mp.setOnErrorListener { _, _, _ -> fin(); true }
            mp.prepareAsync()
        } catch (t: Throwable) { fin(); return }
        main.postDelayed({ if (my == gen && clipMp === mp) fin() }, (len * 1000 + 3000).toLong())
    }

    /** Lo que dice el botón de la voz en «Ahora». */
    fun label(): String = current()?.let { "Voz: " + labelOf(it).first } ?: "Elegir voz"

    // ---------------------------------------------------------------- las voces del móvil («loadVoices»)
    class V(val name: String, val lang: String, val local: Boolean, val voice: android.speech.tts.Voice?)
    private var cache: List<V>? = null
    /** Las voces del móvil, de la más natural a la más robótica (todas o solo las de español). */
    fun voices(all: Boolean = false): List<V> {
        ensure()
        val list = cache ?: run {
            val vs = try { tts?.voices?.toList() ?: emptyList() } catch (t: Throwable) { emptyList() }
            if (vs.isEmpty()) return emptyList()
            vs.map { V(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired, it) }.sortedByDescending { score(it) }.also { cache = it }
        }
        return if (all) list else list.filter { it.lang.lowercase().startsWith("es") }
    }
    fun loaded(): Boolean = ready
    fun score(v: V): Int {
        val n = v.name; var s = 0
        if (Regex("natural|neural", RegexOption.IGNORE_CASE).containsMatchIn(n)) s += 6
        if (Regex("online", RegexOption.IGNORE_CASE).containsMatchIn(n)) s += 2
        if (Regex("premium|enhanced|mejorada|siri", RegexOption.IGNORE_CASE).containsMatchIn(n)) s += 5
        if (Regex("google", RegexOption.IGNORE_CASE).containsMatchIn(n)) s += 3
        if (Regex("-network$", RegexOption.IGNORE_CASE).containsMatchIn(n)) s += 5
        if (!v.local) s += 1
        if (Regex("espeak|compact|eloquence", RegexOption.IGNORE_CASE).containsMatchIn(n)) s -= 4
        if (Regex("^es[-_]ES", RegexOption.IGNORE_CASE).containsMatchIn(v.lang)) s += 1
        return s
    }
    fun isNatural(v: V): Boolean = score(v) >= 4
    private val REGION = mapOf("ES" to "España", "MX" to "México", "US" to "EE. UU.", "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile", "PE" to "Perú", "VE" to "Venezuela", "419" to "Latinoamérica", "CU" to "Cuba", "UY" to "Uruguay",
        "PR" to "Puerto Rico", "EC" to "Ecuador", "BO" to "Bolivia", "CR" to "Costa Rica", "DO" to "Rep. Dominicana", "GT" to "Guatemala", "HN" to "Honduras", "NI" to "Nicaragua", "PA" to "Panamá", "PY" to "Paraguay", "SV" to "El Salvador", "GQ" to "Guinea Ecuatorial")
    private val LANGS = mapOf("es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés", "it" to "Italiano", "de" to "Alemán", "ca" to "Catalán", "gl" to "Gallego", "eu" to "Euskera", "ja" to "Japonés", "ko" to "Coreano")
    /** («voiceLabel») su nombre para enseñarlo y de dónde es. */
    fun labelOf(v: V): Pair<String, String> {
        val an = Regex("^[a-z]{2,3}-[a-z0-9]{2,4}-x-([a-z0-9]+)-(local|network)$", RegexOption.IGNORE_CASE).find(v.name)
        val name = if (an != null) "Voz ${an.groupValues[1].uppercase()}${if (an.groupValues[2].lowercase() == "network") " HD" else ""}"
            else v.name.replace(Regex("^(Microsoft|Google|Apple)\\s+", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*Online\\s*\\(Natural\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\((Natural|Enhanced|Premium)\\)", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+-\\s+.*$"), "").trim().ifEmpty { v.name }
        val parts = v.lang.split('-', '_'); val lang = parts.getOrElse(0) { "" }; val reg = parts.getOrElse(1) { "" }
        val where = if (lang == "es") (REGION[reg.uppercase()] ?: "Español") else "${LANGS[lang] ?: lang}${if (reg.isNotEmpty()) " · " + reg.uppercase() else ""}"
        return name to where
    }
    /** La voz elegida (o la de España, o la primera en español). */
    fun current(): V? {
        val all = voices(true); if (all.isEmpty()) return null
        val saved = Prefs.str("voiceName", "")
        val es = all.filter { it.lang.lowercase().startsWith("es") }
        return all.firstOrNull { it.name == saved } ?: es.firstOrNull { Regex("^es[-_]ES", RegexOption.IGNORE_CASE).containsMatchIn(it.lang) } ?: es.firstOrNull()
    }
    fun use(name: String) { Prefs.set("voiceName", name); applyVoice() }
    private fun applyVoice() { try { val v = current()?.voice; if (v != null) tts?.voice = v } catch (ignored: Throwable) { } }
    /** «speakTTS» con una voz concreta (para «Probar»). */
    fun test(text: String, name: String) {
        ensure(); val v = voices(true).firstOrNull { it.name == name }?.voice
        try { if (v != null) tts?.voice = v } catch (ignored: Throwable) { }
        speak(text)
        main.postDelayed({ applyVoice() }, 200)
    }
}

/** Escuchar a quien habla (el reconocimiento de voz de Android, con su recuadro nativo). */
object Oido {
    fun available(): Boolean { val a = Nativo.act ?: return false; return try { android.speech.SpeechRecognizer.isRecognitionAvailable(a) } catch (t: Throwable) { false } }
    fun listen() {
        val r = Nativo.ui?.nativoListen(Nova.name(), Nativo.lite(), Prefs.bool("sttOffline", false)) ?: return
        when (r) {
            "norec" -> Nativo.toast("Este móvil no tiene reconocimiento de voz.")
            "denied" -> Nativo.toast("Sin permiso para el micrófono. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos.", 4200)
        }
    }
}

/** Tus grabaciones («Tu voz»): las cuñas que suenan en lugar de Nova cuando eliges «Mi voz» o «Las dos». */
object Grabaciones {
    class Clip(val id: String, val slot: String, val label: String, val start: Double, val end: Double, val duration: Double)
    private val SLOT_PREF = mapOf("saludo" to listOf("saludo", "siguiente", "libre"), "ruleta" to listOf("ruleta", "saludo", "libre"), "favorita" to listOf("favorita", "siguiente", "libre"), "siguiente" to listOf("siguiente", "libre"), "test" to emptyList())
    fun all(): List<Clip> = Store.recs("voice").mapNotNull { (_, v) ->
        val m = Json.obj(Json.parseOr(v, null)) ?: return@mapNotNull null
        Clip(Json.s(m["id"]), Json.s(m["slot"]).ifEmpty { "libre" }, Json.s(m["label"]), Json.d(m["start"]), Json.d(m["end"]), Json.d(m["duration"]))
    }
    fun choose(kind: String): Clip? {
        if (Nova.who() == "nova") return null
        val clips = all(); if (clips.isEmpty()) return null
        if (Nova.who() == "mix" && Math.random() < 0.5) return null
        for (slot in SLOT_PREF[kind] ?: listOf("siguiente", "libre")) {
            val pool = clips.filter { it.slot == slot || (slot == "siguiente" && it.slot == "libre") }
            if (pool.isNotEmpty()) return pool.random()
        }
        return null
    }
    fun play(c: Clip, text: String) = Voz.clip(c, text)
    /** El archivo de una grabación. */
    fun file(id: String): java.io.File? = Store.blobFile(Json.obj(Json.parseOr(Store.rec("voice", id), null))?.get("blob"))
}
