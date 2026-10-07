package com.westly.nbms.features.device

import android.content.Context
import com.westly.nbms.core.session.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A random id that names this phone, kept in [SecureStore] under the key "device_id".
 * Signing out clears the secure store, so the id is also copied to a small private file and restored from it.
 * (The id is not a secret; it is useless without a signed-in account.)
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    private val store: SecureStore,
    @ApplicationContext private val context: Context
) {
    private val backup by lazy { context.getSharedPreferences(BACKUP_FILE, Context.MODE_PRIVATE) }

    @Synchronized
    fun get(): String {
        val saved = store.getString(KEY)?.takeIf { it.isNotBlank() }
        if (saved != null) {
            if (backup.getString(KEY, null) != saved) backup.edit().putString(KEY, saved).apply()
            return saved
        }
        val restored = backup.getString(KEY, null)?.takeIf { it.isNotBlank() }
        val id = restored ?: UUID.randomUUID().toString()
        store.putString(KEY, id)
        if (restored == null) backup.edit().putString(KEY, id).apply()
        return id
    }

    companion object {
        const val KEY = "device_id"
        private const val BACKUP_FILE = "nbms_device_identity"
    }
}
