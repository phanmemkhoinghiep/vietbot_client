package vn.vietbot.client.domain

import vn.vietbot.client.data.model.ServerFormData
import vn.vietbot.client.data.model.ValidationResult
import javax.inject.Inject

// :feature:form/domain/ValidateFormUseCase.kt
class ValidateFormUseCase @Inject constructor() {
    operator fun invoke(formData: ServerFormData): ValidationResult {
        // Always valid since we use hardcoded Vietbot config
        return ValidationResult(true, emptyMap())
    }
}