package su.SkrinVex.SkriCode.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.block.*
import su.SkrinVex.SkriCode.data.ProjectVar
import su.SkrinVex.SkriCode.data.ProjectTag
import su.SkrinVex.SkriCode.data.Script
import su.SkrinVex.SkriCode.data.ScriptEvent
import su.SkrinVex.SkriCode.data.VarScope
import su.SkrinVex.SkriCode.data.ProjectTable
import su.SkrinVex.SkriCode.data.deserialize
import su.SkrinVex.SkriCode.engine.ExprEval
import su.SkrinVex.SkriCode.ui.expr.ExpressionEditorScreen
import su.SkrinVex.SkriCode.ui.theme.*

data class ExprEditTarget(val blockIndex: Int, val paramKey: String, val paramLabel: String, val currentValue: String, val isIdentifier: Boolean = false, val branch: String? = null, val childIndex: Int = -1)

data class AnimEditorTarget(val blockIndex: Int, val block: BlockDef, val branch: String? = null, val childIndex: Int = -1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onLandscapeNeeded: (Boolean) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val needsLandscape = state.orientation == su.SkrinVex.SkriCode.data.ProjectOrientation.LANDSCAPE
    var showPicker by remember { mutableStateOf(false) }
    var exprTarget by remember { mutableStateOf<ExprEditTarget?>(null) }
    var showScriptMenu by remember { mutableStateOf<String?>(null) }
    var showAddScriptDialog by remember { mutableStateOf(false) }
    var positionPickerBlock by remember { mutableStateOf<Pair<Int, BlockDef>?>(null) }
    // blockIndex -> sim_hitbox block
    var hitboxEditorTarget by remember { mutableStateOf<Pair<Int, BlockDef>?>(null) }
    var animEditorTarget by remember { mutableStateOf<AnimEditorTarget?>(null) }
    var showLocationEditor by remember { mutableStateOf(false) }

    // Редактор локации — всегда портретная ориентация устройства, но рамка экрана по ориентации проекта
    if (showLocationEditor) {
        DisposableEffect(Unit) {
            onLandscapeNeeded(false)
            onDispose { onLandscapeNeeded(false) }
        }
        val projectIsLandscape = state.orientation == su.SkrinVex.SkriCode.data.ProjectOrientation.LANDSCAPE
        val uiBlocks = state.allScriptBlocks.filter { it.params.containsKey("x") && it.params.containsKey("y") }
        LocationEditorScreen(
            projectId = state.projectId,
            uiBlocks = uiBlocks,
            initialBlocks = state.locationBlocks,
            scenes = state.scenes,
            currentSceneId = state.activeSceneId,
            spriteNames = state.spriteNames,
            sprites = state.sprites,
            isLandscape = projectIsLandscape,
            onCopyToScene = { block, sceneId -> vm.copyLocationBlockToScene(block, sceneId) },
            onSave = { blocks ->
                vm.updateLocationBlocks(blocks)
                showLocationEditor = false
            },
            onDismiss = { showLocationEditor = false }
        )
        return
    }

    // Редактор хитбоксов — полноэкранный
    hitboxEditorTarget?.let { (blockIndex, block) ->
        val objName = block.params["name"]?.value ?: ""
        val simObj = state.activeBlocks.find {
            (it.type == "sim_create" || it.type == "sim_text" || it.type == "sim_sprite") && it.params["name"]?.value == objName
        }
        val fakeObj = simObj?.let {
            val spriteName = it.params["sprite"]?.value?.ifBlank { null }
            val spriteAsset = if (spriteName != null) state.sprites.find { s -> s.name == spriteName } else null
            val rawW = it.params["width"]?.value?.toFloatOrNull() ?: 0f
            val rawH = it.params["height"]?.value?.toFloatOrNull() ?: 0f
            val w = if (rawW > 0f) rawW else (spriteAsset?.width?.toFloat() ?: 100f)
            val h = if (rawH > 0f) rawH else (spriteAsset?.height?.toFloat() ?: 60f)
            su.SkrinVex.SkriCode.engine.SimObject(
                name = objName, x = 0f, y = 0f,
                width = w, height = h,
                radius = it.params["radius"]?.value?.toFloatOrNull() ?: 0f,
                color = su.SkrinVex.SkriCode.engine.SimEngine.parseColor(it.params["color"]?.value ?: "#4F8EF7"),
                spriteName = spriteName
            )
        } ?: su.SkrinVex.SkriCode.engine.SimObject(
            name = objName, x = 0f, y = 0f, width = 100f, height = 60f, radius = 8f,
            color = androidx.compose.ui.graphics.Color(0xFF4F8EF7)
        )
        val existingPoints = su.SkrinVex.SkriCode.engine.SimEngine.parseHitboxPoints(
            block.params["points"]?.value ?: ""
        )
        HitboxEditorScreen(
            obj = fakeObj,
            initialPoints = existingPoints,
            projectId = state.projectId,
            spriteName = fakeObj.spriteName,
            onConfirm = { pts ->
                val serialized = su.SkrinVex.SkriCode.engine.SimEngine.serializeHitboxPoints(pts)
                vm.updateParam(blockIndex, "type", if (pts.isEmpty()) "auto" else "manual")
                vm.updateParam(blockIndex, "points", serialized)
                hitboxEditorTarget = null
            },
            onDismiss = { hitboxEditorTarget = null }
        )
        return
    }

    // Редактор анимации спрайт-листов — полноэкранный
    animEditorTarget?.let { target ->
        val b = target.block
        val sprite = b.params["sprite"]?.value ?: ""
        val cols = b.params["cols"]?.value?.toIntOrNull() ?: 4
        val rows = b.params["rows"]?.value?.toIntOrNull() ?: 1
        val start = b.params["startFrame"]?.value?.toIntOrNull() ?: 0
        val end = b.params["endFrame"]?.value?.toIntOrNull() ?: 0
        val fps = b.params["fps"]?.value?.toFloatOrNull() ?: 12f
        val loop = b.params["loop"]?.value != "false"
        val offX = b.params["offsetX"]?.value?.toIntOrNull() ?: 0
        val offY = b.params["offsetY"]?.value?.toIntOrNull() ?: 0
        val spX = b.params["spacingX"]?.value?.toIntOrNull() ?: 0
        val spY = b.params["spacingY"]?.value?.toIntOrNull() ?: 0
        val fw = b.params["frameW"]?.value?.toIntOrNull() ?: 0
        val fh = b.params["frameH"]?.value?.toIntOrNull() ?: 0

        SpriteAnimationEditorScreen(
            initialSprite = sprite,
            initialCols = cols,
            initialRows = rows,
            initialStartFrame = start,
            initialEndFrame = end,
            initialFps = fps,
            initialLoop = loop,
            initialOffsetX = offX,
            initialOffsetY = offY,
            initialSpacingX = spX,
            initialSpacingY = spY,
            initialFrameW = fw,
            initialFrameH = fh,
            sprites = state.sprites,
            projectId = state.projectId,
            onConfirm = { sName, nCols, nRows, nStart, nEnd, nFps, nLoop, nOffX, nOffY, nSpX, nSpY, nFw, nFh ->
                fun setP(key: String, value: String) {
                    if (target.branch != null && target.childIndex >= 0) {
                        vm.updateChildParam(target.blockIndex, target.branch, target.childIndex, key, value)
                    } else {
                        vm.updateParam(target.blockIndex, key, value)
                    }
                }
                setP("sprite", sName)
                setP("cols", nCols.toString())
                setP("rows", nRows.toString())
                setP("startFrame", nStart.toString())
                setP("endFrame", nEnd.toString())
                setP("fps", nFps.toInt().toString())
                setP("loop", nLoop.toString())
                setP("offsetX", nOffX.toString())
                setP("offsetY", nOffY.toString())
                setP("spacingX", nSpX.toString())
                setP("spacingY", nSpY.toString())
                setP("frameW", nFw.toString())
                setP("frameH", nFh.toString())
                animEditorTarget = null
            },
            onDismiss = { animEditorTarget = null }
        )
        return
    }

    // Редактор выражений — полноэкранный
    exprTarget?.let { target ->
        ExpressionEditorScreen(
            initialValue = target.currentValue,
            paramLabel = target.paramLabel,
            variables = state.visibleVars,
            tags = state.visibleTags,
            tables = state.visibleTables,
            isIdentifier = target.isIdentifier,
            onConfirm = { value ->
                if (target.branch != null && target.childIndex >= 0) {
                    vm.updateChildParam(target.blockIndex, target.branch, target.childIndex, target.paramKey, value)
                } else {
                    vm.updateParam(target.blockIndex, target.paramKey, value)
                }
                exprTarget = null
            },
            onCreateVar = { name, scope -> vm.addVariable(name, scope) },
            onDeleteVar = { name, scope -> vm.deleteVariable(name, scope) },
            onCreateTag = { name, scope -> vm.addTag(name, scope) },
            onDeleteTag = { name, scope -> vm.deleteTag(name, scope) },
            onCreateTable = { name, scope -> vm.addTable(name, scope) },
            onDeleteTable = { name, scope -> vm.deleteTable(name, scope) },
            onSetTableEntry = { name, scope, k, v -> vm.setTableEntry(name, scope, k, v) },
            onRemoveTableEntry = { name, scope, k -> vm.removeTableEntry(name, scope, k) },
            onBack = { exprTarget = null }
        )
        return
    }

    // Визуальный позиционировщик — полноэкранный
    positionPickerBlock?.let { (blockIndex, block) ->
        DisposableEffect(Unit) {
            onLandscapeNeeded(needsLandscape)
            onDispose { onLandscapeNeeded(false) }
        }
        val vars = emptyMap<String, String>()
        fun evalF(key: String, default: Float): Float {
            val raw = block.params[key]?.value ?: return default
            return ExprEval.eval(raw, vars).value.toFloatOrNull() ?: default
        }
        val allOtherBlocks = if (ThemeManager.showObjectsInPicker) {
            val scriptBlocks = state.allScriptBlocks.filter { it.id != block.id && it.params.containsKey("x") && it.params.containsKey("y") }
            val locBlocks = state.locationBlocks.mapNotNull { it.deserialize() }.filter { it.id != block.id }
            scriptBlocks + locBlocks
        } else emptyList()
        
        PositionPickerScreen(
            projectId = state.projectId,
            objectName = block.params["name"]?.value ?: block.displayName,
            blockType = block.type,
            objectWidth = if (block.type == "sim_joystick") evalF("baseRadius", 100f).coerceAtLeast(10f) * 2f
                          else evalF("width", 100f).coerceAtLeast(10f),
            objectHeight = if (block.type == "sim_joystick") evalF("baseRadius", 100f).coerceAtLeast(10f) * 2f
                           else evalF("height", 60f).coerceAtLeast(10f),
            objectRadius = evalF("radius", 8f).coerceAtLeast(0f),
            objectColor = block.params["color"]?.value?.let {
                runCatching {
                    val c = it.trim().trimStart('#').toLong(16)
                    if (it.trim().trimStart('#').length == 6) androidx.compose.ui.graphics.Color(0xFF000000 or c)
                    else androidx.compose.ui.graphics.Color(c)
                }.getOrNull()
            } ?: block.params["baseColor"]?.value?.let {
                runCatching {
                    val c = it.trim().trimStart('#').toLong(16)
                    androidx.compose.ui.graphics.Color(0xFF000000 or c)
                }.getOrNull()
            } ?: androidx.compose.ui.graphics.Color(0xFF4F8EF7),
            objectSprite = block.params["sprite"]?.value?.ifBlank { null }
                ?: block.children["setup"]?.firstOrNull { it.type == "set_texture" }?.params?.get("sprite")?.value?.ifBlank { null },
            sprites = state.sprites,
            initialX = evalF("x", 0f),
            initialY = evalF("y", 0f),
            showOtherObjects = ThemeManager.showObjectsInPicker,
            otherBlocks = allOtherBlocks,
            onConfirm = { xExpr, yExpr ->
                vm.updateParam(blockIndex, "x", xExpr)
                vm.updateParam(blockIndex, "y", yExpr)
                positionPickerBlock = null
            },
            onDismiss = { positionPickerBlock = null }
        )
        return
    }

    // Диалог ошибок
    if (state.validationErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::dismissErrors,
            containerColor = Surface2,
            icon = { Icon(Icons.Default.Warning, null, tint = Warning) },
            title = { Text("Исправь ошибки", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.validationErrors.forEach { err ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Danger,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp))
                            Text(err, color = TextPrim, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = vm::dismissErrors,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("Понял", color = Navy900)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Navy900)) {
        Column(Modifier.fillMaxSize()) {
            // TopBar
            Surface(color = Surface1, shadowElevation = 4.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                        }
                        Text(state.projectName, color = TextPrim, fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1)
                        var showSimSettings by remember { mutableStateOf(false) }
                        IconButton(onClick = { showSimSettings = true }) {
                            Icon(Icons.Default.Settings, "Настройки симуляции", tint = TextSec)
                        }
                        if (showSimSettings) {
                            SimSettingsDialog(onDismiss = { showSimSettings = false })
                        }
                        var showProjectSettings by remember { mutableStateOf(false) }
                        IconButton(onClick = { showProjectSettings = true }) {
                            Icon(Icons.Default.Tune, "Настройки проекта", tint = TextSec)
                        }
                        if (showProjectSettings) {
                            ProjectSettingsDialog(
                                orientation = state.orientation,
                                onOrientationChange = { vm.setOrientation(it) },
                                packageName = state.packageName,
                                onPackageNameChange = { vm.setPackageName(it) },
                                onDismiss = { showProjectSettings = false },
                                vm = vm,
                                projectName = state.projectName
                            )
                        }
                        IconButton(onClick = { showLocationEditor = true }) {
                            Icon(Icons.Default.Map, "Редактор локации", tint = TextSec)
                        }
                        IconButton(onClick = vm::runSim) {
                            Icon(Icons.Default.PlayArrow, "Запустить", tint = Success)
                        }
                    }
                    // Панель сцен
                    SceneTabsRow(
                        scenes = state.scenes,
                        activeId = state.activeScene.id,
                        onSelect = vm::selectScene,
                        onAdd = { vm.addScene("Сцена ${state.scenes.size + 1}") },
                        onRename = { id, name -> vm.renameScene(id, name) },
                        onDelete = { id -> vm.deleteScene(id) }
                    )
                    // Панель скриптов
                    ScriptTabsRow(
                        scripts = state.scripts,
                        activeId = state.activeScript.id,
                        onSelect = vm::selectScript,
                        onAdd = { showAddScriptDialog = true },
                        onLongPress = { showScriptMenu = it }
                    )
                }
            }

            // Блоки активного скрипта
            val activeBlocks = state.activeBlocks
            val activeScriptId = state.activeScript.id
            val listState = rememberLazyListState()

            // Восстанавливаем позицию при смене скрипта
            LaunchedEffect(activeScriptId) {
                val pos = vm.getScrollPosition(activeScriptId)
                if (pos > 0) listState.scrollToItem(pos)
            }

            // Сохраняем позицию при уходе со скрипта (не при каждом пикселе)
            DisposableEffect(activeScriptId) {
                onDispose { vm.saveScrollPosition(activeScriptId, listState.firstVisibleItemIndex) }
            }

            // Локальное состояние свёрнутости — инициализируем из ViewModel при смене скрипта
            val collapsedState = remember(activeScriptId) {
                mutableStateMapOf<String, Boolean>().also { map ->
                    activeBlocks.forEach { block ->
                        val c = vm.isBlockCollapsed(activeScriptId, block.id)
                        android.util.Log.d("SkriPts", "collapsedState init block=${block.id} type=${block.type} collapsed=$c")
                        if (c) map[block.id] = true
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Заголовок события скрипта
                item {
                    ScriptEventHeader(
                        script = state.activeScript,
                        variables = state.visibleVars,
                        tags = state.visibleTags,
                        onChangeEvent = { event, target -> vm.setScriptEvent(state.activeScript.id, event, target) }
                    )
                }
                if (activeBlocks.isEmpty()) {
                    item { EmptyState() }
                }
                itemsIndexed(activeBlocks, key = { _, b -> b.id }) { index, block ->
                    val collapsed = collapsedState[block.id] ?: vm.isBlockCollapsed(activeScriptId, block.id)
                    var showDeleteConfirm by remember { mutableStateOf(false) }

                    val openTypes = setOf("if_open", "for_loop_open", "while_loop_open", "wait_open")
                    val closeTypes = setOf("if_close", "for_loop_close", "while_loop_close", "wait_close")
                    val isOpenCloseElse = block.type in openTypes || block.type in closeTypes || block.type == "else_block"

                    // Определяем скрыт ли блок из-за свёрнутого open или else
                    val isBodyHidden = run {
                        if (block.type in openTypes) return@run false
                        val pairId = block.pairId
                        if (pairId.isBlank()) return@run false

                        val openIdx = activeBlocks.indexOfFirst { it.pairId == pairId && it.type in openTypes }
                        val elseIdx = activeBlocks.indexOfFirst { it.pairId == pairId && it.type == "else_block" }
                        val closeIdx = activeBlocks.indexOfFirst { it.pairId == pairId && it.type in closeTypes }

                        val openCollapsed = if (openIdx >= 0) {
                            val id = activeBlocks[openIdx].id
                            collapsedState[id] ?: vm.isBlockCollapsed(activeScriptId, id)
                        } else false

                        val elseCollapsed = if (elseIdx >= 0) {
                            val id = activeBlocks[elseIdx].id
                            collapsedState[id] ?: vm.isBlockCollapsed(activeScriptId, id)
                        } else false

                        when {
                            block.type == "else_block" -> false
                            block.type in closeTypes -> if (elseIdx < 0) openCollapsed else elseCollapsed
                            elseIdx >= 0 && index > elseIdx && (closeIdx < 0 || index < closeIdx) -> elseCollapsed
                            openIdx >= 0 && index > openIdx && (elseIdx < 0 || index < elseIdx) -> openCollapsed
                            else -> false
                        }
                    }
                    if (isBodyHidden) return@itemsIndexed

                    // Отступ: open/close/else — всегда 0, тело внутри пары — глубина * 12dp
                    val indentDp = if (isOpenCloseElse) 0.dp else remember(activeBlocks, index) {
                        var depth = 0
                        for (i in 0 until index) {
                            val t = activeBlocks[i].type
                            if (t in openTypes) {
                                val pairId = activeBlocks[i].pairId
                                val closeIdx = if (pairId.isNotBlank())
                                    activeBlocks.indexOfFirst { it.pairId == pairId && it.type in closeTypes }
                                else activeBlocks.indexOfFirst { it.type in closeTypes }
                                if (closeIdx < 0 || closeIdx >= index) depth++
                            } else if (t in closeTypes && depth > 0) depth--
                        }
                        depth
                    }.let { (it * 12).dp }
                    if (showDeleteConfirm) {
                        val isPaired = block.pairId.isNotBlank()
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            containerColor = Surface2,
                            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
                            title = { Text("Удалить блок?", color = TextPrim) },
                            text = { Text(
                                if (isPaired) "«${block.displayName}» и все связанные блоки (включая тело) будут удалены."
                                else "«${block.displayName}»${if (block.children.isNotEmpty()) " и все вложенные блоки" else ""} будут удалены.",
                                color = TextSec
                            ) },
                            confirmButton = {
                                Button(onClick = { vm.removeBlock(index); showDeleteConfirm = false },
                                    colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                                    Text("Удалить")
                                }
                            },
                            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена", color = TextSec) } }
                        )
                    }
                    BlockCard(
                        block = block,
                        index = index,
                        total = activeBlocks.size,
                        variables = state.visibleVars,
                        allBlocks = state.allScriptBlocks,
                        sceneNames = state.sceneNames,
                        spriteNames = state.spriteNames,
                        soundNames = state.soundNames,
                        collapsed = collapsed,
                        scriptId = activeScriptId,
                        onToggleCollapse = {
                            val next = vm.toggleBlockCollapsed(activeScriptId, block.id)
                            collapsedState[block.id] = next
                        },
                        onToggleChildCollapsed = { childId -> vm.toggleBlockCollapsed(activeScriptId, childId) },
                        isChildCollapsed = { childId -> vm.isBlockCollapsed(activeScriptId, childId) },
                        onRemove = { showDeleteConfirm = true },
                        onMoveUp = { if (vm.canMoveUp(index, activeBlocks)) vm.moveBlock(index, index - 1) },
                        onMoveDown = { if (vm.canMoveDown(index, activeBlocks)) vm.moveBlock(index, index + 1) },                        canMoveUp = vm.canMoveUp(index, activeBlocks),
                        canMoveDown = vm.canMoveDown(index, activeBlocks),
                        onDuplicate = { vm.duplicateBlock(index) },
                        onParamChange = { k, v -> vm.updateParam(index, k, v) },
                        onCopyBlock = { vm.copyBlock(state.activeScript.blocks[index]) },
                        onOpenExpr = { key, label, cur, isId -> exprTarget = ExprEditTarget(index, key, label, cur, isId) },
                        onOpenPositionPicker = { b -> positionPickerBlock = index to b },
                        onOpenHitboxEditor = { b -> hitboxEditorTarget = index to b },
                        onOpenAnimEditor = { b -> animEditorTarget = AnimEditorTarget(index, b) },
                        onAddChild = { branch, type -> vm.addChildBlock(index, branch, type) },
                        onRemoveChild = { branch, ci -> vm.removeChildBlock(index, branch, ci) },
                        onChildParamChange = { branch, ci, k, v -> vm.updateChildParam(index, branch, ci, k, v) },
                        onOpenChildExpr = { branch, ci, key, label, cur, isId -> exprTarget = ExprEditTarget(index, key, label, cur, isId, branch, ci) },
                        onOpenChildPositionPicker = { branch, ci, b ->
                            val childBlock = state.activeScript.blocks[index].deserialize()
                                ?.children?.get(branch)?.getOrNull(ci) ?: b
                            positionPickerBlock = index to childBlock
                        },
                        onOpenChildAnimEditor = { branch, ci, b ->
                            val childBlock = state.activeScript.blocks[index].deserialize()
                                ?.children?.get(branch)?.getOrNull(ci) ?: b
                            animEditorTarget = AnimEditorTarget(index, childBlock, branch, ci)
                        },
                        onUpdateChild = { branch, ci, updated -> vm.replaceChildBlock(index, branch, ci, updated) },
                        indentStart = indentDp
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showPicker = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent
        ) {
            Icon(Icons.Default.Add, "Добавить блок", tint = Navy900)
        }
    }

    // Диалог добавления скрипта
    if (showAddScriptDialog) {
        var scriptName by remember { mutableStateOf("Скрипт ${state.scripts.size + 1}") }
        AlertDialog(
            onDismissRequest = { showAddScriptDialog = false },
            containerColor = Surface2,
            title = { Text("Новый скрипт", color = TextPrim) },
            text = {
                OutlinedTextField(
                    value = scriptName, onValueChange = { scriptName = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                        cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = { if (scriptName.isNotBlank()) { vm.addScript(scriptName.trim()); showAddScriptDialog = false } },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Создать", color = Navy900) }
            },
            dismissButton = { TextButton(onClick = { showAddScriptDialog = false }) { Text("Отмена", color = TextSec) } }
        )
    }

    // Меню скрипта (долгое нажатие) — переименовать / удалить
    showScriptMenu?.let { scriptId ->
        val script = state.scripts.find { it.id == scriptId }
        if (script != null) {
            ScriptMenuDialog(
                script = script,
                onRename = { name -> vm.renameScript(scriptId, name); showScriptMenu = null },
                onDelete = { vm.deleteScript(scriptId); showScriptMenu = null },
                onCopy = { vm.copyScript(script) },
                onDismiss = { showScriptMenu = null }
            )
        }
    }

    // FAB вставки — показывается когда в буфере есть скрипт или блок
    if (state.clipboardIsScript != null) {
        Box(Modifier.fillMaxSize()) {
            ExtendedFloatingActionButton(
                onClick = { if (state.clipboardIsScript == true) vm.pasteScript() else vm.pasteBlock() },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp),
                containerColor = Color(0xFF2A2F3E),
                contentColor = Accent,
                icon = { Icon(Icons.Default.ContentPaste, null) },
                text = { Text(if (state.clipboardIsScript == true) "Вставить скрипт" else "Вставить блок") }
            )
        }
    }

    if (showPicker) {
        BlockPickerSheet(onDismiss = { showPicker = false }, onPick = { type -> vm.addBlock(type); showPicker = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptTabsRow(
    scripts: List<Script>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onLongPress: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scripts.forEach { script ->
            val isActive = script.id == activeId
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) Accent.copy(alpha = 0.2f) else Surface3)
                    .border(1.dp, if (isActive) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onSelect(script.id) },
                        onLongClick = { onLongPress(script.id) }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        eventIcon(script.event), null,
                        tint = if (isActive) Accent else TextSec,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(script.name, color = if (isActive) Accent else TextSec,
                        fontSize = 13.sp, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        // Кнопка добавить скрипт
        IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, "Новый скрипт", tint = TextSec, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ScriptEventHeader(script: Script, variables: List<ProjectVar>, tags: List<ProjectTag>, onChangeEvent: (ScriptEvent, String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface2)
            .clickable { showDialog = true }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(eventIcon(script.event), null, tint = Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(script.event.label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (script.event != ScriptEvent.ON_START && script.eventTarget.isNotBlank()) {
                Text(
                    if (script.eventTarget.startsWith("#")) "Тег: ${script.eventTarget}"
                    else "Объект: ${script.eventTarget}",
                    color = TextSec, fontSize = 11.sp
                )
            }
        }
        Icon(Icons.Default.Edit, null, tint = TextSec, modifier = Modifier.size(14.dp))
    }

    if (showDialog) {
        EventPickerDialog(
            current = script.event,
            currentTarget = script.eventTarget,
            variables = variables,
            tags = tags,
            onConfirm = { event, target -> onChangeEvent(event, target); showDialog = false },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun EventPickerDialog(
    current: ScriptEvent,
    currentTarget: String,
    variables: List<ProjectVar>,
    tags: List<ProjectTag>,
    onConfirm: (ScriptEvent, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    var target by remember { mutableStateOf(currentTarget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Событие скрипта", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScriptEvent.entries.forEach { event ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selected == event) Accent.copy(0.15f) else Surface3)
                            .clickable { selected = event }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(eventIcon(event), null, tint = if (selected == event) Accent else TextSec,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(event.label, color = if (selected == event) Accent else TextPrim, fontSize = 14.sp)
                    }
                }
                if (selected != ScriptEvent.ON_START) {
                    HorizontalDivider(color = Surface3)
                    // Поле ввода имени объекта
                    OutlinedTextField(
                        value = if (target.startsWith("#")) "" else target,
                        onValueChange = { target = it },
                        label = { Text("Имя объекта") },
                        placeholder = { Text("rect1", color = TextSec) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, focusedLabelColor = Accent,
                            cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                        )
                    )
                    // Список тегов
                    if (tags.isNotEmpty()) {
                        Text("или выбери тег:", color = TextSec, fontSize = 11.sp)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tags.forEach { tag ->
                                val tagVal = "#${tag.name}"
                                val active = target == tagVal
                                Box(
                                    Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(if (active) Color(0xFFFF6B6B).copy(0.2f) else Surface3)
                                        .border(1.dp, if (active) Color(0xFFFF6B6B) else Color.Transparent, RoundedCornerShape(6.dp))
                                        .clickable { target = if (active) "" else tagVal }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("#${tag.name}", color = if (active) Color(0xFFFF6B6B) else TextSec, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    // Показываем что выбрано
                    if (target.isNotBlank()) {
                        Text(
                            "Выбрано: $target",
                            color = Accent, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected, target) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("OK", color = Navy900)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
private fun ScriptMenuDialog(script: Script, onRename: (String) -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(script.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Скрипт", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                        cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
                TextButton(onClick = { onCopy(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Копировать скрипт", color = Accent)
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DeleteOutline, null, tint = Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить скрипт", color = Danger)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRename(name.ifBlank { script.name }) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Сохранить", color = Navy900)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.ViewModule, null, tint = TextSec, modifier = Modifier.size(48.dp))
            Text("Нет блоков", color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text("Нажми + чтобы добавить блок", color = TextSec, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun BlockParamContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    sceneNames: List<String> = emptyList(),
    spriteNames: List<String> = emptyList(),
    soundNames: List<String> = emptyList(),
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
) {
    block.paramOrder.forEach { key ->
        val param = block.params[key] ?: return@forEach
        when {
            block.type == "set_var" && key == "name" ->
                VarNameChip(value = param.value, label = param.label,
                    onClick = { onOpenExpr(key, param.label, param.value, true) })
            block.type == "set_tag" && key == "tag" ->
                TagNameChip(value = param.value, label = param.label,
                    onClick = { onOpenExpr(key, param.label, param.value, true) })
            block.type == "set_tag" && key == "object" ->
                ObjectNameChip(param = param, variables = variables,
                    onClick = { onOpenExpr(key, param.label, param.value, false) })
            (block.type == "table_set" || block.type == "table_get") && key == "table" ->
                TableNameChip(value = param.value, label = param.label,
                    onClick = { onOpenExpr(key, param.label, param.value, true) })
            (block.type == "table_get") && key == "var" ->
                VarNameChip(value = param.value, label = param.label,
                    onClick = { onOpenExpr(key, param.label, param.value, true) })
            (block.type == "save_table" || block.type == "load_table") && key == "table" ->
                TableNameChip(value = param.value, label = param.label,
                    onClick = { onOpenExpr(key, param.label, param.value, true) })
            block.type == "load_var" && key == "var" ->
                VarNameChip(value = param.value, label = param.label,
                    onClick = { onOpenExpr(key, param.label, param.value, true) })
            block.type == "scene_switch" && key == "scene" ->
                SceneChip(param = param, sceneNames = sceneNames,
                    onChange = { onParamChange(key, it) })
            (block.type == "set_texture" || block.type == "sim_sprite" || block.type == "anim_play") && key == "sprite" ->
                SpriteChip(param = param, spriteNames = spriteNames,
                    onChange = { onParamChange(key, it) })
            (block.category == BlockCategory.AUDIO || block.type == "sound_play" || block.type == "sound_stop" || block.type == "music_play") && key == "sound" ->
                SoundChip(param = param, soundNames = soundNames,
                    onChange = { onParamChange(key, it) })
            (block.category == BlockCategory.AUDIO || block.type == "anim_play") && key == "loop" ->
                BoolToggle(param = param, onChange = { onParamChange(key, it) })
            (block.type == "save_var" || block.type == "load_var" || block.type == "save_table" || block.type == "load_table") && key == "encrypt" ->
                EncryptToggle(param = param, onChange = { onParamChange(key, it) })
            (key == "name" || key == "target") && (block.category == BlockCategory.SIMULATION || block.category == BlockCategory.SPRITE || block.category == BlockCategory.PHYSICS) && block.type != "sim_create" && block.type != "sim_text" && block.type != "sim_joystick" && block.type != "particle_emitter" ->
                ObjectNameChip(param = param, variables = variables,
                    onClick = { onOpenExpr(key, param.label, param.value, false) })
            key == "name" && (block.type == "sim_create" || block.type == "sim_text" || block.type == "sim_joystick" || block.type == "particle_emitter") ->
                ExprChip(param = param, variables = variables,
                    onClick = { onOpenExpr(key, param.label, param.value, false) })
            key == "color" || key == "baseColor" || key == "knobColor" || key == "textColor" || key == "colorStart" || key == "colorEnd" ->
                ColorField(param = param, onChange = { onParamChange(key, it) })
            block.type == "sim_move" && key == "mode" ->
                MoveModeToggle(value = param.value, onChange = { onParamChange(key, it) })
            (block.type == "sim_rotate") && key == "mode" ->
                RotateModeToggle(value = param.value, onChange = { onParamChange(key, it) })
            key == "bold" || (block.type == "sim_joystick" && key == "directional") ||
            (block.type == "sim_camera" && key == "enabled") || (block.type == "camera_toggle" && key == "enabled") ||
            (block.type == "physics_toggle" && key == "enabled") || (block.type == "sim_physics" && key == "static") ->
                BoolToggle(param = param, onChange = { onParamChange(key, it) })
            key == "op" || key == "op1" || key == "op2" ->
                OperatorSelector(param = param, onChange = { onParamChange(key, it) })
            else ->
                ExprChip(param = param, variables = variables,
                    onClick = { onOpenExpr(key, param.label, param.value, false) })
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
internal fun BlockCard(
    block: BlockDef, index: Int, total: Int,
    variables: List<ProjectVar>,
    allBlocks: List<BlockDef> = emptyList(),
    sceneNames: List<String> = emptyList(),
    spriteNames: List<String> = emptyList(),
    soundNames: List<String> = emptyList(),
    collapsed: Boolean,
    scriptId: String = "",
    onToggleCollapse: () -> Unit,
    onToggleChildCollapsed: ((blockId: String) -> Boolean)? = null,
    isChildCollapsed: ((blockId: String) -> Boolean)? = null,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean = index > 0,
    canMoveDown: Boolean = index < total - 1,
    onDuplicate: () -> Unit,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onCopyBlock: (() -> Unit)? = null,
    onOpenPositionPicker: ((block: BlockDef) -> Unit)? = null,
    onOpenHitboxEditor: ((block: BlockDef) -> Unit)? = null,
    onOpenAnimEditor: ((block: BlockDef) -> Unit)? = null,
    onAddChild: (branch: String, type: String) -> Unit = { _, _ -> },
    onRemoveChild: (branch: String, childIndex: Int) -> Unit = { _, _ -> },
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit = { _, _, _, _ -> },
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onOpenChildPositionPicker: ((branch: String, childIndex: Int, block: BlockDef) -> Unit)? = null,
    onOpenChildAnimEditor: ((branch: String, childIndex: Int, block: BlockDef) -> Unit)? = null,
    onUpdateChild: ((branch: String, childIndex: Int, updated: BlockDef) -> Unit)? = null,
    indentStart: Dp = 0.dp
) {
    val accent = categoryColor(block.category)
    var showContextMenu by remember { mutableStateOf(false) }

    // Специальный рендеринг для open/close блоков
    val isOpen = block.type in setOf("if_open", "for_loop_open", "while_loop_open", "wait_open")
    val isClose = block.type in setOf("if_close", "for_loop_close", "while_loop_close", "wait_close")
    val isElse = block.type == "else_block"
    if (isOpen || isClose || isElse) {
        OpenCloseBlockCard(
            block = block,
            isOpen = isOpen,
            index = index,
            variables = variables,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            collapsed = collapsed,
            onToggleCollapse = onToggleCollapse,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRemove = onRemove,
            onParamChange = onParamChange,
            onOpenExpr = onOpenExpr,
            indentStart = indentStart
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize()
            .padding(start = indentStart)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showContextMenu = true }
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(categoryIcon(block.category), null, tint = accent, modifier = Modifier.size(14.dp))
                            Text(block.displayName, color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Text(block.description, color = TextSec, fontSize = 11.sp)
                    }
                    SmallBtn(Icons.Default.KeyboardArrowUp, enabled = index > 0, onClick = onMoveUp)
                    SmallBtn(Icons.Default.KeyboardArrowDown, enabled = index < total - 1, onClick = onMoveDown)
                    val hasPosition = block.params.containsKey("x") && block.params.containsKey("y")
                    if (hasPosition && onOpenPositionPicker != null) {
                        SmallBtn(Icons.Default.OpenWith, tint = Color(0xFF00E5FF), onClick = { onOpenPositionPicker(block) })
                    }
                    SmallBtn(
                        if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        tint = accent.copy(alpha = 0.7f),
                        onClick = onToggleCollapse
                    )
                }
                if (!collapsed) {
                    when (block.type) {
                        "if_block" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            IfBlockContent(
                                block = block, variables = variables,
                                allBlocks = allBlocks, sceneNames = sceneNames, spriteNames = spriteNames,
                                soundNames = soundNames,
                                scriptId = scriptId,
                                onToggleChildCollapsed = onToggleChildCollapsed,
                                isChildCollapsed = isChildCollapsed,
                                onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                                onAddChild = onAddChild, onRemoveChild = onRemoveChild,
                                onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr,
                                onOpenChildPositionPicker = onOpenChildPositionPicker,
                                onOpenChildAnimEditor = onOpenChildAnimEditor,
                                onUpdateChild = onUpdateChild
                            )
                        }
                        "for_loop", "while_loop", "wait" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            LoopBlockContent(
                                block = block, variables = variables,
                                allBlocks = allBlocks, sceneNames = sceneNames, spriteNames = spriteNames,
                                soundNames = soundNames,
                                scriptId = scriptId,
                                onToggleChildCollapsed = onToggleChildCollapsed,
                                isChildCollapsed = isChildCollapsed,
                                onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                                onAddChild = onAddChild, onRemoveChild = onRemoveChild,
                                onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr,
                                onUpdateChild = onUpdateChild
                            )
                        }
                        "sim_modify" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            ModifyBlockContent(
                                block = block, variables = variables,
                                allBlocks = allBlocks,
                                onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                                onAddChild = onAddChild, onRemoveChild = onRemoveChild,
                                onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr
                            )
                        }
                        "sim_physics" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            PhysicsBlockContent(block = block, variables = variables,
                                onParamChange = onParamChange, onOpenExpr = onOpenExpr)
                        }
                        "sim_hitbox" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            HitboxBlockContent(
                                block = block, variables = variables,
                                onOpenExpr = onOpenExpr,
                                onOpenHitboxEditor = if (onOpenHitboxEditor != null) {{ onOpenHitboxEditor(block) }} else null
                            )
                        }
                        "anim_play" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            AnimPlayBlockContent(
                                block = block, variables = variables,
                                spriteNames = spriteNames,
                                onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                                onOpenAnimEditor = if (onOpenAnimEditor != null) {{ onOpenAnimEditor(block) }} else null
                            )
                        }
                        "physics_toggle" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            val param = block.params["enabled"]!!
                            BoolToggle(param = param.copy(label = "Физика включена"), onChange = { onParamChange("enabled", it) })
                        }
                        "camera_toggle" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            val nameParam = block.params["name"]!!
                            ExprChip(param = nameParam, variables = variables,
                                onClick = { onOpenExpr("name", nameParam.label, nameParam.value, false) })
                            Spacer(Modifier.height(6.dp))
                            val enabledParam = block.params["enabled"]!!
                            BoolToggle(param = enabledParam.copy(label = "Камера включена"), onChange = { onParamChange("enabled", it) })
                        }
                        "sim_stop" -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "⚠ Останавливает выполнение скрипта. Блоки после этого не выполнятся.",
                                color = Warning.copy(alpha = 0.8f), fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Warning.copy(alpha = 0.08f))
                                    .padding(8.dp)
                            )
                        }
                        else -> {
                            if (block.params.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = Surface3)
                                Spacer(Modifier.height(10.dp))
                                BlockParamContent(
                                    block = block, variables = variables,
                                    sceneNames = sceneNames, spriteNames = spriteNames,
                                    soundNames = soundNames,
                                    onParamChange = onParamChange, onOpenExpr = onOpenExpr
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showContextMenu) {
        AlertDialog(
            onDismissRequest = { showContextMenu = false },
            containerColor = Surface2,
            title = { Text("Действия с блоком", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDuplicate(); showContextMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Дублировать блок")
                    }
                    if (onCopyBlock != null) {
                        Button(
                            onClick = { onCopyBlock(); showContextMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2F3E))
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Копировать блок")
                        }
                    }
                    Button(
                        onClick = { onRemove(); showContextMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Danger)
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить блок")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContextMenu = false }) {
                    Text("Отмена", color = TextSec)
                }
            }
        )
    }
}

@Composable
internal fun OpenCloseBlockCard(
    block: BlockDef,
    isOpen: Boolean,
    index: Int,
    variables: List<ProjectVar>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {},
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    indentStart: Dp = 0.dp
) {
    val accent = categoryColor(block.category)
    val isElse = block.type == "else_block"
    val isClose = block.type in setOf("if_close", "for_loop_close", "while_loop_close", "wait_close")
    val bracketSymbol = when {
        isElse  -> "} {"
        isClose -> "}"
        else    -> "{"
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(bracketSymbol, color = accent, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(categoryIcon(block.category), null, tint = accent, modifier = Modifier.size(14.dp))
                        Text(block.displayName, color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    if (!isClose && !isElse)
                        Text(block.description, color = TextSec, fontSize = 11.sp)
                }
                SmallBtn(Icons.Default.KeyboardArrowUp, enabled = canMoveUp, onClick = onMoveUp)
                SmallBtn(Icons.Default.KeyboardArrowDown, enabled = canMoveDown, onClick = onMoveDown)
                if (isOpen) {
                    SmallBtn(
                        if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        tint = accent.copy(alpha = 0.7f),
                        onClick = onToggleCollapse
                    )
                }
                SmallBtn(Icons.Default.DeleteOutline, tint = Danger.copy(0.7f), onClick = onRemove)
            }
            if (isOpen && !collapsed && block.params.isNotEmpty()) {
                HorizontalDivider(color = Surface3, modifier = Modifier.padding(horizontal = 12.dp))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BlockParamContent(block = block, variables = variables,
                        onParamChange = onParamChange, onOpenExpr = onOpenExpr)
                }
            }
        }
    }
}

@Composable
internal fun RotateModeToggle(value: String, onChange: (String) -> Unit) {
    Column {
        Text("Режим вращения", color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)) {
            listOf("instant" to "Установить", "step" to "Добавить").forEach { (mode, label) ->
                val active = value == mode
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (active) Accent.copy(0.2f) else Color.Transparent)
                        .border(if (active) 1.dp else 0.dp, if (active) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onChange(mode) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (active) Accent else TextSec, fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun BoolToggle(param: BlockParam, onChange: (String) -> Unit) {
    val isTrue = param.value == "true"
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)
        ) {
            listOf("false" to "Нет", "true" to "Да").forEach { (v, label) ->
                val active = param.value == v
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Accent.copy(0.2f) else Color.Transparent)
                        .border(if (active) 1.dp else 0.dp, if (active) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onChange(v) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (active) Accent else TextSec, fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun EncryptToggle(param: BlockParam, onChange: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isEncrypt = param.value == "true"
    val hasKey = su.SkrinVex.SkriCode.engine.SaveCrypto.hasKey(ctx, su.SkrinVex.SkriCode.engine.SimEngine.projectName)
    var showKeyVault by remember { mutableStateOf(false) }

    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)
        ) {
            listOf("false" to "Без шифрования", "true" to "Шифровать").forEach { (v, label) ->
                val active = param.value == v
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active && v == "true") Success.copy(0.15f) else if (active) Accent.copy(0.1f) else Color.Transparent)
                        .border(if (active) 1.dp else 0.dp, if (active && v == "true") Success.copy(0.6f) else if (active) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onChange(v) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (active && v == "true") Success else if (active) Accent else TextSec,
                        fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (isEncrypt && !hasKey) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Warning.copy(0.1f))
                    .border(1.dp, Warning.copy(0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = Warning, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ключ шифрования не задан", color = Warning, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { showKeyVault = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("Добавить", color = Warning, fontSize = 11.sp) }
            }
        }
    }

    if (showKeyVault) {
        KeyVaultScreen(
            currentProjectName = su.SkrinVex.SkriCode.engine.SimEngine.projectName,
            onDismiss = { showKeyVault = false }
        )
    }
}

@Composable
internal fun MoveModeToggle(value: String, onChange: (String) -> Unit) {
    val isStep = value == "step"
    Column {
        Text("Режим перемещения", color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            listOf("instant" to "Моментальный", "step" to "Шаг").forEach { (mode, label) ->
                val active = value == mode
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Accent.copy(0.2f) else Color.Transparent)
                        .border(if (active) 1.dp else 0.dp, if (active) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onChange(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (active) Accent else TextSec, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        val hint = if (isStep)
            "Шаг: каждый раз прибавляет X/Y к текущей позиции"
        else
            "Моментальный: перемещает объект точно в указанную позицию"
        Text(hint, color = TextSec.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun OperatorSelector(param: BlockParam, onChange: (String) -> Unit) {
    val operators = listOf(
        "==" to "равно",
        "!=" to "не равно", 
        ">" to "больше",
        "<" to "меньше",
        ">=" to "больше или равно",
        "<=" to "меньше или равно"
    )
    
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            operators.forEach { (op, label) ->
                val active = param.value == op
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) Accent.copy(0.2f) else Surface3)
                        .border(if (active) 1.dp else 0.dp, if (active) Accent else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onChange(op) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(op, color = if (active) Accent else TextSec, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (param.hint.isNotBlank()) {
            Text(param.hint, color = TextSec.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun IfBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    allBlocks: List<BlockDef> = emptyList(),
    sceneNames: List<String> = emptyList(),
    spriteNames: List<String> = emptyList(),
    soundNames: List<String> = emptyList(),
    scriptId: String = "",
    onToggleChildCollapsed: ((blockId: String) -> Boolean)? = null,
    isChildCollapsed: ((blockId: String) -> Boolean)? = null,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onAddChild: (branch: String, type: String) -> Unit,
    onRemoveChild: (branch: String, childIndex: Int) -> Unit,
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit,
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onOpenChildPositionPicker: ((branch: String, childIndex: Int, block: BlockDef) -> Unit)? = null,
    onOpenChildAnimEditor: ((branch: String, childIndex: Int, block: BlockDef) -> Unit)? = null,
    onUpdateChild: ((branch: String, childIndex: Int, updated: BlockDef) -> Unit)? = null
) {

    // Левое значение
    val ops = listOf("==", "!=", ">", "<", ">=", "<=")
    val opLabels = mapOf("==" to "равно", "!=" to "≠", ">" to "больше", "<" to "меньше", ">=" to "≥", "<=" to "≤")

    val leftParam = block.params["left"]!!
    ExprChip(param = leftParam, variables = variables,
        onClick = { onOpenExpr("left", leftParam.label, leftParam.value, false) })
    Spacer(Modifier.height(6.dp))

    // Оператор
    val currentOp = block.params["op"]?.value ?: "=="
    Column {
        Text("Оператор", color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ops.forEach { op ->
                val active = currentOp == op
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Warning.copy(0.2f) else Surface3)
                        .border(1.dp, if (active) Warning else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onParamChange("op", op) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(op, color = if (active) Warning else TextPrim, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(opLabels[op] ?: "", color = if (active) Warning.copy(0.8f) else TextSec, fontSize = 9.sp)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    // Правое значение
    val rightParam = block.params["right"]!!
    ExprChip(param = rightParam, variables = variables,
        onClick = { onOpenExpr("right", rightParam.label, rightParam.value, false) })
    Spacer(Modifier.height(10.dp))

    // Ветки
    IfBranchSection(
        label = "Если истина",
        color = Color(0xFF4ADE80),
        branch = "then",
        blocks = block.children["then"] ?: emptyList(),
        variables = variables,
        scriptId = scriptId,
        onToggleChildCollapsed = onToggleChildCollapsed,
        isChildCollapsed = isChildCollapsed,
        onAddChild = onAddChild, onRemoveChild = onRemoveChild,
        onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr,
        onOpenChildPositionPicker = onOpenChildPositionPicker,
        onOpenChildAnimEditor = onOpenChildAnimEditor,
        onUpdateChild = if (onUpdateChild != null) {{ ci, upd -> onUpdateChild("then", ci, upd) }} else null,
        allBlocks = allBlocks, sceneNames = sceneNames, spriteNames = spriteNames, soundNames = soundNames
    )
    Spacer(Modifier.height(6.dp))
    IfBranchSection(
        label = "Если ложь",
        color = TextSec,
        branch = "else",
        blocks = block.children["else"] ?: emptyList(),
        variables = variables,
        scriptId = scriptId,
        onToggleChildCollapsed = onToggleChildCollapsed,
        isChildCollapsed = isChildCollapsed,
        onAddChild = onAddChild, onRemoveChild = onRemoveChild,
        onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr,
        onOpenChildPositionPicker = onOpenChildPositionPicker,
        onOpenChildAnimEditor = onOpenChildAnimEditor,
        onUpdateChild = if (onUpdateChild != null) {{ ci, upd -> onUpdateChild("else", ci, upd) }} else null,
        allBlocks = allBlocks, sceneNames = sceneNames, spriteNames = spriteNames, soundNames = soundNames
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IfBranchSection(
    label: String,
    color: Color,
    branch: String,
    blocks: List<BlockDef>,
    variables: List<ProjectVar>,
    scriptId: String = "",
    onToggleChildCollapsed: ((blockId: String) -> Boolean)? = null,
    isChildCollapsed: ((blockId: String) -> Boolean)? = null,
    onAddChild: (branch: String, type: String) -> Unit,
    onRemoveChild: (branch: String, childIndex: Int) -> Unit,
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit,
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onOpenChildPositionPicker: ((branch: String, childIndex: Int, block: BlockDef) -> Unit)? = null,
    onOpenChildAnimEditor: ((branch: String, childIndex: Int, block: BlockDef) -> Unit)? = null,
    onUpdateChild: ((childIndex: Int, updated: BlockDef) -> Unit)? = null,
    allBlocks: List<BlockDef> = emptyList(),
    sceneNames: List<String> = emptyList(),
    spriteNames: List<String> = emptyList(),
    soundNames: List<String> = emptyList()
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Surface3)
            .border(1.dp, color.copy(0.35f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showPicker = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "Добавить блок", tint = TextSec, modifier = Modifier.size(16.dp))
            }
        }
        if (blocks.isEmpty()) {
            Text("Нет блоков — нажми +", color = TextSec.copy(0.5f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, start = 11.dp))
        } else {
            Spacer(Modifier.height(6.dp))
            blocks.forEachIndexed { ci, child ->
                val collapsedState = remember(scriptId, child.id) { mutableStateOf(isChildCollapsed?.invoke(child.id) ?: false) }
                ChildBlockRow(
                    block = child, childIndex = ci, branch = branch,
                    variables = variables,
                    allBlocks = allBlocks,
                    sceneNames = sceneNames,
                    spriteNames = spriteNames,
                    soundNames = soundNames,
                    accentColor = color,
                    collapsed = collapsedState.value,
                    onToggleCollapse = {
                        val next = onToggleChildCollapsed?.invoke(child.id) ?: !collapsedState.value
                        collapsedState.value = next
                    },
                    onRemove = { onRemoveChild(branch, ci) },
                    onParamChange = { k, v -> onChildParamChange(branch, ci, k, v) },
                    onOpenExpr = { k, lbl, cur, isId -> onOpenChildExpr(branch, ci, k, lbl, cur, isId) },
                    onOpenPositionPicker = if (onOpenChildPositionPicker != null) {{ onOpenChildPositionPicker(branch, ci, child) }} else null,
                    onOpenAnimEditor = if (onOpenChildAnimEditor != null) {{ onOpenChildAnimEditor(branch, ci, child) }} else null,
                    onAddGrandchild = { childBranch, type ->
                        val newBlock = BlockRegistry.create(type) ?: return@ChildBlockRow
                        val newChildren = child.children.toMutableMap()
                        newChildren[childBranch] = (newChildren[childBranch] ?: emptyList()) + newBlock
                        onUpdateChild?.invoke(ci, child.copy(children = newChildren))
                    },
                    onRemoveGrandchild = { childBranch, gci ->
                        val newChildren = child.children.toMutableMap()
                        newChildren[childBranch] = (newChildren[childBranch] ?: emptyList()).toMutableList().also { it.removeAt(gci) }
                        onUpdateChild?.invoke(ci, child.copy(children = newChildren))
                    },
                    onGrandchildParamChange = { childBranch, gci, k, v ->
                        val grandchildren = (child.children[childBranch] ?: emptyList()).toMutableList()
                        if (gci < grandchildren.size) {
                            grandchildren[gci] = grandchildren[gci].withParam(k, v)
                            val newChildren = child.children.toMutableMap()
                            newChildren[childBranch] = grandchildren
                            onUpdateChild?.invoke(ci, child.copy(children = newChildren))
                        }
                    },
                    onOpenGrandchildExpr = { childBranch, gci, k, lbl, cur, isId ->
                        onOpenChildExpr(branch, ci, k, lbl, cur, isId)
                    }
                )
                if (ci < blocks.size - 1) Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showPicker) {
        BlockPickerSheet(
            onDismiss = { showPicker = false },
            onPick = { type -> onAddChild(branch, type); showPicker = false }
        )
    }
}

@Composable
internal fun ChildBlockRow(
    block: BlockDef, childIndex: Int, branch: String,
    variables: List<ProjectVar>,
    allBlocks: List<BlockDef> = emptyList(),
    sceneNames: List<String> = emptyList(),
    spriteNames: List<String> = emptyList(),
    soundNames: List<String> = emptyList(),
    accentColor: Color,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onRemove: () -> Unit,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onOpenPositionPicker: (() -> Unit)? = null,
    onOpenAnimEditor: (() -> Unit)? = null,
    onAddGrandchild: (childBranch: String, type: String) -> Unit = { _, _ -> },
    onRemoveGrandchild: (childBranch: String, grandchildIndex: Int) -> Unit = { _, _ -> },
    onGrandchildParamChange: (childBranch: String, grandchildIndex: Int, key: String, value: String) -> Unit = { _, _, _, _ -> },
    onOpenGrandchildExpr: (childBranch: String, grandchildIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit = { _, _, _, _, _, _ -> }
) {
    val accent = categoryColor(block.category)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .border(1.dp, accent.copy(0.2f), RoundedCornerShape(8.dp))
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(categoryIcon(block.category), null, tint = accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(block.displayName, color = TextPrim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (onOpenPositionPicker != null && block.params.containsKey("x") && block.params.containsKey("y")) {
                    SmallBtn(Icons.Default.OpenWith, tint = Accent.copy(0.7f), onClick = onOpenPositionPicker)
                }
                SmallBtn(if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, onClick = onToggleCollapse)
                SmallBtn(Icons.Default.DeleteOutline, tint = Danger.copy(0.7f), onClick = { showDeleteConfirm = true })
            }
            if (!collapsed && block.params.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                if (block.type == "anim_play") {
                    AnimPlayBlockContent(
                        block = block, variables = variables,
                        spriteNames = spriteNames,
                        onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                        onOpenAnimEditor = onOpenAnimEditor
                    )
                } else {
                    BlockParamContent(
                        block = block, variables = variables,
                        sceneNames = sceneNames, spriteNames = spriteNames,
                        soundNames = soundNames,
                        onParamChange = onParamChange, onOpenExpr = onOpenExpr
                    )
                }
            }
            // Рендеринг children для специальных блоков
            if (!collapsed && block.children.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                when (block.type) {
                    "sim_modify" -> ModifyBlockContent(
                        block = block, variables = variables, allBlocks = allBlocks,
                        onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                        onAddChild = onAddGrandchild, onRemoveChild = onRemoveGrandchild,
                        onChildParamChange = onGrandchildParamChange, onOpenChildExpr = onOpenGrandchildExpr
                    )
                    "if_block" -> IfBlockContent(
                        block = block, variables = variables,
                        allBlocks = allBlocks,
                        onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                        onAddChild = onAddGrandchild, onRemoveChild = onRemoveGrandchild,
                        onChildParamChange = onGrandchildParamChange, onOpenChildExpr = onOpenGrandchildExpr
                    )
                    "for_loop", "while_loop", "wait" -> LoopBlockContent(
                        block = block, variables = variables,
                        allBlocks = allBlocks,
                        onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                        onAddChild = onAddGrandchild, onRemoveChild = onRemoveGrandchild,
                        onChildParamChange = onGrandchildParamChange, onOpenChildExpr = onOpenGrandchildExpr
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить блок?", color = TextPrim) },
            text = { Text("«${block.displayName}» будет удалён.", color = TextSec) },
            confirmButton = {
                Button(onClick = { onRemove(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена", color = TextSec) }
            }
        )
    }
}

@Composable
internal fun ObjectNameChip(param: BlockParam, variables: List<ProjectVar>, onClick: () -> Unit) {
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Surface3)
                .border(1.dp, Accent.copy(0.3f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                param.value.startsWith("#") -> Icons.Default.Tag
                param.value.startsWith("{") && param.value.endsWith("}") -> Icons.Default.DataObject
                variables.any { it.name == param.value } -> Icons.Default.DataObject
                else -> Icons.Default.TextFields
            }
            val iconColor = when {
                param.value.startsWith("#") -> Color(0xFFFF6B6B)
                param.value.startsWith("{") && param.value.endsWith("}") -> Warning
                variables.any { it.name == param.value } -> Warning
                else -> Accent
            }
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                param.value.ifBlank { param.hint },
                color = if (param.value.isBlank()) TextSec else TextPrim,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.Edit, null, tint = TextSec, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun ExprChip(param: BlockParam, variables: List<ProjectVar>, onClick: () -> Unit) {
    val isVar = variables.any { "{${it.name}}" == param.value || it.name == param.value }
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Surface3)
                .border(1.dp, if (isVar) Warning.copy(0.5f) else Accent.copy(0.2f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isVar) {
                Icon(Icons.Default.DataObject, null, tint = Warning, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                param.value.ifBlank { param.hint },
                color = if (param.value.isBlank()) TextSec else TextPrim,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.Edit, null, tint = TextSec, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun VarNameChip(value: String, label: String, onClick: () -> Unit) {
    Column {
        Text(label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Surface3)
                .border(1.dp, Accent.copy(0.4f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.DataObject, null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                value.ifBlank { "Нажми чтобы выбрать переменную" },
                color = if (value.isBlank()) TextSec else TextPrim,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = TextSec, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun TagNameChip(value: String, label: String, onClick: () -> Unit) {
    Column {
        Text(label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Surface3)
                .border(1.dp, Color(0xFFFF6B6B).copy(0.4f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tag, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (value.isBlank()) "Нажми чтобы выбрать тег" else "#$value",
                color = if (value.isBlank()) TextSec else TextPrim,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = TextSec, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun TableNameChip(value: String, label: String, onClick: () -> Unit) {
    val tableColor = Color(0xFF34D399)
    Column {
        Text(label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Surface3)
                .border(1.dp, tableColor.copy(0.4f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.TableChart, null, tint = tableColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (value.isBlank()) "Нажми чтобы выбрать таблицу" else value,
                color = if (value.isBlank()) TextSec else tableColor,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = TextSec, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ColorField(param: BlockParam, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val color = runCatching {
        val c = param.value.trim().trimStart('#').toLong(16)
        if (param.value.trim().trimStart('#').length == 6) Color(0xFF000000 or c) else Color(c)
    }.getOrElse { Color(0xFF4F8EF7) }

    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(Surface3)
                .border(1.dp, Accent.copy(0.2f), RoundedCornerShape(8.dp))
                .clickable { showPicker = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(color)
                .border(1.dp, Color.White.copy(0.3f), RoundedCornerShape(6.dp)))
            Text(param.value.ifBlank { "#4F8EF7" }, color = TextPrim,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Colorize, null, tint = TextSec, modifier = Modifier.size(16.dp))
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = param.value,
            onConfirm = { hex -> onChange(hex); showPicker = false },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
internal fun DirectInputField(param: BlockParam, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = param.value, onValueChange = onChange,
        label = { Text(param.label, fontSize = 12.sp) },
        placeholder = { Text(param.hint, fontSize = 12.sp, color = TextSec) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
            focusedLabelColor = Accent, unfocusedLabelColor = TextSec,
            focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent
        ),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
    )
}

@Composable
internal fun SmallBtn(icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, tint: Color = TextSec) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.25f), modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf<BlockCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val allBlocks = BlockRegistry.byCategory()
    val categories = allBlocks.keys.toList()
    
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface3))
            }
        }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("Добавить блок", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск блоков...", color = TextSec) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSec) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Surface3,
                    focusedTextColor = TextPrim,
                    unfocusedTextColor = TextPrim,
                    cursorColor = Accent
                )
            )
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("Все") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = Navy900,
                            containerColor = Surface2,
                            labelColor = TextSec
                        )
                    )
                }
                items(categories.size) { i ->
                    val cat = categories[i]
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat.label) },
                        leadingIcon = { Icon(categoryIcon(cat), null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = categoryColor(cat).copy(alpha = 0.2f),
                            selectedLabelColor = categoryColor(cat),
                            containerColor = Surface2,
                            labelColor = TextSec
                        )
                    )
                }
            }
            
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxWidth()) {
                val filtered = if (selectedCategory == null) allBlocks else allBlocks.filterKeys { it == selectedCategory }
                val searched = if (searchQuery.isBlank()) {
                    filtered
                } else {
                    filtered.mapValues { (_, metas) ->
                        metas.filter { meta ->
                            meta.displayName.contains(searchQuery, ignoreCase = true) ||
                            meta.description.contains(searchQuery, ignoreCase = true)
                        }
                    }.filterValues { it.isNotEmpty() }
                }
                
                searched.forEach { (category, metas) ->
                    if (selectedCategory == null && searchQuery.isBlank()) {
                        item {
                            Text(category.label.uppercase(), color = categoryColor(category),
                                fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
                        }
                    }
                    items(metas.size) { i ->
                        val meta = metas[i]
                        val color = categoryColor(meta.category)
                        Card(onClick = { onPick(meta.type) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface2)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(categoryIcon(meta.category), null, tint = color, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(meta.displayName, color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(meta.description, color = TextSec, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun categoryColor(cat: BlockCategory) = when (cat) {
    BlockCategory.OUTPUT     -> Accent
    BlockCategory.CONTROL    -> Warning
    BlockCategory.MATH       -> Color(0xFF60A5FA)
    BlockCategory.LOGIC      -> Color(0xFFA78BFA)
    BlockCategory.STRING     -> Color(0xFF34D399)
    BlockCategory.VARIABLE   -> Color(0xFFFB923C)
    BlockCategory.SIMULATION -> Color(0xFFF472B6)
    BlockCategory.SPRITE     -> Color(0xFFFFD700)
    BlockCategory.PHYSICS    -> Color(0xFF22D3EE)
    BlockCategory.CAMERA     -> Color(0xFF4ADE80)
    BlockCategory.AUDIO      -> Color(0xFFE879F9)
}

fun categoryIcon(cat: BlockCategory): ImageVector = when (cat) {
    BlockCategory.OUTPUT     -> Icons.Default.Output
    BlockCategory.CONTROL    -> Icons.Default.Tune
    BlockCategory.MATH       -> Icons.Default.Calculate
    BlockCategory.LOGIC      -> Icons.Default.AccountTree
    BlockCategory.STRING     -> Icons.Default.TextFields
    BlockCategory.VARIABLE   -> Icons.Default.DataObject
    BlockCategory.SIMULATION -> Icons.Default.Widgets
    BlockCategory.SPRITE     -> Icons.Default.Image
    BlockCategory.PHYSICS    -> Icons.Default.Science
    BlockCategory.CAMERA     -> Icons.Default.Videocam
    BlockCategory.AUDIO      -> Icons.Default.MusicNote
}

fun eventIcon(event: ScriptEvent): ImageVector = when (event) {
    ScriptEvent.ON_START         -> Icons.Default.PlayArrow
    ScriptEvent.ON_TAP           -> Icons.Default.TouchApp
    ScriptEvent.ON_HOLD          -> Icons.Default.PanTool
    ScriptEvent.ON_COLLISION     -> Icons.Default.Bolt
    ScriptEvent.ON_COLLISION_END -> Icons.Default.CallMissed
}

@Composable
internal fun LoopBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    allBlocks: List<BlockDef> = emptyList(),
    sceneNames: List<String> = emptyList(),
    spriteNames: List<String> = emptyList(),
    soundNames: List<String> = emptyList(),
    scriptId: String = "",
    onToggleChildCollapsed: ((blockId: String) -> Boolean)? = null,
    isChildCollapsed: ((blockId: String) -> Boolean)? = null,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onAddChild: (branch: String, type: String) -> Unit,
    onRemoveChild: (branch: String, childIndex: Int) -> Unit,
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit,
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onUpdateChild: ((branch: String, childIndex: Int, updated: BlockDef) -> Unit)? = null
) {
    block.paramOrder.forEach { key ->
        val param = block.params[key] ?: return@forEach
        ExprChip(param = param, variables = variables,
            onClick = { onOpenExpr(key, param.label, param.value, false) })
        Spacer(Modifier.height(6.dp))
    }
    Spacer(Modifier.height(8.dp))
    val bodyBlocks = block.children["body"] ?: emptyList()
    val branchLabel = when (block.type) {
        "wait" -> "Блоки таймера"
        "while_loop" -> "Тело цикла (пока)"
        else -> "Тело цикла"
    }
    IfBranchSection(
        label = branchLabel,
        color = categoryColor(BlockCategory.CONTROL),
        branch = "body",
        blocks = bodyBlocks,
        variables = variables,
        scriptId = scriptId,
        onToggleChildCollapsed = onToggleChildCollapsed,
        isChildCollapsed = isChildCollapsed,
        onAddChild = onAddChild,
        onRemoveChild = onRemoveChild,
        onChildParamChange = onChildParamChange,
        onOpenChildExpr = onOpenChildExpr,
        onUpdateChild = if (onUpdateChild != null) {{ ci, upd -> onUpdateChild("body", ci, upd) }} else null,
        allBlocks = allBlocks, sceneNames = sceneNames, spriteNames = spriteNames, soundNames = soundNames
    )
}

@Composable
internal fun PhysicsBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
) {
    val nameParam = block.params["name"]!!
    ObjectNameChip(param = nameParam, variables = variables,
        onClick = { onOpenExpr("name", nameParam.label, nameParam.value, false) })
    Spacer(Modifier.height(8.dp))

    // Гравитация
    val gravParam = block.params["gravity"]!!
    ExprChip(param = gravParam, variables = variables,
        onClick = { onOpenExpr("gravity", gravParam.label, gravParam.value, false) })
    Spacer(Modifier.height(6.dp))

    // Статический
    val staticParam = block.params["static"]!!
    BoolToggle(param = staticParam.copy(label = "Статический (нельзя двигать)"), onChange = { onParamChange("static", it) })
    Spacer(Modifier.height(6.dp))

    // Упругость
    val bounceParam = block.params["bounciness"]!!
    ExprChip(param = bounceParam, variables = variables,
        onClick = { onOpenExpr("bounciness", bounceParam.label, bounceParam.value, false) })
    Spacer(Modifier.height(6.dp))

    // Масса
    val massParam = block.params["mass"]!!
    ExprChip(param = massParam, variables = variables,
        onClick = { onOpenExpr("mass", massParam.label, massParam.value, false) })
    Spacer(Modifier.height(6.dp))

    // Начальная скорость X
    val vxParam = block.params["vx"]!!
    ExprChip(param = vxParam, variables = variables,
        onClick = { onOpenExpr("vx", vxParam.label, vxParam.value, false) })
    Spacer(Modifier.height(6.dp))

    // Начальная скорость Y
    val vyParam = block.params["vy"]!!
    ExprChip(param = vyParam, variables = variables,
        onClick = { onOpenExpr("vy", vyParam.label, vyParam.value, false) })
}

@Composable
internal fun HitboxBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onOpenHitboxEditor: (() -> Unit)? = null
) {
    val nameParam = block.params["name"]!!
    ObjectNameChip(param = nameParam, variables = variables,
        onClick = { onOpenExpr("name", nameParam.label, nameParam.value, false) })
    Spacer(Modifier.height(8.dp))

    val typeVal = block.params["type"]?.value ?: "auto"
    val pointsVal = block.params["points"]?.value ?: ""
    val pointCount = if (pointsVal.isBlank()) 0 else pointsVal.split(";").count { it.isNotBlank() }
    val physicsColor = Color(0xFF22D3EE)

    if (typeVal == "manual" && pointCount > 0) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(physicsColor.copy(0.1f))
                .border(1.dp, physicsColor.copy(0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Hexagon, null, tint = physicsColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ручной хитбокс: $pointCount точек", color = physicsColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (onOpenHitboxEditor != null) {
                TextButton(onClick = onOpenHitboxEditor, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Изменить", color = physicsColor, fontSize = 12.sp)
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(physicsColor.copy(0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CropFree, null, tint = physicsColor.copy(0.7f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Авто (по размеру объекта)", color = physicsColor.copy(0.8f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (onOpenHitboxEditor != null) {
                TextButton(onClick = onOpenHitboxEditor, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Нарисовать", color = physicsColor, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun AnimPlayBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    spriteNames: List<String>,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onOpenAnimEditor: (() -> Unit)? = null
) {
    val nameParam = block.params["name"]
    if (nameParam != null) {
        ObjectNameChip(param = nameParam, variables = variables,
            onClick = { onOpenExpr("name", nameParam.label, nameParam.value, false) })
        Spacer(Modifier.height(8.dp))
    }

    val spriteName = block.params["sprite"]?.value ?: ""
    val cols = block.params["cols"]?.value ?: "4"
    val rows = block.params["rows"]?.value ?: "1"
    val startFrame = block.params["startFrame"]?.value ?: "0"
    val endFrame = block.params["endFrame"]?.value ?: "0"
    val fps = block.params["fps"]?.value ?: "12"
    val animColor = Color(0xFFF59E0B)

    val offX = block.params["offsetX"]?.value ?: "0"
    val offY = block.params["offsetY"]?.value ?: "0"

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Surface3.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onOpenAnimEditor != null) { onOpenAnimEditor?.invoke() }
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Movie, null, tint = animColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Редактор анимации спрайт-листа", color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Сетка, смещения, кадры, FPS и предпросмотр", color = TextSec, fontSize = 11.sp)
                }
                if (onOpenAnimEditor != null) {
                    Button(
                        onClick = onOpenAnimEditor,
                        colors = ButtonDefaults.buttonColors(containerColor = animColor),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.Tune, null, tint = Navy900, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Настроить", color = Navy900, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(color = Surface2, shape = RoundedCornerShape(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Image, null, tint = if (spriteName.isNotBlank()) Accent else Danger, modifier = Modifier.size(12.dp))
                        Text(
                            if (spriteName.isNotBlank()) spriteName else "Нет спрайта",
                            color = if (spriteName.isNotBlank()) TextPrim else Danger,
                            fontSize = 11.sp
                        )
                    }
                }
                Surface(color = Surface2, shape = RoundedCornerShape(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.GridOn, null, tint = TextSec, modifier = Modifier.size(12.dp))
                        Text("${cols}×$rows", color = TextPrim, fontSize = 11.sp)
                    }
                }
                Surface(color = Surface2, shape = RoundedCornerShape(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.ViewCarousel, null, tint = TextSec, modifier = Modifier.size(12.dp))
                        val frameText = if (endFrame == "0" || endFrame.isBlank()) "0..все" else "$startFrame..$endFrame"
                        Text(frameText, color = TextPrim, fontSize = 11.sp)
                    }
                }
                Surface(color = Surface2, shape = RoundedCornerShape(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Speed, null, tint = animColor, modifier = Modifier.size(12.dp))
                        Text("$fps FPS", color = animColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if ((offX != "0" && offX.isNotBlank()) || (offY != "0" && offY.isNotBlank())) {
                    Surface(color = Surface2, shape = RoundedCornerShape(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Tune, null, tint = TextSec, modifier = Modifier.size(12.dp))
                            Text("+$offX,+$offY", color = TextSec, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    val spriteParam = block.params["sprite"]
    if (spriteParam != null) {
        SpriteChip(param = spriteParam, spriteNames = spriteNames, onChange = { onParamChange("sprite", it) })
        Spacer(Modifier.height(6.dp))
    }
    val loopParam = block.params["loop"]
    if (loopParam != null) {
        BoolToggle(param = loopParam.copy(label = "Зациклить анимацию"), onChange = { onParamChange("loop", it) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModifyBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    allBlocks: List<BlockDef>,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onAddChild: (branch: String, type: String) -> Unit,
    onRemoveChild: (branch: String, childIndex: Int) -> Unit,
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit,
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
) {
    // Имя объекта
    val nameParam = block.params["name"]!!
    ObjectNameChip(param = nameParam, variables = variables,
        onClick = { onOpenExpr("name", nameParam.label, nameParam.value, false) })
    Spacer(Modifier.height(10.dp))

    // Список свойств
    val props = block.children["props"] ?: emptyList()
    var showPropPicker by remember { mutableStateOf(false) }
    var pendingPropKey by remember { mutableStateOf<String?>(null) }
    
    // Устанавливаем параметр prop после добавления нового блока
    LaunchedEffect(props.size, pendingPropKey) {
        pendingPropKey?.let { propKey ->
            val lastIndex = props.size - 1
            if (lastIndex >= 0) {
                onChildParamChange("props", lastIndex, "prop", propKey)
                pendingPropKey = null
            }
        }
    }
    
    // Определяем доступные свойства на основе имени объекта
    val objectName = nameParam.value.trim()
    val availableProps = remember(objectName, allBlocks) {
        when {
            objectName.isBlank() -> emptyList()
            else -> {
                // Ищем блок создания этого объекта
                val creationBlock = allBlocks.find { 
                    (it.type == "sim_create" || it.type == "sim_text" || it.type == "sim_joystick") &&
                    it.params["name"]?.value == objectName
                }
                val hasPhysics = allBlocks.any {
                    it.type == "sim_physics" && it.params["name"]?.value == objectName
                }
                
                when (creationBlock?.type) {
                    "sim_create" -> buildList {
                        addAll(listOf(
                            "x" to "X позиция", "y" to "Y позиция",
                            "width" to "Ширина", "height" to "Высота",
                            "radius" to "Скругление", "color" to "Цвет",
                            "visible" to "Видимость", "rotation" to "Вращение",
                            "sprite" to "Спрайт", "spriteAlpha" to "Прозрачность спрайта",
                            "spriteScaleX" to "Масштаб спрайта X", "spriteScaleY" to "Масштаб спрайта Y"
                        ))
                        if (hasPhysics) addAll(listOf(
                            "physics_enabled" to "Физика вкл/выкл",
                            "physics_gravity" to "Гравитация",
                            "physics_static" to "Статический",
                            "physics_bounciness" to "Упругость",
                            "physics_mass" to "Масса",
                            "physics_vx" to "Скорость X",
                            "physics_vy" to "Скорость Y"
                        ))
                    }
                    "sim_sprite" -> buildList {
                        addAll(listOf(
                            "x" to "X позиция", "y" to "Y позиция",
                            "width" to "Ширина", "height" to "Высота",
                            "visible" to "Видимость", "rotation" to "Вращение",
                            "sprite" to "Спрайт", "spriteAlpha" to "Прозрачность спрайта",
                            "spriteScaleX" to "Масштаб спрайта X", "spriteScaleY" to "Масштаб спрайта Y"
                        ))
                        if (hasPhysics) addAll(listOf(
                            "physics_enabled" to "Физика вкл/выкл",
                            "physics_gravity" to "Гравитация",
                            "physics_static" to "Статический",
                            "physics_bounciness" to "Упругость",
                            "physics_mass" to "Масса",
                            "physics_vx" to "Скорость X",
                            "physics_vy" to "Скорость Y"
                        ))
                    }
                    "sim_text" -> listOf(
                        "x" to "X позиция", "y" to "Y позиция",
                        "width" to "Ширина", "height" to "Высота",
                        "visible" to "Видимость", "rotation" to "Вращение",
                        "label" to "Текст", "fontSize" to "Размер шрифта",
                        "bold" to "Жирный", "textColor" to "Цвет текста"
                    )
                    "sim_joystick" -> listOf(
                        "x" to "X позиция", "y" to "Y позиция",
                        "baseRadius" to "Радиус базы", "knobRadius" to "Радиус ручки",
                        "baseColor" to "Цвет базы", "knobColor" to "Цвет ручки",
                        "speed" to "Скорость", "directional" to "Поворот по направлению"
                    )
                    else -> {
                        // Проверяем — может это камера
                        val isCam = allBlocks.any { it.type == "sim_camera" && it.params["name"]?.value == objectName }
                        if (isCam) listOf(
                            "target"    to "Объект слежения",
                            "smoothing" to "Плавность",
                            "enabled"   to "Включена"
                        ) else emptyList()
                    }
                }
            }
        }
    }
    
    val usedProps = props.mapNotNull { it.params["prop"]?.value }.toSet()
    val selectableProps = availableProps.filter { it.first !in usedProps }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Surface3)
            .border(1.dp, Accent.copy(0.35f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = Accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text("Свойства (${props.size})", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (selectableProps.isNotEmpty()) {
                IconButton(onClick = { showPropPicker = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Add, "Добавить свойство", tint = Accent, modifier = Modifier.size(16.dp))
                }
            }
        }
        
        when {
            objectName.isBlank() -> {
                Text("Сначала укажи имя объекта", color = Warning.copy(0.7f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            availableProps.isEmpty() -> {
                Text("Объект «$objectName» не найден в скрипте", color = Danger.copy(0.7f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            props.isEmpty() -> {
                Text("Нет свойств — нажми + чтобы добавить", color = TextSec.copy(0.5f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            else -> {
                Spacer(Modifier.height(6.dp))
                props.forEachIndexed { ci, prop ->
                    ModifyPropRow(
                        prop = prop, childIndex = ci,
                        variables = variables,
                        onRemove = { onRemoveChild("props", ci) },
                        onParamChange = { k, v -> onChildParamChange("props", ci, k, v) },
                        onOpenExpr = { k, lbl, cur, isId -> onOpenChildExpr("props", ci, k, lbl, cur, isId) }
                    )
                    if (ci < props.size - 1) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (showPropPicker) {
        ModalBottomSheet(onDismissRequest = { showPropPicker = false }, containerColor = Surface1,
            dragHandle = {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface3))
                }
            }
        ) {
            Text("Выбери свойство", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(selectableProps.size) { i ->
                    val (propKey, propLabel) = selectableProps[i]
                    Card(onClick = {
                        onAddChild("props", "modify_prop")
                        pendingPropKey = propKey
                        showPropPicker = false
                    },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface2)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(propLabel, color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModifyPropRow(
    prop: BlockDef, childIndex: Int,
    variables: List<ProjectVar>,
    onRemove: () -> Unit,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
) {
    val propKey = prop.params["prop"]?.value ?: ""
    val propLabel = when (propKey) {
        "x" -> "X позиция"
        "y" -> "Y позиция"
        "width" -> "Ширина"
        "height" -> "Высота"
        "radius" -> "Скругление"
        "color" -> "Цвет"
        "visible" -> "Видимость"
        "rotation" -> "Вращение"
        "label" -> "Текст"
        "fontSize" -> "Размер шрифта"
        "bold" -> "Жирный"
        "textColor" -> "Цвет текста"
        "baseRadius" -> "Радиус базы"
        "knobRadius" -> "Радиус ручки"
        "baseColor" -> "Цвет базы"
        "knobColor" -> "Цвет ручки"
        "speed" -> "Скорость"
        "directional" -> "Поворот по направлению"
        "physics_enabled" -> "Физика вкл/выкл"
        "physics_gravity" -> "Гравитация"
        "physics_static" -> "Статический"
        "physics_bounciness" -> "Упругость"
        "physics_mass" -> "Масса"
        "physics_vx" -> "Скорость X"
        "physics_vy" -> "Скорость Y"
        "target"    -> "Объект слежения"
        "smoothing" -> "Плавность камеры"
        else -> propKey
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .border(1.dp, Accent.copy(0.2f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tune, null, tint = Accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(propLabel, color = TextPrim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            SmallBtn(Icons.Default.DeleteOutline, tint = Danger.copy(0.7f), onClick = onRemove)
        }
        Spacer(Modifier.height(6.dp))
        
        val valueParam = prop.params["value"] ?: BlockParam("", "Значение", "")
        when (propKey) {
            "color", "baseColor", "knobColor", "textColor" ->
                ColorField(param = valueParam, onChange = { onParamChange("value", it) })
            "visible", "bold", "directional", "physics_enabled", "physics_static", "enabled" ->
                BoolToggle(param = valueParam.copy(label = "Значение"), onChange = { onParamChange("value", it) })
            else ->
                ExprChip(param = valueParam.copy(label = "Значение"), variables = variables,
                    onClick = { onOpenExpr("value", "Значение", valueParam.value, false) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SceneTabsRow(
    scenes: List<su.SkrinVex.SkriCode.data.Scene>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0D1120))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(Icons.Default.Layers, null, tint = TextSec, modifier = Modifier.size(14.dp))
        scenes.forEach { scene ->
            val isActive = scene.id == activeId
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) Color(0xFF1E2A4A) else Color.Transparent)
                    .border(if (isActive) 1.dp else 0.dp, if (isActive) Accent.copy(0.5f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .combinedClickable(
                        onClick = { onSelect(scene.id) },
                        onLongClick = { renamingId = scene.id; renameText = scene.name }
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(scene.name, color = if (isActive) Accent else TextSec, fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Add, "Добавить сцену", tint = TextSec, modifier = Modifier.size(16.dp))
        }
    }

    // Диалог переименования/удаления сцены
    renamingId?.let { id ->
        val scene = scenes.find { it.id == id } ?: return@let
        AlertDialog(
            onDismissRequest = { renamingId = null },
            containerColor = Surface2,
            title = { Text("Сцена «${scene.name}»", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText, onValueChange = { renameText = it },
                        label = { Text("Название") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF2A2F3E),
                            focusedLabelColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent
                        )
                    )
                    if (scenes.size > 1) {
                        TextButton(
                            onClick = { onDelete(id); renamingId = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Danger)
                        ) { Text("Удалить сцену") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onRename(id, renameText.trim().ifBlank { scene.name }); renamingId = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("OK", color = Color.Black) }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Отмена", color = TextSec) } }
        )
    }
}

@Composable
private fun SimSettingsDialog(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var debugMode by remember { mutableStateOf(ThemeManager.debugMode) }
    var showObjectsInPicker by remember { mutableStateOf(ThemeManager.showObjectsInPicker) }
    var showHitboxes by remember { mutableStateOf(ThemeManager.showHitboxes) }
    var showKeyVault by remember { mutableStateOf(false) }

    @Composable
    fun SettingRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)
                .clickable { onToggle(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrim, fontSize = 14.sp)
                Text(subtitle, color = TextSec, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Navy900, checkedTrackColor = Accent))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Settings, null, tint = Accent) },
        title = { Text("Настройки симуляции", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingRow("Режим отладки", "Сетка, логи, кнопка закрытия", debugMode) { debugMode = it }
                SettingRow("Объекты в позиционировщике", "Показывать все объекты при перемещении", showObjectsInPicker) { showObjectsInPicker = it }
                SettingRow("Показать хитбоксы", "Отображать хитбоксы объектов в симуляции", showHitboxes) { showHitboxes = it }

                val hasKey = su.SkrinVex.SkriCode.engine.SaveCrypto.hasKey(ctx, su.SkrinVex.SkriCode.engine.SimEngine.projectName)
                OutlinedButton(
                    onClick = { showKeyVault = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasKey) Success else Warning),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (hasKey) Success.copy(0.5f) else Warning.copy(0.5f))
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasKey) "Хранилище ключей (ключ задан)" else "Хранилище ключей (ключ не задан)")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ThemeManager.setDebugMode(ctx, debugMode)
                    ThemeManager.setShowObjectsInPicker(ctx, showObjectsInPicker)
                    ThemeManager.setShowHitboxes(ctx, showHitboxes)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("OK", color = Navy900) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )

    if (showKeyVault) {
        KeyVaultScreen(
            currentProjectName = su.SkrinVex.SkriCode.engine.SimEngine.projectName,
            onDismiss = { showKeyVault = false }
        )
    }
}

@Composable
internal fun SceneChip(param: BlockParam, sceneNames: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface3)
                    .border(1.dp, Accent.copy(0.3f), RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Layers, null, tint = Accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    param.value.ifBlank { "Выбрать сцену..." },
                    color = if (param.value.isBlank()) TextSec else TextPrim,
                    fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = TextSec, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface2)
            ) {
                sceneNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = if (name == param.value) Accent else TextPrim, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Layers, null, tint = if (name == param.value) Accent else TextSec, modifier = Modifier.size(16.dp)) },
                        onClick = { onChange(name); expanded = false }
                    )
                }
                if (sceneNames.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Нет сцен", color = TextSec, fontSize = 13.sp) },
                        onClick = { expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SpriteChip(param: BlockParam, spriteNames: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface3)
                    .border(1.dp, su.SkrinVex.SkriCode.ui.theme.Warning.copy(0.4f), RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Image, null, tint = su.SkrinVex.SkriCode.ui.theme.Warning, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    param.value.ifBlank { "Выбрать спрайт..." },
                    color = if (param.value.isBlank()) TextSec else TextPrim,
                    fontSize = 13.sp, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = TextSec, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface2)
            ) {
                spriteNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = if (name == param.value) su.SkrinVex.SkriCode.ui.theme.Warning else TextPrim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Image, null, tint = if (name == param.value) su.SkrinVex.SkriCode.ui.theme.Warning else TextSec, modifier = Modifier.size(16.dp)) },
                        onClick = { onChange(name); expanded = false }
                    )
                }
                if (spriteNames.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Нет спрайтов в проекте", color = TextSec, fontSize = 13.sp) },
                        onClick = { expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SoundChip(param: BlockParam, soundNames: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(param.label, color = TextSec, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface3)
                    .border(1.dp, Color(0xFFE879F9).copy(0.4f), RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color(0xFFE879F9), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    param.value.ifBlank { "Выбрать звук..." },
                    color = if (param.value.isBlank()) TextSec else TextPrim,
                    fontSize = 13.sp, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = TextSec, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface2)
            ) {
                soundNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, color = if (name == param.value) Color(0xFFE879F9) else TextPrim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = if (name == param.value) Color(0xFFE879F9) else TextSec, modifier = Modifier.size(16.dp)) },
                        onClick = { onChange(name); expanded = false }
                    )
                }
                if (soundNames.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Нет звуков в проекте", color = TextSec, fontSize = 13.sp) },
                        onClick = { expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
fun ProjectSettingsDialog(
    orientation: su.SkrinVex.SkriCode.data.ProjectOrientation,
    onOrientationChange: (su.SkrinVex.SkriCode.data.ProjectOrientation) -> Unit,
    packageName: String = "",
    onPackageNameChange: (String) -> Unit = {},
    onDismiss: () -> Unit,
    vm: EditorViewModel? = null,
    projectName: String = ""
) {
    var showBuildSettings by remember { mutableStateOf(false) }
    var showLogSettings by remember { mutableStateOf(false) }

    if (showBuildSettings && vm != null) {
        ApkBuildSettingsDialog(vm = vm, projectName = projectName, onDismiss = { showBuildSettings = false })
        return
    }
    if (showLogSettings && vm != null) {
        LogSettingsDialog(vm = vm, onDismiss = { showLogSettings = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Tune, null, tint = Accent) },
        title = { Text("Настройки проекта", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ориентация экрана", color = TextSec, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    su.SkrinVex.SkriCode.data.ProjectOrientation.entries.forEach { o ->
                        val selected = orientation == o
                        val label = if (o == su.SkrinVex.SkriCode.data.ProjectOrientation.PORTRAIT) "Портретная" else "Ландшафтная"
                        val icon = if (o == su.SkrinVex.SkriCode.data.ProjectOrientation.PORTRAIT) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape
                        FilterChip(
                            selected = selected,
                            onClick = { onOrientationChange(o) },
                            label = { Text(label, fontSize = 13.sp) },
                            leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.2f),
                                selectedLabelColor = Accent,
                                selectedLeadingIconColor = Accent
                            )
                        )
                    }
                }
                if (vm != null) {
                    OutlinedButton(
                        onClick = { showBuildSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Success.copy(0.5f))
                    ) {
                        Icon(Icons.Default.Android, null, tint = Success, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Настройки сборки APK", color = Success)
                    }
                    OutlinedButton(
                        onClick = { showLogSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(0.5f))
                    ) {
                        Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Настройки логов", color = Accent)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Готово", color = Navy900)
            }
        }
    )
}

@Composable
private fun ApkBuildSettingsDialog(
    vm: EditorViewModel,
    projectName: String,
    onDismiss: () -> Unit
) {
    val state by vm.state.collectAsState()
    val packageName = state.packageName
    val appLabel = state.appLabel
    val versionName = state.versionName
    val versionCode = state.versionCode
    val iconFileName = state.iconFileName
    val sprites = state.sprites

    var pkgField by remember(packageName) { mutableStateOf(packageName) }
    var labelField by remember(appLabel) { mutableStateOf(appLabel) }
    var verNameField by remember(versionName) { mutableStateOf(versionName) }
    var verCodeField by remember(versionCode) { mutableStateOf(versionCode.toString()) }
    var showIconPicker by remember { mutableStateOf(false) }

    val pkgValid = pkgField.isBlank() || isValidPackageName(pkgField)
    val verCodeInt = verCodeField.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Android, null, tint = Success) },
        title = { Text("Настройки сборки APK", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Имя пакета
                OutlinedTextField(
                    value = pkgField,
                    onValueChange = { pkgField = it; vm.setPackageName(it) },
                    label = { Text("Имя пакета") },
                    placeholder = { Text("com.example.mygame", color = TextSec) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = pkgField.isNotBlank() && (!pkgValid || pkgField.startsWith("su.SkrinVex", ignoreCase = true)),
                    supportingText = {
                        when {
                            pkgField.startsWith("su.SkrinVex", ignoreCase = true) -> 
                                Text("Еба ты гений, так нельзя", color = Danger, fontSize = 11.sp)
                            pkgField.isNotBlank() && !pkgValid -> 
                                Text("Формат: com.example.app или su.myname.game", color = Danger, fontSize = 11.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                        cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
                // Название приложения
                OutlinedTextField(
                    value = labelField,
                    onValueChange = { labelField = it; vm.setAppLabel(it) },
                    label = { Text("Название приложения") },
                    placeholder = { Text(projectName, color = TextSec) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                        cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
                // Версия и код версии
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = verNameField,
                        onValueChange = { verNameField = it; vm.setVersionName(it) },
                        label = { Text("Версия") },
                        placeholder = { Text("1.0", color = TextSec) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, focusedLabelColor = Accent,
                            cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                        )
                    )
                    OutlinedTextField(
                        value = verCodeField,
                        onValueChange = { v ->
                            verCodeField = v
                            v.toIntOrNull()?.let { vm.setVersionCode(it) }
                        },
                        label = { Text("Код версии") },
                        placeholder = { Text("1", color = TextSec) },
                        singleLine = true,
                        isError = verCodeInt == null && verCodeField.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, focusedLabelColor = Accent,
                            cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                        )
                    )
                }
                // Иконка
                Text("Иконка приложения", color = TextSec, fontSize = 13.sp)
                if (sprites.isEmpty()) {
                    Text("Добавьте спрайты в проект чтобы выбрать иконку", color = TextSec, fontSize = 12.sp)
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Surface3).clickable { showIconPicker = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Image, null, tint = if (iconFileName.isBlank()) TextSec else Accent, modifier = Modifier.size(20.dp))
                        Text(
                            if (iconFileName.isBlank()) "По умолчанию (иконка SkriCode)" else iconFileName,
                            color = if (iconFileName.isBlank()) TextSec else TextPrim,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ChevronRight, null, tint = TextSec, modifier = Modifier.size(16.dp))
                    }
                }
                // Подсказка про подпись
                Text(
                    "⚠ Тестовая подпись — для публикации нужна собственная подпись.",
                    color = Warning, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            val isForbiddenPackage = pkgField.equals("su.SkrinVex.SkriCode", ignoreCase = true) ||
                                     pkgField.startsWith("su.SkrinVex", ignoreCase = true)
            Button(
                onClick = onDismiss,
                enabled = !isForbiddenPackage && (pkgField.isBlank() || pkgValid) && (verCodeInt != null),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Готово", color = Navy900)
            }
        }
    )

    if (showIconPicker) {
        AlertDialog(
            onDismissRequest = { showIconPicker = false },
            containerColor = Surface2,
            title = { Text("Выбрать иконку", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Вариант "по умолчанию"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (iconFileName.isBlank()) Accent.copy(0.15f) else Surface3)
                            .clickable { vm.setIconFileName(""); showIconPicker = false }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Android, null, tint = if (iconFileName.isBlank()) Accent else TextSec, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("По умолчанию", color = if (iconFileName.isBlank()) Accent else TextPrim, fontSize = 14.sp)
                    }
                    sprites.take(10).forEach { sprite ->
                        val selected = iconFileName == sprite.fileName
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Accent.copy(0.15f) else Surface3)
                                .clickable { vm.setIconFileName(sprite.fileName); showIconPicker = false }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Image, null, tint = if (selected) Accent else TextSec, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(sprite.name, color = if (selected) Accent else TextPrim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (selected) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIconPicker = false }) { Text("Отмена", color = TextSec) }
            }
        )
    }
}

@Composable
private fun LogSettingsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state by vm.state.collectAsState()
    val dirPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setLogDir(uri.toString())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Description, null, tint = Accent) },
        title = { Text("Настройки логов", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)
                        .clickable { vm.setEnableLogFile(!state.enableLogFile) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Записывать логи в файл", color = TextPrim, fontSize = 14.sp)
                        Text("Сохранять логи симуляции на устройство", color = TextSec, fontSize = 12.sp)
                    }
                    Switch(
                        checked = state.enableLogFile,
                        onCheckedChange = { vm.setEnableLogFile(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Navy900, checkedTrackColor = Accent)
                    )
                }
                if (state.enableLogFile) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Surface3)
                            .clickable { dirPicker.launch(
                                if (state.logDir.isNotBlank())
                                    runCatching { android.net.Uri.parse(state.logDir) }.getOrNull()
                                else null
                            ) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null,
                            tint = if (state.logDir.isBlank()) TextSec else Accent,
                            modifier = Modifier.size(20.dp))
                        Text(
                            if (state.logDir.isBlank()) "Выбрать папку для логов..."
                            else androidx.documentfile.provider.DocumentFile
                                .fromTreeUri(ctx, android.net.Uri.parse(state.logDir))
                                ?.name ?: state.logDir,
                            color = if (state.logDir.isBlank()) TextSec else TextPrim,
                            fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ChevronRight, null, tint = TextSec, modifier = Modifier.size(16.dp))
                    }
                    if (state.logDir.isNotBlank()) {
                        TextButton(
                            onClick = { vm.setLogDir("") },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("Сбросить папку", color = Danger, fontSize = 12.sp) }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)
                            .clickable { vm.setClearLogsOnStart(!state.clearLogsOnStart) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Очищать при каждом запуске", color = TextPrim, fontSize = 14.sp)
                            Text("Новый файл при каждом запуске симуляции", color = TextSec, fontSize = 12.sp)
                        }
                        Switch(
                            checked = state.clearLogsOnStart,
                            onCheckedChange = { vm.setClearLogsOnStart(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Navy900, checkedTrackColor = Accent)
                        )
                    }
                    Text("Файл: <имя_проекта>_<дата-время>.skrilogs", color = TextSec, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("Готово", color = Navy900)
            }
        }
    )
}

private fun isValidPackageName(pkg: String): Boolean =
    pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,}\$"))
