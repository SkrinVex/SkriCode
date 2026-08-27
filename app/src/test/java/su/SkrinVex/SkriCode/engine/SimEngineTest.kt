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
}
