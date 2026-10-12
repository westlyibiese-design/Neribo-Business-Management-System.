package com.westly.nbms.features.media

import com.westly.nbms.core.feature.ImageFieldProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Phase 3 already declared `@Multibinds Set<ImageFieldProvider>`; binding the real provider here makes that set
 * non-empty, so every form that falls back to a URL text field now shows a real photo field instead.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @IntoSet
    abstract fun bindImageFieldProvider(impl: NbmsImageFieldProvider): ImageFieldProvider
}
