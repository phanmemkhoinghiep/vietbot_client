#include "esp32_sd_music.h"
#include "board.h"
#include "audio_codec.h"
#include "application.h"
#include "sd_card.h"
#include <sys/stat.h>
#include <dirent.h>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <unordered_set>
#include <cstdlib>  // atoi, strtoull

#include <esp_log.h>
#include <esp_heap_caps.h>
#include "cJSON.h"

static const char* TAG = "Esp32SdMusic";

// ================================================================
//  UTILITY OF FREE FUNCTIONS (UTF-8, name, time, hint)
// ================================================================

// Normalize to lowercase but only affect ASCII (keep multi-byte UTF-8 unchanged)
static std::string ToLowerAscii(const std::string& s)
{
    std::string out = s;
    for (char& c : out) {
        unsigned char uc = static_cast<unsigned char>(c);
        if (uc < 128) {
            c = static_cast<char>(std::tolower(uc));
        }
    }
    return out;
}

static std::string ExtractDirectory(const std::string& full_path)
{
    size_t pos = full_path.find_last_of('/');
    if (pos == std::string::npos) return std::string();
    return full_path.substr(0, pos);
}

static std::string ExtractBaseNameNoExt(const std::string& name_or_path)
{
    size_t slash = name_or_path.find_last_of('/');
    size_t start = (slash == std::string::npos) ? 0 : slash + 1;
    size_t dot = name_or_path.find_last_of('.');
    size_t end = (dot == std::string::npos || dot < start) ? name_or_path.size() : dot;
    return name_or_path.substr(start, end - start);
}

static std::string MsToTimeString(int64_t ms)
{
    if (ms <= 0) {
        return "00:00";
    }

    int64_t total_sec = ms / 1000;
    int sec  = static_cast<int>(total_sec % 60);
    int min  = static_cast<int>((total_sec / 60) % 60);
    int hour = static_cast<int>(total_sec / 3600);

    char buf[32];

    if (hour > 0) {
        snprintf(buf, sizeof(buf), "%02d:%02d:%02d", hour, min, sec);
    } else {
        snprintf(buf, sizeof(buf), "%02d:%02d", min, sec);
    }

    return std::string(buf);
}

// Normalize string for searching:
// - lowercase ASCII
// - collapse ' ', '_', '-', '.', '/', '\\' into a single space
// - keep multi-byte UTF-8 bytes unchanged
static std::string NormalizeForSearch(const std::string& s)
{
    std::string lower = ToLowerAscii(s);
    std::string out;
    out.reserve(lower.size());

    bool last_space = false;

    for (char ch : lower) {
        unsigned char c = static_cast<unsigned char>(ch);

        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            out.push_back(ch);
            last_space = false;
        } else if (c < 128 && (ch == ' ' || ch == '_' || ch == '-' ||
                               ch == '.' || ch == '/' || ch == '\\')) {
            if (!last_space) {
                out.push_back(' ');
                last_space = true;
            }
        } else {
            out.push_back(ch);
            last_space = false;
        }
    }

    while (!out.empty() && out.front() == ' ') out.erase(out.begin());
    while (!out.empty() && out.back() == ' ')  out.pop_back();

    return out;
}

// Check file extension (e.g., ".mp3", ".wav", ...)
static bool HasExtension(const std::string& path, const char* ext)
{
    if (!ext) return false;
    std::string low = ToLowerAscii(path);
    size_t len_ext  = strlen(ext);
    if (low.size() < len_ext) return false;
    return memcmp(low.data() + low.size() - len_ext, ext, len_ext) == 0;
}

// Supported audio formats on SD
enum class SdAudioFormat : uint8_t {
    Unknown = 0,
    Mp3,
    Wav,
    Aac,
    Flac,
    Ogg,
    Opus
};

static SdAudioFormat DetectAudioFormat(const std::string& path)
{
    if (HasExtension(path, ".mp3"))  return SdAudioFormat::Mp3;
    if (HasExtension(path, ".wav"))  return SdAudioFormat::Wav;
    if (HasExtension(path, ".aac") ||
        HasExtension(path, ".m4a")) return SdAudioFormat::Aac;
    if (HasExtension(path, ".flac")) return SdAudioFormat::Flac;
    if (HasExtension(path, ".ogg"))  return SdAudioFormat::Ogg;
    if (HasExtension(path, ".opus")) return SdAudioFormat::Opus;
    return SdAudioFormat::Unknown;
}

// Parse header WAV PCM 16-bit simple (RIFF/WAVE/fmt/data)
static bool ParseWavHeader(FILE* fp,
                           int& sample_rate,
                           int& channels,
                           size_t& data_offset,
                           size_t& data_size)
{
    if (!fp) return false;

    uint8_t header[44];
    if (fread(header, 1, sizeof(header), fp) != sizeof(header)) {
        return false;
    }

    if (memcmp(header, "RIFF", 4) != 0 ||
        memcmp(header + 8, "WAVE", 4) != 0) {
        return false;
    }

    if (memcmp(header + 12, "fmt ", 4) != 0) {
        return false;
    }

    uint16_t audio_format = header[20] | (header[21] << 8);
    uint16_t num_channels = header[22] | (header[23] << 8);
    uint32_t sampleRate   = header[24] |
                             (header[25] << 8) |
                             (header[26] << 16) |
                             (header[27] << 24);
    uint16_t bitsPerSample = header[34] | (header[35] << 8);

    if (audio_format != 1 || bitsPerSample != 16 ||
        num_channels == 0 || sampleRate == 0) {
        // Only PCM 16-bit is supported
        return false;
    }

    if (memcmp(header + 36, "data", 4) != 0) {
        // More complex layout (different chunk) is not supported here
        return false;
    }

    uint32_t dataSize = header[40] |
                        (header[41] << 8) |
                        (header[42] << 16) |
                        (header[43] << 24);

    sample_rate = (int)sampleRate;
    channels    = (int)num_channels;
    data_offset = sizeof(header);
    data_size   = dataSize;

    return true;
}

// Score for suggestion mode (similar name + same directory + play count)
static int ComputeTrackScoreForBase(const Esp32SdMusic::TrackInfo& base,
                                    const Esp32SdMusic::TrackInfo& cand,
                                    uint32_t cand_play_count)
{
    int score = 0;

    std::string base_dir = ExtractDirectory(base.path);
    std::string cand_dir = ExtractDirectory(cand.path);
    if (!base_dir.empty() && base_dir == cand_dir) {
        score += 3;
    }

    std::string base_name = ExtractBaseNameNoExt(base.name.empty() ? base.path : base.name);
    std::string cand_name = ExtractBaseNameNoExt(cand.name.empty() ? cand.path : cand.name);

    base_name = ToLowerAscii(base_name);
    cand_name = ToLowerAscii(cand_name);

    if (!base_name.empty() && !cand_name.empty()) {
        if (cand_name.find(base_name) != std::string::npos ||
            base_name.find(cand_name) != std::string::npos)
        {
            score += 3;
        } else {
            auto b_space = base_name.find(' ');
            auto c_space = cand_name.find(' ');
            std::string b_first = base_name.substr(0, b_space);
            std::string c_first = cand_name.substr(0, c_space);
            if (!b_first.empty() && b_first == c_first) {
                score += 1;
            }
        }
    }

    if (cand_play_count > 0) {
        score += static_cast<int>(cand_play_count);
    }

    return score;
}

