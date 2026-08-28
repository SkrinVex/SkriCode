package su.SkrinVex.SkriCode.engine

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import su.SkrinVex.SkriCode.data.Script
import su.SkrinVex.SkriCode.data.ScriptEvent
import su.SkrinVex.SkriCode.data.SerializedBlock

class SimEngineTest {

    @Test
    fun testSimEngineRun_CreateAndMove() = runBlocking {
        val createBlock = SerializedBlock(
            type = "sim_create",
            params = mapOf(
                "name" to "hero",
                "x" to "10",
                "y" to "20",
                "width" to "50",
                "height" to "50",
                "color" to "#FF0000"
            )
        )
        val moveBlock = SerializedBlock(
            type = "sim_move",
            params = mapOf(
                "name" to "hero",
                "mode" to "step",
                "x" to "15",
                "y" to "5"
            )
        )

        val script = Script(
            id = "s1",
            name = "InitScript",
            event = ScriptEvent.ON_START,
            blocks = listOf(createBlock, moveBlock)
        )

        val state = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = emptyList()
        )

        val hero = state.objects["hero"]
        assertNotNull(hero)
        assertEquals(25f, hero!!.x, 0.001f)
        assertEquals(25f, hero.y, 0.001f)
    }

    @Test
    fun testSimEngineSelectivePropertyMerge() = runBlocking {
        // Initial state with falling object
        val obj = SimObject(
            name = "hero",
            x = 100f,
            y = 50f, // Live position that had moved from initial 0
            width = 40f,
            height = 40f,
            radius = 0f,
            color = Color.Blue,
            tapScriptId = "tap1"
        )
        val state = SimState(
            objects = mapOf("hero" to obj)
        )

        // Script only changes the color
        val colorBlock = SerializedBlock(
            type = "sim_color",
            params = mapOf(
                "name" to "hero",
                "color" to "#00FF00"
            )
        )
        val script = Script(
            id = "tap1",
            name = "ChangeColor",
            event = ScriptEvent.ON_TAP,
            eventTarget = "hero",
            blocks = listOf(colorBlock)
        )

        // Simulate that while tap script executed, hero had moved further in live simulation (y = 40f)
        val liveState = state.copy(
            objects = mapOf("hero" to obj.copy(y = 40f))
        )

        val finalState = SimEngine.runTap(
            scriptId = "tap1",
            scripts = listOf(script),
            currentState = state,
            getLatestState = { liveState }
        )

        val updatedHero = finalState.objects["hero"]
        assertNotNull(updatedHero)
        // Position y must remain 40f (live) instead of being overwritten with 50f!
        assertEquals(40f, updatedHero!!.y, 0.001f)
        assertEquals(SimEngine.parseColor("#00FF00"), updatedHero.color)
    }

    @Test
    fun testMissingObjectErrorLogging() = runBlocking {
        // Script trying to move a non-existent object "ghost"
        val moveBlock = SerializedBlock(
            type = "sim_move",
            params = mapOf(
                "name" to "ghost",
                "mode" to "step",
                "x" to "10",
                "y" to "10"
            )
        )
        val script = Script(
            id = "s_err",
            name = "ErrorScript",
            event = ScriptEvent.ON_START,
            blocks = listOf(moveBlock)
        )

        val state = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = emptyList()
        )

        org.junit.Assert.assertTrue(
            "State should contain an error about missing object",
            state.errors.any { it.contains("Объект «ghost» не найден") }
        )
    }

    @Test
    fun testMissingSpriteErrorLogging() = runBlocking {
        // Script creating an object with unknown sprite
        val spriteBlock = SerializedBlock(
            type = "sim_sprite",
            params = mapOf(
                "name" to "player",
                "sprite" to "missing_texture"
            )
        )
        val script = Script(
            id = "s_sprite_err",
            name = "SpriteErrorScript",
            event = ScriptEvent.ON_START,
            blocks = listOf(spriteBlock)
        )

        val state = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = emptyList()
        )

        org.junit.Assert.assertTrue(
            "State should contain an error about missing sprite",
            state.errors.any { it.contains("Спрайт «missing_texture» не найден") }
        )
    }

    @Test
    fun testScriptWithTextureChangeAndAnimationAcrossWaitTimers() = runBlocking {
        val spriteAssets = listOf(
            su.SkrinVex.SkriCode.data.SpriteAsset("msf_1000011921", "msf_1000011921.png", 600, 100),
            su.SkrinVex.SkriCode.data.SpriteAsset("msf_1000017168", "msf_1000017168.png", 200, 200)
        )
        val r1 = SimObject(name = "r1", x = 100f, y = 100f, width = 150f, height = 150f, radius = 0f, color = Color.White, tapScriptId = "tap_r1")
        val rect3 = SimObject(
            name = "rect3", x = 0f, y = 0f, width = 1080f, height = 1920f, radius = 0f, color = Color.Black,
            spriteName = "msf_1000011921", animCols = 3, animPlaying = true
        )
        val initialSimState = SimState(
            objects = mapOf("r1" to r1, "rect3" to rect3),
            sprites = spriteAssets
        )

        val setTexBlock = SerializedBlock(
            type = "set_texture",
            params = mapOf("name" to "r1", "sprite" to "msf_1000017168")
        )
        val waitBlock1 = SerializedBlock(
            type = "wait_open",
            params = mapOf("seconds" to "0.02", "count" to "1")
        )
        val waitClose1 = SerializedBlock(type = "wait_close", params = emptyMap())

        val createR2Block = SerializedBlock(
            type = "sim_create",
            params = mapOf(
                "name" to "r2",
                "x" to "50",
                "y" to "50",
                "width" to "400",
                "height" to "300",
                "color" to "#4F8EF7"
            )
        )
        val animPlayR2 = SerializedBlock(
            type = "anim_play",
            params = mapOf(
                "name" to "r2",
                "sprite" to "msf_1000011921",
                "cols" to "6",
                "rows" to "1",
                "fps" to "6",
                "loop" to "true"
            )
        )
        val waitBlock2 = SerializedBlock(
            type = "wait_open",
            params = mapOf("seconds" to "0.02", "count" to "1")
        )
        val waitClose2 = SerializedBlock(type = "wait_close", params = emptyMap())

        val tapScript = Script(
            id = "tap_r1",
            name = "TapR1Script",
            event = ScriptEvent.ON_TAP,
            eventTarget = "r1",
            blocks = listOf(setTexBlock, waitBlock1, waitClose1, createR2Block, animPlayR2, waitBlock2, waitClose2)
        )

        var liveState = initialSimState

        val finalState = SimEngine.runTap(
            scriptId = "tap_r1",
            scripts = listOf(tapScript),
            currentState = initialSimState,
            getLatestState = { liveState },
            onUpdate = { live -> liveState = live }
        )

        val finalR1 = finalState.objects["r1"]
        assertNotNull("r1 must exist in finalState", finalR1)
        assertEquals("r1 must retain updated texture msf_1000017168", "msf_1000017168", finalR1!!.spriteName)

        val finalR2 = finalState.objects["r2"]
        assertNotNull("r2 must exist in finalState even after wait timer", finalR2)
        assertEquals("r2 must have sprite msf_1000011921", "msf_1000011921", finalR2!!.spriteName)
        assertEquals("r2 must have 6 animation columns", 6, finalR2.animCols)
        org.junit.Assert.assertTrue("r2 animation must be playing", finalR2.animPlaying)

        val finalRect3 = finalState.objects["rect3"]
        assertNotNull("rect3 must still exist", finalRect3)
        assertEquals("rect3 sprite must be preserved", "msf_1000011921", finalRect3!!.spriteName)
    }

    @Test
    fun testWhitespaceRobustnessInObjectNamesAndEventBindings() = runBlocking {
        val createBlock = SerializedBlock(
            type = "sim_create",
            params = mapOf("name" to "Револьвер ", "x" to "0", "y" to "0", "width" to "100", "height" to "100", "color" to "#FFFFFF")
        )
        val startScript = Script(
            id = "start_1",
            name = "Start",
            event = ScriptEvent.ON_START,
            blocks = listOf(createBlock)
        )

        val rotateBlock = SerializedBlock(
            type = "sim_rotate",
            params = mapOf("name" to "Револьвер", "mode" to "step", "angle" to "15")
        )
        val tapScript = Script(
            id = "tap_1",
            name = "Tap",
            event = ScriptEvent.ON_TAP,
            eventTarget = "Револьвер",
            blocks = listOf(rotateBlock)
        )

        val state = SimEngine.run(
            scripts = listOf(startScript, tapScript),
            globalVarDefs = emptyList()
        )

        val gun = state.objects["Револьвер"] ?: state.objects["Револьвер "]
        assertNotNull("Gun object must exist", gun)
        assertEquals("tapScriptId must be bound to gun despite trailing space", "tap_1", gun!!.tapScriptId)

        val afterTap = SimEngine.runTap(
            scriptId = "tap_1",
            scripts = listOf(startScript, tapScript),
            currentState = state
        )

        val gunAfter = afterTap.objects["Револьвер"] ?: afterTap.objects["Револьвер "]
        assertNotNull("Gun object must exist after tap", gunAfter)
        assertEquals("Rotation must be 15 degrees", 15f, gunAfter!!.rotation, 0.001f)
    }

    @Test
    fun testSimTouchDisableAndEnable() = runBlocking {
        val createObj = SerializedBlock(
            type = "sim_create",
            params = mapOf("name" to "btn", "x" to "0", "y" to "0", "width" to "100", "height" to "100", "color" to "#FFFFFF")
        )
        val disableTouch = SerializedBlock(
            type = "sim_touch_disable",
            params = mapOf("name" to "btn")
        )
        val enableTouch = SerializedBlock(
            type = "sim_touch_enable",
            params = mapOf("name" to "btn")
        )

        val scriptDisable = Script(
            id = "s_disable",
            name = "Disable",
            event = ScriptEvent.ON_START,
            blocks = listOf(createObj, disableTouch)
        )

        val stateDisabled = SimEngine.run(
            scripts = listOf(scriptDisable),
            globalVarDefs = emptyList()
        )

        val btnDisabled = stateDisabled.objects["btn"]
        assertNotNull("btn must exist", btnDisabled)
        org.junit.Assert.assertFalse("touchEnabled must be false after sim_touch_disable", btnDisabled!!.touchEnabled)

        val scriptEnable = Script(
            id = "s_enable",
            name = "Enable",
            event = ScriptEvent.ON_START,
            blocks = listOf(enableTouch)
        )

        val stateEnabled = SimEngine.runTap(
            scriptId = "s_enable",
            scripts = listOf(scriptEnable),
            currentState = stateDisabled
        )

        val btnEnabled = stateEnabled.objects["btn"]
        assertNotNull("btn must exist", btnEnabled)
        org.junit.Assert.assertTrue("touchEnabled must be true after sim_touch_enable", btnEnabled!!.touchEnabled)
    }

    @Test
    fun testWaitTimerExecutesImmediatelyOnFirstTick() = runBlocking {
        val createObj = SerializedBlock(
            type = "sim_create",
            params = mapOf("name" to "spinner", "x" to "0", "y" to "0", "width" to "100", "height" to "100", "color" to "#FFFFFF")
        )
        val rotateBlock = SerializedBlock(
            type = "sim_rotate",
            params = mapOf("name" to "spinner", "mode" to "step", "angle" to "20")
        )
        val waitOpen = SerializedBlock(
            type = "wait_open",
            params = mapOf("seconds" to "0.05", "count" to "2")
        )
        val waitClose = SerializedBlock(type = "wait_close", params = emptyMap())

        val script = Script(
            id = "spin_script",
            name = "Spin",
            event = ScriptEvent.ON_START,
            blocks = listOf(createObj, waitOpen, rotateBlock, waitClose)
        )

        val updates = mutableListOf<Float>()
        val finalState = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = emptyList(),
            onUpdate = { live ->
                live.objects["spinner"]?.rotation?.let { updates.add(it) }
            }
        )

        val spinner = finalState.objects["spinner"]
        assertNotNull("spinner must exist", spinner)
        assertEquals("spinner must have rotated 2 * 20 = 40 degrees", 40f, spinner!!.rotation, 0.001f)
        org.junit.Assert.assertTrue("updates should capture rotations", updates.contains(20f) && updates.contains(40f))
    }

    @Test
    fun testRussianRouletteButtonResizeAndRestoreWithWaitOpen() = runBlocking {
        val createObj = SerializedBlock(
            type = "sim_create",
            params = mapOf("name" to "Play", "x" to "0", "y" to "0", "width" to "250", "height" to "100", "color" to "#FFFFFF")
        )
        val enlarge = SerializedBlock(
            type = "sim_resize",
            params = mapOf("name" to "Play", "width" to "300", "height" to "150")
        )
        val waitOpen = SerializedBlock(
            type = "wait_open",
            params = mapOf("seconds" to "0.02", "count" to "1")
        )
        val restore = SerializedBlock(
            type = "sim_resize",
            params = mapOf("name" to "Play", "width" to "250", "height" to "100")
        )
        val waitClose = SerializedBlock(type = "wait_close", params = emptyMap())

        val script = Script(
            id = "s_play",
            name = "Play",
            event = ScriptEvent.ON_START,
            blocks = listOf(createObj, enlarge, waitOpen, restore, waitClose)
        )

        val widths = mutableListOf<Float>()
        val finalState = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = emptyList(),
            onUpdate = { live ->
                live.objects["Play"]?.width?.let { widths.add(it) }
            }
        )

        val btn = finalState.objects["Play"]
        assertNotNull("btn must exist", btn)
        assertEquals("btn must be restored to 250", 250f, btn!!.width, 0.001f)
        org.junit.Assert.assertTrue("intermediate updates MUST have captured enlarged width 300: $widths", widths.contains(300f))
    }

    @Test
    fun testProject4ForLoopWithWaitOpen() = runBlocking {
        val forOpen = SerializedBlock(
            type = "for_loop_open",
            params = mapOf("count" to "5")
        )
        val setVar = SerializedBlock(
            type = "set_var",
            params = mapOf("name" to "Time_Intro", "value" to "\$add({Time_Intro}, 1)")
        )
        val waitOpen = SerializedBlock(
            type = "wait_open",
            params = mapOf("seconds" to "0.01", "count" to "1")
        )
        val forClose = SerializedBlock(type = "for_loop_close", params = emptyMap())
        val waitClose = SerializedBlock(type = "wait_close", params = emptyMap())

        val script = Script(
            id = "s_loop",
            name = "Intro",
            event = ScriptEvent.ON_START,
            blocks = listOf(forOpen, setVar, waitOpen, forClose, waitClose)
        )

        val finalState = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = listOf(su.SkrinVex.SkriCode.data.ProjectVar("Time_Intro", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "0"))
        )

        assertEquals("Time_Intro must be 5 after 5 iterations", "5", finalState.globalVars["Time_Intro"])
    }

    @Test
    fun testProject4ScreenHeightDivisionInLandscape() = runBlocking {
        ExprEval.updateDeviceResolution(1080f, 2400f)
        ExprEval.setOrientation(su.SkrinVex.SkriCode.data.ProjectOrientation.LANDSCAPE)

        val textBlock = SerializedBlock(
            type = "sim_text",
            params = mapOf(
                "name" to "ReadText",
                "text" to "None",
                "x" to "0",
                "y" to "0",
                "width" to "\$screenWidth",
                "height" to "\$screenHeight / 30",
                "size" to "16"
            )
        )

        val script = Script(
            id = "s_txt",
            name = "Text",
            event = ScriptEvent.ON_START,
            blocks = listOf(textBlock)
        )

        val finalState = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = emptyList()
        )

        val obj = finalState.objects["ReadText"]
        assertNotNull("ReadText must exist", obj)
        assertEquals("width must be screenWidth in landscape (2400)", 2400f, obj!!.width, 0.1f)
        assertEquals("height must be screenHeight / 30 in landscape (1080 / 30 = 36)", 36f, obj.height, 0.1f)
    }

    @Test
    fun testHierarchicalIfBlocksInElseBranch() = runBlocking {
        // Тест на иерархическую вложенность IfBlock внутри else ветки
        // If (choice == 1) then res="1" else [
        //   If (choice == 2) then res="2" else [
        //     If (choice == 3) then res="3"
        //   ]
        // ]
        fun createIfScript(): Script {
            val if3 = SerializedBlock(
                type = "if_block",
                params = mapOf("left" to "{choice}", "op" to "==", "right" to "3"),
                children = mapOf(
                    "then" to listOf(SerializedBlock(type = "set_var", params = mapOf("name" to "res", "value" to "3")))
                )
            )
            val if2 = SerializedBlock(
                type = "if_block",
                params = mapOf("left" to "{choice}", "op" to "==", "right" to "2"),
                children = mapOf(
                    "then" to listOf(SerializedBlock(type = "set_var", params = mapOf("name" to "res", "value" to "2"))),
                    "else" to listOf(if3)
                )
            )
            val if1 = SerializedBlock(
                type = "if_block",
                params = mapOf("left" to "{choice}", "op" to "==", "right" to "1"),
                children = mapOf(
                    "then" to listOf(SerializedBlock(type = "set_var", params = mapOf("name" to "res", "value" to "1"))),
                    "else" to listOf(if2)
                )
            )
            return Script(
                id = "s_nested_if",
                name = "NestedIf",
                event = ScriptEvent.ON_START,
                blocks = listOf(if1)
            )
        }

        // Проверяем для choice = 3
        val state3 = SimEngine.run(
            scripts = listOf(createIfScript()),
            globalVarDefs = listOf(
                su.SkrinVex.SkriCode.data.ProjectVar("choice", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "3"),
                su.SkrinVex.SkriCode.data.ProjectVar("res", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "0")
            )
        )
        assertEquals("When choice is 3, res must be 3", "3", state3.globalVars["res"])

        // Проверяем для choice = 2
        val state2 = SimEngine.run(
            scripts = listOf(createIfScript()),
            globalVarDefs = listOf(
                su.SkrinVex.SkriCode.data.ProjectVar("choice", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "2"),
                su.SkrinVex.SkriCode.data.ProjectVar("res", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "0")
            )
        )
        assertEquals("When choice is 2, res must be 2", "2", state2.globalVars["res"])

        // Проверяем для choice = 1
        val state1 = SimEngine.run(
            scripts = listOf(createIfScript()),
            globalVarDefs = listOf(
                su.SkrinVex.SkriCode.data.ProjectVar("choice", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "1"),
                su.SkrinVex.SkriCode.data.ProjectVar("res", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "0")
            )
        )
        assertEquals("When choice is 1, res must be 1", "1", state1.globalVars["res"])
    }

    @Test
    fun testWhileLoopExecution() = runBlocking {
        val whileOpen = SerializedBlock(
            type = "while_loop_open",
            params = mapOf("left" to "{i}", "op" to "<", "right" to "5")
        )
        val incVar = SerializedBlock(
            type = "set_var",
            params = mapOf("name" to "i", "value" to "\$add({i}, 1)")
        )
        val whileClose = SerializedBlock(type = "while_loop_close", params = emptyMap())

        val script = Script(
            id = "s_while",
            name = "WhileTest",
            event = ScriptEvent.ON_START,
            blocks = listOf(whileOpen, incVar, whileClose)
        )

        val finalState = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = listOf(su.SkrinVex.SkriCode.data.ProjectVar("i", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "0"))
        )

        assertEquals("i must be 5 after while loop", "5", finalState.globalVars["i"])
    }

    @Test
    fun testTableOperations() = runBlocking {
        val setTbl = SerializedBlock(
            type = "table_set",
            params = mapOf("table" to "highscore", "key" to "player1", "value" to "999")
        )
        val getTbl = SerializedBlock(
            type = "table_get",
            params = mapOf("table" to "highscore", "key" to "player1", "var" to "loadedScore")
        )

        val script = Script(
            id = "s_tbl",
            name = "TableTest",
            event = ScriptEvent.ON_START,
            blocks = listOf(setTbl, getTbl)
        )

        val finalState = SimEngine.run(
            scripts = listOf(script),
            globalVarDefs = listOf(su.SkrinVex.SkriCode.data.ProjectVar("loadedScore", su.SkrinVex.SkriCode.data.VarScope.GLOBAL, "0"))
        )

        assertEquals("Table value must be retrieved into loadedScore", "999", finalState.globalVars["loadedScore"])
    }

    @Test
    fun testTagSelectorModification() = runBlocking {
        val obj1 = SerializedBlock(type = "sim_create", params = mapOf("name" to "enemy1", "x" to "10", "y" to "20"))
        val obj2 = SerializedBlock(type = "sim_create", params = mapOf("name" to "enemy2", "x" to "30", "y" to "40"))
        val tag1 = SerializedBlock(type = "set_tag", params = mapOf("object" to "enemy1", "tag" to "mobs"))
        val tag2 = SerializedBlock(type = "set_tag", params = mapOf("object" to "enemy2", "tag" to "mobs"))
        val hideMobs = SerializedBlock(type = "sim_hide", params = mapOf("name" to "#mobs"))

        val script = Script(
            id = "s_tags",
            name = "TagTest",
            event = ScriptEvent.ON_START,
            blocks = listOf(obj1, obj2, tag1, tag2, hideMobs)
        )

        val finalState = SimEngine.run(scripts = listOf(script), globalVarDefs = emptyList())

        assertNotNull("enemy1 must exist", finalState.objects["enemy1"])
        assertNotNull("enemy2 must exist", finalState.objects["enemy2"])
        assertEquals("enemy1 must be hidden via #mobs tag", false, finalState.objects["enemy1"]?.visible)
        assertEquals("enemy2 must be hidden via #mobs tag", false, finalState.objects["enemy2"]?.visible)
    }

    @Test
    fun testTouchEnableDisable() = runBlocking {
        val create = SerializedBlock(type = "sim_create", params = mapOf("name" to "btn", "x" to "0", "y" to "0"))
        val disable = SerializedBlock(type = "sim_touch_disable", params = mapOf("name" to "btn"))

        val script1 = Script(id = "s_t1", name = "T1", event = ScriptEvent.ON_START, blocks = listOf(create, disable))
        val state1 = SimEngine.run(scripts = listOf(script1), globalVarDefs = emptyList())
        assertEquals("Touch must be disabled", false, state1.objects["btn"]?.touchEnabled)

        val enable = SerializedBlock(type = "sim_touch_enable", params = mapOf("name" to "btn"))
        val script2 = Script(id = "s_t2", name = "T2", event = ScriptEvent.ON_START, blocks = listOf(create, disable, enable))
        val state2 = SimEngine.run(scripts = listOf(script2), globalVarDefs = emptyList())
        assertEquals("Touch must be re-enabled", true, state2.objects["btn"]?.touchEnabled)
    }

    @Test
    fun testBlockReorderingIndexCalculation() {
        // [B0, B1, B2, B3, B4] (total = 5)
        fun calcNewIndex(currentIndex: Int, targetNum: Int, mode: String, totalBlocks: Int): Int {
            val target0 = targetNum - 1
            val newIndex = if (mode == "above") {
                if (currentIndex > target0) target0 else target0 - 1
            } else {
                if (currentIndex > target0) target0 + 1 else target0
            }
            return newIndex.coerceIn(0, totalBlocks - 1)
        }

        // Переместить блок 4 (index 3) НАД блоком 1 (index 0) -> target = 0
        assertEquals(0, calcNewIndex(currentIndex = 3, targetNum = 1, mode = "above", totalBlocks = 5))

        // Переместить блок 1 (index 0) ПОД блок 5 (index 4) -> target = 4
        assertEquals(4, calcNewIndex(currentIndex = 0, targetNum = 5, mode = "below", totalBlocks = 5))

        // Переместить блок 4 (index 3) ПОД блок 2 (index 1) -> target = 2
        assertEquals(2, calcNewIndex(currentIndex = 3, targetNum = 2, mode = "below", totalBlocks = 5))

        // Переместить блок 2 (index 1) НАД блоком 4 (index 3) -> target = 2
        assertEquals(2, calcNewIndex(currentIndex = 1, targetNum = 4, mode = "above", totalBlocks = 5))
    }
}
