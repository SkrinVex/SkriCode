package su.SkrinVex.SkriCode.ui.backpack

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.data.BackpackItem
import su.SkrinVex.SkriCode.data.BackpackItemType
import su.SkrinVex.SkriCode.data.BackpackRepository
import su.SkrinVex.SkriCode.data.ScriptEvent
import su.SkrinVex.SkriCode.data.deserialize
import su.SkrinVex.SkriCode.ui.editor.categoryColor
import su.SkrinVex.SkriCode.ui.editor.categoryIcon
import su.SkrinVex.SkriCode.ui.editor.eventIcon
import su.SkrinVex.SkriCode.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран "Рюкзак" — сквозное хранилище скриптов/блоков/объектов/сцен, общее для всех проектов.
 * Открывается и с главного экрана (только просмотр/удаление), и изнутри редактора проекта
 * (тогда дополнительно доступна вставка — соответствующий onPaste* передан не null).
 */
@Composable
fun BackpackScreen(
    onBack: () -> Unit,
    onPasteScript: ((BackpackItem) -> Unit)? = null,
    onPasteBlock: ((BackpackItem) -> Unit)? = null,
    onPasteObject: ((BackpackItem) -> Unit)? = null,
    onPasteScene: ((BackpackItem) -> Unit)? = null,
    onPasted: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val ctx = LocalContext.current
    var backpack by remember { mutableStateOf(BackpackRepository.load(ctx)) }
    var filter by remember { mutableStateOf<BackpackItemType?>(null) }
    var query by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<BackpackItem?>(null) }
    var itemToPreview by remember { mutableStateOf<BackpackItem?>(null) }

    fun refresh() { backpack = BackpackRepository.load(ctx) }

    val filtered = remember(backpack, filter, query) {
        backpack.items.sortedByDescending { it.createdAt }
            .filter { filter == null || it.type == filter }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        containerColor = Surface1,
        topBar = {
            Surface(color = Surface1, shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                    }
                    Icon(Icons.Default.Backpack, null, tint = Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Рюкзак", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text("${backpack.items.size}", color = TextSec, fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Поиск по названию...", color = TextSec) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSec) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить", tint = TextSec) }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
                    focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent
                )
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackpackFilterChip("Всё", filter == null) { filter = null }
                BackpackFilterChip("Скрипты", filter == BackpackItemType.SCRIPT) { filter = BackpackItemType.SCRIPT }
                BackpackFilterChip("Блоки", filter == BackpackItemType.BLOCK) { filter = BackpackItemType.BLOCK }
                BackpackFilterChip("Объекты", filter == BackpackItemType.OBJECT) { filter = BackpackItemType.OBJECT }
                BackpackFilterChip("Сцены", filter == BackpackItemType.SCENE) { filter = BackpackItemType.SCENE }
            }

            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Backpack, null, tint = TextSec, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (backpack.items.isEmpty()) "Рюкзак пуст" else "Ничего не найдено",
                        color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 16.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Отправляй сюда сцены, скрипты, блоки и объекты из редактора проекта — они будут доступны в любом другом проекте",
                        color = TextSec, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        val onPaste: (() -> Unit)? = when (item.type) {
                            BackpackItemType.SCRIPT -> onPasteScript?.let { cb -> { cb(item); onPasted() } }
                            BackpackItemType.BLOCK -> onPasteBlock?.let { cb -> { cb(item); onPasted() } }
                            BackpackItemType.OBJECT -> onPasteObject?.let { cb -> { cb(item); onPasted() } }
                            BackpackItemType.SCENE -> onPasteScene?.let { cb -> { cb(item); onPasted() } }
                        }
                        val onPreview: (() -> Unit)? = when (item.type) {
                            BackpackItemType.SCRIPT, BackpackItemType.SCENE, BackpackItemType.BLOCK -> { { itemToPreview = item } }
                            else -> null
                        }
                        BackpackItemRow(item = item, onPaste = onPaste, onPreview = onPreview, onDelete = { itemToDelete = item })
                    }
                }
            }
        }
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            containerColor = Surface2,
            title = { Text("Удалить из рюкзака?", color = TextPrim) },
            text = { Text("«${item.name}» будет удалён из рюкзака безвозвратно.", color = TextSec) },
            confirmButton = {
                Button(
                    onClick = { BackpackRepository.removeItem(ctx, item.id); refresh(); itemToDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text("Отмена", color = TextSec) } }
        )
    }

    itemToPreview?.let { item ->
        BackpackPreviewDialog(item = item, onDismiss = { itemToPreview = null })
    }
}

@Composable
private fun BackpackFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) Accent else TextSec, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun typeVisuals(item: BackpackItem): Triple<ImageVector, Color, String> = when (item.type) {
    BackpackItemType.SCRIPT -> Triple(item.script?.let { eventIcon(it.event) } ?: Icons.Default.PlayArrow, Accent, "Скрипт")
    BackpackItemType.BLOCK -> Triple(Icons.Default.ViewModule, Color(0xFF60A5FA), "Блок")
    BackpackItemType.OBJECT -> Triple(Icons.Default.Widgets, Color(0xFFF472B6), "Объект")
    BackpackItemType.SCENE -> Triple(Icons.Default.Layers, Color(0xFF4ADE80), "Сцена")
}

