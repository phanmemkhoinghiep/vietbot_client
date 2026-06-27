/**
 * @file spectrum_analyzer.h
 * @brief FFT-based audio spectrum analyzer using espressif/dl_fft.
 *
 * Accepts raw PCM int16 audio frames, accumulates them, runs a real-valued
 * FFT (dl_rfft_f32), and outputs a power-spectrum array suitable for
 * visualization.  Completely independent of any display or UI code.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#pragma once

#include "spectrum_config.h"

#include <cstdint>
#include <cstring>

// dl_fft C headers
extern "C" {
#include "dl_rfft.h"
}

namespace spectrum {

/**
 * @brief Pure FFT processing engine — no UI dependency.
 *
 * Typical usage:
 * @code
 *   SpectrumAnalyzer analyzer(config);
 *   analyzer.Initialize();
 *   // Inside audio callback:
 *   if (analyzer.ProcessPcmFrame(pcm, 1152)) {
 *       // New spectrum ready → pass to renderer
 *       const float* ps = analyzer.GetPowerSpectrum();
 *   }
 * @endcode
 */
class SpectrumAnalyzer {
public:
    explicit SpectrumAnalyzer(const SpectrumConfig& config);
    ~SpectrumAnalyzer();

    // Non-copyable, non-movable
    SpectrumAnalyzer(const SpectrumAnalyzer&) = delete;
    SpectrumAnalyzer& operator=(const SpectrumAnalyzer&) = delete;

    /**
     * @brief Allocate FFT buffers and initialize the dl_rfft_f32 handle.
     * @return true on success.
     */
    bool Initialize();

    /**
     * @brief Release all allocated memory and the FFT handle.
     */
    void Deinitialize();

    /**
     * @brief Feed one frame of PCM-16 audio data.
     *
     * Internally accumulates `accumulate_frames` frames, then runs the FFT.
     *
     * @param pcm_data   Pointer to int16 PCM samples.
     * @param sample_count  Number of samples in this frame.
     * @return true when a new power spectrum is available (call GetPowerSpectrum()).
     */
    bool ProcessPcmFrame(const int16_t* pcm_data, int sample_count);

    /**
     * @brief Access the latest power-spectrum data.
     * @return Read-only pointer to fft_size/2 floats (valid until next ProcessPcmFrame).
     */
    const float* GetPowerSpectrum() const { return power_spectrum_; }

    /**
     * @brief Number of power-spectrum bins (== fft_size / 2).
     */
    int GetSpectrumSize() const { return config_.fft_size / 2; }

    /**
     * @brief Reset internal accumulation state (e.g., when playback restarts).
     */
    void Reset();

private:
    SpectrumConfig config_;
    bool initialized_ = false;

    // dl_rfft handle
    dl_fft_f32_t* rfft_handle_ = nullptr;

    // Aligned FFT input/output buffer (size = fft_size floats, 16-byte aligned)
    float* fft_buffer_          = nullptr;

    // Hanning window coefficients (size = fft_size)
    float* hanning_window_      = nullptr;

    // Output power spectrum (size = fft_size / 2)
    float* power_spectrum_      = nullptr;

    // Accumulation buffer (size = audio_frame_size int16 samples)
    int16_t* accumulate_buffer_ = nullptr;

    // Number of frames accumulated so far in the current cycle
    int accumulate_count_       = 0;
};

} // namespace spectrum
