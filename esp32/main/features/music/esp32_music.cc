/**
 * @file esp32_music.cc
 * @brief Online music player implementation.
 *
 * Inherits AudioStreamPlayer for HTTP streaming / decoding / FFT.
 * Adds: song search API, ESP32 authentication, lyrics via LyricManager.
 */

#include "esp32_music.h"
#include "board.h"
#include "system_info.h"
#include "audio/audio_codec.h"
#include "application.h"
#include "protocols/protocol.h"
#include "settings.h"

#include <esp_log.h>
#include <esp_heap_caps.h>
#include <esp_timer.h>
#include <mbedtls/sha256.h>
#include <cJSON.h>
#include <cstring>
#include <algorithm>
#include <sstream>
#include <cctype>

static const char* TAG = "Esp32Music";

/* ================================================================== */
/*  URL encoding                                                      */
/* ================================================================== */

std::string Esp32Music::UrlEncode(const std::string& str)
{
    std::string encoded;
    char hex[4];

    for (size_t i = 0; i < str.length(); ++i) {
        unsigned char c = str[i];
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
            encoded += c;
        } else if (c == ' ') {
            encoded += '+';
        } else {
            snprintf(hex, sizeof(hex), "%%%02X", c);
            encoded += hex;
        }
    }
    return encoded;
}

std::string Esp32Music::BuildUrlWithParams(const std::string& base,
                                           const std::string& path,
                                           const std::string& query)
{
    std::string result = base + path + "?";
    size_t pos = 0;
    size_t amp = 0;

    while ((amp = query.find("&", pos)) != std::string::npos) {
        std::string param = query.substr(pos, amp - pos);
        size_t eq = param.find("=");
        if (eq != std::string::npos) {
            result += param.substr(0, eq) + "=" + UrlEncode(param.substr(eq + 1)) + "&";
        } else {
            result += param + "&";
        }
        pos = amp + 1;
    }

    std::string last = query.substr(pos);
    size_t eq = last.find("=");
    if (eq != std::string::npos) {
        result += last.substr(0, eq) + "=" + UrlEncode(last.substr(eq + 1));
    } else {
        result += last;
    }
    return result;
}

/* ================================================================== */
/*  ESP32 Authentication helpers                                      */
/* ================================================================== */

std::string Esp32Music::GetDeviceMac()
{
    return SystemInfo::GetMacAddress();
}

std::string Esp32Music::GetDeviceChipId()
{
    std::string mac = SystemInfo::GetMacAddress();
    mac.erase(std::remove(mac.begin(), mac.end(), ':'), mac.end());
    return mac;
}

std::string Esp32Music::GenerateDynamicKey(int64_t timestamp)
{
    const std::string secret_key = "your-esp32-secret-key-2024";
    std::string mac     = GetDeviceMac();
    std::string chip_id = GetDeviceChipId();

    std::string data = mac + ":" + chip_id + ":" + std::to_string(timestamp) + ":" + secret_key;

    unsigned char hash[32];
    mbedtls_sha256((unsigned char*)data.c_str(), data.length(), hash, 0);

    std::string key;
    for (int i = 0; i < 16; ++i) {
        char hex[3];
        snprintf(hex, sizeof(hex), "%02X", hash[i]);
        key += hex;
    }
    return key;
}

void Esp32Music::AddAuthHeaders(void* http_ptr)
{
    auto* http = static_cast<Http*>(http_ptr);
    if (!http) return;

    int64_t timestamp   = esp_timer_get_time() / 1000000;
    std::string key     = GenerateDynamicKey(timestamp);
    std::string mac     = GetDeviceMac();
    std::string chip_id = GetDeviceChipId();

    http->SetHeader("X-MAC-Address", mac);
    http->SetHeader("X-Chip-ID", chip_id);
    http->SetHeader("X-Timestamp", std::to_string(timestamp));
    http->SetHeader("X-Dynamic-Key", key);

    ESP_LOGI(TAG, "Auth headers: MAC=%s ChipID=%s", mac.c_str(), chip_id.c_str());
}

/* ================================================================== */
/*  Constructor / Destructor                                          */
/* ================================================================== */

Esp32Music::Esp32Music()
    : AudioStreamPlayer(),
      song_name_displayed_(false),
      full_info_displayed_(false)
{
}

Esp32Music::~Esp32Music()
{
    ESP_LOGI(TAG, "Destroying Esp32Music");
    lyric_mgr_.Stop();
    StopStreaming();
    ESP_LOGI(TAG, "Esp32Music destroyed");
}

