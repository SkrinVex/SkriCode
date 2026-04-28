package su.SkrinVex.SkriPts.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
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

    // Размеры экрана — устанавливаются из Activity при старте
    var screenWidth: Float = 1080f
    var screenHeight: Float = 1920f

    // Объекты симуляции — обновляются из SimEngine перед каждым вычислением
    var objects: Map<String, SimObject> = emptyMap()
    var joysticks: Map<String, JoystickState> = emptyMap()
    // Таблицы — обновляются из SimEngine перед каждым вычислением
    var tables: Map<String, Map<String, String>> = emptyMap()
    // Спрайты — обновляются из SimEngine
    var sprites: List<su.SkrinVex.SkriPts.data.SpriteAsset> = emptyList()
    // Контекст для проверки сохранений
    var appContext: android.content.Context? = null

    data class EvalResult(val value: String, val error: String? = null)

    fun eval(expr: String, vars: Map<String, String>): EvalResult {
        if (expr.isBlank()) return EvalResult("")

        if (expr.contains('{') && !expr.contains('}'))
            return EvalResult("", "Незакрытая скобка { в выражении «$expr»")
        if (expr.contains('}') && !expr.contains('{'))
            return EvalResult("", "Лишняя скобка } в выражении «$expr»")
        if (expr.contains('[') && !expr.contains(']'))
            return EvalResult("", "Незакрытая скобка [ в выражении «$expr»")

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
                    val (result, consumed, err) = resolveBuiltin(expr, i, vars)
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
                        ?: if (varName in SYSTEM_VARS) "" // системная переменная — пустая строка по умолчанию
                        else return "" to "Переменная «$varName» не объявлена. Создай её через блок «Переменная»"
                    sb.append(value)
                    i = end + 1
                }
                expr[i] == '[' -> {
                    val end = expr.indexOf(']', i)
                    if (end == -1) return "" to "Незакрытая скобка [ в «$expr»"
                    val ref = expr.substring(i + 1, end).trim()
                    val dot = ref.indexOf('.')
                    if (dot == -1) {
                        // [tableName] — вся таблица как "key1=val1, key2=val2"
                        val tableName = ref.trim()
                        val tableData = tables[tableName]
                            ?: return "" to "Таблица «$tableName» не найдена"
                        sb.append(tableData.entries.joinToString(", ") { "${it.key}=${it.value}" })
                        i = end + 1
                    } else {
                        val tableName = ref.substring(0, dot).trim()
                        val rawKey = ref.substring(dot + 1).trim()
                        val (resolvedKey, keyErr) = substitute(rawKey, vars)
                        if (keyErr != null) return "" to keyErr
                        val tableData = tables[tableName]
                            ?: return "" to "Таблица «$tableName» не найдена"
                        val value = tableData[resolvedKey]
                            ?: return "" to "Ключ «$resolvedKey» не найден в таблице «$tableName»"
                        sb.append(value)
                        i = end + 1
                    }
                }
                expr[i] == '#' -> {
                    // Тег - просто копируем как есть
                    sb.append(expr[i++])
                }
                else -> sb.append(expr[i++])
            }
        }
        return sb.toString() to null
    }

    /** Возвращает (подставленное значение, сколько символов потреблено, ошибка) */
    private fun resolveBuiltin(expr: String, start: Int, vars: Map<String, String> = emptyMap()): Triple<String, Int, String?> {
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
            "lower(" to ::handleLower,
            "objX(" to ::handleObjX,
            "objY(" to ::handleObjY,
            "objRot(" to ::handleObjRot,
            "objVx(" to ::handleObjVx,
            "objVy(" to ::handleObjVy,
            "objDirX(" to ::handleObjDirX,
            "objDirY(" to ::handleObjDirY,
            "objFrontX(" to ::handleObjFrontX,
            "objFrontY(" to ::handleObjFrontY,
            "objGrounded(" to ::handleObjGrounded,
            "sqrt(" to ::handleSqrt,
            "tableSize(" to ::handleTableSize,
            "tableKey(" to ::handleTableKey,
            "tableVal(" to ::handleTableVal,
            "saveExists(" to ::handleSaveExists
        )
        
        for ((pattern, handler) in funcPatterns) {
            if (sub.startsWith(pattern)) {
                // Ищем парную закрывающую скобку с учётом вложенности
                var depth = 1; var ci = pattern.length
                while (ci < sub.length && depth > 0) {
                    when (sub[ci]) { '(' -> depth++; ')' -> depth-- }
                    ci++
                }
                if (depth != 0) return Triple("", 1, "Незакрытая скобка в \$${pattern.dropLast(1)}()")
                val close = ci - 1
                val rawArgs = sub.substring(pattern.length, close)
                // Вычисляем вложенные $-функции в аргументах
                val (resolvedArgs, argErr) = substitute(rawArgs, vars)
                if (argErr != null) return Triple("", 1, argErr)
                // Разбиваем по запятой с учётом вложенности скобок
                val args = splitArgs(resolvedArgs)
                val consumed = 1 + close + 1
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

    private fun handleObjX(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objX() требует один аргумент: имя объекта")
        val name = args[0]
        objects[name]?.let { return Triple(fmt(it.x.toDouble()), consumed, null) }
        joysticks[name]?.let { return Triple(fmt(it.x.toDouble()), consumed, null) }
        return Triple("", consumed, "\$objX(): объект «$name» не найден")
    }

    private fun handleObjY(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objY() требует один аргумент: имя объекта")
        val name = args[0]
        objects[name]?.let { return Triple(fmt(it.y.toDouble()), consumed, null) }
        joysticks[name]?.let { return Triple(fmt(it.y.toDouble()), consumed, null) }
        return Triple("", consumed, "\$objY(): объект «$name» не найден")
    }

    private fun handleObjRot(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objRot() требует один аргумент: имя объекта")
        val name = args[0]
        objects[name]?.let { return Triple(fmt(it.rotation.toDouble()), consumed, null) }
        return Triple("", consumed, "\$objRot(): объект «$name» не найден")
    }

    private fun handleObjVx(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objVx() требует один аргумент: имя объекта")
        val name = args[0]
        val vx = objects[name]?.physicsBody?.velocityX ?: return Triple("0", consumed, null)
        return Triple(fmt(vx.toDouble()), consumed, null)
    }

    private fun handleObjVy(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objVy() требует один аргумент: имя объекта")
        val name = args[0]
        val vy = objects[name]?.physicsBody?.velocityY ?: return Triple("0", consumed, null)
        return Triple(fmt(vy.toDouble()), consumed, null)
    }

    /** Возвращает X-компонент единичного вектора направления объекта (по углу поворота) */
    private fun handleObjDirX(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objDirX() требует один аргумент: имя объекта")
        val name = args[0]
        val rot = objects[name]?.rotation ?: return Triple("0", consumed, null)
        val rad = Math.toRadians(rot.toDouble())
        return Triple(fmt(kotlin.math.sin(rad)), consumed, null)
    }

    /** Возвращает Y-компонент единичного вектора направления объекта (по углу поворота) */
    private fun handleObjDirY(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$objDirY() требует один аргумент: имя объекта")
        val name = args[0]
        val rot = objects[name]?.rotation ?: return Triple("0", consumed, null)
        val rad = Math.toRadians(rot.toDouble())
        return Triple(fmt(kotlin.math.cos(rad)), consumed, null)
    }

    /** X-позиция точки перед объектом на расстоянии dist */
    private fun handleObjFrontX(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$objFrontX() требует два аргумента: имя объекта и расстояние")
        val obj = objects[args[0]] ?: return Triple("0", consumed, null)
        val dist = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$objFrontX(): «${args[1]}» не число")
        val rad = Math.toRadians(obj.rotation.toDouble())
        return Triple(fmt(obj.x + kotlin.math.sin(rad) * dist), consumed, null)
    }

    /** Y-позиция точки перед объектом на расстоянии dist */
    private fun handleObjFrontY(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$objFrontY() требует два аргумента: имя объекта и расстояние")
        val obj = objects[args[0]] ?: return Triple("0", consumed, null)
        val dist = args[1].toDoubleOrNull() ?: return Triple("", consumed, "\$objFrontY(): «${args[1]}» не число")
        val rad = Math.toRadians(obj.rotation.toDouble())
        return Triple(fmt(obj.y + kotlin.math.cos(rad) * dist), consumed, null)
    }

    /** Возвращает true если объект стоит на статическом объекте (на земле) */
    private fun handleObjGrounded(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("false", consumed, "\$objGrounded() требует один аргумент: имя объекта")
        val obj = objects[args[0]] ?: return Triple("false", consumed, null)
        val body = obj.physicsBody ?: return Triple("false", consumed, null)
        // Считаем "на земле" если скорость Y близка к нулю и снизу есть статический объект
        if (kotlin.math.abs(body.velocityY) > 2f) return Triple("false", consumed, null)
        val bottom = obj.y - obj.height / 2f
        val grounded = objects.values.any { other ->
            if (other.name == obj.name) return@any false
            val otherBody = other.physicsBody ?: return@any false
            if (!otherBody.isStatic) return@any false
            val otherTop = other.y + other.height / 2f
            val overlapX = (obj.width / 2f + other.width / 2f) - kotlin.math.abs(obj.x - other.x)
            overlapX > 0f && kotlin.math.abs(bottom - otherTop) < 4f
        }
        return Triple(grounded.toString(), consumed, null)
    }

    private fun handleSqrt(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$sqrt() требует один аргумент")
        val a = args[0].toDoubleOrNull() ?: return Triple("", consumed, "\$sqrt(): «${args[0]}» не число")
        if (a < 0) return Triple("", consumed, "\$sqrt(): нельзя извлечь корень из отрицательного числа")
        return Triple(fmt(sqrt(a)), consumed, null)
    }

    private fun handleTableSize(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$tableSize() требует один аргумент: имя таблицы")
        val tbl = tables[args[0]] ?: return Triple("", consumed, "\$tableSize(): таблица «${args[0]}» не найдена")
        return Triple(tbl.size.toString(), consumed, null)
    }

    private fun handleTableKey(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$tableKey() требует два аргумента: имя таблицы и индекс")
        val tbl = tables[args[0]] ?: return Triple("", consumed, "\$tableKey(): таблица «${args[0]}» не найдена")
        val idx = args[1].toIntOrNull() ?: return Triple("", consumed, "\$tableKey(): индекс «${args[1]}» не число")
        val key = tbl.keys.toList().getOrNull(idx) ?: return Triple("", consumed, "\$tableKey(): индекс $idx вне диапазона (размер: ${tbl.size})")
        return Triple(key, consumed, null)
    }

    private fun handleTableVal(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 2) return Triple("", consumed, "\$tableVal() требует два аргумента: имя таблицы и индекс")
        val tbl = tables[args[0]] ?: return Triple("", consumed, "\$tableVal(): таблица «${args[0]}» не найдена")
        val idx = args[1].toIntOrNull() ?: return Triple("", consumed, "\$tableVal(): индекс «${args[1]}» не число")
        val value = tbl.values.toList().getOrNull(idx) ?: return Triple("", consumed, "\$tableVal(): индекс $idx вне диапазона (размер: ${tbl.size})")
        return Triple(value, consumed, null)
    }

    private fun handleSaveExists(args: List<String>, consumed: Int): Triple<String, Int, String?> {
        if (args.size != 1) return Triple("", consumed, "\$saveExists() требует один аргумент: ключ сохранения")
        val ctx = appContext ?: return Triple("false", consumed, null)
        val prefs = ctx.getSharedPreferences("skripts_saves", android.content.Context.MODE_PRIVATE)
        val exists = prefs.contains(args[0]) || prefs.contains("__table__${args[0]}")
        return Triple(exists.toString(), consumed, null)
    }

    private fun tryArith(expr: String): String? {
        if (expr.toDoubleOrNull() != null) return fmt(expr.toDouble())
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'")))
            return expr.substring(1, expr.length - 1)
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
                    if (expr[i] == '+') {
                        val lv = l.toDoubleOrNull()
                        val rv = r.toDoubleOrNull()
                        return if (lv != null && rv != null) fmt(lv + rv) else l + r
                    } else {
                        val lv = l.toDoubleOrNull() ?: return null
                        val rv = r.toDoubleOrNull() ?: return null
                        return fmt(lv - rv)
                    }
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
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))
            return t.substring(1, t.length - 1)
        return t.toDoubleOrNull()?.let { fmt(it) } ?: t.ifEmpty { null }
    }

    private fun fmt(v: Double): String =
        if (v == floor(v) && abs(v) < 1e12) v.toLong().toString() else v.toString()

    /** Разбивает строку аргументов по запятой с учётом вложенности скобок */
    private fun splitArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0; var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result += s.substring(start, i).trim().removeSurrounding("\"").removeSurrounding("'")
                    start = i + 1
                }
            }
        }
        result += s.substring(start).trim().removeSurrounding("\"").removeSurrounding("'")
        return result
    }

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
