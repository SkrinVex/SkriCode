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
import kotlin.math.hypot

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
        val scene = _scenes.find { it.id == sceneId } ?: return
        _scripts = scene.scripts
        val project = _project ?: return

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
                val target = _scenes.find { it.name == result.pendingSceneSwitch }
                if (target != null) { launchScene(target.id); return@launch }
            }
            _simState.value = result
            startPhysicsLoop()
        }
    }

    private fun startPhysicsLoop() {
        _physicsJob?.cancel()
        _activeHolds.clear()
        _physicsJob = viewModelScope.launch {
            val runningCollision = mutableSetOf<String>()
            val runningHold = mutableSetOf<String>()
            while (true) {
                delay(16)
                if (_simState.value?.isStopped == true) continue

                _simState.update { s ->
                    var sim = s ?: return@update s
                    for ((_, joy) in sim.joysticks) {
                        if (joy.pointerId == null || !joy.visible) continue
                        val len = hypot(joy.knobDx, joy.knobDy)
                        if (len <= 0.05f) continue
                        val target = sim.objects[joy.targetObject] ?: continue
                        val body = target.physicsBody
                        if (body != null && body.isStatic) continue
                        val newTarget = if (body != null && body.enabled)
                            target.copy(physicsBody = body.copy(velocityX = joy.knobDx * joy.speed, velocityY = joy.knobDy * joy.speed))
                        else
                            target.copy(x = target.x + joy.knobDx * joy.speed, y = target.y + joy.knobDy * joy.speed)
                        sim = sim.copy(objects = sim.objects + (joy.targetObject to newTarget))
                    }
                    s.copy()
                    sim
                }

                val (newSim, newCols, endedCols) = SimEngine.physicsTick(_simState.value ?: continue)
                _simState.value = SimEngine.tickCamera(newSim)

                for ((_, pair) in _activeHolds.toMap()) {
                    val (scriptId, _) = pair
                    if (scriptId in runningHold) continue
                    runningHold += scriptId
                    launch {
                        val cur = _simState.value ?: run { runningHold -= scriptId; return@launch }
                        val res = SimEngine.runHold(scriptId, _scripts, cur)
                        runningHold -= scriptId
                        handleSceneSwitch(res) ?: run { _simState.value = res }
                    }
                }

                for ((a, b) in newCols) {
                    val sim = _simState.value ?: break
                    sim.objects[a]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = b, selfName = a, getLatestState = { _simState.value ?: sim })
                                runningCollision -= sid
                                handleSceneSwitch(res) ?: run { _simState.value = res }
                            }
                        }
                    }
                    sim.objects[b]?.collisionScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = a, selfName = b, getLatestState = { _simState.value ?: sim })
                                runningCollision -= sid
                                handleSceneSwitch(res) ?: run { _simState.value = res }
                            }
                        }
                    }
                }

                for ((a, b) in endedCols) {
                    val sim = _simState.value ?: break
                    sim.objects[a]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = b, selfName = a, getLatestState = { _simState.value ?: sim })
                                runningCollision -= sid
                                handleSceneSwitch(res) ?: run { _simState.value = res }
                            }
                        }
                    }
                    sim.objects[b]?.collisionEndScriptId?.let { sid ->
                        if (sid !in runningCollision) {
                            runningCollision += sid
                            launch {
                                val res = SimEngine.runCollision(sid, _scripts, _simState.value ?: return@launch, otherName = a, selfName = b, getLatestState = { _simState.value ?: sim })
                                runningCollision -= sid
                                handleSceneSwitch(res) ?: run { _simState.value = res }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleSceneSwitch(state: SimState): Unit? {
        val target = state.pendingSceneSwitch ?: return null
        val scene = _scenes.find { it.name == target } ?: return null
        launchScene(scene.id)
        return Unit
    }

    fun handleTap(name: String) {
        val scriptId = _simState.value?.objects?.get(name)?.tapScriptId ?: return
        viewModelScope.launch {
            val cur = _simState.value ?: return@launch
            val res = SimEngine.runTap(scriptId, _scripts, cur) { live -> _simState.value = live }
            handleSceneSwitch(res) ?: run { _simState.value = res }
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
