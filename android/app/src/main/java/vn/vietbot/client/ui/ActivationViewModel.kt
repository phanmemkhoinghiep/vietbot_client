package vn.vietbot.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import vn.vietbot.client.data.FormRepository
import vn.vietbot.client.data.FormResult
import vn.vietbot.client.data.model.Activation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivationViewModel@Inject constructor(
    private val repository: FormRepository
) : ViewModel() {
    private val _activationFlow = MutableStateFlow<Activation?>(null)
    val activationFlow: StateFlow<Activation?> = _activationFlow
    init {
        viewModelScope.launch {
            repository.resultFlow.collect {
                (it as? FormResult.VietbotResult)?.let {
                    _activationFlow.value = it.otaResult?.activation
                }
            }
        }
    }

}