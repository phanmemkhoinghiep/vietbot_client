"""
Improved Audio Buffer Manager for Xiaozhi Client
Handles audio buffer glitches, underruns, and overflows gracefully
"""
import asyncio
import time
import numpy as np
import threading
from collections import deque
from dataclasses import dataclass
from typing import Optional, Callable
from enum import IntEnum

from src.utils.logging_config import get_logger

logger = get_logger(__name__)


class BufferEvent(IntEnum):
    """Audio buffer events"""
    NORMAL = 0
    UNDERRUN = 1      # Buffer empty, need more data
    OVERFLOW = 2      # Buffer full, dropping data
    GLITCH = 3        # Audio discontinuity detected
    RECOVERED = 4     # Buffer recovered from error


@dataclass
class BufferStats:
    """Audio buffer statistics"""
    current_size: int = 0
    max_size: int = 0
    underrun_count: int = 0
    overflow_count: int = 0
    glitch_count: int = 0
    total_frames: int = 0
    dropped_frames: int = 0
    avg_latency_ms: float = 0.0


class AudioBufferManager:
    """
    Enhanced audio buffer manager with glitch detection and recovery

    Features:
    - Adaptive buffer size based on network conditions
    - Glitch detection (sequence number gaps)
    - Automatic recovery from underruns/overflows
    - Buffer statistics for monitoring
    - Non-blocking write with backpressure notification
    """

    # Default buffer settings
    DEFAULT_MIN_FRAMES = 5      # ~300ms minimum buffer
    DEFAULT_MAX_FRAMES = 50      # ~3000ms maximum buffer
    TARGET_FRAMES = 15           # ~900ms target buffer

    # Frame duration at 16kHz, 60ms frame = 960 samples
    FRAME_DURATION_MS = 60

    def __init__(
        self,
        min_frames: int = DEFAULT_MIN_FRAMES,
        max_frames: int = DEFAULT_MAX_FRAMES,
        target_frames: int = TARGET_FRAMES,
        event_callback: Optional[Callable[[BufferEvent, dict], None]] = None
    ):
        """
        Initialize audio buffer manager

        Args:
            min_frames: Minimum buffer size (frames)
            max_frames: Maximum buffer size (frames)
            target_frames: Target buffer size for optimal latency
            event_callback: Optional callback for buffer events
        """
        self._min_frames = min_frames
        self._max_frames = max_frames
        self._target_frames = target_frames

        # Buffer storage: deque of (sequence_number, audio_data, timestamp)
        self._buffer: deque[tuple[int, np.ndarray, float]] = deque()

        # Thread safety
        self._lock = threading.Lock()

        # Sequence tracking
        self._expected_seq = 0
        self._last_seq = -1

        # Statistics
        self._stats = BufferStats(max_size=max_frames)

        # Event callback
        self._event_callback = event_callback

        # Timing
        self._last_write_time = 0
        self._buffer_underrun_time = 0

        # Control flags
        self._is_running = False
        self._muted = False

        logger.info(
            f"🎚️ AudioBufferManager initialized: "
            f"min={min_frames}, max={max_frames}, target={target_frames} frames"
        )

    def start(self):
        """Start buffer manager"""
        self._is_running = True
        self._clear()
        logger.info("🎚️ AudioBufferManager started")

    def stop(self):
        """Stop buffer manager"""
        self._is_running = False
        self._clear()
        logger.info("🎚️ AudioBufferManager stopped")

    def write(
        self,
        audio_data: np.ndarray,
        seq: Optional[int] = None,
        timestamp: Optional[float] = None
    ) -> bool:
        """
        Write audio data to buffer (non-blocking)

        Args:
            audio_data: Audio data as numpy array
            seq: Sequence number for glitch detection
            timestamp: Timestamp in seconds

        Returns:
            True if data was written, False if dropped (overflow)
        """
        if not self._is_running or self._muted:
            return False

        with self._lock:
            # Auto-generate sequence if not provided
            if seq is None:
                seq = self._last_seq + 1 if self._last_seq >= 0 else 0

            # Check for glitches (sequence gaps)
            if self._last_seq >= 0 and seq != self._last_seq + 1:
                gap = seq - self._last_seq - 1
                logger.warning(f"🎚️ Audio glitch detected: {gap} frames missing")
                self._stats.glitch_count += gap
                self._notify_event(BufferEvent.GLITCH, {"gap": gap, "seq": seq})

            # Check buffer overflow
            if len(self._buffer) >= self._max_frames:
                # Drop oldest frame
                self._buffer.popleft()
                self._stats.overflow_count += 1
                self._stats.dropped_frames += 1
                self._notify_event(BufferEvent.OVERFLOW, {"size": len(self._buffer)})

            # Add to buffer
            if timestamp is None:
                timestamp = time.time()

            self._buffer.append((seq, audio_data, timestamp))
            self._last_seq = seq
            self._stats.total_frames += 1
            self._stats.current_size = len(self._buffer)
            self._last_write_time = time.time()

            return True

    def read(self, num_frames: int = 1) -> Optional[np.ndarray]:
        """
        Read audio data from buffer (blocking for available data)

        Args:
            num_frames: Number of frames to read

        Returns:
            Concatenated audio data or None if buffer empty
        """
        if not self._is_running:
            return None

        with self._lock:
            if len(self._buffer) == 0:
                # Buffer underrun
                if self._stats.current_size > 0:  # Was just non-empty
                    self._stats.underrun_count += 1
                    self._buffer_underrun_time = time.time()
                    self._notify_event(BufferEvent.UNDERRUN, {"size": 0})

                self._stats.current_size = 0
                return None

            # Recover from underrun
            if self._buffer_underrun_time > 0:
                recovery_time = time.time() - self._buffer_underrun_time
                self._notify_event(BufferEvent.RECOVERED, {"duration": recovery_time})
                self._buffer_underrun_time = 0

            # Read requested frames (or less if not available)
            frames_to_read = min(num_frames, len(self._buffer))
            result_frames = []

            for _ in range(frames_to_read):
                if not self._buffer:
                    break
                seq, audio_data, _ = self._buffer.popleft()
                result_frames.append(audio_data)

            self._stats.current_size = len(self._buffer)

            if result_frames:
                return np.concatenate(result_frames)
            return None

    def get_stats(self) -> BufferStats:
        """Get current buffer statistics"""
        with self._lock:
            # Calculate average latency
            if self._buffer:
                oldest_timestamp = self._buffer[0][2]
                newest_timestamp = self._buffer[-1][2]
                buffer_duration = newest_timestamp - oldest_timestamp
                self._stats.avg_latency_ms = buffer_duration * 1000
            else:
                self._stats.avg_latency_ms = 0.0

            # Return copy of stats
            return BufferStats(
                current_size=self._stats.current_size,
                max_size=self._max_frames,
                underrun_count=self._stats.underrun_count,
                overflow_count=self._stats.overflow_count,
                glitch_count=self._stats.glitch_count,
                total_frames=self._stats.total_frames,
                dropped_frames=self._stats.dropped_frames,
                avg_latency_ms=self._stats.avg_latency_ms
            )

    def clear(self):
        """Clear all buffered audio data"""
        with self._lock:
            self._clear()

    def _clear(self):
        """Internal clear without lock"""
        self._buffer.clear()
        self._expected_seq = 0
        self._last_seq = -1
        self._stats.current_size = 0
        self._buffer_underrun_time = 0

    def get_buffer_level(self) -> float:
        """
        Get buffer level as percentage (0-100)

        Returns:
            Buffer level percentage relative to max size
        """
        with self._lock:
            if self._max_frames == 0:
                return 0.0
            return (self._stats.current_size / self._max_frames) * 100.0

    def is_low_buffer(self) -> bool:
        """Check if buffer is below minimum level"""
        with self._lock:
            return self._stats.current_size < self._min_frames

    def is_high_buffer(self) -> bool:
        """Check if buffer is above target level"""
        with self._lock:
            return self._stats.current_size > self._target_frames

    def get_status_dict(self) -> dict:
        """
        Get buffer status as dictionary for monitoring

        Returns:
            Dictionary with buffer status information
        """
        stats = self.get_stats()
        return {
            "size_frames": stats.current_size,
            "size_ms": stats.current_size * self.FRAME_DURATION_MS,
            "max_frames": self._max_frames,
            "level_percent": self.get_buffer_level(),
            "underruns": stats.underrun_count,
            "overflows": stats.overflow_count,
            "glitches": stats.glitch_count,
            "dropped_frames": stats.dropped_frames,
            "total_frames": stats.total_frames,
            "latency_ms": stats.avg_latency_ms,
            "is_low": self.is_low_buffer(),
            "is_high": self.is_high_buffer()
        }

    def mute(self, muted: bool = True):
        """Mute/unmute audio output"""
        self._muted = muted
        if muted:
            self.clear()
        logger.debug(f"Audio buffer muted: {muted}")

    def reset_stats(self):
        """Reset buffer statistics"""
        with self._lock:
            self._stats = BufferStats(max_size=self._max_frames)

    def _notify_event(self, event: BufferEvent, data: dict):
        """Notify event callback if set"""
        if self._event_callback:
            try:
                self._event_callback(event, data)
            except Exception as e:
                logger.error(f"Error in buffer event callback: {e}")


