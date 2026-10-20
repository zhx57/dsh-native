package dev.dsh.nativeapp.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.dsh.nativeapp.DshApplication

/** Simple factory: the app already owns every dependency the ViewModel needs. */
object ViewModelFactories {
    fun chat(app: DshApplication): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(app) as T
        }
}
