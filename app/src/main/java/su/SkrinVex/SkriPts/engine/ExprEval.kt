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

        // Функции с аргументами
        val funcPatterns = listOf(
            "rand(" to ::handleRand,
            "add(" to ::handleAdd,
            "sub(" to ::handleSub,
            "mul(" to ::handleMul,
            "div(" to ::handleDiv,
            "abs(" to ::handleAbs,
            "min(" to ::handleMin,
            "max(" to ::handleMax,
            "and(" to ::handleAnd,
            "or(" to ::handleOr,
            "not(" to ::handleNot,
            "concat(" to ::handleConcat,
            "length(" to ::handleLength,
            "upper(" to ::handleUpper,
            "lower(" to ::handleLower
        )
        
        for ((pattern, handler) in funcPatterns) {
            if (sub.startsWith(pattern)) {
                val close = sub.indexOf(')')
                if (close == -1) return Triple("", 1, "Незакрытая скобка в \$${pattern.dropLast(1)}()")
                val args = sub.substring(pattern.length, close).split(",").map { it.trim() }
                val consumed = 1 + close + 1 // $ + функция до )
                return handler(args, consumed)
            }
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

    private fun handleRand(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$rand() требует два аргумента: \$rand(min, max)")
        val min = args[0].toIntOrNull() ?: return Triple("", consumed, "\$rand(): «${args[0]}» не число")
        val max = args[1].toIntOrNull() ?: return Triple("", consumed, "\$rand(): «${args[1]}» не число")
        if (min > max) return Triple("", consumed, "\$rand(): min > max")
        return Triple(Random.nextInt(min, max + 1).toString(), consumed, null)
    }

    private fun handleAdd(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$add() требует два аргумента")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$add(): «${args[0]}» не число")
        val b = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$add(): «${args[1]}» не число")
        return Triple(fmt(a + b), consumed, null)
    }

    private fun handleSub(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$sub() требует два аргумента")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$sub(): «${args[0]}» не число")
        val b = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$sub(): «${args[1]}» не число")
        return Triple(fmt(a - b), consumed, null)
    }

    private fun handleMul(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$mul() требует два аргумента")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$mul(): «${args[0]}» не число")
        val b = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$mul(): «${args[1]}» не число")
        return Triple(fmt(a * b), consumed, null)
    }

    private fun handleDiv(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$div() требует два аргумента")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$div(): «${args[0]}» не число")
        val b = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$div(): «${args[1]}» не число")
        if (b == 0.0) return Triple("", consumed, "\$div(): деление на ноль")
        return Triple(fmt(a / b), consumed, null)
    }

    private fun handleAbs(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$abs() требует один аргумент")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$abs(): «${args[0]}» не число")
        return Triple(fmt(abs(a)), consumed, null)
    }

    private fun handleMin(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$min() требует два аргумента")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$min(): «${args[0]}» не число")
        val b = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$min(): «${args[1]}» не число")
        return Triple(fmt(minOf(a, b)), consumed, null)
    }

    private fun handleMax(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$max() требует два аргумента")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$max(): «${args[0]}» не число")
        val b = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$max(): «${args[1]}» не число")
        return Triple(fmt(maxOf(a, b)), consumed, null)
    }

    private fun handleAnd(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$and() требует два аргумента")
        val a = args[0].toBooleanStrictOrNull() ?: return Triple("", consumed, "\$and(): «${args[0]}» не логическое значение")
        val b = args[1].toBooleanStrictOrNull() ?: return Triple("", consumed, "\$and(): «${args[1]}» не логическое значение")
        return Triple((a && b).toString(), consumed, null)
    }

    private fun handleOr(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$or() требует два аргумента")
        val a = args[0].toBooleanStrictOrNull() ?: return Triple("", consumed, "\$or(): «${args[0]}» не логическое значение")
        val b = args[1].toBooleanStrictOrNull() ?: return Triple("", consumed, "\$or(): «${args[1]}» не логическое значение")
        return Triple((a || b).toString(), consumed, null)
    }

    private fun handleNot(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$not() требует один аргумент")
        val a = args[0].toBooleanStrictOrNull() ?: return Triple("", consumed, "\$not(): «${args[0]}» не логическое значение")
        return Triple((!a).toString(), consumed, null)
    }

    private fun handleConcat(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$concat() требует два аргумента")
        return Triple(args[0] + args[1], consumed, null)
    }

    private fun handleLength(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$length() требует один аргумент")
        return Triple(args[0].length.toString(), consumed, null)
    }

    private fun handleUpper(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$upper() требует один аргумент")
        return Triple(args[0].uppercase(), consumed, null)
    }

    private fun handleLower(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$lower() требует один аргумент")
        return Triple(args[0].lowercase(), consumed, null)
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
