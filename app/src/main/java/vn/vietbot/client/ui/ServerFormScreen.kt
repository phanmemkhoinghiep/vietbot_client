package vn.vietbot.client.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import vn.vietbot.client.ui.FormViewModel

import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.vietbot.client.R
import vn.vietbot.client.UiState
import vn.vietbot.client.data.model.XiaoZhiConfig
import kotlinx.coroutines.flow.collect

// :feature:form/ui/ServerFormScreen.kt
@Composable
fun ServerFormScreen(
    viewModel: FormViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsState()
    val validationResult by viewModel.validationResult.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // WebSocket URL field
        OutlinedTextField(
            value = formState.xiaoZhiConfig.webSocketUrl,
            onValueChange = { viewModel.updateWebSocketUrl(it) },
            label = { Text(stringResource(R.string.websocket_url)) },
            modifier = Modifier.fillMaxWidth(),
            isError = validationResult.errors.containsKey("webSocketUrl"),
            supportingText = validationResult.errors["webSocketUrl"]?.let { { Text(it) } }
        )

        Button(
            onClick = { viewModel.submitForm() },
            enabled = uiState !is UiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.label_conn)) }

        when (val state = uiState) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Success -> Text(state.message, color = MaterialTheme.colorScheme.primary)
            is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is UiState.Idle -> {}
        }
    }
}