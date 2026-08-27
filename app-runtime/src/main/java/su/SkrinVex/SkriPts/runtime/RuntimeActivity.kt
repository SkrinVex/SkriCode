package su.SkrinVex.SkriPts.runtime

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.data.*
import su.SkrinVex.SkriPts.engine.*
import su.SkrinVex.SkriPts.ui.sim.SimulationScreen
import su.SkrinVex.SkriPts.ui.theme.SkriPtsTheme
import com.google.gson.Gson
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RuntimeActivity : ComponentActivity() {

    private val vm: RuntimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        SimEngine.appContext = applicationContext
        ExprEval.appContext = applicationContext
        val dm = resources.displayMetrics
        ExprEval.updateDeviceResolution(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())

        val (project, key) = loadProject() ?: run {
            setContent {
                SkriPtsTheme {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("⚠", fontSize = 48.sp)
                            Text("Не удалось загрузить игру", color = Color.White,
                                fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Файл проекта повреждён или отсутствует",
                                color = Color(0xFF8892B0), fontSize = 14.sp)
                        }
                    }
                }
            }
            return
        }

        ExprEval.setOrientation(project.orientation ?: ProjectOrientation.PORTRAIT)
        requestedOrientation = if (project.orientation == ProjectOrientation.LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        extractSprites(project, key)
        extractSounds(project, key)
        SimEngine.projectName = project.name

        // Загружаем ключ шифрования сохранений если он был упакован в APK
        try {
            val encryptedSaveKey = assets.open("savekey.dat").readBytes()
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val saveKey = String(cipher.doFinal(encryptedSaveKey), Charsets.UTF_8)
            if (saveKey.isNotBlank()) SaveCrypto.saveKey(applicationContext, project.name, saveKey)
        } catch (_: Exception) {}

        vm.start(project)

        setContent {
            SkriPtsTheme {
                val state by vm.simState.collectAsState()
                val s = state
                if (s == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF4F8EF7),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                project.appLabel?.ifBlank { null } ?: project.name,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    SimulationScreen(
                        state = s,
                        onTap = { vm.handleTap(it) },
                        onHoldStart = { name, pid -> vm.handleHoldStart(name, pid) },
                        onHoldEnd = { vm.handleHoldEnd(it) },
                        onJoystickMove = { name, dx, dy, pid -> vm.handleJoystickMove(name, dx, dy, pid) },
                        onJoystickRelease = { vm.handleJoystickRelease(it) },
                        onBack = { finishAffinity() },
                        onClearLogs = { vm.clearLogs() },
                        debugMode = false,
                        showHitboxes = false
                    )
                }
            }
        }
    }

    private fun loadProject(): Pair<ScriptProject, ByteArray>? {
        return try {
            // Сначала читаем зашифрованный проект чтобы получить project.id
            val encrypted = assets.open("project.dat").readBytes()
            // Пробуем расшифровать — для этого нужен project.id, но он внутри зашифрованного JSON
            // Решение: храним project.id в отдельном файле или деривируем из packageName
            // Но packageName может быть любым — нужен project.id
            // Временное решение: храним project.id в незашифрованном виде в assets/project_id.txt
            val projectId = try {
                assets.open("project_id.txt").bufferedReader().readText().trim()
            } catch (_: Exception) {
                // Fallback: пробуем расшифровать без ключа (незашифрованный проект)
                val json = String(encrypted, Charsets.UTF_8)
                val project = Gson().fromJson(json, ScriptProject::class.java)
                return project to ByteArray(0)
            }
            
            val key = deriveProjectKey(projectId)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val json = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            val project = Gson().fromJson(json, ScriptProject::class.java)
            project to key
        } catch (_: Exception) {
            try {
                val json = assets.open("project.dat").bufferedReader().readText()
                val project = Gson().fromJson(json, ScriptProject::class.java)
                project to ByteArray(0)
            } catch (_: Exception) { null }
        }
    }

    private companion object {
        private const val KEY_SALT = "SkriPts_Project_Key_v1_\$2f8xQz"
        
        private fun deriveProjectKey(projectId: String): ByteArray {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest("$KEY_SALT:$projectId".toByteArray(Charsets.UTF_8))
        }
    }

    private fun extractSprites(project: ScriptProject, key: ByteArray) {
        // Путь должен совпадать с SpriteRepository: filesDir/projects/{id}/sprites/
        val spritesDir = File(filesDir, "projects/${project.id}/sprites").also { it.mkdirs() }
        val cipher = if (key.isNotEmpty()) {
            Cipher.getInstance("AES/ECB/PKCS5Padding").also {
                it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            }
        } else null
        project.sprites.orEmpty().forEach { sprite ->
            val dest = File(spritesDir, sprite.fileName)
            if (!dest.exists()) {
                try {
                    val raw = assets.open("sprites/${sprite.fileName}").readBytes()
                    dest.writeBytes(if (cipher != null) cipher.doFinal(raw) else raw)
                } catch (_: Exception) {}
            }
        }
    }

    private fun extractSounds(project: ScriptProject, key: ByteArray) {
        // Путь должен совпадать с SoundRepository: filesDir/projects/{id}/sounds/
        val soundsDir = File(filesDir, "projects/${project.id}/sounds").also { it.mkdirs() }
        val cipher = if (key.isNotEmpty()) {
            Cipher.getInstance("AES/ECB/PKCS5Padding").also {
                it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            }
        } else null
        project.sounds.orEmpty().forEach { sound ->
            val dest = File(soundsDir, sound.fileName)
            if (!dest.exists()) {
                try {
                    val raw = assets.open("sounds/${sound.fileName}").readBytes()
                    dest.writeBytes(if (cipher != null) cipher.doFinal(raw) else raw)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.pauseAudio()
    }

    override fun onResume() {
        super.onResume()
        vm.resumeAudio()
    }
}
