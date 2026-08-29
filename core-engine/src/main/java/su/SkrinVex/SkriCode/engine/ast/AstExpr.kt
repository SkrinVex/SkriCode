package su.SkrinVex.SkriCode.engine.ast

import su.SkrinVex.SkriCode.engine.ExprEval
import su.SkrinVex.SkriCode.engine.ExprScope
import su.SkrinVex.SkriCode.engine.SaveCrypto
import kotlin.math.*
import kotlin.random.Random

sealed interface AstExpr {
    fun evalFast(vars: Map<String, String>, scope: ExprScope): Any
    fun evalString(vars: Map<String, String>, scope: ExprScope): String
    fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double?
    fun evalFloat(vars: Map<String, String>, scope: ExprScope, default: Float = 0f): Float =
        evalDouble(vars, scope)?.toFloat() ?: default

    companion object {
        fun formatNumber(v: Double): String =
            if (v == floor(v) && abs(v) < 1e12) v.toLong().toString() else v.toString()
    }
}

data class LiteralNumber(val value: Double, val raw: String) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any = value
    override fun evalString(vars: Map<String, String>, scope: ExprScope): String = raw
    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double = value
}

data class LiteralString(val value: String) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any = value
    override fun evalString(vars: Map<String, String>, scope: ExprScope): String = value
    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double? = value.toDoubleOrNull()
}

data class VarRef(val varName: String) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any {
        val v = vars[varName] ?: ""
        return v.toDoubleOrNull() ?: v
    }

    override fun evalString(vars: Map<String, String>, scope: ExprScope): String {
        return vars[varName] ?: ""
    }

    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double? {
        val v = vars[varName] ?: return null
        return v.toDoubleOrNull()
    }
}

enum class ScreenConstType {
    WIDTH, HEIGHT, TOP, BOTTOM, RIGHT, LEFT
}

data class ScreenConst(val type: ScreenConstType) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any = evalDouble(vars, scope)
    override fun evalString(vars: Map<String, String>, scope: ExprScope): String =
        AstExpr.formatNumber(evalDouble(vars, scope))

    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double = when (type) {
        ScreenConstType.WIDTH -> ExprEval.screenWidth.toDouble()
        ScreenConstType.HEIGHT -> ExprEval.screenHeight.toDouble()
        ScreenConstType.TOP -> (ExprEval.screenHeight / 2f).toDouble()
        ScreenConstType.BOTTOM -> (-ExprEval.screenHeight / 2f).toDouble()
        ScreenConstType.RIGHT -> (ExprEval.screenWidth / 2f).toDouble()
        ScreenConstType.LEFT -> (-ExprEval.screenWidth / 2f).toDouble()
    }
}

data class TableRef(val tableName: String, val keyExpr: AstExpr?) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any = evalString(vars, scope)

    override fun evalString(vars: Map<String, String>, scope: ExprScope): String {
        val table = scope.tables[tableName] ?: return ""
        return if (keyExpr == null) {
            table.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else {
            val key = keyExpr.evalString(vars, scope)
            table[key] ?: ""
        }
    }

    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double? =
        evalString(vars, scope).toDoubleOrNull()
}

data class BinaryArith(val left: AstExpr, val op: Char, val right: AstExpr) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any {
        val num = evalDouble(vars, scope)
        return num ?: evalString(vars, scope)
    }

    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double? {
        val lv = left.evalDouble(vars, scope) ?: return null
        val rv = right.evalDouble(vars, scope) ?: return null
        return when (op) {
            '+' -> lv + rv
            '-' -> lv - rv
            '*' -> lv * rv
            '/' -> if (rv != 0.0) lv / rv else null
            '%' -> if (rv != 0.0) lv % rv else null
            else -> null
        }
    }

    override fun evalString(vars: Map<String, String>, scope: ExprScope): String {
        val num = evalDouble(vars, scope)
        if (num != null) return AstExpr.formatNumber(num)
        val ls = left.evalString(vars, scope)
        val rs = right.evalString(vars, scope)
        return if (op == '+') ls + rs else ""
    }
}

