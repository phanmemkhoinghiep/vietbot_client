package vn.vietbot.client.ui

import android.net.Uri
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
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

    // Device state to emoji mapping (shown when emotion is neutral or during state)
    val stateEmojiMap = mapOf(
        "LISTENING" to "🎤",
        "SPEAKING" to "🔊",
        "THINKING" to "🤔",
        "IDLE" to "😴",
        "CONNECTING" to "🔄"
    )
    val stateEmoji: String = stateEmojiMap[deviceState.name] ?: ""

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
            val context = LocalContext.current
            val hasInternet = remember {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }

            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else if (!isConnecting && hasInternet) {
                        viewModel.connect()
                    }
                },
                enabled = !isConnecting && (isConnected || hasInternet),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFFF44336) else MaterialTheme.colorScheme.primary,
                    disabledContainerColor = if (!isConnected && !hasInternet) Color(0xFF9E9E9E) else MaterialTheme.colorScheme.primary
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
                    text = when {
                        isConnected -> stringResource(R.string.disconnect)
                        !hasInternet -> "Không có mạng"
                        else -> stringResource(R.string.connect)
                    }
                )
            }
        }

        // Device state emoji display (compact)
        Box(
            modifier = Modifier.padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                stateEmoji.isNotEmpty() -> {
                    Text(
                        text = stateEmoji,
                        style = TextStyle(fontSize = 24.sp)
                    )
                }
                else -> {
                    Text(
                        text = emotionEmojiMap[emotion.lowercase()] ?: "😐",
                        style = TextStyle(fontSize = 24.sp)
                    )
                }
            }
        }

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
        val context = LocalContext.current
        val hasInternet = remember {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        val canSendText = isConnected && hasInternet

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { if (canSendText) textInput = it },
                modifier = Modifier.weight(1f),
                enabled = canSendText,
                placeholder = { Text(stringResource(if (canSendText) R.string.text_input_placeholder else R.string.connect_to_chat)) },
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
                },
                enabled = canSendText && textInput.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = if (canSendText) MaterialTheme.colorScheme.primary else Color.Gray
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
    // Handle separator message
    if (message.isSeparator) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        return
    }

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
                // Filter out content:// URIs from message text
                val displayMessage = message.message.replace(Regex("content://[\\w./\\-]+"), "")
                Text(
                    text = displayMessage.ifBlank { "" },
                    style = TextStyle(
                        fontFamily = getFontFamily(fontFamily),
                        fontSize = fontSize.sp,
                        color = if (isCurrentUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            textColor
                    ),
                    modifier = Modifier.padding(top = if (displayMessage.isNotBlank()) 0.dp else 0.dp)
                )
                // Display image if present (correct aspect ratio, no path text)
                message.imageUri?.let { uriString ->
                    AsyncImage(
                        model = Uri.parse(uriString),
                        contentDescription = "Camera photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
                // Display video if present with playback controls
                message.videoUri?.let { uriString ->
                    VideoPlayer(
                        uri = Uri.parse(uriString),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
                // Display audio player if present
                message.audioUri?.let { uriString ->
                    AudioPlayer(
                        uri = Uri.parse(uriString),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
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

@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            android.widget.VideoView(context).apply {
                setVideoURI(uri)
                setMediaController(android.widget.MediaController(context).apply {
                    setAnchorView(this@apply)
                })
            }
        },
        modifier = modifier.height(200.dp)
    )
}

@Composable
fun AudioPlayer(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🎵", style = TextStyle(fontSize = 24.sp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Audio recording",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "audio/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback: play with MediaPlayer
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = "Play audio",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
