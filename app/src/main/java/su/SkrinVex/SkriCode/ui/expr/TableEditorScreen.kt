package su.SkrinVex.SkriCode.ui.expr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.data.ProjectTable
import su.SkrinVex.SkriCode.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableEditorScreen(
    table: ProjectTable,
    onSetEntry: (key: String, value: String) -> Unit,
    onRemoveEntry: (key: String) -> Unit,
    onBack: () -> Unit
) {
    val tableColor = TableAccent
    var showAddDialog by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteKey by remember { mutableStateOf<String?>(null) }

    val entries = table.entries.entries.toList()

    Column(Modifier.fillMaxSize().background(Navy900)) {
        Surface(color = Surface1, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                }
                Column(Modifier.weight(1f)) {
                    Text(table.name, color = tableColor, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                    Text("${entries.size} записей · ${if (table.scope.name == "GLOBAL") "глобальная" else "локальная"}",
                        color = TextSec, fontSize = 12.sp)
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Добавить запись", tint = tableColor)
                }
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.TableChart, null, tint = TextSec, modifier = Modifier.size(48.dp))
                    Text("Таблица пуста", color = TextSec, fontSize = 16.sp)
                    Text("Нажми + чтобы добавить запись", color = TextSec, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(entries, key = { idx, e -> "${idx}_${e.key}" }) { idx, (key, value) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2)
                            .border(1.dp, tableColor.copy(0.15f), RoundedCornerShape(10.dp))
                            .clickable { editEntry = key to value }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${idx + 1}", color = TextSec, fontSize = 11.sp,
                            modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(key, color = tableColor, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                            Text(value.ifBlank { "(пусто)" }, color = TextPrim, fontSize = 14.sp)
                        }
                        IconButton(onClick = { deleteKey = key }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, null, tint = Danger.copy(0.7f),
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    // Диалог добавления/редактирования
    val dialogEntry = editEntry
    if (showAddDialog || dialogEntry != null) {
        val isEdit = dialogEntry != null
        var keyInput by remember(dialogEntry) { mutableStateOf(dialogEntry?.first ?: "") }
        var valueInput by remember(dialogEntry) { mutableStateOf(dialogEntry?.second ?: "") }
        val keyError = when {
            keyInput.isBlank() -> null
            !isEdit && keyInput in table.entries -> "Ключ уже существует"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { showAddDialog = false; editEntry = null },
            containerColor = Surface2,
            icon = { Icon(if (isEdit) Icons.Default.Edit else Icons.Default.Add, null, tint = tableColor) },
            title = { Text(if (isEdit) "Изменить запись" else "Добавить запись", color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it },
                        label = { Text("Ключ") },
                        singleLine = true,
                        enabled = !isEdit,
                        isError = keyError != null,
                        supportingText = keyError?.let { { Text(it, color = Danger) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tableColor, focusedLabelColor = tableColor,
                            cursorColor = tableColor, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                        )
                    )
                    OutlinedTextField(
                        value = valueInput, onValueChange = { valueInput = it },
                        label = { Text("Значение") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tableColor, focusedLabelColor = tableColor,
                            cursorColor = tableColor, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (keyInput.isNotBlank() && keyError == null) {
                            onSetEntry(keyInput.trim(), valueInput)
                            showAddDialog = false; editEntry = null
                        }
                    },
                    enabled = keyInput.isNotBlank() && keyError == null,
                    colors = ButtonDefaults.buttonColors(containerColor = tableColor)
                ) { Text(if (isEdit) "Сохранить" else "Добавить", color = Navy900) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; editEntry = null }) {
                    Text("Отмена", color = TextSec)
                }
            }
        )
    }

    deleteKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deleteKey = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить запись?", color = TextPrim) },
            text = { Text("Ключ «$key» будет удалён из таблицы.", color = TextSec) },
            confirmButton = {
                Button(onClick = { onRemoveEntry(key); deleteKey = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteKey = null }) { Text("Отмена", color = TextSec) }
            }
        )
    }
}
