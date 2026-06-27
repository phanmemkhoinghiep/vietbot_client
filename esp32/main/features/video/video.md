# Video Player Feature — AVI Playback on ESP32-S3

## Overview

This component provides AVI video playback from SD card on ESP32-S3 boards with LCD displays.
It integrates the `espressif/avi_player` library with the existing project audio/display infrastructure.

---

## Files

| File | Description |
|------|-------------|
| `features/video/video_player.h` | `VideoPlayer` class — full public API, structs, constants |
| `features/video/video_player.cc` | Implementation — AVI demux, JPEG decode, LCD draw, PCM audio output |
| `features/video/video.md` | This document |

### Modified Files

| File | Change |
|------|--------|
| `display/lcd_display.h` | Added `GetPanelHandle()` public accessor for direct LCD rendering |
| `CMakeLists.txt` | Added `features/video/video_player.cc` to SOURCES and `features/video` to INCLUDE_DIRS |
| `application.cc` | Added test block in `Application::Start()` with render mode selection |

---

## Architecture

```
SD Card (.avi)
    │
    ▼
espressif/avi_player  (AVI demuxer — FreeRTOS task, core 1, prio 6)
    │
    ├── video_cb  →  Copy MJPEG data to pending buffer + signal semaphore
    │                 (AVI callback returns immediately — non-blocking)
    │       │
    │       ▼  [Binary semaphore]
    │
    │   Video Render Task  (independent FreeRTOS task, core 0, prio 5)
    │       │
    │       ├── Copy pending MJPEG → local decode buffer (mutex, ~1ms)
    │       ├── JPEG decode → RGB565 back-buffer (esp_new_jpeg)
    │       │
    │       ├── [DirectLcd mode]
    │       │       └── esp_lcd_panel_draw_bitmap() → LCD (bypasses LVGL)
    │       │
    │       └── [LvglCanvas mode]
    │               └── memcpy → canvas_buf_ → lv_obj_invalidate()
    │                   → LVGL refresh pipeline → LCD
    │
    └── audio_cb  →  raw PCM int16_t
            │
            ▼
        Volume scale  +  channel conversion (mono↔stereo)
            │
            ▼
        AudioCodec::OutputData()  ──►  I2S  ──►  Speaker
```

### Task Architecture

| Task | Core | Priority | Stack | Purpose |
|------|------|----------|-------|---------|
| AVI demuxer | 1 (APP_CPU) | 6 | 8 KB (PSRAM) | Read AVI from SD, demux, dispatch callbacks |
| Video render | 0 (PRO_CPU) | 5 | 8 KB (internal) | JPEG decode + LCD/canvas draw |
| LVGL refresh | 0 | (varies) | — | Canvas invalidation triggers LCD update |

**Key benefit**: The AVI demuxer is no longer blocked by JPEG decode + LCD draw latency.
The AVI callback just copies MJPEG data (~1ms) and returns, allowing the demuxer to
pre-read the next chunk from SD while the render task processes the current frame.

---

## Rendering Modes

### `VideoRenderMode::DirectLcd` (default)
- Uses `esp_lcd_panel_draw_bitmap()` directly
- Bypasses LVGL completely — **maximum frame rate**
- Best for full-screen video playback
- Cannot overlay LVGL UI elements on top of video

### `VideoRenderMode::LvglCanvas`
- Creates an LVGL canvas widget (`lv_canvas_create`) referencing `DisplayQRCode` pattern
- Decoded frames are copied to canvas buffer, then `lv_obj_invalidate()` triggers refresh
- Goes through LVGL's refresh pipeline — adds some latency
- Allows LVGL UI overlays on top of video (future feature)
- Useful for **A/B performance comparison testing**

Switch modes with:
```cpp
vp.SetRenderMode(VideoRenderMode::DirectLcd);   // max FPS
vp.SetRenderMode(VideoRenderMode::LvglCanvas);  // LVGL pipeline
```

---

## Dependencies (already in `idf_component.yml`)

| Component | Purpose |
|-----------|---------|
| `espressif/avi_player ^2.0.0` | AVI demuxer + FPS timer |
| `espressif/esp_new_jpeg ^0.6.1` | Software JPEG decoder (MJPEG → RGB565) |
| `espressif/esp_lcd_panel_ops` | `esp_lcd_panel_draw_bitmap()` for direct LCD write |
| `espressif/esp_audio_codec` | Audio codec abstraction (I2S output) |
| LVGL v9.3 | Canvas rendering mode (optional) |

---

## Key Design Decisions

### 1. Independent Render Task (NEW)
The AVI callback (`HandleVideoFrame`) no longer performs JPEG decode or LCD draw.
Instead, it copies raw MJPEG data to a pending buffer and signals a binary semaphore.
A dedicated FreeRTOS render task on core 0 handles the actual decode + draw.

