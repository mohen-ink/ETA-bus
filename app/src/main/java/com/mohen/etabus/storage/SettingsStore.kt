package com.mohen.etabus.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.mohen.etabus.model.AppSettings
import com.mohen.etabus.model.DigitFont
import com.mohen.etabus.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "etabus_settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val gson = Gson()
    private val stringsType = object : TypeToken<List<String>>() {}.type

    val settings: Flow<AppSettings> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map(::decode).distinctUntilChanged()

    suspend fun update(value: AppSettings) = transform { value }

    suspend fun transform(change: (AppSettings) -> AppSettings) {
        store.edit { preferences ->
            val value = change(decode(preferences))
            preferences[CITY] = value.cityCode
            preferences[RADIUS] = value.radius
            preferences[REFRESH] = value.refreshSeconds
            preferences[THEME] = value.theme.name
            preferences[DIGITS] = value.digitFont.name
            preferences[PRIVACY] = value.privacyAccepted
            preferences[SEARCHES] = gson.toJson(value.searchHistory.clean(10))
            preferences[RECENT] = gson.toJson(value.recentCities.clean(6))
        }
    }

    suspend fun addSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        transform { it.copy(searchHistory = (listOf(trimmed) + it.searchHistory).clean(10)) }
    }

    suspend fun useCity(code: String) {
        val trimmed = code.trim()
        require(trimmed.isNotEmpty()) { "城市代码不能为空" }
        transform { it.copy(cityCode = trimmed, recentCities = (listOf(trimmed) + it.recentCities).clean(6)) }
    }

    suspend fun clearSearch() = transform { it.copy(searchHistory = emptyList()) }
    suspend fun setRadius(meters: Int) = transform { it.copy(radius = meters) }
    suspend fun setRefreshSeconds(seconds: Int) = transform { it.copy(refreshSeconds = seconds) }
    suspend fun setTheme(theme: ThemeMode) = transform { it.copy(theme = theme) }
    suspend fun setDigitFont(font: DigitFont) = transform { it.copy(digitFont = font) }
    suspend fun setPrivacyAccepted(accepted: Boolean) = transform { it.copy(privacyAccepted = accepted) }

    private fun decode(preferences: Preferences): AppSettings = AppSettings(
        cityCode = preferences[CITY] ?: "0719",
        radius = preferences[RADIUS] ?: 1000,
        refreshSeconds = preferences[REFRESH] ?: 30,
        theme = ThemeMode.entries.firstOrNull { it.name == preferences[THEME] } ?: ThemeMode.SYSTEM,
        digitFont = DigitFont.entries.firstOrNull { it.name == preferences[DIGITS] } ?: DigitFont.MONO,
        privacyAccepted = preferences[PRIVACY] ?: false,
        searchHistory = readList(preferences[SEARCHES]).clean(10),
        recentCities = readList(preferences[RECENT]).clean(6)
    )

    private fun readList(json: String?): List<String> = try {
        if (json == null) emptyList() else gson.fromJson<List<String>>(json, stringsType) ?: emptyList()
    } catch (_: JsonParseException) {
        emptyList()
    }

    private fun List<String>.clean(limit: Int): List<String> = map(String::trim).filter(String::isNotEmpty).distinct().take(limit)

    private companion object {
        val CITY = stringPreferencesKey("city_code")
        val RADIUS = intPreferencesKey("nearby_radius")
        val REFRESH = intPreferencesKey("refresh_seconds")
        val THEME = stringPreferencesKey("theme")
        val DIGITS = stringPreferencesKey("digit_font")
        val PRIVACY = booleanPreferencesKey("privacy_accepted")
        val SEARCHES = stringPreferencesKey("search_history")
        val RECENT = stringPreferencesKey("recent_cities")
    }
}
