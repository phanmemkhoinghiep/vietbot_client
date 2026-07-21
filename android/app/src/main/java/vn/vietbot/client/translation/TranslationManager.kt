package vn.vietbot.client.translation

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.data.SpeakerOutput

/**
 * TranslationManager - Handles offline TTS for translation playback.
 *
 * Server sends messages with format: [TRANSLATION][en-US]<translated_text>
 * Client either:
 *  - Plays server audio stream (OpusStreamPlayer) — default, no TTS used here
 *  - Or plays offline Android TTS for the translated text (en-US @ 1.2x speed)
 *
 * The user's choice between server audio vs offline TTS is stored in
 * SettingsRepository.useOfflineTts. This manager ONLY runs when offline TTS is selected.
 *
 * Speaker routing respects SettingsRepository.speakerOutput:
 *  - BUILTIN_SPEAKER ("Loa Bluetooth") → BT SCO if connected, else phone loudspeaker
 *  - EARPIECE ("Loa Điện thoại") → force phone receiver (earpiece)
 *
 * Queue-based: segments are spoken sequentially via Android's built-in TTS.
 */
class TranslationManager(
    private val context: Context,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "TranslationManager"
        private const val SPEECH_RATE = 1.2f

        // Pattern to parse translation messages
        // Format: [TRANSLATION][xx-XX]<translated_text>
        val TRANSLATION_PATTERN = Regex("^\\[TRANSLATION\\]\\[([a-zA-Z-]+)\\](.+)$")
    }

    // Translation segment data class
    data class TranslationSegment(
        val id: String = UUID.randomUUID().toString(),
        val langCode: String,
        val text: String,
        val isPlayed: Boolean = false
    )

    // Queue of translation segments waiting to be spoken
    private val _translationQueue = MutableStateFlow<List<TranslationSegment>>(emptyList())
    val translationQueue: StateFlow<List<TranslationSegment>> = _translationQueue.asStateFlow()

    // Currently speaking segment ID
    private val _currentSpeakingId = MutableStateFlow<String?>(null)
    val currentSpeakingId: StateFlow<String?> = _currentSpeakingId.asStateFlow()

    // Whether translation mode is active (TTS in use)
    private val _isTranslationMode = MutableStateFlow(false)
    val isTranslationMode: StateFlow<Boolean> = _isTranslationMode.asStateFlow()

    // TextToSpeech instance
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    // Pinned voice for the current translation session
    private var pinnedVoice: Voice? = null

    // Callback when a segment starts playing
    var onSegmentStart: ((String) -> Unit)? = null

    // Callback when all segments are finished
    var onAllFinished: (() -> Unit)? = null

    init {
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                Log.i(TAG, "TTS initialized successfully")

                try {
                    tts?.setEngineByPackageName("com.google.android.tts")
                    tts?.setSpeechRate(SPEECH_RATE)
                    tts?.setPitch(1.0f)
                } catch (e: Exception) {
                    Log.w(TAG, "TTS config failed: ${e.message}")
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS done: $utteranceId")
                        utteranceId?.let { markSegmentAsPlayed(it) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        utteranceId?.let { markSegmentAsPlayed(it) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
                        utteranceId?.let { markSegmentAsPlayed(it) }
                    }
                })
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    /**
     * Parse translation message: [TRANSLATION][xx-XX]<translated_text>
     */
    fun parseTranslationMessage(text: String): TranslationSegment? {
        val match = TRANSLATION_PATTERN.find(text) ?: return null
        return TranslationSegment(
            langCode = match.groupValues[1].trim(),
            text = match.groupValues[2].trim()
        )
    }

    /**
     * Check if text is a translation message
     */
    fun isTranslationMessage(text: String): Boolean {
        return text.startsWith("[TRANSLATION]")
    }

    /**
     * Add translation segment to queue and start speaking if not already speaking
     */
    fun addTranslation(text: String) {
        val segment = parseTranslationMessage(text) ?: return

        Log.i(TAG, "Adding translation: lang=${segment.langCode}, text=${segment.text}")

        _isTranslationMode.value = true

        val currentQueue = _translationQueue.value.toMutableList()
        currentQueue.add(segment)
        _translationQueue.value = currentQueue

        if (_currentSpeakingId.value == null) {
            speakNextSegment()
        }
    }

    private fun speakNextSegment() {
        val queue = _translationQueue.value
        val nextSegment = queue.firstOrNull { !it.isPlayed }

        if (nextSegment == null) {
            _currentSpeakingId.value = null
            onAllFinished?.invoke()
            Log.i(TAG, "All translation segments finished")
            return
        }

        speakSegment(nextSegment)
    }

    /**
     * Route TTS audio to the correct output device based on SettingsRepository.speakerOutput.
     *
     * BUILTIN_SPEAKER ("Loa Bluetooth"):
     *   - If BT is connected → start Bluetooth SCO so TTS audio goes to BT headset/speaker
     *   - If no BT → enable speakerphone (phone loudspeaker)
     *
     * EARPIECE ("Loa Điện thoại"):
     *   - Disable speakerphone → forces audio to earpiece/receiver
     *   - Stop BT SCO if it was started
     *
     * Note: Android TTS routes audio through AudioManager stream settings.
     * setSpeakerphoneOn() affects STREAM_VOICE_CALL (which TTS uses via KEY_PARAM_STREAM).
     * BT SCO routes both voice call and TTS audio to the Bluetooth headset.
     */
    private fun applySpeakerRouting() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (settings.speakerOutput) {
            SpeakerOutput.BUILTIN_SPEAKER -> {
                // Check if any Bluetooth audio device is connected
                val hasBtAudio = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (hasBtAudio) {
                    // Route to Bluetooth — start SCO for TTS audio
                    if (!am.isBluetoothScoOn) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                        Log.i(TAG, "BT connected — started Bluetooth SCO for TTS")
                    }
                    am.isSpeakerphoneOn = false
                } else {
                    // No BT — use phone loudspeaker
                    am.isSpeakerphoneOn = true
                    Log.i(TAG, "No BT — TTS → phone loudspeaker")
                }
            }
            SpeakerOutput.EARPIECE -> {
                // Force earpiece (phone receiver)
                am.isSpeakerphoneOn = false
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                Log.i(TAG, "TTS → earpiece (phone receiver)")
            }
        }
    }

    private fun speakSegment(segment: TranslationSegment) {
        if (!isTtsInitialized) {
            Log.e(TAG, "TTS not initialized")
            return
        }

        _currentSpeakingId.value = segment.id
        onSegmentStart?.invoke(segment.id)

        // Apply speaker routing based on user's speakerOutput setting
        applySpeakerRouting()

        if (pinnedVoice == null) {
            val locale = getLocaleFromCode(segment.langCode)
            val langResult = tts?.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language not supported: ${segment.langCode}, falling back to default")
                tts?.language = Locale.getDefault()
            }
            try {
                val voices = tts?.voices ?: return
                val matchingVoices = voices.filter {
                    it.locale.language == locale.language ||
                    it.locale.toLanguageTag().startsWith(locale.language)
                }
                pinnedVoice = matchingVoices.firstOrNull { !it.isNetworkConnectionRequired }
                    ?: matchingVoices.firstOrNull()
                    ?: voices.firstOrNull { !it.isNetworkConnectionRequired }
                    ?: voices.firstOrNull()
                pinnedVoice?.let { tts?.voice = it }
                Log.i(TAG, "Pinned TTS voice: ${pinnedVoice?.name} (lang=${pinnedVoice?.locale})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pin voice: ${e.message}")
            }
        }

        val params = android.os.Bundle().apply {
            putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
        }
        tts?.speak(segment.text, TextToSpeech.QUEUE_FLUSH, params, segment.id)
        Log.i(TAG, "Speaking [${segment.langCode} @ ${SPEECH_RATE}x]: ${segment.text}")
    }

    private fun markSegmentAsPlayed(utteranceId: String) {
        val currentQueue = _translationQueue.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == utteranceId }

        if (index >= 0) {
            currentQueue[index] = currentQueue[index].copy(isPlayed = true)
            _translationQueue.value = currentQueue
        }

        speakNextSegment()
    }

    private fun getLocaleFromCode(langCode: String): Locale {
        return when (langCode) {
            "vi-VN" -> Locale("vi", "VN")
            "vi" -> Locale("vi", "VN")
            "en-US" -> Locale.US
            "en-GB" -> Locale.UK
            "en" -> Locale.US
            "ja-JP" -> Locale.JAPANESE
            "ja" -> Locale.JAPANESE
            "ko-KR" -> Locale.KOREAN
            "ko" -> Locale.KOREAN
            "zh-CN" -> Locale.CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            "zh" -> Locale.CHINESE
            "fr-FR" -> Locale.FRENCH
            "fr" -> Locale.FRENCH
            "de-DE" -> Locale.GERMAN
            "de" -> Locale.GERMAN
            "es-ES" -> Locale("es", "ES")
            "es" -> Locale("es", "ES")
            "th-TH" -> Locale("th", "TH")
            "th" -> Locale("th", "TH")
            "ru-RU" -> Locale("ru", "RU")
            "ru" -> Locale("ru", "RU")
            "ar-XA" -> Locale("ar", "XA")
            "ar" -> Locale("ar", "XA")
            "pt-BR" -> Locale("pt", "BR")
            "pt" -> Locale("pt", "BR")
            "it-IT" -> Locale.ITALIAN
            "it" -> Locale.ITALIAN
            "nl-NL" -> Locale("nl", "NL")
            "nl" -> Locale("nl", "NL")
            "pl-PL" -> Locale("pl", "PL")
            "pl" -> Locale("pl", "PL")
            "tr-TR" -> Locale("tr", "TR")
            "tr" -> Locale("tr", "TR")
            "hi-IN" -> Locale("hi", "IN")
            "hi" -> Locale("hi", "IN")
            "ms-MY" -> Locale("ms", "MY")
            "ms" -> Locale("ms", "MY")
            "id-ID" -> Locale("id", "ID")
            "id" -> Locale("id", "ID")
            else -> Locale.getDefault()
        }
    }

    fun completeBatch() {
        tts?.stop()
        _translationQueue.value = emptyList()
        _currentSpeakingId.value = null
        Log.i(TAG, "Translation batch completed (mode still active, voice pinned)")
    }

    fun clearQueue() {
        tts?.stop()
        // Clean up BT SCO if we started it
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        } catch (_: Exception) {}
        _translationQueue.value = emptyList()
        _currentSpeakingId.value = null
        _isTranslationMode.value = false
        pinnedVoice = null
    }

    fun stop() {
        clearQueue()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        pinnedVoice = null
        isTtsInitialized = false
    }

    fun isLanguageAvailable(langCode: String): Boolean {
        if (!isTtsInitialized) return false

        val locale = getLocaleFromCode(langCode)
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

        return result >= TextToSpeech.LANG_AVAILABLE
    }
}