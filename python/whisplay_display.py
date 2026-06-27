#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Whisplay HAT Display - 240x280 LCD with RGB LED and Button
Refactored to use gpiozero for better compatibility with newer Pi OS
"""

import logging
import spidev
import time
import asyncio
from PIL import Image, ImageDraw, ImageFont
import os
# Use gpiozero for all GPIO operations (more reliable on newer Pi OS)
from gpiozero import Button, OutputDevice, PWMOutputDevice
from display.base_display import BaseDisplay

logger = logging.getLogger(__name__)

class WhisplayDisplay(BaseDisplay):
    """Whisplay HAT Display Controller - Using gpiozero for all GPIO operations"""
    
    LCD_WIDTH = 240
    LCD_HEIGHT = 280
    CornerHeight = 20
    
    # GPIO pin mapping: BOARD (physical) -> BCM (GPIO number)
    # Physical pin 7  -> GPIO 4  (RST)
    # Physical pin 11 -> GPIO 17 (Button)
    # Physical pin 13 -> GPIO 27 (DC)
    # Physical pin 15 -> GPIO 22 (LED/Backlight)
    # Physical pin 16 -> GPIO 23 (BLUE)
    # Physical pin 18 -> GPIO 24 (GREEN)
    # Physical pin 22 -> GPIO 25 (RED)
    
    # BCM GPIO numbers for gpiozero
    DC_PIN_BCM = 27
    RST_PIN_BCM = 4
    LED_PIN_BCM = 22
    RED_PIN_BCM = 25
    GREEN_PIN_BCM = 24
    BLUE_PIN_BCM = 23
    BUTTON_PIN_BCM = 17
    
    def __init__(self, config: dict):
        self.config = config
        
        # Initialize all GPIO pins to None first for proper cleanup on error
        self.dc_pin = None
        self.rst_pin = None
        self.backlight_pwm = None
        self.backlight_pin = None
        self.red_pwm = None
        self.green_pwm = None
        self.blue_pwm = None
        self.button = None
        self.spi = None
        self.button_press_callback = None
        self.button_release_callback = None
        
        try:
            # Detect hardware version to determine backlight mode
            self._detect_hardware_version()
            
            # Initialize LCD control pins using gpiozero OutputDevice
            self.dc_pin = OutputDevice(self.DC_PIN_BCM, active_high=True, initial_value=False)
            self.rst_pin = OutputDevice(self.RST_PIN_BCM, active_high=True, initial_value=True)
            
            # Initialize backlight based on hardware version
            if self.backlight_mode:
                # PWM mode for Pi Zero 2 W, Pi 3B, Pi 4B, etc.
                self.backlight_pwm = PWMOutputDevice(self.LED_PIN_BCM, frequency=1000, initial_value=1.0)
            else:
                # Simple switch mode for Pi Zero / Pi Zero W
                self.backlight_pin = OutputDevice(self.LED_PIN_BCM, active_high=False, initial_value=False)
            
            # Initialize RGB LED pins using gpiozero PWMOutputDevice
            self.red_pwm = PWMOutputDevice(self.RED_PIN_BCM, frequency=100, initial_value=0.0)
            self.green_pwm = PWMOutputDevice(self.GREEN_PIN_BCM, frequency=100, initial_value=0.0)
            self.blue_pwm = PWMOutputDevice(self.BLUE_PIN_BCM, frequency=100, initial_value=0.0)
            self._current_r = 0
            self._current_g = 0
            self._current_b = 0
            
            # Initialize button using gpiozero
            try:
                # gpiozero Button with debouncing
                self.button = Button(self.BUTTON_PIN_BCM, pull_up=True, bounce_time=0.05)
                self.button.when_pressed = self._on_gpiozero_press
                self.button.when_released = self._on_gpiozero_release
                logger.info(f"✅ Button initialized via gpiozero (BCM GPIO {self.BUTTON_PIN_BCM})")
            except Exception as e:
                logger.warning(f"⚠️ Could not initialize button with gpiozero: {e}")
                logger.warning("⚠️ Button functionality will be disabled.")
            
            # Initialize SPI (still using spidev, not part of gpiozero)
            self.spi = spidev.SpiDev()
            self.spi.open(0, 0)
            self.spi.max_speed_hz = 100_000_000
            self.spi.mode = 0b00
            
            # Font path
            font_path = config.get('font_path', 'NotoSansSC-Bold.ttf')
            if not os.path.exists(font_path):
                alt_paths = [
                    '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
                    '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf'
                ]
                for path in alt_paths:
                    if os.path.exists(path):
                        font_path = path
                        break
            
            self.font_path = font_path
            self.previous_frame = None
            
            # Set backlight to 0 before init
            self.set_backlight(0)
            
            self._reset_lcd()
            self._init_display()
            self.fill_screen(0)
            
            # Set backlight to desired brightness after init
            self.set_backlight(100)
            
            logger.info(f"✅ Whisplay HAT Display initialized: {self.LCD_WIDTH}x{self.LCD_HEIGHT} (Backlight mode: {'PWM' if self.backlight_mode else 'Simple Switch'})")
        except Exception as e:
            # Cleanup GPIO pins if initialization failed
            logger.error(f"❌ Failed to initialize WhisplayDisplay: {e}")
            logger.error("Cleaning up GPIO pins...")
            self.cleanup()
            raise  # Re-raise the exception
    
    def _detect_hardware_version(self):
        """
        Detect Raspberry Pi hardware version and set backlight mode accordingly.
        Pi Zero / Pi Zero W: Simple Switch Mode
        Pi Zero 2 W / Pi 3B / Pi 4B: PWM Mode
        """
        try:
            with open("/proc/cpuinfo", "r") as f:
                lines = f.readlines()
                model_name = None
                for line in lines:
                    if line.startswith("Model"):
                        model_name = line.strip().split(":")[1].strip()
                        break
                if model_name:
                    if "Zero" in model_name and "2" not in model_name:
                        self.backlight_mode = False  # Simple Switch Mode
                    else:
                        self.backlight_mode = True  # PWM Mode
                    logger.info(f"Detected hardware: {model_name}, Backlight mode: {'PWM' if self.backlight_mode else 'Simple Switch'}")
                else:
                    logger.warning("Model name not found in /proc/cpuinfo, defaulting to PWM mode")
                    self.backlight_mode = True
        except Exception as e:
            logger.warning(f"Error detecting hardware version: {e}, defaulting to PWM mode")
            self.backlight_mode = True
    
    def set_backlight(self, brightness):
        """Set backlight brightness (0-100)"""
        if self.backlight_mode:
            # PWM mode
            if self.backlight_pwm:
                # gpiozero PWM uses 0.0-1.0 range, invert for backlight
                value = 1.0 - (brightness / 100.0)
                self.backlight_pwm.value = max(0.0, min(1.0, value))
        else:
            # Simple switch mode (Pi Zero / Pi Zero W)
            if self.backlight_pin:
                if brightness == 0:
                    self.backlight_pin.off()  # Turn off backlight
                else:
                    self.backlight_pin.on()  # Turn on backlight
    
    def set_rgb(self, r, g, b):
        """Set RGB LED color (0-255)"""
        # gpiozero PWM uses 0.0-1.0 range, invert for common anode RGB LED
        self.red_pwm.value = 1.0 - (r / 255.0)
        self.green_pwm.value = 1.0 - (g / 255.0)
        self.blue_pwm.value = 1.0 - (b / 255.0)
        self._current_r = r
        self._current_g = g
        self._current_b = b
    
    def set_rgb_fade(self, r, g, b, duration_ms=100):
        """
        Fade RGB LED to target color (blocking).
        For async version, use set_rgb_fade_async() instead.
        """
        steps = 20
        delay_ms = duration_ms / steps
        r_step = (r - self._current_r) / steps
        g_step = (g - self._current_g) / steps
        b_step = (b - self._current_b) / steps
        
        for _ in range(steps + 1):
            r_interim = int(self._current_r + _ * r_step)
            g_interim = int(self._current_g + _ * g_step)
            b_interim = int(self._current_b + _ * b_step)
            self.set_rgb(
                max(0, min(255, r_interim)),
                max(0, min(255, g_interim)),
                max(0, min(255, b_interim)),
            )
            time.sleep(delay_ms / 1000.0)
    
    async def set_rgb_fade_async(self, r, g, b, duration_ms=100):
        """Fade RGB LED to target color asynchronously (non-blocking)."""
        steps = 20
        delay_ms = duration_ms / steps
        r_step = (r - self._current_r) / steps
        g_step = (g - self._current_g) / steps
        b_step = (b - self._current_b) / steps
        
        for _ in range(steps + 1):
            r_interim = int(self._current_r + _ * r_step)
            g_interim = int(self._current_g + _ * g_step)
            b_interim = int(self._current_b + _ * b_step)
            self.set_rgb(
                max(0, min(255, r_interim)),
                max(0, min(255, g_interim)),
                max(0, min(255, b_interim)),
            )
            await asyncio.sleep(delay_ms / 1000.0)
    
    def button_pressed(self):
        """Check if button is pressed"""
        if self.button:
            return self.button.is_pressed
        return False
    
    def on_button_press(self, callback):
        """Set button press callback"""
        self.button_press_callback = callback
    
    def on_button_release(self, callback):
        """Set button release callback"""
        self.button_release_callback = callback
    
    def _on_gpiozero_press(self):
        """Handle button press event from gpiozero"""
        if self.button_press_callback:
            self.button_press_callback()
    
    def _on_gpiozero_release(self):
        """Handle button release event from gpiozero"""
        if self.button_release_callback:
            self.button_release_callback()
    
    def _reset_lcd(self):
        """Reset LCD"""
        self.rst_pin.on()
        time.sleep(0.1)
        self.rst_pin.off()
        time.sleep(0.1)
        self.rst_pin.on()
        time.sleep(0.12)
    
    def _init_display(self):
        """Initialize display"""
        self._send_command(0x11)
        time.sleep(0.12)
        USE_HORIZONTAL = 1
        direction = {0: 0x00, 1: 0xC0, 2: 0x70, 3: 0xA0}.get(USE_HORIZONTAL, 0x00)
        self._send_command(0x36, direction)
        self._send_command(0x3A, 0x05)
        self._send_command(0xB2, 0x0C, 0x0C, 0x00, 0x33, 0x33)
        self._send_command(0xB7, 0x35)
        self._send_command(0xBB, 0x32)
        self._send_command(0xC2, 0x01)
        self._send_command(0xC3, 0x15)
        self._send_command(0xC4, 0x20)
        self._send_command(0xC6, 0x0F)
        self._send_command(0xD0, 0xA4, 0xA1)
        self._send_command(0xE0, 0xD0, 0x08, 0x0E, 0x09, 0x09, 0x05, 0x31, 0x33, 0x48, 0x17, 0x14, 0x15, 0x31, 0x34)
        self._send_command(0xE1, 0xD0, 0x08, 0x0E, 0x09, 0x09, 0x15, 0x31, 0x33, 0x48, 0x17, 0x14, 0x15, 0x31, 0x34)
        self._send_command(0x21)
        self._send_command(0x29)
    
    def _send_command(self, cmd, *args):
        """Send command to display"""
        self.dc_pin.off()  # Command mode
        self.spi.xfer2([cmd])
        if args:
            self.dc_pin.on()  # Data mode
            self._send_data(list(args))
    
    def _send_data(self, data):
        """Send data to display"""
        self.dc_pin.on()  # Data mode
        max_chunk = 4096
        for i in range(0, len(data), max_chunk):
            self.spi.writebytes(data[i : i + max_chunk])
    
    def set_window(self, x0, y0, x1, y1, use_horizontal=0):
        """Set display window"""
        if use_horizontal in (0, 1):
            self._send_command(0x2A, x0 >> 8, x0 & 0xFF, x1 >> 8, x1 & 0xFF)
            self._send_command(0x2B, (y0 + 20) >> 8, (y0 + 20) & 0xFF, (y1 + 20) >> 8, (y1 + 20) & 0xFF)
        elif use_horizontal in (2, 3):
            self._send_command(0x2A, (x0 + 20) >> 8, (x0 + 20) & 0xFF, (x1 + 20) >> 8, (x1 + 20) & 0xFF)
            self._send_command(0x2B, y0 >> 8, y0 & 0xFF, y1 >> 8, y1 & 0xFF)
        self._send_command(0x2C)
    
    def draw_pixel(self, x, y, color):
        """Draw a pixel"""
        if x >= self.LCD_WIDTH or y >= self.LCD_HEIGHT:
            return
        self.set_window(x, y, x, y)
        self._send_data([(color >> 8) & 0xFF, color & 0xFF])
    
    def fill_screen(self, color):
        """Fill screen with color"""
        self.set_window(0, 0, self.LCD_WIDTH - 1, self.LCD_HEIGHT - 1)
        buffer = []
        high = (color >> 8) & 0xFF
        low = color & 0xFF
        for _ in range(self.LCD_WIDTH * self.LCD_HEIGHT):
            buffer.extend([high, low])
        self._send_data(buffer)
    
    def draw_image(self, x, y, width, height, pixel_data):
        """Draw image"""
        if (x + width > self.LCD_WIDTH) or (y + height > self.LCD_HEIGHT):
            raise ValueError("Image size exceeds screen bounds")
        self.set_window(x, y, x + width - 1, y + height - 1)
        self._send_data(pixel_data)
    
    def render_frame(self, status, emoji, text, scroll_top, battery_level, battery_color, image_path):
        """Render frame - simplified version, full implementation in display_utils"""
        try:
            from display_utils import render_whisplay_frame
            render_whisplay_frame(self, status, emoji, text, scroll_top, battery_level, battery_color, image_path, self.font_path)
        except ImportError:
            logger.warning("display_utils not available, using simple rendering")
            self.fill_screen(0)
    
    # BaseDisplay interface implementation
    def show_ready(self, text: str = "Chờ đánh thức", emoji: str = "✅"):
        """Show ready state"""
        self.show_status("Sẵn sàng", emoji, text)
    
    def show_pairing_code(self, code: str, message: str = ""):
        """Show pairing code"""
        try:
            from display_utils import render_whisplay_frame
            code_display = code[:4] if len(code) >= 4 else code
            if message:
                text = f"Mã thêm thiết bị: {code_display}\n{message}".strip()
            else:
                text = f"Mã thêm thiết bị: {code_display}\nNhập mã này vào trang Web vietbot để thêm thiết bị"
            render_whisplay_frame(
                self, 
                "Ghép đôi", 
                "🔗", 
                text, 
                0, 
                None, 
                None, 
                None, 
                self.font_path
            )
        except Exception as e:
            logger.error(f"Error showing pairing code: {e}")
            self.fill_screen(0)
    
    def show_error(self, text: str):
        """Show error message"""
        try:
            from display_utils import render_whisplay_frame
            error_translations = {
                "Registration failed": "Lỗi đăng ký thiết bị",
                "Connection timeout": "Hết thời gian kết nối",
                "Error:": "Lỗi:",
                "Device not registered. Please pair device first.": "Thiết bị chưa được ghép đôi. Vui lòng ghép đôi thiết bị trước.",
            }
            translated_text = text
            for eng, vi in error_translations.items():
                if eng in text:
                    translated_text = text.replace(eng, vi)
                    break
            render_whisplay_frame(
                self, 
                "Lỗi", 
                "❌", 
                translated_text, 
                0, 
                None, 
                None, 
                None, 
                self.font_path
            )
        except Exception as e:
            logger.error(f"Error showing error: {e}")
            self.fill_screen(0)
    
    def show_status(self, status: str, emoji: str = "", text: str = ""):
        """Show status with emoji and text"""
        try:
            from display_utils import render_whisplay_frame
            status_translations = {
                "Ready": "Sẵn sàng",
                "Listening": "Đang nghe",
                "Thinking": "Đang suy nghĩ",
                "Speaking": "Đang nói",
                "Muted": "Tắt tiếng",
                "Connecting": "Đang kết nối",
                "Pairing": "Ghép đôi",
                "Error": "Lỗi",
            }
            translated_status = status_translations.get(status, status)
            render_whisplay_frame(
                self, 
                translated_status or "", 
                emoji or "", 
                text or "", 
                0, 
                None, 
                None, 
                None, 
                self.font_path
            )
        except Exception as e:
            logger.error(f"Error showing status: {e}")
            self.fill_screen(0)
    
    def update(self, **kwargs):
        """Update display with kwargs"""
        self.show_status(
            kwargs.get("status", ""), 
            kwargs.get("emoji", ""), 
            kwargs.get("text", "")
        )
    
    def stop(self):
        """Stop display (alias for cleanup)"""
        self.cleanup()
    
    def cleanup(self):
        """Cleanup resources"""
        try:
            # Cleanup gpiozero devices
            if self.button:
                self.button.close()
                self.button = None
            
            if self.backlight_pwm:
                self.backlight_pwm.close()
                self.backlight_pwm = None
            
            if self.backlight_pin:
                self.backlight_pin.close()
                self.backlight_pin = None
            
            if self.red_pwm:
                self.red_pwm.close()
            if self.green_pwm:
                self.green_pwm.close()
            if self.blue_pwm:
                self.blue_pwm.close()
            
            if self.dc_pin:
                self.dc_pin.close()
            if self.rst_pin:
                self.rst_pin.close()
            
            # Cleanup SPI
            if self.spi:
                self.spi.close()
            
            logger.info("✅ Whisplay Display cleaned up")
        except Exception as e:
            logger.error(f"Error cleaning up display: {e}")
