package su.SkrinVex.SkriPts.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import su.SkrinVex.SkriPts.block.BlockDef
import su.SkrinVex.SkriPts.block.BlockFactory
import java.io.File

enum class VarScope { GLOBAL, LOCAL }

data class ProjectVar(
    val name: String,
    val scope: VarScope,
    val value: String = "0"
)

data class ProjectTag(
    val name: String,
    val scope: VarScope
)

/** Таблица — именованный словарь ключ→значение */
data class ProjectTable(
    val name: String,
    val scope: VarScope,
    val entries: Map<String, String> = emptyMap()
)

enum class ScriptEvent(val label: String) {
    ON_START("При запуске"),
    ON_TAP("При касании объекта"),
    ON_HOLD("Пока зажат объект"),
    ON_COLLISION("При столкновении"),
    ON_COLLISION_END("При окончании столкновения"),
}

data class Script(
    val id: String,
    val name: String,
    val event: ScriptEvent = ScriptEvent.ON_START,
    val eventTarget: String = "",
    val blocks: List<SerializedBlock> = emptyList(),
    val localVars: List<ProjectVar>? = emptyList(),
    val localTags: List<ProjectTag>? = emptyList(),
    val localTables: List<ProjectTable>? = emptyList(),
    val collapsedBlockIds: Set<String>? = emptySet()  // сохранённые свёрнутые блоки
)

/** Объект на локации (не привязан к скрипту, только визуальное позиционирование) */
data class LocationObject(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 100f,
    val height: Float = 60f,
    val radius: Float = 8f,
    val color: String = "#4F8EF7",
    val type: String = "rect"  // rect | circle | text
)

/** Сцена — независимый экран с собственными скриптами и объектами */
data class Scene(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val scripts: List<Script> = listOf(Script(java.util.UUID.randomUUID().toString(), "Скрипт 1")),
    val locationBlocks: List<SerializedBlock> = emptyList()
)

/** Метаданные спрайта — сам файл хранится в SpriteRepository */
data class SpriteAsset(
    val name: String,          // уникальный идентификатор (без расширения)
    val fileName: String,      // имя файла с расширением (e.g. "hero.png")
    val width: Int = 0,
    val height: Int = 0
)

/** Метаданные звука — сам аудиофайл хранится в SoundRepository */
data class SoundAsset(
    val name: String,          // уникальный идентификатор (без расширения)
    val fileName: String,      // имя файла с расширением (e.g. "jump.wav")
    val durationMs: Long = 0,  // длительность аудио в миллисекундах
    val sizeBytes: Long = 0    // размер файла в байтах
)

enum class ProjectOrientation { PORTRAIT, LANDSCAPE }

data class ScriptProject(
    val id: String,
    val name: String,
    val orientation: ProjectOrientation? = ProjectOrientation.PORTRAIT,
    val packageName: String? = null,
    val appLabel: String? = null,       // кастомное название приложения (null = имя проекта)
    val versionName: String? = null,    // e.g. "1.0"
    val versionCode: Int? = null,       // e.g. 1
    val iconFileName: String? = null,   // имя файла иконки в SpriteRepository (null = дефолтная)
    val enableLogFile: Boolean? = null, // включить запись логов в файл
    val logDir: String? = null,         // директория для сохранения .skrilogs файлов
    val clearLogsOnStart: Boolean? = null, // очищать лог-файл при каждом запуске симуляции
    val scenes: List<Scene>? = null,           // null = legacy (нет сцен)
    val activeSceneId: String? = null,
    val scripts: List<Script>? = emptyList(),  // legacy / сцена по умолчанию
    val globalVars: List<ProjectVar>? = emptyList(),
    val globalTags: List<ProjectTag>? = emptyList(),
    val globalTables: List<ProjectTable>? = emptyList(),
    val locationBlocks: List<SerializedBlock>? = emptyList(),
    val sprites: List<SpriteAsset>? = emptyList(),
    val sounds: List<SoundAsset>? = emptyList(),
    // legacy
    val variables: List<ProjectVar>? = null,
    val blocks: List<SerializedBlock>? = null
)

data class SerializedBlock(
    val type: String,
    val params: Map<String, String>,
    val id: String = java.util.UUID.randomUUID().toString(),
    val children: Map<String, List<SerializedBlock>>? = null,
    val pairId: String? = null  // nullable для обратной совместимости со старыми сохранениями
)

fun BlockDef.serialize(): SerializedBlock = SerializedBlock(
    type, params.mapValues { it.value.value }, id,
    children = if (children.isEmpty()) null else children.mapValues { (_, blocks) -> blocks.map { it.serialize() } },
    pairId = pairId
)

fun SerializedBlock.deserialize(): BlockDef? = BlockFactory.create(type)?.let { proto ->
    var b = proto.copy(id = this.id, pairId = this.pairId ?: "")
    params.forEach { (k, v) -> if (b.params.containsKey(k)) b = b.withParam(k, v) }
    if (!children.isNullOrEmpty()) {
        b = b.copy(children = children.mapValues { (_, list) -> list.mapNotNull { it.deserialize() } })
    }
    b
}

object ProjectRepository {
    private val gson = Gson()
    private fun dir(ctx: Context) = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun save(ctx: Context, project: ScriptProject) =
        File(dir(ctx), "${project.id}.json").writeText(gson.toJson(project))

    fun load(ctx: Context, id: String): ScriptProject? {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), ScriptProject::class.java) }.getOrNull()
    }

    fun list(ctx: Context): List<ScriptProject> {
        val type = object : TypeToken<ScriptProject>() {}.type
        return dir(ctx).listFiles()
            ?.mapNotNull { runCatching { gson.fromJson<ScriptProject>(it.readText(), type) }.getOrNull() }
            ?: emptyList()
    }

    fun delete(ctx: Context, id: String) = File(dir(ctx), "$id.json").delete()
}
