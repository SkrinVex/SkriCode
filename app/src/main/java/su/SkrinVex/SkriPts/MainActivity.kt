package su.SkrinVex.SkriPts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import su.SkrinVex.SkriPts.engine.ExprEval
import su.SkrinVex.SkriPts.engine.SimEngine
import su.SkrinVex.SkriPts.ui.sim.SimulationScreen
import su.SkrinVex.SkriPts.ui.editor.EditorScreen
import su.SkrinVex.SkriPts.ui.editor.EditorViewModel
import su.SkrinVex.SkriPts.ui.home.HomeScreen
import su.SkrinVex.SkriPts.ui.home.HomeViewModel
import su.SkrinVex.SkriPts.ui.resources.ResourcesScreen
import su.SkrinVex.SkriPts.ui.theme.AppTheme
import su.SkrinVex.SkriPts.ui.theme.SkriPtsTheme
import su.SkrinVex.SkriPts.ui.theme.ThemeManager

class MainActivity : ComponentActivity() {
    private val homeVm: HomeViewModel by viewModels()
    private val editorVm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        // Устанавливаем размеры экрана для встроенных констант
        val dm = resources.displayMetrics
        ExprEval.screenWidth = dm.widthPixels.toFloat()
        ExprEval.screenHeight = dm.heightPixels.toFloat()
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
                var screen by remember { mutableStateOf("home") }

                LaunchedEffect(editorState.simRunCount) {
                    if (editorState.simRunCount > 0 && editorState.validationErrors.isEmpty()
                        && editorState.simState != null) {
                        screen = "sim"
                    }
                }

                when (screen) {
                    "sim" -> SimulationScreen(
                        state = editorState.simState!!,
                        simRunCount = editorState.simRunCount,
                        onTap = { objName -> editorVm.handleTap(objName) },
                        onHoldStart = { objName, pid -> editorVm.handleHoldStart(objName, pid) },
                        onHoldEnd = { pid -> editorVm.handleHoldEnd(pid) },
                        onJoystickMove = { name, dx, dy, pid -> editorVm.handleJoystickMove(name, dx, dy, pid) },
                        onJoystickRelease = { pid -> editorVm.handleJoystickRelease(pid) },
                        onBack = { editorVm.stopPhysics(); screen = "editor" },
                        onClearLogs = { editorVm.clearSimLogs() },
                        debugMode = ThemeManager.debugMode,
                        showHitboxes = ThemeManager.showHitboxes
                    )
                    "editor" -> {
                        BackHandler { screen = "resources" }
                        EditorScreen(
                            vm = editorVm,
                            onBack = { screen = "resources" }
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
                                containerColor = su.SkrinVex.SkriPts.ui.theme.Surface2,
                                title = { Text("Выйти из приложения?", color = su.SkrinVex.SkriPts.ui.theme.TextPrim) },
                                confirmButton = {
                                    Button(
                                        onClick = { finish() },
                                        colors = ButtonDefaults.buttonColors(containerColor = su.SkrinVex.SkriPts.ui.theme.Danger)
                                    ) { Text("Выйти") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showExitDialog = false }) {
                                        Text("Отмена", color = su.SkrinVex.SkriPts.ui.theme.TextSec)
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
