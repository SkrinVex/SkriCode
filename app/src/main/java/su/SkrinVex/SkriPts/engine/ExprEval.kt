package su.SkrinVex.SkriPts.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * Вычислитель выражений.
 * Синтаксис:
 *   "100"              -> число
 *   "{x}"              -> значение переменной x
 *   "{x} + 50"         -> арифметика
 *   "$screenWidth"     -> ширина экрана (пикселей)
 *   "$rand(min, max)"  -> случайное целое в диапазоне
 */
object ExprEval {

    // Размеры экрана — устанавливаются из Activity при старте
    var screenWidth: Float = 1080f
    var screenHeight: Float = 1920f

    data class EvalResult(val value: String, val error: String? = null)

    fun eval(expr: String, vars: Map<String, String>): EvalResult {
        if (expr.isBlank()) return EvalResult("")

        if (expr.contains('{') && !expr.contains('}'))
            return EvalResult("", "Незакрытая скобка { в выражении «$expr»")
        if (expr.contains('}') && !expr.contains('{'))
            return EvalResult("", "Лишняя скобка } в выражении «$expr»")

        // Подставляем встроенные функции и переменные
        val (resolved, subErr) = substitute(expr, vars)
        if (subErr != null) return EvalResult("", subErr)

        val arith = tryArith(resolved.trim())
        if (arith != null) return EvalResult(arith)

        return EvalResult(resolved)
    }

    private fun substitute(expr: String, vars: Map<String, String>): Pair<String, String?> {
        val sb = StringBuilder()
        var i = 0
        while (i < expr.length) {
            when {
                expr[i] == '$' -> {
                    // Встроенная функция/константа
                    val (result, consumed, err) = resolveBuiltin(expr, i)
                    if (err != null) return "" to err
                    sb.append(result)
                    i += consumed
                }
                expr[i] == '{' -> {
                    val end = expr.indexOf('}', i)
                    if (end == -1) return "" to "Незакрытая скобка { в «$expr»"
                    val varName = expr.substring(i + 1, end).trim()
                    if (varName.isBlank()) return "" to "Пустое имя переменной {}"
                    val value = vars[varName]
                        ?: return "" to "Переменная «$varName» не объявлена. Создай её через блок «Переменная»"
                    sb.append(value)
                    i = end + 1
                }
                else -> sb.append(expr[i++])
            }
        }
        return sb.toString() to null
    }

    /** Возвращает (подставленное значение, сколько символов потреблено, ошибка) */
    private fun resolveBuiltin(expr: String, start: Int): Triple<String, Int, String?> {
        val sub = expr.substring(start + 1) // после $

        // $rand(min, max)
        if (sub.startsWith("rand(")) {
            val close = sub.indexOf(')')
            if (close == -1) return Triple("", 1, "Незакрытая скобка в \$rand()")
            val args = sub.substring(5, close).split(",")
            if (args.size != 2) return Triple("", 1, "\$rand() требует два аргумента: \$rand(min, max)")
            val min = args[0].trim().toIntOrNull() ?: return Triple("", 1, "\$rand(): «${args[0].trim()}» не число")
            val max = args[1].trim().toIntOrNull() ?: return Triple("", 1, "\$rand(): «${args[1].trim()}» не число")
            if (min > max) return Triple("", 1, "\$rand(): min > max")
            val consumed = 1 + 5 + close + 1  // $ + "rand(" + content + ")"
            return Triple(Random.nextInt(min, max + 1).toString(), consumed, null)
        }

        // Константы
        val constants = mapOf(
            "screenWidth"  to fmt(screenWidth.toDouble()),
            "screenHeight" to fmt(screenHeight.toDouble()),
            "screenTop"    to fmt((screenHeight / 2).toDouble()),
            "screenBottom" to fmt((-screenHeight / 2).toDouble()),
            "screenRight"  to fmt((screenWidth / 2).toDouble()),
            "screenLeft"   to fmt((-screenWidth / 2).toDouble()),
        )
        for ((name, value) in constants) {
            if (sub.startsWith(name) && (sub.length == name.length || !sub[name.length].isLetterOrDigit())) {
                return Triple(value, 1 + name.length, null)
            }
        }

        // Неизвестный идентификатор — оставляем как есть (не ломаем строку)
        return Triple("$", 1, null)
    }