@Composable
private fun BackpackItemRow(item: BackpackItem, onPaste: (() -> Unit)?, onPreview: (() -> Unit)?, onDelete: () -> Unit) {
    val (icon, accent, typeLabel) = typeVisuals(item)
    val dateStr = remember(item.createdAt) {
        SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(item.createdAt))
    }
    val subtitle = when (item.type) {
        BackpackItemType.SCENE -> "$typeLabel · ${item.scene?.scripts?.size ?: 0} скр. · ${item.scene?.locationBlocks?.size ?: 0} объектов"
        BackpackItemType.SCRIPT -> "$typeLabel · ${item.script?.blocks?.size ?: 0} блоков"
        BackpackItemType.BLOCK -> if (item.blocks.size > 1) "$typeLabel · ${item.blocks.size} блоков (с телом)" else "$typeLabel · $dateStr"
        else -> "$typeLabel · $dateStr"
    }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .let { if (onPreview != null) it.clickable(onClick = onPreview) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextSec, fontSize = 11.sp)
        }
        if (onPaste != null) {
            TextButton(onClick = onPaste) {
                Icon(Icons.Default.ContentPaste, null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Вставить", color = Accent, fontSize = 13.sp)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.DeleteOutline, "Удалить", tint = Danger.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

/** Диалог задания имени элемента при отправке в рюкзак. */
@Composable
fun BackpackNameDialog(defaultName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Backpack, null, tint = Color(0xFFF472B6), modifier = Modifier.size(18.dp))
                Text("Отправить в рюкзак", color = TextPrim, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Название в рюкзаке") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, focusedLabelColor = Accent,
                    cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim().ifBlank { defaultName }) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF472B6))
            ) { Text("Отправить", color = Color.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

/** Полноэкранный предпросмотр блоков скрипта/сцены из рюкзака — только просмотр, без редактирования. */
@Composable
private fun BackpackPreviewDialog(item: BackpackItem, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Surface1, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Закрыть", tint = TextPrim)
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(item.name, color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Предпросмотр (только чтение)", color = TextSec, fontSize = 12.sp)
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    when (item.type) {
                        BackpackItemType.BLOCK -> {
                            val blocks = item.blocks.mapNotNull { it.deserialize() }
                            if (blocks.size > 1) {
                                item { PreviewSectionLabel("Парный блок целиком — ${blocks.size} блоков (открывающий + тело + закрывающий)") }
                            }
                            if (blocks.isEmpty()) {
                                item { PreviewEmptyRow("Пусто") }
                            } else {
                                items(blocks, key = { it.id }) { b -> PreviewBlockRow(b) }
                            }
                        }
                        BackpackItemType.SCRIPT -> {
                            val script = item.script
                            if (script != null) {
                                item { PreviewSectionLabel("Событие: ${script.event.label}" + if (script.eventTarget.isNotBlank()) " · ${script.eventTarget}" else "") }
                                val blocks = script.blocks.mapNotNull { it.deserialize() }
                                if (blocks.isEmpty()) {
                                    item { PreviewEmptyRow("В скрипте нет блоков") }
                                } else {
                                    items(blocks, key = { it.id }) { b -> PreviewBlockRow(b) }
                                }
                            }
                        }
                        BackpackItemType.SCENE -> {
                            val scene = item.scene
                            if (scene != null) {
                                item { PreviewSectionLabel("Объекты (${scene.locationBlocks.size})") }
                                val objects = scene.locationBlocks.mapNotNull { it.deserialize() }
                                if (objects.isEmpty()) {
                                    item { PreviewEmptyRow("На сцене нет объектов") }
                                } else {
                                    items(objects, key = { "obj_" + it.id }) { b -> PreviewBlockRow(b) }
                                }
                                items(scene.scripts, key = { "scr_hdr_" + it.id }) { script ->
                                    Column {
                                        Spacer(Modifier.height(4.dp))
                                        PreviewSectionLabel("Скрипт «${script.name}» (${script.event.label})")
                                        val blocks = script.blocks.mapNotNull { it.deserialize() }
                                        if (blocks.isEmpty()) {
                                            PreviewEmptyRow("Пустой скрипт")
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                blocks.forEach { b -> PreviewBlockRow(b) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSectionLabel(text: String) {
    Text(text, color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun PreviewEmptyRow(text: String) {
    Text(text, color = TextSec, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun PreviewBlockRow(block: BlockDef) {
    val accent = categoryColor(block.category)
    val summary = remember(block) {
        block.paramOrder.take(3).joinToString(", ") { key ->
            val v = block.params[key]?.value.orEmpty()
            "$key=${if (v.length > 24) v.take(24) + "…" else v}"
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(categoryIcon(block.category), null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(block.displayName, color = TextPrim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (summary.isNotBlank()) {
                Text(summary, color = TextSec, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!block.enabled) {
            Text("ВЫКЛ", color = Danger, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}
