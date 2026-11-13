package com.travessia.segura.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.travessia.segura.R
import com.travessia.segura.config.LanguageManager
import com.travessia.segura.voice.VoiceCommandController
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verificações estáticas/automatizáveis. TalkBack real exige o checklist físico (ver relatório). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccessibilityLayoutTest {
    private fun inflateMain(): ViewGroup {
        val context = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat)
        return LayoutInflater.from(context).inflate(R.layout.activity_main, null) as ViewGroup
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun checkMainLayout() {
        val root = inflateMain()
        val density = root.resources.displayMetrics.density
        val buttons = all(root).filterIsInstance<Button>()
        assertEquals(5, buttons.size)
        for (b in buttons) assertTrue("${b.id}: alvo de toque >= 48dp", b.minHeight >= (48 * density).toInt())
        // Decorativos/visuais fora do TalkBack; controles essenciais presentes.
        for (id in listOf(R.id.previewView, R.id.overlayView, R.id.externalImageView))
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, root.findViewById<View>(id).importantForAccessibility)
        for (id in listOf(R.id.btnToggle, R.id.btnVoice, R.id.btnConfig))
            assertEquals(View.VISIBLE, root.findViewById<View>(id).visibility)
        // Fonte da câmera só em Configurações: não aparece na tela da câmera.
        assertEquals(View.GONE, root.findViewById<View>(R.id.btnSource).visibility)
    }

    @Test fun mainLayoutPortraitHasLargeTargetsAndHidesDecorativeViews() = checkMainLayout()

    @Config(qualifiers = "land")
    @Test fun mainLayoutLandscapeHasLargeTargetsAndHidesDecorativeViews() = checkMainLayout()

    @Config(qualifiers = "land")
    @Test fun traversalOrderPutsStopRightAfterTheSpokenStatus() {
        val root = inflateMain()
        val ids = listOf(R.id.tvScreenTitle, R.id.tvAlertStatus, R.id.btnToggle, R.id.btnVoice, R.id.btnCrossing,
            R.id.btnConfig, R.id.tvEstado, R.id.tvSituacao, R.id.tvDetectionCount, R.id.tvInfo)
        val views = ids.map { root.findViewById<View>(it) }
        AccessibilityOrder.apply(views)
        for (i in 1 until views.size) assertEquals(views[i - 1].id, views[i].accessibilityTraversalAfter)
        assertEquals(R.id.tvAlertStatus, root.findViewById<View>(R.id.btnToggle).accessibilityTraversalAfter)
    }

    @Test fun layoutsUseScalableTextAndNoFixedButtonHeights() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        for (path in listOf("layout/activity_main.xml", "layout-land/activity_main.xml", "layout/activity_login.xml")) {
            val xml = File(res, path).readText()
            assertFalse("$path: textSize em dp/px não acompanha fonte ampliada",
                Regex("textSize=\"[0-9.]+(dp|px)\"").containsMatchIn(xml))
            for (button in Regex("<Button\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(xml))
                assertTrue("$path: botão com altura fixa corta texto ampliado",
                    button.value.contains("layout_height=\"wrap_content\"") && button.value.contains("minHeight="))
        }
    }

    @Test fun essentialControlsHaveSpokenDescriptionsInAllLanguages() {
        for (lang in listOf(LanguageManager.PT, LanguageManager.EN, LanguageManager.ES))
            for (key in listOf("accessibility_start", "accessibility_stop", "accessibility_source", "accessibility_settings",
                    "voice_button_description", "voice_help")) {
                val text = LanguageManager.text(lang, key)
                assertTrue("$lang/$key", text.isNotBlank() && text != key)
            }
    }

    @Test fun stopIsAlwaysRecognizedAndHelpListsTheGuidedCommands() {
        val c = VoiceCommandController(RuntimeEnvironment.getApplication(), { "pt" }, object : VoiceCommandController.Listener {
            override fun onListeningChanged(isListening: Boolean) {}
            override fun onCommand(command: VoiceCommandController.Command, rawText: String) {}
            override fun onRecognitionError(errorCode: Int) {}
        })
        val parse = VoiceCommandController::class.java.getDeclaredMethod("parse", String::class.java).apply { isAccessible = true }
        for (t in listOf("parar", "pare", "parar análise", "pronto parar", "cancelar e parar", "stop", "detener"))
            assertEquals(t, VoiceCommandController.Command.STOP, parse.invoke(c, t))
        val help = LanguageManager.text(LanguageManager.PT, "voice_help")
        for (word in listOf("pronto", "repetir", "cancelar", "parar")) assertTrue(word, help.contains(word))
    }

    @Test fun liveTextViewsExistForStateAnnouncements() {
        val root = inflateMain()
        for (id in listOf(R.id.tvAlertStatus, R.id.tvInfo, R.id.tvEstado)) assertTrue(root.findViewById<View>(id) is TextView)
    }
}
