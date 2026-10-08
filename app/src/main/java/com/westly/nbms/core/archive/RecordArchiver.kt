package com.westly.nbms.core.archive

/**
 * Soft delete / restore / purge of business records (Westly `softDelete` and `restoreRecord`).
 * Every operation writes its changes in ONE Firestore write batch.
 */
interface RecordArchiver {
    /**
     * Sets isDeleted/deletedAt/deletedBy/deletedByName on the source doc and writes a `deleted_records` archive doc,
     * logs audit action "soft_delete", then tells every [SoftDeleteListener].
     * [label] = human description of the record, [reason] optional.
     */
    suspend fun softDelete(collection: String, documentId: String, label: String, reason: String? = null): Result<Unit>

    /** Super Admin only. Brings the record back and removes its archive doc. Audit action "restore". */
    suspend fun restore(deletedRecordId: String): Result<Unit>

    /** Super Admin only. Hard-deletes the record and its archive doc. Audit action "permanent_purge". */
    suspend fun purge(deletedRecordId: String): Result<Unit>
}

/** Phase 9 declares an empty set of these; Phase 10 binds one that sends the "Record Deleted" staff alert. */
fun interface SoftDeleteListener {
    suspend fun onSoftDeleted(collection: String, label: String, reason: String?)
}
