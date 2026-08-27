package su.SkrinVex.SkriCode.engine

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsWorldTest {

    @Test
    fun testPhysicsWorldTick_GravityAndMotion() {
        val obj = SimObject(
            name = "ball",
            x = 0f,
            y = 100f,
            width = 20f,
            height = 20f,
            radius = 10f,
            color = Color.Blue,
            physicsBody = PhysicsBody(
                enabled = true,
                gravity = -10f,
                isStatic = false,
                mass = 1f,
                velocityX = 5f,
                velocityY = 0f
            )
        )
        val state = SimState(objects = mapOf("ball" to obj), physicsEnabled = true)

        val (nextState, newCols, endedCols) = PhysicsWorld.tick(state)

        val updatedBall = nextState.objects["ball"]
        assertNotNull(updatedBall)
        assertEquals(5f, updatedBall!!.x, 0.001f)
        // vy changes by gravity * DT = -10 * 0.016 = -0.16
        // y changes by vy = 100 - 0.16 = 99.84
        assertEquals(-0.16f, updatedBall.physicsBody!!.velocityY, 0.001f)
        assertEquals(99.84f, updatedBall.y, 0.001f)
        assertTrue(newCols.isEmpty())
        assertTrue(endedCols.isEmpty())
    }

    @Test
    fun testPhysicsWorldCollisionAndRestitution() {
        // Moving dynamic ball falling onto static floor
        val ball = SimObject(
            name = "ball",
            x = 0f,
            y = 25f,
            width = 20f,
            height = 20f,
            radius = 10f,
            color = Color.Red,
            physicsBody = PhysicsBody(
                enabled = true,
                gravity = 0f,
                isStatic = false,
                mass = 1f,
                bounciness = 0.8f,
                velocityX = 0f,
                velocityY = -10f
            )
        )
        val floor = SimObject(
            name = "floor",
            x = 0f,
            y = 0f,
            width = 100f,
            height = 20f,
            radius = 0f,
            color = Color.Green,
            physicsBody = PhysicsBody(
                enabled = true,
                gravity = 0f,
                isStatic = true,
                mass = 1f,
                bounciness = 0.8f
            )
        )
        val state = SimState(objects = mapOf("ball" to ball, "floor" to floor), physicsEnabled = true)

        val (nextState, newCols, _) = PhysicsWorld.tick(state)
        val updatedBall = nextState.objects["ball"]!!
        assertNotNull(updatedBall)
        // Ball was moving down with -10f, collided with static floor (y=0, halfH=10, top=10).
        // It must have bounced upwards (positive vy)
        assertTrue("Ball should have positive velocity after bounce: ${updatedBall.physicsBody!!.velocityY}", updatedBall.physicsBody!!.velocityY > 0f)
        assertTrue("Collision should be recorded", newCols.contains("ball" to "floor") || newCols.contains("floor" to "ball"))
    }

    @Test
    fun testSeparatingObjectsDoNotStick() {
        // Two dynamic objects already moving apart should not attract each other
        val ballA = SimObject(
            name = "ballA",
            x = -5f,
            y = 0f,
            width = 20f,
            height = 20f,
            radius = 10f,
            color = Color.Red,
            physicsBody = PhysicsBody(
                enabled = true,
                gravity = 0f,
                isStatic = false,
                velocityX = -10f, // Moving left (away)
                velocityY = 0f
            )
        )
        val ballB = SimObject(
            name = "ballB",
            x = 5f,
            y = 0f,
            width = 20f,
            height = 20f,
            radius = 10f,
            color = Color.Blue,
            physicsBody = PhysicsBody(
                enabled = true,
                gravity = 0f,
                isStatic = false,
                velocityX = 10f, // Moving right (away)
                velocityY = 0f
            )
        )
        val state = SimState(objects = mapOf("ballA" to ballA, "ballB" to ballB), physicsEnabled = true)

        val (nextState, _, _) = PhysicsWorld.tick(state)
        val resA = nextState.objects["ballA"]!!
        val resB = nextState.objects["ballB"]!!

        // Velocities should not be flipped towards each other
        assertTrue("ballA should continue moving left", resA.physicsBody!!.velocityX <= -10f)
        assertTrue("ballB should continue moving right", resB.physicsBody!!.velocityX >= 10f)
    }
}
