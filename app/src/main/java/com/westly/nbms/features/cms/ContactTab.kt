package com.westly.nbms.features.cms

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

/** Contact tab: `cms_content/contact` → address, phone, email, check-in / check-out times and the map link. */
@Composable
internal fun ContactTab(vm: WebsiteCmsViewModel, state: WebsiteCmsUiState) {
    if (state.contactLoad == SectionLoad.LOADING) {
        CmsLoadingRow("Loading contact information…")
        return
    }
    val d = state.contactDraft
    val errors = state.contactErrors
    CmsSectionCard("Contact Information") {
        CmsTextArea(
            value = d.address,
            onValueChange = { v -> vm.updateContact { it.copy(address = v) } },
            label = "Address",
            lines = 2
        )
        NbmsTextField(
            value = d.phone,
            onValueChange = { v -> vm.updateContact { it.copy(phone = v) } },
            label = "Phone",
            keyboardType = KeyboardType.Phone
        )
        NbmsTextField(
            value = d.email,
            onValueChange = { v -> vm.updateContact { it.copy(email = v) } },
            label = "Email",
            keyboardType = KeyboardType.Email,
            error = errors.email
        )
        NbmsTextField(
            value = d.checkInTime,
            onValueChange = { v -> vm.updateContact { it.copy(checkInTime = v) } },
            label = "Check-In Time",
            placeholder = "2:00 PM"
        )
        NbmsTextField(
            value = d.checkOutTime,
            onValueChange = { v -> vm.updateContact { it.copy(checkOutTime = v) } },
            label = "Check-Out Time",
            placeholder = "11:00 AM"
        )
        NbmsTextField(
            value = d.mapEmbedUrl,
            onValueChange = { v -> vm.updateContact { it.copy(mapEmbedUrl = v) } },
            label = "Google Maps Embed URL",
            keyboardType = KeyboardType.Uri,
            error = errors.mapEmbedUrl
        )
        NbmsButton(
            text = "Save Contact",
            onClick = { vm.saveContact() },
            loading = state.saving,
            enabled = !state.saving,
            leadingIcon = NbmsIcons.Check
        )
    }
}
