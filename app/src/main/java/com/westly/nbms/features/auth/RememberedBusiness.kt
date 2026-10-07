package com.westly.nbms.features.auth

import com.westly.nbms.core.session.SecureStore
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and writes the business code remembered on this device (SecureStore key `last_business_code`). */
@Singleton
class RememberedBusiness @Inject constructor(private val store: SecureStore) {

    /** The remembered code in capitals, or null when none is saved. */
    fun get(): String? =
        store.getString(SecureStore.KEY_LAST_BUSINESS_CODE)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    fun save(code: String) {
        val clean = code.trim().uppercase()
        if (clean.isNotEmpty()) store.putString(SecureStore.KEY_LAST_BUSINESS_CODE, clean)
    }
}
