package com.westly.nbms.features.checkin

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CheckInModule {

    @Binds
    @IntoSet
    abstract fun bindCheckInFeature(impl: CheckInFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindWalkInStore(impl: FirestoreWalkInStore): WalkInStore

    @Binds
    @Singleton
    abstract fun bindConnectivity(impl: AndroidConnectivity): Connectivity
}
