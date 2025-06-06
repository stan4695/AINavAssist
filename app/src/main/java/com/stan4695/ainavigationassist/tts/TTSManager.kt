package com.stan4695.ainavigationassist.tts


import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.stan4695.ainavigationassist.BoundingBox
import com.stan4695.ainavigationassist.settings.SettingsManager
import java.util.Locale

// Clasa de administrare a functionalitatii Text-To-Speech utilizata pentru avertizarea verbala a utilizatorilor despre detectiile de obiecte.
class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var lastAnnouncementTime = 0L
    private var lastAnnouncedObject = ""
    private val ANNOUNCEMENT_COOLDOWN = 4000L // timp de cooldown intre anunturi de 4s

    // Contorizarea timpului de feedback pentru lipsa de detectie
    private var lastNoDetectionFeedbackTime = 0L
    private val NO_DETECTION_FEEDBACK_COOLDOWN = 10000L // 10 seconds

    init {
        textToSpeech = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.getDefault())
            isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)

            if (isTtsReady) {
                Log.d(TAG, "TTS a fost initializat cu succes")
            } else {
                Log.e(TAG, "Limba TTS setata, nu este disponibila.")
            }
        } else {
            Log.e(TAG, "Initializarea TTS a esuat cu status-ul: $status")
        }
    }

    fun announceDetection(message: String) {
        if (!isTtsReady || !SettingsManager.isTtsEnabled(context)) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < ANNOUNCEMENT_COOLDOWN) {
            Log.d(TAG, "Timpul de cooldown nu a expirat inca.")
            return
        }

        Log.d(TAG, "Speaking: $message")
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "custom_message_id")
        lastAnnouncementTime = currentTime
    }

    // Functia de avertizare asupra unui treshold prea ridicat
    fun announceNoDetections() {
        if (!isTtsReady || !SettingsManager.isTtsEnabled(context)) {
            return
        }

        val message = "No objects detected at current sensitivity level"

        val sensitivityThreshold = SettingsManager.getDetectionSensitivity(context)
        if (sensitivityThreshold > 0.7f && System.currentTimeMillis() - lastNoDetectionFeedbackTime > NO_DETECTION_FEEDBACK_COOLDOWN && System.currentTimeMillis() - lastAnnouncementTime > NO_DETECTION_FEEDBACK_COOLDOWN) {
            Log.d(TAG, "Announcing: $message")
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "no_detection_id")
            lastNoDetectionFeedbackTime = System.currentTimeMillis()
        }
    }

    // Oprirea functiei TTS
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
        Log.d(TAG, "TTS a fost inchis cu succes")
    }

    companion object {
        private const val TAG = "TTSManager"
    }
}