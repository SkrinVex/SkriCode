package su.SkrinVex.SkriPts.ui.sim

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.engine.SimObject
import su.SkrinVex.SkriPts.engine.SimState
import su.SkrinVex.SkriPts.ui.theme.*

@Composable
fun SimulationScreen(
    state: SimState,
    onTap: (objectName: String) -> Unit,
    onBack: () -> Unit,
    onClearLogs: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    var panelTab by remember { mutableIntStateOf(-1) }  // -1=скрыто, 0=лог, 1=объекты
    var highlightedObj by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(Pair(0f, 0f)) }

    // Сбрасываем подсветку через 1.5 сек
    LaunchedEffect(highlightedObj) {
        if (highlightedObj != null) {
            kotlinx.coroutines.delay(1500)
            highlightedObj = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state.objects) {
                    detectTapGestures { offset ->
                        val cx = canvasSize.first / 2f
                        val cy = canvasSize.second / 2f
                        val hit = state.objects.values.lastOrNull { obj ->
                            val left = cx + obj.x - obj.width / 2f
                            val top  = cy - obj.y - obj.height / 2f
                            offset.x in left..(left + obj.width) && offset.y in top..(top + obj.height)
                        }
                        if (hit != null) onTap(hit.name)
                    }
                }
        ) {
            canvasSize = Pair(size.width, size.height)
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawGrid(cx, cy)
            state.objects.values.forEach { drawSimObject(it, cx, cy, it.name == highlightedObj) }
        }

        // Кнопка закрыть
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                .background(Color(0x88000000), RoundedCornerShape(8.dp))
        ) {
            Icon(Icons.Default.Close, "Закрыть", tint = Color.White)
        }

        // Кнопки панелей (снизу слева) — видны только когда панель скрыта
        if (panelTab < 0) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PanelBtn(
                    label = if (state.errors.isNotEmpty()) "Ошибки (${state.errors.size})" else "Лог",
                    active = false,
                    color = if (state.errors.isNotEmpty()) Danger else TextSec,
                    onClick = { panelTab = 0 }
                )
                PanelBtn(
                    label = "Объекты (${state.objects.size})",
                    active = false,
                    color = Accent,
                    onClick = { panelTab = 1 }
                )
            }
        }

        // Панель
        if (panelTab >= 0) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 240.dp),
                color = Color(0xEE0A0E1A),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Заголовок панели с вкладками и кнопкой закрыть
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PanelBtn(
                            label = if (state.errors.isNotEmpty()) "Ошибки (${state.errors.size})" else "Лог",
                            active = panelTab == 0,
                            color = if (state.errors.isNotEmpty()) Danger else TextSec,
                            onClick = { panelTab = 0 }
                        )
                        Spacer(Modifier.width(6.dp))
                        PanelBtn(
                            label = "Объекты (${state.objects.size})",
                            active = panelTab == 1,
                            color = Accent,
                            onClick = { panelTab = 1 }
                        )
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

private fun DrawScope.drawSimObject(obj: SimObject, cx: Float, cy: Float, highlighted: Boolean) {
    val left = cx + obj.x - obj.width / 2f
    val top  = cy - obj.y - obj.height / 2f
    val cr = CornerRadius(obj.radius, obj.radius)

    if (obj.color != Color.Transparent) {
        drawRoundRect(color = obj.color, topLeft = Offset(left, top), size = Size(obj.width, obj.height), cornerRadius = cr)
    }

    // Обводка
    val strokeColor = when {
        highlighted -> Color.Yellow
        obj.tapScriptId != null -> Color.White.copy(alpha = 0.7f)
        obj.color == Color.Transparent -> Color.Transparent
        else -> obj.color.copy(alpha = 0.4f)
    }
    if (strokeColor != Color.Transparent) {
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(left - 1f, top - 1f),
            size = Size(obj.width + 2f, obj.height + 2f),
            cornerRadius = CornerRadius(obj.radius + 1f, obj.radius + 1f),
            style = Stroke(width = if (highlighted) 3f else if (obj.tapScriptId != null) 2f else 1f)
        )
    }

    // Текст — только если label задан явно
    if (obj.label.isNotBlank()) {
        val textSize = obj.fontSize * density
        drawContext.canvas.nativeCanvas.drawText(
            obj.label,
            left + obj.width / 2f,
            top + obj.height / 2f + textSize / 3f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                this.textSize = textSize
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                isFakeBoldText = obj.bold
            }
        )
    }
}
