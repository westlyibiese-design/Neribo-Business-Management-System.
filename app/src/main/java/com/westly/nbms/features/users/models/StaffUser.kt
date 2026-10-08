package com.westly.nbms.features.users.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.westly.nbms.core.rbac.Role

/**
 * One staff account, read from the read-only mirror `businesses/{bid}/users/{uid}`
 * (written by the Edge Functions; the app never writes it).
 */
data class StaffUser(
    @DocumentId val id: String = "",
    val uid: String = "",
    val name: String = "",
    val email: String? = null,
    val phone: String? = null,
    /** Role key such as "receptionist". */
    val role: String = "",
    /** "active" or "suspended". */
    val status: String = "active",
    val usesPin: Boolean = false,
    /** Not written by the server yet; shows "Never" until a later phase mirrors it. */
    val lastLogin: Timestamp? = null
)

fun StaffUser.roleOrNull(): Role? = Role.fromKey(role)

fun StaffUser.roleLabel(): String = roleOrNull()?.label ?: role

fun StaffUser.isActive(): Boolean = status == "active"
