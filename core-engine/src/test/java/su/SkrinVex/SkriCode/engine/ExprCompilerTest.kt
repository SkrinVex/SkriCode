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

    @Test
    fun testAdvancedMathFunctions() {
        // Clamp
        val clampAst = ExprCompiler.compile("\$clamp(150, 0, 100)")
        assertEquals(100.0, clampAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        // Lerp
        val lerpAst = ExprCompiler.compile("\$lerp(10, 20, 0.5)")
        assertEquals(15.0, lerpAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        // Dist
        val distAst = ExprCompiler.compile("\$dist(0, 0, 3, 4)")
        assertEquals(5.0, distAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        // Angle
        val angleAst = ExprCompiler.compile("\$angle(0, 0, 10, 0)")
        assertEquals(0.0, angleAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        // Vlen
        val vlenAst = ExprCompiler.compile("\$vlen(6, 8)")
        assertEquals(10.0, vlenAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        // Add, Sub, Mul, Div, Mod, Pow
        assertEquals(30.0, ExprCompiler.compile("\$add(10, 20)").evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)
        assertEquals(15.0, ExprCompiler.compile("\$sub(25, 10)").evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)
        assertEquals(50.0, ExprCompiler.compile("\$mul(5, 10)").evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)
        assertEquals(4.0, ExprCompiler.compile("\$div(20, 5)").evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)
        assertEquals(2.0, ExprCompiler.compile("\$mod(17, 5)").evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)
        assertEquals(8.0, ExprCompiler.compile("\$pow(2, 3)").evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)
    }

    @Test
    fun testScreenConstantsDivisionAndInterpolation() {
        ExprEval.updateDeviceResolution(1080f, 2400f)
        ExprEval.setOrientation(su.SkrinVex.SkriCode.data.ProjectOrientation.LANDSCAPE)

        val heightDivAst = ExprCompiler.compile("\$screenHeight / 30")
        assertEquals(36.0, heightDivAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        val widthAst = ExprCompiler.compile("\$screenWidth")
        assertEquals(2400.0, widthAst.evalDouble(emptyMap(), ExprEval.fallbackScope)!!, 0.001)

        val textAst = ExprCompiler.compile("Width is \$screenWidth and height is \$screenHeight")
        assertEquals("Width is 2400 and height is 1080", textAst.evalString(emptyMap(), ExprEval.fallbackScope))
    }

    @Test
    fun testHitboxPointsNotMangledByExprCompiler() {
        val pointsRaw = "-50.0,30.0;50.0,30.0;0.0,-30.0"
        val ast = ExprCompiler.compile(pointsRaw)
        assertEquals(pointsRaw, ast.evalString(emptyMap(), ExprEval.fallbackScope))
    }
}
