#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Whisplay RGB LED Controller
Controls the RGB LED on Whisplay HAT (pins 22, 18, 16)
Integrates with the state machine for visual feedback
Based on whisplay-ai-chatbot implementation
"""

import logging
import asyncio
from enum import Enum

logger = logging.getLogger(__name__)

class WhisplayRGBLED:
    """RGB LED controller for Whisplay HAT"""
    
    # Color definitions for different states
    STATE_COLORS = {
        'IDLE': (0, 0, 0),           # Off (no light)
        'MUTED': (255, 99, 51),      # Orange/Red (muted_color)
        'LISTENING': (0, 255, 0),    # Bright Green (listening - will blink)
        'THINKING': (0, 0, 255),     # Blue (thinking - will blink)
        'SPEAKING': (0, 255, 0),     # Bright Green (speaking - will blink)
        'BUTTON_PRESSED': (0, 255, 0),  # Green solid when button pressed
    }
    
    # States that should blink
    BLINKING_STATES = {'LISTENING', 'THINKING', 'SPEAKING'}
    
    def __init__(self, whisplay_display):
        """
        Initialize RGB LED controller
        :param whisplay_display: WhisplayDisplay instance with RGB LED methods
        """
        self.display = whisplay_display
        self.current_state = 'IDLE'
        self.current_color = (0, 0, 0)
        self._fade_enabled = True
        self._blink_task = None
        self._blink_enabled = False
        self._blink_interval = 0.5  # 500ms blink interval
        
        # Set initial state (IDLE = off) - turn off LED immediately
        self.display.set_rgb(0, 0, 0)
        self.current_state = 'IDLE'
        self.current_color = (0, 0, 0)
        logger.info("✅ Whisplay RGB LED controller initialized (LED off)")
    
    def set_state(self, state, solid=False):
        """
        Set LED state and update color
        :param state: State enum or string (IDLE, MUTED, LISTENING, THINKING, SPEAKING, BUTTON_PRESSED)
        :param solid: If True, set solid color (no blinking). Used for button press.
        """
        state_name = state.name if hasattr(state, 'name') else str(state).upper()
        
        # Stop blinking if changing state
        if self._blink_task and not self._blink_task.done():
            self._blink_task.cancel()
            self._blink_task = None
        self._blink_enabled = False
        
        if self.current_state == state_name and not solid:
            return
        
        logger.debug(f"RGB LED state: {self.current_state} -> {state_name} (solid={solid})")
        self.current_state = state_name
        
        # Get color for state
        target_color = self.STATE_COLORS.get(state_name, (0, 0, 0))
        
        # IDLE: turn off LED completely (immediate, no fade)
        if state_name == 'IDLE':
            # Stop any blinking first
            if self._blink_task and not self._blink_task.done():
                self._blink_task.cancel()
                self._blink_task = None
            self._blink_enabled = False
            # Turn off LED immediately
            self.display.set_rgb(0, 0, 0)
            self.current_color = (0, 0, 0)
            return
        
        # Update LED color
        if solid:
            # Solid color (no blinking) - used for button press
            self.display.set_rgb(*target_color)
            self.current_color = target_color
        elif state_name in self.BLINKING_STATES:
            # Start blinking for LISTENING, THINKING, SPEAKING
            self._start_blinking(target_color)
        else:
            # Solid color for other states (MUTED, etc.)
            if self._fade_enabled:
                try:
                    loop = asyncio.get_running_loop()
                    asyncio.create_task(self.display.set_rgb_fade_async(*target_color, duration_ms=200))
                except RuntimeError:
                    self.display.set_rgb_fade(*target_color, duration_ms=200)
            else:
                self.display.set_rgb(*target_color)
            self.current_color = target_color
    
    def _start_blinking(self, color):
        """
        Start blinking LED with specified color
        :param color: RGB tuple (r, g, b)
        """
        self._blink_enabled = True
        self.current_color = color
        
        # Cancel existing blink task if any
        if self._blink_task and not self._blink_task.done():
            self._blink_task.cancel()
        
        # Create new blink task
        try:
            loop = asyncio.get_running_loop()
            self._blink_task = asyncio.create_task(self._blink_loop(color))
        except RuntimeError:
            # No event loop, can't blink async - use solid color
            logger.warning("No event loop for blinking, using solid color")
            self.display.set_rgb(*color)
    
    async def _blink_loop(self, color):
        """
        Blink LED loop - turns LED on and off repeatedly
        :param color: RGB tuple (r, g, b)
        """
        try:
            while self._blink_enabled:
                # Turn on
                self.display.set_rgb(*color)
                await asyncio.sleep(self._blink_interval)
                
                if not self._blink_enabled:
                    break
                
                # Turn off
                self.display.set_rgb(0, 0, 0)
                await asyncio.sleep(self._blink_interval)
        except asyncio.CancelledError:
            # Task was cancelled, turn off LED
            self.display.set_rgb(0, 0, 0)
            raise
    
    async def volume_change(self, volume_percent: int):
        """
        Show volume change indication (async)
        :param volume_percent: Volume percentage (0-100)
        """
        logger.debug(f"RGB LED volume indication: {volume_percent}%")
        
        # Flash white briefly to indicate volume change
        original_color = self.current_color
        self.display.set_rgb(255, 255, 255)
        await asyncio.sleep(0.1)
        
        # Restore original color (async fade)
        if self._fade_enabled:
            await self.display.set_rgb_fade_async(*original_color, duration_ms=100)
        else:
            self.display.set_rgb(*original_color)
    
    def set_color(self, r, g, b):
        """
        Set RGB LED to specific color
        :param r: Red (0-255)
        :param g: Green (0-255)
        :param b: Blue (0-255)
        """
        self.display.set_rgb(r, g, b)
        self.current_color = (r, g, b)
    
    async def set_color_fade(self, r, g, b, duration_ms=200):
        """
        Fade RGB LED to specific color (async)
        :param r: Red (0-255)
        :param g: Green (0-255)
        :param b: Blue (0-255)
        :param duration_ms: Fade duration in milliseconds
        """
        await self.display.set_rgb_fade_async(r, g, b, duration_ms)
        self.current_color = (r, g, b)
    
    def set_button_pressed(self):
        """
        Set LED to solid green when button is pressed (no blinking)
        This is called when user presses button to start conversation
        """
        logger.debug("RGB LED: Button pressed - solid green")
        self.set_state('BUTTON_PRESSED', solid=True)
    
    def off(self):
        """Turn off RGB LED immediately"""
        self.display.set_rgb(0, 0, 0)
        self.current_color = (0, 0, 0)
        # Stop blinking if active
        self._blink_enabled = False
        if self._blink_task and not self._blink_task.done():
            self._blink_task.cancel()

    def stop(self):
        """Turn off LED and cleanup"""
        logger.info("Stopping Whisplay RGB LED controller...")
        self.off()

