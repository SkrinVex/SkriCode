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
}
