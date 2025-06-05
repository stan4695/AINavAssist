package com.stan4695.ainavigationassist.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.stan4695.ainavigationassist.settings.SettingsManager

// Clasa de administarare a feedback-ului haptic
class HapticFeedbackManager(private val context: Context) {
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var lastVibrationTime = 0L
    private val VIBRATION_COOLDOWN = 500L // 500ms cooldown between vibrations

    companion object {
        private const val TAG = "HapticFeedbackManager"
        private const val DISTANCE_THRESHOLD = 150 // cm
        private const val MIN_DISTANCE = 10 // cm - closest expected distance
        private const val MAX_INTENSITY = 255 // maximum vibration intensity
    }

    // Oferirea feedback-ului haptic pe baza distantelor fata de obstacole
    fun vibrateForDistance(distance: Int) {
        if (!SettingsManager.isHapticEnabled(context)) {
            return
        }

        // Vibreaza doar daca obstacolul se afla la o distanta mai mica decat DISTANCE_THRESHOLD
        if (distance > DISTANCE_THRESHOLD) {
            Log.d(TAG, "Obstacolul este prea departe ($distance cm).")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrationTime < VIBRATION_COOLDOWN) {
            Log.d(TAG, "Timpul de cooldown nu a expirat inca.")
            return
        }

        // Calculeaza intensitatea vibratiilor bazat pe distanta fata de acestea
        var normalisedDistance: Int
        if (distance < MIN_DISTANCE) {
            normalisedDistance = MIN_DISTANCE
        } else {
            normalisedDistance = distance
        }
        val vibrationIntensityPrecent = 1.0f - (normalisedDistance.toFloat() / DISTANCE_THRESHOLD.toFloat())
        val vibrationIntensity = (vibrationIntensityPrecent * MAX_INTENSITY).toInt()

        // Calculeaza durata bazat pe distanta fata de obstacol
        val duration = (50 + (vibrationIntensityPrecent * 150)).toLong() // 50-200ms

        Log.d(TAG, "Vibreaza cu intesitatea $vibrationIntensity pentru ${duration}ms. Distanta fata de obstacol: ${distance}cm")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, vibrationIntensity))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }

        lastVibrationTime = currentTime
    }

    // Procesarea distantelor fata de obstacole
    fun processSensorData(sensor1Distance: Int, sensor2Distance: Int) {
        // Foloseste cea mai mica valoare dintre cele doua distante
        if (sensor1Distance > sensor2Distance) {
            vibrateForDistance(sensor2Distance)
        }  else {
            vibrateForDistance(sensor1Distance)
        }
    }
}