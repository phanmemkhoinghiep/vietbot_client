#ifndef ESP32_SD_MUSIC_H
#define ESP32_SD_MUSIC_H

/**
 * @file esp32_sd_music.h
 * @brief SD card music player -- inherits AudioStreamPlayer.
 *
 * Responsibilities:
 *   - Playlist management (playlist.json, scanning, genres, suggestions)
 *   - Track metadata (ID3v1 / ID3v2)
 *   - File-based audio streaming via AudioStreamPlayer base class
 *   - Pause / resume / shuffle / repeat
 *   - WAV / MP3 / AAC / FLAC playback from SD card
 */

#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "audio_stream_player.h"

class Esp32SdMusic : public AudioStreamPlayer {
public:
    // ============================================================
    // 1) ENUM -- State Machine
    // ============================================================
    enum class PlayerState {
        Stopped = 0,
        Preparing,
        Playing,
        Paused,
        Error
    };

    // ============================================================
    // 2) ENUM -- Repeat modes
    // ============================================================
    enum class RepeatMode {
        None = 0,
        RepeatOne,
        RepeatAll
    };

    // ============================================================
    // 3) Struct TrackInfo
    // ============================================================
    struct TrackInfo {
        std::string name;
        std::string path;

        std::string title;
        std::string artist;
        std::string album;
        std::string genre;
        std::string comment;
        std::string year;
        int         track_number = 0;

        int    duration_ms  = 0;
        int    bitrate_kbps = 0;
        size_t file_size    = 0;

        uint32_t    cover_size = 0;
        std::string cover_mime;
    };

    // ============================================================
    // 4) Struct Progress
    // ============================================================
    struct TrackProgress {
        int64_t position_ms = 0;
        int64_t duration_ms = 0;
    };

public:
    // ============================================================
    // 5) Constructor / Destructor
    // ============================================================
    Esp32SdMusic();
    ~Esp32SdMusic();

    void Initialize(class SdCard* sd_card, AudioCodec *codec);

    // ============================================================
    // 6) Playlist / Directory API
    // ============================================================

    /** Load (or rebuild) playlist from playlist.json on SD card */
    bool LoadPlaylist();

    /** Get total number of tracks in current playlist */
    size_t GetTotalTracks() const;

    /** Get a copy of the full playlist */
    std::vector<TrackInfo> GetPlaylist() const;

    /** Get paginated playlist (0-based page_index) */
    std::vector<TrackInfo> GetPlaylistPage(size_t page_index,
                                           size_t page_size = 10) const;

    /** Get track info by index */
    TrackInfo GetTrackInfo(int index) const;

    /** Get current playlist index (-1 if none) */
    int GetCurrentIndex() const;

    /** Set working directory (relative to SD mount point) and reload playlist */
    bool SetDirectory(const std::string& relative_dir);

    /** Get sub-directories under current root */
    std::vector<std::string> GetDirectories() const;

    /** Search tracks by keyword (name or path) */
    std::vector<TrackInfo> SearchTracks(const std::string& keyword) const;

    /** Get track count in a specific directory */
    size_t GetTrackCountInDir(const std::string& relative_dir);

    /** Get track count in current directory */
    size_t GetTrackCount() const;

    /** Force rescan SD card and rebuild playlist.json */
    bool RebuildPlaylist();

    // ============================================================
    // 7) Playback control API
    // ============================================================

    /**
     * @brief Play current track (resume if paused).
     * @return true if playback started successfully
     */
    bool Play();

    /**
     * @brief Play a specific file by full path.
     * @param file_path  Absolute path, e.g. "/sdcard/Music/song.mp3"
     * @return true if playback started successfully
     */
    bool Play(const std::string& file_path);

    /**
     * @brief Play a track matched by keyword (name or path substring).
     * @param keyword  Search keyword
     * @return true if a matching track was found and playback started
     */
    bool PlayByName(const std::string& keyword);

