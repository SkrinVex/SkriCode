package su.SkrinVex.SkriPts.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*
import su.SkrinVex.SkriPts.block.BlockDef
import su.SkrinVex.SkriPts.block.BlockRegistry
import su.SkrinVex.SkriPts.data.*
import su.SkrinVex.SkriPts.data.ProjectTable
import su.SkrinVex.SkriPts.engine.SimEngine
import su.SkrinVex.SkriPts.engine.SimState
import java.util.UUID

data class EditorState(
    val projectId: String = "",
    val projectName: String = "Новый проект",
    val scenes: List<su.SkrinVex.SkriPts.data.Scene> = listOf(
        su.SkrinVex.SkriPts.data.Scene(name = "Главное меню")
    ),
    val activeSceneId: String = "",
    val scripts: List<Script> = emptyList(),
    val activeScriptId: String = "",
    val locationBlocks: List<SerializedBlock> = emptyList(),
    val globalVars: List<ProjectVar> = emptyList(),
    val globalTags: List<ProjectTag> = emptyList(),
    val globalTables: List<ProjectTable> = emptyList(),
    val sprites: List<su.SkrinVex.SkriPts.data.SpriteAsset> = emptyList(),
    val orientation: su.SkrinVex.SkriPts.data.ProjectOrientation = su.SkrinVex.SkriPts.data.ProjectOrientation.PORTRAIT,
    val packageName: String = "",
    val appLabel: String = "",
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val iconFileName: String = "",
    val simState: SimState? = null,
    val simRunCount: Int = 0,
    /** Скрипты сцены которая сейчас симулируется (может отличаться от scripts при переходе между сценами) */
    val simScripts: List<Script> = emptyList(),
    val validationErrors: List<String> = emptyList(),
    // Буфер обмена — null = пусто, true = скрипт, false = блок
    val clipboardIsScript: Boolean? = null
) {
    val activeScene: su.SkrinVex.SkriPts.data.Scene get() = scenes.find { it.id == activeSceneId } ?: scenes.first()
    val activeScript: Script get() = scripts.find { it.id == activeScriptId } ?: scripts.firstOrNull() ?: Script(UUID.randomUUID().toString(), "Скрипт 1")
    val activeBlocks: List<BlockDef> get() = activeScript.blocks.mapNotNull { it.deserialize() }
    val allScriptBlocks: List<BlockDef> get() = scripts.flatMap { it.blocks.mapNotNull { b -> b.deserialize() } }
    val visibleVars: List<ProjectVar> get() = globalVars + (activeScript.localVars ?: emptyList())
    val visibleTags: List<ProjectTag> get() = globalTags + (activeScript.localTags ?: emptyList())
    val visibleTables: List<ProjectTable> get() = globalTables + (activeScript.localTables ?: emptyList())
    val sceneNames: List<String> get() = scenes.map { it.name }
    val spriteNames: List<String> get() = sprites.map { it.name }
}

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()

    private var projectId = UUID.randomUUID().toString()
    private var isSimulationRunning = false

    init {
        viewModelScope.launch {
            _state.drop(1).debounce(500).filter { !isSimulationRunning }.collect { saveInternal(it) }
        }
        // Периодически сохраняем collapsed-состояние (не через _state чтобы не вызывать рекомпозицию)
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (_pendingCollapsedSave) {
                    _pendingCollapsedSave = false
                    val s = _state.value
                    android.util.Log.d("SkriPts", "Saving collapsed: $_collapsedBlocks")
                    saveInternal(s)
                }
            }
        }
    }

    fun loadProject(id: String) {
        val project = ProjectRepository.load(getApplication(), id) ?: return
        projectId = project.id
        // Устанавливаем имя проекта сразу — нужно для KeyVault до запуска симуляции
        su.SkrinVex.SkriPts.engine.SimEngine.projectName = project.name

        val globalVars = (project.globalVars ?: project.variables ?: emptyList()).filter { it.scope == VarScope.GLOBAL }
        val globalTags = project.globalTags ?: emptyList()
        val globalTables = project.globalTables ?: emptyList()

        // Загружаем сцены (или создаём одну из legacy-данных)
        val scenes = if (!project.scenes.isNullOrEmpty()) {
            project.scenes
        } else {
            val legacyScripts = if (!project.scripts.isNullOrEmpty()) project.scripts
                else listOf(Script(UUID.randomUUID().toString(), "Скрипт 1", blocks = project.blocks ?: emptyList()))
            listOf(su.SkrinVex.SkriPts.data.Scene(
                id = project.activeSceneId ?: UUID.randomUUID().toString(),
                name = "Сцена 1",
                scripts = legacyScripts,
                locationBlocks = project.locationBlocks ?: emptyList()
            ))
        }
        val activeScene = scenes.find { it.id == project.activeSceneId } ?: scenes.first()

        // Восстанавливаем collapsed
        activeScene.scripts.forEach { script ->
            val collapsed = script.collapsedBlockIds ?: emptySet()
            if (collapsed.isNotEmpty()) _collapsedBlocks[script.id] = collapsed.toMutableSet()
        }

        _state.update { it.copy(
            projectId = project.id,
            projectName = project.name,
            orientation = project.orientation ?: su.SkrinVex.SkriPts.data.ProjectOrientation.PORTRAIT,
            packageName = project.packageName ?: "",
            appLabel = project.appLabel ?: "",
            versionName = project.versionName ?: "1.0",
            versionCode = project.versionCode ?: 1,
            iconFileName = project.iconFileName ?: "",
            scenes = scenes,
            activeSceneId = activeScene.id,
            scripts = activeScene.scripts,
            activeScriptId = activeScene.scripts.first().id,
            locationBlocks = activeScene.locationBlocks,
            globalVars = globalVars,
            globalTags = globalTags,
            globalTables = globalTables,
            sprites = project.sprites ?: emptyList()
        )}
    }

    // --- Скрипты ---
    fun addScript(name: String) {
        val s = Script(UUID.randomUUID().toString(), name)
        _state.update { it.copy(scripts = it.scripts + s, activeScriptId = s.id) }
    }

    fun selectScript(id: String) = _state.update { it.copy(activeScriptId = id) }

    fun renameScript(id: String, name: String) = updateScript(id) { it.copy(name = name) }

    fun setScriptEvent(id: String, event: ScriptEvent, target: String = "") =
        updateScript(id) { it.copy(event = event, eventTarget = target) }

    fun deleteScript(id: String) = _state.update { state ->
        val remaining = state.scripts.filter { it.id != id }
        val fallback = if (remaining.isEmpty()) listOf(Script(UUID.randomUUID().toString(), "Скрипт 1")) else remaining
        val newActive = if (state.activeScriptId == id) fallback.first().id else state.activeScriptId
        state.copy(scripts = fallback, activeScriptId = newActive)
    }

    private fun updateScript(id: String, transform: (Script) -> Script) = _state.update {
        it.copy(scripts = it.scripts.map { s -> if (s.id == id) transform(s) else s })
    }

    // --- Блоки ---
    fun addBlock(type: String) {
        val block = BlockRegistry.create(type) ?: return
        modifyActiveBlocks { it + block.serialize() }
    }

    fun removeBlock(index: Int) = modifyActiveBlocks { list ->
        list.toMutableList().also { it.removeAt(index) }
    }

    fun duplicateBlock(index: Int) = modifyActiveBlocks { list ->
        val block = list[index].deserialize() ?: return@modifyActiveBlocks list
        
        fun BlockDef.withNewIds(): BlockDef {
            val newChildren = children.mapValues { (_, blocks) -> 
                blocks.map { it.withNewIds() }
            }
            return copy(id = java.util.UUID.randomUUID().toString(), children = newChildren)
        }
        
        val duplicated = block.withNewIds().serialize()
        list.toMutableList().also { it.add(index + 1, duplicated) }
    }

    fun moveBlock(from: Int, to: Int) = modifyActiveBlocks { list ->
        list.toMutableList().also {
            val item = it.removeAt(from)
            it.add(to.coerceIn(0, it.size), item)
        }
    }

    fun updateParam(blockIndex: Int, key: String, value: String) = modifyActiveBlocks { list ->
        list.toMutableList().also { mutable ->
            var block = mutable[blockIndex].deserialize() ?: return@also
            block = block.withParam(key, value)
            // sim_sprite: при выборе спрайта автоматически подставляем размер если 0
            if (key == "sprite" && block.type == "sim_sprite") {
                val asset = _state.value.sprites.find { it.name == value }
                if (asset != null) {
                    val curW = block.params["width"]?.value?.toFloatOrNull() ?: 0f
                    val curH = block.params["height"]?.value?.toFloatOrNull() ?: 0f
                    if (curW == 0f) block = block.withParam("width", asset.width.toString())
                    if (curH == 0f) block = block.withParam("height", asset.height.toString())
                }
            }
            mutable[blockIndex] = block.serialize()
        }
    }

    /** Добавить блок в ветку if_block (branch = "then" или "else") */
    fun addChildBlock(blockIndex: Int, branch: String, type: String) = modifyActiveBlocks { list ->
        list.toMutableList().also { mutable ->
            val block = mutable[blockIndex].deserialize() ?: return@also
            val child = BlockRegistry.create(type) ?: return@also
            val newChildren = block.children.toMutableMap()
            newChildren[branch] = (newChildren[branch] ?: emptyList()) + child
            mutable[blockIndex] = block.copy(children = newChildren).serialize()
        }
    }

    /** Удалить дочерний блок из ветки if_block */
    fun removeChildBlock(blockIndex: Int, branch: String, childIndex: Int) = modifyActiveBlocks { list ->
        list.toMutableList().also { mutable ->
            val block = mutable[blockIndex].deserialize() ?: return@also
            val newChildren = block.children.toMutableMap()
            newChildren[branch] = (newChildren[branch] ?: emptyList()).toMutableList().also { it.removeAt(childIndex) }
            mutable[blockIndex] = block.copy(children = newChildren).serialize()
        }
    }

    /** Обновить параметр дочернего блока */
    fun updateChildParam(blockIndex: Int, branch: String, childIndex: Int, key: String, value: String) = modifyActiveBlocks { list ->
        list.toMutableList().also { mutable ->
            val block = mutable[blockIndex].deserialize() ?: return@also
            val newChildren = block.children.toMutableMap()
            val branchList = (newChildren[branch] ?: emptyList()).toMutableList()
            val child = branchList[childIndex]
            branchList[childIndex] = child.withParam(key, value)
            newChildren[branch] = branchList
            mutable[blockIndex] = block.copy(children = newChildren).serialize()
        }
    }

    /** Заменить дочерний блок целиком (для обновления grandchildren) */
    fun replaceChildBlock(blockIndex: Int, branch: String, childIndex: Int, updated: BlockDef) = modifyActiveBlocks { list ->
        list.toMutableList().also { mutable ->
            val block = mutable[blockIndex].deserialize() ?: return@also
            val newChildren = block.children.toMutableMap()
            val branchList = (newChildren[branch] ?: emptyList()).toMutableList()
            if (childIndex < branchList.size) branchList[childIndex] = updated
            newChildren[branch] = branchList
            mutable[blockIndex] = block.copy(children = newChildren).serialize()
        }
    }

    private fun modifyActiveBlocks(transform: (List<SerializedBlock>) -> List<SerializedBlock>) {
        updateScript(_state.value.activeScriptId) { it.copy(blocks = transform(it.blocks)) }
    }

    // --- Переменные ---
    fun addVariable(name: String, scope: VarScope, value: String = "0") {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                if (it.globalVars.any { v -> v.name == name }) it
                else it.copy(globalVars = it.globalVars + ProjectVar(name, scope, value))
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    if (script.localVars.orEmpty().any { v -> v.name == name }) script
                    else script.copy(localVars = script.localVars.orEmpty() + ProjectVar(name, scope, value))
                }
            }
        }
    }

    fun deleteVariable(name: String, scope: VarScope) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                it.copy(globalVars = it.globalVars.filter { v -> v.name != name })
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    script.copy(localVars = script.localVars.orEmpty().filter { v -> v.name != name })
                }
            }
        }
    }

    // --- Теги ---
    fun addTag(name: String, scope: VarScope) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                if (it.globalTags.any { t -> t.name == name }) it
                else it.copy(globalTags = it.globalTags + ProjectTag(name, scope))
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    if (script.localTags.orEmpty().any { t -> t.name == name }) script
                    else script.copy(localTags = script.localTags.orEmpty() + ProjectTag(name, scope))
                }
            }
        }
    }

    fun deleteTag(name: String, scope: VarScope) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                it.copy(globalTags = it.globalTags.filter { t -> t.name != name })
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    script.copy(localTags = script.localTags.orEmpty().filter { t -> t.name != name })
                }
            }
        }
    }

    // --- Таблицы ---
    fun addTable(name: String, scope: VarScope) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                if (it.globalTables.any { t -> t.name == name }) it
                else it.copy(globalTables = it.globalTables + ProjectTable(name, scope))
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    if (script.localTables.orEmpty().any { t -> t.name == name }) script
                    else script.copy(localTables = script.localTables.orEmpty() + ProjectTable(name, scope))
                }
            }
        }
    }

    fun deleteTable(name: String, scope: VarScope) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                it.copy(globalTables = it.globalTables.filter { t -> t.name != name })
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    script.copy(localTables = script.localTables.orEmpty().filter { t -> t.name != name })
                }
            }
        }
    }

    fun setTableEntry(name: String, scope: VarScope, key: String, value: String) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                it.copy(globalTables = it.globalTables.map { t ->
                    if (t.name == name) t.copy(entries = t.entries + (key to value)) else t
                })
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    script.copy(localTables = script.localTables.orEmpty().map { t ->
                        if (t.name == name) t.copy(entries = t.entries + (key to value)) else t
                    })
                }
            }
        }
    }

    fun removeTableEntry(name: String, scope: VarScope, key: String) {
        when (scope) {
            VarScope.GLOBAL -> _state.update {
                it.copy(globalTables = it.globalTables.map { t ->
                    if (t.name == name) t.copy(entries = t.entries - key) else t
                })
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                updateScript(activeId) { script ->
                    script.copy(localTables = script.localTables.orEmpty().map { t ->
                        if (t.name == name) t.copy(entries = t.entries - key) else t
                    })
                }
            }
        }
    }

    // --- Симуляция ---
    private var _physicsJob: kotlinx.coroutines.Job? = null

    fun runSim() {
        val state = _state.value
        val errors = validate(state)
        if (errors.isNotEmpty()) { _state.update { it.copy(validationErrors = errors) }; return }
        SimEngine.projectName = state.projectName
        val initial = SimState()
        isSimulationRunning = true
        _state.update { it.copy(simState = initial, simRunCount = it.simRunCount + 1, validationErrors = emptyList(), simScripts = state.scripts) }
        viewModelScope.launch {
            val result = SimEngine.run(state.scripts, state.globalVars, state.globalTables, state.locationBlocks,
                sprites = state.sprites, projectId = projectId) { liveState ->
                _state.update { it.copy(simState = liveState) }
            }
            // Обрабатываем переход на сцену
            val switchTarget = result.pendingSceneSwitch
            if (switchTarget != null) {
                switchScene(switchTarget, result.globalVars)
            } else {
                _state.update { it.copy(simState = result) }
            }
        }
        // Запускаем физический тик ~60fps
        startPhysicsLoop()
    }

    private fun startPhysicsLoop() {
        _physicsJob?.cancel()
        _activeHolds.clear()
        _physicsJob = viewModelScope.launch {
            val runningCollisionScripts = mutableSetOf<String>()
            val runningHoldScripts = mutableSetOf<String>()
            while (true) {
                delay(16)
                if (_state.value.simState?.isStopped == true) continue
                _state.update { s ->
                    var sim = s.simState ?: return@update s
                    for ((_, joy) in sim.joysticks) {
                        if (joy.pointerId == null) continue
                        val len = hypot(joy.knobDx, joy.knobDy)
                        if (len <= 0.05f) continue
                        val target = sim.objects[joy.targetObject] ?: continue
                        val body = target.physicsBody
                        if (body != null && body.isStatic) continue
                        if (body != null && !sim.physicsEnabled) continue
                        val newTarget = if (joy.directional) {
                            val newRot = (Math.toDegrees(atan2(-joy.knobDy.toDouble(), joy.knobDx.toDouble())) + 90.0).toFloat()
                            val rad = Math.toRadians(newRot.toDouble() - 90.0)
                            target.copy(
                                x = target.x + (cos(rad) * len * joy.speed).toFloat(),
                                y = target.y + (sin(-rad) * len * joy.speed).toFloat(),
                                rotation = newRot
                            )
                        } else if (body != null && body.enabled) {
                            target.copy(physicsBody = body.copy(
                                velocityX = joy.knobDx * joy.speed,
                                velocityY = joy.knobDy * joy.speed
                            ))
                        } else {
                            target.copy(
                                x = target.x + joy.knobDx * joy.speed,
                                y = target.y + joy.knobDy * joy.speed
                            )
                        }
                        sim = sim.copy(objects = sim.objects + (joy.targetObject to newTarget))
                    }
                    s.copy(simState = sim)
                }

                val (newSim, newCols, endedCols) = SimEngine.physicsTick(_state.value.simState ?: continue)
                _state.update { it.copy(simState = SimEngine.tickCamera(newSim)) }

                for ((_, pair) in _activeHolds.toMap()) {
                    val (scriptId, _) = pair
                    if (scriptId in runningHoldScripts) continue
                    runningHoldScripts += scriptId
                    launch {
                        val currentSim = _state.value.simState ?: run { runningHoldScripts -= scriptId; return@launch }
                        val newHoldSim = SimEngine.runHold(scriptId, _state.value.simScripts, currentSim)
                        runningHoldScripts -= scriptId
                        if (newHoldSim.pendingSceneSwitch != null) { switchScene(newHoldSim.pendingSceneSwitch, newHoldSim.globalVars); return@launch }
                        _state.update { it.copy(simState = newHoldSim) }
                    }
                }

                for ((nameA, nameB) in newCols) {
                    val sim = _state.value.simState ?: break
                    val scripts = _state.value.simScripts
                    _state.update { s -> s.copy(simState = s.simState?.let { it.copy(log = it.log + "Коллизия: «$nameA» ↔ «$nameB»") }) }
                    sim.objects[nameA]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, scripts, _state.value.simState ?: return@launch, otherName = nameB, selfName = nameA, getLatestState = { _state.value.simState ?: sim })
                                runningCollisionScripts -= sid
                                if (result.pendingSceneSwitch != null) { switchScene(result.pendingSceneSwitch, result.globalVars); return@launch }
                                _state.update { it.copy(simState = result) }
                            }
                        }
                    }
                    sim.objects[nameB]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, scripts, _state.value.simState ?: return@launch, otherName = nameA, selfName = nameB, getLatestState = { _state.value.simState ?: sim })
                                runningCollisionScripts -= sid
                                if (result.pendingSceneSwitch != null) { switchScene(result.pendingSceneSwitch, result.globalVars); return@launch }
                                _state.update { it.copy(simState = result) }
                            }
                        }
                    }
                }

                for ((nameA, nameB) in endedCols) {
                    val sim = _state.value.simState ?: break
                    val scripts = _state.value.simScripts
                    _state.update { s -> s.copy(simState = s.simState?.let { it.copy(log = it.log + "Конец коллизии: «$nameA» ↔ «$nameB»") }) }
                    sim.objects[nameA]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, scripts, _state.value.simState ?: return@launch, otherName = nameB, selfName = nameA, getLatestState = { _state.value.simState ?: sim })
                                runningCollisionScripts -= sid
                                if (result.pendingSceneSwitch != null) { switchScene(result.pendingSceneSwitch, result.globalVars); return@launch }
                                _state.update { it.copy(simState = result) }
                            }
                        }
                    }
                    sim.objects[nameB]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, scripts, _state.value.simState ?: return@launch, otherName = nameA, selfName = nameB, getLatestState = { _state.value.simState ?: sim })
                                runningCollisionScripts -= sid
                                if (result.pendingSceneSwitch != null) { switchScene(result.pendingSceneSwitch, result.globalVars); return@launch }
                                _state.update { it.copy(simState = result) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopPhysics() { _physicsJob?.cancel(); _physicsJob = null; _activeHolds.clear(); isSimulationRunning = false }

    fun handleTap(objectName: String) {
        val scriptId = _state.value.simState?.objects?.get(objectName)?.tapScriptId ?: return
        viewModelScope.launch {
            val currentSim = _state.value.simState ?: return@launch
            val newSim = SimEngine.runTap(scriptId, _state.value.simScripts, currentSim) { liveState ->
                _state.update { it.copy(simState = liveState) }
            }
            if (newSim.pendingSceneSwitch != null) { switchScene(newSim.pendingSceneSwitch, newSim.globalVars); return@launch }
            _state.update { it.copy(simState = newSim) }
        }
    }

    // Хранит активные hold-скрипты по pointerId: pointerId -> (scriptId, objectName)
    private val _activeHolds = mutableMapOf<Long, Pair<String, String>>()

    fun handleHoldStart(objectName: String, pointerId: Long) {
        val scriptId = _state.value.simState?.objects?.get(objectName)?.holdScriptId ?: return
        _activeHolds[pointerId] = scriptId to objectName
    }

    fun handleHoldEnd(pointerId: Long) {
        _activeHolds.remove(pointerId)
    }

    fun handleJoystickMove(name: String, dx: Float, dy: Float, pointerId: Long) {
        // Обновляем визуальное положение ручки — движение применяется в physics tick
        _state.update { s ->
            val sim = s.simState ?: return@update s
            val joy = sim.joysticks[name] ?: return@update s
            val newJoy = joy.copy(knobDx = dx, knobDy = dy, pointerId = pointerId)
            s.copy(simState = sim.copy(joysticks = sim.joysticks + (name to newJoy)))
        }
    }

    fun handleJoystickRelease(pointerId: Long) {
        // Сбрасываем ручку джойстика который держал этот палец
        _state.update { s ->
            val sim = s.simState ?: return@update s
            val updated = sim.joysticks.mapValues { (_, joy) ->
                if (joy.pointerId == pointerId) joy.copy(knobDx = 0f, knobDy = 0f, pointerId = null)
                else joy
            }
            s.copy(simState = sim.copy(joysticks = updated))
        }
    }

    fun clearSimLogs() {
        _state.update { 
            it.copy(simState = it.simState?.copy(log = emptyList(), errors = emptyList()))
        }
    }

    // --- Буфер обмена ---
    private var _clipboardScript: su.SkrinVex.SkriPts.data.Script? = null
    private var _clipboardBlock: SerializedBlock? = null

    fun copyScript(script: su.SkrinVex.SkriPts.data.Script) {
        _clipboardScript = script; _clipboardBlock = null
        _state.update { it.copy(clipboardIsScript = true) }
    }
    fun copyBlock(block: SerializedBlock) {
        _clipboardBlock = block; _clipboardScript = null
        _state.update { it.copy(clipboardIsScript = false) }
    }
    fun hasClipboard() = _state.value.clipboardIsScript != null
    fun clipboardIsScript() = _state.value.clipboardIsScript == true

    /** Вставить скопированный скрипт в активную сцену */
    fun pasteScript() {
        val src = _clipboardScript ?: return
        val newScript = src.copy(
            id = UUID.randomUUID().toString(),
            name = src.name + " (копия)",
            blocks = src.blocks.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        _state.update { it.copy(scripts = it.scripts + newScript, activeScriptId = newScript.id, clipboardIsScript = null) }
        _clipboardScript = null
    }

    /** Вставить скопированный блок в активный скрипт */
    fun pasteBlock() {
        val src = _clipboardBlock ?: return
        val newBlock = src.copy(id = UUID.randomUUID().toString())
        modifyActiveBlocks { it + newBlock }
        _clipboardBlock = null
        _state.update { it.copy(clipboardIsScript = null) }
    }

    /** Скопировать объект локации в другую сцену */
    fun copyLocationBlockToScene(block: BlockDef, targetSceneId: String) {
        val newBlock = block.copy(id = UUID.randomUUID().toString())
        _state.update { s ->
            s.copy(scenes = s.scenes.map { scene ->
                if (scene.id == targetSceneId) {
                    val serialized = newBlock.serialize()
                    scene.copy(locationBlocks = scene.locationBlocks + serialized)
                } else scene
            })
        }
    }

    fun dismissErrors() = _state.update { it.copy(validationErrors = emptyList()) }

    // --- Объекты локации ---
    fun updateLocationBlocks(blocks: List<SerializedBlock>) =
        _state.update { it.copy(locationBlocks = blocks) }

    // --- Сцены ---
    fun addScene(name: String) {
        val scene = su.SkrinVex.SkriPts.data.Scene(name = name)
        _state.update { it.copy(scenes = it.scenes + scene) }
    }

    fun selectScene(id: String) {
        val s = _state.value
        // Сохраняем текущую сцену перед переключением
        val updatedScenes = s.scenes.map { scene ->
            if (scene.id == s.activeSceneId) scene.copy(scripts = s.scripts, locationBlocks = s.locationBlocks)
            else scene
        }
        val newScene = updatedScenes.find { it.id == id } ?: return
        // Восстанавливаем collapsed для новой сцены
        newScene.scripts.forEach { script ->
            val collapsed = script.collapsedBlockIds ?: emptySet()
            if (collapsed.isNotEmpty()) _collapsedBlocks[script.id] = collapsed.toMutableSet()
        }
        _state.update { it.copy(
            scenes = updatedScenes,
            activeSceneId = id,
            scripts = newScene.scripts,
            activeScriptId = newScene.scripts.first().id,
            locationBlocks = newScene.locationBlocks
        )}
    }

    fun renameScene(id: String, name: String) =
        _state.update { it.copy(scenes = it.scenes.map { s -> if (s.id == id) s.copy(name = name) else s }) }

    fun deleteScene(id: String) {
        val s = _state.value
        if (s.scenes.size <= 1) return  // нельзя удалить последнюю
        val remaining = s.scenes.filter { it.id != id }
        val newActive = if (s.activeSceneId == id) remaining.first() else remaining.find { it.id == s.activeSceneId }!!
        _state.update { it.copy(
            scenes = remaining,
            activeSceneId = newActive.id,
            scripts = newActive.scripts,
            activeScriptId = newActive.scripts.first().id,
            locationBlocks = newActive.locationBlocks
        )}
    }

    /** Переключение сцены во время симуляции — сохраняем globalVars и запускаем новую сцену */
    private fun switchScene(sceneName: String, globalVars: Map<String, String>) {
        val s = _state.value
        val targetScene = s.scenes.find { it.name == sceneName }
        if (targetScene == null) {
            _state.update { it.copy(simState = it.simState?.copy(
                errors = (it.simState.errors) + "Сцена «$sceneName» не найдена",
                pendingSceneSwitch = null
            ))}
            return
        }
        stopPhysics()
        val updatedGlobalVarDefs = s.globalVars.map { v ->
            globalVars[v.name]?.let { v.copy(value = it) } ?: v
        }
        _state.update { it.copy(
            simState = SimState(globalVars = globalVars),
            simRunCount = it.simRunCount + 1,
            globalVars = updatedGlobalVarDefs,
            simScripts = targetScene.scripts
        )}
        viewModelScope.launch {
            val result = SimEngine.run(
                targetScene.scripts, updatedGlobalVarDefs, s.globalTables,
                targetScene.locationBlocks, sprites = s.sprites, projectId = s.projectId
            ) { liveState -> _state.update { it.copy(simState = liveState) } }
            val nextSwitch = result.pendingSceneSwitch
            if (nextSwitch != null) switchScene(nextSwitch, result.globalVars)
            else _state.update { it.copy(simState = result) }
        }
        startPhysicsLoop()
    }

    // UI-состояние (не в EditorState — не вызывает рекомпозицию всего экрана)
    // scriptId -> firstVisibleItemIndex
    private val _scrollPositions = mutableMapOf<String, Int>()
    // scriptId -> Set<blockId>
    private val _collapsedBlocks = mutableMapOf<String, MutableSet<String>>()
    private var _pendingCollapsedSave = false
    private val _tapMutex = Mutex()

    fun saveScrollPosition(scriptId: String, index: Int) { _scrollPositions[scriptId] = index }
    fun getScrollPosition(scriptId: String) = _scrollPositions[scriptId] ?: 0

    fun toggleBlockCollapsed(scriptId: String, blockId: String): Boolean {
        val set = _collapsedBlocks.getOrPut(scriptId) { mutableSetOf() }
        val nowCollapsed = if (blockId in set) { set.remove(blockId); false } else { set.add(blockId); true }
        _pendingCollapsedSave = true
        return nowCollapsed
    }
    fun isBlockCollapsed(scriptId: String, blockId: String) =
        blockId in (_collapsedBlocks[scriptId] ?: emptySet<String>())

    private fun saveInternal(s: EditorState) {
        // Сохраняем collapsed в скрипты активной сцены
        val updatedScripts = s.scripts.map { script ->
            val collapsed = _collapsedBlocks[script.id]
            if (collapsed != null) script.copy(collapsedBlockIds = collapsed.toSet()) else script
        }
        // Обновляем активную сцену с актуальными скриптами и локацией
        val updatedScenes = s.scenes.map { scene ->
            if (scene.id == s.activeSceneId) scene.copy(scripts = updatedScripts, locationBlocks = s.locationBlocks)
            else scene
        }
        ProjectRepository.save(getApplication(), ScriptProject(
            id = projectId, name = s.projectName,
            orientation = s.orientation,
            packageName = s.packageName.ifBlank { null },
            appLabel = s.appLabel.ifBlank { null },
            versionName = s.versionName.ifBlank { null },
            versionCode = s.versionCode,
            iconFileName = s.iconFileName.ifBlank { null },
            scenes = updatedScenes, activeSceneId = s.activeSceneId,
            globalVars = s.globalVars, globalTags = s.globalTags, globalTables = s.globalTables,
            sprites = s.sprites
        ))
    }

    fun setOrientation(o: su.SkrinVex.SkriPts.data.ProjectOrientation) = _state.update { it.copy(orientation = o) }
    fun setPackageName(pkg: String) = _state.update { it.copy(packageName = pkg) }
    fun setAppLabel(v: String) = _state.update { it.copy(appLabel = v) }
    fun setVersionName(v: String) = _state.update { it.copy(versionName = v) }
    fun setVersionCode(v: Int) = _state.update { it.copy(versionCode = v) }
    fun setIconFileName(v: String) = _state.update { it.copy(iconFileName = v) }

    /** Собирает текущий ScriptProject из состояния для экспорта */
    fun buildProject(): su.SkrinVex.SkriPts.data.ScriptProject {
        val s = _state.value
        val updatedScripts = s.scripts.map { script ->
            val collapsed = _collapsedBlocks[script.id]
            if (collapsed != null) script.copy(collapsedBlockIds = collapsed.toSet()) else script
        }
        val updatedScenes = s.scenes.map { scene ->
            if (scene.id == s.activeSceneId) scene.copy(scripts = updatedScripts, locationBlocks = s.locationBlocks)
            else scene
        }
        // В APK всегда запускается первая сцена, независимо от того какая открыта в редакторе
        return su.SkrinVex.SkriPts.data.ScriptProject(
            id = projectId, name = s.projectName,
            orientation = s.orientation,
            packageName = s.packageName.ifBlank { null },
            appLabel = s.appLabel.ifBlank { null },
            versionName = s.versionName.ifBlank { null },
            versionCode = s.versionCode,
            iconFileName = s.iconFileName.ifBlank { null },
            scenes = updatedScenes, activeSceneId = updatedScenes.first().id,
            globalVars = s.globalVars, globalTags = s.globalTags, globalTables = s.globalTables,
            sprites = s.sprites
        )
    }

    // --- Спрайты ---
    fun addSprite(uri: android.net.Uri, name: String): String? {
        val asset = su.SkrinVex.SkriPts.data.SpriteRepository.importSprite(getApplication(), projectId, uri, name)
            ?: return "Не удалось импортировать изображение"
        if (_state.value.sprites.any { it.name == name })
            return "Спрайт с именем «$name» уже существует"
        _state.update { it.copy(sprites = it.sprites + asset) }
        return null
    }

    fun deleteSprite(name: String) {
        val asset = _state.value.sprites.find { it.name == name } ?: return
        su.SkrinVex.SkriPts.data.SpriteRepository.delete(getApplication(), projectId, asset.fileName)
        _state.update { it.copy(sprites = it.sprites.filter { s -> s.name != name }) }
    }

    fun getSpriteFile(name: String): java.io.File? {
        val asset = _state.value.sprites.find { it.name == name } ?: return null
        return su.SkrinVex.SkriPts.data.SpriteRepository.getFile(getApplication(), projectId, asset.fileName)
    }

    fun getProjectId(): String = projectId

    private fun validate(state: EditorState): List<String> {
        val errors = mutableListOf<String>()
        val globalNames = state.globalVars.map { it.name }.toSet()

        state.scripts.forEach { script ->
            val localNames = script.localVars.orEmpty().map { it.name }.toSet()
            val allVisible = globalNames + localNames
            val simNames = mutableSetOf<String>()

            script.blocks.mapNotNull { it.deserialize() }.forEachIndexed { idx, block ->
                val num = idx + 1
                val prefix = "Скрипт «${script.name}», блок $num «${block.displayName}»"

                when (block.type) {
                    "sim_create", "sim_text" -> {
                        val name = block.params["name"]?.value?.trim() ?: ""
                        if (name.isBlank()) errors += "$prefix: имя объекта не заполнено"
                    }
                    "set_var" -> {
                        if ((block.params["name"]?.value?.trim() ?: "").isBlank())
                            errors += "$prefix: имя переменной не заполнено"
                    }
                    "table_set", "table_get" -> {
                        if ((block.params["table"]?.value?.trim() ?: "").isBlank())
                            errors += "$prefix: имя таблицы не заполнено"
                    }
                    "save_var", "load_var", "save_table", "load_table" -> {
                        if ((block.params["key"]?.value?.trim() ?: "").isBlank())
                            errors += "$prefix: ключ сохранения не заполнен"
                    }
                }

                val allTableNames = (state.globalTables + (script.localTables ?: emptyList())).map { it.name }.toSet()

                block.params.forEach { (key, param) ->
                    if (block.type == "set_var" && key == "name") return@forEach
                    if ((block.type == "table_set" || block.type == "table_get") && key == "table") return@forEach
                    if (block.type == "table_get" && key == "var") return@forEach
                    if ((block.type == "save_var" || block.type == "load_var" || block.type == "save_table" || block.type == "load_table") && key == "key") return@forEach
                    if ((block.type == "load_var") && key == "var") return@forEach
                    if ((block.type == "save_table" || block.type == "load_table") && key == "table") return@forEach
                    val v = param.value
                    if (v.count { it == '{' } != v.count { it == '}' })
                        errors += "$prefix [${param.label}]: незакрытая скобка в «$v»"
                    if (v.count { it == '[' } != v.count { it == ']' })
                        errors += "$prefix [${param.label}]: незакрытая скобка [ в «$v»"
                    Regex("\\{([^}]+)\\}").findAll(v).forEach { m ->
                        val ref = m.groupValues[1].trim()
                        if (ref !in allVisible && ref !in su.SkrinVex.SkriPts.engine.ExprEval.SYSTEM_VARS)
                            errors += "$prefix [${param.label}]: переменная «$ref» не объявлена"
                    }
                    Regex("\\[([^\\]]+)\\]").findAll(v).forEach { m ->
                        val ref = m.groupValues[1].trim()
                        val tableName = ref.substringBefore('.').trim()
                        if (tableName !in allTableNames)
                            errors += "$prefix [${param.label}]: таблица «$tableName» не объявлена"
                    }
                }
            }
        }
        return errors
    }
}
