package vn.vietbot.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import vn.vietbot.client.R
import vn.vietbot.client.data.CameraSource
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.ui.theme.AccentNeon
import vn.vietbot.client.ui.theme.BgCard
import vn.vietbot.client.ui.theme.BgDark
import vn.vietbot.client.ui.theme.BorderNeon
import vn.vietbot.client.ui.theme.TextMuted
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
                    onItemSelected = { selectedIndex = it }
                )
            }
        ) { innerPadding ->
            when (selectedIndex) {
                0 -> HomeContent(
                    modifier = Modifier.padding(innerPadding),
                    onStartChat = { selectedIndex = 1 }
                )
                1 -> ChatScreen(
                    settingsRepository = settingsRepository,
                    modifier = Modifier.padding(innerPadding)
                )
                2 -> MediaContent(modifier = Modifier.padding(innerPadding))
                3 -> SettingsContent(
                    modifier = Modifier.padding(innerPadding),
                    settingsRepository = settingsRepository
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
                        tint = if (selected) AccentNeon else TextMuted
                    )
                },
                label = {
                    Text(
                        text = item.titleVi,
                        color = if (selected) AccentNeon else TextMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                selected = selected,
                onClick = { onItemSelected(index) },
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
fun MediaContent(modifier: Modifier = Modifier) {
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
                text = stringResource(R.string.media_developing),
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    settingsRepository: SettingsRepository
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
        "#E3F2FD" to "Xanh nhạt",
        "#FFFFFF" to "Trắng",
        "#F5F5F5" to "Xám nhạt",
        "#FFF3E0" to "Cam nhạt",
        "#E8F5E9" to "Xanh lá nhạt",
        "#F3E5F5" to "Tím nhạt",
        "#FFEBEE" to "Đỏ nhạt",
        "#E0F7FA" to "Cyan nhạt"
    )

    var selectedFontFamily by remember { mutableStateOf(settingsRepository.fontFamily) }
    var selectedFontSize by remember { mutableIntStateOf(settingsRepository.fontSize) }
    var selectedTextColor by remember { mutableStateOf(settingsRepository.textColorHex) }
    var selectedBubbleColor by remember { mutableStateOf(settingsRepository.bubbleColorHex) }
    var cameraSource by remember { mutableStateOf(settingsRepository.cameraSource) }
    var isGlassesConnected by remember { mutableStateOf(settingsRepository.isGlassesConnected) }
    var glassesBatteryLevel by remember { mutableIntStateOf(settingsRepository.glassesBatteryLevel) }

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

        // Glasses Connection Section - Neon Style
        NeonSettingsSection(title = "Nguồn Camera") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isGlassesConnected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                        contentDescription = "Glasses",
                        modifier = Modifier.size(20.dp),
                        tint = if (isGlassesConnected) AccentNeon else TextMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGlassesConnected) "Đã kết nối kính" else "Chưa kết nối kính",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isGlassesConnected) AccentNeon else TextSecondary
                        )
                        if (isGlassesConnected && glassesBatteryLevel >= 0) {
                            Text(
                                text = "Pin: $glassesBatteryLevel%",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Camera source toggle
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CameraSourceChip(
                        label = "Điện thoại",
                        emoji = "📱",
                        isSelected = cameraSource == CameraSource.PHONE,
                        onClick = {
                            cameraSource = CameraSource.PHONE
                            settingsRepository.cameraSource = CameraSource.PHONE
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CameraSourceChip(
                        label = "Kính",
                        emoji = "👓",
                        isSelected = cameraSource == CameraSource.GLASSES,
                        isEnabled = isGlassesConnected,
                        onClick = {
                            if (isGlassesConnected) {
                                cameraSource = CameraSource.GLASSES
                                settingsRepository.cameraSource = CameraSource.GLASSES
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                        text = "v1.0",
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

                // Copyright
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "© 2026 VietBot. Built with ❤️",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Sử dụng Gemini Live API và tương thích với Xiaozhi Client",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "VietBot là sản phẩm phổ cập AI cho cộng đồng, không kinh doanh, không thu phí người dùng.",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted.copy(alpha = 0.6f),
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