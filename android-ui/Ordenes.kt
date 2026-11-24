package __PACKAGE__

import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Fase 4, parte 2 · Lo que le pides a Nova (escrito o hablado): «sáltate esta», «más movida», «¿quién canta?»,
 * «apágate en media hora», «algo para entrenar»… (lo de «novaRun» de la parte web). Lo que no es una orden conocida
 * se lo pasa a su cerebro (el de comprensión, ya en Android) y, si no, se convierte en una mezcla.
 */
object Ordenes {
    /** Lo escrito en la caja de «Ahora» (lo que se manda). */
    var draft = ""
    /** Las ideas que ofrece Nova después de contestar (las que salen bajo la caja). */
    var chips: List<String> = emptyList()

    // ---------------------------------------------------------------- la conversación (se guarda: «chat»)
    fun log(): List<Map<String, Any?>> = Json.list(Prefs.get("chat"))?.mapNotNull { Json.obj(it) } ?: emptyList()
    fun chatAdd(who: String, text: String) {
        if (text.isEmpty()) return
        val l = log().toMutableList(); l.add(linkedMapOf("who" to who, "text" to text.take(600), "ts" to System.currentTimeMillis()))
        Prefs.set("chat", l.takeLast(80))
        Nativo.onChat()
    }

    /** «novaSay»: lo dice con su voz (no con tus grabaciones) y queda en la conversación. */
    fun say(text: String) { chatAdd("nova", text); Nova.djTalk(text, "test", forceTTS = true) }

    /** «novaCommand»: lo que se escribió o se dijo. */
    fun run(raw: String) {
        chatAdd("me", raw)
        draft = ""
        go(raw, false)
        Inicio.mark()
    }

    /** Lo que llega del micrófono (lo entendido y sus otras formas). */
    fun fromVoice(text: String, alts: List<String>, code: String) {
        if (text.isBlank()) { if (code != "cancel" && code != "") Nativo.toast(when (code) { "nomatch", "speech_timeout" -> "No te he entendido. Prueba otra vez."; "network" -> "Sin internet no te puedo escuchar."; else -> "No te he oído bien." }); return }
        run(text)
    }

