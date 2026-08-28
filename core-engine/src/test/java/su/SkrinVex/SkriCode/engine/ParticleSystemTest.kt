package su.SkrinVex.SkriCode.engine

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleSystemTest {

    @Test
    fun testParticleBurst() {
        val particles = ParticleSystem.burst(
            x = 10f, y = 20f, count = 15,
            colorStart = Color.Yellow, colorEnd = Color.Red,
            speed = 100f, sizeStart = 10f, sizeEnd = 2f,
            lifetime = 1f, gravity = -50f
        )
        assertEquals(15, particles.size)
        particles.forEach { p ->
            assertEquals(10f, p.x, 0.001f)
            assertEquals(20f, p.y, 0.001f)
            assertTrue(p.life > 0f)
            assertEquals(-50f, p.gravity, 0.001f)
        }
    }

    @Test
    fun testParticleBurstLargeCount() {
        val particles = ParticleSystem.burst(
            x = 50f, y = 50f, count = 999,
            colorStart = Color.Yellow, colorEnd = Color.Red,
            speed = 150f, sizeStart = 12f, sizeEnd = 2f,
            lifetime = 0.8f, gravity = -100f
        )
        assertEquals(999, particles.size)
        particles.forEach { p ->
            assertEquals(50f, p.x, 0.001f)
            assertEquals(50f, p.y, 0.001f)
            assertTrue(p.life > 0f)
            assertEquals(-100f, p.gravity, 0.001f)
        }
    }

    @Test
    fun testParticleTick() {
        val particles = ParticleSystem.burst(
            x = 0f, y = 0f, count = 5,
            colorStart = Color.White, colorEnd = Color.Black,
            speed = 50f, sizeStart = 8f, sizeEnd = 2f,
            lifetime = 0.5f, gravity = 0f
        )
        val (nextParticles, _) = ParticleSystem.tick(particles, emptyMap(), emptyMap(), 0.1f)
        assertEquals(5, nextParticles.size)
        nextParticles.forEach { p ->
            assertTrue(p.life < 0.5f)
        }

        // After full expiration
        val (expiredParticles, _) = ParticleSystem.tick(nextParticles, emptyMap(), emptyMap(), 1.0f)
        assertEquals(0, expiredParticles.size)
    }

    @Test
    fun testTickAnimations() {
        val obj = SimObject(
            name = "hero", x = 0f, y = 0f, width = 32f, height = 32f, radius = 0f, color = Color.White,
            animCols = 4, animRows = 1, animFps = 10f, animPlaying = true, animLoop = true, animCurrentFrame = 0
        )
        val objs = mapOf("hero" to obj)

        // After 0.15s with 10 FPS (0.1s per frame), frame should advance to 1
        val updated = ParticleSystem.tickAnimations(objs, 0.15f)
        assertEquals(1, updated["hero"]!!.animCurrentFrame)
    }

    @Test
    fun testTickAnimations_MultiRowAndRange() {
        // 5 columns x 3 rows = 15 frames total (0..14)
        // Range 5..9 (Row 2), 10 FPS (0.1s per frame)
        val obj = SimObject(
            name = "slime", x = 0f, y = 0f, width = 64f, height = 64f, radius = 0f, color = Color.White,
            animCols = 5, animRows = 3, animStartFrame = 5, animEndFrame = 9,
            animFps = 10f, animPlaying = true, animLoop = true, animCurrentFrame = 5
        )
        val objs = mapOf("slime" to obj)

        // Advance 1 frame (0.12s)
        var next = ParticleSystem.tickAnimations(objs, 0.12f)
        assertEquals(6, next["slime"]!!.animCurrentFrame)

        // Advance 4 frames (total 5 frames from start -> should loop back to 5)
        next = ParticleSystem.tickAnimations(next, 0.45f)
        assertEquals(5, next["slime"]!!.animCurrentFrame)
    }

    @Test
    fun testTickAnimations_NoLoopStopsAtEndFrame() {
        // 4 frames, non-looping
        val obj = SimObject(
            name = "explosion", x = 0f, y = 0f, width = 32f, height = 32f, radius = 0f, color = Color.White,
            animCols = 4, animRows = 1, animStartFrame = 0, animEndFrame = 3,
            animFps = 10f, animPlaying = true, animLoop = false, animCurrentFrame = 0
        )
        val objs = mapOf("explosion" to obj)

        // Advance past all frames (0.5s = 5 frames)
        val next = ParticleSystem.tickAnimations(objs, 0.5f)
        assertEquals(3, next["explosion"]!!.animCurrentFrame)
        assertEquals(false, next["explosion"]!!.animPlaying)
    }

    @Test
    fun testTickAnimations_AccumulationAcrossMultipleSmallTicks() {
        // 10 FPS = 100ms per frame. Each tick is 16ms (60 FPS game loop).
        val obj = SimObject(
            name = "player", x = 0f, y = 0f, width = 32f, height = 32f, radius = 0f, color = Color.White,
            animCols = 4, animRows = 1, animFps = 10f, animPlaying = true, animLoop = true, animCurrentFrame = 0
        )
        var objs = mapOf("player" to obj)

        // Run 5 ticks (80ms total) -> still frame 0
        repeat(5) {
            objs = ParticleSystem.tickAnimations(objs, 0.016f)
        }
        assertEquals(0, objs["player"]!!.animCurrentFrame)

        // Run 2 more ticks (112ms total) -> frame advances to 1!
        repeat(2) {
            objs = ParticleSystem.tickAnimations(objs, 0.016f)
        }
        assertEquals(1, objs["player"]!!.animCurrentFrame)
    }

    @Test
    fun testTickShake() {
        val shake = ScreenShakeState(intensity = 20f, duration = 0.5f)
        val nextShake = ParticleSystem.tickShake(shake, 0.1f)
        assertTrue(nextShake.elapsed > 0f)
        assertTrue(nextShake.currentOffsetX != 0f || nextShake.currentOffsetY != 0f)

        // After expired
        val doneShake = ParticleSystem.tickShake(nextShake, 1.0f)
        assertEquals(0f, doneShake.currentOffsetX, 0.001f)
        assertEquals(0f, doneShake.currentOffsetY, 0.001f)
    }
}
