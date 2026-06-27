/**
 * @file qrcode_display.cc
 * @brief Standalone QR code display feature module implementation.
 *
 * Auto-detects display type (LCD / OLED) and renders a QR code
 * on a full-screen LVGL canvas.  Manages its own LVGL lock.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */

#include "qrcode_display.h"

#include <lvgl.h>
#include <esp_log.h>
#include <esp_heap_caps.h>
#include <esp_lvgl_port.h>
#include <qrcode.h>
#include <cstring>

static const char* TAG = "QRCodeDisplay";

namespace qrcode {

// ===================================================================
// Singleton
// ===================================================================

QRCodeDisplay& QRCodeDisplay::GetInstance() {
    static QRCodeDisplay instance;
    return instance;
}

QRCodeDisplay::~QRCodeDisplay() {
    // Best-effort cleanup (caller should Clear() first).
    if (canvas_obj_) {
        lv_obj_del(static_cast<lv_obj_t*>(canvas_obj_));
        canvas_obj_ = nullptr;
    }
    if (canvas_buffer_) {
        heap_caps_free(canvas_buffer_);
        canvas_buffer_ = nullptr;
    }
}

// ===================================================================
// Show — generate QR code and render on display
// ===================================================================

bool QRCodeDisplay::Show(const std::string& url, const std::string& display_text) {
    if (url.empty()) return false;

    // Use a local flag to capture whether the callback succeeded
    bool render_ok = false;
    const std::string text_copy = display_text;

    // esp_qrcode_generate calls display_func synchronously
    esp_qrcode_config_t qrcode_cfg = {
        .max_qrcode_version = 10,
        .qrcode_ecc_level = ESP_QRCODE_ECC_MED,
    };

    // We need to pass the display_text into the callback.
    // Since esp_qrcode_config only has a function pointer (no user_data),
    // store the text temporarily in a static for the callback to pick up.
    static std::string s_display_text;
    s_display_text = text_copy;

    // Patch the callback to use the stored text
    qrcode_cfg.display_func = [](esp_qrcode_handle_t qrcode) {
        auto& self = QRCodeDisplay::GetInstance();

        if (!lvgl_port_lock(3000)) {
            ESP_LOGE(TAG, "Failed to acquire LVGL lock");
            return;
        }

        auto* disp = lv_display_get_default();
        if (!disp) {
            lvgl_port_unlock();
            ESP_LOGE(TAG, "No default LVGL display");
            return;
        }

        int screen_w = lv_display_get_horizontal_resolution(disp);
        int screen_h = lv_display_get_vertical_resolution(disp);
        auto cf = lv_display_get_color_format(disp);

        self.DestroyCanvas();

        const char* text = s_display_text.empty() ? nullptr : s_display_text.c_str();
        if (cf == LV_COLOR_FORMAT_I1) {
            self.RenderMono(qrcode, screen_w, screen_h, text);
        } else {
            self.RenderColor(qrcode, screen_w, screen_h, text);
        }

        lvgl_port_unlock();
    };

    esp_err_t err = esp_qrcode_generate(&qrcode_cfg, url.c_str());
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to generate QR code for: %s", url.c_str());
        return false;
    }

    render_ok = displayed_;
    if (render_ok) {
        ESP_LOGI(TAG, "QR code displayed for: %s", url.c_str());
        if (overlay_cb_) overlay_cb_(true);
    }
    return render_ok;
}

// ===================================================================
// Clear — remove canvas from screen
// ===================================================================

void QRCodeDisplay::Clear() {
    if (!displayed_) return;

    if (!lvgl_port_lock(3000)) {
        ESP_LOGE(TAG, "Failed to acquire LVGL lock for Clear");
        return;
    }

    DestroyCanvas();
    lvgl_port_unlock();

    if (overlay_cb_) overlay_cb_(false);
    ESP_LOGI(TAG, "QR code cleared");
}

// ===================================================================
// RenderColor — RGB565 canvas for LCD displays
// ===================================================================