data class InterpolatedText(val parts: List<AstExpr>) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any {
        val str = evalString(vars, scope)
        return str.toDoubleOrNull() ?: str
    }

    override fun evalString(vars: Map<String, String>, scope: ExprScope): String {
        val sb = java.lang.StringBuilder()
        for (p in parts) {
            sb.append(p.evalString(vars, scope))
        }
        return sb.toString()
    }

    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double? =
        evalString(vars, scope).toDoubleOrNull()
}

data class BuiltinFunc(val name: String, val args: List<AstExpr>) : AstExpr {
    override fun evalFast(vars: Map<String, String>, scope: ExprScope): Any {
        val d = evalDouble(vars, scope)
        return d ?: evalString(vars, scope)
    }

    override fun evalString(vars: Map<String, String>, scope: ExprScope): String {
        val d = evalDouble(vars, scope)
        if (d != null) return AstExpr.formatNumber(d)
        return evaluateStringFunc(vars, scope)
    }

    override fun evalDouble(vars: Map<String, String>, scope: ExprScope): Double? {
        return when (name) {
            "add" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                a + b
            }
            "sub" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                a - b
            }
            "mul" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                a * b
            }
            "div" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                if (b == 0.0) 0.0 else a / b
            }
            "mod" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                if (b == 0.0) 0.0 else a % b
            }
            "pow" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                Math.pow(a, b)
            }
            "rand" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope)?.toInt() ?: 0
                val b = args.getOrNull(1)?.evalDouble(vars, scope)?.toInt() ?: 100
                Random.nextInt(min(a, b), max(a, b) + 1).toDouble()
            }
            "abs" -> args.firstOrNull()?.evalDouble(vars, scope)?.let { abs(it) }
            "min" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                min(a, b)
            }
            "max" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                max(a, b)
            }
            "sqrt" -> args.firstOrNull()?.evalDouble(vars, scope)?.takeIf { it >= 0.0 }?.let { sqrt(it) }
            "sin" -> args.firstOrNull()?.evalDouble(vars, scope)?.let { sin(Math.toRadians(it)) }
            "cos" -> args.firstOrNull()?.evalDouble(vars, scope)?.let { cos(Math.toRadians(it)) }
            "round" -> args.firstOrNull()?.evalDouble(vars, scope)?.let { round(it) }
            "floor" -> args.firstOrNull()?.evalDouble(vars, scope)?.let { floor(it) }
            "ceil" -> args.firstOrNull()?.evalDouble(vars, scope)?.let { ceil(it) }
            "clamp" -> {
                val v = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val minV = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                val maxV = args.getOrNull(2)?.evalDouble(vars, scope) ?: return null
                v.coerceIn(min(minV, maxV), max(minV, maxV))
            }
            "lerp" -> {
                val a = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val b = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                val t = args.getOrNull(2)?.evalDouble(vars, scope) ?: return null
                a + (b - a) * t
            }
            "dist" -> {
                val x1 = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val y1 = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                val x2 = args.getOrNull(2)?.evalDouble(vars, scope) ?: return null
                val y2 = args.getOrNull(3)?.evalDouble(vars, scope) ?: return null
                hypot(x2 - x1, y2 - y1)
            }
            "angle" -> {
                val x1 = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val y1 = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                val x2 = args.getOrNull(2)?.evalDouble(vars, scope) ?: return null
                val y2 = args.getOrNull(3)?.evalDouble(vars, scope) ?: return null
                Math.toDegrees(atan2(y2 - y1, x2 - x1))
            }
            "vlen" -> {
                val vx = args.getOrNull(0)?.evalDouble(vars, scope) ?: return null
                val vy = args.getOrNull(1)?.evalDouble(vars, scope) ?: return null
                hypot(vx, vy)
            }
            "length" -> args.firstOrNull()?.evalString(vars, scope)?.length?.toDouble()
            "objX" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                scope.objects[objName]?.x?.toDouble()
            }
            "objY" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                scope.objects[objName]?.y?.toDouble()
            }
            "objRot" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                scope.objects[objName]?.rotation?.toDouble()
            }
            "objVx" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                scope.objects[objName]?.physicsBody?.velocityX?.toDouble()
            }
            "objVy" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                scope.objects[objName]?.physicsBody?.velocityY?.toDouble()
            }
            "objDirX" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                val rot = scope.objects[objName]?.rotation ?: return null
                cos(Math.toRadians(rot.toDouble() - 90.0))
            }
            "objDirY" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                val rot = scope.objects[objName]?.rotation ?: return null
                -sin(Math.toRadians(rot.toDouble() - 90.0))
            }
            "objFrontX" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                val obj = scope.objects[objName] ?: return null
                val rad = Math.toRadians(obj.rotation.toDouble() - 90.0)
                obj.x + cos(rad) * (obj.height / 2f)
            }
            "objFrontY" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                val obj = scope.objects[objName] ?: return null
                val rad = Math.toRadians(obj.rotation.toDouble() - 90.0)
                obj.y - sin(rad) * (obj.height / 2f)
            }
            "tableSize" -> {
                val tName = args.firstOrNull()?.evalString(vars, scope) ?: return null
                scope.tables[tName]?.size?.toDouble()
            }
            else -> evaluateStringFunc(vars, scope).toDoubleOrNull()
        }
    }

    private fun evaluateStringFunc(vars: Map<String, String>, scope: ExprScope): String {
        return when (name) {
            "concat" -> args.joinToString("") { it.evalString(vars, scope) }
            "upper" -> args.firstOrNull()?.evalString(vars, scope)?.uppercase() ?: ""
            "lower" -> args.firstOrNull()?.evalString(vars, scope)?.lowercase() ?: ""
            "trim" -> args.firstOrNull()?.evalString(vars, scope)?.trim() ?: ""
            "tableKey" -> {
                val tName = args.getOrNull(0)?.evalString(vars, scope) ?: return ""
                val idx = args.getOrNull(1)?.evalDouble(vars, scope)?.toInt() ?: 0
                val t = scope.tables[tName] ?: return ""
                t.keys.toList().getOrNull(idx) ?: ""
            }
            "tableVal" -> {
                val tName = args.getOrNull(0)?.evalString(vars, scope) ?: return ""
                val idx = args.getOrNull(1)?.evalDouble(vars, scope)?.toInt() ?: 0
                val t = scope.tables[tName] ?: return ""
                t.values.toList().getOrNull(idx) ?: ""
            }
            "saveExists" -> {
                val key = args.firstOrNull()?.evalString(vars, scope) ?: return "false"
                val ctx = ExprEval.appContext
                val exists = if (ctx != null) SaveCrypto.hasKey(ctx, key) else false
                exists.toString()
            }
            "fieldVal", "inputVal" -> {
                val objName = args.firstOrNull()?.evalString(vars, scope)?.trim() ?: return ""
                val obj = scope.objects[objName] ?: return ""
                obj.label
            }
            else -> ""
        }
    }
}

data class ConditionExpr(val left: AstExpr, val op: String, val right: AstExpr) {
    fun evaluate(vars: Map<String, String>, scope: ExprScope): Boolean {
        val ld = left.evalDouble(vars, scope)
        val rd = right.evalDouble(vars, scope)

        if (ld != null && rd != null) {
            return when (op) {
                "==" -> ld == rd
                "!=" -> ld != rd
                ">" -> ld > rd
                "<" -> ld < rd
                ">=" -> ld >= rd
                "<=" -> ld <= rd
                else -> false
            }
        }

        val ls = left.evalString(vars, scope)
        val rs = right.evalString(vars, scope)
        return when (op) {
            "==" -> ls == rs
            "!=" -> ls != rs
            ">" -> ls > rs
            "<" -> ls < rs
            ">=" -> ls >= rs
            "<=" -> ls <= rs
            else -> false
        }
    }
}
