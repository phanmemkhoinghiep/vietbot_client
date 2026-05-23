package vn.vietbot.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import vn.vietbot.client.R
import vn.vietbot.client.data.SettingsRepository

@Composable
fun ChatScreen(
    viewModel: ChatViewMode = hiltViewModel(),
    settingsRepository: SettingsRepository? = null,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.display.chatFlow.collectAsState()
    val emotion by viewModel.display.emotionFlow.collectAsState()
    val deviceState by viewModel.deviceStateFlow.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    var textInput by remember { mutableStateOf("") }

    // Get settings values
    val fontFamily = settingsRepository?.fontFamily ?: "System"
    val fontSize = settingsRepository?.fontSize ?: 16
    val textColorHex = settingsRepository?.textColorHex ?: "#000000"
    val bubbleColorHex = settingsRepository?.bubbleColorHex ?: "#E3F2FD"

    val textColor = try { Color(android.graphics.Color.parseColor(textColorHex)) } catch (e: Exception) { Color.Black }
    val bubbleColor = try { Color(android.graphics.Color.parseColor(bubbleColorHex)) } catch (e: Exception) { Color(0xFFE3F2FD) }

    // Emotion to emoji mapping
    val emotionEmojiMap = mapOf(
        "neutral" to "😐",
        "happy" to "😊",
        "laughing" to "😂",
        "funny" to "🤡",
        "sad" to "😢",
        "angry" to "😠",
        "crying" to "😭",
        "loving" to "🥰",
        "embarrassed" to "😳",
        "surprised" to "😮",
        "shocked" to "😱",
        "thinking" to "🤔",
        "winking" to "😉",
        "cool" to "😎",
        "relaxed" to "😌",
        "delicious" to "😋",
        "kissy" to "😘",
        "confident" to "😏",
        "sleepy" to "😴",
        "silly" to "🤪",
        "confused" to "😕"
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection status and button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection status indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = when {
                                isConnected -> Color(0xFF4CAF50)
                                isConnecting -> Color(0xFFFF9800)
                                else -> Color(0xFFE0E0E0)
                            },
                            shape = RoundedCornerShape(50)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isConnected -> stringResource(R.string.connected)
                        isConnecting -> stringResource(R.string.connecting)
                        else -> stringResource(R.string.disconnected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Connect/Disconnect button
            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else if (!isConnecting) {
                        viewModel.connect()
                    }
                },
                enabled = !isConnecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                )
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.LinkOff else Icons.Filled.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (isConnected) stringResource(R.string.disconnect) else stringResource(R.string.connect)
                )
            }
        }

        // Emotion display at top center with bigger emoji
        Box(
            modifier = Modifier
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Text(
                        text = emotionEmojiMap[emotion.lowercase()] ?: "😐",
                        style = TextStyle(
                            fontSize = 64.sp,
                            lineHeight = 64.sp
                        )
                    )
                }
            }
        }

        // Device State Text
        Text(
            text = deviceState.name.lowercase()
                .replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall,
            color = when (deviceState) {
                DeviceState.FATAL_ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.reversed()) { message ->
                ChatMessageItem(
                    message = message,
                    fontFamily = fontFamily,
                    fontSize = fontSize,
                    textColor = textColor,
                    bubbleColor = bubbleColor
                )
            }
        }

        // Text input area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.text_input_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (textInput.isNotBlank()) {
                            viewModel.sendTextMessage(textInput)
                            textInput = ""
                        }
                    }
                ),
                singleLine = true
            )
            IconButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendTextMessage(textInput)
                        textInput = ""
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send)
                )
            }
        }
    }
}


@Composable
fun ChatMessageItem(
    message: Message,
    fontFamily: String = "System",
    fontSize: Int = 16,
    textColor: Color = Color.Black,
    bubbleColor: Color = Color(0xFFE3F2FD)
) {
    val isCurrentUser = message.sender == "user"
    var showTimestamp by remember { mutableStateOf(false) }

    // User messages (STT) aligned to right, Assistant messages (TTS) aligned to left
    val alignment = if (isCurrentUser) Alignment.End else Alignment.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isCurrentUser)
                    MaterialTheme.colorScheme.primary
                else
                    bubbleColor
            ),
            modifier = Modifier.widthIn(max = 280.dp),
            onClick = { showTimestamp = !showTimestamp }
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCurrentUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        textColor
                )
                Text(
                    text = message.message,
                    style = TextStyle(
                        fontFamily = getFontFamily(fontFamily),
                        fontSize = fontSize.sp,
                        color = if (isCurrentUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            textColor
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (showTimestamp) {
                    Text(
                        text = message.nowInString,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrentUser)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else
                            textColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .align(Alignment.End)
                    )
                }
            }
        }
    }
}
