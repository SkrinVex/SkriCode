package su.SkrinVex.SkriCode.engine.ast

import java.util.concurrent.ConcurrentHashMap

object ExprCompiler {

    private val exprCache = ConcurrentHashMap<String, AstExpr>(256)
    private val conditionCache = ConcurrentHashMap<String, ConditionExpr>(128)

    fun clearCache() {
        exprCache.clear()
        conditionCache.clear()
    }

    fun compile(raw: String): AstExpr {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return LiteralString("")
        return exprCache.computeIfAbsent(trimmed) { parseExpr(it) }
    }

    fun compileCondition(left: String, op: String, right: String): ConditionExpr {
        val key = "$left|$op|$right"
        return conditionCache.computeIfAbsent(key) {
            ConditionExpr(compile(left), op.trim(), compile(right))
        }
    }

    private fun parseExpr(expr: String): AstExpr {
        // Числовая константа
        val d = expr.toDoubleOrNull()
        if (d != null) return LiteralNumber(d, expr)

        // Строковый литерал в кавычках
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
            if (expr.length >= 2) return LiteralString(expr.substring(1, expr.length - 1))
        }

        // Простая переменная {varName}
        if (expr.startsWith("{") && expr.endsWith("}") && expr.indexOf('{', 1) == -1) {
            val vName = expr.substring(1, expr.length - 1).trim()
            if (vName.isNotEmpty()) return VarRef(vName)
        }

        // Встроенные константы экрана
        when (expr) {
            "\$screenWidth" -> return ScreenConst(ScreenConstType.WIDTH)
            "\$screenHeight" -> return ScreenConst(ScreenConstType.HEIGHT)
            "\$screenTop" -> return ScreenConst(ScreenConstType.TOP)
            "\$screenBottom" -> return ScreenConst(ScreenConstType.BOTTOM)
            "\$screenRight" -> return ScreenConst(ScreenConstType.RIGHT)
            "\$screenLeft" -> return ScreenConst(ScreenConstType.LEFT)
        }

        // Точки хитбокса или списки со знаками «;» без выражений — сырая строка
        if (expr.contains(';') && !expr.contains('{') && !expr.contains('$') && !expr.contains('[')) {
            return LiteralString(expr)
        }

        // Разбор арифметики (+ / -)
        val addSub = parseAddSub(expr)
        if (addSub != null) return addSub

