#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Xiaozhi WebSocket Protocol
Xử lý kết nối WebSocket với xiaozhi server theo đúng chuẩn ESP32 protocol
"""

import asyncio
import json
import struct
import logging
import time
from typing import Optional, Callable, Any, Dict
import websockets
from websockets.exceptions import ConnectionClosed

from xiaozhi_config import get_config, XiaozhiConfig

logger = logging.getLogger(__name__)


# Binary protocol structures (dựa trên ESP32)
class BinaryProtocolV2:
    """Binary Protocol Version 2 - same as ESP32"""
    FORMAT = "!HHHII"  # version(2) + type(2) + reserved(2) + timestamp(4) + payload_size(4)
    HEADER_SIZE = struct.calcsize(FORMAT)

    @staticmethod
    def encode(audio_data: bytes, timestamp: int) -> bytes:
        """Encode audio packet with v2 header"""
        payload_size = len(audio_data)
        header = struct.pack(BinaryProtocolV2.FORMAT,
                            2,  # version
                            0,  # type (0 = audio)
                            0,  # reserved
                            timestamp,
                            payload_size)
        return header + audio_data

    @staticmethod
    def decode(data: bytes) -> tuple:
        """Decode binary packet, return (payload, timestamp)"""
        if len(data) < BinaryProtocolV2.HEADER_SIZE:
            return b"", 0

        header = data[:BinaryProtocolV2.HEADER_SIZE]
        version, msg_type, reserved, timestamp, payload_size = struct.unpack(
            BinaryProtocolV2.FORMAT, header
        )
        payload = data[BinaryProtocolV2.HEADER_SIZE:BinaryProtocolV2.HEADER_SIZE + payload_size]
        return payload, timestamp


class BinaryProtocolV3:
    """Binary Protocol Version 3 - simplified"""
    FORMAT = "!BBH"  # type(1) + reserved(1) + payload_size(2)
    HEADER_SIZE = struct.calcsize(FORMAT)

    @staticmethod
    def encode(audio_data: bytes, timestamp: int = 0) -> bytes:
        """Encode audio packet with v3 header"""
        payload_size = len(audio_data)
        if payload_size > 65535:
            payload_size = 65535  # Max for uint16

        header = struct.pack(BinaryProtocolV3.FORMAT,
                            0,  # type (0 = audio)
                            0,  # reserved
                            payload_size)
        return header + audio_data

    @staticmethod
    def decode(data: bytes) -> tuple:
        """Decode binary packet, return (payload, 0)"""
        if len(data) < BinaryProtocolV3.HEADER_SIZE:
            return b"", 0

        header = data[:BinaryProtocolV3.HEADER_SIZE]
        msg_type, reserved, payload_size = struct.unpack(
            BinaryProtocolV3.FORMAT, header
        )
        payload = data[BinaryProtocolV3.HEADER_SIZE:BinaryProtocolV3.HEADER_SIZE + payload_size]
        return payload, 0


class XiaozhiProtocol:
    """
    Xiaozhi WebSocket Protocol Client

    Kết nối và giao tiếp với xiaozhi server theo đúng chuẩn ESP32 protocol:
    - Hello handshake
    - Binary audio (Opus)
    - JSON messages (tts, stt, llm, iot, mcp, etc.)
    """

    # Callback types
    # on_audio: Callable[[bytes, int], None] - (audio_data, timestamp)
    # on_json: Callable[[Dict], None] - (json_message)
    # on_connected: Callable[[], None]
    # on_disconnected: Callable[[str], None] - (reason)
    # on_error: Callable[[str], None] - (error_message)

    def __init__(self, ws_url: str = None):
        self.config: XiaozhiConfig = get_config()
        self.websocket: Optional[websockets.WebSocketClientProtocol] = None
        self.connected = False
        self.protocol_version = self.config.get_protocol_version()
        # WebSocket URL - can be set explicitly or from config
        self._ws_url = ws_url

        # Audio parameters from server hello
        self.server_sample_rate = 24000
        self.server_frame_duration = 60

        # Server hello event (for synchronization)
        self._hello_received = asyncio.Event()

        # Callbacks
        self._on_audio: Optional[Callable[[bytes, int], None]] = None
        self._on_json: Optional[Callable[[Dict], None]] = None
        self._on_connected: Optional[Callable[[], None]] = None
        self._on_disconnected: Optional[Callable[[str], None]] = None
        self._on_error: Optional[Callable[[str], None]] = None

        # Reception timestamp for audio
        self._audio_timestamp = 0

        # Hello message
        self._hello_message = {
            "type": "hello",
            "version": self.protocol_version,
            "features": {
                "aec": self.config.is_aec_enabled(),
                "mcp": True,
            },
            "transport": "websocket",
            "audio_params": {
                "format": self.config.get("AUDIO.FORMAT", "opus"),
                "sample_rate": self.config.get("AUDIO.INPUT_SAMPLE_RATE", 16000),
                "channels": self.config.get("AUDIO.CHANNELS", 1),
                "frame_duration": self.config.get("AUDIO.FRAME_DURATION_MS", 60),
            }
        }

        logger.info(f"📡 XiaozhiProtocol initialized with v{self.protocol_version}")

    def on_audio(self, callback: Callable[[bytes, int], None]):
        """Set callback for incoming audio data"""
        self._on_audio = callback

    def on_json(self, callback: Callable[[Dict], None]):
        """Set callback for incoming JSON messages"""
        self._on_json = callback

    def on_connected(self, callback: Callable[[], None]):
        """Set callback for connection established"""
        self._on_connected = callback

    def on_disconnected(self, callback: Callable[[str], None]):
        """Set callback for disconnection"""
        self._on_disconnected = callback

    def on_error(self, callback: Callable[[str], None]):
        """Set callback for errors"""
        self._on_error = callback

    async def connect(self, ws_url: str = None) -> bool:
        """
        Connect to xiaozhi WebSocket server

        Args:
            ws_url: Optional WebSocket URL (overrides config)

        Returns:
            True if connection successful, False otherwise
        """
        # Use provided URL, stored URL, or config default
        url = ws_url or self._ws_url or self.config.get_server_url()
        device_id = self.config.get_device_id()
        client_id = self.config.get_client_id()
        access_token = self.config.get_access_token()

        logger.info(f"🔌 Connecting to {url}")
        logger.debug(f"   Device-Id: {device_id}")
        logger.debug(f"   Client-Id: {client_id}")
        if access_token:
            logger.debug(f"   Access Token: {access_token[:10]}...")

        try:
            # Prepare headers
            headers = {}
            if access_token:
                headers["Authorization"] = f"Bearer {access_token}"
            headers["Protocol-Version"] = str(self.protocol_version)
            headers["Device-Id"] = device_id
            headers["Client-Id"] = client_id
            headers["Content-Type"] = "application/json"

            # Connect to WebSocket
            self.websocket = await websockets.connect(
                url,
                additional_headers=headers,
                ping_interval=20,
                ping_timeout=20,
                close_timeout=10,
                max_size=10 * 1024 * 1024,  # 10MB
            )

            logger.info("✅ WebSocket connected, sending hello...")

            # Reset hello event before sending
            self._hello_received.clear()

            # Send hello message
            await self.send_hello()

            # Start message handler
            asyncio.create_task(self._message_handler())

            # Wait for server hello response (timeout 10s like ESP32)
            try:
                await asyncio.wait_for(self._hello_received.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                logger.error("❌ Timeout waiting for server hello")
                await self.websocket.close()
                return False

            self.connected = True

            if self._on_connected:
                self._on_connected()

            return True

        except ConnectionClosed as e:
            error_msg = f"Connection closed by server: {e}"
            logger.error(f"❌ {error_msg}")
            if self._on_error:
                self._on_error(error_msg)
            return False

        except Exception as e:
            error_msg = f"Connection failed: {e}"
            logger.error(f"❌ {error_msg}")
            if self._on_error:
                self._on_error(error_msg)
            return False

    async def send_hello(self):
        """Send hello message to server"""
        hello_json = json.dumps(self._hello_message, ensure_ascii=False)
        await self.websocket.send(hello_json)
        logger.debug("📤 Sent hello message")

    def is_connected(self) -> bool:
        """Check if WebSocket is connected"""
        return self.websocket is not None and self.connected

    def is_audio_channel_opened(self) -> bool:
        """Check if audio channel is opened"""
        return self.is_connected()

    async def send_audio(self, audio_data: bytes, timestamp: int = None) -> bool:
        """
        Send encoded audio data to server

        Args:
            audio_data: Opus encoded audio data
            timestamp: Timestamp in milliseconds

        Returns:
            True if sent successfully, False otherwise
        """
        if not self.is_connected():
            return False

        try:
            if timestamp is None:
                timestamp = int(time.time() * 1000)

            # Encode with binary protocol
            if self.protocol_version == 2:
                encoded = BinaryProtocolV2.encode(audio_data, timestamp)
            else:
                encoded = BinaryProtocolV3.encode(audio_data, timestamp)

            await self.websocket.send(encoded, binary=True)
            return True

        except Exception as e:
            logger.error(f"❌ Failed to send audio: {e}")
            return False

    async def send_text(self, text: str) -> bool:
        """
        Send JSON text message to server

        Args:
            text: JSON string to send

        Returns:
            True if sent successfully, False otherwise
        """
        if not self.is_connected():
            return False

        try:
            await self.websocket.send(text)
            return True
        except Exception as e:
            logger.error(f"❌ Failed to send text: {e}")
            return False

    async def send_command(self, command_type: str, **kwargs) -> bool:
        """
        Send command message to server

        Args:
            command_type: Message type (e.g., "start_listening", "stop_listening")
            **kwargs: Additional parameters for the command

        Returns:
            True if sent successfully, False otherwise
        """
        message = {
            "type": command_type,
            **kwargs
        }
        return await self.send_text(json.dumps(message))

    async def send_start_listening(self, listening_mode: str = "auto_stop"):
        """Send start listening command"""
        return await self.send_command("start_listening", mode=listening_mode)

    async def send_stop_listening(self):
        """Send stop listening command"""
        return await self.send_command("stop_listening")

    async def send_abort_speaking(self, reason: str = "user"):
        """Send abort speaking command"""
        return await self.send_command("abort_speaking", reason=reason)

    async def send_wake_word_detected(self, text: str = "wake_word"):
        """Send wake word detected message"""
        return await self.send_command("wake_word_detected", text=text)

    async def _message_handler(self):
        """Handle incoming messages from server"""
        try:
            async for message in self.websocket:
                if isinstance(message, bytes):
                    # Binary audio data
                    await self._handle_binary_audio(message)
                elif isinstance(message, str):
                    # JSON message
                    await self._handle_json_message(message)
                else:
                    logger.warning(f"⚠️ Unknown message type: {type(message)}")

        except ConnectionClosed:
            logger.warning("🔌 WebSocket connection closed")
            self.connected = False
            if self._on_disconnected:
                self._on_disconnected("Connection closed")

        except Exception as e:
            error_msg = f"Message handler error: {e}"
            logger.error(f"❌ {error_msg}")
            self.connected = False
            if self._on_disconnected:
                self._on_disconnected(error_msg)

    async def _handle_binary_audio(self, data: bytes):
        """Handle incoming binary audio data"""
        try:
            # Decode based on protocol version
            if self.protocol_version == 2:
                payload, timestamp = BinaryProtocolV2.decode(data)
            else:
                payload, timestamp = BinaryProtocolV3.decode(data)

            if payload:
                self._audio_timestamp += 1
                if self._on_audio:
                    self._on_audio(payload, timestamp)

        except Exception as e:
            logger.error(f"❌ Error decoding audio: {e}")

    async def _handle_json_message(self, message: str):
        """Handle incoming JSON message"""
        try:
            data = json.loads(message) if isinstance(message, str) else message
            msg_type = data.get("type", "")

            # Handle hello message from server
            if msg_type == "hello":
                await self._handle_server_hello(data)
            elif msg_type == "stt":
                logger.info(f"🎤 STT: {data.get('text', '')}")
            elif msg_type == "llm":
                emotion = data.get("emotion", "")
                if emotion:
                    logger.debug(f"🎭 LLM emotion: {emotion}")
            elif msg_type == "tts":
                state = data.get("state", "")
                if state == "start":
                    logger.info("🔊 TTS started")
                elif state == "stop":
                    logger.info("🔇 TTS stopped")
                elif state == "sentence_start":
                    text = data.get("text", "")
                    logger.info(f"📢 TTS: {text}")
            elif msg_type == "iot":
                logger.debug(f"🏠 IoT: {data.get('commands', [])}")
            elif msg_type == "mcp":
                logger.debug(f"🔧 MCP: {data.get('payload', '')}")
            elif msg_type in ("wake_word_detected", "abort_speaking", "start_listening", "stop_listening"):
                logger.debug(f"🎮 Command: {msg_type}")
            else:
                logger.debug(f"📩 Unknown message type: {msg_type}")

            # Forward to JSON callback
            if self._on_json:
                self._on_json(data)

        except json.JSONDecodeError as e:
            logger.error(f"❌ JSON decode error: {e}")
        except Exception as e:
            logger.error(f"❌ Error handling JSON: {e}")

    async def _handle_server_hello(self, data: Dict):
        """Handle hello message from server"""
        logger.info("📨 Received server hello")

        # Set hello received event (unblocks connect())
        self._hello_received.set()

        # Parse audio parameters
        audio_params = data.get("audio_params", {})
        if audio_params:
            sample_rate = audio_params.get("sample_rate")
            if sample_rate:
                self.server_sample_rate = sample_rate
                logger.info(f"   Server sample_rate: {sample_rate}Hz")

            frame_duration = audio_params.get("frame_duration")
            if frame_duration:
                self.server_frame_duration = frame_duration
                logger.info(f"   Server frame_duration: {frame_duration}ms")

        # Get session ID
        session_id = data.get("session_id")
        if session_id:
            logger.info(f"   Session ID: {session_id}")

    async def close_audio_channel(self):
        """Close WebSocket connection"""
        self.connected = False
        if self.websocket:
            await self.websocket.close()
            self.websocket = None

        logger.info("🔌 Audio channel closed")

    async def disconnect(self):
        """Disconnect from server"""
        await self.close_audio_channel()


# Test
async def test_protocol():
    """Test protocol connection"""
    protocol = XiaozhiProtocol()

    def on_audio(data, ts):
        logger.info(f"🔊 Received audio: {len(data)} bytes, ts={ts}")

    def on_json(data):
        logger.info(f"📩 Received JSON: {data.get('type')}")

    protocol.on_audio(on_audio)
    protocol.on_json(on_json)

    if await protocol.connect():
        logger.info("✅ Connection test successful")
        await asyncio.sleep(5)
        await protocol.disconnect()
    else:
        logger.error("❌ Connection test failed")


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    asyncio.run(test_protocol())
