package com.westly.nbms.features.finance

import com.google.firebase.firestore.FieldValue
import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Stands for "the server's clock" inside the fields handed to a [RevenueSourceStore]. */
internal object ApprovalServerTime

/**
 * The only database access the ledger needs. [FirestoreRevenueSourceStore] is the real one; the unit tests use a fake.
 * The five streams return the raw documents of the five income collections (a missing collection is just empty).
 */
internal interface RevenueSourceStore {
    fun payments(): Flow<Resource<List<RawPayment>>>
    fun sales(): Flow<Resource<List<RawSale>>>
    fun orders(): Flow<Resource<List<RawOrder>>>
    fun barOrders(): Flow<Resource<List<RawBarOrder>>>
    fun laundryRequests(): Flow<Resource<List<RawLaundry>>>

    /** Updates ONLY [fields] on the existing SOURCE document. [ApprovalServerTime] values become the server timestamp. */
    suspend fun updateFields(source: SourceCollection, id: String, fields: Map<String, Any?>)
}

internal class FirestoreRevenueSourceStore(
    private val firestore: BusinessFirestore
) : RevenueSourceStore {
    override fun payments() = firestore.observeList(SourceCollection.PAYMENTS.path, RawPayment::class.java)
    override fun sales() = firestore.observeList(SourceCollection.SALES.path, RawSale::class.java)
    override fun orders() = firestore.observeList(SourceCollection.ORDERS.path, RawOrder::class.java)
    override fun barOrders() = firestore.observeList(SourceCollection.BAR_ORDERS.path, RawBarOrder::class.java)
    override fun laundryRequests() = firestore.observeList(SourceCollection.LAUNDRY_REQUESTS.path, RawLaundry::class.java)

    override suspend fun updateFields(source: SourceCollection, id: String, fields: Map<String, Any?>) {
        firestore.update(
            source.path,
            id,
            fields.mapValues { (_, v) -> if (v === ApprovalServerTime) FieldValue.serverTimestamp() else v }
        )
    }
}

private fun <T> Resource<List<T>>.items(): List<T> = (this as? Resource.Success)?.data.orEmpty()

internal const val NO_APPROVE_PERMISSION = "You don't have permission to approve payments."

@Singleton
class RevenueLedgerImpl internal constructor(
    private val store: RevenueSourceStore,
    private val audit: AuditLogger,
    private val notifier: Notifier,
    private val session: SessionManager
) : RevenueLedger {

    /** The constructor Hilt uses: the real Firestore-backed store. (Tests use the internal constructor with a fake store.) */
    @Inject
    constructor(
        firestore: BusinessFirestore,
        audit: AuditLogger,
        notifier: Notifier,
        session: SessionManager
    ) : this(FirestoreRevenueSourceStore(firestore), audit, notifier, session)

    init {
        syncZone(session.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<Resource<List<RevenueTransaction>>> =
        session.state
            .map { state -> (state as? SessionState.SignedIn)?.let { it.business.id to it.business.timezone } }
            .distinctUntilChanged()
            .flatMapLatest { key ->
                if (key == null) {
                    flowOf<Resource<List<RevenueTransaction>>>(Resource.Loading)
                } else {
                    applyZone(key.second)
                    merged()
                }
            }

    private fun merged(): Flow<Resource<List<RevenueTransaction>>> =
        combine(
            store.payments(), store.sales(), store.orders(), store.barOrders(), store.laundryRequests()
        ) { payments, sales, orders, barOrders, laundry ->
            val all = listOf(payments, sales, orders, barOrders, laundry)
            val error = all.filterIsInstance<Resource.Error>().firstOrNull()
            when {
                error != null -> error
                all.any { it is Resource.Loading } -> Resource.Loading
                else -> {
                    val txns = buildList {
                        payments.items().filter { it.isIncluded() }.mapTo(this, ::normalizePayment)
                        sales.items().filter { it.isIncluded() }.mapTo(this, ::normalizeSale)
                        orders.items().filter { it.isIncluded() }.mapTo(this, ::normalizeOrder)
                        barOrders.items().filter { it.isIncluded() }.mapTo(this, ::normalizeBarOrder)
                        laundry.items().filter { it.isIncluded() }.mapTo(this, ::normalizeLaundry)
                    }
                    Resource.Success(txns.sortedWith(compareByDescending<RevenueTransaction> { it.date }))
                }
            }
        }

    override suspend fun approve(txn: RevenueTransaction) {
        val user = requireApprover()
        store.updateFields(
            txn.source, txn.id,
            mapOf(
                "approvalStatus" to ApprovalStatus.APPROVED.key(),
                "approvedBy" to user.uid,
                "approvedByName" to user.name,
                "approvedAt" to ApprovalServerTime,
                "rejectedReason" to null
            )
        )
        audit.log(
            "payment_approved",
            txn.source.path,
            txn.id,
            mapOf("approvalStatus" to txn.approvalStatus.key()),
            mapOf("approvalStatus" to ApprovalStatus.APPROVED.key(), "amount" to txn.amount)
        )
        try {
            notifier.notifyPaymentApproved(txn.amount, txn.guestName, user.name)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // The approval is saved and audited; a failed alert must not turn it into a failure.
        }
    }

    override suspend fun reject(txn: RevenueTransaction, reason: String?) {
        val user = requireApprover()
        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        store.updateFields(
            txn.source, txn.id,
            mapOf(
                "approvalStatus" to ApprovalStatus.REJECTED.key(),
                "approvedBy" to user.uid,
                "approvedByName" to user.name,
                "approvedAt" to ApprovalServerTime,
                "rejectedReason" to cleanReason
            )
        )
        audit.log(
            "payment_rejected",
            txn.source.path,
            txn.id,
            mapOf("approvalStatus" to txn.approvalStatus.key()),
            mapOf("approvalStatus" to ApprovalStatus.REJECTED.key(), "reason" to cleanReason, "amount" to txn.amount)
        )
    }

    /** Only super_admin and accountant may approve or reject. The screens hide the buttons; this is the second guard. */
    private fun requireApprover(): SessionUser {
        val signedIn = session.state.value as? SessionState.SignedIn
            ?: throw IllegalStateException("Not signed in")
        syncZone(signedIn)
        if (signedIn.user.role != Role.SUPER_ADMIN && signedIn.user.role != Role.ACCOUNTANT) {
            throw IllegalStateException(NO_APPROVE_PERMISSION)
        }
        return signedIn.user
    }

    private fun syncZone(state: SessionState) {
        if (state is SessionState.SignedIn) applyZone(state.business.timezone)
    }

    private fun applyZone(timezone: String) {
        RevenueMath.zone = try {
            ZoneId.of(timezone)
        } catch (e: Exception) {
            ZoneId.of("Africa/Lagos")
        }
    }
}
