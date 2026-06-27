/**
 * @file music_visualizer.cc
 * @brief Self-contained music spectrum visualizer + player info overlay.
 *
 * All playback info comes through the MusicInfoProvider callback.
 * The visualizer never directly includes or references any concrete
 * player class (Esp32Music, Esp32Radio, Esp32SdMusic).
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */

#include "music_visualizer.h"

#include <esp_log.h>
#include <esp_lvgl_port.h>
#include <font_awesome.h>
#include <cstring>
#include <cmath>
#include <ctime>
#include <algorithm>

static const char* TAG = "MusicVisualizer";

namespace music {

// ===================================================================
// Lifecycle
// ===================================================================

MusicVisualizer::~MusicVisualizer() {
    Stop();
}

bool MusicVisualizer::Start(const VisualizerConfig& cfg, const MusicInfo& initial_info) {
    if (spectrum_mgr_ && spectrum_mgr_->IsRunning()) {
        ESP_LOGW(TAG, "Already running");
        return true;
    }

    config_ = cfg;

    // Build spectrum config from caller-provided canvas rect
    spectrum::SpectrumConfig scfg;
    scfg.monochrome     = false;
    scfg.canvas_x       = cfg.canvas_x;
    scfg.canvas_y       = cfg.canvas_y;
    scfg.canvas_width   = cfg.canvas_width;
    scfg.canvas_height  = cfg.canvas_height;
    scfg.lcd_width      = cfg.lcd_width;
    scfg.lcd_height     = cfg.lcd_height;
    scfg.status_bar_h   = cfg.status_bar_h;
    scfg.bar_max_height = scfg.canvas_height / 2;
    scfg.fft_size       = cfg.fft_size;
    scfg.bar_count      = cfg.bar_count;

    spectrum_mgr_ = std::make_unique<spectrum::SpectrumManager>(scfg);

    // Allocate PCM input buffer BEFORE starting the task
    if (!spectrum_mgr_->AllocateAudioBuffer(cfg.audio_buf_size)) {
        ESP_LOGE(TAG, "Failed to allocate audio buffer (%u bytes)",
                 static_cast<unsigned>(cfg.audio_buf_size));
        spectrum_mgr_.reset();
        return false;
    }

    // Periodic callback for music UI updates (every 1 s, LVGL lock held by manager)
    spectrum_mgr_->SetPeriodicCallback([this]() {
        UpdateMusicUI(); 
    }, 1000);

    // Start spectrum (canvas + task)
    if (!spectrum_mgr_->Start()) {
        ESP_LOGE(TAG, "Failed to start spectrum manager");
        spectrum_mgr_.reset();
        return false;
    }

    // Use initial_info if provided, otherwise query provider
    MusicInfo info = initial_info;
    if (info.source == SourceType::NONE && info_provider_) {
        info = info_provider_();
    }

    // Build music overlay inside LVGL lock
    if (lvgl_port_lock(3000)) {
        if (overlay_cb_) overlay_cb_(true);   // hide main UI
        BuildMusicUI(info);
        lvgl_port_unlock();
    }

    ESP_LOGI(TAG, "Started (%dx%d at %d,%d, source=%d)",
             cfg.canvas_width, cfg.canvas_height, cfg.canvas_x, cfg.canvas_y,
             static_cast<int>(info.source));
    return true;
}

void MusicVisualizer::Stop() {
    if (spectrum_mgr_) {
        spectrum_mgr_->Stop();
    }

    if (lvgl_port_lock(3000)) {
        DestroyMusicUI();
        if (overlay_cb_) overlay_cb_(false);  // restore main UI
        lvgl_port_unlock();
    }

    spectrum_mgr_.reset();
    ESP_LOGI(TAG, "Stopped");
}

bool MusicVisualizer::IsRunning() const {
    return spectrum_mgr_ && spectrum_mgr_->IsRunning();
}

// ===================================================================
// Audio feed (thread-safe)
// ===================================================================

int16_t* MusicVisualizer::AllocateAudioBuffer(size_t sample_count) {
    if (!spectrum_mgr_) return nullptr;
    return spectrum_mgr_->AllocateAudioBuffer(sample_count);
}

void MusicVisualizer::FeedAudioData(const int16_t* data, size_t sample_count) {
    if (spectrum_mgr_) {
        spectrum_mgr_->FeedAudioData(data, sample_count);
    }
}

void MusicVisualizer::ReleaseAudioBuffer() {
    if (spectrum_mgr_) {
        spectrum_mgr_->ReleaseAudioBuffer();
    }
}

// ===================================================================
// Color / Icon helpers
// ===================================================================

lv_color_t MusicVisualizer::AccentColorForSource(SourceType src) {
    switch (src) {
        case SourceType::SD_CARD: return lv_color_hex(0x00FFC2);
        case SourceType::RADIO:   return lv_color_hex(0xFF9E40);
        case SourceType::ONLINE:  return lv_color_hex(0x00D9FF);
        default:                  return lv_color_hex(0xFFFFFF);
    }
}

const char* MusicVisualizer::IconForSource(SourceType src) {
    switch (src) {
        case SourceType::SD_CARD: return LV_SYMBOL_SD_CARD;
        case SourceType::RADIO:   return LV_SYMBOL_VOLUME_MAX;
        case SourceType::ONLINE:  return LV_SYMBOL_AUDIO;
        default:                  return LV_SYMBOL_AUDIO;
    }
}

// ===================================================================
// Music UI — Build
// ===================================================================

void MusicVisualizer::BuildMusicUI(const MusicInfo& info) {
    if (!spectrum_mgr_ || !spectrum_mgr_->GetRenderer()) return;

    lv_obj_t* canvas = spectrum_mgr_->GetRenderer()->GetCanvas();
    if (!canvas) return;

    const int w = spectrum_mgr_->GetConfig().canvas_width;
    const int h = spectrum_mgr_->GetConfig().canvas_height;

    if (info.source == SourceType::NONE && !info.is_playing) return;

    lv_color_t accent   = AccentColorForSource(info.source);
    const char* icon_sym = IconForSource(info.source);

    // Get fonts via callback
    const lv_font_t* text_font = &lv_font_montserrat_14;
    const lv_font_t* icon_font = &lv_font_montserrat_14;
    if (font_provider_) font_provider_(&text_font, &icon_font);

    const int pad_side = static_cast<int>(w * 0.04f);
    const int pad_top  = static_cast<int>(h * 0.05f);

    // Root container
    music_root_ = lv_obj_create(canvas);
    lv_obj_remove_style_all(music_root_);
    lv_obj_set_size(music_root_, w, h);
    lv_obj_set_style_bg_opa(music_root_, LV_OPA_TRANSP, 0);

    // Gradient overlay (top 35 %)
    lv_obj_t* overlay = lv_obj_create(music_root_);
    lv_obj_remove_style_all(overlay);
    lv_obj_set_size(overlay, w, static_cast<int>(h * 0.35f));
    lv_obj_set_style_bg_color(overlay, lv_color_black(), 0);
    lv_obj_set_style_bg_grad_color(overlay, lv_color_black(), 0);
    lv_obj_set_style_bg_grad_dir(overlay, LV_GRAD_DIR_VER, 0);
    lv_obj_set_style_bg_opa(overlay, 200, 0);
    lv_obj_set_style_bg_main_stop(overlay, 0, 0);
    lv_obj_set_style_bg_grad_stop(overlay, 255, 0);

    // Icon
    lv_obj_t* icon = lv_label_create(music_root_);
    lv_obj_set_style_text_font(icon, icon_font, 0);
    lv_obj_set_style_text_color(icon, accent, 0);
    lv_label_set_text(icon, icon_sym);
    lv_obj_align(icon, LV_ALIGN_TOP_LEFT, pad_side, pad_top);

    // Title + sub-info strings
    std::string title_str = info.title;
    std::string sub_str   = info.sub_info;
    show_progress_    = (info.duration_ms > 0);

    if (title_str.empty()) {
        switch (info.source) {
            case SourceType::SD_CARD: title_str = "SD Card Music"; break;
            case SourceType::ONLINE:  title_str = "Music Online";  break;
            case SourceType::RADIO:   title_str = "FM Radio";      break;
            default:                  title_str = "Playing...";    break;
        }
    }

    if (sub_str.empty() && info.bitrate_kbps > 0) {
        char buf[32];
        snprintf(buf, sizeof(buf), "%d kbps", info.bitrate_kbps);
        sub_str = buf;
    }

    int icon_w = 30;
    int text_w = w - (pad_side + icon_w + pad_side) - pad_side;

    // Title
    lv_obj_t* title = lv_label_create(music_root_);
    lv_obj_set_style_text_font(title, text_font, 0);
    lv_obj_set_style_text_color(title, lv_color_white(), 0);
    lv_label_set_long_mode(title, LV_LABEL_LONG_SCROLL_CIRCULAR);
    lv_obj_set_width(title, text_w);
    lv_label_set_text(title, title_str.c_str());
    lv_obj_align_to(title, icon, LV_ALIGN_OUT_RIGHT_TOP, pad_side, 0);
    music_title_label_ = title;

    // Sub-info
    lv_obj_t* sub = lv_label_create(music_root_);
    lv_obj_set_style_text_font(sub, text_font, 0);
    lv_obj_set_style_text_color(sub, lv_color_hex(0xAAAAAA), 0);
    lv_label_set_text(sub, sub_str.c_str());
    lv_label_set_long_mode(sub, LV_LABEL_LONG_SCROLL_CIRCULAR);
    lv_obj_set_width(sub, w - 40);
    lv_obj_align_to(sub, title, LV_ALIGN_OUT_BOTTOM_LEFT, 0, 4);
    music_subinfo_label_ = sub;

    // Progress bar (only when duration is known)
    if (show_progress_) {
        lv_obj_t* bar = lv_bar_create(music_root_);
        lv_obj_set_size(bar, w - (pad_side * 2), 4);
        lv_obj_align_to(bar, sub, LV_ALIGN_OUT_BOTTOM_LEFT, -icon_w - pad_side, 12);
        lv_obj_set_style_bg_color(bar, lv_color_hex(0x303030), LV_PART_MAIN);
        lv_obj_set_style_radius(bar, 2, LV_PART_MAIN);
        lv_obj_set_style_bg_color(bar, accent, LV_PART_INDICATOR);
        lv_obj_set_style_bg_opa(bar, LV_OPA_COVER, LV_PART_INDICATOR);
        lv_obj_set_style_radius(bar, 2, LV_PART_INDICATOR);
        lv_bar_set_range(bar, 0, info.duration_ms);
        lv_bar_set_value(bar, info.position_ms, LV_ANIM_OFF);
        music_bar_ = bar;

        lv_obj_t* t_curr = lv_label_create(music_root_);
        lv_obj_set_style_text_font(t_curr, text_font, 0);
        lv_obj_set_style_text_color(t_curr, accent, 0);
        lv_label_set_text(t_curr, MsToTimeString(info.position_ms).c_str());
        lv_obj_align_to(t_curr, bar, LV_ALIGN_OUT_BOTTOM_LEFT, 0, 6);
        music_time_left_ = t_curr;

        lv_obj_t* t_dur = lv_label_create(music_root_);
        lv_obj_set_style_text_font(t_dur, text_font, 0);
        lv_obj_set_style_text_color(t_dur, lv_color_hex(0xAAAAAA), 0);
        lv_label_set_text(t_dur, MsToTimeString(info.duration_ms).c_str());
        lv_obj_align_to(t_dur, bar, LV_ALIGN_OUT_BOTTOM_RIGHT, 0, 6);
        music_time_remain_ = t_dur;

        // Next track
        if (!info.next_track.empty()) {
            lv_obj_t* nl = lv_label_create(music_root_);
            lv_obj_set_style_text_font(nl, text_font, 0);
            lv_obj_set_style_text_color(nl, lv_color_hex(0x707070), 0);
            char nb[128];
            snprintf(nb, sizeof(nb), "Next: %s", info.next_track.c_str());
            lv_label_set_text(nl, nb);
            lv_label_set_long_mode(nl, LV_LABEL_LONG_SCROLL_CIRCULAR);
            lv_obj_set_width(nl, w - pad_side * 2);
            lv_obj_align_to(nl, t_curr, LV_ALIGN_OUT_BOTTOM_LEFT, 0, 4);
            music_next_line_ = nl;
        }
    } else {
        music_bar_         = nullptr;
        music_time_left_   = nullptr;
        music_time_remain_ = nullptr;
        music_next_line_   = nullptr;
        music_time_total_  = nullptr;
    }

    ESP_LOGI(TAG, "Music UI built (source=%d, title='%s')",
             static_cast<int>(info.source), title_str.c_str());
}

// ===================================================================
// Music UI — Destroy
// ===================================================================

void MusicVisualizer::DestroyMusicUI() {
    if (music_root_ && lv_obj_is_valid(music_root_)) {
        lv_obj_del(music_root_);
    }
    music_root_          = nullptr;
    music_title_label_   = nullptr;
    music_date_label_    = nullptr;
    music_bar_           = nullptr;
    music_time_left_     = nullptr;
    music_time_total_    = nullptr;
    music_time_remain_   = nullptr;
    music_subinfo_label_ = nullptr;
    music_next_line_     = nullptr;
}

// ===================================================================
// Music UI — Periodic update (called with LVGL lock held)
// ===================================================================

void MusicVisualizer::UpdateMusicUI() {
    if (!music_root_ || !lv_obj_is_valid(music_root_)) return;

    // Query current playback state from the provider
    if (!info_provider_) return;
    MusicInfo info = info_provider_();

    if (!show_progress_ && info.duration_ms > 0) {
        ESP_LOGI(TAG, "Switching to progress mode (duration_ms=%lld)", info.duration_ms);
        // Switch to progress mode: destroy and rebuild the UI with a progress bar
        if (lvgl_port_lock(3000)) {
            DestroyMusicUI();
            BuildMusicUI(info);
            lvgl_port_unlock();
        }
        show_progress_ = true;
        return;
    }

    // Update title
    if (music_title_label_ && lv_obj_is_valid(music_title_label_) && !info.title.empty()) {
        // ESP_LOGI(TAG, "Updating title: '%s'", info.title.c_str());
        lv_label_set_text(music_title_label_, info.title.c_str());
    }

    // Update sub-info
    if (music_subinfo_label_ && lv_obj_is_valid(music_subinfo_label_)) {
        std::string sub = info.sub_info;
        if (sub.empty() && info.bitrate_kbps > 0) {
            char buf[64];
            snprintf(buf, sizeof(buf), "%d kbps", info.bitrate_kbps);
            sub = buf;
        }
        if (!sub.empty()) {
            // ESP_LOGI(TAG, "Updating sub-info: '%s'", sub.c_str());
            lv_label_set_text(music_subinfo_label_, sub.c_str());
            lv_label_set_long_mode(music_subinfo_label_, LV_LABEL_LONG_SCROLL_CIRCULAR);
            int cw = spectrum_mgr_ ? spectrum_mgr_->GetConfig().canvas_width : config_.canvas_width;
            lv_obj_set_width(music_subinfo_label_, cw - 40);
        }
    }

    // Update progress bar + time labels
    if (music_bar_ && lv_obj_is_valid(music_bar_) && info.duration_ms > 0) {
        // ESP_LOGI(TAG, "Updating progress: pos=%lldms dur=%lldms", info.position_ms, info.duration_ms);
        lv_bar_set_range(music_bar_, 0, info.duration_ms);
        lv_bar_set_value(music_bar_, info.position_ms, LV_ANIM_OFF);

        if (music_time_left_ && lv_obj_is_valid(music_time_left_)) {
            lv_label_set_text(music_time_left_, MsToTimeString(info.position_ms).c_str());
        }
        if (music_time_remain_ && lv_obj_is_valid(music_time_remain_)) {
            int64_t rem = info.duration_ms - info.position_ms;
            if (rem < 0) rem = 0;
            lv_label_set_text(music_time_remain_, MsToTimeString(rem).c_str());
        }
    }

    // Update next track
    if (music_next_line_ && lv_obj_is_valid(music_next_line_) && !info.next_track.empty()) {
        char nb[128];
        // ESP_LOGI(TAG, "Updating next track: '%s'", info.next_track.c_str());
        snprintf(nb, sizeof(nb), "Next: %s", info.next_track.c_str());
        lv_label_set_text(music_next_line_, nb);
    }
}

// ===================================================================
// Utility
// ===================================================================

std::string MusicVisualizer::MsToTimeString(int64_t ms) {
    if (ms < 0) ms = 0;
    int ts  = static_cast<int>(ms / 1000);
    int s   = ts % 60;
    int m   = (ts / 60) % 60;
    int h   = ts / 3600;
    char buf[32];
    if (h > 0)
        snprintf(buf, sizeof(buf), "%02d:%02d:%02d", h, m, s);
    else
        snprintf(buf, sizeof(buf), "%02d:%02d", m, s);
    return buf;
}

}  // namespace music
