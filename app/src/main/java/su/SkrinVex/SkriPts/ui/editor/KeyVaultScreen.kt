package su.SkrinVex.SkriPts.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
fun KeyVaultScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var currentKey by remember { mutableStateOf(SaveCrypto.getKey(ctx) ?: "") }
    var inputKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showConfirmClear by remember { mutableStateOf(false) }
    val hasKey = currentKey.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Key, null, tint = Accent) },
        title = { Text("Хранилище ключей", color = TextPrim, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Статус
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (hasKey) Success.copy(0.1f) else Warning.copy(0.1f))
                        .border(1.dp, if (hasKey) Success.copy(0.4f) else Warning.copy(0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (hasKey) Icons.Default.LockOpen else Icons.Default.Lock,
                        null,
                        tint = if (hasKey) Success else Warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (hasKey) "Ключ шифрования установлен" else "Ключ шифрования не задан",
                        color = if (hasKey) Success else Warning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    "Ключ используется для шифрования сохранений. Никому не сообщай его — без ключа данные невозможно расшифровать.",
                    color = TextSec,
                    fontSize = 12.sp
                )

                // Поле ввода нового ключа
                OutlinedTextField(
                    value = inputKey,
                    onValueChange = { inputKey = it },
                    label = { Text(if (hasKey) "Новый ключ (заменит текущий)" else "Введи ключ шифрования") },
                    placeholder = { Text("Минимум 8 символов", color = TextSec.copy(0.5f)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, tint = TextSec
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Surface3,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = TextSec,
                        focusedTextColor = TextPrim,
                        unfocusedTextColor = TextPrim,
                        cursorColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Кнопка сохранить ключ
                Button(
                    onClick = {
                        if (inputKey.length >= 8) {
                            SaveCrypto.saveKey(ctx, inputKey)
                            currentKey = inputKey
                            inputKey = ""
                        }
                    },
                    enabled = inputKey.length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp), tint = Navy900)
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasKey) "Заменить ключ" else "Сохранить ключ", color = Navy900)
                }

                // Кнопка удалить ключ
                if (hasKey) {
                    OutlinedButton(
                        onClick = { showConfirmClear = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(0.5f))
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Удалить ключ")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Accent) }
        }
    )

    if (showConfirmClear) {
        AlertDialog(
            onDismissRequest = { showConfirmClear = false },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.Warning, null, tint = Danger) },
            title = { Text("Удалить ключ?", color = TextPrim) },
            text = {
                Text(
                    "Зашифрованные данные станет невозможно расшифровать. Убедись что у тебя нет важных зашифрованных сохранений.",
                    color = TextSec, fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        SaveCrypto.clearKey(ctx)
                        currentKey = ""
                        showConfirmClear = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = false }) { Text("Отмена", color = TextSec) }
            }
        )
    }
}
