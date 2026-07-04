package xyz.abhinava.depthwallpaper.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI

class MotionController(
    context: Context,
    private val maxTiltDegrees: Float,
    private val smoothing: Float,
    private val intensity: Float,
    private val onMotion: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    var tiltX: Float = 0f
        private set
    var tiltY: Float = 0f
        private set

    fun start() {
        val sensor = rotationSensor ?: accelerometer ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val target = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> rotationTarget(event.values)
            Sensor.TYPE_ACCELEROMETER -> accelTarget(event.values)
            else -> null
        } ?: return
        val alpha = smoothing.coerceIn(0.02f, 1f)
        tiltX += (target.first - tiltX) * alpha
        tiltY += (target.second - tiltY) * alpha
        onMotion()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun rotationTarget(values: FloatArray): Pair<Float, Float> {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val roll = orientation[2].toDegrees().coerceIn(-maxTiltDegrees, maxTiltDegrees) / maxTiltDegrees
        val pitch = orientation[1].toDegrees().coerceIn(-maxTiltDegrees, maxTiltDegrees) / maxTiltDegrees
        return roll * intensity to pitch * intensity
    }

    private fun accelTarget(values: FloatArray): Pair<Float, Float> {
        val x = (values[0] / 9.81f).coerceIn(-1f, 1f)
        val y = (values[1] / 9.81f).coerceIn(-1f, 1f)
        return -x * intensity to y * intensity
    }

    private fun Float.toDegrees(): Float = (this * 180f / PI.toFloat())
}
