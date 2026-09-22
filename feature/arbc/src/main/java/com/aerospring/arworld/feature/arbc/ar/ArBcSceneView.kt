package com.aerospring.arworld.feature.arbc.ar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aerospring.arworld.core.ui.theme.ArWorldTheme
import com.aerospring.arworld.feature.arbc.data.ArBcScene
import com.aerospring.arworld.feature.arbc.data.Interaction
import com.aerospring.arworld.feature.arbc.data.SceneModel
import com.aerospring.arworld.feature.arbc.model.ArbcDownloadState
import com.aerospring.arworld.feature.arbc.model.ArbcGlbDownloader
import com.aerospring.arworld.feature.arbc.ui.ArbcLoadingBadge
import com.google.ar.core.Config
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * AR-сцена одной AR.Визитки.
 *
 * В отличие от "Где что находится", здесь НЕТ hit-test по плоскости и НЕТ AnchorNode:
 * position/rotation/scale каждой модели из scene.json передаются в ModelNode напрямую,
 * как локальные координаты ARSceneView. ARCore и так привязывает локальный (0,0,0)
 * к точке старта сессии — а это и есть наша "точка сканирования", без всякого GPS.
 * Модели поэтому появляются сразу при старте сессии, не дожидаясь обнаружения плоскости.
 *
 * defaultAnimation.index проигрывается через com.google.android.filament.gltfio.Animator
 * (подтверждён компиляцией — applyAnimation(index, time) по индексу, с ручным
 * продвижением времени каждый кадр). Индекс, не имя — у клиентских .glb могут быть
 * любые/бессмысленные имена clip'ов, разбираться в них не нужно.
 */
