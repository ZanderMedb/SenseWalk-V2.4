package com.travessia.segura.logic

import android.os.Looper
import android.speech.tts.TextToSpeech
import com.travessia.segura.config.AppConfig
import com.travessia.segura.detection.RawDetection
import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.road.CrossingRiskAnalyzer
import com.travessia.segura.road.RoadText
import com.travessia.segura.tracking.Rastreador
import com.travessia.segura.voice.VoiceSystem
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowTextToSpeech

/** A detecção de faixa continua chegando ao StateMachine, mas não gera falas rotineiras sobre faixa. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CrosswalkSpeechTest {
    private val crosswalkWords = listOf("faixa", "crosswalk", "paso peatonal", "cruce peatonal")
    private fun mentionsCrosswalk(spoken: List<String>) =
        spoken.filter { text -> crosswalkWords.any { text.contains(it, ignoreCase = true) } }

    @Test fun routineCrosswalkMessagesAreSilentButStateStillTracksDetection() {
        val context = RuntimeEnvironment.getApplication()
        val config = AppConfig(context).also { it.appLanguage = "pt" }
        val voice = VoiceSystem(context, config).also { it.onInit(TextToSpeech.SUCCESS) }
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val machine = StateMachine(config, voice, Rastreador(config))
        machine.entrarEstado(AppConfig.EST_RECONHECENDO)
        repeat(80) {
            // faixaDet=true em todos os frames, centro deslocado (alinhamento) e sem veículos.
            machine.processar(emptyMap(), 0, "NENHUM", true, 100f, 640, 360)
            shadowOf(Looper.getMainLooper()).idle()
            ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        }
        assertEquals("detecção preservada: estado de faixa alcançado", AppConfig.EST_FAIXA, machine.estado)
        assertEquals(emptyList<String>(), mentionsCrosswalk(tts.spokenTextList))
    }

    @Test fun vehicleOnCrossingPathIsStillAnAlertWithoutCrosswalkWording() {
        val context = RuntimeEnvironment.getApplication()
        val config = AppConfig(context).also { it.appLanguage = "pt" }
        val voice = VoiceSystem(context, config).also { it.onInit(TextToSpeech.SUCCESS) }
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val machine = StateMachine(config, voice, Rastreador(config))
        val stripe = RawDetection(80f, 150f, 550f, 310f, 8, "crosswalk", .8f)
        val car = VeiculoInfo("car", .9f, 300f, 230f, "PARADO", 0f, floatArrayOf(250f, 180f, 350f, 280f), 100f, 1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(car), 640, 360, stripe, cameraReliable = true)
        machine.processar(mapOf(1 to car), 0, "NENHUM", true, 300f, 640, 360, risk,
            monitorHazards = true, guidedVerification = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(tts.spokenTextList.toString(), tts.spokenTextList.contains(RoadText.get("pt", "crosswalk_occupied")))
        assertEquals(emptyList<String>(), mentionsCrosswalk(tts.spokenTextList))
    }

    @Test fun guidedSequenceTextsDoNotMentionCrosswalkAndAskForReady() {
        for (lang in listOf("pt", "en", "es")) {
            for (key in listOf("verify_right", "verify_left", "verify_front", "verify_inconclusive", "verify_no_crosswalk",
                    "verify_direction_unavailable", "road_confirmed", "analysis_intro", "crosswalk_occupied",
                    "ACTIVE_ANALYSIS", "say_ready_hint")) {
                val text = RoadText.get(lang, key).lowercase()
                assertFalse("$lang/$key: $text", crosswalkWords.any { it in text })
            }
        }
        // Falas curtas; "diga pronto" só é acrescentado quando não há sensor de orientação (USB).
        // "Aponte e mantenha": nenhuma instrução da sequência pede "pronto".
        assertEquals("Aponte a câmera para a direita e mantenha.", RoadText.get("pt", "verify_right"))
        assertEquals("Agora, esquerda. Mantenha.", RoadText.get("pt", "verify_left"))
        assertEquals("Agora, à frente. Mantenha.", RoadText.get("pt", "verify_front"))
        for (k in listOf("verify_right", "verify_left", "verify_front")) assertFalse(RoadText.get("pt", k).contains("pronto"))
        assertTrue(RoadText.get("pt", "say_ready_suffix").contains("pronto"))
        assertTrue(RoadText.get("pt", "ready_explain").contains("pronto"))
        assertEquals("fala curta para a direita vir logo", "Rua identificada.", RoadText.get("pt", "road_confirmed"))
        assertTrue(RoadText.get("pt", "analysis_intro").contains("parar"))
    }
}
