package com.aerospring.arworld.feature.facades.ar

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aerospring.arworld.core.data.model.AdMarking
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import io.github.sceneview.model.ModelInstance
import com.google.android.filament.Engine
import io.github.sceneview.loaders.ModelLoader
import java.io.File

/**
 * Карточка «подробно» (действие openDetail): модель-карточка крупно — вращать пальцем, приближать
 * щипком; заголовок и описание из студии; остальные действия той же модели (сайт, звонок, письмо,
 * помощник); своя строка маркировки (erid карточки, иначе — сцены). Всё из копии на телефоне.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FacadeCardDialog(
    card: FacadeAction.Detail,
    file: File,
    /** Движок и загрузчик AR-экрана: второй свой движок при закрытии карточки «гасил» камеру AR. */
    engine: Engine,
    modelLoader: ModelLoader,
    ad: AdMarking?,
    otherActions: List<FacadeAction>,
    onAction: (FacadeAction) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(card.title?.takeIf { it.isNotBlank() } ?: card.assetTitle?.takeIf { it.isNotBlank() } ?: "Подробно") },
                        actions = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") } }
                    )
                }
            ) { inner ->
                Column(Modifier.fillMaxSize().padding(inner)) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(if (card.lightBackground) Color(0xFFF1F1F1) else Color(0xFF1E1F22))) {
                        CardModel(file, engine, modelLoader, card)
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                            .navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        card.text?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp) }
                        otherActions.forEach { a ->
                            FilledTonalButton(onClick = { onAction(a) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(a.icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(a.buttonText, modifier = Modifier.weight(1f))
                            }
                        }
                        ad?.line?.let {
                            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ad?.testBanner?.let {
                            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardModel(file: File, engine: Engine, modelLoader: ModelLoader, card: FacadeAction.Detail) {
    var instance by remember(file) { mutableStateOf<ModelInstance?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        instance = try {
            modelLoader.loadModelInstance(Uri.fromFile(file).toString())
        } catch (e: Exception) {
            Log.e("FacadeAr", "Карточка не открылась: ${file.name}", e)
            null
        }
        failed = instance == null
    }
    var node by remember(file) { mutableStateOf<ModelNodeImpl?>(null) }
    val r = card.rotation
    SceneView(
        modifier = Modifier.fillMaxSize(),
        // Прозрачный вывод поверх фона карточки (тёмного или светлого) — нужен TextureView.
        surfaceType = SurfaceType.TextureSurface,
        isOpaque = false,
        engine = engine,
        modelLoader = modelLoader,
        autoFitContent = true
    ) {
        instance?.let { inst ->
            // Любой размер карточки — в «единичный» кубик по центру, камера кружит вокруг.
            ModelNode(
                modelInstance = inst, autoAnimate = false, scaleToUnits = 1f,
                centerOrigin = io.github.sceneview.math.Position(0f, 0f, 0f),
                rotation = if (r != null) Rotation(r[0], r[1], r[2]) else Rotation(0f, 0f, 0f),
                apply = { node = this }
            )
        }
    }
    // Анимация по номеру с ручным временем, как у моделей сцены: «не запускать», «один раз», «по кругу».
    LaunchedEffect(node, card.animIndex, card.animMode) {
        val n = node ?: return@LaunchedEffect
        if (card.animMode == "none") return@LaunchedEffect
        val index = card.animIndex ?: 0
        val animator = n.animator
        if (index < 0 || index >= animator.animationCount) return@LaunchedEffect
        val loop = card.animMode != "once"
        val duration = animator.getAnimationDuration(index)
        val start = withFrameNanos { it }
        while (isActive) {
            val elapsed = withFrameNanos { (it - start) / 1_000_000_000f }
            val t = when {
                duration <= 0f -> 0f
                loop -> elapsed % duration
                else -> elapsed.coerceAtMost(duration)
            }
            animator.applyAnimation(index, t)
            animator.updateBoneMatrices()
            if (!loop && elapsed >= duration) break
        }
    }
    when {
        failed -> Text(
            "Карточка не открылась", color = if (card.lightBackground) Color.Black else Color.White,
            modifier = Modifier.fillMaxSize().padding(24.dp), fontSize = 14.sp
        )
        instance == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = if (card.lightBackground) Color.DarkGray else Color.White)
        }
    }
}
