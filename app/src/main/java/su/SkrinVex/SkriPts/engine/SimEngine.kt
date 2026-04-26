package su.SkrinVex.SkriPts.engine

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import su.SkrinVex.SkriPts.block.BlockDef
import su.SkrinVex.SkriPts.data.Script
import su.SkrinVex.SkriPts.data.ScriptEvent
import su.SkrinVex.SkriPts.data.ProjectVar
import su.SkrinVex.SkriPts.data.ProjectTable
import su.SkrinVex.SkriPts.data.deserialize

data class SimObject(
    val name: String,
    val x: Float, val y: Float,
    val width: Float, val height: Float,
    val radius: Float,
    val color: Color,
    val label: String = "",
    val fontSize: Float = 14f,
    val bold: Boolean = false,
    val textColor: Color? = null,
    val tapScriptId: String? = null,
    val holdScriptId: String? = null,
    val visible: Boolean = true,
    val rotation: Float = 0f,
    val tags: Set<String> = emptySet()
)

data class JoystickState(
    val name: String,
    val x: Float, val y: Float,          // позиция центра джойстика
    val baseRadius: Float,               // радиус базы
    val knobRadius: Float,               // радиус ручки
    val baseColor: Color,
    val knobColor: Color,
    val targetObject: String,            // имя объекта которым управляет
    val speed: Float,                    // скорость движения px/tick
    val directional: Boolean,            // вращать объект по направлению
    // runtime
    val knobDx: Float = 0f,             // смещение ручки от центра (-1..1)
    val knobDy: Float = 0f,
    val pointerId: Long? = null          // какой палец держит
)

data class SimState(
    val objects: Map<String, SimObject> = emptyMap(),
    val joysticks: Map<String, JoystickState> = emptyMap(),
    val globalVars: Map<String, String> = emptyMap(),
    val tables: Map<String, Map<String, String>> = emptyMap(),
    val log: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val isStopped: Boolean = false
)

object SimEngine {