    private fun go(raw: String, viaBrain: Boolean) {
        val t = Player.current(); val playing = t != null && Player.playing
        var q = Biblio.norm(raw)
        val me = Biblio.norm(Nova.name())
        q = q.replace(Regex("^(oye |hey |ey |hola |venga )?(" + (if (me.isNotEmpty()) Regex.escape(me) + "|" else "") + "nova|dj)( |$)"), "").replace(Regex("^(por favor |porfa )|( por favor| porfa)$"), "").trim()
        if (!viaBrain && Charla.offer(q)) return
        if (!viaBrain && Charla.follow(q)) return
        if (q.isEmpty()) { if (Regex("^[\\s¡!,.]*(hola|buenas|buenos)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) Charla.chat("c_saludo") else say("Dime, te escucho."); return }
        fun iss(re: String) = Regex(re).containsMatchIn(q)
        if (Alarma.command(q)) return
        if (iss("^(ayuda|que puedes hacer|que sabes hacer|que te puedo pedir)$")) { say("Puedes pedirme cosas como: más movida, más tranquila, sáltate esta, ¿por qué esta?, más como esta, ¿quién canta?, háblame del artista, apágate en treinta minutos, o una mezcla: algo para entrenar. Y enseñarme lo que te gusta: no me pongas reguetón por la mañana, me encanta el rap, cada noche ponme algo tranquilo, ¿qué recuerdas de mí?" + if (Charla.usable()) " Y con mi cerebro nuevo entiendo también lo que me digas con tus palabras." else ""); return }
        if (iss("(resumen|como ha ido|que tal ha ido) (de )?(mi|la|esta) semana")) { say(Recuerdos.weekSummary().ifEmpty { "Esta semana aún has escuchado poco para hacer un resumen." }); return }
        if (iss("^(que hora es|dime la hora)$")) { val c = Calendar.getInstance(); say("Son las ${c.get(Calendar.HOUR_OF_DAY)} y ${Momento.pad(c.get(Calendar.MINUTE))}."); return }
        if (iss("^(pausa|pausala|para(la)?|deten(la|te)?|stop|silencio|calla(te)?|pon pausa|quita la musica)$")) { if (playing) Player.fadePause(); Nova.setBubble("Pausa.", Nova.name()); return }
        if (iss("^(sigue|continua|reanuda|play|dale|reproduce|pon (la )?musica|vuelve a poner(la)?)$")) { if (t != null) Player.resume() else Mezclas.create(surprise = true, autoplay = true); return }
        if (iss("^(siguiente|la siguiente|pasa(la)?|salta(la)?|saltate (esta|esa)|saltatela|otra|otra cancion|cambia(la)?|cambia de cancion|next|no me gusta( esta| nada)?)$")) { if (t != null) { Nova.setBubble("Siguiente.", Nova.name()); Player.next(false) }; return }
        if (iss("^(anterior|la anterior|atras|vuelve atras|la de antes|pon la (de antes|anterior))$")) { if (t != null) Player.prev(); return }
        if (iss("^(sube( el)? volumen|subele|mas alto|mas volumen|sube)$")) { Nativo.volumeStep(+1); return }
        if (iss("^(baja( el)? volumen|bajale|mas bajo|mas flojo|menos volumen|baja)$")) { Nativo.volumeStep(-1); return }
        if (iss("^(me gusta( esta| mucho)?|me encanta( esta)?|guarda(la)?( en favoritas)?|a favoritas|favorita|like)$")) { if (t != null && !t.net) { if (!Biblio.liked(t)) Biblio.like(t.id); say(Cerebro.pickFresh(listOf("Guardada en tus favoritas.", "Apuntada: favorita.", "Hecho, a favoritas."))) }; return }
        if (iss("(no me (la )?pongas mas|no (la )?quiero (volver a )?oir( mas)?|odio esta|quita(la)? para siempre|no la pongas mas)")) { if (t != null) Cerebro.banTrack(t); return }
        if (iss("(mas (como|parecidas a|parecido a) esta|algo parecido|parecidas a esta|mas de este (estilo|rollo|tipo))")) { Cerebro.moreLikeThis(); return }
        if (t != null && iss("^(un poco |algo )?mas (movida|movido|animada|animado|marcha|ritmo|energia|cana|canera|fuerte|rapida|rapido)$|^sube (el|ese) ritmo$|^venga arriba$")) { refineEnergy(+1); return }
        if (t != null && iss("^(un poco |algo )?mas (tranquila|tranquilo|suave|calmada|lenta|lento|relajada|relajado|chill)$|^baja (el|ese) ritmo$|^relaja(nos|te)?$")) { refineEnergy(-1); return }
        if (iss("(que (cancion )?(suena|es esta|esta sonando|estoy escuchando)|como se llama (esta|la cancion)|quien canta( esta)?)")) {
            if (t == null) { say("Ahora mismo no suena nada."); return }
            val f = if (!t.net) Cerebro.cachedFact(t.artist) else null
            say("Suena ${Cerebro.nameOf(t)}." + (if (f != null) " " + Online.shortFact(f) else "")); return
        }
        if (iss("por ?que (has puesto|pusiste|me pones|suena|elegiste|esta)") || iss("^por ?que$")) { explainPick(t); return }
        val ab = Regex("^(quien es|quienes son|hablame (de|del artista|sobre)|cuentame (algo )?(de|del artista|sobre)|que sabes de|dime algo (de|del artista|sobre))( (.+))?$").find(q)
        if (ab != null || iss("^(hablame del artista|quien es el artista|cuentame algo del artista)$")) {
            val who = ab?.groupValues?.getOrNull(7)?.takeIf { it.isNotEmpty() && !Regex("^(este|esta|ese|esa|el|ella|el artista|la artista|el cantante|la cantante|el grupo|la banda|este (grupo|artista|cantante)|esta (banda|artista|cantante)|quien canta)$").matches(it) }
            aboutArtist(who ?: t?.artist); return
        }
        if (iss("^(pon |activa |quiero )?(el )?modo radio$|^(pon )?(la )?radio$")) { if (!Radio.on) Nova.radioButton(); return }
        if (iss("(^| )(que tiempo hace|que tal (hace|el tiempo)|va a llover|esta lloviendo|como esta el tiempo|que clima hace)")) { tellWeather(); return }
        val sl = Regex("(apaga(te)?|duerme(te)?|parate|temporizador|me voy a dormir|a dormir).*?(\\d+|media|una|dos) ?(min|minutos|hora|horas)").find(q)
        if (sl != null) {
            val g4 = sl.groupValues[4]; val g5 = sl.groupValues[5]
            val n = when (g4) { "media" -> 30; "una" -> 1; "dos" -> 2; else -> g4.toIntOrNull() ?: 0 }
            val mins = if (g5.contains("hora")) (if (g4 == "media") 30 else n * 60) else n
            if (mins in 1..600) { Player.setSleep(mins.toString()); say("Vale, me apago en ${if (mins >= 60) (if (mins / 60.0 == 1.0) "una hora" else "${trimNum(mins / 60.0)} horas") else "$mins minutos"}.") }
            return
        }
        if (iss("(al (acabar|terminar)|cuando (acabe|termine)) (esta|la cancion)")) { Player.setSleep("track"); return }
        if (iss("^(pon |ensename |muestrame |abre )?(la )?letra$|karaoke")) { if (t != null) { Ventanas.open("np"); Ventanas.lyOn = true }; return }
        if (iss("visualizador|^(pon )?(el )?visual$")) { Ventanas.open("viz"); return }
        if (iss("(resumen|repaso) (de mi|del|de este|de tu|de todo el) ano|(mi|tu) ano en orbita|(^| )wrapped$|que (he|e) escuchado este ano")) { Recap.open(0); return }
        if (iss("^(pon |ponme |reproduce |quiero (oir|escuchar) )?(todas (mis|las) canciones|toda mi (musica|biblioteca)|todo mi orbita|todo orbita)( sin repetir)?$|^(ponlas|ponmelas|reproducelas) todas$|^(pon|reproduce|ponme) todas( sin repetir)?$")) { Todo.startAll(); return }
        if (iss("(recomienda(me|s)?|que me recomiendas|alguna recomendacion|sugiereme|descubreme (algo|canciones|musica)|(canciones?|algo) nuev[oa]s? (de internet )?que me (puedan? )?gust)")) { say("Las recomendaciones de internet llegan con «Buscar» en la Órbita nativa."); return }
        if (Recuerdos.command(q, raw)) return
        val qn = Regex("^(despues|luego|a continuacion) pon (.+)$|^pon (.+) (despues|luego|a continuacion|la siguiente)$").find(q)
        if (qn != null && t != null) { queueNextByName(qn.groupValues[2].ifEmpty { qn.groupValues[3] }); return }
        val less = Regex("^(nada (mas )?de|sin|menos|quita(me)?) (.+)$").find(q)
        if (less != null && t != null && removeArtistFromQueue(less.groupValues[4])) return
        val clean = raw.replace(Regex("^\\s*((oye|hey|ey|hola|venga)[\\s,]+)?(" + Regex.escape(Nova.name()) + "|nova|dj)[\\s,:]+", RegexOption.IGNORE_CASE), "").trim().ifEmpty { raw }
        if (!viaBrain && Charla.route(raw, q, clean)) return
        mixFromChat(clean)
    }

    private fun trimNum(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    /** Lo que el cerebro entendió y manda hacer como si se hubiera pedido así. */
    fun runAsBrain(text: String) = go(text, true)

    fun mixFromChat(clean: String, hint: String = "") {
        Charla.offer = null; Charla.ctxMix(clean, hint)
        Mezclas.create(request = clean, autoplay = true, hint = hint)
    }

    /** Lo que Nova cuenta en la conversación al tener lista una mezcla pedida. */
    fun afterMix(mx: Cerebro.Mix, request: String) {
        if (request.isEmpty() || mx.request != request || mx.ids.isEmpty()) return
        chatAdd("nova", "He preparado «${mx.name}»: ${mx.ids.size} canciones, empezando por ${Lib.byId(mx.ids[0])?.title ?: "la primera"}." + (mx.note?.let { " $it" } ?: ""))
    }

    fun refineEnergy(dir: Int) {
        val t = Player.current(); val v = Cerebro.vec(t)
        val base = Cerebro.target ?: (v?.get(0) ?: 0.5)
        Cerebro.target = max(0.05, min(0.95, base + dir * 0.22))
        val keep = Player.queue.take(Player.qi + 1)
        val exclude = (Biblio.history().take(10) + keep.takeLast(30)).toSet()
        var ids = Cerebro.rank(Cerebro.pool(), t, Cerebro.target, exclude, 0.3, 4.5).map { it.t.id }.take(10)
        if (ids.size < 3) ids = Cerebro.rank(Cerebro.pool(), t, Cerebro.target, strength = 4.5).map { it.t.id }.take(10)
        if (ids.isEmpty()) { say("No tengo más canciones para cambiar el ritmo."); return }
        Player.replaceAhead(ids); if (Player.source != "radio") Player.setSource("mix")
        val nx = Lib.byId(ids[0])!!
        say(if (dir > 0) Cerebro.pickFresh(listOf("Subimos el ritmo. Lo siguiente: ${nx.title}.", "Marchando algo con más energía, empezando por ${nx.title}.", "Vale, vamos a por más marcha."))
            else Cerebro.pickFresh(listOf("Bajamos revoluciones. Lo siguiente: ${nx.title}.", "Algo más tranquilo, empezando por ${nx.title}.", "Vale, nos relajamos un poco.")))
    }

    fun explainPick(t: Track?) {
        if (t == null) { say("Ahora mismo no suena nada."); return }
        if (t.net) { say(if (t.kind == "radio") "Es una radio que elegiste tú: ${t.title}." else "Es un avance que pusiste tú desde Buscar."); return }
        val r = ArrayList<String>(); val prv = Player.queue.getOrNull(Player.qi - 1)?.let { Lib.byId(it) }
        val mx = Mezclas.mix
        if (mx?.request != null && Player.source == "mix") r.add("me pediste «${mx.request}»")
        if (Biblio.liked(t)) r.add("es una de tus favoritas")
        if (prv != null && Cerebro.transScore(prv.id, t.id) > 0.3) r.add("después de ${prv.title} sueles escucharla")
        val hs = t.hours
        if (hs != null && hs.size == 24) { val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); val tot = hs.sum(); if (tot >= 3 && hs[h] / tot > 0.2) r.add("a esta hora sueles ponerla") }
        if (Biblio.plays(t) <= 1 && !t.demo) r.add("es nueva para ti y quería que la probaras")
        val v = Cerebro.vec(t); val tg = Cerebro.target
        if (v != null && tg != null && kotlin.math.abs(v[0] - tg) < 0.12) r.add("tiene justo la energía que llevamos")
        val a = Analizador.get(t); val pa = Analizador.get(prv)
        if (a != null && pa != null && a.bpm != 0.0 && pa.bpm != 0.0 && a.conf >= 2 && pa.conf >= 2 && kotlin.math.abs(a.bpm / pa.bpm - 1) < 0.06) r.add("su ritmo, ${Math.round(a.bpm)} pulsaciones por minuto, encaja con la anterior para mezclarlas")
        if (pa != null && Mezcla.keyCompat(Mezcla.anaKey(pa), Mezcla.anaKey(a)) >= 0.85) r.add(if (Mezcla.anaKey(pa) == Mezcla.anaKey(a)) "está en ${Mezcla.keyName(Mezcla.anaKey(a))}, el mismo tono que la anterior, y se mezclan sin chocar" else "su tono, ${Mezcla.keyName(Mezcla.anaKey(a))}, pega con el de la anterior")
        if (Biblio.plays(t) >= 3 && Cerebro.pSkip(t, Cerebro.ctx(prv)) < 0.25) r.add("casi nunca la saltas")
        if (r.isEmpty()) r.add(if (Player.source == "radio") "en modo radio voy probando cosas para conocerte mejor" else "va en la lista que estás escuchando")
        say("La he puesto porque ${Cerebro.listES(r.take(3))}.")
    }

    fun aboutArtist(name: String?) {
        if (name.isNullOrEmpty()) { say("¿De qué artista? Pon una canción suya o dime su nombre."); return }
        if (!Online.on()) { say("Para contarte cosas de los artistas necesito internet."); return }
        Nova.setBubble("Buscando a $name…", Nova.name())
        Online.artistFact(name) { f -> say(if (f != null) Json.s(f["text"]).replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ") else "No he encontrado nada fiable sobre $name.") }
    }

    fun tellWeather() {
        if (Prefs.get("city") == null) { say("Dime tu ciudad en Ajustes, en el apartado de tu DJ, y te digo qué tiempo hace."); return }
        Online.loadWeather(false) { w -> say(if (w != null) Online.weatherLine().trim() else "No he podido mirar el tiempo. ¿Tienes internet?") }
    }

    fun queueNextByName(name: String) {
        val k = Biblio.norm(name).replace(Regex("^(la |el |una |la cancion |cancion )"), "")
        val pl = Cerebro.pool()
        val tr = pl.firstOrNull { Biblio.norm(it.title) == k } ?: pl.firstOrNull { Biblio.norm(it.title).contains(k) } ?: pl.firstOrNull { k.contains(Biblio.norm(it.title)) && Biblio.norm(it.title).length > 3 }
        if (tr == null) { say("No encuentro «$name» en tu música."); return }
        Player.playNext(tr.id)
        say("Hecho: después suena ${tr.title}.")
    }

    fun removeArtistFromQueue(name: String): Boolean {
        val k = Biblio.norm(name); if (k.length < 3) return false
        val up = Player.queue.drop(Player.qi + 1)
        val hit = up.filter { id -> val x = Lib.byId(id); x != null && Biblio.artistKey(x.artist).isNotEmpty() && (Biblio.artistKey(x.artist) == k || Biblio.artistKey(x.artist).contains(k)) }
        if (hit.isEmpty()) return false
        val artist = Lib.byId(hit[0])!!.artist
        var rest = up.filter { it !in hit }
        if (Player.source == "radio" && rest.size < 4) rest = rest + Cerebro.radioNext(Player.current(), 6).filter { id -> Lib.byId(id)?.let { Biblio.artistKey(it.artist) != Biblio.artistKey(artist) } == true }
        Player.replaceAhead(rest)
        say("Quito ${if (hit.size == 1) "la canción" else "las ${hit.size} canciones"} de $artist que ${if (hit.size == 1) "venía" else "venían"}.")
        return true
    }
}

/** La conversación con el cerebro de Nova (lo que entiende con tus palabras, sus ofertas y lo que sigue). En el siguiente paso. */
object Charla {
    var offer: Any? = null
    fun usable(): Boolean = false
    fun offer(q: String): Boolean = false
    fun follow(q: String): Boolean = false
    fun chat(kind: String) { Ordenes.say(when (kind) { "c_saludo" -> "¡Hola! ¿Qué te pongo?"; else -> "Dime, te escucho." }) }
    fun route(raw: String, q: String, clean: String): Boolean = false
    fun ctxMix(request: String, hint: String) {}
}


