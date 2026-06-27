import asyncio
import json
import signal
import sys
import threading
import time
import typing as _t  # noqa: F401
from typing import Set

from src.constants.constants import AbortReason, DeviceState, ListeningMode
from src.mcp.mcp_server import McpServer
from src.protocols.mqtt_protocol import MqttProtocol
from src.protocols.websocket_protocol import WebsocketProtocol
from src.utils.common_utils import handle_verification_code
from src.utils.config_manager import ConfigManager
from src.utils.logging_config import get_logger
from src.utils.opus_loader import setup_opus

logger = get_logger(__name__)


def setup_signal_handler(sig, handler, description):
    """
    Unified signal handler setup function (cross-platform best effort).
    """
    try:
        signal.signal(sig, handler)
    except (AttributeError, ValueError) as e:
        print(f"Note: Cannot set {description} handler: {e}")


def handle_sigint(signum, frame):
    app = Application.get_instance()
    if not app:
        sys.exit(0)

    # Use app's main loop, more stable and thread-safe
    loop = app._main_loop
    if loop and not loop.is_closed():
        # Create task directly in the specified loop
        def create_shutdown_task():
            try:
                if loop.is_running():
                    asyncio.run_coroutine_threadsafe(app.shutdown(), loop)
                else:
                    loop.create_task(app.shutdown())
            except Exception as e:
                print(f"Failed to create shutdown task: {e}")
                sys.exit(0)

        loop.call_soon_threadsafe(create_shutdown_task)
    else:
        # Main loop not ready or closed, exit directly
        sys.exit(0)


# Setup signal handlers: Set SIGINT on all platforms; set SIGTERM if supported; ignore SIGTRAP if exists
setup_signal_handler(signal.SIGINT, handle_sigint, "SIGINT")
if hasattr(signal, "SIGTERM"):
    setup_signal_handler(signal.SIGTERM, handle_sigint, "SIGTERM")
if hasattr(signal, "SIGTRAP"):
    setup_signal_handler(signal.SIGTRAP, signal.SIG_IGN, "SIGTRAP")

setup_opus()

try:
    import opuslib  # noqa: F401
except Exception as e:
    logger.critical("Failed to import opuslib: %s", e, exc_info=True)
    logger.critical("Please ensure opus dynamic library is properly installed or in the correct location")
    sys.exit(1)