// ================================================================
//  ID3v1 GENRE TABLE
// ================================================================
static const char* kId3v1Genres[] = {
    "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge",
    "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B",
    "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
    "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient",
    "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical",
    "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel",
    "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
    "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave",
    "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
    "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap",
    "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
    "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal",
    "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll",
    "Hard Rock"
};

static const char* Id3v1GenreNameFromIndex(int idx)
{
    if (idx < 0) return nullptr;
    int count = sizeof(kId3v1Genres) / sizeof(kId3v1Genres[0]);
    if (idx >= count) return nullptr;
    return kId3v1Genres[idx];
}

// ================================================================
//  Read ID3v1 (end of file) — very lightweight, does not touch ID3v2
// ================================================================
static void ReadId3v1(const std::string& path,
                      Esp32SdMusic::TrackInfo& info)
{
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return;

    if (fseek(f, -128, SEEK_END) != 0) {
        fclose(f);
        return;
    }

    uint8_t tag[128];
    if (fread(tag, 1, 128, f) != 128) {
        fclose(f);
        return;
    }
    fclose(f);

    if (memcmp(tag, "TAG", 3) != 0) {
        return;
    }

    auto trim = [](const char* p, size_t n) -> std::string {
        std::string s(p, n);
        while (!s.empty() && (s.back() == ' ' || s.back() == '\0')) s.pop_back();
        return s;
    };

    std::string title   = trim((char*)tag + 3, 30);
    std::string artist  = trim((char*)tag + 33, 30);
    std::string album   = trim((char*)tag + 63, 30);
    std::string year    = trim((char*)tag + 93, 4);
    std::string comment = trim((char*)tag + 97, 28);
    uint8_t     track   = tag[126]; // ID3v1.1

    if (!title.empty()   && info.title.empty())   info.title   = title;
    if (!artist.empty()  && info.artist.empty())  info.artist  = artist;
    if (!album.empty()   && info.album.empty())   info.album   = album;
    if (!year.empty()    && info.year.empty())    info.year    = year;
    if (!comment.empty() && info.comment.empty()) info.comment = comment;

    if (info.track_number == 0 && track != 0) {
        info.track_number = track;
    }

    uint8_t genre_idx = tag[127];
    if (info.genre.empty() && genre_idx != 0xFF) {
        const char* gname = Id3v1GenreNameFromIndex((int)genre_idx);
        if (gname) {
            info.genre = gname;  // Pop, Rock,...
        } else {
            info.genre = std::to_string((int)genre_idx); // fallback
        }
    }
}

// ================================================================
//   Read ID3v2 SAFETY MODE (does not load the entire header into RAM)
//   Only reads TIT2 (title), TPE1 (artist), TALB (album), TYER (year), TCON (genre)
// ================================================================
static std::string Utf16ToUtf8(const uint8_t* data, size_t len, bool big_endian)
{
    std::string out;
    out.reserve(len);

    for (size_t i = 0; i + 1 < len; i += 2) {
        uint16_t ch;
        if (!big_endian)
            ch = data[i] | (data[i + 1] << 8);
        else
            ch = (data[i] << 8) | data[i + 1];

        // Basic UTF-16 (does not handle surrogate pairs because ID3 rarely uses them)
        if (ch < 0x80) {
            out.push_back((char)ch);
        } else if (ch < 0x800) {
            out.push_back((char)(0xC0 | (ch >> 6)));
            out.push_back((char)(0x80 | (ch & 0x3F)));
        } else {
            out.push_back((char)(0xE0 | (ch >> 12)));
            out.push_back((char)(0x80 | ((ch >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (ch & 0x3F)));
        }
    }

    return out;
}

static std::string TrimNull(const std::string& s)
{
    size_t end = s.find('\0');
    if (end == std::string::npos) return s;
    return s.substr(0, end);
}

// Normalize TCON value (ID3v2 genre)
static std::string NormalizeTcon(const std::string& raw)
{
    std::string s = TrimNull(raw);

    // Trim leading/trailing spaces
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.erase(s.begin());
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) s.pop_back();

    // Dạng "(13)" hoặc "(13)Pop" → map 13 → tên nếu có
    if (!s.empty() && s.front() == '(') {
        size_t close = s.find(')');
        if (close != std::string::npos && close > 1) {
            std::string num = s.substr(1, close - 1);
            int idx = atoi(num.c_str());
            const char* gname = Id3v1GenreNameFromIndex(idx);
            if (gname) {
                return std::string(gname);
            }
        }
    }

    return s;
}

static void ReadId3v2_Safe(const std::string& path,
                           Esp32SdMusic::TrackInfo& info)
{
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return;

    uint8_t hdr[10];
    if (fread(hdr, 1, 10, f) != 10) {
        fclose(f);
        return;
    }

    if (memcmp(hdr, "ID3", 3) != 0) {
        fclose(f);
        return;
    }

    uint32_t tag_size =
        ((hdr[6] & 0x7F) << 21) |
        ((hdr[7] & 0x7F) << 14) |
        ((hdr[8] & 0x7F) << 7)  |
         (hdr[9] & 0x7F);

    uint32_t pos = 10;
    uint32_t end = 10 + tag_size;

    auto readFrame = [&](const char* id) -> std::string {
        uint32_t cur = pos;

        while (cur + 10 <= end) {
            fseek(f, cur, SEEK_SET);

            uint8_t frame_hdr[10];
            if (fread(frame_hdr, 1, 10, f) != 10) break;

            if (frame_hdr[0] == 0) break;

            uint32_t frame_size =
                (frame_hdr[4] << 24) |
                (frame_hdr[5] << 16) |
                (frame_hdr[6] << 8)  |
                 frame_hdr[7];

            if (frame_size == 0) break;

            if (memcmp(frame_hdr, id, 4) == 0) {
                if (frame_size < 2 || frame_size > 2048) return "";

                std::vector<uint8_t> buf(frame_size);
                if (fread(buf.data(), 1, frame_size, f) != frame_size)
                    return "";

                uint8_t enc = buf[0];
                const uint8_t* p = &buf[1];
                size_t plen = frame_size - 1;

                std::string raw;

                switch (enc) {
                    case 0: // ISO-8859-1
                        raw = std::string((char*)p, plen);
                        break;

                    case 3: // UTF-8
                        raw = std::string((char*)p, plen);
                        break;

                    case 1: { // UTF-16 with BOM
                        if (plen < 2) return "";
                        bool big_endian = !(p[0] == 0xFF && p[1] == 0xFE);
                        size_t start = 2;
                        raw = Utf16ToUtf8(p + start, plen - start, big_endian);
                        break;
                    }

                    case 2: // UTF-16BE no BOM
                        raw = Utf16ToUtf8(p, plen, true);
                        break;

                    default:
                        return "";
                }

                return TrimNull(raw);
            }

            cur += 10 + frame_size;
            if (cur >= end) break;
        }
        return "";
    };

    std::string t, a, b, y, g;

    t = readFrame("TIT2");
    if (!t.empty()) info.title = t;

    a = readFrame("TPE1");
    if (!a.empty()) info.artist = a;

    b = readFrame("TALB");
    if (!b.empty()) info.album = b;

    y = readFrame("TYER");
    if (!y.empty()) info.year = y;

    g = readFrame("TCON");
    if (!g.empty()) {
        info.genre = NormalizeTcon(g);
    }

    fclose(f);
}

// Escape string to JSON string
static std::string JsonEscape(const std::string& in)
{
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in) {
        switch (c) {
        case '\"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\b': out += "\\b";  break;
        case '\f': out += "\\f";  break;
        case '\n': out += "\\n";  break;
        case '\r': out += "\\r";  break;
        case '\t': out += "\\t";  break;
        default:
            if (c < 0x20) {
                char buf[7];
                snprintf(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            } else {
                out.push_back((char)c);
            }
        }
    }
    return out;
}

// ============================================================================
//                         PART 1 / 3
//      CTOR / DTOR / PLAYLIST / DIRECTORY / TRACK COUNT / PAGINATION
// ============================================================================

Esp32SdMusic::Esp32SdMusic()
    : AudioStreamPlayer(),
      sd_card_(nullptr),
      root_directory_(),
      playlist_(),
      playlist_mutex_(),
      current_index_(-1),
      play_count_(),
      state_(PlayerState::Stopped),
      shuffle_enabled_(false),
      repeat_mode_(RepeatMode::None),
      total_duration_ms_(0),
      genre_playlist_(),
      genre_current_pos_(-1),
      genre_current_key_(),
      history_mutex_(),
      play_history_indices_()
{
}

Esp32SdMusic::~Esp32SdMusic()
{
    ESP_LOGI(TAG, "Destroying SD music module");
    Stop();
    ESP_LOGI(TAG, "SD music module destroyed");
}

void Esp32SdMusic::Initialize(class SdCard* sd_card, AudioCodec *codec) {
    sd_card_ = sd_card;
    if (codec) {
        SetAudioCodec(codec);
    }
    if (sd_card_ && sd_card_->IsMounted()) {
        root_directory_ = sd_card_->GetMountPoint();
    } else {
        ESP_LOGW(TAG, "SD card not mounted yet — will retry later");
    }
}

// Playlist loading — using playlist.json
// If not available / corrupted / empty → rescan and save playlist.json
// If the file has valid content → only read, do not scan
bool Esp32SdMusic::LoadPlaylist()
{
    std::vector<TrackInfo> list;

    if (root_directory_.empty()) {
        if (sd_card_ == nullptr || !sd_card_->IsMounted()) {
            ESP_LOGE(TAG, "LoadPlaylist: SD not mounted");
            return false;
        }
        root_directory_ = sd_card_->GetMountPoint();
    }

    // Playlist by current root directory
    // For example: /sdcard/playlist.json or /sdcard/Music/playlist.json
    std::string playlist_path = root_directory_ + "/playlist.json";

    bool loaded = LoadPlaylistFromFile(playlist_path, list);
    if (!loaded || list.empty()) {
        ESP_LOGW(TAG,
                 "playlist.json missing/empty/invalid, scanning SD to rebuild: %s",
                 root_directory_.c_str());

        ESP_LOGI(TAG, "Scanning SD card: %s", root_directory_.c_str());
        ScanDirectoryRecursive(root_directory_, list);

        // Save playlist.json (even if the list is empty, consider it as an empty playlist)
        if (!SavePlaylistToFile(playlist_path, list)) {
            ESP_LOGE(TAG, "Failed to save playlist.json: %s", playlist_path.c_str());
            return false;
        }
    }

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        playlist_.swap(list);
        current_index_ = playlist_.empty() ? -1 : 0;
        play_count_.assign(playlist_.size(), 0);
    }

    {
        std::lock_guard<std::mutex> hlock(history_mutex_);
        play_history_indices_.clear();
    }

    ESP_LOGI(TAG, "Track list ready from playlist.json: %u tracks",
             (unsigned)playlist_.size());
    return !playlist_.empty();
}

