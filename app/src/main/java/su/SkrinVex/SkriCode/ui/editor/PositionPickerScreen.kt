package su.SkrinVex.SkriCode.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.engine.ExprEval
import su.SkrinVex.SkriCode.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Визуальный позиционировщик объекта.
 * Показывает сцену, объект можно тащить пальцем.
 * При подтверждении возвращает x/y как выражения с $screen* константами.
 */
@Composable
fun PositionPickerScreen(
    projectId: String = "",
    objectName: String,
    blockType: String = "sim_create",
    objectWidth: Float,
    objectHeight: Float,
    objectRadius: Float,
    objectColor: Color,
    objectSprite: String? = null,
    sprites: List<su.SkrinVex.SkriCode.data.SpriteAsset> = emptyList(),
    initialX: Float,
    initialY: Float,
    showOtherObjects: Boolean = false,
    otherBlocks: List<BlockDef> = emptyList(),
    onConfirm: (xExpr: String, yExpr: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Перехват системной кнопки "Назад", чтобы закрывать позиционировщик и возвращаться к блокам
    BackHandler(onBack = onDismiss)

    var objX by remember { mutableFloatStateOf(initialX) }
    var objY by remember { mutableFloatStateOf(initialY) }
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }

    val sw = ExprEval.screenWidth
    val sh = ExprEval.screenHeight

    // Загружаем bitmap для спрайта объекта
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val objSpriteName = remember(blockType, objectSprite) {
        objectSprite?.ifBlank { null }
    }
    val objBitmap = remember(objSpriteName, projectId) {
        if (projectId.isBlank() || objSpriteName == null) return@remember null
        val asset = sprites.find { it.name == objSpriteName }
        val file = if (asset != null) {
            su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, asset.fileName)
        } else {
            su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$objSpriteName.png")
                ?: su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$objSpriteName.jpg")
        }
        file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }

    // Кэш bitmap для других объектов
    val bitmapCache = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
    LaunchedEffect(projectId) {
        if (projectId.isBlank()) return@LaunchedEffect
        val spritesInBlocks = otherBlocks.mapNotNull { extractSpriteName(it) }.toSet()
        spritesInBlocks.forEach { spriteName ->
            if (!bitmapCache.containsKey(spriteName)) {
                val file = su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.png")
                    ?: su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.jpg")
                val bitmap = file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                if (bitmap != null) bitmapCache[spriteName] = bitmap
            }
        }
    }
    
    LaunchedEffect(otherBlocks) {
        if (projectId.isBlank()) return@LaunchedEffect
        val spritesInBlocks = otherBlocks.mapNotNull { extractSpriteName(it) }.toSet()
        spritesInBlocks.forEach { spriteName ->
            if (!bitmapCache.containsKey(spriteName)) {
                val file = su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.png")
                    ?: su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.jpg")
                val bitmap = file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                if (bitmap != null) bitmapCache[spriteName] = bitmap
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { _ ->
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
                    val bc = (b.params["color"] ?: b.params["baseColor"])?.value?.let { hex ->
                        runCatching {
                            val c = hex.trim().trimStart('#').toLong(16)
                            Color(0xFF000000 or c)
                        }.getOrNull()
                    } ?: Color(0xFF4F8EF7)
                    when (b.type) {
                        "sim_joystick" -> {
                            val baseR = (b.params["baseRadius"]?.value?.toFloatOrNull() ?: 100f).coerceAtLeast(1f)
                            val knobR = baseR * 0.4f
                            val knobColor = b.params["knobColor"]?.value?.let { hex ->
                                runCatching { Color(0xFF000000 or hex.trim().trimStart('#').toLong(16)) }.getOrNull()
                            } ?: Color(0xFF4F8EF7)
                            val jcx = cx + bx; val jcy = cy - by
                            drawCircle(color = bc.copy(alpha = 0.35f), radius = baseR, center = Offset(jcx, jcy))
                            drawCircle(color = bc.copy(alpha = 0.6f), radius = baseR, center = Offset(jcx, jcy), style = Stroke(1f))
                            drawCircle(color = knobColor.copy(alpha = 0.5f), radius = knobR, center = Offset(jcx, jcy))
                        }
                        "sim_text" -> {
                            val bw = (b.params["width"]?.value?.toFloatOrNull() ?: 100f).coerceAtLeast(1f)
                            val bh = (b.params["height"]?.value?.toFloatOrNull() ?: 60f).coerceAtLeast(1f)
                            val br = (b.params["radius"]?.value?.toFloatOrNull() ?: 8f).coerceAtLeast(0f)
                            val bl = cx + bx - bw / 2f; val bt = cy - by - bh / 2f
                            drawRoundRect(color = Color(0x22FFFFFF), topLeft = Offset(bl, bt),
                                size = Size(bw, bh), cornerRadius = CornerRadius(br, br))
                            drawRoundRect(color = bc.copy(alpha = 0.6f), topLeft = Offset(bl, bt),
                                size = Size(bw, bh), cornerRadius = CornerRadius(br, br), style = Stroke(1f))
                            val textSize = (bh * 0.4f).coerceIn(10f, 28f) * density
                            drawContext.canvas.nativeCanvas.drawText(
                                b.params["name"]?.value ?: b.displayName,
                                bl + bw / 2f, bt + bh / 2f + textSize / 3f,
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    this.textSize = textSize
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isAntiAlias = true
                                    alpha = 140
                                }
                            )
                        }
                        else -> {
                            val bw = (b.params["width"]?.value?.toFloatOrNull() ?: 100f).coerceAtLeast(1f)
                            val bh = (b.params["height"]?.value?.toFloatOrNull() ?: 60f).coerceAtLeast(1f)
                            val br = (b.params["radius"]?.value?.toFloatOrNull() ?: 8f).coerceAtLeast(0f)
                            val bl = cx + bx - bw / 2f; val bt = cy - by - bh / 2f
                            val spriteName = extractSpriteName(b)
                            val bitmap = spriteName?.let { bitmapCache[it] }
                            if (bitmap != null) {
                                val paint = android.graphics.Paint().apply { isAntiAlias = true; alpha = 140 }
                                drawContext.canvas.nativeCanvas.drawBitmap(bitmap, null,
                                    android.graphics.RectF(bl, bt, bl + bw, bt + bh), paint)
                            } else {
                                drawRoundRect(color = bc.copy(alpha = 0.35f), topLeft = Offset(bl, bt),
                                    size = Size(bw, bh), cornerRadius = CornerRadius(br, br))
                                drawRoundRect(color = bc.copy(alpha = 0.6f), topLeft = Offset(bl, bt),
                                    size = Size(bw, bh), cornerRadius = CornerRadius(br, br), style = Stroke(1f))
                            }
                        }
                    }
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
                    if (objBitmap != null) {
                        val paint = android.graphics.Paint().apply { isAntiAlias = true }
                        drawContext.canvas.nativeCanvas.drawBitmap(objBitmap, null,
                            android.graphics.RectF(left, top, left + objectWidth, top + objectHeight), paint)
                    } else if (objectColor != Color.Transparent) {
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

        // Координаты — адаптивная компактная плашка сверху
        val xExpr = toExpr(objX, sw, isX = true)
        val yExpr = toExpr(objY, sh, isX = false)

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color(0xEE0A0E1A),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0x3300E5FF)),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка отмены слева
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Отмена",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Центр: Название объекта + Координаты (в 2 строки, эллипсис при необходимости)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = objectName,
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "X: $xExpr  Y: $yExpr",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(${objX.roundToInt()}, ${objY.roundToInt()})",
                            color = Color(0x88FFFFFF),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }

                // Кнопка подтверждения справа
                IconButton(
                    onClick = { onConfirm(xExpr, yExpr) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Применить",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(22.dp)
                    )
                }
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

/**
 * Извлекает имя спрайта из блока, учитывая разные типы блоков.
 */
private fun extractSpriteName(block: BlockDef): String? {
    block.params["sprite"]?.value?.ifBlank { null }?.let { return it }
    block.children["setup"]?.firstOrNull { it.type == "set_texture" }
        ?.params?.get("sprite")?.value?.ifBlank { null }?.let { return it }
    return null
}
