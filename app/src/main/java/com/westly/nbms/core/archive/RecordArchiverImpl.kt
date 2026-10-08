package com.westly.nbms.core.archive

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordArchiver"
internal const val MSG_NOT_SIGNED_IN = "Not signed in"
internal const val MSG_SUPER_ADMIN_ONLY = "Only the Super Admin can do this."
internal const val MSG_ARCHIVE_GONE = "This record was already restored or removed."
internal const val MSG_ARCHIVE_GENERIC = "Something went wrong. Please try again."

// ---- Pure builders (unit-tested). [serverTime] is FieldValue.serverTimestamp() in production. ----

/** Fields set on the original document when it is soft-deleted. */
internal fun softDeleteFields(uid: String, name: String, reason: String?, serverTime: Any): Map<String, Any?> = mapOf(
    "isDeleted" to true,
    "deletedAt" to serverTime,
    "deletedBy" to uid,
    "deletedByName" to name,
    "deleteReason" to reason
)

/** The `deleted_records` archive document. */
internal fun archiveEntry(
    collection: String,
    documentId: String,
    label: String,
    uid: String,
    name: String,
    roleKey: String,
    reason: String?,
    serverTime: Any
): Map<String, Any?> = mapOf(
    "originalCollection" to collection,
    "originalDocumentId" to documentId,
    "label" to label,
    "deletedBy" to uid,
    "deletedByName" to name,
    "deletedByRole" to roleKey,
    "deletedAt" to serverTime,
    "reason" to reason
)

/** Fields set on the original document when it is restored. */
internal fun restoreFields(uid: String, name: String, serverTime: Any): Map<String, Any?> = mapOf(
    "isDeleted" to false,
    "restoredAt" to serverTime,
    "restoredBy" to uid,
    "restoredByName" to name
)

@Singleton
class RecordArchiverImpl @Inject constructor(
    private val db: FirebaseFirestore,
    private val firestore: BusinessFirestore,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val listeners: Set<@JvmSuppressWildcards SoftDeleteListener>
) : RecordArchiver {

    private fun signedIn(): SessionState.SignedIn =
        session.state.value as? SessionState.SignedIn ?: throw IllegalStateException(MSG_NOT_SIGNED_IN)

    private fun requireSuperAdmin(s: SessionState.SignedIn) {
        if (s.user.role != Role.SUPER_ADMIN) throw IllegalStateException(MSG_SUPER_ADMIN_ONLY)
    }

    override suspend fun softDelete(collection: String, documentId: String, label: String, reason: String?): Result<Unit> {
        return try {
            val user = signedIn().user
            val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
            val batch = db.batch()
            batch.update(
                firestore.doc(collection, documentId),
                softDeleteFields(user.uid, user.name, cleanReason, FieldValue.serverTimestamp())
            )
            batch.set(
                firestore.collection("deleted_records").document(),
                archiveEntry(
                    collection, documentId, label, user.uid, user.name, user.role.key,
                    cleanReason, FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()

            audit.log("soft_delete", collection, documentId, null, mapOf("reason" to cleanReason))
            for (listener in listeners) {
                try {
                    listener.onSoftDeleted(collection, label, cleanReason)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "A delete listener failed: ${e.javaClass.simpleName}")
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: MSG_ARCHIVE_GENERIC))
        }
    }

    override suspend fun restore(deletedRecordId: String): Result<Unit> {
        return try {
            val s = signedIn()
            requireSuperAdmin(s)
            val archiveRef = firestore.doc("deleted_records", deletedRecordId)
            val snap = archiveRef.get().await()
            if (!snap.exists()) throw IllegalStateException(MSG_ARCHIVE_GONE)
            val collection = snap.getString("originalCollection").orEmpty()
            val documentId = snap.getString("originalDocumentId").orEmpty()
            if (collection.isBlank() || documentId.isBlank()) throw IllegalStateException(MSG_ARCHIVE_GENERIC)

            val batch = db.batch()
            batch.update(
                firestore.doc(collection, documentId),
                restoreFields(s.user.uid, s.user.name, FieldValue.serverTimestamp())
            )
            batch.delete(archiveRef)
            batch.commit().await()

            audit.log("restore", collection, documentId, mapOf("isDeleted" to true), mapOf("isDeleted" to false))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: MSG_ARCHIVE_GENERIC))
        }
    }

    override suspend fun purge(deletedRecordId: String): Result<Unit> {
        return try {
            val s = signedIn()
            requireSuperAdmin(s)
            val archiveRef = firestore.doc("deleted_records", deletedRecordId)
            val snap = archiveRef.get().await()
            if (!snap.exists()) throw IllegalStateException(MSG_ARCHIVE_GONE)
            val collection = snap.getString("originalCollection").orEmpty()
            val documentId = snap.getString("originalDocumentId").orEmpty()
            if (collection.isBlank() || documentId.isBlank()) throw IllegalStateException(MSG_ARCHIVE_GENERIC)

            val batch = db.batch()
            batch.delete(firestore.doc(collection, documentId))
            batch.delete(archiveRef)
            batch.commit().await()

            audit.log("permanent_purge", collection, documentId, mapOf("isDeleted" to true), mapOf("purgedBy" to s.user.name))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: MSG_ARCHIVE_GENERIC))
        }
    }
}
