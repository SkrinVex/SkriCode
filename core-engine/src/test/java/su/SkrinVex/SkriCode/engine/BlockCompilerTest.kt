package su.SkrinVex.SkriCode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.block.BlockParam
import su.SkrinVex.SkriCode.engine.compiler.BlockCompiler
import su.SkrinVex.SkriCode.engine.compiler.CompiledBlock

class BlockCompilerTest {

    @Test
    fun testCompileIfElseBlocks() {
        val blocks = listOf(
            BlockDef("1", "if_open", "Условие", params = mapOf("left" to BlockParam("{x}", "left"), "op" to BlockParam("==", "op"), "right" to BlockParam("10", "right"))),
            BlockDef("2", "set_var", "Переменная", params = mapOf("name" to BlockParam("y", "name"), "value" to BlockParam("1", "value"))),
            BlockDef("3", "else_block", "Иначе"),
            BlockDef("4", "set_var", "Переменная", params = mapOf("name" to BlockParam("y", "name"), "value" to BlockParam("2", "value"))),
            BlockDef("5", "if_close", "Конец условия")
        )

        val compiled = BlockCompiler.compile(blocks)
        assertEquals(4, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.JumpIfFalse)
        assertTrue(compiled[1] is CompiledBlock.SetVar)
        assertTrue(compiled[2] is CompiledBlock.Jump)
        assertTrue(compiled[3] is CompiledBlock.SetVar)

        // jumpIfFalse should target the else block (index 3)
        assertEquals(3, (compiled[0] as CompiledBlock.JumpIfFalse).targetPc)
        // then branch jump should target after if_close (index 4)
        assertEquals(4, (compiled[2] as CompiledBlock.Jump).targetPc)
    }

    @Test
    fun testCompileForLoop() {
        val blocks = listOf(
            BlockDef("1", "for_loop_open", "Цикл", params = mapOf("count" to BlockParam("5", "count"))),
            BlockDef("2", "set_var", "Переменная", params = mapOf("name" to BlockParam("sum", "name"), "value" to BlockParam("{sum} + 1", "value"))),
            BlockDef("3", "for_loop_close", "Конец цикла")
        )

        val compiled = BlockCompiler.compile(blocks)
        assertEquals(3, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.ForLoopStart)
        assertTrue(compiled[1] is CompiledBlock.SetVar)
        assertTrue(compiled[2] is CompiledBlock.ForLoopEnd)

        assertEquals(3, (compiled[0] as CompiledBlock.ForLoopStart).endPc)
        assertEquals(0, (compiled[2] as CompiledBlock.ForLoopEnd).startPc)
    }

    @Test
    fun testCompileAnimationAndVisualBlocks() {
        val blocks = listOf(
            BlockDef("1", "anim_play", "Играть анимацию", params = mapOf(
                "name" to BlockParam("hero", "name"),
                "sprite" to BlockParam("hero_run", "sprite"),
                "cols" to BlockParam("6", "cols"),
                "rows" to BlockParam("1", "rows"),
                "fps" to BlockParam("10", "fps")
            )),
            BlockDef("2", "particle_burst", "Взрыв", params = mapOf(
                "x" to BlockParam("100", "x"),
                "y" to BlockParam("200", "y"),
                "count" to BlockParam("30", "count")
            )),
            BlockDef("3", "screen_shake", "Тряска", params = mapOf(
                "intensity" to BlockParam("20", "intensity"),
                "duration" to BlockParam("0.5", "duration")
            )),
            BlockDef("4", "camera_bounds", "Границы", params = mapOf(
                "minX" to BlockParam("-500", "minX"),
                "maxX" to BlockParam("500", "maxX")
            ))
        )

        val compiled = BlockCompiler.compile(blocks)
        assertEquals(4, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.AnimPlay)
        assertTrue(compiled[1] is CompiledBlock.ParticleBurst)
        assertTrue(compiled[2] is CompiledBlock.ScreenShake)
        assertTrue(compiled[3] is CompiledBlock.CameraBounds)
    }

    @Test
    fun testCompilePhysicsAndHitboxAndBgColor() {
        val blocks = listOf(
            BlockDef("1", "sim_physics", "Физика", params = mapOf(
                "name" to BlockParam("wall", "name"),
                "static" to BlockParam("true", "static")
            )),
            BlockDef("2", "sim_hitbox", "Хитбокс", params = mapOf(
                "name" to BlockParam("wall", "name"),
                "type" to BlockParam("manual", "type"),
                "points" to BlockParam("-20,10;20,10;0,-10", "points")
            )),
            BlockDef("3", "sim_bg_color", "Цвет фона", params = mapOf(
                "color" to BlockParam("#1E293B", "color")
            ))
        )

        val compiled = BlockCompiler.compile(blocks)
        assertEquals(3, compiled.size)

        val phys = compiled[0] as CompiledBlock.SimPhysics
        assertTrue("isStatic must be true when parameter static is true", phys.isStatic)

        val hitbox = compiled[1] as CompiledBlock.SimHitbox
        assertEquals("manual", hitbox.type)
        assertEquals("-20,10;20,10;0,-10", hitbox.pointsExpr.evalString(emptyMap(), ExprEval.fallbackScope))

        val bg = compiled[2] as CompiledBlock.SimBgColor
        assertEquals("#1E293B", bg.colorExpr.evalString(emptyMap(), ExprEval.fallbackScope))
    }
}
