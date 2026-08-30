package su.SkrinVex.SkriPts.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import su.SkrinVex.SkriCode.data.*
import su.SkrinVex.SkriCode.engine.*
import kotlin.math.*

class RuntimeViewModel(app: Application) : AndroidViewModel(app) {

    private val _simState = MutableStateFlow<SimState?>(null)
    val simState = _simState.asStateFlow()

    private var _project: ScriptProject? = null
    private val soundManager = SoundManager(app) { soundName ->
        val p = _project ?: return@SoundManager null
        val sound = p.sounds?.find { it.name == soundName } ?: return@SoundManager null
        java.io.File(app.filesDir, "projects/${p.id}/sounds/${sound.fileName}")
    }

    private var _scripts = listOf<Script>()
    private var _scenes = listOf<Scene>()
    private var _simJob: Job? = null
    private val _runningTapJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var _physicsJob: Job? = null
    private val _activeHolds = mutableMapOf<Long, Pair<String, String>>()

    fun start(project: ScriptProject) {
        _project = project
        _scenes = project.scenes.orEmpty()
        su.SkrinVex.SkriCode.engine.ExprEval.setOrientation(project.orientation ?: su.SkrinVex.SkriCode.data.ProjectOrientation.PORTRAIT)
        SimEngine.soundManager = soundManager
        launchScene(project.activeSceneId ?: _scenes.firstOrNull()?.id ?: return)
    }

    fun pauseAudio() {
        soundManager.pauseAll()
    }

    fun resumeAudio() {
        soundManager.resumeAll()
    }

    fun releaseAudio() {
        soundManager.release()
    }

    private fun stopSimulation() {
        _simJob?.cancel(); _simJob = null
        _runningTapJobs.values.forEach { it.cancel() }
        _runningTapJobs.clear()
        _physicsJob?.cancel(); _physicsJob = null
        _activeHolds.clear()
    }

    private fun launchScene(sceneId: String) {
        stopSimulation()
        val scene = _scenes.find { it.id == sceneId } ?: return
        _scripts = scene.scripts
        val project = _project ?: return
        su.SkrinVex.SkriCode.engine.ExprEval.setOrientation(project.orientation ?: su.SkrinVex.SkriCode.data.ProjectOrientation.PORTRAIT)

        _simState.value = SimState(sprites = project.sprites.orEmpty(), projectId = project.id)

        _simJob = viewModelScope.launch {
            val result = SimEngine.run(
                scripts = _scripts,
                globalVarDefs = project.globalVars.orEmpty(),
                globalTableDefs = project.globalTables.orEmpty(),
                locationBlocks = scene.locationBlocks,
                sprites = project.sprites.orEmpty(),
                projectId = project.id
            ) { live -> _simState.value = live }

            val nextScene = result.pendingSceneSwitch
            if (nextScene != null) {
                switchScene(nextScene, result.globalVars)
            } else {
                _simState.value = result
                startPhysicsLoop()
            }
        }
    }

