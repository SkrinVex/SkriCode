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

enum class ScriptEvent(val label: String) {
    ON_START("При запуске"),
    ON_TAP("При касании объекта"),
}

data class Script(
    val id: String,
    val name: String,
    val event: ScriptEvent = ScriptEvent.ON_START,
    val eventTarget: String = "",
    val blocks: List<SerializedBlock> = emptyList(),
    val localVars: List<ProjectVar>? = emptyList(),
    val collapsedBlockIds: Set<String>? = emptySet()  // сохранённые свёрнутые блоки
)

data class ScriptProject(
    val id: String,
    val name: String,
    val scripts: List<Script>? = emptyList(),
    val globalVars: List<ProjectVar>? = emptyList(),  // только глобальные
    // legacy
    val variables: List<ProjectVar>? = null,
    val blocks: List<SerializedBlock>? = null
)

data class SerializedBlock(
    val type: String,
    val params: Map<String, String>,
    val id: String = java.util.UUID.randomUUID().toString()  // сохраняем ID
)

fun BlockDef.serialize() = SerializedBlock(type, params.mapValues { it.value.value }, id)

fun SerializedBlock.deserialize(): BlockDef? = BlockFactory.create(type)?.let { proto ->
    var b = proto.copy(id = this.id)  // восстанавливаем сохранённый ID
    params.forEach { (k, v) -> if (b.params.containsKey(k)) b = b.withParam(k, v) }
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
