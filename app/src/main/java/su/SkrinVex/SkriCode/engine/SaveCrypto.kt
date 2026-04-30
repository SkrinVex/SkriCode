package su.SkrinVex.SkriCode.engine

import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SaveCrypto {

    private const val PREFS = "skripts_keyvault"
    private const val SALT = "SkriPts_Salt_v1"

    fun listKeys(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.toList().sorted()

    fun hasKey(ctx: Context, projectName: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(projectName)

    fun saveKey(ctx: Context, projectName: String, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(projectName, key).apply()
    }

    fun getKey(ctx: Context, projectName: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(projectName, null)

    fun deleteKey(ctx: Context, projectName: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(projectName).apply()
    }

    /** Шифрует строку. Возвращает Base64-строку вида IV:CipherText */
    fun encrypt(plaintext: String, key: String): String {
        val secretKey = deriveKey(key)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$ivB64:$dataB64"
    }

    /** Дешифрует строку вида IV:CipherText. Возвращает null при ошибке */
    fun decrypt(ciphertext: String, key: String): String? = runCatching {
        val parts = ciphertext.split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val secretKey = deriveKey(key)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveKey(password: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), SALT.toByteArray(), 10000, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}
