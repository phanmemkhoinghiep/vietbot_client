# Spectrum Component — Architecture & Usage

## Overview

The **spectrum** component provides real-time audio spectrum visualization using FFT analysis.  
It was extracted from `lcd_display.cc` into an independent, reusable software component located in `main/features/spectrum/`.

The component uses the **espressif/dl_fft** library (`dl_rfft_f32`) for efficient real-valued FFT computation on ESP32-S3 with hardware-accelerated DSP instructions.

---

## Architecture

```
┌────────────────────────┐
│   SpectrumManager      │  ← Façade / public API
│   (spectrum_manager.h) │     Owns Analyzer + Renderer
│                        │     Manages FreeRTOS task
├────────────────────────┤
│   SpectrumAnalyzer     │  ← FFT engine
│   (spectrum_analyzer.h)│     dl_rfft_f32, Hanning window
│                        │     IIR smoothing, frame accumulation
├────────────────────────┤
│   SpectrumRenderer     │  ← LVGL canvas rendering
│   (spectrum_renderer.h)│     RGB565 pixel buffer
│                        │     Bar drawing, falling blocks, HSV colors
├────────────────────────┤
│   SpectrumConfig       │  ← Configuration struct
│   (spectrum_config.h)  │     FFT size, bar count, canvas dimensions
└────────────────────────┘
```

### Layer Responsibilities

| Layer | File | Role |
|-------|------|------|
| **SpectrumConfig** | `spectrum_config.h` | All configurable parameters in one struct |
| **SpectrumAnalyzer** | `spectrum_analyzer.h/.cc` | PCM → power spectrum via `dl_rfft_f32` |
| **SpectrumRenderer** | `spectrum_renderer.h/.cc` | Power spectrum → LVGL canvas bars |
| **SpectrumManager** | `spectrum_manager.h/.cc` | High-level façade with task lifecycle |

---

## Configuration (`SpectrumConfig`)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `fft_size` | 512 | FFT window size (must be power of 2) |
| `bar_count` | 40 | Number of spectrum bars displayed |
| `canvas_width` | *(set by caller)* | Canvas width in pixels |
| `canvas_height` | *(set by caller)* | Canvas height in pixels |
| `bar_max_height` | 0 | Max bar height (0 = canvas_height / 2) |
| `frames_to_accumulate` | 3 | PCM frames averaged before FFT |
| `smoothing_factor` | 0.3f | IIR low-pass for power spectrum smoothing |
| `render_interval_ms` | 33 | Display refresh interval (~30 FPS) |
| `task_stack_size` | 4096 | FreeRTOS task stack |
| `task_priority` | 3 | FreeRTOS task priority |
| `task_core` | 1 | Pinned CPU core |

---

## API Usage

### Basic Usage

```cpp
#include "spectrum_manager.h"

// 1. Configure
spectrum::SpectrumConfig cfg;
cfg.canvas_width  = 320;
cfg.canvas_height = 240;
cfg.fft_size      = 512;
cfg.bar_count     = 40;

// 2. Create manager
auto mgr = std::make_unique<spectrum::SpectrumManager>(cfg);

// 3. Provide an LVGL parent object and start
lv_obj_t* parent = lv_scr_act();  // or any LVGL container
mgr->Start(parent);

// 4. Feed audio data from your audio pipeline
int16_t* buf = mgr->AllocateAudioBuffer(frame_size);
// ... fill buf with PCM samples ...
mgr->FeedAudioData(buf, frame_size);
mgr->ReleaseAudioBuffer(buf);

// 5. Stop when done
mgr->Stop();
```

### Periodic Callback

You can register a callback that runs periodically (every ~1 second) with the LVGL lock held.
This is useful for updating overlaid UI elements (e.g., a music player progress bar):

```cpp
mgr->SetPeriodicCallback([this]() {
    UpdateMusicUI();  // Your UI update logic
});
```

### Accessing Internal Objects

```cpp
// Get the LVGL canvas object (e.g., to add child widgets on top)
lv_obj_t* canvas = mgr->GetRenderer()->GetCanvas();

// Get current config
const auto& config = mgr->GetConfig();
```

---

## FFT Processing Details

### dl_rfft_f32 Library

The component uses `dl_rfft_f32` from **espressif/dl_fft v0.3.1**:

- **Input:** Real-valued float array, 16-byte aligned (`heap_caps_aligned_alloc`)
- **Output format (in-place):**
  - `x[0]` = DC component (real)
  - `x[1]` = Nyquist component (real)
  - `x[2k]` = Re(k), `x[2k+1]` = Im(k) for k = 1 .. N/2 - 1
