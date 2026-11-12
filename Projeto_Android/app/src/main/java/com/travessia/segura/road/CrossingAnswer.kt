package com.travessia.segura.road

import java.text.Normalizer
import java.util.Locale

object CrossingAnswer {
    fun stopRequested(raw: String): Boolean {
        val words = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "").split("[^a-z]+".toRegex())
        return words.any { it in setOf("parar","pare","stop","detener","deten") }
    }
    fun parse(raw: String): Boolean? {
        val text = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "").replace("[^a-z ]".toRegex(), " ")
            .trim().replace("\\s+".toRegex(), " ")
        return when (text) {
            "sim", "sim quero", "quero atravessar", "sim quero atravessar", "sim eu quero", "sim por favor", "sim pode analisar", "sim sim", "yes", "si" -> true
            "nao", "nao quero", "nao quero atravessar", "cancelar", "no", "cancel" -> false
            else -> null
        }
    }
}
