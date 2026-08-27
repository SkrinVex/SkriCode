package su.SkrinVex.SkriCode.engine

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object ParticleSystem {

    const val MAX_PARTICLES = 300

    fun burst(
        x: Float,
        y: Float,
        count: Int,
        colorStart: Color,
        colorEnd: Color,
        speed: Float,
        sizeStart: Float,
        sizeEnd: Float,
        lifetime: Float,
        gravity: Float
    ): List<Particle> {
        val countClamped = count.coerceIn(1, 100)
        val list = ArrayList<Particle>(countClamped)
        for (i in 0 until countClamped) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val spd = speed * (0.3f + Random.nextFloat() * 0.7f)
            val vx = cos(angle) * spd
            val vy = sin(angle) * spd
            val life = lifetime * (0.6f + Random.nextFloat() * 0.4f)
            val rot = Random.nextFloat() * 360f
            val vRot = (Random.nextFloat() - 0.5f) * 720f
            list.add(
                Particle(
                    x = x,
                    y = y,
                    vx = vx,
                    vy = vy,
                    life = life,
                    maxLife = life,
                    sizeStart = sizeStart,
                    sizeEnd = sizeEnd,
                    colorStart = colorStart,
                    colorEnd = colorEnd,
                    gravity = gravity,
                    rotation = rot,
                    vRot = vRot
                )
            )
        }
        return list
    }

    fun tick(
        particles: List<Particle>,
        emitters: Map<String, ParticleEmitterState>,
        objects: Map<String, SimObject>,
        dt: Float
    ): Pair<List<Particle>, Map<String, ParticleEmitterState>> {
        val nextParticles = ArrayList<Particle>(particles.size + 16)

        // 1. Обновляем существующие частицы
        for (p in particles) {
            val remainingLife = p.life - dt
            if (remainingLife <= 0f) continue

            val nextVy = p.vy + p.gravity * dt
            val nextX = p.x + p.vx * dt
            val nextY = p.y + nextVy * dt
            val nextRot = (p.rotation + p.vRot * dt) % 360f

            nextParticles.add(
                p.copy(
                    x = nextX,
                    y = nextY,
                    vy = nextVy,
                    life = remainingLife,
                    rotation = nextRot
                )
            )
        }

        // 2. Обновляем эмиттеры и создаём новые частицы
        val nextEmitters = HashMap<String, ParticleEmitterState>(emitters.size)
        for ((name, emitter) in emitters) {
            if (!emitter.enabled) {
                nextEmitters[name] = emitter
                continue
            }

            var emX = emitter.x
            var emY = emitter.y
            if (emitter.targetName.isNotBlank()) {
                val target = objects[emitter.targetName]
                if (target != null) {
                    emX = target.x
                    emY = target.y
                }
            }

            val interval = if (emitter.rate > 0f) 1f / emitter.rate else 1f
            var timer = emitter.timer + dt

            while (timer >= interval && nextParticles.size < MAX_PARTICLES) {
                timer -= interval
                val newBorn = burst(
                    x = emX,
                    y = emY,
                    count = emitter.countPerEmission,
                    colorStart = emitter.colorStart,
                    colorEnd = emitter.colorEnd,
                    speed = emitter.speed,
                    sizeStart = emitter.sizeStart,
                    sizeEnd = emitter.sizeEnd,
                    lifetime = emitter.lifetime,
                    gravity = emitter.gravity
                )
                nextParticles.addAll(newBorn)
            }

            nextEmitters[name] = emitter.copy(timer = timer)
        }

        val finalParticles = if (nextParticles.size > MAX_PARTICLES) {
            nextParticles.subList(nextParticles.size - MAX_PARTICLES, nextParticles.size)
        } else nextParticles

        return Pair(finalParticles, nextEmitters)
    }

    fun tickShake(shake: ScreenShakeState, dt: Float): ScreenShakeState {
        if (shake.duration <= 0f || shake.elapsed >= shake.duration) {
            if (shake.currentOffsetX == 0f && shake.currentOffsetY == 0f) return shake
            return ScreenShakeState()
        }

        val elapsed = shake.elapsed + dt
        val progress = (elapsed / shake.duration).coerceIn(0f, 1f)
        val damping = 1f - progress
        val currentIntensity = shake.intensity * damping

        val offX = (Random.nextFloat() * 2f - 1f) * currentIntensity
        val offY = (Random.nextFloat() * 2f - 1f) * currentIntensity

        return shake.copy(
            elapsed = elapsed,
            currentOffsetX = offX,
            currentOffsetY = offY
        )
    }

    fun tickFlash(flash: ScreenFlashState, dt: Float): ScreenFlashState {
        if (!flash.active || flash.duration <= 0f || flash.elapsed >= flash.duration) {
            if (!flash.active) return flash
            return ScreenFlashState()
        }
        val elapsed = flash.elapsed + dt
        return flash.copy(elapsed = elapsed, active = elapsed < flash.duration)
    }

    fun tickAnimations(objects: Map<String, SimObject>, dt: Float): Map<String, SimObject> {
        var anyChanged = false
        val next = HashMap<String, SimObject>(objects.size)

        for ((name, obj) in objects) {
            if (!obj.animPlaying || obj.animFps <= 0f || obj.animCols <= 0 || obj.animRows <= 0) {
                next[name] = obj
                continue
            }

            val totalFrames = obj.animCols * obj.animRows
            val start = obj.animStartFrame.coerceIn(0, totalFrames - 1)
            val end = if (obj.animEndFrame > start && obj.animEndFrame < totalFrames) obj.animEndFrame else totalFrames - 1
            val frameCount = end - start + 1
            if (frameCount <= 1) {
                next[name] = obj
                continue
            }

            val frameDuration = 1f / obj.animFps
            var elapsed = obj.animElapsed + dt
            var curFrame = obj.animCurrentFrame

            if (elapsed >= frameDuration) {
                val advance = (elapsed / frameDuration).toInt()
                elapsed %= frameDuration

                var newFrameIdx = (curFrame - start) + advance
                if (obj.animLoop) {
                    newFrameIdx %= frameCount
                    curFrame = start + newFrameIdx
                } else {
                    if (newFrameIdx >= frameCount) {
                        curFrame = end
                        next[name] = obj.copy(animPlaying = false, animCurrentFrame = curFrame, animElapsed = 0f)
                        anyChanged = true
                        continue
                    } else {
                        curFrame = start + newFrameIdx
                    }
                }
                anyChanged = true
            }

            next[name] = obj.copy(animCurrentFrame = curFrame, animElapsed = elapsed)
        }

        return if (anyChanged) next else objects
    }
}
