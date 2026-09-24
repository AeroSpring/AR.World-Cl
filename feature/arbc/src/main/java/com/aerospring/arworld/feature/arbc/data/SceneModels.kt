package com.aerospring.arworld.feature.arbc.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Root object for a client's AR.Визитки scene, served at
 * https://autoknowledge.tech/clients/{clientId}/scene.json
 *
 * No GPS/geospatial data here on purpose — every model's [Vector3]
 * position is a metre offset from the runtime AR anchor that gets
 * placed when the AR session starts (first plane/camera tracking
 * after the user scans the client's QR code). The scene file is
 * therefore fully independent of *where* or *when* it is scanned.
 */
@Serializable
data class ArBcScene(
    val sceneId: String,
    val schemaVersion: Int,
    val clientName: String,
    val models: List<SceneModel> = emptyList()
)

@Serializable
data class SceneModel(
    val modelId: String,
    /** Path relative to the shared models root, e.g. "/models/romashka_stand.glb" */
    val modelUrl: String,
    val position: Vector3,
    val rotation: Vector3 = Vector3(0f, 0f, 0f),
    val scale: Float = 1f,
    val defaultAnimation: DefaultAnimation? = null,
    val interactions: List<Interaction> = emptyList()
)

@Serializable
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
)

@Serializable
data class DefaultAnimation(
    /** Индекс анимации внутри .glb (Animator.applyAnimation работает по индексу).
     *  Не по имени — клиентские модели могут приходить с любыми, в том числе
     *  бессмысленными или задублированными именами clip'ов; разбираться в чужих
     *  .glb ради имени не нужно, у клиента и так есть индекс 0, 1, 2... */
    val index: Int,
    val loop: Boolean = true
)

/**
 * Open-ended, per-model, per-whole-model interaction (never per mesh/node —
 * touch targets are too small on phone screens for sub-model hit testing).
 *
 * Adding a new interaction type later means adding a new subclass here;
 * unknown/not-yet-supported types on a given platform are safely ignored
 * by [InteractionSerializer] rather than crashing the parse.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class Interaction {

    @Serializable
    @SerialName("openUrl")
    data class OpenUrl(val url: String) : Interaction()

    @Serializable
    @SerialName("activateAI")
    data class ActivateAI(
        /** Optional override; if null, the client's default assistant prompt
         *  (stored alongside scene.json, not inside it) is used. */
        val promptOverrideUrl: String? = null
    ) : Interaction()

    /** Fallback for any interaction type this build doesn't know about yet. */
    @Serializable
    @SerialName("unknown")
    data object Unknown : Interaction()
}

/**
 * JSON instance configured to:
 *  - ignore unknown top-level/object keys (forward-compatible with new
 *    scene.json fields added for the web client or future features)
 *  - not crash on an unrecognized `interactions[].type` — such entries
 *    decode as [Interaction.Unknown] instead of throwing, so Android never
 *    crashes on an interaction type that was so far only shipped on web
 */
@OptIn(ExperimentalSerializationApi::class)
val arBcJson = Json {
    ignoreUnknownKeys = true
    classDiscriminatorMode = kotlinx.serialization.json.ClassDiscriminatorMode.POLYMORPHIC
    coerceInputValues = true
}

/**
 * Convenience parse function.
 * Usage: val scene = arBcJson.decodeSceneOrNull(rawJsonString)
 */
fun Json.decodeSceneOrNull(raw: String): ArBcScene? =
    runCatching { decodeFromString(ArBcScene.serializer(), raw) }.getOrNull()

/** Тело запроса/ответа POST /ai/{clientId}/chat — см. arbc.py на сервере. */
@Serializable
data class ArBcAiChatRequest(val message: String)

@Serializable
data class ArBcAiChatResponse(val reply: String)