size_t Esp32SdMusic::GetTotalTracks() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    return playlist_.size();
}

std::vector<Esp32SdMusic::TrackInfo> Esp32SdMusic::GetPlaylist() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    return playlist_;
}

Esp32SdMusic::TrackInfo Esp32SdMusic::GetTrackInfo(int index) const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    if (index < 0 || index >= (int)playlist_.size()) return {};
    return playlist_[index];
}

int Esp32SdMusic::GetCurrentIndex() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    return current_index_;
}

// Build full path + resolve FAT short / case-insensitive
bool Esp32SdMusic::ResolveDirectoryRelative(const std::string& relative_dir,
                                            std::string& out_full)
{
    if (sd_card_ == nullptr || !sd_card_->IsMounted()) {
        ESP_LOGE(TAG, "ResolveDirectoryRelative: SD not mounted");
        return false;
    }

    std::string mount = sd_card_->GetMountPoint();
    std::string full;

    if (relative_dir.empty() || relative_dir == "/") {
        full = mount;
    } else if (relative_dir[0] == '/') {
        full = mount + relative_dir;
    } else {
        full = mount + "/" + relative_dir;
    }

    full = ResolveLongName(full);
    full = ResolveCaseInsensitiveDir(full);

    struct stat st{};
    if (stat(full.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        ESP_LOGE(TAG, "Invalid directory: %s", full.c_str());
        return false;
    }

    out_full = full;
    return true;
}

bool Esp32SdMusic::SetDirectory(const std::string& relative_dir)
{
    std::string full;
    if (!ResolveDirectoryRelative(relative_dir, full)) {
        ESP_LOGE(TAG, "SetDirectory: cannot resolve %s", relative_dir.c_str());
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        root_directory_ = full;
    }

    ESP_LOGI(TAG, "Directory selected: %s", full.c_str());
    return LoadPlaylist();
}

bool Esp32SdMusic::PlayDirectory(const std::string& relative_dir)
{
    ESP_LOGI(TAG, "Request to play directory: %s", relative_dir.c_str());
    if (!SetDirectory(relative_dir)) {
        ESP_LOGE(TAG, "PlayDirectory: cannot set directory: %s", relative_dir.c_str());
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (playlist_.empty()) {
            ESP_LOGE(TAG, "PlayDirectory: directory is empty: %s", relative_dir.c_str());
            return false;
        }
        current_index_ = 0;
        ESP_LOGI(TAG, "PlayDirectory: start track #0: %s",
                 playlist_[0].name.c_str());
    }
    return Play();
}

// Find index by keyword (name or path)
int Esp32SdMusic::FindTrackByKeyword(const std::string& keyword) const
{
    if (keyword.empty()) return -1;

    std::string kw = NormalizeForSearch(keyword);
    if (kw.empty()) return -1;

    std::lock_guard<std::mutex> lock(playlist_mutex_);

    for (int i = 0; i < (int)playlist_.size(); ++i) {
        std::string name_norm = NormalizeForSearch(playlist_[i].name);
        std::string path_norm = NormalizeForSearch(playlist_[i].path);

        if ((!name_norm.empty() && name_norm.find(kw) != std::string::npos) ||
            (!path_norm.empty() && path_norm.find(kw) != std::string::npos)) {
            return i;
        }
    }
    return -1;
}

bool Esp32SdMusic::PlayByName(const std::string& keyword)
{
    if (keyword.empty()) {
        ESP_LOGW(TAG, "PlayByName(): empty keyword");
        return false;
    }

    bool need_reload = false;
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (playlist_.empty()) {
            ESP_LOGW(TAG, "PlayByName(): playlist empty — reloading");
            need_reload = true;
        }
    }

    if (need_reload) {
        if (!LoadPlaylist()) {
            ESP_LOGE(TAG, "PlayByName(): Cannot load playlist");
            return false;
        }
    }

    int found_index = FindTrackByKeyword(keyword);
    if (found_index < 0) {
        ESP_LOGW(TAG, "PlayByName(): no match for '%s'", keyword.c_str());
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (found_index < 0 || found_index >= (int)playlist_.size()) {
            return false;
        }
        current_index_ = found_index;
        ESP_LOGI(TAG, "PlayByName(): matched track #%d → %s",
                 found_index, playlist_[found_index].name.c_str());
    }

    return Play();
}

