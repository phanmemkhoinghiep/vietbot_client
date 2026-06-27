#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Xiaozhi MQTT Protocol
MQTT + UDP hybrid protocol cho xiaozhi server

Theo xiaozhi protocol (Python reference):
- MQTT (TLS port 8883) cho control messages (JSON)
- UDP cho encrypted audio data (AES-CTR)
"""

import asyncio
import json
import socket
import threading
import time
import logging
from typing import Optional, Callable, Dict, Any
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

import paho.mqtt.client as mqtt

from xiaozhi_config import get_config, XiaozhiConfig

logger = logging.getLogger(__name__)


class XiaozhiMqttProtocol:
    """
    Xiaozhi MQTT Protocol Client (MQTT + UDP hybrid)

    Architecture:
    - MQTT (TLS port 8883): Control messages (hello, stt, tts, llm, iot, mcp)
    - UDP: Encrypted audio data (AES-CTR)
    """

    # Callback types
    # on_audio: Callable[[bytes], None] - (audio_data)
    # on_json: Callable[[Dict], None] - (json_message)
    # on_connected: Callable[[], None]
    # on_disconnected: Callable[[str], None] - (reason)
    # on_error: Callable[[str], None] - (error_message)

    def __init__(self, mqtt_config: Dict = None):
        self.config: XiaozhiConfig = get_config()
        self.connected = False
        self.protocol_version = self.config.get_protocol_version()

        # MQTT config
        self.endpoint = None
        self.client_id = None
        self.username = None
        self.password = None
        self.publish_topic = None
        self.subscribe_topic = None

        # UDP config (from server hello)
        self.udp_server = None
        self.udp_port = None
        self.aes_key = None
        self.aes_nonce = None
        self.local_sequence = 0

        # Connection state
        self.mqtt_client: Optional[mqtt.Client] = None
        self.udp_socket: Optional[socket.socket] = None
        self.udp_thread: Optional[threading.Thread] = None
        self.udp_running = False
        self._is_closing = False

        # Server hello event (for synchronization)
        self._hello_received = asyncio.Event()
        self._loop = asyncio.get_event_loop()

        # Callbacks
        self._on_audio: Optional[Callable[[bytes], None]] = None
        self._on_json: Optional[Callable[[Dict], None]] = None
        self._on_connected: Optional[Callable[[], None]] = None
        self._on_disconnected: Optional[Callable[[str], None]] = None
        self._on_error: Optional[Callable[[str], None]] = None

        # Session
        self.session_id: Optional[str] = None

        # Apply MQTT config if provided
        if mqtt_config:
            self.endpoint = mqtt_config.get("endpoint")
            self.client_id = mqtt_config.get("client_id")
            self.username = mqtt_config.get("username")
            self.password = mqtt_config.get("password")
            self.publish_topic = mqtt_config.get("publish_topic")
            self.subscribe_topic = mqtt_config.get("subscribe_topic")
            # Handle "null" string for subscribe_topic
            if self.subscribe_topic == "null":
                self.subscribe_topic = None

        logger.info(f"📡 XiaozhiMqttProtocol initialized with v{self.protocol_version}")

    def _parse_endpoint(self, endpoint: str) -> tuple[str, int]:
        """Parse endpoint string to extract host and port"""
        if not endpoint:
            raise ValueError("endpoint cannot be empty")

        if ":" in endpoint:
            host, port_str = endpoint.rsplit(":", 1)
            try:
                port = int(port_str)
                if port < 1 or port > 65535:
                    raise ValueError(f"Port must be 1-65535: {port}")
            except ValueError as e:
                raise ValueError(f"Invalid port number: {port_str}") from e
        else:
            host = endpoint
            port = 8883  # Default MQTT TLS port

        return host, port

    def on_audio(self, callback: Callable[[bytes], None]):
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

    async def connect(self, mqtt_config: Dict = None) -> bool:
        """
        Connect to xiaozhi MQTT server

        Args:
            mqtt_config: Optional MQTT config (overrides stored config)

        Returns:
            True if connection successful, False otherwise
        """
        # Apply MQTT config if provided
        if mqtt_config:
            self.endpoint = mqtt_config.get("endpoint")
            self.client_id = mqtt_config.get("client_id")
            self.username = mqtt_config.get("username")
            self.password = mqtt_config.get("password")
            self.publish_topic = mqtt_config.get("publish_topic")
            self.subscribe_topic = mqtt_config.get("subscribe_topic")
            # Handle "null" string for subscribe_topic
            if self.subscribe_topic == "null":
                self.subscribe_topic = None

        # Validate MQTT config
        if not self.endpoint or not self.username or not self.password or not self.publish_topic:
            error_msg = "MQTT config incomplete"
            logger.error(f"❌ {error_msg}")
            if self._on_error:
                self._on_error(error_msg)
            return False

        # Parse endpoint
        try:
            host, port = self._parse_endpoint(self.endpoint)
            use_tls = port == 8883
            logger.info(f"🔌 Connecting to MQTT {host}:{port} (TLS: {use_tls})")
        except ValueError as e:
            error_msg = f"Failed to parse endpoint: {e}"
            logger.error(f"❌ {error_msg}")
            if self._on_error:
                self._on_error(error_msg)
            return False

        # Reset hello event
        self._hello_received.clear()

        # Disconnect existing client if any
        if self.mqtt_client:
            try:
                self.mqtt_client.loop_stop()
                self.mqtt_client.disconnect()
            except Exception as e:
                logger.warning(f"Failed to disconnect existing client: {e}")

        # Create MQTT client
        self.mqtt_client = mqtt.Client(client_id=self.client_id)
        self.mqtt_client.username_pw_set(self.username, self.password)

        # Configure TLS if needed
        if use_tls:
            try:
                self.mqtt_client.tls_set(
                    ca_certs=None,
                    certfile=None,
                    keyfile=None,
                    cert_reqs=mqtt.ssl.CERT_REQUIRED,
                    tls_version=mqtt.ssl.PROTOCOL_TLS,
                )
                logger.debug("✅ TLS configured")
            except Exception as e:
                error_msg = f"TLS configuration failed: {e}"
                logger.error(f"❌ {error_msg}")
                if self._on_error:
                    self._on_error(error_msg)
                return False

        # Create connection future
        connect_future = self._loop.create_future()

        def on_connect(client, userdata, flags, rc, properties=None):
            if rc == 0:
                logger.info("✅ MQTT connected")
                self._loop.call_soon_threadsafe(lambda: connect_future.set_result(True))
            else:
                error_msg = f"MQTT connection failed, code: {rc}"
                logger.error(f"❌ {error_msg}")
                self._loop.call_soon_threadsafe(
                    lambda: connect_future.set_exception(Exception(error_msg))
                )

        def on_message(client, userdata, msg):
            try:
                payload = msg.payload.decode("utf-8")
                self._handle_mqtt_message(payload)
            except Exception as e:
                logger.error(f"❌ Error handling MQTT message: {e}")

        def on_disconnect(client, userdata, rc):
            if rc == 0:
                logger.info("MQTT disconnected normally")
            else:
                logger.warning(f"⚠️ MQTT disconnected abnormally, code: {rc}")
            self.connected = False
            self._stop_udp_receiver()

        # Set callbacks
        self.mqtt_client.on_connect = on_connect
        self.mqtt_client.on_message = on_message
        self.mqtt_client.on_disconnect = on_disconnect

        try:
            # Connect to MQTT server
            self.mqtt_client.connect_async(host, port, keepalive=60)
            self.mqtt_client.loop_start()

            # Wait for connection
            await asyncio.wait_for(connect_future, timeout=10.0)

            # Subscribe to topic
            if self.subscribe_topic:
                self.mqtt_client.subscribe(self.subscribe_topic, qos=1)
                logger.info(f"✅ Subscribed to: {self.subscribe_topic}")

            # Send hello message
            hello_message = {
                "type": "hello",
                "version": self.protocol_version,
                "features": {
                    "mcp": True,
                },
                "transport": "udp",
                "audio_params": {
                    "format": self.config.get("AUDIO.FORMAT", "opus"),
                    "sample_rate": self.config.get("AUDIO.OUTPUT_SAMPLE_RATE", 24000),
                    "channels": self.config.get("AUDIO.CHANNELS", 1),
                    "frame_duration": self.config.get("AUDIO.FRAME_DURATION_MS", 60),
                },
            }

            if not await self.send_text(json.dumps(hello_message)):
                logger.error("❌ Failed to send hello message")
                return False

            # Wait for server hello response
            try:
                await asyncio.wait_for(self._hello_received.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                logger.error("❌ Timeout waiting for server hello")
                await self.disconnect()
                return False

            # Start UDP receiver
            self._start_udp_receiver()

            self.connected = True

            if self._on_connected:
                self._on_connected()

            logger.info("✅ MQTT + UDP connected successfully")
            return True

        except Exception as e:
            error_msg = f"MQTT connection failed: {e}"
            logger.error(f"❌ {error_msg}")
            if self._on_error:
                self._on_error(error_msg)
            return False

    def _handle_mqtt_message(self, payload: str):
        """Handle incoming MQTT message"""
        try:
            data = json.loads(payload)
            msg_type = data.get("type")

            if msg_type == "hello":
                logger.info("📨 Received server hello")
                transport = data.get("transport")
                if transport != "udp":
                    logger.error(f"❌ Unsupported transport: {transport}")
                    return

                # Get session ID
                self.session_id = data.get("session_id", "")

                # Get UDP config
                udp_config = data.get("udp")
                if not udp_config:
                    logger.error("❌ UDP config missing")
                    return

                self.udp_server = udp_config.get("server")
                self.udp_port = udp_config.get("port")
                self.aes_key = udp_config.get("key")
                self.aes_nonce = udp_config.get("nonce")

                logger.info(f"📡 UDP server: {self.udp_server}:{self.udp_port}")

                # Set hello event
                self._loop.call_soon_threadsafe(self._hello_received.set)

            elif msg_type == "goodbye":
                logger.info("👋 Server sent goodbye")
                # Handle goodbye in main loop
                asyncio.run_coroutine_threadsafe(self.disconnect(), self._loop)

            else:
                # Forward to JSON callback
                if self._on_json:
                    self._loop.call_soon_threadsafe(lambda: self._on_json(data))

        except json.JSONDecodeError as e:
            logger.error(f"❌ JSON decode error: {e}")
        except Exception as e:
            logger.error(f"❌ Error handling MQTT message: {e}")

    def _start_udp_receiver(self):
        """Start UDP receiver thread for audio data"""
        if not self.udp_server or not self.udp_port:
            logger.error("❌ UDP config not available")
            return

        if self.udp_socket:
            self.udp_socket.close()

        try:
            self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_socket.settimeout(0.5)
            logger.info(f"🎵 UDP receiver started on :{self.udp_port}")

            self.udp_running = True
            self.udp_thread = threading.Thread(target=self._udp_receive_thread, daemon=True)
            self.udp_thread.start()

        except Exception as e:
            logger.error(f"❌ Failed to start UDP receiver: {e}")

    def _udp_receive_thread(self):
        """UDP receiver thread for audio data"""
        logger.info("🎵 UDP receive thread started")

        while self.udp_running:
            try:
                data, addr = self.udp_socket.recvfrom(4096)

                try:
                    # Validate packet (at least 16 bytes for nonce)
                    if len(data) < 16:
                        logger.error(f"❌ Invalid audio packet size: {len(data)}")
                        continue

                    # Split nonce and encrypted data
                    received_nonce = data[:16]
                    encrypted_audio = data[16:]

                    # Decrypt with AES-CTR
                    decrypted = self._aes_ctr_decrypt(
                        bytes.fromhex(self.aes_key),
                        received_nonce,
                        encrypted_audio
                    )

                    # Process audio data
                    if self._on_audio:
                        self._loop.call_soon_threadsafe(lambda: self._on_audio(decrypted))

                except Exception as e:
                    logger.error(f"❌ Error processing audio packet: {e}")
                    continue

            except socket.timeout:
                continue
            except Exception as e:
                if self.udp_running:
                    logger.error(f"❌ UDP receive error: {e}")
                break

        logger.info("🎵 UDP receive thread stopped")

    def _stop_udp_receiver(self):
        """Stop UDP receiver thread"""
        self.udp_running = False

        if self.udp_thread and self.udp_thread.is_alive():
            try:
                self.udp_thread.join(1.0)
            except RuntimeError:
                pass

        if self.udp_socket:
            try:
                self.udp_socket.close()
            except Exception as e:
                logger.warning(f"Failed to close UDP socket: {e}")
            self.udp_socket = None

    def _aes_ctr_encrypt(self, key: bytes, nonce: bytes, plaintext: bytes) -> bytes:
        """AES-CTR encryption"""
        cipher = Cipher(algorithms.AES(key), modes.CTR(nonce), backend=default_backend())
        encryptor = cipher.encryptor()
        return encryptor.update(plaintext) + encryptor.finalize()

    def _aes_ctr_decrypt(self, key: bytes, nonce: bytes, ciphertext: bytes) -> bytes:
        """AES-CTR decryption"""
        cipher = Cipher(algorithms.AES(key), modes.CTR(nonce), backend=default_backend())
        decryptor = cipher.decryptor()
        return decryptor.update(ciphertext) + decryptor.finalize()

    async def send_text(self, text: str) -> bool:
        """
        Send JSON text message via MQTT

        Args:
            text: JSON string to send

        Returns:
            True if sent successfully, False otherwise
        """
        if not self.mqtt_client or not self.mqtt_client.is_connected():
            logger.error("❌ MQTT not connected")
            return False

        try:
            self.mqtt_client.publish(self.publish_topic, text)
            return True
        except Exception as e:
            logger.error(f"❌ Failed to send MQTT message: {e}")
            return False

    async def send_command(self, command_type: str, **kwargs) -> bool:
        """
        Send command message via MQTT

        Args:
            command_type: Message type (e.g., "start_listening", "stop_listening")
            **kwargs: Additional parameters

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

    async def send_audio(self, audio_data: bytes, timestamp: int = None) -> bool:
        """
        Send audio data via UDP (encrypted with AES-CTR)

        Args:
            audio_data: Opus encoded audio data
            timestamp: Timestamp in milliseconds

        Returns:
            True if sent successfully, False otherwise
        """
        if not self.udp_socket or not self.udp_server or not self.udp_port:
            logger.error("❌ UDP not initialized")
            return False

        try:
            # Generate nonce: 0x01 + 0x00x3 + length (4 hex) + original_nonce + sequence
            self.local_sequence = (self.local_sequence + 1) & 0xFFFFFFFF
            new_nonce = (
                self.aes_nonce[:4] +  # Fixed prefix
                format(len(audio_data), "04x") +  # Data length
                self.aes_nonce[8:24] +  # Original nonce
                format(self.local_sequence, "08x")  # Sequence
            )

            # Encrypt with AES-CTR
            encrypted = self._aes_ctr_encrypt(
                bytes.fromhex(self.aes_key),
                bytes.fromhex(new_nonce),
                bytes(audio_data)
            )

            # Combine nonce and ciphertext
            packet = bytes.fromhex(new_nonce) + encrypted

            # Send via UDP
            self.udp_socket.sendto(packet, (self.udp_server, self.udp_port))

            if self.local_sequence % 100 == 0:
                logger.debug(f"📤 Sent audio packet #{self.local_sequence}")

            return True

        except Exception as e:
            logger.error(f"❌ Failed to send audio: {e}")
            return False

    def is_connected(self) -> bool:
        """Check if MQTT is connected"""
        return (
            self.connected and
            self.mqtt_client and
            self.mqtt_client.is_connected() and
            self.udp_running
        )

    def is_audio_channel_opened(self) -> bool:
        """Check if audio channel is opened"""
        return self.is_connected()

    async def close_audio_channel(self):
        """Close MQTT + UDP connection"""
        self._is_closing = True

        try:
            # Send goodbye if we have session
            if self.session_id:
                goodbye_msg = {"type": "goodbye", "session_id": self.session_id}
                await self.send_text(json.dumps(goodbye_msg))

        except Exception as e:
            logger.error(f"❌ Error sending goodbye: {e}")

        await self.disconnect()

    async def disconnect(self):
        """Disconnect from MQTT server"""
        logger.info("🔌 Disconnecting...")

        self.connected = False
        self._stop_udp_receiver()

        if self.mqtt_client:
            try:
                self.mqtt_client.loop_stop()
                self.mqtt_client.disconnect()
            except Exception as e:
                logger.error(f"❌ Failed to disconnect MQTT: {e}")
            self.mqtt_client = None

        # Reset state
        self.session_id = None
        self.local_sequence = 0
        self.udp_server = None
        self.udp_port = None
        self.aes_key = None
        self.aes_nonce = None

        logger.info("✅ Disconnected")


# Test
async def test_mqtt_protocol():
    """Test MQTT protocol"""
    protocol = XiaozhiMqttProtocol()

    def on_audio(data):
        logger.info(f"🔊 Received audio: {len(data)} bytes")

    def on_json(data):
        logger.info(f"📩 Received JSON: {data.get('type')}")

    protocol.on_audio(on_audio)
    protocol.on_json(on_json)

    # Test with actual MQTT config
    mqtt_config = {
        "endpoint": "xiaozhi.vietdev.vn:8883",
        "client_id": "test_client",
        "username": "test",
        "password": "test",
        "publish_topic": "device-server",
        "subscribe_topic": "devices/p2p/test"
    }

    logger.info("MQTT protocol test completed")


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    asyncio.run(test_mqtt_protocol())
