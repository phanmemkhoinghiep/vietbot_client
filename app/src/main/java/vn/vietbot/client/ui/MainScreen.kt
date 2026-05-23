package vn.vietbot.client.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vietbot.client.R
import vn.vietbot.client.data.SettingsRepository

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

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                navItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selectedIndex == index) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.title
                            )
                        },
                        label = { Text(item.titleVi) },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
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

@Composable
fun HomeContent(
    modifier: Modifier = Modifier,
    onStartChat: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // App Icon/Logo
        Card(
            modifier = Modifier.size(150.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🤖",
                    style = MaterialTheme.typography.displayLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.app_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Feature cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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

        // Start button
        Card(
            onClick = onStartChat,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.start_chat),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
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
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = titleVi,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Font Family Section
        SettingsSection(title = stringResource(R.string.font_family)) {
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

        Spacer(modifier = Modifier.height(16.dp))

        // Font Size Section
        SettingsSection(title = stringResource(R.string.font_size)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

        Spacer(modifier = Modifier.height(16.dp))

        // Text Color Section
        SettingsSection(title = stringResource(R.string.text_color)) {
            ColorPicker(
                presetColors = presetColors,
                selectedColor = selectedTextColor,
                onColorSelected = {
                    selectedTextColor = it
                    settingsRepository.textColorHex = it
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bubble Background Color Section
        SettingsSection(title = stringResource(R.string.bubble_color)) {
            ColorPicker(
                presetColors = presetBubbleColors,
                selectedColor = selectedBubbleColor,
                onColorSelected = {
                    selectedBubbleColor = it
                    settingsRepository.bubbleColorHex = it
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Preview Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.preview),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(android.graphics.Color.parseColor(selectedBubbleColor))
                    )
                ) {
                    Text(
                        text = stringResource(R.string.preview_text),
                        style = TextStyle(
                            fontFamily = getFontFamily(selectedFontFamily),
                            fontSize = selectedFontSize.sp,
                            color = Color(android.graphics.Color.parseColor(selectedTextColor))
                        ),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fontName,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = getFontFamily(fontName)
            )
            if (isSelected) {
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.primary
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = size.toString(),
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isSelected) 38.dp else 36.dp)
                    .background(
                        color = Color(android.graphics.Color.parseColor(hexColor)),
                        shape = RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Text(
                        text = "✓",
                        color = if (isLightColor(hexColor)) Color.Black else Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = colorName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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