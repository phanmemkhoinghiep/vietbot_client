#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Xiaozhi Audio Codec
Xử lý âm thanh: Opus encoding/decoding, sounddevice input/output
Dựa trên audio_codec.py từ reference code
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
    logging.warning("⚠️ opuslib not available, install with: pip install opuslib")

try:
    import soxr
    SOXR_AVAILABLE = True
except ImportError:
    SOXR_AVAILABLE = False
    logging.warning("⚠️ soxr not available, audio resampling may have lower quality")

from xiaozhi_config import get_config

logger = logging.getLogger(__name__)


class AudioConfig:
    """Audio configuration constants"""
    INPUT_SAMPLE_RATE = 16000
    OUTPUT_SAMPLE_RATE = 16000  # Use 16K for output too (Opus decoder handles resampling)
    CHANNELS = 1
    FRAME_DURATION_MS = 60
    FRAME_SIZE = int(INPUT_SAMPLE_RATE * FRAME_DURATION_MS / 1000)
    DTYPE = np.int16


class XiaozhiAudioCodec:
    """
    Audio codec cho xiaozhi client

    Chức năng:
    - Thu âm từ microphone, encode Opus, gửi lên server
    - Nhận Opus audio từ server, decode, phát ra speaker
    - Hỗ trợ resampling
    """

    def __init__(self):
        self.config = get_config()

        # Device IDs
        self.input_device_id = self.config.get("AUDIO.input_device_id")
        self.input_device_name = self.config.get("AUDIO.input_device_name")
        self.output_device_id = self.config.get("AUDIO.output_device_id")
        self.output_device_name = self.config.get("AUDIO.output_device_name")

        # Sample rates
        self.input_sample_rate = self.config.get("AUDIO.INPUT_SAMPLE_RATE", AudioConfig.INPUT_SAMPLE_RATE)
        self.output_sample_rate = self.config.get("AUDIO.OUTPUT_SAMPLE_RATE", AudioConfig.OUTPUT_SAMPLE_RATE)

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

        # Device info
        self._device_input_frame_size = None

        # Input buffer for accumulating samples (from variable callback sizes)
        self._input_buffer = None
        self._input_buffer_lock = threading.Lock()

        logger.info("🎤 XiaozhiAudioCodec initialized")

    def set_encoded_audio_callback(self, callback: Callable[[bytes, int], None]):
        """
        Set callback for encoded audio data

        Args:
            callback: Function to call with (encoded_data, timestamp_ms)
        """
        self._encoded_audio_callback = callback

    async def initialize(self):
        """Initialize audio devices and codec"""
        try:
            # Auto-detect devices if not specified
            if self.input_device_id is None:
                self._auto_detect_input_device()
            if self.output_device_id is None:
                self._auto_detect_output_device()

            # Get device info for logging
            input_info = sd.query_devices(self.input_device_id)
            output_info = sd.query_devices(self.output_device_id)

            # Use 16K directly for both input and output (like reference code)
            # WM8960 with ALSA supports 16000Hz directly
            self.device_input_sample_rate = AudioConfig.INPUT_SAMPLE_RATE  # 16000Hz
            self.device_output_sample_rate = AudioConfig.INPUT_SAMPLE_RATE  # 16000Hz (use same for input/output)

            logger.info(f"📊 Input device: {input_info['name']}")
            logger.info(f"📊 Output device: {output_info['name']}")
            logger.info(f"📊 Using {self.device_input_sample_rate}Hz for both input and output")

            # Calculate frame size
            self._device_input_frame_size = AudioConfig.FRAME_SIZE  # 960 samples (60ms at 16K)

            # Initialize Opus codec
            if not OPUS_AVAILABLE:
                # For now, just log warning and continue without Opus
                # Server may handle raw PCM or we can add support later
                logger.warning("⚠️ opuslib not available, audio encoding/decoding will be disabled")
                logger.warning("   Install with: pip install opuslib")
                self.encoder = None
                self.decoder = None
            else:
                self.encoder = opuslib.Encoder(
                    AudioConfig.INPUT_SAMPLE_RATE,
                    AudioConfig.CHANNELS,
                    opuslib.APPLICATION_AUDIO
                )
                self.decoder = opuslib.Decoder(
                    AudioConfig.INPUT_SAMPLE_RATE,  # Use 16K for decoder too
                    AudioConfig.CHANNELS
                )
                logger.info("✅ Opus codec initialized")

            # Set default sounddevice parameters
            sd.default.channels = AudioConfig.CHANNELS
            sd.default.dtype = AudioConfig.DTYPE

            logger.info("✅ Audio codec initialized successfully")

        except Exception as e:
            logger.error(f"❌ Failed to initialize audio: {e}", exc_info=True)
            await self.close()
            raise

    def _auto_detect_input_device(self):
        """Auto-detect microphone input device"""
        try:
            devices = sd.query_devices()
            # Prioritize wm8960 or sound card
            for i, device in enumerate(devices):
                name = device.get('name', '').lower()
                if ('wm8960' in name or 'sound card' in name or
                    'audio' in name) and device.get('max_input_channels', 0) > 0:
                    self.input_device_id = i
                    self.input_device_name = device['name']
                    logger.info(f"🎤 Auto-detected input device: {device['name']}")
                    return

            # Fallback: use default input device
            default_device = sd.default.device[0]
            if default_device is not None:
                self.input_device_id = default_device
                device_info = sd.query_devices(default_device)
                self.input_device_name = device_info['name']
                logger.info(f"🎤 Using default input device: {device_info['name']}")

        except Exception as e:
            logger.warning(f"⚠️ Could not auto-detect input device: {e}")

    def _auto_detect_output_device(self):
        """Auto-detect speaker output device"""
        try:
            devices = sd.query_devices()
            # Prioritize wm8960 or sound card
            for i, device in enumerate(devices):
                name = device.get('name', '').lower()
                if ('wm8960' in name or 'sound card' in name or
                    'speaker' in name) and device.get('max_output_channels', 0) > 0:
                    self.output_device_id = i
                    self.output_device_name = device['name']
                    logger.info(f"🔊 Auto-detected output device: {device['name']}")
                    return

            # Fallback: use default output device
            default_device = sd.default.device[1]
            if default_device is not None:
                self.output_device_id = default_device
                device_info = sd.query_devices(default_device)
                self.output_device_name = device_info['name']
                logger.info(f"🔊 Using default output device: {device_info['name']}")

        except Exception as e:
            logger.warning(f"⚠️ Could not auto-detect output device: {e}")

    async def start_streams(self):
        """Start audio input and output streams"""
        try:
            self._is_running = True

            # Clear input buffer
            with self._input_buffer_lock:
                self._input_buffer = np.array([], dtype=np.int16)

            # Start input stream (microphone) - 16K direct like reference code
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
                    frame_size = AudioConfig.FRAME_SIZE  # 960 samples
                    while len(self._input_buffer) >= frame_size:
                        frame = self._input_buffer[:frame_size]
                        self._input_buffer = self._input_buffer[frame_size:]

                        # Encode with Opus (if available)
                        if self.encoder:
                            encoded = self.encoder.encode(frame.tobytes(), frame_size)
                        else:
                            # No Opus available, send raw PCM (server may or may not support)
                            encoded = frame.tobytes()
                            logger.debug("⚠️ Sending raw PCM (Opus not available)")

                        # Send via callback
                        if self._encoded_audio_callback:
                            timestamp = int(time.time() * 1000) + self._audio_timestamp
                            self._encoded_audio_callback(encoded, timestamp)
                            self._audio_timestamp += 1

            # Create input stream - 16K direct (like reference code)
            self.input_stream = sd.InputStream(
                samplerate=AudioConfig.INPUT_SAMPLE_RATE,  # 16000Hz
                device=self.input_device_id,
                channels=AudioConfig.CHANNELS,
                dtype=AudioConfig.DTYPE,
                blocksize=0,
                callback=audio_callback
            )

            # Start input stream
            self.input_stream.start()
            logger.info("🎤 Input stream started")

            # Start output stream - 16K direct (like reference code)
            self.output_stream = sd.OutputStream(
                samplerate=AudioConfig.INPUT_SAMPLE_RATE,  # 16000Hz
                device=self.output_device_id,
                channels=AudioConfig.CHANNELS,
                dtype=AudioConfig.DTYPE,
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
                logger.info("🛑 Input stream stopped")

            if self.output_stream:
                self.output_stream.stop()
                self.output_stream.close()
                self.output_stream = None
                logger.info("🛑 Output stream stopped")

        except Exception as e:
            logger.error(f"❌ Error stopping streams: {e}")

    async def write_audio(self, data: bytes):
        """
        Write audio data to output buffer for playback

        Args:
            data: Raw audio data (PCM)
        """
        try:
            # Decode from Opus if needed
            if self.decoder:
                # If data is Opus encoded, decode it
                try:
                    decoded = self.decoder.decode(data, len(data), AudioConfig.FRAME_SIZE)
                except Exception:
                    # Might be raw PCM, try to play directly
                    decoded = data
            else:
                decoded = data

            # Convert to numpy array (decoded at 16K by Opus decoder)
            audio_array = np.frombuffer(decoded, dtype=np.int16)

            # No resampling needed - using 16K directly (like reference code)

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
            # Additional wait for DAC buffer
            await asyncio.sleep(0.2)
        except Exception as e:
            logger.debug(f"Error waiting for audio complete: {e}")

    async def close(self):
        """Close audio codec and release resources"""
        self._is_closing = True
        self._is_running = False

        # Stop streams
        await self.stop_streams()

        # Close codec
        if self.encoder:
            self.encoder = None
        if self.decoder:
            self.decoder = None

        logger.info("🎤 Audio codec closed")


# Test
async def test_audio_codec():
    """Test audio codec"""
    codec = XiaozhiAudioCodec()
    await codec.initialize()
    await codec.start_streams()

    # Test for 5 seconds
    await asyncio.sleep(5)

    await codec.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    asyncio.run(test_audio_codec())