    suspend fun run(
        scripts: List<Script>,
        globalVarDefs: List<ProjectVar>,
        globalTableDefs: List<ProjectTable> = emptyList(),
        onUpdate: (SimState) -> Unit = {}
    ): SimState {
        val objects = mutableMapOf<String, SimObject>()
        val joysticks = mutableMapOf<String, JoystickState>()
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val globalVars = globalVarDefs.associate { it.name to it.value }.toMutableMap()
        // Таблицы: глобальные + локальные скрипта объединяются при выполнении
        val globalTables = globalTableDefs.associate { it.name to it.entries.toMutableMap() }
            .mapValues { it.value }.toMutableMap<String, MutableMap<String, String>>()

        scripts.filter { it.event == ScriptEvent.ON_START }.forEach { script ->
            log += "Скрипт «${script.name}»"
            val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
            val vars = (globalVars + localVars).toMutableMap()
            val localTables = script.localTables.orEmpty().associate { it.name to it.entries.toMutableMap() }
            val allTables = (globalTables + localTables).toMutableMap<String, MutableMap<String, String>>()
            runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors,
                allowDelay = true, onUpdate = { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList())) })
            globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }
            // Синхронизируем изменения таблиц обратно в глобальные
            globalTables.keys.forEach { k -> allTables[k]?.let { globalTables[k] = it } }
        }

        // Привязываем ON_TAP/ON_HOLD только к объектам, уже существующим после ON_START.
        // Объекты, созданные позже (в ON_TAP/ON_HOLD скриптах), будут привязаны динамически
        // через bindEventScripts при каждом обновлении состояния.
        bindEventScripts(scripts, objects, errors, warnMissing = false)

        return SimState(objects = objects, joysticks = joysticks, globalVars = globalVars,
            tables = globalTables.mapValues { it.value.toMap() }, log = log, errors = errors, isStopped = false)
    }

    /**
     * Привязывает ON_TAP/ON_HOLD скрипты к объектам, которые уже существуют в [objects].
     * Вызывается после ON_START и после каждого выполнения скрипта, чтобы подхватить
     * объекты, созданные динамически (например, Button создаётся в ON_TAP Button_start).
     * [warnMissing] — добавлять ли ошибку если объект ещё не создан.
     */
    fun bindEventScripts(
        scripts: List<Script>,
        objects: MutableMap<String, SimObject>,
        errors: MutableList<String>,
        warnMissing: Boolean = false
    ) {
        scripts.filter { it.event == ScriptEvent.ON_TAP }.forEach { script ->
            val target = script.eventTarget.trim()
            if (target.isNotBlank()) {
                val obj = objects[target]
                if (obj != null) {
                    if (obj.tapScriptId != script.id) objects[target] = obj.copy(tapScriptId = script.id)
                } else if (warnMissing) {
                    errors += "Скрипт «${script.name}»: объект «$target» не найден для ON_TAP"
                }
            }
        }
        scripts.filter { it.event == ScriptEvent.ON_HOLD }.forEach { script ->
            val target = script.eventTarget.trim()
            if (target.isNotBlank()) {
                val obj = objects[target]
                if (obj != null) {
                    if (obj.holdScriptId != script.id) objects[target] = obj.copy(holdScriptId = script.id)
                } else if (warnMissing) {
                    errors += "Скрипт «${script.name}»: объект «$target» не найден для ON_HOLD"
                }
            }
        }
    }

    suspend fun runTap(scriptId: String, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, scripts, currentState, onUpdate)
    }

    suspend fun runHold(scriptId: String, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, scripts, currentState, onUpdate)
    }

    private suspend fun runScriptOnState(script: Script, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null): SimState {
        val objects = currentState.objects.toMutableMap()
        val joysticks = currentState.joysticks.toMutableMap()
        val log = currentState.log.toMutableList()
        val errors = currentState.errors.toMutableList()
        val globalVars = currentState.globalVars.toMutableMap()
        val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
        val vars = (globalVars + localVars).toMutableMap()
        val localTables = script.localTables.orEmpty().associate { it.name to it.entries.toMutableMap() }
        val allTables = (currentState.tables.mapValues { it.value.toMutableMap() } + localTables).toMutableMap<String, MutableMap<String, String>>()

        log += "Касание -> «${script.name}»"
        val continued = runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors, allowDelay = true,
            onUpdate = if (onUpdate != null) {
                { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList())) }
            } else null
        )
        globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        return currentState.copy(
            objects = objects,
            joysticks = joysticks,
            globalVars = globalVars,
            tables = allTables.mapValues { it.value.toMap() },
            log = log,
            errors = errors,
            isStopped = !continued
        )
    }

    private suspend fun runScript(
        blocks: List<BlockDef>,
        vars: MutableMap<String, String>,
        objects: MutableMap<String, SimObject>,
        joysticks: MutableMap<String, JoystickState>,
        tables: MutableMap<String, MutableMap<String, String>>,
        log: MutableList<String>,
        errors: MutableList<String>,
        allowDelay: Boolean = true,
        onUpdate: (() -> Unit)? = null
    ): Boolean {
        // Синхронизируем объекты с ExprEval чтобы $objX/$objY/$objRot работали
        ExprEval.objects = objects
        ExprEval.joysticks = joysticks
        ExprEval.tables = tables.mapValues { it.value.toMap() }

        // Вспомогательная функция для получения объектов по имени или тегу
        fun getObjectsByNameOrTag(nameOrTag: String): List<Pair<String, SimObject>> {
            // Вычисляем выражение (поддержка переменных)
            val resolved = ExprEval.eval(nameOrTag, vars).value
            
            return if (resolved.startsWith("#")) {
                val tag = resolved.substring(1)
                objects.filter { (_, obj) -> tag in obj.tags }.toList()
            } else {
                val obj = objects[resolved]
                if (obj != null) listOf(resolved to obj) else emptyList()
            }
        }
        
        for ((idx, block) in blocks.withIndex()) {
            val num = idx + 1

            fun getStr(key: String, default: String = ""): String {
                val raw = block.params[key]?.value ?: default
                val result = ExprEval.eval(raw, vars)
                if (result.error != null)
                    errors += "Блок $num «${block.displayName}» [${block.params[key]?.label ?: key}]: ${result.error}"
                return result.value
            }

            fun getF(key: String, default: Float = 0f): Float {
                val s = getStr(key, default.toString())
                return s.toFloatOrNull() ?: run {
                    errors += "Блок $num «${block.displayName}» [${block.params[key]?.label ?: key}]: «$s» не является числом"
                    default
                }
            }

            when (block.type) {
                "set_var" -> {
                    val name = block.params["name"]?.value?.trim() ?: ""
                    if (name.isBlank()) { errors += "Блок $num «Переменная»: имя не заполнено"; continue }
                    val value = getStr("value")
                    vars[name] = value
                    log += "  $name = $value"
                }
                "set_tag" -> {
                    val objName = getStr("object")
                    val tag = block.params["tag"]?.value?.trim() ?: ""
                    if (tag.isBlank()) { errors += "Блок $num «Установить тег»: тег не заполнен"; continue }
                    val obj = objects[objName]
                    if (obj == null) { errors += "Блок $num «Установить тег»: объект «$objName» не найден"; continue }
                    objects[objName] = obj.copy(tags = obj.tags + tag)
                    log += "  Тег #$tag установлен для «$objName»"
                }
                "sim_stop" -> {
                    log += "  Симуляция остановлена"
                    return false
                }
                "if_block" -> {
                    val left  = block.params["left"]?.value ?: ""
                    val op    = block.params["op"]?.value ?: "=="
                    val right = block.params["right"]?.value ?: "0"
                    val (result, err) = ExprEval.evalCondition(left, op, right, vars)
                    if (err != null) { errors += "Блок $num «Условие»: $err"; continue }
                    val branch = if (result) "then" else "else"
                    val branchBlocks = block.children[branch] ?: emptyList()
                    val leftVal = ExprEval.eval(left, vars).value
                    val rightVal = ExprEval.eval(right, vars).value
                    log += "  Условие: $leftVal $op $rightVal → ${if (result) "истина" else "ложь"}"
                    if (branchBlocks.isNotEmpty()) {
                        if (!runScript(branchBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, onUpdate)) return false
                    }
                }
                "sim_create" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Создать объект»: имя пустое"; continue }
                    if (objects.containsKey(name)) { errors += "Блок $num: объект «$name» уже существует"; continue }
                    objects[name] = SimObject(
                        name = name, x = getF("x"), y = getF("y"),
                        width = getF("width", 100f).coerceAtLeast(1f),
                        height = getF("height", 60f).coerceAtLeast(1f),
                        radius = getF("radius", 8f).coerceAtLeast(0f),
                        color = parseColor(getStr("color", "#4F8EF7"))
                    )
                    log += "  Создан «$name» (${getStr("x")}, ${getStr("y")}) ${getStr("width")}x${getStr("height")}"
                }
                "sim_move" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Переместить»: «$nameOrTag» не найден"; continue }
                    val mode = block.params["mode"]?.value ?: "instant"
                    val rawX = block.params["x"]?.value ?: "0"
                    val rawY = block.params["y"]?.value ?: "0"
                    val noneX = rawX.trim() == "\$none"
                    val noneY = rawY.trim() == "\$none"
                    val dx = if (noneX) 0f else getF("x")
                    val dy = if (noneY) 0f else getF("y")
                    targets.forEach { (name, obj) ->
                        val nx = when { noneX -> obj.x; mode == "step" -> obj.x + dx; else -> dx }
                        val ny = when { noneY -> obj.y; mode == "step" -> obj.y + dy; else -> dy }
                        objects[name] = obj.copy(x = nx, y = ny)
                    }
                    if (mode == "step") log += "  «$nameOrTag» шаг (+$dx, +$dy)"
                    else log += "  «$nameOrTag» -> ($dx, $dy)"
                }
                "sim_resize" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Размер»: «$nameOrTag» не найден"; continue }
                    val w = getF("width", 100f).coerceAtLeast(1f)
                    val h = getF("height", 60f).coerceAtLeast(1f)
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(width = w, height = h) }
                    log += "  «$nameOrTag» размер ${getStr("width")}x${getStr("height")}"
                }
                "sim_color" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Цвет»: «$nameOrTag» не найден"; continue }
                    val color = parseColor(getStr("color", "#4F8EF7"))
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(color = color) }
                    log += "  «$nameOrTag» цвет -> ${getStr("color")}"
                }
                "sim_update_text" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Обновить текст»: «$nameOrTag» не найден"; continue }
                    val text = getStr("text")
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(label = text) }
                    log += "  «$nameOrTag» текст обновлён: «$text»"
                }
                "sim_text" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Текстовый объект»: имя пустое"; continue }
                    if (objects.containsKey(name)) { errors += "Блок $num: объект «$name» уже существует"; continue }
                    val tcRaw = getStr("textColor", "")
                    objects[name] = SimObject(
                        name = name, x = getF("x"), y = getF("y"),
                        width = getF("width", 200f).coerceAtLeast(1f),
                        height = getF("height", 40f).coerceAtLeast(1f),
                        radius = 0f, color = Color.Transparent,
                        label = getStr("text"),
                        fontSize = getF("size", 16f).coerceAtLeast(6f),
                        bold = getStr("bold", "false") == "true",
                        textColor = if (tcRaw.isNotBlank()) parseColor(tcRaw) else null
                    )
                    log += "  Текст «$name»: «${getStr("text")}»"
                }
                "sim_hide" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Скрыть»: «$nameOrTag» не найден"; continue }
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(visible = false) }
                    log += "  «$nameOrTag» скрыт"
                }
                "sim_show" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Показать»: «$nameOrTag» не найден"; continue }
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(visible = true) }
                    log += "  «$nameOrTag» показан"
                }
                "for_loop" -> {
                    val count = getF("count").toInt().coerceAtLeast(0)
                    val bodyBlocks = block.children["body"] ?: emptyList()
                    log += "  Цикл: $count раз"
                    repeat(count) { i ->
                        vars["i"] = i.toString()
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, onUpdate)) return false
                    }
                    vars.remove("i")
                }
                "while_loop" -> {
                    val left = block.params["left"]?.value ?: ""
                    val op = block.params["op"]?.value ?: "<="
                    val right = block.params["right"]?.value ?: "10"
                    val bodyBlocks = block.children["body"] ?: emptyList()
                    log += "  Цикл пока: $left $op $right"
                    var iterations = 0
                    while (iterations < 1000) {
                        val (result, err) = ExprEval.evalCondition(left, op, right, vars)
                        if (err != null) { errors += "Блок $num «Цикл пока»: $err"; break }
                        if (!result) break
                        iterations++
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, onUpdate)) return false
                    }
                    if (iterations >= 1000) errors += "Блок $num «Цикл пока»: превышен лимит итераций (1000)"
                }
                "wait" -> {
                    val seconds = getF("seconds", 1f).coerceIn(0f, 60f)
                    log += "  Ждём ${seconds}с"
                    if (allowDelay) {
                        onUpdate?.invoke()
                        delay((seconds * 1000).toLong())
                    }
                }
                "sim_rotate" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Вращать»: «$nameOrTag» не найден"; continue }
                    val mode = block.params["mode"]?.value ?: "instant"
                    val angle = getF("angle")
                    targets.forEach { (name, obj) ->
                        val nr = if (mode == "step") obj.rotation + angle else angle
                        objects[name] = obj.copy(rotation = nr % 360f)
                    }
                    log += "  «$nameOrTag» поворот -> ${angle}°"
                }
                "sim_joystick" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Джойстик»: имя пустое"; continue }
                    joysticks[name] = JoystickState(
                        name = name,
                        x = getF("x"), y = getF("y"),
                        baseRadius = getF("baseRadius", 100f).coerceAtLeast(20f),
                        knobRadius = getF("knobRadius", 40f).coerceAtLeast(10f),
                        baseColor = parseColor(getStr("baseColor", "#334466")),
                        knobColor = parseColor(getStr("knobColor", "#4F8EF7")),
                        targetObject = getStr("target"),
                        speed = getF("speed", 8f).coerceAtLeast(1f),
                        directional = getStr("directional", "false") == "true"
                    )
                    log += "  Джойстик «$name» создан"
                }
                "sim_modify" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Изменить свойства»: «$nameOrTag» не найден"; continue }
                    val props = block.children["props"] ?: emptyList()
                    targets.forEach { (name, obj) ->
                        var modified = obj
                        props.forEach { prop ->
                            val propName = prop.params["prop"]?.value ?: return@forEach
                            val propValue = prop.params["value"]?.value ?: return@forEach
                            val resolved = ExprEval.eval(propValue, vars).value
                            modified = when (propName) {
                                "x" -> modified.copy(x = resolved.toFloatOrNull() ?: modified.x)
                                "y" -> modified.copy(y = resolved.toFloatOrNull() ?: modified.y)
                                "width" -> modified.copy(width = (resolved.toFloatOrNull() ?: modified.width).coerceAtLeast(1f))
                                "height" -> modified.copy(height = (resolved.toFloatOrNull() ?: modified.height).coerceAtLeast(1f))
                                "radius" -> modified.copy(radius = (resolved.toFloatOrNull() ?: modified.radius).coerceAtLeast(0f))
                                "color" -> modified.copy(color = parseColor(resolved))
                                "visible" -> modified.copy(visible = resolved == "true")
                                "rotation" -> modified.copy(rotation = (resolved.toFloatOrNull() ?: modified.rotation) % 360f)
                                "label" -> modified.copy(label = resolved)
                                "fontSize" -> modified.copy(fontSize = (resolved.toFloatOrNull() ?: modified.fontSize).coerceAtLeast(6f))
                                "bold" -> modified.copy(bold = resolved == "true")
                                "textColor" -> modified.copy(textColor = if (resolved.isNotBlank()) parseColor(resolved) else null)
                                else -> modified
                            }
                        }
                        objects[name] = modified
                    }
                    // Для джойстиков
                    val joyTargets = if (nameOrTag.startsWith("#")) emptyList() else listOfNotNull(joysticks[nameOrTag]?.let { nameOrTag to it })
                    joyTargets.forEach { (name, joy) ->
                        var modified = joy
                        props.forEach { prop ->
                            val propName = prop.params["prop"]?.value ?: return@forEach
                            val propValue = prop.params["value"]?.value ?: return@forEach
                            val resolved = ExprEval.eval(propValue, vars).value
                            modified = when (propName) {
                                "x" -> modified.copy(x = resolved.toFloatOrNull() ?: modified.x)
                                "y" -> modified.copy(y = resolved.toFloatOrNull() ?: modified.y)
                                "baseRadius" -> modified.copy(baseRadius = (resolved.toFloatOrNull() ?: modified.baseRadius).coerceAtLeast(20f))
                                "knobRadius" -> modified.copy(knobRadius = (resolved.toFloatOrNull() ?: modified.knobRadius).coerceAtLeast(10f))
                                "baseColor" -> modified.copy(baseColor = parseColor(resolved))
                                "knobColor" -> modified.copy(knobColor = parseColor(resolved))
                                "speed" -> modified.copy(speed = (resolved.toFloatOrNull() ?: modified.speed).coerceAtLeast(1f))
                                "directional" -> modified.copy(directional = resolved == "true")
                                else -> modified
                            }
                        }
                        joysticks[name] = modified
                    }
                    log += "  «$nameOrTag» свойства изменены (${props.size})"
                }
                "table_set" -> {
                    val tableName = block.params["table"]?.value?.trim() ?: ""
                    if (tableName.isBlank()) { errors += "Блок $num «Таблица: записать»: имя таблицы не заполнено"; continue }
                    val key = getStr("key")
                    val value = getStr("value")
                    val tbl = tables.getOrPut(tableName) { mutableMapOf() }
                    tbl[key] = value
                    ExprEval.tables = tables.mapValues { it.value.toMap() }
                    log += "  $tableName[$key] = $value"
                }
                "table_get" -> {
                    val tableName = block.params["table"]?.value?.trim() ?: ""
                    if (tableName.isBlank()) { errors += "Блок $num «Таблица: читать»: имя таблицы не заполнено"; continue }
                    val key = getStr("key")
                    val varName = block.params["var"]?.value?.trim() ?: ""
                    if (varName.isBlank()) { errors += "Блок $num «Таблица: читать»: имя переменной не заполнено"; continue }
                    val tbl = tables[tableName]
                    val value = tbl?.get(key) ?: ""
                    vars[varName] = value
                    log += "  $varName = $tableName[$key] → «$value»"
                }
            }
        }
        return true
    }

    fun parseColor(hex: String): Color = runCatching {
        val clean = hex.trim().trimStart('#')
        val long = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000 or long)
            8 -> Color(long)
            else -> Color(0xFF4F8EF7)
        }
    }.getOrDefault(Color(0xFF4F8EF7))
}
