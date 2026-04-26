package su.SkrinVex.SkriPts.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.engine.SimObject
import su.SkrinVex.SkriPts.ui.theme.*
import kotlin.math.hypot

@Composable
fun HitboxEditorScreen(
    obj: SimObject,
    initialPoints: List<Pair<Float, Float>>,
    onConfirm: (List<Pair<Float, Float>>) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var points by remember { mutableStateOf(initialPoints.toMutableList()) }
    var selectedIdx by remember { mutableIntStateOf(-1) }
    var canvasSize by remember { mutableStateOf(Pair(0f, 0f)) }
    var draggingIdx by remember { mutableIntStateOf(-1) }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    fun cx() = canvasSize.first / 2f + panX
    fun cy() = canvasSize.second / 2f + panY
    fun toLocal(o: Offset) = Pair((o.x - cx()) / scale, (cy() - o.y) / scale)
    fun toScreen(p: Pair<Float, Float>) = Offset(cx() + p.first * scale, cy() - p.second * scale)

    fun nearestPoint(o: Offset): Int {
        var best = -1; var bestDist = 30f
        points.forEachIndexed { i, p ->
            val d = hypot(toScreen(p).x - o.x, toScreen(p).y - o.y)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    Box(Modifier.fillMaxSize().background(Navy900)) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Pinch-to-zoom и pan двумя пальцами
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.3f, 5f)
                        // Зумируем относительно centroid
                        panX = centroid.x - canvasSize.first / 2f -
                                (centroid.x - canvasSize.first / 2f - panX) * (newScale / scale) + pan.x
                        panY = centroid.y - canvasSize.second / 2f -
                                (centroid.y - canvasSize.second / 2f - panY) * (newScale / scale) + pan.y
                        scale = newScale
                    }
                }
                // Тап/перетаскивание одним пальцем
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Игнорируем если два пальца (зум)
                            if (event.changes.count { it.pressed } > 1) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val pos = change.position
                            when (event.type) {
                                PointerEventType.Press -> {
                                    val hit = nearestPoint(pos)
                                    if (hit >= 0) {
                                        draggingIdx = hit; selectedIdx = hit
                                    } else {
                                        points = (points + toLocal(pos)).toMutableList()
                                        selectedIdx = points.size - 1
                                        draggingIdx = selectedIdx
                                    }
                                    change.consume()
                                }
                                PointerEventType.Move -> {
                                    if (draggingIdx >= 0 && change.pressed) {
                                        points = points.toMutableList().also { it[draggingIdx] = toLocal(pos) }
                                        change.consume()
                                    }
                                }
                                PointerEventType.Release -> {
                                    draggingIdx = -1; change.consume()
                                }
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            canvasSize = Pair(size.width, size.height)
            val cxF = cx(); val cyF = cy()

            // Сетка
            val step = 50f * scale; val gridColor = Color(0x18FFFFFF)
            var gx = cxF % step; while (gx < size.width) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), 0.5f); gx += step }
            var gy = cyF % step; while (gy < size.height) { drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), 0.5f); gy += step }
            drawLine(Color(0x33FFFFFF), Offset(cxF, 0f), Offset(cxF, size.height), 1f)
            drawLine(Color(0x33FFFFFF), Offset(0f, cyF), Offset(size.width, cyF), 1f)

            // Объект
            val w = obj.width * scale; val h = obj.height * scale
            val left = cxF + obj.x * scale - w / 2f
            val top  = cyF - obj.y * scale - h / 2f
            drawRoundRect(color = obj.color.copy(alpha = 0.4f), topLeft = Offset(left, top),
                size = Size(w, h), cornerRadius = CornerRadius(obj.radius * scale, obj.radius * scale))
            drawRoundRect(color = Color.White.copy(0.3f), topLeft = Offset(left, top),
                size = Size(w, h), cornerRadius = CornerRadius(obj.radius * scale, obj.radius * scale), style = Stroke(1f))

            // Линии хитбокса
            if (points.size >= 2) {
                for (i in points.indices) {
                    drawLine(Color(0xFF22D3EE), toScreen(points[i]),
                        toScreen(points[(i + 1) % points.size]), strokeWidth = 2f)
                }
            }

            // Точки
            points.forEachIndexed { i, p ->
                val s = toScreen(p); val sel = i == selectedIdx
                drawCircle(if (sel) Color(0xFFFFD700) else Color(0xFF22D3EE), radius = if (sel) 10f else 7f, center = s)
                drawCircle(Color.White.copy(0.8f), radius = if (sel) 10f else 7f, center = s, style = Stroke(1.5f))
            }
        }

        // Топбар
        Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Surface1.copy(0.95f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = TextSec) }
                Text("Хитбокс: ${obj.name}", color = TextPrim, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (selectedIdx >= 0 && selectedIdx < points.size) {
                    IconButton(onClick = { points = points.toMutableList().also { it.removeAt(selectedIdx) }; selectedIdx = -1 }) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Danger)
                    }
                }
                IconButton(onClick = { points = mutableListOf(); selectedIdx = -1 }) {
                    Icon(Icons.Default.RestartAlt, null, tint = Warning)
                }
                IconButton(onClick = { onConfirm(points.toList()) }) {
                    Icon(Icons.Default.Check, null, tint = Color(0xFF22D3EE))
                }
            }
        }

        // Подсказка
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xCC0A0E1A))
            .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                if (points.isEmpty()) "Тап = добавить точку  •  Щипок = зум/пан"
                else "Точек: ${points.size}  •  Тяни точку = переместить  •  Щипок = зум",
                color = TextSec, fontSize = 12.sp
            )
        }
    }
}
