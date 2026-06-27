/**
 * @file qrcode_display.h
 * @brief Standalone QR code display feature module.
 *
 * Renders a QR code on its own LVGL canvas, fully isolated from
 * any specific Display subclass.  Auto-detects monochrome (OLED)
 * vs color (LCD) display type from the LVGL default display.
 *
 * Usage:
 *   auto& qr = qrcode::QRCodeDisplay::GetInstance();
 *   qr.Show("http://192.168.1.100/ota", "192.168.1.100/ota");
 *   // ... later ...
 *   qr.Clear();
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#pragma once

#include <functional>
#include <string>
#include <cstdint>

namespace qrcode {

class QRCodeDisplay {
public:
    /** Called with `true` when QR is displayed, `false` when cleared. */
    using OverlayCallback = std::function<void(bool active)>;

    /** Get the singleton instance. */
    static QRCodeDisplay& GetInstance();

    // Non-copyable
    QRCodeDisplay(const QRCodeDisplay&) = delete;
    QRCodeDisplay& operator=(const QRCodeDisplay&) = delete;

    /**
     * @brief Generate and display a QR code on screen.
     *
     * Generates the QR code from @p url, creates an LVGL canvas,
     * and draws the QR code centred on the active display.
     * Acquires LVGL lock internally.
     *
     * Supports both LCD (RGB565) and OLED (I1 monochrome) displays
     * by detecting the color format of the active LVGL display.
     *
     * @param url          The URL / text to encode in the QR code.
     * @param display_text Optional text rendered below the QR code.
     * @return true on success, false on failure.
     */
    bool Show(const std::string& url, const std::string& display_text = "");

    /**
     * @brief Remove the QR code canvas from screen.
     *
     * Acquires LVGL lock internally.  Safe to call when not displayed.
     */
    void Clear();

    /** @return true if QR code is currently displayed. */
    bool IsDisplayed() const { return displayed_; }

    /**
     * @brief Register a callback invoked on show (true) and clear (false).
     *
     * Use this to hide/restore the host display's normal UI while the QR
     * canvas is visible (mirrors MusicVisualizer::OverlayCallback pattern).
     */
    void SetOverlayCallback(OverlayCallback cb) { overlay_cb_ = std::move(cb); }

private:
    QRCodeDisplay() = default;
    ~QRCodeDisplay();

    /** Render QR data onto an RGB565 canvas (LCD). */
    void RenderColor(const uint8_t* qrcode, int screen_w, int screen_h,
                     const char* text);

    /** Render QR data onto a monochrome I1 canvas (OLED). */
    void RenderMono(const uint8_t* qrcode, int screen_w, int screen_h,
                    const char* text);

    /** Free canvas and buffer resources.  Caller must hold LVGL lock. */
    void DestroyCanvas();

    void*          canvas_buffer_ = nullptr;
    void*          canvas_obj_    = nullptr;   // lv_obj_t* (avoid lvgl.h in header)
    bool           displayed_     = false;
    OverlayCallback overlay_cb_;
};

}  // namespace qrcode
