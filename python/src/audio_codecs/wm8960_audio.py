"""
WM8960 Audio Codec for Whisplay HAT
Simplified version - 16kHz direct, Opus encoding
"""
import asyncio
import numpy as np
import sounddevice as sd
import logging
import threading
import time
from collections import deque
from typing import Optional, Callable

try:
    import opuslib
    OPUS_AVAILABLE = True
except ImportError:
    OPUS_AVAILABLE = False
    logging.warning("⚠️ opuslib not available")

from src.constants.system import SystemConstants

logger = logging.getLogger(__name__)


class WM8960AudioCodec:
    """
    Audio codec for WM8960 on Whisplay HAT

    Features:
    - 16kHz input/output (direct, no resampling)
    - Opus encoding/decoding
    - Auto-detect WM8960 device
    """

    INPUT_SAMPLE_RATE = SystemConstants.INPUT_SAMPLE_RATE  # 16000
    OUTPUT_SAMPLE_RATE = SystemConstants.OUTPUT_SAMPLE_RATE  # 16000
    CHANNELS = SystemConstants.CHANNELS  # 1
    FRAME_SIZE = SystemConstants.FRAME_SIZE  # 960 samples (60ms at 16K)

    def __init__(self):
        # Opus encoder/decoder
        self.encoder = None
        self.decoder = None

        # Audio streams
        self.input_stream = None
        self.output_stream = None

        # Output buffer
        self.output_buffer = deque(maxlen=500)
        self.output_lock = threading.Lock()

        # Control flags
        self._is_closing = False
        self._is_running = False

        # Callback for encoded audio
        self._encoded_audio_callback: Optional[Callable[[bytes, int], None]] = None

        # Audio timestamp
        self._audio_timestamp = 0

        # Input buffer
        self._input_buffer = None
        self._input_buffer_lock = threading.Lock()

        # Device IDs
        self.input_device_id = None
        self.output_device_id = None

        logger.info("🎤 WM8960AudioCodec initialized")

    def set_encoded_audio_callback(self, callback: Callable[[bytes, int], None]):
        """Set callback for encoded audio data"""
        self._encoded_audio_callback = callback

    async def initialize(self):
        """Initialize audio devices and codec"""
        try:
            # Auto-detect WM8960 devices
            self._auto_detect_devices()

            # Initialize Opus codec
            if not OPUS_AVAILABLE:
                logger.warning("⚠️ opuslib not available, audio encoding/decoding will be disabled")
                self.encoder = None
                self.decoder = None
            else:
                self.encoder = opuslib.Encoder(
                    self.INPUT_SAMPLE_RATE,
                    self.CHANNELS,
                    opuslib.APPLICATION_AUDIO
                )
                self.decoder = opuslib.Decoder(
                    self.OUTPUT_SAMPLE_RATE,
                    self.CHANNELS
                )
                logger.info("✅ Opus codec initialized")

            # Set default sounddevice parameters
            sd.default.channels = self.CHANNELS
            sd.default.dtype = np.int16

            logger.info("✅ Audio codec initialized successfully")

        except Exception as e:
            logger.error(f"❌ Failed to initialize audio: {e}", exc_info=True)
            await self.close()
            raise

    def _auto_detect_devices(self):
        """Auto-detect WM8960 audio devices"""
        try:
            devices = sd.query_devices()
            for i, device in enumerate(devices):
                name = device.get('name', '').lower()
                # Look for WM8960
                if 'wm8960' in name:
                    if device.get('max_input_channels', 0) > 0 and self.input_device_id is None:
                        self.input_device_id = i
                        logger.info(f"🎤 Found input device: {device['name']}")
                    if device.get('max_output_channels', 0) > 0 and self.output_device_id is None:
                        self.output_device_id = i
                        logger.info(f"🔊 Found output device: {device['name']}")

            # Fallback to default devices
            if self.input_device_id is None:
                self.input_device_id = sd.default.device[0]
                logger.info(f"🎤 Using default input device")
            if self.output_device_id is None:
                self.output_device_id = sd.default.device[1]
                logger.info(f"🔊 Using default output device")

        except Exception as e:
            logger.warning(f"⚠️ Could not auto-detect devices: {e}")

    async def start_streams(self):
        """Start audio input and output streams"""
        try:
            self._is_running = True

            # Clear input buffer
            with self._input_buffer_lock:
                self._input_buffer = np.array([], dtype=np.int16)

            # Start input stream (microphone) - 16kHz direct
            def audio_callback(indata, frames, time_info, status):
                if status.output_underflow:
                    logger.warning("⚠️ Audio input underflow")
                    return

                # Get audio data (already at 16K)
                audio_data = indata.flatten()

                # Accumulate in buffer
                with self._input_buffer_lock:
                    self._input_buffer = np.concatenate([self._input_buffer, audio_data])

                    # Split into frames (960 samples for 60ms at 16K)
                    frame_size = self.FRAME_SIZE
                    while len(self._input_buffer) >= frame_size:
                        frame = self._input_buffer[:frame_size]
                        self._input_buffer = self._input_buffer[frame_size:]

                        # Encode with Opus (if available)
                        if self.encoder:
                            encoded = self.encoder.encode(frame.tobytes(), frame_size)
                        else:
                            encoded = frame.tobytes()

                        # Send via callback
                        if self._encoded_audio_callback:
                            timestamp = int(time.time() * 1000) + self._audio_timestamp
                            self._encoded_audio_callback(encoded, timestamp)
                            self._audio_timestamp += 1

            # Create input stream
            self.input_stream = sd.InputStream(
                samplerate=self.INPUT_SAMPLE_RATE,
                device=self.input_device_id,
                channels=self.CHANNELS,
                dtype=np.int16,
                blocksize=0,
                callback=audio_callback
            )

            # Start input stream
            self.input_stream.start()
            logger.info("🎤 Input stream started")

            # Start output stream
            self.output_stream = sd.OutputStream(
                samplerate=self.OUTPUT_SAMPLE_RATE,
                device=self.output_device_id,
                channels=self.CHANNELS,
                dtype=np.int16,
                blocksize=0,
            )
            self.output_stream.start()
            logger.info("🔊 Output stream started")

        except Exception as e:
            logger.error(f"❌ Failed to start streams: {e}", exc_info=True)
            await self.close()
            raise

    async def stop_streams(self):
        """Stop audio streams"""
        try:
            self._is_running = False

            if self.input_stream:
                self.input_stream.stop()
                self.input_stream.close()
                self.input_stream = None

            if self.output_stream:
                self.output_stream.stop()
                self.output_stream.close()
                self.output_stream = None

            logger.info("🛑 Audio streams stopped")

        except Exception as e:
            logger.error(f"❌ Error stopping streams: {e}")

    async def write_audio(self, data: bytes):
        """Write audio data to output buffer for playback"""
        try:
            # Decode from Opus if needed
            if self.decoder:
                try:
                    decoded = self.decoder.decode(data, len(data), self.FRAME_SIZE)
                except Exception:
                    decoded = data
            else:
                decoded = data

            # Convert to numpy array
            audio_array = np.frombuffer(decoded, dtype=np.int16)

            # Add to output buffer
            with self.output_lock:
                self.output_buffer.append(audio_array)

            # Write to stream
            if self.output_stream:
                self.output_stream.write(audio_array)

        except Exception as e:
            logger.error(f"❌ Error writing audio: {e}")

    def clear_audio_queue(self):
        """Clear output audio buffer"""
        with self.output_lock:
            self.output_buffer.clear()

    async def wait_for_audio_complete(self):
        """Wait for all queued audio to finish playing"""
        try:
            while self.output_buffer:
                await asyncio.sleep(0.1)
            await asyncio.sleep(0.2)
        except Exception as e:
            logger.debug(f"Error waiting for audio complete: {e}")

    async def close(self):
        """Close audio codec and release resources"""
        self._is_closing = True
        self._is_running = False

        await self.stop_streams()

        if self.encoder:
            self.encoder = None
        if self.decoder:
            self.decoder = None

        logger.info("🎤 Audio codec closed")
