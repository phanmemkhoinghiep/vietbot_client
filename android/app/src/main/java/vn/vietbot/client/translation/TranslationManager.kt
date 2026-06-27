package vn.vietbot.client.translation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * TranslationManager - Handles offline TTS for translation playback
 *
 * When server sends translation messages with [TRANSLATION][xx-XX] prefix,
 * this manager parses them and uses Android's built-in TTS to speak.
 *
 * It maintains a queue of translation segments and highlights them as they're spoken.
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"

        // Pattern to parse translation messages
        // Format: [TRANSLATION][xx-XX]<translated_text>
        val TRANSLATION_PATTERN = Regex("^\\[TRANSLATION\\]\\[(\\w{2}-\\w{2})\\](.+)$")
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

    // Whether translation mode is active
    private val _isTranslationMode = MutableStateFlow(false)
    val isTranslationMode: StateFlow<Boolean> = _isTranslationMode.asStateFlow()

    // TextToSpeech instance
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    // Pinned voice for the current translation session — all segments
    // share the same voice (fixes random voice per segment issue).
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

                // Set up utterance progress listener
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
     * Parse translation message and return TranslationData or null
     */
    fun parseTranslationMessage(text: String): TranslationSegment? {
        val match = TRANSLATION_PATTERN.find(text) ?: return null

        val langCode = match.groupValues[1]  // e.g., "vi-VN"
        val translatedText = match.groupValues[2].trim()  // e.g., "Xin chào bạn khỏe không?"

        return TranslationSegment(
            langCode = langCode,
            text = translatedText
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

        // Mark translation mode as active
        _isTranslationMode.value = true

        // Add to queue
        val currentQueue = _translationQueue.value.toMutableList()
        currentQueue.add(segment)
        _translationQueue.value = currentQueue

        // Start speaking if not already
        if (_currentSpeakingId.value == null) {
            speakNextSegment()
        }
    }

    /**
     * Speak the next segment in the queue
     */
    private fun speakNextSegment() {
        val queue = _translationQueue.value
        val nextSegment = queue.firstOrNull { !it.isPlayed }

        if (nextSegment == null) {
            // All done
            _currentSpeakingId.value = null
            onAllFinished?.invoke()
            Log.i(TAG, "All translation segments finished")
            return
        }

        speakSegment(nextSegment)
    }

    /**
     * Speak a specific segment.
     *
     * Pin the TTS voice on the FIRST segment so all subsequent segments in
     * this session share the same voice. Without pinning, Android picks
     * a voice per language each call which can yield a different voice for
     * each segment (mismatched pitch/timbre/character).
     */
    private fun speakSegment(segment: TranslationSegment) {
        if (!isTtsInitialized) {
            Log.e(TAG, "TTS not initialized")
            return
        }

        _currentSpeakingId.value = segment.id
        onSegmentStart?.invoke(segment.id)

        if (pinnedVoice == null) {
            val locale = getLocaleFromCode(segment.langCode)
            val langResult = tts?.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language not supported: ${segment.langCode}, falling back to default")
                tts?.language = Locale.getDefault()
            }
            // Pick the first available voice for this language and pin it
            // so all future segments use the SAME voice. This is the fix
            // for the "different voice every segment" issue.
            try {
                pinnedVoice = tts?.voice
                    ?: run {
                        val voices = tts?.voices?.filter {
                            it.locale.language == locale.language ||
                            it.locale.toLanguageTag().startsWith(locale.language)
                        }
                        voices?.firstOrNull { !it.isNetworkConnectionRequired } ?: voices?.firstOrNull()
                    }
                pinnedVoice?.let { tts?.voice = it }
                Log.i(TAG, "Pinned TTS voice: ${pinnedVoice?.name} (lang=${pinnedVoice?.locale})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pin voice: ${e.message}")
            }
        }

        // Speak with utterance ID = segment ID
        tts?.speak(segment.text, TextToSpeech.QUEUE_FLUSH, null, segment.id)
        Log.i(TAG, "Speaking: ${segment.text}")
    }

    /**
     * Mark a segment as played and move to next
     */
    private fun markSegmentAsPlayed(utteranceId: String) {
        val currentQueue = _translationQueue.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == utteranceId }

        if (index >= 0) {
            currentQueue[index] = currentQueue[index].copy(isPlayed = true)
            _translationQueue.value = currentQueue
        }

        // Speak next segment
        speakNextSegment()
    }

    /**
     * Convert language code to Locale
     */
    private fun getLocaleFromCode(langCode: String): Locale {
        return when (langCode) {
            "vi-VN" -> Locale("vi", "VN")
            "en-US" -> Locale.US
            "en-GB" -> Locale.UK
            "ja-JP" -> Locale.JAPANESE
            "ko-KR" -> Locale.KOREAN
            "zh-CN" -> Locale.CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            "fr-FR" -> Locale.FRENCH
            "de-DE" -> Locale.GERMAN
            "es-ES" -> Locale("es", "ES")
            "th-TH" -> Locale("th", "TH")
            "ru-RU" -> Locale("ru", "RU")
            "ar-XA" -> Locale("ar", "XA")
            "pt-BR" -> Locale("pt", "BR")
            "it-IT" -> Locale.ITALIAN
            "nl-NL" -> Locale("nl", "NL")
            "pl-PL" -> Locale("pl", "PL")
            "tr-TR" -> Locale("tr", "TR")
            "hi-IN" -> Locale("hi", "IN")
            "ms-MY" -> Locale("ms", "MY")
            "id-ID" -> Locale("id", "ID")
            else -> Locale.getDefault()
        }
    }

    /**
     * Clear all translation segments
     */
    fun clearQueue() {
        tts?.stop()
        _translationQueue.value = emptyList()
        _currentSpeakingId.value = null
        _isTranslationMode.value = false
        pinnedVoice = null
    }

    /**
     * Stop current TTS and clear queue
     */
    fun stop() {
        clearQueue()
    }

    /**
     * Release TTS resources
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        pinnedVoice = null
        isTtsInitialized = false
    }

    /**
     * Check if TTS is available for a language
     */
    fun isLanguageAvailable(langCode: String): Boolean {
        if (!isTtsInitialized) return false

        val locale = getLocaleFromCode(langCode)
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

        return result >= TextToSpeech.LANG_AVAILABLE
    }
}