package su.SkrinVex.SkriPts.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import su.SkrinVex.SkriPts.data.ProjectRepository
import su.SkrinVex.SkriPts.data.ScriptProject
import java.util.UUID

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val _projects = MutableStateFlow<List<ScriptProject>>(emptyList())
    val projects = _projects.asStateFlow()

    // Последний созданный ID — чтобы MainActivity мог открыть его
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
}
