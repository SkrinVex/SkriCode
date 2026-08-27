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
}
