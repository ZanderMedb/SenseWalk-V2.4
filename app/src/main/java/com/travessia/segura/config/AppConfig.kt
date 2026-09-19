package com.travessia.segura.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuração centralizada.
 * Valores otimizados para Galaxy S21 FE.
 */
class AppConfig(context: Context) {

    private val defaultLanguage = LanguageManager.deviceDefaultLanguage()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("travessia_config", Context.MODE_PRIVATE)


    // ── Idioma ──
    var appLanguage: String
        get()  = prefs.getString("app_language", defaultLanguage) ?: defaultLanguage
        set(v) = prefs.edit().putString("app_language", v).apply()

    // ── Acessibilidade / Comandos de voz ──
    var voiceCommandsEnabled: Boolean
        get()  = prefs.getBoolean("voice_commands_enabled", true)
        set(v) = prefs.edit().putBoolean("voice_commands_enabled", v).apply()

    // Quando ativado, abre uma janela de escuta uma vez ao entrar na tela principal.
    // O Android ainda exige permissao de microfone concedida pelo usuario.
    var voiceAutoListen: Boolean
        get()  = prefs.getBoolean("voice_auto_listen", false)
        set(v) = prefs.edit().putBoolean("voice_auto_listen", v).apply()

    // ── Classes do modelo ──
    val keywordsVeiculos      = setOf("car", "motorcycle", "bicycle", "truck", "ambulance")
    val keywordsFaixa         = setOf("crosswalk")
    val keywordsSemaforoPrefixo = "traffic-light"
    val kwVermelho = setOf("red")
    val kwVerde    = setOf("green")
    val kwAmarelo  = setOf("yellow")

    private val chavesVeiculos = mapOf(
        "car"        to "vehicle_car",
        "motorcycle" to "vehicle_motorcycle",
        "bicycle"    to "vehicle_bicycle",
        "truck"      to "vehicle_truck",
        "ambulance"  to "vehicle_ambulance"
    )

    val classNames = listOf(
        "car",                 // 0
        "motorcycle",          // 1
        "bicycle",             // 2
        "truck",               // 3
        "ambulance",           // 4
        "traffic-light-green", // 5
        "traffic-light-red",   // 6
        "traffic-light-yellow",// 7
        "crosswalk"            // 8
    )

    // ── Detecção ──
    // confMinima mais alta = menos ruído no celular (0.35 é um bom começo)
    var confMinima: Float
        get()  = prefs.getFloat("conf_minima", 0.20f)
        set(v) = prefs.edit().putFloat("conf_minima", v).apply()

    var inferenciaTamanho: Int
        get()  = prefs.getInt("inferencia_tamanho", 640)
        set(v) = prefs.edit().putInt("inferencia_tamanho", v).apply()

    var historicoMax: Int
        get()  = prefs.getInt("historico_max", 20)
        set(v) = prefs.edit().putInt("historico_max", v).apply()

    // ── Velocidades (px/s) ──
    var velParadoPxs: Float
        get()  = prefs.getFloat("vel_parado_pxs", 18f)
        set(v) = prefs.edit().putFloat("vel_parado_pxs", v).apply()

    var velAproximandoPxs: Float
        get()  = prefs.getFloat("vel_aproximando_pxs", 30f)
        set(v) = prefs.edit().putFloat("vel_aproximando_pxs", v).apply()

    // Removido velAltaPxs (não tem mais alerta de alta velocidade)

    // ── Proximidade ──
    var limiarProximoPct: Float
        get()  = prefs.getFloat("limiar_proximo_pct", 0.30f)
        set(v) = prefs.edit().putFloat("limiar_proximo_pct", v).apply()

    // ── Lógica ──
    var tempoRecon: Float
        get()  = prefs.getFloat("tempo_recon", 3.5f)
        set(v) = prefs.edit().putFloat("tempo_recon", v).apply()

    var tempoParadoSeguro: Float
        get()  = prefs.getFloat("tempo_parado_seguro", 3.0f)
        set(v) = prefs.edit().putFloat("tempo_parado_seguro", v).apply()

    var reconThreshold: Float
        get()  = prefs.getFloat("recon_threshold", 0.25f)
        set(v) = prefs.edit().putFloat("recon_threshold", v).apply()

    var reconEarlyThreshold: Float
        get()  = prefs.getFloat("recon_early_threshold", 0.50f)
        set(v) = prefs.edit().putFloat("recon_early_threshold", v).apply()

