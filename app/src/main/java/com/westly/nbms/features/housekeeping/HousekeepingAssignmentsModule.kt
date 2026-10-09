package com.westly.nbms.features.housekeeping

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Binds the housekeeping service and the Room Assignments feature, and nothing else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HousekeepingAssignmentsModule {

    @Binds
    @Singleton
    abstract fun bindHousekeepingService(impl: HousekeepingServiceImpl): HousekeepingService

    @Binds
    @IntoSet
    abstract fun bindHousekeepingAssignmentsFeature(impl: HousekeepingAssignmentsFeature): NbmsFeature
}
