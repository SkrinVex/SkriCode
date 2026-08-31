package su.SkrinVex.SkriCode.ui.editor

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
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.block.BlockRegistry
import su.SkrinVex.SkriCode.data.*
import su.SkrinVex.SkriCode.data.ProjectTable
import su.SkrinVex.SkriCode.engine.SimEngine
import su.SkrinVex.SkriCode.engine.SimState
import java.util.UUID

data class EditorState(
    val projectId: String = "",
    val projectName: String = "Новый проект",
    val scenes: List<su.SkrinVex.SkriCode.data.Scene> = listOf(
        su.SkrinVex.SkriCode.data.Scene(name = "Главное меню")
    ),
    val activeSceneId: String = "",
    val scripts: List<Script> = emptyList(),
    val activeScriptId: String = "",
    val locationBlocks: List<SerializedBlock> = emptyList(),
    val globalVars: List<ProjectVar> = emptyList(),
    val globalTags: List<ProjectTag> = emptyList(),
    val globalTables: List<ProjectTable> = emptyList(),
    val sprites: List<su.SkrinVex.SkriCode.data.SpriteAsset> = emptyList(),
    val sounds: List<su.SkrinVex.SkriCode.data.SoundAsset> = emptyList(),
    val orientation: su.SkrinVex.SkriCode.data.ProjectOrientation = su.SkrinVex.SkriCode.data.ProjectOrientation.PORTRAIT,
    val packageName: String = "",
    val appLabel: String = "",
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val iconFileName: String = "",
    val enableLogFile: Boolean = false,
    val logDir: String = "",
    val clearLogsOnStart: Boolean = false,
    val validationErrors: List<String> = emptyList(),
    val clipboardIsScript: Boolean? = null
) {
    val activeScene: su.SkrinVex.SkriCode.data.Scene get() = scenes.find { it.id == activeSceneId } ?: scenes.first()
    val activeScript: Script get() = scripts.find { it.id == activeScriptId } ?: scripts.firstOrNull() ?: Script(UUID.randomUUID().toString(), "Скрипт 1")
    val activeBlocks: List<BlockDef> get() = activeScript.blocks.mapNotNull { it.deserialize() }
    val allScriptBlocks: List<BlockDef> get() = scripts.flatMap { it.blocks.mapNotNull { b -> b.deserialize() } }
    val activeFunctionParams: List<String> get() = if (activeScript.event == su.SkrinVex.SkriCode.data.ScriptEvent.FUNCTION) {
        activeScript.functionParams?.takeIf { it.isNotEmpty() }
            ?: activeScript.eventTarget.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    } else emptyList()
    val visibleVars: List<ProjectVar> get() {
        val funcParamVars = activeFunctionParams.map { ProjectVar(it, su.SkrinVex.SkriCode.data.VarScope.LOCAL, "0") }
        return globalVars + (activeScript.localVars ?: emptyList()) + funcParamVars
    }
    val visibleTags: List<ProjectTag> get() = globalTags + (activeScript.localTags ?: emptyList())
    val visibleTables: List<ProjectTable> get() = globalTables + (activeScript.localTables ?: emptyList())
    val sceneNames: List<String> get() = scenes.map { it.name }
    val spriteNames: List<String> get() = sprites.map { it.name }
    val soundNames: List<String> get() = sounds.map { it.name }
    val customFunctions: List<Script> get() {
        val sceneFuncs = scenes.flatMap { it.scripts }.filter { it.event == su.SkrinVex.SkriCode.data.ScriptEvent.FUNCTION }
        val currentFuncs = scripts.filter { it.event == su.SkrinVex.SkriCode.data.ScriptEvent.FUNCTION }
        val map = (sceneFuncs + currentFuncs).associateBy { it.name }
        return map.values.toList()
    }
    val functionNames: List<String> get() = customFunctions.map { it.name }
}

