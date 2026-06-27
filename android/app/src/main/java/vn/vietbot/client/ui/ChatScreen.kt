package vn.vietbot.client.ui

import android.net.Uri
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import vn.vietbot.client.translation.TranslationManager
import androidx.compose.ui.graphics.Color.Companion.Gray
import androidx.compose.ui.graphics.Color.Companion.Green
import androidx.compose.ui.text.style.TextDecoration

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
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Unified connection/state button
        val context = LocalContext.current
        val hasInternet = remember {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
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
                    containerColor = when {
                        isConnecting -> Color(0xFFFF9800)
                        isConnected -> Color(0xFF4CAF50)
                        hasInternet -> MaterialTheme.colorScheme.primary
                        else -> Color(0xFF9E9E9E)
                    },
                    disabledContainerColor = Color(0xFF9E9E9E)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    isConnecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.connecting),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    isConnected -> {
                        // Show state emoji (mic/speaker/thinking) when connected
                        if (stateEmoji.isNotEmpty()) {
                            Text(
                                text = stateEmoji,
                                style = TextStyle(fontSize = 18.sp),
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.connected),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        // Show state text (Listening/Speaking) when available
                        val stateText = when (deviceState.name) {
                            "LISTENING" -> stringResource(R.string.device_state_listening)
                            "SPEAKING" -> stringResource(R.string.device_state_speaking)
                            else -> null
                        }
                        if (stateText != null) {
                            Text(
                                text = " • $stateText",
                                style = TextStyle(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                            )
                        }
                    }
                    hasInternet -> {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.connect),
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Filled.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Không có mạng",
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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

        // Text input area (context/hasInternet already declared above for the connection button)
        val canSendText = isConnected && hasInternet

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Take the union of navigation-bar and IME insets so the input
                // sits flush above the keyboard (IME > nav bar when shown) and
                // above the nav bar when the keyboard is hidden. Avoids the gap
                // caused by stacking navigationBarsPadding + imePadding.
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Focus/keyboard controllers — hide the soft keyboard after send
            // because the user mainly drives the assistant by voice, not by
            // typing consecutive messages.
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            fun submitAndHideKeyboard() {
                if (textInput.isNotBlank()) {
                    viewModel.sendTextMessage(textInput)
                    textInput = ""
                }
                keyboardController?.hide()
                focusManager.clearFocus()
            }
            OutlinedTextField(
                value = textInput,
                onValueChange = { if (canSendText) textInput = it },
                modifier = Modifier.weight(1f),
                enabled = canSendText,
                placeholder = { Text(stringResource(if (canSendText) R.string.text_input_placeholder else R.string.connect_to_chat)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { submitAndHideKeyboard() }
                ),
                singleLine = true
            )
            IconButton(
                onClick = { submitAndHideKeyboard() },
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
    // Tap-to-toggle timestamp only available once the bubble is finalized
    val canToggleTimestamp = !message.isStreaming

    // User messages (STT) aligned to right, Assistant messages (TTS) aligned to left
    val alignment = if (isCurrentUser) Alignment.End else Alignment.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        val cardModifier = Modifier.widthIn(max = 320.dp)
        val cardColors = CardDefaults.cardColors(
            containerColor = if (isCurrentUser)
                MaterialTheme.colorScheme.primary
            else
                bubbleColor
        )
        val cardShape = RoundedCornerShape(10.dp)
        val cardContent: @Composable () -> Unit = {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                // Filter out content:// URIs from message text
                val displayMessage = message.message.replace(Regex("content://[\\w./\\-]+"), "")
                // Append a blinking cursor for streaming bubbles so the user knows
                // text is still arriving. The cursor is part of the text, so it
                // wraps naturally with the rest of the message.
                val cursorSuffix = if (message.isStreaming && displayMessage.isNotBlank()) "▌" else ""

                // Karaoke-style highlighting: when the bubble has tracked TTS
                // segments, render played segments in normal style and the
                // upcoming (about-to-be-spoken) segment darker + larger + bold
                // + underlined so the user clearly sees which words audio is
                // about to reach. Falls back to plain text for non-TTS bubbles
                // (user STT, finalized messages).
                val playedColor = if (isCurrentUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    textColor
                // Upcoming segment uses full strength color (more contrast than
                // dim alpha) plus +1sp size, bold, underline — strong enough to
                // be unambiguous even in translation mode where the bot speaks
                // slower than text arrives.
                val upcomingColor = playedColor
                val segments = message.segments
                if (segments != null && segments.isNotEmpty() && !isCurrentUser) {
                    val annotated = buildAnnotatedString {
                        segments.forEachIndexed { index, seg ->
                            withStyle(
                                SpanStyle(
                                    color = if (seg.isPlayed) playedColor else upcomingColor,
                                    fontSize = if (seg.isPlayed) fontSize.sp else (fontSize + 1).sp,
                                    fontWeight = if (seg.isPlayed) FontWeight.Normal else FontWeight.Bold,
                                    textDecoration = if (seg.isPlayed) TextDecoration.None else TextDecoration.Underline
                                )
                            ) {
                                append(seg.text)
                            }
                            if (index < segments.lastIndex) append(" ")
                        }
                        if (cursorSuffix.isNotEmpty()) append(cursorSuffix)
                    }
                    Text(
                        text = annotated,
                        style = TextStyle(
                            fontFamily = getFontFamily(fontFamily),
                            fontSize = fontSize.sp
                        ),
                        modifier = Modifier.padding(top = 0.dp)
                    )
                } else {
                    val finalText = displayMessage.ifBlank { "" } + cursorSuffix
                    Text(
                        text = finalText,
                        style = TextStyle(
                            fontFamily = getFontFamily(fontFamily),
                            fontSize = fontSize.sp,
                            color = playedColor
                        ),
                        // softWrap defaults to true; text wraps to next line automatically
                        // when it approaches the bubble's right edge (widthIn max 280.dp).
                        modifier = Modifier.padding(top = if (displayMessage.isNotBlank()) 0.dp else 0.dp)
                    )
                }
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
                if (showTimestamp && canToggleTimestamp) {
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
        // Two Card overloads: clickable (when finalized) and non-clickable (when streaming)
        if (canToggleTimestamp) {
            Card(
                onClick = { showTimestamp = !showTimestamp },
                shape = cardShape,
                colors = cardColors,
                modifier = cardModifier
            ) { cardContent() }
        } else {
            Card(
                shape = cardShape,
                colors = cardColors,
                modifier = cardModifier
            ) { cardContent() }
        }
    }
}

@Composable
fun TranslationDisplayCard(
    translation: TranslationDisplay,
    textColor: Color = Color.Black,
    bubbleColor: Color = Color(0xFFE3F2FD),
    fontSize: Int = 16,
    fontFamily: String = "System"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE8F5E9) // Light green background for translation
        ),
        border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Translation header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🌍 Bản dịch",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50)
                )
                if (!translation.isComplete) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Translation segments - played text is bold/highlighted, upcoming text is dimmed
            val annotatedText = buildAnnotatedString {
                translation.segments.forEachIndexed { index, segment ->
                    withStyle(
                        style = SpanStyle(
                            color = if (segment.isPlayed) textColor else textColor.copy(alpha = 0.5f),
                            fontWeight = if (segment.isPlayed) FontWeight.Bold else FontWeight.Normal,
                            textDecoration = if (segment.isPlayed) TextDecoration.Underline else TextDecoration.None
                        )
                    ) {
                        append(segment.text)
                    }
                    if (index < translation.segments.size - 1) {
                        append(" ")
                    }
                }
            }

            Text(
                text = annotatedText,
                style = TextStyle(
                    fontFamily = getFontFamily(fontFamily),
                    fontSize = fontSize.sp
                )
            )
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