    private fun tryArith(expr: String): String? {
        if (expr.toDoubleOrNull() != null) return fmt(expr.toDouble())
        return evalAddSub(expr)
    }

    private fun evalAddSub(expr: String): String? {
        var i = expr.length - 1
        var depth = 0
        while (i >= 0) {
            when (expr[i]) {
                ')' -> depth++
                '(' -> depth--
                '+', '-' -> if (depth == 0 && i > 0) {
                    val l = evalAddSub(expr.substring(0, i)) ?: return null
                    val r = evalMulDiv(expr.substring(i + 1)) ?: return null
                    val lv = l.toDoubleOrNull() ?: return null
                    val rv = r.toDoubleOrNull() ?: return null
                    return fmt(if (expr[i] == '+') lv + rv else lv - rv)
                }
            }
            i--
        }
        return evalMulDiv(expr)
    }

    private fun evalMulDiv(expr: String): String? {
        var i = expr.length - 1
        var depth = 0
        while (i >= 0) {
            when (expr[i]) {
                ')' -> depth++
                '(' -> depth--
                '*', '/', '%' -> if (depth == 0) {
                    val l = evalMulDiv(expr.substring(0, i)) ?: return null
                    val r = evalAtom(expr.substring(i + 1)) ?: return null
                    val lv = l.toDoubleOrNull() ?: return null
                    val rv = r.toDoubleOrNull() ?: return null
                    return fmt(when (expr[i]) {
                        '*' -> lv * rv
                        '/' -> if (rv != 0.0) lv / rv else return null
                        '%' -> if (rv != 0.0) lv % rv else return null
                        else -> return null
                    })
                }
            }
            i--
        }
        return evalAtom(expr)
    }

    private fun evalAtom(expr: String): String? {
        val t = expr.trim()
        if (t.startsWith("(") && t.endsWith(")")) return evalAddSub(t.substring(1, t.length - 1))
        return t.toDoubleOrNull()?.let { fmt(it) }
    }

    private fun fmt(v: Double): String =
        if (v == floor(v) && abs(v) < 1e12) v.toLong().toString() else v.toString()

    /** Валидация без вычисления — возвращает текст ошибки или null */
    fun validate(expr: String, vars: Map<String, String>): String? {
        if (expr.isBlank()) return null
        return eval(expr, vars).error
    }

    /**
     * Вычисляет условие: left op right
     * Операторы: == != > < >= <=
     * Сравнение числовое если оба — числа, иначе строковое.
     */
    fun evalCondition(left: String, op: String, right: String, vars: Map<String, String>): Pair<Boolean, String?> {
        val lRes = eval(left, vars)
        if (lRes.error != null) return false to lRes.error
        val rRes = eval(right, vars)
        if (rRes.error != null) return false to rRes.error

        val lv = lRes.value
        val rv = rRes.value

        val ln = lv.toDoubleOrNull()
        val rn = rv.toDoubleOrNull()

        return if (ln != null && rn != null) {
            val result = when (op) {
                "==" -> ln == rn
                "!=" -> ln != rn
                ">"  -> ln > rn
                "<"  -> ln < rn
                ">=" -> ln >= rn
                "<=" -> ln <= rn
                else -> return false to "Неизвестный оператор «$op»"
            }
            result to null
        } else {
            val result = when (op) {
                "==" -> lv == rv
                "!=" -> lv != rv
                ">"  -> lv > rv
                "<"  -> lv < rv
                ">=" -> lv >= rv
                "<=" -> lv <= rv
                else -> return false to "Неизвестный оператор «$op»"
            }
            result to null
        }
    }
}
