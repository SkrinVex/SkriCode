package su.SkrinVex.SkriCode.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.block.BlockRegistry
import su.SkrinVex.SkriCode.ui.theme.*
import java.util.UUID

// Блоки которые имеют смысл как setup для объекта локации
private val SETUP_BLOCK_TYPES = listOf("sim_physics", "sim_hitbox", "set_tag", "sim_rotate", "set_texture", "sim_layer", "anim_play")

/**
 * Полноэкранный редактор setup-блоков объекта локации.
 * Блоки хранятся в children["setup"] объекта.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationObjectEditorScreen(
    objectBlock: BlockDef,
    collapsedState: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    spriteNames: List<String> = emptyList(),
    projectId: String = "",
    sprites: List<su.SkrinVex.SkriCode.data.SpriteAsset> = emptyList(),
    onConfirm: (BlockDef) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    // objectBlock — первый "блок" в списке (основные свойства)
    var mainBlock by remember(objectBlock.id) { mutableStateOf(objectBlock) }
    var setupBlocks by remember(objectBlock.id) {
        mutableStateOf(objectBlock.children["setup"] ?: emptyList<BlockDef>())
    }
    var showPicker by remember { mutableStateOf(false) }
    var exprTarget by remember { mutableStateOf<ExprEditTarget?>(null) }
    var hitboxTarget by remember { mutableStateOf<Pair<Int, BlockDef>?>(null) }
    var animTarget by remember { mutableStateOf<Pair<Int, BlockDef>?>(null) }

    // Синхронизируем имя объекта в setup-блоках при изменении mainBlock
    fun syncName(newName: String) {
        setupBlocks = setupBlocks.map { b ->
            var updated = b
            if (b.params.containsKey("name") && b.params["name"]?.value != newName)
                updated = updated.withParam("name", newName)
            if (b.params.containsKey("object") && b.params["object"]?.value != newName)
                updated = updated.withParam("object", newName)
            updated
        }
    }

    fun save() {
        onConfirm(mainBlock.copy(children = mainBlock.children + ("setup" to setupBlocks)))
    }

    // Редактор выражений
    exprTarget?.let { target ->
        su.SkrinVex.SkriCode.ui.expr.ExpressionEditorScreen(
            initialValue = target.currentValue,
            paramLabel = target.paramLabel,
            variables = emptyList(),
            tags = emptyList(),
            tables = emptyList(),
            isIdentifier = target.isIdentifier,
            onConfirm = { value ->
                if (target.blockIndex == -1) {
                    // mainBlock
                    mainBlock = mainBlock.withParam(target.paramKey, value)
                    if (target.paramKey == "name") syncName(value)
                } else {
                    setupBlocks = setupBlocks.toMutableList().also { list ->
                        list[target.blockIndex] = list[target.blockIndex].withParam(target.paramKey, value)
                    }
                }
                exprTarget = null
            },
            onCreateVar = { _, _ -> }, onDeleteVar = { _, _ -> },
            onCreateTag = { _, _ -> }, onDeleteTag = { _, _ -> },
            onCreateTable = { _, _ -> }, onDeleteTable = { _, _ -> },
            onSetTableEntry = { _, _, _, _ -> }, onRemoveTableEntry = { _, _, _ -> },
            onBack = { exprTarget = null }
        )
        return
    }

    // Редактор хитбокса
    hitboxTarget?.let { (idx, block) ->
        val spriteName = mainBlock.params["sprite"]?.value?.ifBlank { null }
        val spriteAsset = if (spriteName != null) sprites.find { it.name == spriteName } else null
        val rawW = mainBlock.params["width"]?.value?.toFloatOrNull() ?: 0f
        val rawH = mainBlock.params["height"]?.value?.toFloatOrNull() ?: 0f
        val w = if (rawW > 0f) rawW else (spriteAsset?.width?.toFloat() ?: 100f)
        val h = if (rawH > 0f) rawH else (spriteAsset?.height?.toFloat() ?: 60f)
        val fakeObj = su.SkrinVex.SkriCode.engine.SimObject(
            name = mainBlock.params["name"]?.value ?: "", x = 0f, y = 0f,
            width = w, height = h,
            radius = mainBlock.params["radius"]?.value?.toFloatOrNull() ?: 0f,
            color = su.SkrinVex.SkriCode.engine.SimEngine.parseColor(mainBlock.params["color"]?.value ?: "#4F8EF7"),
            spriteName = spriteName
        )
        val existingPts = su.SkrinVex.SkriCode.engine.SimEngine.parseHitboxPoints(block.params["points"]?.value ?: "")
        HitboxEditorScreen(
            obj = fakeObj,
            initialPoints = existingPts,
            projectId = projectId,
            spriteName = fakeObj.spriteName,
            onConfirm = { pts ->
                val serialized = su.SkrinVex.SkriCode.engine.SimEngine.serializeHitboxPoints(pts)
                setupBlocks = setupBlocks.toMutableList().also { list ->
                    list[idx] = list[idx].withParam("type", if (pts.isEmpty()) "auto" else "manual")
                                         .withParam("points", serialized)
                }
                hitboxTarget = null
            },
            onDismiss = { hitboxTarget = null }
        )
        return
    }

    // Редактор анимации спрайт-листа
    animTarget?.let { (idx, block) ->
        val sprite = block.params["sprite"]?.value?.ifBlank { null } ?: mainBlock.params["sprite"]?.value ?: ""
        val cols = block.params["cols"]?.value?.toIntOrNull() ?: 4
        val rows = block.params["rows"]?.value?.toIntOrNull() ?: 1
        val start = block.params["startFrame"]?.value?.toIntOrNull() ?: 0
        val end = block.params["endFrame"]?.value?.toIntOrNull() ?: 0
        val fps = block.params["fps"]?.value?.toFloatOrNull() ?: 12f
        val loop = block.params["loop"]?.value != "false"
        val offX = block.params["offsetX"]?.value?.toIntOrNull() ?: 0
        val offY = block.params["offsetY"]?.value?.toIntOrNull() ?: 0
        val spX = block.params["spacingX"]?.value?.toIntOrNull() ?: 0
        val spY = block.params["spacingY"]?.value?.toIntOrNull() ?: 0
        val fw = block.params["frameW"]?.value?.toIntOrNull() ?: 0
        val fh = block.params["frameH"]?.value?.toIntOrNull() ?: 0

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
            sprites = sprites,
            projectId = projectId,
            onConfirm = { sName, nCols, nRows, nStart, nEnd, nFps, nLoop, nOffX, nOffY, nSpX, nSpY, nFw, nFh ->
                setupBlocks = setupBlocks.toMutableList().also { list ->
                    list[idx] = list[idx]
                        .withParam("sprite", sName)
                        .withParam("cols", nCols.toString())
                        .withParam("rows", nRows.toString())
                        .withParam("startFrame", nStart.toString())
                        .withParam("endFrame", nEnd.toString())
                        .withParam("fps", nFps.toInt().toString())
                        .withParam("loop", nLoop.toString())
                        .withParam("offsetX", nOffX.toString())
                        .withParam("offsetY", nOffY.toString())
                        .withParam("spacingX", nSpX.toString())
                        .withParam("spacingY", nSpY.toString())
                        .withParam("frameW", nFw.toString())
                        .withParam("frameH", nFh.toString())
                }
                animTarget = null
            },
            onDismiss = { animTarget = null }
        )
        return
    }

    Scaffold(
        containerColor = Navy900,
        topBar = {
            Surface(color = Surface1, shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrim)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Настройка объекта", color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(mainBlock.params["name"]?.value ?: "", color = TextSec, fontSize = 12.sp)
                    }
                    Button(
                        onClick = ::save,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("Сохранить", color = Color.Black, fontSize = 13.sp) }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPicker = true }, containerColor = Accent) {
                Icon(Icons.Default.Add, null, tint = Color.Black)
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            // Основной блок объекта (sim_create / sim_text) — всегда первый
            item(key = mainBlock.id) {
                val mainCollapsed = collapsedState[mainBlock.id] ?: false
                BlockCard(
                    block = mainBlock,
                    index = 0,
                    total = setupBlocks.size + 1,
                    variables = emptyList(),
                    allBlocks = emptyList(),
                    spriteNames = spriteNames,
                    collapsed = mainCollapsed,
                    onToggleCollapse = { collapsedState[mainBlock.id] = !mainCollapsed },
                    onRemove = {},  // нельзя удалить основной блок
                    onMoveUp = {}, onMoveDown = {}, onDuplicate = {},
                    onParamChange = { key, value ->
                        var updated = mainBlock.withParam(key, value)
                        // Для sim_sprite: при выборе спрайта автоматически подставляем размер
                        if (key == "sprite" && mainBlock.type == "sim_sprite") {
                            val asset = sprites.find { it.name == value }
                            if (asset != null) {
                                val curW = updated.params["width"]?.value?.toFloatOrNull() ?: 0f
                                val curH = updated.params["height"]?.value?.toFloatOrNull() ?: 0f
                                if (curW == 0f) updated = updated.withParam("width", asset.width.toString())
                                if (curH == 0f) updated = updated.withParam("height", asset.height.toString())
                            }
                        }
                        mainBlock = updated
                        if (key == "name") syncName(value)
                    },
                    onOpenExpr = { key, label, cur, isId ->
                        exprTarget = ExprEditTarget(-1, key, label, cur, isId)
                    },
                    onOpenPositionPicker = null
                )
            }

            if (setupBlocks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("Нажми + чтобы добавить физику, хитбокс или тег", color = TextSec, fontSize = 12.sp)
                    }
                }
            }
            itemsIndexed(setupBlocks, key = { _, b -> b.id }) { index, block ->
                val collapsed = collapsedState[block.id] ?: false
                var showDelete by remember { mutableStateOf(false) }
                if (showDelete) {
                    AlertDialog(
                        onDismissRequest = { showDelete = false },
                        containerColor = Surface2,
                        icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
                        title = { Text("Удалить блок?", color = TextPrim) },
                        text = { Text("«${block.displayName}» будет удалён.", color = TextSec) },
                        confirmButton = {
                            Button(onClick = {
                                setupBlocks = setupBlocks.filter { it.id != block.id }
                                showDelete = false
                            }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Удалить") }
                        },
                        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Отмена", color = TextSec) } }
                    )
                }
                BlockCard(
                    block = block,
                    index = index + 1,
                    total = setupBlocks.size + 1,
                    variables = emptyList(),
                    allBlocks = emptyList(),
                    spriteNames = spriteNames,
                    collapsed = collapsed,
                    onToggleCollapse = { collapsedState[block.id] = !collapsed },
                    onRemove = { showDelete = true },
                    onMoveUp = {
                        if (index > 0) setupBlocks = setupBlocks.toMutableList().also {
                            val item = it.removeAt(index); it.add(index - 1, item)
                        }
                    },
                    onMoveDown = {
                        if (index < setupBlocks.size - 1) setupBlocks = setupBlocks.toMutableList().also {
                            val item = it.removeAt(index); it.add(index + 1, item)
                        }
                    },
                    onDuplicate = {
                        setupBlocks = setupBlocks.toMutableList().also {
                            it.add(index + 1, block.copy(id = UUID.randomUUID().toString()))
                        }
                    },
                    onParamChange = { key, value ->
                        setupBlocks = setupBlocks.toMutableList().also { it[index] = it[index].withParam(key, value) }
                    },
                    onOpenExpr = { key, label, cur, isId ->
                        exprTarget = ExprEditTarget(index, key, label, cur, isId)
                    },
                    onOpenHitboxEditor = { b -> hitboxTarget = index to b },
                    onOpenAnimEditor = { b -> animTarget = index to b }
                )
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = Surface2,
            title = { Text("Добавить настройку", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SETUP_BLOCK_TYPES.forEach { type ->
                        val proto = BlockRegistry.create(type) ?: return@forEach
                        val accent = categoryColor(proto.category)
                        Surface(
                            onClick = {
                                val newBlock = proto.copy(
                                    id = UUID.randomUUID().toString(),
                                    params = proto.params.toMutableMap().also { p ->
                                        // Автозаполняем имя объекта
                                        if (p.containsKey("name")) p["name"] = p["name"]!!.copy(value = objectBlock.params["name"]?.value ?: "")
                                        if (p.containsKey("object")) p["object"] = p["object"]!!.copy(value = objectBlock.params["name"]?.value ?: "")
                                    }
                                )
                                setupBlocks = setupBlocks + newBlock
                                showPicker = false
                            },
                            color = Surface3,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(categoryIcon(proto.category), null, tint = accent, modifier = Modifier.size(20.dp))
                                Column {
                                    Text(proto.displayName, color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(proto.description, color = TextSec, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Отмена", color = TextSec) } }
        )
    }
}
