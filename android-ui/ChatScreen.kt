package __PACKAGE__

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 4.4 · Fase 3, primer paso: la conversación con Nova, dibujada por Android (Kotlin y Compose) con las medidas de la
 * pantalla de siempre. Lo que Nova entiende y contesta lo sigue decidiendo la app: aquí solo se enseña y se escribe.
 */
private const val I_MIC = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1 -6 0V6a3 3 0 0 1 3 -3zM5 11a7 7 0 0 0 14 0M12 18v3"

@Composable
fun ChatScreen() {
    val sk = rememberSkin()
    val p = sk.pal
    val pad = UiState.chatPad
    val msgs = UiState.chatMsgs
    val list = UiState.chatList
    val focus = LocalFocusManager.current

    // lo último, siempre a la vista: al llegar un mensaje, y cuando el teclado se abre y la pantalla encoge
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text) {
        if (msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1, 100000)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { list.layoutInfo.viewportSize.height }.collectLatest {
            delay(120)
            val n = UiState.chatMsgs.size
            if (n > 0) list.scrollToItem(n - 1, 100000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
        val bubbleMax = (maxWidth - pad[1].dp - pad[3].dp) * 0.86f
        Column(Modifier.fillMaxSize().padding(start = pad[3].dp, top = pad[0].dp, end = pad[1].dp, bottom = pad[2].dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // quién es y qué suena · borrar la conversación · cerrar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    T(UiState.chatName, 20.8f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f, maxLines = 1)
                    T(UiState.chatNow, 13.44f * sk.tk, p.muted, maxLines = 1)
                }
                Btn("Borrar", sk, small = true) { UiState.send("chatClear") }
                Spacer(Modifier.width(8.dp))
                Btn("Cerrar", sk, small = true) { focus.clearFocus(); UiState.send("chatClose") }
            }
            // los mensajes
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs.size) { i ->
                    val m = msgs[i]
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.me) Alignment.CenterEnd else Alignment.CenterStart) { Bubble(m, sk, bubbleMax) }
                }
            }
            // cosas que se le pueden pedir
            if (UiState.chatChips.isNotEmpty()) Flow(8.dp) { for (c in UiState.chatChips) Chip(c, sk) }
            ChatInput(sk)
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp))
    }
}

/** Un mensaje: los míos a la derecha, con el color de la app; los de Nova a la izquierda. */
@Composable
private fun Bubble(m: UiMsg, sk: Skin, maxW: Dp) {
    val p = sk.pal
    val r = (18f * sk.round).dp
    val r4 = (4f * sk.round).dp
    val shape = if (m.me) RoundedCornerShape(r, r, r4, r) else RoundedCornerShape(r, r, r, r4)
    var mod = Modifier.widthIn(max = maxW).clip(shape)
    mod = if (m.me) mod.background(p.grad) else mod.background(Color.White.copy(alpha = 0.06f)).border(sk.b, p.line2, shape)
    val b = if (m.me) 0.dp else sk.b
    Box(mod.padding(horizontal = 14.dp + b, vertical = 10.dp + b)) {
        T(m.text, 15f * sk.tk, if (m.me) p.onAccent else p.fg, weight = if (m.me) FontWeight.Bold else FontWeight.Normal, lh = 1.4f)
    }
}

/** Una sugerencia: al tocarla se le dice a Nova tal cual. */
@Composable
private fun Chip(text: String, sk: Skin) {
    Box(Modifier.press { UiState.send("chatCmd") { it.put("text", text) } }.dashed(sk.pal.line2, 999.dp, sk.b).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) {
        T(text, 12.8f * sk.tk, sk.pal.muted, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** Las sugerencias, una detrás de otra, y al renglón siguiente cuando no caben. */
@Composable
private fun Flow(gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val g = gap.roundToPx()
        val maxW = constraints.maxWidth
        val ps = measurables.map { it.measure(Constraints(0, maxW, 0, Constraints.Infinity)) }
        val xs = IntArray(ps.size)
        val ys = IntArray(ps.size)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in ps.indices) {
            val pl = ps[i]
            if (x > 0 && x + pl.width > maxW) { x = 0; y += rowH + g; rowH = 0 }
            xs[i] = x; ys[i] = y
            x += pl.width + g
            rowH = maxOf(rowH, pl.height)
        }
        layout(maxW, y + rowH) { for (i in ps.indices) ps[i].place(xs[i], ys[i]) }
    }
}

/** Escribirle: la caja, el micrófono (si el móvil tiene) y «Enviar». */
@Composable
private fun ChatInput(sk: Skin) {
    val p = sk.pal
    val shape = sk.r(14f)
    var focused by remember { mutableStateOf(false) }
    fun submit() {
        val v = UiState.chatDraft.trim()
        if (v.isEmpty()) return
        UiState.chatDraft = ""
        UiState.send("chatSend") { it.put("text", v) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var box = Modifier.fillMaxWidth()
        if (focused) box = box.drawBehind {
            // el halo de la caja con el cursor dentro (3 de ancho, por fuera)
            val w = 3.dp.toPx()
            drawRoundRect(p.wash, Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w), CornerRadius((14f * sk.round).dp.toPx() + w / 2), style = Stroke(w))
        }
        Box(box.clip(shape).background(Color.Black.copy(alpha = 0.4f)).border(sk.b, if (focused) p.accent else p.line2, shape).padding(horizontal = 16.dp + sk.b, vertical = 13.dp + sk.b)) {
            BasicTextField(
                value = UiState.chatDraft, onValueChange = { UiState.chatDraft = it.take(240) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mensaje para la DJ" }.onFocusChanged {
                    if (it.isFocused && !focused) UiState.send("chatFocus")
                    focused = it.isFocused
                },
                singleLine = true, textStyle = style(15.68f * sk.tk, p.fg), cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (UiState.chatDraft.isEmpty()) T("Escribe a la DJ…", 15.68f * sk.tk, p.faint, maxLines = 1)
                        inner()
                    }
                }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (UiState.chatMic) {
                val bs = sk.r(14f)
                Box(Modifier.semantics { contentDescription = "Háblale" }.press { UiState.send("chatMic") }.clip(bs).background(Color.White.copy(alpha = 0.04f)).border(sk.b, p.line2, bs)
                    .padding(horizontal = 18.dp + sk.b, vertical = 12.dp + sk.b)) {
                    Box(Modifier.height((21.75f * sk.tk).dp), contentAlignment = Alignment.Center) { Ico(I_MIC, 18f, p.fg) }
                }
            }
            Btn("Enviar", sk, Modifier.weight(1f), primary = true) { submit() }
        }
    }
}
