package com.westly.nbms.core.audit

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuditLogger"

/** "Samsung SM-A135F · Android 14". */
internal fun deviceInfoText(manufacturer: String?, model: String?, release: String?): String {
    val maker = manufacturer?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    // Many phones repeat the maker in the model name ("samsung SM-A135F" vs "Samsung").
    val device = when {
        name.isEmpty() -> maker
        maker.isEmpty() -> name
        name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name"
    }
    val android = release?.trim().orEmpty()
    return "${device.ifEmpty { "Unknown device" }} · Android ${android.ifEmpty { "?" }}"
}

/**
 * Writes one document per call to `businesses/{bid}/audit_logs` with exactly these fields:
 * userId, userName, userRole, action, collection, documentId, previousValue, newValue, deviceInfo,
 * timestamp (server time) and isDeleted = false.
 *
 * It talks to the collection directly (not through [BusinessFirestore.add]) so no extra `createdAt` field is added.
 * It never throws, except that a cancelled coroutine stays cancelled.
 */
@Singleton
class AuditLoggerImpl @Inject constructor(
    private val firestore: BusinessFirestore,
    private val session: SessionManager
) : AuditLogger {

    override suspend fun log(
        action: String,
        collection: String,
        documentId: String,
        previousValue: Map<String, Any?>?,
        newValue: Map<String, Any?>?
    ) {
        try {
            val signedIn = session.state.value as? SessionState.SignedIn ?: return
            val entry: Map<String, Any?> = mapOf(
                "userId" to signedIn.user.uid,
                "userName" to signedIn.user.name,
                "userRole" to signedIn.user.role.key,
                "action" to action,
                "collection" to collection,
                "documentId" to documentId,
                "previousValue" to previousValue,
                "newValue" to newValue,
                "deviceInfo" to deviceInfoText(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE),
                "timestamp" to FieldValue.serverTimestamp(),
                "isDeleted" to false
            )
            firestore.collection("audit_logs").add(entry).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not write audit log \"$action\": ${e.javaClass.simpleName}")
        }
    }
}
