package su.SkrinVex.SkriCode.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object SpriteRepository {

    private fun dir(ctx: Context, projectId: String): File =
        File(ctx.filesDir, "projects/$projectId/sprites").also { it.mkdirs() }

    /** Импортирует изображение из URI, возвращает SpriteAsset или null при ошибке */
    fun importSprite(ctx: Context, projectId: String, uri: Uri, name: String): SpriteAsset? {
        val mimeType = ctx.contentResolver.getType(uri) ?: ""
        val ext = when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> "jpg"
            else -> {
                // Попробуем угадать по URI
                val path = uri.lastPathSegment ?: ""
                when {
                    path.endsWith(".png", ignoreCase = true) -> "png"
                    path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "jpg"
                    else -> "png"
                }
            }
        }
        val fileName = "$name.$ext"
        val dest = File(dir(ctx, projectId), fileName)
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, opts)
            SpriteAsset(name = name, fileName = fileName, width = opts.outWidth, height = opts.outHeight)
        }.getOrNull()
    }

    /** Возвращает File для спрайта или null если не существует */
    fun getFile(ctx: Context, projectId: String, fileName: String): File? {
        val f = File(dir(ctx, projectId), fileName)
        return if (f.exists()) f else null
    }

    /** Удаляет файл спрайта */
    fun delete(ctx: Context, projectId: String, fileName: String) {
        File(dir(ctx, projectId), fileName).delete()
    }

    /** Копирует все спрайты из одного проекта в другой */
    fun copyAll(ctx: Context, fromProjectId: String, toProjectId: String) {
        val src = dir(ctx, fromProjectId)
        val dst = dir(ctx, toProjectId)
        src.listFiles()?.forEach { f -> f.copyTo(File(dst, f.name), overwrite = true) }
    }

    /** Возвращает директорию спрайтов проекта */
    fun spritesDir(ctx: Context, projectId: String): File = dir(ctx, projectId)
}
