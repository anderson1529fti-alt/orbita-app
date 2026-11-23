package __PACKAGE__

import java.util.Calendar

/**
 * Fase 4, parte 2 · El momento del día, los ánimos y los temas de color (lo mismo que «Moments, moods, themes» de la
 * parte web, con los mismos números y textos). Sin nada de Android: se prueba en el ordenador contra la web.
 */
object Momento {
    val DAYS = listOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")

    class Franja(val id: String, val from: Int, val to: Int, val phrase: String, val hue: Int, val energy: String, val hint: String)

    val FRANJAS = listOf(
        Franja("madrugada", 0, 6, "de madrugada", 232, "muy baja, íntima", "Luces bajas, volumen suave y canciones que abrazan."),
        Franja("manana", 6, 12, "por la mañana", 28, "empieza suave y va subiendo", "Arrancar sin prisa y coger ritmo poco a poco."),
        Franja("mediodia", 12, 15, "al mediodía", 42, "media-alta, luminosa", "Una pausa con buen ritmo para recargar."),
        Franja("tarde", 15, 20, "por la tarde", 8, "media, constante", "Energía para llegar arriba al final del día."),
        Franja("noche", 20, 24, "por la noche", 285, "baja para relajarse, o alta si hay fiesta", "Bajar revoluciones o salir: tú decides.")
    )

    class Moment(val day: String, val franja: Franja, val hint: String, val hh: String, val weekend: Boolean) {
        val label: String get() = "$day ${franja.phrase}"
    }

    fun pad(n: Int): String = if (n < 10) "0$n" else n.toString()

    fun moment(c: Calendar = Calendar.getInstance()): Moment {
        val h = c.get(Calendar.HOUR_OF_DAY); val wd = c.get(Calendar.DAY_OF_WEEK) - 1
        val f = FRANJAS.first { h >= it.from && h < it.to }
        var hint = f.hint
        if (f.id == "noche" && (wd == 5 || wd == 6)) hint = "Noche de fin de semana: el plan lo pones tú."
        else if (wd == 0 && f.id != "noche" && f.id != "madrugada") hint = "Domingo: sin ninguna prisa."
        else if (wd == 1 && f.id == "manana") hint = "Lunes por la mañana: hace falta algo que empuje."
        return Moment(DAYS[wd], f, hint, pad(h) + ":" + pad(c.get(Calendar.MINUTE)), wd == 0 || wd == 6 || (wd == 5 && h >= 18))
    }

    class Mood(val id: String, val label: String)
    val MOODS = listOf(Mood("auto", "Según la hora"), Mood("energia", "Energía"), Mood("calma", "Calma"), Mood("foco", "Concentración"),
        Mood("fiesta", "Fiesta"), Mood("nostalgia", "Nostalgia"), Mood("gris", "Día gris"), Mood("gym", "Entreno"))

    class Theme(val id: String, val name: String, val desc: String, val hue: Double = 0.0, val sat: Double = 1.0)
    val THEMES = listOf(
        Theme("auto", "Según la hora", "Cambia de color con el momento del día"),
        Theme("cover", "Según la portada", "Toma el color de la canción que suena"),
        Theme("neon", "Neón", "Magenta eléctrico", 305.0, 1.0),
        Theme("oceano", "Océano", "Azules y turquesas", 192.0, 1.0),
        Theme("bosque", "Bosque", "Verdes profundos", 140.0, .85),
        Theme("fuego", "Fuego", "Rojos y naranjas", 12.0, 1.0),
        Theme("oro", "Oro", "Dorado cálido", 44.0, .95),
        Theme("lavanda", "Lavanda", "Violeta suave", 262.0, .75),
        Theme("medianoche", "Medianoche", "Casi sin color", 228.0, .3),
        Theme("custom", "Tu color", "Elige el tono y la intensidad")
    )

    /** El tono de una cadena, como «hashHue» de la web (por puntos de código, con su primera mitad UTF-16). */
    fun hashHue(s: String): Int {
        var h = 0L
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val unit = s[i].code
            h = (h * 31 + unit) and 0xFFFFFFFFL
            i += Character.charCount(cp)
        }
        return (h % 360).toInt()
    }

    /** El tono que se le pone a una canción sin portada. */
    fun phHue(t: Track?): Double = if (t == null) 280.0 else if (t.demo) (t.extra["hue"] as? Number)?.toDouble() ?: 280.0 else hashHue(t.artist + t.title).toDouble()

    /** El color del momento: según la hora, la portada, uno fijo o el tuyo (como «themeTone»). */
    fun tone(theme: String, current: Track?, customHue: Double, customSat: Double, c: Calendar = Calendar.getInstance()): Pair<Double, Double> {
        val th = THEMES.firstOrNull { it.id == theme } ?: THEMES[0]
        val m = moment(c)
        return when (th.id) {
            "auto" -> m.franja.hue.toDouble() to 1.0
            "custom" -> customHue to customSat
            "cover" -> when {
                current != null && current.coverHue != null -> current.coverHue!! to (current.coverSat ?: 1.0)
                current != null -> phHue(current) to .9
                else -> m.franja.hue.toDouble() to 1.0
            }
            else -> th.hue to th.sat
        }
    }
}