    /**
     * @brief Play all tracks in a directory.
     * @param relative_dir  Directory relative to SD mount point
     * @return true if directory has tracks and playback started
     */
    bool PlayDirectory(const std::string& relative_dir);

    /** Stop current playback */
    void Stop();

    /** Pause current playback */
    void Pause();

    /** Play next track in playlist */
    bool Next();

    /** Play previous track in playlist */
    bool Prev();

    /** Jump to a specific track by index and start playing */
    bool SetTrack(int index);

    bool IsPlaying() const override;

    // ============================================================
    // 8) Playback settings
    // ============================================================

    void SetShuffleMode(bool enabled);
    void SetRepeatMode(RepeatMode mode);

    // ============================================================
    // 9) State queries
    // ============================================================

    PlayerState GetState() const;
    TrackProgress GetProgress() const;

    int64_t GetDurationMs() const;
    int64_t GetCurrentPositionMs() const;
    int GetBitrate() const;
    std::string GetDurationString() const;
    std::string GetCurrentTimeString() const;

    std::string GetCurrentTrack() const;
    std::string GetCurrentTrackPath() const;

    // ============================================================
    // 10) Suggestions
    // ============================================================

    std::vector<TrackInfo> SuggestNextTracks(size_t max_results = 5);
    std::vector<TrackInfo> SuggestSimilarTo(const std::string& name_or_path,
                                            size_t max_results = 5);

    // ============================================================
    // 11) Genre playlists
    // ============================================================

    bool BuildGenrePlaylist(const std::string& genre);
    bool PlayGenreIndex(int pos);
    bool PlayNextGenre();
    std::vector<std::string> GetGenres() const;

protected:
    // ============================================================
    // AudioStreamPlayer overrides
    // ============================================================
    void SourceDataLoop(const std::string& source) override;
    void OnStreamInfoReady(int sample_rate, int bits_per_sample,
                           int channels, int bitrate, int frame_size) override;
    void OnPcmFrame(int64_t play_time_ms, int sample_rate,
                    int channels) override;
    bool OnPlaybackFinishedAndContinue() override;
    void OnDisplayReady() override;
    void OnPauseStateChanged(bool paused) override;

private:
    // ============================================================
    // Playlist helpers
    // ============================================================
    void ScanDirectoryRecursive(const std::string& dir,
                                std::vector<TrackInfo>& out);
    int FindNextTrackIndex(int start, int direction);
    bool ResolveDirectoryRelative(const std::string& relative_dir,
                                  std::string& out_full);
    int FindTrackByKeyword(const std::string& keyword) const;

    bool LoadPlaylistFromFile(const std::string& playlist_path,
                              std::vector<TrackInfo>& out) const;
    bool SavePlaylistToFile(const std::string& playlist_path,
                            const std::vector<TrackInfo>& list) const;

    std::string ResolveLongName(const std::string& path);
    std::string ResolveCaseInsensitiveDir(const std::string& path);

    // ============================================================
    // Audio format detection
    // ============================================================
    AudioDecoderType DetectFileFormat(const std::string& path) const;

    // ID3 / WAV helpers
    size_t SkipId3Tag(uint8_t* data, size_t size);

    // ============================================================
    // History / suggestions
    // ============================================================
    void RecordPlayHistory(int index);
    bool HandleNextTrack();

private:
    SdCard* sd_card_;

    // Playlist
    std::string root_directory_;
    std::vector<TrackInfo> playlist_;
    mutable std::mutex playlist_mutex_;
    int current_index_ = -1;
    std::vector<uint32_t> play_count_;

    // State
    std::atomic<PlayerState> state_;

    // Playback options
    bool shuffle_enabled_;
    RepeatMode repeat_mode_;

    // Progress tracking
    std::atomic<int64_t> total_duration_ms_;

    // Genre playlists
    std::vector<int> genre_playlist_;
    int genre_current_pos_ = -1;
    std::string genre_current_key_;

    // History / suggestions
    mutable std::mutex history_mutex_;
    std::vector<int> play_history_indices_;
};

#endif // ESP32_SD_MUSIC_H
