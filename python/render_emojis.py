#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Emoji Pre-renderer - Convert SVG emoji files to PNG for display
Run this script once to pre-render all emoji SVG files to PNG format
This is useful when cairosvg is not available on the target system
"""

import os
import sys
import logging
from pathlib import Path

# Add project root to Python path
PROJECT_ROOT = Path(__file__).parent
sys.path.insert(0, str(PROJECT_ROOT))

from src.utils.logging_config import setup_logging
logger = logging.getLogger(__name__)


# List of emoji names and their SVG filenames
EMOJI_LIST = [
    # Face emotions
    ("smile", "1f603.svg"),
    ("happy", "1f603.svg"),
    ("neutral", "1f642.svg"),
    ("wink", "1f609.svg"),
    ("thinking", "1f914.svg"),
    ("thought", "1f4ad.svg"),

    # Status/Action icons
    ("listening", "1f442.svg"),
    ("ear", "1f442.svg"),
    ("speaking", "1f5e3.svg"),
    ("speaking_head", "1f5e3.svg"),
    ("connecting", "1f504.svg"),
    ("refresh", "1f504.svg"),
    ("waiting", "23f3.svg"),
    ("hourglass", "23f3.svg"),
    ("wireless", "1f9ed.svg"),
    ("signal", "1f4f6.svg"),
    ("wifi", "1f4f6.svg"),
    ("speaker", "1f50a.svg"),
    ("sound", "1f50a.svg"),
    ("plug", "1f50c.svg"),
    ("connection", "1f50c.svg"),

    # Success/Error icons
    ("check", "2705.svg"),
    ("checkmark", "2705.svg"),
    ("success", "2705.svg"),
    ("done", "2705.svg"),
    ("error", "274c.svg"),
    ("cross", "274c.svg"),
    ("warning", "26a0.svg"),
    ("prohibited", "1f6ab.svg"),
    ("no_entry", "1f6ab.svg"),

    # Other useful icons
    ("sparkles", "2728.svg"),
    ("bell", "1f3a7.svg"),
    ("satellite", "1f4e7.svg"),
    ("battery", "1f50b.svg"),
]


def render_emojis(emoji_dir="emoji", output_dir="emoji_png", size=40):
    """
    Pre-render SVG emoji files to PNG format

    Args:
        emoji_dir: Source directory containing SVG files
        output_dir: Output directory for PNG files
        size: Size of rendered emoji in pixels
    """
    import cairosvg
    from PIL import Image
    from io import BytesIO

    emoji_path = Path(emoji_dir)
    output_path = Path(output_dir)

    if not emoji_path.exists():
        logger.error(f"Emoji directory not found: {emoji_path}")
        return False

    output_path.mkdir(exist_ok=True)
    logger.info(f"📁 Created output directory: {output_path}")

    rendered_count = 0
    skipped_count = 0

    for emoji_name, svg_filename in EMOJI_LIST:
        svg_file = emoji_path / svg_filename
        png_file = output_path / f"{emoji_name}.png"

        if not svg_file.exists():
            logger.debug(f"SVG file not found: {svg_file}")
            skipped_count += 1
            continue

        try:
            # Convert SVG to PNG using cairosvg
            png_bytes = cairosvg.svg2png(
                url=str(svg_file),
                output_width=size,
                output_height=size
            )

            # Load and save PNG
            img = Image.open(BytesIO(png_bytes)).convert("RGBA")
            img.save(png_file)
            logger.debug(f"✅ Rendered: {emoji_name} -> {png_file.name}")
            rendered_count += 1

        except Exception as e:
            logger.error(f"❌ Error rendering {emoji_name}: {e}")
            skipped_count += 1

    logger.info(f"✅ Emoji pre-rendering complete:")
    logger.info(f"   Rendered: {rendered_count}")
    logger.info(f"   Skipped: {skipped_count}")
    logger.info(f"   Output: {output_path}")

    return rendered_count > 0


def render_all_svgs(emoji_dir="emoji", output_dir="emoji_png", size=40):
    """
    Pre-render ALL SVG files in the emoji directory to PNG

    Args:
        emoji_dir: Source directory containing SVG files
        output_dir: Output directory for PNG files
        size: Size of rendered emoji in pixels
    """
    import cairosvg
    from PIL import Image
    from io import BytesIO

    emoji_path = Path(emoji_dir)
    output_path = Path(output_dir)

    if not emoji_path.exists():
        logger.error(f"Emoji directory not found: {emoji_dir}")
        return False

    output_path.mkdir(exist_ok=True)
    logger.info(f"📁 Created output directory: {output_path}")

    rendered_count = 0
    skipped_count = 0

    # Get all SVG files in the directory
    svg_files = list(emoji_path.glob("*.svg"))
    logger.info(f"Found {len(svg_files)} SVG files")

    for svg_file in svg_files:
        png_file = output_path / f"{svg_file.stem}.png"

        try:
            # Convert SVG to PNG using cairosvg
            png_bytes = cairosvg.svg2png(
                url=str(svg_file),
                output_width=size,
                output_height=size
            )

            # Load and save PNG
            img = Image.open(BytesIO(png_bytes)).convert("RGBA")
            img.save(png_file)
            rendered_count += 1

        except Exception as e:
            logger.debug(f"Error rendering {svg_file.name}: {e}")
            skipped_count += 1

    logger.info(f"✅ Emoji pre-rendering complete:")
    logger.info(f"   Rendered: {rendered_count}")
    logger.info(f"   Skipped: {skipped_count}")
    logger.info(f"   Output: {output_path}")

    return rendered_count > 0


def main():
    """Main entry point"""
    setup_logging()

    logger.info("=" * 60)
    logger.info("🎨 Emoji Pre-renderer")
    logger.info("=" * 60)

    # Check if cairosvg is available
    try:
        import cairosvg
        logger.info("✅ cairosvg is available")
    except ImportError:
        logger.error("❌ cairosvg is not installed!")
        logger.error("Install with: pip3 install cairosvg tinycss2")
        return 1

    # Render emojis
    if len(sys.argv) > 1 and sys.argv[1] == "--all":
        # Render all SVG files
        success = render_all_svgs()
    else:
        # Render only the emoji list
        success = render_emojis()

    if success:
        logger.info("✅ Done! Emoji PNG files are ready")
        logger.info("   The display can now use PNG files instead of SVG")
        return 0
    else:
        logger.error("❌ Emoji pre-rendering failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
