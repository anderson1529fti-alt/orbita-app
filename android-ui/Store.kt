package __PACKAGE__

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Fase 4, parte 2 · Los datos de Órbita, en Android.
 *
 * Lo que la parte web guardaba en localStorage («orbita:*») y en IndexedDB («orbita»: tracks, audio, playlists, voice,
 * wishlist, analysis, events, lyrics), aquí: una base SQLite y archivos.
 *  - kv:  los ajustes (clave → el mismo texto JSON que había en localStorage)
 *  - rec: los registros de cada almacén (almacén + clave → JSON)
 *  - ev:  las escuchas, en orden (con su número de siempre)
 * Un Blob de la web (el audio de una canción importada, una portada, una grabación de «Tu voz») es aquí un archivo;
 * en el JSON queda en su sitio {"$blob": ruta relativa, "type", "size", "name"}.
 */
object Store {
    private var helper: Helper? = null
    lateinit var root: File; private set

    @Synchronized fun init(ctx: Context) {
        if (helper != null) return
        val app = ctx.applicationContext
        root = File(app.filesDir, "orbita").also { it.mkdirs() }
        helper = Helper(app)
    }

    val ready: Boolean get() = helper != null
    private val db: SQLiteDatabase get() = helper!!.writableDatabase

    // ---------------------------------------------------------------- ajustes
    fun kv(k: String): String? = db.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setKv(k: String, v: String?) {
        if (v == null) db.delete("kv", "k = ?", arrayOf(k))
        else db.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun kvAll(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        db.rawQuery("SELECT k, v FROM kv ORDER BY k", null).use { while (it.moveToNext()) m[it.getString(0)] = it.getString(1) }
        return m
    }

    // ---------------------------------------------------------------- registros
    fun rec(s: String, k: String): String? = db.rawQuery("SELECT v FROM rec WHERE s = ? AND k = ?", arrayOf(s, k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putRec(s: String, k: String, v: String) {
        db.insertWithOnConflict("rec", null, ContentValues().apply { put("s", s); put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delRec(s: String, k: String) { db.delete("rec", "s = ? AND k = ?", arrayOf(s, k)) }

    /** Los de un almacén, por orden de clave: de [limit] en [limit] a partir de la clave [after] (sin incluirla). */
    fun recs(s: String, after: String? = null, limit: Int = 100000): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val q = if (after == null) db.rawQuery("SELECT k, v FROM rec WHERE s = ? ORDER BY k LIMIT ?", arrayOf(s, limit.toString()))
                else db.rawQuery("SELECT k, v FROM rec WHERE s = ? AND k > ? ORDER BY k LIMIT ?", arrayOf(s, after, limit.toString()))
        q.use { while (it.moveToNext()) out.add(it.getString(0) to it.getString(1)) }
        return out
    }

    fun count(s: String): Int = db.rawQuery("SELECT COUNT(*) FROM rec WHERE s = ?", arrayOf(s)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun stores(): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT s FROM rec ORDER BY s", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    // ---------------------------------------------------------------- escuchas
    fun addEvent(v: String, n: Long? = null): Long {
        val cv = ContentValues().apply { if (n != null) put("n", n); put("v", v) }
        return db.insertWithOnConflict("ev", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun events(after: Long = 0, limit: Int = 100000): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT n, v FROM ev WHERE n > ? ORDER BY n LIMIT ?", arrayOf(after.toString(), limit.toString())).use { while (it.moveToNext()) out.add(it.getLong(0) to it.getString(1)) }
        return out
    }

    fun evCount(): Int = db.rawQuery("SELECT COUNT(*) FROM ev", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------------------------------------------------------------- archivos
    /** Dónde va el archivo de un Blob: el almacén, su clave y dónde estaba dentro del registro ("" = el registro entero). */
    fun blobPath(s: String, k: String, path: String): String = "blob/" + safe(s) + "/" + sha1(k + "\u0000" + path).take(24) + (if (path.isEmpty()) "" else "-" + safe(path).take(24))

    fun file(rel: String): File = File(root, rel)

    /** El archivo de una marca {"$blob": …} (o null si no lo es o no está). */
    fun blobFile(o: Any?): File? {
        val j = o as? JSONObject ?: return null
        val rel = j.optString("\$blob", "")
        if (rel.isEmpty()) return null
        val f = file(rel)
        return if (f.isFile) f else null
    }

    /** Lo que hay guardado en archivos: cuántos y cuánto ocupan (para comprobar un traspaso). */
    fun blobStats(): Pair<Int, Long> {
        var n = 0; var b = 0L
        File(root, "blob").walkTopDown().filter { it.isFile }.forEach { n++; b += it.length() }
        return n to b
    }

    // ---------------------------------------------------------------- todo
    /** Borra todo (antes de un traspaso desde cero). Los archivos de la web que no son de aquí no se tocan. */
    fun clearAll() {
        db.beginTransaction()
        try { db.delete("kv", null, null); db.delete("rec", null, null); db.delete("ev", null, null); db.setTransactionSuccessful() } finally { db.endTransaction() }
        File(root, "blob").deleteRecursively()
        File(root, "cache").deleteRecursively()
    }

    fun <T> tx(f: () -> T): T {
        db.beginTransaction()
        try { val r = f(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }

    fun sha1(s: String): String = hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)))
    fun hex(b: ByteArray): String { val sb = StringBuilder(b.size * 2); for (x in b) { val v = x.toInt() and 0xff; sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15]) }; return sb.toString() }
    fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val buf = ByteArray(1 shl 16); while (true) { val n = inp.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        return hex(md.digest())
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "orbita.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            d.execSQL("CREATE TABLE rec (s TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY (s, k))")
            d.execSQL("CREATE TABLE ev (n INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT NOT NULL)")
        }
        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {}
        override fun onConfigure(d: SQLiteDatabase) { d.enableWriteAheadLogging() }
    }
}
