package app.wallengine.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns device motion into [InputState.tiltX]/[InputState.tiltY] (-1..1).
 *
 * Gyroscope: rotation is integrated and slowly decays back to zero, so whatever pose the phone
 * is held in becomes the neutral one after a few seconds. Without a gyroscope, the accelerometer
 * is used instead: tilt is how far gravity has moved from its slow-moving average.
 *
 * Events arrive on [handler]'s thread. [onChange] is called there, only when the tilt moved
 * enough to be worth a frame, so a phone lying still costs no rendering.
 */
class TiltSensor(
    context: Context,
    private val handler: Handler,
    private val input: InputState,
    private val onChange: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var running = false
    private var lastNanos = 0L
    private var x = 0f
    private var y = 0f
    private var baseX = 0f
    private var baseY = 0f

    fun start() {
        if (running || sensor == null) return
        running = manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler) == true
    }

    /** Stops listening and returns to neutral, so the next start does not jump. */
    fun stop() {
        if (!running) return
        running = false
        manager?.unregisterListener(this)
        lastNanos = 0L
        x = 0f
        y = 0f
        publish(force = true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val last = lastNanos
        lastNanos = event.timestamp
        val dt = (event.timestamp - last) * 1e-9f
        val first = last == 0L || dt <= 0f || dt > MAX_GAP_SEC

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (first) return
            // Rotation about the screen's vertical axis slides layers sideways, about its horizontal axis up/down.
            val decay = exp(-dt / RECENTER_SEC)
            x = ((x + event.values[1] * dt / FULL_TILT_RAD) * decay).coerceIn(-1f, 1f)
            y = ((y + event.values[0] * dt / FULL_TILT_RAD) * decay).coerceIn(-1f, 1f)
        } else {
            val gx = event.values[0]
            val gy = event.values[1]
            if (first) {
                baseX = gx
                baseY = gy
                return
            }
            val follow = 1f - exp(-dt / RECENTER_SEC)
            baseX += (gx - baseX) * follow
            baseY += (gy - baseY) * follow
            // Tilting the right edge away moves gravity towards -x; the top edge towards the viewer, towards -y.
            val smooth = 1f - exp(-dt / ACCEL_SMOOTH_SEC)
            x += ((-(gx - baseX) / FULL_TILT_ACCEL).coerceIn(-1f, 1f) - x) * smooth
            y += ((-(gy - baseY) / FULL_TILT_ACCEL).coerceIn(-1f, 1f) - y) * smooth
        }
        publish(force = false)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun publish(force: Boolean) {
        if (!force && abs(x - input.tiltX) < MIN_CHANGE && abs(y - input.tiltY) < MIN_CHANGE) return
        input.tiltX = x
        input.tiltY = y
        onChange()
    }

    private companion object {
        /** Rotation that gives full tilt: about 17 degrees. */
        const val FULL_TILT_RAD = 0.3f
        /** Gravity change (m/s^2) that gives full tilt: about 17 degrees from upright. */
        const val FULL_TILT_ACCEL = 2.9f
        const val RECENTER_SEC = 5f
        const val ACCEL_SMOOTH_SEC = 0.08f
        const val MAX_GAP_SEC = 0.25f
        const val MIN_CHANGE = 0.003f
    }
}
