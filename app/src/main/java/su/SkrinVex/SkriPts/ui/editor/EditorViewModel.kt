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
    val projectName: String = "Новый проект",
    val scripts: List<Script> = listOf(Script(UUID.randomUUID().toString(), "Скрипт 1")),
    val activeScriptId: String = "",
    val globalVars: List<ProjectVar> = emptyList(),
    val globalTags: List<ProjectTag> = emptyList(),
    val globalTables: List<ProjectTable> = emptyList(),
    val simState: SimState? = null,
    val simRunCount: Int = 0,
    val validationErrors: List<String> = emptyList()
) {
    val activeScript: Script get() = scripts.find { it.id == activeScriptId } ?: scripts.first()
    val activeBlocks: List<BlockDef> get() = activeScript.blocks.mapNotNull { it.deserialize() }
    /** Переменные видимые в активном скрипте: глобальные + локальные этого скрипта */
    val visibleVars: List<ProjectVar> get() = globalVars + (activeScript.localVars ?: emptyList())
    /** Теги видимые в активном скрипте: глобальные + локальные этого скрипта */
    val visibleTags: List<ProjectTag> get() = globalTags + (activeScript.localTags ?: emptyList())
    /** Таблицы видимые в активном скрипте: глобальные + локальные этого скрипта */
    val visibleTables: List<ProjectTable> get() = globalTables + (activeScript.localTables ?: emptyList())
}

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()

    private var projectId = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            _state.drop(1).debounce(500).collect { saveInternal(it) }
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

        val scripts = if (!project.scripts.isNullOrEmpty()) project.scripts
        else listOf(Script(UUID.randomUUID().toString(), "Скрипт 1",
            blocks = project.blocks ?: emptyList()))

        val globalVars = (project.globalVars ?: project.variables ?: emptyList())
            .filter { it.scope == VarScope.GLOBAL }
        
        val globalTags = project.globalTags ?: emptyList()
        val globalTables = project.globalTables ?: emptyList()

        // Восстанавливаем collapsed из файла
        scripts.forEach { script ->
            val collapsed = script.collapsedBlockIds ?: emptySet()
            android.util.Log.d("SkriPts", "loadProject script=${script.id} collapsedBlockIds=$collapsed blocks=${script.blocks.map{it.type}}")
            if (collapsed.isNotEmpty()) {
                _collapsedBlocks[script.id] = collapsed.toMutableSet()
            }
        }

        _state.update { it.copy(
            projectName = project.name,
            scripts = scripts,
            activeScriptId = scripts.first().id,
            globalVars = globalVars,
            globalTags = globalTags,
            globalTables = globalTables
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
            val block = mutable[blockIndex].deserialize() ?: return@also
            mutable[blockIndex] = block.withParam(key, value).serialize()
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
    fun runSim() {
        val state = _state.value
        val errors = validate(state)
        if (errors.isNotEmpty()) { _state.update { it.copy(validationErrors = errors) }; return }
        // Сразу показываем экран симуляции с пустым состоянием
        val initial = SimState()
        _state.update { it.copy(simState = initial, simRunCount = it.simRunCount + 1, validationErrors = emptyList()) }
        // ON_START выполняется уже после открытия экрана
        viewModelScope.launch {
            val result = SimEngine.run(state.scripts, state.globalVars, state.globalTables) { liveState ->
                _state.update { it.copy(simState = liveState) }
            }
            _state.update { it.copy(simState = result) }
        }
    }

    fun handleTap(objectName: String) {
        val state = _state.value
        val scriptId = state.simState?.objects?.get(objectName)?.tapScriptId ?: return
        viewModelScope.launch {
            _tapMutex.withLock {
                val currentSim = _state.value.simState ?: return@withLock
                val newSim = SimEngine.runTap(scriptId, _state.value.scripts, currentSim) { liveState ->
                    _state.update { it.copy(simState = liveState) }
                }
                _state.update { it.copy(simState = newSim) }
            }
        }
    }

    // Хранит активные hold-корутины по pointerId
    private val _holdJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()
    private val _joystickJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun handleHoldStart(objectName: String, pointerId: Long) {
        val state = _state.value
        val scriptId = state.simState?.objects?.get(objectName)?.holdScriptId ?: return
        _holdJobs[pointerId]?.cancel()
        _holdJobs[pointerId] = viewModelScope.launch {
            while (true) {
                val currentSim = _state.value.simState ?: break
                val newSim = SimEngine.runHold(scriptId, _state.value.scripts, currentSim)
                _state.update { it.copy(simState = newSim) }
                delay(50) // ~20 раз в секунду, не блокируем поток
            }
        }
    }

    fun handleHoldEnd(pointerId: Long) {
        _holdJobs.remove(pointerId)?.cancel()
    }

    fun handleJoystickMove(name: String, dx: Float, dy: Float, pointerId: Long) {
        // Обновляем визуальное положение ручки и запускаем тик движения
        _state.update { s ->
            val sim = s.simState ?: return@update s
            val joy = sim.joysticks[name] ?: return@update s
            val newJoy = joy.copy(knobDx = dx, knobDy = dy, pointerId = pointerId)
            s.copy(simState = sim.copy(joysticks = sim.joysticks + (name to newJoy)))
        }
        // Запускаем/обновляем тик для этого джойстика
        if (_joystickJobs[name] == null) {
            _joystickJobs[name] = viewModelScope.launch {
                while (true) {
                    val sim = _state.value.simState ?: break
                    val joy = sim.joysticks[name] ?: break
                    if (joy.pointerId == null) break
                    val target = sim.objects[joy.targetObject] ?: run { delay(16); continue }
                    val len = hypot(joy.knobDx, joy.knobDy)
                    if (len > 0.05f) {
                        val moveX = joy.knobDx * joy.speed
                        val moveY = joy.knobDy * joy.speed
                        val newRotation = if (joy.directional)
                            (Math.toDegrees(atan2(-joy.knobDy.toDouble(), joy.knobDx.toDouble())) + 90.0).toFloat()
                        else target.rotation
                        val newTarget = if (joy.directional) {
                            // движение по направлению поворота
                            val rad = Math.toRadians(newRotation.toDouble() - 90.0)
                            target.copy(
                                x = target.x + (cos(rad) * len * joy.speed).toFloat(),
                                y = target.y + (sin(-rad) * len * joy.speed).toFloat(),
                                rotation = newRotation
                            )
                        } else {
                            target.copy(x = target.x + moveX, y = target.y + moveY)
                        }
                        _state.update { s ->
                            val st = s.simState ?: return@update s
                            s.copy(simState = st.copy(objects = st.objects + (joy.targetObject to newTarget)))
                        }
                    }
                    delay(16) // ~60fps
                }
            }
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
        // Останавливаем тики джойстиков без пальца
        _joystickJobs.entries.removeIf { (name, job) ->
            val joy = _state.value.simState?.joysticks?.get(name)
            if (joy?.pointerId == null) { job.cancel(); true } else false
        }
    }

    fun clearSimLogs() {
        _state.update { 
            it.copy(simState = it.simState?.copy(log = emptyList(), errors = emptyList()))
        }
    }

    fun dismissErrors() = _state.update { it.copy(validationErrors = emptyList()) }

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
        val scripts = s.scripts.map { script ->
            val collapsed = _collapsedBlocks[script.id]
            android.util.Log.d("SkriPts", "saveInternal script=${script.id} collapsed=$collapsed")
            if (collapsed != null) script.copy(collapsedBlockIds = collapsed.toSet()) else script
        }
        ProjectRepository.save(getApplication(), ScriptProject(
            id = projectId, name = s.projectName,
            scripts = scripts, globalVars = s.globalVars, globalTags = s.globalTags,
            globalTables = s.globalTables
        ))
    }

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
                        else if (name in simNames) errors += "$prefix: объект «$name» уже создан"
                        simNames += name
                    }
                    "set_var" -> {
                        if ((block.params["name"]?.value?.trim() ?: "").isBlank())
                            errors += "$prefix: имя переменной не заполнено"
                    }
                    "table_set", "table_get" -> {
                        if ((block.params["table"]?.value?.trim() ?: "").isBlank())
                            errors += "$prefix: имя таблицы не заполнено"
                    }
                }

                val allTableNames = (state.globalTables + (script.localTables ?: emptyList())).map { it.name }.toSet()

                block.params.forEach { (key, param) ->
                    if (block.type == "set_var" && key == "name") return@forEach
                    if ((block.type == "table_set" || block.type == "table_get") && key == "table") return@forEach
                    if (block.type == "table_get" && key == "var") return@forEach
                    val v = param.value
                    if (v.count { it == '{' } != v.count { it == '}' })
                        errors += "$prefix [${param.label}]: незакрытая скобка в «$v»"
                    if (v.count { it == '[' } != v.count { it == ']' })
                        errors += "$prefix [${param.label}]: незакрытая скобка [ в «$v»"
                    Regex("\\{([^}]+)\\}").findAll(v).forEach { m ->
                        val ref = m.groupValues[1].trim()
                        if (ref !in allVisible)
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