std::string Esp32SdMusic::GetCurrentTrack() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    if (current_index_ < 0 || current_index_ >= (int)playlist_.size()) return "";
    return playlist_[current_index_].name;
}

std::string Esp32SdMusic::GetCurrentTrackPath() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    if (current_index_ < 0 || current_index_ >= (int)playlist_.size()) return "";
    return playlist_[current_index_].path;
}

std::vector<std::string> Esp32SdMusic::GetDirectories() const
{
    std::vector<std::string> dirs;

    DIR* d = opendir(root_directory_.c_str());
    if (!d) {
        ESP_LOGE(TAG, "Cannot open directory: %s", root_directory_.c_str());
        return dirs;
    }

    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name = ent->d_name;

        if (name == "." || name == "..")
            continue;

        std::string full = root_directory_ + "/" + name;

        struct stat st{};
        if (stat(full.c_str(), &st) != 0)
            continue;

        if (S_ISDIR(st.st_mode)) {
            dirs.push_back(name);
        }
    }

    closedir(d);
    return dirs;
}

std::vector<Esp32SdMusic::TrackInfo>
Esp32SdMusic::SearchTracks(const std::string& keyword) const
{
    std::vector<TrackInfo> results;
    if (keyword.empty()) return results;

    std::string kw = NormalizeForSearch(keyword);
    if (kw.empty()) return results;

    std::lock_guard<std::mutex> lock(playlist_mutex_);
    for (const auto& t : playlist_) {
        std::string name_norm = NormalizeForSearch(t.name);
        std::string path_norm = NormalizeForSearch(t.path);

        if ((!name_norm.empty() && name_norm.find(kw) != std::string::npos) ||
            (!path_norm.empty() && path_norm.find(kw) != std::string::npos)) {
            results.push_back(t);
        }
    }
    return results;
}

std::vector<std::string> Esp32SdMusic::GetGenres() const
{
    std::vector<std::string> genres;
    std::unordered_set<std::string> uniq;

    std::lock_guard<std::mutex> lock(playlist_mutex_);
    for (const auto &t : playlist_) {
        if (t.genre.empty()) continue;
        if (uniq.insert(t.genre).second) {
            genres.push_back(t.genre);
        }
    }

    std::sort(genres.begin(), genres.end(),
              [](const std::string& a, const std::string& b) {
                  return ToLowerAscii(a) < ToLowerAscii(b);
              });

    return genres;
}

std::string Esp32SdMusic::ResolveCaseInsensitiveDir(const std::string& path)
{
    size_t pos = path.find_last_of('/');
    if (pos == std::string::npos)
        return path;

    std::string parent = path.substr(0, pos);
    std::string name   = path.substr(pos + 1);

    DIR* d = opendir(parent.c_str());
    if (!d) {
        return path;
    }

    std::string lowerName = ToLowerAscii(name);

    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string entry = ent->d_name;

        if (entry == "." || entry == "..")
            continue;

        std::string lowerEntry = ToLowerAscii(entry);

        if (lowerEntry == lowerName) {
            std::string full = parent + "/" + entry;

            struct stat st{};
            if (stat(full.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
                closedir(d);
                return full;
            }
        }
    }

    closedir(d);
    return path;
}

bool Esp32SdMusic::SetTrack(int index)
{
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (index < 0 || index >= (int)playlist_.size()) {
            ESP_LOGE(TAG, "SetTrack: index %d out of range", index);
            return false;
        }
        current_index_ = index;
        ESP_LOGI(TAG, "Switching to track #%d: %s",
                 index, playlist_[index].name.c_str());
    }
    return Play();
}

void Esp32SdMusic::ScanDirectoryRecursive(
    const std::string& dir,
    std::vector<TrackInfo>& out)
{
    DIR* d = opendir(dir.c_str());
    if (!d) {
        ESP_LOGE(TAG, "Cannot open directory: %s", dir.c_str());
        return;
    }

    struct dirent* ent;
    while ((ent = readdir(d)) != nullptr) {
        std::string name_utf8 = ent->d_name;

        if (name_utf8 == "." || name_utf8 == "..")
            continue;

        std::string full = dir + "/" + name_utf8;

        struct stat st{};
        if (stat(full.c_str(), &st) != 0) {
            continue;
        }

        if (S_ISDIR(st.st_mode)) {
            ScanDirectoryRecursive(full, out);
            continue;
        }

        SdAudioFormat fmt = DetectAudioFormat(full);
        if (fmt == SdAudioFormat::Unknown)
            continue;

        TrackInfo t;
        t.path      = full;
        t.file_size = st.st_size;

        // ID3v2 (priority) → fallback to ID3v1 if missing
        ReadId3v2_Safe(full, t);
        ReadId3v1(full, t);

        // Display name: priority to title, fallback to file name (without extension)
        if (!t.title.empty())
            t.name = t.title;
        else
            t.name = ExtractBaseNameNoExt(name_utf8);

        out.push_back(std::move(t));
    }

    closedir(d);
}

// Rebuild playlist according to user request (MCP calls this function)
// Ignore the current playlist.json content, always rescan and overwrite
bool Esp32SdMusic::RebuildPlaylist()
{
    std::vector<TrackInfo> list;

    if (root_directory_.empty()) {
        if (sd_card_ == nullptr || !sd_card_->IsMounted()) {
            ESP_LOGE(TAG, "RebuildPlaylist: SD not mounted");
            return false;
        }
        root_directory_ = sd_card_->GetMountPoint();
    }

    ESP_LOGI(TAG, "Rebuilding playlist by scanning directory: %s",
             root_directory_.c_str());

    ScanDirectoryRecursive(root_directory_, list);

    std::string playlist_path = root_directory_ + "/playlist.json";
    if (!SavePlaylistToFile(playlist_path, list)) {
        ESP_LOGE(TAG, "Failed to save playlist.json after rebuild");
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        playlist_.swap(list);
        current_index_ = playlist_.empty() ? -1 : 0;
        play_count_.assign(playlist_.size(), 0);
    }

    {
        std::lock_guard<std::mutex> hlock(history_mutex_);
        play_history_indices_.clear();
    }

    ESP_LOGI(TAG, "Playlist rebuilt: %u tracks",
             (unsigned)playlist_.size());
    return !playlist_.empty();
}

