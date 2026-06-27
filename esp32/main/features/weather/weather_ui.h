#ifndef WEATHER_UI_H
#define WEATHER_UI_H

#include "weather_model.h"
#include "lvgl_display.h"
#if HAVE_LVGL
#include <lvgl.h>
#endif
#include <string>

class WeatherUI {
public:
    WeatherUI();
    ~WeatherUI();

    void SetupIdleUI(lv_obj_t* parent, int screen_width, int screen_height);
    void ShowIdleCard(const IdleCardInfo& info);
    void HideIdleCard();

    static const char* GetWeatherIcon(const std::string& code);

    bool IsInitialized() const { return container_ != nullptr; }

private:
    lv_obj_t* container_;
    int screen_width_;
    int screen_height_;

    // --- HEADER (WIFI & BATTERY) ---
    lv_obj_t* label_wifi_icon_;
    lv_obj_t* label_wifi_text_;
    lv_obj_t* label_bat_icon_;
    lv_obj_t* label_bat_text_;

    // --- MAIN UI COMPONENTS ---
    lv_obj_t* label_full_date_;
    lv_obj_t* label_location_; // Dòng hiển thị vị trí
    lv_obj_t* group_weather_; 
    lv_obj_t* label_main_temp_;
    lv_obj_t* label_main_icon_;
};

#endif // WEATHER_UI_H