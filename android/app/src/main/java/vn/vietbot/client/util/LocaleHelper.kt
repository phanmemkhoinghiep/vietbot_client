package vn.vietbot.client.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Helper to apply a per-app locale override (en/vi) at runtime.
 * Android 13+ supports per-app language preferences natively via
 * LocaleManager.setApplicationLocales(). For older versions, we wrap the base context.
 */
object LocaleHelper {

    const val LANG_ENGLISH = "en"
    const val LANG_VIETNAMESE = "vi"
    const val LANG_SYSTEM = "" // follow device

    private const val PREFS_NAME = "vietbot_settings"
    private const val KEY_APP_LANGUAGE = "app_language"

    /**
     * Read the persisted language code. Defaults to device locale, preferring Vietnamese.
     */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_APP_LANGUAGE, null)
        if (saved != null) return saved
        // First launch: follow device, but default to VI for Vietnamese-region devices
        val device = Locale.getDefault().language
        return if (device == LANG_VIETNAMESE) LANG_VIETNAMESE else LANG_ENGLISH
    }

    /**
     * Persist a language choice. Pass LANG_SYSTEM to follow device locale.
     * NOTE: Does NOT call LocaleManager.setApplicationLocales() — MIUI blocks it.
     * Locale switch happens only via attachBaseContext + Activity.recreate().
     */
    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_APP_LANGUAGE, langCode).apply()
        // No runtime LocaleManager call — recreate() + attachBaseContext() handles it.
    }

    /**
     * Wrap a base context with the persisted locale. Attach in Activity.attachBaseContext().
     */
    fun wrap(base: Context): Context {
        val lang = getLanguage(base)
        if (lang.isBlank()) return base
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
