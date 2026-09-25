package com.aerospring.arworld.feature.arbc

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aerospring.arworld.feature.arbc.data.ArBcAiChatTurn

/**
 * Финальный UI ИИ-диалога (шаг 2) — полноэкранное окно с историей сообщений вместо
 * прежнего одноразового AlertDialog. История реально уходит на сервер при каждом
 * сообщении (см. ArBcAiRepository.chat) — иначе диалог с историей на экране был бы
 * "фейковым": пользователь видит переписку, а LLM её не помнит.
 *
 * Иконка микрофона стоит на своём финальном месте, но пока ЗАГЛУШКА — реальная запись
 * голоса и распознавание речи (STT) собираются отдельным шагом (голосовой контур).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArBcAiChatDialog(
    messages: List<ArBcAiChatTurn>,
    isSending: Boolean,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("ИИ-помощник") },
                        actions = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
                            }
                        }
                    )
                },
                bottomBar = {
                    ChatInputRow(isSending = isSending, onSendMessage = onSendMessage, onMicClick = onMicClick)
                }
            ) { innerPadding ->
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size, isSending) {
                    val lastIndex = messages.size - 1 + if (isSending) 1 else 0
                    if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { turn -> ChatBubble(turn = turn) }
                    if (isSending) {
                        item { ThinkingBubble() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ArBcAiChatTurn) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.wrapContentWidth()
        ) {
            Text(text = turn.content, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** "Размышление" — анимированные полоски-волна вместо статичного спиннера, пока ждём
 *  ответ LLM (запрос к серверу может занимать несколько секунд). */
@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index -> WaveBar(index = index) }
            }
        }
    }
}

@Composable
private fun WaveBar(index: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking-wave")
    val heightFraction by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing, delayMillis = index * 120),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar-$index"
    )
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(18.dp * heightFraction)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputRow(
    isSending: Boolean,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onMicClick) {
            Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ваш вопрос…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            singleLine = true
        )
        IconButton(
            onClick = {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty() && !isSending) {
                    onSendMessage(trimmed)
                    text = ""
                }
            },
            enabled = text.isNotBlank() && !isSending
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
        }
    }
}