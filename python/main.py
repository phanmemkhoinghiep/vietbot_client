#!/usr/bin/env python3
"""
Xiaozhi Python Client v2 - Main Entry Point
Based on reference py_xiaozhi_vietnamese, adapted for Whisplay HAT

Uses the Application class from reference implementation which already has:
- Wake word detection loop
- Button handling and callbacks
- Conversation flow
- State management
- MQTT protocol (connects to MQTT gateway)
"""
import argparse
import asyncio
import sys

# Add project root to Python path
from pathlib import Path
PROJECT_ROOT = Path(__file__).parent
sys.path.insert(0, str(PROJECT_ROOT))

from src.application import Application
from src.utils.logging_config import get_logger, setup_logging

logger = get_logger(__name__)


def parse_args():
    """
    Parse command line arguments.
    """
    parser = argparse.ArgumentParser(description="Xiaozhi AI Application v2")
    parser.add_argument(
        "--mode",
        choices=["gui", "cli"],
        default="cli",
        help="Run mode: gui (graphical) or cli (command line)",
    )
    parser.add_argument(
        "--skip-activation",
        action="store_true",
        help="Skip activation process and start application directly (test mode only)",
    )
    return parser.parse_args()


async def handle_activation(mode: str) -> bool:
    """Handle device activation process, depending on current event loop.

    Args:
        mode: Run mode, "gui" or "cli"

    Returns:
        bool: Whether activation was successful
    """
    try:
        from src.core.system_initializer import SystemInitializer

        logger.info("Checking device activation process...")

        system_initializer = SystemInitializer()
        # Use common activation handling in SystemInitializer, auto-adapts to GUI/CLI
        result = await system_initializer.handle_activation_process(mode=mode)
        success = bool(result.get("is_activated", False))
        logger.info(f"Activation process completed, result: {success}")
        return success
    except Exception as e:
        logger.error(f"Activation process encountered an error: {e}", exc_info=True)
        return False


async def start_app(mode: str, skip_activation: bool) -> int:
    """
    Common entry point to start the application (executed in current event loop).

    Note: Using MQTT protocol to connect to MQTT gateway (WebSocket disabled)
    """
    logger.info("Starting Xiaozhi AI Application v2 (MQTT Protocol)")

    # Handle activation process
    if not skip_activation:
        activation_success = await handle_activation(mode)
        if not activation_success:
            logger.error("Device activation failed, application will exit")
            return 1
    else:
        logger.warning("Skipping activation process (test mode)")

    # Create and start application (MQTT protocol only)
    app = Application.get_instance()
    return await app.run(mode=mode, protocol="mqtt")


if __name__ == "__main__":
    exit_code = 1
    try:
        args = parse_args()
        setup_logging()

        if args.mode == "gui":
            # In GUI mode, main will create QApplication and qasync event loop
            try:
                import qasync
                from PyQt5.QtWidgets import QApplication
            except ImportError as e:
                logger.error(f"GUI mode requires qasync and PyQt5 libraries: {e}")
                sys.exit(1)

            qt_app = QApplication.instance() or QApplication(sys.argv)

            loop = qasync.QEventLoop(qt_app)
            asyncio.set_event_loop(loop)
            logger.info("Created qasync event loop in main")

            with loop:
                exit_code = loop.run_until_complete(
                    start_app(args.mode, args.skip_activation)
                )
        else:
            # CLI mode uses standard asyncio event loop
            exit_code = asyncio.run(
                start_app(args.mode, args.skip_activation)
            )

    except KeyboardInterrupt:
        logger.info("Application interrupted by user")
        exit_code = 0
    except Exception as e:
        logger.error(f"Application exited due to error: {e}", exc_info=True)
        exit_code = 1
    finally:
        sys.exit(exit_code)