@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val soundManager = su.SkrinVex.SkriCode.engine.SoundManager(app) { soundName ->
        getSoundFile(soundName)
    }

    // Симуляция — отдельные flows, не триггерят рекомпозицию редактора
    private val _simState = MutableStateFlow<SimState?>(null)
    val simState = _simState.asStateFlow()
    private val _simRunCount = MutableStateFlow(0)
    val simRunCount = _simRunCount.asStateFlow()
    private var _simScripts = listOf<Script>()

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
        su.SkrinVex.SkriCode.engine.SimEngine.projectName = project.name

        val globalVars = (project.globalVars ?: project.variables ?: emptyList()).filter { it.scope == VarScope.GLOBAL }
        val globalTags = project.globalTags ?: emptyList()
        val globalTables = project.globalTables ?: emptyList()

        // Загружаем сцены (или создаём одну из legacy-данных)
        val scenes = if (!project.scenes.isNullOrEmpty()) {
            project.scenes
        } else {
            val legacyScripts = if (!project.scripts.isNullOrEmpty()) project.scripts
                else listOf(Script(UUID.randomUUID().toString(), "Скрипт 1", blocks = project.blocks ?: emptyList()))
            listOf(su.SkrinVex.SkriCode.data.Scene(
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

        val orient = project.orientation ?: su.SkrinVex.SkriCode.data.ProjectOrientation.PORTRAIT
        su.SkrinVex.SkriCode.engine.ExprEval.setOrientation(orient)

        _state.update { it.copy(
            projectId = project.id,
            projectName = project.name,
            orientation = orient,
            packageName = project.packageName ?: "",
            appLabel = project.appLabel ?: "",
            versionName = project.versionName ?: "1.0",
            versionCode = project.versionCode ?: 1,
            iconFileName = project.iconFileName ?: "",
            enableLogFile = project.enableLogFile ?: false,
            logDir = project.logDir ?: "",
            clearLogsOnStart = project.clearLogsOnStart ?: false,
            scenes = scenes,
            activeSceneId = activeScene.id,
            scripts = activeScene.scripts,
            activeScriptId = activeScene.scripts.first().id,
            locationBlocks = activeScene.locationBlocks,
            globalVars = globalVars,
            globalTags = globalTags,
            globalTables = globalTables,
            sprites = project.sprites ?: emptyList(),
            sounds = project.sounds ?: emptyList()
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
        val closeType = openToCloseType(type)
        if (closeType != null) {
            val closeBlock = BlockRegistry.create(closeType) ?: return
            val pairId = UUID.randomUUID().toString()
            val open = block.copy(pairId = pairId).serialize()
            val close = closeBlock.copy(pairId = pairId).serialize()
            // if_open дополнительно создаёт else_block между open и close
            if (type == "if_open") {
                val elseBlock = BlockRegistry.create("else_block")?.copy(pairId = pairId)?.serialize()
                if (elseBlock != null) {
                    modifyActiveBlocks { it + open + elseBlock + close }
                    return
                }
            }
            modifyActiveBlocks { it + open + close }
        } else {
            modifyActiveBlocks { it + block.serialize() }
        }
    }

    /** Возвращает тип закрывающего блока для открывающего, или null если не пара */
    private fun openToCloseType(type: String): String? = when (type) {
        "if_open"         -> "if_close"
        "for_loop_open"   -> "for_loop_close"
        "while_loop_open" -> "while_loop_close"
        "wait_open"       -> "wait_close"
        else -> null
    }

    /** Возвращает тип открывающего блока для закрывающего, или null если не пара */
    private fun closeToOpenType(type: String): String? = when (type) {
        "if_close"         -> "if_open"
        "for_loop_close"   -> "for_loop_open"
        "while_loop_close" -> "while_loop_open"
        "wait_close"       -> "wait_open"
        else -> null
    }

    private fun isOpenBlock(type: String) = type in setOf("if_open", "for_loop_open", "while_loop_open", "wait_open")
    private fun isCloseBlock(type: String) = type in setOf("if_close", "for_loop_close", "while_loop_close", "wait_close")

    fun removeBlock(blockId: String) = modifyActiveBlocks { list ->
        val targetIdx = list.indexOfFirst { it.id == blockId }
        if (targetIdx < 0) return@modifyActiveBlocks list
        val block = list[targetIdx].deserialize() ?: return@modifyActiveBlocks list
        if (block.pairId.isNotBlank()) {
            val pairId = block.pairId
            val deserialized = list.map { it.deserialize() }
            val openIdx = deserialized.indexOfFirst { it?.pairId == pairId && isOpenBlock(it.type) }
            val closeIdx = deserialized.indexOfFirst { it?.pairId == pairId && isCloseBlock(it.type) }
            if (openIdx >= 0 && closeIdx > openIdx) {
                list.filterIndexed { i, _ -> i < openIdx || i > closeIdx }
            } else {
                list.filter { it.deserialize()?.pairId != pairId }
            }
        } else {
            list.toMutableList().also { it.removeAt(targetIdx) }
        }
    }

    fun removeBlock(index: Int) = modifyActiveBlocks { list ->
        val block = list.getOrNull(index)?.deserialize() ?: return@modifyActiveBlocks list
        if (block.pairId.isNotBlank()) {
            val pairId = block.pairId
            val deserialized = list.map { it.deserialize() }
            // Находим диапазон open..close и удаляем всё включая тело
            val openIdx = deserialized.indexOfFirst { it?.pairId == pairId && isOpenBlock(it.type) }
            val closeIdx = deserialized.indexOfFirst { it?.pairId == pairId && isCloseBlock(it.type) }
            if (openIdx >= 0 && closeIdx > openIdx) {
                // Удаляем весь диапазон open..close включительно
                list.filterIndexed { i, _ -> i < openIdx || i > closeIdx }
            } else {
                // Fallback: удаляем только блоки с этим pairId
                list.filter { it.deserialize()?.pairId != pairId }
            }
        } else {
            list.toMutableList().also { it.removeAt(index) }
        }
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
        val block = list.getOrNull(from)?.deserialize() ?: return@modifyActiveBlocks list
        // Ограничения для open/close пар
        if (block.pairId.isNotBlank()) {
            val pairIndex = list.indexOfFirst { b ->
                val d = b.deserialize()
                d != null && d.pairId == block.pairId && d.id != block.id
            }
            if (pairIndex >= 0) {
                if (isOpenBlock(block.type)) {
                    // Открывающий не может быть ниже закрывающего
                    if (to >= pairIndex) return@modifyActiveBlocks list
                } else if (isCloseBlock(block.type)) {
                    // Закрывающий не может быть выше открывающего
                    if (to <= pairIndex) return@modifyActiveBlocks list
                }
            }
        }
        list.toMutableList().also {
            val item = it.removeAt(from)
            it.add(to.coerceIn(0, it.size), item)
        }
    }

    /** Проверяет, можно ли переместить блок вверх (с учётом ограничений пар) */
    fun canMoveUp(index: Int, blocks: List<su.SkrinVex.SkriCode.block.BlockDef>): Boolean {
        if (index <= 0) return false
        val block = blocks.getOrNull(index) ?: return false
        if (block.pairId.isBlank()) return true
        if (isOpenBlock(block.type)) return true
        if (isCloseBlock(block.type)) {
            val openIndex = blocks.indexOfFirst { it.pairId == block.pairId && isOpenBlock(it.type) }
            return openIndex < 0 || index - 1 > openIndex
        }
        // else_block — не может быть выше открывающего
        if (block.type == "else_block") {
            val openIndex = blocks.indexOfFirst { it.pairId == block.pairId && isOpenBlock(it.type) }
            return openIndex < 0 || index - 1 > openIndex
        }
        return true
    }

    /** Проверяет, можно ли переместить блок вниз (с учётом ограничений пар) */
    fun canMoveDown(index: Int, blocks: List<su.SkrinVex.SkriCode.block.BlockDef>): Boolean {
        if (index >= blocks.size - 1) return false
        val block = blocks.getOrNull(index) ?: return false
        if (block.pairId.isBlank()) return true
        if (isOpenBlock(block.type)) {
            val closeIndex = blocks.indexOfFirst { it.pairId == block.pairId && isCloseBlock(it.type) }
            return closeIndex < 0 || index + 1 < closeIndex
        }
        if (isCloseBlock(block.type)) return true
        // else_block — не может быть ниже закрывающего
        if (block.type == "else_block") {
            val closeIndex = blocks.indexOfFirst { it.pairId == block.pairId && isCloseBlock(it.type) }
            return closeIndex < 0 || index + 1 < closeIndex
        }
        return true
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
                val cleanScripts = it.scripts.map { s ->
                    s.copy(localVars = s.localVars.orEmpty().filter { lv -> lv.name != name })
                }
                if (it.globalVars.any { v -> v.name == name }) it.copy(scripts = cleanScripts)
                else it.copy(
                    globalVars = it.globalVars + ProjectVar(name, scope, value),
                    scripts = cleanScripts
                )
            }
            VarScope.LOCAL -> {
                val activeId = _state.value.activeScriptId
                if (_state.value.globalVars.any { v -> v.name == name }) return
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
    private var _simJob: kotlinx.coroutines.Job? = null
    private val _runningTapJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private var _physicsJob: kotlinx.coroutines.Job? = null
    private var _logWatchJob: kotlinx.coroutines.Job? = null
    private var _activeLogUri: android.net.Uri? = null
    private var _logFlushedCount = 0

    fun runSim() {
        stopPhysics()
        val state = _state.value
        val errors = validate(state)
        if (errors.isNotEmpty()) { _state.update { it.copy(validationErrors = errors) }; return }
        su.SkrinVex.SkriCode.engine.ExprEval.setOrientation(state.orientation)
        SimEngine.projectName = state.projectName
        SimEngine.soundManager = soundManager
        val initial = SimState(sprites = state.sprites, projectId = projectId)
        isSimulationRunning = true
        _simState.value = initial
        _simRunCount.value++
        _simScripts = state.scripts
        _state.update { it.copy(validationErrors = emptyList()) }

        _activeLogUri = resolveLogFile(state)
        _logFlushedCount = 0
        if (_activeLogUri != null) {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            appendToUri(_activeLogUri!!, "=== Сессия: $ts ===\n")
        }
        startLogWatch()

        _simJob = viewModelScope.launch {
            val result = SimEngine.run(state.scripts, state.globalVars, state.globalTables, state.locationBlocks,
                sprites = state.sprites, projectId = projectId) { liveState ->
                _simState.value = liveState
            }
            val switchTarget = result.pendingSceneSwitch
            if (switchTarget != null) {
                switchScene(switchTarget, result.globalVars)
            } else {
                _simState.value = result
            }
        }
        startPhysicsLoop()
    }

    /** Подписывается на simState и пишет новые строки в файл, дебаунс 1 сек */
    private fun startLogWatch() {
        _logWatchJob?.cancel()
        _logWatchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _simState
                .debounce(1000)
                .collect { sim ->
                    val uri = _activeLogUri ?: return@collect
                    if (sim == null) return@collect
                    val allLines = sim.errors.map { "! $it" } + sim.log
                    if (allLines.size <= _logFlushedCount) return@collect
                    val newLines = allLines.drop(_logFlushedCount)
                    appendToUri(uri, newLines.joinToString("\n", postfix = "\n"))
                    _logFlushedCount = allLines.size
                }
        }
    }

    private fun appendToUri(uri: android.net.Uri, text: String) {
        try {
            getApplication<Application>().contentResolver
                .openOutputStream(uri, "wa")
                ?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {}
    }

    private fun resolveLogFile(state: EditorState): android.net.Uri? {
        if (!state.enableLogFile) return null
        val dirUriStr = state.logDir.trim()
        if (dirUriStr.isBlank()) return null
        return try {
            val ctx = getApplication<Application>()
            val dirUri = android.net.Uri.parse(dirUriStr)
            val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, dirUri) ?: return null
            if (!dir.canWrite()) return null
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val fileName = "${state.projectName}_$ts.skrilogs"
            dir.createFile("application/octet-stream", fileName)?.uri
        } catch (_: Exception) { null }
    }

    private fun startPhysicsLoop() {
        _physicsJob?.cancel()
        _activeHolds.clear()
        _physicsJob = viewModelScope.launch {
            val runningCollisionScripts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val runningHoldScripts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            while (true) {
                delay(16)
                if (_simState.value?.isStopped == true) continue

                val curSim = _simState.value ?: continue
                val joySim = SimEngine.tickJoysticks(curSim)
                val (newSim, newCols, endedCols) = SimEngine.physicsTick(joySim)
                _simState.value = SimEngine.tickCamera(newSim)

                for ((_, pair) in _activeHolds.toMap()) {
                    val (scriptId, _) = pair
                    if (scriptId in runningHoldScripts) continue
                    runningHoldScripts += scriptId
                    launch {
                        val currentSim = _simState.value ?: run { runningHoldScripts -= scriptId; return@launch }
                        val newHoldSim = SimEngine.runHold(scriptId, _simScripts, currentSim,
                            getLatestState = { _simState.value ?: currentSim }
                        )
                        runningHoldScripts -= scriptId
                        val next = newHoldSim.pendingSceneSwitch
                        if (next != null) { switchScene(next, newHoldSim.globalVars); return@launch }
                        _simState.value = newHoldSim
                    }
                }

                for ((nameA, nameB) in newCols) {
                    val sim2 = _simState.value ?: break
                    sim2.objects[nameA]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, _simScripts, _simState.value ?: return@launch, otherName = nameB, selfName = nameA, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollisionScripts -= sid
                                val next = result.pendingSceneSwitch
                                if (next != null) { switchScene(next, result.globalVars); return@launch }
                                _simState.value = result
                            }
                        }
                    }
                    sim2.objects[nameB]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, _simScripts, _simState.value ?: return@launch, otherName = nameA, selfName = nameB, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollisionScripts -= sid
                                val next = result.pendingSceneSwitch
                                if (next != null) { switchScene(next, result.globalVars); return@launch }
                                _simState.value = result
                            }
                        }
                    }
                }

                for ((nameA, nameB) in endedCols) {
                    val sim2 = _simState.value ?: break
                    sim2.objects[nameA]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, _simScripts, _simState.value ?: return@launch, otherName = nameB, selfName = nameA, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollisionScripts -= sid
                                val next = result.pendingSceneSwitch
                                if (next != null) { switchScene(next, result.globalVars); return@launch }
                                _simState.value = result
                            }
                        }
                    }
                    sim2.objects[nameB]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollisionScripts) {
                            runningCollisionScripts += sid
                            launch {
                                val result = SimEngine.runCollision(sid, _simScripts, _simState.value ?: return@launch, otherName = nameA, selfName = nameB, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollisionScripts -= sid
                                val next = result.pendingSceneSwitch
                                if (next != null) { switchScene(next, result.globalVars); return@launch }
                                _simState.value = result
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopPhysics() {
        _simJob?.cancel(); _simJob = null
        _runningTapJobs.values.forEach { it.cancel() }
        _runningTapJobs.clear()
        _physicsJob?.cancel(); _physicsJob = null; _activeHolds.clear(); isSimulationRunning = false
        _logWatchJob?.cancel(); _logWatchJob = null
        soundManager.stopAllSounds()
        soundManager.stopMusic()
        // Финальный flush — записываем всё что не успел debounce
        val uri = _activeLogUri
        if (uri != null) {
            val sim = _simState.value
            if (sim != null) {
                val allLines = sim.errors.map { "! $it" } + sim.log
                if (allLines.size > _logFlushedCount) {
                    appendToUri(uri, allLines.drop(_logFlushedCount).joinToString("\n", postfix = "\n"))
                }
            }
            appendToUri(uri, "\n")
            _activeLogUri = null
            _logFlushedCount = 0
        }
    }

    fun handleTextInputSubmit(objectName: String, text: String) {
        val sim = _simState.value ?: return
        val obj = sim.objects[objectName] ?: return
        val newObj = obj.copy(label = text)
        var newGlobalVars = sim.globalVars
        if (obj.isTextInput && obj.targetVar.isNotBlank()) {
            newGlobalVars = newGlobalVars + (obj.targetVar to text)
        }
        _simState.value = sim.copy(
            objects = sim.objects + (objectName to newObj),
            globalVars = newGlobalVars
        )
    }

    fun handleTap(objectName: String) {
        val currentSim = _simState.value ?: return
        val obj = currentSim.objects[objectName]
        if (obj?.touchEnabled == false) return

        // Синхронизируем все текстовые поля с их переменными перед запуском скрипта тапа
        var updatedVars = currentSim.globalVars
        val newLog = currentSim.log.toMutableList()
        currentSim.objects.values.filter { it.isTextInput }.forEach { inputObj ->
            val cleanVar = inputObj.targetVar.removePrefix("{").removeSuffix("}").trim()
            if (cleanVar.isNotBlank()) {
                updatedVars = updatedVars + (cleanVar to inputObj.label)
                if (inputObj.inputTrigger == "button" && (
                    inputObj.inputButton == objectName ||
                    (inputObj.inputButton.startsWith("#") && inputObj.inputButton.substring(1) in (obj?.tags ?: emptySet()))
                )) {
                    newLog += "Ввод «${inputObj.name}»: \"${inputObj.label}\" -> {$cleanVar}"
                }
            }
        }
        val latest = _simState.value ?: currentSim
        _simState.value = latest.copy(globalVars = updatedVars, log = newLog)

        val scriptId = obj?.tapScriptId ?: return
        _runningTapJobs[objectName]?.cancel()
        val job = viewModelScope.launch {
            val liveSim = _simState.value ?: latest
            val newSim = SimEngine.runTap(scriptId, _simScripts, liveSim,
                onUpdate = { liveState -> _simState.value = liveState },
                getLatestState = { _simState.value ?: liveSim }
            )
            val next = newSim.pendingSceneSwitch
            if (next != null) { switchScene(next, newSim.globalVars); return@launch }
            _simState.value = newSim
        }
        _runningTapJobs[objectName] = job
    }

    // Хранит активные hold-скрипты по pointerId: pointerId -> (scriptId, objectName)
    private val _activeHolds = mutableMapOf<Long, Pair<String, String>>()

    fun handleHoldStart(objectName: String, pointerId: Long) {
        val obj = _simState.value?.objects?.get(objectName)
        if (obj?.touchEnabled == false) return
        val scriptId = obj?.holdScriptId ?: return
        _activeHolds[pointerId] = scriptId to objectName
    }

    fun handleHoldEnd(pointerId: Long) {
        _activeHolds.remove(pointerId)
    }

    fun handleJoystickMove(name: String, dx: Float, dy: Float, pointerId: Long) {
        val sim = _simState.value ?: return
        val joy = sim.joysticks[name] ?: return
        _simState.value = sim.copy(joysticks = sim.joysticks + (name to joy.copy(knobDx = dx, knobDy = dy, pointerId = pointerId)))
    }

    fun handleJoystickRelease(pointerId: Long) {
        val sim = _simState.value ?: return
        val updated = sim.joysticks.mapValues { (_, joy) ->
            if (joy.pointerId == pointerId) joy.copy(knobDx = 0f, knobDy = 0f, pointerId = null) else joy
        }
        _simState.value = sim.copy(joysticks = updated)
    }

    fun clearSimLogs() {
        _simState.update { it?.copy(log = emptyList(), errors = emptyList()) }
    }

    // --- Буфер обмена ---
    private var _clipboardScript: su.SkrinVex.SkriCode.data.Script? = null
    private var _clipboardBlock: SerializedBlock? = null

    fun copyScript(script: su.SkrinVex.SkriCode.data.Script) {
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
        val scene = su.SkrinVex.SkriCode.data.Scene(name = name)
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

    fun moveScene(fromIndex: Int, toIndex: Int) {
        val s = _state.value
        if (fromIndex !in s.scenes.indices || toIndex !in s.scenes.indices || fromIndex == toIndex) return
        val updatedScenes = s.scenes.map { scene ->
            if (scene.id == s.activeSceneId) scene.copy(scripts = s.scripts, locationBlocks = s.locationBlocks)
            else scene
        }.toMutableList()
        val item = updatedScenes.removeAt(fromIndex)
        updatedScenes.add(toIndex, item)
        _state.update { it.copy(scenes = updatedScenes) }
    }

    /** Переключение сцены во время симуляции — сохраняем globalVars и запускаем новую сцену */
    private fun switchScene(sceneName: String, globalVars: Map<String, String>) {
        val s = _state.value
        val cleanName = sceneName.trim()
        val targetScene = s.scenes.find { it.name.trim() == cleanName }
        if (targetScene == null) {
            _simState.update { it?.copy(errors = (it.errors) + "Сцена «$sceneName» не найдена", pendingSceneSwitch = null) }
            return
        }
        stopPhysics()
        su.SkrinVex.SkriCode.engine.ExprEval.setOrientation(s.orientation)
        isSimulationRunning = true
        val updatedGlobalVarDefs = s.globalVars.map { v ->
            globalVars[v.name]?.let { v.copy(value = it) } ?: v
        }
        _simState.value = SimState(globalVars = globalVars, sprites = s.sprites, projectId = s.projectId)
        _simRunCount.value++
        _simScripts = targetScene.scripts
        _state.update { it.copy(globalVars = updatedGlobalVarDefs) }
        _simJob = viewModelScope.launch {
            val result = SimEngine.run(
                targetScene.scripts, updatedGlobalVarDefs, s.globalTables,
                targetScene.locationBlocks, sprites = s.sprites, projectId = s.projectId
            ) { liveState -> _simState.value = liveState }
            val nextSwitch = result.pendingSceneSwitch
            if (nextSwitch != null) switchScene(nextSwitch, result.globalVars)
            else _simState.value = result
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
            enableLogFile = s.enableLogFile.takeIf { it },
            logDir = s.logDir.ifBlank { null },
            clearLogsOnStart = s.clearLogsOnStart.takeIf { it },
            scenes = updatedScenes, activeSceneId = s.activeSceneId,
            globalVars = s.globalVars, globalTags = s.globalTags, globalTables = s.globalTables,
            sprites = s.sprites,
            sounds = s.sounds
        ))
    }

    fun setOrientation(o: su.SkrinVex.SkriCode.data.ProjectOrientation) {
        su.SkrinVex.SkriCode.engine.ExprEval.setOrientation(o)
        _state.update { it.copy(orientation = o) }
    }
    fun setPackageName(pkg: String) = _state.update { it.copy(packageName = pkg) }
    fun setAppLabel(v: String) = _state.update { it.copy(appLabel = v) }
    fun setVersionName(v: String) = _state.update { it.copy(versionName = v) }
    fun setVersionCode(v: Int) = _state.update { it.copy(versionCode = v) }
    fun setIconFileName(v: String) = _state.update { it.copy(iconFileName = v) }
    fun setEnableLogFile(v: Boolean) = _state.update { it.copy(enableLogFile = v) }
    fun setLogDir(v: String) = _state.update { it.copy(logDir = v) }
    fun setClearLogsOnStart(v: Boolean) = _state.update { it.copy(clearLogsOnStart = v) }

    /** Собирает текущий ScriptProject из состояния для экспорта */
    fun buildProject(): su.SkrinVex.SkriCode.data.ScriptProject {
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
        return su.SkrinVex.SkriCode.data.ScriptProject(
            id = projectId, name = s.projectName,
            orientation = s.orientation,
            packageName = s.packageName.ifBlank { null },
            appLabel = s.appLabel.ifBlank { null },
            versionName = s.versionName.ifBlank { null },
            versionCode = s.versionCode,
            iconFileName = s.iconFileName.ifBlank { null },
            enableLogFile = s.enableLogFile.takeIf { it },
            logDir = s.logDir.ifBlank { null },
            clearLogsOnStart = s.clearLogsOnStart.takeIf { it },
            scenes = updatedScenes, activeSceneId = updatedScenes.first().id,
            globalVars = s.globalVars, globalTags = s.globalTags, globalTables = s.globalTables,
            sprites = s.sprites,
            sounds = s.sounds
        )
    }

    // --- Спрайты ---
    fun addSprite(uri: android.net.Uri, name: String): String? {
        val asset = su.SkrinVex.SkriCode.data.SpriteRepository.importSprite(getApplication(), projectId, uri, name)
            ?: return "Не удалось импортировать изображение"
        if (_state.value.sprites.any { it.name == name })
            return "Спрайт с именем «$name» уже существует"
        _state.update { it.copy(sprites = it.sprites + asset) }
        return null
    }

    fun deleteSprite(name: String) {
        val asset = _state.value.sprites.find { it.name == name } ?: return
        su.SkrinVex.SkriCode.data.SpriteRepository.delete(getApplication(), projectId, asset.fileName)
        _state.update { it.copy(sprites = it.sprites.filter { s -> s.name != name }) }
    }

    fun getSpriteFile(name: String): java.io.File? {
        val asset = _state.value.sprites.find { it.name == name } ?: return null
        return su.SkrinVex.SkriCode.data.SpriteRepository.getFile(getApplication(), projectId, asset.fileName)
    }

    // --- Звуки ---
    fun addSound(uri: android.net.Uri, name: String): String? {
        if (_state.value.sounds.any { it.name == name })
            return "Звук с именем «$name» уже существует"
        return try {
            val asset = su.SkrinVex.SkriCode.data.SoundRepository.importSound(getApplication(), projectId, uri, name)
            _state.update { it.copy(sounds = it.sounds + asset) }
            null
        } catch (e: Exception) {
            e.message ?: "Ошибка импорта аудиофайла"
        }
    }

    fun deleteSound(name: String) {
        val asset = _state.value.sounds.find { it.name == name } ?: return
        su.SkrinVex.SkriCode.data.SoundRepository.delete(getApplication(), projectId, asset.fileName)
        _state.update { it.copy(sounds = it.sounds.filter { s -> s.name != name }) }
    }

    fun getSoundFile(name: String): java.io.File? {
        val asset = _state.value.sounds.find { it.name == name } ?: return null
        return su.SkrinVex.SkriCode.data.SoundRepository.getFile(getApplication(), projectId, asset.fileName)
    }

    fun getProjectId(): String = projectId

    override fun onCleared() {
        super.onCleared()
        _logWatchJob?.cancel()
        _activeLogUri = null
        soundManager.release()
    }

    private fun validate(state: EditorState): List<String> {
        val errors = mutableListOf<String>()
        val globalNames = state.globalVars.map { it.name }.toSet()

        state.scripts.forEach { script ->
            val localNames = script.localVars.orEmpty().map { it.name }.toSet()
            val funcParams = if (script.event == ScriptEvent.FUNCTION) {
                (script.functionParams?.takeIf { it.isNotEmpty() }
                    ?: script.eventTarget.split(",").map { it.trim() }.filter { it.isNotEmpty() }).toSet()
            } else emptySet()
            val allVisible = globalNames + localNames + funcParams
            val simNames = mutableSetOf<String>()

            fun checkBlock(block: BlockDef, idx: Int) {
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
                        if (ref !in allVisible && ref !in su.SkrinVex.SkriCode.engine.ExprEval.SYSTEM_VARS && !ref.startsWith("collision_"))
                            errors += "$prefix [${param.label}]: переменная «$ref» не объявлена"
                    }
                    Regex("\\[([^\\]]+)\\]").findAll(v).forEach { m ->
                        val ref = m.groupValues[1].trim()
                        val tableName = ref.substringBefore('.').trim()
                        if (tableName !in allTableNames)
                            errors += "$prefix [${param.label}]: таблица «$tableName» не объявлена"
                    }
                }

                block.children.values.flatten().forEachIndexed { cIdx, child ->
                    checkBlock(child, cIdx)
                }
            }

            script.blocks.mapNotNull { it.deserialize() }.forEachIndexed { idx, block ->
                checkBlock(block, idx)
            }
        }
        return errors
    }
}
