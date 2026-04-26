package su.SkrinVex.SkriPts.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import su.SkrinVex.SkriPts.data.ImportException
import su.SkrinVex.SkriPts.data.ProjectIO
import su.SkrinVex.SkriPts.data.ProjectRepository
import su.SkrinVex.SkriPts.data.ScriptProject
import java.util.UUID

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val _projects = MutableStateFlow<List<ScriptProject>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    var lastCreatedId: String = ""
        private set

    fun refresh() {
        viewModelScope.launch {
            _projects.value = ProjectRepository.list(getApplication())
        }
    }

    fun createProject(name: String) {
        val id = UUID.randomUUID().toString()
        lastCreatedId = id
        ProjectRepository.save(getApplication(), ScriptProject(id, name, emptyList()))
        refresh()
    }

    fun delete(id: String) {
        ProjectRepository.delete(getApplication(), id)
        refresh()
    }

    fun rename(id: String, newName: String) {
        val project = ProjectRepository.load(getApplication(), id) ?: return
        ProjectRepository.save(getApplication(), project.copy(name = newName))
        refresh()
    }

    fun exportProject(project: ScriptProject, uri: Uri) {
        viewModelScope.launch {
            runCatching { ProjectIO.export(getApplication(), project, uri) }
                .onFailure { _importError.value = "Ошибка экспорта: ${it.message}" }
        }
    }

    fun importProject(uri: Uri) {
        viewModelScope.launch {
            runCatching { ProjectIO.import(getApplication(), uri) }
                .onSuccess { imported ->
                    // Если проект с таким ID уже есть — генерируем новый ID
                    val existing = ProjectRepository.load(getApplication(), imported.id)
                    val toSave = if (existing != null)
                        imported.copy(id = UUID.randomUUID().toString(), name = "${imported.name} (импорт)")
                    else imported
                    ProjectRepository.save(getApplication(), toSave)
                    refresh()
                }
                .onFailure { e ->
                    _importError.value = when (e) {
                        is ImportException -> e.message
                        else -> "Не удалось импортировать: ${e.message}"
                    }
                }
        }
    }

    fun clearImportError() { _importError.value = null }
}
