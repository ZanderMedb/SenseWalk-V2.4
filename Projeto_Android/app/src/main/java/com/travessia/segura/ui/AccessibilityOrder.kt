package com.travessia.segura.ui

import android.view.View

/**
 * Ordem lógica do TalkBack: título, estado falado, Parar/Iniciar, Voz, direção (opcional), fonte,
 * configurações e, por último, os textos informativos. Parar/Iniciar fica sempre no início para quem
 * navega por gestos, independentemente da posição visual em retrato ou paisagem.
 */
object AccessibilityOrder {
    fun apply(views: List<View>) {
        for (i in 1 until views.size) {
            if (views[i - 1].id == View.NO_ID) views[i - 1].id = View.generateViewId()
            views[i].accessibilityTraversalAfter = views[i - 1].id
        }
        for (v in views) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
