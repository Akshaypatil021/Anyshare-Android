package com.example.anyshare.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Random

/**
 * Manages device nickname, settings, theme, language, and storage configuration.
 */
class DeviceConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("anyshare_device_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DEVICE_NICKNAME = "KEY_DEVICE_NICKNAME"
        private const val KEY_DEVICE_TAG = "KEY_DEVICE_TAG"
        private const val KEY_QUICK_SAVE_MODE = "KEY_QUICK_SAVE_MODE"

        // Settings Constants
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        private const val KEY_THEME = "KEY_THEME_MODE"
        private const val KEY_LANGUAGE = "KEY_LANGUAGE_CODE"
        private const val KEY_ANIMATIONS = "KEY_ANIMATIONS_ENABLED"

        private const val KEY_QUICK_SAVE = "KEY_QUICK_SAVE_ENABLED"
        private const val KEY_QUICK_SAVE_FAV = "KEY_QUICK_SAVE_FAVOURITES"
        private const val KEY_REQUIRE_PIN = "KEY_REQUIRE_PIN"
        private const val KEY_DESTINATION_URI = "KEY_DESTINATION_URI"
        private const val KEY_DESTINATION_NAME = "KEY_DESTINATION_NAME"
        private const val KEY_SAVE_MEDIA_GALLERY = "KEY_SAVE_MEDIA_GALLERY"
        private const val KEY_AUTO_FINISH = "KEY_AUTO_FINISH"

        const val QUICK_SAVE_OFF = 0
        const val QUICK_SAVE_FAVOURITES = 1
        const val QUICK_SAVE_ON = 2

        private val ADJECTIVES = listOf(
            "Cool", "Swift", "Brave", "Golden", "Gentle", "Clever",
            "Bright", "Sunny", "Lucky", "Happy", "Cosmic", "Mighty"
        )

        private val NOUNS = listOf(
            "Pumpkin", "Tiger", "Falcon", "Koala", "Otter", "Panda",
            "Fox", "Dolphin", "Apple", "Mango", "Sparrow", "Comet"
        )
    }

    /**
     * Get or generate a persistent friendly device nickname (e.g. "Cool Pumpkin").
     */
    fun getDeviceNickname(): String {
        val saved = prefs.getString(KEY_DEVICE_NICKNAME, null)
        if (!saved.isNullOrEmpty()) {
            return saved
        }

        // Generate default nickname
        val random = Random(System.currentTimeMillis() xor Build.MODEL.hashCode().toLong())
        val adj = ADJECTIVES[random.nextInt(ADJECTIVES.size)]
        val noun = NOUNS[random.nextInt(NOUNS.size)]
        val generated = "$adj $noun"
        setDeviceNickname(generated)
        return generated
    }

    fun setDeviceNickname(name: String) {
        prefs.edit().putString(KEY_DEVICE_NICKNAME, name.trim()).apply()
    }

    /**
     * Get or generate a persistent device tag/code (e.g. "#6 #2").
     */
    fun getDeviceTag(): String {
        val saved = prefs.getString(KEY_DEVICE_TAG, null)
        if (!saved.isNullOrEmpty()) {
            return saved
        }

        val random = Random(System.currentTimeMillis() xor 12345L)
        val d1 = random.nextInt(9) + 1
        val d2 = random.nextInt(9) + 1
        val generated = "#$d1 #$d2"
        prefs.edit().putString(KEY_DEVICE_TAG, generated).apply()
        return generated
    }

    /**
     * Theme Preferences: System Default, Dark (OLED), White (Light)
     */
    fun getThemePreference(): Int {
        return prefs.getInt(KEY_THEME, THEME_SYSTEM)
    }

    fun setThemePreference(mode: Int) {
        prefs.edit().putInt(KEY_THEME, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int = getThemePreference()) {
        when (mode) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * Language Preferences: System (""), English ("en"), Hindi ("hi")
     */
    fun getLanguagePreference(): String {
        return prefs.getString(KEY_LANGUAGE, "") ?: ""
    }

    fun setLanguagePreference(languageCode: String) {
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
        applyLanguage(languageCode)
    }

    fun applyLanguage(languageCode: String = getLanguagePreference()) {
        val localeList = if (languageCode.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Animations toggle (ON/OFF). If false, animations like rotating rings are disabled.
     */
    fun isAnimationsEnabled(): Boolean {
        return prefs.getBoolean(KEY_ANIMATIONS, true)
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANIMATIONS, enabled).apply()
    }

    /**
     * Receive: Quick Save
     */
    fun isQuickSaveEnabled(): Boolean {
        return prefs.getBoolean(KEY_QUICK_SAVE, false)
    }

    fun setQuickSaveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_SAVE, enabled).apply()
    }

    /**
     * Receive: Quick Save for Favourites
     */
    fun isQuickSaveFavouritesEnabled(): Boolean {
        return prefs.getBoolean(KEY_QUICK_SAVE_FAV, false)
    }

    fun setQuickSaveFavouritesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_SAVE_FAV, enabled).apply()
    }

    /**
     * Receive: Require PIN
     */
    fun isRequirePinEnabled(): Boolean {
        return prefs.getBoolean(KEY_REQUIRE_PIN, false)
    }

    fun setRequirePinEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_PIN, enabled).apply()
    }

    /**
     * Receive: Custom Save Destination Directory
     */
    fun getDestinationName(): String {
        return prefs.getString(KEY_DESTINATION_NAME, "(Downloads)") ?: "(Downloads)"
    }

    fun getDestinationUri(): String? {
        return prefs.getString(KEY_DESTINATION_URI, null)
    }

    fun setDestination(name: String, uriString: String?) {
        prefs.edit()
            .putString(KEY_DESTINATION_NAME, name)
            .putString(KEY_DESTINATION_URI, uriString)
            .apply()
    }

    /**
     * Receive: Save media to gallery
     */
    fun isSaveMediaToGalleryEnabled(): Boolean {
        return prefs.getBoolean(KEY_SAVE_MEDIA_GALLERY, true)
    }

    fun setSaveMediaToGalleryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_MEDIA_GALLERY, enabled).apply()
    }

    /**
     * Receive: Auto Finish transfer screen when complete
     */
    fun isAutoFinishEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_FINISH, false)
    }

    fun setAutoFinishEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FINISH, enabled).apply()
    }

    /**
     * Legacy Quick Save mode (0: Off, 1: Favourites, 2: On).
     */
    fun getQuickSaveMode(): Int {
        return prefs.getInt(KEY_QUICK_SAVE_MODE, QUICK_SAVE_OFF)
    }

    fun setQuickSaveMode(mode: Int) {
        prefs.edit().putInt(KEY_QUICK_SAVE_MODE, mode).apply()
    }
}
