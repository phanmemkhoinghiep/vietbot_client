"""
Button Handler for Whisplay HAT
Based on working code from xiaozhi_python_client/start.py
GPIO BCM 17, gpiozero, short/long press detection
"""
import time
import logging
import asyncio
from enum import Enum
from typing import Optional, Callable

logger = logging.getLogger(__name__)


class ButtonEvent(Enum):
    """Button event types"""
    WAKEUP_SHORT = "wakeup_short"  # Short press - start/stop conversation
    WAKEUP_LONG = "wakeup_long"    # Long press - special action


class WhisplayButtonHandler:
    """
    Button handler for Whisplay HAT
    BCM GPIO 17, gpiozero, short/long press detection

    Based on working implementation from xiaozhi_python_client
    """

    # Button configuration
    BUTTON_PIN_BCM = 17
    LONG_PRESS_TIME = 2.0  # seconds

    def __init__(self, display_manager=None, led_controller=None):
        """
        Initialize button handler

        Args:
            display_manager: WhisplayDisplayManager instance (with button support)
            led_controller: WhisplayRGBLED instance
        """
        self.display_manager = display_manager
        self.led_controller = led_controller

        # Button state
        self._button_press_time = 0.0
        self._is_running = False
        self._press_lock = asyncio.Lock()  # Add lock for thread safety

        # Task reference for cleanup
        self._long_press_task: Optional[asyncio.Task] = None

        # Callbacks
        self._on_short_press: Optional[Callable] = None
        self._on_long_press: Optional[Callable] = None

        # Try to import gpiozero
        try:
            from gpiozero import Button
            self.Button = Button
            logger.info("✅ WhisplayButtonHandler: gpiozero available")
        except ImportError:
            logger.warning("⚚�️ gpiozero not available, button handling disabled")
            self.Button = None

    def set_short_press_callback(self, callback: Callable):
        """Set callback for short button press"""
        self._on_short_press = callback

    def set_long_press_callback(self, callback: Callable):
        """Set callback for long button press"""
        self._on_long_press = callback

    async def start(self):
        """Start button detection in background"""
        if not self.Button:
            logger.warning("⚠️ Button detection not available")
            return

        # Setup button with display_manager
        if (self.display_manager and
            hasattr(self.display_manager, 'on_button_press') and
            hasattr(self.display_manager, 'on_button_release')):
            # Use display_manager's button handling
            self.display_manager.on_button_press(self._on_button_pressed)
            self.display_manager.on_button_release(self._on_button_released)
            logger.info("✅ WhisplayButtonHandler started (via display_manager)")
        else:
            logger.warning("⚠️ display_manager doesn't support button handling")

    def _on_button_pressed(self):
        """Handle button press event from gpiozero"""
        self._button_press_time = time.time()
        logger.debug("🔘 Button pressed - starting long press timer")

        # Start long press detection task with reference tracking
        if self._on_long_press:
            loop = asyncio.get_event_loop()
            self._long_press_task = loop.create_task(self._check_long_press())

    def _on_button_released(self):
        """Handle button release event from gpiozero"""
        press_duration = time.time() - self._button_press_time if self._button_press_time > 0 else 0

        # Cancel long press task if still running
        if self._long_press_task and not self._long_press_task.done():
            self._long_press_task.cancel()
            logger.debug("🔘 Long press task cancelled")

        if self._button_press_time > 0 and press_duration < self.LONG_PRESS_TIME:
            logger.debug(f"🔘 Button released after {press_duration:.2f}s - short press (WAKEUP_SHORT)")
            if self._on_short_press:
                # Run callback in background
                if asyncio.iscoroutinefunction(self._on_short_press):
                    asyncio.create_task(self._on_short_press())
                else:
                    self._on_short_press()

        # Reset
        self._button_press_time = 0.0
        self._long_press_task = None

    async def _check_long_press(self):
        """Check for long press with race condition protection"""
        try:
            # Wait for long press duration
            await asyncio.sleep(self.LONG_PRESS_TIME)

            # Double-check button is still pressed and task hasn't been cancelled
            if self._button_press_time > 0 and not asyncio.current_task().cancelled():
                logger.debug(f"🔘 Button still pressed after {self.LONG_PRESS_TIME}s - long press (WAKEUP_LONG)")
                if self._on_long_press:
                    if asyncio.iscoroutinefunction(self._on_long_press):
                        await self._on_long_press()
                    else:
                        self._on_long_press()

                # Reset after long press
                self._button_press_time = 0.0
                self._long_press_task = None

        except asyncio.CancelledError:
            logger.debug("Long press detection cancelled")
            self._long_press_task = None

    def stop(self):
        """Stop button detection and cleanup tasks"""
        self._is_running = False

        # Cancel long press task if running
        if self._long_press_task and not self._long_press_task.done():
            self._long_press_task.cancel()
            self._long_press_task = None


# Singleton instance
_button_handler_instance: Optional[WhisplayButtonHandler] = None


def get_button_handler(display_manager=None, led_controller=None) -> WhisplayButtonHandler:
    """
    Get button handler instance (singleton)

    Args:
        display_manager: WhisplayDisplayManager instance
        led_controller: WhisplayRGBLED instance

    Returns:
        WhisplayButtonHandler instance
    """
    global _button_handler_instance
    if _button_handler_instance is None:
        _button_handler_instance = WhisplayButtonHandler(display_manager, led_controller)
    return _button_handler_instance
