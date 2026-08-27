package su.SkrinVex.SkriPts.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Высокопроизводительный 2D физический решатель (Pure Kotlin, без JNI).
 *
 * Архитектура:
 * 1. Отсутствие JNI overhead и копирования структур каждый тик.
 * 2. Двухуровневое обнаружение: Broadphase (Spatial Hash Grid) + Narrowphase (SAT для AABB и полигонов).
 * 3. Потокобезопасный итеративный импульсный решатель с корректным отскоком (bounciness) и разделением масс.
 */
object PhysicsWorld {
    const val DT = 0.016f
    private const val SOLVER_ITERS = 3
    private const val GRID_CELL = 160f
    private const val GRID_THRESHOLD = 24
    private const val MAX_VERTS = 24
    private const val PENETRATION_SLOP = 0.1f

    private val lock = Any()

    private var cap = 0
    private var names = arrayOfNulls<String>(0)
    private var srcObjs = arrayOfNulls<SimObject>(0)
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var mass = FloatArray(0)
    private var bounce = FloatArray(0)
    private var gravity = FloatArray(0)
    private var rot = FloatArray(0)
    private var halfW = FloatArray(0)
    private var halfH = FloatArray(0)
    private var aabbMinX = FloatArray(0)
    private var aabbMinY = FloatArray(0)
    private var aabbMaxX = FloatArray(0)
    private var aabbMaxY = FloatArray(0)
    private var isStatic = BooleanArray(0)
    private var isDynamic = BooleanArray(0)
    private var changed = BooleanArray(0)
    private var vertCount = IntArray(0)
    private var vertX = FloatArray(0)
    private var vertY = FloatArray(0)

    private val pairSet = HashSet<Long>(64)
    private var pairI = IntArray(64)
    private var pairJ = IntArray(64)
    private var pairCount = 0

    private val grid = HashMap<Long, ArrayList<Int>>(64)
    private val gridPool = ArrayList<ArrayList<Int>>(32)

    fun tick(state: SimState): Triple<SimState, Set<Pair<String, String>>, Set<Pair<String, String>>> = synchronized(lock) {
        if (!state.physicsEnabled) {
            return clearCollisionsIfNeeded(state)
        }
        val src = state.objects
        if (src.isEmpty()) return clearCollisionsIfNeeded(state)

        ensureCap(src.size)
        var n = 0
        var dynamicCount = 0
        for (obj in src.values) {
            val body = obj.physicsBody ?: continue
            if (!body.enabled || !obj.visible) continue
            names[n] = obj.name
            srcObjs[n] = obj
            px[n] = obj.x
            py[n] = obj.y
            vx[n] = body.velocityX
            vy[n] = body.velocityY
            mass[n] = max(body.mass, 0.01f)
            bounce[n] = body.bounciness.coerceIn(0f, 1f)
            gravity[n] = body.gravity
            rot[n] = obj.rotation
            halfW[n] = obj.width * 0.5f
            halfH[n] = obj.height * 0.5f
            isStatic[n] = body.isStatic
            isDynamic[n] = !body.isStatic
            changed[n] = false
            if (isDynamic[n]) dynamicCount++
            n++
        }

        if (n == 0 || dynamicCount == 0) return clearCollisionsIfNeeded(state)

        // Интегрируем скорости и гравитацию
        for (i in 0 until n) {
            if (!isDynamic[i]) continue
            vy[i] += gravity[i] * DT
            px[i] += vx[i]
            py[i] += vy[i]
            changed[i] = true
        }

        val currentCollisions = LinkedHashSet<Pair<String, String>>()
        repeat(SOLVER_ITERS) {
            rebuildBounds(n)
            collectPairs(n)
            for (p in 0 until pairCount) {
                val i = pairI[p]
                val j = pairJ[p]
                val a = srcObjs[i] ?: continue
                val b = srcObjs[j] ?: continue
                if (isStatic[i] && isStatic[j]) continue
                if (ignores(a, b) || ignores(b, a)) continue

                val hit = collide(i, j) ?: continue
                val nameA = names[i]!!
                val nameB = names[j]!!
                currentCollisions += if (nameA < nameB) nameA to nameB else nameB to nameA
                resolve(i, j, hit)
            }
        }

        val newCollisions = currentCollisions - state.activeCollisions
        val endedCollisions = state.activeCollisions - currentCollisions

        var anyChanged = false
        for (i in 0 until n) {
            if (changed[i]) {
                anyChanged = true
                break
            }
        }

        if (!anyChanged && currentCollisions == state.activeCollisions) {
            return Triple(state, emptySet(), emptySet())
        }

        val objects = if (anyChanged) {
            val next = HashMap(src)
            for (i in 0 until n) {
                if (!changed[i]) continue
                val obj = srcObjs[i] ?: continue
                val body = obj.physicsBody ?: continue
                next[obj.name] = obj.copy(
                    x = px[i],
                    y = py[i],
                    physicsBody = body.copy(velocityX = vx[i], velocityY = vy[i])
                )
            }
            next
        } else src

        return Triple(state.copy(objects = objects, activeCollisions = currentCollisions), newCollisions, endedCollisions)
    }

