package su.SkrinVex.SkriCode.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import su.SkrinVex.SkriCode.data.SpriteAsset
import su.SkrinVex.SkriCode.data.SpriteRepository
import su.SkrinVex.SkriCode.ui.theme.*

/**
 * Полноэкранный визуальный редактор анимации спрайт-листов.
 * Поддерживает нарезку сетки, смещения (Offset X/Y), межпокадровые отступы (Spacing X/Y),
 * выбор диапазонов, FPS, зацикливание и интерактивную карту с живым предпросмотром.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpriteAnimationEditorScreen(
    initialSprite: String,
    initialCols: Int = 4,
    initialRows: Int = 1,
    initialStartFrame: Int = 0,
    initialEndFrame: Int = 0,
    initialFps: Float = 12f,
    initialLoop: Boolean = true,
    initialOffsetX: Int = 0,
    initialOffsetY: Int = 0,
    initialSpacingX: Int = 0,
    initialSpacingY: Int = 0,
    initialFrameW: Int = 0,
    initialFrameH: Int = 0,
    sprites: List<SpriteAsset>,
    projectId: String,
    onConfirm: (
        sprite: String,
        cols: Int,
        rows: Int,
        startFrame: Int,
        endFrame: Int,
        fps: Float,
        loop: Boolean,
        offsetX: Int,
        offsetY: Int,
        spacingX: Int,
        spacingY: Int,
        frameW: Int,
        frameH: Int
    ) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val ctx = LocalContext.current

    var selectedSpriteName by remember {
        mutableStateOf(if (initialSprite.isNotBlank() && sprites.any { it.name == initialSprite }) initialSprite else (sprites.firstOrNull()?.name ?: ""))
    }
    var cols by remember { mutableIntStateOf(initialCols.coerceAtLeast(1)) }
    var rows by remember { mutableIntStateOf(initialRows.coerceAtLeast(1)) }
    var startFrame by remember { mutableIntStateOf(initialStartFrame.coerceAtLeast(0)) }
    var endFrame by remember { mutableIntStateOf(initialEndFrame.coerceAtLeast(0)) }
    var fps by remember { mutableFloatStateOf(initialFps.coerceIn(1f, 60f)) }
    var loop by remember { mutableStateOf(initialLoop) }

    // Смещение и отступы сетки
    var offsetX by remember { mutableIntStateOf(initialOffsetX.coerceAtLeast(0)) }
    var offsetY by remember { mutableIntStateOf(initialOffsetY.coerceAtLeast(0)) }
    var spacingX by remember { mutableIntStateOf(initialSpacingX.coerceAtLeast(0)) }
    var spacingY by remember { mutableIntStateOf(initialSpacingY.coerceAtLeast(0)) }
    var frameWCustom by remember { mutableIntStateOf(initialFrameW.coerceAtLeast(0)) }
    var frameHCustom by remember { mutableIntStateOf(initialFrameH.coerceAtLeast(0)) }

    var isPlaying by remember { mutableStateOf(true) }
    var currentFrameIndex by remember { mutableIntStateOf(startFrame) }

    // Загрузка Bitmap выбранного спрайта
    val bitmap: Bitmap? = remember(selectedSpriteName, projectId) {
        val asset = sprites.find { it.name == selectedSpriteName }
        if (asset != null) {
            val file = SpriteRepository.getFile(ctx, projectId, asset.fileName)
            file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        } else null
    }

    val totalFrames = (cols * rows).coerceAtLeast(1)
    val effectiveEndFrame = if (endFrame > 0 && endFrame in startFrame until totalFrames) endFrame else (totalFrames - 1)
    val activeFrameCount = (effectiveEndFrame - startFrame + 1).coerceAtLeast(1)

    // Вычисляемые размеры отдельного кадра
    val computedFrameW = remember(bitmap, cols, offsetX, spacingX, frameWCustom) {
        if (frameWCustom > 0) frameWCustom
        else if (bitmap != null) {
            ((bitmap.width - offsetX - (cols - 1) * spacingX) / cols).coerceAtLeast(1)
        } else 32
    }
    val computedFrameH = remember(bitmap, rows, offsetY, spacingY, frameHCustom) {
        if (frameHCustom > 0) frameHCustom
        else if (bitmap != null) {
            ((bitmap.height - offsetY - (rows - 1) * spacingY) / rows).coerceAtLeast(1)
        } else 32
    }

    // Корректируем границы если сетка изменилась
    LaunchedEffect(cols, rows) {
        val maxF = (cols * rows) - 1
        if (startFrame > maxF) startFrame = maxF
        if (endFrame > maxF) endFrame = 0
    }

    // Живой цикл воспроизведения
    LaunchedEffect(isPlaying, fps, startFrame, effectiveEndFrame, activeFrameCount, loop) {
        if (!isPlaying || activeFrameCount <= 1) return@LaunchedEffect
        currentFrameIndex = startFrame
        val frameDelayMs = (1000f / fps).toLong().coerceAtLeast(16L)

        while (isActive && isPlaying) {
            delay(frameDelayMs)
            var next = currentFrameIndex + 1
            if (next > effectiveEndFrame) {
                if (loop) {
                    next = startFrame
                } else {
                    currentFrameIndex = effectiveEndFrame
                    isPlaying = false
                    break
                }
            }
            currentFrameIndex = next
        }
    }

    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Navy900)
    ) {
        // --- Верхняя панель ---
        Surface(color = Surface1, shadowElevation = 4.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                }
                Column(Modifier.weight(1f)) {
                    Text("Редактор анимации спрайт-листа", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Нарезка сетки, смещения и предпросмотр", color = TextSec, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        onConfirm(
                            selectedSpriteName,
                            cols,
                            rows,
                            startFrame,
                            endFrame,
                            fps,
                            loop,
                            offsetX,
                            offsetY,
                            spacingX,
                            spacingY,
                            frameWCustom,
                            frameHCustom
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = Navy900)
                    Spacer(Modifier.width(6.dp))
                    Text("Применить", color = Navy900, fontWeight = FontWeight.Bold)
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Выбор спрайта (если несколько) ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface2),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Спрайт из проекта", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (sprites.isEmpty()) {
                        Text("В проекте нет загруженных спрайтов", color = Danger, fontSize = 13.sp)
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrim)
                            ) {
                                Icon(Icons.Default.Image, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (selectedSpriteName.isNotBlank()) selectedSpriteName else "Выберите спрайт",
                                    color = TextPrim,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSec)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.background(Surface3)
                            ) {
                                sprites.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(s.name, color = TextPrim, fontWeight = FontWeight.Medium)
                                                if (s.width > 0) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("${s.width}×${s.height}", color = TextSec, fontSize = 11.sp)
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedSpriteName = s.name
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- Блок живого предпросмотра ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface2),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Movie, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Text("Предпросмотр анимации", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Surface(
                            color = if (isPlaying) Accent.copy(alpha = 0.2f) else Surface3,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    null,
                                    tint = if (isPlaying) Accent else TextSec,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    if (isPlaying) "ИГРАЕТ" else "ПАУЗА",
                                    color = if (isPlaying) Accent else TextSec,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Окно анимации (шахматный фон для прозрачности)
                    Box(
                        Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF141A29))
                            .border(1.dp, Surface3, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Шахматка
                        Canvas(Modifier.fillMaxSize()) {
                            val checkSize = 16f
                            val numX = (size.width / checkSize).toInt() + 1
                            val numY = (size.height / checkSize).toInt() + 1
                            for (ix in 0..numX) {
                                for (iy in 0..numY) {
                                    if ((ix + iy) % 2 == 0) {
                                        drawRect(
                                            color = Color(0xFF1B2338),
                                            topLeft = Offset(ix * checkSize, iy * checkSize),
                                            size = Size(checkSize, checkSize)
                                        )
                                    }
                                }
                            }
                        }

                        // Отрисовка текущего кадра
                        if (bitmap != null) {
                            val cur = currentFrameIndex.coerceIn(0, totalFrames - 1)
                            val col = cur % cols
                            val row = cur / cols
                            val leftCrop = (offsetX + col * (computedFrameW + spacingX)).coerceIn(0, bitmap.width)
                            val topCrop = (offsetY + row * (computedFrameH + spacingY)).coerceIn(0, bitmap.height)
                            val rightCrop = (leftCrop + computedFrameW).coerceIn(0, bitmap.width)
                            val bottomCrop = (topCrop + computedFrameH).coerceIn(0, bitmap.height)

                            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                                if (rightCrop > leftCrop && bottomCrop > topCrop) {
                                    val srcRect = Rect(leftCrop, topCrop, rightCrop, bottomCrop)
                                    val curW = (rightCrop - leftCrop).toFloat()
                                    val curH = (bottomCrop - topCrop).toFloat()
                                    val scale = minOf(size.width / curW, size.height / curH)
                                    val drawW = curW * scale
                                    val drawH = curH * scale
                                    val dstLeft = (size.width - drawW) / 2f
                                    val dstTop = (size.height - drawH) / 2f
                                    val dstRect = RectF(dstLeft, dstTop, dstLeft + drawW, dstTop + drawH)

                                    val p = android.graphics.Paint().apply {
                                        isAntiAlias = true
                                        isFilterBitmap = true
                                    }
                                    drawContext.canvas.nativeCanvas.drawBitmap(bitmap, srcRect, dstRect, p)
                                }
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.ImageNotSupported, null, tint = TextSec, modifier = Modifier.size(36.dp))
                                Text("Нет изображения", color = TextSec, fontSize = 12.sp)
                            }
                        }
                    }

                    // Бейджи информации о кадре
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(color = Surface3, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "Кадр: $currentFrameIndex / ${totalFrames - 1}",
                                color = TextPrim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Surface(color = Surface3, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "Сетка: ${cols}×${rows} ($totalFrames кадр.)",
                                color = TextPrim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Surface(color = Surface3, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                "${computedFrameW}×${computedFrameH} px",
                                color = TextPrim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Кнопки управления плеером
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Сброс на начало
                        IconButton(
                            onClick = {
                                currentFrameIndex = startFrame
                            },
                            modifier = Modifier.size(40.dp).background(Surface3, CircleShape)
                        ) {
                            Icon(Icons.Default.Replay, "В начало", tint = TextPrim, modifier = Modifier.size(20.dp))
                        }

                        // Шаг назад
                        IconButton(
                            onClick = {
                                isPlaying = false
                                var prev = currentFrameIndex - 1
                                if (prev < startFrame) prev = effectiveEndFrame
                                currentFrameIndex = prev
                            },
                            modifier = Modifier.size(40.dp).background(Surface3, CircleShape)
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Шаг назад", tint = TextPrim, modifier = Modifier.size(20.dp))
                        }

                        // Play / Pause
                        FilledIconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.size(52.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "Пауза" else "Старт",
                                tint = Navy900,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Шаг вперед
                        IconButton(
                            onClick = {
                                isPlaying = false
                                var next = currentFrameIndex + 1
                                if (next > effectiveEndFrame) next = startFrame
                                currentFrameIndex = next
                            },
                            modifier = Modifier.size(40.dp).background(Surface3, CircleShape)
                        ) {
                            Icon(Icons.Default.SkipNext, "Шаг вперед", tint = TextPrim, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // --- Интерактивная карта спрайт-листа ---
            if (bitmap != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface2),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.GridOn, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Text("Интерактивная карта сетки", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text("Клик по кадру для выбора", color = TextSec, fontSize = 11.sp)
                        }

                        // Canvas с полной сеткой
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F1420))
                                .border(1.dp, Surface3, RoundedCornerShape(8.dp))
                        ) {
                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(cols, rows, startFrame, effectiveEndFrame, bitmap, offsetX, offsetY, spacingX, spacingY, computedFrameW, computedFrameH) {
                                        detectTapGestures { offset ->
                                            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                                            val drawW = bitmap.width * scale
                                            val drawH = bitmap.height * scale
                                            val leftOffset = (size.width - drawW) / 2f
                                            val topOffset = (size.height - drawH) / 2f

                                            if (offset.x >= leftOffset && offset.x <= leftOffset + drawW &&
                                                offset.y >= topOffset && offset.y <= topOffset + drawH
                                            ) {
                                                val bmpX = (offset.x - leftOffset) / scale
                                                val bmpY = (offset.y - topOffset) / scale

                                                val relX = bmpX - offsetX
                                                val relY = bmpY - offsetY

                                                val stepX = computedFrameW + spacingX
                                                val stepY = computedFrameH + spacingY

                                                val col = (relX / stepX).toInt().coerceIn(0, cols - 1)
                                                val row = (relY / stepY).toInt().coerceIn(0, rows - 1)
                                                val clickedFrame = row * cols + col

                                                if (clickedFrame < startFrame) {
                                                    startFrame = clickedFrame
                                                } else if (clickedFrame == startFrame) {
                                                    endFrame = 0
                                                } else {
                                                    endFrame = clickedFrame
                                                }
                                                currentFrameIndex = clickedFrame
                                            }
                                        }
                                    }
                            ) {
                                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                                val drawW = bitmap.width * scale
                                val drawH = bitmap.height * scale
                                val leftOffset = (size.width - drawW) / 2f
                                val topOffset = (size.height - drawH) / 2f

                                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                                val dstRect = RectF(leftOffset, topOffset, leftOffset + drawW, topOffset + drawH)
                                val p = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    isFilterBitmap = true
                                }
                                drawContext.canvas.nativeCanvas.drawBitmap(bitmap, srcRect, dstRect, p)

                                val cellW = computedFrameW * scale
                                val cellH = computedFrameH * scale
                                val stepW = (computedFrameW + spacingX) * scale
                                val stepH = (computedFrameH + spacingY) * scale
                                val gridLeft = leftOffset + offsetX * scale
                                val gridTop = topOffset + offsetY * scale

                                val textPaint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    textSize = (minOf(cellW, cellH) * 0.35f).coerceIn(16f, 32f)
                                    color = android.graphics.Color.WHITE
                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                                }

                                // Рисуем ячейки, подсветку и номера кадров
                                for (r in 0 until rows) {
                                    for (c in 0 until cols) {
                                        val fIndex = r * cols + c
                                        val cLeft = gridLeft + c * stepW
                                        val cTop = gridTop + r * stepH
                                        val isActiveRange = fIndex in startFrame..effectiveEndFrame
                                        val isCurrent = fIndex == currentFrameIndex

                                        // Затемнение кадров вне диапазона
                                        if (!isActiveRange) {
                                            drawRect(
                                                color = Color.Black.copy(alpha = 0.6f),
                                                topLeft = Offset(cLeft, cTop),
                                                size = Size(cellW, cellH)
                                            )
                                        }

                                        // Подсветка активного диапазона
                                        if (isActiveRange) {
                                            drawRect(
                                                color = Accent.copy(alpha = 0.15f),
                                                topLeft = Offset(cLeft, cTop),
                                                size = Size(cellW, cellH)
                                            )
                                            drawRect(
                                                color = Accent.copy(alpha = 0.6f),
                                                topLeft = Offset(cLeft, cTop),
                                                size = Size(cellW, cellH),
                                                style = Stroke(width = 1.5f)
                                            )
                                        }

                                        // Подсветка текущего кадра
                                        if (isCurrent) {
                                            drawRect(
                                                color = Warning.copy(alpha = 0.3f),
                                                topLeft = Offset(cLeft, cTop),
                                                size = Size(cellW, cellH)
                                            )
                                            drawRect(
                                                color = Warning,
                                                topLeft = Offset(cLeft + 1f, cTop + 1f),
                                                size = Size(cellW - 2f, cellH - 2f),
                                                style = Stroke(width = 3f)
                                            )
                                        }

                                        // Линии сетки
                                        drawRect(
                                            color = Color.White.copy(alpha = 0.35f),
                                            topLeft = Offset(cLeft, cTop),
                                            size = Size(cellW, cellH),
                                            style = Stroke(width = 1f)
                                        )

                                        // Номер кадра в ячейке
                                        val text = fIndex.toString()
                                        drawContext.canvas.nativeCanvas.drawText(
                                            text,
                                            cLeft + 6f,
                                            cTop + textPaint.textSize + 2f,
                                            textPaint
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- Настройка сетки (Столбцы / Строки) ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface2),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.GridOn, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Text("Сетка спрайт-листа", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Столбцы
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Столбцов (в ширину)", color = TextSec, fontSize = 12.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilledIconButton(
                                    onClick = { if (cols > 1) cols-- },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Remove, "-", tint = TextPrim)
                                }
                                Surface(
                                    color = Surface3,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        "$cols",
                                        color = TextPrim,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                FilledIconButton(
                                    onClick = { if (cols < 32) cols++ },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Add, "+", tint = TextPrim)
                                }
                            }
                        }

                        // Строки
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Строк (в высоту)", color = TextSec, fontSize = 12.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilledIconButton(
                                    onClick = { if (rows > 1) rows-- },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Remove, "-", tint = TextPrim)
                                }
                                Surface(
                                    color = Surface3,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        "$rows",
                                        color = TextPrim,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                FilledIconButton(
                                    onClick = { if (rows < 32) rows++ },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Add, "+", tint = TextPrim)
                                }
                            }
                        }
                    }
                }
            }

            // --- Настройка смещения (Offset) и отступов (Spacing) ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface2),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Tune, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Text("Смещение и отступы сетки", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (offsetX > 0 || offsetY > 0 || spacingX > 0 || spacingY > 0 || frameWCustom > 0 || frameHCustom > 0) {
                            TextButton(
                                onClick = {
                                    offsetX = 0
                                    offsetY = 0
                                    spacingX = 0
                                    spacingY = 0
                                    frameWCustom = 0
                                    frameHCustom = 0
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Сбросить", color = Warning, fontSize = 12.sp)
                            }
                        }
                    }

                    // Смещение X и Y
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Смещение X
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Смещение X:", color = TextSec, fontSize = 12.sp)
                                Text("${offsetX} px", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledIconButton(
                                    onClick = { if (offsetX > 0) offsetX-- },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                                Slider(
                                    value = offsetX.toFloat(),
                                    onValueChange = { offsetX = it.toInt() },
                                    valueRange = 0f..(bitmap?.width?.toFloat() ?: 200f),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                                )
                                FilledIconButton(
                                    onClick = { offsetX++ },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        // Смещение Y
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Смещение Y:", color = TextSec, fontSize = 12.sp)
                                Text("${offsetY} px", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledIconButton(
                                    onClick = { if (offsetY > 0) offsetY-- },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                                Slider(
                                    value = offsetY.toFloat(),
                                    onValueChange = { offsetY = it.toInt() },
                                    valueRange = 0f..(bitmap?.height?.toFloat() ?: 200f),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                                )
                                FilledIconButton(
                                    onClick = { offsetY++ },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Отступ X и Y между кадрами (Spacing)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Spacing X
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Отступ между X:", color = TextSec, fontSize = 12.sp)
                                Text("${spacingX} px", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledIconButton(
                                    onClick = { if (spacingX > 0) spacingX-- },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                                Slider(
                                    value = spacingX.toFloat(),
                                    onValueChange = { spacingX = it.toInt() },
                                    valueRange = 0f..64f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                                )
                                FilledIconButton(
                                    onClick = { spacingX++ },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        // Spacing Y
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Отступ между Y:", color = TextSec, fontSize = 12.sp)
                                Text("${spacingY} px", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledIconButton(
                                    onClick = { if (spacingY > 0) spacingY-- },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                                Slider(
                                    value = spacingY.toFloat(),
                                    onValueChange = { spacingY = it.toInt() },
                                    valueRange = 0f..64f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                                )
                                FilledIconButton(
                                    onClick = { spacingY++ },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Surface3)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = TextPrim, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }

            // --- Диапазон кадров и пресеты ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface2),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ViewCarousel, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Text("Диапазон кадров анимации", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Быстрые пресеты
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = (startFrame == 0 && endFrame == 0),
                            onClick = { startFrame = 0; endFrame = 0 },
                            label = { Text("Все кадры (0..${totalFrames - 1})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent,
                                selectedLabelColor = Navy900,
                                containerColor = Surface3,
                                labelColor = TextPrim
                            )
                        )

                        for (r in 0 until rows) {
                            val rStart = r * cols
                            val rEnd = ((r + 1) * cols - 1).coerceAtMost(totalFrames - 1)
                            val isSelected = startFrame == rStart && (endFrame == rEnd || (r == rows - 1 && endFrame == 0))
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    startFrame = rStart
                                    endFrame = if (r == rows - 1 && rStart == 0) 0 else rEnd
                                },
                                label = { Text("Строка ${r + 1} ($rStart..$rEnd)") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent,
                                    selectedLabelColor = Navy900,
                                    containerColor = Surface3,
                                    labelColor = TextPrim
                                )
                            )
                        }
                    }

                    // Начальный кадр
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Начальный кадр:", color = TextSec, fontSize = 13.sp)
                            Text("$startFrame", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = startFrame.toFloat(),
                            onValueChange = {
                                startFrame = it.toInt()
                                if (endFrame > 0 && endFrame < startFrame) endFrame = startFrame
                            },
                            valueRange = 0f..(totalFrames - 1).coerceAtLeast(1).toFloat(),
                            steps = (totalFrames - 2).coerceAtLeast(0),
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                        )
                    }

                    // Конечный кадр
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Конечный кадр:", color = TextSec, fontSize = 13.sp)
                            Text(
                                if (endFrame == 0) "0 (до конца = ${totalFrames - 1})" else "$endFrame",
                                color = TextPrim,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Slider(
                            value = endFrame.toFloat(),
                            onValueChange = {
                                val v = it.toInt()
                                endFrame = if (v < startFrame && v != 0) startFrame else v
                            },
                            valueRange = 0f..(totalFrames - 1).coerceAtLeast(1).toFloat(),
                            steps = (totalFrames - 2).coerceAtLeast(0),
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                        )
                    }
                }
            }

            // --- Настройка скорости и зацикливания ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface2),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Speed, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Text("Воспроизведение", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // FPS Slider
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Скорость воспроизведения:", color = TextSec, fontSize = 13.sp)
                            Text("${fps.toInt()} FPS", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = fps,
                            onValueChange = { fps = it },
                            valueRange = 1f..60f,
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                        )

                        // Пресеты FPS
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(6f, 10f, 12f, 24f, 30f, 60f).forEach { preset ->
                                Surface(
                                    color = if (fps == preset) Accent else Surface3,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { fps = preset }
                                ) {
                                    Text(
                                        "${preset.toInt()}",
                                        color = if (fps == preset) Navy900 else TextPrim,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Loop Toggle
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { loop = !loop }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Repeat, null, tint = if (loop) Accent else TextSec, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Зацикливать анимацию", color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    if (loop) "Бесконечное повторение" else "Один раз до последнего кадра",
                                    color = TextSec,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Switch(
                            checked = loop,
                            onCheckedChange = { loop = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Navy900,
                                checkedTrackColor = Accent,
                                uncheckedTrackColor = Surface3
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}
