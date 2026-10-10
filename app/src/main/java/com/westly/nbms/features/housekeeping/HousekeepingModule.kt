package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Final Part 23B version: binds the Housekeeping feature and declares the (possibly empty) set of extra Overview
 * buttons. Phase 24 adds its bindings with `@Binds @IntoSet`. `HousekeepingService` is bound by Part 23A, not here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HousekeepingModule {

    @Multibinds
    abstract fun overviewActions(): Set<HousekeepingOverviewAction>

    @Binds
    @IntoSet
    abstract fun bindFeature(f: HousekeepingFeature): NbmsFeature
}
