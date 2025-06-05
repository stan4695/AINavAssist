package com.stan4695.ainavigationassist.settings

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "AINavAssistPrefs"
    private const val KEY_TTS_ENABLED = "tts_enabled"
    private const val KEY_DETECTION_SENSITIVITY = "detection_sensitivity"
    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_GPU_ACCELERATION_ENABLED = "gpu_acceleration_enabled"
    private const val DEFAULT_TTS_ENABLED = true
    private const val DEFAULT_DETECTION_SENSITIVITY = 0.25f
    private const val DEFAULT_HAPTIC_ENABLED = true

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isTtsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_TTS_ENABLED, DEFAULT_TTS_ENABLED)
    }

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    fun isGpuAccelerationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GPU_ACCELERATION_ENABLED, false) // Default to false
    }

    fun setGpuAccelerationEnabled(context: Context, isEnabled: Boolean) {
        getPrefs(context = context).edit().putBoolean(KEY_GPU_ACCELERATION_ENABLED, isEnabled).apply()
    }

    fun getDetectionSensitivity(context: Context): Float {
        return getPrefs(context).getFloat(KEY_DETECTION_SENSITIVITY, DEFAULT_DETECTION_SENSITIVITY)
    }

    fun setDetectionSensitivity(context: Context, sensitivity: Float) {
        getPrefs(context).edit().putFloat(KEY_DETECTION_SENSITIVITY, sensitivity).apply()
    }

    fun isHapticEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTIC_ENABLED, DEFAULT_HAPTIC_ENABLED)
    }

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }
}