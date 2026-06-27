#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Whisplay CLI Display - Display implementation for Whisplay HAT (240x280 LCD)
Implements the display interface expected by the Application class from reference
Displays dynamic text from reference implementation with Vietnamese translation
"""

import asyncio
import logging
import json
from typing import Optional, Callable

logger = logging.getLogger(__name__)


class WhisplayCliDisplay:
    """
    Display implementation for Whisplay HAT with CLI-style interface.
    Compatible with the Application class from reference implementation.

    Displays:
    - Static: Time, WiFi icon, Battery, "AI-BOX.VN"
    - Dynamic: Status (translated to Vietnamese), user/assistant messages, emoji
    """

    def __init__(self):
        """Initialize Whisplay CLI Display"""
        self._display = None
        self._led = None
        self._running = True
        self._current_status = ""
        self._current_text = ""
        self._current_emotion = "neutral"
        self._connected = False
        self._is_listening = False
        self._is_speaking = False

        # Callbacks (compatible with Application interface)
        self.button_press_callback: Optional[Callable] = None
        self.button_release_callback: Optional[Callable] = None
        self.mode_callback: Optional[Callable] = None
        self.auto_callback: Optional[Callable] = None
        self.abort_callback: Optional[Callable] = None
        self.send_text_callback: Optional[Callable] = None

        # Button state for long press detection
        self._button_press_time = 0.0
        self._button_press_task = None
        self._long_press_time = 2.0  # seconds

        # Load config and initialize display
        self._init_display()

        # Track user/assistant messages
        self._last_user_message = ""
        self._last_assistant_message = ""

    def _init_display(self):
        """Initialize Whisplay display and LED"""
        try:
            config_path = "config.whisplay.json"
            config = {}
            try:
                with open(config_path, 'r') as f:
                    config = json.load(f)
            except FileNotFoundError:
                config = {
                    "screen": {
                        "type": "whisplay169",
                        "font_path": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                        "brightness": 100
                    }
                }

            # Import WhisplayDisplay
            from whisplay_display import WhisplayDisplay
            self._display = WhisplayDisplay(config.get("screen", {}))
            logger.info("✅ WhisplayDisplay initialized")

            # Setup button callbacks with long press detection
            self._display.on_button_press(self._on_button_press)
            self._display.on_button_release(self._on_button_release)
            logger.info("✅ Button callbacks registered")

            # Initialize LED
            try:
                from whisplay_rgb_led import WhisplayRGBLED
                self._led = WhisplayRGBLED(self._display)
                logger.info("✅ RGB LED initialized")
            except Exception as e:
                logger.warning(f"⚠️ RGB LED init failed: {e}")
                self._led = None

        except Exception as e:
            logger.error(f"❌ Failed to initialize display: {e}", exc_info=True)

    def _on_button_press(self):
        """Handle button press - start long press detection"""
        import time
        self._button_press_time = time.time()
        logger.debug("Button pressed - starting long press timer")

        # Create task to check for long press (thread-safe)
        async def check_long_press():
            await asyncio.sleep(self._long_press_time)
            if self._button_press_time > 0:  # Still pressed
                logger.info("🔄 Long press detected - restart")
                self._handle_long_press()

        # Cancel previous task if exists
        if self._button_press_task and not self._button_press_task.done():
            self._button_press_task.cancel()

        try:
            loop = asyncio.get_event_loop()
            if loop and loop.is_running():
                def create_task():
                    self._button_press_task = asyncio.create_task(check_long_press())

                loop.call_soon_threadsafe(create_task)
            else:
                logger.debug("Event loop not running")
        except RuntimeError as e:
            logger.debug(f"No event loop available: {e}")

    def _on_button_release(self):
        """Handle button release - short press triggers auto_callback"""
        import time
        press_duration = time.time() - self._button_press_time if self._button_press_time > 0 else 0

        if self._button_press_task and not self._button_press_task.done():
            self._button_press_task.cancel()
            self._button_press_task = None

        # Short press triggers auto_callback
        if self._button_press_time > 0 and press_duration < self._long_press_time:
            logger.debug(f"Short press ({press_duration:.2f}s) - triggering auto_callback")
            # Use auto_callback (toggle chat state) from Application
            if self.auto_callback:
                try:
                    # Schedule the callback in the event loop (thread-safe)
                    loop = asyncio.get_event_loop()
                    if loop and loop.is_running():
                        # Check if callback is async
                        if asyncio.iscoroutinefunction(self.auto_callback):
                            loop.call_soon_threadsafe(lambda: asyncio.create_task(self.auto_callback()))
                        else:
                            loop.call_soon_threadsafe(self.auto_callback)
                    else:
                        logger.debug("Event loop not running")
                except Exception as e:
                    logger.error(f"Error triggering auto_callback: {e}")

        self._button_press_time = 0.0

    def _handle_long_press(self):
        """Handle long press - could trigger restart"""
        logger.info("Long press action - implement restart if needed")
        # For now, just log - restart can be implemented later
        self._button_press_time = 0.0

    async def set_callbacks(
        self,
        press_callback: Optional[Callable] = None,
        release_callback: Optional[Callable] = None,
        mode_callback: Optional[Callable] = None,
        auto_callback: Optional[Callable] = None,
        abort_callback: Optional[Callable] = None,
        send_text_callback: Optional[Callable] = None,
    ):
        """
        Set callbacks for button events.
        Compatible with Application interface.
        """
        self.button_press_callback = press_callback
        self.button_release_callback = release_callback
        self.mode_callback = mode_callback
        self.auto_callback = auto_callback
        self.abort_callback = abort_callback
        self.send_text_callback = send_text_callback
        logger.info("✅ Display callbacks registered")

    async def update_status(self, status: str, connected: bool):
        """
        Update status display.
        Compatible with Application interface.

        Status mapping (English -> Vietnamese):
        - "Standby" -> "Ready" (mapped to whisplay_display.py status)
        - "Listening..." -> "Listening"
        - "Speaking..." -> "Speaking"
        - "Connecting..." -> "Connecting"
        """
        self._current_status = status
        self._connected = connected

        # Track listening/speaking state
        self._is_listening = (status == "Listening...")
        self._is_speaking = (status == "Speaking...")

        # Map Application status to WhisplayDisplay status (English keys for status_translations)
        # Also get Vietnamese emoji/text indicator for display
        status_map = {
            "Standby": ("Ready", "Sẵn sàng"),
            "Listening...": ("Listening", "Đang nghe"),
            "Speaking...": ("Speaking", "Đang nói"),
            "Connecting...": ("Connecting", "Đang kết nối"),
        }

        whisplay_status, emoji = status_map.get(status, (status, status))

        # Determine what text to display
        # Priority: assistant message > user message > status description
        if self._last_assistant_message:
            display_text = self._last_assistant_message
        elif self._last_user_message:
            display_text = f"Bạn: {self._last_user_message}"
        else:
            # Default text based on status
            text_map = {
                "Standby": "",  # Show AI-BOX.VN centered
                "Listening...": "Đang nghe...",
                "Speaking...": "Đang nói...",
                "Connecting...": "Đang kết nối...",
            }
            display_text = text_map.get(status, "")

        # Pass English status to whisplay_display.py (it will translate to Vietnamese)
        self._update_display(whisplay_status, emoji, display_text)

    async def update_text(self, text: str):
        """
        Update text display (user/assistant messages).
        Compatible with Application interface.

        This is called when:
        - User speaks (STT): during LISTENING state -> show "Bạn: {text}"
        - Assistant speaks (TTS): during SPEAKING state -> show assistant's message
        """
        if text and text.strip():
            text_stripped = text.strip()

            # Determine if this is user or assistant message based on state
            if self._is_listening:
                # User speaking (STT)
                self._last_user_message = text_stripped
                # Display with "Bạn:" prefix
                display_text = f"Bạn: {text_stripped}"
                self._update_display("", "", display_text)
            elif self._is_speaking:
                # Assistant speaking (TTS)
                self._last_assistant_message = text_stripped
                self._current_text = text_stripped
                # Display assistant's message (no prefix needed)
                self._update_display("", "", self._last_assistant_message)
            else:
                # Store as assistant message by default
                self._last_assistant_message = text_stripped

    async def update_emotion(self, emotion: str):
        """
        Update emotion display.
        Compatible with Application interface.

        Emotion mapping to LED colors:
        - "neutral" -> Green (IDLE)
        - "happy" -> Yellow
        - "thinking" -> Blue
        - "sad" -> Purple
        """
        self._current_emotion = emotion
        logger.debug(f"Emotion: {emotion}")

        # Update LED based on emotion
        if self._led:
            emotion_colors = {
                "neutral": (0, 255, 0),    # Green
                "happy": (255, 255, 0),    # Yellow
                "thinking": (0, 0, 255),   # Blue
                "sad": (128, 0, 128),      # Purple
            }
            color = emotion_colors.get(emotion, (0, 255, 0))
            try:
                self._led.set_color(*color)
            except Exception as e:
                logger.debug(f"LED emotion update error: {e}")

    async def update_button_status(self, text: str):
        """
        Update button status.
        Compatible with Application interface.
        """
        logger.debug(f"Button status: {text}")

    def _update_display(self, status: str, emoji: str, text: str):
        """Internal method to update display via display_manager"""
        if self._display:
            try:
                # Use display's update method
                self._display.update(status=status, emoji=emoji, text=text)
            except Exception as e:
                logger.error(f"Display update error: {e}")

    async def start(self):
        """Start display (compatible with Application interface)"""
        logger.info("🖥️ Whisplay CLI Display started")

        # Enable display rendering if available
        if self._display and hasattr(self._display, 'enable_rendering'):
            try:
                self._display.enable_rendering()
                logger.info("✅ Display rendering enabled")
            except Exception as e:
                logger.error(f"Failed to enable display rendering: {e}")

        # Show initial status
        await self.update_status("Standby", False)

        # Keep display running - wait until app is stopped
        # This is critical for keeping the application alive
        while self._running:
            await asyncio.sleep(1)

        logger.info("🖥️ Whisplay CLI Display stopped")

    async def close(self):
        """Close display (compatible with Application interface)"""
        logger.info("🛑 Stopping Whisplay CLI Display")
        self._running = False
        if self._display:
            try:
                self._display.cleanup()
            except Exception as e:
                logger.error(f"Display cleanup error: {e}")
        if self._led:
            try:
                self._led.off()
            except Exception as e:
                logger.error(f"LED cleanup error: {e}")
