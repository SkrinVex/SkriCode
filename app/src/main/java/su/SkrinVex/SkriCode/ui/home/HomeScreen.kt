package su.SkrinVex.SkriCode.ui.home

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.data.ScriptProject
import su.SkrinVex.SkriCode.ui.theme.*

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
                        Text("SkriCode", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrim)
                        Text("Визуальный конструктор программ", fontSize = 12.sp, color = TextSec)
                    }
                    val ctx = LocalContext.current
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SkriCode")))
                    }) {
                        Icon(Icons.Default.Send, "Сообщество", tint = TextSec)
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
                            onOpen = { vm.recordOpen(project.id); onOpenProject(project.id) },
                            onDelete = { vm.delete(project.id) },
                            onRename = { name -> vm.rename(project.id, name) },
                            onExport = { uri -> vm.exportProject(project, uri) }
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("*/*", "application/json")) },
                containerColor = Surface2,
                contentColor = Accent
            ) {
                Icon(Icons.Default.FileDownload, "Импорт проекта")
            }
            FloatingActionButton(
                onClick = { showNewDialog = true },
                containerColor = Accent
            ) {
                Icon(Icons.Default.Add, "Новый проект", tint = Navy900)
            }
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
                val scenes = project.scenes
                val scriptCount = scenes?.sumOf { it.scripts.size } ?: (project.scripts?.size ?: 0)
                val blockCount = scenes?.sumOf { s -> s.scripts.sumOf { it.blocks.size } }
                    ?: (project.scripts?.sumOf { it.blocks.size } ?: 0)
                val spriteCount = project.sprites?.size ?: 0
                val spriteStr = if (spriteCount > 0) " · $spriteCount сп." else ""
                Text("$scriptCount скриптов · $blockCount блоков$spriteStr", color = TextSec, fontSize = 13.sp)
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
    var query by remember { mutableStateOf("") }

    // Все разделы справки
    data class HelpEntry(val icon: ImageVector, val title: String, val items: List<String>)
    val allSections = listOf(
        HelpEntry(Icons.Default.Info, "Основы", listOf(
            "SkriCode — визуальный конструктор для создания интерактивных сцен",
            "Создавайте скрипты из блоков, запускайте симуляцию и взаимодействуйте с объектами",
            "Используйте переменные для хранения данных и выражения для вычислений",
            "Зажмите карточку проекта чтобы переименовать или удалить его"
        )),
        HelpEntry(Icons.Default.Image, "Спрайты", listOf(
            "Спрайты — изображения (PNG/JPG) которые можно назначать на объекты",
            "Управление спрайтами: нажми на проект → вкладка «Спрайты»",
            "Нажми + чтобы импортировать изображение с устройства",
            "Имя спрайта — уникальный идентификатор (только буквы, цифры, _)",
            "Нажми на спрайт в списке чтобы просмотреть его",
            "Блок «Установить текстуру» — назначает спрайт на существующий объект",
            "  Поддерживает прямоугольники, текстовые объекты и джойстики",
            "  Параметры: масштаб X/Y, прозрачность (0..1), обрезка (cropX/Y/W/H)",
            "  Обрезка 0 = вся ширина/высота спрайта",
            "Блок «Создать спрайт-объект» — создаёт объект с текстурой",
            "  Размер автоматически берётся из спрайта если ширина/высота = 0",
            "  Хитбокс по размеру объекта (как у обычного прямоугольника)",
            "В редакторе локации: кнопка + → «Создать спрайт-объект»",
            "Экспорт проекта теперь ZIP-архив — спрайты включены автоматически",
            "При импорте проекта спрайты восстанавливаются вместе с проектом"
        )),
        HelpEntry(Icons.Default.TouchApp, "Работа с блоками", listOf(
            "Зажмите блок для вызова меню: дублировать, копировать или удалить",
            "Стрелки вверх/вниз — изменить порядок блоков",
            "Иконка с крестиком — визуальное позиционирование объекта на сцене",
            "Стрелка вниз/вверх справа — свернуть/развернуть параметры блока",
            "Копировать блок — сохраняет блок в буфер, кнопка «Вставить блок» появится внизу слева",
            "Зажмите скрипт (вкладку) → «Копировать скрипт» — копирует весь скрипт в буфер",
            "Кнопка «Вставить» исчезает после вставки автоматически"
        )),
        HelpEntry(Icons.Default.Layers, "Сцены", listOf(
            "Сцена — независимый экран со своими скриптами и объектами локации",
            "Панель сцен находится над панелью скриптов в редакторе",
            "Кнопка + — добавить новую сцену",
            "Зажать на сцену — переименовать или удалить",
            "Глобальные переменные и таблицы общие для всех сцен",
            "Блок «Перейти на сцену» — переключает активную сцену во время симуляции",
            "  Поле «Сцена» — выпадающий список всех сцен проекта",
            "  При переходе: текущая сцена останавливается, глобальные переменные сохраняются",
            "  Работает из любого скрипта: ON_START, ON_TAP, ON_COLLISION и т.д.",
            "Скрипты и объекты локации можно копировать между сценами"
        )),
        HelpEntry(Icons.Default.DataObject, "Переменные", listOf(
            "Глобальные переменные доступны во всех скриптах",
            "Локальные переменные доступны только в текущем скрипте",
            "Используйте {имя} для обращения к переменной: {score} + 10"
        )),
        HelpEntry(Icons.Default.TableChart, "Таблицы", listOf(
            "Таблица — словарь ключ→значение, бывают глобальные и локальные",
            "Создай таблицу: редактор выражений → вкладка «Таблицы» → кнопка +",
            "Нажми ✏ рядом с таблицей чтобы открыть редактор и заполнить данные заранее",
            "Блок «Таблица: записать» — поле «Ключ» это просто строка, например: level1",
            "  ⚠ НЕ пиши [таблица.ключ] в поле «Ключ» — это синтаксис ЧТЕНИЯ, не записи!",
            "  Правильно: ключ = level1, значение = 500",
            "  Динамический ключ из переменной: ключ = {currentLevel}",
            "Блок «Таблица: читать» — читает значение в переменную",
            "В выражениях: [таблица.ключ] — подставляет значение по ключу (только для чтения!)",
            "Ключ может быть переменной: [scores.{currentLevel}]",
            "[таблица] — вся таблица одной строкой: key1=val1, key2=val2"
        )),
        HelpEntry(Icons.Default.Loop, "Перебор таблицы в цикле", listOf(
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
        )),
        HelpEntry(Icons.Default.PhoneAndroid, "Константы экрана", listOf(
            "\$screenWidth — ширина экрана в пикселях",
            "\$screenHeight — высота экрана в пикселях",
            "\$screenTop — Y верхнего края (screenHeight / 2)",
            "\$screenBottom — Y нижнего края (-screenHeight / 2)",
            "\$screenLeft — X левого края (-screenWidth / 2)",
            "\$screenRight — X правого края (screenWidth / 2)"
        )),
        HelpEntry(Icons.Default.Save, "Сохранения (память устройства)", listOf(
            "Данные сохраняются на устройстве и остаются после закрытия приложения",
            "Блок «Сохранить переменную» — сохраняет значение по ключу",
            "Блок «Загрузить переменную» — загружает сохранённое значение в переменную",
            "Блок «Сохранить таблицу» — сохраняет всю таблицу целиком",
            "Блок «Загрузить таблицу» — загружает таблицу из памяти",
            "\$saveExists(ключ) — возвращает true если сохранение с таким ключом существует"
        )),
        HelpEntry(Icons.Default.Lock, "Шифрование сохранений", listOf(
            "Каждый блок сохранения/загрузки имеет переключатель «Шифровать»",
            "Ключ шифрования задаётся в: Настройки (иконка ⚙ с ползунками) → Хранилище ключей",
            "Ключ должен быть минимум 8 символов",
            "Если ключ не задан — блок с шифрованием выдаст ошибку при запуске",
            "Ключ шифрования НЕ экспортируется вместе с проектом"
        )),
        HelpEntry(Icons.Default.Functions, "Функции", listOf(
            "\$rand(0, 100) — случайное число от 0 до 100",
            "\$abs(-5) — модуль числа: 5",
            "\$sqrt(9) — квадратный корень: 3",
            "\$min(3, 7) — минимум: 3",
            "\$max(3, 7) — максимум: 7",
            "\$add, \$sub, \$mul, \$div — арифметика"
        )),
        HelpEntry(Icons.Default.TextFields, "Строковые функции", listOf(
            "\$concat(\"Привет\", \" мир\") — соединение: \"Привет мир\"",
            "\$length(\"текст\") — длина строки: 5",
            "\$upper(\"текст\") — в верхний регистр: \"ТЕКСТ\"",
            "\$lower(\"ТЕКСТ\") — в нижний регистр: \"текст\""
        )),
        HelpEntry(Icons.Default.Widgets, "Функции объектов", listOf(
            "\$objX(имя) — позиция объекта по X",
            "\$objY(имя) — позиция объекта по Y",
            "\$objRot(имя) — угол поворота объекта в градусах",
            "\$objVx(имя) — скорость физического тела по X",
            "\$objVy(имя) — скорость физического тела по Y",
            "\$objDirX(имя) — X-компонент направления (sin угла поворота)",
            "\$objDirY(имя) — Y-компонент направления (cos угла поворота)",
            "\$objFrontX(имя, дист) — X точки перед объектом на расстоянии",
            "\$objFrontY(имя, дист) — Y точки перед объектом на расстоянии",
            "\$objGrounded(имя) — true если объект стоит на статическом объекте",
            "Стрельба: x=\$objFrontX(player,80), vx=\$mul(\$objDirX(player),500)"
        )),
        HelpEntry(Icons.Default.Tune, "Блоки управления", listOf(
            "Условие (если) — выполняет блоки если условие истинно",
            "Цикл (повторить) — повторяет блоки N раз, создаёт переменную {i}",
            "Цикл (пока) — повторяет пока условие истинно",
            "Ждать — пауза в выполнении",
            "Завершить симуляцию — останавливает выполнение скрипта"
        )),
        HelpEntry(Icons.Default.Widgets, "Блоки симуляции", listOf(
            "Создать объект — создаёт прямоугольник на сцене",
            "Переместить объект — меняет позицию (моментально или шагом)",
            "  X или Y = \$none — не изменять эту координату",
            "Изменить размер — меняет ширину и высоту",
            "Изменить цвет — меняет цвет объекта (#RRGGBB)",
            "Изменить свойства — универсальный блок для изменения любых свойств",
            "Текстовый объект — создаёт текст на сцене",
            "Скрыть/Показать объект — управление видимостью"
        )),
        HelpEntry(Icons.Default.Videocam, "Камера слежения", listOf(
            "Блок «Создать камеру» — создаёт камеру которая следит за объектом",
            "  Плавность: 1 = мгновенно, 0.05 = очень плавно",
            "  Теги интерфейса — объекты с этими тегами не двигаются с камерой",
            "Блок «Переключить камеру» — включает или выключает камеру",
            "Одновременно может работать только одна камера"
        )),
        HelpEntry(Icons.Default.Science, "Физика объектов", listOf(
            "Блок «Физика объекта» — добавляет физическое тело к объекту",
            "  gravity — ускорение свободного падения (отрицательное = вниз)",
            "  static = true — объект не двигается физикой (стены, пол)",
            "  bounciness — упругость при столкновении: 0 = нет отскока, 1 = полный",
            "  mass — масса объекта (влияет на импульс при столкновении)",
            "  vx/vy — начальная скорость по X и Y",
            "Блок «Переключить физику» — включает/выключает физику всей симуляции",
            "Блок «Импульс (прыжок)» — мгновенно добавляет скорость физическому телу",
            "  vx/vy — добавляемая скорость (не устанавливает, а прибавляет к текущей)",
            "Блок «Физическое движение» — движение объекта по направлению его поворота",
            "  speed — скорость вперёд (по направлению объекта)",
            "  turn — угловая скорость в градусах за тик (поворот)",
            "  friction — трение: 0.9 = умеренное торможение, 1.0 = нет трения",
            "  Используется для машин, кораблей и других транспортных средств"
        )),
        HelpEntry(Icons.Default.DirectionsRun, "Примеры: платформер (прыжок)", listOf(
            "Цель: объект прыгает при нажатии кнопки и падает под гравитацией",
            "",
            "ON_START:",
            "  «Физика объекта» → player: gravity=-9.8, static=false",
            "  «Физика объекта» → floor: gravity=0, static=true",
            "  «Джойстик» → target=player, speed=5 (движение влево/вправо)",
            "",
            "ON_TAP кнопки прыжка:",
            "  «Условие» → \$objGrounded(player) == true",
            "    Если истина: «Импульс (прыжок)» → player: vx=0, vy=500",
            "",
            "\$objGrounded(player) — true если игрок стоит на статическом объекте.",
            "Без проверки игрок может прыгать в воздухе.",
            "Джойстик позволяет двигаться влево/вправо в воздухе."
        )),
        HelpEntry(Icons.Default.GpsFixed, "Примеры: стрельба", listOf(
            "Цель: нажимаешь кнопку — пуля летит в направлении игрока",
            "",
            "ON_START:",
            "  «Создать спрайт-объект» или «Создать объект» → shoot (кнопка огня)",
            "  «Физика объекта» → player: gravity=0 (вид сверху)",
            "  «Джойстик» → target=player, directional=true (поворот по направлению)",
            "",
            "ON_TAP кнопки shoot:",
            "  «Создать объект» → ammo: x=\$objFrontX(player,80), y=\$objFrontY(player,80)",
            "  «Физика объекта» → ammo: gravity=0, vx=\$mul(\$objDirX(player),500), vy=\$mul(\$objDirY(player),500)",
            "  «Игнорировать коллизию» → ammo: ignore=player",
            "",
            "\$objFrontX(player,80) — точка перед игроком на 80px (пуля не внутри игрока)",
            "\$objDirX/Y(player) — вектор направления по углу поворота игрока",
            "«Игнорировать коллизию» — пуля не толкает игрока при создании"
        )),
        HelpEntry(Icons.Default.DirectionsCar, "Примеры: управление машиной", listOf(
            "Цель: кнопки газа/тормоза/поворота управляют машиной через физику",
            "",
            "ON_START:",
            "  «Физика объекта» → car: gravity=0, static=false",
            "",
            "ON_HOLD кнопки газа:",
            "  «Физическое движение» → car: speed=5, turn=0, friction=0.95",
            "",
            "ON_HOLD кнопки поворота влево:",
            "  «Физическое движение» → car: speed=0, turn=-3, friction=0.98",
            "",
            "ON_HOLD кнопки поворота вправо:",
            "  «Физическое движение» → car: speed=0, turn=3, friction=0.98",
            "",
            "Машина плавно разгоняется и поворачивается.",
            "friction < 1 создаёт эффект торможения при отпускании кнопки."
        )),
        HelpEntry(Icons.Default.CropFree, "Хитбоксы", listOf(
            "Хитбокс — область объекта участвующая в коллизиях",
            "Авто (auto) — хитбокс автоматически по размеру объекта",
            "Ручной (manual) — нажми «Нарисовать» чтобы открыть редактор хитбокса",
            "Показать хитбоксы — переключатель в настройках симуляции"
        )),
        HelpEntry(Icons.Default.Bolt, "Коллизии (столкновения)", listOf(
            "Коллизии работают только между объектами с физическим телом",
            "Событие «При столкновении» — срабатывает в момент начала касания",
            "Событие «При окончании столкновения» — срабатывает когда объекты разошлись",
            "{collision_self} — имя объекта которому принадлежит этот скрипт",
            "{collision_other} — имя другого объекта (с кем столкнулись)",
            "{collision_x/y} — позиция другого объекта в момент столкновения"
        )),
        HelpEntry(Icons.Default.DeleteOutline, "Удаление объектов", listOf(
            "Блок «Удалить объект» — удаляет объект или джойстик со сцены",
            "Поддерживает имя объекта, тег (#tag) и переменные ({имя})",
            "По тегу удаляются все объекты с этим тегом одновременно"
        ))
    )

    val filtered = remember(query) {
        if (query.isBlank()) allSections
        else allSections.filter { section ->
            section.title.contains(query, ignoreCase = true) ||
            section.items.any { it.contains(query, ignoreCase = true) }
        }.map { section ->
            if (section.title.contains(query, ignoreCase = true)) section
            else section.copy(items = section.items.filter { it.contains(query, ignoreCase = true) })
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        modifier = Modifier.fillMaxHeight(),
        dragHandle = null
    ) {
        Column(Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars)) {
            // Заголовок + поиск
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, null, tint = Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Справка по SkriCode", color = TextPrim, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск по справке...", color = TextSec) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSec) },
                    trailingIcon = if (query.isNotBlank()) {{ IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, null, tint = TextSec)
                    }}} else null,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
                        focusedTextColor = TextPrim, unfocusedTextColor = TextPrim,
                        cursorColor = Accent
                    )
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Text("Ничего не найдено по запросу «$query»", color = TextSec, fontSize = 14.sp)
                        }
                    }
                } else {
                    items(filtered) { section ->
                        HelpSection(section.icon, section.title, section.items)
                    }
                }
            }
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
private fun HelpSectionRich(icon: ImageVector, title: String, items: List<Pair<ImageVector?, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        items.forEach { (itemIcon, text) ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (itemIcon != null) {
                    Icon(itemIcon, null, tint = Accent, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                } else {
                    Text("• ", color = TextSec, fontSize = 14.sp)
                }
                Text(text, color = TextPrim, fontSize = 14.sp, lineHeight = 20.sp)
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