@Composable
fun ArBcSceneView(
    scene: ArBcScene,
    onInteraction: (Interaction) -> Unit,
    onBackgroundClick: () -> Unit,
    onSessionCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val viewNodeWindowManager = rememberViewNodeManager()

    // Та же защита от гонки клика, что и в ArCameraView: регистрация узла в момент
    // его создания (apply{}), очистка через DisposableEffect на жизненный цикл модели.
    val nodeToModel = remember { mutableMapOf<Node, SceneModel>() }

    val modelLoadDispatcher = remember { Dispatchers.IO.limitedParallelism(3) }

    ARSceneView(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        viewNodeWindowManager = viewNodeWindowManager,
        // Плоскость не используется — модели не "приземляются" на пол, они висят
        // в воздухе в заданных координатах от точки старта сессии.
        planeFindingMode = Config.PlaneFindingMode.DISABLED,
        depthMode = Config.DepthMode.DISABLED,
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { _, node ->
                var current: Node? = node
                var model: SceneModel? = null
                while (current != null) {
                    model = nodeToModel[current]
                    if (model != null) break
                    current = current.parent
                }
                if (model != null) {
                    // Пока просто проксируем первую интеракцию модели наверх — реальная
                    // обработка (переход на сайт, активация ИИ) появится на шаге 4.
                    model.interactions.firstOrNull()?.let(onInteraction)
                } else {
                    onBackgroundClick()
                }
            }
        ),
        onSessionCreated = { onSessionCreated() }
    ) {
        scene.models.forEach { sceneModel ->
            key(sceneModel.modelId) {
                val downloadState by produceState<ArbcDownloadState>(
                    initialValue = ArbcDownloadState.Progress(0),
                    key1 = sceneModel.modelUrl
                ) {
                    ArbcGlbDownloader.download(context, sceneModel.modelUrl).collect { value = it }
                }

                var modelInstance by remember(sceneModel.modelId) { mutableStateOf<ModelInstance?>(null) }
                var modelLoadError by remember(sceneModel.modelId) { mutableStateOf<String?>(null) }

                LaunchedEffect(sceneModel.modelId) {
                    try {
                        withTimeout(45_000) {
                            withContext(modelLoadDispatcher) {
                                modelLoader.loadModelInstanceAsync(sceneModel.modelUrl) { instance ->
                                    if (instance != null) {
                                        modelInstance = instance
                                    } else {
                                        modelLoadError = "loadModelInstanceAsync вернул null"
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        modelLoadError = "Таймаут загрузки (45с) — файл слишком большой или сервер отвечает медленно"
                    } catch (e: Exception) {
                        modelLoadError = e.message ?: "Ошибка загрузки 3D-модели"
                    }
                }

                val nodeHolder = remember(sceneModel.modelId) { arrayOfNulls<Node>(1) }
                DisposableEffect(sceneModel.modelId) {
                    onDispose { nodeHolder[0]?.let { nodeToModel.remove(it) } }
                }

                val currentModelInstance = modelInstance
                if (currentModelInstance != null) {
                    var placedNode by remember(sceneModel.modelId) { mutableStateOf<ModelNode?>(null) }

                    ModelNode(
                        modelInstance = currentModelInstance,
                        scaleToUnits = sceneModel.scale,
                        position = Position(
                            sceneModel.position.x,
                            sceneModel.position.y,
                            sceneModel.position.z
                        ),
                        rotation = Rotation(
                            x = sceneModel.rotation.x,
                            y = sceneModel.rotation.y,
                            z = sceneModel.rotation.z
                        ),
                        apply = {
                            nodeToModel[this] = sceneModel
                            nodeHolder[0] = this
                            // Тот же issue с "проталкиванием" состояния в Filament для
                            // только что созданного узла, что и в ArCameraView.
                            this.isVisible = false
                            this.isVisible = true
                            // Свойство ModelNode.animator подтверждено только по общей
                            // документации SceneView, не по коду именно вашего проекта —
                            // если сборка не найдёт animator здесь, посмотри автодополнение
                            // по "this." прямо в этом блоке и пришли, что покажет IDE.
                            placedNode = this
                        }
                    )

                    // Проигрывание defaultAnimation.index из scene.json по подтверждённому
                    // com.google.android.filament.gltfio.Animator (компиляция уже это
                    // подтвердила): работает по ИНДЕКСУ и требует ручного продвижения
                    // времени на каждый кадр — своего "зацикливания" в библиотеке нет.
                    val defaultAnimation = sceneModel.defaultAnimation
                    LaunchedEffect(placedNode, defaultAnimation) {
                        val node = placedNode ?: return@LaunchedEffect
                        val anim = defaultAnimation ?: return@LaunchedEffect
                        val animator = node.animator

                        if (anim.index < 0 || anim.index >= animator.animationCount) {
                            // Индекс из scene.json не существует в этой конкретной .glb —
                            // не падаем, просто не анимируем (модель в бинд-позе).
                            return@LaunchedEffect
                        }

                        val durationSeconds = animator.getAnimationDuration(anim.index)
                        val startNanos = withFrameNanos { it }

                        while (isActive) {
                            val elapsedSeconds = withFrameNanos { frameNanos ->
                                (frameNanos - startNanos) / 1_000_000_000f
                            }
                            val time = when {
                                durationSeconds <= 0f -> 0f
                                anim.loop -> elapsedSeconds % durationSeconds
                                else -> elapsedSeconds.coerceAtMost(durationSeconds)
                            }
                            animator.applyAnimation(anim.index, time)
                            animator.updateBoneMatrices()
                            if (!anim.loop && elapsedSeconds >= durationSeconds) break
                        }
                    }
                } else {
                    val statusText = modelLoadError?.let { "Ошибка: $it" }
                        ?: when (val state = downloadState) {
                            is ArbcDownloadState.Progress -> if (state.isMegabytes) {
                                "Загрузка ${state.percent} МБ"
                            } else {
                                "Загрузка ${state.percent}%"
                            }
                            is ArbcDownloadState.Done -> "Обработка..."
                            is ArbcDownloadState.Error -> "Ошибка: ${state.message}"
                        }

                    ViewNode(
                        windowManager = viewNodeWindowManager,
                        unlit = true,
                        position = Position(
                            sceneModel.position.x,
                            sceneModel.position.y,
                            sceneModel.position.z
                        )
                    ) {
                        ArWorldTheme(darkTheme = true) {
                            ArbcLoadingBadge(statusText = statusText)
                        }
                    }
                }
            }
        }
    }
}