# Xiaozhi Python Client v2 - Whisplay HAT

Xiaozhi Voice Client cho Raspberry Pi với Whisplay HAT (240×280 LCD + RGB LED).

## Tính năng mới (v2.1)

### 🎯 Binary Protocol Support
- ✅ Binary Protocol v1, v2, v3 cho backward compatibility với ESP32
- ✅ Tự động phát hiện phiên bản protocol từ server

### 🎵 Audio Buffer Management
- ✅ Enhanced buffer manager với glitch detection
- ✅ Automatic recovery từ buffer underrun/overflow
- ✅ Adaptive buffer size dựa trên network conditions

### ⏱️ Device State Management
- ✅ State timeout mechanisms
- ✅ Automatic recovery từ stuck states
- ✅ State history tracking cho debugging

### 🧪 Integration Tests
- ✅ Protocol communication tests
- ✅ Binary encoding/decoding tests
- ✅ MQTT protocol tests

### 📚 Documentation
- ✅ API documentation
- ✅ Protocol flow documentation
- ✅ Troubleshooting guide

## Cài đặt

```bash
# Copy project đến Raspberry Pi
scp -r xiaozhi_python_client_v2 pi@<pi-ip>:~/

# Trên Raspberry Pi
cd ~/xiaozhi_python_client_v2
source vietbot_env/bin/activate  # Nếu có venv

# Cài đặt dependencies
pip3 install -r requirements.txt

# Cài đặt các gói hệ thống cần thiết
sudo apt-get update
sudo apt-get install python3-spidev python3-pil python3-numpy
```

## Cấu hình OTA Server

Để thay đổi OTA server (mặc định: `https://xiaozhi.vietdev.vn/ota/`):

1. Copy file config mẫu:
```bash
cp config/config.example.json config/config.json
```

2. Chỉnh sửa `config/config.json`:
```json
{
  "SYSTEM_OPTIONS": {
    "NETWORK": {
      "OTA_VERSION_URL": "https://your-ota-server.com/ota/",
      "AUTHORIZATION_URL": "https://your-server.com/"
    }
  }
}
```

## Chạy chương trình

```bash
# Chạy mặc định (CLI mode, MQTT protocol)
python3 main.py

# GUI mode
python3 main.py --mode gui

# Skip activation (test mode)
python3 main.py --skip-activation
```

## Tính năng

- ✅ Màn hình LCD 240×280 với hiển thị căn giữa
- ✅ RGB LED báo trạng thái (Xanh lá: Sẵn sàng, Cam: Kết nối, Xanh dương: Nghe, Đỏ: Lỗi)
- ✅ Nút bấm GPIO 17 (Short press: Bắt đầu/ngắt, Long press: Hành động khác)
- ✅ Audio codec WM8960 16kHz
- ✅ Kết nối MQTT Gateway với UDP cho audio
- ✅ Wake word detection (Sherpa-ONNX)
- ✅ MCP (Model Context Protocol) integration

## Cấu trúc project

```
xiaozhi_python_client_v2/
├── main.py                      # Entry point
├── whisplay_display.py          # Driver màn hình LCD
├── whisplay_rgb_led.py          # Driver RGB LED
├── display/                     # Display utilities
├── src/
│   ├── audio_codecs/           # WM8960, Opus
│   ├── audio_processing/       # VAD, buffer manager
│   ├── constants/              # Configuration constants
│   ├── core/                   # OTA, System init
│   ├── display/                # GUI adapter
│   ├── hardware/               # Button handler
│   ├── mcp/                    # Model Context Protocol
│   ├── protocols/              # MQTT protocol (main), WebSocket (legacy)
│   └── utils/                  # Config, logging
├── tests/                      # Integration tests
├── config/                      # Cấu hình
│   └── config.example.json     # Config mẫu
└── emoji/                      # SVG emoji resources
```

## Troubleshooting

### Màn hình không hiển thị
```bash
# Kiểm tra SPI
lsmod | grep spidev

# Kiểm tra GPIO
gpioinfo 0  # BCM GPIO 17
```

### Audio không hoạt động
```bash
# Kiểm tra WM8960
aplay -l
arecord -l
```

### Kết nối OTA thất bại
Kiểm tra file `config/config.json` và đảm bảo OTA_URL đúng.

