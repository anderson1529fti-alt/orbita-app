package __PACKAGE__

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * 5.2 · Fase 3, paso 5: el cerebro de comprensión de Nova, en Android.
 *
 * Es el mismo cerebro de siempre (el modelo de frases que se descargó una vez y vive en la carpeta de la app), pero ya no
 * corre dentro de la parte web: lo corre Android, con ONNX Runtime y varios núcleos del móvil. Entiende lo mismo y de la
 * misma forma (las mismas piezas de palabras, los mismos ejemplos y los mismos umbrales), más rápido y sin cargar la
 * parte web con el modelo. Habla con la app con los mismos mensajes que usaba la parte web: «init», «embed», «bank»,
 * «classify», «tokenize» (y «reset» para soltarlo).
 */
internal object Mind {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-comprension").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tok: Tok? = null
    private var prefix = ""
    private var bank: Bank? = null
    @Volatile var threads = 0
    @Volatile var loadMs = 0L
    @Volatile var calls = 0

    fun handle(ctx: Context, call: PluginCall) {
        val type = call.getString("type", "") ?: ""
        exec.execute {
            try {
                call.resolve(run(ctx, type, call))
            } catch (t: Throwable) {
                if (type == "init") close()
                call.resolve(JSObject().put("ok", false).put("error", (t.message ?: t.javaClass.simpleName).take(200)))
            }
        }
    }

    private fun run(ctx: Context, type: String, call: PluginCall): JSObject {
        val r = JSObject().put("ok", true)
        when (type) {
            "init" -> {
                val dir = File(ctx.filesDir, "nova-cerebro-pequeno")
                val tf = File(dir, "tokenizer.json"); val mf = File(dir, "modelo.onnx")
                if (!tf.exists() || !mf.exists()) throw IllegalStateException("falta " + (if (!tf.exists()) "tokenizer.json" else "modelo.onnx"))
                close()
                val t0 = SystemClock.uptimeMillis()
                prefix = call.getObject("cfg")?.optString("prefix", "") ?: ""
                tok = Tok.load(tf)
                val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
                val cores = Runtime.getRuntime().availableProcessors()
                threads = call.getInt("threads", 0)?.takeIf { it > 0 }?.coerceAtMost(8) ?: (cores / 2).coerceIn(1, 4)
                val o = OrtSession.SessionOptions()
                o.setIntraOpNumThreads(threads)
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // (frases de distinta longitud: sin estas dos, la memoria crece con cada frase)
                o.setMemoryPatternOptimization(false)
                o.setCPUArenaAllocator(false)
                session = e.createSession(mf.absolutePath, o)
                loadMs = SystemClock.uptimeMillis() - t0
                val t1 = SystemClock.uptimeMillis()
                val v = embed("hola, ¿qué tal?")
                r.put("dim", v.size).put("ms", SystemClock.uptimeMillis() - t1).put("vocab", tok!!.scores.size)
                    .put("threads", threads).put("load", loadMs)
            }
            "embed" -> {
                val texts = strings(call.getArray("texts"))
                val t0 = SystemClock.uptimeMillis()
                val vs = texts.map { embed(it) }
                val d = if (vs.isEmpty()) 0 else vs[0].size
                val bb = ByteBuffer.allocate(vs.size * d * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (v in vs) for (x in v) bb.putFloat(x)
                r.put("vecs", Base64.encodeToString(bb.array(), Base64.NO_WRAP)).put("dim", d).put("ms", SystemClock.uptimeMillis() - t0)
            }
            "bank" -> {
                val raw = Base64.decode(call.getString("vecs", "") ?: "", Base64.DEFAULT)
                val fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val vecs = FloatArray(fb.remaining()); fb.get(vecs)
                val la = call.getArray("labels") ?: JSArray()
                val labels = IntArray(la.length()) { la.optInt(it) }
                val b = Bank(vecs, labels, call.getInt("nI", 0) ?: 0, call.getInt("dim", 0) ?: 0)
                if (b.d <= 0 || b.vecs.size < b.labels.size * b.d) throw IllegalArgumentException("banco incompleto")
                b.cal = b.calibrate()
                bank = b
                r.put("cal", b.cal)
            }
            "classify" -> {
                val b = bank ?: throw IllegalStateException("sin ejemplos")
                val t0 = SystemClock.uptimeMillis()
                val q = embed(call.getString("text", "") ?: "")
                val s = b.scoreAll(q, -1)
                r.put("intent", s.b).put("s1", s.s1).put("second", s.c).put("s2", s.s2).put("raw", s.raw).put("cal", b.cal)
                    .put("ms", SystemClock.uptimeMillis() - t0)
            }
            "tokenize" -> {
                val t = tok ?: throw IllegalStateException("sin cerebro")
                val out = JSArray()
                for (s in strings(call.getArray("texts"))) { val a = JSArray(); for (id in t.encode(s)) a.put(id); out.put(a) }
                r.put("ids", out)
            }
            "reset" -> close()
            else -> throw IllegalArgumentException("no sé hacer «$type»")
        }
        calls++
        return r
    }

    /** Se cierra la app: suelta el cerebro (la próxima vez se vuelve a cargar). */
    fun release() { exec.execute { close() } }

    private fun strings(a: JSArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }
    }

