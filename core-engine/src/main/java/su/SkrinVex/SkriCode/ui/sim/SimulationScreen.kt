package su.SkrinVex.SkriCode.ui.sim

import android.graphics.BitmapFactory
import android.graphics.Rect as AndroidRect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import su.SkrinVex.SkriCode.data.SpriteAsset
import su.SkrinVex.SkriCode.engine.ExprEval
import su.SkrinVex.SkriCode.engine.HitboxType
import su.SkrinVex.SkriCode.engine.JoystickState
import su.SkrinVex.SkriCode.engine.SimObject
import su.SkrinVex.SkriCode.engine.SimState
import su.SkrinVex.SkriCode.ui.theme.*
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
    showHitboxes: Boolean = false,
    onTextInputSubmit: ((objectName: String, text: String) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.clearFocusTrigger) {
        if (state.clearFocusTrigger > 0L) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

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
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val ctrl = WindowInsetsControllerCompat(window, view)
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                val ctrl = WindowInsetsControllerCompat(window, view)
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val ctx = LocalContext.current
    var bitmapCacheVersion by remember { mutableIntStateOf(0) }

    // Перезагружаем bitmap при каждом запуске симуляции или изменении списка спрайтов
    LaunchedEffect(state.projectId, state.sprites, simRunCount) {
        bitmapCache.clear()
        state.sprites.forEach { sprite ->
            val file = su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, state.projectId, sprite.fileName)
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
                                            val zoom = if (cam != null && cam.enabled) cam.zoom.coerceIn(0.05f, 20f) else 1f
                                            val uiTags = cam?.uiTags ?: emptySet()
                                            val hit = currentState.objects.values
                                                .filter { it.visible && it.touchEnabled && !it.isTextInput }
                                                .sortedWith(compareBy({ isUiObject(it, uiTags) }, { it.zOrder }))
                                                .lastOrNull { obj ->
                                                    val isUi = isUiObject(obj, uiTags)
                                                    if (isUi) {
                                                        val left = cx + obj.x - obj.width / 2f
                                                        val top  = cy - obj.y - obj.height / 2f
                                                        offset.x in left..(left + obj.width) && offset.y in top..(top + obj.height)
                                                    } else {
                                                        val ox = cx + camOx
                                                        val oy = cy + camOy
                                                        val touchX = (offset.x - cx) / zoom + cx
                                                        val touchY = (offset.y - cy) / zoom + cy
                                                        val left = ox + obj.x - obj.width / 2f
                                                        val top  = oy - obj.y - obj.height / 2f
                                                        touchX in left..(left + obj.width) && touchY in top..(top + obj.height)
                                                    }
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
            // Обновляем базовые физические размеры экрана из реального размера Canvas
            ExprEval.updateDeviceResolution(size.width, size.height)
            val shakeOx = state.screenShake.currentOffsetX
            val shakeOy = state.screenShake.currentOffsetY
            val cx = size.width / 2f + shakeOx
            val cy = size.height / 2f + shakeOy
            // Читаем версию кэша чтобы Canvas перерисовывался после загрузки bitmap
            @Suppress("UNUSED_EXPRESSION") bitmapCacheVersion
            drawRect(state.backgroundColor)
            if (debugMode) drawGrid(cx, cy)

            val cam = state.camera
            val camOx = if (cam != null && cam.enabled) cam.offsetX else 0f
            val camOy = if (cam != null && cam.enabled) cam.offsetY else 0f
            val zoom = if (cam != null && cam.enabled) cam.zoom.coerceIn(0.05f, 20f) else 1f
            val uiTags = cam?.uiTags ?: emptySet()

            val sortedObjects = state.objects.values.sortedBy { it.zOrder }

            // Мировые объекты (со смещением и масштабом камеры)
            withTransform({
                if (zoom != 1f) {
                    scale(scaleX = zoom, scaleY = zoom, pivot = Offset(cx, cy))
                }
            }) {
                sortedObjects.forEach { obj ->
                    if (!obj.visible || isUiObject(obj, uiTags)) return@forEach
                    val ox = cx + camOx + obj.x
                    val oy = cy + camOy - obj.y
                    val maxRadius = kotlin.math.hypot(obj.width, obj.height) / 2f
                    if (ox + maxRadius < -300f || ox - maxRadius > size.width + 300f ||
                        oy + maxRadius < -300f || oy - maxRadius > size.height + 300f) return@forEach
                    drawSimObject(obj, cx + camOx, cy + camOy, obj.name == highlightedObj, debugMode, state, ctx)
                }

                // Отрисовка частиц (мировые координаты)
                if (state.particles.isNotEmpty()) {
                    state.particles.forEach { p ->
                        val ox = cx + camOx + p.x
                        val oy = cy + camOy - p.y
                        if (ox >= -100f && ox <= size.width + 100f && oy >= -100f && oy <= size.height + 100f) {
                            val progress = if (p.maxLife > 0f) (1f - (p.life / p.maxLife)).coerceIn(0f, 1f) else 1f
                            val currentRadius = (p.sizeStart + (p.sizeEnd - p.sizeStart) * progress) / 2f
                            val alpha = if (p.maxLife > 0f) (p.life / p.maxLife).coerceIn(0f, 1f) else 0f
                            val r = p.colorStart.red + (p.colorEnd.red - p.colorStart.red) * progress
                            val g = p.colorStart.green + (p.colorEnd.green - p.colorStart.green) * progress
                            val b = p.colorStart.blue + (p.colorEnd.blue - p.colorStart.blue) * progress
                            val col = Color(r, g, b, alpha)
                            drawCircle(color = col, radius = currentRadius.coerceAtLeast(1f), center = Offset(ox, oy))
                        }
                    }
                }

                if (showHitboxes) {
                    sortedObjects.forEach { obj ->
                        if (obj.visible && !isUiObject(obj, uiTags)) {
                            drawHitbox(obj, cx + camOx, cy + camOy)
                        }
                    }
                }
            }

            // UI объекты — без смещения и без зума (поверх мировых), по zOrder
            sortedObjects.forEach { obj ->
                if (!obj.visible || !isUiObject(obj, uiTags)) return@forEach
                drawSimObject(obj, cx, cy, obj.name == highlightedObj, debugMode, state, ctx)
            }
            if (showHitboxes) {
                sortedObjects.forEach { obj ->
                    if (obj.visible && isUiObject(obj, uiTags)) {
                        drawHitbox(obj, cx, cy)
                    }
                }
            }
            state.joysticks.values.forEach { if (it.visible) drawJoystick(it, cx, cy) }

            // Вспышка экрана
            if (state.screenFlash.active && state.screenFlash.duration > 0f) {
                val progress = (state.screenFlash.elapsed / state.screenFlash.duration).coerceIn(0f, 1f)
                val flashAlpha = (1f - progress).coerceIn(0f, 1f)
                if (flashAlpha > 0f) {
                    drawRect(
                        color = state.screenFlash.color.copy(alpha = flashAlpha),
                        topLeft = Offset.Zero,
                        size = size
                    )
                }
            }
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

        // Нативные инлайн-поля ввода прямо на холсте симуляции
        if (canvasSize.first > 0f && canvasSize.second > 0f) {
            val cx = canvasSize.first / 2f + state.screenShake.currentOffsetX
            val cy = canvasSize.second / 2f + state.screenShake.currentOffsetY
            val cam = state.camera
            val camOx = if (cam != null && cam.enabled) cam.offsetX else 0f
            val camOy = if (cam != null && cam.enabled) cam.offsetY else 0f
            val zoom = if (cam != null && cam.enabled) cam.zoom.coerceIn(0.05f, 20f) else 1f
            val uiTags = cam?.uiTags ?: emptySet()
            val density = LocalDensity.current

            state.objects.values.filter { it.visible && it.isTextInput }.forEach { obj ->
                val isUi = isUiObject(obj, uiTags)
                val leftPx: Float
                val topPx: Float
                val objW: Float
                val objH: Float
                if (isUi) {
                    leftPx = cx + obj.x - obj.width / 2f
                    topPx = cy - obj.y - obj.height / 2f
                    objW = obj.width
                    objH = obj.height
                } else {
                    val ox = (cx + camOx + obj.x - cx) * zoom + cx
                    val oy = (cy + camOy - obj.y - cy) * zoom + cy
                    objW = obj.width * zoom
                    objH = obj.height * zoom
                    leftPx = ox - objW / 2f
                    topPx = oy - objH / 2f
                }

                val isMulti = obj.multiline
                val align = if (isMulti) Alignment.TopStart else Alignment.CenterStart
                val vertPad = if (isMulti) 8.dp else 4.dp

                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                        .size(with(density) { objW.toDp() }, with(density) { objH.toDp() })
                        .alpha(obj.alpha.coerceIn(0f, 1f))
                        .rotate(obj.rotation)
                        .clip(RoundedCornerShape(with(density) { (obj.radius * if (isUi) 1f else zoom).toDp() }))
                        .background(obj.color)
                        .border(
                            width = 1.5.dp,
                            color = Color(0xFF4F8EF7).copy(alpha = 0.85f),
                            shape = RoundedCornerShape(with(density) { (obj.radius * if (isUi) 1f else zoom).toDp() })
                        )
                        .padding(horizontal = 10.dp, vertical = vertPad),
                    contentAlignment = align
                ) {
                    var textValue by remember(obj.name) { mutableStateOf(obj.label) }
                    LaunchedEffect(obj.label) {
                        if (textValue != obj.label) {
                            textValue = obj.label
                        }
                    }

                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

                    BasicTextField(
                        value = textValue,
                        onValueChange = { newText ->
                            textValue = newText
                            onTextInputSubmit?.invoke(obj.name, newText)
                        },
                        textStyle = TextStyle(
                            color = obj.textColor ?: Color.White,
                            fontSize = obj.fontSize.sp,
                            fontWeight = if (obj.bold) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Default
                        ),
                        cursorBrush = SolidColor(obj.textColor ?: Color.White),
                        singleLine = !isMulti,
                        maxLines = if (isMulti) 8 else 1,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (!isMulti && obj.inputTrigger == "keyboard") ImeAction.Done else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                onTextInputSubmit?.invoke(obj.name, textValue)
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = align
                            ) {
                                if (textValue.isEmpty() && obj.placeholder.isNotEmpty()) {
                                    Text(
                                        text = obj.placeholder,
                                        color = (obj.textColor ?: Color.White).copy(alpha = 0.45f),
                                        fontSize = obj.fontSize.sp,
                                        fontWeight = if (obj.bold) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
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
    val body = obj.physicsBody
    val left = cx + obj.x - obj.width / 2f
    val top  = cy - obj.y - obj.height / 2f
    val centerX = left + obj.width / 2f
    val centerY = top + obj.height / 2f
    val hitboxColor = if (body == null) Color(0xFF00E5FF) else if (body.isStatic) Color(0xFF00FF88) else Color(0xFFFF4444)

    rotate(obj.rotation, Offset(centerX, centerY)) {
        if (obj.hitbox.type == HitboxType.MANUAL && obj.hitbox.points.size >= 2) {
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

private val spritePaint = android.graphics.Paint().apply { isAntiAlias = true }
private val textPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign = android.graphics.Paint.Align.CENTER
}
private val dstRectF = android.graphics.RectF()
private val srcRectAndroid = AndroidRect()

private fun DrawScope.drawSimObject(
    obj: SimObject,
    cx: Float,
    cy: Float,
    highlighted: Boolean,
    debugMode: Boolean = true,
    state: SimState? = null,
    ctx: android.content.Context? = null
) {
    if (obj.isTextInput) return
    val left = cx + obj.x - obj.width / 2f
    val top  = cy - obj.y - obj.height / 2f
    val cr = CornerRadius(obj.radius, obj.radius)
    val centerX = left + obj.width / 2f
    val centerY = top + obj.height / 2f

    rotate(obj.rotation, Offset(centerX, centerY)) {
        val objectAlpha = obj.alpha.coerceIn(0f, 1f)

        // Фон (цвет) — рисуем если нет спрайта или цвет не прозрачный
        if (obj.spriteName == null && obj.color != Color.Transparent) {
            val bgAlpha = (obj.color.alpha * objectAlpha).coerceIn(0f, 1f)
            drawRoundRect(color = obj.color.copy(alpha = bgAlpha), topLeft = Offset(left, top), size = Size(obj.width, obj.height), cornerRadius = cr)
        }

        // Спрайт
        val bitmap = obj.spriteName?.let { name ->
            bitmapCache[name] ?: run {
                if (state != null && ctx != null) {
                    val sprite = state.sprites.find { it.name == name }
                    val fileName = sprite?.fileName ?: (name + ".png")
                    val file = su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, state.projectId, fileName)
                    val bmp = file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                    if (bmp != null) {
                        bitmapCache[name] = bmp
                    }
                    bmp
                } else null
            }
        }
        if (bitmap != null) {
            spritePaint.alpha = ((obj.spriteAlpha.coerceIn(0f, 1f) * objectAlpha).coerceIn(0f, 1f) * 255).toInt()
            val srcRect = if (obj.animCols > 1 || obj.animRows > 1 || obj.animPlaying || obj.animCurrentFrame > 0) {
                val cols = obj.animCols.coerceAtLeast(1)
                val rows = obj.animRows.coerceAtLeast(1)
                val offX = obj.animOffsetX.coerceAtLeast(0)
                val offY = obj.animOffsetY.coerceAtLeast(0)
                val spX = obj.animSpacingX.coerceAtLeast(0)
                val spY = obj.animSpacingY.coerceAtLeast(0)
                val frameW = if (obj.animFrameWidth > 0) obj.animFrameWidth else {
                    ((bitmap.width - offX - (cols - 1) * spX) / cols).coerceAtLeast(1)
                }
                val frameH = if (obj.animFrameHeight > 0) obj.animFrameHeight else {
                    ((bitmap.height - offY - (rows - 1) * spY) / rows).coerceAtLeast(1)
                }
                val totalFrames = cols * rows
                val curFrame = obj.animCurrentFrame.coerceIn(0, totalFrames - 1)
                val col = curFrame % cols
                val row = curFrame / cols
                val leftCrop = (offX + col * (frameW + spX)).coerceIn(0, bitmap.width)
                val topCrop = (offY + row * (frameH + spY)).coerceIn(0, bitmap.height)
                val rightCrop = (leftCrop + frameW).coerceIn(0, bitmap.width)
                val bottomCrop = (topCrop + frameH).coerceIn(0, bitmap.height)
                srcRectAndroid.set(leftCrop, topCrop, rightCrop, bottomCrop)
                srcRectAndroid
            } else if (obj.spriteCropW > 0 && obj.spriteCropH > 0) {
                srcRectAndroid.set(
                    obj.spriteCropX, obj.spriteCropY,
                    obj.spriteCropX + obj.spriteCropW, obj.spriteCropY + obj.spriteCropH
                )
                srcRectAndroid
            } else null
            dstRectF.set(left, top, left + obj.width, top + obj.height)
            drawContext.canvas.nativeCanvas.drawBitmap(bitmap, srcRect, dstRectF, spritePaint)
        }

        // Обводка
        val isTextOnly = obj.color == Color.Transparent && obj.spriteName == null && !obj.isTextInput
        val strokeColor = when {
            highlighted -> Color.Yellow
            obj.isTextInput -> Color(0xFF818CF8).copy(alpha = 0.8f)
            isTextOnly -> Color.Transparent
            obj.tapScriptId != null && debugMode -> Color.White.copy(alpha = 0.7f)
            obj.spriteName != null -> Color.Transparent
            else -> obj.color.copy(alpha = 0.4f)
        }
        if (strokeColor != Color.Transparent) {
            val strokeAlpha = (strokeColor.alpha * objectAlpha).coerceIn(0f, 1f)
            drawRoundRect(
                color = strokeColor.copy(alpha = strokeAlpha),
                topLeft = Offset(left - 1f, top - 1f),
                size = Size(obj.width + 2f, obj.height + 2f),
                cornerRadius = CornerRadius(obj.radius + 1f, obj.radius + 1f),
                style = Stroke(width = if (highlighted) 3f else if (obj.isTextInput) 1.5f else if (obj.tapScriptId != null) 2f else 1f)
            )
        }

        // Текст — если label задан явно или если это текстовое поле с placeholder
        val displayText = if (obj.label.isNotBlank()) obj.label else if (obj.isTextInput) obj.placeholder else ""
        if (displayText.isNotBlank()) {
            val textSize = obj.fontSize * density
            val isPlaceholder = obj.label.isBlank() && obj.isTextInput
            val tc = if (isPlaceholder) (obj.textColor ?: Color.White).copy(alpha = 0.45f) else (obj.textColor ?: Color.White)
            val finalAlpha = (tc.alpha * objectAlpha).coerceIn(0f, 1f)
            textPaint.color = android.graphics.Color.argb(
                (finalAlpha * 255).toInt(), (tc.red * 255).toInt(),
                (tc.green * 255).toInt(), (tc.blue * 255).toInt()
            )
            textPaint.textSize = textSize
            textPaint.isFakeBoldText = obj.bold

            val lines = obj.label.replace("\\n", "\n").split("\n")
            val lineSpacing = textSize * 1.25f
            val totalTextHeight = lines.size * lineSpacing
            val startY = top + (obj.height - totalTextHeight) / 2f + textSize * 0.85f
            val textCenterX = left + obj.width / 2f

            lines.forEachIndexed { idx, line ->
                drawContext.canvas.nativeCanvas.drawText(
                    line,
                    textCenterX,
                    startY + idx * lineSpacing,
                    textPaint
                )
            }
        }
    } // end rotate
}

private fun isUiObject(obj: su.SkrinVex.SkriCode.engine.SimObject, uiTags: Set<String>): Boolean {
    if (uiTags.isEmpty()) return false
    val cleanUiTags = uiTags.map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotBlank() }.toSet()
    if (cleanUiTags.isEmpty()) return false
    return obj.tags.any { tag -> tag.trim().removePrefix("#").lowercase() in cleanUiTags }
}

