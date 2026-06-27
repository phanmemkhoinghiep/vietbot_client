/**
 * @file lyric_manager.cc
 * @brief Standalone lyrics module – download, parse (LRC format), sync & display.
 */

#include "lyric_manager.h"
#include "board.h"
#include "display/display.h"

#include <esp_log.h>
#include <cstring>
#include <sstream>
#include <algorithm>
#include <cctype>
#include <chrono>

static const char* TAG = "LyricManager";

/* ================================================================== */
/*  Constructor / Destructor                                          */
/* ================================================================== */

LyricManager::LyricManager()
    : current_index_(-1),
      is_running_(false),
      task_handle_(nullptr)
{
    lines_mutex_ = xSemaphoreCreateMutex();
}

LyricManager::~LyricManager()
{
    Stop();
    if (lines_mutex_) vSemaphoreDelete(lines_mutex_);
}

/* ================================================================== */
/*  Public API                                                        */
/* ================================================================== */

bool LyricManager::Start(const std::string& lyric_url)
{
    if (lyric_url.empty()) {
        ESP_LOGW(TAG, "Lyric URL is empty, nothing to load");
        return false;
    }

    /* Stop any previous task */
    Stop();

    lyric_url_ = lyric_url;
    is_running_ = true;
    current_index_ = -1;

    BaseType_t ret = xTaskCreatePinnedToCore(
        TaskEntry, "lyric_dl",
        LYRIC_TASK_STACK_SIZE, this,
        LYRIC_TASK_PRIORITY, &task_handle_,
        LYRIC_TASK_CORE);

    if (ret != pdPASS) {
        ESP_LOGE(TAG, "Failed to create lyric task");
        is_running_ = false;
        return false;
    }

    ESP_LOGI(TAG, "Lyric download started: %s", lyric_url.c_str());
    return true;
}

void LyricManager::Stop()
{
    is_running_ = false;

    /* Wait for task to finish */
    if (task_handle_) {
        for (int i = 0; i < 30 && task_handle_; ++i) {
            vTaskDelay(pdMS_TO_TICKS(100));
            if (task_handle_ == nullptr) break;
            if (eTaskGetState(task_handle_) == eDeleted) break;
        }
        task_handle_ = nullptr;
    }

    /* Clear lyrics */
    if (xSemaphoreTake(lines_mutex_, pdMS_TO_TICKS(500)) == pdTRUE) {
        lines_.clear();
        xSemaphoreGive(lines_mutex_);
    }
    current_index_ = -1;

    ESP_LOGI(TAG, "LyricManager stopped");
}

void LyricManager::UpdateDisplay(int64_t current_time_ms)
{
    if (xSemaphoreTake(lines_mutex_, pdMS_TO_TICKS(50)) != pdTRUE) return;

    if (lines_.empty()) {
        xSemaphoreGive(lines_mutex_);
        return;
    }

    /* Find the latest line whose timestamp <= current_time_ms */
    int new_idx = -1;
    int start = (current_index_.load() >= 0) ? current_index_.load() : 0;

    for (int i = start; i < (int)lines_.size(); ++i) {
        if (lines_[i].timestamp_ms <= current_time_ms) {
            new_idx = i;
        } else {
            break;
        }
    }

    /* Handle case where playback time went backwards (seek) */
    if (new_idx < 0 && current_time_ms >= 0) {
        for (int i = 0; i < (int)lines_.size(); ++i) {
            if (lines_[i].timestamp_ms <= current_time_ms) {
                new_idx = i;
            } else {
                break;
            }
        }
    }

    if (new_idx != current_index_.load()) {
        current_index_ = new_idx;

        std::string text;
        if (new_idx >= 0 && new_idx < (int)lines_.size()) {
            text = lines_[new_idx].text;
        }

        xSemaphoreGive(lines_mutex_);

        /* Display the lyric */
        auto display = Board::GetInstance().GetDisplay();
        if (display) {
            display->SetChatMessage("lyric", text.c_str());
            ESP_LOGD(TAG, "Lyric @%lldms: %s",
                     current_time_ms, text.empty() ? "(empty)" : text.c_str());
        }
    } else {
        xSemaphoreGive(lines_mutex_);
    }
}

bool LyricManager::HasLyrics() const
{
    return !lines_.empty();
}

size_t LyricManager::GetLineCount() const
{
    return lines_.size();
}

std::vector<LyricLine> LyricManager::GetAllLines() const
{
    std::vector<LyricLine> copy;
    if (xSemaphoreTake(lines_mutex_, pdMS_TO_TICKS(200)) == pdTRUE) {
        copy = lines_;
        xSemaphoreGive(lines_mutex_);
    }
    return copy;
}

/* ================================================================== */
/*  Task                                                              */
/* ================================================================== */

void LyricManager::TaskEntry(void* param)
{
    auto* self = static_cast<LyricManager*>(param);
    self->TaskFunc();
    self->task_handle_ = nullptr;
    vTaskDelete(nullptr);
}

