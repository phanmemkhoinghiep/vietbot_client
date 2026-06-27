"""
Integration Tests for Protocol Communication
Tests MQTT and WebSocket protocol implementations
"""
import asyncio
import json
import pytest
import struct
from unittest.mock import Mock, AsyncMock, patch, MagicMock

from src.protocols.binary_protocol import (
    BinaryProtocolHandler,
    BinaryProtocolVersion,
    MessageType,
    encode_audio,
    decode_audio
)
from src.protocols.mqtt_protocol import MqttProtocol
from src.protocols.websocket_protocol import WebsocketProtocol
from src.constants.constants import AudioConfig
from src.utils.config_manager import ConfigManager


class TestBinaryProtocol:
    """Test binary protocol encoding/decoding"""

    @pytest.fixture
    def audio_data(self):
        """Sample audio data"""
        return bytes(range(256)) * 4  # 1024 bytes

    @pytest.fixture
    def json_data(self):
        """Sample JSON data"""
        return json.dumps({"type": "test", "data": "value"})

    def test_protocol_v1_direct_frame(self, audio_data):
        """Test Protocol V1 - direct Opus frame"""
        handler = BinaryProtocolHandler(BinaryProtocolVersion.V1)

        # Encode
        encoded = handler.encode_audio_packet(audio_data)
        assert encoded == audio_data  # V1 should be direct

        # Decode
        msg_type, payload = handler.decode_packet(encoded)
        assert msg_type is None  # V1 has no message type
        assert payload == audio_data

    def test_protocol_v2_encoding(self, audio_data):
        """Test Protocol V2 encoding"""
        handler = BinaryProtocolHandler(BinaryProtocolVersion.V2)

        # Encode with timestamp
        timestamp = 12345
        encoded = handler.encode_audio_packet(audio_data, timestamp)

        # Check header size
        assert len(encoded) == handler.BP2_HEADER_SIZE + len(audio_data)

        # Decode header
        version, msg_type, reserved, decoded_timestamp, payload_size = struct.unpack(
            handler.BP2_FORMAT,
            encoded[:handler.BP2_HEADER_SIZE]
        )

        assert version == 2
        assert msg_type == MessageType.OPUS_AUDIO
        assert decoded_timestamp == timestamp
        assert payload_size == len(audio_data)

        # Check payload
        payload = encoded[handler.BP2_HEADER_SIZE:]
        assert payload == audio_data

    def test_protocol_v3_encoding(self, audio_data):
        """Test Protocol V3 encoding"""
        handler = BinaryProtocolHandler(BinaryProtocolVersion.V3)

        # Encode
        encoded = handler.encode_audio_packet(audio_data)

        # Check header size
        assert len(encoded) == handler.BP3_HEADER_SIZE + len(audio_data)

        # Decode header
        msg_type, reserved, payload_size = struct.unpack(
            handler.BP3_FORMAT,
            encoded[:handler.BP3_HEADER_SIZE]
        )

        assert msg_type == MessageType.OPUS_AUDIO
        assert payload_size == len(audio_data)

        # Check payload
        payload = encoded[handler.BP3_HEADER_SIZE:]
        assert payload == audio_data

    def test_protocol_v2_json_encoding(self, json_data):
        """Test Protocol V2 JSON encoding"""
        handler = BinaryProtocolHandler(BinaryProtocolVersion.V2)

        # Encode
        encoded = handler.encode_json_packet(json_data, timestamp=100)

        # Decode
        msg_type, payload = handler.decode_packet(encoded)

        assert msg_type == MessageType.JSON_DATA
        assert payload.decode('utf-8') == json_data

    def test_protocol_v3_json_encoding(self, json_data):
        """Test Protocol V3 JSON encoding"""
        handler = BinaryProtocolHandler(BinaryProtocolVersion.V3)

        # Encode
        encoded = handler.encode_json_packet(json_data)

        # Decode
        msg_type, payload = handler.decode_packet(encoded)

        assert msg_type == MessageType.JSON_DATA
        assert payload.decode('utf-8') == json_data

    def test_protocol_auto_detection(self):
        """Test automatic protocol version detection"""
        # V3 packet (type byte is 0 or 1)
        v3_packet = struct.pack("!BBH", 0, 0, 256) + bytes(256)
        detected = BinaryProtocolHandler.detect_protocol_version(v3_packet)
        assert detected == BinaryProtocolVersion.V3

        # V2 packet (version is 2)
        v2_packet = struct.pack("!HHII", 2, 0, 0, 0, 256) + bytes(256)
        detected = BinaryProtocolHandler.detect_protocol_version(v2_packet)
        assert detected == BinaryProtocolVersion.V2

        # V1 packet (no header)
        v1_packet = bytes(100)
        detected = BinaryProtocolHandler.detect_protocol_version(v1_packet)
        assert detected == BinaryProtocolVersion.V1

    def test_is_json_message(self):
        """Test JSON message detection"""
        handler = BinaryProtocolHandler(BinaryProtocolVersion.V3)

        # JSON packet
        json_data = json.dumps({"test": "value"})
        json_packet = handler.encode_json_packet(json_data)
        assert handler.is_json_message(json_packet)

        # Audio packet
        audio_packet = handler.encode_audio_packet(bytes(100))
        assert not handler.is_json_message(audio_packet)

    def test_convenience_functions(self, audio_data):
        """Test convenience functions"""
        # Encode/decode audio
        encoded = encode_audio(audio_data, version=3)
        decoded = decode_audio(encoded, version=3)

        assert decoded == audio_data


