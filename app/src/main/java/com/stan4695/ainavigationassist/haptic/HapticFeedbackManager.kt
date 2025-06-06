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
    private val VIBRATION_COOLDOWN = 500L // cooldown de 0.5 secunde intre fiecare vibratie

    companion object {
        private const val TAG = "HapticFeedbackManager"
        private const val DISTANCE_THRESHOLD = 150 // distanta de la care incep sa se genereze vibratii
        private const val MIN_DISTANCE = 30 // valoarea de la care incepe sa se genereze vibratii de intensitate maxima
        private const val MAX_INTENSITY = 255 // valoarea maxima suportata de functia createOneShot() din VibrationEffect
    }

    // Oferirea feedback-ului haptic pe baza distantelor fata de obstacole
    fun vibrateForDistance(distance: Int) {
        if (!SettingsManager.isHapticEnabled(context)) {
            return
        }

        // Se genereaza o vibratie doar daca obstacolul se afla la o distanta mai mica decat DISTANCE_THRESHOLD
        if (distance > DISTANCE_THRESHOLD) {
            Log.d(TAG, "Obstacolul este prea departe ($distance cm).")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrationTime < VIBRATION_COOLDOWN) {
            Log.d(TAG, "Timpul de cooldown nu a expirat inca.")
            return
        }

        var normalisedDistance: Int
        if (distance < MIN_DISTANCE) {
            normalisedDistance = 0
        } else {
            normalisedDistance = distance
        }

        // Calculeaza intensitatea vibratiilor bazat pe distanta fata de acestea
        val vibrationIntensityPrecent = 1.0f - (normalisedDistance.toFloat() / DISTANCE_THRESHOLD.toFloat())
        val vibrationIntensity = (vibrationIntensityPrecent * MAX_INTENSITY).toInt()

        // Calculeaza durata vibratiilor in functie de nivelul intensitatii
        val vibrationDuration = (50 + (vibrationIntensityPrecent * 150)).toLong() // 50-200ms

        Log.d(TAG, "Vibreaza cu intesitatea $vibrationIntensity pentru ${vibrationDuration}ms. Distanta fata de obstacol: ${distance}cm")
        vibrator.vibrate(VibrationEffect.createOneShot(vibrationDuration, vibrationIntensity))
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