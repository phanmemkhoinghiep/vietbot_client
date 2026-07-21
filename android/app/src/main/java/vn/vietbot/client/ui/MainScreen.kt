package vn.vietbot.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.util.Log
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.os.Environment
import android.content.ContentValues
import vn.vietbot.client.R
import vn.vietbot.client.audio.AudioDeviceSelector
import vn.vietbot.client.data.CameraSource
import vn.vietbot.client.data.MediaHistoryItem
import vn.vietbot.client.data.MicSource
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.data.SpeakerOutput
import vn.vietbot.client.mcp.SmartGlassesManager
import vn.vietbot.client.ui.theme.AccentNeon
import vn.vietbot.client.ui.theme.AccentPink
import vn.vietbot.client.ui.theme.BgCard
import vn.vietbot.client.ui.theme.BgDark
import vn.vietbot.client.ui.theme.BorderNeon
import vn.vietbot.client.ui.theme.TextMuted
import vn.vietbot.client.util.TimeUtils
import vn.vietbot.client.ui.theme.TextSecondary

sealed class BottomNavItem(
    val route: String,
    val title: String,
    val titleVi: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : BottomNavItem("home", "Home", "Trang chủ", Icons.Filled.Home, Icons.Outlined.Home)
    data object Chat : BottomNavItem("chat", "Chat", "Trò chuyện", Icons.Filled.Chat, Icons.Outlined.Chat)
    data object Media : BottomNavItem("media", "Media", "Media", Icons.Filled.MusicNote, Icons.Outlined.MusicNote)
    data object Settings : BottomNavItem("settings", "Settings", "Cài đặt", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    chatViewModel: ChatViewMode? = null,
    modifier: Modifier = Modifier,
    onNavigateToForm: () -> Unit = {},
    onNavigateToChat: () -> Unit = {}
) {
    val navItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Chat,
        BottomNavItem.Media,
        BottomNavItem.Settings
    )
    var selectedIndex by remember { mutableIntStateOf(0) }
    var showGlassesSelection by remember { mutableStateOf(false) }

    // Get glasses manager singleton
    val glassesManager = remember { SmartGlassesManager.getInstanceOrNull() }
    val connectionState by glassesManager?.connectionState?.collectAsState() ?: remember { mutableStateOf(null) }

    // Show GlassesSelectionScreen if requested
    if (showGlassesSelection && glassesManager != null) {
        GlassesSelectionScreen(
            glassesManager = glassesManager,
            onNavigateBack = { showGlassesSelection = false }
        )
    } else {
        MainScreenContent(
            settingsRepository = settingsRepository,
            chatViewModel = chatViewModel,
            navItems = navItems,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { selectedIndex = it },
            glassesManager = glassesManager,
            onNavigateToGlasses = { showGlassesSelection = true },
            onStartChat = { selectedIndex = 1 }
        )
    }
}

@Composable
private fun MainScreenContent(
    settingsRepository: SettingsRepository,
    chatViewModel: ChatViewMode?,
    navItems: List<BottomNavItem>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    glassesManager: SmartGlassesManager?,
    onNavigateToGlasses: () -> Unit,
    onStartChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by glassesManager?.connectionState?.collectAsState() ?: remember { mutableStateOf(null) }
    // Check if glasses has been selected (not just connected)
    val hasGlassesSelected = settingsRepository.glassesName != null
    val isGlassesConnected = connectionState?.name == "CONNECTED"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        BgDark,
                        Color(0xFF0a0e1a),
                        BgDark
                    )
                )
            )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            bottomBar = {
                NeonNavigationBar(
                    items = navItems,
                    selectedIndex = selectedIndex,
                    onItemSelected = onSelectedIndexChange
                )
            }
        ) { innerPadding ->
            when (selectedIndex) {
                0 -> HomeContent(
                    modifier = Modifier.padding(innerPadding),
                    onStartChat = onStartChat
                )
                1 -> ChatScreen(
                    settingsRepository = settingsRepository,
                    modifier = Modifier.padding(innerPadding)
                )
                2 -> MediaContent(modifier = Modifier.padding(innerPadding), settingsRepository = settingsRepository)
                3 -> SettingsContent(
                    modifier = Modifier.padding(innerPadding),
                    settingsRepository = settingsRepository,
                    chatViewModel = chatViewModel,
                    isGlassesConnected = isGlassesConnected || hasGlassesSelected,
                    glassesDeviceName = settingsRepository.glassesName,
                    onNavigateToGlasses = onNavigateToGlasses,
                    glassesManager = glassesManager
                )
            }
        }
    }
}

