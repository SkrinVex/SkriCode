package su.SkrinVex.SkriPts.runtime

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

        SimEngine.appContext = applicationContext
        ExprEval.appContext = applicationContext
        val dm = resources.displayMetrics
        ExprEval.screenWidth = dm.widthPixels.toFloat()
        ExprEval.screenHeight = dm.heightPixels.toFloat()

        val project = loadProject() ?: run {
            setContent { Text("Ошибка загрузки проекта") }
            return
        }

        requestedOrientation = if (project.orientation == ProjectOrientation.LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        extractSprites(project)
        SimEngine.projectName = project.name
        vm.start(project)

        setContent {
            SkriPtsTheme {
                val state by vm.simState.collectAsState()
                state?.let { s ->
                    SimulationScreen(
                        state = s,
                        onTap = { vm.handleTap(it) },
                        onHoldStart = { name, pid -> vm.handleHoldStart(name, pid) },
                        onHoldEnd = { vm.handleHoldEnd(it) },
                        onJoystickMove = { name, dx, dy, pid -> vm.handleJoystickMove(name, dx, dy, pid) },
                        onJoystickRelease = { vm.handleJoystickRelease(it) },
                        onBack = { finishAffinity() },
                        debugMode = false,
                        showHitboxes = false
                    )
                }
            }
        }
    }

    private fun loadProject(): ScriptProject? {
        return try {
            val encrypted = assets.open("project.dat").readBytes()
            val key = assets.open("key.dat").readBytes()
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val json = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            Gson().fromJson(json, ScriptProject::class.java)
        } catch (_: Exception) {
            try {
                val json = assets.open("project.dat").bufferedReader().readText()
                Gson().fromJson(json, ScriptProject::class.java)
            } catch (_: Exception) { null }
        }
    }

    private fun extractSprites(project: ScriptProject) {
        val spritesDir = File(filesDir, "sprites/${project.id}").also { it.mkdirs() }
        project.sprites.orEmpty().forEach { sprite ->
            val dest = File(spritesDir, sprite.fileName)
            if (!dest.exists()) {
                try {
                    assets.open("sprites/${sprite.fileName}").use { it.copyTo(dest.outputStream()) }
                } catch (_: Exception) {}
            }
        }
    }
}
