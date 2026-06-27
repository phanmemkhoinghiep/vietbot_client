import json
import os
import uuid
from typing import Any, Dict

from src.utils.logging_config import get_logger
from src.utils.resource_finder import resource_finder

logger = get_logger(__name__)


class ConfigManager:
    """Configuration manager - Singleton pattern"""

    _instance = None

    # 默认配置
    DEFAULT_CONFIG = {
        "SYSTEM_OPTIONS": {
            "CLIENT_ID": None,
            "DEVICE_ID": None,
            "NETWORK": {
                "OTA_VERSION_URL": "https://xiaozhi.vietdev.vn/ota/",
                "WEBSOCKET_URL": None,
                "WEBSOCKET_ACCESS_TOKEN": None,
                "MQTT_INFO": None,
                "ACTIVATION_VERSION": "v2",  # 可选值: v1, v2
                "AUTHORIZATION_URL": "https://xiaozhi.vietdev.vn/",
            },
        },
        "WAKE_WORD_OPTIONS": {
            "USE_WAKE_WORD": True,
            "MODEL_PATH": "models",
            "NUM_THREADS": 4,
            "PROVIDER": "cpu",
            "MAX_ACTIVE_PATHS": 2,
            "KEYWORDS_SCORE": 1.8,
            "KEYWORDS_THRESHOLD": 0.2,
            "NUM_TRAILING_BLANKS": 1,
        },
        "CAMERA": {
            "camera_index": 0,
            "frame_width": 640,
            "frame_height": 480,
            "fps": 30,
            "Local_VL_url": "https://open.bigmodel.cn/api/paas/v4/",
            "VLapi_key": "",
            "models": "glm-4v-plus",
        },
        "SHORTCUTS": {
            "ENABLED": True,
            "MANUAL_PRESS": {"modifier": "ctrl", "key": "j", "description": "按住说话"},
            "AUTO_TOGGLE": {"modifier": "ctrl", "key": "k", "description": "自动对话"},
            "ABORT": {"modifier": "ctrl", "key": "q", "description": "中断对话"},
            "MODE_TOGGLE": {"modifier": "ctrl", "key": "m", "description": "切换模式"},
            "WINDOW_TOGGLE": {
                "modifier": "ctrl",
                "key": "w",
                "description": "显示/隐藏窗口",
            },
        },
        "AEC_OPTIONS": {
            "ENABLED": False,
            "BUFFER_MAX_LENGTH": 200,
            "FRAME_DELAY": 3,
            "FILTER_LENGTH_RATIO": 0.4,
            "ENABLE_PREPROCESS": True,
        },
        "AUDIO_DEVICES": {
            "input_device_id": None,
            "input_device_name": None,
            "output_device_id": None,
            "output_device_name": None,
            "input_sample_rate": None,
            "output_sample_rate": None
        }
    }

    def __new__(cls):
        """
        Ensure singleton pattern.
        """
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        """
        Initialize configuration manager.
        """
        if self._initialized:
            return
        self._initialized = True

        # Initialize configuration file paths
        self._init_config_paths()

        # Ensure required directories exist
        self._ensure_required_directories()

        # Load configuration with environment variable overrides
        self._config = self._load_config()

        # Apply environment variable overrides
        self._apply_env_overrides()

    def _init_config_paths(self):
        """
        Initialize configuration file paths.
        """
        # Use resource_finder to find or create config directory
        self.config_dir = resource_finder.find_config_dir()
        if not self.config_dir:
            # If config directory not found, create in project root
            project_root = resource_finder.get_project_root()
            self.config_dir = project_root / "config"
            self.config_dir.mkdir(parents=True, exist_ok=True)
            logger.info(f"Created config directory: {self.config_dir.absolute()}")

        self.config_file = self.config_dir / "config.json"

        # Log configuration file paths
        logger.info(f"Config directory: {self.config_dir.absolute()}")
        logger.info(f"Config file: {self.config_file.absolute()}")

    def _ensure_required_directories(self):
        """
        Ensure required directories exist.
        """
        project_root = resource_finder.get_project_root()

        # Create models directory
        models_dir = project_root / "models"
        if not models_dir.exists():
            models_dir.mkdir(parents=True, exist_ok=True)
            logger.info(f"Created models directory: {models_dir.absolute()}")

        # Create cache directory
        cache_dir = project_root / "cache"
        if not cache_dir.exists():
            cache_dir.mkdir(parents=True, exist_ok=True)
            logger.info(f"Created cache directory: {cache_dir.absolute()}")

    def _load_config(self) -> Dict[str, Any]:
        """
        Load configuration file, create if not exists.
        """
        try:
            # First try to find config file using resource_finder
            config_file_path = resource_finder.find_file("config/config.json")

            if config_file_path:
                logger.debug(f"Found config file using resource_finder: {config_file_path}")
                config = json.loads(config_file_path.read_text(encoding="utf-8"))
                return self._merge_configs(self.DEFAULT_CONFIG, config)

            # If resource_finder didn't find it, try using path in instance variable
            if self.config_file.exists():
                logger.debug(f"Found config file using instance path: {self.config_file}")
                config = json.loads(self.config_file.read_text(encoding="utf-8"))
                return self._merge_configs(self.DEFAULT_CONFIG, config)
            else:
                # Create default config file
                logger.info("Config file does not exist, creating default config")
                self._save_config(self.DEFAULT_CONFIG)
                return self.DEFAULT_CONFIG.copy()

        except Exception as e:
            logger.error(f"Config loading error: {e}")
            return self.DEFAULT_CONFIG.copy()

    def _save_config(self, config: dict) -> bool:
        """
        Save configuration to file.
        """
        try:
            # Ensure config directory exists
            self.config_dir.mkdir(parents=True, exist_ok=True)

            # Save config file
            self.config_file.write_text(
                json.dumps(config, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            logger.debug(f"Config saved to: {self.config_file}")
            return True

        except Exception as e:
            logger.error(f"Config save error: {e}")
            return False

    @staticmethod
    def _merge_configs(default: dict, custom: dict) -> dict:
        """
        Recursively merge configuration dictionaries.
        """
        result = default.copy()
        for key, value in custom.items():
            if (
                key in result
                and isinstance(result[key], dict)
                and isinstance(value, dict)
            ):
                result[key] = ConfigManager._merge_configs(result[key], value)
            else:
                result[key] = value
        return result

    def get_config(self, path: str, default: Any = None) -> Any:
        """
        Get configuration value by path
        path: Dot-separated configuration path, e.g. "SYSTEM_OPTIONS.NETWORK.MQTT_INFO"
        """
        try:
            value = self._config
            for key in path.split("."):
                value = value[key]
            return value
        except (KeyError, TypeError):
            return default

    def update_config(self, path: str, value: Any) -> bool:
        """
        Update specific configuration item
        path: Dot-separated configuration path, e.g. "SYSTEM_OPTIONS.NETWORK.MQTT_INFO"
        """
        try:
            current = self._config
            *parts, last = path.split(".")
            for part in parts:
                current = current.setdefault(part, {})
            current[last] = value
            return self._save_config(self._config)
        except Exception as e:
            logger.error(f"Configuration update error {path}: {e}")
            return False

    def reload_config(self) -> bool:
        """
        Reload configuration file.
        """
        try:
            self._config = self._load_config()
            logger.info("Configuration file reloaded")
            return True
        except Exception as e:
            logger.error(f"Configuration reload failed: {e}")
            return False

    def generate_uuid(self) -> str:
        """
        Generate UUID v4.
        """
        return str(uuid.uuid4())

    def initialize_client_id(self):
        """
        Ensure client ID exists.
        """
        if not self.get_config("SYSTEM_OPTIONS.CLIENT_ID"):
            client_id = self.generate_uuid()
            success = self.update_config("SYSTEM_OPTIONS.CLIENT_ID", client_id)
            if success:
                logger.info(f"Generated new client ID: {client_id}")
            else:
                logger.error("Failed to save new client ID")

    def initialize_device_id_from_fingerprint(self, device_fingerprint):
        """
        Initialize device ID from device fingerprint.
        """
        if not self.get_config("SYSTEM_OPTIONS.DEVICE_ID"):
            try:
                # Get MAC address from efuse.json as DEVICE_ID
                mac_address = device_fingerprint.get_mac_address_from_efuse()
                if mac_address:
                    success = self.update_config(
                        "SYSTEM_OPTIONS.DEVICE_ID", mac_address
                    )
                    if success:
                        logger.info(f"Got DEVICE_ID from efuse.json: {mac_address}")
                    else:
                        logger.error("Failed to save DEVICE_ID")
                else:
                    logger.error("Cannot get MAC address from efuse.json")
                    # Fallback: Get directly from device fingerprint
                    fingerprint = device_fingerprint.generate_fingerprint()
                    mac_from_fingerprint = fingerprint.get("mac_address")
                    if mac_from_fingerprint:
                        success = self.update_config(
                            "SYSTEM_OPTIONS.DEVICE_ID", mac_from_fingerprint
                        )
                        if success:
                            logger.info(
                                f"Using MAC address from fingerprint as DEVICE_ID: "
                                f"{mac_from_fingerprint}"
                            )
                        else:
                            logger.error("Failed to save fallback DEVICE_ID")
            except Exception as e:
                logger.error(f"Error initializing DEVICE_ID: {e}")

    @classmethod
    def get_instance(cls):
        """
        Get configuration manager instance.
        """
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def _apply_env_overrides(self):
        """
        Apply environment variable overrides to configuration.
        Environment variables take precedence over config file values.
        """
        env_mappings = {
            # OTA Server Configuration
            "XIAOZHI_OTA_URL": "SYSTEM_OPTIONS.NETWORK.OTA_VERSION_URL",
            "XIAOZHI_AUTH_URL": "SYSTEM_OPTIONS.NETWORK.AUTHORIZATION_URL",
            "XIAOZHI_MQTT_ENDPOINT": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.endpoint",
            "XIAOZHI_MQTT_PORT": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.port",
            "XIAOZHI_MQTT_CLIENT_ID": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.client_id",
            "XIAOZHI_MQTT_USERNAME": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.username",
            "XIAOZHI_MQTT_PASSWORD": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.password",
            "XIAOZHI_MQTT_PUBLISH_TOPIC": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.publish_topic",
            "XIAOZHI_MQTT_SUBSCRIBE_TOPIC": "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.subscribe_topic",
            # Camera/VL Configuration
            "XIAOZHI_VL_URL": "CAMERA.Local_VL_url",
            "XIAOZHI_VL_API_KEY": "CAMERA.VLapi_key",
            # Activation Configuration
            "XIAOZHI_ACTIVATION_VERSION": "SYSTEM_OPTIONS.NETWORK.ACTIVATION_VERSION",
        }

        for env_var, config_path in env_mappings.items():
            env_value = os.environ.get(env_var)
            if env_value:
                # Convert string to appropriate type
                converted_value = self._convert_env_value(env_value)
                self._set_nested_config(config_path, converted_value)
                logger.info(f"Applied env override: {env_var} -> {config_path}")

    def _convert_env_value(self, value: str) -> Any:
        """
        Convert environment variable string to appropriate type.

        Args:
            value: String value from environment

        Returns:
            Converted value (int, bool, str, etc.)
        """
        # Try boolean
        if value.lower() in ("true", "1", "yes", "on"):
            return True
        if value.lower() in ("false", "0", "no", "off"):
            return False

        # Try integer
        try:
            return int(value)
        except ValueError:
            pass

        # Try JSON (for dicts/lists)
        if value.startswith("{") or value.startswith("["):
            try:
                return json.loads(value)
            except json.JSONDecodeError:
                pass

        # Default to string
        return value

    def _set_nested_config(self, path: str, value: Any):
        """
        Set nested configuration value using dot notation.

        Args:
            path: Dot-separated path (e.g., "SYSTEM_OPTIONS.NETWORK.MQTT_INFO.endpoint")
            value: Value to set
        """
        keys = path.split(".")
        current = self._config

        for key in keys[:-1]:
            if key not in current:
                current[key] = {}
            current = current[key]

        current[keys[-1]] = value
