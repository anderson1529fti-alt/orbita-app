package __PACKAGE__

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Fase 4, parte 2 · Las tarjetas de «Ajustes» de la voz propia de Nova (Piper), su cerebro de comprensión y su cerebro
 * grande, con lo que hay instalado en el móvil.
 * (EN OBRAS: la descarga y el uso de los tres se pasan a Android en el paso de los cerebros; hasta entonces, la tarjeta
 * dice lo que hay y sus botones avisan.)
 */
object VozPropia {
    fun st(): Map<String, Any?>? = Json.obj(Prefs.get("nv"))
    fun on(): Boolean = Prefs.bool("nvOn", true)
}
object Comprension {
    fun st(): Map<String, Any?>? = Json.obj(Prefs.get("nb"))
    fun on(): Boolean = Prefs.bool("nbOn", true)
}
object CerebroGrande {
    fun st(): Map<String, Any?>? = Json.obj(Prefs.get("bb"))
    fun on(): Boolean = Prefs.bool("bbOn", true)
    /** Si esta compilación trae el motor del cerebro grande (llama.cpp). */
    fun included(): Boolean = try { Class.forName("orbita.nova.NovaLlm"); true } catch (t: Throwable) { false }
}

private fun pendiente() = Nativo.toast("Esto llega en el siguiente paso de la Órbita nativa.", 3200)

@Composable
internal fun VozPropiaCard(sk: Skin) {
    val st = VozPropia.st()
    NvCard(sk) {
        if (st != null) {
            DText("Voz propia: ${Json.s(st["label"])}", "${Json.s(st["accent"])} · ${Json.s(st["mb"])} MB · motor nativo · funciona sin internet", sk)
            RowWrap {
                Toggle("Usar mi voz propia", VozPropia.on(), sk) { Ajustes.set("nvOn", !VozPropia.on()) }
                HBtn("Probar", sk, kind = 1) { pendiente() }
                HBtn("Cambiar de voz", sk, kind = 1) { pendiente() }
                HBtn("Borrar", sk, kind = 1) { pendiente() }
            }
        } else {
            DText("Voz propia para ${Nova.name()}", "Una voz neuronal en español que suena igual en todos los móviles y no necesita internet. Se descarga una vez (unos 100 MB con el motor).", sk)
            RowWrap { HBtn("Elegir voz", sk, primary = true, kind = 1) { pendiente() } }
        }
        T("Motor: Piper (MIT) con espeak-ng (GPL-3.0) y ONNX Runtime (MIT). Las voces son de los conjuntos de datos que indica su ficha en el catálogo de Piper.", 11.84f * sk.tk, sk.pal.muted)
    }
}

@Composable
internal fun ComprensionCard(sk: Skin) {
    val st = Comprension.st()
    val n = Nova.name()
    NvCard(sk) {
        if (st == null) {
            DText("Cerebro de $n", "Un modelo de lenguaje que entiende lo que le dices con tus palabras («esta es una pasada», «baja las revoluciones», «estoy reventado») y lo convierte en acciones o en respuestas. Funciona en tu móvil, sin internet, y nunca se inventa datos. Se descarga una vez: unos 145 MB.", sk)
            RowWrap { HBtn("Descargar el cerebro", sk, primary = true, kind = 1) { pendiente() } }
            Note("Mejor con wifi." + if (Ia.ready()) " Con tu clave de Claude, además, charla libremente cuando la conversación lo pide." else "", sk)
            T("Modelo: multilingual-e5-small (Microsoft, licencia MIT). Motor: ONNX Runtime (MIT).", 11.84f * sk.tk, sk.pal.muted)
        } else {
            DText("Cerebro de $n", "listo para arrancar · ${Json.s(st["mb"])} MB · sin internet", sk)
            RowWrap {
                Toggle("Usar el cerebro", Comprension.on(), sk) { Ajustes.set("nbOn", !Comprension.on()) }
                HBtn("Probar y enseñar", sk, kind = 1) { pendiente() }
                HBtn("Comprobar", sk, kind = 1) { pendiente() }
                HBtn("Borrar", sk, kind = 1) { pendiente() }
            }
            if (Ia.ready()) Note("Con tu clave de Claude, además, charla libremente cuando la conversación lo pide.", sk)
            T("Modelo: ${Json.s(st["label"])} (${Json.s(st["by"])}, licencia ${Json.s(st["lic"])}), en formato ONNX de Xenova. Motor: ONNX Runtime (MIT).", 11.84f * sk.tk, sk.pal.muted)
        }
    }
}

@Composable
internal fun CerebroGrandeCard(sk: Skin) {
    val st = CerebroGrande.st()
    val n = Nova.name()
    NvCard(sk) {
        if (st == null) {
            DText("Cerebro grande de $n", "Un modelo de lenguaje (Qwen3 1.7B) que corre en tu móvil, sin internet, para charlar contigo con sus propias palabras. Se descarga una vez: unos 1,1 GB.", sk)
            RowWrap { HBtn("Descargar", sk, primary = true, kind = 1) { pendiente() } }
        } else {
            DText("Cerebro grande de $n", "instalado · ${Ajustes.gb(Json.d(st["size"]))} · sin internet", sk)
            RowWrap {
                Toggle("Usar el cerebro grande", CerebroGrande.on(), sk) { Ajustes.set("bbOn", !CerebroGrande.on()) }
                HBtn("Comprobar", sk, kind = 1) { pendiente() }
                HBtn("Borrar", sk, kind = 1) { pendiente() }
            }
        }
    }
}
