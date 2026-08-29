package su.SkrinVex.SkriCode.ui.expr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriCode.data.ProjectVar
import su.SkrinVex.SkriCode.data.ProjectTag
import su.SkrinVex.SkriCode.data.ProjectTable
import su.SkrinVex.SkriCode.data.VarScope
import su.SkrinVex.SkriCode.ui.theme.*

private data class BuiltinFn(
    val insert: String,       // что вставляется в поле
    val label: String,        // название
    val description: String,  // подсказка
    val icon: ImageVector,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressionEditorScreen(
    initialValue: String,
    paramLabel: String,
    variables: List<ProjectVar>,
    tags: List<ProjectTag> = emptyList(),
    tables: List<ProjectTable> = emptyList(),
    isIdentifier: Boolean = false,
    onConfirm: (String) -> Unit,
    onCreateVar: (name: String, scope: VarScope) -> Unit,
    onDeleteVar: ((name: String, scope: VarScope) -> Unit)? = null,
    onCreateTag: ((name: String, scope: VarScope) -> Unit)? = null,
    onDeleteTag: ((name: String, scope: VarScope) -> Unit)? = null,
    onCreateTable: ((name: String, scope: VarScope) -> Unit)? = null,
    onDeleteTable: ((name: String, scope: VarScope) -> Unit)? = null,
    onSetTableEntry: ((name: String, scope: VarScope, key: String, value: String) -> Unit)? = null,
    onRemoveTableEntry: ((name: String, scope: VarScope, key: String) -> Unit)? = null,
    onBack: () -> Unit
) {
    var tfv by remember { mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length))) }
    val history = remember { mutableStateListOf(initialValue) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateVar by remember { mutableStateOf(false) }
    var showCreateTag by remember { mutableStateOf(false) }
    var showCreateTable by remember { mutableStateOf(false) }
    var showDeleteVar by remember { mutableStateOf<ProjectVar?>(null) }
    var showDeleteTag by remember { mutableStateOf<ProjectTag?>(null) }
    var showDeleteTable by remember { mutableStateOf<ProjectTable?>(null) }
    var editingTable by remember { mutableStateOf<ProjectTable?>(null) }
    var hasChanges by remember { mutableStateOf(false) }
    var validationWarning by remember { mutableStateOf<String?>(null) }

    val knownVarNames = remember(variables) { variables.map { it.name }.toSet() }

    fun validateAndConfirm() {
        if (isIdentifier) { onConfirm(tfv.text); return }
        val unknown = Regex("\\{([^}]+)\\}").findAll(tfv.text.trim())
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && it !in knownVarNames && it !in su.SkrinVex.SkriCode.engine.ExprEval.SYSTEM_VARS }
            .toSet()
        if (unknown.isNotEmpty()) {
            validationWarning = unknown.joinToString(", ") { "{$it}" }
        } else {
            onConfirm(tfv.text)
        }
    }

    fun push(v: String) { if (history.lastOrNull() != v) history.add(v) }

    fun insertAt(insert: String) {
        val cur = tfv.text
        val sel = tfv.selection
        val pos = if (sel.collapsed) sel.start else sel.end
        push(cur)
        val newText = cur.substring(0, pos) + insert + cur.substring(pos)
        tfv = TextFieldValue(newText, TextRange(pos + insert.length))
        hasChanges = true
    }

    fun insertVar(name: String) {
        if (isIdentifier) { push(tfv.text); tfv = TextFieldValue(name, TextRange(name.length)); hasChanges = true }
        else insertAt("{$name}")
    }
    fun insertTag(name: String) {
        if (isIdentifier) { push(tfv.text); tfv = TextFieldValue(name, TextRange(name.length)); hasChanges = true }
        else insertAt("#$name")
    }
    fun insertTable(tableName: String, key: String) {
        if (isIdentifier) { push(tfv.text); tfv = TextFieldValue(tableName, TextRange(tableName.length)); hasChanges = true }
        else insertAt("[$tableName.$key]")
    }
    fun insertFn(insert: String) { insertAt(insert) }
    
    BackHandler {
        onBack()
    }

    // Редактор таблицы — полноэкранный
    editingTable?.let { tbl ->
        // Берём актуальную версию таблицы из списка (данные могут меняться)
        val current = tables.find { it.name == tbl.name && it.scope == tbl.scope } ?: tbl
        TableEditorScreen(
            table = current,
            onSetEntry = { k, v -> onSetTableEntry?.invoke(tbl.name, tbl.scope, k, v) },
            onRemoveEntry = { k -> onRemoveTableEntry?.invoke(tbl.name, tbl.scope, k) },
            onBack = { editingTable = null }
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Navy900)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Surface1, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Редактор выражений", color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(paramLabel, color = TextSec, fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = {
                            if (history.size > 1) {
                                history.removeLastOrNull()
                                val prev = history.lastOrNull() ?: ""
                                tfv = TextFieldValue(prev, TextRange(prev.length))
                            }
                        },
                        enabled = history.size > 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Отменить",
                            tint = if (history.size > 1) TextPrim else TextSec.copy(alpha = 0.3f))
                    }
                    IconButton(onClick = { push(tfv.text); tfv = TextFieldValue("", TextRange(0)) }) {
                        Icon(Icons.Default.ClearAll, "Очистить", tint = Danger.copy(alpha = 0.8f))
                    }
                    Button(onClick = { validateAndConfirm() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.padding(end = 8.dp)) {
                        Text("OK", color = Navy900, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = tfv,
                    onValueChange = { new ->
                        if (new.text != tfv.text) push(tfv.text)
                        tfv = new
                        hasChanges = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Выражение") },
                    placeholder = { Text("100, {x} + 50, \$screenWidth - 100", color = TextSec) },
                    singleLine = isIdentifier,
                    maxLines = if (isIdentifier) 1 else 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Surface3,
                        focusedLabelColor = Accent, unfocusedLabelColor = TextSec,
                        focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, cursorColor = Accent
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Warning, fontFamily = FontFamily.Monospace)) { append("{имя}") }
                        append(" — переменная  ")
                        withStyle(SpanStyle(color = Color(0xFF34D399), fontFamily = FontFamily.Monospace)) { append("[таблица.ключ]") }
                        append(" — таблица  ")
                        withStyle(SpanStyle(color = Color(0xFF60A5FA), fontFamily = FontFamily.Monospace)) { append("\$константа") }
                        append(" — встроенная")
                    },
                    color = TextSec, fontSize = 11.sp
                )
            }

            TabRow(selectedTabIndex = selectedTab, containerColor = Surface1, contentColor = Accent) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DataObject, null, modifier = Modifier.size(14.dp))
                        Text("Переменные (${variables.size})", fontSize = 13.sp)
                    }
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tag, null, modifier = Modifier.size(14.dp))
                        Text("Теги (${tags.size})", fontSize = 13.sp)
                    }
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TableChart, null, modifier = Modifier.size(14.dp))
                        Text("Таблицы (${tables.size})", fontSize = 13.sp)
                    }
                }
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Functions, null, modifier = Modifier.size(14.dp))
                        Text("Функции", fontSize = 13.sp)
                    }
                }
            }

            when (selectedTab) {
                0 -> VarsTab(variables, onInsert = { insertVar(it) }, onDelete = { showDeleteVar = it })
                1 -> TagsTab(tags, onInsert = { insertTag(it) }, onDelete = if (onDeleteTag != null) { { showDeleteTag = it } } else null)
                2 -> TablesTab(tables, onInsert = { t, k -> insertTable(t, k) },
                    onDelete = if (onDeleteTable != null) { { showDeleteTable = it } } else null,
                    onEdit = { editingTable = it })
                3 -> FunctionsTab(onInsert = { insertFn(it) })
            }
        }

        // FAB — создать переменную, тег или таблицу
        FloatingActionButton(
            onClick = {
                when (selectedTab) {
                    0 -> showCreateVar = true
                    1 -> if (onCreateTag != null) showCreateTag = true
                    2 -> if (onCreateTable != null) showCreateTable = true
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Accent
        ) {
            Icon(Icons.Default.Add, "Создать", tint = Navy900)
        }
    }

    validationWarning?.let { warn ->
        AlertDialog(
            onDismissRequest = { validationWarning = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.Warning, null, tint = Warning) },
            title = { Text("Возможная ошибка", color = TextPrim) },
            text = { Text(
                "Переменная $warn не найдена в проекте.\n\n" +
                "Если хочешь использовать переменную — оберни имя в фигурные скобки: {имя}.\n\n" +
                "Всё равно сохранить?",
                color = TextSec
            ) },
            confirmButton = {
                Button(onClick = { validationWarning = null; onConfirm(tfv.text) },
                    colors = ButtonDefaults.buttonColors(containerColor = Warning)
                ) { Text("Сохранить", color = Navy900) }
            },
            dismissButton = {
                TextButton(onClick = { validationWarning = null }) { Text("Исправить", color = TextSec) }
            }
        )
    }

    if (showCreateVar) {
        var selectedScope by remember { mutableStateOf(VarScope.GLOBAL) }
        CreateVarDialog(
            scope = selectedScope,
            onScopeChange = { selectedScope = it },
            existingNames = variables.map { it.name }.toSet(),
            onDismiss = { showCreateVar = false },
            onCreate = { name, scope ->
                onCreateVar(name, scope)
                showCreateVar = false
                insertVar(name)
            }
        )
    }
    
    if (showCreateTag && onCreateTag != null) {
        var selectedScope by remember { mutableStateOf(VarScope.GLOBAL) }
        CreateTagDialog(
            scope = selectedScope,
            onScopeChange = { selectedScope = it },
            existingNames = tags.map { it.name }.toSet(),
            onDismiss = { showCreateTag = false },
            onCreate = { name, scope ->
                onCreateTag(name, scope)
                showCreateTag = false
                insertTag(name)
            }
        )
    }

    if (showCreateTable && onCreateTable != null) {
        var selectedScope by remember { mutableStateOf(VarScope.GLOBAL) }
        CreateTableDialog(
            scope = selectedScope,
            onScopeChange = { selectedScope = it },
            existingNames = tables.map { it.name }.toSet(),
            onDismiss = { showCreateTable = false },
            onCreate = { name, scope ->
                onCreateTable(name, scope)
                showCreateTable = false
            }
        )
    }

    showDeleteVar?.let { variable ->
        AlertDialog(
            onDismissRequest = { showDeleteVar = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить переменную?", color = TextPrim) },
            text = { 
                Text(
                    "Переменная «${variable.name}» будет удалена. Это может сломать блоки, которые её используют.",
                    color = TextSec
                )
            },
            confirmButton = {
                Button(
                    onClick = { 
                        onDeleteVar?.invoke(variable.name, variable.scope)
                        showDeleteVar = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVar = null }) {
                    Text("Отмена", color = TextSec)
                }
            }
        )
    }
    
    showDeleteTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { showDeleteTag = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить тег?", color = TextPrim) },
            text = { Text("Тег «#${tag.name}» будет удалён.", color = TextSec) },
            confirmButton = {
                Button(
                    onClick = { 
                        onDeleteTag?.invoke(tag.name, tag.scope)
                        showDeleteTag = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTag = null }) {
                    Text("Отмена", color = TextSec)
                }
            }
        )
    }

    showDeleteTable?.let { table ->
        AlertDialog(
            onDismissRequest = { showDeleteTable = null },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить таблицу?", color = TextPrim) },
            text = { Text("Таблица «${table.name}» и все её данные будут удалены.", color = TextSec) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTable?.invoke(table.name, table.scope)
                        showDeleteTable = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTable = null }) {
                    Text("Отмена", color = TextSec)
                }
            }
        )
    }
}

