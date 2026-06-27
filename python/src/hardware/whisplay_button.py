"""
Button Handler for Whisplay HAT
Uses gpiozero to detect button presses and trigger actions
"""
import logging
import asyncio
from typing import Callable, Optional

logger = logging.getLogger(__name__)


class WhisplayButton:
    """
    Button handler for Whisplay HAT
    BCM GPIO 17 (button on Whisplay HAT)
    """

    def __init__(self, gpio_pin: int = 17, display_manager=None, led_controller=None):
        """
        Initialize button handler

        Args:
            gpio_pin: BCM GPIO pin number (default 17 for Whisplay HAT)
            display_manager: WhisplayDisplayManager instance
            led_controller: WhisplayRGBLED instance
        """
        self.gpio_pin = gpio_pin
        self._display = display_manager
        self._led = led_controller
        self._button = None
        self._is_running = False
        self._press_callback: Optional[Callable] = None

        # Try to import gpiozero
        try:
            from gpiozero import Button
            self.Button = Button
            logger.info("✅ WhisplayButton: gpiozero available")
        except ImportError:
            logger.warning("⚠️ gpiozero not available, button handling disabled")
            self.Button = None

    def set_press_callback(self, callback: Callable):
        """
        Set callback for button press

        Args:
            callback: Async function to call when button is pressed
        """
        self._press_callback = callback

    async def start(self):
        """Start button detection loop with non-blocking approach"""
        if not self.Button:
            logger.warning("⚠️ Button detection not available (gpiozero missing)")
            return

        self._is_running = True

        try:
            # Setup button with gpiozero
            self._button = self.Button(self.gpio_pin)

            logger.info(f"🔘 Button detection started on GPIO {self.gpio_pin}")

            # Non-blocking button detection loop
            while self._is_running:
                try:
                    # Use when_pressed event with timeout for non-blocking behavior
                    # Wait with timeout to allow checking _is_running flag
                    button_pressed = await asyncio.to_thread(
                        self._wait_for_press_with_timeout, timeout=0.5
                    )

                    if button_pressed:
                        logger.debug("🔘 Button pressed!")

                        # Visual feedback
                        if self._led:
                            try:
                                self._led.set_color(0, 255, 0)  # Green
                            except Exception as e:
                                logger.debug(f"LED error: {e}")

                        # Trigger callback
                        if self._press_callback:
                            try:
                                if asyncio.iscoroutinefunction(self._press_callback):
                                    await self._press_callback()
                                else:
                                    self._press_callback()
                            except Exception as e:
                                logger.error(f"Button callback error: {e}", exc_info=True)

                        # Debounce delay
                        await asyncio.sleep(0.5)

                        # Turn off LED
                        if self._led:
                            try:
                                self._led.off()
                            except Exception as e:
                                logger.debug(f"LED off error: {e}")

                except Exception as e:
                    if self._is_running:  # Only log if not intentionally stopped
                        logger.error(f"Button detection loop error: {e}")
                    break

        except Exception as e:
            logger.error(f"❌ Button detection error: {e}", exc_info=True)
            self._is_running = False

    def _wait_for_press_with_timeout(self, timeout: float) -> bool:
        """
        Wait for button press with timeout (runs in thread).

        Args:
            timeout: Maximum time to wait in seconds

        Returns:
            True if button was pressed, False if timeout occurred
        """
        import threading

        press_event = threading.Event()
        result = [False]  # Use list to allow modification in closure

        def on_press():
            result[0] = True
            press_event.set()

        # Register temporary callback
        if self._button:
            self._button.when_pressed = on_press

        # Wait for press or timeout
        press_event.wait(timeout=timeout)

        # Clear callback
        if self._button:
            self._button.when_pressed = None

        return result[0]

    def stop(self):
        """Stop button detection"""
        self._is_running = False
        if self._button:
            try:
                self._button.close()
            except Exception as e:
                logger.debug(f"Button close error: {e}")

    def is_available(self) -> bool:
        """Check if button is available"""
        return self.Button is not None


# Singleton instance
_button_instance: Optional[WhisplayButton] = None


def get_button(gpio_pin: int = 17, display_manager=None, led_controller=None) -> WhisplayButton:
    """
    Get button instance (singleton)

    Args:
        gpio_pin: BCM GPIO pin number
        display_manager: Optional WhisplayDisplayManager instance
        led_controller: Optional WhisplayRGBLED instance

    Returns:
        WhisplayButton instance
    """
    global _button_instance
    if _button_instance is None:
        _button_instance = WhisplayButton(gpio_pin, display_manager, led_controller)
    return _button_instance
