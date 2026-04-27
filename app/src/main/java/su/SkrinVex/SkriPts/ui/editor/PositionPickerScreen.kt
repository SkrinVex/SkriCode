package su.SkrinVex.SkriPts.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.block.BlockDef
import su.SkrinVex.SkriPts.engine.ExprEval
import su.SkrinVex.SkriPts.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Визуальный позиционировщик объекта.
 * Показывает сцену, объект можно тащить пальцем.
 * При подтверждении возвращает x/y как выражения с $screen* константами.
 */
@Composable
fun PositionPickerScreen(
    objectName: String,
    blockType: String = "sim_create",
    objectWidth: Float,
    objectHeight: Float,
    objectRadius: Float,
    objectColor: Color,
    initialX: Float,
    initialY: Float,
    showOtherObjects: Boolean = false,
    otherBlocks: List<BlockDef> = emptyList(),
    onConfirm: (xExpr: String, yExpr: String) -> Unit,
    onDismiss: () -> Unit
) {
    var objX by remember { mutableFloatStateOf(initialX) }
    var objY by remember { mutableFloatStateOf(initialY) }
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }

    val sw = ExprEval.screenWidth
    val sh = ExprEval.screenHeight

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Захватываем объект если нажали на него
                            val cx = canvasW / 2f; val cy = canvasH / 2f
                            val left = cx + objX - objectWidth / 2f
                            val top  = cy - objY - objectHeight / 2f
                            // Разрешаем drag с любой точки экрана для удобства
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount.x вправо = +X, dragAmount.y вниз = -Y (инвертируем Y)
                            objX += dragAmount.x
                            objY -= dragAmount.y
                        }
                    )
                }
        ) {
            canvasW = size.width
            canvasH = size.height
            val cx = size.width / 2f
            val cy = size.height / 2f

            // Сетка
            val step = 50f
            val gridColor = Color(0x18FFFFFF)
            var gx = cx % step; while (gx < size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 0.5f); gx += step }
            var gy = cy % step; while (gy < size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 0.5f); gy += step }
            drawLine(Color(0x44FFFFFF), Offset(cx, 0f), Offset(cx, size.height), 1f)
            drawLine(Color(0x44FFFFFF), Offset(0f, cy), Offset(size.width, cy), 1f)

            // Другие объекты (полупрозрачные)
            if (showOtherObjects) {
                val emptyVars = emptyMap<String, String>()
                otherBlocks.forEach { b ->
                    val bx = ExprEval.eval(b.params["x"]?.value ?: "0", emptyVars).value.toFloatOrNull() ?: 0f
                    val by = ExprEval.eval(b.params["y"]?.value ?: "0", emptyVars).value.toFloatOrNull() ?: 0f
                    val bw = (b.params["width"]?.value?.toFloatOrNull() ?: 100f).coerceAtLeast(1f)
                    val bh = (b.params["height"]?.value?.toFloatOrNull() ?: 60f).coerceAtLeast(1f)
                    val br = (b.params["radius"]?.value?.toFloatOrNull() ?: 8f).coerceAtLeast(0f)
                    val bc = b.params["color"]?.value?.let { hex ->
                        runCatching {
                            val c = hex.trim().trimStart('#').toLong(16)
                            Color(0xFF000000 or c)
                        }.getOrNull()
                    } ?: Color(0xFF4F8EF7)
                    val bl = cx + bx - bw / 2f
                    val bt = cy - by - bh / 2f
                    drawRoundRect(color = bc.copy(alpha = 0.35f), topLeft = Offset(bl, bt),
                        size = Size(bw, bh), cornerRadius = CornerRadius(br, br))
                    drawRoundRect(color = bc.copy(alpha = 0.6f), topLeft = Offset(bl, bt),
                        size = Size(bw, bh), cornerRadius = CornerRadius(br, br), style = Stroke(1f))
                }
            }

            // Объект
            val left = cx + objX - objectWidth / 2f
            val top  = cy - objY - objectHeight / 2f
            val cr = CornerRadius(objectRadius, objectRadius)

            when (blockType) {
                "sim_joystick" -> {
                    // Джойстик: два круга
                    val baseR = objectWidth / 2f
                    val knobR = baseR * 0.4f
                    val jcx = cx + objX; val jcy = cy - objY
                    drawCircle(color = objectColor.copy(alpha = 0.5f), radius = baseR, center = Offset(jcx, jcy))
                    drawCircle(color = objectColor.copy(alpha = 0.3f), radius = baseR, center = Offset(jcx, jcy), style = Stroke(2f))
                    drawCircle(color = Color(0xFF4F8EF7), radius = knobR, center = Offset(jcx, jcy))
                }
                "sim_text" -> {
                    // Текстовый объект: прозрачный фон с текстом
                    drawRoundRect(color = Color(0x22FFFFFF), topLeft = Offset(left, top),
                        size = Size(objectWidth, objectHeight), cornerRadius = cr)
                    drawRoundRect(color = Color(0xFF00E5FF).copy(0.5f), topLeft = Offset(left, top),
                        size = Size(objectWidth, objectHeight), cornerRadius = cr, style = Stroke(1f))
                    val textSize = (objectHeight * 0.4f).coerceIn(12f, 32f) * density
                    drawContext.canvas.nativeCanvas.drawText(
                        objectName,
                        left + objectWidth / 2f,
                        top + objectHeight / 2f + textSize / 3f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            this.textSize = textSize
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                    )
                }
                else -> {
                    // Обычный прямоугольник
                    if (objectColor != Color.Transparent) {
                        drawRoundRect(color = objectColor, topLeft = Offset(left, top),
                            size = Size(objectWidth, objectHeight), cornerRadius = cr)
                    }
                }
            }
            // Обводка выделения
            val selLeft = if (blockType == "sim_joystick") cx + objX - objectWidth / 2f else left
            val selTop  = if (blockType == "sim_joystick") cy - objY - objectWidth / 2f else top
            val selW = objectWidth; val selH = if (blockType == "sim_joystick") objectWidth else objectHeight
            drawRoundRect(
                color = Color(0xFF00E5FF),
                topLeft = Offset(selLeft - 2f, selTop - 2f),
                size = Size(selW + 4f, selH + 4f),
                cornerRadius = CornerRadius(objectRadius + 2f, objectRadius + 2f),
                style = Stroke(width = 2.5f)
            )
            // Крестик в центре объекта
            val ocx = cx + objX; val ocy = cy - objY
            drawLine(Color(0xFF00E5FF), Offset(ocx - 10f, ocy), Offset(ocx + 10f, ocy), 1.5f)
            drawLine(Color(0xFF00E5FF), Offset(ocx, ocy - 10f), Offset(ocx, ocy + 10f), 1.5f)
        }

        // Координаты — показываем как выражения
        val xExpr = toExpr(objX, sw, isX = true)
        val yExpr = toExpr(objY, sh, isX = false)

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            color = Color(0xCC0A0E1A),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(objectName, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "X: $xExpr   Y: $yExpr",
                    color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Text(
                    "(${objX.roundToInt()}, ${objY.roundToInt()}) px",
                    color = Color(0x88FFFFFF), fontSize = 11.sp
                )
            }
        }

        // Кнопки
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FloatingActionButton(
                onClick = onDismiss,
                containerColor = Color(0xFF2A2F3E)
            ) {
                Icon(Icons.Default.Close, "Отмена", tint = Color.White)
            }
            FloatingActionButton(
                onClick = { onConfirm(xExpr, yExpr) },
                containerColor = Color(0xFF00E5FF)
            ) {
                Icon(Icons.Default.Check, "Применить", tint = Color.Black)
            }
        }
    }
}

/**
 * Конвертирует пиксельную координату в выражение с $screen* константами.
 * Порог: если остаток < 20px — округляем до константы.
 */
fun toExpr(px: Float, screenDim: Float, isX: Boolean): String {
    val half = screenDim / 2f
    val rounded = px.roundToInt()

    val candidates = if (isX) listOf(
        half.roundToInt() to "\$screenRight",
        (-half).roundToInt() to "\$screenLeft"
    ) else listOf(
        half.roundToInt() to "\$screenTop",
        (-half).roundToInt() to "\$screenBottom"
    )

    for ((base, name) in candidates) {
        val diff = rounded - base
        if (abs(diff) < 5) return name
        if (abs(diff) < screenDim * 0.35f) {
            return if (diff > 0) "$name + $diff" else "$name - ${abs(diff)}"
        }
    }
    return rounded.toString()
}
