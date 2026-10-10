package com.westly.nbms.features.housekeeping

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Sub-phase 23B-1 version: only declares the (possibly empty) set of extra Overview buttons so Hilt can inject it
 * into the Overview ViewModel. Phase 24 adds its bindings with `@Binds @IntoSet`. Sub-phase 23B-2 replaces this file
 * with the full version that also binds the Housekeeping feature.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HousekeepingModule {

    @Multibinds
    abstract fun overviewActions(): Set<HousekeepingOverviewAction>
}
