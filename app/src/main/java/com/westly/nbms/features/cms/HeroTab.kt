package com.westly.nbms.features.cms

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

/** Hero tab: `cms_content/hero` → headline, sub-headline, call-to-action button and background image. */
@Composable
internal fun HeroTab(vm: WebsiteCmsViewModel, state: WebsiteCmsUiState) {
    if (state.heroLoad == SectionLoad.LOADING) {
        CmsLoadingRow("Loading hero…")
        return
    }
    val d = state.heroDraft
    CmsSectionCard("Hero Section") {
        NbmsTextField(
            value = d.headline,
            onValueChange = { v -> vm.updateHero { it.copy(headline = v) } },
            label = "Headline"
        )
        NbmsTextField(
            value = d.subheadline,
            onValueChange = { v -> vm.updateHero { it.copy(subheadline = v) } },
            label = "Sub-headline"
        )
        NbmsTextField(
            value = d.ctaText,
            onValueChange = { v -> vm.updateHero { it.copy(ctaText = v) } },
            label = "CTA Button Text"
        )
        NbmsTextField(
            value = d.ctaLink,
            onValueChange = { v -> vm.updateHero { it.copy(ctaLink = v) } },
            label = "CTA Button Link"
        )
        CmsImageField(
            providers = vm.imageProviders,
            label = "background image",
            folder = IMAGE_FOLDER_HERO,
            value = d.backgroundImage,
            onChange = { v -> vm.updateHero { it.copy(backgroundImage = v) } },
            previewHeight = 160.dp
        )
        NbmsButton(
            text = "Save Hero",
            onClick = { vm.saveHero() },
            loading = state.saving,
            enabled = !state.saving,
            leadingIcon = NbmsIcons.Check
        )
    }
}
