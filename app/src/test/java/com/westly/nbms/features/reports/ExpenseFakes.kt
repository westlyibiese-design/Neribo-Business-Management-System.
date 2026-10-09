package com.westly.nbms.features.reports

import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** A Firestore that records every `add`. [docs] is what `observeList("expenses", ...)` shows. Set [gate] to hold `add` open, or [failWith] to make it throw. */
internal class ExpenseFakeFirestore : BusinessFirestore {
    override val businessId: String = "biz1"
    val docs = MutableStateFlow<Resource<List<ExpenseDoc>>>(Resource.Success(emptyList()))
    val adds = mutableListOf<Pair<String, Any>>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: Exception? = null

    override fun collection(name: String): CollectionReference = throw UnsupportedOperationException("collection")
    override fun doc(collection: String, id: String): DocumentReference = throw UnsupportedOperationException("doc")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observeList(
        collection: String,
        clazz: Class<T>,
        query: (Query) -> Query
    ): Flow<Resource<List<T>>> = docs as Flow<Resource<List<T>>>

    override fun <T : Any> observeDoc(collection: String, id: String, clazz: Class<T>): Flow<Resource<T?>> =
        throw UnsupportedOperationException("observeDoc")

    override suspend fun <T : Any> add(collection: String, value: T): String {
        adds += collection to value
        gate?.await()
        failWith?.let { throw it }
        return "exp1"
    }

    override suspend fun update(collection: String, id: String, fields: Map<String, Any?>) = throw UnsupportedOperationException("update")
    override suspend fun set(collection: String, id: String, value: Any, merge: Boolean) = throw UnsupportedOperationException("set")
    override suspend fun delete(collection: String, id: String) = throw UnsupportedOperationException("delete")
    override suspend fun <R> runTransaction(block: suspend (Transaction, BusinessFirestore) -> R): R =
        throw UnsupportedOperationException("runTransaction")
}

internal class ExpenseFakeAudit : AuditLogger {
    data class Entry(
        val action: String,
        val collection: String,
        val documentId: String,
        val previous: Map<String, Any?>?,
        val new: Map<String, Any?>?
    )

    val entries = mutableListOf<Entry>()

    override suspend fun log(
        action: String, collection: String, documentId: String,
        previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?
    ) {
        entries += Entry(action, collection, documentId, previousValue, newValue)
    }
}

/** Records every notification. Only `notify` is implemented; `notifyLargeExpense` is the real one from [Notifier]. */
internal class ExpenseFakeNotifier : Notifier {
    data class Call(val type: String, val title: String, val message: String, val forRoles: List<Role>)

    val calls = mutableListOf<Call>()
    var fail = false

    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, title, message, forRoles)
    }
}

internal class ExpenseFakeSession(role: Role = Role.ACCOUNTANT, timezone: String = "Africa/Lagos") : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Ngozi", "n@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role), timezone = timezone),
            emptySet<ModuleKey>()
        )
    )

    val signedIn: SessionState.SignedIn get() = state.value as SessionState.SignedIn

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

internal fun expenseTs(iso: String): Timestamp = Timestamp(Instant.parse(iso).epochSecond, 0)

/** An expense document for the tests. Defaults: a 1,000 "other" cash expense dated 15 Oct 2026 and recorded by "Ngozi". */
internal fun expenseDoc(
    id: String,
    title: String = "Item $id",
    amount: Double = 1_000.0,
    category: String = "other",
    date: String? = "2026-10-15T10:00:00Z",
    method: String = "cash",
    by: String = "Ngozi",
    deleted: Boolean = false
) = ExpenseDoc(
    id = id,
    title = title,
    amount = amount,
    category = category,
    date = date?.let { expenseTs(it) },
    paymentMethod = method,
    recordedByName = by,
    isDeleted = deleted
)
