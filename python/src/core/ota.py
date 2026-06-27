import asyncio
import json
import socket

import aiohttp

from src.constants.system import SystemConstants
from src.utils.config_manager import ConfigManager
from src.utils.device_fingerprint import DeviceFingerprint
from src.utils.logging_config import get_logger


class Ota:
    _instance = None
    _lock = asyncio.Lock()

    def __init__(self):
        self.logger = get_logger(__name__)
        self.config = ConfigManager.get_instance()
        self.device_fingerprint = DeviceFingerprint.get_instance()
        self.mac_addr = None
        self.ota_version_url = None
        self.local_ip = None
        self.system_info = None

    @classmethod
    async def get_instance(cls):
        if cls._instance is None:
            async with cls._lock:
                if cls._instance is None:
                    instance = cls()
                    await instance.init()
                    cls._instance = instance
        return cls._instance

    async def init(self):
        """
        Initialize OTA instance.
        """
        self.local_ip = await self.get_local_ip()
        # Get device ID (MAC address) from config
        self.mac_addr = self.config.get_config("SYSTEM_OPTIONS.DEVICE_ID")
        # Get OTA URL
        self.ota_version_url = self.config.get_config(
            "SYSTEM_OPTIONS.NETWORK.OTA_VERSION_URL"
        )

    async def get_local_ip(self):
        """
        Asynchronously get local IP address.
        """
        try:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(None, self._sync_get_ip)
        except Exception as e:
            self.logger.error(f"Failed to get local IP: {e}")
            return "127.0.0.1"

    def _sync_get_ip(self):
        """
        Synchronously get IP address.
        """
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]

    def build_payload(self):
        """
        Build OTA request payload.
        Using same format as working xiaozhi_python_client for xiaozhi.vietdev.vn
        """
        return {
            "application": {
                "name": SystemConstants.APP_NAME,
                "version": SystemConstants.APP_VERSION
            },
            "device": {
                "model": SystemConstants.BOARD_TYPE,
                "mac_address": self.mac_addr
            }
        }

    def build_headers(self):
        """
        Build OTA request headers.
        """
        app_version = SystemConstants.APP_VERSION
        board_type = SystemConstants.BOARD_TYPE
        app_name = SystemConstants.APP_NAME

        # Get serial number from device fingerprint
        serial_number = self.device_fingerprint.get_serial_number()

        # Base headers
        headers = {
            "Device-Id": self.mac_addr,
            "Client-Id": self.config.get_config("SYSTEM_OPTIONS.CLIENT_ID"),
            "Content-Type": "application/json",
            "User-Agent": f"xiaozhi-python-client/{app_version}",
        }

        # Add Serial-Number header (required for xiaozhi.vietdev.vn)
        if serial_number:
            headers["Serial-Number"] = serial_number

        # Decide whether to add Activation-Version header based on activation version
        activation_version = self.config.get_config(
            "SYSTEM_OPTIONS.NETWORK.ACTIVATION_VERSION", "v1"
        )

        # Only add Activation-Version header for v2 protocol
        if activation_version == "v2":
            headers["Activation-Version"] = "2"  # Fixed to "2" for v2 protocol
            self.logger.debug(f"v2 protocol: Added Activation-Version header: 2")
        else:
            self.logger.debug("v1 protocol: Not adding Activation-Version header")

        return headers

    async def get_ota_config(self):
        """
        Get OTA server configuration (MQTT, WebSocket, etc.)
        """
        if not self.mac_addr:
            self.logger.error("Device ID (MAC address) not configured")
            raise ValueError("Device ID not configured")

        if not self.ota_version_url:
            self.logger.error("OTA URL not configured")
            raise ValueError("OTA URL not configured")

        headers = self.build_headers()
        payload = self.build_payload()

        # Log request details for debugging
        self.logger.info(f"OTA request URL: {self.ota_version_url}")
        self.logger.info(f"OTA request headers: {json.dumps(dict(list(headers.items())[:4]), indent=2)}")
        self.logger.info(f"OTA request payload: {json.dumps(payload, indent=2)}")

        try:
            # Use aiohttp to send async request
            timeout = aiohttp.ClientTimeout(total=10)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(
                    self.ota_version_url, headers=headers, json=payload
                ) as response:
                    # Check HTTP status code
                    if response.status != 200:
                        self.logger.error(f"OTA server error: HTTP {response.status}")
                        raise ValueError(f"OTA server returned error status code: {response.status}")

                    # Parse JSON data
                    response_data = await response.json()

                    # Debug info: Print complete OTA response
                    self.logger.info(
                        f"OTA server response: "
                        f"{json.dumps(response_data, indent=2, ensure_ascii=False)}"
                    )

                    return response_data

        except asyncio.TimeoutError:
            self.logger.error("OTA request timeout, please check network or server status")
            raise ValueError("OTA request timeout! Please try again later.")

        except aiohttp.ClientError as e:
            self.logger.error(f"OTA request failed: {e}")
            raise ValueError("Cannot connect to OTA server, please check network connection!")

    async def update_mqtt_config(self, response_data):
        """
        Update MQTT configuration.
        """
        if "mqtt" in response_data:
            self.logger.info("Found MQTT configuration")
            mqtt_info = response_data["mqtt"]
            if mqtt_info:
                # Update configuration
                success = self.config.update_config(
                    "SYSTEM_OPTIONS.NETWORK.MQTT_INFO", mqtt_info
                )
                if success:
                    self.logger.info("MQTT configuration updated")
                    return mqtt_info
                else:
                    self.logger.error("Failed to update MQTT configuration")
            else:
                self.logger.warning("MQTT configuration is empty")
        else:
            self.logger.info("No MQTT configuration found")

        return None

    async def update_websocket_config(self, response_data):
        """
        Update WebSocket configuration.
        """
        if "websocket" in response_data:
            self.logger.info("Found WebSocket configuration")
            websocket_info = response_data["websocket"]

            # Update WebSocket URL
            if "url" in websocket_info:
                self.config.update_config(
                    "SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL", websocket_info["url"]
                )
                self.logger.info(f"WebSocket URL updated: {websocket_info['url']}")

            # Update WebSocket Token
            token_value = websocket_info.get("token", "test-token") or "test-token"
            self.config.update_config(
                "SYSTEM_OPTIONS.NETWORK.WEBSOCKET_ACCESS_TOKEN", token_value
            )
            self.logger.info("WebSocket Token updated")

            return websocket_info
        else:
            self.logger.info("No WebSocket configuration found")

        return None

    async def fetch_and_update_config(self):
        """
        Fetch and update all configuration.
        """
        try:
            # Get OTA configuration
            response_data = await self.get_ota_config()

            # Update MQTT configuration
            mqtt_config = await self.update_mqtt_config(response_data)

            # Update WebSocket configuration
            websocket_config = await self.update_websocket_config(response_data)

            # Return complete response data for activation process
            return {
                "response_data": response_data,
                "mqtt_config": mqtt_config,
                "websocket_config": websocket_config,
            }

        except Exception as e:
            self.logger.error(f"Failed to fetch and update configuration: {e}")
            raise