**Benefits:**
- AVI demuxer (core 1) is not blocked by JPEG decode or LCD draw latency
- AVI task can pre-read the next SD card chunk while render processes current frame
- Decouples I/O-bound (SD read) from compute-bound (JPEG decode) workloads
- Mutex held only during fast memcpy (~1ms), not during 30-50ms decode+draw

### 2. Dual Render Modes
Two rendering strategies selectable at runtime via `SetRenderMode()`:
- **DirectLcd**: `esp_lcd_panel_draw_bitmap()` — bypasses LVGL, maximum throughput
- **LvglCanvas**: LVGL canvas widget following the `DisplayQRCode` pattern — goes
  through LVGL's refresh pipeline, useful for testing and future UI overlay

### 3. Direct LCD Rendering (no LVGL)
Video frames are drawn with `esp_lcd_panel_draw_bitmap()` instead of going through LVGL.
This eliminates LVGL mutex overhead and vsync wait, enabling the highest possible frame rate.

### 4. Double-Buffered PSRAM Frames
Two RGB565 frame buffers (each up to `VIDEO_MAX_WIDTH × VIDEO_MAX_HEIGHT × 2` bytes) are
allocated in PSRAM. While one buffer is drawn to the LCD, the JPEG decoder writes the next
frame into the other — completely tear-free, zero copy.

### 3. JPEG Decode via `esp_new_jpeg`
The `esp_new_jpeg` software decoder is already a project dependency and outputs `RGB565_LE`
natively — the exact pixel format expected by the SPI/RGB LCD panels used in this project.

### 4. Automatic Sample Rate Switching
`AviAudioClockCallback` is invoked once per file with the AVI audio stream parameters.
It calls `AudioCodec::SetOutputSampleRate()` to reconfigure the I2S clock without restarting
the codec. On `Stop()` / `HandlePlayEnd()`, the original sample rate is restored with `-1`.

### 5. Channel Conversion (mono ↔ stereo)
`OutputAudioPcm()` detects mismatches between the AVI audio channel count and the codec's
`output_channels()`:
- Mono AVI + stereo codec → duplicate each sample to L+R.
- Stereo AVI + mono codec → average L+R samples.
- Same count → direct pass-through.

### 6. Singleton Pattern
Consistent with `Application::GetInstance()` and other project singletons.
Prevents duplicate resource allocation across the codebase.

### 7. Virtual Hooks for Extension
Three protected virtual methods allow subclasses to customize behavior without modifying
the core player:

```cpp
virtual void OnVideoFrameReady(uint16_t* rgb565, uint16_t w, uint16_t h);
virtual void OnAudioFrameReady(int16_t* pcm, size_t samples, int channels);
virtual void OnPlaybackStateChanged(VideoPlayerState old, VideoPlayerState new);
```

---

## Configuration Constants (`video_player.h`)

| Constant | Default | Description |
|----------|---------|-------------|
| `VIDEO_AVI_BUFFER_SIZE` | 60 KB | AVI internal read buffer |
| `VIDEO_AVI_TASK_STACK` | 8 KB | AVI player FreeRTOS task stack |
| `VIDEO_AVI_TASK_PRIORITY` | 6 | AVI demuxer task priority |
| `VIDEO_AVI_TASK_CORE` | 1 (APP_CPU) | AVI demuxer pinned core |
| `VIDEO_RENDER_TASK_STACK` | 8 KB | Render task stack (internal RAM) |
| `VIDEO_RENDER_TASK_PRIORITY` | 5 | Render task priority (< AVI for backpressure) |
| `VIDEO_RENDER_TASK_CORE` | 0 (PRO_CPU) | Render task pinned core |
| `VIDEO_MAX_WIDTH` | 320 | Max video width (frame buffer sizing) |
| `VIDEO_MAX_HEIGHT` | 240 | Max video height (frame buffer sizing) |
| `VIDEO_DEFAULT_DIRECTORY` | `"videos"` | Sub-folder on SD card |
| `VIDEO_MAX_FILES` | 256 | Max files per directory scan |

---

## State Machine

```
  Idle ──Play()──► Loading ──success──► Playing
   ▲                  │                   │
   │                  │ error             ├──Pause()──► Paused
   │                  ▼                   │              │
   │               Error                  │           Resume()
   │                                      │              │
   └───────Stop()────────────────────────-┘◄─────────────┘
                                          │
                                      PlayEnd
                                          │
                                        Idle
```

---

## Usage Example

