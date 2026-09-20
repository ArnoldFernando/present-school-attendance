package com.attendancefr.ui.screens.exports

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.export.ExcelExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ExportFile(
    val file: File,
    val name: String,
    val sizeKb: Long,
    val dateModified: Long,
)

@HiltViewModel
class ExportsViewModel @Inject constructor(
    application: Application,
    private val exporter: ExcelExporter,
) : AndroidViewModel(application) {

    private val _files = MutableStateFlow<List<ExportFile>>(emptyList())
    val files: StateFlow<List<ExportFile>> = _files

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val dir = exporter.exportsDir()
            val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".xlsx", true) }
                ?.map {
                    ExportFile(
                        file = it,
                        name = it.name,
                        sizeKb = it.length() / 1024,
                        dateModified = it.lastModified(),
                    )
                }
                ?.sortedByDescending { it.dateModified }
                ?: emptyList()
            _files.value = list
        }
    }

    fun delete(file: File) {
        viewModelScope.launch {
            file.delete()
            refresh()
            _message.value = "Deleted ${file.name}"
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

        fun showMessage(msg: String) {
        _message.value = msg
    }
}
