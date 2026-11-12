package com.travessia.segura.voice

import android.os.Looper
import android.speech.tts.TextToSpeech
import com.travessia.segura.config.AppConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech

/** Executa VoiceSystem com TTS simulado; não comprova áudio/latência no aparelho. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceSystemAuditTest {
    private lateinit var voice: VoiceSystem
    private lateinit var tts: ShadowTextToSpeech
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        voice = VoiceSystem(context,AppConfig(context))
        tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        voice.onInit(TextToSpeech.SUCCESS)
    }
    @Test fun consentQuestionMustNotCutAnActiveDangerWarning() {
        voice.falarEstado("scene_approach","car","DANGER",VoicePriority.SAFETY)
        voice.ask("QUESTION") {}
        assertEquals("DANGER",tts.lastSpokenText)
        assertFalse(tts.isStopped)
    }
    @Test fun statusCommandMustNotFlushAnActiveDangerWarning() {
        voice.falarEstado("scene_approach","car","DANGER",VoicePriority.SAFETY)
        voice.forcar("status","STATUS")
        assertEquals("DANGER",tts.lastSpokenText)
    }
    @Test fun invalidationMustStopFavorableAlreadySubmittedToTts() {
        voice.falarEstado("crossing_stage","favorable","FAVORABLE")
        voice.observarEstado("crossing_stage","inconclusive")
        assertTrue(tts.isStopped)
    }
    @Test fun dangerInterruptsFavorableAndDiscardsLowerPriorityQueue() {
        voice.falarEstado("crossing_stage","favorable","FAVORABLE")
        voice.falarEstado("context","waiting","OLD CONTEXT",VoicePriority.INFO)
        voice.falarEstado("scene_approach","car","DANGER",VoicePriority.SAFETY)
        assertEquals("DANGER",tts.lastSpokenText)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(tts.spokenTextList.contains("OLD CONTEXT"))
    }
    @Test fun stateInterruptedBeforeCompletionCanBeAnnouncedAgain() {
        voice.falarEstado("instruction","right","RIGHT")
        voice.falarEstado("scene_approach","car","DANGER",VoicePriority.SAFETY)
        shadowOf(Looper.getMainLooper()).idle()
        voice.falarEstado("instruction","right","RIGHT")
        assertEquals(2,tts.spokenTextList.count { it == "RIGHT" })
    }
}
