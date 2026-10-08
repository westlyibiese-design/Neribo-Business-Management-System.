package com.westly.nbms.core.audit

/**
 * Writes the audit trail (Firestore `businesses/{bid}/audit_logs`). Every screen that changes data calls this.
 */
interface AuditLogger {
    /** Writes businesses/{bid}/audit_logs. Never throws; failures are swallowed and logged. */
    suspend fun log(
        action: String,
        collection: String,
        documentId: String,
        previousValue: Map<String, Any?>? = null,
        newValue: Map<String, Any?>? = null
    )
}
