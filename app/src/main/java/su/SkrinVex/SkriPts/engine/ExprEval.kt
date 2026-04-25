package su.SkrinVex.SkriPts.engine

import kotlin.math.abs
import kotlin.math.floor

/**
 * Вычислитель выражений.
 * Синтаксис:
 *   "100"            -> число
 *   "Привет"         -> строка
 *   "{x}"            -> значение переменной x
 *   "{x} + 50"       -> арифметика: подставляет x, считает
 *   "Счёт: {score}"  -> конкатенация строки и переменной
 *   "{a} + {b} * 2"  -> несколько переменных
 */
object ExprEval {

    data class EvalResult(val value: String, val error: String? = null)

    fun eval(expr: String, vars: Map<String, String>): EvalResult {
        if (expr.isBlank()) return EvalResult("")

        // Проверяем незакрытые скобки
        if (expr.contains('{') && !expr.contains('}'))
            return EvalResult("", "Незакрытая скобка { в выражении «$expr»")
        if (expr.contains('}') && !expr.contains('{'))
            return EvalResult("", "Лишняя скобка } в выражении «$expr»")

        // Подставляем переменные
        val (resolved, subErr) = substituteVars(expr, vars)
        if (subErr != null) return EvalResult("", subErr)

        // Пробуем арифметику
        val arith = tryArith(resolved.trim())
        if (arith != null) return EvalResult(arith)

        // Строка
        return EvalResult(resolved)
    }

    private fun substituteVars(expr: String, vars: Map<String, String>): Pair<String, String?> {
        val sb = StringBuilder()
        var i = 0
        while (i < expr.length) {
            if (expr[i] == '{') {
                val end = expr.indexOf('}', i)
                if (end == -1) return "" to "Незакрытая скобка { в «$expr»"
                val varName = expr.substring(i + 1, end).trim()
                if (varName.isBlank()) return "" to "Пустое имя переменной {}"
                val value = vars[varName]
                    ?: return "" to "Переменная «$varName» не объявлена. Создай её через блок «Переменная»"
                sb.append(value)
                i = end + 1
            } else {
                sb.append(expr[i++])
            }
        }
        return sb.toString() to null
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
}