    private fun clearCollisionsIfNeeded(state: SimState): Triple<SimState, Set<Pair<String, String>>, Set<Pair<String, String>>> {
        if (state.activeCollisions.isEmpty()) return Triple(state, emptySet(), emptySet())
        return Triple(state.copy(activeCollisions = emptySet()), emptySet(), state.activeCollisions)
    }

    private fun ignores(obj: SimObject, other: SimObject): Boolean {
        if (other.name in obj.collisionIgnore) return true
        return obj.collisionIgnore.any { it.startsWith("#") && it.substring(1) in other.tags }
    }

    private fun rebuildBounds(n: Int) {
        for (i in 0 until n) fillShape(i)
    }

    private fun fillShape(i: Int) {
        val obj = srcObjs[i] ?: return
        val hb = obj.hitbox
        val cx = px[i]
        val cy = py[i]
        val rad = Math.toRadians(rot[i].toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val base = i * MAX_VERTS
        var count = 0

        fun addLocal(lx: Float, ly: Float) {
            if (count >= MAX_VERTS) return
            vertX[base + count] = cx + lx * c + ly * s
            vertY[base + count] = cy - lx * s + ly * c
            count++
        }

        val manual = hb.type == HitboxType.MANUAL && hb.points.size >= 3
        if (manual) {
            for (pt in hb.points) addLocal(pt.first, pt.second)
        } else {
            val hw = halfW[i]
            val hh = halfH[i]
            addLocal(-hw, -hh)
            addLocal(hw, -hh)
            addLocal(hw, hh)
            addLocal(-hw, hh)
        }
        vertCount[i] = count
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (k in 0 until count) {
            val x = vertX[base + k]
            val y = vertY[base + k]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        aabbMinX[i] = minX
        aabbMinY[i] = minY
        aabbMaxX[i] = maxX
        aabbMaxY[i] = maxY
    }

    private fun collectPairs(n: Int) {
        pairSet.clear()
        pairCount = 0
        if (n <= GRID_THRESHOLD) {
            for (i in 0 until n) {
                for (j in i + 1 until n) addPair(i, j)
            }
            return
        }
        clearGrid()
        val inv = 1f / GRID_CELL
        for (i in 0 until n) {
            val x0 = floor(aabbMinX[i] * inv).toInt()
            val y0 = floor(aabbMinY[i] * inv).toInt()
            val x1 = floor(aabbMaxX[i] * inv).toInt()
            val y1 = floor(aabbMaxY[i] * inv).toInt()
            var cy = y0
            while (cy <= y1) {
                var cx = x0
                while (cx <= x1) {
                    val key = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
                    val cell = grid.getOrPut(key) { borrowCell() }
                    for (other in cell) addPair(i, other)
                    cell += i
                    cx++
                }
                cy++
            }
        }
    }

    private fun addPair(i: Int, j: Int) {
        val a = min(i, j)
        val b = max(i, j)
        if (a == b) return
        val key = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
        if (!pairSet.add(key)) return
        if (pairCount >= pairI.size) {
            val nc = pairI.size * 2
            pairI = pairI.copyOf(nc)
            pairJ = pairJ.copyOf(nc)
        }
        pairI[pairCount] = a
        pairJ[pairCount] = b
        pairCount++
    }

    private fun collide(i: Int, j: Int): Hit? {
        if (aabbMaxX[i] <= aabbMinX[j] || aabbMaxX[j] <= aabbMinX[i]) return null
        if (aabbMaxY[i] <= aabbMinY[j] || aabbMaxY[j] <= aabbMinY[i]) return null

        val ci = vertCount[i]
        val cj = vertCount[j]
        if (ci < 3 || cj < 3) return null

        var minPen = Float.POSITIVE_INFINITY
        var nx = 0f
        var ny = 0f
        val axisOk = testAxes(i, ci, j, cj) { pen, ax, ay ->
            if (pen < minPen) {
                minPen = pen
                nx = ax
                ny = ay
            }
        }
        if (!axisOk) return null
        val dx = px[j] - px[i]
        val dy = py[j] - py[i]
        if (nx * dx + ny * dy < 0f) {
            nx = -nx
            ny = -ny
        }
        return Hit(minPen, nx, ny)
    }

    private inline fun testAxes(
        i: Int, ci: Int, j: Int, cj: Int,
        onPen: (Float, Float, Float) -> Unit
    ): Boolean {
        if (!projectAxes(i, ci, j, cj, onPen)) return false
        if (!projectAxes(j, cj, i, ci, onPen)) return false
        return true
    }

    private inline fun projectAxes(
        owner: Int, count: Int, other: Int, otherCount: Int,
        onPen: (Float, Float, Float) -> Unit
    ): Boolean {
        val base = owner * MAX_VERTS
        for (k in 0 until count) {
            val x1 = vertX[base + k]
            val y1 = vertY[base + k]
            val x2 = vertX[base + (k + 1) % count]
            val y2 = vertY[base + (k + 1) % count]
            var ax = -(y2 - y1)
            var ay = x2 - x1
            val len = sqrt(ax * ax + ay * ay)
            if (len < 1e-6f) continue
            ax /= len
            ay /= len

            val (minA, maxA) = projectRange(owner, count, ax, ay)
            val (minB, maxB) = projectRange(other, otherCount, ax, ay)
            val overlap = min(maxA, maxB) - max(minA, minB)
            if (overlap <= 0f) return false
            onPen(overlap, ax, ay)
        }
        return true
    }

    private fun projectRange(idx: Int, count: Int, ax: Float, ay: Float): Pair<Float, Float> {
        val base = idx * MAX_VERTS
        var minV = vertX[base] * ax + vertY[base] * ay
        var maxV = minV
        for (k in 1 until count) {
            val v = vertX[base + k] * ax + vertY[base + k] * ay
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        return Pair(minV, maxV)
    }

    private fun resolve(i: Int, j: Int, hit: Hit) {
        val aStatic = isStatic[i]
        val bStatic = isStatic[j]
        val invMassA = if (aStatic) 0f else 1f / mass[i]
        val invMassB = if (bStatic) 0f else 1f / mass[j]
        val totalInvMass = invMassA + invMassB
        if (totalInvMass <= 0f) return

        val aRatio = invMassA / totalInvMass
        val bRatio = invMassB / totalInvMass
        val push = hit.pen + PENETRATION_SLOP

        // Разведение перекрывающихся тел
        if (!aStatic) {
            px[i] -= hit.nx * push * aRatio
            py[i] -= hit.ny * push * aRatio
            changed[i] = true
        }
        if (!bStatic) {
            px[j] += hit.nx * push * bRatio
            py[j] += hit.ny * push * bRatio
            changed[j] = true
        }

        // Относительная скорость вдоль нормали (hit.nx/ny направлена от i к j)
        val relVx = vx[i] - vx[j]
        val relVy = vy[i] - vy[j]
        val relN = relVx * hit.nx + relVy * hit.ny

        // Накладываем импульс только если тела сближаются
        if (relN > 0f) {
            val rest = (bounce[i] + bounce[j]) * 0.5f
            val impulse = (1f + rest) * relN / totalInvMass
            if (!aStatic) {
                vx[i] -= impulse * invMassA * hit.nx
                vy[i] -= impulse * invMassA * hit.ny
                changed[i] = true
            }
            if (!bStatic) {
                vx[j] += impulse * invMassB * hit.nx
                vy[j] += impulse * invMassB * hit.ny
                changed[j] = true
            }
        }
    }

    private fun ensureCap(min: Int) {
        if (min <= cap) return
        cap = max(16, min * 2)
        names = arrayOfNulls(cap)
        srcObjs = arrayOfNulls(cap)
        px = FloatArray(cap)
        py = FloatArray(cap)
        vx = FloatArray(cap)
        vy = FloatArray(cap)
        mass = FloatArray(cap)
        bounce = FloatArray(cap)
        gravity = FloatArray(cap)
        rot = FloatArray(cap)
        halfW = FloatArray(cap)
        halfH = FloatArray(cap)
        aabbMinX = FloatArray(cap)
        aabbMinY = FloatArray(cap)
        aabbMaxX = FloatArray(cap)
        aabbMaxY = FloatArray(cap)
        isStatic = BooleanArray(cap)
        isDynamic = BooleanArray(cap)
        changed = BooleanArray(cap)
        vertCount = IntArray(cap)
        vertX = FloatArray(cap * MAX_VERTS)
        vertY = FloatArray(cap * MAX_VERTS)
    }

    private fun clearGrid() {
        val it = grid.values.iterator()
        while (it.hasNext()) {
            val cell = it.next()
            cell.clear()
            if (gridPool.size < 64) gridPool += cell
        }
        grid.clear()
    }

    private fun borrowCell(): ArrayList<Int> {
        val last = gridPool.size - 1
        return if (last >= 0) gridPool.removeAt(last) else ArrayList(4)
    }

    private data class Hit(val pen: Float, val nx: Float, val ny: Float)
}
