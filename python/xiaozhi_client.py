#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Xiaozhi Client - Main client for xiaozhi server
Hỗ trợ cả MQTT (ưu tiên) và WebSocket (fallback) protocols

Kết hợp:
- XiaozhiMqttProtocol - MQTT + UDP protocol (faster, lower latency)
- XiaozhiProtocol - WebSocket protocol (fallback)
- XiaozhiAudioCodec - Audio encoding/decoding
- State management - IDLE, LISTENING, THINKING, SPEAKING
"""

import asyncio
import logging
import json
import time
from enum import Enum, auto
from typing import Optional, Callable, Dict, Any

from xiaozhi_config import get_config
from xiaozhi_protocol import XiaozhiProtocol
from xiaozhi_mqtt_protocol import XiaozhiMqttProtocol
from xiaozhi_audio_codec import XiaozhiAudioCodec

logger = logging.getLogger(__name__)


class DeviceState(Enum):
    """Device states matching xiaozhi server"""
    IDLE = auto()
    CONNECTING = auto()
    LISTENING = auto()
    SPEAKING = auto()


class ListeningMode(Enum):
    """Listening modes"""
    AUTO_STOP = "auto_stop"
    MANUAL = "manual"
    REALTIME = "realtime"


class XiaozhiClient:
    """
    Xiaozhi Client - Hỗ trợ MQTT (ưu tiên) và WebSocket

    Ưu tiên kết nối:
    1. MQTT + UDP (nếu có mqtt_config) - Tốc độ nhanh hơn
    2. WebSocket (fallback)

    Kết hợp:
    - Protocol (MQTT or WebSocket)
    - Audio codec (XiaozhiAudioCodec)
    - State management
    - Message handling
    """

    def __init__(
        self,
        config: dict = None,
        led_controller=None,
        jwt_token: str = None,
        display_manager=None,
        device_id: str = None,
        mac_address: str = None,
        ws_url: str = None,
        mqtt_config: Dict = None,
        state_callback: Callable[[str], None] = None,
        sound_player=None,
    ):
        """
        Initialize Xiaozhi client

        Args:
            config: Configuration dictionary (optional, will use default if None)
            led_controller: LED controller for visual feedback
            jwt_token: JWT token for authentication
            display_manager: Display manager for UI updates
            device_id: Device ID (MAC address)
            mac_address: MAC address (deprecated, use device_id)
            ws_url: WebSocket URL (from OTA endpoint, fallback)
            mqtt_config: MQTT config from OTA endpoint (prioritized)
            state_callback: Callback when state changes (for display updates)
            sound_player: Sound player for UI sounds
        """
        self.config = config or {}
        self.led_controller = led_controller
        self.display_manager = display_manager
        self.state_callback = state_callback
        self.sound_player = sound_player

        # Use device_id or mac_address
        self.device_id = device_id or mac_address

        # Connection URLs from OTA endpoint
        self._ws_url = ws_url
        self._mqtt_config = mqtt_config

        # Determine protocol type
        self._use_mqtt = mqtt_config is not None

        # Set access token if provided
        if jwt_token:
            get_config().set_access_token(jwt_token)

        # Initialize protocol based on availability
        if self._use_mqtt:
            logger.info("📡 Using MQTT + UDP protocol (faster)")
            self.protocol = XiaozhiMqttProtocol(mqtt_config=mqtt_config)
        else:
            logger.info("🔌 Using WebSocket protocol")
            self.protocol = XiaozhiProtocol(ws_url=ws_url)

        self.audio_codec = XiaozhiAudioCodec()

        # State management
        self._state = DeviceState.IDLE
        self._state_lock = asyncio.Lock()
        self.listening_mode = ListeningMode.AUTO_STOP

        # Audio control
        self.conversation_active = False

        # Audio send queue (with backpressure)
        self._audio_send_queue: asyncio.Queue = None
        self._audio_send_task: asyncio.Task = None

        # Setup callbacks
        self._setup_callbacks()

        logger.info("🚀 XiaozhiClient initialized")

    def _setup_callbacks(self):
        """Setup protocol and audio callbacks"""

        # Audio callback - when codec encodes audio, send via protocol
        def on_encoded_audio(encoded_data: bytes, timestamp: int):
            if self._state == DeviceState.LISTENING and self.conversation_active:
                # Put in queue instead of creating task directly (backpressure)
                if self._audio_send_queue and not self._audio_send_queue.full():
                    try:
                        self._audio_send_queue.put_nowait((encoded_data, timestamp))
                    except asyncio.QueueFull:
                        pass  # Drop frame if queue is full (backpressure)

        self.audio_codec.set_encoded_audio_callback(on_encoded_audio)

        # Protocol callbacks
        self.protocol.on_connected(self._on_connected)
        self.protocol.on_disconnected(self._on_disconnected)
        self.protocol.on_json(self._on_json_message)
        self.protocol.on_audio(self._on_audio_data)

        # Set LED to off (IDLE)
        if self.led_controller:
            try:
                self.led_controller.set_state(DeviceState.IDLE)
            except Exception as e:
                logger.debug(f"LED error: {e}")

    async def _audio_send_loop(self):
        """Background task to send audio frames from queue"""
        while self.conversation_active or not self._audio_send_queue.empty():
            try:
                encoded_data, timestamp = await asyncio.wait_for(
                    self._audio_send_queue.get(),
                    timeout=0.1
                )
                await self.protocol.send_audio(encoded_data, timestamp)
            except asyncio.TimeoutError:
                continue
            except Exception as e:
                logger.error(f"❌ Error sending audio: {e}")

    def _on_connected(self):
        """Called when WebSocket connection is established"""
        logger.info("✅ WebSocket connected")

        # Update LED
        if self.led_controller:
            self.led_controller.set_state(DeviceState.IDLE)

    def _on_disconnected(self, reason: str = ""):
        """Called when WebSocket connection is lost"""
        logger.warning(f"⚠️ WebSocket disconnected: {reason}")

        # Reset state
        self._set_state(DeviceState.IDLE)

        # Update LED
        if self.led_controller:
            self.led_controller.set_state(DeviceState.IDLE)

    def _on_json_message(self, data: dict):
        """Handle incoming JSON messages from server"""
        msg_type = data.get("type", "")

        if msg_type == "stt":
            # STT transcript
            text = data.get("text", "")
            if text:
                logger.info(f">> {text}")
                self._update_display_text(text, "user")

        elif msg_type == "tts":
            # TTS control
            state = data.get("state", "")
            if state == "start":
                self._set_state(DeviceState.SPEAKING)
            elif state == "stop":
                self._set_state(DeviceState.LISTENING if self.listening_mode == ListeningMode.REALTIME else DeviceState.IDLE)
                # Play start sound after TTS finishes
                if self.sound_player:
                    asyncio.create_task(self.sound_player.play_async('start', volume=200))

        elif msg_type == "llm":
            # LLM emotion
            emotion = data.get("emotion", "")
            if emotion:
                logger.debug(f"Emotion: {emotion}")
                if self.led_controller and hasattr(self.led_controller, "set_emotion"):
                    self.led_controller.set_emotion(emotion)

    def _on_audio_data(self, data: bytes, timestamp: int):
        """Handle incoming audio data from server"""
        # Decode and play audio
        if self._state in [DeviceState.SPEAKING, DeviceState.LISTENING]:
            asyncio.create_task(self._play_audio_async(data))

    async def _play_audio_async(self, encoded_data: bytes):
        """Play audio data asynchronously"""
        try:
            # Check if decoder is available
            if not self.audio_codec.decoder:
                logger.warning("⚠️ No Opus decoder available, cannot play audio")
                return

            # Decode Opus
            decoded = self.audio_codec.decoder.decode(
                encoded_data,
                len(encoded_data),
                self.audio_codec.config.get("AUDIO.FRAME_DURATION_MS", 60)
            )

            # Convert to numpy array
            import numpy as np
            audio_array = np.frombuffer(decoded, dtype=np.int16)

            # Write to audio stream
            if self.audio_codec.output_stream:
                self.audio_codec.output_stream.write(audio_array)

        except Exception as e:
            logger.error(f"❌ Error playing audio: {e}")

    def _set_state(self, new_state: DeviceState):
        """Set device state and notify callbacks"""
        if self._state == new_state:
            return

        old_state = self._state
        self._state = new_state

        logger.debug(f"State: {old_state.name} -> {new_state.name}")

        # Update LED
        if self.led_controller:
            try:
                self.led_controller.set_state(new_state)
            except Exception as e:
                logger.debug(f"LED error: {e}")

        # Update display
        if self.display_manager:
            state_map = {
                DeviceState.IDLE: ("Sẵn sàng", "😊", "Chờ đánh thức"),
                DeviceState.CONNECTING: ("Đang kết nối", "🔄", "Đang kết nối..."),
                DeviceState.LISTENING: ("Đang nghe", "👂", "Đang nghe..."),
                DeviceState.SPEAKING: ("Đang nói", "🗣️", "Đang nói..."),
            }
            status, emoji, text = state_map.get(new_state, ("Unknown", "❓", ""))
            self.display_manager.update_display(status=status, emoji=emoji, text=text)

        # Notify callback
        if self.state_callback:
            state_name = new_state.name
            self.state_callback(state_name)

    def _update_display_text(self, text: str, role: str = "assistant"):
        """Update display with new text"""
        if self.display_manager:
            prefix = "Bạn:" if role == "user" else "Bot:"
            self.display_manager.update_display(text=f"{prefix} {text}")

    async def initialize(self):
        """Initialize the client"""
        logger.info("🔧 Initializing XiaozhiClient...")

        # Initialize audio codec
        await self.audio_codec.initialize()

        # Update config with device ID if set
        if self.device_id:
            get_config().set("SERVER.DEVICE_ID", self.device_id)

        logger.info("✅ XiaozhiClient initialized")

    async def connect(self, ws_url: str = None, mqtt_config: Dict = None) -> bool:
        """
        Connect to xiaozhi server

        Args:
            ws_url: Optional WebSocket URL (overrides stored URL)
            mqtt_config: Optional MQTT config (overrides stored config)

        Returns:
            True if connection successful, False otherwise
        """
        self._set_state(DeviceState.CONNECTING)

        # Update protocol if config changed
        if mqtt_config and not self._use_mqtt:
            # Switch to MQTT
            logger.info("📡 Switching to MQTT protocol")
            self._use_mqtt = True
            self.protocol = XiaozhiMqttProtocol(mqtt_config=mqtt_config)
        elif ws_url and self._use_mqtt and not mqtt_config:
            # Switch to WebSocket
            logger.info("🔌 Switching to WebSocket protocol")
            self._use_mqtt = False
            self.protocol = XiaozhiProtocol(ws_url=ws_url)

        # Connect based on protocol type
        if self._use_mqtt:
            logger.info("📡 Connecting via MQTT + UDP...")
            success = await self.protocol.connect(mqtt_config=mqtt_config or self._mqtt_config)
        else:
            url = ws_url or self._ws_url
            if url:
                logger.info(f"🔌 Connecting to xiaozhi server: {url}")
            else:
                logger.info("🔌 Connecting to xiaozhi server...")
            success = await self.protocol.connect(ws_url=url)

        if success:
            # Start audio streams
            await self.audio_codec.start_streams()
            logger.info("✅ Connected to xiaozhi server")
            return True
        else:
            logger.error("❌ Failed to connect to xiaozhi server")
            self._set_state(DeviceState.IDLE)
            return False

    def is_connected(self) -> bool:
        """Check if connected to server"""
        return self.protocol.is_connected()

    async def start_conversation(self) -> bool:
        """
        Start a conversation session

        Returns:
            True if started successfully, False otherwise
        """
        if not self.is_connected():
            logger.error("❌ Not connected to server")
            return False

        try:
            # Set state FIRST (so audio callback knows we're listening)
            self._set_state(DeviceState.LISTENING)

            # Create audio send queue (max 50 frames = 3 seconds at 60ms/frame)
            self._audio_send_queue = asyncio.Queue(maxsize=50)

            # Start audio send loop
            self._audio_send_task = asyncio.create_task(self._audio_send_loop())

            # Then set conversation active
            self.conversation_active = True

            # Send start_listening command LAST
            if self.listening_mode == ListeningMode.REALTIME:
                await self.protocol.send_start_listening("realtime")
            else:
                await self.protocol.send_start_listening("auto_stop")

            logger.info("✅ Conversation started")

            return True

        except Exception as e:
            logger.error(f"❌ Failed to start conversation: {e}")
            self.conversation_active = False
            self._set_state(DeviceState.IDLE)
            return False

    async def stop_conversation(self) -> bool:
        """
        Stop the current conversation

        Returns:
            True if stopped successfully, False otherwise
        """
        try:
            # Stop audio encoding FIRST
            self.conversation_active = False

            # Send stop_listening command
            await self.protocol.send_stop_listening()

            # Wait for audio send task to finish
            if self._audio_send_task and not self._audio_send_task.done():
                try:
                    await asyncio.wait_for(self._audio_send_task, timeout=1.0)
                except asyncio.TimeoutError:
                    self._audio_send_task.cancel()
                    try:
                        await self._audio_send_task
                    except asyncio.CancelledError:
                        pass

            self._audio_send_task = None
            self._audio_send_queue = None

            # Wait for audio to finish playing
            await self.audio_codec.wait_for_audio_complete()

            self._set_state(DeviceState.IDLE)
            logger.info("✅ Conversation stopped")

            return True

        except Exception as e:
            logger.error(f"❌ Failed to stop conversation: {e}")
            self._set_state(DeviceState.IDLE)
            return False

    async def abort_speaking(self, reason: str = "user"):
        """
        Abort speaking

        Args:
            reason: Reason for aborting
        """
        try:
            await self.protocol.send_abort_speaking(reason)
            await self.audio_codec.clear_audio_queue()
            self._set_state(DeviceState.IDLE)
            logger.info(f"✅ Speaking aborted: {reason}")
        except Exception as e:
            logger.error(f"❌ Failed to abort speaking: {e}")

    def is_conversation_active(self) -> bool:
        """Check if a conversation is currently active"""
        return self.conversation_active

    async def disconnect(self):
        """Disconnect from server"""
        logger.info("🔌 Disconnecting...")

        self.conversation_active = False

        # Close audio channel
        await self.protocol.close_audio_channel()

        # Stop audio streams
        await self.audio_codec.stop_streams()

        # Reset state
        self._set_state(DeviceState.IDLE)

        logger.info("✅ Disconnected")


# Test
async def test_client():
    """Test xiaozhi client"""
    client = XiaozhiClient()

    def state_callback(state_name):
        logger.info(f"State changed: {state_name}")

    client.state_callback = state_callback

    await client.initialize()

    if await client.connect():
        # Test conversation
        await asyncio.sleep(2)
        await client.start_conversation()
        await asyncio.sleep(5)
        await client.stop_conversation()
        await client.disconnect()

    logger.info("✅ Test completed")


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.DEBUG,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    asyncio.run(test_client())
