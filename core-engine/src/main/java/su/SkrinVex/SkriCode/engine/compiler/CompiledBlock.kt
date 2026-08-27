package su.SkrinVex.SkriCode.engine.compiler

import androidx.compose.ui.graphics.Color
import su.SkrinVex.SkriCode.engine.ast.AstExpr
import su.SkrinVex.SkriCode.engine.ast.ConditionExpr

sealed interface CompiledBlock {
    // ── Переменные и таблицы ──────────────────────────────────────────────────
    data class SetVar(val name: String, val valueExpr: AstExpr) : CompiledBlock
    data class SetTag(val targetExpr: AstExpr, val tagExpr: AstExpr) : CompiledBlock
    data class TableSet(val tableExpr: AstExpr, val keyExpr: AstExpr, val valueExpr: AstExpr) : CompiledBlock
    data class TableGet(val tableExpr: AstExpr, val keyExpr: AstExpr, val varName: String) : CompiledBlock
    data class SaveVar(val keyExpr: AstExpr, val valueExpr: AstExpr, val encrypt: Boolean) : CompiledBlock
    data class LoadVar(val keyExpr: AstExpr, val varName: String, val defaultExpr: AstExpr, val encrypt: Boolean) : CompiledBlock
    data class SaveTable(val keyExpr: AstExpr, val tableExpr: AstExpr, val encrypt: Boolean) : CompiledBlock
    data class LoadTable(val keyExpr: AstExpr, val tableExpr: AstExpr, val encrypt: Boolean) : CompiledBlock

    // ── Симуляция и объекты ──────────────────────────────────────────────────
    data class SimCreate(
        val nameExpr: AstExpr, val xExpr: AstExpr, val yExpr: AstExpr,
        val widthExpr: AstExpr, val heightExpr: AstExpr, val radiusExpr: AstExpr,
        val colorExpr: AstExpr, val tags: Set<String> = emptySet(), val zOrder: Int = 0
    ) : CompiledBlock

    data class SimText(
        val nameExpr: AstExpr, val textExpr: AstExpr, val xExpr: AstExpr, val yExpr: AstExpr,
        val widthExpr: AstExpr, val heightExpr: AstExpr, val sizeExpr: AstExpr,
        val bold: Boolean, val textColorExpr: AstExpr,
        val tags: Set<String> = emptySet(), val zOrder: Int = 0
    ) : CompiledBlock

    data class SimSprite(
        val nameExpr: AstExpr, val spriteExpr: AstExpr, val xExpr: AstExpr, val yExpr: AstExpr,
        val widthExpr: AstExpr, val heightExpr: AstExpr, val alphaExpr: AstExpr,
        val tags: Set<String> = emptySet(), val zOrder: Int = 0
    ) : CompiledBlock

    data class SimMove(val targetExpr: AstExpr, val mode: String, val xExpr: AstExpr, val yExpr: AstExpr) : CompiledBlock
    data class SimResize(val targetExpr: AstExpr, val widthExpr: AstExpr, val heightExpr: AstExpr) : CompiledBlock
    data class SimColor(val targetExpr: AstExpr, val colorExpr: AstExpr) : CompiledBlock
    data class SimUpdateText(val targetExpr: AstExpr, val textExpr: AstExpr) : CompiledBlock
    data class SimRotate(val targetExpr: AstExpr, val mode: String, val angleExpr: AstExpr) : CompiledBlock
    data class SimHide(val targetExpr: AstExpr) : CompiledBlock
    data class SimShow(val targetExpr: AstExpr) : CompiledBlock
    data class SimDelete(val targetExpr: AstExpr) : CompiledBlock
    data class SimModify(val targetExpr: AstExpr, val props: List<Pair<String, AstExpr>>) : CompiledBlock
    data class SimLayer(val targetExpr: AstExpr, val layerExpr: AstExpr) : CompiledBlock

    data class SimJoystick(
        val name: String, val xExpr: AstExpr, val yExpr: AstExpr,
        val baseRadius: Float, val knobRadius: Float,
        val baseColor: Color, val knobColor: Color,
        val target: String, val speed: Float, val directional: Boolean
    ) : CompiledBlock

    data class SetTexture(
        val targetExpr: AstExpr, val spriteExpr: AstExpr,
        val scaleXExpr: AstExpr, val scaleYExpr: AstExpr, val alphaExpr: AstExpr,
        val cropXExpr: AstExpr, val cropYExpr: AstExpr, val cropWExpr: AstExpr, val cropHExpr: AstExpr
    ) : CompiledBlock

    // ── Физика ───────────────────────────────────────────────────────────────
    data class SimPhysics(
        val targetExpr: AstExpr, val gravityExpr: AstExpr, val isStatic: Boolean,
        val bouncinessExpr: AstExpr, val massExpr: AstExpr, val vxExpr: AstExpr, val vyExpr: AstExpr
    ) : CompiledBlock

    data class PhysicsImpulse(val targetExpr: AstExpr, val vxExpr: AstExpr, val vyExpr: AstExpr) : CompiledBlock
    data class PhysicsMove(val targetExpr: AstExpr, val speedExpr: AstExpr, val turnExpr: AstExpr, val frictionExpr: AstExpr) : CompiledBlock
    data class SimHitbox(val targetExpr: AstExpr, val type: String, val pointsExpr: AstExpr) : CompiledBlock
    data class SimNoCollision(val targetExpr: AstExpr, val ignoreExpr: AstExpr) : CompiledBlock
    data class PhysicsToggle(val enabledExpr: AstExpr) : CompiledBlock

    // ── Камера ───────────────────────────────────────────────────────────────
    data class SimCameraBlock(
        val name: String, val targetExpr: AstExpr, val smoothingExpr: AstExpr,
        val uiTags: Set<String>, val enabled: Boolean
    ) : CompiledBlock

    data class CameraToggle(val name: String, val enabledExpr: AstExpr) : CompiledBlock

    // ── Звуки и музыка ───────────────────────────────────────────────────────
    data class SoundPlay(val soundExpr: AstExpr, val volumeExpr: AstExpr, val loopExpr: AstExpr, val rateExpr: AstExpr) : CompiledBlock
    data class SoundStop(val soundExpr: AstExpr) : CompiledBlock
    data class MusicPlay(val trackExpr: AstExpr, val volumeExpr: AstExpr, val loopExpr: AstExpr) : CompiledBlock
    object MusicPause : CompiledBlock
    object MusicResume : CompiledBlock
    object MusicStop : CompiledBlock
    data class MusicVolume(val volumeExpr: AstExpr) : CompiledBlock

    // ── Управление потоком ────────────────────────────────────────────────────
    data class JumpIfFalse(val condition: ConditionExpr, var targetPc: Int) : CompiledBlock
    data class Jump(var targetPc: Int) : CompiledBlock
    data class ForLoopStart(val countExpr: AstExpr, val loopId: String, var endPc: Int) : CompiledBlock
    data class ForLoopEnd(val loopId: String, val startPc: Int) : CompiledBlock
    data class WhileLoopStart(val condition: ConditionExpr, var endPc: Int) : CompiledBlock
    data class WhileLoopEnd(val startPc: Int) : CompiledBlock
    data class WaitTimer(val secondsExpr: AstExpr, val countExpr: AstExpr, val innerBlocks: List<CompiledBlock>) : CompiledBlock

    data class SceneSwitch(val sceneExpr: AstExpr) : CompiledBlock
    object SimStop : CompiledBlock
}
