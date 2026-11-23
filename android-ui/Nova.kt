package __PACKAGE__

/**
 * Fase 4, parte 2 · Nova, la DJ (lo de «DJ voice», «Nova's memory», «Roulette + radio» y compañía de la parte web).
 * (En construcción: de momento, lo que el reproductor necesita saber de ella.)
 */
object Nova {
    /** El nombre de la mezcla que suena (si es una mezcla). */
    var mixName: String? = null

    /** «Más como esta» y «No me la pongas más» del reproductor. */
    fun moreLike(t: Track) { Nativo.toast("Pondré más como «${t.title}».") }
    fun ban(t: Track) { Nativo.toast("Vale, no te pondré «${t.title}».") }

    /** Lo que dice Nova en «Ahora» (el bocadillo). */
    var bubbleText = ""; private set

    fun bubble(text: String) { bubbleText = text; Nativo.onNova() }

    /** Empieza una canción (lo que dice al presentarla, lo que aprende). */
    fun onBegin(t: Track, talk: Pair<String, String>?, again: Boolean) {
        if (talk != null) bubble(talk.first)
    }

    fun onListen(ev: Memoria.Ev) {}
    fun onLike(t: Track) {}

    /** «spinRoulette»: sin nada puesto, «play» pone algo (de momento, todo en aleatorio). */
    fun spinRoulette() {
        val pool = Biblio.visible().filter { !it.net }
        if (pool.isEmpty()) { Nativo.toast("Añade canciones para empezar."); return }
        Player.playQueue(pool.shuffled().map { it.id }, 0, "library")
    }

    fun goodNight() { bubble("Buenas noches. He parado la música.") }
}

/** La radio de Nova y las colas sin fin (lo que va añadiendo canciones al final). */
object Radio {
    /** «naRunway»: con la radio o una mezcla sin fin, siempre hay canciones por delante (también con la app dormida). */
    fun runway() {}
    /** Antes de pasar a la siguiente: la radio rellena. */
    fun onNext(auto: Boolean) {}
}

/** La voz de Nova (Piper, en el motor nativo; si no, la del sistema). */
object Voz {
    @Volatile var speaking = false; private set
    fun stop() { speaking = false }
}
