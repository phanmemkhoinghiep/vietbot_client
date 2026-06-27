# Vietbot Client

Multi-platform client for Vietbot voice assistant. Each platform lives in
its own subdirectory and is built/deployed independently.

| Subdir | Platform | Stack |
|---|---|---|
| [`android/`](android/) | Android phone + smart glasses | Kotlin / Jetpack Compose / Hilt |
| [`esp32/`](esp32/) | ESP32 smart glasses | C / ESP-IDF / LVGL |
| [`python/`](python/) | Python reference / Pi / display client | Python 3 / asyncio |

## Repo layout

```
vietbot_client/
├── android/    Android client (phone mic/speaker + BLE glasses camera)
├── esp32/      ESP32 firmware for HeyCyan smart glasses
└── python/     Python reference client (Whisplay display, Pi deployments)
```

## Building

See each subdir's README for platform-specific build instructions.

- Android: `cd android && ./gradlew assembleDebug`
- ESP32: `cd esp32 && idf.py build`
- Python: `cd python && pip install -r requirements.txt`

## History

This repo was reorganized on 2026-06-27 to consolidate three independent
client platforms under a single source of truth. Pre-reorganization
history was discarded; current history starts from the snapshot of
`vietbot_android_client`, `xiaozhi-esp32_vietnam`, and
`xiaozhi_python_client_v2` taken at that date.
