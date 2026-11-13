package com.travessia.segura.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class VoiceCommandValidationTest {
    @Test fun stopRemainsAvailableInConsentDialogAndDoesNotAnswerYes() {
        val controller = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity = controller.setup().get()
        val config = com.travessia.segura.config.AppConfig(activity)
        val voice = VoiceSystem(activity,config)
        var stopped = false; var answered = false
        val consent = com.travessia.segura.road.CrossingConsentController(activity,voice,{"pt"},{stopped=true})
        consent.ask { answered=true }
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(stopped); assertFalse(answered); assertFalse(consent.active)
        for (s in listOf("parar","stop","detener")) assertTrue(com.travessia.segura.road.CrossingAnswer.stopRequested(s))
        assertFalse(com.travessia.segura.road.CrossingAnswer.stopRequested("preparar"))
        consent.cancel(); controller.pause().stop().destroy()
    }
    private fun parse(text: String): VoiceCommandController.Command {
        val controller = VoiceCommandController(RuntimeEnvironment.getApplication(),{"pt"},object: VoiceCommandController.Listener {
            override fun onListeningChanged(isListening: Boolean) {}
            override fun onCommand(command: VoiceCommandController.Command, rawText: String) {}
            override fun onRecognitionError(errorCode: Int) {}
        })
        return VoiceCommandController::class.java.getDeclaredMethod("parse",String::class.java).apply { isAccessible=true }
            .invoke(controller,text) as VoiceCommandController.Command
    }
    @Test fun stopWinsOverLanguageAndStart() {
        for (s in listOf("parar português", "start then stop", "detener español"))
            assertEquals(VoiceCommandController.Command.STOP,parse(s))
    }
    @Test fun negationAndSubstringsDoNotStartOrStopAnalysis() {
        for (s in listOf("não iniciar", "do not start", "no comenzar", "preparar", "restart"))
            assertEquals(VoiceCommandController.Command.UNKNOWN,parse(s))
    }
    @Test fun usbAndDirectionCommandsUseExistingExplicitActions() {
        assertEquals(VoiceCommandController.Command.SOURCE_USB,parse("câmera usb"))
        for (s in listOf("câmera posicionada", "camera ready", "cámara lista"))
            assertEquals(VoiceCommandController.Command.CONFIRM_DIRECTION,parse(s))
    }
    @Test fun guidedSequenceCommandsInAllLanguages() {
        val c = VoiceCommandController.Command.CONFIRM_DIRECTION
        for (t in listOf("pronto","Pronto!","ready","done","listo")) assertEquals(t,c,parse(t))
        for (t in listOf("continuar","continue","próximo","siguiente")) assertEquals(t,VoiceCommandController.Command.CONTINUE,parse(t))
        for (t in listOf("repetir","repita","repeat","repite")) assertEquals(t,VoiceCommandController.Command.REPEAT,parse(t))
        for (t in listOf("cancelar","cancel")) assertEquals(t,VoiceCommandController.Command.CANCEL,parse(t))
        for (t in listOf("analisar novamente","analyze again","analizar de nuevo")) assertEquals(t,VoiceCommandController.Command.RESTART,parse(t))
        for (t in listOf("iniciar análise","start analysis","comenzar")) assertEquals(t,VoiceCommandController.Command.START,parse(t))
        for (t in listOf("parar","pare","stop","detener")) assertEquals(t,VoiceCommandController.Command.STOP,parse(t))
    }
    @Test fun stopWinsOverGuidanceCommandsAndNegationBlocksThem() {
        assertEquals(VoiceCommandController.Command.STOP,parse("pronto parar"))
        assertEquals(VoiceCommandController.Command.STOP,parse("cancelar e parar"))
        assertEquals(VoiceCommandController.Command.UNKNOWN,parse("não continuar"))
        assertEquals(VoiceCommandController.Command.UNKNOWN,parse("não cancelar"))
    }
}
