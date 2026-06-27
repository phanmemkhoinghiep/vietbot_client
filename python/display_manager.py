"""
Display manager for Whisplay HAT (1.69" LCD 240x280)
Based on whisplay-ai-chatbot implementation
Uses RenderThread for continuous rendering
"""

import logging
import threading
import time
import asyncio
import aiohttp
from typing import Optional
from PIL import Image, ImageDraw, ImageFont
import os
from datetime import datetime

logger = logging.getLogger(__name__)

# Global variables for display state (following whisplay-ai-chatbot pattern)
current_status = "Sẵn sàng"
current_emoji = "✅"
current_text = "Chờ wakeup"
current_battery_level = 100
current_battery_color = (85, 255, 0)  # #55FF00
current_scroll_top = 0
current_scroll_speed = 1  # Very slow scroll speed for better readability (1 pixel per frame = ~30 pixels/second)
current_image_path = ""
current_image = None
_rendering_enabled = True
# Word-by-word display state
current_words = []  # List of words to display
current_word_index = 0  # Current word position
current_word_display_text = ""  # Text to display (accumulated words)
# Transcript history for scrolling up effect
transcript_history = []  # List of dicts: [{"type": "bot"|"user", "text": "...", "lines": [...]}, ...]
MAX_TRANSCRIPT_ENTRIES = 50  # Maximum number of transcript entries to keep

# Font sizes
battery_font_size = 12  # Only battery_font_size is used, others are hardcoded in render methods

# Import utils
try:
    from display_utils import TextUtils, ImageUtils
except ImportError:
    logger.error("display_utils not available")
    TextUtils = None
    ImageUtils = None