class TestMQTTProtocol:
    """Test MQTT protocol implementation"""

    @pytest.fixture
    def config(self):
        """Get config manager instance"""
        return ConfigManager.get_instance()

    @pytest.fixture
    def mqtt_protocol(self, event_loop):
        """Create MQTT protocol instance"""
        protocol = MqttProtocol(loop=event_loop)
        yield protocol
        # Cleanup
        if protocol.mqtt_client:
            protocol.mqtt_client.loop_stop()

    def test_endpoint_parsing(self, mqtt_protocol):
        """Test MQTT endpoint parsing"""
        # Test various endpoint formats
        test_cases = [
            ("mqtt.example.com", ("mqtt.example.com", 1883)),
            ("mqtt.example.com:1883", ("mqtt.example.com", 1883)),
            ("mqtt://mqtt.example.com", ("mqtt.example.com", 1883)),
            ("mqtts://mqtt.example.com", ("mqtt.example.com", 1883)),
            ("mqtt.example.com:8883", ("mqtt.example.com", 8883)),
        ]

        for endpoint, expected in test_cases:
            result = mqtt_protocol._parse_endpoint(endpoint)
            assert result == expected, f"Failed for {endpoint}: got {result}, expected {expected}"

    def test_endpoint_parsing_invalid(self, mqtt_protocol):
        """Test invalid endpoint parsing"""
        with pytest.raises(ValueError):
            mqtt_protocol._parse_endpoint("")

        with pytest.raises(ValueError):
            mqtt_protocol._parse_endpoint("example.com:99999")

    @pytest.mark.asyncio
    async def test_aes_encryption_decryption(self, mqtt_protocol):
        """Test AES-CTR encryption/decryption"""
        key = b'test_key_16bytes_'
        nonce = b'test_nonce_12byt'

        # Test data
        plaintext = b"Hello, this is a test message for encryption!"

        # Encrypt
        encrypted = mqtt_protocol.aes_ctr_encrypt(key, nonce, plaintext)
        assert encrypted != plaintext
        assert len(encrypted) == len(plaintext)

        # Decrypt
        decrypted = mqtt_protocol.aes_ctr_decrypt(key, nonce, encrypted)
        assert decrypted == plaintext

    @pytest.mark.asyncio
    async def test_connection_info(self, mqtt_protocol):
        """Test connection info"""
        info = mqtt_protocol.get_connection_info()

        assert isinstance(info, dict)
        assert "connected" in info
        assert "mqtt_connected" in info
        assert "is_closing" in info
        assert "auto_reconnect_enabled" in info


class TestWebSocketProtocol:
    """Test WebSocket protocol implementation"""

    @pytest.fixture
    def ws_protocol(self):
        """Create WebSocket protocol instance"""
        return WebsocketProtocol()

    def test_initialization(self, ws_protocol):
        """Test protocol initialization"""
        assert ws_protocol.websocket is None
        assert ws_protocol.connected is False
        assert ws_protocol._binary_protocol is not None
        assert ws_protocol._binary_protocol.version == BinaryProtocolVersion.V3

    def test_protocol_version_setting(self, ws_protocol):
        """Test protocol version setting"""
        # Set version to 2
        ws_protocol._binary_protocol.set_version(2)
        assert ws_protocol._binary_protocol.version == 2

        # Set version to 1
        ws_protocol._binary_protocol.set_version(1)
        assert ws_protocol._binary_protocol.version == 1

        # Test invalid version (should default to 3)
        ws_protocol._binary_protocol.set_version(99)
        assert ws_protocol._binary_protocol.version == 3

    @pytest.mark.asyncio
    async def test_send_text_without_connection(self, ws_protocol):
        """Test sending text without connection"""
        # Should not raise exception, just log warning
        await ws_protocol.send_text("test message")
        assert ws_protocol.websocket is None

    @pytest.mark.asyncio
    async def test_send_audio_without_connection(self, ws_protocol):
        """Test sending audio without connection"""
        # Should not raise exception
        await ws_protocol.send_audio(b"test audio data")

    def test_is_audio_channel_opened(self, ws_protocol):
        """Test audio channel status check"""
        # Without connection, should be False
        assert ws_protocol.is_audio_channel_opened() is False


# Helper functions for running tests
def run_binary_protocol_tests():
    """Run binary protocol tests"""
    test = TestBinaryProtocol()
    audio_data = bytes(range(256)) * 4
    json_data = json.dumps({"type": "test", "data": "value"})

    print("Testing Binary Protocol...")

    try:
        test.test_protocol_v1_direct_frame(audio_data)
        print("✅ V1 direct frame test passed")

        test.test_protocol_v2_encoding(audio_data)
        print("✅ V2 encoding test passed")

        test.test_protocol_v3_encoding(audio_data)
        print("✅ V3 encoding test passed")

        test.test_protocol_v2_json_encoding(json_data)
        print("✅ V2 JSON encoding test passed")

        test.test_protocol_v3_json_encoding(json_data)
        print("✅ V3 JSON encoding test passed")

        test.test_protocol_auto_detection()
        print("✅ Protocol auto-detection test passed")

        test.test_is_json_message()
        print("✅ JSON message detection test passed")

        test.test_convenience_functions(audio_data)
        print("✅ Convenience functions test passed")

        print("\n✅ All binary protocol tests passed!")
        return True

    except Exception as e:
        print(f"\n❌ Test failed: {e}")
        return False


if __name__ == "__main__":
    # Run tests standalone
    import sys
    success = run_binary_protocol_tests()
    sys.exit(0 if success else 1)
