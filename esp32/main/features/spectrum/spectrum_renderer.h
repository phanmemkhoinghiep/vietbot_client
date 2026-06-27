/**
 * @file spectrum_renderer.h
 * @brief LVGL-based spectrum bar renderer.
 *
 * Creates an LVGL canvas at a configurable position/size and draws
 * frequency-domain bars with a falling-block animation and HSV color cycling.
 * Completely independent of any Display class — only depends on LVGL.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#pragma once

#include "spectrum_config.h"

#include <cstdint>
#include <vector>
#include <lvgl.h>

namespace spectrum {

/**
 * @brief Renders spectrum bars onto an LVGL canvas.
 *
 * The renderer owns the LVGL canvas object and its backing pixel buffer.
 * All public methods that touch LVGL objects **must** be called while the
 * LVGL port lock is held by the caller.
 */
class SpectrumRenderer {
public:
    explicit SpectrumRenderer(const SpectrumConfig& config);
    ~SpectrumRenderer();

    // Non-copyable, non-movable
    SpectrumRenderer(const SpectrumRenderer&) = delete;
    SpectrumRenderer& operator=(const SpectrumRenderer&) = delete;

    /**
     * @brief Create the LVGL canvas on the given parent.
     * @param parent  LVGL parent object (nullptr → lv_scr_act()).
     * @return true on success.
     * @note Must be called with LVGL lock held.
     */
    bool CreateCanvas(lv_obj_t* parent = nullptr);

    /**
     * @brief Destroy the LVGL canvas and free the pixel buffer.
     * @note Must be called with LVGL lock held.
     */
    void DestroyCanvas();

    /**
     * @brief Render one frame of spectrum bars from power-spectrum data.
     *
     * Clears the canvas to black, maps power_spectrum bins → bars,
     * converts to dB, applies falling-block animation, and draws.
     *
     * @param power_spectrum  Array of power values (fft_size/2 floats).
     * @param spectrum_size   Number of elements in power_spectrum.
     * @note Must be called with LVGL lock held.
     */
    void Render(const float* power_spectrum, int spectrum_size);

    /**
     * @brief Mark the spectrum area as dirty so LVGL redraws it.
     * @note Must be called with LVGL lock held.
     */
    void Invalidate();

    /** @return Raw LVGL canvas object (may be nullptr). */
    lv_obj_t* GetCanvas() const { return canvas_; }

    /** @return true if the canvas has been created. */
    bool IsCreated() const { return canvas_ != nullptr; }

    /** @return Canvas width in pixels. */
    int GetCanvasWidth() const { return config_.canvas_width; }

    /** @return Canvas height in pixels. */
    int GetCanvasHeight() const { return config_.canvas_height; }

private:
    // ---- Bar drawing helpers ----
    void DrawBar(int x, int y, int bar_width, int bar_height,
                 uint16_t color, int bar_index);
    void DrawBlock(int x, int y, int block_w, int block_h, uint16_t color);

    // ---- Color helpers ----
    uint16_t GetBarColor(int bar_index);
    uint16_t GetRandomColor();

    // ---- Configuration ----
    SpectrumConfig config_;

    // ---- Monochrome helpers ----
    void DrawBlockMono(int x, int y, int block_w, int block_h);
    int  GetMonoStride() const { return (config_.canvas_width + 7) / 8; }

    // ---- LVGL objects ----
    lv_obj_t* canvas_        = nullptr;
    void*     canvas_buffer_ = nullptr;   // uint16_t* (RGB565) or uint8_t* (I1)

    // ---- Falling-block animation state (per bar) ----
    std::vector<int>      current_heights_;
    std::vector<uint16_t> falling_colors_;
    std::vector<uint32_t> last_flash_time_;

    // ---- Continuous hue offset for rainbow color cycling ----
    float hue_offset_ = 0.0f;
};

} // namespace spectrum
