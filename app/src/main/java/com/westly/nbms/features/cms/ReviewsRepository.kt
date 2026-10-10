package com.westly.nbms.features.cms

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReviewsRepository"

internal const val REVIEWS_COLLECTION = "reviews"
internal const val REVIEW_STATUS_PENDING = "pending"
internal const val REVIEW_STATUS_APPROVED = "approved"
internal const val AUDIT_REVIEW_APPROVED = "review_approved"
internal const val AUDIT_REVIEW_DELETED = "review_deleted"

internal const val REVIEWS_WRITE_TIMEOUT_MS = 20_000L
internal const val REVIEWS_POST_STEP_TIMEOUT_MS = 5_000L
internal const val MSG_REVIEWS_LOAD_FAILED = "Reviews failed to load. Reload and try again."
internal const val MSG_REVIEWS_NOT_SIGNED_IN = "You're signed out. Sign in again and retry."
internal const val MSG_REVIEWS_TIMEOUT = "That took too long. Check your connection and try again."
internal const val MSG_REVIEWS_GENERIC = "Something went wrong. Please try again."

/** One guest review: `businesses/{bid}/reviews/{id}`. Anything whose [status] is not "approved" is pending. */
data class Review(
    val id: String,
    val name: String,
    val text: String,
    val rating: Int?,
    val status: String,
    val createdAt: Timestamp?,
    val approvedAt: Timestamp?,
    val approvedBy: String?
) {
    val isApproved: Boolean get() = status == REVIEW_STATUS_APPROVED
    val isPending: Boolean get() = !isApproved
}

/**
 * Reads a review tolerantly: a missing or wrongly typed field becomes "" (text), null (rating, times, approver) or "pending"
 * (status). A rating outside 1–5 is read as no rating. Never throws.
 */
internal fun parseReview(id: String, raw: Map<String, Any?>): Review {
    val rating = (raw["rating"] as? Number)?.let { n ->
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite()) null else n.toInt().takeIf { it in 1..5 }
    }
    return Review(
        id = id,
        name = (raw["name"] as? String) ?: "",
        text = (raw["text"] as? String) ?: "",
        rating = rating,
        status = (raw["status"] as? String)?.takeIf { it.isNotBlank() } ?: REVIEW_STATUS_PENDING,
        createdAt = raw["createdAt"] as? Timestamp,
        approvedAt = raw["approvedAt"] as? Timestamp,
        approvedBy = (raw["approvedBy"] as? String)?.takeIf { it.isNotBlank() }
    )
}

/** Stands for "the server's time" in a payload; the real store swaps it for `FieldValue.serverTimestamp()`. */
internal object ReviewServerTime

/** What Approve writes: `status = "approved"`, `approvedAt = server time`, `approvedBy = the current user's id`. */
internal fun buildReviewApprovePayload(approvedBy: String): Map<String, Any?> = mapOf(
    "status" to REVIEW_STATUS_APPROVED,
    "approvedAt" to ReviewServerTime,
    "approvedBy" to approvedBy
)

/** The database calls Guest Reviews needs. [FirestoreReviewsStore] is the real one; the unit tests use a fake. */
interface ReviewsStore {
    /** Live `reviews`, every document parsed with [parseReview]. */
    fun observe(): Flow<Resource<List<Review>>>

    /** Updates ONLY [fields] on the review. The fields may hold [ReviewServerTime]. */
    suspend fun update(id: String, fields: Map<String, Any?>)

    /** Hard-deletes the review document. */
    suspend fun delete(id: String)
}

class FirestoreReviewsStore(private val firestore: BusinessFirestore) : ReviewsStore {

    override fun observe(): Flow<Resource<List<Review>>> = callbackFlow {
        trySend(Resource.Loading)
        val registration = try {
            firestore.collection(REVIEWS_COLLECTION).addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(Resource.Error(MSG_REVIEWS_LOAD_FAILED, error))
                } else if (snapshot != null) {
                    try {
                        val list = snapshot.documents.map { doc: DocumentSnapshot ->
                            parseReview(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                        }
                        trySend(Resource.Success(list))
                    } catch (e: Exception) {
                        trySend(Resource.Error(MSG_REVIEWS_LOAD_FAILED, e))
                    }
                }
            }
        } catch (e: Exception) {
            trySend(Resource.Error(MSG_REVIEWS_LOAD_FAILED, e))
            null
        }
        awaitClose { registration?.remove() }
    }

    override suspend fun update(id: String, fields: Map<String, Any?>) {
        firestore.update(REVIEWS_COLLECTION, id, fields.mapValues { (_, v) -> if (v === ReviewServerTime) FieldValue.serverTimestamp() else v })
    }

    override suspend fun delete(id: String) {
        firestore.delete(REVIEWS_COLLECTION, id)
    }
}

/** A problem with a message that is safe to show. */
class ReviewsException(message: String) : Exception(message)

/** What the Guest Reviews ViewModel needs. Lets the unit tests use a fake. */
interface ReviewsSource {
    fun observe(): Flow<Resource<List<Review>>>
    suspend fun approve(id: String)
    suspend fun delete(id: String)
}

/**
 * Reads the `reviews` collection live and approves or deletes a review. Never shows toasts (the ViewModel does).
 * [approve] and [delete] throw when they fail; the audit entry is best-effort.
 */
@Singleton
class ReviewsRepository internal constructor(
    private val store: ReviewsStore,
    private val session: SessionManager,
    private val audit: AuditLogger
) : ReviewsSource {

    @Inject
    constructor(firestore: BusinessFirestore, session: SessionManager, audit: AuditLogger) :
        this(FirestoreReviewsStore(firestore), session, audit)

    override fun observe(): Flow<Resource<List<Review>>> = store.observe()

    /**
     * Sets `status = "approved"`, `approvedAt = server time`, `approvedBy = current user id`, then audits
     * `review_approved` with before/after `{status: "pending"} → {status: "approved"}`.
     */
    override suspend fun approve(id: String) {
        val signedIn = session.state.value as? SessionState.SignedIn ?: throw ReviewsException(MSG_REVIEWS_NOT_SIGNED_IN)
        write { store.update(id, buildReviewApprovePayload(signedIn.user.uid)) }
        auditBestEffort(
            AUDIT_REVIEW_APPROVED, id,
            previous = mapOf("status" to REVIEW_STATUS_PENDING),
            new = mapOf("status" to REVIEW_STATUS_APPROVED)
        )
    }

    /** Hard-deletes the review document, then audits `review_deleted`. */
    override suspend fun delete(id: String) {
        write { store.delete(id) }
        auditBestEffort(AUDIT_REVIEW_DELETED, id, previous = null, new = null)
    }

    private suspend fun write(block: suspend () -> Unit) {
        val finished: Boolean = try {
            withTimeoutOrNull(REVIEWS_WRITE_TIMEOUT_MS) {
                block()
                true
            } ?: false
        } catch (e: TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReviewsException) {
            throw e
        } catch (e: Exception) {
            throw ReviewsException(e.message?.takeIf { it.isNotBlank() } ?: MSG_REVIEWS_GENERIC)
        }
        if (!finished) throw ReviewsException(MSG_REVIEWS_TIMEOUT)
    }

    private suspend fun auditBestEffort(action: String, id: String, previous: Map<String, Any?>?, new: Map<String, Any?>?) {
        try {
            withTimeoutOrNull(REVIEWS_POST_STEP_TIMEOUT_MS) { audit.log(action, REVIEWS_COLLECTION, id, previous, new) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Review audit step failed", e)
        }
    }
}
