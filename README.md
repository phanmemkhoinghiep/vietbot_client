# VietBot Android Client

Android voice assistant client for VietBot platform.

## Features

- Voice interaction with AI assistant
- WebSocket and MQTT protocol support
- Real-time speech recognition (STT) and text-to-speech (TTS)
- Emotion expression display
- Text input for chat
- Customizable chat bubble styling (font, size, colors)

## Requirements

- Android 7.0 (API 24) or higher
- Microphone permission
- Internet connection

## Architecture

The project follows Clean Architecture with MVVM pattern:

```
app/src/main/java/vn/vietbot/client/
├── data/               # Data layer
│   ├── model/         # Data models
│   └── repository/    # Repository implementations
├── domain/            # Business logic
│   └── usecase/      # Use cases
├── protocol/         # Network protocols (WebSocket, MQTT)
├── ui/               # Presentation layer
│   ├── theme/       # Material 3 theming
│   └── ...         # Screens and ViewModels
└── AppModule.kt      # Hilt dependency injection
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **DI**: Hilt
- **Async**: Kotlin Coroutines & Flow
- **Networking**: OkHttp (WebSocket), Paho MQTT
- **Audio**: Opus codec

## Building

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/`

## License

MIT License
