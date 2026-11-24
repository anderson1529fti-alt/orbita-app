package __PACKAGE__

/**
 * Fase 4, parte 2 · «Órbita se ha actualizado»: lo nuevo de la versión que se acaba de instalar, una vez (lo que la web
 * enseñaba con «showNews»). Lo de cada versión, con lo que va en negrita entre asteriscos dobles.
 */
object Novedades {
    val NEWS = mapOf(
        "5.4" to listOf(
            "**Órbita ya es una app de Android de principio a fin**: tus datos, el reproductor, «Buscar», «Tu voz», la letra, el visualizador, «Tu año», el despertador y todo lo que Nova decide y recuerda los hace Android. La parte web se ha ido.",
            "Se ve y se toca igual que siempre, pero arranca antes, va más fluida y gasta menos batería y memoria.",
            "Tus canciones, listas, grabaciones, letras, ajustes y escuchas siguen ahí: no hay que hacer nada.",
            "YouTube se sigue viendo con su reproductor oficial (es lo único que va en una página dentro de la app, como pide YouTube)."
        )
    )
    var ver = ""; private set
    var items: List<String> = emptyList(); private set
    /** Al abrir: si esta versión trae novedades y aún no se han visto. */
    fun show() {
        val v = Ajustes.version(); val mm = v.split('.').take(2).joinToString(".")
        val list = NEWS[mm] ?: return
        if (Prefs.str("newsSeen", "") == mm) return
        Prefs.set("newsSeen", mm)
        ver = "Versión $v instalada"; items = list
        Ventanas.open("news")
    }
}
