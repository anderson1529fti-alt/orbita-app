package __PACKAGE__

/**
 * Fase 4, parte 2 · «Mis listas» (las listas guardadas, como en la web: {id, name, description, trackIds, createdAt…}).
 */
object Listas {
    fun uid(p: String): String = p + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e10).toLong(), 36).take(5)

    fun all(): List<Map<String, Any?>> = Lib.playlists

    fun put(p: Map<String, Any?>) {
        val id = Json.s(p["id"])
        val i = Lib.playlists.indexOfFirst { Json.s(it["id"]) == id }
        if (i >= 0) Lib.playlists[i] = p else Lib.playlists.add(p)
        Store.putRec("playlists", id, Json.str(p))
        Nativo.onLists()
    }

    /** Guarda unas canciones como lista nueva («Guardar lista» de las mezclas). */
    fun saveIds(name: String, ids: List<String>, desc: String = "", extra: Map<String, Any?> = emptyMap()) {
        val mx = Mezclas.mix
        val p = LinkedHashMap<String, Any?>()
        p["id"] = uid("p"); p["name"] = name; p["description"] = desc.ifEmpty { if (mx != null && mx.ids == ids) mx.desc else "" }; p["trackIds"] = ids.toList()
        p["createdAt"] = System.currentTimeMillis(); p["moment"] = Momento.moment().label
        if (mx != null && mx.ids == ids) { p["mood"] = mx.mood; p["source"] = mx.source; p["intros"] = mx.intros; p["greeting"] = mx.greeting }
        p.putAll(extra)
        put(p)
        Nativo.toast("Guardada en Mis listas")
    }
}
