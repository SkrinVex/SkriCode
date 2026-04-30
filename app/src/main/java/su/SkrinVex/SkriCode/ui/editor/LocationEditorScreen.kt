package su.SkrinVex.SkriCode.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.block.BlockFactory
import su.SkrinVex.SkriCode.data.SerializedBlock
import su.SkrinVex.SkriCode.data.deserialize
import su.SkrinVex.SkriCode.data.serialize
import su.SkrinVex.SkriCode.engine.ExprEval
import su.SkrinVex.SkriCode.ui.theme.*
import java.util.UUID
import kotlin.math.roundToInt

private val LOCATION_BLOCK_TYPES = listOf("sim_create", "sim_text", "sim_sprite")

/**
 * Редактор локации — бесконечный холст с зумом.
 * Объекты локации — это те же BlockDef (sim_create / sim_text / sim_joystick).
 */
@Composable
fun LocationEditorScreen(
    projectId: String,
    uiBlocks: List<BlockDef>,
    initialBlocks: List<SerializedBlock>,
    scenes: List<su.SkrinVex.SkriCode.data.Scene> = emptyList(),
    currentSceneId: String = "",
    spriteNames: List<String> = emptyList(),
    sprites: List<su.SkrinVex.SkriCode.data.SpriteAsset> = emptyList(),
    isLandscape: Boolean = false,
    onCopyToScene: (BlockDef, String) -> Unit = { _, _ -> },
    onSave: (List<SerializedBlock>) -> Unit,
    onDismiss: () -> Unit
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    var locBlocks by remember { mutableStateOf(initialBlocks.mapNotNull { it.deserialize() }) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Загружаем lockedIds из блоков (поле _locked в params)
    var lockedIds by remember { mutableStateOf(
        initialBlocks.mapNotNull { sb -> sb.id.takeIf { sb.params["_locked"] == "true" } }.toSet()
    ) }
    // Undo/redo стеки
    var undoStack by remember { mutableStateOf(listOf<List<BlockDef>>()) }
    var redoStack by remember { mutableStateOf(listOf<List<BlockDef>>()) }

    fun pushUndo() {
        undoStack = (undoStack + listOf(locBlocks)).takeLast(30)
        redoStack = emptyList()
    }
    fun undo() { if (undoStack.isNotEmpty()) { redoStack = redoStack + listOf(locBlocks); locBlocks = undoStack.last(); undoStack = undoStack.dropLast(1) } }
    fun redo() { if (redoStack.isNotEmpty()) { undoStack = undoStack + listOf(locBlocks); locBlocks = redoStack.last(); redoStack = redoStack.dropLast(1) } }
    // Мультивыделение
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var snapEnabled by remember { mutableStateOf(false) }
    var snapSize by remember { mutableIntStateOf(32) }

    fun snapF(v: Float) = if (snapEnabled) (kotlin.math.round(v / snapSize) * snapSize).toFloat() else v

    var showAddSheet by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<BlockDef?>(null) }
    var deleteConfirmBlock by remember { mutableStateOf<BlockDef?>(null) }
    var copyToSceneBlock by remember { mutableStateOf<BlockDef?>(null) }
    var copySelectionToScene by remember { mutableStateOf(false) }
    var deleteSelectionConfirm by remember { mutableStateOf(false) }
    // Сохраняем свёрнутость блоков между открытиями редактора объекта
    val setupCollapsedState = remember { mutableStateMapOf<String, Boolean>() }

    // Кэш bitmap для спрайтов в редакторе локации
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val locBitmapCache = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
    
    // Загружаем bitmap при первом появлении спрайта в блоке
    LaunchedEffect(projectId, sprites) {
        if (projectId.isBlank()) return@LaunchedEffect
        val allBlocks = locBlocks + uiBlocks
        val spritesInBlocks = allBlocks.mapNotNull { extractSpriteName(it) }.toSet()
        spritesInBlocks.forEach { spriteName ->
            if (!locBitmapCache.containsKey(spriteName)) {
                // Ищем правильный fileName через SpriteAsset
                val asset = sprites.find { it.name == spriteName }
                val file = if (asset != null) {
                    su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, asset.fileName)
                } else {
                    su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.png")
                        ?: su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.jpg")
                }
                val bitmap = file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                if (bitmap != null) locBitmapCache[spriteName] = bitmap
            }
        }
    }
    
    // Обновляем кэш при изменении блоков
    LaunchedEffect(locBlocks, uiBlocks) {
        if (projectId.isBlank()) return@LaunchedEffect
        val allBlocks = locBlocks + uiBlocks
        val spritesInBlocks = allBlocks.mapNotNull { extractSpriteName(it) }.toSet()
        spritesInBlocks.forEach { spriteName ->
            if (!locBitmapCache.containsKey(spriteName)) {
                val asset = sprites.find { it.name == spriteName }
                val file = if (asset != null) {
                    su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, asset.fileName)
                } else {
                    su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.png")
                        ?: su.SkrinVex.SkriCode.data.SpriteRepository.getFile(ctx, projectId, "$spriteName.jpg")
                }
                val bitmap = file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                if (bitmap != null) locBitmapCache[spriteName] = bitmap
            }
        }
    }

    // Открываем полноэкранный редактор объекта
    editingBlock?.let { block ->
        LocationObjectEditorScreen(
            objectBlock = block,
            collapsedState = setupCollapsedState,
            spriteNames = spriteNames,
            projectId = projectId,
            sprites = sprites,
            onConfirm = { updated ->
                val idx = locBlocks.indexOfFirst { it.id == updated.id }
                locBlocks = if (idx >= 0) locBlocks.toMutableList().also { it[idx] = updated }
                            else locBlocks + updated
                selectedId = updated.id
                editingBlock = null
            },
            onDismiss = { editingBlock = null }
        )
        return
    }

    val sw = if (isLandscape) ExprEval.screenHeight else ExprEval.screenWidth
    val sh = if (isLandscape) ExprEval.screenWidth else ExprEval.screenHeight

    Box(Modifier.fillMaxSize().background(Color(0xFF080C18))) {

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val worldX = (down.position.x - size.width / 2f - panX) / zoom
                        val worldY = -(down.position.y - size.height / 2f - panY) / zoom

                        val hit = locBlocks.lastOrNull { b ->
                            val bx = evalParam(b, "x"); val by = evalParam(b, "y")
                            val hw = blockHalfW(b); val hh = blockHalfH(b)
                            worldX in (bx - hw)..(bx + hw) && worldY in (by - hh)..(by + hh)
                        }

                        if (hit != null && hit.id in lockedIds) {
                            // Заблокированный — выбираем, зум разрешён, двигать нельзя
                            selectedId = hit.id
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    zoom = (zoom * event.calculateZoom()).coerceIn(0.1f, 6f)
                                    val pan = event.calculatePan(); panX += pan.x; panY += pan.y
                                    event.changes.forEach { it.consume() }; continue
                                }
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.consume()
                            } while (true)
                        } else if (hit != null) {
                            if (multiSelectMode) {
                                selectedIds = if (hit.id in selectedIds) selectedIds - hit.id else selectedIds + hit.id
                                selectedId = null
                                var moved = false; var pushedUndo = false; var lastPos = down.position
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        zoom = (zoom * event.calculateZoom()).coerceIn(0.1f, 6f)
                                        val pan = event.calculatePan(); panX += pan.x; panY += pan.y
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val delta = ch.position - lastPos
                                    if (delta.getDistance() > 8f) moved = true
                                    if (moved) {
                                        if (!pushedUndo) { pushUndo(); pushedUndo = true }
                                        lastPos = ch.position
                                        val dx = delta.x / zoom; val dy = -delta.y / zoom
                                        locBlocks = locBlocks.map { b ->
                                            if (b.id in selectedIds)
                                                b.withParam("x", snapF(evalParam(b, "x") + dx).roundToInt().toString())
                                                 .withParam("y", snapF(evalParam(b, "y") + dy).roundToInt().toString())
                                            else b
                                        }
                                    }
                                    ch.consume()
                                } while (true)
                            } else {
                                selectedId = hit.id
                                var moved = false; var pushedUndo = false; var lastPos = down.position
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        zoom = (zoom * event.calculateZoom()).coerceIn(0.1f, 6f)
                                        val pan = event.calculatePan(); panX += pan.x; panY += pan.y
                                        event.changes.forEach { it.consume() }; continue
                                    }
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val delta = ch.position - lastPos
                                    if (delta.getDistance() > 8f) moved = true
                                    if (moved) {
                                        if (!pushedUndo) { pushUndo(); pushedUndo = true }
                                        lastPos = ch.position
                                        val dx = delta.x / zoom; val dy = -delta.y / zoom
                                        locBlocks = locBlocks.map { b ->
                                            if (b.id == hit.id) {
                                                val nx = snapF(evalParam(b, "x") + dx)
                                                val ny = snapF(evalParam(b, "y") + dy)
                                                b.withParam("x", nx.roundToInt().toString())
                                                 .withParam("y", ny.roundToInt().toString())
                                            } else b
                                        }
                                    }
                                    ch.consume()
                                } while (true)
                            }
                        } else {
                            selectedId = null
                            var lastPan = down.position
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    zoom = (zoom * event.calculateZoom()).coerceIn(0.1f, 6f)
                                    val pan = event.calculatePan()
                                    panX += pan.x; panY += pan.y
                                    event.changes.forEach { it.consume() }
                                } else {
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val delta = ch.position - lastPan
                                    lastPan = ch.position
                                    panX += delta.x; panY += delta.y
                                    ch.consume()
                                }
                            } while (true)
                        }
                    }
                }
        ) {
            val cx = size.width / 2f + panX
            val cy = size.height / 2f + panY

            // Объекты локации — под сеткой, отсортированные по zOrder
            val emptyVars = emptyMap<String, String>()
            val sortedLocBlocks = locBlocks.sortedBy { b ->
                b.children["setup"]?.firstOrNull { it.type == "sim_layer" }
                    ?.params?.get("layer")?.value?.toIntOrNull() ?: 0
            }
            sortedLocBlocks.forEach { b ->
                val isSelected = b.id == selectedId || b.id in selectedIds
                drawBlock(b, cx, cy, zoom, alpha = if (b.id in lockedIds) 0.7f else 1f, emptyVars, density, isSelected, bitmapCache = locBitmapCache)
                if (zoom > 0.05f) {
                    val bx = evalParam(b, "x"); val by = evalParam(b, "y")
                    val screenX = cx + bx * zoom; val screenY = cy - by * zoom
                    val labelY = screenY - blockHalfH(b) * zoom - 6f
                    val labelSize = (13f * density).coerceIn(28f, 52f)
                    val label = (if (b.id in lockedIds) "🔒 " else "") + (b.params["name"]?.value ?: "")
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        screenX, labelY,
                        android.graphics.Paint().apply {
                            color = if (b.id in lockedIds) android.graphics.Color.argb(200, 255, 179, 0)
                                    else android.graphics.Color.argb(200, 255, 255, 255)
                            textSize = labelSize
                            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                            setShadowLayer(3f, 0f, 1f, android.graphics.Color.argb(160, 0, 0, 0))
                        }
                    )
                }
            }

            // Сетка и рамка экрана — поверх объектов локации
            val step = 50f * zoom
            val gridAlpha = (0x28 + (0x50 * (1f - zoom.coerceIn(0.1f, 1f))).toInt()).coerceIn(0x28, 0x70)
            val gridColor = Color(gridAlpha shl 24 or 0xFFFFFF)
            val gridStroke = (0.5f + 0.5f * (1f - zoom.coerceIn(0.1f, 1f))).coerceIn(0.5f, 1f)
            var gx = cx % step; while (gx < size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), gridStroke); gx += step }
            var gy = cy % step; while (gy < size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), gridStroke); gy += step }
            drawLine(Color(0x55FFFFFF), Offset(cx, 0f), Offset(cx, size.height), 1.5f)
            drawLine(Color(0x55FFFFFF), Offset(0f, cy), Offset(size.width, cy), 1.5f)

            // Граница экрана (пунктир) — яркая, всегда видна
            val sw2 = sw * zoom; val sh2 = sh * zoom
            val sl = cx - sw2 / 2f; val st = cy - sh2 / 2f
            drawRect(
                color = Color(0xAAFFFFFF),
                topLeft = Offset(sl, st), size = Size(sw2, sh2),
                style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            )
            // Подложка под текст "Экран" чтобы читался поверх спрайта
            drawContext.canvas.nativeCanvas.drawText("Экран", sl + 8f, st - 6f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 255, 255, 255)
                    textSize = 28f; isAntiAlias = true
                    setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                })

            // UI-объекты из скриптов — поверх всего (полупрозрачные)
            uiBlocks.forEach { b -> drawBlock(b, cx, cy, zoom, alpha = 0.5f, emptyVars, density, bitmapCache = locBitmapCache) }
        }

        // Инфо выбранного объекта
        selectedId?.let { sid ->
            val obj = locBlocks.find { it.id == sid }
            if (obj != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 12.dp),
                    color = Color(0xCC0A0E1A), shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(obj.params["name"]?.value ?: obj.type, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("X: ${evalParam(obj, "x").roundToInt()}  Y: ${evalParam(obj, "y").roundToInt()}", color = Color.White, fontSize = 12.sp)
                        val isLocked = obj.id in lockedIds
                        IconButton(onClick = {
                            lockedIds = if (isLocked) lockedIds - obj.id else lockedIds + obj.id
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                null,
                                tint = if (isLocked) Color(0xFFFFB300) else TextSec,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (!isLocked) {
                        IconButton(onClick = { editingBlock = obj }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, null, tint = Accent, modifier = Modifier.size(18.dp))
                        }
                        }
                        if (scenes.size > 1) {
                            IconButton(onClick = { copyToSceneBlock = obj }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.IosShare, null, tint = TextSec, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = {
                            val copy = obj.copy(
                                id = UUID.randomUUID().toString(),
                                params = obj.params.toMutableMap().also { p ->
                                    p["name"] = p["name"]!!.copy(value = (p["name"]?.value ?: "obj") + "_copy")
                                    p["x"] = p["x"]!!.copy(value = ((p["x"]?.value?.toFloatOrNull() ?: 0f) + 30f).roundToInt().toString())
                                    p["y"] = p["y"]!!.copy(value = ((p["y"]?.value?.toFloatOrNull() ?: 0f) - 30f).roundToInt().toString())
                                }
                            )
                            locBlocks = locBlocks + copy
                            selectedId = copy.id
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, null, tint = TextSec, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { deleteConfirmBlock = obj }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Зум + кнопки Отмена/Сохранить в одной строке сверху слева
        Row(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(color = Color(0xAA0A0E1A), shape = RoundedCornerShape(8.dp)) {
                Text("${(zoom * 100).roundToInt()}%", color = TextSec, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
            SmallLocFab(Icons.Default.Close, "Отмена") { onDismiss() }
            SmallLocFab(Icons.Default.Check, "Сохранить") {
                onSave(locBlocks.map { b ->
                    val s = b.serialize()
                    if (b.id in lockedIds) s.copy(params = s.params + ("_locked" to "true"))
                    else s.copy(params = s.params - "_locked")
                })
            }
        }

        // Кнопки справа
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallLocFab(Icons.Default.CenterFocusStrong, "Сброс") { zoom = 1f; panX = 0f; panY = 0f }
            SmallLocFab(Icons.Default.Add, "Добавить") { showAddSheet = true }
            SmallFloatingActionButton(
                onClick = { undo() },
                containerColor = if (undoStack.isNotEmpty()) Color(0xFF1E2535) else Color(0xFF111520),
                contentColor = if (undoStack.isNotEmpty()) Color.White else Color(0xFF444444),
                shape = CircleShape
            ) { Icon(Icons.Default.Undo, "Отменить", modifier = Modifier.size(20.dp)) }
            SmallFloatingActionButton(
                onClick = { redo() },
                containerColor = if (redoStack.isNotEmpty()) Color(0xFF1E2535) else Color(0xFF111520),
                contentColor = if (redoStack.isNotEmpty()) Color.White else Color(0xFF444444),
                shape = CircleShape
            ) { Icon(Icons.Default.Redo, "Повторить", modifier = Modifier.size(20.dp)) }
            // Мультивыделение
            SmallFloatingActionButton(
                onClick = { multiSelectMode = !multiSelectMode; if (!multiSelectMode) selectedIds = emptySet() },
                containerColor = if (multiSelectMode) Color(0xFFFFB300) else Color(0xFF1E2535),
                contentColor = if (multiSelectMode) Color.Black else Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.SelectAll, "Выделение", modifier = Modifier.size(20.dp))
            }
            // Snap-to-grid
            SmallFloatingActionButton(
                onClick = { snapEnabled = !snapEnabled },
                containerColor = if (snapEnabled) Accent else Color(0xFF1E2535),
                contentColor = if (snapEnabled) Color.Black else Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.GridOn, "Сетка", modifier = Modifier.size(20.dp))
            }
            if (snapEnabled) {
                Surface(color = Color(0xCC0A0E1A), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        listOf(8, 16, 32, 64).forEach { step ->
                            val active = snapSize == step
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (active) Accent.copy(0.2f) else Color.Transparent)
                                    .clickable { snapSize = step },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$step", color = if (active) Accent else TextSec, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // Топбар мультивыделения
        if (multiSelectMode) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                color = Color(0xCC0A0E1A), shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Выбрать всё / снять
                    val allSelected = locBlocks.isNotEmpty() && locBlocks.all { it.id in selectedIds }
                    IconButton(onClick = {
                        selectedIds = if (allSelected) emptySet() else locBlocks.map { it.id }.toSet()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            null, tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        if (selectedIds.isEmpty()) "Выбери объекты" else "${selectedIds.size} выбрано",
                        color = Color(0xFFFFB300), fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    if (selectedIds.isNotEmpty()) {
                        if (scenes.size > 1) {
                            IconButton(onClick = { copySelectionToScene = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.IosShare, null, tint = TextSec, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { deleteSelectionConfirm = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // (кнопки Отмена/Сохранить перенесены в топбар слева)
    }

    // Выбор типа блока для добавления
    if (showAddSheet) {
        AlertDialog(
            onDismissRequest = { showAddSheet = false },
            containerColor = Surface2,
            title = { Text("Добавить объект", color = TextPrim) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LOCATION_BLOCK_TYPES) { type ->
                        val proto = BlockFactory.create(type) ?: return@items
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface3)
                                .clickable {
                                    val newBlock = proto.copy(id = UUID.randomUUID().toString())
                                    editingBlock = newBlock
                                    showAddSheet = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(blockIcon(type), null, tint = Accent, modifier = Modifier.size(20.dp))
                            Column {
                                Text(proto.displayName, color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(proto.description, color = TextSec, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddSheet = false }) { Text("Отмена", color = TextSec) } }
        )
    }

    if (deleteSelectionConfirm) {
        AlertDialog(
            onDismissRequest = { deleteSelectionConfirm = false },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFFF6B6B)) },
            title = { Text("Удалить ${selectedIds.size} объектов?", color = TextPrim) },
            text = { Text("Все выделенные объекты будут удалены с локации.", color = TextSec) },
            confirmButton = {
                Button(onClick = {
                    locBlocks = locBlocks.filter { it.id !in selectedIds }
                    selectedIds = emptySet(); deleteSelectionConfirm = false
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteSelectionConfirm = false }) { Text("Отмена", color = TextSec) } }
        )
    }

    // Диалог подтверждения удаления
    deleteConfirmBlock?.let { block ->
        AlertDialog(
            onDismissRequest = { deleteConfirmBlock = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFFF6B6B)) },
            title = { Text("Удалить объект?", color = TextPrim) },
            text = { Text("«${block.params["name"]?.value ?: block.displayName}» будет удалён с локации.", color = TextSec) },
            confirmButton = {
                Button(onClick = {
                    locBlocks = locBlocks.filter { it.id != block.id }
                    selectedId = null; deleteConfirmBlock = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmBlock = null }) { Text("Отмена", color = TextSec) } }
        )
    }

    // Диалог копирования объекта в другую сцену
    copyToSceneBlock?.let { block ->
        AlertDialog(
            onDismissRequest = { copyToSceneBlock = null },
            containerColor = Surface2,
            title = { Text("Копировать в сцену", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    scenes.filter { it.id != currentSceneId }.forEach { scene ->
                        Surface(
                            onClick = { onCopyToScene(block, scene.id); copyToSceneBlock = null },
                            color = Surface3,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Layers, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Text(scene.name, color = TextPrim, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { copyToSceneBlock = null }) { Text("Отмена", color = TextSec) } }
        )
    }

    if (copySelectionToScene) {
        AlertDialog(
            onDismissRequest = { copySelectionToScene = false },
            containerColor = Surface2,
            title = { Text("Копировать ${selectedIds.size} объектов в сцену", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    scenes.filter { it.id != currentSceneId }.forEach { scene ->
                        Surface(
                            onClick = {
                                locBlocks.filter { it.id in selectedIds }.forEach { b -> onCopyToScene(b, scene.id) }
                                copySelectionToScene = false
                            },
                            color = Surface3, shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Layers, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Text(scene.name, color = TextPrim, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { copySelectionToScene = false }) { Text("Отмена", color = TextSec) } }
        )
    }
}

private fun evalParam(b: BlockDef, key: String): Float =
    ExprEval.eval(b.params[key]?.value ?: "0", emptyMap()).value.toFloatOrNull() ?: 0f

/**
 * Извлекает имя спрайта из блока, учитывая разные типы блоков:
 * - sim_sprite: параметр sprite
 * - sim_create/sim_joystick: ищет set_texture в children.setup
 */
private fun extractSpriteName(block: BlockDef): String? {
    // Прямой параметр sprite (sim_sprite)
    block.params["sprite"]?.value?.ifBlank { null }?.let { return it }
    
    // Ищем set_texture в setup (sim_create)
    block.children["setup"]?.firstOrNull { it.type == "set_texture" }
        ?.params?.get("sprite")?.value?.ifBlank { null }?.let { return it }
    
    return null
}

private fun blockHalfW(b: BlockDef): Float = when (b.type) {
    "sim_joystick" -> b.params["baseRadius"]?.value?.toFloatOrNull() ?: 100f
    else -> (b.params["width"]?.value?.toFloatOrNull() ?: 100f) / 2f
}

private fun blockHalfH(b: BlockDef): Float = when (b.type) {
    "sim_joystick" -> b.params["baseRadius"]?.value?.toFloatOrNull() ?: 100f
    else -> (b.params["height"]?.value?.toFloatOrNull() ?: 60f) / 2f
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlock(
    b: BlockDef, cx: Float, cy: Float, zoom: Float, alpha: Float,
    vars: Map<String, String>, density: Float, isSelected: Boolean = false,
    bitmapCache: Map<String, android.graphics.Bitmap?> = emptyMap()
) {
    val bx = evalParam(b, "x"); val by = evalParam(b, "y")
    val screenX = cx + bx * zoom; val screenY = cy - by * zoom

    val bc = (b.params["color"] ?: b.params["baseColor"])?.value?.let { hex ->
        runCatching { Color(0xFF000000 or hex.trim().trimStart('#').toLong(16)) }.getOrNull()
    } ?: Color(0xFF4F8EF7)

    when (b.type) {
        "sim_joystick" -> {
            val baseR = (b.params["baseRadius"]?.value?.toFloatOrNull() ?: 100f) * zoom
            val knobR = baseR * 0.4f
            val knobColor = b.params["knobColor"]?.value?.let { hex ->
                runCatching { Color(0xFF000000 or hex.trim().trimStart('#').toLong(16)) }.getOrNull()
            } ?: Color(0xFF4F8EF7)
            drawCircle(color = bc.copy(alpha = 0.5f * alpha), radius = baseR, center = Offset(screenX, screenY))
            drawCircle(color = bc.copy(alpha = 0.7f * alpha), radius = baseR, center = Offset(screenX, screenY), style = Stroke(1.5f))
            drawCircle(color = knobColor.copy(alpha = alpha), radius = knobR, center = Offset(screenX, screenY))
            if (isSelected) drawCircle(color = Color(0xFF00E5FF), radius = baseR + 3f, center = Offset(screenX, screenY), style = Stroke(2.5f))
        }
        "sim_text" -> {
            val bw = (b.params["width"]?.value?.toFloatOrNull() ?: 200f) * zoom
            val bh = (b.params["height"]?.value?.toFloatOrNull() ?: 40f) * zoom
            val br = (b.params["radius"]?.value?.toFloatOrNull() ?: 0f) * zoom
            val tl = Offset(screenX - bw / 2f, screenY - bh / 2f)
            drawRoundRect(color = Color(0x22FFFFFF).copy(alpha = 0.13f * alpha), topLeft = tl, size = Size(bw, bh), cornerRadius = CornerRadius(br, br))
            val textColor = b.params["textColor"]?.value?.let { hex ->
                runCatching { Color(0xFF000000 or hex.trim().trimStart('#').toLong(16)) }.getOrNull()
            } ?: Color.White
            drawRoundRect(color = textColor.copy(alpha = 0.5f * alpha), topLeft = tl, size = Size(bw, bh), cornerRadius = CornerRadius(br, br), style = Stroke(1f))
            val ts = ((b.params["size"]?.value?.toFloatOrNull() ?: 16f) * zoom).coerceIn(8f, 60f) * density
            drawContext.canvas.nativeCanvas.drawText(
                b.params["text"]?.value ?: "",
                screenX, screenY + ts / 3f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE; textSize = ts
                    textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                    this.alpha = (alpha * 200).toInt()
                }
            )
            if (isSelected) drawRoundRect(color = Color(0xFF00E5FF), topLeft = Offset(tl.x - 3f, tl.y - 3f), size = Size(bw + 6f, bh + 6f), cornerRadius = CornerRadius(br + 3f, br + 3f), style = Stroke(2.5f))
        }
        else -> {
            val bw = (b.params["width"]?.value?.toFloatOrNull() ?: 100f) * zoom
            val bh = (b.params["height"]?.value?.toFloatOrNull() ?: 60f) * zoom
            val br = (b.params["radius"]?.value?.toFloatOrNull() ?: 8f) * zoom
            val tl = Offset(screenX - bw / 2f, screenY - bh / 2f)
            val spriteName = extractSpriteName(b)
            val bitmap = spriteName?.let { bitmapCache[it] }
            if (bitmap != null) {
                val paint = android.graphics.Paint().apply { isAntiAlias = true; this.alpha = (alpha * 255).toInt() }
                drawContext.canvas.nativeCanvas.drawBitmap(bitmap, null,
                    android.graphics.RectF(tl.x, tl.y, tl.x + bw, tl.y + bh), paint)
            } else {
                drawRoundRect(color = bc.copy(alpha = alpha), topLeft = tl, size = Size(bw, bh), cornerRadius = CornerRadius(br, br))
                if (spriteName != null) {
                    drawLine(Color.Yellow.copy(alpha = 0.5f * alpha), tl, Offset(tl.x + bw, tl.y + bh), 2f)
                    drawLine(Color.Yellow.copy(alpha = 0.5f * alpha), Offset(tl.x + bw, tl.y), Offset(tl.x, tl.y + bh), 2f)
                }
            }
            if (isSelected) drawRoundRect(color = Color(0xFF00E5FF), topLeft = Offset(tl.x - 3f, tl.y - 3f), size = Size(bw + 6f, bh + 6f), cornerRadius = CornerRadius(br + 3f, br + 3f), style = Stroke(2.5f))
        }
    }
}

private fun blockIcon(type: String) = when (type) {
    "sim_create"   -> Icons.Default.CropSquare
    "sim_text"     -> Icons.Default.TextFields
    "sim_sprite"   -> Icons.Default.Image
    else           -> Icons.Default.Add
}

@Composable
private fun SmallLocFab(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    SmallFloatingActionButton(onClick = onClick, containerColor = Color(0xFF1E2535), contentColor = Color.White, shape = CircleShape) {
        Icon(icon, desc, modifier = Modifier.size(20.dp))
    }
}

// Диалог копирования выделения в другую сцену — вызывается из LocationEditorScreen
// (встроен в основную функцию через copySelectionToScene state)
