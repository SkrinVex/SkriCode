package su.SkrinVex.SkriPts.block

import com.google.gson.JsonObject

enum class BlockCategory(val label: String) {
    OUTPUT("Вывод"),
    CONTROL("Управление"),
    MATH("Математика"),
    LOGIC("Логика"),
    STRING("Строки"),
    VARIABLE("Переменные"),
    SIMULATION("Симуляция"),
    SPRITE("Спрайты"),
    PHYSICS("Физика"),
    CAMERA("Камера"),
}

enum class ParamType { TEXT, NUMBER, SELECT }

data class BlockParam(
    val value: String,
    val label: String,
    val hint: String = "",
    val type: ParamType = ParamType.TEXT
)

/**
 * Блок полностью immutable — params хранятся в data class,
 * изменение = копия через withParam().
 */
data class BlockDef(
    val id: String,           // уникальный id экземпляра
    val type: String,
    val displayName: String,
    val description: String,
    val category: BlockCategory,
    val isAsync: Boolean = false,
    val params: Map<String, BlockParam> = emptyMap(),
    val paramOrder: List<String> = emptyList(),
    // Дочерние блоки для if_block: "then" и "else" ветки
    val children: Map<String, List<BlockDef>> = emptyMap()
) {
    fun withParam(key: String, value: String): BlockDef =
        copy(params = params + (key to params[key]!!.copy(value = value)))

    /** Добавляет/обновляет произвольный параметр (в т.ч. служебные _-ключи) */
    fun withExtraParam(key: String, value: String): BlockDef =
        copy(params = params + (key to (params[key]?.copy(value = value) ?: BlockParam(value, key))))

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("async", isAsync)
        val p = JsonObject()
        paramOrder.forEach { k -> p.addProperty(k, params[k]!!.value) }
        add("params", p)
    }
}