class Application:
    """
    Pure asyncio-based application architecture.
    """

    _instance = None
    _lock = threading.Lock()

    @classmethod
    def get_instance(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = Application()
        return cls._instance

    def __init__(self):
        """
        Initialize application.
        """
        if Application._instance is not None:
            logger.error("Attempted to create multiple instances of Application")
            raise Exception("Application is a singleton class, please use get_instance() to get instance")
        Application._instance = self

        logger.debug("Initializing Application instance")

        # Configuration management
        self.config = ConfigManager.get_instance()

        # State management
        self.device_state = DeviceState.IDLE
        self.voice_detected = False
        self.keep_listening = False
        self.aborted = False
        self.aborted_event = None  # Will be initialized in _initialize_async_objects

        # Listening mode and AEC enabled status
        self.listening_mode = ListeningMode.AUTO_STOP
        self.aec_enabled = self.config.get_config("AEC_OPTIONS.ENABLED", True)

        # Async components
        self.audio_codec = None
        self.protocol = None
        self.display = None
        self.wake_word_detector = None
        # Task management
        self.running = False
        self._main_tasks: Set[asyncio.Task] = set()
        # Lightweight background task pool (short-term tasks), to be cancelled uniformly during shutdown
        self._bg_tasks: Set[asyncio.Task] = set()

        # Runtime metrics/counts
        self._command_dropped_count = 0

        # Command queue - delayed initialization until event loop is running
        self.command_queue: asyncio.Queue = None

        # Task cancellation event - delayed initialization until event loop is running
        self._shutdown_event = None

        # Save main thread's event loop (will be set later in run method)
        self._main_loop = None

        # MCP server
        self.mcp_server = McpServer.get_instance()

        # Message handler mapping
        self._message_handlers = {
            "tts": self._handle_tts_message,
            "stt": self._handle_stt_message,
            "llm": self._handle_llm_message,
            "iot": self._handle_iot_message,
            "mcp": self._handle_mcp_message,
        }

        # Concurrency control locks - will be initialized in _initialize_async_objects
        self._state_lock = None
        self._abort_lock = None

        # Audio and send concurrency limits (avoid task storm)
        try:
            audio_write_cc = int(
                self.config.get_config("APP.AUDIO_WRITE_CONCURRENCY", 4)
            )
        except Exception:
            audio_write_cc = 4
        try:
            send_audio_cc = int(self.config.get_config("APP.SEND_AUDIO_CONCURRENCY", 4))
        except Exception:
            send_audio_cc = 4
        # Save configuration values, create Semaphore in _initialize_async_objects
        self._audio_write_cc = audio_write_cc
        self._send_audio_cc = send_audio_cc
        self._audio_write_semaphore = None
        self._send_audio_semaphore = None

        # Most recent time of receiving server audio (for handling TTS start-stop race condition)
        self._last_incoming_audio_at: float = 0.0

        # Audio silence detection (event-driven instead of fixed sleep)
        try:
            tail_silence_ms = int(
                self.config.get_config("APP.TTS_TAIL_SILENCE_MS", 150)
            )
        except Exception:
            tail_silence_ms = 150
        try:
            tail_wait_timeout_ms = int(
                self.config.get_config("APP.TTS_TAIL_WAIT_TIMEOUT_MS", 800)
            )
        except Exception:
            tail_wait_timeout_ms = 800
        self._incoming_audio_silence_sec: float = max(0.0, tail_silence_ms / 1000.0)
        self._incoming_audio_tail_timeout_sec: float = max(
            0.1, tail_wait_timeout_ms / 1000.0
        )
        self._incoming_audio_idle_event = None
        self._incoming_audio_idle_handle = None

        logger.debug("Application instance initialization complete")

    async def run(self, **kwargs):
        """
        Start application.
        """
        logger.info("Starting application, parameters: %s", kwargs)

        mode = kwargs.get("mode", "gui")
        protocol = kwargs.get("protocol", "websocket")

        return await self._run_application_core(protocol, mode)

    def _initialize_async_objects(self):
        """
        Initialize async objects - must be called after event loop is running.
        """
        logger.debug("Initializing async objects")
        # Read command queue max size from config, default 256
        try:
            maxsize = int(self.config.get_config("APP.COMMAND_QUEUE_MAXSIZE", 256))
        except Exception:
            maxsize = 256
        self.command_queue = asyncio.Queue(maxsize=maxsize)
        self._shutdown_event = asyncio.Event()

        # Initialize async locks
        self._state_lock = asyncio.Lock()
        self._abort_lock = asyncio.Lock()

        # Initialize abort event
        self.aborted_event = asyncio.Event()
        self.aborted_event.clear()

        # Initialize semaphores
        self._audio_write_semaphore = asyncio.Semaphore(self._audio_write_cc)
        self._send_audio_semaphore = asyncio.Semaphore(self._send_audio_cc)

        # Initialize audio silence event (default to already silent, avoid unnecessary waiting)
        self._incoming_audio_idle_event = asyncio.Event()
        self._incoming_audio_idle_event.set()

    async def _run_application_core(self, protocol: str, mode: str):
        """
        Application core running logic.
        """
        try:
            self.running = True

            # Save main thread's event loop
            self._main_loop = asyncio.get_running_loop()

            # Initialize async objects - must be created after event loop is running
            logger.info("Initializing async objects...")
            self._initialize_async_objects()

            # Initialize components
            logger.info("About to initialize components...")
            await self._initialize_components(mode, protocol)
            logger.info("Components initialized successfully")

            # Start core tasks
            logger.info("About to start core tasks...")
            await self._start_core_tasks()
            logger.info("Core tasks started")

            # Start display interface
            if mode == "gui":
                logger.info("Starting GUI display...")
                await self._start_gui_display()
            else:
                logger.info("Starting CLI display...")
                await self._start_cli_display()
                logger.info("CLI display started, continuing...")

            logger.info("Application started, press Ctrl+C to exit")

            # Wait for shutdown signal
            await self._shutdown_event.wait()

            return 0

        except Exception as e:
            logger.error(f"Failed to start application: {e}", exc_info=True)
            return 1
        finally:
            # Ensure application shuts down properly
            try:
                await self.shutdown()
            except Exception as e:
                logger.error(f"Error shutting down application: {e}")

    async def _initialize_components(self, mode: str, protocol: str):
        """
        Initialize application components.
        """
        logger.info("Initializing application components...")

        try:
            # Setup display type (must be before device state setting)
            logger.info("Setting display type...")
            self._set_display_type(mode)
            logger.info("Display type set")
        except Exception as e:
            logger.error(f"Failed to set display type: {e}", exc_info=True)
            raise

        try:
            # Initialize MCP server
            logger.info("Initializing MCP server...")
            self._initialize_mcp_server()
            logger.info("MCP server initialized")
        except Exception as e:
            logger.error(f"Failed to initialize MCP server: {e}", exc_info=True)
            raise

        try:
            # Setup device state
            await self._set_device_state(DeviceState.IDLE)
            logger.info("Device state set to IDLE")
        except Exception as e:
            logger.error(f"Failed to set device state: {e}", exc_info=True)
            raise

        try:
            # Initialize IoT devices
            await self._initialize_iot_devices()
            logger.info("IoT devices initialized")
        except Exception as e:
            logger.error(f"Failed to initialize IoT devices: {e}", exc_info=True)
            raise

        try:
            # Initialize audio codec
            await self._initialize_audio()
            logger.info("Audio codec initialized")
        except Exception as e:
            logger.error(f"Failed to initialize audio codec: {e}", exc_info=True)
            raise

        try:
            # Setup protocol
            self._set_protocol_type(protocol)
            logger.info("Protocol type set")
        except Exception as e:
            logger.error(f"Failed to set protocol type: {e}", exc_info=True)
            raise

        try:
            # Initialize wake word detector
            await self._initialize_wake_word_detector()
            logger.info("Wake word detector initialized")
        except Exception as e:
            logger.error(f"Failed to initialize wake word detector: {e}", exc_info=True)
            raise

        try:
            # Setup protocol callbacks
            self._setup_protocol_callbacks()
            logger.info("Protocol callbacks set up")
        except Exception as e:
            logger.error(f"Failed to setup protocol callbacks: {e}", exc_info=True)
            raise

        try:
            # Start calendar reminder service
            await self._start_calendar_reminder_service()
            logger.info("Calendar reminder service started")
        except Exception as e:
            logger.error(f"Failed to start calendar reminder service: {e}", exc_info=True)
            raise

        try:
            # Start timer service
            await self._start_timer_service()
            logger.info("Timer service started")
        except Exception as e:
            logger.error(f"Failed to start timer service: {e}", exc_info=True)
            raise

        try:
            # Initialize shortcut manager
            await self._initialize_shortcuts()
            logger.info("Shortcut manager initialized")
        except Exception as e:
            logger.error(f"Failed to initialize shortcut manager: {e}", exc_info=True)
            raise

        logger.info("Application components initialization complete")

    async def _initialize_audio(self):
        """
        Initialize audio device and codec.
        """
        try:
            import os as _os

            if _os.getenv("XIAOZHI_DISABLE_AUDIO") == "1":
                logger.warning("Audio initialization disabled via environment variable (XIAOZHI_DISABLE_AUDIO=1)")
                self.audio_codec = None
                return
            logger.debug("Starting to initialize audio codec")
            from src.audio_codecs.audio_codec import AudioCodec

            self.audio_codec = AudioCodec()
            await self.audio_codec.initialize()

            # Setup realtime encoding callback - Key: ensure microphone data is sent in realtime
            self.audio_codec.set_encoded_audio_callback(self._on_encoded_audio)

            logger.info("Audio codec initialized successfully")

        except Exception as e:
            logger.error("Failed to initialize audio device: %s", e, exc_info=True)
            # Ensure audio_codec is None when initialization fails
            self.audio_codec = None

    def _on_encoded_audio(self, encoded_data: bytes):
        """Process encoded audio data callback.

        Note: This callback is invoked in the audio driver thread, requiring thread-safe scheduling to the main event loop.
        Key logic: Only send audio data in LISTENING state or SPEAKING+REALTIME mode
        """
        try:
            # 1. LISTENING state: Always send (including during TTS playback in realtime mode)
            # 2. SPEAKING state: Only send in REALTIME mode (backward compatible)
            should_send = self._should_send_microphone_audio()

            if (
                should_send
                and self.protocol
                and self.protocol.is_audio_channel_opened()
            ):

                # Thread-safe scheduling to main event loop
                if self._main_loop and not self._main_loop.is_closed():
                    self._main_loop.call_soon_threadsafe(
                        self._schedule_audio_send, encoded_data
                    )

        except Exception as e:
            logger.error(f"Failed to process encoded audio callback: {e}")

    def _schedule_audio_send(self, encoded_data: bytes):
        """
        Schedule audio send task in the main event loop.
        """
        try:
            if not self.running or not self.protocol:
                return
            # Check state again (state may have changed during scheduling)
            # Core logic: Send audio in LISTENING state or SPEAKING+REALTIME mode
            should_send = self._should_send_microphone_audio()

            if (
                should_send
                and self.protocol
                and self.protocol.is_audio_channel_opened()
            ):
                # Use call_soon_threadsafe to avoid qasync task reentry
                if self._main_loop and not self._main_loop.is_closed():
                    self._main_loop.call_soon_threadsafe(
                        self._schedule_audio_send_task, encoded_data
                    )

        except Exception as e:
            logger.error(f"Failed to schedule audio send: {e}")

    def _schedule_audio_send_task(self, encoded_data: bytes):
        """
        Create audio send task in the main event loop.
        """
        try:
            if not self.running or not self.protocol:
                return

            # Concurrency limit, avoid task storm
            async def _send():
                async with self._send_audio_semaphore:
                    await self.protocol.send_audio(encoded_data)

            self._create_background_task(_send(), "Send audio data")
        except Exception as e:
            logger.error(f"Failed to create audio send task: {e}", exc_info=True)

    def _schedule_audio_write_task(self, data: bytes):
        """
        Create audio write task in the main event loop.
        """
        try:
            if not self.running or not self.audio_codec:
                return

            # Audio data processing requires realtime, limit concurrency to avoid task storm
            async def _write():
                async with self._audio_write_semaphore:
                    await self.audio_codec.write_audio(data)

            self._create_background_task(_write(), "Write audio data")
        except Exception as e:
            logger.error(f"Failed to create audio write task: {e}", exc_info=True)

    def _should_send_microphone_audio(self) -> bool:
        """
        Whether to send microphone encoded audio data to protocol layer.
        """
        return self.device_state == DeviceState.LISTENING or (
            self.device_state == DeviceState.SPEAKING
            and self.aec_enabled
            and self.keep_listening
            and self.listening_mode == ListeningMode.REALTIME
        )

    def _set_protocol_type(self, protocol_type: str):
        """
        Set protocol type.
        """
        logger.debug("Setting protocol type: %s", protocol_type)
        if protocol_type == "mqtt":
            self.protocol = MqttProtocol(asyncio.get_running_loop())
        else:
            self.protocol = WebsocketProtocol()

    def _set_display_type(self, mode: str):
        """
        Set display interface type.
        """
        logger.debug("Setting display interface type: %s", mode)

        if mode == "gui":
            from src.display.gui_display import GuiDisplay
            self.display = GuiDisplay()
            self._setup_gui_callbacks()
        else:
            from src.display.cli_display import CliDisplay

            self.display = CliDisplay()
            self._setup_cli_callbacks()

    def _create_async_callback(self, coro_func, *args):
        """
        Helper method to create async callback functions - use call_soon_threadsafe to avoid qasync task reentry.
        """

        def _callback():
            try:
                if self._main_loop and not self._main_loop.is_closed():
                    self._main_loop.call_soon_threadsafe(
                        self._schedule_gui_callback, coro_func, args
                    )
            except Exception as e:
                logger.error(f"Failed to schedule GUI callback: {e}", exc_info=True)

        return _callback

    def _schedule_gui_callback(self, coro_func, args):
        """
        Schedule GUI callback in the main event loop.
        """
        try:
            if not self.running:
                return

            async def _execute():
                await coro_func(*args)

            task = asyncio.create_task(_execute())

            def _on_done(t):
                if not t.cancelled() and t.exception():
                    logger.error(f"GUI callback task exception: {t.exception()}", exc_info=True)

            task.add_done_callback(_on_done)
        except Exception as e:
            logger.error(f"Failed to execute GUI callback: {e}", exc_info=True)

    def _setup_gui_callbacks(self):
        """
        Setup GUI callback functions.
        """
        self._create_background_task(
            self.display.set_callbacks(
                press_callback=self._create_async_callback(self.start_listening),
                release_callback=self._create_async_callback(self.stop_listening),
                mode_callback=self._on_mode_changed,
                auto_callback=self._create_async_callback(self.toggle_chat_state),
                abort_callback=self._create_async_callback(
                    self.abort_speaking, AbortReason.WAKE_WORD_DETECTED
                ),
                send_text_callback=self._send_text_tts,
            ),
            "GUI callback registration",
        )

    def _setup_cli_callbacks(self):
        """
        Setup CLI callback functions.
        """
        self._create_background_task(
            self.display.set_callbacks(
                auto_callback=self._create_async_callback(self.toggle_chat_state),
                abort_callback=self._create_async_callback(
                    self.abort_speaking, AbortReason.WAKE_WORD_DETECTED
                ),
                send_text_callback=self._send_text_tts,
            ),
            "CLI callback registration",
        )

    def _setup_protocol_callbacks(self):
        """
        Setup protocol callback functions.
        """
        self.protocol.on_network_error(self._on_network_error)
        self.protocol.on_incoming_audio(self._on_incoming_audio)
        self.protocol.on_incoming_json(self._on_incoming_json)
        self.protocol.on_audio_channel_opened(self._on_audio_channel_opened)
        self.protocol.on_audio_channel_closed(self._on_audio_channel_closed)

    async def _start_core_tasks(self):
        """
        Start core tasks.
        """
        logger.debug("Starting core tasks")

        # Command processing task
        self._create_task(self._command_processor(), "Command processing")

    def _create_task(self, coro, name: str) -> asyncio.Task:
        """
        Create and manage tasks.
        """
        task = asyncio.create_task(coro, name=name)
        self._main_tasks.add(task)

        def done_callback(t):
            # Remove from set when task completes to prevent memory leak
            self._main_tasks.discard(t)

            if not t.cancelled() and t.exception():
                logger.error(f"Task {name} ended abnormally: {t.exception()}", exc_info=True)

        task.add_done_callback(done_callback)
        return task

    def schedule_command_nowait(self, command):
        """
        Schedule a command to be executed asynchronously.
        """
        if self.command_queue:
            self.command_queue.put_nowait(command)

    async def _command_processor(self):
        """
        Process commands from queue sequentially - 改进版本，更快响应关闭.
        """
        # 使用更短的超时以便快速响应shutdown
        QUEUE_TIMEOUT = 0.1  # 100ms timeout

        while self.running:
            try:
                command = await asyncio.wait_for(self.command_queue.get(), timeout=QUEUE_TIMEOUT)
                result = command()
                # Check if result is a coroutine (e.g., lambda: async_func())
                if asyncio.iscoroutine(result):
                    await result
            except asyncio.TimeoutError:
                # 正常超时，继续检查running状态
                continue
            except asyncio.CancelledError:
                logger.debug("Command processor cancelled")
                break
            except Exception as e:
                logger.error(f"Command processing error: {e}")

    def _create_background_task(
        self, coro, name: str
    ):  # type: (asyncio.coroutines, str) -> _t.Optional[asyncio.Task]
        """
        Create short-term background tasks not managed by _main_tasks, and log exceptions uniformly. Tasks will be added to _bg_tasks and cancelled uniformly during shutdown.
        """

        # Improved shutdown check - atomic check with event
        if not self.running:
            logger.debug(f"Skip background task creation (application not running): {name}")
            return None

        if self._shutdown_event and self._shutdown_event.is_set():
            logger.debug(f"Skip background task creation (shutting down): {name}")
            return None

        # Double-check running state before creating task
        try:
            task = asyncio.create_task(coro, name=name)
            self._bg_tasks.add(task)

            def done_callback(t):
                if not t.cancelled() and t.exception():
                    logger.error(
                        f"Background task {name} ended abnormally: {t.exception()}", exc_info=True
                    )
                # Remove from background task pool
                self._bg_tasks.discard(t)

            task.add_done_callback(done_callback)
            return task
        except Exception as e:
            logger.error(f"Failed to create background task {name}: {e}")
            return None

    async def _start_gui_display(self):
        """
        Start GUI display.
        """
        # In qasync environment, GUI can start directly in main thread
        await self.display.start()

    async def _start_cli_display(self):
        """
        Start CLI display.
        """
        await self.display.start()

    async def _start_listening_common(self, listening_mode, keep_listening_flag):
        """
        Common start listening logic.
        """
        async with self._state_lock:
            if self.device_state != DeviceState.IDLE:
                return False

        if not self.protocol:
            logger.error("Protocol not initialized, cannot start listening")
            return False

        if not self.protocol.is_audio_channel_opened():
            success = await self.protocol.open_audio_channel()
            if not success:
                return False

        if self.audio_codec:
            await self.audio_codec.clear_audio_queue()

        await self._set_device_state(DeviceState.CONNECTING)

        # Save listening mode (important: used for audio sending judgment)
        self.listening_mode = listening_mode
        self.keep_listening = keep_listening_flag
        try:
            await self.protocol.send_start_listening(listening_mode)
        except Exception as e:
            logger.error(f"Failed to send start listening command: {e}", exc_info=True)
            await self._set_device_state(DeviceState.IDLE)
            try:
                await self.protocol.close_audio_channel()
            except Exception:
                pass
            return False
        await self._set_device_state(DeviceState.LISTENING)
        return True

    async def start_listening(self):
        """
        Start listening.
        """
        self.schedule_command_nowait(self._start_listening_impl)

    async def _start_listening_impl(self):
        """
        Start listening implementation.
        """
        success = await self._start_listening_common(ListeningMode.MANUAL, False)

        if not success and self.device_state == DeviceState.SPEAKING:
            if not self.aborted:
                await self.abort_speaking(AbortReason.WAKE_WORD_DETECTED)

    async def stop_listening(self):
        """
        Stop listening.
        """
        self.schedule_command_nowait(self._stop_listening_impl)

    async def _stop_listening_impl(self):
        """
        Stop listening implementation.
        """
        if self.device_state == DeviceState.LISTENING:
            await self.protocol.send_stop_listening()
            await self._set_device_state(DeviceState.IDLE)

    async def toggle_chat_state(self):
        """
        Toggle chat state.
        """
        self.schedule_command_nowait(self._toggle_chat_state_impl)

    async def _toggle_chat_state_impl(self):
        """
        Toggle chat state implementation.
        """
        if self.device_state == DeviceState.IDLE:
            # Determine listening mode based on AEC enabled status
            listening_mode = (
                ListeningMode.REALTIME if self.aec_enabled else ListeningMode.AUTO_STOP
            )
            await self._start_listening_common(listening_mode, True)

        elif self.device_state == DeviceState.SPEAKING:
            await self.abort_speaking(AbortReason.NONE)
        elif self.device_state == DeviceState.LISTENING:
            await self.protocol.close_audio_channel()
            await self._set_device_state(DeviceState.IDLE)

    async def abort_speaking(self, reason):
        """
        Abort speech output.
        """
        if self.aborted:
            logger.debug(f"Already aborted, ignoring duplicate abort request: {reason}")
            return

        logger.info(f"Aborting speech output, reason: {reason}")
        self.aborted = True
        self.aborted_event.set()
        if self.audio_codec:
            await self.audio_codec.clear_audio_queue()

        try:
            await self.protocol.send_abort_speaking(reason)
            await self._set_device_state(DeviceState.IDLE)
            restart = (
                reason == AbortReason.WAKE_WORD_DETECTED
                and self.keep_listening
                and self.protocol.is_audio_channel_opened()
            )

        except Exception as e:
            logger.error(f"Error aborting speech: {e}")
            restart = False
        finally:
            self.aborted = False
            self.aborted_event.clear()

        if restart:
            await asyncio.sleep(0.1)
            try:
                # Restart listening after interruption (using current mode)
                await self.protocol.send_start_listening(self.listening_mode)
                await self._set_device_state(DeviceState.LISTENING)
            except Exception as e:
                logger.error(f"Failed to restore listening: {e}")

    async def _set_device_state(self, state):
        """
        Set device state - ensure sequential execution through queue.
        """
        self.schedule_command_nowait(lambda: self._set_device_state_impl(state))

    def _update_display_async(self, update_func, *args):
        """
        Helper method to asynchronously update display - use call_soon_threadsafe to avoid qasync task reentry.
        """
        if self.display and self._main_loop:
            try:
                # Use call_soon_threadsafe to avoid qasync task reentry issues
                self._main_loop.call_soon_threadsafe(
                    self._schedule_display_update, update_func, args
                )
            except Exception as e:
                logger.error(f"Failed to schedule display update: {e}", exc_info=True)

    def _schedule_display_update(self, update_func, args):
        """
        Schedule display update in main event loop.
        """
        try:
            if not self.running or not self.display:
                return

            # Create background task to update display, avoid blocking
            async def _update():
                await update_func(*args)

            self._create_background_task(_update(), "Display update")
        except Exception as e:
            logger.error(f"Failed to schedule display update: {e}", exc_info=True)

    async def _set_device_state_impl(self, state):
        """
        Device state setting.
        """
        # Only complete state change and subsequent action selection within lock, avoid executing I/O within lock
        perform_idle = False
        perform_listening = False
        display_update = None

        async with self._state_lock:
            if self.device_state == state:
                return
            logger.debug(f"Device state change: {self.device_state} -> {state}")
            self.device_state = state
            if state == DeviceState.IDLE:
                perform_idle = True
            elif state == DeviceState.CONNECTING:
                display_update = ("Connecting...", False)
            elif state == DeviceState.LISTENING:
                perform_listening = True
            elif state == DeviceState.SPEAKING:
                display_update = ("Speaking...", True)

        # Execute I/O and time-consuming operations outside lock
        if perform_idle:
            await self._handle_idle_state()
        elif perform_listening:
            await self._handle_listening_state()
        if display_update is not None:
            text, connected = display_update
            self._update_display_async(self.display.update_status, text, connected)

    async def _handle_idle_state(self):
        """
        Handle idle state.
        """
        # UI update executed asynchronously (standby: considered disconnected by default)
        self._update_display_async(self.display.update_status, "Standby", False)

        # Set emotion
        self.set_emotion("neutral")

    async def _handle_listening_state(self):
        """
        Handle listening state.
        """
        # UI update executed asynchronously (listening: connection established)
        self._update_display_async(self.display.update_status, "Listening...", True)

        # Set emotion
        self.set_emotion("neutral")

        # Update IoT states
        await self._update_iot_states(True)

    async def _send_text_tts(self, text):
        """
        Send text for TTS.
        """
        if not self.protocol.is_audio_channel_opened():
            await self.protocol.open_audio_channel()

        await self.protocol.send_wake_word_detected(text)

    def set_chat_message(self, role, message):
        """
        Set chat message.
        """
        self._update_display_async(self.display.update_text, message)

    def set_emotion(self, emotion):
        """
        Set emotion.
        """
        self._update_display_async(self.display.update_emotion, emotion)

    # Protocol callback methods
    def _on_network_error(self, error_message=None):
        """
        Network error callback.
        """
        if error_message:
            logger.error(error_message)
        self.schedule_command_nowait(self._handle_network_error)

    async def _handle_network_error(self):
        """
        Handle network error.
        """
        self.keep_listening = False
        await self._set_device_state(DeviceState.IDLE)

        if self.protocol:
            await self.protocol.close_audio_channel()

    def _on_incoming_audio(self, data):
        """
        Receive audio data callback.
        """
        # In realtime mode, device state may remain LISTENING during TTS playback, audio still needs to be played
        should_play_audio = self.device_state == DeviceState.SPEAKING or (
            self.device_state == DeviceState.LISTENING
            and self.listening_mode == ListeningMode.REALTIME
        )

        if should_play_audio and self.audio_codec and self.running:
            # If IDLE, restore to SPEAKING (through command queue, thread-safe, reentrant)
            if self.device_state == DeviceState.IDLE:
                self.schedule_command_nowait(
                    lambda: self._set_device_state_impl(DeviceState.SPEAKING)
                )

            try:
                # Record the most recent time of receiving server audio
                self._last_incoming_audio_at = time.monotonic()

                # Mark "non-silent" and reset timer: set event after silence period
                try:
                    if self._incoming_audio_idle_event:
                        self._incoming_audio_idle_event.clear()
                    # Cancel old silence timer
                    if self._incoming_audio_idle_handle:
                        self._incoming_audio_idle_handle.cancel()
                        self._incoming_audio_idle_handle = None
                    # Schedule new silence timing task (set after tail_silence_ms)
                    # Add running check in callback to prevent firing after shutdown

                    def _mark_idle():
                        # Check running state before setting event
                        if self.running and self._incoming_audio_idle_event:
                            self._incoming_audio_idle_event.set()

                    if self._main_loop and not self._main_loop.is_closed() and self.running:
                        self._incoming_audio_idle_handle = self._main_loop.call_later(
                            self._incoming_audio_silence_sec,
                            _mark_idle,
                        )
                except Exception:
                    pass

                # If currently in IDLE, it indicates a start-stop race condition of "stop immediately followed by start", switch to SPEAKING first
                if self.device_state == DeviceState.IDLE:
                    self.schedule_command_nowait(
                        lambda: self._set_device_state_impl(DeviceState.SPEAKING)
                    )

                # Use call_soon_threadsafe to avoid qasync task reentry
                # Add shutdown check before scheduling
                if self._main_loop and not self._main_loop.is_closed() and self.running:
                    self._main_loop.call_soon_threadsafe(
                        self._schedule_audio_write_task, data
                    )
            except RuntimeError as e:
                logger.error(f"Cannot create audio write task: {e}")
            except Exception as e:
                logger.error(f"Failed to create audio write task: {e}", exc_info=True)

    def _on_incoming_json(self, json_data):
        """
        Receive JSON data callback.
        """
        self.schedule_command_nowait(lambda: self._handle_incoming_json(json_data))

    async def _handle_incoming_json(self, json_data):
        """
        Handle JSON message.
        """
        try:
            if not json_data:
                return

            if isinstance(json_data, str):
                data = json.loads(json_data)
            else:
                data = json_data
            msg_type = data.get("type", "")

            handler = self._message_handlers.get(msg_type)
            if handler:
                await handler(data)
            else:
                logger.warning(f"Received unknown type message: {msg_type}")

        except Exception as e:
            logger.error(f"Error handling JSON message: {e}", exc_info=True)

    async def _handle_tts_message(self, data):
        """
        Handle TTS message.
        """
        state = data.get("state", "")
        if state == "start":
            await self._handle_tts_start()
        elif state == "stop":
            await self._handle_tts_stop()
        elif state == "sentence_start":
            text = data.get("text", "")
            if text:
                logger.info(f"<< {text}")
                self.set_chat_message("assistant", text)

                import re

                match = re.search(r"((?:\d\s*){6,})", text)
                if match:
                    await asyncio.to_thread(handle_verification_code, text)

    async def _handle_tts_start(self):
        """
        Handle TTS start event.
        """
        logger.info(
            f"TTS start, current state: {self.device_state}, listening mode: {self.listening_mode}"
        )

        async with self._abort_lock:
            self.aborted = False
            self.aborted_event.clear()

        # In realtime mode, if currently in LISTENING state, maintain LISTENING state to support bidirectional conversation
        # Only transition to SPEAKING state in IDLE state or non-realtime mode
        if self.device_state == DeviceState.IDLE:
            await self._set_device_state(DeviceState.SPEAKING)
        elif (
            self.device_state == DeviceState.LISTENING
            and self.listening_mode != ListeningMode.REALTIME
        ):
            await self._set_device_state(DeviceState.SPEAKING)
        elif (
            self.device_state == DeviceState.LISTENING
            and self.listening_mode == ListeningMode.REALTIME
        ):
            logger.info("TTS start in realtime mode, maintain LISTENING state to support bidirectional conversation")

    async def _handle_tts_stop(self):
        """
        Handle TTS stop event.
        """
        logger.info(
            f"TTS stop, current state: {self.device_state}, listening mode: {self.listening_mode}"
        )

        # Wait for audio playback to complete
        if self.audio_codec:
            logger.debug("Waiting for TTS audio playback to complete...")
            try:
                await self.audio_codec.wait_for_audio_complete()
            except Exception as e:
                logger.warning(f"TTS audio playback wait failed: {e}")
            else:
                logger.debug("TTS audio playback complete")

        # Only wait for "silence event" in non-abort case
        if not self.aborted_event.is_set():
            try:
                if self._incoming_audio_idle_event:
                    # Wait for at most a timeout to avoid getting stuck in abnormal situations
                    try:
                        await asyncio.wait_for(
                            self._incoming_audio_idle_event.wait(),
                            timeout=self._incoming_audio_tail_timeout_sec,
                        )
                    except asyncio.TimeoutError:
                        pass
            except Exception:
                pass

        # State transition logic optimization
        if self.device_state == DeviceState.SPEAKING:
            # Traditional mode: transition from SPEAKING to LISTENING or IDLE
            if self.keep_listening:
                await self.protocol.send_start_listening(self.listening_mode)
                await self._set_device_state(DeviceState.LISTENING)
            else:
                await self._set_device_state(DeviceState.IDLE)
        elif (
            self.device_state == DeviceState.LISTENING
            and self.listening_mode == ListeningMode.REALTIME
        ):
            # Realtime mode: already in LISTENING state, no state transition needed, audio stream continues
            logger.info("Realtime mode TTS end, maintain LISTENING state, audio stream continues")

    async def _handle_stt_message(self, data):
        """
        Handle STT message.
        """
        text = data.get("text", "")
        if text:
            logger.info(f">> {text}")
            self.set_chat_message("user", text)

    async def _handle_llm_message(self, data):
        """
        Handle LLM message.
        """
        emotion = data.get("emotion", "")
        if emotion:
            self.set_emotion(emotion)

    async def _on_audio_channel_opened(self):
        """
        Audio channel opened callback.
        """
        logger.info("Audio channel opened")
        try:
            if self.audio_codec:
                await self.audio_codec.start_streams()

            # Send IoT device descriptors
            from src.iot.thing_manager import ThingManager

            thing_manager = ThingManager.get_instance()
            descriptors_json = await thing_manager.get_descriptors_json()
            await self.protocol.send_iot_descriptors(descriptors_json)
            await self._update_iot_states(False)
        except Exception as e:
            logger.error(f"Audio channel opened callback handling failed: {e}", exc_info=True)

    async def _on_audio_channel_closed(self):
        """
        Audio channel closed callback.
        """
        logger.info("Audio channel closed")
        await self._set_device_state(DeviceState.IDLE)
        self.keep_listening = False

    async def _initialize_wake_word_detector(self):
        """
        Initialize wake word detector.
        """
        try:
            from src.audio_processing.wake_word_detect import WakeWordDetector

            self.wake_word_detector = WakeWordDetector()

            # Setup callbacks
            self.wake_word_detector.on_detected(self._on_wake_word_detected)
            self.wake_word_detector.on_error = self._handle_wake_word_error

            await self.wake_word_detector.start(self.audio_codec)

            logger.info("Wake word detector initialized successfully")

        except RuntimeError as e:
            logger.info(f"Skipping wake word detector initialization: {e}")
            self.wake_word_detector = None
        except Exception as e:
            logger.error(f"Failed to initialize wake word detector: {e}")
            self.wake_word_detector = None

    async def _on_wake_word_detected(self, wake_word, full_text):
        """
        Wake word detection callback.
        """
        logger.info(f"Wake word detected: {wake_word}")

        if self.device_state == DeviceState.IDLE:
            await self._set_device_state(DeviceState.CONNECTING)
            await self._connect_and_start_listening(wake_word)
        elif self.device_state == DeviceState.SPEAKING:
            await self.abort_speaking(AbortReason.WAKE_WORD_DETECTED)

    async def _connect_and_start_listening(self, wake_word):
        """
        Connect to server and start listening.
        """
        try:
            if not await self.protocol.connect():
                logger.error("Failed to connect to server")
                await self._set_device_state(DeviceState.IDLE)
                return

            if not await self.protocol.open_audio_channel():
                logger.error("Failed to open audio channel")
                await self._set_device_state(DeviceState.IDLE)
                return

            await self.protocol.send_wake_word_detected("wake")
            self.keep_listening = True
            # Determine listening mode based on AEC enabled status
            listening_mode = (
                ListeningMode.REALTIME if self.aec_enabled else ListeningMode.AUTO_STOP
            )
            self.listening_mode = listening_mode
            await self.protocol.send_start_listening(listening_mode)
            await self._set_device_state(DeviceState.LISTENING)

        except Exception as e:
            logger.error(f"Connection and start listening failed: {e}")
            await self._set_device_state(DeviceState.IDLE)

    def _handle_wake_word_error(self, error):
        """
        Handle wake word detector error.
        """
        logger.error(f"Wake word detection error: {error}")

    async def _initialize_iot_devices(self):
        """
        Initialize IoT devices.
        """
        from src.iot.thing_manager import ThingManager

        thing_manager = ThingManager.get_instance()

        await thing_manager.initialize_iot_devices(self.config)
        logger.info("IoT devices initialization complete")

    async def _handle_iot_message(self, data):
        """
        Handle IoT message.
        """
        from src.iot.thing_manager import ThingManager

        thing_manager = ThingManager.get_instance()
        commands = data.get("commands", [])
        logger.info(f"IoT message: {commands}")
        for command in commands:
            try:
                result = await thing_manager.invoke(command)
                logger.info(f"IoT command execution result: {result}")
            except Exception as e:
                logger.error(f"Failed to execute IoT command: {e}")

    async def _update_iot_states(self, delta=None):
        """
        Update IoT device states.
        """
        from src.iot.thing_manager import ThingManager

        thing_manager = ThingManager.get_instance()

        try:
            if delta is None:
                # Directly use async method to get states
                states_json = await thing_manager.get_states_json_str()
                await self.protocol.send_iot_states(states_json)
            else:
                # Directly use async method to get state changes
                changed, states_json = await thing_manager.get_states_json(delta=delta)
                if not delta or changed:
                    await self.protocol.send_iot_states(states_json)
        except Exception as e:
            logger.error(f"Failed to update IoT states: {e}")

    def _on_mode_changed(self):
        """
        Handle conversation mode change.
        """
        # Note: This is a synchronous method, used in GUI callback
        # Need to create temporary task to execute async lock operation
        try:
            # Quick check of current state, avoid executing complex async operations in GUI thread
            if self.device_state != DeviceState.IDLE:
                return False

            self.keep_listening = not self.keep_listening
            return True
        except Exception as e:
            logger.error(f"Mode change check failed: {e}")
            return False

    async def _safe_close_resource(
        self, resource, resource_name: str, close_method: str = "close"
    ):
        """
        Helper method to safely close resources.
        """
        if resource:
            try:
                close_func = getattr(resource, close_method, None)
                if close_func:
                    if asyncio.iscoroutinefunction(close_func):
                        await close_func()
                    else:
                        close_func()
                logger.info(f"{resource_name} closed")
            except Exception as e:
                logger.error(f"Failed to close {resource_name}: {e}")

    async def shutdown(self):
        """
        Shutdown application - 改进版本，防止race condition.
        """
        if not self.running:
            return

        logger.info("Shutting down application...")

        # First, set shutdown event to prevent new tasks
        if self._shutdown_event is not None:
            self._shutdown_event.set()

        # Then set running to False
        self.running = False

        try:
            # 1. Close wake word detector first
            await self._safe_close_resource(
                self.wake_word_detector, "Wake word detector", "stop"
            )

            # 2. Cancel all long-running tasks
            if self._main_tasks:
                logger.info(f"Cancelling {len(self._main_tasks)} main tasks")
                tasks = list(self._main_tasks)
                for task in tasks:
                    if not task.done():
                        task.cancel()

                try:
                    # Wait for task cancellation to complete
                    await asyncio.wait(tasks, timeout=3.0)  # 增加超时
                except asyncio.TimeoutError:
                    logger.warning("Some task cancellation timed out")
                except Exception as e:
                    logger.warning(f"Error waiting for task completion: {e}")

                self._main_tasks.clear()

            # 3. Cancel background tasks (short-term task pool)
            try:
                if self._bg_tasks:
                    for t in list(self._bg_tasks):
                        if not t.done():
                            t.cancel()
                    # Wait with timeout for background tasks
                    await asyncio.wait(list(self._bg_tasks), timeout=2.0, return_when=asyncio.ALL_COMPLETED)
                self._bg_tasks.clear()
            except Exception as e:
                logger.warning(f"Error cancelling background tasks: {e}")

            # 5. Close protocol connection (close as early as possible, avoid network waits after event loop ends)
            if self.protocol:
                try:
                    await self.protocol.close_audio_channel()
                    logger.info("Protocol connection closed")
                except Exception as e:
                    logger.error(f"Failed to close protocol connection: {e}")

            # 6. Close audio device (stop streams first then close completely, mitigate C extension exit race)
            if self.audio_codec:
                try:
                    await self.audio_codec.stop_streams()
                except Exception:
                    pass
            # Release audio resources as early as possible, avoid awaiting internal sleep after event loop closes
            await self._safe_close_resource(self.audio_codec, "Audio device")

            # 7. Close MCP server
            await self._safe_close_resource(self.mcp_server, "MCP server")

            # 8. Clear queues
            try:
                for q in [
                    self.command_queue,
                ]:
                    while not q.empty():
                        try:
                            q.get_nowait()
                        except asyncio.QueueEmpty:
                            break
                logger.info("Queues cleared")
            except Exception as e:
                logger.error(f"Failed to clear queues: {e}")

            # 9. Cancel tail silence timer and set silence event, avoid waiting
            # 改进：确保timer被cancel后不会再fire
            try:
                if self._incoming_audio_idle_handle:
                    self._incoming_audio_idle_handle.cancel()
                    self._incoming_audio_idle_handle = None
                # 设置事件确保任何等待的代码不会被阻塞
                if self._incoming_audio_idle_event:
                    self._incoming_audio_idle_event.set()
                    self._incoming_audio_idle_event = None
            except Exception:
                pass

            # 10. Finally stop UI display
            await self._safe_close_resource(self.display, "Display interface")

            logger.info("Application shutdown complete")

        except Exception as e:
            logger.error(f"Error shutting down application: {e}", exc_info=True)

    def _initialize_mcp_server(self):
        """
        Initialize MCP server.
        """
        logger.info("Initializing MCP server")
        # Setup async send callback - MCP server expects async callback
        self.mcp_server.set_send_callback(self._send_mcp_message_async)
        # Add common tools
        self.mcp_server.add_common_tools()

    async def _send_mcp_message_async(self, msg):
        """
        MCP message send callback (async interface) - use call_soon_threadsafe to avoid qasync task reentry.
        """
        try:
            if not self.protocol or not self._main_loop:
                logger.warning("Protocol not initialized or event loop unavailable, discarding MCP message")
                return

            # Use call_soon_threadsafe to safely schedule to main event loop
            self._main_loop.call_soon_threadsafe(
                self._schedule_mcp_send, msg
            )
            # Ensure async callback returns quickly
            await asyncio.sleep(0)
        except Exception as e:
            logger.error(f"Failed to schedule MCP message send: {e}", exc_info=True)

    def _schedule_mcp_send(self, msg):
        """
        Schedule MCP message send in main event loop.
        """
        try:
            if not self.running or not self.protocol:
                return

            # Create background task to send MCP message, avoid blocking
            async def _send():
                await self.protocol.send_mcp_message(msg)

            self._create_background_task(_send(), "Send MCP message")
        except Exception as e:
            logger.error(f"Failed to schedule MCP message send: {e}", exc_info=True)

    async def _handle_mcp_message(self, data):
        """
        Handle MCP message.
        """
        payload = data.get("payload")
        if payload:
            await self.mcp_server.parse_message(payload)

    async def _start_calendar_reminder_service(self):
        """
        Start calendar reminder service.
        """
        try:
            logger.info("Starting calendar reminder service")
            from src.mcp.tools.calendar import get_reminder_service

            # Get reminder service instance (via singleton pattern)
            reminder_service = get_reminder_service()

            # Start reminder service (service will automatically handle initialization and schedule checking internally)
            await reminder_service.start()

            logger.info("Calendar reminder service started")

        except Exception as e:
            logger.error(f"Failed to start calendar reminder service: {e}", exc_info=True)

    async def _start_timer_service(self):
        """
        Start timer service.
        """
        try:
            logger.info("Starting timer service")
            from src.mcp.tools.timer.timer_service import get_timer_service

            # Get timer service instance (via singleton pattern)
            get_timer_service()

            logger.info("Timer service started and registered to resource manager")

        except Exception as e:
            logger.error(f"Failed to start timer service: {e}", exc_info=True)

    async def _initialize_shortcuts(self):
        """
        Initialize shortcut manager.
        """
        try:
            from src.views.components.shortcut_manager import (
                start_global_shortcuts_async,
            )

            shortcut_manager = await start_global_shortcuts_async(logger)
            if shortcut_manager:
                logger.info("Shortcut manager initialized successfully")
            else:
                logger.warning("Shortcut manager initialization failed")
        except Exception as e:
            logger.error(f"Failed to initialize shortcut manager: {e}", exc_info=True)
