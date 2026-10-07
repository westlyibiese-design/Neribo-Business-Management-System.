package com.westly.nbms.core.feature

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Declares the feature sets so they compile (as empty sets) even when no phase has contributed to them yet. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureModule {
    @Multibinds abstract fun nbmsFeatures(): Set<NbmsFeature>
    @Multibinds abstract fun authFeatures(): Set<AuthFeature>
    @Multibinds abstract fun dashboardProviders(): Set<DashboardProvider>
    @Multibinds abstract fun shellOverlays(): Set<ShellOverlay>
}
