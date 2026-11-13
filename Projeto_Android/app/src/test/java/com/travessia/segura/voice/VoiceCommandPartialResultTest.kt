package com.travessia.segura.voice

import android.os.Bundle
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Resultado parcial entrega "pronto" uma vez; o resultado final da mesma janela não repete o comando. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoiceCommandPartialResultTest {
    private val commands = mutableListOf<VoiceCommandController.Command>()
    private val errors = mutableListOf<Int>()
    private val controller = VoiceCommandController(RuntimeEnvironment.getApplication(), { "pt" },
        object : VoiceCommandController.Listener {
            override fun onListeningChanged(isListening: Boolean) {}
            override fun onCommand(command: VoiceCommandController.Command, rawText: String) { commands += command }
            override fun onRecognitionError(errorCode: Int) { errors += errorCode }
        })
    private fun openWindow() {
        VoiceCommandController::class.java.getDeclaredField("listening").apply { isAccessible = true }.set(controller, true)
        VoiceCommandController::class.java.getDeclaredField("deliveredThisWindow").apply { isAccessible = true }.set(controller, false)
    }
    private fun bundle(vararg text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*text))
    }

    @Test fun partialReadyIsDeliveredOnceAndFinalResultIsIgnored() {
        openWindow()
        controller.onPartialResults(bundle("pron"))
        assertTrue(commands.isEmpty())
        controller.onPartialResults(bundle("pronto"))
        controller.onPartialResults(bundle("pronto"))
        controller.onResults(bundle("pronto"))
        controller.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(listOf(VoiceCommandController.Command.CONFIRM_DIRECTION), commands)
        assertTrue(errors.isEmpty())
        assertFalse(controller.isListening)
    }

    @Test fun stopIsRecognizedFromPartialResult() {
        openWindow()
        controller.onPartialResults(bundle("pronto parar"))
        assertEquals(listOf(VoiceCommandController.Command.STOP), commands)
    }

    @Test fun finalResultStillWorksWithoutPartials() {
        openWindow()
        controller.onResults(bundle("continuar"))
        assertEquals(listOf(VoiceCommandController.Command.CONTINUE), commands)
        openWindow()
        controller.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), errors)
    }
}