    fun close() {
        try { session?.close() } catch (ignored: Throwable) { }
        session = null; tok = null; bank = null
    }

    /** Una frase → un vector de significado (los mismos pasos que hacía la parte web). */
    private fun embed(text: String): FloatArray {
        val s = session ?: throw IllegalStateException("sin cerebro")
        val e = env ?: throw IllegalStateException("sin cerebro")
        val ids = tok!!.encode(prefix + text)
        val n = ids.size
        val feeds = LinkedHashMap<String, OnnxTensor>()
        try {
            for (name in s.inputNames) {
                val arr = when (name) {
                    "input_ids" -> LongArray(n) { ids[it].toLong() }
                    "attention_mask" -> LongArray(n) { 1L }
                    "token_type_ids" -> LongArray(n) { 0L }
                    else -> null
                } ?: continue
                feeds[name] = OnnxTensor.createTensor(e, LongBuffer.wrap(arr), longArrayOf(1L, n.toLong()))
            }
            s.run(feeds).use { res ->
                val outs = s.outputNames
                val name = when {
                    "sentence_embedding" in outs -> "sentence_embedding"
                    "last_hidden_state" in outs -> "last_hidden_state"
                    else -> outs.first()
                }
                val t = res.get(name).get() as OnnxTensor
                val shape = t.info.shape
                val fb = t.floatBuffer
                val d = shape[shape.size - 1].toInt()
                val v = FloatArray(d)
                if (shape.size == 2) { for (k in 0 until d) v[k] = fb.get(k) }
                else {
                    // la media de todas las piezas (sumarlas basta: luego se normaliza)
                    val rows = shape[1].toInt()
                    for (rr in 0 until rows) for (k in 0 until d) v[k] += fb.get(rr * d + k)
                }
                var q = 0.0
                for (k in 0 until d) q += v[k].toDouble() * v[k].toDouble()
                q = Math.sqrt(q); if (q == 0.0) q = 1.0
                for (k in 0 until d) v[k] = (v[k].toDouble() / q).toFloat()
                return v
            }
        } finally {
            for (t in feeds.values) try { t.close() } catch (ignored: Throwable) { }
        }
    }

    /** Los ejemplos de Nova (y los que le enseñas), y cómo de seguro hay que estar para hacer caso. */
    private class Bank(val vecs: FloatArray, val labels: IntArray, val nI: Int, val d: Int) {
        var cal: JSObject = JSObject()

        class Score(val b: Int, val s1: Double, val c: Int, val s2: Double, val raw: Double)

