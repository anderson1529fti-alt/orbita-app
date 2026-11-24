package __PACKAGE__

/**
 * Fase 4, parte 2 · YouTube dentro de Órbita (lo que en la web era «3.2 · YouTube inside Órbita» y la pestaña de YouTube
 * de «Buscar»). El vídeo se ve con el reproductor oficial de YouTube, en un WebView (lo único de web que queda).
 */
object Tubo {
    /** La clave de la API de YouTube (opcional: para buscar vídeos sin salir de Órbita). */
    fun key(): String = Prefs.str("ytKey", "")

    /** «ytFind»: busca y pone el primero (con clave); si no, la búsqueda en YouTube. */
    fun find(q: String) {
        Nativo.openExternal("https://www.youtube.com/results?search_query=" + Online.enc(q))
    }
}