void QRCodeDisplay::RenderColor(const uint8_t* qrcode,
                                 int screen_w, int screen_h,
                                 const char* text) {
    if (!qrcode) return;

    int canvas_w = screen_w;
    int canvas_h = screen_h;

    // Allocate RGB565 buffer in SPIRAM
    auto* buf = static_cast<uint16_t*>(
        heap_caps_malloc(canvas_w * canvas_h * sizeof(uint16_t),
                         MALLOC_CAP_8BIT | MALLOC_CAP_SPIRAM));
    if (!buf) {
        ESP_LOGE(TAG, "Failed to allocate RGB565 buffer (%dx%d)", canvas_w, canvas_h);
        return;
    }
    canvas_buffer_ = buf;

    // Create LVGL canvas
    auto* canvas = lv_canvas_create(lv_scr_act());
    lv_canvas_set_buffer(canvas, buf, canvas_w, canvas_h, LV_COLOR_FORMAT_RGB565);
    lv_obj_set_pos(canvas, 0, 0);
    lv_obj_set_size(canvas, canvas_w, canvas_h);
    lv_canvas_fill_bg(canvas, lv_color_make(0xFF, 0xFF, 0xFF), LV_OPA_COVER);
    lv_obj_move_foreground(canvas);
    canvas_obj_ = canvas;

    // Draw QR modules
    int qr_size    = esp_qrcode_get_size(qrcode);
    int max_side   = ((canvas_w < canvas_h) ? canvas_w : canvas_h) * 70 / 100;
    int pixel_size = max_side / qr_size;
    if (pixel_size < 2) pixel_size = 2;

    int qr_pos_x = (canvas_w - qr_size * pixel_size) / 2;
    int qr_pos_y = (canvas_h - qr_size * pixel_size) / 2;

    lv_layer_t layer;
    lv_canvas_init_layer(canvas, &layer);

    lv_draw_rect_dsc_t rect_dsc;
    lv_draw_rect_dsc_init(&rect_dsc);
    rect_dsc.bg_color = lv_color_black();
    rect_dsc.bg_opa   = LV_OPA_COVER;

    for (int y = 0; y < qr_size; y++) {
        for (int x = 0; x < qr_size; x++) {
            if (esp_qrcode_get_module(qrcode, x, y)) {
                lv_area_t a = {
                    .x1 = (int32_t)(x * pixel_size + qr_pos_x),
                    .y1 = (int32_t)(y * pixel_size + qr_pos_y),
                    .x2 = (int32_t)((x + 1) * pixel_size - 1 + qr_pos_x),
                    .y2 = (int32_t)((y + 1) * pixel_size - 1 + qr_pos_y),
                };
                lv_draw_rect(&layer, &rect_dsc, &a);
            }
        }
    }

    // Draw text below QR code
    if (text && text[0]) {
        lv_draw_label_dsc_t label_dsc;
        lv_draw_label_dsc_init(&label_dsc);
        label_dsc.color = lv_palette_main(LV_PALETTE_ORANGE);
        label_dsc.text  = text;

        int th = lv_font_get_line_height(label_dsc.font);
        int text_y = canvas_h - qr_pos_y + (qr_pos_y - th) / 2;
        lv_area_t text_area = {
            .x1 = (int32_t)qr_pos_x,
            .y1 = (int32_t)text_y,
            .x2 = (int32_t)(canvas_w - 1),
            .y2 = (int32_t)(canvas_h - 1),
        };
        lv_draw_label(&layer, &label_dsc, &text_area);
    }

    lv_canvas_finish_layer(canvas, &layer);
    displayed_ = true;

    ESP_LOGI(TAG, "QR rendered (color, %dx%d, pixel=%d)", qr_size, qr_size, pixel_size);
}

// ===================================================================
// RenderMono — I1 monochrome canvas for OLED displays
// ===================================================================