// JSON Playlist: stores all the necessary metadata for searching/finding songs (does not store cover art).
bool Esp32SdMusic::SavePlaylistToFile(const std::string& playlist_path,
                                      const std::vector<TrackInfo>& list) const
{
    FILE* fp = fopen(playlist_path.c_str(), "wb");
    if (!fp) {
        ESP_LOGE(TAG, "Cannot create playlist file: %s", playlist_path.c_str());
        return false;
    }

    // Header JSON
    fprintf(fp, "{\n");
    fprintf(fp, "  \"version\": 1,\n");
    fprintf(fp, "  \"tracks\": [\n");

    size_t written = 0;

    for (size_t i = 0; i < list.size(); ++i) {
        const auto& t = list[i];

        // Prepare the escape sequence.
        std::string name    = JsonEscape(t.name.empty()
                                         ? ExtractBaseNameNoExt(t.path)
                                         : t.name);
        std::string path    = JsonEscape(t.path);
        std::string title   = JsonEscape(t.title);
        std::string artist  = JsonEscape(t.artist);
        std::string album   = JsonEscape(t.album);
        std::string genre   = JsonEscape(t.genre);
        std::string comment = JsonEscape(t.comment);
        std::string year    = JsonEscape(t.year);

        int track_number = t.track_number;
        int duration_ms  = t.duration_ms;
        int bitrate      = t.bitrate_kbps;
        unsigned long long file_size = (unsigned long long)t.file_size;

        // Start object
        fprintf(fp, "    {\n");
        fprintf(fp, "      \"name\": \"%s\",\n",    name.c_str());
        fprintf(fp, "      \"path\": \"%s\",\n",    path.c_str());
        fprintf(fp, "      \"title\": \"%s\",\n",   title.c_str());
        fprintf(fp, "      \"artist\": \"%s\",\n",  artist.c_str());
        fprintf(fp, "      \"album\": \"%s\",\n",   album.c_str());
        fprintf(fp, "      \"genre\": \"%s\",\n",   genre.c_str());
        fprintf(fp, "      \"comment\": \"%s\",\n", comment.c_str());
        fprintf(fp, "      \"year\": \"%s\",\n",    year.c_str());
        fprintf(fp, "      \"track_number\": %d,\n",   track_number);
        fprintf(fp, "      \"duration_ms\": %d,\n",    duration_ms);
        fprintf(fp, "      \"bitrate_kbps\": %d,\n",   bitrate);
        fprintf(fp, "      \"file_size\": %llu\n",     file_size);
        fprintf(fp, "    }%s\n", (i + 1 < list.size()) ? "," : "");

        ++written;
    }

    fprintf(fp, "  ]\n");
    fprintf(fp, "}\n");
    fclose(fp);

    ESP_LOGI(TAG, "Playlist file (JSON) saved: %s (%u tracks)",
             playlist_path.c_str(), (unsigned)written);
    // Allow empty playlist (0 tracks) to be considered successful
    return true;
}

bool Esp32SdMusic::LoadPlaylistFromFile(const std::string& playlist_path,
                                        std::vector<TrackInfo>& out) const
{
    FILE* fp = fopen(playlist_path.c_str(), "rb");
    if (!fp) {
        ESP_LOGW(TAG, "Playlist file not found: %s", playlist_path.c_str());
        return false;
    }

    if (fseek(fp, 0, SEEK_END) != 0) {
        fclose(fp);
        return false;
    }
    long sz = ftell(fp);
    if (sz <= 0) {
        fclose(fp);
        ESP_LOGW(TAG, "Playlist file is empty: %s", playlist_path.c_str());
        return false;
    }
    fseek(fp, 0, SEEK_SET);

    char* buf = (char*) heap_caps_malloc(sz + 1, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!buf) {
        ESP_LOGE(TAG, "Not enough memory to read playlist file (%ld bytes)", sz);
        fclose(fp);
        return false;
    }

    size_t read_bytes = fread(buf, 1, sz, fp);
    fclose(fp);
    buf[read_bytes] = '\0';

    cJSON* root = cJSON_Parse(buf);
    heap_caps_free(buf);

    if (!root) {
        ESP_LOGE(TAG, "Failed to parse playlist.json as JSON");
        return false;
    }

    cJSON* tracks = cJSON_GetObjectItem(root, "tracks");
    if (!cJSON_IsArray(tracks)) {
        ESP_LOGE(TAG, "playlist.json has no 'tracks' array");
        cJSON_Delete(root);
        return false;
    }

    out.clear();
    cJSON* item = nullptr;
    cJSON_ArrayForEach(item, tracks) {
        if (!cJSON_IsObject(item)) {
            continue;
        }

        auto getStr = [&](const char* key) -> std::string {
            cJSON* v = cJSON_GetObjectItem(item, key);
            if (cJSON_IsString(v) && v->valuestring) {
                return std::string(v->valuestring);
            }
            return std::string();
        };

        auto getInt = [&](const char* key) -> int {
            cJSON* v = cJSON_GetObjectItem(item, key);
            if (cJSON_IsNumber(v)) {
                return v->valueint;
            }
            return 0;
        };

        auto getSizeT = [&](const char* key) -> size_t {
            cJSON* v = cJSON_GetObjectItem(item, key);
            if (cJSON_IsNumber(v)) {
                return (size_t)v->valuedouble;
            }
            return 0;
        };

        TrackInfo t;

        t.name    = getStr("name");
        t.path    = getStr("path");
        t.title   = getStr("title");
        t.artist  = getStr("artist");
        t.album   = getStr("album");
        t.genre   = getStr("genre");
        t.comment = getStr("comment");
        t.year    = getStr("year");

        if (t.path.empty()) {
            continue;
        }

        if (t.name.empty()) {
            t.name = ExtractBaseNameNoExt(t.path);
        }

        t.track_number = getInt("track_number");
        t.duration_ms  = getInt("duration_ms");
        t.bitrate_kbps = getInt("bitrate_kbps");
        t.file_size    = getSizeT("file_size");

        // Do not use cover_* in playlists (it's always 0 / empty).
        t.cover_size = 0;
        t.cover_mime.clear();

        // ESP_LOGI(TAG, "Loaded track from playlist: %s (path: %s, size: %zu bytes, track_number: %d, duration_ms: %d, bitrate_kbps: %d)",
        //          t.name.c_str(), t.path.c_str(), t.file_size, t.track_number, t.duration_ms, t.bitrate_kbps);

        out.push_back(std::move(t));
    }

    cJSON_Delete(root);

    if (out.empty()) {
        ESP_LOGW(TAG, "Playlist JSON has no valid tracks: %s",
                 playlist_path.c_str());
        return false;
    }

    ESP_LOGI(TAG, "Loaded %u tracks from playlist.json: %s",
             (unsigned)out.size(), playlist_path.c_str());
    return true;
}

std::string Esp32SdMusic::ResolveLongName(const std::string& path)
{
    // Do not handle short-name 8.3 → return the original path
    return path;
}

int Esp32SdMusic::FindNextTrackIndex(int start, int direction)
{
    if (playlist_.empty()) return -1;
    int count = static_cast<int>(playlist_.size());
    if (start < 0 || start >= count)
        return 0;
    int result = (start + direction + count) % count;
    return result;
}

size_t Esp32SdMusic::GetTrackCountInDir(const std::string& relative_dir)
{
    std::string full;
    if (!ResolveDirectoryRelative(relative_dir, full)) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(playlist_mutex_);
    if (playlist_.empty()) {
        return 0;
    }

    std::string prefix = full;
    if (!prefix.empty() && prefix.back() != '/') {
        prefix += '/';
    }

    size_t count = 0;
    for (const auto& t : playlist_) {
        if (t.path.compare(0, prefix.size(), prefix) == 0) {
            ++count;
        }
    }
    return count;
}

size_t Esp32SdMusic::GetTrackCount() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    return playlist_.size();
}

