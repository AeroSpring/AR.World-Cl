package com.aerospring.arworld.feature.facades.ar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.core.data.model.AdMarking
import com.aerospring.arworld.feature.facades.data.FacadeCounter
import com.aerospring.arworld.feature.facades.data.facadesJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Действие модели сцены (касание). Поля — как их сохраняет студия, см. FacadeSceneModel.interactions. */
internal sealed class FacadeAction(open val label: String?) {
    data class OpenUrl(val url: String, override val label: String?) : FacadeAction(label)
    data class Call(val phone: String, override val label: String?) : FacadeAction(label)
    data class Email(val email: String, val subject: String?, override val label: String?) : FacadeAction(label)
    data class Ai(override val label: String?) : FacadeAction(label)
    data class Detail(
        val assetId: String,
        val assetUrl: String,
        val title: String?,
        /** Название модели-карточки в студии — заголовок, если свой не задан. */
        val assetTitle: String?,
        val text: String?,
        /** erid карточки; null — у карточки нет своего креатива, действует erid сцены. */
        val erid: String?,
        /** Строка маркировки карточки; null — берётся строка сцены. */
        val ad: AdMarking?,
        override val label: String?,
        /** Какой стороной модель повёрнута при открытии, градусы X/Y/Z; null — как в файле. */
        val rotation: List<Float>? = null,
        /** Анимация: номер и как играть (loop | once | none); null — первая по кругу. */
        val animIndex: Int? = null,
        val animMode: String? = null,
        /** Светлый фон (по умолчанию тёмный). */
        val lightBackground: Boolean = false,
        /** Свои действия карточки; null — в карточке действия модели сцены. */
        val actions: List<FacadeAction>? = null
    ) : FacadeAction(label)

    val buttonText: String
        get() = label?.takeIf { it.isNotBlank() } ?: when (this) {
            is OpenUrl -> "Открыть сайт"
            is Call -> "Позвонить"
            is Email -> "Написать письмо"
            is Ai -> "Спросить помощника"
            is Detail -> (title?.takeIf { it.isNotBlank() } ?: assetTitle)?.let { "Подробно: $it" } ?: "Посмотреть подробно"
        }

    val icon: ImageVector
        get() = when (this) {
            is OpenUrl -> Icons.Filled.Language
            is Call -> Icons.Filled.Call
            is Email -> Icons.Filled.Email
            is Ai -> Icons.AutoMirrored.Filled.Chat
            is Detail -> Icons.Filled.ViewInAr
        }
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Разбор действий модели; непонятные и неполные пропускаются (сервер мог добавить новый тип). */
internal fun parseActions(list: List<JsonObject>, aiEnabled: Boolean): List<FacadeAction> = list.mapNotNull { o ->
    val label = o.str("label")
    when (o.str("type")) {
        "openUrl" -> o.str("url")?.let { FacadeAction.OpenUrl(it, label) }
        "callPhone" -> o.str("phone")?.let { FacadeAction.Call(it, label) }
        "composeEmail" -> o.str("email")?.let { FacadeAction.Email(it, o.str("subject"), label) }
        // Помощник без промпта на сервере выключен — кнопку не показываем.
        "aiAssistant" -> if (aiEnabled) FacadeAction.Ai(label) else null
        "openDetail" -> {
            val id = o.str("assetId")
            val url = o.str("assetUrl")
            if (id == null || url == null) null
            else {
                val anim = o["animation"] as? JsonObject
                FacadeAction.Detail(
                    id, url, o.str("title"), o.str("assetTitle"), o.str("text"), o.str("erid"),
                    o["ad"]?.let { runCatching { facadesJson.decodeFromJsonElement(AdMarking.serializer(), it) }.getOrNull() },
                    label,
                    rotation = (o["rotation"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.floatOrNull }?.takeIf { it.size == 3 },
                    animIndex = anim?.get("index")?.jsonPrimitive?.intOrNull,
                    animMode = anim?.get("mode")?.jsonPrimitive?.contentOrNull,
                    lightBackground = o.str("background") == "light",
                    // Свои действия карточки — без вложенных карточек.
                    actions = (o["actions"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                        ?.let { list -> parseActions(list, aiEnabled).filter { it !is FacadeAction.Detail } }
                        ?.takeIf { it.isNotEmpty() }
                )
            }
        }
        else -> null
    }
}

/** Для счётчика: каким полем учитывается действие (сайт, звонок, письмо). */
internal fun FacadeAction.counter(): FacadeCounter? = when (this) {
    is FacadeAction.OpenUrl -> FacadeCounter.URL_OPEN
    is FacadeAction.Call -> FacadeCounter.CALL
    is FacadeAction.Email -> FacadeCounter.EMAIL
    else -> null
}

/**
 * Сайт, звонок, письмо — системными приложениями. Звонок только набирает номер (ACTION_DIAL):
 * позвонит человек сам, разрешение на звонки не нужно. true — получилось открыть.
 */
internal fun launchExternal(context: Context, action: FacadeAction): Boolean {
    val intent = when (action) {
        is FacadeAction.OpenUrl -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
        is FacadeAction.Call -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + action.phone.filter { it.isDigit() || it == '+' }))
        is FacadeAction.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + action.email)).apply {
            action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        else -> return false
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "На телефоне нет приложения, чтобы это открыть", Toast.LENGTH_SHORT).show()
        false
    }
}

/** Выбор действия, когда у модели их несколько. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(title: String?, actions: List<FacadeAction>, onPick: (FacadeAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            title?.let { Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp)) }
            actions.forEach { a ->
                FilledTonalButton(onClick = { onPick(a) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(a.icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(a.buttonText, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
