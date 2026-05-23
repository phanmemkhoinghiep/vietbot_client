package vn.vietbot.client.data

import vn.vietbot.client.ApplicationScope
import vn.vietbot.client.Ota
import vn.vietbot.client.data.model.OtaResult
import vn.vietbot.client.data.model.ServerFormData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class FormResult{
    class VietbotResult(val otaResult: OtaResult?) : FormResult()
}

// :feature:form/data/FormRepository.kt
interface FormRepository {
    suspend fun submitForm(formData: ServerFormData)

    val resultFlow : StateFlow<FormResult?>
}




@Singleton
class FormRepositoryImpl @Inject constructor(
    private val ota: Ota,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val coroutineScope: CoroutineScope,
) : FormRepository {

    override suspend fun submitForm(formData: ServerFormData) {
        settingsRepository.transportType = formData.xiaoZhiConfig.transportType
        settingsRepository.webSocketUrl = formData.xiaoZhiConfig.webSocketUrl
        ota.checkVersion(formData.xiaoZhiConfig.qtaUrl)
        resultFlow.value = FormResult.VietbotResult(ota.otaResult)
        settingsRepository.mqttConfig = ota.otaResult?.mqttConfig
        print(ota.deviceInfo)
    }

    override val resultFlow: MutableStateFlow<FormResult?> = MutableStateFlow(null)
}
