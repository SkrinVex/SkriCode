package su.SkrinVex.SkriPts.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private const val FORMAT_VERSION = 1

data class ExportEnvelope(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "1.2",
    val project: ScriptProject
)

object ProjectIO {
    private val gson = Gson()

    fun export(ctx: Context, project: ScriptProject, uri: Uri) {
        val json = gson.toJson(ExportEnvelope(project = project))
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Не удалось открыть файл для записи")
    }

    fun import(ctx: Context, uri: Uri): ScriptProject {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw ImportException("Не удалось прочитать файл")

        val root = runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw ImportException("Файл повреждён или имеет неверный формат") }

        val projectJson: JsonObject = when {
            root.has("project") && root.has("formatVersion") -> {
                val ver = root.get("formatVersion").asInt
                if (ver > FORMAT_VERSION)
                    throw ImportException("Файл создан в более новой версии приложения (формат v$ver). Обновите SkriPts.")
                root.getAsJsonObject("project")
            }
            root.has("id") && root.has("name") -> root
            else -> throw ImportException("Неизвестный формат файла")
        }

        val project = runCatching { gson.fromJson(projectJson, ScriptProject::class.java) }
            .getOrElse { throw ImportException("Не удалось разобрать данные проекта: ${it.message}") }

        if (project.id.isBlank() || project.name.isBlank())
            throw ImportException("Файл проекта неполный или повреждён")

        return project
    }
}

class ImportException(message: String) : Exception(message)
