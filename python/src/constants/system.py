# System constants for Xiaozhi Python Client (Whisplay HAT)
from enum import Enum


class InitializationStage(Enum):
    """Initialization stages"""

    DEVICE_FINGERPRINT = "Stage 1: Device Identity"
    CONFIG_MANAGEMENT = "Stage 2: Config Management"
    OTA_CONFIG = "Stage 3: OTA Config"
    ACTIVATION = "Stage 4: Activation"


class SystemConstants:
    """System constants"""

    # App info
    APP_NAME = "xiaozhi-whisplay"
    APP_VERSION = "2.0.0"
    BOARD_TYPE = "whisplay-hat"  # Whisplay HAT with WM8960

    # Default timeout settings
    DEFAULT_TIMEOUT = 10
    ACTIVATION_MAX_RETRIES = 60
    ACTIVATION_RETRY_INTERVAL = 5

    # File name constants
    CONFIG_FILE = "config.json"
    EFUSE_FILE = "efuse.json"
    DEVICE_CONFIG_FILE = "xiaozhi_config.json"

    # Audio settings
    INPUT_SAMPLE_RATE = 16000
    OUTPUT_SAMPLE_RATE = 16000
    CHANNELS = 1
    FRAME_DURATION_MS = 60
    FRAME_SIZE = int(INPUT_SAMPLE_RATE * FRAME_DURATION_MS / 1000)