@Composable
private fun FunctionsTab(onInsert: (String) -> Unit) {
    val screenColor = Color(0xFF60A5FA)
    val randColor   = Color(0xFFA78BFA)
    val mathColor   = Color(0xFF34D399)
    val logicColor  = Color(0xFFF472B6)
    val stringColor = Color(0xFFFB923C)

    val fns = listOf(
        BuiltinFn("\$none",         "Не изменять",          "Оставляет координату без изменений (для sim_move)", Icons.Default.Block, Color(0xFF94A3B8)),
        BuiltinFn("\$screenWidth",  "Ширина экрана",        "Ширина экрана в пикселях",          Icons.Default.PhoneAndroid, screenColor),
        BuiltinFn("\$screenHeight", "Высота экрана",        "Высота экрана в пикселях",          Icons.Default.PhoneAndroid, screenColor),
        BuiltinFn("\$screenTop",    "Верхняя точка",        "Y верхнего края (screenHeight / 2)", Icons.Default.VerticalAlignTop,    screenColor),
        BuiltinFn("\$screenBottom", "Нижняя точка",         "Y нижнего края (-screenHeight / 2)", Icons.Default.VerticalAlignBottom, screenColor),
        BuiltinFn("\$screenRight",  "Правая точка",         "X правого края (screenWidth / 2)",  Icons.Default.ChevronRight, screenColor),
        BuiltinFn("\$screenLeft",   "Левая точка",          "X левого края (-screenWidth / 2)",  Icons.Default.ChevronLeft,  screenColor),
        BuiltinFn("\$rand(0, 100)", "Случайное число",      "Целое число в диапазоне [min, max]", Icons.Default.Casino,       randColor),
        
        // Математические функции
        BuiltinFn("\$add(5, 3)",    "Сложение",             "Складывает два числа",               Icons.Default.Add,          mathColor),
        BuiltinFn("\$sub(10, 3)",   "Вычитание",            "Вычитает второе из первого",         Icons.Default.Remove,       mathColor),
        BuiltinFn("\$mul(4, 5)",    "Умножение",            "Умножает два числа",                 Icons.Default.Close,        mathColor),
        BuiltinFn("\$div(20, 4)",   "Деление",              "Делит первое на второе",             Icons.Default.Percent,      mathColor),
        BuiltinFn("\$abs(-5)",      "Модуль числа",         "Абсолютное значение числа",          Icons.Default.Functions,    mathColor),
        BuiltinFn("\$sqrt(9)",      "Квадратный корень",    "Извлекает квадратный корень числа",  Icons.Default.Functions,    mathColor),
        BuiltinFn("\$min(3, 7)",    "Минимум",              "Меньшее из двух чисел",              Icons.Default.KeyboardArrowDown, mathColor),
        BuiltinFn("\$max(3, 7)",    "Максимум",             "Большее из двух чисел",              Icons.Default.KeyboardArrowUp,   mathColor),
        
        // Логические функции
        BuiltinFn("\$and(true, false)", "Логическое И",     "true если оба условия истинны",      Icons.Default.AccountTree,  logicColor),
        BuiltinFn("\$or(true, false)",  "Логическое ИЛИ",   "true если хотя бы одно истинно",     Icons.Default.AccountTree,  logicColor),
        BuiltinFn("\$not(true)",        "Логическое НЕ",    "Инвертирует логическое значение",    Icons.Default.NotInterested, logicColor),
        
        // Строковые функции
        BuiltinFn("\$concat(\"a\", \"b\")", "Соединить",    "Объединяет два текста",              Icons.Default.Link,         stringColor),
        BuiltinFn("\$length(\"text\")",     "Длина текста", "Количество символов в тексте",       Icons.Default.Straighten,   stringColor),
        BuiltinFn("\$upper(\"text\")",      "В верхний регистр", "Преобразует в заглавные буквы", Icons.Default.KeyboardArrowUp, stringColor),
        BuiltinFn("\$lower(\"TEXT\")",      "В нижний регистр",  "Преобразует в строчные буквы",  Icons.Default.KeyboardArrowDown, stringColor),

        // Объекты
        BuiltinFn("\$objX(Button)",    "X объекта",          "Позиция объекта по горизонтали",                Icons.Default.SwapHoriz,    screenColor),
        BuiltinFn("\$objY(Button)",    "Y объекта",          "Позиция объекта по вертикали",                  Icons.Default.SwapVert,     screenColor),
        BuiltinFn("\$objRot(Button)",  "Вращение объекта",   "Угол поворота объекта в градусах",              Icons.Default.RotateRight,  screenColor),
        BuiltinFn("\$objVx(Button)",   "Скорость X объекта", "Скорость физического тела по X",                Icons.Default.Speed,        screenColor),
        BuiltinFn("\$objVy(Button)",   "Скорость Y объекта", "Скорость физического тела по Y",                Icons.Default.Speed,        screenColor),
        BuiltinFn("\$objDirX(Button)", "Направление X",      "X-компонент вектора направления (по повороту)", Icons.Default.Navigation,   screenColor),
        BuiltinFn("\$objDirY(Button)", "Направление Y",      "Y-компонент вектора направления (по повороту)", Icons.Default.Navigation,   screenColor),
        BuiltinFn("\$objFrontX(Button, 80)", "Точка перед X", "X позиции перед объектом на расстоянии",       Icons.Default.Navigation,   screenColor),
        BuiltinFn("\$objFrontY(Button, 80)", "Точка перед Y", "Y позиции перед объектом на расстоянии",       Icons.Default.Navigation,   screenColor),
        BuiltinFn("\$objGrounded(Button)",   "На земле?",     "true если объект стоит на статическом объекте", Icons.Default.VerticalAlignBottom, screenColor),

        // Таблицы
        BuiltinFn("\$tableSize(scores)", "Размер таблицы",  "Количество записей в таблице",                    Icons.Default.TableChart, TableAccent),
        BuiltinFn("\$tableKey(scores, 0)", "Ключ по индексу", "Ключ записи по индексу (0, 1, 2, ...)",         Icons.Default.TableChart, TableAccent),
        BuiltinFn("\$tableVal(scores, 0)", "Значение по индексу", "Значение записи по индексу (0, 1, 2, ...)", Icons.Default.TableChart, TableAccent),

        // Сохранения
        BuiltinFn("\$saveExists(ключ)", "Есть сохранение?", "true если сохранение с таким ключом существует", Icons.Default.Save, Color(0xFF22C55E)),

        // Коллизия
        BuiltinFn("{collision_self}",          "Я (инициатор)",            "Имя объекта которому принадлежит этот скрипт",   Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_other}",         "Другой объект",            "Имя объекта с которым произошло столкновение",   Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_x}",             "X другого объекта",        "Позиция X другого объекта",                      Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_y}",             "Y другого объекта",        "Позиция Y другого объекта",                      Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_width}",         "Ширина другого объекта",   "Ширина другого объекта",                         Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_height}",        "Высота другого объекта",   "Высота другого объекта",                         Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_rotation}",      "Поворот другого объекта",  "Угол поворота другого объекта",                  Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_self_x}",        "X моего объекта",          "Позиция X объекта-инициатора",                   Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_self_y}",        "Y моего объекта",          "Позиция Y объекта-инициатора",                   Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_self_width}",    "Ширина моего объекта",     "Ширина объекта-инициатора",                      Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_self_height}",   "Высота моего объекта",     "Высота объекта-инициатора",                      Icons.Default.Bolt, Color(0xFFFF6B6B)),
        BuiltinFn("{collision_self_rotation}", "Поворот моего объекта",    "Угол поворота объекта-инициатора",               Icons.Default.Bolt, Color(0xFFFF6B6B)),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(fns) { fn ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2)
                    .clickable { onInsert(fn.insert) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(fn.color.copy(0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(fn.icon, null, tint = fn.color, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = fn.color, fontFamily = FontFamily.Monospace)) {
                                append(fn.insert)
                            }
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                    Text(fn.description, color = TextSec, fontSize = 11.sp)
                }
                Icon(Icons.Default.AddCircleOutline, null, tint = fn.color.copy(0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun VarRow(variable: ProjectVar, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                .background(if (variable.scope == VarScope.GLOBAL) Accent.copy(0.15f) else Warning.copy(0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (variable.scope == VarScope.GLOBAL) Icons.Default.Public else Icons.Default.Lock,
                null, tint = if (variable.scope == VarScope.GLOBAL) Accent else Warning,
                modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Warning)) { append("{") }
                    withStyle(SpanStyle(color = TextPrim, fontFamily = FontFamily.Monospace)) { append(variable.name) }
                    withStyle(SpanStyle(color = Warning)) { append("}") }
                },
                fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Text("= ${variable.value.ifBlank { "0" }}", color = TextSec, fontSize = 11.sp)
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.DeleteOutline, "Удалить", tint = Danger, modifier = Modifier.size(16.dp))
            }
        } else {
            Icon(Icons.Default.AddCircleOutline, null, tint = Accent, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateVarDialog(
    scope: VarScope,
    onScopeChange: (VarScope) -> Unit,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, VarScope) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val nameError = when {
        name.isBlank() -> null
        !name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) -> "Только латинские буквы, цифры и _"
        name in existingNames -> "Переменная с таким именем уже существует"
        name in su.SkrinVex.SkriCode.engine.ExprEval.SYSTEM_VARS -> "Это системная переменная, нельзя переопределить"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.DataObject, null, tint = if (scope == VarScope.GLOBAL) Accent else Warning) },
        title = { Text("Создать переменную", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Переключатель области видимости
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)) {
                    listOf(VarScope.GLOBAL to "Глобальная", VarScope.LOCAL to "Локальная").forEach { (s, label) ->
                        val active = scope == s
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (active) (if (s == VarScope.GLOBAL) Accent else Warning).copy(0.2f) else Color.Transparent)
                                .border(if (active) 1.dp else 0.dp, if (active) (if (s == VarScope.GLOBAL) Accent else Warning) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onScopeChange(s) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (active) (if (s == VarScope.GLOBAL) Accent else Warning) else TextSec, fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя переменной") },
                    placeholder = { Text("myVar", color = TextSec) },
                    singleLine = true, isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = Danger) } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, focusedLabelColor = Accent,
                        cursorColor = Accent, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
                Text(
                    if (scope == VarScope.GLOBAL) "Доступна во всех скриптах проекта"
                    else "Доступна только в текущем скрипте",
                    color = TextSec, fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && nameError == null) onCreate(name.trim(), scope) },
                enabled = name.isNotBlank() && nameError == null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Создать", color = Navy900) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

