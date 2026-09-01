package su.SkrinVex.SkriCode.data

import android.content.Context
import com.google.gson.Gson
import su.SkrinVex.SkriCode.block.BlockDef
import java.io.File
import java.util.UUID

/**
 * Хранилище "Рюкзака" — один общий JSON-файл на устройстве, не привязанный к проекту.
 * Переживает удаление любого конкретного проекта (в т.ч. того, откуда элемент был отправлен).
 */
object BackpackRepository {
    private val gson = Gson()

    private fun file(ctx: Context) = File(ctx.filesDir, "backpack.json")
    private fun spritesDir(ctx: Context) = File(ctx.filesDir, "backpack_sprites").also { it.mkdirs() }

    fun load(ctx: Context): Backpack {
        val f = file(ctx)
        if (!f.exists()) return Backpack()
        return runCatching { gson.fromJson(f.readText(), Backpack::class.java) }.getOrNull() ?: Backpack()
    }

    private fun persist(ctx: Context, backpack: Backpack) {
        file(ctx).writeText(gson.toJson(backpack))
    }

    fun addScript(ctx: Context, script: Script, name: String) {
        val item = BackpackItem(type = BackpackItemType.SCRIPT, name = name, script = script)
        val current = load(ctx)
        persist(ctx, current.copy(items = current.items + item))
    }

    /**
     * Кладёт блок(и) в рюкзак. Для одиночного блока — список из одного элемента.
     * Для парного блока (if/for/while/wait) вызывающий код обязан передать весь диапазон
     * целиком (открывающий блок + тело + закрывающий), иначе при вставке пара окажется разорвана.
     */
    fun addBlock(ctx: Context, blocks: List<BlockDef>, name: String) {
        if (blocks.isEmpty()) return
        val item = BackpackItem(type = BackpackItemType.BLOCK, name = name, blocks = blocks.map { it.serialize() })
        val current = load(ctx)
        persist(ctx, current.copy(items = current.items + item))
    }

    /** Копирует файл спрайта проекта в собственное хранилище рюкзака под новым уникальным именем. */
    private fun copySpriteToBackpack(ctx: Context, projectId: String, asset: SpriteAsset): SpriteAsset? {
        val src = SpriteRepository.getFile(ctx, projectId, asset.fileName) ?: return null
        if (!src.exists()) return null
        val ext = src.extension.ifBlank { "png" }
        val storedFileName = "${UUID.randomUUID()}.$ext"
        val ok = runCatching { src.copyTo(File(spritesDir(ctx), storedFileName), overwrite = true) }.isSuccess
        return if (ok) asset.copy(fileName = storedFileName) else null
    }

    /**
     * Кладёт объект сцены в рюкзак вместе со всеми его настройками (тэги/физика/хитбокс —
     * они уже часть block.children["setup"], serialize() их сохраняет автоматически).
     * Если объект использует спрайт — копирует файл картинки в собственное хранилище рюкзака.
     */
    fun addObject(ctx: Context, projectId: String, block: BlockDef, spriteAsset: SpriteAsset?, name: String) {
        val storedSprite = spriteAsset?.let { copySpriteToBackpack(ctx, projectId, it) }
        val item = BackpackItem(
            type = BackpackItemType.OBJECT, name = name, blocks = listOf(block.serialize()),
            sprites = listOfNotNull(storedSprite)
        )
        val current = load(ctx)
        persist(ctx, current.copy(items = current.items + item))
    }

    /**
     * Кладёт целую сцену (все её объекты локации и скрипты) в рюкзак.
     * Копирует файлы всех спрайтов, на которые ссылаются объекты сцены.
     */
    fun addScene(ctx: Context, projectId: String, scene: Scene, projectSprites: List<SpriteAsset>, name: String) {
        val usedSpriteNames = scene.locationBlocks.mapNotNull { it.deserialize() }
            .mapNotNull { it.params["sprite"]?.value?.takeIf { s -> s.isNotBlank() } }
            .toSet()
        val storedSprites = usedSpriteNames.mapNotNull { spriteName ->
            projectSprites.find { it.name == spriteName }?.let { copySpriteToBackpack(ctx, projectId, it) }
        }
        val item = BackpackItem(type = BackpackItemType.SCENE, name = name, scene = scene, sprites = storedSprites)
        val current = load(ctx)
        persist(ctx, current.copy(items = current.items + item))
    }

    fun removeItem(ctx: Context, itemId: String) {
        val current = load(ctx)
        val target = current.items.find { it.id == itemId } ?: return
        target.sprites.forEach { File(spritesDir(ctx), it.fileName).delete() }
        persist(ctx, current.copy(items = current.items.filterNot { it.id == itemId }))
    }

    /** Файл картинки, скопированной в рюкзак вместе с OBJECT/SCENE-элементом. */
    fun getSpriteFile(ctx: Context, fileName: String): File? {
        val f = File(spritesDir(ctx), fileName)
        return if (f.exists()) f else null
    }
}
