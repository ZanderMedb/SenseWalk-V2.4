package com.travessia.segura.road

object RoadText {
    fun get(language: String, key: String): String {
        val texts = when (key) {
            "yes_word" -> listOf("sim", "yes", "sí")
            "retry_speech" -> listOf("Não entendi. Após o sinal, repita apenas sim ou não.", "Not understood. After the tone, say only yes or no.", "No entendí. Después de la señal, diga solo sí o no.")
            "offline_unavailable" -> listOf("Reconhecimento de voz offline indisponível. Use os botões sim ou não.", "Offline speech recognition unavailable. Use the yes or no buttons.", "Reconocimiento de voz sin conexión no disponible. Use los botones sí o no.")
            "microphone_unavailable" -> listOf("Permita o microfone para responder por voz. Os botões sim e não estão disponíveis.", "Allow microphone access to answer by voice. Yes and no buttons are available.", "Permita el micrófono para responder por voz. Los botones sí y no están disponibles.")
            "AWAITING_CONFIRMATION" -> listOf("Rua detectada. Deseja atravessar? Após o sinal, diga sim ou não.", "Road detected. Do you want to cross? After the tone, say yes or no.", "Calle detectada. ¿Desea cruzar? Después de la señal, diga sí o no.")
            "listening" -> listOf("Microfone aberto. Diga sim ou não.", "Microphone ready. Say yes or no.", "Micrófono abierto. Diga sí o no.")
            "speech_error" -> listOf("Não consegui reconhecer a resposta. Use o botão Sim ou Não.", "Could not recognize the answer. Use Yes or No.", "No pude reconocer la respuesta. Use Sí o No.")
            "STARTING_ANALYSIS" -> listOf("Sim reconhecido. Preparando análise de veículos e semáforos.", "Yes recognized. Preparing vehicle and traffic light analysis.", "Sí reconocido. Preparando análisis de vehículos y semáforos.")
            "yes" -> listOf("Sim, analisar", "Yes, analyze", "Sí, analizar")
            "no" -> listOf("Não", "No", "No")
            "declined" -> listOf("Análise não iniciada. Continuando o monitoramento.", "Analysis not started. Monitoring continues.", "Análisis no iniciado. Continúa el monitoreo.")
            "unheard" -> listOf("Não ouvi um sim ou não claro. Análise não iniciada.", "No clear yes or no heard. Analysis not started.", "No escuché un sí o no claro. Análisis no iniciado.")
            "consent_yes" -> listOf("Sim reconhecido. Preparando análise do ambiente.", "Yes recognized. Preparing scene analysis.", "Sí reconocido. Preparando análisis del entorno.")
            "license" -> listOf("Licença e créditos FFNet", "FFNet license and credits", "Licencia y créditos FFNet")
            "setting" -> listOf("Detecção automática de ruas", "Automatic road detection", "Detección automática de calles")
            "subtitle" -> listOf("Monitoramento visual experimental local. Não autoriza travessias.", "Experimental offline visual monitoring. Does not authorize crossing.", "Monitoreo visual experimental local. No autoriza cruzar.")
            "debug" -> listOf("Modo desenvolvedor: segmentação e métricas", "Developer mode: segmentation and metrics", "Modo desarrollador: segmentación y métricas")
            "seg_fps" -> listOf("Frequência FFNet (FPS)", "FFNet frequency (FPS)", "Frecuencia FFNet (FPS)")
            "yolo_fps" -> listOf("Limite YOLO (FPS)", "YOLO limit (FPS)", "Límite YOLO (FPS)")
            "confirm" -> listOf("Confirmação visual (ms)", "Visual confirmation (ms)", "Confirmación visual (ms)")
            "hold" -> listOf("Histerese de saída (ms)", "Exit hysteresis (ms)", "Histéresis de salida (ms)")
            "cooldown" -> listOf("Intervalo entre ativações (ms)", "Activation cooldown (ms)", "Intervalo de activación (ms)")
            "MONITORING" -> listOf("Monitoramento automático ativo", "Automatic monitoring active", "Monitoreo automático activo")
            "POSSIBLE_CROSSING" -> listOf("Possível travessia identificada", "Possible crossing identified", "Posible cruce identificado")
            "ACTIVE_ANALYSIS" -> listOf("Análise de veículos e semáforos ativa.", "Vehicle and traffic light analysis active.", "Análisis de vehículos y semáforos activo.")
            "ERROR" -> listOf("Erro no monitoramento visual. Use a análise manual.", "Visual monitoring error. Use manual analysis.", "Error de monitoreo visual. Use el análisis manual.")
            "road" -> listOf("Rua detectada", "Road detected", "Calle detectada")
            "sidewalk" -> listOf("Calçada detectada", "Sidewalk detected", "Acera detectada")
            "INCONCLUSIVE" -> listOf("A rua não foi identificada com clareza nesta imagem.", "Road not clearly identified in this image.", "La calle no se identificó claramente en esta imagen.")
            "stale_frame" -> listOf("Imagem atrasada. Aguardando uma imagem atual.", "Delayed image. Waiting for a current frame.", "Imagen atrasada. Esperando una imagen actual.")
            "context_only" -> listOf("Segmentação da rua indisponível neste quadro. Análise de veículos ativa.", "Road segmentation unavailable for this frame. Vehicle analysis active.", "Segmentación de calle no disponible en este cuadro. Análisis de vehículos activo.")
            else -> listOf("Não foi possível confirmar as condições da via.", "Could not confirm road conditions.", "No fue posible confirmar las condiciones de la vía.")
        }
        return texts[when (language) { "en" -> 1; "es" -> 2; else -> 0 }]
    }
}
