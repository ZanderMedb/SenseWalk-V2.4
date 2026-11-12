package com.travessia.segura.road

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.travessia.segura.config.AppConfig
import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.logic.StateMachine
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidSafetyIntegrationTest {
    @Test fun lateralDangerCannotBeFollowedByNoVehiclesMessage() {
        val context = RuntimeEnvironment.getApplication()
        val config = AppConfig(context).also { it.appLanguage = "pt" }
        val voice = VoiceSystem(context,config).also { it.onInit(TextToSpeech.SUCCESS) }
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val machine = StateMachine(config,voice,Rastreador(config))
        machine.entrarEstado(AppConfig.EST_VIA_LIVRE)
        val car = VeiculoInfo("car",.9f,40f,90f,"APROXIMANDO",70f,floatArrayOf(20f,70f,60f,110f),40f,1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(car),640,360,cameraReliable=true)
        repeat(6) {
            machine.processar(mapOf(1 to car),0,"NENHUM",false,-1f,640,360,risk)
            shadowOf(Looper.getMainLooper()).idle()
            ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        }
        assertTrue(tts.spokenTextList.any { "aproximando" in it })
        assertFalse(tts.spokenTextList.contains(config.tr("voice_road_clear")))
        assertEquals("AGUARDE",machine.situacaoAtual)
    }

    @Test fun hazardInferenceRunsBeforeConsentAndStopsWithMonitoring() {
        val context = RuntimeEnvironment.getApplication()
        // Desliga apenas FFNet neste teste: não carrega um intérprete nativo no Robolectric.
        val config = AppConfig(context).also { it.automaticRoadDetection = false }
        val coordinator = InferenceCoordinator(context,config,{false},{},{})
        val bitmap = Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        var calls = 0
        coordinator.start()
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        coordinator.process(bitmap,0,SystemClock.elapsedRealtime()) { calls++ }
        assertEquals(1,calls)
        coordinator.suspendMonitoring()
        coordinator.process(bitmap,0,SystemClock.elapsedRealtime()) { calls++ }
        assertEquals(1,calls)
        bitmap.recycle(); coordinator.close()
    }

    @Test fun oldFramesAndPreviousCameraGenerationCannotBeAccepted() {
        val context = RuntimeEnvironment.getApplication()
        val config = AppConfig(context).also { it.automaticRoadDetection = false }
        val coordinator = InferenceCoordinator(context,config,{false},{},{})
        coordinator.start()
        val token = coordinator.resultToken
        val captured = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1600))
        assertFalse(coordinator.acceptsResult(token,captured))
        coordinator.invalidateSession()
        assertFalse(coordinator.acceptsResult(token,SystemClock.elapsedRealtime()))
        val bitmap = Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        var called = false
        coordinator.process(bitmap,0,captured) { called = true }
        assertFalse(called)
        bitmap.recycle(); coordinator.close()
    }

    @Test fun automaticDefaultAndExplicitDisablePersistAcrossConfigInstances() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("travessia_config",0).edit().clear().commit()
        assertTrue(AppConfig(context).automaticRoadDetection)
        AppConfig(context).automaticRoadDetection = false
        assertFalse(AppConfig(context).automaticRoadDetection)
        for (language in listOf("pt","en","es")) {
            AppConfig(context).appLanguage = language
            assertEquals(language,AppConfig(context).appLanguage)
            assertTrue(RoadText.get(language,"verify_direction_unavailable").isNotBlank())
        }
    }

    @Test fun completedInferenceDoesNotRefreshTheCaptureTimestamp() {
        val context = RuntimeEnvironment.getApplication()
        val config = AppConfig(context).also { it.automaticRoadDetection = false }
        val coordinator = InferenceCoordinator(context,config,{false},{},{})
        coordinator.start()
        val captured = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1400))
        coordinator.completedYoloFrame(captured)
        assertTrue(coordinator.hasRecentYoloResult)
        ShadowSystemClock.advanceBy(Duration.ofMillis(101))
        assertFalse(coordinator.hasRecentYoloResult)
        coordinator.invalidateSession()
        assertEquals(0L,coordinator.successfulYoloFrames)
        coordinator.close()
    }
}