---

## API Reference

### Application Class

```python
from src.application import Application

# Get singleton instance
app = Application.get_instance()

# Run application (MQTT protocol only)
await app.run(mode="cli")

# Shutdown
await app.shutdown()
```

### MQTT Protocol

```python
from src.protocols.mqtt_protocol import MqttProtocol

# Create MQTT protocol instance
mqtt = MqttProtocol(loop=asyncio.get_event_loop())

# Connect to MQTT gateway
await mqtt.connect()

# Send text message (JSON control data)
await mqtt.send_text(json.dumps({"type": "hello"}))

# Send audio data (Opus encoded via UDP)
await mqtt.send_audio(opus_data)

# Enable auto-reconnect
mqtt.enable_auto_reconnect(enabled=True, max_attempts=5)
```

### Audio Buffer Manager

```python
from src.audio_processing.audio_buffer_manager import AudioBufferManager

buffer = AudioBufferManager(
    min_frames=5,     # ~300ms minimum
    max_frames=50,    # ~3000ms maximum
    target_frames=15  # ~900ms optimal
)

# Write audio
buffer.write(audio_array, seq=1)

# Read audio
audio = buffer.read(num_frames=1)

# Get statistics
stats = buffer.get_status_dict()
print(f"Buffer level: {stats['level_percent']}%")
print(f"Latency: {stats['latency_ms']:.1f}ms")
```

---

## Protocol Flow

### MQTT + UDP (Primary Protocol)

#### 1. Connection Phase
```
Client → MQTT Gateway: MQTT CONNECT
  - clientId: "groupId@@@mac_address_no_colon@@@uuid"
  - username: base64(JSON with user data)
  - password: HMAC-SHA256 signature of "clientId|username"

MQTT Gateway → Client: CONNACK (return code 0 = success)
```

#### 2. Hello Exchange
```json
// Client → Gateway (via MQTT publish to "device-server" topic)
{
  "type": "hello",
  "version": 3,
  "features": {"mcp": true},
  "transport": "udp",
  "audio_params": {
    "format": "opus",
    "sample_rate": 16000,
    "channels": 1,
    "frame_duration": 60
  }
}

// Gateway → Client (via MQTT subscribe/forward)
{
  "type": "hello",
  "transport": "udp",
  "session_id": "abc123",
  "udp": {
    "server": "audio.server.com",
    "port": 8001,
    "key": "aes_key_hex",
    "nonce": "aes_nonce_hex"
  }
}
```

#### 3. Audio Exchange (via UDP)
```
Client → Server: UDP packets (AES-CTR encrypted)
  - Format: nonce(16 bytes) + encrypted_opus_data
  - Nonce format: 0x01 + 0x00*3 + length(2) + original_nonce(16) + sequence(8)
  - AES-CTR mode with 256-bit key

Server → Client: UDP packets (AES-CTR encrypted)
  - Same format as client → server
  - Decrypted using shared key and nonce
```

---

## Testing

```bash
# Run all tests
pytest tests/

# Run specific test
pytest tests/protocols/__init__.py::TestBinaryProtocol
pytest tests/protocols/__init__.py::TestMQTTProtocol

# Run with coverage
pytest --cov=src tests/

# Standalone test
python tests/protocols/__init__.py
```

---

## Development

### Adding New MCP Tool

```python
# 1. Create tool file: src/mcp/tools/my_tool.py

from src.mcp.mcp_server import Property, PropertyList, PropertyType

def my_tool_callback(args):
    """Tool implementation"""
    return "result"

# 2. Register in McpServer.add_common_tools()
properties = PropertyList([
    Property("param1", PropertyType.STRING),
    Property("param2", PropertyType.INTEGER, default_value=10)
])
self.add_tool(("my_tool", "Tool description", properties, my_tool_callback))
```

### Adding New Display Type

```python
# 1. Create: src/display/my_display.py

from src.display.base_display import BaseDisplay

class MyDisplay(BaseDisplay):
    async def set_callbacks(self, **callbacks):
        # Implement callback setup
        pass

    async def update_status(self, status, connected):
        # Implement status update
        pass

    # ... implement other abstract methods
```

---

## License

MIT License
