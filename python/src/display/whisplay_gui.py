"""
Whisplay GUI Adapter - Renders GUI elements on Whisplay HAT display
1.69-inch LCD, 240×280 resolution, SPI interface
RGB LED control for status indication
"""
import logging
import asyncio
from typing import Optional, Dict, Any
from enum import Enum

logger = logging.getLogger(__name__)


class DeviceState(Enum):
    """Device states"""
    IDLE = "idle"
    CONNECTING = "connecting"
    LISTENING = "listening"
    SPEAKING = "speaking"
    THINKING = "thinking"
    ERROR = "error"


class DisplayColor(Enum):
    """Display color schemes for different states"""
    IDLE = (0, 255, 0)        # Green
    CONNECTING = (255, 165, 0) # Orange
    LISTENING = (0, 0, 255)    # Blue
    SPEAKING = (128, 0, 128)   # Purple
    THINKING = (255, 255, 0)   # Yellow
    ERROR = (255, 0, 0)        # Red


class WhisplayGUI:
    """
    GUI Adapter for Whisplay HAT (240×280 LCD + RGB LED)
    Renders GUI elements on SPI LCD display

    Hardware specs:
    - Display: 1.69-inch LCD, 240×280 resolution, SPI interface
    - LED: RGB LED for status indication
    """

    # Display specifications
    DISPLAY_WIDTH = 240
    DISPLAY_HEIGHT = 280

    def __init__(self, display_manager=None, led_controller=None):
        """
        Initialize GUI adapter

        Args:
            display_manager: WhisplayDisplayManager instance (whisplay_display)
            led_controller: WhisplayRGBLED instance (whisplay_rgb_led)
        """
        self._display = display_manager
        self._led = led_controller
        self._current_state = DeviceState.IDLE
        self._current_text = ""
        self._current_emoji = ""
        self._volume = 100
        self._battery = 100

        # Try to import whisplay libraries
        # Display must be initialized first (LED depends on it)
        if self._display is None:
            try:
                from whisplay_display import WhisplayDisplay
                # Load config for WhisplayDisplay
                import json
                config_path = "config.whisplay.json"
                config = {}
                try:
                    with open(config_path, 'r') as f:
                        config = json.load(f)
                except FileNotFoundError:
                    logger.warning(f"Config file not found: {config_path}, using defaults")
                    config = {
                        "screen": {
                            "type": "whisplay169",
                            "font_path": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                            "brightness": 100
                        }
                    }
                self._display = WhisplayDisplay(config)
                logger.info("✅ WhisplayGUI: Display initialized (240×280)")
            except ImportError as e:
                logger.warning(f"⚠️ whisplay_display not available: {e}")
                self._display = None

        # LED requires display instance
        if self._led is None and self._display is not None:
            try:
                from whisplay_rgb_led import WhisplayRGBLED
                self._led = WhisplayRGBLED(self._display)
                logger.info("✅ WhisplayGUI: RGB LED initialized")
            except ImportError as e:
                logger.warning(f"⚠️ whisplay_rgb_led not available: {e}")
                self._led = None
        elif self._led is None and self._display is None:
            logger.warning("⚠️ Cannot initialize RGB LED: Display not available")

        logger.info(f"🖥️ WhisplayGUI initialized ({self.DISPLAY_WIDTH}×{self.DISPLAY_HEIGHT} LCD)")

    def show_state(self, state: DeviceState, text: str = "", emoji: str = ""):
        """
        Show device state on display with appropriate LED color

        Args:
            state: Device state (IDLE, LISTENING, SPEAKING, etc.)
            text: Text to display (max ~20 chars for 240×280)
            emoji: Emoji icon to show
        """
        self._current_state = state
        self._current_text = text
        self._current_emoji = emoji

        # Get status info for this state
        status_map = {
            DeviceState.IDLE: ("Sẵn sàng", "✅", DisplayColor.IDLE),
            DeviceState.CONNECTING: ("Đang kết nối", "🔌", DisplayColor.CONNECTING),
            DeviceState.LISTENING: ("Đang nghe", "👂", DisplayColor.LISTENING),
            DeviceState.SPEAKING: ("Đang nói", "🔊", DisplayColor.SPEAKING),
            DeviceState.THINKING: ("Đang suy nghĩ", "🤔", DisplayColor.THINKING),
            DeviceState.ERROR: ("Lỗi", "❌", DisplayColor.ERROR),
        }

        status_text, status_emoji, color = status_map.get(state, ("", "", DisplayColor.IDLE))

        # Use provided emoji/text if available
        display_emoji = emoji or status_emoji
        display_text = text or status_text

        # Truncate text for 240×280 display (approx 15-20 chars max)
        if len(display_text) > 30:
            display_text = display_text[:27] + "..."

        # Update display
        self._update_display(status_text, display_emoji, display_text)

        # Update LED color
        if color:
            r, g, b = color.value
            self.set_led_color(r, g, b)

    def _update_display(self, status: str, emoji: str, text: str):
        """
        Update 240×280 LCD display with centered content

        Display layout (240×280) - CENTERED:
        ┌────────────────────────────┐
        │                            │
        │     [Emoji] Status        │  <- Top: Centered
        │                            │
        │      Text content          │  <- Main: Centered
        │      (word wrap)           │
        │                            │
        └────────────────────────────┘

        Center alignment:
        - Emoji + Status: Horizontally centered
        - Text: Horizontally centered with word wrap
        """
        if self._display:
            try:
                # Build centered display text for 240×280
                display_lines = []
                max_line_width = 22  # ~22 chars per line for nice centering on 240px

                # Top section: centered emoji + status
                if emoji and status:
                    top_line = f"{emoji} {status}"
                    # Center the top line
                    display_lines.append(self._center_text(top_line, max_line_width))
                elif emoji:
                    display_lines.append(self._center_text(emoji, max_line_width))
                elif status:
                    display_lines.append(self._center_text(status, max_line_width))

                # Add empty line separator
                if display_lines and text:
                    display_lines.append("")

                # Main section: centered text content with word wrap
                if text:
                    # Split text into words and wrap
                    words = text.split()
                    current_line = ""
                    for word in words:
                        test_line = current_line + " " + word if current_line else word
                        if len(test_line) <= max_line_width:
                            current_line = test_line
                        else:
                            # Center and add current line
                            if current_line:
                                display_lines.append(self._center_text(current_line, max_line_width))
                            current_line = word
                    # Add last line
                    if current_line:
                        display_lines.append(self._center_text(current_line, max_line_width))

                display_text = "\n".join(display_lines)

                # Call whisplay display update with centered content
                self._display.update(
                    status=status,
                    emoji=emoji,
                    text=display_text
                )
            except Exception as e:
                logger.error(f"Display update error: {e}")

    def _center_text(self, text: str, max_width: int) -> str:
        """
        Center text within max_width

        Args:
            text: Text to center
            max_width: Maximum line width

        Returns:
            Centered text with padding
        """
        # Calculate padding needed
        padding = (max_width - len(text)) // 2
        if padding > 0:
            # Add padding spaces on both sides
            return " " * padding + text + " " * padding
        return text

    # ==================== GUI Methods (compatible with PyQt5 GUI) ====================

    def show_idle(self):
        """Show idle state with green LED"""
        self.show_state(DeviceState.IDLE)

    def show_listening(self):
        """Show listening state with blue LED"""
        self.show_state(DeviceState.LISTENING)

    def show_speaking(self, text: str = ""):
        """Show speaking state with purple LED"""
        # Truncate text for 240×280 display
        display_text = text[:30] if text else ""
        self.show_state(DeviceState.SPEAKING, display_text)

    def show_thinking(self):
        """Show thinking state with yellow LED"""
        self.show_state(DeviceState.THINKING)

    def show_error(self, message: str):
        """Show error message with red LED"""
        self.show_state(DeviceState.ERROR, message[:30], "❌")

    def show_connecting(self):
        """Show connecting state with orange LED"""
        self.show_state(DeviceState.CONNECTING)

    def show_activation(self, code: str, url: str = "xiaozhi.vietdev.vn"):
        """
        Show activation code on 240×280 display (CENTERED)

        Display layout:
        ┌────────────────────────────┐
        │                            │
        │       🔑 Kích hoạt          │  <- Centered title
        │                            │
        │       Mã: 6 6 3 8 7 0       │  <- Centered code
        │                            │
        │    xiaozhi.vietdev.vn       │  <- Centered URL
        │                            │
        └────────────────────────────┘
        """
        code_display = ' '.join(code) if isinstance(code, list) else code
        # Truncate URL if too long
        url_short = url[:22] if len(url) > 22 else url

        # Build centered activation display
        max_width = 22  # For nice centering on 240px
        lines = [
            self._center_text("🔑 Kích hoạt", max_width),
            "",
            self._center_text(f"Mã: {code_display}", max_width),
            "",
            self._center_text(url_short, max_width)
        ]

        display_text = "\n".join(lines)

        # Blink orange LED during activation
        self._blink_led(DisplayColor.CONNECTING.value)

        # Update display with pre-formatted centered text
        if self._display:
            try:
                self._display.update(
                    status="Kích hoạt",
                    emoji="🔑",
                    text=display_text
                )
            except Exception as e:
                logger.error(f"Display update error: {e}")

    def show_ready(self):
        """Show ready state"""
        self.show_state(DeviceState.IDLE, "Đã kích hoạt")

    # ==================== Volume & Battery ====================

    def set_volume(self, volume: int):
        """Set volume (0-100) and show briefly on display"""
        self._volume = volume
        # Optionally show volume indicator briefly
        # self._update_display("Âm lượng", "🔊", f"{volume}%")

    def set_battery(self, battery: int):
        """Set battery level (0-100)"""
        self._battery = battery
        # Could show battery indicator if needed

    # ==================== LED Control ====================

    def set_led_color(self, r: int, g: int, b: int):
        """
        Set RGB LED color for Whisplay HAT

        Args:
            r: Red component (0-255)
            g: Green component (0-255)
            b: Blue component (0-255)
        """
        if self._led:
            try:
                self._led.set_color(r, g, b)
            except Exception as e:
                logger.error(f"LED error: {e}")

    def led_off(self):
        """Turn off RGB LED"""
        if self._led:
            try:
                self._led.off()
            except Exception as e:
                logger.error(f"LED off error: {e}")

    def _blink_led(self, color: tuple, interval: float = 0.5):
        """Blink LED with specified color (async)"""
        r, g, b = color

        async def _blink():
            for _ in range(3):  # Blink 3 times
                self.set_led_color(r, g, b)
                await asyncio.sleep(interval)
                self.led_off()
                await asyncio.sleep(interval)

        # Run blink in background
        asyncio.create_task(_blink())

    # ==================== Activation UI ====================

    def show_activation_window(self, activation_code: list, message: str):
        """Show activation window (replaces PyQt5 activation window)"""
        self.show_activation(activation_code, message)

    def close_activation_window(self):
        """Close activation window"""
        self.show_ready()
        self.led_off()  # Turn off LED after activation

    # ==================== Display Control ====================

    def is_available(self) -> bool:
        """Check if display is available"""
        return self._display is not None

    def enable(self):
        """Enable display rendering"""
        if self._display and hasattr(self._display, 'enable_rendering'):
            self._display.enable_rendering()

    def disable(self):
        """Disable display rendering"""
        if self._display and hasattr(self._display, 'disable_rendering'):
            self._display.disable_rendering()

    def clear(self):
        """Clear the display"""
        if self._display:
            try:
                self._display.update(status="", emoji="", text="")
            except Exception as e:
                logger.error(f"Display clear error: {e}")


# Singleton instance
_gui_instance: Optional[WhisplayGUI] = None


def get_gui(display_manager=None, led_controller=None) -> WhisplayGUI:
    """
    Get GUI instance (singleton)

    Args:
        display_manager: Optional WhisplayDisplayManager instance
        led_controller: Optional WhisplayRGBLED instance

    Returns:
        WhisplayGUI instance
    """
    global _gui_instance
    if _gui_instance is None:
        _gui_instance = WhisplayGUI(display_manager, led_controller)
    return _gui_instance