@Composable
fun NeonNavigationBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = BgCard,
        contentColor = AccentNeon
    ) {
        items.forEachIndexed { index, item ->
            val selected = selectedIndex == index
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.title,
                        tint = if (selected) AccentNeon else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = item.titleVi,
                        color = if (selected) AccentNeon else TextMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 10.sp
                    )
                },
                selected = selected,
                onClick = { onItemSelected(index) },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentNeon,
                    selectedTextColor = AccentNeon,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = AccentNeon.copy(alpha = 0.15f)
                )
            )
        }
    }
}

@Composable
fun HomeContent(
    modifier: Modifier = Modifier,
    onStartChat: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Neon Glow App Icon/Logo
        Box(
            modifier = Modifier
                .size(80.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = AccentNeon.copy(alpha = 0.3f),
                    spotColor = AccentNeon.copy(alpha = 0.5f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                border = BorderStroke(1.dp, AccentNeon.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🤖",
                        style = MaterialTheme.typography.displayMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // App Name with Neon Glow
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = AccentNeon,
            modifier = Modifier
                .shadow(
                    elevation = 8.dp,
                    ambientColor = AccentNeon.copy(alpha = 0.4f),
                    spotColor = AccentNeon.copy(alpha = 0.4f)
                )
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.app_description),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Feature cards with neon styling
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeatureCard(
                modifier = Modifier.weight(1f),
                emoji = "🎤",
                title = stringResource(R.string.feature_voice),
                titleVi = "Ghi âm"
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                emoji = "💬",
                title = stringResource(R.string.feature_chat),
                titleVi = "Chat"
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeatureCard(
                modifier = Modifier.weight(1f),
                emoji = "🎵",
                title = stringResource(R.string.feature_tts),
                titleVi = "TTS"
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                emoji = "🌐",
                title = stringResource(R.string.feature_multi),
                titleVi = "Đa ngôn ngữ"
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Neon Start Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = AccentNeon.copy(alpha = 0.4f),
                    spotColor = AccentNeon.copy(alpha = 0.5f)
                )
        ) {
            Card(
                onClick = onStartChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = AccentNeon
                ),
                border = BorderStroke(1.dp, AccentNeon.copy(alpha = 0.8f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    AccentNeon,
                                    AccentNeon.copy(alpha = 0.8f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.start_chat),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = BgDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun FeatureCard(
    modifier: Modifier = Modifier,
    emoji: String,
    title: String,
    titleVi: String
) {
    Card(
        modifier = modifier.height(70.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderNeon.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = titleVi,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
fun MediaContent(
    modifier: Modifier = Modifier,
    settingsRepository: SettingsRepository? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = settingsRepository ?: remember { vn.vietbot.client.data.SettingsRepositoryImpl(context) }
    var history by remember { mutableStateOf<List<MediaHistoryItem>>(emptyList()) }

    // Refresh history when screen is shown (simple approach: load on first compose)
    LaunchedEffect(Unit) {
        history = repo.mediaHistory
    }

    if (history.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "🎵", style = MaterialTheme.typography.displayLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Chưa có bài hát nào được phát.\nHãy thử: \"Phát bài Shape of You trên YouTube\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = "🎵 Lịch sử phát",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = AccentNeon,
                modifier = Modifier.shadow(
                    elevation = 6.dp,
                    ambientColor = AccentNeon.copy(alpha = 0.3f),
                    spotColor = AccentNeon.copy(alpha = 0.4f)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            history.forEachIndexed { index, item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            // Replay: open YouTube with this song
                            try {
                                val encodedQuery = android.net.Uri.encode(item.songName)
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)

                                // Update timestamp on replay
                                val updated = history.toMutableList()
                                updated[index] = item.copy(timestamp = System.currentTimeMillis())
                                repo.mediaHistory = updated
                                history = updated
                            } catch (e: Exception) {
                                Log.e("MediaContent", "Failed to replay: ${e.message}")
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = BorderStroke(1.dp, BorderNeon.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "▶",
                            style = MaterialTheme.typography.titleLarge,
                            color = AccentNeon
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.songName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                maxLines = 2
                            )
                            Text(
                                text = formatAge(System.currentTimeMillis() - item.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatAge(deltaMillis: Long): String = TimeUtils.formatAge(deltaMillis)

@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    settingsRepository: SettingsRepository,
    chatViewModel: ChatViewMode? = null,
    isGlassesConnected: Boolean = false,
    glassesDeviceName: String? = null,
    onNavigateToGlasses: () -> Unit = {},
    glassesManager: SmartGlassesManager? = null
) {
    val fontFamilies = listOf("System", "Sans-serif", "Monospace", "Serif")
    val fontSizes = listOf(12, 14, 16, 18, 20, 24)
    val presetColors = listOf(
        "#000000" to "Đen",
        "#333333" to "Xám đậm",
        "#666666" to "Xám",
        "#2196F3" to "Xanh dương",
        "#4CAF50" to "Xanh lá",
        "#F44336" to "Đỏ",
        "#FF9800" to "Cam",
        "#9C27B0" to "Tím"
    )
    val presetBubbleColors = listOf(
        "#BBDEFB" to "Xanh nhạt",
        "#FFFFFF" to "Trắng",
        "#E0E0E0" to "Xám nhạt",
        "#FFCC80" to "Cam nhạt",
        "#A5D6A7" to "Xanh lá nhạt",
        "#CE93D8" to "Tím nhạt",
        "#EF9A9A" to "Đỏ nhạt",
        "#80DEEA" to "Cyan nhạt"
    )

    var selectedFontFamily by remember { mutableStateOf(settingsRepository.fontFamily) }
    var selectedFontSize by remember { mutableIntStateOf(settingsRepository.fontSize) }
    var selectedTextColor by remember { mutableStateOf(settingsRepository.textColorHex) }
    var selectedBubbleColor by remember { mutableStateOf(settingsRepository.bubbleColorHex) }
    var cameraSource by remember { mutableStateOf(settingsRepository.cameraSource) }
    var appLanguage by remember { mutableStateOf(settingsRepository.appLanguage) }
    var micSource by remember { mutableStateOf(settingsRepository.micSource) }
    var speakerOutput by remember { mutableStateOf(settingsRepository.speakerOutput) }
    var useOfflineTts by remember { mutableStateOf(settingsRepository.useOfflineTts) }

    // Helper to apply audio device change — must rebuild OpusStreamPlayer (captures
    // AudioDeviceInfo in init {} and cannot be re-routed after creation).
    val applyAudioDeviceChange: () -> Unit = {
        Log.i("SettingsContent", "🎛️ applyAudioDeviceChange → calling chatViewModel.restartAudioForDeviceChange()")
        chatViewModel?.restartAudioForDeviceChange()
            ?: Log.w("SettingsContent", "⚠️ chatViewModel is NULL — cannot restart audio")
    }
    // Use parameter isGlassesConnected instead of local state
    var glassesBatteryLevel by remember { mutableIntStateOf(settingsRepository.glassesBatteryLevel) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = AccentNeon,
            modifier = Modifier.shadow(
                elevation = 6.dp,
                ambientColor = AccentNeon.copy(alpha = 0.3f),
                spotColor = AccentNeon.copy(alpha = 0.4f)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Language Section
        NeonSettingsSection(title = stringResource(R.string.app_language)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CameraSourceChip(
                    label = "🇻🇳 Tiếng Việt",
                    emoji = "",
                    isSelected = appLanguage == vn.vietbot.client.util.LocaleHelper.LANG_VIETNAMESE,
                    onClick = {
                        appLanguage = vn.vietbot.client.util.LocaleHelper.LANG_VIETNAMESE
                        settingsRepository.appLanguage = vn.vietbot.client.util.LocaleHelper.LANG_VIETNAMESE
                        vn.vietbot.client.util.LocaleHelper.setLanguage(context, vn.vietbot.client.util.LocaleHelper.LANG_VIETNAMESE)
                        (context as? android.app.Activity)?.recreate()
                    },
                    modifier = Modifier.weight(1f)
                )
                CameraSourceChip(
                    label = "🇬🇧 English",
                    emoji = "",
                    isSelected = appLanguage == vn.vietbot.client.util.LocaleHelper.LANG_ENGLISH,
                    onClick = {
                        appLanguage = vn.vietbot.client.util.LocaleHelper.LANG_ENGLISH
                        settingsRepository.appLanguage = vn.vietbot.client.util.LocaleHelper.LANG_ENGLISH
                        vn.vietbot.client.util.LocaleHelper.setLanguage(context, vn.vietbot.client.util.LocaleHelper.LANG_ENGLISH)
                        (context as? android.app.Activity)?.recreate()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Camera Source Section - Dropdown like mic/speaker
        NeonSettingsSection(title = stringResource(R.string.camera_source)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val availableCameraSources = remember(isGlassesConnected) {
                val list = mutableListOf(CameraSource.PHONE)
                if (isGlassesConnected) list.add(CameraSource.GLASSES)
                list
            }
            val effectiveCameraSource = if (availableCameraSources.contains(cameraSource)) cameraSource else availableCameraSources.firstOrNull() ?: CameraSource.PHONE

            // Pre-resolve labels
            val labelPhone = stringResource(R.string.camera_source_phone)
            val labelGlasses = stringResource(R.string.camera_source_glasses)

            DeviceDropdownSection(
                options = availableCameraSources,
                selectedValue = effectiveCameraSource,
                onValueChange = { newSource ->
                    if (availableCameraSources.contains(newSource)) {
                        cameraSource = newSource
                        settingsRepository.cameraSource = newSource
                    }
                },
                labelProvider = { source ->
                    when (source) {
                        CameraSource.PHONE -> labelPhone
                        CameraSource.GLASSES -> labelGlasses
                    }
                },
                isEnabledProvider = { source ->
                    source != CameraSource.GLASSES || isGlassesConnected
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Translation TTS Mode Section
        NeonSettingsSection(title = stringResource(R.string.translation_tts_mode)) {
            val options = listOf(true, false) // true = offline TTS, false = server audio
            val labelOffline = stringResource(R.string.offline_tts)
            val labelServer = stringResource(R.string.server_audio)
            val descOffline = stringResource(R.string.offline_tts_desc)
            val descServer = stringResource(R.string.server_audio_desc)

            DeviceDropdownSection(
                options = options,
                selectedValue = useOfflineTts,
                onValueChange = { newValue ->
                    useOfflineTts = newValue
                    settingsRepository.useOfflineTts = newValue
                },
                labelProvider = { value ->
                    if (value) labelOffline else labelServer
                },
                descriptionProvider = { value ->
                    if (value) descOffline else descServer
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Speaker Output Section
        NeonSettingsSection(title = stringResource(R.string.speaker_output)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val availableSpeakers = AudioDeviceSelector.getAvailableSpeakerOutputs(context, isGlassesConnected)
            val effectiveSpeaker = if (availableSpeakers.contains(speakerOutput)) speakerOutput else (availableSpeakers.firstOrNull() ?: SpeakerOutput.BUILTIN_SPEAKER)

            val labelBuiltInSpeaker = stringResource(R.string.speaker_builtin)
            val labelEarpiece = stringResource(R.string.speaker_earpiece)

            DeviceDropdownSection(
                options = availableSpeakers,
                selectedValue = effectiveSpeaker,
                onValueChange = { newOutput ->
                    Log.i("SettingsContent", "🔀 Speaker dropdown onValueChange: $speakerOutput → $newOutput")
                    if (availableSpeakers.contains(newOutput)) {
                        speakerOutput = newOutput
                        settingsRepository.speakerOutput = newOutput
                        applyAudioDeviceChange()
                    }
                },
                labelProvider = { output ->
                    when (output) {
                        SpeakerOutput.BUILTIN_SPEAKER -> labelBuiltInSpeaker
                        SpeakerOutput.EARPIECE -> labelEarpiece
                    }
                },
                isEnabledProvider = { _ -> true }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Mic Source Section
        NeonSettingsSection(title = stringResource(R.string.mic_source)) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val availableMicSources = AudioDeviceSelector.getAvailableMicSources(context, isGlassesConnected)
            // Ensure current selection is in available list; if not, force a valid one
            val effectiveMicSource = if (availableMicSources.contains(micSource)) micSource else (availableMicSources.firstOrNull() ?: MicSource.BUILTIN)

            // Pre-resolve labels
            val labelBuiltin = stringResource(R.string.mic_builtin)
            val labelBtSco = stringResource(R.string.mic_bluetooth_sco)

            DeviceDropdownSection(
                options = availableMicSources,
                selectedValue = effectiveMicSource,
                onValueChange = { newSource ->
                    Log.i("SettingsContent", "🎤 Mic dropdown onValueChange: $micSource → $newSource")
                    if (availableMicSources.contains(newSource)) {
                        micSource = newSource
                        settingsRepository.micSource = newSource
                        applyAudioDeviceChange()
                    }
                },
                labelProvider = { source ->
                    when (source) {
                        MicSource.BUILTIN -> labelBuiltin
                        MicSource.BLUETOOTH_SCO -> labelBtSco
                    }
                },
                isEnabledProvider = { _ ->
                    true  // Both BUILTIN and BLUETOOTH_SCO always available
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Speaker Output Section
        // Font Family Section
        NeonSettingsSection(title = stringResource(R.string.font_family)) {
            fontFamilies.forEach { font ->
                FontOptionItem(
                    fontName = font,
                    isSelected = selectedFontFamily == font,
                    onClick = {
                        selectedFontFamily = font
                        settingsRepository.fontFamily = font
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Font Size Section
        NeonSettingsSection(title = stringResource(R.string.font_size)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                fontSizes.forEach { size ->
                    FontSizeChip(
                        size = size,
                        isSelected = selectedFontSize == size,
                        onClick = {
                            selectedFontSize = size
                            settingsRepository.fontSize = size
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Text Color Section
        NeonSettingsSection(title = stringResource(R.string.text_color)) {
            ColorPicker(
                presetColors = presetColors,
                selectedColor = selectedTextColor,
                onColorSelected = {
                    selectedTextColor = it
                    settingsRepository.textColorHex = it
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bubble Background Color Section
        NeonSettingsSection(title = stringResource(R.string.bubble_color)) {
            ColorPicker(
                presetColors = presetBubbleColors,
                selectedColor = selectedBubbleColor,
                onColorSelected = {
                    selectedBubbleColor = it
                    settingsRepository.bubbleColorHex = it
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // About Section
        NeonSettingsSection(title = "About") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // App info row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🤖",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "VietBot",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = AccentNeon
                            )
                            Text(
                                text = "AI Voice Assistant",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                    Text(
                        text = "v1.1",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentNeon,
                        modifier = Modifier
                            .background(
                                color = AccentNeon.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(BorderNeon.copy(alpha = 0.15f))
                )

                // Description
                Text(
                    text = "VietBot là giải pháp AI Voice Assistant kết hợp sự linh hoạt của Xiaozhi với hệ sinh thái Google AI thông qua Gemini Live API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // New features
                Text(
                    text = "v1.1 - Tích hợp kính HeyCyan Model Mxxx",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = AccentNeon.copy(alpha = 0.8f)
                )

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(BorderNeon.copy(alpha = 0.15f))
                )

                // Author info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Tác giả",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "Thế Anh",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Liên hệ",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "phanmemkhoinghiep@gmail.com",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentNeon
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "phanmemkhoinghiep/vietbot_client",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentNeon
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(BorderNeon.copy(alpha = 0.15f))
                )

                // About Vietbot section (unified content from landing_page)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🤖🌟 " + stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = AccentNeon
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_version),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = stringResource(R.string.about_author),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = stringResource(R.string.about_phone),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = stringResource(R.string.about_website),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentNeon
                    )
                    Text(
                        text = stringResource(R.string.about_email),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_tech_stack_title),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentNeon
                    )
                    Text(
                        text = stringResource(R.string.about_tech_stack),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.about_ai_engine_title),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentNeon
                    )
                    Text(
                        text = stringResource(R.string.about_ai_engine),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.about_clients_title),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentNeon
                    )
                    Text(
                        text = stringResource(R.string.about_clients),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💕 " + stringResource(R.string.about_vision_title),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentPink
                    )
                    Text(
                        text = stringResource(R.string.about_vision),
                        style = MaterialTheme.typography.labelSmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_footer),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun NeonSettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderNeon.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = AccentNeon
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun FontOptionItem(
    fontName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) AccentNeon.copy(alpha = 0.15f) else BgCard,
        label = "containerColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) AccentNeon else BorderNeon.copy(alpha = 0.15f),
        label = "borderColor"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(0.5.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fontName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = getFontFamily(fontName),
                color = if (isSelected) AccentNeon else TextSecondary
            )
            if (isSelected) {
                Text(
                    text = "✓",
                    color = AccentNeon,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun FontSizeChip(
    size: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentNeon else BgCard
        ),
        border = BorderStroke(0.5.dp, if (isSelected) AccentNeon else BorderNeon.copy(alpha = 0.15f))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) BgDark else TextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun ColorPicker(
    presetColors: List<Pair<String, String>>,
    selectedColor: String,
    onColorSelected: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presetColors.take(4).forEach { (hex, name) ->
                ColorCircle(
                    hexColor = hex,
                    colorName = name,
                    isSelected = selectedColor == hex,
                    onClick = { onColorSelected(hex) }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presetColors.drop(4).forEach { (hex, name) ->
                ColorCircle(
                    hexColor = hex,
                    colorName = name,
                    isSelected = selectedColor == hex,
                    onClick = { onColorSelected(hex) }
                )
            }
        }
    }
}

@Composable
fun ColorCircle(
    hexColor: String,
    colorName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isSelected) 30.dp else 28.dp)
                    .shadow(
                        elevation = if (isSelected) 6.dp else 0.dp,
                        shape = RoundedCornerShape(50),
                        ambientColor = AccentNeon.copy(alpha = if (isSelected) 0.4f else 0f),
                        spotColor = AccentNeon.copy(alpha = if (isSelected) 0.4f else 0f)
                    )
                    .background(
                        color = Color(android.graphics.Color.parseColor(hexColor)),
                        shape = RoundedCornerShape(50)
                    )
                    .border(
                        width = 1.dp,
                        color = BorderNeon.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(50)
                    )
                    .then(
                        if (isSelected) Modifier.background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    AccentNeon.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(50)
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Text(
                        text = "✓",
                        color = if (isLightColor(hexColor)) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = colorName,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1
        )
    }
}

fun getFontFamily(fontName: String): FontFamily {
    return when (fontName) {
        "System" -> FontFamily.Default
        "Sans-serif" -> FontFamily.SansSerif
        "Monospace" -> FontFamily.Monospace
        "Serif" -> FontFamily.Serif
        else -> FontFamily.Default
    }
}

fun isLightColor(hexColor: String): Boolean {
    val color = android.graphics.Color.parseColor(hexColor)
    val darkness = 1 - (0.299 * android.graphics.Color.red(color) +
            0.587 * android.graphics.Color.green(color) +
            0.114 * android.graphics.Color.blue(color)) / 255
    return darkness < 0.5
}

@Composable
fun CameraSourceChip(
    label: String,
    emoji: String,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> AccentNeon.copy(alpha = 0.15f)
            !isEnabled -> BgCard.copy(alpha = 0.5f)
            else -> BgCard
        },
        label = "cameraChipColor"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> AccentNeon
            !isEnabled -> BorderNeon.copy(alpha = 0.1f)
            else -> BorderNeon.copy(alpha = 0.2f)
        },
        label = "cameraChipBorder"
    )

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(0.5.dp, borderColor),
        enabled = isEnabled
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isSelected -> AccentNeon
                    !isEnabled -> TextMuted.copy(alpha = 0.5f)
                    else -> TextMuted
                },
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/**
 * Generic DropdownMenu section for device selection (mic/speaker).
 * Follows the NeonSettingsSection visual style.
 */
@Composable
fun <T> DeviceDropdownSection(
    options: List<T>,
    selectedValue: T,
    onValueChange: (T) -> Unit,
    labelProvider: (T) -> String,
    descriptionProvider: (T) -> String = { "" },
    isEnabledProvider: (T) -> Boolean = { true }
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = labelProvider(selectedValue)

    Box(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = { expanded = true })  // ✅ Click to open dropdown
    ) {
        Text(
            text = selectedLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = AccentNeon,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp)
                .background(BgCard)
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = AccentNeon,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(220.dp)
        ) {
            options.forEach { option ->
                val enabled = isEnabledProvider(option)
                DropdownMenuItem(
                    text = {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                        ) {
                            Text(
                                text = labelProvider(option),
                                color = if (enabled) TextSecondary else TextMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val desc = descriptionProvider(option)
                            if (desc.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = desc,
                                    color = TextMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    },
                    onClick = {
                        if (enabled) {
                            onValueChange(option)
                            expanded = false
                        }
                    },
                    enabled = enabled
                )
                if (option != options.last()) HorizontalDivider()
            }
        }
    }
}

private fun saveToGallery(context: android.content.Context, imageData: ByteArray, prefix: String): Boolean {
    return try {
        val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_DCIM + "/VietBot")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        if (uri == null) {
            android.util.Log.e("MainScreen", "saveToGallery: contentResolver.insert returned null")
            return false
        }
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(imageData)
            stream.flush()
        }
        // Clear IS_PENDING so the file becomes visible
        contentValues.clear()
        contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, contentValues, null, null)
        android.util.Log.i("MainScreen", "saveToGallery: saved $filename (${imageData.size} bytes) to $uri")
        true
    } catch (e: Exception) {
        android.util.Log.e("MainScreen", "Failed to save photo to gallery", e)
        false
    }
}

private suspend fun capturePhotoWithCameraMcpTools(context: android.content.Context): String =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        try {
            val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            // Find back camera
            var cameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id
                    break
                }
            }
            if (cameraId == null) cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                cont.resume("Không tìm thấy camera")
                return@suspendCancellableCoroutine
            }

            val imageReader = android.media.ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2)
            var capturedImage: ByteArray? = null
            val handlerThread = android.os.HandlerThread("TestCamera").also { it.start() }
            val bgHandler = android.os.Handler(handlerThread.looper)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    capturedImage = ByteArray(buffer.remaining())
                    buffer.get(capturedImage!!)
                    image.close()
                }
            }, bgHandler)

            cameraManager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    try {
                        val captureBuilder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                        }
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: android.hardware.camera2.CameraCaptureSession,
                                                request: android.hardware.camera2.CaptureRequest,
                                                result: android.hardware.camera2.TotalCaptureResult
                                            ) {
                                                capturedImage?.let {
                                                    val saved = saveToGallery(context, it, "Phone_Test")
                                                    if (saved) {
                                                        cont.resume("Đã lưu ảnh từ điện thoại vào DCIM/VietBot (${it.size} bytes)")
                                                    } else {
                                                        cont.resume("Chụp thành công nhưng không lưu được vào bộ sưu tập")
                                                    }
                                                } ?: run {
                                                    cont.resume("Camera không trả về ảnh")
                                                }
                                                camera.close()
                                                imageReader.close()
                                                handlerThread.quitSafely()
                                            }

                                            override fun onCaptureFailed(
                                                session: android.hardware.camera2.CameraCaptureSession,
                                                request: android.hardware.camera2.CaptureRequest,
                                                failure: android.hardware.camera2.CaptureFailure
                                            ) {
                                                cont.resume("Chụp ảnh thất bại")
                                                camera.close()
                                                imageReader.close()
                                                handlerThread.quitSafely()
                                            }
                                        }, bgHandler)
                                    } catch (e: Exception) {
                                        cont.resume("Lỗi chụp: ${e.message}")
                                        camera.close()
                                        imageReader.close()
                                        handlerThread.quitSafely()
                                    }
                                }

                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                    cont.resume("Lỗi cấu hình camera")
                                    camera.close()
                                    imageReader.close()
                                    handlerThread.quitSafely()
                                }
                            },
                            bgHandler
                        )
                    } catch (e: Exception) {
                        cont.resume("Lỗi mở camera: ${e.message}")
                        camera.close()
                        imageReader.close()
                        handlerThread.quitSafely()
                    }
                }

                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    camera.close()
                    imageReader.close()
                    handlerThread.quitSafely()
                }

                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    if (!cont.isCompleted) {
                        cont.resume("Lỗi camera: $error")
                    }
                    camera.close()
                    imageReader.close()
                    handlerThread.quitSafely()
                }
            }, bgHandler)

            cont.invokeOnCancellation {
                imageReader.close()
                handlerThread.quitSafely()
            }
        } catch (e: Exception) {
            cont.resume("Lỗi: ${e.message}")
        }
    }