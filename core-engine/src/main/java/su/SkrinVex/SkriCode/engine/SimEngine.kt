package su.SkrinVex.SkriCode.engine

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.data.ProjectTable
import su.SkrinVex.SkriCode.data.ProjectVar
import su.SkrinVex.SkriCode.data.Script
import su.SkrinVex.SkriCode.data.ScriptEvent
import su.SkrinVex.SkriCode.data.SpriteAsset
import su.SkrinVex.SkriCode.data.deserialize
import su.SkrinVex.SkriCode.engine.ast.AstExpr
import su.SkrinVex.SkriCode.engine.ast.ExprCompiler
import su.SkrinVex.SkriCode.engine.compiler.BlockCompiler
import su.SkrinVex.SkriCode.engine.compiler.CompiledBlock

object SimEngine {

    var appContext: Context? = null
    var projectName: String = ""
    var soundManager: SoundManager? = null

    suspend fun run(
        scripts: List<Script>,
        globalVarDefs: List<ProjectVar>,
        globalTableDefs: List<ProjectTable> = emptyList(),
        locationBlocks: List<su.SkrinVex.SkriCode.data.SerializedBlock> = emptyList(),
        sprites: List<SpriteAsset> = emptyList(),
        projectId: String = "",
        backgroundScope: kotlinx.coroutines.CoroutineScope? = null,
        onUpdate: (SimState) -> Unit = {}
    ): SimState {
        val objects = mutableMapOf<String, SimObject>()
        val joysticks = mutableMapOf<String, JoystickState>()
        val log = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val globalVars = globalVarDefs.associate { it.name to it.value }.toMutableMap()
        val globalTables = globalTableDefs.associate { it.name to it.entries.toMutableMap() }
            .mapValues { it.value.toMutableMap() }.toMutableMap()
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
                    x = ExprCompiler.compile(p("x").ifBlank { "0" }).evalFloat(emptyMap(), ExprEval.fallbackScope),
                    y = ExprCompiler.compile(p("y").ifBlank { "0" }).evalFloat(emptyMap(), ExprEval.fallbackScope),
                    width = pf("width", 100f).coerceAtLeast(1f),
                    height = pf("height", 60f).coerceAtLeast(1f),
                    radius = pf("radius", 8f).coerceAtLeast(0f),
                    color = parseColor(p("color").ifBlank { "#4F8EF7" }),
                    tags = tags, physicsBody = physicsBody, hitbox = hitbox, zOrder = zOrder
                )
                "sim_text" -> objects[name] = SimObject(
                    name = name,
                    x = ExprCompiler.compile(p("x").ifBlank { "0" }).evalFloat(emptyMap(), ExprEval.fallbackScope),
                    y = ExprCompiler.compile(p("y").ifBlank { "0" }).evalFloat(emptyMap(), ExprEval.fallbackScope),
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
                        x = ExprCompiler.compile(p("x").ifBlank { "0" }).evalFloat(emptyMap(), ExprEval.fallbackScope),
                        y = ExprCompiler.compile(p("y").ifBlank { "0" }).evalFloat(emptyMap(), ExprEval.fallbackScope),
                        width = w.coerceAtLeast(1f), height = h.coerceAtLeast(1f),
                        radius = 0f, color = Color.Transparent,
                        spriteName = p("sprite").ifBlank { null },
                        spriteAlpha = pf("alpha", 1f).coerceIn(0f, 1f),
                        tags = tags, physicsBody = physicsBody, hitbox = hitbox, zOrder = zOrder
                    )
                }
            }
            // Выполняем setup-блоки объекта
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
        val onStartScripts = scripts.filter { it.event == ScriptEvent.ON_START }

        fun launchScript(scope: kotlinx.coroutines.CoroutineScope, script: Script) {
            scope.launch {
                if (sceneSwitchRef[0] != null) return@launch
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
        }

        if (backgroundScope != null) {
            onStartScripts.forEach { launchScript(backgroundScope, it) }
        } else {
            coroutineScope {
                onStartScripts.forEach { launchScript(this, it) }
            }
        }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        return SimState(objects = objects, joysticks = joysticks, globalVars = globalVars,
            tables = globalTables.mapValues { it.value.toMap() }, log = log, errors = errors, isStopped = false,
            physicsEnabled = physicsEnabled, camera = cameraRef[0], pendingSceneSwitch = sceneSwitchRef[0],
            sprites = sprites, projectId = projectId)
    }

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

    fun physicsTick(state: SimState): Triple<SimState, Set<Pair<String,String>>, Set<Pair<String,String>>> =
        PhysicsWorld.tick(state)

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

    private suspend fun runScriptOnState(
        script: Script,
        scripts: List<Script>,
        currentState: SimState,
        onUpdate: ((SimState) -> Unit)? = null,
        collisionTarget: String = "",
        collisionSelf: String = "",
        getLatestState: (() -> SimState)? = null
    ): SimState {
        val objects = currentState.objects.toMutableMap()
        val joysticks = currentState.joysticks.toMutableMap()
        val log = currentState.log.toMutableList()
        val errors = currentState.errors.toMutableList()
        val globalVars = currentState.globalVars.toMutableMap()
        val localVars = script.localVars.orEmpty().associate { it.name to it.value }.toMutableMap()
        val vars = (globalVars + localVars).toMutableMap()

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
        val allTables = ((getLatestState?.invoke() ?: currentState).tables.mapValues { it.value.toMutableMap() } + localTables).toMutableMap<String, MutableMap<String, String>>()
        var physicsEnabled = currentState.physicsEnabled
        val cameraRef: Array<SimCamera?> = arrayOf(currentState.camera)
        val sceneSwitchRef: Array<String?> = arrayOf(null)

        val deletedObjects = mutableSetOf<String>()
        val deletedJoysticks = mutableSetOf<String>()
        val modifiedFields = mutableMapOf<String, MutableSet<String>>()

        log += if (collisionTarget.isNotBlank()) "Коллизия -> «${script.name}» (с «$collisionTarget»)" else "Касание -> «${script.name}»"
        val continued = runScript(script.blocks.mapNotNull { it.deserialize() }, vars, objects, joysticks, allTables, log, errors, allowDelay = true,
            physicsEnabledRef = { physicsEnabled }, setPhysicsEnabled = { physicsEnabled = it },
            cameraRef = cameraRef, sceneSwitchRef = sceneSwitchRef,
            getLatestState = getLatestState,
            deletedObjects = deletedObjects, deletedJoysticks = deletedJoysticks,
            modifiedFields = modifiedFields,
            onUpdate = if (onUpdate != null) {
                { onUpdate(SimState(objects.toMap(), joysticks.toMap(), globalVars.toMap(), allTables.mapValues { it.value.toMap() }, log.toList(), errors.toList(), physicsEnabled = physicsEnabled, camera = cameraRef[0], sprites = currentState.sprites, projectId = currentState.projectId)) }
            } else null
        )
        globalVars.keys.forEach { k -> vars[k]?.let { globalVars[k] = it } }

        bindEventScripts(scripts, objects, errors, warnMissing = false)

        val baseState = getLatestState?.invoke() ?: currentState

        val mergedObjects = baseState.objects.toMutableMap()
        deletedObjects.forEach { mergedObjects.remove(it) }
        objects.forEach { (name, scriptObj) ->
            val liveObj = mergedObjects[name]
            if (liveObj != null) {
                val fields = modifiedFields[name] ?: emptySet()
                if (fields.isNotEmpty()) {
                    mergedObjects[name] = liveObj.copy(
                        visible = if ("visible" in fields) scriptObj.visible else liveObj.visible,
                        label = if ("label" in fields) scriptObj.label else liveObj.label,
                        fontSize = if ("fontSize" in fields) scriptObj.fontSize else liveObj.fontSize,
                        bold = if ("bold" in fields) scriptObj.bold else liveObj.bold,
                        textColor = if ("textColor" in fields) scriptObj.textColor else liveObj.textColor,
                        color = if ("color" in fields) scriptObj.color else liveObj.color,
                        tags = if ("tags" in fields) scriptObj.tags else liveObj.tags,
                        spriteName = if ("sprite" in fields) scriptObj.spriteName else liveObj.spriteName,
                        spriteAlpha = if ("spriteAlpha" in fields || "sprite" in fields) scriptObj.spriteAlpha else liveObj.spriteAlpha,
                        spriteScaleX = if ("spriteScaleX" in fields || "sprite" in fields) scriptObj.spriteScaleX else liveObj.spriteScaleX,
                        spriteScaleY = if ("spriteScaleY" in fields || "sprite" in fields) scriptObj.spriteScaleY else liveObj.spriteScaleY,
                        spriteCropX = if ("spriteCrop" in fields || "cropX" in fields) scriptObj.spriteCropX else liveObj.spriteCropX,
                        spriteCropY = if ("spriteCrop" in fields || "cropY" in fields) scriptObj.spriteCropY else liveObj.spriteCropY,
                        spriteCropW = if ("spriteCrop" in fields || "cropW" in fields) scriptObj.spriteCropW else liveObj.spriteCropW,
                        spriteCropH = if ("spriteCrop" in fields || "cropH" in fields) scriptObj.spriteCropH else liveObj.spriteCropH,
                        zOrder = if ("zOrder" in fields || "layer" in fields) scriptObj.zOrder else liveObj.zOrder,
                        collisionIgnore = if ("collisionIgnore" in fields) scriptObj.collisionIgnore else liveObj.collisionIgnore,
                        width = if ("width" in fields) scriptObj.width else liveObj.width,
                        height = if ("height" in fields) scriptObj.height else liveObj.height,
                        radius = if ("radius" in fields) scriptObj.radius else liveObj.radius,
                        rotation = if ("rotation" in fields) scriptObj.rotation else liveObj.rotation,
                        x = if ("x" in fields) scriptObj.x else liveObj.x,
                        y = if ("y" in fields) scriptObj.y else liveObj.y,
                        physicsBody = if ("physicsBody" in fields || fields.any { it.startsWith("physics_") }) scriptObj.physicsBody else liveObj.physicsBody,
                        hitbox = if ("hitbox" in fields) scriptObj.hitbox else liveObj.hitbox
                    )
                }
            } else if (name !in currentState.objects) {
                mergedObjects[name] = scriptObj
            }
        }

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
        modifiedFields: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        evalScopeIn: ExprScope? = null,
        onUpdate: (() -> Unit)? = null
    ): Boolean {
        val evalScope = evalScopeIn ?: ExprScope(objects, joysticks, tables)
        val compiled = BlockCompiler.compile(blocks)
        return executeCompiled(
            compiled, vars, objects, joysticks, tables, log, errors,
            allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef,
            getLatestState, deletedObjects, deletedJoysticks, modifiedFields, evalScope, onUpdate
        )
    }

    private suspend fun executeCompiled(
        instructions: List<CompiledBlock>,
        vars: MutableMap<String, String>,
        objects: MutableMap<String, SimObject>,
        joysticks: MutableMap<String, JoystickState>,
        tables: MutableMap<String, MutableMap<String, String>>,
        log: MutableList<String>,
        errors: MutableList<String>,
        allowDelay: Boolean,
        physicsEnabledRef: () -> Boolean,
        setPhysicsEnabled: (Boolean) -> Unit,
        cameraRef: Array<SimCamera?>,
        sceneSwitchRef: Array<String?>,
        getLatestState: (() -> SimState)?,
        deletedObjects: MutableSet<String>,
        deletedJoysticks: MutableSet<String>,
        modifiedFields: MutableMap<String, MutableSet<String>>,
        evalScope: ExprScope,
        onUpdate: (() -> Unit)?
    ): Boolean {
        fun getObjectsByNameOrTag(nameOrTag: String): List<Pair<String, SimObject>> {
            return if (nameOrTag.startsWith("#")) {
                val tag = nameOrTag.substring(1)
                objects.filter { (_, obj) -> tag in obj.tags }.toList()
            } else {
                val obj = objects[nameOrTag]
                if (obj != null) listOf(nameOrTag to obj) else emptyList()
            }
        }

        val loopCounters = mutableMapOf<String, Int>()
        var pc = 0

        while (pc < instructions.size) {
            when (val inst = instructions[pc]) {
                is CompiledBlock.SetVar -> {
                    val value = inst.valueExpr.evalString(vars, evalScope)
                    vars[inst.name] = value
                    log += "  ${inst.name} = $value"
                    pc++
                }

                is CompiledBlock.SetTag -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val tag = inst.tagExpr.evalString(vars, evalScope)
                    val obj = objects[target]
                    if (obj != null) {
                        objects[target] = obj.copy(tags = obj.tags + tag)
                        modifiedFields.getOrPut(target) { mutableSetOf() }.add("tags")
                        log += "  Тег #$tag установлен для «$target»"
                    }
                    pc++
                }

                is CompiledBlock.SimCreate -> {
                    val name = inst.nameExpr.evalString(vars, evalScope)
                    if (name.isNotBlank()) {
                        val existing = objects[name]
                        val x = inst.xExpr.evalFloat(vars, evalScope, 0f)
                        val y = inst.yExpr.evalFloat(vars, evalScope, 0f)
                        val w = inst.widthExpr.evalFloat(vars, evalScope, 100f).coerceAtLeast(1f)
                        val h = inst.heightExpr.evalFloat(vars, evalScope, 60f).coerceAtLeast(1f)
                        val r = inst.radiusExpr.evalFloat(vars, evalScope, 8f).coerceAtLeast(0f)
                        val cStr = inst.colorExpr.evalString(vars, evalScope)
                        val col = parseColor(cStr.ifBlank { "#4F8EF7" })

                        objects[name] = SimObject(
                            name = name, x = x, y = y, width = w, height = h, radius = r, color = col,
                            tags = inst.tags, zOrder = inst.zOrder,
                            tapScriptId = existing?.tapScriptId,
                            holdScriptId = existing?.holdScriptId,
                            collisionScriptId = existing?.collisionScriptId,
                            collisionEndScriptId = existing?.collisionEndScriptId,
                            hitbox = existing?.hitbox ?: Hitbox()
                        )
                        modifiedFields.getOrPut(name) { mutableSetOf() }.addAll(setOf("x", "y", "width", "height", "radius", "color", "hitbox"))
                        log += "  Создан/обновлён «$name» ($x, $y) ${w.toInt()}x${h.toInt()}"
                    }
                    pc++
                }

                is CompiledBlock.SimText -> {
                    val name = inst.nameExpr.evalString(vars, evalScope)
                    if (name.isNotBlank()) {
                        val existing = objects[name]
                        val text = inst.textExpr.evalString(vars, evalScope)
                        val x = inst.xExpr.evalFloat(vars, evalScope, 0f)
                        val y = inst.yExpr.evalFloat(vars, evalScope, 0f)
                        val w = inst.widthExpr.evalFloat(vars, evalScope, 200f).coerceAtLeast(1f)
                        val h = inst.heightExpr.evalFloat(vars, evalScope, 40f).coerceAtLeast(1f)
                        val size = inst.sizeExpr.evalFloat(vars, evalScope, 16f).coerceAtLeast(6f)
                        val tc = inst.textColorExpr.evalString(vars, evalScope)
                        val textColor = if (tc.isNotBlank()) parseColor(tc) else null

                        objects[name] = SimObject(
                            name = name, x = x, y = y, width = w, height = h, radius = 0f,
                            color = Color.Transparent, label = text, fontSize = size, bold = inst.bold,
                            textColor = textColor, tags = inst.tags, zOrder = inst.zOrder,
                            tapScriptId = existing?.tapScriptId,
                            holdScriptId = existing?.holdScriptId,
                            collisionScriptId = existing?.collisionScriptId,
                            collisionEndScriptId = existing?.collisionEndScriptId,
                            hitbox = existing?.hitbox ?: Hitbox()
                        )
                        modifiedFields.getOrPut(name) { mutableSetOf() }.addAll(setOf("x", "y", "width", "height", "label", "fontSize", "bold", "textColor", "hitbox"))
                        log += "  Текст «$name»: «$text»"
                    }
                    pc++
                }

                is CompiledBlock.SimSprite -> {
                    val name = inst.nameExpr.evalString(vars, evalScope)
                    if (name.isNotBlank()) {
                        val existing = objects[name]
                        val sprite = inst.spriteExpr.evalString(vars, evalScope)
                        val alpha = inst.alphaExpr.evalFloat(vars, evalScope, 1f).coerceIn(0f, 1f)
                        val spriteAsset = ExprEval.sprites.find { it.name == sprite }
                        val rawW = inst.widthExpr.evalFloat(vars, evalScope, 0f)
                        val rawH = inst.heightExpr.evalFloat(vars, evalScope, 0f)
                        val w = if (rawW > 0f) rawW else (spriteAsset?.width?.toFloat() ?: 100f)
                        val h = if (rawH > 0f) rawH else (spriteAsset?.height?.toFloat() ?: 100f)
                        val x = inst.xExpr.evalFloat(vars, evalScope, 0f)
                        val y = inst.yExpr.evalFloat(vars, evalScope, 0f)

                        objects[name] = SimObject(
                            name = name, x = x, y = y, width = w.coerceAtLeast(1f), height = h.coerceAtLeast(1f),
                            radius = 0f, color = Color.Transparent, spriteName = sprite.ifBlank { null },
                            spriteAlpha = alpha, tags = inst.tags, zOrder = inst.zOrder,
                            tapScriptId = existing?.tapScriptId,
                            holdScriptId = existing?.holdScriptId,
                            collisionScriptId = existing?.collisionScriptId,
                            collisionEndScriptId = existing?.collisionEndScriptId,
                            physicsBody = existing?.physicsBody,
                            hitbox = existing?.hitbox ?: Hitbox()
                        )
                        modifiedFields.getOrPut(name) { mutableSetOf() }.addAll(setOf("x", "y", "width", "height", "sprite", "spriteAlpha", "hitbox"))
                        log += "  Спрайт «$name» ($x, $y) ${w.toInt()}x${h.toInt()}"
                    }
                    pc++
                }

                is CompiledBlock.SimMove -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val mode = inst.mode
                    val dx = inst.xExpr.evalFloat(vars, evalScope, 0f)
                    val dy = inst.yExpr.evalFloat(vars, evalScope, 0f)

                    targets.forEach { (name, obj) ->
                        val nx = if (mode == "step") obj.x + dx else dx
                        val ny = if (mode == "step") obj.y + dy else dy
                        objects[name] = obj.copy(x = nx, y = ny)
                        val mSet = modifiedFields.getOrPut(name) { mutableSetOf() }
                        mSet.add("x")
                        mSet.add("y")
                    }
                    log += "  «$target» move ($mode: $dx, $dy)"
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimResize -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val w = inst.widthExpr.evalFloat(vars, evalScope, 100f).coerceAtLeast(1f)
                    val h = inst.heightExpr.evalFloat(vars, evalScope, 60f).coerceAtLeast(1f)
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(width = w, height = h)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.addAll(setOf("width", "height"))
                    }
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimColor -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val col = parseColor(inst.colorExpr.evalString(vars, evalScope))
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(color = col)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("color")
                    }
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimUpdateText -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val text = inst.textExpr.evalString(vars, evalScope)
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(label = text)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("label")
                    }
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimRotate -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val angle = inst.angleExpr.evalFloat(vars, evalScope, 0f)
                    targets.forEach { (name, obj) ->
                        val nr = if (inst.mode == "step") obj.rotation + angle else angle
                        objects[name] = obj.copy(rotation = nr % 360f)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("rotation")
                    }
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimHide -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(visible = false)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("visible")
                    }
                    joysticks[target]?.let { joysticks[target] = it.copy(visible = false) }
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimShow -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(visible = true)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("visible")
                    }
                    joysticks[target]?.let { joysticks[target] = it.copy(visible = true) }
                    onUpdate?.invoke()
                    pc++
                }

                is CompiledBlock.SimDelete -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    targets.forEach { (name, _) -> objects.remove(name); deletedObjects += name }
                    if (joysticks.containsKey(target)) { joysticks.remove(target); deletedJoysticks += target }
                    pc++
                }

                is CompiledBlock.SimModify -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    targets.forEach { (name, obj) ->
                        var modified = obj
                        val mSet = modifiedFields.getOrPut(name) { mutableSetOf() }
                        for ((propName, propExpr) in inst.props) {
                            mSet.add(propName)
                            val resolved = propExpr.evalString(vars, evalScope)
                            modified = when (propName) {
                                "x" -> modified.copy(x = propExpr.evalFloat(vars, evalScope, modified.x))
                                "y" -> modified.copy(y = propExpr.evalFloat(vars, evalScope, modified.y))
                                "width" -> modified.copy(width = propExpr.evalFloat(vars, evalScope, modified.width).coerceAtLeast(1f))
                                "height" -> modified.copy(height = propExpr.evalFloat(vars, evalScope, modified.height).coerceAtLeast(1f))
                                "radius" -> modified.copy(radius = propExpr.evalFloat(vars, evalScope, modified.radius).coerceAtLeast(0f))
                                "color" -> modified.copy(color = parseColor(resolved))
                                "visible" -> modified.copy(visible = resolved == "true")
                                "rotation" -> modified.copy(rotation = propExpr.evalFloat(vars, evalScope, modified.rotation) % 360f)
                                "label" -> modified.copy(label = resolved)
                                "fontSize" -> modified.copy(fontSize = propExpr.evalFloat(vars, evalScope, modified.fontSize).coerceAtLeast(6f))
                                "bold" -> modified.copy(bold = resolved == "true")
                                "textColor" -> modified.copy(textColor = if (resolved.isNotBlank()) parseColor(resolved) else null)
                                else -> modified
                            }
                        }
                        objects[name] = modified
                    }
                    pc++
                }

                is CompiledBlock.SimPhysics -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val g = inst.gravityExpr.evalFloat(vars, evalScope, -9.8f)
                    val bounce = inst.bouncinessExpr.evalFloat(vars, evalScope, 0f).coerceIn(0f, 1f)
                    val m = inst.massExpr.evalFloat(vars, evalScope, 1f).coerceAtLeast(0.01f)
                    val vx = inst.vxExpr.evalFloat(vars, evalScope, 0f)
                    val vy = inst.vyExpr.evalFloat(vars, evalScope, 0f)
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(physicsBody = PhysicsBody(
                            enabled = true, gravity = g, isStatic = inst.isStatic,
                            bounciness = bounce, mass = m, velocityX = vx, velocityY = vy
                        ))
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("physicsBody")
                    }
                    pc++
                }

                is CompiledBlock.PhysicsImpulse -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val dvx = inst.vxExpr.evalFloat(vars, evalScope, 0f)
                    val dvy = inst.vyExpr.evalFloat(vars, evalScope, 0f)
                    targets.forEach { (name, obj) ->
                        val body = obj.physicsBody ?: return@forEach
                        objects[name] = obj.copy(physicsBody = body.copy(
                            velocityX = body.velocityX + dvx,
                            velocityY = body.velocityY + dvy
                        ))
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("physicsBody")
                    }
                    pc++
                }

                is CompiledBlock.PhysicsMove -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val speed = inst.speedExpr.evalFloat(vars, evalScope, 0f)
                    val turn = inst.turnExpr.evalFloat(vars, evalScope, 0f)
                    val friction = inst.frictionExpr.evalFloat(vars, evalScope, 0.9f).coerceIn(0f, 1f)
                    targets.forEach { (name, obj) ->
                        val body = obj.physicsBody ?: return@forEach
                        val newRot = (obj.rotation + turn) % 360f
                        val rad = Math.toRadians(newRot.toDouble())
                        val dirX = kotlin.math.sin(rad).toFloat()
                        val dirY = kotlin.math.cos(rad).toFloat()
                        val newVx = (body.velocityX + dirX * speed) * friction
                        val newVy = (body.velocityY + dirY * speed) * friction
                        objects[name] = obj.copy(
                            rotation = newRot,
                            physicsBody = body.copy(velocityX = newVx, velocityY = newVy)
                        )
                        modifiedFields.getOrPut(name) { mutableSetOf() }.addAll(setOf("rotation", "physicsBody"))
                    }
                    pc++
                }

                is CompiledBlock.SimHitbox -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val ptsStr = inst.pointsExpr.evalString(vars, evalScope)
                    val hbType = if (inst.type == "manual" && ptsStr.isNotBlank()) HitboxType.MANUAL else HitboxType.AUTO
                    val pts = if (hbType == HitboxType.MANUAL) parseHitboxPoints(ptsStr) else emptyList()
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(hitbox = Hitbox(type = hbType, points = pts))
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("hitbox")
                    }
                    pc++
                }

                is CompiledBlock.SimLayer -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val layer = inst.layerExpr.evalFloat(vars, evalScope, 0f).toInt()
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(zOrder = layer)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("zOrder")
                    }
                    pc++
                }

                is CompiledBlock.SimNoCollision -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val ignore = inst.ignoreExpr.evalString(vars, evalScope).split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    targets.forEach { (name, obj) ->
                        objects[name] = obj.copy(collisionIgnore = obj.collisionIgnore + ignore)
                        modifiedFields.getOrPut(name) { mutableSetOf() }.add("collisionIgnore")
                    }
                    pc++
                }

                is CompiledBlock.PhysicsToggle -> {
                    val en = inst.enabledExpr.evalString(vars, evalScope) == "true"
                    setPhysicsEnabled(en)
                    pc++
                }

                is CompiledBlock.SimJoystick -> {
                    joysticks[inst.name] = JoystickState(
                        name = inst.name,
                        x = inst.xExpr.evalFloat(vars, evalScope, 0f),
                        y = inst.yExpr.evalFloat(vars, evalScope, 0f),
                        baseRadius = inst.baseRadius,
                        knobRadius = inst.knobRadius,
                        baseColor = inst.baseColor,
                        knobColor = inst.knobColor,
                        targetObject = inst.target,
                        speed = inst.speed,
                        directional = inst.directional
                    )
                    pc++
                }

                is CompiledBlock.SetTexture -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val targets = getObjectsByNameOrTag(target)
                    val sprite = inst.spriteExpr.evalString(vars, evalScope)
                    val alpha = inst.alphaExpr.evalFloat(vars, evalScope, 1f).coerceIn(0f, 1f)
                    val sx = inst.scaleXExpr.evalFloat(vars, evalScope, 1f)
                    val sy = inst.scaleYExpr.evalFloat(vars, evalScope, 1f)
                    val cx = inst.cropXExpr.evalFloat(vars, evalScope, 0f).toInt()
                    val cy = inst.cropYExpr.evalFloat(vars, evalScope, 0f).toInt()
                    val cw = inst.cropWExpr.evalFloat(vars, evalScope, 0f).toInt()
                    val ch = inst.cropHExpr.evalFloat(vars, evalScope, 0f).toInt()

                    targets.forEach { (n, obj) ->
                        objects[n] = obj.copy(
                            spriteName = sprite.ifBlank { null },
                            spriteAlpha = alpha, spriteScaleX = sx, spriteScaleY = sy,
                            spriteCropX = cx, spriteCropY = cy, spriteCropW = cw, spriteCropH = ch
                        )
                        modifiedFields.getOrPut(n) { mutableSetOf() }.addAll(setOf("sprite", "spriteAlpha", "spriteScaleX", "spriteScaleY", "spriteCrop"))
                    }
                    pc++
                }

                is CompiledBlock.SimCameraBlock -> {
                    val target = inst.targetExpr.evalString(vars, evalScope)
                    val smoothing = inst.smoothingExpr.evalFloat(vars, evalScope, 0.1f).coerceIn(0.01f, 1f)
                    cameraRef[0] = SimCamera(name = inst.name, enabled = inst.enabled, targetName = target,
                        smoothing = smoothing, uiTags = inst.uiTags)
                    pc++
                }

                is CompiledBlock.CameraToggle -> {
                    val en = inst.enabledExpr.evalString(vars, evalScope) == "true"
                    cameraRef[0]?.let { if (it.name == inst.name) cameraRef[0] = it.copy(enabled = en) }
                    pc++
                }

                is CompiledBlock.TableSet -> {
                    val tName = inst.tableExpr.evalString(vars, evalScope).trim()
                    val key = inst.keyExpr.evalString(vars, evalScope)
                    val value = inst.valueExpr.evalString(vars, evalScope)
                    if (tName.isNotBlank()) {
                        tables.getOrPut(tName) { mutableMapOf() }[key] = value
                        onUpdate?.invoke()
                    }
                    pc++
                }

                is CompiledBlock.TableGet -> {
                    val tName = inst.tableExpr.evalString(vars, evalScope).trim()
                    val key = inst.keyExpr.evalString(vars, evalScope)
                    if (tName.isNotBlank() && inst.varName.isNotBlank()) {
                        val value = tables[tName]?.get(key) ?: ""
                        vars[inst.varName] = value
                    }
                    pc++
                }

                is CompiledBlock.SaveVar -> {
                    val ctx = appContext
                    val key = inst.keyExpr.evalString(vars, evalScope)
                    val value = inst.valueExpr.evalString(vars, evalScope)
                    if (ctx != null && key.isNotBlank()) {
                        val toStore = if (inst.encrypt) {
                            val cipherKey = SaveCrypto.getKey(ctx, projectName)
                            if (cipherKey != null) SaveCrypto.encrypt(value, cipherKey) else value
                        } else value
                        ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE).edit().putString(key, toStore).apply()
                    }
                    pc++
                }

                is CompiledBlock.LoadVar -> {
                    val ctx = appContext
                    val key = inst.keyExpr.evalString(vars, evalScope)
                    if (ctx != null && key.isNotBlank() && inst.varName.isNotBlank()) {
                        val prefs = ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE)
                        val raw = prefs.getString(key, null)
                        val defaultVal = inst.defaultExpr.evalString(vars, evalScope)
                        val value = if (raw == null) {
                            defaultVal
                        } else if (inst.encrypt) {
                            val cipherKey = SaveCrypto.getKey(ctx, projectName)
                            if (cipherKey != null) SaveCrypto.decrypt(raw, cipherKey) ?: defaultVal else raw
                        } else raw
                        vars[inst.varName] = value
                    }
                    pc++
                }

                is CompiledBlock.SaveTable -> {
                    val ctx = appContext
                    val key = inst.keyExpr.evalString(vars, evalScope)
                    val tName = inst.tableExpr.evalString(vars, evalScope).trim()
                    if (ctx != null && key.isNotBlank() && tName.isNotBlank()) {
                        val tbl = tables[tName] ?: emptyMap<String, String>()
                        val json = com.google.gson.Gson().toJson(tbl)
                        val toStore = if (inst.encrypt) {
                            val cipherKey = SaveCrypto.getKey(ctx, projectName)
                            if (cipherKey != null) SaveCrypto.encrypt(json, cipherKey) else json
                        } else json
                        ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE).edit().putString("__table__$key", toStore).apply()
                    }
                    pc++
                }

                is CompiledBlock.LoadTable -> {
                    val ctx = appContext
                    val key = inst.keyExpr.evalString(vars, evalScope)
                    val tName = inst.tableExpr.evalString(vars, evalScope).trim()
                    if (ctx != null && key.isNotBlank() && tName.isNotBlank()) {
                        val prefs = ctx.getSharedPreferences("skripts_saves", Context.MODE_PRIVATE)
                        val raw = prefs.getString("__table__$key", null)
                        if (raw != null) {
                            val json = if (inst.encrypt) {
                                val cipherKey = SaveCrypto.getKey(ctx, projectName)
                                if (cipherKey != null) SaveCrypto.decrypt(raw, cipherKey) ?: "" else raw
                            } else raw
                            val loaded: Map<String, String> = runCatching {
                                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                                com.google.gson.Gson().fromJson<Map<String, String>>(json, type)
                            }.getOrDefault(emptyMap())
                            tables.getOrPut(tName) { mutableMapOf() }.putAll(loaded)
                            onUpdate?.invoke()
                        }
                    }
                    pc++
                }

                is CompiledBlock.SoundPlay -> {
                    val sName = inst.soundExpr.evalString(vars, evalScope)
                    val vol = inst.volumeExpr.evalFloat(vars, evalScope, 1f).coerceIn(0f, 1f)
                    val loop = inst.loopExpr.evalString(vars, evalScope) == "true"
                    val rate = inst.rateExpr.evalFloat(vars, evalScope, 1f).coerceIn(0.5f, 2.0f)
                    if (sName.isNotBlank()) soundManager?.playSound(sName, vol, loop, rate)
                    pc++
                }

                is CompiledBlock.SoundStop -> {
                    val sName = inst.soundExpr.evalString(vars, evalScope)
                    if (sName.isBlank() || sName.equals("all", ignoreCase = true)) soundManager?.stopAllSounds()
                    else soundManager?.stopSound(sName)
                    pc++
                }

                is CompiledBlock.MusicPlay -> {
                    val sName = inst.trackExpr.evalString(vars, evalScope)
                    val vol = inst.volumeExpr.evalFloat(vars, evalScope, 1f).coerceIn(0f, 1f)
                    val loop = inst.loopExpr.evalString(vars, evalScope) == "true"
                    if (sName.isNotBlank()) soundManager?.playMusic(sName, vol, loop)
                    pc++
                }

                is CompiledBlock.MusicPause -> { soundManager?.pauseMusic(); pc++ }
                is CompiledBlock.MusicResume -> { soundManager?.resumeMusic(); pc++ }
                is CompiledBlock.MusicStop -> { soundManager?.stopMusic(); pc++ }
                is CompiledBlock.MusicVolume -> {
                    val vol = inst.volumeExpr.evalFloat(vars, evalScope, 1f).coerceIn(0f, 1f)
                    soundManager?.setMusicVolume(vol)
                    pc++
                }

                is CompiledBlock.SceneSwitch -> {
                    val sName = inst.sceneExpr.evalString(vars, evalScope).trim()
                    if (sName.isNotBlank()) {
                        sceneSwitchRef[0] = sName
                        return false
                    }
                    pc++
                }

                is CompiledBlock.SimStop -> {
                    return false
                }

                // ── Управление переходами ─────────────────────────────────────────
                is CompiledBlock.JumpIfFalse -> {
                    val cond = inst.condition.evaluate(vars, evalScope)
                    if (!cond) {
                        pc = inst.targetPc
                    } else {
                        pc++
                    }
                }

                is CompiledBlock.Jump -> {
                    pc = inst.targetPc
                }

                is CompiledBlock.ForLoopStart -> {
                    val count = inst.countExpr.evalDouble(vars, evalScope)?.toInt() ?: 1
                    val current = loopCounters.getOrPut(inst.loopId) { 0 }
                    if (current >= count) {
                        loopCounters.remove(inst.loopId)
                        pc = inst.endPc
                    } else {
                        vars["i"] = current.toString()
                        pc++
                    }
                }

                is CompiledBlock.ForLoopEnd -> {
                    val cur = loopCounters[inst.loopId] ?: 0
                    loopCounters[inst.loopId] = cur + 1
                    pc = inst.startPc
                }

                is CompiledBlock.WhileLoopStart -> {
                    val cond = inst.condition.evaluate(vars, evalScope)
                    if (!cond) {
                        pc = inst.endPc
                    } else {
                        pc++
                    }
                }

                is CompiledBlock.WhileLoopEnd -> {
                    pc = inst.startPc
                }

                is CompiledBlock.WaitTimer -> {
                    val seconds = inst.secondsExpr.evalFloat(vars, evalScope, 1f).coerceIn(0.016f, 60f)
                    val count = inst.countExpr.evalDouble(vars, evalScope)?.toInt() ?: 1
                    val actualCount = if (count <= 0) Int.MAX_VALUE else count
                    if (allowDelay) {
                        repeat(actualCount) {
                            delay((seconds * 1000).toLong())
                            getLatestState?.invoke()?.let { live ->
                                objects.clear(); objects.putAll(live.objects)
                                joysticks.clear(); joysticks.putAll(live.joysticks)
                                deletedObjects.clear(); deletedJoysticks.clear()
                            }
                            if (inst.innerBlocks.isNotEmpty()) {
                                val ok = executeCompiled(
                                    inst.innerBlocks, vars, objects, joysticks, tables, log, errors,
                                    allowDelay, physicsEnabledRef, setPhysicsEnabled, cameraRef, sceneSwitchRef,
                                    getLatestState, deletedObjects, deletedJoysticks, modifiedFields, evalScope, onUpdate
                                )
                                if (!ok) return false
                            }
                            onUpdate?.invoke()
                        }
                    }
                    pc++
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

    fun parseHitboxPoints(s: String): List<Pair<Float, Float>> = runCatching {
        s.trim().split(";").mapNotNull { part ->
            val xy = part.trim().split(",")
            if (xy.size == 2) Pair(xy[0].trim().toFloat(), xy[1].trim().toFloat()) else null
        }
    }.getOrDefault(emptyList())

    fun serializeHitboxPoints(pts: List<Pair<Float, Float>>): String =
        pts.joinToString(";") { "${it.first},${it.second}" }
}
