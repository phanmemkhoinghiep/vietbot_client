# VietBot Android Client

<div align="center">

![VietBot Logo](https://vietbot.vn/logo.png)

**An intelligent voice assistant application for Android, powered by AI**

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)

</div>

## Overview

VietBot Android Client is an open-source voice assistant application that enables natural language interactions with AI. It supports real-time speech recognition (STT), text-to-speech (TTS), emotion expression display, and customizable chat interfaces.

## Features

- 🎤 **Voice Interaction** - Natural conversation with AI assistant
- 🌐 **Multi-language Support** - Vietnamese and English
- 💬 **Text Chat** - Send text messages to the assistant
- 😊 **Emotion Display** - Visual emoji reactions based on assistant's emotional state
- 🎨 **Customizable UI** - Change fonts, colors, and themes
- 📡 **Multiple Protocols** - WebSocket support for real-time communication
- 🔒 **Privacy Focused** - No data collection without consent

## Screenshots

| Home | Chat | Settings |
|:---:|:---:|:---:|
| ![Home](docs/images/home.png) | ![Chat](docs/images/chat.png) | ![Settings](docs/images/settings.png) |

## Requirements

- Android 7.0 (API 24) or higher
- Microphone permission for voice features
- Internet connection for AI services

## Installation

### From Source

```bash
# Clone the repository
git clone https://github.com/vietbot/vietbot-android-client.git

# Navigate to project directory
cd vietbot-android-client

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### From APK

Download the latest release from the [Releases](https://github.com/vietbot/vietbot-android-client/releases) page.

## Architecture

The project follows **Clean Architecture** with **MVVM** pattern:

```
app/src/main/java/vn/vietbot/client/
├── data/                    # Data layer
│   ├── model/              # Data models (DeviceInfo, ServerFormData)
│   └── repository/         # Repository implementations
├── domain/                 # Business logic layer
│   └── usecase/           # Use cases
├── protocol/              # Network protocols (WebSocket, MQTT)
├── ui/                   # Presentation layer
│   ├── theme/           # Material 3 theming
│   ├── ChatScreen.kt    # Chat interface
│   ├── MainScreen.kt    # Main navigation
│   └── .../             # ViewModels and other screens
└── AppModule.kt          # Hilt dependency injection
```

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material 3 |
| Dependency Injection | Hilt |
| Async | Kotlin Coroutines & Flow |
| Networking | OkHttp (WebSocket) |
| MQTT | Paho MQTT |
| Audio | Opus Codec |
| Architecture | MVVM + Clean Architecture |

## Configuration

### WebSocket Server

The app connects to a WebSocket server for AI communication. Configure the server URL in the app settings.

Default server: `wss://vietbot.vn/ws/`

## Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) before submitting pull requests.

## Code of Conduct

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) before participating in our community.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Thanks to all contributors who have made this project possible
- Built with [Jetpack Compose](https://developer.android.com/compose)
- Powered by [Kotlin](https://kotlinlang.org/)

## Support

- 📖 Documentation: [docs.vietbot.vn](https://docs.vietbot.vn)
- 🐛 Issues: [GitHub Issues](https://github.com/vietbot/vietbot-android-client/issues)
- 💬 Discussions: [GitHub Discussions](https://github.com/vietbot/vietbot-android-client/discussions)

---

<div align="center">

Made with ❤️ by [VietBot](https://vietbot.vn)

</div>
