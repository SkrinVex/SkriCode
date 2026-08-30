package su.SkrinVex.SkriCode.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.block.BlockParam
import su.SkrinVex.SkriCode.engine.compiler.BlockCompiler
import su.SkrinVex.SkriCode.engine.compiler.CompiledBlock
import su.SkrinVex.SkriCode.engine.ast.ExprCompiler

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

    @Test
    fun testCompileButtonAndTextInput() {
        val blocks = listOf(
            BlockDef("1", "sim_button", "Кнопка", params = mapOf(
                "name" to BlockParam("btn_start", "name"),
                "text" to BlockParam("Старт игры", "text"),
                "x" to BlockParam("10", "x"),
                "y" to BlockParam("20", "y"),
                "width" to BlockParam("180", "width"),
                "height" to BlockParam("60", "height"),
                "radius" to BlockParam("12", "radius"),
                "color" to BlockParam("#4F8EF7", "color"),
                "textColor" to BlockParam("#FFFFFF", "textColor"),
                "size" to BlockParam("18", "size"),
                "bold" to BlockParam("true", "bold")
            )),
            BlockDef("2", "sim_text_input", "Поле ввода", params = mapOf(
                "name" to BlockParam("input_name", "name"),
                "placeholder" to BlockParam("Ваше имя", "placeholder"),
                "text" to BlockParam("Player 1", "text"),
                "var" to BlockParam("user_name", "var"),
                "x" to BlockParam("0", "x"),
                "y" to BlockParam("50", "y"),
                "trigger" to BlockParam("button", "trigger"),
                "button" to BlockParam("btn_start", "button")
            ))
        )

        val compiled = BlockCompiler.compile(blocks)
        assertEquals(2, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.SimButton)
        assertTrue(compiled[1] is CompiledBlock.SimTextInput)

        val btn = compiled[0] as CompiledBlock.SimButton
        assertEquals("btn_start", btn.nameExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals("Старт игры", btn.textExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertTrue(btn.bold)

        val inp = compiled[1] as CompiledBlock.SimTextInput
        assertEquals("input_name", inp.nameExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals("Ваше имя", inp.placeholderExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals("Player 1", inp.initialTextExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals("user_name", inp.targetVar)
        assertEquals(false, inp.multiline)
        assertEquals("button", inp.trigger)
        assertEquals("btn_start", inp.buttonTargetExpr.evalString(emptyMap(), ExprEval.fallbackScope))
    }

    @Test
    fun testFieldValExpression() {
        val testObject = SimObject(
            name = "my_field",
            x = 0f, y = 0f, width = 100f, height = 40f, radius = 4f,
            color = androidx.compose.ui.graphics.Color.Black,
            label = "Entered Secret Text",
            isTextInput = true
        )
        val scope = ExprScope(
            objects = mapOf("my_field" to testObject)
        )
        val expr = ExprCompiler.compile("\$fieldVal(my_field)")
        val evaluated = expr.evalString(emptyMap<String, String>(), scope)
        assertEquals("Entered Secret Text", evaluated)
    }

    @Test
    fun testVarRefStringInterpolation() {
        val expr = ExprCompiler.compile("{text}")
        // Missing variable should evaluate to empty string, not "0"
        assertEquals("", expr.evalString(emptyMap(), ExprEval.fallbackScope))

        // Existing string variable
        val vars = mapOf("text" to "Привет")
        assertEquals("Привет", expr.evalString(vars, ExprEval.fallbackScope))

        // Template interpolation
        val templateExpr = ExprCompiler.compile("Введено: {text}!")
        assertEquals("Введено: Привет!", templateExpr.evalString(vars, ExprEval.fallbackScope))
    }

    @Test
    fun testCompileClearFocus() {
        val blocks = listOf(
            BlockDef("cf1", "sim_clear_focus", "Снять фокус")
        )
        val compiled = BlockCompiler.compile(blocks)
        assertEquals(1, compiled.size)
        assertEquals(CompiledBlock.SimClearFocus, compiled[0])
    }

    @Test
    fun testCompileMultilineTextInput() {
        val blocks = listOf(
            BlockDef("mt1", "sim_text_input", "Поле ввода", params = mapOf(
                "name" to BlockParam("multi_inp", "name"),
                "placeholder" to BlockParam("Длинный текст...", "placeholder"),
                "multiline" to BlockParam("true", "multiline"),
                "var" to BlockParam("user_bio", "var")
            ))
        )
        val compiled = BlockCompiler.compile(blocks)
        assertEquals(1, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.SimTextInput)
        val inp = compiled[0] as CompiledBlock.SimTextInput
        assertEquals("multi_inp", inp.nameExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals(true, inp.multiline)
        assertEquals("button", inp.trigger)
        assertEquals(90f, inp.heightExpr.evalFloat(emptyMap(), ExprEval.fallbackScope, 0f))
    }

    @Test
    fun testCompileCallFuncAndReturnVal() {
        val blocks = listOf(
            BlockDef("cf1", "call_func", "Вызвать функцию", params = mapOf(
                "name" to BlockParam("calculateBonus", "name"),
                "args" to BlockParam("10, {multiplier}", "args"),
                "return_var" to BlockParam("{bonus}", "return_var")
            )),
            BlockDef("ret1", "return_val", "Вернуть значение", params = mapOf(
                "value" to BlockParam("{a} + {b}", "value")
            ))
        )
        val compiled = BlockCompiler.compile(blocks)
        assertEquals(2, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.CallFunc)
        val call = compiled[0] as CompiledBlock.CallFunc
        assertEquals("calculateBonus", call.funcNameExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals(2, call.argsExpr.size)
        assertEquals("bonus", call.returnVar)

        assertTrue(compiled[1] is CompiledBlock.ReturnVal)
        val ret = compiled[1] as CompiledBlock.ReturnVal
        assertEquals("15", ret.valueExpr.evalString(mapOf("a" to "10", "b" to "5"), ExprEval.fallbackScope))
    }

    @Test
    fun testCompileCameraZoomAndUiTags() {
        val blocks = listOf(
            BlockDef("c1", "sim_camera", "Создать камеру", params = mapOf(
                "name" to BlockParam("cam1", "name"),
                "target" to BlockParam("player", "target"),
                "smoothing" to BlockParam("0.2", "smoothing"),
                "ui_tags" to BlockParam("#UI, hud", "ui_tags")
            )),
            BlockDef("c2", "camera_zoom", "Масштаб камеры", params = mapOf(
                "zoom" to BlockParam("2.0", "zoom"),
                "smoothing" to BlockParam("0.5", "smoothing")
            ))
        )
        val compiled = BlockCompiler.compile(blocks)
        assertEquals(2, compiled.size)
        assertTrue(compiled[0] is CompiledBlock.SimCameraBlock)
        val cam = compiled[0] as CompiledBlock.SimCameraBlock
        assertEquals("cam1", cam.name)
        assertEquals("player", cam.targetExpr.evalString(emptyMap(), ExprEval.fallbackScope))
        assertEquals("#UI, hud", cam.uiTagsExpr.evalString(emptyMap(), ExprEval.fallbackScope))

        assertTrue(compiled[1] is CompiledBlock.CameraZoom)
        val zoom = compiled[1] as CompiledBlock.CameraZoom
        assertEquals(2.0f, zoom.zoomExpr.evalFloat(emptyMap(), ExprEval.fallbackScope), 0.001f)
    }
}
