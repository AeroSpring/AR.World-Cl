package com.aerospring.arworld.feature.guide.ar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Короткие мягкие звуки путеводителя — без файлов, синтезируются на лету.
 *  - [lost]  — «мур-мур» вниз: телефон потерял, где он, — нужно навести камеру на лист-опору;
 *  - [found] — короткое «динь» вверх: место снова найдено.
 * Громкость — системная громкость уведомлений/интерфейса, беззвучный режим телефона уважается.
 */
internal object GuideSound {
    private const val RATE = 22_050
    private var lastLostAt = 0L

    fun lost() {
        val now = System.currentTimeMillis()
        if (now - lastLostAt < 8_000) return   // не чаще раза в 8 секунд
        lastLostAt = now
        play(listOf(523.25f to 0.16f, 392.0f to 0.24f))   // до → соль
    }

    fun found() = play(listOf(659.25f to 0.09f, 987.77f to 0.16f))   // ми → си

    private fun play(notes: List<Pair<Float, Float>>) {
        thread(name = "guide-sound", isDaemon = true) {
            try {
                val samples = synth(notes)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep((samples.size * 1000L / RATE) + 100)
                track.release()
            } catch (e: Exception) {
                Log.w("GuideSound", "Звук не проигран: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Нота = синус с мягкой атакой и затуханием + немного второй гармоники («мурлыкающий» тембр). */
    private fun synth(notes: List<Pair<Float, Float>>): ShortArray {
        val out = ArrayList<Short>()
        for ((freq, dur) in notes) {
            val n = (RATE * dur).toInt()
            for (i in 0 until n) {
                val t = i.toFloat() / RATE
                val attack = (t / 0.015f).coerceAtMost(1f)
                val decay = exp(-3.5f * t / dur)
                val wobble = 1f + 0.004f * sin(2 * PI * 18 * t).toFloat()
                val v = sin(2 * PI * freq * wobble * t) * 0.8 + sin(4 * PI * freq * t) * 0.2
                out += (v * attack * decay * 0.35 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
