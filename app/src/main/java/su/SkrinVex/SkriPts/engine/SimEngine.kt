package su.SkrinVex.SkriPts.engine

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import su.SkrinVex.SkriPts.block.BlockDef
import su.SkrinVex.SkriPts.data.Script
import su.SkrinVex.SkriPts.data.ScriptEvent
import su.SkrinVex.SkriPts.data.ProjectVar
import su.SkrinVex.SkriPts.data.ProjectTable
import su.SkrinVex.SkriPts.data.deserialize

/** Тип хитбокса */
enum class HitboxType { AUTO, MANUAL }

/** Хитбокс объекта */
data class Hitbox(
    val type: HitboxType = HitboxType.AUTO,
    /** Для MANUAL — список точек в локальных координатах объекта (относительно центра) */
    val points: List<Pair<Float, Float>> = emptyList()
)

/** Физическое тело объекта */
data class PhysicsBody(
    val enabled: Boolean = true,
    val gravity: Float = -9.8f,   // px/tick² (отрицательное = вниз)
    val isStatic: Boolean = false, // статик нельзя двигать физикой/джойстиком
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val mass: Float = 1f,
    val bounciness: Float = 0f    // 0..1
)

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
    val collisionScriptId: String? = null,
    val collisionEndScriptId: String? = null,
    val visible: Boolean = true,
    val rotation: Float = 0f,
    val tags: Set<String> = emptySet(),
    val physicsBody: PhysicsBody? = null,
    val hitbox: Hitbox = Hitbox()
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
    val isStopped: Boolean = false,
    val physicsEnabled: Boolean = true,
    /** Пары имён объектов которые сейчас соприкасаются */
    val activeCollisions: Set<Pair<String, String>> = emptySet()
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
        val globalTables = globalTableDefs.associate { it.name to it.entries.toMutableMap() }
            .mapValues { it.value }.toMutableMap<String, MutableMap<String, String>>()
        var physicsEnabled = true

        scripts.filter { it.event == ScriptEvent.ON_START }.forEach { script ->
            log += "Скрипт «${script.name}»"
            val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
            val vars = (globalVars + localVars).toMutableMap()
            val localTables = script.localTables.orEmpty().associate { it.name to it.entries.toMutableMap() }
            val allTables = (globalTables + localTables).toMutableMap<String, MutableMap<String, String>>()
            runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors,
                allowDelay = true, physicsEnabledRef = { physicsEnabled }, setPhysicsEnabled = { physicsEnabled = it },
                onUpdate = { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList(), physicsEnabled = physicsEnabled)) })
            globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }
            globalTables.keys.forEach { k -> allTables[k]?.let { globalTables[k] = it } }
        }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        return SimState(objects = objects, joysticks = joysticks, globalVars = globalVars,
            tables = globalTables.mapValues { it.value.toMap() }, log = log, errors = errors, isStopped = false,
            physicsEnabled = physicsEnabled)
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
        fun bindToObjects(event: ScriptEvent, assign: SimObject.(String) -> SimObject) {
            scripts.filter { it.event == event }.forEach { script ->
                val target = script.eventTarget.trim()
                if (target.isBlank()) return@forEach
                val targets = if (target.startsWith("#")) {
                    val tag = target.substring(1)
                    objects.filter { (_, o) -> tag in o.tags }.keys.toList()
                } else listOfNotNull(target.takeIf { objects.containsKey(it) })
                if (targets.isEmpty() && warnMissing)
                    errors += "Скрипт «${script.name}»: объект/тег «$target» не найден для ${event.name}"
                targets.forEach { name -> objects[name] = objects[name]!!.assign(script.id) }
            }
        }
        bindToObjects(ScriptEvent.ON_TAP)          { id -> if (tapScriptId != id) copy(tapScriptId = id) else this }
        bindToObjects(ScriptEvent.ON_HOLD)         { id -> if (holdScriptId != id) copy(holdScriptId = id) else this }
        bindToObjects(ScriptEvent.ON_COLLISION)    { id -> if (collisionScriptId != id) copy(collisionScriptId = id) else this }
        bindToObjects(ScriptEvent.ON_COLLISION_END){ id -> if (collisionEndScriptId != id) copy(collisionEndScriptId = id) else this }
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

    suspend fun runCollision(scriptId: String, scripts: List<Script>, currentState: SimState, otherName: String = "", selfName: String = ""): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, scripts, currentState, collisionTarget = otherName, collisionSelf = selfName)
    }

    /** Применяет один тик физики. Возвращает новое состояние + пары новых/завершённых коллизий. */
    fun physicsTick(state: SimState): Triple<SimState, Set<Pair<String,String>>, Set<Pair<String,String>>> {
        if (!state.physicsEnabled) return Triple(state, emptySet(), emptySet())
        val objects = state.objects.toMutableMap()

        val dynamics = objects.entries.filter { (_, obj) ->
            val b = obj.physicsBody; b != null && b.enabled && !b.isStatic
        }
        if (dynamics.isEmpty()) return Triple(state, emptySet(), emptySet())

        dynamics.forEach { (name, obj) ->
            val body = obj.physicsBody!!
            val vy = body.velocityY + body.gravity * 0.016f
            objects[name] = obj.copy(x = obj.x + body.velocityX, y = obj.y + vy,
                physicsBody = body.copy(velocityY = vy))
        }

        val allPhysics = objects.values.filter { it.physicsBody != null }
        val currentCollisions = mutableSetOf<Pair<String, String>>()

        repeat(3) {
            for (i in allPhysics.indices) {
                for (j in i + 1 until allPhysics.size) {
                    val a = objects[allPhysics[i].name] ?: continue
                    val b = objects[allPhysics[j].name] ?: continue
                    val aBody = a.physicsBody ?: continue
                    val bBody = b.physicsBody ?: continue
                    if (aBody.isStatic && bBody.isStatic) continue
                    if (!aBody.enabled && !bBody.enabled) continue

                    val overlapX = (a.width / 2f + b.width / 2f) - kotlin.math.abs(a.x - b.x)
                    val overlapY = (a.height / 2f + b.height / 2f) - kotlin.math.abs(a.y - b.y)
                    if (overlapX <= 0f || overlapY <= 0f) continue

                    // Записываем коллизию (имена в алфавитном порядке для уникальности)
                    val pair = if (a.name < b.name) a.name to b.name else b.name to a.name
                    currentCollisions += pair

                    val totalMass = aBody.mass + bBody.mass
                    val aRatio = if (aBody.isStatic) 0f else if (bBody.isStatic) 1f else bBody.mass / totalMass
                    val bRatio = if (bBody.isStatic) 0f else if (aBody.isStatic) 1f else aBody.mass / totalMass

                    if (overlapX < overlapY) {
                        val sign = if (a.x < b.x) -1f else 1f
                        val push = overlapX + 0.5f
                        val bounce = (aBody.bounciness + bBody.bounciness) / 2f
                        val relVx = aBody.velocityX - bBody.velocityX
                        val impulse = relVx * (1f + bounce) / (1f / aBody.mass + 1f / bBody.mass)
                        if (!aBody.isStatic) objects[a.name] = (objects[a.name] ?: a).let { it.copy(x = it.x + sign * push * aRatio, physicsBody = it.physicsBody!!.copy(velocityX = (aBody.velocityX - impulse / aBody.mass) * 0.5f)) }
                        if (!bBody.isStatic) objects[b.name] = (objects[b.name] ?: b).let { it.copy(x = it.x - sign * push * bRatio, physicsBody = it.physicsBody!!.copy(velocityX = (bBody.velocityX + impulse / bBody.mass) * 0.5f)) }
                    } else {
                        val sign = if (a.y < b.y) -1f else 1f
                        val push = overlapY + 0.5f
                        val bounce = (aBody.bounciness + bBody.bounciness) / 2f
                        val relVy = aBody.velocityY - bBody.velocityY
                        val impulse = relVy * (1f + bounce) / (1f / aBody.mass + 1f / bBody.mass)
                        if (!aBody.isStatic) objects[a.name] = (objects[a.name] ?: a).let { it.copy(y = it.y + sign * push * aRatio, physicsBody = it.physicsBody!!.copy(velocityY = (aBody.velocityY - impulse / aBody.mass) * 0.5f)) }
                        if (!bBody.isStatic) objects[b.name] = (objects[b.name] ?: b).let { it.copy(y = it.y - sign * push * bRatio, physicsBody = it.physicsBody!!.copy(velocityY = (bBody.velocityY + impulse / bBody.mass) * 0.5f)) }
                    }
                }
            }
        }

        val newCollisions = currentCollisions - state.activeCollisions
        val endedCollisions = state.activeCollisions - currentCollisions
        val newState = state.copy(objects = objects, activeCollisions = currentCollisions)
        return Triple(newState, newCollisions, endedCollisions)
    }

    private suspend fun runScriptOnState(script: Script, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null, collisionTarget: String = "", collisionSelf: String = ""): SimState {
        val objects = currentState.objects.toMutableMap()
        val joysticks = currentState.joysticks.toMutableMap()
        val log = currentState.log.toMutableList()
        val errors = currentState.errors.toMutableList()
        val globalVars = currentState.globalVars.toMutableMap()
        val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
        val vars = (globalVars + localVars).toMutableMap()

        // Заполняем collision_* переменные если это скрипт коллизии
        if (collisionTarget.isNotBlank()) {
            val other = objects[collisionTarget]
            val self  = if (collisionSelf.isNotBlank()) objects[collisionSelf] else null
            // Другой объект
            vars["collision_other"]    = collisionTarget
            vars["collision_name"]     = collisionTarget  // алиас
            vars["collision_x"]        = other?.x?.let { "%.1f".format(it) } ?: "0"
            vars["collision_y"]        = other?.y?.let { "%.1f".format(it) } ?: "0"
            vars["collision_width"]    = other?.width?.let { "%.1f".format(it) } ?: "0"
            vars["collision_height"]   = other?.height?.let { "%.1f".format(it) } ?: "0"
            vars["collision_rotation"] = other?.rotation?.let { "%.1f".format(it) } ?: "0"
            // Свой объект
            vars["collision_self"]          = collisionSelf
            vars["collision_self_x"]        = self?.x?.let { "%.1f".format(it) } ?: "0"
            vars["collision_self_y"]        = self?.y?.let { "%.1f".format(it) } ?: "0"
            vars["collision_self_width"]    = self?.width?.let { "%.1f".format(it) } ?: "0"
            vars["collision_self_height"]   = self?.height?.let { "%.1f".format(it) } ?: "0"
            vars["collision_self_rotation"] = self?.rotation?.let { "%.1f".format(it) } ?: "0"
        }

        val localTables = script.localTables.orEmpty().associate { it.name to it.entries.toMutableMap() }
        val allTables = (currentState.tables.mapValues { it.value.toMutableMap() } + localTables).toMutableMap<String, MutableMap<String, String>>()
        var physicsEnabled = currentState.physicsEnabled

        log += if (collisionTarget.isNotBlank()) "Коллизия -> «${script.name}» (с «$collisionTarget»)" else "Касание -> «${script.name}»"
        val continued = runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors, allowDelay = true,
            physicsEnabledRef = { physicsEnabled }, setPhysicsEnabled = { physicsEnabled = it },
            onUpdate = if (onUpdate != null) {
                { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList(), physicsEnabled = physicsEnabled)) }
            } else null
        )
        globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        return currentState.copy(
            objects = objects, joysticks = joysticks, globalVars = globalVars,
            tables = allTables.mapValues { it.value.toMap() },
            log = log, errors = errors, isStopped = !continued, physicsEnabled = physicsEnabled
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
        physicsEnabledRef: () -> Boolean = { true },
        setPhysicsEnabled: (Boolean) -> Unit = {},
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
                        if (!runScript(branchBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, onUpdate)) return false
                    }
                }
                "sim_create" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Создать объект»: имя пустое"; continue }
                    val existing = objects[name]
                    objects[name] = SimObject(
                        name = name, x = getF("x"), y = getF("y"),
                        width = getF("width", 100f).coerceAtLeast(1f),
                        height = getF("height", 60f).coerceAtLeast(1f),
                        radius = getF("radius", 8f).coerceAtLeast(0f),
                        color = parseColor(getStr("color", "#4F8EF7")),
                        // Сохраняем скрипты и физику если объект уже существовал
                        tapScriptId = existing?.tapScriptId,
                        holdScriptId = existing?.holdScriptId,
                        collisionScriptId = existing?.collisionScriptId,
                        collisionEndScriptId = existing?.collisionEndScriptId,
                        physicsBody = existing?.physicsBody,
                        hitbox = existing?.hitbox ?: Hitbox()
                    )
                    if (existing != null) log += "  Обновлён «$name» (${getStr("x")}, ${getStr("y")})"
                    else log += "  Создан «$name» (${getStr("x")}, ${getStr("y")}) ${getStr("width")}x${getStr("height")}"
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
                    val existing = objects[name]
                    val tcRaw = getStr("textColor", "")
                    objects[name] = SimObject(
                        name = name, x = getF("x"), y = getF("y"),
                        width = getF("width", 200f).coerceAtLeast(1f),
                        height = getF("height", 40f).coerceAtLeast(1f),
                        radius = 0f, color = Color.Transparent,
                        label = getStr("text"),
                        fontSize = getF("size", 16f).coerceAtLeast(6f),
                        bold = getStr("bold", "false") == "true",
                        textColor = if (tcRaw.isNotBlank()) parseColor(tcRaw) else null,
                        tapScriptId = existing?.tapScriptId,
                        holdScriptId = existing?.holdScriptId,
                        collisionScriptId = existing?.collisionScriptId,
                        collisionEndScriptId = existing?.collisionEndScriptId,
                        physicsBody = existing?.physicsBody,
                        hitbox = existing?.hitbox ?: Hitbox()
                    )
                    log += "  ${if (existing != null) "Обновлён" else "Создан"} текст «$name»: «${getStr("text")}»"
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
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, onUpdate)) return false
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
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, onUpdate)) return false
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
                                // Физические свойства
                                "physics_enabled" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(enabled = resolved == "true"))
                                "physics_gravity" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(gravity = resolved.toFloatOrNull() ?: (modified.physicsBody?.gravity ?: -9.8f)))
                                "physics_static" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(isStatic = resolved == "true"))
                                "physics_bounciness" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(bounciness = (resolved.toFloatOrNull() ?: (modified.physicsBody?.bounciness ?: 0f)).coerceIn(0f, 1f)))
                                "physics_mass" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(mass = (resolved.toFloatOrNull() ?: (modified.physicsBody?.mass ?: 1f)).coerceAtLeast(0.01f)))
                                "physics_vx" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(velocityX = resolved.toFloatOrNull() ?: (modified.physicsBody?.velocityX ?: 0f)))
                                "physics_vy" -> modified.copy(physicsBody = (modified.physicsBody ?: PhysicsBody()).copy(velocityY = resolved.toFloatOrNull() ?: (modified.physicsBody?.velocityY ?: 0f)))
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
                "sim_delete" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Удалить объект»: «$nameOrTag» не найден"; continue }
                    targets.forEach { (name, _) -> objects.remove(name) }
                    joysticks.remove(nameOrTag)
                    log += "  Удалён «$nameOrTag» (${targets.size} объект(ов))"
                }
                "sim_physics" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Физика объекта»: «$nameOrTag» не найден"; continue }
                    val gravity = getF("gravity", -9.8f)
                    val isStatic = getStr("static", "false") == "true"
                    val bounciness = getF("bounciness", 0f).coerceIn(0f, 1f)
                    val mass = getF("mass", 1f).coerceAtLeast(0.01f)
                    val vx = getF("vx", 0f)
                    val vy = getF("vy", 0f)
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(physicsBody = PhysicsBody(
                            enabled = true, gravity = gravity, isStatic = isStatic,
                            bounciness = bounciness, mass = mass,
                            velocityX = vx, velocityY = vy
                        ))
                    }
                    log += "  «$nameOrTag» физика: g=$gravity static=$isStatic (хитбокс AUTO по умолчанию)"
                }
                "sim_hitbox" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Хитбокс»: «$nameOrTag» не найден"; continue }
                    val typeStr = block.params["type"]?.value ?: "auto"
                    val pointsStr = block.params["points"]?.value ?: ""
                    val hbType = if (typeStr == "manual" && pointsStr.isNotBlank()) HitboxType.MANUAL else HitboxType.AUTO
                    val pts = if (hbType == HitboxType.MANUAL) parseHitboxPoints(pointsStr) else emptyList()
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(hitbox = Hitbox(type = hbType, points = pts))
                    }
                    log += "  «$nameOrTag» хитбокс: $hbType (${pts.size} точек)"
                }
                "physics_toggle" -> {
                    val enabled = getStr("enabled", "true") == "true"
                    setPhysicsEnabled(enabled)
                    log += "  Физика: ${if (enabled) "включена" else "выключена"}"
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

    /** Парсит строку вида "x1,y1;x2,y2;..." в список точек */
    fun parseHitboxPoints(s: String): List<Pair<Float, Float>> = runCatching {
        s.trim().split(";").mapNotNull { part ->
            val xy = part.trim().split(",")
            if (xy.size == 2) Pair(xy[0].trim().toFloat(), xy[1].trim().toFloat()) else null
        }
    }.getOrDefault(emptyList())

    /** Сериализует список точек в строку */
    fun serializeHitboxPoints(pts: List<Pair<Float, Float>>): String =
        pts.joinToString(";") { "${it.first},${it.second}" }
}
