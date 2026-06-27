/**
 * @file spectrum_analyzer.cc
 * @brief Implementation of the FFT-based audio spectrum analyzer.
 *
 * Uses espressif/dl_fft real-FFT (dl_rfft_f32) for efficient single-pass
 * frequency-domain analysis of real-valued PCM audio data.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#include "spectrum_analyzer.h"

#include <cmath>
#include <esp_heap_caps.h>
#include <esp_log.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static const char* TAG = "SpectrumAnalyzer";

namespace spectrum {

// ---------------------------------------------------------------------------
// Construction / Destruction
// ---------------------------------------------------------------------------

SpectrumAnalyzer::SpectrumAnalyzer(const SpectrumConfig& config)
    : config_(config) {}

SpectrumAnalyzer::~SpectrumAnalyzer() {
    Deinitialize();
}

// ---------------------------------------------------------------------------
// Initialize — allocate all buffers and the dl_rfft handle
// ---------------------------------------------------------------------------

bool SpectrumAnalyzer::Initialize() {
    if (initialized_) return true;

    const int N = config_.fft_size;

    // 1. dl_rfft requires 16-byte aligned input buffer (N floats for real FFT)
    fft_buffer_ = static_cast<float*>(
        heap_caps_aligned_alloc(16, N * sizeof(float), MALLOC_CAP_SPIRAM));
    if (!fft_buffer_) {
        ESP_LOGE(TAG, "Failed to allocate fft_buffer_ (%d floats)", N);
        return false;
    }

    // 2. Hanning window coefficients
    hanning_window_ = static_cast<float*>(
        heap_caps_malloc(N * sizeof(float), MALLOC_CAP_SPIRAM));
    if (!hanning_window_) {
        ESP_LOGE(TAG, "Failed to allocate hanning_window_");
        Deinitialize();
        return false;
    }
    for (int i = 0; i < N; i++) {
        hanning_window_[i] = 0.5f * (1.0f - cosf(2.0f * M_PI * i / (N - 1)));
    }

    // 3. Output power spectrum (N/2 bins)
    power_spectrum_ = static_cast<float*>(
        heap_caps_malloc((N / 2) * sizeof(float), MALLOC_CAP_SPIRAM));
    if (!power_spectrum_) {
        ESP_LOGE(TAG, "Failed to allocate power_spectrum_");
        Deinitialize();
        return false;
    }
    memset(power_spectrum_, 0, (N / 2) * sizeof(float));

    // 4. Accumulation buffer (audio_frame_size samples)
    accumulate_buffer_ = static_cast<int16_t*>(
        heap_caps_malloc(config_.audio_frame_size * sizeof(int16_t), MALLOC_CAP_SPIRAM));
    if (!accumulate_buffer_) {
        ESP_LOGE(TAG, "Failed to allocate accumulate_buffer_");
        Deinitialize();
        return false;
    }
    memset(accumulate_buffer_, 0, config_.audio_frame_size * sizeof(int16_t));

    // 5. Initialize the dl_rfft_f32 handle (tables stored in internal RAM if possible)
    rfft_handle_ = dl_rfft_f32_init(N, MALLOC_CAP_8BIT);
    if (!rfft_handle_) {
        ESP_LOGE(TAG, "dl_rfft_f32_init(%d) failed", N);
        Deinitialize();
        return false;
    }

    accumulate_count_ = 0;
    initialized_ = true;
    ESP_LOGI(TAG, "Initialized (fft_size=%d, frame=%d, accum=%d)",
             N, config_.audio_frame_size, config_.accumulate_frames);
    return true;
}

// ---------------------------------------------------------------------------
// Deinitialize — release all memory
// ---------------------------------------------------------------------------

void SpectrumAnalyzer::Deinitialize() {
    if (rfft_handle_) {
        dl_rfft_f32_deinit(rfft_handle_);
        rfft_handle_ = nullptr;
    }
    if (fft_buffer_) {
        heap_caps_free(fft_buffer_);
        fft_buffer_ = nullptr;
    }
    if (hanning_window_) {
        heap_caps_free(hanning_window_);
        hanning_window_ = nullptr;
    }
    if (power_spectrum_) {
        heap_caps_free(power_spectrum_);
        power_spectrum_ = nullptr;
    }
    if (accumulate_buffer_) {
        heap_caps_free(accumulate_buffer_);
        accumulate_buffer_ = nullptr;
    }
    accumulate_count_ = 0;
    initialized_ = false;
}

// ---------------------------------------------------------------------------
// Reset — clear state without freeing memory
// ---------------------------------------------------------------------------

void SpectrumAnalyzer::Reset() {
    if (!initialized_) return;

    accumulate_count_ = 0;
    memset(accumulate_buffer_, 0, config_.audio_frame_size * sizeof(int16_t));
    memset(power_spectrum_, 0, (config_.fft_size / 2) * sizeof(float));
}

// ---------------------------------------------------------------------------
// ProcessPcmFrame — accumulate + FFT
// ---------------------------------------------------------------------------

bool SpectrumAnalyzer::ProcessPcmFrame(const int16_t* pcm_data, int sample_count) {
    if (!initialized_ || !pcm_data) return false;

    const int frame_size = config_.audio_frame_size;
    const int count = (sample_count < frame_size) ? sample_count : frame_size;

    // ---- Phase 1: accumulate frames ----
    if (accumulate_count_ < config_.accumulate_frames) {
        for (int i = 0; i < count; i++) {
            accumulate_buffer_[i] += pcm_data[i];
        }
        accumulate_count_++;
        return false;  // Not ready yet
    }

    // ---- Phase 2: perform FFT on accumulated data ----
    const int N        = config_.fft_size;
    const int hop_size = N;
    const int num_segs = 1 + (frame_size - N) / hop_size;

    // Power spectrum uses IIR smoothing: new = (old + sum_segments) / num_segs
    // (matches original behavior — keeps the visual bars from jumping too fast)
    for (int seg = 0; seg < num_segs; seg++) {
        const int start = seg * hop_size;
        if (start + N > frame_size) break;

        // Convert int16 → float, apply Hanning window
        for (int i = 0; i < N; i++) {
            float sample = accumulate_buffer_[start + i] / 32768.0f;
            fft_buffer_[i] = sample * hanning_window_[i];
        }

        // In-place real FFT via dl_rfft_f32
        dl_rfft_f32_run(rfft_handle_, fft_buffer_);

        // Accumulate power spectrum from rfft output
        // Output format: x[0]=DC, x[1]=Nyquist,
        //   x[2k]=re(k), x[2k+1]=im(k)  for k = 1 .. N/2-1
        power_spectrum_[0] += fft_buffer_[0] * fft_buffer_[0];  // DC bin
        for (int k = 1; k < N / 2; k++) {
            float re = fft_buffer_[2 * k];
            float im = fft_buffer_[2 * k + 1];
            power_spectrum_[k] += re * re + im * im;
        }
    }

    // Average across segments (IIR smoothing from previous frame included)
    for (int k = 0; k < N / 2; k++) {
        power_spectrum_[k] /= num_segs;
    }

    // Reset accumulation for next cycle
    accumulate_count_ = 0;
    memset(accumulate_buffer_, 0, frame_size * sizeof(int16_t));

    return true;  // New spectrum data available
}

} // namespace spectrum
