#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Emoji Helper - Render SVG emoji icons for Whisplay HAT display
Uses Noto Color Emoji SVG files for emoji display
"""

import os
import logging
from typing import Optional, Dict, Tuple
from pathlib import Path

logger = logging.getLogger(__name__)


# Emoji mapping to SVG filenames (hex Unicode codepoints)
EMOJI_MAP = {
    # Face emotions
    "smile": "1f603.svg",           # 😊
    "smiling_face": "1f603.svg",
    "happy": "1f603.svg",
    "wink": "1f609.svg",            # 😉
    "neutral": "1f642.svg",         # 🙂
    "relaxed": "1f642.svg",
    "thinking": "1f914.svg",       # 🤔
    "thought": "1f4ad.svg",        # 💭

    # Status/Action icons
    "listening": "1f442.svg",      # 👂
    "ear": "1f442.svg",
    "speaking": "1f5e3.svg",       # 🗣️
    "speaking_head": "1f5e3.svg",
    "connecting": "1f504.svg",     # 🔄
    "refresh": "1f504.svg",
    "waiting": "23f3.svg",         # ⏳
    "hourglass": "23f3.svg",
    "wireless": "1f9ed.svg",       # 📭
    "signal": "1f4f6.svg",         # 📶
    "wifi": "1f4f6.svg",
    "speaker": "1f50a.svg",        # 🔊
    "sound": "1f50a.svg",
    "plug": "1f50c.svg",           # 🔌
    "connection": "1f50c.svg",

    # Success/Error icons
    "check": "2705.svg",           # ✅
    "checkmark": "2705.svg",
    "success": "2705.svg",
    "done": "2705.svg",
    "error": "274c.svg",           # ❌ (cross mark)
    "cross": "274c.svg",
    "x": "274c.svg",
    "warning": "26a0.svg",         # ⚠️
    "prohibited": "1f6ab.svg",     # 🚫
    "no_entry": "1f6ab.svg",
    "stop": "1f6ab.svg",

    # Other useful icons
    "sparkles": "2728.svg",        # ✨
    "bell": "1f3a7.svg",           # 🔔
    "satellite": "1f4e7.svg",      # 📡
    "battery": "1f50b.svg",        # 🔋
}

# Emoji to character mapping for fallback text display
EMOJI_CHARS = {
    "smile": "😊",
    "happy": "😊",
    "neutral": "🙂",
    "thinking": "🤔",
    "listening": "👂",
    "speaking": "🗣️",
    "connecting": "🔄",
    "waiting": "⏳",
    "check": "✅",
    "error": "❌",
    "warning": "⚠️",
    "wireless": "📶",
    "speaker": "🔊",
}


class EmojiHelper:
    """
    Helper class for loading and rendering emoji SVG icons
    """

    def __init__(self, emoji_dir: str = "emoji"):
        """
        Initialize emoji helper

        Args:
            emoji_dir: Directory containing emoji SVG files
        """
        self.emoji_dir = Path(emoji_dir)
        self._emoji_cache: Dict[str, bytes] = {}

        if not self.emoji_dir.exists():
            logger.warning(f"Emoji directory not found: {self.emoji_dir}")

    def get_emoji_file(self, emoji_name: str) -> Optional[str]:
        """
        Get SVG file path for an emoji name

        Args:
            emoji_name: Name of the emoji (e.g., "smile", "listening")

        Returns:
            Path to SVG file, or None if not found
        """
        svg_file = EMOJI_MAP.get(emoji_name)
        if svg_file:
            file_path = self.emoji_dir / svg_file
            if file_path.exists():
                return str(file_path)

        # Try direct emoji name as filename
        file_path = self.emoji_dir / f"{emoji_name}.svg"
        if file_path.exists():
            return str(file_path)

        logger.debug(f"Emoji file not found: {emoji_name}")
        return None

    def get_emoji_char(self, emoji_name: str) -> str:
        """
        Get emoji character for fallback text display

        Args:
            emoji_name: Name of the emoji

        Returns:
            Emoji character string
        """
        return EMOJI_CHARS.get(emoji_name, "")

    def load_svg(self, emoji_name: str) -> Optional[str]:
        """
        Load SVG content for an emoji

        Args:
            emoji_name: Name of the emoji

        Returns:
            SVG content as string, or None if not found
        """
        if emoji_name in self._emoji_cache:
            return self._emoji_cache[emoji_name]

        file_path = self.get_emoji_file(emoji_name)
        if file_path:
            try:
                with open(file_path, 'r') as f:
                    svg_content = f.read()
                self._emoji_cache[emoji_name] = svg_content
                return svg_content
            except Exception as e:
                logger.error(f"Error loading emoji {emoji_name}: {e}")

        return None

    def render_svg_to_image(self, emoji_name: str, width: int = 40, height: int = 40) -> Optional[object]:
        """
        Render SVG emoji to PIL Image

        Args:
            emoji_name: Name of the emoji
            width: Target width in pixels
            height: Target height in pixels

        Returns:
            PIL Image object, or None if rendering failed
        """
        try:
            from PIL import Image
            from io import BytesIO

            # For now, return a placeholder since SVG rendering requires cairosvg or similar
            # In a real implementation, you would use cairosvg or svglib to render SVG to image
            # For now, we'll use text emoji fallback

            emoji_char = self.get_emoji_char(emoji_name)
            if emoji_char:
                # Create a simple image with emoji text
                image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
                from PIL import ImageDraw, ImageFont

                draw = ImageDraw.Draw(image)

                # Try to use a font that supports emoji
                try:
                    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", int(height * 0.8))
                except:
                    font = ImageFont.load_default()

                # Draw emoji text centered
                bbox = draw.textbbox((0, 0), emoji_char, font=font)
                text_width = bbox[2] - bbox[0]
                text_height = bbox[3] - bbox[1]
                x = (width - text_width) // 2
                y = (height - text_height) // 2

                draw.text((x, y), emoji_char, font=font, fill=(255, 255, 255, 255))
                return image

        except Exception as e:
            logger.debug(f"Error rendering emoji {emoji_name}: {e}")

        return None

    def render_svg_to_rgb565(self, emoji_name: str, width: int = 40, height: int = 40) -> Optional[bytes]:
        """
        Render SVG emoji to RGB565 format for display

        Args:
            emoji_name: Name of the emoji
            width: Target width in pixels
            height: Target height in pixels

        Returns:
            RGB565 byte array, or None if rendering failed
        """
        try:
            image = self.render_svg_to_image(emoji_name, width, height)
            if image:
                # Convert RGBA to RGB565
                return self._image_to_rgb565(image, width, height)
        except Exception as e:
            logger.debug(f"Error rendering emoji to RGB565 {emoji_name}: {e}")

        return None

    def _image_to_rgb565(self, image, width: int, height: int) -> bytes:
        """
        Convert PIL Image to RGB565 format

        Args:
            image: PIL Image object (RGBA)
            width: Image width
            height: Image height

        Returns:
            RGB565 byte array
        """
        # Convert RGBA to RGB
        rgb_image = image.convert("RGB")
        pixels = list(rgb_image.getdata())

        rgb565_data = bytearray()
        for r, g, b in pixels:
            # Convert RGB to RGB565: 5 bits red, 6 bits green, 5 bits blue
            rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)
            rgb565_data.extend([rgb565 >> 8, rgb565 & 0xFF])

        return bytes(rgb565_data)

    def list_available_emojis(self) -> list:
        """
        List all available emoji names

        Returns:
            List of emoji names
        """
        return list(EMOJI_MAP.keys())


# Global instance
_emoji_instance: Optional[EmojiHelper] = None


def get_emoji_helper(emoji_dir: str = "emoji") -> EmojiHelper:
    """
    Get emoji helper instance (singleton)

    Args:
        emoji_dir: Directory containing emoji SVG files

    Returns:
        EmojiHelper instance
    """
    global _emoji_instance
    if _emoji_instance is None:
        _emoji_instance = EmojiHelper(emoji_dir)
    return _emoji_instance
