"""
Audio Pipeline Manager for Xiaozhi Client
Manages the complete audio flow from capture to playback with enhanced buffering
"""
import asyncio
import numpy as np
import threading
from typing import Optional, Callable
from dataclasses import dataclass

from src.audio_processing.audio_buffer_manager import (
    AudioBufferManager,
    AdaptiveAudioBuffer,
    BufferEvent,
    BufferStats
)
from src.utils.logging_config import get_logger

logger = get_logger(__name__)


@dataclass
class PipelineConfig:
    """Audio pipeline configuration"""
    use_adaptive_buffer: bool = True
    min_buffer_frames: int = 5
    max_buffer_frames: int = 50
    target_buffer_frames: int = 15
    enable_glitch_detection: bool = True
    enable_auto_recovery: bool = True


class AudioPipelineManager:
    """
    Enhanced audio pipeline manager

    Features:
    - Improved buffer management with glitch detection
    - Automatic recovery from buffer underruns/overflows
    - Non-blocking audio operations
    - Pipeline statistics monitoring
    - Callback-based event notifications
    """

    def __init__(
        self,
        audio_codec,
        config: Optional[PipelineConfig] = None,
        stats_callback: Optional[Callable[[dict], None]] = None
    ):
        """
        Initialize audio pipeline manager

        Args:
            audio_codec: WM8960 audio codec instance
            config: Pipeline configuration
            stats_callback: Optional callback for statistics updates
        """
        self._codec = audio_codec
        self._config = config or PipelineConfig()
        self._stats_callback = stats_callback

        # Buffer manager
        self._buffer_manager: Optional[AudioBufferManager] = None
        self._playback_task: Optional[asyncio.Task] = None
        self._stats_task: Optional[asyncio.Task] = None

        # Control flags
        self._is_running = False
        self._is_playing = False

        # Event callback for buffer events
        self._buffer_event_callback = self._on_buffer_event

        logger.info("🎵 AudioPipelineManager initialized")

    async def start(self):
        """Start audio pipeline"""
        if self._is_running:
            logger.warning("Audio pipeline already running")
            return

        logger.info("🎵 Starting audio pipeline...")

        # Initialize buffer manager
        buffer_class = AdaptiveAudioBuffer if self._config.use_adaptive_buffer else AudioBufferManager
        self._buffer_manager = buffer_class(
            min_frames=self._config.min_buffer_frames,
            max_frames=self._config.max_buffer_frames,
            target_frames=self._config.target_buffer_frames,
            event_callback=self._buffer_event_callback
        )
        self._buffer_manager.start()

        # Set encoded audio callback
        self._codec.set_encoded_audio_callback(self._on_encoded_audio)

        # Start codec streams
        await self._codec.start_streams()

        # Start playback task
        self._playback_task = asyncio.create_task(self._playback_loop())

        # Start statistics task
        if self._stats_callback:
            self._stats_task = asyncio.create_task(self._stats_loop())

        self._is_running = True
        self._is_playing = True

        logger.info("✅ Audio pipeline started")

    async def stop(self):
        """Stop audio pipeline"""
        if not self._is_running:
            return

        logger.info("🛑 Stopping audio pipeline...")

        self._is_running = False
        self._is_playing = False

        # Cancel tasks
        if self._playback_task:
            self._playback_task.cancel()
            try:
                await self._playback_task
            except asyncio.CancelledError:
                pass
            self._playback_task = None

        if self._stats_task:
            self._stats_task.cancel()
            try:
                await self._stats_task
            except asyncio.CancelledError:
                pass
            self._stats_task = None

        # Stop buffer manager
        if self._buffer_manager:
            self._buffer_manager.stop()
            self._buffer_manager = None

        # Stop codec streams
        await self._codec.stop_streams()

        logger.info("✅ Audio pipeline stopped")

    def _on_encoded_audio(self, data: bytes, timestamp: int):
        """Callback for encoded audio from codec (input)"""
        # This is for sending audio to server, handled by application
        pass

    def write_audio(self, data: bytes):
        """
        Write audio data to output buffer (non-blocking)

        Args:
            data: Encoded audio data
        """
        if not self._is_running or not self._buffer_manager:
            return

        try:
            # Decode audio data
            if self._codec.decoder:
                decoded = self._codec.decoder.decode(data, len(data), self._codec.FRAME_SIZE)
            else:
                decoded = data

            # Convert to numpy array
            audio_array = np.frombuffer(decoded, dtype=np.int16)

            # Write to buffer manager
            self._buffer_manager.write(audio_array)

        except Exception as e:
            logger.error(f"Error writing audio to buffer: {e}")

    async def _playback_loop(self):
        """Playback loop - reads from buffer and writes to codec"""
        logger.debug("Playback loop started")

        while self._is_running and self._is_playing:
            try:
                if not self._buffer_manager:
                    await asyncio.sleep(0.01)
                    continue

                # Check buffer level
                if self._buffer_manager.is_low_buffer():
                    # Buffer low, wait for more data
                    await asyncio.sleep(0.05)
                    continue

                # Read audio from buffer
                audio_data = self._buffer_manager.read(num_frames=1)

                if audio_data is not None:
                    # Write to codec output stream
                    if self._codec.output_stream:
                        try:
                            # Convert to int16 if needed
                            if audio_data.dtype != np.int16:
                                audio_data = audio_data.astype(np.int16)

                            self._codec.output_stream.write(audio_data)
                        except Exception as e:
                            logger.error(f"Error writing to output stream: {e}")

                # Small delay to prevent busy waiting
                await asyncio.sleep(0.01)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in playback loop: {e}", exc_info=True)
                await asyncio.sleep(0.1)

        logger.debug("Playback loop stopped")

    async def _stats_loop(self):
        """Statistics loop - periodic stats updates"""
        logger.debug("Stats loop started")

        while self._is_running and self._stats_callback:
            try:
                await asyncio.sleep(1.0)  # Update every second

                if self._buffer_manager:
                    stats = self._buffer_manager.get_status_dict()
                    try:
                        self._stats_callback(stats)
                    except Exception as e:
                        logger.error(f"Error in stats callback: {e}")

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in stats loop: {e}", exc_info=True)

        logger.debug("Stats loop stopped")

    def _on_buffer_event(self, event: BufferEvent, data: dict):
        """Handle buffer events"""
        event_names = {
            BufferEvent.NORMAL: "Normal",
            BufferEvent.UNDERRUN: "Underrun",
            BufferEvent.OVERFLOW: "Overflow",
            BufferEvent.GLITCH: "Glitch",
            BufferEvent.RECOVERED: "Recovered"
        }

        event_name = event_names.get(event, f"Unknown({event})")

        if event != BufferEvent.NORMAL:
            logger.warning(
                f"🎚️ Buffer event: {event_name} - {data}"
            )

    def clear_buffer(self):
        """Clear audio output buffer"""
        if self._buffer_manager:
            self._buffer_manager.clear()
            logger.debug("Audio buffer cleared")

    def get_stats(self) -> Optional[dict]:
        """Get pipeline statistics"""
        if self._buffer_manager:
            return self._buffer_manager.get_status_dict()
        return None

    def pause(self):
        """Pause audio playback"""
        self._is_playing = False
        logger.debug("Audio playback paused")

    def resume(self):
        """Resume audio playback"""
        if self._is_running:
            self._is_playing = True
            logger.debug("Audio playback resumed")

    def is_running(self) -> bool:
        """Check if pipeline is running"""
        return self._is_running

    def is_playing(self) -> bool:
        """Check if playback is active"""
        return self._is_playing


