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
import su.SkrinVex.SkriPts.ui.editor.EditorScreen
import su.SkrinVex.SkriPts.ui.editor.EditorViewModel
import su.SkrinVex.SkriPts.ui.home.HomeScreen
import su.SkrinVex.SkriPts.ui.home.HomeViewModel
import su.SkrinVex.SkriPts.ui.sim.SimulationScreen
import su.SkrinVex.SkriPts.ui.theme.SkriPtsTheme

class MainActivity : ComponentActivity() {
    private val homeVm: HomeViewModel by viewModels()
    private val editorVm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Устанавливаем размеры экрана для встроенных констант
        val dm = resources.displayMetrics
        ExprEval.screenWidth = dm.widthPixels.toFloat()
        ExprEval.screenHeight = dm.heightPixels.toFloat()
        setContent {
            SkriPtsTheme {
                val editorState by editorVm.state.collectAsState()
                var screen by remember { mutableStateOf("home") }

                LaunchedEffect(editorState.simRunCount) {
                    if (editorState.simRunCount > 0 && screen == "editor"
                        && editorState.validationErrors.isEmpty()
                        && editorState.simState != null) {
                        screen = "sim"
                    }
                }

                when (screen) {
                    "sim" -> SimulationScreen(
                        state = editorState.simState!!,
                        onTap = { objName -> editorVm.handleTap(objName) },
                        onBack = { screen = "editor" }
                    )
                    "editor" -> {
                        BackHandler { screen = "home"; homeVm.refresh() }
                        EditorScreen(
                            vm = editorVm,
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
                                screen = "editor"
                            }
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
}