class RenderThread(threading.Thread):
    """Render thread for continuous display updates (following whisplay-ai-chatbot pattern)"""
    
    def __init__(self, whisplay, font_path, fps=20, transcript_font_path=None):
        super().__init__()
        self.whisplay = whisplay
        self.font_path = font_path
        self.transcript_font_path = transcript_font_path  # Custom font for transcripts
        self.fps = fps
        self.running = False
        self.main_text_font = None
        self.main_text_line_height = 0
        self.text_cache_image = None
        self.current_render_text = ""
        self.daemon = True  # Thread will exit when main program exits
        
    def start_render(self):
        """Start rendering thread"""
        if not self.running:
            self.running = True
            # Load fonts
            try:
                self.main_text_font = ImageFont.truetype(self.font_path, 20)
                self.main_text_line_height = self.main_text_font.getmetrics()[0] + self.main_text_font.getmetrics()[1]
            except Exception as e:
                logger.error(f"Failed to load font: {e}")
                self.main_text_font = ImageFont.load_default()
                self.main_text_line_height = 20
            self.start()
    
    def stop_render(self):
        """Stop rendering thread"""
        self.running = False
    
    def run(self):
        """Main render loop"""
        frame_interval = 1.0 / self.fps
        # Slower word update interval for better readability (especially for bot transcript)
        # Increased interval to allow text to be fully displayed before next update
        word_update_interval = 1.2  # Update word every 1200ms (increased from 600ms for better readability)
        last_word_update = time.time()
        
        last_rendered_content = None  # Track last rendered content to avoid unnecessary renders
        
        while self.running:
            try:
                if _rendering_enabled:
                    # Update word-by-word display (skip for transcripts - they show full text immediately)
                    global current_words, current_word_index, current_word_display_text, current_text
                    
                    # Check if current text is a transcript (user or bot)
                    is_transcript = current_text.startswith("Bạn:") or current_text.startswith("Bot:")
                    
                    # Only use word-by-word for non-transcript text that hasn't been fully displayed
                    if not is_transcript and current_words and current_word_index < len(current_words) and time.time() - last_word_update >= word_update_interval:
                        # Add next word
                        if current_word_display_text:
                            current_word_display_text += " " + current_words[current_word_index]
                        else:
                            current_word_display_text = current_words[current_word_index]
                        current_word_index += 1
                        last_word_update = time.time()
                    
                    # PERFORMANCE: Check if content has changed before rendering
                    # Build content hash to detect changes
                    global transcript_history
                    has_transcript_history = transcript_history and len(transcript_history) > 0
                    
                    if has_transcript_history:
                        # For transcript history, use history length and scroll position as change indicator
                        current_content = (current_status, current_emoji, len(transcript_history), current_scroll_top, current_battery_level)
                    else:
                        # Use word display text if available and not a transcript, otherwise use full text
                        if is_transcript or not current_word_display_text or current_word_index >= len(current_words):
                            display_text = current_text
                        else:
                            display_text = current_word_display_text
                        current_content = (current_status, current_emoji, display_text, current_scroll_top, current_battery_level)
                    
                    # Only render if content has changed
                    if current_content != last_rendered_content:
                        last_rendered_content = current_content
                        if has_transcript_history:
                            # Render transcript history (scrolling up effect)
                            self.render_frame(
                                current_status, 
                                current_emoji, 
                                None,  # text=None means render transcript history
                                current_scroll_top, 
                                current_battery_level, 
                                current_battery_color
                            )
                        else:
                            self.render_frame(
                                current_status, 
                                current_emoji, 
                                display_text, 
                                current_scroll_top, 
                                current_battery_level, 
                                current_battery_color
                            )
                time.sleep(frame_interval)
            except Exception as e:
                logger.error(f"Error in render loop: {e}", exc_info=True)
                time.sleep(frame_interval)
    
    def render_frame(self, status, emoji, text, scroll_top, battery_level, battery_color):
        """Render a single frame (following whisplay-ai-chatbot pattern)"""
        global current_image_path, current_image
        
        if current_image_path not in [None, ""]:
            # Render image if available
            if current_image is not None:
                rgb565_data = ImageUtils.image_to_rgb565(current_image, self.whisplay.LCD_WIDTH, self.whisplay.LCD_HEIGHT)
                self.whisplay.draw_image(0, 0, self.whisplay.LCD_WIDTH, self.whisplay.LCD_HEIGHT, rgb565_data)
            elif os.path.exists(current_image_path):
                try:
                    image = Image.open(current_image_path).convert("RGBA")
                    img_w, img_h = image.size
                    screen_ratio = self.whisplay.LCD_WIDTH / self.whisplay.LCD_HEIGHT
                    img_ratio = img_w / img_h
                    if img_ratio > screen_ratio:
                        new_w = int(img_h * screen_ratio)
                        left = (img_w - new_w) // 2
                        image = image.crop((left, 0, left + new_w, img_h))
                    else:
                        new_h = int(img_w / screen_ratio)
                        top = (img_h - new_h) // 2
                        image = image.crop((0, top, img_w, top + new_h))
                    image = image.resize((self.whisplay.LCD_WIDTH, self.whisplay.LCD_HEIGHT), Image.LANCZOS)
                    current_image = image
                    rgb565_data = ImageUtils.image_to_rgb565(image, self.whisplay.LCD_WIDTH, self.whisplay.LCD_HEIGHT)
                    self.whisplay.draw_image(0, 0, self.whisplay.LCD_WIDTH, self.whisplay.LCD_HEIGHT, rgb565_data)
                except Exception as e:
                    logger.error(f"Failed to load image {current_image_path}: {e}")
        else:
            current_image = None
            header_height = 25  # Compact header for Wifi, time, battery
            
            # Check if this is "waiting for connection" state (show special layout)
            is_waiting = (text == "Chờ đánh thức" or text == "Chờ kết nối" or 
                         (status and ("Sẵn sàng" in status or "Chờ" in status)))
            
            # Render header
            image = Image.new("RGBA", (self.whisplay.LCD_WIDTH, header_height), (0, 0, 0, 255))
            draw = ImageDraw.Draw(image)
            self.render_header(image, draw, status, emoji, battery_level, battery_color)
            self.whisplay.draw_image(0, 0, self.whisplay.LCD_WIDTH, header_height, 
                                  ImageUtils.image_to_rgb565(image, self.whisplay.LCD_WIDTH, header_height))
            
            # Render main content area
            content_height = self.whisplay.LCD_HEIGHT - header_height
            content_image = Image.new("RGBA", (self.whisplay.LCD_WIDTH, content_height), (0, 0, 0, 255))
            content_draw = ImageDraw.Draw(content_image)
            
            # Draw emoji in middle row (between header and "Vietbot" text or main content)
            # Emoji should be centered horizontally, positioned in the middle area
            emoji_offset = 0
            if emoji and TextUtils:
                # Use separate font for emoji (Symbola has better emoji support)
                # while keeping DejaVuSans for main text
                emoji_font = None
                emoji_font_paths = [
                    # Symbola font - has good emoji support (monochrome)
                    "/usr/share/fonts/truetype/symbola/Symbola.ttf",
                    "/usr/share/fonts/truetype/Symbola/Symbola.ttf",
                    "/usr/share/fonts/opentype/symbola/Symbola.otf",
                    # Noto Emoji fonts (black and white emoji)
                    "/usr/share/fonts/truetype/noto/NotoEmoji-Regular.ttf",
                    "/usr/share/fonts/noto/NotoEmoji-Regular.ttf",
                    # Fallback to DejaVu for emoji (limited support)
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    # Last resort - configured font
                    self.font_path,
                ]
                for font_path in emoji_font_paths:
                    try:
                        if os.path.exists(font_path):
                            emoji_font = ImageFont.truetype(font_path, 24)
                            # Test if font can render emoji (check bbox size)
                            test_bbox = emoji_font.getbbox(emoji)
                            test_w = test_bbox[2] - test_bbox[0]
                            test_h = test_bbox[3] - test_bbox[1]
                            # If font returns reasonable size for emoji (at least 10px), use it
                            if test_w >= 10 and test_h >= 10:
                                logger.debug(f"Using emoji font: {font_path}")
                                break
                    except:
                        continue

                # Fallback to default font if no good font found
                if emoji_font is None:
                    emoji_font = ImageFont.load_default()
                    logger.debug("Using default font for emoji")
                
                emoji_bbox = emoji_font.getbbox(emoji)
                emoji_w = emoji_bbox[2] - emoji_bbox[0]
                emoji_h = emoji_bbox[3] - emoji_bbox[1]
                # Center emoji horizontally
                emoji_x = (self.whisplay.LCD_WIDTH - emoji_w) // 2
                # Position emoji in middle row (between header and main content)
                # Header is 25px, so emoji at y=5px in content area (which starts at y=25)
                emoji_y = 5  # Top of content area, between header and main content
                TextUtils.draw_mixed_text(content_draw, content_image, emoji, emoji_font, (emoji_x, emoji_y))
                emoji_offset = emoji_h + 10  # Emoji height + margin
            
            if is_waiting:
                # Special layout for waiting state: "Vietbot" large in center, actual text below
                # Adjust for emoji space (emoji is ~24px + 5px margin = ~30px)
                self.render_waiting_layout(content_image, content_draw, content_height, text, emoji_offset=emoji_offset)
            elif text is None:
                # Render transcript history (scrolling up effect)
                self.render_transcript_history(content_image, content_height, content_draw, current_scroll_speed, emoji_offset=emoji_offset)
            else:
                # Normal layout: show text with scrolling
                # Adjust text area to account for emoji space
                self.render_main_text(content_image, content_height, content_draw, text, current_scroll_speed, emoji_offset=emoji_offset)
            
            self.whisplay.draw_image(0, header_height, self.whisplay.LCD_WIDTH, content_height, 
                                   ImageUtils.image_to_rgb565(content_image, self.whisplay.LCD_WIDTH, content_height))
    
    def render_waiting_layout(self, image, draw, area_height, subtitle_text=None, emoji_offset=0):
        """Render waiting state: Large 'AI-BOX.VN' text in center
        emoji_offset: vertical offset to account for emoji space at top
        """
        try:
            # Large font for "AI-BOX.VN" - approximately 2/3 of screen width
            target_width = int(self.whisplay.LCD_WIDTH * 2 / 3)
            # Estimate font size: approximate 10 characters for "AI-BOX.VN", so each char ~ target_width/10
            brand_font_size = max(18, int(target_width / 10))
            brand_font = ImageFont.truetype(self.font_path, brand_font_size)
        except:
            brand_font = ImageFont.load_default()

        image_width = self.whisplay.LCD_WIDTH

        # Draw "AI-BOX.VN" - centered, large
        # Adjust for emoji space at top
        brand_text = "AI-BOX.VN"
        brand_bbox = brand_font.getbbox(brand_text)
        brand_w = brand_bbox[2] - brand_bbox[0]
        brand_h = brand_bbox[3] - brand_bbox[1]
        brand_x = (image_width - brand_w) // 2
        # Position below emoji (emoji_offset accounts for emoji space)
        available_height = area_height - emoji_offset
        brand_y = emoji_offset + (available_height - brand_h) // 2  # Center of available space
        # Cyan color for "AI-BOX.VN" text
        cyan_color = (0, 200, 200)
        draw.text((brand_x, brand_y), brand_text, font=brand_font, fill=cyan_color)

        # No subtitle text - dynamic status will be shown instead
    
    def render_main_text(self, main_text_image, area_height, draw, text, scroll_speed=2, emoji_offset=0):
        """Render main text content with scrolling
        emoji_offset: vertical offset to account for emoji space at top
        """
        global current_scroll_top
        
        if not text or not TextUtils:
            return
        
        # Strip prefix "Bot:" or "Bạn:" if present (for display, we use color/highlight to differentiate)
        display_text = text
        is_bot_transcript = text.startswith("Bot:")
        is_user_transcript = text.startswith("Bạn:")
        is_transcript = is_bot_transcript or is_user_transcript
        
        # Remove prefix for display
        if is_bot_transcript:
            display_text = text[4:].strip()  # Remove "Bot:"
        elif is_user_transcript:
            display_text = text[4:].strip()  # Remove "Bạn:"
        
        # Use cleaner, rounder font for transcripts (non-bold for better readability)
        if is_transcript:
            try:
                # Try custom font path from config first
                transcript_font_loaded = False
                if self.transcript_font_path and os.path.exists(self.transcript_font_path):
                    font = ImageFont.truetype(self.transcript_font_path, 18)
                    line_height = font.getmetrics()[0] + font.getmetrics()[1]
                    transcript_font_loaded = True
                    logger.debug(f"✅ Loaded custom transcript font: {self.transcript_font_path}")
                
                # Fallback to DejaVu Sans Regular if custom font not specified or not found
                if not transcript_font_loaded:
                    dejavu_regular_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
                    if os.path.exists(dejavu_regular_path):
                        font = ImageFont.truetype(dejavu_regular_path, 18)  # Slightly smaller for cleaner look
                        line_height = font.getmetrics()[0] + font.getmetrics()[1]
                    else:
                        font = self.main_text_font
                        line_height = self.main_text_line_height
            except Exception as e:
                logger.debug(f"Could not load transcript font: {e}, using default")
                font = self.main_text_font
                line_height = self.main_text_line_height
        else:
            font = self.main_text_font
            line_height = self.main_text_line_height
        
        # Use full width minus margins (10px each side = 20px total for transcripts, 10px for others)
        text_width = self.whisplay.LCD_WIDTH - 20 if is_transcript else self.whisplay.LCD_WIDTH - 10
        lines = TextUtils.wrap_text(draw, display_text, font, text_width)
        
        # Check if text is short and should be centered (like "Đang nghe")
        is_short_text = len(lines) == 1 and len(display_text) < 30
        is_listening_text = (display_text == "Đang nghe" or display_text == "Đang nghe..." or display_text.startswith("Đang nghe")) and not is_transcript
        
        # Calculate total text height
        total_text_height = len(lines) * line_height
        
        # Adjust area_height to account for emoji space
        available_height = area_height - emoji_offset
        
        # Only scroll if text is longer than visible area (accounting for emoji space)
        should_scroll = total_text_height > available_height
        
        # Calculate visible lines (show as many as possible)
        display_lines = []
        
        # For transcript display, try to show as many lines as possible
        max_visible_lines = available_height // line_height
        
        for i, line in enumerate(lines):
            line_y = i * line_height
            # Check if line is visible in current viewport (accounting for emoji space)
            if line_y + line_height >= current_scroll_top and line_y - current_scroll_top <= available_height:
                display_lines.append(line)
        
        # If text fits in area, show all lines without scrolling
        if not should_scroll:
            display_lines = lines[:max_visible_lines] if len(lines) > max_visible_lines else lines
            current_scroll_top = 0  # Reset scroll if text fits
        
        # Render text - Always render for transcripts to ensure they're displayed
        render_text = ""
        for line in display_lines:
            render_text += line
        
        # For transcripts, always update cache to ensure they're rendered
        # For other text, only update if content changed
        should_update_cache = (self.current_render_text != render_text) or is_transcript
        
        if should_update_cache:
            self.current_render_text = render_text
            # Create image for all visible lines
            visible_height = len(display_lines) * line_height
            show_text_image = Image.new("RGBA", (self.whisplay.LCD_WIDTH, visible_height), (0, 0, 0, 255))
            show_text_draw = ImageDraw.Draw(show_text_image)
            y_pos = 0
            # Colors for transcripts:
            # Bot transcript: light blue highlight with dark blue/navy text
            # User transcript: light green highlight with dark green text
            
            for line in display_lines:
                line_bbox = font.getbbox(line)
                line_w = line_bbox[2] - line_bbox[0]
                
                if is_bot_transcript:
                    # Bot transcript: left-align with 10px margin, light blue highlight, dark blue text
                    line_x = 10  # Left margin 10px
                    bot_highlight = (173, 216, 230)  # Light blue (lightblue)
                    bot_text_color = (0, 0, 139)  # Dark blue (navy) for good contrast
                    # Draw highlight rectangle
                    padding = 4  # Padding around text
                    show_text_draw.rectangle(
                        [line_x - padding, y_pos, line_x + line_w + padding, y_pos + line_height],
                        fill=bot_highlight
                    )
                    # Draw dark blue text on light blue highlight
                    show_text_draw.text((line_x, y_pos), line, font=font, fill=bot_text_color)
                elif is_user_transcript:
                    # User transcript: right-align with 10px margin, light green highlight, dark green text
                    line_x = self.whisplay.LCD_WIDTH - line_w - 10  # Right margin 10px
                    user_highlight = (144, 238, 144)  # Light green (lightgreen)
                    user_text_color = (0, 100, 0)  # Dark green for good contrast
                    # Draw highlight rectangle
                    padding = 4  # Padding around text
                    show_text_draw.rectangle(
                        [line_x - padding, y_pos, line_x + line_w + padding, y_pos + line_height],
                        fill=user_highlight
                    )
                    # Draw dark green text on light green highlight
                    show_text_draw.text((line_x, y_pos), line, font=font, fill=user_text_color)
                elif is_short_text and is_listening_text:
                    # Center the text horizontally (for "Đang nghe")
                    line_x = (self.whisplay.LCD_WIDTH - line_w) // 2
                    TextUtils.draw_mixed_text(show_text_draw, show_text_image, line, font, (line_x, y_pos))
                else:
                    # Left-align with 5px margin (for other text)
                    TextUtils.draw_mixed_text(show_text_draw, show_text_image, line, font, (5, y_pos))
                y_pos += line_height
            self.text_cache_image = show_text_image
        
        if self.text_cache_image:
            # Paste text at correct scroll position
            # Adjust for emoji space at top
            if should_scroll:
                main_text_image.paste(self.text_cache_image, (0, emoji_offset - current_scroll_top), self.text_cache_image)
            else:
                # Center text vertically if it fits (accounting for emoji space)
                y_offset = emoji_offset + (available_height - len(display_lines) * line_height) // 2
                main_text_image.paste(self.text_cache_image, (0, y_offset), self.text_cache_image)
        
        # Update scroll position only if text is longer than area (accounting for emoji space)
        if should_scroll and scroll_speed > 0 and current_scroll_top < total_text_height - available_height:
            current_scroll_top += scroll_speed
        elif current_scroll_top >= total_text_height - available_height:
            # Reset scroll when reaching end (loop back)
            current_scroll_top = 0
    
    def render_transcript_history(self, main_text_image, area_height, draw, scroll_speed=2, emoji_offset=0):
        """Render transcript history with scrolling up effect
        Transcripts are displayed from top to bottom, newest at bottom
        Old transcripts scroll up when new ones arrive
        """
        global transcript_history, current_scroll_top
        
        if not transcript_history or not TextUtils:
            return
        
        # Use a cleaner, rounder font for transcripts (non-bold for better readability)
        try:
            # Try custom font path from config first (stored in RenderThread)
            transcript_font_loaded = False
            if self.transcript_font_path and os.path.exists(self.transcript_font_path):
                font = ImageFont.truetype(self.transcript_font_path, 18)
                transcript_font_loaded = True
                logger.debug(f"✅ Loaded custom transcript font: {self.transcript_font_path}")
            
            # Fallback to DejaVu Sans Regular if custom font not specified or not found
            if not transcript_font_loaded:
                dejavu_regular_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
                if os.path.exists(dejavu_regular_path):
                    font = ImageFont.truetype(dejavu_regular_path, 18)
                else:
                    font = self.main_text_font  # Use default if nothing else available
        except Exception as e:
            logger.debug(f"Could not load transcript font: {e}, using default")
            font = self.main_text_font
        
        line_height = font.getmetrics()[0] + font.getmetrics()[1]
        available_height = area_height - emoji_offset
        text_width = self.whisplay.LCD_WIDTH - 20  # 10px margin each side
        
        # Colors
        bot_highlight = (173, 216, 230)  # Light blue
        bot_text_color = (0, 0, 139)  # Dark blue (navy)
        user_highlight = (144, 238, 144)  # Light green
        user_text_color = (0, 100, 0)  # Dark green
        padding = 4  # Padding around text
        
        # Calculate total height needed for all transcripts
        total_height = 0
        transcript_lines = []  # List of (type, lines) tuples
        
        for entry in transcript_history:
            entry_text = entry["text"]
            entry_type = entry["type"]
            # Wrap text to fit width
            lines = TextUtils.wrap_text(draw, entry_text, font, text_width)
            transcript_lines.append((entry_type, lines))
            total_height += len(lines) * line_height + 2  # +2 for spacing between entries
        
        # Determine scroll position - scroll from bottom (newest at bottom)
        max_scroll = max(0, total_height - available_height)
        
        # Auto-scroll to show newest (bottom) content when new transcript arrives
        if total_height > available_height:
            # Keep scroll at bottom to show newest content
            current_scroll_top = max_scroll
        else:
            current_scroll_top = 0
        
        # Render transcripts from top to bottom
        y_pos = emoji_offset - current_scroll_top
        
        for entry_type, lines in transcript_lines:
            for line in lines:
                line_bbox = font.getbbox(line)
                line_w = line_bbox[2] - line_bbox[0]
                
                # Check if line is visible
                if y_pos + line_height < emoji_offset:
                    y_pos += line_height
                    continue  # Above visible area
                if y_pos > area_height:
                    break  # Below visible area
                
                if entry_type == "bot":
                    # Bot transcript: left-align with 10px margin
                    line_x = 10
                    # Draw highlight rectangle
                    draw.rectangle(
                        [line_x - padding, y_pos, line_x + line_w + padding, y_pos + line_height],
                        fill=bot_highlight
                    )
                    # Draw text
                    draw.text((line_x, y_pos), line, font=font, fill=bot_text_color)
                else:  # user
                    # User transcript: right-align with 10px margin
                    line_x = self.whisplay.LCD_WIDTH - line_w - 10
                    # Draw highlight rectangle
                    draw.rectangle(
                        [line_x - padding, y_pos, line_x + line_w + padding, y_pos + line_height],
                        fill=user_highlight
                    )
                    # Draw text
                    draw.text((line_x, y_pos), line, font=font, fill=user_text_color)
                
                y_pos += line_height
            
            y_pos += 2  # Spacing between transcript entries
    
    def render_header(self, image, draw, status, emoji, battery_level, battery_color):
        """Render header: Time (top-left), Wifi icon (top-center), Battery % (top-right), Emoji (middle-center)"""
        try:
            time_font = ImageFont.truetype(self.font_path, 18)  # Font for time
            emoji_font = ImageFont.truetype(self.font_path, 24)  # Font for emoji
            battery_font = ImageFont.truetype(self.font_path, battery_font_size)
        except:
            time_font = ImageFont.load_default()
            emoji_font = ImageFont.load_default()
            battery_font = ImageFont.load_default()
        
        image_width = self.whisplay.LCD_WIDTH
        header_height = image.size[1]
        
        # 1. Draw time (top-left corner, 5px margin from left edge)
        current_time = datetime.now().strftime("%H:%M")
        time_bbox = time_font.getbbox(current_time)
        time_w = time_bbox[2] - time_bbox[0]
        time_x = 5  # 5px margin from left edge
        time_y = 2  # Top margin
        draw.text((time_x, time_y), current_time, font=time_font, fill="white")
        
        # 2. Draw Wifi icon (top-center, larger size)
        wifi_x = image_width // 2  # Center horizontally
        wifi_y = header_height // 2  # Center vertically in header (top row)
        for i in range(3):
            radius = 6 + i * 3  # Increased from 4 + i * 2 to make it larger
            draw.arc([wifi_x - radius, wifi_y - radius, wifi_x + radius, wifi_y + radius], 
                    start=225, end=315, fill="white", width=2)  # Increased line width from 1 to 2
        
        # 3. Draw battery (top-right corner)
        if battery_level is not None:
            self.render_battery(draw, image, battery_font, battery_level, battery_color, image_width, header_height)
        
        # Note: Emoji will be rendered in content area (not in header) to avoid overlap
        # Emoji rendering is handled in render_frame method
    
    def render_battery(self, draw, image, battery_font, battery_level, battery_color, image_width, header_height):
        """Render battery icon with percentage (top-right corner, moved 10px to the left)"""
        # Use the battery_level passed in (from global variable, updated by DisplayManager's polling thread)
        # No need to access DisplayManager's cache here - battery_level is already provided
        
        # Battery icon parameters (restored original size)
        battery_width = 26
        battery_height = 15
        battery_margin_right = 5  # 5px margin from right edge
        battery_x = image_width - battery_width - battery_margin_right
        battery_y = header_height // 2 - battery_height // 2  # Center vertically in header
        corner_radius = 3
        fill_color = "black"
        if battery_color:
            fill_color = battery_color
        outline_color = "white"
        line_width = 2
        
        # Draw rounded rectangle
        draw.arc((battery_x, battery_y, battery_x + 2 * corner_radius, battery_y + 2 * corner_radius), 180, 270, fill=outline_color, width=line_width)
        draw.arc((battery_x + battery_width - 2 * corner_radius, battery_y, battery_x + battery_width, battery_y + 2 * corner_radius), 270, 0, fill=outline_color, width=line_width)
        draw.arc((battery_x, battery_y + battery_height - 2 * corner_radius, battery_x + 2 * corner_radius, battery_y + battery_height), 90, 180, fill=outline_color, width=line_width)
        draw.arc((battery_x + battery_width - 2 * corner_radius, battery_y + battery_height - 2 * corner_radius, battery_x + battery_width, battery_y + battery_height), 0, 90, fill=outline_color, width=line_width)
        
        draw.line([(battery_x + corner_radius, battery_y), (battery_x + battery_width - corner_radius, battery_y)], fill=outline_color, width=line_width)
        draw.line([(battery_x + corner_radius, battery_y + battery_height), (battery_x + battery_width - corner_radius, battery_y + battery_height)], fill=outline_color, width=line_width)
        draw.line([(battery_x, battery_y + corner_radius), (battery_x, battery_y + battery_height - corner_radius)], fill=outline_color, width=line_width)
        draw.line([(battery_x + battery_width, battery_y + corner_radius), (battery_x + battery_width, battery_y + battery_height - corner_radius)], fill=outline_color, width=line_width)
        
        if fill_color != (0, 0, 0):
            draw.rectangle([battery_x + line_width // 2, battery_y + line_width // 2, 
                          battery_x + battery_width - line_width // 2, battery_y + battery_height - line_width // 2], fill=fill_color)
        
        # Battery head
        head_width = 2
        head_height = 5
        head_x = battery_x + battery_width
        head_y = battery_y + (battery_height - head_height) // 2
        draw.rectangle([head_x, head_y, head_x + head_width, head_y + head_height], fill="white")
        
        # Battery level text (percentage) - positioned to the left of battery icon
        battery_text = f"{battery_level}%"
        text_bbox = battery_font.getbbox(battery_text)
        text_w = text_bbox[2] - text_bbox[0]
        text_h = text_bbox[3] - text_bbox[1]
        text_x = battery_x - text_w - 5  # 5px gap between text and battery icon
        # Ensure text doesn't go too close to left edge (minimum 5px margin)
        if text_x < 5:
            text_x = 5
        text_y = battery_y + (battery_height - text_h) // 2  # Center vertically with battery icon
        draw.text((text_x, text_y), battery_text, font=battery_font, fill="white")
        
        # Charging indicator (⚡) - if charging, show above battery icon
        # Note: is_charging info is not available in RenderThread, so we skip charging indicator
        # (It would require passing it from DisplayManager or using a global variable)
        if False and TextUtils:  # Disabled for now - would need is_charging parameter
            try:
                emoji_font = ImageFont.truetype(self.font_path, 12)
            except:
                emoji_font = ImageFont.load_default()
            TextUtils.draw_mixed_text(draw, image, "⚡", emoji_font, (battery_x + battery_width // 2 - 6, battery_y - 12))
    
    async def _get_pisugar_token_async(self):
        """Get authentication token from PiSugar HTTP API (async)"""
        try:
            url = f"http://{self.pisugar_host}:{self.pisugar_port}/login"
            params = {
                'username': self.pisugar_username,
                'password': self.pisugar_password
            }
            
            timeout = aiohttp.ClientTimeout(total=2.0)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(url, params=params) as response:
                    if response.status == 200:
                        token = (await response.text()).strip()
                        if token:
                            return token
        except Exception as e:
            if logger.isEnabledFor(logging.DEBUG):
                logger.debug(f"PiSugar login error: {e}")
        return None
    
    def _get_pisugar_token(self):
        """Get authentication token from PiSugar HTTP API (sync wrapper)"""
        try:
            return asyncio.run(self._get_pisugar_token_async())
        except Exception as e:
            if logger.isEnabledFor(logging.DEBUG):
                logger.debug(f"PiSugar token sync wrapper error: {e}")
        return None
    
    async def _pisugar_http_exec_async(self, command):
        """Execute PiSugar command via HTTP API (async)"""
        try:
            # Get token if not available
            if not self.pisugar_token:
                self.pisugar_token = await self._get_pisugar_token_async()
                if not self.pisugar_token:
                    return None
            
            url = f"http://{self.pisugar_host}:{self.pisugar_port}/exec"
            params = {'token': self.pisugar_token}
            data = command.encode('utf-8')
            
            timeout = aiohttp.ClientTimeout(total=2.0)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(url, params=params, data=data, headers={'Content-Type': 'text/plain'}) as response:
                    if response.status == 200:
                        result = (await response.text()).strip()
                        return result
                    elif response.status == 401:
                        # Token expired, clear it
                        self.pisugar_token = None
                        return None
        except Exception as e:
            if logger.isEnabledFor(logging.DEBUG):
                logger.debug(f"PiSugar HTTP exec error: {e}")
        return None
    
    def _pisugar_http_exec(self, command):
        """Execute PiSugar command via HTTP API (sync wrapper)"""
        try:
            return asyncio.run(self._pisugar_http_exec_async(command))
        except Exception as e:
            if logger.isEnabledFor(logging.DEBUG):
                logger.debug(f"PiSugar HTTP exec sync wrapper error: {e}")
        return None
    
    async def _get_pisugar_battery_async(self):
        """Get battery level from PiSugar 3 module via pisugar-server (async)"""
        try:
            import socket
            
            # Try Unix socket first (faster, no auth needed)
            socket_path = "/tmp/pisugar-server.sock"
            if os.path.exists(socket_path):
                try:
                    # Use asyncio to handle socket I/O
                    loop = asyncio.get_event_loop()
                    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    sock.setblocking(False)
                    
                    try:
                        await asyncio.wait_for(
                            loop.sock_connect(sock, socket_path),
                            timeout=1.0
                        )
                        
                        # Send command
                        await loop.sock_sendall(sock, b"get battery\n")
                        response_bytes = await asyncio.wait_for(
                            loop.sock_recv(sock, 64),
                            timeout=1.0
                        )
                        response = response_bytes.decode('utf-8').strip()
                        
                        # Try to get charging status
                        is_charging = False
                        try:
                            await loop.sock_sendall(sock, b"get battery_charging\n")
                            charging_bytes = await asyncio.wait_for(
                                loop.sock_recv(sock, 64),
                                timeout=1.0
                            )
                            charging_response = charging_bytes.decode('utf-8').strip()
                            if charging_response.lower() in ['true', '1', 'yes', 'charging']:
                                is_charging = True
                        except:
                            pass
                        
                        sock.close()
                        
                        if response and response.isdigit():
                            level = int(response)
                            return level, is_charging
                    except asyncio.TimeoutError:
                        sock.close()
                    except Exception as e:
                        sock.close()
                        if logger.isEnabledFor(logging.DEBUG):
                            logger.debug(f"PiSugar socket error: {e}")
                except Exception as e:
                    if logger.isEnabledFor(logging.DEBUG):
                        logger.debug(f"PiSugar socket error: {e}")
            
            # Fallback: Try HTTP API (requires auth token, but more reliable)
            try:
                battery_response = await self._pisugar_http_exec_async("get battery")
                if battery_response and battery_response.isdigit():
                    level = int(battery_response)
                    # Try to get charging status via HTTP
                    is_charging = False
                    charging_response = await self._pisugar_http_exec_async("get battery_charging")
                    if charging_response and charging_response.lower() in ['true', '1', 'yes', 'charging']:
                        is_charging = True
                    return level, is_charging
            except Exception as e:
                if logger.isEnabledFor(logging.DEBUG):
                    logger.debug(f"PiSugar HTTP fallback error: {e}")
            
            return None, False
        except Exception as e:
            if logger.isEnabledFor(logging.DEBUG):
                logger.debug(f"PiSugar battery read error: {e}")
            return None, False
    
    def _get_pisugar_battery(self):
        """Get battery level from PiSugar 3 module via pisugar-server (sync wrapper)"""
        try:
            return asyncio.run(self._get_pisugar_battery_async())
        except Exception as e:
            if logger.isEnabledFor(logging.DEBUG):
                logger.debug(f"PiSugar battery sync wrapper error: {e}")
        return None, False
    
    async def _get_battery_info_async(self):
        """Get actual battery level and charging status - prioritize PiSugar 3, fallback to system (async)"""
        # First, try PiSugar 3 module
        pisugar_level, pisugar_charging = await self._get_pisugar_battery_async()
        if pisugar_level is not None:
            return pisugar_level, pisugar_charging
        
        # Fallback to system methods
        try:
            import os
            # Try to read from /sys/class/power_supply (Linux)
            battery_paths = [
                "/sys/class/power_supply/BAT0",
                "/sys/class/power_supply/BAT1",
                "/sys/class/power_supply/battery",
            ]
            
            loop = asyncio.get_event_loop()
            for bat_path in battery_paths:
                capacity_file = os.path.join(bat_path, "capacity")
                status_file = os.path.join(bat_path, "status")
                
                if os.path.exists(capacity_file) and os.path.exists(status_file):
                    try:
                        # Use executor for file I/O
                        def read_file_sync(path):
                            with open(path, 'r') as f:
                                return f.read().strip()
                        
                        level_str = await loop.run_in_executor(None, read_file_sync, capacity_file)
                        status_str = await loop.run_in_executor(None, read_file_sync, status_file)
                        
                        level = int(level_str)
                        status = status_str.upper()
                        is_charging = status in ["CHARGING", "FULL"]
                        
                        return level, is_charging
                    except (ValueError, IOError):
                        continue
            
            # Fallback: try upower (if available)
            try:
                process = await asyncio.create_subprocess_exec(
                    'upower', '-i', '/org/freedesktop/UPower/devices/battery_BAT0',
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )
                try:
                    stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=1.0)
                    if process.returncode == 0:
                        output = stdout.decode('utf-8', errors='ignore')
                        level = None
                        is_charging = False
                        
                        for line in output.split('\n'):
                            if 'percentage' in line.lower():
                                try:
                                    level = int(line.split('%')[0].split()[-1])
                                except ValueError:
                                    pass
                            if 'state' in line.lower() and 'charging' in line.lower():
                                is_charging = True
                        
                        if level is not None:
                            return level, is_charging
                except asyncio.TimeoutError:
                    process.kill()
                    await process.wait()
            except FileNotFoundError:
                pass
            
            # If all methods fail, return None (use provided battery_level)
            return None, False
        except Exception as e:
            logger.debug(f"Could not get battery info: {e}")
            return None, False
    
    def _get_battery_info(self):
        """Get actual battery level and charging status - prioritize PiSugar 3, fallback to system (sync wrapper)"""
        try:
            return asyncio.run(self._get_battery_info_async())
        except Exception as e:
            logger.debug(f"Battery info sync wrapper error: {e}")
        return None, False


class DisplayManager:
    """Display manager for Whisplay HAT (following whisplay-ai-chatbot pattern)"""
    
    def __init__(self, config: dict):
        global current_status, current_emoji, current_text
        
        self.config = config
        screen_cfg = config.get("screen", {})
        screen_type = screen_cfg.get("type", "whisplay169")
        self.display = None
        self.render_thread = None
        # Transcript font path from config (can be overridden)
        self.transcript_font_path = screen_cfg.get("transcript_font_path", None)
        
        # PiSugar configuration
        pisugar_cfg = config.get("pisugar", {})
        self.pisugar_host = pisugar_cfg.get("host", "127.0.0.1")
        self.pisugar_port = pisugar_cfg.get("port", 8421)
        self.pisugar_username = pisugar_cfg.get("username", "admin")
        self.pisugar_password = pisugar_cfg.get("password", "admin")
        self.pisugar_token = None  # Will be obtained via login
        
        # PiSugar battery polling (30s interval) - using asyncio.Task instead of thread
        self._battery_cache = None
        self._battery_cache_time = 0
        self._battery_poll_interval = 30.0  # Poll every 30 seconds
        self._battery_poll_task = None
        self._battery_poll_running = False

        try:
            if screen_type == "whisplay169":
                from whisplay_display import WhisplayDisplay
                
                # Retry logic for GPIO busy errors
                max_retries = 3
                retry_delay = 1.0  # seconds
                
                for attempt in range(max_retries):
                    try:
                        self.display = WhisplayDisplay(screen_cfg)
                        logger.debug("✅ Whisplay 240x280 display initialized")
                        break  # Success, exit retry loop
                    except Exception as e:
                        error_msg = str(e)
                        is_gpio_busy = "GPIO busy" in error_msg or "busy" in error_msg.lower()
                        
                        if is_gpio_busy and attempt < max_retries - 1:
                            logger.warning(f"⚠️ GPIO busy (attempt {attempt + 1}/{max_retries}), waiting {retry_delay}s before retry...")
                            import time
                            time.sleep(retry_delay)
                            retry_delay *= 1.5  # Exponential backoff
                            continue
                        else:
                            logger.error(f"❌ Failed to init display: {error_msg}")
                            
                            # Check if it's a GPIO busy error
                            if is_gpio_busy:
                                logger.error("⚠️ GPIO pins are busy - another process may be using them")
                                logger.error("💡 Try: sudo pkill -f 'python.*start.py' or restart the device")
                                logger.error("💡 Or wait a few seconds for GPIO to be released")
                            
                            logger.error("Display initialization failed, but continuing without display...")
                            self.display = None
                            break  # Exit retry loop
                
                # Get font path
                font_path = screen_cfg.get("font_path", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
                # Convert relative paths to absolute (relative to python_client directory)
                # start.py runs from python_client directory, so os.getcwd() gives us python_client dir
                if not os.path.isabs(font_path):
                    python_client_dir = os.getcwd()  # start.py runs from python_client directory
                    font_path = os.path.join(python_client_dir, font_path)
                if not os.path.exists(font_path):
                    # Try alternative paths
                    alt_paths = [
                        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    ]
                    for alt_path in alt_paths:
                        if os.path.exists(alt_path):
                            font_path = alt_path
                            break
                
                # Get transcript font path from config
                transcript_font_path = screen_cfg.get("transcript_font_path", None)
                # Convert relative paths to absolute for transcript font too
                if transcript_font_path and not os.path.isabs(transcript_font_path):
                    if 'python_client_dir' not in locals():
                        python_client_dir = os.getcwd()  # start.py runs from python_client directory
                    transcript_font_path = os.path.join(python_client_dir, transcript_font_path)
                # Start render thread (reduced fps from 30 to 20 for better performance)
                self.render_thread = RenderThread(self.display, font_path, fps=20, transcript_font_path=transcript_font_path)
                self.render_thread.start_render()
                logger.debug("✅ Display render thread started")
                
                # Set initial display state to show something immediately
                self.show_ready("Đang khởi tạo...", "⏳")
                
                # Note: Battery polling will be started via start_battery_polling_async()
                # Called from VoiceAssistant.run() after async context is available
            else:
                logger.warning(f"⚠️ Unsupported screen type: {screen_type}. Only 'whisplay169' is supported.")
        except Exception as e:
            logger.error(f"❌ Failed to init display: {e}", exc_info=True)
            self.display = None
    
    def start_battery_polling_async(self):
        """Start battery polling as asyncio.Task (better than thread + asyncio.run())"""
        if self._battery_poll_task is None or self._battery_poll_task.done():
            self._battery_poll_running = True
            try:
                loop = asyncio.get_event_loop()
                self._battery_poll_task = loop.create_task(self._battery_poll_loop_async())
                logger.debug("✅ PiSugar battery polling task started (30s interval)")
            except RuntimeError:
                logger.warning("⚠️ No event loop available for battery polling, will start later")
    
    async def _stop_battery_polling_async(self):
        """Stop battery polling task"""
        self._battery_poll_running = False
        if self._battery_poll_task and not self._battery_poll_task.done():
            self._battery_poll_task.cancel()
            try:
                await self._battery_poll_task
            except asyncio.CancelledError:
                pass
            logger.debug("✅ PiSugar battery polling task stopped")
    
    def _stop_battery_polling(self):
        """Stop battery polling (sync wrapper for backward compatibility)"""
        # This is called from stop() which may be called from sync context
        # Try to stop async task if event loop is available
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                # Create task to stop polling
                asyncio.create_task(self._stop_battery_polling_async())
            else:
                # No running loop, just set flag
                self._battery_poll_running = False
        except RuntimeError:
            # No event loop, just set flag
            self._battery_poll_running = False
    
    async def _battery_poll_loop_async(self):
        """Background async loop to poll PiSugar battery status every 30 seconds"""
        global current_battery_level, current_battery_color
        
        while self._battery_poll_running:
            try:
                # Try PiSugar first
                level, is_charging = await self._get_pisugar_battery_async()
                if level is None:
                    # PiSugar not available, try system fallback
                    level, is_charging = await self._get_battery_info_async()
                
                if level is not None:
                    # Update global battery level
                    current_battery_level = level
                    
                    # Update battery color based on level
                    if level >= 50:
                        current_battery_color = (85, 255, 0)  # Green
                    elif level >= 20:
                        current_battery_color = (255, 255, 0)  # Yellow
                    else:
                        current_battery_color = (255, 0, 0)  # Red
                    
                    # Cache the result
                    self._battery_cache = (level, is_charging)
                    self._battery_cache_time = time.time()
                    
                    if logger.isEnabledFor(logging.DEBUG):
                        logger.debug(f"🔋 PiSugar battery: {level}% (charging: {is_charging})")
            except asyncio.CancelledError:
                logger.debug("Battery polling task cancelled")
                break
            except Exception as e:
                logger.debug(f"Error in battery polling: {e}")
            
            # Sleep for poll interval (30 seconds) - using asyncio.sleep() instead of time.sleep()
            try:
                await asyncio.sleep(self._battery_poll_interval)
            except asyncio.CancelledError:
                break

    def enable_rendering(self):
        """Enable rendering"""
        global _rendering_enabled
        _rendering_enabled = True

    def disable_rendering(self):
        """Disable rendering"""
        global _rendering_enabled
        _rendering_enabled = False

    def update_display(self, status=None, emoji=None, text=None, **kwargs):
        """Update display content (updates global variables)"""
        global current_status, current_emoji, current_text, current_scroll_top, _rendering_enabled
        global current_words, current_word_index, current_word_display_text
        
        if status is not None:
            current_status = status
            logger.debug(f"📺 Display status updated: {status}")
        if emoji is not None:
            current_emoji = emoji
            logger.debug(f"📺 Display emoji updated: {emoji}")
        if text is not None:
            # Split text into words
            import re
            # Split by whitespace but keep spaces
            words = re.findall(r'\S+|\s+', text)
            # Filter out empty strings and combine spaces with following words
            new_words = []
            for word in words:
                if word.strip():  # Non-space word
                    new_words.append(word)
                elif new_words:  # Space after a word
                    new_words[-1] += word  # Append space to previous word
            
            # Check if this is a transcript (bot or user)
            is_bot_transcript = text.startswith("Bot:")
            is_user_transcript = text.startswith("Bạn:")
            is_transcript = is_bot_transcript or is_user_transcript
            
            # Initialize is_text_continuation to avoid UnboundLocalError
            is_text_continuation = False
            
            # For transcripts, add to history list (scrolling up effect)
            if is_transcript:
                global transcript_history, MAX_TRANSCRIPT_ENTRIES
                # Extract text without prefix
                transcript_text = text[4:].strip() if (is_bot_transcript or is_user_transcript) else text
                transcript_type = "bot" if is_bot_transcript else "user"
                
                # Check if this is a continuation of the last transcript (same type)
                # For bot: append if extending previous bot transcript
                # For user: always create new entry (user transcripts don't append)
                is_continuation = False
                if is_bot_transcript and transcript_history and transcript_history[-1]["type"] == "bot":
                    # Check if new text extends last bot transcript
                    last_text = transcript_history[-1]["text"]
                    if transcript_text.startswith(last_text):
                        # Update last entry instead of creating new one
                        transcript_history[-1]["text"] = transcript_text
                        is_continuation = True
                
                if not is_continuation:
                    # Add new transcript entry
                    transcript_history.append({
                        "type": transcript_type,
                        "text": transcript_text
                    })
                    # Limit history size
                    if len(transcript_history) > MAX_TRANSCRIPT_ENTRIES:
                        transcript_history.pop(0)  # Remove oldest
                
                # Update current_text for compatibility
                current_text = text
                current_words = new_words
                current_word_index = 0
                current_word_display_text = text
                # Auto-scroll to bottom (newest) when new transcript arrives
                # This will be handled in render_transcript_history
            else:
                # Not a transcript, use normal logic
                is_text_continuation = False
                
                if is_bot_transcript and current_text and current_text.startswith("Bot:"):
                    # Check if new text extends old text (for bot transcript, we append incrementally)
                    if text.startswith(current_text):
                        # New text is an extension of old text, don't reset word display
                        is_text_continuation = True
                        # Extract only new words (the part that was added)
                        old_text_len = len(current_text)
                        if len(text) > old_text_len:
                            # Extract new part
                            new_part = text[old_text_len:].strip()
                            if new_part:
                                # Split new part into words
                                new_part_words = re.findall(r'\S+|\s+', new_part)
                                # Append only new words to existing words
                                for word in new_part_words:
                                    if word.strip():
                                        current_words.append(word)
                                    elif current_words:
                                        current_words[-1] += word
                        # Don't update current_words with new_words, keep existing + appended
                    else:
                        # Text changed completely, use new words
                        current_words = new_words
                else:
                    # Not a continuation, use new words
                    current_words = new_words
                
                # Reset word-by-word display state only if text is completely new (not continuation)
                if not is_text_continuation:
                    current_word_index = 0
                    current_word_display_text = ""
                    # Only reset scroll if text is completely different (not just continuation)
                    # This allows text to scroll smoothly when appending
                    if not (is_bot_transcript and current_text and text.startswith(current_text)):
                        current_scroll_top = 0  # Reset scroll when text changes completely
                
                current_text = text
                
                # For user transcript, bot transcript, or short text, immediately show full text instead of word-by-word
                # This ensures transcripts are displayed fully in the wide text area
                if is_user_transcript or is_bot_transcript or len(current_words) <= 10 or len(text) < 50:
                    current_word_display_text = text  # Show full text immediately
                    current_word_index = len(current_words)  # Mark all words as shown
            
            logger.debug(f"📺 Display text updated: {text[:50]}... (rendering enabled: {_rendering_enabled}, words: {len(current_words)}, continuation: {is_text_continuation})")
        else:
            logger.debug(f"📺 Display update called but text is None. Status: {status}, Emoji: {emoji}")
    
    def show_connecting(self):
        """Show connecting state"""
        global current_status, current_emoji, current_text, current_scroll_top
        current_status = "Đang kết nối"
        current_emoji = "🔄"
        current_text = "Đang kết nối đến máy chủ..."
        current_scroll_top = 0
    
    def show_ready(self, text="Chờ đánh thức", emoji="✅"):
        """Show ready state"""
        global current_status, current_emoji, current_text, current_scroll_top
        current_status = "Sẵn sàng"
        current_emoji = emoji
        current_text = text
        current_scroll_top = 0

    def show_pairing_code(self, code: str, message: str = ""):
        """Show pairing code - show all 6 digits"""
        global current_status, current_emoji, current_text, current_scroll_top
        # Show full code (all 6 digits for v2 activation)
        code_display = code  # Full code, not truncated
        logger.debug(f"📱 Showing pairing code on display: {code_display}")
        current_status = "Kích hoạt"
        current_emoji = "🔑"
        if message:
            current_text = f"Mã: {code_display}\n{message}".strip()
        else:
            current_text = f"Mã: {code_display}\nxiaozhi.vietdev.vn"
        current_scroll_top = 0

        # Also call display directly to ensure it's shown immediately
        if self.display:
            try:
                self.display.show_pairing_code(code, message)
            except Exception as e:
                logger.error(f"Error showing pairing code on display: {e}")

    def show_error(self, text: str):
        """Show error message"""
        global current_status, current_emoji, current_text, current_scroll_top
        current_status = "Lỗi"
        current_emoji = "❌"
        # Translate common error messages
        error_translations = {
            "Registration failed": "Lỗi đăng ký thiết bị",
            "Connection timeout": "Hết thời gian kết nối",
            "Error:": "Lỗi:",
        }
        translated_text = text
        for eng, vi in error_translations.items():
            if eng in text:
                translated_text = text.replace(eng, vi)
                break
        current_text = translated_text
        current_scroll_top = 0

    def show_status(self, status: str, emoji: str = "", text: str = ""):
        """Show status"""
        self.update_display(status=status, emoji=emoji, text=text)

    def update(self, **kwargs):
        """Update display with any parameters"""
        self.update_display(**kwargs)
    
    def stop(self):
        """Stop display manager (sync version)"""
        global _rendering_enabled
        
        # Stop battery polling (sync wrapper)
        self._stop_battery_polling()
        
        if self.render_thread:
            self.render_thread.stop_render()
            self.render_thread.join(timeout=2.0)
        if self.display:
            try:
                self.display.fill_screen(0)
            except Exception as e:
                logger.debug(f"Error filling screen during stop: {e}")
        logger.debug("Display manager stopped")
    
    async def stop_async(self):
        """Stop display manager (async version)"""
        global _rendering_enabled
        
        # Stop battery polling (async)
        await self._stop_battery_polling_async()
        
        if self.render_thread:
            self.render_thread.stop_render()
            self.render_thread.join(timeout=2.0)
        if self.display:
            try:
                self.display.fill_screen(0)
            except Exception as e:
                logger.debug(f"Error filling screen during stop_async: {e}")
        logger.debug("Display manager stopped")

    def shutdown(self):
        """Shutdown display manager"""
        self.stop()
