package su.SkrinVex.SkriPts.ui.expr

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.data.ProjectVar
import su.SkrinVex.SkriPts.data.VarScope
import su.SkrinVex.SkriPts.ui.theme.*

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
    isIdentifier: Boolean = false,
    onConfirm: (String) -> Unit,
    onCreateVar: (name: String, scope: VarScope) -> Unit,
    onDeleteVar: ((name: String, scope: VarScope) -> Unit)? = null,
    onBack: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    val history = remember { mutableStateListOf(initialValue) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateVar by remember { mutableStateOf(false) }
    var createVarScope by remember { mutableStateOf(VarScope.GLOBAL) }
    var showDeleteVar by remember { mutableStateOf<ProjectVar?>(null) }

    val globalVars = variables.filter { it.scope == VarScope.GLOBAL }
    val localVars  = variables.filter { it.scope == VarScope.LOCAL }

    fun push(v: String) { if (history.lastOrNull() != v) history.add(v) }
    fun insertVar(name: String) { push(value); value = if (isIdentifier) name else value + "{$name}" }
    fun insertFn(insert: String) { push(value); value = value + insert }

    Box(Modifier.fillMaxSize().background(Navy900)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Surface1, shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = TextPrim)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Редактор выражений", color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(paramLabel, color = TextSec, fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = { if (history.size > 1) { history.removeLastOrNull(); value = history.lastOrNull() ?: "" } },
                        enabled = history.size > 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Отменить",
                            tint = if (history.size > 1) TextPrim else TextSec.copy(alpha = 0.3f))
                    }
                    IconButton(onClick = { push(value); value = "" }) {
                        Icon(Icons.Default.ClearAll, "Очистить", tint = Danger.copy(alpha = 0.8f))
                    }
                    Button(onClick = { onConfirm(value) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.padding(end = 8.dp)) {
                        Text("OK", color = Navy900, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { push(value); value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Выражение") },
                    placeholder = { Text("100, {x} + 50, \$screenWidth - 100", color = TextSec) },
                    singleLine = true,
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
                        Icon(Icons.Default.Public, null, modifier = Modifier.size(14.dp))
                        Text("Глобальные (${globalVars.size})", fontSize = 13.sp)
                    }
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp))
                        Text("Локальные (${localVars.size})", fontSize = 13.sp)
                    }
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Functions, null, modifier = Modifier.size(14.dp))
                        Text("Функции", fontSize = 13.sp)
                    }
                }
            }

            when (selectedTab) {
                2 -> FunctionsTab(onInsert = { insertFn(it) })
                else -> {
                    val currentVars = if (selectedTab == 0) globalVars else localVars
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!isIdentifier) {
                            item {
                                OutlinedButton(
                                    onClick = { createVarScope = if (selectedTab == 0) VarScope.GLOBAL else VarScope.LOCAL; showCreateVar = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Создать ${if (selectedTab == 0) "глобальную" else "локальную"} переменную")
                                }
                            }
                        }
                        if (currentVars.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                                    Text("Нет переменных", color = TextSec, fontSize = 14.sp)
                                }
                            }
                        }
                        items(currentVars, key = { it.name }) { v ->
                            VarRow(
                                variable = v, 
                                onClick = { insertVar(v.name) },
                                onDelete = if (onDeleteVar != null) { { showDeleteVar = v } } else null
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateVar) {
        CreateVarDialog(
            scope = createVarScope,
            existingNames = variables.map { it.name }.toSet(),
            onDismiss = { showCreateVar = false },
            onCreate = { name, scope ->
                onCreateVar(name, scope)
                showCreateVar = false
                insertVar(name)
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
}

@Composable
private fun FunctionsTab(onInsert: (String) -> Unit) {
    val screenColor = Color(0xFF60A5FA)
    val randColor   = Color(0xFFA78BFA)
    val mathColor   = Color(0xFF34D399)
    val logicColor  = Color(0xFFF472B6)
    val stringColor = Color(0xFFFB923C)

    val fns = listOf(
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
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String, VarScope) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val nameError = when {
        name.isBlank() -> null
        !name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) -> "Только латинские буквы, цифры и _"
        name in existingNames -> "Переменная с таким именем уже существует"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.DataObject, null, tint = if (scope == VarScope.GLOBAL) Accent else Warning) },
        title = { Text("${if (scope == VarScope.GLOBAL) "Глобальная" else "Локальная"} переменная", color = TextPrim) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
