package su.SkrinVex.SkriPts.build

import android.content.Context
import com.google.gson.Gson
import su.SkrinVex.SkriPts.data.ScriptProject
import su.SkrinVex.SkriPts.data.SpriteRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

/**
 * ZIP writer с поддержкой page-alignment для .so файлов.
 * Android требует что данные .so entry начинались на offset кратный 4096.
 */
private class AlignedZipWriter(private val out: OutputStream) {
    private var pos = 0L
    private val centralDir = ByteArrayOutputStream()
    private var entryCount = 0

    data class EntryInfo(val name: ByteArray, val offset: Long, val crc: Long,
                         val size: Long, val compressedSize: Long, val method: Int)
    private val entries = mutableListOf<EntryInfo>()

    fun writeEntry(name: String, data: ByteArray, stored: Boolean, alignTo: Int = 1) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val method = if (stored) 0 else 8 // STORED=0, DEFLATED=8

        val compressed = if (stored) data else deflate(data)
        val crc = crc32raw(data)

        // Вычисляем нужный extra padding для выравнивания
        // Local file header = 30 + nameLen + extraLen, потом данные
        val headerBase = 30 + nameBytes.size
        val currentOffset = pos + headerBase
        val extraLen = if (alignTo > 1) {
            val rem = (currentOffset % alignTo).toInt()
            if (rem == 0) 0 else alignTo - rem
        } else 0

        val extra = ByteArray(extraLen)

        // Local file header
        writeLE4(0x04034b50)          // signature
        writeLE2(20)                   // version needed
        writeLE2(0)                    // flags
        writeLE2(method)               // compression
        writeLE2(0); writeLE2(0)       // mod time/date
        writeLE4(crc)                  // crc32
        writeLE4(compressed.size)      // compressed size
        writeLE4(data.size)            // uncompressed size
        writeLE2(nameBytes.size)       // name length
        writeLE2(extraLen)             // extra length
        write(nameBytes)
        write(extra)
        write(compressed)

        entries.add(EntryInfo(nameBytes, pos - headerBase - extraLen - compressed.size,
            crc, data.size.toLong(), compressed.size.toLong(), method))
        entryCount++
    }

    fun finish() {
        val cdOffset = pos
        entries.forEach { e ->
            writeCD(e)
        }
        val cdSize = pos - cdOffset
        // End of central directory
        writeLE4(0x06054b50)
        writeLE2(0); writeLE2(0)
        writeLE2(entryCount); writeLE2(entryCount)
        writeLE4(cdSize)
        writeLE4(cdOffset)
        writeLE2(0)
        out.flush()
    }

    private fun writeCD(e: EntryInfo) {
        writeLE4(0x02014b50)
        writeLE2(20); writeLE2(20)
        writeLE2(0); writeLE2(e.method)
        writeLE2(0); writeLE2(0)
        writeLE4(e.crc)
        writeLE4(e.compressedSize)
        writeLE4(e.size)
        writeLE2(e.name.size)
        writeLE2(0); writeLE2(0)
        writeLE2(0); writeLE2(0)
        writeLE4(0)
        writeLE4(e.offset)
        write(e.name)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        def.setInput(data)
        def.finish()
        val buf = ByteArray(data.size + 64)
        val n = def.deflate(buf)
        def.end()
        return buf.copyOf(n)
    }

    private fun crc32raw(data: ByteArray): Long {
        val c = CRC32(); c.update(data); return c.value
    }

    private fun writeLE2(v: Int) { write(byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())) }
    private fun writeLE4(v: Long) { val b = ByteArray(4); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()); write(b) }
    private fun writeLE4(v: Int) = writeLE4(v.toLong())
    private fun write(b: ByteArray) { out.write(b); pos += b.size }
}

/**
 * Собирает APK из шаблона runtime.apk:
 * 1. Копирует template APK
 * 2. Заменяет packageName в AndroidManifest.xml (бинарный XML)
 * 3. Добавляет зашифрованный project.dat + key.dat + спрайты
 * 4. Подписывает V1 (JAR) тестовым ключом
 */
object ApkBuilder {

    sealed class BuildStep(val message: String)
    object StepEncrypt   : BuildStep("Шифрование проекта...")
    object StepPackage   : BuildStep("Упаковка ресурсов...")
    object StepManifest  : BuildStep("Настройка манифеста...")
    object StepSign      : BuildStep("Подпись APK...")
    object StepDone      : BuildStep("Готово!")
    class  StepError(val error: String) : BuildStep("Ошибка: $error")

