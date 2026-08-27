package su.SkrinVex.SkriCode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExprEvalTest {

    @Test
    fun testBasicArithmetic() {
        val res = ExprEval.eval("10 + 20", emptyMap())
        assertNull(res.error)
        assertEquals("30", res.value)

        val res2 = ExprEval.eval("10 * 5 - 4", emptyMap())
        assertNull(res2.error)
        assertEquals("46", res2.value)
    }

    @Test
    fun testNestedParenthesesMatching() {
        val res = ExprEval.eval("(1 + 2) * (3 + 4)", emptyMap())
        assertNull(res.error)
        assertEquals("21", res.value)

        val res2 = ExprEval.eval("((10 + 5) * 2) / 3", emptyMap())
        assertNull(res2.error)
        assertEquals("10", res2.value)
    }

    @Test
    fun testBuiltinFunctions() {
        val resAbs = ExprEval.eval("\$abs(-42)", emptyMap())
        assertEquals("42", resAbs.value)

        val resMin = ExprEval.eval("\$min(15, 8)", emptyMap())
        assertEquals("8", resMin.value)

        val resMax = ExprEval.eval("\$max(15, 8)", emptyMap())
        assertEquals("15", resMax.value)

        val resSqrt = ExprEval.eval("\$sqrt(64)", emptyMap())
        assertEquals("8", resSqrt.value)

        val resConcat = ExprEval.eval("\$concat('Hello ', 'World')", emptyMap())
        assertEquals("Hello World", resConcat.value)
    }

    @Test
    fun testScopeEvaluation() {
        val obj = SimObject(
            name = "player",
            x = 120f,
            y = 250f,
            width = 50f,
            height = 50f,
            radius = 0f,
            color = androidx.compose.ui.graphics.Color.Black
        )
        val scope = ExprScope(objects = mapOf("player" to obj))

        val resX = ExprEval.eval("\$objX('player')", emptyMap(), scope)
        assertEquals("120", resX.value)

        val resY = ExprEval.eval("\$objY('player')", emptyMap(), scope)
        assertEquals("250", resY.value)
    }

    @Test
    fun testConditions() {
        val (c1, err1) = ExprEval.evalCondition("10", ">", "5", emptyMap())
        assertNull(err1)
        assertTrue(c1)

        val (c2, err2) = ExprEval.evalCondition("apple", "==", "apple", emptyMap())
        assertNull(err2)
        assertTrue(c2)

        val (c3, err3) = ExprEval.evalCondition("apple", "!=", "banana", emptyMap())
        assertNull(err3)
        assertTrue(c3)
    }
}
