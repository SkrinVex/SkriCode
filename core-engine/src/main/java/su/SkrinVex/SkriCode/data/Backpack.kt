package su.SkrinVex.SkriCode.data

import java.util.UUID

enum class BackpackItemType { SCRIPT, BLOCK, OBJECT, SCENE }

/**
 * Один элемент "Рюкзака" — сквозного, не привязанного к проекту хранилища.
 * Ровно одно из [script]/[blocks]/[scene] заполнено в зависимости от [type].
 *
 * [blocks] хранит СПИСОК блоков, а не один блок: для BLOCK-элемента это может быть как
 * один самостоятельный блок, так и целый диапазон парного блока (if/for/while/wait —
 * открывающий блок + тело + закрывающий, все вместе) — так парные блоки нельзя разорвать
 * при отправке в рюкзак. Для OBJECT-элемента список всегда содержит ровно один блок.
 *
 * [sprites] заполняется для OBJECT- (0 или 1 элемент) и SCENE-элементов (0..N),
 * ссылающихся на картинки — файлы спрайтов при этом копируются в собственное
 * хранилище рюкзака, так что элемент остаётся самодостаточным и работает в любом другом проекте.
 */
data class BackpackItem(
    val id: String = UUID.randomUUID().toString(),
    val type: BackpackItemType,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val script: Script? = null,
    val blocks: List<SerializedBlock> = emptyList(),
    val scene: Scene? = null,
    val sprites: List<SpriteAsset> = emptyList()
)

data class Backpack(
    val items: List<BackpackItem> = emptyList()
)
