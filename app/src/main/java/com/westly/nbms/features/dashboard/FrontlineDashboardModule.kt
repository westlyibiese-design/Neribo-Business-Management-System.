package com.westly.nbms.features.dashboard

import com.westly.nbms.core.feature.DashboardProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Phase 34A2: binds both front-line providers. Phase 34B does not touch this file. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FrontlineDashboardModule {
    @Binds
    @IntoSet
    abstract fun bindFrontline(p: FrontlineDashboardProvider): DashboardProvider

    @Binds
    @IntoSet
    abstract fun bindFallback(p: MyTasksFallbackProvider): DashboardProvider
}
