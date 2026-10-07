package com.westly.nbms.core.design

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The person's light / dark / system choice for this phone (Appendix A.6.2). Default is [ThemeMode.System]. */
interface ThemePreferenceStore {
    val mode: Flow<ThemeMode>
    suspend fun set(mode: ThemeMode)
}

private val Context.nbmsThemeDataStore by preferencesDataStore(name = "nbms_theme_prefs")
private val THEME_KEY = stringPreferencesKey("nbms_theme")

@Singleton
class DataStoreThemePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ThemePreferenceStore {

    override val mode: Flow<ThemeMode> = context.nbmsThemeDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs: Preferences -> themeModeFromKey(prefs[THEME_KEY]) }

    override suspend fun set(mode: ThemeMode) {
        context.nbmsThemeDataStore.edit { it[THEME_KEY] = mode.name.lowercase() }
    }
}

internal fun themeModeFromKey(key: String?): ThemeMode = when (key) {
    "light" -> ThemeMode.Light
    "dark" -> ThemeMode.Dark
    else -> ThemeMode.System
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ThemeModule {
    @Binds
    @Singleton
    abstract fun bindThemePreferenceStore(impl: DataStoreThemePreferenceStore): ThemePreferenceStore
}
