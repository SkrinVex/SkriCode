package su.SkrinVex.SkriPts.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.data.ScriptProject
import su.SkrinVex.SkriPts.ui.theme.*

@Composable
fun HomeScreen(vm: HomeViewModel, onOpenProject: (String?) -> Unit, onThemeChanged: () -> Unit = {}) {
    val projects by vm.projects.collectAsState()
    val importError by vm.importError.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    // Лаунчер для выбора файла импорта
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importProject(it) } }

    LaunchedEffect(Unit) { vm.refresh() }

    Box(Modifier.fillMaxSize().background(Navy900)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Surface1, shadowElevation = 4.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Code, null, tint = Accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SkriPts", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrim)
                        Text("Визуальный конструктор программ", fontSize = 12.sp, color = TextSec)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*", "application/json")) }) {
                        Icon(Icons.Default.FileDownload, "Импорт проекта", tint = TextSec)
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Default.Help, "Справка", tint = TextSec)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Настройки", tint = TextSec)
                    }
                }
            }

            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.FolderOpen, null, tint = TextSec, modifier = Modifier.size(64.dp))
                        Text("Нет проектов", color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                        Text("Нажми + чтобы создать первый", color = TextSec, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*", "application/json")) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(0.5f))
                        ) {
                            Icon(Icons.Default.FileDownload, null, tint = Accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Импортировать проект", color = Accent)
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { vm.delete(project.id) },
                            onRename = { name -> vm.rename(project.id, name) },
                            onExport = { uri -> vm.exportProject(project, uri) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent
        ) {
            Icon(Icons.Default.Add, "Новый проект", tint = Navy900)
        }
    }

    // Диалог ошибки импорта/экспорта
    importError?.let { err ->
        AlertDialog(
            onDismissRequest = vm::clearImportError,
            containerColor = Surface2,
            icon = { Icon(Icons.Default.ErrorOutline, null, tint = Danger) },
            title = { Text("Ошибка", color = TextPrim) },
            text = { Text(err, color = TextSec) },
            confirmButton = {
                Button(onClick = vm::clearImportError,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("OK", color = Navy900)
                }
            }
        )
    }

    if (showNewDialog) {
        NewProjectDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name -> vm.createProject(name); showNewDialog = false; onOpenProject(null) }
        )
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onThemeChanged = onThemeChanged
        )
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ScriptProject,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onExport: (Uri) -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? -> uri?.let { onExport(it) } }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onOpen,
            onLongClick = { showMenu = true }
        ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val blockCount = project.scripts?.sumOf { it.blocks.size } ?: 0
                Text("${project.scripts?.size ?: 0} скриптов · $blockCount блоков", color = TextSec, fontSize = 13.sp)
            }
            IconButton(onClick = {
                val safeName = project.name.replace(Regex("[^a-zA-Zа-яА-Я0-9_\\- ]"), "_")
                exportLauncher.launch("$safeName.skripts")
            }) {
                Icon(Icons.Default.FileUpload, "Экспорт", tint = TextSec.copy(alpha = 0.7f))
            }
        }
    }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            containerColor = Surface2,
            title = { Text(project.name, color = TextPrim) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showMenu = false; showRename = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Переименовать")
                    }
                    Button(onClick = { showMenu = false; showConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Danger)
                    ) {
                        Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMenu = false }) { Text("Отмена", color = TextSec) } }
        )
    }

    if (showRename) {
        var newName by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.Edit, null, tint = Accent) },
            title = { Text("Переименовать", color = TextPrim) },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
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
                    onClick = { if (newName.isNotBlank()) { onRename(newName.trim()); showRename = false } },
                    enabled = newName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("Сохранить", color = Navy900) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Отмена", color = TextSec) } }
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить проект?", color = TextPrim) },
            text = { Text("«${project.name}» будет удалён без возможности восстановления.", color = TextSec) },
            confirmButton = {
                Button(onClick = { onDelete(); showConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Отмена", color = TextSec) }
            }
        )
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onThemeChanged: () -> Unit) {
    var selectedTheme by remember { mutableStateOf(ThemeManager.getCurrentTheme()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("Настройки", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Тема оформления", color = TextPrim, fontWeight = FontWeight.SemiBold)
                
                AppTheme.entries.forEach { theme ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTheme == theme) theme.accent.copy(0.15f) else Surface3)
                            .clickable { selectedTheme = theme }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(theme.accent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            theme.displayName, 
                            color = if (selectedTheme == theme) theme.accent else TextPrim,
                            fontWeight = if (selectedTheme == theme) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    ThemeManager.setTheme(ctx, selectedTheme)
                    onThemeChanged()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Применить", color = Navy900) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSec)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        modifier = Modifier.fillMaxHeight(),
        dragHandle = null
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxHeight()
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, null, tint = Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Справка по SkriPts", 
                        color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
            }
            
            item { HelpSection(Icons.Default.Info, "Основы", listOf(
                "SkriPts — визуальный конструктор для создания интерактивных сцен",
                "Создавайте скрипты из блоков, запускайте симуляцию и взаимодействуйте с объектами",
                "Используйте переменные для хранения данных и выражения для вычислений",
                "Зажмите карточку проекта чтобы переименовать или удалить его"
            )) }
            
            item { HelpSection(Icons.Default.TouchApp, "Работа с блоками", listOf(
                "Зажмите блок для вызова меню: дублировать или удалить",
                "Стрелки вверх/вниз — изменить порядок блоков",
                "Иконка с крестиком — визуальное позиционирование объекта на сцене",
                "Стрелка вниз/вверх справа — свернуть/развернуть параметры блока"
            )) }
            
            item { HelpSection(Icons.Default.DataObject, "Переменные", listOf(
                "Глобальные переменные доступны во всех скриптах",
                "Локальные переменные доступны только в текущем скрипте",
                "Используйте {имя} для обращения к переменной: {score} + 10"
            )) }

            item { HelpSection(Icons.Default.TableChart, "Таблицы", listOf(
                "Таблица — словарь ключ→значение, бывают глобальные и локальные",
                "Создай таблицу: редактор выражений → вкладка «Таблицы» → кнопка +",
                "Нажми ✏ рядом с таблицей чтобы открыть редактор и заполнить данные заранее",
                "Блок «Таблица: записать» — записывает значение: [scores.level1] = 500",
                "Блок «Таблица: читать» — читает значение в переменную",
                "В выражениях: [таблица.ключ] — подставляет значение по ключу",
                "Ключ может быть переменной: [scores.{currentLevel}]",
                "[таблица] — вся таблица одной строкой: key1=val1, key2=val2"
            )) }

            item { HelpSection(Icons.Default.Loop, "Перебор таблицы в цикле", listOf(
                "\$tableSize(имя) — количество записей в таблице",
                "\$tableKey(имя, i) — ключ записи по индексу (начиная с 0)",
                "\$tableVal(имя, i) — значение записи по индексу (начиная с 0)",
                "Пример — вывести все записи таблицы scores построчно:",
                "  1. Создай локальную таблицу scores с нужными данными",
                "  2. Переменная i = 0",
                "  3. Переменная pos = 0",
                "  4. Цикл пока: {i} < \$tableSize(scores)",
                "       sim_text  имя: text{i}  текст: \$tableKey(scores, {i}) + \": \" + \$tableVal(scores, {i})",
                "                 x: 0  y: {pos}  ширина: 200  высота: 40",
                "       Переменная i = {i} + 1",
                "       Переменная pos = {pos} - 200",
                "Каждая строка таблицы создаёт отдельный текстовый объект со смещением вниз",
                "Имя объекта text{i} уникально для каждой итерации благодаря переменной {i}"
            )) }
            
            item { HelpSection(Icons.Default.PhoneAndroid, "Константы экрана", listOf(
                "\$screenWidth — ширина экрана в пикселях",
                "\$screenHeight — высота экрана в пикселях", 
                "\$screenTop — Y верхнего края (screenHeight / 2)",
                "\$screenBottom — Y нижнего края (-screenHeight / 2)",
                "\$screenLeft — X левого края (-screenWidth / 2)",
                "\$screenRight — X правого края (screenWidth / 2)"
            )) }
            
            item { HelpSection(Icons.Default.Functions, "Функции", listOf(
                "\$rand(0, 100) — случайное число от 0 до 100",
                "\$add(5, 3) — сложение: 5 + 3 = 8",
                "\$sub(10, 3) — вычитание: 10 - 3 = 7", 
                "\$mul(4, 5) — умножение: 4 × 5 = 20",
                "\$div(20, 4) — деление: 20 ÷ 4 = 5",
                "\$abs(-5) — модуль числа: |-5| = 5",
                "\$sqrt(9) — квадратный корень: √9 = 3",
                "\$min(3, 7) — минимум: min(3, 7) = 3",
                "\$max(3, 7) — максимум: max(3, 7) = 7"
            )) }
            
            item { HelpSection(Icons.Default.AccountTree, "Логические функции", listOf(
                "\$and(true, false) — логическое И: true && false = false",
                "\$or(true, false) — логическое ИЛИ: true || false = true",
                "\$not(true) — логическое НЕ: !true = false"
            )) }
            
            item { HelpSection(Icons.Default.TextFields, "Строковые функции", listOf(
                "\$concat(\"Привет\", \" мир\") — соединение: \"Привет мир\"",
                "\$length(\"текст\") — длина строки: 5",
                "\$upper(\"текст\") — в верхний регистр: \"ТЕКСТ\"",
                "\$lower(\"ТЕКСТ\") — в нижний регистр: \"текст\""
            )) }

            item { HelpSection(Icons.Default.Widgets, "Функции объектов", listOf(
                "\$objX(имя) — позиция объекта по X (горизонталь)",
                "\$objY(имя) — позиция объекта по Y (вертикаль)",
                "\$objRot(имя) — угол поворота объекта в градусах",
                "Работает со всеми типами: sim_create, sim_text, sim_joystick",
                "Пример: \$objX(Button) + 50 — правее кнопки на 50px",
                "Пример: set_var score = \$objY(Player) — сохранить Y игрока"
            )) }
            
            item { HelpSection(Icons.Default.Tune, "Блоки управления", listOf(
                "Условие (если) — выполняет блоки если условие истинно",
                "Цикл (повторить) — повторяет блоки N раз, создаёт переменную {i}",
                "Цикл (пока) — повторяет пока условие истинно",
                "Ждать — пауза в выполнении (в симуляции показывает сообщение)",
                "Завершить симуляцию — останавливает выполнение скрипта"
            )) }
            
            item { HelpSection(Icons.Default.Widgets, "Блоки симуляции", listOf(
                "Создать объект — создаёт прямоугольник на сцене",
                "Переместить объект — меняет позицию (моментально или шагом)",
                "  X или Y = \$none — не изменять эту координату при перемещении",
                "Изменить размер — меняет ширину и высоту",
                "Изменить цвет — меняет цвет объекта (#RRGGBB)",
                "Изменить свойства — универсальный блок для изменения любых свойств",
                "  Автоматически определяет тип объекта (прямоугольник, текст, джойстик)",
                "  Показывает только доступные свойства конкретного объекта",
                "  Можно изменить несколько свойств одновременно через кнопку +",
                "  Работает с объектами созданными в том же скрипте",
                "Текст на объекте — добавляет текст внутри прямоугольника",
                "Текстовый объект — создаёт текст на сцене",
                "Скрыть объект — делает невидимым и неклкабельным",
                "Показать объект — делает видимым и кликабельным"
            )) }
            
            item { HelpSection(Icons.Default.Lightbulb, "Примеры выражений", listOf(
                "{x} + 50 — прибавить 50 к переменной x (число)",
                "Текст + {score} — конкатенация: если хотя бы одна сторона строка",
                "\"Счёт: \" + {score} — строка + число = \"Счёт: 42\"",
                "\$screenWidth / 2 — половина ширины экрана",
                "\$rand(-100, 100) — случайное число от -100 до 100",
                "\$add({score}, 10) — увеличить счёт на 10",
                "[scores.{level}] + \" очков\" — значение из таблицы + текст"
            )) }
            
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun HelpSection(icon: ImageVector, title: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Text("• ", color = TextSec, fontSize = 14.sp)
                Text(item, color = TextPrim, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.CreateNewFolder, null, tint = Accent) },
        title = { Text("Новый проект", color = TextPrim) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название проекта") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                    cursorColor = Accent,
                    unfocusedTextColor = TextPrim,
                    focusedTextColor = TextPrim
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Создать", color = Navy900) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) }
        }
    )
}
