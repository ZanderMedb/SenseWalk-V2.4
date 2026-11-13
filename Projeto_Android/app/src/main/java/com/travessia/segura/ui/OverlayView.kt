package com.travessia.segura.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.travessia.segura.detection.RawDetection

/**
 * View transparente que desenha bounding boxes por cima da câmera.
 * Recebe detecções do FrameAnalyzer e transforma as coordenadas
 * do sensor (landscape) para o display (portrait), replicando
 * o comportamento FILL_CENTER do PreviewView.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Data class para cada caixa a desenhar ──
    data class BoxInfo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val label: String,
        val confidence: Float,
        val color: Int,
        val isGhost: Boolean = false
    )

    // ── Estado ──
    private var boxes: List<BoxInfo> = emptyList()
    private var frameWidth = 1f
    private var frameHeight = 1f
    private var sensorRotation = 90
    private var roadMask: Bitmap? = null
    private var roadWidth = 1
    private var roadHeight = 1
    private var roadMetrics = ""

    /** Chamado na UI. Máscara de rótulos, sem interpolar IDs de classes. */
    fun updateRoad(frame: com.travessia.segura.road.RoadDebugFrame) {
        roadMask?.recycle(); roadMask = null
        frame.labels?.let { labels ->
            val colors = IntArray(labels.size) { i -> when (labels[i].toInt()) {
                0 -> 0x668000FF; 1 -> 0x6600DDFF; else -> Color.TRANSPARENT
            } }
            roadMask = Bitmap.createBitmap(colors, 256, 128, Bitmap.Config.ARGB_8888)
        }
        roadWidth = frame.frameWidth; roadHeight = frame.frameHeight
        roadMetrics = if (frame.labels != null) "${frame.state} ${frame.reason} FFNet=${frame.inferenceMs}ms frame=${frame.totalMs}ms skip=${frame.dropped} " +
            "FPS cb/FF/Y: %.1f/%.1f/%.1f".format(frame.callbackFps,frame.segmentationFps,frame.yoloFps) else ""
        invalidate()
    }

    // ── Density para converter dp → px ──
    private val dp = context.resources.displayMetrics.density

    // ── Paints reutilizáveis ──
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        pathEffect = DashPathEffect(floatArrayOf(12f * dp, 8f * dp), 0f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * dp
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(2f * dp, 1f, 1f, Color.BLACK)
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // RectF reutilizável
    private val tempRect = RectF()
    private val labelRect = RectF()

    // O modelo do app NÃO usa índices COCO; a classe 0 é car, não person.

    // ══════════════════════════════════════════
    //  API PÚBLICA
    // ══════════════════════════════════════════

    /**
     * Atualiza as detecções a serem desenhadas (usando BoxInfo diretamente).
     * Pode ser chamado de qualquer thread (usa postInvalidate).
     */
    fun updateDetections(
        newBoxes: List<BoxInfo>,
        frameW: Int,
        frameH: Int,
        rotation: Int
    ) {
        this.boxes = newBoxes
        this.frameWidth = frameW.toFloat()
        this.frameHeight = frameH.toFloat()
        this.sensorRotation = rotation
        postInvalidate()
    }

    /**
     * Atualiza usando RawDetection diretamente (conveniência).
     * Converte automaticamente para BoxInfo com cores apropriadas.
     *
     * @param detections Lista de detecções brutas do YoloDetector
     * @param frameW Largura do frame original (antes de rotação)
     * @param frameH Altura do frame original (antes de rotação)
     * @param rotation Rotação do sensor em graus (0, 90, 180, 270)
     * @param vehicleAlertActive Se true, veículos ficam com cor de alerta mais intensa
     */
    fun setDetections(
        detections: List<RawDetection>,
        frameW: Int,
        frameH: Int,
        rotation: Int,
        vehicleAlertActive: Boolean = false
    ) {
        this.boxes = detections.map { det ->
            BoxInfo(
                x1 = det.x1,
                y1 = det.y1,
                x2 = det.x2,
                y2 = det.y2,
                label = det.className,
                confidence = det.confidence,
                color = getColorForDetection(det, vehicleAlertActive),
                isGhost = false
            )
        }
        this.frameWidth = frameW.toFloat()
        this.frameHeight = frameH.toFloat()
        this.sensorRotation = rotation
        postInvalidate()
    }

    /**
     * Limpa todas as caixas da tela.
     */
    // ══════════════════════════════════════════
    //  DEPURAÇÃO DO GIRO (ponto + rastro)
    // ══════════════════════════════════════════
    private data class YawPoint(val yaw: Float?, val tMs: Long, val covered: Boolean)
    private val yawTrail = ArrayDeque<YawPoint>()
    private var yawLabel = ""
    private var yawLateralMin = 45f; private var yawLateralMax = 135f; private var yawFrontTol = 20f
    private val yawPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Giro relativo à frente (graus; direita positiva). null = desconhecido. Mostrado só no modo desenvolvedor. */
    fun updateYawDebug(yawDeg: Float?, covered: Boolean, label: String, lateralMin: Float, lateralMax: Float, frontTol: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        yawTrail.addLast(YawPoint(yawDeg, now, covered))
        while (yawTrail.isNotEmpty() && now - yawTrail.first().tMs > 6000) yawTrail.removeFirst()
        yawLabel = label; yawLateralMin = lateralMin; yawLateralMax = lateralMax; yawFrontTol = frontTol
        postInvalidate()
    }
    fun clearYawDebug() { yawTrail.clear(); yawLabel = ""; postInvalidate() }

    /** Etiqueta "para onde estou olhando" (sempre visível na análise). Verde = bate com a direção pedida. */
    private var lookTag = ""
    private var lookTagMatches = false
    fun updateLookTag(text: String, matchesRequested: Boolean) {
        if (text == lookTag && matchesRequested == lookTagMatches) return
        lookTag = text; lookTagMatches = matchesRequested; postInvalidate()
    }
    private val lookPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 44f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val lookBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun drawLookTag(canvas: Canvas, vw: Float, vh: Float) {
        if (lookTag.isEmpty()) return
        val y = vh * .30f
        val w = lookPaint.measureText(lookTag) + 48f
        lookBgPaint.color = if (lookTagMatches) Color.argb(200, 46, 125, 50) else Color.argb(200, 33, 33, 33)
        canvas.drawRoundRect(vw / 2 - w / 2, y - 50f, vw / 2 + w / 2, y + 16f, 18f, 18f, lookBgPaint)
        lookPaint.color = if (lookTagMatches) Color.WHITE else Color.rgb(255, 213, 79)
        canvas.drawText(lookTag, vw / 2, y, lookPaint)
    }

    private fun drawYawDebug(canvas: Canvas, vw: Float, vh: Float) {
        if (yawTrail.isEmpty()) return
        val left = vw * .08f; val right = vw * .92f; val barY = vh * .16f
        fun x(yaw: Float) = left + (yaw.coerceIn(-150f, 150f) + 150f) / 300f * (right - left)
        // Faixas-alvo: esquerda (azul), frente (branco), direita (azul).
        yawPaint.style = Paint.Style.FILL
        yawPaint.color = Color.argb(70, 33, 150, 243)
        canvas.drawRect(x(-yawLateralMax), barY - 14f, x(-yawLateralMin), barY + 14f, yawPaint)
        canvas.drawRect(x(yawLateralMin), barY - 14f, x(yawLateralMax), barY + 14f, yawPaint)
        yawPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(x(-yawFrontTol), barY - 14f, x(yawFrontTol), barY + 14f, yawPaint)
        yawPaint.color = Color.argb(160, 255, 255, 255); yawPaint.strokeWidth = 2f
        canvas.drawLine(left, barY, right, barY, yawPaint)
        textPaint.textSize = 26f
        canvas.drawText("E", x(-90f) - 8f, barY - 20f, textPaint)
        canvas.drawText("F", x(0f) - 8f, barY - 20f, textPaint)
        canvas.drawText("D", x(90f) - 8f, barY - 20f, textPaint)
        // Rastro: o mais novo na barra, os mais antigos escorrendo para baixo ("pinta" o caminho do giro).
        val now = android.os.SystemClock.elapsedRealtime()
        var prev: Pair<Float, Float>? = null
        for (p in yawTrail) {
            val age = (now - p.tMs) / 6000f
            val y = barY + age * vh * .5f
            if (p.yaw == null) { prev = null; continue }
            val px = x(p.yaw)
            yawPaint.color = if (p.covered) Color.argb((220 * (1 - age)).toInt().coerceAtLeast(30), 76, 175, 80)
                else Color.argb((220 * (1 - age)).toInt().coerceAtLeast(30), 255, 193, 7)
            prev?.let { canvas.drawLine(it.first, it.second, px, y, yawPaint.apply { strokeWidth = 5f }) }
            prev = px to y
        }
        val last = yawTrail.last()
        yawPaint.color = when { last.yaw == null -> Color.GRAY; last.covered -> Color.rgb(76, 175, 80); else -> Color.rgb(255, 193, 7) }
        canvas.drawCircle(x(last.yaw ?: 0f), barY, 16f, yawPaint)
        canvas.drawText(yawLabel, left, barY + 44f, textPaint)
    }

    fun clear() {
        this.boxes = emptyList()
        roadMask?.recycle(); roadMask = null; roadMetrics = ""
        postInvalidate()
    }

    // ══════════════════════════════════════════
    //  CORES AUTOMÁTICAS
    // ══════════════════════════════════════════

    /**
     * Determina a cor da bounding box com base no tipo de objeto.
     */
    private fun getColorForDetection(det: RawDetection, alertActive: Boolean): Int {
        return when {
            det.className in setOf("car","motorcycle","bicycle","truck","bus") -> {
                if (alertActive) COLOR_APROXIMANDO else COLOR_EM_MOVIMENTO
            }
            det.className == "crosswalk" -> COLOR_FAIXA
            det.className == "pedestrian-light-green" -> COLOR_SEM_VERDE
            det.className == "pedestrian-light-red" -> COLOR_SEM_VERMELHO
            det.className.startsWith("traffic-light-") -> COLOR_SEM_OUTRO
            else -> COLOR_OUTROS
        }
    }

    // ══════════════════════════════════════════
    //  DESENHO
    // ══════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vw = width.toFloat()
        val vh = height.toFloat()

        if (vw <= 0f || vh <= 0f) return
        drawYawDebug(canvas, vw, vh)
        drawLookTag(canvas, vw, vh)
        roadMask?.let { mask ->
            val scale = maxOf(vw / roadWidth, vh / roadHeight)
            val w = roadWidth * scale; val h = roadHeight * scale
            canvas.drawBitmap(mask, null, RectF((vw-w)/2, (vh-h)/2, (vw+w)/2, (vh+h)/2), null)
            canvas.drawText(roadMetrics, 10f * dp, vh - 100f * dp, textPaint)
        }

        for (box in boxes) {
            // Transformar coordenadas do sensor → view
            transformToView(box.x1, box.y1, box.x2, box.y2, vw, vh, tempRect)

            // Pular boxes muito pequenas (ruído)
            if (tempRect.width() < 4f || tempRect.height() < 4f) continue

            // ── Preenchimento semitransparente ──
            fillPaint.color = (box.color and 0x00FFFFFF) or 0x1A000000
            canvas.drawRoundRect(tempRect, 4f * dp, 4f * dp, fillPaint)

            // ── Borda ──
            val borderPaint = if (box.isGhost) {
                ghostPaint.apply { color = (box.color and 0x00FFFFFF) or 0x99000000.toInt() }
            } else {
                boxPaint.apply { color = box.color }
            }
            canvas.drawRoundRect(tempRect, 4f * dp, 4f * dp, borderPaint)

            // ── Label de texto ──
            val labelText = "${box.label} ${(box.confidence * 100).toInt()}%"
            val textW = textPaint.measureText(labelText)
            val textH = textPaint.textSize
            val pad = 4f * dp

            // Fundo do label (acima da box)
            labelRect.set(
                tempRect.left,
                tempRect.top - textH - pad * 2,
                tempRect.left + textW + pad * 2,
                tempRect.top
            )

            // Se label sairia da tela por cima, colocar dentro da box
            if (labelRect.top < 0) {
                labelRect.offset(0f, -labelRect.top + 2f)
            }

            textBgPaint.color = (box.color and 0x00FFFFFF) or 0xCC000000.toInt()
            canvas.drawRoundRect(labelRect, 3f * dp, 3f * dp, textBgPaint)

            // Texto
            canvas.drawText(
                labelText,
                labelRect.left + pad,
                labelRect.bottom - pad,
                textPaint
            )
        }
    }

    // ══════════════════════════════════════════
    //  TRANSFORMAÇÃO DE COORDENADAS
    // ══════════════════════════════════════════

    /**
     * Transforma coordenadas do sensor (ex: 640x480 landscape)
     * para coordenadas da View (portrait), replicando o
     * comportamento FILL_CENTER do PreviewView.
     */
    private fun transformToView(
        x1: Float, y1: Float, x2: Float, y2: Float,
        viewW: Float, viewH: Float,
        outRect: RectF
    ) {
        // PASSO 1: Rotacionar coordenadas do sensor
        val rx1: Float; val ry1: Float
        val rx2: Float; val ry2: Float
        val rotW: Float; val rotH: Float

        when (sensorRotation) {
            90 -> {
                // 90° CW: ponto (x,y) → (H-y, x) no espaço (H x W)
                rx1 = frameHeight - y2
                ry1 = x1
                rx2 = frameHeight - y1
                ry2 = x2
                rotW = frameHeight
                rotH = frameWidth
            }
            180 -> {
                rx1 = frameWidth - x2
                ry1 = frameHeight - y2
                rx2 = frameWidth - x1
                ry2 = frameHeight - y1
                rotW = frameWidth
                rotH = frameHeight
            }
            270 -> {
                rx1 = y1
                ry1 = frameWidth - x2
                rx2 = y2
                ry2 = frameWidth - x1
                rotW = frameHeight
                rotH = frameWidth
            }
            else -> {
                rx1 = x1; ry1 = y1
                rx2 = x2; ry2 = y2
                rotW = frameWidth
                rotH = frameHeight
            }
        }

        // PASSO 2: Escala FILL_CENTER (igual ao PreviewView)
        val scaleX = viewW / rotW
        val scaleY = viewH / rotH
        val scale = maxOf(scaleX, scaleY)

        val displayW = rotW * scale
        val displayH = rotH * scale
        val offsetX = (viewW - displayW) / 2f
        val offsetY = (viewH - displayH) / 2f

        outRect.set(
            (rx1 * scale + offsetX).coerceIn(0f, viewW),
            (ry1 * scale + offsetY).coerceIn(0f, viewH),
            (rx2 * scale + offsetX).coerceIn(0f, viewW),
            (ry2 * scale + offsetY).coerceIn(0f, viewH)
        )
    }

    // ══════════════════════════════════════════
    //  CORES PADRÃO
    // ══════════════════════════════════════════

    companion object {
        val COLOR_PARADO = Color.rgb(76, 175, 80)           // Verde
        val COLOR_EM_MOVIMENTO = Color.rgb(255, 193, 7)     // Amarelo
        val COLOR_APROXIMANDO = Color.rgb(244, 67, 54)      // Vermelho
        val COLOR_PESSOA = Color.rgb(255, 152, 0)           // Laranja
        val COLOR_FAIXA = Color.rgb(33, 150, 243)           // Azul
        val COLOR_OUTROS = Color.rgb(100, 181, 246)         // Azul claro
        val COLOR_SEM_VERMELHO = Color.rgb(244, 67, 54)     // Vermelho
        val COLOR_SEM_VERDE = Color.rgb(76, 175, 80)        // Verde
        val COLOR_SEM_AMARELO = Color.rgb(255, 235, 59)     // Amarelo
        val COLOR_SEM_OUTRO = Color.rgb(156, 39, 176)       // Roxo
        val COLOR_GHOST = Color.rgb(158, 158, 158)          // Cinza
    }
}
