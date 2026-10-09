package com.westly.nbms.features.bar

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.ModuleKey
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.core.session.Business
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.session.SessionUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory bar_orders. `createSale` records what the real store would write. */
internal class FakePlaceStore : BarSalesStore {
    val created = mutableMapOf<String, Map<String, Any?>>()
    var createCalls = 0
    var failWith: Exception? = null
    var hang = false
    private var counter = 0

    override suspend fun createSale(payload: Map<String, Any?>): String {
        createCalls++
        if (hang) kotlinx.coroutines.awaitCancellation()
        failWith?.let { throw it }
        val id = "sale${++counter}"
        created[id] = payload
        return id
    }
}

internal class FakePlaceAudit : AuditLogger {
    val entries = mutableListOf<List<Any?>>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += listOf(action, collection, documentId, newValue)
    }
}

internal class FakePlaceNotifier : Notifier {
    data class Call(val type: String, val title: String, val message: String, val link: String?)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, title, message, link)
    }
}

internal class FakePlaceSession(signedIn: Boolean = true, role: Role = Role.BAR_ATTENDANT) : SessionManager {
    override val state: StateFlow<SessionState> = MutableStateFlow(
        if (!signedIn) SessionState.SignedOut
        else SessionState.SignedIn(
            SessionUser("u1", "biz1", role, "Wale", "w@x.com", null, "active", false),
            Business("biz1", "Hotel", "ABC123", setOf(role)),
            emptySet<ModuleKey>()
        )
    )
    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> = Result.success(Unit)
    override suspend fun signOut() {}
    override suspend fun refresh() {}
}

class BarSalesPlaceTest {

    private class Rig(signedIn: Boolean = true, role: Role = Role.BAR_ATTENDANT) {
        val store = FakePlaceStore()
        val audit = FakePlaceAudit()
        val notifier = FakePlaceNotifier()
        val repo = BarSalesRepository(store, FakePlaceSession(signedIn, role), audit, notifier)
    }

    private val heineken = BarCartLine("a1", "Heineken 60cl", 1500.0, 2, false)
    private val cocktail = BarCartLine("manual-1-abc123", "Special cocktail", 3000.0, 1, true)

    @Test fun placeSavesOnePendingSaleInOneTransaction() = runTest {
        val r = Rig()
        val form = BarSaleForm(tableNumber = "B-03", payment = BarPaymentMethod.POS)
        val result = r.repo.place(listOf(heineken, cocktail), form)

        assertEquals(1, r.store.createCalls)
        assertEquals(6000.0, result.total, 0.0)
        assertEquals(2, result.itemCount)
        val doc = r.store.created.getValue(result.saleId)
        assertEquals("u1", doc["barAttendantId"]); assertEquals("Wale", doc["barAttendantName"])
        assertEquals("pending", doc["status"]); assertEquals("pending", doc["approvalStatus"])
        assertEquals("B-03", doc["tableNumber"]); assertEquals("pos", doc["paymentMethod"])
        assertEquals(6000.0, doc["total"]); assertEquals(true, doc["hasManualItems"]); assertEquals(false, doc["isDeleted"])
        assertSame(BarServerTime, doc["createdAt"])
    }

    @Test fun placeLogsTheAuditEntryAndSendsTheBarAlert() = runTest {
        val r = Rig()
        val result = r.repo.place(listOf(heineken), BarSaleForm())
        assertEquals(listOf(listOf("new_bar_sale", "bar_orders", result.saleId, mapOf("total" to 3000.0))), r.audit.entries)
        val call = r.notifier.calls.single()
        assertEquals("new_sale", call.type)
        assertTrue(call.message.contains("Wale")); assertTrue(call.message.contains("bar"))
    }

    @Test fun aFailingAuditOrAlertNeverTurnsASavedSaleIntoAnError() = runTest {
        val r = Rig()
        r.audit.fail = true; r.notifier.fail = true
        val result = r.repo.place(listOf(heineken), BarSaleForm())
        assertTrue(r.store.created.containsKey(result.saleId))
    }

    @Test fun anEmptySaleIsRefusedAndNothingIsSaved() = runTest {
        val r = Rig()
        try { r.repo.place(emptyList(), BarSaleForm()); fail("expected an error") } catch (e: BarSaleException) {
            assertEquals("The sale is empty.", e.message)
        }
        assertEquals(0, r.store.createCalls)
    }

    @Test fun signedOutIsRefusedAndNothingIsSaved() = runTest {
        val r = Rig(signedIn = false)
        try { r.repo.place(listOf(heineken), BarSaleForm()); fail("expected an error") } catch (e: BarSaleException) {
            assertEquals("Not signed in", e.message)
        }
        assertEquals(0, r.store.createCalls)
    }

    @Test fun aFailedSaveGivesItsMessageAndSkipsTheExtras() = runTest {
        val r = Rig()
        r.store.failWith = IllegalStateException("You don't have access to this data.")
        try { r.repo.place(listOf(heineken), BarSaleForm()); fail("expected an error") } catch (e: IllegalStateException) {
            assertEquals("You don't have access to this data.", e.message)
        }
        assertTrue(r.store.created.isEmpty()); assertTrue(r.audit.entries.isEmpty()); assertTrue(r.notifier.calls.isEmpty())
    }

    @Test fun aSaveThatNeverFinishesEndsWithAClearTimeoutMessage() = runTest {
        val r = Rig()
        r.store.hang = true
        try { r.repo.place(listOf(heineken), BarSaleForm()); fail("expected a timeout") } catch (e: BarSaleException) {
            assertEquals("The sale took too long to save. Check your connection and try again.", e.message)
        }
        assertTrue(r.audit.entries.isEmpty())
    }
}
