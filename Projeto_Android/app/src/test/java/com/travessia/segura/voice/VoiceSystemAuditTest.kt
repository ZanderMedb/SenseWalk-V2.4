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
    @Test fun dangerInterruptsAnOrdinaryGuidanceInstruction() {
        voice.falarEstado("crossing_stage","AWAIT_RIGHT","APONTE DIREITA")
        assertEquals("APONTE DIREITA",tts.lastSpokenText)
        voice.falarEstado("scene_approach","approaching_left","PERIGO",VoicePriority.SAFETY)
        assertEquals("PERIGO",tts.lastSpokenText)
    }
    @Test fun staleInstructionIsNotSpokenAfterStageChange() {
        voice.falarEstado("scene_approach","car","DANGER",VoicePriority.SAFETY)
        voice.falarEstado("crossing_stage","AWAIT_RIGHT","APONTE DIREITA") // fica na fila atrás do perigo
        voice.falarEstado("crossing_stage","AWAIT_LEFT","APONTE ESQUERDA")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(tts.spokenTextList.contains("APONTE DIREITA"))
    }
    @Test fun sameStageRequestedEveryFrameIsSpokenOnce() {
        repeat(100) { voice.falarEstado("crossing_stage","AWAIT_RIGHT","APONTE DIREITA") }
        assertEquals(1,tts.spokenTextList.count { it == "APONTE DIREITA" })
    }
    @Test fun stopControlOutranksGuidanceButNotDanger() {
        voice.falarEstado("crossing_stage","AWAIT_RIGHT","APONTE DIREITA")
        voice.forcarPrioritario("cancelado","PARADO",VoicePriority.CONTROL)
        assertEquals("PARADO",tts.lastSpokenText)
        voice.falarEstado("scene_approach","car","DANGER",VoicePriority.SAFETY)
        voice.forcarPrioritario("cancelado2","PARADO2",VoicePriority.CONTROL)
        assertEquals("DANGER",tts.lastSpokenText)
    }
    @Test fun speakingCallbackLetsTheMicrophoneCloseBeforeSpeech() {
        val events = mutableListOf<Boolean>()
        voice.onSpeakingChanged = { events += it }
        voice.falarEstado("crossing_stage","AWAIT_RIGHT","APONTE DIREITA")
        assertTrue(voice.ocupado)
        shadowOf(Looper.getMainLooper()).idle()
        // "true" sempre precede a fala; o TTS simulado conclui sozinho, então "false" pode já ter chegado.
        assertEquals(true,events.first())
        voice.interromper(preserveSafety = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(voice.ocupado)
        assertEquals(false,events.last())
    }
}
