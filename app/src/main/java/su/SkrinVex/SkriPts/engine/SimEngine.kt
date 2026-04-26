package su.SkrinVex.SkriPts.engine

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import su.SkrinVex.SkriPts.block.BlockDef
import su.SkrinVex.SkriPts.data.Script
import su.SkrinVex.SkriPts.data.ScriptEvent
import su.SkrinVex.SkriPts.data.ProjectVar
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
    val tapScriptId: String? = null,
    val holdScriptId: String? = null,
    val visible: Boolean = true,
    val rotation: Float = 0f  // градусы, по часовой стрелке
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
    val log: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val isStopped: Boolean = false
)

object SimEngine {

    suspend fun run(
        scripts: List<Script>,
        globalVarDefs: List<ProjectVar>,
        onUpdate: (SimState) -> Unit = {}
    ): SimState {
        val objects = mutableMapOf<String, SimObject>()
        val joysticks = mutableMapOf<String, JoystickState>()
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val globalVars = globalVarDefs.associate { it.name to it.value }.toMutableMap()

        scripts.filter { it.event == ScriptEvent.ON_START }.forEach { script ->
            log += "Скрипт «${script.name}»"
            val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
            val vars = (globalVars + localVars).toMutableMap()
            runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, log, errors,
                allowDelay = true, onUpdate = { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), log.toList(), errors.toList())) })
            globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }
        }

        scripts.filter { it.event == ScriptEvent.ON_TAP }.forEach { script ->
            val target = script.eventTarget.trim()
            if (target.isNotBlank()) {
                val obj = objects[target]
                if (obj != null) objects[target] = obj.copy(tapScriptId = script.id)
                else errors += "Скрипт «${script.name}»: объект «$target» не найден для ON_TAP"
            }
        }

        scripts.filter { it.event == ScriptEvent.ON_HOLD }.forEach { script ->
            val target = script.eventTarget.trim()
            if (target.isNotBlank()) {
                val obj = objects[target]
                if (obj != null) objects[target] = obj.copy(holdScriptId = script.id)
                else errors += "Скрипт «${script.name}»: объект «$target» не найден для ON_HOLD"
            }
        }

        return SimState(objects = objects, joysticks = joysticks, globalVars = globalVars, log = log, errors = errors, isStopped = false)
    }

    suspend fun runTap(scriptId: String, scripts: List<Script>, currentState: SimState): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, currentState)
    }

    suspend fun runHold(scriptId: String, scripts: List<Script>, currentState: SimState): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, currentState)
    }

    private suspend fun runScriptOnState(script: Script, currentState: SimState): SimState {
        val objects = currentState.objects.toMutableMap()
        val joysticks = currentState.joysticks.toMutableMap()
        val log = currentState.log.toMutableList()
        val errors = currentState.errors.toMutableList()
        val globalVars = currentState.globalVars.toMutableMap()
        val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
        val vars = (globalVars + localVars).toMutableMap()

        log += "Касание -> «${script.name}»"
        val continued = runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, log, errors, allowDelay = true)
        globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }

        return currentState.copy(
            objects = objects,
            joysticks = joysticks,
            globalVars = globalVars,
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
        log: MutableList<String>,
        errors: MutableList<String>,
        allowDelay: Boolean = true,
        onUpdate: (() -> Unit)? = null
    ): Boolean {
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
                        if (!runScript(branchBlocks, vars, objects, joysticks, log, errors, allowDelay, onUpdate)) return false
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
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Переместить»: «$name» не найден"; continue }
                    val mode = block.params["mode"]?.value ?: "instant"
                    val rawX = block.params["x"]?.value ?: "0"
                    val rawY = block.params["y"]?.value ?: "0"
                    val noneX = rawX.trim() == "\$none"
                    val noneY = rawY.trim() == "\$none"
                    val dx = if (noneX) 0f else getF("x")
                    val dy = if (noneY) 0f else getF("y")
                    val nx = when { noneX -> obj.x; mode == "step" -> obj.x + dx; else -> dx }
                    val ny = when { noneY -> obj.y; mode == "step" -> obj.y + dy; else -> dy }
                    objects[name] = obj.copy(x = nx, y = ny)
                    if (mode == "step") log += "  «$name» шаг (+$dx, +$dy) -> ($nx, $ny)"
                    else log += "  «$name» -> ($nx, $ny)"
                }
                "sim_resize" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Размер»: «$name» не найден"; continue }
                    objects[name] = obj.copy(width = getF("width", 100f).coerceAtLeast(1f), height = getF("height", 60f).coerceAtLeast(1f))
                    log += "  «$name» размер ${getStr("width")}x${getStr("height")}"
                }
                "sim_color" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Цвет»: «$name» не найден"; continue }
                    objects[name] = obj.copy(color = parseColor(getStr("color", "#4F8EF7")))
                    log += "  «$name» цвет -> ${getStr("color")}"
                }
                "sim_label" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Текст»: «$name» не найден"; continue }
                    objects[name] = obj.copy(label = getStr("text"))
                    log += "  «$name» текст: «${getStr("text")}»"
                }
                "sim_update_text" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Обновить текст»: «$name» не найден"; continue }
                    val text = getStr("text")
                    objects[name] = obj.copy(label = text)
                    log += "  «$name» текст обновлён: «$text»"
                }
                "sim_text" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Текстовый объект»: имя пустое"; continue }
                    if (objects.containsKey(name)) { errors += "Блок $num: объект «$name» уже существует"; continue }
                    objects[name] = SimObject(
                        name = name, x = getF("x"), y = getF("y"),
                        width = getF("width", 200f).coerceAtLeast(1f),
                        height = getF("height", 40f).coerceAtLeast(1f),
                        radius = 0f, color = Color.Transparent,
                        label = getStr("text"),
                        fontSize = getF("size", 16f).coerceAtLeast(6f),
                        bold = getStr("bold", "false") == "true"
                    )
                    log += "  Текст «$name»: «${getStr("text")}»"
                }
                "sim_hide" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Скрыть»: «$name» не найден"; continue }
                    objects[name] = obj.copy(visible = false)
                    log += "  «$name» скрыт"
                }
                "sim_show" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Показать»: «$name» не найден"; continue }
                    objects[name] = obj.copy(visible = true)
                    log += "  «$name» показан"
                }
                "for_loop" -> {
                    val count = getF("count").toInt().coerceAtLeast(0)
                    val bodyBlocks = block.children["body"] ?: emptyList()
                    log += "  Цикл: $count раз"
                    repeat(count) { i ->
                        vars["i"] = i.toString()
                        if (!runScript(bodyBlocks, vars, objects, joysticks, log, errors, allowDelay, onUpdate)) return false
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
                        if (!runScript(bodyBlocks, vars, objects, joysticks, log, errors, allowDelay, onUpdate)) return false
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
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Вращать»: «$name» не найден"; continue }
                    val mode = block.params["mode"]?.value ?: "instant"
                    val angle = getF("angle")
                    val nr = if (mode == "step") obj.rotation + angle else angle
                    objects[name] = obj.copy(rotation = nr % 360f)
                    log += "  «$name» поворот -> ${nr % 360f}°"
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