void Esp32Music::Initialize(AudioCodec* codec)
{
    if (codec) {
        SetAudioCodec(codec);
    }
    ESP_LOGI(TAG, "Music player initialised (codec=%s)", codec ? "direct" : "app-pipeline");
}

/* ================================================================== */
/*  Music interface: Download (search API)                            */
/* ================================================================== */

bool Esp32Music::Download(const std::string& song_name, const std::string& artist_name)
{
    ESP_LOGI(TAG, "Searching for: %s (artist: %s)", song_name.c_str(), artist_name.c_str());

    last_downloaded_data_.clear();
    title_name_.clear();
    artist_name_.clear();
    current_song_name_ = song_name;

    std::string base_url = GetCheckMusicServerUrl();
    std::string full_url = base_url + "/stream_pcm?song=" +
                           UrlEncode(song_name) + "&artist=" + UrlEncode(artist_name);

    ESP_LOGI(TAG, "API URL: %s", full_url.c_str());

    auto network = Board::GetInstance().GetNetwork();
    auto http = network->CreateHttp(0);

    http->SetHeader("User-Agent", "ESP32-Music-Player/1.0");
    http->SetHeader("Accept", "application/json");
    AddAuthHeaders(http.get());

    if (!http->Open("GET", full_url)) {
        ESP_LOGE(TAG, "Failed to connect to music API");
        return false;
    }

    int status = http->GetStatusCode();
    if (status != 200) {
        ESP_LOGE(TAG, "API returned HTTP %d", status);
        http->Close();
        return false;
    }

    last_downloaded_data_ = http->ReadAll();
    http->Close();

    ESP_LOGI(TAG, "API response: %zu bytes", last_downloaded_data_.size());

    if (last_downloaded_data_.empty()) {
        ESP_LOGE(TAG, "Empty API response");
        return false;
    }

    /* Parse JSON */
    cJSON* root = cJSON_Parse(last_downloaded_data_.c_str());
    if (!root) {
        ESP_LOGE(TAG, "JSON parse failed");
        return false;
    }

    cJSON* j_artist   = cJSON_GetObjectItem(root, "artist");
    cJSON* j_title    = cJSON_GetObjectItem(root, "title");
    cJSON* j_audio    = cJSON_GetObjectItem(root, "audio_url");
    cJSON* j_lyric    = cJSON_GetObjectItem(root, "lyric_url");
    cJSON* j_duration = cJSON_GetObjectItem(root, "duration");

    if (cJSON_IsString(j_artist)) artist_name_ = j_artist->valuestring;
    if (cJSON_IsString(j_title))  title_name_  = j_title->valuestring;
    if (cJSON_IsNumber(j_duration)) {
        duration_ms_ = static_cast<int64_t>(j_duration->valuedouble * 1000);
        ESP_LOGI(TAG, "Duration from API: %lld ms", duration_ms_);
    } else {
        duration_ms_ = 0;
    }
    bitrate_kbps_ = 0;  // reset — will be set by OnStreamInfoReady

    ESP_LOGI(TAG, "Song: %s | Artist: %s", title_name_.c_str(), artist_name_.c_str());
    ESP_LOGI(TAG, "Audio URL: %s", cJSON_IsString(j_audio) ? j_audio->valuestring : "N/A");
    // ESP_LOGI(TAG, "Lyric URL: %s", cJSON_IsString(j_lyric) ? j_lyric->valuestring : "N/A");

    /* Validate audio URL */
    if (!cJSON_IsString(j_audio) || !j_audio->valuestring || strlen(j_audio->valuestring) == 0) {
        ESP_LOGE(TAG, "No audio URL in API response for '%s'", song_name.c_str());
        cJSON_Delete(root);
        return false;
    }

    /* Build audio URL */
    std::string audio_path = j_audio->valuestring;
    if (audio_path.find("?") != std::string::npos && audio_path.find("/mp3/") == std::string::npos) {
        size_t qpos = audio_path.find("?");
        current_music_url_ = BuildUrlWithParams(base_url,
                                                audio_path.substr(0, qpos),
                                                audio_path.substr(qpos + 1));
    } else {
        // audio_path": "/mp3/proxy/stream?url=https%3A%2F%2Fa128
        // fallback: strip /mp3/ prefix if present to support older API versions
        // ESP_LOGI(TAG, "Audio path before processing: '%s'", audio_path.c_str());
        if (audio_path.find("/mp3/") != std::string::npos) {
            ESP_LOGI(TAG, "Stripping '/mp3/' prefix from audio path for compatibility");
            audio_path = audio_path.substr(audio_path.find("/mp3/") + 4);
        }
        // ESP_LOGI(TAG, "Final audio path: '%s'", audio_path.c_str());
        current_music_url_ = base_url + audio_path;
    }

    song_name_displayed_ = false;
    full_info_displayed_ = false;

    /* Start streaming */
    StartStreaming(current_music_url_);

    /* Build lyric URL & start lyrics if in lyric display mode */
    if (cJSON_IsString(j_lyric) && j_lyric->valuestring && strlen(j_lyric->valuestring) > 0) {
        std::string lyric_path = j_lyric->valuestring;
        if (lyric_path.find("?") != std::string::npos) {
            size_t qpos = lyric_path.find("?");
            current_lyric_url_ = BuildUrlWithParams(base_url,
                                                    lyric_path.substr(0, qpos),
                                                    lyric_path.substr(qpos + 1));
        } else {
            current_lyric_url_ = base_url + lyric_path;
        }

        if (GetDisplayMode() == DISPLAY_MODE_LYRICS) {
            ESP_LOGI(TAG, "Loading lyrics (lyrics display mode)");
            lyric_mgr_.Start(current_lyric_url_);
        } else {
            ESP_LOGI(TAG, "Lyric URL found but spectrum mode active, skipping");
        }
    } else {
        ESP_LOGW(TAG, "No lyric URL for this song");
    }

    cJSON_Delete(root);
    return true;
}

