package com.westly.nbms.core.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small encrypted key/value store. If the device's secure storage cannot be opened
 * (rare keystore problem) it falls back to a plain private file so the app never crashes.
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    fun getString(key: String): String? = try { prefs.getString(key, null) } catch (e: Exception) { null }

    fun putString(key: String, value: String) {
        try { prefs.edit().putString(key, value).apply() } catch (_: Exception) { }
    }

    fun remove(key: String) {
        try { prefs.edit().remove(key).apply() } catch (_: Exception) { }
    }

    fun clear() {
        try { prefs.edit().clear().apply() } catch (_: Exception) { }
    }

    /** Removes every key except the ones listed (used on sign-out to keep `last_business_code`). */
    fun clearExcept(vararg keep: String) {
        try {
            val saved = keep.associateWith { prefs.getString(it, null) }
            prefs.edit().clear().apply()
            val e = prefs.edit()
            saved.forEach { (k, v) -> if (v != null) e.putString(k, v) }
            e.apply()
        } catch (_: Exception) { }
    }

    companion object {
        private const val FILE = "nbms_secure_store"
        private const val FILE_FALLBACK = "nbms_secure_store_fallback"
        const val KEY_SUPABASE_SESSION = "supabase_session"
        const val KEY_LAST_BUSINESS_CODE = "last_business_code"
    }
}
