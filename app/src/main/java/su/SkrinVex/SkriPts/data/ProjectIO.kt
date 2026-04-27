package su.SkrinVex.SkriPts.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val FORMAT_VERSION = 2
private const val PROJECT_JSON_ENTRY = "project.json"
private const val SPRITES_DIR = "sprites/"

data class ExportEnvelope(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "1.3",
    val project: ScriptProject
)

object ProjectIO {
    private val gson = Gson()

    fun export(ctx: Context, project: ScriptProject, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                // project.json
                val json = gson.toJson(ExportEnvelope(project = project))
                zip.putNextEntry(ZipEntry(PROJECT_JSON_ENTRY))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // sprites/
                val spritesDir = SpriteRepository.spritesDir(ctx, project.id)
                project.sprites.orEmpty().forEach { sprite ->
                    val file = File(spritesDir, sprite.fileName)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("$SPRITES_DIR${sprite.fileName}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } ?: error("Не удалось открыть файл для записи")
    }

    fun import(ctx: Context, uri: Uri): ScriptProject {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportException("Не удалось прочитать файл")

        // Пробуем ZIP
        return runCatching { importZip(ctx, bytes) }
            .getOrElse {
                // Fallback: старый JSON формат
                importLegacyJson(bytes)
            }
    }

    private fun importZip(ctx: Context, bytes: ByteArray): ScriptProject {
        var project: ScriptProject? = null
        val spriteFiles = mutableMapOf<String, ByteArray>()

        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == PROJECT_JSON_ENTRY -> {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        project = parseProjectJson(text)
                    }
                    entry.name.startsWith(SPRITES_DIR) && !entry.isDirectory -> {
                        val fileName = entry.name.removePrefix(SPRITES_DIR)
                        if (fileName.isNotBlank()) {
                            spriteFiles[fileName] = zip.readBytes()
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val p = project ?: throw ImportException("Архив не содержит project.json")

        // Сохраняем файлы спрайтов
        if (spriteFiles.isNotEmpty()) {
            val spritesDir = SpriteRepository.spritesDir(ctx, p.id)
            spriteFiles.forEach { (name, data) ->
                File(spritesDir, name).writeBytes(data)
            }
        }

        return p
    }

    private fun importLegacyJson(bytes: ByteArray): ScriptProject {
        val text = bytes.toString(Charsets.UTF_8)
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

        return runCatching { gson.fromJson(projectJson, ScriptProject::class.java) }
            .getOrElse { throw ImportException("Не удалось разобрать данные проекта: ${it.message}") }
            .also { if (it.id.isBlank() || it.name.isBlank()) throw ImportException("Файл проекта неполный или повреждён") }
    }

    private fun parseProjectJson(text: String): ScriptProject {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw ImportException("project.json повреждён") }

        val projectJson: JsonObject = when {
            root.has("project") && root.has("formatVersion") -> root.getAsJsonObject("project")
            root.has("id") && root.has("name") -> root
            else -> throw ImportException("Неизвестный формат project.json")
        }

        return runCatching { gson.fromJson(projectJson, ScriptProject::class.java) }
            .getOrElse { throw ImportException("Не удалось разобрать project.json: ${it.message}") }
            .also { if (it.id.isBlank() || it.name.isBlank()) throw ImportException("project.json неполный") }
    }
}

class ImportException(message: String) : Exception(message)
