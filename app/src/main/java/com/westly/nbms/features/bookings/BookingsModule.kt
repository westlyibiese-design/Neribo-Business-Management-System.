package com.westly.nbms.features.bookings

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BookingsModule {

    @Binds
    @IntoSet
    abstract fun bindBookingsFeature(impl: BookingsFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindBookingStore(impl: FirestoreBookingStore): BookingStore

    /** Phase 14 adds its Extend Stay dialog with @IntoSet. Declared here so the set exists, empty, until then. */
    @Multibinds
    abstract fun extendStayLaunchers(): Set<ExtendStayLauncher>
}
