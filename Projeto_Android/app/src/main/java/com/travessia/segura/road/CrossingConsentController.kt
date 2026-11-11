package com.travessia.segura.road

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.travessia.segura.voice.VoiceSystem

/** Uma resposta por pergunta. Sem resposta inequívoca, nunca consentir. */
class CrossingConsentController(private val activity: AppCompatActivity, private val voice: VoiceSystem,
    private val language: () -> String) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var dialog: AlertDialog? = null
    private var callback: ((Boolean) -> Unit)? = null
    private var generation = 0
    private var retries = 0
    private var tone: android.media.ToneGenerator? = null
    val active: Boolean get() = callback != null
    private fun text(key: String) = RoadText.get(language(), key)
    private val timeout = Runnable { finish(false, "unheard") }

    fun ask(answer: (Boolean) -> Unit) {
        if (active) return
        callback = answer
        retries = 0
        val token = ++generation
        dialog = AlertDialog.Builder(activity).setTitle(text("AWAITING_CONFIRMATION"))
            .setPositiveButton(text("yes")) { _, _ -> finish(true, "consent_yes") }
            .setNegativeButton(text("no")) { _, _ -> finish(false, "declined") }
            .setOnCancelListener { finish(false, "declined") }.show()
        handler.postDelayed(timeout, 24000)
        voice.ask(text("AWAITING_CONFIRMATION")) { spoken ->
            if (token == generation && active) {
                if (spoken) listen(token) else finish(false, "unheard")
            }
        }
    }

    private fun listen(token: Int) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dialog?.setMessage(text("microphone_unavailable"))
            voice.falar("consent_microphone", text("microphone_unavailable"))
            return
        }
        try {
            recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            else {
                dialog?.setMessage(text("offline_unavailable"))
                voice.falar("consent_offline", text("offline_unavailable"))
                return
            }
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (token != generation || !active) return
                    val raw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val result = CrossingAnswer.parse(raw)
                    android.util.Log.i("CrossingConsent", "Speech parsed=${result?.toString() ?: "unknown"}")
                    if (result == null) retryOrExplain() else finish(result, if (result) "consent_yes" else "declined")
                }
                override fun onError(error: Int) {
                    if (token == generation && active) {
                        android.util.Log.w("CrossingConsent", "Speech error=$error")
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) retryOrExplain()
                        else { dialog?.setMessage(text("speech_error")); voice.falar("consent_speech_error",text("speech_error")) }
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {
                    if (token == generation && active) {
                        dialog?.setMessage(text("listening"))
                        runCatching {
                            tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC,80)
                            tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP,120)
                        }
                        android.util.Log.i("CrossingConsent", "Microphone ready")
                    }
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, when(language()) { "en" -> "en-US"; "es" -> "es-ES"; else -> "pt-BR" })
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                if (Build.VERSION.SDK_INT >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    arrayListOf(text("yes_word"),text("no")))
            })
        } catch (_: Exception) { finish(false, "unheard") }
    }

    private fun retryOrExplain() {
        if (++retries > 1) {
            dialog?.setMessage(text("speech_error"))
            voice.falar("consent_speech_error",text("speech_error"))
            return
        }
        val token = ++generation
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        voice.ask(text("retry_speech")) { spoken ->
            if (spoken && active && token == generation) listen(token)
        }
    }

    private fun finish(accepted: Boolean, key: String) {
        val answer = callback ?: return
        cancel()
        voice.forcar("crossing_consent", text(key))
        answer(accepted)
    }

    fun cancel() {
        val wasActive = active
        generation++; callback = null
        handler.removeCallbacks(timeout)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        tone?.release(); tone = null
        dialog?.dismiss(); dialog = null
        if (wasActive) voice.interromper()
    }
}