std::string Esp32Music::GetDownloadResult()
{
    return last_downloaded_data_;
}

/* ================================================================== */
/*  Music interface: Streaming                                        */
/* ================================================================== */

bool Esp32Music::StartStreaming(const std::string& music_url)
{
    ESP_LOGI(TAG, "StartStreaming: %s", music_url.c_str());
    return StartStream(music_url, AudioDecoderType::MP3);
}

bool Esp32Music::StopStreaming()
{
    if (!IsPlaying() && !IsDownloading()) {
        return true;
    }
    
    ESP_LOGI(TAG, "StopStreaming");
    lyric_mgr_.Stop();
    bool ok = StopStream();

    title_name_.clear();
    artist_name_.clear();
    current_song_name_.clear();
    return ok;
}

/* ================================================================== */
/*  AudioStreamPlayer hooks                                           */
/* ================================================================== */

void Esp32Music::OnPrepareHttp(void* http_ptr)
{
    AddAuthHeaders(http_ptr);

    auto* http = static_cast<Http*>(http_ptr);
    if (http) {
        http->SetHeader("Connection", "keep-alive");
        http->SetHeader("Cache-Control", "no-cache");
    }
}

void Esp32Music::OnStreamInfoReady(int sample_rate, int bits_per_sample, int channels,
                                  int bitrate, int frame_size)
{
    if (full_info_displayed_) return;

    bitrate_kbps_ = bitrate;

    /* Estimate duration from Content-Length + bitrate if not set by API */
    if (duration_ms_ <= 0 && bitrate_kbps_ > 0) {
        size_t content_len = GetContentLength();
        if (content_len > 0) {
            duration_ms_ = static_cast<int64_t>(content_len) * 8 / bitrate_kbps_;
            ESP_LOGI(TAG, "Estimated duration from Content-Length: %lld ms "
                     "(content=%zu bytes, bitrate=%d kbps)",
                     duration_ms_, content_len, bitrate_kbps_);
        }
    }

    ESP_LOGI(TAG, "Stream info: %d Hz, %d bit, %d ch, %d kbps, %d frame size", sample_rate, bits_per_sample, channels, bitrate, frame_size);
    full_info_displayed_ = true;
}

void Esp32Music::OnPcmFrame(int64_t play_time_ms, int sample_rate, int channels)
{
    /* Update lyric display */
    lyric_mgr_.UpdateDisplay(play_time_ms + LYRIC_LATENCY_OFFSET_MS);
}

bool Esp32Music::OnPlaybackFinishedAndContinue()
{
    lyric_mgr_.Stop();
    ESP_LOGI(TAG, "Playback finished callback");
    return false;
}

void Esp32Music::OnDisplayReady()
{
    /* Display is now handled externally via Application callbacks */
    ESP_LOGD(TAG, "Display ready callback");
}

/* ================================================================== */
/*  Settings                                                          */
/* ================================================================== */

std::string Esp32Music::GetCheckMusicServerUrl()
{
    Settings settings("wifi", false);
    std::string url = settings.GetString("music_url");
    if (url.empty()) {
        url = DEFAULT_MUSIC_URL;
    }
    return url;
}

/* ================================================================== */
/*  Metadata getters                                                  */
/* ================================================================== */

std::string Esp32Music::GetTitle() const
{
    if (!title_name_.empty()) return title_name_;
    return current_song_name_;
}

std::string Esp32Music::GetArtist() const
{
    return artist_name_;
}
