package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.audit.AuditLogger
import com.westly.nbms.core.notify.Notifier
import com.westly.nbms.core.rbac.Role
import com.westly.nbms.features.rooms.Cleanliness
import com.westly.nbms.features.rooms.Room
import com.westly.nbms.features.rooms.RoomDisplayStatus
import com.westly.nbms.features.rooms.RoomEvent
import com.westly.nbms.features.rooms.RoomLogic
import com.westly.nbms.features.rooms.RoomStatus
import kotlinx.datetime.Instant

/** In-memory documents with real batch behaviour: a batch is applied all at once (or not at all if a write is impossible). */
internal class FakeHousekeepingStore : HousekeepingStore {
    val docs = mutableMapOf<String, MutableMap<String, MutableMap<String, Any?>>>()
    var commits = 0
    private var counter = 0

    fun col(name: String): MutableMap<String, MutableMap<String, Any?>> = docs.getOrPut(name) { mutableMapOf() }
    fun put(collection: String, id: String, vararg fields: Pair<String, Any?>) {
        col(collection)[id] = mutableMapOf(*fields)
    }

    override fun newId(collection: String): String = "$collection-new${++counter}"
    override suspend fun getDoc(collection: String, id: String): Map<String, Any?>? = col(collection)[id]?.toMap()
    override suspend fun addDoc(collection: String, data: Map<String, Any?>): String {
        val id = "$collection-add${++counter}"
        col(collection)[id] = data.toMutableMap()
        return id
    }

    override suspend fun updateDoc(collection: String, id: String, fields: Map<String, Any?>) {
        val doc = col(collection)[id] ?: throw IllegalStateException("No document to update: $collection/$id")
        doc.putAll(fields)
    }

    override suspend fun commit(writes: List<HkWrite>) {
        commits++
        // Check first, then apply: nothing is applied when a write cannot be done.
        writes.forEach {
            if (it is HkWrite.Patch && col(it.collection)[it.id] == null) throw IllegalStateException("No document to update: ${it.collection}/${it.id}")
        }
        writes.forEach {
            when (it) {
                is HkWrite.Put -> col(it.collection)[it.id] = it.data.toMutableMap()
                is HkWrite.Patch -> col(it.collection).getValue(it.id).putAll(it.fields)
                is HkWrite.Remove -> col(it.collection).remove(it.id)
            }
        }
    }

    override suspend fun activeMirrorRoomIds(housekeeperId: String): List<String> =
        col("room_assignments").filter { (_, d) -> d["housekeeperId"] == housekeeperId && d["status"] == "active" }.keys.toList()
}

internal class FakeHousekeepingRoomLogic : RoomLogic {
    /** Everything called, in order: "status:<roomId>:<status>" or "clean:<roomId>:<cleanliness>". */
    val calls = mutableListOf<String>()
    val cleanlinessExtras = mutableListOf<Map<String, Any?>>()
    var failStatus: Exception? = null
    var failCleanliness: Exception? = null

    override suspend fun updateRoomStatus(roomId: String, newStatus: RoomStatus, extra: Map<String, Any?>, allowOccupiedOverride: Boolean) {
        calls += "status:$roomId:${newStatus.key}"
        failStatus?.let { throw it }
    }

    override suspend fun updateRoomCleanliness(roomId: String, cleanliness: Cleanliness, extra: Map<String, Any?>) {
        calls += "clean:$roomId:${cleanliness.key}"
        cleanlinessExtras += extra
        failCleanliness?.let { throw it }
    }

    override fun datesOverlap(aIn: Instant, aOut: Instant, bIn: Instant, bOut: Instant): Boolean = aIn < bOut && aOut > bIn
    override suspend fun detectConflict(roomId: String, checkIn: Instant, checkOut: Instant, excludeBookingId: String?): Boolean = false
    override suspend fun findAvailableRooms(roomType: String, checkIn: Instant, checkOut: Instant): List<Room> = emptyList()
    override fun getRoomDisplayStatus(room: Room): RoomDisplayStatus = throw UnsupportedOperationException("not used by housekeeping")
    override suspend fun triggerRoomStatus(roomId: String, event: RoomEvent) {}
}

internal class FakeHousekeepingAudit : AuditLogger {
    data class Entry(val action: String, val collection: String, val documentId: String, val newValue: Map<String, Any?>?)
    val entries = mutableListOf<Entry>()
    var fail = false
    override suspend fun log(action: String, collection: String, documentId: String, previousValue: Map<String, Any?>?, newValue: Map<String, Any?>?) {
        if (fail) throw IllegalStateException("audit is down")
        entries += Entry(action, collection, documentId, newValue)
    }
}

/** Records every notification; `calls` hold "type|message" and `userIds` the people it went to. */
internal class FakeHousekeepingNotifier : Notifier {
    data class Call(val type: String, val message: String, val forUserIds: List<String>)
    val calls = mutableListOf<Call>()
    var fail = false
    override suspend fun notify(
        type: String, title: String, message: String, severity: String, link: String?,
        forRoles: List<Role>, forUserIds: List<String>, excludeActor: Boolean
    ) {
        if (fail) throw IllegalStateException("push is down")
        calls += Call(type, message, forUserIds)
    }
}

internal val TEST_ACTOR = HousekeepingActor("sup1", "Sam Super")
