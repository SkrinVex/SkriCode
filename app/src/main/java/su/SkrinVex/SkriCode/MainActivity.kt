package su.SkrinVex.SkriCode

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import su.SkrinVex.SkriCode.data.ProjectOrientation
import su.SkrinVex.SkriCode.engine.ExprEval
import su.SkrinVex.SkriCode.engine.SimEngine
import su.SkrinVex.SkriCode.ui.sim.SimulationScreen
import su.SkrinVex.SkriCode.ui.editor.EditorScreen
import su.SkrinVex.SkriCode.ui.editor.EditorViewModel
import su.SkrinVex.SkriCode.ui.home.HomeScreen
import su.SkrinVex.SkriCode.ui.home.HomeViewModel
import su.SkrinVex.SkriCode.ui.resources.ResourcesScreen
import su.SkrinVex.SkriCode.ui.theme.AppTheme
import su.SkrinVex.SkriCode.ui.theme.SkriPtsTheme
import su.SkrinVex.SkriCode.ui.theme.ThemeManager

class MainActivity : ComponentActivity() {
    private val homeVm: HomeViewModel by viewModels()
    private val editorVm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        ThemeManager.init(this)
        // Устанавливаем размеры экрана для встроенных констант
        val dm = resources.displayMetrics
        ExprEval.updateDeviceResolution(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        // Контекст для сохранений
        SimEngine.appContext = applicationContext
        ExprEval.appContext = applicationContext
        
        // Проверяем, открыт ли файл .skripts
        val importUri = intent?.data
        if (importUri != null && intent?.action == android.content.Intent.ACTION_VIEW) {
            val fileName = importUri.lastPathSegment ?: ""
            if (fileName.endsWith(".skripts", ignoreCase = true)) {
                homeVm.importProject(importUri)
            }
        }
        
        setContent {
            var currentTheme by remember { mutableStateOf<AppTheme>(ThemeManager.getCurrentTheme()) }
            
            SkriPtsTheme {
                val editorState by editorVm.state.collectAsState()
                val simState by editorVm.simState.collectAsState()
                val simRunCount by editorVm.simRunCount.collectAsState()
                var screen by remember { mutableStateOf("home") }
                var landscapeActive by remember { mutableStateOf(false) }

                LaunchedEffect(editorState.orientation) {
                    ExprEval.setOrientation(editorState.orientation)
                }

                // Применяем ориентацию — только портрет везде кроме sim/локации/позиционировщика
                val isLandscape = landscapeActive ||
                    (screen == "sim" && editorState.orientation == ProjectOrientation.LANDSCAPE)
                SideEffect {
                    val target = if (isLandscape)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    if (requestedOrientation != target) requestedOrientation = target
                }

                LaunchedEffect(simRunCount) {
                    if (simRunCount > 0 && editorState.validationErrors.isEmpty() && simState != null) {
                        screen = "sim"
                    }
                }

                when (screen) {
                    "sim" -> SimulationScreen(
                        state = simState!!,
                        simRunCount = simRunCount,
                        onTap = { objName -> editorVm.handleTap(objName) },
                        onHoldStart = { objName, pid -> editorVm.handleHoldStart(objName, pid) },
                        onHoldEnd = { pid -> editorVm.handleHoldEnd(pid) },
                        onJoystickMove = { name, dx, dy, pid -> editorVm.handleJoystickMove(name, dx, dy, pid) },
                        onJoystickRelease = { pid -> editorVm.handleJoystickRelease(pid) },
                        onBack = { editorVm.stopPhysics(); landscapeActive = false; screen = "editor" },
                        onClearLogs = { editorVm.clearSimLogs() },
                        debugMode = ThemeManager.debugMode,
                        showHitboxes = ThemeManager.showHitboxes
                    )
                    "editor" -> {
                        BackHandler { screen = "resources" }
                        EditorScreen(
                            vm = editorVm,
                            onBack = { screen = "resources" },
                            onLandscapeNeeded = { landscapeActive = it }
                        )
                    }
                    "resources" -> {
                        BackHandler { screen = "home"; homeVm.refresh() }
                        ResourcesScreen(
                            vm = editorVm,
                            projectName = editorState.projectName,
                            onOpenEditor = { screen = "editor" },
                            onBack = { screen = "home"; homeVm.refresh() }
                        )
                    }
                    else -> {
                        var showExitDialog by remember { mutableStateOf(false) }
                        BackHandler { showExitDialog = true }
                        HomeScreen(
                            vm = homeVm,
                            onOpenProject = { id ->
                                if (id != null) editorVm.loadProject(id)
                                else editorVm.loadProject(homeVm.lastCreatedId)
                                screen = "resources"
                            },
                            onThemeChanged = { currentTheme = ThemeManager.getCurrentTheme() }
                        )
                        if (showExitDialog) {
                            AlertDialog(
                                onDismissRequest = { showExitDialog = false },
                                containerColor = su.SkrinVex.SkriCode.ui.theme.Surface2,
                                title = { Text("Выйти из приложения?", color = su.SkrinVex.SkriCode.ui.theme.TextPrim) },
                                confirmButton = {
                                    Button(
                                        onClick = { finish() },
                                        colors = ButtonDefaults.buttonColors(containerColor = su.SkrinVex.SkriCode.ui.theme.Danger)
                                    ) { Text("Выйти") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showExitDialog = false }) {
                                        Text("Отмена", color = su.SkrinVex.SkriCode.ui.theme.TextSec)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val importUri = intent.data
        if (importUri != null && intent.action == android.content.Intent.ACTION_VIEW) {
            val fileName = importUri.lastPathSegment ?: ""
            if (fileName.endsWith(".skripts", ignoreCase = true)) {
                homeVm.importProject(importUri)
            }
        }
    }
}
