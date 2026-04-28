package su.SkrinVex.SkriPts.ui.resources

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.SkrinVex.SkriPts.build.ApkBuilder
import su.SkrinVex.SkriPts.data.SpriteAsset
import su.SkrinVex.SkriPts.ui.editor.EditorViewModel
import su.SkrinVex.SkriPts.ui.theme.*

/** Экран-хаб ресурсов: показывает карточки категорий */
@Composable
fun ResourcesScreen(
    vm: EditorViewModel,
    projectName: String,
    onOpenEditor: () -> Unit,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    var subScreen by remember { mutableStateOf<String?>(null) }

    when (subScreen) {
        "sprites" -> SpritesScreen(
            vm = vm,
            sprites = state.sprites,
            onBack = { subScreen = null }
        )
        else -> ResourcesHub(
            vm = vm,
            projectName = projectName,
            spriteCount = state.sprites.size,
            onOpenEditor = onOpenEditor,
            onOpenSprites = { subScreen = "sprites" },
            onBack = onBack
        )
    }
}

@Composable
private fun ResourcesHub(
    vm: EditorViewModel,
    projectName: String,
    spriteCount: Int,
    onOpenEditor: () -> Unit,
    onOpenSprites: () -> Unit,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    var showApkDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Navy900)) {
        Surface(color = Surface1, shadowElevation = 4.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад", tint = TextSec)
                }
                Column(Modifier.weight(1f)) {
                    Text(projectName, color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Менеджер проекта", color = TextSec, fontSize = 12.sp)
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Логика / Скрипты — всегда первая
            item {
                ResourceCard(
                    icon = Icons.Default.Code,
                    title = "Логика",
                    subtitle = "Скрипты, сцены, блоки",
                    color = Accent,
                    onClick = onOpenEditor
                )
            }
            // Спрайты — только если есть или всегда показываем
            item {
                ResourceCard(
                    icon = Icons.Default.Image,
                    title = "Изображения",
                    subtitle = if (spriteCount > 0) "$spriteCount спрайтов" else "Нет спрайтов",
                    color = su.SkrinVex.SkriPts.ui.theme.Warning,
                    onClick = onOpenSprites
                )
            }
            // Звуки — в разработке
            item {
                ResourceCard(
                    icon = Icons.Default.MusicNote,
                    title = "Звуки",
                    subtitle = "В разработке",
                    color = TextSec,
                    enabled = false,
                    onClick = {}
                )
            }
            // Экспорт в APK
            item {
                ResourceCard(
                    icon = Icons.Default.Android,
                    title = "Экспорт в APK",
                    subtitle = if (state.packageName.isBlank()) "Укажите имя пакета в настройках" else state.packageName,
                    color = su.SkrinVex.SkriPts.ui.theme.Success,
                    onClick = { showApkDialog = true }
                )
            }
        }
    }

    if (showApkDialog) {
        ApkExportDialog(
            vm = vm,
            packageName = state.packageName,
            projectName = projectName,
            onGoToSettings = { showApkDialog = false },
            onDismiss = { showApkDialog = false }
        )
    }
}

