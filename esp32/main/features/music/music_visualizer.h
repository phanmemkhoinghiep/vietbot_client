/**
 * @file music_visualizer.h
 * @brief Self-contained music spectrum visualizer + player info overlay.
 *
 * Owns:
 *   • SpectrumManager  (FFT + LVGL canvas bars)
 *   • Music UI overlay (title, sub-info, progress bar, next track)
 *
 * Isolation contract:
 *   • Does NOT depend on Display, LcdDisplay, or any player class.
 *   • Uses a `MusicInfoProvider` callback to query current playback state
 *     from the host — never directly includes any concrete player.
 *   • Uses `OverlayCallback` to tell the host whether the media overlay
 *     should be active or not.
 *
 * Thread safety:
 *   • Audio feed methods are thread-safe (called from streaming task).
 *   • All LVGL calls acquire `lvgl_port_lock` internally.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#pragma once

#include "spectrum_manager.h"

#include <lvgl.h>
#include <functional>
#include <string>
#include <memory>
#include <vector>

namespace music {

/**
 * @brief Source type for the music currently playing.
 */
enum class SourceType {
    NONE = 0,
    SD_CARD,
    ONLINE,
    RADIO,
};

/**
 * @brief Immutable snapshot of the current playback state.
 *
 * Provided by the host via `MusicInfoProvider` callback.
 * The visualizer never directly accesses any player object —
 * it only reads this data-only struct.
 */
struct MusicInfo {
    SourceType  source       = SourceType::NONE;   ///< Active source
    bool        is_playing   = false;              ///< True if audio is playing

    std::string title;         ///< Track / station / stream name
    std::string sub_info;      ///< Artist, bitrate, etc.
    std::string next_track;    ///< Next track name (empty if N/A)

    int64_t     position_ms  = 0;   ///< Current position (ms)
    int64_t     duration_ms  = 0;   ///< Total duration (ms, 0 for live)
    int         bitrate_kbps = 0;   ///< Bitrate in kbps
};

/**
 * @brief Configuration for the visualizer canvas and FFT parameters.
 *
 * The caller specifies the exact canvas position and dimensions.
 * This allows flexible layout control — the canvas can be smaller than
 * the screen and placed at any (x, y) position by the caller.
 */
struct VisualizerConfig {
    int canvas_x          = 0;       ///< Canvas X position on screen (pixels)
    int canvas_y          = 0;       ///< Canvas Y position on screen (pixels)
    int canvas_width      = 320;     ///< Canvas width  (pixels)
    int canvas_height     = 240;     ///< Canvas height (pixels)
    int lcd_width         = 320;     ///< LCD width (pixels, for UI layout fallback)
    int lcd_height        = 240;     ///< LCD height (pixels, for UI layout fallback)
    int status_bar_h      = 24;      ///< Status bar height (pixels, for UI layout fallback)
    int fft_size          = 512;
    int bar_count         = 40;
    size_t audio_buf_size = 4608;    ///< PCM buffer size in bytes (must match pipeline)
};

/**
 * @brief Self-contained music spectrum visualizer.
 *
 * Usage:
 * @code
 *   music::MusicVisualizer viz;
 *   viz.SetOverlayCallback([&](bool a) { display->SetMediaOverlayActive(a); });
 *   viz.SetInfoProvider([&]() { return BuildMusicInfo(); });
 *   viz.SetFontProvider([&](auto** t, auto** i) { ... });
 *   viz.Start(config);
 *   // feed audio...
 *   viz.Stop();
 * @endcode
 */
class MusicVisualizer {
public:
    /// Callback to show/hide the media overlay on the host display.
    using OverlayCallback    = std::function<void(bool active)>;
    /// Callback to retrieve a snapshot of current playback state.
    using MusicInfoProvider  = std::function<MusicInfo()>;
    /// Callback to retrieve fonts from the display theme.
    using FontProvider       = std::function<void(const lv_font_t** text_font,
                                                  const lv_font_t** icon_font)>;

    MusicVisualizer() = default;
    ~MusicVisualizer();

    // Non-copyable
    MusicVisualizer(const MusicVisualizer&) = delete;
    MusicVisualizer& operator=(const MusicVisualizer&) = delete;

    // ─── Callback registration (call before Start) ────────────────

    /** Register callback to show/hide the media overlay. */
    void SetOverlayCallback(OverlayCallback cb) { overlay_cb_ = std::move(cb); }

    /** Register callback to query current playback info. */
    void SetInfoProvider(MusicInfoProvider cb) { info_provider_ = std::move(cb); }

    /** Register callback to get theme fonts. */
    void SetFontProvider(FontProvider cb) { font_provider_ = std::move(cb); }

    // ─── Lifecycle ────────────────────────────────────────────────

    /**
     * @brief Start spectrum rendering + music UI.
     *
     * Creates the spectrum canvas, builds the music info overlay,
     * and spawns the background FFT task.
     *
     * @param cfg         Canvas & FFT configuration.
     * @param initial_info  Optional initial MusicInfo snapshot for first frame.
     */
    bool Start(const VisualizerConfig& cfg, const MusicInfo& initial_info = {});

    /** Stop spectrum + tear down music UI. */
    void Stop();

    /** @return true if currently running. */
    bool IsRunning() const;

    // ─── Audio data feed (thread-safe) ────────────────────────────

    /** Allocate a PCM buffer for the audio pipeline. */
    int16_t* AllocateAudioBuffer(size_t sample_count);

    /** Feed PCM data to the FFT analyzer. */
    void FeedAudioData(const int16_t* data, size_t sample_count);

    /** Release the PCM buffer. */
    void ReleaseAudioBuffer();

private:
    // ─── Music UI helpers ─────────────────────────────────────────
    void BuildMusicUI(const MusicInfo& info);
    void DestroyMusicUI();
    void UpdateMusicUI();

    static std::string MsToTimeString(int64_t ms);

    static lv_color_t  AccentColorForSource(SourceType src);
    static const char*  IconForSource(SourceType src);

    // ─── Callbacks ────────────────────────────────────────────────
    OverlayCallback   overlay_cb_;
    MusicInfoProvider info_provider_;
    FontProvider      font_provider_;

    // ─── Spectrum engine ──────────────────────────────────────────
    std::unique_ptr<spectrum::SpectrumManager> spectrum_mgr_;

    // ─── Config ───────────────────────────────────────────────────
    VisualizerConfig config_{};

    // ─── LVGL music UI widgets ────────────────────────────────────
    lv_obj_t* music_root_          = nullptr;
    lv_obj_t* music_title_label_   = nullptr;
    lv_obj_t* music_date_label_    = nullptr;
    lv_obj_t* music_bar_           = nullptr;
    lv_obj_t* music_time_left_     = nullptr;
    lv_obj_t* music_time_total_    = nullptr;
    lv_obj_t* music_subinfo_label_ = nullptr;
    lv_obj_t* music_time_remain_   = nullptr;
    lv_obj_t* music_next_line_     = nullptr;

    // ─── State ─────────────────────────────────────────────────────
    bool show_progress_ = false;
};

}  // namespace music
