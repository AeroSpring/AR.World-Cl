package com.aerospring.arworld.feature.arbc

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.arbc.ar.ArBcSceneView
import com.aerospring.arworld.feature.arbc.data.ArBcAiChatResult
import com.aerospring.arworld.feature.arbc.data.ArBcAiChatTurn
import com.aerospring.arworld.feature.arbc.data.ArBcAiRepository
import com.aerospring.arworld.feature.arbc.data.ArBcRepository
import com.aerospring.arworld.feature.arbc.data.ArBcScene
import com.aerospring.arworld.feature.arbc.data.ArBcSceneFetchResult
import com.aerospring.arworld.feature.arbc.data.Interaction
import com.aerospring.arworld.feature.arbc.permissions.REQUIRED_ARBC_PERMISSIONS
import com.aerospring.arworld.feature.arbc.permissions.rememberArbcPermissionsGranted
import kotlinx.coroutines.launch

/**
 * Экран одной AR.Визитки. clientId приходит от ArBcQrScanScreen (см. ArBcEntryPoint) —
 * после того как пользователь отсканировал QR-код конкретной визитки внутри раздела.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArBcScreen(
    clientId: String,
    onBackClick: () -> Unit
) {
    var permissionsGranted by remember { mutableStateOf(false) }
    val initiallyGranted = rememberArbcPermissionsGranted()

    LaunchedEffect(initiallyGranted) {
        permissionsGranted = initiallyGranted
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AR.Визитка") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (permissionsGranted) {
                var fetchResult by remember { mutableStateOf<ArBcSceneFetchResult?>(null) }
                LaunchedEffect(clientId) {
                    fetchResult = ArBcRepository.fetchScene(clientId)
                }

                when (val result = fetchResult) {
                    null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    is ArBcSceneFetchResult.Error -> Text(
                        text = "Не удалось загрузить AR.Визитку: ${result.message}",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )

                    is ArBcSceneFetchResult.Success -> {
                        val scene: ArBcScene = result.scene
                        val context = LocalContext.current
                        val coroutineScope = rememberCoroutineScope()

                        // Вся переписка с ИИ-помощником за время нахождения на этом экране.
                        // Живёт до выхода со сцены — специально НЕ сбрасывается между
                        // повторными тапами по модели, чтобы разговор оставался цельным.
                        var chatHistory by remember { mutableStateOf<List<ArBcAiChatTurn>>(emptyList()) }
                        var chatDialogOpen by remember { mutableStateOf(false) }
                        var isSending by remember { mutableStateOf(false) }

                        fun sendMessage(text: String) {
                            val historyBeforeThisMessage = chatHistory
                            chatHistory = chatHistory + ArBcAiChatTurn(role = "user", content = text)
                            isSending = true
                            coroutineScope.launch {
                                when (
                                    val chatResult = ArBcAiRepository.chat(
                                        clientId = clientId,
                                        message = text,
                                        history = historyBeforeThisMessage
                                    )
                                ) {
                                    is ArBcAiChatResult.Success -> {
                                        chatHistory = chatHistory + ArBcAiChatTurn(
                                            role = "assistant",
                                            content = chatResult.reply
                                        )
                                    }
                                    is ArBcAiChatResult.Error -> {
                                        chatHistory = chatHistory + ArBcAiChatTurn(
                                            role = "assistant",
                                            content = "Ошибка: ${chatResult.message}"
                                        )
                                    }
                                }
                                isSending = false
                            }
                        }

                        ArBcSceneView(
                            scene = scene,
                            onInteraction = { interaction ->
                                when (interaction) {
                                    is Interaction.OpenUrl -> {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(interaction.url))
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                context,
                                                "Не удалось открыть ссылку",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    is Interaction.ActivateAI -> {
                                        // Локальное приветствие первым тапом — не уходит на
                                        // сервер отдельным запросом, просто задаёт тон диалога
                                        // (не тратим LLM-вызов на пустое приветствие).
                                        if (chatHistory.isEmpty()) {
                                            chatHistory = listOf(
                                                ArBcAiChatTurn(
                                                    role = "assistant",
                                                    content = "Здравствуйте! Чем могу помочь?"
                                                )
                                            )
                                        }
                                        chatDialogOpen = true
                                    }
                                    Interaction.Unknown -> {
                                        // Неизвестный тип интеракции (например, появившийся
                                        // сначала в веб-версии) — молча игнорируем, не падаем.
                                        android.util.Log.d("ArBcScreen", "unknown interaction")
                                    }
                                }
                            },
                            onBackgroundClick = { },
                            onSessionCreated = { },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (chatDialogOpen) {
                            ArBcAiChatDialog(
                                messages = chatHistory,
                                isSending = isSending,
                                onSendMessage = ::sendMessage,
                                onMicClick = {
                                    // Заглушка — реальная запись голоса появится отдельным шагом.
                                    Toast.makeText(
                                        context,
                                        "Голосовой ввод появится на следующем шаге",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onDismiss = { chatDialogOpen = false }
                            )
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.align(Alignment.Center)) {
                    Text("Для работы AR.Визитки нужен доступ к камере.")
                    Button(onClick = { permissionLauncher.launch(REQUIRED_ARBC_PERMISSIONS) }) {
                        Text("Предоставить доступ")
                    }
                }
            }
        }
    }
}