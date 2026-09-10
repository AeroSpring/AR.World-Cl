package com.aerospring.arworld.feature.whereanythingis.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Азимут устройства (0..360°, по часовой стрелке от магнитного севера).
 * Нужен для калибровки: ARCore ориентирует локальные оси сцены по направлению камеры
 * в момент старта сессии, а не по сторонам света — без этой привязки east/north-смещения
 * POI оказываются повёрнуты в случайную сторону.
 */
@Composable
fun rememberDeviceHeadingDegrees(): State<Float?> {
    val context = LocalContext.current
    val headingState = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthDeg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
                headingState.value = azimuthDeg
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose { sensorManager.unregisterListener(listener) }
    }

    return headingState
}