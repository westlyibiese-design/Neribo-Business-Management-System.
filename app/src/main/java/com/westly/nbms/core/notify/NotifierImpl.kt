package com.westly.nbms.core.notify

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Notifier"

/** Where a tapped push opens when the notification has no link of its own. */
internal const val DEFAULT_PUSH_LINK = "/admin/dashboard"

/** The Firestore document written for one notification (everything except the server timestamp, added by the caller). */
internal fun notificationFields(
    type: String,
    title: String,
    message: String,
    severity: String,
    link: String?,
    forRoles: List<Role>,
    forUserIds: List<String>,
    excludeActor: Boolean,
    actorUid: String
): Map<String, Any?> = mapOf(
    "type" to type,
    "title" to title,
    "message" to message,
    "severity" to severity,
    "link" to link,
    "forRoles" to forRoles.map { it.key },
    "forUserIds" to forUserIds,
    "excludeUserId" to if (excludeActor) actorUid else null,
    "actorId" to actorUid,
    "readBy" to emptyList<String>(),
    "deletedBy" to emptyList<String>()
)

/** The request sent to the `send-push` Edge Function. */
internal fun pushRequestBody(
    forRoles: List<Role>,
    forUserIds: List<String>,
    excludeUserId: String?,
    title: String,
    body: String,
    link: String?,
    notificationId: String
): JsonObject = JsonObject(
    mapOf(
        "forRoles" to JsonArray(forRoles.map { JsonPrimitive(it.key) }),
        "forUserIds" to JsonArray(forUserIds.map { JsonPrimitive(it) }),
        "excludeUserId" to (excludeUserId?.let { JsonPrimitive(it) } ?: JsonNull),
        "title" to JsonPrimitive(title),
        "body" to JsonPrimitive(body),
        "link" to JsonPrimitive(link ?: DEFAULT_PUSH_LINK),
        "notificationId" to JsonPrimitive(notificationId)
    )
)

/**
 * Writes `businesses/{bid}/notifications` and then asks the `send-push` Edge Function to deliver the same
 * message as an Android push. Every failure is swallowed and logged.
 */
@Singleton
class NotifierImpl @Inject constructor(
    private val firestore: BusinessFirestore,
    private val session: SessionManager,
    private val supabase: SupabaseClient
) : Notifier {

    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val currencySymbol: String
        get() = (session.state.value as? SessionState.SignedIn)?.business?.currencySymbol ?: "₦"

    override suspend fun notify(
        type: String,
        title: String,
        message: String,
        severity: String,
        link: String?,
        forRoles: List<Role>,
        forUserIds: List<String>,
        excludeActor: Boolean
    ) {
        try {
            val signedIn = session.state.value as? SessionState.SignedIn ?: return
            val uid = signedIn.user.uid
            val fields = notificationFields(type, title, message, severity, link, forRoles, forUserIds, excludeActor, uid)
            val id = firestore.add("notifications", fields + ("createdAt" to FieldValue.serverTimestamp()))
            sendPush(
                pushRequestBody(
                    forRoles = forRoles,
                    forUserIds = forUserIds,
                    excludeUserId = fields["excludeUserId"] as String?,
                    title = title,
                    body = message,
                    link = link,
                    notificationId = id
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not create notification \"$type\": ${e.javaClass.simpleName}")
        }
    }

    /** Fire-and-forget: never blocks the caller and never throws. */
    private fun sendPush(body: JsonObject) {
        pushScope.launch {
            try {
                supabase.functions.invoke(function = "send-push", body = body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Push request failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