std::vector<Esp32SdMusic::TrackInfo>
Esp32SdMusic::GetPlaylistPage(size_t page_index, size_t page_size) const
{
    std::vector<TrackInfo> result;
    if (page_size == 0) return result;

    std::lock_guard<std::mutex> lock(playlist_mutex_);
    if (playlist_.empty()) return result;

    size_t start = page_index * page_size;
    if (start >= playlist_.size()) return result;

    size_t end = std::min(start + page_size, playlist_.size());
    result.reserve(end - start);
    for (size_t i = start; i < end; ++i) {
        result.push_back(playlist_[i]);
    }
    return result;
}

// ============================================================================
//                         PART 2 / 3
//      SHUFFLE / REPEAT / PLAY-PAUSE-STOP / THREAD / DECODE
// ============================================================================

void Esp32SdMusic::SetShuffleMode(bool enabled)
{
    shuffle_enabled_ = enabled;
    ESP_LOGI(TAG, "Shuffle: %s", enabled ? "ON" : "OFF");
}

void Esp32SdMusic::SetRepeatMode(RepeatMode mode)
{
    repeat_mode_ = mode;
    const char* mode_str =
        (mode == RepeatMode::None)      ? "None" :
        (mode == RepeatMode::RepeatOne) ? "RepeatOne" : "RepeatAll";
    ESP_LOGI(TAG, "Repeat mode = %s", mode_str);
}

bool Esp32SdMusic::IsPlaying() const
{
    PlayerState st = state_.load();
    return (st != PlayerState::Stopped &&
            st != PlayerState::Error);
}

bool Esp32SdMusic::Play()
{
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);

        if (playlist_.empty()) {
            ESP_LOGW(TAG, "Playlist empty — reloading");
            LoadPlaylist();
            if (playlist_.empty()) {
                ESP_LOGE(TAG, "No audio files found on SD");
                return false;
            }
        }

        if (current_index_ < 0)
            current_index_ = 0;
    }

    // Resume from pause
    if (state_.load() == PlayerState::Paused) {
        ESP_LOGI(TAG, "Resuming playback");
        ResumeStream();
        state_.store(PlayerState::Playing);
        return true;
    }

    // Stop any current playback
    StopStream();

    TrackInfo track;
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (current_index_ < 0 || current_index_ >= (int)playlist_.size()) {
            ESP_LOGE(TAG, "Invalid track index %d", current_index_);
            return false;
        }
        track = playlist_[current_index_];
    }

    ESP_LOGI(TAG, "Playing: %s", track.path.c_str());
    ESP_LOGI(TAG, "Track info: name='%s', title='%s', artist='%s', album='%s', genre='%s', duration=%d ms, bitrate=%d kbps, file_size=%zu bytes",
             track.name.c_str(), track.title.c_str(), track.artist.c_str(),
             track.album.c_str(), track.genre.c_str(), track.duration_ms,
             track.bitrate_kbps, track.file_size);

    // Detect file format
    AudioDecoderType dec_type = DetectFileFormat(track.path);
    if (dec_type == AudioDecoderType::AUTO) {
        ESP_LOGE(TAG, "Unsupported file format: %s", track.path.c_str());
        state_.store(PlayerState::Error);
        return false;
    }

    // For WAV, parse header and set wav_info_ before starting
    if (dec_type == AudioDecoderType::WAV) {
        FILE* fp = fopen(track.path.c_str(), "rb");
        if (!fp) {
            ESP_LOGE(TAG, "Cannot open WAV file: %s", track.path.c_str());
            state_.store(PlayerState::Error);
            return false;
        }
        int sr = 0, ch = 0;
        size_t d_off = 0, d_sz = 0;
        bool ok = ParseWavHeader(fp, sr, ch, d_off, d_sz);
        fclose(fp);
        if (!ok) {
            ESP_LOGE(TAG, "Invalid WAV header: %s", track.path.c_str());
            state_.store(PlayerState::Error);
            return false;
        }
        wav_info_.sample_rate     = sr;
        wav_info_.channels        = ch;
        wav_info_.bits_per_sample = 16;
        wav_info_.data_offset     = d_off;
        wav_info_.data_size       = d_sz;

        total_duration_ms_ = (int64_t)d_sz * 1000 / (sr * ch * 2);
    }

    state_.store(PlayerState::Preparing);
    RecordPlayHistory(current_index_);

    if (!StartStream(track.path, dec_type)) {
        ESP_LOGE(TAG, "Failed to start stream for: %s", track.path.c_str());
        state_.store(PlayerState::Error);
        return false;
    }

    state_.store(PlayerState::Playing);
    return true;
}

bool Esp32SdMusic::Play(const std::string& file_path)
{
    if (file_path.empty()) {
        ESP_LOGW(TAG, "Play(file_path): empty path");
        return false;
    }

    // Try to find the track in the current playlist
    int idx = FindTrackByKeyword(file_path);
    if (idx >= 0) {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        current_index_ = idx;
    } else {
        // File not in playlist — add it temporarily and set as current
        struct stat st{};
        if (stat(file_path.c_str(), &st) != 0) {
            ESP_LOGE(TAG, "Play(file_path): file not found: %s", file_path.c_str());
            return false;
        }

        TrackInfo t;
        t.path      = file_path;
        t.file_size = st.st_size;
        t.name      = ExtractBaseNameNoExt(file_path);

        ReadId3v2_Safe(file_path, t);
        ReadId3v1(file_path, t);
        if (!t.title.empty()) t.name = t.title;

        std::lock_guard<std::mutex> lock(playlist_mutex_);
        playlist_.push_back(std::move(t));
        play_count_.push_back(0);
        current_index_ = static_cast<int>(playlist_.size()) - 1;
    }

    return Play();
}

void Esp32SdMusic::Pause()
{
    if (state_.load() == PlayerState::Playing) {
        ESP_LOGI(TAG, "Pausing playback");
        PauseStream();
        state_.store(PlayerState::Paused);
    }
}

void Esp32SdMusic::Stop()
{
    PlayerState st = state_.load();

    if (st == PlayerState::Stopped ||
        st == PlayerState::Error) {
        return;
    }

    ESP_LOGI(TAG, "Stopping SD music playback");
    state_.store(PlayerState::Stopped);
    StopStream();
    total_duration_ms_ = 0;
    ESP_LOGI(TAG, "SD music stopped successfully");
}

bool Esp32SdMusic::Next()
{
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (playlist_.empty()) return false;
        if (shuffle_enabled_) {
            if (playlist_.size() > 1) {
                int new_i;
                do {
                    new_i = rand() % playlist_.size();
                } while (new_i == current_index_);
                current_index_ = new_i;
            }
        } else {
            current_index_ = FindNextTrackIndex(current_index_, +1);
        }
        ESP_LOGW(TAG, "Next track → #%d: %s",
                 current_index_, playlist_[current_index_].name.c_str());
    }
    state_.store(PlayerState::Stopped);
    return Play();
}

bool Esp32SdMusic::Prev()
{
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (playlist_.empty()) return false;
        if (shuffle_enabled_) {
            if (playlist_.size() > 1) {
                int new_i;
                do {
                    new_i = rand() % playlist_.size();
                } while (new_i == current_index_);
                current_index_ = new_i;
            }
        } else {
            current_index_ = FindNextTrackIndex(current_index_, -1);
        }
        ESP_LOGW(TAG, "Previous track → #%d: %s",
                 current_index_, playlist_[current_index_].name.c_str());
    }
    state_.store(PlayerState::Stopped);
    return Play();
}

