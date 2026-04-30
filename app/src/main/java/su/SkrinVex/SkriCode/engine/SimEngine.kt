package su.SkrinVex.SkriCode.engine

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.data.Script
import su.SkrinVex.SkriCode.data.ScriptEvent
import su.SkrinVex.SkriCode.data.ProjectVar
import su.SkrinVex.SkriCode.data.ProjectTable
import su.SkrinVex.SkriCode.data.deserialize
import su.SkrinVex.SkriCode.engine.SaveCrypto

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
    val hitbox: Hitbox = Hitbox(),
    val zOrder: Int = 0,
    /** Имена или теги (#tag) объектов с которыми не считать коллизии */
    val collisionIgnore: Set<String> = emptySet(),
    // Текстура
    val spriteName: String? = null,
    val spriteAlpha: Float = 1f,
    val spriteScaleX: Float = 1f,
    val spriteScaleY: Float = 1f,
    val spriteCropX: Int = 0,
    val spriteCropY: Int = 0,
    val spriteCropW: Int = 0,
    val spriteCropH: Int = 0
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
    val visible: Boolean = true,
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
    val activeCollisions: Set<Pair<String, String>> = emptySet(),
    val camera: SimCamera? = null,
    val pendingSceneSwitch: String? = null,  // имя сцены для перехода
    val sprites: List<su.SkrinVex.SkriCode.data.SpriteAsset> = emptyList(),
    val projectId: String = ""
)