    fun build(
        ctx: Context,
        project: ScriptProject,
        packageName: String,
        outputFile: File,
        onStep: (BuildStep) -> Unit
    ) {
        try {
            val workDir = File(ctx.cacheDir, "apkbuild_${project.id}").also { it.deleteRecursively(); it.mkdirs() }

            // 1. Шифруем проект
            onStep(StepEncrypt)
            val aesKey = generateAesKey()
            val projectJson = Gson().toJson(project)
            val encryptedProject = aesEncrypt(projectJson.toByteArray(Charsets.UTF_8), aesKey)

            // Также включаем ключ шифрования сохранений если есть
            val saveKey = loadSaveKey(ctx, project)

            // 2. Распаковываем шаблон
            onStep(StepPackage)
            val templateApk = File(workDir, "template.apk")
            ctx.assets.open("runtime.apk").use { it.copyTo(templateApk.outputStream()) }

            val outputZip = File(workDir, "output_unsigned.apk")
            val zipOut = AlignedZipWriter(FileOutputStream(outputZip))

            ZipFile(templateApk).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("META-INF/")) return@forEach
                    if (entry.name == "AndroidManifest.xml") return@forEach
                    if (entry.name == "resources.arsc") return@forEach
                    if (entry.name == "assets/project.dat") return@forEach
                    if (entry.name == "assets/key.dat") return@forEach
                    if (entry.name.startsWith("assets/sprites/")) return@forEach

                    val bytes = zip.getInputStream(entry).readBytes()
                    val isSo = entry.name.endsWith(".so")
                    val stored = isSo || entry.method == ZipEntry.STORED
                    zipOut.writeEntry(entry.name, bytes, stored, if (isSo) 4096 else 1)
                }
            }

            // 3. Патчим манифест (меняем packageName) и resources.arsc (меняем app_name)
            onStep(StepManifest)
            val manifestBytes = ZipFile(templateApk).use { zip ->
                zip.getEntry("AndroidManifest.xml")?.let { zip.getInputStream(it).readBytes() }
            }
            if (manifestBytes != null) {
                zipOut.writeEntry("AndroidManifest.xml", patchManifestPackage(manifestBytes, packageName), stored = false)
            }

            val arscBytes = ZipFile(templateApk).use { zip ->
                zip.getEntry("resources.arsc")?.let { zip.getInputStream(it).readBytes() }
            }
            if (arscBytes != null) {
                zipOut.writeEntry("resources.arsc", patchArscAppName(arscBytes, project.name), stored = true, alignTo = 4)
            }

            // 4. Добавляем зашифрованный проект
            zipOut.writeEntry("assets/project.dat", encryptedProject, stored = false)
            zipOut.writeEntry("assets/key.dat", aesKey, stored = false)
            if (saveKey != null) {
                zipOut.writeEntry("assets/savekey.dat", saveKey.toByteArray(Charsets.UTF_8), stored = false)
            }

            // 5. Добавляем спрайты
            project.sprites.orEmpty().forEach { sprite ->
                val spriteFile = SpriteRepository.getFile(ctx, project.id, sprite.fileName)
                if (spriteFile?.exists() == true) {
                    zipOut.writeEntry("assets/sprites/${sprite.fileName}", spriteFile.readBytes(), stored = false)
                }
            }

            zipOut.finish()

            // 6. Подписываем V1+V2+V3 через apksig
            onStep(StepSign)
            signApk(outputZip, outputFile, ctx)

            workDir.deleteRecursively()
            onStep(StepDone)

        } catch (e: Exception) {
            onStep(StepError(e.message ?: "Неизвестная ошибка"))
        }
    }

    // ── Шифрование ──────────────────────────────────────────────────────────

    private fun generateAesKey(): ByteArray {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(128)
        return kg.generateKey().encoded
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun loadSaveKey(ctx: Context, project: ScriptProject): String? {
        return try {
            val prefs = ctx.getSharedPreferences("skripts_keyvault", Context.MODE_PRIVATE)
            prefs.getString("key_${project.id}", null)
        } catch (_: Exception) { null }
    }

    // ── Патч манифеста ──────────────────────────────────────────────────────

    /**
     * Патчит бинарный AndroidManifest.xml (AXML формат).
     * Заменяет packageName runtime-шаблона на пользовательский.
     * Placeholder — applicationId из build.gradle.kts модуля app-runtime.
     */
    private fun patchManifestPackage(manifest: ByteArray, newPackage: String): ByteArray {
        // Placeholder = applicationId из app-runtime/build.gradle.kts
        val placeholder = "su.SkrinVex.SkriPts.runtime.template"
        var data = manifest.copyOf()
        val chunkStart = 8

        // Патчим строки итеративно (offsets меняются после каждой замены)
        var changed = true
        while (changed) {
            changed = false
            val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.position(chunkStart)
            if ((bb.short.toInt() and 0xFFFF) != 0x0001) break
            val chunkHeaderSize = bb.short.toInt() and 0xFFFF
            val chunkSizeOffset = chunkStart + 4
            val chunkSize = bb.int
            val stringCount = bb.int
            bb.int // styleCount
            val flags = bb.int
            val isUtf8 = (flags and 0x100) != 0
            val stringsStartOffset = bb.int
            val offsetsBase = chunkStart + chunkHeaderSize
            val stringsBase = chunkStart + stringsStartOffset

            for (i in 0 until stringCount) {
                bb.position(offsetsBase + i * 4)
                val strOff = bb.int
                val absPos = stringsBase + strOff
                val str = try {
                    if (isUtf8) readUtf8Str(data, absPos) else readUtf16Str(data, absPos)
                } catch (_: Exception) { continue }

                // Заменяем только строки где placeholder встречается как пакет
                // (не трогаем имена классов вида su.SkrinVex.SkriPts.runtime.runtime.RuntimeActivity)
                if (placeholder !in str) continue
                val newStr = str.replace(placeholder, newPackage)

                data = patchStringInPool(data, chunkStart, chunkSizeOffset, chunkSize,
                    stringCount, offsetsBase, stringsBase, stringsStartOffset,
                    i, strOff, str, newStr, isUtf8)
                changed = true
                break
            }
        }
        return data
    }

    private fun readUtf16Str(buf: ByteArray, pos: Int): String {
        val len = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        return String(buf, pos + 2, len * 2, Charsets.UTF_16LE)
    }

    private fun readUtf8Str(buf: ByteArray, pos: Int): String {
        // UTF-8 в AXML: [utf16len: 1-2 bytes][utf8len: 1-2 bytes][data][0x00]
        var p = pos
        // skip utf16 length (1 or 2 bytes)
        if (buf[p].toInt() and 0x80 != 0) p += 2 else p += 1
        val utf8Len = if (buf[p].toInt() and 0x80 != 0) {
            val hi = (buf[p].toInt() and 0x7F) shl 8; p += 1
            hi or (buf[p].toInt() and 0xFF).also { p += 1 }
        } else { (buf[p].toInt() and 0xFF).also { p += 1 } }
        return String(buf, p, utf8Len, Charsets.UTF_8)
    }

    private fun patchStringInPool(
        original: ByteArray, chunkStart: Int, chunkSizeOffset: Int, oldChunkSize: Int,
        stringCount: Int, offsetsBase: Int, stringsBase: Int, stringsStartOffset: Int,
        targetIdx: Int, targetStrOff: Int, oldStr: String, newStr: String, isUtf8: Boolean
    ): ByteArray {
        val oldEncoded = if (isUtf8) encodeUtf8Str(oldStr) else encodeUtf16Str(oldStr)
        val newEncoded = if (isUtf8) encodeUtf8Str(newStr) else encodeUtf16Str(newStr)
        var delta = newEncoded.size - oldEncoded.size

        // Chunk size must be aligned to 4 bytes
        val newChunkSize = oldChunkSize + delta
        val alignedChunkSize = (newChunkSize + 3) and -4
        val padding = alignedChunkSize - newChunkSize
        delta += padding

        val absStrPos = stringsBase + targetStrOff
        val result = ByteArrayOutputStream()
        result.write(original, 0, absStrPos)
        result.write(newEncoded)
        if (padding > 0) result.write(ByteArray(padding))
        result.write(original, absStrPos + oldEncoded.size, original.size - absStrPos - oldEncoded.size)
        val newBuf = result.toByteArray()

        val bb = java.nio.ByteBuffer.wrap(newBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        for (i in (targetIdx + 1) until stringCount) {
            val offPos = offsetsBase + i * 4
            bb.position(offPos)
            val old = bb.int
            bb.position(offPos)
            bb.putInt(old + delta)
        }

        bb.position(chunkSizeOffset)
        bb.putInt(alignedChunkSize)

        bb.position(4)
        val oldFileSize = bb.int
        bb.position(4)
        bb.putInt(oldFileSize + delta)

        return newBuf
    }

    private fun encodeUtf16Str(s: String): ByteArray {
        val chars = s.toByteArray(Charsets.UTF_16LE)
        val len = byteArrayOf((s.length and 0xFF).toByte(), (s.length shr 8 and 0xFF).toByte())
        return len + chars + byteArrayOf(0, 0) // null terminator
    }

    private fun encodeUtf8Str(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        val utf16Len = s.length
        val out = ByteArrayOutputStream()
        // utf16 length
        if (utf16Len > 0x7F) { out.write((utf16Len shr 8) or 0x80); out.write(utf16Len and 0xFF) }
        else out.write(utf16Len)
        // utf8 length
        if (utf8.size > 0x7F) { out.write((utf8.size shr 8) or 0x80); out.write(utf8.size and 0xFF) }
        else out.write(utf8.size)
        out.write(utf8)
        out.write(0) // null terminator
        return out.toByteArray()
    }

    // ── Signing via apksig ──────────────────────────────────────────────────

    private fun signApk(input: File, output: File, ctx: Context) {
        val (privateKey, cert) = getOrCreateTestKey(ctx)
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "CERT", privateKey, listOf(cert)
        ).build()
        com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(24)
            .build()
            .sign()
    }


    /**
     * Патчит resources.arsc — заменяет строку "SkriPts App" на имя проекта.
     * resources.arsc тоже содержит string pool с той же структурой что AXML.
     */
    private fun patchArscAppName(arsc: ByteArray, newName: String): ByteArray {
        return patchArscString(arsc, "SkriPts App", newName)
    }

    private fun patchArscString(data: ByteArray, oldStr: String, newStr: String): ByteArray {
        // resources.arsc: RES_TABLE (0x0002) содержит вложенные chunks.
        // String pool (0x0001) начинается сразу после заголовка таблицы (offset 12).
        val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        val end = data.size
        while (pos < end - 8) {
            bb.position(pos)
            val type = bb.short.toInt() and 0xFFFF
            val headerSize = bb.short.toInt() and 0xFFFF
            val chunkSize = bb.int
            if (chunkSize <= 0 || pos + chunkSize > end) break

            if (type == 0x0001 && headerSize >= 28) {
                val stringCount = bb.int
                bb.int // styleCount
                val flags = bb.int
                val isUtf8 = (flags and 0x100) != 0
                val stringsStart = bb.int
                bb.int // stylesStart

                val offsetsBase = pos + headerSize
                val stringsBase = pos + stringsStart

                for (i in 0 until stringCount) {
                    bb.position(offsetsBase + i * 4)
                    val strOff = bb.int
                    val absPos = stringsBase + strOff
                    if (absPos >= data.size) continue
                    val str = try {
                        if (isUtf8) readUtf8Str(data, absPos) else readUtf16Str(data, absPos)
                    } catch (_: Exception) { continue }
                    if (str == oldStr) {
                        return patchStringInPool(data, pos, pos + 4, chunkSize,
                            stringCount, offsetsBase, stringsBase, stringsStart,
                            i, strOff, oldStr, newStr, isUtf8)
                    }
                }
                // Нашли string pool, но строка не найдена — дальше не ищем
                break
            }
            // Для RES_TABLE (0x0002) — заходим внутрь (пропускаем только заголовок)
            if (type == 0x0002) {
                pos += headerSize
            } else {
                pos += chunkSize
            }
        }
        return data
    }

    private fun getOrCreateTestKey(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val alias = "skripts_sign_v2"
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

        if (!ks.containsAlias(alias)) {
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN
            )
                .setKeySize(2048)
                .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA1, android.security.keystore.KeyProperties.DIGEST_SHA256, android.security.keystore.KeyProperties.DIGEST_NONE)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=SkriPts, O=SkriPts"))
                .setCertificateSerialNumber(java.math.BigInteger.ONE)
                .setCertificateNotBefore(java.util.Date())
                .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000))
                .build()
            java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                .also { it.initialize(spec) }
                .generateKeyPair()
        }

        val key = ks.getKey(alias, null) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return key to cert
    }
}
