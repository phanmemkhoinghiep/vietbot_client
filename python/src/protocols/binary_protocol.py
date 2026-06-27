"""
Binary Protocol Handler for Xiaozhi Communication
Supports Protocol Version 1, 2, and 3

Protocol Formats:
- Version 1: Direct Opus frames (no header)
- Version 2: BinaryProtocol2 header (16 bytes)
- Version 3: BinaryProtocol3 header (4 bytes)

Reference: xiaozhi-esp32_vietnam/main/protocols/protocol.h
"""
import struct
from typing import Tuple, Optional
from enum import IntEnum

from src.utils.logging_config import get_logger

logger = get_logger(__name__)


class MessageType(IntEnum):
    """Message type for BinaryProtocol2 and BinaryProtocol3"""
    OPUS_AUDIO = 0
    JSON_DATA = 1


class BinaryProtocolVersion(IntEnum):
    """Binary protocol version"""
    V1 = 1  # Direct Opus frames
    V2 = 2  # BinaryProtocol2 header
    V3 = 3  # BinaryProtocol3 header


class BinaryProtocolHandler:
    """
    Handler for encoding/decoding binary protocol messages
    """

    # BinaryProtocol2 header format (16 bytes)
    BP2_FORMAT = "!HHIIII"  # version, type, reserved, timestamp, payload_size
    BP2_HEADER_SIZE = struct.calcsize(BP2_FORMAT)

    # BinaryProtocol3 header format (4 bytes)
    BP3_FORMAT = "!BBH"  # type, reserved, payload_size
    BP3_HEADER_SIZE = struct.calcsize(BP3_FORMAT)

    def __init__(self, version: int = BinaryProtocolVersion.V3):
        """
        Initialize binary protocol handler

        Args:
            version: Protocol version (1, 2, or 3)
        """
        self.version = version
        self._timestamp_counter = 0

    def encode_audio_packet(self, payload: bytes, timestamp: Optional[int] = None) -> bytes:
        """
        Encode audio packet with protocol header

        Args:
            payload: Opus encoded audio data
            timestamp: Timestamp in milliseconds (for v2 only)

        Returns:
            Encoded packet ready to send
        """
        if self.version == BinaryProtocolVersion.V1:
            # Direct Opus frame, no header
            return payload

        elif self.version == BinaryProtocolVersion.V2:
            # BinaryProtocol2 header (16 bytes)
            if timestamp is None:
                timestamp = self._get_next_timestamp()

            header = struct.pack(
                self.BP2_FORMAT,
                self.version,           # version: 2
                MessageType.OPUS_AUDIO, # type: 0
                0,                      # reserved
                timestamp,              # timestamp
                len(payload)            # payload_size
            )
            return header + payload

        elif self.version == BinaryProtocolVersion.V3:
            # BinaryProtocol3 header (4 bytes)
            header = struct.pack(
                self.BP3_FORMAT,
                MessageType.OPUS_AUDIO,  # type: 0
                0,                       # reserved
                len(payload)             # payload_size
            )
            return header + payload

        else:
            logger.warning(f"Unknown protocol version {self.version}, sending raw payload")
            return payload

    def encode_json_packet(self, json_data: str, timestamp: Optional[int] = None) -> bytes:
        """
        Encode JSON packet with protocol header

        Args:
            json_data: JSON string data
            timestamp: Timestamp in milliseconds (for v2 only)

        Returns:
            Encoded packet ready to send
        """
        payload = json_data.encode('utf-8')

        if self.version == BinaryProtocolVersion.V1:
            # V1 doesn't support JSON in binary format, should use text frame
            logger.warning("Protocol V1 doesn't support JSON in binary format")
            return payload

        elif self.version == BinaryProtocolVersion.V2:
            # BinaryProtocol2 header
            if timestamp is None:
                timestamp = self._get_next_timestamp()

            header = struct.pack(
                self.BP2_FORMAT,
                self.version,          # version: 2
                MessageType.JSON_DATA, # type: 1
                0,                     # reserved
                timestamp,             # timestamp
                len(payload)           # payload_size
            )
            return header + payload

        elif self.version == BinaryProtocolVersion.V3:
            # BinaryProtocol3 header
            header = struct.pack(
                self.BP3_FORMAT,
                MessageType.JSON_DATA,  # type: 1
                0,                      # reserved
                len(payload)            # payload_size
            )
            return header + payload

        else:
            return payload

    def decode_packet(self, data: bytes) -> Tuple[Optional[int], Optional[bytes]]:
        """
        Decode received binary packet

        Args:
            data: Raw received data

        Returns:
            Tuple of (message_type, payload)
            - message_type: MessageType enum value or None for v1
            - payload: Decoded payload data
        """
        if not data:
            logger.warning("Empty packet received")
            return None, None

        if self.version == BinaryProtocolVersion.V1:
            # Direct Opus frame
            return None, data

        elif self.version == BinaryProtocolVersion.V2:
            # BinaryProtocol2
            if len(data) < self.BP2_HEADER_SIZE:
                logger.error(f"Packet too short for BP2: {len(data)} < {self.BP2_HEADER_SIZE}")
                return None, None

            try:
                version, msg_type, reserved, timestamp, payload_size = struct.unpack(
                    self.BP2_FORMAT,
                    data[:self.BP2_HEADER_SIZE]
                )

                # Validate version
                if version != 2:
                    logger.warning(f"Unexpected BP2 version: {version}")

                # Extract payload
                payload = data[self.BP2_HEADER_SIZE:self.BP2_HEADER_SIZE + payload_size]

                return msg_type, payload

            except struct.error as e:
                logger.error(f"Failed to unpack BP2 header: {e}")
                return None, None

        elif self.version == BinaryProtocolVersion.V3:
            # BinaryProtocol3
            if len(data) < self.BP3_HEADER_SIZE:
                logger.error(f"Packet too short for BP3: {len(data)} < {self.BP3_HEADER_SIZE}")
                return None, None

            try:
                msg_type, reserved, payload_size = struct.unpack(
                    self.BP3_FORMAT,
                    data[:self.BP3_HEADER_SIZE]
                )

                # Extract payload
                payload = data[self.BP3_HEADER_SIZE:self.BP3_HEADER_SIZE + payload_size]

                return msg_type, payload

            except struct.error as e:
                logger.error(f"Failed to unpack BP3 header: {e}")
                return None, None

        else:
            logger.warning(f"Unknown protocol version {self.version}, returning raw data")
            return None, data

    def is_json_message(self, data: bytes) -> bool:
        """
        Check if the packet contains JSON data

        Args:
            data: Raw received data

        Returns:
            True if packet contains JSON data
        """
        if self.version == BinaryProtocolVersion.V1:
            # V1 doesn't have type field, check if it looks like JSON
            try:
                return data.strip().startswith(b'{')
            except Exception:
                return False

        msg_type, _ = self.decode_packet(data)
        return msg_type == MessageType.JSON_DATA

    def _get_next_timestamp(self) -> int:
        """Get next timestamp counter"""
        import time
        if self._timestamp_counter == 0:
            self._timestamp_counter = int(time.time() * 1000)
        else:
            self._timestamp_counter += 1
        return self._timestamp_counter

    def set_version(self, version: int):
        """
        Set protocol version

        Args:
            version: Protocol version (1, 2, or 3)
        """
        if version in [BinaryProtocolVersion.V1, BinaryProtocolVersion.V2, BinaryProtocolVersion.V3]:
            self.version = version
            logger.info(f"Protocol version set to {version}")
        else:
            logger.warning(f"Invalid protocol version {version}, using V3")
            self.version = BinaryProtocolVersion.V3

    @classmethod
    def detect_protocol_version(cls, data: bytes) -> int:
        """
        Auto-detect protocol version from received data

        Args:
            data: Raw received data

        Returns:
            Detected protocol version (1, 2, or 3)
        """
        if not data or len(data) < 4:
            logger.debug("Data too short to detect protocol, assuming V1")
            return BinaryProtocolVersion.V1

        # Check for BP3 marker (type byte should be 0 or 1)
        first_byte = data[0]
        if first_byte in [MessageType.OPUS_AUDIO, MessageType.JSON_DATA]:
            # Check if payload_size makes sense
            payload_size = struct.unpack("!H", data[2:4])[0]
            if len(data) >= cls.BP3_HEADER_SIZE + payload_size:
                logger.debug("Detected BinaryProtocol3")
                return BinaryProtocolVersion.V3

        # Check for BP2 marker (version should be 2)
        if len(data) >= cls.BP2_HEADER_SIZE:
            version = struct.unpack("!H", data[0:2])[0]
            if version == 2:
                logger.debug("Detected BinaryProtocol2")
                return BinaryProtocolVersion.V2

        # Default to V1 (direct Opus frames)
        logger.debug("Assuming BinaryProtocol1 (direct Opus)")
        return BinaryProtocolVersion.V1


# Convenience function for quick encoding
def encode_audio(data: bytes, version: int = 3, timestamp: Optional[int] = None) -> bytes:
    """Quick encode audio packet"""
    handler = BinaryProtocolHandler(version)
    return handler.encode_audio_packet(data, timestamp)


def decode_audio(data: bytes, version: int = 3) -> Optional[bytes]:
    """Quick decode audio packet"""
    handler = BinaryProtocolHandler(version)
    msg_type, payload = handler.decode_packet(data)
    if msg_type == MessageType.OPUS_AUDIO or msg_type is None:
        return payload
    return None
