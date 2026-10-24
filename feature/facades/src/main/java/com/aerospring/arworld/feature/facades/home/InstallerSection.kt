package com.aerospring.arworld.feature.facades.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.facades.data.DownloadProgress
import com.aerospring.arworld.feature.facades.data.FacadeStore
import com.aerospring.arworld.feature.facades.data.FacadesInstallApi
import com.aerospring.arworld.feature.facades.data.InstallFacade
import com.aerospring.arworld.feature.facades.data.InstallerException
import com.aerospring.arworld.feature.facades.data.InstallerPrefs
import com.aerospring.arworld.feature.facades.data.InstallerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Папка копий для предпросмотра монтажника: отдельно от копий прохожего, вместе с черновиками. */
internal const val PREVIEW_STORE = "facades-preview"

/** Вход монтажника — тем же логином и паролем, что в студии или кабинете. */
@Composable
internal fun InstallerLoginDialog(onLoggedIn: (InstallerSession) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Вход монтажника") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Логин и пароль — те же, что в студии или кабинете заказчика. Нужно право «Может снимать эталоны».",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = login, onValueChange = { login = it }, label = { Text("Логин") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && login.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val s = FacadesInstallApi.login(login, password)
                            InstallerPrefs.save(context.applicationContext, s)
                            onLoggedIn(s)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: InstallerException) {
                            error = e.message
                        } catch (e: Exception) {
                            error = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text("Войти") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } }
    )
}

/**
 * Блок «Мои фасады (монтаж)» в списке: все фасады монтажника, включая черновики и выключенные.
 * «Предпросмотр» скачивает фасад вместе с черновыми сценами в отдельную папку и открывает AR с панелью монтажа.
 */
@Composable
internal fun InstallerBlock(session: InstallerSession, onLogout: () -> Unit, onOpenPreview: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewStore = remember { FacadeStore(context.applicationContext, PREVIEW_STORE) }
    var list by remember { mutableStateOf<List<InstallFacade>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<String, DownloadProgress>?>(null) }
    var itemError by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(session.token) {
        try {
            list = FacadesInstallApi.facades(session)
            listError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstallerException) {
            listError = e.message
            if (e.code == 401 || e.code == 403) { InstallerPrefs.clear(context.applicationContext); onLogout() }
        } catch (e: Exception) {
            listError = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun preview(id: String) {
        if (progress != null) return
        itemError = null
        progress = id to DownloadProgress(0, 0, 0, 0)
        scope.launch {
            try {
                previewStore.download(id, previewToken = session.token) { p -> scope.launch { if (progress?.first == id) progress = id to p } }
                progress = null
                onOpenPreview(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                itemError = id to if (e.message?.startsWith("HTTP 4") == true) "Нет доступа к фасаду (${e.message})" else "Нет связи с сервером: скачивание прервано"
            }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Монтаж", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(session.displayName + (session.clientName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { InstallerPrefs.clear(context.applicationContext); onLogout() }) { Text("Выйти") }
            }
            listError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val l = list
            when {
                l == null && listError == null -> Text("Загружаем ваши фасады…", style = MaterialTheme.typography.bodySmall)
                l != null && l.isEmpty() -> Text("Фасадов для монтажа нет.", style = MaterialTheme.typography.bodySmall)
            }
            l?.forEach { f ->
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(f.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            f.clientName.takeIf { it.isNotBlank() },
                            "эталонов: ${f.references}",
                            if (f.visibleToPassersBy) "виден прохожим" else f.hiddenReason.ifBlank { "не виден прохожим" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val p = progress?.takeIf { it.first == f.id }?.second
                    if (p != null) {
                        LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(
                            if (p.fileCount == 0) "Загрузка: готовимся…" else "Загрузка ${p.percent}% · файл ${p.fileIndex} из ${p.fileCount}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Button(onClick = { preview(f.id) }, enabled = progress == null, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Предпросмотр и монтаж")
                        }
                    }
                    itemError?.takeIf { it.first == f.id }?.let {
                        Text(it.second, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