        // Вложенные выражения / функции / интерполяция
        return parsePrimary(expr)
    }

    private fun parseAddSub(expr: String): AstExpr? {
        var i = expr.length - 1
        var depth = 0
        while (i >= 0) {
            when (expr[i]) {
                ')', '}', ']' -> depth++
                '(', '{', '[' -> depth--
                '+', '-' -> if (depth == 0 && i > 0) {
                    val prev = expr[i - 1]
                    if (expr[i] == '-' && (prev == '+' || prev == '-' || prev == '*' || prev == '/' || prev == '%' || prev == '(' || prev == ',')) {
                        i--
                        continue
                    }
                    val leftStr = expr.substring(0, i).trim()
                    val rightStr = expr.substring(i + 1).trim()
                    if (leftStr.isNotEmpty() && rightStr.isNotEmpty()) {
                        val leftNode = parseExpr(leftStr)
                        val rightNode = parseMulDiv(rightStr) ?: parseExpr(rightStr)
                        return BinaryArith(leftNode, expr[i], rightNode)
                    }
                }
            }
            i--
        }
        return parseMulDiv(expr)
    }

    private fun parseMulDiv(expr: String): AstExpr? {
        var i = expr.length - 1
        var depth = 0
        while (i >= 0) {
            when (expr[i]) {
                ')', '}', ']' -> depth++
                '(', '{', '[' -> depth--
                '*', '/', '%' -> if (depth == 0 && i > 0) {
                    val leftStr = expr.substring(0, i).trim()
                    val rightStr = expr.substring(i + 1).trim()
                    if (leftStr.isNotEmpty() && rightStr.isNotEmpty()) {
                        val leftNode = parseMulDiv(leftStr) ?: parseExpr(leftStr)
                        val rightNode = parseExpr(rightStr)
                        return BinaryArith(leftNode, expr[i], rightNode)
                    }
                }
            }
            i--
        }
        return null
    }

    private fun parsePrimary(expr: String): AstExpr {
        val t = expr.trim()

        // Скобки (expr)
        if (t.startsWith("(") && t.endsWith(")")) {
            var depth = 0
            var wrapsAll = true
            for (i in 0 until t.length - 1) {
                if (t[i] == '(') depth++
                else if (t[i] == ')') {
                    depth--
                    if (depth == 0) {
                        wrapsAll = false
                        break
                    }
                }
            }
            if (wrapsAll) {
                return parseExpr(t.substring(1, t.length - 1))
            }
        }

        // Проверка на встроенные функции $func(...)
        if (t.startsWith("$")) {
            val openParen = t.indexOf('(')
            if (openParen > 1 && t.endsWith(")")) {
                val funcName = t.substring(1, openParen).trim()
                val argsRaw = t.substring(openParen + 1, t.length - 1)
                val args = splitArgs(argsRaw).map { parseExpr(it) }
                return BuiltinFunc(funcName, args)
            }
        }

        // Проверка на таблицу [table] или [table.key]
        if (t.startsWith("[") && t.endsWith("]")) {
            val content = t.substring(1, t.length - 1).trim()
            val dot = content.indexOf('.')
            return if (dot == -1) {
                TableRef(content, null)
            } else {
                val tableName = content.substring(0, dot).trim()
                val keyPart = content.substring(dot + 1).trim()
                TableRef(tableName, parseExpr(keyPart))
            }
        }

        // Интерполяция: содержит {$var} или {var} или $func() внутри текста
        if (t.contains('{') || t.contains('$') || t.contains('[')) {
            val parts = mutableListOf<AstExpr>()
            var i = 0
            val sb = java.lang.StringBuilder()
            while (i < t.length) {
                when {
                    t[i] == '{' -> {
                        if (sb.isNotEmpty()) {
                            parts.add(LiteralString(sb.toString()))
                            sb.setLength(0)
                        }
                        val end = t.indexOf('}', i)
                        if (end != -1) {
                            val varName = t.substring(i + 1, end).trim()
                            parts.add(VarRef(varName))
                            i = end + 1
                        } else {
                            sb.append(t[i++])
                        }
                    }
                    t[i] == '[' -> {
                        if (sb.isNotEmpty()) {
                            parts.add(LiteralString(sb.toString()))
                            sb.setLength(0)
                        }
                        val end = t.indexOf(']', i)
                        if (end != -1) {
                            val sub = t.substring(i, end + 1)
                            parts.add(parsePrimary(sub))
                            i = end + 1
                        } else {
                            sb.append(t[i++])
                        }
                    }
                    t[i] == '$' -> {
                        if (sb.isNotEmpty()) {
                            parts.add(LiteralString(sb.toString()))
                            sb.setLength(0)
                        }
                        // Проверяем константы или функции
                        val sub = t.substring(i)
                        var matched = false
                        for (c in listOf("\$screenWidth", "\$screenHeight", "\$screenTop", "\$screenBottom", "\$screenRight", "\$screenLeft")) {
                            if (sub.startsWith(c)) {
                                parts.add(parseExpr(c))
                                i += c.length
                                matched = true
                                break
                            }
                        }
                        if (!matched) {
                            // Проверяем вызов функции
                            val open = sub.indexOf('(')
                            if (open != -1) {
                                var depth = 0
                                var close = -1
                                for (k in open until sub.length) {
                                    if (sub[k] == '(') depth++
                                    else if (sub[k] == ')') {
                                        depth--
                                        if (depth == 0) {
                                            close = k
                                            break
                                        }
                                    }
                                }
                                if (close != -1) {
                                    val funcSub = sub.substring(0, close + 1)
                                    parts.add(parsePrimary(funcSub))
                                    i += funcSub.length
                                    matched = true
                                }
                            }
                        }
                        if (!matched) {
                            sb.append(t[i++])
                        }
                    }
                    else -> sb.append(t[i++])
                }
            }
            if (sb.isNotEmpty()) {
                parts.add(LiteralString(sb.toString()))
            }
            if (parts.size == 1) return parts[0]
            if (parts.isNotEmpty()) return InterpolatedText(parts)
        }

        val d = t.toDoubleOrNull()
        if (d != null) return LiteralNumber(d, t)

        if (t.matches(Regex("^[a-zA-Z_\\p{L}][a-zA-Z0-9_\\p{L}]*$"))) {
            return VarRef(t, isBare = true)
        }

        return LiteralString(t)
    }

    private fun splitArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        var inQuote = false
        var quoteChar = ' '
        for (i in s.indices) {
            val c = s[i]
            if (inQuote) {
                if (c == quoteChar) inQuote = false
            } else {
                when (c) {
                    '\'', '"' -> { inQuote = true; quoteChar = c }
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        result += s.substring(start, i).trim()
                        start = i + 1
                    }
                }
            }
        }
        if (start < s.length) {
            result += s.substring(start).trim()
        }
        return result
    }
}
