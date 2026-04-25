package su.SkrinVex.SkriPts.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import su.SkrinVex.SkriPts.data.ScriptProject
import su.SkrinVex.SkriPts.ui.theme.*

@Composable
fun HomeScreen(vm: HomeViewModel, onOpenProject: (String?) -> Unit) {
    val projects by vm.projects.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Box(Modifier.fillMaxSize().background(Navy900)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Surface1, shadowElevation = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Code, null, tint = Accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("SkriPts", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrim)
                        Text("Визуальный конструктор программ", fontSize = 12.sp, color = TextSec)
                    }
                }
            }

            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.FolderOpen, null, tint = TextSec, modifier = Modifier.size(64.dp))
                        Text("Нет проектов", color = TextPrim, fontWeight = FontWeight.Medium, fontSize = 18.sp)
                        Text("Нажми + чтобы создать первый", color = TextSec, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { onOpenProject(project.id) },
                            onDelete = { vm.delete(project.id) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent
        ) {
            Icon(Icons.Default.Add, "Новый проект", tint = Navy900)
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name -> vm.createProject(name); showNewDialog = false; onOpenProject(null) }
        )
    }
}

@Composable
private fun ProjectCard(project: ScriptProject, onOpen: () -> Unit, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, color = TextPrim, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val blockCount = project.scripts?.sumOf { it.blocks.size } ?: 0
                Text("${project.scripts?.size ?: 0} скриптов · $blockCount блоков", color = TextSec, fontSize = 13.sp)
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(Icons.Default.DeleteOutline, "Удалить", tint = Danger.copy(alpha = 0.7f))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = Surface2,
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = Danger) },
            title = { Text("Удалить проект?", color = TextPrim) },
            text = { Text("«${project.name}» будет удалён без возможности восстановления.", color = TextSec) },
            confirmButton = {
                Button(onClick = { onDelete(); showConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Отмена", color = TextSec) }
            }
        )
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        icon = { Icon(Icons.Default.CreateNewFolder, null, tint = Accent) },
        title = { Text("Новый проект", color = TextPrim) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название проекта") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                    cursorColor = Accent,
                    unfocusedTextColor = TextPrim,
                    focusedTextColor = TextPrim
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("Создать", color = Navy900) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSec) }
        }
    )
}
