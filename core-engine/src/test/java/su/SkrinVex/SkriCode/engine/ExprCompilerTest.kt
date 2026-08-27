package su.SkrinVex.SkriCode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import su.SkrinVex.SkriCode.engine.ast.ExprCompiler
import su.SkrinVex.SkriCode.engine.ast.LiteralNumber
import su.SkrinVex.SkriCode.engine.ast.BinaryArith
import su.SkrinVex.SkriCode.engine.ast.VarRef

class ExprCompilerTest {

    @Test
    fun testCompileNumberAndVar() {
        val numAst = ExprCompiler.compile("42.5")
        assertTrue(numAst is LiteralNumber)
        assertEquals(42.5, (numAst as LiteralNumber).value, 0.001)

        val varAst = ExprCompiler.compile("{score}")
        assertTrue(varAst is VarRef)
        assertEquals("score", (varAst as VarRef).varName)
        assertEquals(100.0, varAst.evalDouble(mapOf("score" to "100"), ExprEval.fallbackScope)!!, 0.001)
    }

    @Test
    fun testCompileArithmeticPrecedence() {
        val ast = ExprCompiler.compile("10 + 20 * 3")
        assertTrue(ast is BinaryArith)
        val res = ast.evalDouble(emptyMap(), ExprEval.fallbackScope)
        assertEquals(70.0, res!!, 0.001)
    }

    @Test
    fun testCompileCondition() {
        val cond = ExprCompiler.compileCondition("{hp}", ">=", "0")
        assertTrue(cond.evaluate(mapOf("hp" to "10"), ExprEval.fallbackScope))
        assertTrue(!cond.evaluate(mapOf("hp" to "-5"), ExprEval.fallbackScope))
    }

    @Test
    fun testCompileBuiltinFunc() {
        val ast = ExprCompiler.compile("\$max({a}, {b})")
        val vars = mapOf("a" to "15", "b" to "42")
        assertEquals(42.0, ast.evalDouble(vars, ExprEval.fallbackScope)!!, 0.001)
    }
}
