package su.SkrinVex.SkriPts.ui.sim

import android.graphics.BitmapFactory
import android.graphics.Rect as AndroidRect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.data.SpriteAsset
import su.SkrinVex.SkriPts.engine.JoystickState
import su.SkrinVex.SkriPts.engine.SimObject
import su.SkrinVex.SkriPts.engine.SimState
import su.SkrinVex.SkriPts.engine.HitboxType
import su.SkrinVex.SkriPts.ui.theme.*
import kotlin.math.*

/** Кэш декодированных bitmap для спрайтов — живёт пока жив SimulationScreen */
private val bitmapCache = mutableMapOf<String, android.graphics.Bitmap?>()

@Composable
fun SimulationScreen(
    state: SimState,
    simRunCount: Int = 0,
    onTap: (objectName: String) -> Unit,
    onHoldStart: (objectName: String, pointerId: Long) -> Unit = { _, _ -> },
    onHoldEnd: (pointerId: Long) -> Unit = {},
    onJoystickMove: (name: String, dx: Float, dy: Float, pointerId: Long) -> Unit = { _, _, _, _ -> },
    onJoystickRelease: (pointerId: Long) -> Unit = {},
    onBack: () -> Unit,
    onClearLogs: () -> Unit = {},
    debugMode: Boolean = true,
    showHitboxes: Boolean = false
) {
    BackHandler(onBack = onBack)

    // Если симуляция остановлена и debug выключен — сразу выходим
    LaunchedEffect(state.isStopped) {
        if (state.isStopped && !debugMode) onBack()
    }

    // Если симуляция остановлена и debug включён — открываем лог
    var panelTab by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.isStopped) {
        if (state.isStopped && debugMode) panelTab = 0
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as android.app.Activity).window
        val ctrl = WindowInsetsControllerCompat(window, view)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val ctx = LocalContext.current
    var bitmapCacheVersion by remember { mutableIntStateOf(0) }

    // Перезагружаем bitmap при каждом запуске симуляции
    LaunchedEffect(state.projectId, simRunCount) {
        bitmapCache.clear()
        state.sprites.forEach { sprite ->
            val file = su.SkrinVex.SkriPts.data.SpriteRepository.getFile(ctx, state.projectId, sprite.fileName)
            bitmapCache[sprite.name] = file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        }
        bitmapCacheVersion++
    }

    var highlightedObj by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(Pair(0f, 0f)) }

    // Сбрасываем подсветку через 1.5 сек
    LaunchedEffect(highlightedObj) {
        if (highlightedObj != null) {
            kotlinx.coroutines.delay(1500)
            highlightedObj = null
        }
    }

    Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets(0)).background(Color.Black)) {
        val currentState by rememberUpdatedState(state)
        val currentCanvasSize by rememberUpdatedState(canvasSize)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state.isStopped) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (currentState.isStopped) continue
                            val cx = currentCanvasSize.first / 2f
                            val cy = currentCanvasSize.second / 2f
                            when (event.type) {
                                PointerEventType.Press, PointerEventType.Move -> {
                                    event.changes.forEach { change ->
                                        if (!change.pressed) return@forEach
                                        val offset = change.position
                                        val pid = change.id.value.toLong()
                                        val capturedJoy = currentState.joysticks.values.firstOrNull { j -> j.pointerId == pid && j.visible }
                                        val joy = capturedJoy ?: if (event.type == PointerEventType.Press)
                                            currentState.joysticks.values.firstOrNull { j ->
                                                j.visible && hypot(offset.x - (cx + j.x), offset.y - (cy - j.y)) <= j.baseRadius
                                            } else null
                                        if (joy != null) {
                                            val jx = cx + joy.x; val jy = cy - joy.y
                                            val rawDx = (offset.x - jx) / joy.baseRadius
                                            val rawDy = (offset.y - jy) / joy.baseRadius
                                            val len = hypot(rawDx, rawDy).coerceAtMost(1f)
                                            val angle = atan2(rawDy, rawDx)
                                            onJoystickMove(joy.name, cos(angle) * len, -sin(angle) * len, pid)
                                        } else if (event.type == PointerEventType.Press) {
                                            val cam = currentState.camera
                                            val camOx = if (cam != null && cam.enabled) cam.offsetX else 0f
                                            val camOy = if (cam != null && cam.enabled) cam.offsetY else 0f
                                            val uiTags = cam?.uiTags ?: emptySet()
                                            val hit = currentState.objects.values.filter { it.visible }.lastOrNull { obj ->
                                                val isUi = obj.tags.any { it in uiTags }
                                                val ox = if (isUi) cx else cx + camOx
                                                val oy = if (isUi) cy else cy + camOy
                                                val left = ox + obj.x - obj.width / 2f
                                                val top  = oy - obj.y - obj.height / 2f
                                                offset.x in left..(left + obj.width) && offset.y in top..(top + obj.height)
                                            }
                                            if (hit != null) {
                                                onTap(hit.name)
                                                onHoldStart(hit.name, pid)
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Release -> {
                                    event.changes.forEach { change ->
                                        if (!change.pressed) {
                                            val pid = change.id.value.toLong()
                                            onHoldEnd(pid)
                                            onJoystickRelease(pid)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            canvasSize = Pair(size.width, size.height)
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Читаем версию кэша чтобы Canvas перерисовывался после загрузки bitmap
            @Suppress("UNUSED_EXPRESSION") bitmapCacheVersion
            if (debugMode) drawGrid(cx, cy)

            val cam = state.camera
            val camOx = if (cam != null && cam.enabled) cam.offsetX else 0f
            val camOy = if (cam != null && cam.enabled) cam.offsetY else 0f
            val uiTags = cam?.uiTags ?: emptySet()

            val sortedObjects = state.objects.values.sortedBy { it.zOrder }

            // Мировые объекты — со смещением камеры, отсортированные по zOrder
            sortedObjects.forEach { obj ->
                if (!obj.visible) return@forEach
                val isUi = obj.tags.any { it in uiTags }
                if (!isUi) {
                    val ox = cx + camOx + obj.x
                    val oy = cy + camOy - obj.y
                    val hw = obj.width / 2f; val hh = obj.height / 2f
                    if (ox + hw < 0 || ox - hw > size.width || oy + hh < 0 || oy - hh > size.height) return@forEach
                    drawSimObject(obj, cx + camOx, cy + camOy, obj.name == highlightedObj, debugMode)
                }
            }
            if (showHitboxes) {
                sortedObjects.forEach { obj ->
                    if (obj.visible) {
                        val isUi = obj.tags.any { it in uiTags }
                        if (!isUi) drawHitbox(obj, cx + camOx, cy + camOy)
                    }
                }
            }
            // UI объекты — без смещения (поверх), тоже по zOrder
            sortedObjects.forEach { obj ->
                if (!obj.visible) return@forEach
                val isUi = obj.tags.any { it in uiTags }
                if (isUi) drawSimObject(obj, cx, cy, obj.name == highlightedObj, debugMode)
            }
            if (showHitboxes) {
                sortedObjects.forEach { obj ->
                    if (obj.visible) {
                        val isUi = obj.tags.any { it in uiTags }
                        if (isUi) drawHitbox(obj, cx, cy)
                    }
                }
            }
            state.joysticks.values.forEach { if (it.visible) drawJoystick(it, cx, cy) }
        }

        // Кнопка закрыть — только в debug или при ошибках
        if (debugMode || state.errors.isNotEmpty()) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(Color(0x88000000), RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.Close, "Закрыть", tint = Color.White)
            }
        }

        val hasErrors = state.errors.isNotEmpty()
        // Кнопки панелей — только в debug или при ошибках
        if ((debugMode || hasErrors) && panelTab < 0) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PanelBtn(
                    label = if (hasErrors) "Ошибки (${state.errors.size})" else "Лог",
                    active = false,
                    color = if (hasErrors) Danger else TextSec,
                    onClick = { panelTab = 0 }
                )
                if (debugMode) PanelBtn(
                    label = "Объекты (${state.objects.size})",
                    active = false,
                    color = Accent,
                    onClick = { panelTab = 1 }
                )
            }
        }

        // Панель
        if ((debugMode || hasErrors) && panelTab >= 0) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 240.dp),
                color = Color(0xEE0A0E1A),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PanelBtn(
                            label = if (hasErrors) "Ошибки (${state.errors.size})" else "Лог",
                            active = panelTab == 0,
                            color = if (hasErrors) Danger else TextSec,
                            onClick = { panelTab = 0 }
                        )
                        if (debugMode) {
                            Spacer(Modifier.width(6.dp))
                            PanelBtn(
                                label = "Объекты (${state.objects.size})",
                                active = panelTab == 1,
                                color = Accent,
                                onClick = { panelTab = 1 }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { panelTab = -1 }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, "Свернуть", tint = TextSec, modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                    when (panelTab) {
                        0 -> LogPanel(state, onClearLogs)
                        1 -> ObjectsPanel(state.objects.values.toList(), onHighlight = { highlightedObj = it })
                    }
                }
            }
        }

        if (state.objects.isEmpty() && state.errors.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Сцена пуста", color = Color(0x88FFFFFF), fontSize = 16.sp)
                Text("Добавь блоки «Симуляция» в редакторе", color = Color(0x55FFFFFF), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PanelBtn(label: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) color.copy(alpha = 0.25f) else Color(0x88000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (active) color else color.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LogPanel(state: SimState, onClearLogs: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (state.errors.isNotEmpty()) "Ошибки" else "Лог выполнения",
            color = if (state.errors.isNotEmpty()) Danger else TextSec,
            fontWeight = FontWeight.SemiBold, fontSize = 13.sp
        )
        if (state.log.isNotEmpty() || state.errors.isNotEmpty()) {
            TextButton(
                onClick = onClearLogs,
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Очистить", color = TextSec, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    LazyColumn {
        items(state.errors) { e ->
            Text("! $e", color = Danger, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 2.dp))
        }
        items(state.log) { l ->
            Text(l, color = Color(0xFF4ADE80), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}

@Composable
private fun ObjectsPanel(objects: List<SimObject>, onHighlight: (String) -> Unit) {
    Text("Объекты на сцене", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(objects, key = { it.name }) { obj ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A2540))
                    .clickable { onHighlight(obj.name) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Цветной квадрат
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (obj.color == Color.Transparent) Color(0x44FFFFFF) else obj.color)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(obj.name, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace)
                    Text("(${obj.x.toInt()}, ${obj.y.toInt()})  ${obj.width.toInt()}x${obj.height.toInt()}",
                        color = TextSec, fontSize = 11.sp)
                }
                if (obj.tapScriptId != null) {
                    Text("TAP", color = Warning, fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Warning.copy(0.15f)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
                if (obj.holdScriptId != null) {
                    Spacer(Modifier.width(4.dp))
                    Text("HOLD", color = Color(0xFFA78BFA), fontSize = 10.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFA78BFA).copy(0.15f)).padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(cx: Float, cy: Float) {
    val step = 50f
    val gridColor = Color(0x18FFFFFF)
    var x = cx % step; while (x < size.width) { drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.5f); x += step }
    var y = cy % step; while (y < size.height) { drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.5f); y += step }
    drawLine(Color(0x33FFFFFF), Offset(cx, 0f), Offset(cx, size.height), 1f)
    drawLine(Color(0x33FFFFFF), Offset(0f, cy), Offset(size.width, cy), 1f)
}

private fun DrawScope.drawJoystick(joy: JoystickState, cx: Float, cy: Float) {
    val jx = cx + joy.x
    val jy = cy - joy.y
    // База
    drawCircle(color = joy.baseColor, radius = joy.baseRadius, center = Offset(jx, jy))
    drawCircle(color = joy.baseColor.copy(alpha = 0.4f), radius = joy.baseRadius,
        center = Offset(jx, jy), style = Stroke(width = 2f))
    // Ручка
    val kx = jx + joy.knobDx * (joy.baseRadius - joy.knobRadius)
    val ky = jy - joy.knobDy * (joy.baseRadius - joy.knobRadius)
    drawCircle(color = joy.knobColor, radius = joy.knobRadius, center = Offset(kx, ky))
}

private fun DrawScope.drawHitbox(obj: SimObject, cx: Float, cy: Float) {
    val body = obj.physicsBody ?: return
    val left = cx + obj.x - obj.width / 2f
    val top  = cy - obj.y - obj.height / 2f
    val centerX = left + obj.width / 2f
    val centerY = top + obj.height / 2f
    val hitboxColor = if (body.isStatic) Color(0xFF00FF88) else Color(0xFFFF4444)

    rotate(obj.rotation, Offset(centerX, centerY)) {
        if (obj.hitbox.type == su.SkrinVex.SkriPts.engine.HitboxType.MANUAL && obj.hitbox.points.size >= 2) {
            val pts = obj.hitbox.points
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                drawLine(hitboxColor,
                    Offset(centerX + a.first, centerY - a.second),
                    Offset(centerX + b.first, centerY - b.second),
                    strokeWidth = 2f)
            }
        } else {
            drawRoundRect(
                color = hitboxColor,
                topLeft = Offset(left, top),
                size = Size(obj.width, obj.height),
                cornerRadius = CornerRadius(obj.radius, obj.radius),
                style = Stroke(width = 2f)
            )
        }
    }
}

private fun DrawScope.drawSimObject(obj: SimObject, cx: Float, cy: Float, highlighted: Boolean, debugMode: Boolean = true) {
    val left = cx + obj.x - obj.width / 2f
    val top  = cy - obj.y - obj.height / 2f
    val cr = CornerRadius(obj.radius, obj.radius)
    val centerX = left + obj.width / 2f
    val centerY = top + obj.height / 2f

    rotate(obj.rotation, Offset(centerX, centerY)) {
        // Фон (цвет) — рисуем если нет спрайта или цвет не прозрачный
        if (obj.spriteName == null && obj.color != Color.Transparent) {
            drawRoundRect(color = obj.color, topLeft = Offset(left, top), size = Size(obj.width, obj.height), cornerRadius = cr)
        }

        // Спрайт
        val bitmap = obj.spriteName?.let { bitmapCache[it] }
        if (bitmap != null) {
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                alpha = (obj.spriteAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            val srcRect = if (obj.spriteCropW > 0 && obj.spriteCropH > 0) {
                AndroidRect(obj.spriteCropX, obj.spriteCropY,
                    obj.spriteCropX + obj.spriteCropW, obj.spriteCropY + obj.spriteCropH)
            } else null
            val dstRect = android.graphics.RectF(left, top, left + obj.width, top + obj.height)
            drawContext.canvas.nativeCanvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }

        // Обводка
        val isTextOnly = obj.color == Color.Transparent && obj.spriteName == null
        val strokeColor = when {
            highlighted -> Color.Yellow
            isTextOnly -> Color.Transparent
            obj.tapScriptId != null && debugMode -> Color.White.copy(alpha = 0.7f)
            obj.spriteName != null -> Color.Transparent
            else -> obj.color.copy(alpha = 0.4f)
        }
        if (strokeColor != Color.Transparent) {
            drawRoundRect(
                color = strokeColor,
                topLeft = Offset(left - 1f, top - 1f),
            size = Size(obj.width + 2f, obj.height + 2f),
            cornerRadius = CornerRadius(obj.radius + 1f, obj.radius + 1f),
            style = Stroke(width = if (highlighted) 3f else if (obj.tapScriptId != null) 2f else 1f)
        )
    }

    // Текст — только если label задан явно
    if (obj.label.isNotBlank()) {
        val textSize = obj.fontSize * density
        val tc = obj.textColor ?: Color.White
        drawContext.canvas.nativeCanvas.drawText(
            obj.label,
            left + obj.width / 2f,
            top + obj.height / 2f + textSize / 3f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.argb(
                    (tc.alpha * 255).toInt(), (tc.red * 255).toInt(),
                    (tc.green * 255).toInt(), (tc.blue * 255).toInt()
                )
                this.textSize = textSize
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                isFakeBoldText = obj.bold
            }
        )
    }
    } // end rotate
}