    var reconEarlyMinFrames: Int
        get()  = prefs.getInt("recon_early_min_frames", 15)
        set(v) = prefs.edit().putInt("recon_early_min_frames", v).apply()

    var upgradeFrames: Int
        get()  = prefs.getInt("upgrade_frames", 8)
        set(v) = prefs.edit().putInt("upgrade_frames", v).apply()

    // ── ROI ──
    var roiAtivo: Boolean
        get()  = prefs.getBoolean("roi_ativo", false)
        set(v) = prefs.edit().putBoolean("roi_ativo", v).apply()

    var roiTop: Float
        get()  = prefs.getFloat("roi_top", 0.05f)
        set(v) = prefs.edit().putFloat("roi_top", v).apply()

    var roiBottom: Float
        get()  = prefs.getFloat("roi_bottom", 0.95f)
        set(v) = prefs.edit().putFloat("roi_bottom", v).apply()

    var roiLeft: Float
        get()  = prefs.getFloat("roi_left", 0.02f)
        set(v) = prefs.edit().putFloat("roi_left", v).apply()

    var roiRight: Float
        get()  = prefs.getFloat("roi_right", 0.98f)
        set(v) = prefs.edit().putFloat("roi_right", v).apply()

    // ── Suavização ──
    var emaAlpha: Float
        get()  = prefs.getFloat("ema_alpha", 0.45f)
        set(v) = prefs.edit().putFloat("ema_alpha", v).apply()

    // ── Visão / Benchmark V2.4 ──
    var opticalFlowEnabled: Boolean
        get()  = prefs.getBoolean("optical_flow_enabled", true)
        set(v) = prefs.edit().putBoolean("optical_flow_enabled", v).apply()

    var opticalFlowMinConfidence: Float
        get()  = prefs.getFloat("optical_flow_min_confidence", 0.35f)
        set(v) = prefs.edit().putFloat("optical_flow_min_confidence", v.coerceIn(0.10f, 0.90f)).apply()

    // Esta versão é voltada a medição; o benchmark registra somente métricas numéricas,
    // nunca frames/imagens. Pode ser desligado nas configurações.
    var benchmarkEnabled: Boolean
        get()  = prefs.getBoolean("benchmark_enabled", true)
        set(v) = prefs.edit().putBoolean("benchmark_enabled", v).apply()

    // ── Tracking V2.4 ──
    // Quantos frames o bbox pode ser mantido por previsão curta quando o YOLO falha.
    var trackerGhostFrames: Int
        get()  = prefs.getInt("tracker_ghost_frames", 4)
        set(v) = prefs.edit().putInt("tracker_ghost_frames", v.coerceIn(0, 8)).apply()

    // Distância máxima do centro (fração da diagonal do frame) aceita no matching.
    // Valor maior tolera mais tremida, mas não convém exagerar para não trocar IDs.
    var trackerCenterDistancePct: Float
        get()  = prefs.getFloat("tracker_center_distance_pct", 0.14f)
        set(v) = prefs.edit().putFloat("tracker_center_distance_pct", v.coerceIn(0.05f, 0.35f)).apply()

    // Crescimento relativo da bbox por segundo usado como sinal de aproximação frontal.
    var crescimentoBBoxAproximando: Float
        get()  = prefs.getFloat("crescimento_bbox_aproximando", 0.14f)
        set(v) = prefs.edit().putFloat("crescimento_bbox_aproximando", v.coerceIn(0.04f, 0.60f)).apply()

    // Mantido por compatibilidade com preferências de versões anteriores.
    var cameraCompMinObjetos: Int
        get()  = prefs.getInt("camera_comp_min_objetos", 3)
        set(v) = prefs.edit().putInt("camera_comp_min_objetos", v).apply()

    // ── Faixa ──
    var faixaMargemAlinhamento: Float
        get()  = prefs.getFloat("faixa_margem_alinhamento", 0.10f)
        set(v) = prefs.edit().putFloat("faixa_margem_alinhamento", v).apply()

    // ── Heartbeat ──
    // Intervalo maior = menos interrupções de voz
    var heartbeatInterval: Float
        get()  = prefs.getFloat("heartbeat_interval", 8.0f)
        set(v) = prefs.edit().putFloat("heartbeat_interval", v).apply()

    // ── Volume ──
    var volumeVoz: Float
        get()  = prefs.getFloat("volume_voz", 1.0f)
        set(v) = prefs.edit().putFloat("volume_voz", v).apply()



