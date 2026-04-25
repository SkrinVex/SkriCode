package su.SkrinVex.SkriPts.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.block.*
import su.SkrinVex.SkriPts.data.ProjectVar
import su.SkrinVex.SkriPts.data.Script
import su.SkrinVex.SkriPts.data.ScriptEvent
import su.SkrinVex.SkriPts.data.VarScope
import su.SkrinVex.SkriPts.ui.expr.ExpressionEditorScreen
import su.SkrinVex.SkriPts.ui.theme.*

data class ExprEditTarget(val blockIndex: Int, val paramKey: String, val paramLabel: String, val currentValue: String, val isIdentifier: Boolean = false, val branch: String? = null, val childIndex: Int = -1)

private val DIRECT_PARAM_KEYS = setOf("name", "color")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var exprTarget by remember { mutableStateOf<ExprEditTarget?>(null) }
    var showScriptMenu by remember { mutableStateOf<String?>(null) }
    var showAddScriptDialog by remember { mutableStateOf(false) }

    // Редактор выражений — полноэкранный
    exprTarget?.let { target ->
        ExpressionEditorScreen(
            initialValue = target.currentValue,
            paramLabel = target.paramLabel,
            variables = state.visibleVars,
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
            onBack = { exprTarget = null }
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
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                        }
                        Text(state.projectName, color = TextPrim, fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1)
                        IconButton(onClick = vm::runSim) {
                            Icon(Icons.Default.PlayArrow, "Запустить", tint = Success)
                        }
                    }
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
                        onChangeEvent = { event, target -> vm.setScriptEvent(state.activeScript.id, event, target) }
                    )
                }
                if (activeBlocks.isEmpty()) {
                    item { EmptyState() }
                }
                itemsIndexed(activeBlocks, key = { _, b -> b.id }) { index, block ->
                    val collapsed = collapsedState[block.id] ?: vm.isBlockCollapsed(activeScriptId, block.id)
                    BlockCard(
                        block = block,
                        index = index,
                        total = activeBlocks.size,
                        variables = state.visibleVars,
                        collapsed = collapsed,
                        onToggleCollapse = {
                            val next = vm.toggleBlockCollapsed(activeScriptId, block.id)
                            collapsedState[block.id] = next
                        },
                        onRemove = { vm.removeBlock(index) },
                        onMoveUp = { if (index > 0) vm.moveBlock(index, index - 1) },
                        onMoveDown = { if (index < activeBlocks.size - 1) vm.moveBlock(index, index + 1) },
                        onParamChange = { k, v -> vm.updateParam(index, k, v) },
                        onOpenExpr = { key, label, cur, isId -> exprTarget = ExprEditTarget(index, key, label, cur, isId) },
                        onAddChild = { branch, type -> vm.addChildBlock(index, branch, type) },
                        onRemoveChild = { branch, ci -> vm.removeChildBlock(index, branch, ci) },
                        onChildParamChange = { branch, ci, k, v -> vm.updateChildParam(index, branch, ci, k, v) },
                        onOpenChildExpr = { branch, ci, key, label, cur, isId -> exprTarget = ExprEditTarget(index, key, label, cur, isId, branch, ci) }
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
                onDismiss = { showScriptMenu = null }
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
private fun ScriptEventHeader(script: Script, onChangeEvent: (ScriptEvent, String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .clickable { showDialog = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(eventIcon(script.event), null, tint = Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(script.event.label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (script.event == ScriptEvent.ON_TAP && script.eventTarget.isNotBlank()) {
                Text("Объект: ${script.eventTarget}", color = TextSec, fontSize = 11.sp)
            }
        }
        Icon(Icons.Default.Edit, null, tint = TextSec, modifier = Modifier.size(14.dp))
    }

    if (showDialog) {
        EventPickerDialog(
            current = script.event,
            currentTarget = script.eventTarget,
            onConfirm = { event, target -> onChangeEvent(event, target); showDialog = false },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun EventPickerDialog(
    current: ScriptEvent,
    currentTarget: String,
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
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected == event) Accent.copy(0.15f) else Surface3)
                            .clickable { selected = event }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(eventIcon(event), null, tint = if (selected == event) Accent else TextSec,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(event.label, color = if (selected == event) Accent else TextPrim, fontSize = 14.sp)
                    }
                }
                if (selected == ScriptEvent.ON_TAP) {
                    OutlinedTextField(
                        value = target,
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
private fun ScriptMenuDialog(script: Script, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
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
private fun BlockCard(
    block: BlockDef, index: Int, total: Int,
    variables: List<ProjectVar>,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onRemove: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onAddChild: (branch: String, type: String) -> Unit = { _, _ -> },
    onRemoveChild: (branch: String, childIndex: Int) -> Unit = { _, _ -> },
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit = { _, _, _, _ -> },
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit = { _, _, _, _, _, _ -> }
) {
    val accent = categoryColor(block.category)

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize()
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
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
                    SmallBtn(if (!collapsed) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        onClick = onToggleCollapse)
                    SmallBtn(Icons.Default.DeleteOutline, tint = Danger.copy(alpha = 0.8f), onClick = onRemove)
                }
                if (!collapsed) {
                    when (block.type) {
                        "if_block" -> {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Surface3)
                            Spacer(Modifier.height(10.dp))
                            IfBlockContent(
                                block = block, variables = variables,
                                onParamChange = onParamChange, onOpenExpr = onOpenExpr,
                                onAddChild = onAddChild, onRemoveChild = onRemoveChild,
                                onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr
                            )
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
                                block.paramOrder.forEach { key ->
                                    val param = block.params[key] ?: return@forEach
                                    when {
                                        block.type == "set_var" && key == "name" ->
                                            VarNameChip(value = param.value, label = param.label,
                                                onClick = { onOpenExpr(key, param.label, param.value, true) })
                                        key == "color" ->
                                            ColorField(param = param, onChange = { onParamChange(key, it) })
                                        block.type == "sim_move" && key == "mode" ->
                                            MoveModeToggle(value = param.value, onChange = { onParamChange(key, it) })
                                        key in DIRECT_PARAM_KEYS ->
                                            DirectInputField(param = param, onChange = { onParamChange(key, it) })
                                        else ->
                                            ExprChip(param = param, variables = variables,
                                                onClick = { onOpenExpr(key, param.label, param.value, false) })
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveModeToggle(value: String, onChange: (String) -> Unit) {
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
private fun IfBlockContent(
    block: BlockDef,
    variables: List<ProjectVar>,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit,
    onAddChild: (branch: String, type: String) -> Unit,
    onRemoveChild: (branch: String, childIndex: Int) -> Unit,
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit,
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
) {
    val ops = listOf("==", "!=", ">", "<", ">=", "<=")
    val opLabels = mapOf("==" to "равно", "!=" to "≠", ">" to "больше", "<" to "меньше", ">=" to "≥", "<=" to "≤")

    // Левое значение
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
        onAddChild = onAddChild, onRemoveChild = onRemoveChild,
        onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr
    )
    Spacer(Modifier.height(6.dp))
    IfBranchSection(
        label = "Если ложь",
        color = TextSec,
        branch = "else",
        blocks = block.children["else"] ?: emptyList(),
        variables = variables,
        onAddChild = onAddChild, onRemoveChild = onRemoveChild,
        onChildParamChange = onChildParamChange, onOpenChildExpr = onOpenChildExpr
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IfBranchSection(
    label: String,
    color: Color,
    branch: String,
    blocks: List<BlockDef>,
    variables: List<ProjectVar>,
    onAddChild: (branch: String, type: String) -> Unit,
    onRemoveChild: (branch: String, childIndex: Int) -> Unit,
    onChildParamChange: (branch: String, childIndex: Int, key: String, value: String) -> Unit,
    onOpenChildExpr: (branch: String, childIndex: Int, key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
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
                ChildBlockRow(
                    block = child, childIndex = ci, branch = branch,
                    variables = variables,
                    accentColor = color,
                    onRemove = { onRemoveChild(branch, ci) },
                    onParamChange = { k, v -> onChildParamChange(branch, ci, k, v) },
                    onOpenExpr = { k, lbl, cur, isId -> onOpenChildExpr(branch, ci, k, lbl, cur, isId) }
                )
                if (ci < blocks.size - 1) Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, containerColor = Surface1,
            dragHandle = {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface3))
                }
            }
        ) {
            Text("Добавить в «$label»", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            // Показываем только блоки без вложенности (не if_block внутри if_block)
            val allowed = BlockRegistry.all().filter { it.type != "if_block" }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(allowed.size) { i ->
                    val meta = allowed[i]
                    val c = categoryColor(meta.category)
                    Card(onClick = { onAddChild(branch, meta.type); showPicker = false },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface2)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.copy(0.15f)),
                                contentAlignment = Alignment.Center) {
                                Icon(categoryIcon(meta.category), null, tint = c, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(meta.displayName, color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(meta.description, color = TextSec, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChildBlockRow(
    block: BlockDef, childIndex: Int, branch: String,
    variables: List<ProjectVar>,
    accentColor: Color,
    onRemove: () -> Unit,
    onParamChange: (String, String) -> Unit,
    onOpenExpr: (key: String, label: String, current: String, isIdentifier: Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val accent = categoryColor(block.category)

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
                SmallBtn(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, onClick = { expanded = !expanded })
                SmallBtn(Icons.Default.DeleteOutline, tint = Danger.copy(0.7f), onClick = onRemove)
            }
            if (expanded && block.params.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                block.paramOrder.forEach { key ->
                    val param = block.params[key] ?: return@forEach
                    when {
                        block.type == "set_var" && key == "name" ->
                            VarNameChip(value = param.value, label = param.label,
                                onClick = { onOpenExpr(key, param.label, param.value, true) })
                        key == "color" ->
                            ColorField(param = param, onChange = { onParamChange(key, it) })
                        block.type == "sim_move" && key == "mode" ->
                            MoveModeToggle(value = param.value, onChange = { onParamChange(key, it) })
                        key in DIRECT_PARAM_KEYS ->
                            DirectInputField(param = param, onChange = { onParamChange(key, it) })
                        else ->
                            ExprChip(param = param, variables = variables,
                                onClick = { onOpenExpr(key, param.label, param.value, false) })
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ExprChip(param: BlockParam, variables: List<ProjectVar>, onClick: () -> Unit) {
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
private fun VarNameChip(value: String, label: String, onClick: () -> Unit) {
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
private fun ColorField(param: BlockParam, onChange: (String) -> Unit) {
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
private fun DirectInputField(param: BlockParam, onChange: (String) -> Unit) {
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
private fun SmallBtn(icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, tint: Color = TextSec) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.25f), modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Surface3))
            }
        }
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxWidth()) {
            item {
                Text("Добавить блок", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            BlockRegistry.byCategory().forEach { (category, metas) ->
                item {
                    Text(category.label.uppercase(), color = categoryColor(category),
                        fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
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

fun categoryColor(cat: BlockCategory) = when (cat) {
    BlockCategory.OUTPUT     -> Accent
    BlockCategory.CONTROL    -> Warning
    BlockCategory.MATH       -> Color(0xFF60A5FA)
    BlockCategory.LOGIC      -> Color(0xFFA78BFA)
    BlockCategory.STRING     -> Color(0xFF34D399)
    BlockCategory.VARIABLE   -> Color(0xFFFB923C)
    BlockCategory.SIMULATION -> Color(0xFFF472B6)
}

fun categoryIcon(cat: BlockCategory): ImageVector = when (cat) {
    BlockCategory.OUTPUT     -> Icons.Default.Output
    BlockCategory.CONTROL    -> Icons.Default.Tune
    BlockCategory.MATH       -> Icons.Default.Calculate
    BlockCategory.LOGIC      -> Icons.Default.AccountTree
    BlockCategory.STRING     -> Icons.Default.TextFields
    BlockCategory.VARIABLE   -> Icons.Default.DataObject
    BlockCategory.SIMULATION -> Icons.Default.Widgets
}

fun eventIcon(event: ScriptEvent): ImageVector = when (event) {
    ScriptEvent.ON_START -> Icons.Default.PlayArrow
    ScriptEvent.ON_TAP   -> Icons.Default.TouchApp
}
