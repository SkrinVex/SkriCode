package su.SkrinVex.SkriPts.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.engine.SaveCrypto
import su.SkrinVex.SkriPts.ui.theme.*

@Composable
fun KeyVaultScreen(currentProjectName: String = "", onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var keys by remember { mutableStateOf(SaveCrypto.listKeys(ctx)) }
    // Если есть текущий проект — сразу открываем его для редактирования
    var selectedProject by remember { mutableStateOf(if (currentProjectName.isNotBlank()) currentProjectName else "") }
    var inputKey by remember { mutableStateOf("") }
    var inputProjectName by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showConfirmDelete by remember { mutableStateOf<String?>(null) }
    var showAddNew by remember { mutableStateOf(currentProjectName.isBlank()) }

    fun refresh() { keys = SaveCrypto.listKeys(ctx) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Key, null, tint = Accent) },
        title = { Text("Хранилище ключей", color = TextPrim, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Каждый проект имеет свой ключ шифрования. Ключ используется для защиты сохранений.",
                    color = TextSec, fontSize = 12.sp)

                // Список существующих ключей
                if (keys.isNotEmpty()) {
                    Text("Сохранённые ключи", color = TextSec, fontSize = 11.sp)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(keys) { proj ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedProject == proj) Accent.copy(0.15f) else Surface3)
                                    .border(1.dp, if (selectedProject == proj) Accent.copy(0.5f) else Surface3, RoundedCornerShape(8.dp))
                                    .clickable { selectedProject = proj; inputKey = ""; showAddNew = false }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, null, tint = if (selectedProject == proj) Accent else TextSec, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(proj, color = if (selectedProject == proj) Accent else TextPrim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showConfirmDelete = proj }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.DeleteOutline, null, tint = Danger.copy(0.6f), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Форма редактирования выбранного ключа
                if (selectedProject.isNotBlank() && !showAddNew) {
                    HorizontalDivider(color = Surface3)
                    Text("Ключ для «$selectedProject»", color = TextSec, fontSize = 11.sp)
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text("Новый ключ") },
                        placeholder = { Text("Минимум 8 символов", color = TextSec.copy(0.5f)) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextSec)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
                            focusedLabelColor = Accent, unfocusedLabelColor = TextSec,
                            focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            SaveCrypto.saveKey(ctx, selectedProject, inputKey)
                            inputKey = ""; refresh()
                        },
                        enabled = inputKey.length >= 8,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp), tint = Navy900)
                        Spacer(Modifier.width(6.dp))
                        Text("Сохранить ключ", color = Navy900)
                    }
                }

                // Форма добавления нового ключа
                if (showAddNew || keys.isEmpty()) {
                    HorizontalDivider(color = Surface3)
                    Text("Добавить ключ для проекта", color = TextSec, fontSize = 11.sp)
                    OutlinedTextField(
                        value = if (currentProjectName.isNotBlank()) currentProjectName else inputProjectName,
                        onValueChange = { if (currentProjectName.isBlank()) inputProjectName = it },
                        label = { Text("Имя проекта") },
                        singleLine = true,
                        enabled = currentProjectName.isBlank(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
                            focusedLabelColor = Accent, unfocusedLabelColor = TextSec,
                            focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent,
                            disabledTextColor = TextPrim, disabledBorderColor = Surface3, disabledLabelColor = TextSec
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text("Ключ шифрования") },
                        placeholder = { Text("Минимум 8 символов", color = TextSec.copy(0.5f)) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextSec)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
                            focusedLabelColor = Accent, unfocusedLabelColor = TextSec,
                            focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val projForSave = if (currentProjectName.isNotBlank()) currentProjectName else inputProjectName
                    Button(
                        onClick = {
                            SaveCrypto.saveKey(ctx, projForSave, inputKey)
                            inputKey = ""; inputProjectName = ""; showAddNew = false
                            selectedProject = projForSave; refresh()
                        },
                        enabled = inputKey.length >= 8 && projForSave.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp), tint = Navy900)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить ключ", color = Navy900)
                    }
                }

                if (keys.isNotEmpty() && !showAddNew) {
                    TextButton(onClick = { showAddNew = true; selectedProject = ""; inputKey = "" },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Добавить ключ для другого проекта")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = Accent) } }
    )

    showConfirmDelete?.let { proj ->
        AlertDialog(
            onDismissRequest = { showConfirmDelete = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("Удалить ключ «$proj»?", color = TextPrim) },
            text = { Text("Зашифрованные данные этого проекта станет невозможно расшифровать.", color = TextSec, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = {
                    SaveCrypto.deleteKey(ctx, proj)
                    if (selectedProject == proj) selectedProject = ""
                    showConfirmDelete = null; refresh()
                }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showConfirmDelete = null }) { Text("Отмена", color = TextSec) } }
        )
    }
}
