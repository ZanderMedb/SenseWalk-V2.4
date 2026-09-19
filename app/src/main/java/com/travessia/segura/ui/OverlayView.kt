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

    // IDs de classes de veículos (COCO)
    private val vehicleClassIds = setOf(1, 2, 3, 5, 6, 7) // bicycle, car, motorcycle, bus, train, truck
    private val personClassId = 0

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
    fun clear() {
        this.boxes = emptyList()
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
            det.classId in vehicleClassIds -> {
                if (alertActive) COLOR_APROXIMANDO else COLOR_EM_MOVIMENTO
            }
            det.classId == personClassId -> COLOR_PESSOA
            det.classId == 9 -> COLOR_SEM_VERDE  // traffic light
            det.classId == 11 -> COLOR_SEM_OUTRO // stop sign
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

        if (vw <= 0f || vh <= 0f || boxes.isEmpty()) return

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