void Esp32SdMusic::RecordPlayHistory(int index)
{
    if (index < 0) return;

    {
        std::lock_guard<std::mutex> lock(history_mutex_);
        play_history_indices_.push_back(index);
        const size_t kMaxHistory = 200;
        if (play_history_indices_.size() > kMaxHistory) {
            size_t remove_count = play_history_indices_.size() - kMaxHistory;
            play_history_indices_.erase(
                play_history_indices_.begin(),
                play_history_indices_.begin() + remove_count);
        }
    }

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (index >= 0 && index < (int)play_count_.size()) {
            ++play_count_[index];
        }
    }
}

// ============================================================================
//       AudioStreamPlayer overrides -- SourceDataLoop + hooks
// ============================================================================

void Esp32SdMusic::SourceDataLoop(const std::string& source)
{
    // source = file path on SD card
    FILE* fp = fopen(source.c_str(), "rb");
    if (!fp) {
        ESP_LOGE(TAG, "SourceDataLoop: cannot open %s", source.c_str());
        return;
    }

    // Get file size
    struct stat st{};
    size_t file_size = 0;
    if (stat(source.c_str(), &st) == 0) {
        file_size = (size_t)st.st_size;
    }
    (void)file_size;  // may be used for progress/duration in future

    // For WAV, seek past header to data section
    if (GetDecoderType() == AudioDecoderType::WAV && wav_info_.data_offset > 0) {
        fseek(fp, (long)wav_info_.data_offset, SEEK_SET);
    } else {
        // For compressed, skip ID3 tag if present
        uint8_t id3_buf[10];
        size_t rd = fread(id3_buf, 1, 10, fp);
        if (rd == 10) {
            size_t skip = SkipId3Tag(id3_buf, rd);
            if (skip > 0) {
                fseek(fp, (long)skip, SEEK_SET);
                ESP_LOGI(TAG, "Skipped ID3 tag: %zu bytes", skip);
            } else {
                fseek(fp, 0, SEEK_SET);
            }
        } else {
            fseek(fp, 0, SEEK_SET);
        }
    }

    uint8_t read_buf[AUDIO_FILE_READ_CHUNK_SIZE];
    size_t total_read = 0;

    while (IsSourceActive() && IsPlaying()) {
        size_t rd = fread(read_buf, 1, sizeof(read_buf), fp);
        if (rd == 0) {
            ESP_LOGI(TAG, "SourceDataLoop: EOF after %zu bytes", total_read);
            break;
        }

        if (!PushToBuffer(read_buf, rd)) {
            ESP_LOGW(TAG, "SourceDataLoop: PushToBuffer failed (stopped?)");
            break;
        }

        total_read += rd;
    }

    fclose(fp);
    ESP_LOGI(TAG, "SourceDataLoop: finished, total %zu bytes read", total_read);
}

void Esp32SdMusic::OnStreamInfoReady(int sample_rate, int bits_per_sample,
                                      int channels, int bitrate, int frame_size)
{
    ESP_LOGI(TAG, "Stream info: %d Hz, %d-bit, %d ch, %d kbps, %d frame size",
             sample_rate, bits_per_sample, channels, bitrate, frame_size);

    // Estimate duration for compressed formats
    if (GetDecoderType() != AudioDecoderType::WAV) {
        {
            std::lock_guard<std::mutex> lock(playlist_mutex_);
            if (current_index_ >= 0 && current_index_ < (int)playlist_.size()) {
                auto &track = playlist_[current_index_];
                if (track.file_size > 0 && bitrate > 0) {
                    total_duration_ms_ = (int64_t)track.file_size * 8LL / bitrate;
                }
                track.duration_ms  = (int)total_duration_ms_.load();
                track.bitrate_kbps = bitrate;
                ESP_LOGI(TAG, "Updated track duration: %d ms, bitrate: %d kbps, total duration: %lld ms",
                         track.duration_ms, track.bitrate_kbps, total_duration_ms_.load());
            }
        }
    }
}

void Esp32SdMusic::OnPcmFrame(int64_t play_time_ms, int sample_rate,
                                int channels)
{
    // Base class already tracks play_time_ms, feeds FFT, outputs audio.
    // We just need to keep our state_ updated.
    if (state_.load() == PlayerState::Preparing) {
        state_.store(PlayerState::Playing);
    }
}

bool Esp32SdMusic::OnPlaybackFinishedAndContinue()
{
    ESP_LOGI(TAG, "Playback finished");
    // If stopped explicitly, don't auto-advance
    if (state_.load() == PlayerState::Stopped) {
        return false;
    }
    // Genre playlist auto-advance
    if (!genre_playlist_.empty()) {
        if (PlayNextGenre()) return true;
    }

    // Handle repeat / next track logic
    return HandleNextTrack();
}

void Esp32SdMusic::OnDisplayReady()
{
    /* Display is now handled externally via Application callbacks */
    ESP_LOGD("Esp32SdMusic", "Display ready callback");
}

void Esp32SdMusic::OnPauseStateChanged(bool paused)
{
    if (paused) {
        state_.store(PlayerState::Paused);
    } else {
        state_.store(PlayerState::Playing);
    }
}

AudioDecoderType Esp32SdMusic::DetectFileFormat(const std::string& path) const
{
    if (HasExtension(path, ".mp3"))                        return AudioDecoderType::MP3;
    if (HasExtension(path, ".wav"))                        return AudioDecoderType::WAV;
    if (HasExtension(path, ".aac") || HasExtension(path, ".m4a"))
                                                           return AudioDecoderType::AAC;
    if (HasExtension(path, ".flac"))                       return AudioDecoderType::FLAC;
    return AudioDecoderType::AUTO;  // unsupported
}

size_t Esp32SdMusic::SkipId3Tag(uint8_t* data, size_t size)
{
    if (!data || size < 10) return 0;
    if (memcmp(data, "ID3", 3) != 0) return 0;

    uint32_t tag_sz =
        ((data[6] & 0x7F) << 21) |
        ((data[7] & 0x7F) << 14) |
        ((data[8] & 0x7F) << 7)  |
         (data[9] & 0x7F);

    size_t total = 10 + tag_sz;
    if (total > size) total = size;

    return total;
}

bool Esp32SdMusic::HandleNextTrack()
{
    int next_index = -1;

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);

        if (playlist_.empty()) {
            state_.store(PlayerState::Stopped);
            return false;
        }

        switch (repeat_mode_) {
            case RepeatMode::RepeatOne:
                ESP_LOGI(TAG, "[RepeatOne] → replay same track");
                next_index = current_index_;
                break;

            case RepeatMode::RepeatAll:
                ESP_LOGI(TAG, "[RepeatAll] → next");
                if (shuffle_enabled_ && playlist_.size() > 1) {
                    int new_i;
                    do {
                        new_i = rand() % playlist_.size();
                    } while (new_i == current_index_);
                    next_index = new_i;
                } else {
                    next_index = FindNextTrackIndex(current_index_, +1);
                }
                break;

            case RepeatMode::None:
            default:
                ESP_LOGI(TAG, "[No repeat] → stop");

                state_.store(PlayerState::Stopped);
                next_index = -1;
                break;
        }
    }

    if (next_index >= 0) {
        current_index_ = next_index;
        return Play();
    }
    return false;
}

