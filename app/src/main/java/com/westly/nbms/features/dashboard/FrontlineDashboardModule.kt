package com.westly.nbms.features.dashboard

import com.westly.nbms.core.feature.DashboardProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Phase 34A1 binds only the My Tasks fallback. Phase 34A2 REPLACES this file so it also binds FrontlineDashboardProvider. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FrontlineDashboardModule {
    @Binds
    @IntoSet
    abstract fun bindFallback(p: MyTasksFallbackProvider): DashboardProvider
}
