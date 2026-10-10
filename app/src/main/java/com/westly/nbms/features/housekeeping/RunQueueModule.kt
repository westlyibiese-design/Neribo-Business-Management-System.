package com.westly.nbms.features.housekeeping

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Phase 24: adds the "Run Queue Now" button to the Housekeeping Overview's set of actions. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RunQueueModule {

    @Binds
    @IntoSet
    abstract fun bindRunQueueAction(impl: RunQueueAction): HousekeepingOverviewAction
}