// ============================================================================
//                         PART 3 / 3
//      DECODER UTIL / STATE / PROGRESS / SONG SUGGESTIONS
// ============================================================================

Esp32SdMusic::TrackProgress Esp32SdMusic::GetProgress() const
{
    TrackProgress p;
    p.position_ms = GetPlayTimeMs();
    p.duration_ms = total_duration_ms_.load();
    return p;
}

Esp32SdMusic::PlayerState Esp32SdMusic::GetState() const
{
    return state_.load();
}

int Esp32SdMusic::GetBitrate() const
{
    std::lock_guard<std::mutex> lock(playlist_mutex_);
    if (current_index_ >= 0 && current_index_ < (int)playlist_.size()) {
        return playlist_[current_index_].bitrate_kbps * 1000;
    }
    return 0;
}

int64_t Esp32SdMusic::GetDurationMs() const
{
    return total_duration_ms_.load();
}

int64_t Esp32SdMusic::GetCurrentPositionMs() const
{
    return GetPlayTimeMs();
}

std::string Esp32SdMusic::GetDurationString() const
{
    return MsToTimeString(total_duration_ms_.load());
}

std::string Esp32SdMusic::GetCurrentTimeString() const
{
    return MsToTimeString(GetPlayTimeMs());
}

// Suggest the next track based on play history
std::vector<Esp32SdMusic::TrackInfo>
Esp32SdMusic::SuggestNextTracks(size_t max_results)
{
    std::vector<TrackInfo> results;
    if (max_results == 0) return results;

    int base_index = -1;
    {
        std::lock_guard<std::mutex> lock(history_mutex_);
        if (!play_history_indices_.empty()) {
            base_index = play_history_indices_.back();
        }
    }

    std::vector<TrackInfo> playlist_copy;
    std::vector<uint32_t> count_copy;
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (playlist_.empty()) return results;
        playlist_copy = playlist_;
        count_copy    = play_count_;
    }

    if (base_index < 0 || base_index >= (int)playlist_copy.size()) {
        size_t limit = std::min(max_results, playlist_copy.size());
        results.assign(playlist_copy.begin(),
                       playlist_copy.begin() + limit);
        return results;
    }

    TrackInfo base = playlist_copy[base_index];

    struct Scored { int index; int score; };
    std::vector<Scored> scored;
    int n = static_cast<int>(playlist_copy.size());
    scored.reserve(std::max(0, n - 1));

    for (int i = 0; i < n; ++i) {
        if (i == base_index) continue;
        uint32_t pc = (i < (int)count_copy.size()) ? count_copy[i] : 0;
        int s = ComputeTrackScoreForBase(base, playlist_copy[i], pc);
        scored.push_back({i, s});
    }

    std::sort(scored.begin(), scored.end(),
              [](const Scored& a, const Scored& b) {
                  if (a.score != b.score) return a.score > b.score;
                  return a.index < b.index;
              });

    size_t limit = std::min(max_results, scored.size());
    results.reserve(limit);
    for (size_t i = 0; i < limit; ++i) {
        results.push_back(playlist_copy[scored[i].index]);
    }

    return results;
}

// Suggest tracks similar to track X
std::vector<Esp32SdMusic::TrackInfo>
Esp32SdMusic::SuggestSimilarTo(const std::string& name_or_path,
                               size_t max_results)
{
    std::vector<TrackInfo> results;
    if (max_results == 0) return results;

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (playlist_.empty()) {
            ESP_LOGW(TAG, "SuggestSimilarTo(): playlist empty — reloading");
        }
    }
    if (playlist_.empty()) {
        if (!LoadPlaylist()) {
            ESP_LOGE(TAG, "SuggestSimilarTo(): cannot load playlist");
            return results;
        }
    }

    int base_index = FindTrackByKeyword(name_or_path);
    if (base_index < 0) {
        return SuggestNextTracks(max_results);
    }

    std::vector<TrackInfo> playlist_copy;
    std::vector<uint32_t> count_copy;
    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        playlist_copy = playlist_;
        count_copy    = play_count_;
    }

    if (playlist_copy.empty() ||
        base_index < 0 ||
        base_index >= (int)playlist_copy.size()) {
        return results;
    }

    TrackInfo base = playlist_copy[base_index];

    struct Scored { int index; int score; };
    std::vector<Scored> scored;
    int n = static_cast<int>(playlist_copy.size());
    scored.reserve(std::max(0, n - 1));

    for (int i = 0; i < n; ++i) {
        if (i == base_index) continue;
        uint32_t pc = (i < (int)count_copy.size()) ? count_copy[i] : 0;
        int s = ComputeTrackScoreForBase(base, playlist_copy[i], pc);
        scored.push_back({i, s});
    }

    std::sort(scored.begin(), scored.end(),
              [](const Scored& a, const Scored& b) {
                  if (a.score != b.score) return a.score > b.score;
                  return a.index < b.index;
              });

    size_t limit = std::min(max_results, scored.size());
    results.reserve(limit);
    for (size_t i = 0; i < limit; ++i) {
        results.push_back(playlist_copy[scored[i].index]);
    }

    return results;
}

// Build a playlist by genre (from ID3v1 / ID3v2)
bool Esp32SdMusic::BuildGenrePlaylist(const std::string& genre)
{
    std::string kw = ToLowerAscii(genre);
    if (kw.empty()) return false;

    std::vector<int> indices;

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        for (int i = 0; i < (int)playlist_.size(); ++i) {
            std::string g = ToLowerAscii(playlist_[i].genre);
            if (!g.empty() && g.find(kw) != std::string::npos) {
                indices.push_back(i);
            }
        }
    }

    if (indices.empty()) {
        ESP_LOGW(TAG, "No tracks found with genre '%s'", genre.c_str());
        return false;
    }

    genre_playlist_   = indices;
    genre_current_key_ = genre;
    genre_current_pos_ = 0;

    ESP_LOGI(TAG, "Genre playlist built for '%s' (%d tracks)",
             genre.c_str(), (int)indices.size());
    return true;
}

bool Esp32SdMusic::PlayGenreIndex(int pos)
{
    if (genre_playlist_.empty())
        return false;

    if (pos < 0 || pos >= (int)genre_playlist_.size())
        return false;

    int track_index = genre_playlist_[pos];

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (track_index < 0 || track_index >= (int)playlist_.size())
            return false;

        current_index_ = track_index;
    }

    genre_current_pos_ = pos;

    ESP_LOGW(TAG, "Play genre-track [%d/%d] → index %d (%s)",
             pos + 1, (int)genre_playlist_.size(),
             track_index,
             playlist_[track_index].name.c_str());

    return Play();
}

bool Esp32SdMusic::PlayNextGenre()
{
    if (genre_playlist_.empty())
        return false;

    int next_pos = genre_current_pos_ + 1;

    if (next_pos >= (int)genre_playlist_.size()) {
        ESP_LOGI(TAG, "End of genre playlist '%s'", genre_current_key_.c_str());
        return false;
    }

    genre_current_pos_ = next_pos;
    int track_index = genre_playlist_[next_pos];

    {
        std::lock_guard<std::mutex> lock(playlist_mutex_);
        if (track_index < 0 || track_index >= (int)playlist_.size())
            return false;

        current_index_ = track_index;
    }

    ESP_LOGW(TAG, "Next genre track → pos=%d → index=%d (%s)",
             next_pos,
             track_index,
             playlist_[track_index].name.c_str());

    return Play();
}