    // ── Aparência / Interface ──
    // Essas opções mudam apenas a aparência do app, sem alterar a lógica da IA.
    var espPreviewTelaCheia: Boolean
        get()  = prefs.getBoolean("esp_preview_tela_cheia", true)
        set(v) = prefs.edit().putBoolean("esp_preview_tela_cheia", v).apply()

    var espPreviewRotation: Int
        get()  = prefs.getInt("esp_preview_rotation", 0)
        set(v) = prefs.edit().putInt("esp_preview_rotation", v).apply()

    var mostrarOverlay: Boolean
        get()  = prefs.getBoolean("mostrar_overlay", true)
        set(v) = prefs.edit().putBoolean("mostrar_overlay", v).apply()

    var mostrarPainelSuperior: Boolean
        get()  = prefs.getBoolean("mostrar_painel_superior", true)
        set(v) = prefs.edit().putBoolean("mostrar_painel_superior", v).apply()

    var mostrarPainelInferior: Boolean
        get()  = prefs.getBoolean("mostrar_painel_inferior", true)
        set(v) = prefs.edit().putBoolean("mostrar_painel_inferior", v).apply()

    // ── Estados ──
    companion object {
        const val EST_INATIVO      = "INATIVO"
        const val EST_RECONHECENDO = "RECONHECENDO"
        const val EST_SEMAFORO     = "SEMAFORO"
        const val EST_FAIXA        = "FAIXA"
        const val EST_VIA_LIVRE    = "VIA_LIVRE"
    }

    // ── Helpers ──
    fun categorizarClasse(nome: String): String? {
        val n = nome.lowercase().trim()
        return when {
            n in keywordsVeiculos            -> "veiculo"
            n in keywordsFaixa               -> "faixa"
            n.startsWith(keywordsSemaforoPrefixo) -> "semaforo"
            else                             -> null
        }
    }

    fun inferirCorSemaforo(nome: String): String {
        val n = nome.lowercase()
        return when {
            kwVermelho.any { it in n } -> "VERMELHO"
            kwVerde.any    { it in n } -> "VERDE"
            kwAmarelo.any  { it in n } -> "AMARELO"
            else                       -> "DESCONHECIDO"
        }
    }

    fun tr(key: String): String = LanguageManager.text(appLanguage, key)

    fun trFormat(key: String, vararg args: Any): String =
        tr(key).format(*args)

    fun nomeVeiculo(nomeClasse: String): String {
        val key = chavesVeiculos[nomeClasse.lowercase().trim()] ?: "object_vehicle"
        return tr(key)
    }

    // Mantido para compatibilidade com arquivos antigos.
    fun nomeVeiculoPt(nomeClasse: String): String = nomeVeiculo(nomeClasse)

    fun nomeCorSemaforo(cor: String): String {
        return when (cor) {
            "VERMELHO" -> tr("color_red")
            "VERDE" -> tr("color_green")
            "AMARELO" -> tr("color_yellow")
            "DESCONHECIDO" -> tr("color_unknown")
            "NENHUM" -> tr("color_none")
            else -> cor
        }
    }

    fun sufixoMovimento(classif: String): String {
        return when (classif) {
            "PARADO" -> tr("motion_stopped_short")
            "EM_MOVIMENTO" -> tr("motion_moving_short")
            "APROXIMANDO" -> tr("motion_approaching_short")
            else -> ""
        }
    }

    fun dentroRoi(cx: Float, cy: Float, w: Int, h: Int): Boolean {
        if (!roiAtivo) return true
        return cx >= roiLeft * w && cx <= roiRight * w &&
                cy >= roiTop * h  && cy <= roiBottom * h
    }

    fun calcularAlinhamentoFaixa(faixaCx: Float, wf: Int): Pair<String, Float> {
        val centro = wf / 2f
        val margem = wf * faixaMargemAlinhamento
        val diff   = faixaCx - centro
        return when {
            diff < -margem -> "ESQUERDA" to (Math.abs(diff) / centro)
            diff >  margem -> "DIREITA"  to (Math.abs(diff) / centro)
            else           -> "ALINHADO" to (Math.abs(diff) / centro)
        }
    }

    fun resetarParaPadrao() {
        val idiomaAtual = appLanguage
        prefs.edit().clear().putString("app_language", idiomaAtual).apply()
    }
}