@Composable
private fun ResourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = if (enabled) 0.15f else 0.07f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = if (enabled) TextPrim else TextSec, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, color = TextSec, fontSize = 13.sp)
            }
            if (enabled) {
                Icon(Icons.Default.ChevronRight, null, tint = TextSec, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Полноэкранный список спрайтов */
@Composable
private fun SpritesScreen(
    vm: EditorViewModel,
    sprites: List<SpriteAsset>,
    onBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var previewSprite by remember { mutableStateOf<SpriteAsset?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingUri = uri; showAddDialog = true }
    }

    Column(Modifier.fillMaxSize().background(Navy900)) {
        Surface(color = Surface1, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Назад", tint = TextSec)
                }
                Column(Modifier.weight(1f)) {
                    Text("Изображения", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${sprites.size} спрайтов", color = TextSec, fontSize = 12.sp)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (sprites.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Image, null, tint = TextSec, modifier = Modifier.size(64.dp))
                    Text("Нет спрайтов", color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                    Text("Нажми + чтобы добавить PNG или JPG", color = TextSec, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sprites, key = { it.name }) { sprite ->
                        SpriteCard(
                            sprite = sprite,
                            file = vm.getSpriteFile(sprite.name),
                            onPreview = { previewSprite = sprite },
                            onDelete = { vm.deleteSprite(sprite.name) }
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = { pickLauncher.launch(arrayOf("image/png", "image/jpeg")) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = Accent
            ) {
                Icon(Icons.Default.Add, "Добавить спрайт", tint = Navy900)
            }
        }
    }

    if (showAddDialog && pendingUri != null) {
        AddSpriteDialog(
            suggestedName = pendingUri!!.lastPathSegment
                ?.substringAfterLast("/")?.substringBeforeLast(".")
                ?.replace(Regex("[^a-zA-Zа-яА-Я0-9_]"), "_") ?: "sprite",
            existingNames = sprites.map { it.name },
            onConfirm = { name ->
                val err = vm.addSprite(pendingUri!!, name)
                if (err != null) error = err
                showAddDialog = false; pendingUri = null
            },
            onDismiss = { showAddDialog = false; pendingUri = null }
        )
    }

    previewSprite?.let { sprite ->
        SpritePreviewDialog(sprite = sprite, file = vm.getSpriteFile(sprite.name), onDismiss = { previewSprite = null })
    }

    error?.let { err ->
        AlertDialog(
            onDismissRequest = { error = null }, containerColor = Surface2,
            icon = { Icon(Icons.Default.ErrorOutline, null, tint = Danger) },
            title = { Text("Ошибка", color = TextPrim) },
            text = { Text(err, color = TextSec) },
            confirmButton = { Button(onClick = { error = null }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("OK", color = Navy900) } }
        )
    }
}

@Composable
private fun SpriteCard(sprite: SpriteAsset, file: java.io.File?, onPreview: () -> Unit, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    val bitmap = remember(file) { file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() } }

    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onPreview),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = sprite.name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.BrokenImage, null, tint = TextSec, modifier = Modifier.size(32.dp))
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Navy900.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sprite.name, color = TextPrim, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.Default.DeleteOutline, "Удалить", tint = Danger.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp).clickable { showConfirm = true })
                }
                if (sprite.width > 0) Text("${sprite.width}×${sprite.height}", color = TextSec, fontSize = 10.sp)
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false }, containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить спрайт?", color = TextPrim) },
            text = { Text("«${sprite.name}» будет удалён из проекта.", color = TextSec) },
            confirmButton = { Button(onClick = { onDelete(); showConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Отмена", color = TextSec) } }
        )
    }
}

@Composable
private fun AddSpriteDialog(suggestedName: String, existingNames: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(suggestedName) }
    val isValid = name.isNotBlank() && name !in existingNames && name.matches(Regex("[a-zA-Zа-яА-Я0-9_]+"))
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface2,
        icon = { Icon(Icons.Default.Image, null, tint = Accent) },
        title = { Text("Добавить спрайт", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Имя спрайта — уникальный идентификатор в блоках.", color = TextSec, fontSize = 13.sp)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Имя спрайта") }, singleLine = true,
                    isError = !isValid && name.isNotBlank(),
                    supportingText = when {
                        name in existingNames -> {{ Text("Имя уже занято", color = Danger) }}
                        name.isNotBlank() && !name.matches(Regex("[a-zA-Zа-яА-Я0-9_]+")) -> {{ Text("Только буквы, цифры и _", color = Danger) }}
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, focusedLabelColor = Accent,
                        cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim)
                )
            }
        },
        confirmButton = { Button(onClick = { if (isValid) onConfirm(name.trim()) }, enabled = isValid, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Добавить", color = Navy900) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
private fun SpritePreviewDialog(sprite: SpriteAsset, file: java.io.File?, onDismiss: () -> Unit) {
    val bitmap = remember(file) { file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Surface2, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, null, tint = Accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(sprite.name, color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = sprite.name,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)).background(Surface3), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.BrokenImage, null, tint = TextSec, modifier = Modifier.size(48.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Файл:", color = TextSec, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                        Text(sprite.fileName, color = TextPrim, fontSize = 13.sp)
                    }
                    if (sprite.width > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Размер:", color = TextSec, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                        Text("${sprite.width} × ${sprite.height} px", color = TextPrim, fontSize = 13.sp)
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("Закрыть", color = Navy900)
                }
            }
        }
    }
}

