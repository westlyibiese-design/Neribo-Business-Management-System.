package com.westly.nbms.features.reservations

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ReservationsModule {

    @Binds
    @IntoSet
    abstract fun bindReservationsFeature(impl: ReservationsFeature): NbmsFeature

    @Binds
    abstract fun bindReservationsSource(impl: FirestoreReservationsSource): ReservationsSource

    @Binds
    abstract fun bindReservationsNetwork(impl: AndroidReservationsNetwork): ReservationsNetwork
}