- **Power:** `magnitude[k] = sqrt(re² + im²)`, converted to dB scale

### Processing Pipeline

```
PCM int16 → float conversion → Hanning window → frame accumulation (3x)
    → dl_rfft_f32 → power spectrum (dB) → IIR smoothing → bar heights
```

1. **Frame Accumulation:** 3 consecutive PCM frames are averaged to reduce noise
2. **Hanning Window:** Pre-computed windowing function reduces spectral leakage  
3. **FFT:** `dl_rfft_f32_run()` — single-call real FFT
4. **Power Spectrum:** Magnitude in dB with configurable range (0–60 dB)
5. **IIR Smoothing:** `new = α × current + (1-α) × previous` for visual smoothness
6. **Bass Attenuation:** Bins 1–5 get progressive scaling (×0.2 to ×0.8) to avoid bass dominance

---

## Rendering Details

### Bar Drawing

- Direct RGB565 pixel buffer manipulation for maximum performance
- Each bar: 2px gap between bars, calculated width from `canvas_width / bar_count`
- Height mapped from normalized power spectrum value (0.0–1.0) to pixels
- Bars drawn bottom-up from `canvas_height`

### Color Scheme

- **HSV Rainbow Cycling:** Each bar gets a hue based on its index: `hue = (index × 360 / bar_count) % 360`
- Saturation = 255, Value = 255 → vivid rainbow spectrum
- Colors are converted to RGB565 for direct buffer writing

### Falling Block Animation

- Each bar has a "peak indicator" block that falls with gravity
- Block stays at peak for a short duration, then decays downward
- Random colors assigned to falling blocks for visual variety

---

## Integration with lcd_display

In `lcd_display.cc`, the spectrum component is used as follows:

```cpp
// StartFFT() — called when music playback begins
void LcdDisplay::StartFFT() {
    spectrum::SpectrumConfig cfg;
    cfg.canvas_width  = width_;
    cfg.canvas_height = height_;
    // ... configure ...
    
    spectrum_manager_ = std::make_unique<spectrum::SpectrumManager>(cfg);
    spectrum_manager_->SetPeriodicCallback([this]() { UpdateMusicUI(); });
    
    lvgl_port_lock(0);
    spectrum_manager_->Start(content_);
    BuildMusicUI();     // Overlay music player widgets
    lvgl_port_unlock();
    
    SetMediaOverlayActive(true);
}

// StopFFT() — called when music playback stops  
void LcdDisplay::StopFFT() {
    spectrum_manager_->Stop();
    
    lvgl_port_lock(0);
    DestroyMusicUI();   // Remove music player widgets
    lvgl_port_unlock();
    
    SetMediaOverlayActive(false);
}
```

The audio pipeline feeds data via:
- `MakeAudioBuffFFT()` → `spectrum_manager_->AllocateAudioBuffer()`
- `FeedAudioDataFFT()` → `spectrum_manager_->FeedAudioData()`
- `ReleaseAudioBuffFFT()` → `spectrum_manager_->ReleaseAudioBuffer()`

---

## File Structure

```
main/features/spectrum/
├── spectrum_config.h       # Configuration struct
├── spectrum_analyzer.h     # FFT engine header
├── spectrum_analyzer.cc    # FFT engine implementation
├── spectrum_renderer.h     # LVGL renderer header
├── spectrum_renderer.cc    # LVGL renderer implementation  
├── spectrum_manager.h      # Façade API header
├── spectrum_manager.cc     # Façade implementation
└── fft.md                  # This documentation
```

---

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| espressif/dl_fft | ≥ 0.3.1 | Real-valued FFT (`dl_rfft_f32`) |
| LVGL | ≥ 9.0 | Canvas widget for rendering |
| esp_lvgl_port | ≥ 2.6.0 | LVGL thread-safety (`lvgl_port_lock/unlock`) |
| FreeRTOS | (ESP-IDF) | Task management |

---

## Changes from Original Implementation

| Aspect | Before (in lcd_display) | After (spectrum component) |
|--------|------------------------|--------------------------|
| FFT | Custom `compute()` with separate Re/Im arrays | `dl_rfft_f32` with interleaved output |
| Memory | Manual `new[]` / `delete[]` | `heap_caps_aligned_alloc` (16-byte aligned) for FFT, SPIRAM for large buffers |
| Canvas | Shared `canvas_` with QR code | Own canvas per SpectrumRenderer |
| Task | `periodicUpdateTask` mixed FFT + UI | Separated: SpectrumManager task + callback |
| Config | Hard-coded constants | Centralized `SpectrumConfig` struct |
| Coupling | Tightly coupled to LcdDisplay | Fully independent, any LVGL parent |