@Composable
private fun ApkExportDialog(
    vm: EditorViewModel,
    packageName: String,
    projectName: String,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    if (packageName.isBlank()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = su.SkrinVex.SkriPts.ui.theme.Surface2,
            icon = { Icon(Icons.Default.Android, null, tint = su.SkrinVex.SkriPts.ui.theme.Warning) },
            title = { Text("Имя пакета не указано", color = su.SkrinVex.SkriPts.ui.theme.TextPrim) },
            text = { Text("Укажите имя пакета в настройках проекта (кнопка ⚙ в редакторе).\n\nПример: com.example.mygame", color = su.SkrinVex.SkriPts.ui.theme.TextSec) },
            confirmButton = {
                Button(onClick = onGoToSettings, colors = ButtonDefaults.buttonColors(containerColor = su.SkrinVex.SkriPts.ui.theme.Accent)) {
                    Text("Понял", color = su.SkrinVex.SkriPts.ui.theme.Navy900)
                }
            }
        )
        return
    }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Состояния: null = ожидание, "..." = прогресс, "done" = готово, "error:..." = ошибка
    var buildStep by remember { mutableStateOf<String?>(null) }
    var buildError by remember { mutableStateOf<String?>(null) }
    // Путь к готовому tmp файлу — устанавливается после успешной сборки
    var builtTmpFile by remember { mutableStateOf<java.io.File?>(null) }

    // Лаунчер открывается ТОЛЬКО после того как builtTmpFile != null
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val tmp = builtTmpFile ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            // Пользователь отменил — оставляем tmp, можно попробовать снова
            buildStep = null
            return@rememberLauncherForActivityResult
        }
        buildStep = "Сохранение файла..."
        scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                tmp.delete()
                builtTmpFile = null
                buildStep = "done"
            } catch (e: Exception) {
                buildError = e.message ?: "Ошибка сохранения"
                buildStep = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (buildStep == null && buildStep != "done") onDismiss() },
        containerColor = su.SkrinVex.SkriPts.ui.theme.Surface2,
        icon = { Icon(Icons.Default.Android, null, tint = su.SkrinVex.SkriPts.ui.theme.Success) },
        title = { Text("Экспорт в APK", color = su.SkrinVex.SkriPts.ui.theme.TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    buildStep == "done" -> {
                        Text("✓ APK успешно сохранён!", color = su.SkrinVex.SkriPts.ui.theme.Success)
                    }
                    buildError != null -> {
                        Text("Ошибка: $buildError", color = su.SkrinVex.SkriPts.ui.theme.Danger)
                    }
                    buildStep != null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(color = su.SkrinVex.SkriPts.ui.theme.Accent, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(buildStep!!, color = su.SkrinVex.SkriPts.ui.theme.TextSec, fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Text("Проект: $projectName", color = su.SkrinVex.SkriPts.ui.theme.TextPrim)
                        Text("Пакет: $packageName", color = su.SkrinVex.SkriPts.ui.theme.TextSec, fontSize = 13.sp)
                        Text("Подпись: тестовая (debug)", color = su.SkrinVex.SkriPts.ui.theme.TextSec, fontSize = 12.sp)
                        Text("⚠ APK с тестовой подписью нельзя опубликовать в Google Play", color = su.SkrinVex.SkriPts.ui.theme.Warning, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            when {
                buildStep == null && buildError == null && builtTmpFile == null -> {
                    Button(
                        onClick = {
                            buildError = null
                            buildStep = "Шифрование проекта..."
                            scope.launch(Dispatchers.IO) {
                                val project = vm.buildProject()
                                val tmp = java.io.File(ctx.cacheDir, "skripts_export_${project.id}.apk")
                                var lastError: String? = null
                                su.SkrinVex.SkriPts.build.ApkBuilder.build(ctx, project, packageName, tmp) { step ->
                                    if (step is su.SkrinVex.SkriPts.build.ApkBuilder.StepError) lastError = step.error
                                    buildStep = step.message
                                }
                                if (lastError != null) {
                                    buildError = lastError
                                    buildStep = null
                                    tmp.delete()
                                } else if (tmp.exists() && tmp.length() > 0) {
                                    builtTmpFile = tmp
                                    val safeName = projectName.replace(Regex("[^a-zA-Zа-яА-Я0-9_]"), "_")
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        buildStep = null
                                        saveLauncher.launch("$safeName.apk")
                                    }
                                } else {
                                    buildError = "Файл APK не был создан"
                                    buildStep = null
                                    tmp.delete()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = su.SkrinVex.SkriPts.ui.theme.Success)
                    ) { Text("Собрать APK") }
                }
                buildStep == "done" || buildError != null -> {
                    Button(
                        onClick = {
                            builtTmpFile?.delete()
                            builtTmpFile = null
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = su.SkrinVex.SkriPts.ui.theme.Accent)
                    ) { Text("Закрыть", color = su.SkrinVex.SkriPts.ui.theme.Navy900) }
                }
            }
        },
        dismissButton = {
            if (buildStep == null && buildStep != "done" && buildError == null) {
                TextButton(onClick = onDismiss) { Text("Отмена", color = su.SkrinVex.SkriPts.ui.theme.TextSec) }
            }
        }
    )
}
