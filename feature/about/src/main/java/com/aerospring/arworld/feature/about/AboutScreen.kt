package com.aerospring.arworld.feature.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.data.repository.UpdateCheckResult
import com.aerospring.arworld.core.data.repository.UpdateRepository
import kotlinx.coroutines.launch

/** Политика обработки ПД — постоянный адрес действующей редакции на сайте (текст меняется на сайте, без выпуска приложения). */
private const val PRIVACY_POLICY_URL = "https://ar-to-business.ru/documents/privacy-policy/"

/** Страница приложения в RuStore (открывается в приложении RuStore, если оно установлено, иначе в браузере). */
private const val RUSTORE_APP_URL = "https://www.rustore.ru/catalog/app/com.aerospring.arworld"

/** Пакет приложения RuStore — так система помечает приложения, установленные из него. */
private const val RUSTORE_INSTALLER = "ru.vk.store"

private sealed class ScreenState {
    object Idle : ScreenState()
    object Checking : ScreenState()
    object UpToDate : ScreenState()
    data class Available(val apkUrl: String, val versionName: String, val notes: String?) : ScreenState()
    data class CheckError(val message: String) : ScreenState()
}

/** Кто установил приложение: RuStore → обновление через RuStore; иначе (сайт, файл) → скачивание APK браузером. */
private fun installedFromRuStore(context: Context): Boolean = try {
    val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
    installer == RUSTORE_INSTALLER
} catch (e: Exception) {
    false
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // Нет приложения для открытия ссылки — ничего не делаем, экран остаётся как был.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    currentVersionName: String,
    currentVersionCode: Int,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ScreenState>(ScreenState.Idle) }
    val fromRuStore = remember { installedFromRuStore(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("О программе") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("AR.Мир")
                Text("Версия $currentVersionName")

                when (val s = state) {
                    is ScreenState.Idle -> {
                        Button(onClick = {
                            state = ScreenState.Checking
                            scope.launch {
                                state = when (val result = UpdateRepository.checkForUpdate(currentVersionCode)) {
                                    is UpdateCheckResult.Available -> ScreenState.Available(
                                        apkUrl = result.info.apkUrl,
                                        versionName = result.info.versionName,
                                        notes = result.info.releaseNotes
                                    )
                                    is UpdateCheckResult.UpToDate -> ScreenState.UpToDate
                                    is UpdateCheckResult.Error -> ScreenState.CheckError(result.message)
                                }
                            }
                        }) {
                            Text("Проверить обновления")
                        }
                    }
                    is ScreenState.Checking -> CircularProgressIndicator()
                    is ScreenState.UpToDate -> Text("У вас последняя версия")
                    is ScreenState.CheckError -> Text("Ошибка проверки: ${s.message}")
                    is ScreenState.Available -> {
                        Text("Доступна версия ${s.versionName}")
                        s.notes?.let { Text(it, textAlign = TextAlign.Center) }
                        if (fromRuStore) {
                            Button(onClick = { openUrl(context, RUSTORE_APP_URL) }) {
                                Text("Обновить в RuStore")
                            }
                        } else {
                            Button(onClick = { openUrl(context, s.apkUrl) }) {
                                Text("Скачать обновление")
                            }
                            Text(
                                "Файл скачается в браузере. Откройте его из уведомления о загрузке, чтобы установить.",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                TextButton(onClick = { openUrl(context, PRIVACY_POLICY_URL) }) {
                    Text("Политика обработки персональных данных")
                }
            }
        }
    }
}
