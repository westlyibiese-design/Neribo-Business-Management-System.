package com.westly.nbms.features.cms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.feature.ImageFieldProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import javax.inject.Inject

internal const val BANNERS_INTRO = "Hero banners and backgrounds for every other page on the site. Each one goes live as soon as you save."

/**
 * Which page banner is open (only one at a time) and its editor. Opening a banner starts watching its document; closing it
 * stops. The open editor lives here, so typing in it survives switching to another tab and back.
 */
@HiltViewModel
class PageBannersViewModel internal constructor(
    private val source: CmsSource,
    private val toast: ToastController,
    val imageProviders: Set<ImageFieldProvider>
) : ViewModel() {

    @Inject
    constructor(
        repository: CmsRepository,
        toast: ToastController,
        imageProviders: @JvmSuppressWildcards Set<ImageFieldProvider>
    ) : this(repository as CmsSource, toast, imageProviders)

    private val _openDocId = MutableStateFlow<String?>(null)
    val openDocId: StateFlow<String?> = _openDocId.asStateFlow()

    private val _model = MutableStateFlow<PageHeroEditorModel?>(null)
    val model: StateFlow<PageHeroEditorModel?> = _model.asStateFlow()

    private var watchJob: Job? = null

    /** Opens the banner [docId], closing the one that was open; tapping the open one closes it. */
    fun toggle(docId: String) {
        val wasOpen = _openDocId.value == docId
        close()
        if (wasOpen) return
        val section = PAGE_HERO_SECTIONS.firstOrNull { it.docId == docId } ?: return
        val job = SupervisorJob(viewModelScope.coroutineContext.job)
        watchJob = job
        _model.value = PageHeroEditorModel(source, toast, section, CoroutineScope(viewModelScope.coroutineContext + job))
        _openDocId.value = docId
    }

    private fun close() {
        watchJob?.cancel()
        watchJob = null
        _model.value = null
        _openDocId.value = null
    }
}

/** Page Banners tab: one expandable card per page banner (only one open at a time). */
@Composable
internal fun PageBannersTab() {
    val vm: PageBannersViewModel = hiltViewModel()
    val openId by vm.openDocId.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(BANNERS_INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PAGE_HERO_SECTIONS.forEach { section ->
            val open = openId == section.docId
            NbmsCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { vm.toggle(section.docId) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            section.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            section.usedOn,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    NbmsButton(
                        text = if (open) "Close" else "Edit",
                        onClick = { vm.toggle(section.docId) },
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm
                    )
                }
                if (open) {
                    model?.let { current ->
                        PageHeroEditor(
                            section = section,
                            model = current,
                            providers = vm.imageProviders,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
