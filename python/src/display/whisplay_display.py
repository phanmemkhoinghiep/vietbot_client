"""
Whisplay HAT Display Adapter
No GUI dependency - uses SPI LCD display directly
"""
import logging
from typing import Optional

logger = logging.getLogger(__name__)


class WhisplayDisplay:
    """Whisplay HAT display manager (SPI LCD, no GUI)"""

    def __init__(self):
        self._display = None
        self._led = None
        self._enabled = False
        try:
            # Import whisplay library
            from whisplay_display import WhisplayDisplayManager
            from whisplay_rgb_led import WhisplayRGBLED

            # Initialize display
            self._display = WhisplayDisplayManager()
            self._led = WhisplayRGBLED()
            self._enabled = True
            logger.info("✅ Whisplay HAT display initialized")
        except ImportError as e:
            logger.warning(f"⚠️ whisplay libraries not available: {e}")
        except Exception as e:
            logger.error(f"❌ Failed to initialize display: {e}")

    def show_message(self, title: str, message: str = ""):
        """Show message on display"""
        if self._display:
            try:
                self._display.update_display(
                    status=title,
                    emoji="",
                    text=message
                )
            except Exception as e:
                logger.error(f"Display error: {e}")

    def show_activation(self, code: str, url: str = "xiaozhi.vietdev.vn"):
        """Show activation code on display"""
        code_display = ' '.join(code) if isinstance(code, list) else code
        self.show_message("Kích hoạt", f"Mã: {code_display}\n{url}")

    def show_ready(self):
        """Show ready message"""
        self.show_message("Sẵn sàng", "Đã kích hoạt")

    def show_listening(self):
        """Show listening state"""
        self.show_message("Đang nghe", "")

    def show_speaking(self, text: str = ""):
        """Show speaking state"""
        self.show_message("Đang nói", text[:50] if text else "")

    def show_error(self, message: str):
        """Show error message"""
        self.show_message("Lỗi", message)

    def set_led_color(self, r: int, g: int, b: int):
        """Set LED color"""
        if self._led:
            try:
                self._led.set_color(r, g, b)
            except Exception as e:
                logger.error(f"LED error: {e}")

    def led_off(self):
        """Turn off LED"""
        if self._led:
            try:
                self._led.off()
            except Exception as e:
                logger.error(f"LED error: {e}")

    def enable(self):
        """Enable display rendering"""
        self._enabled = True

    def disable(self):
        """Disable display rendering"""
        self._enabled = False

    def is_available(self) -> bool:
        """Check if display is available"""
        return self._display is not None


def get_display() -> Optional[WhisplayDisplay]:
    """Get display instance"""
    return WhisplayDisplay()
