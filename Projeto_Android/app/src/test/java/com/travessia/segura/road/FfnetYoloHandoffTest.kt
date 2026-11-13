package com.travessia.segura.road

import android.graphics.Bitmap
import android.os.SystemClock
import com.travessia.segura.config.AppConfig
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

/**
 * FFNet -> YOLO com segmentador injetado (rua em toda a ROI). Mede chamadas, não qualidade visual.
 * Não substitui teste no aparelho com o intérprete TFLite real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FfnetYoloHandoffTest {
    private lateinit var config: AppConfig
    private lateinit var bitmap: Bitmap
    private var manual = false
    private var ffnet = 0
    private var yolo = 0
    private val confirmations = mutableListOf<Long>()
    private var roadVisible = true

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("travessia_config", 0).edit().clear().commit()
        config = AppConfig(context).also {
            it.automaticRoadDetection = true
            it.roadConfirmationMs = 500
            it.roadCooldownMs = 5000
            it.roadSegmentationFps = 2
        }
        bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    }
    @After fun tearDown() { bitmap.recycle() }

    private fun coordinator() = InferenceCoordinator(RuntimeEnvironment.getApplication(), config, { manual },
        { token -> confirmations += token }, {},
        segmenter = { ffnet++; ByteArray(256 * 128) { if (roadVisible) 0 else 10 } },
        ffnetExecutor = java.util.concurrent.Executor { it.run() }) // determinístico; produção usa thread própria

    private fun frames(c: InferenceCoordinator, ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100)); elapsed += 100
            c.process(bitmap, 0, SystemClock.elapsedRealtime()) { yolo++ }
        }
    }

    @Test fun confirmedRoadStartsOneAnalysisAndSuspendsFfnetWhileYoloRuns() {
        val c = coordinator()
        c.start()
        assertEquals(MonitorPhase.WAITING_FOR_ROAD, c.phase)
        frames(c, 3000)
        assertEquals("uma rua -> uma análise", 1, confirmations.size)
        assertEquals(MonitorPhase.ANALYZING, c.phase)
        val ffnetAtStart = ffnet
        val yoloAtStart = yolo
        frames(c, 10000)
        assertEquals("FFNet não infere durante a análise", ffnetAtStart, ffnet)
        assertTrue("YOLO continua durante a análise", yolo > yoloAtStart + 50)
        assertEquals(1, confirmations.size)
        c.close()
    }

    @Test fun normalEndReturnsToRoadSearchAfterCooldown() {
        val c = coordinator()
        c.start()
        frames(c, 3000)
        assertEquals(1, confirmations.size)
        c.finishAnalysis()
        assertEquals(MonitorPhase.WAITING_FOR_ROAD, c.phase)
        val before = ffnet
        frames(c, 4000)
        assertTrue("FFNet volta a procurar rua", ffnet > before)
        assertEquals("não reinicia dentro do intervalo de rearme", 1, confirmations.size)
        frames(c, 4000)
        assertEquals(2, confirmations.size)
        c.close()
    }

    @Test fun manualStartRunsYoloDirectlyWithoutFfnet() {
        config.automaticRoadDetection = false
        val c = coordinator()
        manual = true
        assertTrue(c.beginAnalysis())
        assertFalse("início duplicado ignorado", c.beginAnalysis())
        frames(c, 3000)
        assertEquals(0, ffnet)
        assertTrue(yolo > 20)
        assertTrue(confirmations.isEmpty())
        c.close()
    }

    @Test fun manualStartWhileWaitingSuspendsFfnetImmediately() {
        val c = coordinator()
        c.start()
        roadVisible = false
        frames(c, 1500)
        assertTrue(ffnet > 0)
        manual = true; c.beginAnalysis()
        val before = ffnet
        roadVisible = true
        frames(c, 5000)
        assertEquals(before, ffnet)
        assertTrue(confirmations.isEmpty())
        c.close()
    }

    @Test fun stopKeepsEverythingStoppedAndCameraSwitchDropsOldResults() {
        val c = coordinator()
        c.start()
        frames(c, 3000)
        val token = c.resultToken
        val captured = SystemClock.elapsedRealtime()
        c.invalidateSession() // troca de câmera
        assertFalse(c.acceptsResult(token, captured))
        c.suspendMonitoring() // "Parar"
        assertEquals(MonitorPhase.STOPPED, c.phase)
        val f = ffnet; val y = yolo
        frames(c, 20000)
        assertEquals(f, ffnet)
        assertEquals(y, yolo)
        assertEquals(1, confirmations.size)
        c.close()
    }
    @Test fun framesAreSkippedOnlyWhenNoModelIsDueAndYoloRateIsPreserved() {
        config.roadYoloFps = 10 // intervalo de 100 ms
        val c = coordinator()
        manual = true; c.beginAnalysis()
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        assertTrue(c.wantsFrame())
        c.process(bitmap, 0, SystemClock.elapsedRealtime()) { yolo++ }
        assertEquals(1, yolo)
        ShadowSystemClock.advanceBy(Duration.ofMillis(30))
        assertFalse("YOLO acabou de rodar e FFNet está suspensa: descartar antes de converter", c.wantsFrame())
        ShadowSystemClock.advanceBy(Duration.ofMillis(70))
        assertTrue("frame mais novo é aceito assim que o YOLO estiver devido", c.wantsFrame())
        c.close()
        // Mesma sequência de frames a 30 fps, com e sem o descarte antecipado: mesma quantidade de YOLO.
        fun run(gated: Boolean): Int {
            val k = coordinator(); var runs = 0
            k.beginAnalysis()
            repeat(300) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(33))
                if (!gated || k.wantsFrame()) k.process(bitmap, 0, SystemClock.elapsedRealtime()) { runs++ }
            }
            k.close(); return runs
        }
        val withoutGate = run(gated = false)
        val withGate = run(gated = true)
        assertTrue(withoutGate > 0)
        assertEquals("descarte antecipado não reduz a frequência do YOLO", withoutGate, withGate)
        assertEquals(0, ffnet)
    }

    @Test fun waitingForRoadWantsFramesForFfnetEvenBetweenYoloRuns() {
        val c = coordinator()
        c.start()
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        assertTrue(c.wantsFrame())
        c.suspendMonitoring()
        assertFalse("parado: nenhum frame é convertido", c.wantsFrame())
        c.close()
    }
    @Test fun ffnetRunsOffTheYoloThreadWithAtMostOneInFlight() {
        // Executor que só enfileira: simula FFNet lenta em outra thread.
        val pending = mutableListOf<Runnable>()
        val c = InferenceCoordinator(RuntimeEnvironment.getApplication(), config, { false }, { confirmations += it }, {},
            segmenter = { ffnet++; ByteArray(256 * 128) }, ffnetExecutor = java.util.concurrent.Executor { pending += it })
        c.start()
        frames(c, 3000) // 30 frames; YOLO não espera a FFNet
        assertEquals("sem fila: uma FFNet em andamento por vez", 1, pending.size)
        assertTrue("YOLO segue no ritmo próprio enquanto a FFNet está ocupada", yolo >= 25)
        pending.removeAt(0).run()
        frames(c, 600)
        assertEquals("liberada a FFNet, um novo frame é aceito", 1, pending.size)
        c.close()
    }
    @Test fun slowFfnetLikeOnThePhoneStillStartsTheAnalysisAutomatically() {
        // No aparelho a máscara ficava pronta ~1,5–1,8 s após a captura e era descartada como "imagem atrasada".
        val pending = mutableListOf<Runnable>()
        val c = InferenceCoordinator(RuntimeEnvironment.getApplication(), config, { false }, { confirmations += it }, {},
            segmenter = { ffnet++; ByteArray(256 * 128) }, ffnetExecutor = java.util.concurrent.Executor { pending += it })
        c.start()
        repeat(12) {
            frames(c, 600)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1200)) // FFNet lenta: termina ~1,8 s depois da captura
            pending.toList().also { pending.clear() }.forEach { it.run() }
        }
        assertEquals("rua confirmada mesmo com FFNet lenta", 1, confirmations.size)
        c.close()
    }
}
