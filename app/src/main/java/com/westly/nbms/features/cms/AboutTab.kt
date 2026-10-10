package com.westly.nbms.features.cms

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

/** About tab: `cms_content/about` → title, description, mission, year founded (digits only) and picture. */
@Composable
internal fun AboutTab(vm: WebsiteCmsViewModel, state: WebsiteCmsUiState) {
    if (state.aboutLoad == SectionLoad.LOADING) {
        CmsLoadingRow("Loading about…")
        return
    }
    val d = state.aboutDraft
    CmsSectionCard("About Section") {
        NbmsTextField(
            value = d.title,
            onValueChange = { v -> vm.updateAbout { it.copy(title = v) } },
            label = "Title"
        )
        CmsTextArea(
            value = d.description,
            onValueChange = { v -> vm.updateAbout { it.copy(description = v) } },
            label = "Description",
            lines = 4
        )
        NbmsTextField(
            value = d.mission,
            onValueChange = { v -> vm.updateAbout { it.copy(mission = v) } },
            label = "Mission Statement"
        )
        NbmsTextField(
            value = d.founded,
            onValueChange = { v -> vm.updateAbout { it.copy(founded = CmsFormRules.filterFounded(v)) } },
            label = "Year Founded",
            keyboardType = KeyboardType.Number
        )
        CmsImageField(
            providers = vm.imageProviders,
            label = "about image",
            folder = IMAGE_FOLDER_ABOUT,
            value = d.image,
            onChange = { v -> vm.updateAbout { it.copy(image = v) } },
            previewHeight = 160.dp
        )
        NbmsButton(
            text = "Save About",
            onClick = { vm.saveAbout() },
            loading = state.saving,
            enabled = !state.saving,
            leadingIcon = NbmsIcons.Check
        )
    }
}