        /** Los ejemplos más parecidos de cada intención (el mejor y el segundo, como en la parte web). */
        fun scoreAll(q: FloatArray, skip: Int, qo: Int = 0): Score {
            val best = FloatArray(nI) { -1f }; val sec = FloatArray(nI) { -1f }
            for (i in labels.indices) {
                if (i == skip) continue
                var s = 0.0; val o = i * d
                for (k in 0 until d) s += q[qo + k].toDouble() * vecs[o + k].toDouble()
                val l = labels[i]
                if (l < 0 || l >= nI) continue
                if (s > best[l]) { sec[l] = best[l]; best[l] = s.toFloat() } else if (s > sec[l]) sec[l] = s.toFloat()
            }
            val sc = FloatArray(nI)
            for (l in 0 until nI) sc[l] = if (best[l] < -0.5f) -1f else (0.75 * best[l] + 0.25 * (if (sec[l] > -0.5f) (best[l].toDouble() + sec[l]) / 2 else best[l].toDouble())).toFloat()
            var b = 0; var c = -1
            for (l in 1 until nI) if (sc[l] > sc[b]) b = l
            for (l in 0 until nI) if (l != b && (c < 0 || sc[l] > sc[c])) c = l
            return Score(b, if (nI > 0) sc[b].toDouble() else -1.0, c, if (c >= 0) sc[c].toDouble() else -1.0, if (nI > 0) best[b].toDouble() else -1.0)
        }

        fun calibrate(): JSObject {
            class Row(val s1: Double, val s2: Double, val ok: Boolean)
            val rows = ArrayList<Row>(labels.size)
            for (i in labels.indices) { val r = scoreAll(vecs, i, i * d); rows.add(Row(r.s1, r.s2, r.b == labels[i])) }
            fun med(a: List<Double>): Double { val s = a.sorted(); return if (s.isEmpty()) 0.0 else s[s.size shr 1] }
            val good = rows.filter { it.ok }
            val pos = med(good.map { it.s1 }); val neg = med(rows.map { it.s2 }); val spread = Math.max(0.02, pos - neg)
            // la nota más baja con la que al menos el 90 % de los ejemplos se entienden bien
            val sorted = rows.sortedWith(compareByDescending { it.s1 })
            var ok = 0; var tau = if (sorted.isEmpty()) 0.0 else sorted[sorted.size - 1].s1
            sorted.forEachIndexed { i, r -> if (r.ok) ok++; if (ok.toDouble() / (i + 1) >= 0.9) tau = r.s1 }
            val loo = good.size.toDouble() / Math.max(1, rows.size)
            return JSObject().put("tau", Math.max(tau, neg + 0.15 * spread)).put("margin", 0.08 * spread).put("spread", spread)
                .put("pos", pos).put("neg", neg).put("loo", loo)
        }
    }
}

/**
 * Las piezas de palabras del modelo (SentencePiece «Unigram», leído de tokenizer.json): la misma forma de partir las
 * frases que usaba la parte web, para que Android entienda exactamente igual.
 */
