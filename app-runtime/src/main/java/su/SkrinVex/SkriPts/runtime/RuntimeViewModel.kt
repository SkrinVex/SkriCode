package su.SkrinVex.SkriPts.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import su.SkrinVex.SkriPts.data.*
import su.SkrinVex.SkriPts.engine.*
import kotlin.math.*

class RuntimeViewModel(app: Application) : AndroidViewModel(app) {

    private val _simState = MutableStateFlow<SimState?>(null)
    val simState = _simState.asStateFlow()

    private var _scripts = listOf<Script>()
    private var _scenes = listOf<Scene>()
    private var _project: ScriptProject? = null
    private var _physicsJob: Job? = null
    private val _activeHolds = mutableMapOf<Long, Pair<String, String>>()

    fun start(project: ScriptProject) {
        _project = project
        _scenes = project.scenes.orEmpty()
        launchScene(project.activeSceneId ?: _scenes.firstOrNull()?.id ?: return)
    }

    private fun launchScene(sceneId: String) {
        _physicsJob?.cancel()
        _activeHolds.clear()
        val scene = _scenes.find { it.id == sceneId } ?: return
        _scripts = scene.scripts
        val project = _project ?: return

        _simState.value = SimState(sprites = project.sprites.orEmpty(), projectId = project.id)

        viewModelScope.launch {
            val result = SimEngine.run(
                scripts = _scripts,
                globalVarDefs = project.globalVars.orEmpty(),
                globalTableDefs = project.globalTables.orEmpty(),
                locationBlocks = scene.locationBlocks,
                sprites = project.sprites.orEmpty(),
                projectId = project.id
            ) { live -> _simState.value = live }

            if (result.pendingSceneSwitch != null) {
                switchScene(result.pendingSceneSwitch, result.globalVars)
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
                var sim = curSim
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
                        target.copy(physicsBody = body.copy(velocityX = joy.knobDx * joy.speed, velocityY = joy.knobDy * joy.speed))
                    } else {
                        target.copy(x = target.x + joy.knobDx * joy.speed, y = target.y + joy.knobDy * joy.speed)
                    }
                    sim = sim.copy(objects = sim.objects + (joy.targetObject to newTarget))
                }
                if (sim !== curSim) _simState.value = sim

                val (newSim, newCols, endedCols) = SimEngine.physicsTick(_simState.value ?: continue)
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
                        if (res.pendingSceneSwitch != null) { switchScene(res.pendingSceneSwitch, res.globalVars); return@launch }
                        _simState.value = res
                    }
                }

                for ((a, b) in newCols) {
                    val sim2 = _simState.value ?: break
                    sim2.objects[a]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = b, selfName = a, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                if (res.pendingSceneSwitch != null) { switchScene(res.pendingSceneSwitch, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                    sim2.objects[b]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = a, selfName = b, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                if (res.pendingSceneSwitch != null) { switchScene(res.pendingSceneSwitch, res.globalVars); return@launch }
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
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = b, selfName = a, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                if (res.pendingSceneSwitch != null) { switchScene(res.pendingSceneSwitch, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                    sim2.objects[b]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = a, selfName = b, getLatestState = { _simState.value ?: sim2 })
                                runningCollision -= sid
                                if (res.pendingSceneSwitch != null) { switchScene(res.pendingSceneSwitch, res.globalVars); return@launch }
                                _simState.value = res
                            }
                        }
                    }
                }
            }
        }
    }

    private fun switchScene(sceneName: String, globalVars: Map<String, String>) {
        val scene = _scenes.find { it.name == sceneName } ?: return
        _physicsJob?.cancel()
        _activeHolds.clear()
        _scripts = scene.scripts
        val project = _project ?: return
        val updatedGlobalVarDefs = project.globalVars.orEmpty().map { v ->
            globalVars[v.name]?.let { v.copy(value = it) } ?: v
        }
        _simState.value = SimState(globalVars = globalVars, sprites = project.sprites.orEmpty(), projectId = project.id)
        viewModelScope.launch {
            val result = SimEngine.run(
                scene.scripts, updatedGlobalVarDefs, project.globalTables.orEmpty(),
                scene.locationBlocks, sprites = project.sprites.orEmpty(), projectId = project.id
            ) { live -> _simState.value = live }
            if (result.pendingSceneSwitch != null) switchScene(result.pendingSceneSwitch, result.globalVars)
            else { _simState.value = result; startPhysicsLoop() }
        }
    }

    fun handleTap(name: String) {
        val scriptId = _simState.value?.objects?.get(name)?.tapScriptId ?: return
        viewModelScope.launch {
            val cur = _simState.value ?: return@launch
            val res = SimEngine.runTap(scriptId, _scripts, cur,
                onUpdate = { live -> _simState.value = live },
                getLatestState = { _simState.value ?: cur }
            )
            if (res.pendingSceneSwitch != null) { switchScene(res.pendingSceneSwitch, res.globalVars); return@launch }
            _simState.value = res
        }
    }

    fun handleHoldStart(name: String, pid: Long) {
        val scriptId = _simState.value?.objects?.get(name)?.holdScriptId ?: return
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

    override fun onCleared() { _physicsJob?.cancel() }
}
