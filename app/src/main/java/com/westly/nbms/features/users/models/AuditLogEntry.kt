package com.westly.nbms.features.users.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.westly.nbms.core.rbac.Role

/** One row of `businesses/{bid}/audit_logs`. The `isDeleted` field is written but not needed for reading. */
data class AuditLogEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userName: String = "",
    /** Role key such as "super_admin". */
    val userRole: String = "",
    val action: String = "",
    val collection: String = "",
    val documentId: String = "",
    val previousValue: Map<String, Any?>? = null,
    val newValue: Map<String, Any?>? = null,
    val deviceInfo: String = "",
    val timestamp: Timestamp? = null
)

fun AuditLogEntry.roleLabel(): String = Role.fromKey(userRole)?.label ?: userRole

/** Milliseconds since 1970, or null when the server has not stamped the entry yet. */
fun AuditLogEntry.timeMillis(): Long? = timestamp?.let { it.seconds * 1000L + it.nanoseconds / 1_000_000 }