void LyricManager::TaskFunc()
{
    std::string content;
    if (Download(lyric_url_, content)) {
        ParseLrc(content);
    } else {
        ESP_LOGE(TAG, "Failed to download lyrics");
    }
    is_running_ = false;
    ESP_LOGI(TAG, "Lyric task finished");
}

/* ================================================================== */
/*  Download with retry                                               */
/* ================================================================== */

bool LyricManager::Download(const std::string& url, std::string& out_content)
{
    ESP_LOGI(TAG, "Downloading lyrics: %s", url.c_str());

    for (int attempt = 0; attempt < LYRIC_MAX_RETRIES; ++attempt) {
        if (!is_running_) return false;

        if (attempt > 0) {
            ESP_LOGI(TAG, "Retry %d/%d", attempt + 1, LYRIC_MAX_RETRIES);
            vTaskDelay(pdMS_TO_TICKS(LYRIC_RETRY_DELAY_MS));
        }

        auto network = Board::GetInstance().GetNetwork();
        auto http = network->CreateHttp(0);
        if (!http) {
            ESP_LOGE(TAG, "Failed to create HTTP client");
            continue;
        }

        http->SetHeader("User-Agent", "ESP32-Music-Player/1.0");
        http->SetHeader("Accept", "text/plain");

        PrepareHttp(http.get());

        if (!http->Open("GET", url)) {
            ESP_LOGE(TAG, "HTTP open failed");
            continue;
        }

        int status = http->GetStatusCode();
        if (status < 200 || status >= 300) {
            ESP_LOGE(TAG, "HTTP status %d", status);
            http->Close();
            continue;
        }

        /* Read content */
        out_content.clear();
        char buf[1024];
        bool success = false;

        while (true) {
            int n = http->Read(buf, sizeof(buf) - 1);
            if (n > 0) {
                buf[n] = '\0';
                out_content += buf;
            } else if (n == 0) {
                success = true;
                break;
            } else {
                if (!out_content.empty()) {
                    success = true;
                }
                break;
            }
        }

        http->Close();

        if (success && !out_content.empty()) {
            ESP_LOGI(TAG, "Downloaded %zu bytes of lyrics", out_content.size());
            return true;
        }
    }

    ESP_LOGE(TAG, "Lyrics download failed after %d attempts", LYRIC_MAX_RETRIES);
    return false;
}

void LyricManager::PrepareHttp(void* http_ptr)
{
    /* Base implementation does nothing.
     * Subclasses or callers can set auth headers through other means. */
    (void)http_ptr;
}

/* ================================================================== */
/*  LRC parser                                                        */
/* ================================================================== */

bool LyricManager::ParseLrc(const std::string& content)
{
    ESP_LOGI(TAG, "Parsing LRC content (%zu bytes)", content.size());

    std::vector<LyricLine> parsed;
    std::istringstream stream(content);
    std::string line;

    while (std::getline(stream, line)) {
        /* Remove trailing CR */
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        if (line.empty()) continue;

        /* Expect [mm:ss.xx]text */
        if (line.size() <= 3 || line[0] != '[') continue;

        size_t close = line.find(']');
        if (close == std::string::npos) continue;

        std::string tag = line.substr(1, close - 1);
        std::string text = line.substr(close + 1);

        /* Distinguish metadata tags [ti:...] from timestamps [mm:ss.xx] */
        size_t colon = tag.find(':');
        if (colon == std::string::npos) continue;

        std::string left = tag.substr(0, colon);

        /* If left part is not all digits, it's a metadata tag – skip */
        bool is_time = true;
        for (char c : left) {
            if (!std::isdigit(static_cast<unsigned char>(c))) {
                is_time = false;
                break;
            }
        }
        if (!is_time) {
            ESP_LOGD(TAG, "Skipping metadata: [%s]", tag.c_str());
            continue;
        }

        /* Parse timestamp */
        try {
            int minutes = std::stoi(left);
            float seconds = std::stof(tag.substr(colon + 1));
            int ts_ms = minutes * 60 * 1000 + (int)(seconds * 1000);

            LyricLine ll;
            ll.timestamp_ms = ts_ms;
            ll.text = text;
            parsed.push_back(std::move(ll));
        } catch (...) {
            ESP_LOGW(TAG, "Failed to parse timestamp: [%s]", tag.c_str());
        }
    }

    /* Sort by timestamp */
    std::sort(parsed.begin(), parsed.end());

    /* Store results */
    if (xSemaphoreTake(lines_mutex_, pdMS_TO_TICKS(500)) == pdTRUE) {
        lines_ = std::move(parsed);
        xSemaphoreGive(lines_mutex_);
    }

    ESP_LOGI(TAG, "Parsed %zu lyric lines", lines_.size());
    return !lines_.empty();
}
