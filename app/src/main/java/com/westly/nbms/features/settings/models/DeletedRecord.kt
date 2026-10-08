package com.westly.nbms.features.settings.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

/** One row of `businesses/{bid}/deleted_records`. */
data class DeletedRecord(
    @DocumentId val id: String = "",
    val originalCollection: String = "",
    val originalDocumentId: String = "",
    val label: String = "",
    val deletedBy: String = "",
    val deletedByName: String = "",
    /** Role key such as "receptionist". */
    val deletedByRole: String = "",
    val deletedAt: Timestamp? = null,
    val reason: String? = null
)