@Composable
private fun VarsTab(variables: List<ProjectVar>, onInsert: (String) -> Unit, onDelete: (ProjectVar) -> Unit) {
    val globalVars = variables.filter { it.scope == VarScope.GLOBAL }
    val localVars = variables.filter { it.scope == VarScope.LOCAL }
    
    Column(Modifier.fillMaxHeight()) {
        if (variables.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                Text("Нет переменных. Нажми + чтобы создать.", color = TextSec, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (globalVars.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Public, null, tint = Accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Глобальные", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(globalVars, key = { "global_${it.name}" }) { v ->
                        VarRow(variable = v, onClick = { onInsert(v.name) }, onDelete = { onDelete(v) })
                    }
                }
                if (localVars.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp).padding(top = if (globalVars.isNotEmpty()) 8.dp else 0.dp)) {
                            Icon(Icons.Default.Lock, null, tint = Warning, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Локальные", color = Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(localVars, key = { "local_${it.name}" }) { v ->
                        VarRow(variable = v, onClick = { onInsert(v.name) }, onDelete = { onDelete(v) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TagsTab(tags: List<ProjectTag>, onInsert: (String) -> Unit, onDelete: ((ProjectTag) -> Unit)?) {
    val globalTags = tags.filter { it.scope == VarScope.GLOBAL }
    val localTags = tags.filter { it.scope == VarScope.LOCAL }
    
    Column(Modifier.fillMaxHeight()) {
        if (tags.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                Text("Нет тегов. Нажми + чтобы создать.", color = TextSec, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (globalTags.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Public, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Глобальные", color = Color(0xFFFF6B6B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(globalTags, key = { "global_${it.name}" }) { t ->
                        TagRow(tag = t, onClick = { onInsert(t.name) }, onDelete = onDelete?.let { { it(t) } })
                    }
                }
                if (localTags.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp).padding(top = if (globalTags.isNotEmpty()) 8.dp else 0.dp)) {
                            Icon(Icons.Default.Lock, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Локальные", color = Color(0xFFFF6B6B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(localTags, key = { "local_${it.name}" }) { t ->
                        TagRow(tag = t, onClick = { onInsert(t.name) }, onDelete = onDelete?.let { { it(t) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(tag: ProjectTag, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Tag, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("#${tag.name}", color = TextPrim, fontSize = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, null, tint = Danger.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CreateTagDialog(    scope: VarScope,
    onScopeChange: (VarScope) -> Unit,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, VarScope) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val nameError = when {
        name.isBlank() -> null
        !name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) -> "Только латинские буквы, цифры и _"
        name in existingNames -> "Тег с таким именем уже существует"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.Tag, null, tint = Color(0xFFFF6B6B)) },
        title = { Text("Создать тег", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Переключатель области видимости
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)) {
                    listOf(VarScope.GLOBAL to "Глобальный", VarScope.LOCAL to "Локальный").forEach { (s, label) ->
                        val active = scope == s
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFFFF6B6B).copy(0.2f) else Color.Transparent)
                                .border(if (active) 1.dp else 0.dp, if (active) Color(0xFFFF6B6B) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onScopeChange(s) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (active) Color(0xFFFF6B6B) else TextSec, fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя тега") },
                    placeholder = { Text("enemies", color = TextSec) },
                    prefix = { Text("#", color = Color(0xFFFF6B6B)) },
                    singleLine = true, isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = Danger) } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF6B6B), focusedLabelColor = Color(0xFFFF6B6B),
                        cursorColor = Color(0xFFFF6B6B), focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
                Text(
                    if (scope == VarScope.GLOBAL) "Доступен во всех скриптах проекта"
                    else "Доступен только в текущем скрипте",
                    color = TextSec, fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && nameError == null) onCreate(name.trim(), scope) },
                enabled = name.isNotBlank() && nameError == null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}

private val TableColor = Color(0xFF34D399)

@Composable
private fun TablesTab(
    tables: List<ProjectTable>,
    onInsert: (tableName: String, key: String) -> Unit,
    onDelete: ((ProjectTable) -> Unit)?,
    onEdit: (ProjectTable) -> Unit
) {
    val globalTables = tables.filter { it.scope == VarScope.GLOBAL }
    val localTables = tables.filter { it.scope == VarScope.LOCAL }

    Column(Modifier.fillMaxHeight()) {
        if (tables.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                Text("Нет таблиц. Нажми + чтобы создать.", color = TextSec, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (globalTables.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Public, null, tint = TableColor, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Глобальные", color = TableColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(globalTables, key = { "global_${it.name}" }) { t ->
                        TableRow(table = t, onInsert = { k -> onInsert(t.name, k) },
                            onDelete = onDelete?.let { { it(t) } }, onEdit = { onEdit(t) })
                    }
                }
                if (localTables.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp).padding(top = if (globalTables.isNotEmpty()) 8.dp else 0.dp)) {
                            Icon(Icons.Default.Lock, null, tint = TableColor, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Локальные", color = TableColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(localTables, key = { "local_${it.name}" }) { t ->
                        TableRow(table = t, onInsert = { k -> onInsert(t.name, k) },
                            onDelete = onDelete?.let { { it(t) } }, onEdit = { onEdit(t) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(table: ProjectTable, onInsert: (key: String) -> Unit, onDelete: (() -> Unit)?, onEdit: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2)
    ) {
        // Заголовок таблицы
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(TableColor.copy(0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.TableChart, null, tint = TableColor, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TableColor, fontFamily = FontFamily.Monospace)) { append(table.name) }
                    },
                    fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
                Text("${table.entries.size} записей · нажми чтобы вставить ключ", color = TextSec, fontSize = 11.sp)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, null, tint = TableColor.copy(0.8f), modifier = Modifier.size(16.dp))
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, null, tint = Danger.copy(0.7f), modifier = Modifier.size(16.dp))
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = TextSec, modifier = Modifier.size(18.dp)
            )
        }

        // Записи таблицы
        if (expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (table.entries.isEmpty()) {
                    Text("Таблица пуста · нажми ✏ чтобы добавить записи",
                        color = TextSec, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                } else {
                    table.entries.forEach { (key, value) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface3)
                                .clickable { onInsert(key) }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = TableColor, fontFamily = FontFamily.Monospace)) { append("[${table.name}.$key]") }
                                },
                                fontSize = 12.sp, modifier = Modifier.weight(1f)
                            )
                            Text("= $value", color = TextSec, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.AddCircleOutline, null, tint = TableColor.copy(0.7f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTableDialog(
    scope: VarScope,
    onScopeChange: (VarScope) -> Unit,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, VarScope) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val nameError = when {
        name.isBlank() -> null
        !name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) -> "Только латинские буквы, цифры и _"
        name in existingNames -> "Таблица с таким именем уже существует"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.TableChart, null, tint = TableColor) },
        title = { Text("Создать таблицу", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface3)) {
                    listOf(VarScope.GLOBAL to "Глобальная", VarScope.LOCAL to "Локальная").forEach { (s, label) ->
                        val active = scope == s
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (active) TableColor.copy(0.2f) else Color.Transparent)
                                .border(if (active) 1.dp else 0.dp, if (active) TableColor else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onScopeChange(s) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (active) TableColor else TextSec, fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя таблицы") },
                    placeholder = { Text("scores", color = TextSec) },
                    singleLine = true, isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = Danger) } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TableColor, focusedLabelColor = TableColor,
                        cursorColor = TableColor, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim
                    )
                )
                Text(
                    "Доступ: [${name.ifBlank { "таблица" }}.ключ]",
                    color = TextSec, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && nameError == null) onCreate(name.trim(), scope) },
                enabled = name.isNotBlank() && nameError == null,
                colors = ButtonDefaults.buttonColors(containerColor = TableColor)
            ) { Text("Создать", color = Navy900) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) } }
    )
}
