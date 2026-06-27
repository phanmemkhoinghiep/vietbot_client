#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Display Utilities - Text and Emoji Rendering
Based on whisplay-ai-chatbot implementation
"""

import os
import unicodedata
from io import BytesIO
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import logging

logger = logging.getLogger(__name__)

# Try to import cairosvg for emoji rendering
try:
    import cairosvg
    CAIROSVG_AVAILABLE = True
    logger.debug("✅ cairosvg available for emoji rendering")
except ImportError:
    CAIROSVG_AVAILABLE = False
    logger.debug("ℹ️ cairosvg not available, emoji will use font rendering (fallback)")

char_size_cache = {}
line_image_cache = {}
# Font cache to avoid reloading fonts on every render
_font_cache = {}

class ColorUtils:
    @staticmethod
    def rgb565_to_rgb255(color_565):
        """Convert RGB565 to RGB255 tuple"""
        red_5bit = (color_565 >> 11) & 0x1F
        green_6bit = (color_565 >> 5) & 0x3F
        blue_5bit = color_565 & 0x1F
        red_8bit = (red_5bit * 255) // 31
        green_8bit = (green_6bit * 255) // 63
        blue_8bit = (blue_5bit * 255) // 31
        return (red_8bit, green_8bit, blue_8bit)

    @staticmethod
    def hex_to_rgb255(hex_color):
        """Convert hex color to RGB255 tuple"""
        hex_color = hex_color.lstrip("#")
        if len(hex_color) == 6:
            return (int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16))
        elif len(hex_color) == 8:
            return (int(hex_color[0:2], 16), int(hex_color[2:4], 16), int(hex_color[4:6], 16))
        return None

    @staticmethod
    def get_rgb255_from_any(rgb_led):
        """Auto-detect format and convert to RGB255"""
        if isinstance(rgb_led, int):
            if 0 <= rgb_led <= 0xFFFF:
                return ColorUtils.rgb565_to_rgb255(rgb_led)
        elif isinstance(rgb_led, str):
            return ColorUtils.hex_to_rgb255(rgb_led)
        return None

    @staticmethod
    def calculate_luminance(rgb_tuple):
        """Calculate RGB luminance"""
        if rgb_tuple is None:
            return -1
        r, g, b = rgb_tuple
        return 0.299 * r + 0.587 * g + 0.114 * b


class ImageUtils:
    @staticmethod
    def image_to_rgb565(image: Image.Image, width: int, height: int) -> list:
        """Convert PIL image to RGB565 byte array"""
        image = image.convert("RGB")
        image.thumbnail((width, height), Image.LANCZOS)
        bg = Image.new("RGB", (width, height), (0, 0, 0))
        x = (width - image.width) // 2
        y = (height - image.height) // 2
        bg.paste(image, (x, y))
        np_img = np.array(bg)
        r = (np_img[:, :, 0] >> 3).astype(np.uint16)
        g = (np_img[:, :, 1] >> 2).astype(np.uint16)
        b = (np_img[:, :, 2] >> 3).astype(np.uint16)
        rgb565 = (r << 11) | (g << 5) | b
        high_byte = (rgb565 >> 8).astype(np.uint8)
        low_byte = (rgb565 & 0xFF).astype(np.uint8)
        interleaved = np.dstack((high_byte, low_byte)).flatten().tolist()
        return interleaved


class EmojiUtils:
    @staticmethod
    def emoji_to_filename(char):
        """Convert emoji to filename"""
        return '-'.join(f"{ord(c):x}" for c in char) + ".svg"

    @staticmethod
    def get_local_emoji_svg_image(char, size):
        """Get emoji SVG image (with PNG fallback) - Simplified: return None to use text instead"""
        # Since we're using text-based indicators like [SẴN SÀNG] instead of emoji,
        # just return None to fall back to text rendering
        return None

    @staticmethod
    def is_emoji(char):
        """Check if character is emoji"""
        return unicodedata.category(char) in ('So', 'Sk') or ord(char) > 0x1F000
    
    @staticmethod
    def extract_emojis(text: str) -> str:
        """
        Extract first emoji from text (similar to whisplay-ai-chatbot extractEmojis)
        Returns the first emoji found (including complex emojis with zero-width joiner), or None if no emoji found
        
        This matches emoji sequences like:
        - Simple emojis: 😊, 👍
        - Complex emojis: 👨‍👩‍👧‍👦, 👨‍💻
        - Flags: 🇻🇳
        - Emojis with variation selector: ❤️
        """
        if not text:
            return None
        
        import re
        # Pattern to match emoji sequences including zero-width joiner (\u200d) and variation selector (\ufe0f)
        # This pattern matches complete emoji sequences, not individual characters
        # Similar to whisplay-ai-chatbot: /([\p{Emoji_Presentation}\u200d\ufe0f])/gu
        # Python equivalent: match emoji presentation characters, zero-width joiner, and variation selector
        emoji_pattern = re.compile(
            r'[\U0001F300-\U0001F9FF\U0001FA00-\U0001FAFF'  # Emoji symbols
            r'\U00002600-\U000027BF'  # Miscellaneous symbols
            r'\U0001F600-\U0001F64F'  # Emoticons
            r'\U0001F680-\U0001F6FF'  # Transport & map symbols
            r'\U0001F1E0-\U0001F1FF'  # Regional indicator symbols (flags)
            r'\u200D'  # Zero-width joiner
            r'\ufe0f'  # Variation selector-16
            r']+',  # Match one or more characters (to capture complete sequences)
            re.UNICODE
        )
        
        matches = emoji_pattern.findall(text)
        
        if matches:
            # Return first complete emoji sequence found
            # This will correctly capture complex emojis like 👨‍👩‍👧‍👦, 🇻🇳, ❤️
            return matches[0]
        return None


class TextUtils:
    @staticmethod
    def get_char_size(font, char):
        """Get character size"""
        global char_size_cache
        cache_key = (font.getname(), font.size, char)
        if cache_key in char_size_cache:
            return char_size_cache[cache_key]
        
        if EmojiUtils.is_emoji(char):
            # Try to get emoji from SVG first
            emoji_img = EmojiUtils.get_local_emoji_svg_image(char, size=font.size)
            if emoji_img:
                char_size_cache[cache_key] = (emoji_img.width, emoji_img.height)
                return emoji_img.width, emoji_img.height
            # Fallback: Use font to render emoji (some fonts support emoji)
            # This ensures emoji is rendered even without SVG files
            try:
                bbox = font.getbbox(char)
                width = bbox[2] - bbox[0]
                height = bbox[3] - bbox[1]
                # If font returns valid size, use it
                if width > 0 and height > 0:
                    char_size_cache[cache_key] = (width, height)
                    return width, height
            except Exception:
                pass
            # Last resort: return default size for emoji
            char_size_cache[cache_key] = (font.size, font.size)
            return font.size, font.size
        else:
            bbox = font.getbbox(char)
            char_size_cache[cache_key] = (bbox[2] - bbox[0], bbox[3] - bbox[1])
            return char_size_cache[cache_key]

    @staticmethod
    def draw_mixed_text(draw, image, text, font, start_xy):
        """Draw text with emoji support"""
        x, y = start_xy
        add_img = TextUtils.get_line_img(text, font)
        image.paste(add_img, (x, y), add_img)

    @staticmethod
    def get_line_img(text, font):
        """Get line image with emoji support"""
        cache_key = (font.getname(), font.size, text)
        if cache_key in line_image_cache:
            return line_image_cache[cache_key]
        
        x, y = 0, 0
        ascent, descent = font.getmetrics()
        baseline = y + ascent
        line_height = ascent + descent
        width = 0
        
        for char in text:
            width += TextUtils.get_char_size(font, char)[0]
        
        img = Image.new("RGBA", (width, line_height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        
        for char in text:
            if EmojiUtils.is_emoji(char):
                # Try to get emoji from SVG first
                emoji_img = EmojiUtils.get_local_emoji_svg_image(char, size=font.size)
                if emoji_img:
                    emoji_y = baseline - emoji_img.height
                    img.paste(emoji_img, (x, emoji_y), emoji_img)
                    x += emoji_img.width
                else:
                    # Fallback: Render emoji using font (some fonts support emoji)
                    # This ensures emoji is displayed even without SVG files
                    draw.text((x, y), char, font=font, fill=(255, 255, 255))
                    char_width = TextUtils.get_char_size(font, char)[0]
                    x += char_width
            else:
                draw.text((x, y), char, font=font, fill=(255, 255, 255))
                char_width = TextUtils.get_char_size(font, char)[0]
                x += char_width
        
        line_image_cache[cache_key] = img
        return line_image_cache[cache_key]

    @staticmethod
    def clean_line_image_cache():
        """Clear line image cache"""
        global line_image_cache
        line_image_cache = {}

    @staticmethod
    def wrap_text(draw, text, font, max_width):
        """Wrap text to fit width"""
        lines = []
        current_line = ""
        current_width = 0
        
        for char in text:
            test_line = current_line + char
            char_width = TextUtils.get_char_size(font, char)[0]
            current_width += char_width
            
            if current_width <= max_width:
                current_line = test_line
            else:
                lines.append(current_line)
                current_line = char
                current_width = char_width
        
        if current_line:
            lines.append(current_line)
        return lines


def render_whisplay_frame(display, status, emoji, text, scroll_top, battery_level, battery_color, image_path, font_path):
    """Render frame for Whisplay HAT (optimized with font caching)"""
    global _font_cache
    
    # Cache fonts to avoid reloading on every render
    status_font_size = 24
    emoji_font_size = 40
    battery_font_size = 13
    main_text_font_size = 20
    
    font_key_base = (font_path,)
    
    # Get or create fonts from cache
    status_font_key = font_key_base + (status_font_size,)
    emoji_font_key = font_key_base + (emoji_font_size,)
    battery_font_key = font_key_base + (battery_font_size,)
    main_text_font_key = font_key_base + (main_text_font_size,)
    
    if status_font_key not in _font_cache:
        try:
            _font_cache[status_font_key] = ImageFont.truetype(font_path, status_font_size)
        except:
            _font_cache[status_font_key] = ImageFont.load_default()
    
    if emoji_font_key not in _font_cache:
        try:
            _font_cache[emoji_font_key] = ImageFont.truetype(font_path, emoji_font_size)
        except:
            _font_cache[emoji_font_key] = ImageFont.load_default()
    
    if battery_font_key not in _font_cache:
        try:
            _font_cache[battery_font_key] = ImageFont.truetype(font_path, battery_font_size)
        except:
            _font_cache[battery_font_key] = ImageFont.load_default()
    
    if main_text_font_key not in _font_cache:
        try:
            _font_cache[main_text_font_key] = ImageFont.truetype(font_path, main_text_font_size)
        except:
            _font_cache[main_text_font_key] = ImageFont.load_default()
    
    status_font = _font_cache[status_font_key]
    emoji_font = _font_cache[emoji_font_key]
    battery_font = _font_cache[battery_font_key]
    main_text_font = _font_cache[main_text_font_key]
    
    header_height = 88 + 10
    image = Image.new("RGBA", (display.LCD_WIDTH, header_height), (0, 0, 0, 255))
    draw = ImageDraw.Draw(image)
    
    # Render header
    TextUtils.draw_mixed_text(draw, image, status, status_font, (display.CornerHeight, 0))
    
    emoji_bbox = emoji_font.getbbox(emoji)
    emoji_w = emoji_bbox[2] - emoji_bbox[0]
    TextUtils.draw_mixed_text(draw, image, emoji, emoji_font, 
                             ((display.LCD_WIDTH - emoji_w) // 2, status_font_size + 8))
    
    # Render battery
    if battery_level is not None:
        render_battery(draw, battery_font, battery_level, battery_color, 
                      display.LCD_WIDTH, status_font_size)
    
    rgb565_data = ImageUtils.image_to_rgb565(image, display.LCD_WIDTH, header_height)
    display.draw_image(0, 0, display.LCD_WIDTH, header_height, rgb565_data)
    
    # Render main text area
    text_area_height = display.LCD_HEIGHT - header_height
    text_bg_image = Image.new("RGBA", (display.LCD_WIDTH, text_area_height), (0, 0, 0, 255))
    text_draw = ImageDraw.Draw(text_bg_image)
    
    if text:
        # Word-by-word display: show text on single line, wrap when too long
        line_height = main_text_font.getmetrics()[0] + main_text_font.getmetrics()[1]
        max_width = display.LCD_WIDTH - 20
        
        # Measure text width
        text_width = 0
        for char in text:
            text_width += TextUtils.get_char_size(main_text_font, char)[0]
        
        # If text fits on screen, display it
        if text_width <= max_width:
            TextUtils.draw_mixed_text(text_draw, text_bg_image, text, main_text_font, (10, 0))
        else:
            # Text is too long, need to wrap or scroll
            # For now, just display what fits and let word-by-word logic handle scrolling
            # Calculate how many characters fit
            visible_text = ""
            current_width = 0
            for char in text:
                char_width = TextUtils.get_char_size(main_text_font, char)[0]
                if current_width + char_width <= max_width:
                    visible_text += char
                    current_width += char_width
                else:
                    break
            
            # If we have visible text, show it
            if visible_text:
                TextUtils.draw_mixed_text(text_draw, text_bg_image, visible_text, main_text_font, (10, 0))
            else:
                # Fallback: show first character
                if text:
                    TextUtils.draw_mixed_text(text_draw, text_bg_image, text[0], main_text_font, (10, 0))
    
    rgb565_text = ImageUtils.image_to_rgb565(text_bg_image, display.LCD_WIDTH, text_area_height)
    display.draw_image(0, header_height, display.LCD_WIDTH, text_area_height, rgb565_text)


def render_battery(draw, battery_font, battery_level, battery_color, image_width, status_font_size):
    """Render battery indicator for Whisplay HAT"""
    battery_width = 26
    battery_height = 15
    battery_margin_right = 20
    battery_x = image_width - battery_width - battery_margin_right
    battery_y = status_font_size // 2
    corner_radius = 3
    fill_color = "black"
    
    if battery_color is not None:
        if isinstance(battery_color, tuple):
            fill_color = battery_color
        else:
            fill_color = ColorUtils.get_rgb255_from_any(battery_color) or "black"
    
    outline_color = "white"
    line_width = 2
    
    # Draw battery outline with rounded corners
    draw.arc((battery_x, battery_y, battery_x + 2 * corner_radius, battery_y + 2 * corner_radius), 
             180, 270, fill=outline_color, width=line_width)
    draw.arc((battery_x + battery_width - 2 * corner_radius, battery_y, 
              battery_x + battery_width, battery_y + 2 * corner_radius), 
             270, 0, fill=outline_color, width=line_width)
    draw.arc((battery_x, battery_y + battery_height - 2 * corner_radius, 
              battery_x + 2 * corner_radius, battery_y + battery_height), 
             90, 180, fill=outline_color, width=line_width)
    draw.arc((battery_x + battery_width - 2 * corner_radius, battery_y + battery_height - 2 * corner_radius, 
              battery_x + battery_width, battery_y + battery_height), 
             0, 90, fill=outline_color, width=line_width)
    
    # Draw lines
    draw.line([(battery_x + corner_radius, battery_y), 
               (battery_x + battery_width - corner_radius, battery_y)], 
              fill=outline_color, width=line_width)
    draw.line([(battery_x + corner_radius, battery_y + battery_height), 
               (battery_x + battery_width - corner_radius, battery_y + battery_height)], 
              fill=outline_color, width=line_width)
    draw.line([(battery_x, battery_y + corner_radius), 
               (battery_x, battery_y + battery_height - corner_radius)], 
              fill=outline_color, width=line_width)
    draw.line([(battery_x + battery_width, battery_y + corner_radius), 
               (battery_x + battery_width, battery_y + battery_height - corner_radius)], 
              fill=outline_color, width=line_width)
    
    # Fill battery
    if fill_color != (0, 0, 0):
        draw.rectangle([battery_x + line_width // 2, battery_y + line_width // 2, 
                        battery_x + battery_width - line_width // 2, 
                        battery_y + battery_height - line_width // 2], 
                       fill=fill_color)
    
    # Battery head
    head_width = 2
    head_height = 5
    head_x = battery_x + battery_width
    head_y = battery_y + (battery_height - head_height) // 2
    draw.rectangle([head_x, head_y, head_x + head_width, head_y + head_height], fill="white")
    
    # Battery level text
    battery_text = str(battery_level)
    text_bbox = battery_font.getbbox(battery_text)
    text_y = battery_y + (battery_height - (battery_font.getmetrics()[0] + battery_font.getmetrics()[1])) // 2
    text_w = text_bbox[2] - text_bbox[0]
    text_x = battery_x + (battery_width - text_w) // 2
    
    if isinstance(fill_color, tuple):
        luminance = ColorUtils.calculate_luminance(fill_color)
        text_fill_color = "black" if luminance > 128 else "white"
    else:
        text_fill_color = "white"
    
    draw.text((text_x, text_y), battery_text, font=battery_font, fill=text_fill_color)

