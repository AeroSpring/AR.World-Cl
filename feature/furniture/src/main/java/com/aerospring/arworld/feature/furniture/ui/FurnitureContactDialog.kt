package com.aerospring.arworld.feature.furniture.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aerospring.arworld.feature.furniture.data.ShowcaseCompany
import com.aerospring.arworld.feature.furniture.data.ShowcaseModel

/** Порядок и подписи контактов в окне «Хочу такую». */
private val CONTACT_LABELS = listOf(
    "phone" to "Позвонить",
    "whatsapp" to "WhatsApp",
    "telegram" to "Telegram",
    "max" to "MAX",
    "vk" to "ВКонтакте",
    "email" to "Написать на почту",
    "site" to "Сайт",
    "address" to "Адрес салона",
)

/**
 * «Хочу такую»: какая модель (код, название, дизайнер) и как связаться с компанией.
 * Приложение ничего о госте не собирает и никуда не отправляет — гость звонит/пишет сам.
 */
@Composable
fun FurnitureContactDialog(
    model: ShowcaseModel,
    company: ShowcaseCompany?,
    typeName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val message = buildString {
        append("Здравствуйте! Хочу заказать мебель как модель ${model.code} «${model.showcaseName}»")
        if (model.designerName.isNotBlank()) append(", дизайнер ${model.designerName}")
        append(". Увидел(а) её в приложении AR.Мир.")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Хочу такую") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(model.showcaseName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Код модели: ${model.code}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val details = listOf(typeName, model.designerName.takeIf { it.isNotBlank() }?.let { "дизайнер $it" })
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Свяжитесь с компанией и назовите код модели — по нему вас сразу поймут.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))

                if (company == null || company.contacts.isEmpty()) {
                    Text("Контакты компании сейчас недоступны", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(company.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    for ((key, label) in CONTACT_LABELS) {
                        val value = company.contacts[key]?.trim().orEmpty()
                        if (value.isEmpty()) continue
                        ContactRow(label = label, value = value) {
                            openContact(context, key, value, model, message)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = {
            TextButton(onClick = {
                copyToClipboard(context, message)
                Toast.makeText(context, "Текст с кодом модели скопирован", Toast.LENGTH_SHORT).show()
            }) { Text("Скопировать текст") }
        },
    )
}

@Composable
private fun ContactRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun withScheme(value: String): String =
    if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else "https://$value"

private fun isLink(value: String): Boolean =
    value.startsWith("http://", true) || value.startsWith("https://", true) || value.contains(".ru/") ||
            value.contains(".com/") || value.contains(".me/")

private fun digits(value: String): String = value.filter { it.isDigit() }

private fun openContact(context: Context, key: String, value: String, model: ShowcaseModel, message: String) {
    val uri: Uri? = when (key) {
        "phone" -> Uri.parse("tel:" + value.filter { it.isDigit() || it == '+' })
        "email" -> Uri.parse(
            "mailto:" + value +
                    "?subject=" + Uri.encode("Модель ${model.code} из AR.Мир") +
                    "&body=" + Uri.encode(message)
        )
        "site" -> Uri.parse(withScheme(value))
        "address" -> Uri.parse("geo:0,0?q=" + Uri.encode(value))
        "telegram" -> when {
            isLink(value) -> Uri.parse(withScheme(value))
            else -> Uri.parse("https://t.me/" + value.removePrefix("@"))
        }
        "whatsapp" -> when {
            isLink(value) -> Uri.parse(withScheme(value))
            digits(value).length >= 10 -> Uri.parse("https://wa.me/" + digits(value) + "?text=" + Uri.encode(message))
            else -> null
        }
        "vk" -> when {
            isLink(value) -> Uri.parse(withScheme(value))
            else -> Uri.parse("https://vk.com/" + value.removePrefix("@"))
        }
        "max" -> if (isLink(value)) Uri.parse(withScheme(value)) else null
        else -> null
    }

    val action = when (key) {
        "phone" -> Intent.ACTION_DIAL
        "email" -> Intent.ACTION_SENDTO
        else -> Intent.ACTION_VIEW
    }
    if (uri != null) {
        try {
            context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // нет подходящего приложения — ниже скопируем контакт
        } catch (_: SecurityException) {
        }
    }
    copyToClipboard(context, value)
    Toast.makeText(context, "Скопировано: $value", Toast.LENGTH_SHORT).show()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("AR.Мебель", text))
}