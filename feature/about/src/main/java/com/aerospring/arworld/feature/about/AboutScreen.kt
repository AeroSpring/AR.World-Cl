package com.aerospring.arworld.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.data.repository.UpdateCheckResult
import com.aerospring.arworld.core.data.repository.UpdateRepository
import kotlinx.coroutines.launch

private sealed class ScreenState {
    object Idle : ScreenState()
    object Checking : ScreenState()
    object UpToDate : ScreenState()
    data class Available(val apkUrl: String, val versionName: String, val notes: String?) : ScreenState()
    data class CheckError(val message: String) : ScreenState()
    data class Downloading(val percent: Int, val hint: String? = null) : ScreenState()
    data class DownloadError(val message: String) : ScreenState()
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
                        s.notes?.let { Text(it) }
                        Button(onClick = {
                            state = ScreenState.Downloading(0)
                            scope.launch {
                                ApkDownloader.download(context, s.apkUrl).collect { downloadState ->
                                    when (downloadState) {
                                        is ApkDownloadState.Progress -> state = ScreenState.Downloading(downloadState.percent, downloadState.hint)
                                        is ApkDownloadState.Done -> installApk(context, downloadState.uri)
                                        is ApkDownloadState.Error -> state = ScreenState.DownloadError(downloadState.message)
                                    }
                                }
                            }
                        }) {
                            Text("Обновить")
                        }
                    }
                    is ScreenState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                        )
                        Text("Загрузка ${s.percent}%")
                        s.hint?.let { Text(it) }
                    }
                    is ScreenState.DownloadError -> Text("Ошибка загрузки: ${s.message}")
                }
            }
        }
    }
}