void QRCodeDisplay::RenderMono(const uint8_t* qrcode,
                                int screen_w, int screen_h,
                                const char* text) {
    if (!qrcode) return;

    int canvas_w = screen_w;
    int canvas_h = screen_h;

    // Allocate I1 (1-bit) buffer in DMA-capable internal memory.
    // Must use LV_DRAW_BUF_SIZE — it includes the 8-byte I1 palette
    // (2 entries × sizeof(lv_color32_t)) that LV_CANVAS_BUF_SIZE omits.
    size_t buf_size = LV_DRAW_BUF_SIZE(canvas_w, canvas_h, LV_COLOR_FORMAT_I1);
    auto* buf = static_cast<uint8_t*>(
        heap_caps_malloc(buf_size, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL));
    if (!buf) {
        ESP_LOGE(TAG, "Failed to allocate I1 buffer (%dx%d, %u bytes)",
                 canvas_w, canvas_h, (unsigned)buf_size);
        return;
    }
    memset(buf, 0, buf_size);
    canvas_buffer_ = buf;

    // Create LVGL canvas
    auto* canvas = lv_canvas_create(lv_scr_act());
    lv_canvas_set_buffer(canvas, buf, canvas_w, canvas_h, LV_COLOR_FORMAT_I1);
    // Palette must be set BEFORE fill_bg so the fill uses the correct mapping.
    // index 0 = white (background bits = 0), index 1 = black (foreground bits = 1)
    lv_canvas_set_palette(canvas, 0, lv_color_to_32(lv_color_white(), LV_OPA_COVER));
    lv_canvas_set_palette(canvas, 1, lv_color_to_32(lv_color_black(), LV_OPA_COVER));
    lv_obj_set_size(canvas, canvas_w, canvas_h);
    lv_obj_center(canvas);
    lv_canvas_fill_bg(canvas, lv_color_black(), LV_OPA_COVER);
    lv_obj_move_foreground(canvas);
    canvas_obj_ = canvas;

    // Calculate QR metrics
    int qr_size    = esp_qrcode_get_size(qrcode);
    int max_side   = ((canvas_w < canvas_h) ? canvas_w : canvas_h) - 10;
    int pixel_size = max_side / qr_size;
    if (pixel_size < 1) pixel_size = 1;

    int qr_display_size = qr_size * pixel_size;
    int qr_pos_x = (canvas_w - qr_display_size) / 2;
    int qr_pos_y = (canvas_h - qr_display_size) / 2 - 5;  // Shift up for text

    lv_layer_t layer;
    lv_canvas_init_layer(canvas, &layer);

    lv_draw_rect_dsc_t rect_dsc;
    lv_draw_rect_dsc_init(&rect_dsc);
    rect_dsc.bg_color   = lv_color_white();
    rect_dsc.bg_opa     = LV_OPA_COVER;
    rect_dsc.border_opa = LV_OPA_TRANSP;

    for (int y = 0; y < qr_size; y++) {
        for (int x = 0; x < qr_size; x++) {
            if (esp_qrcode_get_module(qrcode, x, y)) {
                lv_area_t a = {
                    .x1 = (int32_t)(x * pixel_size + qr_pos_x),
                    .y1 = (int32_t)(y * pixel_size + qr_pos_y),
                    .x2 = (int32_t)((x + 1) * pixel_size - 1 + qr_pos_x),
                    .y2 = (int32_t)((y + 1) * pixel_size - 1 + qr_pos_y),
                };
                lv_draw_rect(&layer, &rect_dsc, &a);
            }
        }
    }

#if (0)
    // Draw text below QR code
    if (text && text[0]) {
        lv_draw_label_dsc_t label_dsc;
        lv_draw_label_dsc_init(&label_dsc);
        label_dsc.color = lv_color_white();
        label_dsc.text  = text;

        int text_y = qr_pos_y + qr_display_size + 2;
        lv_area_t text_area = {
            .x1 = 0,
            .y1 = (int32_t)text_y,
            .x2 = (int32_t)(canvas_w - 1),
            .y2 = (int32_t)(canvas_h - 1),
        };
        lv_draw_label(&layer, &label_dsc, &text_area);
    }
#endif

    lv_canvas_finish_layer(canvas, &layer);
    lv_obj_remove_flag(canvas, LV_OBJ_FLAG_HIDDEN);
    displayed_ = true;

    ESP_LOGI(TAG, "QR rendered (mono, %dx%d, pixel=%d)", qr_size, qr_size, pixel_size);
}

// ===================================================================
// DestroyCanvas — free internal resources (LVGL lock must be held)
// ===================================================================

void QRCodeDisplay::DestroyCanvas() {
    if (canvas_obj_) {
        lv_obj_del(static_cast<lv_obj_t*>(canvas_obj_));
        canvas_obj_ = nullptr;
    }
    if (canvas_buffer_) {
        heap_caps_free(canvas_buffer_);
        canvas_buffer_ = nullptr;
    }
    displayed_ = false;
}

}  // namespace qrcode
