/**
 * @file spectrum_manager.h
 * @brief High-level façade for the audio spectrum visualization component.
 *
 * Coordinates SpectrumAnalyzer (FFT processing) and SpectrumRenderer
 * (LVGL bar drawing) behind a simple Start / Stop / Feed API.
 * Manages its own FreeRTOS task, LVGL locking, and memory lifecycle.
 *
 * Design goals:
 *   • Zero coupling to Display / LcdDisplay — only depends on LVGL + FreeRTOS.
 *   • Thread-safe audio feeding from any task.
 *   • Optional periodic callback for ancillary UI updates (e.g., music info).
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#pragma once

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "spectrum_config.h"
#include "spectrum_analyzer.h"
#include "spectrum_renderer.h"

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <lvgl.h>

namespace spectrum {

/**
 * @brief Façade that ties SpectrumAnalyzer + SpectrumRenderer together.
 *
 * Usage example:
 * @code
 *   spectrum::SpectrumConfig cfg;
 *   cfg.canvas_width  = display_width;
 *   cfg.canvas_height = display_height - status_bar_h;
 *   cfg.canvas_y      = status_bar_h;
 *
 *   auto mgr = std::make_unique<spectrum::SpectrumManager>(cfg);
 *   mgr->Start();                        // creates canvas + task
 *
 *   // In audio thread:
 *   mgr->FeedAudioData(pcm, bytes);
 *
 *   // When done:
 *   mgr->Stop();                         // destroys canvas + task
 * @endcode
 */
class SpectrumManager {
public:
    /** Signature for the optional periodic callback. */
    using PeriodicCallback = std::function<void()>;

    explicit SpectrumManager(const SpectrumConfig& config);
    ~SpectrumManager();

    // Non-copyable, non-movable
    SpectrumManager(const SpectrumManager&) = delete;
    SpectrumManager& operator=(const SpectrumManager&) = delete;

    // ---- Lifecycle ----

    /**
     * @brief Start the spectrum visualization.
     *
     * Creates the LVGL canvas (inside the LVGL lock) and spawns the
     * background FreeRTOS task that processes audio and refreshes bars.
     *
     * @param parent  LVGL parent for the canvas (nullptr → lv_scr_act()).
     * @return true on success.
     */
    bool Start(lv_obj_t* parent = nullptr);

    /**
     * @brief Stop the spectrum visualization.
     *
     * Signals the task to exit, waits for it, then destroys the canvas
     * and resets all internal state.
     */
    void Stop();

    /** @return true when the background task is running. */
    bool IsRunning() const { return task_handle_ != nullptr; }

    // ---- Audio buffer management ----

    /**
     * @brief Allocate (or return existing) PCM input buffer.
     * @param bytes  Buffer size in **bytes**.
     * @return Pointer to the buffer, or nullptr on failure.
     */
    int16_t* AllocateAudioBuffer(size_t bytes);

    /**
     * @brief Feed one chunk of PCM-16 audio data.
     *
     * Copies the data into the internal buffer so the background task
     * can process it.  Safe to call from any thread.
     *
     * @param data   PCM-16 samples.
     * @param bytes  Number of **bytes** (not samples).
     */
    void FeedAudioData(const int16_t* data, size_t bytes);

    /**
     * @brief Release the PCM input buffer.
     */
    void ReleaseAudioBuffer();

    // ---- Optional periodic callback ----

    /**
     * @brief Register a callback invoked periodically from the spectrum task.
     *
     * Useful for ancillary UI updates (e.g., music progress bar) without
     * creating a separate task.  The callback is invoked **with the LVGL
     * lock held**, so it may safely touch LVGL objects.
     *
     * @param cb           Callback function.
     * @param interval_ms  Invocation interval in milliseconds (default 1000).
     */
    void SetPeriodicCallback(PeriodicCallback cb, int interval_ms = 1000);

    // ---- Sub-component access ----

    /** @return Pointer to the renderer (for accessing canvas, etc.). */
    SpectrumRenderer* GetRenderer() { return renderer_.get(); }

    /** @return Pointer to the analyzer. */
    SpectrumAnalyzer* GetAnalyzer() { return analyzer_.get(); }

    /** @return The current configuration (read-only). */
    const SpectrumConfig& GetConfig() const { return config_; }

private:
    static void TaskWrapper(void* arg);
    void TaskLoop();

    SpectrumConfig config_;

    std::unique_ptr<SpectrumAnalyzer> analyzer_;
    std::unique_ptr<SpectrumRenderer> renderer_;

    // PCM input buffer (written by audio thread, read by spectrum task)
    int16_t* pcm_input_buffer_ = nullptr;
    size_t   pcm_buffer_size_  = 0;

    // FreeRTOS task
    std::atomic<bool> should_stop_{false};
    TaskHandle_t task_handle_ = nullptr;

    // Optional periodic callback
    PeriodicCallback periodic_callback_;
    int periodic_interval_ms_ = 1000;
};

} // namespace spectrum