/** Камера слежения */
data class SimCamera(
    val name: String,
    val enabled: Boolean = true,
    val targetName: String = "",   // имя объекта за которым следим
    val uiTags: Set<String> = emptySet(), // теги объектов-интерфейса (не двигаются с камерой)
    val smoothing: Float = 1f,     // 0..1: 1 = мгновенно, 0.05 = очень плавно
    // runtime — текущее смещение камеры
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

object SimEngine {

    var appContext: Context? = null
    var projectName: String = ""

    suspend fun run(
        scripts: List<Script>,
        globalVarDefs: List<ProjectVar>,
        globalTableDefs: List<ProjectTable> = emptyList(),
        locationBlocks: List<su.SkrinVex.SkriCode.data.SerializedBlock> = emptyList(),
        sprites: List<su.SkrinVex.SkriCode.data.SpriteAsset> = emptyList(),
        projectId: String = "",
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

        // Синхронизируем спрайты в ExprEval
        ExprEval.sprites = sprites

        // Создаём объекты локации до выполнения скриптов
        locationBlocks.mapNotNull { it.deserialize() }.forEach { block ->
            val name = block.params["name"]?.value?.trim() ?: return@forEach
            if (name.isBlank()) return@forEach
            fun p(key: String) = block.params[key]?.value ?: ""
            fun pf(key: String, def: Float) = p(key).toFloatOrNull() ?: def

            val tags = p("_tags").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val zOrder = p("_zOrder").toIntOrNull() ?: 0
            val hasPhysics = p("_physics") == "true"
            val physicsBody = if (hasPhysics) PhysicsBody(
                enabled = true,
                gravity = pf("_gravity", -9.8f),
                isStatic = p("_static") == "true",
                bounciness = pf("_bounciness", 0f).coerceIn(0f, 1f),
                mass = pf("_mass", 1f).coerceAtLeast(0.01f),
                velocityX = pf("_vx", 0f),
                velocityY = pf("_vy", 0f)
            ) else null
            val hitboxType = if (p("_hitbox_type") == "manual") HitboxType.MANUAL else HitboxType.AUTO
            val hitboxPts = if (hitboxType == HitboxType.MANUAL) parseHitboxPoints(p("_hitbox_points")) else emptyList()
            val hitbox = Hitbox(type = hitboxType, points = hitboxPts)

            when (block.type) {
                "sim_create" -> objects[name] = SimObject(
                    name = name,
                    x = ExprEval.eval(p("x").ifBlank { "0" }, emptyMap()).value.toFloatOrNull() ?: 0f,
                    y = ExprEval.eval(p("y").ifBlank { "0" }, emptyMap()).value.toFloatOrNull() ?: 0f,
                    width = pf("width", 100f).coerceAtLeast(1f),
                    height = pf("height", 60f).coerceAtLeast(1f),
                    radius = pf("radius", 8f).coerceAtLeast(0f),
                    color = parseColor(p("color").ifBlank { "#4F8EF7" }),
                    tags = tags, physicsBody = physicsBody, hitbox = hitbox, zOrder = zOrder
                )
                "sim_text" -> objects[name] = SimObject(
                    name = name,
                    x = ExprEval.eval(p("x").ifBlank { "0" }, emptyMap()).value.toFloatOrNull() ?: 0f,
                    y = ExprEval.eval(p("y").ifBlank { "0" }, emptyMap()).value.toFloatOrNull() ?: 0f,
                    width = pf("width", 200f).coerceAtLeast(1f),
                    height = pf("height", 40f).coerceAtLeast(1f),
                    radius = 0f,
                    color = parseColor(p("textColor").ifBlank { "#FFFFFF" }),
                    label = p("text"),
                    fontSize = pf("size", 16f),
                    bold = p("bold") == "true",
                    tags = tags, physicsBody = physicsBody, hitbox = hitbox, zOrder = zOrder
                )
                "sim_sprite" -> {
                    val spriteAsset = sprites.find { it.name == p("sprite") }
                    val rawW = pf("width", 0f); val rawH = pf("height", 0f)
                    val w = if (rawW > 0f) rawW else (spriteAsset?.width?.toFloat() ?: 100f)
                    val h = if (rawH > 0f) rawH else (spriteAsset?.height?.toFloat() ?: 100f)
                    objects[name] = SimObject(
                        name = name,
                        x = ExprEval.eval(p("x").ifBlank { "0" }, emptyMap()).value.toFloatOrNull() ?: 0f,
                        y = ExprEval.eval(p("y").ifBlank { "0" }, emptyMap()).value.toFloatOrNull() ?: 0f,
                        width = w.coerceAtLeast(1f), height = h.coerceAtLeast(1f),
                        radius = 0f, color = androidx.compose.ui.graphics.Color.Transparent,
                        spriteName = p("sprite").ifBlank { null },
                        spriteAlpha = pf("alpha", 1f).coerceIn(0f, 1f),
                        tags = tags, physicsBody = physicsBody, hitbox = hitbox, zOrder = zOrder
                    )
                }
            }
            // Выполняем setup-блоки объекта (sim_physics, sim_hitbox, set_tag и т.д.)
            val setupBlocks = block.children["setup"] ?: emptyList()
            if (setupBlocks.isNotEmpty()) {
                val dummyVars = mutableMapOf<String, String>()
                runScript(setupBlocks, dummyVars, objects, joysticks,
                    mutableMapOf(), log, errors,
                    allowDelay = false, physicsEnabledRef = { physicsEnabled },
                    setPhysicsEnabled = { physicsEnabled = it }, cameraRef = arrayOf(null))
            }
        }
        val cameraRef: Array<SimCamera?> = arrayOf(null)

        val sceneSwitchRef: Array<String?> = arrayOf(null)
        scripts.filter { it.event == ScriptEvent.ON_START }.forEach { script ->
            if (sceneSwitchRef[0] != null) return@forEach  // уже переключаемся
            log += "Скрипт «${script.name}»"
            val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
            val vars = (globalVars + localVars).toMutableMap()
            val localTables = script.localTables.orEmpty().associate { it.name to it.entries.toMutableMap() }
            val allTables = (globalTables + localTables).toMutableMap<String, MutableMap<String, String>>()
            runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors,
                allowDelay = true, physicsEnabledRef = { physicsEnabled }, setPhysicsEnabled = { physicsEnabled = it },
                cameraRef = cameraRef, sceneSwitchRef = sceneSwitchRef,
                onUpdate = { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList(), physicsEnabled = physicsEnabled, camera = cameraRef[0], sprites = sprites, projectId = projectId)) })
            globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }
            globalTables.keys.forEach { k -> allTables[k]?.let { globalTables[k] = it } }
        }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        return SimState(objects = objects, joysticks = joysticks, globalVars = globalVars,
            tables = globalTables.mapValues { it.value.toMap() }, log = log, errors = errors, isStopped = false,
            physicsEnabled = physicsEnabled, camera = cameraRef[0], pendingSceneSwitch = sceneSwitchRef[0],
            sprites = sprites, projectId = projectId)
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

    suspend fun runTap(scriptId: String, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null, getLatestState: (() -> SimState)? = null): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, scripts, currentState, onUpdate, getLatestState = getLatestState)
    }

    suspend fun runHold(scriptId: String, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null, getLatestState: (() -> SimState)? = null): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, scripts, currentState, onUpdate, getLatestState = getLatestState)
    }

    suspend fun runCollision(scriptId: String, scripts: List<Script>, currentState: SimState, otherName: String = "", selfName: String = "", onUpdate: ((SimState) -> Unit)? = null, getLatestState: (() -> SimState)? = null): SimState {
        if (currentState.isStopped) return currentState
        val script = scripts.find { it.id == scriptId } ?: return currentState
        return runScriptOnState(script, scripts, currentState, onUpdate = onUpdate, collisionTarget = otherName, collisionSelf = selfName, getLatestState = getLatestState)
    }

    /** Применяет один тик физики. Возвращает новое состояние + пары новых/завершённых коллизий. */
    fun physicsTick(state: SimState): Triple<SimState, Set<Pair<String,String>>, Set<Pair<String,String>>> {
        if (!state.physicsEnabled) return Triple(state, emptySet(), emptySet())
        val objects = state.objects.toMutableMap()

        val dynamics = objects.entries.filter { (_, obj) ->
            val b = obj.physicsBody; b != null && b.enabled && !b.isStatic && obj.visible
        }
        if (dynamics.isEmpty()) return Triple(state, emptySet(), emptySet())

        dynamics.forEach { (name, obj) ->
            val body = obj.physicsBody!!
            val vy = body.velocityY + body.gravity * 0.016f
            objects[name] = obj.copy(x = obj.x + body.velocityX, y = obj.y + vy,
                physicsBody = body.copy(velocityY = vy))
        }

        val allPhysics = objects.values.filter { it.physicsBody != null && it.visible }
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

                    // Проверяем collisionIgnore
                    fun ignores(obj: SimObject, other: SimObject): Boolean {
                        if (other.name in obj.collisionIgnore) return true
                        return obj.collisionIgnore.any { it.startsWith("#") && it.substring(1) in other.tags }
                    }
                    if (ignores(a, b) || ignores(b, a)) continue

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

    /** Обновляет позицию камеры мгновенно по текущему состоянию объектов. Вызывается после каждого движения. */
    fun tickCamera(state: SimState): SimState {
        val cam = state.camera ?: return state
        if (!cam.enabled || cam.targetName.isBlank()) return state
        val target = state.objects[cam.targetName] ?: return state
        val targetOffX = -target.x
        val targetOffY = target.y
        val s = cam.smoothing.coerceIn(0.01f, 1f)
        val newCam = cam.copy(
            offsetX = cam.offsetX + (targetOffX - cam.offsetX) * s,
            offsetY = cam.offsetY + (targetOffY - cam.offsetY) * s
        )
        return state.copy(camera = newCam)
    }

    private suspend fun runScriptOnState(script: Script, scripts: List<Script>, currentState: SimState, onUpdate: ((SimState) -> Unit)? = null, collisionTarget: String = "", collisionSelf: String = "", getLatestState: (() -> SimState)? = null): SimState {
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
            vars["collision_other"]    = collisionTarget
            vars["collision_name"]     = collisionTarget
            vars["collision_x"]        = other?.x?.let { "%.1f".format(it) } ?: "0"
            vars["collision_y"]        = other?.y?.let { "%.1f".format(it) } ?: "0"
            vars["collision_width"]    = other?.width?.let { "%.1f".format(it) } ?: "0"
            vars["collision_height"]   = other?.height?.let { "%.1f".format(it) } ?: "0"
            vars["collision_rotation"] = other?.rotation?.let { "%.1f".format(it) } ?: "0"
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
        val cameraRef: Array<SimCamera?> = arrayOf(currentState.camera)
        val sceneSwitchRef: Array<String?> = arrayOf(null)

        // Отслеживаем дифф: что скрипт явно удалил
        val deletedObjects = mutableSetOf<String>()
        val deletedJoysticks = mutableSetOf<String>()

        log += if (collisionTarget.isNotBlank()) "Коллизия -> «${script.name}» (с «$collisionTarget»)" else "Касание -> «${script.name}»"
        val continued = runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors, allowDelay = true,
            physicsEnabledRef = { physicsEnabled }, setPhysicsEnabled = { physicsEnabled = it },
            cameraRef = cameraRef, sceneSwitchRef = sceneSwitchRef,
            getLatestState = getLatestState,
            deletedObjects = deletedObjects, deletedJoysticks = deletedJoysticks,
            onUpdate = if (onUpdate != null) {
                { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList(), physicsEnabled = physicsEnabled, camera = cameraRef[0], sprites = currentState.sprites, projectId = currentState.projectId)) }
            } else null
        )
        globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        // Берём свежее живое состояние как базу
        val baseState = getLatestState?.invoke() ?: currentState

        // Применяем дифф скрипта поверх живого состояния:
        val mergedObjects = baseState.objects.toMutableMap()
        // 1. Удаляем то что скрипт явно удалил
        deletedObjects.forEach { mergedObjects.remove(it) }
        // 2. Применяем изменения/создания скрипта
        objects.forEach { (name, scriptObj) ->
            val liveObj = mergedObjects[name]
            if (liveObj != null) {
                // Объект существовал — переносим только то что скрипт мог изменить (не позицию/физику)
                mergedObjects[name] = liveObj.copy(
                    visible = scriptObj.visible,
                    label = scriptObj.label,
                    color = scriptObj.color,
                    tags = scriptObj.tags,
                    spriteName = scriptObj.spriteName,
                    spriteAlpha = scriptObj.spriteAlpha,
                    zOrder = scriptObj.zOrder,
                    collisionIgnore = scriptObj.collisionIgnore,
                    width = scriptObj.width,
                    height = scriptObj.height,
                    radius = scriptObj.radius,
                    rotation = scriptObj.rotation,
                    x = scriptObj.x,
                    y = scriptObj.y,
                    physicsBody = scriptObj.physicsBody
                )
            } else if (name !in currentState.objects) {
                // Объект создан скриптом (не существовал в начале) — добавляем
                mergedObjects[name] = scriptObj
            }
            // Если объект был в currentState но не в baseState (удалён другим скриптом) — не восстанавливаем
        }

        // Аналогично для джойстиков
        val mergedJoysticks = baseState.joysticks.toMutableMap()
        deletedJoysticks.forEach { mergedJoysticks.remove(it) }
        joysticks.forEach { (name, scriptJoy) ->
            val liveJoy = mergedJoysticks[name]
            if (liveJoy != null) {
                mergedJoysticks[name] = liveJoy.copy(visible = scriptJoy.visible)
            } else if (name !in currentState.joysticks) {
                mergedJoysticks[name] = scriptJoy
            }
        }

        bindEventScripts(scripts, mergedObjects, errors, warnMissing = false)

        return baseState.copy(
            objects = mergedObjects,
            joysticks = mergedJoysticks,
            globalVars = globalVars,
            tables = allTables.mapValues { it.value.toMap() },
            log = log, errors = errors, isStopped = !continued, physicsEnabled = physicsEnabled,
            camera = if (cameraRef[0] != currentState.camera) cameraRef[0] else baseState.camera,
            pendingSceneSwitch = sceneSwitchRef[0]
        )
    }

    /**
     * Собирает блоки тела между открывающим блоком (на позиции [openIdx]) и его парным закрывающим.
     * Поддерживает вложенность: если внутри есть ещё открывающие блоки того же типа — ищет соответствующий закрывающий.
     */
    private fun collectBodyBlocks(blocks: List<BlockDef>, openIdx: Int): List<BlockDef> =
        collectBodyBlocksWithRange(blocks, openIdx).first

    /**
     * Возвращает тело и диапазон индексов (openIdx+1..closeIdx включительно) для пропуска.
     */
    private fun collectBodyBlocksWithRange(blocks: List<BlockDef>, openIdx: Int): Pair<List<BlockDef>, Pair<Int, Int>?> {
        val openBlock = blocks[openIdx]
        val openType = openBlock.type
        val closeType = when (openType) {
            "if_open"         -> "if_close"
            "for_loop_open"   -> "for_loop_close"
            "while_loop_open" -> "while_loop_close"
            "wait_open"       -> "wait_close"
            else -> return emptyList<BlockDef>() to null
        }
        // Если есть pairId — ищем по нему (точное совпадение)
        if (openBlock.pairId.isNotBlank()) {
            val closeIdx = blocks.indexOfFirst { it.pairId == openBlock.pairId && it.type == closeType }
            if (closeIdx > openIdx) return blocks.subList(openIdx + 1, closeIdx) to (openIdx + 1 to closeIdx)
        }
        // Fallback: ищем по вложенности
        var depth = 1
        for (i in openIdx + 1 until blocks.size) {
            val t = blocks[i].type
            if (t == openType) depth++
            else if (t == closeType) {
                depth--
                if (depth == 0) return blocks.subList(openIdx + 1, i) to (openIdx + 1 to i)
            }
        }
        return emptyList<BlockDef>() to null
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
        cameraRef: Array<SimCamera?> = arrayOf(null),
        sceneSwitchRef: Array<String?> = arrayOf(null),
        getLatestState: (() -> SimState)? = null,
        deletedObjects: MutableSet<String> = mutableSetOf(),
        deletedJoysticks: MutableSet<String> = mutableSetOf(),
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

        // Индексы блоков которые нужно пропустить (тело open/close блоков)
        val skipIndices = mutableSetOf<Int>()

        for ((idx, block) in blocks.withIndex()) {
            if (idx in skipIndices) continue
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
                "scene_switch" -> {
                    val sceneName = ExprEval.eval(block.params["scene"]?.value ?: "", vars).value.trim()
                    if (sceneName.isBlank()) { errors += "Блок $num «Перейти на сцену»: имя сцены не заполнено"; continue }
                    log += "  Переход на сцену «$sceneName»"
                    sceneSwitchRef[0] = sceneName
                    return false
                }
                "set_texture" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Установить текстуру»: «$nameOrTag» не найден"; continue }
                    val sprite = getStr("sprite")
                    val alpha = getF("alpha", 1f).coerceIn(0f, 1f)
                    val sx = getF("scaleX", 1f)
                    val sy = getF("scaleY", 1f)
                    val cx = getF("cropX", 0f).toInt()
                    val cy = getF("cropY", 0f).toInt()
                    val cw = getF("cropW", 0f).toInt()
                    val ch = getF("cropH", 0f).toInt()
                    targets.forEach { (n, obj) ->
                        // Не применяем текстуру к текстовым объектам
                        if (obj.label.isNotEmpty() && obj.color == androidx.compose.ui.graphics.Color.Transparent) {
                            errors += "Блок $num «Установить текстуру»: нельзя применить к текстовому объекту «$n»"
                            return@forEach
                        }
                        objects[n] = obj.copy(
                            spriteName = sprite.ifBlank { null },
                            spriteAlpha = alpha, spriteScaleX = sx, spriteScaleY = sy,
                            spriteCropX = cx, spriteCropY = cy, spriteCropW = cw, spriteCropH = ch
                        )
                    }
                    log += "  «$nameOrTag» текстура -> «$sprite»"
                }
                "sim_sprite" -> {
                    val name = getStr("name")
                    if (name.isBlank()) { errors += "Блок $num «Создать спрайт-объект»: имя пустое"; continue }
                    val sprite = getStr("sprite")
                    val alpha = getF("alpha", 1f).coerceIn(0f, 1f)
                    val existing = objects[name]
                    // Размер: если 0 — берём из метаданных спрайта
                    val spriteAsset = ExprEval.sprites.find { it.name == sprite }
                    val rawW = getF("width", 0f)
                    val rawH = getF("height", 0f)
                    val w = if (rawW > 0f) rawW else (spriteAsset?.width?.toFloat() ?: 100f)
                    val h = if (rawH > 0f) rawH else (spriteAsset?.height?.toFloat() ?: 100f)
                    objects[name] = SimObject(
                        name = name, x = getF("x"), y = getF("y"),
                        width = w.coerceAtLeast(1f), height = h.coerceAtLeast(1f),
                        radius = 0f, color = androidx.compose.ui.graphics.Color.Transparent,
                        spriteName = sprite.ifBlank { null }, spriteAlpha = alpha,
                        tapScriptId = existing?.tapScriptId,
                        holdScriptId = existing?.holdScriptId,
                        collisionScriptId = existing?.collisionScriptId,
                        collisionEndScriptId = existing?.collisionEndScriptId,
                        physicsBody = existing?.physicsBody,
                        hitbox = existing?.hitbox ?: Hitbox()
                    )
                    log += "  ${if (existing != null) "Обновлён" else "Создан"} спрайт «$name» (${getStr("x")}, ${getStr("y")}) ${w.toInt()}x${h.toInt()}"
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
                        if (!runScript(branchBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                    }
                }
                // ── if_open / else_block / if_close ──────────────────────────────
                "if_open" -> {
                    val left  = block.params["left"]?.value ?: ""
                    val op    = block.params["op"]?.value ?: "=="
                    val right = block.params["right"]?.value ?: "0"
                    val (result, err) = ExprEval.evalCondition(left, op, right, vars)
                    if (err != null) { errors += "Блок $num «Условие»: $err"; continue }
                    val leftVal = ExprEval.eval(left, vars).value
                    val rightVal = ExprEval.eval(right, vars).value
                    log += "  Условие: $leftVal $op $rightVal → ${if (result) "истина" else "ложь"}"

                    // Собираем тело: от if_open до if_close, разбиваем по else_block
                    val (allBody, bodyRange) = collectBodyBlocksWithRange(blocks, idx)
                    bodyRange?.let { skipIndices.addAll(it.first..it.second) }

                    // Ищем else_block внутри тела (по pairId или по типу)
                    val elseIdx = if (block.pairId.isNotBlank()) {
                        allBody.indexOfFirst { it.pairId == block.pairId && it.type == "else_block" }
                    } else {
                        allBody.indexOfFirst { it.type == "else_block" }
                    }

                    val bodyToRun = if (result) {
                        if (elseIdx >= 0) allBody.subList(0, elseIdx) else allBody
                    } else {
                        if (elseIdx >= 0) allBody.subList(elseIdx + 1, allBody.size) else emptyList()
                    }
                    if (bodyToRun.isNotEmpty()) {
                        if (!runScript(bodyToRun, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                    }
                }
                "else_block", "if_close" -> { /* пропуск — обрабатывается if_open */ }
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
                        // Физику НЕ переносим при пересоздании — иначе новый объект
                        // унаследует скорость старого (баг «пуля тянет игрока»)
                        physicsBody = null,
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
                    onUpdate?.invoke()
                }
                "sim_resize" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Размер»: «$nameOrTag» не найден"; continue }
                    val w = getF("width", 100f).coerceAtLeast(1f)
                    val h = getF("height", 60f).coerceAtLeast(1f)
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(width = w, height = h) }
                    log += "  «$nameOrTag» размер ${getStr("width")}x${getStr("height")}"
                    onUpdate?.invoke()
                }
                "sim_color" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Цвет»: «$nameOrTag» не найден"; continue }
                    val color = parseColor(getStr("color", "#4F8EF7"))
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(color = color) }
                    log += "  «$nameOrTag» цвет -> ${getStr("color")}"
                    onUpdate?.invoke()
                }
                "sim_update_text" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Обновить текст»: «$nameOrTag» не найден"; continue }
                    val text = getStr("text")
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(label = text) }
                    log += "  «$nameOrTag» текст обновлён: «$text»"
                    onUpdate?.invoke()
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
                    val joy = joysticks[nameOrTag]
                    if (targets.isEmpty() && joy == null) { errors += "Блок $num «Скрыть»: «$nameOrTag» не найден"; continue }
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(visible = false) }
                    if (joy != null) joysticks[nameOrTag] = joy.copy(visible = false)
                    log += "  «$nameOrTag» скрыт"
                    onUpdate?.invoke()
                }
                "sim_show" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    val joy = joysticks[nameOrTag]
                    if (targets.isEmpty() && joy == null) { errors += "Блок $num «Показать»: «$nameOrTag» не найден"; continue }
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(visible = true) }
                    if (joy != null) joysticks[nameOrTag] = joy.copy(visible = true)
                    log += "  «$nameOrTag» показан"
                    onUpdate?.invoke()
                }
                "for_loop" -> {
                    val count = getF("count").toInt().coerceAtLeast(0)
                    val bodyBlocks = block.children["body"] ?: emptyList()
                    log += "  Цикл: $count раз"
                    repeat(count) { i ->
                        vars["i"] = i.toString()
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
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
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                    }
                    if (iterations >= 1000) errors += "Блок $num «Цикл пока»: превышен лимит итераций (1000)"
                }
                "wait" -> {
                    val seconds = getF("seconds", 1f).coerceIn(0.016f, 60f)
                    val count = getF("count", 1f).toInt().let { if (it <= 0) Int.MAX_VALUE else it }
                    val bodyBlocks = block.children["body"] ?: emptyList()
                    log += "  Таймер: ${seconds}с × $count"
                    if (allowDelay) {
                        repeat(count) {
                            delay((seconds * 1000).toLong())
                            // После паузы полностью синхронизируем объекты из живого состояния.
                            // Это гарантирует что таймер не восстанавливает объекты удалённые другими скриптами.
                            getLatestState?.invoke()?.let { live ->
                                objects.clear(); objects.putAll(live.objects)
                                joysticks.clear(); joysticks.putAll(live.joysticks)
                                // Сбрасываем deletedObjects — они уже применены в живом состоянии
                                deletedObjects.clear(); deletedJoysticks.clear()
                                ExprEval.objects = objects
                                ExprEval.joysticks = joysticks
                            }
                            if (bodyBlocks.isNotEmpty()) {
                                if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                            }
                            onUpdate?.invoke()
                        }
                    }
                }
                // ── Open/Close блоки — тело между открывающим и закрывающим ──────
                "for_loop_open" -> {
                    val count = getF("count").toInt().coerceAtLeast(0)
                    val (bodyBlocks, bodyRange) = collectBodyBlocksWithRange(blocks, idx)
                    bodyRange?.let { skipIndices.addAll(it.first..it.second) }
                    log += "  Цикл (open/close): $count раз"
                    repeat(count) { i ->
                        vars["i"] = i.toString()
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                    }
                    vars.remove("i")
                }
                "while_loop_open" -> {
                    val left = block.params["left"]?.value ?: ""
                    val op = block.params["op"]?.value ?: "<="
                    val right = block.params["right"]?.value ?: "10"
                    val (bodyBlocks, bodyRange) = collectBodyBlocksWithRange(blocks, idx)
                    bodyRange?.let { skipIndices.addAll(it.first..it.second) }
                    log += "  Цикл пока (open/close): $left $op $right"
                    var iterations = 0
                    while (iterations < 1000) {
                        val (result, err) = ExprEval.evalCondition(left, op, right, vars)
                        if (err != null) { errors += "Блок $num «Цикл пока»: $err"; break }
                        if (!result) break
                        iterations++
                        if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                    }
                    if (iterations >= 1000) errors += "Блок $num «Цикл пока»: превышен лимит итераций (1000)"
                }
                "wait_open" -> {
                    val seconds = getF("seconds", 1f).coerceIn(0.016f, 60f)
                    val count = getF("count", 1f).toInt().let { if (it <= 0) Int.MAX_VALUE else it }
                    val (bodyBlocks, bodyRange) = collectBodyBlocksWithRange(blocks, idx)
                    bodyRange?.let { skipIndices.addAll(it.first..it.second) }
                    log += "  Таймер (open/close): ${seconds}с × $count"
                    if (allowDelay) {
                        repeat(count) {
                            delay((seconds * 1000).toLong())
                            getLatestState?.invoke()?.let { live ->
                                objects.clear(); objects.putAll(live.objects)
                                joysticks.clear(); joysticks.putAll(live.joysticks)
                                deletedObjects.clear(); deletedJoysticks.clear()
                                ExprEval.objects = objects
                                ExprEval.joysticks = joysticks
                            }
                            if (bodyBlocks.isNotEmpty()) {
                                if (!runScript(bodyBlocks, vars, objects, joysticks, tables, log, errors, allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef, getLatestState, deletedObjects, deletedJoysticks, onUpdate)) return false
                            }
                            onUpdate?.invoke()
                        }
                    }
                }
                // Закрывающие блоки — пропускаем (тело уже обработано открывающим)
                "for_loop_close", "while_loop_close", "wait_close" -> { /* пропуск */ }
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
                    onUpdate?.invoke()
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
                    val props = block.children["props"] ?: emptyList()

                    // Камера
                    val cam = cameraRef[0]
                    if (cam != null && cam.name == nameOrTag) {
                        var modified: SimCamera = cam
                        props.forEach { prop ->
                            val propName = prop.params["prop"]?.value ?: return@forEach
                            val propValue = prop.params["value"]?.value ?: return@forEach
                            val resolved = ExprEval.eval(propValue, vars).value
                            modified = when (propName) {
                                "target"    -> modified.copy(targetName = resolved)
                                "smoothing" -> modified.copy(smoothing = (resolved.toFloatOrNull() ?: modified.smoothing).coerceIn(0.01f, 1f))
                                "enabled"   -> modified.copy(enabled = resolved == "true")
                                else -> modified
                            }
                        }
                        cameraRef[0] = modified
                        log += "  Камера «$nameOrTag» свойства изменены"
                        continue
                    }

                    if (targets.isEmpty()) { errors += "Блок $num «Изменить свойства»: «$nameOrTag» не найден"; continue }
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
                                // Спрайт-свойства
                                "sprite" -> modified.copy(spriteName = resolved.ifBlank { null })
                                "spriteAlpha" -> modified.copy(spriteAlpha = (resolved.toFloatOrNull() ?: modified.spriteAlpha).coerceIn(0f, 1f))
                                "spriteScaleX" -> modified.copy(spriteScaleX = resolved.toFloatOrNull() ?: modified.spriteScaleX)
                                "spriteScaleY" -> modified.copy(spriteScaleY = resolved.toFloatOrNull() ?: modified.spriteScaleY)
                                "zOrder", "layer" -> modified.copy(zOrder = resolved.toIntOrNull() ?: modified.zOrder)
                                "collisionIgnore" -> modified.copy(collisionIgnore = resolved.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet())
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
                    val hasJoy = joysticks.containsKey(nameOrTag)
                    if (targets.isEmpty() && !hasJoy) { errors += "Блок $num «Удалить объект»: «$nameOrTag» не найден"; continue }
                    targets.forEach { (name, _) -> objects.remove(name); deletedObjects += name }
                    if (hasJoy) { joysticks.remove(nameOrTag); deletedJoysticks += nameOrTag }
                    log += "  Удалён «$nameOrTag»"
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
                "physics_impulse" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Импульс»: «$nameOrTag» не найден"; continue }
                    val dvx = getF("vx", 0f)
                    val dvy = getF("vy", 0f)
                    targets.forEach { (name, obj) ->
                        val body = obj.physicsBody
                        if (body == null) { errors += "Блок $num «Импульс»: у «$name» нет физики"; return@forEach }
                        objects[name] = obj.copy(physicsBody = body.copy(
                            velocityX = body.velocityX + dvx,
                            velocityY = body.velocityY + dvy
                        ))
                    }
                    log += "  «$nameOrTag» импульс (+$dvx, +$dvy)"
                }
                "physics_move" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Физическое движение»: «$nameOrTag» не найден"; continue }
                    val speed = getF("speed", 0f)
                    val turn = getF("turn", 0f)
                    val friction = getF("friction", 0.9f).coerceIn(0f, 1f)
                    targets.forEach { (name, obj) ->
                        val body = obj.physicsBody
                        if (body == null) { errors += "Блок $num «Физическое движение»: у «$name» нет физики"; return@forEach }
                        // Поворачиваем объект
                        val newRot = (obj.rotation + turn) % 360f
                        // Вычисляем вектор направления по новому углу
                        val rad = Math.toRadians(newRot.toDouble())
                        val dirX = kotlin.math.sin(rad).toFloat()
                        val dirY = kotlin.math.cos(rad).toFloat()
                        // Применяем скорость вперёд + трение
                        val newVx = (body.velocityX + dirX * speed) * friction
                        val newVy = (body.velocityY + dirY * speed) * friction
                        objects[name] = obj.copy(
                            rotation = newRot,
                            physicsBody = body.copy(velocityX = newVx, velocityY = newVy)
                        )
                    }
                    log += "  «$nameOrTag» физ.движение speed=$speed turn=$turn"
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
                "sim_layer" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Слой объекта»: «$nameOrTag» не найден"; continue }
                    val layer = getF("layer", 0f).toInt()
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(zOrder = layer) }
                    log += "  «$nameOrTag» слой -> $layer"
                }
                "sim_no_collision" -> {
                    val nameOrTag = getStr("name")
                    val targets = getObjectsByNameOrTag(nameOrTag)
                    if (targets.isEmpty()) { errors += "Блок $num «Игнорировать коллизию»: «$nameOrTag» не найден"; continue }
                    val ignore = getStr("ignore").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    targets.forEach { (name, obj) -> objects[name] = obj.copy(collisionIgnore = obj.collisionIgnore + ignore) }
                    log += "  «$nameOrTag» игнорирует коллизии с: ${ignore.joinToString()}"
                }
                "physics_toggle" -> {
                    val enabled = getStr("enabled", "true") == "true"
                    setPhysicsEnabled(enabled)
                    log += "  Физика: ${if (enabled) "включена" else "выключена"}"
                }
                "sim_camera" -> {
                    val camName = getStr("name")
                    if (camName.isBlank()) { errors += "Блок $num «Создать камеру»: имя пустое"; continue }
                    // Проверяем что нет другой активной камеры
                    val existing = cameraRef[0]
                    if (existing != null && existing.name != camName && existing.enabled) {
                        errors += "Блок $num «Создать камеру»: камера «${existing.name}» уже активна — нельзя использовать две камеры одновременно"
                        continue
                    }
                    val target = getStr("target")
                    val smoothing = getF("smoothing", 0.1f).coerceIn(0.01f, 1f)
                    val uiTagsRaw = getStr("ui_tags")
                    val uiTags = uiTagsRaw.split(",").map { it.trim().trimStart('#') }.filter { it.isNotBlank() }.toSet()
                    val enabled = getStr("enabled", "true") == "true"
                    cameraRef[0] = SimCamera(name = camName, enabled = enabled, targetName = target,
                        smoothing = smoothing, uiTags = uiTags)
                    log += "  Камера «$camName» создана, следит за «$target»"
                }
                "camera_toggle" -> {
                    val camName = getStr("name")
                    val enabled = getStr("enabled", "true") == "true"
                    val cam = cameraRef[0]
                    if (cam == null || cam.name != camName) { errors += "Блок $num «Переключить камеру»: камера «$camName» не найдена"; continue }
                    cameraRef[0] = cam.copy(enabled = enabled)
                    log += "  Камера «$camName»: ${if (enabled) "включена" else "выключена"}"
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
                "save_var" -> {
                    val ctx = appContext
                    if (ctx == null) { errors += "Блок $num «Сохранить переменную»: контекст недоступен"; continue }
                    val key = getStr("key")
                    if (key.isBlank()) { errors += "Блок $num «Сохранить переменную»: ключ не заполнен"; continue }
                    val value = getStr("value")
                    val encrypt = getStr("encrypt") == "true"
                    val toStore = if (encrypt) {
                        val cipherKey = SaveCrypto.getKey(ctx, projectName)
                        if (cipherKey == null) { errors += "Блок $num «Сохранить переменную»: ключ шифрования не задан — добавь его в Настройки → Хранилище ключей"; continue }
                        SaveCrypto.encrypt(value, cipherKey)
                    } else value
                    ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE).edit().putString(key, toStore).apply()
                    log += "  Сохранено: $key"
                }
                "load_var" -> {
                    val ctx = appContext
                    if (ctx == null) { errors += "Блок $num «Загрузить переменную»: контекст недоступен"; continue }
                    val key = getStr("key")
                    if (key.isBlank()) { errors += "Блок $num «Загрузить переменную»: ключ не заполнен"; continue }
                    val varName = block.params["var"]?.value?.trim() ?: ""
                    if (varName.isBlank()) { errors += "Блок $num «Загрузить переменную»: имя переменной не заполнено"; continue }
                    val default = getStr("default")
                    val encrypt = getStr("encrypt") == "true"
                    val prefs = ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE)
                    val raw = prefs.getString(key, null)
                    val value = if (raw == null) {
                        default
                    } else if (encrypt) {
                        val cipherKey = SaveCrypto.getKey(ctx, projectName)
                        if (cipherKey == null) { errors += "Блок $num «Загрузить переменную»: ключ шифрования не задан — добавь его в Настройки → Хранилище ключей"; continue }
                        SaveCrypto.decrypt(raw, cipherKey) ?: run {
                            errors += "Блок $num «Загрузить переменную»: не удалось расшифровать «$key» — неверный ключ?"
                            continue
                        }
                    } else raw
                    vars[varName] = value
                    log += "  Загружено: $varName = «$value»"
                }
                "save_table" -> {
                    val ctx = appContext
                    if (ctx == null) { errors += "Блок $num «Сохранить таблицу»: контекст недоступен"; continue }
                    val key = getStr("key")
                    if (key.isBlank()) { errors += "Блок $num «Сохранить таблицу»: ключ не заполнен"; continue }
                    val tableName = block.params["table"]?.value?.trim() ?: ""
                    if (tableName.isBlank()) { errors += "Блок $num «Сохранить таблицу»: имя таблицы не заполнено"; continue }
                    val encrypt = getStr("encrypt") == "true"
                    val tbl = tables[tableName] ?: emptyMap<String, String>()
                    val json = tbl.entries.joinToString("|") { "${it.key}=${it.value}" }
                    val toStore = if (encrypt) {
                        val cipherKey = SaveCrypto.getKey(ctx, projectName)
                        if (cipherKey == null) { errors += "Блок $num «Сохранить таблицу»: ключ шифрования не задан — добавь его в Настройки → Хранилище ключей"; continue }
                        SaveCrypto.encrypt(json, cipherKey)
                    } else json
                    ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE).edit().putString("__table__$key", toStore).apply()
                    log += "  Таблица «$tableName» сохранена как «$key» (${tbl.size} записей)"
                }
                "load_table" -> {
                    val ctx = appContext
                    if (ctx == null) { errors += "Блок $num «Загрузить таблицу»: контекст недоступен"; continue }
                    val key = getStr("key")
                    if (key.isBlank()) { errors += "Блок $num «Загрузить таблицу»: ключ не заполнен"; continue }
                    val tableName = block.params["table"]?.value?.trim() ?: ""
                    if (tableName.isBlank()) { errors += "Блок $num «Загрузить таблицу»: имя таблицы не заполнено"; continue }
                    val encrypt = getStr("encrypt") == "true"
                    val prefs = ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE)
                    val raw = prefs.getString("__table__$key", null)
                    if (raw != null) {
                        val json = if (encrypt) {
                            val cipherKey = SaveCrypto.getKey(ctx, projectName)
                            if (cipherKey == null) { errors += "Блок $num «Загрузить таблицу»: ключ шифрования не задан — добавь его в Настройки → Хранилище ключей"; continue }
                            SaveCrypto.decrypt(raw, cipherKey) ?: run {
                                errors += "Блок $num «Загрузить таблицу»: не удалось расшифровать «$key» — неверный ключ?"
                                continue
                            }
                        } else raw
                        val loaded = json.split("|").mapNotNull {
                            val eq = it.indexOf('='); if (eq == -1) null else it.substring(0, eq) to it.substring(eq + 1)
                        }.toMap().toMutableMap()
                        tables[tableName] = loaded
                        ExprEval.tables = tables.mapValues { it.value.toMap() }
                        log += "  Таблица «$tableName» загружена из «$key» (${loaded.size} записей)"
                    } else {
                        log += "  Таблица «$key» не найдена в памяти — «$tableName» не изменена"
                    }
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
