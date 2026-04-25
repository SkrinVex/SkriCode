package su.SkrinVex.SkriPts.engine

import androidx.compose.ui.graphics.Color
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
    val tapScriptId: String? = null
)

data class SimState(
    val objects: Map<String, SimObject> = emptyMap(),
    val globalVars: Map<String, String> = emptyMap(),  // живые глобальные — накапливаются между касаниями
    val log: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

object SimEngine {

    fun run(scripts: List<Script>, globalVarDefs: List<ProjectVar>): SimState {
        val objects = mutableMapOf<String, SimObject>()
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        // Начальные значения глобальных переменных
        val globalVars = globalVarDefs.associate { it.name to it.value }.toMutableMap()

        // ON_START скрипты
        scripts.filter { it.event == ScriptEvent.ON_START }.forEach { script ->
            log += "Скрипт «${script.name}»"
            // Локальные переменные — только для этого скрипта, не сохраняются
            val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
            val vars = (globalVars + localVars).toMutableMap()
            runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, log, errors)
            // Сохраняем обновлённые глобальные
            globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }
        }

        // Привязываем ON_TAP
        scripts.filter { it.event == ScriptEvent.ON_TAP }.forEach { script ->
            val target = script.eventTarget.trim()
            if (target.isNotBlank()) {
                val obj = objects[target]
                if (obj != null) objects[target] = obj.copy(tapScriptId = script.id)
                else errors += "Скрипт «${script.name}»: объект «$target» не найден для ON_TAP"
            }
        }

        return SimState(objects = objects, globalVars = globalVars, log = log, errors = errors)
    }

    /** ON_TAP: использует актуальные globalVars из SimState, локальные берёт из скрипта */
    fun runTap(scriptId: String, scripts: List<Script>, currentState: SimState): SimState {
        val script = scripts.find { it.id == scriptId } ?: return currentState
        val objects = currentState.objects.toMutableMap()
        val log = currentState.log.toMutableList()
        val errors = currentState.errors.toMutableList()

        // Актуальные глобальные из SimState (накопленные)
        val globalVars = currentState.globalVars.toMutableMap()
        // Локальные — из определения скрипта (сбрасываются при каждом касании)
        val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
        val vars = (globalVars + localVars).toMutableMap()

        log += "Касание -> «${script.name}»"
        runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, log, errors)

        // Сохраняем обновлённые глобальные обратно в SimState
        globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }

        return currentState.copy(objects = objects, globalVars = globalVars, log = log, errors = errors)
    }

    private fun runScript(
        blocks: List<BlockDef>,
        vars: MutableMap<String, String>,
        objects: MutableMap<String, SimObject>,
        log: MutableList<String>,
        errors: MutableList<String>
    ) {
        blocks.forEachIndexed { idx, block ->
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
                    if (name.isBlank()) { errors += "Блок $num «Переменная»: имя не заполнено"; return@forEachIndexed }
                    val value = getStr("value")
                    vars[name] = value
                    log += "  $name = $value"
                }
                "sim_create" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Создать объект»: имя пустое"; return@forEachIndexed }
                    if (objects.containsKey(name)) { errors += "Блок $num: объект «$name» уже существует"; return@forEachIndexed }
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
                    val obj = objects[name] ?: run { errors += "Блок $num «Переместить»: «$name» не найден"; return@forEachIndexed }
                    objects[name] = obj.copy(x = getF("x"), y = getF("y"))
                    log += "  «$name» -> (${getStr("x")}, ${getStr("y")})"
                }
                "sim_resize" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Размер»: «$name» не найден"; return@forEachIndexed }
                    objects[name] = obj.copy(width = getF("width", 100f).coerceAtLeast(1f), height = getF("height", 60f).coerceAtLeast(1f))
                    log += "  «$name» размер ${getStr("width")}x${getStr("height")}"
                }
                "sim_color" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Цвет»: «$name» не найден"; return@forEachIndexed }
                    objects[name] = obj.copy(color = parseColor(getStr("color", "#4F8EF7")))
                    log += "  «$name» цвет -> ${getStr("color")}"
                }
                "sim_label" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Текст»: «$name» не найден"; return@forEachIndexed }
                    objects[name] = obj.copy(label = getStr("text"))
                    log += "  «$name» текст: «${getStr("text")}»"
                }
                "sim_update_text" -> {
                    val name = getStr("name")
                    val obj = objects[name] ?: run { errors += "Блок $num «Обновить текст»: «$name» не найден"; return@forEachIndexed }
                    val text = getStr("text")
                    objects[name] = obj.copy(label = text)
                    log += "  «$name» текст обновлён: «$text»"
                }
                "sim_text" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Текстовый объект»: имя пустое"; return@forEachIndexed }
                    if (objects.containsKey(name)) { errors += "Блок $num: объект «$name» уже существует"; return@forEachIndexed }
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
            }
        }
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