class AdaptiveAudioBuffer(AudioBufferManager):
    """
    Adaptive audio buffer that adjusts size based on network conditions

    Automatically increases buffer size when glitches are detected
    and decreases when stable for extended period
    """

    STABLE_THRESHOLD_SECONDS = 30  # Consider stable after 30 seconds without issues
    ADJUSTMENT_STEP = 5            # Add/remove 5 frames per adjustment

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._last_issue_time = time.time()
        self._last_adjustment_time = time.time()

    def write(self, *args, **kwargs) -> bool:
        """Write with adaptive buffer size adjustment"""
        result = super().write(*args, **kwargs)

        # Check for recent issues and adjust buffer size
        self._maybe_adjust_buffer_size()

        return result

    def _maybe_adjust_buffer_size(self):
        """Adjust buffer size based on recent activity"""
        now = time.time()
        time_since_issue = now - self._last_issue_time
        time_since_adjustment = now - self._last_adjustment_time

        # Only adjust every 5 seconds minimum
        if time_since_adjustment < 5:
            return

        stats = self.get_stats()

        # Had recent issues - increase buffer
        if stats.underrun_count > 0 or stats.overflow_count > 0 or stats.glitch_count > 0:
            if self._max_frames < 100:  # Cap at 100 frames (6 seconds)
                self._max_frames = min(100, self._max_frames + self.ADJUSTMENT_STEP)
                logger.info(f"🎚️ Increased buffer to {self._max_frames} frames")
                self._last_issue_time = now
                self._last_adjustment_time = now

        # Been stable for a while - decrease buffer
        elif time_since_issue > self.STABLE_THRESHOLD_SECONDS:
            if self._max_frames > self._max(self.DEFAULT_MIN_FRAMES, self._target_frames * 2):
                self._max_frames = max(
                    self._max(self.DEFAULT_MIN_FRAMES, self._target_frames * 2),
                    self._max_frames - self.ADJUSTMENT_STEP
                )
                logger.info(f"🎚️ Decreased buffer to {self._max_frames} frames (stable)")
                self._last_adjustment_time = now

    @staticmethod
    def _max(a: int, b: int) -> int:
        return max(a, b)