internal class Tok private constructor(
    private val ids: HashMap<String, Int>, val scores: FloatArray, private val unk: Int, private val unkScore: Double,
    private val maxLen: Int, private val norm: Norm, private val rep: String, private val prefix: Boolean,
    private val pre: IntArray, private val post: IntArray
) {
    companion object {
        // (los mismos juegos de caracteres que la parte web: sus «\s» y su trim() no son los de Java)
        private const val JS_WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
        private val WS_RUN = Regex("[$JS_WS]+")
        private val WS_ONE = Regex("[$JS_WS]")
        private val TRIM = Regex("^[$JS_WS]+|[$JS_WS]+$")
        private val NMT_DROP = Regex("[\\u0001-\\u0008\\u000B\\u000E-\\u001F\\u007F\\u008F\\u009F]")
        private val NMT_SPACE = Regex("[\\u0009\\u000A\\u000C\\u000D\\u1680\\u200B-\\u200F\\u2028\\u2029\\u2581\\uFEFF\\uFFFD]")
        private val MARKS = Regex("[\\u0300-\\u036F]")
        private val LEAD = Regex("^[$JS_WS]+")
        private val TAIL = Regex("[$JS_WS]+$")
        fun jsTrim(s: String) = s.replace(TRIM, "")

        fun load(f: File): Tok {
            var model: Model? = null
            var normalizer: JSONObject? = null; var preTok: JSONObject? = null; var postProc: JSONObject? = null
            var added: JSONArray? = null
            JsonReader(InputStreamReader(f.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { jr ->
                jr.beginObject()
                while (jr.hasNext()) {
                    when (jr.nextName()) {
                        "model" -> model = readModel(jr)
                        "normalizer" -> normalizer = readAny(jr) as? JSONObject
                        "pre_tokenizer" -> preTok = readAny(jr) as? JSONObject
                        "post_processor" -> postProc = readAny(jr) as? JSONObject
                        "added_tokens" -> added = readAny(jr) as? JSONArray
                        else -> jr.skipValue()
                    }
                }
                jr.endObject()
            }
            val m = model ?: throw IllegalStateException("tokenizer.json sin modelo")
            if (m.type != "Unigram") throw IllegalStateException("El tokenizador no es de tipo Unigram")
            val ms = metaspaceOf(preTok)
            val rep = ms?.optString("replacement", "")?.takeIf { it.isNotEmpty() } ?: "▁"
            val prefix = ms == null || (!(ms.has("add_prefix_space") && ms.opt("add_prefix_space") == false) && ms.optString("prepend_scheme", "") != "never")
            val unk = if (m.unkId >= 0) m.unkId else (m.ids["<unk>"] ?: 0)
            val (pre, post) = specialIds(postProc, added, m.ids)
            return Tok(m.ids, m.scores, unk, m.min - 10, Math.min(m.maxLen, 48), Norm(normalizer), rep, prefix, pre, post)
        }

        private class Model(val type: String, val ids: HashMap<String, Int>, val scores: FloatArray, val min: Double, val maxLen: Int, val unkId: Int)

        /** El vocabulario (250 000 piezas) leído sin cargar el archivo entero en memoria. */
        private fun readModel(jr: JsonReader): Model {
            var type = ""; var unkId = -1
            val ids = HashMap<String, Int>(1 shl 19)
            var scores = FloatArray(1 shl 18); var n = 0; var min = Double.POSITIVE_INFINITY; var maxLen = 1
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "type" -> type = jr.nextString()
                    "unk_id" -> { if (jr.peek() == JsonToken.NULL) { jr.nextNull() } else { unkId = jr.nextInt() } }
                    "vocab" -> {
                        jr.beginArray()
                        while (jr.hasNext()) {
                            jr.beginArray()
                            val p = jr.nextString(); val s = jr.nextDouble()
                            while (jr.hasNext()) jr.skipValue()
                            jr.endArray()
                            if (n == scores.size) scores = scores.copyOf(n * 2)
                            if (!ids.containsKey(p)) ids[p] = n
                            scores[n] = s.toFloat()
                            if (s < min) min = s
                            val l = p.codePointCount(0, p.length); if (l > maxLen) maxLen = l
                            n++
                        }
                        jr.endArray()
                    }
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            return Model(type, ids, scores.copyOf(n), min, maxLen, unkId)
        }

        private fun readAny(jr: JsonReader): Any? = when (jr.peek()) {
            JsonToken.BEGIN_OBJECT -> { val o = JSONObject(); jr.beginObject(); while (jr.hasNext()) { val k = jr.nextName(); o.put(k, readAny(jr) ?: JSONObject.NULL) }; jr.endObject(); o }
            JsonToken.BEGIN_ARRAY -> { val a = JSONArray(); jr.beginArray(); while (jr.hasNext()) a.put(readAny(jr) ?: JSONObject.NULL); jr.endArray(); a }
            JsonToken.STRING -> jr.nextString()
            JsonToken.NUMBER -> { val s = jr.nextString(); s.toLongOrNull() ?: s.toDouble() }
            JsonToken.BOOLEAN -> jr.nextBoolean()
            JsonToken.NULL -> { jr.nextNull(); null }
            else -> { jr.skipValue(); null }
        }

        private fun metaspaceOf(p: JSONObject?): JSONObject? {
            if (p == null) return null
            if (p.optString("type") == "Metaspace") return p
            if (p.optString("type") == "Sequence") {
                val a = p.optJSONArray("pretokenizers") ?: return null
                for (i in 0 until a.length()) { val m = metaspaceOf(a.optJSONObject(i)); if (m != null) return m }
            }
            return null
        }

        private fun specialIds(p: JSONObject?, added: JSONArray?, ids: Map<String, Int>): Pair<IntArray, IntArray> {
            fun look(name: String): Int? {
                if (added != null) for (i in 0 until added.length()) { val a = added.optJSONObject(i) ?: continue; if (a.optString("content") == name && a.has("id")) return a.optInt("id") }
                return ids[name]
            }
            fun fromTemplate(pp: JSONObject): Pair<IntArray, IntArray> {
                val pre = ArrayList<Int>(); val post = ArrayList<Int>(); var seen = false
                val single = pp.optJSONArray("single") ?: JSONArray()
                val specials = pp.optJSONObject("special_tokens")
                for (i in 0 until single.length()) {
                    val it = single.optJSONObject(i) ?: continue
                    if (it.has("Sequence")) { seen = true; continue }
                    val stk = it.optJSONObject("SpecialToken") ?: continue
                    val id = stk.optString("id")
                    val st = specials?.optJSONObject(id)
                    val v = if (st != null) (st.optJSONArray("ids") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optInt(it) } } else listOfNotNull(look(id))
                    (if (seen) post else pre).addAll(v)
                }
                return Pair(pre.toIntArray(), post.toIntArray())
            }
            if (p != null) {
                val list = if (p.optString("type") == "Sequence") (p.optJSONArray("processors") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } else listOf(p)
                for (pp in list) {
                    val t = pp.optString("type")
                    if (t == "TemplateProcessing") return fromTemplate(pp)
                    if (t == "RobertaProcessing" || t == "BertProcessing") {
                        val cls = pp.optJSONArray("cls"); val sep = pp.optJSONArray("sep")
                        return Pair(if (cls != null) intArrayOf(cls.optInt(1)) else IntArray(0), if (sep != null) intArrayOf(sep.optInt(1)) else IntArray(0))
                    }
                }
            }
            val cls = look("<s>") ?: look("[CLS]"); val sep = look("</s>") ?: look("[SEP]")
            return Pair(if (cls != null) intArrayOf(cls) else IntArray(0), if (sep != null) intArrayOf(sep) else IntArray(0))
        }

    }

    /** Cómo se limpia la frase antes de partirla (lo mismo que la parte web, paso a paso). */
    private class Norm(n: JSONObject?) {
        private val type = n?.optString("type", "") ?: ""
        private val kids: List<Norm> = if (type == "Sequence") (n?.optJSONArray("normalizers") ?: JSONArray()).let { a -> (0 until a.length()).map { Norm(a.optJSONObject(it)) } } else emptyList()
        private val left = !(n != null && n.has("strip_left") && n.opt("strip_left") == false)
        private val right = !(n != null && n.has("strip_right") && n.opt("strip_right") == false)
        private val pattern = n?.optJSONObject("pattern")
        private val re: Regex? = if (type == "Replace" && pattern != null && pattern.has("Regex")) Regex(pattern.optString("Regex")) else null
        private val lit = if (type == "Replace" && pattern != null && !pattern.has("Regex")) pattern.optString("String", "") else ""
        private val content = n?.optString("content", "") ?: ""
        private val prepend = n?.optString("prepend", "") ?: ""
        private val clean = !(n != null && n.has("clean_text") && n.opt("clean_text") == false)
        private val lower = !(n != null && n.has("lowercase") && n.opt("lowercase") == false)
        private val stripAcc = n?.opt("strip_accents").let { sa -> sa == true || ((sa == null || sa == JSONObject.NULL) && lower) }

        fun apply(s: String): String = when (type) {
            "Sequence" -> kids.fold(s) { acc, k -> k.apply(acc) }
            "NFKC" -> Normalizer.normalize(s, Normalizer.Form.NFKC)
            "NFC" -> Normalizer.normalize(s, Normalizer.Form.NFC)
            "NFKD" -> Normalizer.normalize(s, Normalizer.Form.NFKD)
            "NFD" -> Normalizer.normalize(s, Normalizer.Form.NFD)
            "Nmt" -> s.replace(NMT_DROP, "").replace(NMT_SPACE, " ")
            // (la tabla compilada de SentencePiece es, en esencia, NFKC más la limpieza de Nmt)
            "Precompiled" -> Normalizer.normalize(s.replace(NMT_DROP, "").replace(NMT_SPACE, " "), Normalizer.Form.NFKC)
            "Lowercase" -> s.lowercase()
            "Strip" -> { var x = s; if (left) x = x.replace(LEAD, ""); if (right) x = x.replace(TAIL, ""); x }
            "StripAccents" -> Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
            "Replace" -> if (re != null) s.replace(re, Regex.escapeReplacement(content)) else if (lit.isEmpty()) s else s.replace(lit, content)
            "Prepend" -> prepend + s
            "BertNormalizer" -> {
                var x = s
                if (clean) x = x.replace(NMT_DROP, "").replace(WS_ONE, " ")
                if (lower) x = x.lowercase()
                if (stripAcc) x = Normalizer.normalize(x, Normalizer.Form.NFD).replace(MARKS, "")
                x
            }
            else -> s
        }
    }

    /** La mejor forma de partir una palabra en piezas (Viterbi sobre la nota de cada pieza). */
    private fun viterbi(word: String): List<Int> {
        val cp = word.codePoints().toArray(); val n = cp.size
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }; val from = IntArray(n + 1); val pid = IntArray(n + 1)
        best[0] = 0.0
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            sb.setLength(0); var single = false
            var l = 1
            while (l <= maxLen && i + l <= n) {
                sb.appendCodePoint(cp[i + l - 1])
                val id = ids[sb.toString()]
                if (id != null) {
                    if (l == 1) single = true
                    val v = best[i] + scores[id]
                    if (v > best[i + l]) { best[i + l] = v; from[i + l] = i; pid[i + l] = id }
                }
                l++
            }
            if (!single) { val v = best[i] + unkScore; if (v > best[i + 1]) { best[i + 1] = v; from[i + 1] = i; pid[i + 1] = unk } }
        }
        val out = ArrayList<Int>()
        var k = n
        while (k > 0) { out.add(pid[k]); k = from[k] }
        out.reverse()
        // (varias piezas desconocidas seguidas cuentan como una)
        val res = ArrayList<Int>(out.size)
        for (i in out.indices) if (!(out[i] == unk && i > 0 && out[i - 1] == unk)) res.add(out[i])
        return res
    }

    fun encode(text: String, max: Int = 128): IntArray {
        val words = norm.apply(jsTrim(text)).split(WS_RUN).filter { it.isNotEmpty() }
        val out = ArrayList<Int>()
        words.forEachIndexed { i, w -> out.addAll(viterbi((if (i > 0 || prefix) rep else "") + w)) }
        val keep = max - pre.size - post.size
        val body = if (keep >= 0) out.take(keep) else out.dropLast(Math.min(out.size, -keep))
        return pre + body.toIntArray() + post
    }
}