    private fun startPhysicsLoop() {
        _physicsJob?.cancel()
        _activeHolds.clear()
        _physicsJob = viewModelScope.launch {
            val runningCollision = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val runningHold = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            while (true) {
                delay(16)
                if (_simState.value?.isStopped == true) continue

                val curSim = _simState.value ?: continue
                val joySim = SimEngine.tickJoysticks(curSim)
                val (newSim, newCols, endedCols) = SimEngine.physicsTick(joySim)
                _simState.value = SimEngine.tickCamera(newSim)

                for ((_, pair) in _activeHolds.toMap()) {
                    val (scriptId, _) = pair
                    if (scriptId in runningHold) continue
                    runningHold += scriptId
                    launch {
                        val cur = _simState.value ?: run { runningHold -= scriptId; return@launch }
                        val res = SimEngine.runHold(scriptId, _scripts, cur,
                            getLatestState = { _simState.value ?: cur }
                        )
                        runningHold -= scriptId
                        val next = res.pendingSceneSwitch
                        if (next != null) { switchScene(next, res.globalVars); return@launch }
                        _simState.value = res
                    }
                }

                for ((a, b) in newCols) {
                    val sim2 = _simState.value ?: break
                    sim2.objects[a]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = b, selfName = a, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                val next = res.pendingSceneSwitch
                                if (next != null) { switchScene(next, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                    sim2.objects[b]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = a, selfName = b, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                val next = res.pendingSceneSwitch
                                if (next != null) { switchScene(next, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                }

                for ((a, b) in endedCols) {
                    val sim2 = _simState.value ?: break
                    sim2.objects[a]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = b, selfName = a, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                val next = res.pendingSceneSwitch
                                if (next != null) { switchScene(next, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                    sim2.objects[b]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = a, selfName = b, onUpdate = { _simState.value = it }, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                val next = res.pendingSceneSwitch
                                if (next != null) { switchScene(next, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                }
            }
        }
    }

    private fun switchScene(sceneName: String, globalVars: Map<String, String>) {
        val clean = sceneName.trim()
        val scene = _scenes.find { it.name.trim() == clean } ?: return
        stopSimulation()
        _scripts = scene.scripts
        val project = _project ?: return
        val updatedGlobalVarDefs = project.globalVars.orEmpty().map { v ->
            globalVars[v.name]?.let { v.copy(value = it) } ?: v
        }
        _simState.value = SimState(globalVars = globalVars, sprites = project.sprites.orEmpty(), projectId = project.id)
        _simJob = viewModelScope.launch {
            val result = SimEngine.run(
                scene.scripts, updatedGlobalVarDefs, project.globalTables.orEmpty(),
                scene.locationBlocks, sprites = project.sprites.orEmpty(), projectId = project.id
            ) { live -> _simState.value = live }
            val next = result.pendingSceneSwitch
            if (next != null) switchScene(next, result.globalVars)
            else { _simState.value = result; startPhysicsLoop() }
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

    fun handleTap(name: String) {
        val cur = _simState.value ?: return
        val obj = cur.objects[name]
        if (obj?.touchEnabled == false) return

        // Синхронизируем все текстовые поля с их переменными перед запуском скрипта тапа
        var updatedVars = cur.globalVars
        val newLog = cur.log.toMutableList()
        cur.objects.values.filter { it.isTextInput }.forEach { inputObj ->
            val cleanVar = inputObj.targetVar.removePrefix("{").removeSuffix("}").trim()
            if (cleanVar.isNotBlank()) {
                updatedVars = updatedVars + (cleanVar to inputObj.label)
                if (inputObj.inputTrigger == "button" && (
                    inputObj.inputButton == name ||
                    (inputObj.inputButton.startsWith("#") && inputObj.inputButton.substring(1) in (obj?.tags ?: emptySet()))
                )) {
                    newLog += "Ввод «${inputObj.name}»: \"${inputObj.label}\" -> {$cleanVar}"
                }
            }
        }
        val latest = _simState.value ?: cur
        _simState.value = latest.copy(globalVars = updatedVars, log = newLog)

        val scriptId = obj?.tapScriptId ?: return
        _runningTapJobs[name]?.cancel()
        val job = viewModelScope.launch {
            val liveSim = _simState.value ?: latest
            val res = SimEngine.runTap(scriptId, _scripts, liveSim,
                onUpdate = { live -> _simState.value = live },
                getLatestState = { _simState.value ?: liveSim }
            )
            val next = res.pendingSceneSwitch
            if (next != null) { switchScene(next, res.globalVars); return@launch }
            _simState.value = res
        }
        _runningTapJobs[name] = job
    }

    fun handleHoldStart(name: String, pid: Long) {
        val obj = _simState.value?.objects?.get(name)
        if (obj?.touchEnabled == false) return
        val scriptId = obj?.holdScriptId ?: return
        _activeHolds[pid] = scriptId to name
    }

    fun handleHoldEnd(pid: Long) { _activeHolds.remove(pid) }

    fun handleJoystickMove(name: String, dx: Float, dy: Float, pid: Long) {
        _simState.update { s ->
            val joy = s?.joysticks?.get(name) ?: return@update s
            s.copy(joysticks = s.joysticks + (name to joy.copy(knobDx = dx, knobDy = dy, pointerId = pid)))
        }
    }

    fun handleJoystickRelease(pid: Long) {
        _activeHolds.remove(pid)
        _simState.update { s ->
            val joy = s?.joysticks?.values?.firstOrNull { it.pointerId == pid } ?: return@update s
            s.copy(joysticks = s.joysticks + (joy.name to joy.copy(knobDx = 0f, knobDy = 0f, pointerId = null)))
        }
    }

    fun clearLogs() {
        _simState.update { it?.copy(log = emptyList(), errors = emptyList()) }
    }

    override fun onCleared() {
        stopSimulation()
        soundManager.release()
    }
}
