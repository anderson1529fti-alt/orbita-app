package __PACKAGE__

/**
 * Fase 4, parte 2 · JSON pequeño y propio (sin nada de Android: se prueba igual en el ordenador).
 * Lee a: Map<String, Any?> (en orden), List<Any?>, String, Double (o Long si es entero y cabe), Boolean y null.
 * Escribe igual que JSON.stringify de la web (los números, como los escribe JavaScript), para que lo que se guarda
 * aquí y lo que guardaba la parte web sea el mismo texto.
 */
object Json {
    fun parse(s: String): Any? { val p = P(s); p.ws(); val v = p.value(); p.ws(); if (p.i != s.length) p.err("sobra texto"); return v }

    fun parseOr(s: String?, d: Any?): Any? = try { if (s == null) d else parse(s) } catch (t: Throwable) { d }

    fun str(v: Any?): String { val b = StringBuilder(); write(b, v); return b.toString() }

    @Suppress("UNCHECKED_CAST")
    private fun write(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is String -> quote(b, v)
            is Boolean -> b.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> b.append(v.toString())
            is Double -> b.append(num(v))
            is Float -> b.append(num(v.toDouble()))
            is Number -> b.append(num(v.toDouble()))
            is Map<*, *> -> { b.append('{'); var first = true; for ((k, x) in v) { if (x === Undefined) continue; if (!first) b.append(','); first = false; quote(b, k.toString()); b.append(':'); write(b, x) }; b.append('}') }
            is Iterable<*> -> { b.append('['); var first = true; for (x in v) { if (!first) b.append(','); first = false; write(b, if (x === Undefined) null else x) }; b.append(']') }
            is Array<*> -> write(b, v.asList())
            is IntArray -> write(b, v.asList())
            is LongArray -> write(b, v.asList())
            is DoubleArray -> write(b, v.asList())
            is FloatArray -> write(b, v.asList())
            else -> quote(b, v.toString())
        }
    }

    /** «undefined» de la web: en un objeto, la clave no se escribe; en una lista, null. */
    object Undefined

    /** Un número como lo escribe JavaScript (Number.prototype.toString): 1, 1.5, 0.000001, 1e-7, 1e+21… */
    fun num(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == 0.0) return "0"
        if (d == Math.rint(d) && Math.abs(d) < 9007199254740992.0) return d.toLong().toString()
        // los dígitos más cortos que vuelven al mismo número, y su exponente
        val r = java.math.BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        val digits = r.unscaledValue().toString()
        val k = digits.length
        val n = k - r.scale()            // posición del punto decimal (como en ECMAScript: valor = 0.digits × 10^n)
        val sb = StringBuilder(); if (d < 0) sb.append('-')
        when {
            n in k..21 -> { sb.append(digits); repeat(n - k) { sb.append('0') } }
            n in 1..21 -> { sb.append(digits, 0, n); sb.append('.'); sb.append(digits, n, k) }
            n in -5..0 -> { sb.append("0."); repeat(-n) { sb.append('0') }; sb.append(digits) }
            else -> {
                val e = n - 1
                sb.append(digits[0]); if (k > 1) { sb.append('.'); sb.append(digits, 1, k) }
                sb.append('e'); sb.append(if (e >= 0) "+" else "-"); sb.append(Math.abs(e))
            }
        }
        return sb.toString()
    }

    // (como JSON.stringify: los de control con su escape; una mitad de pareja suelta como \uXXXX; una pareja buena, tal cual)
    private fun quote(b: StringBuilder, s: String) {
        b.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c == '\b' -> b.append("\\b")
                c == '\u000C' -> b.append("\\f")
                c < ' ' -> u(b, c)
                Character.isHighSurrogate(c) -> if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { b.append(c).append(s[i + 1]); i++ } else u(b, c)
                Character.isLowSurrogate(c) -> u(b, c)
                else -> b.append(c)
            }
            i++
        }
        b.append('"')
    }

    private fun u(b: StringBuilder, c: Char) { val h = Integer.toHexString(c.code); b.append("\\u"); repeat(4 - h.length) { b.append('0') }; b.append(h) }

    private class P(val s: String) {
        var i = 0
        fun err(m: String): Nothing = throw IllegalArgumentException("JSON: $m en $i")
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun value(): Any? {
            if (i >= s.length) err("se acaba")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else err("no esperaba '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) err("esperaba $w"); i += w.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') err("esperaba una clave")
                val k = string(); ws(); if (i >= s.length || s[i] != ':') err("esperaba ':'"); i++; ws()
                m[k] = value(); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                err("esperaba ',' o '}'")
            }
        }
        fun arr(): List<Any?> {
            val a = ArrayList<Any?>(); i++; ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws(); a.add(value()); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return a }
                err("esperaba ',' o ']'")
            }
        }
        fun string(): String {
            i++; val b = StringBuilder()
            while (true) {
                if (i >= s.length) err("texto sin cerrar")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) err("escape sin terminar")
                        when (val e = s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { if (i + 4 > s.length) err("\\u corto"); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> err("escape raro '$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun number(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var frac = false
            if (i < s.length && s[i] == '.') { frac = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { frac = true; i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++; while (i < s.length && s[i] in '0'..'9') i++ }
            val t = s.substring(st, i)
            if (!frac && t.length < 16) return t.toLong()
            return t.toDouble()
        }
    }

    // ---------------------------------------------------------------- para leer lo que viene de la web sin sustos
    fun obj(v: Any?): Map<String, Any?>? = @Suppress("UNCHECKED_CAST") (v as? Map<String, Any?>)
    fun list(v: Any?): List<Any?>? = v as? List<Any?>
    fun d(v: Any?, def: Double = 0.0): Double = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: def; is Boolean -> if (v) 1.0 else 0.0; else -> def }
    fun l(v: Any?, def: Long = 0): Long = when (v) { is Number -> v.toLong(); is String -> v.toDoubleOrNull()?.toLong() ?: def; else -> def }
    fun s(v: Any?, def: String = ""): String = when (v) { null -> def; is String -> v; is Number -> num(v.toDouble()); else -> v.toString() }
    fun b(v: Any?, def: Boolean = false): Boolean = when (v) { is Boolean -> v; null -> def; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty(); else -> true }
}