class SimpleAudioBuffer:
    """
    Simplified audio buffer wrapper for drop-in replacement

    Can be used to replace the deque in WM8960AudioCodec
    without major refactoring
    """

    def __init__(
        self,
        maxlen: int = 500,
        event_callback: Optional[Callable[[str, dict], None]] = None
    ):
        """
        Initialize simple audio buffer

        Args:
            maxlen: Maximum number of frames to store
            event_callback: Optional callback for events
        """
        from collections import deque

        self._buffer = deque(maxlen=maxlen)
        self._lock = threading.Lock()
        self._event_callback = event_callback

        # Statistics
        self._overflow_count = 0
        self._underrun_count = 0

    def append(self, audio_array: np.ndarray):
        """Append audio data to buffer"""
        with self._lock:
            was_full = len(self._buffer) >= self._buffer.maxlen

            self._buffer.append(audio_array)

            if was_full and self._event_callback:
                self._overflow_count += 1
                self._event_callback("overflow", {
                    "count": self._overflow_count,
                    "size": len(self._buffer)
                })

    def pop(self) -> Optional[np.ndarray]:
        """Pop audio data from buffer"""
        with self._lock:
            if not self._buffer:
                if self._event_callback:
                    self._underrun_count += 1
                    self._event_callback("underrun", {
                        "count": self._underrun_count
                    })
                return None
            return self._buffer.popleft()

    def peek(self) -> Optional[np.ndarray]:
        """Peek at first element without removing"""
        with self._lock:
            if not self._buffer:
                return None
            return self._buffer[0]

    def clear(self):
        """Clear buffer"""
        with self._lock:
            self._buffer.clear()

    def __len__(self) -> int:
        """Get buffer length"""
        with self._lock:
            return len(self._buffer)

    def get_stats(self) -> dict:
        """Get buffer statistics"""
        with self._lock:
            return {
                "size": len(self._buffer),
                "maxlen": self._buffer.maxlen,
                "overflow_count": self._overflow_count,
                "underrun_count": self._underrun_count,
                "level_percent": (len(self._buffer) / self._buffer.maxlen * 100) if self._buffer.maxlen > 0 else 0
            }
