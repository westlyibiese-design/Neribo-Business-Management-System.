package com.westly.nbms.features.reservations

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReservationsRepositoryModule {
    @Binds
    abstract fun bindReservationsService(impl: ReservationsRepository): ReservationsService
}
