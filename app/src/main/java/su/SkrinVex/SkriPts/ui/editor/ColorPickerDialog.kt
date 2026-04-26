package su.SkrinVex.SkriPts.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun ColorPickerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initColor = parseHex(initial) ?: Color(0xFF4F8EF7)
    var r by remember { mutableFloatStateOf(initColor.red * 255f) }
    var g by remember { mutableFloatStateOf(initColor.green * 255f) }
    var b by remember { mutableFloatStateOf(initColor.blue * 255f) }

    val color = Color(r / 255f, g / 255f, b / 255f)
    val hex = "#%02X%02X%02X".format(r.roundToInt(), g.roundToInt(), b.roundToInt())

    // Поле ввода HEX
    var hexInput by remember(hex) { mutableStateOf(hex) }
    // Синхронизируем слайдеры при ручном вводе
    LaunchedEffect(hexInput) {
        val parsed = parseHex(hexInput)
        if (parsed != null) {
            r = parsed.red * 255f
            g = parsed.green * 255f
            b = parsed.blue * 255f
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Выбор цвета", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Предпросмотр
                Box(
                    Modifier.fillMaxWidth().height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(color)
                        .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(10.dp))
                )

                // HEX поле — редактируемое и выделяемое
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v
                    },
                    label = { Text("HEX", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Surface3,
                        focusedLabelColor = Accent,
                        focusedTextColor = TextPrim,
                        unfocusedTextColor = TextPrim,
                        cursorColor = Accent
                    ),
                    leadingIcon = {
                        Box(
                            Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(color)
                                .border(1.dp, Color.White.copy(0.3f), RoundedCornerShape(4.dp))
                        )
                    }
                )

                // Слайдеры RGB
                RgbSlider("R", r, Color(1f, 0f, 0f)) { r = it; hexInput = "#%02X%02X%02X".format(it.roundToInt(), g.roundToInt(), b.roundToInt()) }
                RgbSlider("G", g, Color(0f, 0.8f, 0f)) { g = it; hexInput = "#%02X%02X%02X".format(r.roundToInt(), it.roundToInt(), b.roundToInt()) }
                RgbSlider("B", b, Color(0.2f, 0.5f, 1f)) { b = it; hexInput = "#%02X%02X%02X".format(r.roundToInt(), g.roundToInt(), it.roundToInt()) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hex) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("OK", color = Navy900)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
private fun RgbSlider(label: String, value: Float, trackColor: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = trackColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = trackColor,
                activeTrackColor = trackColor,
                inactiveTrackColor = trackColor.copy(alpha = 0.3f)
            )
        )
        Text(value.roundToInt().toString(), color = TextSec, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
    }
}

private fun parseHex(hex: String): Color? = runCatching {
    val clean = hex.trim().trimStart('#')
    val long = clean.toLong(16)
    when (clean.length) {
        6 -> Color(0xFF000000 or long)
        8 -> Color(long)
        else -> null
    }
}.getOrNull()
