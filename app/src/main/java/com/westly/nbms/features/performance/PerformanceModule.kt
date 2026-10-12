package com.westly.nbms.features.performance

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Staff Performance feature into the app's feature set. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerformanceModule {
    @Binds
    @IntoSet
    abstract fun bindPerformanceFeature(f: PerformanceFeature): NbmsFeature
}
