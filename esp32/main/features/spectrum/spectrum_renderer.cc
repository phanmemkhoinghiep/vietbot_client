/**
 * @file spectrum_renderer.cc
 * @brief Implementation of the LVGL spectrum bar renderer.
 *
 * Renders frequency-domain data as colored block-style bars on an LVGL
 * canvas, with a falling-block animation and HSV rainbow color cycling.
 * All pixel operations use direct RGB565 buffer writes for maximum
 * performance on ESP32-S3.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#include "spectrum_renderer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <esp_heap_caps.h>
#include <esp_log.h>
#include <esp_random.h>
#include <esp_timer.h>

static const char* TAG = "SpectrumRenderer";

// RGB565 color constants
static constexpr uint16_t COLOR_BLACK = 0x0000;
static constexpr uint16_t COLOR_BLUE  = 0x001F;

namespace spectrum {

// ---------------------------------------------------------------------------
// Construction / Destruction
// ---------------------------------------------------------------------------

SpectrumRenderer::SpectrumRenderer(const SpectrumConfig& config)
    : config_(config) {
    const int bars = config_.bar_count;
    current_heights_.assign(bars, 0);
    falling_colors_.assign(bars, COLOR_BLUE);
    last_flash_time_.assign(bars, 0);
}

SpectrumRenderer::~SpectrumRenderer() {
    DestroyCanvas();
}

// ---------------------------------------------------------------------------
// CreateCanvas — allocate pixel buffer + LVGL canvas object
// ---------------------------------------------------------------------------

bool SpectrumRenderer::CreateCanvas(lv_obj_t* parent) {
    if (canvas_) {
        ESP_LOGW(TAG, "Canvas already created");
        return true;
    }

    const int w = config_.canvas_width;
    const int h = config_.canvas_height;

    lv_color_format_t fmt;

    if (config_.monochrome) {
        // I1 (1-bit) — suitable for OLED SSD1306 / SH1107 displays.
        // Must use LV_DRAW_BUF_SIZE which correctly includes the 8-byte palette
        // (2 entries × sizeof(lv_color32_t)) prepended to the pixel data.
        // LV_CANVAS_BUF_SIZE omits the palette and would produce a too-small buffer.
        size_t buf_size = LV_DRAW_BUF_SIZE(w, h, LV_COLOR_FORMAT_I1);
        canvas_buffer_ = heap_caps_malloc(buf_size, MALLOC_CAP_8BIT | MALLOC_CAP_INTERNAL);
        if (!canvas_buffer_) {
            ESP_LOGE(TAG, "Failed to allocate mono canvas buffer (%dx%d)", w, h);
            return false;
        }
        memset(canvas_buffer_, 0, buf_size);
        fmt = LV_COLOR_FORMAT_I1;
    } else {
        // RGB565 — LCD displays
        canvas_buffer_ = heap_caps_malloc(w * h * sizeof(uint16_t),
                                          MALLOC_CAP_8BIT | MALLOC_CAP_SPIRAM);
        if (!canvas_buffer_) {
            ESP_LOGE(TAG, "Failed to allocate canvas buffer (%dx%d)", w, h);
            return false;
        }
        memset(canvas_buffer_, 0, w * h * sizeof(uint16_t));
        fmt = LV_COLOR_FORMAT_RGB565;
    }

    // Create LVGL canvas
    lv_obj_t* par = parent ? parent : lv_scr_act();
    canvas_ = lv_canvas_create(par);
    lv_canvas_set_buffer(canvas_, canvas_buffer_, w, h, fmt);
    lv_obj_set_pos(canvas_, config_.canvas_x, config_.canvas_y);
    lv_obj_set_size(canvas_, w, h);

    // FLOATING: bypass parent layout/padding/scroll so canvas is truly absolute-positioned
    lv_obj_add_flag(canvas_, LV_OBJ_FLAG_FLOATING);
    lv_obj_clear_flag(canvas_, LV_OBJ_FLAG_SCROLLABLE);

    // For I1 indexed format the palette sits at the start of the buffer.
    // After memset-to-zero both entries are transparent-black, so "white" bits
    // would render invisibly.  Set palette BEFORE fill_bg so the fill uses the
    // correct color mapping: index 1 = black, index 0 = white.
    if (config_.monochrome) {
        lv_canvas_set_palette(canvas_, 1, lv_color_to_32(lv_color_black(), LV_OPA_COVER));  // black
        lv_canvas_set_palette(canvas_, 0, lv_color_to_32(lv_color_white(), LV_OPA_COVER));  // white
    }

    lv_canvas_fill_bg(canvas_, lv_color_make(0, 0, 0), LV_OPA_COVER);
    lv_obj_move_foreground(canvas_);

    ESP_LOGI(TAG, "Canvas created (%dx%d at %d,%d, %s)", w, h,
             config_.canvas_x, config_.canvas_y,
             config_.monochrome ? "I1" : "RGB565");
    return true;
}

// ---------------------------------------------------------------------------
// DestroyCanvas — release LVGL object + pixel buffer
// ---------------------------------------------------------------------------

void SpectrumRenderer::DestroyCanvas() {
    if (canvas_) {
        lv_obj_del(canvas_);
        canvas_ = nullptr;
        ESP_LOGI(TAG, "Canvas deleted");
    }
    if (canvas_buffer_) {
        heap_caps_free(canvas_buffer_);
        canvas_buffer_ = nullptr;
    }

    // Reset animation state
    std::fill(current_heights_.begin(), current_heights_.end(), 0);
    std::fill(falling_colors_.begin(), falling_colors_.end(), COLOR_BLUE);
    std::fill(last_flash_time_.begin(), last_flash_time_.end(), 0);
    hue_offset_ = 0.0f;
}

// ---------------------------------------------------------------------------
// Render — convert power spectrum → visual bars
// ---------------------------------------------------------------------------

void SpectrumRenderer::Render(const float* power_spectrum, int spectrum_size) {
    if (!canvas_ || !canvas_buffer_ || !power_spectrum) return;

    const int bar_total     = config_.bar_count;
    const int bar_max_h     = config_.GetBarMaxHeight();
    const int w             = config_.canvas_width;
    const int h             = config_.canvas_height;
    const int bar_width     = w / bar_total;

    // ---- 1. Bin → bar magnitude (linear average within each bar group) ----
    static constexpr float MIN_DB = -25.0f;
    static constexpr float MAX_DB =   0.0f;

    float magnitude[bar_total];
    float max_magnitude = 0.0f;

    for (int bin = 0; bin < bar_total; bin++) {
        int start = bin * (spectrum_size / bar_total);
        int end   = (bin + 1) * (spectrum_size / bar_total);
        float sum = 0.0f;
        int   cnt = 0;
        for (int k = start; k < end && k < spectrum_size; k++) {
            sum += sqrtf(power_spectrum[k]);
            cnt++;
        }
        magnitude[bin] = (cnt > 0) ? (sum / cnt) : 0.0f;
        if (magnitude[bin] > max_magnitude) max_magnitude = magnitude[bin];
    }

    // Attenuate lowest frequency bars to reduce bass dominance
    if (bar_total > 5) {
        magnitude[1] *= 0.6f;
        magnitude[2] *= 0.7f;
        magnitude[3] *= 0.8f;
        magnitude[4] *= 0.8f;
        magnitude[5] *= 0.9f;
    }

    // ---- 2. Convert to dB relative to peak ----
    for (int bin = 1; bin < bar_total; bin++) {
        if (magnitude[bin] > 0.0f && max_magnitude > 0.0f) {
            magnitude[bin] = 20.0f * log10f(magnitude[bin] / max_magnitude + 1e-10f);
        } else {
            magnitude[bin] = MIN_DB;
        }
        magnitude[bin] = std::max(MIN_DB, std::min(MAX_DB, magnitude[bin]));
    }

    // ---- 3. Clear canvas ----
    if (config_.monochrome) {
        const int stride = GetMonoStride();
        // The I1 buffer layout: [8-byte palette][stride*h pixel data].
        // Skip the palette when clearing, otherwise the white/black color
        // entries are wiped every frame and bars become invisible.
        const size_t palette_bytes = LV_COLOR_INDEXED_PALETTE_SIZE(LV_COLOR_FORMAT_I1)
                                     * sizeof(lv_color32_t);  // 2 * 4 = 8
        memset(static_cast<uint8_t*>(canvas_buffer_) + palette_bytes, 0, stride * h);
    } else {
        std::fill_n(static_cast<uint16_t*>(canvas_buffer_), w * h, COLOR_BLACK);
    }

    // ---- 4. Draw bars (skip DC bar at index 0) ----
    for (int k = 1; k < bar_total; k++) {
        int x_pos = bar_width * (k - 1);
        float mag = (magnitude[k] - MIN_DB) / (MAX_DB - MIN_DB);
        mag = std::max(0.0f, std::min(1.0f, mag));

        int bar_height = static_cast<int>(mag * bar_max_h);
        uint16_t color = config_.monochrome ? 0xFFFF : GetBarColor(k);
        DrawBar(x_pos, h - 1, bar_width, bar_height, color, k - 1);
    }
}

// ---------------------------------------------------------------------------
// Invalidate — tell LVGL to redraw the spectrum area
// ---------------------------------------------------------------------------

void SpectrumRenderer::Invalidate() {
    if (!canvas_) return;

    if (config_.monochrome) {
        // OLED canvas is small — just invalidate the whole object
        lv_obj_invalidate(canvas_);
    } else {
        // Only refresh the spectrum area (bottom portion of the canvas)
        lv_area_t lcd_area;
        lcd_area.x1 = 0;
        lcd_area.y1 = config_.lcd_height - config_.GetBarMaxHeight();
        lcd_area.x2 = config_.canvas_width - 1;
        lcd_area.y2 = config_.lcd_height - 1;
        // Note: this api will allow LVGL update the canvas data to be visible on area of the screen (not area of the canvas).
        lv_obj_invalidate_area(canvas_, &lcd_area);
    }
}

// ---------------------------------------------------------------------------
// DrawBar — one spectrum bar made of small blocks + falling indicator
// ---------------------------------------------------------------------------

void SpectrumRenderer::DrawBar(int x, int y, int bar_width, int bar_height,
                               uint16_t color, int bar_index) {
    // Use smaller block sizes for monochrome OLED (denser bars)
    const int block_space  = config_.monochrome ? 1 : 2;
    const int block_x_size = bar_width - block_space;
    const int block_y_size = config_.monochrome ? 2 : 4;

    const int blocks_per_col = bar_height / (block_y_size + block_space);
    const int start_x = (block_x_size + block_space) / 2 + x;
    const int h = config_.canvas_height;

    // ---- Falling-block animation ----
    if (bar_index < static_cast<int>(current_heights_.size())) {
        if (current_heights_[bar_index] < bar_height) {
            current_heights_[bar_index] = bar_height;
            falling_colors_[bar_index]  = COLOR_BLUE;
        } else {
            constexpr int FALL_SPEED = 2;
            current_heights_[bar_index] -= FALL_SPEED;

            if (current_heights_[bar_index] > (block_y_size + block_space)) {
                uint32_t now = esp_timer_get_time() / 1000;
                if (now - last_flash_time_[bar_index] > 80) {
                    falling_colors_[bar_index]  = config_.monochrome ? 0xFFFF : GetRandomColor();
                    last_flash_time_[bar_index] = now;
                }
                if (config_.monochrome) {
                    DrawBlockMono(start_x, h - current_heights_[bar_index],
                                  block_x_size, block_y_size);
                } else {
                    DrawBlock(start_x, h - current_heights_[bar_index],
                              block_x_size, block_y_size, falling_colors_[bar_index]);
                }
            }
        }
    }

    // ---- Draw main bar blocks ----
    if (config_.monochrome) {
        DrawBlockMono(start_x, h - 1, block_x_size, block_y_size);
        for (int j = 1; j < blocks_per_col; j++) {
            int start_y = j * (block_y_size + block_space);
            DrawBlockMono(start_x, h - start_y, block_x_size, block_y_size);
        }
    } else {
        DrawBlock(start_x, h - 1, block_x_size, block_y_size, color);
        for (int j = 1; j < blocks_per_col; j++) {
            int start_y = j * (block_y_size + block_space);
            DrawBlock(start_x, h - start_y, block_x_size, block_y_size, color);
        }
    }
}

// ---------------------------------------------------------------------------
// DrawBlock — fill a small rectangle in the pixel buffer
// ---------------------------------------------------------------------------

void SpectrumRenderer::DrawBlock(int x, int y, int block_w, int block_h,
                                 uint16_t color) {
    const int w = config_.canvas_width;
    const int h = config_.canvas_height;
    uint16_t* buf = static_cast<uint16_t*>(canvas_buffer_);

    for (int row = y; row > y - block_h; row--) {
        if (row < 0 || row >= h) continue;
        if (x < 0 || x + block_w > w) continue;
        uint16_t* line = &buf[row * w + x];
        std::fill_n(line, block_w, color);
    }
}

// ---------------------------------------------------------------------------
// DrawBlockMono — fill a rectangle in I1 (1-bit) pixel buffer (white)
// ---------------------------------------------------------------------------

void SpectrumRenderer::DrawBlockMono(int x, int y, int block_w, int block_h) {
    const int w      = config_.canvas_width;
    const int h      = config_.canvas_height;
    const int stride = GetMonoStride();
    const size_t palette_bytes = LV_COLOR_INDEXED_PALETTE_SIZE(LV_COLOR_FORMAT_I1)
                                    * sizeof(lv_color32_t);  // 2 * 4 = 8
    uint8_t* buf     = static_cast<uint8_t*>(canvas_buffer_) + palette_bytes; // Skip palette

    for (int row = y; row > y - block_h; row--) {
        if (row < 0 || row >= h) continue;
        for (int col = x; col < x + block_w && col < w; col++) {
            if (col < 0) continue;
            // I1 format: MSB first, white = set bit
            buf[row * stride + col / 8] |= (0x80 >> (col % 8));
        }
    }
}

// ---------------------------------------------------------------------------
// GetBarColor — HSV rainbow cycling based on bar position + time offset
// ---------------------------------------------------------------------------

uint16_t SpectrumRenderer::GetBarColor(int bar_index) {
    hue_offset_ += 0.1f;
    if (hue_offset_ >= 360.0f) hue_offset_ -= 360.0f;

    float base_hue = static_cast<float>(bar_index) * (240.0f / 40.0f);
    float h = fmodf(base_hue + hue_offset_, 360.0f);

    // HSV → RGB565 (S=1, V=1)
    float c  = 1.0f;
    float hh = h / 60.0f;
    float x  = c * (1.0f - fabsf(fmodf(hh, 2.0f) - 1.0f));

    float r1 = 0.0f, g1 = 0.0f, b1 = 0.0f;
    int region = static_cast<int>(hh);
    switch (region) {
        case 0:  r1 = c;  g1 = x;  b1 = 0;  break;
        case 1:  r1 = x;  g1 = c;  b1 = 0;  break;
        case 2:  r1 = 0;  g1 = c;  b1 = x;  break;
        case 3:  r1 = 0;  g1 = x;  b1 = c;  break;
        case 4:  r1 = x;  g1 = 0;  b1 = c;  break;
        default: r1 = c;  g1 = 0;  b1 = x;  break;
    }

    uint8_t r = static_cast<uint8_t>(r1 * 31);
    uint8_t g = static_cast<uint8_t>(g1 * 63);
    uint8_t b = static_cast<uint8_t>(b1 * 31);
    return (r << 11) | (g << 5) | b;
}

// ---------------------------------------------------------------------------
// GetRandomColor — bright random RGB565 color
// ---------------------------------------------------------------------------

uint16_t SpectrumRenderer::GetRandomColor() {
    uint8_t r = (esp_random() % 16) + 16;
    uint8_t g = (esp_random() % 32) + 32;
    uint8_t b = (esp_random() % 16) + 16;

    switch (esp_random() % 3) {
        case 0: r = 31; break;
        case 1: g = 63; break;
        case 2: b = 31; break;
    }
    return (r << 11) | (g << 5) | b;
}

} // namespace spectrum