```cpp
#include "video_player.h"
#include "lcd_display.h"
#include "board.h"

// After Board, Display and SdCard are initialized:

auto* lcd = dynamic_cast<LcdDisplay*>(Board::GetInstance().GetDisplay());
auto& vp  = VideoPlayer::GetInstance();

// Initialize (pass Display* for LVGL canvas support)
vp.Initialize(
    lcd->GetPanelHandle(),
    lcd->width(), lcd->height(),
    Board::GetInstance().GetAudioCodec(),
    Board::GetInstance().GetSdCard(),
    lcd  // Display* for LVGL canvas mode (nullptr = DirectLcd only)
);

// Select render mode (call before Play)
vp.SetRenderMode(VideoRenderMode::DirectLcd);   // Max FPS (default)
// vp.SetRenderMode(VideoRenderMode::LvglCanvas); // LVGL pipeline test

// Register callbacks (optional)
vp.SetEndCallback([](const std::string& path) {
    ESP_LOGI("App", "Finished: %s", path.c_str());
});

// Scan SD card for AVI files in /sdcard/videos/
size_t count = vp.ScanDirectory();
ESP_LOGI("App", "Found %zu videos", count);

// Play a specific file
vp.Play("/sdcard/videos/demo.avi");

// Or play by name from current directory
vp.PlayFile("demo.avi");

// Playlist navigation
vp.Next();
vp.Prev();

// Control
vp.Pause();
vp.Resume();
vp.Stop();

// Set volume (1.0 = 100%)
vp.SetVolume(0.8f);

// Query stats
auto stats = vp.GetStats();
ESP_LOGI("App", "FPS approx: %.1f  Decode avg: %.1f ms",
         1000.0f / stats.avg_decode_ms, stats.avg_decode_ms);
```

---

## Recommended AVI File Format

For best performance on ESP32-S3 with ILI9341 / ST7789 (320×240):

| Parameter | Recommended |
|-----------|-------------|
| Container | AVI (RIFF) |
| Video codec | MJPEG |
| Resolution | 320×240 or smaller |
| Frame rate | 10–20 fps |
| Audio codec | PCM (uncompressed) |
| Audio sample rate | 16000 or 44100 Hz |
| Audio channels | 1 (mono) or 2 (stereo) |
| Audio bits | 16-bit |

**Conversion command (FFmpeg):**
```bash
ffmpeg -i input.mp4 \
  -vf "scale=320:240" \
  -r 15 \
  -vcodec mjpeg -q:v 5 \
  -acodec pcm_s16le -ar 16000 -ac 1 \
  output.avi

ffmpeg -i sample-5s.mp4 -vf "scale=320:240" -r 15 -vcodec mjpeg -q:v 5 -acodec pcm_s16le -ar 16000 -ac 1 sample-5s.avi
```

> For larger displays (e.g. 480×320), increase `VIDEO_MAX_WIDTH` / `VIDEO_MAX_HEIGHT`
> constants in `video_player.h` accordingly. Frame buffer PSRAM usage = `W × H × 2 × 2` bytes.

---

## Memory Usage

| Resource | Location | Size |
|----------|----------|------|
| Frame buffer × 2 | PSRAM | 2 × 320×240×2 = 307 200 bytes ≈ 300 KB |
| MJPEG pending buffer | PSRAM | 60 KB |
| MJPEG decode buffer | PSRAM | 60 KB |
| AVI read buffer | PSRAM (task heap) | 60 KB |
| JPEG I/O structs | PSRAM | ~64 bytes |
| AVI player task stack | PSRAM | 8 KB |
| Render task stack | Internal RAM | 8 KB |
| Canvas buffer (LvglCanvas mode) | PSRAM | 320×240×2 = 150 KB (optional) |
| **Total PSRAM** | | **≈ 488 KB** (DirectLcd) / **≈ 638 KB** (LvglCanvas) |
| AudioCodec output vector | Internal RAM (temporary) | ~4 KB per frame |

---

## Changelog

### v2.0 — Independent Render Task + LVGL Canvas

**New features:**
- `HandleVideoFrame` now runs in an **independent FreeRTOS render task** (core 0, priority 5)
  - AVI callback only copies MJPEG data + signals semaphore → returns immediately
  - JPEG decode + LCD draw happen in the render task, decoupled from AVI demuxer
  - Mutex held only during fast memcpy (~1ms for 30KB MJPEG frame)
- New **`DrawFrameToCanvas()`** method using LVGL canvas (following `DisplayQRCode` pattern)
  - Creates `lv_canvas_create()` with PSRAM-backed RGB565 buffer
  - Copies decoded frame to canvas buffer → `lv_obj_invalidate()` → LVGL refresh
  - For A/B testing LVGL pipeline performance vs direct LCD writes
- New **`VideoRenderMode`** enum: `DirectLcd` | `LvglCanvas`
  - Switchable via `SetRenderMode()` before calling `Play()`
- `Initialize()` now accepts optional `Display* display` parameter for LVGL canvas support
- Added render task configuration constants: `VIDEO_RENDER_TASK_STACK`, `PRIORITY`, `CORE`

**Modified files:**
- `video_player.h` — Added `VideoRenderMode`, render task members, canvas members, updated `Initialize` signature
- `video_player.cc` — Rewrote `HandleVideoFrame` (copy+signal), added `RenderTaskLoop`, `DrawFrameToCanvas`, `CreateVideoCanvas`, `DestroyVideoCanvas`, `SetRenderMode`
- `application.cc` — Updated test block to pass `Display*` and set `VideoRenderMode::LvglCanvas`
- `video.md` — Updated documentation with new architecture, task table, render modes
