package su.SkrinVex.SkriCode.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import su.SkrinVex.SkriCode.data.ImportException
import su.SkrinVex.SkriCode.data.ProjectIO
import su.SkrinVex.SkriCode.data.ProjectRepository
import su.SkrinVex.SkriCode.data.ScriptProject
import java.util.UUID

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val _projects = MutableStateFlow<List<ScriptProject>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    var lastCreatedId: String = ""
        private set

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences("project_open_times", android.content.Context.MODE_PRIVATE)

    fun recordOpen(id: String) {
        prefs().edit().putLong(id, System.currentTimeMillis()).apply()
    }

    fun refresh() {
        viewModelScope.launch {
            val p = prefs()
            _projects.value = ProjectRepository.list(getApplication())
                .sortedByDescending { p.getLong(it.id, 0L) }
        }
    }

    fun createProject(name: String) {
        val id = UUID.randomUUID().toString()
        lastCreatedId = id
        ProjectRepository.save(getApplication(), ScriptProject(id = id, name = name))
        refresh()
    }

    fun delete(id: String) {
        // Удаляем спрайты и звуки проекта
        val project = ProjectRepository.load(getApplication(), id)
        project?.sprites.orEmpty().forEach { sprite ->
            su.SkrinVex.SkriCode.data.SpriteRepository.delete(getApplication(), id, sprite.fileName)
        }
        project?.sounds.orEmpty().forEach { sound ->
            su.SkrinVex.SkriCode.data.SoundRepository.delete(getApplication(), id, sound.fileName)
        }
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
                    val toSave = if (existing != null) {
                        val newId = UUID.randomUUID().toString()
                        // Копируем спрайты и звуки под новый ID
                        su.SkrinVex.SkriCode.data.SpriteRepository.copyAll(getApplication(), imported.id, newId)
                        su.SkrinVex.SkriCode.data.SoundRepository.copyAll(getApplication(), imported.id, newId)
                        imported.copy(id = newId, name = "${imported.name} (импорт)")
                    } else imported
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
