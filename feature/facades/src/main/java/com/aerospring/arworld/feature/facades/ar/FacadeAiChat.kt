package com.aerospring.arworld.feature.facades.ar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.aerospring.arworld.feature.facades.data.FacadeAiAction

/** Сообщение в ленте: реплика и, у ответа ИИ, кнопка действия (сайт, звонок, письмо). */
internal data class ChatItem(val role: String, val content: String, val action: FacadeAction? = null, val isError: Boolean = false)

/** Действие от ИИ → то же действие, что у модели (сайт, звонок, письмо). */
internal fun FacadeAiAction.toFacadeAction(): FacadeAction? = when (type) {
    "open_url" -> url?.takeIf { it.startsWith("http") }?.let { FacadeAction.OpenUrl(it, "Открыть сайт") }
    "call_phone" -> phone?.let { FacadeAction.Call(it, "Позвонить: $it") }
    "compose_email" -> email?.let { FacadeAction.Email(it, subject, "Написать: $it") }
    else -> null
}

/**
 * ИИ-помощник фасада: полноэкранный диалог. История — только на телефоне, пока открыт фасад.
 * Действие от ИИ не выполняется само: под ответом кнопка, нажимает человек.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FacadeAiChatDialog(
    title: String,
    items: List<ChatItem>,
    sending: Boolean,
    onSend: (String) -> Unit,
    onAction: (FacadeAction) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Своё окно у Dialog: отдаём вставки клавиатуры Compose (imePadding), как в AR.Визитках.
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) } }

        Surface(Modifier.fillMaxSize().imePadding()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        actions = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") } }
                    )
                },
                bottomBar = { InputRow(sending, onSend) }
            ) { inner ->
                val listState = rememberLazyListState()
                LaunchedEffect(items.size, sending) {
                    val last = items.size - 1 + if (sending) 1 else 0
                    if (last >= 0) listState.animateScrollToItem(last)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (items.isEmpty()) item {
                        Text(
                            "Спросите о том, что видите на фасаде: что здесь, как работает, как связаться.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
                        )
                    }
                    items(items) { Bubble(it, onAction) }
                    if (sending) item { Thinking() }
                }
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem, onAction: (FacadeAction) -> Unit) {
    val user = item.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                color = when {
                    item.isError -> MaterialTheme.colorScheme.errorContainer
                    user -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(item.content, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            item.action?.let { a ->
                FilledTonalButton(onClick = { onAction(a) }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(a.icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(a.buttonText)
                }
            }
        }
    }
}

@Composable
private fun Thinking() {
    val t = rememberInfiniteTransition(label = "thinking")
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i ->
                val h by t.animateFloat(
                    0.3f, 1f,
                    infiniteRepeatable(tween(500, delayMillis = i * 120, easing = LinearEasing), RepeatMode.Reverse),
                    label = "bar$i"
                )
                Box(Modifier.width(4.dp).height(18.dp * h).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun InputRow(sending: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        val t = text.trim()
        if (t.isNotEmpty() && !sending) { onSend(t.take(1000)); text = "" }
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ваш вопрос…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            maxLines = 4
        )
        IconButton(onClick = send, enabled = text.isNotBlank() && !sending) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
        }
    }
}
