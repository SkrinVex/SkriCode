package su.SkrinVex.SkriCode.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

object SoundRepository {

    const val MAX_SOUND_SIZE_BYTES = 20L * 1024L * 1024L // 20 MB

    val ALLOWED_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")

    private fun dir(ctx: Context, projectId: String): File =
        File(ctx.filesDir, "projects/$projectId/sounds").also { it.mkdirs() }

    /**
     * Валидирует и импортирует аудиофайл из URI в проект.
     * @return SoundAsset при успехе, либо выбрасывает IllegalArgumentException с понятным описанием ошибки.
     */
    fun importSound(ctx: Context, projectId: String, uri: Uri, name: String): SoundAsset {
        val cr = ctx.contentResolver

        // 1. Проверяем размер файла
        var sizeBytes: Long = 0
        cr.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
            }
        }

        if (sizeBytes > MAX_SOUND_SIZE_BYTES) {
            val sizeMb = String.format(java.util.Locale.US, "%.1f", sizeBytes / (1024.0 * 1024.0))
            throw IllegalArgumentException("Размер аудиофайла ($sizeMb МБ) превышает максимальный лимит 20 МБ")
        }

        // 2. Определяем расширение и тип
        val mimeType = cr.getType(uri) ?: ""
        val pathSegment = uri.lastPathSegment ?: ""
        val rawExt = pathSegment.substringAfterLast(".", "").lowercase()

        val ext = when {
            rawExt in ALLOWED_EXTENSIONS -> rawExt
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
            mimeType.contains("wav") || mimeType.contains("wave") -> "wav"
            mimeType.contains("ogg") -> "ogg"
            mimeType.contains("m4a") || mimeType.contains("mp4") -> "m4a"
            mimeType.contains("aac") -> "aac"
            mimeType.contains("flac") -> "flac"
            else -> "mp3"
        }

        val fileName = "$name.$ext"
        val dest = File(dir(ctx, projectId), fileName)

        // 3. Копируем файл
        cr.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Не удалось прочитать выбранный аудиофайл")

        val actualSize = dest.length()
        if (actualSize > MAX_SOUND_SIZE_BYTES) {
            dest.delete()
            throw IllegalArgumentException("Размер аудиофайла превышает 20 МБ")
        }

        // 4. Извлекаем метаданные длительности через MediaMetadataRetriever
        var durationMs: Long = 0
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(dest.absolutePath)
            val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
            mmr.release()
        } catch (_: Exception) {
            // Если не удалось извлечь длительность (например нестандартный WAV), сохраняем с 0
        }

        return SoundAsset(
            name = name,
            fileName = fileName,
            durationMs = durationMs,
            sizeBytes = actualSize
        )
    }

    /** Возвращает File для звука или null если не существует */
    fun getFile(ctx: Context, projectId: String, fileName: String): File? {
        val d = dir(ctx, projectId)
        val f = File(d, fileName)
        if (f.exists()) return f
        for (ext in ALLOWED_EXTENSIONS) {
            val withExt = File(d, "$fileName.$ext")
            if (withExt.exists()) return withExt
        }
        return d.listFiles()?.firstOrNull { it.nameWithoutExtension == fileName || it.name == fileName }
    }

    /** Удаляет аудиофайл */
    fun delete(ctx: Context, projectId: String, fileName: String) {
        File(dir(ctx, projectId), fileName).delete()
    }

    /** Копирует все звуки из одного проекта в другой */
    fun copyAll(ctx: Context, fromProjectId: String, toProjectId: String) {
        val src = dir(ctx, fromProjectId)
        val dst = dir(ctx, toProjectId)
        src.listFiles()?.forEach { f -> f.copyTo(File(dst, f.name), overwrite = true) }
    }

    /** Возвращает директорию звуков проекта */
    fun soundsDir(ctx: Context, projectId: String): File = dir(ctx, projectId)
}
