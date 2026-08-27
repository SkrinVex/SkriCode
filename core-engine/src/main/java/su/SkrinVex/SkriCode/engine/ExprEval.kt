package su.SkrinVex.SkriCode.engine

import su.SkrinVex.SkriCode.data.ProjectOrientation
import su.SkrinVex.SkriCode.data.SpriteAsset
import su.SkrinVex.SkriCode.engine.ast.AstExpr
import su.SkrinVex.SkriCode.engine.ast.ExprCompiler
import kotlin.math.abs
import kotlin.math.floor

object ExprEval {

    /** Системные переменные — всегда доступны, не требуют объявления */
    val SYSTEM_VARS = setOf(
        "collision_self",    // имя объекта которому принадлежит этот скрипт
        "collision_other",   // имя другого объекта (с кем столкнулись)
        // алиасы для удобства
        "collision_name",    // = collision_other (устаревший, оставлен для совместимости)
        "collision_x", "collision_y",
        "collision_width", "collision_height", "collision_rotation",
        "collision_self_x", "collision_self_y",
        "collision_self_width", "collision_self_height", "collision_self_rotation"
    )

    // Физические размеры экрана устройства (меньшая и большая стороны)
    var devicePhysicalWidth: Float = 1080f
    var devicePhysicalHeight: Float = 1920f

    // Текущая ориентация проекта
    var projectOrientation: ProjectOrientation = ProjectOrientation.PORTRAIT

    // Динамическая ширина экрана: для LANDSCAPE — большая сторона, для PORTRAIT — меньшая
    val screenWidth: Float
        get() {
            val minDim = minOf(devicePhysicalWidth, devicePhysicalHeight)
            val maxDim = maxOf(devicePhysicalWidth, devicePhysicalHeight)
            return if (projectOrientation == ProjectOrientation.LANDSCAPE) maxDim else minDim
        }

    // Динамическая высота экрана: для LANDSCAPE — меньшая сторона, для PORTRAIT — большая
    val screenHeight: Float
        get() {
            val minDim = minOf(devicePhysicalWidth, devicePhysicalHeight)
            val maxDim = maxOf(devicePhysicalWidth, devicePhysicalHeight)
            return if (projectOrientation == ProjectOrientation.LANDSCAPE) minDim else maxDim
        }

    fun updateDeviceResolution(w: Float, h: Float) {
        if (w > 0f && h > 0f) {
            devicePhysicalWidth = minOf(w, h)
            devicePhysicalHeight = maxOf(w, h)
        }
    }

    fun setOrientation(orientation: ProjectOrientation) {
        projectOrientation = orientation
    }

    val fallbackScope = ExprScope()

    var objects: Map<String, SimObject>
        get() = fallbackScope.objects
        set(value) { fallbackScope.objects = value }
    var joysticks: Map<String, JoystickState>
        get() = fallbackScope.joysticks
        set(value) { fallbackScope.joysticks = value }
    var tables: Map<String, Map<String, String>>
        get() = fallbackScope.tables
        set(value) { fallbackScope.tables = value }

    var sprites: List<SpriteAsset> = emptyList()
    var appContext: android.content.Context? = null

    fun eval(expr: String, vars: Map<String, String>, evalScope: ExprScope = fallbackScope): EvalResult {
        if (expr.isBlank()) return EvalResult("")

        if (expr.contains('{') && !expr.contains('}'))
            return EvalResult("", "Незакрытая скобка { в выражении «$expr»")
        if (expr.contains('}') && !expr.contains('{'))
            return EvalResult("", "Лишняя скобка } в выражении «$expr»")
        if (expr.contains('[') && !expr.contains(']'))
            return EvalResult("", "Незакрытая скобка [ в выражении «$expr»")

        return try {
            val ast = ExprCompiler.compile(expr)
            val res = ast.evalString(vars, evalScope)
            EvalResult(res)
        } catch (e: Exception) {
            EvalResult("", e.message ?: "Ошибка вычисления выражения «$expr»")
        }
    }

    fun validate(expr: String, vars: Map<String, String>): String? {
        if (expr.isBlank()) return null
        return eval(expr, vars).error
    }

    fun evalCondition(
        left: String,
        op: String,
        right: String,
        vars: Map<String, String>,
        evalScope: ExprScope = fallbackScope
    ): Pair<Boolean, String?> {
        val lRes = eval(left, vars, evalScope)
        if (lRes.error != null) return false to lRes.error
        val rRes = eval(right, vars, evalScope)
        if (rRes.error != null) return false to rRes.error

        val cond = ExprCompiler.compileCondition(left, op, right)
        return cond.evaluate(vars, evalScope) to null
    }

    fun fmt(v: Double): String = AstExpr.formatNumber(v)
}
